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
    /** Nur beim lokalen Ordner: öffnet den Android-Ordnerdialog. */
    private final com.google.android.material.button.MaterialButton btnLocalFolder;
    private final androidx.activity.result.ActivityResultLauncher<android.net.Uri> localFolderPicker;

    private String selectedServerType = SettingsStore.SERVER_NEXTCLOUD;
    /** Die Dateiauswahl gilt einer KMyMoney-Datenbank: dann alle Dateien zeigen, nicht nur „.kmy". */
    private boolean database;

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
        this.btnLocalFolder = activity.findViewById(R.id.btnLocalFolder);
        // Muss vor STARTED registriert sein – die Masken bauen diesen Regler in onCreate.
        this.localFolderPicker = activity.registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
                this::onLocalFolderChosen);
        btnLocalFolder.setOnClickListener(v -> localFolderPicker.launch(null));
        // Die Beschriftung folgt dem (versteckten) Adressfeld: Die Profileinstellungen füllen es erst
        // nach der Wahl der Serverart, und der Knopf zeigte sonst „Ordner wählen…" statt des Ordners.
        editUrl.addTextChangedListener(new SimpleWatcher(() -> {
            if (btnLocalFolder.getVisibility() == View.VISIBLE) {
                updateLocalFolderLabel();
            }
        }));
        // Der Assistent entscheidet über seine Schritte selbst; was daneben sichtbar ist, gehört
        // hierher. Deshalb meldet er nur, daß sich etwas geändert hat.
        smbWizard.setOnStepChanged(this::applyServerTypeHints);
    }

    /** Die gewählte Serverart — Nextcloud, WebDAV, SMB oder ein lokaler Ordner. */
    String serverType() {
        return selectedServerType;
    }

    // ---- Serverart ----

    void setupServerType() {
        String ncLabel = activity.getString(R.string.server_type_nextcloud);
        String davLabel = activity.getString(R.string.server_type_webdav);
        String smbLabel = activity.getString(R.string.server_type_smb);
        String localLabel = activity.getString(R.string.server_type_local);
        PickerAdapters.plain(editServerType, Arrays.asList(ncLabel, davLabel, smbLabel, localLabel));
        selectedServerType = settings.getServerType();
        editServerType.setText(labelForServerType(ncLabel, davLabel, smbLabel, localLabel), false);
        applyServerTypeHints();
        editServerType.setOnItemClickListener((parent, view, position, id) -> {
            String vorher = selectedServerType;
            selectedServerType = position == 1 ? SettingsStore.SERVER_WEBDAV
                    : position == 2 ? SettingsStore.SERVER_SMB
                    : position == 3 ? SettingsStore.SERVER_LOCAL : SettingsStore.SERVER_NEXTCLOUD;
            // Eine Server-Adresse ist kein Ordner und umgekehrt: beim Wechsel von oder zu „lokal"
            // gehört ins Adressfeld nur, was zur neuen Art passt – die gespeicherte Adresse, wenn sie
            // dazu passt (wer hin- und zurückwechselt, findet seinen Ordner wieder), sonst nichts.
            boolean lokalVorher = SettingsStore.SERVER_LOCAL.equals(vorher);
            boolean lokalJetzt = SettingsStore.SERVER_LOCAL.equals(selectedServerType);
            if (lokalVorher != lokalJetzt) {
                String gespeichert = settings.getUrl();
                boolean gespeichertLokal = gespeichert.startsWith("content://");
                editUrl.setText(gespeichertLokal == lokalJetzt ? gespeichert : "");
            }
            // Gespeichert wird erst mit der Maske – „Zurück" verwirft die Wahl wie die übrigen Felder.
            applyServerTypeHints();
        });
    }

    private String labelForServerType(String nc, String dav, String smb, String local) {
        if (SettingsStore.SERVER_WEBDAV.equals(selectedServerType)) {
            return dav;
        }
        if (SettingsStore.SERVER_SMB.equals(selectedServerType)) {
            return smb;
        }
        if (SettingsStore.SERVER_LOCAL.equals(selectedServerType)) {
            return local;
        }
        return nc;
    }

    /**
     * Der Ordner aus dem Android-Dialog: Zugriff dauerhaft sichern und als „Adresse" eintragen. Das
     * Adressfeld ist dabei unsichtbar – gespeichert wird es mit der Maske wie jede Server-Adresse.
     */
    private void onLocalFolderChosen(android.net.Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            activity.getContentResolver().takePersistableUriPermission(uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            toast(R.string.local_folder_none, Toast.LENGTH_LONG);
            return;
        }
        editUrl.setText(uri.toString());
        updateLocalFolderLabel();
    }

    /** Beschriftet den Ordner-Knopf mit dem Namen des gewählten Ordners. */
    private void updateLocalFolderLabel() {
        String uri = Ui.trimmedText(editUrl);
        String name = de.spahr.ausgaben.net.LocalStorage.displayName(activity, uri);
        if (uri.isEmpty()) {
            btnLocalFolder.setText(R.string.local_folder_choose);
        } else {
            btnLocalFolder.setText(activity.getString(R.string.local_folder_chosen,
                    name.isEmpty() ? uri : name));
        }
    }

    /** Wie in den Einstellungen: bei SMB übernimmt der Assistent die Felder URL/Benutzer/Passwort. */
    void applyServerTypeHints() {
        if (SettingsStore.SERVER_LOCAL.equals(selectedServerType)) {
            // Ein Ordner auf dem Gerät: keine Adresse, kein Benutzer, kein Passwort, kein Assistent –
            // nur der Ordnerdialog. „Verbindung testen" prüft dann Freigabe, Lesen und Schreiben.
            smbWizard.resetManual();
            smbWizard.setVisible(false);
            urlLayout.setVisibility(View.GONE);
            userLayout.setVisibility(View.GONE);
            passwordLayout.setVisibility(View.GONE);
            activity.findViewById(R.id.btnSmbSearch).setVisibility(View.GONE);
            activity.findViewById(R.id.btnTestConnection).setVisibility(View.VISIBLE);
            btnLocalFolder.setVisibility(View.VISIBLE);
            updateLocalFolderLabel();
            return;
        }
        btnLocalFolder.setVisibility(View.GONE);
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
        // „Verbindung testen" braucht Zugangsdaten, sonst gibt es nichts zu prüfen: bei manueller
        // Eingabe stehen sie in den Feldern, im Assistenten erst an seinem Schluß. Gerade dort ist der
        // Knopf wichtig – der Assistent hat sich zwar angemeldet und Freigaben gelesen, über den
        // Zielordner und das Schreibrecht darin sagt das aber nichts.
        activity.findViewById(R.id.btnTestConnection)
                .setVisibility(!wizard || smbWizard.isDone() ? View.VISIBLE : View.GONE);
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
        String pw = Ui.trimmedText(editPassword);
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
        browseKmyAt(RemotePath.folderOf(Ui.trimmedText(editKmyPath)));
    }

    /** Gilt die Dateiauswahl einer KMyMoney-Datenbank? Siehe {@link ExportFormat}. */
    void setDatabase(boolean database) {
        this.database = database;
    }

    private void browseKmyAt(String folder) {
        final boolean alle = database;
        final String serverType = selectedServerType;
        final String url = Ui.trimmedText(editUrl);
        final String user = Ui.trimmedText(editUser);
        final String password = passwordOrSaved();
        toast(R.string.loading_files, Toast.LENGTH_SHORT);
        new Thread(() -> {
            try {
                // Ordner und Dateien in einem Aufruf: SMB meldet sich sonst zweimal hintereinander an.
                RemoteStorage storage = RemoteStorage.from(activity, serverType, url, user, password);
                List<String> folders;
                List<String> files;
                if (alle) {
                    // Eine KMyMoney-Datenbank kann heißen, wie sie will – KMyMoney prüft die Endung
                    // auch nicht. Also den ganzen Ordner zeigen.
                    folders = storage.listFolders(folder);
                    files = new java.util.ArrayList<>(storage.listAllFiles(folder));
                } else {
                    RemoteStorage.Entries entries = storage.listEntries(folder, "kmy");
                    folders = entries.folders;
                    files = entries.files;
                }
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
        browseFolderAt(Ui.trimmedText(target), target);
    }

    private void browseFolderAt(String folder, TextInputEditText target) {
        final String serverType = selectedServerType;
        final String url = Ui.trimmedText(editUrl);
        final String user = Ui.trimmedText(editUser);
        final String password = passwordOrSaved();
        toast(R.string.loading_files, Toast.LENGTH_SHORT);
        new Thread(() -> {
            try {
                RemoteStorage storage = RemoteStorage.from(activity, serverType, url, user, password);
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
        Ui.post(activity, () -> {
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

}
