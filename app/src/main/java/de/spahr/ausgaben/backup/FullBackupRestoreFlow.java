package de.spahr.ausgaben.backup;

import android.content.Intent;
import android.net.Uri;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.ui.AppDialog;
import de.spahr.ausgaben.ui.MainActivity;
import de.spahr.ausgaben.ui.Ui;

/**
 * Komplettsicherung (alle Profile) wiederherstellen: Datei wählen → ggf. Passwort abfragen → Umfang
 * wählen (Daten/Einstellungen/Beides) → Sicherheitsabfrage → {@link BackupStore#restoreAllData} /
 * {@link BackupStore#restoreAllSettings} → Neustart in {@link MainActivity}.
 *
 * <p>Eigenständige, wiederverwendbare Fassung des Ablaufs, der früher nur in {@code SettingsActivity}
 * lag – wird sowohl dort als auch im Onboarding-Assistenten verwendet (dort für „Komplettsicherung
 * wiederherstellen" statt ein neues Profil einzurichten). Konstruktor registriert den Datei-Auswahl-
 * Launcher, daher in {@code onCreate} der jeweiligen Activity instanzieren, nicht später.</p>
 */
public final class FullBackupRestoreFlow {

    private final AppCompatActivity activity;
    private final ActivityResultLauncher<String[]> restoreLauncher;

