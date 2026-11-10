package de.spahr.ausgaben.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.HashMap;

import de.spahr.ausgaben.db.Booking;

/**
 * Datenbanken aller geprüften Schema-Versionen: 12 aus KMyMoney 5.1, 13 bis 16 aus KMyMoney 5.2. Jede
 * lässt sich lesen und beschreiben, und die App hebt die Version dabei nicht an – ein KMyMoney 5.1
 * öffnet seine Datenbank auch nach einem Export weiter.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KmySqliteSchemaTest {

    private final Context ctx = ApplicationProvider.getApplicationContext();

    private static Booking ausgabe(String payee) {
        Booking b = new Booking();
        b.id = 5;
        b.account = "Bargeld";
        b.category = "Essen";
        b.payee = payee;
        b.amountCents = 1240;
        b.createdAt = KmyDocument.parseKmyDate("2026-02-01");
        return b;
    }

    @Test
    public void jedeGepruefteVersionLaesstSichBeschreiben() throws Exception {
        for (String version : KmySqlite.SCHEMATA) {
            byte[] roh = KmyTestDb.ausFixture(ctx, "edited.xml", version);
            KmyDocument d = new KmyDocument(roh, ctx);
            assertEquals(version, d.datenbank().version);
            assertNull(version, KmyExportCoordinator.datenbankHindernis(ctx, d, "test.sqlite"));

            KmyExporter.Result r = new KmyExporter(d, ctx).build(
                    Collections.singletonList(ausgabe("Kiosk")), Collections.emptyList(), new HashMap<>());
            KmyExportCheck.pruefen(d.xml(), r.xml, KmyDocument.gzip(r.xml), r.aenderungen);
            byte[] neu = KmySqliteWriter.schreibe(ctx, roh, r.xml, r.aenderungen);
            assertEquals(version, r.xml, KmyDocument.alsXml(ctx, neu));
            assertEquals(version, version, KmyTestDb.frage(ctx, neu, "SELECT version FROM kmmFileInfo"));
        }
    }

    /** Ab 13 legt der Export einen neuen Empfänger mit leerem Muster an, so wie KMyMoney 5.2: '' statt NULL. */
    @Test
    public void neuerEmpfaengerAbSchema13() throws Exception {
        byte[] roh = KmyTestDb.ausFixture(ctx, "edited.xml", "16");
        KmyDocument d = new KmyDocument(roh, ctx);
        KmyExporter.Result r = new KmyExporter(d, ctx).build(
                Collections.singletonList(ausgabe("Kiosk")), Collections.emptyList(), new HashMap<>());
        byte[] neu = KmySqliteWriter.schreibe(ctx, roh, r.xml, r.aenderungen);
        assertEquals("Kiosk|text||text|", KmyTestDb.frage(ctx, neu,
                "SELECT name, typeof(idPattern), idPattern, typeof(urlTemplate), urlTemplate "
                        + "FROM kmmPayees WHERE name = 'Kiosk'"));
    }

    /**
     * Ein gesetztes Muster erscheint im Abbild so, wie KMyMoney 5.2 es in die .kmy schreibt: beide
     * Attribute, sobald eins gesetzt ist – und das Schreiben lässt es unangetastet.
     */
    @Test
    public void musterUndVorlageDesEmpfaengers() throws Exception {
        byte[] roh = KmyTestDb.aendere(ctx, KmyTestDb.ausFixture(ctx, "edited.xml", "13"),
                "UPDATE kmmPayees SET idPattern = 'RE-\\d+' WHERE id = 'P000001'");
        KmyDocument d = new KmyDocument(roh, ctx);
        assertTrue(d.xml(), d.xml().contains("idpattern=\"RE-\\d+\" urltemplate=\"\""));
        assertFalse(d.xml().contains("idpattern=\"\""));

        KmyExporter.Result r = new KmyExporter(d, ctx).build(
                Collections.singletonList(ausgabe("Kiosk")), Collections.emptyList(), new HashMap<>());
        byte[] neu = KmySqliteWriter.schreibe(ctx, roh, r.xml, r.aenderungen);
        assertEquals(r.xml, KmyDocument.alsXml(ctx, neu));
        assertEquals("RE-\\d+", KmyTestDb.frage(ctx, neu,
                "SELECT idPattern FROM kmmPayees WHERE id = 'P000001'"));
    }

    /**
     * KMyMoney 5.2 legt ein Budget in <b>einer</b> Zeile in {@code kmmBudgetConfig} ab, 5.1 eingerückt,
     * ein Element je Zeile. Beide ergeben dasselbe Budget wie die .kmy.
     */
    @Test
    public void budgetInEinerZeileWieKMyMoney52() throws Exception {
        String fixture = "corpus/test-budget.xml";
        KmyDocument ausKmy = new KmyDocument(KmyRobustnessTest.fixture(fixture), ctx);
        String budget = "<BUDGETS><BUDGET id=\"B000002\" name=\"constant-yearly\" start=\"2024-01-01\" "
                + "version=\"2\"><ACCOUNT id=\"A000001\" budgetlevel=\"yearly\" budgetsubaccounts=\"0\">"
                + "<PERIOD amount=\"120/1\" start=\"2024-01-01\"/></ACCOUNT></BUDGET></BUDGETS>";
        byte[] roh = KmyTestDb.aendere(ctx, KmyTestDb.ausFixture(ctx, fixture, "16"),
                "INSERT INTO kmmBudgetConfig (id, name, start, XML) VALUES "
                        + "('B000002', 'constant-yearly', '2024-01-01', '" + budget + "')");
        KmyDocument ausDb = new KmyDocument(roh, ctx);
        assertTrue(ausDb.budgetYears().contains(2024));
        assertFalse(ausKmy.budgetEntries(2024).isEmpty());
        assertEquals(beschreibe(ausKmy.budgetEntries(2024)), beschreibe(ausDb.budgetEntries(2024)));

        assertEquals("<BUDGETS>\n <BUDGET id=\"B\">\n  <ACCOUNT id=\"A\">\n   <PERIOD amount=\"1/1\"/>\n"
                        + "  </ACCOUNT>\n </BUDGET>\n</BUDGETS>\n",
                KmySqlite.budgetZeilen("<BUDGETS><BUDGET id=\"B\"><ACCOUNT id=\"A\"><PERIOD amount=\"1/1\"/>"
                        + "</ACCOUNT></BUDGET></BUDGETS>"));
        String eingerueckt = "<BUDGETS>\n <BUDGET id=\"B\"/>\n</BUDGETS>\n";
        assertEquals(eingerueckt, KmySqlite.budgetZeilen(eingerueckt));
    }

    private static String beschreibe(java.util.List<KmyDocument.BudgetEntry> l) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (KmyDocument.BudgetEntry e : l) {
            out.add(e.category + '|' + e.isIncome + '|' + e.yearlyCents);
        }
        java.util.Collections.sort(out);
        return out.toString();
    }

    @Test
    public void unbekannteVersionWirdNurGelesen() throws Exception {
        for (String version : new String[]{"11", "17"}) {
            byte[] roh = KmyTestDb.ausFixture(ctx, "edited.xml", version);
            KmyDocument d = new KmyDocument(roh, ctx);
            assertFalse(version, d.accountNames().isEmpty());
            String grund = KmyExportCoordinator.datenbankHindernis(ctx, d, "test.sqlite");
            assertTrue(grund, grund.contains(version) && grund.contains("12–16"));
        }
    }
}
