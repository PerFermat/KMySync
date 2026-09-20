package de.spahr.ausgaben.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.ImageViewCompat;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import de.spahr.ausgaben.R;

/**
 * Die drei Knöpfe einer Regelzeile müssen gefärbt sein.
 *
 * <p>Die Zeichnungen sind für die grüne Titelleiste gemacht und weiß. In der Regelzeile stehen sie auf
 * hellem Grund: ohne Färbung sind sie unsichtbar — Knöpfe, die man nur zufällig trifft.</p>
 *
 * <p>Bis 2.1 stand dort {@code android:tint}, seither {@code app:tint} (Lint: {@code UseAppTint}).</p>
 *
 * <p><b>Was dieser Test nicht leistet:</b> zwischen den beiden Schreibweisen unterscheiden. Ab API 21
 * liest {@link ImageViewCompat#getImageTintList} die Färbung des Systems, und die setzt
 * {@code android:tint} genauso — in der Gegenprobe blieb der Test mit der alten Schreibweise grün. Der
 * Umbau folgte also der Konvention, er reparierte nichts.</p>
 *
 * <p><b>Was er leistet:</b> Er hält fest, daß die Knöpfe überhaupt gefärbt sind und daß AppCompat sie
 * aufbläst. Letzteres ist die Bedingung dafür, daß {@code app:tint} <em>überhaupt</em> etwas tut: Käme
 * der Inflater je von einem anderen Kontext — etwa vom Anwendungskontext statt von der Maske —, bliebe
 * die Eigenschaft wirkungslos und die weißen Symbole verschwänden still im hellen Hintergrund. Das wäre
 * ein echter Rückschritt gegenüber {@code android:tint}, und genau den fängt der Typ-Test ab.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class AnchorRowTintTest {

    private AppCompatActivity maske;
    private View zeile;

    @Before
    public void zeileAufblasen() {
        maske = Robolectric.buildActivity(AppCompatActivity.class).setup().get();
        zeile = LayoutInflater.from(maske).inflate(R.layout.item_statement_anchor, null);
    }

    @Test
    public void appCompatBlaestDieKnoepfeAuf() {
        for (int id : new int[]{R.id.btnAnchorUp, R.id.btnAnchorDown, R.id.btnAnchorRemove}) {
            View knopf = zeile.findViewById(id);
            assertTrue("Knopf " + id + " ist ein " + knopf.getClass().getName()
                            + " — ohne AppCompat bleibt app:tint wirkungslos",
                    knopf instanceof androidx.appcompat.widget.AppCompatImageButton);
        }
    }

    @Test
    public void dieBeidenPfeileSindGruen() {
        int gruen = maske.getResources().getColor(R.color.green_primary, maske.getTheme());
        assertEquals(gruen, farbeVon(R.id.btnAnchorUp));
        assertEquals(gruen, farbeVon(R.id.btnAnchorDown));
    }

    /** Rot wie jeder Löschknopf der App — das Wegwerfen soll sich vom Verschieben unterscheiden. */
    @Test
    public void derLoeschknopfIstRot() {
        assertEquals(maske.getResources().getColor(R.color.expense_red, maske.getTheme()),
                farbeVon(R.id.btnAnchorRemove));
    }

    private int farbeVon(int id) {
        ImageButton knopf = zeile.findViewById(id);
        ColorStateList tint = ImageViewCompat.getImageTintList(knopf);
        assertNotNull("Keine Färbung gesetzt — steht im Layout wieder android:tint?", tint);
        return tint.getDefaultColor();
    }
}
