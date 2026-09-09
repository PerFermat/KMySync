package de.spahr.ausgaben.net;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import de.spahr.ausgaben.settings.SettingsStore;

/**
 * {@link RemoteStorage} über eine SMB/Samba-Freigabe (SMB2/3, via smbj). Die „URL" hat die Form
 * {@code smb://Host[:Port]/Freigabe[/Basis]}; ohne Port gilt der Standardport 445.
 * Benutzer/Passwort aus den Einstellungen; leerer Benutzer → Gast.
 * Ein {@code DOMÄNE\\Benutzer}-Präfix wird als Windows-Domäne interpretiert. Verbindung wird je Aufruf
 * geöffnet und wieder geschlossen (wie der WebDAV-Pfad zustandslos).
 */
public class SmbStorage implements RemoteStorage {

    private final String host;
    private final int port;
    private final String share;
    private final String base;
    private final String user;
    private final String password;
    /** Wird gerufen, wenn die Verbindung nur über einen anderen Port als den eingetragenen zustande kam. */
    private PortCorrection portCorrection;

    /** Rückmeldung eines abweichenden Ports, damit die Einstellungen ihn übernehmen können. */
    public interface PortCorrection {
        void onPortChanged(int usedPort);
    }

    public SmbStorage(String url, String user, String password) {
        String[] parts = SettingsStore.parseSmb(url);
        this.host = parts[0];
        this.port = parts[3].isEmpty() ? 0 : Integer.parseInt(parts[3]);
        this.share = parts[1];
        this.base = parts[2];
        this.user = user == null ? "" : user.trim();
        this.password = password == null ? "" : password;
    }

    /** Meldet abweichende Ports an den Aufrufer; {@code null} = niemand hört zu. */
    public SmbStorage withPortCorrection(PortCorrection correction) {
        this.portCorrection = correction;
        return this;
    }

    private interface Action<T> {
        T run(DiskShare share) throws Exception;
    }

