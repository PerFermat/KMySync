package de.spahr.ausgaben.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.PayeeCorrection;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.settings.PlacesStore;
import de.spahr.ausgaben.settings.SettingsStore;

/** Formular zum Anlegen/Ändern/Löschen eines Alias mit allen Feldern (deckt alle Buchungsarten ab). */
public class AliasEditActivity extends LocalizedActivity {

    /** Zu bearbeitende Alias-ID; fehlt/−1 = neuer Alias. */
    public static final String EXTRA_ALIAS_ID = "alias_id";

    private Repository repository;
    private MaterialToolbar toolbar;
    private TextInputEditText editSpoken;
    private MaterialAutoCompleteTextView editCorrected;
    private MaterialAutoCompleteTextView editType;
    private MaterialAutoCompleteTextView editAccount;
    private MaterialAutoCompleteTextView editCatExpense1;
    private MaterialAutoCompleteTextView editCatExpense2;
    private MaterialAutoCompleteTextView editCatIncome1;
    private MaterialAutoCompleteTextView editCatIncome2;
    private MaterialAutoCompleteTextView editFrom;
    private MaterialAutoCompleteTextView editTo;
    private MaterialAutoCompleteTextView editPlace;
    private MaterialAutoCompleteTextView editFromPlace;
    private MaterialAutoCompleteTextView editToPlace;
    private View placeLayout;
    private View fromPlaceLayout;
    private View toPlaceLayout;
    private PlacesStore placesStore;
    private com.google.android.material.materialswitch.MaterialSwitch switchPreferred;
    private android.widget.TextView textBand;
    private com.google.android.material.slider.RangeSlider sliderBand;
    /** Bisherige Beträge des Zielempfängers, aufsteigend – Beschriftung und Ränge des Reglers. */
    private long[] bandAmounts = new long[0];
    private MaterialButton btnDelete;
    private View gpsSection;
    private android.widget.LinearLayout gpsContainer;
    private ActivityResultLauncher<Intent> mapLauncher;
    /** Die auf das Karten-Ergebnis wartenden Felder (Zeile, deren Karten-Knopf gedrückt wurde). */
    private TextInputEditText pendingMapLat;
    private TextInputEditText pendingMapLon;
    private final Set<String> knownAccountNames = new HashSet<>();
    private CategoryFilterAdapter expenseCategoryAdapter;
    private CategoryFilterAdapter incomeCategoryAdapter;

    // ---- Stichwörter (siehe BookingTags) ----
    private View rowTags;
    private android.widget.TextView textTags;
    /** Die Stichwörter dieses Alias; sie belegen eine neue Buchung dieses Empfängers vor. */
    private String aliasTags = "";
    /** Die in KMyMoney vorhandenen Stichwörter – nur daraus lässt sich wählen; leer = Zeile aus. */
    private java.util.List<String> knownTagNames = new ArrayList<>();

    /** Beim Bearbeiten geladener Alias (behält id/createdAt); null = neu. */
    private PayeeCorrection loaded;

    /** Bis hierhin nennt die Stichwort-Zeile die Namen; darüber nur noch ihre Anzahl. */
    private static final int TAGS_LABEL_MAX = 40;

