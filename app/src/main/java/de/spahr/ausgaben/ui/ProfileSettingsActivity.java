package de.spahr.ausgaben.ui;

import de.spahr.ausgaben.net.RemotePath;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.backup.BackupStore;
import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.Language;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.export.CsvImporter;
import de.spahr.ausgaben.export.KmyDocument;
import de.spahr.ausgaben.export.KmyImporter;
import de.spahr.ausgaben.net.RemoteStorage;
import de.spahr.ausgaben.settings.SettingsStore;

/**
 * „Profil ändern": die volle Einstellungsmöglichkeit für ein <b>bestehendes</b> Profil (Sprache,
 * Sync-Verbindung, Import/Export-Format, Währung/Zahlenformat, Dividenden, Budget, Orte, Alias,
 * Sicherung/Wiederherstellung, Konto löschen/schließen, Profil zurücksetzen/löschen). Eigenständig von
 * {@link OnboardingActivity} (Ersteinrichtung/neues Profil, dort bewusst nur das Nötigste) getrennt,
 * damit eine Änderung an der einen Maske nicht versehentlich die andere mitverändert. Aufrufer:
 * {@code SettingsActivity} („Profil ändern") und der lange Druck auf eine Zeile in
 * {@link ProfileSwitchDialog}.
 */
