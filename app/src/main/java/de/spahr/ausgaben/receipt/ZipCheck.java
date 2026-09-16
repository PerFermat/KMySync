package de.spahr.ausgaben.receipt;

/**
 * Ist eine ZIP-Datei überhaupt lesbar – oder wurde sie mittendrin abgeschnitten?
 *
 * <p>Der Beleg-Export schreibt unmittelbar in die gewählte Datei. Räumt Android den Prozess dabei ab,
 * bleibt ein Torso zurück, den kein Packprogramm mehr öffnet. Beim Abbrechen dagegen wird der Strom
 * regulär geschlossen: Die Datei ist dann zwar unvollständig, aber gültig, und die bis dahin
 * gepackten Belege sind etwas wert. Genau diese beiden Fälle muss die App auseinanderhalten können,
 * bevor sie eine Datei wegwirft.</p>
 *
 * <p>Das Kennzeichen ist das <i>End of Central Directory</i> am Dateiende: die vier Bytes
 * {@code 50 4B 05 06}, danach mindestens 18 weitere. Es wird als Letztes geschrieben – wer es findet,
 * hat eine vollständig geschlossene Datei vor sich. Dahinter darf noch ein Kommentar stehen (bis
 * 65535 Bytes), deshalb wird rückwärts gesucht und nicht nur die letzte Position geprüft.</p>
 *
 * <p>Rein und ohne Android: geprüft wird ein Stück vom Ende der Datei, das der Aufrufer besorgt.</p>
 */
public final class ZipCheck {

    /** Die Signatur des End-of-Central-Directory-Eintrags, in Schreibrichtung. */
    private static final byte[] EOCD = {0x50, 0x4B, 0x05, 0x06};

    /** Länge des Eintrags ohne Kommentar – so viele Bytes müssen hinter der Signatur noch folgen. */
    private static final int EOCD_LENGTH = 22;

    /**
     * So viel vom Dateiende genügt: die 22 Bytes des Eintrags und der längstmögliche Kommentar.
     * Wer weniger liest, kann ein gültiges Archiv für kaputt halten.
     */
    public static final int TAIL_BYTES = 65535 + EOCD_LENGTH;

    private ZipCheck() {
    }

    /**
     * Sieht dieses Stück vom <b>Ende</b> einer Datei nach einem vollständig geschlossenen ZIP aus?
     *
     * @param tail die letzten Bytes der Datei – mindestens {@link #TAIL_BYTES}, sonst die ganze Datei
     * @return {@code true}, wenn das End of Central Directory darin steht
     */
    public static boolean looksComplete(byte[] tail) {
        if (tail == null || tail.length < EOCD_LENGTH) {
            return false;
        }
        // Von hinten suchen: Der letzte Treffer ist der richtige, falls die Signatur zufällig auch in
        // den gepackten Daten vorkommt.
        for (int i = tail.length - EOCD_LENGTH; i >= 0; i--) {
            if (matchesAt(tail, i)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAt(byte[] data, int at) {
        for (int k = 0; k < EOCD.length; k++) {
            if (data[at + k] != EOCD[k]) {
                return false;
            }
        }
        return true;
    }
}
