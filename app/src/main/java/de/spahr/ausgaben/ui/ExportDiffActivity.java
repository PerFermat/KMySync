package de.spahr.ausgaben.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.export.ExportDiff;
import de.spahr.ausgaben.export.ExportDiffStore;
import de.spahr.ausgaben.export.TabellenDiff;
import de.spahr.ausgaben.settings.ProfileManager;

/**
 * Was die Exporte an der KMyMoney-Datei geändert haben. Ohne {@link #EXTRA_NAME} die Liste der
 * aufgehobenen Exporte, mit ihm die Zeilen eines einzelnen: hinzugekommene grün, entfernte rot, die
 * Umgebung ohne Farbe – schwarz auf Weiß im hellen, weiß auf dunklem Grund im dunklen Modus –, jede
 * mit Zeilennummer.
 *
 * <p>Die Daten stammen aus dem Vergleich nach dem Export ({@link ExportDiff}) – dem Stand davor gegen
 * die vom Server zurückgelesene Datei –, nicht aus dem, was die App zu schreiben vorhatte.</p>
 */
public class ExportDiffActivity extends LocalizedActivity {

    /** Name des anzuzeigenden Vergleichs (der Name der zugehörigen Sicherung). */
    public static final String EXTRA_NAME = "name";
    /** Bei einer Datenbank: die Tabelle, deren Sätze gezeigt werden; ohne ihn die Liste der Tabellen. */
    public static final String EXTRA_TABLE = "table";

    /** Breiter wird keine Spalte der Tabellenansicht; längere Werte enden auf „…". */
    private static final int MAX_ZELLE = 80;