    public FullBackupRestoreFlow(AppCompatActivity activity) {
        this.activity = activity;
        this.restoreLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        doRestore(uri);
                    }
                });
    }

    /** Startet den Ablauf (Dateiauswahl). */
    public void start() {
        // Alles anbieten: je nach Gerät meldet der Dateianbieter ZIP mal als application/zip, mal als
        // octet-stream, und verschlüsselte Sicherungen (.abk) haben gar keinen bekannten Typ. Ob es eine
        // Sicherung ist, entscheidet ohnehin der Dateiinhalt (Kopf bzw. Manifest).
        restoreLauncher.launch(new String[]{"*/*"});
    }

    /**
     * Sicherung einlesen: bei Bedarf Passwort abfragen, dann fragen, was eingespielt werden soll (Daten,
     * Einstellungen oder beides) und erst danach die Sicherheitsabfrage stellen.
     */
    private void doRestore(Uri uri) {
        new Thread(() -> {
            try {
                byte[] data = readBytes(uri);
                if (BackupCrypto.isEncrypted(data)) {
                    Ui.post(activity, () -> askBackupPassword(data));
                    return;
                }
                openRestore(data);
            } catch (Exception e) {
                postRestoreError(e);
            }
        }).start();
    }

    /** Passwort der verschlüsselten Sicherung erfragen und die Datei damit öffnen. */
    private void askBackupPassword(byte[] data) {
        // Das Archiv bleibt hier liegen und geht nicht ins Bundle – siehe BackupRestoreController,
        // dieselbe Überlegung.
        this.archiv = data;
        de.spahr.ausgaben.ui.HostedDialog.show(activity, DLG_BACKUP_PASSWORD, null);
    }

    /** Schlüssel der Passwortabfrage – siehe {@code HostedDialog}. */
    public static final String DLG_BACKUP_PASSWORD = "dlg_fullBackupPassword";

    /** Das noch verschlüsselte Archiv, solange nach dem Passwort gefragt wird. */
    private byte[] archiv;

    /**
     * Die Dialoge dieses Ablaufs – die Maske, die ihn hält, leitet {@code buildDialog} hierher weiter.
     *
     * @return {@code null}, wenn der Schlüssel keiner von hier ist <b>oder</b> das Archiv nach einer
     *         Drehung nicht mehr vorliegt
     */
    public android.app.Dialog buildDialog(String key, android.os.Bundle args) {
        if (!DLG_BACKUP_PASSWORD.equals(key)) {
            return null;
        }
        if (archiv == null) {
            Toast.makeText(activity, R.string.restore_pick_again, Toast.LENGTH_LONG).show();
            return null;
        }
        return buildPasswordDialog(archiv);
    }

    private android.app.Dialog buildPasswordDialog(final byte[] data) {
        final TextInputEditText field = new TextInputEditText(activity);
        field.setHint(activity.getString(R.string.backup_password_hint));
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        int pad = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(field);
        return new AppDialog(activity)
                .setTitle(R.string.restore_password_title)
                .setView(box)
                .setPositiveButton(R.string.restore_db, (d, w) -> {
                    String pw = field.getText() == null ? "" : field.getText().toString();
                    new Thread(() -> {
                        byte[] plain;
                        try {
                            plain = BackupCrypto.decrypt(data, pw);
                        } catch (javax.crypto.BadPaddingException e) {
                            // Nur hier steht das Passwort tatsächlich in Frage – siehe
                            // BackupRestoreController, dieselbe Unterscheidung.
                            Ui.post(activity, () -> Toast.makeText(activity,
                                    R.string.restore_password_wrong, Toast.LENGTH_LONG).show());
                            return;
                        } catch (Exception e) {
                            postRestoreError(e);
                            return;
                        }
                        try {
                            openRestore(plain);
                        } catch (Exception e) {
                            postRestoreError(e);
                        }
                    }).start();
                })
                .create();
    }

    /** Archiv lesen und die Auswahl anbieten (läuft im Hintergrund-Thread). */
    private void openRestore(byte[] zip) throws Exception {
        final BackupArchive.Content content = BackupArchive.read(zip);
        if (!content.hasData() && !content.hasSettings()) {
            Ui.post(activity, () ->
                    Toast.makeText(activity, R.string.restore_invalid, Toast.LENGTH_LONG).show());
            return;
        }
        // Eine Profil-Sicherung (aus der Profil-Maske heraus erstellt) betrifft nur ein Profil – die
        // gehört dort auch wieder eingespielt, nicht hier, wo eine Komplettsicherung alle Profile ersetzt.
        // Das Wort „Profil" in der Meldung ist dabei kein Zufall: dort steht der passende Knopf.
        if (!content.isAllProfiles()) {
            Ui.post(activity, () -> Toast.makeText(activity,
                    R.string.restore_wrong_scope_use_profile, Toast.LENGTH_LONG).show());
            return;
        }
        Ui.post(activity, () -> chooseRestoreScope(content));
    }

    /** „Daten", „Einstellungen" oder „Beides" – nur was die Sicherung auch enthält. */
    private void chooseRestoreScope(BackupArchive.Content content) {
        final List<String> labels = new ArrayList<>();
        final List<Integer> scopes = new ArrayList<>();   // 0 = Daten, 1 = Einstellungen, 2 = beides
        if (content.hasData()) {
            labels.add(activity.getString(R.string.restore_what_data));
            scopes.add(0);
        }
        if (content.hasSettings()) {
            labels.add(activity.getString(R.string.restore_what_settings));
            scopes.add(1);
        }
        if (content.hasData() && content.hasSettings()) {
            labels.add(activity.getString(R.string.restore_what_both));
            scopes.add(2);
        }
        final int[] choice = {scopes.size() - 1};   // Vorgabe: der umfassendste Eintrag
        new AppDialog(activity)
                .setTitle(R.string.restore_what_title)
                .setSingleChoiceItems(labels.toArray(new String[0]), choice[0], (d, w) -> choice[0] = w)
                .setPositiveButton(R.string.restore_db, (d, w) ->
                        confirmAndRestore(content, scopes.get(choice[0])))
                .show();
    }

    private void confirmAndRestore(BackupArchive.Content content, int scope) {
        new AppDialog(activity)
                .setTitle(R.string.restore_confirm_title)
                .setMessage(R.string.restore_confirm_message)
                .setPositiveButton(R.string.restore_db, (d, w) -> applyRestore(content, scope))
                .show();
    }

    private void applyRestore(BackupArchive.Content content, int scope) {
        new Thread(() -> {
            try {
                if (scope == 0 || scope == 2) {
                    BackupStore.restoreAllData(activity, content);
                }
                if (scope == 1 || scope == 2) {
                    BackupStore.restoreAllSettings(activity, content);
                }
                Ui.post(activity, () -> {
                    Toast.makeText(activity, R.string.restore_done, Toast.LENGTH_LONG).show();
                    Intent i = new Intent(activity, MainActivity.class);
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    activity.startActivity(i);
                });
            } catch (Exception e) {
                postRestoreError(e);
            }
        }).start();
    }

    private void postRestoreError(Exception e) {
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        Ui.post(activity, () -> Toast.makeText(activity,
                activity.getString(R.string.restore_failed, msg), Toast.LENGTH_LONG).show());
    }

    private byte[] readBytes(Uri uri) throws Exception {
        try (InputStream is = activity.getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (is != null && (n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }
}
