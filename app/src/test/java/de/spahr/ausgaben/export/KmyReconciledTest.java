package de.spahr.ausgaben.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.KmyPendingDelete;

/**
 * In KMyMoney abgeglichene Buchungen ({@code reconcileflag="2"}): Der Import übernimmt den Status, der
 * Export lässt solche Transaktionen aus, und die Selbstprüfung schlägt an, wenn doch eine angefasst
 * wurde. Grundlage ist {@code src/test/resources/kmy/reconciled.xml}: T…1 ist nur „geklärt" (1),
 * T…5 auf der Seite des Girokontos abgeglichen (2).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KmyReconciledTest {

    private static final String T5 = "T000000000000000005";

    private final Context ctx = ApplicationProvider.getApplicationContext();

    private KmyDocument doc() throws Exception {
        return new KmyDocument(KmyRobustnessTest.fixture("reconciled.xml"), ctx);
    }

    private static String blockOf(String xml, String txId) {
        int start = xml.lastIndexOf("<TRANSACTION", xml.indexOf("id=\"" + txId + "\""));
        return xml.substring(start, xml.indexOf("</TRANSACTION>", start));
    }

    private static Booking am(List<Booking> liste, String datum) {
        for (Booking b : liste) {
            if (b.createdAt == KmyDocument.parseKmyDate(datum)) {
                return b;
            }
        }
        throw new AssertionError("keine Buchung am " + datum);
    }

    // ---- Import ----

    @Test
    public void importSetztDenStatus_nurBeiFlagZwei() throws Exception {
        KmyImporter imp = new KmyImporter(doc(), ctx);
        assertTrue("Flag 2 auf dem Kontosplit", am(imp.bookingsForAccount("Girokonto"), "2026-01-08").reconciled);
        List<Booking> bar = imp.bookingsForAccount("Bargeld");
        assertFalse("Flag 1 ist nur geklärt", am(bar, "2026-01-05").reconciled);
        assertFalse("Flag 0", am(bar, "2026-01-06").reconciled);
        // Derselbe Weg in einem Durchlauf über mehrere Konten.
        assertTrue(am(imp.bookingsForAccounts(Arrays.asList("Bargeld", "Girokonto"), null)
                .get("Girokonto"), "2026-01-08").reconciled);
    }

    /** Es genügt ein Split der Transaktion – auch wenn es der der Gegenseite ist. */
    @Test
    public void importSetztDenStatus_auchVonDerGegenseiteHer() throws Exception {
        // Die Umbuchung T…2 (Bargeld → Girokonto) wird nur auf der Seite des Girokontos abgeglichen.
        String xml = doc().xml();
        int start = xml.indexOf("<TRANSACTION id=\"T000000000000000002\"");
        int giro = xml.indexOf("account=\"A000002\"", start);
        int flag = xml.lastIndexOf("reconcileflag=\"0\"", giro);
        assertTrue(flag > start);
        xml = xml.substring(0, flag) + "reconcileflag=\"2\"" + xml.substring(flag + 17);
        KmyImporter imp = new KmyImporter(new KmyDocument(xml.getBytes(StandardCharsets.UTF_8), ctx), ctx);
        assertTrue(am(imp.bookingsForAccount("Bargeld"), "2026-01-06").reconciled);
        assertTrue(am(imp.bookingsForAccount("Girokonto"), "2026-01-06").reconciled);
    }

    /** Wird der Abgleich in KMyMoney zurückgenommen, ist die Buchung nach dem Einlesen wieder frei. */
    @Test
    public void ruecknahmeDesAbgleichs_gibtDieBuchungWiederFrei() throws Exception {
        String zurueck = doc().xml().replace("reconcileflag=\"2\"", "reconcileflag=\"0\"");
        KmyImporter imp = new KmyImporter(
                new KmyDocument(zurueck.getBytes(StandardCharsets.UTF_8), ctx), ctx);
        assertFalse(am(imp.bookingsForAccount("Girokonto"), "2026-01-08").reconciled);
    }

    // ---- Export ----

    /** Die in der App geänderte Fassung der abgeglichenen Buchung vom 08.01. */
    private static Booking bearbeitet() {
        Booking b = new Booking();
        b.id = 9;
        b.account = "Girokonto";
        b.category = "Auto";
        b.payee = "Bäcker";
        b.amountCents = 800;
        b.createdAt = KmyDocument.parseKmyDate("2026-01-08");
        b.edited = true;
        b.origAccount = "Girokonto";
        b.origSignedCents = -700;
        b.origCreatedAt = b.createdAt;
        b.origPayee = "Bäcker";
        return b;
    }

    @Test
    public void exportUeberspringtAbgeglicheneAenderung_undLaesstDieBuchungWieSieIst() throws Exception {
        KmyDocument d = doc();
        Booking b = bearbeitet();
        KmyExporter.Result r = new KmyExporter(d, ctx).build(Collections.emptyList(),
                Collections.singletonList(b), new HashMap<>());

        assertEquals(Collections.singletonList(9L), r.reconciledIds);
        assertEquals(1, r.reconciledSkipped.size());
        assertTrue(r.reconciledSkipped.get(0), r.reconciledSkipped.get(0).contains("Bäcker"));
        // Weder geschrieben noch „nicht gefunden" – und die Datei steht an der Stelle wie zuvor.
        assertEquals(Collections.emptyList(), r.writtenIds);
        assertEquals(Collections.emptyList(), r.notFound);
        assertEquals(0, r.updated);
        assertEquals(blockOf(d.xml(), T5), blockOf(r.xml, T5));
        KmyExportCheck.pruefen(d.xml(), r.xml, KmyDocument.gzip(r.xml), r.aenderungen);

        // Die lokale Buchung: Inhalt, Status und reconciled unverändert.
        Booking vorher = bearbeitet();
        assertEquals(vorher.amountCents, b.amountCents);
        assertEquals(vorher.category, b.category);
        assertEquals(vorher.account, b.account);
        assertEquals(vorher.createdAt, b.createdAt);
        assertTrue(b.edited);
        assertFalse(b.exported);
        assertFalse("reconciled setzt allein der Import", b.reconciled);
        assertEquals(vorher.origSignedCents, b.origSignedCents);
        assertEquals(vorher.origAccount, b.origAccount);
    }

    /** Im selben Lauf geht alles andere normal durch. */
    @Test
    public void exportSchreibtDenRestTrotzdem() throws Exception {
        KmyDocument d = doc();
        Booking andere = new Booking();
        andere.id = 1;
        andere.account = "Bargeld";
        andere.category = "Essen";
        andere.amountCents = 400;
        andere.createdAt = KmyDocument.parseKmyDate("2026-01-05");
        andere.edited = true;
        andere.origAccount = "Bargeld";
        andere.origSignedCents = -250;
        andere.origCreatedAt = andere.createdAt;
        KmyExporter.Result r = new KmyExporter(d, ctx).build(Collections.emptyList(),
                Arrays.asList(bearbeitet(), andere), new HashMap<>());

        assertEquals(Collections.singletonList(9L), r.reconciledIds);
        assertEquals("geklärt (Flag 1) bleibt bearbeitbar", Collections.singletonList(1L), r.writtenIds);
        assertEquals(blockOf(d.xml(), T5), blockOf(r.xml, T5));
        KmyExportCheck.pruefen(d.xml(), r.xml, KmyDocument.gzip(r.xml), r.aenderungen);
    }

    @Test
    public void exportUeberspringtAbgeglicheneLoeschung_dieVormerkungBleibt() throws Exception {
        KmyDocument d = doc();
        KmyPendingDelete del = new KmyPendingDelete("Girokonto", -700,
                KmyDocument.parseKmyDate("2026-01-08"), "Bäcker", 1L);
        del.id = 4;
        KmyExporter.DeleteResult r = new KmyExporter(d, ctx)
                .removeTransactions(d.xml(), Collections.singletonList(del));

        assertEquals("nicht aufgelöst – sie bleibt vorgemerkt", Collections.emptyList(), r.resolvedIds);
        assertEquals(Collections.singletonList(4L), r.reconciledIds);
        assertEquals(1, r.reconciledSkipped.size());
        assertEquals(d.xml(), r.xml);
    }

    // ---- Selbstprüfung ----

    private static void faelltDurch(String alt, String neu, KmyAenderungen erwartet, String grund)
            throws Exception {
        try {
            KmyExportCheck.pruefen(alt, neu, KmyDocument.gzip(neu), erwartet);
            fail("die Prüfung hätte anschlagen müssen");
        } catch (KmyExportCheck.Failed e) {
            assertTrue(e.getMessage(), e.getMessage().contains(grund));
        }
    }

    private static java.util.Map<String, KmyBruch> soll(String konto1, long cent1, String konto2,
                                                        long cent2) {
        java.util.Map<String, KmyBruch> m = new HashMap<>();
        m.put(konto1, KmyBruch.ausCent(cent1));
        m.put(konto2, KmyBruch.ausCent(cent2));
        return m;
    }

    /**
     * Selbst wenn ein Export die Änderung ordentlich ansagte und die Beträge stimmten: Ein vorher
     * abgeglichener Split darf nicht anders dastehen.
     */
    @Test
    public void veraenderterAbgeglichenerSplit_faelltDurch() throws Exception {
        String alt = doc().xml();
        KmyAenderungen angesagt = new KmyAenderungen();
        angesagt.geaendert(T5).soll = soll("A000002", -700, "A000003", 700);
        int block = alt.indexOf("<TRANSACTION id=\"" + T5 + "\"");
        assertTrue(block > 0);
        String kopf = alt.substring(0, block);
        String rest = alt.substring(block);

        // Nur die Notiz des abgeglichenen Splits – Beträge und Konten bleiben.
        faelltDurch(alt, kopf + rest.replaceFirst("reconcileflag=\"2\" value=\"-700/100\" "
                        + "shares=\"-700/100\" price=\"1/1\" memo=\"\"",
                "reconcileflag=\"2\" value=\"-700/100\" shares=\"-700/100\" price=\"1/1\" memo=\"x\""),
                angesagt, "abgeglichener Split S0001");
        // Der Abgleich selbst zurückgesetzt.
        faelltDurch(alt, kopf + rest.replaceFirst("reconcileflag=\"2\"", "reconcileflag=\"0\""),
                angesagt, "abgeglichener Split S0001");
        // Die nicht abgeglichene Gegenseite dürfte sich ändern – das allein ist kein Verstoß.
        String gegenseite = kopf + rest.replaceFirst("reconcileflag=\"0\" value=\"700/100\" "
                        + "shares=\"700/100\" price=\"1/1\" memo=\"\"",
                "reconcileflag=\"0\" value=\"700/100\" shares=\"700/100\" price=\"1/1\" memo=\"x\"");
        assertFalse(gegenseite.equals(alt));
        KmyExportCheck.pruefen(alt, gegenseite, KmyDocument.gzip(gegenseite), angesagt);
    }

    @Test
    public void fehlenderAbgeglichenerSplit_faelltDurch() throws Exception {
        String alt = doc().xml();
        int block = alt.indexOf("<TRANSACTION id=\"" + T5 + "\"");
        int ende = alt.indexOf("</TRANSACTION>", block) + "</TRANSACTION>".length();

        // Die ganze Transaktion gelöscht – angesagt und im Zähler nachgezogen.
        KmyAenderungen geloescht = new KmyAenderungen();
        geloescht.geloescht(T5);
        faelltDurch(alt, (alt.substring(0, block) + alt.substring(ende))
                        .replace("<TRANSACTIONS count=\"5\"", "<TRANSACTIONS count=\"4\""),
                geloescht, "abgeglichene TRANSACTION " + T5 + " fehlt");

        // Nur der abgeglichene Split heraus, die Absicht passend dazu angesagt.
        int split = alt.indexOf("<SPLIT id=\"S0001\"", block);
        int splitEnde = alt.indexOf("/>", split) + 2;
        KmyAenderungen geaendert = new KmyAenderungen();
        java.util.Map<String, KmyBruch> nurKategorie = new HashMap<>();
        nurKategorie.put("A000003", KmyBruch.ausCent(700));
        geaendert.geaendert(T5).soll = nurKategorie;
        faelltDurch(alt, alt.substring(0, split) + alt.substring(splitEnde), geaendert,
                "abgeglichener Split S0001");
    }
}
