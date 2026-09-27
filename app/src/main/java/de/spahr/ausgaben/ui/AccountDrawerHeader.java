package de.spahr.ausgaben.ui;

import android.app.Activity;
import android.content.Intent;
import android.view.View;

import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Kopf der Kontenschublade: Auswahl der Kontengruppe, Sprung in die Kontenverwaltung und das flüchtige
 * Suchfeld. Hauptansicht und Depot-Ansicht teilen sich denselben Kopf – deshalb liegt die Verdrahtung
 * hier und nicht in den Activities.
 *
 * <p>Die Überschrift bleibt „Konten"; welche Gruppe gerade gilt, steht in der obersten Zeile der Liste
 * anstelle von „Alle Konten". Die gewählte Gruppe ist eine app-weite Einstellung.</p>
 */
public class AccountDrawerHeader {

    /** Meldet die gerade gültige Gruppe samt der Konten, auf die sie einschränkt. */
    public interface Listener {
        /**
         * @param groupId  gewählte Gruppe, 0 = alle Konten
         * @param label    Beschriftung der Gruppe (bzw. „Alle Konten")
         * @param accounts Konten der Gruppe; leer, wenn keine Gruppe gewählt ist (= keine Einschränkung)
         */
        void onGroupChanged(long groupId, String label, List<String> accounts);
    }

    private final Activity activity;
    private final Repository repository;
    private final SettingsStore settings;
    private final AccountDrawerAdapter adapter;
    private final Listener listener;
    private final EditText search;
    private final TextView title;
    private ImageView searchIcon;
    /** true, sobald die Tastatur zur laufenden Suche tatsächlich zu sehen war. */
    private boolean imeWasVisible;
    /** Wiederverwendet, weil die Messung bei jedem Layout-Durchgang läuft. */
    private final android.graphics.Rect visibleFrame = new android.graphics.Rect();

    public AccountDrawerHeader(Activity activity, Repository repository, SettingsStore settings,
                               AccountDrawerAdapter adapter, Listener listener) {
        this.activity = activity;
        this.repository = repository;
        this.settings = settings;
        this.adapter = adapter;
        this.listener = listener;
        this.search = activity.findViewById(R.id.accountSearch);
        this.title = activity.findViewById(R.id.accountGroupTitle);

        if (title != null) {
            // Aufgeklappt wird unter der Überschrift, breit aber wie die ganze Schublade.
            title.setOnClickListener(v -> AccountGroupPicker.show(activity, title,
                    activity.findViewById(R.id.drawerPanel), repository, settings, this::reload));
        }
        ImageView manage = activity.findViewById(R.id.manageAccounts);
        if (manage != null) {
            manage.setOnClickListener(v ->
                    activity.startActivity(new Intent(activity, AccountManageActivity.class)));
        }
        this.searchIcon = activity.findViewById(R.id.accountSearchIcon);
        View searchButton = activity.findViewById(R.id.accountSearchButton);
        if (searchButton != null) {
            // Ein Tipp hat immer dieselbe Bedeutung: läuft eine Suche, hebt er sie auf, sonst beginnt er eine.
            searchButton.setOnClickListener(v -> {
                if (isSearching()) {
                    clearSearch();
                } else {
                    startSearch();
                }
            });
        }
        if (search != null) {
            search.addTextChangedListener(new SimpleWatcher(
                    () -> adapter.setQuery(search.getText().toString())));
            // Fertig-Taste der Tastatur: Eingabefeld einklappen, Filter bleibt.
            search.setOnEditorActionListener((v, actionId, event) -> {
                collapseInput();
                return true;
            });
            watchKeyboard();
        }
    }

