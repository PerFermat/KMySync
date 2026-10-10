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

import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.BookingSplit;
import de.spahr.ausgaben.db.KmyPendingDelete;
import de.spahr.ausgaben.db.ScheduledAdvance;
import de.spahr.ausgaben.db.SecurityTx;
import de.spahr.ausgaben.db.SecurityTxSplit;

/**
 * Schreiben in eine KMyMoney-Datenbank: Was der Export am XML-Abbild geändert hat, steht danach in
 * den Tabellen – und die Datenbank ergibt abgebildet genau die geprüfte Fassung (die Gegenprobe).
 * Dazu die Werte, die die Datenbank doppelt führt: Zähler, Salden, ausgeschriebene Beträge.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KmySqliteWriterTest {

    private final Context ctx = ApplicationProvider.getApplicationContext();

    private static Booking ausgabe(long id, long cents, String payee) {
        Booking b = new Booking();
        b.id = id;
        b.account = "Bargeld";
        b.category = "Essen";
        b.payee = payee;
        b.amountCents = cents;
        b.createdAt = KmyDocument.parseKmyDate("2026-02-01");
        return b;
    }

    /** Prüft und schreibt wie der Export-Lauf; liefert die neue Datenbank. */
    private byte[] schreibe(byte[] roh, KmyDocument d, String neuXml, KmyAenderungen ae) throws Exception {
        KmyExportCheck.pruefen(d.xml(), neuXml, KmyDocument.gzip(neuXml), ae);
        byte[] neu = KmySqliteWriter.schreibe(ctx, roh, neuXml, ae);
        assertTrue(KmySqlite.istSqlite(neu));
        // Die Gegenprobe von außen noch einmal: frisch gelesen ist es die geprüfte Fassung.
        assertEquals(neuXml, KmyDocument.alsXml(ctx, neu));
        return neu;
    }

    private String frage(byte[] roh, String sql) throws Exception {
        return KmyTestDb.frage(ctx, roh, sql);
    }

    @Test
    public void neueBuchungMitNeuemEmpfaenger() throws Exception {
        byte[] roh = KmyTestDb.ausFixture(ctx, "edited.xml");
        KmyDocument d = new KmyDocument(roh, ctx);
        KmyExporter.Result r = new KmyExporter(d, ctx).build(
                Collections.singletonList(ausgabe(5, 1240, "Kiosk")), Collections.emptyList(), new HashMap<>());
        assertEquals(Collections.emptyList(), r.skipped);
        byte[] neu = schreibe(roh, d, r.xml, r.aenderungen);

        assertEquals("T000000000000000005|N|2026-02-01||EUR", frage(neu,
                "SELECT id, txType, postDate, memo, currencyId FROM kmmTransactions "
                        + "WHERE id = 'T000000000000000005'"));
        // Die Splits: Nummern ab 0, Beträge gekürzt, die ausgeschriebenen Spalten wie KMyMoney sie füllt.
        assertEquals("0|P000002|∅|0|-62/5|-12.4|-62/5|1.00|1/1|1.0000|A000001|2026-02-01\n"
                        + "1|P000002|∅|0|62/5|12.4|62/5|1.00|1/1|1.0000|A000003|2026-02-01",
                frage(neu, "SELECT splitId, payeeId, reconcileDate, reconcileFlag, value, valueFormatted, "
                        + "shares, sharesFormatted, price, priceFormatted, accountId, postDate FROM kmmSplits "
                        + "WHERE transactionId = 'T000000000000000005' ORDER BY splitId"));
        assertEquals("P000002|Kiosk|1|Y", frage(neu,
                "SELECT id, name, matchData, matchIgnoreCase FROM kmmPayees WHERE id = 'P000002'"));
        // Zähler und höchste ids.
        assertEquals("5|10|6|3", frage(neu,
                "SELECT transactions, splits, hiTransactionId, hiPayeeId FROM kmmFileInfo"));
        assertEquals(frage(roh, "SELECT payees + 1 FROM kmmFileInfo"),
                frage(neu, "SELECT payees FROM kmmFileInfo"));
        // Das Änderungsdatum ist mitgezogen.
        assertFalse(frage(neu, "SELECT lastModified FROM kmmFileInfo").equals(
                frage(roh, "SELECT lastModified FROM kmmFileInfo")));
        // Alles andere steht da wie zuvor.
        String andere = "SELECT * FROM kmmSplits WHERE transactionId != 'T000000000000000005' "
                + "ORDER BY transactionId, splitId";
        assertEquals(frage(roh, andere), frage(neu, andere));
        assertEquals(frage(roh, "SELECT * FROM kmmPayees WHERE id != 'P000002' ORDER BY id"),
                frage(neu, "SELECT * FROM kmmPayees WHERE id != 'P000002' ORDER BY id"));
    }

    /** Saldo und Buchungszahl der berührten Konten folgen ihren Splits – die übrigen bleiben. */
    @Test
    public void saldoUndBuchungszahlDerKonten() throws Exception {
        byte[] roh = KmyTestDb.ausFixture(ctx, "edited.xml");
        KmyDocument d = new KmyDocument(roh, ctx);
        KmyExporter.Result r = new KmyExporter(d, ctx).build(
                Collections.singletonList(ausgabe(5, 1240, "")), Collections.emptyList(), new HashMap<>());
        byte[] neu = schreibe(roh, d, r.xml, r.aenderungen);

        // Bargeld: −2,50 −10,00 −5,00 −5,00 −12,40 = −34,90; Essen: 2,50 + 5 + 5 + 12,40 = 24,90.
        assertEquals("A000001|-349/10|-34.9|5\nA000003|249/10|24.9|4", frage(neu,
                "SELECT id, balance, balanceFormatted, transactionCount FROM kmmAccounts "
                        + "WHERE id IN ('A000001', 'A000003') ORDER BY id"));
        // Girokonto und „Auto" hat der Export nicht berührt.
        String unberuehrt = "SELECT * FROM kmmAccounts WHERE id IN ('A000002', 'A000004')";
        assertEquals(frage(roh, unberuehrt), frage(neu, unberuehrt));
    }

    @Test
    public void geaenderteBuchung() throws Exception {
        byte[] roh = KmyTestDb.ausFixture(ctx, "edited.xml");
        KmyDocument d = new KmyDocument(roh, ctx);
        Booking b = ausgabe(1, 400, "Bäcker");
        b.note = "zwei\nZeilen";
        b.createdAt = KmyDocument.parseKmyDate("2026-01-09");
        b.edited = true;
        b.origAccount = "Bargeld";
        b.origSignedCents = -250;
        b.origCreatedAt = KmyDocument.parseKmyDate("2026-01-05");
        b.origPayee = "Bäcker";
        BookingSplit p1 = new BookingSplit(1, "Essen", 100);
        BookingSplit p2 = new BookingSplit(1, "Auto", 300);
        HashMap<Long, java.util.List<BookingSplit>> teile = new HashMap<>();
        teile.put(1L, Arrays.asList(p1, p2));
        KmyExporter.Result r = new KmyExporter(d, ctx).build(Collections.emptyList(),
                Collections.singletonList(b), teile);
        assertEquals(1, r.updated);
        byte[] neu = schreibe(roh, d, r.xml, r.aenderungen);

        // Dieselbe Transaktion, neues Datum; aus zwei Splits sind drei geworden.
        assertEquals("2026-01-09", frage(neu,
                "SELECT postDate FROM kmmTransactions WHERE id = 'T000000000000000001'"));
        assertEquals("0|A000001|-4/1|2026-01-09|zwei\nZeilen\n1|A000003|1/1|2026-01-09|zwei\nZeilen\n"
                        + "2|A000004|3/1|2026-01-09|zwei\nZeilen",
                frage(neu, "SELECT splitId, accountId, value, postDate, memo FROM kmmSplits "
                        + "WHERE transactionId = 'T000000000000000001' ORDER BY splitId"));
        assertEquals("4|9", frage(neu, "SELECT transactions, splits FROM kmmFileInfo"));
        // „Auto" bekommt seine erste Buchung, „Essen" behält drei.
        assertEquals("A000003|3\nA000004|1", frage(neu, "SELECT id, transactionCount FROM kmmAccounts "
                + "WHERE id IN ('A000003', 'A000004') ORDER BY id"));
    }

    @Test
    public void geloeschteBuchung() throws Exception {
        byte[] roh = KmyTestDb.ausFixture(ctx, "edited.xml");
        KmyDocument d = new KmyDocument(roh, ctx);
        KmyPendingDelete del = new KmyPendingDelete("Bargeld", -1000,
                KmyDocument.parseKmyDate("2026-01-06"), "", 1L);
        KmyExporter.DeleteResult r = new KmyExporter(d, ctx)
                .removeTransactions(d.xml(), Collections.singletonList(del));
        assertEquals(1, r.resolvedIds.size());
        byte[] neu = schreibe(roh, d, r.xml, r.aenderungen);

        assertEquals("0", frage(neu, "SELECT count(*) FROM kmmTransactions WHERE id = 'T000000000000000002'"));
        assertEquals("0", frage(neu, "SELECT count(*) FROM kmmSplits WHERE transactionId = 'T000000000000000002'"));
        assertEquals("3|6", frage(neu, "SELECT transactions, splits FROM kmmFileInfo"));
        // Das Girokonto hatte nur diese eine Buchung.
        assertEquals("0/1|0|0", frage(neu,
                "SELECT balance, balanceFormatted, transactionCount FROM kmmAccounts WHERE id = 'A000002'"));
        // Die höchste id bleibt, was sie war – die gelöschte war nicht die letzte.
        assertEquals("5", frage(neu, "SELECT hiTransactionId FROM kmmFileInfo"));
    }

    /** Stichwörter hängen am Split und stehen in ihrer eigenen Tabelle. */
    @Test
    public void buchungMitStichwoertern() throws Exception {
        byte[] roh = KmyTestDb.ausFixture(ctx, "tagged-split.xml");
        KmyDocument d = new KmyDocument(roh, ctx);
        String stichwort = d.tagNames().get(0);
        Booking b = ausgabe(5, 111, "");
        b.account = d.accountNames().get(0);
        b.category = "";
        b.tags = stichwort;
        KmyExporter.Result r = new KmyExporter(d, ctx).build(Collections.singletonList(b),
                Collections.emptyList(), new HashMap<>());
        assertEquals(Collections.emptyList(), r.skipped);
        byte[] neu = schreibe(roh, d, r.xml, r.aenderungen);
        String neueId = null;
        for (KmyAenderungen.Absicht ab : r.aenderungen.transaktionen()) {
            neueId = ab.txId;
        }
        assertEquals(d.tagId(stichwort) + "|0", frage(neu,
                "SELECT tagId, splitId FROM kmmTagSplits WHERE transactionId = '" + neueId + "'"));
    }

    @Test
    public void wertpapierbewegung() throws Exception {
        byte[] roh = KmyTestDb.ausFixture(ctx, "security-tx.xml");
        KmyDocument d = new KmyDocument(roh, ctx);
        SecurityTx tx = new SecurityTx();
        tx.id = 1;
        tx.depot = "Depot";
        tx.securityKmyId = "E000001";
        tx.securityName = "Musterfonds";
        tx.moneyAccount = "Verrechnungskonto";
        tx.action = SecurityTx.BUY;
        tx.shares = 2.5;
        tx.amountCents = 12500;
        tx.feeCents = 100;
        tx.netCents = 12600;
        tx.date = KmyDocument.parseKmyDate("2026-04-01");
        tx.pending = true;
        tx.parts.add(new SecurityTxSplit(0, false, "Bankgebühren", 100, "", 0));
        KmyExporter.SecurityResult r = new KmyExporter(d, ctx)
                .buildSecurityTransactions(d.xml(), Collections.singletonList(tx));
        assertEquals(r.skipped.toString(), 1, r.writtenIds.size());
        byte[] neu = schreibe(roh, d, r.xml, r.aenderungen);

        // Der Wertpapier-Split: Stückzahl und Kurs eigenständig, Aktion „Buy"; die ausgeschriebenen
        // Spalten richten sich nach dem Wertpapier (saf 1000 → drei Stellen, Kursgenauigkeit vier).
        assertEquals("Buy|125/1|5/2|50/1|50.000|50.0000", frage(neu,
                "SELECT action, value, shares, price, sharesFormatted, priceFormatted FROM kmmSplits "
                        + "WHERE transactionId = 'T000000000000000002' AND accountId = 'A000003'"));
        // Bestand des Wertpapierkontos: 20 vorher + 2,5.
        assertEquals("45/2|22.5|2", frage(neu,
                "SELECT balance, balanceFormatted, transactionCount FROM kmmAccounts WHERE id = 'A000003'"));
    }

    @Test
    public void weitergestelltePlanung() throws Exception {
        String xml = new String(KmyRobustnessTest.fixture("edited.xml"), StandardCharsets.UTF_8)
                .replace("<SCHEDULES/>", "<SCHEDULES count=\"1\">\n"
                        + "    <SCHEDULED_TX id=\"SCH000001\" name=\"Miete\" type=\"1\" occurence=\"32\" "
                        + "occurenceMultiplier=\"1\" paymentType=\"1\" startDate=\"2025-01-01\" endDate=\"\" "
                        + "fixed=\"1\" lastDayInMonth=\"0\" autoEnter=\"0\" lastPayment=\"2026-01-01\" "
                        + "weekendOption=\"2\">\n      <PAYMENTS/>\n"
                        + "      <TRANSACTION id=\"\" postdate=\"2026-02-01\" memo=\"\" entrydate=\"\" "
                        + "commodity=\"EUR\">\n        <SPLITS>\n"
                        + "          <SPLIT id=\"S0001\" payee=\"\" reconciledate=\"\" action=\"\" "
                        + "reconcileflag=\"0\" value=\"-1000/1\" shares=\"-1000/1\" price=\"1/1\" memo=\"\" "
                        + "account=\"A000002\" number=\"\" bankid=\"\"/>\n        </SPLITS>\n"
                        + "      </TRANSACTION>\n    </SCHEDULED_TX>\n  </SCHEDULES>");
        byte[] roh = KmyTestDb.ausXml(ctx, xml);
        KmyDocument d = new KmyDocument(roh, ctx);
        ScheduledAdvance a = new ScheduledAdvance();
        a.id = 1;
        a.kmyId = "SCH000001";
        a.fromDueMs = KmyDocument.parseKmyDate("2026-02-01");
        a.nextDueMs = KmyDocument.parseKmyDate("2026-03-01");
        a.lastPaymentMs = KmyDocument.parseKmyDate("2026-02-01");
        KmyExporter.ScheduleResult r = KmyExporter.applyScheduleAdvances(d.xml(), Collections.singletonList(a));
        assertEquals(1, r.writtenIds.size());
        byte[] neu = schreibe(roh, d, r.xml, r.aenderungen);

        assertEquals("2026-02-01|2026-03-01", frage(neu,
                "SELECT lastPayment, nextPaymentDue FROM kmmSchedules WHERE id = 'SCH000001'"));
        assertEquals("2026-03-01", frage(neu,
                "SELECT postDate FROM kmmTransactions WHERE id = 'SCH000001' AND txType = 'S'"));
        assertEquals("2026-03-01", frage(neu,
                "SELECT postDate FROM kmmSplits WHERE transactionId = 'SCH000001'"));
        // Eine Planung ist keine Buchung: am Konto und an den Zählern ändert sich nichts.
        assertEquals(frage(roh, "SELECT * FROM kmmAccounts ORDER BY id"),
                frage(neu, "SELECT * FROM kmmAccounts ORDER BY id"));
    }

    /**
     * Die Gegenprobe: Verlangt die neue Fassung etwas, das nicht angesagt ist, stimmt die Datenbank
     * danach nicht mit ihr überein – dann gibt es keine neue Datenbank.
     */
    @Test
    public void gegenprobeSchlaegtAn() throws Exception {
        byte[] roh = KmyTestDb.ausFixture(ctx, "edited.xml");
        KmyDocument d = new KmyDocument(roh, ctx);
        KmyExporter.Result r = new KmyExporter(d, ctx).build(
                Collections.singletonList(ausgabe(5, 1240, "")), Collections.emptyList(), new HashMap<>());
        // Eine fremde Buchung im XML verändert, ohne dass sie angesagt wäre.
        String falsch = r.xml.replaceFirst("value=\"-10/1\"", "value=\"-11/1\"");
        if (falsch.equals(r.xml)) {
            falsch = r.xml.replaceFirst("value=\"-1000/100\"", "value=\"-1100/100\"");
        }
        assertFalse(falsch.equals(r.xml));
        try {
            KmySqliteWriter.schreibe(ctx, roh, falsch, r.aenderungen);
            fail("die Gegenprobe hätte anschlagen müssen");
        } catch (KmyExportCheck.Failed e) {
            assertTrue(e.getMessage(), e.getMessage().contains("weicht nach dem Schreiben"));
        }
        // Eine angesagte Transaktion, die in der neuen Fassung gar nicht steht.
        KmyAenderungen erfunden = new KmyAenderungen();
        erfunden.neu("T000000000000000099");
        try {
            KmySqliteWriter.schreibe(ctx, roh, d.xml(), erfunden);
            fail("hätte scheitern müssen");
        } catch (KmyExportCheck.Failed e) {
            assertTrue(e.getMessage(), e.getMessage().contains("T000000000000000099"));
        }
    }

    @Test
    public void maskierungAufheben() {
        assertEquals("a&b <c> \"d\" 'e'\nf\tg", KmySqliteWriter.klar(
                "a&amp;b &lt;c&gt; &quot;d&quot; &apos;e&apos;&#xa;f&#x9;g"));
        assertEquals("€ und A", KmySqliteWriter.klar("&#8364; und &#65;"));
        assertEquals("ohne", KmySqliteWriter.klar("ohne"));
        assertEquals("", KmySqliteWriter.klar(null));
        // Hin und zurück mit der Maskierung des Exports.
        String wild = "Müller & Söhne <\"'>\nzweite\tZeile\r";
        assertEquals(wild, KmySqliteWriter.klar(KmyExporter.esc(wild)));
    }
}
