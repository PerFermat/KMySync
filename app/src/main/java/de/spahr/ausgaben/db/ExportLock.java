package de.spahr.ausgaben.db;

import androidx.annotation.Nullable;

/**
 * Hält die Buchungen fest, solange ein Export in die KMyMoney-Datei läuft – die eine Regel dieses
 * Zustands, ohne Android und ohne Datenbank.
 *
 * <p>Der Export läuft im Hintergrund, die App bleibt bedienbar. Zwischen dem Festhalten des Stands und
 * dem Ende des Laufs liegen aber Schreiben, Zurücklesen und das Aktualisieren aller Konten aus der
 * Datei – und das Aktualisieren ersetzt jede schon übertragene Buchung durch die Fassung der Datei.
 * Eine Änderung, die in dieser Zeit an einer vorhandenen Buchung gemacht würde, ginge dabei verloren
 * oder würde als „exportiert" markiert, ohne je geschrieben worden zu sein.</p>
 *
 * <p>Deshalb gilt für die Dauer des Laufs: Was es beim Start schon gab, lässt sich nur ansehen. Neue
 * Buchungen sind frei – sie stehen nicht im festgehaltenen Stand, werden vom Aktualisieren nicht
 * berührt und gehen mit dem nächsten Export hinaus. Unterschieden wird an der id: sie wächst mit jeder
 * neuen Buchung.</p>
 */
public final class ExportLock {

    /** Höchste Buchungs-id beim Start des laufenden Exports; {@code -1} = es läuft keiner. */
    private static volatile long upToId = -1;

    private ExportLock() {
    }

    /** Ein Export beginnt; {@code maxBookingId} ist die höchste id, die es jetzt gibt (0 = keine). */
    public static void begin(long maxBookingId) {
        upToId = Math.max(0, maxBookingId);
    }

    /** Der Export ist zu Ende, gleich wie er ausging. */
    public static void end() {
        upToId = -1;
    }

    public static boolean active() {
        return upToId >= 0;
    }

    /** Darf diese Buchung gerade weder geändert noch gelöscht werden? */
    public static boolean locked(@Nullable Booking b) {
        return b != null && locked(b.id);
    }

    public static boolean locked(long bookingId) {
        long grenze = upToId;
        return grenze >= 0 && bookingId > 0 && bookingId <= grenze;
    }
}
