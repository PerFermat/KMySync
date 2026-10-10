package de.spahr.ausgaben.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.export.ExportDiff;
import de.spahr.ausgaben.export.ExportDiffStore;
import de.spahr.ausgaben.export.TabellenDiff;
import de.spahr.ausgaben.settings.ProfileManager;

/**
 * Die Ansicht der Datei-Änderungen lässt sich öffnen und zeigt, was abgelegt ist – als Liste und als
 * Zeilen. Kein Ersatz für den Blick aufs Gerät, aber ein Absturz beim Aufbau fiele hier auf.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ExportDiffActivityTest {

    private final Context ctx = ApplicationProvider.getApplicationContext();

    private ExportDiffStore store() {
        return new ExportDiffStore(ExportDiffStore.ordnerFuer(ctx.getFilesDir(),
                new ProfileManager(ctx).getActiveProfileId()));
    }

    private static ExportDiff beispiel() {
        StringBuilder alt = new StringBuilder();
        for (int i = 1; i <= 80; i++) {
            alt.append("Zeile ").append(i).append('\n');
        }
        String neu = alt.toString().replace("Zeile 10\n", "Zeile 10\nNEU\n")
                .replace("Zeile 60\n", "");
        ExportDiff d = ExportDiff.von(alt.toString(), neu);
        d.zeit = 1_780_000_000_000L;
        d.datei = "michael.kmy";
        return d;
    }

    @Test
    public void leereListeZeigtDenHinweis() {
        ExportDiffActivity a = Robolectric.buildActivity(ExportDiffActivity.class).setup().get();
        TextView note = a.findViewById(R.id.diffNote);
        assertEquals(a.getString(R.string.kmy_changes_empty), note.getText().toString());
    }

    @Test
    public void defektAngekommen_stehtInListeUndUeberDenZeilen() throws Exception {
        String name = "michael.kmy.bak-20260601-100000";
        ExportDiff d = beispiel();
        d.defekt = true;
        d.abweichung = true;
        store().speichere(name, d);

        ExportDiffActivity liste = Robolectric.buildActivity(ExportDiffActivity.class).setup().get();
        RecyclerView list = liste.findViewById(R.id.diffList);
        list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST));
        list.layout(0, 0, 1080, 1920);
        String zeile = ((TextView) list.getChildAt(0).findViewById(R.id.diffSummary))
                .getText().toString();
        assertTrue(zeile.contains(ctx.getString(R.string.kmy_changes_defect)));
        assertTrue(zeile.contains("Backup/" + name));

        d.wiederhergestellt = true;
        store().speichere(name, d);
        ExportDiffActivity zeilen = Robolectric.buildActivity(ExportDiffActivity.class,
                new Intent(ctx, ExportDiffActivity.class)
                        .putExtra(ExportDiffActivity.EXTRA_NAME, name)).setup().get();
        String note = ((TextView) zeilen.findViewById(R.id.diffNote)).getText().toString();
        assertTrue(note.contains(ctx.getString(R.string.kmy_changes_defect)));
        assertTrue(note.contains(ctx.getString(R.string.kmy_changes_restored)));
        assertFalse(note.contains(ctx.getString(R.string.kmy_changes_differs)));
    }

    /** Eine Datenbank: erst die Tabellen – geänderte vorn, unveränderte grau –, dann die Sätze. */
    @Test
    public void datenbank_tabellenUndSaetze() throws Exception {
        String name = "test.sqlite.bak-20260601-100000";
        TabellenDiff.Tabelle gleich = new TabellenDiff.Tabelle("kmmPayees");
        gleich.spalten = new String[]{"id", "name"};
        TabellenDiff.Tabelle anders = new TabellenDiff.Tabelle("kmmSplits");
        anders.spalten = new String[]{"transactionId", "splitId", "memo"};
        anders.hinzu = 2;
        anders.entfernt = 1;
        anders.saetze.add(new TabellenDiff.Satz(TabellenDiff.ENTFERNT, new String[]{"T1", "0", "alt"}));
        anders.saetze.add(new TabellenDiff.Satz(TabellenDiff.HINZU, new String[]{"T1", "0", null}));
        anders.saetze.add(new TabellenDiff.Satz(TabellenDiff.HINZU, new String[]{"T2", "0", "neu"}));
        ExportDiff d = ExportDiff.ausTabellen(java.util.Arrays.asList(gleich, anders));
        d.zeit = 1_780_000_000_000L;
        d.datei = "test.sqlite";
        store().speichere(name, d);

        ExportDiffActivity tabellen = Robolectric.buildActivity(ExportDiffActivity.class,
                new Intent(ctx, ExportDiffActivity.class)
                        .putExtra(ExportDiffActivity.EXTRA_NAME, name)).setup().get();
        RecyclerView list = tabellen.findViewById(R.id.diffList);
        assertEquals(2, list.getAdapter().getItemCount());
        list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST));
        list.layout(0, 0, 1080, 1920);
        TextView erste = list.getChildAt(0).findViewById(R.id.diffWhen);
        TextView zweite = list.getChildAt(1).findViewById(R.id.diffWhen);
        assertEquals(ctx.getString(R.string.kmy_changes_table_changed, "kmmSplits", 2, 1),
                erste.getText().toString());
        assertEquals(ctx.getString(R.string.kmy_changes_table_same, "kmmPayees"),
                zweite.getText().toString());
        assertTrue(list.getChildAt(0).isClickable());
        assertFalse(list.getChildAt(1).isClickable());
        assertTrue(erste.getCurrentTextColor() != zweite.getCurrentTextColor());

        ExportDiffActivity saetze = Robolectric.buildActivity(ExportDiffActivity.class,
                new Intent(ctx, ExportDiffActivity.class)
                        .putExtra(ExportDiffActivity.EXTRA_NAME, name)
                        .putExtra(ExportDiffActivity.EXTRA_TABLE, "kmmSplits")).setup().get();
        assertEquals(View.VISIBLE, saetze.findViewById(R.id.diffScroll).getVisibility());
        RecyclerView lines = saetze.findViewById(R.id.diffLines);
        // Die Kopfzeile mit den Spaltennamen und drei Sätze.
        assertEquals(4, lines.getAdapter().getItemCount());
        lines.measure(View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST));
        lines.layout(0, 0, 2000, 1920);
        ViewGroup kopf = (ViewGroup) lines.getChildAt(0);
        assertEquals("transactionId", ((TextView) kopf.getChildAt(1)).getText().toString());
        ViewGroup zweiter = (ViewGroup) lines.getChildAt(2);
        assertEquals("+", ((TextView) zweiter.getChildAt(0)).getText().toString());
        assertEquals("NULL", ((TextView) zweiter.getChildAt(3)).getText().toString());
    }

    @Test
    public void listeUndZeilen() throws Exception {
        store().speichere("michael.kmy.bak-20260601-100000", beispiel());

        ExportDiffActivity liste = Robolectric.buildActivity(ExportDiffActivity.class).setup().get();
        RecyclerView list = liste.findViewById(R.id.diffList);
        assertEquals(View.VISIBLE, list.getVisibility());
        assertEquals(1, list.getAdapter().getItemCount());

        ExportDiffActivity zeilen = Robolectric.buildActivity(ExportDiffActivity.class,
                new Intent(ctx, ExportDiffActivity.class).putExtra(ExportDiffActivity.EXTRA_NAME,
                        "michael.kmy.bak-20260601-100000")).setup().get();
        assertEquals(View.VISIBLE, zeilen.findViewById(R.id.diffScroll).getVisibility());
        RecyclerView lines = zeilen.findViewById(R.id.diffLines);
        assertNotNull(lines.getAdapter());
        // Zwei Änderungen weit auseinander: zwei Blöcke und dazwischen die Lücke.
        assertEquals(beispiel().zeilen.size(), lines.getAdapter().getItemCount());
        assertTrue(lines.getLayoutParams().width >= ctx.getResources().getDisplayMetrics().widthPixels);
        // Die Zeilen lassen sich auch wirklich aufbauen und füllen.
        for (int i = 0; i < lines.getAdapter().getItemCount(); i++) {
            RecyclerView.ViewHolder h = lines.getAdapter().createViewHolder(lines, 0);
            lines.getAdapter().bindViewHolder(h, i);
            assertNotNull(((TextView) h.itemView.findViewById(R.id.lineText)).getText());
        }
    }
}
