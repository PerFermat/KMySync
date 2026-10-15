package de.spahr.ausgaben.statement;

import java.util.Locale;
import java.util.regex.Pattern;

import de.spahr.ausgaben.util.Currencies;

/**
 * Wie ein Profil Beträge schreibt — genauer: ob und womit es Tausender gruppiert. Gebraucht für Beträge
 * <b>ohne</b> Nachkommastellen in Benachrichtigungen: „Zahlung über 12 €", „1.234 € abgebucht".
 *
 * <p>Mit Nachkommastellen ist ein Betrag eindeutig, das rechte Trennzeichen ist das Dezimalzeichen.
 * Ohne sie ist er es nicht: „1.234" heißt nach deutscher Schreibweise tausendzweihundertvierunddreißig,
 * nach englischer eins Komma zwei drei vier. Das entscheidet hier das Zahlenformat, das der Nutzer für
 * sein Profil eingestellt hat ({@code SettingsStore.NUMBER_FORMAT_*}): „1.234,56" gruppiert mit dem
 * Punkt, „1,234.56" mit dem Komma, die beiden Formate ohne Gruppierung gar nicht.</p>
 *
 * <p>Ganzzahlen zählen nur mit einem Währungszeichen daran oder daneben — „Termin 12 Uhr" und
 * „PIN 1234" sind keine Beträge. {@link #canonical} schreibt solche Beträge in die eindeutige Form
 * „1234,00" um; danach lesen Betragsfilter und Erkennung sie wie jeden anderen. Der Wortlaut einer
 * Meldung, wie er in die Notiz kommt, bleibt davon unberührt.</p>
 *
 * <p>{@link #STRICT} lässt Ganzzahlen gar nicht zu. Das gilt überall dort, wo kein Profil feststeht oder
 * die Schwelle bewusst hoch bleibt: bei der Frage, ob sich eine noch nicht freigegebene App überhaupt
 * zur Auswahl anbietet — sonst täte das jede, die „Noch 3 € Guthaben" meldet.</p>
 */
public final class MoneyStyle {

    /** Keine Ganzzahlen: nur Beträge mit Nachkommastellen, wie bis 2.2. */
    public static final MoneyStyle STRICT = new MoneyStyle(false, '\0');

    /** Höchstens so viele Ziffern hat ein ganzzahliger Betrag; längeres sind Nummern. */
    static final int MAX_DIGITS = 7;

    private static final Pattern PLAIN = Pattern.compile("\\d{1," + MAX_DIGITS + "}");

    private final boolean integers;
    /** Das Tausenderzeichen; {@code '\0'}, wenn das Format nicht gruppiert. */
    private final char grouping;
    private final Pattern grouped;

    private MoneyStyle(boolean integers, char grouping) {
        this.integers = integers;
        this.grouping = grouping;
        this.grouped = grouping == '\0' ? null
                : Pattern.compile("\\d{1,3}(?:" + Pattern.quote(String.valueOf(grouping)) + "\\d{3})+");
    }

    /**
     * Zum Zahlenformat eines Profils ({@code de_group}, {@code en_group}, {@code plain_comma},
     * {@code plain_dot}). Unbekanntes oder Leeres gilt wie die Vorgabe der App, ohne Gruppierung.
     */
    public static MoneyStyle forNumberFormat(String format) {
        if ("de_group".equals(format)) {
            return new MoneyStyle(true, '.');
        }
        if ("en_group".equals(format)) {
            return new MoneyStyle(true, ',');
        }
        return new MoneyStyle(true, '\0');
    }

