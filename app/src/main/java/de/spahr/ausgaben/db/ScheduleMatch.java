package de.spahr.ausgaben.db;

import java.util.List;

/**
 * Prüft, ob eine neu erfasste Wertpapier-Bewegung zu einer geplanten Umbuchung auf das Wertpapierkonto
 * passt. KMyMoney kennt keine echten Wertpapier-Schedules; der übliche Umweg ist eine geplante Umbuchung
 * von einem Geldkonto auf das Wertpapier-Unterkonto (Zielkonto == Wertpapiername, siehe
 * {@link SecurityDao#getSecurityNames}). KMyMoney friert dabei die Stückzahl auf den Kurs zum Zeitpunkt
 * der Anlage/letzten Bearbeitung der Planung ein – wird sie erkannt, kann {@link #newShares} die
 * Stückzahl für den nächsten Termin auf den gerade erfassten Kurs korrigieren. Reine Logik, keine
 * DB-Zugriffe.
 */
public final class ScheduleMatch {

    private static final long WINDOW_MS = 5L * 24 * 60 * 60 * 1000;

    private ScheduleMatch() {
    }

    /** Ein Treffer: die Bewegung, die passende Planung und die für sie neu berechnete Stückzahl. */
    public static final class Result {
        public final SecurityTx tx;
        public final ScheduledTransaction schedule;
        public final double newShares;

        public Result(SecurityTx tx, ScheduledTransaction schedule, double newShares) {
            this.tx = tx;
            this.schedule = schedule;
            this.newShares = newShares;
        }
    }

    /**
     * Sucht in {@code schedules} genau eine passende Umbuchungs-Planung für {@code tx}: gleiches
     * Geldkonto, Zielkonto == Wertpapiername, passende Richtung (Kauf/Verkauf) und Fälligkeit innerhalb
     * von ±5 Tagen um {@code tx.date}. Mehrdeutige Treffer werden verworfen – lieber nichts vorschlagen
     * als raten.
     *
     * @param securityName Anzeigename des Wertpapiers (== Name seines Unterkontos in KMyMoney)
     * @return {@code null}, wenn kein eindeutiger Treffer gefunden wurde
     */
    public static ScheduledTransaction findMatch(List<ScheduledTransaction> schedules, SecurityTx tx,
                                                 String securityName) {
        if (schedules == null || tx == null || securityName == null || securityName.trim().isEmpty()) {
            return null;
        }
        boolean buy = SecurityTx.BUY.equals(tx.action);
        boolean sell = SecurityTx.SELL.equals(tx.action);
        if (!buy && !sell) {
            return null; // nur Kauf/Verkauf bewegen Geld gegen das Wertpapierkonto
        }
        int wantIncoming = sell ? 1 : 0; // Verkauf: Geld fließt ins Geldkonto; Kauf: Geld fließt hinaus
        ScheduledTransaction found = null;
        for (ScheduledTransaction st : schedules) {
            if (st.kind != ScheduledTransaction.KIND_TRANSFER || st.incoming != wantIncoming) {
                continue;
            }
            if (!equalsIgnoreCaseTrim(st.account, tx.moneyAccount)
                    || !equalsIgnoreCaseTrim(st.counterparty, securityName)) {
                continue;
            }
            if (Math.abs(st.nextDueMs - tx.date) > WINDOW_MS) {
                continue;
            }
            if (found != null) {
                return null; // mehrdeutig
            }
            found = st;
        }
        return found;
    }

    /**
     * Neue Stückzahl für den nächsten Termin der Planung: ihr fester Euro-Betrag, geteilt durch den
     * neuen Kurs. Liefert {@code 0}, wenn kein sinnvoller Kurs vorliegt.
     */
    public static double newShares(ScheduledTransaction st, double newPrice) {
        if (st == null || newPrice <= 0) {
            return 0;
        }
        return st.amountCents / 100.0 / newPrice;
    }

    private static boolean equalsIgnoreCaseTrim(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }
}
