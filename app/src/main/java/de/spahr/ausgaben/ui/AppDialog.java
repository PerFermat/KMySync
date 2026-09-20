package de.spahr.ausgaben.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.ButtonBarLayout;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.shape.MaterialShapeDrawable;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.settings.AccentColor;

/**
 * Jeder Dialog der App. Bündelt, was alle gemeinsam haben: den Theme-Aufsatz mit 16dp-Radius und
 * einem Kopfband sowie Rahmen um das ganze Fenster, beide in der Akzentfarbe des aktiven Profils
 * (siehe {@link AccentColor}) – Dialoge laufen in einem eigenen Fenster und ziehen sonst nicht mit.
 *
 * <p>Der Rahmen entsteht nicht über eine eigene Zeichnung, sondern am Hintergrund, den
 * {@link MaterialAlertDialogBuilder} ohnehin anlegt: der behält seine Form und seine Ränder, und es
 * kommt nur der Strich dazu. So bleibt der Dialog in jeder Bildschirmgröße dort, wo Material ihn
 * hinstellt.</p>
 *
 * <p>Zerstörende Abfragen (löschen, zurücksetzen) über {@link #destructive(Context)} – dort bleibt die
 * bestätigende Taste rot statt in der Profilfarbe.</p>
 */
public class AppDialog extends MaterialAlertDialogBuilder {

    /**
     * Legt einen Dialoginhalt in einen scrollbaren Rahmen.
     *
     * <p>Ein {@code AlertDialog} scrollt seine eigene Ansicht nicht: Was nicht hineinpasst, wird
     * abgeschnitten. Quer fällt das auf, sobald eine Rechentastatur im Dialog steht — dort blieb die
     * unterste Reihe weg, und mit ihr die OK-Taste, die den Betrag übernimmt.</p>
     *
     * <p>Die Tastatur kleiner zu messen wäre der scheinbar elegantere Weg und war der erste Versuch:
     * Sie passte dann hinein, war aber nicht mehr zu lesen. Lieber die gewohnte Größe und ein Dialog,
     * durch den man schiebt — quer erfasst ohnehin kaum jemand eine Buchung.</p>
     *
     * @return der Rahmen, der als {@code setView(...)} zu übergeben ist
     */
    public static View scrollable(View content) {
        android.widget.ScrollView rahmen = new android.widget.ScrollView(content.getContext());
        // Ohne das säße der Inhalt bei viel Platz oben und der Dialog bliebe unnötig hoch leer.
        rahmen.setFillViewport(true);
        // Der Elevation-Schatten der Tasten reicht über ihren Rand hinaus – sonst kappt ihn der Rahmen.
        rahmen.setClipToPadding(false);
        rahmen.addView(content, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return rahmen;
    }

    /** Stärke des Rahmens in dp. */
    private static final float STROKE_DP = 2f;

    /** true bei {@link #destructive}: die bestätigende Taste bleibt rot statt der Profilfarbe. */
    private final boolean destructive;

    public AppDialog(@NonNull Context context) {
        super(context, R.style.ThemeOverlay_Ausgaben_Dialog);
        this.destructive = false;
    }

    private AppDialog(@NonNull Context context, int themeOverlay, boolean destructive) {
        super(context, themeOverlay);
        this.destructive = destructive;
    }

    /** Abfrage, die etwas unwiderruflich entfernt: bestätigende Taste rot. */
    public static AppDialog destructive(@NonNull Context context) {
        return new AppDialog(context, R.style.ThemeOverlay_Ausgaben_Dialog_Destructive, true);
    }

    /** Überschrift; sie wandert in das eigene Kopfband statt in den Material-Titel. */
    private CharSequence title;
    /** Über {@link #setView(View)} gesetzter Inhalt – zum Einfärben in {@link #create()} gemerkt. */
    private View customView;

    @NonNull
    @Override
    public AppDialog setTitle(CharSequence text) {
        this.title = text;
        return this;
    }

    @NonNull
    @Override
    public AppDialog setTitle(int textId) {
        return setTitle(getContext().getText(textId));
    }

    @NonNull
    @Override
    public AppDialog setView(View view) {
        this.customView = view;
        super.setView(view);
        return this;
    }

    @NonNull
    @Override
    @SuppressLint("InflateParams")   // Dialoginhalt, siehe Begruendung an der inflate-Zeile
    public AlertDialog create() {
        int color = AccentColor.current(getContext());
        View band = null;
        if (title != null) {
            // inflate(..., null, false) ist bei einem Dialog richtig: Die Ansicht geht an setView und hat zu
            // diesem Zeitpunkt keine Eltern, von denen sie Layout-Vorgaben übernehmen könnte. Lint meldet das
            // trotzdem als InflateParams.
            band = LayoutInflater.from(getContext()).inflate(R.layout.dialog_title, null, false);
            ((TextView) band.findViewById(R.id.dialogTitle)).setText(title);
            ((TextView) band.findViewById(R.id.dialogTitle)).setTextColor(AccentColor.contrastColor(color));
            Drawable bandBg = band.getBackground();
            if (bandBg != null) {
                bandBg = bandBg.mutate();
                if (bandBg instanceof GradientDrawable) {
                    ((GradientDrawable) bandBg).setColor(color);
                }
            }
            setCustomTitle(band);
        }
        AlertDialog dialog = super.create();
        frame(dialog, color);
        AccentColor.applyToDialog(dialog, !destructive, customView);
        if (band != null) {
            band.findViewById(R.id.dialogClose).setOnClickListener(v -> dialog.cancel());
        }
        keepButtonsInOneRow(dialog);
        return dialog;
    }

    /**
     * Die Tastenzeile darf nicht umbrechen. Material legt sie in eine {@code ScrollView}: gestapelte
     * Tasten wachsen dort nicht nach unten heraus, sondern verschwinden im Bildlauf – man sieht nur
     * noch die oberste. Nebeneinander ist auch das Gewohnte.
     */
    private static void keepButtonsInOneRow(AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            unstack(window.getDecorView());
        }
    }

