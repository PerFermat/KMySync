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

import java.util.Collections;
import java.util.Map;

/**
 * Konten schließen und wieder öffnen über den Export: in der .kmy und in der KMyMoney-Datenbank, mit
 * Selbstprüfung – und eine Änderung am Konto, die niemand angesagt hat, hält die Prüfung auf.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KmyKontoStatusExportTest {

    private final Context ctx = ApplicationProvider.getApplicationContext();

    private static Map<String, Boolean> status(String name, boolean zu) {
        return Collections.singletonMap(name, zu);
    }

    @Test
    public void kmySchliessenUndWiederOeffnen() throws Exception {
        KmyDocument d = new KmyDocument(KmyRobustnessTest.fixture("edited.xml"), ctx);
        assertFalse(d.isClosed("Bargeld"));

        KmyExporter.AccountStatusResult zu = new KmyExporter(d, ctx).applyAccountStatus(d.xml(),
                status("Bargeld", true));
        assertEquals(Collections.singletonList("Bargeld"), zu.writtenNames);
        KmyExportCheck.pruefen(d.xml(), zu.xml, KmyDocument.gzip(zu.xml), zu.aenderungen);
        KmyDocument geschlossen = new KmyDocument(KmyDocument.gzip(zu.xml), ctx);
        assertTrue(geschlossen.isClosed("Bargeld"));
        assertFalse(geschlossen.isClosed("Girokonto"));
        assertTrue(new KmyImporter(geschlossen, ctx).closedAccounts().get("Bargeld"));

        KmyExporter.AccountStatusResult auf = new KmyExporter(geschlossen, ctx).applyAccountStatus(
                geschlossen.xml(), status("Bargeld", false));
        KmyExportCheck.pruefen(geschlossen.xml(), auf.xml, KmyDocument.gzip(auf.xml), auf.aenderungen);
        assertEquals(d.xml(), auf.xml);
    }

    /** Schon so in der Datei: erledigt, aber nichts geschrieben. Unbekannter Name: bleibt vorgemerkt. */
    @Test
    public void schonSoOderUnbekannt() throws Exception {
        KmyDocument d = new KmyDocument(KmyRobustnessTest.fixture("edited.xml"), ctx);
        KmyExporter.AccountStatusResult r = new KmyExporter(d, ctx).applyAccountStatus(d.xml(),
                status("Bargeld", false));
        assertEquals(d.xml(), r.xml);
        assertTrue(r.writtenNames.isEmpty());
        assertEquals(Collections.singletonList("Bargeld"), r.resolvedNames);
        r = new KmyExporter(d, ctx).applyAccountStatus(d.xml(), status("Gibt es nicht", true));
        assertTrue(r.resolvedNames.isEmpty());
    }

    @Test
    public void unangesagteKontoaenderungWirdAbgelehnt() throws Exception {
        KmyDocument d = new KmyDocument(KmyRobustnessTest.fixture("edited.xml"), ctx);
        KmyExporter.AccountStatusResult zu = new KmyExporter(d, ctx).applyAccountStatus(d.xml(),
                status("Bargeld", true));
        try {
            KmyExportCheck.pruefen(d.xml(), zu.xml, KmyDocument.gzip(zu.xml), KmyAenderungen.keine());
            fail("ohne Ansage hätte die Prüfung anschlagen müssen");
        } catch (KmyExportCheck.Failed e) {
            assertTrue(e.getMessage(), e.getMessage().contains("ACCOUNTS"));
        }
        // Angesagt ist „schließen", geschrieben auch der Name: auch das fällt auf.
        String mehr = zu.xml.replace("name=\"Bargeld\"", "name=\"Barkasse\"");
        try {
            KmyExportCheck.pruefen(d.xml(), mehr, KmyDocument.gzip(mehr), zu.aenderungen);
            fail();
        } catch (KmyExportCheck.Failed e) {
            assertTrue(e.getMessage(), e.getMessage().contains("über den Zustand hinaus"));
        }
    }

    /** In der KMyMoney-Datenbank: eine Zeile in kmmKeyValuePairs, und das Abbild ist die geprüfte Fassung. */
    @Test
    public void datenbankSchliessenUndWiederOeffnen() throws Exception {
        for (String version : new String[]{"12", "16"}) {
            byte[] roh = KmyTestDb.ausFixture(ctx, "edited.xml", version);
            KmyDocument d = new KmyDocument(roh, ctx);
            String id = d.accountId("Bargeld");
            int paareVorher = Integer.parseInt(KmyTestDb.frage(ctx, roh, "SELECT COALESCE(kvps, 0) FROM kmmFileInfo"));

            KmyExporter.AccountStatusResult zu = new KmyExporter(d, ctx).applyAccountStatus(d.xml(),
                    status("Bargeld", true));
            KmyExportCheck.pruefen(d.xml(), zu.xml, KmyDocument.gzip(zu.xml), zu.aenderungen);
            byte[] neu = KmySqliteWriter.schreibe(ctx, d.datenbankRoh(), zu.xml, zu.aenderungen);
            assertEquals(version, zu.xml, KmyDocument.alsXml(ctx, neu));
            assertEquals("ACCOUNT|" + id + "|mm-closed|yes", KmyTestDb.frage(ctx, neu,
                    "SELECT kvpType, kvpId, kvpKey, kvpData FROM kmmKeyValuePairs WHERE kvpKey = 'mm-closed'"));
            assertEquals(String.valueOf(paareVorher + 1),
                    KmyTestDb.frage(ctx, neu, "SELECT kvps FROM kmmFileInfo"));
            KmyDocument geschlossen = new KmyDocument(neu, ctx);
            assertTrue(geschlossen.isClosed("Bargeld"));

            KmyExporter.AccountStatusResult auf = new KmyExporter(geschlossen, ctx).applyAccountStatus(
                    geschlossen.xml(), status("Bargeld", false));
            KmyExportCheck.pruefen(geschlossen.xml(), auf.xml, KmyDocument.gzip(auf.xml), auf.aenderungen);
            byte[] wieder = KmySqliteWriter.schreibe(ctx, geschlossen.datenbankRoh(), auf.xml, auf.aenderungen);
            assertEquals(d.xml(), KmyDocument.alsXml(ctx, wieder));
            assertEquals("0", KmyTestDb.frage(ctx, wieder,
                    "SELECT count(*) FROM kmmKeyValuePairs WHERE kvpKey = 'mm-closed'"));
            assertEquals(String.valueOf(paareVorher), KmyTestDb.frage(ctx, wieder, "SELECT kvps FROM kmmFileInfo"));
        }
    }
}
