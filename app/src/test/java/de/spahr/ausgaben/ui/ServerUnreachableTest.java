package de.spahr.ausgaben.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Wann der Benachrichtigungs-Durchlauf trotz gescheiterter Aktualisierung läuft: wenn der Server nicht
 * zu erreichen war — und nur dann.
 */
public class ServerUnreachableTest {

    @Test
    public void keineVerbindung() {
        assertTrue(MainImportFlow.serverUnreachable(new java.net.UnknownHostException("nas")));
        assertTrue(MainImportFlow.serverUnreachable(new java.net.SocketTimeoutException("timeout")));
        assertTrue(MainImportFlow.serverUnreachable(new java.io.IOException("Verbindung abgewiesen")));
    }

    /** Die Bibliotheken verpacken den Ein-/Ausgabefehler gern in eine eigene Ausnahme. */
    @Test
    public void verpackterVerbindungsfehler() {
        assertTrue(MainImportFlow.serverUnreachable(
                new RuntimeException("SMB", new java.net.ConnectException("refused"))));
    }

    /** Die Datei kam an, ließ sich aber nicht verarbeiten: das ist kein Verbindungsproblem. */
    @Test
    public void andereFehler() {
        assertFalse(MainImportFlow.serverUnreachable(new IllegalStateException("kaputte Datei")));
        assertFalse(MainImportFlow.serverUnreachable(new RuntimeException(new NullPointerException())));
        assertFalse(MainImportFlow.serverUnreachable(null));
    }
}
