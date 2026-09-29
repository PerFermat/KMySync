package de.spahr.ausgaben.ui;

/**
 * Die Kriterien des Filtertrichters der Buchungsliste – ohne die Live-Suche der Titelzeile, die daneben
 * bestehen bleibt (siehe {@code MainActivity#searchQuery}). Ausgelagert aus {@link MainActivity}: Die
 * Werte standen dort als elf einzelne Felder, und ihre Zuweisungen waren über Dialog, Zurücksetzen und
 * Auswertung verteilt.
 */
final class BookingFilterState {

    String payee = "";
    String category = "";
    boolean categoryIsMain = false;
    /** Typ der gefilterten Kategorie (Einnahme/Ausgabe), {@code null} = kein Typ gewählt ("Alle"). */
    Boolean categoryIsIncome = null;
    /** Gefiltertes Stichwort (leer = alle); die Buchung muß es tragen. */
    String tag = "";
    Long amountFrom = null;
    Long amountTo = null;
    Long dateFrom = null;
    Long dateTo = null;
    /** Umkreis in Metern um {@link #center}; 0 = aus (siehe {@link de.spahr.ausgaben.location.RadiusFilter}). */
    int radiusM = 0;
    /** Eigene Position „lat, lon" im Moment des Anwendens – eingefroren, damit die Liste ruhig bleibt. */
    double[] center = null;

    /** Ist irgendein Kriterium des Trichters gesetzt? */
    boolean isActive() {
        return !payee.isEmpty() || !category.isEmpty() || !tag.isEmpty()
                || amountFrom != null || amountTo != null
                || dateFrom != null || dateTo != null
                || radiusM > 0;
    }

    /** Räumt alle Kriterien ab. */
    void reset() {
        payee = "";
        category = "";
        tag = "";
        categoryIsMain = false;
        categoryIsIncome = null;
        amountFrom = null;
        amountTo = null;
        dateFrom = null;
        dateTo = null;
        radiusM = 0;
        center = null;
    }
}
