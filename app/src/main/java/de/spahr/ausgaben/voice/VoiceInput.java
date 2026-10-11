package de.spahr.ausgaben.voice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.spahr.ausgaben.util.PayeeMatch;

/**
 * Zerlegt einen gesprochenen Buchungssatz wie „Frisör 20€" in Empfänger-Suchbegriff und Betrag.
 *
 * <p>Der Betrag wird als Zahl (mit optionaler Nachkommastelle, Komma oder Punkt) erkannt; bevorzugt die
 * Zahl direkt vor einem Währungswort, sonst die letzte Zahl im Satz. Beträge in Worten („zwanzig")
 * werden nicht umgesetzt. Der verbleibende Text (ohne Zahl und Währungswörter) ist der
 * Empfänger-Suchbegriff.</p>
 *
 * <p>Die Währungswörter der drei mitgelieferten Sprachen sind fest hinterlegt, <b>jeweils mit Plural</b>:
 * Der gesprochene Satz heißt auf Spanisch „peluquería 20 euros" und auf Englisch „Barber 20 euros" – genau
 * so schlägt die App es in {@code wear_prompt} auch vor. Ohne den Plural blieb früher ein einzelnes „s" im
 * Empfänger stehen. Für hochgeladene Sprachen kommt die im Profil eingestellte Währung dazu
 * ({@link #parse(String, String)}); ein davon abweichendes gesprochenes Währungswort bleibt im Empfänger
 * stehen, was harmloser ist als ein falsch abgeschnittener Name.</p>
 */
public final class VoiceInput {

    /** Ergebnis der Zerlegung. {@link #amountCents} ist {@code null}, wenn kein Betrag erkannt wurde. */
    public static final class Result {
        public final String payee;
        public final Long amountCents;

        public Result(String payee, Long amountCents) {
            this.payee = payee;
            this.amountCents = amountCents;
        }
    }

    /**
     * Währungswörter der mitgelieferten Sprachen. Längere Form immer vor der kürzeren, sonst schluckt die
     * Alternative „euro" das „s" von „euros" nicht mit.
     */
    private static final String CURRENCY_WORDS =
            "euros|euro|eur|d[oó]lares|d[oó]lar|dollars|dollar|libras|libra|pounds|pound"
                    + "|francs|franc|franken";

    /** Fertige Muster je Währung – parse() wird pro erkanntem Satz mehrfach aufgerufen. */
    private static final Map<String, Pattern[]> CACHE = new ConcurrentHashMap<>();

    private VoiceInput() {
    }

    /** Wie {@link #parse(String, String)} ohne Profilwährung – nur die eingebauten Währungswörter. */
    public static Result parse(String spoken) {
        return parse(spoken, null);
    }

    /**
     * @param currency Währung des aktiven Profils (Zeichen wie „€" oder Kürzel wie „CHF"); {@code null}
     *                 oder leer lässt es bei den eingebauten Wörtern.
     */
    public static Result parse(String spoken, String currency) {
        if (spoken == null) {
            return new Result("", null);
        }
        String text = spoken.trim();
        Pattern[] muster = muster(currency);

        // Betrag suchen: den Treffer mit Währungswort bevorzugen, sonst den letzten Zahl-Treffer.
        Matcher m = muster[0].matcher(text);
        int lastStart = -1, lastEnd = -1;
        String lastNumber = null;
        int curStart = -1, curEnd = -1;
        String curNumber = null;
        while (m.find()) {
            lastStart = m.start();
            lastEnd = m.end();
            lastNumber = m.group(1);
            if (m.group(2) != null && curNumber == null) { // erste Zahl mit Währungswort
                curStart = m.start();
                curEnd = m.end();
                curNumber = m.group(1);
            }
        }
        boolean useCurrency = curNumber != null;
        String amtNumber = useCurrency ? curNumber : lastNumber;
        int amtStart = useCurrency ? curStart : lastStart;
        int amtEnd = useCurrency ? curEnd : lastEnd;

        Long cents = null;
        String rest = text;
        if (amtNumber != null) {
            cents = toCents(amtNumber);
            rest = text.substring(0, amtStart) + " " + text.substring(amtEnd);
        }
        // Übrige Währungswörter aus dem Empfänger entfernen und Leerraum normalisieren.
        rest = muster[1].matcher(rest).replaceAll(" ");
        String payee = rest.replaceAll("\\s+", " ").trim();

        return new Result(payee, cents);
    }

    /** [0] = Zahl mit optionalem Währungswort, [1] = Währungswort allein. */
    private static Pattern[] muster(String currency) {
        String key = currency == null ? "" : currency.trim();
        Pattern[] cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        String gruppe = waehrungsGruppe(key);
        Pattern[] neu = {
                Pattern.compile("(\\d+(?:[.,]\\d{1,2})?)\\s*(" + gruppe + ")?", Pattern.CASE_INSENSITIVE),
                Pattern.compile(gruppe, Pattern.CASE_INSENSITIVE),
        };
        CACHE.put(key, neu);
        return neu;
    }

    /**
     * Baut die Alternative aus eingebauten Wörtern, Währungszeichen und – falls gesetzt – der Profilwährung.
     * Buchstaben-Währungen bekommen Wortgrenzen: sonst risse ein Kürzel wie „kr" mitten aus einem
     * Empfängernamen ein Stück heraus. Zeichen wie „€" stehen ohne, weil {@code \b} an ihnen nicht greift.
     */
    private static String waehrungsGruppe(String currency) {
        StringBuilder sb = new StringBuilder("(?:\\b(?:").append(CURRENCY_WORDS).append(")\\b|€|\\$|£");
        if (!currency.isEmpty()) {
            boolean hatBuchstaben = false;
            for (int i = 0; i < currency.length(); i++) {
                if (Character.isLetter(currency.charAt(i))) {
                    hatBuchstaben = true;
                    break;
                }
            }
            String quoted = Pattern.quote(currency);
            sb.append('|').append(hatBuchstaben ? "\\b" + quoted + "\\b" : quoted);
        }
        return sb.append(')').toString();
    }

    /**
     * Wählt aus {@code candidates} den Empfänger, der dem gesprochenen {@code term} am ähnlichsten ist –
     * unscharf (Umlaut-Normalisierung + Ähnlichkeit auf Wortebene), damit z. B. „Friseur" auch „Frisör
     * Frank" findet. Gibt {@code null} zurück, wenn kein Kandidat ähnlich genug ist.
     */
    public static String bestFuzzyPayee(String term, List<String> candidates) {
        if (term == null || candidates == null) {
            return null;
        }
        String[] spoken = PayeeMatch.normalizeTokens(term);
        if (spoken.length == 0) {
            return null;
        }
        String best = null;
        double bestScore = 0;
        for (String cand : candidates) {
            if (cand == null || cand.trim().isEmpty()) {
                continue;
            }
            double score = PayeeMatch.tokenSimilarity(spoken, PayeeMatch.normalizeTokens(cand));
            if (score > bestScore) {
                bestScore = score;
                best = cand;
            }
        }
        return bestScore >= PayeeMatch.THRESHOLD ? best : null;
    }

    private static Long toCents(String number) {
        try {
            String normalized = number.replace(",", ".");
            return new BigDecimal(normalized).movePointRight(2)
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }
}
