package de.spahr.ausgaben.util;

import java.util.Locale;

/**
 * Unscharfer Vergleich zweier Empfängernamen: Umlaute vereinheitlicht, Satzzeichen weg, dann die beste
 * Ähnlichkeit auf Wortebene.
 *
 * <p>Stand bis 2.2 in {@code VoiceInput} und galt nur dem gesprochenen Empfänger. Herausgelöst, weil der
 * Abgleich einer Benachrichtigung mit einer Buchung dieselbe Frage stellt — und zwei Maßstäbe dafür
 * hießen, dass „Frisör Frank" beim Diktieren gefunden wird und in der Benachrichtigung nicht.</p>
 */
public final class PayeeMatch {

    /** Ab dieser Ähnlichkeit gelten zwei Namen als derselbe Empfänger. */
    public static final double THRESHOLD = 0.70;

    private PayeeMatch() {
    }

    /** Ähnlichkeit zweier Namen, 0 bis 1; 0, wenn einer fehlt oder kein Wort lang genug ist. */
    public static double similarity(String a, String b) {
        if (a == null || b == null) {
            return 0;
        }
        return tokenSimilarity(normalizeTokens(a), normalizeTokens(b));
    }

    /** Beste Wort-zu-Wort-Ähnlichkeit (0..1) zwischen zwei Token-Listen; zu kurze Tokens ignoriert. */
    public static double tokenSimilarity(String[] a, String[] b) {
        double best = 0;
        for (String ta : a) {
            if (ta.length() < 4) {
                continue;
            }
            for (String tb : b) {
                if (tb.length() < 4) {
                    continue;
                }
                int d = levenshtein(ta, tb);
                int max = Math.max(ta.length(), tb.length());
                double s = max == 0 ? 1.0 : 1.0 - (double) d / max;
                if (s > best) {
                    best = s;
                }
            }
        }
        return best;
    }

    /** Kleinschreibung, Umlaute → ae/oe/ue/ss, nur Buchstaben/Ziffern; in Wörter zerlegt. */
    public static String[] normalizeTokens(String s) {
        String x = s.toLowerCase(Locale.GERMANY)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < x.length(); i++) {
            char c = x.charAt(i);
            sb.append(Character.isLetterOrDigit(c) ? c : ' ');
        }
        String norm = sb.toString().trim();
        return norm.isEmpty() ? new String[0] : norm.split("\\s+");
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }
}
