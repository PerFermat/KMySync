package de.spahr.ausgaben.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.button.MaterialButtonToggleGroup;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.AppDatabase;
import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.Repository;

/**
 * Eine gespeicherte Buchung in eine andere Art umwandeln (Umbuchung ↔ Ausgabe): Intern entsteht sie
 * neu, ihr Standort ist aber der, an dem bezahlt wurde – nicht der, an dem man sie umstellt.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ConvertKeepsLocationTest {

    private static final String GPS = "GPS: 50.123456, 8.654321";

    private Context ctx;
    private Repository repo;

    @Before
    public void setUp() throws Exception {
        ctx = ApplicationProvider.getApplicationContext();
        repo = new Repository(ctx);
        im(() -> {
            AppDatabase.getInstance(ctx).clearAllTables();
            return null;
        });
    }

    @After
    public void tearDown() throws Exception {
        im(() -> {
            AppDatabase.getInstance(ctx).clearAllTables();
            return null;
        });
    }

    private <T> T im(Callable<T> work) throws Exception {
        return repo.executor().submit(work).get(10, TimeUnit.SECONDS);
    }

    private static void idle() {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    private void ruhe(BookingEditActivity a) throws Exception {
        java.lang.reflect.Field f = BookingEditActivity.class.getDeclaredField("repository");
        f.setAccessible(true);
        Repository r = (Repository) f.get(a);
        for (int i = 0; i < 6; i++) {
            r.executor().submit(() -> null).get(10, TimeUnit.SECONDS);
            idle();
        }
    }

    @Test
    public void umbuchungZurAusgabeBehaeltIhrenStandort() throws Exception {
        im(() -> {
            repo.insertTransferNow("Kreditkarte", "PayPal-Konto", 2500L, "PayPal",
                    "Neuer Umsatz\n" + GPS, "", System.currentTimeMillis());
            return null;
        });
        Booking out = null;
        for (Booking b : im(() -> AppDatabase.getInstance(ctx).bookingDao().getAllBookings())) {
            if (!b.isIncome) {
                out = b;
            }
        }
        Intent i = new Intent(ctx, BookingEditActivity.class)
                .putExtra(BookingEditActivity.EXTRA_BOOKING_ID, out.id);
        BookingEditActivity a = Robolectric.buildActivity(BookingEditActivity.class, i).setup().get();
        ruhe(a);
        MaterialButtonToggleGroup type = a.findViewById(R.id.toggleType);
        assertEquals(R.id.btnTransfer, type.getCheckedButtonId());

        type.check(R.id.btnExpense);
        idle();
        a.findViewById(R.id.btnUpdate).performClick();
        ruhe(a);

        List<Booking> alle = im(() -> AppDatabase.getInstance(ctx).bookingDao().getAllBookings());
        assertEquals(1, alle.size());
        Booking neu = alle.get(0);
        assertFalse(neu.isTransfer);
        assertTrue(neu.note, neu.note.contains(GPS));
    }

    @Test
    public void ausgabeZurUmbuchungBehaeltIhrenStandort() throws Exception {
        final Booking b = new Booking();
        b.account = "Kreditkarte";
        b.payee = "PayPal";
        b.amountCents = 2500L;
        b.createdAt = System.currentTimeMillis();
        b.note = "Neuer Umsatz\n" + GPS;
        long id = im(() -> {
            AppDatabase db = AppDatabase.getInstance(ctx);
            db.accountDao().insertIfAbsent(new de.spahr.ausgaben.db.Account("Kreditkarte"));
            db.accountDao().insertIfAbsent(new de.spahr.ausgaben.db.Account("PayPal-Konto"));
            return db.bookingDao().insert(b);
        });
        Intent i = new Intent(ctx, BookingEditActivity.class)
                .putExtra(BookingEditActivity.EXTRA_BOOKING_ID, id);
        BookingEditActivity a = Robolectric.buildActivity(BookingEditActivity.class, i).setup().get();
        ruhe(a);
        MaterialButtonToggleGroup type = a.findViewById(R.id.toggleType);
        type.check(R.id.btnTransfer);
        idle();
        android.widget.AutoCompleteTextView to = a.findViewById(R.id.editAccountTo);
        to.setText("PayPal-Konto", false);
        idle();
        a.findViewById(R.id.btnUpdate).performClick();
        ruhe(a);

        List<Booking> alle = im(() -> AppDatabase.getInstance(ctx).bookingDao().getAllBookings());
        assertEquals(2, alle.size());
        for (Booking neu : alle) {
            assertTrue(neu.isTransfer);
            assertTrue(neu.note, neu.note.contains(GPS));
        }
    }
}
