package de.spahr.ausgaben.db;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Wechsel weg vom .kmy-Schreibziel: Was nur dorthin gelangt wäre, wird gezählt und auf Wunsch
 * verworfen – vollständig und so, dass per CSV nichts doppelt hinausgeht.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KmyPendingDiscardTest {

    private AppDatabase db;
    private Repository repo;
    private ExecutorService executor;

    @Before
    public void setUp() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        new SettingsStore(ctx).save("", "", "", "", "", "", SettingsStore.MODE_KMY, "", "");
        executor = Executors.newSingleThreadExecutor();
        db = AppDatabase.getInstance(ctx);
        imHintergrund(() -> {
            db.clearAllTables();
            return null;
        });
        repo = new Repository(ctx);
    }

    @After
    public void tearDown() throws Exception {
        imHintergrund(() -> {
            db.clearAllTables();
            return null;
        });
        executor.shutdownNow();
    }

    private <T> T imHintergrund(Callable<T> arbeit) throws Exception {
        return executor.submit(arbeit).get(10, TimeUnit.SECONDS);
    }

    private long buchung(boolean exported, boolean edited) throws Exception {
        Booking b = new Booking();
        b.account = "Girokonto";
        b.payee = "Bäcker";
        b.amountCents = 350L;
        b.createdAt = 1_780_000_000_000L;
        b.exported = exported;
        b.edited = edited;
        if (edited) {
            b.origAccount = "Girokonto";
            b.origSignedCents = -300L;
            b.origCreatedAt = b.createdAt;
            b.origPayee = "Bäcker";
        }
        return imHintergrund(() -> db.bookingDao().insert(b));
    }

    private void vormerkungenAnlegen(long geldbuchung) throws Exception {
        imHintergrund(() -> {
            db.kmyPendingDeleteDao().insert(
                    new KmyPendingDelete("Girokonto", -500L, 1_780_000_000_000L, "Kiosk", 1L));
            db.scheduledAdvanceDao().insert(new ScheduledAdvance("SCH000001", 1L, 2L, 1L, 1L));
            SecurityTx tx = new SecurityTx();
            tx.depot = "Depot";
            tx.securityKmyId = "S000001";
            tx.securityName = "ETF";
            tx.moneyAccount = "Girokonto";
            tx.action = SecurityTx.BUY;
            tx.shares = 1.0;
            tx.amountCents = 10_000L;
            tx.netCents = 10_000L;
            tx.date = 1_780_000_000_000L;
            tx.pending = true;
            tx.bookingId = geldbuchung;
            db.securityDao().insertTx(tx);
            return null;
        });
    }

    @Test
    public void zaehltJedeArtEinzeln() throws Exception {
        long geld = buchung(false, false);
        buchung(true, true);
        vormerkungenAnlegen(geld);

        assertArrayEquals(new int[]{1, 1, 1, 1}, imHintergrund(() -> repo.countKmyPendingNow()));
    }

    @Test
    public void verwerfenRaeumtAllesAb() throws Exception {
        long geld = buchung(false, false);
        long bearbeitet = buchung(true, true);
        long unbeteiligt = buchung(false, false);
        vormerkungenAnlegen(geld);

        imHintergrund(() -> {
            repo.discardKmyPendingNow();
            return null;
        });

        assertArrayEquals(new int[]{0, 0, 0, 0}, imHintergrund(() -> repo.countKmyPendingNow()));
        // Die Geldbuchung der Depot-Bewegung geht mit – per CSV ließe sie sich nicht sinnvoll exportieren.
        assertNull(imHintergrund(() -> db.bookingDao().getById(geld)));
        // Die bearbeitete Buchung gilt als exportiert: sonst ginge sie als zweite Buchung per CSV hinaus.
        Booking b = imHintergrund(() -> db.bookingDao().getById(bearbeitet));
        assertTrue(b.exported);
        assertFalse(b.edited);
        assertEquals("", b.origPayee);
        // Eine gewöhnliche offene Buchung bleibt, wie sie ist.
        Booking u = imHintergrund(() -> db.bookingDao().getById(unbeteiligt));
        assertFalse(u.exported);
    }
}
