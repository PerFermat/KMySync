package de.spahr.ausgaben.db;

import java.util.Locale;

/**
 * Freitext-Suche über eine Buchung: trifft auf <b>Empfänger, Notiz oder Kategorie</b> (Teilstring, Groß-/
 * Kleinschreibung egal). Bewusst an einer Stelle, damit Liste und Auswertung nicht auseinanderlaufen –
 * beide filtern über dieselbe Methode.
 *
 * <p>Seit 2.1 trägt sie <b>zwei</b> Bedienwege: das Suchfeld im Filter-Dialog und die Live-Suche in der
 * Titelzeile ({@code BookingSearchBar}). Beide gelten zusammen – wer im Trichter etwas gesetzt hat und
 * dann oben tippt, sucht innerhalb des bereits Gefilterten weiter.</p>
 *
 * <p>Kleingeschrieben wird mit {@link Locale#ROOT}, nicht mit einer Sprache. Hier stand
 * {@code Locale.GERMANY}; gefährlich war das nie, weil beide Seiten dasselbe Locale benutzen und sich
 * damit gleich verhalten. Richtig ist es trotzdem nicht: Es geht um einen Textvergleich, nicht um
 * Anzeige, und genau dafür ist {@code ROOT} da. Der Wächter in {@code LocaleGuardTest} findet solche
 * Stellen nicht – er sucht nach dem <em>fehlenden</em> Argument, nicht nach dem falschen.</p>
 */
public final class BookingSearch {

    private BookingSearch() {
    }

    /** {@code true}, wenn {@code needle} leer ist oder in Empfänger/Notiz/Kategorie vorkommt. */
    public static boolean matches(Booking b, String needle) {
        if (needle == null || needle.isEmpty()) {
            return true;
        }
        String n = needle.toLowerCase(Locale.ROOT);
        return contains(b.payee, n) || contains(b.note, n) || contains(b.category, n);
    }

    private static boolean contains(String haystack, String lowerNeedle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }
}
