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

    /** Das Gezählte einer Fassung. */
    static final class Stand {
        final Map<String, Integer> anzahl = new HashMap<>();
        /** {@code <TRANSACTIONS count="…">}; {@code -1}, wenn nicht angegeben. */
        int kopfzahl = -1;

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
        Stand vorher = erfassen(alt, "alte Datei");
        Stand nachher = erfassen(neu, "neue Datei");

        KmyGliederung a;
        KmyGliederung n;
        try {
            a = KmyGliederung.lesen(alt);
            n = KmyGliederung.lesen(neu);
            unberuehrtesBleibt(a, n, vorher, nachher, erwartet);
        } catch (KmyGliederung.Fehler e) {
            throw new Failed("Datei nicht zerlegbar (" + e.getMessage() + ")", e);
        }

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
    static Stand erfassen(String xml, String welche) throws Failed {
        Stand stand = new Stand();
        Map<String, String> behaelterVon = new HashMap<>();
        for (String[] paar : GEZAEHLT) {
            behaelterVon.put(paar[1], paar[0]);
        }
        Deque<String> pfad = new ArrayDeque<>();
        boolean wurzelGesehen = false;
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
                    pfad.push(name);
                } else if (event == XmlPullParser.END_TAG) {
                    pfad.pop();
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
}
