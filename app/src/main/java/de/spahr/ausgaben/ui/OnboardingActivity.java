package de.spahr.ausgaben.ui;

import de.spahr.ausgaben.net.RemotePath;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;


import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.Language;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.export.CsvImporter;
import de.spahr.ausgaben.export.KmyDocument;
import de.spahr.ausgaben.export.KmyImporter;
import de.spahr.ausgaben.net.RemoteStorage;
import de.spahr.ausgaben.settings.SettingsStore;

/**
 * On-Boarding beim ersten Start (noch keine Konten): setzt die Kernpunkte (Sprache, Sync-Verbindung,
 * Import/Export-Format) und importiert direkt Konten – über <b>dieselben</b> Bausteine und denselben
 * Auswahldialog wie „Konto hinzufügen". Erscheint nur automatisch (siehe
 * {@code MainActivity.populateAccountDrawer}); es gibt bewusst keinen Menüaufruf.
 *
 * <p>Die bereits verifizierte Import-Logik in {@code MainActivity} bleibt unangetastet – dieses
 * On-Boarding importiert eigenständig, damit die kritische Bestandslogik nicht angefasst wird. Da beim
 * ersten Start noch keine Konten existieren, entfällt hier die Filterung schon vorhandener Konten.</p>
 */
public class OnboardingActivity extends LocalizedActivity implements SmbWizardController.Host, HostedDialog.Host {
    /**
     * Baut die Dialoge dieser Maske – beim ersten Mal und nach jeder Drehung erneut (siehe
     * {@link HostedDialog}). Die beiden Browser-Dialoge der Verbindungsfelder liegen im
     * {@link SyncFieldsController}; er baut sie selbst.
     */
    @Override
    public android.app.Dialog buildDialog(String key, Bundle args) {
        if (DLG_CSV_PICK.equals(key)) {
            return buildCsvPick(args);
        }
        android.app.Dialog vomWiederherstellen = backupRestore.buildDialog(key, args);
        if (vomWiederherstellen != null) {
            return vomWiederherstellen;
        }
        if (fullBackupRestoreFlow != null) {
            android.app.Dialog vomVollablauf = fullBackupRestoreFlow.buildDialog(key, args);
            if (vomVollablauf != null) {
                return vomVollablauf;
            }
        }
        return syncFields == null ? null : syncFields.buildDialog(key, args);
    }

    @Override
    public void onDialogCancelled(String key, Bundle args) {
        // Die Browser-Dialoge dürfen weggetippt werden; es folgt nichts daraus.
    }

    /** Schlüssel und Angaben des Datei-Browsers – siehe {@link HostedDialog}. */
    private static final String DLG_CSV_PICK = "dlg_csvPick";
    private static final String ARG_CSV_FOLDER = "a_csvFolder";
    private static final String ARG_CSV_FOLDERS = "a_csvFolders";
    private static final String ARG_CSV_FILES = "a_csvFiles";


    /**
     * Nur gesetzt, wenn dieser Assistent zum Anlegen eines <b>neuen, zusätzlichen</b> Profils gestartet
     * wurde (statt beim allerersten App-Start). Zusammen mit {@link #EXTRA_NEW_PROFILE_ID} bestimmt das,
     * auf welches Profil bei einem Abbruch zurückgewechselt wird.
     */
    public static final String EXTRA_PREVIOUS_PROFILE_ID = "previous_profile_id";
    /** Das gerade angelegte, zum Zeitpunkt des Assistenten bereits aktive, noch leere Profil. */
    public static final String EXTRA_NEW_PROFILE_ID = "new_profile_id";
    private String previousProfileId;
    private String newProfileId;
    /**
     * true, solange ein Konten-Import (KMY-Depot oder CSV) im Hintergrund noch läuft. Verlässt man die
     * Maske währenddessen (Fertig/Zurück), lief die Import-Datenbankoperation nach dem Schließen der
     * Activity weiter und konnte auf eine inzwischen geschlossene Verbindung treffen (Absturz
     * „connection pool has been closed") – deshalb blockiert das Verlassen, bis der Import fertig ist.
     */
    private boolean importRunning = false;

    private Repository repository;
    private SettingsStore settings;
    private de.spahr.ausgaben.settings.ProfileManager profiles;
    private View profileColorSwatch;
    private TextInputEditText editProfileName;

