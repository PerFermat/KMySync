package de.spahr.ausgaben.settings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import de.spahr.ausgaben.db.AppDatabase;

/**
 * Verwaltet mehrere Benutzerprofile in derselben App-Installation. Jedes Profil hat eine eigene
 * Room-Datenbankdatei, eigene Datenquelle (Server/Zugangsdaten/kmy-Pfad, siehe die Profil-Präfixe
 * in {@link SettingsStore}) und eine eigene Akzentfarbe. Alles andere (Sprache, Währung, …) bleibt
 * global über {@link SettingsStore} geregelt.
 *
 * <p>Liegt in einer eigenen Prefs-Datei ({@link #PREFS}), damit ein Werksreset von
 * {@link SettingsStore#clearAll()} die Profilliste nicht versehentlich mitlöscht.</p>
 */
public class ProfileManager {

    private static final String PREFS = "ausgaben_profiles";
    private static final String KEY_PROFILES = "profiles";
    private static final String KEY_ACTIVE = "active_profile_id";

    /** ID des automatisch aus einer Bestandsinstallation angelegten ersten Profils. */
    public static final String LEGACY_PROFILE_ID = "legacy";
    private static final String LEGACY_DB_FILE = "ausgaben.db";

    /** Standard-Akzentfarbe (heutiges Grün) für neue und migrierte Profile. */
    public static final int DEFAULT_ACCENT_COLOR = 0xFF1B5E20;

    /**
     * Auswahl für die Profil-Akzentfarbe: kräftige, gut sättigte Töne statt der blasseren
     * Kategorienfarben – die müssen auf Toolbar/Buttons in Hell- <b>und</b> Dunkelmodus tragen, nicht
     * nur als kleiner Punkt neben einem Kategorienamen stehen. Erster Eintrag ist das bisherige
     * Standard-Grün, damit man es nach einer anderen Wahl wieder auswählen kann.
     */
    public static final int[] ACCENT_PALETTE = {
            DEFAULT_ACCENT_COLOR, // Standard-Grün
            0xFFC62828, // Rot
            0xFF1565C0, // Blau
            0xFFEF6C00, // Orange
            0xFF6A1B9A, // Lila
            0xFF00695C, // Teal
            0xFFAD1457, // Pink
            0xFF283593, // Indigo
            0xFF4E342E, // Braun
            0xFF37474F, // Blaugrau
    };

    public static class Profile {
        public final String id;
        public String name;
        public String dbFileName;
        public int accentColor;
        public long createdAt;

        Profile(String id, String name, String dbFileName, int accentColor, long createdAt) {
            this.id = id;
            this.name = name;
            this.dbFileName = dbFileName;
            this.accentColor = accentColor;
            this.createdAt = createdAt;
        }
    }

    private final SharedPreferences prefs;

