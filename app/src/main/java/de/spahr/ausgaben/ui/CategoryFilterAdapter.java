package de.spahr.ausgaben.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Filter;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.settings.CategoryColorStore;

/**
 * Adapter für die Kategorie-Auswahl. Oberste Ebene: die Gruppen-Überschriften „Ausgabe" und „Einnahme"
 * (fett, nicht als Wert nutzbar), darunter je die Kategorien als Baum (`Haupt:Unter`, Unterkategorien
 * eingerückt). Beim Tippen wird gefiltert, die zugehörige Gruppen-Überschrift bleibt aber sichtbar.
 */
public class CategoryFilterAdapter extends ArrayAdapter<CategoryFilterAdapter.CatItem> {

    private static final int KIND_ALL = 0;
    private static final int KIND_GROUP = 1;
    private static final int KIND_MAIN = 2;
    private static final int KIND_SUB = 3;
    /** Kategorie im Vorspann „bei diesem Empfänger" – dieselbe Kategorie steht unten noch einmal. */
    private static final int KIND_FAV = 4;

    /** Ein Eintrag. {@code value} = zu filternder Kategorietext ("" = alle bzw. Überschrift). */
    public static class CatItem {
        public final String label;
        public final String value;
        final int kind;
        final String group; // zu welcher Gruppe (Überschrift) dieser Eintrag gehört
        public final boolean isMain;
        /** Typ der Gruppe, aus der dieser Eintrag stammt (true = Einnahme, false = Ausgabe). */
        public final boolean groupIsIncome;

        CatItem(String label, String value, int kind, String group, boolean groupIsIncome) {
            this.label = label;
            this.value = value;
            this.kind = kind;
            this.group = group;
            this.isMain = kind == KIND_MAIN;
            this.groupIsIncome = groupIsIncome;
        }
    }

    private final List<CatItem> all;
    /** Vorspann samt Überschrift: die Kategorien des eingetragenen Empfängers; leer = keiner. */
    private List<CatItem> favorites = new ArrayList<>();
    private List<CatItem> shown;
    /** Die zuletzt angezeigte Suchanfrage – leer heißt: das Feld steht offen mit dem ganzen Bestand. */
    private String lastConstraint = "";
    private final int indentPx;
    /** Liefert die Farbe, die dieselbe Kategorie auch im Kreisdiagramm trägt. */
    private final CategoryColorStore colors;

    public CategoryFilterAdapter(@NonNull Context context, String allLabel,
                                 String expenseLabel, List<String> expenseCats,
                                 String incomeLabel, List<String> incomeCats) {
        super(context, R.layout.item_picker_row, R.id.pickerText);
        this.all = build(allLabel, expenseLabel, expenseCats, incomeLabel, incomeCats);
        this.shown = new ArrayList<>(all);
        this.indentPx = Math.round(24 * context.getResources().getDisplayMetrics().density);
        this.colors = new CategoryColorStore(context);
        addAll(this.shown);
    }

    private static List<CatItem> build(String allLabel, String expenseLabel, List<String> expenseCats,
                                       String incomeLabel, List<String> incomeCats) {
        List<CatItem> out = new ArrayList<>();
        if (allLabel != null) {
            out.add(new CatItem(allLabel, "", KIND_ALL, "", false));
        }
        addGroup(out, expenseLabel, false, expenseCats);
        addGroup(out, incomeLabel, true, incomeCats);
        return out;
    }

