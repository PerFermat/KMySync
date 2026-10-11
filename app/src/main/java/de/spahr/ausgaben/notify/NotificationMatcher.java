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
     * Je App höchstens eine Meldung. Leer heißt: nichts gefunden. Mehrere heißt: verschiedene Apps
     * haben denselben Betrag gemeldet, und der Nutzer muss sagen, welche gemeint ist.
     *
     * <p>Bleiben <b>innerhalb</b> einer App mehrere übrig, die auch der Empfänger nicht trennt, gibt
     * es von dieser App keinen Treffer. Zwei gleich teure Einkäufe am selben Tag sind nicht zu
     * unterscheiden, und aus der falschen zu lernen hieße, die richtige später doppelt vorzuschlagen.</p>
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
            NotificationBuffer.Entry best = pick(latestPerKey(candidates), payee);
            if (best != null) {
                out.add(best);
            }
        }
        return out;
    }

    /** Eine App, die ihre Meldung aktualisiert, hat sie nicht zweimal geschickt: die jüngste Fassung gilt. */
    private static List<NotificationBuffer.Entry> latestPerKey(List<NotificationBuffer.Entry> entries) {
        Map<String, NotificationBuffer.Entry> latest = new LinkedHashMap<>();
        for (NotificationBuffer.Entry e : entries) {
            NotificationBuffer.Entry seen = latest.get(e.key);
            if (seen == null || e.postTime >= seen.postTime) {
                latest.put(e.key, e);
            }
        }
        return new ArrayList<>(latest.values());
    }

    private static NotificationBuffer.Entry pick(List<NotificationBuffer.Entry> candidates,
                                                 String payee) {
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        NotificationBuffer.Entry named = null;
        for (NotificationBuffer.Entry e : candidates) {
            if (PayeeMatch.similarity(payee, e.title + " " + e.text) >= PayeeMatch.THRESHOLD) {
                if (named != null) {
                    return null;   // auch der Empfänger trennt sie nicht
                }
                named = e;
            }
        }
        return named;
    }

    private static LocalDate dayOf(long millis, ZoneId zone) {
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate();
    }
}
