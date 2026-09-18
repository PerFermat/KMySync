package de.spahr.ausgaben.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * Persistiert die App-Einstellungen. Das Server-Passwort liegt verschlüsselt im {@link SecretStore};
 * alle übrigen Felder in normalen Prefs.
 */
public class SettingsStore {

    private static final String PREFS = "ausgaben_settings";

    /**
     * Die Datei, die die Selbstheilung verwirft. Das ist die des {@link SecretStore} – <b>nicht</b>
     * die alte {@code ausgaben_secret} von bis 2.0: Die räumt {@code SecretMigration} weg, sobald sie
     * übernommen wurde, und würde hier fälschlich das letzte gelöscht, was das Passwort noch enthält.
     */
    private static final String SECRET_PREFS = SecretStore.FILE;

    /**
     * Die unverschlüsselte Ersatzdatei – und hier steht der Name <b>ausgeschrieben</b>, statt ihn wie
     * bisher aus {@link #SECRET_PREFS} abzuleiten.
     *
     * <p>Der Grund ist ein Fehler, den erst {@code SecretPrefsRecoveryTest} sichtbar gemacht hat: Mit
     * der Umstellung auf {@link SecretStore} wanderte {@code SECRET_PREFS} auf einen neuen
     * Dateinamen, und die abgeleitete Ersatzdatei wäre stillschweigend mitgewandert. Wer auf 2.0 mit
     * defektem Keystore lief, hat sein Server-Passwort aber genau in <i>dieser</i> Datei liegen — es
     * wäre beim Update unauffindbar geworden. Der Name bleibt deshalb, wo er ist.</p>
     */
    private static final String SECRET_FALLBACK_PREFS = "ausgaben_secret_fallback";

    private static final String KEY_URL = "nextcloud_url";
    private static final String KEY_USER = "nextcloud_user";
    private static final String KEY_PASSWORD = "nextcloud_password";
    private static final String KEY_FOLDER = "nextcloud_folder";
    private static final String KEY_IMPORT_FOLDER = "nextcloud_import_folder";
    private static final String KEY_DEFAULT_ACCOUNT = "default_account";
    private static final String KEY_ACCOUNT_GROUP = "account_group";
    private static final String KEY_NIGHT_MODE = "night_mode";
    private static final String KEY_LOCAL_EXPORT_TREE = "local_export_tree";
    private static final String KEY_EXPORT_MODE = "export_mode";
    private static final String KEY_KMY_PATH = "kmy_path";
    private static final String KEY_APP_LOCK = "app_lock";
    private static final String KEY_GPS_ENABLED = "gps_enabled";
    private static final String KEY_AMOUNT_SUGGEST = "amount_suggest";
    private static final String KEY_RECEIPT_ENABLED = "receipt_enabled";
    private static final String KEY_SCHEDULED_REMINDER = "scheduled_reminder";
    private static final String KEY_WEAR_INSTALL_MODEL = "wear_install_offline_model";
    private static final String KEY_SERVER_TYPE = "server_type";
    private static final String KEY_ALIAS_PROMPT = "alias_prompt";
    private static final String KEY_LANGUAGE = "language";
    private static final String KEY_CURRENCY = "currency";
    private static final String KEY_NUMBER_FORMAT = "number_format";
    private static final String KEY_CSV_SEPARATOR = "csv_separator";
    private static final String KEY_SHOW_CURRENCY = "show_currency";
    private static final String KEY_DIVIDEND_GROSS = "dividend_gross";
    private static final String KEY_DIVIDEND_TAX_RATE = "dividend_tax_rate";
    private static final String KEY_BUDGET_INTERNAL = "budget_internal";
    private static final String KEY_STATEMENT_ENABLED = "statement_enabled";
    private static final String KEY_FONT_SIZE = "font_size";
    private static final String KEY_SMB_KNOWN_HOSTS = "smb_known_hosts";
    private static final String KEY_RECONCILE_PAYEE = "reconcile_payee";
    private static final String KEY_RECONCILE_CATEGORY = "reconcile_category";

    /** So viele zuletzt gefundene SMB-Server werden gemerkt. */
    private static final int MAX_KNOWN_SMB_HOSTS = 10;