    public ProfileManager(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Einmalige Migration beim ersten Start nach dem Update: eine Bestandsinstallation bekommt ein
     * Profil, das auf die vorhandene {@code ausgaben.db} zeigt (Datei bleibt unverändert liegen),
     * eine Neuinstallation ein leeres Erstprofil. Muss vor der ersten {@link SettingsStore}- bzw.
     * {@link AppDatabase}-Nutzung laufen; ist danach ein No-Op (idempotent).
     */
    public static void migrateLegacyInstallationIfNeeded(Context context) {
        ProfileManager pm = new ProfileManager(context);
        if (!pm.getProfiles().isEmpty()) {
            return;
        }
        Context app = context.getApplicationContext();
        boolean hasLegacyDb = app.getDatabasePath(LEGACY_DB_FILE).exists();
        if (hasLegacyDb) {
            SharedPreferences legacySettings = app.getSharedPreferences("ausgaben_settings", Context.MODE_PRIVATE);
            String legacyUser = legacySettings.getString("nextcloud_user", "");
            String name = legacyUser != null && !legacyUser.trim().isEmpty()
                    ? legacyUser.trim() : "Standard";
            Profile profile = new Profile(LEGACY_PROFILE_ID, name, LEGACY_DB_FILE,
                    DEFAULT_ACCENT_COLOR, System.currentTimeMillis());
            pm.saveNewProfile(profile);
            pm.setActiveProfileId(LEGACY_PROFILE_ID);
            copyLegacySettingsUnderPrefix(app, legacySettings, LEGACY_PROFILE_ID);
        } else {
            Profile profile = pm.newProfileRecord("Profil 1");
            pm.saveNewProfile(profile);
            pm.setActiveProfileId(profile.id);
        }
    }

    /**
     * Die Schlüssel aus {@code ausgaben_settings}, die seit dem Mehrprofil-Umbau zum <b>Profil</b>
     * gehören (Datenquelle, Anzeige, Budget). Alles, was hier nicht steht, bleibt global.
     *
     * <p>Nach Typ getrennt, weil {@link SharedPreferences} beim Lesen den Typ verlangt. Wer einen
     * Schlüssel ergänzt, deckt damit zugleich {@link #isProfileSettingsKey} ab – und darüber das
     * Einspielen einer Sicherung aus der Zeit vor den Profilen ({@code BackupStore}).</p>
     */
    private static final String[] PROFILE_STRING_KEYS = {"nextcloud_url", "nextcloud_user",
            "nextcloud_folder", "nextcloud_import_folder", "local_export_tree", "export_mode",
            "kmy_path", "receipt_folder", "server_type", "csv_separator", "default_account",
            "currency", "number_format"};
    private static final String[] PROFILE_BOOLEAN_KEYS = {"show_currency", "dividend_gross",
            "budget_internal", "alias_prompt"};
    private static final String[] PROFILE_LONG_KEYS = {"account_group", "dividend_tax_rate"};

    /**
     * Ob ein <b>unpräfixierter</b> Schlüssel aus {@code ausgaben_settings} zum Profil gehört.
     *
     * <p>Für {@code BackupStore}: eine Sicherung aus der Zeit vor den Profilen führt alle Schlüssel
     * unpräfixiert nebeneinander. Ohne diese Unterscheidung landeten beim Einspielen auch Sprache,
     * Nachtmodus, Schriftgröße und Kategoriefarben unter einem Profil-Präfix – wo sie niemand mehr
     * liest.</p>
     */
    public static boolean isProfileSettingsKey(String key) {
        return enthaelt(PROFILE_STRING_KEYS, key) || enthaelt(PROFILE_BOOLEAN_KEYS, key)
                || enthaelt(PROFILE_LONG_KEYS, key);
    }

    private static boolean enthaelt(String[] keys, String key) {
        for (String k : keys) {
            if (k.equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** Kopiert die bisher unpräfixierten Profil-Keys (Datenquelle + Anzeige/Budget/Orte) einmalig
     *  unter das Profil-Präfix. */
    private static void copyLegacySettingsUnderPrefix(Context app, SharedPreferences legacySettings,
                                                        String profileId) {
        String prefix = "p_" + profileId + "_";
        SharedPreferences.Editor editor = legacySettings.edit();
        for (String key : PROFILE_STRING_KEYS) {
            if (legacySettings.contains(key)) {
                editor.putString(prefix + key, legacySettings.getString(key, ""));
            }
        }
        for (String key : PROFILE_BOOLEAN_KEYS) {
            if (legacySettings.contains(key)) {
                editor.putBoolean(prefix + key, legacySettings.getBoolean(key, true));
            }
        }
        for (String key : PROFILE_LONG_KEYS) {
            if (legacySettings.contains(key)) {
                editor.putLong(prefix + key, legacySettings.getLong(key, 0L));
            }
        }
        editor.apply();

        // Server-Passwort: liegt verschlüsselt unter ausgaben_secret, bis 1.11 aber unter dem
        // unpräfixierten Schlüssel – ab 1.12 liest SettingsStore.getPassword() profilweise über pk().
        // Ohne diesen Umzug stünde jede Bestandsinstallation mit Serveranbindung nach dem Update ohne
        // Zugangsdaten da: hasNextcloudConfig() wird false, Sync und Export scheitern mit einem
        // Auth-Fehler, dessen Ursache nirgends sichtbar wird. Den Klartextstand noch älterer Fassungen
        // holt weiterhin SettingsStore.migratePlaintextPassword() nach.
        String passwordKey = "nextcloud_password";
        SharedPreferences legacySecret = SettingsStore.secretPrefs(app);
        if (legacySecret.contains(passwordKey)) {
            legacySecret.edit()
                    .putString(prefix + passwordKey, legacySecret.getString(passwordKey, ""))
                    .remove(passwordKey)
                    .apply();
        }

        // Orte (PlacesStore) waren bisher ein einziges globales JSON-Objekt – unter das Profil-Präfix
        // kopieren, damit sie für das Legacy-Profil erhalten bleiben.
        SharedPreferences legacyPlaces = app.getSharedPreferences(PlacesStore.PREFS, Context.MODE_PRIVATE);
        if (legacyPlaces.contains(PlacesStore.KEY_ACCOUNTS)) {
            legacyPlaces.edit()
                    .putString(prefix + PlacesStore.KEY_ACCOUNTS,
                            legacyPlaces.getString(PlacesStore.KEY_ACCOUNTS, "{}"))
                    .apply();
        }

        // Gelernte Erkennungsregeln für PDF-Abrechnungen (StatementTemplates) waren ebenfalls global –
        // unter das Profil-Präfix kopieren, sonst müsste das Legacy-Profil sie neu lernen.
        SharedPreferences legacyStatements =
                app.getSharedPreferences(StatementTemplates.PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor statementsEditor = legacyStatements.edit();
        if (legacyStatements.contains(StatementTemplates.KEY_TEMPLATES)) {
            statementsEditor.putString(prefix + StatementTemplates.KEY_TEMPLATES,
                    legacyStatements.getString(StatementTemplates.KEY_TEMPLATES, "[]"));
        }
        if (legacyStatements.contains(StatementTemplates.KEY_ISINS)) {
            statementsEditor.putString(prefix + StatementTemplates.KEY_ISINS,
                    legacyStatements.getString(StatementTemplates.KEY_ISINS, "{}"));
        }
        statementsEditor.apply();
    }

    private Profile newProfileRecord(String name) {
        String id = UUID.randomUUID().toString();
        return new Profile(id, name, dbFileNameFor(id), DEFAULT_ACCENT_COLOR, System.currentTimeMillis());
    }

    /**
     * Datenbankdateiname zu einer Profil-ID, ohne dass das Profil in der Liste stehen muss – dieselbe
     * Regel wie {@link #newProfileRecord}. Für {@link de.spahr.ausgaben.backup.BackupStore}: beim
     * Einspielen einer Alle-Profile-Sicherung lässt sich so der Zieldateiname je Profil-ID berechnen,
     * ohne auf die (ggf. erst im selben Zug wiederhergestellte) Profilliste angewiesen zu sein.
     */
    public static String dbFileNameFor(String profileId) {
        return LEGACY_PROFILE_ID.equals(profileId)
                ? LEGACY_DB_FILE : "ausgaben_" + profileId.replace("-", "") + ".db";
    }

    public List<Profile> getProfiles() {
        List<Profile> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY_PROFILES, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Profile(o.getString("id"), o.getString("name"), o.getString("dbFileName"),
                        (int) o.optLong("accentColor", DEFAULT_ACCENT_COLOR & 0xFFFFFFFFL),
                        o.optLong("createdAt", 0L)));
            }
        } catch (JSONException ignored) {
            // leere Liste
        }
        return out;
    }

    private void saveProfiles(List<Profile> profiles) {
        JSONArray arr = new JSONArray();
        try {
            for (Profile p : profiles) {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("name", p.name);
                o.put("dbFileName", p.dbFileName);
                o.put("accentColor", p.accentColor & 0xFFFFFFFFL);
                o.put("createdAt", p.createdAt);
                arr.put(o);
            }
        } catch (JSONException ignored) {
            return;
        }
        prefs.edit().putString(KEY_PROFILES, arr.toString()).apply();
    }

    private void saveNewProfile(Profile profile) {
        List<Profile> profiles = getProfiles();
        profiles.add(profile);
        saveProfiles(profiles);
    }

    public String getActiveProfileId() {
        String id = prefs.getString(KEY_ACTIVE, "");
        if (!id.isEmpty()) {
            return id;
        }
        List<Profile> profiles = getProfiles();
        return profiles.isEmpty() ? "" : profiles.get(0).id;
    }

    private void setActiveProfileId(String id) {
        prefs.edit().putString(KEY_ACTIVE, id).apply();
    }

    public Profile getActiveProfile() {
        String id = getActiveProfileId();
        for (Profile p : getProfiles()) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        List<Profile> profiles = getProfiles();
        return profiles.isEmpty() ? null : profiles.get(0);
    }

    /**
     * Zwischenspeicher für {@link #currentDbFileName}: Schlüssel ist der Rohzustand der Profil-Prefs,
     * Wert der daraus errechnete Dateiname.
     */
    private static volatile String dbFileNameKey;
    private static volatile String dbFileNameValue;

    /**
     * Dateiname der aktuell aktiven Profil-Datenbank – Komfortmethode für {@link AppDatabase}/Backup.
     *
     * <p>{@code AppDatabase.getInstance} ruft das bei <b>jedem</b> Zugriff, um einen Profilwechsel
     * mitzubekommen. Bis 1.12 wurde dabei jedes Mal die Profil-Liste aus JSON gelesen und in
     * {@link Profile}-Objekte gebaut, nur um ein einziges Feld daraus abzulesen.</p>
     *
     * <p>Gespart wird deshalb der <b>Parse</b>, nicht die Abfrage: die beiden Prefs-Werte werden
     * weiterhin jedes Mal gelesen (nach dem ersten Zugriff hält Android die Datei ohnehin im Speicher)
     * und dienen als Schlüssel. Damit wirkt jede Änderung sofort — auch eine, die an dieser Klasse
     * vorbeigeht, wie das Einspielen einer Alle-Profile-Sicherung, das die Prefs-Datei direkt
     * überschreibt.</p>
     */
    public static String currentDbFileName(Context context) {
        SharedPreferences prefs =
                context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = prefs.getString(KEY_ACTIVE, "") + "\n" + prefs.getString(KEY_PROFILES, "[]");
        if (key.equals(dbFileNameKey)) {
            return dbFileNameValue;
        }
        Profile active = new ProfileManager(context).getActiveProfile();
        String name = active != null ? active.dbFileName : LEGACY_DB_FILE;
        dbFileNameValue = name;
        dbFileNameKey = key;
        return name;
    }

    /**
     * Wechselt das aktive Profil und schließt die offene Datenbank, damit die nächste
     * {@link AppDatabase#getInstance(Context)}-Anfrage die (ggf. neue, leere) Datei des Zielprofils
     * öffnet. Ruft die Activity nicht neu auf – das übernimmt der Aufrufer (Stack-Reset zu
     * MainActivity).
     */
    public void switchTo(Context context, String profileId) {
        setActiveProfileId(profileId);
        AppDatabase.closeInstance();
        // Deutsch/Englisch stehen als Tabellenzeilen in der (jetzt gewechselten) Profil-Datenbank – bei
        // einem frischen, leeren Profil fehlen sie sonst und die Sprachauswahl in den Einstellungen bliebe
        // leer. AppDatabase.getInstance() im Aufruf öffnet dabei automatisch die neue Datei.
        de.spahr.ausgaben.i18n.LocaleManager.init(context);
        // Uhr sofort auf das neue Profil bringen, statt bis zum nächsten Peer-Connect zu warten
        // (full: echte Sync, foss: No-op-Stub – siehe wear/BalanceSync).
        de.spahr.ausgaben.wear.BalanceSync.publish(context);
    }

    public Profile createProfile(String name) {
        Profile profile = newProfileRecord(name == null || name.trim().isEmpty() ? "Profil" : name.trim());
        saveNewProfile(profile);
        return profile;
    }

    public void renameProfile(String profileId, String newName) {
        if (newName == null || newName.trim().isEmpty()) {
            return;
        }
        List<Profile> profiles = getProfiles();
        for (Profile p : profiles) {
            if (p.id.equals(profileId)) {
                p.name = newName.trim();
                break;
            }
        }
        saveProfiles(profiles);
    }

    public void setAccentColor(String profileId, int color) {
        List<Profile> profiles = getProfiles();
        for (Profile p : profiles) {
            if (p.id.equals(profileId)) {
                p.accentColor = color;
                break;
            }
        }
        saveProfiles(profiles);
    }

    /**
     * Löscht ein Profil samt Datenbankdatei und profilspezifischen Einstellungen. Verboten für das
     * letzte verbleibende Profil. War es das aktive Profil, wird vorher automatisch auf ein anderes
     * gewechselt.
     */
    public void deleteProfile(Context context, String profileId) {
        List<Profile> profiles = getProfiles();
        if (profiles.size() <= 1) {
            return;
        }
        Profile toDelete = null;
        for (Profile p : profiles) {
            if (p.id.equals(profileId)) {
                toDelete = p;
                break;
            }
        }
        if (toDelete == null) {
            return;
        }
        if (profileId.equals(getActiveProfileId())) {
            for (Profile p : profiles) {
                if (!p.id.equals(profileId)) {
                    switchTo(context, p.id);
                    break;
                }
            }
        }
        profiles.remove(toDelete);
        saveProfiles(profiles);
        deleteProfileFiles(context, toDelete.dbFileName);
        clearProfilePrefixedSettings(context, profileId);
        // Die Belege liegen seit 2.2 in einem eigenen Ordner je Profil. Ohne diese Zeile bliebe er
        // samt Inhalt liegen – niemand räumte ihn je auf, denn der Aufräumlauf sieht nur den Ordner
        // des aktiven Profils.
        de.spahr.ausgaben.receipt.Receipts.deleteProfile(context, profileId);
    }

    private void deleteProfileFiles(Context context, String dbFileName) {
        Context app = context.getApplicationContext();
        app.getDatabasePath(dbFileName).delete();
        app.getDatabasePath(dbFileName + "-wal").delete();
        app.getDatabasePath(dbFileName + "-shm").delete();
    }

    private void clearProfilePrefixedSettings(Context context, String profileId) {
        Context app = context.getApplicationContext();
        String prefix = "p_" + profileId + "_";
        removePrefixed(app.getSharedPreferences("ausgaben_settings", Context.MODE_PRIVATE), prefix);
        removePrefixed(app.getSharedPreferences(PlacesStore.PREFS, Context.MODE_PRIVATE), prefix);
        removePrefixed(app.getSharedPreferences(StatementTemplates.PREFS, Context.MODE_PRIVATE), prefix);
    }

    private static void removePrefixed(SharedPreferences prefs, String prefix) {
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(prefix)) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    /**
     * Kopiert die ganze Profil-Konfiguration (Datenquelle, Währung/Format/Dividenden/Budget-Werte,
     * Orte, Akzentfarbe) von {@code fromProfileId} nach {@code toProfileId} – z. B. beim Anlegen eines
     * weiteren Profils, um nicht alles neu eintippen zu müssen. Die eigentlichen Buchungsdaten
     * (Datenbank) bleiben davon unberührt.
     *
     * <p>Die Beleg-Merklisten (Prefs-Datei {@code receipts}) werden <b>nicht</b> mitkopiert, und das
     * ist kein Versehen: Sie verweisen auf Dateien des Quellprofils. Im Zielprofil zeigten sie ins
     * Leere, {@code ReceiptSync} striche sie beim ersten Lauf ohnehin wieder – und ein
     * mitgenommener Ordnerwechsel verschöbe fremde Dateien auf dem Server.</p>
     *
     * <p>Der Belegordner selbst ({@code receipt_folder}) wandert dagegen mit, wie jede andere
     * Einstellung der Datenquelle. Beide Profile stehen damit zunächst auf demselben Ordner – genau
     * dafür gibt es die Rückfrage beim Speichern, wenn der Zielordner nicht leer ist.</p>
     */
    public void copySettingsFrom(Context context, String fromProfileId, String toProfileId) {
        Context app = context.getApplicationContext();
        String fromPrefix = "p_" + fromProfileId + "_";
        String toPrefix = "p_" + toProfileId + "_";
        SharedPreferences settingsPrefs = app.getSharedPreferences("ausgaben_settings", Context.MODE_PRIVATE);
        copyPrefixed(settingsPrefs, fromPrefix, toPrefix);
        // Standardkonto und Kontengruppe verweisen auf Zeilen der Quelldatenbank – im neuen (noch
        // leeren) Zielprofil ohne Gültigkeit und irreführend, solange dort kein gleichnamiges Konto
        // existiert.
        settingsPrefs.edit()
                .remove(toPrefix + "default_account")
                .remove(toPrefix + "account_group")
                .apply();
        copyPrefixed(SettingsStore.secretPrefs(app), fromPrefix, toPrefix);
        copyPrefixed(app.getSharedPreferences(PlacesStore.PREFS, Context.MODE_PRIVATE), fromPrefix, toPrefix);
        copyPrefixed(app.getSharedPreferences(StatementTemplates.PREFS, Context.MODE_PRIVATE), fromPrefix, toPrefix);

        for (Profile p : getProfiles()) {
            if (p.id.equals(fromProfileId)) {
                setAccentColor(toProfileId, p.accentColor);
                break;
            }
        }
    }

    private static void copyPrefixed(SharedPreferences prefs, String fromPrefix, String toPrefix) {
        SharedPreferences.Editor editor = prefs.edit();
        for (java.util.Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (!e.getKey().startsWith(fromPrefix)) {
                continue;
            }
            String targetKey = toPrefix + e.getKey().substring(fromPrefix.length());
            Object value = e.getValue();
            if (value instanceof String) {
                editor.putString(targetKey, (String) value);
            } else if (value instanceof Boolean) {
                editor.putBoolean(targetKey, (Boolean) value);
            } else if (value instanceof Long) {
                editor.putLong(targetKey, (Long) value);
            } else if (value instanceof Integer) {
                editor.putInt(targetKey, (Integer) value);
            } else if (value instanceof Float) {
                editor.putFloat(targetKey, (Float) value);
            }
        }
        editor.apply();
    }

    /**
     * Löscht nur die Datenquellen-Einstellungen (Server, Zugangsdaten, kmy-Pfad, …) des aktiven Profils;
     * das Profil selbst und seine Datenbank bleiben bestehen. Für „Nur dieses Profil zurücksetzen" –
     * die Datenbankinhalte räumt {@code Repository.resetAllData(...)} separat auf, bevor diese Methode
     * läuft.
     */
    public void clearActiveProfileSettings(Context context) {
        Profile active = getActiveProfile();
        if (active != null) {
            clearProfilePrefixedSettings(context, active.id);
        }
    }

    /** Werksreset: löscht alle Profile samt ihrer Datenbankdateien und die Profilliste selbst. */
    @SuppressLint("ApplySharedPref")   // Begruendung an SettingsActivity#finishReset
    public void clearAll(Context context) {
        // Erst schließen, dann löschen: SQLite hält die Datei des aktiven Profils sonst noch offen, und
        // das spätere close() schreibt den WAL-Puffer zurück – die eben gelöschte Datenbank stünde je
        // nach Zeitpunkt wieder da, und -wal/-shm blieben ohnehin liegen.
        AppDatabase.closeInstance();
        for (Profile p : getProfiles()) {
            deleteProfileFiles(context, p.dbFileName);
        }
        prefs.edit().clear().commit();
    }
}
