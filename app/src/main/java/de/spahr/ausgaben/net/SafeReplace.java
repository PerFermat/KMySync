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
 * <p>Hier wird deshalb zuerst <b>vollständig</b> unter einem Zwischennamen geschrieben. Scheitert das
 * Schreiben, ist die Zieldatei nie angefasst worden.</p>
 *
 * <h2>Tauschen statt Überschreiben</h2>
 *
 * <p>Anschließend wird <b>getauscht</b>, nicht überschrieben: die alte Datei zur Seite benennen
 * ({@code <Datei>.<Zeitstempel>.old}), die neue an ihren Platz, zuletzt die alte löschen. Früher stand
 * hier ein einziges MOVE mit {@code Overwrite: T}, in der Annahme, der Server führe das unteilbar aus.
 * Nextcloud tut das nicht: Es löscht zuerst das Ziel und benennt dann um. Scheiterte das Umbenennen
 * (am 25.09.2026 so geschehen), war die .kmy schon weg – und weil dieser Code das Ziel für unberührt
 * hielt, räumte er obendrein die Zwischendatei weg. Übrig blieb nichts; gerettet hat der Papierkorb
 * der Nextcloud.</p>
 *
 * <p>Beim Tauschen ist jedes Umbenennen eines auf einen <b>freien</b> Namen, und gelöscht wird erst,
 * wenn die neue Datei an ihrem Platz steht. Zu jedem Zeitpunkt liegt also mindestens ein
 * vollständiger Stand unter einem bekannten Namen. Scheitert das Einsetzen der neuen Datei, wird die
 * alte zurückbenannt – der Zustand ist dann derselbe wie vor dem Export, passend dazu, dass der
 * Aufrufer nichts als exportiert markiert.</p>
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

    /** Endung der zur Seite benannten alten Datei während des Tauschs. */
    public static final String OLD_EXT = "old";

    private SafeReplace() {
    }

    /** Name der Zwischendatei zu {@code file}. */
    private static String tmpName(String file, String stamp) {
        return file + "." + stamp + "." + TMP_EXT;
    }

    /** Name, unter dem die alte Datei während des Tauschs liegt. */
    private static String oldName(String file, String stamp) {
        return file + "." + stamp + "." + OLD_EXT;
    }

    /**
     * Schreibt {@code content} nach {@code folder/file}.
     *
     * @param expectedVersion Stand, auf dem die Datei noch stehen muss (aus
     *                        {@link RemoteStorage#fileVersion}); {@code ""} = ungeprüft. Geprüft wird
     *                        unmittelbar vor dem Ersetzen, also so spät wie möglich.
     * @param stamp           Zeitstempel für die Zwischennamen (vom Aufrufer, damit testbar).
     * @throws RemoteConflictException    wenn die Datei zwischenzeitlich fremd geändert wurde
     * @throws RemoteMoveException        wenn der Tausch nicht ging; die Zieldatei steht dann wieder
     *                                    unverändert an ihrem Platz
     * @throws RemoteReplaceStuckException wenn auch das Zurückbenennen scheiterte – die Datei fehlt,
     *                                    beide Stände liegen unter ihren Zwischennamen
     */
    public static void replace(RemoteStorage storage, String folder, String file, byte[] content,
                               String expectedVersion, String stamp) throws IOException {
        String tmp = tmpName(file, stamp);
        String old = oldName(file, stamp);
        try {
            storage.uploadBytes(folder, tmp, content);
            // So spät wie möglich prüfen: zwischen Herunterladen und hier liegen Minuten, zwischen hier
            // und dem Tausch nur Millisekunden.
            if (expectedVersion != null && !expectedVersion.isEmpty()) {
                String now = storage.fileVersion(folder, file);
                if (!now.isEmpty() && !now.equals(expectedVersion)) {
                    throw new RemoteConflictException("Datei wurde zwischenzeitlich geändert: " + file);
                }
            }
            try {
                storage.move(folder, file, old);
            } catch (IOException | RuntimeException e) {
                // Noch nichts vertauscht: Die Zieldatei steht, wie sie war.
                throw new RemoteMoveException(
                        "Umbenennen auf dem Server nicht möglich: " + file + " → " + old, e);
            }
        } catch (IOException | RuntimeException e) {
            // Die Zwischendatei ist wertlos, sobald es bis hierher schiefging – wegräumen, aber den
            // eigentlichen Fehler nicht dadurch verdecken, dass auch das Aufräumen scheitert.
            deleteQuietly(storage, folder, tmp);
            throw e;
        }

        // Ab hier liegt die alte Datei unter „old". Nichts wird gelöscht, bevor die neue steht.
        try {
            storage.move(folder, tmp, file);
        } catch (IOException | RuntimeException e) {
            try {
                storage.move(folder, old, file);
            } catch (IOException | RuntimeException zurueck) {
                // Weder neu noch alt an ihrem Platz. Beide Stände bleiben liegen – jede Löschung wäre
                // jetzt ein Verlust. Die Meldung nennt den Namen, der zurückbenannt werden muss.
                RemoteReplaceStuckException stuck = new RemoteReplaceStuckException(file, old, e);
                stuck.addSuppressed(zurueck);
                throw stuck;
            }
            // Alte Datei steht wieder: wie vor dem Export. Erst jetzt ist die neue wertlos.
            deleteQuietly(storage, folder, tmp);
            throw new RemoteMoveException(
                    "Umbenennen auf dem Server nicht möglich: " + tmp + " → " + file, e);
        }
        // Die neue Datei steht. Bleibt die alte liegen, räumt sie der nächste Export weg.
        deleteQuietly(storage, folder, old);
    }

    private static void deleteQuietly(RemoteStorage storage, String folder, String name) {
        try {
            storage.delete(folder, name);
        } catch (Exception ignored) {
            // liegengelassen; der nächste Export räumt auf
        }
    }

    /**
     * Entfernt Zwischendateien früherer, abgebrochener Versuche zu {@code file}. Fehler dabei sind
     * belanglos – es ist reines Aufräumen und darf einen Export nie scheitern lassen.
     *
     * <p>Darf nur laufen, wenn {@code file} gerade gelesen werden konnte – der Export ruft es nach dem
     * Herunterladen. Liegengebliebene {@code .old}-Dateien sind dann nachweislich überholt; fehlte die
     * Datei, wäre eine davon womöglich der einzige Stand.</p>
     */
    public static void cleanUp(RemoteStorage storage, String folder, String file) {
        for (String ext : new String[]{TMP_EXT, OLD_EXT}) {
            try {
                List<String> names = storage.listFiles(folder, ext);
                for (String name : names) {
                    if (name.startsWith(file + ".") && name.endsWith("." + ext)) {
                        deleteQuietly(storage, folder, name);
                    }
                }
            } catch (Exception ignored) {
                // Auflisten nicht möglich – dann eben nicht aufräumen
            }
        }
    }
}
