package de.spahr.ausgaben.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Beim Zuordnen der Abrechnungszeilen bleibt die Seite bei der Kategorie, zu der sie gehört. */
public class CategorySplitsSideTest {

    private static CategorySplits.Part teil(String kategorie, long cents, String label, Boolean seite) {
        return new CategorySplits.Part(kategorie, cents, label, seite);
    }

    @Test
    public void kategorieAusDerVorlageBringtIhreSeiteMit() {
        List<CategorySplits.Part> found = Arrays.asList(
                teil("Steuern:KapESt", 2000, "Kapitalertragsteuer", Boolean.FALSE),
                teil("Versicherung:Krankenzusatz", 110, "Soli", Boolean.TRUE));
        List<CategorySplits.Part> out = CategorySplits.match(found, 2110, Collections.emptyList());
        assertEquals(Boolean.FALSE, out.get(0).categoryIsIncome);
        assertEquals(Boolean.TRUE, out.get(1).categoryIsIncome);
    }

    @Test
    public void kategorieAusDerLetztenBuchungBringtIhreSeiteMit() {
        // Die Abrechnung nennt nur Beträge und Beschriftungen, die Kategorien kommen aus der Historie.
        List<CategorySplits.Part> found = Arrays.asList(
                teil("", 110, "Soli", null), teil("", 2000, "Kapitalertragsteuer", null));
        List<CategorySplits.Part> known = Arrays.asList(
                teil("Steuern:KapESt", 0, "Kapitalertragsteuer", Boolean.FALSE),
                teil("Versicherung:Krankenzusatz", 0, "Soli", Boolean.TRUE));
        List<CategorySplits.Part> out = CategorySplits.match(found, 2110, known);
        assertEquals("Versicherung:Krankenzusatz", out.get(0).category);
        assertEquals(Boolean.TRUE, out.get(0).categoryIsIncome);
        assertEquals("Steuern:KapESt", out.get(1).category);
        assertEquals(Boolean.FALSE, out.get(1).categoryIsIncome);
    }

    @Test
    public void ohneBetragStehenDieBekanntenZeilenMitSeiteDa() {
        List<CategorySplits.Part> known = Collections.singletonList(
                teil("Zinsen:Dividende", 0, "", Boolean.TRUE));
        List<CategorySplits.Part> out = CategorySplits.rows(Collections.emptyList(), 0, known);
        assertEquals(Boolean.TRUE, out.get(0).categoryIsIncome);
    }

    @Test
    public void ohneKategorieKeineSeite_undDieZahlform() {
        assertNull(teil("", 100, "x", Boolean.TRUE).categoryIsIncome);
        assertNull(CategorySplits.Part.ausZahl(CategorySplits.Part.alsZahl(null)));
        assertEquals(Boolean.TRUE, CategorySplits.Part.ausZahl(CategorySplits.Part.alsZahl(true)));
        assertEquals(Boolean.FALSE, CategorySplits.Part.ausZahl(CategorySplits.Part.alsZahl(false)));
    }
}
