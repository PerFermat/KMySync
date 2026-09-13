package de.spahr.ausgaben.receipt;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.spahr.ausgaben.db.AppDatabase;
import de.spahr.ausgaben.net.RemoteStorage;
import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Räumt Belegdateien auf, zu denen es keine Buchung mehr gibt – etwa nach dem Löschen einer Buchung.
 *
 * <p>Bewusst <b>nicht</b> direkt beim Löschen: die Liste bietet danach „Rückgängig" an, und eine
 * wiederhergestellte Buchung ohne ihre Bilder wäre schlimmer als ein paar verwaiste Dateien. Der Lauf
 * startet stattdessen beim App-Start; bis dahin ist die Rücknahme längst vorbei.</p>
 */
public final class ReceiptGc {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private ReceiptGc() {
    }

    /** Verhindert einen zweiten Lauf im selben Prozess – siehe {@link #runOncePerStart}. */
    private static final java.util.concurrent.atomic.AtomicBoolean DONE =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Räumt <b>einmal je App-Start</b> auf. Wichtig, weil {@code onResume} auch beim Zurückkommen aus dem
     * Buchungseditor läuft: liefe der Lauf dort erneut, wären die Bilder einer gerade gelöschten Buchung
     * weg, bevor der Nutzer „Rückgängig" antippen kann.
     */
    public static void runOncePerStart(Context context) {
        if (DONE.compareAndSet(false, true)) {
            run(context);
        }
    }

    /** Stößt den Aufräumlauf im Hintergrund an. */
    public static void run(Context context) {
        final Context ctx = context.getApplicationContext();
        IO.execute(() -> {
            try {
                sweep(ctx);
            } catch (Exception e) {
                // Aufräumen ist Beiwerk – ein Fehler darf den App-Start nicht stören.
            }
        });
    }

    private static void sweep(Context ctx) {
        AppDatabase db = AppDatabase.getInstance(ctx);
        // Sicherheitsleine: ohne Buchungen (frische Installation vor dem ersten Import) wird nichts
        // gelöscht – sonst räumte der Lauf den ganzen Beleg-Ordner auf dem Server aus.
        if (db.bookingDao().countAll() == 0) {
            return;
        }
        // Beide Seiten zählen: Eine Depotbewegung trägt ihren Beleg-Tag selbst, und bei den aus
        // KMyMoney eingelesenen gibt es gar keine Geldbuchung dazu (booking_id = 0). Fragte der Lauf
        // nur die Buchungen, hielte er jede Wertpapierabrechnung für verwaist – und löschte sie, sobald
        // sie zum Ansehen einmal heruntergeladen wurde, lokal und in allen Jahresordnern des Servers.
        Set<String> keep = basesOf(db.bookingDao().getReceiptNotes());
        keep.addAll(basesOf(db.securityDao().getReceiptNotes()));
        Set<String> pending = new HashSet<>();
        for (String entry : Receipts.pending(ctx)) {
            pending.add(Receipts.entryFile(entry));
        }
        File[] files = Receipts.dir(ctx).listFiles();
        if (files == null) {
            return;
        }
        List<String> orphans = orphans(names(files), keep);
        if (orphans.isEmpty()) {
            return;
        }
        RemoteStorage storage = remote(ctx);
        String base = storage == null ? null : ReceiptSync.remoteBase(new SettingsStore(ctx));
        List<String> yearFolders = yearFolders(storage, base);
        for (String name : orphans) {
            if (pending.contains(name)) {
                continue; // noch nicht hochgeladen – die gehört noch jemandem
            }
            Receipts.localFile(ctx, name).delete();
            Receipts.removePending(ctx, name);
            // Der Dateiname trägt kein Jahr mehr, also in allen Jahresordnern löschen – ein Fehlschlag
            // (Datei liegt dort nicht) ist harmlos.
            for (String folder : yearFolders) {
                try {
                    storage.delete(folder, name);
                } catch (Exception e) {
                    // offline oder schon weg – beim nächsten Lauf erneut
                }
            }
        }
    }

    /** Die vorhandenen Jahresordner unter {@code base}; leer, wenn es keinen Server gibt. */
    private static List<String> yearFolders(RemoteStorage storage, String base) {
        List<String> out = new ArrayList<>();
        if (storage == null) {
            return out;
        }
        try {
            for (String name : storage.listFolders(base)) {
                out.add(base + "/" + name);
            }
        } catch (Exception e) {
            // Ordner (noch) nicht da oder offline – dann bleibt es beim lokalen Aufräumen.
        }
        return out;
    }

    /**
     * Die Dateinamen, die zu keiner der noch benötigten Basen gehören. Rein und testbar – die
     * {@code _original}-Dateien und alle Seiten einer Basis fallen automatisch mit.
     */
    public static List<String> orphans(Collection<String> fileNames, Set<String> keepBases) {
        List<String> out = new ArrayList<>();
        for (String name : fileNames) {
            // Zwischendateien einer laufenden Aufnahme gehören dem Editor, nicht dem Aufräumlauf.
            if (name == null || name.startsWith("pend_") || name.startsWith("cam_")) {
                continue;
            }
            if (!keepBases.contains(NoteReceipt.baseOf(name))) {
                out.add(name);
            }
        }
        return out;
    }

    /** Die Basen aller Beleg-Verweise in den übergebenen Notizen. */
    public static Set<String> basesOf(Collection<String> notes) {
        Set<String> bases = new HashSet<>();
        if (notes == null) {
            return bases;
        }
        for (String note : notes) {
            // Beide Beleg-Arten: ein PDF gilt ebenso als benötigt wie eine Fotoseite.
            for (String tag : new String[]{NoteReceipt.fileName(note), NoteReceipt.pdfName(note)}) {
                if (tag != null) {
                    bases.add(NoteReceipt.baseOf(tag));
                }
            }
        }
        return bases;
    }

    private static List<String> names(File[] files) {
        List<String> out = new ArrayList<>(files.length);
        for (File f : files) {
            if (f.isFile()) {
                out.add(f.getName());
            }
        }
        return out;
    }

    private static RemoteStorage remote(Context ctx) {
        SettingsStore settings = new SettingsStore(ctx);
        if (!settings.hasRemoteConfig()) {
            return null;
        }
        try {
            return RemoteStorage.from(settings);
        } catch (Exception e) {
            return null;
        }
    }
}
