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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.KmyPendingDelete;

/**
 * Der Export schreibt, wie KMyMoney schreibt: ein Element je Zeile, eingerückt wie der Bestand. Am
 * Inhalt ändert das nichts – aber ein Zeilenvergleich der Datei zeigt dann je Split eine Zeile, statt
 * aller neuen Buchungen in einer einzigen.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KmyLineFormatTest {

    private final Context ctx = ApplicationProvider.getApplicationContext();

    private KmyDocument doc(String xml) throws Exception {
        return new KmyDocument(xml.getBytes(StandardCharsets.UTF_8), ctx);
    }

    private String edited() throws Exception {
        return new String(KmyRobustnessTest.fixture("edited.xml"), StandardCharsets.UTF_8);
    }

    private static Booking neu(String payee) {
        Booking b = new Booking();
        b.id = 5;
        b.account = "Bargeld";
        b.category = "Essen";
        b.payee = payee;
        b.amountCents = 111;
        b.createdAt = KmyDocument.parseKmyDate("2026-02-01");
        return b;
    }

    private KmyExporter.Result schreibe(KmyDocument d, Booking b) throws Exception {
        KmyExporter.Result r = new KmyExporter(d, ctx).build(Collections.singletonList(b),
                Collections.emptyList(), new HashMap<>());
        assertEquals(Collections.emptyList(), r.skipped);
        // Was auch immer mit den Zeilen geschieht: die Selbstprüfung muss es weiter gutheißen.
        KmyExportCheck.pruefen(d.xml(), r.xml, KmyDocument.gzip(r.xml), r.aenderungen);
        return r;
    }

    private static List<String> zeilen(String xml) {
        return Arrays.asList(xml.split("\n", -1));
    }

    /** Die Zeilen von {@code neu}, die in {@code alt} nicht stehen – in ihrer Reihenfolge. */
    private static List<String> hinzugekommen(String alt, String neu) {
        List<String> vorher = new ArrayList<>(zeilen(alt));
        List<String> out = new ArrayList<>();
        for (String z : zeilen(neu)) {
            if (!vorher.remove(z)) {
                out.add(z);
            }
        }
        return out;
    }

    private static int einrueckung(String zeile) {
        int i = 0;
        while (i < zeile.length() && zeile.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    @Test
    public void neueBuchungStehtZeileFuerZeileWieDerBestand() throws Exception {
        KmyDocument d = doc(edited());
        KmyExporter.Result r = schreibe(d, neu("Kiosk"));
        List<String> neu = hinzugekommen(d.xml(), r.xml);

        // Erwartet: die drei Zeilen des Empfängers, die sechs der Transaktion und die drei Zeilen,
        // an denen sich nur ein Wert bewegt hat (beide Zähler, Änderungsdatum). Sonst nichts.
        List<String> tags = new ArrayList<>();
        for (String z : neu) {
            String t = z.trim();
            tags.add(einrueckung(z) + ":" + t.substring(0, Math.min(t.length(),
                    t.indexOf(' ') > 0 ? t.indexOf(' ') : t.length())));
        }
        assertEquals(Arrays.asList("4:<LAST_MODIFIED_DATE", "2:<PAYEES", "4:<PAYEE", "6:<ADDRESS",
                "4:</PAYEE>", "2:<TRANSACTIONS", "4:<TRANSACTION", "6:<SPLITS>", "8:<SPLIT", "8:<SPLIT",
                "6:</SPLITS>", "4:</TRANSACTION>"), tags);
        // Die schließenden Tags der Behälter stehen weiter allein auf ihrer Zeile.
        assertTrue(zeilen(r.xml).contains("  </TRANSACTIONS>"));
        assertTrue(zeilen(r.xml).contains("  </PAYEES>"));
        // Und keine Zeile der alten Datei ist verschwunden außer den dreien mit bewegtem Wert.
        assertEquals(3, hinzugekommen(r.xml, d.xml()).size());
    }

    @Test
    public void geaenderteBuchungBehaeltEinrueckungUndStelle() throws Exception {
        KmyDocument d = doc(edited());
        Booking b = neu("");
        b.amountCents = 400;
        b.createdAt = KmyDocument.parseKmyDate("2026-01-06");
        b.edited = true;
        b.origAccount = "Bargeld";
        b.origSignedCents = -1000;
        b.origCreatedAt = b.createdAt;
        KmyExporter.Result r = new KmyExporter(d, ctx).build(Collections.emptyList(),
                Collections.singletonList(b), new HashMap<>());
        assertEquals(1, r.updated);
        KmyExportCheck.pruefen(d.xml(), r.xml, KmyDocument.gzip(r.xml), r.aenderungen);

        List<String> z = zeilen(r.xml);
        assertEquals("gleich viele Zeilen: sechs gingen, sechs kamen", zeilen(d.xml()).size(), z.size());
        int kopf = -1;
        for (int i = 0; i < z.size(); i++) {
            if (z.get(i).contains("id=\"T000000000000000002\"")) {
                kopf = i;
            }
        }
        assertTrue(kopf > 0);
        assertEquals(4, einrueckung(z.get(kopf)));
        assertEquals("      <SPLITS>", z.get(kopf + 1));
        assertEquals(8, einrueckung(z.get(kopf + 2)));
        assertEquals(8, einrueckung(z.get(kopf + 3)));
        assertEquals("      </SPLITS>", z.get(kopf + 4));
        assertEquals("    </TRANSACTION>", z.get(kopf + 5));
        // Davor und dahinter stehen die Nachbarn, wo sie standen.
        assertEquals(zeilen(d.xml()).get(kopf - 1), z.get(kopf - 1));
        assertEquals(zeilen(d.xml()).get(kopf + 6), z.get(kopf + 6));
    }

    /** Löschen nimmt die Zeilen des Blocks ganz weg – es bleibt keine leere Zeile zurück. */
    @Test
    public void loeschenHinterlaesstKeineLeereZeile() throws Exception {
        KmyDocument d = doc(edited());
        KmyPendingDelete del = new KmyPendingDelete("Bargeld", -1000,
                KmyDocument.parseKmyDate("2026-01-06"), "", 1L);
        KmyExporter.DeleteResult r = new KmyExporter(d, ctx)
                .removeTransactions(d.xml(), Collections.singletonList(del));
        assertEquals(1, r.resolvedIds.size());
        List<String> alt = zeilen(d.xml());
        List<String> neu = zeilen(r.xml);
        assertEquals(alt.size() - 6, neu.size());
        for (String zeile : neu) {
            assertFalse("leere Zeile mitten in der Datei", zeile.trim().isEmpty()
                    && neu.indexOf(zeile) < neu.size() - 1);
        }
    }

    /** Schreiben und wieder löschen: bis auf Empfänger, Zähler und Datum die Datei von vorher. */
    @Test
    public void schreibenUndLoeschenStelltDieZeilenWiederHer() throws Exception {
        KmyDocument d = doc(edited());
        KmyExporter.Result r = schreibe(d, neu(""));
        KmyPendingDelete del = new KmyPendingDelete("Bargeld", -111,
                KmyDocument.parseKmyDate("2026-02-01"), "", 1L);
        KmyExporter.DeleteResult dr = new KmyExporter(doc(r.xml), ctx)
                .removeTransactions(r.xml, Collections.singletonList(del));
        assertEquals(1, dr.resolvedIds.size());
        assertEquals(d.xml(), dr.xml.replaceFirst("<LAST_MODIFIED_DATE date=\"[^\"]*\"",
                "<LAST_MODIFIED_DATE date=\"2026-01-01\""));
    }

    @Test
    public void dateiMitWindowsZeilenendenBehaeltSie() throws Exception {
        String crlf = edited().replace("\n", "\r\n");
        KmyDocument d = doc(crlf);
        KmyExporter.Result r = schreibe(d, neu("Kiosk"));
        assertEquals("kein einzelnes \\n ohne \\r davor", r.xml.replace("\r\n", "").indexOf('\n'), -1);
        assertTrue(r.xml.contains("\r\n      <SPLITS>\r\n"));
    }

    /** {@code <PAYEES/>} und {@code <TRANSACTIONS count="0"/>} werden mehrzeilig aufgeklappt. */
    @Test
    public void leereBehaelterWerdenZeilenweiseAufgeklappt() throws Exception {
        KmyDocument d = new KmyDocument(KmyRobustnessTest.fixture("empty-blocks.xml"), ctx);
        Booking b = neu("Bäcker");
        KmyExporter.Result r = schreibe(d, b);
        List<String> z = zeilen(r.xml);
        int auf = -1;
        for (int i = 0; i < z.size(); i++) {
            if (z.get(i).trim().equals("<PAYEES>")) {
                auf = i;
            }
        }
        assertTrue("<PAYEES> steht auf eigener Zeile", auf > 0);
        int ebene = einrueckung(z.get(auf));
        assertTrue(z.get(auf + 1).trim().startsWith("<PAYEE "));
        assertEquals(2 * ebene, einrueckung(z.get(auf + 1)));
        assertEquals("</PAYEES>", z.get(auf + 4).trim());
        assertEquals(ebene, einrueckung(z.get(auf + 4)));
    }

    /** Eine Datei ganz ohne Zeilen bleibt, wie sie ist: das Fragment kommt am Stück hinein. */
    @Test
    public void einzeiligeDateiBleibtEinzeilig() throws Exception {
        String flach = edited().replaceAll(">\\s*\n\\s*<", "><").replace("\n", " ");
        assertEquals(-1, flach.indexOf('\n'));
        KmyDocument d = doc(flach);
        KmyExporter.Result r = schreibe(d, neu("Kiosk"));
        assertEquals(-1, r.xml.indexOf('\n'));
    }

    /**
     * Bis 2.2 hängte die App ihre Buchungen am Stück vor das schließende Tag. Trifft der Export auf so
     * eine Zeile, schreibt er ab dort zeilenweise weiter und stellt das schließende Tag auf eine eigene.
     */
    @Test
    public void alteEinzeiligeAnhaengselWerdenNichtFortgesetzt() throws Exception {
        String alt = edited().replace("  </TRANSACTIONS>",
                "  <TRANSACTION id=\"T000000000000000009\" postdate=\"2026-01-09\" memo=\"\" "
                        + "entrydate=\"2026-01-09\" commodity=\"EUR\"><SPLITS><SPLIT id=\"S0001\" "
                        + "payee=\"\" reconciledate=\"\" action=\"\" reconcileflag=\"0\" "
                        + "value=\"-100/100\" shares=\"-100/100\" price=\"1/1\" memo=\"\" "
                        + "account=\"A000001\" number=\"\" bankid=\"\"/></SPLITS></TRANSACTION>"
                        + "</TRANSACTIONS>").replace("count=\"4\"", "count=\"5\"");
        KmyDocument d = doc(alt);
        KmyExporter.Result r = schreibe(d, neu(""));
        List<String> z = zeilen(r.xml);
        assertTrue(z.contains("  </TRANSACTIONS>"));
        assertTrue(z.contains("      <SPLITS>"));
        // Die alte Zeile selbst bleibt Zeichen für Zeichen stehen, nur ohne das schließende Tag.
        assertTrue(r.xml.contains("bankid=\"\"/></SPLITS></TRANSACTION>\n    <TRANSACTION id="));
    }

    @Test
    public void gliedernLehntTextZwischenDenTagsAb() {
        assertEquals("  <A x=\"a>b\">\n   <B/>\n  </A>\n",
                KmyExporter.gegliedert("<A x=\"a>b\"><B/></A>", "  ", " ", "\n"));
        assertNull(KmyExporter.gegliedert("<A>Text</A>", "", " ", "\n"));
        assertNull(KmyExporter.gegliedert("<A><B></A>", "", " ", "\n"));
    }
}
