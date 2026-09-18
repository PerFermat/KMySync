package de.spahr.ausgaben.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
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

    private ImageView icon;
    private TextView title;
    private EditText field;
    private BookingSearchBar bar;
    private final List<String> meldungen = new ArrayList<>();
    private final List<Boolean> offenMeldungen = new ArrayList<>();

    @Before
    public void aufbauen() {
        Context ctx = ApplicationProvider.getApplicationContext();
        icon = new ImageView(ctx);
        title = new TextView(ctx);
        field = new EditText(ctx);
        field.setVisibility(View.GONE);
        meldungen.clear();
        offenMeldungen.clear();
        bar = new BookingSearchBar(icon, title, field,
                () -> meldungen.add(bar.query()), offenMeldungen::add);
    }

    /** Läßt die Entprellung ablaufen – sonst kommt die Meldung nie. */
    private void ruheAbwarten() {
        ShadowLooper.idleMainLooper(BookingSearchBar.RUHE_MS + 50, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @Test
    public void dieLupeMachtDemFeldPlatz() {
        icon.performClick();

        assertEquals("Feld sichtbar", View.VISIBLE, field.getVisibility());
        assertEquals("Kontoname weg", View.GONE, title.getVisibility());
        assertEquals("die Maske erfährt es", java.util.Arrays.asList(true), offenMeldungen);
    }

    /**
     * <b>Der Kontoname ist das eigentliche Ziel.</b> Er ist breit und kaum zu verfehlen; die Lupe
     * daneben ist nur 20 dp groß und soll gar nicht getroffen werden müssen — sie zeigt an, daß es
     * hier etwas zu suchen gibt.
     *
     * <p>Diese Verdrahtung ist eine einzige Zeile und würde beim nächsten Umbau lautlos verlorengehen:
     * Sichtbar wäre das nur daran, daß ein Tipp auf den Namen nichts tut, und wer das nicht weiß,
     * tippt eben wieder auf die Lupe.</p>
     */
    @Test
    public void auchDerKontonameOeffnetDieSuche() {
        title.performClick();

        assertEquals("Feld sichtbar", View.VISIBLE, field.getVisibility());
        assertEquals("Kontoname weg", View.GONE, title.getVisibility());
    }

    /** Und er räumt sie auch wieder weg — dieselbe Bedeutung wie die Lupe, nicht eine zweite. */
    @Test
    public void derKontonameRaeumtDieSuche() {
        title.performClick();
        field.setText("Netto");
        ruheAbwarten();
        field.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
        meldungen.clear();

        title.performClick();

        assertEquals("nichts mehr gesucht", "", bar.query());
        assertFalse(bar.istAktiv());
        assertEquals("ohne Wartezeit gemeldet", java.util.Arrays.asList(""), meldungen);
    }

    /** Getippt wird laufend, gefiltert erst, wenn die Eingabe ruht. */
    @Test
    public void erstNachDerRuheWirdGefiltert() {
        icon.performClick();
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
        icon.performClick();
        field.setText("Netto");
        ruheAbwarten();

        field.onEditorAction(EditorInfo.IME_ACTION_SEARCH);

        assertEquals("Feld zu", View.GONE, field.getVisibility());
        assertEquals("Kontoname zurück", View.VISIBLE, title.getVisibility());
        assertEquals("Suchtext bleibt", "Netto", bar.query());
        assertTrue("die Suche wirkt weiter", bar.istAktiv());
    }

    /**
     * Und weil sie weiterwirkt, muß man das sehen: Solange etwas gesucht wird, trägt die Lupe ihren
     * Durchstrich — auch bei eingeklapptem Feld. Ohne dieses Zeichen gäbe es keinen Hinweis auf die
     * Einschränkung und keinen Weg zurück.
     */
    @Test
    public void dasSymbolZeigtDieLaufendeSuche() {
        icon.performClick();
        field.setText("Netto");
        ruheAbwarten();
        field.onEditorAction(EditorInfo.IME_ACTION_SEARCH);

        assertTrue("eingeklappt, aber aktiv", bar.istAktiv());
        assertFalse("Feld ist zu", bar.istOffen());
    }

    /** Ein Tipp auf die durchgestrichene Lupe räumt die Suche – und meldet das sofort, nicht entprellt. */
    @Test
    public void dieLupeLoeschtDieLaufendeSuche() {
        icon.performClick();
        field.setText("Netto");
        ruheAbwarten();
        field.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
        meldungen.clear();

        icon.performClick();

        assertEquals("nichts mehr gesucht", "", bar.query());
        assertFalse(bar.istAktiv());
        assertEquals("ohne Wartezeit gemeldet", java.util.Arrays.asList(""), meldungen);
    }

    /** „Zurücksetzen" im Trichter räumt mit – aber ohne eigene Meldung, es filtert selbst neu. */
    @Test
    public void stillesLoeschenMeldetNicht() {
        icon.performClick();
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
        assertTrue("eingeklappt, aber weiterhin aktiv", bar.istAktiv());
        assertFalse(bar.istOffen());
    }

    /**
     * Ein entprellter Lauf darf nach dem Ende der Maske nicht mehr feuern – er arbeitete auf Ansichten,
     * die es nicht mehr gibt. {@code Ui.post} greift hier nicht: Der Handler gehört dieser Leiste.
     */
    @Test
    public void nachDetachKommtNichtsMehr() {
        icon.performClick();
        field.setText("Netto");
        bar.detach();

        ruheAbwarten();

        assertTrue(meldungen.isEmpty());
    }
}
