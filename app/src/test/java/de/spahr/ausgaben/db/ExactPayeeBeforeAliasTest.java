package de.spahr.ausgaben.db;

import static org.junit.Assert.assertEquals;

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
 * Ein Empfänger, den es unter genau diesem Namen schon gibt, geht einem bevorzugten Alias vor, der nur
 * unscharf passt – auf allen Eingabewegen, denn sie teilen sich diese eine Auflösung.
 *
 * <p>Regression: Die Meldung „Bäckerei Butterblume" wurde über den bevorzugten Alias „bäcker" zur
 * „Bäckerei Krustengold", obwohl es Buchungen bei der Bäckerei Butterblume gab.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ExactPayeeBeforeAliasTest {

    private static final String KONTO = "Girokonto";
    /** Standort der Bäckerei Butterblume. */
    private static final String BUTTERBLUME = "48.000000, 7.820000";
    /** Rund 11 km weiter. */
    private static final String WEIT_WEG = "48.100000, 7.820000";

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

    private void buchung(String payee, String category, String note, long daysAgo) throws Exception {
        Booking b = new Booking();
        b.account = KONTO;
        b.payee = payee;
        b.category = category;
        b.amountCents = 500;
        b.note = note;
        b.createdAt = System.currentTimeMillis() - daysAgo * 24L * 3600 * 1000;
        imHintergrund(() -> db.bookingDao().insert(b));
    }

    private void bevorzugterAlias(String spoken, String corrected) throws Exception {
        PayeeCorrection a = new PayeeCorrection(spoken, corrected, System.currentTimeMillis());
        a.preferred = true;
        a.catExpense1 = "Lebensmittel";
        imHintergrund(() -> {
            db.payeeCorrectionDao().upsert(a);
            return null;
        });
    }

    /** Wie die Spracheingabe von Uhr/Widget und die Benachrichtigungen: nur vorschlagen. */
    private String empfaenger(String satz, String coords) throws Exception {
        Booking b = imHintergrund(() -> repo.voiceBookingBlocking(satz, KONTO, false, coords,
                System.currentTimeMillis(), false, null));
        return b.payee;
    }

    @Test
    public void exakterEmpfaengerSchlaegtUnscharfenBevorzugtenAlias() throws Exception {
        buchung("Bäckerei Butterblume", "Lebensmittel", "", 3);
        bevorzugterAlias("bäcker", "Bäckerei Krustengold");
        assertEquals("Bäckerei Butterblume", empfaenger("Bäckerei Butterblume 6,85 €", null));
        // Groß/klein spielt keine Rolle.
        assertEquals("Bäckerei Butterblume", empfaenger("BÄCKEREI BUTTERBLUME 6,85 €", null));
    }

    @Test
    public void mitStandortInDerNaeheGiltDerExakteTreffer() throws Exception {
        buchung("Bäckerei Butterblume", "Lebensmittel", "GPS: " + BUTTERBLUME, 3);
        bevorzugterAlias("bäcker", "Bäckerei Krustengold");
        assertEquals("Bäckerei Butterblume", empfaenger("Bäckerei Butterblume 6,85 €", BUTTERBLUME));
    }

    @Test
    public void weitWegEntscheidetWieBisherDerBevorzugteAlias() throws Exception {
        buchung("Bäckerei Butterblume", "Lebensmittel", "GPS: " + BUTTERBLUME, 3);
        bevorzugterAlias("bäcker", "Bäckerei Krustengold");
        // Die Buchungen dieses Namens liegen alle weit weg: kein exakter Treffer an diesem Ort.
        assertEquals("Bäckerei Krustengold", empfaenger("Bäckerei Butterblume 6,85 €", WEIT_WEG));
    }

    @Test
    public void ohneStandortBeiDenBuchungenZaehltDerName() throws Exception {
        buchung("Bäckerei Butterblume", "Lebensmittel", "", 3);
        bevorzugterAlias("bäcker", "Bäckerei Krustengold");
        assertEquals("Bäckerei Butterblume", empfaenger("Bäckerei Butterblume 6,85 €", WEIT_WEG));
    }

    @Test
    public void wortgleicherBevorzugterAliasBleibtVorn() throws Exception {
        // Der Nutzer hat genau diesen Begriff bewusst umgeleitet.
        buchung("Mama", "Geschenke", "", 3);
        bevorzugterAlias("mama", "Erika Mustermann");
        assertEquals("Erika Mustermann", empfaenger("Mama 20 €", null));
    }

    @Test
    public void ohneExaktenTrefferBleibtDerBevorzugteAlias() throws Exception {
        buchung("Bäckerei Butterblume", "Lebensmittel", "", 3);
        bevorzugterAlias("bäcker", "Bäckerei Krustengold");
        assertEquals("Bäckerei Krustengold", empfaenger("Bäcker 3,20 €", null));
    }

    @Test
    public void spracheingabeAmHandyNutztDieselbeRegel() throws Exception {
        buchung("Bäckerei Butterblume", "Lebensmittel", "", 3);
        bevorzugterAlias("bäcker", "Bäckerei Krustengold");
        final Repository.VoiceResolution[] res = new Repository.VoiceResolution[1];
        repo.resolveVoice("Bäckerei Butterblume", null, r -> res[0] = r);
        for (int i = 0; i < 50 && res[0] == null; i++) {
            Thread.sleep(50);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        }
        assertEquals("Bäckerei Butterblume", res[0].booking.payee);
        assertEquals(null, res[0].alias);
    }
}
