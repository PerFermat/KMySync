package de.spahr.ausgaben.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import de.spahr.ausgaben.notify.KnownApps;
import de.spahr.ausgaben.statement.NotificationLearner;
import de.spahr.ausgaben.statement.NotificationSource;
import de.spahr.ausgaben.statement.NotificationTemplate;

/**
 * Die Vorlagen in der Datenbank: was gelernt wurde, liest nach dem Speichern und Laden dasselbe — und
 * die Zuordnung Konto → App bleibt eindeutig.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NotificationTemplateStoreTest {

    private AppDatabase db;
    private NotificationTemplateStore store;
    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase.class).allowMainThreadQueries().build();
        store = new NotificationTemplateStore(ctx, db);
        new KnownApps(ctx).clear();
    }

    @After
    public void tearDown() {
        db.close();
    }

    private static NotificationSource zahlung(String app, String haendler, String betrag) {
        return new NotificationSource(app, 0L, "Kartenzahlung",
                "Zahlung bei " + haendler + " über " + betrag + " EUR.");
    }

    private static NotificationTemplate gelernt(String app, String account) {
        NotificationTemplate t = NotificationLearner.learn(zahlung(app, "REWE Markt", "12,34"), 1234L,
                false, "Rewe", account);
        assertNotNull(t);
        return t;
    }

    @Test
    public void gelerntesUeberstehtSpeichernUndLaden() {
        store.save(gelernt("de.bank", "Kreditkarte"));
        List<NotificationTemplate> all = store.all();
        assertEquals(1, all.size());
        NotificationTemplate back = all.get(0);
        assertTrue(back.sameAs(gelernt("de.bank", "Kreditkarte")));
        NotificationTemplate.Hit hit = back.apply(zahlung("de.bank", "Lidl Markt", "7,50"));
        assertNotNull(hit);
        assertEquals(750L, hit.cents);
        assertEquals("Lidl Markt", hit.payee);
    }

    @Test
    public void dieselbeVorlageStehtNurEinmalDa() {
        store.save(gelernt("de.bank", "Kreditkarte"));
        store.save(gelernt("de.bank", "Kreditkarte"));
        assertEquals(1, store.all().size());
    }

    /** Zahlung und Gutschrift lesen sich verschieden: zwei Vorlagen für dasselbe Konto. */
    @Test
    public void mehrereVorlagenJeKontoUndApp() {
        store.save(gelernt("de.bank", "Kreditkarte"));
        NotificationTemplate gutschrift = NotificationLearner.learn(new NotificationSource("de.bank",
                        0L, "Gutschrift", "Gutschrift von Amazon in Höhe von 20,00 EUR."), 2000L, true,
                "Amazon", "Kreditkarte");
        assertNotNull(gutschrift);
        store.save(gutschrift);
        assertEquals(2, store.all().size());
    }

    /** Eine App, zwei Konten: beide Vorlagen bleiben. */
    @Test
    public void eineAppDarfMehrereKontenHaben() {
        store.save(gelernt("de.bank", "Kreditkarte"));
        store.save(gelernt("de.bank", "Girokonto"));
        assertEquals(2, store.all().size());
    }

    /** Ein Konto, zwei Apps: das geht nicht — die neue ersetzt die alte. */
    @Test
    public void einKontoGehoertZuGenauEinerApp() {
        store.save(gelernt("de.alte.bank", "Kreditkarte"));
        store.save(gelernt("de.alte.bank", "Girokonto"));
        store.save(gelernt("de.neue.bank", "Kreditkarte"));
        List<NotificationTemplate> all = store.all();
        assertEquals(2, all.size());
        for (NotificationTemplate t : all) {
            if (t.account.equals("Kreditkarte")) {
                assertEquals("de.neue.bank", t.packageName);
            } else {
                assertEquals("de.alte.bank", t.packageName);
            }
        }
    }

    @Test
    public void mitDemKontoGehenSeineVorlagen() {
        store.save(gelernt("de.bank", "Kreditkarte"));
        store.save(gelernt("de.bank", "Girokonto"));
        store.deleteForAccount("Kreditkarte");
        List<NotificationTemplate> all = store.all();
        assertEquals(1, all.size());
        assertEquals("Girokonto", all.get(0).account);
    }

    /** Der Papierkorb in den Einstellungen: alles zu einer App, über alle Konten, und nur zu ihr. */
    @Test
    public void regelnEinerAppLassenSichLoeschen() {
        store.save(gelernt("de.bank", "Kreditkarte"));
        store.save(gelernt("de.bank", "Girokonto"));
        store.save(gelernt("de.andere", "Sparkonto"));
        store.deleteForPackage("de.bank");
        List<NotificationTemplate> all = store.all();
        assertEquals(1, all.size());
        assertEquals("de.andere", all.get(0).packageName);
        assertEquals(new HashSet<>(Collections.singletonList("de.andere")), new KnownApps(ctx).all());
    }

    /** Der Dienst im Hintergrund muss wissen, welche Apps eine Vorlage haben. */
    @Test
    public void bekannteAppsFolgenDenVorlagen() {
        store.save(gelernt("de.bank", "Kreditkarte"));
        store.save(gelernt("de.andere", "Girokonto"));
        assertEquals(new HashSet<>(Arrays.asList("de.bank", "de.andere")), new KnownApps(ctx).all());
        store.deleteForAccount("Girokonto");
        assertEquals(new HashSet<>(Collections.singletonList("de.bank")), new KnownApps(ctx).all());
    }
}
