package de.spahr.ausgaben.ui;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Der Rückweg von der Knopf-Kennung zum Index in der Kandidatenliste.
 *
 * <p>Die Knöpfe der Anker-Auswahl entstehen zur Laufzeit und tragen künstliche Kennungen {@code 1..n}
 * — die {@code 0} ist bei einer {@link RadioGroup} für „nichts gewählt" vergeben. Bis 2.1 rechnete der
 * Rückweg {@code getCheckedRadioButtonId() - 1}. Das lief, band die Auswertung aber an den Zähler der
 * Schleife, die die Knöpfe anlegt: Wer dort ansetzte, verschob still die Zuordnung.</p>
 *
 * <p>Der letzte Fall ist der, der zählt: Steht kein Knopf, muß {@code -1} herauskommen. Beide
 * Aufrufer prüfen darauf und verstehen es als „die App entscheiden lassen" — käme hier {@code 0}
 * heraus, wählte die App stillschweigend den ersten Kandidaten.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class AnchorChoiceIndexTest {

    private RadioGroup gruppe;

    @Before
    public void gruppeMitDreiKnoepfen() {
        Context ctx = ApplicationProvider.getApplicationContext();
        gruppe = new RadioGroup(ctx);
        for (int i = 0; i < 3; i++) {
            RadioButton knopf = new RadioButton(ctx);
            knopf.setId(i + 1);   // wie in showSplitAnchorChoice/buildAnchorChoiceDialog
            gruppe.addView(knopf);
        }
    }

    @Test
    public void derErsteKnopfIstIndexNull() {
        gruppe.check(1);
        assertEquals(0, SecurityTxEditActivity.gewaehlterIndex(gruppe));
    }

    @Test
    public void derLetzteKnopfIstDerLetzteIndex() {
        gruppe.check(3);
        assertEquals(2, SecurityTxEditActivity.gewaehlterIndex(gruppe));
    }

    @Test
    public void ohneAuswahlKommtMinusEinsHeraus() {
        assertEquals(-1, SecurityTxEditActivity.gewaehlterIndex(gruppe));
    }

    /**
     * Und nach einem Zurücknehmen der Auswahl ebenso: {@code clearCheck()} setzt die Kennung auf
     * {@code View.NO_ID}, und darauf muß {@code findViewById} mit {@code null} antworten statt zu suchen.
     */
    @Test
    public void nachClearCheckEbenso() {
        gruppe.check(2);
        gruppe.clearCheck();
        assertEquals(-1, SecurityTxEditActivity.gewaehlterIndex(gruppe));
    }
}
