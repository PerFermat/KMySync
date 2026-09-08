package de.spahr.ausgaben.settings;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import de.spahr.ausgaben.pdf.PdfText;
import de.spahr.ausgaben.statement.StatementRulesIo;
import de.spahr.ausgaben.statement.StatementScan;
import de.spahr.ausgaben.statement.StatementTemplate;

/**
 * Was die App über die Abrechnungen der eigenen Banken gelernt hat — die Ankerregeln je Vorlage und die
 * Zuordnung ISIN → Wertpapier, soweit sie nicht schon aus KMyMoney kommt.
 *
 * <p>Liegt wie die Orte ({@link PlacesStore}) bewusst außerhalb der Room-Datenbank: das Gelernte soll
 * „Datenbank zurücksetzen" und einen Import überstehen. In die Sicherung kommt es nicht von selbst —
 * die Datei steht dafür namentlich in {@code BackupStore.PREFS_FILES}.</p>
 *
 * <p>Speicherformat unter {@code templates}: eine Liste von
 * {@code {"a":"buy","d":"Depot","r":{"NET":{"t":["Endbetrag zu Ihren Lasten"],"d":"SAME_LINE","s":false,"c":"EUR"}, …}}}.
 * Geschrieben und gelesen wird sie von {@link StatementRulesIo} — derselben Klasse, die auch die Datei
 * schreibt, mit der ein Nutzer seine Regeln weitergibt. Nur das {@code "d"} mit dem Depot kommt von hier:
 * in der Datei steht das Depot einmal im Kopf, denn dort gehören alle Vorlagen zu einem.
 * Unter {@code isins} steht je ISIN Depot, Wertpapier-ID und Name.</p>
 *
 * <p>Die Vorlagen gehören zum <b>Depot</b>, nicht zur Bank: verschickt eine Bank Abrechnungen an zwei
 * Depots, entstehen dort zwei (gleichlautende) Vorlagen je Aktion statt einer geteilten. So bleiben es je
 * Depot höchstens drei — eine je {@code buy}, {@code sell}, {@code dividend} —, und wechselt ein Depot die
 * Bank, verlernt es die alten Beschriftungen von selbst über {@link StatementTemplate#mergedOver} bzw.
 * {@link StatementTemplate#appendedTo}, statt das andere Depot mitzuverändern.</p>
 */
public class StatementTemplates {

    static final String PREFS = "ausgaben_statements";
    static final String KEY_TEMPLATES = "templates";
    static final String KEY_ISINS = "isins";
    /**
     * Trennt Depot, Wertpapier-ID und Name im gespeicherten Wert. Das Steuerzeichen
     * "unit separator" kann in keinem der drei vorkommen; als Fluchtfolge geschrieben, weil es
     * sonst unsichtbar im Quelltext stuende.
     */
    private static final String SEP = "";

    private final SharedPreferences prefs;
    private final String profilePrefix;

    public StatementTemplates(Context context) {
        Context app = context.getApplicationContext();
        this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.profilePrefix = "p_" + new ProfileManager(app).getActiveProfileId() + "_";
    }

    private String keyTemplates() {
        return profilePrefix + KEY_TEMPLATES;
    }

    private String keyIsins() {
        return profilePrefix + KEY_ISINS;
    }

    // ---- Vorlagen ----

    /** Wie {@link #match(PdfText, String)}, ohne Depot — für Aufrufer, die keins kennen. */
    public StatementTemplate match(PdfText text) {
        return match(text, "");
    }

    /** Die Vorlage dieses Depots, die zu diesem Dokument passt, oder {@code null} (siehe {@link #best}). */
    public StatementTemplate match(PdfText text, String depot) {
        return best(all(depot), text);
    }

