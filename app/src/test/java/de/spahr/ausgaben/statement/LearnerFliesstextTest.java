package de.spahr.ausgaben.statement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import de.spahr.ausgaben.pdf.PdfText;

/**
 * Lernen aus einem <b>Satz</b> statt aus einer Abrechnung — die Grundlage für Buchungen aus
 * Benachrichtigungen.
 *
 * <p>Der Unterschied zur Abrechnung: dort steht links die Beschriftung und rechts die Zahl. In einem
 * Satz steht dazwischen der Händler, und der wechselt. Jeder Test hier lernt deshalb an einer Meldung
 * und liest an einer <b>zweiten</b> mit anderem Händler und anderem Betrag — eine Regel, die nur ihren
 * eigenen Lernbeleg wiederfindet, ist keine.</p>
 */
public class LearnerFliesstextTest {

    private static PdfText text(String title, String body) {
        return new NotificationSource("app", 0L, title, body).text();
    }

    // ---- Betrag ----

    @Test
    public void ankerIstDasWortVorDemBetragNichtDerHalbeSatz() {
        PdfText gelernt = text("Kartenzahlung", "Zahlung bei REWE Markt über 12,34 EUR.");
        List<AnchorRule> wege = TemplateLearner.kandidatenImFliesstext(gelernt, 12.34, "REWE Markt");
        assertEquals(1, wege.size());
        AnchorRule regel = wege.get(0);
        assertEquals(java.util.Collections.singletonList("über"), regel.anchors);
        assertEquals(AnchorRule.Direction.SAME_LINE, regel.direction);
        assertEquals("EUR", regel.currency);

        PdfText andere = text("Kartenzahlung", "Zahlung bei Lidl über 7,50 EUR.");
        assertEquals(Long.valueOf(750L), regel.readCents(andere));
    }

    /** Der alte Weg nähme „Zahlung bei REWE Markt über" — und fände den nächsten Händler nicht. */
    @Test
    public void derAnkerDerAbrechnungTruegeHierNicht() {
        PdfText gelernt = text("Kartenzahlung", "Zahlung bei REWE Markt über 12,34 EUR.");
        PdfText andere = text("Kartenzahlung", "Zahlung bei Lidl über 7,50 EUR.");
        AnchorRule alt = AnchorRule.single(TemplateLearner.labelOf(gelernt.lines().get(1).text()),
                AnchorRule.Direction.SAME_LINE);
        assertNull(alt.read(andere));
    }

    /** Ist der gebuchte Name kürzer als der gedruckte, bleibt das eine Wort trotzdem der erste Weg. */
    @Test
    public void dasEinzelneWortKommtVorDenZweien() {
        PdfText gelernt = text("Kartenzahlung", "Zahlung bei REWE Markt über 12,34 EUR.");
        List<AnchorRule> wege = TemplateLearner.kandidatenImFliesstext(gelernt, 12.34, "Rewe");
        assertEquals(2, wege.size());
        assertEquals(java.util.Collections.singletonList("über"), wege.get(0).anchors);
        assertEquals(java.util.Collections.singletonList("Markt über"), wege.get(1).anchors);
    }

    @Test
    public void kuerzelZwischenAnkerUndBetrag() {
        PdfText gelernt = text("Kartenzahlung", "Zahlung über EUR 12,34 bei REWE");
        List<AnchorRule> wege = TemplateLearner.kandidatenImFliesstext(gelernt, 12.34, "REWE");
        assertFalse(wege.isEmpty());
        assertEquals(java.util.Collections.singletonList("über"), wege.get(0).anchors);
        assertEquals("EUR", wege.get(0).currency);
    }

    /** Hinter dem Betrag steht noch eine Zahl — gelesen wird die erste hinter dem Anker. */
    @Test
    public void kartenendungHinterDemBetragStoertNicht() {
        PdfText gelernt = text("Kartenzahlung", "Zahlung über 12,34 EUR mit Karte 1234");
        AnchorRule regel = TemplateLearner.kandidatenImFliesstext(gelernt, 12.34, null).get(0);
        PdfText andere = text("Kartenzahlung", "Zahlung über 99,00 EUR mit Karte 1234");
        assertEquals(Long.valueOf(9900L), regel.readCents(andere));
    }

    /** „ab" allein träfe zu viel; mit dem Wort davor ist es ein Anker. */
    @Test
    public void einZuKurzesWortNimmtDasDavorMit() {
        PdfText gelernt = text("Konto", "Summe ab 12,34 EUR");
        List<AnchorRule> wege = TemplateLearner.kandidatenImFliesstext(gelernt, 12.34, null);
        assertEquals(1, wege.size());
        assertEquals(java.util.Collections.singletonList("Summe ab"), wege.get(0).anchors);
    }

    /** Eine andere Währung als die gelernte ist nicht der gesuchte Betrag. */
    @Test
    public void fremdeWaehrungWirdNichtAlsEuroGelesen() {
        PdfText gelernt = text("Kartenzahlung", "Zahlung über 12,34 EUR bei REWE");
        AnchorRule regel = TemplateLearner.kandidatenImFliesstext(gelernt, 12.34, "REWE").get(0);
        assertNull(regel.read(text("Kartenzahlung", "Zahlung über 12,34 USD bei Walmart")));
    }

