package de.spahr.ausgaben.db;

import androidx.annotation.Nullable;

/**
 * Entscheidet, ob aus dem Ändern einer Buchung der Status „bearbeitet" wird
 * ({@link Booking#edited}) – die eine Regel dieses Zustands, bewußt ohne Android und ohne Datenbank,
 * damit sie sich mit gewöhnlichen Tests festnageln läßt.
 *
 * <p>Hintergrund: der Export schreibt nur Buchungen mit {@code exported = 0}, und zwar immer als
 * <b>neue</b> Transaktion. Eine geänderte, schon exportierte Buchung erreichte die .kmy-Datei deshalb
 * früher nie. „Bearbeitet" merkt sich stattdessen die Signatur der exportierten Fassung
 * ({@code orig_*}); beim nächsten Übertragen wird die Transaktion in der Datei geändert.
 */
public final class EditStatus {

    private EditStatus() {
    }

    /**
     * Setzt an {@code updated} den Status, der sich aus dem alten Stand ergibt. Von Hand ist
     * „bearbeitet" damit nie zu setzen: es entsteht ausschließlich hier.
     *
     * @param old     der gespeicherte Stand vor dieser Änderung ({@code null} = unbekannt, dann nichts)
     * @param updated der zu speichernde Stand; wird verändert
     * @param kmyMode nur im kmy-Modus gibt es eine gemeinsame Datei, in der etwas nachzuziehen wäre
     */
    public static void apply(@Nullable Booking old, Booking updated, boolean kmyMode) {
        if (old == null || updated == null || !kmyMode) {
            return;
        }
        if (old.edited) {
            // Zweite Änderung derselben Buchung: die Signatur muß die der einmal exportierten Fassung
            // bleiben, nicht die des Zwischenstands – sonst ist die Transaktion nicht mehr zu finden.
            carryOver(old, updated);
            return;
        }
        if (!old.exported) {
            return; // war noch nicht in der Datei – bleibt eine ganz normale, neue Buchung
        }
        if (!updated.exported) {
            return; // Schalter von Hand ausgelegt: Ihr Wille gilt, wie bisher „nicht exportiert"
        }
        updated.exported = false;
        updated.edited = true;
        updated.origAccount = old.account;
        updated.origSignedCents = signed(old);
        updated.origCreatedAt = old.createdAt;
        updated.origPayee = old.payee;
    }

    /** Übernimmt „bearbeitet" samt Signatur unverändert auf den neuen Stand. */
    public static void carryOver(Booking old, Booking updated) {
        updated.exported = false;
        updated.edited = true;
        updated.origAccount = old.origAccount;
        updated.origSignedCents = old.origSignedCents;
        updated.origCreatedAt = old.origCreatedAt;
        updated.origPayee = old.origPayee;
    }

    /**
     * Vererbt den Export-Status samt Signatur auf eine neu angelegte Zeile – für Umbuchungen, deren beide
     * Zeilen beim Ändern gelöscht und neu angelegt werden. {@code from == null} = frische Buchung.
     */
    public static void inherit(@Nullable Booking from, Booking to) {
        if (from == null) {
            return;
        }
        to.exported = from.exported;
        to.edited = from.edited;
        to.origAccount = from.origAccount;
        to.origSignedCents = from.origSignedCents;
        to.origCreatedAt = from.origCreatedAt;
        to.origPayee = from.origPayee;
    }

    /** Vorzeichenbehafteter Betrag in Cent ({@code +} = Einnahme), wie im Kontosplit der Transaktion. */
    public static long signed(Booking b) {
        return b.isIncome ? b.amountCents : -b.amountCents;
    }

    /**
     * Signatur, mit der diese Buchung in der .kmy-Datei zu suchen ist: bei „bearbeitet" die der
     * exportierten Fassung, sonst die aktuellen Werte. Reihenfolge: Konto, Betrag, Zeitpunkt.
     */
    public static String fileAccount(Booking b) {
        return b.edited ? b.origAccount : b.account;
    }

    public static long fileSignedCents(Booking b) {
        return b.edited ? b.origSignedCents : signed(b);
    }

    public static long fileCreatedAt(Booking b) {
        return b.edited ? b.origCreatedAt : b.createdAt;
    }

    /** Empfänger der exportierten Fassung, leer = unbekannt/keiner (dann greift kein zusätzliches Kriterium). */
    public static String filePayee(Booking b) {
        return b.edited ? b.origPayee : b.payee;
    }
}
