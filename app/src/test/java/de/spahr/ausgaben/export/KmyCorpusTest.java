package de.spahr.ausgaben.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.KmyPendingDelete;

/**
 * Lässt Lesen, Schreiben und Löschen gegen die echten KMyMoney-Testdateien laufen (aus Fehlerberichten
 * entstanden, FIXVERSION 4–11, Mehrwährung, Depots, Budgets, Kredite). Ohne dieses Nachbar-Repo wird der
 * Test übersprungen; der Pfad lässt sich per {@code -Dkmy.corpus=…} setzen.
 *
 * <p>Geprüft wird nicht der Inhalt einzelner Dateien, sondern was für <b>jede</b> Datei gelten muss:
 * einlesbar, exportierbar zu wohlgeformtem XML mit stimmigem {@code count}, und das Geschriebene kommt
 * unverändert wieder zurück.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KmyCorpusTest {

    private static final String DEFAULT_DIR =
            "/home/michael/git/kmymoney/kmymoney/plugins/views/reports/core/tests/data";

    private final Context ctx = ApplicationProvider.getApplicationContext();

    private static File[] corpus() {
        File dir = new File(System.getProperty("kmy.corpus", DEFAULT_DIR));
        Assume.assumeTrue("KMyMoney-Testdaten nicht vorhanden: " + dir, dir.isDirectory());
        File[] files = dir.listFiles((d, n) -> n.endsWith(".xml"));
        Assume.assumeTrue(files != null && files.length > 0);
        Arrays.sort(files);
        return files;
    }

    @Test
    public void everyFileSurvivesReadWriteDelete() throws Exception {
        int checked = 0;
        int written = 0;
        for (File f : corpus()) {
            byte[] raw = Files.readAllBytes(f.toPath());
            if (!KmyDocument.looksLikeKmyXml(new String(raw, StandardCharsets.UTF_8))) {
                continue; // im selben Ordner liegen auch Berichtsdefinitionen o. Ä.
            }
            written += checkFile(f.getName(), raw) ? 1 : 0;
            checked++;
        }
        // Der Standardordner enthält gut 40 Dateien; deutlich weniger hieße, der Filter greift zu scharf.
        // Bei selbst gesetztem Ordner (-Dkmy.corpus) genügt eine Datei.
        int min = System.getProperty("kmy.corpus") == null ? 20 : 1;
        assertTrue("nur " + checked + " Dateien geprüft", checked >= min);
        assertTrue("nur " + written + " Dateien beschrieben", written >= min);
    }

    /** @return {@code true}, wenn die Datei auch beschrieben und wieder bereinigt wurde */
    private boolean checkFile(String name, byte[] raw) throws Exception {
        KmyDocument doc = new KmyDocument(raw, ctx);
        List<String> accounts = doc.accountNames();
        KmyImporter imp = new KmyImporter(doc, ctx);

        // 1) Lesen: alle Konten, geplante Buchungen und Budgets müssen ohne Ausnahme durchlaufen.
        imp.bookingsForAccounts(accounts, null);
        imp.scheduledTransactions();
        for (int year : imp.budgetYears()) {
            imp.budgetEntries(year);
        }
        for (String depot : imp.depotNames()) {
            imp.importDepot(depot);
        }
        if (accounts.isEmpty()) {
            return false; // Datei ohne bebuchbares Konto – nichts zu schreiben
        }

        // 2) Schreiben: eine Buchung auf dem ersten Konto, Kategorie passend zur Währung des Kontos.
        String account = accounts.get(0);
        String category = categoryFor(doc, account);
        Booking b = new Booking();
        b.id = 42;
        b.account = account;
        b.category = category == null ? "" : category;
        b.payee = "Ausgaben-Test";
        b.amountCents = 1234;
        b.createdAt = KmyDocument.parseKmyDate("2026-03-17");
        KmyExporter.Result r = new KmyExporter(doc, ctx).build(Collections.singletonList(b));
        assertEquals(name + ": Buchung nicht geschrieben " + r.skipped, 1, r.writtenIds.size());
        assertWellFormed(name, r.xml);
        assertCountMatches(name, r.xml);
        // Dieselbe Selbstprüfung, die der Export vor dem Hochladen macht, muss jede Datei bestehen.
        KmyExportCheck.pruefen(doc.xml(), r.xml, KmyDocument.gzip(r.xml), 0, r.writtenIds.size());

        // 3) Zurücklesen: derselbe Betrag, dasselbe Datum, dieselbe Kategorie.
        KmyDocument written = new KmyDocument(r.xml.getBytes(StandardCharsets.UTF_8), ctx);
        List<Booking> back = new KmyImporter(written, ctx).bookingsForAccount(account);
        Booking mine = null;
        for (Booking x : back) {
            if ("Ausgaben-Test".equals(x.payee)) {
                mine = x;
            }
        }
        assertNotNull(name + ": geschriebene Buchung nicht wiedergefunden", mine);
        assertEquals(name, 1234, mine.amountCents);
        assertEquals(name, KmyDocument.parseKmyDate("2026-03-17"), mine.createdAt);
        if (category != null) {
            assertEquals(name, category, mine.category);
        }

        // 4) Löschen: die eigene Buchung wieder entfernen – danach steht die Datei wie zuvor.
        KmyPendingDelete del = new KmyPendingDelete();
        del.id = 1;
        del.account = account;
        del.createdAt = b.createdAt;
        del.signedCents = -1234;
        KmyExporter.DeleteResult dr = new KmyExporter(written, ctx)
                .removeTransactions(r.xml, Collections.singletonList(del));
        assertEquals(name + ": Buchung nicht wieder löschbar", 1, dr.resolvedIds.size());
        assertWellFormed(name, dr.xml);
        assertCountMatches(name, dr.xml);
        KmyExportCheck.pruefen(r.xml, dr.xml, KmyDocument.gzip(dr.xml), dr.resolvedIds.size(), 0);
        return true;
    }

    /** Erste Kategorie in der Währung des Kontos (sonst überspringt der Export sie zu Recht). */
    private String categoryFor(KmyDocument doc, String account) {
        String wanted = doc.currencyOfAccount(account);
        for (String path : doc.categoryTypesByPath().keySet()) {
            String id = doc.categoryId(path);
            if (id == null) {
                continue;
            }
            String cur = doc.accountCurrencyOf(id);
            if (cur.isEmpty() || wanted.isEmpty() || cur.equalsIgnoreCase(wanted)) {
                return path;
            }
        }
        return null;
    }

    private static void assertWellFormed(String name, String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setValidating(false);
        f.setNamespaceAware(false);
        // Der DOCTYPE zeigt auf keine DTD – Auflösung abschalten, sonst sucht der Parser im Dateisystem.
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /** {@code <TRANSACTIONS count="N">} muss zur tatsächlichen Zahl der Buchungen im Hauptbuch passen. */
    private static void assertCountMatches(String name, String xml) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("<TRANSACTIONS\\b[^>]*\\bcount=\"(\\d+)\"").matcher(xml);
        if (!m.find()) {
            return; // Datei ohne count-Attribut – KMyMoney nutzt es nur als Hinweis
        }
        int ledgerEnd = xml.indexOf("</TRANSACTIONS>");
        String ledger = ledgerEnd < 0 ? "" : xml.substring(m.end(), ledgerEnd);
        assertEquals(name + ": count passt nicht zur Zahl der TRANSACTION-Elemente",
                Integer.parseInt(m.group(1)), KmyRobustnessTest.countOf(ledger, "<TRANSACTION "));
    }
}
