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
        AppSelection.setAllowed(ctx, Arrays.asList("de.bank", "de.karte"));
        buffer.add("de.bank", 1L, "Titel", "12,34 EUR", "a");
        buffer.add("de.karte", 2L, "Titel", "5,00 EUR", "b");
        AppSelection.setAllowed(ctx, Collections.singletonList("de.karte"));
        assertEquals(1, buffer.all().size());
        assertEquals("de.karte", buffer.all().get(0).packageName);
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
