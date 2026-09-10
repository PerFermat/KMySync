package de.spahr.ausgaben.net.smb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Der SMB-Diagnosebericht ist zum Weiterschicken gedacht: Er muss den Fehlerschritt benennen – und
 * darf keine Zugangsdaten enthalten. Die reine Form des Berichts prüft {@code DiagnosticsTest}.
 */
public class SmbDiagnosticsTest {

    /**
     * Ein echter Lauf gegen eine tote Adresse: Der Bericht nennt die Adresse und den gescheiterten
     * Schritt, aber niemals das Passwort – und den Benutzernamen nur als „gesetzt".
     */
    @Test
    public void reportNeverContainsCredentials() {
        String report = SmbDiagnostics.report(SmbDiagnostics.run(
                "smb://127.0.0.1:1/daten/unterordner", "hts", "streng-geheim", "test.kmy"));
        assertTrue(report, report.startsWith("SMB-Diagnose (KMySync)"));
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
