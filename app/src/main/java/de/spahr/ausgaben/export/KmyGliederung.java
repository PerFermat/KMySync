package de.spahr.ausgaben.export;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Zerlegt den Rohtext einer KMyMoney-Datei in seine Elemente, ohne ihn zu deuten – Grundlage für den
 * Zeichen-für-Zeichen-Vergleich in {@link KmyExportCheck}.
 *
 * <p>Ein XML-Parser liefert Namen und Attribute, aber nicht die Stelle im Text; der Vergleich „dieser
 * Block steht unverändert da" braucht genau die. Die Textmuster des {@link KmyExporter} kommen dafür
 * bewusst nicht in Frage: Eine Prüfung, die mit denselben Mustern sucht wie der Geprüfte, übersieht
 * dieselben Sonderfälle. Hier wird stattdessen Zeichen für Zeichen gelesen – Anführungszeichen,
 * Kommentare, Verarbeitungsanweisungen, DOCTYPE und CDATA werden beachtet.</p>
 *
 * <p>Ohne Android und ohne Zustand; die Wohlgeformtheit prüft nicht diese Klasse, sondern der Parser
 * in {@link KmyExportCheck}. Was hier nicht aufgeht, wird als {@link Fehler} gemeldet.</p>
 */
final class KmyGliederung {

    /** Der Text lässt sich nicht in Elemente zerlegen. */
    static final class Fehler extends Exception {
        Fehler(String grund) {
            super(grund);
        }
    }

    /** Ein Element samt seiner Stelle im Text. */
    static final class Element {
        private final String quelle;
        final String name;
        /** Beginn des öffnenden Tags und Ende hinter dem schließenden. */
        final int start;
        final int ende;
        /** Ende des öffnenden Tags (hinter dem {@code >}). */
        private final int tagEnde;
        /** Beginn des schließenden Tags; bei selbstschließenden gleich {@link #tagEnde}. */
        private final int innenEnde;
        /** Selbstschließend ({@code <X/>}). */
        final boolean leer;

        Element(String quelle, String name, int start, int tagEnde, int innenEnde, int ende,
                boolean leer) {
            this.quelle = quelle;
            this.name = name;
            this.start = start;
            this.tagEnde = tagEnde;
            this.innenEnde = innenEnde;
            this.ende = ende;
            this.leer = leer;
        }

        /** Das ganze Element als Text. */
        String text() {
            return quelle.substring(start, ende);
        }

        /** Das öffnende Tag als Text. */
        String oeffnung() {
            return quelle.substring(start, tagEnde);
        }

        /** Zeichengleich mit {@code anderes}? Ohne die Texte dafür herauszukopieren. */
        boolean gleich(Element anderes) {
            int laenge = ende - start;
            return laenge == anderes.ende - anderes.start
                    && quelle.regionMatches(start, anderes.quelle, anderes.start, laenge);
        }

        /** Die Attribute des öffnenden Tags in Dateireihenfolge, Werte so, wie sie dastehen. */
        Map<String, String> attribute() throws Fehler {
            Map<String, String> out = new LinkedHashMap<>();
            int i = start + 1 + name.length();
            int grenze = tagEnde - 1;
            while (true) {
                while (i < grenze && Character.isWhitespace(quelle.charAt(i))) {
                    i++;
                }
                if (i >= grenze || quelle.charAt(i) == '/') {
                    return out;
                }
                int namensStart = i;
                while (i < grenze && quelle.charAt(i) != '=' && !Character.isWhitespace(quelle.charAt(i))) {
                    i++;
                }
                String attribut = quelle.substring(namensStart, i);
                while (i < grenze && Character.isWhitespace(quelle.charAt(i))) {
                    i++;
                }
                if (i >= grenze || quelle.charAt(i) != '=') {
                    throw new Fehler("Attribut " + attribut + " in <" + name + "> ohne Wert");
                }
                i++;
                while (i < grenze && Character.isWhitespace(quelle.charAt(i))) {
                    i++;
                }
                char quote = i < grenze ? quelle.charAt(i) : 0;
                if (quote != '"' && quote != '\'') {
                    throw new Fehler("Attribut " + attribut + " in <" + name + "> ohne Anführungszeichen");
                }
                int schluss = quelle.indexOf(quote, i + 1);
                if (schluss < 0 || schluss >= grenze) {
                    throw new Fehler("Attribut " + attribut + " in <" + name + "> nicht abgeschlossen");
                }
                out.put(attribut, quelle.substring(i + 1, schluss));
                i = schluss + 1;
            }
        }

