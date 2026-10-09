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
        return getString(R.string.kmy_changes_summary, d.hinzu, d.entfernt, d.datei);
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
                if (e.kopf.abweichung) {
                    text = text + "\n" + getString(R.string.kmy_changes_differs);
                }
                ((TextView) h.itemView.findViewById(R.id.diffSummary)).setText(text);
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
        if (d.abweichung) {
            hinweise.add(getString(R.string.kmy_changes_differs));
        }
        if (d.gekuerzt) {
            hinweise.add(getString(R.string.kmy_changes_truncated));
        }
        if (d.zeilen.isEmpty()) {
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
        TextView probe = LayoutInflater.from(this)
                .inflate(R.layout.item_export_diff_line, lines, false).findViewById(R.id.lineText);
        float breiteste = 0;
        for (ExportDiff.Zeile z : d.zeilen) {
            breiteste = Math.max(breiteste, probe.getPaint().measureText("  " + z.text));
        }
        float dichte = getResources().getDisplayMetrics().density;
        ViewGroup.LayoutParams lp = lines.getLayoutParams();
        lp.width = Math.max(getResources().getDisplayMetrics().widthPixels,
                (int) (breiteste + (64 + 16 + 8) * dichte));
        lines.setLayoutParams(lp);
    }
}
