package de.spahr.ausgaben.ui;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.GridLayout;

import androidx.appcompat.app.AlertDialog;

/** Farbraster-Dialog – für Kategorien ({@link de.spahr.ausgaben.settings.CategoryColorStore#PALETTE}) wie Profil-Akzentfarbe. */
public final class ColorPickerDialog {

    private ColorPickerDialog() {
    }

    public interface OnColorPicked {
        void onColorPicked(int color);
    }

    public static void show(Activity activity, int titleRes, int[] palette, OnColorPicked onPicked) {
        GridLayout grid = new GridLayout(activity);
        grid.setColumnCount(5);
        int pad = Ui.dp(activity, 16);
        grid.setPadding(pad, pad, pad, pad);

        AlertDialog dialog = new AppDialog(activity)
                .setTitle(titleRes)
                .setView(grid)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        for (int color : palette) {
            View cell = new View(activity);
            cell.setBackground(swatchDrawable(color));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = Ui.dp(activity, 40);
            lp.height = Ui.dp(activity, 40);
            lp.setMargins(Ui.dp(activity, 6), Ui.dp(activity, 6), Ui.dp(activity, 6), Ui.dp(activity, 6));
            cell.setLayoutParams(lp);
            cell.setOnClickListener(v -> {
                dialog.dismiss();
                onPicked.onColorPicked(color);
            });
            grid.addView(cell);
        }
        dialog.show();
    }

    /** Runder Farbtupfer mit dünnem Rand (auch helle Farben bleiben sichtbar). */
    public static GradientDrawable swatchDrawable(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(2, 0x33000000);
        return d;
    }

}
