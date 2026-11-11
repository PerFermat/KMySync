package de.spahr.ausgaben.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Ein in KMyMoney geschlossenes Depot ist schon nach dem ersten Import geschlossen, und der Abgleich
 * der Depot-Zeilen beim Aktualisieren öffnet es nicht wieder.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DepotClosedStatusTest {

    private static final String DEPOT = "Altes Depot";

    private AppDatabase db;
    private Repository repo;
    private ExecutorService executor;

    @Before
    public void setUp() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        executor = Executors.newSingleThreadExecutor();
        db = AppDatabase.getInstance(ctx);
        imHintergrund(() -> {
            db.clearAllTables();
            return null;
        });
        repo = new Repository(ctx);
    }

    @After
    public void tearDown() throws Exception {
        imHintergrund(() -> {
            db.clearAllTables();
            return null;
        });
        executor.shutdownNow();
    }

    private <T> T imHintergrund(Callable<T> arbeit) throws Exception {
        return executor.submit(arbeit).get(10, TimeUnit.SECONDS);
    }

    private void warte(CountDownLatch fertig) throws Exception {
        for (int i = 0; i < 50 && fertig.getCount() > 0; i++) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            fertig.await(20, TimeUnit.MILLISECONDS);
        }
        assertEquals("onDone wurde nicht rechtzeitig aufgerufen", 0, fertig.getCount());
    }

    private void importiere(boolean geschlossen) throws Exception {
        Security s = new Security();
        s.depot = DEPOT;
        s.kmyId = "E000001";
        s.name = "Fonds";
        CountDownLatch fertig = new CountDownLatch(1);
        repo.replaceDepotImport(DEPOT, Collections.singletonList(s), Collections.emptyList(),
                Collections.emptyList(), geschlossen, fertig::countDown);
        warte(fertig);
    }

    private boolean geschlossen() throws Exception {
        return imHintergrund(() -> db.accountDao().getClosedNames().contains(DEPOT));
    }

    @Test
    public void geschlossenSchonBeimErstenImport() throws Exception {
        importiere(true);
        assertTrue(geschlossen());
    }

    @Test
    public void abgleichOeffnetEsNichtWieder() throws Exception {
        importiere(true);
        CountDownLatch fertig = new CountDownLatch(1);
        repo.ensureDepotAccounts(Collections.singletonList(DEPOT), fertig::countDown);
        warte(fertig);
        assertTrue(geschlossen());
    }

    @Test
    public void inKmyMoneyWiederGeoeffnet() throws Exception {
        importiere(true);
        importiere(false);
        assertFalse(geschlossen());
    }
}
