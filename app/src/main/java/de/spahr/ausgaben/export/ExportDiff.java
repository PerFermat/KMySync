package de.spahr.ausgaben.export;

import java.util.ArrayList;
import java.util.List;

/**
 * Was ein Export an der KMyMoney-Datei geändert hat – als Zeilen zum Anzeigen. Entsteht aus dem
 * Vergleich des Stands vor dem Export mit der Datei, die danach vom Server zurückgelesen wurde: nicht
 * das, was die App schreiben wollte, sondern das, was dort jetzt steht.
 *
 * <p>Gezeigt wird jede geänderte Zeile mit {@link #UMGEBUNG} Zeilen davor und danach. Liegen zwischen
 * zwei Änderungen höchstens doppelt so viele unveränderte Zeilen, laufen ihre Umgebungen ineinander und
 * es entsteht ein Block, in dem keine Zeile zweimal steht. Liegen mehr dazwischen, steht dort eine
 * {@link #LUECKE}. Ohne Android.</p>
 */
public final class ExportDiff {

    /** So viele unveränderte Zeilen stehen vor und hinter jeder Änderung. */
    public static final int UMGEBUNG = 10;

    public static final char HINZU = '+';
    public static final char ENTFERNT = '-';
    public static final char GLEICH = ' ';
    /** Hier fehlen Zeilen: zwischen zwei Blöcken liegt unveränderter Text, der nicht gezeigt wird. */
    public static final char LUECKE = '~';

    /** Längere Zeilen werden für die Anzeige gekappt; verglichen wird immer die ganze Zeile. */
    static final int MAX_ZEICHEN = 500;
    /** Mehr Zeilen hebt kein Vergleich auf – ein Export, der so viel ändert, ist keiner mehr. */
    static final int MAX_ZEILEN = 5000;

    /** Eine Zeile der Anzeige. */
    public static final class Zeile {
        public final char art;
        /**
         * Zeilennummer, ab 1: bei {@link #ENTFERNT} die der alten Datei, bei {@link #HINZU} und
         * {@link #GLEICH} die der neuen. Eine {@link #LUECKE} hat keine (0).
         */
        public final int nummer;
        public final String text;

        public Zeile(char art, int nummer, String text) {
            this.art = art;
            this.nummer = nummer;
            this.text = text;
        }
    }

    public final List<Zeile> zeilen = new ArrayList<>();
    /** Zahl der hinzugekommenen und der entfernten Zeilen – aller, nicht nur der gezeigten. */
    public int hinzu;
    public int entfernt;
    /** Zeitpunkt des Exports und Name der Datei. */
    public long zeit;
    public String datei = "";
    /**
     * Die zurückgelesene Datei ist nicht die, die hochgeladen wurde – etwas anderes hat dazwischen
     * geschrieben. Der Vergleich zeigt trotzdem, was jetzt auf dem Server liegt.
     */
    public boolean abweichung;
    /**
     * Die zurückgelesene Datei war nicht lesbar – abgeschnitten oder beschädigt. Die Zeilen zeigen,
     * was sich von ihr noch entpacken ließ.
     */
    public boolean defekt;
    /** Nach einem {@link #defekt}: der Stand von vor dem Export liegt wieder auf dem Server. */
    public boolean wiederhergestellt;
    /** Es waren mehr als {@link #MAX_ZEILEN}; der Rest fehlt. */
    public boolean gekuerzt;
    /**
     * Der Vergleich einer KMyMoney-Datenbank: statt Zeilen die Tabellen ({@link #tabellen}).
     * {@link #hinzu} und {@link #entfernt} zählen dann Sätze.
     */
    public boolean datenbank;
    /** Nur bei {@link #datenbank}: alle Tabellen, auch die unveränderten. */
    public final List<TabellenDiff.Tabelle> tabellen = new ArrayList<>();

    /** Der Vergleich einer Datenbank aus dem, was {@link TabellenDiff} gefunden hat. */
    public static ExportDiff ausTabellen(List<TabellenDiff.Tabelle> tabellen) {
        ExportDiff out = new ExportDiff();
        out.datenbank = true;
        for (TabellenDiff.Tabelle t : tabellen) {
            out.tabellen.add(t);
            out.hinzu += t.hinzu;
            out.entfernt += t.entfernt;
            out.gekuerzt |= t.gekuerzt;
        }
        return out;
    }

