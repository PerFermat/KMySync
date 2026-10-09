package de.spahr.ausgaben.ui;

import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.export.CsvImporter;
import de.spahr.ausgaben.export.KmyDocument;
import de.spahr.ausgaben.export.KmyImporter;
import de.spahr.ausgaben.net.RemotePath;
import de.spahr.ausgaben.net.RemoteStorage;
import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Das Einlesen aus der Buchungsliste: Konten, Depots und geplante Buchungen aus der .kmy (langer Tipp in
 * der Schublade, „Neues Konto hinzufügen", Wischen nach unten) und der CSV-Import (Datei-Browser auf dem
 * Server oder lokaler Picker). Ausgelagert aus {@link MainActivity}, um deren Umfang zu verringern – der
 * Code ist dabei unverändert umgezogen.
 *
 * <p>Registriert den Activity-Result-Launcher des lokalen Pickers im Konstruktor und muss deshalb in
 * {@code onCreate} entstehen (vor STARTED). Die Kontenlisten gehören weiter der Maske; dieser Ablauf
 * liest sie nur, um schon Vorhandenes im Auswahldialog auszublenden.</p>
 */
class MainImportFlow {

    /** Schlüssel und Angaben des Datei-Browsers – siehe {@link HostedDialog}. */
    static final String DLG_CSV_PICK = "dlg_csvPick";
    private static final String ARG_CSV_FOLDER = "a_csvFolder";
    private static final String ARG_CSV_FOLDERS = "a_csvFolders";
    private static final String ARG_CSV_FILES = "a_csvFiles";

    /** Was der Ablauf von der Buchungsliste braucht. */
    interface Host {
        /** Der blockierende Fortschrittsdialog der Maske (teilt ihn mit dem Export). */
        void showProgress(String text);

        void dismissProgress();

        /** Import fertig – Liste neu laden. */
        void refreshBookings();
    }

    private final AppCompatActivity activity;
    private final SettingsStore settings;
    private final Repository repository;
    private final ImportBanner importBanner;
    /** Aktuell in der App vorhandene Konten (für „Alle Konten aktualisieren"). */
    private final List<String> appAccounts;
    /** Alle bereits importierten Konten inkl. geschlossener – zum Ausblenden im Import-Auswahldialog. */
    private final List<String> importedAccounts;
    /** Bereits importierte Depots – um sie im Import-Auswahldialog auszublenden. */
    private final List<String> appDepots;
    private final Host host;
    private final ActivityResultLauncher<String[]> importLauncher;

