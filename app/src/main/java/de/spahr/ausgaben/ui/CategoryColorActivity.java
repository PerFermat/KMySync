package de.spahr.ausgaben.ui;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.List;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.settings.CategoryColorStore;

/**
 * Farb-Editor der Kategorien-Auswertung: listet alle Kategorien mit ihrem aktuellen Farb-Swatch; ein Tipp
 * öffnet die Palette zur Auswahl (oder „Standard" zum Zurücksetzen). Die Zuordnung wird in
 * {@link CategoryColorStore} gespeichert und wirkt sofort in {@link CategoryChartActivity}.
 */
public class CategoryColorActivity extends LocalizedActivity {

    private Repository repository;
    private CategoryColorStore colors;
    private LinearLayout listView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_category_colors);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        repository = new Repository(this);
        colors = new CategoryColorStore(this);
        listView = findViewById(R.id.colorList);

        repository.getCategoryNames(names -> {
            listView.removeAllViews();
            if (names != null) {
                for (String category : names) {
                    if (category != null && !category.isEmpty()) {
                        listView.addView(buildRow(category));
                    }
                }
            }
        });
    }

    private View buildRow(String category) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        row.setClickable(true);
        row.setFocusable(true);

        View swatch = new View(this);
        swatch.setBackground(swatchDrawable(colors.colorFor(category)));
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24));
        swLp.setMarginEnd(Ui.dp(this, 16));
        row.addView(swatch, swLp);

        TextView name = new TextView(this);
        name.setText(category);
        name.setTextSize(16);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(v -> showPicker(category, swatch));
        return row;
    }

    private void showPicker(String category, View swatch) {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(5);
        int pad = Ui.dp(this, 16);
        grid.setPadding(pad, pad, pad, pad);

        AlertDialog dialog = new AppDialog(this)
                .setTitle(R.string.category_color_pick)
                .setView(grid)
                .setNeutralButton(R.string.category_color_default, (d, w) -> {
                    colors.clear(category);
                    swatch.setBackground(swatchDrawable(colors.colorFor(category)));
                })
                .create();

        for (int color : CategoryColorStore.PALETTE) {
            View cell = new View(this);
            cell.setBackground(swatchDrawable(color));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = Ui.dp(this, 40);
            lp.height = Ui.dp(this, 40);
            lp.setMargins(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
            cell.setLayoutParams(lp);
            cell.setOnClickListener(v -> {
                colors.setColor(category, color);
                swatch.setBackground(swatchDrawable(color));
                dialog.dismiss();
            });
            grid.addView(cell);
        }
        dialog.show();
    }

    /** Runder Farbtupfer mit dünnem Rand (auch helle Farben bleiben sichtbar). */
    private GradientDrawable swatchDrawable(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(Ui.dp(this, 1), 0x33000000);
        return d;
    }

}
