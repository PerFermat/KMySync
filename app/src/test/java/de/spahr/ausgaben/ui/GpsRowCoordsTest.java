package de.spahr.ausgaben.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Der Standort einer Buchung übersteht das Öffnen und Speichern Zeichen für Zeichen. Der {@code GPS:}-Tag
 * wird beim Speichern aus dem gelesenen Wert neu zusammengesetzt – jede Abweichung dabei stünde nach
 * dem Export als geänderte Notiz in der KMyMoney-Datei.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class GpsRowCoordsTest {

    /** So setzt {@code BookingEditActivity.composeNoteForSave} den Tag wieder an. */
    private static String wiederAngesetzt(String notiz) {
        String frei = BookingEditActivity.stripTags(notiz);
        String coords = GpsRowController.parseGpsCoords(notiz);
        return coords == null ? frei : (frei.isEmpty() ? "GPS: " + coords : frei + " GPS: " + coords);
    }

    @Test
    public void leerzeichenHinterDemKommaBleibt() {
        assertEquals("48.775800, 9.182900", GpsRowController.parseGpsCoords("GPS: 48.775800, 9.182900"));
        assertEquals("Bäcker GPS: 48.775800, 9.182900", wiederAngesetzt("Bäcker GPS: 48.775800, 9.182900"));
        assertEquals("GPS: 48.775800, 9.182900", wiederAngesetzt("GPS: 48.775800, 9.182900"));
    }

    /** Auch die Form ohne Leerzeichen bleibt, wie sie ist – es wird nichts hineinkorrigiert. */
    @Test
    public void ohneLeerzeichenBleibtOhne() {
        assertEquals("48.775800,9.182900", GpsRowController.parseGpsCoords("x GPS: 48.775800,9.182900"));
        assertEquals("x GPS: 48.775800,9.182900", wiederAngesetzt("x GPS: 48.775800,9.182900"));
        assertEquals("-33.5,-70.25", GpsRowController.parseGpsCoords("GPS:-33.5,-70.25"));
        assertNull(GpsRowController.parseGpsCoords("ohne Standort"));
        assertNull(GpsRowController.parseGpsCoords(null));
    }

    /** Ein auf der Karte gewählter Standort bekommt dieselbe Form wie einer aus der Ortung. */
    @Test
    public void neueKoordinatenMitLeerzeichen() {
        assertEquals("48.775800, 9.182900", GpsRowController.formatCoords(48.7758, 9.1829));
        assertEquals("-33.500000, -70.250000", GpsRowController.formatCoords(-33.5, -70.25));
    }
}
