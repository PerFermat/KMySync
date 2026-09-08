package de.spahr.ausgaben.statement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Die Datei, mit der ein Nutzer seine gelernten Regeln weitergibt.
 *
 * <p>Sie ist das einzige Stück der App, das ein <b>fremder</b> Nutzer beisteuert — hier ist also nicht
 * nur zu prüfen, dass eine eigene Datei den Weg hin und zurück übersteht, sondern ebenso, dass eine
 * beliebige andere Datei sauber abgewiesen wird statt halbe Regeln in ein Depot zu legen.</p>
 *
 * <p>Läuft unter Robolectric, weil {@code org.json} zum Android-Rahmen gehört und im nackten
 * Unit-Test nur Attrappen liefert.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class StatementRulesIoTest {

    /** Eine Vorlage, die alles mitbringt, was das Format kennt. */
    private static StatementTemplate reicheVorlage() {
        Map<StatementTemplate.Field, AnchorRule> rules =
                new EnumMap<>(StatementTemplate.Field.class);
        rules.put(StatementTemplate.Field.NET, new AnchorRule(
                Arrays.asList("Endbetrag zu Ihren Lasten", "Ausmachender Betrag"),
                AnchorRule.Direction.SAME_LINE, false, "EUR",
                AnchorRule.Position.LAST, 1, 0));
        // Eine Regel abseits jedes Regelfalls: Spaltenposition, fester Zeilenabstand, aufsummiert.
        rules.put(StatementTemplate.Field.FEE, new AnchorRule(
                Collections.singletonList("Provision"),
                AnchorRule.Direction.LINE_BELOW, true, "",
                AnchorRule.Position.COLUMN, 3, 2));
        rules.put(StatementTemplate.Field.DATE, new AnchorRule(
                Collections.singletonList("Valuta"),
                AnchorRule.Direction.SAME_LINE, false, "",
                AnchorRule.Position.FIRST, 2, 0));
        List<StatementTemplate.PartRule> feeParts = new ArrayList<>();
        feeParts.add(new StatementTemplate.PartRule("Kapitalertragsteuer",
                new AnchorRule(Collections.singletonList("Kapitalertragsteuer"),
                        AnchorRule.Direction.SAME_LINE, false, "EUR",
                        AnchorRule.Position.LAST, 1, 0),
                "Steuern"));
        return new StatementTemplate("buy", rules, 99L, "Gebühren", true,
                feeParts, Collections.emptyList(), "Gebühren", "Ertrag");
    }

    @Test
    public void vorlageUeberstehtDenWegDurchDieDatei() throws Exception {
        StatementTemplate vorher = reicheVorlage();
        String datei = StatementRulesIo.toFile("DKB Depot",
                Collections.singletonList(vorher));

        StatementRulesIo.Parsed gelesen = StatementRulesIo.parse(datei);

        assertEquals("DKB Depot", gelesen.depot);
        assertEquals(1, gelesen.templates.size());
        StatementTemplate nachher = gelesen.templates.get(0);
        assertTrue("die Regeln kamen verändert zurück", nachher.sameAs(vorher));
        // sameAs vergleicht die Regeln; die Angaben daneben einzeln nachgehalten.
        assertEquals("buy", nachher.action);
        assertEquals(99L, nachher.fixedFeeCents);
        assertEquals("Gebühren", nachher.fixedFeeCategory);
        assertTrue(nachher.fixedFeeInTotal);
        assertEquals("Gebühren", nachher.feeCategory);
        assertEquals("Ertrag", nachher.incomeCategory);
        assertEquals(1, nachher.feeParts.size());
        assertEquals("Kapitalertragsteuer", nachher.feeParts.get(0).label);
        assertEquals("Steuern", nachher.feeParts.get(0).category);
    }

    /** Die Abweichungen vom Regelfall stehen nur dann in der Datei, wenn es sie gibt – und kommen zurück. */
    @Test
    public void spalteAbstandUndStelleKommenZurueck() throws Exception {
        StatementRulesIo.Parsed gelesen = StatementRulesIo.parse(
                StatementRulesIo.toFile("", Collections.singletonList(reicheVorlage())));

        AnchorRule fee = gelesen.templates.get(0).rule(StatementTemplate.Field.FEE);
        assertNotNull(fee);
        assertEquals(AnchorRule.Position.COLUMN, fee.position);
        assertEquals(AnchorRule.Direction.LINE_BELOW, fee.direction);
        assertEquals(3, fee.nth);
        assertEquals(2, fee.lineDistance);
        assertTrue(fee.sum);
    }

    @Test
    public void keinJsonWirdAbgewiesen() {
        assertProblem("das hier ist eine PDF", StatementRulesIo.Problem.NOT_JSON);
    }

    @Test
    public void fremdeJsonDateiWirdAbgewiesen() {
        // Die Sprachdatei der App ist ebenfalls JSON und wird ebenfalls über den Dateiwähler geholt –
        // ohne das Erkennungsmerkmal im Kopf landete sie sonst als „keine brauchbare Regel" im Depot.
        assertProblem("{\"language\":\"fr\",\"strings\":{}}", StatementRulesIo.Problem.NOT_OURS);
    }

    @Test
    public void neueresFormatWirdAbgewiesen() {
        assertProblem("{\"app\":\"" + StatementRulesIo.APP + "\",\"version\":99,\"templates\":[]}",
                StatementRulesIo.Problem.TOO_NEW);
    }

    /**
     * Eine Vorlage ohne Gesamtbetrag fällt weg — sie erkennt sich nicht wieder und könnte nichts
     * buchen. Bleibt danach keine übrig, ist die ganze Datei nichts wert.
     */
    @Test
    public void vorlageOhneGesamtbetragFaelltWeg() {
        Map<StatementTemplate.Field, AnchorRule> nurDatum =
                new EnumMap<>(StatementTemplate.Field.class);
        nurDatum.put(StatementTemplate.Field.DATE, new AnchorRule(
                Collections.singletonList("Valuta"), AnchorRule.Direction.SAME_LINE,
                false, "", AnchorRule.Position.LAST, 1, 0));
        String datei = StatementRulesIo.toFile("Depot",
                Arrays.asList(new StatementTemplate("buy", nurDatum), reicheVorlage()));

        try {
            StatementRulesIo.Parsed gelesen = StatementRulesIo.parse(datei);
            assertEquals("die lückenhafte Vorlage kam mit", 1, gelesen.templates.size());
            assertEquals("buy", gelesen.templates.get(0).action);
            assertNotNull(gelesen.templates.get(0).rule(StatementTemplate.Field.NET));
        } catch (StatementRulesIo.StatementRulesFormatException e) {
            fail("die vollständige Vorlage hätte bleiben müssen");
        }
    }

    @Test
    public void dateiOhneBrauchbareVorlageWirdAbgewiesen() {
        assertProblem(StatementRulesIo.toFile("Depot", Collections.emptyList()),
                StatementRulesIo.Problem.NO_TEMPLATES);
    }

    private static void assertProblem(String json, StatementRulesIo.Problem erwartet) {
        try {
            StatementRulesIo.parse(json);
            fail("hätte abgewiesen werden müssen: " + erwartet);
        } catch (StatementRulesIo.StatementRulesFormatException e) {
            assertEquals(erwartet, e.problem);
        }
    }
}
