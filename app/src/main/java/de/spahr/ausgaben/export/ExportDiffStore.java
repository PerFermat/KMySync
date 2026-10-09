package de.spahr.ausgaben.export;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Hebt die Vergleiche der letzten Exporte auf ({@link ExportDiff}), je Profil in einem eigenen Ordner.
 *
 * <p>Jeder Vergleich heißt wie die Sicherung, die derselbe Export vor dem Schreiben angelegt hat
 * ({@code <Datei>.bak-<Zeitstempel>}), und lebt so lange wie sie: aufgeräumt wird mit derselben Regel
 * ({@link KmyBackups#obsolete}). Zu jedem aufbewahrten Vergleich liegt damit im Backup-Ordner noch die
 * Datei, wie sie vor genau diesem Export war. Ohne Android.</p>
 */
public final class ExportDiffStore {

    private static final String ENDUNG = ".diff";

    /** Ein aufgehobener Vergleich, wie die Liste ihn zeigt. */
    public static final class Eintrag {
        /** Name zum Laden – der Name der zugehörigen Sicherung. */
        public final String name;
        /** Kopfdaten; die Zeilen sind hier nicht geladen. */
        public final ExportDiff kopf;

        Eintrag(String name, ExportDiff kopf) {
            this.name = name;
            this.kopf = kopf;
        }
    }

    private final File ordner;

    public ExportDiffStore(File ordner) {
        this.ordner = ordner;
    }

    /** Der Ordner eines Profils unterhalb des App-Speichers. */
    public static File ordnerFuer(File filesDir, String profileId) {
        return new File(new File(filesDir, "kmy-diffs"),
                profileId == null || profileId.isEmpty() ? "default" : profileId);
    }

    public void speichere(String name, ExportDiff diff) throws IOException {
        if (!ordner.isDirectory() && !ordner.mkdirs()) {
            throw new IOException("Ordner nicht anlegbar: " + ordner);
        }
        Files.write(new File(ordner, name + ENDUNG).toPath(),
                diff.alsText().getBytes(StandardCharsets.UTF_8));
    }

    /** Der ganze Vergleich, oder {@code null}, wenn es ihn nicht (mehr) gibt. */
    public ExportDiff lade(String name) {
        return lies(new File(ordner, name + ENDUNG), false);
    }

    /** Alle aufgehobenen Vergleiche, der jüngste zuerst. */
    public List<Eintrag> liste() {
        List<Eintrag> out = new ArrayList<>();
        for (String name : namen()) {
            ExportDiff kopf = lies(new File(ordner, name + ENDUNG), true);
            if (kopf != null) {
                out.add(new Eintrag(name, kopf));
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.kopf.zeit, a.kopf.zeit));
        return out;
    }

    /**
     * Wirft die Vergleiche zu {@code file} weg, deren Sicherung der Export ebenfalls weggeräumt hat.
     *
     * @return wie viele gelöscht wurden
     */
    public int raeumeAuf(String file, int keep) {
        int weg = 0;
        for (String name : KmyBackups.obsolete(namen(), file, keep)) {
            if (new File(ordner, name + ENDUNG).delete()) {
                weg++;
            }
        }
        return weg;
    }

    private List<String> namen() {
        List<String> out = new ArrayList<>();
        String[] dateien = ordner.list();
        if (dateien != null) {
            for (String d : dateien) {
                if (d.endsWith(ENDUNG)) {
                    out.add(d.substring(0, d.length() - ENDUNG.length()));
                }
            }
        }
        return out;
    }

    private static ExportDiff lies(File f, boolean nurKopf) {
        try {
            return ExportDiff.ausText(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8),
                    nurKopf);
        } catch (IOException e) {
            return null;
        }
    }
}
