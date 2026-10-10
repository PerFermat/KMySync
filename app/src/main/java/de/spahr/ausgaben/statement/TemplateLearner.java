package de.spahr.ausgaben.statement;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.spahr.ausgaben.pdf.PdfText;
import de.spahr.ausgaben.util.TextValues;

/**
 * Leitet aus einer <b>einmal selbst erfassten</b> Abrechnung ab, wo die Werte stehen.
 *
 * <p>Der Nutzer tippt die erste Abrechnung einer Bank ohnehin ab. Danach kennt die App seine Zahlen und
 * den Text des Dokuments — und kann die Beschriftungen selbst finden: sie sucht {@code 1.000,00} und
 * sieht links daneben „Endbetrag zu Ihren Lasten". Eine Markier-Oberfläche, in der man auf dem Handy
 * kleine Zahlen antippt, wird damit überflüssig, und das Ergebnis ist robuster als eine Markierung.</p>
 *
 * <p>Reine Rechenklasse ohne Android-Bezug.</p>
 */
public final class TemplateLearner {

    /** Stückzahlen müssen genau stimmen – 0,005 Stück wären bei einem Sparplan ein echter Unterschied. */
    private static final double SHARE_EPSILON = 0.0000005;

    /**
     * Geldbeträge und Kurse dürfen um einen halben Cent abweichen.
     *
     * <p>Nötig, weil die Maske einen fehlenden Wert zurückrechnet und dabei in der letzten Stelle von der
     * Bank abweicht: 1.000,00 ÷ 6,09607 ergibt 164,0401, im Dokument steht 164,04. Ohne die Toleranz
     * fände der Lerner die Zeile nicht und käme nie zu einer Kursregel — der Wert der Abrechnung gilt.</p>
     */
    private static final double MONEY_EPSILON = 0.005;

    /** Höchstens so viele Zeilen werden zu einer Summe zusammengesucht (Steuer + Soli + Kirchensteuer). */
    private static final int MAX_SUMMANDS = 3;

    /**
     * Bis zu dieser Stelle wird beim <b>Lernen</b> in einer Zeile nach dem Wert gesucht (siehe
     * {@code stelleIn}).
     *
     * <p>Von Hand lässt sich auf der Regelseite weiter zählen — bewusst: Wer die Stelle selbst wählt,
     * weiß, was er meint, während der Lerner nur rät. Siehe {@code StatementRulesActivity}.</p>
     */
    static final int MAX_STELLE = 3;

    private TemplateLearner() {
    }

    /** Die Werte, die der Nutzer für dieses Dokument eingetragen hat. Nicht Gesetztes ist {@code null}. */
    public static final class Known {
        public String action;
        public Double shares;
        public Double price;
        public Long feeCents;
        /** Kategorie der Gebühr — nur gebraucht, wenn daraus eine feste Ordergebühr wird. */
        public String feeCategory = "";
        /** Die Seite von {@link #feeCategory}; {@code null} = unbekannt. */
        public Boolean feeCategoryIsIncome;
        public Long netCents;
        /**
         * Der Bruttobetrag einer Dividende — nur gesetzt, wenn der Nutzer für ihn <b>ausdrücklich</b>
         * eine Beschriftung gewählt hat (siehe {@link #chosenRules}).
         *
         * <p>Von selbst sucht der Lerner dafür weiterhin nichts: Das Brutto steht in der Maske
         * meistens als gerechnete Zahl, und eine daran geratene Beschriftung träfe irgendeine Zeile
         * mit demselben Betrag — siehe {@link StatementTemplate.Field#GROSS}. Hat der Nutzer die Zeile
         * dagegen selbst angetippt, ist nichts mehr zu raten.</p>
         */
        public Long grossCents;
        public long dateMillis = -1;
        /**
         * Die Beschriftung, die der Nutzer für das Datum ausgewählt hat — sie hat Vorrang. Nötig, weil
         * mehrere Zeilen dasselbe Datum tragen können („Zahltag" und „Valuta" fallen oft zusammen); ohne
         * seine Wahl würde die unterste gelernt, und in der nächsten Abrechnung wäre es die falsche.
         */
        public String dateAnchor;
        /**
         * Die Regel, die der Nutzer in der Auswahl angetippt hat — sie hat Vorrang vor allem Suchen.
         *
         * <p>Die Auswahl legt zu einem Datum beide Lesarten vor: die Beschriftung daneben und die
         * Spaltenüberschrift darüber. Erratbar ist danach nicht mehr, welche er gemeint hat — die
         * Beschriftung allein sagt es nicht. Also reicht die Auswahl die fertige Regel durch.</p>
         */
        public AnchorRule dateRule;
        /**
         * Dasselbe für die Wertfelder: was der Nutzer beim Verlassen des Feldes ausgewählt hat.
         *
         * <p>Der Grund ist derselbe wie beim Datum, nur häufiger: zu einer Zahl führen mehrere
         * Beschriftungen, und welche die Bank im nächsten Beleg behält, steht nicht im Dokument
         * (siehe {@link #kandidaten}). Was hier steht, hat Vorrang vor der eigenen Suche — sofern es
         * den Wert auch wirklich liest; sonst sucht der Lerner wie bisher.</p>
         */
        public Map<StatementTemplate.Field, AnchorRule> chosenRules =
                new EnumMap<>(StatementTemplate.Field.class);
        /**
         * Die Teilbeträge, in die der Nutzer die Gebühr bzw. Steuer aufgeteilt hat — jeder für sich
         * eine Zeile der Abrechnung. Gelernt wird daraus je eine eigene Regel, damit beim nächsten
         * Mal nicht nur die Summe, sondern auch ihre Aufteilung dasteht.
         */
        public List<StatementTemplate.Part> feeParts = new ArrayList<>();
        /** Dasselbe für die Aufteilung des Ertrags. */
        public List<StatementTemplate.Part> incomeParts = new ArrayList<>();
    }

