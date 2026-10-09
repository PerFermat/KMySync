package de.spahr.ausgaben.db;

import androidx.annotation.Nullable;

/**
 * Entscheidet, ob eine Buchung in der App noch geändert oder gelöscht werden darf, obwohl sie in
 * KMyMoney abgeglichen ist – die eine Regel dieses Zustands, bewußt ohne Android und ohne Datenbank,
 * damit sie sich mit gewöhnlichen Tests festnageln läßt.
 *
 * <p>Hintergrund: Mit dem Abgleich bestätigt man in KMyMoney, dass die Buchungen eines Zeitraums mit
 * dem Kontoauszug übereinstimmen. Änderte die App eine davon nachträglich, stimmte der abgeglichene
 * Saldo nicht mehr, ohne dass KMyMoney es bemerkte. Deshalb sind sie hier nur zur Ansicht da.</p>
 */
public final class ReconciledGuard {

    private ReconciledGuard() {
    }

    /** Eine in KMyMoney abgeglichene Buchung darf in der App weder geändert noch gelöscht werden. */
    public static boolean locked(@Nullable Booking b) {
        return b != null && b.reconciled;
    }
}
