package de.spahr.ausgaben.statement;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.spahr.ausgaben.pdf.PdfText;
import de.spahr.ausgaben.util.TextValues;

/**
 * Wo in einer Abrechnung ein bestimmter Wert steht — beschrieben über die <b>Beschriftung</b> daneben,
 * nicht über eine Position auf der Seite.
 *
 * <p>Der Grund: Abrechnungen verschieben sich senkrecht. Der Wertpapiername bricht mal auf zwei Zeilen
 * um, „Stückzinsen" oder „Devisenkurs" erscheinen nur manchmal, bei Sparplänen rutscht der Block eine
 * Seite weiter. Eine gemerkte Koordinate zeigt dann ins Leere — und zwar still, sie liefert eine falsche
 * Zahl statt keiner. Die Beschriftung bleibt.</p>
 *
 * <p>Regeln entstehen nicht von Hand, sondern werden von {@link TemplateLearner} aus einer einmal selbst
 * erfassten Abrechnung abgeleitet. Im Code steht deshalb nichts über eine bestimmte Bank.</p>
 */
/*
 * Serializable, damit die Erfassungsmaske die gewählte Datumsregel über einen Konfigurationswechsel
 * hinweg im Bundle halten kann. Bewusst nicht Parcelable: das brächte einen Android-Bezug in eine
 * Klasse, die sonst reines Java ist und genau deshalb ohne Gerät prüfbar bleibt.
 */
