package de.spahr.ausgaben.export;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ob ein Konto in KMyMoney geschlossen ist: KMyMoney vermerkt es als Schlüsselpaar am Konto,
 * {@code <PAIR key="mm-closed" value="yes"/>} im {@code KEYVALUEPAIRS}-Block des {@code ACCOUNT}.
 *
 * <p>Diese Klasse setzt und entfernt das Paar im Text, so wie KMyMoney selbst schriebe: Die Paare
 * stehen nach dem Schlüssel sortiert; fehlt der Block, kommt er ans Ende des Kontos, hinter die
 * Unterkonten; wird er leer, verschwindet er, und ein Konto ohne Inhalt schließt sich selbst
 * ({@code <ACCOUNT …/>}). Die Einrückung kommt aus dem Konto selbst – KMyMoney 5.2 rückt zwei
 * Leerzeichen je Ebene ein, das Abbild einer Datenbank ({@link KmySqlite}) eines.</p>
 *
 * <p>Ohne Android.</p>
 */
public final class KmyKontoStatus {

    /** Der Schlüssel, unter dem KMyMoney „geschlossen" führt. */
    public static final String SCHLUESSEL = "mm-closed";

    private static final Pattern PAAR = Pattern.compile(
            "<PAIR\\s+key=\"" + SCHLUESSEL + "\"\\s+value=\"([^\"]*)\"\\s*/>");

    private KmyKontoStatus() {
    }

    /** Ob dieser Konto-Block das Paar trägt (mit dem Wert „yes", wie KMyMoney ihn schreibt). */
    public static boolean geschlossen(String block) {
        Matcher m = PAAR.matcher(block);
        return m.find() && "yes".equalsIgnoreCase(m.group(1).trim());
    }

    /**
     * Setzt den Zustand in einem ganzen Konto-Block. {@code einzug} ist der Text vor {@code <ACCOUNT}
     * auf seiner Zeile – daraus folgt die Einrückung des Inhalts.
     */
    public static String setze(String block, String einzug, boolean geschlossen) {
        if (geschlossen(block) == geschlossen) {
            return block;
        }
        return geschlossen ? schliessen(block, einzug) : oeffnen(block);
    }

    private static String schliessen(String block, String einzug) {
        // ACCOUNT steht auf der zweiten Ebene (KMYMONEY-FILE › ACCOUNTS › ACCOUNT): sein Einzug ist zwei Schritte.
        String schritt = !einzug.isEmpty() && einzug.length() % 2 == 0
                ? einzug.substring(einzug.length() / 2) : " ";
        String paar = "<PAIR key=\"" + SCHLUESSEL + "\" value=\"yes\"/>";
        if (block.endsWith("/>") && block.indexOf('>') == block.length() - 1) {
            // <ACCOUNT …/> bekommt Inhalt
            String kopf = block.substring(0, block.length() - 2).replaceAll("\\s+$", "");
            return kopf + ">\n" + einzug + schritt + "<KEYVALUEPAIRS>\n" + einzug + schritt + schritt + paar
                    + "\n" + einzug + schritt + "</KEYVALUEPAIRS>\n" + einzug + "</ACCOUNT>";
        }
        int kvp = block.indexOf("<KEYVALUEPAIRS>");
        if (kvp < 0) {
            int schluss = block.lastIndexOf("</ACCOUNT>");
            int zeile = block.lastIndexOf('\n', schluss) + 1;
            return block.substring(0, zeile) + einzug + schritt + "<KEYVALUEPAIRS>\n" + einzug + schritt + schritt
                    + paar + "\n" + einzug + schritt + "</KEYVALUEPAIRS>\n" + block.substring(zeile);
        }
        // In den vorhandenen Block, an die Stelle, die die Sortierung nach dem Schlüssel verlangt.
        int ende = block.indexOf("</KEYVALUEPAIRS>", kvp);
        Matcher m = Pattern.compile("([ \\t]*)<PAIR\\s+key=\"([^\"]*)\"[^>]*/>").matcher(block);
        m.region(kvp, ende);
        String paarEinzug = null;
        int vor = -1;
        while (m.find()) {
            paarEinzug = m.group(1);
            if (vor < 0 && SCHLUESSEL.compareTo(unescape(m.group(2))) < 0) {
                vor = m.start();
            }
        }
        if (paarEinzug == null) {
            paarEinzug = einzug + schritt + schritt;
        }
        if (vor < 0) {
            vor = block.lastIndexOf('\n', ende) + 1;
        }
        return block.substring(0, vor) + paarEinzug + paar + "\n" + block.substring(vor);
    }

    private static String oeffnen(String block) {
        Matcher m = Pattern.compile("[ \\t]*" + PAAR.pattern() + "[ \\t]*\\r?\\n?").matcher(block);
        if (!m.find()) {
            return block;
        }
        String ohne = block.substring(0, m.start()) + block.substring(m.end());
        // Leerer Block fällt weg – samt seiner Zeilen.
        ohne = ohne.replaceFirst("[ \\t]*<KEYVALUEPAIRS>\\s*</KEYVALUEPAIRS>[ \\t]*\\r?\\n?", "");
        // Ohne Inhalt schließt sich das Konto selbst.
        Matcher leer = Pattern.compile("^(<ACCOUNT\\b[^>]*?)>\\s*</ACCOUNT>$", Pattern.DOTALL).matcher(ohne);
        if (leer.matches()) {
            return leer.group(1) + "/>";
        }
        return ohne;
    }

    /** Der Block ohne das Paar – zum Vergleich in der Selbstprüfung: Sonst darf sich nichts ändern. */
    static String ohnePaar(String block) {
        return oeffnen(block);
    }

    /**
     * Setzt den Zustand mehrerer Konten im ganzen Dokument. Gesucht wird nur im {@code ACCOUNTS}-Block;
     * dasselbe Tag steht auch in jedem Budget. Unbekannte ids bleiben unberührt.
     *
     * @return das Dokument; {@code geaendert} bekommt die ids, deren Block sich wirklich geändert hat
     */
    public static String setze(String xml, Map<String, Boolean> zustand, java.util.Set<String> geaendert) {
        int anfang = xml.indexOf("<ACCOUNTS");
        int ende = xml.indexOf("</ACCOUNTS>", anfang);
        if (anfang < 0 || ende < 0 || zustand.isEmpty()) {
            return xml;
        }
        StringBuilder out = new StringBuilder(xml.length() + 256);
        out.append(xml, 0, anfang);
        String bereich = xml.substring(anfang, ende);
        Matcher m = Pattern.compile("<ACCOUNT\\s[^>]*?\\bid=\"([^\"]*)\"").matcher(bereich);
        int pos = 0;
        while (m.find(pos)) {
            int start = m.start();
            int tagEnde = bereich.indexOf('>', start);
            boolean selbst = bereich.charAt(tagEnde - 1) == '/';
            int blockEnde = selbst ? tagEnde + 1
                    : bereich.indexOf("</ACCOUNT>", tagEnde) + "</ACCOUNT>".length();
            String id = m.group(1);
            Boolean soll = zustand.get(id);
            out.append(bereich, pos, start);
            String block = bereich.substring(start, blockEnde);
            if (soll != null) {
                int zeile = bereich.lastIndexOf('\n', start) + 1;
                String neu = setze(block, bereich.substring(zeile, start), soll);
                if (!neu.equals(block)) {
                    geaendert.add(id);
                }
                block = neu;
            }
            out.append(block);
            pos = blockEnde;
        }
        out.append(bereich, pos, bereich.length());
        out.append(xml, ende, xml.length());
        return out.toString();
    }

    private static String unescape(String s) {
        return s.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }
}
