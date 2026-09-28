package de.spahr.ausgaben.db;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Die drei Regeln, die im CSV-Modus stillen Datenverlust verhindern sollen. */
public class CsvModeGuardTest {

    private static Booking booking(boolean exported) {
        Booking b = new Booking();
        b.exported = exported;
        return b;
    }

    @Test
    public void exportierteBuchungImCsvModusIstGesperrt() {
        assertTrue(CsvModeGuard.lockedForEdit(booking(true), false));
    }

    @Test
    public void exportierteBuchungImKmyModusBleibtFrei() {
        assertFalse(CsvModeGuard.lockedForEdit(booking(true), true));
    }

    @Test
    public void nochNichtExportierteBuchungBleibtImmerFrei() {
        assertFalse(CsvModeGuard.lockedForEdit(booking(false), false));
        assertFalse(CsvModeGuard.lockedForEdit(booking(false), true));
    }

    @Test
    public void ohneBuchungIstNichtsGesperrt() {
        assertFalse(CsvModeGuard.lockedForEdit(null, false));
    }

    @Test
    public void planungenNurImKmyModus() {
        assertTrue(CsvModeGuard.scheduledBlocked(false));
        assertFalse(CsvModeGuard.scheduledBlocked(true));
    }

    @Test
    public void depotNurImKmyModus() {
        assertTrue(CsvModeGuard.depotBlocked(false));
        assertFalse(CsvModeGuard.depotBlocked(true));
    }

    @Test
    public void splitbuchungenNurImKmyModus() {
        assertTrue(CsvModeGuard.splitBlocked(false));
        assertFalse(CsvModeGuard.splitBlocked(true));
    }
}
