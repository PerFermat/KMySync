package de.spahr.ausgaben.export;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Prüft die fertig erzeugte Datei, bevor sie den Server erreicht.
 *
 * <p>{@link KmyExporter} ändert die XML mit Textmustern, nicht über einen Baum – schnell und
 * formatgetreu, aber ein Sonderfall, den ein Muster nicht vorhergesehen hat, ergäbe eine Datei, die
 * KMyMoney nicht mehr öffnet. Die Sicherung davor rettet die Daten, aber erst, wenn jemand den Schaden
 * bemerkt. Deshalb wird hier jede neue Fassung einmal vollständig durchgelesen und mit der alten
 * verglichen; schlägt etwas fehl, bleibt der Server unberührt.</p>
 *
 * <p>Die Regeln folgen dem, was ein Export überhaupt darf: Er legt Buchungen und Empfänger an, ändert
 * und löscht Buchungen und stellt Planungen weiter – und sagt in {@link KmyAenderungen} an, welche.
 * Alles, was dort nicht steht, muss Zeichen für Zeichen stehen bleiben: jede andere Buchung, jeder
 * Empfänger, jede Planung, und Konten, Institute, Wertpapiere, Kurse und Budgets als Ganzes.</p>
 *
 * <p>Geprüft wird das XML selbst, nicht mit den Textmustern des Exporters: {@link KmyGliederung}
 * zerlegt den Rohtext, der Parser liest den Inhalt.</p>
 */
public final class KmyExportCheck {

    /** Die Selbstprüfung hat angeschlagen; die Nachricht nennt die verletzte Regel. */
    public static class Failed extends IOException {
        public Failed(String reason) {
            super(reason);
        }

        Failed(String reason, Throwable cause) {
            super(reason, cause);
        }
    }

    /** Behälter und das Element, das darin gezählt wird. */
    private static final String[][] GEZAEHLT = {
            {"ACCOUNTS", "ACCOUNT"},
            {"INSTITUTIONS", "INSTITUTION"},
            {"SECURITIES", "SECURITY"},
            {"PRICES", "PRICEPAIR"},
            {"SCHEDULES", "SCHEDULED_TX"},
            {"BUDGETS", "BUDGET"},
            {"PAYEES", "PAYEE"},
            {"TRANSACTIONS", "TRANSACTION"},
    };

    private KmyExportCheck() {
    }

    /** Ein Split des Hauptbuchs mit allem, was an ihm steht. */
    static final class Split {
        final Map<String, String> attribute = new LinkedHashMap<>();
        /** Die ids seiner {@code <TAG>}-Kindelemente. */
        final List<String> stichwoerter = new ArrayList<>();

        String von(String name) {
            String v = attribute.get(name);
            return v == null ? "" : v;
        }
    }

    /** Eine Transaktion des Hauptbuchs. */
    static final class Buchung {
        final Map<String, String> attribute = new LinkedHashMap<>();
        final List<Split> splits = new ArrayList<>();

        String id() {
            return attribute.get("id");
        }
    }

    /** Das Gelesene einer Fassung. */
    static final class Stand {
        final Map<String, Integer> anzahl = new HashMap<>();
        /** {@code <TRANSACTIONS count="…">}; {@code -1}, wenn nicht angegeben. */
        int kopfzahl = -1;
        /** Konto-id → Summe aller Split-{@code value} im Hauptbuch. */
        final Map<String, KmyBruch> saldo = new HashMap<>();
        /**
         * Splits, deren {@code value} keine Zahl ist („Konto Wert“ → Anzahl). In einer fremden Datei
         * kann so etwas stehen; solange es vorher und nachher gleich viele sind, hat der Export nichts
         * daran getan.
         */
        final Map<String, Integer> unlesbar = new HashMap<>();
        /** Die Transaktionen, nach denen gefragt war, im Einzelnen (id → Transaktion). */
        final Map<String, Buchung> genau = new HashMap<>();
        /** Die Transaktionen mit mindestens einem abgeglichenen Split (id → Transaktion). */
        final Map<String, Buchung> abgeglichen = new LinkedHashMap<>();
        /** Konto-id → {@code {type, parentaccount, currency}} aus dem {@code ACCOUNTS}-Block. */
        final Map<String, String[]> konten = new HashMap<>();
        /** Die ids aller {@code PAYEE}. */
        final Set<String> empfaenger = new HashSet<>();
        /**
         * Die Währungen, die die Datei kennt: der {@code CURRENCIES}-Block, die Währungen ihrer Konten
         * und die Basiswährung.
         */
        final Set<String> waehrungen = new HashSet<>();

        int von(String element) {
            Integer n = anzahl.get(element);
            return n == null ? 0 : n;
        }
    }

