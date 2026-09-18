package de.spahr.ausgaben.ui;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import de.spahr.ausgaben.db.Booking;

/**
 * Die Rangfolge aus {@link BookingLabel} – Stufe für Stufe.
 *
 * <p>Seit 2.1 darf der Empfänger leer bleiben, und damit wird die Frage „was steht dann da?" in jeder
 * Liste gestellt. Robolectric ist hier nötig (anders als in {@code WidgetSubtitleTest}), weil drei der
 * fünf Antworten aus {@code strings.xml} kommen.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BookingLabelTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private static Booking buchung(String payee, String kategorie) {
        Booking b = new Booking();
        b.payee = payee;
        b.category = kategorie;
        return b;
    }

    /** Stufe 2 schlägt alles darunter – auch wenn Kategorie und Aufteilung vorhanden sind. */
    @Test
    public void derEmpfaengerGehtVor() {
        assertEquals("Edeka", BookingLabel.title(ctx(), buchung("Edeka", "Lebensmittel"), true));
    }

    /** Stufe 4: ohne Empfänger tritt die Kategorie an seine Stelle – der Kern des Wunsches. */
    @Test
    public void ohneEmpfaengerStehtDieKategorie() {
        assertEquals("Versicherungen:Krankenzusatz",
                BookingLabel.title(ctx(), buchung("", "Versicherungen:Krankenzusatz"), false));
    }

    /**
     * <b>Der eigentliche Grund für die Rangfolge.</b>
     *
     * <p>{@code category} ist bei einer Splitbuchung <em>nicht</em> leer: {@code KmyImporter} trägt dort
     * den betragsmäßig größten Teil ein. Stünde die Kategorie vor der Aufteilung, sähe diese Buchung wie
     * eine gewöhnliche mit einer einzigen Kategorie aus – die Aufteilung wäre unsichtbar, und zwar ohne
     * jedes Anzeichen. Dieser Test ist der einzige, der eine Vertauschung bemerken würde; die beiden
     * darüber und darunter blieben grün.</p>
     */
    @Test
    @Config(qualifiers = "de")
    public void dieAufteilungGehtVorDerGroesstenKategorie() {
        assertEquals("Split-Buchung",
                BookingLabel.title(ctx(), buchung("", "Versicherungen:Krankenzusatz"), true));
    }

    /** Stufe 5: KMyMoneys eigener Wortlaut, wenn gar nichts zugeordnet ist. */
    @Test
    @Config(qualifiers = "de")
    public void ohneAllesStehtNichtZugewiesen() {
        assertEquals("*** NICHT ZUGEWIESEN ***", BookingLabel.title(ctx(), buchung("", ""), false));
        Booking ohneKategorie = buchung("", null);
        assertEquals("null-Kategorie zählt wie leer",
                "*** NICHT ZUGEWIESEN ***", BookingLabel.title(ctx(), ohneKategorie, false));
    }

    /**
     * Beide Ersatztexte gehen durch die gewöhnliche Übersetzungslogik – kein Sonderweg, kein fest
     * verdrahteter Wortlaut. Geprüft wird an allen drei Sprachen der App, und zwar mit genau den
     * Zeichenketten aus KMyMoneys eigenem Katalog: Wer KMySync auf Spanisch stellt, soll dasselbe lesen
     * wie in KMyMoney auf Spanisch. Ohne diesen Test bliebe eine vergessene Übersetzung unbemerkt – der
     * Rückfall auf {@code values/} ist ja gültiges Englisch und sieht nach Absicht aus.
     */
    @Test
    public void beideErsatztexteSindUebersetzt() {
        String[][] erwartet = {
                {"de", "Split-Buchung", "*** NICHT ZUGEWIESEN ***"},
                {"en", "Split transaction", "*** UNASSIGNED ***"},
                {"es", "Dividir asiento", "*** SIN ASIGNAR ***"},
        };
        for (String[] fall : erwartet) {
            Context ctx = ctx().createConfigurationContext(konfiguration(fall[0]));
            assertEquals(fall[0] + ": Split", fall[1],
                    BookingLabel.title(ctx, buchung("", "Lebensmittel"), true));
            assertEquals(fall[0] + ": ohne alles", fall[2],
                    BookingLabel.title(ctx, buchung("", ""), false));
        }
    }

    private android.content.res.Configuration konfiguration(String sprache) {
        android.content.res.Configuration c =
                new android.content.res.Configuration(ctx().getResources().getConfiguration());
        c.setLocale(new java.util.Locale(sprache));
        return c;
    }

    /**
     * Stufe 1 bleibt, wie sie war: Eine Umbuchung hat immer ein Gegenüber, ihr fehlt nie die Zuordnung.
     * Sie darf deshalb weder „Split-Buchung" noch „*** NICHT ZUGEWIESEN ***" zeigen.
     */
    @Test
    public void dieUmbuchungBehaeltIhrenPfeil() {
        Booking b = buchung("", "");
        b.isTransfer = true;
        b.transferAccount = "Sparkonto";
        assertEquals("→ Sparkonto", BookingLabel.title(ctx(), b, true));

        b.isIncome = true;
        assertEquals("← Sparkonto", BookingLabel.title(ctx(), b, true));

        b.payee = "Finanzamt";
        assertEquals("← Finanzamt", BookingLabel.title(ctx(), b, true));
    }
}
