package de.spahr.ausgaben.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.BookingSplit;
import de.spahr.ausgaben.db.KmyPendingDelete;

/**
 * Eigenschaften, die eine <b>fremde</b> KMyMoney-Datei mitbringen kann und die die App bisher nicht
 * vertragen hat: leere (selbstschließende) Blöcke, Splits mit Kindelementen, Konten in Fremdwährung
 * und doppelte Kontonamen. Die Dateien dazu liegen in {@code src/test/resources/kmy}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KmyRobustnessTest {

    private final Context ctx = ApplicationProvider.getApplicationContext();

    static byte[] fixture(String name) throws IOException {
        try (java.io.InputStream in = KmyRobustnessTest.class.getResourceAsStream("/kmy/" + name)) {
            assertNotNull("Testdatei " + name + " fehlt", in);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private KmyDocument doc(String fixture) throws IOException {
        return new KmyDocument(fixture(fixture), ctx);
    }

    private static Booking expense(String account, String category, long cents, String payee) {
        Booking b = new Booking();
        b.id = 1;
        b.account = account;
        b.category = category;
        b.payee = payee;
        b.amountCents = cents;
        b.isIncome = false;
        b.createdAt = KmyDocument.parseKmyDate("2026-02-01");
        return b;
    }

    // ---- Leere Blöcke ----

    /**
     * Frische Datei: {@code <PAYEES/>} und {@code <TRANSACTIONS count="0"/>}. Vorher fiel das Einfügen
     * still aus, während das count-Attribut trotzdem hochgezählt wurde.
     */
    @Test
    public void emptyBlocksAreOpenedUp() throws Exception {
        KmyDocument d = doc("empty-blocks.xml");
        KmyExporter.Result r = new KmyExporter(d, ctx)
                .build(Collections.singletonList(expense("Bargeld", "Essen", 250, "Bäcker")));

        assertEquals(Collections.emptyList(), r.skipped);
        assertEquals(1, r.writtenIds.size());
        assertEquals(1, r.newPayees);
        assertTrue(r.xml.contains("<TRANSACTIONS count=\"1\">"));
        // <PAYEES/> hatte kein count-Attribut – dann wird auch keins erfunden, nur aufgeklappt.
        assertTrue(r.xml.contains("<PAYEES>"));
        assertEquals(1, countOf(r.xml, "<PAYEE "));
        assertTrue(r.xml.contains("account=\"A000001\""));
        assertEquals(1, countOf(r.xml, "<TRANSACTION "));

        // Die geschriebene Buchung muss auch wieder einlesbar sein.
        List<Booking> back = new KmyImporter(new KmyDocument(r.xml.getBytes(StandardCharsets.UTF_8), ctx),
                ctx).bookingsForAccount("Bargeld");
        assertEquals(1, back.size());
        assertEquals(250, back.get(0).amountCents);
        assertEquals("Essen", back.get(0).category);
        assertEquals("Bäcker", back.get(0).payee);
    }

    // ---- Splits mit Kindelementen ----

    /** Buchung mit Schlagwort löschen: der SPLIT ist nicht selbstschließend. */
    @Test
    public void taggedTransactionCanBeRemoved() throws Exception {
        KmyDocument d = doc("tagged-split.xml");
        KmyPendingDelete del = new KmyPendingDelete();
        del.id = 7;
        del.account = "Bargeld";
        del.createdAt = KmyDocument.parseKmyDate("2026-01-05");
        del.signedCents = -250;

        KmyExporter.DeleteResult r = new KmyExporter(d, ctx)
                .removeTransactions(d.xml(), Collections.singletonList(del));

        assertEquals(Collections.singletonList(7L), r.resolvedIds);
        assertFalse(r.xml.contains("T000000000000000001"));
        assertTrue(r.xml.contains("<TRANSACTIONS count=\"0\">"));
    }

    /** Das Stichwort am Split wird gelesen und steht danach an der Buchung. */
    @Test
    public void tagsAreReadFromTheSplit() throws Exception {
        List<Booking> back = new KmyImporter(doc("tagged-split.xml"), ctx).bookingsForAccount("Bargeld");
        assertEquals(1, back.size());
        // Es hängt am Kategorie-Split; die App führt die Stichwörter je Buchung, also steht es hier.
        assertEquals("Urlaub", back.get(0).tags);
    }

    /**
     * Die eigentliche Probe: eine Buchung mit Stichwort, in der App geändert und zurückgeschrieben,
     * hat danach <b>wieder</b> ihr {@code <TAG>}. Bis diese Fassung die Stichwörter kannte, baute
     * {@code replaceTransaction} die Splits ohne sie neu – das Stichwort war still verloren.
     */
    @Test
    public void editingKeepsTheTag() throws Exception {
        KmyDocument d = doc("tagged-split.xml");
        Booking b = new KmyImporter(d, ctx).bookingsForAccount("Bargeld").get(0);
        b.id = 3;
        b.edited = true;
        b.origAccount = b.account;
        b.origSignedCents = -250;
        b.origCreatedAt = b.createdAt;
        b.amountCents = 399; // der Nutzer hat den Betrag berichtigt

        KmyExporter.Result r = new KmyExporter(d, ctx)
                .build(new ArrayList<>(), Collections.singletonList(b), new HashMap<>());

        assertEquals(Collections.emptyList(), r.skipped);
        assertEquals(1, r.updated);
        assertTrue(r.xml.contains("-399/100"));
        // Beide Splits tragen es: die App führt die Stichwörter je Buchung.
        assertEquals(2, countOf(r.xml, "<TAG id=\"G000001\"/>"));
        // Und der Kopfblock bleibt, wie er war.
        assertEquals(1, countOf(r.xml, "<TAGS count=\"1\">"));
    }

    /** Ein Name, den die Datei nicht kennt, wird übergangen – die App legt nie ein Stichwort an. */
    @Test
    public void unknownTagIsNeverCreated() throws Exception {
        KmyDocument d = doc("tagged-split.xml");
        Booking b = expense("Bargeld", "Essen", 250, "Bäcker");
        b.tags = "Erfunden";

        KmyExporter.Result r = new KmyExporter(d, ctx).build(Collections.singletonList(b));

        assertEquals(Collections.emptyList(), r.skipped);
        assertFalse(r.xml.contains("Erfunden"));
        // Nur die beiden Splits der alten Buchung tragen ein Stichwort, die neuen bleiben ohne.
        assertEquals(1, countOf(r.xml, "<TAG id=\"G000001\"/>"));
        assertEquals(1, countOf(r.xml, "<TAGS count=\"1\">"));
    }

    // ---- Wertpapier-Buchungen ----

    /** Ein Wertpapierkauf kommt als Umbuchung herein, mit dem Wertpapier als Gegenkonto. */
    @Test
    public void securityBookingArrivesAsTransfer() throws Exception {
        List<Booking> back = new KmyImporter(doc("security-tx.xml"), ctx)
                .bookingsForAccount("Verrechnungskonto");
        assertEquals(1, back.size());
        assertTrue(back.get(0).isTransfer);
        assertEquals("Musterfonds", back.get(0).transferAccount);
    }

    /**
     * Die eigentliche Probe: Notiz und Stichwort einer Wertpapier-Buchung werden zurückgeschrieben,
     * <b>ohne</b> die Transaktion neu zu bauen. Stückzahl, Kurs und Aktion stehen in keiner Buchung der
     * App – würden sie neu gebaut, wäre das Depot in der Datei zerstört.
     */
    @Test
    public void editingASecurityBookingKeepsSharesAndPrice() throws Exception {
        KmyDocument d = doc("security-tx.xml");
        Booking b = new KmyImporter(d, ctx).bookingsForAccount("Verrechnungskonto").get(0);
        b.id = 5;
        b.edited = true;
        b.origAccount = b.account;
        b.origSignedCents = -101000;
        b.origCreatedAt = b.createdAt;
        b.note = "Sparplanrate";
        b.tags = "Depot";

        KmyExporter.Result r = new KmyExporter(d, ctx)
                .build(new ArrayList<>(), Collections.singletonList(b), new HashMap<>());

        assertEquals(Collections.emptyList(), r.skipped);
        assertEquals(1, r.updated);
        // Notiz und Stichwort sind angekommen – an jedem Split der Transaktion.
        assertEquals(3, countOf(r.xml, "memo=\"Sparplanrate\""));
        assertEquals(3, countOf(r.xml, "<TAG id=\"G000001\"/>"));
        // Und das Wertpapier ist unversehrt.
        assertTrue(r.xml.contains("shares=\"20/1\""));
        assertTrue(r.xml.contains("price=\"50000/1000\""));
        assertTrue(r.xml.contains("action=\"Buy\""));
        assertTrue(r.xml.contains("account=\"A000003\""));
        // Dieselbe Transaktion an derselben Stelle, mit demselben Datum und Betrag.
        assertTrue(r.xml.contains("id=\"T000000000000000001\""));
        assertTrue(r.xml.contains("postdate=\"2026-03-10\""));
        assertTrue(r.xml.contains("value=\"-101000/100\""));
        assertEquals(1, countOf(r.xml, "<TRANSACTION "));
    }

    /**
     * Der Depot-Import trennt Wertpapier-Betrag und Gebühr: {@code amountCents} sind Stücke × Kurs
     * (1000,00 €), die Gebühr aus dem Ausgabe-Kategorie-Split (10,00 €) steht daneben. Sonst könnte die
     * Detailansicht einer Kaufbuchung die Belastung von 1010,00 € nicht mehr herleiten.
     */
    @Test
    public void securityBuyKeepsFeeApartFromAmount() throws Exception {
        List<de.spahr.ausgaben.db.SecurityTx> txs =
                new KmyImporter(doc("security-tx.xml"), ctx).importDepot("Depot").transactions;
        assertEquals(1, txs.size());
        de.spahr.ausgaben.db.SecurityTx tx = txs.get(0);
        assertEquals("buy", tx.action);
        assertEquals(20d, tx.shares, 0.0001);
        assertEquals(100000L, tx.amountCents);
        assertEquals(1000L, tx.feeCents);
    }

    /** Ohne Stichwörter bleiben die Splits selbstschließend – es entsteht kein leeres Elternelement. */
    @Test
    public void patchingWithoutTagsLeavesSelfClosingSplits() throws Exception {
        KmyDocument d = doc("security-tx.xml");
        Booking b = new KmyImporter(d, ctx).bookingsForAccount("Verrechnungskonto").get(0);
        b.id = 5;
        b.edited = true;
        b.origAccount = b.account;
        b.origSignedCents = -101000;
        b.origCreatedAt = b.createdAt;
        b.note = "ohne Stichwort";

        KmyExporter.Result r = new KmyExporter(d, ctx)
                .build(new ArrayList<>(), Collections.singletonList(b), new HashMap<>());

        assertEquals(1, r.updated);
        assertEquals(0, countOf(r.xml, "</SPLIT>"));
        assertEquals(3, countOf(r.xml, "memo=\"ohne Stichwort\""));
        assertTrue(r.xml.contains("shares=\"20/1\""));
    }

    // ---- Fremdwährung ----

    /** Konto in USD, Transaktion in EUR: maßgeblich ist {@code shares} (USD), nicht {@code value}. */
    @Test
    public void foreignAccountUsesShares() throws Exception {
        KmyImporter imp = new KmyImporter(doc("foreign-currency.xml"), ctx);
        List<Booking> usd = imp.bookingsForAccount("Konto USD");
        assertEquals(1, usd.size());
        assertEquals(874708, usd.get(0).amountCents); // 218677/25 USD, nicht 10123 EUR
        assertFalse(usd.get(0).isIncome);
    }

    /** Export schreibt die Währung des Kontos – ein hartes „EUR" wäre in dieser Datei falsch. */
    @Test
    public void exportUsesAccountCurrency() throws Exception {
        KmyDocument d = doc("foreign-currency.xml");
        KmyExporter.Result r = new KmyExporter(d, ctx)
                .build(Collections.singletonList(expense("Konto USD", "Essen USD", 500, "")));
        assertEquals(Collections.emptyList(), r.skipped);
        assertTrue(r.xml.contains("commodity=\"USD\""));
    }

    /** Kategorie in anderer Währung als das Konto: ohne Kurs nicht schreibbar → übersprungen. */
    @Test
    public void currencyMismatchIsSkipped() throws Exception {
        KmyDocument d = doc("foreign-currency.xml");
        KmyExporter.Result r = new KmyExporter(d, ctx)
                .build(Collections.singletonList(expense("Konto USD", "Essen EUR", 500, "")));
        assertEquals(0, r.writtenIds.size());
        assertEquals(1, r.skipped.size());
        assertTrue(r.skipped.get(0).contains("USD"));
        assertTrue(r.skipped.get(0).contains("EUR"));
    }

    // ---- Doppelte Namen ----

    /** Zwei Konten „Girokonto" müssen einzeln ansprechbar bleiben (sonst bucht der Export daneben). */
    @Test
    public void duplicateAccountNamesStayDistinct() throws Exception {
        KmyDocument d = doc("duplicate-names.xml");
        List<String> names = d.accountNames();
        assertEquals(4, names.size()); // Bank A, Bank A:Girokonto, Bank B, Bank B:Girokonto
        assertTrue(names.contains("Bank A:Girokonto"));
        assertTrue(names.contains("Bank B:Girokonto"));
        assertFalse(d.accountId("Bank A:Girokonto").equals(d.accountId("Bank B:Girokonto")));

        // Kategorien: der Pfad trifft, der mehrdeutige Blattname nicht mehr.
        assertEquals("A000006", d.categoryId("Auto:Sonstiges"));
        assertEquals("A000008", d.categoryId("Haus:Sonstiges"));
        assertEquals(null, d.categoryId("Sonstiges"));
    }

    // ---- Splitbuchung über die ganze Kette ----

    /** Splitbuchung schreiben und wieder einlesen: Teilbeträge und Kopf-Kategorie bleiben erhalten. */
    @Test
    public void splitBookingRoundTrips() throws Exception {
        KmyDocument d = doc("empty-blocks.xml");
        Booking b = expense("Bargeld", "Essen", 1000, "Laden");
        Map<Long, List<BookingSplit>> parts = new HashMap<>();
        List<BookingSplit> list = new ArrayList<>();
        list.add(new BookingSplit(b.id, "Essen", 700, false));
        list.add(new BookingSplit(b.id, "Essen", 300, false));
        parts.put(b.id, list);

        KmyExporter.Result r = new KmyExporter(d, ctx).build(Collections.singletonList(b), parts);
        assertEquals(1, r.writtenIds.size());

        List<Booking> back = new KmyImporter(new KmyDocument(r.xml.getBytes(StandardCharsets.UTF_8), ctx),
                ctx).bookingsForAccount("Bargeld");
        assertEquals(1, back.size());
        assertEquals(1000, back.get(0).amountCents);
        assertNotNull(back.get(0).parts);
        assertEquals(2, back.get(0).parts.size());
    }

    /** Keine KMyMoney-Datei (z. B. GPG-verschlüsselt): klare Meldung statt Parserfehler. */
    @Test(expected = IOException.class)
    public void nonKmyFileIsRejected() throws Exception {
        new KmyDocument("-----BEGIN PGP MESSAGE-----\nabcdef\n".getBytes(StandardCharsets.UTF_8), ctx);
    }

    /** Eine Datei, die selbst eine andere Kodierung als UTF-8 deklariert: klare Meldung, nicht stumm falsch gelesen. */
    @Test
    public void wrongEncodingIsRejected() throws Exception {
        try {
            new KmyDocument(("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>"
                    + "<KMYMONEY-FILE></KMYMONEY-FILE>").getBytes(StandardCharsets.UTF_8), ctx);
            fail("hätte werfen müssen");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("ISO-8859-1"));
        }
    }

    // ---- Wiederherstellung nach Absturz (PendingExport) ----

    /** Genau die Signatur einer schon vorhandenen Transaktion wird gefunden. */
    @Test
    public void transactionExistsFindsKnownSignature() throws Exception {
        KmyDocument d = doc("tagged-split.xml");
        KmyExporter exporter = new KmyExporter(d, ctx);
        long created = KmyDocument.parseKmyDate("2026-01-05");

        assertTrue(exporter.transactionExists(d.xml(), "Bargeld", -250, created, "",
                new java.util.HashSet<>()));
    }

    /** Eine abweichende Signatur (falscher Betrag) trifft nichts. */
    @Test
    public void transactionExistsMissesWrongAmount() throws Exception {
        KmyDocument d = doc("tagged-split.xml");
        KmyExporter exporter = new KmyExporter(d, ctx);
        long created = KmyDocument.parseKmyDate("2026-01-05");

        assertFalse(exporter.transactionExists(d.xml(), "Bargeld", -999, created, "",
                new java.util.HashSet<>()));
    }

    /**
     * Ein geteiltes {@code replacedTxIds}-Set über mehrere Abgleiche verhindert, dass zwei zufällig
     * gleich signierte Pending-Einträge dieselbe einzelne Transaktion doppelt „finden" – sonst würde
     * die Wiederherstellung nach einem Absturz eine Buchung fälschlich als schon geschrieben ansehen.
     */
    @Test
    public void transactionExistsSharedSetPreventsDoubleMatch() throws Exception {
        KmyDocument d = doc("tagged-split.xml");
        KmyExporter exporter = new KmyExporter(d, ctx);
        long created = KmyDocument.parseKmyDate("2026-01-05");
        java.util.Set<String> replaced = new java.util.HashSet<>();

        assertTrue(exporter.transactionExists(d.xml(), "Bargeld", -250, created, "", replaced));
        assertFalse(exporter.transactionExists(d.xml(), "Bargeld", -250, created, "", replaced));
    }

    /**
     * Der ganze Wiederherstellungs-Durchlauf: die schon geschriebene Buchung fällt aus der Liste und wird
     * gemeldet, die nicht geschriebene bleibt – und der Vermerk ist danach weg.
     */
    @Test
    public void recoverNimmtNurDieSchonGeschriebeneBuchung() throws Exception {
        KmyDocument d = doc("tagged-split.xml");
        KmyExporter exporter = new KmyExporter(d, ctx);
        de.spahr.ausgaben.settings.SettingsStore settings = new de.spahr.ausgaben.settings.SettingsStore(ctx);
        long created = KmyDocument.parseKmyDate("2026-01-05");
        java.util.List<PendingExport.Entry> vermerk = new java.util.ArrayList<>();
        vermerk.add(new PendingExport.Entry(1, "Bargeld", -250, created, ""));
        vermerk.add(new PendingExport.Entry(2, "Bargeld", -999, created, ""));
        PendingExport.write(settings, vermerk);

        Booking geschrieben = new Booking();
        geschrieben.id = 1;
        Booking offen = new Booking();
        offen.id = 2;
        java.util.List<Booking> bookings = new java.util.ArrayList<>(java.util.Arrays.asList(geschrieben, offen));

        java.util.List<Long> ids = PendingExport.recover(settings, exporter, d.xml(), bookings);

        assertEquals(java.util.Collections.singletonList(1L), ids);
        assertEquals(1, bookings.size());
        assertEquals(2, bookings.get(0).id);
        assertTrue(PendingExport.read(settings).isEmpty());
    }

    /**
     * Der Empfänger als viertes Kriterium: Ein unbekannter Empfängername (existiert in dieser Datei gar
     * nicht) darf die Suche nicht ins Leere laufen lassen – dann zählt wie bisher nur Konto/Betrag/Datum.
     */
    @Test
    public void transactionExistsWithUnknownPayeeIgnoresIt() throws Exception {
        KmyDocument d = doc("tagged-split.xml");
        KmyExporter exporter = new KmyExporter(d, ctx);
        long created = KmyDocument.parseKmyDate("2026-01-05");

        assertTrue(exporter.transactionExists(d.xml(), "Bargeld", -250, created, "Unbekannt",
                new java.util.HashSet<>()));
    }

    /**
     * Die eigentliche Probe für das Verwechslungsrisiko: Zwei Transaktionen mit identischem
     * Konto/Datum/Betrag, aber unterschiedlichem Empfänger. Ohne Empfänger als Kriterium träfe das
     * Bearbeiten immer die erste im Dokument – mit Empfänger die richtige.
     */
    @Test
    public void editingUsesPayeeToDisambiguateSameSignature() throws Exception {
        KmyDocument d = doc("same-signature-different-payee.xml");
        Booking b = new Booking();
        b.id = 9;
        b.edited = true;
        b.origAccount = "Bargeld";
        b.origSignedCents = -250;
        b.origCreatedAt = KmyDocument.parseKmyDate("2026-01-05");
        b.origPayee = "Metzger";
        b.account = "Bargeld";
        b.amountCents = 250;
        b.isIncome = false;
        b.createdAt = KmyDocument.parseKmyDate("2026-01-05");
        b.category = "Essen";
        b.payee = "Metzger";
        b.note = "korrigiert";

        KmyExporter.Result r = new KmyExporter(d, ctx)
                .build(new ArrayList<>(), Collections.singletonList(b), new HashMap<>());

        assertEquals(Collections.emptyList(), r.skipped);
        assertEquals(1, r.updated);
        // Nur die Metzger-Transaktion (T...002) wurde neu gebaut: Kopf und beide Splits tragen die
        // neue Notiz.
        assertEquals(3, countOf(r.xml, "memo=\"korrigiert\""));
        assertTrue(r.xml.contains("id=\"T000000000000000002\""));
        // … die Bäcker-Transaktion (T...001) bleibt Zeichen für Zeichen unangetastet.
        assertTrue(r.xml.contains(
                "<TRANSACTION id=\"T000000000000000001\" postdate=\"2026-01-05\" memo=\"\""
                        + " entrydate=\"2026-01-05\" commodity=\"EUR\">"));
    }

    /** Ohne Empfänger als Unterscheidung träfe das Bearbeiten die erste im Dokument – zum Vergleich. */
    @Test
    public void editingWithoutPayeeHitsTheFirstMatch() throws Exception {
        KmyDocument d = doc("same-signature-different-payee.xml");
        Booking b = new Booking();
        b.id = 9;
        b.edited = true;
        b.origAccount = "Bargeld";
        b.origSignedCents = -250;
        b.origCreatedAt = KmyDocument.parseKmyDate("2026-01-05");
        // origPayee bewusst leer gelassen: kein zusätzliches Kriterium.
        b.account = "Bargeld";
        b.amountCents = 250;
        b.isIncome = false;
        b.createdAt = KmyDocument.parseKmyDate("2026-01-05");
        b.category = "Essen";
        b.payee = "Metzger";
        b.note = "ohne Empfänger-Kriterium";

        KmyExporter.Result r = new KmyExporter(d, ctx)
                .build(new ArrayList<>(), Collections.singletonList(b), new HashMap<>());

        assertEquals(1, r.updated);
        // Trifft die erste Transaktion im Dokument (Bäcker), nicht die eigentlich gemeinte (Metzger).
        assertEquals(3, countOf(r.xml, "memo=\"ohne Empfänger-Kriterium\""));
        assertTrue(r.xml.contains("id=\"T000000000000000001\""));
        // Die Metzger-Transaktion (T...002) bleibt Zeichen für Zeichen unangetastet.
        assertTrue(r.xml.contains(
                "<TRANSACTION id=\"T000000000000000002\" postdate=\"2026-01-05\" memo=\"\""
                        + " entrydate=\"2026-01-05\" commodity=\"EUR\">"));
    }

    static int countOf(String haystack, String needle) {
        int n = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            n++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return n;
    }
}
