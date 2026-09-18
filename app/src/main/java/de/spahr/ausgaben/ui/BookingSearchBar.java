package de.spahr.ausgaben.ui;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import de.spahr.ausgaben.R;

/**
 * Die Live-Suche in der Titelzeile der Buchungsliste.
 *
 * <p>Gebaut nach dem Vorbild der Kontenschublade ({@link AccountDrawerHeader}): Ein Tipp auf die Lupe,
 * und der Kontoname macht einem Suchfeld Platz. Was man tippt, engt die Liste sofort ein. Bis 2.1
 * führte der einzige Weg dorthin über den Trichter — einen Dialog mit sechs Blöcken und
 * „Übernehmen" ganz unten; für „wo war nochmal diese eine Buchung" waren das fünf Handgriffe.</p>
 *
 * <h2>Die vier Zustände</h2>
 *
 * <ul>
 *   <li><b>Lupe antippen</b> — Kontoname weg, Feld da, Tastatur auf.</li>
 *   <li><b>Tippen</b> — nach kurzer Ruhe filtern (siehe unten).</li>
 *   <li><b>Lupe auf der Tastatur</b> — Feld zu, Kontoname zurück, <em>die Suche bleibt</em>. Der
 *       Suchtext ist ja das Ergebnis, nicht das Feld.</li>
 *   <li><b>Lupe im Band, während gesucht wird</b> — Suchtext löschen. Erkennbar am durchgestrichenen
 *       Symbol {@code ic_search_off}: Nach dem Einklappen gäbe es sonst keinen Hinweis mehr darauf,
 *       daß die Liste noch eingeengt ist, und keinen Weg zurück.</li>
 * </ul>
 *
 * <h2>Warum entprellt wird</h2>
 *
 * <p>Die Kontenschublade filtert bei jedem Tastendruck und kommt damit aus — sie hat ein paar Dutzend
 * Konten. Hier läuft {@code applyFilter()} über alle Buchungen im Speicher, prüft je Buchung
 * Kategorie-Teile, Stichwörter und den GPS-Umkreis, baut die Saldo-Ansichten neu und wirft den
 * Adapter um. Bei ein paar tausend Buchungen fünfmal hintereinander für „Netto" wäre das spürbar.
 * Deshalb erst, wenn die Eingabe {@value #RUHE_MS} ms ruht — nach demselben Muster, das der
 * Zifferndialog in {@code MainActivity} schon benutzt.</p>
 *
 * <h2>Warum kein gemeinsamer Vorfahr mit {@link AccountDrawerHeader}</h2>
 *
 * <p>Gemeinsam ist das Muster, nicht der Code. Dort hängen das Kontengruppen-Dreieck, ein Zahnrad und
 * ein Beobachter für die Tastaturhöhe mit im Kopf; hier ein Untertitel, der stehenbleibt, und diese
 * Entprellung. Ein gemeinsamer Vorfahr müßte beide Seiten kennen und wäre die dritte Sache, die man
 * beim Ändern versteht.</p>
 */
final class BookingSearchBar {

    /** Wie lange die Eingabe ruhen muß, bevor gefiltert wird. */
    static final long RUHE_MS = 200L;

    private final ImageView icon;
    private final TextView title;
    private final EditText field;
    private final Runnable onQueryChanged;
    private final java.util.function.Consumer<Boolean> onOpenChanged;
    private final Handler ruhe = new Handler(Looper.getMainLooper());

    /**
     * @param onQueryChanged wird gerufen, wenn sich {@link #query()} geändert hat — die Maske filtert
     *                       dann neu. Nicht bei jedem Tastendruck, siehe Entprellung.
     * @param onOpenChanged  meldet Auf- und Zuklappen. Die Maske hängt daran ihre Zurück-Taste: Bei
     *                       offenem Feld soll sie es schließen statt die Maske zu verlassen, und der
     *                       Rückruf ist der einzige Weg, das <em>auch dann</em> mitzubekommen, wenn
     *                       nicht die Maske, sondern die Lupe oder die Tastatur umgeschaltet hat.
     */
    BookingSearchBar(ImageView icon, TextView title, EditText field, Runnable onQueryChanged,
                     java.util.function.Consumer<Boolean> onOpenChanged) {
        this.icon = icon;
        this.title = title;
        this.field = field;
        this.onQueryChanged = onQueryChanged;
        this.onOpenChanged = onOpenChanged;

        icon.setOnClickListener(v -> {
            if (istAktiv()) {
                clear();
            } else {
                open();
            }
        });
        field.addTextChangedListener(new SimpleWatcher(() -> {
            ruhe.removeCallbacksAndMessages(null);
            ruhe.postDelayed(this::melden, RUHE_MS);
        }));
        // Die Lupe auf der Tastatur beendet die Eingabe, nicht die Suche.
        Keyboard.onCommitAction(field, this::collapse);
        updateIcon();
    }

