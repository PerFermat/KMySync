package de.spahr.ausgaben.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
import java.util.concurrent.TimeUnit;

import de.spahr.ausgaben.db.AppDatabase;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.notify.AppSelection;
import de.spahr.ausgaben.notify.NotificationBuffer;
import de.spahr.ausgaben.settings.ProfileManager;
import de.spahr.ausgaben.settings.SettingsStore;
import de.spahr.ausgaben.statement.NotificationLearner;
import de.spahr.ausgaben.statement.NotificationSource;
import de.spahr.ausgaben.statement.NotificationTemplate;

/**
 * Die gelernten Regeln für Buchungen aus Benachrichtigungen gehören in jede Sicherung — die eines
 * Profils wie die aller Profile.
 *
 * <p>Sie stehen in der Datenbank des Profils und gehen deshalb mit, ohne dass jemand sie eigens
 * einpacken müsste. Gerade weil es von selbst geschieht, hält dieser Test es fest: Zöge die Tabelle
 * einmal um, verlöre man beim Einspielen alles Gelernte, ohne dass es auffiele — die nächste Meldung
 * würde einfach wieder nicht erkannt.</p>
 *
 * <p>Die Gegenprobe steht daneben: die aufgehobenen Meldungstexte gehören <b>nicht</b> hinein.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BackupNotificationRulesTest {

    private static final String BANK = "com.android.shell";

    private final Context ctx = ApplicationProvider.getApplicationContext();

    @Before
    public void setUp() throws Exception {
        ctx.getSharedPreferences("ausgaben_profiles", Context.MODE_PRIVATE).edit().clear().commit();
        ctx.getSharedPreferences("ausgaben_settings", Context.MODE_PRIVATE).edit().clear().commit();
        AppDatabase.closeInstance();
        ProfileManager.migrateLegacyInstallationIfNeeded(ctx);
        leeren();
        NotificationBuffer.get(ctx).clear();
    }

    @After
    public void tearDown() throws Exception {
        leeren();
        NotificationBuffer.get(ctx).clear();
    }

    private <T> T imHintergrund(Callable<T> arbeit) throws Exception {
        return new Repository(ctx).executor().submit(arbeit).get(10, TimeUnit.SECONDS);
    }

    private void leeren() throws Exception {
        imHintergrund(() -> {
            AppDatabase.getInstance(ctx).clearAllTables();
            return null;
        });
    }

    private static NotificationSource zahlung(String haendler, String betrag) {
        return new NotificationSource(BANK, 0L, "Neuer Umsatz",
                "Sie haben kontaktlos " + betrag + " € bei " + haendler + " bezahlt.");
    }

    private void regelLernen(final String account) throws Exception {
        final NotificationTemplate t = NotificationLearner.learn(zahlung("REWE Markt", "12,34"), 1234L,
                false, "Rewe", account);
        assertNotNull(t);
        imHintergrund(() -> {
            new Repository(ctx).notificationTemplates().save(t);
            return null;
        });
    }

    private List<NotificationTemplate> regeln() throws Exception {
        return imHintergrund(() -> new Repository(ctx).notificationTemplates().all());
    }

    @Test
    public void profilSicherungBringtRegelnUndAuswahlZurueck() throws Exception {
        regelLernen("Kreditkarte");
        AppSelection.setAllowed(ctx, Collections.singletonList(BANK));

        BackupArchive.Content content = BackupArchive.read(BackupStore.createProfile(ctx, false, ""));

        // Alles verwerfen, als hätte man das Profil neu aufgesetzt.
        leeren();
        AppSelection.setAllowed(ctx, Collections.<String>emptySet());
        assertTrue(regeln().isEmpty());

        BackupStore.restoreProfileData(ctx, content.db);
        BackupStore.restoreProfileSettings(ctx, content);

        List<NotificationTemplate> back = regeln();
        assertEquals(1, back.size());
        assertEquals("Kreditkarte", back.get(0).account);
        // Nicht bloß vorhanden, sondern brauchbar: die Regel liest die nächste Meldung.
        NotificationTemplate.Hit hit = back.get(0).apply(zahlung("Lidl", "7,50"));
        assertNotNull(hit);
        assertEquals(750L, hit.cents);
        assertEquals("Lidl", hit.payee);
        assertTrue("auch die Auswahl der App", AppSelection.isAllowed(ctx, BANK));
    }

    @Test
    public void komplettSicherungBringtRegelnAuswahlUndSchalterZurueck() throws Exception {
        regelLernen("Kreditkarte");
        AppSelection.setAllowed(ctx, Collections.singletonList(BANK));
        new SettingsStore(ctx).setNotifyLearnEnabled(true);

        BackupArchive.Content content = BackupArchive.read(BackupStore.createAll(ctx, false, ""));

        leeren();
        ctx.getSharedPreferences("ausgaben_settings", Context.MODE_PRIVATE).edit().clear().commit();
        assertFalse(SettingsStore.isNotifyLearnEnabled(ctx));

        BackupStore.restoreAllSettings(ctx, content);
        BackupStore.restoreAllData(ctx, content);

        List<NotificationTemplate> back = regeln();
        assertEquals(1, back.size());
        assertNotNull(back.get(0).apply(zahlung("Lidl", "7,50")));
        assertTrue(AppSelection.isAllowed(ctx, BANK));
        assertTrue("der Schalter gilt für das Gerät und steht in der Komplettsicherung",
                SettingsStore.isNotifyLearnEnabled(ctx));
    }

    /** Die Gegenprobe: was von den Meldungen aufgehoben wurde, verlässt das Gerät nicht. */
    @Test
    public void aufgehobeneMeldungenStehenInKeinerSicherung() throws Exception {
        regelLernen("Kreditkarte");
        AppSelection.setAllowed(ctx, Collections.singletonList(BANK));
        String geheim = "Sie haben kontaktlos 99,99 € bei Juwelier Goldstück bezahlt.";
        NotificationBuffer.get(ctx).add(BANK, System.currentTimeMillis(), "Neuer Umsatz", geheim, "k1");

        for (byte[] archiv : new byte[][]{BackupStore.createProfile(ctx, false, ""),
                BackupStore.createAll(ctx, false, "")}) {
            BackupArchive.Content content = BackupArchive.read(archiv);
            for (String name : content.prefs.keySet()) {
                assertFalse(name, name.startsWith("notify_"));
                assertFalse(name, content.prefs.get(name).contains("Goldstück"));
            }
            for (byte[] db : content.dbs.values()) {
                assertFalse("kein Meldungstext in einer gesicherten Datenbank",
                        new String(db, java.nio.charset.StandardCharsets.UTF_8).contains("Goldstück"));
            }
        }
    }
}
