package de.spahr.ausgaben.net;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ein Ordner auf dem Gerät als Sync-Ziel – neben Nextcloud, WebDAV und SMB die vierte Art.
 *
 * <p>Der Nutzer gibt den Ordner über den Android-Ordnerdialog frei ({@code ACTION_OPEN_DOCUMENT_TREE});
 * gespeichert wird die Baum-Adresse ({@code content://…/tree/…}), die App hält die Berechtigung dauerhaft.
 * Damit lässt sich jeder Ordner nutzen, den ein Dateianbieter zeigt: der interne Speicher, eine
 * SD-Karte oder der Ordner einer Sync-App wie Syncthing oder FolderSync, die die Datei mit dem Rechner
 * abgleicht, auf dem KMyMoney läuft.</p>
 *
 * <p>Alles läuft über {@link DocumentsContract} und damit über AOSP – keine Bibliothek, keine
 * Google-Dienste. {@code folder} ist wie bei den Servern relativ zur Wurzel, Teile mit „/“ getrennt.</p>
 *
 * <p><b>Namen:</b> Manche Anbieter ändern beim Anlegen oder Umbenennen den Namen (hängen eine Endung
 * an oder machen ihn eindeutig). Für die KMyMoney-Datei wäre das fatal – also wird jeder Name danach
 * geprüft, und weicht er ab, scheitert der Zugriff mit einer verständlichen Meldung statt still eine
 * Datei falschen Namens zu hinterlassen.</p>
 */
public class LocalStorage implements RemoteStorage {

    /** Ohne Endung, damit der Anbieter keine anhängt. */
    private static final String MIME_BINARY = "application/octet-stream";

    private final ContentResolver resolver;
    private final Uri tree;

    public LocalStorage(Context context, String treeUri) throws IOException {
        if (context == null || treeUri == null || treeUri.trim().isEmpty()) {
            throw new IOException(Diagnostics.t("Kein lokaler Ordner gewählt.",
                    "No local folder selected."));
        }
        this.resolver = context.getApplicationContext().getContentResolver();
        this.tree = Uri.parse(treeUri.trim());
    }

    /** Ein Eintrag eines Ordners. */
    private static final class Child {
        final String id;
        final String name;
        final boolean folder;
        final long modified;
        final long size;

        Child(String id, String name, boolean folder, long modified, long size) {
            this.id = id;
            this.name = name;
            this.folder = folder;
            this.modified = modified;
            this.size = size;
        }

        String version() {
            return modified + ":" + size;
        }
    }

    // ---- Pfade ----

    private String rootId() throws IOException {
        try {
            return DocumentsContract.getTreeDocumentId(tree);
        } catch (IllegalArgumentException e) {
            throw new IOException(Diagnostics.t("Die gespeicherte Ordneradresse ist ungültig – bitte den "
                    + "Ordner neu wählen.", "The saved folder address is invalid – please choose the "
                    + "folder again."), e);
        }
    }

    private Uri docUri(String id) {
        return DocumentsContract.buildDocumentUriUsingTree(tree, id);
    }

    private List<Child> children(String parentId) throws IOException {
        Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] cols = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE};
        List<Child> out = new ArrayList<>();
        // Die Form mit Bundle (ab Android 8): Dokumentanbieter verstehen nur sie zuverlässig.
        try (Cursor c = resolver.query(uri, cols, null, null)) {
            if (c == null) {
                throw new IOException(Diagnostics.t("Der Ordner lässt sich nicht lesen.",
                        "The folder cannot be read."));
            }
            while (c.moveToNext()) {
                out.add(new Child(c.getString(0), c.getString(1),
                        Document.MIME_TYPE_DIR.equals(c.getString(2)),
                        c.isNull(3) ? 0 : c.getLong(3), c.isNull(4) ? -1 : c.getLong(4)));
            }
        } catch (SecurityException | IllegalArgumentException e) {
            throw noAccess(e);
        }
        return out;
    }

    private static IOException noAccess(Exception e) {
        return new IOException(Diagnostics.t(
                "Kein Zugriff mehr auf den lokalen Ordner – bitte in den Profileinstellungen neu wählen.",
                "No access to the local folder any more – please choose it again in the profile "
                        + "settings."), e);
    }

    private static List<String> parts(String folder) {
        List<String> out = new ArrayList<>();
        if (folder != null) {
            for (String p : folder.split("/")) {
                if (!p.trim().isEmpty()) {
                    out.add(p.trim());
                }
            }
        }
        return out;
    }

    private static Child named(List<Child> list, String name) {
        for (Child c : list) {
            if (c.name != null && c.name.equals(name)) {
                return c;
            }
        }
        return null;
    }

    /** Kennung des Ordners; {@code null}, wenn es ihn nicht gibt und {@code create} aus ist. */
    private String folderId(String folder, boolean create) throws IOException {
        String id = rootId();
        for (String part : parts(folder)) {
            Child next = named(children(id), part);
            if (next != null && next.folder) {
                id = next.id;
                continue;
            }
            if (next != null || !create) {
                return null;
            }
            id = createChecked(id, Document.MIME_TYPE_DIR, part);
        }
        return id;
    }

    private String requireFolder(String folder) throws IOException {
        String id = folderId(folder, false);
        if (id == null) {
            throw new FileNotFoundException(Diagnostics.t("Ordner nicht gefunden: ",
                    "Folder not found: ") + folder);
        }
        return id;
    }

    private Child file(String folder, String name) throws IOException {
        String id = folderId(folder, false);
        if (id == null) {
            return null;
        }
        Child c = named(children(id), name);
        return c != null && !c.folder ? c : null;
    }

    private Child requireFile(String folder, String name) throws IOException {
        Child c = file(folder, name);
        if (c == null) {
            throw new FileNotFoundException(Diagnostics.t("Datei nicht gefunden: ",
                    "File not found: ") + RemotePath.join(folder, name));
        }
        return c;
    }

    /** Legt an und prüft, dass der Anbieter den Namen nicht verändert hat. */
    private String createChecked(String parentId, String mime, String name) throws IOException {
        Uri created;
        try {
            created = DocumentsContract.createDocument(resolver, docUri(parentId), mime, name);
        } catch (SecurityException | IllegalArgumentException e) {
            throw noAccess(e);
        }
        if (created == null) {
            throw new IOException(Diagnostics.t("Anlegen fehlgeschlagen: ", "Could not create: ") + name);
        }
        String id = DocumentsContract.getDocumentId(created);
        checkName(parentId, id, name);
        return id;
    }

    private void checkName(String parentId, String id, String expected) throws IOException {
        for (Child c : children(parentId)) {
            if (c.id.equals(id)) {
                if (!expected.equals(c.name)) {
                    throw new IOException(Diagnostics.t(
                            "Der Speicher hat den Dateinamen geändert („" + expected + "“ → „" + c.name
                                    + "“). Dieser Ordner eignet sich nicht als Sync-Ziel.",
                            "The storage changed the file name (\"" + expected + "\" → \"" + c.name
                                    + "\"). This folder cannot be used as a sync target."));
                }
                return;
            }
        }
    }

    // ---- Schreiben ----

    @Override
    public void uploadText(String folder, String fileName, String content) throws IOException {
        uploadBytes(folder, fileName, content.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void uploadBytes(String folder, String fileName, byte[] content) throws IOException {
        uploadBytes(folder, fileName, content, "");
    }

    @Override
    public void uploadBytes(String folder, String fileName, byte[] content, String expectedVersion)
            throws IOException {
        String parent = folderId(folder, true);
        Child existing = named(children(parent), fileName);
        if (existing != null && existing.folder) {
            throw new IOException(Diagnostics.t("Dort liegt ein Ordner: ", "A folder is in the way: ")
                    + fileName);
        }
        if (expectedVersion != null && !expectedVersion.isEmpty()) {
            String now = existing == null ? "" : existing.version();
            if (!expectedVersion.equals(now)) {
                throw new RemoteConflictException(Diagnostics.t(
                        "Die Datei wurde inzwischen geändert: ", "The file has been changed meanwhile: ")
                        + fileName);
            }
        }
        String id = existing != null ? existing.id : createChecked(parent, MIME_BINARY, fileName);
        write(docUri(id), content);
    }

    private void write(Uri uri, byte[] content) throws IOException {
        OutputStream out;
        try {
            // „wt" kürzt eine vorhandene Datei; nicht jeder Anbieter kennt es.
            out = openOut(uri, "wt");
        } catch (FileNotFoundException | IllegalArgumentException e) {
            out = openOut(uri, "w");
        }
        try (OutputStream o = out) {
            o.write(content);
            o.flush();
        }
    }

    private OutputStream openOut(Uri uri, String mode) throws FileNotFoundException {
        try {
            OutputStream out = resolver.openOutputStream(uri, mode);
            if (out == null) {
                throw new FileNotFoundException(uri.toString());
            }
            return out;
        } catch (SecurityException e) {
            FileNotFoundException f = new FileNotFoundException(noAccess(e).getMessage());
            f.initCause(e);
            throw f;
        }
    }

    @Override
    public void ensureFolder(String folder) throws IOException {
        folderId(folder, true);
    }

    @Override
    public void delete(String folder, String fileName) throws IOException {
        Child c = file(folder, fileName);
        if (c == null) {
            return;
        }
        try {
            DocumentsContract.deleteDocument(resolver, docUri(c.id));
        } catch (SecurityException | IllegalArgumentException e) {
            throw noAccess(e);
        }
    }

    @Override
    public void move(String fromFolder, String fromName, String toFolder, String toName)
            throws IOException {
        Child target = file(toFolder, toName);
        if (target != null) {
            // move ersetzt ein vorhandenes Ziel – wie bei den Servern.
            delete(toFolder, toName);
        }
        if (parts(fromFolder).equals(parts(toFolder))) {
            rename(fromFolder, fromName, toName);
            return;
        }
        copyInternal(fromFolder, fromName, toFolder, toName);
        delete(fromFolder, fromName);
    }

    @Override
    public void moveNoReplace(String folder, String fromName, String toName, String expectedVersion)
            throws IOException {
        if (file(folder, toName) != null) {
            throw new RemoteConflictException(Diagnostics.t("Ziel ist schon belegt: ",
                    "Target already exists: ") + toName);
        }
        Child source = requireFile(folder, fromName);
        if (expectedVersion != null && !expectedVersion.isEmpty()
                && !expectedVersion.equals(source.version())) {
            throw new RemoteConflictException(Diagnostics.t(
                    "Die Datei wurde inzwischen geändert: ", "The file has been changed meanwhile: ")
                    + fromName);
        }
        rename(folder, fromName, toName);
    }

    /** Umbenennen im selben Ordner; danach muss der Name stimmen. */
    private void rename(String folder, String fromName, String toName) throws IOException {
        String parent = requireFolder(folder);
        Child source = named(children(parent), fromName);
        if (source == null || source.folder) {
            throw new FileNotFoundException(Diagnostics.t("Datei nicht gefunden: ", "File not found: ")
                    + RemotePath.join(folder, fromName));
        }
        Uri renamed;
        try {
            renamed = DocumentsContract.renameDocument(resolver, docUri(source.id), toName);
        } catch (SecurityException | IllegalArgumentException e) {
            throw noAccess(e);
        } catch (UnsupportedOperationException e) {
            // Anbieter ohne Umbenennen: kopieren, prüfen, das Original löschen.
            copyInternal(folder, fromName, folder, toName);
            delete(folder, fromName);
            return;
        }
        String id = renamed != null ? DocumentsContract.getDocumentId(renamed) : source.id;
        checkName(parent, id, toName);
    }

    @Override
    public void copy(String fromFolder, String fromName, String toFolder, String toName)
            throws IOException {
        if (file(toFolder, toName) != null) {
            throw new RemoteConflictException(Diagnostics.t("Ziel ist schon belegt: ",
                    "Target already exists: ") + toName);
        }
        copyInternal(fromFolder, fromName, toFolder, toName);
    }

    private void copyInternal(String fromFolder, String fromName, String toFolder, String toName)
            throws IOException {
        byte[] content = downloadBytes(fromFolder, fromName);
        uploadBytes(toFolder, toName, content);
        long size = fileSize(toFolder, toName);
        if (size >= 0 && size != content.length) {
            throw new IOException(Diagnostics.t("Kopie unvollständig: ", "Copy incomplete: ") + toName);
        }
    }

    // ---- Lesen ----

    @Override
    public String fileVersion(String folder, String fileName) throws IOException {
        Child c = file(folder, fileName);
        return c == null ? "" : c.version();
    }

    @Override
    public long fileSize(String folder, String fileName) throws IOException {
        Child c = file(folder, fileName);
        return c == null ? -1 : c.size;
    }

    @Override
    public List<String> listFiles(String folder, String ext) throws IOException {
        String suffix = "." + ext.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String name : listAllFiles(folder)) {
            if (name.toLowerCase(Locale.ROOT).endsWith(suffix)) {
                out.add(name);
            }
        }
        return out;
    }

    @Override
    public List<String> listAllFiles(String folder) throws IOException {
        List<String> out = new ArrayList<>();
        String id = folderId(folder, false);
        if (id != null) {
            for (Child c : children(id)) {
                if (!c.folder && c.name != null) {
                    out.add(c.name);
                }
            }
        }
        return out;
    }

    @Override
    public List<String> listFolders(String folder) throws IOException {
        List<String> out = new ArrayList<>();
        String id = folderId(folder, false);
        if (id != null) {
            for (Child c : children(id)) {
                if (c.folder && c.name != null) {
                    out.add(c.name);
                }
            }
        }
        return out;
    }

    @Override
    public Entries listEntries(String folder, String ext) throws IOException {
        List<String> folders = new ArrayList<>();
        List<String> files = new ArrayList<>();
        String suffix = "." + ext.toLowerCase(Locale.ROOT);
        String id = folderId(folder, false);
        if (id != null) {
            for (Child c : children(id)) {
                if (c.name == null) {
                    continue;
                }
                if (c.folder) {
                    folders.add(c.name);
                } else if (c.name.toLowerCase(Locale.ROOT).endsWith(suffix)) {
                    files.add(c.name);
                }
            }
        }
        return new Entries(folders, files);
    }

    @Override
    public String downloadText(String folder, String fileName) throws IOException {
        return new String(downloadBytes(folder, fileName), StandardCharsets.UTF_8);
    }

    @Override
    public byte[] downloadBytes(String folder, String fileName) throws IOException {
        return downloadBytes(folder, fileName, null);
    }

    @Override
    public byte[] downloadBytes(String folder, String fileName,
                                de.spahr.ausgaben.util.ProgressListener listener) throws IOException {
        Child c = requireFile(folder, fileName);
        InputStream in;
        try {
            in = resolver.openInputStream(docUri(c.id));
        } catch (SecurityException e) {
            throw noAccess(e);
        }
        if (in == null) {
            throw new FileNotFoundException(fileName);
        }
        try (InputStream i = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n;
            while ((n = i.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (listener != null) {
                    listener.onProgress(done, c.size);
                }
            }
            return out.toByteArray();
        }
    }

    @Override
    public void testConnection() throws IOException {
        children(rootId());
    }

    /** Der Anzeigename des gewählten Ordners, für die Maske; leer, wenn er sich nicht lesen lässt. */
    public static String displayName(Context context, String treeUri) {
        if (treeUri == null || treeUri.trim().isEmpty()) {
            return "";
        }
        try {
            Uri tree = Uri.parse(treeUri.trim());
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
            try (Cursor c = context.getContentResolver().query(doc,
                    new String[]{Document.COLUMN_DISPLAY_NAME}, null, null)) {
                if (c != null && c.moveToFirst()) {
                    return c.getString(0);
                }
            }
        } catch (RuntimeException ignored) {
            // nicht lesbar – die Maske zeigt dann die Adresse
        }
        return "";
    }
}
