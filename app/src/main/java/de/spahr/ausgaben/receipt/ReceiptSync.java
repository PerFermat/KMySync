package de.spahr.ausgaben.receipt;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.spahr.ausgaben.net.RemotePath;
import de.spahr.ausgaben.net.RemoteStorage;
import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Hintergrund-Synchronisierung der Belegfotos ins konfigurierte Netzwerkverzeichnis unter
 * {@code <Basis>/Belege/<Jahr>/}. Die Basis ist im kmy-Modus der <b>Ordner der KMyMoney-Datei</b> (dort
 * liegt auch der {@code Backup}-Ordner), im CSV-Modus der eingestellte Sync-Ordner. Kein WorkManager: ein
 * einzelner I/O-Thread lädt die offenen Dateien hoch, ausgelöst beim App-Öffnen und nach der Aufnahme.
 */
public final class ReceiptSync {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private ReceiptSync() {
    }

    /**
     * Der Beleg-Ordner auf dem Server, relativ zur konfigurierten Wurzel: neben der KMyMoney-Datei bzw. im
     * Sync-Ordner.
     *
     * <p>Der Ordnername kommt seit 2.2 aus den Einstellungen des Profils
     * ({@code SettingsStore#getReceiptFolder}) statt aus einer Konstante. Ohne Eintrag steht dort
     * {@code Belege} – für jede bestehende Installation ändert sich damit nichts. Der Grund für die
     * Einstellung steht an jener Methode: Zwei .kmy-Dateien im selben Ordner ergaben sonst
     * zwangsläufig denselben Belegordner.</p>
     */
    public static String remoteBase(SettingsStore settings) {
        String base = settings.isKmyMode()
                ? RemotePath.folderOf(settings.getKmyPath())
                : settings.getFolder();
        return RemotePath.join(base, settings.getReceiptFolder());
    }

    /**
     * Der frühere Ablageort (immer der Sync-Ordner). Wird nur noch <b>gelesen</b>, damit Belege, die vor
     * der Umstellung im kmy-Modus hochgeladen wurden, weiter gefunden werden.
     *
     * <p>Folgt demselben Ordnernamen wie {@link #remoteBase}: Wer ihn umstellt, meint seinen
     * Belegordner – nicht nur den an der einen Stelle.</p>
     */
    private static String legacyBase(SettingsStore settings) {
        return RemotePath.join(settings.getFolder(), settings.getReceiptFolder());
    }

    /**
     * Lädt alle offenen Belege hoch und holt die offenen Jahreswechsel nach (No-op ohne
     * Remote-Konfiguration bzw. ohne offene Vorgänge).
     */
    public static void syncPending(Context context) {
        final Context ctx = context.getApplicationContext();
        final SettingsStore settings = new SettingsStore(ctx);
        if (!settings.hasRemoteConfig()) {
            return;
        }
        // Auf demselben Faden wie die Uploads: Ein Umzug, der beim letzten Mal nicht klappte, würde
        // sonst nie wieder versucht – und der Beleg bliebe im alten Jahresordner unauffindbar.
        IO.execute(() -> ReceiptPages.movePending(ctx));
        final Set<String> pending = Receipts.pending(ctx);
        if (pending.isEmpty()) {
            return;
        }
        IO.execute(() -> {
            RemoteStorage storage;
            try {
                storage = RemoteStorage.from(settings);
            } catch (Exception e) {
                return;
            }
            final String belege = remoteBase(settings);
            for (String entry : pending) {
                String file = Receipts.entryFile(entry);
                int year = Receipts.entryYear(entry);
                File local = Receipts.localFile(ctx, file);
                if (year < 0 || !local.exists()) {
                    Receipts.removePending(ctx, file); // ungültig/verschwunden → nicht endlos erneut versuchen
                    continue;
                }
                try {
                    String yearFolder = belege + "/" + year;
                    storage.ensureFolder(belege);
                    storage.ensureFolder(yearFolder);
                    storage.uploadBytes(yearFolder, file, readAll(local));
                    Receipts.removePending(ctx, file);
                } catch (Exception e) {
                    // offline / Fehler → bleibt offen, nächster Versuch beim nächsten Aufruf
                }
            }
        });
    }

