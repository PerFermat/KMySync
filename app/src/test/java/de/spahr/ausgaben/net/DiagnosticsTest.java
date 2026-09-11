package de.spahr.ausgaben.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.spahr.ausgaben.net.Diagnostics.Step;

/**
 * Die Form des Berichts – gemeinsam für SMB und WebDAV. Er ist zum Weiterschicken gedacht: Er muss
 * lesbar sein und den Fehlerschritt benennen.
 */
public class DiagnosticsTest {

    private static Step step(String label, boolean ok, String detail, long ms) {
        return new Step(label, ok, detail, ms);
    }

    @Test
    public void reportMarksSuccessAndFailure() {
        String report = Diagnostics.report("Probe-Diagnose", Arrays.asList(
                step("Verbinden", true, "", 28),
                step("Freigabe „daten\" öffnen", false, "STATUS_ACCESS_DENIED", 12)));
        assertTrue(report, report.startsWith("Probe-Diagnose"));
        assertTrue(report, report.contains("✓ Verbinden (28 ms)"));
        assertTrue(report, report.contains("✗ Freigabe „daten\" öffnen: STATUS_ACCESS_DENIED (12 ms)"));
    }

    @Test
    public void stepWithoutDurationOmitsTheMilliseconds() {
        assertEquals("✓ Aushandeln: SMB_3_1_1", step("Aushandeln", true, "SMB_3_1_1", -1).toString());
    }

    @Test
    public void firstFailureIsTheOneThatMatters() {
        List<Step> steps = Arrays.asList(
                step("Verbinden", true, "", 5),
                step("Anmelden", false, "STATUS_LOGON_FAILURE", 7),
                step("Freigaben", false, "egal", 1));
        assertNotNull(Diagnostics.firstFailure(steps));
        assertEquals("Anmelden", Diagnostics.firstFailure(steps).label);
        assertNull(Diagnostics.firstFailure(Collections.singletonList(step("Alles", true, "", 1))));
    }

    /**
     * Der Fortschritt ist das, woran der Nutzer während der Prüfung sieht, daß noch etwas passiert:
     * zu jedem Schritt erst „fängt an", dann „fertig" – und beides mit derselben Beschriftung.
     */
    @Test
    public void everyStepIsAnnouncedBeforeItIsReported() {
        List<String> gemeldet = new ArrayList<>();
        Diagnostics.Log log = new Diagnostics.Log(new Diagnostics.Progress() {
            @Override
            public void beginning(String label) {
                gemeldet.add("an: " + label);
            }

            @Override
            public void finished(Step step) {
                gemeldet.add((step.ok ? "ok: " : "weg: ") + step.label);
            }
        });

        log.begin("Verbinden");
        log.ok("");
        log.begin("Anmelden");
        log.fail("STATUS_LOGON_FAILURE");
        log.note("Aushandeln", true, "SMB_3_1_1");

        assertEquals(Arrays.asList("an: Verbinden", "ok: Verbinden",
                "an: Anmelden", "weg: Anmelden",
                "an: Aushandeln", "ok: Aushandeln"), gemeldet);
        assertEquals(3, log.steps().size());
        assertEquals("Anmelden", Diagnostics.firstFailure(log.steps()).label);
    }

    /** Der Bericht allein und die Tests brauchen keine Anzeige – ohne Empfänger läuft es genauso. */
    @Test
    public void logWorksWithoutAnyListener() {
        Diagnostics.Log log = new Diagnostics.Log(null);
        log.begin("Verbinden");
        Step step = log.ok("über Port 445");
        assertEquals("Verbinden", step.label);
        assertTrue(step.ok);
        // Die Dauer wird gemessen, also nicht als „keine Dauer" (-1) ausgewiesen.
        assertTrue(String.valueOf(step.millis), step.millis >= 0);
        assertEquals(1, log.steps().size());
    }

    /** Eine Zeile ohne eigene Dauer bleibt eine ohne – sie zu stoppen wäre sinnlos. */
    @Test
    public void noteHasNoDuration() {
        Diagnostics.Log log = new Diagnostics.Log(null);
        assertEquals(-1, log.note("Adresse", true, "server:445").millis);
    }

    /** Ein Stapelauszug im Bericht wäre unlesbar – der Grund bleibt einzeilig und gekürzt. */
    @Test
    public void reasonsStayOnOneLineAndShort() {
        assertEquals("a b c", Diagnostics.shorten("  a\n b\t\tc "));
        String lang = new String(new char[300]).replace('\0', 'x');
        assertEquals(201, Diagnostics.shorten(lang).length());
        assertTrue(Diagnostics.shorten(lang).endsWith("…"));
        assertEquals("", Diagnostics.shorten(null));
    }
}
