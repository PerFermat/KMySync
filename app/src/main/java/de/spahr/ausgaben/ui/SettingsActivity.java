package de.spahr.ausgaben.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.spahr.ausgaben.AusgabenApp;
import de.spahr.ausgaben.R;
import de.spahr.ausgaben.util.UriBytes;
import de.spahr.ausgaben.backup.BackupStore;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.export.ExportCoordinator;
import de.spahr.ausgaben.security.BiometricAuth;
import de.spahr.ausgaben.settings.PlacesStore;
import de.spahr.ausgaben.settings.SettingsStore;

public class SettingsActivity extends LocalizedActivity implements HostedDialog.Host {

    /** Schlüssel der Dialoge dieser Maske – siehe {@link HostedDialog}. */
    private static final String DLG_BACKUP_OPTIONS = "dlg_backupOptions";

    @Override
    public android.app.Dialog buildDialog(String key, Bundle args) {
        if (DLG_BACKUP_OPTIONS.equals(key)) {
            return buildBackupOptions();
        }
        return fullBackupRestoreFlow == null ? null : fullBackupRestoreFlow.buildDialog(key, args);
    }

    @Override
    public void onDialogCancelled(String key, Bundle args) {
        // Beide Dialoge dürfen weggetippt werden; es folgt nichts daraus.
    }


    private SettingsStore settings;
    private Repository repository;

    private MaterialSwitch switchDarkMode;
    private MaterialSwitch switchScheduledReminder;
    private MaterialSwitch switchAppLock;

    private MaterialAutoCompleteTextView editLanguage;
    private com.google.android.material.slider.Slider sliderFontSize;
    /** Schriftgrößen-Werte je Slider-Position 0..3 (klein → sehr groß). */
    private static final String[] FONT_SIZE_VALUES = {
            SettingsStore.FONT_SIZE_SMALL, SettingsStore.FONT_SIZE_NORMAL,
            SettingsStore.FONT_SIZE_LARGE, SettingsStore.FONT_SIZE_XLARGE};
    private java.util.List<de.spahr.ausgaben.db.Language> languages = new java.util.ArrayList<>();

    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private ActivityResultLauncher<String> backupLauncher;
    private de.spahr.ausgaben.backup.FullBackupRestoreFlow fullBackupRestoreFlow;
    /** Antworten aus dem Sichern-Dialog – gelten bis der Dateiname gewählt ist. */
    private boolean backupIncludeServerPassword;
    private String backupPassword = "";
    private ActivityResultLauncher<Uri> exportTreeLauncher;
    private ActivityResultLauncher<String> templateExportLauncher;
    private ActivityResultLauncher<String[]> languageUploadLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        settings = new SettingsStore(this);
        repository = new Repository(this);

        switchDarkMode = findViewById(R.id.switchDarkMode);

        switchDarkMode.setChecked(settings.isDarkMode());
        switchDarkMode.setOnCheckedChangeListener((b, checked) -> {
            int mode = checked ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO;
            settings.setNightMode(mode);
            AppCompatDelegate.setDefaultNightMode(mode);
        });

        // Tägliche Erinnerung an fällige geplante Buchungen (Standard aus).
        switchScheduledReminder = findViewById(R.id.switchScheduledReminder);
        switchScheduledReminder.setChecked(settings.isScheduledReminderEnabled());
        switchScheduledReminder.setOnCheckedChangeListener((b, checked) -> {
            settings.setScheduledReminderEnabled(checked);
            if (checked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                return;   // Wecker erst nach der Antwort stellen
            }
            de.spahr.ausgaben.notify.ScheduledReminder.apply(this);
        });

        switchAppLock = findViewById(R.id.switchAppLock);
        switchAppLock.setChecked(settings.isAppLockEnabled());
        switchAppLock.setOnCheckedChangeListener((b, checked) -> onAppLockToggled(b, checked));

        MaterialSwitch switchGps = findViewById(R.id.switchGps);
        switchGps.setChecked(settings.isGpsEnabled());
        switchGps.setOnCheckedChangeListener((b, checked) -> settings.setGpsEnabled(checked));

        MaterialSwitch switchAmountSuggest = findViewById(R.id.switchAmountSuggest);
        switchAmountSuggest.setChecked(settings.isAmountSuggestEnabled());
        switchAmountSuggest.setOnCheckedChangeListener(
                (b, checked) -> settings.setAmountSuggestEnabled(checked));

