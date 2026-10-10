package de.spahr.ausgaben.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;

import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.statement.AnchorRule;

/**
 * Verwaltet die dynamische Kategorie-/Teilbetrag-Liste (Splitbuchung) im Buchungseditor: fügt Zeilen an,
 * hält genau eine leere Abschlusszeile, koppelt Gesamtbetrag ↔ Teilbeträge und prüft die Gültigkeit
 * (Summe der Teile = Gesamt). Ausgelagert aus {@link BookingEditActivity}, um deren Umfang zu verringern.
 *
 * <p>Der Gesamtbetrag lebt weiterhin im {@code editAmount}-Feld der Activity (auch für Umbuchungen genutzt);
 * dieser Controller hält nur eine Referenz darauf. Bei jeder relevanten Änderung meldet er sich über
 * {@code onChanged} zurück (→ Buttons freischalten).</p>
 */
class SplitRowController {

    /** Einfacher Kategorie/Teilbetrag-Datensatz während der Editor-Eingabe. */
    static final class Part {
        final String category;
        final long cents;
        /** Typ der Kategorie (true=Einnahme, false=Ausgabe, null=unbekannt/frei getippt). */
        final Boolean categoryIsIncome;
        /**
         * Die Beschriftung, unter der dieser Betrag in einer Abrechnung stand; leer, wenn die Zeile
         * nicht von dort stammt. Nur bei Depotbewegungen benutzt (siehe {@code SecurityTxSplit}), bei
         * Geldbuchungen bleibt sie immer leer.
         */
        final String label;
        /**
         * Die erkannte Anker-Regel dieser Zeile — der Vorschlag, den {@link #labelTag} als Text zeigt.
         * {@code null}, wenn keine (mehr) gilt; siehe {@link StatementTemplate.Part#chosenRule}, wohin
         * sie beim Speichern weiterwandert.
         */
        final AnchorRule chosenRule;

        Part(String category, long cents) {
            this(category, cents, null);
        }

        Part(String category, long cents, Boolean categoryIsIncome) {
            this(category, cents, categoryIsIncome, "");
        }

        Part(String category, long cents, Boolean categoryIsIncome, String label) {
            this(category, cents, categoryIsIncome, label, null);
        }

        Part(String category, long cents, Boolean categoryIsIncome, String label, AnchorRule chosenRule) {
            this.category = category;
            this.cents = cents;
            this.categoryIsIncome = categoryIsIncome;
            this.label = label == null ? "" : label;
            this.chosenRule = chosenRule;
        }
    }

    private final LinearLayout container;
    private final TextInputEditText totalField;
    private final LayoutInflater inflater;
    private final boolean readOnly;
    /** Im CSV-Schreibweg entsteht nie eine zweite Kategorie-Zeile, siehe {@link #onSplitCategoryChanged}. */
    private final boolean splitLocked;
    private final Runnable onChanged;

    private CategoryFilterAdapter categoryAdapter;
    private boolean suppressSplitEvents;
    private boolean syncingAmounts;
    private AmountFieldBinder amountBinder;
    /** Stammt der Inhalt der Kategoriezeilen noch allein aus Vorbelegungen? */
    private boolean categoryAuto = true;
    /** Läuft gerade eine Vorbelegung? Dann ist die Änderung kein Handgriff des Nutzers. */
    private boolean prefilling;
    /**
     * Die „Rest-Zeile" bei zuerst eingegebenem Gesamtbetrag: ihr Teilbetrag wurde automatisch belegt und
     * trägt die Differenz zum (vom Nutzer eingegebenen) Gesamtbetrag. Benutzereingaben in anderen Zeilen
     * haben Vorrang – der Gesamtbetrag bleibt fest, diese Zeile zieht nach. {@code null} = Summen-Modus
     * (Gesamt = Summe der Teile), z. B. wenn die Kategorien vor dem Gesamtbetrag eingetragen werden.
     */
    private View autoAmountRow;

    /** Hängt ein Teilbetrag-Feld an die gemeinsame Rechentastatur (von der Activity gesetzt). */
    interface AmountFieldBinder {
        void bind(TextInputLayout layout, TextInputEditText field);
    }

