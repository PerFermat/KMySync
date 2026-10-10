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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.BookingSplit;

/**
 * Eine KMyMoney-Datenbank (SQLite) wird gelesen wie eine .kmy-Datei: {@link KmySqlite} bildet sie als
 * XML ab, und der Import liefert daraus dasselbe wie aus der XML-Testdatei mit denselben Daten.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KmySqliteTest {

    private final Context ctx = ApplicationProvider.getApplicationContext();

    private static String beschreibe(Booking b) {
        StringBuilder sb = new StringBuilder();
        sb.append(b.createdAt).append('|').append(b.isIncome ? '+' : '-').append(b.amountCents)
                .append('|').append(b.payee).append('|').append(b.category).append('|')
                .append(b.categoryIsIncome).append('|').append(b.isTransfer).append('|')
                .append(b.transferAccount).append('|').append(b.note).append('|').append(b.tags)
                .append('|').append(b.reconciled);
        if (b.parts != null) {
            for (BookingSplit p : b.parts) {
                sb.append("|[").append(p.category).append(' ').append(p.amountCents).append(']');
            }
        }
        return sb.toString();
    }

    private List<String> buchungen(KmyDocument d) throws Exception {
        List<String> out = new ArrayList<>();
        List<String> konten = d.accountNames();
        for (List<Booking> l : new KmyImporter(d, ctx).bookingsForAccounts(konten, null).values()) {
            for (Booking b : l) {
                out.add(b.account + "|" + beschreibe(b));
            }
        }
        Collections.sort(out);
        return out;
    }

    @Test
    public void erkenntSqliteAmDateikopf() throws Exception {
        assertTrue(KmySqlite.istSqlite(KmyTestDb.ausFixture(ctx, "edited.xml")));
        assertFalse(KmySqlite.istSqlite(KmyRobustnessTest.fixture("edited.xml")));
        assertFalse(KmySqlite.istSqlite(KmyDocument.gzip("<KMYMONEY-FILE/>")));
        assertFalse(KmySqlite.istSqlite(new byte[0]));
        assertFalse(KmySqlite.istSqlite(null));
    }

    /** Für jede Testdatei: aus der Datenbank kommen dieselben Konten und Buchungen wie aus dem XML. */
    @Test
    public void importAusDerDatenbankWieAusDerKmy() throws Exception {
        for (String fixture : new String[]{"edited.xml", "reconciled.xml", "tagged-split.xml",
                "security-tx.xml", "foreign-currency.xml", "institutions.xml", "duplicate-names.xml",
                "depot-name-clash.xml", "same-signature-different-payee.xml", "empty-blocks.xml"}) {
            KmyDocument ausXml = new KmyDocument(KmyRobustnessTest.fixture(fixture), ctx);
            KmyDocument ausDb = new KmyDocument(KmyTestDb.ausFixture(ctx, fixture), ctx);
            assertTrue(fixture, ausDb.istDatenbank());
            assertFalse(fixture, ausXml.istDatenbank());

            List<String> kontenXml = new ArrayList<>(ausXml.accountNames());
            List<String> kontenDb = new ArrayList<>(ausDb.accountNames());
            Collections.sort(kontenXml);
            Collections.sort(kontenDb);
            assertEquals(fixture, kontenXml, kontenDb);
            assertEquals(fixture, ausXml.depotNames(), ausDb.depotNames());
            assertEquals(fixture, ausXml.tagNames(), ausDb.tagNames());
            assertEquals(fixture, ausXml.baseCurrency(), ausDb.baseCurrency());
            assertEquals(fixture, ausXml.categoryTypesByPath(), ausDb.categoryTypesByPath());
            assertEquals(fixture, ausXml.institutionsByAccount(), ausDb.institutionsByAccount());
            assertEquals(fixture, ausXml.maxTransactionNumber(), ausDb.maxTransactionNumber());
            assertEquals(fixture, ausXml.maxPayeeNumber(), ausDb.maxPayeeNumber());
            assertEquals(fixture, buchungen(ausXml), buchungen(ausDb));
            for (String depot : ausXml.depotNames()) {
                assertEquals(fixture, new KmyImporter(ausXml, ctx).importDepot(depot).transactions.size(),
                        new KmyImporter(ausDb, ctx).importDepot(depot).transactions.size());
            }
        }
    }

    /** Der Besitzer der Datei steht als Zeile {@code USER} in der Empfänger-Tabelle – er ist keiner. */
    @Test
    public void besitzerIstKeinEmpfaenger() throws Exception {
        KmyDocument d = new KmyDocument(KmyTestDb.ausFixture(ctx, "edited.xml"), ctx);
        assertTrue(d.xml().contains("<PAYEES count=\"1\">"));
        assertFalse(d.xml().contains("Testnutzer"));
        assertEquals(null, d.payeeId("Testnutzer"));
    }

    @Test
    public void zustandDerDatenbank() throws Exception {
        KmySqlite.Abbild a = new KmyDocument(KmyTestDb.ausFixture(ctx, "edited.xml"), ctx).datenbank();
        assertEquals(KmySqlite.SCHEMA, a.version);
        assertEquals("", a.logonUser);
        assertFalse(a.updateInProgress);
    }

    /** Gleiche Datenbank, gleiches XML – Zeichen für Zeichen; darauf steht später das Schreiben. */
    @Test
    public void abbildungIstFest() throws Exception {
        byte[] roh = KmyTestDb.ausFixture(ctx, "tagged-split.xml");
        String eins = KmyDocument.alsXml(ctx, roh);
        String zwei = KmyDocument.alsXml(ctx, roh);
        assertEquals(eins, zwei);
        assertTrue(eins.startsWith("<?xml"));
        // Ein Element je Zeile, wie KMyMoney schreibt.
        for (String zeile : eins.split("\n")) {
            assertTrue(zeile, zeile.trim().startsWith("<") && zeile.trim().endsWith(">"));
        }
        // Sonderzeichen in Werten sind maskiert, auch Zeilenumbrüche.
        byte[] mitUmbruch = KmyTestDb.ausXml(ctx,
                new String(KmyRobustnessTest.fixture("edited.xml"), StandardCharsets.UTF_8)
                        .replaceFirst("memo=\"\" account=\"A000001\"", "memo=\"a&#xa;b &amp; c\" account=\"A000001\""));
        assertTrue(KmyDocument.alsXml(ctx, mitUmbruch).contains("memo=\"a&#xa;b &amp; c\""));
    }
}
