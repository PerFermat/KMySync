package de.spahr.ausgaben.notify;

import java.util.List;

/**
 * Ob zwei aufgehobene Meldungen <b>dieselbe</b> sind — eine App hat ihre Benachrichtigung aktualisiert
 * („vorgemerkt", dann „gebucht") — oder zwei Umsätze.
 *
 * <p>Der Schlüssel allein ({@code sbn.getKey()}) sagt das nicht. Viele Bank-Apps posten jede Zahlung
 * unter derselben festen Benachrichtigungs-ID; dann tragen zwei echte Einkäufe denselben Schlüssel.
 * Bis 2.2 galt jede ältere Fassung unter gleichem Schlüssel als überholt — und der Nutzer löschte womöglich
 * eine echte Zahlung.</p>
 *
 * <p>Eine Aktualisierung ist es deshalb nur, wenn außer dem Schlüssel auch der Betrag übereinstimmt
 * und die beiden höchstens {@link #MAX_GAP_MS} auseinanderliegen. Aktualisierungen kommen binnen
 * Sekunden bis Minuten; zwei gleich teure Käufe unter fester ID — der Kaffee am Morgen und der am
 * Nachmittag — liegen weiter auseinander. Ungleiche Beträge sind immer zwei Umsätze.</p>
 *
 * <p>Die eine Stelle für diese Frage: die Vorschlagsliste ({@link NotificationRun}) und der Abgleich mit
 * einer von Hand angelegten Buchung ({@link NotificationMatcher}) fragen beide hier. Verglichen werden
 * die Beträge aus dem Wortlaut, nicht die erkannten — so gilt dasselbe für erkannte und nicht erkannte
 * Meldungen.</p>
 */
public final class MessageUpdate {

    /** So weit dürfen zwei Fassungen derselben Meldung höchstens auseinanderliegen. */
    public static final long MAX_GAP_MS = 10 * 60 * 1000L;

    private MessageUpdate() {
    }

    public static boolean isUpdate(String pkgA, String keyA, long timeA, List<Long> amountsA,
                                   String pkgB, String keyB, long timeB, List<Long> amountsB) {
        if (pkgA == null || !pkgA.equals(pkgB) || keyA == null || keyA.isEmpty() || !keyA.equals(keyB)) {
            return false;
        }
        if (Math.abs(timeA - timeB) > MAX_GAP_MS) {
            return false;
        }
        for (Long amount : amountsA) {
            if (amountsB.contains(amount)) {
                return true;
            }
        }
        return false;
    }

    static boolean isUpdate(NotificationBuffer.Entry a, NotificationBuffer.Entry b) {
        return isUpdate(a.packageName, a.key, a.postTime, AmountFilter.amounts(a.title, a.text),
                b.packageName, b.key, b.postTime, AmountFilter.amounts(b.title, b.text));
    }

    static boolean isUpdate(NotificationDraft a, NotificationDraft b) {
        return isUpdate(a.packageName, a.key, a.postTime, AmountFilter.amounts(a.title, a.text),
                b.packageName, b.key, b.postTime, AmountFilter.amounts(b.title, b.text));
    }

    /** Ob {@code b} die jüngere Fassung ist; bei gleicher Zeit gilt die später aufgehobene. */
    static boolean newer(long timeB, long idB, long timeA, long idA) {
        return timeB > timeA || (timeB == timeA && idB > idA);
    }
}