    SplitRowController(LinearLayout container, TextInputEditText totalField, LayoutInflater inflater,
                       boolean readOnly, boolean splitLocked, Runnable onChanged) {
        this.container = container;
        this.totalField = totalField;
        this.inflater = inflater;
        this.readOnly = readOnly;
        this.splitLocked = splitLocked;
        this.onChanged = onChanged;
    }

    /** Setzt den Binder, mit dem jedes Teilbetrag-Feld an die Rechentastatur gebunden wird. */
    void setAmountBinder(AmountFieldBinder binder) {
        this.amountBinder = binder;
    }

    /** Setzt den (gruppierten) Kategorie-Adapter und wendet ihn auf bestehende Zeilen an. */
    void setAdapter(CategoryFilterAdapter adapter) {
        this.categoryAdapter = adapter;
        applyAdapterToRows();
    }

    void applyAdapterToRows() {
        for (int i = 0; i < container.getChildCount(); i++) {
            MaterialAutoCompleteTextView cat = container.getChildAt(i).findViewById(R.id.splitCategory);
            if (cat != null) {
                PickerAdapters.categories(cat, categoryAdapter);
            }
        }
    }

    /** Entfernt alle Zeilen (vor dem Vorbelegen). */
    void clear() {
        autoAmountRow = null;
        container.removeAllViews();
    }

    /** Beim Bulk-Vorbelegen die Zeilen-Events unterdrücken. */
    void setSuppressEvents(boolean suppress) {
        this.suppressSplitEvents = suppress;
    }

    void addRow(String category, String amountText) {
        addRow(category, amountText, null);
    }

    /**
     * Wie {@link #addRow(String, String)}, übernimmt zusätzlich einen bereits bekannten Kategorietyp
     * (z. B. beim Vorbelegen einer bestehenden Buchung) als gemerkten Zustand der Zeile, ohne dass der
     * Nutzer die Kategorie erneut in der Auswahlliste antippen müsste.
     */
    void addRow(String category, String amountText, Boolean categoryIsIncome) {
        addRow(category, amountText, categoryIsIncome, null);
    }

    /**
     * Wie oben, merkt sich zusätzlich die Beschriftung, aus der der Betrag stammt.
     *
     * <p>Sie hängt am Betragsfeld, so wie der Kategorietyp am Kategoriefeld hängt: die Zeile ist
     * nichts weiter als ein aufgeblasenes Layout, und ein eigenes Modell dafür wäre mehr Buchhaltung
     * als Nutzen. Wechselt der Nutzer die Kategorie, bleibt die Beschriftung stehen — sie sagt ja
     * nicht, wohin gebucht wird, sondern woher der Betrag kommt.</p>
     */
    void addRow(String category, String amountText, Boolean categoryIsIncome, String label) {
        View row = inflater.inflate(R.layout.item_split_row, container, false);
        MaterialAutoCompleteTextView cat = row.findViewById(R.id.splitCategory);
        TextInputEditText amt = row.findViewById(R.id.splitAmount);
        TextInputLayout amtLayout = row.findViewById(R.id.splitAmountLayout);
        amt.setTag(label == null || label.trim().isEmpty() ? null : label.trim());
        View remove = row.findViewById(R.id.btnRemoveSplit);
        if (categoryAdapter != null) {
            PickerAdapters.categories(cat, categoryAdapter);
        }
        // Vorbelegung vor dem Anhängen der Listener, damit sie keine dynamische Logik auslösen.
        if (category != null) {
            cat.setText(category, false);
        }
        cat.setTag(categoryIsIncome);
        if (amountText != null) {
            amt.setText(amountText);
        }
        if (readOnly) {
            // Ansicht: Kategorie/Betrag gesperrt, kein Entfernen-Knopf.
            lockField(cat);
            lockField(amt);
            remove.setVisibility(View.GONE);
            container.addView(row);
            return;
        }
        cat.addTextChangedListener(new SimpleWatcher(() -> {
            // Frei getippt/geändert → gemerkten Typ verwerfen (kein Auswahl-Signal mehr gültig). Bei
            // einer Dropdown-Auswahl läuft dieser Watcher VOR dem Klick-Listener unten (Android ruft bei
            // performCompletion() erst setText(), dann den Item-Klick), der den Typ danach korrekt setzt.
            cat.setTag(null);
            noteUserEdit();
            onSplitCategoryChanged(row);
        }));
        // Über PickerBehaviour: die Kategorie kann auch getippt und stehengelassen werden, dann fällt
        // kein Antippen eines Listeneintrags an und die Richtung (Einnahme/Ausgabe) bliebe unbekannt.
        PickerBehaviour.onCommitted(cat, value ->
                cat.setTag(categoryAdapter != null ? categoryAdapter.sideIn(cat, value) : null));
        amt.addTextChangedListener(new SimpleWatcher(() -> {
            if (!prefilling && !suppressSplitEvents && !syncingAmounts) {
                // Ein echter Handgriff des Nutzers: der Betrag hat sich geändert, eine vorher gezeigte
                // erkannte Regel gilt jetzt nicht mehr für DIESEN Wert – Titel und Symbol verschwinden,
                // bis das Feld erneut verlassen wird (siehe SecurityTxEditActivity#ankerAuswahlAnbietenSplit).
                amt.setTag(R.id.splitAmountTyped, Boolean.TRUE);
                if (amtLayout.getEndIconMode() == TextInputLayout.END_ICON_CUSTOM) {
                    amtLayout.setEndIconMode(TextInputLayout.END_ICON_NONE);
                    amtLayout.setHint(amtLayout.getResources().getString(R.string.split_partial_hint));
                    amt.setTag(R.id.splitAnchorRule, null);
                }
            }
            noteUserEdit();
            onPartialChanged(row);
        }));
        if (amountBinder != null) {
            amountBinder.bind(amtLayout, amt);   // Teilbetrag-Feld an die Rechentastatur binden
        }
        remove.setOnClickListener(v -> {
            categoryAuto = false;      // von Hand entfernt – hier wird nichts mehr nachgezogen
            if (row == autoAmountRow) {
                autoAmountRow = null;  // die Rest-Zeile ist weg → zurück in den Summen-Modus
            }
            container.removeView(row);
            ensureTrailingRow();
            applyCoupling();
            onChanged.run();
        });
        container.addView(row);
    }

