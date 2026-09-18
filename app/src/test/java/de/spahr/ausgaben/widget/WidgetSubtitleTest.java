package de.spahr.ausgaben.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

import de.spahr.ausgaben.db.Booking;

/**
 * Die graue Zeile unter dem Empfänger im großen Widget — das Gegenstück zu
 * {@code BookingSubtitleTest}.
 *
 * <p>Sie ist einzeilig und wird <em>hinten</em> abgeschnitten
 * ({@code maxLines="1"}, {@code ellipsize="end"}). Deshalb steht das Datum vorn: Es wird in jeder
 * Zeile gebraucht, die Kategorie darf notfalls wegfallen. KMyMoney-Kategorien sind hierarchisch und
 * entsprechend lang («Versicherungen:Krankenzusatz»), das Widget dagegen schmal.</p>
 *
 * <p>Diese Klasse gibt es, weil das Widget beim Umbau der Buchungsliste übersehen wurde: Dort wurde
 * die Reihenfolge längst gedreht, hier stand die Kategorie weiterhin vorn — aufgefallen ist es erst
 * am fertig eingerichteten Widget auf dem Gerät. Ein Wächter über Datumsmuster kann so etwas nicht
 * finden, ein Test über die Reihenfolge schon.</p>
 *
 * <p>Das Datum steht <b>vollständig</b> da, mit Jahr — wie in der Buchungsliste. Kurz stand hier nur
 * Tag und Monat, um Platz zu sparen; bei älteren Buchungen ist das aber mehrdeutig, und gerade im
 * Widget sieht man sie ohne weiteren Zusammenhang.</p>
 *
 * <p>Ohne Robolectric, wie {@code BookingSubtitleTest}: {@link de.spahr.ausgaben.settings.DateFormats}
 * steht dann auf seinen Vorgabewerten, also deutsch. Das ist vorhersagbar und braucht keine
 * Android-Laufzeit — geprüft wird hier ohnehin die Reihenfolge, nicht die Schreibweise.</p>
 */
public class WidgetSubtitleTest {

    @BeforeClass
    public static void zeitzoneFestlegen() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
    }

    private static Booking buchung(String kategorie) {
        Booking b = new Booking();
        b.category = kategorie;
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.MARCH, 24, 9, 5, 0);
        b.createdAt = c.getTimeInMillis();
        return b;
    }

    /** Der eigentliche Fund: Die lange Kategorie darf das Datum nicht aus der Zeile drängen. */
    @Test
    public void datumStehtVorDerKategorie() {
        String zeile = WidgetLarge.sub(buchung("Versicherungen:Krankenzusatz"));

        assertTrue(zeile, zeile.indexOf("24.03.2026") < zeile.indexOf("Versicherungen"));
        assertTrue("die Zeile beginnt mit dem Datum", zeile.startsWith("24.03.2026"));
    }

    /**
     * Ohne Kategorie bleibt nur das Datum – und kein einsamer Trenner davor oder dahinter. Das Jahr
     * gehört dazu: Ohne es ist eine ältere Buchung im Widget nicht einzuordnen.
     */
    @Test
    public void ohneKategorieStehtNurDasVollstaendigeDatum() {
        assertEquals("24.03.2026", WidgetLarge.sub(buchung("")));
        assertEquals("24.03.2026", WidgetLarge.sub(buchung(null)));
    }

    @Test
    public void mitKategorieStehenBeideMitTrenner() {
        assertEquals("24.03.2026 · Geschenke", WidgetLarge.sub(buchung("Geschenke")));
    }
}
