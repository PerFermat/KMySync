package de.spahr.ausgaben.net;

import java.io.IOException;
import java.util.List;

import de.spahr.ausgaben.util.ForegroundGate;
import de.spahr.ausgaben.util.ProgressListener;

/**
 * Ein Speicher, der im Hintergrund wartet, statt zu scheitern.
 *
 * <p>Android drosselt das Netz einer App, die nicht vorn ist – gesperrtes Gerät, andere App. Ein
 * Export, der davon nichts weiß, bricht dann mittendrin ab. Hier wartet jeder Zugriff, bis die App
 * wieder zu sehen ist ({@link ForegroundGate}), und läuft dann weiter.</p>
 *
 * <p>Reißt ein Zugriff ab, <b>während</b> die App im Hintergrund ist, wird nach der Rückkehr einmal
 * wiederholt – aber nur, was sich gefahrlos wiederholen lässt: Lesen und das Hochladen einer ganzen
 * Datei. Umbenennen und Löschen nicht: Ob der Server dort schon gehandelt hat, bevor die Antwort
 * ausblieb, klärt der Aufrufer selbst ({@link SafeReplace} sieht nach, was im Ordner liegt), und ein
 * blindes zweites Mal könnte genau das durcheinanderbringen.</p>
 *
 * <p>Ohne Android.</p>
 */
public final class WaitingStorage implements RemoteStorage {

    /** Erfährt, wenn ein Zugriff auf den Vordergrund wartet und wenn es weitergeht. */
    public interface Beobachter {
        void wartet(boolean ja);
    }

    /** So lange wird gewartet; Android darf einen Hintergrundprozess jederzeit abräumen. */
    public static final long FRIST_MS = 10 * 60 * 1000L;

    private interface Zugriff<T> {
        T tu() throws IOException;
    }

    private final RemoteStorage innen;
    private final long fristMs;
    private final String zuLange;
    private final Beobachter beobachter;

    /**
     * @param zuLange    Text der Ausnahme, wenn die Frist abläuft
     * @param beobachter darf {@code null} sein
     */
    public WaitingStorage(RemoteStorage innen, long fristMs, String zuLange, Beobachter beobachter) {
        this.innen = innen;
        this.fristMs = fristMs;
        this.zuLange = zuLange;
        this.beobachter = beobachter;
    }

    private void warte() throws IOException {
        if (ForegroundGate.isForeground()) {
            return;
        }
        if (beobachter != null) {
            beobachter.wartet(true);
        }
        try {
            if (!ForegroundGate.awaitForeground(fristMs)) {
                throw new IOException(zuLange);
            }
        } finally {
            if (beobachter != null) {
                beobachter.wartet(false);
            }
        }
    }

    /** Ein Zugriff, der sich wiederholen lässt. */
    private <T> T wiederholbar(Zugriff<T> z) throws IOException {
        warte();
        try {
            return z.tu();
        } catch (RemoteConflictException e) {
            throw e;   // eine Auskunft des Servers, kein Abriss
        } catch (IOException e) {
            if (ForegroundGate.isForeground()) {
                throw e;   // vorn gescheitert: Das liegt nicht am Hintergrund.
            }
            warte();
            return z.tu();
        }
    }

    /** Ein Zugriff, der höchstens einmal geschehen darf. */
    private <T> T einmal(Zugriff<T> z) throws IOException {
        warte();
        return z.tu();
    }

    // ---- Lesen ----

    @Override
    public String downloadText(String folder, String fileName) throws IOException {
        return wiederholbar(() -> innen.downloadText(folder, fileName));
    }

    @Override
    public byte[] downloadBytes(String folder, String fileName) throws IOException {
        return wiederholbar(() -> innen.downloadBytes(folder, fileName));
    }

    @Override
    public byte[] downloadBytes(String folder, String fileName, ProgressListener listener)
            throws IOException {
        return wiederholbar(() -> innen.downloadBytes(folder, fileName, listener));
    }

    @Override
    public String fileVersion(String folder, String fileName) throws IOException {
        return wiederholbar(() -> innen.fileVersion(folder, fileName));
    }

    @Override
    public long fileSize(String folder, String fileName) throws IOException {
        return wiederholbar(() -> innen.fileSize(folder, fileName));
    }

    @Override
    public List<String> listFiles(String folder, String ext) throws IOException {
        return wiederholbar(() -> innen.listFiles(folder, ext));
    }

    @Override
    public List<String> listAllFiles(String folder) throws IOException {
        return wiederholbar(() -> innen.listAllFiles(folder));
    }

    @Override
    public List<String> listFolders(String folder) throws IOException {
        return wiederholbar(() -> innen.listFolders(folder));
    }

    @Override
    public Entries listEntries(String folder, String ext) throws IOException {
        return wiederholbar(() -> innen.listEntries(folder, ext));
    }

    @Override
    public void testConnection() throws IOException {
        wiederholbar(() -> {
            innen.testConnection();
            return null;
        });
    }

    // ---- Schreiben einer ganzen Datei: ein zweites Mal ergibt dasselbe ----

    @Override
    public void uploadText(String folder, String fileName, String content) throws IOException {
        wiederholbar(() -> {
            innen.uploadText(folder, fileName, content);
            return null;
        });
    }

    @Override
    public void uploadBytes(String folder, String fileName, byte[] content) throws IOException {
        wiederholbar(() -> {
            innen.uploadBytes(folder, fileName, content);
            return null;
        });
    }

    @Override
    public void ensureFolder(String folder) throws IOException {
        wiederholbar(() -> {
            innen.ensureFolder(folder);
            return null;
        });
    }

    // ---- Was höchstens einmal geschehen darf ----

    /** Bedingtes Schreiben: Nach einem Abriss ist offen, ob der Stand sich schon geändert hat. */
    @Override
    public void uploadBytes(String folder, String fileName, byte[] content, String expectedVersion)
            throws IOException {
        einmal(() -> {
            innen.uploadBytes(folder, fileName, content, expectedVersion);
            return null;
        });
    }

    @Override
    public void delete(String folder, String fileName) throws IOException {
        einmal(() -> {
            innen.delete(folder, fileName);
            return null;
        });
    }

    @Override
    public void move(String folder, String fromName, String toName) throws IOException {
        einmal(() -> {
            innen.move(folder, fromName, toName);
            return null;
        });
    }

    @Override
    public void move(String fromFolder, String fromName, String toFolder, String toName)
            throws IOException {
        einmal(() -> {
            innen.move(fromFolder, fromName, toFolder, toName);
            return null;
        });
    }

    @Override
    public void moveNoReplace(String folder, String fromName, String toName, String expectedVersion)
            throws IOException {
        einmal(() -> {
            innen.moveNoReplace(folder, fromName, toName, expectedVersion);
            return null;
        });
    }
}
