package de.spahr.ausgaben.notify;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import de.spahr.ausgaben.statement.NotificationSource;
import de.spahr.ausgaben.statement.NotificationTemplate;
import de.spahr.ausgaben.util.PayeeMatch;

/**
 * Der Durchlauf beim Aktualisieren, soweit er reines Rechnen ist: aus dem Puffer und den Vorlagen die
 * Zeilen der Vorschlagsliste bauen und markieren, was doppelt sein könnte.
 *
 * <p>Die Leitlinie steht in jeder Verzweigung: im Zweifel nicht erkennen. Zwei Vorlagen, die sich
 * widersprechen, ergeben keine Buchung zum Aussuchen, sondern eine Zeile „nicht erkannt".</p>
 */
public final class NotificationRun {

    /** Die Währung eines Kontos, wie die App sie führt; leer, wenn unbekannt. */
    public interface AccountCurrency {
        String of(String account);
    }

    private NotificationRun() {
    }

    /**
     * Die Zeilen der Liste, die ältesten zuerst. Meldungen von Apps <b>ohne</b> Vorlage kommen nicht
     * vor — sie gehören diesem Profil nicht und bleiben im Puffer unberührt.
     */
    public static ArrayList<NotificationDraft> drafts(List<NotificationBuffer.Entry> buffer,
                                                      List<NotificationTemplate> templates,
                                                      AccountCurrency currencies, ZoneId zone) {
        ArrayList<NotificationDraft> out = new ArrayList<>();
        for (NotificationBuffer.Entry e : buffer) {
            List<NotificationTemplate> ofApp = new ArrayList<>();
            for (NotificationTemplate t : templates) {
                if (t.packageName.equals(e.packageName)) {
                    ofApp.add(t);
                }
            }
            if (ofApp.isEmpty()) {
                continue;
            }
            out.add(draft(e, ofApp, currencies));
        }
        markBufferDuplicates(out, zone);
        return out;
    }

    private static NotificationDraft draft(NotificationBuffer.Entry e, List<NotificationTemplate> ofApp,
                                           AccountCurrency currencies) {
        NotificationDraft d = new NotificationDraft();
        d.entryId = e.id;
        d.packageName = e.packageName;
        d.key = e.key;
        d.postTime = e.postTime;
        d.title = e.title;
        d.text = e.text;
        d.dateMillis = e.postTime;

        NotificationSource source = new NotificationSource(e.packageName, e.postTime, e.title, e.text);
        List<NotificationTemplate.Hit> hits = new ArrayList<>();
        for (NotificationTemplate t : ofApp) {
            NotificationTemplate.Hit hit = t.apply(source);
            if (hit != null) {
                hits.add(hit);
            }
        }
        hits = mostSpecific(hits);
        if (!hits.isEmpty() && agree(hits)) {
            NotificationTemplate.Hit first = hits.get(0);
            d.recognized = true;
            d.cents = first.cents;
            d.isIncome = first.template.isIncome;
            d.payee = first.payee;
            d.account = commonAccount(hits);
        } else {
            // Nicht erkannt. Vorbelegt wird nur, was ohne Raten feststeht: das Konto, wenn die App nur
            // eines hat, und der Betrag, wenn die Meldung nur einen nennt.
            Set<String> accounts = new HashSet<>();
            for (NotificationTemplate t : ofApp) {
                accounts.add(t.account);
            }
            d.account = accounts.size() == 1 ? accounts.iterator().next() : "";
            List<Long> amounts = AmountFilter.amounts(e.title, e.text);
            d.cents = amounts.size() == 1 ? amounts.get(0) : 0;
        }
        d.foreignCurrency = foreignCurrency(e, d.account.isEmpty() ? "" : currencies.of(d.account));
        return d;
    }

    /** Trägt eine Vorlage ein Unterscheidungsmerkmal und trifft, gilt sie vor denen ohne. */
    private static List<NotificationTemplate.Hit> mostSpecific(List<NotificationTemplate.Hit> hits) {
        List<NotificationTemplate.Hit> marked = new ArrayList<>();
        for (NotificationTemplate.Hit h : hits) {
            if (!h.template.discriminator.isEmpty()) {
                marked.add(h);
            }
        }
        return marked.isEmpty() ? hits : marked;
    }

