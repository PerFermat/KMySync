package de.spahr.ausgaben.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import de.spahr.ausgaben.export.KmyDocument;
import de.spahr.ausgaben.export.KmyImporter;

/**
 * Die Seite einer Kategorie wird dort, wo sie fehlt, einmal nachgetragen: nach der Typtabelle, wenn
 * der Name dort nur auf einer Seite steht, sonst nach der Richtung dessen, was die Kategorie trägt.
 * Was schon gespeichert ist, bleibt.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CategorySideFillTest {

    /** Gibt es im Einnahme- und im Ausgabebaum. */
    private static final String BEIDE = "Versicherung:Krankenzusatz";

    private final Context ctx = ApplicationProvider.getApplicationContext();
    private AppDatabase db;

    private static <T> T imHintergrund(Callable<T> c) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Throwable> fehler = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                out.set(c.call());
            } catch (Throwable e) {
                fehler.set(e);
            }
        });
        t.start();
        t.join();
        if (fehler.get() != null) {
            throw new AssertionError(fehler.get());
        }
        return out.get();
    }

    @Before
    public void setUp() throws Exception {
        db = AppDatabase.getInstance(ctx);
        imHintergrund(() -> {
            db.clearAllTables();
            db.categoryTypeDao().upsert(new CategoryType(BEIDE, true));
            db.categoryTypeDao().upsert(new CategoryType(BEIDE, false));
            db.categoryTypeDao().upsert(new CategoryType("Essen", false));
            db.categoryTypeDao().upsert(new CategoryType("Gehalt", true));
            return null;
        });
    }

    @After
    public void tearDown() throws Exception {
        imHintergrund(() -> {
            db.clearAllTables();
            return null;
        });
    }

    private long buchung(String kategorie, boolean einnahme, Boolean seite) {
        Booking b = new Booking();
        b.account = "Bargeld";
        b.category = kategorie;
        b.isIncome = einnahme;
        b.categoryIsIncome = seite;
        b.amountCents = 100;
        return db.bookingDao().insert(b);
    }

    private Boolean seiteDerBuchung(long id) {
        return db.bookingDao().getById(id).categoryIsIncome;
    }

    @Test
    public void typtabelleFuehrtBeideSeitenUndNenntNurEindeutiges() throws Exception {
        imHintergrund(() -> {
            assertEquals(4, db.categoryTypeDao().getAll().size());
            assertEquals(Boolean.FALSE, db.categoryTypeDao().isIncome("essen"));
            assertEquals(Boolean.TRUE, db.categoryTypeDao().isIncome("Gehalt"));
            assertNull(db.categoryTypeDao().isIncome(BEIDE));
            assertNull(db.categoryTypeDao().isIncome("Gibt es nicht"));
            return null;
        });
    }

    @Test
    public void buchungen() throws Exception {
        imHintergrund(() -> {
            // Eindeutig: die Seite der Tabelle, auch gegen die Richtung (Erstattung).
            long erstattung = buchung("Essen", true, null);
            // In beiden Bäumen: die Richtung der Buchung.
            long beitrag = buchung(BEIDE, false, null);
            long leistung = buchung(BEIDE, true, null);
            // Unbekannt: die Richtung.
            long fremd = buchung("Gibt es nicht", true, null);
            // Schon gespeichert: bleibt, auch wenn alles dagegen spricht.
            long gesetzt = buchung(BEIDE, false, Boolean.TRUE);
            long ohne = buchung("", false, null);

            db.categorySideDao().fillMissing();

            assertEquals(Boolean.FALSE, seiteDerBuchung(erstattung));
            assertEquals(Boolean.FALSE, seiteDerBuchung(beitrag));
            assertEquals(Boolean.TRUE, seiteDerBuchung(leistung));
            assertEquals(Boolean.TRUE, seiteDerBuchung(fremd));
            assertEquals(Boolean.TRUE, seiteDerBuchung(gesetzt));
            assertNull(seiteDerBuchung(ohne));

            // Einmal: Was jetzt dasteht, ändert auch eine andere Typtabelle nicht mehr.
            db.categoryTypeDao().upsert(new CategoryType("Gibt es nicht", false));
            db.categorySideDao().fillMissing();
            assertEquals(Boolean.TRUE, seiteDerBuchung(fremd));
            return null;
        });
    }

    @Test
    public void teileEinerSplitbuchung() throws Exception {
        imHintergrund(() -> {
            long ausgabe = buchung("", false, null);
            db.bookingDao().insertSplit(new BookingSplit(ausgabe, BEIDE, 4000, null));
            db.bookingDao().insertSplit(new BookingSplit(ausgabe, BEIDE, -1000, null));
            db.bookingDao().insertSplit(new BookingSplit(ausgabe, "Gehalt", 500, null));
            db.bookingDao().insertSplit(new BookingSplit(ausgabe, BEIDE, 700, Boolean.TRUE));

            db.categorySideDao().fillMissing();

            List<BookingSplit> t = db.bookingDao().getSplits(ausgabe);
            // Mit der Buchung: Ausgabe. Gegen sie: Einnahme. Eindeutig: laut Tabelle. Gesetzt: bleibt.
            assertEquals(Boolean.FALSE, t.get(0).categoryIsIncome);
            assertEquals(Boolean.TRUE, t.get(1).categoryIsIncome);
            assertEquals(Boolean.TRUE, t.get(2).categoryIsIncome);
            assertEquals(Boolean.TRUE, t.get(3).categoryIsIncome);
            return null;
        });
    }

    @Test
    public void aliasNachSeinemFeld() throws Exception {
        imHintergrund(() -> {
            PayeeCorrection a = new PayeeCorrection();
            a.spoken = "kasse";
            a.corrected = "Kasse";
            a.catExpense1 = BEIDE;
            a.catExpense2 = "Gehalt";
            a.catIncome1 = BEIDE;
            a.catIncome2 = "Unbekannt";
            db.payeeCorrectionDao().upsert(a);

            db.categorySideDao().fillMissing();

            PayeeCorrection z = db.payeeCorrectionDao().getAll().get(0);
            assertEquals(Boolean.FALSE, z.catExpense1IsIncome);
            assertEquals(Boolean.TRUE, z.catExpense2IsIncome);   // eindeutig: laut Tabelle
            assertEquals(Boolean.TRUE, z.catIncome1IsIncome);
            assertEquals(Boolean.TRUE, z.catIncome2IsIncome);
            return null;
        });
    }

    @Test
    public void depotZeilenUndPlanungen() throws Exception {
        imHintergrund(() -> {
            SecurityTx tx = new SecurityTx();
            long txId = db.securityDao().insertTx(tx);
            db.securityDao().insertSplit(new SecurityTxSplit(txId, true, BEIDE, 10000, "", 0));
            db.securityDao().insertSplit(new SecurityTxSplit(txId, true, BEIDE, -2500, "", 1));
            db.securityDao().insertSplit(new SecurityTxSplit(txId, false, BEIDE, 300, "", 2));
            db.securityDao().insertSplit(new SecurityTxSplit(txId, false, "Gehalt", 300, "", 3));

            ScheduledTransaction einnahme = new ScheduledTransaction("S1", "Leistung",
                    ScheduledTransaction.KIND_INCOME, 0, 100, "", "Bargeld", BEIDE, 0, 1, 0);
            long e = db.scheduledTransactionDao().insert(einnahme);
            ScheduledTransaction ausgabe = new ScheduledTransaction("S2", "Beitrag",
                    ScheduledTransaction.KIND_EXPENSE, 0, 100, "", "Bargeld", "", 0, 1, 0);
            ausgabe.split = 1;
            long a = db.scheduledTransactionDao().insert(ausgabe);
            db.scheduledSplitDao().insert(new ScheduledSplit(a, BEIDE, 4000));
            db.scheduledSplitDao().insert(new ScheduledSplit(a, BEIDE, -1000));
            ScheduledTransaction umbuchung = new ScheduledTransaction("S3", "Umbuchung",
                    ScheduledTransaction.KIND_TRANSFER, 0, 100, "", "Bargeld", "Girokonto", 0, 1, 0);
            long u = db.scheduledTransactionDao().insert(umbuchung);

            db.categorySideDao().fillMissing();

            List<SecurityTxSplit> z = db.securityDao().getSplits(txId);
            // Reihenfolge der Abfrage: erst die Gebührenzeilen, dann die Ertragszeilen.
            assertEquals(Boolean.FALSE, z.get(0).categoryIsIncome);   // Gebühr
            assertEquals(Boolean.TRUE, z.get(1).categoryIsIncome);    // eindeutig: laut Tabelle
            assertEquals(Boolean.TRUE, z.get(2).categoryIsIncome);    // Ertrag
            assertEquals(Boolean.FALSE, z.get(3).categoryIsIncome);   // Abzug im Ertrag

            assertEquals(Boolean.TRUE, db.scheduledTransactionDao().getById(e).counterpartyIsIncome);
            List<ScheduledSplit> t = db.scheduledSplitDao().getForScheduled(a);
            assertEquals(Boolean.FALSE, t.get(0).categoryIsIncome);
            assertEquals(Boolean.TRUE, t.get(1).categoryIsIncome);
            // Das Gegenkonto einer Umbuchung ist keine Kategorie.
            assertNull(db.scheduledTransactionDao().getById(u).counterpartyIsIncome);
            return null;
        });
    }

    /** Die Datei nennt für jede Kategorie beide Seiten, wenn es beide gibt. */
    @Test
    public void einlesenLiefertBeideSeiten() throws Exception {
        byte[] roh = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/test/resources/kmy/same-category-both-sides.xml"));
        KmyDocument d = new KmyDocument(roh, ctx);
        List<CategoryType> typen = new KmyImporter(d, ctx).categoryTypes();
        int einnahme = 0;
        int ausgabe = 0;
        for (CategoryType t : typen) {
            if (BEIDE.equals(t.category)) {
                if (t.isIncome) {
                    einnahme++;
                } else {
                    ausgabe++;
                }
            }
        }
        assertEquals(1, einnahme);
        assertEquals(1, ausgabe);
        assertEquals(Boolean.FALSE, d.categorySideOf("A000003"));
        assertEquals(Boolean.TRUE, d.categorySideOf("A000005"));
        assertNull(d.categorySideOf("A000001"));
    }
}
