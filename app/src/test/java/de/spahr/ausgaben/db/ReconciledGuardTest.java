package de.spahr.ausgaben.db;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Die eine Regel zu abgeglichenen Buchungen. */
public class ReconciledGuardTest {

    @Test
    public void nurAbgeglicheneSindGesperrt() {
        Booking b = new Booking();
        assertFalse(ReconciledGuard.locked(b));
        b.exported = true;
        b.edited = true;
        assertFalse("exportiert oder bearbeitet allein sperrt nicht", ReconciledGuard.locked(b));
        b.reconciled = true;
        assertTrue(ReconciledGuard.locked(b));
        assertFalse(ReconciledGuard.locked(null));
    }
}
