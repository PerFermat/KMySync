package de.spahr.ausgaben.ui;

import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.BookingTags;
import de.spahr.ausgaben.db.Repository;

/**
 * Die Stichwort-Zeile im Buchungseditor: welche Stichwörter die Buchung trägt, die Vorbelegung aus dem
 * Empfänger und das Auswahlfenster. Ausgelagert aus {@link BookingEditActivity}, um deren Umfang zu
 * verringern – der Code ist dabei unverändert umgezogen.
 */
class TagsRowController {

    /** Was die Zeile von der Buchungsmaske braucht. */
    interface Host {
        /** Reine Ansicht – kann sich nach {@code onCreate} noch ändern, deshalb jedes Mal gefragt. */
        boolean isReadOnly();
    }

    /** Höchstlänge der Stichwort-Beschriftung in der Zeile, danach „…". */
    private static final int TAGS_LABEL_MAX = 40;

    private final AppCompatActivity activity;
    private final Repository repository;
    private final TextView editPayee;
    private final Host host;
    private final View rowTags;
    private final TextView textTags;
    private final ImageButton btnTagsEdit;
    private final ImageButton btnTagsClear;

    /** Stichwörter dieser Buchung, so wie sie gespeichert werden (siehe {@link BookingTags}). */
    private String bookingTags = "";
    /** Die in KMyMoney vorhandenen Stichwörter – nur daraus lässt sich wählen; leer = Zeile aus. */
    private List<String> knownTagNames = new ArrayList<>();
    /** Zu welchem Empfänger schon vorbelegt wurde – spart die Abfrage bei jedem Tastendruck. */
    private String payeeTagKey;
    /**
     * Empfänger (klein), bei dem die Stichwörter von Hand gesetzt wurden. Für ihn schlägt die App
     * nichts mehr vor – sonst käme ein gelöschtes Stichwort beim nächsten Öffnen des Fensters wieder.
     */
    private String tagsEditedForPayee;

    TagsRowController(AppCompatActivity activity, Repository repository, TextView editPayee, Host host) {
        this.activity = activity;
        this.repository = repository;
        this.editPayee = editPayee;
        this.host = host;
        this.rowTags = activity.findViewById(R.id.rowTags);
        this.textTags = activity.findViewById(R.id.textTags);
        this.btnTagsEdit = activity.findViewById(R.id.btnTagsEdit);
        this.btnTagsClear = activity.findViewById(R.id.btnTagsClear);
    }

    /** Die Stichwörter der Buchung, wie sie gespeichert werden. */
    String tags() {
        return bookingTags;
    }

    /** Übernimmt die Stichwörter einer geladenen Buchung/Vorlage und zeigt sie. */
    void set(String tags) {
        bookingTags = tags == null ? "" : tags;
        update();
    }

    /**
     * Die Stichwörter der .kmy sind aus der Datenbank da. Die Liste kommt womöglich später als der
     * vorbelegte Empfänger – dann ist sein Vorspann noch nirgends angekommen; deshalb wird der gemerkte
     * Empfänger vergessen, damit {@link #refreshForPayee()} noch einmal fragt.
     */
    void setKnownTags(List<String> names) {
        knownTagNames = names == null ? new ArrayList<>() : names;
    }

    /** Vergisst, zu welchem Empfänger schon vorbelegt wurde. */
    void forgetPayee() {
        payeeTagKey = null;
    }

