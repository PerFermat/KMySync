package de.spahr.ausgaben.net.smb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Der Diagnosebericht ist zum Weiterschicken gedacht: Er muss lesbar sein, den Fehlerschritt
 * benennen – und darf keine Zugangsdaten enthalten.
 */
public class SmbDiagnosticsTest {

    private static SmbDiagnostics.Step step(String label, boolean ok, String detail, long ms) {
        return new SmbDiagnostics.Step(label, ok, detail, ms);
    }

    @Test
    public void reportMarksSuccessAndFailure() {
        String report = SmbDiagnostics.report(Arrays.asList(
                step("Verbinden", true, "", 28),
                step("Freigabe „daten\" öffnen", false, "STATUS_ACCESS_DENIED", 12)));
        assertTrue(report, report.startsWith("SMB-Diagnose (KMySync)"));
        assertTrue(report, report.contains("✓ Verbinden (28 ms)"));
        assertTrue(report, report.contains("✗ Freigabe „daten\" öffnen: STATUS_ACCESS_DENIED (12 ms)"));
    }

    @Test
    public void stepWithoutDurationOmitsTheMilliseconds() {
        assertEquals("✓ Aushandeln: SMB_3_1_1", step("Aushandeln", true, "SMB_3_1_1", -1).toString());
    }

    @Test
    public void firstFailureIsTheOneThatMatters() {
        List<SmbDiagnostics.Step> steps = Arrays.asList(
                step("Verbinden", true, "", 5),
                step("Anmelden", false, "STATUS_LOGON_FAILURE", 7),
                step("Freigaben", false, "egal", 1));
        assertNotNull(SmbDiagnostics.firstFailure(steps));
        assertEquals("Anmelden", SmbDiagnostics.firstFailure(steps).label);
        assertNull(SmbDiagnostics.firstFailure(Collections.singletonList(step("Alles", true, "", 1))));
    }

    /**
     * Ein echter Lauf gegen eine tote Adresse: Der Bericht nennt die Adresse und den gescheiterten
     * Schritt, aber niemals das Passwort – und den Benutzernamen nur als „gesetzt".
     */
    @Test
    public void reportNeverContainsCredentials() {
        String report = SmbDiagnostics.report(SmbDiagnostics.run(
                "smb://127.0.0.1:1/daten/unterordner", "hts", "streng-geheim", "test.kmy"));
        assertFalse(report, report.contains("streng-geheim"));
        assertFalse(report, report.contains("hts"));
        assertTrue(report, report.contains("Benutzer gesetzt"));
        assertTrue(report, report.contains("127.0.0.1:1"));
        assertTrue(report, report.contains("✗ Verbinden"));
    }

    @Test
    public void missingShareIsReportedInsteadOfConnecting() {
        String report = SmbDiagnostics.report(SmbDiagnostics.run("smb://server", "", "", ""));
        assertTrue(report, report.contains("✗ Adresse: server, keine Freigabe"));
        assertTrue(report, report.contains("smb://Host/Freigabe"));
        // Ohne Adresse wird gar nicht erst verbunden.
        assertFalse(report, report.contains("Verbinden"));
    }

    /** Der Ordner wird ohne Dateinamen geprüft (CSV-Modus) – dann taucht kein Datei-Schritt auf. */
    @Test
    public void folderOnlyRunSkipsTheFileSteps() {
        String report = SmbDiagnostics.report(
                SmbDiagnostics.run("smb://127.0.0.1:1/daten", "", "", "Finanzen", ""));
        assertFalse(report, report.contains("beschreibbar"));
        assertTrue(report, report.contains("✗ Verbinden"));
    }

    @Test
    public void emptyAddressSaysSoInsteadOfPrintingBlanks() {
        String report = SmbDiagnostics.report(SmbDiagnostics.run("", "", "", ""));
        assertTrue(report, report.contains("kein Host"));
        assertFalse(report, report.contains(":445"));
    }
}
