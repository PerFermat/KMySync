package de.spahr.ausgaben.net;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Probe aufs Exempel beim Einrichten: Kann der Server das, was das Rückschreiben der KMyMoney-Datei
 * braucht – <b>schreiben, umbenennen, löschen</b>?
 *
 * <p>Das bloße Verbinden und Auflisten ({@link RemoteStorage#testConnection()}) sagt darüber nichts. Ohne
 * Umbenennen scheitert später jeder Export (siehe {@link SafeReplace}), und das fiele erst beim ersten
 * echten Übertragen auf. Hier läuft es einmal im Kleinen durch – mit einer winzigen Datei, die
 * anschließend wieder verschwindet.</p>
 *
 * <p>Transportneutral: geprüft wird gegen {@link RemoteStorage}, also für WebDAV/Nextcloud <b>und</b>
 * SMB derselbe Ablauf. Bewusst ohne Android, damit es sich gewöhnlich testen läßt.</p>
 */
public final class RemoteSelfTest {

    /** Ergebnis: welcher Schritt scheiterte, und warum. */
    public enum Step {
        /** Alles gut. */
        OK,
        /** Schon das Schreiben ging nicht (Rechte, Speicherplatz, Ordner). */
        WRITE,
        /** Geschrieben ja, umbenennen nein – dann ist ein sicherer Export nicht möglich. */
        RENAME
    }

    /** Was die Probe ergeben hat; bei {@link Step#OK} ist {@link #cause} {@code null}. */
    public static final class Result {
        public final Step step;
        public final Exception cause;

        Result(Step step, Exception cause) {
            this.step = step;
            this.cause = cause;
        }

        public boolean ok() {
            return step == Step.OK;
        }
    }

    private static final byte[] PROBE = "KMySync".getBytes(StandardCharsets.UTF_8);

    private RemoteSelfTest() {
    }

    /**
     * Führt die Probe in {@code folder} durch. Aufräumen ist Teil des Ablaufs; bleibt doch einmal etwas
     * liegen, sind es wenige Bytes mit erkennbarem Namen.
     *
     * @param stamp Zeitstempel für die Namen (vom Aufrufer, damit testbar)
     */
    public static Result run(RemoteStorage storage, String folder, String stamp) {
        String from = probeName(stamp);
        String to = renamedProbeName(stamp);
        try {
            storage.uploadBytes(folder, from, PROBE);
        } catch (IOException | RuntimeException e) {
            return new Result(Step.WRITE, asException(e));
        }
        try {
            storage.move(folder, from, to);
        } catch (IOException | RuntimeException e) {
            cleanUp(storage, folder, from);
            return new Result(Step.RENAME, asException(e));
        }
        cleanUp(storage, folder, to);
        return new Result(Step.OK, null);
    }

    /**
     * Name der Probe-Datei. Der Punkt am Anfang hält sie aus der Anzeige heraus, der Rest nennt App und
     * Zeitpunkt – damit ein Überbleibsel (abgebrochene Verbindung) zuzuordnen ist. Auch die Diagnosen
     * benutzen diesen Namen, damit es nur <b>eine</b> Auskunft darüber gibt, wonach der Nutzer im
     * Zweifel suchen muss.
     */
    public static String probeName(String stamp) {
        return ".kmysync-probe-" + stamp + "." + SafeReplace.TMP_EXT;
    }

    /** Der Name, auf den die Probe umbenannt wird – das prüft das Umbenennen-Recht. */
    public static String renamedProbeName(String stamp) {
        return ".kmysync-probe-" + stamp + "-ok." + SafeReplace.TMP_EXT;
    }

    private static void cleanUp(RemoteStorage storage, String folder, String name) {
        try {
            storage.delete(folder, name);
        } catch (Exception ignored) {
            // Aufräumen darf das Ergebnis der Probe nicht kippen.
        }
    }

    private static Exception asException(Throwable t) {
        return t instanceof Exception ? (Exception) t : new IOException(t);
    }
}
