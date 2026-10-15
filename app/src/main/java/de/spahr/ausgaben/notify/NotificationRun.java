package de.spahr.ausgaben.notify;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import de.spahr.ausgaben.statement.MoneyStyle;
import de.spahr.ausgaben.statement.NotificationSource;
import de.spahr.ausgaben.statement.NotificationTemplate;

/**
 * Der Durchlauf beim Aktualisieren, soweit er reines Rechnen ist: aus dem Puffer und den Vorlagen die
 * Zeilen der Vorschlagsliste bauen und markieren, was doppelt sein könnte.
 *
 * <p>Die Leitlinie: im Zweifel eine Zeile <b>mit Hinweis zeigen</b>, nicht unterdrücken — eine
 * überzählige Buchung ist schnell gelöscht, eine fehlende fällt oft erst beim Abgleich auf. Gebucht
 * wird nur, was der Nutzer bestätigt. Zwei Vorlagen, die sich widersprechen, ergeben deshalb keine
 * geratene Buchung, sondern eine Zeile „nicht erkannt", die er von Hand buchen kann.</p>
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
        return drafts(buffer, templates, currencies, zone, MoneyStyle.STRICT);
    }

    /** @param style das Zahlenformat des Profils, für Beträge ohne Nachkommastellen */
    public static ArrayList<NotificationDraft> drafts(List<NotificationBuffer.Entry> buffer,
                                                      List<NotificationTemplate> templates,
                                                      AccountCurrency currencies, ZoneId zone,
                                                      MoneyStyle style) {
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
            out.add(draft(e, ofApp, currencies, style));
        }
        markBufferDuplicates(out, zone, style);
        return out;
    }

    /** Nur die Meldungen der genannten Apps — die Grenze zwischen den Profilen. */
    public static List<NotificationBuffer.Entry> ofApps(List<NotificationBuffer.Entry> buffer,
                                                        Set<String> packages) {
        List<NotificationBuffer.Entry> out = new ArrayList<>();
        for (NotificationBuffer.Entry e : buffer) {
            if (packages.contains(e.packageName)) {
                out.add(e);
            }
        }
        return out;
    }

    private static NotificationDraft draft(NotificationBuffer.Entry e, List<NotificationTemplate> ofApp,
                                           AccountCurrency currencies, MoneyStyle style) {
        NotificationDraft d = new NotificationDraft();
        d.entryId = e.id;
        d.packageName = e.packageName;
        d.key = e.key;
        d.postTime = e.postTime;
        d.title = e.title;
        d.text = e.text;
        d.coords = e.coords;
        d.dateMillis = e.postTime;

        NotificationSource source = new NotificationSource(e.packageName, e.postTime, e.title, e.text,
                style);
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
            d.rawPayee = first.payee;
            d.account = commonAccount(hits);
        } else {
            // Nicht erkannt. Vorbelegt wird nur, was ohne Raten feststeht: das Konto, wenn die App nur
            // eines hat, und der Betrag, wenn die Meldung nur einen nennt.
            Set<String> accounts = new HashSet<>();
            for (NotificationTemplate t : ofApp) {
                accounts.add(t.account);
            }
            d.account = accounts.size() == 1 ? accounts.iterator().next() : "";
            List<Long> amounts = AmountFilter.amounts(e.title, e.text, style);
            d.cents = amounts.size() == 1 ? amounts.get(0) : 0;
        }
        d.foreignCurrency = foreignCurrency(e, d.account.isEmpty() ? "" : currencies.of(d.account),
                style);
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
        return foreignCurrency(e, accountCurrency, MoneyStyle.STRICT);
    }

    static String foreignCurrency(NotificationBuffer.Entry e, String accountCurrency,
                                  MoneyStyle style) {
        String own = AmountFilter.iso(accountCurrency);
        if (own.isEmpty()) {
            return "";
        }
        for (String found : AmountFilter.currencies(e.title + "\n" + e.text, style)) {
            if (!found.equals(own)) {
                return found;
            }
        }
        return "";
    }

    /**
     * Markiert Zeilen, die dieselbe Meldung ein zweites Mal sein könnten. Markiert — entfernt wird
     * nichts; der Nutzer sieht beide Zeilen und entscheidet.
     *
     * <p>Zwei Fälle. Eine Aktualisierung derselben Meldung ({@link MessageUpdate}: gleicher Schlüssel,
     * gleicher Betrag, wenige Minuten): die ältere Fassung. Und gleiche App, gleicher Betrag, gleicher
     * Empfänger am selben Tag: dann ist es entweder dieselbe Meldung unter neuem Schlüssel oder
     * wirklich ein zweiter Einkauf; das weiß nur der Nutzer, also wird die spätere markiert.</p>
     *
     * <p>Gleicher Schlüssel allein ist kein Grund: viele Bank-Apps posten jede Zahlung unter derselben
     * festen ID.</p>
     */
    public static void markBufferDuplicates(List<NotificationDraft> drafts, ZoneId zone) {
        markBufferDuplicates(drafts, zone, MoneyStyle.STRICT);
    }

    public static void markBufferDuplicates(List<NotificationDraft> drafts, ZoneId zone,
                                            MoneyStyle style) {
        for (NotificationDraft d : drafts) {
            d.dupBuffer = false;
            for (NotificationDraft o : drafts) {
                if (o != d && MessageUpdate.isUpdate(d, o, style)
                        && MessageUpdate.newer(o.postTime, o.entryId, d.postTime, d.entryId)) {
                    d.dupBuffer = true;   // es gibt eine jüngere Fassung derselben Meldung
                    break;
                }
            }
        }
        Set<String> seenContent = new HashSet<>();
        for (NotificationDraft d : drafts) {
            if (d.dupBuffer || d.cents <= 0) {
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
}
