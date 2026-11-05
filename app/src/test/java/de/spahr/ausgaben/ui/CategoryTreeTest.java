package de.spahr.ausgaben.ui;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.os.Looper;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import de.spahr.ausgaben.R;

/**
 * Kategorien mit mehr als zwei Ebenen stehen als Baum in der Liste: jede Zeile nur mit ihrem eigenen
 * Namen, statt „Auto:Benzin" unter „Fahrtkosten" (Issue #11). Gewählt wird weiter der volle Pfad.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CategoryTreeTest {

    /** Mit dem Theme der App – sonst fehlen der Listenzeile ihre Attribute beim Aufblasen. */
    private final Context ctx = new android.view.ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(), R.style.Theme_Ausgaben);

    private CategoryFilterAdapter liste(String... ausgaben) {
        return new CategoryFilterAdapter(ctx, null, "Ausgaben", Arrays.asList(ausgaben),
                "Einnahmen", Collections.singletonList("Gehalt"));
    }

    private static final String[] BAUM = {
            "Fahrtkosten", "Fahrtkosten:Auto", "Fahrtkosten:Auto:Benzin",
            "Fahrtkosten:Auto:Parkkosten", "Fahrtkosten:Bahn", "Haushalt"};

    /** Sucht und wartet, bis das Ergebnis im Adapter steht – der Suchlauf läuft nebenher. */
    private void suche(CategoryFilterAdapter adapter, String query) throws InterruptedException {
        CountDownLatch fertig = new CountDownLatch(1);
        adapter.getFilter().filter(query, count -> fertig.countDown());
        for (int i = 0; i < 50 && fertig.getCount() > 0; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (fertig.await(20, TimeUnit.MILLISECONDS)) {
                break;
            }
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static List<String> spalte(CategoryFilterAdapter a, boolean werte) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.getCount(); i++) {
            CategoryFilterAdapter.CatItem it = a.getItem(i);
            out.add(werte ? it.value : it.label);
        }
        return out;
    }

    @Test
    public void jedeZeileTraegtNurIhrenEigenenNamen() {
        CategoryFilterAdapter a = liste(BAUM);
        assertEquals(Arrays.asList("Ausgaben", "Fahrtkosten", "Auto", "Benzin", "Parkkosten", "Bahn",
                "Haushalt", "Einnahmen", "Gehalt"), spalte(a, false));
        assertEquals(Arrays.asList("", "Fahrtkosten", "Fahrtkosten:Auto", "Fahrtkosten:Auto:Benzin",
                "Fahrtkosten:Auto:Parkkosten", "Fahrtkosten:Bahn", "Haushalt", "", "Gehalt"),
                spalte(a, true));
        List<Integer> tiefen = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            tiefen.add(a.getItem(i).depth);
        }
        assertEquals(Arrays.asList(0, 1, 2, 2, 1, 0), tiefen);
        assertEquals(false, a.getItem(3).lastChild);  // Benzin: ├
        assertEquals(true, a.getItem(4).lastChild);   // Parkkosten: └
    }

    @Test
    public void fehlendesZwischengliedEntstehtVonSelbst() {
        CategoryFilterAdapter a = liste("Fahrtkosten:Auto:Benzin");
        assertEquals(Arrays.asList("", "Fahrtkosten", "Fahrtkosten:Auto", "Fahrtkosten:Auto:Benzin",
                "", "Gehalt"), spalte(a, true));
        assertEquals("Fahrtkosten:Auto", a.knownForm("fahrtkosten:auto"));
    }

    @Test
    public void trefferBringtSeineVorfahrenMit() throws InterruptedException {
        CategoryFilterAdapter a = liste(BAUM);
        suche(a, "benz");
        assertEquals(Arrays.asList("Ausgaben", "Fahrtkosten", "Auto", "Benzin"), spalte(a, false));
    }

    @Test
    public void passendeKategorieZeigtAllesDarunter() throws InterruptedException {
        CategoryFilterAdapter a = liste(BAUM);
        suche(a, "auto");
        assertEquals(Arrays.asList("Ausgaben", "Fahrtkosten", "Auto", "Benzin", "Parkkosten"),
                spalte(a, false));
    }

    @Test
    public void sucheUeberDieEbenenHinweg() throws InterruptedException {
        CategoryFilterAdapter a = liste(BAUM);
        suche(a, "auto:park");
        assertEquals(Arrays.asList("Ausgaben", "Fahrtkosten", "Auto", "Parkkosten"), spalte(a, false));
    }

    @Test
    public void gewaehltWirdDerVollePfad() {
        CategoryFilterAdapter a = liste(BAUM);
        assertEquals("Fahrtkosten:Auto:Benzin",
                a.getFilter().convertResultToString(a.getItem(3)).toString());
    }

    @Test
    public void dritteEbeneZeigtBaumlinieVorDemNamen() {
        CategoryFilterAdapter a = liste(BAUM);
        FrameLayout parent = new FrameLayout(ctx);
        TextView benzin = a.getView(3, null, parent).findViewById(R.id.pickerText);
        TextView park = a.getView(4, null, parent).findViewById(R.id.pickerText);
        TextView auto = a.getView(2, null, parent).findViewById(R.id.pickerText);
        assertEquals("├ Benzin", benzin.getText().toString());
        assertEquals("└ Parkkosten", park.getText().toString());
        assertEquals("Auto", auto.getText().toString());
    }
}
