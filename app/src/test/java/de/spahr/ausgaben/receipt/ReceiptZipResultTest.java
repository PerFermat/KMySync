package de.spahr.ausgaben.receipt;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Die Buchführung des Beleg-Exports. Drei Gründe, warum ein Beleg fehlt, und sie meinen ganz
 * Verschiedenes: „liegt nicht auf dem Server" ist ein Befund, „nicht erreichbar" ein Verbindungsproblem,
 * „kam nicht mehr dran" ein abgebrochener Lauf. Vorher standen alle drei in einer Zahl.
 */
public class ReceiptZipResultTest {

    @Test
    public void missing_zaehltAlleGruende() {
        ReceiptZip.Result r = new ReceiptZip.Result();
        r.notFound = 2;
        r.unreachable = 3;
        r.pending = 5;
        r.skipped = 7;
        assertEquals(17, r.missing());
    }

    @Test
    public void nichtGeladenIstKeinFehler() {
        // „Auf Wunsch nicht geladen" gehört nicht in dieselbe Zahl wie „nicht erreichbar" – der
        // Ergebnisdialog nennt beides getrennt.
        ReceiptZip.Result r = new ReceiptZip.Result();
        r.skipped = 199;
        assertEquals(0, r.unreachable);
        assertEquals(199, r.missing());
    }

    @Test
    public void missing_istNullWennAllesGepacktWurde() {
        ReceiptZip.Result r = new ReceiptZip.Result();
        r.receipts = 238;
        r.written = 238;
        assertEquals(0, r.missing());
    }
}
