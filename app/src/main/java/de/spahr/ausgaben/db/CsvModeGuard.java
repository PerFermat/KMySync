package de.spahr.ausgaben.db;

import androidx.annotation.Nullable;

/**
 * Entscheidet, welche Aktionen im CSV-Modus stillen Datenverlust verursachen würden und deshalb
 * gesperrt gehören – die eine Regel dieses Zustands, bewußt ohne Android und ohne Datenbank,
 * damit sie sich mit gewöhnlichen Tests festnageln läßt.
 *
 * <p>Hintergrund: der CSV-Export schreibt jede Buchung immer als neue Zeile in eine frische Datei;
 * es gibt kein CSV-Format für „Änderung" oder „Löschung" einer bereits exportierten Buchung, und
 * Planungen/Depot werden im CSV-Modus überhaupt nicht zurückgeschrieben.
 */
public final class CsvModeGuard {

    private CsvModeGuard() {
    }

    /**
     * Eine bereits exportierte Buchung darf im CSV-Modus nicht mehr geändert oder gelöscht werden –
     * die Änderung käme in KMyMoney nie an.
     */
    public static boolean lockedForEdit(@Nullable Booking b, boolean kmyMode) {
        return b != null && b.exported && !kmyMode;
    }

    /** Planungen (Scheduled Transactions) funktionieren im CSV-Modus grundsätzlich nicht. */
    public static boolean scheduledBlocked(boolean kmyMode) {
        return !kmyMode;
    }

    /** Depot/Wertpapiere sind nur im kmy-Modus nutzbar. */
    public static boolean depotBlocked(boolean kmyMode) {
        return !kmyMode;
    }
}
