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
        int id = xml.indexOf(" id=\"" + txId + "\"");
        assertTrue("Transaktion " + txId + " fehlt", id >= 0);
        int start = xml.lastIndexOf("<TRANSACTION", id);
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
        angesagt.neu("T000000000000000099").soll = soll("A000001", -500, "A000003", 500);
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
            ohneEmpfaenger.neu(a.txId).soll = a.soll;
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

    // ---- Salden ----

    private static java.util.Map<String, KmyBruch> soll(String konto1, long cent1, String konto2,
                                                        long cent2) {
        java.util.Map<String, KmyBruch> m = new HashMap<>();
        m.put(konto1, KmyBruch.ausCent(cent1));
        m.put(konto2, KmyBruch.ausCent(cent2));
        return m;
    }

    /** Die id der einen Transaktion, die dieser Export neu angelegt hat. */
    private static String neueId(KmyExporter.Result r) {
        String id = null;
        for (KmyAenderungen.Absicht a : r.aenderungen.transaktionen()) {
            assertEquals(KmyAenderungen.Art.NEU, a.art);
            id = a.txId;
        }
        return id;
    }

    /** Der neue Block trägt einen Cent mehr, als die Buchung der App hergibt. */
    @Test
    public void neueBuchungMitFalschemBetrag_faelltDurch() throws Exception {
        KmyDocument d = doc();
        KmyExporter.Result r = neueBuchung(d);
        String neu = imBlock(r.xml, neueId(r), "\"-111/100\"", "\"-112/100\"");
        faelltDurch(d.xml(), neu, r.aenderungen, "Saldo von Konto A000001");
    }

    @Test
    public void neueBuchungAufFalschemKonto_faelltDurch() throws Exception {
        KmyDocument d = doc();
        KmyExporter.Result r = neueBuchung(d);
        // Die Kategorie „Auto" statt „Essen": die Summe der Transaktion stimmt weiter, das Konto nicht.
        String neu = imBlock(r.xml, neueId(r), "account=\"A000003\"", "account=\"A000004\"");
        faelltDurch(d.xml(), neu, r.aenderungen, "Saldo von Konto A00000");
    }

    @Test
    public void neueBuchungMitFalschemNenner_faelltDurch() throws Exception {
        KmyDocument d = doc();
        KmyExporter.Result r = neueBuchung(d);
        String neu = imBlock(r.xml, neueId(r), "\"-111/100\"", "\"-111/10\"");
        faelltDurch(d.xml(), neu, r.aenderungen, "Saldo von Konto A000001");
    }

    @Test
    public void neueBuchungMitUnlesbaremBetrag_faelltDurch() throws Exception {
        KmyDocument d = doc();
        KmyExporter.Result r = neueBuchung(d);
        String neu = imBlock(r.xml, neueId(r), "value=\"-111/100\"", "value=\"elf\"");
        faelltDurch(d.xml(), neu, r.aenderungen, "kein Betrag");
    }

    @Test
    public void neueBuchungOhneSplit_faelltDurch() throws Exception {
        KmyDocument d = doc();
        KmyExporter.Result r = neueBuchung(d);
        int[] b = block(r.xml, neueId(r));
        int split = r.xml.indexOf("<SPLIT ", r.xml.indexOf("id=\"S0001\"", b[0]));
        int ende = r.xml.indexOf("/>", split) + 2;
        assertTrue(split > b[0] && ende < b[1]);
        faelltDurch(d.xml(), r.xml.substring(0, split) + r.xml.substring(ende), r.aenderungen,
                "Saldo von Konto A000003");
    }

    /** Die geänderte Buchung steht mit dem alten statt dem neuen Betrag da. */
    @Test
    public void geaenderteBuchungMitFalschemBetrag_faelltDurch() throws Exception {
        KmyDocument d = doc();
        Booking b = ausgabe(1, 400);
        b.createdAt = KmyDocument.parseKmyDate("2026-01-06");
        b.edited = true;
        b.origAccount = "Bargeld";
        b.origSignedCents = -1000;
        b.origCreatedAt = b.createdAt;
        // Aus der Umbuchung Bargeld → Girokonto wird eine Ausgabe für Essen.
        KmyExporter.Result r = new KmyExporter(d, ctx).build(Collections.emptyList(),
                Collections.singletonList(b), new HashMap<>());
        assertEquals(1, r.updated);
        besteht(d.xml(), r.xml, r.aenderungen);
        faelltDurch(d.xml(), imBlock(r.xml, T2, "\"400/100\"", "\"401/100\""), r.aenderungen,
                "Saldo von Konto A000003");
    }

    @Test
    public void neueUmbuchung_besteht() throws Exception {
        KmyDocument d = doc();
        Booking b = new Booking();
        b.id = 7;
        b.account = "Bargeld";
        b.transferAccount = "Girokonto";
        b.isTransfer = true;
        b.amountCents = 2000;
        b.createdAt = KmyDocument.parseKmyDate("2026-02-02");
        KmyExporter.Result r = new KmyExporter(d, ctx).build(Collections.singletonList(b),
                Collections.emptyList(), new HashMap<>());
        assertEquals(Collections.emptyList(), r.skipped);
        besteht(d.xml(), r.xml, r.aenderungen);
        // Quelle und Ziel vertauscht: jede Transaktion für sich ausgeglichen, die Konten nicht.
        String id = neueId(r);
        String vertauscht = imBlock(imBlock(imBlock(r.xml, id, "account=\"A000001\"", "account=\"X\""),
                id, "account=\"A000002\"", "account=\"A000001\""), id, "account=\"X\"",
                "account=\"A000002\"");
        faelltDurch(d.xml(), vertauscht, r.aenderungen, "Saldo von Konto A00000");
    }

    /** Eine Buchung ohne Kategorie geht bewusst mit nur einer Seite in die Datei. */
    @Test
    public void neueBuchungOhneKategorie_besteht() throws Exception {
        KmyDocument d = doc();
        Booking b = ausgabe(8, 333);
        b.category = "";
        KmyExporter.Result r = new KmyExporter(d, ctx).build(Collections.singletonList(b),
                Collections.emptyList(), new HashMap<>());
        assertEquals(Collections.emptyList(), r.skipped);
        besteht(d.xml(), r.xml, r.aenderungen);
    }

    // ---- Neue und geänderte Transaktionen sind in sich stimmig ----

    /**
     * Zwei neue Buchungen auf denselben Konten, ein Cent wandert von der einen in die andere: Die
     * Salden der Konten stimmen weiter, die einzelne Transaktion nicht.
     */
    @Test
    public void unausgeglicheneNeueBuchung_faelltDurch() throws Exception {
        KmyDocument d = doc();
        KmyExporter.Result r = new KmyExporter(d, ctx).build(
                Arrays.asList(ausgabe(5, 111), ausgabe(6, 222)), Collections.emptyList(), new HashMap<>());
        assertEquals(2, r.writtenIds.size());
        besteht(d.xml(), r.xml, r.aenderungen);
        String a = null;
        String b = null;
        for (KmyAenderungen.Absicht ab : r.aenderungen.transaktionen()) {
            if (a == null) {
                a = ab.txId;
            } else {
                b = ab.txId;
            }
        }
        String neu = imBlock(imBlock(r.xml, a, "\"-111/100\"", "\"-112/100\""),
                b, "\"-222/100\"", "\"-221/100\"");
        faelltDurch(d.xml(), neu, r.aenderungen, a + ": Summe der Splits ist -1/100 statt 0/1");
    }

    /** Ausgeglichen und mit richtigen Kontosalden – aber in der Transaktion sind die Seiten vertauscht. */
    @Test
    public void vertauschteSeitenZwischenZweiBuchungen_faelltDurch() throws Exception {
        KmyDocument d = doc();
        Booking einnahme = ausgabe(6, 111);
        einnahme.isIncome = true;
        KmyExporter.Result r = new KmyExporter(d, ctx).build(
                Arrays.asList(ausgabe(5, 111), einnahme), Collections.emptyList(), new HashMap<>());
        assertEquals(2, r.writtenIds.size());
        besteht(d.xml(), r.xml, r.aenderungen);
        // Aus Ausgabe und Einnahme über je 1,11 werden zwei Einnahmen bzw. Ausgaben vertauscht:
        // jede Transaktion summiert sich auf 0, jedes Konto behält seinen Saldo.
        String neu = r.xml;
        for (KmyAenderungen.Absicht ab : r.aenderungen.transaktionen()) {
            neu = imBlock(imBlock(imBlock(neu, ab.txId, "\"-111/100\"", "\"X\""),
                    ab.txId, "\"111/100\"", "\"-111/100\""), ab.txId, "\"X\"", "\"111/100\"");
        }
        faelltDurch(d.xml(), neu, r.aenderungen, "auf Konto A00000");
    }

    @Test
    public void sharesWeichenVomValueAb_faelltDurch() throws Exception {
        KmyDocument d = doc();
        KmyExporter.Result r = neueBuchung(d);
        faelltDurch(d.xml(), imBlock(r.xml, neueId(r), "shares=\"-111/100\"", "shares=\"-111/10\""),
                r.aenderungen, "trägt shares -111/10 bei value -111/100");
    }

    @Test
    public void verweisInsLeere_faelltDurch() throws Exception {
        KmyDocument d = doc();
        KmyExporter.Result r = neueBuchung(d);
        String id = neueId(r);
        faelltDurch(d.xml(), imBlock(r.xml, id, "payee=\"P000002\"", "payee=\"P000099\""), r.aenderungen,
                "Empfänger \"P000099\" gibt es nicht");
        faelltDurch(d.xml(), imBlock(r.xml, id, "commodity=\"EUR\"", "commodity=\"XXX\""), r.aenderungen,
                "Währung \"XXX\"");

        // Ein Konto, das es nicht gibt – so angesagt, dass die Saldoprüfung nichts dagegen hat.
        KmyAenderungen angesagt = new KmyAenderungen();
        angesagt.neu(id).soll = soll("A000001", -111, "A000999", 111);
        angesagt.neuerEmpfaenger("P000002");
        faelltDurch(d.xml(), imBlock(r.xml, id, "account=\"A000003\"", "account=\"A000999\""), angesagt,
                "Konto \"A000999\" gibt es nicht");
    }

    @Test
    public void doppelteSplitId_faelltDurch() throws Exception {
        KmyDocument d = doc();
        KmyExporter.Result r = neueBuchung(d);
        faelltDurch(d.xml(), imBlock(r.xml, neueId(r), "id=\"S0002\"", "id=\"S0001\""), r.aenderungen,
                "Split-id \"S0001\" fehlt oder ist doppelt");
    }

    /** Eine neue Transaktion unter einer id, die es schon gibt. */
    @Test
    public void neueIdKollidiertMitVorhandener_faelltDurch() throws Exception {
        KmyDocument d = doc();
        KmyExporter.Result r = neueBuchung(d);
        String id = neueId(r);
        KmyAenderungen angesagt = new KmyAenderungen();
        angesagt.neu(T1).soll = soll("A000001", -111, "A000003", 111);
        angesagt.neuerEmpfaenger("P000002");
        faelltDurch(d.xml(), r.xml.replace("id=\"" + id + "\"", "id=\"" + T1 + "\""), angesagt,
                "TRANSACTION " + T1);
    }

    @Test
    public void unmoeglichesDatum_faelltDurch() throws Exception {
        KmyDocument d = doc();
        KmyExporter.Result r = neueBuchung(d);
        String id = neueId(r);
        faelltDurch(d.xml(), imBlock(r.xml, id, "postdate=\"2026-02-01\"", "postdate=\"2026-02-30\""),
                r.aenderungen, "ist kein Datum");
        faelltDurch(d.xml(), imBlock(r.xml, id, "postdate=\"2026-02-01\"", "postdate=\"01.02.2026\""),
                r.aenderungen, "ist kein Datum");
        faelltDurch(d.xml(), imBlock(r.xml, id, "postdate=\"2026-02-01\"", "postdate=\"\""),
                r.aenderungen, "ist kein Datum");
    }

    @Test
    public void datumspruefung() {
        assertTrue(KmyExportCheck.istDatum("2026-02-28"));
        assertTrue(KmyExportCheck.istDatum("2024-02-29"));
        assertTrue(KmyExportCheck.istDatum("2000-02-29"));
        for (String s : new String[]{"2026-02-29", "1900-02-29", "2026-13-01", "2026-00-10",
                "2026-04-31", "2026-1-01", "2026-01-1", "26-01-01", "2026/01/01", "2026-01-00", ""}) {
            assertTrue(s, !KmyExportCheck.istDatum(s));
        }
    }

    /** An einer Wertpapier-Transaktion ändert der Export nur die Notiz – und nur das geht durch. */
    @Test
    public void wertpapierbuchung_nurDieNotiz() throws Exception {
        KmyDocument d = new KmyDocument(KmyRobustnessTest.fixture("security-tx.xml"), ctx);
        Booking b = new Booking();
        b.id = 3;
        b.account = "Verrechnungskonto";
        b.transferAccount = "Musterfonds";
        b.isTransfer = true;
        b.amountCents = 101000;
        b.note = "BELEG:2026/kauf.pdf";
        b.createdAt = KmyDocument.parseKmyDate("2026-03-10");
        b.edited = true;
        b.origAccount = "Verrechnungskonto";
        b.origSignedCents = -101000;
        b.origCreatedAt = b.createdAt;
        KmyExporter.Result r = new KmyExporter(d, ctx).build(Collections.emptyList(),
                Collections.singletonList(b), new HashMap<>());
        assertEquals(1, r.updated);
        assertEquals(KmyAenderungen.Art.NUR_NOTIZ, r.aenderungen.zu(T1).art);
        assertTrue(r.xml.contains("memo=\"BELEG:2026/kauf.pdf\""));
        besteht(d.xml(), r.xml, r.aenderungen);

        // Dabei die Stückzahl verändert: kein Konto bewegt sich, die Summe bleibt – und doch falsch.
        faelltDurch(d.xml(), imBlock(r.xml, T1, "shares=\"20/1\"", "shares=\"21/1\""), r.aenderungen,
                "über die Notiz hinaus geändert");
        faelltDurch(d.xml(), imBlock(r.xml, T1, "postdate=\"2026-03-10\"", "postdate=\"2026-03-11\""),
                r.aenderungen, "mehr als die Notiz geändert");
    }

    // ---- Einnahme- und Ausgabeseite ----

    /** edited.xml kennt nur Ausgabekategorien – hier kommt „Lohn" im Einnahmenbaum dazu. */
    private KmyDocument mitEinnahmekategorie() throws Exception {
        String xml = alt().replace("<ACCOUNTS count=\"4\">", "<ACCOUNTS count=\"6\">\n"
                + "    <ACCOUNT id=\"A000005\" parentaccount=\"AStd::Income\" lastreconciled=\"\" "
                + "lastmodified=\"2026-01-01\" institution=\"\" opened=\"2026-01-01\" number=\"\" "
                + "type=\"12\" name=\"Lohn\" description=\"\" currency=\"EUR\"/>\n"
                + "    <ACCOUNT id=\"A000006\" parentaccount=\"A000005\" lastreconciled=\"\" "
                + "lastmodified=\"2026-01-01\" institution=\"\" opened=\"2026-01-01\" number=\"\" "
                + "type=\"12\" name=\"Bonus\" description=\"\" currency=\"EUR\"/>");
        return new KmyDocument(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8), ctx);
    }

    private KmyExporter.Result eine(KmyDocument d, Booking b) {
        KmyExporter.Result r = new KmyExporter(d, ctx).build(Collections.singletonList(b),
                Collections.emptyList(), new HashMap<>());
        assertEquals(Collections.emptyList(), r.skipped);
        return r;
    }

    @Test
    public void kategorieImFalschenBaum_faelltDurch() throws Exception {
        KmyDocument d = mitEinnahmekategorie();
        // Als Ausgabekategorie geführt, liegt aber im Einnahmenbaum.
        Booking b = ausgabe(5, 111);
        b.category = "Lohn";
        b.categoryIsIncome = false;
        b.payee = "Chef";
        KmyExporter.Result r = eine(d, b);
        faelltDurch(d.xml(), r.xml, r.aenderungen, "Kategorie A000005 liegt im Einnahmenbaum");
        // Die Meldung nennt die Buchung, damit man sie findet.
        faelltDurch(d.xml(), r.xml, r.aenderungen, "(Chef, 2026-02-01)");

        // Und umgekehrt: als Einnahmekategorie geführt, liegt im Ausgabenbaum.
        Booking c = ausgabe(6, 111);
        c.isIncome = true;
        c.categoryIsIncome = true;
        KmyExporter.Result r2 = eine(d, c);
        faelltDurch(d.xml(), r2.xml, r2.aenderungen, "Kategorie A000003 liegt im Ausgabenbaum");
    }

    @Test
    public void kategorieImRichtigenBaum_besteht() throws Exception {
        KmyDocument d = mitEinnahmekategorie();
        Booking lohn = ausgabe(5, 250000);
        lohn.isIncome = true;
        lohn.category = "Lohn";
        lohn.categoryIsIncome = true;
        KmyExporter.Result r = eine(d, lohn);
        besteht(d.xml(), r.xml, r.aenderungen);

        // Eine Erstattung: Geld kommt herein, die Kategorie bleibt eine Ausgabekategorie. Maßgeblich
        // ist die Seite der Kategorie, nicht die Richtung des Geldes.
        Booking erstattung = ausgabe(6, 111);
        erstattung.isIncome = true;
        erstattung.categoryIsIncome = false;
        r = eine(d, erstattung);
        besteht(d.xml(), r.xml, r.aenderungen);

        // Kennt die App die Seite nicht (Zeile aus der Zeit vor dem Feld), wird nichts behauptet.
        Booking unbekannt = ausgabe(7, 111);
        unbekannt.category = "Lohn";
        unbekannt.categoryIsIncome = null;
        r = eine(d, unbekannt);
        besteht(d.xml(), r.xml, r.aenderungen);
    }

    /** Jeder Teil einer Splitbuchung trägt seine eigene Seite. */
    @Test
    public void splitbuchungMitEinemTeilImFalschenBaum_faelltDurch() throws Exception {
        KmyDocument d = mitEinnahmekategorie();
        Booking b = ausgabe(5, 300);
        b.category = "";
        java.util.Map<Long, java.util.List<de.spahr.ausgaben.db.BookingSplit>> teile = new HashMap<>();
        teile.put(5L, Arrays.asList(
                new de.spahr.ausgaben.db.BookingSplit(5, "Essen", 100, false),
                new de.spahr.ausgaben.db.BookingSplit(5, "Bonus", 200, false)));
        KmyExporter.Result r = new KmyExporter(d, ctx).build(Collections.singletonList(b),
                Collections.emptyList(), teile);
        assertEquals(Collections.emptyList(), r.skipped);
        faelltDurch(d.xml(), r.xml, r.aenderungen, "liegt im Einnahmenbaum");

        teile.put(5L, Arrays.asList(
                new de.spahr.ausgaben.db.BookingSplit(5, "Essen", 100, false),
                new de.spahr.ausgaben.db.BookingSplit(5, "Bonus", 200, true)));
        r = new KmyExporter(d, ctx).build(Collections.singletonList(b), Collections.emptyList(), teile);
        besteht(d.xml(), r.xml, r.aenderungen);
    }

    /** Der Baum eines Kontos: am Typ, und wo der nichts sagt, über die Elternkonten bis zur Wurzel. */
    @Test
    public void baumEinesKontos() throws Exception {
        String xml = mitEinnahmekategorie().xml()
                .replace("type=\"12\" name=\"Bonus\"", "type=\"\" name=\"Bonus\"");
        KmyExportCheck.Stand datei = KmyExportCheck.erfassen(xml, "Test", Collections.emptySet());
        assertEquals(Boolean.TRUE, KmyExportCheck.einnahmenbaum("A000005", datei));
        assertEquals("ohne eigenen Typ: über das Elternkonto", Boolean.TRUE,
                KmyExportCheck.einnahmenbaum("A000006", datei));
        assertEquals(Boolean.FALSE, KmyExportCheck.einnahmenbaum("A000003", datei));
        assertEquals("ein Bestandskonto liegt in keinem der beiden", null,
                KmyExportCheck.einnahmenbaum("A000001", datei));
        assertEquals(null, KmyExportCheck.einnahmenbaum("A999999", datei));
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
