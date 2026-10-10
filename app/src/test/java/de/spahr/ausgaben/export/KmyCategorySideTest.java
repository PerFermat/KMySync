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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.BookingSplit;

/**
 * Derselbe Kategorie-Pfad im Einnahme- und im Ausgabebaum: Der Export trifft die Kategorie, die der
 * Nutzer gewählt hat, nicht die, die beim Einlesen zuletzt kam.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KmyCategorySideTest {

    private static final String PFAD = "Versicherung:Krankenzusatz";
    private static final String AUSGABE = "A000003";
    private static final String EINNAHME = "A000005";

    private final Context ctx = ApplicationProvider.getApplicationContext();

    private KmyDocument xml() throws Exception {
        return new KmyDocument(KmyRobustnessTest.fixture("same-category-both-sides.xml"), ctx);
    }

    private static Booking buchung(long id, boolean einnahme, Boolean seite, long cents) {
        Booking b = new Booking();
        b.id = id;
        b.account = "Bargeld";
        b.category = PFAD;
        b.isIncome = einnahme;
        b.categoryIsIncome = seite;
        b.amountCents = cents;
        b.createdAt = KmyDocument.parseKmyDate("2026-02-0" + id);
        return b;
    }

    /** Die Kategoriekonten der neuen Transaktionen, in der Reihenfolge der Datei. */
    private static String kategorien(KmyDocument alt, String neu) {
        StringBuilder sb = new StringBuilder();
        Matcher tx = Pattern.compile("<TRANSACTION id=\"(T\\d+)\"[\\s\\S]*?</TRANSACTION>").matcher(neu);
        while (tx.find()) {
            if (alt.xml().contains("id=\"" + tx.group(1) + "\"")) {
                continue;
            }
            Matcher sp = Pattern.compile("<SPLIT [^>]*>").matcher(tx.group());
            while (sp.find()) {
                Matcher a = Pattern.compile(" account=\"([^\"]+)\"").matcher(sp.group());
                if (a.find() && !"A000001".equals(a.group(1))) {
                    sb.append(a.group(1)).append(' ');
                }
            }
        }
        return sb.toString().trim();
    }

    private KmyExporter.Result exportiere(KmyDocument d, List<Booking> neu,
                                          Map<Long, List<BookingSplit>> teile) throws Exception {
        KmyExporter.Result r = new KmyExporter(d, ctx).build(neu, Collections.emptyList(), teile);
        assertTrue(String.valueOf(r.skipped), r.skipped.isEmpty());
        KmyExportCheck.pruefen(d.xml(), r.xml, KmyDocument.gzip(r.xml), r.aenderungen);
        return r;
    }

    @Test
    public void dieSeiteEntscheidetWelchesKontoGemeintIst() throws Exception {
        KmyDocument d = xml();
        assertEquals(AUSGABE, d.categoryId(PFAD, false));
        assertEquals(EINNAHME, d.categoryId(PFAD, true));
        // Der Blattname ist je Seite eindeutig, über beide Seiten nicht.
        assertEquals(AUSGABE, d.categoryId("krankenzusatz", false));
        assertEquals(EINNAHME, d.categoryId("Krankenzusatz", true));
        // Was es nur auf einer Seite gibt, wird mit jeder Angabe gefunden.
        assertEquals("A000006", d.categoryId("Essen", true));
        assertEquals("A000007", d.categoryId("Gehalt", false));
        assertEquals("A000006", d.categoryId("Essen", null));
    }

    @Test
    public void ausgabeUndEinnahmeAufDieGleichnamigeKategorie() throws Exception {
        KmyDocument d = xml();
        KmyExporter.Result r = exportiere(d, Arrays.asList(buchung(1, false, false, 4000),
                buchung(2, true, true, 1500)), new HashMap<>());
        assertEquals(AUSGABE + " " + EINNAHME, kategorien(d, r.xml));
    }

    /** Eine Erstattung auf der Ausgabekategorie und eine Rückzahlung auf der Einnahmekategorie. */
    @Test
    public void dieGewaehlteSeiteGiltAuchGegenDieRichtungDerBuchung() throws Exception {
        KmyDocument d = xml();
        KmyExporter.Result r = exportiere(d, Arrays.asList(buchung(1, true, false, 4000),
                buchung(2, false, true, 1500)), new HashMap<>());
        assertEquals(AUSGABE + " " + EINNAHME, kategorien(d, r.xml));
    }

    @Test
    public void ohneAngabeFolgtDieSeiteDerRichtungDerBuchung() throws Exception {
        KmyDocument d = xml();
        KmyExporter.Result r = exportiere(d, Arrays.asList(buchung(1, false, null, 4000),
                buchung(2, true, null, 1500)), new HashMap<>());
        assertEquals(AUSGABE + " " + EINNAHME, kategorien(d, r.xml));
    }

    @Test
    public void splitbuchungMitBeidenSeiten() throws Exception {
        KmyDocument d = xml();
        Booking b = buchung(1, false, null, 3000);
        b.category = "";
        Map<Long, List<BookingSplit>> teile = new HashMap<>();
        teile.put(1L, Arrays.asList(new BookingSplit(1, PFAD, 4000, false),
                new BookingSplit(1, PFAD, -1000, true)));
        KmyExporter.Result r = exportiere(d, Collections.singletonList(b), teile);
        assertEquals(AUSGABE + " " + EINNAHME, kategorien(d, r.xml));
    }

    /** Die Datenbank geht denselben Weg; hier muss die Gegenprobe des Schreibers mitspielen. */
    @Test
    public void auchInDerDatenbank() throws Exception {
        byte[] roh = KmyTestDb.ausFixture(ctx, "same-category-both-sides.xml");
        KmyDocument d = new KmyDocument(roh, ctx);
        KmyExporter.Result r = exportiere(d, Arrays.asList(buchung(1, false, false, 4000),
                buchung(2, true, true, 1500)), new HashMap<>());
        byte[] neu = KmySqliteWriter.schreibe(ctx, roh, r.xml, r.aenderungen);
        String konten = KmyTestDb.frage(ctx, neu, "SELECT accountId FROM kmmSplits"
                + " WHERE accountId IN ('" + AUSGABE + "','" + EINNAHME + "') ORDER BY transactionId");
        assertEquals(AUSGABE + "\n" + EINNAHME, konten.trim());
        assertFalse(konten.contains("A000006"));
    }
}
