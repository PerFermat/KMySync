package de.spahr.ausgaben.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;

import de.spahr.ausgaben.db.Booking;

/**
 * Die KMyMoney-Datenbank im Export-Lauf: wann nicht geschrieben wird, was der Zeilenvergleich danach
 * zeigt, und was mit einer Datei geschieht, die weder XML noch lesbare Datenbank ist.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KmySqliteExportTest {

    private final Context ctx = ApplicationProvider.getApplicationContext();

    private String hindernis(byte[] roh) throws Exception {
        return KmyExportCoordinator.datenbankHindernis(ctx, new KmyDocument(roh, ctx), "test.sqlite");
    }

    @Test
    public void geschlosseneDatenbankDerRichtigenVersionIstFrei() throws Exception {
        assertNull(hindernis(KmyTestDb.ausFixture(ctx, "edited.xml")));
        // Eine .kmy-Datei hat mit alledem nichts zu tun.
        assertNull(KmyExportCoordinator.datenbankHindernis(ctx,
                new KmyDocument(KmyRobustnessTest.fixture("edited.xml"), ctx), "test.kmy"));
    }

    /** KMyMoney trägt sich beim Öffnen ein – das Gegenstück zur Sperrdatei neben einer .kmy. */
    @Test
    public void inKMyMoneyGeoeffnet_wirdNichtGeschrieben() throws Exception {
        byte[] offen = KmyTestDb.aendere(ctx, KmyTestDb.ausFixture(ctx, "edited.xml"),
                "UPDATE kmmFileInfo SET logonUser = 'michael@rechner'");
        String grund = hindernis(offen);
        assertTrue(grund, grund.contains("test.sqlite") && grund.contains("michael@rechner"));
        // Gelesen wird sie trotzdem.
        assertEquals(2, new KmyDocument(offen, ctx).accountNames().size());
    }

    @Test
    public void mittenImSchreibvorgang_wirdNichtGeschrieben() throws Exception {
        byte[] halb = KmyTestDb.aendere(ctx, KmyTestDb.ausFixture(ctx, "edited.xml"),
                "UPDATE kmmFileInfo SET updateInProgress = 'Y'");
        assertTrue(hindernis(halb).contains("test.sqlite"));
    }

    @Test
    public void fremdeSchemaVersion_wirdNichtGeschrieben() throws Exception {
        byte[] alt = KmyTestDb.aendere(ctx, KmyTestDb.ausFixture(ctx, "edited.xml"),
                "UPDATE kmmFileInfo SET version = '11'");
        String grund = hindernis(alt);
        assertTrue(grund, grund.contains("11") && grund.contains(KmySqlite.SCHEMA));
    }

    @Test
    public void journalNebenDerDatenbank() {
        assertTrue(KmyLock.hasJournal(Arrays.asList("a.sqlite", "a.sqlite-journal"), "a.sqlite"));
        assertTrue(KmyLock.hasJournal(Arrays.asList("a.sqlite", "a.sqlite-wal", "a.sqlite-shm"), "a.sqlite"));
        assertFalse(KmyLock.hasJournal(Arrays.asList("a.sqlite", "b.sqlite-journal"), "a.sqlite"));
        assertFalse(KmyLock.hasJournal(null, "a.sqlite"));
    }

    /** Weder XML noch SQLite – etwa eine verschlüsselte Datenbank: klare Absage statt Rätselraten. */
    @Test
    public void verschluesselteDatenbankWirdAbgelehnt() throws Exception {
        byte[] wirr = new byte[4096];
        new java.util.Random(3).nextBytes(wirr);
        try {
            new KmyDocument(wirr, ctx);
            fail("hätte abgelehnt werden müssen");
        } catch (IOException e) {
            assertEquals(ctx.getString(de.spahr.ausgaben.R.string.err_kmy_not_xml), e.getMessage());
        }
    }

    /**
     * Der Weg des Export-Laufs: prüfen, in die Datenbank schreiben, „zurücklesen" und vergleichen. Der
     * Zeilenvergleich zeigt für die Datenbank dasselbe wie für eine .kmy – die neue Buchung als
     * zusammenhängenden grünen Block.
     */
    @Test
    public void zeilenvergleichNachDemSchreiben() throws Exception {
        byte[] roh = KmyTestDb.ausFixture(ctx, "edited.xml");
        KmyDocument d = new KmyDocument(roh, ctx);
        Booking b = new Booking();
        b.id = 5;
        b.account = "Bargeld";
        b.category = "Essen";
        b.amountCents = 1240;
        b.createdAt = KmyDocument.parseKmyDate("2026-02-01");
        KmyExporter.Result r = new KmyExporter(d, ctx).build(Collections.singletonList(b),
                Collections.emptyList(), new HashMap<>());
        KmyExportCheck.pruefen(d.xml(), r.xml, KmyDocument.gzip(r.xml), r.aenderungen);
        byte[] neu = KmySqliteWriter.schreibe(ctx, roh, r.xml, r.aenderungen);

        ExportDiff diff = ExportDiff.von(d.xml(), KmyDocument.alsXml(ctx, neu));
        // Sechs Zeilen Transaktion, dazu Zähler und Änderungsdatum.
        assertEquals(8, diff.hinzu);
        assertEquals(2, diff.entfernt);
        StringBuilder gruen = new StringBuilder();
        for (ExportDiff.Zeile z : diff.zeilen) {
            if (z.art == ExportDiff.HINZU && !z.text.contains(" count=") && !z.text.contains("LAST_MOD")) {
                String t = z.text.trim();
                gruen.append(t.contains(" ") ? t.substring(0, t.indexOf(' ')) : t).append(' ');
            }
        }
        assertEquals("<TRANSACTION <SPLITS> <SPLIT <SPLIT </SPLITS> </TRANSACTION>", gruen.toString().trim());
    }
}