    private static void addGroup(List<CatItem> out, String groupLabel, boolean groupIsIncome,
                                 List<String> categories) {
        if (categories == null || categories.isEmpty()) {
            return;
        }
        out.add(new CatItem(groupLabel, "", KIND_GROUP, groupLabel, groupIsIncome));
        TreeMap<String, TreeSet<String>> tree = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String c : categories) {
            if (c == null || c.trim().isEmpty()) {
                continue;
            }
            String cat = c.trim();
            int i = cat.indexOf(':');
            String main = i >= 0 ? cat.substring(0, i).trim() : cat;
            String sub = i >= 0 ? cat.substring(i + 1).trim() : "";
            TreeSet<String> subs = tree.computeIfAbsent(main,
                    k -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER));
            if (!sub.isEmpty()) {
                subs.add(sub);
            }
        }
        for (Map.Entry<String, TreeSet<String>> e : tree.entrySet()) {
            String main = e.getKey();
            out.add(new CatItem(main, main, KIND_MAIN, groupLabel, groupIsIncome));
            for (String sub : e.getValue()) {
                out.add(new CatItem(sub, main + ":" + sub, KIND_SUB, groupLabel, groupIsIncome));
            }
        }
    }

    /**
     * Setzt den Vorspann: die Kategorien des eingetragenen Empfängers, unter der Überschrift
     * {@code header} und in der übergebenen Reihenfolge. Sie stehen im leeren Feld ganz oben und noch
     * einmal an ihrem gewohnten Platz; sobald jemand tippt, bleibt nur der gewohnte Platz.
     *
     * <p>Was die Liste nicht (mehr) kennt, fällt weg. Die Einträge behalten Richtungspfeil und Farbe
     * ihrer Kategorie, tragen aber den vollen Namen „Haupt:Unter": im Vorspann fehlt die
     * Hauptkategorie als Überschrift darüber.</p>
     */
    void setFavorites(String header, List<String> values) {
        setFavorites(header, values, null);
    }

    /**
     * Wie oben, mit der Seite jeder Kategorie ({@code sides} gleich lang wie {@code values}, ein
     * {@code null} darin = unbekannt). Eine mitgegebene Seite geht vor: sie stammt von der Buchung, aus
     * der der Vorschlag kommt, und entscheidet auch dann richtig, wenn es den Namen in beiden Bäumen
     * gibt. Nur ohne sie zählt die Gruppe des gleichnamigen Listeneintrags.
     */
    void setFavorites(String header, List<String> values, List<Boolean> sides) {
        List<CatItem> neu = new ArrayList<>();
        if (values != null) {
            for (int i = 0; i < values.size(); i++) {
                CatItem item = itemFor(values.get(i));
                if (item != null && !item.value.isEmpty()) {
                    Boolean side = sides != null && i < sides.size() ? sides.get(i) : null;
                    neu.add(new CatItem(item.value, item.value, KIND_FAV, header,
                            side != null ? side : item.groupIsIncome));
                }
            }
        }
        if (!neu.isEmpty()) {
            neu.add(0, new CatItem(header, "", KIND_GROUP, header, false));
        }
        favorites = neu;
        // Das Feld zeigt gerade die alte Liste; ohne neuen Suchlauf bliebe sie bis zum nächsten Öffnen.
        // Sucht dort jemand gerade, bleibt seine Trefferliste stehen – der Vorspann kommt beim Leeren.
        if (lastConstraint.isEmpty()) {
            getFilter().filter("");
        }
    }

    /** True, wenn der Text einer auswählbaren Kategorie (Haupt- oder Unterkategorie) entspricht. */
    boolean containsCategory(String value) {
        return knownForm(value) != null;
    }

    /**
     * Die Kategorie, die so heißt – in der Schreibweise der Liste, also so, wie sie auch beim Wählen aus
     * der Liste im Feld landet („Haupt:Unter", siehe {@code convertResultToString}). {@code null}, wenn
     * es keine gibt.
     */
    /** Der Eintrag zu einem Kategoriewert – auch „alle" und getippte Werte finden hierüber ihren Typ. */
    CatItem itemFor(String value) {
        String q = value == null ? "" : value.trim();
        for (CatItem item : all) {
            if (item.kind == KIND_ALL && q.isEmpty()) {
                return item;
            }
            if ((item.kind == KIND_MAIN || item.kind == KIND_SUB) && item.value.equalsIgnoreCase(q)) {
                return item;
            }
        }
        return null;
    }

    String knownForm(String value) {
        String q = value == null ? "" : value.trim();
        if (q.isEmpty()) {
            return null;
        }
        for (CatItem item : all) {
            if ((item.kind == KIND_MAIN || item.kind == KIND_SUB) && item.value.equalsIgnoreCase(q)) {
                return item.value;
            }
        }
        return null;
    }

    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        View row = super.getView(position, convertView, parent);
        TextView tv = row.findViewById(R.id.pickerText);
        android.widget.ImageView icon = row.findViewById(R.id.pickerIcon);
        CatItem item = getItem(position);
        if (item == null) {
            return row;
        }
        tv.setText(item.label);
        int top = tv.getPaddingTop();
        int right = tv.getPaddingRight();
        int bottom = tv.getPaddingBottom();
        if (item.kind == KIND_GROUP) {
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            tv.setAllCaps(true);
            tv.setTextColor(tv.getResources().getColor(R.color.grey_text, null));
            tv.setPadding(0, top, right, bottom);
            icon.setImageDrawable(null);
        } else {
            tv.setAllCaps(false);
            tv.setTextColor(primaryText(tv));
            tv.setTypeface(item.kind == KIND_MAIN ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            int left = item.kind == KIND_SUB ? indentPx : 0;
            tv.setPadding(left, top, right, bottom);
            // Ein Zeichen für zweierlei: die Richtung (Einnahme/Ausgabe) über die Form, die Kategorie
            // über die Farbe – dieselbe, die sie im Kreisdiagramm trägt. Unterkategorien erben die Farbe
            // ihrer Hauptkategorie, damit Geschwister zusammengehörig aussehen.
            if (item.kind == KIND_ALL) {
                icon.setImageDrawable(null); // „Alle Kategorien" gehört zu keiner Richtung
            } else {
                icon.setImageResource(item.groupIsIncome
                        ? R.drawable.ic_income : R.drawable.ic_expense);
                icon.setColorFilter(colors.colorFor(mainOf(item.value)));
            }
        }
        return row;
    }

    /** „Haupt:Unter" → „Haupt"; Hauptkategorien bleiben, wie sie sind. */
    private static String mainOf(String value) {
        if (value == null) {
            return "";
        }
        int i = value.indexOf(':');
        return i >= 0 ? value.substring(0, i).trim() : value;
    }

    private static int primaryText(TextView t) {
        android.util.TypedValue tv = new android.util.TypedValue();
        t.getContext().getTheme().resolveAttribute(android.R.attr.textColorPrimary, tv, true);
        return t.getContext().getColor(tv.resourceId != 0 ? tv.resourceId : android.R.color.black);
    }

    @NonNull
    @Override
    public Filter getFilter() {
        return filter;
    }

    private final Filter filter = new Filter() {
        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            List<CatItem> result;
            String q = constraint == null ? "" : constraint.toString().trim().toLowerCase(Locale.getDefault());
            if (q.isEmpty()) {
                // Leeres Feld: der Vorspann des Empfängers steht über dem Bestand. Beim Suchen fällt er
                // weg, die Kategorien stehen dann nur noch an ihrem gewohnten Platz.
                if (favorites.isEmpty()) {
                    result = all;
                } else {
                    result = new ArrayList<>(favorites);
                    result.addAll(all);
                }
            } else {
                // Passende Kategorien sammeln; eine Gruppen-Überschrift nur zeigen, wenn sie Treffer hat.
                // Trifft nur eine Unterkategorie zu, wird zusätzlich ihre Hauptkategorie mit angezeigt
                // (als Kontext, auch wenn deren eigener Text nicht passt) – sonst wüsste man beim Tippen
                // einer Unterkategorie nicht, zu welcher Hauptkategorie sie gehört. Umgekehrt zeigt eine
                // passende Hauptkategorie auch alle ihre Unterkategorien (unabhängig von deren Text).
                result = new ArrayList<>();
                for (CatItem it : all) {
                    if (it.kind == KIND_ALL) {
                        result.add(it);
                    }
                }
                java.util.LinkedHashMap<String, List<CatItem>> byGroup = new java.util.LinkedHashMap<>();
                java.util.Map<String, CatItem> headers = new java.util.HashMap<>();
                java.util.Map<String, CatItem> pendingMain = new java.util.HashMap<>();
                java.util.Set<String> addedMain = new java.util.HashSet<>();
                java.util.Set<String> expandMain = new java.util.HashSet<>();
                for (CatItem it : all) {
                    if (it.kind == KIND_GROUP) {
                        headers.put(it.group, it);
                        byGroup.put(it.group, new ArrayList<>());
                    } else if (it.kind == KIND_MAIN) {
                        pendingMain.put(it.value, it);
                        addedMain.remove(it.value);
                        expandMain.remove(it.value);
                        if (it.label.toLowerCase(Locale.getDefault()).contains(q)) {
                            List<CatItem> l = byGroup.get(it.group);
                            if (l != null) {
                                l.add(it);
                                addedMain.add(it.value);
                                expandMain.add(it.value);
                            }
                        }
                    } else if (it.kind == KIND_SUB) {
                        String mainKey = it.value.substring(0, it.value.indexOf(':'));
                        boolean matches = expandMain.contains(mainKey)
                                || it.label.toLowerCase(Locale.getDefault()).contains(q);
                        if (!matches) {
                            continue;
                        }
                        List<CatItem> l = byGroup.get(it.group);
                        if (l != null) {
                            if (!addedMain.contains(mainKey)) {
                                CatItem mainItem = pendingMain.get(mainKey);
                                if (mainItem != null) {
                                    l.add(mainItem);
                                    addedMain.add(mainKey);
                                }
                            }
                            l.add(it);
                        }
                    }
                }
                for (Map.Entry<String, List<CatItem>> e : byGroup.entrySet()) {
                    if (!e.getValue().isEmpty()) {
                        result.add(headers.get(e.getKey()));
                        result.addAll(e.getValue());
                    }
                }
            }
            FilterResults r = new FilterResults();
            r.values = result;
            r.count = result.size();
            return r;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void publishResults(CharSequence constraint, FilterResults results) {
            lastConstraint = constraint == null ? "" : constraint.toString().trim();
            shown = results.values == null ? new ArrayList<>() : (List<CatItem>) results.values;
            clear();
            addAll(shown);
            notifyDataSetChanged();
        }

        @Override
        public CharSequence convertResultToString(Object resultValue) {
            // Nur echte Kategorien liefern einen Wert; Überschriften/„alle" ergeben "".
            if (resultValue instanceof CatItem) {
                CatItem c = (CatItem) resultValue;
                return c.kind == KIND_MAIN || c.kind == KIND_SUB || c.kind == KIND_FAV ? c.value : "";
            }
            return "";
        }
    };
}
