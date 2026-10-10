package de.spahr.ausgaben.statement;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.spahr.ausgaben.pdf.PdfText;

/**
 * Was die App über die Abrechnungen <b>einer</b> Bank für <b>eine</b> Bewegungsart gelernt hat: je Feld
 * eine {@link AnchorRule}, dazu die Aktion, die zu solchen Dokumenten gehört.
 *
 * <p>Eine Vorlage erkennt sich an ihren eigenen Ankertexten wieder — kommen alle im Dokument vor, ist es
 * eine Abrechnung dieser Bank und dieser Art. Eigene Erkennungsmerkmale braucht es dafür nicht: die
 * Wortwahl der Beschriftungen ist ohnehin bankspezifisch.</p>
 */
public final class StatementTemplate {

    /** Die Felder, die ausgelesen werden. */
    public enum Field {
        /** Stückzahl. */
        SHARES,
        /** Stückpreis; bei Dividenden nicht ausgelesen (dort steht er in Fremdwährung). */
        PRICE,
        /** Gebühr bzw. Steuer, oft die Summe mehrerer Zeilen. */
        FEE,
        /** Gesamtsumme bzw. Nettogutschrift — der Wert, der aufs Konto geht. */
        NET,
        /** Das gebuchte Datum unter den mehreren, die eine Abrechnung trägt. */
        DATE,
        /**
         * Der Bruttobetrag in Kontowährung. Der Lerner legt dafür <b>keine</b> Regel an — das Brutto wird
         * in der Maske gerechnet und nicht eingetippt, eine daran geratene Beschriftung träfe irgendeine
         * Zeile mit derselben Zahl.
         *
         * <p>Von Hand ist das Feld dagegen nützlich: bei einem dollarnotierten Papier steht der gebuchte
         * Betrag in der Umrechnungszeile, bei einem euronotierten in der Bruttozeile. Als Kette
         * {@code [Umg. z. Dev.-Kurs, Brutto]} mit Währung EUR liest dieselbe Vorlage beide.</p>
         */
        GROSS
    }

    /**
     * Eine Regel für einen <b>Teilbetrag</b> — eine einzelne Steuer- oder Gebührenzeile unterhalb des
     * Gesamtbetrags, den {@link Field#FEE} liest, bzw. ein Teil des Ertrags.
     *
     * <p>Zusammengehalten wird das Ganze von der <b>Beschriftung</b>: über sie findet ein Betrag beim
     * nächsten Mal wieder zu seiner Kategorie, gleich in welcher Reihenfolge die Zeilen stehen und
     * auch dann, wenn eine davon einmal fehlt.</p>
     */
    public static final class PartRule {
        public final String label;
        public final AnchorRule rule;
        /**
         * Die Kategorie, unter der dieser Teilbetrag gebucht wird; leer, wenn keine festgelegt ist.
         *
         * <p>Sie gehört zur Bank und nicht zum Wertpapier — deshalb steht sie hier und nicht nur in
         * der letzten Buchung. Ist sie gesetzt, gewinnt sie: was auf der Regelseite eingetragen oder
         * beim Buchen gelernt wurde, ist eine Festlegung, die Historie nur ein Schluss daraus.</p>
         */
        public final String category;
        /**
         * Die Seite von {@link #category} ({@code true} = Einnahme-, {@code false} =
         * Ausgabekategorie); {@code null}, solange sie nicht ermittelt ist oder keine Kategorie
         * dasteht. Denselben Pfad kann es in KMyMoney in beiden Bäumen geben.
         */
        public final Boolean categoryIsIncome;

        public PartRule(String label, AnchorRule rule) {
            this(label, rule, "");
        }

        public PartRule(String label, AnchorRule rule, String category) {
            this(label, rule, category, null);
        }

