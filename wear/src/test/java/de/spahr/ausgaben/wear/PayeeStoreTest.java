package de.spahr.ausgaben.wear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Die zwei Regeln, an denen die Empfängerliste der Uhr hängt: das Lesen der vom Handy übertragenen
 * Zeilen und die Rangfolge im Umkreis.
 */
public class PayeeStoreTest {

    /** Marienplatz München – Bezugspunkt aller Entfernungen hier. */
    private static final double LAT = 48.13714;
    private static final double LON = 11.57549;

    private static String line(String name, int tier, String account, String points) {
        return name + PayeeStore.SEP + tier + PayeeStore.SEP + account + PayeeStore.SEP + points;
    }

    /** Ein Punkt rund {@code meter} noerdlich des Bezugspunkts. */
    private static String north(double meter) {
        return (LAT + meter / 111_320.0) + "," + LON;
    }

    @Test
    public void zeilenWerdenGelesen() {
        List<PayeeStore.Entry> e = PayeeStore.parse(
                line("Edeka", 0, "Bargeld", "48.13714,11.57549;48.13720,11.57560"));
        assertEquals(1, e.size());
        assertEquals("Edeka", e.get(0).name);
        assertEquals(0, e.get(0).tier);
        assertEquals("Bargeld", e.get(0).account);
        assertEquals(2, e.get(0).points.size());
    }

    @Test
    public void eineKrummeZeileVerdirbtNichtDenRest() {
        // Sonst stuende die Uhr wegen eines einzigen schiefen Eintrags ganz ohne Vorschlaege da.
        String raw = "voellig kaputt\n"
                + line("Edeka", 0, "", north(10)) + "\n"
                + line("Ohne Punkte", 0, "", "") + "\n"
                + line("Krumme Stufe", 9999, "", north(10)).replace("9999", "x") + "\n"
                + line("Aldi", 1, "", north(20));
        List<PayeeStore.Entry> e = PayeeStore.parse(raw);
        assertEquals(2, e.size());
        assertEquals("Edeka", e.get(0).name);
        assertEquals("Aldi", e.get(1).name);
    }

    @Test
    public void einKrummerPunktLaesstDieUebrigenGelten() {
        List<PayeeStore.Entry> e = PayeeStore.parse(
                line("Edeka", 0, "", "kaputt;48.13714,11.57549"));
        assertEquals(1, e.get(0).points.size());
    }

    @Test
    public void leerBleibtLeer() {
        assertTrue(PayeeStore.parse(null).isEmpty());
        assertTrue(PayeeStore.parse("").isEmpty());
    }

    @Test
    public void stufeSchlaegtEntfernung() {
        // Die Rangfolge des Handys: bevorzugter Alias vor Buchung, auch wenn die Buchung naeher liegt.
        List<PayeeStore.Entry> e = PayeeStore.parse(
                line("Naher Laden", 1, "", north(5)) + "\n"
                        + line("Bevorzugt", 0, "", north(50)));
        assertEquals(Arrays.asList("Bevorzugt", "Naher Laden"),
                PayeeStore.rank(e, LAT, LON, ""));
    }

    @Test
    public void innerhalbEinerStufeZaehltDieEntfernung() {
        List<PayeeStore.Entry> e = PayeeStore.parse(
                line("Weiter", 1, "", north(80)) + "\n"
                        + line("Naeher", 1, "", north(10)));
        assertEquals(Arrays.asList("Naeher", "Weiter"), PayeeStore.rank(e, LAT, LON, ""));
    }

    @Test
    public void jederNameNurEinmal() {
        // Derselbe Laden steht als Alias und als Buchung da – gezeigt wird er einmal.
        List<PayeeStore.Entry> e = PayeeStore.parse(
                line("Edeka", 0, "", north(60)) + "\n"
                        + line("edeka", 1, "", north(5)));
        assertEquals(Arrays.asList("Edeka"), PayeeStore.rank(e, LAT, LON, ""));
    }

    @Test
    public void jenseitsVonHundertMeternIstNiemandDa() {
        // Streng 100 m: Sonst stuende ein Laden aus dem Nachbarviertel als Vorschlag da.
        List<PayeeStore.Entry> e = PayeeStore.parse(line("Weit weg", 0, "", north(150)));
        assertTrue(PayeeStore.rank(e, LAT, LON, "").isEmpty());
    }

    @Test
    public void dernaechstePunktEinesEmpfaengersEntscheidet() {
        List<PayeeStore.Entry> e = PayeeStore.parse(
                line("Kette", 0, "", north(500) + ";" + north(20)));
        assertEquals(Arrays.asList("Kette"), PayeeStore.rank(e, LAT, LON, ""));
    }

    @Test
    public void dasGewaehlteKontoGrenztEin() {
        // Wer auf „Bargeld" bucht, meint keinen, den es bisher nur auf dem Girokonto gab.
        List<PayeeStore.Entry> e = PayeeStore.parse(
                line("Nur Giro", 0, "Girokonto", north(10)) + "\n"
                        + line("Bargeld-Laden", 0, "Bargeld", north(20)) + "\n"
                        + line("Ohne Konto", 0, "", north(30)));
        assertEquals(Arrays.asList("Bargeld-Laden", "Ohne Konto"),
                PayeeStore.rank(e, LAT, LON, "Bargeld"));
        // Ohne Kontowahl zaehlen alle.
        assertEquals(3, PayeeStore.rank(e, LAT, LON, "").size());
    }
}
