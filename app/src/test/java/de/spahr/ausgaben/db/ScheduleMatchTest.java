package de.spahr.ausgaben.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

/**
 * Zuordnung einer neu erfassten Wertpapier-Bewegung zu einer geplanten Umbuchung auf das
 * Wertpapierkonto ({@link ScheduleMatch}).
 */
public class ScheduleMatchTest {

    private static long ymd(int y, int m, int d) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(y, m - 1, d);
        return c.getTimeInMillis();
    }

    private static long plusDays(long base, int days) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(base);
        c.add(Calendar.DAY_OF_MONTH, days);
        return c.getTimeInMillis();
    }

    /** Ein Kauf-Schedule wie im Screenshot des Nutzers: Umbuchung Tagesgeld → ETF, incoming = 0. */
    private static ScheduledTransaction kaufSchedule(long dueMs) {
        ScheduledTransaction st = new ScheduledTransaction("SCH000259", "Kauf: Vanguard All-World",
                ScheduledTransaction.KIND_TRANSFER, dueMs, 50000L, "", "Tagesgeld Ing-DiBa",
                "Vanguard FTSE All-World UCITS ETF", 32, 1, 0);
        st.incoming = 0;
        return st;
    }

    private static SecurityTx kaufTx(long dateMs) {
        SecurityTx tx = new SecurityTx();
        tx.depot = "Depot";
        tx.securityKmyId = "E000001";
        tx.securityName = "Vanguard FTSE All-World UCITS ETF";
        tx.date = dateMs;
        tx.action = SecurityTx.BUY;
        tx.shares = 6.1234;
        tx.amountCents = 50000L;
        tx.moneyAccount = "Tagesgeld Ing-DiBa";
        return tx;
    }

    @Test
    public void findsExactMatch() {
        long due = ymd(2026, 8, 1);
        ScheduledTransaction st = kaufSchedule(due);
        SecurityTx tx = kaufTx(due);

        ScheduledTransaction match = ScheduleMatch.findMatch(
                Arrays.asList(st, kaufSchedule(plusDays(due, 40))), tx, "Vanguard FTSE All-World UCITS ETF");
        assertNotNull(match);
        assertEquals("SCH000259", match.kmyId);
    }

    @Test
    public void toleratesFiveDaysDifference() {
        long due = ymd(2026, 8, 1);
        ScheduledTransaction st = kaufSchedule(due);
        SecurityTx tx = kaufTx(plusDays(due, 5));

        assertNotNull(ScheduleMatch.findMatch(List.of(st), tx, "Vanguard FTSE All-World UCITS ETF"));
    }

    @Test
    public void rejectsBeyondFiveDays() {
        long due = ymd(2026, 8, 1);
        ScheduledTransaction st = kaufSchedule(due);
        SecurityTx tx = kaufTx(plusDays(due, 6));

        assertNull(ScheduleMatch.findMatch(List.of(st), tx, "Vanguard FTSE All-World UCITS ETF"));
    }

    @Test
    public void rejectsDifferentMoneyAccount() {
        long due = ymd(2026, 8, 1);
        ScheduledTransaction st = kaufSchedule(due);
        SecurityTx tx = kaufTx(due);
        tx.moneyAccount = "Girokonto";

        assertNull(ScheduleMatch.findMatch(List.of(st), tx, "Vanguard FTSE All-World UCITS ETF"));
    }

    @Test
    public void rejectsDifferentSecurityName() {
        long due = ymd(2026, 8, 1);
        ScheduledTransaction st = kaufSchedule(due);
        SecurityTx tx = kaufTx(due);

        assertNull(ScheduleMatch.findMatch(List.of(st), tx, "Ein anderes Wertpapier"));
    }

    /** Verkauf: Geld fließt ins Geldkonto (incoming = 1) – die entgegengesetzte Richtung eines Kaufs. */
    @Test
    public void sellRequiresIncomingDirection() {
        long due = ymd(2026, 8, 1);
        ScheduledTransaction sell = kaufSchedule(due);
        sell.incoming = 1;
        SecurityTx tx = kaufTx(due);
        tx.action = SecurityTx.SELL;
        tx.shares = -6.1234;

        assertNotNull(ScheduleMatch.findMatch(List.of(sell), tx, "Vanguard FTSE All-World UCITS ETF"));
        // Der Kauf-Schedule (incoming = 0) passt nicht zu einem Verkauf.
        assertNull(ScheduleMatch.findMatch(List.of(kaufSchedule(due)), tx,
                "Vanguard FTSE All-World UCITS ETF"));
    }

    @Test
    public void ambiguousMatchesAreDiscarded() {
        long due = ymd(2026, 8, 1);
        List<ScheduledTransaction> schedules = new ArrayList<>();
        schedules.add(kaufSchedule(due));
        schedules.add(kaufSchedule(plusDays(due, 2))); // zweiter Kandidat, auch innerhalb ±5 Tagen
        SecurityTx tx = kaufTx(plusDays(due, 1));

        assertNull(ScheduleMatch.findMatch(schedules, tx, "Vanguard FTSE All-World UCITS ETF"));
    }

    @Test
    public void ignoresNonTransferSchedules() {
        long due = ymd(2026, 8, 1);
        ScheduledTransaction st = new ScheduledTransaction("SCH1", "Miete", ScheduledTransaction.KIND_EXPENSE,
                due, 50000L, "", "Tagesgeld Ing-DiBa", "Vanguard FTSE All-World UCITS ETF", 32, 1, 0);
        SecurityTx tx = kaufTx(due);

        assertNull(ScheduleMatch.findMatch(List.of(st), tx, "Vanguard FTSE All-World UCITS ETF"));
    }

    @Test
    public void dividendActionNeverMatches() {
        long due = ymd(2026, 8, 1);
        ScheduledTransaction st = kaufSchedule(due);
        SecurityTx tx = kaufTx(due);
        tx.action = SecurityTx.DIVIDEND;

        assertNull(ScheduleMatch.findMatch(List.of(st), tx, "Vanguard FTSE All-World UCITS ETF"));
    }

    @Test
    public void newSharesUsesFixedAmountAndNewPrice() {
        ScheduledTransaction st = kaufSchedule(ymd(2026, 8, 1)); // amountCents = 50000 (500,00 €)
        double shares = ScheduleMatch.newShares(st, 81.69);
        assertEquals(500.0 / 81.69, shares, 1e-9);
    }

    @Test
    public void newSharesIsZeroWithoutAPrice() {
        ScheduledTransaction st = kaufSchedule(ymd(2026, 8, 1));
        assertEquals(0.0, ScheduleMatch.newShares(st, 0), 1e-9);
        assertEquals(0.0, ScheduleMatch.newShares(st, -1), 1e-9);
    }
}