public final class AnchorRule implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Welche Zahl der Zeile gemeint ist.
     *
     * <p>Der Regelfall ist die letzte: in einer Abrechnung steht rechts der Betrag. Bei Tabellen mit
     * Spaltenüberschrift ist es die erste — „Nominale Wertpapierbezeichnung ISIN (WKN)" und darunter
     * „EUR 2.000,00 8,75 % METALCORP GROUP B.V. DE000A1HLTD2"; dort führt die Stückzahl die Zeile an und
     * rechts stehen Kurs und Kennnummer. An 2354 fremden Abrechnungen gemessen kommt die Stückzahl in
     * 876 Fällen <b>nur</b> so vor.</p>
     */
    public enum Position {
        /** Die letzte Zahl der Zeile — der Regelfall. */
        LAST,
        /** Die erste Zahl der Zeile — Tabellenspalte unter einer Überschrift. */
        FIRST,
        /**
         * Die Zahl, die <b>waagerecht unter (oder über) der Beschriftung</b> steht — nicht abgezählt,
         * sondern über die Wortposition getroffen (siehe {@link #inColumn}).
         *
         * <p>Der Unterschied zu {@link #FIRST}/{@link #LAST} zeigt sich bei Tabellen: Abzählen stimmt
         * nur, solange jede Spalte genau eine Zahl breit ist und keine fehlt. Eine zweiwortige
         * Überschrift, ein Wertpapiername oder eine ausgelassene Spalte verschieben die Zählung — still,
         * mit einer Zahl aus der Nachbarspalte als Ergebnis. Die Position verschiebt sich nicht.</p>
         *
         * <p>{@link AnchorRule#nth} ist dann bedeutungslos, und mit {@link Direction#SAME_LINE} ergibt
         * die Angabe keinen Sinn: dort ist die Spalte der Beschriftung die Beschriftung selbst. Die
         * Regelseite lässt die Kombination nicht zu; im Modell liefert sie nichts.</p>
         */
        COLUMN
    }

    /** Wo der Wert relativ zur Beschriftung steht. Welche Richtung gilt, ermittelt der Lerner. */
    public enum Direction {
        /** In derselben Zeile wie die Beschriftung — der Regelfall. */
        SAME_LINE,
        /** In der Zeile darunter; manche Banken setzen den Wert unter die Überschrift. */
        LINE_BELOW,
        /**
         * In der Zeile darüber. Spiegelbild von {@link #LINE_BELOW}, für Belege, die erst die Zahl und
         * darunter erst die Beschriftung setzen — etwa eine Summenzeile, unter der „Gesamtbetrag" steht,
         * oder eine Tabelle, deren Spaltennamen unter den Daten stehen.
         */
        LINE_ABOVE
    }

    /**
     * Die Beschriftungen, an denen die Regel anschlägt — <b>der Reihe nach</b>: es gilt die erste, die im
     * Dokument eine Zeile mit Wert anführt.
     *
     * <p>Damit lassen sich Rückfälle angeben, und die braucht es: eine Bank druckt die Valuta-Zeile nicht,
     * wenn sie mit dem Zahltag zusammenfällt — {@code [Valuta, Zahltag, Ex-Tag]} liest dann beide Formen
     * derselben Abrechnung. Ebenso beim Brutto, das bei einem dollarnotierten Papier in der
     * Umrechnungszeile steht und bei einem euronotierten in der Bruttozeile.</p>
     *
     * <p>Beim Summieren ({@link #sum}) gilt das nicht: dort tragen <b>alle</b> Beschriftungen zusammen
     * das Ergebnis, weil Banken die Steuer auf mehrere Zeilen verteilen.</p>
     */
    public final List<String> anchors;
    public final Direction direction;
    /**
     * Ob über <b>alle</b> passenden Zeilen summiert wird. Nötig, weil Banken die Steuer aufteilen:
     * Kapitalertragsteuer und Solidaritätszuschlag stehen auf zwei Zeilen, die Kirchensteuer käme als
     * dritte dazu.
     */
    public final boolean sum;
    /**
     * Das Währungskennzeichen der Zeile, in der der Wert steht — leer, wenn die Zeile keines trägt
     * (Stückzahlen etwa haben keines).
     *
     * <p>Nötig, weil dieselbe Abrechnung Beträge in mehreren Währungen führt: die Ertragsgutschrift nennt
     * „Brutto USD 1.053,47" und rechnet zwei Zeilen tiefer auf „EUR 906,99" um. Ohne diese Prüfung läse
     * eine Regel den Dollarbetrag als Euro — still, und um ein Sechstel daneben.</p>
     */
    public final String currency;
    /**
     * Von welchem Ende der Zeile gezählt wird — oder {@link Position#COLUMN}, wenn gar nicht gezählt,
     * sondern die Spalte der Beschriftung getroffen wird. Der Lerner ermittelt es (siehe
     * {@link Position}).
     */
    public final Position position;
    /**
     * Die wievielte Zahl von diesem Ende gemeint ist, ab 1.
     *
     * <p>Gebraucht für Tabellenzeilen: „YOU BOUGHT XBI 78464A870 06/29/22 07/01/22 MARGIN 5 $74.33000"
     * nennt den Kurs als letzte und die Menge als <b>zweitletzte</b> Zahl. Ohne das wäre die Menge nicht
     * auszudrücken.</p>
     */
    public final int nth;
    /**
     * Wie viele Zeilen von der Beschriftung entfernt der Wert steht — bei {@link Direction#LINE_BELOW}
     * nach unten, bei {@link Direction#LINE_ABOVE} nach oben. Bei {@link Direction#SAME_LINE} ohne
     * Bedeutung.
     *
     * <p><b>0 heißt suchend:</b> die nächste Zeile in dieser Richtung, die überhaupt einen brauchbaren
     * Wert trägt, höchstens {@link #MAX_DISTANCE} weit. Das ist der Regelfall und der nachsichtige: bei
     * einer Spaltenüberschrift steht zwischen ihr und den Daten oft noch eine zweite Kopfzeile
     * („Betrag / Stk." und darunter „Wechselkurs"), und ob sie da ist, hängt vom einzelnen Beleg ab.</p>
     *
     * <p><b>1 bis 3</b> heißt genau so viele Zeilen weiter — für den Fall, dass die suchende Fassung an
     * einer Zwischenzeile hängenbleibt, die zufällig eine Zahl trägt.</p>
     */
    public final int lineDistance;

    /** So weit sucht die nachsichtige Fassung von {@link #lineDistance} — nach unten wie nach oben. */
    public static final int MAX_DISTANCE = 3;

    public AnchorRule(List<String> anchors, Direction direction, boolean sum) {
        this(anchors, direction, sum, "");
    }

    public AnchorRule(List<String> anchors, Direction direction, boolean sum, String currency) {
        this(anchors, direction, sum, currency, Position.LAST);
    }

    public AnchorRule(List<String> anchors, Direction direction, boolean sum, String currency,
                      Position position) {
        this(anchors, direction, sum, currency, position, 1);
    }

    public AnchorRule(List<String> anchors, Direction direction, boolean sum, String currency,
                      Position position, int nth) {
        this(anchors, direction, sum, currency, position, nth, 0);
    }

    public AnchorRule(List<String> anchors, Direction direction, boolean sum, String currency,
                      Position position, int nth, int lineDistance) {
        List<String> copy = new ArrayList<>();
        for (String a : anchors) {
            if (a != null && !a.trim().isEmpty()) {
                copy.add(a.trim());
            }
        }
        this.anchors = copy;
        this.direction = direction;
        this.sum = sum;
        this.currency = currency == null ? "" : currency.trim();
        this.position = position == null ? Position.LAST : position;
        this.nth = Math.max(1, nth);
        this.lineDistance = Math.max(0, Math.min(MAX_DISTANCE, lineDistance));
    }

    public static AnchorRule single(String anchor, Direction direction) {
        return single(anchor, direction, "");
    }

    public static AnchorRule single(String anchor, Direction direction, String currency) {
        return single(anchor, direction, currency, Position.LAST);
    }

    public static AnchorRule single(String anchor, Direction direction, String currency,
                                    Position position) {
        return single(anchor, direction, currency, position, 1);
    }

    public static AnchorRule single(String anchor, Direction direction, String currency,
                                    Position position, int nth) {
        return new AnchorRule(java.util.Collections.singletonList(anchor), direction, false, currency,
                position, nth);
    }

    public static AnchorRule summed(List<String> anchors, Direction direction, String currency) {
        return new AnchorRule(anchors, direction, true, currency);
    }

    /**
     * Der Wert dieser Regel, roh als Dezimalzahl; {@code null}, wenn keine Beschriftung anschlägt.
     *
     * <p>Passt eine Beschriftung auf mehrere Zeilen und wird nicht summiert, gilt die <b>unterste</b>.
     * In einer Abrechnung steht die Endsumme unten, und sie ist die Zahl, die auch mit Gebühren noch
     * stimmt — dieselbe Wahl trifft der Lerner.</p>
     */
    public Double read(PdfText text) {
        if (text == null || anchors.isEmpty()) {
            return null;
        }
        if (sum) {
            // Alle Beschriftungen zusammen: Kapitalertragsteuer und Solidaritätszuschlag ergeben die
            // Steuer erst gemeinsam. Fehlt eine der Zeilen, zählt eben nur die vorhandene.
            double total = 0;
            boolean found = false;
            for (String anchor : anchors) {
                Double part = valueOf(text, anchor);
                if (part != null) {
                    total += part;
                    found = true;
                }
            }
            return found ? total : null;
        }
        for (String anchor : anchors) {
            Double value = valueOf(text, anchor);
            if (value != null) {
                return value;   // die erste Beschriftung, die trägt
            }
        }
        return null;
    }

    /** Der Wert zu <b>einer</b> Beschriftung; passt sie auf mehrere Zeilen, gilt die unterste. */
    private Double valueOf(PdfText text, String anchor) {
        List<PdfText.Line> lines = text.lines();
        Double result = null;
        for (int i = 0; i < lines.size(); i++) {
            for (Target target : targets(lines, i, anchor)) {
                if (!currencyFits(target.text)) {
                    continue;   // andere Währung als gelernt – dann ist es nicht der gesuchte Betrag
                }
                Double value = position == Position.COLUMN
                        ? inColumn(lines.get(i), anchor, target.line)
                        : numberAt(target.text, position, nth);
                if (value != null) {
                    result = value;   // weiter suchen: die unterste Fundstelle gewinnt
                    break;            // je Fundstelle aber nur die erste Zeile mit einem Wert
                }
            }
        }
        return result;
    }

    /**
     * Die Textstücke, in denen der Wert zu suchen ist — leer, wenn die Beschriftung hier nicht anschlägt.
     * Der Aufrufer nimmt das <b>erste</b>, das einen Wert hergibt.
     *
     * <p>Steht die Beschriftung <b>mitten</b> in der Zeile, gilt nur, was dahinter kommt. Nötig, weil
     * nicht jede Bank die Beschriftung an den Anfang setzt: Trade Republic schreibt
     * „Beispielstraße 1 DATUM 13.05.2019", und ohne diese Einschränkung läse eine Regel für „DATUM" die
     * Hausnummer mit. Führt die Beschriftung die Zeile an — der Regelfall —, ist es wie bisher der
     * ganze Rest.</p>
     *
     * <p>Bei „darunter" ist es eine Zeile tiefer, bei „darüber" eine höher, bei einem festen
     * {@link #lineDistance} genau so viele — und ohne Angabe die nächsten {@link #MAX_DISTANCE} in dieser
     * Richtung, aus denen der Aufrufer die erste mit einem Wert nimmt. Letzteres macht
     * Spaltenüberschriften erreichbar, unter denen noch eine zweite Kopfzeile steht.</p>
     */
    private List<Target> targets(List<PdfText.Line> lines, int i, String anchor) {
        int after = afterAnchor(lines.get(i).text(), anchor);
        if (after < 0) {
            return java.util.Collections.emptyList();
        }
        if (direction == Direction.SAME_LINE) {
            return java.util.Collections.singletonList(
                    new Target(lines.get(i), lines.get(i).text().substring(after)));
        }
        int step = direction == Direction.LINE_ABOVE ? -1 : 1;
        if (lineDistance > 0) {
            int j = i + step * lineDistance;
            return j >= 0 && j < lines.size()
                    ? java.util.Collections.singletonList(new Target(lines.get(j), lines.get(j).text()))
                    : java.util.Collections.<Target>emptyList();
        }
        List<Target> out = new ArrayList<>();
        for (int k = 1; k <= MAX_DISTANCE; k++) {
            int j = i + step * k;
            if (j < 0 || j >= lines.size()) {
                break;
            }
            out.add(new Target(lines.get(j), lines.get(j).text()));
        }
        return out;
    }

    /**
     * Eine Zeile, in der zu suchen ist, samt dem Textausschnitt, der dabei gilt.
     *
     * <p>Beides wird gebraucht: die abzählenden Stellen arbeiten auf dem Text — bei
     * {@link Direction#SAME_LINE} nur auf dem <b>hinter</b> der Beschriftung —, {@link Position#COLUMN}
     * dagegen auf den Wörtern samt ihrer Position auf der Seite.</p>
     */
    private static final class Target {
        final PdfText.Line line;
        final String text;

        Target(PdfText.Line line, String text) {
            this.line = line;
            this.text = text;
        }
    }

    /**
     * Welche Beschriftung den Wert getragen hat; {@code null}, wenn keine trägt. Für die Probe auf der
     * Regelseite — beim Eintippen einer Kette will man sehen, welches Glied gegriffen hat. Beim Summieren
     * sind es alle, die etwas beigetragen haben.
     */
    public String matchedAnchor(PdfText text) {
        if (text == null) {
            return null;
        }
        StringBuilder summed = new StringBuilder();
        for (String anchor : anchors) {
            if (valueOf(text, anchor) == null && dateOf(text, anchor) <= 0) {
                continue;
            }
            if (!sum) {
                return anchor;
            }
            if (summed.length() > 0) {
                summed.append(" + ");
            }
            summed.append(anchor);
        }
        return summed.length() == 0 ? null : summed.toString();
    }

    /** Der Wert in Cent — für die Geldfelder; gerundet wie {@link TextValues#toCents}, nicht über double. */
    public Long readCents(PdfText text) {
        Double value = read(text);
        return value == null ? null : TextValues.centsOf(value);
    }

    /**
     * Das Datum der angeankerten Zeile; -1, wenn keines darin steht oder es mehrdeutig ist.
     *
     * <p>Eine Abrechnung trägt mehrere Datumsangaben — Briefdatum, Ausführungstag, Valuta, Ex-Tag,
     * Zahltag. Welches gebucht gehört, ist deshalb eine Ankerfrage wie jede andere und kein „erstes
     * Datum im Dokument".</p>
     */
    public long readDate(PdfText text) {
        if (text == null || anchors.isEmpty()) {
            return -1;
        }
        for (String anchor : anchors) {
            long millis = dateOf(text, anchor);
            if (millis > 0) {
                return millis;   // die erste Beschriftung, die trägt
            }
        }
        return -1;
    }

    /** Das Datum zu <b>einer</b> Beschriftung; passt sie auf mehrere Zeilen, gilt die unterste. */
    private long dateOf(PdfText text, String anchor) {
        List<PdfText.Line> lines = text.lines();
        long result = -1;
        for (int i = 0; i < lines.size(); i++) {
            for (Target target : targets(lines, i, anchor)) {
                long millis = position == Position.COLUMN
                        ? dateInColumn(lines.get(i), anchor, target.line)
                        : dateAt(target.text, position, nth);
                if (millis > 0) {
                    result = millis;   // unterste Fundstelle gewinnt, wie bei den Zahlen
                    break;             // je Fundstelle die erste Zeile mit einem Datum
                }
            }
        }
        return result;
    }

    /**
     * Das gesuchte Datum einer Zeile — dieselbe Stellenangabe wie bei den Zahlen.
     *
     * <p>Nötig für Tabellenzeilen: „30.06.2026 01.07.2026 Gutschrift …" nennt vorn den Buchungstag und
     * daneben die Wertstellung. Ohne die Stelle gewönne immer der erste, und die Wertstellung wäre nicht
     * auszudrücken — obwohl die Regelseite die Auswahl anbietet.</p>
     */
    static long dateAt(String line, Position position, int nth) {
        // Die Vorgabe LAST/1 heisst beim Datum weiterhin «das erste hinter der Beschriftung» und nicht
        // «das letzte». Das ist eine Rücksicht auf den Bestand: gelernte Regeln tragen diese Vorgabe,
        // ohne dass jemand sie gewählt hätte, und eine Zeile wie «Für 01.07.2025 - 30.06.2026» läse
        // sonst über Nacht das Enddatum statt des Anfangs. Wer die letzte Angabe will, sagt es über eine
        // Stelle grösser eins oder über «von links».
        if (position != Position.FIRST && nth <= 1) {
            return firstDate(line);
        }
        List<Long> found = allDates(line);
        if (found.isEmpty()) {
            return -1;
        }
        int index = position == Position.FIRST ? nth - 1 : found.size() - nth;
        return index >= 0 && index < found.size() ? found.get(index) : -1;
    }

    /** Alle eindeutigen Daten einer Zeile, von links nach rechts. */
    static List<Long> allDates(String line) {
        List<Long> out = new ArrayList<>();
        if (line == null) {
            return out;
        }
        String[] tokens = line.split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            long millis = dateStartingAt(tokens, i);
            if (millis > 0) {
                out.add(millis);
                // Ein mehrteiliges Datum verbraucht seine Wörter nicht: der nächste Anlauf beginnt beim
                // folgenden Wort und findet dort nichts mehr, weil ein Teilstück allein kein Datum ist.
            }
        }
        return out;
    }

    /**
     * Das erste eindeutige Datum einer Zeile; -1, wenn keines darin steht.
     *
     * <p>Auch über mehrere Wörter: englischsprachige Belege schreiben „30 Aug 2023" oder „Dec 5, 2019",
     * und das sind drei Wörter, kein eines. Geprüft werden deshalb ein, zwei und drei aufeinander
     * folgende — das längste zuerst, sonst gewönne bei „5 Dec 2019" die bloße 5.</p>
     */
    static long firstDate(String line) {
        if (line == null) {
            return -1;
        }
        String[] tokens = line.split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            long millis = dateStartingAt(tokens, i);
            if (millis > 0) {
                return millis;
            }
        }
        return -1;
    }

    /** Das Datum, das bei Wort {@code i} beginnt; -1, wenn dort keines steht. */
    private static long dateStartingAt(String[] tokens, int i) {
        for (int len = Math.min(3, tokens.length - i); len >= 1; len--) {
            long millis = dateStartingAt(tokens, i, len);
            if (millis > 0) {
                return millis;
            }
        }
        return -1;
    }

    /**
     * Wie viele Wörter das Datum <b>wirklich</b> belegt — die kürzeste Gruppe ab {@code i}, die dasselbe
     * Ergebnis liefert wie die {@code len} Wörter lange.
     *
     * <p>Nötig, weil das Einlesen überschüssigen Text stillschweigend übergeht: „30.06.2026 01.07.2026
     * Gutschrift" ergibt dasselbe Datum wie „30.06.2026" allein. Für die Spaltensuche ist das ein
     * Unterschied ums Ganze — die Gruppe belegte sonst die halbe Zeile und überschnitte jede Spalte.</p>
     */
    private static int shortest(String[] tokens, int i, int len, long millis) {
        int shortest = len;
        while (shortest > 1 && dateStartingAt(tokens, i, shortest - 1) == millis) {
            shortest--;
        }
        return shortest;
    }

    /** Das Datum aus genau {@code len} Wörtern ab {@code i}; -1, wenn die keines ergeben. */
    private static long dateStartingAt(String[] tokens, int i, int len) {
        if (i < 0 || len < 1 || i + len > tokens.length) {
            return -1;
        }
        StringBuilder joined = new StringBuilder();
        for (int k = 0; k < len; k++) {
            if (k > 0) {
                joined.append(' ');
            }
            joined.append(tokens[i + k]);
        }
        // Das Komma in „Dec 5, 2019" gehört zur Schreibweise, nicht zum Datum.
        return TextValues.toUnambiguousDateMillis(joined.toString().replace(",", ""));
    }

    /**
     * Als Ende eines Textfelds: der Text reicht bis zur ersten Zahl oder zum ersten Währungskennzeichen.
     * Ein Wort kann das nicht sein — {@link #readText} sucht Wörter, und „#" steht in keiner Meldung als
     * eigenes.
     */
    public static final String UNTIL_NUMBER = "#";

    /**
     * Der <b>Text</b> an der angeankerten Stelle — für Felder, die keine Zahl sind, also den Empfänger.
     * {@code null}, wenn keine Beschriftung anschlägt oder das Ende fehlt.
     *
     * <p>Gelesen wird wie bei den Zahlen: hinter der Beschriftung, in der Zeile darunter oder darüber,
     * die unterste Fundstelle gewinnt. {@link #position}, {@link #nth} und {@link #currency} haben hier
     * keine Bedeutung.</p>
     *
     * <p>{@code until} sagt, wo der Text aufhört: leer heißt am Zeilenende, {@link #UNTIL_NUMBER} vor
     * der ersten Zahl, sonst vor diesem Wort. Fehlt das Wort, gibt es <b>keinen</b> Text und nicht etwa
     * den Rest der Zeile: die Meldung ist dann anders gebaut als die, aus der gelernt wurde, und was
     * dahinter steht, ist nicht der Empfänger.</p>
     */
    public String readText(PdfText text, String until) {
        if (text == null) {
            return null;
        }
        for (String anchor : anchors) {
            String found = textOf(text, anchor, until == null ? "" : until.trim());
            if (found != null) {
                return found;   // die erste Beschriftung, die trägt
            }
        }
        return null;
    }

    /** Der Text zu <b>einer</b> Beschriftung; passt sie auf mehrere Zeilen, gilt die unterste. */
    private String textOf(PdfText text, String anchor, String until) {
        List<PdfText.Line> lines = text.lines();
        String result = null;
        for (int i = 0; i < lines.size(); i++) {
            for (Target target : targets(lines, i, anchor)) {
                String cut = cutAt(target.text.trim(), until);
                if (cut != null && !cut.isEmpty()) {
                    result = cut;
                    break;
                }
            }
        }
        return result;
    }

    /** Der Anfang von {@code line} bis vor {@code until}; {@code null}, wenn das Ende darin fehlt. */
    static String cutAt(String line, String until) {
        if (until.isEmpty()) {
            return line;
        }
        if (!UNTIL_NUMBER.equals(until)) {
            int end = afterAnchor(line, until);
            return end < 0 ? null : line.substring(0, end - until.length()).trim();
        }
        StringBuilder out = new StringBuilder();
        for (String token : line.split("\\s+")) {
            if (endsText(token)) {
                return out.toString();
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(token);
        }
        return null;   // keine Zahl in der Zeile – dann ist es nicht die gelernte Form
    }

    /** Ob das Wort eine Zahl oder ein Währungskennzeichen ist und damit ein Textfeld beendet. */
    static boolean endsText(String token) {
        return TextValues.toDecimal(token) != null || !currencyOf(token).isEmpty();
    }

    /**
     * Ob die Zeile die Beschriftung als eigenes Wort enthält.
     *
     * <p>Hieß bis 1.12 {@code startsWithAnchor} und trug den Kommentar „Ob die Zeile mit genau dieser
     * Beschriftung beginnt". Beides war falsch: {@link #afterAnchor} sucht an jeder <b>Wortgrenze</b>,
     * nicht nur am Zeilenanfang. Geändert ist der Name, nicht das Verhalten — und zwar mit Absicht:
     * {@link #hits} zählt ausdrücklich „nach demselben Kriterium, nach dem später gelesen wird", und
     * gelesen wird über {@code afterAnchor}. Auf den Zeilenanfang einzuschränken hieße, die
     * Vorlagenauswahl nach einem anderen Maßstab zu treffen als das Lesen danach — und würde jede
     * Beschriftung ausschließen, die in einer Tabellenzeile hinter anderem Text steht.</p>
     */
    private static boolean containsAnchor(String line, String anchor) {
        return afterAnchor(line, anchor) >= 0;
    }

    /**
     * Der Teil der Zeile hinter der Beschriftung, oder {@code null}. Für den Lerner: die Währung muss aus
     * demselben Text stammen, der beim Lesen geprüft wird, sonst lernt er eine, die nie wieder passt.
     */
    static String afterAnchorText(String line, String anchor) {
        int at = afterAnchor(line, anchor);
        return at < 0 ? null : line.substring(at);
    }

    /**
     * Die Stelle unmittelbar hinter der Beschriftung, oder -1. Gesucht wird an <b>Wortgrenzen</b>:
     * „Kurs" darf nicht auf „Kurswert" anschlagen, sonst läse ein Kauf den Kurswert als Kurs.
     *
     * <p>Verglichen wird mit {@link String#regionMatches(boolean, int, String, int, int)} auf der
     * <b>Originalzeile</b>. Vorher wurden dafür bei jedem Aufruf zwei kleingeschriebene Kopien angelegt —
     * in der innersten Schleife der Erkennung, die pro Zeile das ganze Dokument durchläuft. Und der
     * zurückgegebene Index galt streng genommen für die Kopie: {@code toLowerCase} ist nicht
     * längenerhaltend (das türkische „İ" wird zu zwei Zeichen), sodass {@link #afterAnchorText},
     * {@link #targets} und {@link #anchorSpan} damit an der falschen Stelle geschnitten hätten.</p>
     *
     * <p>Die Beschriftung wird getrimmt — dieselbe Länge, mit der {@link #anchorSpan} zurückrechnet.</p>
     */
    private static int afterAnchor(String line, String anchor) {
        if (line == null || anchor == null) {
            return -1;
        }
        String needle = anchor.trim();
        int n = needle.length();
        if (n == 0) {
            return -1;
        }
        for (int at = 0; at + n <= line.length(); at++) {
            if (at > 0 && line.charAt(at - 1) != ' ') {
                continue;   // keine Wortgrenze links
            }
            if (!line.regionMatches(true, at, needle, 0, n)) {
                continue;
            }
            int end = at + n;
            if (end == line.length() || line.charAt(end) == ' ') {
                return end;
            }
        }
        return -1;
    }

    /**
     * Wie viele der Beschriftungen dieser Regel im Dokument tatsächlich eine Zeile anführen.
     *
     * <p>Damit lässt sich messen, wie gut eine Vorlage zu einem Dokument passt, <b>ohne</b> Vollständigkeit
     * zu verlangen: eine Abrechnung, in der eine Zeile fehlt, ist immer noch dieselbe Abrechnung. Gezählt
     * wird nach demselben Kriterium, nach dem später gelesen wird — sonst könnte eine Vorlage „passen",
     * ohne dass eine einzige Regel etwas fände.</p>
     */
    public int hits(PdfText text) {
        if (text == null) {
            return 0;
        }
        int found = 0;
        for (String anchor : anchors) {
            for (PdfText.Line line : text.lines()) {
                if (containsAnchor(line.text(), anchor)) {
                    found++;
                    break;
                }
            }
        }
        return found;
    }

    /** Ob die Zeile das gelernte Währungskennzeichen trägt (ohne gelerntes: immer). */
    private boolean currencyFits(String line) {
        return currency.isEmpty() || currency.equals(currencyOf(line));
    }

    /**
     * Das Währungskennzeichen einer Zeile: ein alleinstehendes Wort aus drei Großbuchstaben oder ein
     * Währungssymbol. {@code ""}, wenn keines darin steht.
     */
    static String currencyOf(String line) {
        if (line == null) {
            return "";
        }
        for (String token : line.split("\\s+")) {
            if (token.equals("€") || token.equals("$")) {
                return token;
            }
            // Zeichenweise statt token.matches(...): das kompiliert bei jedem Aufruf ein Pattern, und
            // currencyFits ruft diese Methode in der innersten Schleife der Erkennung – je Zeile, je
            // Beschriftung, je Regel.
            if (waehrungskuerzel(token)) {
                return token;
            }
        }
        return "";
    }

    /**
     * Ob das Wort ein Währungskürzel ist. Bis 1.12 galt jedes Wort aus drei Großbuchstaben als eines,
     * und die Regel band sich dann an ein Kürzel, das gar keines war — beim nächsten Beleg passte sie
     * nicht mehr.
     */
    private static boolean waehrungskuerzel(String token) {
        return de.spahr.ausgaben.util.Currencies.isCode(token);
    }

    /**
     * Die Zahl der Zeile {@code valueLine}, die <b>waagerecht unter (oder über) der Beschriftung</b>
     * steht; {@code null}, wenn keine dafür in Frage kommt.
     *
     * <p>Gesucht wird nicht abgezählt, sondern über die Position der Wörter auf der Seite: die
     * Beschriftung belegt einen waagerechten Bereich, und gesucht ist die Zahl im selben Bereich. Das
     * hält auch dann, wenn eine Spalte in einem Beleg fehlt oder eine Überschrift aus zwei Wörtern
     * besteht — beides verschiebt jedes Abzählen.</p>
     *
     * <p>Es gewinnt die erste Zahl, deren Bereich den der Beschriftung <b>überschneidet</b>. Gibt es
     * keine solche, die mit dem geringsten Abstand der Mitten: Zahlenspalten sind rechtsbündig gesetzt,
     * Überschriften oft linksbündig, und dann überschneidet sich nichts, obwohl es dieselbe Spalte
     * ist.</p>
     *
     * <p>Währungskennzeichen und Prozentzeichen sind keine Kandidaten — sie stehen in Tabellen als
     * eigene Spalte oder direkt neben dem Wert und wären sonst der nächstliegende Treffer.</p>
     *
     * <p>Steht die Beschriftung in derselben Zeile wie der Wert, liefert die Angabe {@code null}: dort
     * ist die Spalte der Beschriftung die Beschriftung selbst. Die Regelseite lässt die Kombination
     * gar nicht erst zu.</p>
     */
    static Double inColumn(PdfText.Line labelLine, String anchor, PdfText.Line valueLine) {
        if (labelLine == null || valueLine == null || labelLine == valueLine) {
            return null;
        }
        float[] span = anchorSpan(labelLine, anchor);
        if (span == null) {
            return null;
        }
        PdfText.Word best = nearest(valueLine, span, true);
        if (best == null) {
            best = nearest(valueLine, span, false);
        }
        if (best == null) {
            return null;
        }
        Double value = TextValues.toDecimal(best.text);
        return value == null ? null : Math.abs(value);
    }

    /**
     * Das Datum in der Spalte der Beschriftung; -1, wenn keines dort steht. Sonst wie {@link #inColumn}.
     *
     * <p>Ein Datum kann über drei Wörter gehen („30 Aug 2023"); es belegt dann den Bereich vom ersten
     * bis zum letzten davon.</p>
     */
    static long dateInColumn(PdfText.Line labelLine, String anchor, PdfText.Line valueLine) {
        if (labelLine == null || valueLine == null || labelLine == valueLine) {
            return -1;
        }
        float[] span = anchorSpan(labelLine, anchor);
        if (span == null) {
            return -1;
        }
        List<PdfText.Word> words = valueLine.words;
        String[] tokens = new String[words.size()];
        for (int i = 0; i < words.size(); i++) {
            tokens[i] = words.get(i).text;
        }
        long best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < tokens.length; i++) {
            for (int len = Math.min(3, tokens.length - i); len >= 1; len--) {
                long millis = dateStartingAt(tokens, i, len);
                if (millis <= 0) {
                    continue;
                }
                float x = words.get(i).x;
                float endX = words.get(i + shortest(tokens, i, len, millis) - 1).endX;
                if (overlaps(x, endX, span[0], span[1])) {
                    return millis;
                }
                float distance = Math.abs(middle(x, endX) - middle(span[0], span[1]));
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = millis;
                }
                break;   // das längste Datum an dieser Stelle genügt
            }
        }
        return best;
    }

    /**
     * Der waagerechte Bereich, den die Beschriftung in ihrer Zeile belegt: {@code {x, endX}}, oder
     * {@code null}, wenn sie dort nicht steht.
     *
     * <p>{@link PdfText.Line#text()} fügt die Wörter mit genau einem Leerzeichen zusammen; aus der
     * Fundstelle im Text lässt sich deshalb ausrechnen, welche Wörter die Beschriftung ausmacht.</p>
     */
    static float[] anchorSpan(PdfText.Line line, String anchor) {
        int at = afterAnchor(line.text(), anchor);
        if (at < 0) {
            return null;
        }
        int end = at;                              // Zeichen hinter der Beschriftung
        int start = end - anchor.trim().length();  // ihr erstes Zeichen
        PdfText.Word first = null;
        PdfText.Word last = null;
        int pos = 0;
        for (PdfText.Word word : line.words) {
            int wordEnd = pos + word.text.length();
            if (wordEnd > start && pos < end) {
                if (first == null) {
                    first = word;
                }
                last = word;
            }
            pos = wordEnd + 1;   // das trennende Leerzeichen
        }
        return first == null ? null : new float[]{first.x, last.endX};
    }

    /**
     * Die Zahl mit der geringsten Abweichung zur Mitte der Beschriftung — wahlweise nur unter denen, die
     * ihren Bereich <b>überschneiden</b>.
     *
     * <p>Der Vorrang der Überschneidung ist die Absicherung für den Ausnahmefall: eine ungewöhnlich
     * breite Zahl kann ihre Spalte überdecken und trotzdem eine Mitte weit ab haben, während eine Zahl
     * aus der Nachbarspalte zufällig näher an der Mitte liegt. Unter mehreren überschneidenden gewinnt
     * die mittigste — nicht die erste: bei einer breiten Überschrift stünde die erste ganz links, und
     * die Spalte, die sie meint, weiter rechts.</p>
     */
    private static PdfText.Word nearest(PdfText.Line line, float[] span, boolean nurUeberlappende) {
        PdfText.Word best = null;
        float bestDistance = Float.MAX_VALUE;
        for (PdfText.Word word : line.words) {
            if (!isValue(word.text)) {
                continue;
            }
            if (nurUeberlappende && !overlaps(word.x, word.endX, span[0], span[1])) {
                continue;
            }
            float distance = Math.abs(middle(word.x, word.endX) - middle(span[0], span[1]));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = word;
            }
        }
        return best;
    }

    /** Ob sich zwei waagerechte Bereiche berühren oder überschneiden. */
    private static boolean overlaps(float aStart, float aEnd, float bStart, float bEnd) {
        return aStart <= bEnd && aEnd >= bStart;
    }

    private static float middle(float start, float end) {
        return (start + end) / 2f;
    }

    /**
     * Ob ein Wort als Wert einer Spalte in Frage kommt: es muss eine Zahl sein.
     *
     * <p>Damit fallen Währungskennzeichen und Prozentzeichen von selbst heraus — „EUR" und „%" stehen in
     * Tabellen als eigene Spalte oder direkt neben dem Wert und wären sonst der nächstliegende Treffer,
     * aber als Zahl lesen lässt sich keines von beiden. Ein ausdrücklicher Ausschluss dafür wäre toter
     * Code: er liess sich nicht zum Anschlagen bringen.</p>
     */
    private static boolean isValue(String token) {
        return token != null && !token.isEmpty() && TextValues.toDecimal(token) != null;
    }

    /** Die {@code nth}-te Zahl vom vorderen oder hinteren Ende der Zeile. */
    static Double numberAt(String line, Position position, int nth) {
        List<String> tokens = TextValues.numberTokens(line);
        int index = position == Position.FIRST ? nth - 1 : tokens.size() - nth;
        if (index < 0 || index >= tokens.size()) {
            return null;
        }
        Double value = TextValues.toDecimal(tokens.get(index));
        return value == null ? null : Math.abs(value);
    }

    /** Die erste Zahl einer Zeile, ohne Vorzeichen; {@code null}, wenn keine darin steht. */
    static Double firstNumber(String line) {
        return numberAt(line, Position.FIRST, 1);
    }

    /**
     * Die letzte Zahl einer Zeile, <b>ohne Vorzeichen</b>; {@code null}, wenn keine darin steht.
     *
     * <p>Das Vorzeichen fällt weg, weil es zur Buchhaltung der Bank gehört und nicht zum Feld: „Kurswert:
     * -1.100,-- EUR" nennt denselben Kurswert wie „Kurswert 1.100,00 EUR", das Minus sagt nur, dass es
     * eine Belastung ist. Was ein Wert bedeutet, sagt hier seine Beschriftung.</p>
     *
     * <p>Ohne das ginge auch die Rechnung schief: bei einer Dividende ist {@code Netto = Brutto − Steuer},
     * und mit einer negativ gelesenen Steuer käme aus 144,52 und 36,73 nicht 181,25 heraus, sondern
     * 107,79. Ebenso beim Lernen — der Nutzer tippt 1.100 ein und muss die Zeile wiederfinden.</p>
     */
    static Double lastNumber(String line) {
        return numberAt(line, Position.LAST, 1);
    }

    /**
     * Zwei Regeln sind gleich, wenn sie dieselben Beschriftungen in derselben Richtung lesen. Gebraucht,
     * um zu erkennen, ob ein Lernvorgang überhaupt etwas Neues ergeben hat.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AnchorRule)) {
            return false;
        }
        AnchorRule other = (AnchorRule) o;
        return sum == other.sum && direction == other.direction && anchors.equals(other.anchors)
                && currency.equals(other.currency) && position == other.position && nth == other.nth
                && lineDistance == other.lineDistance;
    }

    @Override
    public int hashCode() {
        // Jedes Feld bekommt seine eigene Runde: Bis 1.12 wurden lineDistance und sum am Ende nur
        // addiert, weshalb {lineDistance 1, sum false} und {lineDistance 0, sum true} denselben Wert
        // ergaben — obwohl equals sie unterscheidet.
        int h = ((((anchors.hashCode() * 31 + direction.hashCode()) * 31 + currency.hashCode()) * 31
                + position.hashCode()) * 31 + nth) * 31 + lineDistance;
        return h * 31 + (sum ? 1 : 0);
    }

    @Override
    public String toString() {
        return (sum ? "Summe von " : "") + anchors + " (" + direction
                + (lineDistance > 0 ? " " + lineDistance : "")
                + (position == Position.COLUMN ? ", in der Spalte der Beschriftung"
                        : position == Position.FIRST || nth > 1 ? ", " + nth + ". Zahl von "
                                + (position == Position.FIRST ? "links" : "rechts") : "")
                + (currency.isEmpty() ? "" : ", " + currency) + ")";
    }
}
