package de.spahr.ausgaben.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import de.spahr.ausgaben.db.Booking;

/**
 * Der Rohvergleich zweier Fassungen einer KMyMoney-Datenbank: was er an Sätzen findet, wie er sie
 * ordnet, und dass er auch zeigt, was im XML-Abbild keinen Platz hat.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TabellenDiffTest {

    private final Context ctx = ApplicationProvider.getApplicationContext();

    private static List<String[]> saetze(String... zeilen) {
        List<String[]> out = new ArrayList<>();
        for (String z : zeilen) {
            String[] w = z.split("\\|", -1);
            for (int i = 0; i < w.length; i++) {
                w[i] = "∅".equals(w[i]) ? null : w[i];
            }
            out.add(w);
        }
        return out;
    }

    private static String kurz(TabellenDiff.Tabelle t) {
        StringBuilder sb = new StringBuilder();
        for (TabellenDiff.Satz s : t.saetze) {
            sb.append(s.art).append(String.join("|", Arrays.asList(s.werte)
                    .stream().map(w -> w == null ? "∅" : w).toArray(String[]::new))).append(' ');
        }
        return sb.toString().trim();
    }

    private static TabellenDiff.Tabelle tabelle(List<TabellenDiff.Tabelle> alle, String name) {
        for (TabellenDiff.Tabelle t : alle) {
            if (t.name.equals(name)) {
                return t;
            }
        }
        return null;
    }

    @Test
    public void gleicheSaetze_unveraendert_auchInAndererReihenfolge() {
        TabellenDiff.Tabelle t = TabellenDiff.vergleiche("t", new String[]{"id", "x"}, new int[]{0},
                saetze("1|a", "2|b"), saetze("2|b", "1|a"));
        assertFalse(t.geaendert());
        assertTrue(t.saetze.isEmpty());
    }

    @Test
    public void geaenderterSatz_stehtAltUeberNeu() {
        TabellenDiff.Tabelle t = TabellenDiff.vergleiche("t", new String[]{"id", "x"}, new int[]{0},
                saetze("1|a", "2|b", "3|c"), saetze("1|a", "2|B", "4|d"));
        assertEquals(2, t.hinzu);
        assertEquals(2, t.entfernt);
        assertEquals("-2|b +2|B -3|c +4|d", kurz(t));
    }

    @Test
    public void nullIstEtwasAnderesAlsLeer_undDoppelteZaehlenEinzeln() {
        TabellenDiff.Tabelle t = TabellenDiff.vergleiche("t", new String[]{"id", "x"}, new int[0],
                saetze("1|∅", "5|z", "5|z"), saetze("1|", "5|z"));
        assertEquals("-1|∅ +1| -5|z", kurz(t));
    }

    @Test
    public void zahlenOrdnenSichDerGroesseNach() {
        TabellenDiff.Tabelle t = TabellenDiff.vergleiche("t", new String[]{"id"}, new int[]{0},
                saetze(), saetze("10", "9", "100"));
        assertEquals("+9 +10 +100", kurz(t));
    }

    @Test
    public void zuVieleSaetze_gekuerztAberGezaehlt() {
        List<String[]> viele = new ArrayList<>();
        for (int i = 0; i < TabellenDiff.MAX_SAETZE + 50; i++) {
            viele.add(new String[]{String.valueOf(i)});
        }
        TabellenDiff.Tabelle t = TabellenDiff.vergleiche("t", new String[]{"id"}, new int[]{0},
                saetze(), viele);
        assertTrue(t.gekuerzt);
        assertEquals(TabellenDiff.MAX_SAETZE + 50, t.hinzu);
        assertEquals(TabellenDiff.MAX_SAETZE, t.saetze.size());
    }

    /** Eine neue Buchung: Transaktion und Splits, dazu die Zähler und Salden, die das XML nicht zeigt. */
    @Test
    public void neueBuchungInDerDatenbank() throws Exception {
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
        byte[] neu = KmySqliteWriter.schreibe(ctx, roh, r.xml, r.aenderungen);

        List<TabellenDiff.Tabelle> alle = TabellenDiff.von(ctx, roh, neu);

        TabellenDiff.Tabelle tx = tabelle(alle, "kmmTransactions");
        assertEquals(1, tx.hinzu);
        assertEquals(0, tx.entfernt);
        assertEquals("id", tx.spalten[0]);
        TabellenDiff.Tabelle splits = tabelle(alle, "kmmSplits");
        assertEquals(2, splits.hinzu);
        assertEquals(0, splits.entfernt);
        // Der Zähler der Datei und die Salden der beiden Konten: je ein Satz alt, einer neu.
        TabellenDiff.Tabelle info = tabelle(alle, "kmmFileInfo");
        assertEquals(1, info.hinzu);
        assertEquals(1, info.entfernt);
        TabellenDiff.Tabelle konten = tabelle(alle, "kmmAccounts");
        assertEquals(2, konten.hinzu);
        assertEquals(2, konten.entfernt);
        assertEquals(TabellenDiff.ENTFERNT, konten.saetze.get(0).art);
        assertEquals(TabellenDiff.HINZU, konten.saetze.get(1).art);
        assertEquals(konten.saetze.get(0).werte[0], konten.saetze.get(1).werte[0]);
        // Was nicht angefasst wurde, steht als unverändert da.
        TabellenDiff.Tabelle empfaenger = tabelle(alle, "kmmPayees");
        assertNotNull(empfaenger);
        assertFalse(empfaenger.geaendert());
        assertNull(tabelle(alle, "sqlite_sequence"));

        // Durch die Ablage und zurück.
        ExportDiff diff = ExportDiff.ausTabellen(alle);
        diff.datei = "test.sqlite";
        assertEquals(6, diff.hinzu);
        assertEquals(3, diff.entfernt);
        ExportDiff kopf = ExportDiff.ausText(diff.alsText(), true);
        assertTrue(kopf.datenbank);
        assertEquals(6, kopf.hinzu);
        ExportDiff zurueck = ExportDiff.ausText(diff.alsText(), false);
        assertEquals(alle.size(), zurueck.tabellen.size());
        TabellenDiff.Tabelle k2 = zurueck.tabelle("kmmAccounts");
        assertEquals(kurz(konten), kurz(k2));
        assertEquals(Arrays.asList(konten.spalten), Arrays.asList(k2.spalten));
        assertFalse(zurueck.tabelle("kmmPayees").geaendert());
    }

    @Test
    public void werteMitTabUndZeilenendeUeberstehenDieAblage() {
        TabellenDiff.Tabelle t = TabellenDiff.vergleiche("t", new String[]{"id", "memo"}, new int[]{0},
                saetze(), Arrays.asList(new String[]{"1", "a\tb\nc\\N"}, new String[]{"2", null},
                        new String[]{"3", "\\N"}));
        ExportDiff zurueck = ExportDiff.ausText(
                ExportDiff.ausTabellen(Collections.singletonList(t)).alsText(), false);
        List<TabellenDiff.Satz> s = zurueck.tabelle("t").saetze;
        assertEquals("a\tb\nc\\N", s.get(0).werte[1]);
        assertNull(s.get(1).werte[1]);
        assertEquals("\\N", s.get(2).werte[1]);
    }
}
