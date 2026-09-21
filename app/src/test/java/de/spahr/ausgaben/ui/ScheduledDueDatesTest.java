package de.spahr.ausgaben.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.List;

import de.spahr.ausgaben.db.ScheduledTransaction;

/**
 * Hält fest, dass eine liegengebliebene Planung ihren fälligen Termin behält
 * ({@link ScheduledActivity#dueDates}).
 *
 * <p>Der Fall, der den Test veranlasst hat: Das Fenster der Liste beginnt einen Monat vor heute.
 * Eine monatliche Planung, die länger nicht gebucht wurde, hat ihre Fälligkeit davor – und fiel
 * damit aus der Liste. Weil Buchen und Überspringen daran hängen, dass eine angezeigte Zeile genau
 * diesen Termin trägt, ließ sie sich danach weder buchen noch überspringen. Da Überspringen der
 * einzige Weg gewesen wäre, sie wieder einzufangen, blieb sie dauerhaft hängen.</p>
 */
public class ScheduledDueDatesTest {

    /** Monatlich (KMyMoney-Kennung 32), ohne Enddatum. */
    private static ScheduledTransaction monatlich() {
        ScheduledTransaction st = new ScheduledTransaction();
        st.occurrence = 32;
        st.occurrenceMultiplier = 1;
        st.endMs = 0;
        return st;
    }

    private static long ymd(int y, int m, int d) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(y, m - 1, d);
        return c.getTimeInMillis();
    }

    /** Der Fall aus dem Fehlerbericht: fällig am 01.08., Fenster ab 21.08., gemeldet am 21.09. */
    @Test
    public void ueberfaelligerTerminBleibtErhalten() {
        long base = ymd(2026, 8, 1);
        long from = ymd(2026, 8, 21);
        long to = ymd(2026, 12, 21);

        List<Long> dues = ScheduledActivity.dueDates(base, monatlich(), from, to);

        assertTrue("der fällige Termin muss dabei sein, sonst ist die Planung nicht mehr anfassbar",
                dues.contains(base));
        assertEquals("und er gehört nach vorn", base, (long) dues.get(0));
    }

    /**
     * Der zweite Teil: <b>jeder</b> verpasste Termin gehört in die Liste, nicht nur der älteste.
     *
     * <p>Seit Januar liegengeblieben, heute im September – Februar bis Juli sind genauso offen wie
     * Januar und dürfen nicht still ausfallen.</p>
     */
    @Test
    public void jederVerpassteTerminErscheint() {
        long base = ymd(2026, 1, 1);
        long from = ymd(2026, 8, 1);          // Fenster: ein Monat vor „heute" (01.09.)
        long to = ymd(2026, 12, 1);

        List<Long> dues = ScheduledActivity.dueDates(base, monatlich(), from, to);

        for (int monat = 1; monat <= 12; monat++) {
            assertTrue("der 01." + monat + ". fehlt", dues.contains(ymd(2026, monat, 1)));
        }
        assertEquals("lückenlos ab dem fälligen Termin", 12, dues.size());
        assertEquals(base, (long) dues.get(0));
    }

    /** Die Kette muss lückenlos sein – kein Sprung vom fälligen Termin zum Fensteranfang. */
    @Test
    public void keineLueckeZwischenFaelligemTerminUndFenster() {
        long base = ymd(2026, 1, 1);
        List<Long> dues = ScheduledActivity.dueDates(base, monatlich(),
                ymd(2026, 8, 1), ymd(2026, 10, 1));

        assertEquals(ymd(2026, 1, 1), (long) dues.get(0));
        assertEquals("direkt danach der Februar, nicht der August", ymd(2026, 2, 1), (long) dues.get(1));
        assertEquals(ymd(2026, 3, 1), (long) dues.get(2));
    }

    /** Liegt der fällige Termin im Fenster, ändert sich nichts – und er steht nur einmal da. */
    @Test
    public void terminImFensterWirdNichtVerdoppelt() {
        long base = ymd(2026, 9, 1);
        long from = ymd(2026, 8, 21);
        long to = ymd(2026, 12, 21);

        List<Long> dues = ScheduledActivity.dueDates(base, monatlich(), from, to);

        assertEquals(base, (long) dues.get(0));
        int treffer = 0;
        for (long d : dues) {
            if (d == base) {
                treffer++;
            }
        }
        assertEquals("kein doppelter Eintrag", 1, treffer);
    }

    /** Eine beendete Planung bleibt beendet: Ihr alter Termin wird nicht wiederbelebt. */
    @Test
    public void beendetePlanungBekommtKeinenTerminZurueck() {
        long base = ymd(2026, 8, 1);
        ScheduledTransaction st = monatlich();
        st.endMs = ymd(2026, 7, 1);          // Ende liegt vor dem fälligen Termin

        List<Long> dues = ScheduledActivity.dueDates(base, st, ymd(2026, 8, 21), ymd(2026, 12, 21));

        assertFalse(dues.contains(base));
    }

    /**
     * Die Termine nach dem überfälligen bleiben unangetastet – der Zusatz ergänzt, er ersetzt nicht.
     */
    @Test
    public void kuenftigeTermineBleibenVollstaendig() {
        long base = ymd(2026, 8, 1);
        List<Long> dues = ScheduledActivity.dueDates(base, monatlich(),
                ymd(2026, 8, 21), ymd(2026, 11, 21));

        assertEquals(base, (long) dues.get(0));
        assertTrue(dues.contains(ymd(2026, 9, 1)));
        assertTrue(dues.contains(ymd(2026, 10, 1)));
        assertTrue(dues.contains(ymd(2026, 11, 1)));
    }
}
