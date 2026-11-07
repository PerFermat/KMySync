package de.spahr.ausgaben.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.spahr.ausgaben.util.FxTable;

/**
 * Fremdwährungskonten in den Summen: Saldo am Stichtag × Devisenkurs dieses Tages, in Basiswährung.
 * Konten in Basiswährung und Konten ohne Devisenkurs bleiben außen vor (sie zählen unverändert).
 */
public class CurrencyContextTest {

    private static final long JAN = 1_767_225_600_000L;     // 2026-01-01
    private static final long JUN = JAN + 151L * 86_400_000L;
    private static final long NOV = JAN + 310L * 86_400_000L;

    private static Booking b(String account, long cents, boolean income, long at) {
        Booking x = new Booking();
        x.account = account;
        x.amountCents = cents;
        x.isIncome = income;
        x.createdAt = at;
        return x;
    }

    private static CurrencyContext ctx() {
        Map<String, String> cur = new HashMap<>();
        cur.put("konto usd", "USD");
        cur.put("girokonto", "EUR");
        cur.put("konto chf", "CHF");   // kein Devisenkurs vorhanden
        FxTable fx = new FxTable();
        fx.add("USD", "EUR", 0.9, JAN);
        fx.add("USD", "EUR", 0.8, JUN);
        return new CurrencyContext("EUR", cur, fx);
    }

    private static final List<Booking> BOOKINGS = Arrays.asList(
            b("Konto USD", 100_000, true, JAN),
            b("Konto USD", 20_000, false, NOV),
            b("Girokonto", 50_000, true, JAN),
            b("Konto CHF", 10_000, true, JAN));

    @Test
    public void nurKontenInFremdwaehrungMitKurs() {
        CurrencyContext.ForeignBalances f = ctx().foreignBalances(BOOKINGS);
        assertTrue(f.contains("Konto USD"));
        assertFalse(f.contains("Girokonto"));
        assertFalse("ohne Devisenkurs zählt es unverändert", f.contains("Konto CHF"));
    }

    @Test
    public void saldoMalKursDesStichtags() {
        CurrencyContext.ForeignBalances f = ctx().foreignBalances(BOOKINGS);
        assertEquals(90_000, f.valueCentsAt(JAN, null));          // 1.000 USD × 0,9
        assertEquals(80_000, f.valueCentsAt(JUN, null));          // dieselben 1.000 USD × 0,8
        assertEquals(64_000, f.valueCentsAt(NOV + 1, null));      // 800 USD × 0,8
        assertEquals(0, f.valueCentsAt(JAN - 1, null));
    }

    @Test
    public void filterAufKonten() {
        CurrencyContext.ForeignBalances f = ctx().foreignBalances(BOOKINGS);
        assertEquals(0, f.valueCentsAt(NOV + 1, a -> a.equals("girokonto")));
        assertEquals(64_000, f.valueCentsAt(NOV + 1, a -> a.equals("konto usd")));
    }

    @Test
    public void ohneBasiswaehrungKeineUmrechnung() {
        Map<String, String> cur = new HashMap<>();
        cur.put("konto usd", "USD");
        FxTable fx = new FxTable();
        fx.add("USD", "EUR", 0.9, JAN);
        assertTrue(new CurrencyContext("", cur, fx).foreignBalances(BOOKINGS).isEmpty());
    }
}
