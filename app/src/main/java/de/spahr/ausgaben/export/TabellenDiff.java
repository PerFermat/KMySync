package de.spahr.ausgaben.export;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Was sich in einer KMyMoney-Datenbank geändert hat: jede Tabelle der einen Fassung gegen dieselbe
 * Tabelle der anderen, Satz für Satz. Das Gegenstück zum Zeilenvergleich einer .kmy
 * ({@link LineDiff}), und wie er ohne jede Kenntnis davon, was in den Tabellen steht – gelesen wird,
 * was {@code sqlite_master} nennt, verglichen werden die Werte als Text.
 *
 * <p>Ein Satz, der in beiden Fassungen mit denselben Werten steht, gilt als unverändert. Ein
 * geänderter Satz erscheint deshalb zweimal: in seiner alten Fassung als entfernt, in seiner neuen als
 * hinzugefügt; sortiert nach dem Schlüssel der Tabelle stehen die beiden untereinander.</p>
 */
public final class TabellenDiff {

    public static final char HINZU = '+';
    public static final char ENTFERNT = '-';

    /** Mehr Sätze je Tabelle werden nicht aufgehoben; gezählt werden alle. */
    static final int MAX_SAETZE = 500;

    /** Ein hinzugekommener oder entfernter Satz; ein Wert {@code null} ist SQL-NULL. */
    public static final class Satz {
        public final char art;
        public final String[] werte;

        public Satz(char art, String[] werte) {
            this.art = art;
            this.werte = werte;
        }
    }

    /** Eine Tabelle mit dem, was sich in ihr geändert hat. */
    public static final class Tabelle {
        public final String name;
        public String[] spalten = new String[0];
        /** Zahl der hinzugekommenen und der entfernten Sätze – aller, nicht nur der aufgehobenen. */
        public int hinzu;
        public int entfernt;
        /** Es waren mehr als {@link #MAX_SAETZE}; der Rest fehlt. */
        public boolean gekuerzt;
        public final List<Satz> saetze = new ArrayList<>();

        public Tabelle(String name) {
            this.name = name;
        }

        public boolean geaendert() {
            return hinzu > 0 || entfernt > 0;
        }
    }

    private TabellenDiff() {
    }

    // ---- Der Vergleich selbst, ohne Android ----

    private static final char TRENNER = '\u0001';
    private static final String NULL = "\u0000";

    private static String schluessel(String[] werte) {
        StringBuilder sb = new StringBuilder();
        for (String w : werte) {
            sb.append(w == null ? NULL : w).append(TRENNER);
        }
        return sb.toString();
    }

    private static String[] werte(String schluessel, int n) {
        String[] out = new String[n];
        int von = 0;
        for (int i = 0; i < n; i++) {
            int bis = schluessel.indexOf(TRENNER, von);
            if (bis < 0) {
                break;   // der Satz hat weniger Spalten als die Tabelle der anderen Fassung
            }
            String w = schluessel.substring(von, bis);
            out[i] = NULL.equals(w) ? null : w;
            von = bis + 1;
        }
        return out;
    }

    /**
     * @param spalten die Spalten der Tabelle
     * @param ordnung Spalten, nach denen die gefundenen Sätze sortiert werden (der Primärschlüssel);
     *                leer = nach allen
     * @param alt     die Sätze der alten Fassung, {@code neu} die der neuen
     */
    static Tabelle vergleiche(String name, String[] spalten, int[] ordnung,
                              Iterable<String[]> alt, Iterable<String[]> neu) {
        Tabelle t = new Tabelle(name);
        t.spalten = spalten;
        // Wie oft jeder Satz in der alten Fassung steht; die neue zieht davon ab.
        Map<String, int[]> offen = new HashMap<>();
        for (String[] w : alt) {
            int[] n = offen.get(schluessel(w));
            if (n == null) {
                offen.put(schluessel(w), new int[]{1});
            } else {
                n[0]++;
            }
        }
        List<Satz> alle = new ArrayList<>();
        for (String[] w : neu) {
            int[] n = offen.get(schluessel(w));
            if (n != null && n[0] > 0) {
                n[0]--;
            } else {
                alle.add(new Satz(HINZU, w));
                t.hinzu++;
            }
        }
        for (Map.Entry<String, int[]> e : offen.entrySet()) {
            for (int i = 0; i < e.getValue()[0]; i++) {
                alle.add(new Satz(ENTFERNT, werte(e.getKey(), spalten.length)));
                t.entfernt++;
            }
        }
        Collections.sort(alle, (a, b) -> {
            int c = ordne(a.werte, b.werte, ordnung);
            if (c == 0) {
                // Bei gleichem Schlüssel erst die alte Fassung, dann die neue.
                c = Character.compare(b.art, a.art);
            }
            return c != 0 || ordnung.length == 0 ? c : ordne(a.werte, b.werte, new int[0]);
        });
        if (alle.size() > MAX_SAETZE) {
            t.gekuerzt = true;
            alle = alle.subList(0, MAX_SAETZE);
        }
        t.saetze.addAll(alle);
        return t;
    }

