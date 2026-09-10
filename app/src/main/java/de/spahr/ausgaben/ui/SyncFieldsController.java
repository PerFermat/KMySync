package de.spahr.ausgaben.ui;

import android.view.View;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.net.RemotePath;
import de.spahr.ausgaben.net.RemoteStorage;
import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Die Server-Felder einer Einrichtungsmaske: Serverart, Verbindungsprobe und die beiden
 * Ordner-Browser.
 *
 * <p>Denselben Block gibt es in zwei Masken — im Willkommen-Assistenten
 * ({@link OnboardingActivity}) und beim Ändern eines Profils ({@link ProfileSettingsActivity}) —, und
 * er stand dort zeichengleich zweimal. Die Felder tragen in beiden Layouts dieselben Kennungen; die
 * Klasse sucht sie sich deshalb selbst und braucht von der Maske nur die Einstellungen und den
 * SMB-Assistenten.</p>
 *
 * <p>Die gewählte Serverart gehört hierher und nicht in die Maske: sie entscheidet über die
 * Beschriftungen, über die sichtbaren Felder, über den Assistenten und über jede Verbindung, die von
 * hier ausgeht. Die Maske fragt sie beim Speichern über {@link #serverType()} ab.</p>
 */
final class SyncFieldsController {

    /** Schlüssel und Angaben der beiden Browser-Dialoge – siehe {@link HostedDialog}. */
    static final String DLG_KMY_PICK = "dlg_kmyPick";
    static final String DLG_FOLDER_PICK = "dlg_folderPick";
    private static final String ARG_FOLDER = "a_folder";
    private static final String ARG_FOLDERS = "a_folders";
    private static final String ARG_FILES = "a_files";
    private static final String ARG_TARGET_ID = "a_targetId";

    private final AppCompatActivity activity;
    private final SettingsStore settings;
    private final SmbWizardController smbWizard;

    private final MaterialAutoCompleteTextView editServerType;
    private final TextInputEditText editUrl;
    private final TextInputEditText editUser;
    private final TextInputEditText editPassword;
    private final TextInputEditText editKmyPath;
    private final TextInputLayout urlLayout;
    private final TextInputLayout userLayout;
    private final TextInputLayout passwordLayout;

    private String selectedServerType = SettingsStore.SERVER_NEXTCLOUD;

    SyncFieldsController(AppCompatActivity activity, SettingsStore settings,
                         SmbWizardController smbWizard) {
        this.activity = activity;
        this.settings = settings;
        this.smbWizard = smbWizard;
        this.editServerType = activity.findViewById(R.id.editServerType);
        this.editUrl = activity.findViewById(R.id.editUrl);
        this.editUser = activity.findViewById(R.id.editUser);
        this.editPassword = activity.findViewById(R.id.editPassword);
        this.editKmyPath = activity.findViewById(R.id.editKmyPath);
        this.urlLayout = activity.findViewById(R.id.urlLayout);
        this.userLayout = activity.findViewById(R.id.userLayout);
        this.passwordLayout = activity.findViewById(R.id.passwordLayout);
    }

    /** Die gewählte Serverart — Nextcloud, WebDAV oder SMB. */
    String serverType() {
        return selectedServerType;
    }

    // ---- Serverart ----

    void setupServerType() {
        String ncLabel = activity.getString(R.string.server_type_nextcloud);
        String davLabel = activity.getString(R.string.server_type_webdav);
        String smbLabel = activity.getString(R.string.server_type_smb);
        PickerAdapters.plain(editServerType, Arrays.asList(ncLabel, davLabel, smbLabel));
        selectedServerType = settings.getServerType();
        editServerType.setText(labelForServerType(ncLabel, davLabel, smbLabel), false);
        applyServerTypeHints();
        editServerType.setOnItemClickListener((parent, view, position, id) -> {
            selectedServerType = position == 1 ? SettingsStore.SERVER_WEBDAV
                    : position == 2 ? SettingsStore.SERVER_SMB : SettingsStore.SERVER_NEXTCLOUD;
            settings.setServerType(selectedServerType);
            applyServerTypeHints();
        });
    }

    private String labelForServerType(String nc, String dav, String smb) {
        if (SettingsStore.SERVER_WEBDAV.equals(selectedServerType)) {
            return dav;
        }
        if (SettingsStore.SERVER_SMB.equals(selectedServerType)) {
            return smb;
        }
        return nc;
    }

    /** Wie in den Einstellungen: bei SMB übernimmt der Assistent die Felder URL/Benutzer/Passwort. */
    void applyServerTypeHints() {
        boolean smb = SettingsStore.SERVER_SMB.equals(selectedServerType);
        urlLayout.setHint(activity.getString(smb ? R.string.smb_url_hint : R.string.nextcloud_url_hint));
        userLayout.setHint(activity.getString(smb ? R.string.smb_user_hint : R.string.nextcloud_user_hint));
        if (!smb) {
            smbWizard.resetManual();
        }
        boolean wizard = smb && !smbWizard.isManual();
        int fields = wizard ? View.GONE : View.VISIBLE;
        urlLayout.setVisibility(fields);
        userLayout.setVisibility(fields);
        passwordLayout.setVisibility(fields);
        // „Verbindung testen" gehört zu den Eingabefeldern und ist nur verdeckt, solange der
        // SMB-Assistent sie für sich hat; er hat seine eigene Probe.
        activity.findViewById(R.id.btnTestConnection).setVisibility(fields);
        // Rückweg zum Assistenten nur, solange SMB gewählt und gerade manuell eingegeben wird.
        activity.findViewById(R.id.btnSmbSearch).setVisibility(smb && !wizard ? View.VISIBLE : View.GONE);
        smbWizard.setVisible(wizard);
    }

    // ---- Verbindung ----

    // „Verbindung testen" liegt in der Maske (siehe {@code DiagnosticsDialog}): Geprüft wird der
    // Ordner, in den die App wirklich schreibt, und den kennt erst die Maske – im .kmy-Modus der
    // Ordner der Datei, im CSV-Modus der Export-Ordner.

    /** Leeres Passwortfeld heißt: das gespeicherte gilt weiter. */
    private String passwordOrSaved() {
        String pw = textOf(editPassword);
        return pw.isEmpty() ? settings.getPassword() : pw;
    }

    /**
     * Der Grund eines gescheiterten Serverzugriffs in Worten. Bei SMB kommt er aus
     * {@code SmbErrors}: dort steht hinter einem nackten Statuscode eine Erklärung, mit der sich etwas
     * anfangen lässt. Die Maske braucht das auch für ihre eigenen Zugriffe (Export, Import) und fragt
     * deshalb hier nach — die Serverart, von der die Wahl abhängt, liegt hier.
     */
    String serverError(Exception e) {
        if (SettingsStore.SERVER_SMB.equals(selectedServerType)) {
            return de.spahr.ausgaben.net.smb.SmbErrors.messageFor(activity,
                    de.spahr.ausgaben.net.smb.SmbErrors.Step.FOLDER, e);
        }
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    // ---- Ordner durchsehen ----

    /** Der .kmy-Browser, ausgehend vom Ordner des aktuell eingetragenen Pfades. */
    void browseKmy() {
        browseKmyAt(RemotePath.folderOf(textOf(editKmyPath)));
    }

    private void browseKmyAt(String folder) {
        final String serverType = selectedServerType;
        final String url = textOf(editUrl);
        final String user = textOf(editUser);
        final String password = passwordOrSaved();
        toast(R.string.loading_files, Toast.LENGTH_SHORT);
        new Thread(() -> {
            try {
                // Ordner und Dateien in einem Aufruf: SMB meldet sich sonst zweimal hintereinander an.
                RemoteStorage.Entries entries = RemoteStorage.from(serverType, url, user, password)
                        .listEntries(folder, "kmy");
                List<String> folders = entries.folders;
                List<String> files = entries.files;
                Collections.sort(folders, String.CASE_INSENSITIVE_ORDER);
                Collections.sort(files, String.CASE_INSENSITIVE_ORDER);
                imVordergrund(() -> {
                    if (folder.isEmpty() && folders.isEmpty() && files.isEmpty()) {
                        toast(R.string.kmy_browse_none, Toast.LENGTH_LONG);
                    } else {
                        showKmyPick(folder, folders, files);
                    }
                });
            } catch (Exception e) {
                meldeFehler(e);
            }
        }).start();
    }

    private void showKmyPick(String folder, List<String> folders, List<String> files) {
        Bundle args = new Bundle();
        args.putString(ARG_FOLDER, folder);
        args.putStringArray(ARG_FOLDERS, folders.toArray(new String[0]));
        args.putStringArray(ARG_FILES, files.toArray(new String[0]));
        HostedDialog.show(activity, DLG_KMY_PICK, args);
    }

    /**
     * Baut den Datei-Browser aus dem, was im Bundle steht — beim ersten Mal und nach jeder Drehung.
     *
     * <p>Der Serverzugriff bleibt dabei aus: Die Liste dieses Ordners steht schon in den Angaben, und
     * erst ein Tipp auf einen Unterordner holt die nächste. Bis 1.12 war der Dialog nach der Drehung
     * weg und der ganze Weg durch die Ordner von vorn zu gehen.</p>
     */
    private android.app.Dialog buildKmyPick(Bundle args) {
        final String folder = args.getString(ARG_FOLDER, "");
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        if (!folder.isEmpty()) {
            labels.add("↑  ..");
            actions.add(() -> browseKmyAt(RemotePath.parentFolder(folder)));
        }
        for (String d : args.getStringArray(ARG_FOLDERS)) {
            labels.add("📁  " + d);
            final String target = folder.isEmpty() ? d : folder + "/" + d;
            actions.add(() -> browseKmyAt(target));
        }
        for (String f : args.getStringArray(ARG_FILES)) {
            labels.add(f);
            final String path = folder.isEmpty() ? f : folder + "/" + f;
            actions.add(() -> editKmyPath.setText(path));
        }
        String title = folder.isEmpty() ? activity.getString(R.string.kmy_browse) : "/" + folder;
        return new AppDialog(activity)
                .setTitle(title)
                .setItems(labels.toArray(new String[0]), (d, w) -> actions.get(w).run())
                .create();
    }

    /**
     * Navigierbarer Ordner-Dialog (nur Ordner) für die CSV-Export-/Import-Ordner. Nutzt – wie der
     * kmy-Browser – {@link RemoteStorage} mit den aktuell eingegebenen Zugangsdaten, gilt also für
     * Nextcloud, WebDAV und SMB.
     */
    void browseFolderInto(TextInputEditText target) {
        browseFolderAt(textOf(target), target);
    }

    private void browseFolderAt(String folder, TextInputEditText target) {
        final String serverType = selectedServerType;
        final String url = textOf(editUrl);
        final String user = textOf(editUser);
        final String password = passwordOrSaved();
        toast(R.string.loading_files, Toast.LENGTH_SHORT);
        new Thread(() -> {
            try {
                RemoteStorage storage = RemoteStorage.from(serverType, url, user, password);
                List<String> folders = storage.listFolders(folder);
                Collections.sort(folders, String.CASE_INSENSITIVE_ORDER);
                imVordergrund(() -> showFolderPick(folder, folders, target));
            } catch (Exception e) {
                meldeFehler(e);
            }
        }).start();
    }

    private void showFolderPick(String folder, List<String> folders, TextInputEditText target) {
        Bundle args = new Bundle();
        args.putString(ARG_FOLDER, folder);
        args.putStringArray(ARG_FOLDERS, folders.toArray(new String[0]));
        // Nicht das Feld selbst, sondern seine Kennung: nach einer Drehung gibt es das alte nicht mehr,
        // die neue Maske hat aber eines mit derselben id.
        args.putInt(ARG_TARGET_ID, target.getId());
        HostedDialog.show(activity, DLG_FOLDER_PICK, args);
    }

    /** Wie {@link #buildKmyPick}, nur ohne Dateien — und mit dem Zielfeld über seine id. */
    private android.app.Dialog buildFolderPick(Bundle args) {
        final String folder = args.getString(ARG_FOLDER, "");
        final TextInputEditText target = activity.findViewById(args.getInt(ARG_TARGET_ID));
        if (target == null) {
            return null;
        }
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        labels.add(activity.getString(R.string.folder_choose_this));
        actions.add(() -> target.setText(folder));
        if (!folder.isEmpty()) {
            labels.add("↑  ..");
            actions.add(() -> browseFolderAt(RemotePath.parentFolder(folder), target));
        }
        for (String d : args.getStringArray(ARG_FOLDERS)) {
            labels.add("📁  " + d);
            final String next = folder.isEmpty() ? d : folder + "/" + d;
            actions.add(() -> browseFolderAt(next, target));
        }
        String title = folder.isEmpty() ? activity.getString(R.string.folder_browse) : "/" + folder;
        return new AppDialog(activity)
                .setTitle(title)
                .setItems(labels.toArray(new String[0]), (d, w) -> actions.get(w).run())
                .create();
    }

    /**
     * Die Dialoge dieses Reglers – die Maske, die ihn hält, leitet {@code buildDialog} hierher weiter
     * (siehe {@link HostedDialog}).
     *
     * @return {@code null}, wenn der Schlüssel keiner von hier ist
     */
    android.app.Dialog buildDialog(String key, Bundle args) {
        if (DLG_KMY_PICK.equals(key)) {
            return buildKmyPick(args);
        }
        if (DLG_FOLDER_PICK.equals(key)) {
            return buildFolderPick(args);
        }
        return null;
    }

    // ---- Kleinkram ----

    /**
     * Ergebnis eines Serverzugriffs anzeigen — aber nur, solange es die Maske noch gibt. Jeder dieser
     * Zugriffe läuft in einem eigenen Faden, und ein Server, der nicht antwortet, kommt erst nach
     * seiner Zeitgrenze zurück; bis dahin kann die Maske längst geschlossen sein. Ein Dialog auf ein
     * Fenster, das es nicht mehr gibt, beendet die App.
     */
    private void imVordergrund(Runnable r) {
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            r.run();
        });
    }

    private void meldeFehler(Exception e) {
        final String msg = serverError(e);
        imVordergrund(() -> Toast.makeText(activity,
                activity.getString(R.string.conn_failed, msg), Toast.LENGTH_LONG).show());
    }

    private void toast(int text, int length) {
        Toast.makeText(activity, text, length).show();
    }

    private String textOf(TextInputEditText field) {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }
}