    @Test
    public void stehtDerBetragVornGibtEsImSatzKeinenAnker() {
        PdfText gelernt = text("Kartenzahlung", "12,34 EUR bei REWE");
        assertTrue(TemplateLearner.kandidatenImFliesstext(gelernt, 12.34, "REWE").isEmpty());
    }

    /** Dann trägt der Titel — über den Weg, den es für Spaltenüberschriften längst gibt. */
    @Test
    public void derTitelAnkertDenBetragInDerZeileDarunter() {
        PdfText gelernt = text("Kartenzahlung", "12,34 EUR bei REWE");
        List<AnchorRule> wege = TemplateLearner.kandidatenFuerBetrag(gelernt, 12.34);
        assertEquals(1, wege.size());
        AnchorRule regel = wege.get(0);
        assertEquals(java.util.Collections.singletonList("Kartenzahlung"), regel.anchors);
        assertEquals(AnchorRule.Direction.LINE_BELOW, regel.direction);
        assertEquals(Long.valueOf(750L), regel.readCents(text("Kartenzahlung", "7,50 EUR bei Lidl")));
    }

    @Test
    public void einBetragDerNichtDastehtErgibtNichts() {
        PdfText gelernt = text("Kartenzahlung", "Zahlung über 12,34 EUR");
        assertTrue(TemplateLearner.kandidatenImFliesstext(gelernt, 12.35, null).isEmpty());
    }

    // ---- Empfänger ----

    @Test
    public void empfaengerZwischenZweiWoertern() {
        PdfText gelernt = text("Kartenzahlung", "Zahlung bei REWE Markt über 12,34 EUR.");
        TextRule regel = TemplateLearner.textregel(gelernt, "Rewe");
        assertNotNull(regel);
        assertEquals(java.util.Collections.singletonList("bei"), regel.rule.anchors);
        assertEquals("über", regel.until);
        assertEquals("REWE Markt", regel.read(gelernt));

        PdfText andere = text("Kartenzahlung", "Zahlung bei Lidl Stiftung & Co über 7,50 EUR.");
        assertEquals("Lidl Stiftung & Co", regel.read(andere));
    }

    @Test
    public void empfaengerVorDemBetrag() {
        PdfText gelernt = text("Kartenzahlung", "Sie haben bei REWE Markt 12,34 EUR bezahlt");
        TextRule regel = TemplateLearner.textregel(gelernt, "REWE");
        assertNotNull(regel);
        assertEquals(AnchorRule.UNTIL_NUMBER, regel.until);
        assertEquals("Aldi Süd",
                regel.read(text("Kartenzahlung", "Sie haben bei Aldi Süd 3,00 EUR bezahlt")));
    }

    @Test
    public void empfaengerAmZeilenende() {
        PdfText gelernt = text("Kartenzahlung", "12,34 EUR bezahlt bei REWE");
        TextRule regel = TemplateLearner.textregel(gelernt, "Rewe");
        assertNotNull(regel);
        assertEquals("", regel.until);
        assertEquals("Lidl Markt",
                regel.read(text("Kartenzahlung", "7,50 EUR bezahlt bei Lidl Markt")));
    }

    /** Der Titel ist der Händler: geankert wird an der Zeile darunter. */
    @Test
    public void empfaengerIstDerTitel() {
        PdfText gelernt = text("REWE Markt", "Zahlung über 12,34 EUR");
        TextRule regel = TemplateLearner.textregel(gelernt, "Rewe");
        assertNotNull(regel);
        assertEquals(AnchorRule.Direction.LINE_ABOVE, regel.rule.direction);
        assertEquals("Shell Tankstelle 4711",
                regel.read(text("Shell Tankstelle 4711", "Zahlung über 50,00 EUR")));
    }

    /** Gebucht „Rewe Köln", gedruckt „REWE Markt": das längste Wort des Namens genügt. */
    @Test
    public void einWortDesNamensGenuegt() {
        PdfText gelernt = text("Kartenzahlung", "Zahlung bei REWE Markt über 12,34 EUR");
        TextRule regel = TemplateLearner.textregel(gelernt, "Rewe Köln");
        assertNotNull(regel);
        assertEquals("REWE Markt", regel.read(gelernt));
    }

    /** Steht der gebuchte Name nicht in der Meldung, wird nichts geraten. */
    @Test
    public void unbekannterNameErgibtKeineRegel() {
        PdfText gelernt = text("Kartenzahlung", "Zahlung bei REWE Markt über 12,34 EUR");
        assertNull(TemplateLearner.textregel(gelernt, "Supermarkt"));
        assertNull(TemplateLearner.textregel(gelernt, ""));
    }

    /** Ohne Wort davor und ohne Zeile daneben lässt sich der Name nicht anankern. */
    @Test
    public void nameOhneBeschriftungErgibtKeineRegel() {
        PdfText gelernt = text("", "REWE 12,34 EUR");
        assertNull(TemplateLearner.textregel(gelernt, "Rewe"));
    }
}
