package de.spahr.ausgaben.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import de.spahr.ausgaben.settings.SettingsStore;

/**
 * In KMyMoney abgeglichene Buchungen sind in der App schreibgeschützt: Jeder Weg des Repositorys, der
 * eine Buchung ändert oder löscht, lässt sie aus – gleichgültig, was die Maske ihm übergibt.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ReconciledLockTest {

    private AppDatabase db;
    private Repository repo;

    @Before
    public void setUp() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        new SettingsStore(ctx).save("", "", "", "", "", "", SettingsStore.MODE_KMY, "", "");
        db = AppDatabase.getInstance(ctx);
        repo = new Repository(ctx);
        imHintergrund(() -> {
            db.clearAllTables();
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

    /** Auf dem Faden des Repositorys – damit ist zugleich alles abgearbeitet, was davor anstand. */
    private <T> T imHintergrund(Callable<T> arbeit) throws Exception {
        return repo.executor().submit(arbeit).get(10, TimeUnit.SECONDS);
    }

    private void abwarten() throws Exception {
        imHintergrund(() -> null);
    }

    private long buchung(boolean abgeglichen) throws Exception {
        Booking b = new Booking();
        b.account = "Girokonto";
        b.payee = "Bäcker";
        b.category = "Essen";
        b.amountCents = 350L;
        b.createdAt = 1_780_000_000_000L;
        b.exported = true;
        b.reconciled = abgeglichen;
        return imHintergrund(() -> db.bookingDao().insert(b));
    }

    private Booking gelesen(long id) throws Exception {
        return imHintergrund(() -> db.bookingDao().getById(id));
    }

    /** Was die Maske zum Speichern übergäbe: dieselbe Buchung mit anderem Inhalt. */
    private Booking geaendert(long id) throws Exception {
        Booking b = gelesen(id);
        b.amountCents = 999L;
        b.payee = "Metzger";
        b.note = "geändert";
        // Ein veraltetes oder falsches Objekt darf die Sperre nicht aushebeln.
        b.reconciled = false;
        return b;
    }

    private void unveraendert(long id) throws Exception {
        Booking b = gelesen(id);
        assertNotNull("die Buchung muss noch da sein", b);
        assertEquals(350L, b.amountCents);
        assertEquals("Bäcker", b.payee);
        assertEquals("", b.note);
        assertTrue(b.exported);
        assertFalse(b.edited);
        assertTrue(b.reconciled);
        assertEquals("nichts zum Löschen vorgemerkt", 0,
                (int) imHintergrund(() -> db.kmyPendingDeleteDao().getAll().size()));
    }

    @Test
    public void aendernWirdAbgelehnt_aufJedemWeg() throws Exception {
        long id = buchung(true);

        repo.updateBooking(geaendert(id), null);
        abwarten();
        unveraendert(id);

        repo.updateNotesAndTags(geaendert(id), null);
        abwarten();
        unveraendert(id);

        repo.updateSplitBooking(geaendert(id), Arrays.asList(
                new BookingSplit(id, "Essen", 500), new BookingSplit(id, "Auto", 499)), null);
        abwarten();
        unveraendert(id);
        assertEquals(0, (int) imHintergrund(() -> db.bookingDao().getSplits(id).size()));

        repo.updateBookingWithPlace(geaendert(id), "Geldbeutel", null, null);
        abwarten();
        unveraendert(id);

        repo.updateTransferBooking(geaendert(id), "Girokonto", "Bargeld", 999L, "Metzger", "geändert",
                1_780_000_000_000L, null);
        abwarten();
        unveraendert(id);
    }

    @Test
    public void loeschenWirdAbgelehnt_aufJedemWeg() throws Exception {
        long id = buchung(true);

        repo.deleteBooking(id, null);
        abwarten();
        unveraendert(id);

        repo.deleteTransfer("", id, null);
        abwarten();
        unveraendert(id);

        repo.deleteSecurityBooking(gelesen(id), null);
        abwarten();
        unveraendert(id);

        imHintergrund(() -> {
            repo.deleteSecurityBookingNow(db.bookingDao().getById(id));
            return null;
        });
        unveraendert(id);
    }

    /** Bei einer Umbuchung genügt eine abgeglichene Seite – in der Datei ist sie eine Transaktion. */
    @Test
    public void umbuchungMitEinerAbgeglichenenSeite_bleibtGanz() throws Exception {
        Booking raus = new Booking();
        raus.account = "Girokonto";
        raus.transferAccount = "Bargeld";
        raus.isTransfer = true;
        raus.transferGroup = "g1";
        raus.amountCents = 5000L;
        raus.createdAt = 1_780_000_000_000L;
        raus.exported = true;
        raus.reconciled = true;
        Booking rein = new Booking();
        rein.account = "Bargeld";
        rein.transferAccount = "Girokonto";
        rein.isTransfer = true;
        rein.isIncome = true;
        rein.transferGroup = "g1";
        rein.amountCents = 5000L;
        rein.createdAt = raus.createdAt;
        rein.exported = true;
        long idRaus = imHintergrund(() -> db.bookingDao().insert(raus));
        long idRein = imHintergrund(() -> db.bookingDao().insert(rein));

        // Angefasst wird die nicht abgeglichene Seite.
        repo.deleteTransfer("g1", idRein, null);
        abwarten();
        repo.updateTransferBooking(gelesen(idRein), "Girokonto", "Bargeld", 1L, "", "", raus.createdAt,
                null);
        abwarten();

        assertEquals(5000L, gelesen(idRaus).amountCents);
        assertEquals(5000L, gelesen(idRein).amountCents);
        assertEquals(0, (int) imHintergrund(() -> db.kmyPendingDeleteDao().getAll().size()));
    }

    /** Die Gegenprobe: Ohne Abgleich geht beides weiterhin durch. */
    @Test
    public void nichtAbgeglicheneBleibenBearbeitbar() throws Exception {
        long id = buchung(false);
        Booking b = gelesen(id);
        b.amountCents = 999L;
        repo.updateSplitBooking(b, null, null);
        abwarten();
        Booking danach = gelesen(id);
        assertEquals(999L, danach.amountCents);
        assertTrue("im kmy-Modus wird daraus „bearbeitet“", danach.edited);

        repo.deleteBooking(id, null);
        abwarten();
        assertNull(gelesen(id));
        assertEquals(1, (int) imHintergrund(() -> db.kmyPendingDeleteDao().getAll().size()));
    }

    /**
     * In der App gelöscht, in KMyMoney inzwischen abgeglichen: Der Import bringt die Buchung gesperrt
     * zurück und räumt die Vormerkung ab, die der Export nie mehr ausführen würde. Eine Vormerkung zu
     * einer anderen Buchung bleibt.
     */
    @Test
    public void importRaeumtVormerkungAufAbgeglicheneAb() throws Exception {
        final long tag = 1_780_000_000_000L;
        imHintergrund(() -> {
            db.kmyPendingDeleteDao().insert(new KmyPendingDelete("Girokonto", -350L, tag + 3_600_000L,
                    "Bäcker", 1L));
            db.kmyPendingDeleteDao().insert(new KmyPendingDelete("Girokonto", -777L, tag, "", 1L));
            return null;
        });

        Booking ausDatei = new Booking();
        ausDatei.account = "Girokonto";
        ausDatei.payee = "Bäcker";
        ausDatei.amountCents = 350L;
        // Der Import kennt nur den Tag, nicht die Uhrzeit der gelöschten Buchung.
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(tag + 3_600_000L);
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        ausDatei.createdAt = c.getTimeInMillis();
        ausDatei.exported = true;
        ausDatei.reconciled = true;
        LinkedHashMap<String, List<Booking>> map = new LinkedHashMap<>();
        map.put("Girokonto", Collections.singletonList(ausDatei));
        repo.replaceImportAccounts(map, null, r -> { });
        abwarten();

        List<KmyPendingDelete> uebrig = imHintergrund(() -> db.kmyPendingDeleteDao().getAll());
        assertEquals(1, uebrig.size());
        assertEquals(-777L, uebrig.get(0).signedCents);
        List<Booking> alle = imHintergrund(() -> db.bookingDao().getAllBookings());
        assertEquals(1, alle.size());
        assertTrue(alle.get(0).reconciled);
    }
}
