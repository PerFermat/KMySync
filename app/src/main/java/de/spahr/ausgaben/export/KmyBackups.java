package de.spahr.ausgaben.export;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.spahr.ausgaben.net.RemoteStorage;

/**
 * Hält den Sicherungsordner neben der KMyMoney-Datei in Grenzen.
 *
 * <p>Jeder Export legt vor dem Schreiben eine vollständige Kopie als {@code <Datei>.bak-<Zeitstempel>}
 * ab. Gelöscht wurde davon bisher nie eine – nach gut hundert Exporten lagen dort 61 MB, darunter
 * Sicherungen längst nicht mehr benutzter Profile. Die jüngsten sind das, was im Ernstfall zählt; was
 * darunter liegt, ist Ballast.</p>
 *
 * <p>Die Auswahl ist eine reine Rechenregel und damit ohne Server prüfbar – das Löschen selbst braucht
 * nur noch die Liste.</p>
 */
public final class KmyBackups {

    /** So viele Sicherungen je Datei bleiben liegen. */
    public static final int KEEP = 20;

    /** Trennzeichen vor dem Zeitstempel: {@code michael.kmy.bak-20260914-111705}. */
    private static final String MARK = ".bak-";

    private KmyBackups() {
    }

    /**
     * Die Sicherungen zu {@code file}, die weg dürfen – alle außer den {@code keep} jüngsten.
     *
     * <p>Sortiert wird nach dem Namen, und das genügt: Der Zeitstempel {@code yyyyMMdd-HHmmss} ist so
     * gebaut, dass seine alphabetische Reihenfolge die zeitliche ist. Namen anderer Dateien im selben
     * Ordner (andere Profile) bleiben unberührt – jede Datei hat ihr eigenes Kontingent.</p>
     */
    public static List<String> obsolete(List<String> names, String file, int keep) {
        List<String> eigene = new ArrayList<>();
        if (names != null && file != null) {
            String prefix = file + MARK;
            for (String name : names) {
                if (name != null && name.startsWith(prefix)) {
                    eigene.add(name);
                }
            }
        }
        if (eigene.size() <= Math.max(0, keep)) {
            return Collections.emptyList();
        }
        Collections.sort(eigene, Collections.reverseOrder()); // jüngste zuerst
        return new ArrayList<>(eigene.subList(Math.max(0, keep), eigene.size()));
    }

    /**
     * Räumt den Sicherungsordner auf. Reines Beiwerk: Jeder Fehler wird verschluckt, denn ein Export
     * darf nicht daran scheitern, dass eine alte Sicherung nicht wegzubekommen ist.
     *
     * @return wie viele Sicherungen gelöscht wurden
     */
    public static int prune(RemoteStorage storage, String backupFolder, String file, int keep) {
        if (storage == null) {
            return 0;
        }
        List<String> names;
        try {
            names = storage.listAllFiles(backupFolder);
        } catch (Exception e) {
            return 0; // Auflisten nicht möglich – dann eben nicht aufräumen
        }
        int weg = 0;
        for (String name : obsolete(names, file, keep)) {
            try {
                storage.delete(backupFolder, name);
                weg++;
            } catch (Exception ignored) {
                // nächster Versuch beim nächsten Export
            }
        }
        return weg;
    }
}