    private <T> T withShare(Action<T> action) throws IOException {
        if (host.isEmpty() || share.isEmpty()) {
            throw new IOException("SMB: Host/Freigabe fehlt (smb://Host/Freigabe)");
        }
        try (de.spahr.ausgaben.net.smb.SmbSessions.Link link =
                     de.spahr.ausgaben.net.smb.SmbSessions.open(host, port, false)) {
            if (portCorrection != null && link.usedPort != port) {
                portCorrection.onPortChanged(link.usedPort);
            }
            Session session = de.spahr.ausgaben.net.smb.SmbSessions.authenticate(
                    link.connection, user, password);
            try (DiskShare disk = (DiskShare) session.connectShare(share)) {
                return action.run(disk);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        }
    }

    @Override
    public void uploadText(String folder, String fileName, String content) throws IOException {
        uploadBytes(folder, fileName, content.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void uploadBytes(String folder, String fileName, byte[] content) throws IOException {
        final String dir = joinPath(base, folder);
        final String path = joinPath(dir, fileName);
        withShare(disk -> {
            ensureDir(disk, dir);
            try (com.hierynomus.smbj.share.File f = disk.openFile(path,
                    EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OVERWRITE_IF, null);
                 OutputStream os = f.getOutputStream()) {
                os.write(content);
            }
            return null;
        });
    }

    @Override
    public void delete(String folder, String fileName) throws IOException {
        final String path = joinPath(joinPath(base, folder), fileName);
        withShare(disk -> {
            if (disk.fileExists(path)) {
                disk.rm(path);
            }
            return null;
        });
    }

    /**
     * Benennt innerhalb desselben Ordners um und ersetzt ein vorhandenes Ziel. Der Server führt das in
     * einem Zug aus – anders als beim direkten Überschreiben (dort kürzt {@code FILE_OVERWRITE_IF} die
     * Datei erst auf null) kann das Ziel dabei nie halb geschrieben zurückbleiben.
     */
    @Override
    public void move(String folder, String fromName, String toName) throws IOException {
        final String dir = joinPath(base, folder);
        final String from = joinPath(dir, fromName);
        final String to = joinPath(dir, toName);
        withShare(disk -> {
            // Zum Umbenennen wird DELETE auf der Quelle verlangt (SMB benennt „durch Löschen des
            // alten Namens" um); das Ziel darf ersetzt werden.
            try (com.hierynomus.smbj.share.File f = disk.openFile(from,
                    EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ), null,
                    SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)) {
                f.rename(to, true);
            }
            return null;
        });
    }

    /** Dateistand als {@code "changeTime:size"}; leer, wenn die Datei (noch) nicht lesbar ist. */
    @Override
    public String fileVersion(String folder, String fileName) throws IOException {
        final String path = joinPath(joinPath(base, folder), fileName);
        return withShare(disk -> versionOf(disk, path));
    }

    /**
     * Schreibt nur, wenn die Datei noch auf {@code expectedVersion} steht. SMB kennt kein {@code If-Match};
     * geprüft wird deshalb unmittelbar vor dem Schreiben in derselben Sitzung – das Restrisiko sind
     * Millisekunden statt der Minuten zwischen Herunterladen und Rückschreiben.
     */
    @Override
    public void uploadBytes(String folder, String fileName, byte[] content, String expectedVersion)
            throws IOException {
        if (expectedVersion == null || expectedVersion.isEmpty()) {
            uploadBytes(folder, fileName, content);
            return;
        }
        final String dir = joinPath(base, folder);
        final String path = joinPath(dir, fileName);
        withShare(disk -> {
            String current = versionOf(disk, path);
            if (!current.isEmpty() && !current.equals(expectedVersion)) {
                throw new RemoteConflictException("SMB: " + fileName + " wurde zwischenzeitlich geändert");
            }
            ensureDir(disk, dir);
            try (com.hierynomus.smbj.share.File f = disk.openFile(path,
                    EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OVERWRITE_IF, null);
                 OutputStream os = f.getOutputStream()) {
                os.write(content);
            }
            return null;
        });
    }

    /** Änderungszeit + Größe der Datei; "" wenn nicht ermittelbar (dann kein Schutz). */
    private String versionOf(DiskShare disk, String path) {
        try {
            com.hierynomus.msfscc.fileinformation.FileAllInformation info = disk.getFileInformation(path);
            long changed = info.getBasicInformation().getChangeTime().toEpochMillis();
            long size = info.getStandardInformation().getEndOfFile();
            return changed + ":" + size;
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public List<String> listFiles(String folder, String ext) throws IOException {
        return withShare(disk -> collect(disk, joinPath(base, folder), ext).files);
    }

    @Override
    public List<String> listFolders(String folder) throws IOException {
        return withShare(disk -> collect(disk, joinPath(base, folder), "").folders);
    }

    /** Ordner und Dateien in <b>einer</b> Verbindung – der Datei-Browser braucht immer beides. */
    @Override
    public Entries listEntries(String folder, String ext) throws IOException {
        return withShare(disk -> collect(disk, joinPath(base, folder), ext));
    }

    /** Ein Verzeichnis lesen und in Ordner/Dateien trennen; leere {@code ext} = keine Dateien. */
    private static Entries collect(DiskShare disk, String dir, String ext) {
        List<String> folders = new ArrayList<>();
        List<String> files = new ArrayList<>();
        String suffix = ext.isEmpty() ? "" : "." + ext.toLowerCase();
        long dirFlag = FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue();
        for (FileIdBothDirectoryInformation info : disk.list(dir)) {
            String name = info.getFileName();
            if (name == null || name.equals(".") || name.equals("..")) {
                continue;
            }
            if ((info.getFileAttributes() & dirFlag) != 0) {
                folders.add(name);
            } else if (!suffix.isEmpty() && name.toLowerCase().endsWith(suffix)) {
                files.add(name);
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

    /** Herunterladen mit Rückmeldung der gelesenen Bytes; Gesamtgröße aus der Datei-Info. */
    @Override
    public byte[] downloadBytes(String folder, String fileName,
                                de.spahr.ausgaben.util.ProgressListener listener) throws IOException {
        final String path = joinPath(joinPath(base, folder), fileName);
        return withShare(disk -> {
            long total = -1;
            try {
                total = disk.getFileInformation(path).getStandardInformation().getEndOfFile();
            } catch (Exception ignored) {
                // Ohne Größe läuft der Download weiter, nur ohne Prozentwert.
            }
            try (com.hierynomus.smbj.share.File f = disk.openFile(path,
                    EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN, null);
                 InputStream is = f.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                long read = 0;
                int n;
                while ((n = is.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    read += n;
                    if (listener != null) {
                        listener.onProgress(read, total);
                    }
                }
                return bos.toByteArray();
            }
        });
    }

    @Override
    public void testConnection() throws IOException {
        withShare(disk -> disk.list(base));
    }

    /** Legt fehlende Verzeichnisse der (Backslash-)Pfadkette an; „schon vorhanden" wird ignoriert. */
    private void ensureDir(DiskShare disk, String dir) {
        if (dir.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String seg : dir.split("\\\\")) {
            if (seg.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\\');
            }
            sb.append(seg);
            String sofar = sb.toString();
            try {
                if (!disk.folderExists(sofar)) {
                    disk.mkdir(sofar);
                }
            } catch (Exception ignored) {
                // Rennen/Rechte: der eigentliche openFile-Aufruf meldet einen echten Fehler.
            }
        }
    }

    /** Verbindet zwei Pfadteile zu einem SMB-Pfad (Backslash-getrennt, ohne führende/mehrfache Trenner). */
    private static String joinPath(String a, String b) {
        String left = normalize(a);
        String right = normalize(b);
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty()) {
            return left;
        }
        return left + "\\" + right;
    }

    /** Normalisiert einen Pfadteil: Slashes → Backslash, Trenner am Rand/doppelt entfernen. */
    private static String normalize(String p) {
        if (p == null) {
            return "";
        }
        String s = p.trim().replace('/', '\\');
        StringBuilder out = new StringBuilder();
        for (String seg : s.split("\\\\")) {
            if (seg.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append('\\');
            }
            out.append(seg);
        }
        return out.toString();
    }
}