    MainImportFlow(AppCompatActivity activity, SettingsStore settings, Repository repository,
                   ImportBanner importBanner, List<String> appAccounts, List<String> importedAccounts,
                   List<String> appDepots, Host host) {
        this.activity = activity;
        this.settings = settings;
        this.repository = repository;
        this.importBanner = importBanner;
        this.appAccounts = appAccounts;
        this.importedAccounts = importedAccounts;
        this.appDepots = appDepots;
        this.host = host;
        importLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri != null) {
                        doImportLocal(uri);
                    }
                });
    }

    // ---- Import (Schublade: langer Tipp = importieren) ----

    /** Langer Tipp auf ein Konto (bzw. „Alle Konten") in der Schublade. */
    void onImportRequested(String account, boolean isAll) {
        if (!settings.isKmySource()) {
            startCsvImport();
            return;
        }
        if (!settings.hasRemoteConfig()) {
            Toast.makeText(activity, R.string.export_no_config, Toast.LENGTH_LONG).show();
            return;
        }
        if (settings.getKmyPath().isEmpty()) {
            Toast.makeText(activity, R.string.kmy_path_missing, Toast.LENGTH_LONG).show();
            return;
        }
        // Ohne Rückfrage: die KMyMoney-Datei ist führend.
        runKmyImport(isAll ? null : account);
    }

    /** „Neues Konto hinzufügen": lädt die .kmy und zeigt den Konto-Auswahldialog. */
    void onAddAccountClicked() {
        if (!settings.isKmySource()) {
            startCsvImport();
            return;
        }
        if (!settings.hasRemoteConfig()) {
            Toast.makeText(activity, R.string.export_no_config, Toast.LENGTH_LONG).show();
            return;
        }
        String path = settings.getKmyPath();
        if (path.isEmpty()) {
            Toast.makeText(activity, R.string.kmy_path_missing, Toast.LENGTH_LONG).show();
            return;
        }
        host.showProgress(activity.getString(R.string.progress_download));
        new Thread(() -> {
            try {
                byte[] raw = RemoteStorage.from(settings).downloadBytes(RemotePath.folderOf(path), RemotePath.fileOf(path));
                KmyImporter importer = new KmyImporter(
                        new KmyDocument(raw, activity.getApplicationContext()), activity.getApplicationContext());
                // Stichwortliste der Datei übernehmen – nur was dort steht, ist in der App wählbar.
                // (Wie beim Aktualisieren/Export; sonst fehlten die Stichwörter nach dem Neuimport.)
                repository.replaceTags(importer.tagNames());
                Ui.post(activity, () -> {
                    host.dismissProgress();
                    List<String> accounts = importer.accountNames();
                    List<String> depots = importer.depotNames();
                    if (accounts.isEmpty() && depots.isEmpty()) {
                        Toast.makeText(activity, R.string.kmy_no_files, Toast.LENGTH_LONG).show();
                    } else {
                        chooseAccountForImport(importer, accounts, depots);
                    }
                });
            } catch (Exception e) {
                postImportError(e);
            }
        }).start();
    }

    /**
     * Auswahl-Dialog mit Mehrfachauswahl: mehrere Konten (und/oder Depots) auf einmal importieren.
     * Bereits importierte Konten (in der App vorhanden) werden ausgeblendet; Depots mit „(Depot)" markiert.
     */
    private void chooseAccountForImport(KmyImporter importer, List<String> accounts, List<String> depots) {
        // Bereits vorhandene App-Konten/Depots ausblenden – nur noch nicht importierte anbieten.
        // Konten inkl. geschlossener (importedAccounts), damit auch geschlossene nicht erneut erscheinen.
        final List<String> newAccounts = new ArrayList<>();
        for (String a : accounts) {
            if (!MainActivity.containsIgnoreCase(importedAccounts, a)) {
                newAccounts.add(a);
            }
        }
        final List<String> newDepots = new ArrayList<>();
        for (String d : depots) {
            if (!MainActivity.containsIgnoreCase(appDepots, d)) {
                newDepots.add(d);
            }
        }
        List<String> labels = new ArrayList<>(newAccounts);
        for (String d : newDepots) {
            labels.add(activity.getString(R.string.kmy_choose_depot, d));
        }
        if (labels.isEmpty()) {
            Toast.makeText(activity, R.string.kmy_no_new_accounts, Toast.LENGTH_LONG).show();
            return;
        }
        final int accountCount = newAccounts.size();
        final boolean[] checked = new boolean[labels.size()];
        String[] items = labels.toArray(new String[0]);
        new AppDialog(activity)
                .setTitle(R.string.kmy_choose_account)
                .setMultiChoiceItems(items, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.kmy_import_selected, (d, w) -> {
                    List<String> accountTargets = new ArrayList<>();
                    List<String> depotTargets = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) {
                        if (!checked[i]) {
                            continue;
                        }
                        if (i < accountCount) {
                            accountTargets.add(newAccounts.get(i));
                        } else {
                            depotTargets.add(newDepots.get(i - accountCount));
                        }
                    }
                    if (accountTargets.isEmpty() && depotTargets.isEmpty()) {
                        return; // nichts angehakt
                    }
                    startBatchImport(importer, accountTargets, depotTargets);
                })
                .show();
    }

    /**
     * Importiert die gewählten Konten als Batch und anschließend die gewählten Depots nacheinander.
     * Läuft komplett im Hintergrund – die Oberfläche bleibt bedienbar; nur bei einem Fehler kommt eine
     * Meldung, am Ende wird die Liste still aktualisiert.
     */
    private void startBatchImport(KmyImporter importer, List<String> accountTargets,
                                  List<String> depotTargets) {
        if (ExportBand.blocksImport(activity)) {
            return;
        }
        importBanner.start(activity.getString(R.string.import_running_banner));
        // Die Mengen stehen fest – daraus ergeben sich die Prozentbereiche dieses Laufs.
        final de.spahr.ausgaben.export.ImportBudget budget =
                de.spahr.ausgaben.export.KmyAccountImport.budgetFor(importer, accountTargets.size(),
                        depotTargets, false);
        new Thread(() -> {
            try {
                if (accountTargets.isEmpty()) {
                    Ui.post(activity, () -> importDepotsThenFinish(importer, budget, depotTargets));
                    return;
                }
                // Ein Lesedurchlauf für ALLE Konten (vorher: einer je Konto über die ganze Datei).
                java.util.LinkedHashMap<String, List<Booking>> map = importer.bookingsForAccounts(
                        accountTargets, importBanner.phase(activity.getString(R.string.import_stage_bookings),
                                budget.from(de.spahr.ausgaben.export.KmyAccountImport.BOOKINGS_READ),
                                budget.to(de.spahr.ausgaben.export.KmyAccountImport.BOOKINGS_READ)));
                for (String acc : accountTargets) {
                    repository.setAccountCurrency(acc, importer.currencyOf(acc));
                }
                // Konto- und Kategorietypen für ALLE Konten/Kategorien der .kmy übernehmen.
                repository.applyAccountTypes(importer.accountTypes());
                repository.applyCategoryTypes(importer.categoryTypes());
                int written = 0;
                for (List<Booking> l : map.values()) {
                    written += l.size();
                }
                budget.resize(de.spahr.ausgaben.export.KmyAccountImport.BOOKINGS_WRITE,
                        written * de.spahr.ausgaben.export.ImportBudget.BOOKING_WRITE);
                Ui.post(activity, () -> repository.replaceImportAccounts(map,
                        importBanner.phase(activity.getString(R.string.import_stage_saving),
                                budget.from(de.spahr.ausgaben.export.KmyAccountImport.BOOKINGS_WRITE),
                                budget.to(de.spahr.ausgaben.export.KmyAccountImport.BOOKINGS_WRITE)),
                        res -> importDepotsThenFinish(importer, budget, depotTargets)));
            } catch (Exception e) {
                postImportError(e);
            }
        }).start();
    }

    /** Importiert die Depots der Reihe nach; am Ende Banner auf 100 % und Liste aktualisieren. */
    private void importDepotsThenFinish(KmyImporter importer,
                                        de.spahr.ausgaben.export.ImportBudget budget,
                                        List<String> depots) {
        if (depots.isEmpty()) {
            completeImport();
            return;
        }
        final String depot = depots.get(0);
        final List<String> rest = new ArrayList<>(depots.subList(1, depots.size()));
        final String label = activity.getString(R.string.import_stage_depot, depot);
        final String lesen = de.spahr.ausgaben.export.KmyAccountImport.depotRead(depot);
        final String schreiben = de.spahr.ausgaben.export.KmyAccountImport.depotWrite(depot);
        final de.spahr.ausgaben.util.ProgressListener readListener =
                importBanner.phase(label, budget.from(lesen), budget.to(lesen));
        final de.spahr.ausgaben.util.ProgressListener writeListener =
                importBanner.phase(label, budget.from(schreiben), budget.to(schreiben));
        new Thread(() -> {
            try {
                KmyImporter.DepotData data = importer.importDepot(depot, readListener);
                repository.replaceDepotImport(depot, data.securities, data.transactions, data.prices,
                        writeListener, () -> importDepotsThenFinish(importer, budget, rest));
            } catch (Exception e) {
                postImportError(e);
            }
        }).start();
    }

    /**
     * Langer Tipp in der Schublade: lädt die .kmy und aktualisiert genau dieses Depot – im Hintergrund
     * mit dem gelben Fortschrittsbanner; die Oberfläche bleibt bedienbar, nur bei Fehlern kommt eine Meldung.
     */
    void reimportDepot(String depotName) {
        if (!settings.isKmySource()) {
            Toast.makeText(activity, R.string.export_no_config, Toast.LENGTH_LONG).show();
            return;
        }
        if (!settings.hasRemoteConfig()) {
            Toast.makeText(activity, R.string.export_no_config, Toast.LENGTH_LONG).show();
            return;
        }
        final String path = settings.getKmyPath();
        if (path.isEmpty()) {
            Toast.makeText(activity, R.string.kmy_path_missing, Toast.LENGTH_LONG).show();
            return;
        }
        if (ExportBand.blocksImport(activity)) {
            return;
        }
        importBanner.start(activity.getString(R.string.import_running_banner));
        new Thread(() -> {
            try {
                byte[] raw = RemoteStorage.from(settings).downloadBytes(RemotePath.folderOf(path), RemotePath.fileOf(path),
                        importBanner.phase(activity.getString(R.string.import_stage_download),
                                de.spahr.ausgaben.export.ImportPhase.DOWNLOAD_FROM,
                                de.spahr.ausgaben.export.ImportPhase.DOWNLOAD_TO));
                KmyImporter importer = new KmyImporter(
                        new KmyDocument(raw, activity.getApplicationContext(),
                                importBanner.phase(activity.getString(R.string.import_stage_reading),
                                        de.spahr.ausgaben.export.ImportPhase.READ_FILE_FROM,
                                        de.spahr.ausgaben.export.ImportPhase.READ_FILE_TO)),
                        activity.getApplicationContext());
                // Nur dieses eine Depot: ihm gehört der ganze Rest des Balkens.
                final String label = activity.getString(R.string.import_stage_depot, depotName);
                final de.spahr.ausgaben.export.ImportBudget budget =
                        de.spahr.ausgaben.export.KmyAccountImport.budgetFor(importer, 0,
                                java.util.Collections.singletonList(depotName), false);
                final String lesen = de.spahr.ausgaben.export.KmyAccountImport.depotRead(depotName);
                final String schreiben = de.spahr.ausgaben.export.KmyAccountImport.depotWrite(depotName);
                KmyImporter.DepotData data = importer.importDepot(depotName,
                        importBanner.phase(label, budget.from(lesen), budget.to(lesen)));
                final de.spahr.ausgaben.util.ProgressListener writeListener =
                        importBanner.phase(label, budget.from(schreiben), budget.to(schreiben));
                Ui.post(activity, () -> repository.replaceDepotImport(depotName, data.securities,
                        data.transactions, data.prices, writeListener, this::completeImport));
            } catch (Exception e) {
                postImportError(e);
            }
        }).start();
    }

    /** Lädt die .kmy und importiert ein Konto ({@code null} = alle bereits vorhandenen App-Konten). */
    void runKmyImport(final String account) {
        if (ExportBand.blocksImport(activity)) {
            return;
        }
        importBanner.start(activity.getString(R.string.import_running_banner));
        // „Alle Konten" (account == null) heißt: Konten, Depots und geplante Buchungen in einem Zug.
        de.spahr.ausgaben.export.KmyAccountImport.start(activity, settings, repository, appAccounts, account,
                account == null ? appDepots : java.util.Collections.emptyList(), account == null,
                new de.spahr.ausgaben.export.KmyAccountImport.Ui() {
                    @Override
                    public de.spahr.ausgaben.util.ProgressListener phase(String label, int from, int to) {
                        return importBanner.phase(label, from, to);
                    }

                    @Override
                    public void noMatchingAccount() {
                        Ui.post(activity, () -> {
                            importBanner.finishNow();
                            Toast.makeText(activity, R.string.kmy_account_not_found,
                                    Toast.LENGTH_LONG).show();
                        });
                    }

                    @Override
                    public void failed(Exception e) {
                        postImportError(e);
                    }

                    @Override
                    public void finished() {
                        completeImport();
                    }
                });
    }

    private void postImportError(Exception e) {
        final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        Ui.post(activity, () -> {
            host.dismissProgress();
            importBanner.finishNow();
            Toast.makeText(activity, activity.getString(R.string.import_failed, msg), Toast.LENGTH_LONG).show();
        });
    }

    /** Import abgeschlossen: 100 % kurz zeigen, dann Banner ausblenden und Liste aktualisieren. */
    private void completeImport() {
        importBanner.finish();
        host.refreshBookings();
    }

    // ---- CSV-Import (Nextcloud-Liste oder lokaler Picker) ----

    void startCsvImport() {
        if (settings.hasRemoteConfig()) {
            browseCsvAt(settings.getImportFolder());
        } else {
            importLauncher.launch(new String[]{
                    "text/*", "text/csv", "text/comma-separated-values", "application/octet-stream"});
        }
    }

    /** Navigierbarer CSV-Browser (Unterordner + CSV-Dateien) im entfernten Importordner. */
    private void browseCsvAt(String folder) {
        Toast.makeText(activity, R.string.loading_files, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                // Ordner und Dateien in einem Aufruf: SMB meldet sich sonst zweimal hintereinander an.
                RemoteStorage.Entries entries = RemoteStorage.from(settings).listEntries(folder, "csv");
                List<String> folders = entries.folders;
                List<String> files = entries.files;
                java.util.Collections.sort(folders, String.CASE_INSENSITIVE_ORDER);
                java.util.Collections.sort(files, String.CASE_INSENSITIVE_ORDER);
                Ui.post(activity, () -> {
                    if (folder.isEmpty() && folders.isEmpty() && files.isEmpty()) {
                        Toast.makeText(activity, R.string.no_files, Toast.LENGTH_LONG).show();
                    } else {
                        showCsvPick(folder, folders, files);
                    }
                });
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                Ui.post(activity, () -> Toast.makeText(activity,
                        activity.getString(R.string.import_failed, msg), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void showCsvPick(String folder, List<String> folders, List<String> files) {
        Bundle args = new Bundle();
        args.putString(ARG_CSV_FOLDER, folder);
        args.putStringArray(ARG_CSV_FOLDERS, folders.toArray(new String[0]));
        args.putStringArray(ARG_CSV_FILES, files.toArray(new String[0]));
        HostedDialog.show(activity, DLG_CSV_PICK, args);
    }

    /**
     * Baut den Datei-Browser aus dem, was im Bundle steht — beim ersten Mal und nach jeder Drehung.
     * Der Serverzugriff bleibt dabei aus: Die Liste dieses Ordners steht schon in den Angaben.
     */
    android.app.Dialog buildCsvPick(Bundle args) {
        final String folder = args.getString(ARG_CSV_FOLDER, "");
        final List<String> labels = new java.util.ArrayList<>();
        final List<Runnable> actions = new java.util.ArrayList<>();
        if (!folder.isEmpty()) {
            labels.add("↑  ..");
            actions.add(() -> browseCsvAt(RemotePath.parentFolder(folder)));
        }
        for (String d : args.getStringArray(ARG_CSV_FOLDERS)) {
            labels.add("📁  " + d);
            final String target = folder.isEmpty() ? d : folder + "/" + d;
            actions.add(() -> browseCsvAt(target));
        }
        for (String f : args.getStringArray(ARG_CSV_FILES)) {
            labels.add(f);
            actions.add(() -> downloadAndImport(folder, f));
        }
        String title = folder.isEmpty() ? activity.getString(R.string.choose_import_file) : "/" + folder;
        return new AppDialog(activity)
                .setTitle(title)
                .setItems(labels.toArray(new String[0]), (d, w) -> actions.get(w).run())
                .create();
    }

    private void downloadAndImport(String folder, String fileName) {
        // Ohne das Banner sah ein CSV-Reimport nach nichts aus – anders als der KMY-Reimport
        // (reimportDepot/runKmyImport), der immer schon importBanner.start()/finish() nutzt.
        if (ExportBand.blocksImport(activity)) {
            return;
        }
        importBanner.start(activity.getString(R.string.import_running_banner));
        // Bewußt ein eigener Faden und nicht repository.executor(): der ist einfach besetzt
        // (newSingleThreadExecutor) und trägt die gesamte Datenbankarbeit. Ein hängender Server
        // würde dort jede andere Abfrage der App mit blockieren, bis der Timeout greift.
        new Thread(() -> {
            try {
                String content = RemoteStorage.from(settings).downloadText(folder, fileName);
                processImport(content);
            } catch (Exception e) {
                importFehlgeschlagen(e);
            }
        }).start();
    }

    private void doImportLocal(Uri uri) {
        if (ExportBand.blocksImport(activity)) {
            return;
        }
        importBanner.start(activity.getString(R.string.import_running_banner));
        new Thread(() -> {
            try {
                processImport(readText(uri));
            } catch (Exception e) {
                importFehlgeschlagen(e);
            }
        }).start();
    }

    /** Parst den Inhalt und ersetzt die exportierten Buchungen des Kontos. Aufruf aus Hintergrund-Thread. */
    private void processImport(String content) {
        try {
            CsvImporter importer = new CsvImporter(activity);
            List<Booking> bookings = importer.parse(content);
            String account = importer.getParsedAccount();
            Ui.post(activity, () -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                repository.replaceImport(account, bookings, count -> {
                    importBanner.finish();
                    Toast.makeText(activity, activity.getString(R.string.import_done, count), Toast.LENGTH_LONG).show();
                    host.refreshBookings();
                });
            });
        } catch (Exception e) {
            importFehlgeschlagen(e);
        }
    }

    /**
     * Meldet einen gescheiterten Import auf dem Bedienfaden. Der Import läuft im Hintergrund weiter,
     * auch wenn der Nutzer die Maske inzwischen verlassen hat – am geschlossenen Fenster darf dann
     * weder das Banner noch ein Toast mehr angefaßt werden.
     */
    private void importFehlgeschlagen(Exception e) {
        final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        Ui.post(activity, () -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            importBanner.finishNow();
            Toast.makeText(activity, activity.getString(R.string.import_failed, msg), Toast.LENGTH_LONG).show();
        });
    }

    private String readText(Uri uri) throws Exception {
        try (InputStream is = activity.getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (is != null && (n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