    /**
     * Prüft die neue Fassung gegen die alte.
     *
     * @param erwartet was der Export ändern wollte; alles andere muss unberührt sein
     * @param gepackt  die Bytes, die tatsächlich hochgeladen werden sollen
     */
    public static void pruefen(String alt, String neu, byte[] gepackt, KmyAenderungen erwartet)
            throws Failed {
        Set<String> angesagt = new HashSet<>();
        for (KmyAenderungen.Absicht ab : erwartet.transaktionen()) {
            angesagt.add(ab.txId);
        }
        Stand vorher = erfassen(alt, "alte Datei", angesagt);
        // Was vorher abgeglichen war, wird in der neuen Fassung im Einzelnen gebraucht – auch dann,
        // wenn es dort gar nicht mehr als abgeglichen dasteht.
        Set<String> genau = new HashSet<>(angesagt);
        genau.addAll(vorher.abgeglichen.keySet());
        Stand nachher = erfassen(neu, "neue Datei", genau);

        KmyGliederung a;
        KmyGliederung n;
        try {
            a = KmyGliederung.lesen(alt);
            n = KmyGliederung.lesen(neu);
            unberuehrtesBleibt(a, n, vorher, nachher, erwartet);
        } catch (KmyGliederung.Fehler e) {
            throw new Failed("Datei nicht zerlegbar (" + e.getMessage() + ")", e);
        }
        saldenStimmen(vorher, nachher, erwartet);
        for (KmyAenderungen.Absicht ab : erwartet.transaktionen()) {
            Buchung neueFassung = nachher.genau.get(ab.txId);
            if (ab.art == KmyAenderungen.Art.NEU || ab.art == KmyAenderungen.Art.GEAENDERT) {
                inSichStimmig(neueFassung, ab, nachher);
                seitePlausibel(ab, nachher);
            } else if (ab.art == KmyAenderungen.Art.NUR_NOTIZ) {
                nurNotizGeaendert(vorher.genau.get(ab.txId), neueFassung);
            }
        }
        abgeglichenesBleibt(vorher, nachher);

        int txAlt = vorher.von("TRANSACTION");
        int txNeu = nachher.von("TRANSACTION");
        // Kopfzahl und Inhalt müssen so zueinander stehen wie vorher. Verglichen wird der Abstand, nicht
        // die Gleichheit: Eine Datei, die schon vorher uneins war, soll nicht jeden Export blockieren.
        if (vorher.kopfzahl >= 0 && nachher.kopfzahl >= 0
                && vorher.kopfzahl - txAlt != nachher.kopfzahl - txNeu) {
            throw new Failed("TRANSACTIONS count " + nachher.kopfzahl + " passt nicht zu " + txNeu
                    + " Buchungen (vorher " + vorher.kopfzahl + " zu " + txAlt + ")");
        }
        String entpackt;
        try {
            entpackt = KmyDocument.gunzip(gepackt);
        } catch (IOException e) {
            throw new Failed("gepackte Datei nicht lesbar", e);
        }
        if (!neu.equals(entpackt)) {
            throw new Failed("gepackte Datei weicht vom Inhalt ab");
        }
    }

    // ---- Unberührtes bleibt Zeichen für Zeichen stehen ----

    /**
     * Die erste Regel und die wichtigste: Was der Export nicht angesagt hat, steht in der neuen Fassung
     * genau so da wie in der alten. Verglichen wird der Rohtext, Element für Element.
     */
    private static void unberuehrtesBleibt(KmyGliederung a, KmyGliederung n, Stand vorher,
                                           Stand nachher, KmyAenderungen erwartet)
            throws Failed, KmyGliederung.Fehler {
        if (!a.kopf.equals(n.kopf) || !a.schluss.equals(n.schluss)
                || !a.wurzel.oeffnung().equals(n.wurzel.oeffnung())) {
            throw new Failed("Dateikopf verändert");
        }
        KmyGliederung.Inhalt alt = a.wurzel.inhalt();
        KmyGliederung.Inhalt neu = n.wurzel.inhalt();
        if (alt.kinder.size() != neu.kinder.size()) {
            throw new Failed("Bereiche der Datei " + alt.kinder.size() + " → " + neu.kinder.size());
        }
        if (!alt.luecken.equals(neu.luecken)) {
            throw new Failed("Text zwischen den Bereichen verändert");
        }
        for (int i = 0; i < alt.kinder.size(); i++) {
            KmyGliederung.Element ea = alt.kinder.get(i);
            KmyGliederung.Element en = neu.kinder.get(i);
            if (!ea.name.equals(en.name)) {
                throw new Failed("Bereich " + ea.name + " → " + en.name);
            }
            switch (ea.name) {
                case "TRANSACTIONS":
                    hauptbuchBleibt(ea, en, vorher, nachher, erwartet);
                    break;
                case "PAYEES":
                    empfaengerBleiben(ea, en, erwartet);
                    break;
                case "SCHEDULES":
                    planungenBleiben(ea, en, erwartet);
                    break;
                case "FILEINFO":
                    dateikopfBleibt(ea, en);
                    break;
                default:
                    if (!ea.gleich(en)) {
                        throw new Failed(ea.name + " verändert");
                    }
            }
        }
    }