        // Nur im full-Build (Wear-Anbindung): Offline-Sprachpaket auf der Uhr installieren lassen.
        // Der Zustand reist über LanguageSync (/language-DataItem) zur Uhr.
        MaterialSwitch switchWearModel = findViewById(R.id.switchWearOfflineModel);
        if (getResources().getBoolean(R.bool.wear_bridge)) {
            switchWearModel.setChecked(settings.isWearInstallModel());
            switchWearModel.setOnCheckedChangeListener((b, checked) -> {
                settings.setWearInstallModel(checked);
                de.spahr.ausgaben.wear.LanguageSync.publish(this);
            });
        } else {
            switchWearModel.setVisibility(android.view.View.GONE);
        }

        // Abrechnungen einlesen. Der Hinweis kommt nur beim Einschalten und hält nichts auf – er sagt,
        // woran die Erkennung gemessen wurde, damit niemand die erkannten Zahlen ungeprüft übernimmt.
        MaterialSwitch switchStatement = findViewById(R.id.switchStatement);
        switchStatement.setChecked(settings.isStatementEnabled());
        switchStatement.setOnCheckedChangeListener((b, checked) -> {
            settings.setStatementEnabled(checked);
            if (checked) {
                new AppDialog(this)
                        .setTitle(R.string.statement_switch)
                        .setMessage(R.string.statement_switch_notice)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        });

        MaterialSwitch switchReceipt = findViewById(R.id.switchReceipt);
        switchReceipt.setChecked(settings.isReceiptEnabled());
        switchReceipt.setOnCheckedChangeListener((b, checked) -> settings.setReceiptEnabled(checked));

        editLanguage = findViewById(R.id.editLanguage);
        sliderFontSize = findViewById(R.id.sliderFontSize);
        setupFontSize();
        setupLanguages();
        ((MaterialButton) findViewById(R.id.btnExportTemplate)).setOnClickListener(
                v -> templateExportLauncher.launch(
                        "kmysync-language-" + settings.getLanguage() + ".json"));
        ((MaterialButton) findViewById(R.id.btnUploadLanguage)).setOnClickListener(
                v -> languageUploadLauncher.launch(new String[]{"application/json"}));

        registerLaunchers();

        ((MaterialButton) findViewById(R.id.btnChangeProfile)).setOnClickListener(
                v -> ProfileSettingsActivity.startForEditing(this, new de.spahr.ausgaben.settings.ProfileManager(this)
                        .getActiveProfileId()));
        ((MaterialButton) findViewById(R.id.btnNewProfile)).setOnClickListener(
                v -> OnboardingActivity.startForNewProfile(this));

        ((MaterialButton) findViewById(R.id.btnExportAll)).setOnClickListener(v -> exportAll());
        ((MaterialButton) findViewById(R.id.btnBackup)).setOnClickListener(v -> askBackupOptions());
        ((MaterialButton) findViewById(R.id.btnRestore)).setOnClickListener(
                v -> fullBackupRestoreFlow.start());
        ((MaterialButton) findViewById(R.id.btnReset)).setOnClickListener(v -> confirmReset());
    }
    /** Schalter „App mit Biometrie schützen": bei Aktivierung Verfügbarkeit prüfen. */
    private void onAppLockToggled(CompoundButton buttonView, boolean checked) {
        if (checked) {
            String problem = BiometricAuth.availabilityMessage(this);
            if (problem == null) {
                settings.setAppLockEnabled(true);
                ((AusgabenApp) getApplication()).markUnlocked();
            } else {
                buttonView.setChecked(false); // zurücksetzen (löst erneut den Listener aus → „aus")
                new AppDialog(this)
                        .setTitle(R.string.app_lock_switch)
                        .setMessage(problem)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        } else {
            settings.setAppLockEnabled(false);
            ((AusgabenApp) getApplication()).markUnlocked();
        }
    }

    private void registerLaunchers() {
        // Benachrichtigungs-Berechtigung (ab Android 13) für die Erinnerung an fällige Buchungen.
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        de.spahr.ausgaben.notify.ScheduledReminder.apply(this);
                    } else {
                        settings.setScheduledReminderEnabled(false);
                        switchScheduledReminder.setChecked(false);
                        Toast.makeText(this, R.string.reminder_no_permission, Toast.LENGTH_LONG).show();
                    }
                });
        backupLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/octet-stream"),
                uri -> {
                    if (uri != null) {
                        doBackup(uri);
                    }
                });
        fullBackupRestoreFlow = new de.spahr.ausgaben.backup.FullBackupRestoreFlow(this);
        exportTreeLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(),
                uri -> {
                    if (uri != null) {
                        getContentResolver().takePersistableUriPermission(uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        settings.setLocalExportTree(uri.toString());
                        runExportAll();
                    }
                });
        templateExportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/json"),
                uri -> {
                    if (uri != null) {
                        writeTemplate(uri);
                    }
                });
        languageUploadLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        importLanguageFile(uri);
                    }
                });
    }

    /**
     * Schriftgrößen-Slider (links klein … rechts sehr groß). Die Änderung greift – wie beim Nacht-Modus –
     * sofort: die Auswahl wird gespeichert und die aktuelle Ansicht neu aufgebaut (Live-Vorschau). Der
     * Rest der App zieht beim nächsten Anzeigen nach (siehe {@link LocalizedActivity}).
     */
    private void setupFontSize() {
        String[] labels = {
                getString(R.string.font_size_small),
                getString(R.string.font_size_normal),
                getString(R.string.font_size_large),
                getString(R.string.font_size_xlarge)};
        sliderFontSize.setValue(indexOfFontSize(settings.getFontSize()));
        sliderFontSize.setLabelFormatter(value -> labels[Math.round(value)]);
        // Beim Loslassen anwenden (nicht bei jedem Zwischenschritt), damit kein Neuaufbau-Stakkato entsteht.
        sliderFontSize.addOnSliderTouchListener(new com.google.android.material.slider.Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(com.google.android.material.slider.Slider slider) {
            }

            @Override
            public void onStopTrackingTouch(com.google.android.material.slider.Slider slider) {
                applyFontSize(FONT_SIZE_VALUES[Math.round(slider.getValue())]);
            }
        });
    }

    private int indexOfFontSize(String code) {
        for (int i = 0; i < FONT_SIZE_VALUES.length; i++) {
            if (FONT_SIZE_VALUES[i].equals(code)) {
                return i;
            }
        }
        return 1; // normal
    }

    /** Speichert die Schriftgröße und baut die Ansicht sofort neu auf (Live-Vorschau, wie Nacht-Modus). */
    private void applyFontSize(String code) {
        if (code.equals(settings.getFontSize())) {
            return;
        }
        settings.setFontSize(code);
        de.spahr.ausgaben.settings.FontScale.refresh(this);
        recreate();
    }

    // ---- Sprache ----

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
        // Per-App-Sprache anwenden – erzeugt alle Activities neu (auch im Back-Stack).
        AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.forLanguageTags(code));
    }

    private void writeTemplate(Uri uri) {
        String lang = settings.getLanguage();
        repository.buildLanguageTemplate(lang, template -> {
            if (template == null) {
                return;
            }
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out != null) {
                    out.write(template.json.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                Toast.makeText(this, getString(R.string.language_upload_failed, reason(e)),
                        Toast.LENGTH_LONG).show();
                return;
            }
            // Bei einer eingebauten Sprache ist die Vorlage leer; „alle 821 fehlen" wäre dort keine
            // Auskunft. Gezählt wird nur, wo vorbelegt wurde – also bei einer hochgeladenen Sprache.
            if (de.spahr.ausgaben.i18n.LocaleManager.isBuiltIn(lang)) {
                Toast.makeText(this, R.string.language_export_done, Toast.LENGTH_LONG).show();
                return;
            }
            new AppDialog(this)
                    .setTitle(R.string.language_export_done)
                    .setMessage(template.missing == 0
                            ? getString(R.string.language_export_complete, template.total)
                            : getString(R.string.language_export_missing,
                                    template.missing, template.total))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        });
    }

    /** Grund einer Ausnahme für eine Meldung – nie das Wort „null", das sonst im Text landen würde. */
    private static String reason(Exception e) {
        String m = e == null ? null : e.getMessage();
        return m == null || m.isEmpty() ? String.valueOf(e) : m;
    }

    private void importLanguageFile(Uri uri) {
        try {
            String json = new String(UriBytes.read(this, uri), StandardCharsets.UTF_8);
            de.spahr.ausgaben.i18n.TranslationIo.Parsed parsed =
                    de.spahr.ausgaben.i18n.TranslationIo.parse(json);
            repository.importLanguage(parsed, () -> {
                Toast.makeText(this, getString(R.string.language_upload_done, parsed.name),
                        Toast.LENGTH_LONG).show();
                setupLanguages();
            });
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.language_upload_failed, reason(e)),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void confirmReset() {
        AppDialog.destructive(this)
                .setTitle(R.string.reset_confirm_title)
                .setMessage(R.string.reset_confirm_message)
                .setPositiveButton(R.string.reset_db, (d, w) -> repository.resetAllData(this::finishReset))
                .show();
    }

    /**
     * Nach dem Leeren der Datenbank den Rest des Auslieferungszustands herstellen: Einstellungen (inkl.
     * Server-Passwort), Orte-Definitionen, offene Belege samt Dateien und die Widget-Auswahl löschen, dann
     * die App neu starten, damit auch alle Zwischenspeicher (Währung, Zahlenformat, gewähltes Konto …)
     * frisch sind – die App steht danach wie nach der Installation da (Willkommen-Assistent).
     */
    private void finishReset() {
        settings.clearAll();
        new de.spahr.ausgaben.settings.ProfileManager(this).clearAll(this);
        new de.spahr.ausgaben.settings.PlacesStore(this).clearAll();
        de.spahr.ausgaben.receipt.Receipts.reset(this);
        getSharedPreferences("widget_selection", MODE_PRIVATE).edit().clear().commit();
        Toast.makeText(this, R.string.reset_done, Toast.LENGTH_LONG).show();
        android.content.Intent launch =
                getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(launch);
        }
        finishAffinity();
        Runtime.getRuntime().exit(0);
    }

    private void exportAll() {
        if (!settings.hasRemoteConfig() && settings.getLocalExportTree().isEmpty()) {
            Toast.makeText(this, R.string.choose_export_folder, Toast.LENGTH_LONG).show();
            exportTreeLauncher.launch(null);
            return;
        }
        runExportAll();
    }

    private void runExportAll() {
        Toast.makeText(this, R.string.export_all_running, Toast.LENGTH_SHORT).show();
        String tree = settings.hasRemoteConfig() ? null : settings.getLocalExportTree();
        new ExportCoordinator(this, repository, settings, tree).exportAll(
                (message, refreshNeeded) -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    /**
     * Vor dem Sichern fragen: Server-Passwort mitsichern? Und soll die Datei mit einem eigenen Passwort
     * verschlüsselt werden? Erst danach den Dateinamen wählen lassen.
     */
    private void askBackupOptions() {
        HostedDialog.show(this, DLG_BACKUP_OPTIONS, null);
    }

    /**
     * Der Sichern-Dialog – nach einer Drehung baut ihn die neue Maske erneut. Was eingetippt war,
     * stellt das Fenstersystem selbst wieder her (siehe HostedDialog).
     */
    private android.app.Dialog buildBackupOptions() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_backup_options, null, false);
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
                    // Leer heißt „ohne Verschlüsselung" – das bleibt erlaubt. Hier geht es um die
                    // Sicherung *aller* Profile; ein Passwort, das seinen Zweck nicht erfüllen kann,
                    // wird abgelehnt.
                    if (!p1.isEmpty() && p1.length() < de.spahr.ausgaben.backup.BackupCrypto.MIN_PASSWORD_LENGTH) {
                        Toast.makeText(this, getString(R.string.backup_password_too_short,
                                de.spahr.ausgaben.backup.BackupCrypto.MIN_PASSWORD_LENGTH), Toast.LENGTH_LONG).show();
                        return;
                    }
                    backupIncludeServerPassword = include.isChecked();
                    backupPassword = p1;
                    dialog.dismiss();
                    backupLauncher.launch("kmysync-backup-" + timestamp() + (p1.isEmpty() ? ".zip" : ".abk"));
                }));
        return dialog;
    }

    private void doBackup(Uri uri) {
        // Aus dem Feld genommen, bevor der Faden startet – siehe ProfileSettingsActivity.doBackup.
        final String passwort = backupPassword;
        backupPassword = "";
        new Thread(() -> {
            try {
                byte[] file = BackupStore.createAll(this,
                        backupIncludeServerPassword, passwort);
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
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

    private String textOf(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString();
    }
}
