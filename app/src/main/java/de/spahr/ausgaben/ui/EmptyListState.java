package de.spahr.ausgaben.ui;

/**
 * Warum ist die Buchungsliste leer? Ein leerer Bildschirm allein sagt dem Nutzer nichts – schlimmer
 * noch, er sieht bei einem zu engen Filter genauso aus wie eine frisch eingerichtete App.
 *
 * <p>Die Entscheidung steht bewusst hier und nicht in der Activity: So ist die Reihenfolge der Fälle
 * prüfbar, ohne einen Bildschirm zu bauen.</p>
 */
public enum EmptyListState {

    /** Die Liste ist nicht leer – kein Hinweis. */
    NONE,
    /** Überhaupt keine Buchungen vorhanden (frische App, noch kein Import). */
    NO_BOOKINGS,
    /** Es gibt Buchungen, aber keine passt zum Filter. */
    NO_MATCH,
    /** Das gewählte Konto enthält (noch) keine Buchungen. */
    EMPTY_ACCOUNT;

    /**
     * @param total        Anzahl aller Buchungen, ungefiltert
     * @param shown        Anzahl der gerade angezeigten
     * @param filterActive steht ein Filter (Empfänger, Kategorie, Betrag, Zeitraum, Umkreis …)?
     * @param accountChosen ist in der Schublade ein einzelnes Konto gewählt?
     */
    public static EmptyListState of(int total, int shown, boolean filterActive,
                                    boolean accountChosen) {
        if (shown > 0) {
            return NONE;
        }
        // Reihenfolge zählt: Ist gar nichts da, hilft „Filter zurücksetzen" nicht weiter – der Nutzer
        // liefe ins Leere und hielte den Filter für kaputt.
        if (total <= 0) {
            return NO_BOOKINGS;
        }
        if (filterActive) {
            return NO_MATCH;
        }
        if (accountChosen) {
            return EMPTY_ACCOUNT;
        }
        // Buchungen vorhanden, kein Filter, kein Konto gewählt – dann steht eine Kontengruppe davor,
        // die nichts enthält. Derselbe Satz paßt: hier ist nichts.
        return EMPTY_ACCOUNT;
    }

    /** Nur beim zu engen Filter gibt es etwas zu tun – sonst führt ein Knopf nirgendwohin. */
    public boolean offersFilterReset() {
        return this == NO_MATCH;
    }
}
