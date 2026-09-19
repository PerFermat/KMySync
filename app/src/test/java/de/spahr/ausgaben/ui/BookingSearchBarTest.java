package de.spahr.ausgaben.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

/**
 * Das Umschalten der Live-Suche in der Titelzeile.
 *
 * <p>Der Reiz dieser Leiste liegt darin, daß „Feld zu" und „Suche vorbei" <b>zweierlei</b> sind: Die
 * Lupe auf der Tastatur klappt nur das Feld ein, die Liste bleibt eingeengt. Genau das ist auch der
 * Fehler, der leicht passiert — ein {@code setText("")} zuviel beim Einklappen, und die Einschränkung
 * wäre still weg. Am Bild sähe man nur eine vollständige Liste, und die sieht aus wie in Ordnung.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BookingSearchBarTest {

    private TextView title;
    private EditText field;
    private BookingSearchBar bar;
    private final List<String> meldungen = new ArrayList<>();
    private final List<Boolean> offenMeldungen = new ArrayList<>();

    @Before
    public void aufbauen() {
        Context ctx = ApplicationProvider.getApplicationContext();
        title = new TextView(ctx);
        field = new EditText(ctx);
        field.setVisibility(View.GONE);
        meldungen.clear();
        offenMeldungen.clear();
        bar = new BookingSearchBar(title, field,
                () -> meldungen.add(bar.query()), offenMeldungen::add);
    }

    /** Läßt die Entprellung ablaufen – sonst kommt die Meldung nie. */
    private void ruheAbwarten() {
        ShadowLooper.idleMainLooper(BookingSearchBar.RUHE_MS + 50, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @Test
    public void derKontonameMachtDemFeldPlatz() {
        title.performClick();

        assertEquals("Feld sichtbar", View.VISIBLE, field.getVisibility());
        assertEquals("Kontoname weg", View.GONE, title.getVisibility());
        assertEquals("die Maske erfährt es", java.util.Arrays.asList(true), offenMeldungen);
    }

    /**
     * <b>Der Fall, der zählt.</b> Ist die Suche eingeklappt und man tippt den Kontonamen erneut an,
     * kommt das Feld <em>mit dem bisherigen Suchtext</em> zurück — man bessert ihn nach, statt ihn
     * neu zu schreiben. Ginge er dabei verloren, merkte man es erst, wenn man schon losgetippt hat.
     *
     * <p>Geräumt wird hier gar nichts mehr; das tut das X vor „Filter aktiv (n)" in der Zeile
     * darunter, und zwar für Live-Suche und Trichter gemeinsam.</p>
     */
    @Test
    public void derKontonameHoltDieSucheZurueck() {
        title.performClick();
        field.setText("Netto");
        ruheAbwarten();
        field.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
        meldungen.clear();

        title.performClick();

        assertEquals("Feld wieder offen", View.VISIBLE, field.getVisibility());
        assertEquals("Suchtext steht noch drin", "Netto", bar.query());
        assertTrue("und wurde nicht neu gemeldet", meldungen.isEmpty());
    }

    /** Getippt wird laufend, gefiltert erst, wenn die Eingabe ruht. */
    @Test
    public void erstNachDerRuheWirdGefiltert() {
        title.performClick();
        field.setText("Net");
        assertTrue("noch nichts gemeldet", meldungen.isEmpty());

        field.setText("Netto");
        ruheAbwarten();

        assertEquals("genau einmal, mit dem letzten Stand",
                java.util.Arrays.asList("Netto"), meldungen);
    }

    /**
     * <b>Der Kern.</b> Die Lupe auf der Tastatur beendet die Eingabe, nicht die Suche: Das Feld
     * verschwindet, der Kontoname kommt zurück — und der Suchtext bleibt, die Liste also eingeengt.
     */
    @Test
    public void dieTastaturLupeKlapptNurEin() {
        title.performClick();
        field.setText("Netto");
        ruheAbwarten();

        field.onEditorAction(EditorInfo.IME_ACTION_SEARCH);

        assertEquals("Feld zu", View.GONE, field.getVisibility());
        assertEquals("Kontoname zurück", View.VISIBLE, title.getVisibility());
        assertEquals("Suchtext bleibt, die Liste also eingeengt", "Netto", bar.query());
    }

    /** „Zurücksetzen" im Trichter räumt mit – aber ohne eigene Meldung, es filtert selbst neu. */
    @Test
    public void stillesLoeschenMeldetNicht() {
        title.performClick();
        field.setText("Netto");
        ruheAbwarten();
        meldungen.clear();

        bar.clearSilently();
        ruheAbwarten();

        assertEquals("", bar.query());
        assertTrue("keine Meldung", meldungen.isEmpty());
    }

    /**
     * Nach der Drehung stehen Text und Zustand wieder da – ohne zu melden: Die Maske filtert beim
     * Neuaufbau ohnehin, und zu diesem Zeitpunkt ist die Buchungsliste noch gar nicht geladen.
     */
    @Test
    public void dieDrehungRettetDieSuche() {
        bar.restore("Netto", true);

        assertEquals("Netto", bar.query());
        assertTrue(bar.istOffen());
        assertTrue(meldungen.isEmpty());

        bar.restore("Netto", false);
        assertFalse("eingeklappt", bar.istOffen());
        assertEquals("aber weiterhin gesucht", "Netto", bar.query());
    }

    /**
     * Ein entprellter Lauf darf nach dem Ende der Maske nicht mehr feuern – er arbeitete auf Ansichten,
     * die es nicht mehr gibt. {@code Ui.post} greift hier nicht: Der Handler gehört dieser Leiste.
     */
    @Test
    public void nachDetachKommtNichtsMehr() {
        title.performClick();
        field.setText("Netto");
        bar.detach();

        ruheAbwarten();

        assertTrue(meldungen.isEmpty());
    }
}
