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
 * <p>Beim Tauschen ist jedes Umbenennen eines auf einen <b>freien</b> Namen – erzwungen über
 * {@link RemoteStorage#moveNoReplace}, das ein belegtes Ziel nie ersetzt –, und gelöscht wird erst,
 * wenn die neue Datei an ihrem Platz steht. Nach jedem gescheiterten Schritt wird nachgesehen, was
 * wirklich im Ordner liegt: Eine Antwort kann verlorengehen, nachdem der Server längst umbenannt hat. Zu jedem Zeitpunkt liegt also mindestens ein
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
     * <p>Jedes Umbenennen geht über {@link RemoteStorage#moveNoReplace} – ein belegtes Ziel wird nie
     * ersetzt. Scheitert ein Schritt, sieht der Code nach, was auf dem Server tatsächlich liegt, statt
     * es zu vermuten: Eine verlorene Antwort heißt nicht, dass der Server nichts getan hat.</p>
     *
     * @param expectedVersion Stand, auf dem die Datei noch stehen muss (aus
     *                        {@link RemoteStorage#fileVersion}); {@code ""} = ungeprüft. Geprüft wird
     *                        im Umbenennen selbst – bei WebDAV vom Server, also ohne Zeitfenster.
     * @param stamp           Zeitstempel für die Zwischennamen (vom Aufrufer, damit testbar).
     * @throws RemoteConflictException    wenn die Datei zwischenzeitlich fremd geändert wurde
     * @throws RemoteMoveException        wenn der Tausch nicht ging; die Zieldatei steht dann
     *                                    unverändert an ihrem Platz
     * @throws RemoteReplaceStuckException wenn sich das nicht sicherstellen ließ – dann ist nichts
     *                                    gelöscht, der alte Stand liegt unter seinem Zwischennamen
     */
    public static void replace(RemoteStorage storage, String folder, String file, byte[] content,
                               String expectedVersion, String stamp) throws IOException {
        String tmp = tmpName(file, stamp);
        String old = oldName(file, stamp);
        String version = expectedVersion == null ? "" : expectedVersion;

        // 1. Neue Fassung vollständig hochladen und nachmessen.
        try {
            storage.uploadBytes(folder, tmp, content);
            long size = storage.fileSize(folder, tmp);
            if (size >= 0 && size != content.length) {
                // Ein Proxy oder Server, der kürzt und trotzdem „OK" sagt: Die Datei darf nicht an
                // ihren Platz.
                throw new IOException("Übertragung unvollständig: " + size + " von " + content.length
                        + " Bytes auf dem Server");
            }
            // Frühwarnung vor dem ersten Umbenennen. Verbindlich prüft erst moveNoReplace.
            if (!version.isEmpty()) {
                String now = storage.fileVersion(folder, file);
                if (!now.isEmpty() && !now.equals(version)) {
                    throw new RemoteConflictException("Datei wurde zwischenzeitlich geändert: " + file);
                }
            }
        } catch (IOException | RuntimeException e) {
            deleteQuietly(storage, folder, tmp);
            throw e;
        }

        // 2. Alte Datei zur Seite – nur, wenn sie noch auf dem erwarteten Stand ist.
        try {
            storage.moveNoReplace(folder, file, old, version);
        } catch (RemoteConflictException e) {
            deleteQuietly(storage, folder, tmp);
            throw e;
        } catch (IOException | RuntimeException e) {
            Lage lage = Lage.lesen(storage, folder, file, tmp, old);
            if (lage != null && lage.file && !lage.old) {
                // Wirklich nichts passiert.
                deleteQuietly(storage, folder, tmp);
                throw new RemoteMoveException(
                        "Umbenennen auf dem Server nicht möglich: " + file + " → " + old, e);
            }
            if (lage == null || lage.file || !lage.old) {
                // Unklar oder unerwartet – nichts anfassen.
                throw new RemoteReplaceStuckException(file, old, e);
            }
            // Datei weg, „old" da: Der Server hat umbenannt, nur die Antwort kam nicht an. Weiter.
        }

        // 3. Neue Datei an ihren Platz. Ab hier liegt die alte unter „old"; nichts wird gelöscht,
        // bevor die neue steht.
        try {
            storage.moveNoReplace(folder, tmp, file, "");
        } catch (IOException | RuntimeException e) {
            Lage lage = Lage.lesen(storage, folder, file, tmp, old);
            if (lage != null && lage.file && !lage.tmp) {
                // Hat geklappt, nur die Antwort fehlte.
                deleteQuietly(storage, folder, old);
                return;
            }
            if (lage != null && lage.file) {
                // Unter dem Namen liegt eine fremde, jüngere Datei (etwa vom Rechner gerade
                // gespeichert). Die gilt; unsere beiden Stände sind überholt und bleiben bis zum
                // nächsten Aufräumen liegen.
                throw new RemoteConflictException("Während des Tauschs neu angelegt: " + file);
            }
            try {
                storage.moveNoReplace(folder, old, file, "");
            } catch (IOException | RuntimeException zurueck) {
                Lage nach = Lage.lesen(storage, folder, file, tmp, old);
                if (nach == null || !nach.file || nach.old) {
                    RemoteReplaceStuckException stuck = new RemoteReplaceStuckException(file, old, e);
                    stuck.addSuppressed(zurueck);
                    throw stuck;
                }
                // Zurückbenennen hat doch gegriffen.
            }
            // Alte Datei steht wieder: wie vor dem Export. Erst jetzt ist die neue wertlos.
            deleteQuietly(storage, folder, tmp);
            throw new RemoteMoveException(
                    "Umbenennen auf dem Server nicht möglich: " + tmp + " → " + file, e);
        }

        // 4. Die neue Datei steht. Bleibt die alte liegen, räumt sie der nächste Export weg.
        deleteQuietly(storage, folder, old);
    }

    /** Was nach einem unklaren Ausgang tatsächlich im Ordner liegt. */
    static final class Lage {
        final boolean file;
        final boolean tmp;
        final boolean old;

        Lage(boolean file, boolean tmp, boolean old) {
            this.file = file;
            this.tmp = tmp;
            this.old = old;
        }

        /**
         * Einmal auflisten, bei Fehler ein zweites Mal; {@code null}, wenn es sich nicht feststellen
         * lässt. Dann darf der Aufrufer nichts löschen.
         */
        static Lage lesen(RemoteStorage storage, String folder, String file, String tmp, String old) {
            for (int versuch = 0; versuch < 2; versuch++) {
                try {
                    List<String> names = storage.listAllFiles(folder);
                    return new Lage(names.contains(file), names.contains(tmp), names.contains(old));
                } catch (Exception ignored) {
                    // noch einmal
                }
            }
            return null;
        }
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