    /** Reihenfolge passend zu {@link #typeLabels}. */
    private static final String[] TYPE_VALUES = {
            Repository.VOICE_TYPE_EXPENSE, Repository.VOICE_TYPE_INCOME, Repository.VOICE_TYPE_TRANSFER};
    private String[] typeLabels;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alias_edit);
        repository = new Repository(this);

        toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        editSpoken = findViewById(R.id.editAliasSpoken);
        editCorrected = findViewById(R.id.editAliasCorrected);
        editType = findViewById(R.id.editAliasType);
        editAccount = findViewById(R.id.editAliasAccount);
        editCatExpense1 = findViewById(R.id.editAliasCatExpense1);
        editCatExpense2 = findViewById(R.id.editAliasCatExpense2);
        editCatIncome1 = findViewById(R.id.editAliasCatIncome1);
        editCatIncome2 = findViewById(R.id.editAliasCatIncome2);
        editFrom = findViewById(R.id.editAliasFrom);
        editTo = findViewById(R.id.editAliasTo);
        editPlace = findViewById(R.id.editAliasPlace);
        editFromPlace = findViewById(R.id.editAliasFromPlace);
        editToPlace = findViewById(R.id.editAliasToPlace);
        placeLayout = findViewById(R.id.aliasPlaceLayout);
        fromPlaceLayout = findViewById(R.id.aliasFromPlaceLayout);
        toPlaceLayout = findViewById(R.id.aliasToPlaceLayout);
        placesStore = new PlacesStore(this);
        switchPreferred = findViewById(R.id.switchAliasPreferred);
        rowTags = findViewById(R.id.rowAliasTags);
        textTags = findViewById(R.id.textAliasTags);
        findViewById(R.id.btnAliasTagsEdit).setOnClickListener(v ->
                TagsDialog.show(this, knownTagNames, null, aliasTags, tags -> {
                    aliasTags = tags;
                    updateTagsRow();
                }));
        findViewById(R.id.btnAliasTagsClear).setOnClickListener(v -> {
            aliasTags = "";
            updateTagsRow();
        });
        repository.getTagNames(names -> {
            // Ohne bekannte Stichwörter (CSV-Betrieb, noch kein Abgleich) bleibt die Zeile weg.
            knownTagNames = names == null ? new ArrayList<>() : names;
            updateTagsRow();
        });
        btnDelete = findViewById(R.id.btnDeleteAlias);

        textBand = findViewById(R.id.textAliasBand);
        sliderBand = findViewById(R.id.sliderAliasBand);

        // Ort-Felder folgen dem jeweils getippten Konto und erscheinen nur, wenn das Konto Orte hat.
        editAccount.addTextChangedListener(afterText(() ->
                setupPlaceField(editPlace, placeLayout, text(editAccount))));
        editFrom.addTextChangedListener(afterText(() ->
                setupPlaceField(editFromPlace, fromPlaceLayout, text(editFrom))));
        editTo.addTextChangedListener(afterText(() ->
                setupPlaceField(editToPlace, toPlaceLayout, text(editTo))));

        // Standort-Bereich nur bei aktiviertem GPS zeigen.
        gpsSection = findViewById(R.id.aliasGpsSection);
        gpsContainer = findViewById(R.id.aliasGpsContainer);
        boolean gps = new SettingsStore(this).isGpsEnabled();
        gpsSection.setVisibility(gps ? View.VISIBLE : View.GONE);

        mapLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null
                            && pendingMapLat != null && pendingMapLon != null) {
                        double lat = result.getData().getDoubleExtra(MapPickerActivity.EXTRA_LAT, 0);
                        double lon = result.getData().getDoubleExtra(MapPickerActivity.EXTRA_LON, 0);
                        pendingMapLat.setText(formatCoord(lat));
                        pendingMapLon.setText(formatCoord(lon));
                    }
                    pendingMapLat = null;
                    pendingMapLon = null;
                });
        ((MaterialButton) findViewById(R.id.btnAliasGpsAdd)).setOnClickListener(v -> addGpsRow(null, null));

        ((MaterialButton) findViewById(R.id.btnSaveAlias)).setOnClickListener(v -> save());
        btnDelete.setOnClickListener(v -> confirmDelete());

        typeLabels = new String[]{
                getString(R.string.alias_type_expense),
                getString(R.string.alias_type_income),
                getString(R.string.alias_type_transfer)};
        PickerAdapters.plain(editType, java.util.Arrays.asList(typeLabels));
        selectType(Repository.VOICE_TYPE_EXPENSE);

        // Erst jetzt, denn der Regler fragt über currentType() die eben gesetzten Typ-Beschriftungen ab.
        setupBandSlider();
        editCorrected.addTextChangedListener(afterText(this::refreshBandAmounts));
        editType.addTextChangedListener(afterText(this::refreshBandAmounts));
        refreshBandAmounts();

        repository.getAccountNames(names -> {
            knownAccountNames.clear();
            for (String name : names) {
                if (name != null) {
                    knownAccountNames.add(name.trim().toLowerCase(Locale.ROOT));
                }
            }
            PickerAdapters.accounts(repository, names, editAccount, editFrom, editTo);
        });

        // Kategorie-Felder wie im Buchungseditor: gruppierte Baum-Anzeige (Überschrift + Einrückung).
        repository.getCategoriesGrouped(g -> {
            expenseCategoryAdapter = new CategoryFilterAdapter(this, null,
                    getString(R.string.category_group_expense), g.expense, null, new ArrayList<>());
            incomeCategoryAdapter = new CategoryFilterAdapter(this, null,
                    null, new ArrayList<>(), getString(R.string.category_group_income), g.income);
            PickerAdapters.categories(editCatExpense1, expenseCategoryAdapter);
            PickerAdapters.categories(editCatExpense2, expenseCategoryAdapter);
            PickerAdapters.categories(editCatIncome1, incomeCategoryAdapter);
            PickerAdapters.categories(editCatIncome2, incomeCategoryAdapter);

        });

        long id = getIntent().getLongExtra(EXTRA_ALIAS_ID, -1);
        if (id >= 0) {
            repository.getAlias(id, this::bind);
        } else {
            toolbar.setTitle(R.string.alias_new_title);
            btnDelete.setVisibility(android.view.View.GONE);
        }
    }

    private void bind(PayeeCorrection a) {
        if (a == null) {
            finish();
            return;
        }
        loaded = a;
        toolbar.setTitle(R.string.alias_edit_title);
        btnDelete.setVisibility(android.view.View.VISIBLE);
        editSpoken.setText(a.spoken);
        editCorrected.setText(a.corrected, false);
        selectType(a.type);
        editAccount.setText(a.account, false);
        editCatExpense1.setText(a.catExpense1, false);
        editCatExpense2.setText(a.catExpense2, false);
        editCatIncome1.setText(a.catIncome1, false);
        editCatIncome2.setText(a.catIncome2, false);
        editFrom.setText(a.fromAccount, false);
        editTo.setText(a.toAccount, false);
        // Ort-Dropdowns für die geladenen Konten aufbauen, dann die Alias-Orte setzen.
        setupPlaceField(editPlace, placeLayout, a.account);
        setupPlaceField(editFromPlace, fromPlaceLayout, a.fromAccount);
        setupPlaceField(editToPlace, toPlaceLayout, a.toAccount);
        editPlace.setText(a.place, false);
        editFromPlace.setText(a.fromPlace, false);
        editToPlace.setText(a.toPlace, false);
        switchPreferred.setChecked(a.preferred);
        aliasTags = a.tags == null ? "" : a.tags;
        updateTagsRow();
        sliderBand.setValues(Math.min(a.pctLow, a.pctHigh), Math.max(a.pctLow, a.pctHigh));
        refreshBandAmounts();
        gpsContainer.removeAllViews();
        for (double[] p : a.gpsPoints()) {
            addGpsRow(p[0], p[1]);
        }
    }

    /**
     * Der Regler läuft in <b>Rängen</b> (0–100 %), beschriftet werden die zugehörigen <b>Beträge</b> –
     * dieselbe Trennung wie bei den Betragsfiltern der Auswertung ({@link AmountRange}). So zieht ein
     * einzelner Ausreißer das Band nicht auf, und man sieht trotzdem, welche Spanne man einstellt.
     */
    private void setupBandSlider() {
        sliderBand.setLabelFormatter(value -> de.spahr.ausgaben.settings.MoneyFormat.plain(
                de.spahr.ausgaben.util.Quantile.valueAt(bandAmounts, value)));
        sliderBand.setValues(de.spahr.ausgaben.db.PayeeAmounts.DEFAULT_LOW,
                de.spahr.ausgaben.db.PayeeAmounts.DEFAULT_HIGH);
        sliderBand.addOnChangeListener((s, value, fromUser) -> updateBandLabel());
    }

    /** Holt die Beträge des Zielempfängers für die aktuell gewählte Buchungsart. */
    private void refreshBandAmounts() {
        String payee = text(editCorrected);
        String type = currentType();
        if (payee.isEmpty()) {
            bandAmounts = new long[0];
            updateBandLabel();
            return;
        }
        final String key = payee.toLowerCase(Locale.ROOT) + "|" + type;
        repository.getPayeeAmounts(payee, type, amounts -> {
            // Die Antwort kommt später; inzwischen kann etwas anderes im Feld stehen.
            if (!key.equals(text(editCorrected).toLowerCase(Locale.ROOT) + "|" + currentType())) {
                return;
            }
            bandAmounts = amounts == null ? new long[0] : amounts;
            updateBandLabel();
        });
    }

    /** Zeigt die eingestellte Spanne als Beträge – oder den Hinweis, daß es zu wenige Buchungen sind. */
    private void updateBandLabel() {
        boolean genug = bandAmounts.length >= de.spahr.ausgaben.db.PayeeAmounts.MIN_COUNT;
        sliderBand.setEnabled(genug);
        if (!genug) {
            textBand.setText(getString(R.string.alias_band_too_few,
                    de.spahr.ausgaben.db.PayeeAmounts.MIN_COUNT));
            return;
        }
        float low = sliderBand.getValues().get(0);
        float high = sliderBand.getValues().get(1);
        textBand.setText(getString(R.string.alias_band_range,
                de.spahr.ausgaben.settings.MoneyFormat.plain(
                        de.spahr.ausgaben.util.Quantile.valueAt(bandAmounts, low)),
                de.spahr.ausgaben.settings.MoneyFormat.plain(
                        de.spahr.ausgaben.util.Quantile.valueAt(bandAmounts, high)),
                Math.round(low), Math.round(high)));
    }

    /** Die Stichwort-Zeile: Namen, solange sie passen, sonst deren Anzahl. */
    private void updateTagsRow() {
        if (rowTags == null) {
            return;
        }
        rowTags.setVisibility(knownTagNames.isEmpty() ? View.GONE : View.VISIBLE);
        String label = de.spahr.ausgaben.db.BookingTags.label(aliasTags, TAGS_LABEL_MAX);
        textTags.setText(getString(R.string.tags_row,
                label.isEmpty() ? getString(R.string.tags_none) : label));
    }

    private void save() {
        // Erst die Suche in allen Vorschlagsfeldern beenden: der Knopf nimmt dem Feld nicht
        // zwangsläufig den Fokus, und ein Feld mitten in der Suche ist leer.
        PickerBehaviour.settleAll(getWindow().getDecorView());
        String spoken = text(editSpoken);
        String corrected = text(editCorrected);
        if (spoken.isEmpty() || corrected.isEmpty()) {
            Toast.makeText(this, R.string.alias_error_required, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!validateKnownSelections()) {
            return;
        }

        PayeeCorrection a = loaded != null ? loaded : new PayeeCorrection();
        a.spoken = spoken;
        a.corrected = corrected;
        a.type = currentType();
        a.account = text(editAccount);
        a.catExpense1 = text(editCatExpense1);
        a.catExpense2 = text(editCatExpense2);
        a.catIncome1 = text(editCatIncome1);
        a.catIncome2 = text(editCatIncome2);
        // Die Ausgabe-Felder bieten nur den Ausgabenbaum an, die Einnahme-Felder nur den
        // Einnahmenbaum, und validate() hat das eben geprüft – die Seite ist hier die des Feldes.
        a.catExpense1IsIncome = a.catExpense1.isEmpty() ? null : Boolean.FALSE;
        a.catExpense2IsIncome = a.catExpense2.isEmpty() ? null : Boolean.FALSE;
        a.catIncome1IsIncome = a.catIncome1.isEmpty() ? null : Boolean.TRUE;
        a.catIncome2IsIncome = a.catIncome2.isEmpty() ? null : Boolean.TRUE;
        a.fromAccount = text(editFrom);
        a.toAccount = text(editTo);
        a.place = text(editPlace);
        a.fromPlace = text(editFromPlace);
        a.toPlace = text(editToPlace);
        a.preferred = switchPreferred.isChecked();
        a.tags = aliasTags;
        a.pctLow = sliderBand.getValues().get(0);
        a.pctHigh = sliderBand.getValues().get(1);
        // Standorte aus den Zeilen übernehmen (nur Zeilen mit gültiger Breite UND Länge).
        List<double[]> points = new ArrayList<>();
        for (int i = 0; i < gpsContainer.getChildCount(); i++) {
            View row = gpsContainer.getChildAt(i);
            Double lat = parseCoord(text((TextInputEditText) row.findViewById(R.id.gpsRowLat)));
            Double lon = parseCoord(text((TextInputEditText) row.findViewById(R.id.gpsRowLon)));
            if (lat != null && lon != null) {
                points.add(new double[]{lat, lon});
            }
        }
        a.setGpsPoints(points);
        repository.saveAlias(a);
        Toast.makeText(this, R.string.alias_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    /**
     * Prüft, ob die eingetippten Konten/Kategorien wirklich existieren. Die Meldung nennt den beanstandeten
     * Namen – ein unbekannter Name ist etwas anderes als ein leeres Feld, und ohne den Namen weiß man bei
     * vier Kategoriefeldern nicht, welches gemeint ist.
     */
    private boolean validateKnownSelections() {
        if (!text(editAccount).isEmpty() && !isKnownAccount(text(editAccount))) {
            return unknownValue(R.string.alias_unknown_account, text(editAccount));
        }
        if (!text(editFrom).isEmpty() && !isKnownAccount(text(editFrom))) {
            return unknownValue(R.string.alias_unknown_account, text(editFrom));
        }
        if (!text(editTo).isEmpty() && !isKnownAccount(text(editTo))) {
            return unknownValue(R.string.alias_unknown_account, text(editTo));
        }
        if (!isKnownCategory(editCatExpense1, expenseCategoryAdapter)) {
            return unknownValue(R.string.alias_unknown_category, text(editCatExpense1));
        }
        if (!isKnownCategory(editCatExpense2, expenseCategoryAdapter)) {
            return unknownValue(R.string.alias_unknown_category, text(editCatExpense2));
        }
        if (!isKnownCategory(editCatIncome1, incomeCategoryAdapter)) {
            return unknownValue(R.string.alias_unknown_category, text(editCatIncome1));
        }
        if (!isKnownCategory(editCatIncome2, incomeCategoryAdapter)) {
            return unknownValue(R.string.alias_unknown_category, text(editCatIncome2));
        }
        return true;
    }

    /** Meldet einen unbekannten Namen und lehnt das Speichern ab (immer {@code false}). */
    private boolean unknownValue(int messageRes, String value) {
        Toast.makeText(this, getString(messageRes, value), Toast.LENGTH_LONG).show();
        return false;
    }

    private boolean isKnownAccount(String account) {
        return account != null && knownAccountNames.contains(account.trim().toLowerCase(Locale.ROOT));
    }

    private boolean isKnownCategory(MaterialAutoCompleteTextView field, CategoryFilterAdapter adapter) {
        String value = text(field);
        return value.isEmpty() || (adapter != null && adapter.containsCategory(value));
    }

    private void confirmDelete() {
        if (loaded == null) {
            return;
        }
        AppDialog.destructive(this)
                .setTitle(R.string.alias_delete_confirm_title)
                .setMessage(R.string.alias_delete_confirm_message)
                .setPositiveButton(R.string.delete, (d, w) -> repository.deleteAlias(loaded.id, () -> {
                    Toast.makeText(this, R.string.alias_deleted, Toast.LENGTH_SHORT).show();
                    finish();
                }))
                .show();
    }

    /** Fügt eine Koordinaten-Zeile (Breite/Länge + Karte + Minus) hinzu; Werte optional vorbelegt. */
    private void addGpsRow(Double lat, Double lon) {
        View row = getLayoutInflater().inflate(R.layout.item_alias_gps_row, gpsContainer, false);
        TextInputEditText latField = row.findViewById(R.id.gpsRowLat);
        TextInputEditText lonField = row.findViewById(R.id.gpsRowLon);
        if (lat != null && lon != null) {
            latField.setText(formatCoord(lat));
            lonField.setText(formatCoord(lon));
        }
        row.findViewById(R.id.btnGpsRowMap).setOnClickListener(v -> openMap(latField, lonField));
        row.findViewById(R.id.btnGpsRowRemove).setOnClickListener(v -> gpsContainer.removeView(row));
        gpsContainer.addView(row);
    }

    /** Öffnet die Karten-Auswahl für eine Zeile, zentriert auf deren aktuelle Koordinaten (falls vorhanden). */
    private void openMap(TextInputEditText latField, TextInputEditText lonField) {
        pendingMapLat = latField;
        pendingMapLon = lonField;
        Intent i = new Intent(this, MapPickerActivity.class);
        Double lat = parseCoord(text(latField));
        Double lon = parseCoord(text(lonField));
        if (lat != null && lon != null) {
            i.putExtra(MapPickerActivity.EXTRA_LAT, (double) lat);
            i.putExtra(MapPickerActivity.EXTRA_LON, (double) lon);
        }
        mapLauncher.launch(i);
    }

    private static String formatCoord(double v) {
        return String.format(Locale.US, "%.6f", v);
    }

    private static Double parseCoord(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String text(android.widget.EditText field) {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }

    /** Befüllt ein Ort-Dropdown mit den Orten des Kontos und zeigt es nur, wenn das Konto Orte hat. */
    private void setupPlaceField(MaterialAutoCompleteTextView field, View layout, String account) {
        List<String> places = placesStore.getPlaces(account == null ? "" : account.trim());
        PickerAdapters.places(field, places);
        layout.setVisibility(places.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Kleiner TextWatcher, der nur auf Textänderungen reagiert. */
    private android.text.TextWatcher afterText(Runnable action) {
        return new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) { action.run(); }
        };
    }

    private void selectType(String type) {
        int idx = 0; // Standard: Ausgabe
        for (int i = 0; i < TYPE_VALUES.length; i++) {
            if (TYPE_VALUES[i].equals(type)) {
                idx = i;
                break;
            }
        }
        editType.setText(typeLabels[idx], false);
    }

    private String currentType() {
        String label = text(editType);
        for (int i = 0; i < typeLabels.length; i++) {
            if (typeLabels[i].equals(label)) {
                return TYPE_VALUES[i];
            }
        }
        return Repository.VOICE_TYPE_EXPENSE;
    }
}
