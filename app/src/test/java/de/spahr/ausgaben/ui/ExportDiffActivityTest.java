package de.spahr.ausgaben.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.view.View;
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
