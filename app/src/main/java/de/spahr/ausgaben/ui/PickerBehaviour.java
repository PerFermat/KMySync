package de.spahr.ausgaben.ui;

import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.AutoCompleteTextView;
import android.widget.Filterable;
import android.widget.ListAdapter;

import com.google.android.material.textfield.TextInputLayout;

import de.spahr.ausgaben.R;

/**
 * Wie sich ein Vorschlagsfeld anfühlt – Empfänger, Konto, Kategorie, Ort und die Sichtenwahl der
 * Auswertung verhalten sich damit überall gleich. Gesetzt wird es in {@link PickerAdapters}, nicht an
 * den Aufrufstellen.
 *
 * <p>Das Feld ist Anzeige, Auswahlliste und Suchfeld in einem:</p>
 * <ul>
 *   <li>Hineingehen: der bisherige Eintrag wird beiseite gelegt, steht blaß als Platzhalter, und die
 *       ganze Liste klappt auf.</li>
 *   <li>Tippen und Löschen: Live-Suche nach Teiltreffern; ist das Feld wieder leer, steht wieder alles
 *       da.</li>
 *   <li>Verlassen: leeres Feld oder unbekannter Text holen den alten Wert zurück – außer beim
 *       Empfänger, wo unbekannter Text gerade der neue Empfänger ist.</li>
 * </ul>
 */
final class PickerBehaviour {

    /** Was beim Verlassen geschieht, wenn der Text auf keinen Eintrag der Liste paßt. */
    enum Unknown {
        /** Konto, Kategorie, Ort: der Wert muß aus der Liste stammen, sonst kommt der alte zurück. */
        RESTORE,
        /** Empfänger: ein unbekannter Name ist ein neuer Empfänger und bleibt stehen. */
        KEEP
    }

    private PickerBehaviour() {
    }

