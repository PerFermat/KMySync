package de.spahr.ausgaben.statement;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Die JSON-Gestalt der gelernten Vorlagen — einmal für den Einstellungsspeicher, einmal für die Datei,
 * die ein Nutzer weitergibt.
 *
 * <p>Beides ist absichtlich <b>dasselbe</b> Format: eine Vorlage steht in der Datei Zeichen für Zeichen
 * so da, wie sie in {@code ausgaben_statements} liegt. Zwei Serialisierer für dieselbe Sache driften
 * auseinander, sobald einer eine Angabe dazubekommt und der andere übersehen wird; der Fehler fiele
 * erst beim Einspielen einer fremden Datei auf, also genau dann, wenn niemand mehr nachsehen kann,
 * woher sie stammt.</p>
 *
 * <p>Die Datei bekommt darüber einen Kopf: {@code app} als Erkennungsmerkmal, {@code version} für
 * spätere Formatwechsel und {@code depot} als bloßen Hinweis. <b>Maßgeblich ist immer das Depot, in das
 * eingespielt wird</b> — Depotnamen sind bei jedem Nutzer andere, und eine Datei, die sich ihr Ziel
 * selbst aussucht, legte fremde Regeln in ein Depot, an das der Empfänger nicht gedacht hat.</p>
 *
 * <p>Reines Java ohne Android: so lässt sich das Format ohne Gerät prüfen — wie bei
 * {@link de.spahr.ausgaben.i18n.TranslationIo}.</p>
 */
public final class StatementRulesIo {

    /**
     * Steht im Kopf jeder Datei; eine Datei ohne dieses Merkmal wird nicht angefaßt.
     *
     * <p>Trägt noch den alten Namen der App und behält ihn: Das Merkmal steht <b>in</b> den Dateien,
     * die schon im Umlauf sind, und ein Wechsel machte sie mit einem Schlag zu „keine Regeldatei
     * dieser App". Der Name der Datei ist eine andere Sache — der darf sich ändern, weil ihn beim
     * Einlesen niemand ansieht.</p>
     */
    public static final String APP = "ausgaben-statement-rules";

    /** Die Fassung des Dateiformats, die diese App schreibt und höchstens lesen kann. */
    public static final int VERSION = 1;

    private StatementRulesIo() {
    }

    // ---- Datei ----

    /** Was in einer eingelesenen Datei stand. */
    public static final class Parsed {
        /** Das Depot, aus dem die Regeln stammen — nur zur Anzeige, nie zur Zuordnung. */
        public final String depot;
        public final List<StatementTemplate> templates;

        Parsed(String depot, List<StatementTemplate> templates) {
            this.depot = depot;
            this.templates = templates;
        }
    }

    /** Die Regeln eines Depots als Dateiinhalt. */
    public static String toFile(String depot, List<StatementTemplate> templates) {
        JSONObject root = new JSONObject();
        try {
            root.put("app", APP);
            root.put("version", VERSION);
            root.put("depot", depot == null ? "" : depot);
            JSONArray arr = new JSONArray();
            if (templates != null) {
                for (StatementTemplate t : templates) {
                    JSONObject o = templateToJson(t);
                    if (o != null) {
                        arr.put(o);
                    }
                }
            }
            root.put("templates", arr);
            // Eingerückt: Die Datei wird gelesen, verglichen und im Zweifel per Hand nachgebessert –
            // eine einzige lange Zeile wäre für den Menschen wertlos, um den es hier geht.
            return root.toString(2);
        } catch (JSONException e) {
            // Kann nicht eintreten: die Schlüssel stehen hier, und keiner der Werte ist null.
            throw new IllegalStateException(e);
        }
    }

