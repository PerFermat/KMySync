package de.spahr.ausgaben.notify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import de.spahr.ausgaben.settings.ProfileManager;

/**
 * Die Freigabe der Apps für „Buchungen aus Benachrichtigungen".
 *
 * <p>Festgehalten wird die Zusage dahinter: von einer nicht freigegebenen App bleibt der Name und
 * sonst nichts, und wer eine App abwählt, ist auch ihre schon aufgehobenen Meldungen los.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AppSelectionTest {

    private Context ctx;
    private NotificationBuffer buffer;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.getSharedPreferences("ausgaben_profiles", Context.MODE_PRIVATE).edit().clear().commit();
        ctx.getSharedPreferences("ausgaben_settings", Context.MODE_PRIVATE).edit().clear().commit();
        ProfileManager.migrateLegacyInstallationIfNeeded(ctx);
        new KnownApps(ctx).clear();
        buffer = NotificationBuffer.get(ctx);
        buffer.clear();
        AppSelection.setAllowed(ctx, Collections.<String>emptySet());
        AppSelection.clearSeen(ctx);
    }

    @Test
    public void ohneAuswahlIstNichtsFreigegeben() {
        assertFalse(AppSelection.isAllowed(ctx, "de.bank"));
        assertTrue(AppSelection.candidates(ctx).isEmpty());
    }

    @Test
    public void geseheneAppStehtZurAuswahlIstAberNichtFreigegeben() {
        AppSelection.noteSeen(ctx, "de.bank");
        AppSelection.noteSeen(ctx, "de.bank");
        assertEquals(Collections.singleton("de.bank"), AppSelection.candidates(ctx));
        assertFalse(AppSelection.isAllowed(ctx, "de.bank"));
    }

    @Test
    public void freigabeGiltGenauFuerDieGewaehltenApps() {
        AppSelection.setAllowed(ctx, Arrays.asList("de.bank", "de.karte"));
        assertTrue(AppSelection.isAllowed(ctx, "de.bank"));
        assertTrue(AppSelection.isAllowed(ctx, "de.karte"));
        assertFalse(AppSelection.isAllowed(ctx, "de.shop"));
        assertEquals(new HashSet<>(Arrays.asList("de.bank", "de.karte")), AppSelection.allowed(ctx));
    }

    /** Eine Freigabe ohne „gesehen" (aus einer Sicherung) muss sich wieder abwählen lassen. */
    @Test
    public void freigegebeneAppsStehenImmerZurAuswahl() {
        AppSelection.setAllowed(ctx, Collections.singletonList("de.bank"));
        assertEquals(Collections.singleton("de.bank"), AppSelection.candidates(ctx));
    }

    @Test
    public void abwaehlenLoeschtWasVonDerAppAufgehobenWurde() {
        long jetzt = System.currentTimeMillis();
        AppSelection.setAllowed(ctx, Arrays.asList("de.bank", "de.karte"));
        buffer.add("de.bank", jetzt, "Titel", "12,34 EUR", "a");
        buffer.add("de.karte", jetzt, "Titel", "5,00 EUR", "b");
        AppSelection.setAllowed(ctx, Collections.singletonList("de.karte"));
        assertEquals(1, buffer.all().size());
        assertEquals("de.karte", buffer.all().get(0).packageName);
    }

    // ---- je Profil ----

    /** Wechselt in ein zweites Profil und gibt dessen Kennung zurück. */
    private String zweitesProfil() {
        ProfileManager pm = new ProfileManager(ctx);
        String id = pm.createProfile("Zweites").id;
        pm.switchTo(ctx, id);
        return id;
    }

    @Test
    public void jedesProfilHatSeineEigeneAuswahl() {
        ProfileManager pm = new ProfileManager(ctx);
        String erstes = pm.getActiveProfileId();
        AppSelection.setAllowed(ctx, Collections.singletonList("de.bank"));
        zweitesProfil();
        assertFalse(AppSelection.isAllowed(ctx, "de.bank"));
        AppSelection.setAllowed(ctx, Collections.singletonList("de.karte"));
        pm.switchTo(ctx, erstes);
        assertEquals(Collections.singleton("de.bank"), AppSelection.allowed(ctx));
    }

    /** Der Dienst kennt kein Profil: aufgehoben wird, was irgendeines freigegeben hat. */
    @Test
    public void derDienstFragtUeberAlleProfile() {
        AppSelection.setAllowed(ctx, Collections.singletonList("de.bank"));
        zweitesProfil();
        assertTrue(AppSelection.isAllowedAnywhere(ctx, "de.bank"));
        assertFalse(AppSelection.isAllowedAnywhere(ctx, "de.shop"));
    }

    /** Die gemeinsame Karte: wählt ein Profil die App ab, behält das andere ihre Meldungen. */
    @Test
    public void abwaehlenLaesstDemAnderenProfilSeineMeldungen() {
        AppSelection.setAllowed(ctx, Collections.singletonList("de.bank"));
        buffer.add("de.bank", System.currentTimeMillis(), "Titel", "12,34 EUR", "a");
        zweitesProfil();
        AppSelection.setAllowed(ctx, Collections.singletonList("de.bank"));
        AppSelection.setAllowed(ctx, Collections.<String>emptySet());
        assertEquals(1, buffer.all().size());
    }

    /** Ebenso der Papierkorb: die App bleibt im Angebot, solange ein anderes Profil sie hält. */
    @Test
    public void vergessenInEinemProfilNimmtSieDemAnderenNicht() {
        AppSelection.noteSeen(ctx, "de.bank");
        AppSelection.setAllowed(ctx, Collections.singletonList("de.bank"));
        buffer.add("de.bank", System.currentTimeMillis(), "Titel", "12,34 EUR", "a");
        ProfileManager pm = new ProfileManager(ctx);
        String erstes = pm.getActiveProfileId();
        zweitesProfil();
        AppSelection.setAllowed(ctx, Collections.singletonList("de.bank"));
        AppSelection.forget(ctx, "de.bank");
        assertFalse(AppSelection.isAllowed(ctx, "de.bank"));
        assertEquals(1, buffer.all().size());
        pm.switchTo(ctx, erstes);
        assertTrue(AppSelection.isAllowed(ctx, "de.bank"));
        assertTrue(AppSelection.candidates(ctx).contains("de.bank"));
    }

    /** Die frühere geräteweite Liste wird nicht übernommen — sie gehörte keinem Profil. */
    @Test
    public void alteGeraeteweiteAuswahlGiltNichtMehr() {
        ctx.getSharedPreferences("ausgaben_settings", Context.MODE_PRIVATE).edit()
                .putString("notify_apps", "de.bank").commit();
        assertFalse(AppSelection.isAllowed(ctx, "de.bank"));
        assertFalse(AppSelection.isAllowedAnywhere(ctx, "de.bank"));
    }

    /** Der Papierkorb ohne Regeln: die App ist aus Auswahl und Freigabe heraus, ihr Puffer geleert. */
    @Test
    public void vergesseneAppIstGanzWeg() {
        AppSelection.noteSeen(ctx, "com.android.shell");
        AppSelection.noteSeen(ctx, "de.bank");
        AppSelection.setAllowed(ctx, Arrays.asList("com.android.shell", "de.bank"));
        buffer.add("com.android.shell", System.currentTimeMillis(), "Titel", "12,34 EUR", "a");
        buffer.add("de.bank", System.currentTimeMillis(), "Titel", "5,00 EUR", "b");
        AppSelection.forget(ctx, "com.android.shell");
        assertEquals(Collections.singleton("de.bank"), AppSelection.candidates(ctx));
        assertFalse(AppSelection.isAllowed(ctx, "com.android.shell"));
        assertTrue(AppSelection.isAllowed(ctx, "de.bank"));
        assertEquals(1, buffer.all().size());
        // Vergessen heißt nicht sperren: meldet sie sich wieder, steht sie wieder zur Auswahl.
        AppSelection.noteSeen(ctx, "com.android.shell");
        assertTrue(AppSelection.candidates(ctx).contains("com.android.shell"));
    }

    /** Ausschalten der Funktion vergisst die gesehenen Apps; die Freigabe bleibt fürs Wiedereinschalten. */
    @Test
    public void geseheneAppsLassenSichVergessen() {
        AppSelection.noteSeen(ctx, "de.shop");
        AppSelection.setAllowed(ctx, Collections.singletonList("de.bank"));
        AppSelection.clearSeen(ctx);
        assertEquals(Collections.singleton("de.bank"), AppSelection.candidates(ctx));
    }
}
