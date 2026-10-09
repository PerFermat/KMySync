package de.spahr.ausgaben.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Die Kategorien eines Empfängers ({@link PayeeCategories}): Quellenrang, Reihenfolge, Deckelung.
 */
public class PayeeCategoriesTest {

    /** Alias mit Ausgabe-Kategorien; {@code preferred} = mit Stern. */
    private static PayeeCorrection ausgabeAlias(boolean preferred, String cat1, String cat2) {
        PayeeCorrection a = new PayeeCorrection();
        a.corrected = "REWE";
        a.preferred = preferred;
        a.catExpense1 = cat1;
        a.catExpense2 = cat2 == null ? "" : cat2;
        return a;
    }

    private static PayeeCorrection einnahmeAlias(boolean preferred, String cat1) {
        PayeeCorrection a = new PayeeCorrection();
        a.corrected = "Arbeitgeber";
        a.preferred = preferred;
        a.catIncome1 = cat1;
        return a;
    }

    @Test
    public void bevorzugterAliasStehtVorDenBuchungen() {
        List<String> cats = PayeeCategories.rank(
                Collections.singletonList(ausgabeAlias(true, "Haushalt", null)),
                Arrays.asList("Lebensmittel", "Drogerie"), false);
        assertEquals(Arrays.asList("Haushalt", "Lebensmittel", "Drogerie"), cats);
    }

    @Test
    public void gewoehnlicherAliasStehtHinterDenBuchungen() {
        List<String> cats = PayeeCategories.rank(
                Collections.singletonList(ausgabeAlias(false, "Haushalt", null)),
                Arrays.asList("Lebensmittel", "Drogerie"), false);
        assertEquals(Arrays.asList("Lebensmittel", "Drogerie", "Haushalt"), cats);
    }

    @Test
    public void beideAliasArtenUmschliessenDieBuchungen() {
        List<String> cats = PayeeCategories.rank(
                Arrays.asList(ausgabeAlias(false, "Übrig", null), ausgabeAlias(true, "Stern", null)),
                Collections.singletonList("Buchung"), false);
        assertEquals(Arrays.asList("Stern", "Buchung", "Übrig"), cats);
    }

    @Test
    public void doppelteFallenWegUndDerErsteFundZaehlt() {
        List<String> cats = PayeeCategories.rank(
                Collections.singletonList(ausgabeAlias(true, "Lebensmittel", null)),
                Arrays.asList("lebensmittel", "Drogerie", "LEBENSMITTEL"), false);
        assertEquals(Arrays.asList("Lebensmittel", "Drogerie"), cats);
    }

    @Test
    public void einnahmeUndAusgabeWerdenNichtVermischt() {
        List<PayeeCorrection> aliase = Collections.singletonList(einnahmeAlias(true, "Gehalt"));
        assertEquals(Collections.singletonList("Gehalt"),
                PayeeCategories.rank(aliase, null, true));
        assertTrue(PayeeCategories.rank(aliase, null, false).isEmpty());
    }

    @Test
    public void beideKategorienEinesAliasZaehlen() {
        List<String> cats = PayeeCategories.rank(
                Collections.singletonList(ausgabeAlias(true, "Haushalt", "Garten")), null, false);
        assertEquals(Arrays.asList("Haushalt", "Garten"), cats);
    }

    @Test
    public void hoechstensSechs() {
        List<String> viele = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            viele.add("Kategorie " + i);
        }
        List<String> cats = PayeeCategories.rank(null, viele, false);
        assertEquals(PayeeCategories.LIMIT, cats.size());
        assertEquals("Kategorie 0", cats.get(0));
        assertEquals("Kategorie 5", cats.get(5));
    }

    @Test
    public void ohneQuellenBleibtNichts() {
        assertTrue(PayeeCategories.rank(null, null, false).isEmpty());
        assertTrue(PayeeCategories.rank(Collections.singletonList(ausgabeAlias(true, "", null)),
                Arrays.asList("", "   "), false).isEmpty());
    }

    // ---- Die Seite der Kategorie kommt mit ----

    private static List<String> beschrieben(List<PayeeCategory> cats) {
        List<String> out = new ArrayList<>();
        for (PayeeCategory c : cats) {
            out.add(c.category + "=" + c.isIncome);
        }
        return out;
    }

    /** Eine Erstattung: für Einnahmen gebucht, aber auf eine Ausgabekategorie. Das bleibt so. */
    @Test
    public void dieSeiteDerAltenBuchungBleibtErhalten() {
        List<PayeeCategory> cats = PayeeCategories.ranked(null, Arrays.asList(
                new PayeeCategory("Kleidung", false), new PayeeCategory("Gutschrift", true),
                new PayeeCategory("Alt", null)), true);
        assertEquals(Arrays.asList("Kleidung=false", "Gutschrift=true", "Alt=null"), beschrieben(cats));
    }

    @Test
    public void derAliasBringtSeineSeiteMit() {
        PayeeCorrection stern = einnahmeAlias(true, "Kleidung");
        stern.catIncome1IsIncome = false;
        PayeeCorrection alt = einnahmeAlias(false, "Gehalt");
        List<PayeeCategory> cats = PayeeCategories.ranked(Arrays.asList(stern, alt), null, true);
        assertEquals("ein Alias aus der Zeit vor dem Feld kennt seine Seite nicht",
                Arrays.asList("Kleidung=false", "Gehalt=null"), beschrieben(cats));
    }

    /** Kennt der erste Fund seine Seite nicht, trägt ein späterer gleichnamiger sie nach – am selben Platz. */
    @Test
    public void spaetererFundTraegtDieSeiteNach() {
        List<PayeeCategory> cats = PayeeCategories.ranked(
                Collections.singletonList(ausgabeAlias(true, "Lebensmittel", null)),
                Arrays.asList(new PayeeCategory("Drogerie", false),
                        new PayeeCategory("lebensmittel", false)), false);
        assertEquals(Arrays.asList("Lebensmittel=false", "Drogerie=false"), beschrieben(cats));
        // Eine schon bekannte Seite überschreibt er nicht.
        cats = PayeeCategories.ranked(null, Arrays.asList(new PayeeCategory("Kleidung", false),
                new PayeeCategory("Kleidung", true)), true);
        assertEquals(Collections.singletonList("Kleidung=false"), beschrieben(cats));
    }
}
