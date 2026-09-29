package de.spahr.ausgaben.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

import de.spahr.ausgaben.R;

/**
 * Der Filtertrichter der Buchungsliste: Suche, Kategorie, Stichwort, Betrag, Datum, Umkreis. Ausgelagert
 * aus {@link MainActivity}, um deren Umfang zu verringern – der Code ist dabei unverändert umgezogen.
 * Er schreibt die gewählten Kriterien in einen {@link BookingFilterState}; was danach mit der Liste
 * geschieht, entscheidet die Maske ({@link Host#onFilterApplied()}/{@link Host#onFilterReset()}).
 */
final class FilterDialog {

    /** Was der Dialog von der Buchungsliste braucht. */
    interface Host {
        List<String> expenseCategories();

        List<String> incomeCategories();

        /** Die in KMyMoney vorhandenen Stichwörter; leer = das Feld erscheint gar nicht. */
        List<String> knownTagNames();

        /** Vorzeichenbehaftete Beträge der gerade sichtbaren Buchungen (Konto/Gruppe), unsortiert. */
        List<Long> amountsInScope();

        /** Buchungszeitpunkte aller Buchungen – für die Datums-Spanne. */
        List<Long> bookingDates();

        boolean isGpsEnabled();

        boolean hasLocationPermission();

        void requestLocationPermission();

        /** Aktuelle Position „lat, lon" oder {@code null}. */
        String currentCoordinates();

        /** Betrag in der Währung der Ansicht, für die Beschriftung des Reglers. */
        String formatAmount(long signedCents);

        void onFilterApplied();

        void onFilterReset();
    }

    private FilterDialog() {
    }

    static void show(AppCompatActivity activity, BookingFilterState filter, Host host) {
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_filter, null, false);
        TextInputEditText fPayee = view.findViewById(R.id.filterPayee);
        MaterialAutoCompleteTextView fCategory = view.findViewById(R.id.filterCategory);
        ZeroMarkSlider slider = view.findViewById(R.id.filterAmountSlider);
        TextInputEditText fFrom = view.findViewById(R.id.filterAmountFrom);
        TextInputEditText fTo = view.findViewById(R.id.filterAmountTo);
        AmountField.prepareNumber(fFrom);
        AmountField.prepareNumber(fTo);

        fPayee.setText(filter.payee);

        final List<String> catExpense = host.expenseCategories();
        final List<String> catIncome = host.incomeCategories();
        final List<String> knownTagNames = host.knownTagNames();

        // Kategorie-Baum
        final String[] catValue = {filter.category};
        final boolean[] catIsMain = {filter.categoryIsMain};
        // "Alle" (leerer Wert) setzt bewusst keinen Typ – kein Ausschluss.
        final Boolean[] catIsIncome = {filter.category.isEmpty() ? null : filter.categoryIsIncome};
        CategoryFilterAdapter catAdapter = new CategoryFilterAdapter(activity,
                activity.getString(R.string.category_all),
                activity.getString(R.string.category_group_expense), catExpense,
                activity.getString(R.string.category_group_income), catIncome);
        PickerAdapters.categories(fCategory, catAdapter);
        fCategory.setText(filter.category, false);
        // Über PickerBehaviour: die Kategorie kann auch getippt und stehengelassen werden, dann fällt
        // kein Antippen eines Listeneintrags an. Den Text setzt PickerBehaviour selbst.
        PickerBehaviour.onCommitted(fCategory, value -> {
            CategoryFilterAdapter.CatItem it = catAdapter.itemFor(value);
            if (it != null) {
                catValue[0] = it.value;
                catIsMain[0] = it.isMain;
                catIsIncome[0] = it.value.isEmpty() ? null : it.groupIsIncome;
            }
        });

        // Stichwort: dieselbe Bedienung wie das Kategoriefeld, mit „Alle" als erstem Eintrag. Ohne
        // bekannte Stichwörter (CSV-Betrieb, noch kein Abgleich) bleibt das ganze Feld weg.
        View tagLayout = view.findViewById(R.id.filterTagLayout);
        MaterialAutoCompleteTextView fTag = view.findViewById(R.id.filterTag);
        if (knownTagNames.isEmpty()) {
            tagLayout.setVisibility(View.GONE);
        } else {
            tagLayout.setVisibility(View.VISIBLE);
            List<String> tagChoices = new ArrayList<>();
            tagChoices.add(activity.getString(R.string.category_all));
            tagChoices.addAll(knownTagNames);
            PickerAdapters.plainSearchable(fTag, tagChoices);
            fTag.setText(filter.tag, false);
        }