    private MaterialAutoCompleteTextView editLanguage;
    private MaterialAutoCompleteTextView editExportMode;
    private MaterialAutoCompleteTextView editServerType;
    private TextInputEditText editUrl;
    private TextInputEditText editUser;
    private TextInputEditText editPassword;
    private TextInputEditText editFolder;
    private TextInputEditText editImportFolder;
    private TextInputEditText editKmyPath;
    private MaterialAutoCompleteTextView editCsvSeparator;
    /** Aktuell gewähltes CSV-Trennzeichen (SettingsStore.CSV_SEP_*). */
    private String selectedCsvSeparator = SettingsStore.CSV_SEP_SEMICOLON;
    private static final String[] CSV_SEPARATOR_VALUES = {
            SettingsStore.CSV_SEP_SEMICOLON, SettingsStore.CSV_SEP_COMMA};
    private TextInputLayout urlLayout;
    private TextInputLayout userLayout;
    private TextInputLayout passwordLayout;
    /** Assistent für SMB; ersetzt bei diesem Server-Typ die Felder URL/Benutzer/Passwort. */
    private SmbWizardController smbWizard;
    private SyncFieldsController syncFields;
    /** Das gelbe Band während „Verbindung testen" – siehe {@link DiagnosticsBanner}. */
    private DiagnosticsBanner diagBanner;
    private LinearLayout importStatus;
    private View importProgress;
    private TextView importStatusText;

    private List<Language> languages = new ArrayList<>();
    private String selectedExportMode = SettingsStore.MODE_CSV;

    // ---- Standardkonto ----
    private MaterialAutoCompleteTextView editDefaultAccount;

