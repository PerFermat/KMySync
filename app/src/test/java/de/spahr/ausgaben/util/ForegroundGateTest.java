package de.spahr.ausgaben.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Die Schranke, an der ein langer Netzlauf wartet, solange die App im Hintergrund ist.
 */
public class ForegroundGateTest {

    @Before
    public void setUp() {
        ForegroundGate.resetForTest();
    }

    @After
    public void tearDown() {
        ForegroundGate.resetForTest();
    }

    @Test
    public void ohneSichtbareAnsichtIstDieAppImHintergrund() {
        assertFalse(ForegroundGate.isForeground());
        ForegroundGate.enter();
        assertTrue(ForegroundGate.isForeground());
        ForegroundGate.leave();
        assertFalse(ForegroundGate.isForeground());
    }

    @Test
    public void zweiAnsichtenZaehlenEinzeln() {
        // Beim Wechsel zwischen zwei Activities überlappen sich start und stop – dabei darf die
        // Schranke nicht kurz auf „Hintergrund" fallen und einen Lauf schlafen legen.
        ForegroundGate.enter();
        ForegroundGate.enter();
        ForegroundGate.leave();
        assertTrue(ForegroundGate.isForeground());
        ForegroundGate.leave();
        assertFalse(ForegroundGate.isForeground());
    }

    @Test
    public void leaveZaehltNichtUnterNull() {
        ForegroundGate.leave();
        ForegroundGate.enter();
        assertTrue("ein zusätzliches leave darf kein Guthaben anlegen", ForegroundGate.isForeground());
    }

    @Test
    public void imVordergrundWartetNiemand() {
        ForegroundGate.enter();
        long vorher = System.currentTimeMillis();
        assertTrue(ForegroundGate.awaitForeground(10_000));
        assertTrue("hätte sofort zurückkommen müssen", System.currentTimeMillis() - vorher < 1000);
    }

    @Test
    public void ohneRueckkehrLaeuftDieFristAb() {
        long vorher = System.currentTimeMillis();
        assertFalse(ForegroundGate.awaitForeground(150));
        assertTrue("die Frist muß auch wirklich gewartet werden",
                System.currentTimeMillis() - vorher >= 150);
    }

    @Test
    public void rueckkehrWecktDenWartenden() throws Exception {
        final AtomicBoolean ergebnis = new AtomicBoolean(false);
        final CountDownLatch faengtAn = new CountDownLatch(1);
        final CountDownLatch fertig = new CountDownLatch(1);
        Thread wartend = new Thread(() -> {
            faengtAn.countDown();
            ergebnis.set(ForegroundGate.awaitForeground(10_000));
            fertig.countDown();
        });
        wartend.start();
        assertTrue(faengtAn.await(5, TimeUnit.SECONDS));
        Thread.sleep(50); // dem Thread Zeit geben, wirklich in wait() zu gehen
        ForegroundGate.enter();
        assertTrue("der Wartende muß geweckt werden", fertig.await(5, TimeUnit.SECONDS));
        assertTrue(ergebnis.get());
    }
}
