package de.spahr.ausgaben.export;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.net.RemoteStorage;
import de.spahr.ausgaben.settings.SettingsStore;
import de.spahr.ausgaben.util.ProgressListener;

/**
 * Aktualisiert vorhandene Konten aus der .kmy: Datei laden, Buchungen der Zielkonten bauen und in der
 * Datenbank ersetzen. Neue Konten legt der Import bewusst nicht an – dafür gibt es „Konto hinzufügen".
 *
 * <p>Die Oberfläche liefert nur die Rückmeldungen ({@link Ui}); Banner, Abfragen und Meldungen bleiben
 * Sache der jeweiligen Ansicht. So können Konto- und Depotansicht denselben Import anstoßen.</p>
 */
public final class KmyAccountImport {

    /**
     * Rückmeldungen an die Oberfläche. {@link #phase} und {@link #noMatchingAccount}/{@link #failed}
     * kommen aus einem <b>Hintergrund-Thread</b> – die Umsetzung muss selbst auf den Main-Thread
     * wechseln. Nur {@link #finished()} läuft bereits im Main-Thread.
     */
    public interface Ui {
        /** Fortschritts-Empfänger für eine Phase (bildet auf {@code from..to} Prozent ab). */
        ProgressListener phase(String label, int from, int to);

        /** Keines der gewünschten Konten steht in der .kmy. */
        void noMatchingAccount();

        void failed(Exception e);

        void finished();
    }

    // Schlüssel der Phasen im ImportBudget.
    public static final String BOOKINGS_READ = "bookings.read";
    public static final String BOOKINGS_WRITE = "bookings.write";
    public static final String SCHEDULES = "schedules";

    public static String depotRead(String depot) {
        return "depot.read." + depot;
    }

    public static String depotWrite(String depot) {
        return "depot.write." + depot;
    }

    private KmyAccountImport() {
    }

    /**
     * Teilt den Fortschritt hinter dem Lesen der Datei nach der gemessenen Arbeitsmenge auf. Die Zahl
     * der zu schreibenden Buchungen steht hier noch nicht fest – dafür gibt es später
     * {@link ImportBudget#resize}.
     */
    public static ImportBudget budgetFor(KmyImporter importer, int accountCount, List<String> depots,
                                         boolean schedules) {
        ImportBudget budget = new ImportBudget();
        int transactions = importer.transactionCount();
        if (accountCount > 0) {
            budget.add(BOOKINGS_READ, transactions * ImportBudget.BOOKING_READ);
            budget.add(BOOKINGS_WRITE, transactions * ImportBudget.BOOKING_WRITE);
        }
        for (String depot : depots) {
            // Das Depot liest das Hauptbuch einmal ganz durch und schreibt dann seine Kurshistorie.
            budget.add(depotRead(depot), transactions * ImportBudget.DEPOT_READ);
            budget.add(depotWrite(depot), importer.priceCount(depot) * ImportBudget.PRICE_WRITE);
        }
        if (schedules) {
            budget.add(SCHEDULES, ImportBudget.SCHEDULES);
        }
        return budget;
    }

