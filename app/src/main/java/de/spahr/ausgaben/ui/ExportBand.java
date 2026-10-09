package de.spahr.ausgaben.ui;

import android.app.Activity;
import android.widget.Toast;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.export.KmyExportRun;
import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Bindet das Fortschrittsband einer Ansicht an den Export-Lauf der App ({@link KmyExportRun}) –
 * einmal für alle Ansichten, die ein Band haben. Der Lauf gehört nicht der Ansicht: wer sichtbar
 * wird, hängt sich an und zeigt den Stand; wer verschwindet, löst sich wieder.
 */
final class ExportBand implements KmyExportRun.Observer {

    private final Activity activity;
    private final ImportBanner banner;
    private final Runnable onChanged;
    private boolean shown;

    /** @param onChanged lädt die Listen der Ansicht neu, nachdem der Lauf Buchungen geändert hat */
    ExportBand(Activity activity, ImportBanner banner, Runnable onChanged) {
        this.activity = activity;
        this.banner = banner;
        this.onChanged = onChanged;
    }

    /** Aus {@code onStart} der Ansicht. */
    void attach() {
        KmyExportRun.attach(this);
    }

    /** Aus {@code onStop} der Ansicht. */
    void detach() {
        KmyExportRun.detach(this);
        if (shown) {
            shown = false;
            banner.finishNow();
        }
    }

    /**
     * Startet den Export, wenn nichts dagegen spricht – sonst sagt ein Hinweis, was gerade läuft.
     */
    void start(Repository repository, SettingsStore settings) {
        if (KmyExportRun.isRunning()) {
            Toast.makeText(activity, R.string.export_busy, Toast.LENGTH_LONG).show();
            return;
        }
        if (banner.isRunning()) {
            Toast.makeText(activity, R.string.import_blocks_export, Toast.LENGTH_LONG).show();
            return;
        }
        KmyExportRun.start(activity, repository, settings);
    }

    /**
     * Vor jedem Import zu fragen: Läuft ein Export, kommt ein Hinweis und {@code true} zurück – der
     * Import darf dann nicht starten. Beide ersetzen Buchungen und kämen sich in die Quere.
     */
    static boolean blocksImport(Activity activity) {
        if (!KmyExportRun.isRunning()) {
            return false;
        }
        Toast.makeText(activity, R.string.export_blocks_import, Toast.LENGTH_LONG).show();
        return true;
    }

    /**
     * Vor allem zu fragen, was die Datenbank wechselt, leert oder ersetzt (Profil wechseln,
     * zurücksetzen, wiederherstellen, Konten löschen): Der Export schreibt noch in sie.
     */
    static boolean blocksDataChange(Activity activity) {
        if (!KmyExportRun.isRunning()) {
            return false;
        }
        Toast.makeText(activity, R.string.export_wait, Toast.LENGTH_LONG).show();
        return true;
    }

    @Override
    public void onProgress(String label, int percent) {
        if (!shown) {
            shown = true;
            banner.startExport(label);
        }
        banner.set(label, percent);
    }

    @Override
    public void onEnd(String message, boolean failed, boolean refresh) {
        if (shown) {
            shown = false;
            banner.finish();
        }
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (failed) {
            // Kein Toast: der zeigt zwei Zeilen und ist nach Sekunden weg – ausgerechnet bei den
            // Meldungen, die man lesen muss.
            new AppDialog(activity)
                    .setTitle(R.string.kmy_export_stopped_title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        } else {
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        }
        if (refresh && onChanged != null) {
            onChanged.run();
        }
    }
}