    /** Schriftgröße klein (Faktor 0,90). */
    public static final String FONT_SIZE_SMALL = "klein";
    /** Schriftgröße normal (Faktor 1,0) = heutiges Verhalten. Standard. */
    public static final String FONT_SIZE_NORMAL = "normal";
    /** Schriftgröße groß (Faktor 1,15). */
    public static final String FONT_SIZE_LARGE = "gross";
    /** Schriftgröße sehr groß (Faktor 1,30). */
    public static final String FONT_SIZE_XLARGE = "sehr_gross";

    /** Zahlenformat: Tausenderpunkt + Dezimalkomma („1.234,56"). */
    public static final String NUMBER_FORMAT_DE_GROUP = "de_group";
    /** Tausenderkomma + Dezimalpunkt („1,234.56"). */
    public static final String NUMBER_FORMAT_EN_GROUP = "en_group";
    /** Ohne Tausendertrennung, Dezimalkomma („1234,56"). Standard = heutiges Verhalten. */
    public static final String NUMBER_FORMAT_PLAIN_COMMA = "plain_comma";
    /** Ohne Tausendertrennung, Dezimalpunkt („1234.56"). */
    public static final String NUMBER_FORMAT_PLAIN_DOT = "plain_dot";

    /** CSV-Spaltentrennzeichen: Semikolon (Standard). */
    public static final String CSV_SEP_SEMICOLON = ";";
    /** CSV-Spaltentrennzeichen: Komma. */
    public static final String CSV_SEP_COMMA = ",";

    /** Server-Typ: Nextcloud (Standard, mit {@code /remote.php/dav/files/<user>/}). */
    public static final String SERVER_NEXTCLOUD = "nextcloud";
    /** Server-Typ: generischer WebDAV-Server; die Basis-URL ist bereits die DAV-Wurzel. */
    public static final String SERVER_WEBDAV = "webdav";
    /** Server-Typ: SMB/Samba-Freigabe; die „URL" ist {@code smb://Host/Freigabe[/Basis]}. */
    public static final String SERVER_SMB = "smb";

    /** Export-/Import-Modus: kMyMoney-CSV wie bisher. */
    public static final String MODE_CSV = "csv";
    /** Export-/Import-Modus: direkt in eine KMyMoney-.kmy-Datei. */
    public static final String MODE_KMY = "kmy";

    private final SharedPreferences prefs;
    private final SharedPreferences secret;
    private final String profilePrefix;

    public SettingsStore(Context context) {
        Context app = context.getApplicationContext();
        this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.secret = secretPrefs(app);
        this.profilePrefix = "p_" + new ProfileManager(app).getActiveProfileId() + "_";
        migratePlaintextPassword();
    }

    /**
     * Präfixiert Schlüssel, die zur Datenquelle des aktiven Profils gehören (Server, Zugangsdaten,
     * kmy-Pfad, Export-Modus, …) oder auf Zeilen der profileigenen Datenbank verweisen
     * (Standardkonto, Kontengruppe). Alle übrigen Einstellungen bleiben unpräfixiert = global.
     */
    private String pk(String baseKey) {
        return profilePrefix + baseKey;
    }

    /**
     * Die verschlüsselte Prefs-Datei wird einmal je Prozess aufgebaut.
     *
     * <p>{@link #createSecretPrefs} kostet einen Keystore-Zugriff und den Aufbau der
     * {@code EncryptedSharedPreferences} — bis 1.12 bei <b>jedem</b> {@code new SettingsStore(…)}, und
     * der entsteht an einigen Stellen pro Abfrage neu (Depot-Kennzahlen, GPS-Prüfung beim Buchen). Die
     * Datei hängt am Gerät und nicht am Profil, ein Zwischenspeichern ist also unbedenklich; die
     * Instanz ist wie jede {@code SharedPreferences} fadensicher.</p>
     */
    private static volatile SharedPreferences secretPrefsCache;

    /**
     * Ob die verschlüsselte Ablage nicht zustande kam und ersatzweise eine <b>unverschlüsselte</b>
     * benutzt wird.
     *
     * <p>Der Rückfall ist gewollt — ein defekter Keystore soll die App nicht unbrauchbar machen —, aber
     * er darf nicht unbemerkt bleiben: Betroffen ist das Server-Passwort. Die Masken, die es abfragen,
     * sagen es dem Nutzer (siehe {@code settings_secret_fallback}).</p>
     *
     * <p>Paketsichtbar statt privat, damit die Tests im selben Paket ihn lesen und vor jedem Fall
     * zurücksetzen können — er ist statisch und überlebt sonst von einem Test zum nächsten.</p>
     */
    static volatile boolean fallbackInUse;

