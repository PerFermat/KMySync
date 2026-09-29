package de.spahr.ausgaben.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;

import com.google.android.material.textfield.TextInputEditText;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import de.spahr.ausgaben.R;

/**
 * Die Splitbuchung im Buchungseditor: Kategoriezeilen, Teilbeträge und der Gesamtbetrag.
 *
 * <p>Hier steckt Logik in der Bedienung, nicht in einer Formel — und zwar zwei Betriebsarten, zwischen
 * denen die Maske stillschweigend wechselt. Im <b>Rest-Modus</b> steht der Gesamtbetrag fest, den der
 * Nutzer eingetippt hat, und die erste Kategoriezeile trägt, was übrig bleibt. Im <b>Summen-Modus</b>
 * ist es umgekehrt: die Teilbeträge stehen fest und der Gesamtbetrag folgt ihnen.</p>
 *
 * <p>Welche Art gerade gilt, hängt an der Reihenfolge der Handgriffe. Das ließ sich bisher nur von Hand
 * durchspielen — und wer prüft schon nach jeder Änderung sechs Abläufe durch.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SplitRowBehaviourTest {

    private LinearLayout container;
    private TextInputEditText total;
    private SplitRowController ctl;

    @Before
    public void maske() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar);
        container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        total = new TextInputEditText(activity);
        ctl = new SplitRowController(container, total, activity.getLayoutInflater(), false, false,
                () -> { });
        // Wie im Editor: jede Änderung des Gesamtbetrags meldet sich beim Controller.
        total.addTextChangedListener(new SimpleWatcher(ctl::onTotalChanged));
        ctl.ensureTrailingRow();
    }

    // ---- Handgriffe, wie sie der Nutzer macht ----

    private void kategorie(int zeile, String name) {
        ((android.widget.AutoCompleteTextView) container.getChildAt(zeile)
                .findViewById(R.id.splitCategory)).setText(name);
    }

    private void teilbetrag(int zeile, String betrag) {
        ((TextInputEditText) container.getChildAt(zeile).findViewById(R.id.splitAmount))
                .setText(betrag);
    }

    private String teilbetrag(int zeile) {
        CharSequence s = ((TextInputEditText) container.getChildAt(zeile)
                .findViewById(R.id.splitAmount)).getText();
        return s == null ? "" : s.toString();
    }

    private void entferne(int zeile) {
        container.getChildAt(zeile).findViewById(R.id.btnRemoveSplit).performClick();
    }

    private String gesamt() {
        return total.getText() == null ? "" : total.getText().toString();
    }

    // ---- Rest-Modus: der Gesamtbetrag steht fest ----

    /**
     * Erst der Betrag, dann die Kategorie: dann ist der Betrag gemeint und die Zeile bekommt ihn ganz.
     * Andernfalls müsste man ihn ein zweites Mal eintippen.
     */
    @Test
    public void ersteKategorieUebernimmtDenGanzenBetrag() {
        total.setText("100,00");
        kategorie(0, "Lebensmittel");

        assertEquals("100,00", teilbetrag(0));
        assertEquals("100,00", gesamt());
    }

    /** Die zweite Kategorie nimmt sich ihren Teil, die erste behält den Rest — der Gesamtbetrag bleibt. */
    @Test
    public void zweiteKategorieMindertDenRest() {
        total.setText("100,00");
        kategorie(0, "Lebensmittel");
        kategorie(1, "Getränke");
        teilbetrag(1, "30,00");

        assertEquals("70,00", teilbetrag(0));
        assertEquals("100,00", gesamt());
    }

    /**
     * Wer die Rest-Zeile selbst anfasst, will sie festhalten. Von da an gilt der Summen-Modus: der
     * Gesamtbetrag folgt den Teilen, statt sie zu bestimmen.
     */
    @Test
    public void handAnDieRestZeileWechseltInDenSummenModus() {
        total.setText("100,00");
        kategorie(0, "Lebensmittel");
        kategorie(1, "Getränke");
        teilbetrag(1, "30,00");

        teilbetrag(0, "50,00");

        assertEquals("50,00", teilbetrag(0));
        assertEquals("80,00", gesamt());
    }

    // ---- Summen-Modus: die Teile stehen fest ----

    /** Ohne vorher eingetippten Gesamtbetrag zählt die Maske zusammen. */
    @Test
    public void ohneGesamtbetragZaehltDieMaskeZusammen() {
        kategorie(0, "Lebensmittel");
        teilbetrag(0, "30,00");
        kategorie(1, "Getränke");
        teilbetrag(1, "20,00");

        assertEquals("50,00", gesamt());
    }

    @Test
    public void entfernenZiehtDenGesamtbetragNach() {
        kategorie(0, "Lebensmittel");
        teilbetrag(0, "30,00");
        kategorie(1, "Getränke");
        teilbetrag(1, "20,00");

        entferne(1);

        assertEquals("30,00", gesamt());
    }

    // ---- Die leere Abschlusszeile ----

    /** Immer genau eine leere Zeile am Ende: sonst müsste man erst einen Knopf für „noch eine" suchen. */
    @Test
    public void esGibtImmerGenauEineLeereZeileAmEnde() {
        assertEquals(1, container.getChildCount());

        kategorie(0, "Lebensmittel");
        assertEquals(2, container.getChildCount());

        kategorie(1, "Getränke");
        assertEquals(3, container.getChildCount());

        ctl.ensureTrailingRow();
        assertEquals("keine zweite leere Zeile anhängen", 3, container.getChildCount());
    }

    /**
     * Im CSV-Schreibweg (kein kmy-Ziel) darf nie eine zweite Kategorie-Zeile entstehen: KMyMoneys
     * eigener CSV-Import kann pro Buchung nur eine Kategorie entgegennehmen (siehe CsvModeGuard).
     */
    @Test
    public void splitLockedLaesstNieEineZweiteZeileEntstehen() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar);
        LinearLayout lockedContainer = new LinearLayout(activity);
        TextInputEditText lockedTotal = new TextInputEditText(activity);
        SplitRowController locked = new SplitRowController(lockedContainer, lockedTotal,
                activity.getLayoutInflater(), false, true, () -> { });
        locked.ensureTrailingRow();
        assertEquals(1, lockedContainer.getChildCount());

        ((android.widget.AutoCompleteTextView) lockedContainer.getChildAt(0)
                .findViewById(R.id.splitCategory)).setText("Lebensmittel");

        assertEquals("keine zweite Zeile im CSV-Schreibweg", 1, lockedContainer.getChildCount());
    }

    /**
     * Auch eine geladene Kategorie (Bearbeiten, Vorlage) darf keine leere Abschlusszeile nach sich ziehen –
     * in die ließe sich sonst die zweite Kategorie eintippen.
     */
    @Test
    public void splitLockedHaengtNachDemLadenKeineLeereZeileAn() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar);
        LinearLayout lockedContainer = new LinearLayout(activity);
        TextInputEditText lockedTotal = new TextInputEditText(activity);
        SplitRowController locked = new SplitRowController(lockedContainer, lockedTotal,
                activity.getLayoutInflater(), false, true, () -> { });
        locked.setSuppressEvents(true);
        locked.addRow("Lebensmittel", "10,00");
        locked.setSuppressEvents(false);

        locked.ensureTrailingRow();

        assertEquals(1, lockedContainer.getChildCount());
    }

    /** Entfernen der letzten Zeile lässt genau eine leere übrig, nie mehr. */
    @Test
    public void splitLockedNachEntfernenGenauEineZeile() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar);
        LinearLayout lockedContainer = new LinearLayout(activity);
        TextInputEditText lockedTotal = new TextInputEditText(activity);
        SplitRowController locked = new SplitRowController(lockedContainer, lockedTotal,
                activity.getLayoutInflater(), false, true, () -> { });
        locked.setSuppressEvents(true);
        locked.addRow("Lebensmittel", "10,00");
        locked.addRow("Getränke", "5,00");
        locked.setSuppressEvents(false);

        lockedContainer.getChildAt(1).findViewById(R.id.btnRemoveSplit).performClick();
        assertEquals(1, lockedContainer.getChildCount());

        lockedContainer.getChildAt(0).findViewById(R.id.btnRemoveSplit).performClick();
        assertEquals("eine leere Zeile bleibt zum Eintragen", 1, lockedContainer.getChildCount());
    }

    // ---- Gültigkeit ----

    /** Ohne Kategorie ist es eine einfache Buchung – die ist gültig. */
    @Test
    public void ohneKategorieIstGueltig() {
        total.setText("100,00");

        assertTrue(ctl.isValid());
    }

    @Test
    public void teileMuessenDieSummeErgeben() {
        total.setText("100,00");
        kategorie(0, "Lebensmittel");
        kategorie(1, "Getränke");
        teilbetrag(1, "30,00");
        // Die Rest-Zeile trägt 70,00 – das geht auf.
        assertTrue(ctl.isValid());

        // Von Hand kleiner gemacht, und der Gesamtbetrag bleibt bei 100: dann fehlt etwas.
        teilbetrag(0, "50,00");
        total.setText("100,00");

        assertFalse(ctl.isValid());
    }

    @Test
    public void kategorieOhneBetragMachtUngueltig() {
        total.setText("100,00");
        kategorie(0, "Lebensmittel");
        teilbetrag(0, "");

        assertFalse(ctl.isValid());
    }

    // ---- Was am Ende gespeichert wird ----

    @Test
    public void gesammeltWerdenNurZeilenMitKategorieUndBetrag() {
        total.setText("100,00");
        kategorie(0, "Lebensmittel");
        kategorie(1, "Getränke");
        teilbetrag(1, "30,00");

        List<SplitRowController.Part> teile = ctl.collectParts();

        assertEquals("die leere Abschlusszeile zählt nicht mit", 2, teile.size());
        assertEquals(7000L, teile.get(0).cents);
        assertEquals(3000L, teile.get(1).cents);
    }
}
