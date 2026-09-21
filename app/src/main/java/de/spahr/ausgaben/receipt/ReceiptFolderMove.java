package de.spahr.ausgaben.receipt;

import android.content.Context;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.spahr.ausgaben.net.RemoteStorage;
import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Führt einen Wechsel des Belegordners aus – die Dateien wandern vom alten in den neuen Ordner.
 *
 * <h2>Warum der Umzug nicht beim Umstellen passiert</h2>
 *
 * <p>Wer in den Einstellungen einen anderen Belegordner einträgt, soll die Maske schließen können,
 * ohne auf das Netz zu warten; im Funkloch käme er sonst gar nicht durch. Die Einstellung gilt
 * deshalb <b>sofort</b>, und der Umzug steht als Vorsatz in {@link Receipts#folderMoves(Context)},
 * bis er erledigt ist – dieselbe Bauart wie die offenen Jahreswechsel in {@link ReceiptPages}.</p>
 *
 * <p>Der Preis dafür ist eine Übergangszeit, in der die Belege noch am alten Ort liegen, während die
 * Einstellung schon auf den neuen zeigt. Dafür sucht {@link ReceiptPages} zusätzlich in den
 * Ausgangsordnern der offenen Einträge ({@link #openSourceFolders(Context)}) – ohne das wären genau
 * die noch nicht umgezogenen Belege vorübergehend unauffindbar.</p>
 */
public final class ReceiptFolderMove {

    private static final String TAG = "ReceiptFolderMove";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private ReceiptFolderMove() {
    }

    /**
     * Merkt den Umzug aller Belege <b>dieses</b> Profils von {@code altBase} nach {@code neuBase} vor
     * und stößt ihn an.
     *
     * <p>Welche Dateien dem Profil gehören, sagt seine eigene Datenbank – über denselben
     * {@code BELEG:}-Tag, an dem sich auch {@link ReceiptGc} orientiert. Fremde Dateien im alten
     * Ordner bleiben damit unangetastet: Genau darum geht es ja bei einem geteilten Belegordner.</p>
     *
     * <p>Vorgemerkt wird je Jahresordner, weil die Belege dort einsortiert sind und der Zielordner
     * dieselbe Jahresstruktur bekommen soll. Der {@code Papierkorb} bleibt außen vor – was dort
     * liegt, wurde bereits einmal weggeräumt und soll nicht mit umziehen.</p>
     *
     * <p><b>Blockierend</b> (Netz und Datenbank) – nur von einem Hintergrund-Thread aufrufen.</p>
     *
     * @return wie viele Dateien vorgemerkt wurden
     */
    public static int scheduleMove(Context context, String altBase, String neuBase) {
        Context app = context.getApplicationContext();
        if (altBase == null || neuBase == null || altBase.equals(neuBase)) {
            return 0;
        }
        SettingsStore settings = new SettingsStore(app);
        if (!settings.hasRemoteConfig()) {
            return 0;
        }
        RemoteStorage storage;
        try {
            storage = RemoteStorage.from(settings);
        } catch (Exception e) {
            return 0;
        }
        de.spahr.ausgaben.db.AppDatabase db = de.spahr.ausgaben.db.AppDatabase.getInstance(app);
        java.util.Set<String> eigene = ReceiptGc.basesOf(db.bookingDao().getReceiptNotes());
        eigene.addAll(ReceiptGc.basesOf(db.securityDao().getReceiptNotes()));
        if (eigene.isEmpty()) {
            return 0;
        }
        int vorgemerkt = 0;
        for (String jahresOrdner : ReceiptGc.yearFolders(storage, altBase)) {
            String jahr = jahresOrdner.substring(jahresOrdner.lastIndexOf('/') + 1);
            List<String> namen;
            try {
                namen = storage.listAllFiles(jahresOrdner);
            } catch (Exception e) {
                continue; // dieses Jahr gerade nicht lesbar – der Rest zieht trotzdem um
            }
            for (String name : namen) {
                if (eigene.contains(NoteReceipt.baseOf(name))) {
                    Receipts.addFolderMove(app, name, jahresOrdner, neuBase + "/" + jahr);
                    vorgemerkt++;
                }
            }
        }
        movePending(app);
        return vorgemerkt;
    }

    /** Stößt die Abarbeitung im Hintergrund an (No-op, wenn nichts offen ist). */
    public static void run(Context context) {
        final Context app = context.getApplicationContext();
        if (Receipts.folderMoves(app).isEmpty()) {
            return;
        }
        IO.execute(() -> {
            try {
                movePending(app);
            } catch (Exception e) {
                // Beiwerk: Ein Fehlschlag darf den App-Start nicht stören, der Vorsatz bleibt stehen.
            }
        });
    }

    /**
     * Arbeitet die vorgemerkten Ordnerwechsel ab, so weit es geht.
     *
     * <p><b>Blockierend</b> – nur von einem Hintergrund-Thread aufrufen.</p>
     */
    public static void movePending(Context context) {
        Context app = context.getApplicationContext();
        if (Receipts.folderMoves(app).isEmpty()) {
            return;
        }
        SettingsStore settings = new SettingsStore(app);
        if (!settings.hasRemoteConfig()) {
            return;
        }
        RemoteStorage storage;
        try {
            storage = RemoteStorage.from(settings);
        } catch (Exception e) {
            return;
        }
        movePending(app, storage);
    }

    /**
     * Dasselbe mit bereits aufgebauter Verbindung.
     *
     * <p>Paketsichtbar, damit {@code ReceiptFolderMoveRetryTest} den Fehlerfall festhalten kann –
     * ohne diese Naht ließe sich nur mit echtem Server prüfen, was bei einem fehlgeschlagenen
     * Verschieben passiert. Genau dort saß der Fehler.</p>
     */
    static void movePending(Context app, RemoteStorage storage) {
        Set<String> offen = Receipts.folderMoves(app);
        for (String entry : offen) {
            String[] teile = Receipts.folderMoveParts(entry);
            if (teile == null) {
                Receipts.removeFolderMove(app, entry); // unbrauchbar – nicht endlos mitschleppen
                continue;
            }
            String von = teile[0];
            String nach = teile[1];
            String name = teile[2];
            try {
                storage.ensureFolder(nach);
                storage.move(von, name, nach, name);
                Receipts.removeFolderMove(app, entry);
            } catch (Exception e) {
                // Hier liegt der Unterschied zum Jahreswechsel, der den Eintrag bei bestehender
                // Verbindung wegwirft. Dort ist ein Fehlschlag der Regelfall: Ein „Original" gibt es
                // oft gar nicht, und eine nie hochgeladene Datei liegt auch nicht im alten Ordner.
                // Hier dagegen wurde die Datei vorher aufgelistet (scheduleMove) – sie ist da. Ein
                // Fehlschlag heißt also „später noch einmal", nicht „nichts zu tun".
                //
                // Genau daran ist der erste Umzug gescheitert: Der Zielordner existierte noch nicht
                // (ensureFolder war auf SMB ein No-op), der rename schlug fehl, und weil das Gerät
                // online war, verschwand der Vorsatz sofort und für immer.
                //
                // Die eine Ausnahme ist „Quelle gibt es nicht": Dann ist die Datei entweder längst
                // umgezogen oder gelöscht – in beiden Fällen bleibt nichts zu tun, und der Eintrag
                // soll nicht ewig mitlaufen.
                if (ReceiptSync.saysNotFound(e)) {
                    Receipts.removeFolderMove(app, entry);
                } else {
                    android.util.Log.w(TAG, "Beleg " + name + " zieht noch nicht nach " + nach
                            + " um – bleibt vorgemerkt", e);
                }
            }
        }
    }

    /**
     * Die Ausgangsordner aller noch offenen Wechsel – dort ist zusätzlich nachzusehen, solange der
     * Umzug läuft.
     *
     * <p>Als Menge, nicht als Liste: Ein Ordnerwechsel betrifft regelmäßig hunderte Dateien aus
     * einer Handvoll Jahresordner, und gesucht wird je Beleg einmal.</p>
     */
    public static java.util.Set<String> openSourceFolders(Context context) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String entry : Receipts.folderMoves(context.getApplicationContext())) {
            String[] teile = Receipts.folderMoveParts(entry);
            if (teile != null) {
                out.add(teile[0]);
            }
        }
        return out;
    }
}
