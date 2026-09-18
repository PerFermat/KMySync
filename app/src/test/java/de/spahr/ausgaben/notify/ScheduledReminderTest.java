package de.spahr.ausgaben.notify;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Calendar;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import de.spahr.ausgaben.db.AppDatabase;
import de.spahr.ausgaben.db.ScheduledTransaction;

/**
 * Das Tagesfenster der Planungs-Erinnerung.
 *
 * <p>{@code notify/} hatte bis hierher keinen Test. Die Erinnerung ist aber genau die Art Code, die
 * unbemerkt falsch läuft: Sie meldet sich einmal am Tag, und ob sie zu oft oder gar nicht kommt,
 * merkt der Nutzer erst nach Wochen — und dann als „die App erinnert mich ständig" oder „die App hat
 * mich nie erinnert".</p>
 *
 * <p>Geprüft wird hier <b>nur das Fenster</b> von Tagesbeginn bis Tagesende. Die Auffaltung der
 * Wiederholungen selbst hat mit {@code ScheduleProjectionTest} einen eigenen Test; sie hier noch
 * einmal durchzugehen brächte nichts als doppelte Pflege.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ScheduledReminderTest {

    /** Wiederholungskennung „täglich" aus dem KMyMoney-Vorrat (siehe {@code ScheduleProjection}). */
    private static final int TAEGLICH = 2;
    /** „Einmalig" – kein Schrittmaß, also höchstens ein Termin. */
    private static final int EINMALIG = 0;

    private Context ctx;
    private ExecutorService executor;
    private ScheduledReminderReceiver receiver;

    @Before
    public void setUp() throws Exception {
        ctx = ApplicationProvider.getApplicationContext();
        executor = Executors.newSingleThreadExecutor();
        receiver = new ScheduledReminderReceiver();
        imHintergrund(() -> {
            AppDatabase.getInstance(ctx).scheduledTransactionDao().deleteAll();
            return null;
        });
    }

    @After
    public void tearDown() {
        executor.shutdownNow();
    }

    /** Room verbietet Datenbankzugriff auf dem Bedienfaden; im Betrieb läuft das Zählen im Hintergrund. */
    private <T> T imHintergrund(Callable<T> arbeit) throws Exception {
        return executor.submit(arbeit).get(10, TimeUnit.SECONDS);
    }

    /** Ein Zeitpunkt heute, mitten am Tag – unabhängig davon, wann der Test läuft. */
    private static long heuteMittag() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 12);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static long tageVersetzt(int tage) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(heuteMittag());
        c.add(Calendar.DAY_OF_MONTH, tage);
        return c.getTimeInMillis();
    }

    private void planung(String name, long faelligMs, int occurrence, long endeMs) throws Exception {
        ScheduledTransaction st = new ScheduledTransaction();
        st.name = name;
        st.nextDueMs = faelligMs;
        st.occurrence = occurrence;
        st.occurrenceMultiplier = 1;
        st.endMs = endeMs;
        imHintergrund(() -> AppDatabase.getInstance(ctx).scheduledTransactionDao().insert(st));
    }

    private int faelligHeute() throws Exception {
        return imHintergrund(() -> receiver.countDueToday(ctx));
    }

    @Test
    public void heuteFaelligZaehltMit() throws Exception {
        planung("Miete", heuteMittag(), EINMALIG, 0);

        assertEquals(1, faelligHeute());
    }

    /**
     * Der wichtigste Fall: Eine <b>tägliche</b> Planung darf genau einmal zählen, nicht so oft, wie
     * die Projektion Termine liefern könnte. Wäre das Fenster zu weit, meldete die App jeden Morgen
     * eine zweistellige Zahl fälliger Buchungen.
     */
    @Test
    public void taeglicheZaehltNurEinmalProTag() throws Exception {
        planung("Zeitung", heuteMittag(), TAEGLICH, 0);

        assertEquals(1, faelligHeute());
    }

    @Test
    public void morgenFaelligZaehltHeuteNichtMit() throws Exception {
        planung("Versicherung", tageVersetzt(1), EINMALIG, 0);

        assertEquals(0, faelligHeute());
    }

    @Test
    public void gesternFaelligZaehltNichtMehr() throws Exception {
        planung("Abo", tageVersetzt(-1), EINMALIG, 0);

        assertEquals(0, faelligHeute());
    }

    /**
     * Eine beendete Planung schweigt. {@code endMs} begrenzt das Fenster nach oben – lief die
     * Wiederholung gestern aus, darf heute nichts mehr kommen, auch wenn die Reihe rechnerisch
     * weiterliefe.
     */
    @Test
    public void gesternBeendeteReiheSchweigtHeute() throws Exception {
        planung("Ratenzahlung", tageVersetzt(-30), TAEGLICH, tageVersetzt(-1));

        assertEquals(0, faelligHeute());
    }

    /** Ohne Fälligkeitsdatum (in der .kmy nicht gesetzt) gibt es nichts zu melden. */
    @Test
    public void ohneFaelligkeitZaehltNichts() throws Exception {
        planung("Unvollständig", 0, TAEGLICH, 0);

        assertEquals(0, faelligHeute());
    }

    /** Mehrere Planungen am selben Tag werden zusammengezählt – das ist die Zahl in der Meldung. */
    @Test
    public void mehrereAmSelbenTagWerdenZusammengezaehlt() throws Exception {
        planung("Miete", heuteMittag(), EINMALIG, 0);
        planung("Strom", heuteMittag(), EINMALIG, 0);
        planung("Morgen", tageVersetzt(1), EINMALIG, 0);

        assertEquals(2, faelligHeute());
    }
}
