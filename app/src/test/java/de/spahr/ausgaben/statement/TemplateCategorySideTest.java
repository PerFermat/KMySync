package de.spahr.ausgaben.statement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Eine Abrechnungsvorlage führt zu jeder Kategorie ihre Seite (Einnahme/Ausgabe): durch die Ablage,
 * beim Zusammenführen und – für Vorlagen aus der Zeit davor – einmal nachgetragen.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TemplateCategorySideTest {

    private static AnchorRule regel(String anker) {
        return new AnchorRule(Collections.singletonList(anker), AnchorRule.Direction.SAME_LINE,
                false, "EUR", AnchorRule.Position.LAST, 1, 0);
    }

    private static Map<StatementTemplate.Field, AnchorRule> regeln() {
        Map<StatementTemplate.Field, AnchorRule> rules = new EnumMap<>(StatementTemplate.Field.class);
        rules.put(StatementTemplate.Field.NET, regel("Gutschrift"));
        return rules;
    }

    /** Dividende: zwei Steuerzeilen, eine Ertragszeile, feste Gebühr, Kategorien fürs Ganze. */
    private static StatementTemplate vorlage(Boolean steuer, Boolean soli, Boolean ertrag,
                                             StatementTemplate.Seiten seiten) {
        List<StatementTemplate.PartRule> fee = Arrays.asList(
                new StatementTemplate.PartRule("Kapitalertragsteuer", regel("Kapitalertragsteuer"),
                        "Steuern:KapESt", steuer),
                new StatementTemplate.PartRule("Solidaritätszuschlag", regel("Solidaritätszuschlag"),
                        "Versicherung:Krankenzusatz", soli));
        List<StatementTemplate.PartRule> income = Collections.singletonList(
                new StatementTemplate.PartRule("Ertrag", regel("Ertrag"), "Zinsen:Dividende", ertrag));
        return new StatementTemplate("dividend", regeln(), 99L, "Gebühren", true, fee, income,
                "Steuern", "Zinsen", seiten);
    }

    @Test
    public void seitenUeberstehenDieAblage() {
        StatementTemplate t = vorlage(Boolean.FALSE, Boolean.TRUE, Boolean.TRUE,
                new StatementTemplate.Seiten(Boolean.FALSE, Boolean.TRUE, Boolean.FALSE));
        StatementTemplate z = StatementRulesIo.templateFromJson(StatementRulesIo.templateToJson(t));

        assertEquals(Boolean.FALSE, z.feeParts.get(0).categoryIsIncome);
        assertEquals(Boolean.TRUE, z.feeParts.get(1).categoryIsIncome);
        assertEquals(Boolean.TRUE, z.incomeParts.get(0).categoryIsIncome);
        assertEquals(Boolean.FALSE, z.seiten.fee);
        assertEquals(Boolean.TRUE, z.seiten.income);
        assertEquals(Boolean.FALSE, z.seiten.fixedFee);
        assertTrue(t.sameAs(z));
    }

    /** Eine Ablage aus der Zeit vor der Seite liest sich weiter – mit „unbekannt". */
    @Test
    public void alteAblageOhneSeiten() throws Exception {
        JSONObject alt = StatementRulesIo.templateToJson(
                vorlage(null, null, null, StatementTemplate.Seiten.LEER));
        assertFalse(alt.toString(), alt.toString().contains("\"ks\""));
        assertFalse(alt.has("fcats") || alt.has("icats") || alt.has("fcs"));

        StatementTemplate z = StatementRulesIo.templateFromJson(alt);
        assertNull(z.feeParts.get(0).categoryIsIncome);
        assertNull(z.seiten.fee);
        assertNull(z.seiten.income);
        assertNull(z.seiten.fixedFee);
    }

    @Test
    public void nachtragen_eindeutigLautNamenSonstNachDerRolle() {
        StatementTemplate alt = vorlage(null, null, null, StatementTemplate.Seiten.LEER);
        // „Steuern:KapESt" ist eindeutig eine Ausgabe, „Zinsen" eindeutig eine Einnahme; den Rest
        // gibt es in beiden Bäumen oder gar nicht.
        StatementTemplate.Seitenwissen wissen = name ->
                "Steuern:KapESt".equals(name) ? Boolean.FALSE
                        : "Zinsen".equals(name) ? Boolean.TRUE : null;

        StatementTemplate neu = alt.mitNachgetragenenSeiten(wissen);

        assertEquals(Boolean.FALSE, neu.feeParts.get(0).categoryIsIncome);
        assertEquals("Gebührenteil ohne eindeutigen Namen: Ausgabe",
                Boolean.FALSE, neu.feeParts.get(1).categoryIsIncome);
        assertEquals("Ertragsteil: Einnahme", Boolean.TRUE, neu.incomeParts.get(0).categoryIsIncome);
        assertEquals(Boolean.FALSE, neu.seiten.fee);
        assertEquals(Boolean.TRUE, neu.seiten.income);
        assertEquals(Boolean.FALSE, neu.seiten.fixedFee);
        // Die Regeln selbst bleiben, wie sie waren.
        assertEquals(alt.rules(), neu.rules());
        assertEquals("Steuern", neu.feeCategory);
        assertEquals(99L, neu.fixedFeeCents);

        // Einmal: Der zweite Lauf findet nichts mehr und gibt dieselbe Vorlage zurück.
        assertSame(neu, neu.mitNachgetragenenSeiten(name -> Boolean.TRUE));
    }

    @Test
    public void nachtragen_laesstGespeichertesStehen() {
        StatementTemplate t = vorlage(Boolean.TRUE, null, Boolean.FALSE,
                new StatementTemplate.Seiten(Boolean.TRUE, null, null));

        StatementTemplate neu = t.mitNachgetragenenSeiten(name -> null);

        assertEquals(Boolean.TRUE, neu.feeParts.get(0).categoryIsIncome);
        assertEquals(Boolean.FALSE, neu.feeParts.get(1).categoryIsIncome);
        assertEquals(Boolean.FALSE, neu.incomeParts.get(0).categoryIsIncome);
        assertEquals(Boolean.TRUE, neu.seiten.fee);
        assertEquals(Boolean.TRUE, neu.seiten.income);
    }

    /** Beim Zusammenführen wandert die Seite mit der Kategorie, von der sie stammt. */
    @Test
    public void zusammenfuehrenBehaeltDieSeiteDerBehaltenenKategorie() {
        StatementTemplate alt = vorlage(Boolean.FALSE, Boolean.TRUE, Boolean.TRUE,
                new StatementTemplate.Seiten(Boolean.FALSE, Boolean.TRUE, Boolean.FALSE));
        // Neu gelernt: nur die Regeln, keine Kategorien fürs Ganze, keine feste Gebühr, eine Zeile.
        StatementTemplate neu = new StatementTemplate("dividend", regeln(), 0L, "", false,
                Collections.singletonList(new StatementTemplate.PartRule("Kapitalertragsteuer",
                        regel("Kapitalertragsteuer"), "Steuern:Abgeltung", Boolean.FALSE)),
                Collections.emptyList(), "", "", StatementTemplate.Seiten.LEER);

        StatementTemplate z = neu.mergedOver(alt);

        assertEquals("Steuern", z.feeCategory);
        assertEquals(Boolean.FALSE, z.seiten.fee);
        assertEquals(Boolean.TRUE, z.seiten.income);
        assertEquals(Boolean.FALSE, z.seiten.fixedFee);
        // Die neue Zeile gewinnt, die alte zweite bleibt mit ihrer Seite stehen.
        assertEquals("Steuern:Abgeltung", z.feeParts.get(0).category);
        assertEquals(Boolean.TRUE, z.feeParts.get(1).categoryIsIncome);
        assertEquals(Boolean.TRUE, z.incomeParts.get(0).categoryIsIncome);
    }

    /** Eine bekannte gegen eine andere bekannte Seite ist ein Unterschied; „unbekannt" keiner. */
    @Test
    public void gleichheit() {
        StatementTemplate ohne = vorlage(null, null, null, StatementTemplate.Seiten.LEER);
        StatementTemplate mit = vorlage(Boolean.FALSE, Boolean.FALSE, Boolean.TRUE,
                new StatementTemplate.Seiten(Boolean.FALSE, Boolean.TRUE, Boolean.FALSE));
        StatementTemplate anders = vorlage(Boolean.FALSE, Boolean.TRUE, Boolean.TRUE,
                new StatementTemplate.Seiten(Boolean.FALSE, Boolean.TRUE, Boolean.FALSE));
        assertTrue(ohne.sameAs(mit));
        assertFalse(mit.sameAs(anders));
    }
}