    private ActivityResultLauncher<String[]> csvLauncher;
    private ActivityResultLauncher<String[]> restoreBackupLauncher;
    /** Der gemeinsame Ablauf zum Einspielen einer Sicherung – siehe {@link BackupRestoreController}. */
    private final BackupRestoreController backupRestore = new BackupRestoreController(this);
    private de.spahr.ausgaben.backup.FullBackupRestoreFlow fullBackupRestoreFlow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);
        repository = new Repository(this);
        settings = new SettingsStore(this);
        profiles = new de.spahr.ausgaben.settings.ProfileManager(this);

        previousProfileId = getIntent().getStringExtra(EXTRA_PREVIOUS_PROFILE_ID);
        newProfileId = getIntent().getStringExtra(EXTRA_NEW_PROFILE_ID);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> {
            if (!blockIfImporting()) {
                abortIfNewProfile();
            }
        });
        if (newProfileId != null) {
            toolbar.setTitle(getString(R.string.settings_profile_new));
        } else {
            // Allererstes, automatisch gestartetes Onboarding – kein Profil, zu dem „Zurück" führen
            // könnte. Titel bleibt „Willkommen" aus dem Layout.
            toolbar.setNavigationIcon(null);
        }

        fullBackupRestoreFlow = new de.spahr.ausgaben.backup.FullBackupRestoreFlow(this);
        ((MaterialButton) findViewById(R.id.btnRestoreFullBackup))
                .setOnClickListener(v -> fullBackupRestoreFlow.start());

        setupProfileColor();
        setupCopyFromProfile();

        editLanguage = findViewById(R.id.editLanguage);
        editExportMode = findViewById(R.id.editExportMode);
        editServerType = findViewById(R.id.editServerType);
        editUrl = findViewById(R.id.editUrl);
        editUser = findViewById(R.id.editUser);
        editPassword = findViewById(R.id.editPassword);
        editFolder = findViewById(R.id.editFolder);
        editImportFolder = findViewById(R.id.editImportFolder);
        editKmyPath = findViewById(R.id.editKmyPath);
        editCsvSeparator = findViewById(R.id.editCsvSeparator);
        urlLayout = findViewById(R.id.urlLayout);
        userLayout = findViewById(R.id.userLayout);
        passwordLayout = findViewById(R.id.passwordLayout);
        importStatus = findViewById(R.id.importStatus);
        importProgress = findViewById(R.id.importProgress);
        importStatusText = findViewById(R.id.importStatusText);

        smbWizard = new SmbWizardController(this, findViewById(R.id.smbWizard), settings, this);
        // Serverart, Verbindungsprobe und Ordner-Browser – der gemeinsame Block beider
        // Einrichtungsmasken, siehe {@link SyncFieldsController}.
        syncFields = new SyncFieldsController(this, settings, smbWizard);
        diagBanner = new DiagnosticsBanner(this);

        setupLanguages();
        setupExportMode();
        setupCsvSeparator();
        syncFields.setupServerType();
        prefillSyncFields();

        editDefaultAccount = findViewById(R.id.editDefaultAccount);
        editDefaultAccount.setText(settings.getDefaultAccount(), false);
        repository.getAccountNames(names -> PickerAdapters.accounts(repository, editDefaultAccount, names));

        csvLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri != null) {
                        importCsvLocal(uri);
                    }
                });
        restoreBackupLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        backupRestore.restore(uri);
                    }
                });

        ((MaterialButton) findViewById(R.id.btnTestConnection))
                .setOnClickListener(v -> runDiagnostics());
        findViewById(R.id.btnSmbSearch).setOnClickListener(v -> {
            smbWizard.restart();
            syncFields.applyServerTypeHints();
        });
        ((MaterialButton) findViewById(R.id.btnBrowseKmy))
                .setOnClickListener(v -> syncFields.browseKmy());
        ((MaterialButton) findViewById(R.id.btnBrowseFolder))
                .setOnClickListener(v -> syncFields.browseFolderInto(editFolder));
        ((MaterialButton) findViewById(R.id.btnBrowseImportFolder))
                .setOnClickListener(v -> syncFields.browseFolderInto(editImportFolder));
        ((MaterialButton) findViewById(R.id.btnImportAccounts))
                .setOnClickListener(v -> importAccounts());
        ((MaterialButton) findViewById(R.id.btnDone)).setOnClickListener(v -> {
            if (blockIfImporting()) {
                return;
            }
            saveSettings();
            finishFromProfileMask();
        });

        ((MaterialButton) findViewById(R.id.btnRestoreProfile)).setOnClickListener(v -> confirmRestore());
    }

    @Override
    public void onBackPressed() {
        if (blockIfImporting()) {
            return;
        }
        abortIfNewProfile();
    }

    /**
     * Ein Konten-Import läuft im Hintergrund auf einer eigenen Datenbankverbindung weiter, auch wenn
     * diese Maske schließt. {@link #abortIfNewProfile()} wechselt dabei ggf. das aktive Profil zurück
     * und schließt die Datenbank ({@link de.spahr.ausgaben.settings.ProfileManager#switchTo}) – trifft
     * das auf einen noch laufenden Import, stürzt die App ab („connection pool has been closed").
     * Deshalb: solange {@link #importRunning}, weder „Fertig" noch Zurück zulassen.
     */
    private boolean blockIfImporting() {
        if (importRunning) {
            Toast.makeText(this, R.string.onboarding_import_running, Toast.LENGTH_LONG).show();
            return true;
        }
        return false;
    }

    /**
     * Bricht der Nutzer die Einrichtung eines <b>neuen</b> Profils ab (Zurück-Pfeil/Systemtaste), wird
     * automatisch auf das vorherige Profil zurückgewechselt und das leere neue Profil wieder entfernt –
     * kein „Geister-Profil" ohne Inhalt. Beim allerersten App-Start (kein {@link #newProfileId}) bleibt es
     * beim einfachen {@link #finish()}.
     */
    private void abortIfNewProfile() {
        if (newProfileId != null && previousProfileId != null) {
            de.spahr.ausgaben.settings.ProfileManager pm = new de.spahr.ausgaben.settings.ProfileManager(this);
            pm.switchTo(this, previousProfileId);
            pm.deleteProfile(this, newProfileId);
        }
        finishFromProfileMask();
    }

    /**
     * Schließt diese Maske. Wurde sie über „Neues Profil anlegen" geöffnet (statt beim allerersten,
     * automatischen Onboarding), setzt sie den Activity-Stack auf eine frische {@code MainActivity}
     * zurück – wie schon {@link ProfileSwitchDialog} es beim Wechseln/Löschen tut. Nötig, weil ein
     * Profilwechsel unterwegs die Datenbankverbindung schließt
     * ({@link de.spahr.ausgaben.settings.ProfileManager#switchTo}): ein einfaches {@link #finish()}
     * kehrte sonst zu einer darunterliegenden {@code SettingsActivity}/{@code MainActivity} zurück,
     * deren {@code Repository} noch die alte, jetzt geschlossene Verbindung hält – das stürzte beim
     * nächsten Datenbankzugriff dort ab („connection pool has been closed").
     */
    private void finishFromProfileMask() {
        if (newProfileId != null) {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
        }
        finish();
    }

    // ---- Dropdowns (gleiches Muster wie SettingsActivity) ----

    private void setupLanguages() {
        repository.getLanguages(list -> {
            languages = list;
            String[] names = new String[list.size()];
            String current = settings.getLanguage();
            String currentName = "";
            for (int i = 0; i < list.size(); i++) {
                names[i] = list.get(i).name;
                if (list.get(i).code.equals(current)) {
                    currentName = list.get(i).name;
                }
            }
            PickerAdapters.plain(editLanguage, java.util.Arrays.asList(names));
            if (!currentName.isEmpty()) {
                editLanguage.setText(currentName, false);
            }
            editLanguage.setOnItemClickListener((parent, view, position, id) ->
                    onLanguageChosen(languages.get(position).code));
        });
    }

    private void onLanguageChosen(String code) {
        if (code.equals(settings.getLanguage())) {
            return;
        }
        settings.setLanguage(code);
        de.spahr.ausgaben.i18n.LocaleManager.reload(this);
        de.spahr.ausgaben.wear.LanguageSync.publish(this);
        AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.forLanguageTags(code));
    }

    private void setupExportMode() {
        String csvLabel = getString(R.string.export_mode_csv);
        String kmyLabel = getString(R.string.export_mode_kmy);
        PickerAdapters.plain(editExportMode, java.util.Arrays.asList(csvLabel, kmyLabel));
        selectedExportMode = settings.getExportMode();
        editExportMode.setText(
                SettingsStore.MODE_KMY.equals(selectedExportMode) ? kmyLabel : csvLabel, false);
        applyExportModeVisibility();
        editExportMode.setOnItemClickListener((parent, view, position, id) -> {
            selectedExportMode = position == 1 ? SettingsStore.MODE_KMY : SettingsStore.MODE_CSV;
            settings.setExportMode(selectedExportMode);
            applyExportModeVisibility();
        });
    }

    private void applyExportModeVisibility() {
        boolean kmy = SettingsStore.MODE_KMY.equals(selectedExportMode);
        findViewById(R.id.csvOptions).setVisibility(kmy ? View.GONE : View.VISIBLE);
        findViewById(R.id.kmyOptions).setVisibility(kmy ? View.VISIBLE : View.GONE);
    }

    /** Dropdown „CSV-Trennzeichen" (nur im CSV-Block sichtbar): Semikolon (Standard) oder Komma. */
    private void setupCsvSeparator() {
        String[] labels = {
                getString(R.string.csv_separator_semicolon),
                getString(R.string.csv_separator_comma)};
        PickerAdapters.plain(editCsvSeparator, java.util.Arrays.asList(labels));
        selectedCsvSeparator = settings.getCsvSeparator();
        int idx = SettingsStore.CSV_SEP_COMMA.equals(selectedCsvSeparator) ? 1 : 0;
        editCsvSeparator.setText(labels[idx], false);
        editCsvSeparator.setOnItemClickListener((parent, view, position, id) ->
                selectedCsvSeparator = CSV_SEPARATOR_VALUES[position]);
    }

    /** Akzentfarbe des Profils – erstes Feld der Maske. Wirkt sofort (wie die Kategoriefarben). */
    private void setupProfileColor() {
        editProfileName = findViewById(R.id.editProfileName);
        de.spahr.ausgaben.settings.ProfileManager.Profile active = profiles.getActiveProfile();
        editProfileName.setText(active != null ? active.name : "");

        profileColorSwatch = findViewById(R.id.profileColorSwatch);
        refreshProfileColorSwatch();
        View row = findViewById(R.id.profileColorRow);
        View.OnClickListener openPicker = v -> ColorPickerDialog.show(this, R.string.profile_change_color,
                de.spahr.ausgaben.settings.ProfileManager.ACCENT_PALETTE,
                color -> {
                    profiles.setAccentColor(profiles.getActiveProfileId(), color);
                    refreshProfileColorSwatch();
                    de.spahr.ausgaben.settings.AccentColor.apply(this);
                });
        profileColorSwatch.setOnClickListener(openPicker);
        row.setOnClickListener(openPicker);
    }

    private void refreshProfileColorSwatch() {
        de.spahr.ausgaben.settings.ProfileManager.Profile active = profiles.getActiveProfile();
        int color = active != null ? active.accentColor
                : de.spahr.ausgaben.settings.ProfileManager.DEFAULT_ACCENT_COLOR;
        profileColorSwatch.setBackground(ColorPickerDialog.swatchDrawable(color));
    }

    /**
     * Beim Anlegen eines <b>weiteren</b> Profils (nicht beim allerersten) lässt sich die ganze
     * Konfiguration eines bestehenden Profils übernehmen, statt sie neu einzutippen. Nur sichtbar,
     * solange es mindestens ein anderes Profil gibt.
     */
    private void setupCopyFromProfile() {
        View button = findViewById(R.id.btnCopyFromProfile);
        List<de.spahr.ausgaben.settings.ProfileManager.Profile> others = new ArrayList<>();
        String ownId = profiles.getActiveProfileId();
        if (newProfileId != null) {
            for (de.spahr.ausgaben.settings.ProfileManager.Profile p : profiles.getProfiles()) {
                if (!p.id.equals(ownId)) {
                    others.add(p);
                }
            }
        }
        if (others.isEmpty()) {
            button.setVisibility(View.GONE);
            return;
        }
        button.setVisibility(View.VISIBLE);
        String[] labels = new String[others.size()];
        for (int i = 0; i < others.size(); i++) {
            labels[i] = others.get(i).name;
        }
        button.setOnClickListener(v -> new AppDialog(this)
                .setTitle(R.string.profile_copy_title)
                .setItems(labels, (d, w) -> {
                    profiles.copySettingsFrom(this, others.get(w).id, ownId);
                    prefillAll();
                })
                .show());
    }

    /** Zieht alle Feldwerte frisch aus den (ggf. gerade übernommenen) Einstellungen nach. */
    private void prefillAll() {
        refreshProfileColorSwatch();
        setupExportMode();
        setupCsvSeparator();
        syncFields.setupServerType();
        prefillSyncFields();
        editDefaultAccount.setText(settings.getDefaultAccount(), false);
    }

    @Override
    protected void onDestroy() {
        smbWizard.stopDiscovery();
        super.onDestroy();
    }

    @Override
    public void onSmbConfigured(String url, String user, String password) {
        editUrl.setText(url);
        editUser.setText(user);
        if (!password.isEmpty()) {
            editPassword.setText(password);
        }
        saveSettings();
    }

    @Override
    public void onSmbManualRequested() {
        syncFields.applyServerTypeHints();
    }

    private void prefillSyncFields() {
        editUrl.setText(settings.getUrl());
        editUser.setText(settings.getUser());
        editFolder.setText(settings.getFolder());
        editImportFolder.setText(settings.getImportFolder());
        editKmyPath.setText(settings.getKmyPath());
        // Passwort bleibt leer: leer speichern lässt ein vorhandenes unverändert. Ist bereits eines
        // gespeichert, unter dem Feld „••••••" anzeigen (wie in den Einstellungen).
        if (settings.hasPassword()) {
            passwordLayout.setHelperText(getString(R.string.password_saved_hint));
        }
        // Ein defekter Schlüsselspeicher lässt die App weiterlaufen, legt das Server-Passwort dann aber
        // unverschlüsselt ab. Das gehört gesagt – und zwar dort, wo man es eingibt.
        if (SettingsStore.isSecretStorageUnencrypted(this)) {
            passwordLayout.setError(getString(R.string.settings_secret_fallback));
            passwordLayout.setErrorIconDrawable(null);
        }
    }

    // ---- Speichern ----

    private void saveSettings() {
        profiles.renameProfile(profiles.getActiveProfileId(), textOf(editProfileName));

        String defaultAccount = editDefaultAccount.getText() == null
                ? "" : editDefaultAccount.getText().toString().trim();
        settings.save(
                textOf(editUrl),
                textOf(editUser),
                textOf(editPassword),
                textOf(editFolder),
                textOf(editImportFolder),
                defaultAccount,
                selectedExportMode,
                textOf(editKmyPath),
                syncFields.serverType());
        settings.setCsvSeparator(selectedCsvSeparator);

        repository.ensureAccount(defaultAccount);
        // Währung/Zahlenformat/Währungsanzeige/Dividenden-Modus sind hier (bewusst schlanker
        // Assistent) keine eigenen Felder – sie kommen aus der gewählten Sprache (siehe „Profil
        // ändern" für die volle Einstellungsmöglichkeit).
        Language lang = currentLanguage();
        settings.setCurrency(lang != null ? lang.defaultCurrency : "€");
        settings.setNumberFormat(lang != null ? lang.numberFormat : SettingsStore.NUMBER_FORMAT_PLAIN_COMMA);
        settings.setCurrencyShown(true);
        settings.setDividendGross(false);
        de.spahr.ausgaben.settings.Currencies.refresh(this);
        de.spahr.ausgaben.settings.MoneyFormat.refresh(this);
        // Das Standardkonto bestimmt den Saldo, den die Uhr anzeigt – sonst zeigte sie bis zum
        // nächsten Sync noch das alte (full: echte Sync, foss: No-op-Stub).
        de.spahr.ausgaben.wear.BalanceSync.publish(this);
    }

    /** Die gerade gewählte Sprache aus der (bereits geladenen) Sprachliste, sonst {@code null}. */
    private Language currentLanguage() {
        String code = settings.getLanguage();
        for (Language l : languages) {
            if (l.code.equals(code)) {
                return l;
            }
        }
        return null;
    }

    /**
     * Legt ein neues, leeres Profil an, macht es zum aktiven und öffnet diese Maske dafür – der
     * Nutzer kommt aus {@code SettingsActivity} ("Neues Profil anlegen") oder aus
     * {@link ProfileSwitchDialog} ("+"). Bricht der Nutzer ab, wechselt {@link #abortIfNewProfile()}
     * zurück und entfernt das leere Profil wieder.
     */
    public static void startForNewProfile(android.app.Activity activity) {
        de.spahr.ausgaben.settings.ProfileManager pm =
                new de.spahr.ausgaben.settings.ProfileManager(activity);
        String previousProfileId = pm.getActiveProfileId();
        de.spahr.ausgaben.settings.ProfileManager.Profile newProfile = pm.createProfile(
                activity.getString(R.string.profile_default_name, pm.getProfiles().size() + 1));
        pm.switchTo(activity, newProfile.id);
        Intent intent = new Intent(activity, OnboardingActivity.class);
        intent.putExtra(EXTRA_PREVIOUS_PROFILE_ID, previousProfileId);
        intent.putExtra(EXTRA_NEW_PROFILE_ID, newProfile.id);
        activity.startActivity(intent);
    }

    // ---- Verbindung testen / .kmy auswählen (gleiches Verhalten wie in den Einstellungen) ----

    /**
     * Diagnose: läuft die ganze Kette durch und zeigt je Schritt Ergebnis und rohen Statuscode – beim
     * Erststart die schnellste Antwort auf „warum geht es nicht?". Welche Kette, hängt an der
     * Serverart.
     */
    private void runDiagnostics() {
        String pw = textOf(editPassword);
        // Geprüft wird der Ordner, in den die App wirklich schreibt: im .kmy-Modus der Ordner der
        // Datei (samt Datei), im CSV-Modus der Export-Ordner.
        boolean kmy = SettingsStore.MODE_KMY.equals(selectedExportMode);
        String path = kmy ? textOf(editKmyPath) : textOf(editFolder);
        String folder = kmy ? RemotePath.folderOf(path) : path;
        String file = kmy ? RemotePath.fileOf(path) : "";
        String password = pw.isEmpty() ? settings.getPassword() : pw;
        String type = syncFields.serverType();
        if (SettingsStore.SERVER_SMB.equals(type)) {
            DiagnosticsDialog.runSmb(this, diagBanner, textOf(editUrl), textOf(editUser), password, folder, file);
        } else {
            DiagnosticsDialog.runWebDav(this, diagBanner, textOf(editUrl), textOf(editUser), password,
                    !SettingsStore.SERVER_WEBDAV.equals(type), folder, file);
        }
    }

    // ---- Konten importieren (gleicher Ablauf wie MainActivity.onAddAccountClicked) ----

    private void importAccounts() {
        saveSettings();
        if (!settings.isKmyMode()) {
            startCsvImport();
            return;
        }
        if (!settings.hasRemoteConfig()) {
            Toast.makeText(this, R.string.export_no_config, Toast.LENGTH_LONG).show();
            return;
        }
        String path = settings.getKmyPath();
        if (path.isEmpty()) {
            Toast.makeText(this, R.string.kmy_path_missing, Toast.LENGTH_LONG).show();
            return;
        }
        showImportStatus(getString(R.string.progress_download));
        new Thread(() -> {
            try {
                byte[] raw = RemoteStorage.from(settings).downloadBytes(RemotePath.folderOf(path), RemotePath.fileOf(path));
                KmyImporter importer = new KmyImporter(
                        new KmyDocument(raw, getApplicationContext()), getApplicationContext());
                runOnUiThread(() -> {
                    hideImportStatus();
                    List<String> accounts = importer.accountNames();
                    List<String> depots = importer.depotNames();
                    if (accounts.isEmpty() && depots.isEmpty()) {
                        Toast.makeText(this, R.string.kmy_no_files, Toast.LENGTH_LONG).show();
                    } else {
                        chooseAccountForImport(importer, accounts, depots);
                    }
                });
            } catch (Exception e) {
                postImportError(e);
            }
        }).start();
    }

    /** Derselbe Mehrfachauswahl-Dialog wie in MainActivity (ohne Filterung – DB ist beim Start leer). */
    private void chooseAccountForImport(KmyImporter importer, List<String> accounts, List<String> depots) {
        final List<String> accountList = new ArrayList<>(accounts);
        final List<String> depotList = new ArrayList<>(depots);
        List<String> labels = new ArrayList<>(accountList);
        for (String d : depotList) {
            labels.add(getString(R.string.kmy_choose_depot, d));
        }
        final int accountCount = accountList.size();
        final boolean[] checked = new boolean[labels.size()];
        String[] items = labels.toArray(new String[0]);
        new AppDialog(this)
                .setTitle(R.string.kmy_choose_account)
                .setMultiChoiceItems(items, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.kmy_import_selected, (d, w) -> {
                    List<String> accountTargets = new ArrayList<>();
                    List<String> depotTargets = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) {
                        if (!checked[i]) {
                            continue;
                        }
                        if (i < accountCount) {
                            accountTargets.add(accountList.get(i));
                        } else {
                            depotTargets.add(depotList.get(i - accountCount));
                        }
                    }
                    if (accountTargets.isEmpty() && depotTargets.isEmpty()) {
                        return;
                    }
                    startBatchImport(importer, accountTargets, depotTargets);
                })
                .show();
    }

    private void startBatchImport(KmyImporter importer, List<String> accountTargets,
                                  List<String> depotTargets) {
        showImportStatus(getString(R.string.onboarding_importing));
        final int importedCount = accountTargets.size() + depotTargets.size();
        new Thread(() -> {
            try {
                if (accountTargets.isEmpty()) {
                    runOnUiThread(() -> importDepotsThenFinish(importer, depotTargets, importedCount));
                    return;
                }
                java.util.LinkedHashMap<String, List<Booking>> map =
                        importer.bookingsForAccounts(accountTargets, null);
                for (String acc : accountTargets) {
                    repository.setAccountCurrency(acc, importer.currencyOf(acc));
                }
                repository.applyAccountTypes(importer.accountTypes());
                repository.applyCategoryTypes(importer.categoryTypes());
                runOnUiThread(() -> repository.replaceImportAccounts(map, null,
                        res -> importDepotsThenFinish(importer, depotTargets, importedCount)));
            } catch (Exception e) {
                postImportError(e);
            }
        }).start();
    }

    private void importDepotsThenFinish(KmyImporter importer, List<String> depots, int importedCount) {
        if (depots.isEmpty()) {
            finishImport(importedCount);
            return;
        }
        final String depot = depots.get(0);
        final List<String> rest = new ArrayList<>(depots.subList(1, depots.size()));
        new Thread(() -> {
            try {
                KmyImporter.DepotData data = importer.importDepot(depot);
                repository.replaceDepotImport(depot, data.securities, data.transactions, data.prices, () ->
                        importDepotsThenFinish(importer, rest, importedCount));
            } catch (Exception e) {
                postImportError(e);
            }
        }).start();
    }

    private void finishImport(int importedCount) {
        importRunning = false;
        importProgress.setVisibility(View.GONE);
        importStatus.setVisibility(View.VISIBLE);
        importStatusText.setText(getString(R.string.onboarding_import_done, importedCount));
        refreshAccountDependentFields();
    }

    /**
     * Nach einem Kontenimport ist das Dropdown für das Standardkonto noch auf dem alten (meist leeren)
     * Stand – ohne Auffrischen stünden die gerade importierten Konten dort nicht zur Auswahl.
     */
    private void refreshAccountDependentFields() {
        repository.getAccountNames(names -> PickerAdapters.accounts(repository, editDefaultAccount, names));
    }

    // ---- CSV-Import (lokaler Picker; bei Remote-Konfig Ordner durchsuchen) ----

    private void startCsvImport() {
        if (settings.hasRemoteConfig()) {
            browseCsvAt(settings.getImportFolder());
        } else {
            csvLauncher.launch(new String[]{
                    "text/*", "text/csv", "text/comma-separated-values", "application/octet-stream"});
        }
    }

    private void browseCsvAt(String folder) {
        Toast.makeText(this, R.string.loading_files, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                RemoteStorage storage = RemoteStorage.from(settings);
                List<String> folders = storage.listFolders(folder);
                List<String> files = storage.listFiles(folder, "csv");
                java.util.Collections.sort(folders, String.CASE_INSENSITIVE_ORDER);
                java.util.Collections.sort(files, String.CASE_INSENSITIVE_ORDER);
                runOnUiThread(() -> {
                    if (folder.isEmpty() && folders.isEmpty() && files.isEmpty()) {
                        Toast.makeText(this, R.string.no_files, Toast.LENGTH_LONG).show();
                    } else {
                        showCsvPick(folder, folders, files);
                    }
                });
            } catch (Exception e) {
                final String msg = syncFields.serverError(e);
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.import_failed, msg), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void showCsvPick(String folder, List<String> folders, List<String> files) {
        Bundle args = new Bundle();
        args.putString(ARG_CSV_FOLDER, folder);
        args.putStringArray(ARG_CSV_FOLDERS, folders.toArray(new String[0]));
        args.putStringArray(ARG_CSV_FILES, files.toArray(new String[0]));
        HostedDialog.show(this, DLG_CSV_PICK, args);
    }

    /**
     * Baut den Datei-Browser aus dem, was im Bundle steht — beim ersten Mal und nach jeder Drehung.
     * Der Serverzugriff bleibt dabei aus: Die Liste dieses Ordners steht schon in den Angaben.
     */
    private android.app.Dialog buildCsvPick(Bundle args) {
        final String folder = args.getString(ARG_CSV_FOLDER, "");
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        if (!folder.isEmpty()) {
            labels.add("↑  ..");
            actions.add(() -> browseCsvAt(RemotePath.parentFolder(folder)));
        }
        for (String d : args.getStringArray(ARG_CSV_FOLDERS)) {
            labels.add("📁  " + d);
            final String target = folder.isEmpty() ? d : folder + "/" + d;
            actions.add(() -> browseCsvAt(target));
        }
        for (String f : args.getStringArray(ARG_CSV_FILES)) {
            labels.add(f);
            actions.add(() -> downloadAndImportCsv(folder, f));
        }
        String title = folder.isEmpty() ? getString(R.string.choose_import_file) : "/" + folder;
        return new AppDialog(this)
                .setTitle(title)
                .setItems(labels.toArray(new String[0]), (d, w) -> actions.get(w).run())
                .create();
    }

    private void downloadAndImportCsv(String folder, String fileName) {
        showImportStatus(getString(R.string.onboarding_importing));
        new Thread(() -> {
            try {
                String content = RemoteStorage.from(settings).downloadText(folder, fileName);
                processCsv(content);
            } catch (Exception e) {
                postImportError(e);
            }
        }).start();
    }

    private void importCsvLocal(Uri uri) {
        showImportStatus(getString(R.string.onboarding_importing));
        new Thread(() -> {
            try {
                processCsv(readText(uri));
            } catch (Exception e) {
                postImportError(e);
            }
        }).start();
    }

    /** Parst den CSV-Inhalt und ersetzt die Buchungen des Kontos (Aufruf aus Hintergrund-Thread). */
    private void processCsv(String content) {
        try {
            CsvImporter importer = new CsvImporter(this);
            List<Booking> bookings = importer.parse(content);
            String account = importer.getParsedAccount();
            runOnUiThread(() -> repository.replaceImport(account, bookings, count -> finishImport(1)));
        } catch (Exception e) {
            postImportError(e);
        }
    }

    // ---- Hilfen ----

    private void showImportStatus(String text) {
        importRunning = true;
        importStatus.setVisibility(View.VISIBLE);
        importProgress.setVisibility(View.VISIBLE);
        importStatusText.setText(text);
    }

    private void hideImportStatus() {
        importRunning = false;
        importStatus.setVisibility(View.GONE);
    }

    private void postImportError(Exception e) {
        final String msg = syncFields.serverError(e);
        runOnUiThread(() -> {
            hideImportStatus();
            Toast.makeText(this, getString(R.string.import_failed, msg), Toast.LENGTH_LONG).show();
        });
    }

    private String readText(Uri uri) throws Exception {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (is != null && (n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private String textOf(TextInputEditText field) {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }

    // ---- Sicherung/Wiederherstellen (nur das aktive Profil) ----

    private void confirmRestore() {
        restoreBackupLauncher.launch(new String[]{"*/*"});
    }


}
