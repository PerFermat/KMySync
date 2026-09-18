package de.spahr.ausgaben.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Die verschlüsselte Ablage für das Server-Passwort – mit einem Schlüssel aus dem Android-Keystore.
 *
 * <h2>Warum nicht mehr {@code EncryptedSharedPreferences}</h2>
 *
 * <p>{@code androidx.security:security-crypto} steckte bis 2.0 auf {@code 1.1.0-alpha06} fest, und
 * die Bibliothek ist inzwischen abgekündigt – weitere Fassungen wird es nicht geben. Für das, was
 * diese App davon braucht, ist sie ohnehin überdimensioniert: Sie bringt Tink mit, um eine Handvoll
 * Zeichenketten zu schützen.</p>
 *
 * <h2>Aufbau</h2>
 *
 * <p>Eine gewöhnliche, app-private {@code SharedPreferences}-Datei, in der jeder <b>Wert</b> als
 * {@code Base64(IV ‖ Chiffrat)} steht. Verschlüsselt wird mit AES-256/GCM; der Schlüssel liegt im
 * Android-Keystore und verlässt ihn nie. Format und Konstanten folgen bewusst
 * {@code BackupCrypto} – dieselbe Schreibweise für dieselbe Sache, nur mit Keystore-Schlüssel statt
 * eines aus einem Passwort abgeleiteten.</p>
 *
 * <p>Die <b>Schlüsselnamen</b> bleiben im Klartext, und das ist Absicht: Sie lauten
 * {@code p1_serverPassword} und verraten damit, <i>welches Profil</i> ein Passwort hinterlegt hat –
 * nicht das Passwort. Die Datei ist app-privat. Sie zusätzlich zu verschleiern hieße, ein zweites
 * Schlüsselschema zu führen (deterministisch, damit man noch suchen kann) für einen Gewinn, den
 * niemand benennen kann. Wer das „nachbessern" will, lese erst diesen Absatz.</p>
 *
 * <p>Jeder Wert trägt vor dem Klartext einen <b>Typ-Kennbuchstaben</b>. Im Betrieb wird zwar nur
 * {@code putString} benutzt, aber {@code BackupStore.putTyped} kann beim Einspielen einer Sicherung
 * grundsätzlich jeden Prefs-Typ schreiben. Ohne die Kennung fielen fremde Typen stillschweigend
 * heraus – ein verschluckter Datenverlust genau an der Stelle, die Daten retten soll.</p>
 *
 * <h2>Fadensicherheit</h2>
 *
 * <p>{@link Cipher} ist nicht fadensicher; jeder Aufruf baut sich deshalb seinen eigenen. Der
 * {@link SecretKey} dagegen ist unveränderlich und darf geteilt werden.</p>
 *
 * <h2>Was hier nicht geprüft werden kann</h2>
 *
 * <p>Robolectric kennt keinen {@code AndroidKeyStore}. Der Schlüssel ist deshalb über
 * {@link #with(SharedPreferences, SecretKey)} einspeisbar: Die Tests prüfen damit echtes Ver- und
 * Entschlüsseln, den Typ-Rundlauf und {@code getAll} mit einem gewöhnlichen AES-Schlüssel. Ungeprüft
 * bleibt allein das Beschaffen des Schlüssels aus dem Keystore – das belegt nur ein Lauf auf einem
 * echten Gerät.</p>
 */
public final class SecretStore implements SharedPreferences {

    /** Der Alias behält den Stamm „ausgaben": applicationId, Prefs und DB-Namen heißen weiterhin so. */
    private static final String KEY_ALIAS = "ausgaben_secret_key";

    /** Die neue Datei. Der alte Name bleibt frei, damit die Übernahme beide nebeneinander sieht. */
    public static final String FILE = "ausgaben_secret2";

    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;

    private final SharedPreferences raw;
    private final SecretKey key;

    private SecretStore(SharedPreferences raw, SecretKey key) {
        this.raw = raw;
        this.key = key;
    }

    /**
     * Die Ablage öffnen, den Schlüssel bei Bedarf anlegen und ein Passwort aus der alten
     * {@code EncryptedSharedPreferences}-Datei einmalig übernehmen.
     *
     * @throws GeneralSecurityException wenn der Keystore den Schlüssel nicht liefert – der Aufrufer
     *         ({@code SettingsStore.createSecretPrefs}) entscheidet dann über Verwerfen und Rückfall
     */
    public static SharedPreferences open(Context context) throws Exception {
        Context app = context.getApplicationContext();
        SecretStore store = new SecretStore(
                app.getSharedPreferences(FILE, Context.MODE_PRIVATE), keystoreKey());
        SecretMigration.uebernehmenFallsNoetig(app, store);
        return store;
    }

    /** Für Tests: dieselbe Ablage mit einem eingespeisten Schlüssel, ohne Keystore und ohne Übernahme. */
    static SecretStore with(SharedPreferences raw, SecretKey key) {
        return new SecretStore(raw, key);
    }

    /**
     * Den Schlüssel aus dem Keystore holen, sonst einen anlegen.
     *
     * <p>Ohne Nutzerauthentifizierung und ohne StrongBox-Zwang: Die App muss auch dann an das
     * Server-Passwort kommen, wenn gerade niemand hinsieht (Widget, geplante Buchungen), und
     * StrongBox gibt es längst nicht auf jedem Gerät – die Forderung würde dort das Anlegen
     * scheitern lassen.</p>
     */
    private static SecretKey keystoreKey() throws GeneralSecurityException, java.io.IOException {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.Entry vorhanden = ks.getEntry(KEY_ALIAS, null);
        if (vorhanden instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) vorhanden).getSecretKey();
        }
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build());
        return gen.generateKey();
    }

    // ---- Verschlüsseln ----

    /**
     * Den IV erzeugt der Cipher selbst. Bei Keystore-GCM-Schlüsseln ist das nicht nur bequem, sondern
     * vorgeschrieben: Ein selbst gesetzter IV wird abgelehnt, und das aus gutem Grund – ein zweimal
     * benutzter IV hebt bei GCM den Schutz auf.
     */
    private String verschluesseln(String klartext) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORM);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        byte[] chiffrat = cipher.doFinal(klartext.getBytes(StandardCharsets.UTF_8));
        byte[] zusammen = new byte[iv.length + chiffrat.length];
        System.arraycopy(iv, 0, zusammen, 0, iv.length);
        System.arraycopy(chiffrat, 0, zusammen, iv.length, chiffrat.length);
        return Base64.encodeToString(zusammen, Base64.NO_WRAP);
    }

    private String entschluesseln(String gespeichert) throws GeneralSecurityException {
        byte[] zusammen = Base64.decode(gespeichert, Base64.NO_WRAP);
        if (zusammen.length <= IV_LEN) {
            throw new GeneralSecurityException("Eintrag zu kurz für IV und Chiffrat");
        }
        Cipher cipher = Cipher.getInstance(TRANSFORM);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, zusammen, 0, IV_LEN));
        byte[] klar = cipher.doFinal(zusammen, IV_LEN, zusammen.length - IV_LEN);
        return new String(klar, StandardCharsets.UTF_8);
    }

    // ---- Typ-Kennbuchstaben ----

    private static String mitTyp(char typ, String wert) {
        return typ + wert;
    }

    /** Liest einen Eintrag; {@code null}, wenn er fehlt oder sich nicht entschlüsseln lässt. */
    private String roh(String schluessel) {
        String gespeichert = raw.getString(schluessel, null);
        if (gespeichert == null) {
            return null;
        }
        try {
            return entschluesseln(gespeichert);
        } catch (Exception nichtLesbar) {
            // Einzelne unlesbare Einträge dürfen die übrigen nicht mitreißen: Das passiert, wenn der
            // Keystore-Schlüssel gewechselt hat, und dann ist genau dieser Wert ohnehin verloren.
            android.util.Log.w("SecretStore",
                    "Eintrag „" + schluessel + "“ nicht entschlüsselbar", nichtLesbar);
            return null;
        }
    }

    private Object entpacken(String mitTyp) {
        if (mitTyp == null || mitTyp.isEmpty()) {
            return null;
        }
        String wert = mitTyp.substring(1);
        switch (mitTyp.charAt(0)) {
            case 's': return wert;
            case 'b': return Boolean.valueOf(wert);
            case 'i': return Integer.valueOf(wert);
            case 'l': return Long.valueOf(wert);
            case 'f': return Float.valueOf(wert);
            case 'M': return mengeAus(wert);
            default:  return wert;   // vor der Typ-Kennung geschriebene Einträge
        }
    }

    private static Set<String> mengeAus(String json) {
        Set<String> out = new HashSet<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                out.add(arr.optString(i, ""));
            }
        } catch (Exception keinFeld) {
            android.util.Log.w("SecretStore", "Zeichenketten-Menge nicht lesbar", keinFeld);
        }
        return out;
    }

    // ---- SharedPreferences ----

    @Override
    public Map<String, ?> getAll() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String schluessel : new HashMap<>(raw.getAll()).keySet()) {
            Object wert = entpacken(roh(schluessel));
            if (wert != null) {
                out.put(schluessel, wert);
            }
        }
        return out;
    }

    @Override
    public String getString(String key, String defValue) {
        Object wert = entpacken(roh(key));
        return wert == null ? defValue : String.valueOf(wert);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<String> getStringSet(String key, Set<String> defValues) {
        Object wert = entpacken(roh(key));
        return wert instanceof Set ? (Set<String>) wert : defValues;
    }

    @Override
    public int getInt(String key, int defValue) {
        Object wert = entpacken(roh(key));
        return wert instanceof Number ? ((Number) wert).intValue() : defValue;
    }

    @Override
    public long getLong(String key, long defValue) {
        Object wert = entpacken(roh(key));
        return wert instanceof Number ? ((Number) wert).longValue() : defValue;
    }

    @Override
    public float getFloat(String key, float defValue) {
        Object wert = entpacken(roh(key));
        return wert instanceof Number ? ((Number) wert).floatValue() : defValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        Object wert = entpacken(roh(key));
        return wert instanceof Boolean ? (Boolean) wert : defValue;
    }

    @Override
    public boolean contains(String key) {
        return raw.contains(key);
    }

    @Override
    public Editor edit() {
        return new VerschluesselnderEditor(raw.edit());
    }

    /** Die Schlüsselnamen stehen im Klartext – ein Horcher bekommt also brauchbare Namen gemeldet. */
    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        raw.registerOnSharedPreferenceChangeListener(listener);
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        raw.unregisterOnSharedPreferenceChangeListener(listener);
    }

    /** Verschlüsselt beim Ablegen; sonst reicht er alles an den Editor der Rohdatei durch. */
    private final class VerschluesselnderEditor implements Editor {

        private final Editor ziel;

        VerschluesselnderEditor(Editor ziel) {
            this.ziel = ziel;
        }

        private Editor ablegen(String key, char typ, String wert) {
            try {
                ziel.putString(key, verschluesseln(mitTyp(typ, wert)));
            } catch (GeneralSecurityException nichtVerschluesselbar) {
                // Lieber nichts ablegen als Klartext: Der Aufrufer merkt es daran, dass der Wert beim
                // Lesen fehlt, und das ist der harmlosere der beiden Fehler.
                android.util.Log.w("SecretStore",
                        "Wert zu „" + key + "“ nicht verschlüsselbar – er wird nicht abgelegt",
                        nichtVerschluesselbar);
            }
            return this;
        }

        @Override
        public Editor putString(String key, String value) {
            return value == null ? remove(key) : ablegen(key, 's', value);
        }

        @Override
        public Editor putStringSet(String key, Set<String> values) {
            if (values == null) {
                return remove(key);
            }
            return ablegen(key, 'M', new JSONArray(values).toString());
        }

        @Override
        public Editor putInt(String key, int value) {
            return ablegen(key, 'i', String.valueOf(value));
        }

        @Override
        public Editor putLong(String key, long value) {
            return ablegen(key, 'l', String.valueOf(value));
        }

        @Override
        public Editor putFloat(String key, float value) {
            return ablegen(key, 'f', String.valueOf(value));
        }

        @Override
        public Editor putBoolean(String key, boolean value) {
            return ablegen(key, 'b', String.valueOf(value));
        }

        @Override
        public Editor remove(String key) {
            ziel.remove(key);
            return this;
        }

        @Override
        public Editor clear() {
            ziel.clear();
            return this;
        }

        @Override
        public boolean commit() {
            return ziel.commit();
        }

        @Override
        public void apply() {
            ziel.apply();
        }
    }

    /** Liegt die Datei zu diesem Prefs-Namen auf der Platte? */
    static boolean dateiVorhanden(Context app, String name) {
        return new File(app.getApplicationInfo().dataDir, "shared_prefs/" + name + ".xml").exists();
    }
}
