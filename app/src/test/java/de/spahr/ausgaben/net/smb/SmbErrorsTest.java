package de.spahr.ausgaben.net.smb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.net.ConnectException;

import de.spahr.ausgaben.R;

/**
 * Auswahl der Fehlermeldung – ohne Android-Ressourcen, deshalb wird nur die gewählte
 * String-Ressource geprüft.
 *
 * <p>Aus einem Fehlerbericht gelernt: Wer schon Freigaben gesehen hat, darf nie „Server nicht
 * erreichbar" lesen, und der rohe Statuscode muss in der Meldung stehen – sonst ist eine
 * Rückmeldung aus der Ferne wertlos.</p>
 */
public class SmbErrorsTest {

    private static Throwable status(String text) {
        return new IOException(text);
    }

    @Test
    public void unreachableOnlyBeforeAConnectionStood() {
        Throwable refused = new ConnectException("Connection refused");
        assertEquals(R.string.smb_err_unreachable,
                SmbErrors.reasonFor(SmbErrors.Step.CONNECT, refused));
        // Freigabe/Ordner setzen eine stehende Verbindung voraus – dort wäre der Satz eine Lüge.
        assertNotEquals(R.string.smb_err_unreachable,
                SmbErrors.reasonFor(SmbErrors.Step.SHARE, refused));
        assertNotEquals(R.string.smb_err_unreachable,
                SmbErrors.reasonFor(SmbErrors.Step.FOLDER, refused));
    }

    @Test
    public void wrongCredentialsAreNamed() {
        assertEquals(R.string.smb_err_credentials,
                SmbErrors.reasonFor(SmbErrors.Step.LOGIN, status("STATUS_LOGON_FAILURE")));
    }

    @Test
    public void accessDeniedDependsOnTheStep() {
        assertEquals(R.string.smb_err_folder_denied,
                SmbErrors.reasonFor(SmbErrors.Step.FOLDER, status("STATUS_ACCESS_DENIED")));
        assertEquals(R.string.smb_err_credentials,
                SmbErrors.reasonFor(SmbErrors.Step.LOGIN, status("STATUS_ACCESS_DENIED")));
    }

    @Test
    public void serverSideFeaturesGetTheirOwnText() {
        assertEquals(R.string.smb_err_encryption,
                SmbErrors.reasonFor(SmbErrors.Step.SHARE,
                        status("Message encryption is required, but no encryption key is negotiated")));
        assertEquals(R.string.smb_err_dfs,
                SmbErrors.reasonFor(SmbErrors.Step.FOLDER, status("STATUS_PATH_NOT_COVERED")));
        assertEquals(R.string.smb_err_smb1,
                SmbErrors.reasonFor(SmbErrors.Step.CONNECT, status("Could not negotiate dialect")));
        assertEquals(R.string.smb_err_guest_signing, SmbErrors.reasonFor(SmbErrors.Step.LOGIN,
                new IOException(new com.hierynomus.smbj.session.SMB2GuestSigningRequiredException())));
    }

    @Test
    public void unknownFolderErrorStaysUnknownInsteadOfBlamingTheNetwork() {
        assertEquals(R.string.smb_err_unknown,
                SmbErrors.reasonFor(SmbErrors.Step.FOLDER, status("STATUS_SOMETHING_NEW")));
    }

    @Test
    public void rawReasonIsAppendedOnce() {
        String out = SmbErrors.withRaw("Zugriff verweigert.", "STATUS_ACCESS_DENIED (0xC0000022)");
        assertTrue(out, out.startsWith("Zugriff verweigert. ("));
        assertTrue(out, out.contains("STATUS_ACCESS_DENIED"));
        // Steht der Grund schon im Text, wird er nicht doppelt angehängt.
        assertEquals("STATUS_ACCESS_DENIED", SmbErrors.withRaw("STATUS_ACCESS_DENIED",
                "STATUS_ACCESS_DENIED"));
    }

    @Test
    public void rawReasonIsSingleLineAndBounded() {
        StringBuilder long_ = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            long_.append("abcdefghij\n");
        }
        String out = SmbErrors.withRaw("Fehler.", long_.toString());
        assertFalse(out.contains("\n"));
        assertTrue(out.length() < 200);
        assertTrue(out.endsWith("…)"));
    }

    @Test
    public void causeChainIsRead() {
        Throwable e = new IOException("außen", new IOException("STATUS_BAD_NETWORK_NAME"));
        assertEquals(R.string.smb_err_share, SmbErrors.reasonFor(SmbErrors.Step.SHARE, e));
        assertTrue(SmbErrors.textOf(e).contains("STATUS_BAD_NETWORK_NAME"));
    }

    /**
     * {@code ExecutionException(cause)} (Einzelargument-Konstruktor) setzt die eigene Meldung auf den
     * vollen {@code toString()} der Ursache – lief die Kette blind weiter, stünde derselbe Grund
     * zweimal im Bericht (genau das Muster aus einem echten Diagnose-Fehlschlag: „Aufräumen im
     * Ordner" zeigte den EOFException-Text doppelt).
     */
    @Test
    public void executionExceptionCauseIsNotDuplicated() {
        Throwable eof = new java.io.EOFException("EOF while reading packet");
        Throwable exec = new java.util.concurrent.ExecutionException(eof);

        String out = SmbErrors.textOf(exec);

        int first = out.indexOf("EOF while reading packet");
        assertTrue("Grund muss vorkommen: " + out, first >= 0);
        assertEquals("Grund darf nur einmal vorkommen: " + out, -1,
                out.indexOf("EOF while reading packet", first + 1));
    }
}
