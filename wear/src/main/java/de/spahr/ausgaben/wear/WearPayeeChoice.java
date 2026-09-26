package de.spahr.ausgaben.wear;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Die Empfänger-Runde der Bestätigungsseite: der gesprochene vorn, dahinter die im Umkreis, am Ende
 * „ohne Empfänger" – und welcher davon gerade gewählt ist.
 *
 * <p>Eigene Klasse, weil hier ein Fehler saß, den kein Bildschirm verrät: Die Activity merkte sich vor
 * jedem Neuaufbau die bisherige Wahl und stellte sie danach wieder her. Das war für den Neuaufbau
 * <b>innerhalb</b> einer Eingabe gedacht (der Standort trifft erst während des Countdowns ein). Weil die
 * Liste bei einer neuen Eingabe nicht geleert wurde, überlebte aber die Wahl der <b>vorigen</b>: „Mama
 * 10 €" gesagt, und vorbelegt war der Empfänger der letzten Buchung an diesem Ort. Deshalb trennt die
 * Klasse die beiden Fälle ausdrücklich – {@link #neu} und {@link #aktualisieren}.</p>
 */
final class WearPayeeChoice {

    private final List<String> namen = new ArrayList<>();
    private String gesprochen = "";
    /** Ob beim letzten Aufbau Empfänger aus dem Umkreis dabei waren. */
    private boolean naheDabei;
    /** Gewählte Stelle; {@code namen.size()} steht für „ohne Empfänger". */
    private int wahl;

    /**
     * Beginnt eine neue Eingabe. Eine frühere Wahl zählt nicht: Der gesprochene Empfänger steht vorn
     * und ist gewählt – auch wenn er nicht im Umkreis liegt. Ohne gesprochenen ist der nächste nahe
     * gewählt.
     */
    void neu(String gesprochen, List<String> nahe) {
        this.gesprochen = gesprochen == null ? "" : gesprochen.trim();
        aufbauen(nahe);
        wahl = 0;
    }

    /**
     * Baut die Runde innerhalb derselben Eingabe neu auf, etwa wenn der Standort erst spät eintrifft.
     * Die getroffene Wahl bleibt – auch „ohne Empfänger" –, sonst spränge sie dem Nutzer unter den
     * Fingern weg.
     */
    void aktualisieren(List<String> nahe) {
        boolean ohne = ohneEmpfaenger();
        String vorher = gewaehlt();
        aufbauen(nahe);
        if (ohne) {
            wahl = namen.size();
            return;
        }
        wahl = 0;
        for (int i = 0; i < namen.size(); i++) {
            if (namen.get(i).equalsIgnoreCase(vorher)) {
                wahl = i;
                break;
            }
        }
    }

    private void aufbauen(List<String> nahe) {
        namen.clear();
        naheDabei = nahe != null && !nahe.isEmpty();
        if (!gesprochen.isEmpty()) {
            namen.add(gesprochen);
        }
        if (nahe != null) {
            for (String name : nahe) {
                if (name == null || name.trim().isEmpty() || enthaelt(name)) {
                    continue; // den gesprochenen und Doppelte nicht zweimal führen
                }
                namen.add(name);
            }
        }
    }

    private boolean enthaelt(String name) {
        for (String n : namen) {
            if (n.equalsIgnoreCase(name.trim())) {
                return true;
            }
        }
        return false;
    }

    /** Weiter zum nächsten; nach dem letzten kommt „ohne Empfänger", dann wieder von vorn. */
    void weiter() {
        if (!namen.isEmpty()) {
            wahl = (wahl + 1) % (namen.size() + 1);
        }
    }

    /** Der gewählte Name; leer bei „ohne Empfänger" oder leerer Runde. */
    String gewaehlt() {
        return wahl < namen.size() ? namen.get(wahl) : "";
    }

    /** Ausdrücklich „ohne Empfänger" gewählt – im Unterschied zu „keiner da". */
    boolean ohneEmpfaenger() {
        return !namen.isEmpty() && wahl == namen.size();
    }

    /** Steht die Wahl noch auf dem gesprochenen Empfänger? */
    boolean aufGesprochenem() {
        return !gesprochen.isEmpty() && wahl == 0 && !namen.isEmpty();
    }

    /**
     * Sind schon Empfänger aus dem Umkreis in der Runde? Solange nicht, lohnt ein Neuaufbau, sobald
     * der Standort eintrifft – auch wenn der gesprochene Empfänger schon vorn steht.
     */
    boolean hatNahe() {
        return naheDabei;
    }

    boolean leer() {
        return namen.isEmpty();
    }

    int anzahl() {
        return namen.size();
    }

    List<String> namen() {
        return Collections.unmodifiableList(namen);
    }
}
