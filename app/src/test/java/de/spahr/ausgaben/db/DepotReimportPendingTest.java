package de.spahr.ausgaben.db;

import static org.junit.Assert.assertEquals;
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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Was ein Depot-Reimport mit einer in der App erfassten, noch nicht exportierten Bewegung macht.
 *
 * <p>Regression: {@code deleteImportedTx} schont diese Bewegungen seit jeher, das Löschen ihrer
 * Kategoriezeilen tat es nicht. Die Bewegung überlebte den Reimport dadurch als Zeile ohne Teile —
 * und fiel beim nächsten Export still aus {@code KmyExporter.addCategorySplits} heraus, während die
 * zugehörige Geldbuchung über {@code BookingDao.getUnexported()} ebenfalls ausgeschlossen war. Beides
 * verschwand also unbemerkt und dauerhaft aus der Ausgabedatei.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DepotReimportPendingTest {

    private static final String DEPOT = "Depot A";
    private static final String KMY_ID = "S1";

    private Context ctx;
    private AppDatabase db;
    private Repository repo;
    private ExecutorService executor;

    @Before
    public void setUp() throws Exception {
        ctx = ApplicationProvider.getApplicationContext();
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

    /** Eine selbst erfasste Dividende mit zwei Steuerzeilen; {@code pending} = noch nicht exportiert. */
    private long legeOffeneBewegungAn() throws Exception {
        return imHintergrund(() -> {
            SecurityTx tx = new SecurityTx();
            tx.depot = DEPOT;
            tx.securityKmyId = KMY_ID;
            tx.securityName = "Vanguard FTSE All-World";
            tx.moneyAccount = "Girokonto";
            tx.action = "dividend";
            tx.shares = 100.0;
            tx.amountCents = 5_000L;
            tx.netCents = 3_681L;
            tx.date = 0L;
            tx.pending = true;
            long id = db.securityDao().insertTx(tx);

            db.securityDao().insertSplit(teil(id, "Kapitalertragsteuer", 1_319L));
            db.securityDao().insertSplit(teil(id, "Dividenden", 5_000L));
            return id;
        });
    }

    private static SecurityTxSplit teil(long txId, String kategorie, long cents) {
        SecurityTxSplit s = new SecurityTxSplit();
        s.txId = txId;
        s.category = kategorie;
        s.amountCents = cents;
        s.income = "Dividenden".equals(kategorie);
        return s;
    }

    /** Eine aus der Datei stammende Bewegung – die soll der Reimport samt Teilen wegräumen. */
    private long legeImportierteBewegungAn() throws Exception {
        return imHintergrund(() -> {
            SecurityTx tx = new SecurityTx();
            tx.depot = DEPOT;
            tx.securityKmyId = KMY_ID;
            tx.securityName = "Vanguard FTSE All-World";
            tx.moneyAccount = "Girokonto";
            tx.action = "buy";
            tx.shares = 6.09607;
            tx.amountCents = 100_250L;
            tx.netCents = 100_250L;
            tx.date = 0L;
            tx.pending = false;
            long id = db.securityDao().insertTx(tx);
            db.securityDao().insertSplit(teil(id, "Gebühren", 250L));
            return id;
        });
    }

    private List<SecurityTxSplit> teileVon(long txId) throws Exception {
        return imHintergrund(() -> db.securityDao().getSplits(txId));
    }

    /**
     * {@code replaceDepotImport} läuft auf Repositorys eigenem Hintergrund-Executor und meldet sich per
     * {@code onDone} auf dem Hauptfaden zurück – wie in {@link DepotDeleteTest} den (in Robolectric
     * pausierten) Hauptfaden zwischendurch antreiben.
     */
    private void reimportUndWarte() throws Exception {
        Security neu = new Security();
        neu.depot = DEPOT;
        neu.kmyId = KMY_ID;
        neu.name = "Vanguard FTSE All-World";

        CountDownLatch fertig = new CountDownLatch(1);
        repo.replaceDepotImport(DEPOT, Collections.singletonList(neu),
                Collections.emptyList(), Collections.emptyList(), false, fertig::countDown);
        for (int i = 0; i < 50 && fertig.getCount() > 0; i++) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            fertig.await(20, TimeUnit.MILLISECONDS);
        }
        assertEquals("onDone wurde nicht rechtzeitig aufgerufen", 0, fertig.getCount());
    }

    @Test
    public void offeneBewegungBehaeltIhreKategoriezeilen() throws Exception {
        long offen = legeOffeneBewegungAn();

        reimportUndWarte();

        assertEquals("Die offene Bewegung selbst bleibt stehen", 1,
                imHintergrund(() -> db.securityDao().getTxBySecurity(DEPOT, KMY_ID)).size());
        List<SecurityTxSplit> teile = teileVon(offen);
        assertEquals("Ohne ihre Kategoriezeilen fiele sie still aus dem Export", 2, teile.size());
    }

    @Test
    public void importierteBewegungVerschwindetSamtTeilen() throws Exception {
        long importiert = legeImportierteBewegungAn();

        reimportUndWarte();

        assertTrue("Die Bewegung aus der Datei schreibt der Reimport neu",
                imHintergrund(() -> db.securityDao().getTxBySecurity(DEPOT, KMY_ID)).isEmpty());
        assertTrue("Ihre Kategoriezeilen dürfen nicht verwaisen", teileVon(importiert).isEmpty());
    }
}