    /** Das öffnende Tag eines Behälters darf sich nur im {@code count} unterscheiden. */
    private static void oeffnungBleibt(KmyGliederung.Element alt, KmyGliederung.Element neu,
                                       String ausgenommen) throws Failed, KmyGliederung.Fehler {
        Map<String, String> a = alt.attribute();
        Map<String, String> n = neu.attribute();
        a.remove(ausgenommen);
        n.remove(ausgenommen);
        if (!a.equals(n)) {
            throw new Failed("<" + alt.name + "> verändert");
        }
    }

    /** Die Blöcke aus {@code alle}, deren id nicht in {@code ohne} steht. */
    private static List<KmyGliederung.Element> ohne(List<KmyGliederung.Element> alle, Set<String> ohne)
            throws KmyGliederung.Fehler {
        List<KmyGliederung.Element> out = new ArrayList<>();
        for (KmyGliederung.Element e : alle) {
            String id = e.id();
            if (id == null || !ohne.contains(id)) {
                out.add(e);
            }
        }
        return out;
    }

    private static int anzahl(List<KmyGliederung.Element> alle, String id) throws KmyGliederung.Fehler {
        int n = 0;
        for (KmyGliederung.Element e : alle) {
            if (id.equals(e.id())) {
                n++;
            }
        }
        return n;
    }

    /**
     * Zwei Folgen von Blöcken müssen Stück für Stück zeichengleich sein. Die Meldung nennt den ersten
     * Block, an dem es hakt, und was mit ihm geschehen ist.
     */
    private static void folgeGleich(List<KmyGliederung.Element> alt, List<KmyGliederung.Element> neu)
            throws Failed, KmyGliederung.Fehler {
        int gemeinsam = Math.min(alt.size(), neu.size());
        for (int i = 0; i < gemeinsam; i++) {
            KmyGliederung.Element ea = alt.get(i);
            KmyGliederung.Element en = neu.get(i);
            if (ea.gleich(en)) {
                continue;
            }
            String idAlt = ea.id();
            String idNeu = en.id();
            if (idAlt != null && idAlt.equals(idNeu)) {
                throw new Failed(ea.name + " " + idAlt + " verändert, ohne dass der Export es wollte");
            }
            if (idAlt != null && anzahl(neu, idAlt) == 0) {
                throw new Failed(ea.name + " " + idAlt + " verschwunden, ohne dass der Export es wollte");
            }
            throw new Failed(en.name + " " + idNeu + " aufgetaucht oder verschoben, ohne dass der "
                    + "Export es wollte");
        }
        if (alt.size() > gemeinsam) {
            KmyGliederung.Element e = alt.get(gemeinsam);
            throw new Failed(e.name + " " + e.id() + " verschwunden, ohne dass der Export es wollte");
        }
        if (neu.size() > gemeinsam) {
            KmyGliederung.Element e = neu.get(gemeinsam);
            throw new Failed(e.name + " " + e.id() + " aufgetaucht, ohne dass der Export es wollte");
        }
    }

    private static void hauptbuchBleibt(KmyGliederung.Element alt, KmyGliederung.Element neu,
                                        Stand vorher, Stand nachher, KmyAenderungen erwartet)
            throws Failed, KmyGliederung.Fehler {
        oeffnungBleibt(alt, neu, "count");
        KmyGliederung.Inhalt a = alt.inhalt();
        KmyGliederung.Inhalt n = neu.inhalt();
        // Gegenprobe: Zerleger und Parser müssen dieselben Buchungen sehen – sonst vergliche die Regel
        // hier etwas anderes, als KMyMoney nachher liest.
        if (a.kinder.size() != vorher.von("TRANSACTION") || n.kinder.size() != nachher.von("TRANSACTION")) {
            throw new Failed("TRANSACTION uneinheitlich gezählt (" + n.kinder.size() + " zu "
                    + nachher.von("TRANSACTION") + ")");
        }
        if (!a.rest().equals(n.rest())) {
            throw new Failed("Text zwischen den TRANSACTION-Blöcken verändert");
        }
        Set<String> nichtAlt = new HashSet<>();
        Set<String> nichtNeu = new HashSet<>();
        for (KmyAenderungen.Absicht ab : erwartet.transaktionen()) {
            if (ab.art != KmyAenderungen.Art.NEU) {
                nichtAlt.add(ab.txId);
            }
            if (ab.art != KmyAenderungen.Art.GELOESCHT) {
                nichtNeu.add(ab.txId);
            }
        }
        folgeGleich(ohne(a.kinder, nichtAlt), ohne(n.kinder, nichtNeu));
        for (KmyAenderungen.Absicht ab : erwartet.transaktionen()) {
            int warDa = anzahl(a.kinder, ab.txId);
            int istDa = anzahl(n.kinder, ab.txId);
            int sollAlt = ab.art == KmyAenderungen.Art.NEU ? 0 : 1;
            int sollNeu = ab.art == KmyAenderungen.Art.GELOESCHT ? 0 : 1;
            if (warDa != sollAlt || istDa != sollNeu) {
                throw new Failed("TRANSACTION " + ab.txId + " als " + ab.art + " angesagt, steht aber "
                        + warDa + "-mal in der alten und " + istDa + "-mal in der neuen Datei");
            }
        }
        // Was der Export geschrieben hat, darf in keinem Attribut einen wörtlichen Zeilenumbruch
        // tragen: KMyMoney läse dort ein Leerzeichen, und aus einer mehrzeiligen Notiz würde eine Zeile.
        for (KmyGliederung.Element e : n.kinder) {
            KmyAenderungen.Absicht ab = erwartet.zu(e.id());
            if (ab != null && ab.art != KmyAenderungen.Art.GELOESCHT && e.umbruchImAttribut()) {
                throw new Failed("TRANSACTION " + ab.txId + ": Zeilenumbruch in einem Attribut "
                        + "nicht als &#xa; geschrieben");
            }
        }
    }