    /** Die Tabelle dieses Namens, oder {@code null}. */
    public TabellenDiff.Tabelle tabelle(String name) {
        for (TabellenDiff.Tabelle t : tabellen) {
            if (t.name.equals(name)) {
                return t;
            }
        }
        return null;
    }

    public static ExportDiff von(String alt, String neu) {
        String[] a = LineDiff.zeilen(alt);
        String[] b = LineDiff.zeilen(neu);
        LineDiff d = LineDiff.vergleiche(a, b);

        // Beide Fassungen nebeneinander gelegt, in der Reihenfolge der Datei: erst was an einer Stelle
        // wegfiel, dann was dort hinzukam, dann die nächste gemeinsame Zeile.
        List<Zeile> alle = new ArrayList<>();
        ExportDiff out = new ExportDiff();
        int i = 0;
        int j = 0;
        while (i < a.length || j < b.length) {
            if (i < a.length && d.entfernt[i]) {
                alle.add(new Zeile(ENTFERNT, i + 1, a[i]));
                out.entfernt++;
                i++;
            } else if (j < b.length && d.hinzu[j]) {
                alle.add(new Zeile(HINZU, j + 1, b[j]));
                out.hinzu++;
                j++;
            } else {
                alle.add(new Zeile(GLEICH, j + 1, b[j]));
                i++;
                j++;
            }
        }

        // Sichtbar ist, was höchstens UMGEBUNG Zeilen von einer Änderung entfernt liegt.
        int n = alle.size();
        int[] abstand = new int[n];
        int letzte = Integer.MIN_VALUE / 2;
        for (int k = 0; k < n; k++) {
            if (alle.get(k).art != GLEICH) {
                letzte = k;
            }
            abstand[k] = k - letzte;
        }
        int naechste = Integer.MAX_VALUE / 2;
        for (int k = n - 1; k >= 0; k--) {
            if (alle.get(k).art != GLEICH) {
                naechste = k;
            }
            abstand[k] = Math.min(abstand[k], naechste - k);
        }
        boolean lueckeOffen = false;
        for (int k = 0; k < n; k++) {
            if (abstand[k] > UMGEBUNG) {
                lueckeOffen = true;
                continue;
            }
            if (out.zeilen.size() >= MAX_ZEILEN) {
                out.gekuerzt = true;
                break;
            }
            // Eine Lücke nur zwischen zwei Blöcken – nicht vor dem ersten und nicht hinter dem letzten.
            if (lueckeOffen && !out.zeilen.isEmpty()) {
                out.zeilen.add(new Zeile(LUECKE, 0, ""));
            }
            lueckeOffen = false;
            Zeile z = alle.get(k);
            out.zeilen.add(new Zeile(z.art, z.nummer, anzeige(z.text)));
        }
        return out;
    }

    /** Für die Anzeige: ohne das {@code \r} eines Windows-Zeilenendes, überlange Zeilen gekappt. */
    private static String anzeige(String zeile) {
        String s = zeile.endsWith("\r") ? zeile.substring(0, zeile.length() - 1) : zeile;
        return s.length() > MAX_ZEICHEN ? s.substring(0, MAX_ZEICHEN) + " …" : s;
    }

    // ---- Ablage als Text ----

    private static final String KOPF = "KMYDIFF 1";
    private static final String TRENNER = "---";