    private ExportDiffStore store;
    private DateFormat datum;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_export_diff);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        store = new ExportDiffStore(ExportDiffStore.ordnerFuer(getFilesDir(),
                new ProfileManager(this).getActiveProfileId()));
        datum = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT,
                getResources().getConfiguration().getLocales().get(0));

        String name = getIntent().getStringExtra(EXTRA_NAME);
        if (name == null) {
            showList();
        } else {
            showLines(toolbar, name);
        }
    }

    private String summary(ExportDiff d) {
        return getString(d.datenbank ? R.string.kmy_changes_summary_rows
                : R.string.kmy_changes_summary, d.hinzu, d.entfernt, d.datei);
    }

    // ---- Liste der Exporte ----

    private void showList() {
        final List<ExportDiffStore.Eintrag> eintraege = store.liste();
        TextView note = findViewById(R.id.diffNote);
        note.setText(eintraege.isEmpty() ? R.string.kmy_changes_empty : R.string.kmy_changes_hint);
        RecyclerView list = findViewById(R.id.diffList);
        list.setVisibility(View.VISIBLE);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        list.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View v = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_export_diff, parent, false);
                return new RecyclerView.ViewHolder(v) {
                };
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
                final ExportDiffStore.Eintrag e = eintraege.get(position);
                ((TextView) h.itemView.findViewById(R.id.diffWhen))
                        .setText(datum.format(new Date(e.kopf.zeit)));
                String text = summary(e.kopf);
                if (e.kopf.defekt) {
                    text = text + "\n" + defektText(e.kopf, e.name);
                } else if (e.kopf.abweichung) {
                    text = text + "\n" + getString(R.string.kmy_changes_differs);
                }
                TextView summary = h.itemView.findViewById(R.id.diffSummary);
                summary.setText(text);
                // Ein Defekt soll in der Liste auffallen; die Farbe muss auch wieder weg, wenn die
                // Zeile für einen anderen Eintrag wiederverwendet wird.
                if (h.itemView.getTag() == null) {
                    h.itemView.setTag(summary.getTextColors());
                }
                if (e.kopf.defekt) {
                    summary.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                            summary, androidx.appcompat.R.attr.colorError));
                } else {
                    summary.setTextColor((android.content.res.ColorStateList) h.itemView.getTag());
                }
                h.itemView.setOnClickListener(v -> startActivity(
                        new Intent(ExportDiffActivity.this, ExportDiffActivity.class)
                                .putExtra(EXTRA_NAME, e.name)));
            }

            @Override
            public int getItemCount() {
                return eintraege.size();
            }
        });
    }

    /** Der Hinweis zu einer defekt angekommenen Datei; {@code name} ist der Name ihrer Sicherung. */
    private String defektText(ExportDiff d, String name) {
        return getString(R.string.kmy_changes_defect) + " " + (d.wiederhergestellt
                ? getString(R.string.kmy_changes_restored)
                : getString(R.string.kmy_changes_not_restored,
                        de.spahr.ausgaben.export.KmyExportCoordinator.BACKUP_DIR + "/" + name));
    }

    // ---- Zeilen eines Exports ----

    private void showLines(MaterialToolbar toolbar, String name) {
        final ExportDiff d = store.lade(name);
        TextView note = findViewById(R.id.diffNote);
        if (d == null) {
            note.setText(R.string.kmy_changes_empty);
            return;
        }
        toolbar.setTitle(datum.format(new Date(d.zeit)));
        // Die Kurzfassung steht unter der Leiste, nicht als ihr Untertitel: bei großer Schrift passt
        // eine zweite Zeile dort nicht hinein und wird unten abgeschnitten.
        List<String> hinweise = new ArrayList<>();
        hinweise.add(summary(d));
        if (d.defekt) {
            hinweise.add(defektText(d, name));
        } else if (d.abweichung) {
            hinweise.add(getString(R.string.kmy_changes_differs));
        }
        if (d.gekuerzt) {
            hinweise.add(getString(R.string.kmy_changes_truncated));
        }
        if (d.datenbank) {
            if (!d.tabellen.isEmpty()) {
                hinweise.add(getString(R.string.kmy_changes_tables_hint));
            }
            String tabelle = getIntent().getStringExtra(EXTRA_TABLE);
            TabellenDiff.Tabelle t = tabelle == null ? null : d.tabelle(tabelle);
            if (t == null) {
                note.setText(android.text.TextUtils.join("\n", hinweise));
                showTables(d, name);
            } else {
                note.setText(tabellenText(t)
                        + (t.gekuerzt ? "\n" + getString(R.string.kmy_changes_truncated) : ""));
                showRows(t);
            }
            return;
        }
        if (d.zeilen.isEmpty() && !d.defekt) {
            hinweise.add(getString(R.string.kmy_changes_none));
        }
        note.setText(android.text.TextUtils.join("\n", hinweise));
        if (d.zeilen.isEmpty()) {
            return;
        }
        findViewById(R.id.diffScroll).setVisibility(View.VISIBLE);
        RecyclerView lines = findViewById(R.id.diffLines);
        lines.setLayoutManager(new LinearLayoutManager(this));
        final int added = getColor(R.color.diff_added_bg);
        final int removed = getColor(R.color.diff_removed_bg);
        final int gap = getColor(R.color.diff_gap_bg);
        final String gapText = getString(R.string.kmy_changes_gap);
        // Die Nummernspalte so breit wie die größte Nummer – eine KMyMoney-Datei hat schnell
        // fünfstellige Zeilennummern, und eine feste Breite schnitte die führende Ziffer ab.
        View probeZeile = LayoutInflater.from(this)
                .inflate(R.layout.item_export_diff_line, lines, false);
        int groesste = 0;
        for (ExportDiff.Zeile z : d.zeilen) {
            groesste = Math.max(groesste, z.nummer);
        }
        final float dichte = getResources().getDisplayMetrics().density;
        final int nummernBreite = (int) (((TextView) probeZeile.findViewById(R.id.lineNumber))
                .getPaint().measureText(String.valueOf(groesste)) + 20 * dichte);
        lines.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View v = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_export_diff_line, parent, false);
                return new RecyclerView.ViewHolder(v) {
                };
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
                ExportDiff.Zeile z = d.zeilen.get(position);
                TextView number = h.itemView.findViewById(R.id.lineNumber);
                TextView text = h.itemView.findViewById(R.id.lineText);
                if (number.getLayoutParams().width != nummernBreite) {
                    number.getLayoutParams().width = nummernBreite;
                    number.requestLayout();
                }
                switch (z.art) {
                    case ExportDiff.HINZU:
                        h.itemView.setBackgroundColor(added);
                        break;
                    case ExportDiff.ENTFERNT:
                        h.itemView.setBackgroundColor(removed);
                        break;
                    case ExportDiff.LUECKE:
                        h.itemView.setBackgroundColor(gap);
                        break;
                    default:
                        h.itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                }
                boolean luecke = z.art == ExportDiff.LUECKE;
                number.setText(luecke ? "" : String.valueOf(z.nummer));
                // Das Vorzeichen steht zusätzlich zur Farbe da – wer Rot und Grün schlecht trennt,
                // liest es trotzdem.
                text.setText(luecke ? gapText
                        : (z.art == ExportDiff.GLEICH ? "  " : z.art + " ") + z.text);
            }

            @Override
            public int getItemCount() {
                return d.zeilen.size();
            }
        });
        // Die Liste so breit wie ihre längste Zeile, mindestens so breit wie der Bildschirm: Erst mit
        // fester Breite lässt sie sich waagerecht verschieben, ohne dass jede Zeile umbricht.
        TextView probe = probeZeile.findViewById(R.id.lineText);
        float breiteste = 0;
        for (ExportDiff.Zeile z : d.zeilen) {
            breiteste = Math.max(breiteste, probe.getPaint().measureText("  " + z.text));
        }
        ViewGroup.LayoutParams lp = lines.getLayoutParams();
        lp.width = Math.max(getResources().getDisplayMetrics().widthPixels,
                (int) (breiteste + nummernBreite + 24 * dichte));
        lines.setLayoutParams(lp);
    }
    // ---- Datenbank: die Tabellen eines Exports ----

    private String tabellenText(TabellenDiff.Tabelle t) {
        return t.geaendert()
                ? getString(R.string.kmy_changes_table_changed, t.name, t.hinzu, t.entfernt)
                : getString(R.string.kmy_changes_table_same, t.name);
    }

    /** Erst die geänderten Tabellen, antippbar; darunter grau die unveränderten. */
    private void showTables(ExportDiff d, final String name) {
        final List<TabellenDiff.Tabelle> tabellen = new ArrayList<>();
        for (TabellenDiff.Tabelle t : d.tabellen) {
            if (t.geaendert()) {
                tabellen.add(t);
            }
        }
        for (TabellenDiff.Tabelle t : d.tabellen) {
            if (!t.geaendert()) {
                tabellen.add(t);
            }
        }
        RecyclerView list = findViewById(R.id.diffList);
        list.setVisibility(View.VISIBLE);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        final int schwarz = com.google.android.material.color.MaterialColors.getColor(list,
                com.google.android.material.R.attr.colorOnSurface);
        final int grau = getColor(R.color.diff_number);
        list.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View v = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_export_diff, parent, false);
                v.findViewById(R.id.diffSummary).setVisibility(View.GONE);
                return new RecyclerView.ViewHolder(v) {
                };
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
                final TabellenDiff.Tabelle t = tabellen.get(position);
                TextView text = h.itemView.findViewById(R.id.diffWhen);
                text.setText(tabellenText(t));
                text.setTextColor(t.geaendert() ? schwarz : grau);
                if (t.geaendert()) {
                    h.itemView.setOnClickListener(v -> startActivity(
                            new Intent(ExportDiffActivity.this, ExportDiffActivity.class)
                                    .putExtra(EXTRA_NAME, name).putExtra(EXTRA_TABLE, t.name)));
                } else {
                    h.itemView.setOnClickListener(null);
                }
                h.itemView.setClickable(t.geaendert());
            }

            @Override
            public int getItemCount() {
                return tabellen.size();
            }
        });
    }

    // ---- Datenbank: die Sätze einer Tabelle ----

    private static String zelle(String wert) {
        if (wert == null) {
            return "NULL";
        }
        String s = wert.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        return s.length() > MAX_ZELLE ? s.substring(0, MAX_ZELLE) + "…" : s;
    }

    /**
     * Die hinzugekommenen und entfernten Sätze als Tabelle: oben die Spaltennamen, darunter je Satz
     * eine Zeile, grün oder rot. Jede Spalte so breit wie ihr längster Wert.
     */
    private void showRows(final TabellenDiff.Tabelle t) {
        findViewById(R.id.diffScroll).setVisibility(View.VISIBLE);
        RecyclerView lines = findViewById(R.id.diffLines);
        lines.setLayoutManager(new LinearLayoutManager(this));
        final float dichte = getResources().getDisplayMetrics().density;
        final int rand = (int) (8 * dichte);
        final int n = t.spalten.length;
        final android.graphics.Paint stift = ((TextView) LayoutInflater.from(this)
                .inflate(R.layout.item_export_diff_line, lines, false)
                .findViewById(R.id.lineText)).getPaint();
        final int[] breite = new int[n + 1];
        breite[0] = (int) (stift.measureText("+") + 2 * rand);
        int gesamt = breite[0];
        for (int i = 0; i < n; i++) {
            // Der Kopf steht fett da und braucht etwas mehr Platz.
            float b = stift.measureText(t.spalten[i]) * 1.1f;
            for (TabellenDiff.Satz s : t.saetze) {
                if (i < s.werte.length) {
                    b = Math.max(b, stift.measureText(zelle(s.werte[i])));
                }
            }
            breite[i + 1] = (int) (b + 2 * rand);
            gesamt += breite[i + 1];
        }
        final int added = getColor(R.color.diff_added_bg);
        final int removed = getColor(R.color.diff_removed_bg);
        final int kopf = getColor(R.color.diff_gap_bg);
        final int schrift = getColor(R.color.diff_text);
        lines.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                android.widget.LinearLayout zeile = new android.widget.LinearLayout(parent.getContext());
                zeile.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                zeile.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                for (int i = 0; i <= n; i++) {
                    TextView z = new TextView(parent.getContext());
                    z.setTypeface(android.graphics.Typeface.MONOSPACE);
                    z.setTextSize(12);
                    z.setTextColor(schrift);
                    z.setSingleLine(true);
                    z.setPadding(rand, rand / 4, rand, rand / 4);
                    zeile.addView(z, new android.widget.LinearLayout.LayoutParams(breite[i],
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                return new RecyclerView.ViewHolder(zeile) {
                };
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
                ViewGroup zeile = (ViewGroup) h.itemView;
                TabellenDiff.Satz s = position == 0 ? null : t.saetze.get(position - 1);
                zeile.setBackgroundColor(s == null ? kopf
                        : s.art == TabellenDiff.HINZU ? added : removed);
                // Das Vorzeichen steht zusätzlich zur Farbe da.
                ((TextView) zeile.getChildAt(0)).setText(s == null ? "" : String.valueOf(s.art));
                for (int i = 0; i < n; i++) {
                    TextView z = (TextView) zeile.getChildAt(i + 1);
                    z.setTypeface(android.graphics.Typeface.MONOSPACE, s == null
                            ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                    z.setText(s == null ? t.spalten[i] : i < s.werte.length ? zelle(s.werte[i]) : "");
                }
            }

            @Override
            public int getItemCount() {
                return t.saetze.size() + 1;
            }
        });
        ViewGroup.LayoutParams lp = lines.getLayoutParams();
        lp.width = Math.max(getResources().getDisplayMetrics().widthPixels, gesamt);
        lines.setLayoutParams(lp);
    }
}
