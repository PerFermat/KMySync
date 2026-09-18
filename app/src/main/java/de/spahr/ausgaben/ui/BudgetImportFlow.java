package de.spahr.ausgaben.ui;

import de.spahr.ausgaben.net.RemotePath;
import android.app.Activity;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;


import java.util.ArrayList;
import java.util.List;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Budget;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.export.KmyDocument;
import de.spahr.ausgaben.export.KmyImporter;
import de.spahr.ausgaben.net.RemoteStorage;
import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Lädt die konfigurierte KMyMoney-Datei und speichert deren Budgetplanung als Soll des Zieljahres
 * (Herkunft {@code "kmy"}, nicht editierbar). Bei mehreren Budgetjahren fragt ein Dialog nach.
 * Wird von {@link BudgetActivity} (Leerzustand) und {@link SettingsActivity} genutzt.
 */
final class BudgetImportFlow {

    private BudgetImportFlow() {
    }

    /** Führt den Import aus; {@code onDone} läuft auf dem UI-Thread bei Erfolg. */
    static void run(Activity activity, SettingsStore settings, Repository repository,
                    int targetYear, Runnable onDone) {
        if (!settings.isKmyMode() || !settings.hasRemoteConfig()) {
            Toast.makeText(activity, R.string.export_no_config, Toast.LENGTH_LONG).show();
            return;
        }
        final String path = settings.getKmyPath();
        if (path.trim().isEmpty()) {
            Toast.makeText(activity, R.string.kmy_path_missing, Toast.LENGTH_LONG).show();
            return;
        }

        ProgressBar bar = new ProgressBar(activity);
        AlertDialog progress = new AppDialog(activity)
                .setTitle(R.string.progress_download)
                .setView(bar)
                .setCancelable(false)
                .create();
        progress.show();

        new Thread(() -> {
            try {
                byte[] raw = RemoteStorage.from(settings).downloadBytes(RemotePath.folderOf(path), RemotePath.fileOf(path));
                KmyImporter importer = new KmyImporter(
                        new KmyDocument(raw, activity.getApplicationContext()),
                        activity.getApplicationContext());
                // Kategorietyp (Einnahme/Ausgabe) aus der Datei übernehmen – verlässliche Budget-Einordnung.
                repository.applyCategoryTypes(importer.categoryTypes());
                List<Integer> years = importer.budgetYears();
                Ui.post(activity, () -> {
                    progress.dismiss();
                    if (years.isEmpty()) {
                        Toast.makeText(activity, R.string.budget_import_none, Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (years.size() == 1) {
                        store(activity, repository, targetYear, importer, years.get(0), onDone);
                    } else {
                        String[] labels = new String[years.size()];
                        for (int i = 0; i < years.size(); i++) {
                            labels[i] = String.valueOf(years.get(i));
                        }
                        new AppDialog(activity)
                                .setTitle(R.string.budget_import)
                                .setItems(labels, (d, which) ->
                                        store(activity, repository, targetYear, importer,
                                                years.get(which), onDone))
                                .show();
                    }
                });
            } catch (Exception e) {
                // Grund mitgeben: import_failed trägt ein %1$s – ohne getString(...) stünde der
                // Platzhalter wörtlich in der Meldung.
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                Ui.post(activity, () -> {
                    progress.dismiss();
                    Toast.makeText(activity, activity.getString(R.string.import_failed, msg),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private static void store(Activity activity, Repository repository, int targetYear,
                              KmyImporter importer, int sourceYear, Runnable onDone) {
        List<Budget> lines = new ArrayList<>();
        for (KmyDocument.BudgetEntry e : importer.budgetEntries(sourceYear)) {
            if (e.monthlyCents != null) {
                // Monatsgenaues Budget (monthbymonth): je Monat mit Betrag eine Zeile (month 1–12).
                for (int m = 0; m < 12; m++) {
                    if (e.monthlyCents[m] != 0) {
                        lines.add(new Budget(targetYear, m + 1, e.category, e.isIncome,
                                e.monthlyCents[m], Budget.SOURCE_KMY));
                    }
                }
            } else {
                // Jahres-Soll (yearly/monthly): eine Zeile month=0 (Monatssicht = /12).
                lines.add(new Budget(targetYear, e.category, e.isIncome, e.yearlyCents, Budget.SOURCE_KMY));
            }
        }
        repository.replaceBudget(targetYear, Budget.SOURCE_KMY, lines, () -> {
            Toast.makeText(activity, R.string.budget_import_done, Toast.LENGTH_LONG).show();
            if (onDone != null) {
                onDone.run();
            }
        });
    }

}
