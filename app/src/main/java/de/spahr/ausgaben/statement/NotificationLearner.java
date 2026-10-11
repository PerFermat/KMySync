package de.spahr.ausgaben.statement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.spahr.ausgaben.pdf.PdfText;
import de.spahr.ausgaben.util.PayeeMatch;

/**
 * Lernt aus <b>einer</b> Benachrichtigung und der Buchung, die der Nutzer dazu von Hand angelegt hat,
 * eine {@link NotificationTemplate}.
 *
 * <p>Die Arbeit macht {@link TemplateLearner} — hier wird nur zusammengesetzt und gegengeprüft. Wie
 * dort gilt: Eine Vorlage entsteht nur, wenn sie auf ihrer eigenen Meldung wieder genau das liest,
 * was gebucht wurde. Sonst gibt es keine.</p>
 */
public final class NotificationLearner {

    /** Eine verdeckt geschriebene Kartennummer: „*1234", „xxxx1234", „…1234", auch in Klammern. */
    private static final Pattern MASKED = Pattern.compile("^\\(?[*xX•·…#.]+\\d{4}\\)?$");

    /** Wörter, hinter denen vier Ziffern die Kartenendung sind. */
    private static final List<String> CARD_WORDS = Arrays.asList("karte", "kreditkarte",
            "kartennummer", "endziffer", "endziffern", "card", "ending", "tarjeta");

    private NotificationLearner() {
    }

    /**
     * @param cents  der gebuchte Betrag, positiv
     * @param payee  der gebuchte Empfänger; darf leer sein
     * @return die Vorlage, oder {@code null}, wenn der Betrag in der Meldung nicht zu verankern ist
     */
    public static NotificationTemplate learn(TextSource source, long cents, boolean isIncome,
                                             String payee, String account) {
        if (source == null || cents <= 0) {
            return null;
        }
        PdfText text = source.text();
        double value = cents / 100.0;
        TextRule payeeRule = TemplateLearner.textregel(text, payee);
        // Was als Empfänger in der Meldung steht, darf in keinem Betragsanker vorkommen – sonst träfe
        // die Vorlage nur noch diesen einen Händler.
        String meiden = payeeRule != null ? payeeRule.read(text) : payee;
        List<AnchorRule> wege = new ArrayList<>(
                TemplateLearner.kandidatenImFliesstext(text, value, meiden));
        for (AnchorRule weg : TemplateLearner.kandidatenFuerBetrag(text, value)) {
            if (!nennt(weg, meiden) && !wege.contains(weg)) {
                wege.add(weg);
            }
        }
        String discriminator = discriminator(text);
        for (AnchorRule weg : wege) {
            NotificationTemplate t = new NotificationTemplate(source.sender(), discriminator, account,
                    isIncome, weg, payeeRule);
            NotificationTemplate.Hit hit = t.apply(source);
            if (hit != null && hit.cents == cents) {
                return t;
            }
        }
        return null;
    }

    /** Ob einer der Anker ein Wort des Empfängers enthält. */
    private static boolean nennt(AnchorRule rule, String payee) {
        if (payee == null) {
            return false;
        }
        List<String> name = Arrays.asList(PayeeMatch.normalizeTokens(payee));
        for (String anchor : rule.anchors) {
            for (String word : PayeeMatch.normalizeTokens(anchor)) {
                if (name.contains(word)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Das Unterscheidungsmerkmal der Meldung — die Kartenendung, wenn eine dasteht; sonst {@code ""}.
     *
     * <p>Gebraucht, wenn eine App für zwei Karten meldet: der Paketname ist derselbe, das Konto nicht.
     * Zurück kommt das Wort so, wie es dasteht, weil genau danach später gesucht wird.</p>
     */
    static String discriminator(PdfText text) {
        for (PdfText.Line line : text.lines()) {
            String previous = "";
            for (PdfText.Word word : line.words) {
                Matcher m = MASKED.matcher(word.text);
                if (m.matches()) {
                    return word.text;
                }
                if (word.text.matches("\\d{4}") && CARD_WORDS.contains(previous)) {
                    return word.text;
                }
                previous = word.text.toLowerCase(Locale.ROOT);
            }
        }
        return "";
    }
}