    /**
     * Baut die Vorlage. Für jedes Feld, dessen Wert sich im Text eindeutig wiederfindet, entsteht eine
     * Regel; für die übrigen <b>keine</b> — eine geratene Regel wäre schlimmer als gar keine, denn sie
     * belegt künftig still das falsche Feld vor.
     */
    public static StatementTemplate learn(PdfText text, Known known) {
        Map<StatementTemplate.Field, AnchorRule> rules =
                new EnumMap<>(StatementTemplate.Field.class);
        if (text == null || known == null) {
            return new StatementTemplate(known == null ? null : known.action, rules);
        }
        List<String> used = new ArrayList<>();

        // Die Aufteilung zuerst: sie sagt, wieviel von der Gebühr überhaupt im Dokument steht — und
        // damit, wieviel davon für eine feste Ordergebühr noch übrigbleibt.
        Teile gebuehrenteile = learnParts(text, known.feeParts);
        Teile ertragsteile = learnParts(text, known.incomeParts);
        long rest = gebuehrenteile.rest(known.feeCents);

        // Reihenfolge nach Unterscheidungskraft: die Gesamtsumme ist die kennzeichnendste Zahl, die
        // Stückzahl die unscheinbarste – eine glatte 10 findet sich in einer Abrechnung schnell mehrfach.
        // Die Währung wird nur dort festgehalten, wo sie feststeht: Gesamtsumme und Steuer gehen als
        // Buchung aufs Konto und sind deshalb immer in Kontowährung. Der Stückpreis dagegen ist die
        // Notierung des Wertpapiers – bei einem Dollar-Papier steht dort USD, bei einem Euro-Papier EUR.
        // Bände man ihn an die einmal gelernte Währung, ginge nach dem Lernen an einem Dollar-Papier
        // kein Euro-Papier mehr, und umgekehrt.
        AnchorRule net = regelFuer(known, StatementTemplate.Field.NET, text, cents(known.netCents),
                MONEY_EPSILON, true, used);
        put(rules, StatementTemplate.Field.NET, net, used);
        AnchorRule fee = regelFuer(known, StatementTemplate.Field.FEE, text, cents(known.feeCents),
                MONEY_EPSILON, true, used);
        put(rules, StatementTemplate.Field.FEE, fee, used);

        // Weder Gesamtbetrag noch Gebühr gefunden? Dann kann beides denselben Grund haben: die Bank nimmt
        // eine feste Ordergebühr, druckt sie nicht aus – und ihr Gesamtbetrag ist der ohne diese Gebühr.
        //
        // Fest ist dabei nur, was <b>nicht</b> im Dokument steht: haben die Teilregeln die Steuerzeilen
        // gefunden, bleibt als feste Gebühr allein der Rest. Ohne diese Rechnung würde bei Scalable
        // Capital die ganze Steuer zur Ordergebühr — 773,58 statt der 0,99, die die Bank wirklich nimmt —
        // und die Gesamtbetrag-Regel landete auf dem Kurswert statt auf der Gutschrift.
        long fixedFee = 0;
        if (net == null && fee == null) {
            AnchorRule ersatz = forValue(text, cents(printedTotal(known, rest)), MONEY_EPSILON, true, used);
            if (ersatz != null) {
                put(rules, StatementTemplate.Field.NET, ersatz, used);
                fixedFee = rest;
            }
        }

        put(rules, StatementTemplate.Field.PRICE, regelFuer(known, StatementTemplate.Field.PRICE,
                text, known.price, MONEY_EPSILON, false, used), used);
        put(rules, StatementTemplate.Field.SHARES, regelFuer(known, StatementTemplate.Field.SHARES,
                text, known.shares, SHARE_EPSILON, false, used), used);
        // Das Brutto nur aus der Wahl des Nutzers, ohne eigene Suche (siehe Known#grossCents).
        AnchorRule brutto = known.chosenRules == null ? null
                : known.chosenRules.get(StatementTemplate.Field.GROSS);
        if (brutto != null && known.grossCents != null
                && liestSichSelbst(brutto, text, Math.abs(known.grossCents) / 100.0, MONEY_EPSILON)) {
            put(rules, StatementTemplate.Field.GROSS, brutto, used);
        }
        put(rules, StatementTemplate.Field.DATE,
                known.dateRule != null && known.dateRule.readDate(text) == known.dateMillis
                        ? known.dateRule
                        : forDate(text, known.dateMillis, known.dateAnchor, used), used);
        return new StatementTemplate(known.action, rules, fixedFee,
                fixedFee > 0 ? gebuehrenteile.restKategorie(known.feeCategory) : "", false,
                gebuehrenteile.rules, ertragsteile.rules,
                wholeCategory(known.feeParts), wholeCategory(known.incomeParts),
                new StatementTemplate.Seiten(wholeSide(known.feeParts), wholeSide(known.incomeParts),
                        fixedFee > 0 ? gebuehrenteile.restSeite(known.feeCategoryIsIncome) : null));
    }

    /**
     * Das Ergebnis des Teilelernens: die Regeln, und was von der Gebühr <b>nicht</b> im Dokument stand.
     *
     * <p>Beides gehört zusammen. Der Rest ist genau das, was eine feste Ordergebühr sein kann — und
     * seine Kategorie steht in der Zeile, für die keine Regel entstand: der Nutzer hat sie ja
     * eingetragen, obwohl die Bank sie nicht ausdruckt.</p>
     */
    private static final class Teile {
        final List<StatementTemplate.PartRule> rules = new ArrayList<>();
        /** Die Teilbeträge, für die sich keine Regel finden ließ — in der Reihenfolge der Maske. */
        final List<StatementTemplate.Part> ungefunden = new ArrayList<>();
        long gefunden;

        /** Was von der Gebühr für eine feste Ordergebühr übrigbleibt; ohne Aufteilung ihr ganzer Betrag. */
        long rest(Long feeCents) {
            if (feeCents == null) {
                return 0;
            }
            return Math.max(0, Math.abs(feeCents) - gefunden);
        }

        /** Die Kategorie des nicht gefundenen Teils; sonst die mitgegebene. */
        String restKategorie(String vorgabe) {
            for (StatementTemplate.Part part : ungefunden) {
                if (!part.category.trim().isEmpty()) {
                    return part.category;
                }
            }
            return vorgabe;
        }

        /** Die Seite zu {@link #restKategorie}: die der Zeile, deren Kategorie dort genommen wird. */
        Boolean restSeite(Boolean vorgabe) {
            for (StatementTemplate.Part part : ungefunden) {
                if (!part.category.trim().isEmpty()) {
                    return part.categoryIsIncome;
                }
            }
            return vorgabe;
        }
    }

    /**
     * Die Kategorie des ganzen Betrags — nur, wenn er gar nicht aufgeteilt wurde.
     *
     * <p>Bei einer Aufteilung tragen die Teile ihre eigene; eine zusätzliche für das Ganze wäre eine
     * zweite Wahrheit über dieselbe Zahl.</p>
     */
    private static String wholeCategory(List<StatementTemplate.Part> parts) {
        return parts != null && parts.size() == 1 ? parts.get(0).category : "";
    }

    /** Die Seite zu {@link #wholeCategory}. */
    private static Boolean wholeSide(List<StatementTemplate.Part> parts) {
        return parts != null && parts.size() == 1 ? parts.get(0).categoryIsIncome : null;
    }

    /**
     * Je Teilbetrag eine eigene Regel, benannt nach der Beschriftung, die sie trifft.
     *
     * <p>Eine einzelne Zeile wird nicht gelernt: sie trägt den ganzen Betrag und wäre nichts weiter
     * als eine zweite Regel für dieselbe Zahl, die die Summenregel schon liest. Erst ab zwei Zeilen
     * gibt es eine Aufteilung, die sich zu merken lohnt.</p>
     *
     * <p>Die Beschriftungen der Summenregel bleiben hier ausdrücklich frei: die Steuer steht bei der
     * ING als Summe von Kapitalertragsteuer und Solidaritätszuschlag da, und genau diese beiden
     * Zeilen sind es, die die Teile suchen. Wären sie schon vergeben, fände kein Teil mehr etwas.</p>
     */
    private static Teile learnParts(PdfText text, List<StatementTemplate.Part> parts) {
        Teile out = new Teile();
        if (parts == null || parts.size() < 2) {
            // Eine einzelne Zeile bleibt ungeteilt – und damit ganz „nicht gefunden": ihr Betrag ist
            // die Summe selbst, und ob er im Dokument steht, entscheidet die Summenregel.
            if (parts != null) {
                out.ungefunden.addAll(parts);
            }
            return out;
        }
        List<String> used = new ArrayList<>();
        for (StatementTemplate.Part part : parts) {
            // Wie bei den Hauptfeldern (siehe regelFuer): hat der Nutzer beim Verlassen des Feldes eine
            // Beschriftung gewählt (oder wurde nur eine gefunden), gilt sie — sofern sie den Betrag noch
            // liest. Sonst sucht der Lerner wie bisher selbst.
            AnchorRule rule = part.chosenRule != null
                    && liestSichSelbst(part.chosenRule, text, Math.abs(part.cents) / 100.0, MONEY_EPSILON)
                    ? part.chosenRule
                    : forValue(text, part.cents / 100.0, MONEY_EPSILON, true, used);
            if (rule == null) {
                out.ungefunden.add(part);
                continue;
            }
            for (String anchor : rule.anchors) {
                used.add(taken(anchor, rule.position));
            }
            String label = rule.matchedAnchor(text);
            if (label == null || label.trim().isEmpty()) {
                label = rule.anchors.get(0);
            }
            // Die Kategorie kommt aus der Maske mit: gelernt wird nicht nur, wo der Betrag steht,
            // sondern auch, wohin er gebucht gehört.
            out.rules.add(new StatementTemplate.PartRule(label, rule, part.category,
                    part.categoryIsIncome));
            out.gefunden += Math.abs(part.cents);
        }
        return out;
    }

