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

    /** So viele Sicherungen je Datei bleiben liegen, wenn das Profil nichts anderes sagt. */
    public static final int DEFAULT_KEEP = 20;
    /** Größter einstellbarer Wert; 0 heißt „nie löschen". */
    public static final int MAX_KEEP = 999;

    /** Trennzeichen vor dem Zeitstempel: {@code michael.kmy.bak-20260914-111705}. */
    private static final String MARK = ".bak-";

    private KmyBackups() {
    }

    /** Bringt eine eingegebene Zahl in den erlaubten Bereich. */
    public static int begrenzt(int keep) {
        return Math.max(0, Math.min(MAX_KEEP, keep));
    }

    private static List<String> eigene(List<String> names, String file) {
        List<String> out = new ArrayList<>();
        if (names != null && file != null) {
            String prefix = file + MARK;
            for (String name : names) {
                if (name != null && name.startsWith(prefix)) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    /**
     * Die Sicherungen zu {@code file}, die weg dürfen – alle außer den {@code keep} jüngsten. Mit
     * {@code keep = 0} keine: dann wird dauerhaft aufgehoben.
     *
     * <p>Sortiert wird nach dem Namen, und das genügt: Der Zeitstempel {@code yyyyMMdd-HHmmss} ist so
     * gebaut, dass seine alphabetische Reihenfolge die zeitliche ist. Namen anderer Dateien im selben
     * Ordner (andere Profile) bleiben unberührt – jede Datei hat ihr eigenes Kontingent.</p>
     */
    public static List<String> obsolete(List<String> names, String file, int keep) {
        List<String> eigene = eigene(names, file);
        if (keep <= 0 || eigene.size() <= keep) {
            return Collections.emptyList();
        }
        Collections.sort(eigene, Collections.reverseOrder()); // jüngste zuerst
        return new ArrayList<>(eigene.subList(keep, eigene.size()));
    }

    // ---- Monatssicherungen ----

    /** Der Monat einer Sicherung als {@code yyyyMM}; leer, wenn der Name keinen Zeitstempel trägt. */
    public static String monat(String name, String file) {
        String prefix = file + MARK;
        if (name == null || !name.startsWith(prefix) || name.length() < prefix.length() + 6) {
            return "";
        }
        String m = name.substring(prefix.length(), prefix.length() + 6);
        for (int i = 0; i < m.length(); i++) {
            if (m.charAt(i) < '0' || m.charAt(i) > '9') {
                return "";
            }
        }
        return m;
    }

    /** Liegt im Monatsordner schon eine Sicherung dieser Datei aus diesem Monat? */
    public static boolean hatMonat(List<String> imMonatsordner, String file, String monat) {
        if (monat == null || monat.isEmpty()) {
            return true;   // ohne Monat nichts zu sichern
        }
        for (String name : eigene(imMonatsordner, file)) {
            if (monat.equals(monat(name, file))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Welche der zum Löschen vorgesehenen Sicherungen stattdessen in den Monatsordner gehören: die
     * <b>älteste</b> ihres Monats, sofern der Monat dort noch fehlt. Je Monat höchstens eine.
     *
     * <p>So bekommen auch Monate ihre Sicherung, die vor dieser Regel lagen – soweit von ihnen noch
     * etwas da ist.</p>
     *
     * @param weg            was {@link #obsolete} zum Löschen vorsieht
     * @param alle           alle Namen im Sicherungsordner
     * @param imMonatsordner alle Namen im Monatsordner
     */
    public static List<String> zuVerschieben(List<String> weg, List<String> alle,
                                             List<String> imMonatsordner, String file) {
        List<String> out = new ArrayList<>();
        if (weg == null || weg.isEmpty()) {
            return out;
        }
        List<String> eigene = eigene(alle, file);
        Collections.sort(eigene);   // älteste zuerst
        java.util.Set<String> gesehen = new java.util.HashSet<>();
        for (String name : eigene) {
            String monat = monat(name, file);
            if (monat.isEmpty() || !gesehen.add(monat)) {
                continue;   // nicht die erste ihres Monats
            }
            if (weg.contains(name) && !hatMonat(imMonatsordner, file, monat)) {
                out.add(name);
            }
        }
        return out;
    }

    private static List<String> liste(RemoteStorage storage, String folder) {
        try {
            List<String> names = storage.listAllFiles(folder);
            return names == null ? new ArrayList<>() : names;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Legt die eben erzeugte Sicherung zusätzlich im Monatsordner ab, wenn es dort für diesen Monat
     * noch keine gibt – sie ist dann die erste des Monats und bleibt für immer. Beiwerk: Fehler
     * bleiben folgenlos, der nächste Export versucht es wieder.
     *
     * <p>Kopiert wird <b>auf dem Server</b>: Die Sicherung liegt dort ja schon, und eine KMyMoney-
     * Datenbank ein zweites Mal durch die Leitung zu schicken, kostete jedes Mal mehrere Megabyte.
     * Nur wenn der Server das Kopieren nicht kann, wird hochgeladen.</p>
     *
     * @param backupFolder der Ordner, in dem {@code backupName} schon liegt
     * @param bytes        ihr Inhalt, für den Rückfall
     * @return ob eine Monatssicherung angelegt wurde
     */
    public static boolean sichereMonat(RemoteStorage storage, String backupFolder, String monatsOrdner,
                                       String file, String backupName, byte[] bytes) {
        if (storage == null) {
            return false;
        }
        try {
            storage.ensureFolder(monatsOrdner);
            List<String> da = liste(storage, monatsOrdner);
            if (da == null || hatMonat(da, file, monat(backupName, file))) {
                return false;   // nicht zu klären oder schon vorhanden
            }
            try {
                storage.copy(backupFolder, backupName, monatsOrdner, backupName);
            } catch (Exception e) {
                storage.uploadBytes(monatsOrdner, backupName, bytes);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Räumt den Sicherungsordner auf. Reines Beiwerk: Jeder Fehler wird verschluckt, denn ein Export
     * darf nicht daran scheitern, dass eine alte Sicherung nicht wegzubekommen ist.
     *
     * <p>Was gelöscht würde, aber die erste Sicherung ihres Monats ist, wandert stattdessen in den
     * Monatsordner. Dort wird nie gelöscht.</p>
     *
     * @param monatsOrdner {@code null} = keine Monatssicherungen, nur löschen
     * @return wie viele Sicherungen gelöscht wurden
     */
    public static int prune(RemoteStorage storage, String backupFolder, String monatsOrdner,
                            String file, int keep) {
        if (storage == null) {
            return 0;
        }
        List<String> names = liste(storage, backupFolder);
        if (names == null) {
            return 0; // Auflisten nicht möglich – dann eben nicht aufräumen
        }
        List<String> weg = obsolete(names, file, keep);
        if (weg.isEmpty()) {
            return 0;
        }
        java.util.Set<String> bleibt = new java.util.HashSet<>();
        if (monatsOrdner != null) {
            List<String> monatlich = liste(storage, monatsOrdner);
            if (monatlich == null) {
                // Ohne Blick in den Monatsordner ließe sich nicht sagen, was dorthin gehört – dann
                // lieber diesmal gar nichts löschen, als die erste Sicherung eines Monats zu verlieren.
                return 0;
            }
            for (String name : zuVerschieben(weg, names, monatlich, file)) {
                try {
                    storage.ensureFolder(monatsOrdner);
                    storage.move(backupFolder, name, monatsOrdner, name);
                } catch (Exception e) {
                    // nicht verschoben: liegen lassen, nächster Versuch beim nächsten Export
                }
                bleibt.add(name);
            }
        }
        int geloescht = 0;
        for (String name : weg) {
            if (bleibt.contains(name)) {
                continue;
            }
            try {
                storage.delete(backupFolder, name);
                geloescht++;
            } catch (Exception ignored) {
                // nächster Versuch beim nächsten Export
            }
        }
        return geloescht;
    }
}
