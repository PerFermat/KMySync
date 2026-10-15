package de.spahr.ausgaben.notify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.content.Context;
import android.os.Process;
import android.service.notification.StatusBarNotification;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;

import de.spahr.ausgaben.settings.ProfileManager;

/**
 * Was der Dienst von einer Meldung nimmt — beim Eintreffen wie beim nachträglichen Übernehmen aus der
 * Benachrichtigungsleiste. Beides läuft über dieselben zwei Schritte, lesen und aufheben, und die
 * werden hier ohne laufenden Dienst geprüft.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BankNotificationCaptureTest {

    private static final String BANK = "de.bank";

    private Context ctx;
    private NotificationBuffer buffer;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.getSharedPreferences("ausgaben_profiles", Context.MODE_PRIVATE).edit().clear().commit();
        ctx.getSharedPreferences("ausgaben_settings", Context.MODE_PRIVATE).edit().clear().commit();
        ProfileManager.migrateLegacyInstallationIfNeeded(ctx);
        AppSelection.clearSeen(ctx);
        buffer = NotificationBuffer.get(ctx);
        buffer.clear();
    }

    private StatusBarNotification meldung(String pkg, String title, String text, String big,
                                          int flags, long time) {
        Notification.Builder b = new Notification.Builder(ctx, "kanal")
                .setContentTitle(title).setContentText(text);
        if (big != null) {
            b.setStyle(new Notification.BigTextStyle().bigText(big));
        }
        Notification n = b.build();
        n.flags |= flags;
        return new StatusBarNotification(pkg, pkg, 1, "tag" + time, 0, 0, 0, n,
                Process.myUserHandle(), time);
    }

    private BankNotificationListener.Captured lesen(StatusBarNotification sbn) {
        return BankNotificationListener.read(sbn, ctx.getPackageName());
    }

    // ---- lesen ----

    @Test
    public void titelUndTextWerdenGelesen() {
        BankNotificationListener.Captured c = lesen(meldung(BANK, "Neuer Umsatz",
                "Sie haben 12,34 € bei REWE bezahlt.", null, 0, 5L));
        assertNotNull(c);
        assertEquals(BANK, c.packageName);
        assertEquals("Neuer Umsatz", c.title);
        assertEquals("Sie haben 12,34 € bei REWE bezahlt.", c.text);
        assertEquals(5L, c.postTime);
    }

    /** Der ausgeklappte Text geht vor: die Kurzfassung ist oft abgeschnitten. */
    @Test
    public void langerTextGehtVor() {
        BankNotificationListener.Captured c = lesen(meldung(BANK, "Neuer Umsatz", "Sie haben…",
                "Sie haben 12,34 € bei REWE bezahlt.", 0, 5L));
        assertEquals("Sie haben 12,34 € bei REWE bezahlt.", c.text);
    }

    /** Ohne Betrag wird nichts aufgehoben – und eine nicht freigegebene App bietet sich nicht an. */
    @Test
    public void ohneBetragWirdNichtsAufgehoben() {
        BankNotificationListener.Captured c =
                lesen(meldung(BANK, "Hinweis", "Ihre neue PIN ist unterwegs.", null, 0, 5L));
        assertEquals(-1L, BankNotificationListener.keep(ctx, c, null));
        assertFalse(AppSelection.candidates(ctx).contains(BANK));
        AppSelection.setAllowed(ctx, Collections.singletonList(BANK));
        assertEquals(-1L, BankNotificationListener.keep(ctx, c, null));
        assertTrue(buffer.all().isEmpty());
    }

    // ---- Beträge ohne Nachkommastellen ----

    private void zahlenformat(String format) {
        ctx.getSharedPreferences("ausgaben_settings", Context.MODE_PRIVATE).edit()
                .putString("p_" + new ProfileManager(ctx).getActiveProfileId() + "_number_format", format)
                .commit();
    }

    /** Freigegeben, Profil schreibt „1.234,56": „1.234 €" ist ein Betrag und wird aufgehoben. */
    @Test
    public void ganzzahlMitTausenderpunktNachZahlenformatDesProfils() {
        zahlenformat("de_group");
        AppSelection.setAllowed(ctx, Collections.singletonList(BANK));
        assertTrue(BankNotificationListener.keep(ctx, lesen(meldung(BANK, "Neuer Umsatz",
                "Sie haben 1.234 € bei Media Markt bezahlt.", null, 0, System.currentTimeMillis())),
                null) > 0);
    }

    /** Nicht freigegeben: dort bleibt die strenge Schwelle – „Noch 3 € Guthaben" bietet keine App an. */
    @Test
    public void nichtFreigegebeneAppBrauchtNachkommastellen() {
        zahlenformat("de_group");
        assertEquals(-1L, BankNotificationListener.keep(ctx, lesen(meldung("de.shop", "Konto",
                "Noch 3 € Guthaben", null, 0, 5L)), null));
        assertFalse(AppSelection.candidates(ctx).contains("de.shop"));
    }

    /** Die Sammelzeile einer Gruppe wiederholt nur ihre Einzelmeldungen. */
    @Test
    public void sammelzeileWirdUebersprungen() {
        assertNull(lesen(meldung(BANK, "Neuer Umsatz", "12,34 € bei REWE", null,
                Notification.FLAG_GROUP_SUMMARY, 5L)));
    }

    @Test
    public void eigeneMeldungenWerdenUebersprungen() {
        assertNull(lesen(meldung(ctx.getPackageName(), "Fällig", "Miete 800,00 €", null, 0, 5L)));
    }

    // ---- aufheben ----

    /** Nicht freigegeben: der Name bleibt als Angebot, der Text nicht. */
    @Test
    public void nichtFreigegebeneAppHinterlaesstNurIhrenNamen() {
        assertEquals(-1L, BankNotificationListener.keep(ctx,
                lesen(meldung(BANK, "Neuer Umsatz", "12,34 € bei REWE", null, 0, 5L)), null));
        assertTrue(buffer.all().isEmpty());
        assertTrue(AppSelection.candidates(ctx).contains(BANK));
    }

    @Test
    public void freigegebeneAppWirdAufgehoben() {
        AppSelection.setAllowed(ctx, Collections.singletonList(BANK));
        long jetzt = System.currentTimeMillis();
        assertTrue(BankNotificationListener.keep(ctx,
                lesen(meldung(BANK, "Neuer Umsatz", "12,34 € bei REWE", null, 0, jetzt)), null) > 0);
        assertEquals(1, buffer.all().size());
    }

    /**
     * Nach dem Anhaken wird übernommen, was noch in der Leiste steht — auch wenn der Dienst dieselbe
     * Meldung schon beim Eintreffen aufgehoben hätte: sie steht dann trotzdem nur einmal da.
     */
    @Test
    public void doppeltUebernommeneMeldungStehtEinmalDa() {
        AppSelection.setAllowed(ctx, Collections.singletonList(BANK));
        long jetzt = System.currentTimeMillis();
        StatusBarNotification sbn = meldung(BANK, "Neuer Umsatz", "12,34 € bei REWE", null, 0, jetzt);
        assertTrue(BankNotificationListener.keep(ctx, lesen(sbn), null) > 0);
        assertEquals(-1L, BankNotificationListener.keep(ctx, lesen(sbn), Collections.singleton(BANK)));
        assertEquals(1, buffer.all().size());
    }

    /** Übernommen wird nur für die frisch angehakten Apps, nicht für alles, was sonst freigegeben ist. */
    @Test
    public void uebernommenWirdNurFuerDieGenanntenApps() {
        AppSelection.setAllowed(ctx, java.util.Arrays.asList(BANK, "de.karte"));
        long jetzt = System.currentTimeMillis();
        assertEquals(-1L, BankNotificationListener.keep(ctx,
                lesen(meldung("de.karte", "Umsatz", "5,00 € bei Lidl", null, 0, jetzt)),
                Collections.singleton(BANK)));
        assertTrue(buffer.all().isEmpty());
    }

    /** Ohne gebundenen Dienst gibt es nichts zu übernehmen – und keinen Absturz. */
    @Test
    public void ohneLaufendenDienstGeschiehtNichts() {
        assertEquals(0, BankNotificationListener.importActive(ctx, Collections.singleton(BANK)));
        BankNotificationListener.scanSeen(ctx);
        assertTrue(buffer.all().isEmpty());
    }
}
