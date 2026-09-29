package de.spahr.ausgaben.ui;

import android.app.Activity;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Wechsel des Sync-Modus aus Einstellungen und Einrichtungsassistent.
 *
 * <p>Löschungen, Weiterstellungen von Planungen, Depot-Bewegungen und Änderungen an exportierten
 * Buchungen kommen nur über das .kmy-Schreibziel in KMyMoney an. Wer davon wegwechselt, während noch
 * etwas davon aussteht, verlöre es still – bearbeitete Buchungen gingen sogar als zweite Buchung per
 * CSV hinaus. Deshalb fragt der Wechsel nach und verwirft die Vormerkungen ausdrücklich
 * ({@link Repository#discardKmyPending}).</p>
 */
final class ExportModeSwitch {

    private ExportModeSwitch() {
    }

    /**
     * @param onApplied  nach dem Speichern des neuen Modus (auf dem Hauptfaden)
     * @param onReverted der Nutzer hat abgebrochen, der alte Modus bleibt – die Auswahl zurückstellen
     */
    static void request(Activity activity, Repository repository, SettingsStore settings, String newMode,
                        Runnable onApplied, Runnable onReverted) {
        boolean leavesKmy = SettingsStore.MODE_KMY.equals(settings.getExportMode())
                && !SettingsStore.MODE_KMY.equals(newMode);
        if (!leavesKmy) {
            apply(settings, newMode, onApplied);
            return;
        }
        repository.countKmyPending(counts -> {
            if (counts[0] + counts[1] + counts[2] + counts[3] == 0) {
                apply(settings, newMode, onApplied);
                return;
            }
            if (activity.isFinishing() || activity.isDestroyed()) {
                onReverted.run();
                return;
            }
            AppDialog.destructive(activity)
                    .setTitle(R.string.export_mode_discard_title)
                    .setMessage(message(activity, counts))
                    .setNegativeButton(R.string.cancel, (d, w) -> onReverted.run())
                    .setOnCancelListener(d -> onReverted.run())
                    .setPositiveButton(R.string.export_mode_discard_confirm, (d, w) ->
                            repository.discardKmyPending(() -> apply(settings, newMode, onApplied)))
                    .show();
        });
    }

    private static void apply(SettingsStore settings, String mode, Runnable onApplied) {
        settings.setExportMode(mode);
        onApplied.run();
    }

    /** Einleitung und je eine Zeile pro Art, die tatsächlich aussteht. */
    private static String message(Activity a, int[] counts) {
        StringBuilder sb = new StringBuilder(a.getString(R.string.export_mode_discard_intro));
        int[] lines = {R.string.export_mode_discard_deletes, R.string.export_mode_discard_advances,
                R.string.export_mode_discard_security, R.string.export_mode_discard_edited};
        for (int i = 0; i < lines.length; i++) {
            if (counts[i] > 0) {
                sb.append("\n• ").append(a.getString(lines[i], counts[i]));
            }
        }
        return sb.toString();
    }
}
