package de.spahr.ausgaben.statement;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Ganzzahlige Beträge in Benachrichtigungen, gelesen nach dem Zahlenformat des Profils.
 *
 * <p>„1.234 €" ist nach deutscher Schreibweise tausendzweihundertvierunddreißig Euro, nach englischer
 * gar kein Betrag. Die Tests halten beides fest — und dass Zahlen ohne Währung daneben bleiben, was
 * sie sind.</p>
 */
public class MoneyStyleTest {

    private static final MoneyStyle DE = MoneyStyle.forNumberFormat("de_group");
    private static final MoneyStyle EN = MoneyStyle.forNumberFormat("en_group");
    private static final MoneyStyle OHNE = MoneyStyle.forNumberFormat("plain_comma");

    @Test
    public void tausenderpunktImDeutschenFormat() {
        assertEquals("Sie haben 1234,00 € bezahlt", DE.canonical("Sie haben 1.234 € bezahlt"));
        assertEquals("1234567,00 EUR", DE.canonical("1.234.567 EUR"));
    }

    @Test
    public void tausenderkommaImEnglischenFormat() {
        assertEquals("You paid 1234,00 €", EN.canonical("You paid 1,234 €"));
    }

    /** Das Trennzeichen des anderen Formats ist keine Tausendergruppe – dann bleibt die Zahl stehen. */
    @Test
    public void fremderTrennerBleibtStehen() {
        assertEquals("1,234 €", DE.canonical("1,234 €"));
        assertEquals("1.234 €", EN.canonical("1.234 €"));
        assertEquals("1.234 €", OHNE.canonical("1.234 €"));
    }

    @Test
    public void ganzzahlOhneGruppierungInJedemFormat() {
        for (MoneyStyle style : new MoneyStyle[]{DE, EN, OHNE}) {
            assertEquals("Zahlung über 12,00 € bei REWE", style.canonical("Zahlung über 12 € bei REWE"));
        }
    }

    @Test
    public void waehrungAmWortOderDaneben() {
        assertEquals("€12,00 bei Lidl", DE.canonical("€12 bei Lidl"));
        assertEquals("12,00€ kontaktlos", DE.canonical("12€ kontaktlos"));
        assertEquals("EUR 5,00 abgebucht", DE.canonical("EUR 5 abgebucht"));
        assertEquals("EUR5,00", DE.canonical("EUR5"));
        assertEquals("12,00 Euro", DE.canonical("12 Euro"));
        assertEquals("(12,00 USD)", DE.canonical("(12 USD)"));
        assertEquals("-12,00 €", DE.canonical("-12 €"));
    }

    /** Ohne Währung daneben ist eine Zahl keine Geldangabe. */
    @Test
    public void zahlenOhneWaehrungBleiben() {
        for (String s : new String[]{"Termin 12 Uhr", "PIN 1234", "Rabatt 10 %", "3 Tage",
                "Karte *1234", "Zahlung über 12,34 €", "Version 1.234"}) {
            assertEquals(s, DE.canonical(s));
        }
    }

    /** Mehr als sieben Ziffern sind eine Nummer, kein Betrag. */
    @Test
    public void langeNummernBleiben() {
        assertEquals("12345678 €", DE.canonical("12345678 €"));
    }

    @Test
    public void strengLaesstAllesStehen() {
        assertEquals("Zahlung über 12 € bei REWE", MoneyStyle.STRICT.canonical("Zahlung über 12 € bei REWE"));
    }

    @Test
    public void zeilenBleibenErhalten() {
        assertEquals("Neuer Umsatz\n1234,00 € bei X", DE.canonical("Neuer Umsatz\n1.234 € bei X"));
    }

    /** Lernen und Lesen gehen durch dieselbe Form: aus „1.234 €" gelernt, liest die Regel auch „12 €". */
    @Test
    public void erkennungLiestGanzzahlen() {
        NotificationTemplate t = NotificationLearner.learn(new NotificationSource("app", 0L,
                "Neuer Umsatz", "Sie haben kontaktlos 1.234 € bei Media Markt bezahlt.", DE),
                123400L, false, "Media Markt", "Kreditkarte");
        NotificationTemplate.Hit hit = t.apply(new NotificationSource("app", 0L, "Neuer Umsatz",
                "Sie haben kontaktlos 12 € bei Lidl bezahlt.", DE));
        assertEquals(1200L, hit.cents);
        assertEquals("Lidl", hit.payee);
    }
}
