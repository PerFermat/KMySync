package de.spahr.ausgaben.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Die Bestätigungstaste der Tastatur — und welche davon zählt.
 *
 * <p>Im Filter der Buchungsliste verdeckte die Tastatur den Knopf „Übernehmen": Der Dialog ist lang
 * (Suche, Kategorie, Stichwort, Betrag, Datum, Umkreis), das Suchfeld steht ganz oben, und wer nur
 * einen Namen suchen wollte, mußte die Tastatur erst wegwischen. Jetzt übernimmt die Lupe unmittelbar.
 * </p>
 *
 * <p>Warum das hier geprüft wird und nicht am Gerät: Der Fehlerfall ist <b>gerätespezifisch</b>. Welchen
 * Code eine Tastatur meldet, entscheidet sie selbst — auf dem eigenen Gerät funktioniert jede Fassung
 * dieser Abfrage, und gerade das macht sie gefährlich. {@code Keyboard.onCommitAction} gibt seinen
 * Zuhörer eigens zurück, damit er hier mit allen Meldungen gerufen werden kann, die in freier Wildbahn
 * vorkommen.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeyboardCommitTest {

    private EditText feld;
    private TextView.OnEditorActionListener zuhoerer;
    private int gelaufen;

    @Before
    public void aufbauen() {
        feld = new EditText(ApplicationProvider.getApplicationContext());
        gelaufen = 0;
        zuhoerer = Keyboard.onCommitAction(feld, () -> gelaufen++);
    }

    /** So, wie es eine Tastatur tut: eine Aktion, und wahlweise ein Tastenereignis dazu. */
    private boolean meldet(int actionId, KeyEvent event) {
        return zuhoerer.onEditorAction(feld, actionId, event);
    }

    /** Der gewöhnliche Weg: Das Feld fordert {@code actionSearch}, die Tastatur meldet es zurück. */
    @Test
    public void dieLupeUebernimmt() {
        assertTrue("verbraucht sein eigenes Ereignis", meldet(EditorInfo.IME_ACTION_SEARCH, null));
        assertEquals(1, gelaufen);
    }

    /**
     * Nicht jede Tastatur meldet die <em>angeforderte</em> Aktion zurück. Manche schicken „Fertig"
     * oder „Los", obwohl das Feld die Lupe verlangt hat. Prüfte man nur auf {@code IME_ACTION_SEARCH},
     * täte der Knopf auf solchen Geräten schlicht nichts — und niemand käme darauf, warum.
     */
    @Test
    public void auchFertigUndLosUebernehmen() {
        meldet(EditorInfo.IME_ACTION_DONE, null);
        assertEquals("IME_ACTION_DONE", 1, gelaufen);

        meldet(EditorInfo.IME_ACTION_GO, null);
        assertEquals("IME_ACTION_GO", 2, gelaufen);
    }

    /**
     * Eine angesteckte Hardware-Tastatur meldet gar keine Aktion, sondern die Eingabetaste als
     * Tastenereignis — und zwar <b>zweimal</b>, beim Drücken und beim Loslassen. Gezählt werden darf
     * nur eines von beiden. Sonst liefe der Filter doppelt; hier wäre das nur unnötige Arbeit, an einem
     * Knopf mit Nebenwirkung wäre es ein Fehler.
     */
    @Test
    public void dieEingabetasteZaehltNurEinmal() {
        meldet(EditorInfo.IME_ACTION_UNSPECIFIED,
                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
        assertEquals("Drücken zählt nicht", 0, gelaufen);

        meldet(EditorInfo.IME_ACTION_UNSPECIFIED,
                new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
        assertEquals("Loslassen zählt", 1, gelaufen);
    }

    /**
     * Was nicht bestätigt, muß durchgereicht werden: {@code false} heißt „nicht verbraucht", und erst
     * das läßt die Tastatur ihre eigene Arbeit tun. Der Sprung ins nächste Feld etwa – im Filter geht
     * es von der Suche zur Kategorie – hinge sonst.
     */
    @Test
    public void allesAndereWirdDurchgereicht() {
        assertFalse(meldet(EditorInfo.IME_ACTION_NEXT, null));
        assertFalse(meldet(EditorInfo.IME_ACTION_UNSPECIFIED, null));
        assertFalse("ein Buchstabe ist keine Bestätigung", meldet(EditorInfo.IME_ACTION_UNSPECIFIED,
                new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_E)));
        assertEquals(0, gelaufen);
    }

    /** Ohne Feld oder ohne Aktion gibt es nichts zu verdrahten – und keinen Absturz. */
    @Test
    public void nullsSindErlaubt() {
        Keyboard.onCommitAction(null, () -> gelaufen++);
        Keyboard.onCommitAction(feld, null);
        Keyboard.hideOnScroll(null);
        assertEquals(0, gelaufen);
    }
}
