package de.spahr.ausgaben.ui;

import android.content.Intent;
import android.net.Uri;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.backup.BackupArchive;
import de.spahr.ausgaben.backup.BackupCrypto;
import de.spahr.ausgaben.backup.BackupStore;
import de.spahr.ausgaben.util.UriBytes;

/**
 * Eine Sicherung einspielen: von der gewählten Datei bis zum Neustart der App.
 *
 * <p>Der Ablauf ist an zwei Stellen erreichbar — im Willkommen-Assistenten
 * ({@link OnboardingActivity}) und beim Ändern eines Profils ({@link ProfileSettingsActivity}) —, und er
 * stand dort zeichengleich zweimal: rund 170 Zeilen mit fünf Dialogen und vier Hintergrundfäden. Das war
 * nicht nur doppelte Pflege, sondern doppelte Fehlersuche: jeder Fund an dieser Kette musste zweimal
 * behoben werden, und die Fassungen begannen bereits auseinanderzulaufen.</p>
 *
 * <p>Die Auswahl der Datei bleibt draußen: ein {@code ActivityResultLauncher} muss beim Aufbau der
 * Activity angemeldet werden und lässt sich nicht herumreichen. Was er liefert, geht hier hinein
 * ({@link #restore(Uri)}).</p>
 */
final class BackupRestoreController {

    /** Schlüssel der Passwortabfrage – siehe {@link HostedDialog}. */
    static final String DLG_BACKUP_PASSWORD = "dlg_backupPassword";

    private final AppCompatActivity activity;

    BackupRestoreController(AppCompatActivity activity) {
        this.activity = activity;
    }

    /**
     * Der Einstieg: die gewählte Datei einlesen, bei einer verschlüsselten zuerst nach dem Passwort
     * fragen. Das Lesen läuft im Hintergrund — die Datei kann in der Cloud liegen.
     */
    void restore(Uri uri) {
        new Thread(() -> {
            try {
                byte[] data = UriBytes.read(activity, uri);
                if (BackupCrypto.isEncrypted(data)) {
                    imVordergrund(() -> askBackupPassword(data));
                    return;
                }
                openRestore(data);
            } catch (Exception e) {
                postRestoreError(e);
            }
        }).start();
    }

    /**
     * Etwas auf dem Bedienfaden tun — aber nur, solange es die Maske noch gibt.
     *
     * <p>Jeder Schritt dieser Kette kommt aus dem Hintergrund zurück, und dazwischen kann der Nutzer
     * weggegangen oder das Gerät gedreht worden sein. Der Name bleibt, weil er an dieser Kette gut
     * liest; die Prüfung selbst steht inzwischen in {@link Ui#post} — dort gilt sie für die ganze App
     * und nicht mehr nur für diesen Regler.</p>
     */
    private void imVordergrund(Runnable r) {
        Ui.post(activity, r);
    }

    private void askBackupPassword(byte[] data) {
        // Das Archiv bleibt hier liegen: es gehört nicht in ein Bundle, dort ist bei rund einem
        // Megabyte Schluss und eine Sicherung sprengt das. Nach einer Drehung ist es weg – dann kann
        // der Dialog nicht mehr gebaut werden, und die Datei ist neu zu wählen (siehe buildDialog).
        this.archiv = data;
        HostedDialog.show(activity, DLG_BACKUP_PASSWORD, null);
    }

    /** Das noch verschlüsselte Archiv, solange nach dem Passwort gefragt wird. */
    private byte[] archiv;

