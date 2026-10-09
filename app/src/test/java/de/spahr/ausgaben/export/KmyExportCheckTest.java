package de.spahr.ausgaben.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.KmyPendingDelete;
import de.spahr.ausgaben.db.ScheduledAdvance;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;

/**
 * Die Selbstprüfung vor dem Hochladen: Ein echter Export muss sie bestehen, eine beschädigte Fassung
 * nicht. Grundlage ist {@code src/test/resources/kmy/edited.xml} (vier Buchungen).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KmyExportCheckTest {

    private final Context ctx = ApplicationProvider.getApplicationContext();

    private String alt() throws Exception {
        return new KmyDocument(KmyRobustnessTest.fixture("edited.xml"), ctx).xml();
    }

    private static void besteht(String alt, String neu, KmyAenderungen erwartet) throws Exception {
        KmyExportCheck.pruefen(alt, neu, KmyDocument.gzip(neu), erwartet);
    }

    private static void faelltDurch(String alt, String neu, KmyAenderungen erwartet,
                                    String grundEnthaelt) throws Exception {
        try {
            KmyExportCheck.pruefen(alt, neu, KmyDocument.gzip(neu), erwartet);
            fail("die Prüfung hätte anschlagen müssen");
        } catch (KmyExportCheck.Failed e) {
            assertTrue(e.getMessage(), e.getMessage().contains(grundEnthaelt));
        }
    }

    private static final String T1 = "T000000000000000001";
    private static final String T2 = "T000000000000000002";
    private static final String T3 = "T000000000000000003";

    /** Anfang und Ende des Blocks zu dieser Transaktions-id. */
    private static int[] block(String xml, String txId) {
        int start = xml.indexOf("<TRANSACTION id=\"" + txId + "\"");
        assertTrue("Transaktion " + txId + " fehlt", start >= 0);
        return new int[]{start, xml.indexOf("</TRANSACTION>", start) + "</TRANSACTION>".length()};
    }

    /** Ersetzt {@code von} durch {@code nach}, aber nur innerhalb dieser einen Transaktion. */
    private static String imBlock(String xml, String txId, String von, String nach) {
        int[] b = block(xml, txId);
        String alt = xml.substring(b[0], b[1]);
        assertTrue(von + " steht nicht in " + txId, alt.contains(von));
        return xml.substring(0, b[0]) + alt.replace(von, nach) + xml.substring(b[1]);
    }

    private static Booking ausgabe(long id, long cents) {
        Booking b = new Booking();
        b.id = id;
        b.account = "Bargeld";
        b.category = "Essen";
        b.amountCents = cents;
        b.isIncome = false;
        b.createdAt = KmyDocument.parseKmyDate("2026-02-01");
        return b;
    }

    /** Ein echter Export einer neuen Buchung – die Grundlage, an der die Verstöße angebracht werden. */
    private KmyExporter.Result neueBuchung(KmyDocument d) {
        Booking neu = ausgabe(5, 111);
        neu.payee = "Kiosk";
        return new KmyExporter(d, ctx).build(Collections.singletonList(neu),
                Collections.emptyList(), new HashMap<>());
    }

    private KmyDocument doc() throws Exception {
        return new KmyDocument(KmyRobustnessTest.fixture("edited.xml"), ctx);
    }

    // ---- Was schon vorher galt ----

    @Test
    public void echterExport_bestehtDiePruefung() throws Exception {
        KmyDocument d = doc();
        KmyExporter.Result r = neueBuchung(d);
        assertEquals(1, r.newPayees);
        besteht(d.xml(), r.xml, r.aenderungen);
    }

    @Test
    public void unveraenderteDatei_besteht() throws Exception {
        String alt = alt();
        besteht(alt, alt, KmyAenderungen.keine());
    }

    @Test
    public void abgeschnitteneDatei_faelltDurch() throws Exception {
        String alt = alt();
        faelltDurch(alt, alt.substring(0, alt.length() / 2), KmyAenderungen.keine(), "wohlgeformt");
    }

    @Test
    public void verlorenesKonto_faelltDurch() throws Exception {
        String alt = alt();
        // In der Testdatei sind die Konten selbstschließend geschrieben.
        int start = alt.indexOf("<ACCOUNT ");
        int ende = alt.indexOf("/>", start) + 2;
        faelltDurch(alt, alt.substring(0, start) + alt.substring(ende), KmyAenderungen.keine(),
                "ACCOUNTS");
    }

    @Test
    public void verschwundeneBuchung_faelltDurch() throws Exception {
        String alt = alt();
        int[] b = block(alt, T1);
        String ohne = alt.substring(0, b[0]) + alt.substring(b[1]);
        faelltDurch(alt, ohne, KmyAenderungen.keine(), T1 + " verschwunden");
        // Mit angekündigter Löschung und passend nachgezogenem Zähler ist dasselbe in Ordnung.
        KmyAenderungen geloescht = new KmyAenderungen();
        geloescht.geloescht(T1);
        besteht(alt, ohne.replace("<TRANSACTIONS count=\"4\"", "<TRANSACTIONS count=\"3\""), geloescht);
        // Angekündigt war aber genau diese – eine andere zu entfernen, fällt auf.
        int[] b2 = block(alt, T2);
        faelltDurch(alt, (alt.substring(0, b2[0]) + alt.substring(b2[1]))
                .replace("<TRANSACTIONS count=\"4\"", "<TRANSACTIONS count=\"3\""), geloescht, T2);
    }

    @Test
    public void verschobenerZaehler_faelltDurch() throws Exception {
        String alt = alt();
        faelltDurch(alt, alt.replace("<TRANSACTIONS count=\"4\"", "<TRANSACTIONS count=\"5\""),
                KmyAenderungen.keine(), "count");
    }

    @Test
    public void gepackteBytesWeichenAb_faelltDurch() throws Exception {
        String alt = alt();
        try {
            KmyExportCheck.pruefen(alt, alt, KmyDocument.gzip(alt + " "), KmyAenderungen.keine());
            fail("die Prüfung hätte anschlagen müssen");
        } catch (KmyExportCheck.Failed e) {
            assertTrue(e.getMessage(), e.getMessage().contains("gepackt"));
        }
    }

    // ---- Unberührtes bleibt Zeichen für Zeichen stehen ----

    /** Der Export legt eine Buchung an – und nebenbei ändert sich an einer fremden nur der Betrag. */
    @Test
    public void fremdeTransaktionMitAnderemBetrag_faelltDurch() throws Exception {
        KmyDocument d = doc();
        KmyExporter.Result r = neueBuchung(d);
        String neu = imBlock(imBlock(r.xml, T2, "\"-1000/100\"", "\"-1001/100\""),
                T2, "\"1000/100\"", "\"1001/100\"");
        faelltDurch(d.xml(), neu, r.aenderungen, T2 + " verändert");
    }

    @Test
    public void fremdeTransaktionMitAnderemKonto_faelltDurch() throws Exception {
        KmyDocument d = doc();
        KmyExporter.Result r = neueBuchung(d);
        faelltDurch(d.xml(), imBlock(r.xml, T2, "account=\"A000002\"", "account=\"A000004\""),
                r.aenderungen, T2 + " verändert");
    }

    @Test
    public void verschluckterSplit_faelltDurch() throws Exception {
        KmyDocument d = doc();
        KmyExporter.Result r = neueBuchung(d);
        int[] b = block(r.xml, T3);
        int split = r.xml.indexOf("<SPLIT id=\"S0002\"", b[0]);
        int ende = r.xml.indexOf("/>", split) + 2;
        assertTrue(split > 0 && ende < b[1]);
        faelltDurch(d.xml(), r.xml.substring(0, split) + r.xml.substring(ende), r.aenderungen,
                T3 + " verändert");
    }

    @Test
    public void falscherBruchnenner_faelltDurch() throws Exception {
        KmyDocument d = doc();
        KmyExporter.Result r = neueBuchung(d);
        faelltDurch(d.xml(), imBlock(r.xml, T2, "\"-1000/100\"", "\"-1000/10\""), r.aenderungen,
                T2 + " verändert");
    }

    /** Auch eine Änderung, die nichts am Inhalt ändert – ein Leerzeichen –, ist eine Änderung. */
    @Test
    public void fremdeTransaktionNurAndersGeschrieben_faelltDurch() throws Exception {
        String alt = alt();
        faelltDurch(alt, imBlock(alt, T2, "<SPLITS>", "<SPLITS >"), KmyAenderungen.keine(),
                T2 + " verändert");
    }

    @Test
    public void ungemeldeteNeueBuchung_faelltDurch() throws Exception {
        String alt = alt();
        int[] b = block(alt, T3);
        String kopie = alt.substring(b[0], b[1]).replace(T3, "T000000000000000099");
        String neu = (alt.substring(0, b[1]) + kopie + alt.substring(b[1]))
                .replace("<TRANSACTIONS count=\"4\"", "<TRANSACTIONS count=\"5\"");
        faelltDurch(alt, neu, KmyAenderungen.keine(), "T000000000000000099 aufgetaucht");
        KmyAenderungen angesagt = new KmyAenderungen();
        angesagt.neu("T000000000000000099");
        besteht(alt, neu, angesagt);
    }

    @Test
    public void vertauschteReihenfolge_faelltDurch() throws Exception {
        String alt = alt();
        int[] b2 = block(alt, T2);
        int[] b3 = block(alt, T3);
        String neu = alt.substring(0, b2[0]) + alt.substring(b3[0], b3[1]) + alt.substring(b2[1], b3[0])
                + alt.substring(b2[0], b2[1]) + alt.substring(b3[1]);
        faelltDurch(alt, neu, KmyAenderungen.keine(), "TRANSACTION");
    }

    /** Angesagt, aber nicht ausgeführt: auch das ist ein Widerspruch zwischen Wollen und Tun. */
    @Test
    public void angesagteAenderungOhneBlock_faelltDurch() throws Exception {
        String alt = alt();
        KmyAenderungen angesagt = new KmyAenderungen();
        angesagt.geaendert("T000000000000000077");
        faelltDurch(alt, alt, angesagt, "T000000000000000077");
        KmyAenderungen neu = new KmyAenderungen();
        neu.neu(T1);
        faelltDurch(alt, alt, neu, T1);
    }

    @Test
    public void geaenderteBuchung_besteht_undNurSie() throws Exception {
        KmyDocument d = doc();
        Booking b = ausgabe(1, 400);
        b.createdAt = KmyDocument.parseKmyDate("2026-01-07");
        b.edited = true;
        b.origAccount = "Bargeld";
        b.origSignedCents = -500;
        b.origCreatedAt = b.createdAt;
        KmyExporter.Result r = new KmyExporter(d, ctx).build(Collections.emptyList(),
                Collections.singletonList(b), new HashMap<>());
        assertEquals(1, r.updated);
        besteht(d.xml(), r.xml, r.aenderungen);
        // Dieselbe Änderung, als nichts angesagt: fällt durch.
        faelltDurch(d.xml(), r.xml, KmyAenderungen.keine(), T3 + " verändert");
    }

    @Test
    public void geloeschteBuchung_besteht() throws Exception {
        KmyDocument d = doc();
        KmyPendingDelete del = new KmyPendingDelete();
        del.id = 1;
        del.account = "Bargeld";
        del.createdAt = KmyDocument.parseKmyDate("2026-01-06");
        del.signedCents = -1000;
        KmyExporter.DeleteResult r = new KmyExporter(d, ctx)
                .removeTransactions(d.xml(), Collections.singletonList(del));
        assertEquals(1, r.resolvedIds.size());
        besteht(d.xml(), r.xml, r.aenderungen);
        faelltDurch(d.xml(), r.xml, KmyAenderungen.keine(), T2 + " verschwunden");
    }

    @Test
    public void veraenderterEmpfaenger_faelltDurch() throws Exception {
        KmyDocument d = doc();
        KmyExporter.Result r = neueBuchung(d);
        faelltDurch(d.xml(), r.xml.replace("name=\"Bäcker\"", "name=\"Metzger\""), r.aenderungen,
                "PAYEE P000001 verändert");
    }

    @Test
    public void ungemeldeterEmpfaenger_faelltDurch() throws Exception {
        KmyDocument d = doc();
        KmyExporter.Result r = neueBuchung(d);
        // Die Buchung ist angesagt, ihr neuer Empfänger nicht.
        KmyAenderungen ohneEmpfaenger = new KmyAenderungen();
        for (KmyAenderungen.Absicht a : r.aenderungen.transaktionen()) {
            ohneEmpfaenger.neu(a.txId);
        }
        faelltDurch(d.xml(), r.xml, ohneEmpfaenger, "PAYEE P000002 aufgetaucht");
    }

    @Test
    public void veraendertesBudget_faelltDurch() throws Exception {
        String alt = alt();
        faelltDurch(alt, alt.replace("<BUDGETS/>", "<BUDGETS></BUDGETS>"), KmyAenderungen.keine(),
                "BUDGETS");
    }

    @Test
    public void veraendertesKontoattribut_faelltDurch() throws Exception {
        String alt = alt();
        faelltDurch(alt, alt.replace("name=\"Girokonto\"", "name=\"Girokonto2\""),
                KmyAenderungen.keine(), "ACCOUNTS");
    }

    /** Im Dateikopf darf sich allein das Datum der letzten Änderung bewegen. */
    @Test
    public void dateikopf_nurDasAenderungsdatum() throws Exception {
        String alt = alt();
        besteht(alt, alt.replace("<LAST_MODIFIED_DATE date=\"2026-01-01\"/>",
                "<LAST_MODIFIED_DATE date=\"2026-09-09\"/>"), KmyAenderungen.keine());
        faelltDurch(alt, alt.replace("<CREATION_DATE date=\"2026-01-01\"/>",
                "<CREATION_DATE date=\"2026-09-09\"/>"), KmyAenderungen.keine(), "FILEINFO");
        faelltDurch(alt, alt.replace("<FIXVERSION id=\"11\"/>", "<FIXVERSION id=\"12\"/>"),
                KmyAenderungen.keine(), "FILEINFO");
    }

    // ---- Planungen ----

    private static String planung(String id, String postdate) {
        return "<SCHEDULED_TX id=\"" + id + "\" name=\"Miete\" lastPayment=\"2026-01-01\" "
                + "startDate=\"2025-01-01\" endDate=\"\" occurence=\"32\" occurenceMultiplier=\"1\">"
                + "<TRANSACTION id=\"\" postdate=\"" + postdate + "\" memo=\"\" entrydate=\"\" "
                + "commodity=\"EUR\"><SPLITS>"
                + "<SPLIT id=\"S0001\" payee=\"\" reconciledate=\"\" action=\"\" reconcileflag=\"0\" "
                + "value=\"-1000/100\" shares=\"-1000/100\" price=\"1/1\" memo=\"\" account=\"A000002\" "
                + "number=\"\" bankid=\"\"/></SPLITS></TRANSACTION></SCHEDULED_TX>";
    }

    private String mitPlanungen() throws Exception {
        return alt().replace("<SCHEDULES/>", "<SCHEDULES count=\"2\">\n    "
                + planung("SCH000001", "2026-02-01") + "\n    " + planung("SCH000002", "2026-02-01")
                + "\n  </SCHEDULES>");
    }

    @Test
    public void weitergestelltePlanung_besteht_undNurSie() throws Exception {
        String alt = mitPlanungen();
        ScheduledAdvance a = new ScheduledAdvance();
        a.id = 1;
        a.kmyId = "SCH000001";
        a.fromDueMs = KmyDocument.parseKmyDate("2026-02-01");
        a.nextDueMs = KmyDocument.parseKmyDate("2026-03-01");
        a.lastPaymentMs = KmyDocument.parseKmyDate("2026-02-01");
        KmyExporter.ScheduleResult r = KmyExporter.applyScheduleAdvances(alt, Arrays.asList(a));
        assertEquals(1, r.writtenIds.size());
        besteht(alt, r.xml, r.aenderungen);
        // Dieselbe Bewegung an der anderen, nicht angesagten Planung fällt durch.
        String falsch = alt.replace(planung("SCH000002", "2026-02-01"), planung("SCH000002", "2026-03-01"));
        faelltDurch(alt, falsch, r.aenderungen, "SCHEDULED_TX SCH000002 verändert");
        // Und eine verschwundene Planung ebenso.
        faelltDurch(alt, alt.replace(planung("SCH000002", "2026-02-01"), ""), KmyAenderungen.keine(),
                "SCHEDULED_TX");
    }
}
