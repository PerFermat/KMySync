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

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Eine Kategorie wird nie ohne ihre Seite weitergereicht. Wo die App eine Kategorie aus einer früheren
 * Buchung, einem Alias oder der Kassensturz-Vorgabe in eine neue Buchung übernimmt, kommt mit, ob sie
 * dort Einnahme- oder Ausgabekategorie war ({@code category_is_income}) – statt sie aus der Richtung
 * des Geldes zu erraten.
 *
 * <p>Der Prüfstein ist durchweg die <b>Erstattung</b>: Geld kommt herein, die Kategorie bleibt eine
 * Ausgabekategorie. Geraten käme hier „Einnahmekategorie" heraus.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CategorySideCarriedTest {

    private static final String KONTO = "Girokonto";

    private AppDatabase db;
    private Repository repo;
    private SettingsStore settings;

    @Before
    public void setUp() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        settings = new SettingsStore(ctx);
        settings.setGpsEnabled(false);
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
        settings.setReconcileTarget("", "", null);
    }

    private <T> T imHintergrund(Callable<T> arbeit) throws Exception {
        return repo.executor().submit(arbeit).get(10, TimeUnit.SECONDS);
    }

    /** Eine Erstattung: Einnahme auf die Ausgabekategorie „Kleidung". */
    private long erstattung(String payee) throws Exception {
        Booking b = new Booking();
        b.account = KONTO;
        b.payee = payee;
        b.isIncome = true;
        b.amountCents = 4990L;
        b.category = "Kleidung";
        b.categoryIsIncome = false;
        b.createdAt = 1_780_000_000_000L;
        b.exported = true;
        return imHintergrund(() -> db.bookingDao().insert(b));
    }

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

    // ---- Empfänger-Historie ----

    @Test
    public void historieLiefertDieSeiteDerAltenBuchung() throws Exception {
        erstattung("Modehaus");
        List<PayeeCategory> cats = imHintergrund(
                () -> db.bookingDao().getCategoriesByPayee("modehaus", true));
        assertEquals(1, cats.size());
        assertEquals("Kleidung", cats.get(0).category);
        assertEquals("Ausgabekategorie, obwohl das Geld hereinkam", Boolean.FALSE, cats.get(0).isIncome);
    }

    /** Bei einer Splitbuchung zählt die Seite jeder Teilzeile, nicht die der Kopfzeile. */
    @Test
    public void historieLiefertDieSeiteJederTeilzeile() throws Exception {
        Booking b = new Booking();
        b.account = KONTO;
        b.payee = "Arbeitgeber";
        b.isIncome = true;
        b.amountCents = 300000L;
        b.createdAt = 1_780_000_000_000L;
        long id = imHintergrund(() -> db.bookingDao().insert(b));
        imHintergrund(() -> {
            db.bookingDao().insertSplit(new BookingSplit(id, "Gehalt", 320000L, true));
            db.bookingDao().insertSplit(new BookingSplit(id, "Kantine", -20000L, false));
            return null;
        });
        List<PayeeCategory> cats = imHintergrund(
                () -> db.bookingDao().getCategoriesByPayee("Arbeitgeber", true));
        assertEquals(2, cats.size());
        for (PayeeCategory c : cats) {
            assertEquals(c.category, "Gehalt".equals(c.category), c.isIncome);
        }
    }

    // ---- Spracheingabe, Uhr, Widget ----

    @Test
    public void spracheingabeUebernimmtDieSeiteDerVorlage() throws Exception {
        erstattung("Modehaus");
        assertTrue(imHintergrund(() -> repo.createVoiceBookingBlocking(
                "Modehaus 19,90", KONTO, "", Repository.VOICE_TYPE_INCOME, "")));
        Booking neu = letzte();
        assertEquals(1990L, neu.amountCents);
        assertEquals("Kleidung", neu.category);
        assertEquals(Boolean.FALSE, neu.categoryIsIncome);
    }

    private void alias(Boolean seite) throws Exception {
        PayeeCorrection a = new PayeeCorrection();
        a.spoken = "modehaus";
        a.corrected = "Modehaus";
        a.catIncome1 = "Kleidung";
        a.catIncome1IsIncome = seite;
        a.createdAt = 1L;
        imHintergrund(() -> {
            db.payeeCorrectionDao().upsert(a);
            return null;
        });
    }

    @Test
    public void spracheingabeUebernimmtDieSeiteDesAlias() throws Exception {
        alias(Boolean.FALSE);
        assertTrue(imHintergrund(() -> repo.createVoiceBookingBlocking(
                "Modehaus 19,90", KONTO, "", Repository.VOICE_TYPE_INCOME, "")));
        Booking neu = letzte();
        assertEquals("Kleidung", neu.category);
        assertEquals(Boolean.FALSE, neu.categoryIsIncome);
    }

    /** Ein Alias aus der Zeit vor dem Feld: einmal nachschlagen, und wo nichts steht, nichts behaupten. */
    @Test
    public void alterAliasOhneSeiteWirdNachgeschlagen() throws Exception {
        alias(null);
        assertTrue(imHintergrund(() -> repo.createVoiceBookingBlocking(
                "Modehaus 19,90", KONTO, "", Repository.VOICE_TYPE_INCOME, "")));
        assertNull("ohne Eintrag bleibt die Seite unbekannt", letzte().categoryIsIncome);

        imHintergrund(() -> {
            // Die eben angelegte Buchung weg: sonst diente sie beim zweiten Mal als Vorlage, und es
            // ginge nicht mehr um den Alias.
            db.bookingDao().deleteAll();
            db.categoryTypeDao().upsert(new CategoryType("Kleidung", false));
            return null;
        });
        assertTrue(imHintergrund(() -> repo.createVoiceBookingBlocking(
                "Modehaus 29,90", KONTO, "", Repository.VOICE_TYPE_INCOME, "")));
        assertEquals(Boolean.FALSE, letzte().categoryIsIncome);
    }

    // ---- Kassensturz ----

    /** Der Ausgleich geht in beide Richtungen auf dieselbe Kategorie – ihre Seite bleibt, wie gewählt. */
    @Test
    public void kassensturzBuchungTraegtDieSeiteDerVorgabe() throws Exception {
        repo.saveReconcile("Bargeld", "", 1500L, true, "Kassensturz", "Sonstiges", Boolean.FALSE, null);
        imHintergrund(() -> null);
        Booking mehr = letzte();
        assertTrue("es ist mehr Geld da: Einnahme", mehr.isIncome);
        assertEquals("Sonstiges", mehr.category);
        assertEquals(Boolean.FALSE, mehr.categoryIsIncome);

        repo.saveReconcile("Bargeld", "", 1000L, true, "Kassensturz", "Sonstiges", Boolean.FALSE, null);
        imHintergrund(() -> null);
        Booking weniger = letzte();
        assertTrue(!weniger.isIncome);
        assertEquals(Boolean.FALSE, weniger.categoryIsIncome);
    }

    @Test
    public void kassensturzVorgabeMerktSichDieSeite() {
        assertNull(settings.getReconcileCategoryIsIncome());
        settings.setReconcileTarget("Kassensturz", "Sonstiges", Boolean.FALSE);
        assertEquals(Boolean.FALSE, settings.getReconcileCategoryIsIncome());
        settings.setReconcileTarget("Kassensturz", "Zinsen", Boolean.TRUE);
        assertEquals(Boolean.TRUE, settings.getReconcileCategoryIsIncome());
        assertEquals("Zinsen", settings.getReconcileCategory());
        settings.setReconcileTarget("Kassensturz", "Irgendwas", null);
        assertNull(settings.getReconcileCategoryIsIncome());
    }
}
