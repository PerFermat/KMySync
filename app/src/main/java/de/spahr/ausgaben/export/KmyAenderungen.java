package de.spahr.ausgaben.export;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Was ein Export an der Datei ändern <b>will</b> – die Ansage, an der {@link KmyExportCheck} das
 * Ergebnis misst. Alles, was hier nicht steht, muss in der neuen Fassung Zeichen für Zeichen so
 * dastehen wie in der alten.
 *
 * <p>Gefüllt wird sie vom {@link KmyExporter} an den Stellen, an denen er eine Transaktion anlegt,
 * ersetzt oder entfernt; jeder seiner Schritte liefert eine eigene, {@link #zusammen} fügt sie zur
 * Ansage des ganzen Laufs. Ohne Android.</p>
 */
public final class KmyAenderungen {

    /** Was mit einer Transaktion geschieht. */
    public enum Art {
        /** Kommt hinzu: steht nur in der neuen Fassung. */
        NEU,
        /** Wird an ihrer Stelle neu gebaut: steht in beiden Fassungen. */
        GEAENDERT,
        /** Wertpapier-Transaktion, an der nur Notiz und Stichwörter wandern; Beträge bleiben. */
        NUR_NOTIZ,
        /** Wird entfernt: steht nur in der alten Fassung. */
        GELOESCHT
    }

    /** Die Ansage zu einer Transaktion. */
    public static final class Absicht {
        public final String txId;
        public final Art art;
        /**
         * Was die Transaktion auf jedem Konto bewegen soll (Konto-id → Betrag), berechnet aus den Daten
         * der App – siehe {@link KmyAbsicht}. Nur bei {@link Art#NEU} und {@link Art#GEAENDERT}; eine
         * gelöschte nimmt mit, was sie in der Datei trug, eine {@link Art#NUR_NOTIZ} bewegt nichts.
         */
        public Map<String, KmyBruch> soll;
        /**
         * Kategorie-Konten, die die Buchung der App als Einnahme- bzw. als Ausgabekategorie führt
         * ({@code category_is_income}). Die Selbstprüfung hält dagegen, in welchem Baum das Konto in
         * der Datei wirklich liegt. Eine Kategorie, deren Seite die App nicht kennt, steht in keiner
         * der beiden Mengen.
         */
        public final Set<String> einnahmeKonten = new LinkedHashSet<>();
        public final Set<String> ausgabeKonten = new LinkedHashSet<>();
        /** Woran der Nutzer die Buchung erkennt – für die Meldung, falls die Prüfung anschlägt. */
        public String bezeichnung = "";

        Absicht(String txId, Art art) {
            this.txId = txId;
            this.art = art;
        }
    }

    private final Map<String, Absicht> transaktionen = new LinkedHashMap<>();
    private final Set<String> neueEmpfaenger = new LinkedHashSet<>();
    private final Set<String> planungen = new LinkedHashSet<>();

    public Absicht neu(String txId) {
        return melde(new Absicht(txId, Art.NEU));
    }

    public Absicht geaendert(String txId) {
        return melde(new Absicht(txId, Art.GEAENDERT));
    }

    public Absicht nurNotiz(String txId) {
        return melde(new Absicht(txId, Art.NUR_NOTIZ));
    }

    public Absicht geloescht(String txId) {
        return melde(new Absicht(txId, Art.GELOESCHT));
    }

    /** Ein neu angelegter {@code PAYEE}. */
    public void neuerEmpfaenger(String payeeId) {
        neueEmpfaenger.add(payeeId);
    }

    /** Eine weitergestellte Planung ({@code SCHEDULED_TX}). */
    public void planung(String scheduleId) {
        planungen.add(scheduleId);
    }

    /**
     * Trägt eine Ansage ein. Trifft sie auf eine frühere zur selben Transaktion, gilt, was am Ende in
     * der Datei steht: Eine in diesem Lauf angelegte und gleich wieder entfernte Transaktion taucht
     * nirgends auf; eine geänderte und dann entfernte ist gelöscht.
     */
    private Absicht melde(Absicht a) {
        Absicht vorher = transaktionen.get(a.txId);
        if (vorher != null && vorher.art == Art.NEU) {
            if (a.art == Art.GELOESCHT) {
                transaktionen.remove(a.txId);
            }
            return vorher;
        }
        transaktionen.put(a.txId, a);
        return a;
    }

    /** Verwirft alles – der Schritt hat die Datei doch nicht angefasst. */
    public void leeren() {
        transaktionen.clear();
        neueEmpfaenger.clear();
        planungen.clear();
    }

    /** Die Ansage zu dieser Transaktion, oder {@code null}: dann muss sie unberührt bleiben. */
    public Absicht zu(String txId) {
        return txId == null ? null : transaktionen.get(txId);
    }

    public Iterable<Absicht> transaktionen() {
        return transaktionen.values();
    }

    public Set<String> neueEmpfaenger() {
        return neueEmpfaenger;
    }

    public Set<String> planungen() {
        return planungen;
    }

    /** Nichts soll sich ändern. */
    public static KmyAenderungen keine() {
        return new KmyAenderungen();
    }

    /** Die Ansagen mehrerer Schritte, in der Reihenfolge, in der sie auf die Datei gewirkt haben. */
    public static KmyAenderungen zusammen(KmyAenderungen... schritte) {
        KmyAenderungen out = new KmyAenderungen();
        for (KmyAenderungen s : schritte) {
            if (s == null) {
                continue;
            }
            for (Absicht a : s.transaktionen.values()) {
                out.melde(a);
            }
            out.neueEmpfaenger.addAll(s.neueEmpfaenger);
            out.planungen.addAll(s.planungen);
        }
        return out;
    }
}
