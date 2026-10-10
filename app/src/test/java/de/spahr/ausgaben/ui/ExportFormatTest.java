package de.spahr.ausgaben.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.HashSet;
import java.util.List;

import de.spahr.ausgaben.settings.SettingsStore;

/** Die Auswahl „Format": .kmy-Datei und SQLite-Datenbank stehen als eigene Einträge da. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ExportFormatTest {

    @Test
    public void jederEintragFindetZuSichZurueck() {
        Context ctx = ApplicationProvider.getApplicationContext();
        List<String> labels = ExportFormat.labels(ctx);
        assertEquals(5, labels.size());
        assertEquals("keine Beschriftung doppelt", 5, new HashSet<>(labels).size());
        for (int i = 0; i < labels.size(); i++) {
            assertEquals(i, ExportFormat.position(ExportFormat.mode(i), ExportFormat.database(i)));
        }
    }

    /** Die Datenbank ist keine eigene Speicherart: sie teilt sich Lesen und Schreiben mit der .kmy. */
    @Test
    public void datenbankTeiltSichDieSpeicherartMitDerKmy() {
        assertEquals(SettingsStore.MODE_CSV, ExportFormat.mode(0));
        assertEquals(SettingsStore.MODE_KMY_CSV, ExportFormat.mode(1));
        assertEquals(SettingsStore.MODE_KMY, ExportFormat.mode(2));
        assertEquals(SettingsStore.MODE_KMY_CSV, ExportFormat.mode(3));
        assertEquals(SettingsStore.MODE_KMY, ExportFormat.mode(4));
        assertFalse(ExportFormat.database(2));
        assertTrue(ExportFormat.database(3));
        assertTrue(ExportFormat.database(4));
        // Im CSV-Modus gibt es keine Quelle – die Datenbank-Angabe spielt dann keine Rolle.
        assertEquals(0, ExportFormat.position(SettingsStore.MODE_CSV, true));
    }

    @Test
    public void angabeWirdJeProfilGemerkt() {
        SettingsStore s = new SettingsStore(ApplicationProvider.getApplicationContext());
        assertFalse(s.isKmyDatabase());
        s.setKmyDatabase(true);
        assertTrue(s.isKmyDatabase());
        s.setKmyDatabase(false);
        assertFalse(s.isKmyDatabase());
    }
}
