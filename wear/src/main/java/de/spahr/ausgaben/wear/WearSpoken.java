package de.spahr.ausgaben.wear;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zerlegt einen gesprochenen Satz wie „Frisör 20 Euro" in Empfänger und Betrag – so weit, wie es die
 * Uhr für die <b>Anzeige</b> braucht.
 *
 * <p>Bewusst eine schlankere Verwandte von {@code de.spahr.ausgaben.voice.VoiceInput} im Handy-Modul:
 * Die Module teilen keinen Code, und die Uhr braucht nur die Aufteilung für den Bildschirm. Maßgeblich
 * bleibt der Auswerter des Handys – deshalb wird der gesprochene Satz auch <b>unverändert</b>
 * weitergereicht, solange der Nutzer den Empfänger nicht austauscht. Eine schiefe Aufteilung kostet
 * dann höchstens eine schiefe Anzeige, nie eine falsche Buchung.</p>
 */
final class WearSpoken {

    /** Zahl mit höchstens zwei Nachkommastellen, gefolgt von einem möglichen Währungswort. */
    private static final Pattern BETRAG = Pattern.compile(
            "(\\d+(?:[.,]\\d{1,2})?)\\s*(euros?|eur|€|dollars?|\\$|pounds?|£|francs?|franken|chf)?",
            Pattern.CASE_INSENSITIVE);

    /** Empfänger und Betrag, wie sie auf dem Bildschirm stehen sollen; beide können leer sein. */
    static final class Result {
        final String payee;
        final String amount;

        Result(String payee, String amount) {
            this.payee = payee;
            this.amount = amount;
        }
    }

    private WearSpoken() {
    }

    static Result parse(String spoken) {
        if (spoken == null || spoken.trim().isEmpty()) {
            return new Result("", "");
        }
        String text = spoken.trim();
        Matcher m = BETRAG.matcher(text);
        int start = -1;
        int end = -1;
        String zahl = null;
        while (m.find()) {
            boolean mitWaehrung = m.group(2) != null && !m.group(2).isEmpty();
            // Die Zahl vor einem Währungswort gewinnt; sonst zählt die letzte im Satz. Genau deshalb
            // steht der Betrag beim Senden hinten: „Aral 24" als Name würde sonst den Betrag stellen.
            if (mitWaehrung) {
                start = m.start();
                end = m.end();
                zahl = m.group(1);
                break;
            }
            start = m.start();
            end = m.end();
            zahl = m.group(1);
        }
        if (zahl == null) {
            return new Result(text, "");
        }
        String rest = (text.substring(0, start) + " " + text.substring(end)).trim();
        return new Result(rest.replaceAll("\\s+", " "), zahl.replace('.', ','));
    }
}
