package de.spahr.ausgaben.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.AppDatabase;
import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.notify.AppSelection;
import de.spahr.ausgaben.notify.KnownApps;
import de.spahr.ausgaben.notify.NotificationBuffer;
import de.spahr.ausgaben.settings.SettingsStore;
import de.spahr.ausgaben.statement.NotificationTemplate;

/**
 * Lernen nach einer von Hand angelegten Umbuchung: die Meldung kann von jeder der beiden Seiten
 * kommen, also fragt die App, zu welchem Konto sie gehört.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NotificationLearnTransferTest {

    private static final String BANK = "de.bank";

    /** Eine Maske, die die Dialoge des Lernens trägt – wie {@link BookingEditActivity}. */
    public static class Host extends AppCompatActivity implements HostedDialog.Host {
        Repository repository;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            setTheme(R.style.Theme_Ausgaben);
            super.onCreate(savedInstanceState);
            repository = new Repository(this);
        }

        @Override
        public Dialog buildDialog(String key, Bundle args) {
            return NotificationLearnFlow.build(this, repository, key, args);
        }

        @Override
        public void onDialogCancelled(String key, Bundle args) {
            finish();
        }
    }

    private Context ctx;
    private Repository repo;
    private Host host;

    @Before
    public void setUp() throws Exception {
        ctx = ApplicationProvider.getApplicationContext();
        repo = new Repository(ctx);
        NotificationBuffer.get(ctx).clear();
        warte(() -> {
            AppDatabase.getInstance(ctx).clearAllTables();
            return null;
        });
        ctx.getSharedPreferences("ausgaben_settings", Context.MODE_PRIVATE).edit().clear().commit();
        new KnownApps(ctx).clear();
        new SettingsStore(ctx).setNotifyLearnEnabled(true);
        AppSelection.setAllowed(ctx, Collections.singletonList(BANK));
    }

    @After
    public void tearDown() throws Exception {
        NotificationBuffer.get(ctx).clear();
        warte(() -> {
            AppDatabase.getInstance(ctx).clearAllTables();
            return null;
        });
    }

    private <T> T warte(java.util.concurrent.Callable<T> arbeit) throws Exception {
        return repo.executor().submit(arbeit).get(10, TimeUnit.SECONDS);
    }

    /** Hintergrund und Hauptfaden abarbeiten, bis nichts mehr nachkommt. */
    private void ruhe() throws Exception {
        for (int i = 0; i < 5; i++) {
            warte(() -> null);
            if (host != null) {
                host.repository.executor().submit(() -> null).get(10, TimeUnit.SECONDS);
            }
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        }
    }

    private AlertDialog frage(long now) throws Exception {
        NotificationBuffer.get(ctx).add(BANK, now, "Neuer Umsatz",
                "Sie haben kontaktlos 25,00 € bei PayPal bezahlt.", "s1");
        host = Robolectric.buildActivity(Host.class).setup().get();
        Booking saved = new Booking();
        saved.isTransfer = true;
        saved.account = "Kreditkarte";
        saved.transferAccount = "PayPal-Konto";
        saved.amountCents = 2500L;
        saved.payee = "PayPal";
        saved.createdAt = now;
        NotificationLearnFlow.afterSave(host, host.repository, saved, host::finish);
        ruhe();
        Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull("nach einer Umbuchung muss gefragt werden", d);
        return (AlertDialog) d;
    }

    private List<NotificationTemplate> vorlagen() throws Exception {
        return warte(() -> repo.notificationTemplates().all());
    }

    @Test
    public void nachUmbuchungWirdNachDemKontoGefragt() throws Exception {
        AlertDialog d = frage(System.currentTimeMillis());
        assertEquals("Kreditkarte",
                d.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        assertEquals("PayPal-Konto",
                d.getButton(AlertDialog.BUTTON_NEUTRAL).getText().toString());
    }

    @Test
    public void vonKontoLerntEinenAbgang() throws Exception {
        AlertDialog d = frage(System.currentTimeMillis());
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ruhe();
        List<NotificationTemplate> t = vorlagen();
        assertEquals(1, t.size());
        assertEquals("Kreditkarte", t.get(0).account);
        assertEquals(false, t.get(0).isIncome);
    }

    @Test
    public void nachKontoLerntEinenZugang() throws Exception {
        AlertDialog d = frage(System.currentTimeMillis());
        d.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ruhe();
        List<NotificationTemplate> t = vorlagen();
        assertEquals(1, t.size());
        assertEquals("PayPal-Konto", t.get(0).account);
        assertTrue(t.get(0).isIncome);
    }
}