    /**
     * Stellt sicher, dass der Beleg lokal vorliegt; lädt ihn sonst vom Netzlaufwerk nach. Blockierend –
     * vom Aufrufer auf einem Hintergrund-Thread nutzen. Liefert die lokale Datei oder {@code null}.
     *
     * <p>{@code year} ist der Jahresordner; {@code -1} lässt ihn aus dem Dateinamen ableiten (Altbelege
     * mit Jahres-Präfix).</p>
     */
    public static File ensureLocal(Context context, String file, int year) {
        return ensureLocal(context, file, year, null);
    }

    /**
     * Wie {@link #ensureLocal(Context, String, int)}, nutzt aber eine <b>mitgebrachte</b> Verbindung.
     *
     * <p>Für Läufe über viele Belege: Sonst entsteht je Datei eine neue {@code RemoteStorage} und damit
     * ein neuer TLS-Handshake zum Server – bei einem Export über 238 Belege 238 Verbindungsaufbauten,
     * an denen der Verbindungspool von OkHttp folgenlos vorbeiläuft. {@code null} heißt: selbst
     * aufbauen, wie bisher.</p>
     */
    public static File ensureLocal(Context context, String file, int year, RemoteStorage shared) {
        return fetch(context, file, year, shared).file;
    }

    /** Ergebnis von {@link #fetch} – die Datei und, wenn sie fehlt, der Grund. */
    public static final class Fetched {
        /** Die lokale Datei, sobald vorhanden; sonst {@code null}. */
        public final File file;
        /**
         * {@code true} = der Server hat geantwortet, die Datei gibt es dort nicht. Ein zweiter Versuch
         * ändert daran nichts; {@code false} bei einem Verbindungsproblem – das lohnt eine Wiederholung.
         */
        public final boolean notFound;

        Fetched(File file, boolean notFound) {
            this.file = file;
            this.notFound = notFound;
        }
    }

    /**
     * Wie {@link #ensureLocal(Context, String, int, RemoteStorage)}, sagt aber, <b>warum</b> eine Datei
     * fehlt. Für den Beleg-Export, der zwischen „gibt es nicht" und „komme nicht dran" unterscheiden
     * muss: Das eine ist ein Befund, das andere ein Grund, es gleich noch einmal zu versuchen.
     */
    public static Fetched fetch(Context context, String file, int year, RemoteStorage shared) {
        final Context ctx = context.getApplicationContext();
        File local = Receipts.localFile(ctx, file);
        if (local.exists()) {
            return new Fetched(local, false);
        }
        SettingsStore settings = new SettingsStore(ctx);
        int y = year >= 0 ? year : NoteReceipt.yearOf(file);
        if (!settings.hasRemoteConfig() || y < 0) {
            return new Fetched(null, false);
        }
        RemoteStorage storage = shared;
        if (storage == null) {
            try {
                storage = RemoteStorage.from(settings);
            } catch (Exception e) {
                return new Fetched(null, false);
            }
        }
        boolean allMissing = true;
        for (String folder : searchFolders(ctx, settings, y)) {
            try {
                byte[] bytes = storage.downloadBytes(folder, file);
                try (FileOutputStream fos = new FileOutputStream(local)) {
                    fos.write(bytes);
                }
                return new Fetched(local, false);
            } catch (Exception e) {
                local.delete(); // halb geschriebene Datei nicht stehen lassen
                allMissing &= saysNotFound(e);
            }
        }
        return new Fetched(null, allMissing);
    }

