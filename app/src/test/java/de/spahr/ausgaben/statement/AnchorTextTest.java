package de.spahr.ausgaben.statement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import de.spahr.ausgaben.pdf.PdfText;

/**
 * {@link AnchorRule#readText}: der Text an einer angeankerten Stelle — gebraucht für den Empfänger in
 * einer Benachrichtigung.
 *
 * <p>Die Frage, die eine Zahl nicht stellt, ist das Ende. Die Tests halten fest, dass ein fehlendes
 * Ende <b>nichts</b> liefert und nicht den Rest der Zeile: lieber kein Empfänger als ein falscher.</p>
 */
public class AnchorTextTest {

    private static AnchorRule hinter(String anchor) {
        return AnchorRule.single(anchor, AnchorRule.Direction.SAME_LINE);
    }

    private static PdfText text(String title, String body) {
        return new NotificationSource("app", 0L, title, body).text();
    }

    @Test
    public void bisZumWort() {
        PdfText t = text("Kartenzahlung", "Zahlung bei REWE Markt über 12,34 EUR");
        assertEquals("REWE Markt", hinter("bei").readText(t, "über"));
    }

    @Test
    public void ohneEndeBisZumZeilenende() {
        PdfText t = text("Kartenzahlung", "12,34 EUR bezahlt bei REWE Markt");
        assertEquals("REWE Markt", hinter("bei").readText(t, ""));
    }

    @Test
    public void bisZurErstenZahl() {
        PdfText t = text("Kartenzahlung", "Sie haben bei REWE Markt 12,34 EUR bezahlt");
        assertEquals("REWE Markt", hinter("bei").readText(t, AnchorRule.UNTIL_NUMBER));
    }

    /** Ein vorangestelltes Kürzel beendet den Namen ebenso wie die Zahl selbst. */
    @Test
    public void auchDasWaehrungskuerzelBeendetDenText() {
        PdfText t = text("Kartenzahlung", "Sie haben bei REWE Markt EUR 12,34 bezahlt");
        assertEquals("REWE Markt", hinter("bei").readText(t, AnchorRule.UNTIL_NUMBER));
    }

    /** Die Meldung ist anders gebaut als die gelernte — dann ist der Rest der Zeile kein Empfänger. */
    @Test
    public void fehltDasEndeGibtEsKeinenText() {
        PdfText t = text("Kartenzahlung", "Zahlung bei REWE Markt wurde abgelehnt");
        assertNull(hinter("bei").readText(t, "über"));
        assertNull(hinter("bei").readText(t, AnchorRule.UNTIL_NUMBER));
    }

    @Test
    public void ohneBeschriftungKeinText() {
        PdfText t = text("Kartenzahlung", "Zahlung an REWE Markt über 12,34 EUR");
        assertNull(hinter("bei").readText(t, "über"));
    }

    /** Wortgrenzen wie bei den Zahlen: „bei" schlägt nicht auf „dabei" an. */
    @Test
    public void beschriftungNurAlsGanzesWort() {
        PdfText t = text("Kartenzahlung", "dabei REWE Markt über 12,34 EUR");
        assertNull(hinter("bei").readText(t, "über"));
    }

    /** Der Titel ist der Händler, die Beschriftung steht darunter. */
    @Test
    public void dieZeileUeberDerBeschriftung() {
        PdfText t = text("REWE Markt", "Zahlung über 12,34 EUR");
        AnchorRule rule = new AnchorRule(java.util.Collections.singletonList("Zahlung über"),
                AnchorRule.Direction.LINE_ABOVE, false, "", AnchorRule.Position.LAST, 1, 1);
        assertEquals("REWE Markt", rule.readText(t, ""));
    }

    @Test
    public void dieZeileUnterDerBeschriftung() {
        PdfText t = text("Zahlung an", "REWE Markt\n12,34 EUR");
        AnchorRule rule = new AnchorRule(java.util.Collections.singletonList("Zahlung an"),
                AnchorRule.Direction.LINE_BELOW, false, "", AnchorRule.Position.LAST, 1, 1);
        assertEquals("REWE Markt", rule.readText(t, ""));
    }

    /** Die Rückfallkette gilt auch hier: die erste Beschriftung, die trägt. */
    @Test
    public void zweiteBeschriftungSpringtEin() {
        PdfText t = text("Kartenzahlung", "Zahlung an REWE Markt über 12,34 EUR");
        AnchorRule rule = new AnchorRule(java.util.Arrays.asList("bei", "an"),
                AnchorRule.Direction.SAME_LINE, false);
        assertEquals("REWE Markt", rule.readText(t, "über"));
    }
}