    private void onSplitCategoryChanged(View row) {
        if (suppressSplitEvents) {
            return;
        }
        int idx = container.indexOfChild(row);
        String cat = catText(row);
        // Erste Kategorie bei bereits eingegebenem Gesamtbetrag → diese Zeile wird zur Rest-Zeile
        // (ihr Teilbetrag folgt automatisch dem Gesamtbetrag; siehe applyCoupling).
        if (idx == 0 && !cat.isEmpty() && amtText(row).isEmpty() && currentTotalCents() > 0) {
            autoAmountRow = row;
        }
        // Kategorie in der letzten Zeile → neue leere Zeile anhängen. Im CSV-Schreibweg nie: KMyMoneys
        // eigener CSV-Import kann pro Buchung nur eine Kategorie entgegennehmen (siehe CsvModeGuard).
        if (!splitLocked && !cat.isEmpty() && idx == container.getChildCount() - 1) {
            addRow(null, null);
        }
        applyCoupling();
        onChanged.run();
    }

    /**
     * Teilbetrag geändert. Rest-Modus (Rest-Zeile vorhanden): der Gesamtbetrag bleibt die Nutzereingabe,
     * die Rest-Zeile trägt die Differenz. Ändert der Nutzer die Rest-Zeile selbst, ist sie fortan fest
     * (Summen-Modus). Sonst: Gesamt = Summe der Teilbeträge.
     */
    private void onPartialChanged(View row) {
        if (suppressSplitEvents || syncingAmounts) {
            onChanged.run();
            return;
        }
        if (row == autoAmountRow) {
            // Benutzereingabe hat Vorrang: die Rest-Zeile ist jetzt vom Nutzer gesetzt.
            autoAmountRow = null;
        }
        applyCoupling();
        onChanged.run();
    }

