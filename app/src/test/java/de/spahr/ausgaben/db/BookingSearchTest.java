package de.spahr.ausgaben.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Die Freitextsuche über eine Buchung — trifft auf <b>Empfänger, Notiz oder Kategorie</b>.
 *
 * <p>Diese Methode hatte bis 2.1 keinen einzigen Test, obwohl das Suchfeld des Filter-Dialogs seit
 * jeher an ihr hängt. Mit der Live-Suche in der Titelzeile trägt sie nun <b>zwei</b> Bedienwege, und
 * beide würden dieselbe stille Art von Fehler zeigen: Die Liste sähe einfach etwas anders aus, und
 * niemandem fiele auf, daß eine Buchung fehlt, die es geben müßte.</p>
 *
 * <p>Ohne Robolectric — reine Zeichenkettenarbeit, keine Android-Laufzeit nötig.</p>
 */
public class BookingSearchTest {

    private static Booking buchung(String payee, String note, String category) {
        Booking b = new Booking();
        b.payee = payee;
        b.note = note;
        b.category = category;
        return b;
    }

    /** Jedes der drei Felder trägt für sich — das ist der Kern der Zusage an den Nutzer. */
    @Test
    public void alleDreiFelderTragen() {
        assertTrue("Empfänger", BookingSearch.matches(buchung("Netto", "", ""), "Netto"));
        assertTrue("Notiz", BookingSearch.matches(buchung("", "Kassenbon 42", ""), "Kassenbon"));
        assertTrue("Kategorie", BookingSearch.matches(buchung("", "", "Lebensmittel"), "Lebensmittel"));
    }

    /**
     * Mitten im Wort, nicht nur am Anfang. KMyMoney-Empfänger tragen oft einen Ortszusatz
     * («Netto - Hattenhofen»), und Kategorien sind hierarchisch («Versicherungen:Krankenzusatz») —
     * wer dort nur den vorderen Teil fände, fände meistens nichts.
     */
    @Test
    public void auchMittenImWort() {
        Booking b = buchung("Netto - Hattenhofen", "", "Versicherungen:Krankenzusatz");
        assertTrue("Ortszusatz", BookingSearch.matches(b, "Hattenhofen"));
        assertTrue("Unterkategorie", BookingSearch.matches(b, "Krankenzusatz"));
        assertTrue("Teilwort", BookingSearch.matches(b, "kranken"));
    }

    /** Wer sucht, tippt klein. Was in der Buchung steht, ist beliebig geschrieben. */
    @Test
    public void grossUndKleinIstEgal() {
        Booking b = buchung("REWE", "", "");
        assertTrue("klein gesucht", BookingSearch.matches(b, "rewe"));
        assertTrue("groß gesucht", BookingSearch.matches(buchung("Rewe", "", ""), "REWE"));
    }

    /** Ein leerer Begriff schränkt nicht ein – sonst wäre eine unbenutzte Suche ein Filter. */
    @Test
    public void leererBegriffLaesstAllesDurch() {
        Booking b = buchung("Netto", "", "");
        assertTrue(BookingSearch.matches(b, ""));
        assertTrue(BookingSearch.matches(b, null));
    }

    /** Was nirgends vorkommt, wird auch nicht gefunden – die Gegenprobe zu allem darüber. */
    @Test
    public void wasNichtDrinStehtWirdNichtGefunden() {
        assertFalse(BookingSearch.matches(buchung("Netto", "Kassenbon", "Lebensmittel"), "Benzin"));
    }

    /**
     * Beide Bedienwege müssen in der Filterkette stehen — der aus dem Trichter und der aus der
     * Titelzeile.
     *
     * <p>Ein Wächter am Quelltext, weil der Ausfall <b>unsichtbar</b> wäre: Fiele der zweite Aufruf
     * beim nächsten Umbau weg, filterte die Live-Suche stillschweigend nicht mehr. Man tippt, die
     * Liste bleibt vollständig — und eine vollständige Liste sieht aus wie eine, in der alles
     * gefunden wurde. Kein Absturz, keine Meldung, kein schiefes Bild.</p>
     *
     * <p>Erprobte Bauart ({@code LocaleGuardTest}, {@code DateFormatsTest}, {@code PayeeGuardTest}):
     * Kommentare fallen vorher heraus, sonst zählte dieses Javadoc mit.</p>
     */
    @Test
    public void beideSuchwegeStehenInDerFilterkette() throws java.io.IOException {
        java.nio.file.Path f = java.nio.file.Paths.get(
                "src/main/java/de/spahr/ausgaben/ui/MainActivity.java");
        assertTrue("Pfad " + f.toAbsolutePath() + " nicht gefunden", java.nio.file.Files.isRegularFile(f));

        String quelle = new String(java.nio.file.Files.readAllBytes(f),
                java.nio.charset.StandardCharsets.UTF_8);
        String code = quelle.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
        int von = code.indexOf("private boolean matchesFilter(");
        assertTrue("matchesFilter nicht gefunden", von >= 0);
        int bis = code.indexOf("private boolean", von + 10);
        String rumpf = bis > von ? code.substring(von, bis) : code.substring(von);

        int treffer = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("BookingSearch\\.matches\\(\\s*b\\s*,\\s*(\\w+)\\s*\\)").matcher(rumpf);
        java.util.List<String> argumente = new java.util.ArrayList<>();
        while (m.find()) {
            argumente.add(m.group(1));
            treffer++;
        }
        assertEquals("matchesFilter muss filterPayee UND searchQuery prüfen, gefunden: " + argumente,
                2, treffer);
        assertTrue("der Trichter fehlt: " + argumente, argumente.contains("filterPayee"));
        assertTrue("die Live-Suche fehlt: " + argumente, argumente.contains("searchQuery"));
    }

    /**
     * {@code null} in einem Feld darf nicht abstürzen. Die Entity setzt zwar überall leere Zeichenketten
     * vor, aber sie ist ein offenes Datenobjekt: Importwege bauen sie von Hand zusammen, und ein
     * vergessenes Feld wäre hier ein Absturz beim Tippen des ersten Buchstabens.
     */
    @Test
    public void nullFelderStuerzenNichtAb() {
        Booking b = new Booking();
        b.payee = null;
        b.note = null;
        b.category = null;
        assertFalse(BookingSearch.matches(b, "Netto"));
        assertTrue("leerer Begriff geht auch dann durch", BookingSearch.matches(b, ""));
    }
}
