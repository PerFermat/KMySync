package de.spahr.ausgaben.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import de.spahr.ausgaben.settings.SettingsStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Ein reiner Betrag von der Uhr, dort ausdrücklich „ohne Empfänger" gewählt.
 *
 * <p>Regression: Die Uhr schickte dafür denselben Text wie ohne Kandidaten in der Nähe – nur den
 * Betrag. Das Handy suchte daraufhin selbst im 100-m-Umkreis und setzte den nächsten Empfänger ein,
 * gegen die ausdrückliche Wahl am Handgelenk.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WearNoPayeeTest {

    private static final String KONTO = "Girokonto";
    private static final String HIER = "50.110900, 8.682100";

    private AppDatabase db;
    private Repository repo;
    private ExecutorService executor;

    @Before
    public void setUp() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        new SettingsStore(ctx).setGpsEnabled(true);
        executor = Executors.newSingleThreadExecutor();
        db = AppDatabase.getInstance(ctx);
        imHintergrund(() -> {
            db.clearAllTables();
            return null;
        });
        repo = new Repository(ctx);
        // Ein Empfänger mit Standort genau hier – den fände die Umkreissuche.
        assertTrue(imHintergrund(() -> repo.createVoiceBookingBlocking(
                "Edeka 12,50", KONTO, "", Repository.VOICE_TYPE_EXPENSE, HIER)));
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

    /** Die zuletzt angelegte Buchung (höchste id). */
    private Booking letzte() throws Exception {
        List<Booking> alle = imHintergrund(() -> db.bookingDao().getAllBookings());
        Booking neueste = null;
        for (Booking b : alle) {
            if (neueste == null || b.id > neueste.id) {
                neueste = b;
            }
        }
        return neueste;
    }

    @Test
    public void ohneMerkmalSuchtDasHandySelbstImUmkreis() throws Exception {
        assertTrue(imHintergrund(() -> repo.createVoiceBookingBlocking(
                "8,00", KONTO, "", Repository.VOICE_TYPE_EXPENSE, HIER, false)));
        assertEquals("Edeka", letzte().payee);
    }

    @Test
    public void ausdruecklichOhneEmpfaengerBleibtOhneEmpfaenger() throws Exception {
        assertTrue(imHintergrund(() -> repo.createVoiceBookingBlocking(
                "8,00", KONTO, "", Repository.VOICE_TYPE_EXPENSE, HIER, true)));
        Booking b = letzte();
        assertEquals("", b.payee);
        assertEquals(800L, b.amountCents);
    }
}
