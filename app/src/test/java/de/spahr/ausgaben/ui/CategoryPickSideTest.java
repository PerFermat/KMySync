package de.spahr.ausgaben.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Parcel;
import android.widget.AutoCompleteTextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.SecurityTx;
import de.spahr.ausgaben.util.CategorySplits;

/**
 * Gibt es eine Kategorie unter demselben Namen bei den Ausgaben und bei den Einnahmen, zählt der
 * Eintrag, der gewählt wurde – nicht der erste, der so heißt. Und der Entwurf einer Abrechnung trägt
 * die Seite bis in die Bewegung.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CategoryPickSideTest {

    private static final String BEIDE = "Versicherung:Krankenzusatz";

    private final Context ctx = ApplicationProvider.getApplicationContext();

    private CategoryFilterAdapter liste() {
        return new CategoryFilterAdapter(ctx, null,
                "Ausgaben", Arrays.asList("Essen", BEIDE),
                "Einnahmen", Arrays.asList("Gehalt", BEIDE));
    }

    /** Der Eintrag der Liste mit diesem Wert in dieser Gruppe. */
    private static CategoryFilterAdapter.CatItem eintrag(CategoryFilterAdapter a, String wert,
                                                         boolean einnahme) {
        for (int i = 0; i < a.getCount(); i++) {
            CategoryFilterAdapter.CatItem item = a.getItem(i);
            if (item != null && item.value.equals(wert) && item.groupIsIncome == einnahme) {
                return item;
            }
        }
        throw new AssertionError("kein Eintrag " + wert + " / " + einnahme);
    }

    @Test
    public void derNameAlleinEntscheidetNurWennErEindeutigIst() {
        CategoryFilterAdapter a = liste();
        assertEquals(Boolean.FALSE, a.sideOfName("Essen"));
        assertEquals(Boolean.TRUE, a.sideOfName("gehalt"));
        assertNull(a.sideOfName(BEIDE));
        assertNull(a.sideOfName("Gibt es nicht"));
    }

    @Test
    public void derGewaehlteEintragEntscheidet() {
        CategoryFilterAdapter a = liste();
        AutoCompleteTextView feld = new AutoCompleteTextView(ctx);

        // Getippt und stehengelassen: Der Name gibt es nicht her.
        assertNull(a.sideIn(feld, BEIDE));

        feld.setTag(R.id.pickerItem, eintrag(a, BEIDE, true));
        assertEquals(Boolean.TRUE, a.sideIn(feld, BEIDE));
        feld.setTag(R.id.pickerItem, eintrag(a, BEIDE, false));
        assertEquals(Boolean.FALSE, a.sideIn(feld, BEIDE));

        // Steht inzwischen etwas anderes im Feld, gilt der frühere Eintrag nicht mehr.
        assertEquals(Boolean.TRUE, a.sideIn(feld, "Gehalt"));
    }

    @Test
    public void entwurfTraegtDieSeiteDurchDasParcelBisInDieBewegung() {
        StatementDraft d = new StatementDraft();
        d.kmyId = "S000002";
        d.action = StatementDraft.DIVIDEND;
        d.dateMillis = 1_755_000_000_000L;
        d.grossCents = 10_000L;
        d.netCents = 8_000L;
        d.feeCents = 2_000L;
        d.moneyAccount = "Giro";
        d.fixedFeeCategory = "Gebühren";
        d.fixedFeeCategoryIsIncome = Boolean.FALSE;
        d.feeParts.add(new CategorySplits.Part(BEIDE, 2_000L, "Steuer", Boolean.FALSE));
        d.incomeParts.add(new CategorySplits.Part(BEIDE, 10_000L, "Ertrag", Boolean.TRUE));

        Parcel p = Parcel.obtain();
        d.writeToParcel(p, 0);
        p.setDataPosition(0);
        StatementDraft z = StatementDraft.CREATOR.createFromParcel(p);
        p.recycle();

        assertEquals(Boolean.FALSE, z.fixedFeeCategoryIsIncome);
        assertEquals(Boolean.FALSE, z.feeParts.get(0).categoryIsIncome);
        assertEquals(Boolean.TRUE, z.incomeParts.get(0).categoryIsIncome);

        SecurityTx tx = z.toTx();
        assertEquals(2, tx.parts.size());
        assertFalse(tx.parts.get(0).income);
        assertEquals(Boolean.FALSE, tx.parts.get(0).categoryIsIncome);
        assertTrue(tx.parts.get(1).income);
        assertEquals(Boolean.TRUE, tx.parts.get(1).categoryIsIncome);
    }
}
