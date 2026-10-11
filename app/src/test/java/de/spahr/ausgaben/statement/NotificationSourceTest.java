package de.spahr.ausgaben.statement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import de.spahr.ausgaben.pdf.PdfText;

/**
 * Eine Benachrichtigung als Eingang der Erkennung.
 *
 * <p>Geprüft wird vor allem das Zurechtmachen des Texts: {@link AnchorRule} kennt nur das Leerzeichen
 * als Wortgrenze, und ein Satz hängt seine Satzzeichen an die Wörter. Was hier nicht abgetrennt wird,
 * macht weiter hinten still einen Betrag unlesbar.</p>
 */
public class NotificationSourceTest {

    @Test
    public void derPunktAmSatzendeGehoertNichtZumKuerzel() {
        assertEquals("Zahlung über 12,34 EUR", NotificationSource.normalize("Zahlung über 12,34 EUR."));
    }

    @Test
    public void anfuehrungszeichenUndKommaGehoerenNichtZumNamen() {
        assertEquals("bei REWE Köln", NotificationSource.normalize("bei „REWE“, Köln!"));
    }

    /** Zwischen Zahl und Währung steht bei vielen Apps ein geschütztes Leerzeichen. */
    @Test
    public void geschuetztesLeerzeichenTrenntWieEinGewoehnliches() {
        assertEquals("12,34 €", NotificationSource.normalize("12,34 €"));
        assertEquals("12,34 €", NotificationSource.normalize("12,34 €"));
    }

    /** Der Punkt in der Zahl bleibt, und die Klammer auch: sie heißt „negativ". */
    @Test
    public void zeichenInDerZahlBleiben() {
        assertEquals("1.234,56 EUR", NotificationSource.normalize("1.234,56 EUR"));
        assertEquals("(12,34) EUR", NotificationSource.normalize("(12,34) EUR"));
    }

    @Test
    public void zeilenumbruecheBleibenLeerzeilenNicht() {
        assertEquals("Erste\nZweite", NotificationSource.normalize("Erste\r\n\n  Zweite  "));
    }

    @Test
    public void titelUndTextSindEigeneZeilen() {
        NotificationSource s = new NotificationSource("de.bank.app", 5L, "Kartenzahlung",
                "12,34 € bei REWE.");
        PdfText text = s.text();
        assertEquals(2, text.lines().size());
        assertEquals("Kartenzahlung", text.lines().get(0).text());
        assertEquals("12,34 € bei REWE", text.lines().get(1).text());
        // Ohne Layout: der Lerner darf hier keine Spalten suchen.
        assertFalse(text.hasWordPositions());
        assertEquals("de.bank.app", s.sender());
        assertEquals(5L, s.timeMillis());
    }

    @Test
    public void einPdfHatWederAbsenderNochZeit() {
        TextSource pdf = TextSource.ofPdf(StatementFixtures.ingKauf());
        assertEquals("", pdf.sender());
        assertEquals(-1L, pdf.timeMillis());
    }
}