    /**
     * Liest eine solche Datei. Wirft mit einer Meldung, die sich dem Nutzer zeigen läßt, wenn sie
     * keine ist, aus einer neueren App stammt oder keine brauchbare Vorlage enthält.
     *
     * <p>Brauchbar heißt: mit einer Regel für den Gesamtbetrag. Ohne die erkennt sich eine Vorlage
     * nicht wieder und könnte auch nichts buchen — dieselbe Bedingung, unter der die Regelseite ein
     * Speichern verweigert.</p>
     */
    public static Parsed parse(String json) throws StatementRulesFormatException {
        JSONObject root;
        try {
            root = new JSONObject(json == null ? "" : json);
        } catch (JSONException e) {
            throw new StatementRulesFormatException(Problem.NOT_JSON);
        }
        if (!APP.equals(root.optString("app", ""))) {
            throw new StatementRulesFormatException(Problem.NOT_OURS);
        }
        if (root.optInt("version", 0) > VERSION) {
            throw new StatementRulesFormatException(Problem.TOO_NEW);
        }
        List<StatementTemplate> templates = new ArrayList<>();
        JSONArray arr = root.optJSONArray("templates");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            StatementTemplate t = templateFromJson(arr.optJSONObject(i));
            if (t != null && !t.isEmpty() && t.rule(StatementTemplate.Field.NET) != null) {
                templates.add(t);
            }
        }
        if (templates.isEmpty()) {
            throw new StatementRulesFormatException(Problem.NO_TEMPLATES);
        }
        return new Parsed(root.optString("depot", ""), templates);
    }

    /** Woran eine Datei gescheitert ist; die Oberfläche macht daraus einen Satz. */
    public enum Problem {
        /** Gar kein JSON. */
        NOT_JSON,
        /** JSON, aber keine Regeldatei dieser App. */
        NOT_OURS,
        /** Aus einer neueren Fassung der App. */
        TOO_NEW,
        /** Keine brauchbare Vorlage darin. */
        NO_TEMPLATES
    }

    /** Eine Datei, die sich nicht einspielen läßt, mitsamt dem Grund. */
    public static final class StatementRulesFormatException extends Exception {
        public final Problem problem;

        StatementRulesFormatException(Problem problem) {
            super(problem.name());
            this.problem = problem;
        }
    }

    // ---- Eine einzelne Vorlage ----

    /**
     * Eine Vorlage als JSON; {@code null}, wenn sie sich nicht schreiben läßt.
     *
     * <p>Geschrieben wird nur, was vom Regelfall abweicht — so bleiben Bestandsvorlagen Zeichen für
     * Zeichen so, wie sie sind, und eine Datei wird nicht länger als nötig.</p>
     */
    public static JSONObject templateToJson(StatementTemplate t) {
        if (t == null) {
            return null;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("a", t.action == null ? "" : t.action);
            JSONObject rules = new JSONObject();
            for (Map.Entry<StatementTemplate.Field, AnchorRule> e : t.rules().entrySet()) {
                rules.put(e.getKey().name(), ruleToJson(e.getValue()));
            }
            o.put("r", rules);
            // Nur schreiben, wenn es eine feste Gebühr gibt – Bestandsvorlagen bleiben so, wie sie sind.
            if (t.fixedFeeCents > 0) {
                o.put("ff", t.fixedFeeCents);
                o.put("fc", t.fixedFeeCategory);
                o.put("fi", t.fixedFeeInTotal);
            }
            if (!t.feeParts.isEmpty()) {
                o.put("fp", partsToJson(t.feeParts));
            }
            if (!t.incomeParts.isEmpty()) {
                o.put("ip", partsToJson(t.incomeParts));
            }
            if (!t.feeCategory.isEmpty()) {
                o.put("fcat", t.feeCategory);
            }
            if (!t.incomeCategory.isEmpty()) {
                o.put("icat", t.incomeCategory);
            }
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /** Eine Vorlage aus JSON; {@code null}, wenn nichts dasteht. */
    public static StatementTemplate templateFromJson(JSONObject o) {
        if (o == null) {
            return null;
        }
        Map<StatementTemplate.Field, AnchorRule> rules =
                new EnumMap<>(StatementTemplate.Field.class);
        JSONObject r = o.optJSONObject("r");
        if (r != null) {
            for (StatementTemplate.Field field : StatementTemplate.Field.values()) {
                AnchorRule rule = ruleFromJson(r.optJSONObject(field.name()));
                if (rule != null) {
                    rules.put(field, rule);
                }
            }
        }
        return new StatementTemplate(o.optString("a", ""), rules,
                o.optLong("ff", 0L), o.optString("fc", ""), o.optBoolean("fi", false),
                partsFromJson(o.optJSONArray("fp")), partsFromJson(o.optJSONArray("ip")),
                o.optString("fcat", ""), o.optString("icat", ""));
    }

    public static JSONObject ruleToJson(AnchorRule r) throws JSONException {
        JSONObject ro = new JSONObject();
        ro.put("t", new JSONArray(r.anchors));
        ro.put("d", r.direction.name());
        ro.put("s", r.sum);
        ro.put("c", r.currency);
        if (r.nth > 1) {
            ro.put("n", r.nth);
        }
        if (r.lineDistance > 0) {
            // Nur bei fester Angabe – ohne sie sucht die Regel, und das ist der Regelfall.
            ro.put("b", r.lineDistance);
        }
        if (r.position != AnchorRule.Position.LAST) {
            // Nur schreiben, wenn es vom Regelfall abweicht – Bestandsvorlagen bleiben so, wie
            // sie sind, und beim Lesen gilt ohne Angabe die letzte Zahl.
            ro.put("p", r.position.name());
        }
        return ro;
    }

    /** Eine einzelne Regel; {@code null}, wenn keine dasteht oder ihr die Beschriftungen fehlen. */
    public static AnchorRule ruleFromJson(JSONObject ro) {
        if (ro == null) {
            return null;
        }
        List<String> anchors = new ArrayList<>();
        JSONArray arr = ro.optJSONArray("t");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                anchors.add(arr.optString(i, ""));
            }
        }
        if (anchors.isEmpty()) {
            return null;
        }
        AnchorRule.Direction dir;
        try {
            dir = AnchorRule.Direction.valueOf(
                    ro.optString("d", AnchorRule.Direction.SAME_LINE.name()));
        } catch (IllegalArgumentException e) {
            dir = AnchorRule.Direction.SAME_LINE;
        }
        AnchorRule.Position pos;
        try {
            pos = AnchorRule.Position.valueOf(ro.optString("p", AnchorRule.Position.LAST.name()));
        } catch (IllegalArgumentException e) {
            pos = AnchorRule.Position.LAST;
        }
        return new AnchorRule(anchors, dir, ro.optBoolean("s", false),
                ro.optString("c", ""), pos, ro.optInt("n", 1), ro.optInt("b", 0));
    }

    public static JSONArray partsToJson(List<StatementTemplate.PartRule> parts) throws JSONException {
        JSONArray arr = new JSONArray();
        for (StatementTemplate.PartRule part : parts) {
            JSONObject po = new JSONObject();
            po.put("l", part.label);
            po.put("r", ruleToJson(part.rule));
            if (!part.category.isEmpty()) {
                po.put("k", part.category);
            }
            arr.put(po);
        }
        return arr;
    }

    public static List<StatementTemplate.PartRule> partsFromJson(JSONArray arr) {
        List<StatementTemplate.PartRule> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject po = arr.optJSONObject(i);
            if (po == null) {
                continue;
            }
            AnchorRule rule = ruleFromJson(po.optJSONObject("r"));
            String label = po.optString("l", "");
            // Ohne Beschriftung wäre der Teil nicht wiederzuerkennen und ohne Regel nicht zu lesen.
            if (rule != null && !label.trim().isEmpty()) {
                out.add(new StatementTemplate.PartRule(label, rule, po.optString("k", "")));
            }
        }
        return out;
    }
}