    /**
     * Der Betrag, den die Bank ausdrucken würde, wenn ihr Gesamtbetrag die feste Gebühr <b>nicht</b>
     * enthält — oder {@code null}, wenn diese Vermutung hier nicht in Frage kommt.
     *
     * <p>Beim Kauf erhöht die Gebühr die Belastung, beim Verkauf mindert sie die Gutschrift; der
     * ausgedruckte Betrag ist also der eingetragene ohne sie. Dasselbe Vorzeichen benutzt
     * {@code SecurityAmounts}.</p>
     *
     * <p><b>Nur bei Kauf und Verkauf.</b> Bei einer Dividende ist die „Gebühr" die Steuer, und
     * Brutto minus Steuer ergibt das Netto — die Rechnung träfe dort regelmäßig die Bruttozeile und
     * erfände eine Ordergebühr, die es nicht gibt.</p>
     *
     * @param rest der Teil der Gebühr, der im Dokument nicht steht — nur er kann fest sein
     */
    private static Long printedTotal(Known known, long rest) {
        boolean kauf = StatementScan.BUY.equals(known.action);
        boolean verkauf = StatementScan.SELL.equals(known.action);
        if ((!kauf && !verkauf) || known.netCents == null || rest == 0) {
            return null;
        }
        return known.netCents - (kauf ? rest : -rest);
    }

    private static Double cents(Long value) {
        return value == null ? null : value / 100.0;
    }

    /**
     * Die Regel für ein Wertfeld: die vom Nutzer gewählte, sonst die selbst gesuchte.
     *
     * <p>Die Wahl gilt nur, wenn sie den eingetragenen Wert auch liest. Sie kann veraltet sein — wer
     * eine Beschriftung antippt und den Betrag danach noch einmal ändert, meint sie nicht mehr. Dann
     * ist ein selbst gesuchter Vorschlag besser als eine Regel, die von Anfang an etwas anderes
     * liest.</p>
     */
    private static AnchorRule regelFuer(Known known, StatementTemplate.Field field, PdfText text,
                                        Double value, double epsilon, boolean bindCurrency,
                                        List<String> used) {
        if (value == null) {
            return null;
        }
        AnchorRule gewaehlt = known.chosenRules == null ? null : known.chosenRules.get(field);
        if (gewaehlt != null && liestSichSelbst(gewaehlt, text, Math.abs(value), epsilon)) {
            return gewaehlt;
        }
        return forValue(text, value, epsilon, bindCurrency, used);
    }

    private static void put(Map<StatementTemplate.Field, AnchorRule> rules,
                            StatementTemplate.Field field, AnchorRule rule, List<String> used) {
        if (rule != null) {
            rules.put(field, rule);
            for (String anchor : rule.anchors) {
                used.add(taken(anchor, rule.position));
            }
        }
    }

    /**
     * Der Merkposten für eine schon vergebene Beschriftung — samt der Zahl, die sie meint.
     *
     * <p>Die Stelle gehört dazu, weil eine Zeile zwei Felder tragen kann: „St. 1.437 EUR 37,22" nennt
     * vorn die Stückzahl und hinten den Kurs. Ohne die Unterscheidung nähme sich der Kurs die
     * Beschriftung „St.", und für die Stückzahl bliebe keine übrig.</p>
     */
    private static String taken(String anchor, AnchorRule.Position position) {
        return anchor + '\u001F' + position;
    }

    /**
     * Sucht eine Regel für {@code value}. Zuerst in einer einzelnen Zeile; findet sich der Wert dort
     * nicht, wird er als <b>Summe</b> mehrerer Zeilen gesucht — so steht die Steuer bei der ING, auf
     * Kapitalertragsteuer und Solidaritätszuschlag verteilt.
     */
    private static AnchorRule forValue(PdfText text, Double value, double epsilon,
                                       boolean bindCurrency, List<String> used) {
        if (value == null) {
            return null;
        }
        AnchorRule single = singleLine(text, value, epsilon, bindCurrency, used);
        if (single != null && single.direction == AnchorRule.Direction.SAME_LINE) {
            return single;
        }
        // Steht der Wert in einer Tabellenzeile, gewinnt die Spalte vor jeder abgezählten Stelle: eine
        // fehlende Spalte verschiebt jede gezählte Stelle, die Position nicht.
        //
        // Das setzt echte Wortpositionen voraus. Im zurückgebauten Text ({@link PdfText#fromLines})
        // hängen die Wörter mit einem Leerzeichen aneinander — eine Tabelle ist darin gar nicht mehr zu
        // sehen, und die Bedingung schlösse nur aus, ohne je etwas zu finden. Genau das hat die Messung
        // am fremden Bestand gezeigt, der aus solchen Textdateien besteht: mit der Bedingung auf beiden
        // Wegen fielen die vollständig gelesenen Banken von 12 auf 10 und die jüngsten Abrechnungen von
        // 39,6 auf 39,1 Prozent. Über die Tabellenlogik sagt das nichts — dort ist sie gar nicht
        // messbar; sie greift nur, wo die Koordinaten stehen.
        if (text.hasWordPositions()) {
            AnchorRule tabelle = columnLine(text, value, epsilon, bindCurrency, used, true);
            if (tabelle != null) {
                return tabelle;
            }
            if (single != null) {
                return single;
            }
        }
        // Ohne Tabellenlage bleibt die Spalte, was sie war: die erste Wahl vor der gezählten Stelle.
        AnchorRule spalte = columnLine(text, value, epsilon, bindCurrency, used, false);
        if (spalte != null) {
            return spalte;
        }
        if (single != null) {
            return single;
        }
        AnchorRule summed = summedLines(text, value, epsilon, bindCurrency, used);
        if (summed != null) {
            return summed;
        }
        // Zuletzt die Form, die sich nicht abzählen lässt: der Wert über seiner Beschriftung.
        return aboveLine(text, value, epsilon, bindCurrency, used);
    }

    /**
     * Ab wievielen Zahlen nebeneinander eine Zeile als <b>Tabellenzeile</b> gilt.
     *
     * <p>Zwei. Eine Betragszeile trägt ihre eine Zahl und sonst Text („Kurswert 1.100,00 EUR" — das
     * Währungskürzel zählt nicht mit); stehen zwei Zahlen nebeneinander, sind es Spalten. Drei wäre zu
     * streng: eine Anleihe-Zeile führt Nominale und Kurs nebeneinander und ist die Tabelle, um die es
     * geht — an einem Bestand fremder Belege kommt die Stückzahl in 876 von 2354 Fällen <b>nur</b> so
     * vor (siehe {@code AnchorFallbackTest#derLernerFindetDieSpalteVonSelbst}).</p>
     */
    private static final int TABELLE_AB = 2;

    /** Wieviele Zahlen die Zeile trägt. Währungskennzeichen und Prozentzeichen sind keine. */
    private static int zahlenIn(PdfText.Line line) {
        int n = 0;
        for (PdfText.Word word : line.words) {
            if (TextValues.toDecimal(word.text) != null) {
                n++;
            }
        }
        return n;
    }

    /**
     * Ob die Zeile wie eine <b>Tabellenzeile</b> aussieht: mehrere Werte nebeneinander.
     *
     * <p>Das Merkmal entscheidet zweierlei — ob der Lerner die Spalte der abgezählten Stelle vorzieht,
     * und ob {@link StatementScan} eine Spaltenüberschrift überhaupt zur Wahl stellt. Ohne die Frage
     * stünde über jedem beliebigen Datum irgendein Wort, das sich als „Überschrift" anbietet: über dem
     * Briefdatum am rechten Rand etwa die Anschrift.</p>
     */
    static boolean istTabellenzeile(PdfText.Line line) {
        return zahlenIn(line) >= TABELLE_AB || datenIn(line) >= 2;
    }

