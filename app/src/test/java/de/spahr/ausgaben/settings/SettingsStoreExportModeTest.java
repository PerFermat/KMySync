package de.spahr.ausgaben.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Die drei Export-/Import-Modi und die beiden Fragen, die daran hängen: darf geschrieben werden
 * ({@link SettingsStore#isKmyMode()}), und ist die .kmy die Lesequelle ({@link SettingsStore#isKmySource()}).
 * Der gemischte Modus ({@link SettingsStore#MODE_KMY_CSV}) liest aus der .kmy, schreibt aber nie hinein.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SettingsStoreExportModeTest {

    private final SettingsStore settings = new SettingsStore(ApplicationProvider.getApplicationContext());

    @Test
    public void csvModusWederSchreibbarNochLesequelleKmy() {
        settings.setExportMode(SettingsStore.MODE_CSV);
        assertFalse(settings.isKmyMode());
        assertFalse(settings.isKmySource());
    }

    @Test
    public void kmyModusSchreibbarUndLesequelle() {
        settings.setExportMode(SettingsStore.MODE_KMY);
        assertTrue(settings.isKmyMode());
        assertTrue(settings.isKmySource());
    }

    @Test
    public void gemischterModusNurLesequelleNichtSchreibbar() {
        settings.setExportMode(SettingsStore.MODE_KMY_CSV);
        assertFalse(settings.isKmyMode());
        assertTrue(settings.isKmySource());
    }

    /** Unbekannte/fehlerhafte Werte (z.B. aus einer Sicherung einer neueren Version) fallen auf CSV zurück. */
    @Test
    public void unbekannterWertFaelltAufCsvZurueck() {
        settings.setExportMode("irgendwas");
        assertEquals(SettingsStore.MODE_CSV, settings.getExportMode());
    }

    @Test
    public void isKmySourceModeIstStatischNutzbarOhneGespeichertenWert() {
        assertTrue(SettingsStore.isKmySourceMode(SettingsStore.MODE_KMY));
        assertTrue(SettingsStore.isKmySourceMode(SettingsStore.MODE_KMY_CSV));
        assertFalse(SettingsStore.isKmySourceMode(SettingsStore.MODE_CSV));
        assertFalse(SettingsStore.isKmySourceMode(null));
    }
}