    /**
     * Die Vorlage, die am besten zu diesem Dokument passt, oder {@code null}.
     *
     * <p>Nennt das Dokument seine <b>Art</b> eindeutig ({@link StatementScan#certainAction}), kommen nur
     * Vorlagen dieser Art in Frage. Das muss über der Trefferzahl stehen: viele Banken rechnen Kauf und
     * Verkauf mit derselben Endzeile ab („Ausmachender Betrag"), und dann gewinnt sonst zufällig die
     * Vorlage, die eine Regel mehr mitbringt — mit umgekehrter Buchungsrichtung. Eine Regel weniger zu
     * lesen ist der kleinere Schaden.</p>
     *
     * <p>Ist die Art nicht eindeutig, entscheidet die Zahl der getroffenen Felder; bei Gleichstand der
     * bloße Vorschlag aus den Wortlisten, zuletzt die jüngere Vorlage — sie ist die genauere, denn beim
     * Lernen wird eine gleichartige ersetzt.</p>
     */
    public static StatementTemplate best(List<StatementTemplate> templates, PdfText text) {
        String certain = StatementScan.certainAction(text);
        if (certain != null) {
            List<StatementTemplate> ofKind = new ArrayList<>();
            for (StatementTemplate t : templates) {
                if (certain.equals(t.action)) {
                    ofKind.add(t);
                }
            }
            StatementTemplate best = byScore(ofKind, text, null);
            if (best != null) {
                return best;
            }
            // Für diese Art ist noch nichts gelernt – dann lieber die andere als gar keine.
        }
        return byScore(templates, text, StatementScan.guessAction(text));
    }

    private static StatementTemplate byScore(List<StatementTemplate> templates, PdfText text,
                                             String guess) {
        StatementTemplate best = null;
        int bestScore = 0;
        boolean bestFitsAction = false;
        for (StatementTemplate t : templates) {
            int score = t.score(text);
            if (score <= 0) {
                continue;
            }
            boolean fitsAction = guess != null && guess.equals(t.action);
            boolean better = best == null || score > bestScore
                    || (score == bestScore && (fitsAction || !bestFitsAction));
            if (better) {
                best = t;
                bestScore = score;
                bestFitsAction = fitsAction;
            }
        }
        return best;
    }

    /** Wie {@link #all(String)}, über alle Depots hinweg — nur für die Fälle, denen keins bekannt ist. */
    public List<StatementTemplate> all() {
        return all("");
    }

    /** Die Vorlagen dieses Depots (höchstens drei: je eine für buy, sell, dividend). */
    public List<StatementTemplate> all(String depot) {
        List<StatementTemplate> out = new ArrayList<>();
        for (Entry e : entries()) {
            if (sameDepot(e.depot, depot)) {
                out.add(e.template);
            }
        }
        return out;
    }

    /**
     * Merkt sich eine Vorlage im Depot {@code ""} — für Aufrufer, die keins kennen.
     *
     * @see #save(StatementTemplate, String)
     */
    public void save(StatementTemplate template) {
        save(template, "");
    }

    /**
     * Merkt sich eine Vorlage für dieses Depot. Eine bereits vorhandene Vorlage desselben Depots und
     * derselben Aktion wird ersetzt — je Depot gibt es nie mehr als eine Vorlage je Aktion.
     */
    public void save(StatementTemplate template, String depot) {
        if (template == null || template.isEmpty()) {
            return;
        }
        List<Entry> kept = new ArrayList<>();
        for (Entry e : entries()) {
            if (!(sameDepot(e.depot, depot) && sameAction(e.template.action, template.action))) {
                kept.add(e);
            }
        }
        kept.add(new Entry(normalize(depot), template));
        write(kept);
    }

    /**
     * Schreibt die ganze Liste — für die Regelseite, auf der von Hand bearbeitet wird. Ersetzt alles im
     * Depot {@code ""}; für ein bestimmtes Depot {@link #saveAll(String, List)} nehmen.
     *
     * <p>Der Javadoc sagte das schon immer, die Methode tat es nicht: sie schrieb die übergebene Liste
     * als <b>gesamten</b> Bestand und löschte damit die Vorlagen aller anderen Depots gleich mit.
     * Aufgerufen wurde sie bisher nur aus Tests, in denen es kein zweites Depot gab — als öffentliche
     * Methode war sie trotzdem eine Falle. Jetzt geht sie denselben Weg wie ihre Schwester.</p>
     */
    public void saveAll(List<StatementTemplate> templates) {
        saveAll("", templates);
    }

