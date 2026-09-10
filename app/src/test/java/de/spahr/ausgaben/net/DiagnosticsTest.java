package de.spahr.ausgaben.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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