    private static void empfaengerBleiben(KmyGliederung.Element alt, KmyGliederung.Element neu,
                                          KmyAenderungen erwartet)
            throws Failed, KmyGliederung.Fehler {
        oeffnungBleibt(alt, neu, "count");
        KmyGliederung.Inhalt a = alt.inhalt();
        KmyGliederung.Inhalt n = neu.inhalt();
        if (!a.rest().equals(n.rest())) {
            throw new Failed("Text zwischen den PAYEE-Blöcken verändert");
        }
        folgeGleich(a.kinder, ohne(n.kinder, erwartet.neueEmpfaenger()));
        for (String id : erwartet.neueEmpfaenger()) {
            if (anzahl(a.kinder, id) != 0 || anzahl(n.kinder, id) != 1) {
                throw new Failed("PAYEE " + id + " als neu angesagt, steht aber " + anzahl(a.kinder, id)
                        + "-mal in der alten und " + anzahl(n.kinder, id) + "-mal in der neuen Datei");
            }
        }
    }

    private static void planungenBleiben(KmyGliederung.Element alt, KmyGliederung.Element neu,
                                         KmyAenderungen erwartet)
            throws Failed, KmyGliederung.Fehler {
        if (!alt.oeffnung().equals(neu.oeffnung())) {
            throw new Failed("<SCHEDULES> verändert");
        }
        KmyGliederung.Inhalt a = alt.inhalt();
        KmyGliederung.Inhalt n = neu.inhalt();
        if (a.kinder.size() != n.kinder.size() || !a.luecken.equals(n.luecken)) {
            throw new Failed("SCHEDULED_TX " + a.kinder.size() + " → " + n.kinder.size());
        }
        for (int i = 0; i < a.kinder.size(); i++) {
            KmyGliederung.Element ea = a.kinder.get(i);
            KmyGliederung.Element en = n.kinder.get(i);
            String id = ea.id();
            if (id == null || !id.equals(en.id())) {
                if (!ea.gleich(en)) {
                    throw new Failed("SCHEDULED_TX " + id + " → " + en.id());
                }
            } else if (!erwartet.planungen().contains(id) && !ea.gleich(en)) {
                throw new Failed("SCHEDULED_TX " + id + " verändert, ohne dass der Export es wollte");
            }
        }
    }

    /** {@code FILEINFO}: allein das Datum der letzten Änderung darf ein anderes sein. */
    private static void dateikopfBleibt(KmyGliederung.Element alt, KmyGliederung.Element neu)
            throws Failed, KmyGliederung.Fehler {
        KmyGliederung.Inhalt a = alt.inhalt();
        KmyGliederung.Inhalt n = neu.inhalt();
        boolean gleich = alt.oeffnung().equals(neu.oeffnung()) && a.kinder.size() == n.kinder.size()
                && a.luecken.equals(n.luecken);
        for (int i = 0; gleich && i < a.kinder.size(); i++) {
            KmyGliederung.Element ea = a.kinder.get(i);
            KmyGliederung.Element en = n.kinder.get(i);
            if ("LAST_MODIFIED_DATE".equals(ea.name) && ea.name.equals(en.name)) {
                Map<String, String> aa = ea.attribute();
                Map<String, String> an = en.attribute();
                aa.remove("date");
                an.remove("date");
                gleich = aa.equals(an) && ea.leer == en.leer;
            } else {
                gleich = ea.gleich(en);
            }
        }
        if (!gleich) {
            throw new Failed("FILEINFO verändert");
        }
    }

