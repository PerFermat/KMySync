package de.spahr.ausgaben.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Die Fassungsnummer, die im Sicherungs-Manifest landet, muss auf <b>jedem</b> unterstützten Android
 * zu holen sein — auch auf 8.0 und 8.1.
 *
 * <p>Bis 2.1 holte {@code versionCode} sie über {@code getLongVersionCode()}. Die Methode gibt es erst
 * ab Android 9; darunter wirft der Aufruf {@link NoSuchMethodError}. Das {@code catch (Exception)}
 * daneben sah aus, als fange es genau diesen Fall ab — tut es aber nicht, denn ein {@code Error} ist
 * keine {@code Exception}. Auf 8.0 und 8.1 flog damit jede Sicherung, und zwar wegen eines Wertes, den
 * das Archiv nur <em>zur Information</em> mitführt: Beim Einspielen liest ihn niemand.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class BackupVersionCodeTest {

    /** Android 8.0 — die kleinste Fassung, die die App noch bedient (minSdk 26). */
    @Test
    @Config(sdk = 26)
    public void aufAndroid8StehtDieFassungsnummerImArchiv() throws Exception {
        pruefe();
    }

    /** Android 9 — ab hier gibt es {@code getLongVersionCode()}. */
    @Test
    @Config(sdk = 28)
    public void aufAndroid9StehtDieFassungsnummerImArchiv() throws Exception {
        pruefe();
    }

    /**
     * Auf beiden Wegen muss dieselbe Zahl herauskommen: die aus {@code build.gradle}. Die {@code 0}
     * abzulehnen ist der Kern der Prüfung — sie ist der Rückfallwert des {@code catch} und damit das,
     * was man sähe, wenn der Fehler wieder still verschluckt würde.
     */
    private void pruefe() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        // Gegen das Manifest selbst geprüft, nicht gegen eine hier eingetragene Zahl: Die müsste man
        // bei jeder Veröffentlichung nachziehen, und vergäße es.
        @SuppressWarnings("deprecation")
        int erwartet = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionCode;
        assertNotEquals("Vorbedingung: das Manifest muss eine Fassungsnummer tragen", 0, erwartet);
        assertEquals(erwartet, BackupStore.versionCode(app));
    }
}