    /** Der gültige Suchtext; leer, wenn nicht gesucht wird. */
    String query() {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }

    /**
     * Wirkt gerade eine Suche? Das Feld kann dabei eingeklappt sein — genau dafür gibt es das
     * durchgestrichene Symbol.
     */
    boolean istAktiv() {
        return field.getVisibility() == View.VISIBLE || !query().isEmpty();
    }

    /** Steht das Feld offen? Für die Zurück-Taste, die es zuerst schließen soll. */
    boolean istOffen() {
        return field.getVisibility() == View.VISIBLE;
    }

    /** Klappt das Suchfeld auf und holt die Tastatur dazu. */
    void open() {
        title.setVisibility(View.GONE);
        field.setVisibility(View.VISIBLE);
        Keyboard.show(field);
        updateIcon();
        onOpenChanged.accept(true);
    }

    /**
     * Klappt das Feld ein, <b>ohne</b> den Suchtext anzurühren: Der Kontoname steht wieder da, die
     * Liste bleibt eingeengt.
     */
    void collapse() {
        Keyboard.hide(field);
        field.clearFocus();
        field.setVisibility(View.GONE);
        title.setVisibility(View.VISIBLE);
        updateIcon();
        onOpenChanged.accept(false);
    }

    /** Löscht die Suche und klappt ein – danach gilt wieder allein, was im Trichter steht. */
    void clear() {
        boolean hatteText = !query().isEmpty();
        clearSilently();
        if (hatteText) {
            // Sofort, nicht entprellt: Hier wartet niemand auf weitere Tasten.
            melden();
        }
    }

    /**
     * Wie {@link #clear()}, aber ohne Meldung an die Maske.
     *
     * <p>Für „Zurücksetzen" im Trichter: Das räumt <em>alle</em> Filter und filtert danach selbst neu.
     * Eine Meldung von hier liefe mittendrin — auf einem Stand, in dem die Live-Suche schon weg ist
     * und Kategorie, Betrag und Zeitraum noch stehen. Sichtbar wäre das kaum, aber es wäre ein
     * Durchlauf über alle Buchungen für ein Bild, das niemand sehen soll.</p>
     */
    void clearSilently() {
        field.setText("");
        // Nach dem Setzen, nicht davor: setText weckt den Textwächter, der prompt einen neuen Lauf
        // einreiht. Andersherum war es still genau eine Zeile lang.
        ruhe.removeCallbacksAndMessages(null);
        collapse();
    }

    /**
     * Stellt Feld und Text nach einer Drehung wieder her.
     *
     * <p>Ohne Meldung an die Maske: Die filtert nach dem Neuaufbau ohnehin, und ein zusätzlicher
     * Durchlauf mitten im {@code onCreate} liefe auf einer Liste, die noch gar nicht geladen ist.</p>
     */
    void restore(String text, boolean offen) {
        field.setText(text == null ? "" : text);
        // Siehe clearSilently: Sonst löste jede Drehung einen Filterlauf aus – auf einer Liste, die
        // zu diesem Zeitpunkt noch leer ist.
        ruhe.removeCallbacksAndMessages(null);
        if (offen) {
            open();
        } else {
            // Ausdrücklich einklappen statt nur das Symbol nachzuziehen: Die Methode soll einen Zustand
            // herstellen, nicht einen annehmen. Beim Neuaufbau nach einer Drehung ist das Feld ohnehin
            // zu – aber darauf soll sich niemand verlassen müssen, der sie später anderswo ruft.
            collapse();
        }
    }

    /** Hängt an der Maske: beim Verlassen keine Nachzügler mehr auslösen. */
    void detach() {
        ruhe.removeCallbacksAndMessages(null);
    }

    private void melden() {
        updateIcon();
        onQueryChanged.run();
    }

    private void updateIcon() {
        icon.setImageResource(istAktiv() ? R.drawable.ic_search_off : R.drawable.ic_search);
    }
}
