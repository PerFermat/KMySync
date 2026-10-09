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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Solange ein Export läuft, sind die Buchungen gesperrt, die es bei seinem Start schon gab – neue
 * bleiben frei, werden nicht mit exportiert und überstehen das Aktualisieren danach.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ExportLockTest {

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
        ExportLock.end();
    }

    @After
    public void tearDown() throws Exception {
        ExportLock.end();
        imHintergrund(() -> {
            db.clearAllTables();
            return null;
        });
    }

    private <T> T imHintergrund(Callable<T> arbeit) throws Exception {
        return repo.executor().submit(arbeit).get(10, TimeUnit.SECONDS);
    }

    private long buchung(boolean exported, long cents) throws Exception {
        Booking b = new Booking();
        b.account = "Girokonto";
        b.payee = "Bäcker";
        b.category = "Essen";
        b.amountCents = cents;
        b.createdAt = 1_780_000_000_000L;
        b.exported = exported;
        return imHintergrund(() -> db.bookingDao().insert(b));
    }

    private Booking gelesen(long id) throws Exception {
        return imHintergrund(() -> db.bookingDao().getById(id));
    }

    // ---- Die Regel ----

    @Test
    public void ohneLaufIstNichtsGesperrt() {
        assertFalse(ExportLock.active());
        assertFalse(ExportLock.locked(1));
        assertFalse(ExportLock.locked((Booking) null));
    }

    @Test
    public void gesperrtIstWasEsBeimStartSchonGab() {
        ExportLock.begin(41);
        assertTrue(ExportLock.active());
        assertTrue(ExportLock.locked(1));
        assertTrue(ExportLock.locked(41));
        assertFalse("danach angelegt: frei", ExportLock.locked(42));
        assertFalse("noch ohne id: eine neue Buchung", ExportLock.locked(0));
        ExportLock.end();
        assertFalse(ExportLock.locked(41));
    }

    /** Auch in einer leeren Datenbank läuft ein Export – gesperrt ist dann eben nichts. */
    @Test
    public void leererBestand() {
        ExportLock.begin(0);
        assertTrue(ExportLock.active());
        assertFalse(ExportLock.locked(1));
    }

    // ---- Im Repository ----

    @Test
    public void waehrendDesLaufsBleibenVorhandeneBuchungenWieSieSind() throws Exception {
        long alt = buchung(true, 350L);
        long nochNieExportiert = buchung(false, 200L);
        ExportLock.begin(imHintergrund(() -> db.bookingDao().getMaxId()));

        Booking b = gelesen(alt);
        b.amountCents = 999L;
        repo.updateSplitBooking(b, null, null);
        repo.deleteBooking(alt, null);
        // Auch die noch nie exportierte steht im festgehaltenen Stand: Der Lauf schreibt sie gerade
        // und markiert sie am Ende – eine Änderung dazwischen gälte als geschrieben, ohne es zu sein.
        Booking c = gelesen(nochNieExportiert);
        c.amountCents = 999L;
        repo.updateSplitBooking(c, null, null);
        repo.deleteBooking(nochNieExportiert, null);
        imHintergrund(() -> null);

        assertEquals(350L, gelesen(alt).amountCents);
        assertFalse(gelesen(alt).edited);
        assertEquals(200L, gelesen(nochNieExportiert).amountCents);
        assertEquals(0, (int) imHintergrund(() -> db.kmyPendingDeleteDao().getAll().size()));

        // Nach dem Lauf geht beides wieder.
        ExportLock.end();
        repo.updateSplitBooking(b, null, null);
        imHintergrund(() -> null);
        assertEquals(999L, gelesen(alt).amountCents);
    }

    @Test
    public void neueBuchungenBleibenFrei_undUeberstehenDasAktualisieren() throws Exception {
        long alt = buchung(true, 350L);
        ExportLock.begin(imHintergrund(() -> db.bookingDao().getMaxId()));

        // Während des Laufs angelegt, geändert – und eine zweite angelegt und wieder gelöscht.
        long neu = buchung(false, 120L);
        Booking n = gelesen(neu);
        n.amountCents = 130L;
        repo.updateSplitBooking(n, null, null);
        long weg = buchung(false, 50L);
        repo.deleteBooking(weg, null);
        imHintergrund(() -> null);
        assertEquals(130L, gelesen(neu).amountCents);
        assertNull(gelesen(weg));

        // Der Lauf markiert, was in seinem Stand war, und aktualisiert dann das Konto aus der Datei.
        imHintergrund(() -> {
            db.bookingDao().markExported(Collections.singletonList(alt));
            return null;
        });
        Booking ausDatei = new Booking();
        ausDatei.account = "Girokonto";
        ausDatei.payee = "Bäcker";
        ausDatei.amountCents = 350L;
        ausDatei.createdAt = 1_780_000_000_000L;
        ausDatei.exported = true;
        LinkedHashMap<String, List<Booking>> map = new LinkedHashMap<>();
        map.put("Girokonto", Collections.singletonList(ausDatei));
        repo.replaceImportAccounts(map, null, r -> { });
        imHintergrund(() -> null);
        ExportLock.end();

        Booking geblieben = gelesen(neu);
        assertNotNull("die neue Buchung hat das Aktualisieren überstanden", geblieben);
        assertEquals(130L, geblieben.amountCents);
        assertFalse("und wartet auf den nächsten Export", geblieben.exported);
        assertEquals(2, (int) imHintergrund(() -> db.bookingDao().getAllBookings().size()));
    }
}