        public PartRule(String label, AnchorRule rule, String category, Boolean categoryIsIncome) {
            this.label = label == null ? "" : label.trim();
            this.rule = rule;
            this.category = category == null ? "" : category.trim();
            this.categoryIsIncome = this.category.isEmpty() ? null : categoryIsIncome;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof PartRule)) {
                return false;
            }
            PartRule other = (PartRule) o;
            return label.equals(other.label) && rule.equals(other.rule)
                    && category.equals(other.category)
                    && seiteGleich(categoryIsIncome, other.categoryIsIncome);
        }

        @Override
        public int hashCode() {
            return (label.hashCode() * 31 + rule.hashCode()) * 31 + category.hashCode();
        }
    }

    /**
     * Ein ausgelesener Teilbetrag: was in der Abrechnung stand, unter welcher Beschriftung — und,
     * wenn die Vorlage es weiß, unter welcher Kategorie es gebucht gehört.
     */
    public static final class Part {
        public final String label;
        public final long cents;
        public final String category;
        /**
         * Die vom Nutzer in der Maske gewählte (oder automatisch als erste gefundene) Anker-Regel für
         * diese Zeile; {@code null}, wenn keine feststeht — dann sucht {@link TemplateLearner} beim
         * Lernen selbst danach, wie bisher. Siehe {@code chosenRules} in {@link TemplateLearner.Known}
         * für dasselbe Prinzip bei den Hauptfeldern.
         */
        public final AnchorRule chosenRule;
        /** Die Seite von {@link #category}; {@code null} = unbekannt. Siehe {@link PartRule}. */
        public final Boolean categoryIsIncome;

        public Part(String label, long cents) {
            this(label, cents, "");
        }

        public Part(String label, long cents, String category) {
            this(label, cents, category, null);
        }

        public Part(String label, long cents, String category, AnchorRule chosenRule) {
            this(label, cents, category, chosenRule, null);
        }

        public Part(String label, long cents, String category, AnchorRule chosenRule,
                    Boolean categoryIsIncome) {
            this.label = label == null ? "" : label;
            this.cents = cents;
            this.category = category == null ? "" : category;
            this.chosenRule = chosenRule;
            this.categoryIsIncome = this.category.trim().isEmpty() ? null : categoryIsIncome;
        }
    }

    /** Eine der Aktionen {@code buy}, {@code sell}, {@code dividend}. */
    public final String action;
    private final Map<Field, AnchorRule> rules;
    /** Aufschlüsselung der Steuer bzw. Gebühr in einzelne Zeilen; leer, wenn nichts gelernt wurde. */
    public final List<PartRule> feeParts;
    /** Dasselbe für den Ertrag einer Dividende. */
    public final List<PartRule> incomeParts;
    /**
     * Die Kategorie des <b>ganzen</b> Gebühren- bzw. Steuerbetrags — für die Banken, bei denen es
     * nichts aufzuteilen gibt. Bei aufgeteilten Beträgen tragen die Teile ihre eigene.
     */
    public final String feeCategory;
    /** Dasselbe für den Ertrag einer Dividende. */
    public final String incomeCategory;

    /**
     * Eine Gebühr, die die Bank <b>nicht</b> ausdruckt — 0, wenn es keine gibt.
     *
     * <p>Scalable Capital nimmt je Order einen festen Betrag, der in der Abrechnung nirgends steht. Eine
     * Ankerregel kann ihn nicht finden, denn sie sucht nach einer Beschriftung; deshalb steht er hier an
     * der Vorlage und nicht in einer {@link AnchorRule}.</p>
     */
    public final long fixedFeeCents;
    /** Kategorie, unter der die feste Gebühr gebucht wird. */
    public final String fixedFeeCategory;
    /**
     * Ob die feste Gebühr im ausgedruckten Gesamtbetrag schon steckt.
     *
     * <p>Steckt sie nicht darin, ist der abgebuchte Betrag um sie höher als die Zahl auf dem Beleg — und
     * dann muss sie dort nachgetragen werden, sonst stimmte der Kontostand nicht.</p>
     */
    public final boolean fixedFeeInTotal;

    /**
     * Die Seiten der drei Kategorien, die an der Vorlage selbst hängen ({@link #feeCategory},
     * {@link #incomeCategory}, {@link #fixedFeeCategory}); je {@code null} = noch nicht ermittelt.
     */
    public static final class Seiten {
        public static final Seiten LEER = new Seiten(null, null, null);
        public final Boolean fee;
        public final Boolean income;
        public final Boolean fixedFee;

        public Seiten(Boolean fee, Boolean income, Boolean fixedFee) {
            this.fee = fee;
            this.income = income;
            this.fixedFee = fixedFee;
        }
    }

    public final Seiten seiten;

    /** Zwei Seiten widersprechen sich nur, wenn beide bekannt und verschieden sind. */
    static boolean seiteGleich(Boolean a, Boolean b) {
        return a == null || b == null || a.equals(b);
    }

    public StatementTemplate(String action, Map<Field, AnchorRule> rules) {
        this(action, rules, 0L, "", false);
    }

    public StatementTemplate(String action, Map<Field, AnchorRule> rules, long fixedFeeCents,
                             String fixedFeeCategory, boolean fixedFeeInTotal) {
        this(action, rules, fixedFeeCents, fixedFeeCategory, fixedFeeInTotal, null, null, "", "");
    }

    public StatementTemplate(String action, Map<Field, AnchorRule> rules, long fixedFeeCents,
                             String fixedFeeCategory, boolean fixedFeeInTotal,
                             List<PartRule> feeParts, List<PartRule> incomeParts,
                             String feeCategory, String incomeCategory) {
        this(action, rules, fixedFeeCents, fixedFeeCategory, fixedFeeInTotal, feeParts, incomeParts,
                feeCategory, incomeCategory, Seiten.LEER);
    }

    public StatementTemplate(String action, Map<Field, AnchorRule> rules, long fixedFeeCents,
                             String fixedFeeCategory, boolean fixedFeeInTotal,
                             List<PartRule> feeParts, List<PartRule> incomeParts,
                             String feeCategory, String incomeCategory, Seiten seiten) {
        this.seiten = seiten == null ? Seiten.LEER : seiten;
        this.action = action;
        this.feeCategory = feeCategory == null ? "" : feeCategory.trim();
        this.incomeCategory = incomeCategory == null ? "" : incomeCategory.trim();
        this.rules = new EnumMap<>(Field.class);
        this.rules.putAll(rules);
        this.fixedFeeCents = Math.max(0, fixedFeeCents);
        this.fixedFeeCategory = fixedFeeCategory == null ? "" : fixedFeeCategory.trim();
        this.fixedFeeInTotal = fixedFeeInTotal;
        this.feeParts = feeParts == null
                ? java.util.Collections.emptyList()
                : java.util.Collections.unmodifiableList(new ArrayList<>(feeParts));
        this.incomeParts = incomeParts == null
                ? java.util.Collections.emptyList()
                : java.util.Collections.unmodifiableList(new ArrayList<>(incomeParts));
    }

    /** Dieselbe Vorlage mit anderer fester Gebühr – für die Regelseite. */
    public StatementTemplate withFixedFee(long cents, String category, boolean inTotal) {
        return withFixedFee(cents, category, inTotal, null);
    }

    /** Wie {@link #withFixedFee(long, String, boolean)}, mit der Seite der Kategorie. */
    public StatementTemplate withFixedFee(long cents, String category, boolean inTotal,
                                          Boolean categoryIsIncome) {
        return new StatementTemplate(action, rules, cents, category, inTotal, feeParts, incomeParts,
                feeCategory, incomeCategory,
                new Seiten(seiten.fee, seiten.income, categoryIsIncome));
    }

    /** Dieselbe Vorlage mit anderen Seiten ihrer Kategorien – für das einmalige Nachtragen. */
    public StatementTemplate withSeiten(Seiten neu, List<PartRule> newFeeParts,
                                        List<PartRule> newIncomeParts) {
        return new StatementTemplate(action, rules, fixedFeeCents, fixedFeeCategory, fixedFeeInTotal,
                newFeeParts, newIncomeParts, feeCategory, incomeCategory, neu);
    }

    /** Sagt zu einem Kategorienamen die Seite, wenn sie daraus eindeutig hervorgeht; sonst {@code null}. */
    public interface Seitenwissen {
        Boolean eindeutig(String category);
    }

    /**
     * Dieselbe Vorlage, in der jede Kategorie ihre Seite trägt. Angefasst wird nur, was noch keine
     * hat: Dann gilt, was {@code wissen} über den Namen sagt, und sonst die Rolle – was unter Steuer
     * und Gebühr steht, ist eine Ausgabe, was unter dem Ertrag steht, eine Einnahme.
     *
     * @return {@code this}, wenn nichts fehlte
     */
    public StatementTemplate mitNachgetragenenSeiten(Seitenwissen wissen) {
        boolean[] geaendert = new boolean[1];
        List<PartRule> fee = nachgetragen(feeParts, false, wissen, geaendert);
        List<PartRule> income = nachgetragen(incomeParts, true, wissen, geaendert);
        Seiten neu = new Seiten(
                nachgetragen(feeCategory, seiten.fee, false, wissen, geaendert),
                nachgetragen(incomeCategory, seiten.income, true, wissen, geaendert),
                nachgetragen(fixedFeeCents > 0 ? fixedFeeCategory : "", seiten.fixedFee, false,
                        wissen, geaendert));
        return geaendert[0] ? withSeiten(neu, fee, income) : this;
    }

    private static Boolean nachgetragen(String category, Boolean seite, boolean rolle,
                                        Seitenwissen wissen, boolean[] geaendert) {
        if (seite != null || category == null || category.trim().isEmpty()) {
            return seite;
        }
        Boolean bekannt = wissen == null ? null : wissen.eindeutig(category.trim());
        geaendert[0] = true;
        return bekannt != null ? bekannt : Boolean.valueOf(rolle);
    }

    private static List<PartRule> nachgetragen(List<PartRule> parts, boolean rolle,
                                               Seitenwissen wissen, boolean[] geaendert) {
        List<PartRule> out = new ArrayList<>();
        for (PartRule p : parts) {
            Boolean seite = nachgetragen(p.category, p.categoryIsIncome, rolle, wissen, geaendert);
            out.add(seite == p.categoryIsIncome ? p : new PartRule(p.label, p.rule, p.category, seite));
        }
        return out;
    }

    /** Dieselbe Vorlage mit anderen Teilbetragsregeln – für die Regelseite. */
    public StatementTemplate withParts(List<PartRule> newFeeParts, List<PartRule> newIncomeParts) {
        return new StatementTemplate(action, rules, fixedFeeCents, fixedFeeCategory, fixedFeeInTotal,
                newFeeParts, newIncomeParts, feeCategory, incomeCategory, seiten);
    }

    /**
     * Dieselbe Vorlage, aber für die genannten Felder mit den Regeln aus {@code other}.
     *
     * <p>Damit lässt sich je Feld zwischen „ersetzen" ({@link #mergedOver}) und „hinzufügen"
     * ({@link #appendedTo}) wählen: beide liefern eine ganze Vorlage, und diese Methode setzt die
     * eine feldweise in die andere ein. Alles außer den Feldregeln — Aufteilungen, feste Gebühr,
     * Kategorien — bleibt aus {@code this}.</p>
     */
    public StatementTemplate withRulesFrom(StatementTemplate other, java.util.Set<Field> fields) {
        if (other == null || fields == null || fields.isEmpty()) {
            return this;
        }
        Map<Field, AnchorRule> gemischt = new EnumMap<>(rules);
        for (Field field : fields) {
            AnchorRule fremd = other.rules.get(field);
            if (fremd == null) {
                gemischt.remove(field);
            } else {
                gemischt.put(field, fremd);
            }
        }
        return new StatementTemplate(action, gemischt, fixedFeeCents, fixedFeeCategory,
                fixedFeeInTotal, feeParts, incomeParts, feeCategory, incomeCategory, seiten);
    }

    public Map<Field, AnchorRule> rules() {
        return new LinkedHashMap<>(rules);
    }

    public AnchorRule rule(Field field) {
        return rules.get(field);
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /** Ob diese Vorlage zum Dokument gehört (siehe {@link #score}). */
    public boolean matches(PdfText text) {
        return score(text) > 0;
    }

    /**
     * Wie gut die Vorlage zum Dokument passt: die Zahl der Beschriftungen, die darin eine Zeile anführen.
     * {@code 0} heißt „gehört nicht dazu".
     *
     * <p>Erkannt wird die Abrechnung an der <b>Gesamtsumme</b>: fehlt deren Beschriftung, ist es eine
     * andere Bank oder eine andere Art — „Endbetrag zu Ihren Lasten" gegen „Gesamtbetrag zu Ihren
     * Gunsten" unterscheidet beides in einem. Alles Übrige darf fehlen.</p>
     *
     * <p>Vollständigkeit zu verlangen wäre falsch, und zwar still: eine Bank lässt die Valuta-Zeile weg,
     * wenn sie mit dem Zahltag zusammenfällt, und den Solidaritätszuschlag, wenn keiner anfällt. Bestünde
     * die Vorlage auf jeder Zeile, fiele sie bei solchen Abrechnungen ganz aus — und übernommen würde
     * <b>nichts</b>, obwohl Betrag, Stückzahl und Kurs sauber dastehen. Jedes Feld wird deshalb für sich
     * gesucht; was fehlt, bleibt leer.</p>
     */
    public int score(PdfText text) {
        if (text == null || rules.isEmpty()) {
            return 0;
        }
        AnchorRule net = rules.get(Field.NET);
        if (net == null || net.hits(text) == 0) {
            return 0;
        }
        // Gezählt werden Felder, nicht Beschriftungen: eine lange Rückfall-Kette macht eine Vorlage nicht
        // treffender, sie macht sie nur nachsichtiger.
        int score = 0;
        for (AnchorRule rule : rules.values()) {
            if (rule.hits(text) > 0) {
                score++;
            }
        }
        return score;
    }

    /**
     * Diese Vorlage, ergänzt um die Regeln von {@code older} für Felder, zu denen hier keine entstand.
     *
     * <p>Gebraucht beim Lernen: aus einer Abrechnung, in der eine Zeile fehlt, lässt sich für dieses Feld
     * nichts ableiten — und dann darf das bisher Gelernte nicht verlorengehen. Sonst verkürzte eine
     * Abrechnung ohne Solidaritätszuschlag die Steuerregel auf eine Zeile, und die nächste vollständige
     * läse zu wenig Steuer, ohne dass es auffiele.</p>
     */
    public StatementTemplate mergedOver(StatementTemplate older) {
        if (older == null) {
            return this;
        }
        Map<Field, AnchorRule> merged = new EnumMap<>(Field.class);
        for (Field field : Field.values()) {
            AnchorRule mine = rules.get(field);
            AnchorRule old = older.rules.get(field);
            if (mine == null) {
                mine = old;
            } else if (isExcerptOf(mine, old)) {
                mine = old;
            }
            if (mine != null) {
                merged.put(field, mine);
            }
        }
        return new StatementTemplate(action, merged, keptFixedFee(older),
                keptFixedCategory(older), keptFixedInTotal(older),
                keptParts(feeParts, older.feeParts), keptParts(incomeParts, older.incomeParts),
                keptCategory(feeCategory, older.feeCategory),
                keptCategory(incomeCategory, older.incomeCategory), keptSeiten(older));
    }

    /**
     * Teilbetragsregeln beider Vorlagen, nach Beschriftung zusammengeführt — die neue gewinnt, die
     * alten Beschriftungen bleiben stehen.
     *
     * <p>Dass nichts wegfällt, ist hier wichtiger als bei den Feldern: eine Ausschüttung ohne
     * Solidaritätszuschlag lernte sonst dessen Zeile weg, und die nächste vollständige Abrechnung
     * verteilte die Steuer auf zu wenige Kategorien — ohne dass die Summe es verriete, denn die liest
     * ja eine eigene Regel.</p>
     */
    /** Die Seite wandert mit der Kategorie, von der sie stammt (siehe die {@code kept…}-Methoden). */
    private Seiten keptSeiten(StatementTemplate older) {
        return new Seiten(feeCategory.isEmpty() ? older.seiten.fee : seiten.fee,
                incomeCategory.isEmpty() ? older.seiten.income : seiten.income,
                fixedFeeCents > 0 ? seiten.fixedFee : older.seiten.fixedFee);
    }

    /** Eine einmal festgelegte Kategorie geht beim Lernen nicht verloren. */
    private static String keptCategory(String newer, String older) {
        return newer.isEmpty() ? older : newer;
    }

    private static List<PartRule> keptParts(List<PartRule> newer, List<PartRule> older) {
        List<PartRule> merged = new ArrayList<>(newer);
        for (PartRule old : older) {
            boolean bekannt = false;
            for (PartRule mine : newer) {
                if (mine.label.equalsIgnoreCase(old.label)) {
                    bekannt = true;
                    break;
                }
            }
            if (!bekannt) {
                merged.add(old);
            }
        }
        return merged;
    }

    /**
     * Die feste Gebühr überlebt jeden Lernvorgang.
     *
     * <p>Der Lerner setzt sie nie – sie steht ja nicht im Dokument, sondern wurde auf der Regelseite von
     * Hand eingetragen. Ohne diese Übernahme wäre sie nach der nächsten eingelesenen Abrechnung fort, und
     * niemand käme darauf, warum.</p>
     */
    private long keptFixedFee(StatementTemplate older) {
        return fixedFeeCents > 0 ? fixedFeeCents : older.fixedFeeCents;
    }

    private String keptFixedCategory(StatementTemplate older) {
        return fixedFeeCents > 0 ? fixedFeeCategory : older.fixedFeeCategory;
    }

    private boolean keptFixedInTotal(StatementTemplate older) {
        return fixedFeeCents > 0 ? fixedFeeInTotal : older.fixedFeeInTotal;
    }

    /**
     * Ob die neue Regel nur ein Ausschnitt der alten ist — sie liest dieselben Zeilen, bloß weniger
     * davon.
     *
     * <p>Dann hat diese Abrechnung schlicht weniger Zeilen gehabt, und nicht die Bank ihre Beschriftungen
     * geändert. Die alte Regel bleibt, denn sie liest beides richtig: bei der vollständigen Abrechnung
     * alle Zeilen, bei dieser die eine vorhandene. Ohne diese Unterscheidung verkürzte eine Dividende
     * ohne Solidaritätszuschlag die Steuerregel dauerhaft auf eine Zeile.</p>
     */
    /**
     * Diese Vorlage, aber mit der Reihenfolge von {@code older}: dessen Beschriftungen behalten Vorrang,
     * neue kommen dazu — hinten, wenn die alte Regel in {@code text} nichts fand, sonst vorne.
     *
     * <p>Die Gegenstück zu {@link #mergedOver}: wer eine Kette von Hand geordnet hat, soll sie durch das
     * nächste Lernen ergänzt bekommen und nicht ersetzt. Richtung, Summenkennung und Währung bleiben die
     * der bisherigen Regel — die Reihenfolge ist die Entscheidung, die von Hand getroffen wurde.</p>
     *
     * <p>Fand die alte Regel in dieser Abrechnung etwas — und sei es der falsche Wert, den der Nutzer
     * gerade korrigiert hat —, soll sie beim nächsten Mal nicht mehr zuerst gewinnen: die neue
     * Beschriftung kommt vor sie. Fand sie nichts, war die Zeile hier nur nicht vorhanden, und die alte
     * Reihenfolge bleibt vorn — dieselbe Regel liest weiterhin auch vollständigere Abrechnungen.</p>
     */
    public StatementTemplate appendedTo(StatementTemplate older, PdfText text) {
        if (older == null) {
            return this;
        }
        Map<Field, AnchorRule> merged = new EnumMap<>(Field.class);
        for (Field field : Field.values()) {
            AnchorRule mine = rules.get(field);
            AnchorRule old = older.rules.get(field);
            if (old == null) {
                if (mine != null) {
                    merged.put(field, mine);
                }
                continue;
            }
            if (mine == null) {
                merged.put(field, old);
                continue;
            }
            java.util.List<String> anchors = new java.util.ArrayList<>(old.anchors);
            java.util.List<String> neu = new java.util.ArrayList<>();
            for (String anchor : mine.anchors) {
                if (!anchors.contains(anchor)) {
                    neu.add(anchor);
                }
            }
            if (!neu.isEmpty()) {
                boolean oldFoundEtwas = field == Field.DATE
                        ? old.readDate(text) > 0
                        : old.read(text) != null;
                if (oldFoundEtwas) {
                    anchors.addAll(0, neu);
                } else {
                    anchors.addAll(neu);
                }
            }
            // Die volle Signatur, nicht die kurze: position/nth/lineDistance gehören zur alten Regel und
            // fielen sonst auf COLUMN→LAST, nth→1, lineDistance→0 zurück. Eine gelernte Spaltenregel läse
            // ab dem ersten Nachlernen still die falsche Zahl.
            merged.put(field, anchors.size() == old.anchors.size() ? old
                    : new AnchorRule(anchors, old.direction, old.sum, old.currency,
                            old.position, old.nth, old.lineDistance));
        }
        return new StatementTemplate(action, merged, keptFixedFee(older),
                keptFixedCategory(older), keptFixedInTotal(older),
                keptParts(feeParts, older.feeParts), keptParts(incomeParts, older.incomeParts),
                keptCategory(feeCategory, older.feeCategory),
                keptCategory(incomeCategory, older.incomeCategory), keptSeiten(older));
    }

    private static boolean isExcerptOf(AnchorRule newer, AnchorRule older) {
        // Auf die Summenkennung kommt es dabei nicht an: gerade der Übergang von einer Summe auf eine
        // einzelne Zeile ist der Fall, um den es geht.
        return older != null && older.anchors.size() > newer.anchors.size()
                && older.anchors.containsAll(newer.anchors);
    }

    /**
     * Liest die Werte aus dem Dokument. Was keine Regel trifft, bleibt leer — <b>geraten wird nichts</b>:
     * ein falsch vorbelegter Betrag, den man übersieht, ist schlimmer als ein leeres Feld.
     */
    public Extraction apply(PdfText text) {
        Extraction e = new Extraction();
        e.action = action;
        e.isin = StatementScan.isin(text);
        AnchorRule shares = rules.get(Field.SHARES);
        AnchorRule price = rules.get(Field.PRICE);
        AnchorRule fee = rules.get(Field.FEE);
        AnchorRule net = rules.get(Field.NET);
        AnchorRule date = rules.get(Field.DATE);
        AnchorRule gross = rules.get(Field.GROSS);
        e.grossCents = gross == null ? null : gross.readCents(text);
        e.shares = shares == null ? null : shares.read(text);
        e.price = price == null ? null : price.read(text);
        // Die Aufteilung zuerst: findet die Regel für den Gesamtbetrag nichts, tragen die Teilzeilen
        // die Summe. So liest sich eine Abrechnung, deren Steuerzeilen einzeln ausgewiesen sind, auch
        // dann vollständig, wenn die Bank keine Summenzeile druckt.
        List<Part> teile = readParts(feeParts, text);
        Long geleseneGebuehr = fee == null ? null : fee.readCents(text);
        boolean fest = angesetzt(geleseneGebuehr, fee);
        Long ausTeilen = teile.isEmpty() ? null : sumOf(teile);
        e.feeCents = withFixedFee(geleseneGebuehr == null ? ausTeilen : geleseneGebuehr, fest);
        // Stehen Teilzeilen daneben, ist die feste Gebühr eine weitere davon und keine Kategorie für
        // das Ganze — sonst ergäben die Zeilen zusammen nicht mehr den Betrag darüber.
        if (fest && !teile.isEmpty()) {
            teile.add(new Part("", fixedFeeCents, fixedFeeCategory, null, seiten.fixedFee));
        }
        e.feeCategory = fest && teile.isEmpty() ? fixedFeeCategory : "";
        e.feeCategoryIsIncome = e.feeCategory.isEmpty() ? null : seiten.fixedFee;
        // Eine Regel, die gesucht und nichts gefunden hat, sagt etwas anderes als eine fehlende Regel:
        // „stand nicht drin" statt „weiß ich nicht". Bei einer Dividende macht das den Unterschied — sonst
        // gilt die Steuer als unbekannt, und der Steuersatz aus den Einstellungen erfindet eine, obwohl
        // die Bank innerhalb des Freibetrags gar keine abgezogen hat.
        //
        // Bei Kauf und Verkauf ist die Unterscheidung entbehrlich: dort bedeutet ein leeres Gebührenfeld
        // ohnehin 0, und ein geschriebenes „0,00" verdeckte nur die Beschriftung des Feldes.
        if (fee != null && e.feeCents == null && "dividend".equals(action)) {
            e.feeCents = 0L;
        }
        e.netCents = net == null ? null : net.readCents(text);
        // Steckt die feste Gebühr nicht im ausgedruckten Gesamtbetrag, ist der abgebuchte Betrag um sie
        // höher – beim Verkauf die Gutschrift um sie niedriger. Nachgetragen wird nur, was oben auch
        // wirklich angesetzt wurde.
        if (e.netCents != null && !fixedFeeInTotal && fest) {
            e.netCents += ("sell".equals(action) ? -1 : 1) * fixedFeeCents;
        }
        e.dateMillis = date == null ? -1 : date.readDate(text);
        e.feeParts = orWhole(teile, feeCategory, seiten.fee, e.feeCents);
        e.incomeParts = orWhole(readParts(incomeParts, text), incomeCategory, seiten.income,
                e.grossCents);
        return e;
    }

    /**
     * Die Teilbeträge, die in dieser Abrechnung stehen. Zeilen, die es hier nicht gibt, fallen still
     * weg — eine Ausschüttung ohne Kirchensteuer hat eben nur zwei Steuerzeilen, und die dritte als 0
     * zu buchen brächte eine Kategorie ins Spiel, die gar nichts abbekommen hat.
     */
    private static List<Part> readParts(List<PartRule> parts, PdfText text) {
        List<Part> out = new ArrayList<>();
        for (PartRule part : parts) {
            Long cents = part.rule.readCents(text);
            if (cents != null) {
                out.add(new Part(part.label, Math.abs(cents), part.category, null,
                        part.categoryIsIncome));
            }
        }
        return out;
    }

    /**
     * Nichts aufzuteilen, aber eine Kategorie für das Ganze: dann ist der ganze Betrag die eine Zeile.
     * So gilt für Banken ohne Aufteilung dieselbe Mechanik wie für die mit.
     */
    private static List<Part> orWhole(List<Part> parts, String wholeCategory, Boolean wholeSide,
                                      Long wholeCents) {
        if (parts.isEmpty() && !wholeCategory.isEmpty() && wholeCents != null && wholeCents != 0) {
            parts.add(new Part("", Math.abs(wholeCents), wholeCategory, null, wholeSide));
        }
        return parts;
    }

    private static long sumOf(List<Part> parts) {
        long total = 0;
        for (Part part : parts) {
            total += part.cents;
        }
        return total;
    }

    /**
     * Ob die feste Gebühr bei dieser Abrechnung überhaupt zum Zuge kommt.
     *
     * <p>Darüber entscheidet der vorhandene Schalter «Mehrere Zeilen zusammenzählen»: er sagt bereits, ob
     * sich Gebührenbeträge addieren. Fand die Regel nichts – oder gibt es gar keine –, gilt der feste
     * Wert allein; das ist der Fall, um den es geht. Steht der Schalter aus und wurde etwas gefunden,
     * gewinnt der gefundene Wert.</p>
     */
    private boolean angesetzt(Long gelesen, AnchorRule fee) {
        if (fixedFeeCents <= 0) {
            return false;
        }
        return gelesen == null || (fee != null && fee.sum);
    }

    /** Die Gebühr, wie sie nach der Regel oben gilt — der gelesene Betrag zuzüglich der festen. */
    private Long withFixedFee(Long gelesen, boolean fest) {
        if (!fest) {
            return gelesen;
        }
        return (gelesen == null ? 0L : gelesen) + fixedFeeCents;
    }

    /**
     * Ob diese Vorlage dieselbe Auslese beschreibt wie {@code other} — gleiche Aktion, gleiche Regeln.
     *
     * <p>Damit lässt sich sagen, ob ein Lernvorgang überhaupt etwas Neues ergeben hat. Hat der Nutzer
     * nichts korrigiert, kommt beim Lernen dieselbe Vorlage heraus, und es gibt nichts zu fragen.</p>
     */
    public boolean sameAs(StatementTemplate other) {
        if (other == null) {
            return false;
        }
        if (action == null ? other.action != null : !action.equals(other.action)) {
            return false;
        }
        if (fixedFeeCents != other.fixedFeeCents || fixedFeeInTotal != other.fixedFeeInTotal
                || !fixedFeeCategory.equals(other.fixedFeeCategory)) {
            return false;
        }
        return rules.equals(other.rules) && feeParts.equals(other.feeParts)
                && incomeParts.equals(other.incomeParts)
                && feeCategory.equals(other.feeCategory)
                && incomeCategory.equals(other.incomeCategory)
                && seiteGleich(seiten.fee, other.seiten.fee)
                && seiteGleich(seiten.income, other.seiten.income)
                && seiteGleich(seiten.fixedFee, other.seiten.fixedFee);
    }

    /** Das Ergebnis einer Auslese. Nicht Erkanntes ist {@code null} bzw. -1. */
    public static final class Extraction {
        public String action;
        public String isin;
        public long dateMillis = -1;
        public Double shares;
        public Double price;
        public Long feeCents;
        /** Kategorie einer festen Gebühr; leer, wenn keine angesetzt wurde. */
        public String feeCategory = "";
        /** Die Seite von {@link #feeCategory}; {@code null} = unbekannt. */
        public Boolean feeCategoryIsIncome;
        public Long netCents;
        /** Nur gesetzt, wenn von Hand eine Brutto-Regel angelegt wurde (siehe {@link Field#GROSS}). */
        public Long grossCents;
        /**
         * Aufschlüsselung der Steuer bzw. Gebühr in einzelne Zeilen; leer, wenn nichts aufzuschlüsseln
         * war. Ob die Teile eine Beschriftung tragen, entscheidet später über ihre Zuordnung: eine
         * gelernte Vorlage kennt sie, ein fest programmierter Leser gibt seine Teile nur der Reihe
         * nach.
         */
        public java.util.List<Part> feeParts = new ArrayList<>();
        /** Dasselbe für den Ertrag. */
        public java.util.List<Part> incomeParts = new ArrayList<>();

        /** Ob überhaupt etwas herauskam, das die Maske vorbelegen kann. */
        public boolean hasAnything() {
            return isin != null || dateMillis > 0 || shares != null || price != null
                    || feeCents != null || netCents != null || grossCents != null;
        }
    }
}