        // Betrag-Range: vorzeichenbehaftete Beträge der gerade sichtbaren Buchungen, sortiert – der
        // Regler läuft über ihre Ränge, nicht über die Beträge (siehe AmountRange).
        List<Long> scope = host.amountsInScope();
        final long[] sortedCents = new long[scope.size()];
        for (int i = 0; i < sortedCents.length; i++) {
            sortedCents[i] = scope.get(i);
        }
        java.util.Arrays.sort(sortedCents);
        final boolean hasRange = sortedCents.length > 1
                && sortedCents[0] < sortedCents[sortedCents.length - 1];
        final AmountRange amountRange = hasRange
                ? AmountRange.attach(slider, fFrom, fTo, sortedCents,
                        filter.amountFrom, filter.amountTo, host::formatAmount)
                : null;
        if (!hasRange) {
            // Kein sinnvoller Bereich (0/1 Buchung oder alle gleich) → deaktivieren.
            slider.setValueFrom(0f);
            slider.setValueTo(1f);
            slider.setValues(0f, 1f);
            slider.setEnabled(false);
            fFrom.setEnabled(false);
            fTo.setEnabled(false);
        }

        // Datums-Range (Slider in Monatsschritten; taggenau direkt im Feld eingebbar).
        com.google.android.material.slider.RangeSlider dateSlider = view.findViewById(R.id.filterDateSlider);
        TextInputEditText dFrom = view.findViewById(R.id.filterDateFrom);
        TextInputEditText dTo = view.findViewById(R.id.filterDateTo);
        List<Long> dates = host.bookingDates();
        long dtMin = Long.MAX_VALUE;
        long dtMax = Long.MIN_VALUE;
        for (long createdAt : dates) {
            dtMin = Math.min(dtMin, createdAt);
            dtMax = Math.max(dtMax, createdAt);
        }
        final MonthRange dateRange;
        if (!dates.isEmpty()) {
            dateRange = MonthRange.attach(dateSlider, dFrom, dTo, dtMin, dtMax, filter.dateFrom, filter.dateTo);
        } else {
            dateRange = null;
            dateSlider.setValueFrom(0f);
            dateSlider.setValueTo(1f);
            dateSlider.setValues(0f, 1f);
            dateSlider.setEnabled(false);
            dFrom.setEnabled(false);
            dTo.setEnabled(false);
        }
        final long dtDataMin = dtMin;
        final long dtDataMax = dtMax;

        // Umkreis: nur bei eingeschaltetem Standort; jeder Tipp schaltet eine Stufe weiter.
        com.google.android.material.button.MaterialButton radius = view.findViewById(R.id.filterRadius);
        final int[] radiusM = {filter.radiusM};
        if (!host.isGpsEnabled()) {
            radius.setVisibility(View.GONE);
        } else {
            setRadiusLabel(activity, radius, radiusM[0]);
            radius.setOnClickListener(v -> {
                radiusM[0] = de.spahr.ausgaben.location.RadiusFilter.next(radiusM[0]);
                setRadiusLabel(activity, radius, radiusM[0]);
                // Ohne Berechtigung gäbe es nie eine Position – einmal danach fragen.
                if (radiusM[0] > 0 && !host.hasLocationPermission()) {
                    host.requestLocationPermission();
                }
            });
        }

