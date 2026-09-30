package de.spahr.ausgaben.db;

import static org.junit.Assert.assertEquals;
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
 * Die Geldbuchung einer in der App erfassten Dividende ist eine Einnahme, keine Umbuchung – der
 * Bewegung sieht man sie also nicht an. Sie gehört über {@link SecurityTx#bookingId} dazu, und beide
 * müssen gemeinsam verschwinden, samt ihrer Kategorie-Teile.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DividendBookingLinkTest {

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

    /** Legt die Dividende so an, wie DepotRepository.saveManualTx es tut. */
    private long[] dividendeAnlegen() throws Exception {
        SecurityTx tx = new SecurityTx("Depot", "S1", "Alpen Fund", 1_780_000_000_000L,
                SecurityTx.DIVIDEND, 0, 10_000L, 8_000L);
        tx.moneyAccount = "Girokonto";
        tx.parts.add(new SecurityTxSplit(0, true, "Zinsen:Dividende", 10_000L, "", 0));
        tx.parts.add(new SecurityTxSplit(0, false, "Steuern:Kapitalertragssteuer", 2_000L, "", 1));
        Booking b = tx.toMoneyBooking(8_000L);
        return imHintergrund(() -> {
            long bookingId = db.bookingDao().insert(b);
            for (BookingSplit p : b.parts) {
                p.bookingId = bookingId;
                db.bookingDao().insertSplit(p);
            }
            tx.pending = true;
            tx.bookingId = bookingId;
            long txId = db.securityDao().insertTx(tx);
            for (SecurityTxSplit p : tx.parts) {
                p.txId = txId;
                db.securityDao().insertSplit(p);
            }
            return new long[]{bookingId, txId};
        });
    }

    @Test
    public void geldbuchungIstEinnahmeMitTeilen() throws Exception {
        long[] ids = dividendeAnlegen();
        Booking b = imHintergrund(() -> db.bookingDao().getById(ids[0]));
        assertTrue(b.isIncome);
        assertEquals(false, b.isTransfer);
        assertEquals(2, (int) imHintergrund(() -> db.bookingDao().getSplits(ids[0]).size()));
        // Bis zum Export steht sie nicht in der Liste der offenen Buchungen – sie geht mit der Bewegung.
        assertTrue(imHintergrund(() -> db.bookingDao().getUnexported()).isEmpty());
    }

    @Test
    public void loeschenNimmtBewegungUndTeileMit() throws Exception {
        long[] ids = dividendeAnlegen();
        Booking b = imHintergrund(() -> db.bookingDao().getById(ids[0]));

        imHintergrund(() -> {
            repo.deleteSecurityBookingNow(b);
            return null;
        });

        assertNull(imHintergrund(() -> db.bookingDao().getById(ids[0])));
        assertNull(imHintergrund(() -> db.securityDao().getTxById(ids[1])));
        assertTrue(imHintergrund(() -> db.bookingDao().getSplits(ids[0])).isEmpty());
        assertTrue(imHintergrund(() -> db.securityDao().getSplits(ids[1])).isEmpty());
    }
}