    /**
     * Gesamtbetrag geändert. Mit Rest-Zeile trägt diese die Differenz; sonst wird bei genau einer
     * Kategorie deren Teilbetrag gleich dem Gesamtbetrag gesetzt.
     */
    void onTotalChanged() {
        if (suppressSplitEvents || syncingAmounts) {
            onChanged.run();
            return;
        }
        if (autoAmountRow != null) {
            applyCoupling();
            onChanged.run();
            return;
        }
        View single = singleCategoryRow();
        if (single != null) {
            syncingAmounts = true;
            setAmtText(single, de.spahr.ausgaben.settings.MoneyFormat.plain(currentTotalCents()));
            syncingAmounts = false;
        }
        onChanged.run();
    }

    /**
     * Rest-Modus (Rest-Zeile vorhanden und gültig): Gesamtbetrag bleibt fest, die Rest-Zeile bekommt die
     * Differenz zu den übrigen Teilbeträgen. Sonst Summen-Modus: Gesamt = Summe der Teilbeträge.
     */
    private void applyCoupling() {
        if (autoAmountRow != null && container.indexOfChild(autoAmountRow) >= 0
                && !catText(autoAmountRow).isEmpty()) {
            long remainder = currentTotalCents() - sumOfPartsExcept(autoAmountRow);
            syncingAmounts = true;
            setAmtText(autoAmountRow, de.spahr.ausgaben.settings.MoneyFormat.plain(remainder));
            syncingAmounts = false;
        } else {
            autoAmountRow = null;
            recomputeTotalFromParts();
        }
    }

    /** Summe der Teilbeträge aller Kategoriezeilen außer {@code exclude}. */
    private long sumOfPartsExcept(View exclude) {
        long sum = 0;
        for (int i = 0; i < container.getChildCount(); i++) {
            View r = container.getChildAt(i);
            if (r == exclude || catText(r).isEmpty()) {
                continue;
            }
            Long c = parseCents(amtText(r));
            if (c != null) {
                sum += c;
            }
        }
        return sum;
    }

    /** Setzt den Gesamtbetrag auf die Summe aller Teilbeträge (Zeilen mit Kategorie). */
    private void recomputeTotalFromParts() {
        long sum = 0;
        boolean any = false;
        for (int i = 0; i < container.getChildCount(); i++) {
            View r = container.getChildAt(i);
            if (catText(r).isEmpty()) {
                continue;
            }
            Long c = parseCents(amtText(r));
            if (c != null) {
                sum += c;
                any = true;
            }
        }
        if (!any) {
            return; // keine Kategorie mit Betrag → Gesamtbetrag unverändert lassen
        }
        syncingAmounts = true;
        totalField.setText(de.spahr.ausgaben.settings.MoneyFormat.plain(sum));
        syncingAmounts = false;
    }

    /** Liefert die einzige Zeile mit gesetzter Kategorie oder {@code null}, wenn es 0 oder mehrere sind. */
    private View singleCategoryRow() {
        View found = null;
        for (int i = 0; i < container.getChildCount(); i++) {
            View r = container.getChildAt(i);
            if (!catText(r).isEmpty()) {
                if (found != null) {
                    return null;
                }
                found = r;
            }
        }
        return found;
    }

    /**
     * Merkt einen Handgriff des Nutzers in den Zeilen vor: ab jetzt wird die Kategorie nicht mehr
     * nachgezogen. Programmgesteuerte Schreibvorgänge zählen nicht mit – weder das Vorbelegen selbst
     * noch der Teilbetrag, den {@link #onSplitCategoryChanged} und {@link #onTotalChanged} setzen.
     */
    private void noteUserEdit() {
        if (!prefilling && !suppressSplitEvents && !syncingAmounts) {
            categoryAuto = false;
        }
    }

    /** Ob die Kategoriezeilen noch allein aus Vorbelegungen stammen – dann darf eine neue hinein. */
    boolean isCategoryAuto() {
        return categoryAuto;
    }

    /**
     * Erklärt die jetzigen Zeilen für unantastbar: bei einer gespeicherten oder geplanten Buchung ist
     * die Kategorie gesetzte Wahrheit und keine Vorbelegung.
     */
    void lockCategories() {
        categoryAuto = false;
    }

