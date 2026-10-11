package de.spahr.ausgaben.notify;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import de.spahr.ausgaben.statement.NotificationSource;
import de.spahr.ausgaben.util.Currencies;
import de.spahr.ausgaben.util.TextValues;

/**
 * Ob eine Benachrichtigung überhaupt von Geld spricht — die Schwelle, hinter der sie aufgehoben wird.
 *
 * <p>Verlangt wird beides: eine Zahl mit zwei Nachkommastellen <b>und</b> ein Währungskennzeichen
 * daran oder daneben. Eines allein genügt nicht. „Akku bei 12,50 %" ist keine Buchung, „Noch 3 €
 * Guthaben" keine Zahl mit Komma. Was hier durchfällt, sieht die App nie wieder; die Schwelle ist
 * deshalb der Datenschutz dieser Funktion und nicht bloß ein Vorfilter.</p>
 *
 * <p>Die Währung ist nicht auf die eigene beschränkt: ein Umsatz in Dollar soll im Puffer landen und
 * später als Fremdwährung auffallen, statt gar nicht erst gesehen zu werden.</p>
 */
public final class AmountFilter {

    /** Ziffer, Trenner, zwei Nachkommastellen (oder „,--") und danach keine weitere Ziffer. */
    private static final Pattern CENTS = Pattern.compile("\\d[.,](\\d{2}|--?)(?!\\d)");

    private AmountFilter() {
    }

    public static boolean hasAmount(String title, String text) {
        return hasAmount(title) || hasAmount(text);
    }

    public static boolean hasAmount(String raw) {
        return !amounts(raw).isEmpty();
    }

    /** Alle Beträge aus Titel und Text, in Cent und ohne Vorzeichen. */
    public static List<Long> amounts(String title, String text) {
        List<Long> out = amounts(title);
        out.addAll(amounts(text));
        return out;
    }

    /** Alle Beträge des Texts — jede Zahl mit Nachkommastellen, an oder neben der eine Währung steht. */
    public static List<Long> amounts(String raw) {
        List<Long> out = new ArrayList<>();
        for (String line : NotificationSource.normalize(raw).split("\n")) {
            String[] tokens = words(line);
            for (int i = 0; i < tokens.length; i++) {
                if (!isMoney(tokens[i])) {
                    continue;
                }
                if (carriesCurrency(tokens[i])
                        || (i > 0 && isCurrency(tokens[i - 1]))
                        || (i + 1 < tokens.length && isCurrency(tokens[i + 1]))) {
                    out.add(Math.abs(TextValues.toCents(tokens[i])));
                }
            }
        }
        return out;
    }

    /**
     * Die Währungen der Beträge im Text, als ISO-Kürzel und ohne Doppelte — für die Frage, ob eine
     * Meldung in fremder Währung spricht.
     */
    public static List<String> currencies(String raw) {
        List<String> out = new ArrayList<>();
        for (String line : NotificationSource.normalize(raw).split("\n")) {
            String[] tokens = words(line);
            for (int i = 0; i < tokens.length; i++) {
                if (!isMoney(tokens[i])) {
                    continue;
                }
                String found = attached(tokens[i]);
                if (found.isEmpty() && i + 1 < tokens.length && isCurrency(tokens[i + 1])) {
                    found = iso(tokens[i + 1]);
                }
                if (found.isEmpty() && i > 0 && isCurrency(tokens[i - 1])) {
                    found = iso(tokens[i - 1]);
                }
                if (!found.isEmpty() && !out.contains(found)) {
                    out.add(found);
                }
            }
        }
        return out;
    }

    /** Das ISO-Kürzel zu einem Kennzeichen („€" → EUR); leer, wenn es keines ist. */
    public static String iso(String token) {
        if (token == null) {
            return "";
        }
        String t = token.trim();
        if (t.length() == 1) {
            switch (t.charAt(0)) {
                case '€': return "EUR";
                case '$': return "USD";
                case '£': return "GBP";
                case '¥': return "JPY";
                case '₣': return "CHF";
                default: return "";
            }
        }
        if (Currencies.isCode(t)) {
            return t;
        }
        String lower = t.toLowerCase(Locale.ROOT);
        return lower.equals("euro") || lower.equals("eur") ? "EUR" : "";
    }

    /** Das Kennzeichen, das an der Zahl klebt, als ISO-Kürzel; leer, wenn keines. */
    private static String attached(String token) {
        String first = iso(token.substring(0, 1));
        if (!first.isEmpty()) {
            return first;
        }
        String last = iso(token.substring(token.length() - 1));
        if (!last.isEmpty()) {
            return last;
        }
        if (token.length() > 3) {
            if (Currencies.isCode(token.substring(0, 3))) {
                return token.substring(0, 3);
            }
            if (Currencies.isCode(token.substring(token.length() - 3))) {
                return token.substring(token.length() - 3);
            }
        }
        return "";
    }

    /**
     * Die Wörter einer Zeile, ohne eine einzelne Klammer am Rand: „(7,50 USD)" sind zwei Wörter, und an
     * jedem hängt eine halbe Klammer. Die ganze bleibt — „(12,34)" heißt negativ.
     */
    private static String[] words(String line) {
        String[] tokens = line.split(" ");
        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i];
            boolean opens = t.startsWith("(");
            boolean closes = t.endsWith(")");
            if (opens && !closes) {
                tokens[i] = t.substring(1);
            } else if (closes && !opens) {
                tokens[i] = t.substring(0, t.length() - 1);
            }
        }
        return tokens;
    }

    /** Eine lesbare Zahl mit Nachkommastellen — „17" und „1.234" sind keine Beträge. */
    private static boolean isMoney(String token) {
        return !token.isEmpty() && CENTS.matcher(token).find() && TextValues.toCents(token) != null;
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

    /** Ob das Kennzeichen an der Zahl klebt: „12,34€", „EUR12,34". */
    private static boolean carriesCurrency(String token) {
        if (isSymbol(token.charAt(0)) || isSymbol(token.charAt(token.length() - 1))) {
            return true;
        }
        return token.length() > 3 && (Currencies.isCode(token.substring(0, 3))
                || Currencies.isCode(token.substring(token.length() - 3)));
    }

    private static boolean isSymbol(char c) {
        return c == '€' || c == '$' || c == '£' || c == '¥' || c == '₣';
    }
}
