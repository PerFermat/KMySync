package de.spahr.ausgaben.ui;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

/**
 * Die Live-Suche in der Titelzeile der Buchungsliste.
 *
 * <p>Ein Tipp auf den <b>Kontonamen</b>, und er macht einem Suchfeld Platz. Was man tippt, engt die
 * Liste sofort ein. Bis 2.1 führte der einzige Weg dorthin über den Trichter — einen Dialog mit sechs
 * Blöcken und „Übernehmen" ganz unten; für „wo war nochmal diese eine Buchung" waren das fünf
 * Handgriffe.</p>
 *
 * <h2>Die drei Zustände</h2>
 *
 * <ul>
 *   <li><b>Kontoname antippen</b> — Name weg, Feld da, Tastatur auf. Auch bei laufender Suche: Dann
 *       steht der bisherige Suchtext noch darin und läßt sich nachbessern.</li>
 *   <li><b>Tippen</b> — nach kurzer Ruhe filtern (siehe unten).</li>
 *   <li><b>Lupe auf der Tastatur</b> — Feld zu, Kontoname zurück, <em>die Suche bleibt</em>. Der
 *       Suchtext ist ja das Ergebnis, nicht das Feld.</li>
 * </ul>
 *
 * <p><b>Weggeräumt wird hier nicht.</b> Das erledigt das X vor „Filter aktiv (n)" in der Zeile
 * darunter, und zwar für Live-Suche und Trichter gemeinsam — die Zeile meldet beides, also nimmt das
 * X davor auch beides weg. Hier stand einmal eine Lupe vor dem Kontonamen, die bei laufender Suche
 * durchgestrichen war und nur die Live-Suche räumte; in zwei Anläufen (groß und knopfartig, dann
 * klein und hochgestellt) blieb sie ein Fremdkörper in einer Zeile, in der sonst nur ein Name
 * steht.</p>
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
     *                       nicht die Maske, sondern der Kontoname oder die Tastatur umgeschaltet hat.
     */
    BookingSearchBar(TextView title, EditText field, Runnable onQueryChanged,
                     java.util.function.Consumer<Boolean> onOpenChanged) {
        this.title = title;
        this.field = field;
        this.onQueryChanged = onQueryChanged;
        this.onOpenChanged = onOpenChanged;

        // Immer öffnen, auch wenn schon gesucht wird: Dann kommt das Feld mit dem bisherigen Text
        // zurück und läßt sich nachbessern. Räumen tut das X unten in der Trefferzeile.
        title.setOnClickListener(v -> open());
        field.addTextChangedListener(new SimpleWatcher(() -> {
            ruhe.removeCallbacksAndMessages(null);
            ruhe.postDelayed(onQueryChanged, RUHE_MS);
        }));
        // Die Lupe auf der Tastatur beendet die Eingabe, nicht die Suche.
        Keyboard.onCommitAction(field, this::collapse);
    }

    /** Der gültige Suchtext; leer, wenn nicht gesucht wird. */
    String query() {
        return field.getText() == null ? "" : field.getText().toString().trim();
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
        onOpenChanged.accept(false);
    }

    /** Löscht die Suche und klappt ein – danach gilt wieder allein, was im Trichter steht. */
    void clear() {
        boolean hatteText = !query().isEmpty();
        clearSilently();
        if (hatteText) {
            // Sofort, nicht entprellt: Hier wartet niemand auf weitere Tasten.
            onQueryChanged.run();
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
            // Ausdrücklich einklappen: Die Methode soll einen Zustand herstellen, nicht einen annehmen.
            // Beim Neuaufbau nach einer Drehung ist das Feld ohnehin zu – aber darauf soll sich niemand
            // verlassen müssen, der sie später anderswo ruft.
            collapse();
        }
    }

    /** Hängt an der Maske: beim Verlassen keine Nachzügler mehr auslösen. */
    void detach() {
        ruhe.removeCallbacksAndMessages(null);
    }
}
