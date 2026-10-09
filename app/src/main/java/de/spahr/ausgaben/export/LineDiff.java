package de.spahr.ausgaben.export;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Zeilenvergleich zweier Texte: welche Zeilen der alten Fassung fehlen in der neuen, welche der neuen
 * gab es in der alten nicht.
 *
 * <p>Gebraucht für den Vergleich der KMyMoney-Datei vor und nach einem Export. Beide Fassungen sind
 * fast gleich – ein paar Dutzend Zeilen von hunderttausend –, und darauf ist das Verfahren
 * zugeschnitten: Der gemeinsame Anfang und Schluss fallen sofort weg, den Rest löst der Algorithmus
 * von Myers, dessen Aufwand mit der Zahl der Unterschiede wächst und nicht mit der Länge der Datei.
 * Ohne Android.</p>
 */
public final class LineDiff {

    /**
     * Jenseits so vieler Unterschiede wird nicht mehr fein verglichen, sondern der strittige Bereich
     * als Ganzes ersetzt gemeldet. Das Ergebnis bleibt richtig, nur nicht mehr das kürzeste – und der
     * Speicherbedarf bleibt begrenzt, falls zwei völlig verschiedene Dateien verglichen werden.
     */
    static final int MAX_UNTERSCHIEDE = 1500;

    /** Je Zeile der alten Fassung: fehlt sie in der neuen? */
    public final boolean[] entfernt;
    /** Je Zeile der neuen Fassung: ist sie hinzugekommen? */
    public final boolean[] hinzu;

    private LineDiff(boolean[] entfernt, boolean[] hinzu) {
        this.entfernt = entfernt;
        this.hinzu = hinzu;
    }

    /**
     * Zerlegt einen Text in Zeilen. Getrennt wird am {@code \n}; ein {@code \r} davor bleibt Teil der
     * Zeile, damit auch ein geändertes Zeilenende als Unterschied auffällt. Endet der Text mit einem
     * Zeilenende, entsteht dahinter keine leere Zeile.
     */
    public static String[] zeilen(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int nl = text.indexOf('\n', start);
            if (nl < 0) {
                out.add(text.substring(start));
                break;
            }
            out.add(text.substring(start, nl));
            start = nl + 1;
        }
        return out.toArray(new String[0]);
    }

    public static LineDiff vergleiche(String[] alt, String[] neu) {
        boolean[] entfernt = new boolean[alt.length];
        boolean[] hinzu = new boolean[neu.length];

        int anfang = 0;
        while (anfang < alt.length && anfang < neu.length && alt[anfang].equals(neu[anfang])) {
            anfang++;
        }
        int endeAlt = alt.length;
        int endeNeu = neu.length;
        while (endeAlt > anfang && endeNeu > anfang && alt[endeAlt - 1].equals(neu[endeNeu - 1])) {
            endeAlt--;
            endeNeu--;
        }
        int n = endeAlt - anfang;
        int m = endeNeu - anfang;
        if (n == 0 || m == 0) {
            for (int i = 0; i < n; i++) {
                entfernt[anfang + i] = true;
            }
            for (int j = 0; j < m; j++) {
                hinzu[anfang + j] = true;
            }
            return new LineDiff(entfernt, hinzu);
        }

        // Zeilen als Nummern: gleiche Zeile, gleiche Nummer – danach wird nur noch mit Zahlen verglichen.
        Map<String, Integer> nummern = new HashMap<>();
        int[] a = new int[n];
        int[] b = new int[m];
        for (int i = 0; i < n; i++) {
            a[i] = nummer(nummern, alt[anfang + i]);
        }
        for (int j = 0; j < m; j++) {
            b[j] = nummer(nummern, neu[anfang + j]);
        }
        if (!myers(a, b, anfang, entfernt, hinzu)) {
            for (int i = 0; i < n; i++) {
                entfernt[anfang + i] = true;
            }
            for (int j = 0; j < m; j++) {
                hinzu[anfang + j] = true;
            }
        }
        return new LineDiff(entfernt, hinzu);
    }

    private static int nummer(Map<String, Integer> nummern, String zeile) {
        Integer nr = nummern.get(zeile);
        if (nr == null) {
            nr = nummern.size();
            nummern.put(zeile, nr);
        }
        return nr;
    }

    /**
     * Der kürzeste Weg von {@code a} nach {@code b} (Myers, „An O(ND) Difference Algorithm").
     *
     * @return {@code false}, wenn es mehr als {@link #MAX_UNTERSCHIEDE} Unterschiede sind
     */
    private static boolean myers(int[] a, int[] b, int versatz, boolean[] entfernt, boolean[] hinzu) {
        int n = a.length;
        int m = b.length;
        int max = Math.min(n + m, MAX_UNTERSCHIEDE);
        // Der Stand je Schritt zum Zurückverfolgen. Schritt d braucht nur die Diagonalen −d … d.
        List<int[]> spur = new ArrayList<>();
        int[] v = new int[2 * max + 2];
        int ziel = -1;
        for (int d = 0; d <= max && ziel < 0; d++) {
            int[] stand = new int[2 * d + 1];
            for (int k = -d; k <= d; k += 2) {
                int x;
                if (k == -d || (k != d && v[max + k - 1] < v[max + k + 1])) {
                    x = v[max + k + 1];          // nach unten: eine Zeile aus b kommt hinzu
                } else {
                    x = v[max + k - 1] + 1;      // nach rechts: eine Zeile aus a entfällt
                }
                int y = x - k;
                while (x < n && y < m && a[x] == b[y]) {
                    x++;
                    y++;
                }
                v[max + k] = x;
                stand[k + d] = x;
                if (x >= n && y >= m) {
                    ziel = d;
                    break;
                }
            }
            spur.add(stand);
        }
        if (ziel < 0) {
            return false;
        }
        // Vom Ziel zurück zum Anfang: jeder Schritt war entweder „entfernt" oder „hinzu".
        int x = n;
        int y = m;
        for (int d = ziel; d > 0; d--) {
            int[] davor = spur.get(d - 1);
            int k = x - y;
            int vorK;
            if (k == -d || (k != d && wert(davor, d - 1, k - 1) < wert(davor, d - 1, k + 1))) {
                vorK = k + 1;
            } else {
                vorK = k - 1;
            }
            int vorX = wert(davor, d - 1, vorK);
            int vorY = vorX - vorK;
            // Erst die gleichen Zeilen zurück, dann der eine Schritt.
            while (x > vorX && y > vorY) {
                x--;
                y--;
            }
            if (x == vorX) {
                hinzu[versatz + vorY] = true;
            } else {
                entfernt[versatz + vorX] = true;
            }
            x = vorX;
            y = vorY;
        }
        return true;
    }

    private static int wert(int[] stand, int d, int k) {
        int i = k + d;
        return i < 0 || i >= stand.length ? -1 : stand[i];
    }
}
