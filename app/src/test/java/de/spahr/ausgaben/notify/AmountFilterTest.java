package de.spahr.ausgaben.notify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Die Schwelle, hinter der eine Benachrichtigung aufgehoben wird: Zahl mit Nachkommastellen <b>und</b>
 * Währung.
 *
 * <p>Die Verneinungen sind hier die wichtigere Hälfte. Jede Meldung, die fälschlich durchgeht, ist der
 * Text einer fremden App, der einen Tag lang auf dem Gerät liegt, ohne dass er etwas mit Geld zu tun
 * hätte.</p>
 */
public class AmountFilterTest {

    // ---- geht durch ----

    @Test
    public void betragMitKuerzel() {
        assertTrue(AmountFilter.hasAmount("Kartenzahlung", "Zahlung über 12,34 EUR bei REWE."));
    }

    @Test
    public void betragMitSymbolDavorUndDahinter() {
        assertTrue(AmountFilter.hasAmount("12,34 € bei REWE"));
        assertTrue(AmountFilter.hasAmount("€ 12,34 bei REWE"));
    }

    @Test
    public void symbolKlebtAnDerZahl() {
        assertTrue(AmountFilter.hasAmount("12,34€ bezahlt"));
        assertTrue(AmountFilter.hasAmount("You paid $12.34"));
    }

    @Test
    public void geschuetztesLeerzeichenVorDerWaehrung() {
        assertTrue(AmountFilter.hasAmount("Umsatz: 1.234,56 €."));
    }

    @Test
    public void ausgeschriebenerEuro() {
        assertTrue(AmountFilter.hasAmount("Es wurden 12,34 Euro abgebucht."));
    }

    /** Eine fremde Währung gehört in den Puffer — sie soll später auffallen, nicht fehlen. */
    @Test
    public void fremdeWaehrung() {
        assertTrue(AmountFilter.hasAmount("Zahlung über 12,34 USD"));
        assertTrue(AmountFilter.hasAmount("CHF 4'420.00 belastet"));
    }

    /** Der umgerechnete Betrag steht oft in Klammern dahinter. */
    @Test
    public void betragInKlammern() {
        assertEquals(java.util.Arrays.asList(690L, 750L),
                AmountFilter.amounts("Zahlung über 6,90 EUR (7,50 USD) bei Walmart"));
        assertEquals(java.util.Arrays.asList("EUR", "USD"),
                AmountFilter.currencies("Zahlung über 6,90 EUR (7,50 USD) bei Walmart"));
    }

    @Test
    public void betragNurImTitel() {
        assertTrue(AmountFilter.hasAmount("-12,34 €", "REWE Markt"));
    }

    // ---- geht nicht durch ----

    @Test
    public void zahlOhneWaehrung() {
        assertFalse(AmountFilter.hasAmount("Akku bei 12,50 %"));
        assertFalse(AmountFilter.hasAmount("Version 12.34 ist da"));
    }

    @Test
    public void waehrungOhneNachkommastellen() {
        assertFalse(AmountFilter.hasAmount("Noch 3 € Guthaben"));
        assertFalse(AmountFilter.hasAmount("1.234 EUR Jackpot"));
    }

    /** Die Währung muss an der Zahl stehen, nicht irgendwo im Satz. */
    @Test
    public void waehrungWoandersImSatz() {
        assertFalse(AmountFilter.hasAmount("Der EUR fällt, der Kurs liegt jetzt bei 1,08"));
    }

    @Test
    public void datumIstKeinBetrag() {
        assertFalse(AmountFilter.hasAmount("Termin am 17.08.2026 um 12:30"));
    }

    @Test
    public void leerUndNull() {
        assertFalse(AmountFilter.hasAmount(null, null));
        assertFalse(AmountFilter.hasAmount("", ""));
    }
}
