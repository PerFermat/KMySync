package de.spahr.ausgaben.wear;

import android.speech.SpeechRecognizer;

/**
 * Was nach einem Fehler der Spracherkennung geschieht – als reine Entscheidung, damit sie sich ohne
 * Uhr prüfen lässt.
 *
 * <p>Bis 2.1.1 sprang die Uhr bei {@code ERROR_CLIENT} und bei fehlender Verbindung zum Handy sofort in
 * den Zahlenblock. {@code ERROR_CLIENT} meldet aber auch die eigene Bereitschaftswache und jeder
 * Fehlstart des kalten Erkennungsdienstes; das Handy braucht die Erkennung gar nicht. Der Nutzer sah
 * dann „mit Handy daneben trotzdem Zahlenblock, nach mehreren Versuchen klappt es". In den Zahlenblock
 * geht es jetzt nur noch, wenn wirklich kein Netz da ist.</p>
 */
final class WearVoiceFehler {

    enum Folge {
        /** Noch einmal still zuhören (der Dienst lief beim Losreden noch nicht). */
        STILL_WIEDERHOLEN,
        /** Kein Netz: Sprache kann gerade nicht klappen, die Eingabe muss trotzdem möglich bleiben. */
        ZAHLENBLOCK,
        /** Zurück zur Auswahl mit „Nicht verstanden"; der Zahlenblock-Knopf steht dort bereit. */
        NICHT_VERSTANDEN
    }

    /** {@code SpeechRecognizer.ERROR_SERVER_DISCONNECTED}, erst ab API 31 als Konstante vorhanden. */
    static final int ERROR_SERVER_DISCONNECTED = 11;

    private WearVoiceFehler() {
    }

    /**
     * @param code            Fehlercode des Erkennungsdienstes
     * @param sprachBegonnen  ob der Dienst in diesem Versuch schon einen Sprachanfang gemeldet hat
     * @param wiederholungen  wie oft für diese Eingabe schon still wiederholt wurde
     * @param internet        ob gerade validiertes Internet da ist
     */
    static Folge entscheiden(int code, boolean sprachBegonnen, int wiederholungen, boolean internet) {
        // Hat der Dienst nie Sprache gehört, lief er beim Losreden noch nicht – oder war noch belegt.
        // Genau das hat der Nutzer bisher von Hand ausgeglichen. Einmal, nicht öfter: eine Uhr, die
        // unbemerkt immer weiter zuhört, wäre schlimmer als eine, die aufgibt.
        if (!sprachBegonnen && wiederholungen < 1 && (code == SpeechRecognizer.ERROR_NO_MATCH
                || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                || code == SpeechRecognizer.ERROR_CLIENT
                || code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)) {
            return Folge.STILL_WIEDERHOLEN;
        }
        if (!internet
                || code == SpeechRecognizer.ERROR_NETWORK
                || code == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                || code == SpeechRecognizer.ERROR_SERVER
                || code == ERROR_SERVER_DISCONNECTED) {
            return Folge.ZAHLENBLOCK;
        }
        return Folge.NICHT_VERSTANDEN;
    }
}
