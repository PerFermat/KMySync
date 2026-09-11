package de.spahr.ausgaben.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Der WebDAV-Diagnosebericht ist zum Weiterschicken gedacht: Adresse und Statuscodes gehören hinein,
 * Benutzername und Passwort nicht. Und die Deutung der Statuscodes muss ohne Server prüfbar sein –
 * genau sie ist ja der Grund, warum es die Diagnose gibt.
 */
public class WebDavDiagnosticsTest {

    /**
     * Ein echter Lauf gegen eine tote Adresse: Der Bericht nennt Host und Port, aber niemals das
     * Passwort – und den Benutzernamen nur als „gesetzt" bzw. als Platzhalter im Pfad.
     */
    @Test
    public void reportNeverContainsCredentials() {
        String report = WebDavDiagnostics.report(WebDavDiagnostics.run(
                "http://127.0.0.1:1", "hts", "streng-geheim", true, "Finanzen/test.kmy"));
        assertTrue(report, report.startsWith("WebDAV-Diagnose (KMySync)"));
        assertFalse(report, report.contains("streng-geheim"));
        assertFalse(report, report.contains("hts"));
        assertTrue(report, report.contains("Benutzer gesetzt"));
        assertTrue(report, report.contains("127.0.0.1:1"));
        assertTrue(report, report.contains(WebDavDiagnostics.USER_MASK));
        assertTrue(report, report.contains("✗ Erreichbarkeit"));
    }

    /**
     * Was das Band anzeigt, muss zum Bericht passen: Jeder gemeldete Schritt steht auch dort, und
     * angekündigt war er vorher. Sonst zeigte die Anzeige etwas anderes als das Ergebnis.
     */
    @Test
    public void progressMatchesTheReport() {
        java.util.List<String> angekuendigt = new java.util.ArrayList<>();
        java.util.List<Diagnostics.Step> fertig = new java.util.ArrayList<>();
        java.util.List<Diagnostics.Step> steps = WebDavDiagnostics.run(
                "http://127.0.0.1:1", "hts", "geheim", true, "", "", new Diagnostics.Progress() {
                    @Override
                    public void beginning(String label) {
                        angekuendigt.add(label);
                    }

                    @Override
                    public void finished(Diagnostics.Step step) {
                        fertig.add(step);
                    }
                });

        assertFalse("es muss etwas gemeldet worden sein", fertig.isEmpty());
        assertEquals(steps.size(), fertig.size());
        for (int i = 0; i < steps.size(); i++) {
            assertEquals(steps.get(i).label, fertig.get(i).label);
            assertEquals(steps.get(i).label, angekuendigt.get(i));
        }
    }

    @Test
    public void emptyAddressSaysSoInsteadOfConnecting() {
        String report = WebDavDiagnostics.report(
                WebDavDiagnostics.run("", "", "", true, "", ""));
        assertTrue(report, report.contains("✗ Adresse: keine Adresse"));
        assertFalse(report, report.contains("Erreichbarkeit"));
    }

    @Test
    public void nonsenseAddressIsReportedInsteadOfConnecting() {
        String report = WebDavDiagnostics.report(
                WebDavDiagnostics.run("cloud.example.de", "", "", true, "", ""));
        assertTrue(report, report.contains("✗ Adresse"));
        assertFalse(report, report.contains("Erreichbarkeit"));
    }

    /** Der häufigste Zuschnittfehler: der Nextcloud-Pfad steht schon in der Basis-URL. */
    @Test
    public void doubledNextcloudPathIsCalledOut() {
        String report = WebDavDiagnostics.report(WebDavDiagnostics.run(
                "https://cloud.example.de/remote.php/dav/files/hts", "hts", "geheim", true, "", ""));
        assertTrue(report, report.contains("✗ Adresse"));
        assertTrue(report, report.contains("/remote.php"));
        // Gesagt gehört, was stattdessen in das Feld gehört – sonst rät der Nutzer weiter.
        assertTrue(report, report.contains("nur https://server"));
        assertFalse(report, report.contains("Erreichbarkeit"));
    }