    /**
     * Wo ein Beleg des Jahres {@code y} liegen kann, in der Reihenfolge, in der gesucht wird.
     *
     * <ol>
     *   <li>Der <b>gültige</b> Belegordner – der Regelfall, und deshalb zuerst.</li>
     *   <li>Der <b>frühere</b> Ort (immer der Sync-Ordner), damit Uploads von vor der Umstellung auf
     *       den kmy-Modus erreichbar bleiben.</li>
     *   <li>Die <b>Ausgangsordner offener Ordnerwechsel</b>. Wer seinen Belegordner umbenennt, hat
     *       für eine Weile Belege an zwei Orten: Die Einstellung gilt sofort, der Umzug läuft im
     *       Hintergrund ({@link ReceiptFolderMove}). Ohne diesen dritten Ort wären genau die noch
     *       nicht umgezogenen Belege in dieser Zeit unauffindbar – und der Nutzer sähe „Beleg
     *       fehlt", obwohl nichts fehlt.</li>
     * </ol>
     *
     * <p>Die offenen Ausgangsordner tragen ihr Jahr bereits im Pfad; gefiltert wird deshalb auf das
     * gesuchte Jahr, statt eines anzuhängen. Ein Ordnerwechsel betrifft leicht mehrere Jahre, und
     * jeder zusätzliche Kandidat kostet im Fehlerfall eine Anfrage übers Netz.</p>
     *
     * <p>Doppelte fallen weg ({@link java.util.LinkedHashSet}): Ohne Umzug und ohne kmy-Modus sind
     * die ersten beiden Einträge derselbe Ordner, und zweimal dieselbe vergebliche Anfrage wäre nur
     * Wartezeit.</p>
     */
    private static java.util.Collection<String> searchFolders(Context ctx, SettingsStore settings,
                                                              int y) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        out.add(remoteBase(settings) + "/" + y);
        out.add(legacyBase(settings) + "/" + y);
        for (String folder : ReceiptFolderMove.openSourceFolders(ctx)) {
            if (folder.endsWith("/" + y)) {
                out.add(folder);
            }
        }
        return out;
    }

    /**
     * Heißt dieser Fehler „die Datei gibt es dort nicht"? Über WebDAV ist das ein HTTP 404; SMB meldet
     * dasselbe nur im Text seiner {@code IOException}. Im Zweifel lautet die Antwort {@code false} –
     * dann wird noch einmal versucht, und das ist der harmlosere Irrtum.
     */
    private static boolean saysNotFound(Exception e) {
        if (e instanceof de.spahr.ausgaben.net.HttpStatusException) {
            return ((de.spahr.ausgaben.net.HttpStatusException) e).code == 404;
        }
        String m = e == null || e.getMessage() == null ? "" : e.getMessage().toUpperCase(java.util.Locale.ROOT);
        return m.contains("STATUS_OBJECT_NAME_NOT_FOUND") || m.contains("STATUS_NO_SUCH_FILE");
    }

    /** Wie {@link #ensureLocal(Context, String, int)} mit dem Jahr aus dem Dateinamen (Altbelege). */
    public static File ensureLocal(Context context, String file) {
        return ensureLocal(context, file, -1);
    }

    /** Vom Aufrufer stellbarer Abbruch (z. B. wenn die Belegseite inzwischen recycelt wurde). */
    public interface Cancelled {
        boolean get();
    }

    /** Ergebnis von {@link #ensureLocalWaiting}. */
    public static final class Loaded {
        /** Die lokale Datei, sobald vorhanden; sonst {@code null}. */
        public final File file;
        /** {@code true} = keine Verbindung → der Aufrufer zeigt eine Fehlermeldung. */
        public final boolean offline;

        Loaded(File file, boolean offline) {
            this.file = file;
            this.offline = offline;
        }
    }

    private static final int MAX_ATTEMPTS = 6;
    private static final long RETRY_DELAY_MS = 3000L;

    /**
     * Wie {@link #ensureLocal}, aber wartend: bei bestehender Verbindung, aber (noch) nicht vorhandener
     * Datei wird mehrfach erneut versucht (der Aufrufer lässt derweil den Hinweis „Wird geladen …" stehen).
     * Ergebnis: Datei gefunden ({@code file != null}), keine Verbindung ({@code offline}) oder online, aber
     * (noch) nicht da ({@code file == null && !offline}) – dann bleibt es beim Hinweis, keine Fehlermeldung.
     * <b>Blockierend</b> – vom Aufrufer auf einem Hintergrund-Thread nutzen.
     */
    public static Loaded ensureLocalWaiting(Context context, String file, int year, Cancelled cancelled) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (cancelled != null && cancelled.get()) {
                return new Loaded(null, false);
            }
            File local = ensureLocal(context, file, year);
            if (local != null && local.exists()) {
                return new Loaded(local, false);
            }
            if (!de.spahr.ausgaben.net.Net.isOnline(context)) {
                return new Loaded(null, true);
            }
            if (attempt + 1 < MAX_ATTEMPTS) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new Loaded(null, false);
                }
            }
        }
        return new Loaded(null, false); // online, aber nicht auffindbar → Hinweis bleibt stehen
    }

    private static byte[] readAll(File f) throws java.io.IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
