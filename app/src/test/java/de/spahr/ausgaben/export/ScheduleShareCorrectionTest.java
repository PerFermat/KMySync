package de.spahr.ausgaben.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.spahr.ausgaben.db.ScheduledAdvance;

/**
 * Stückzahl-Korrektur bei geplanten Wertpapier-Umbuchungen ({@link KmyExporter#applyShareCorrections}).
 * Die eingebettete Planung entspricht dem Aufbau einer echten Datei (siehe {@code SCH000259} in einer
 * realen .kmy): Umbuchung vom Verrechnungskonto auf das Wertpapier-Unterkonto, fester Euro-Betrag,
 * Stückzahl/Kurs im Investment-Split.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ScheduleShareCorrectionTest {

    private final Context ctx = ApplicationProvider.getApplicationContext();

    private static final String SCHEDULED_TX =
            "<SCHEDULED_TX type=\"4\" fixed=\"0\" id=\"SCH000259\" endDate=\"\" occurenceMultiplier=\"1\""
            + " startDate=\"2021-01-01\" occurence=\"32\" name=\"Kauf: Musterfonds\" lastPayment=\"2026-07-01\">\n"
            + " <PAYMENTS/>\n"
            + " <TRANSACTION entrydate=\"\" memo=\"\" id=\"\" commodity=\"EUR\" postdate=\"2026-08-01\">\n"
            + "  <SPLITS>\n"
            + "   <SPLIT value=\"-500/1\" bankid=\"\" memo=\"\" id=\"S0001\" reconcileflag=\"0\" price=\"1/1\""
            + " reconciledate=\"\" shares=\"-500/1\" number=\"\" action=\"\" account=\"A000001\" payee=\"\"/>\n"
            + "   <SPLIT value=\"500/1\" bankid=\"\" memo=\"\" id=\"S0002\" reconcileflag=\"0\" price=\"8169/100\""
            + " reconciledate=\"\" shares=\"352361/100000\" number=\"\" action=\"Buy\" account=\"A000003\""
            + " payee=\"\"/>\n"
            + "  </SPLITS>\n"
            + " </TRANSACTION>\n"
            + "</SCHEDULED_TX>";

    /** Die security-tx.xml-Testdatei mit einer eingesetzten Planung statt des leeren {@code <SCHEDULES/>}. */
    private KmyDocument doc() throws IOException {
        String xml = new String(KmyRobustnessTest.fixture("security-tx.xml"), StandardCharsets.UTF_8)
                .replace("<SCHEDULES/>", "<SCHEDULES count=\"1\">\n" + SCHEDULED_TX + "\n</SCHEDULES>");
        return new KmyDocument(xml.getBytes(StandardCharsets.UTF_8), ctx);
    }

    private static ScheduledAdvance advance(String kmyId, Double newShares) {
        ScheduledAdvance a = new ScheduledAdvance(kmyId, 0, 0, 0, 0);
        a.securityDepot = "Depot";
        a.securityKmyId = "E000001";
        a.newShares = newShares;
        return a;
    }

    /**
     * Der Split auf einem Konto <b>innerhalb der Planung</b> SCH000259 – die Testdatei bringt in ihrer
     * gewöhnlichen Transaktion selbst schon einen Split auf A000003/A000001 mit, den die Suche sonst
     * zuerst träfe.
     */
    private static String splitOn(String xml, String accountId) {
        Matcher block = Pattern.compile("<SCHEDULED_TX\\b[^>]*\\bid=\"SCH000259\"[^>]*>.*?</SCHEDULED_TX>",
                Pattern.DOTALL).matcher(xml);
        assertTrue("Planung SCH000259 nicht gefunden", block.find());
        Matcher m = Pattern.compile("<SPLIT\\b[^>]*\\baccount=\"" + accountId + "\"[^>]*/>")
                .matcher(block.group());
        assertTrue("kein Split auf " + accountId, m.find());
        return m.group();
    }

    private static double fractionOf(String splitXml, String attribute) {
        Matcher m = Pattern.compile("\\b" + attribute + "=\"([^\"]*)\"").matcher(splitXml);
        assertTrue(attribute + " fehlt", m.find());
        String f = m.group(1);
        int slash = f.indexOf('/');
        return slash < 0 ? Double.parseDouble(f)
                : Double.parseDouble(f.substring(0, slash)) / Double.parseDouble(f.substring(slash + 1));
    }

    @Test
    public void korrigiertStückzahlUndKursBeiNeuemPreis() throws IOException {
        KmyDocument original = doc();
        KmyExporter exporter = new KmyExporter(original, ctx);
        String updated = exporter.applyShareCorrections(original.xml(),
                Collections.singletonList(advance("SCH000259", 500.0 / 90.0)));

        String split = splitOn(updated, "A000003");
        assertEquals("Stückzahl", 500.0 / 90.0, fractionOf(split, "shares"), 1e-6);
        assertEquals("Kurs", 90.0, fractionOf(split, "price"), 0.01);
        assertEquals("der feste Euro-Betrag bleibt unverändert", 500.0, fractionOf(split, "value"), 1e-9);
    }

    @Test
    public void dasGeldkontoBleibtUnberuehrt() throws IOException {
        KmyDocument original = doc();
        KmyExporter exporter = new KmyExporter(original, ctx);
        String updated = exporter.applyShareCorrections(original.xml(),
                Collections.singletonList(advance("SCH000259", 6.0)));

        String moneySplit = splitOn(updated, "A000001");
        assertEquals(-500.0, fractionOf(moneySplit, "shares"), 1e-9);
        assertEquals(-500.0, fractionOf(moneySplit, "value"), 1e-9);
    }

    @Test
    public void ohneNeueStückzahlBleibtDieDateiUnveraendert() throws IOException {
        KmyDocument original = doc();
        KmyExporter exporter = new KmyExporter(original, ctx);
        String updated = exporter.applyShareCorrections(original.xml(),
                Collections.singletonList(advance("SCH000259", null)));

        assertEquals(original.xml(), updated);
    }

    @Test
    public void unbekannteRegelBleibtUnberuehrt() throws IOException {
        KmyDocument original = doc();
        KmyExporter exporter = new KmyExporter(original, ctx);
        String updated = exporter.applyShareCorrections(original.xml(),
                Collections.singletonList(advance("SCH999999", 6.0)));

        assertEquals(original.xml(), updated);
    }

    @Test
    public void leereListeBleibtUnveraendert() throws IOException {
        KmyDocument original = doc();
        KmyExporter exporter = new KmyExporter(original, ctx);
        assertEquals(original.xml(), exporter.applyShareCorrections(original.xml(), null));
    }
}