    /** Bei generischem WebDAV ist genau dieser Pfad richtig und darf nicht bemängelt werden. */
    @Test
    public void plainWebDavMayCarryTheWholePathInTheUrl() {
        String report = WebDavDiagnostics.report(WebDavDiagnostics.run(
                "http://127.0.0.1:1/remote.php/dav/files/hts", "hts", "geheim", false, "", ""));
        assertTrue(report, report.contains("✓ Adresse"));
    }

    @Test
    public void httpWithoutTlsIsFlagged() {
        String report = WebDavDiagnostics.report(
                WebDavDiagnostics.run("http://127.0.0.1:1", "", "", true, "", ""));
        assertTrue(report, report.contains("Klartext"));
    }

    /** Die Deutung der Statuscodes – der eigentliche Gewinn gegenüber „Verbindung fehlgeschlagen". */
    @Test
    public void statusCodesAreExplained() {
        assertTrue(WebDavDiagnostics.anmeldeDeutung(401, true).contains("App-Passwort"));
        assertTrue(WebDavDiagnostics.anmeldeDeutung(403, true).contains("verweigert"));
        assertTrue(WebDavDiagnostics.anmeldeDeutung(404, true).contains("Benutzername im Pfad"));
        assertTrue(WebDavDiagnostics.anmeldeDeutung(404, false).contains("DAV-Wurzel"));
        assertTrue(WebDavDiagnostics.anmeldeDeutung(405, true).contains("kein WebDAV"));
        assertTrue(WebDavDiagnostics.anmeldeDeutung(500, true).contains("eigenen Fehler"));
        assertEquals("", WebDavDiagnostics.anmeldeDeutung(0, true));
        assertTrue(WebDavDiagnostics.ordnerDeutung(404).contains("gibt es dort nicht"));
        assertTrue(WebDavDiagnostics.ordnerDeutung(423).contains("gesperrt"));
    }

    @Test
    public void networkTroubleIsNamedByItsKind() {
        assertTrue(WebDavDiagnostics.netzgrund(new java.net.UnknownHostException("x"))
                .contains("nicht auflösbar"));
        assertTrue(WebDavDiagnostics.netzgrund(new javax.net.ssl.SSLHandshakeException("abgelaufen"))
                .contains("Zertifikat"));
        assertTrue(WebDavDiagnostics.netzgrund(new java.net.SocketTimeoutException())
                .contains("Wartezeit"));
        assertTrue(WebDavDiagnostics.netzgrund(new java.net.ConnectException())
                .contains("keine Verbindung an"));
    }

    @Test
    public void userNameIsMaskedEvenWhenUrlEncoded() {
        assertEquals("https://x/files/" + WebDavDiagnostics.USER_MASK,
                WebDavDiagnostics.mask("https://x/files/hans müller", "hans müller"));
        assertEquals("https://x/files/" + WebDavDiagnostics.USER_MASK,
                WebDavDiagnostics.mask("https://x/files/hans+m%C3%BCller", "hans müller"));
        assertEquals("https://x/files/hts", WebDavDiagnostics.mask("https://x/files/hts", ""));
    }

    /** Der Ordner selbst ist die erste PROPFIND-Antwort und zählt nicht als Eintrag mit. */
    @Test
    public void folderItselfIsNotCountedAsEntry() {
        String xml = "<d:multistatus xmlns:d=\"DAV:\">"
                + "<d:response><d:href>/f/</d:href></d:response>"
                + "<d:response><d:href>/f/a.kmy</d:href></d:response>"
                + "<d:response><d:href>/f/b.kmy</d:href></d:response></d:multistatus>";
        assertEquals(2, WebDavDiagnostics.eintraege(xml));
        assertEquals(0, WebDavDiagnostics.eintraege(""));
        assertEquals(0, WebDavDiagnostics.eintraege(null));
    }

    @Test
    public void etagAndSizeAreReadRegardlessOfNamespacePrefix() {
        assertEquals("\"abc123\"", WebDavDiagnostics.tag(
                "<x:getetag>\"abc123\"</x:getetag>", "getetag"));
        assertEquals("4711", WebDavDiagnostics.tag(
                "<getcontentlength>4711</getcontentlength>", "getcontentlength"));
        assertEquals("", WebDavDiagnostics.tag("<d:resourcetype/>", "getetag"));
    }
}
