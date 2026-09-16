package de.spahr.ausgaben.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

import de.spahr.ausgaben.db.Booking;

/**
 * Die graue Zeile unter dem Empfänger. Sie ist einzeilig und wird hinten abgeschnitten – deshalb
 * steht das Datum vorn und der Kontoname nur dort, wo er überhaupt etwas unterscheidet.
 */
public class BookingSubtitleTest {

    @BeforeClass
    public static void zeitzoneFestlegen() {
        // Die Formatierer merken sich die Zeitzone beim Laden der Klasse.
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
    }

    private static long stamp(int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.MARCH, 24, hour, minute, 0);
        return c.getTimeInMillis();
    }

    private static Booking mit(String account) {
        Booking b = new Booking();
        b.account = account;
        return b;
    }

    @Test
    public void einKontoZeigtDieUhrzeit() {
        assertEquals("24.03.2026 14:30",
                BookingAdapter.subtitle(stamp(14, 30), "Girokonto", true));
    }

    @Test
    public void importierteBuchungBleibtOhneUhrzeit() {
        // 00:00 ist beim Import keine Uhrzeit, sondern ein fehlender Wert.
        assertEquals("24.03.2026", BookingAdapter.subtitle(stamp(0, 0), "Girokonto", true));
    }

    @Test
    public void mehrereKontenZeigenNiemalsEineUhrzeit() {
        // Der Platz gehört dem Kontonamen.
        assertEquals("24.03.2026 · Girokonto",
                BookingAdapter.subtitle(stamp(14, 30), "Girokonto", false));
    }

    @Test
    public void datumStehtVorDemKontonamen() {
        String line = BookingAdapter.subtitle(stamp(9, 5), "Sparkasse Musterstadt Girokonto", false);
        assertTrue(line, line.indexOf("24.03.2026") < line.indexOf("Sparkasse"));
    }

    @Test
    public void ohneKontonameKeinEinsamerTrenner() {
        assertEquals("24.03.2026", BookingAdapter.subtitle(stamp(14, 30), "", false));
        assertEquals("24.03.2026", BookingAdapter.subtitle(stamp(14, 30), null, false));
    }

    @Test
    public void fehlendesDatumErfindetKeineUhrzeit() {
        // Ohne Datum in der .kmy-Datei landet 0 in der Buchung – in Berlin wäre das 01:00 gewesen.
        assertEquals("01.01.1970", BookingAdapter.subtitle(0L, "Girokonto", true));
    }

    @Test
    public void gleichesKontoInJederZeileIstUeberfluessig() {
        List<Booking> alle = Arrays.asList(mit("Girokonto"), mit("girokonto"));
        assertTrue(BookingAdapter.onlyOneAccount(alle));
    }

    @Test
    public void zweiKontenBrauchenDenNamen() {
        assertFalse(BookingAdapter.onlyOneAccount(Arrays.asList(mit("Girokonto"), mit("Tagesgeld"))));
    }

    @Test
    public void leereListeFuehrtZuNichts() {
        assertFalse(BookingAdapter.onlyOneAccount(null));
        assertFalse(BookingAdapter.onlyOneAccount(Collections.emptyList()));
    }
}
