package de.spahr.ausgaben.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Die verschlüsselte Ablage muss sich aus einem unbrauchbaren Zustand selbst befreien.
 *
 * <p>Gelangt eine gesicherte Prefs-Datei auf ein Gerät, dessen Keystore den zugehörigen Schlüssel
 * nicht kennt, lässt sie sich nicht mehr öffnen. Bis 2.0 fiel die App dann sofort auf die
 * <b>unverschlüsselte</b> Ersatzdatei zurück — und weil die unbrauchbare Datei niemand wegräumte,
 * scheiterte auch jeder spätere Start. Das Server-Passwort lag von da an dauerhaft ungeschützt,
 * obwohl der Schlüsselspeicher völlig in Ordnung war.</p>
 *
 * <p>Geprüft wird deshalb nicht das Verschlüsseln selbst — dafür bräuchte es einen echten Keystore —,
 * sondern die Entscheidung davor: <b>wann</b> verworfen und neu angelegt wird und wann der Rückfall
 * greifen darf. Das Öffnen ist dazu als {@link SettingsStore.SecretPrefsOpener} einspeisbar.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SecretPrefsRecoveryTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        // Der Merker ist statisch und überlebt sonst von einem Fall zum nächsten.
        SettingsStore.fallbackInUse = false;
    }

    /** Zählt die Versuche und liefert beim n-ten Aufruf eine Datei statt einer Ausnahme. */
    private static final class Opener implements SettingsStore.SecretPrefsOpener {
        private final int gelingtAbVersuch;   // 0 = nie
        int versuche;

        Opener(int gelingtAbVersuch) {
            this.gelingtAbVersuch = gelingtAbVersuch;
        }

        @Override
        public SharedPreferences open(Context app) throws Exception {
            versuche++;
            if (gelingtAbVersuch == 0 || versuche < gelingtAbVersuch) {
                throw new IllegalStateException("Datei passt nicht zum Schlüssel");
            }
            return app.getSharedPreferences("test_verschluesselt", Context.MODE_PRIVATE);
        }
    }

    @Test
    public void gelingtSofort_keinVerwerfen_keinRueckfall() {
        Opener opener = new Opener(1);

        SharedPreferences prefs = SettingsStore.createSecretPrefs(context, opener);

        assertEquals("darf nur einmal versucht haben", 1, opener.versuche);
        assertFalse("kein Rückfall", SettingsStore.fallbackInUse);
        assertSame(context.getSharedPreferences("test_verschluesselt", Context.MODE_PRIVATE), prefs);
    }

    /**
     * Der eigentliche Fall: Die Datei passt nicht zum Schlüssel, der Keystore ist aber heil. Nach dem
     * Verwerfen gelingt der zweite Versuch — die Verschlüsselung bleibt, der Rückfall greift nicht.
     */
    @Test
    public void passtNichtZumSchluessel_wirdVerworfenUndNeuAngelegt() {
        Opener opener = new Opener(2);

        SettingsStore.createSecretPrefs(context, opener);

        assertEquals("genau ein zweiter Versuch, keine Schleife", 2, opener.versuche);
        assertFalse("Passwort darf nicht unverschlüsselt landen", SettingsStore.fallbackInUse);
    }

    /**
     * Die unbrauchbare Datei muss dabei wirklich weg sein, sonst scheitert der nächste Start erneut.
     *
     * <p>Hier stand bis 2.0 {@code "ausgaben_secret"}. Seit das Passwort im {@link SecretStore} liegt,
     * ist es dessen Datei, die verworfen wird — {@code ausgaben_secret} räumt die einmalige Übernahme
     * weg, und sie hier zu löschen hieße, das letzte wegzuwerfen, was das Passwort noch enthält.
     * Deshalb steht der Name nicht mehr ausgeschrieben da, sondern kommt aus derselben Quelle wie im
     * Code.</p>
     */
    @Test
    public void passtNichtZumSchluessel_alteDateiIstDanachLeer() {
        context.getSharedPreferences(SecretStore.FILE, Context.MODE_PRIVATE)
                .edit().putString("rest", "aus einer Sicherung").commit();

        SettingsStore.createSecretPrefs(context, new Opener(2));

        SharedPreferences danach =
                context.getSharedPreferences(SecretStore.FILE, Context.MODE_PRIVATE);
        assertTrue("die unbrauchbare Datei muss verworfen sein", danach.getAll().isEmpty());
    }

    /** Defekter Keystore: Auch der zweite Versuch scheitert – dann ist der Rückfall richtig. */
    @Test
    public void keystoreDefekt_rueckfallGreiftUndMeldetSich() {
        Opener opener = new Opener(0);

        SharedPreferences prefs = SettingsStore.createSecretPrefs(context, opener);

        assertEquals("zweimal versucht, dann aufgegeben", 2, opener.versuche);
        assertTrue("der Nutzer muss es erfahren", SettingsStore.fallbackInUse);
        assertSame(context.getSharedPreferences("ausgaben_secret_fallback", Context.MODE_PRIVATE),
                prefs);
    }
}
