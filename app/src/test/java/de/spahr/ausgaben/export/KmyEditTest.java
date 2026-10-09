package de.spahr.ausgaben.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.BookingSplit;

/**
 * Buchungen mit dem Status „bearbeitet": ihre Transaktion steht schon in der .kmy-Datei und wird dort
 * <b>geändert</b> – gleiche id, gleiche Stelle, unveränderter Zähler. Grundlage ist
 * {@code src/test/resources/kmy/edited.xml}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KmyEditTest {

    private final Context ctx = ApplicationProvider.getApplicationContext();

    private KmyDocument doc() throws IOException {
        return new KmyDocument(KmyRobustnessTest.fixture("edited.xml"), ctx);
    }

    /** Eine exportierte, danach geänderte Ausgabe: {@code orig*} ist die Fassung, die in der Datei steht. */
    private static Booking edited(long id, String account, String category, long cents,
                                  String date, String origAccount, long origSignedCents,
                                  String origDate) {
        Booking b = new Booking();
        b.id = id;
        b.account = account;
        b.category = category;
        b.amountCents = cents;
        b.isIncome = false;
        b.createdAt = KmyDocument.parseKmyDate(date);
        b.edited = true;
        b.origAccount = origAccount;
        b.origSignedCents = origSignedCents;
        b.origCreatedAt = KmyDocument.parseKmyDate(origDate);
        return b;
    }

    private static int countOf(String xml, String needle) {
        int n = 0;
        int i = xml.indexOf(needle);
        while (i >= 0) {
            n++;
            i = xml.indexOf(needle, i + needle.length());
        }
        return n;
    }

    /** Der Block zu dieser Transaktions-id. */
    private static String blockOf(String xml, String txId) {
        int start = xml.indexOf("<TRANSACTION id=\"" + txId + "\"");
        if (start < 0) {
            start = xml.indexOf("id=\"" + txId + "\"");
            assertTrue("Transaktion " + txId + " fehlt", start >= 0);
            start = xml.lastIndexOf("<TRANSACTION", start);
        }
        int end = xml.indexOf("</TRANSACTION>", start);
        assertTrue(end > start);
        return xml.substring(start, end);
    }

    private KmyExporter.Result apply(KmyDocument d, List<Booking> edited,
                                     Map<Long, List<BookingSplit>> splits) {
        return new KmyExporter(d, ctx).build(new ArrayList<>(), edited, splits);
    }

    @Test
    public void geaenderterBetragBehaeltIdUndStelle() throws Exception {
        KmyDocument d = doc();
        KmyExporter.Result r = apply(d, Collections.singletonList(
                edited(1, "Bargeld", "Essen", 400, "2026-01-05", "Bargeld", -250, "2026-01-05")),
                new HashMap<>());

        assertEquals(Collections.emptyList(), r.skipped);
        assertEquals(Collections.emptyList(), r.notFound);
        assertEquals(1, r.updated);
        assertEquals(Collections.singletonList(1L), r.writtenIds);
        // Keine neue Transaktion, kein neuer Zähler – dieselbe Transaktion, nur mit neuem Betrag.
        assertTrue(r.xml.contains("<TRANSACTIONS count=\"4\">"));
        assertEquals(4, countOf(r.xml, "<TRANSACTION "));
        String block = blockOf(r.xml, "T000000000000000001");
        assertTrue(block.contains("value=\"-400/100\""));
        assertFalse(block.contains("value=\"-250/100\""));
        assertTrue(block.contains("postdate=\"2026-01-05\""));
        // Die anderen Transaktionen bleiben unberührt: jeder ihrer Blöcke steht Zeichen für Zeichen da.
        for (String id : new String[]{"T000000000000000002", "T000000000000000003",
                "T000000000000000004"}) {
            assertEquals(id, blockOf(d.xml(), id), blockOf(r.xml, id));
        }
    }

    /** Bearbeitung verschiebt Konto und Datum: gefunden wird über die Signatur der exportierten Fassung. */
    @Test
    public void andereSignaturAlsHeuteWirdTrotzdemGefunden() throws Exception {
        KmyDocument d = doc();
        KmyExporter.Result r = apply(d, Collections.singletonList(
                edited(1, "Girokonto", "Auto", 999, "2026-03-09", "Bargeld", -250, "2026-01-05")),
                new HashMap<>());

        assertEquals(1, r.updated);
        String block = blockOf(r.xml, "T000000000000000001");
        assertTrue(block.contains("postdate=\"2026-03-09\""));
        assertTrue(block.contains("account=\"A000002\"")); // Girokonto
        assertTrue(block.contains("account=\"A000004\"")); // Auto
        assertTrue(block.contains("value=\"-999/100\""));
    }

    /** Aus der Einzelkategorie wird eine Splitbuchung: der neue Block trägt drei Splits. */
    @Test
    public void splitbuchungErsetztDieAltenSplits() throws Exception {
        KmyDocument d = doc();
        Booking b = edited(1, "Bargeld", "", 300, "2026-01-05", "Bargeld", -250, "2026-01-05");
        BookingSplit p1 = new BookingSplit();
        p1.bookingId = 1;
        p1.category = "Essen";
        p1.amountCents = 100;
        BookingSplit p2 = new BookingSplit();
        p2.bookingId = 1;
        p2.category = "Auto";
        p2.amountCents = 200;
        Map<Long, List<BookingSplit>> splits = new HashMap<>();
        splits.put(1L, Arrays.asList(p1, p2));

        KmyExporter.Result r = apply(d, Collections.singletonList(b), splits);

        assertEquals(Collections.emptyList(), r.skipped);
        assertEquals(1, r.updated);
        String block = blockOf(r.xml, "T000000000000000001");
        assertEquals(3, countOf(block, "<SPLIT "));
        assertTrue(block.contains("value=\"-300/100\""));
        assertTrue(block.contains("value=\"100/100\""));
        assertTrue(block.contains("value=\"200/100\""));
    }

    /** Umbuchung: zwei Buchungszeilen, aber nur eine Transaktion – sie wird genau einmal ersetzt. */
    @Test
    public void umbuchungWirdNurEinmalErsetzt() throws Exception {
        KmyDocument d = doc();
        Booking out = new Booking();
        out.id = 10;
        out.account = "Bargeld";
        out.transferAccount = "Girokonto";
        out.transferGroup = "g1";
        out.isTransfer = true;
        out.isIncome = false;
        out.amountCents = 1500;
        out.createdAt = KmyDocument.parseKmyDate("2026-01-06");
        out.edited = true;
        out.origAccount = "Bargeld";
        out.origSignedCents = -1000;
        out.origCreatedAt = KmyDocument.parseKmyDate("2026-01-06");
        Booking in = new Booking();
        in.id = 11;
        in.account = "Girokonto";
        in.transferAccount = "Bargeld";
        in.transferGroup = "g1";
        in.isTransfer = true;
        in.isIncome = true;
        in.amountCents = 1500;
        in.createdAt = out.createdAt;
        in.edited = true;
        in.origAccount = out.origAccount;
        in.origSignedCents = out.origSignedCents;
        in.origCreatedAt = out.origCreatedAt;

        KmyExporter.Result r = apply(d, Arrays.asList(out, in), new HashMap<>());

        assertEquals(Collections.emptyList(), r.skipped);
        assertEquals(1, r.updated);
        // Beide Zeilen gelten als geschrieben (beide werden auf „exportiert" gestellt).
        assertEquals(Arrays.asList(10L, 11L), r.writtenIds);
        assertTrue(r.xml.contains("<TRANSACTIONS count=\"4\">"));
        assertEquals(4, countOf(r.xml, "<TRANSACTION "));
        String block = blockOf(r.xml, "T000000000000000002");
        assertTrue(block.contains("value=\"-1500/100\""));
        assertTrue(block.contains("value=\"1500/100\""));
    }

    /** Zwei gleichartige Buchungen (gleicher Tag, gleicher Betrag) treffen zwei verschiedene Blöcke. */
    @Test
    public void zwillingeTreffenNichtDenselbenBlock() throws Exception {
        KmyDocument d = doc();
        KmyExporter.Result r = apply(d, Arrays.asList(
                edited(1, "Bargeld", "Essen", 501, "2026-01-07", "Bargeld", -500, "2026-01-07"),
                edited(2, "Bargeld", "Essen", 502, "2026-01-07", "Bargeld", -500, "2026-01-07")),
                new HashMap<>());

        assertEquals(2, r.updated);
        assertEquals(Collections.emptyList(), r.notFound);
        assertTrue(r.xml.contains("value=\"-501/100\""));
        assertTrue(r.xml.contains("value=\"-502/100\""));
        assertFalse(r.xml.contains("value=\"-500/100\""));
        assertTrue(r.xml.contains("<TRANSACTIONS count=\"4\">"));
    }

    /**
     * Die Transaktion ist nicht (mehr) in der Datei – etwa weil sie am Rechner gelöscht wurde. Dann wird
     * nichts eingefügt (keine Dublette) und die Buchung bleibt „bearbeitet".
     */
    @Test
    public void ohneTrefferBleibtDieDateiUnberuehrt() throws Exception {
        KmyDocument d = doc();
        KmyExporter.Result r = apply(d, Collections.singletonList(
                edited(1, "Bargeld", "Essen", 400, "2026-01-05", "Bargeld", -777, "2026-01-05")),
                new HashMap<>());

        assertEquals(Collections.singletonList(1L), r.notFound);
        assertEquals(Collections.emptyList(), r.writtenIds);
        assertEquals(0, r.updated);
        assertTrue(r.xml.contains("<TRANSACTIONS count=\"4\">"));
        assertEquals(4, countOf(r.xml, "<TRANSACTION "));
        assertFalse(r.xml.contains("value=\"-400/100\""));
    }

    /** Neue und bearbeitete Buchungen im selben Lauf: eine kommt hinzu, eine wird geändert. */
    @Test
    public void neueUndBearbeiteteImSelbenLauf() throws Exception {
        KmyDocument d = doc();
        Booking neu = new Booking();
        neu.id = 5;
        neu.account = "Bargeld";
        neu.category = "Essen";
        neu.amountCents = 111;
        neu.isIncome = false;
        neu.createdAt = KmyDocument.parseKmyDate("2026-02-01");

        KmyExporter.Result r = new KmyExporter(d, ctx).build(Collections.singletonList(neu),
                Collections.singletonList(
                        edited(1, "Bargeld", "Essen", 400, "2026-01-05", "Bargeld", -250, "2026-01-05")),
                new HashMap<>());

        assertEquals(Collections.emptyList(), r.skipped);
        assertEquals(1, r.updated);
        assertEquals(2, r.writtenIds.size());
        // Genau eine Transaktion mehr als vorher.
        assertTrue(r.xml.contains("<TRANSACTIONS count=\"5\">"));
        assertEquals(5, countOf(r.xml, "<TRANSACTION "));
        assertTrue(r.xml.contains("value=\"-111/100\""));
        assertTrue(blockOf(r.xml, "T000000000000000001").contains("value=\"-400/100\""));
    }

    /** Das Konto der exportierten Fassung gibt es in der Datei nicht mehr → kein Treffer, nichts kaputt. */
    @Test
    public void unbekanntesAltesKontoBleibtOhneTreffer() throws Exception {
        KmyDocument d = doc();
        KmyExporter.Result r = apply(d, Collections.singletonList(
                edited(1, "Bargeld", "Essen", 400, "2026-01-05", "Sparstrumpf", -250, "2026-01-05")),
                new HashMap<>());

        assertEquals(Collections.singletonList(1L), r.notFound);
        assertEquals(0, r.updated);
        assertNotNull(r.xml);
        assertEquals(4, countOf(r.xml, "<TRANSACTION "));
    }
}
