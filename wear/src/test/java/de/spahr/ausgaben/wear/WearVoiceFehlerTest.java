package de.spahr.ausgaben.wear;

import static org.junit.Assert.assertEquals;

import android.speech.SpeechRecognizer;

import de.spahr.ausgaben.wear.WearVoiceFehler.Folge;

import org.junit.Test;

public class WearVoiceFehlerTest {

    /** Kaltstart oder Bereitschaftswache: erst still wiederholen, nie gleich der Zahlenblock. */
    @Test
    public void clientFehlerMitNetz_wiederholtStattZahlenblock() {
        assertEquals(Folge.STILL_WIEDERHOLEN,
                WearVoiceFehler.entscheiden(SpeechRecognizer.ERROR_CLIENT, false, 0, true));
        assertEquals(Folge.NICHT_VERSTANDEN,
                WearVoiceFehler.entscheiden(SpeechRecognizer.ERROR_CLIENT, false, 1, true));
    }

    @Test
    public void belegterDienst_wiederholt() {
        assertEquals(Folge.STILL_WIEDERHOLEN,
                WearVoiceFehler.entscheiden(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, false, 0, true));
    }

    @Test
    public void spracheGehoertAberNichtVerstanden_keineWiederholung() {
        assertEquals(Folge.NICHT_VERSTANDEN,
                WearVoiceFehler.entscheiden(SpeechRecognizer.ERROR_NO_MATCH, true, 0, true));
    }

    @Test
    public void ohneNetz_zahlenblock() {
        assertEquals(Folge.ZAHLENBLOCK,
                WearVoiceFehler.entscheiden(SpeechRecognizer.ERROR_NO_MATCH, true, 0, false));
        assertEquals(Folge.ZAHLENBLOCK,
                WearVoiceFehler.entscheiden(SpeechRecognizer.ERROR_NETWORK, true, 0, true));
        assertEquals(Folge.ZAHLENBLOCK,
                WearVoiceFehler.entscheiden(WearVoiceFehler.ERROR_SERVER_DISCONNECTED, true, 0, true));
    }
}
