package de.spahr.ausgaben.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Was die neue Geheimablage zusichern muss.
 *
 * <p>Der Schlüssel kommt hier aus einem gewöhnlichen {@link KeyGenerator}, nicht aus dem
 * Android-Keystore — den kennt Robolectric nicht. Geprüft wird damit alles außer dem Beschaffen des
 * Schlüssels: echtes Ver- und Entschlüsseln, der Typ-Rundlauf, {@code getAll}, {@code clear} und vor
 * allem, <b>dass in der Datei kein Klartext steht</b>. Das Beschaffen selbst belegt nur ein Lauf auf
 * einem echten Gerät; dafür steht der Schritt in der Testanleitung.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SecretStoreTest {

    private static final String PASSWORT = "Streng-Geheim-2026!";

    private Context context;
    private SharedPreferences datei;
    private SecretStore store;

    @Before
    public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        datei = context.getSharedPreferences("test_geheim", Context.MODE_PRIVATE);
        datei.edit().clear().commit();
        KeyGenerator gen = KeyGenerator.getInstance("AES");
        gen.init(256);
        SecretKey key = gen.generateKey();
        store = SecretStore.with(datei, key);
    }

    /**
     * Die eigentliche Zusicherung. Alles andere in dieser Klasse prüft Bequemlichkeit; dies hier
     * prüft den Zweck: Wer die Datei in die Hand bekommt, findet das Passwort nicht darin.
     */
    @Test
    public void dasPasswortStehtNichtInDerDatei() {
        store.edit().putString("p1_serverPassword", PASSWORT).commit();

        String abgelegt = datei.getString("p1_serverPassword", null);
        assertNotNull("es muss etwas abgelegt worden sein", abgelegt);
        assertFalse("Klartext in der Datei", abgelegt.contains(PASSWORT));
        assertFalse("auch nicht in Teilen", abgelegt.contains("Geheim"));
        assertEquals("gelesen kommt es unverändert zurück", PASSWORT,
                store.getString("p1_serverPassword", ""));
    }

    /**
     * Zweimal dasselbe Passwort darf nicht zweimal dasselbe ergeben — sonst verriete ein Vergleich
     * zweier Profile, dass sie dasselbe Passwort tragen. Dafür sorgt der je Aufruf neue IV.
     */
    @Test
    public void gleichesPasswortErgibtVerschiedeneChiffrate() {
        store.edit().putString("p1_serverPassword", PASSWORT).commit();
        store.edit().putString("p2_serverPassword", PASSWORT).commit();

        assertFalse("gleiche Chiffrate trotz gleichem Klartext",
                datei.getString("p1_serverPassword", "a").equals(datei.getString("p2_serverPassword", "b")));
        assertEquals(PASSWORT, store.getString("p1_serverPassword", ""));
        assertEquals(PASSWORT, store.getString("p2_serverPassword", ""));
    }

    /**
     * Im Betrieb wird nur {@code putString} benutzt — aber {@code BackupStore.putTyped} kann beim
     * Einspielen einer Sicherung jeden Prefs-Typ schreiben. Fiele einer davon still heraus, wäre das
     * ein Datenverlust an genau der Stelle, die Daten retten soll.
     */
    @Test
    public void jederTypKommtUnveraendertZurueck() {
        Set<String> menge = new HashSet<>(Arrays.asList("eins", "zwei"));
        store.edit()
                .putString("s", "Text")
                .putBoolean("b", true)
                .putInt("i", 42)
                .putLong("l", 1234567890123L)
                .putFloat("f", 1.5f)
                .putStringSet("m", menge)
                .commit();

        assertEquals("Text", store.getString("s", ""));
        assertTrue(store.getBoolean("b", false));
        assertEquals(42, store.getInt("i", 0));
        assertEquals(1234567890123L, store.getLong("l", 0));
        assertEquals(1.5f, store.getFloat("f", 0f), 0.0001f);
        assertEquals(menge, store.getStringSet("m", null));
    }

    /**
     * {@code getAll} trägt die Sicherung über alle Profile
     * ({@code BackupStore.writeAllBackup}) — es muss entschlüsselte Werte in den richtigen Typen
     * liefern, nicht die Chiffrate.
     */
    @Test
    public void getAllLiefertEntschluesselteWerte() {
        store.edit()
                .putString("p1_serverPassword", PASSWORT)
                .putString("p2_serverPassword", "anderes")
                .commit();

        Map<String, ?> alle = store.getAll();
        assertEquals(2, alle.size());
        assertEquals(PASSWORT, alle.get("p1_serverPassword"));
        assertEquals("anderes", alle.get("p2_serverPassword"));
    }

    /** {@code clear().commit()} benutzt {@code SettingsStore} beim Zurücksetzen auf Werkseinstellung. */
    @Test
    public void clearRaeumtAlles() {
        store.edit().putString("p1_serverPassword", PASSWORT).commit();
        store.edit().clear().commit();

        assertTrue(store.getAll().isEmpty());
        assertFalse(store.contains("p1_serverPassword"));
        assertEquals("", store.getString("p1_serverPassword", ""));
    }

    /**
     * Das Präfixkopieren beim Profilwechsel
     * ({@code ProfileManager.copySettingsFrom}) geht über {@code getAll} hinein und über
     * {@code putString} hinaus — der Rundlauf über beides muss das Passwort erhalten.
     */
    @Test
    public void praefixkopierenErhaeltDasPasswort() {
        store.edit().putString("p1_serverPassword", PASSWORT).commit();

        SharedPreferences.Editor editor = store.edit();
        for (Map.Entry<String, ?> e : store.getAll().entrySet()) {
            if (e.getKey().startsWith("p1_")) {
                editor.putString("p9_" + e.getKey().substring(3), String.valueOf(e.getValue()));
            }
        }
        editor.commit();

        assertEquals(PASSWORT, store.getString("p9_serverPassword", ""));
        assertEquals("das Ursprungsprofil bleibt unberührt", PASSWORT,
                store.getString("p1_serverPassword", ""));
    }

    /**
     * Ein unlesbarer Eintrag — etwa nach einem Schlüsselwechsel im Keystore — darf die übrigen nicht
     * mitreißen. Sonst kostete ein einziger kaputter Wert alle Profile ihr Passwort.
     */
    @Test
    public void einKaputterEintragReisstDieAnderenNichtMit() {
        store.edit().putString("p1_serverPassword", PASSWORT).commit();
        datei.edit().putString("p2_serverPassword", "kein gültiges Base64-Chiffrat").commit();

        assertEquals(PASSWORT, store.getString("p1_serverPassword", ""));
        assertEquals("", store.getString("p2_serverPassword", ""));
        assertEquals("der kaputte fehlt, der gute ist da", 1, store.getAll().size());
    }
}
