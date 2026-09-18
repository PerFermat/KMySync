package de.spahr.ausgaben.ui;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

/**
 * Die System-Tastatur öffnen und schließen – die eine Stelle, an der das steht. Vorher lagen dieselben
 * vier Zeilen zweimal im Baum, und eine dritte Abschrift wäre dazugekommen, als die Vorschlagsfelder
 * ihre Fertig-Taste bekamen.
 *
 * <p>Dazu die beiden Wege, sie wieder <em>los</em>zuwerden, wenn sie einen Knopf verdeckt:
 * {@link #onCommitAction} für „fertig, los" und {@link #hideOnScroll} für „ich will weiter unten noch
 * etwas einstellen".</p>
 */
final class Keyboard {

    private Keyboard() {
    }

    /**
     * Setzt den Kursor in {@code field} und holt die Tastatur dazu – für Stellen, an denen der Benutzer
     * gerade zu erkennen gegeben hat, daß er tippen will.
     *
     * <p>Der Aufruf wartet über {@code post(…)} einen Durchgang ab: in einem eben erst gezeigten Dialog
     * hängt das Feld noch an keinem Fenster, und die Tastatur bliebe stumm.</p>
     */
    static void show(View field) {
        if (field == null) {
            return;
        }
        field.post(() -> {
            field.requestFocus();
            InputMethodManager imm = (InputMethodManager)
                    field.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    /**
     * Schließt die Tastatur, die zum Fenster von {@code anchor} gehört. Der Fokus bleibt, wo er ist –
     * wer ihn abgeben will, muß das selbst tun.
     *
     * <p>Hängt die Ansicht (noch) an keinem Fenster, gibt es auch keine Tastatur zu schließen.</p>
     */
    static void hide(View anchor) {
        if (anchor == null || anchor.getWindowToken() == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager)
                anchor.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(anchor.getWindowToken(), 0);
        }
    }

    /**
     * Die Bestätigungstaste der Tastatur räumt diese weg und löst {@code aktion} aus.
     *
     * <p>Gedacht für Textfelder in einem Dialog, dessen Knöpfe die Tastatur verdeckt. Der Filter der
     * Buchungsliste war der Anlaß: Er ist lang (Suche, Kategorie, Stichwort, Betrag, Datum, Umkreis),
     * das Suchfeld steht ganz oben, und „Übernehmen" sitzt unter der Tastatur. Wer nur einen Namen
     * suchen wollte – der häufigste Fall überhaupt –, mußte erst die Tastatur wegwischen.</p>
     *
     * <p><b>Drei Auslöser statt einem</b>, und das ist nicht Übervorsicht: Welchen Code eine Tastatur
     * schickt, entscheidet sie selbst. Die einen melden die angeforderte Aktion
     * ({@code IME_ACTION_SEARCH}), andere {@code IME_ACTION_DONE}, und eine angesteckte
     * Hardware-Tastatur meldet gar keine Aktion, sondern schlicht die Eingabetaste als
     * {@link KeyEvent}. Prüfte man nur auf eine davon, ginge die Taste auf dem eigenen Gerät und auf
     * einem anderen nicht.</p>
     *
     * <p>Beim Tastendruck zählt nur das <b>Loslassen</b>: Drücken und Loslassen kommen beide hier an,
     * die Aktion liefe sonst zweimal.</p>
     */
    static android.widget.TextView.OnEditorActionListener onCommitAction(
            android.widget.EditText field, Runnable aktion) {
        if (field == null || aktion == null) {
            return null;
        }
        android.widget.TextView.OnEditorActionListener listener = (v, actionId, event) -> {
            boolean gemeldeteAktion = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                    || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                    || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO;
            boolean eingabetaste = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_UP;
            if (!gemeldeteAktion && !eingabetaste) {
                return false;
            }
            hide(v);
            aktion.run();
            return true;
        };
        field.setOnEditorActionListener(listener);
        return listener;
    }

    /**
     * Wer in einem Dialog nach unten schiebt, will dort etwas sehen – die Tastatur geht dann weg.
     *
     * <p>Der Gegenfall zu {@link #onCommitAction}: Nach dem Suchtext soll noch ein Zeitraum oder ein
     * Betrag eingestellt werden. Bestätigen wäre falsch, aber die Tastatur steht im Weg.</p>
     *
     * <p><b>An der Berührung, nicht am Scrollstand.</b> Ein {@code OnScrollChangeListener} wäre der
     * naheliegende Weg und der falsche: Er schlägt auch an, wenn die Tastatur selbst den Dialog
     * verkleinert und der Inhalt dadurch verrutscht – sie schlösse sich dann selbst, kaum daß man ein
     * Feld angetippt hat. Deshalb zählt allein {@code ACTION_MOVE}, also ein bewegter Finger.</p>
     *
     * <p>Das Ereignis wird <b>nicht</b> verbraucht: Der Dialog muß weiter scrollen und ein Tipp auf
     * ein Feld darin weiter ankommen.</p>
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    static void hideOnScroll(android.widget.ScrollView scroll) {
        if (scroll == null) {
            return;
        }
        scroll.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_MOVE) {
                hide(v);
            }
            return false;
        });
    }
}