        /**
         * Steht irgendwo in diesem Element ein wörtlicher Zeilenumbruch oder Tabulator <b>innerhalb
         * eines Attributwerts</b>? Erlaubt wäre das, aber ein XML-Leser macht daraus ein Leerzeichen –
         * der Wert käme anders an, als er geschrieben wurde. Richtig ist {@code &#xa;}.
         */
        boolean umbruchImAttribut() {
            boolean imTag = false;
            char quote = 0;
            for (int i = start; i < ende; i++) {
                char c = quelle.charAt(i);
                if (quote != 0) {
                    if (c == quote) {
                        quote = 0;
                    } else if (c == '\n' || c == '\r' || c == '\t') {
                        return true;
                    }
                } else if (imTag) {
                    if (c == '"' || c == '\'') {
                        quote = c;
                    } else if (c == '>') {
                        imTag = false;
                    }
                } else if (c == '<') {
                    imTag = true;
                }
            }
            return false;
        }

        /** Das Attribut {@code id}, oder {@code null}. */
        String id() throws Fehler {
            return attribute().get("id");
        }

        /** Die direkten Kindelemente und was zwischen ihnen steht. */
        Inhalt inhalt() throws Fehler {
            return kinderIn(quelle, tagEnde, innenEnde);
        }
    }

    /** Die direkten Kinder eines Elements. */
    static final class Inhalt {
        final List<Element> kinder = new ArrayList<>();
        /**
         * Der Text vor, zwischen und hinter den Kindern – immer ein Eintrag mehr als Kinder. Kommentare
         * und Verarbeitungsanweisungen zählen dazu.
         */
        final List<String> luecken = new ArrayList<>();

        /** Alles aus {@link #luecken}, was kein Leerraum ist. */
        String rest() {
            StringBuilder sb = new StringBuilder();
            for (String luecke : luecken) {
                for (int i = 0; i < luecke.length(); i++) {
                    char c = luecke.charAt(i);
                    if (!Character.isWhitespace(c)) {
                        sb.append(c);
                    }
                }
            }
            return sb.toString();
        }

        /** Das erste Kind dieses Namens, oder {@code null}. */
        Element kind(String name) {
            for (Element e : kinder) {
                if (e.name.equals(name)) {
                    return e;
                }
            }
            return null;
        }
    }

    /** Alles vor dem Wurzelelement: XML-Deklaration, DOCTYPE, Kommentare. */
    final String kopf;
    final Element wurzel;
    /** Alles hinter dem Wurzelelement. */
    final String schluss;

    private KmyGliederung(String kopf, Element wurzel, String schluss) {
        this.kopf = kopf;
        this.wurzel = wurzel;
        this.schluss = schluss;
    }

    static KmyGliederung lesen(String xml) throws Fehler {
        int i = 0;
        while (true) {
            int lt = xml.indexOf('<', i);
            if (lt < 0) {
                throw new Fehler("kein Wurzelelement");
            }
            if (xml.startsWith("<?", lt)) {
                i = hinter(xml, "?>", lt + 2);
            } else if (xml.startsWith("<!--", lt)) {
                i = hinter(xml, "-->", lt + 4);
            } else if (xml.startsWith("<!", lt)) {
                i = endeDerDeklaration(xml, lt);
            } else {
                Element wurzel = element(xml, lt);
                return new KmyGliederung(xml.substring(0, lt), wurzel, xml.substring(wurzel.ende));
            }
        }
    }

    /** Die Stelle hinter dem nächsten {@code marke} ab {@code ab}. */
    private static int hinter(String xml, String marke, int ab) throws Fehler {
        int idx = xml.indexOf(marke, ab);
        if (idx < 0) {
            throw new Fehler("„" + marke + "“ fehlt");
        }
        return idx + marke.length();
    }