    /** Ob das Server-Passwort gerade unverschlüsselt abgelegt wird – siehe {@link #fallbackInUse}. */
    public static boolean isSecretStorageUnencrypted(Context context) {
        secretPrefs(context);   // stellt sicher, dass der Versuch überhaupt gelaufen ist
        return fallbackInUse;
    }

    /**
     * Verschlüsselte Prefs-Datei fürs Server-Passwort – auch für {@link ProfileManager#copySettingsFrom}
     * und {@code BackupStore} (Alle-Profile-Sicherung).
     */
    public static SharedPreferences secretPrefs(Context context) {
        SharedPreferences vorhanden = secretPrefsCache;
        if (vorhanden != null) {
            return vorhanden;
        }
        synchronized (SettingsStore.class) {
            if (secretPrefsCache == null) {
                secretPrefsCache = createSecretPrefs(context.getApplicationContext());
            }
            return secretPrefsCache;
        }
    }

    /** Öffnet die verschlüsselte Ablage – die eine Stelle, an der es schiefgehen kann. */
    interface SecretPrefsOpener {
        SharedPreferences open(Context app) throws Exception;
    }

    private static SharedPreferences openEncrypted(Context app) throws Exception {
        return SecretStore.open(app);
    }

    private static SharedPreferences createSecretPrefs(Context app) {
        return createSecretPrefs(app, SettingsStore::openEncrypted);
    }

    /**
     * Zwei Fehlschläge sehen gleich aus, meinen aber Verschiedenes.
     *
     * <p><b>Die Datei passt nicht zum Schlüssel.</b> So sieht es aus, wenn eine gesicherte Prefs-Datei
     * auf ein Gerät gelangt, dessen Keystore den zugehörigen Schlüssel nicht kennt – der wird nie
     * mitgesichert. Der Schlüsselspeicher ist dabei völlig in Ordnung. Würden wir hier gleich
     * zurückfallen, bliebe es dabei: Niemand räumt die unbrauchbare Datei je weg, also scheitert auch
     * jeder spätere Start, und das Server-Passwort läge von da an dauerhaft unverschlüsselt. Deshalb
     * einmal verwerfen und neu anlegen. Das kostet den Nutzer das Passwort – verloren war es ohnehin,
     * denn entschlüsseln konnte es niemand mehr –, aber die Verschlüsselung bleibt.
     *
     * <p><b>Der Keystore ist defekt.</b> Dann scheitert auch der zweite Versuch, und es greift der
     * unverschlüsselte Rückfall: lieber ungeschützt als eine App, die sich nicht mehr öffnen lässt.
     * Nicht stillschweigend – siehe {@link #fallbackInUse}.
     *
     * <p>Seit Version 2.1 sichert Android die Dateien dieser App nicht mehr mit
     * ({@code allowBackup="false"}), der erste Fall sollte also seltener werden. Verlassen wollen wir
     * uns darauf nicht: Auch ein abgebrochenes Schreiben hinterlässt eine Datei, die nicht mehr aufgeht.
     *
     * @param opener nur für Tests einspeisbar; im Betrieb stets {@link #openEncrypted}
     */
    static SharedPreferences createSecretPrefs(Context app, SecretPrefsOpener opener) {
        try {
            return opener.open(app);
        } catch (Exception passtNicht) {
            android.util.Log.w("SettingsStore",
                    "Verschlüsselte Ablage nicht lesbar – wird einmal verworfen und neu angelegt",
                    passtNicht);
            app.deleteSharedPreferences(SECRET_PREFS);
            try {
                return opener.open(app);
            } catch (Exception keystoreDefekt) {
                android.util.Log.w("SettingsStore",
                        "Keystore nicht verfügbar – Passwort liegt unverschlüsselt", keystoreDefekt);
                fallbackInUse = true;
                return app.getSharedPreferences(SECRET_FALLBACK_PREFS, Context.MODE_PRIVATE);
            }
        }
    }