    /**
     * Ersetzt den vorbelegten Satz durch diese eine Kategorie – der Empfänger hat gewechselt. Was der
     * Nutzer selbst eingetragen hat, bleibt unangetastet ({@link #isCategoryAuto()}). Ohne Kategorie
     * bleibt allein die leere Abschlusszeile stehen.
     */
    void replaceAutoCategories(String category, Boolean categoryIsIncome) {
        if (!categoryAuto) {
            return;
        }
        prefilling = true;
        clear();
        ensureTrailingRow();
        setFirstCategory(category, categoryIsIncome);
        prefilling = false;
        onChanged.run();
    }

    /**
     * Trägt eine Kategorie in die erste Zeile ein (Vorbelegung aus dem Empfänger) samt ihrem bekannten
     * Typ. Der Weg über {@code setText} ist Absicht: der Beobachter der Zeile füllt daraufhin den
     * Teilbetrag mit dem Gesamtbetrag und hängt eine neue leere Zeile an – wie bei einer Auswahl von
     * Hand. Den Typ setzt er dabei zurück, deshalb kommt er erst danach.
     */
    void setFirstCategory(String category, Boolean categoryIsIncome) {
        if (category == null || category.trim().isEmpty() || container.getChildCount() == 0) {
            return;
        }
        MaterialAutoCompleteTextView cat = container.getChildAt(0).findViewById(R.id.splitCategory);
        if (cat == null) {
            return;
        }
        cat.setText(category, false);
        // Die Seite kommt mit der Kategorie von ihrer Quelle; nur ohne sie wird nachgeschlagen.
        cat.setTag(categoryIsIncome != null ? categoryIsIncome : sideOf(category));
    }

    /**
     * Die Seite einer Kategorie laut Auswahlliste ({@code null} = die Liste kennt sie nicht oder ist
     * noch nicht da). Der Rückfall für Kategorien, die ohne ihre Seite ankommen.
     */
    Boolean sideOf(String category) {
        return categoryAdapter != null ? categoryAdapter.sideOfName(category) : null;
    }

    /** Reicht den Vorspann des Empfängers an die gemeinsame Kategorieliste aller Zeilen weiter. */
    void setCategoryFavorites(String header, List<de.spahr.ausgaben.db.PayeeCategory> values) {
        if (categoryAdapter == null) {
            return;
        }
        List<String> names = new ArrayList<>();
        List<Boolean> sides = new ArrayList<>();
        if (values != null) {
            for (de.spahr.ausgaben.db.PayeeCategory v : values) {
                names.add(v.category);
                sides.add(v.isIncome);
            }
        }
        categoryAdapter.setFavorites(header, names, sides);
    }

    /** Sorgt für genau eine leere Abschluss-Zeile am Ende. */
    void ensureTrailingRow() {
        if (readOnly) {
            return; // Ansicht: keine leere Zusatzzeile.
        }
        int n = container.getChildCount();
        if (n == 0) {
            addRow(null, null);
            return;
        }
        if (splitLocked) {
            return; // CSV-Schreibweg: nie eine zweite Zeile, auch nicht als leere Abschlusszeile.
        }
        if (!catText(container.getChildAt(n - 1)).isEmpty()) {
            addRow(null, null);
        }
    }

    /** Kategorie-Teile mit gültiger Kategorie und Betrag (leere Abschlusszeile wird ignoriert). */
    List<Part> collectParts() {
        List<Part> parts = new ArrayList<>();
        for (int i = 0; i < container.getChildCount(); i++) {
            View r = container.getChildAt(i);
            String c = catText(r);
            if (c.isEmpty()) {
                continue;
            }
           if (categoryAdapter != null && !categoryAdapter.containsCategory(c)) {
                continue;
            }
            Long cents = parseCents(amtText(r));
            if (cents != null) {
                parts.add(new Part(c, cents, categoryIsIncomeTag(r), labelTag(r), ruleTag(r)));
            }
        }
        return parts;
    }

    /** Die gemerkte Herkunftsbeschriftung der Zeile; leer, wenn sie nicht aus einer Abrechnung kam. */
    private String labelTag(View row) {
        TextInputEditText amt = row.findViewById(R.id.splitAmount);
        Object tag = amt.getTag();
        return tag instanceof String ? (String) tag : "";
    }

    /** Die erkannte Anker-Regel der Zeile (siehe {@link Part#chosenRule}); {@code null}, wenn keine gilt. */
    private AnchorRule ruleTag(View row) {
        TextInputEditText amt = row.findViewById(R.id.splitAmount);
        Object tag = amt.getTag(R.id.splitAnchorRule);
        return tag instanceof AnchorRule ? (AnchorRule) tag : null;
    }

