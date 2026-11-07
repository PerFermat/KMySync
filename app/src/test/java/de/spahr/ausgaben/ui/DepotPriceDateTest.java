package de.spahr.ausgaben.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Locale;

/** Die Depot-Zeile nennt das Datum des Kurses, damit ein veralteter Kurs auffällt (GitHub-Issue #12). */
public class DepotPriceDateTest {

    private final DateFormat df = new SimpleDateFormat("dd.MM.yy", Locale.GERMANY);

    @Test
    public void kursMitDatum() throws Exception {
        long d = df.parse("06.11.26").getTime();
        assertEquals("31,50 (06.11.26)", DepotActivity.priceWithDate("31,50", d, df));
    }

    @Test
    public void ohneDatumNurDerKurs() {
        assertEquals("31,50", DepotActivity.priceWithDate("31,50", 0, df));
    }
}