    /** Einmalige Migration: früher im Klartext gespeichertes Passwort verschlüsselt übernehmen. */
    private void migratePlaintextPassword() {
        if (prefs.contains(KEY_PASSWORD)) {
            String legacy = prefs.getString(KEY_PASSWORD, "");
            if (legacy != null && !legacy.isEmpty() && getPassword().isEmpty()) {
                secret.edit().putString(pk(KEY_PASSWORD), legacy).apply();
            }
            prefs.edit().remove(KEY_PASSWORD).apply();
        }
    }

    public String getUrl() {
        return prefs.getString(pk(KEY_URL), "").trim();
    }

    /** Nur die Adresse ändern (z. B. korrigierter SMB-Port); alles andere bleibt stehen. */
    public void setUrl(String url) {
        prefs.edit().putString(pk(KEY_URL), url == null ? "" : url.trim()).apply();
    }

    public String getUser() {
        return prefs.getString(pk(KEY_USER), "").trim();
    }

    public String getPassword() {
        return secret.getString(pk(KEY_PASSWORD), "");
    }

    public boolean hasPassword() {
        return !getPassword().isEmpty();
    }

    /** Nur das Server-Passwort setzen (Wiederherstellen einer Sicherung); alles andere bleibt stehen. */
    public void setPassword(String password) {
        secret.edit().putString(pk(KEY_PASSWORD), password == null ? "" : password).apply();
    }

    /** Empfänger für die Kassensturz-Ausgleichsbuchung (leer = noch nicht festgelegt). */
    public String getReconcilePayee() {
        return prefs.getString(KEY_RECONCILE_PAYEE, "").trim();
    }

    /** Kategorie für die Kassensturz-Ausgleichsbuchung (leer = noch nicht festgelegt). */
    public String getReconcileCategory() {
        return prefs.getString(KEY_RECONCILE_CATEGORY, "").trim();
    }

    public void setReconcileTarget(String payee, String category) {
        prefs.edit()
                .putString(KEY_RECONCILE_PAYEE, payee == null ? "" : payee.trim())
                .putString(KEY_RECONCILE_CATEGORY, category == null ? "" : category.trim())
                .apply();
    }

    public String getFolder() {
        return prefs.getString(pk(KEY_FOLDER), "").trim();
    }

    public String getImportFolder() {
        return prefs.getString(pk(KEY_IMPORT_FOLDER), "").trim();
    }

    public String getDefaultAccount() {
        return prefs.getString(pk(KEY_DEFAULT_ACCOUNT), "").trim();
    }

    public void setDefaultAccount(String account) {
        prefs.edit().putString(pk(KEY_DEFAULT_ACCOUNT), account == null ? "" : account.trim()).apply();
    }

    /**
     * Gewählte Kontengruppe (Zeile in der profileigenen Datenbank); 0 = alle Konten. Je Profil
     * getrennt, da die Kontengruppen-ID sich auf die Datenbank des jeweiligen Profils bezieht.
     */
    public long getAccountGroup() {
        return prefs.getLong(pk(KEY_ACCOUNT_GROUP), 0L);
    }

    public void setAccountGroup(long groupId) {
        prefs.edit().putLong(pk(KEY_ACCOUNT_GROUP), groupId <= 0 ? 0L : groupId).apply();
    }

    /** Persistierte SAF-Tree-URI für den lokalen Export (leer = noch nicht gewählt). */
    public String getLocalExportTree() {
        return prefs.getString(pk(KEY_LOCAL_EXPORT_TREE), "");
    }

    public void setLocalExportTree(String uri) {
        prefs.edit().putString(pk(KEY_LOCAL_EXPORT_TREE), uri == null ? "" : uri).apply();
    }

    public boolean hasNextcloudConfig() {
        return !getUrl().isEmpty() && !getUser().isEmpty() && hasPassword();
    }

    /**
     * Ist ein entferntes Sync-Ziel konfiguriert? SMB: sobald Host+Freigabe gesetzt sind (Gast erlaubt);
     * WebDAV/Nextcloud: URL+Benutzer+Passwort.
     */
    public boolean hasRemoteConfig() {
        if (isSmbServer()) {
            String[] smb = parseSmb(getUrl());
            return !smb[0].isEmpty() && !smb[1].isEmpty();
        }
        return hasNextcloudConfig();
    }