    /**
     * Die Dialoge dieses Reglers – die Maske, die ihn hält, leitet {@code buildDialog} hierher weiter.
     *
     * @return {@code null}, wenn der Schlüssel keiner von hier ist <b>oder</b> das Archiv nach einer
     *         Drehung nicht mehr vorliegt
     */
    android.app.Dialog buildDialog(String key, android.os.Bundle args) {
        if (!DLG_BACKUP_PASSWORD.equals(key)) {
            return null;
        }
        if (archiv == null) {
            // Die Frage ohne das Archiv dahinter wäre eine Falle: Man tippt das Passwort ein und
            // nichts geschieht. Lieber sagen, dass die Datei neu zu wählen ist.
            toast(R.string.restore_pick_again);
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
                            // Nur hier steht das Passwort tatsächlich in Frage: GCM prüft beim
                            // Entschlüsseln seinen Authentifizierungs-Tag, und der schlägt fehl, wenn
                            // der Schlüssel nicht passt.
                            imVordergrund(() -> toast(R.string.restore_password_wrong));
                            return;
                        } catch (Exception e) {
                            // Beschädigtes Archiv, fehlender Algorithmus, zu wenig Speicher: alles
                            // andere als ein falsches Passwort, und es hilft niemandem, es so zu
                            // nennen.
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

    /**
     * Eine Profil-Sicherung geht direkt in die Daten/Einstellungen-Auswahl; eine Alle-Profile-Sicherung
     * fragt zuerst, welches der darin enthaltenen Profile das aktive ersetzen soll (siehe
     * {@link BackupStore#restoreProfileFromAllBackup}).
     */
    private void openRestore(byte[] zip) throws Exception {
        final BackupArchive.Content content = BackupArchive.read(zip);
        if (!content.hasData() && !content.hasSettings()) {
            imVordergrund(() -> toast(R.string.restore_invalid));
            return;
        }
        if (content.isAllProfiles()) {
            imVordergrund(() -> pickProfileFromBackup(content));
        } else {
            imVordergrund(() -> chooseRestoreScope(content));
        }
    }

    private void pickProfileFromBackup(BackupArchive.Content content) {
        List<String[]> profiles;
        try {
            profiles = BackupStore.profilesInBackup(content);
        } catch (JSONException e) {
            toast(R.string.restore_invalid);
            return;
        }
        if (profiles.isEmpty()) {
            toast(R.string.restore_invalid);
            return;
        }
        String[] labels = new String[profiles.size()];
        for (int i = 0; i < profiles.size(); i++) {
            labels[i] = profiles.get(i)[1];
        }
        new AppDialog(activity)
                .setTitle(R.string.restore_pick_profile_title)
                .setItems(labels, (d, w) -> confirmRestoreFromAllBackup(content, profiles.get(w)[0]))
                .show();
    }

    private void confirmRestoreFromAllBackup(BackupArchive.Content content, String sourceProfileId) {
        new AppDialog(activity)
                .setTitle(R.string.restore_confirm_title)
                .setMessage(R.string.restore_confirm_message)
                .setPositiveButton(R.string.restore_db, (d, w) -> new Thread(() -> {
                    try {
                        BackupStore.restoreProfileFromAllBackup(activity, content, sourceProfileId);
                        postRestoreDone();
                    } catch (Exception e) {
                        postRestoreError(e);
                    }
                }).start())
                .show();
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
                    BackupStore.restoreProfileData(activity, content.db);
                }
                if (scope == 1 || scope == 2) {
                    BackupStore.restoreProfileSettings(activity, content);
                }
                postRestoreDone();
            } catch (Exception e) {
                postRestoreError(e);
            }
        }).start();
    }

    /**
     * Nach einer Wiederherstellung ist der Activity-Stack auf eine frische MainActivity zu setzen: die
     * Datenbank wurde ersetzt, und jede darüberliegende Maske hielte noch Werte der alten.
     *
     * <p>Das läuft <b>ohne</b> die Prüfung aus {@link #imVordergrund}: hier wird kein Dialog mehr
     * gezeigt, sondern die App neu aufgesetzt. Das muss auch dann geschehen, wenn der Nutzer inzwischen
     * weggetippt hat — sonst bliebe er in einer Maske mit den Daten von vorhin.</p>
     */
    private void postRestoreDone() {
        activity.runOnUiThread(() -> {
            Toast.makeText(activity, R.string.restore_done, Toast.LENGTH_LONG).show();
            Intent i = new Intent(activity, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity.startActivity(i);
        });
    }

    private void postRestoreError(Exception e) {
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        imVordergrund(() -> Toast.makeText(activity,
                activity.getString(R.string.restore_failed, msg), Toast.LENGTH_LONG).show());
    }

    private void toast(int text) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
    }
}