public class ProfileSettingsActivity extends LocalizedActivity implements SmbWizardController.Host, HostedDialog.Host {
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
        if (DLG_BACKUP_OPTIONS.equals(key)) {
            return buildBackupOptions();
        }
        android.app.Dialog vomWiederherstellen = backupRestore.buildDialog(key, args);
        if (vomWiederherstellen != null) {
            return vomWiederherstellen;
        }
        return syncFields == null ? null : syncFields.buildDialog(key, args);
    }

    @Override
    public void onDialogCancelled(String key, Bundle args) {
        // Die Browser-Dialoge dürfen weggetippt werden; es folgt nichts daraus.
    }

    /** Schlüssel und Angaben der Dialoge dieser Maske – siehe {@link HostedDialog}. */
    private static final String DLG_CSV_PICK = "dlg_csvPick";
    private static final String DLG_BACKUP_OPTIONS = "dlg_backupOptions";
    private static final String ARG_CSV_FOLDER = "a_csvFolder";
    private static final String ARG_CSV_FOLDERS = "a_csvFolders";
    private static final String ARG_CSV_FILES = "a_csvFiles";


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
    private LinearLayout importStatus;
    private View importProgress;
    private TextView importStatusText;

    private List<Language> languages = new ArrayList<>();
    private String selectedExportMode = SettingsStore.MODE_CSV;

    // ---- Weitere Profil-Einstellungen (Währung, Dividenden, Budget, Standardkonto, Orte, Alias) ----
    private MaterialAutoCompleteTextView editDefaultAccount;
    private TextInputEditText editCurrency;
    private MaterialAutoCompleteTextView editNumberFormat;
    /** Aktuell gewählter Zahlenformat-Wert (SettingsStore.NUMBER_FORMAT_*). */
    private String selectedNumberFormat = SettingsStore.NUMBER_FORMAT_PLAIN_COMMA;
    private static final String[] NUMBER_FORMAT_VALUES = {
            SettingsStore.NUMBER_FORMAT_DE_GROUP, SettingsStore.NUMBER_FORMAT_EN_GROUP,
            SettingsStore.NUMBER_FORMAT_PLAIN_COMMA, SettingsStore.NUMBER_FORMAT_PLAIN_DOT};
    private com.google.android.material.materialswitch.MaterialSwitch switchShowCurrency;
    private com.google.android.material.materialswitch.MaterialSwitch switchDividendGross;
    private TextInputEditText editDividendTaxRate;
    private com.google.android.material.textfield.TextInputLayout dividendTaxLayout;
    private com.google.android.material.materialswitch.MaterialSwitch switchBudgetInternal;
    private com.google.android.material.materialswitch.MaterialSwitch switchAliasPrompt;

    private de.spahr.ausgaben.settings.PlacesStore placesStore;
    private LinearLayout placesContainer;
    private MaterialAutoCompleteTextView editDefaultPlace;
    private MaterialAutoCompleteTextView editPlacesAccount;
    /** Konto, dessen Orte gerade in der Profil-Maske verwaltet werden. */
    private String placesAccount = "";

    private ActivityResultLauncher<String[]> csvLauncher;
    private ActivityResultLauncher<String> backupLauncher;
    private ActivityResultLauncher<String[]> restoreBackupLauncher;
    /** Der gemeinsame Ablauf zum Einspielen einer Sicherung – siehe {@link BackupRestoreController}. */
    private final BackupRestoreController backupRestore = new BackupRestoreController(this);
    /** Antworten aus dem Sichern-Dialog – gelten bis der Dateiname gewählt ist. */
    private boolean backupIncludeServerPassword;
    private String backupPassword = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile_settings);
        repository = new Repository(this);
        settings = new SettingsStore(this);
        profiles = new de.spahr.ausgaben.settings.ProfileManager(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> {
            if (!blockIfImporting()) {
                finishToMainActivity();
            }
        });
        toolbar.setTitle(getString(R.string.settings_profile_change));

        // Nicht onBackPressed überschreiben: Das ist seit API 33 überholt, und mit
        // android:enableOnBackInvokedCallback würde der Import-Schutz still übersprungen — die Maske
        // schlösse mitten in einem laufenden Konten-Import, was die App zum Absturz bringt.
        getOnBackPressedDispatcher().addCallback(this,
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (!blockIfImporting()) {
                            finishToMainActivity();
                        }
                    }
                });

        setupProfileColor();
        setupDeleteProfile();
        setupSyncFields();
        setupProfileFields();
        setupBudgetAndAliases();
        setupPlaces();
        setupLaunchers();
        setupButtons();
    }

    /** Datenquelle: Serverart, Zugangsdaten, Ordner und der SMB-Assistent. */
    private void setupSyncFields() {
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

        setupLanguages();
        setupExportMode();
        setupCsvSeparator();
        syncFields.setupServerType();
        prefillSyncFields();
    }

    /** Standardkonto, Währung, Zahlenformat und die Dividenden-Einstellungen. */
    private void setupProfileFields() {
        placesStore = new de.spahr.ausgaben.settings.PlacesStore(this);
        editDefaultAccount = findViewById(R.id.editDefaultAccount);
        editDefaultAccount.setText(settings.getDefaultAccount(), false);
        repository.getAccountNames(names -> PickerAdapters.accounts(repository, editDefaultAccount, names));

        editCurrency = findViewById(R.id.editCurrency);
        editCurrency.setText(settings.getCurrency());
        editNumberFormat = findViewById(R.id.editNumberFormat);
        setupNumberFormat();

        switchShowCurrency = findViewById(R.id.switchShowCurrency);
        switchShowCurrency.setChecked(settings.isCurrencyShown());
        switchDividendGross = findViewById(R.id.switchDividendGross);
        switchDividendGross.setChecked(settings.isDividendGross());
        editDividendTaxRate = findViewById(R.id.editDividendTaxRate);
        dividendTaxLayout = findViewById(R.id.dividendTaxLayout);
        // Ziffern und das oben eingestellte Dezimalzeichen – android:inputType="numberDecimal" kennt
        // nur den Punkt und verschluckte ein Komma (siehe AmountField).
        AmountField.preparePercent(editDividendTaxRate);
        editDividendTaxRate.addTextChangedListener(
                new SimpleWatcher(() -> dividendTaxLayout.setError(null)));
        double taxPercent = settings.getDividendTaxPercent();
        if (taxPercent > 0) {
            editDividendTaxRate.setText(de.spahr.ausgaben.settings.MoneyFormat.decimal(taxPercent, 0, 5));
        }
    }

    /** Budget-Schalter samt seinen beiden Knöpfen und die Alias-Einstellung. */
    private void setupBudgetAndAliases() {
        switchBudgetInternal = findViewById(R.id.switchBudgetInternal);
        switchBudgetInternal.setChecked(settings.isBudgetInternal());
        ((MaterialButton) findViewById(R.id.btnBudgetCompute)).setOnClickListener(v -> {
            int y = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
            AppDialog.destructive(this)
                    .setTitle(R.string.budget_compute_confirm_title)
                    .setMessage(getString(R.string.budget_compute_confirm_message, y))
                    .setPositiveButton(R.string.budget_compute, (d, w) ->
                            repository.computeBudgetFromHistory(y, () ->
                                    Toast.makeText(this, R.string.budget_import_done,
                                            Toast.LENGTH_LONG).show()))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });
        ((MaterialButton) findViewById(R.id.btnBudgetImport)).setOnClickListener(v -> {
            int y = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
            BudgetImportFlow.run(this, settings, repository, y, null);
        });

        switchAliasPrompt = findViewById(R.id.switchAliasPrompt);
        switchAliasPrompt.setChecked(settings.isAliasPromptEnabled());
        ((MaterialButton) findViewById(R.id.btnManageAliases)).setOnClickListener(
                v -> startActivity(new Intent(this, AliasActivity.class)));
    }

    /**
     * Die drei Dateiauswahl-Anmeldungen.
     *
     * <p>Muss beim Aufbau der Maske geschehen: Ein {@code ActivityResultLauncher} lässt sich später
     * nicht mehr anmelden.</p>
     */
    private void setupLaunchers() {
        csvLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri != null) {
                        importCsvLocal(uri);
                    }
                });
        backupLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/octet-stream"),
                uri -> {
                    if (uri != null) {
                        doBackup(uri);
                    }
                });
        restoreBackupLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        backupRestore.restore(uri);
                    }
                });
    }

    /** Alle Knöpfe der Maske an einer Stelle. */
    private void setupButtons() {
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
            if (blockIfImporting() || !steuersatzIstBrauchbar()) {
                return;
            }
            saveSettings();
            finishToMainActivity();
        });

        ((MaterialButton) findViewById(R.id.btnBackupProfile)).setOnClickListener(v -> askBackupOptions());
        ((MaterialButton) findViewById(R.id.btnRestoreProfile)).setOnClickListener(v -> confirmRestore());
        ((MaterialButton) findViewById(R.id.btnDeleteAccount)).setOnClickListener(v -> manageAccounts());
        ((MaterialButton) findViewById(R.id.btnResetProfile)).setOnClickListener(v -> confirmResetProfile());
    }


    /**
     * Ein Konten-Import läuft im Hintergrund auf einer eigenen Datenbankverbindung weiter, auch wenn
     * diese Maske schließt. {@link #finishToMainActivity()} schließt dabei ggf. die Datenbank – trifft
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
     * Schließt diese Maske und setzt den Activity-Stack auf eine frische {@code MainActivity} zurück –
     * wie schon {@link ProfileSwitchDialog} es beim Wechseln/Löschen tut. Nötig, weil ein Profilwechsel
     * unterwegs (z. B. über „Von anderem Profil übernehmen" oder einen Restore) die Datenbankverbindung
     * schließen kann ({@link de.spahr.ausgaben.settings.ProfileManager#switchTo}): ein einfaches
     * {@link #finish()} kehrte sonst zu einer darunterliegenden {@code SettingsActivity}/
     * {@code MainActivity} zurück, deren {@code Repository} noch die alte, jetzt geschlossene
     * Verbindung hält – das stürzte beim nächsten Datenbankzugriff dort ab
     * („connection pool has been closed").
     */
    private void finishToMainActivity() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
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

    /** „Profil löschen" – nur sichtbar, solange mehr als ein Profil existiert (siehe ProfileManager). */
    private void setupDeleteProfile() {
        View button = findViewById(R.id.btnDeleteProfile);
        String ownId = profiles.getActiveProfileId();
        if (profiles.getProfiles().size() <= 1) {
            button.setVisibility(View.GONE);
            return;
        }
        button.setVisibility(View.VISIBLE);
        button.setOnClickListener(v -> {
            if (blockIfImporting()) {
                return;
            }
            AppDialog.destructive(this)
                    .setTitle(R.string.profile_delete)
                    .setMessage(R.string.profile_delete_confirm_message)
                    .setPositiveButton(R.string.profile_delete, (d, w) -> {
                        profiles.deleteProfile(this, ownId);
                        Intent intent = new Intent(this, MainActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
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
        settings.setCurrency(textOf(editCurrency));
        settings.setNumberFormat(selectedNumberFormat);
        settings.setCurrencyShown(switchShowCurrency.isChecked());
        settings.setDividendGross(switchDividendGross.isChecked());
        // Der Steuersatz kommt im eingestellten Zahlenformat herein (Komma oder Punkt). Ein unlesbarer
        // Wert lässt den gespeicherten stehen – siehe TextValues.percentOrNull; abgeschaltet wird die Vorbelegung
        // nur durch ein leeres Feld.
        Double steuersatz = de.spahr.ausgaben.util.TextValues.percentOrNull(textOf(editDividendTaxRate));
        if (steuersatz != null) {
            settings.setDividendTaxPercent(steuersatz);
        }
        settings.setBudgetInternal(switchBudgetInternal.isChecked());
        settings.setAliasPromptEnabled(switchAliasPrompt.isChecked());
        de.spahr.ausgaben.settings.Currencies.refresh(this);
        de.spahr.ausgaben.settings.MoneyFormat.refresh(this);
        // Das Standardkonto bestimmt den Saldo, den die Uhr anzeigt – sonst zeigte sie bis zum
        // nächsten Sync noch das alte (full: echte Sync, foss: No-op-Stub).
        de.spahr.ausgaben.wear.BalanceSync.publish(this);
    }

    /**
     * Meldet einen unbrauchbaren Steuersatz am Feld und verhindert das Verlassen der Maske. Gerufen
     * wird das nur beim ausdrücklichen „Fertig" – wer die Maske über einen anderen Weg verlässt, soll
     * an einer Nebensächlichkeit nicht hängenbleiben; dort greift die Regel aus
     * {@link de.spahr.ausgaben.util.TextValues#percentOrNull}, den gespeicherten Wert stehen zu lassen.
     */
    private boolean steuersatzIstBrauchbar() {
        if (de.spahr.ausgaben.util.TextValues.percentOrNull(textOf(editDividendTaxRate)) != null) {
            dividendTaxLayout.setError(null);
            return true;
        }
        dividendTaxLayout.setError(getString(R.string.dividend_tax_rate_invalid));
        editDividendTaxRate.requestFocus();
        return false;
    }

    /** Dropdown mit den vier Zahlenformat-Optionen (Beispiel-Labels); Vorauswahl aus den Einstellungen. */
    private void setupNumberFormat() {
        selectedNumberFormat = settings.getNumberFormat();
        String[] labels = {
                getString(R.string.number_format_de_group),
                getString(R.string.number_format_en_group),
                getString(R.string.number_format_plain_comma),
                getString(R.string.number_format_plain_dot)};
        PickerAdapters.plain(editNumberFormat, java.util.Arrays.asList(labels));
        for (int i = 0; i < NUMBER_FORMAT_VALUES.length; i++) {
            if (NUMBER_FORMAT_VALUES[i].equals(selectedNumberFormat)) {
                editNumberFormat.setText(labels[i], false);
                break;
            }
        }
        editNumberFormat.setOnItemClickListener((parent, view, position, id) ->
                selectedNumberFormat = NUMBER_FORMAT_VALUES[position]);
    }

    // ---- Orte (Bargeld-Bestände) ----

    private void setupPlaces() {
        placesContainer = findViewById(R.id.placesContainer);
        editDefaultPlace = findViewById(R.id.editDefaultPlace);
        editPlacesAccount = findViewById(R.id.editPlacesAccount);

        placesAccount = settings.getDefaultAccount();
        repository.getAccountNames(names -> {
            if (placesAccount.isEmpty() && !names.isEmpty()) {
                placesAccount = names.get(0);
            }
            PickerAdapters.accounts(repository, editPlacesAccount, names);
            editPlacesAccount.setText(placesAccount, false);
            refreshPlaces();
        });
        PickerBehaviour.onCommitted(editPlacesAccount, value -> {
            placesAccount = value;
            refreshPlaces();
        });

        android.widget.EditText editNewPlace = findViewById(R.id.editNewPlace);
        ((MaterialButton) findViewById(R.id.btnAddPlace)).setOnClickListener(v -> {
            String name = editNewPlace.getText() == null ? "" : editNewPlace.getText().toString().trim();
            if (!name.isEmpty() && !placesAccount.isEmpty()) {
                placesStore.addPlace(placesAccount, name);
                editNewPlace.setText("");
                refreshPlaces();
            }
        });

        PickerBehaviour.onCommitted(editDefaultPlace, value ->
                placesStore.setDefaultPlace(placesAccount,
                        de.spahr.ausgaben.settings.PlacesStore.NO_PLACE.equals(value) ? "" : value));

        refreshPlaces();
    }

    private void refreshPlaces() {
        List<String> places = placesAccount.isEmpty()
                ? new ArrayList<>() : placesStore.getPlaces(placesAccount);

        placesContainer.removeAllViews();
        for (String place : places) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);

            TextView name = new TextView(this);
            name.setText("✎  " + place);
            name.setTextSize(16f);
            name.setPadding(0, 24, 0, 24);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            name.setLayoutParams(lp);
            android.util.TypedValue ripple = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
            name.setBackgroundResource(ripple.resourceId);
            name.setContentDescription(getString(R.string.place_rename_hint, place));
            name.setOnClickListener(v -> renamePlaceDialog(place));

            MaterialButton remove = new MaterialButton(this,
                    null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            remove.setText(R.string.remove);
            remove.setTextColor(getColor(R.color.expense_red));
            remove.setStrokeColor(android.content.res.ColorStateList.valueOf(getColor(R.color.expense_red)));
            remove.setOnClickListener(v -> confirmRemovePlace(place));

            row.addView(name);
            row.addView(remove);
            placesContainer.addView(row);
        }

        List<String> options = new ArrayList<>(places);
        options.add(de.spahr.ausgaben.settings.PlacesStore.NO_PLACE);
        PickerAdapters.places(editDefaultPlace, options);
        String def = placesAccount.isEmpty() ? "" : placesStore.getDefaultPlace(placesAccount);
        editDefaultPlace.setText(def.isEmpty() ? de.spahr.ausgaben.settings.PlacesStore.NO_PLACE : def, false);
    }

    private void renamePlaceDialog(String oldName) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setText(oldName);
        input.setSelectAllOnFocus(true);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.setPadding(pad, pad / 2, pad, 0);
        frame.addView(input);
        new AppDialog(this)
                .setTitle(R.string.place_rename_title)
                .setView(frame)
                .setPositiveButton(R.string.rename, (d, w) -> {
                    String newName = input.getText().toString().trim();
                    if (!newName.isEmpty() && !newName.equals(oldName)) {
                        placesStore.renamePlace(placesAccount, oldName, newName);
                        repository.renamePlaceEntries(placesAccount, oldName, newName, this::refreshPlaces);
                    }
                })
                .show();
    }

    private void confirmRemovePlace(String place) {
        AppDialog.destructive(this)
                .setTitle(R.string.place_remove_title)
                .setMessage(getString(R.string.place_remove_message, place))
                .setPositiveButton(R.string.remove, (d, w) -> {
                    placesStore.removePlace(placesAccount, place);
                    repository.deletePlaceEntries(placesAccount, place, this::refreshPlaces);
                })
                .show();
    }

    /**
     * Öffnet diese Maske zum Bearbeiten eines (ggf. noch nicht aktiven) Profils – schaltet bei Bedarf
     * erst dorthin um. Aufrufer: {@code SettingsActivity} ("Profil ändern") und der lange Druck auf
     * eine Zeile in {@link ProfileSwitchDialog}.
     */
    public static void startForEditing(android.app.Activity activity, String profileId) {
        de.spahr.ausgaben.settings.ProfileManager pm =
                new de.spahr.ausgaben.settings.ProfileManager(activity);
        if (!profileId.equals(pm.getActiveProfileId())) {
            pm.switchTo(activity, profileId);
        }
        activity.startActivity(new Intent(activity, ProfileSettingsActivity.class));
    }

    // ---- Verbindung testen / .kmy auswählen (gleiches Verhalten wie in den Einstellungen) ----

    /**
     * Diagnose: läuft die ganze Kette durch und zeigt je Schritt Ergebnis und rohen Statuscode – die
     * schnellste Antwort auf „warum geht es nicht?". Welche Kette, hängt an der Serverart.
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
            DiagnosticsDialog.runSmb(this, textOf(editUrl), textOf(editUser), password, folder, file);
        } else {
            DiagnosticsDialog.runWebDav(this, textOf(editUrl), textOf(editUser), password,
                    !SettingsStore.SERVER_WEBDAV.equals(type), folder, file);
        }
    }

    // ---- Konten importieren (gleicher Ablauf wie MainActivity.onAddAccountClicked) ----

    private void importAccounts() {
        // Ohne diese Sperre startet jeder weitere Tipp einen zweiten Download und einen zweiten Import
        // auf dieselbe Datenbank – während der erste noch schreibt.
        if (blockIfImporting()) {
            return;
        }
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
     * Nach einem Kontenimport sind die Dropdowns für Standardkonto und die Konto-Auswahl der
     * Orte-Verwaltung noch auf dem alten (meist leeren) Stand – ohne Auffrischen stünden die gerade
     * importierten Konten dort nicht zur Auswahl.
     */
    private void refreshAccountDependentFields() {
        repository.getAccountNames(names -> {
            PickerAdapters.accounts(repository, editDefaultAccount, names);
            PickerAdapters.accounts(repository, editPlacesAccount, names);
            if (placesAccount.isEmpty() && !names.isEmpty()) {
                placesAccount = names.get(0);
                editPlacesAccount.setText(placesAccount, false);
                refreshPlaces();
            }
        });
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
            if (is == null) {
                // Einmal davor prüfen statt in jedem Schleifendurchlauf – und vor allem: sagen, dass
                // die Datei nicht lesbar war, statt eine leere Zeichenkette zurückzugeben.
                throw new java.io.IOException(getString(R.string.backup_source_unreadable));
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private String textOf(TextInputEditText field) {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }

    // ---- Sicherung/Wiederherstellen (nur das aktive Profil) ----

    /** Vor dem Sichern fragen: Server-Passwort mitsichern? Datei mit eigenem Passwort verschlüsseln? */
    private void askBackupOptions() {
        HostedDialog.show(this, DLG_BACKUP_OPTIONS, null);
    }

    /**
     * Der Sichern-Dialog – nach einer Drehung baut ihn die neue Maske erneut.
     *
     * <p>Was eingetippt war, stellt das Fenstersystem selbst wieder her: Die Felder im eingebundenen
     * Layout haben ids, und ein {@link androidx.fragment.app.DialogFragment} sichert den Zustand seiner
     * Ansichten mit. Vorher war der Dialog nach der Drehung weg, und das zweimal eingetippte Passwort
     * mit ihm.</p>
     */
    private android.app.Dialog buildBackupOptions() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_backup_options, null, false);
        ((TextView) view.findViewById(R.id.backupOptionsMessage))
                .setText(R.string.backup_options_message_profile);
        com.google.android.material.checkbox.MaterialCheckBox include =
                view.findViewById(R.id.backupIncludePassword);
        TextInputEditText pw = view.findViewById(R.id.backupPassword);
        TextInputEditText repeat = view.findViewById(R.id.backupPasswordRepeat);
        AlertDialog dialog =
                new AppDialog(this)
                        .setTitle(R.string.backup_options_title)
                        .setView(view)
                        .setPositiveButton(R.string.backup_db, null)   // erst prüfen, dann schließen
                        .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String p1 = textOf(pw);
                    if (!p1.equals(textOf(repeat))) {
                        Toast.makeText(this, R.string.backup_password_mismatch, Toast.LENGTH_LONG).show();
                        return;
                    }
                    // Leer heißt „ohne Verschlüsselung" – das bleibt erlaubt. Nur ein Passwort, das
                    // seinen Zweck nicht erfüllen kann, wird abgelehnt: die Sicherung enthält den
                    // gesamten Buchungsbestand und liegt danach irgendwo als Datei.
                    if (!p1.isEmpty() && p1.length() < de.spahr.ausgaben.backup.BackupCrypto.MIN_PASSWORD_LENGTH) {
                        Toast.makeText(this, getString(R.string.backup_password_too_short,
                                de.spahr.ausgaben.backup.BackupCrypto.MIN_PASSWORD_LENGTH), Toast.LENGTH_LONG).show();
                        return;
                    }
                    backupIncludeServerPassword = include.isChecked();
                    backupPassword = p1;
                    dialog.dismiss();
                    backupLauncher.launch("kmysync-profil-" + timestamp() + (p1.isEmpty() ? ".zip" : ".abk"));
                }));
        return dialog;
    }

    private void doBackup(Uri uri) {
        // Aus dem Feld genommen, bevor der Faden startet: danach hängt das Passwort nur noch am
        // laufenden Vorgang und nicht mehr an der Maske. Bis 1.12 blieb es dort bis zum Schließen der
        // Activity stehen, obwohl es nach dem Schreiben der Datei niemand mehr braucht.
        final String passwort = backupPassword;
        backupPassword = "";
        new Thread(() -> {
            try {
                byte[] file = BackupStore.createProfile(this, backupIncludeServerPassword, passwort);
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) {
                        // Ohne diese Prüfung landete die NullPointerException im catch darunter und
                        // der Nutzer las „Sicherung fehlgeschlagen: null".
                        throw new java.io.IOException(getString(R.string.backup_target_unwritable));
                    }
                    out.write(file);
                }
                runOnUiThread(() -> Toast.makeText(this, R.string.backup_done, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.backup_failed, msg), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private String timestamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.GERMANY).format(new Date());
    }

    private void confirmRestore() {
        restoreBackupLauncher.launch(new String[]{"*/*"});
    }

    // ---- Konto löschen/schließen (nur das aktive Profil, siehe Repository) ----

    /**
     * Alle Konten <b>und Depots</b> mit Status als Mehrfachauswahl (Depots tragen den Zusatz „(Depot)").
     * Untere Zeile (vor „Abbrechen"): „Löschen" (immer bei Auswahl) und die kontextabhängige Aktion
     * „Schließen"/„Öffnen" – die gibt es nur für gewöhnliche Konten, nur wenn <b>alle</b> ausgewählten
     * Saldo 0 haben bzw. alle geschlossen sind; sobald ein Depot dabei ist, entfällt sie (Depotsaldo
     * kommt aus Kursen, nicht aus der Buchungssumme).
     */
    private void manageAccounts() {
        repository.getAllAccountsWithStatus(all -> {
            if (all.isEmpty()) {
                Toast.makeText(this, R.string.no_accounts, Toast.LENGTH_LONG).show();
                return;
            }
            repository.getAllAccountBalances(balances -> showAccountsDialog(all, balances));
        });
    }

    /** Mehrfachauswahl-Dialog; die Aktionsbuttons werden je nach Auswahl dynamisch ein-/ausgeblendet. */
    private void showAccountsDialog(List<de.spahr.ausgaben.db.Account> accounts, Map<String, Long> balances) {
        final boolean[] checked = new boolean[accounts.size()];
        final Runnable[] updater = new Runnable[1];
        final AlertDialog dlg = AppDialog.destructive(this)
                .setTitle(R.string.account_manage_choose)
                .setMultiChoiceItems(kontozeilen(accounts), checked, (d, which, isChecked) -> {
                    checked[which] = isChecked;
                    if (updater[0] != null) {
                        updater[0].run();
                    }
                })
                .setNeutralButton(R.string.account_close, null)
                .setPositiveButton(R.string.delete, null)
                .create();
        dlg.setOnShowListener(dialog -> {
            Button delBtn = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
            Button actBtn = dlg.getButton(AlertDialog.BUTTON_NEUTRAL); // Schließen/Öffnen (dynamisch)
            updater[0] = () -> zeigeKnoepfe(delBtn, actBtn, selectedAccounts(accounts, checked), balances);
            delBtn.setOnClickListener(v -> {
                List<de.spahr.ausgaben.db.Account> sel = selectedAccounts(accounts, checked);
                if (!sel.isEmpty()) {
                    dlg.dismiss();
                    confirmDeleteAccounts(sel);
                }
            });
            actBtn.setOnClickListener(v -> {
                List<de.spahr.ausgaben.db.Account> sel = selectedAccounts(accounts, checked);
                if (!sel.isEmpty()) {
                    dlg.dismiss();
                    schliesseOderOeffne(sel);
                }
            });
            updater[0].run();
        });
        dlg.show();
    }

    /** Je Konto eine Zeile: Name (Depots gekennzeichnet) und ob es offen oder geschlossen ist. */
    private String[] kontozeilen(List<de.spahr.ausgaben.db.Account> accounts) {
        String[] items = new String[accounts.size()];
        for (int i = 0; i < accounts.size(); i++) {
            de.spahr.ausgaben.db.Account a = accounts.get(i);
            String status = getString(a.closed
                    ? R.string.account_status_closed : R.string.account_status_active);
            String name = a.isDepot() ? getString(R.string.account_status_depot_marker, a.name) : a.name;
            items[i] = getString(R.string.account_status_line, name, status);
        }
        return items;
    }

    /**
     * Welche Knöpfe zur getroffenen Auswahl passen.
     *
     * <p>Löschen geht immer, sobald etwas ausgewählt ist. Schließen dagegen nur, wenn <b>alle</b>
     * Ausgewählten offen sind und einen Saldo von 0 haben — ein Konto mit Geld darauf zu schließen
     * hieße, den Betrag verschwinden zu lassen. Wieder öffnen umgekehrt nur, wenn alle geschlossen
     * sind. Ein Depot lässt sich gar nicht schließen; sein Bestand hängt an den Wertpapieren.</p>
     */
    private void zeigeKnoepfe(Button delBtn, Button actBtn, List<de.spahr.ausgaben.db.Account> sel,
                              Map<String, Long> balances) {
        delBtn.setEnabled(!sel.isEmpty());
        boolean anyDepot = false;
        boolean allClosed = !sel.isEmpty();
        boolean allOpenZero = !sel.isEmpty();
        for (de.spahr.ausgaben.db.Account a : sel) {
            if (a.isDepot()) {
                anyDepot = true;
            }
            long bal = balances.containsKey(a.name) ? balances.get(a.name) : 0L;
            if (!a.closed) {
                allClosed = false;
            }
            if (a.closed || bal != 0) {
                allOpenZero = false;
            }
        }
        if (anyDepot) {
            actBtn.setVisibility(View.GONE);
        } else if (allClosed) {
            actBtn.setText(R.string.account_reopen);
            actBtn.setVisibility(View.VISIBLE);
        } else if (allOpenZero) {
            actBtn.setText(R.string.account_close);
            actBtn.setVisibility(View.VISIBLE);
        } else {
            actBtn.setVisibility(View.GONE);   // Schließen nur bei Saldo 0 aller Ausgewählten
        }
    }

    /** Geschlossene wieder öffnen, offene schließen — was von beidem, sagt die Auswahl. */
    private void schliesseOderOeffne(List<de.spahr.ausgaben.db.Account> sel) {
        boolean allClosed = true;
        for (de.spahr.ausgaben.db.Account a : sel) {
            if (!a.closed) {
                allClosed = false;
                break;
            }
        }
        final List<String> names = accountNames(sel);
        final boolean reopen = allClosed;
        repository.setAccountsClosed(names, !reopen, () ->
                Toast.makeText(this, getString(reopen
                        ? R.string.accounts_reopened_done : R.string.accounts_closed_done,
                        names.size()), Toast.LENGTH_SHORT).show());
    }

    private List<de.spahr.ausgaben.db.Account> selectedAccounts(
            List<de.spahr.ausgaben.db.Account> accounts, boolean[] checked) {
        List<de.spahr.ausgaben.db.Account> sel = new ArrayList<>();
        for (int i = 0; i < accounts.size(); i++) {
            if (checked[i]) {
                sel.add(accounts.get(i));
            }
        }
        return sel;
    }

    private List<String> accountNames(List<de.spahr.ausgaben.db.Account> accounts) {
        List<String> names = new ArrayList<>();
        for (de.spahr.ausgaben.db.Account a : accounts) {
            names.add(a.name);
        }
        return names;
    }

    private void confirmDeleteAccounts(List<de.spahr.ausgaben.db.Account> selected) {
        List<String> accounts = new ArrayList<>();
        List<String> depots = new ArrayList<>();
        for (de.spahr.ausgaben.db.Account a : selected) {
            (a.isDepot() ? depots : accounts).add(a.name);
        }
        int total = accounts.size() + depots.size();
        String message = depots.isEmpty()
                ? getString(R.string.delete_accounts_confirm_message, total)
                : getString(R.string.delete_accounts_confirm_message_with_depots, total, depots.size());
        AppDialog.destructive(this)
                .setTitle(R.string.delete_account_confirm_title)
                .setMessage(message)
                .setPositiveButton(R.string.delete, (d, w) -> repository.deleteAccountsAndDepots(
                        accounts, depots, () -> {
                    for (String account : accounts) {
                        placesStore.removeAccount(account);
                    }
                    Toast.makeText(this, getString(R.string.accounts_deleted_done, total),
                            Toast.LENGTH_LONG).show();
                    refreshAccountDependentFields();
                }))
                .show();
    }

    // ---- Nur dieses Profil zurücksetzen ----

    /**
     * Setzt nur das aktive Profil zurück (Datenbank + dessen Server-/kmy-Einstellungen); andere Profile
     * bleiben unberührt.
     */
    private void confirmResetProfile() {
        AppDialog.destructive(this)
                .setTitle(R.string.reset_profile_confirm_title)
                .setMessage(R.string.reset_profile_confirm_message)
                .setPositiveButton(R.string.reset_profile_db,
                        (d, w) -> repository.resetAllData(this::finishResetProfile))
                .show();
    }

    private void finishResetProfile() {
        profiles.clearActiveProfileSettings(this);
        Toast.makeText(this, R.string.reset_done, Toast.LENGTH_LONG).show();
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
    }

}