    /**
     * Macht ein Feld zum Such- und Auswahlfeld.
     *
     * <p>Der beiseite gelegte Wert hängt als Merkmal am Feld und nicht in einer Abschluß-Variablen,
     * damit auch ein Dialog-Knopf {@link #settle(AutoCompleteTextView)} aufrufen kann, bevor er den
     * Text liest – bis dahin hat das Feld den Fokus womöglich noch.</p>
     */
    static void searchable(AutoCompleteTextView field, Unknown unknown) {
        if (field == null) {
            return;
        }
        // Ein gesperrtes Feld (Buchung nur ansehen) bleibt gesperrt, auch wenn seine Liste erst später
        // eintrifft – die Konten warten auf die Datenbank. Sonst holte der neu gesetzte Klick-Zuhörer die
        // Suche zurück, die das Sperren gerade abgeräumt hat: ein Tipp legte den Wert beiseite, und weil
        // das Feld keinen Fokus annimmt, käme er ohne settle() nie zurück – das Feld stünde leer da.
        if (!field.isFocusableInTouchMode()) {
            return;
        }
        boolean schonVerdrahtet = field.getTag(R.id.pickerUnknown) != null;
        field.setTag(R.id.pickerUnknown, unknown);
        if (schonVerdrahtet) {
            // Manche Listen werden nachgereicht (die Orte hängen am gewählten Konto). Die Listener
            // dürfen dabei nicht ein zweites Mal dazukommen – Beobachter am Text sammeln sich sonst an.
            return;
        }

        field.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                open(field);
            } else {
                settle(field);
            }
        });
        // Ein Eintrag aus der Liste wirkt sofort; getippt und stehengelassen wirkt er beim Verlassen
        // (siehe settle). Beide Wege laufen über dieselbe Meldung, damit kein Aufrufer nur den einen
        // von beiden mitbekommt.
        field.setOnItemClickListener((parent, view, position, id) -> {
            Object item = parent.getItemAtPosition(position);
            String gewaehlt = item == null ? "" : convert(field, item);
            field.setTag(R.id.pickerCommitted, null);
            setPlaceholder(field, null);
            field.setText(gewaehlt, false);
            // Den Eintrag selbst merken, nicht nur seinen Text: Zwei Einträge können gleich heißen
            // (dieselbe Kategorie unter Einnahme und unter Ausgabe), und nur der Eintrag weiß, welcher
            // gemeint war.
            field.setTag(R.id.pickerItem, item);
            report(field, gewaehlt);
            // Die Liste klappt beim Antippen von selbst zu; die Tastatur blieb bisher stehen und
            // verdeckte die halbe Seite, obwohl die Eingabe fertig ist.
            Keyboard.hide(field);
        });
        // Die Fertig-Taste beendet die Suche, ohne das Feld zu verlassen.
        field.setOnEditorActionListener((v, actionId, event) -> {
            // IME_NULL: die Enter-Taste einer angesteckten Tastatur meldet sich beim Drücken und beim
            // Loslassen – ohne diese Unterscheidung liefe alles doppelt.
            boolean fertig = actionId == EditorInfo.IME_ACTION_DONE
                    || (actionId == EditorInfo.IME_NULL
                        && (event == null || event.getAction() == KeyEvent.ACTION_UP));
            if (fertig) {
                takeSingleHit(field);
                // settle() hängt sonst am Fokuswechsel – und der Fokus soll hier gerade bleiben. Ohne
                // diesen Aufruf stünde nach der Fertig-Taste ein leeres Feld mit blassem Platzhalter da.
                settle(field);
                Keyboard.hide(field);
            }
            return true; // aufgebraucht: kein Sprung ins nächste Feld, der Fokus bleibt hier
        });
        // Ein Tipp ins bereits gewählte Feld beginnt eine neue Suche; hat es den Fokus noch nicht, hat
        // der Fokus-Listener schon geöffnet und open() tut nichts mehr.
        field.setOnClickListener(v -> open(field));

        // Löscht man den Suchbegriff ganz, klappt AutoCompleteTextView die Liste zu und behält den
        // zusammengestrichenen Bestand – gerade dann soll aber wieder alles dastehen.
        field.addTextChangedListener(new SimpleWatcher(() -> {
            if (isOpen(field) && field.getText().length() == 0) {
                showAll(field);
            }
        }));
    }

    /**
     * Meldet den gewählten Wert – gleich, ob er aus der Liste kam oder getippt wurde. Das ersetzt
     * {@code setOnItemClickListener}: der feuert nur beim Antippen eines Eintrags, und wer sich allein
     * darauf verläßt, verpaßt den getippten Namen.
     */
    static void onCommitted(AutoCompleteTextView field, Committed action) {
        if (field != null) {
            field.setTag(R.id.pickerAction, action);
        }
    }

    interface Committed {
        void onCommitted(String value);
    }

    /**
     * Der Listeneintrag, der zuletzt in diesem Feld gewählt wurde – angetippt oder als einziger
     * Treffer übernommen; {@code null}, wenn noch keiner. Wer ihn benutzt, prüft, ob er noch zum Text
     * des Feldes gehört: nach einem getippten Wert steht hier noch der frühere.
     */
    static Object pickedItem(AutoCompleteTextView field) {
        return field == null ? null : field.getTag(R.id.pickerItem);
    }

    private static void report(AutoCompleteTextView field, String value) {
        Object action = field.getTag(R.id.pickerAction);
        if (action instanceof Committed) {
            ((Committed) action).onCommitted(value);
        }
    }

    /**
     * Was beim Wählen aus der Liste im Feld landet. Die Kategorien zeigen ihre Unterkategorien nur mit
     * dem Blattnamen an, tragen aber „Haupt:Unter" ein – das entscheidet ihr eigener Suchlauf.
     */
    private static String convert(AutoCompleteTextView field, Object item) {
        android.widget.ListAdapter adapter = field.getAdapter();
        if (adapter instanceof Filterable) {
            return String.valueOf(((Filterable) adapter).getFilter().convertResultToString(item));
        }
        return String.valueOf(item);
    }

    /** Beginnt eine Suche: Wert merken, Feld leeren, ganze Liste zeigen. */
    private static void open(AutoCompleteTextView field) {
        if (isOpen(field)) {
            return; // läuft schon
        }
        field.setTag(R.id.pickerCommitted, field.getText().toString());
        setPlaceholder(field, field.getText().toString());
        field.setText("", false);
        showAll(field);
    }

    /**
     * Bleibt nach dem Tippen genau ein Eintrag in der Liste übrig, ist er gemeint: er wird ins Feld
     * geschrieben, und das anschließende {@link #settle} erkennt ihn als bekannten Wert. So genügt
     * „visa u" statt „Visa Urlaub".
     *
     * <p>Nur bei getipptem Text. Im leeren Feld steht der ganze Bestand, und daß jemand mit einem
     * einzigen Konto die Fertig-Taste drückt, heißt noch nicht, daß er dieses Konto wählen will.</p>
     *
     * <p>Gefragt wird der Adapter, denn dort steht gerade die letzte Trefferliste – dieselbe, die man
     * vor sich sieht. {@link #convert} übersetzt den Eintrag wie beim Antippen: die Kategorien zeigen
     * das Blatt, tragen aber „Haupt:Unter" ein.</p>
     */
    private static void takeSingleHit(AutoCompleteTextView field) {
        if (!isOpen(field) || field.getText().toString().trim().isEmpty()) {
            return;
        }
        ListAdapter adapter = field.getAdapter();
        if (adapter == null || adapter.getCount() != 1) {
            return;
        }
        Object item = adapter.getItem(0);
        if (item == null) {
            return;
        }
        // Nicht jede Zeile ist ein Wert: die Kategorienliste kennt Überschriften und „alle", und die
        // liefern beim Übersetzen nichts. Dann bleibt der getippte Text stehen und settle() entscheidet.
        String value = convert(field, item);
        if (!value.trim().isEmpty()) {
            field.setText(value, false);
            field.setTag(R.id.pickerItem, item);
        }
    }

    /**
     * Beendet eine Suche und entscheidet über den Wert. Mehrfaches Aufrufen schadet nicht – nach dem
     * ersten Mal ist nichts mehr beiseite gelegt.
     */
    static void settle(AutoCompleteTextView field) {
        if (field == null || !isOpen(field)) {
            return;
        }
        String committed = (String) field.getTag(R.id.pickerCommitted);
        String typed = field.getText().toString().trim();
        String known = knownForm(field, typed);

        if (known != null) {
            field.setText(known, false);
        } else if (typed.isEmpty() || field.getTag(R.id.pickerUnknown) == Unknown.RESTORE) {
            field.setText(committed, false);
        } // sonst: unbekannter Empfängername, der bleibt stehen

        field.setTag(R.id.pickerCommitted, null);
        setPlaceholder(field, null);
        field.dismissDropDown();

        String jetzt = field.getText().toString();
        if (!jetzt.equals(committed)) {
            report(field, jetzt);
        }
    }

    /**
     * Beendet die Suche in allen Feldern unterhalb von {@code root}. Dialog-Knöpfe rufen das auf, bevor
     * sie die Felder auslesen: der Knopf nimmt dem Feld nicht zwangsläufig den Fokus, und ein Feld
     * mitten in der Suche ist leer – ohne diesen Aufruf läse der Dialog nichts statt des alten Werts.
     */
    static void settleAll(android.view.View root) {
        if (root instanceof AutoCompleteTextView) {
            settle((AutoCompleteTextView) root);
        } else if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                settleAll(group.getChildAt(i));
            }
        }
    }

    private static boolean isOpen(AutoCompleteTextView field) {
        return field.getTag(R.id.pickerCommitted) != null;
    }

    /**
     * Klappt mit dem vollen Bestand auf.
     *
     * <p>Der Umweg über den Suchlauf ist nötig, weil dieser den Bestand des Adapters selbst
     * zusammenstreicht: nach einer Suche stünden beim nächsten Öffnen sonst nur noch die Treffer von
     * damals da. Er läuft nebenher, deshalb wird erst in seiner Rückmeldung aufgeklappt.</p>
     */
    private static void showAll(AutoCompleteTextView field) {
        ListAdapter adapter = field.getAdapter();
        if (!(adapter instanceof Filterable)) {
            field.showDropDown();
            return;
        }
        ((Filterable) adapter).getFilter().filter("", count -> {
            if (field.isAttachedToWindow() && field.hasWindowFocus() && field.hasFocus()) {
                field.showDropDown();
            }
        });
    }

    /**
     * Der Eintrag der Liste, der so heißt – in der Schreibweise der Liste. {@code null}, wenn es keinen
     * gibt. Gesucht wird im ungefilterten Bestand, denn im Adapter steht gerade nur die letzte
     * Trefferliste.
     */
    private static String knownForm(AutoCompleteTextView field, String text) {
        if (text.isEmpty()) {
            return null;
        }
        ListAdapter adapter = field.getAdapter();
        if (adapter instanceof PickerAdapters.Stock) {
            for (String value : ((PickerAdapters.Stock) adapter).stock()) {
                if (value != null && value.trim().equalsIgnoreCase(text)) {
                    return value;
                }
            }
            return null;
        }
        if (adapter instanceof CategoryFilterAdapter) {
            return ((CategoryFilterAdapter) adapter).knownForm(text);
        }
        for (int i = 0; adapter != null && i < adapter.getCount(); i++) {
            Object item = adapter.getItem(i);
            if (item != null && item.toString().trim().equalsIgnoreCase(text)) {
                return item.toString();
            }
        }
        return null;
    }

    /**
     * Der bisherige Eintrag blaß im leeren Feld: man sieht, was man überschreibt, und was beim Abbruch
     * zurückkommt. Sitzt das Feld – wie überall in dieser App – in einem {@link TextInputLayout},
     * gehört der Platzhalter dorthin.
     */
    private static void setPlaceholder(AutoCompleteTextView field, String text) {
        TextInputLayout layout = layoutOf(field);
        if (layout != null) {
            layout.setPlaceholderText(text == null || text.isEmpty() ? null : text);
        }
    }

    private static TextInputLayout layoutOf(AutoCompleteTextView field) {
        for (android.view.ViewParent p = field.getParent(); p != null; p = p.getParent()) {
            if (p instanceof TextInputLayout) {
                return (TextInputLayout) p;
            }
        }
        return null;
    }
}