    /**
     * Der Text mit ganzzahligen Beträgen in eindeutiger Form: „1.234 €" → „1234,00 €", „€12" →
     * „€12,00". Erwartet Text, wie {@link NotificationSource#normalize} ihn liefert (Wörter durch ein
     * Leerzeichen getrennt). Alles andere bleibt Zeichen für Zeichen, wie es ist.
     */
    public String canonical(String text) {
        if (!integers || text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        String[] lines = text.split("\n", -1);
        for (int l = 0; l < lines.length; l++) {
            if (l > 0) {
                out.append('\n');
            }
            String[] tokens = lines[l].split(" ", -1);
            for (int i = 0; i < tokens.length; i++) {
                if (i > 0) {
                    out.append(' ');
                }
                boolean neighbour = (i > 0 && isCurrency(bare(tokens[i - 1])))
                        || (i + 1 < tokens.length && isCurrency(bare(tokens[i + 1])));
                out.append(rewrite(tokens[i], neighbour));
            }
        }
        return out.toString();
    }

    /** Ein Wort, umgeschrieben, wenn es ein ganzzahliger Betrag ist; sonst unverändert. */
    private String rewrite(String token, boolean currencyNeighbour) {
        // Eine einzelne Klammer am Rand gehört nicht zum Betrag: „(12 USD)".
        String open = token.startsWith("(") && !token.endsWith(")") ? "(" : "";
        String close = token.endsWith(")") && !token.startsWith("(") ? ")" : "";
        String t = token.substring(open.length(), token.length() - close.length());
        String prefix = currencyPrefix(t);
        String rest = t.substring(prefix.length());
        String suffix = currencySuffix(rest);
        String core = rest.substring(0, rest.length() - suffix.length());
        String sign = "";
        if (core.startsWith("-") || core.startsWith("+")) {
            sign = core.substring(0, 1);
            core = core.substring(1);
        }
        if (prefix.isEmpty() && suffix.isEmpty() && !currencyNeighbour) {
            return token;
        }
        String digits = digitsOf(core);
        if (digits == null) {
            return token;
        }
        return open + prefix + sign + digits + ",00" + suffix + close;
    }

    /** Die Ziffern einer Ganzzahl in diesem Format, ohne Gruppierung; {@code null}, wenn keine. */
    String digitsOf(String core) {
        if (PLAIN.matcher(core).matches()) {
            return core;
        }
        if (grouped != null && grouped.matcher(core).matches()) {
            String digits = core.replace(String.valueOf(grouping), "");
            return digits.length() <= MAX_DIGITS ? digits : null;
        }
        return null;
    }

    /** Das Währungszeichen oder -kürzel am Anfang des Worts; {@code ""}, wenn keins. */
    private static String currencyPrefix(String t) {
        if (!t.isEmpty() && isSymbol(t.charAt(0))) {
            return t.substring(0, 1);
        }
        if (t.length() > 3 && Currencies.isCode(t.substring(0, 3)) && !Character.isLetter(t.charAt(3))) {
            return t.substring(0, 3);
        }
        return "";
    }

    /** Das Währungszeichen oder -kürzel am Ende des Worts; {@code ""}, wenn keins. */
    private static String currencySuffix(String t) {
        if (!t.isEmpty() && isSymbol(t.charAt(t.length() - 1))) {
            return t.substring(t.length() - 1);
        }
        if (t.length() > 3 && Currencies.isCode(t.substring(t.length() - 3))
                && !Character.isLetter(t.charAt(t.length() - 4))) {
            return t.substring(t.length() - 3);
        }
        return "";
    }

    /** Das Wort ohne einzelne Klammer am Rand. */
    private static String bare(String token) {
        String t = token;
        if (t.startsWith("(") && !t.endsWith(")")) {
            t = t.substring(1);
        } else if (t.endsWith(")") && !t.startsWith("(")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    /** Ein alleinstehendes Währungskennzeichen: Symbol, Kürzel oder das ausgeschriebene „Euro". */
    static boolean isCurrency(String token) {
        if (token.length() == 1) {
            return isSymbol(token.charAt(0));
        }
        if (Currencies.isCode(token)) {
            return true;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        return lower.equals("euro") || lower.equals("eur");
    }

    private static boolean isSymbol(char c) {
        return c == '€' || c == '$' || c == '£' || c == '¥' || c == '₣';
    }

    @Override
    public String toString() {
        return !integers ? "STRICT" : grouping == '\0' ? "ohne Gruppierung" : "gruppiert mit " + grouping;
    }
}