        androidx.appcompat.app.AlertDialog dialog = new AppDialog(activity)
                .setTitle(R.string.filter_title)
                .setView(view)
                .setPositiveButton(R.string.filter_apply, (d, w) -> {
                    // Der Knopf nimmt dem Feld nicht zwangsläufig den Fokus; ein Feld mitten in der Suche
                    // ist leer. Erst die Suche beenden, dann lesen.
                    PickerBehaviour.settleAll(view);

                    filter.payee = Ui.text(fPayee).trim();
                    String typedCategory = Ui.text(fCategory).trim();
                    if (!typedCategory.equals(catValue[0])) {
                        catValue[0] = typedCategory;
                        catIsMain[0] = isKnownMainCategory(catExpense, catIncome, typedCategory);
                        catIsIncome[0] = typeForCategory(catExpense, catIncome, typedCategory);
                    }

                    // „Alle" heißt: kein Stichwort gewählt.
                    String tag = Ui.text(fTag).trim();
                    filter.tag = knownTagNames.isEmpty() || tag.equals(activity.getString(R.string.category_all))
                            ? "" : tag;

                    filter.category = catValue[0] == null ? "" : catValue[0].trim();
                    filter.categoryIsMain = catIsMain[0];
                    filter.categoryIsIncome = filter.category.isEmpty() ? null : catIsIncome[0];
                    if (dateRange != null) {
                        long df = dateRange.getFromMillis();
                        long dt = dateRange.getToMillis();
                        if (df <= dtDataMin && dt >= dtDataMax) {
                            filter.dateFrom = null;
                            filter.dateTo = null;
                        } else {
                            filter.dateFrom = df;
                            filter.dateTo = dt;
                        }
                    } else {
                        filter.dateFrom = null;
                        filter.dateTo = null;
                    }
                    if (amountRange != null) {
                        if (amountRange.isFullRange()) {
                            filter.amountFrom = null;
                            filter.amountTo = null;
                        } else {
                            filter.amountFrom = amountRange.getFromCents();
                            filter.amountTo = amountRange.getToCents();
                        }
                    } else {
                        filter.amountFrom = null;
                        filter.amountTo = null;
                    }
                    filter.radiusM = radiusM[0];
                    // Die Position einmal einfrieren: die Liste soll nicht mitwandern, wenn man weitergeht.
                    filter.center = filter.radiusM > 0
                            ? de.spahr.ausgaben.location.Geo.parse(host.currentCoordinates())
                            : null;
                    if (filter.radiusM > 0 && filter.center == null) {
                        Toast.makeText(activity, R.string.filter_radius_no_fix, Toast.LENGTH_SHORT).show();
                    }
                    host.onFilterApplied();
                })
                .setNeutralButton(R.string.filter_reset, (d, w) -> host.onFilterReset())
                .show();

        // Der Dialog ist lang – Suche, Kategorie, Stichwort, Betrag, Datum, Umkreis – und „Übernehmen"
        // sitzt an seinem Ende. Tippt man ins Suchfeld ganz oben, fährt die Tastatur hoch und verdeckt
        // ihn: Für den häufigsten Fall überhaupt, „ich suche einen Namen", waren erst zwei zusätzliche
        // Handgriffe nötig. Zwei Auswege, je nach Absicht:
        //   die Lupe auf der Tastatur übernimmt sofort,
        Keyboard.onCommitAction(fPayee, () ->
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick());
        //   und wer weiterschiebt, will unten etwas einstellen – dann geht sie nur weg.
        Keyboard.hideOnScroll((android.widget.ScrollView) view);
    }

    /** Beschriftet den Umkreis-Knopf mit der eingestellten Stufe („Umkreis aus", „Umkreis 500 m"). */
    private static void setRadiusLabel(AppCompatActivity activity,
                                       com.google.android.material.button.MaterialButton button, int radiusM) {
        button.setText(radiusM <= 0
                ? activity.getString(R.string.filter_radius_off)
                : activity.getString(R.string.filter_radius,
                        de.spahr.ausgaben.location.RadiusFilter.label(radiusM)));
    }

    /** True für bekannte Hauptkategorien; frei getippte Unterkategorien bleiben exakte Filter. */
    private static boolean isKnownMainCategory(List<String> catExpense, List<String> catIncome,
                                               String category) {
        if (category == null || category.trim().isEmpty() || category.contains(":")) {
            return false;
        }
        String c = category.trim();
        return MainActivity.containsIgnoreCase(catExpense, c) || MainActivity.containsIgnoreCase(catIncome, c);
    }

    /** Ermittelt den Kategorie-Typ auch dann, wenn der Text getippt statt aus der Liste gewählt wurde. */
    private static Boolean typeForCategory(List<String> catExpense, List<String> catIncome, String category) {
        if (category == null || category.trim().isEmpty()) {
            return null;
        }
        String c = category.trim();
        if (MainActivity.containsIgnoreCase(catIncome, c)) {
            return Boolean.TRUE;
        }
        if (MainActivity.containsIgnoreCase(catExpense, c)) {
            return Boolean.FALSE;
        }
        return null;
    }
}
