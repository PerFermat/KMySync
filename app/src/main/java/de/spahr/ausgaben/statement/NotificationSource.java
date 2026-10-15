package de.spahr.ausgaben.statement;

import de.spahr.ausgaben.pdf.PdfText;

/**
 * Eine Benachrichtigung als {@link TextSource}: der Titel ist die erste Zeile, der Text folgt darunter.
 *
 * <p>Die Trennung in Zeilen ist kein Zufall. Der Titel ist bei vielen Apps das einzig Feste an der
 * Meldung („Kartenzahlung"), und als eigene Zeile lässt er sich wie eine Spaltenüberschrift ansprechen
 * ({@link AnchorRule.Direction#LINE_BELOW}).</p>
 */
public final class NotificationSource implements TextSource {

    private final String packageName;
    private final long postTime;
    private final PdfText text;

    public NotificationSource(String packageName, long postTime, String title, String body) {
        this(packageName, postTime, title, body, MoneyStyle.STRICT);
    }

    /**
     * @param style das Zahlenformat des Profils: ganzzahlige Beträge („1.234 €") werden in die
     *              eindeutige Form gebracht, bevor die Erkennung sie liest
     */
    public NotificationSource(String packageName, long postTime, String title, String body,
                              MoneyStyle style) {
        this.packageName = packageName == null ? "" : packageName;
        this.postTime = postTime;
        this.text = PdfText.fromLines(style.canonical(normalize(title)) + "\n"
                + style.canonical(normalize(body)));
    }

    @Override
    public PdfText text() {
        return text;
    }

    @Override
    public String sender() {
        return packageName;
    }

    @Override
    public long timeMillis() {
        return postTime;
    }

    /**
     * Macht aus Fließtext Wörter, wie die Erkennung sie erwartet: durch einfache Leerzeichen getrennt
     * und ohne Satzzeichen am Rand.
     *
     * <p>Nötig, weil {@link AnchorRule} nur das Leerzeichen als Wortgrenze kennt. In einer Abrechnung
     * stört das nicht, dort stehen Beschriftung und Zahl in Spalten. Ein Satz dagegen endet mit
     * „12,34 EUR." — und mit dem Punkt am Kürzel ist es kein Währungskürzel mehr, die Regel fände den
     * Betrag nicht. Ebenso „bei REWE, Köln": das Komma gehörte sonst zum Namen.</p>
     *
     * <p>Zeilenumbrüche bleiben; sie tragen die Gliederung der Meldung.</p>
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String line : raw.replace("\r", "").split("\n", -1)) {
            StringBuilder row = new StringBuilder();
            // Geschütztes und schmales Leerzeichen: Apps setzen sie zwischen Zahl und Währung.
            for (String token : line.replace(' ', ' ').replace(' ', ' ')
                    .replace(' ', ' ').replace('\t', ' ').split(" ")) {
                String word = strip(token);
                if (word.isEmpty()) {
                    continue;
                }
                if (row.length() > 0) {
                    row.append(' ');
                }
                row.append(word);
            }
            if (row.length() == 0) {
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(row);
        }
        return out.toString();
    }

    /** Das Wort ohne Satz- und Anführungszeichen am Rand. Klammern bleiben: „(12,34)" heißt negativ. */
    private static String strip(String token) {
        int start = 0;
        int end = token.length();
        while (start < end && isQuote(token.charAt(start))) {
            start++;
        }
        while (end > start && (isQuote(token.charAt(end - 1)) || isSentenceMark(token.charAt(end - 1)))) {
            end--;
        }
        return token.substring(start, end);
    }

    private static boolean isSentenceMark(char c) {
        return c == '.' || c == ',' || c == ';' || c == ':' || c == '!' || c == '?';
    }

    private static boolean isQuote(char c) {
        return c == '"' || c == '\'' || c == '„' || c == '“' || c == '”'
                || c == '‚' || c == '‘' || c == '’' || c == '«' || c == '»';
    }
}