    /**
     * Zerlegt {@code smb://Host[:Port]/Freigabe/Basis} (auch {@code //Host/...} oder {@code Host/...})
     * in {@code [host, share, base, port]} – leere Strings bei fehlenden Teilen. Der Port wird nur
     * abgetrennt, wenn dort wirklich eine gültige Portnummer steht; sonst bleibt er Teil des Hosts,
     * damit ein Tippfehler als „Server nicht erreichbar" auffällt statt still zu verschwinden.
     */
    public static String[] parseSmb(String url) {
        String s = url == null ? "" : url.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        }
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) {
            return new String[]{"", "", "", ""};
        }
        String[] parts = s.split("/", 3);
        String host = parts.length > 0 ? parts[0].trim() : "";
        String share = parts.length > 1 ? parts[1].trim() : "";
        String base = parts.length > 2 ? parts[2].trim() : "";
        String port = "";
        // Bei IPv6 in Klammern erst hinter der schließenden Klammer nach dem Port suchen.
        int colon = host.lastIndexOf(':');
        if (colon > 0 && colon > host.lastIndexOf(']') && isPort(host.substring(colon + 1))) {
            port = host.substring(colon + 1);
            host = host.substring(0, colon);
        }
        return new String[]{host, share, base, port};
    }

    /**
     * Setzt in einer SMB-Adresse den Port neu; {@code 0} oder 445 lassen ihn ganz weg (Standardport).
     * Freigabe und Basisordner bleiben unverändert – gebraucht, wenn die Verbindung nur über den
     * Standardport zustande kam und die gespeicherte Adresse das künftig widerspiegeln soll.
     */
    public static String withPort(String url, int port) {
        String[] parts = parseSmb(url);
        if (parts[0].isEmpty()) {
            return url == null ? "" : url.trim();
        }
        StringBuilder sb = new StringBuilder("smb://").append(parts[0]);
        if (port > 0 && port != 445) {
            sb.append(':').append(port);
        }
        if (!parts[1].isEmpty()) {
            sb.append('/').append(parts[1]);
        }
        if (!parts[2].isEmpty()) {
            sb.append('/').append(parts[2]);
        }
        return sb.toString();
    }

    /** true, wenn {@code text} nur aus Ziffern besteht und eine gültige Portnummer ergibt. */
    private static boolean isPort(String text) {
        if (text.isEmpty() || text.length() > 5) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return false;
            }
        }
        int value = Integer.parseInt(text);
        return value >= 1 && value <= 65535;
    }

    /**
     * Zuletzt im Netz gefundene SMB-Server als {@code Name|Host|Arbeitsgruppe|Port}-Zeilen (höchstens
     * {@link #MAX_KNOWN_SMB_HOSTS}; Arbeitsgruppe und Port dürfen fehlen – ältere dreiteilige Zeilen
     * bleiben so lesbar). Der Einrichtungsassistent zeigt sie sofort an, während die neue Suche läuft.
     */
    public java.util.List<String[]> getKnownSmbHosts() {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (String line : prefs.getString(KEY_SMB_KNOWN_HOSTS, "").split("\n")) {
            String[] parts = line.split("\\|", 4);
            if (parts.length >= 2 && !parts[1].isEmpty()) {
                out.add(new String[]{parts[0], parts[1], parts.length > 2 ? parts[2] : "",
                        parts.length > 3 ? parts[3] : ""});
            }
        }
        return out;
    }

    public void setKnownSmbHosts(java.util.List<String[]> hosts) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String[] h : hosts) {
            if (h.length < 2 || h[1] == null || h[1].isEmpty() || h[1].indexOf('|') >= 0) {
                continue;
            }
            if (n++ >= MAX_KNOWN_SMB_HOSTS) {
                break;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(h[0] == null || h[0].isEmpty() ? h[1] : h[0].replace('|', ' '))
                    .append('|').append(h[1])
                    .append('|').append(h.length > 2 && h[2] != null ? h[2].replace('|', ' ') : "")
                    .append('|').append(h.length > 3 && h[3] != null ? h[3].replace('|', ' ') : "");
        }
        prefs.edit().putString(KEY_SMB_KNOWN_HOSTS, sb.toString()).apply();
    }

    /** {@link #MODE_CSV} (Standard) oder {@link #MODE_KMY}. */
    public String getExportMode() {
        return prefs.getString(pk(KEY_EXPORT_MODE), MODE_CSV);
    }

    public void setExportMode(String exportMode) {
        prefs.edit().putString(pk(KEY_EXPORT_MODE), MODE_KMY.equals(exportMode) ? MODE_KMY : MODE_CSV).apply();
    }

    public boolean isKmyMode() {
        return MODE_KMY.equals(getExportMode());
    }

    /** {@link #SERVER_NEXTCLOUD} (Standard) oder {@link #SERVER_WEBDAV}. */
    public String getServerType() {
        return prefs.getString(pk(KEY_SERVER_TYPE), SERVER_NEXTCLOUD);
    }

    public void setServerType(String serverType) {
        prefs.edit().putString(pk(KEY_SERVER_TYPE), normalizeServerType(serverType)).apply();
    }

    /** true = Nextcloud-Pfadschema; false = generischer WebDAV-Server oder SMB (Basis-URL = Wurzel). */
    public boolean isNextcloudServer() {
        return SERVER_NEXTCLOUD.equals(getServerType());
    }

    public boolean isSmbServer() {
        return SERVER_SMB.equals(getServerType());
    }

    /** Relativer Nextcloud-Pfad zur .kmy inkl. Dateiname, z. B. {@code KMyMoney/gdyx.kmy}. */
    public String getKmyPath() {
        return prefs.getString(pk(KEY_KMY_PATH), "").trim();
    }

    /** Standard: dem System folgen, bis der Nutzer aktiv umschaltet. */
    public int getNightMode() {
        return prefs.getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    public boolean isDarkMode() {
        return getNightMode() == AppCompatDelegate.MODE_NIGHT_YES;
    }

    public void setNightMode(int mode) {
        prefs.edit().putInt(KEY_NIGHT_MODE, mode).apply();
    }

    /** Optionale biometrische App-Sperre (Standard: aus). */
    public boolean isAppLockEnabled() {
        return prefs.getBoolean(KEY_APP_LOCK, false);
    }

    public void setAppLockEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_APP_LOCK, enabled).apply();
    }

    /**
     * Standort-/GPS-Nutzung (Standard: aus). Aus = keine Berechtigungsabfrage, keine GPS-Koordinaten in
     * Buchungsnotizen, keine Betrag-only-Erfassung am Handy, kein Alias-Standort.
     */
    /** Tägliche Erinnerung an fällige geplante Buchungen; standardmäßig aus. */
    public boolean isScheduledReminderEnabled() {
        return prefs.getBoolean(KEY_SCHEDULED_REMINDER, false);
    }

    public void setScheduledReminderEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SCHEDULED_REMINDER, enabled).apply();
    }

    public boolean isGpsEnabled() {
        return prefs.getBoolean(KEY_GPS_ENABLED, false);
    }

    public void setGpsEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_GPS_ENABLED, enabled).apply();
    }

    /**
     * Ob der Betrag bei der Empfängersuche mitentscheidet (Standard: aus). Betrifft die Ziffernmaske
     * und die Vorbelegung im Buchungseditor – also die Stellen, an denen man den Empfänger ohnehin
     * selbst wählen kann. Die reine Spracheingabe ohne Empfänger (Handy wie Uhr) nutzt den Betrag
     * unabhängig davon, denn dort gibt es nichts zu wählen.
     */
    public boolean isAmountSuggestEnabled() {
        return prefs.getBoolean(KEY_AMOUNT_SUGGEST, false);
    }

    public void setAmountSuggestEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AMOUNT_SUGGEST, enabled).apply();
    }

    /**
     * Ob die Uhr das Offline-Sprachpaket der gewählten Sprache installieren darf (nur im {@code full}-Build
     * sichtbar/relevant). Standard aus – dann greift offline der Zahlenblock-Fallback.
     */
    public boolean isWearInstallModel() {
        return prefs.getBoolean(KEY_WEAR_INSTALL_MODEL, false);
    }

    public void setWearInstallModel(boolean enabled) {
        prefs.edit().putBoolean(KEY_WEAR_INSTALL_MODEL, enabled).apply();
    }

    /** Auslieferungszustand: alle Einstellungen inkl. gespeichertem Server-Passwort löschen. */
    public void clearAll() {
        prefs.edit().clear().commit();
        secret.edit().clear().commit();
    }

    /** Belegfotos je Buchung aufnehmen und ins Netzlaufwerk synchronisieren (Standard: an). */
    public boolean isReceiptEnabled() {
        return prefs.getBoolean(KEY_RECEIPT_ENABLED, true);
    }

    public void setReceiptEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_RECEIPT_ENABLED, enabled).apply();
    }

    /** Nachfrage, ob ein geänderter Empfänger als Alias gemerkt werden soll (Standard: an). */
    public boolean isAliasPromptEnabled() {
        return prefs.getBoolean(pk(KEY_ALIAS_PROMPT), true);
    }

    public void setAliasPromptEnabled(boolean enabled) {
        prefs.edit().putBoolean(pk(KEY_ALIAS_PROMPT), enabled).apply();
    }

    /**
     * Sprachcode der App-Texte. Ist noch keine Sprache gewählt (Erststart), bestimmt die Handy-Sprache:
     * jede mitgelieferte Sprache wird übernommen, jede andere fällt auf „en" zurück (Standardsprache
     * Englisch). Nachgeladene Sprachen zählen hier nicht – die gibt es beim Erststart noch nicht.
     */
    public String getLanguage() {
        String stored = prefs.getString(KEY_LANGUAGE, "");
        if (!stored.isEmpty()) {
            return stored;
        }
        String phone = java.util.Locale.getDefault().getLanguage();
        return de.spahr.ausgaben.i18n.LocaleManager.isBuiltIn(phone) ? phone : "en";
    }

    public void setLanguage(String code) {
        prefs.edit().putString(KEY_LANGUAGE, code == null ? "" : code.trim()).apply();
    }

    /** Standard-Währungskennzeichen des Profils (für Konten ohne eigene Währung). Standard „€". */
    public String getCurrency() {
        String c = prefs.getString(pk(KEY_CURRENCY), "€");
        return c == null || c.trim().isEmpty() ? "€" : c.trim();
    }

    public void setCurrency(String currency) {
        prefs.edit().putString(pk(KEY_CURRENCY), currency == null ? "" : currency.trim()).apply();
    }

    /** Gewähltes Zahlenformat (siehe {@code NUMBER_FORMAT_*}). Standard = heutiges Verhalten (1234,56). */
    public String getNumberFormat() {
        String v = prefs.getString(pk(KEY_NUMBER_FORMAT), NUMBER_FORMAT_PLAIN_COMMA);
        return v == null || v.trim().isEmpty() ? NUMBER_FORMAT_PLAIN_COMMA : v.trim();
    }

    public void setNumberFormat(String format) {
        prefs.edit().putString(pk(KEY_NUMBER_FORMAT),
                format == null ? NUMBER_FORMAT_PLAIN_COMMA : format.trim()).apply();
    }

    /** Gewähltes CSV-Spaltentrennzeichen (";" Standard oder ","). Nur im CSV-Modus relevant. */
    public String getCsvSeparator() {
        String v = prefs.getString(pk(KEY_CSV_SEPARATOR), CSV_SEP_SEMICOLON);
        return CSV_SEP_COMMA.equals(v) ? CSV_SEP_COMMA : CSV_SEP_SEMICOLON;
    }

    public void setCsvSeparator(String separator) {
        prefs.edit().putString(pk(KEY_CSV_SEPARATOR),
                CSV_SEP_COMMA.equals(separator) ? CSV_SEP_COMMA : CSV_SEP_SEMICOLON).apply();
    }

    /** Gewählte Schriftgröße (siehe {@code FONT_SIZE_*}). Standard = normal (heutiges Verhalten). */
    public String getFontSize() {
        String v = prefs.getString(KEY_FONT_SIZE, FONT_SIZE_NORMAL);
        return v == null || v.trim().isEmpty() ? FONT_SIZE_NORMAL : v.trim();
    }

    public void setFontSize(String size) {
        prefs.edit().putString(KEY_FONT_SIZE, size == null ? FONT_SIZE_NORMAL : size.trim()).apply();
    }

    /** Ob das Währungskennzeichen an Beträge angehängt wird (Standard an). */
    public boolean isCurrencyShown() {
        return prefs.getBoolean(pk(KEY_SHOW_CURRENCY), true);
    }

    public void setCurrencyShown(boolean shown) {
        prefs.edit().putBoolean(pk(KEY_SHOW_CURRENCY), shown).apply();
    }

    /** Dividenden im Depot brutto (true, Standard) oder netto (false) anzeigen/verrechnen. */
    public boolean isDividendGross() {
        return prefs.getBoolean(pk(KEY_DIVIDEND_GROSS), true);
    }

    public void setDividendGross(boolean gross) {
        prefs.edit().putBoolean(pk(KEY_DIVIDEND_GROSS), gross).apply();
    }

    /**
     * Steuersatz auf Dividenden in Prozent (25 % Kapitalertragsteuer + 5,5 % Soli = 26,375). Belegt in der
     * Wertpapier-Erfassung die Steuer vor, solange erst eines der drei Geldfelder feststeht; 0 = keine
     * Vorbelegung. Für die Anzeige bereits gebuchter Dividenden spielt der Wert keine Rolle – dort zählt
     * allein die gespeicherte Differenz zwischen brutto und netto.
     *
     * <p>Abgelegt wird in Hunderttausendsteln, damit fünf Nachkommastellen exakt bleiben: als
     * {@code float} reichte die Genauigkeit bei Sätzen nahe 100 % nicht mehr für die fünfte Stelle.</p>
     */
    public double getDividendTaxPercent() {
        try {
            return prefs.getLong(pk(KEY_DIVIDEND_TAX_RATE), 0L) / 100000.0;
        } catch (ClassCastException e) {
            // Frühere Fassung: als float gespeichert. Einmal gelesen, schreibt das Speichern den Wert
            // im neuen Format zurück.
            return prefs.getFloat(pk(KEY_DIVIDEND_TAX_RATE), 0f);
        }
    }

    public void setDividendTaxPercent(double percent) {
        double p = percent < 0 || percent >= 100 ? 0 : percent;
        prefs.edit().putLong(pk(KEY_DIVIDEND_TAX_RATE), Math.round(p * 100000.0)).apply();
    }

    /**
     * Bankabrechnungen als PDF einlesen — <b>Standard: aus</b>.
     *
     * <p>Die Erkennung trägt nicht bei jeder Bank, und wer ihr blind vertraut, verbucht im ungünstigen
     * Fall falsche Zahlen. Deshalb muss man sie ausdrücklich einschalten; dabei sagt die App, woran sie
     * gemessen wurde. Ausgeschaltet verschwinden im Depot nur die Wege dorthin — die gelernten Vorlagen
     * bleiben erhalten und stehen nach dem Einschalten wieder bereit.</p>
     */
    public boolean isStatementEnabled() {
        return prefs.getBoolean(KEY_STATEMENT_ENABLED, false);
    }

    public void setStatementEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_STATEMENT_ENABLED, enabled).apply();
    }

    /** Budget app-intern aus dem Verlauf berechnen (true) statt aus KMyMoney importieren (false, Standard). */
    public boolean isBudgetInternal() {
        return prefs.getBoolean(pk(KEY_BUDGET_INTERNAL), false);
    }

    public void setBudgetInternal(boolean internal) {
        prefs.edit().putBoolean(pk(KEY_BUDGET_INTERNAL), internal).apply();
    }

    /**
     * Speichert die Einstellungen. Ein leeres {@code password} lässt das vorhandene unverändert,
     * ein nicht-leeres ersetzt es (verschlüsselt).
     */
    public void save(String url, String user, String password, String folder, String importFolder,
                     String defaultAccount, String exportMode, String kmyPath, String serverType) {
        prefs.edit()
                .putString(pk(KEY_URL), url == null ? "" : url.trim())
                .putString(pk(KEY_USER), user == null ? "" : user.trim())
                .putString(pk(KEY_FOLDER), folder == null ? "" : folder.trim())
                .putString(pk(KEY_IMPORT_FOLDER), importFolder == null ? "" : importFolder.trim())
                .putString(pk(KEY_DEFAULT_ACCOUNT), defaultAccount == null ? "" : defaultAccount.trim())
                .putString(pk(KEY_EXPORT_MODE), MODE_KMY.equals(exportMode) ? MODE_KMY : MODE_CSV)
                .putString(pk(KEY_KMY_PATH), kmyPath == null ? "" : kmyPath.trim())
                .putString(pk(KEY_SERVER_TYPE), normalizeServerType(serverType))
                .apply();
        if (password != null && !password.isEmpty()) {
            secret.edit().putString(pk(KEY_PASSWORD), password).apply();
        }
    }

    private static String normalizeServerType(String serverType) {
        if (SERVER_WEBDAV.equals(serverType)) {
            return SERVER_WEBDAV;
        }
        if (SERVER_SMB.equals(serverType)) {
            return SERVER_SMB;
        }
        return SERVER_NEXTCLOUD;
    }
}