    /** Ende einer {@code <!DOCTYPE …>}-Deklaration, auch mit interner Teilmenge in eckigen Klammern. */
    private static int endeDerDeklaration(String xml, int start) throws Fehler {
        int klammern = 0;
        char quote = 0;
        for (int i = start + 2; i < xml.length(); i++) {
            char c = xml.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '[') {
                klammern++;
            } else if (c == ']') {
                klammern--;
            } else if (c == '>' && klammern <= 0) {
                return i + 1;
            }
        }
        throw new Fehler("Deklaration nicht abgeschlossen");
    }

    /** Ende des Tags, das bei {@code start} beginnt – ein {@code >} in einem Attributwert zählt nicht. */
    private static int endeDesTags(String xml, int start) throws Fehler {
        char quote = 0;
        for (int i = start + 1; i < xml.length(); i++) {
            char c = xml.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i + 1;
            }
        }
        throw new Fehler("Tag nicht abgeschlossen");
    }

    /** Hinter Kommentar, CDATA oder Verarbeitungsanweisung bei {@code lt}; {@code -1}, wenn dort keins steht. */
    private static int hinterBeiwerk(String xml, int lt) throws Fehler {
        if (xml.startsWith("<!--", lt)) {
            return hinter(xml, "-->", lt + 4);
        }
        if (xml.startsWith("<![CDATA[", lt)) {
            return hinter(xml, "]]>", lt + 9);
        }
        if (xml.startsWith("<?", lt)) {
            return hinter(xml, "?>", lt + 2);
        }
        return -1;
    }

    /** Das Element, dessen öffnendes Tag bei {@code start} beginnt. */
    private static Element element(String xml, int start) throws Fehler {
        int n = start + 1;
        while (n < xml.length() && !Character.isWhitespace(xml.charAt(n))
                && xml.charAt(n) != '>' && xml.charAt(n) != '/') {
            n++;
        }
        String name = xml.substring(start + 1, n);
        if (name.isEmpty()) {
            throw new Fehler("Tag ohne Namen");
        }
        int tagEnde = endeDesTags(xml, start);
        if (xml.charAt(tagEnde - 2) == '/') {
            return new Element(xml, name, start, tagEnde, tagEnde, tagEnde, true);
        }
        int tiefe = 1;
        int i = tagEnde;
        while (true) {
            int lt = xml.indexOf('<', i);
            if (lt < 0) {
                throw new Fehler("<" + name + "> nicht geschlossen");
            }
            int beiwerk = hinterBeiwerk(xml, lt);
            if (beiwerk >= 0) {
                i = beiwerk;
            } else if (xml.startsWith("</", lt)) {
                int gt = xml.indexOf('>', lt);
                if (gt < 0) {
                    throw new Fehler("<" + name + "> nicht geschlossen");
                }
                if (--tiefe == 0) {
                    return new Element(xml, name, start, tagEnde, lt, gt + 1, false);
                }
                i = gt + 1;
            } else {
                int ende = endeDesTags(xml, lt);
                if (xml.charAt(ende - 2) != '/') {
                    tiefe++;
                }
                i = ende;
            }
        }
    }

    private static Inhalt kinderIn(String xml, int von, int bis) throws Fehler {
        Inhalt out = new Inhalt();
        int i = von;
        int luecke = von;
        while (true) {
            int lt = xml.indexOf('<', i);
            if (lt < 0 || lt >= bis) {
                out.luecken.add(xml.substring(luecke, bis));
                return out;
            }
            int beiwerk = hinterBeiwerk(xml, lt);
            if (beiwerk >= 0) {
                i = beiwerk;
                continue;
            }
            if (xml.startsWith("</", lt)) {
                throw new Fehler("unerwartetes schließendes Tag");
            }
            Element e = element(xml, lt);
            out.luecken.add(xml.substring(luecke, lt));
            out.kinder.add(e);
            i = e.ende;
            luecke = e.ende;
        }
    }
}
