package de.spahr.ausgaben.notify;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.location.Location;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Welcher Fix beim Eintreffen einer Meldung zählt – und wann genug gewartet ist. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ArrivalLocationTest {

    private static final long START = 1_000_000_000L;

    private static Location fix(long time, Float accuracy) {
        Location l = new Location("gps");
        l.setLatitude(50.1);
        l.setLongitude(8.6);
        l.setTime(time);
        if (accuracy != null) {
            l.setAccuracy(accuracy);
        }
        return l;
    }

    /** Ein alter Standort (eine Wiederholung von früher) zählt nicht. */
    @Test
    public void alterFixZaehltNicht() {
        ArrivalLocation.Fixes f = new ArrivalLocation.Fixes(START);
        assertFalse(f.offer(fix(START - ArrivalLocation.FRESH_TOLERANCE_MS - 1, 5f)));
        assertNull(f.best());
    }

    /** Frisch und genau genug: die Ortung ist fertig. */
    @Test
    public void genauerFrischerFixBeendetDieOrtung() {
        ArrivalLocation.Fixes f = new ArrivalLocation.Fixes(START);
        Location l = fix(START + 3000, 12f);
        assertTrue(f.offer(l));
        assertSame(l, f.best());
    }

    /** Ein grober frischer Fix wird gemerkt, aber es wird weiter gewartet. */
    @Test
    public void groberFixWirdNurGemerkt() {
        ArrivalLocation.Fixes f = new ArrivalLocation.Fixes(START);
        Location grob = fix(START + 1000, 800f);
        assertFalse(f.offer(grob));
        assertSame(grob, f.best());
        Location besser = fix(START + 2000, 300f);
        assertFalse(f.offer(besser));
        assertSame(besser, f.best());
        // Ein noch gröberer verdrängt den besseren nicht.
        assertFalse(f.offer(fix(START + 3000, 900f)));
        assertSame(besser, f.best());
    }

    /** Ohne Genauigkeitsangabe genügt ein Fix nicht, taugt aber als Rückfall. */
    @Test
    public void fixOhneGenauigkeitIstNurRueckfall() {
        ArrivalLocation.Fixes f = new ArrivalLocation.Fixes(START);
        Location ohne = fix(START, null);
        assertFalse(f.offer(ohne));
        assertSame(ohne, f.best());
        Location mit = fix(START + 1000, 500f);
        f.offer(mit);
        assertSame(mit, f.best());
    }

    /** Die Meldung wartet länger als die Ortung – der Rückruf hat Zeit, sie freizugeben. */
    @Test
    public void zurueckhaltenDauertLaengerAlsDieOrtung() {
        assertTrue(ArrivalLocation.HOLD_MS > ArrivalLocation.WAIT_MS);
        assertTrue(ArrivalLocation.WAIT_MS >= 60_000L);
    }
}
