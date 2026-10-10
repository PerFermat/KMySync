package de.spahr.ausgaben.ui;

import android.content.Context;

import java.util.Arrays;
import java.util.List;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Die Einträge der Auswahl „Format" in Einstellungen und Einrichtungsassistent.
 *
 * <p>KMyMoney führt seine Daten als .kmy-Datei oder als SQLite-Datenbank. Für die App ist das dieselbe
 * Speicherart – gelesen und geschrieben wird beides gleich, erkannt wird die Datenbank am Dateikopf.
 * In der Auswahl stehen trotzdem getrennte Einträge: Wer eine Datenbank einrichtet, soll sie dort
 * finden, und die Dateiauswahl zeigt dann <b>alle</b> Dateien des Ordners statt nur „.kmy". Eine
 * KMyMoney-Datenbank kann heißen, wie sie will; KMyMoney prüft die Endung auch nicht.</p>
 *
 * <p>Ein Eintrag ist damit ein Paar aus Speicherart ({@link SettingsStore#getExportMode}) und der
 * Angabe „die Quelle ist eine Datenbank" ({@link SettingsStore#isKmyDatabase}).</p>
 */
final class ExportFormat {

    private ExportFormat() {
    }

    /** Die Beschriftungen in der Reihenfolge der Auswahl. */
    static List<String> labels(Context c) {
        return Arrays.asList(
                c.getString(R.string.export_mode_csv),
                c.getString(R.string.export_mode_kmy_csv),
                c.getString(R.string.export_mode_kmy),
                c.getString(R.string.export_mode_sqlite_csv),
                c.getString(R.string.export_mode_sqlite));
    }

    /** Die Speicherart zum Eintrag an dieser Stelle. */
    static String mode(int position) {
        switch (position) {
            case 1:
            case 3:
                return SettingsStore.MODE_KMY_CSV;
            case 2:
            case 4:
                return SettingsStore.MODE_KMY;
            default:
                return SettingsStore.MODE_CSV;
        }
    }

    /** Meint der Eintrag an dieser Stelle eine KMyMoney-Datenbank? */
    static boolean database(int position) {
        return position == 3 || position == 4;
    }

    /** Die Stelle des Eintrags zu Speicherart und Datenbank-Angabe. */
    static int position(String mode, boolean database) {
        if (SettingsStore.MODE_KMY.equals(mode)) {
            return database ? 4 : 2;
        }
        if (SettingsStore.MODE_KMY_CSV.equals(mode)) {
            return database ? 3 : 1;
        }
        return 0;
    }
}