    /**
     * Schreibt die Vorlagen dieses Depots neu — die anderer Depots bleiben unangetastet.
     *
     * <p>Nicht {@link #save}: das ersetzt nur je Aktion eine, hier steht die ganze von Hand geordnete
     * Liste des Depots auf einmal fest.</p>
     */
    public void saveAll(String depot, List<StatementTemplate> templates) {
        List<Entry> kept = new ArrayList<>();
        for (Entry e : entries()) {
            if (!sameDepot(e.depot, depot)) {
                kept.add(e);
            }
        }
        for (StatementTemplate t : templates) {
            if (t == null || t.isEmpty()) {
                continue;
            }
            kept.add(new Entry(normalize(depot), t));
        }
        write(kept);
    }

    /**
     * Alles Gelernte dieses Profils verwerfen (Auslieferungszustand).
     *
     * <p>Nur die beiden Einträge dieses Profils, nicht die ganze Datei: die Vorlagen aller Profile
     * liegen in derselben und tragen den Profilnamen nur im Schlüssel. Ein {@code clear()} nahm dem
     * Nutzer beim Zurücksetzen <b>eines</b> Profils das Gelernte aller anderen mit — ohne dass es
     * irgendwo aufgefallen wäre, denn eine leere Vorlagenliste sieht aus wie eine, die es noch nicht
     * gibt.</p>
     */
    public void clearAll() {
        prefs.edit().remove(keyTemplates()).remove(keyIsins()).apply();
    }

    private static boolean sameDepot(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    private static boolean sameAction(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String normalize(String depot) {
        return depot == null ? "" : depot;
    }

    // ---- ISIN → Wertpapier ----

    /**
     * Das Wertpapier zu einer ISIN als {@code {Depot, kmyId, Name}}, oder {@code null}. Nur für ISINs,
     * die in KMyMoney nicht gepflegt sind — dort gefundene haben Vorrang und brauchen dieses Gedächtnis
     * nicht. Der Name steht mit dabei, damit die Maske ohne zweite Abfrage öffnen kann.
     */
    public String[] security(String isin) {
        if (isin == null || isin.trim().isEmpty()) {
            return null;
        }
        try {
            String value = new JSONObject(prefs.getString(keyIsins(), "{}"))
                    .optString(isin.trim().toUpperCase(java.util.Locale.ROOT), "");
            String[] parts = value.split(SEP, -1);
            return parts.length == 3 ? parts : null;
        } catch (Exception e) {
            return null;
        }
    }

    public void rememberSecurity(String isin, String depot, String kmyId, String name) {
        if (isin == null || isin.trim().isEmpty() || depot == null || kmyId == null) {
            return;
        }
        try {
            JSONObject root = new JSONObject(prefs.getString(keyIsins(), "{}"));
            root.put(isin.trim().toUpperCase(java.util.Locale.ROOT),
                    depot + SEP + kmyId + SEP + (name == null ? "" : name));
            prefs.edit().putString(keyIsins(), root.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    // ---- JSON ----

    /** Eine gespeicherte Vorlage mitsamt dem Depot, zu dem sie gehört. */
    private static final class Entry {
        final String depot;
        final StatementTemplate template;

        Entry(String depot, StatementTemplate template) {
            this.depot = normalize(depot);
            this.template = template;
        }
    }

    private List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(keyTemplates(), "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                StatementTemplate t = StatementRulesIo.templateFromJson(o);
                if (t != null && !t.isEmpty()) {
                    out.add(new Entry(o == null ? "" : o.optString("d", ""), t));
                }
            }
        } catch (Exception ignored) {
            // Unlesbarer Bestand: lieber ohne Vorlagen weiterarbeiten als die App daran scheitern lassen.
        }
        return out;
    }

    private void write(List<Entry> entries) {
        JSONArray arr = new JSONArray();
        for (Entry e : entries) {
            JSONObject o = StatementRulesIo.templateToJson(e.template);
            if (o != null) {
                try {
                    o.put("d", e.depot);
                } catch (Exception ignored) {
                }
                arr.put(o);
            }
        }
        prefs.edit().putString(keyTemplates(), arr.toString()).apply();
    }

}
