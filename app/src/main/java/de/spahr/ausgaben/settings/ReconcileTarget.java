package de.spahr.ausgaben.settings;

/**
 * Vorgabe für die Kassensturz-Ausgleichsbuchung: Empfänger und Kategorie. Beides ist anfangs leer und
 * wird vom Benutzer einmal festgelegt (siehe {@link SettingsStore#setReconcileTarget(String, String, Boolean)}).
 */
public final class ReconcileTarget {

    private ReconcileTarget() {
    }

    /**
     * Darf der Kassensturz übernommen werden? Ohne Ausgleichsbuchung immer; mit Buchung nur, wenn
     * Empfänger <b>und</b> Kategorie feststehen.
     */
    public static boolean canApply(boolean createBooking, String payee, String category) {
        return !createBooking || (!isBlank(payee) && !isBlank(category));
    }

    /** Beschriftung des Festlegen-Knopfes: „Empfänger / Kategorie" oder {@code null}, wenn nichts steht. */
    public static String label(String payee, String category) {
        if (isBlank(payee) || isBlank(category)) {
            return null;
        }
        return payee.trim() + " / " + category.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
