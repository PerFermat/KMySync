package de.spahr.ausgaben.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.BookingSplit;

/**
 * Beim Ändern einer bereits exportierten Buchung wird ihre Transaktion neu gebaut. Alles, was
 * <b>KMyMoney</b> daran führt und die App nicht kennt – der Abgleich-Status samt Datum, die Aktion und
 * die Angaben des Bankimports – muß dabei stehenbleiben. Vorher fiel es auf die Vorgabewerte zurück:
 * aus einer abgeglichenen Buchung wurde stillschweigend eine offene.
 *
 * <p>Grundlage ist {@code src/test/resources/kmy/reconciled.xml} – wie {@code edited.xml}, aber mit
 * gesetzten Werten an der ersten Transaktion.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KmyReconcileFlagTest {

    private final Context ctx = ApplicationProvider.getApplicationContext();

    private KmyDocument doc() throws IOException {
        return new KmyDocument(KmyRobustnessTest.fixture("reconciled.xml"), ctx);
    }

    private static Booking edited(String account, String category, long cents) {
        Booking b = new Booking();
        b.id = 1;
        b.account = account;
        b.category = category;
        b.amountCents = cents;
        b.isIncome = false;
        b.createdAt = KmyDocument.parseKmyDate("2026-01-05");
        b.edited = true;
        b.origAccount = "Bargeld";
        b.origSignedCents = -250;
        b.origCreatedAt = KmyDocument.parseKmyDate("2026-01-05");
        return b;
    }

    private static String blockOf(String xml, String txId) {
        int start = xml.indexOf("id=\"" + txId + "\"");
        assertTrue("Transaktion " + txId + " fehlt", start >= 0);
        start = xml.lastIndexOf("<TRANSACTION", start);
        int end = xml.indexOf("</TRANSACTION>", start);
        return xml.substring(start, end);
    }

    /** Der Split zum Konto {@code accountId} aus dem Block. */
    private static String splitOf(String block, String accountId) {
        for (String part : block.split("<SPLIT")) {
            if (part.contains("account=\"" + accountId + "\"")) {
                return part;
            }
        }
        throw new AssertionError("Split für " + accountId + " fehlt in: " + block);
    }

    private KmyExporter.Result apply(KmyDocument d, List<Booking> edited,
                                     Map<Long, List<BookingSplit>> splits) {
        return new KmyExporter(d, ctx).build(new ArrayList<>(), edited, splits);
    }

    @Test
    public void geaenderterBetragBehaeltAbgleichAktionUndBankangaben() throws Exception {
        KmyDocument d = doc();
        KmyExporter.Result r = apply(d, Collections.singletonList(edited("Bargeld", "Essen", 400)),
                new HashMap<>());

        assertEquals(1, r.updated);
        String block = blockOf(r.xml, "T000000000000000001");
        assertTrue("der neue Betrag muß drinstehen", block.contains("value=\"-400/100\""));

        String konto = splitOf(block, "A000001");
        assertTrue("Abgleich-Status bleibt", konto.contains("reconcileflag=\"1\""));
        assertTrue("Abgleich-Datum bleibt", konto.contains("reconciledate=\"2026-02-01\""));
        assertTrue("Aktion bleibt", konto.contains("action=\"Interest\""));
        assertTrue("Bankimport-Nummer bleibt", konto.contains("number=\"42\""));
        assertTrue("Bankimport-Kennung bleibt", konto.contains("bankid=\"B-123\""));

        // Auch die Gegenseite behält ihren eigenen Wert – zugeordnet wird je Konto, nicht pauschal.
        assertTrue(splitOf(block, "A000003").contains("reconcileflag=\"2\""));
    }

    /**
     * Wandert die Buchung auf ein anderes Konto, gibt es dort nichts zu übernehmen: der neue Split
     * startet mit den Vorgabewerten, statt den Abgleich eines fremden Kontos zu erben.
     */
    @Test
    public void neuesKontoErbtKeinenFremdenAbgleich() throws Exception {
        KmyDocument d = doc();
        KmyExporter.Result r = apply(d, Collections.singletonList(edited("Girokonto", "Essen", 400)),
                new HashMap<>());

        assertEquals(1, r.updated);
        String block = blockOf(r.xml, "T000000000000000001");
        String giro = splitOf(block, "A000002");
        assertTrue(giro.contains("reconcileflag=\"0\""));
        assertFalse(giro.contains("action=\"Interest\""));
    }

    /**
     * Aus einer Kategorie werden zwei: der Kontosplit behält seinen Abgleich, die zusätzliche
     * Kategoriezeile bekommt die Vorgabewerte.
     */
    @Test
    public void beiSplitbuchungBehaeltDerKontosplitSeinenAbgleich() throws Exception {
        KmyDocument d = doc();
        Booking b = edited("Bargeld", "", 300);
        BookingSplit p1 = new BookingSplit();
        p1.bookingId = 1;
        p1.category = "Essen";
        p1.amountCents = 100;
        BookingSplit p2 = new BookingSplit();
        p2.bookingId = 1;
        p2.category = "Auto";
        p2.amountCents = 200;
        Map<Long, List<BookingSplit>> splits = new HashMap<>();
        splits.put(1L, java.util.Arrays.asList(p1, p2));

        KmyExporter.Result r = apply(d, Collections.singletonList(b), splits);

        assertEquals(1, r.updated);
        String block = blockOf(r.xml, "T000000000000000001");
        assertTrue(splitOf(block, "A000001").contains("reconcileflag=\"1\""));
        assertTrue(splitOf(block, "A000004").contains("reconcileflag=\"0\"")); // Auto, neu hinzu
    }
}
