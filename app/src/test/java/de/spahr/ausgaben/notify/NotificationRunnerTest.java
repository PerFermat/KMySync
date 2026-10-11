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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import de.spahr.ausgaben.db.AppDatabase;
import de.spahr.ausgaben.db.Booking;
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
    private static final long HEUTE = 20000 * TAG + TAG / 2;
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