    /** Wieviele Datumsangaben die Zeile trägt — das Tabellenmerkmal einer Datumszeile. */
    private static int datenIn(PdfText.Line line) {
        int n = 0;
        for (PdfText.Word word : line.words) {
            if (TextValues.toUnambiguousDateMillis(word.text) > 0) {
                n++;
            }
        }
        return n;
    }

    /**
     * Der Wert steht in der <b>Spalte</b> einer Überschrift, die einige Zeilen darüber steht.
     *
     * <p>Hier hilft kein Abzählen: fehlt in einer Zeile eine Spalte oder besteht eine Überschrift aus
     * zwei Wörtern, zeigt jede gezählte Stelle auf die falsche Zahl. Über die Wortposition ist die Spalte
     * dagegen eindeutig (siehe {@link AnchorRule.Position#COLUMN}).</p>
     *
     * <p>Gelernt wird mit <b>festem</b> Abstand zur Überschrift. Suchend wäre hier untauglich: in der
     * Spalte einer Überschrift steht fast in jeder Zeile irgendeine Zahl, und die erste beste wäre
     * selten die gemeinte.</p>
     */
    private static AnchorRule columnLine(PdfText text, double value, double epsilon,
                                         boolean bindCurrency, List<String> used,
                                         boolean nurTabelle) {
        List<PdfText.Line> lines = text.lines();
        AnchorRule found = null;
        for (int i = 0; i < lines.size(); i++) {
            if (nurTabelle && zahlenIn(lines.get(i)) < TABELLE_AB) {
                continue;
            }
            for (PdfText.Word word : lines.get(i).words) {
                Double zahl = TextValues.toDecimal(word.text);
                if (zahl == null || Math.abs(Math.abs(zahl) - value) > epsilon) {
                    continue;
                }
                String currency = bindCurrency
                        ? AnchorRule.currencyOf(lines.get(i).text()) : "";
                AnchorRule probe = columnRuleFor(text, i, word, used, currency,
                        gelesen -> gelesen != null && Math.abs(gelesen - value) <= epsilon, null);
                if (probe != null) {
                    found = probe;   // wie sonst auch: die unterste Fundstelle gewinnt
                }
            }
        }
        return found;
    }

    /** Prüft, was eine Regel gelesen hat — für Zahlen bzw. für Datumsangaben. */
    interface ValueCheck {
        boolean stimmt(Double gelesen);
    }

    interface DateCheck {
        boolean stimmt(long millis);
    }

    /**
     * Die Spaltenregel zu einem Wort: die Überschrift darüber suchen, daraus eine Regel bauen und sie
     * sich selbst bestätigen lassen.
     *
     * <p>Die eine Stelle, an der die Spaltensuche steht — der Lerner benutzt sie für Zahlen wie für
     * Datumsangaben, und {@link StatementScan} legt damit dem Nutzer die Werte einer Tabelle zur Wahl
     * vor. Gesucht wird in <b>zwei Durchgängen</b>: erst die Zeilen, in denen ein Wort waagerecht über
     * dem Wert steht, dann die übrigen (siehe {@link #columnLabel}).</p>
     *
     * @param used  Beschriftungen, die andere Felder schon belegt haben; darf leer sein
     * @param wert  gesetzt für eine Zahl, {@code null} für ein Datum
     * @param datum gesetzt für ein Datum, {@code null} für eine Zahl
     */
    static AnchorRule columnRuleFor(PdfText text, int lineIndex, PdfText.Word word, List<String> used,
                                    String currency, ValueCheck wert, DateCheck datum) {
        List<PdfText.Line> lines = text.lines();
        for (int runde = 0; runde < 2; runde++) {
            for (int j = lineIndex - 1; j >= 0 && j >= lineIndex - AnchorRule.MAX_DISTANCE; j--) {
                String label = columnLabel(lines.get(j), word, runde == 0);
                if (!isUsable(label, used, AnchorRule.Position.COLUMN)) {
                    continue;
                }
                AnchorRule probe = new AnchorRule(java.util.Collections.singletonList(label),
                        AnchorRule.Direction.LINE_BELOW, false, currency == null ? "" : currency,
                        AnchorRule.Position.COLUMN, 1, lineIndex - j);
                boolean stimmt = wert != null ? wert.stimmt(probe.read(text))
                        : datum.stimmt(probe.readDate(text));
                if (stimmt) {
                    return probe;
                }
            }
        }
        return null;
    }

