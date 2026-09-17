package de.spahr.ausgaben.wear;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Die Aufteilung des gesprochenen Satzes in Empfänger und Betrag – nur für die Anzeige auf der Uhr.
 * Maßgeblich bleibt der Auswerter des Handys; solange die Vorgabe steht, geht der Satz unverändert
 * hinaus.
 */
public class WearSpokenTest {

    private static void pruefe(String satz, String payee, String amount) {
        WearSpoken.Result r = WearSpoken.parse(satz);
        assertEquals("Empfänger aus \"" + satz + "\"", payee, r.payee);
        assertEquals("Betrag aus \"" + satz + "\"", amount, r.amount);
    }

    @Test
    public void empfaengerUndBetrag() {
        pruefe("Frisör 20 Euro", "Frisör", "20");
        pruefe("Edeka 12,50", "Edeka", "12,50");
        pruefe("Bäcker 3.20 €", "Bäcker", "3,20");
    }

    @Test
    public void nurEinBetrag() {
        pruefe("12,50", "", "12,50");
        pruefe("20 Euro", "", "20");
    }

    @Test
    public void nurEinEmpfaenger() {
        // Ohne Zahl bleibt der ganze Satz der Empfänger – das Handy zieht dann den Betrag aus der
        // letzten Buchung dieses Empfängers.
        pruefe("Frisör", "Frisör", "");
    }

    @Test
    public void zahlImNamenKostetNichtDenBetrag() {
        // „Aral 24" ist der Name der Tankstelle; das Währungswort entscheidet.
        pruefe("Aral 24 30 Euro", "Aral 24", "30");
    }

    @Test
    public void ohneWaehrungswortZaehltDieLetzteZahl() {
        pruefe("Aral 24 30", "Aral 24", "30");
    }

    @Test
    public void leeresBleibtLeer() {
        pruefe(null, "", "");
        pruefe("   ", "", "");
    }

    @Test
    public void mehrWorteBleibenErhalten() {
        pruefe("Sparkasse Musterstadt 45,90 Euro", "Sparkasse Musterstadt", "45,90");
    }
}