    /**
     * Sucht im Dialogfenster die Tastenzeile und verbietet ihr das Stapeln.
     *
     * <p>{@link ButtonBarLayout} ist in {@code androidx.appcompat} mit {@code @RestrictTo} versehen,
     * Lint meldet das als {@code RestrictedApi}. Einen unterstützten Weg gibt es nicht: Ob gestapelt
     * wird, entscheidet allein {@code setAllowStacking}, und weder {@code AlertDialog} noch
     * {@code MaterialAlertDialogBuilder} reichen das nach außen.</p>
     *
     * <p>Das Risiko ist tragbar, weil der Fehlerfall <b>laut</b> ist: Verschwindet die Klasse aus der
     * Bibliothek, bricht der Bau. Sie still zu umgehen — etwa über den Klassennamen statt über den Typ —
     * wäre das Gegenteil: Die Zeile stapelte wieder, und niemand merkte es. Findet {@code unstack}
     * nichts, bleibt es bei Materials Verhalten.</p>
     */
    @SuppressLint("RestrictedApi")
    private static void unstack(View view) {
        if (view instanceof ButtonBarLayout) {
            ((ButtonBarLayout) view).setAllowStacking(false);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                unstack(group.getChildAt(i));
            }
        }
    }

    /**
     * Legt den grünen Strich um das Dialogfenster. Der Hintergrund ist eine {@link InsetDrawable} um
     * eine {@link MaterialShapeDrawable}; passt das nicht (etwa nach einem Bibliotheks-Wechsel),
     * bleibt der Dialog eben ohne Rahmen – kein Grund, ihn gar nicht zu zeigen.
     */
    private static void frame(AlertDialog dialog, int color) {
        Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        Drawable background = window.getDecorView().getBackground();
        if (background instanceof InsetDrawable) {
            background = ((InsetDrawable) background).getDrawable();
        }
        if (!(background instanceof MaterialShapeDrawable)) {
            return;
        }
        float stroke = STROKE_DP * dialog.getContext().getResources().getDisplayMetrics().density;
        ((MaterialShapeDrawable) background).setStroke(stroke, color);
    }
}