    /**
     * Die Überschrift über einem Wert: das Wort der Kopfzeile, das waagerecht über ihm steht.
     *
     * <p>Zahlen scheiden aus — in einer Kopfzeile stehen Namen, und eine Zahl darin gehört zu einer
     * anderen Zeile, die nur ähnlich hoch liegt. Ist das getroffene Wort zu kurz, um als Anker zu taugen
     * („Stk."), kommt das Wort davor dazu.</p>
     *
     * <p>Mit {@code nurUeberlappende} zählt nur, was <b>waagerecht über</b> dem Wert steht. Das ist der
     * erste Durchgang der Suche, und er ist nötig: über einer Tabellenzeile steht oft nicht die
     * Kopfzeile, sondern eine einzelne Zelle weit rechts (in einer Dividendenabrechnung etwa
     * „Wechselkurs"). Ohne diese Vorfahrt gölte die als Spaltenüberschrift, und die Suche bräche dort
     * ab, statt eine Zeile weiter oben die wirkliche Überschrift zu finden. Erst wenn keine Zeile
     * darüber ein überschneidendes Wort trägt, entscheidet wie bisher der Abstand — bei rechtsbündigen
     * Zahlenspalten unter linksbündigen Überschriften überlappt nämlich nichts.</p>
     */
    private static String columnLabel(PdfText.Line header, PdfText.Word value,
                                      boolean nurUeberlappende) {
        PdfText.Word best = null;
        int bestIndex = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < header.words.size(); i++) {
            PdfText.Word word = header.words.get(i);
            if (TextValues.toDecimal(word.text) != null) {
                continue;
            }
            boolean overlap = word.x <= value.endX && word.endX >= value.x;
            float distance = Math.abs((word.x + word.endX) / 2f - (value.x + value.endX) / 2f);
            if (overlap) {
                return withPrevious(header, i);
            }
            if (nurUeberlappende) {
                continue;
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
                best = word;
            }
        }
        return best == null ? "" : withPrevious(header, bestIndex);
    }

    /** Das Wort an dieser Stelle — und, wenn es allein zu kurz wäre, das davor mit dazu. */
    private static String withPrevious(PdfText.Line line, int index) {
        String word = line.words.get(index).text;
        if (word.trim().length() >= 3 || index == 0) {
            return word;
        }
        return line.words.get(index - 1).text + ' ' + word;
    }

    /**
     * Der Wert steht <b>über</b> seiner Beschriftung. Spiegelbild zu der Überschrift-Suche in
     * {@link #singleLine}: manche Belege setzen die Summe nach oben und benennen sie darunter.
     */
    private static AnchorRule aboveLine(PdfText text, double value, double epsilon,
                                        boolean bindCurrency, List<String> used) {
        List<PdfText.Line> lines = text.lines();
        AnchorRule found = null;
        for (int i = 0; i < lines.size(); i++) {
            int[] stelle = stelleIn(lines.get(i).text(), value, epsilon);
            if (stelle == null) {
                continue;
            }
            AnchorRule.Position where = stelle[0] == 1
                    ? AnchorRule.Position.FIRST : AnchorRule.Position.LAST;
            for (int j = i + 1; j < lines.size() && j <= i + AnchorRule.MAX_DISTANCE; j++) {
                String label = labelOf(lines.get(j).text());
                if (!isUsable(label, used, where)) {
                    continue;
                }
                AnchorRule probe = new AnchorRule(java.util.Collections.singletonList(label),
                        AnchorRule.Direction.LINE_ABOVE, false,
                        bindCurrency ? AnchorRule.currencyOf(lines.get(i).text()) : "",
                        where, stelle[1], j - i);
                Double gelesen = probe.read(text);
                if (gelesen != null && Math.abs(gelesen - value) <= epsilon) {
                    found = probe;
                    break;
                }
            }
        }
        return found;
    }

    /**
     * Der Wert steht als letzte Zahl einer Zeile — der Regelfall.
     *
     * <p>Jede Regel wird <b>zurückgelesen</b>, bevor sie gilt: sie muss auf demselben Beleg wieder den
     * Wert hergeben, aus dem sie entstanden ist. Ohne diese Probe entstand aus „STK 170 … EUR 126,933"
     * eine Regel für den Stückpreis mit der Beschriftung „STK" — die aber steht in derselben
     * Abrechnung noch zweimal, und beim Lesen gewinnt die <b>unterste</b> Fundstelle
     * ({@link AnchorRule#read}). Herauskam das anteilige Ergebnis aus der Steuertabelle von Seite 2.
     * {@code columnLine} und {@code aboveLine} halten diese Probe längst; hier fehlte sie.</p>
     */
    private static AnchorRule singleLine(PdfText text, double value, double epsilon,
                                         boolean bindCurrency, List<String> used) {
        List<AnchorRule> gefunden = new ArrayList<>();
        zeilenregeln(text, value, epsilon, bindCurrency, used, gefunden, false);
        // Mehrere Fundstellen: die unterste gewinnt – in einer Abrechnung steht die Endsumme unten,
        // und sie ist die Zahl, die auch mit Gebühren noch stimmt.
        return gefunden.isEmpty() ? null : gefunden.get(gefunden.size() - 1);
    }

    /**
     * Der sammelnde Kern von {@link #singleLine}: jede Beschriftung, die diesen Wert in ihrer eigenen
     * Zeile (oder in der Zeile darunter) wiederfindet, in der Reihenfolge des Dokuments.
     *
     * <p>Mit {@code alle} entscheidet der Aufrufer, wieviel er braucht. Der Lerner nimmt je Fundstelle
     * die erste Beschriftung, die sich bestätigt, und ist fertig — er sucht einen Vorschlag. Die
     * Auswahlliste der Maske dagegen will jede: dort soll der Nutzer ja gerade zwischen „STK" und
     * „Nominale" entscheiden können ({@link #kandidaten}).</p>
     */
    private static void zeilenregeln(PdfText text, double value, double epsilon,
                                     boolean bindCurrency, List<String> used,
                                     List<AnchorRule> out, boolean alle) {
        List<PdfText.Line> lines = text.lines();
        for (int i = 0; i < lines.size(); i++) {
            // Von aussen nach innen: erst die letzte Zahl – der Regelfall –, dann die erste (unter einer
            // Spaltenüberschrift führt der Wert die Zeile an), dann die zweitletzte und so fort. In einer
            // Tabellenzeile steht der gesuchte Wert weiter innen: „… MARGIN 5 $74.33000" nennt den Kurs
            // zuletzt und die Menge davor.
            int[] stelle = stelleIn(lines.get(i).text(), value, epsilon);
            if (stelle == null) {
                continue;
            }
            AnchorRule.Position where = stelle[0] == 1
                    ? AnchorRule.Position.FIRST : AnchorRule.Position.LAST;
            int nth = stelle[1];
            String zeile = lines.get(i).text();
            AnchorRule probe = null;
            // Beschriftung in derselben Zeile — erst die unmittelbar vor dem Wert, dann die am
            // Zeilenanfang. Beides wird gebraucht: „Beispielstraße 1 DATUM 13.05.2019" gibt nur die erste
            // her, „Stückzinsen für 153 Tage per 26.11.2015 73,16-" nur die zweite. Hat die Zeile keine
            // brauchbare, steht sie eine Zeile darüber.
            //
            // Nicht mehr über {@code firstUsable}: hält die erste brauchbare Beschriftung die Rückprobe
            // nicht, muss die zweite noch drankommen.
            for (String kandidat : new String[]{labelBefore(zeile, where), labelOf(zeile)}) {
                if (!isUsable(kandidat, used, where)) {
                    continue;
                }
                // Die Währung aus dem Text hinter der Beschriftung – genau dem, den die Regel beim Lesen
                // ansieht. Nähme man die ganze Zeile, lernte „UNSOLICITED NET AMOUNT $371.65" die
                // Währung „NET" (drei Großbuchstaben) und fände sie nie wieder.
                AnchorRule regel = AnchorRule.single(kandidat, AnchorRule.Direction.SAME_LINE,
                        bindCurrency ? AnchorRule.currencyOf(AnchorRule.afterAnchorText(zeile, kandidat))
                                : "",
                        where, nth);
                if (liestSichSelbst(regel, text, value, epsilon)) {
                    probe = regel;
                    if (alle) {
                        merke(out, regel);
                        continue;
                    }
                    break;
                }
            }
            // Hat die Zeile keine brauchbare Beschriftung, steht sie eine Zeile darüber. Beim Sammeln
            // wird sie zusätzlich angeboten: eine Tabellenzeile trägt beides, „STK" daneben und
            // „Nominale" darüber, und welche die Bank im nächsten Beleg behält, weiß nur der Nutzer.
            if (probe == null || alle) {
                AnchorRule oben = ruleAbove(text, lines, i, used, where, nth, value, epsilon,
                        bindCurrency);
                if (alle) {
                    merke(out, oben);
                } else {
                    probe = oben;
                }
            }
            if (!alle && probe != null) {
                out.add(probe);
            }
        }
    }

    /** Nimmt die Regel auf, wenn nicht schon eine gleichlautende dasteht. */
    private static void merke(List<AnchorRule> out, AnchorRule rule) {
        if (rule != null && !out.contains(rule)) {
            out.add(rule);
        }
    }

    /**
     * <b>Alle</b> Wege, auf denen dieser Wert in diesem Dokument zu erreichen ist — für die Auswahl in
     * der Erfassungsmaske.
     *
     * <p>Der Lerner sucht sich sonst einen davon aus, und das ist die Stelle, an der er raten muss:
     * „STK 86 Vanguard FTSE All-World U.ETF EUR 116,20" unter der Überschrift „Nominale … Kurs" gibt
     * für den Kurs drei Beschriftungen her — die Überschrift „Kurs" (richtig), das „STK" daneben
     * (zufällig auch) und den Fondsnamen (beim nächsten Fonds falsch). Aus dem Dokument allein ist
     * das nicht zu entscheiden; also legt die Maske die Wege vor und lässt wählen, wie sie es beim
     * Datum längst tut ({@link StatementScan#dates}).</p>
     *
     * <p>Die Reihenfolge ist die des Lerners, damit sein Vorschlag und die Auswahl nicht
     * auseinanderlaufen: erst die Beschriftungen in der Zeile selbst und darüber, dann die
     * Spaltenüberschriften, dann der Wert über seiner Beschriftung, zuletzt die Summe mehrerer Zeilen.
     * Jeder Kandidat ist zurückgelesen — was hier draufsteht, findet die Regel hinterher wieder.</p>
     *
     * <p>Eine <b>leere</b> Liste ist kein Fehler: der gerechnete Gesamtbetrag einer Dividende und eine
     * feste Ordergebühr stehen nirgends im Dokument. Dann gibt es nichts zu fragen.</p>
     */
    /**
     * Wie {@link #kandidaten(PdfText, Double, double, boolean)}, aber mit den Maßen des Feldes — damit
     * die Maske sie nicht selbst kennen muss und nicht von denen abweichen kann, mit denen
     * {@link #learn} kurz darauf sucht.
     */
    public static List<AnchorRule> kandidaten(PdfText text, StatementTemplate.Field field,
                                              Double value) {
        boolean geld = field == StatementTemplate.Field.NET || field == StatementTemplate.Field.FEE;
        return kandidaten(text, value,
                field == StatementTemplate.Field.SHARES ? SHARE_EPSILON : MONEY_EPSILON,
                // Die Währung wird nur dort festgehalten, wo sie feststeht: Gesamtsumme und Steuer
                // gehen aufs Konto, der Stückpreis ist die Notierung des Papiers (siehe learn).
                geld);
    }

    /**
     * Wie {@link #kandidaten(PdfText, StatementTemplate.Field, Double)}, für einen Betrag ohne eigenes
     * Feld der Vorlage — einen Teilbetrag einer Aufteilung (Gebühr/Steuer, Ertrag). Bindet die Währung
     * wie Gebühr und Netto, denn ein Teilbetrag geht immer aufs Konto, nie in die Notierung des Papiers.
     */
    public static List<AnchorRule> kandidatenFuerBetrag(PdfText text, Double value) {
        return kandidaten(text, value, MONEY_EPSILON, true);
    }

    public static List<AnchorRule> kandidaten(PdfText text, Double value, double epsilon,
                                              boolean bindCurrency) {
        List<AnchorRule> out = new ArrayList<>();
        if (text == null || value == null) {
            return out;
        }
        double gesucht = Math.abs(value);
        List<String> used = java.util.Collections.emptyList();
        zeilenregeln(text, gesucht, epsilon, bindCurrency, used, out, true);
        spaltenregeln(text, gesucht, epsilon, bindCurrency, used, out);
        merke(out, aboveLine(text, gesucht, epsilon, bindCurrency, used));
        merke(out, summedLines(text, gesucht, epsilon, bindCurrency, used));
        return out;
    }

    /**
     * Die Spaltenüberschriften, unter denen dieser Wert steht — der zweite Teil von
     * {@link #kandidaten}.
     *
     * <p>Nur in Tabellenzeilen und nur bei echten Wortpositionen: sonst bietet sich über jeder
     * beliebigen Zahl irgendein Wort als „Überschrift" an. Dieselbe Schranke zieht
     * {@link StatementScan#values}.</p>
     */
    private static void spaltenregeln(PdfText text, double value, double epsilon,
                                      boolean bindCurrency, List<String> used,
                                      List<AnchorRule> out) {
        if (!text.hasWordPositions()) {
            return;
        }
        List<PdfText.Line> lines = text.lines();
        for (int i = 0; i < lines.size(); i++) {
            if (!istTabellenzeile(lines.get(i))) {
                continue;
            }
            for (PdfText.Word word : lines.get(i).words) {
                Double zahl = TextValues.toDecimal(word.text);
                if (zahl == null || Math.abs(Math.abs(zahl) - value) > epsilon) {
                    continue;
                }
                merke(out, columnRuleFor(text, i, word, used,
                        bindCurrency ? AnchorRule.currencyOf(lines.get(i).text()) : "",
                        gelesen -> gelesen != null && Math.abs(gelesen - value) <= epsilon, null));
            }
        }
    }

    /** Ob die Regel auf ihrem eigenen Lernbeleg wieder den gelernten Wert hergibt. */
    private static boolean liestSichSelbst(AnchorRule rule, PdfText text, double value,
                                           double epsilon) {
        Double gelesen = rule.read(text);
        return gelesen != null && Math.abs(gelesen - value) <= epsilon;
    }

    /**
     * Die Regel zu einer Spaltenüberschrift <b>über</b> der Wertzeile — {@code null}, wenn keine trägt.
     *
     * <p>Wie {@link #labelAbove}, aber mit der Rückprobe: gesucht wird weiter nach oben, bis eine
     * Überschrift ihren eigenen Wert wieder liest. Die erste brauchbare zu nehmen und es dabei zu
     * belassen, hiesse hier dasselbe Risiko eingehen wie in derselben Zeile.</p>
     */
    private static AnchorRule ruleAbove(PdfText text, List<PdfText.Line> lines, int i,
                                        List<String> used, AnchorRule.Position where, int nth,
                                        double value, double epsilon, boolean bindCurrency) {
        for (int j = i - 1; j >= 0 && j >= i - AnchorRule.MAX_DISTANCE; j--) {
            String label = labelOf(lines.get(j).text());
            if (!isUsable(label, used, where)) {
                continue;
            }
            AnchorRule regel = AnchorRule.single(label, AnchorRule.Direction.LINE_BELOW,
                    bindCurrency ? AnchorRule.currencyOf(lines.get(i).text()) : "", where, nth);
            if (liestSichSelbst(regel, text, value, epsilon)) {
                return regel;
            }
        }
        return null;
    }

    /**
     * Die Spaltenüberschrift über einer Wertzeile — {@code null}, wenn keine taugt.
     *
     * <p>Gesucht wird bis zu {@link AnchorRule#MAX_DISTANCE} Zeilen nach oben, denn zwischen Überschrift und
     * Daten steht oft noch eine zweite Kopfzeile: Scalable Capital schreibt „Buchung Wertstellung Typ …
     * Gesamt", darunter „Wechselkurs" und erst dann die Zahlen. Eine Zeile weit zu schauen fände dort
     * nur „Wechselkurs" — und das ist die Überschrift einer anderen Spalte.</p>
     *
     * <p>Die gelernte Regel merkt sich den Abstand <b>nicht</b>: sie sucht beim Lesen die nächste Zeile
     * mit einem Wert. Fällt beim nächsten Beleg eine Zwischenzeile weg (kein Wechselkurs bei einem
     * Euro-Papier), stimmt sie weiterhin.</p>
     */
    private static String labelAbove(List<PdfText.Line> lines, int i, List<String> used,
                                     AnchorRule.Position where) {
        for (int j = i - 1; j >= 0 && j >= i - AnchorRule.MAX_DISTANCE; j--) {
            String label = labelOf(lines.get(j).text());
            if (isUsable(label, used, where)) {
                return label;
            }
        }
        return null;
    }

    /**
     * Wo der Wert in der Zeile steht: {@code {0 = von rechts | 1 = von links, Stelle ab 1}}, oder
     * {@code null}, wenn er dort nicht vorkommt.
     *
     * <p>Gesucht wird von aussen nach innen und höchstens bis zur dritten Stelle. Weiter zu zählen wäre
     * gefährlich: je tiefer, desto grösser die Aussicht, dass irgendeine Zahl der Zeile zufällig passt
     * und eine Regel entsteht, die beim nächsten Beleg etwas anderes meint.</p>
     */
    private static int[] stelleIn(String line, double value, double epsilon) {
        for (int nth = 1; nth <= MAX_STELLE; nth++) {
            for (int vonLinks = 0; vonLinks <= 1; vonLinks++) {
                AnchorRule.Position where = vonLinks == 1
                        ? AnchorRule.Position.FIRST : AnchorRule.Position.LAST;
                Double found = AnchorRule.numberAt(line, where, nth);
                if (found != null && Math.abs(found - value) <= epsilon) {
                    return new int[]{vonLinks, nth};
                }
            }
        }
        return null;
    }

    /** Der Wert ist die Summe der letzten Zahlen mehrerer Zeilen (aufgeteilte Steuer). */
    private static AnchorRule summedLines(PdfText text, double value, double epsilon,
                                          boolean bindCurrency, List<String> used) {
        List<String> labels = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        List<String> currencies = new ArrayList<>();
        // Über ein Set statt labels.contains(...): das lief je Zeile durch die ganze bisherige Liste und
        // machte allein das Sammeln quadratisch.
        java.util.Set<String> gesehen = new java.util.HashSet<>();
        for (PdfText.Line line : text.lines()) {
            Double last = AnchorRule.lastNumber(line.text());
            String label = labelOf(line.text());
            if (last != null && last != 0 && isUsable(label, used, AnchorRule.Position.LAST)
                    && gesehen.add(label)) {
                labels.add(label);
                values.add(last);
                currencies.add(bindCurrency ? AnchorRule.currencyOf(line.text()) : "");
            }
        }
        List<Integer> pick = subsetSummingTo(values, value, epsilon, MAX_SUMMANDS);
        if (pick == null) {
            return null;
        }
        List<String> anchors = new ArrayList<>();
        String currency = null;
        for (int idx : pick) {
            anchors.add(labels.get(idx));
            // Nur eine einheitliche Währung wird gelernt; stehen die Summanden in verschiedenen, wäre
            // die Summe ohnehin fragwürdig, und ohne Kennzeichen bleibt es beim bisherigen Verhalten.
            if (currency == null) {
                currency = currencies.get(idx);
            } else if (!currency.equals(currencies.get(idx))) {
                currency = "";
            }
        }
        return AnchorRule.summed(anchors, AnchorRule.Direction.SAME_LINE,
                currency == null ? "" : currency);
    }

    /**
     * Sucht bis zu {@code max} Zeilen, deren Werte zusammen {@code target} ergeben; null wenn keine.
     *
     * <p>Durchsucht wird <b>aufsteigend sortiert</b>, mit Schranken: Zu jeder Stelle steht fest, welche
     * Summe die verbleibenden Summanden mindestens und höchstens noch beitragen können (die kleinsten
     * bzw. größten des Restes, ablesbar an den Präfixsummen). Liegt der gesuchte Rest außerhalb, ist
     * dieser Ast erledigt — und weil die Werte aufsteigen, ist er es unterhalb der Untergrenze für alle
     * folgenden gleich mit, die Schleife bricht ab.</p>
     *
     * <p>Vorher wurden alle Zweier- und Dreierkombinationen ungefiltert aufgezählt: Θ(n³). Kommt der
     * gesuchte Betrag als Summe gar nicht vor — bei einem Sammelbeleg oder einer
     * Jahressteuerbescheinigung der Normalfall — lief der Suchraum vollständig durch, und die App stand.</p>
     */
    private static List<Integer> subsetSummingTo(List<Double> values, double target, double epsilon,
                                                 int max) {
        int n = values.size();
        if (n < 2) {
            return null;
        }
        // Aufsteigend sortieren, aber die ursprüngliche Stelle mitführen: gemeldet werden am Ende die
        // Zeilennummern des Belegs, nicht die der sortierten Hilfsliste.
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Double.compare(values.get(a), values.get(b)));
        double[] sorted = new double[n];
        for (int i = 0; i < n; i++) {
            sorted[i] = values.get(order[i]);
        }
        // prefix[k] ist die Summe der k kleinsten Werte. Damit steht jede Schranke in einer Subtraktion.
        double[] prefix = new double[n + 1];
        for (int i = 0; i < n; i++) {
            prefix[i + 1] = prefix[i] + sorted[i];
        }

        for (int size = 2; size <= max && size <= n; size++) {
            List<Integer> found = search(sorted, prefix, target, epsilon, size, 0,
                    new ArrayList<Integer>());
            if (found != null) {
                List<Integer> result = new ArrayList<>();
                for (int i : found) {
                    result.add(order[i]);
                }
                java.util.Collections.sort(result);
                return result;
            }
        }
        return null;
    }

    /**
     * Ein Ast der Summensuche: {@code left} weitere Summanden ab Stelle {@code from}, die zusammen
     * {@code remaining} ergeben sollen.
     *
     * <p>Die beiden Schranken tragen die Arbeit. Was die restlichen Summanden mindestens beitragen, sind
     * die kleinsten des Restes; was sie höchstens beitragen, die grössten. Bleibt zu wenig übrig, ist der
     * Ast erledigt — und weil die Werte aufsteigen, gilt das für alle folgenden gleich mit, die Schleife
     * bricht ab statt weiterzuzählen.</p>
     */
    private static List<Integer> search(double[] sorted, double[] prefix, double remaining,
                                        double epsilon, int left, int from, List<Integer> chosen) {
        int n = sorted.length;
        if (left == 0) {
            return Math.abs(remaining) <= epsilon ? new ArrayList<>(chosen) : null;
        }
        for (int i = from; i <= n - left; i++) {
            double rest = remaining - sorted[i];
            int weitere = left - 1;
            double minRest = weitere == 0 ? 0 : prefix[i + 1 + weitere] - prefix[i + 1];
            double maxRest = weitere == 0 ? 0 : prefix[n] - prefix[n - weitere];
            if (rest - epsilon > maxRest) {
                continue;   // dieser Summand ist zu klein; ein grösserer kommt noch
            }
            if (rest + epsilon < minRest) {
                break;      // zu gross — und alle folgenden sind noch grösser
            }
            chosen.add(i);
            List<Integer> found = search(sorted, prefix, rest, epsilon, weitere, i + 1, chosen);
            if (found != null) {
                return found;
            }
            chosen.remove(chosen.size() - 1);
        }
        return null;
    }

    /** Das gebuchte Datum: die Zeile suchen, in der genau dieses Datum steht. */
    private static AnchorRule forDate(PdfText text, long dateMillis, String chosen, List<String> used) {
        if (dateMillis <= 0) {
            return null;
        }
        String anchor = null;
        AnchorRule.Direction direction = AnchorRule.Direction.SAME_LINE;
        AnchorRule.Position position = AnchorRule.Position.LAST;
        int nthFound = 1;
        // Stand das gefundene Datum in einer Tabellenzeile? Dann gilt die Spalte vor der Stelle.
        boolean tabelle = false;
        List<PdfText.Line> lines = text.lines();
        for (int i = 0; i < lines.size(); i++) {
            // Irgendwo in der Zeile, nicht nur als erste Angabe: eine Zeile trägt oft mehrere Daten
            // („Schlusstag/-Zeit 25.11.2015 11:02:54 Zinstermin Monat(e) 27. Juni"), und gebucht gehört
            // nicht zwangsläufig das erste.
            int[] stelle = datumsStelle(lines.get(i).text(), dateMillis);
            if (stelle == null) {
                continue;
            }
            AnchorRule.Position where = stelle[0] == 1
                    ? AnchorRule.Position.FIRST : AnchorRule.Position.LAST;
            String label = firstUsable(used, where,
                    labelBeforeDate(lines.get(i).text(), dateMillis), labelOf(lines.get(i).text()));
            if (label != null) {
                if (chosen != null && chosen.equalsIgnoreCase(label)) {
                    // Seine Wahl gewinnt – und mit ihr die Stelle, an der das Datum dort steht.
                    return AnchorRule.single(label, AnchorRule.Direction.SAME_LINE, "",
                            where, stelle[1]);
                }
                anchor = label;
                direction = AnchorRule.Direction.SAME_LINE;
                position = where;
                nthFound = stelle[1];
                // Der Merker gehört zum zuletzt gefundenen Datum und muss hier mit zurückgesetzt
                // werden. Sonst blieb er von einer früheren Fundstelle stehen, und die Spaltenregel
                // gewann am Ende gegen die eigene Beschriftung, die hier gerade gefunden wurde.
                tabelle = false;
                continue;
            }
            // Keine eigene Beschriftung – dann trägt sie die Spaltenüberschrift darüber. So steht das
            // Datum bei Scalable Capital: die Zeile beginnt mit ihm und hat gar keine.
            String above = labelAbove(lines, i, used, where);
            if (above != null) {
                anchor = above;
                direction = AnchorRule.Direction.LINE_BELOW;
                position = where;
                nthFound = stelle[1];
                // Zwei Daten nebeneinander unter einer Überschrift: das ist eine Tabellenzeile, und dort
                // trifft die Spalte auch dann noch, wenn eine Angabe einmal fehlt. Auch das braucht
                // echte Koordinaten – siehe die Weiche in forValue.
                tabelle = text.hasWordPositions() && istTabellenzeile(lines.get(i));
            }
        }
        if (tabelle) {
            AnchorRule spalte = columnDate(text, dateMillis, used);
            if (spalte != null) {
                return spalte;
            }
        }
        if (anchor != null) {
            return AnchorRule.single(anchor, direction, "", position, nthFound);
        }
        // Wie bei den Zahlen zuletzt die Spalte: „Buchung Wertstellung Typ …" als Überschrift und
        // darunter zwei Daten nebeneinander – abzählen träfe dort das falsche, sobald eine Spalte fehlt.
        return columnDate(text, dateMillis, used);
    }

    /** Das gebuchte Datum steht in der Spalte einer Überschrift darüber. Wie {@link #columnLine}. */
    private static AnchorRule columnDate(PdfText text, long dateMillis, List<String> used) {
        List<PdfText.Line> lines = text.lines();
        AnchorRule found = null;
        for (int i = 0; i < lines.size(); i++) {
            for (PdfText.Word word : lines.get(i).words) {
                if (TextValues.toUnambiguousDateMillis(word.text) != dateMillis) {
                    continue;
                }
                AnchorRule probe = columnRuleFor(text, i, word, used, "", null,
                        millis -> millis == dateMillis);
                if (probe != null) {
                    found = probe;
                }
            }
        }
        return found;
    }

    /**
     * Das wievielte Datum der Zeile das gesuchte ist: {@code {0 = von rechts | 1 = von links, Stelle}}.
     *
     * <p>Gezählt wird <b>immer von links</b>. Anders als bei den Zahlen ist das keine Geschmacksfrage:
     * die Vorgabe „von rechts, erste" bedeutet beim Datum aus Rücksicht auf den Bestand etwas anderes
     * ({@link AnchorRule#dateAt}), und eine gelernte Regel soll nicht in diese Zweideutigkeit geraten.
     * „30.06.2026 01.07.2026 Gutschrift …" ergibt für die Wertstellung also die zweite von links.</p>
     */
    private static int[] datumsStelle(String line, long dateMillis) {
        List<Long> alle = AnchorRule.allDates(line);
        int vonLinks = alle.indexOf(dateMillis);
        return vonLinks < 0 ? null : new int[]{1, vonLinks + 1};
    }

    /** Die erste brauchbare unter mehreren Beschriftungen; {@code null}, wenn keine taugt. */
    private static String firstUsable(List<String> used, AnchorRule.Position where,
                                      String... candidates) {
        for (String candidate : candidates) {
            if (isUsable(candidate, used, where)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Die Beschriftung <b>unmittelbar vor</b> dem Wert: die Wörter davor, zurück bis zur nächsten Zahl.
     *
     * <p>Der Unterschied zu {@link #labelOf} zeigt sich erst, wenn vor dem Wert schon eine Zahl steht:
     * „Beispielstraße 1 DATUM 13.05.2019" — die Beschriftung ist „DATUM", nicht „Beispielstraße". Steht
     * keine Zahl davor, kommt dasselbe heraus wie bisher, und die gelernten Vorlagen sehen aus wie
     * gewohnt.</p>
     */
    static String labelBefore(String line, AnchorRule.Position where) {
        if (line == null) {
            return "";
        }
        String[] tokens = line.trim().split("\\s+");
        int value = numberIndex(tokens, where);
        return value <= 0 ? labelOf(line) : wordsBefore(tokens, value);
    }

    /** Dasselbe für ein Datum: die Wörter vor der Datumsangabe. */
    static String labelBeforeDate(String line, long dateMillis) {
        if (line == null) {
            return "";
        }
        String[] tokens = line.trim().split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            if (TextValues.toUnambiguousDateMillis(tokens[i]) == dateMillis) {
                return i == 0 ? "" : wordsBefore(tokens, i);
            }
        }
        return labelOf(line);
    }

    /** Index der ersten bzw. letzten Zahl unter den Wörtern; -1, wenn keine dabei ist. */
    private static int numberIndex(String[] tokens, AnchorRule.Position where) {
        int found = -1;
        for (int i = 0; i < tokens.length; i++) {
            if (TextValues.toDecimal(tokens[i]) == null) {
                continue;
            }
            if (where == AnchorRule.Position.FIRST) {
                return i;
            }
            found = i;
        }
        return found;
    }

    /** Die Wörter vor {@code index}, zurück bis zum nächsten mit einer Ziffer; ohne Währungskürzel. */
    private static String wordsBefore(String[] tokens, int index) {
        List<String> words = new ArrayList<>();
        for (int i = index - 1; i >= 0; i--) {
            if (hasDigit(tokens[i])) {
                break;
            }
            words.add(0, tokens[i]);
        }
        while (!words.isEmpty() && isCurrencyCode(words.get(words.size() - 1))) {
            words.remove(words.size() - 1);
        }
        StringBuilder label = new StringBuilder();
        for (String w : words) {
            if (label.length() > 0) {
                label.append(' ');
            }
            label.append(w);
        }
        return label.toString();
    }

    /**
     * Die Beschriftung einer Zeile: alle Wörter bis zum ersten, das eine Ziffer enthält.
     *
     * <p>Nicht „bis zur ersten Zahl": „Kapitalertragsteuer 25,00% EUR 158,73" — der Steuersatz ist keine
     * lesbare Zahl (Prozentzeichen), stünde aber im Anker und änderte sich mit dem Satz. Ein
     * abschließendes Währungskürzel fällt ebenfalls weg, damit derselbe Anker auch greift, wenn eine
     * Abrechnung einmal in einer anderen Währung kommt.</p>
     */
    static String labelOf(String line) {
        if (line == null) {
            return "";
        }
        StringBuilder label = new StringBuilder();
        List<String> words = new ArrayList<>();
        for (String token : line.split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            if (hasDigit(token)) {
                break;
            }
            words.add(token);
        }
        while (!words.isEmpty() && isCurrencyCode(words.get(words.size() - 1))) {
            words.remove(words.size() - 1);
        }
        for (String w : words) {
            if (label.length() > 0) {
                label.append(' ');
            }
            label.append(w);
        }
        return label.toString();
    }

    private static boolean hasDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ob ein Wort ein Währungskürzel ist. Geprüft wird gegen die vergebenen Kürzel und nicht mehr nur
     * auf „drei Großbuchstaben": sonst fiel jedes dreibuchstabige Kürzel am Ende einer Beschriftung weg
     * — bei einem Wertpapier-Ticker (etwa {@code SAP} oder {@code BMW}) genau das Wort, das die Zeile
     * unterscheidbar macht.
     */
    private static boolean isCurrencyCode(String s) {
        return de.spahr.ausgaben.util.Currencies.isCode(s);
    }

    /**
     * Ob eine Beschriftung als Anker taugt: nicht leer, nicht zu kurz (ein einzelner Buchstabe träfe zu
     * viel) und noch nicht für ein anderes Feld vergeben — sonst läsen zwei Felder dieselbe Zahl.
     */
    private static boolean isUsable(String label, List<String> used, AnchorRule.Position position) {
        if (label == null || label.trim().length() < 3 || used.contains(taken(label, position))) {
            return false;
        }
        // Ein bloßes Währungskürzel taugt nicht: „EUR 2.000,00 8,75 % METALCORP …" führt die Zeile mit
        // „EUR" an, und darauf schlüge die Regel bei jeder anderen Zeile derselben Abrechnung auch an.
        return !isCurrencyCode(label.trim());
    }
}
