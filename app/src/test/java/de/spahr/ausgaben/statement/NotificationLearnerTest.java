package de.spahr.ausgaben.statement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Lernen aus einer Benachrichtigung und der Buchung dazu — und vor allem: was die gelernte Vorlage
 * danach <b>nicht</b> liest.
 *
 * <p>Eine Vorlage, die zu viel erkennt, schlägt Buchungen vor, die es nicht gibt. Die Hälfte der
 * Tests hält deshalb fest, wo sie schweigt: andere App, andere Karte, anders gebaute Meldung.</p>
 */
public class NotificationLearnerTest {

    private static final String APP = "de.bank.karte";

    private static NotificationSource meldung(String title, String body) {
        return new NotificationSource(APP, 0L, title, body);
    }

    private static NotificationSource zahlung(String haendler, String betrag) {
        return meldung("Kartenzahlung",
                "Zahlung bei " + haendler + " über " + betrag + " EUR mit Karte *1234.");
    }

    private static NotificationTemplate gelernt() {
        return NotificationLearner.learn(zahlung("REWE Markt", "12,34"), 1234L, false, "Rewe",
                "Kreditkarte");
    }

    // ---- Lernen ----

    @Test
    public void lerntBetragEmpfaengerUndKarte() {
        NotificationTemplate t = gelernt();
        assertNotNull(t);
        assertEquals(APP, t.packageName);
        assertEquals("Kreditkarte", t.account);
        assertEquals("*1234", t.discriminator);
        assertEquals(java.util.Collections.singletonList("über"), t.amountRule.anchors);
        assertEquals("EUR", t.amountRule.currency);
        assertEquals(java.util.Collections.singletonList("bei"), t.payeeRule.rule.anchors);
        assertEquals("über", t.payeeRule.until);
    }

    @Test
    public void liestDieNaechsteZahlungBeiAnderemHaendler() {
        NotificationTemplate.Hit hit = gelernt().apply(zahlung("Lidl Stiftung & Co", "7,50"));
        assertNotNull(hit);
        assertEquals(750L, hit.cents);
        assertEquals("Lidl Stiftung & Co", hit.payee);
    }

    /** Die Richtung kommt aus der Buchung, nicht aus dem Text. */
    @Test
    public void richtungStammtAusDerBuchung() {
        NotificationTemplate t = NotificationLearner.learn(
                meldung("Gutschrift", "Gutschrift von Amazon über 20,00 EUR"), 2000L, true, "Amazon",
                "Kreditkarte");
        assertNotNull(t);
        assertEquals(true, t.isIncome);
    }

    /** Steht der Betrag vorn, trägt der Titel. */
    @Test
    public void lerntAmTitelWennVorDemBetragNichtsSteht() {
        NotificationTemplate t = NotificationLearner.learn(
                meldung("Kartenzahlung", "12,34 EUR bei REWE"), 1234L, false, "Rewe", "Kreditkarte");
        assertNotNull(t);
        assertEquals(java.util.Collections.singletonList("Kartenzahlung"), t.amountRule.anchors);
        NotificationTemplate.Hit hit = t.apply(meldung("Kartenzahlung", "7,50 EUR bei Lidl Markt"));
        assertNotNull(hit);
        assertEquals(750L, hit.cents);
        assertEquals("Lidl Markt", hit.payee);
    }

    /** Der gebuchte Name steht nicht in der Meldung: Betrag ja, Empfänger nein — geraten wird nicht. */
    @Test
    public void ohneAuffindbarenEmpfaengerNurDerBetrag() {
        NotificationTemplate t = NotificationLearner.learn(zahlung("REWE Markt", "12,34"), 1234L,
                false, "Supermarkt", "Kreditkarte");
        assertNotNull(t);
        assertNull(t.payeeRule);
        NotificationTemplate.Hit hit = t.apply(zahlung("Lidl", "7,50"));
        assertEquals(750L, hit.cents);
        assertEquals("", hit.payee);
    }

    /** Kein Anker enthält den Händler — sonst träfe die Vorlage nur noch diesen. */
    @Test
    public void derHaendlerStehtInKeinemAnker() {
        NotificationTemplate t = NotificationLearner.learn(
                meldung("REWE Markt", "Zahlung REWE 12,34 EUR"), 1234L, false, "Rewe", "Kreditkarte");
        if (t != null) {
            for (String anchor : t.amountRule.anchors) {
                assertEquals(-1, anchor.toLowerCase(java.util.Locale.ROOT).indexOf("rewe"));
            }
        }
    }

    @Test
    public void betragNichtInDerMeldungErgibtKeineVorlage() {
        assertNull(NotificationLearner.learn(zahlung("REWE Markt", "12,34"), 9999L, false, "Rewe",
                "Kreditkarte"));
        assertNull(NotificationLearner.learn(zahlung("REWE Markt", "12,34"), 0L, false, "Rewe",
                "Kreditkarte"));
    }

    // ---- Schweigen ----

    @Test
    public void andereAppWirdNichtGelesen() {
        NotificationSource fremd = new NotificationSource("de.andere.bank", 0L, "Kartenzahlung",
                "Zahlung bei Lidl über 7,50 EUR mit Karte *1234.");
        assertNull(gelernt().apply(fremd));
    }

    @Test
    public void andereKarteWirdNichtGelesen() {
        assertNull(gelernt().apply(meldung("Kartenzahlung",
                "Zahlung bei Lidl über 7,50 EUR mit Karte *9999.")));
    }

    /** Eine Gutschrift ist anders gebaut als die gelernte Zahlung. */
    @Test
    public void andersGebauteMeldungWirdNichtGelesen() {
        assertNull(gelernt().apply(meldung("Gutschrift",
                "Gutschrift von 20,00 EUR von Amazon auf Karte *1234.")));
    }

    @Test
    public void abgelehnteZahlungOhneBetragWirdNichtGelesen() {
        assertNull(gelernt().apply(meldung("Kartenzahlung",
                "Zahlung bei REWE Markt mit Karte *1234 abgelehnt.")));
    }

    /** Fremdwährung: die Regel ist an EUR gebunden und liest den Dollarbetrag nicht als Euro. */
    @Test
    public void fremdwaehrungWirdNichtAlsEuroGelesen() {
        assertNull(gelernt().apply(meldung("Kartenzahlung",
                "Zahlung bei Walmart über 7,50 USD mit Karte *1234.")));
    }

    // ---- Unterscheidungsmerkmal ----

    @Test
    public void kartenendungInVerschiedenenSchreibweisen() {
        assertEquals("*1234", NotificationLearner.discriminator(
                meldung("", "Karte *1234 belastet").text()));
        assertEquals("xxxx1234", NotificationLearner.discriminator(
                meldung("", "Card xxxx1234 charged").text()));
        assertEquals("1234", NotificationLearner.discriminator(
                meldung("", "mit Karte 1234 bezahlt").text()));
    }

    /** Vier Ziffern allein sind keine Kartenendung — eine Jahreszahl sieht genauso aus. */
    @Test
    public void vierZiffernOhneKartenwortSindKeinMerkmal() {
        assertEquals("", NotificationLearner.discriminator(
                meldung("", "Zahlung über 12,34 EUR im Jahr 2026").text()));
    }
}