    /**
     * Liest {@code xml} einmal vollständig durch und zählt, was in {@link #GEZAEHLT} steht – jeweils
     * nur als direktes Kind seines Behälters. Das ist nötig, weil dieselben Namen anderswo wieder
     * auftauchen: {@code ACCOUNT} in jedem Budget, {@code TRANSACTION} in jeder Planung.
     */
    static Stand erfassen(String xml, String welche, Set<String> genau) throws Failed {
        Stand stand = new Stand();
        Map<String, String> behaelterVon = new HashMap<>();
        for (String[] paar : GEZAEHLT) {
            behaelterVon.put(paar[1], paar[0]);
        }
        Deque<String> pfad = new ArrayDeque<>();
        boolean wurzelGesehen = false;
        // Die Transaktion des Hauptbuchs, in der der Parser gerade steht, und ihr letzter Split.
        Buchung buchung = null;
        Split split = null;
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(new StringReader(xml));
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String name = parser.getName();
                    if (pfad.isEmpty()) {
                        if (!"KMYMONEY-FILE".equals(name)) {
                            throw new Failed(welche + ": Wurzel ist <" + name + ">");
                        }
                        wurzelGesehen = true;
                    }
                    String behaelter = behaelterVon.get(name);
                    if (behaelter != null && behaelter.equals(pfad.peek())) {
                        stand.anzahl.merge(name, 1, Integer::sum);
                    }
                    if ("TRANSACTIONS".equals(name) && "KMYMONEY-FILE".equals(pfad.peek())) {
                        String count = parser.getAttributeValue(null, "count");
                        try {
                            stand.kopfzahl = count == null ? -1 : Integer.parseInt(count.trim());
                        } catch (NumberFormatException e) {
                            stand.kopfzahl = -1;
                        }
                    }
                    stammdaten(stand, parser, name, pfad);
                    // Nur das Hauptbuch: dieselben Namen stehen auch in jeder Planung.
                    if ("TRANSACTION".equals(name) && pfad.size() == 2
                            && "TRANSACTIONS".equals(pfad.peek())) {
                        buchung = new Buchung();
                        attribute(parser, buchung.attribute);
                    } else if (buchung != null && "SPLIT".equals(name)) {
                        split = new Split();
                        attribute(parser, split.attribute);
                        buchung.splits.add(split);
                    } else if (split != null && "TAG".equals(name)) {
                        String id = parser.getAttributeValue(null, "id");
                        split.stichwoerter.add(id == null ? "" : id);
                    }
                    pfad.push(name);
                } else if (event == XmlPullParser.END_TAG) {
                    pfad.pop();
                    if ("SPLIT".equals(parser.getName())) {
                        split = null;
                    } else if (buchung != null && pfad.size() == 2) {
                        verbuchen(stand, buchung, genau);
                        buchung = null;
                    }
                }
                event = parser.next();
            }
        } catch (XmlPullParserException | IOException e) {
            throw new Failed(welche + ": XML nicht wohlgeformt (" + e.getMessage() + ")", e);
        }
        if (!wurzelGesehen) {
            throw new Failed(welche + ": leer");
        }
        return stand;
    }

    private static String oderLeer(String s) {
        return s == null ? "" : s.trim();
    }

    /** Konten, Empfänger und Währungen – worauf eine Transaktion verweisen darf. */
    private static void stammdaten(Stand stand, XmlPullParser parser, String name, Deque<String> pfad) {
        if (pfad.size() != 2) {
            return; // nur direkte Kinder der Behälter; ACCOUNT steht auch in jedem Budget
        }
        String behaelter = pfad.peek();
        String id = parser.getAttributeValue(null, "id");
        if ("ACCOUNT".equals(name) && "ACCOUNTS".equals(behaelter) && id != null) {
            String waehrung = oderLeer(parser.getAttributeValue(null, "currency"));
            stand.konten.put(id, new String[]{
                    oderLeer(parser.getAttributeValue(null, "type")),
                    oderLeer(parser.getAttributeValue(null, "parentaccount")), waehrung});
            if (!waehrung.isEmpty()) {
                stand.waehrungen.add(waehrung.toUpperCase(java.util.Locale.ROOT));
            }
        } else if ("PAYEE".equals(name) && "PAYEES".equals(behaelter) && id != null) {
            stand.empfaenger.add(id);
        } else if ("CURRENCY".equals(name) && "CURRENCIES".equals(behaelter) && id != null) {
            stand.waehrungen.add(id.trim().toUpperCase(java.util.Locale.ROOT));
        } else if ("PAIR".equals(name) && "KEYVALUEPAIRS".equals(behaelter)
                && "kmm-baseCurrency".equals(parser.getAttributeValue(null, "key"))) {
            String basis = oderLeer(parser.getAttributeValue(null, "value"));
            if (!basis.isEmpty()) {
                stand.waehrungen.add(basis.toUpperCase(java.util.Locale.ROOT));
            }
        }
    }

    private static void attribute(XmlPullParser parser, Map<String, String> ziel) {
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            ziel.put(parser.getAttributeName(i), parser.getAttributeValue(i));
        }
    }

    /** Trägt die Splits einer fertig gelesenen Transaktion in die Salden ein. */
    private static void verbuchen(Stand stand, Buchung buchung, Set<String> genau) {
        for (Split s : buchung.splits) {
            String konto = s.von("account");
            try {
                KmyBruch wert = KmyBruch.lesen(s.von("value"));
                KmyBruch bisher = stand.saldo.get(konto);
                stand.saldo.put(konto, bisher == null ? wert : bisher.plus(wert));
            } catch (NumberFormatException | ArithmeticException e) {
                stand.unlesbar.merge(konto + " " + s.von("value"), 1, Integer::sum);
            }
        }
        String id = buchung.id();
        if (id != null && genau.contains(id) && !stand.genau.containsKey(id)) {
            stand.genau.put(id, buchung);
        }
        if (id != null && !stand.abgeglichen.containsKey(id)) {
            for (Split s : buchung.splits) {
                if (ABGEGLICHEN.equals(s.von("reconcileflag").trim())) {
                    stand.abgeglichen.put(id, buchung);
                    break;
                }
            }
        }
    }

    // ---- Abgeglichenes ----

    /** {@code reconcileflag} eines in KMyMoney abgeglichenen Splits. */
    private static final String ABGEGLICHEN = "2";

    /**
     * Kein Split, der vorher abgeglichen war, darf verändert sein oder fehlen. Mit dem Abgleich ist in
     * KMyMoney bestätigt, dass diese Buchungen zum Kontoauszug passen – der Export lässt sie deshalb
     * aus ({@code KmyExporter}), und hier wird nachgesehen, dass er es wirklich getan hat. Verglichen
     * wird der Inhalt: jedes Attribut und jedes Stichwort des Splits.
     */
    private static void abgeglichenesBleibt(Stand vorher, Stand nachher) throws Failed {
        for (Map.Entry<String, Buchung> e : vorher.abgeglichen.entrySet()) {
            Buchung neu = nachher.genau.get(e.getKey());
            if (neu == null) {
                throw new Failed("abgeglichene TRANSACTION " + e.getKey() + " fehlt");
            }
            List<Split> uebrig = new ArrayList<>(neu.splits);
            for (Split alt : e.getValue().splits) {
                if (!ABGEGLICHEN.equals(alt.von("reconcileflag").trim())) {
                    continue;
                }
                boolean gefunden = false;
                for (int i = 0; i < uebrig.size() && !gefunden; i++) {
                    Split s = uebrig.get(i);
                    if (s.attribute.equals(alt.attribute) && s.stichwoerter.equals(alt.stichwoerter)) {
                        uebrig.remove(i);
                        gefunden = true;
                    }
                }
                if (!gefunden) {
                    throw new Failed("abgeglichener Split " + alt.von("id") + " (Konto "
                            + alt.von("account") + ") der TRANSACTION " + e.getKey()
                            + " verändert oder entfernt");
                }
            }
        }
    }

    // ---- Salden ----

    /** Konto-id → Summe der Split-{@code value} dieser einen Transaktion. */
    private static Map<String, KmyBruch> summen(Buchung buchung, String welche) throws Failed {
        Map<String, KmyBruch> out = new HashMap<>();
        for (Split s : buchung.splits) {
            KmyBruch wert;
            try {
                wert = KmyBruch.lesen(s.von("value"));
            } catch (NumberFormatException | ArithmeticException e) {
                throw new Failed("TRANSACTION " + buchung.id() + " (" + welche + "): value \""
                        + s.von("value") + "\" ist kein Betrag");
            }
            KmyBruch bisher = out.get(s.von("account"));
            out.put(s.von("account"), bisher == null ? wert : bisher.plus(wert));
        }
        return out;
    }

    private static void addiere(Map<String, KmyBruch> ziel, Map<String, KmyBruch> dazu, boolean negativ) {
        for (Map.Entry<String, KmyBruch> e : dazu.entrySet()) {
            KmyBruch wert = negativ ? e.getValue().negiert() : e.getValue();
            KmyBruch bisher = ziel.get(e.getKey());
            ziel.put(e.getKey(), bisher == null ? wert : bisher.plus(wert));
        }
    }

    /**
     * Die zweite Regel: Auf jedem Konto ändert sich die Summe aller Split-{@code value} um genau das,
     * was der Export beabsichtigt hat – und auf jedem anderen Konto um nichts.
     *
     * <p>Die Absicht stammt aus den Daten der App ({@link KmyAbsicht}), nicht aus dem geschriebenen XML:
     * eine neue Buchung bringt ihr Soll mit, eine geänderte ihr Soll abzüglich dessen, was vorher unter
     * ihrer id in der Datei stand, eine gelöschte nimmt genau das mit.</p>
     */
    private static void saldenStimmen(Stand vorher, Stand nachher, KmyAenderungen erwartet)
            throws Failed {
        Map<String, KmyBruch> absicht = new HashMap<>();
        for (KmyAenderungen.Absicht ab : erwartet.transaktionen()) {
            boolean bringtSoll = ab.art == KmyAenderungen.Art.NEU
                    || ab.art == KmyAenderungen.Art.GEAENDERT;
            if (bringtSoll) {
                if (ab.soll == null) {
                    throw new Failed("TRANSACTION " + ab.txId + " ohne beabsichtigte Beträge angesagt");
                }
                addiere(absicht, ab.soll, false);
                // Was dasteht, muss sich lesen lassen – ein kaputter Betrag darf hier nicht still
                // unter „unlesbar" fallen.
                Buchung neu = nachher.genau.get(ab.txId);
                if (neu != null) {
                    summen(neu, "neu");
                }
            }
            if (ab.art == KmyAenderungen.Art.GEAENDERT || ab.art == KmyAenderungen.Art.GELOESCHT) {
                Buchung alt = vorher.genau.get(ab.txId);
                if (alt == null) {
                    throw new Failed("TRANSACTION " + ab.txId + " steht nicht in der alten Datei");
                }
                addiere(absicht, summen(alt, "alt"), true);
            }
        }
        Set<String> konten = new HashSet<>(vorher.saldo.keySet());
        konten.addAll(nachher.saldo.keySet());
        konten.addAll(absicht.keySet());
        for (String konto : konten) {
            KmyBruch alt = vorher.saldo.get(konto);
            KmyBruch neu = nachher.saldo.get(konto);
            KmyBruch differenz = (neu == null ? KmyBruch.NULL : neu)
                    .minus(alt == null ? KmyBruch.NULL : alt);
            KmyBruch soll = absicht.get(konto);
            if (!differenz.equals(soll == null ? KmyBruch.NULL : soll)) {
                throw new Failed("Saldo von Konto " + konto + " ändert sich um " + differenz
                        + ", beabsichtigt war " + (soll == null ? KmyBruch.NULL : soll));
            }
        }
        if (!vorher.unlesbar.equals(nachher.unlesbar)) {
            throw new Failed("Splits mit unlesbarem value: " + vorher.unlesbar + " → " + nachher.unlesbar);
        }
    }

    // ---- Neue und geänderte Transaktionen ----

    /**
     * Die dritte Regel: Was der Export angelegt oder neu gebaut hat, ist für sich genommen eine
     * Transaktion, die KMyMoney so auch selbst geschrieben haben könnte.
     *
     * <ul>
     *   <li>Auf jedem Konto steht genau der beabsichtigte Betrag, die Summe aller Splits ist damit 0 –
     *       außer bei einer Buchung ohne Kategorie, die bewusst nur ihre Kontoseite trägt.</li>
     *   <li>Ein Split in der Währung der Transaktion trägt als {@code shares} denselben Betrag wie als
     *       {@code value}.</li>
     *   <li>Jedes Konto, jeder Empfänger und die Währung gibt es in der Datei.</li>
     *   <li>Die Split-ids sind innerhalb der Transaktion eindeutig. (Dass ihre eigene id neu und
     *       einmalig ist, stellt schon die erste Regel sicher.)</li>
     *   <li>{@code postdate} ist ein wirkliches Datum.</li>
     * </ul>
     */
    private static void inSichStimmig(Buchung tx, KmyAenderungen.Absicht ab, Stand datei) throws Failed {
        String wer = "TRANSACTION " + ab.txId;
        if (tx == null || ab.soll == null) {
            throw new Failed(wer + " fehlt in der neuen Datei");
        }
        Map<String, KmyBruch> ist = summen(tx, "neu");
        KmyBruch summe = KmyBruch.NULL;
        for (KmyBruch b : ist.values()) {
            summe = summe.plus(b);
        }
        KmyBruch sollSumme = KmyBruch.NULL;
        for (KmyBruch b : ab.soll.values()) {
            sollSumme = sollSumme.plus(b);
        }
        if (!summe.equals(sollSumme)) {
            throw new Failed(wer + ": Summe der Splits ist " + summe + " statt " + sollSumme);
        }
        Set<String> konten = new HashSet<>(ist.keySet());
        konten.addAll(ab.soll.keySet());
        for (String konto : konten) {
            KmyBruch i = ist.get(konto);
            KmyBruch s = ab.soll.get(konto);
            if (!(i == null ? KmyBruch.NULL : i).equals(s == null ? KmyBruch.NULL : s)) {
                throw new Failed(wer + ": auf Konto " + konto + " steht " + (i == null ? KmyBruch.NULL : i)
                        + ", beabsichtigt war " + (s == null ? KmyBruch.NULL : s));
            }
        }

        String waehrung = oderLeer(tx.attribute.get("commodity"));
        if (waehrung.isEmpty() || (!datei.waehrungen.isEmpty()
                && !datei.waehrungen.contains(waehrung.toUpperCase(java.util.Locale.ROOT)))) {
            throw new Failed(wer + ": Währung \"" + waehrung + "\" kennt die Datei nicht");
        }
        if (!istDatum(oderLeer(tx.attribute.get("postdate")))) {
            throw new Failed(wer + ": postdate \"" + tx.attribute.get("postdate") + "\" ist kein Datum");
        }
        Set<String> splitIds = new HashSet<>();
        for (Split s : tx.splits) {
            String konto = s.von("account");
            String[] stamm = datei.konten.get(konto);
            if (stamm == null) {
                throw new Failed(wer + ": Konto \"" + konto + "\" gibt es nicht");
            }
            String empfaenger = s.von("payee");
            if (!empfaenger.isEmpty() && !datei.empfaenger.contains(empfaenger)) {
                throw new Failed(wer + ": Empfänger \"" + empfaenger + "\" gibt es nicht");
            }
            if (s.von("id").isEmpty() || !splitIds.add(s.von("id"))) {
                throw new Failed(wer + ": Split-id \"" + s.von("id") + "\" fehlt oder ist doppelt");
            }
            // Ein Konto ohne Währungsangabe gilt wie im Exporter als eines in der Währung der
            // Transaktion; ein Wertpapierkonto trägt hier die id des Papiers und fällt damit heraus.
            if (stamm[2].isEmpty() || stamm[2].equalsIgnoreCase(waehrung)) {
                KmyBruch shares;
                try {
                    shares = KmyBruch.lesen(s.von("shares"));
                } catch (NumberFormatException | ArithmeticException e) {
                    throw new Failed(wer + ": shares \"" + s.von("shares") + "\" ist kein Betrag");
                }
                if (!shares.equals(KmyBruch.lesen(s.von("value")))) {
                    throw new Failed(wer + ": Split " + s.von("id") + " trägt shares " + s.von("shares")
                            + " bei value " + s.von("value"));
                }
            }
        }
    }

    // ---- Einnahme- und Ausgabeseite ----

    /**
     * In welchem Baum liegt das Konto: {@code TRUE} = Einnahmen, {@code FALSE} = Ausgaben,
     * {@code null} = in keinem von beiden. Entschieden wird am Kontotyp (12/13) und, wo der fehlt, an
     * der Kette der Elternkonten bis zur Wurzel {@code AStd::Income} bzw. {@code AStd::Expense}.
     */
    static Boolean einnahmenbaum(String kontoId, Stand datei) {
        String id = kontoId;
        for (int i = 0; i < 64 && id != null && !id.isEmpty(); i++) {
            if ("AStd::Income".equals(id)) {
                return Boolean.TRUE;
            }
            if ("AStd::Expense".equals(id)) {
                return Boolean.FALSE;
            }
            String[] stamm = datei.konten.get(id);
            if (stamm == null) {
                return null;
            }
            if ("12".equals(stamm[0])) {
                return Boolean.TRUE;
            }
            if ("13".equals(stamm[0])) {
                return Boolean.FALSE;
            }
            id = stamm[1];
        }
        return null;
    }

    /**
     * Die fünfte Regel: Führt die App eine Kategorie als Einnahme, darf ihr Split nicht auf einem Konto
     * des Ausgabenbaums liegen, und umgekehrt. KMyMoney erlaubt denselben Kategorienamen in beiden
     * Bäumen; landete eine Buchung im falschen, stünde sie mit richtigem Betrag in der falschen
     * Auswertung – unauffällig, und deshalb hier abgefangen.
     */
    private static void seitePlausibel(KmyAenderungen.Absicht ab, Stand datei) throws Failed {
        String wer = "TRANSACTION " + ab.txId
                + (ab.bezeichnung.isEmpty() ? "" : " (" + ab.bezeichnung + ")");
        for (String konto : ab.einnahmeKonten) {
            if (Boolean.FALSE.equals(einnahmenbaum(konto, datei))) {
                throw new Failed(wer + ": Kategorie " + konto + " liegt im Ausgabenbaum, die Buchung "
                        + "führt sie als Einnahme");
            }
        }
        for (String konto : ab.ausgabeKonten) {
            if (Boolean.TRUE.equals(einnahmenbaum(konto, datei))) {
                throw new Failed(wer + ": Kategorie " + konto + " liegt im Einnahmenbaum, die Buchung "
                        + "führt sie als Ausgabe");
            }
        }
    }

    /** Ein Datum in der Form {@code JJJJ-MM-TT}, das es im Kalender gibt. */
    static boolean istDatum(String s) {
        if (s.length() != 10 || s.charAt(4) != '-' || s.charAt(7) != '-') {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (i != 4 && i != 7 && (s.charAt(i) < '0' || s.charAt(i) > '9')) {
                return false;
            }
        }
        int jahr = Integer.parseInt(s.substring(0, 4));
        int monat = Integer.parseInt(s.substring(5, 7));
        int tag = Integer.parseInt(s.substring(8, 10));
        if (jahr < 1 || monat < 1 || monat > 12 || tag < 1) {
            return false;
        }
        boolean schaltjahr = jahr % 4 == 0 && (jahr % 100 != 0 || jahr % 400 == 0);
        int[] tage = {31, schaltjahr ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        return tag <= tage[monat - 1];
    }

    /**
     * An einer Wertpapier-Transaktion darf der Export nur Notiz und Stichwörter ändern. Alles andere –
     * Datum, Konten, Beträge, Stückzahl, Kurs, Aktion, Abgleich – steht da wie zuvor.
     */
    private static void nurNotizGeaendert(Buchung alt, Buchung neu) throws Failed {
        if (alt == null || neu == null) {
            throw new Failed("TRANSACTION mit geänderter Notiz fehlt in einer der Fassungen");
        }
        String wer = "TRANSACTION " + alt.id();
        if (!alt.attribute.equals(neu.attribute) || alt.splits.size() != neu.splits.size()) {
            throw new Failed(wer + ": mehr als die Notiz geändert");
        }
        for (int i = 0; i < alt.splits.size(); i++) {
            Map<String, String> a = new HashMap<>(alt.splits.get(i).attribute);
            Map<String, String> n = new HashMap<>(neu.splits.get(i).attribute);
            a.remove("memo");
            n.remove("memo");
            if (!a.equals(n)) {
                throw new Failed(wer + ": Split " + alt.splits.get(i).von("id")
                        + " über die Notiz hinaus geändert");
            }
        }
    }
}
