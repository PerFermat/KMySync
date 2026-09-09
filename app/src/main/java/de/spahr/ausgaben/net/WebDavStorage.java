package de.spahr.ausgaben.net;

import java.io.IOException;
import java.util.List;

/**
 * {@link RemoteStorage} über WebDAV/Nextcloud. Kapselt Basis-URL/Benutzer/Passwort und delegiert an den
 * bestehenden {@link NextcloudUploader}.
 */
public class WebDavStorage implements RemoteStorage {

    private final NextcloudUploader uploader;
    private final String baseUrl;
    private final String user;
    private final String password;

    public WebDavStorage(String baseUrl, String user, String password, boolean nextcloudLayout) {
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.user = user == null ? "" : user;
        this.password = password == null ? "" : password;
        this.uploader = new NextcloudUploader(nextcloudLayout);
    }

    @Override
    public void uploadText(String folder, String fileName, String content) throws IOException {
        uploader.upload(baseUrl, user, password, folder, fileName, content);
    }

    @Override
    public void uploadBytes(String folder, String fileName, byte[] content) throws IOException {
        uploader.uploadBytes(baseUrl, user, password, folder, fileName, content);
    }

    @Override
    public List<String> listFiles(String folder, String ext) throws IOException {
        return uploader.listFiles(baseUrl, user, password, folder, ext);
    }

    @Override
    public List<String> listFolders(String folder) throws IOException {
        return uploader.listFolders(baseUrl, user, password, folder);
    }

    @Override
    public void ensureFolder(String folder) throws IOException {
        uploader.createFolder(baseUrl, user, password, folder);
    }

    @Override
    public void delete(String folder, String fileName) throws IOException {
        uploader.delete(baseUrl, user, password, folder, fileName);
    }

    @Override
    public void move(String folder, String fromName, String toName) throws IOException {
        uploader.move(baseUrl, user, password, folder, fromName, toName);
    }

    /** Herunterladen mit Rückmeldung der gelesenen Bytes (Fortschrittsanzeige). */
    @Override
    public byte[] downloadBytes(String folder, String fileName,
                                de.spahr.ausgaben.util.ProgressListener listener) throws IOException {
        return uploader.downloadBytes(baseUrl, user, password, folder, fileName, listener);
    }

    /** WebDAV-ETag der Datei (leer, wenn der Server keinen liefert). */
    @Override
    public String fileVersion(String folder, String fileName) throws IOException {
        return uploader.etag(baseUrl, user, password, folder, fileName);
    }

    /** Schreibt per {@code If-Match} nur, wenn die Datei noch den erwarteten ETag hat (Server prüft). */
    @Override
    public void uploadBytes(String folder, String fileName, byte[] content, String expectedVersion)
            throws IOException {
        uploader.uploadBytes(baseUrl, user, password, folder, fileName, content, expectedVersion);
    }

    @Override
    public String downloadText(String folder, String fileName) throws IOException {
        return uploader.downloadText(baseUrl, user, password, folder, fileName);
    }

    @Override
    public byte[] downloadBytes(String folder, String fileName) throws IOException {
        return uploader.downloadBytes(baseUrl, user, password, folder, fileName);
    }

    @Override
    public void testConnection() throws IOException {
        // PROPFIND auf die Wurzel: prüft URL, Zugangsdaten und Erreichbarkeit.
        uploader.listFiles(baseUrl, user, password, "", "csv");
    }
}
