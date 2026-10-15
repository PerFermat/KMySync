package de.spahr.ausgaben.notify;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.spahr.ausgaben.util.PayeeMatch;

/**
 * Findet zu einer von Hand angelegten Buchung die Benachrichtigung, die denselben Umsatz gemeldet hat.
 *
 * <p>Maßgeblich ist der <b>Betrag</b>, auf den Cent; das Vorzeichen zählt nicht, weil die Meldung
 * selten eines trägt. Dazu der Tag: die Meldung kommt am Tag der Buchung oder einen daneben — abends
 * bezahlt, am nächsten Morgen eingetragen. Der Empfänger entscheidet nur, wenn danach noch mehrere
 * übrig sind; gebucht wird „Rewe", gemeldet „REWE SAGT DANKE 4711", und daran darf der Treffer nicht
 * scheitern.</p>
 */
public final class NotificationMatcher {

    /** So viele Tage dürfen zwischen Meldung und Buchungsdatum liegen. */
    static final int MAX_DAYS = 1;

    private NotificationMatcher() {
    }

    /**
     * Die Meldungen, die zu der Buchung passen. Leer heißt: nichts gefunden. Eine heißt: diese. Mehrere
     * heißt: der Nutzer muss sagen, welche gemeint ist — sei es, dass verschiedene Apps denselben
     * Betrag gemeldet haben, sei es, dass innerhalb einer App mehrere übrig bleiben, die auch der
     * Empfänger nicht trennt (zwei gleich teure Einkäufe am selben Tag).
     *
     * <p>Zusammengefasst wird nur, was nachweislich dieselbe Meldung ist ({@link MessageUpdate}); ein
     * gleicher Schlüssel allein genügt nicht. Lieber einmal mehr nachfragen, als eine echte Zahlung
     * stillschweigend fallen zu lassen.</p>
     */
    public static List<NotificationBuffer.Entry> match(List<NotificationBuffer.Entry> buffer,
                                                       long cents, long bookingMillis, String payee,
                                                       ZoneId zone) {
        long amount = Math.abs(cents);
        LocalDate day = dayOf(bookingMillis, zone);
        Map<String, List<NotificationBuffer.Entry>> byApp = new LinkedHashMap<>();
        for (NotificationBuffer.Entry e : buffer) {
            if (Math.abs(ChronoUnit.DAYS.between(day, dayOf(e.postTime, zone))) > MAX_DAYS
                    || !AmountFilter.amounts(e.title, e.text).contains(amount)) {
                continue;
            }
            List<NotificationBuffer.Entry> list = byApp.get(e.packageName);
            if (list == null) {
                list = new ArrayList<>();
                byApp.put(e.packageName, list);
            }
            list.add(e);
        }
        List<NotificationBuffer.Entry> out = new ArrayList<>();
        for (List<NotificationBuffer.Entry> candidates : byApp.values()) {
            out.addAll(pick(withoutOlderVersions(candidates), payee));
        }
        return out;
    }

    /**
     * Eine App, die ihre Meldung aktualisiert, hat sie nicht zweimal geschickt: von einer
     * Aktualisierung gilt die jüngste Fassung. Alles andere bleibt Kandidat — auch unter gleichem
     * Schlüssel, denn viele Apps posten jede Zahlung unter derselben festen ID.
     */
    private static List<NotificationBuffer.Entry> withoutOlderVersions(
            List<NotificationBuffer.Entry> entries) {
        List<NotificationBuffer.Entry> out = new ArrayList<>();
        for (NotificationBuffer.Entry e : entries) {
            boolean superseded = false;
            for (NotificationBuffer.Entry o : entries) {
                if (o != e && MessageUpdate.isUpdate(e, o)
                        && MessageUpdate.newer(o.postTime, o.id, e.postTime, e.id)) {
                    superseded = true;
                    break;
                }
            }
            if (!superseded) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * Mehrere Kandidaten einer App: der Empfänger entscheidet, wenn er genau einen trennt. Sonst
     * kommen alle zurück — die passenden, wenn es mehrere sind, sonst alle — und der Nutzer wählt.
     */
    private static List<NotificationBuffer.Entry> pick(List<NotificationBuffer.Entry> candidates,
                                                       String payee) {
        if (candidates.size() <= 1) {
            return candidates;
        }
        List<NotificationBuffer.Entry> named = new ArrayList<>();
        for (NotificationBuffer.Entry e : candidates) {
            if (PayeeMatch.similarity(payee, e.title + " " + e.text) >= PayeeMatch.THRESHOLD) {
                named.add(e);
            }
        }
        return named.isEmpty() ? candidates : named;
    }

    private static LocalDate dayOf(long millis, ZoneId zone) {
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate();
    }
}