    /**
     * Die Stichwort-Zeile. Sie erscheint nur, wenn die App überhaupt Stichwörter aus einer
     * {@code .kmy}-Datei kennt – ohne sie gäbe es nichts zu wählen. In der Ansicht bleibt der Text
     * stehen, die beiden Symbole verschwinden.
     */
    void update() {
        if (rowTags == null) {
            return; // Views noch nicht gebunden
        }
        boolean readOnly = host.isReadOnly();
        boolean show = !knownTagNames.isEmpty() && (!readOnly || !bookingTags.isEmpty());
        rowTags.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) {
            return;
        }
        String label = BookingTags.label(bookingTags, TAGS_LABEL_MAX);
        textTags.setText(activity.getString(R.string.tags_row,
                label.isEmpty() ? activity.getString(R.string.tags_none) : label));
        btnTagsEdit.setVisibility(readOnly ? View.GONE : View.VISIBLE);
        btnTagsClear.setVisibility(readOnly || bookingTags.isEmpty() ? View.GONE : View.VISIBLE);
        btnTagsEdit.setOnClickListener(v -> showTagsDialog());
        btnTagsClear.setOnClickListener(v -> {
            // Ohne Rückfrage – wie das Löschkreuz einer Belegseite; rückgängig durch Verlassen
            // der Maske, ohne zu speichern.
            bookingTags = "";
            noteTagsEdited();
            update();
        });
    }

    /**
     * Das Pop-Up zu den Stichwörtern: oben die vergebenen, jedes einzeln zu löschen, darunter ein
     * Feld zum Hinzufügen. Es verhält sich wie das Konto- und das Kategoriefeld – gesucht wird über
     * Teiltreffer, und was auf keinen Eintrag paßt, wird verworfen: eingebbar ist nur, was es in
     * KMyMoney gibt.
     */
    private void showTagsDialog() {
        // Der Stift nimmt dem Empfängerfeld nicht den Fokus, und ein Feld mitten in der Suche ist leer:
        // ohne dieses settleAll wäre ein nur getippter Empfängername hier noch nicht angekommen.
        PickerBehaviour.settleAll(activity.getWindow().getDecorView());
        final String payee = Ui.text(editPayee).trim();
        if (payee.isEmpty() || knownTagNames.isEmpty()) {
            openTagsDialog(new ArrayList<>());
            return;
        }
        // Erst fragen, dann öffnen. Andersherum stünde das Fenster schon da, wenn die Antwort eintrifft –
        // dann bliebe der Vorspann leer und eine Vorbelegung ginge beim „Fertig" wieder verloren.
        repository.getPayeeTags(payee, suggestion -> {
            applyTagPreset(payee, suggestion);
            openTagsDialog(suggestion.ranked);
        });
    }

    private void openTagsDialog(List<String> lead) {
        TagsDialog.show(activity, knownTagNames, lead, bookingTags, tags -> {
            bookingTags = tags;
            noteTagsEdited();
            update();
        });
    }

    /**
     * Merkt sich, dass die Stichwörter von Hand gesetzt wurden – für <b>diesen</b> Empfänger schlägt
     * die App dann nichts mehr vor. Was gelöscht wurde, bleibt gelöscht; wählen Sie dagegen einen
     * anderen Empfänger, gilt dessen Vorbelegung wieder.
     */
    private void noteTagsEdited() {
        tagsEditedForPayee = Ui.text(editPayee).trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Holt die Stichwörter des gewählten Empfängers und belegt eine <b>neue</b> Buchung damit vor.
     * Gegenstück zur Kategorie-Vorbelegung und am selben Faden aufgehängt – jedem bestätigten Empfänger
     * folgt beides.
     */
    void refreshForPayee() {
        if (host.isReadOnly() || knownTagNames.isEmpty()) {
            return;
        }
        final String payee = Ui.text(editPayee).trim();
        final String key = payee.toLowerCase(Locale.ROOT);
        if (key.equals(payeeTagKey)) {
            return; // derselbe Empfänger – nichts zu tun
        }
        payeeTagKey = key;
        if (payee.isEmpty()) {
            return;
        }
        repository.getPayeeTags(payee, suggestion -> applyTagPreset(payee, suggestion));
    }

    /**
     * Übernimmt die Stichwörter des Empfängers in die Buchung – aber nur, wenn dort noch keine stehen:
     * eine geöffnete Buchung und eine Planung bringen ihre eigenen mit, und die soll ein
     * Empfängerwechsel nicht wegräumen.
     */
    private void applyTagPreset(String payee, de.spahr.ausgaben.db.PayeeTagSuggestion suggestion) {
        if (payee.toLowerCase(Locale.ROOT).equals(tagsEditedForPayee)) {
            return; // hier hat der Nutzer selbst entschieden – auch, wenn er alles gelöscht hat
        }
        if (bookingTags.isEmpty() && !suggestion.preset.isEmpty()) {
            bookingTags = suggestion.preset;
            update();
        }
    }
}
