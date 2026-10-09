package de.spahr.ausgaben.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

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
 * entstanden, FIXVERSION 4–11, Mehrwährung, Depots, Budgets, Kredite). Sie liegen als Kopie in
 * {@code src/test/resources/kmy/corpus} – Herkunft und Lizenz stehen dort in der README. Ein weiterer
 * Ordner lässt sich per {@code -Dkmy.corpus=…} zusätzlich mitprüfen.
 *
 * <p>Geprüft wird nicht der Inhalt einzelner Dateien, sondern was für <b>jede</b> Datei gelten muss:
 * einlesbar, exportierbar zu wohlgeformtem XML mit stimmigem {@code count}, das Geschriebene kommt
 * unverändert wieder zurück, und nach dem Löschen steht die Datei Zeichen für Zeichen da wie zuvor.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KmyCorpusTest {

    /** Die mitgelieferten Dateien; der Test läuft im Modulordner. */
    private static final String ORDNER = "src/test/resources/kmy/corpus";

    /**
     * So viele Dateien müssen mindestens geprüft und beschrieben worden sein. Der Ordner enthält gut 40;
     * deutlich weniger hieße, er ist leer, verschoben, oder der Filter greift zu scharf.
     */
    private static final int MINDESTENS = 20;

    private final Context ctx = ApplicationProvider.getApplicationContext();

    private static List<File> xmlDateien(File dir) {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".xml"));
        assertNotNull(dir + " ist kein lesbarer Ordner", files);
        Arrays.sort(files);
        return Arrays.asList(files);
    }

    @Test
    public void everyFileSurvivesReadWriteDelete() throws Exception {
        File dir = new File(ORDNER);
        assertTrue("mitgelieferte KMyMoney-Testdaten fehlen: " + dir.getAbsolutePath(), dir.isDirectory());
        int[] gezaehlt = pruefe(xmlDateien(dir));
        assertTrue("nur " + gezaehlt[0] + " Dateien geprüft", gezaehlt[0] >= MINDESTENS);
        assertTrue("nur " + gezaehlt[1] + " Dateien beschrieben", gezaehlt[1] >= MINDESTENS);

        // Optionale Zusatzquelle, etwa ein frischer Stand aus dem KMyMoney-Repo oder eigene Dateien.
        String extra = System.getProperty("kmy.corpus");
        if (extra != null && !extra.trim().isEmpty()) {
            int[] zusaetzlich = pruefe(xmlDateien(new File(extra)));
            assertTrue("in " + extra + " keine KMyMoney-Datei gefunden", zusaetzlich[0] >= 1);
        }
    }

    /** @return {@code [geprüft, beschrieben]} */
    private int[] pruefe(List<File> dateien) throws Exception {
        int checked = 0;
        int written = 0;
        for (File f : dateien) {
            byte[] raw = Files.readAllBytes(f.toPath());
            if (!KmyDocument.looksLikeKmyXml(new String(raw, StandardCharsets.UTF_8))) {
                continue; // im selben Ordner liegen auch Berichtsdefinitionen o. Ä.
            }
            written += checkFile(f.getName(), raw) ? 1 : 0;
            checked++;
        }
        return new int[]{checked, written};
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
        KmyExportCheck.pruefen(doc.xml(), r.xml, KmyDocument.gzip(r.xml), r.aenderungen);

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
        KmyExportCheck.pruefen(r.xml, dr.xml, KmyDocument.gzip(dr.xml), dr.aenderungen);
        assertEquals(name + ": nach Schreiben und Löschen steht die Datei nicht mehr da wie zuvor",
                doc.xml(), ohneSpuren(doc.xml(), dr.xml, r.aenderungen));
        return true;
    }

    /**
     * Nimmt aus {@code danach} heraus, was ein Lauf „schreiben und wieder löschen" zu Recht hinterlässt –
     * und nur das. Was dann übrig ist, muss Zeichen für Zeichen das Original sein.
     *
     * <ul>
     *   <li>der für die Testbuchung neu angelegte Empfänger samt dem hochgezählten {@code count},</li>
     *   <li>das Datum der letzten Änderung im Dateikopf,</li>
     *   <li>ein leerer Behälter, der vorher selbstschließend dastand ({@code <PAYEES/>}) und zum
     *       Einfügen aufgeklappt wurde.</li>
     * </ul>
     */
    private static String ohneSpuren(String original, String danach, KmyAenderungen geschrieben)
            throws Exception {
        KmyGliederung.Inhalt alt = KmyGliederung.lesen(original).wurzel.inhalt();
        String out = danach;
        // Von hinten nach vorn ersetzen, damit die Stellen der vorderen Bereiche gültig bleiben.
        List<KmyGliederung.Element> bereiche = KmyGliederung.lesen(danach).wurzel.inhalt().kinder;
        for (int i = bereiche.size() - 1; i >= 0; i--) {
            KmyGliederung.Element neu = bereiche.get(i);
            KmyGliederung.Element vorher = alt.kind(neu.name);
            if (vorher == null) {
                continue;
            }
            String ersatz = null;
            if ("FILEINFO".equals(neu.name)) {
                KmyGliederung.Element datumNeu = neu.inhalt().kind("LAST_MODIFIED_DATE");
                KmyGliederung.Element datumAlt = vorher.inhalt().kind("LAST_MODIFIED_DATE");
                if (datumNeu != null && datumAlt != null) {
                    ersatz = danach.substring(neu.start, datumNeu.start) + datumAlt.text()
                            + danach.substring(datumNeu.ende, neu.ende);
                }
            } else if ("PAYEES".equals(neu.name) || "TRANSACTIONS".equals(neu.name)) {
                StringBuilder kinder = new StringBuilder();
                KmyGliederung.Inhalt inhalt = neu.inhalt();
                int geblieben = 0;
                for (int k = 0; k < inhalt.kinder.size(); k++) {
                    KmyGliederung.Element kind = inhalt.kinder.get(k);
                    // Der neue Empfänger geht samt seiner Zeile: Zeilenende und Einrückung davor.
                    if (!geschrieben.neueEmpfaenger().contains(kind.id())) {
                        kinder.append(inhalt.luecken.get(k)).append(kind.text());
                        geblieben++;
                    }
                }
                kinder.append(inhalt.luecken.get(inhalt.kinder.size()));
                ersatz = vorher.leer && geblieben == 0
                        ? vorher.text()
                        : vorher.oeffnung() + kinder + "</" + neu.name + ">";
            }
            if (ersatz != null) {
                out = out.substring(0, neu.start) + ersatz + out.substring(neu.ende);
            }
        }
        return out;
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
