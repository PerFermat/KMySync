package de.spahr.ausgaben.notify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import de.spahr.ausgaben.db.AppDatabase;
import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.BookingSplit;
import de.spahr.ausgaben.db.PayeeCorrection;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.statement.NotificationLearner;
import de.spahr.ausgaben.statement.NotificationSource;
import de.spahr.ausgaben.statement.NotificationTemplate;

/**
 * Der Durchlauf gegen Puffer und Datenbank: Duplikate gegen den Bestand, und vor allem die Löschregeln.
 *
 * <p>Die Zusage lautet: nach abgeschlossenem Durchlauf sind die gezeigten Meldungen weg, verarbeitet
 * oder nicht — und <b>nur</b> die gezeigten, und <b>nur</b> nach abgeschlossenem Durchlauf. Jede der
 * beiden Einschränkungen hat hier ihren Test.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NotificationRunnerTest {

    private static final long TAG = 24L * 60 * 60 * 1000;
    /** Die laufende Zeit: der Abschluss räumt auf, und der Verfall misst an der Uhr. */
    private static final long HEUTE = System.currentTimeMillis();
    private static final String ANDERES_PROFIL = "anderes";
    private static final String BANK = "de.bank";

    private Context ctx;
    private AppDatabase db;
    private Repository repo;
    private NotificationBuffer buffer;

    @Before
    public void setUp() throws Exception {
        ctx = ApplicationProvider.getApplicationContext();
        db = AppDatabase.getInstance(ctx);
        repo = new Repository(ctx);
        buffer = NotificationBuffer.get(ctx);
        buffer.clear();
        imHintergrund(() -> {
            db.clearAllTables();
            return null;
        });
        settings().edit().clear().commit();
        new KnownApps(ctx).clear();
        AppSelection.setAllowed(ctx, Arrays.asList(BANK, "de.fremd"));
    }

    private android.content.SharedPreferences settings() {
        return ctx.getSharedPreferences("ausgaben_settings", Context.MODE_PRIVATE);
    }

    @After
    public void tearDown() throws Exception {
        buffer.clear();
        imHintergrund(() -> {
            db.clearAllTables();
            return null;
        });
    }

    private <T> T imHintergrund(Callable<T> arbeit) throws Exception {
        return repo.executor().submit(arbeit).get(10, TimeUnit.SECONDS);
    }

    private static String zahlung(String haendler, String betrag) {
        return "Zahlung bei " + haendler + " über " + betrag + " EUR.";
    }

    private long meldung(long time, String text, String key) {
        return buffer.add(BANK, time, "Kartenzahlung", text, key);
    }

    private void vorlageLernen() throws Exception {
        final NotificationTemplate t = NotificationLearner.learn(new NotificationSource(BANK, 0L,
                "Kartenzahlung", zahlung("REWE Markt", "12,34")), 1234L, false, "Rewe", "Kreditkarte");
        imHintergrund(() -> {
            repo.notificationTemplates().save(t);
            return null;
        });
    }

    private void buchung(long cents, long time, String payee, String category) throws Exception {
        final Booking b = new Booking();
        b.account = "Kreditkarte";
        b.payee = payee;
        b.category = category;
        b.amountCents = cents;
        b.createdAt = time;
        imHintergrund(() -> db.bookingDao().insert(b));
    }

    private ArrayList<NotificationDraft> vorbereiten() throws Exception {
        return imHintergrund(() -> NotificationRunner.prepare(ctx, repo));
    }

    private int abschliessen(final List<NotificationDraft> remaining, final long... shown)
            throws Exception {
        return imHintergrund(() -> NotificationRunner.complete(ctx, repo, remaining, shown));
    }

    private static long[] ids(List<NotificationDraft> drafts) {
        long[] out = new long[drafts.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = drafts.get(i).entryId;
        }
        return out;
    }

    // ---- Vorbereiten ----

    // ---- wie eine Spracheingabe ----

    private void alias(String spoken, String corrected, String expenseCategory) throws Exception {
        final PayeeCorrection a = new PayeeCorrection(spoken, corrected, HEUTE - 40 * TAG);
        a.catExpense1 = expenseCategory;
        imHintergrund(() -> {
            db.payeeCorrectionDao().upsert(a);
            return null;
        });
    }

    /** Die Bank schreibt „REWE SAGT DANKE", der Alias macht „Rewe" daraus – wie beim Diktieren. */
    @Test
    public void aliasLoestDieSchreibweiseDerBankAuf() throws Exception {
        vorlageLernen();
        alias("REWE SAGT DANKE", "Rewe", "Lebensmittel");
        meldung(HEUTE, zahlung("REWE SAGT DANKE", "7,50"), "a");
        NotificationDraft d = vorbereiten().get(0);
        assertTrue(d.viaVoice);
        assertEquals("Rewe", d.payee);
        assertEquals("REWE SAGT DANKE", d.rawPayee);
        assertEquals("Lebensmittel", d.category);
        assertEquals("Kreditkarte", d.account);
    }

    /** Unbekannter Händler: der Wortlaut bleibt Empfänger, eine Kategorie wird nicht erfunden. */
    @Test
    public void unbekannterHaendlerBleibtWieGemeldet() throws Exception {
        vorlageLernen();
        meldung(HEUTE, zahlung("Blumenhaus Sonnenschein", "7,50"), "a");
        NotificationDraft d = vorbereiten().get(0);
        assertEquals("Blumenhaus Sonnenschein", d.payee);
        assertEquals("", d.category);
    }

    /** Ziffern im Händlernamen: der Betrag ist der mit dem Währungszeichen, nicht die Filialnummer. */
    @Test
    public void ziffernImHaendlernamenAendernDenBetragNicht() throws Exception {
        vorlageLernen();
        meldung(HEUTE, zahlung("Shell 4711", "50,00"), "a");
        ArrayList<NotificationDraft> drafts = vorbereiten();
        NotificationDraft d = drafts.get(0);
        assertTrue(d.viaVoice);
        assertEquals(5000L, d.cents);
        assertEquals(1, abschliessen(drafts, ids(drafts)));
        Booking b = imHintergrund(() -> db.bookingDao().getAllBookings()).get(0);
        assertEquals(5000L, b.amountCents);
        assertEquals("Shell 4711", b.payee);
    }

    /** Eine Splitvorlage wird anteilig vorgeschlagen und mit OK auch als Aufteilung gebucht. */
    @Test
    public void splitvorlageWirdAnteiligUebernommen() throws Exception {
        vorlageLernen();
        final Booking alt = new Booking();
        alt.account = "Kreditkarte";
        alt.payee = "Drogerie Müller";
        alt.category = "Haushalt";
        alt.amountCents = 1000L;
        alt.createdAt = HEUTE - 20 * TAG;
        imHintergrund(() -> {
            long id = db.bookingDao().insert(alt);
            db.bookingDao().insertSplit(new BookingSplit(id, "Haushalt", 600L, Boolean.FALSE));
            db.bookingDao().insertSplit(new BookingSplit(id, "Kosmetik", 400L, Boolean.FALSE));
            return null;
        });
        meldung(HEUTE, zahlung("Drogerie Müller", "20,00"), "a");
        ArrayList<NotificationDraft> drafts = vorbereiten();
        NotificationDraft d = drafts.get(0);
        assertTrue(d.isSplit());
        assertEquals(Arrays.asList("Haushalt", "Kosmetik"), d.splitCategories);
        assertEquals(1200L, d.splitCents[0]);
        assertEquals(800L, d.splitCents[1]);

        assertEquals(1, abschliessen(drafts, ids(drafts)));
        List<Booking> alle = imHintergrund(() -> db.bookingDao().getAllBookings());
        final Booking neu = alle.get(0).createdAt == HEUTE ? alle.get(0) : alle.get(1);
        assertEquals(2000L, neu.amountCents);
        List<BookingSplit> teile = imHintergrund(() -> db.bookingDao().getSplits(neu.id));
        assertEquals(2, teile.size());
        assertEquals(1200L, teile.get(0).amountCents);
        assertEquals(800L, teile.get(1).amountCents);
    }

    /** Vorschau und Ergebnis kommen aus demselben Aufruf und können nicht auseinanderlaufen. */
    @Test
    public void gebuchtWirdWasVorgeschlagenWurde() throws Exception {
        vorlageLernen();
        alias("REWE SAGT DANKE", "Rewe", "Lebensmittel");
        meldung(HEUTE, zahlung("REWE SAGT DANKE", "7,50"), "a");
        ArrayList<NotificationDraft> drafts = vorbereiten();
        NotificationDraft d = drafts.get(0);
        abschliessen(drafts, ids(drafts));
        Booking b = imHintergrund(() -> db.bookingDao().getAllBookings()).get(0);
        assertEquals(d.payee, b.payee);
        assertEquals(d.category, b.category);
        assertEquals(d.cents, b.amountCents);
        assertEquals(d.account, b.account);
        // Mit dem Datum der Meldung, nicht dem Augenblick des Buchens.
        assertEquals(HEUTE, b.createdAt);
        // Und mit dem Wortlaut der Bank, falls der Empfänger später berichtigt wird.
        assertEquals("REWE SAGT DANKE", b.spokenText);
    }

    /** Nur vorschlagen heißt: nichts ist angelegt, auch kein Empfänger in der Vorschlagsliste. */
    @Test
    public void vorschlagenLegtNichtsAn() throws Exception {
        vorlageLernen();
        meldung(HEUTE, zahlung("Blumenhaus Sonnenschein", "7,50"), "a");
        vorbereiten();
        assertTrue(imHintergrund(() -> db.bookingDao().getAllBookings()).isEmpty());
    }

    /** Mit dem Export hat der erkannte Text ausgedient. */
    @Test
    public void exportLeertDenErkanntenText() throws Exception {
        vorlageLernen();
        meldung(HEUTE, zahlung("Lidl", "7,50"), "a");
        ArrayList<NotificationDraft> drafts = vorbereiten();
        abschliessen(drafts, ids(drafts));
        final Booking b = imHintergrund(() -> db.bookingDao().getAllBookings()).get(0);
        assertEquals("Lidl", b.spokenText);
        imHintergrund(() -> {
            db.bookingDao().markExported(Collections.singletonList(b.id));
            return null;
        });
        assertEquals("", imHintergrund(() -> db.bookingDao().getAllBookings()).get(0).spokenText);
    }

    // ---- Wortlaut in der Notiz ----

    /** Die Notiz ist der Wortlaut der Meldung – nicht die der alten Buchung, die als Vorlage diente. */
    @Test
    public void notizIstDerWortlautNichtDieDerVorlage() throws Exception {
        vorlageLernen();
        final Booking alt = new Booking();
        alt.account = "Kreditkarte";
        alt.payee = "Lidl";
        alt.category = "Lebensmittel";
        alt.note = "Wocheneinkauf mit Pfand";
        alt.amountCents = 999L;
        alt.createdAt = HEUTE - 30 * TAG;
        imHintergrund(() -> db.bookingDao().insert(alt));
        meldung(HEUTE, zahlung("Lidl", "7,50"), "a");
        ArrayList<NotificationDraft> drafts = vorbereiten();
        String wortlaut = "Kartenzahlung\n" + zahlung("Lidl", "7,50");
        // Schon die Vorschau zeigt ihn …
        assertEquals(wortlaut, drafts.get(0).note);
        assertEquals("Lebensmittel", drafts.get(0).category);
        abschliessen(drafts, ids(drafts));
        // … und gebucht wird dasselbe.
        List<Booking> alle = imHintergrund(() -> db.bookingDao().getAllBookings());
        Booking neu = alle.get(0).createdAt == HEUTE ? alle.get(0) : alle.get(1);
        assertEquals(wortlaut, neu.note);
    }

    /** Mit Standort steht der GPS-Zusatz hinter dem Wortlaut, in einer eigenen Zeile. */
    @Test
    public void standortHaengtHinterDemWortlaut() throws Exception {
        new de.spahr.ausgaben.settings.SettingsStore(ctx).setGpsEnabled(true);
        vorlageLernen();
        long id = meldung(HEUTE, zahlung("Lidl", "7,50"), "a");
        buffer.setCoords(id, "50.123456, 8.654321");
        ArrayList<NotificationDraft> drafts = vorbereiten();
        abschliessen(drafts, ids(drafts));
        Booking b = imHintergrund(() -> db.bookingDao().getAllBookings()).get(0);
        assertEquals("Kartenzahlung\n" + zahlung("Lidl", "7,50") + "\nGPS: 50.123456, 8.654321",
                b.note);
    }

    /** Eine mehrzeilige Meldung wird mehrzeilig gebucht; der Standort hängt hinter der letzten Zeile. */
    @Test
    public void mehrzeiligerWortlautWirdMitUmbruechenGebucht() throws Exception {
        new de.spahr.ausgaben.settings.SettingsStore(ctx).setGpsEnabled(true);
        vorlageLernen();
        long id = meldung(HEUTE, zahlung("Lidl", "7,50") + "\nKarte *1234", "a");
        buffer.setCoords(id, "50.123456, 8.654321");
        ArrayList<NotificationDraft> drafts = vorbereiten();
        assertTrue(drafts.get(0).recognized);
        abschliessen(drafts, ids(drafts));
        Booking b = imHintergrund(() -> db.bookingDao().getAllBookings()).get(0);
        assertEquals("Kartenzahlung\n" + zahlung("Lidl", "7,50")
                + "\nKarte *1234\nGPS: 50.123456, 8.654321", b.note);
    }

    /** Auch eine nicht erkannte Zeile bringt ihren Wortlaut mit – für die Maske hinter dem Stift. */
    @Test
    public void nichtErkannteZeileTraegtIhrenWortlaut() throws Exception {
        vorlageLernen();
        meldung(HEUTE, "Gutschrift von 20,00 EUR von Amazon", "a");
        assertEquals("Kartenzahlung\nGutschrift von 20,00 EUR von Amazon",
                vorbereiten().get(0).note);
    }

    // ---- Standort beim Eintreffen ----

    /** Mit GPS-Schalter: der Standort der Meldung kommt wie beim Diktieren in die Notiz der Buchung. */
    @Test
    public void standortDerMeldungKommtInDieBuchung() throws Exception {
        new de.spahr.ausgaben.settings.SettingsStore(ctx).setGpsEnabled(true);
        vorlageLernen();
        long id = meldung(HEUTE, zahlung("Lidl", "7,50"), "a");
        buffer.setCoords(id, "50.123456, 8.654321");
        ArrayList<NotificationDraft> drafts = vorbereiten();
        assertEquals("50.123456, 8.654321", drafts.get(0).coords);
        abschliessen(drafts, ids(drafts));
        Booking b = imHintergrund(() -> db.bookingDao().getAllBookings()).get(0);
        assertTrue(b.note, b.note.contains("50.123456"));
    }

    private void standortJetzt(double lat, double lon) {
        org.robolectric.Shadows.shadowOf((android.app.Application) ctx)
                .grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION);
        android.location.LocationManager lm =
                (android.location.LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        android.location.Location l =
                new android.location.Location(android.location.LocationManager.GPS_PROVIDER);
        l.setLatitude(lat);
        l.setLongitude(lon);
        l.setTime(System.currentTimeMillis());
        org.robolectric.Shadows.shadowOf(lm).setLastKnownLocation(
                android.location.LocationManager.GPS_PROVIDER, l);
    }

    /** Ohne Standort vom Eintreffen gilt der vom Import – als wäre der Satz jetzt diktiert worden. */
    @Test
    public void ohneStandortDerMeldungGiltDerVomImport() throws Exception {
        new de.spahr.ausgaben.settings.SettingsStore(ctx).setGpsEnabled(true);
        standortJetzt(48.111111, 11.222222);
        vorlageLernen();
        meldung(HEUTE, zahlung("Lidl", "7,50"), "a");
        assertEquals("48.111111, 11.222222", vorbereiten().get(0).coords);
    }

    /** Der Standort vom Eintreffen geht vor: er ist der, an dem bezahlt wurde. */
    @Test
    public void standortDerMeldungGehtVorDemVomImport() throws Exception {
        new de.spahr.ausgaben.settings.SettingsStore(ctx).setGpsEnabled(true);
        standortJetzt(48.111111, 11.222222);
        vorlageLernen();
        long id = meldung(HEUTE, zahlung("Lidl", "7,50"), "a");
        buffer.setCoords(id, "50.123456, 8.654321");
        assertEquals("50.123456, 8.654321", vorbereiten().get(0).coords);
    }

    /** GPS ausgeschaltet: auch der Standort vom Import bleibt außen vor. */
    @Test
    public void ohneGpsSchalterAuchKeinStandortVomImport() throws Exception {
        new de.spahr.ausgaben.settings.SettingsStore(ctx).setGpsEnabled(false);
        standortJetzt(48.111111, 11.222222);
        vorlageLernen();
        meldung(HEUTE, zahlung("Lidl", "7,50"), "a");
        assertEquals("", vorbereiten().get(0).coords);
    }

    /** GPS ganz ausgeschaltet: dann wird auch ein festgehaltener Standort nicht verwendet. */
    @Test
    public void ohneGpsSchalterKeinStandort() throws Exception {
        new de.spahr.ausgaben.settings.SettingsStore(ctx).setGpsEnabled(false);
        vorlageLernen();
        long id = meldung(HEUTE, zahlung("Lidl", "7,50"), "a");
        buffer.setCoords(id, "50.123456, 8.654321");
        ArrayList<NotificationDraft> drafts = vorbereiten();
        abschliessen(drafts, ids(drafts));
        Booking b = imHintergrund(() -> db.bookingDao().getAllBookings()).get(0);
        assertFalse(b.note, b.note.contains("50.123456"));
    }

    /** Der bekannte Empfänger ersetzt den Wortlaut der Meldung und bringt seine übliche Kategorie mit. */
    @Test
    public void bekannterEmpfaengerBringtSeineKategorieMit() throws Exception {
        vorlageLernen();
        buchung(999L, HEUTE - 30 * TAG, "Lidl", "Lebensmittel");
        meldung(HEUTE, zahlung("LIDL Markt GmbH", "7,50"), "a");
        NotificationDraft d = vorbereiten().get(0);
        assertEquals("Lidl", d.payee);
        assertEquals("Lebensmittel", d.category);
        assertFalse(d.dupBooked);
    }

    @Test
    public void ohneVorlageGibtEsNichtsZuZeigen() throws Exception {
        meldung(HEUTE, zahlung("Lidl", "7,50"), "a");
        assertTrue(vorbereiten().isEmpty());
        assertEquals(1, buffer.all().size());
    }

    // ---- Duplikate gegen den Bestand ----

    @Test
    public void gleicherBetragZweiTageDanebenIstEinMoeglichesDuplikat() throws Exception {
        vorlageLernen();
        buchung(750L, HEUTE - 2 * TAG, "Lidl", "");
        meldung(HEUTE, zahlung("Lidl", "7,50"), "a");
        assertTrue(vorbereiten().get(0).dupBooked);
    }

    @Test
    public void dreiTageDanebenNichtMehr() throws Exception {
        vorlageLernen();
        buchung(750L, HEUTE - 3 * TAG, "Lidl", "");
        meldung(HEUTE, zahlung("Lidl", "7,50"), "a");
        assertFalse(vorbereiten().get(0).dupBooked);
    }

    @Test
    public void andererBetragIstKeinDuplikat() throws Exception {
        vorlageLernen();
        buchung(751L, HEUTE, "Lidl", "");
        meldung(HEUTE, zahlung("Lidl", "7,50"), "a");
        assertFalse(vorbereiten().get(0).dupBooked);
    }

    // ---- Abschließen ----

    @Test
    public void okBuchtAufDasKontoDerVorlageUndLeertDenPuffer() throws Exception {
        vorlageLernen();
        meldung(HEUTE, zahlung("Lidl", "7,50"), "a");
        ArrayList<NotificationDraft> drafts = vorbereiten();
        assertEquals(1, abschliessen(drafts, ids(drafts)));
        List<Booking> booked = imHintergrund(() -> db.bookingDao().getAllBookings());
        assertEquals(1, booked.size());
        assertEquals(750L, booked.get(0).amountCents);
        assertFalse(booked.get(0).isIncome);
        assertEquals("Lidl", booked.get(0).payee);
        assertEquals(HEUTE, booked.get(0).createdAt);
        assertFalse(booked.get(0).exported);
        assertTrue(buffer.all().isEmpty());
    }

    /** Nicht erkannt heißt: nicht gebucht — aber gezeigt, und deshalb danach weg. */
    @Test
    public void nichtErkannteWerdenNichtGebuchtAberGeloescht() throws Exception {
        vorlageLernen();
        meldung(HEUTE, "Gutschrift von 20,00 EUR von Amazon", "a");
        ArrayList<NotificationDraft> drafts = vorbereiten();
        assertEquals(0, abschliessen(drafts, ids(drafts)));
        assertTrue(imHintergrund(() -> db.bookingDao().getAllBookings()).isEmpty());
        assertTrue(buffer.all().isEmpty());
    }

    /** Aus der Liste entfernt: nicht gebucht, aber gezeigt — also ebenfalls weg. */
    @Test
    public void entfernteZeilenWerdenNichtGebuchtAberGeloescht() throws Exception {
        vorlageLernen();
        meldung(HEUTE, zahlung("Lidl", "7,50"), "a");
        meldung(HEUTE, zahlung("Aldi", "9,00"), "b");
        ArrayList<NotificationDraft> drafts = vorbereiten();
        long[] gezeigt = ids(drafts);
        drafts.remove(1);
        assertEquals(1, abschliessen(drafts, gezeigt));
        assertEquals(1, imHintergrund(() -> db.bookingDao().getAllBookings()).size());
        assertTrue(buffer.all().isEmpty());
    }

    /** Was eintrifft, während die Liste offen ist, hat der Nutzer nicht gesehen und bleibt. */
    @Test
    public void waehrendDerListeEingetroffenesBleibt() throws Exception {
        vorlageLernen();
        meldung(HEUTE, zahlung("Lidl", "7,50"), "a");
        ArrayList<NotificationDraft> drafts = vorbereiten();
        long spaeter = meldung(HEUTE + 1000, zahlung("Aldi", "9,00"), "b");
        abschliessen(drafts, ids(drafts));
        List<NotificationBuffer.Entry> rest = buffer.all();
        assertEquals(1, rest.size());
        assertEquals(spaeter, rest.get(0).id);
    }

    /**
     * Die gemeinsame Karte: ein zweites Profil hat dieselbe App freigegeben und Regeln dafür. Der
     * Abschluss hier erledigt die Meldung nur für dieses Profil — gelöscht ist sie damit nicht.
     */
    @Test
    public void abschlussLaesstDieMeldungDemAnderenProfil() throws Exception {
        vorlageLernen();
        settings().edit().putString("p_" + ANDERES_PROFIL + "_notify_apps", BANK).commit();
        new KnownApps(ctx).put(ANDERES_PROFIL, java.util.Collections.singleton(BANK));
        long id = meldung(HEUTE, zahlung("Lidl", "7,50"), "a");
        ArrayList<NotificationDraft> drafts = vorbereiten();
        assertEquals(1, abschliessen(drafts, ids(drafts)));
        // Für dieses Profil erledigt: die Liste kommt nicht wieder.
        assertTrue(vorbereiten().isEmpty());
        // Für das andere noch da.
        assertEquals(1, buffer.allFor(ANDERES_PROFIL).size());
        // Erst wenn auch das andere fertig ist, geht sie.
        buffer.markDone(Collections.singletonList(id), ANDERES_PROFIL);
        NotificationBuffer.purgeNow(ctx);
        assertTrue(buffer.all().isEmpty());
    }

    /** Hat dieses Profil die App nicht freigegeben, zeigt es ihre Meldungen nicht — trotz Regel. */
    @Test
    public void nichtFreigegebeneAppErscheintNicht() throws Exception {
        vorlageLernen();
        settings().edit().putString("p_" + ANDERES_PROFIL + "_notify_apps", BANK).commit();
        AppSelection.setAllowed(ctx, Collections.<String>emptySet());
        meldung(HEUTE, zahlung("Lidl", "7,50"), "a");
        assertTrue(vorbereiten().isEmpty());
        assertEquals(1, buffer.all().size());
    }

    /** Meldungen von Apps ohne Vorlage waren nie in der Liste und bleiben ebenfalls. */
    @Test
    public void meldungenFremderAppsBleiben() throws Exception {
        vorlageLernen();
        meldung(HEUTE, zahlung("Lidl", "7,50"), "a");
        buffer.add("de.fremd", HEUTE, "Titel", "Zahlung über 3,00 EUR", "x");
        ArrayList<NotificationDraft> drafts = vorbereiten();
        abschliessen(drafts, ids(drafts));
        assertEquals(1, buffer.all().size());
        assertEquals("de.fremd", buffer.all().get(0).packageName);
    }

    /** Abbruch heißt: der Abschluss läuft nicht. Dann ist nichts gebucht und nichts gelöscht. */
    @Test
    public void ohneAbschlussBleibtAllesWieEsWar() throws Exception {
        vorlageLernen();
        meldung(HEUTE, zahlung("Lidl", "7,50"), "a");
        vorbereiten();
        assertEquals(1, buffer.all().size());
        assertTrue(imHintergrund(() -> db.bookingDao().getAllBookings()).isEmpty());
        // Beim nächsten Aktualisieren steht dieselbe Zeile wieder da.
        assertEquals(1, vorbereiten().size());
    }

    /** Erkannt, aber ohne den Blick des Nutzers nicht buchbar: bleibt ungebucht. */
    @Test
    public void nichtBuchbareZeilenWerdenNichtGebucht() throws Exception {
        NotificationDraft d = new NotificationDraft();
        d.entryId = meldung(HEUTE, zahlung("Lidl", "7,50"), "a");
        d.recognized = true;
        d.cents = 750L;
        d.account = "";
        assertEquals(0, abschliessen(Collections.singletonList(d), d.entryId));
    }

    /** „Daraus lernen": eine nicht erkannte Meldung, von Hand gebucht, ergibt die nächste Vorlage. */
    @Test
    public void vonHandGebuchteMeldungWirdGelernt() throws Exception {
        vorlageLernen();
        meldung(HEUTE, "Gutschrift von Amazon in Höhe von 20,00 EUR.", "a");
        ArrayList<NotificationDraft> drafts = vorbereiten();
        NotificationDraft d = drafts.get(0);
        assertFalse(d.recognized);
        // Was die Maske zurückgibt:
        d.cents = 2000L;
        d.isIncome = true;
        d.payee = "Amazon";
        d.account = "Kreditkarte";
        d.edited = true;
        d.learn = true;
        assertEquals(1, abschliessen(drafts, ids(drafts)));
        assertEquals(2, imHintergrund(() -> repo.notificationTemplates().all()).size());

        // Die nächste Gutschrift wird erkannt, als Einnahme.
        meldung(HEUTE + TAG, "Gutschrift von Zalando in Höhe von 35,00 EUR.", "b");
        NotificationDraft next = vorbereiten().get(0);
        assertTrue(next.recognized);
        assertTrue(next.isIncome);
        assertEquals(3500L, next.cents);
        assertEquals("Zalando", next.payee);
    }
}