    /**
     * Geht die Tastatur zu – auch über die Zurück-Geste, bei der die Eingabe ihren Fokus behält –, klappt
     * das Eingabefeld ein. Der eingegebene Begriff <b>bleibt</b> dabei wirksam: die Kontenliste zeigt
     * weiterhin nur die Treffer, und die Lupe wechselt zum Zeichen „Suche aufheben".
     *
     * <p>{@link #imeWasVisible} verhindert, daß die Meldung „Tastatur unsichtbar", die unmittelbar nach
     * dem Antippen der Lupe noch eintrudelt, die gerade begonnene Suche sofort wieder abräumt.</p>
     */
    private void watchKeyboard() {
        final View root = activity.getWindow().getDecorView();
        // Gemessen wird am sichtbaren Fensterausschnitt statt an den Fenster-Einfassungen: die Schublade
        // liegt in einem DrawerLayout, das die Einfassungen selbst verarbeitet, und dort kam die Meldung
        // „Tastatur zu" nicht verlässlich an.
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            root.getWindowVisibleDisplayFrame(visibleFrame);
            int height = root.getHeight();
            if (height <= 0) {
                return;
            }
            boolean keyboard = height - visibleFrame.height() > height / 4;
            if (keyboard) {
                imeWasVisible = true;
            } else if (imeWasVisible) {
                imeWasVisible = false;
                root.post(this::collapseInput);
            }
        });
    }

    /** Läuft gerade eine Suche – entweder mit offenem Eingabefeld oder als noch wirksamer Filter? */
    private boolean isSearching() {
        return search != null
                && (search.getVisibility() == View.VISIBLE || search.getText().length() > 0);
    }

    /** Blendet das Eingabefeld an der Stelle der Überschrift ein und öffnet die Tastatur. */
    private void startSearch() {
        if (search == null || title == null) {
            return;
        }
        imeWasVisible = false;
        title.setVisibility(View.GONE);
        setSpacerVisible(false);
        search.setVisibility(View.VISIBLE);
        Keyboard.show(search);
        updateSearchIcon();
    }

    /**
     * Klappt nur das Eingabefeld ein und gibt der Überschrift ihren Platz zurück. Ein leerer Begriff
     * beendet die Suche gleich ganz – es gäbe nichts zu behalten.
     */
    private void collapseInput() {
        if (search == null || title == null) {
            return;
        }
        hideKeyboard();
        search.clearFocus();
        search.setVisibility(View.GONE);
        title.setVisibility(View.VISIBLE);
        setSpacerVisible(true);
        updateSearchIcon();
    }

    /**
     * Beendet die Suche vollständig: Eingabe verworfen, Filter weg, Überschrift wieder da. Gerufen beim
     * erneuten Druck auf die Lupe, bei der Kontowahl, beim Schließen der Schublade und beim Verlassen
     * der App.
     */
    public void clearSearch() {
        if (search == null || title == null) {
            return;
        }
        search.setText(""); // löst den Beobachter aus und hebt damit den Filter auf
        collapseInput();
    }

    /** Die Lupe zeigt an, ob die Kontenliste gerade gefiltert ist – und was ein Tipp bewirkt. */
    private void updateSearchIcon() {
        if (searchIcon != null) {
            searchIcon.setImageResource(isSearching()
                    ? R.drawable.ic_search_off : R.drawable.ic_search);
        }
    }

    /** Der Platzhalter hinter der Überschrift weicht während der Suche dem Eingabefeld. */
    private void setSpacerVisible(boolean visible) {
        View spacer = activity.findViewById(R.id.accountTitleSpacer);
        if (spacer != null) {
            spacer.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void hideKeyboard() {
        Keyboard.hide(search);
    }

    /**
     * Lädt Gruppen, Kontenart-Reihenfolge und Konten neu und schiebt sie in den Adapter. Existiert die
     * gespeicherte Gruppe nicht mehr (etwa nach einer Wiederherstellung), fällt die Auswahl auf „alle".
     */
    public void reload() {
        final long wanted = settings.getAccountGroup();
        repository.getAccountGroup(wanted, group -> {
            final long groupId = group == null ? 0L : group.id;
            if (wanted != groupId) {
                settings.setAccountGroup(groupId);
            }
            final String label = group == null
                    ? activity.getString(R.string.account_all) : group.name;
            adapter.setAllLabel(label);
            repository.getAccountKindOrder(adapter::setKindOrder);
            repository.getAccountsGrouped(groupId, g -> {
                adapter.setAccounts(g.assets, g.liabilities);
                // Depots funktionieren nur im kmy-Modus; im CSV-Modus bleiben sie auch dann verborgen,
                // wenn noch Alt-Daten aus einer früheren kmy-Nutzung in der DB liegen.
                adapter.setDepots(settings.isKmyMode() ? g.depots : java.util.Collections.emptyList());
            });
            repository.getAccountNamesInGroup(groupId,
                    names -> listener.onGroupChanged(groupId, label, names));
        });
    }

}