    /** Gemerkter Kategorietyp der Zeile (aus der Auswahlliste angetippt oder vorbelegt), sonst {@code null}. */
    private Boolean categoryIsIncomeTag(View row) {
        MaterialAutoCompleteTextView cat = row.findViewById(R.id.splitCategory);
        Object tag = cat.getTag();
        return tag instanceof Boolean ? (Boolean) tag : null;
    }

    /** true, wenn das Ausgabe/Einnahme-Formular gespeichert werden darf (Summe der Teile = Gesamt). */
    boolean isValid() {
        Long total = parseCents(text(totalField));
        if (total == null || total <= 0) {
            return false;
        }
        long sum = 0;
        int count = 0;
        for (int i = 0; i < container.getChildCount(); i++) {
            View r = container.getChildAt(i);
            String c = catText(r);
            String a = amtText(r);
            if (c.isEmpty()) {
                continue; // leere / betragslose Kategoriezeile ignorieren
            }
            if (categoryAdapter != null && !categoryAdapter.containsCategory(c)) {
                return false; // Kategorie muss aus der Auswahlliste stammen
            }
            Long cents = parseCents(a);
            if (cents == null) {
                return false; // Kategorie ohne gültigen Teilbetrag
            }
            sum += cents;
            count++;
        }
        if (count == 0) {
            return true; // keine Kategorie → einfache (nicht zugeordnete) Buchung
        }
        return sum == total;
    }

    private String catText(View row) {
        MaterialAutoCompleteTextView cat = row.findViewById(R.id.splitCategory);
        return cat.getText() == null ? "" : cat.getText().toString().trim();
    }

    private String amtText(View row) {
        TextInputEditText amt = row.findViewById(R.id.splitAmount);
        return amt.getText() == null ? "" : amt.getText().toString().trim();
    }

    private void setAmtText(View row, String textValue) {
        TextInputEditText amt = row.findViewById(R.id.splitAmount);
        amt.setText(textValue);
    }

    private long currentTotalCents() {
        Long t = parseCents(text(totalField));
        return t == null ? 0 : t;
    }

    /**
     * Sperrt nachträglich alle vorhandenen Zeilen wie in der Ansicht und entfernt die leere
     * Abschlusszeile – für die Geldbuchung einer Depot-Bewegung, an der nur Notiz, Stichwort und Beleg
     * änderbar sind (siehe {@code BookingEditActivity#applyNotesOnlyIfNeeded}).
     */
    void lockRows() {
        for (int i = container.getChildCount() - 1; i >= 0; i--) {
            View row = container.getChildAt(i);
            android.widget.EditText cat = row.findViewById(R.id.splitCategory);
            android.widget.EditText amt = row.findViewById(R.id.splitAmount);
            if (catText(row).isEmpty() && amtText(row).isEmpty()) {
                container.removeViewAt(i);
                continue;
            }
            lockField(cat);
            lockField(amt);
            row.findViewById(R.id.btnRemoveSplit).setVisibility(View.GONE);
        }
    }

    private void lockField(android.widget.EditText e) {
        e.setFocusable(false);
        e.setFocusableInTouchMode(false);
        e.setClickable(false);
        e.setLongClickable(false);
        e.setCursorVisible(false);
        e.setKeyListener(null);
        e.setOnClickListener(null);
    }

    private static String text(android.widget.EditText e) {
        return e.getText() == null ? "" : e.getText().toString();
    }


    /**
     * Teilbetrag in Cent; akzeptiert wie das Gesamtfeld auch eine kleine Rechnung (z. B. {@code 12,50+3,20}).
     * Paketsichtbar: {@code SecurityTxEditActivity} liest damit denselben Betrag, den auch diese Klasse
     * beim Sammeln der Zeilen benutzt (siehe {@code ankerAuswahlAnbietenSplit}).
     */
    static Long parseCents(String raw) {
        BigDecimal value = de.spahr.ausgaben.settings.AmountExpression.evaluate(raw);
        if (value == null) {
            return null;
        }
        try {
            return value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }
}