    /** Zeilenweise Textform: ein Kopf aus {@code name=wert}, dann je Zeile Art, Nummer, Tab, Text. */
    public String alsText() {
        StringBuilder sb = new StringBuilder();
        sb.append(KOPF).append('\n');
        sb.append("zeit=").append(zeit).append('\n');
        sb.append("datei=").append(datei.replace('\n', ' ')).append('\n');
        sb.append("hinzu=").append(hinzu).append('\n');
        sb.append("entfernt=").append(entfernt).append('\n');
        sb.append("abweichung=").append(abweichung ? 1 : 0).append('\n');
        sb.append("gekuerzt=").append(gekuerzt ? 1 : 0).append('\n');
        sb.append("defekt=").append(defekt ? 1 : 0).append('\n');
        sb.append("wiederhergestellt=").append(wiederhergestellt ? 1 : 0).append('\n');
        sb.append("datenbank=").append(datenbank ? 1 : 0).append('\n');
        sb.append(TRENNER).append('\n');
        for (Zeile z : zeilen) {
            sb.append(z.art).append(z.nummer).append('\t').append(z.text).append('\n');
        }
        // Je Tabelle: Kopf (T), Spalten (S), dann ihre Sätze (+/-), die Werte durch Tab getrennt.
        for (TabellenDiff.Tabelle t : tabellen) {
            sb.append("T\t").append(maske(t.name)).append('\t').append(t.hinzu).append('\t')
                    .append(t.entfernt).append('\t').append(t.gekuerzt ? 1 : 0).append('\n');
            sb.append('S');
            for (String s : t.spalten) {
                sb.append('\t').append(maske(s));
            }
            sb.append('\n');
            for (TabellenDiff.Satz s : t.saetze) {
                sb.append(s.art);
                for (String w : s.werte) {
                    sb.append('\t').append(maske(w));
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** Ein Wert für die Ablage: ohne Tab und Zeilenende, NULL als {@code \N}, überlange gekappt. */
    private static String maske(String w) {
        if (w == null) {
            return "\\N";
        }
        String s = w.length() > MAX_ZEICHEN ? w.substring(0, MAX_ZEICHEN) + " …" : w;
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static String klar(String s) {
        if ("\\N".equals(s)) {
            return null;
        }
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 == s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            sb.append(n == 't' ? '\t' : n == 'n' ? '\n' : n == 'r' ? '\r' : n);
        }
        return sb.toString();
    }

    private static String[] klar(String[] teile, int ab) {
        String[] out = new String[teile.length - ab];
        for (int i = ab; i < teile.length; i++) {
            out[i - ab] = klar(teile[i]);
        }
        return out;
    }

    /**
     * Umkehrung von {@link #alsText}.
     *
     * @param nurKopf die Zeilen selbst auslassen – für die Liste, die nur Datum und Zahlen zeigt
     * @return {@code null}, wenn der Text kein solcher Vergleich ist
     */
    public static ExportDiff ausText(String text, boolean nurKopf) {
        String[] z = LineDiff.zeilen(text);
        if (z.length == 0 || !KOPF.equals(z[0])) {
            return null;
        }
        ExportDiff out = new ExportDiff();
        int k = 1;
        try {
            for (; k < z.length && !TRENNER.equals(z[k]); k++) {
                int gleich = z[k].indexOf('=');
                if (gleich < 0) {
                    continue;
                }
                String name = z[k].substring(0, gleich);
                String wert = z[k].substring(gleich + 1);
                switch (name) {
                    case "zeit":
                        out.zeit = Long.parseLong(wert);
                        break;
                    case "datei":
                        out.datei = wert;
                        break;
                    case "hinzu":
                        out.hinzu = Integer.parseInt(wert);
                        break;
                    case "entfernt":
                        out.entfernt = Integer.parseInt(wert);
                        break;
                    case "abweichung":
                        out.abweichung = "1".equals(wert);
                        break;
                    case "gekuerzt":
                        out.gekuerzt = "1".equals(wert);
                        break;
                    case "defekt":
                        out.defekt = "1".equals(wert);
                        break;
                    case "wiederhergestellt":
                        out.wiederhergestellt = "1".equals(wert);
                        break;
                    case "datenbank":
                        out.datenbank = "1".equals(wert);
                        break;
                    default:
                        // Ein Feld einer späteren Fassung: überlesen.
                }
            }
            if (nurKopf) {
                return out;
            }
            TabellenDiff.Tabelle t = null;
            for (k++; k < z.length; k++) {
                int tab = z[k].indexOf('\t');
                if (out.datenbank) {
                    if (z[k].isEmpty()) {
                        continue;
                    }
                    String[] teile = z[k].split("\t", -1);
                    char art = z[k].charAt(0);
                    if (art == 'T' && teile.length >= 5) {
                        t = new TabellenDiff.Tabelle(klar(teile[1]));
                        t.hinzu = Integer.parseInt(teile[2]);
                        t.entfernt = Integer.parseInt(teile[3]);
                        t.gekuerzt = "1".equals(teile[4]);
                        out.tabellen.add(t);
                    } else if (art == 'S' && t != null) {
                        t.spalten = klar(teile, 1);
                    } else if (t != null) {
                        t.saetze.add(new TabellenDiff.Satz(art, klar(teile, 1)));
                    }
                    continue;
                }
                if (tab < 1) {
                    continue;
                }
                out.zeilen.add(new Zeile(z[k].charAt(0), Integer.parseInt(z[k].substring(1, tab)),
                        z[k].substring(tab + 1)));
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }
}
