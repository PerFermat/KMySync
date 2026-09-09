package de.spahr.ausgaben.net;

import java.io.IOException;
import java.util.List;

/**
 * Ersetzt eine Datei auf dem Server, ohne sie bei einem Abbruch beschädigen zu können.
 *
 * <p>Hintergrund: Der Export schrieb die KMyMoney-Datei früher unmittelbar über die vorhandene. Riss die
 * Verbindung mittendrin – etwa durch ein Timeout –, blieb ein <b>Torso</b> zurück: halb geschriebene
 * Datei, unlesbar, und die einzige gute Fassung war bereits überschrieben. Genau so ging eine .kmy
 * verloren (nur die Sicherung rettete die Daten).</p>
 *
 * <p>Hier wird deshalb zuerst <b>vollständig</b> unter einem Zwischennamen geschrieben und das Ziel erst
 * danach durch {@link RemoteStorage#move} ersetzt – ein Schritt, den der Server unteilbar ausführt.
 * Scheitert das Schreiben, ist die Zieldatei nie angefasst worden.</p>
 *
 * <p>Bewusst ohne Android-Bezug, damit sich das Verhalten mit gewöhnlichen Tests festnageln läßt.</p>
 */
public final class SafeReplace {

    /**
     * Endung der Zwischendateien. Der Name lautet {@code <Datei>.<Zeitstempel>.tmp} – der Zeitstempel
     * hält zwei gleichzeitige Exporte auseinander, die Endung macht die Reste über
     * {@link RemoteStorage#listFiles} auffindbar (beide Backends filtern strikt nach Endung, ein leeres
     * Filterkriterium liefert dort <b>nichts</b>).
     */
    public static final String TMP_EXT = "tmp";

    private SafeReplace() {
    }

    /** Name der Zwischendatei zu {@code file}. */
    private static String tmpName(String file, String stamp) {
        return file + "." + stamp + "." + TMP_EXT;
    }

    /**
     * Schreibt {@code content} nach {@code folder/file}.
     *
     * @param expectedVersion Stand, auf dem die Datei noch stehen muss (aus
     *                        {@link RemoteStorage#fileVersion}); {@code ""} = ungeprüft. Geprüft wird
     *                        unmittelbar vor dem Ersetzen, also so spät wie möglich.
     * @param stamp           Zeitstempel für den Zwischennamen (vom Aufrufer, damit testbar).
     * @throws RemoteConflictException wenn die Datei zwischenzeitlich fremd geändert wurde
     */
    public static void replace(RemoteStorage storage, String folder, String file, byte[] content,
                               String expectedVersion, String stamp) throws IOException {
        String tmp = tmpName(file, stamp);
        try {
            storage.uploadBytes(folder, tmp, content);
            // So spät wie möglich prüfen: zwischen Herunterladen und hier liegen Minuten, zwischen hier
            // und dem move nur Millisekunden.
            if (expectedVersion != null && !expectedVersion.isEmpty()) {
                String now = storage.fileVersion(folder, file);
                if (!now.isEmpty() && !now.equals(expectedVersion)) {
                    throw new RemoteConflictException("Datei wurde zwischenzeitlich geändert: " + file);
                }
            }
            try {
                storage.move(folder, tmp, file);
            } catch (IOException | RuntimeException e) {
                // Eigene Ausnahme: hier ist alles geschrieben, nur das Ersetzen ging nicht. Für den
                // Nutzer ein anderer Sachverhalt als ein Netzfehler mittendrin – und die Zieldatei ist
                // garantiert unberührt.
                throw new RemoteMoveException(
                        "Umbenennen auf dem Server nicht möglich: " + tmp + " → " + file, e);
            }
        } catch (IOException | RuntimeException e) {
            // Die Zwischendatei ist wertlos, sobald es schiefging – wegräumen, aber den eigentlichen
            // Fehler nicht dadurch verdecken, dass auch das Aufräumen scheitert.
            try {
                storage.delete(folder, tmp);
            } catch (Exception ignored) {
                // liegengelassene Zwischendatei; sie wird beim nächsten Export aufgeräumt
            }
            throw e;
        }
    }

    /**
     * Entfernt Zwischendateien früherer, abgebrochener Versuche zu {@code file}. Fehler dabei sind
     * belanglos – es ist reines Aufräumen und darf einen Export nie scheitern lassen.
     */
    public static void cleanUp(RemoteStorage storage, String folder, String file) {
        try {
            List<String> names = storage.listFiles(folder, TMP_EXT);
            for (String name : names) {
                if (name.startsWith(file + ".") && name.endsWith("." + TMP_EXT)) {
                    try {
                        storage.delete(folder, name);
                    } catch (Exception ignored) {
                        // nächster Versuch beim nächsten Export
                    }
                }
            }
        } catch (Exception ignored) {
            // Auflisten nicht möglich – dann eben nicht aufräumen
        }
    }
}
