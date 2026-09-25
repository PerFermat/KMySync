package de.spahr.ausgaben.export;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

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
 * und löscht Buchungen und stellt Planungen weiter. Konten, Institute, Wertpapiere, Kurse, Planungen
 * und Budgets fasst er der Zahl nach nie an.</p>
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

    /** Diese dürfen sich durch einen Export der Zahl nach nicht ändern. */
    private static final String[] UNVERAENDERT = {
            "ACCOUNT", "INSTITUTION", "SECURITY", "PRICEPAIR", "SCHEDULED_TX", "BUDGET"};

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
     * @param geloescht   wie viele Buchungen der Export aus der Datei entfernt hat
     * @param geschrieben Obergrenze der neu angelegten Buchungen (geschriebene Buchungen und
     *                    Wertpapierbewegungen – eine Umbuchung zählt dort doppelt, das schadet der
     *                    Obergrenze nicht)
     * @param gepackt     die Bytes, die tatsächlich hochgeladen werden sollen
     */
    public static void pruefen(String alt, String neu, byte[] gepackt, int geloescht, int geschrieben)
            throws Failed {
        Stand vorher = erfassen(alt, "alte Datei");
        Stand nachher = erfassen(neu, "neue Datei");

        for (String element : UNVERAENDERT) {
            if (vorher.von(element) != nachher.von(element)) {
                throw new Failed(element + " " + vorher.von(element) + " → " + nachher.von(element));
            }
        }
        if (nachher.von("PAYEE") < vorher.von("PAYEE")) {
            throw new Failed("PAYEE " + vorher.von("PAYEE") + " → " + nachher.von("PAYEE"));
        }
        int txAlt = vorher.von("TRANSACTION");
        int txNeu = nachher.von("TRANSACTION");
        int mindestens = txAlt - Math.max(0, geloescht);
        int hoechstens = txAlt + Math.max(0, geschrieben);
        if (txNeu < mindestens || txNeu > hoechstens) {
            throw new Failed("TRANSACTION " + txAlt + " → " + txNeu
                    + " (erlaubt " + mindestens + "–" + hoechstens + ")");
        }
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
