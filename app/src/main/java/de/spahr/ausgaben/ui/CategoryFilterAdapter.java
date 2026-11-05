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

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.settings.CategoryColorStore;

/**
 * Adapter für die Kategorie-Auswahl. Oberste Ebene: die Gruppen-Überschriften „Ausgabe" und „Einnahme"
 * (fett, nicht als Wert nutzbar), darunter je die Kategorien als Baum beliebiger Tiefe
 * (`Haupt:Unter:Unterunter`). Jede Zeile zeigt nur ihren eigenen Namen, eingerückt nach ihrer Ebene; ab
 * der dritten Ebene steht eine Baumlinie davor (├ / └), damit „Benzin" erkennbar unter „Auto" liegt, ohne
 * dass „Auto:" in jeder Zeile wiederkehrt (Issue #11). Beim Tippen wird gefiltert, die zugehörige
 * Gruppen-Überschrift und die übergeordneten Kategorien bleiben aber sichtbar.
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
        /** Ebene im Baum: 0 = Hauptkategorie, 1 = Unterkategorie, 2 und tiefer mit Baumlinie. */
        final int depth;
        /** Letztes Kind seines Elternteils – zeichnet └ statt ├. */
        final boolean lastChild;

        CatItem(String label, String value, int kind, String group, boolean groupIsIncome) {
            this(label, value, kind, group, groupIsIncome, 0, false);
        }

        CatItem(String label, String value, int kind, String group, boolean groupIsIncome,
                int depth, boolean lastChild) {
            this.label = label;
            this.value = value;
            this.kind = kind;
            this.group = group;
            this.isMain = kind == KIND_MAIN;
            this.groupIsIncome = groupIsIncome;
            this.depth = depth;
            this.lastChild = lastChild;
        }
    }

    private final List<CatItem> all;
    /** Vorspann samt Überschrift: die Kategorien des eingetragenen Empfängers; leer = keiner. */
    private List<CatItem> favorites = new ArrayList<>();
    private List<CatItem> shown;
    /** Die zuletzt angezeigte Suchanfrage – leer heißt: das Feld steht offen mit dem ganzen Bestand. */
    private String lastConstraint = "";
    private final int indentPx;
    /** Weitere Einrückung je Ebene ab der dritten – kleiner, weil dort die Baumlinie mit einrückt. */
    private final int treeIndentPx;
    /** Liefert die Farbe, die dieselbe Kategorie auch im Kreisdiagramm trägt. */
    private final CategoryColorStore colors;

    public CategoryFilterAdapter(@NonNull Context context, String allLabel,
                                 String expenseLabel, List<String> expenseCats,
                                 String incomeLabel, List<String> incomeCats) {
        super(context, R.layout.item_picker_row, R.id.pickerText);
        this.all = build(allLabel, expenseLabel, expenseCats, incomeLabel, incomeCats);
        this.shown = new ArrayList<>(all);
        float density = context.getResources().getDisplayMetrics().density;
        this.indentPx = Math.round(24 * density);
        this.treeIndentPx = Math.round(12 * density);
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
        Node root = new Node();
        for (String c : categories) {
            if (c == null || c.trim().isEmpty()) {
                continue;
            }
            // Fehlt ein Zwischenglied („Fahrtkosten:Auto" zu „Fahrtkosten:Auto:Benzin"), entsteht es
            // hier von selbst – wählbar wie bisher schon die Hauptkategorie über ihren Unterkategorien.
            Node node = root;
            for (String part : c.trim().split(":")) {
                String name = part.trim();
                if (!name.isEmpty()) {
                    node = node.children.computeIfAbsent(name, k -> new Node());
                }
            }
        }
        emit(out, root, "", 0, groupLabel, groupIsIncome);
    }

    /** Ein Knoten im Kategoriebaum; die Kinder sortiert wie bisher, ohne Groß-/Kleinschreibung. */
    private static final class Node {
        final TreeMap<String, Node> children = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    }

    /** Gibt den Baum in Lesereihenfolge aus: jeder Knoten, dann seine Kinder. */
    private static void emit(List<CatItem> out, Node node, String prefix, int depth, String groupLabel,
                             boolean groupIsIncome) {
        int i = 0;
        int n = node.children.size();
        for (Map.Entry<String, Node> e : node.children.entrySet()) {
            String name = e.getKey();
            String value = prefix.isEmpty() ? name : prefix + ":" + name;
            out.add(new CatItem(name, value, depth == 0 ? KIND_MAIN : KIND_SUB, groupLabel,
                    groupIsIncome, depth, ++i == n));
            emit(out, e.getValue(), value, depth + 1, groupLabel, groupIsIncome);
        }
    }

    /** „A:B:C" → „A:B"; Hauptkategorien haben keinen Elternteil (""). */
    private static String parentOf(String value) {
        int i = value.lastIndexOf(':');
        return i >= 0 ? value.substring(0, i) : "";
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

    /**
     * Die Seite der Kategorie, die in {@code field} steht ({@code true} = Einnahme, {@code false} =
     * Ausgabe). Wurde sie aus der Liste gewählt, ist es die Gruppe des gewählten Eintrags – auch wenn
     * es denselben Namen in der anderen Gruppe gibt. Wurde sie getippt, sagt es der Name, sofern er nur
     * in einer Gruppe steht; sonst {@code null}.
     */
    Boolean sideIn(android.widget.AutoCompleteTextView field, String value) {
        String q = value == null ? "" : value.trim();
        Object gewaehlt = PickerBehaviour.pickedItem(field);
        if (gewaehlt instanceof CatItem && !q.isEmpty()
                && ((CatItem) gewaehlt).value.equalsIgnoreCase(q)) {
            return ((CatItem) gewaehlt).groupIsIncome;
        }
        return sideOfName(q);
    }

    /** Die Seite laut Name – nur, wenn er in genau einer der beiden Gruppen steht; sonst {@code null}. */
    Boolean sideOfName(String value) {
        String q = value == null ? "" : value.trim();
        Boolean seite = null;
        for (CatItem item : all) {
            if ((item.kind == KIND_MAIN || item.kind == KIND_SUB) && item.value.equalsIgnoreCase(q)) {
                if (seite != null && seite != item.groupIsIncome) {
                    return null;   // in beiden Bäumen: der Name allein entscheidet nichts
                }
                seite = item.groupIsIncome;
            }
        }
        return seite;
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
            int left = item.kind == KIND_SUB ? indentPx * Math.min(item.depth, 1)
                    + treeIndentPx * Math.max(item.depth - 1, 0) : 0;
            tv.setPadding(left, top, right, bottom);
            if (item.kind == KIND_SUB && item.depth >= 2) {
                tv.setText(withTreeLine(tv, item));
            }
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

    /**
     * Der Name mit grauer Baumlinie davor. Bricht er in eine zweite Zeile um, beginnt die unter dem
     * Namen, nicht unter der Linie.
     */
    private static CharSequence withTreeLine(TextView tv, CatItem item) {
        String line = item.lastChild ? "└ " : "├ ";
        android.text.SpannableString text = new android.text.SpannableString(line + item.label);
        text.setSpan(new android.text.style.ForegroundColorSpan(
                        tv.getResources().getColor(R.color.grey_text, null)),
                0, line.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int rest = Math.round(tv.getPaint().measureText(line));
        text.setSpan(new android.text.style.LeadingMarginSpan.Standard(0, rest),
                0, text.length(), android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        return text;
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
                // Ein Eintrag passt, wenn sein voller Pfad die Suche enthält – eine passende Kategorie
                // zeigt damit auch alle Kategorien darunter, und „auto:benz" trifft „Fahrtkosten:Auto:
                // Benzin". Zu jedem Treffer kommen seine übergeordneten Kategorien als Kontext dazu,
                // sonst wüsste man nicht, wo er hängt. Eine Gruppen-Überschrift nur bei Treffern.
                result = new ArrayList<>();
                for (CatItem it : all) {
                    if (it.kind == KIND_ALL) {
                        result.add(it);
                    }
                }
                // Je Gruppe die Pfade der Treffer samt Vorfahren; die Ausgabe folgt der Reihenfolge von all.
                java.util.Map<String, java.util.Set<String>> keep = new java.util.HashMap<>();
                for (CatItem it : all) {
                    if ((it.kind == KIND_MAIN || it.kind == KIND_SUB)
                            && it.value.toLowerCase(Locale.getDefault()).contains(q)) {
                        java.util.Set<String> paths = keep.computeIfAbsent(it.group,
                                k -> new java.util.HashSet<>());
                        // Hinauf bis zur Hauptkategorie – oder bis zu einem Vorfahren, der schon drin ist.
                        String p = it.value;
                        while (!p.isEmpty() && paths.add(p)) {
                            p = parentOf(p);
                        }
                    }
                }
                for (CatItem it : all) {
                    java.util.Set<String> paths = keep.get(it.group);
                    if (paths == null) {
                        continue;
                    }
                    if (it.kind == KIND_GROUP || paths.contains(it.value)) {
                        result.add(it);
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
