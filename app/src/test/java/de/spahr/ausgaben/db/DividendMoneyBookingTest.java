package de.spahr.ausgaben.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Die Geldbuchung einer in der App erfassten Wertpapier-Bewegung hat dieselbe Form wie nach dem
 * .kmy-Import: Kauf und Verkauf als Umbuchung zum Wertpapier, die Dividende als Einnahme. Bis 2.2
 * stand die Dividende als Umbuchung da, bis das nächste Einlesen sie zur Einnahme machte.
 */
public class DividendMoneyBookingTest {

    private static SecurityTx dividende() {
        SecurityTx tx = new SecurityTx("Depot", "S1", "Alpen Fund", 1_780_000_000_000L,
                SecurityTx.DIVIDEND, 0, 10_000L, 8_000L);
        tx.moneyAccount = "Girokonto";
        return tx;
    }

    /** Ertrag 100, Steuer 20, Gutschrift 80 – genau die Splitbuchung, die der Import daraus liest. */
    @Test
    public void dividendeMitSteuerIstSplitEinnahme() {
        SecurityTx tx = dividende();
        tx.parts.add(new SecurityTxSplit(0, true, "Zinsen:Dividende", 10_000L, "", 0));
        tx.parts.add(new SecurityTxSplit(0, false, "Steuern:Kapitalertragssteuer", 2_000L, "", 1));

        Booking b = tx.toMoneyBooking(8_000L);

        assertFalse("keine Umbuchung", b.isTransfer);
        assertTrue(b.isIncome);
        assertEquals(8_000L, b.amountCents);
        assertEquals("Girokonto", b.account);
        assertEquals("Alpen Fund", b.payee);
        assertEquals("Zinsen:Dividende", b.category);
        assertEquals(Boolean.TRUE, b.categoryIsIncome);
        assertEquals(2, b.parts.size());
        assertEquals(10_000L, b.parts.get(0).amountCents);
        assertEquals(Boolean.TRUE, b.parts.get(0).categoryIsIncome);
        assertEquals(-2_000L, b.parts.get(1).amountCents);
        assertEquals(Boolean.FALSE, b.parts.get(1).categoryIsIncome);
        assertEquals("Teile ergeben die Gutschrift", b.amountCents,
                b.parts.get(0).amountCents + b.parts.get(1).amountCents);
    }

    /** Eine einzige Kategorie ist keine Splitbuchung. */
    @Test
    public void dividendeOhneSteuerIstEinfacheEinnahme() {
        SecurityTx tx = dividende();
        tx.parts.add(new SecurityTxSplit(0, true, "Zinsen:Dividende", 8_000L, "", 0));

        Booking b = tx.toMoneyBooking(8_000L);

        assertFalse(b.isTransfer);
        assertEquals("Zinsen:Dividende", b.category);
        assertNull(b.parts);
    }

    @Test
    public void kaufBleibtUmbuchung() {
        SecurityTx tx = new SecurityTx("Depot", "S1", "Alpen Fund", 1_780_000_000_000L,
                SecurityTx.BUY, 5, 5_000L);
        tx.moneyAccount = "Girokonto";

        Booking b = tx.toMoneyBooking(5_100L);

        assertTrue(b.isTransfer);
        assertFalse(b.isIncome);
        assertEquals("Alpen Fund", b.transferAccount);
        assertEquals("", b.category);
    }
}