    private static int ordne(String[] a, String[] b, int[] ordnung) {
        int n = ordnung.length > 0 ? ordnung.length : Math.min(a.length, b.length);
        for (int k = 0; k < n; k++) {
            int i = ordnung.length > 0 ? ordnung[k] : k;
            if (i >= a.length || i >= b.length) {
                continue;
            }
            String x = a[i] == null ? "" : a[i];
            String y = b[i] == null ? "" : b[i];
            // Zahlen der Größe nach: Die kürzere Ziffernfolge ist die kleinere.
            int c = zahl(x) && zahl(y) && x.length() != y.length()
                    ? Integer.compare(x.length(), y.length()) : x.compareTo(y);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static boolean zahl(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    // ---- Lesen der beiden Datenbanken ----

    /** Alle Tabellen beider Fassungen, nach Namen sortiert; auch die unveränderten. */
    public static List<Tabelle> von(Context context, byte[] alt, byte[] neu) throws IOException {
        File a = KmySqlite.zwischendatei(context, alt);
        File b = null;
        SQLiteDatabase dbA = null;
        SQLiteDatabase dbB = null;
        try {
            b = KmySqlite.zwischendatei(context, neu);
            dbA = oeffne(a);
            dbB = oeffne(b);
            List<String> inA = tabellen(dbA);
            List<String> inB = tabellen(dbB);
            TreeSet<String> namen = new TreeSet<>(inA);
            namen.addAll(inB);
            List<Tabelle> out = new ArrayList<>();
            for (String name : namen) {
                String[] spaltenA = inA.contains(name) ? spalten(dbA, name, null) : new String[0];
                List<Integer> pk = new ArrayList<>();
                String[] spaltenB = inB.contains(name) ? spalten(dbB, name, pk) : new String[0];
                if (!inB.contains(name)) {
                    spalten(dbA, name, pk);
                }
                String[] spalten = spaltenB.length > 0 ? spaltenB : spaltenA;
                int[] ordnung = new int[pk.size()];
                for (int i = 0; i < ordnung.length; i++) {
                    ordnung[i] = pk.get(i);
                }
                // Hat die Tabelle in beiden Fassungen andere Spalten, lassen sich die Sätze nicht
                // nebeneinanderlegen: dann gilt die alte als ganz entfernt, die neue als ganz neu.
                boolean gleicheSpalten = java.util.Arrays.equals(spaltenA, spaltenB)
                        || spaltenA.length == 0 || spaltenB.length == 0;
                try (Saetze sa = new Saetze(dbA, inA.contains(name) ? name : null,
                        gleicheSpalten ? spalten.length : -1);
                     Saetze sb = new Saetze(dbB, inB.contains(name) ? name : null, spalten.length)) {
                    out.add(vergleiche(name, spalten, ordnung, sa, sb));
                }
            }
            return out;
        } catch (android.database.SQLException e) {
            throw new IOException("KMyMoney-Datenbank nicht lesbar: " + e.getMessage(), e);
        } finally {
            if (dbA != null) {
                dbA.close();
            }
            if (dbB != null) {
                dbB.close();
            }
            KmySqlite.entfernen(a);
            KmySqlite.entfernen(b);
        }
    }

    private static SQLiteDatabase oeffne(File f) {
        return SQLiteDatabase.openDatabase(f.getPath(), null,
                SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
    }

    private static String q(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private static List<String> tabellen(SQLiteDatabase db) {
        List<String> out = new ArrayList<>();
        try (Cursor c = db.rawQuery("select name from sqlite_master where type='table'"
                + " and name not like 'sqlite_%' order by name", null)) {
            while (c.moveToNext()) {
                out.add(c.getString(0));
            }
        }
        return out;
    }

    /** Die Spaltennamen; in {@code pk} landen die Stellen des Primärschlüssels in seiner Reihenfolge. */
    private static String[] spalten(SQLiteDatabase db, String tabelle, List<Integer> pk) {
        List<String> out = new ArrayList<>();
        Map<Integer, Integer> stellen = new java.util.TreeMap<>();
        try (Cursor c = db.rawQuery("pragma table_info(" + q(tabelle) + ")", null)) {
            int name = c.getColumnIndexOrThrow("name");
            int teil = c.getColumnIndexOrThrow("pk");
            while (c.moveToNext()) {
                if (c.getInt(teil) > 0) {
                    stellen.put(c.getInt(teil), out.size());
                }
                out.add(c.getString(name));
            }
        }
        if (pk != null) {
            pk.clear();
            pk.addAll(stellen.values());
        }
        return out.toArray(new String[0]);
    }

    /** Die Sätze einer Tabelle, einer nach dem anderen aus dem Zeiger – nie alle zugleich im Speicher. */
    private static final class Saetze implements Iterable<String[]>, AutoCloseable {
        private final Cursor c;
        private final int breite;

        /**
         * @param tabelle {@code null} = die Tabelle gibt es in dieser Fassung nicht
         * @param breite  Zahl der Spalten des Vergleichs; {@code -1} = die Sätze passen nicht dazu und
         *                werden mit einer Spalte mehr geliefert, damit keiner als gleich gilt
         */
        Saetze(SQLiteDatabase db, String tabelle, int breite) {
            this.c = tabelle == null ? null : db.rawQuery("select * from " + q(tabelle), null);
            this.breite = breite;
        }

        @Override
        public Iterator<String[]> iterator() {
            return new Iterator<String[]>() {
                @Override
                public boolean hasNext() {
                    return c != null && !c.isLast() && c.getCount() > 0;
                }

                @Override
                public String[] next() {
                    c.moveToNext();
                    int n = c.getColumnCount();
                    String[] w = new String[breite < 0 ? n + 1 : n];
                    for (int i = 0; i < n; i++) {
                        switch (c.getType(i)) {
                            case Cursor.FIELD_TYPE_NULL:
                                w[i] = null;
                                break;
                            case Cursor.FIELD_TYPE_BLOB:
                                w[i] = hex(c.getBlob(i));
                                break;
                            default:
                                w[i] = c.getString(i);
                        }
                    }
                    if (breite < 0) {
                        w[n] = "alt";
                    }
                    return w;
                }
            };
        }

        @Override
        public void close() {
            if (c != null) {
                c.close();
            }
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder("x'");
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return sb.append('\'').toString();
    }
}