    /**
     * Startet den Import im Hintergrund.
     *
     * @param knownAccounts in der App vorhandene Konten (Grundlage für „alle Konten")
     * @param account       einzelnes Konto oder {@code null} für alle vorhandenen Konten
     * @param depots        zusätzlich zu aktualisierende Depots (leer/{@code null} = keine)
     * @param schedules     zusätzlich die geplanten Buchungen neu einlesen
     */
    public static void start(Context context, SettingsStore settings, Repository repository,
                             List<String> knownAccounts, final String account,
                             final List<String> depots, final boolean schedules, final Ui ui) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                String path = settings.getKmyPath();
                byte[] raw = RemoteStorage.from(settings).downloadBytes(folderOf(path), fileOf(path),
                        ui.phase(app.getString(R.string.import_stage_download),
                                ImportPhase.DOWNLOAD_FROM, ImportPhase.DOWNLOAD_TO));
                importRaw(app, repository, raw, knownAccounts, account, depots, schedules, ui);
            } catch (Exception e) {
                ui.failed(e);
            }
        }).start();
    }

    /**
     * Wie {@link #start}, aber mit einer schon geladenen Datei – für den Export, der die eben
     * geschriebene Datei ohnehin zurückliest und sie nicht ein zweites Mal holen soll. Läuft auf einem
     * eigenen Thread; {@code ui} wird wie bei {@link #start} bedient.
     */
    public static void startWithBytes(Context context, Repository repository, final byte[] raw,
                                      List<String> knownAccounts, final List<String> depots,
                                      final boolean schedules, final Ui ui) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                importRaw(app, repository, raw, knownAccounts, null, depots, schedules, ui);
            } catch (Exception e) {
                ui.failed(e);
            }
        }).start();
    }

    /** Alles hinter dem Laden: Datei aufbereiten, Konten, Depots und Planungen einlesen. */
    private static void importRaw(Context app, Repository repository, byte[] raw,
                                  List<String> knownAccounts, String account, List<String> depots,
                                  boolean schedules, Ui ui) throws Exception {
        KmyImporter importer = new KmyImporter(
                new KmyDocument(raw, app,
                        ui.phase(app.getString(R.string.import_stage_reading),
                                ImportPhase.READ_FILE_FROM, ImportPhase.READ_FILE_TO)),
                app);
        // Die Stichwortliste der Datei: nur was dort steht, ist in der App wählbar.
        repository.replaceTags(importer.tagNames());
        List<String> available = importer.accountNames();
        List<String> targets = new ArrayList<>();
        if (account == null) {
            // Nur bereits vorhandene App-Konten, die es auch in der .kmy gibt.
            for (String acc : knownAccounts) {
                if (containsIgnoreCase(available, acc)) {
                    targets.add(acc);
                }
            }
        } else if (containsIgnoreCase(available, account)) {
            targets.add(account);
        }
        final List<String> depotTargets = new ArrayList<>();
        if (depots != null) {
            for (String d : depots) {
                if (containsIgnoreCase(importer.depotNames(), d)) {
                    depotTargets.add(d);
                }
            }
        }
        if (targets.isEmpty() && depotTargets.isEmpty() && !schedules) {
            ui.noMatchingAccount();
            return;
        }
        // Jetzt stehen die Mengen fest – daraus ergeben sich die Prozentbereiche des Laufs.
        final ImportBudget budget = budgetFor(importer, targets.isEmpty() ? 0 : 1,
                depotTargets, schedules);
        if (targets.isEmpty()) {
            // Nur Depots und/oder Planungen – die Buchungsphase entfällt.
            afterAccounts(app, repository, importer, budget, depotTargets, schedules, ui);
            return;
        }
        // Ein Lesedurchlauf für ALLE Konten (vorher: einer je Konto über die ganze Datei).
        LinkedHashMap<String, List<Booking>> map = importer.bookingsForAccounts(targets,
                ui.phase(app.getString(R.string.import_stage_bookings),
                        budget.from(BOOKINGS_READ), budget.to(BOOKINGS_READ)));
        for (String acc : targets) {
            // Währungskennzeichen aus der KMyMoney-Datei je Konto übernehmen.
            repository.setAccountCurrency(acc, importer.currencyOf(acc));
        }
        // Anlage/Verbindlichkeit bzw. Einnahme/Ausgabe für ALLE Konten und Kategorien der .kmy
        // klassifizieren, nicht nur für die aktualisierten.
        repository.applyAccountTypes(importer.accountTypes());
        repository.applyCategoryTypes(importer.categoryTypes());
        // Kontengruppen aus der Datei nachziehen: Banken aus dem Institutsblock, „Favoriten"
        // aus den bevorzugten Konten.
        repository.applyFileGroups(importer.institutions(), importer.favorites(),
                app.getString(R.string.accounts_group_favorites));
        // Jetzt ist die wirkliche Zahl der Buchungen bekannt – vorher war sie geschätzt.
        int written = 0;
        for (List<Booking> l : map.values()) {
            written += l.size();
        }
        budget.resize(BOOKINGS_WRITE, written * ImportBudget.BOOKING_WRITE);
        // Kein separates „Buchungen werden gespeichert" beim Konto-Aktualisieren – nur die
        // laufende Phase weiterzählen.
        repository.replaceImportAccounts(map,
                ui.phase(app.getString(R.string.import_running_banner),
                        budget.from(BOOKINGS_WRITE), budget.to(BOOKINGS_WRITE)),
                res -> afterAccounts(app, repository, importer, budget, depotTargets,
                        schedules, ui));
    }

    /**
     * Nach den Konten: Depots der Reihe nach, danach die geplanten Buchungen – alles aus derselben,
     * bereits geladenen Datei. Läuft rekursiv über die Rückrufe des Repositorys (Main-Thread), die
     * eigentliche Arbeit jeweils in einem Hintergrund-Thread.
     */
    private static void afterAccounts(Context app, Repository repository, KmyImporter importer,
                                      ImportBudget budget, List<String> depots, boolean schedules,
                                      Ui ui) {
        if (!depots.isEmpty()) {
            final String depot = depots.get(0);
            final List<String> rest = new ArrayList<>(depots.subList(1, depots.size()));
            final String label = app.getString(R.string.import_stage_depot, depot);
            final ProgressListener readListener = ui.phase(label,
                    budget.from(depotRead(depot)), budget.to(depotRead(depot)));
            final ProgressListener writeListener = ui.phase(label,
                    budget.from(depotWrite(depot)), budget.to(depotWrite(depot)));
            new Thread(() -> {
                try {
                    KmyImporter.DepotData data = importer.importDepot(depot, readListener);
                    repository.replaceDepotImport(depot, data.securities, data.transactions, data.prices,
                            writeListener,
                            () -> afterAccounts(app, repository, importer, budget, rest, schedules, ui));
                } catch (Exception e) {
                    ui.failed(e);
                }
            }).start();
            return;
        }
        if (schedules) {
            ui.phase(app.getString(R.string.import_stage_scheduled),
                    budget.from(SCHEDULES), budget.to(SCHEDULES)).onProgress(0, 1);
            new Thread(() -> {
                try {
                    repository.applyScheduledTransactions(importer.scheduledTransactions(),
                            () -> repository.reopenAccountsWithBalance(ui::finished));
                } catch (Exception e) {
                    ui.failed(e);
                }
            }).start();
            return;
        }
        // Zum Schluss: geschlossene Konten, die wieder einen Saldo haben, öffnen sich von selbst.
        repository.reopenAccountsWithBalance(ui::finished);
    }

    private static boolean containsIgnoreCase(List<String> list, String name) {
        for (String s : list) {
            if (s.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    static String folderOf(String path) {
        String p = path.trim();
        int slash = p.lastIndexOf('/');
        return slash < 0 ? "" : p.substring(0, slash);
    }

    static String fileOf(String path) {
        String p = path.trim();
        int slash = p.lastIndexOf('/');
        return slash < 0 ? p : p.substring(slash + 1);
    }
}
