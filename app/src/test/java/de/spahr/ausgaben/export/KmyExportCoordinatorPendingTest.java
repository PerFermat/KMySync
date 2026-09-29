package de.spahr.ausgaben.export;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

import de.spahr.ausgaben.net.RemoteConflictException;
import de.spahr.ausgaben.net.RemoteMoveException;
import de.spahr.ausgaben.net.RemoteReplaceStuckException;

/**
 * Wann der Absturz-Vermerk nach einem gescheiterten Schreiben stehen bleibt. Bleibt er zu Unrecht, hält
 * der nächste Export eine zufällig gleich signierte fremde Transaktion für die eigene Buchung und
 * markiert sie als exportiert, ohne sie je zu schreiben.
 */
public class KmyExportCoordinatorPendingTest {

    @Test
    public void nachKonfliktIstDerVermerkWeg() {
        assertFalse(KmyExportCoordinator.keepPendingAfter(new RemoteConflictException("geändert")));
    }

    @Test
    public void nachGescheitertemUmbenennenIstDerVermerkWeg() {
        assertFalse(KmyExportCoordinator.keepPendingAfter(
                new RemoteMoveException("nicht möglich", new IOException("423"))));
    }

    @Test
    public void nachNetzfehlerBeimHochladenIstDerVermerkWeg() {
        assertFalse(KmyExportCoordinator.keepPendingAfter(new IOException("timeout")));
        assertFalse(KmyExportCoordinator.keepPendingAfter(new IllegalStateException("kaputt")));
    }

    /** Hier ist offen, ob die neue Datei an ihrem Platz steht – dann muss die Wiederherstellung greifen. */
    @Test
    public void nachSteckengebliebenemTauschBleibtDerVermerk() {
        assertTrue(KmyExportCoordinator.keepPendingAfter(
                new RemoteReplaceStuckException("a.kmy", "a.kmy.1.old", new IOException("weg"))));
    }
}
