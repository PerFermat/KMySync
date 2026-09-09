package de.spahr.ausgaben.net;

import java.io.IOException;
import java.util.List;

import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Ordner-/datei-orientierter Zugriff auf ein entferntes Sync-Ziel. Implementierungen kapseln die
 * Zugangsdaten; {@code folder} ist relativ zur konfigurierten Wurzel (WebDAV-Wurzel bzw. SMB-Freigabe/Basis).
 * Alle Methoden blockieren (Netzwerk) und werden von den Aufrufern auf Hintergrund-Threads genutzt.
 */
public interface RemoteStorage {

    void uploadText(String folder, String fileName, String content) throws IOException;

    void uploadBytes(String folder, String fileName, byte[] content) throws IOException;

    /**
     * Undurchsichtiges Kennzeichen des aktuellen Dateistands (z. B. WebDAV-ETag oder „mtime:size"), um
     * eine Fremdänderung zwischen Herunterladen und Rückschreiben zu erkennen. {@code ""} = das Backend
     * unterstützt das nicht (dann gibt es keinen Schutz).
     */
    default String fileVersion(String folder, String fileName) throws IOException {
        return "";
    }

    /**
     * Wie {@link #uploadBytes(String, String, byte[])}, schreibt aber nur, wenn die Datei noch auf dem
     * Stand {@code expectedVersion} ist (aus {@link #fileVersion}); sonst
     * {@link RemoteConflictException}. Leeres {@code expectedVersion} = ungeprüft schreiben.
     * Standard: kein Schutz – Backends überschreiben das.
     */
    default void uploadBytes(String folder, String fileName, byte[] content, String expectedVersion)
            throws IOException {
        uploadBytes(folder, fileName, content);
    }

    /** Dateinamen im Ordner mit der Endung {@code ext} (ohne Punkt, z. B. "csv" oder "kmy"). */
    List<String> listFiles(String folder, String ext) throws IOException;

    /** Unterordner-Namen im Ordner (für den Datei-Browser); leere Liste, wenn nicht unterstützt. */
    default List<String> listFolders(String folder) throws IOException {
        return java.util.Collections.emptyList();
    }

    /** Ordner und Dateien eines Verzeichnisses – das, was ein Datei-Browser pro Schritt braucht. */
    final class Entries {
        public final List<String> folders;
        public final List<String> files;

        public Entries(List<String> folders, List<String> files) {
            this.folders = folders;
            this.files = files;
        }
    }

    /**
     * Ordner <b>und</b> Dateien in einem Aufruf. Backends, die je Aufruf eine eigene Verbindung
     * aufbauen (SMB), sparen damit die Hälfte: der Browser fragte bisher zweimal hintereinander und
     * meldete sich dafür zweimal an – Server mit Sitzungsgrenzen lehnen die zweite Anmeldung schon mal
     * ab. Standard: die beiden Einzelmethoden nacheinander.
     */
    default Entries listEntries(String folder, String ext) throws IOException {
        return new Entries(listFolders(folder), listFiles(folder, ext));
    }

    /**
     * Stellt sicher, dass {@code folder} existiert (legt ihn ggf. an). Standard: nichts zu tun – Backends,
     * deren Upload den Ordner ohnehin anlegt (SMB), brauchen keine eigene Implementierung.
     */
    default void ensureFolder(String folder) throws IOException {
    }

    /**
     * Löscht eine Datei. Standard: nicht unterstützt – die Implementierungen überschreiben das. Fehlt die
     * Datei, ist das kein Fehler.
     */
    default void delete(String folder, String fileName) throws IOException {
    }

    /**
     * Benennt eine Datei innerhalb desselben Ordners um und ersetzt dabei ein vorhandenes Ziel. Der
     * Vorgang läuft auf dem Server und ist dort unteilbar – darauf beruht das gefahrlose Ersetzen der
     * KMyMoney-Datei (siehe {@link SafeReplace}): geschrieben wird erst vollständig unter einem
     * Zwischennamen, das Ziel wird nur durch dieses Umbenennen ersetzt.
     *
     * <p>Standard: nicht unterstützt. Ein Backend ohne Umbenennen muss weiterhin direkt schreiben – dann
     * bleibt das alte Risiko bestehen, deshalb implementieren es WebDAV und SMB beide.</p>
     */
    default void move(String folder, String fromName, String toName) throws IOException {
        throw new IOException("Umbenennen wird von diesem Server nicht unterstützt");
    }

    String downloadText(String folder, String fileName) throws IOException;

    byte[] downloadBytes(String folder, String fileName) throws IOException;

    /**
     * Wie {@link #downloadBytes(String, String)}, meldet aber die gelesenen Bytes (für die
     * Fortschrittsanzeige). Standard: ohne Rückmeldung – Backends überschreiben das.
     */
    default byte[] downloadBytes(String folder, String fileName,
                                 de.spahr.ausgaben.util.ProgressListener listener) throws IOException {
        return downloadBytes(folder, fileName);
    }

    /** Prüft die Verbindung (Verbinden + Auflisten der Basis); wirft bei Fehler eine {@link IOException}. */
    void testConnection() throws IOException;

    /**
     * Erstellt das passende Backend nach Server-Typ; Zugangsdaten aus den Einstellungen.
     *
     * <p>Kam eine SMB-Verbindung nur über den Standardport zustande (der eingetragene Port stammt oft
     * aus der mDNS-Auskunft des Servers und ist manchmal falsch), wird die gespeicherte Adresse hier
     * gleich korrigiert – sonst liefe jeder weitere Zugriff wieder in den Fehlversuch.</p>
     */
    static RemoteStorage from(SettingsStore s) {
        if (SettingsStore.SERVER_SMB.equals(s.getServerType())) {
            return new SmbStorage(s.getUrl(), s.getUser(), s.getPassword())
                    .withPortCorrection(port -> s.setUrl(SettingsStore.withPort(s.getUrl(), port)));
        }
        return from(s.getServerType(), s.getUrl(), s.getUser(), s.getPassword());
    }

    /**
     * Wie {@link #from(SettingsStore)}, aber aus Einzelwerten – für UI-Aktionen mit noch nicht
     * gespeicherten Feldwerten (Verbindung testen / .kmy durchsuchen).
     */
    static RemoteStorage from(String serverType, String url, String user, String password) {
        if (SettingsStore.SERVER_SMB.equals(serverType)) {
            return new SmbStorage(url, user, password);
        }
        boolean nextcloudLayout = !SettingsStore.SERVER_WEBDAV.equals(serverType);
        return new WebDavStorage(url, user, password, nextcloudLayout);
    }
}