    /** Ob alle Treffer dasselbe gelesen haben: Betrag und Richtung. */
    private static boolean agree(List<NotificationTemplate.Hit> hits) {
        NotificationTemplate.Hit first = hits.get(0);
        for (NotificationTemplate.Hit h : hits) {
            if (h.cents != first.cents || h.template.isIncome != first.template.isIncome) {
                return false;
            }
        }
        return true;
    }

    /** Das Konto, wenn alle Treffer dasselbe meinen; sonst leer — dann wählt der Nutzer. */
    private static String commonAccount(List<NotificationTemplate.Hit> hits) {
        String account = hits.get(0).template.account;
        for (NotificationTemplate.Hit h : hits) {
            if (!h.template.account.equals(account)) {
                return "";
            }
        }
        return account;
    }

    /** Das Kürzel einer Währung in der Meldung, die nicht die des Kontos ist; leer, wenn keine. */
    static String foreignCurrency(NotificationBuffer.Entry e, String accountCurrency) {
        String own = AmountFilter.iso(accountCurrency);
        if (own.isEmpty()) {
            return "";
        }
        for (String found : AmountFilter.currencies(e.title + "\n" + e.text)) {
            if (!found.equals(own)) {
                return found;
            }
        }
        return "";
    }

    /**
     * Markiert Zeilen, die dieselbe Meldung ein zweites Mal sind.
     *
     * <p>Zwei Fälle. Gleicher Schlüssel: die App hat ihre Meldung aktualisiert („vorgemerkt", dann
     * „gebucht") — alle Fassungen außer der jüngsten. Und gleiche App, gleicher Betrag, gleicher
     * Empfänger am selben Tag: dann ist es entweder dieselbe Meldung unter neuem Schlüssel oder
     * wirklich ein zweiter Einkauf; das weiß nur der Nutzer, also wird die spätere markiert und nicht
     * entfernt.</p>
     */
    public static void markBufferDuplicates(List<NotificationDraft> drafts, ZoneId zone) {
        Map<String, NotificationDraft> latestByKey = new HashMap<>();
        for (NotificationDraft d : drafts) {
            d.dupBuffer = false;
            NotificationDraft seen = latestByKey.get(d.packageName + '\u001F' + d.key);
            if (seen == null || d.postTime >= seen.postTime) {
                latestByKey.put(d.packageName + '\u001F' + d.key, d);
            }
        }
        Set<String> seenContent = new HashSet<>();
        for (NotificationDraft d : drafts) {
            if (latestByKey.get(d.packageName + '\u001F' + d.key) != d) {
                d.dupBuffer = true;
                continue;
            }
            if (d.cents <= 0) {
                continue;
            }
            LocalDate day = Instant.ofEpochMilli(d.dateMillis).atZone(zone).toLocalDate();
            String content = d.packageName + '\u001F' + d.cents + '\u001F'
                    + d.payee.toLowerCase(Locale.ROOT) + '\u001F' + day;
            if (!seenContent.add(content)) {
                d.dupBuffer = true;
            }
        }
    }

    /**
     * Der bekannte Empfänger, den die Meldung meint; {@code null}, wenn keiner eindeutig passt.
     *
     * <p>Strenger als der Vergleich beim Diktieren: <b>jedes</b> Wort des bekannten Namens muss in der
     * Meldung stehen. Dort heißt es „REWE Markt GmbH"; „Rewe" passt, „Media Markt" nicht — obwohl
     * „Markt" in beiden steht. Passen mehrere gleich gut, bleibt der Text der Meldung stehen.</p>
     */
    public static String knownPayee(String raw, List<String> known) {
        if (raw == null || raw.trim().isEmpty() || known == null) {
            return null;
        }
        Set<String> words = new HashSet<>(Arrays.asList(PayeeMatch.normalizeTokens(raw)));
        String best = null;
        int bestLength = 0;
        boolean tie = false;
        for (String candidate : known) {
            if (candidate == null) {
                continue;
            }
            String[] name = PayeeMatch.normalizeTokens(candidate);
            int length = 0;
            boolean all = name.length > 0;
            for (String word : name) {
                all &= words.contains(word);
                length += word.length();
            }
            if (!all || length < 4) {
                continue;
            }
            if (length > bestLength) {
                best = candidate;
                bestLength = length;
                tie = false;
            } else if (length == bestLength && !candidate.equalsIgnoreCase(best)) {
                tie = true;
            }
        }
        return tie ? null : best;
    }
}
