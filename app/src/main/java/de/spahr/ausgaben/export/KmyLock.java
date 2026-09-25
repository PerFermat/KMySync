package de.spahr.ausgaben.export;

import java.util.List;

import de.spahr.ausgaben.net.RemoteStorage;

/**
 * Erkennt, ob KMyMoney die Datei gerade geöffnet hat.
 *
 * <p>Ein geöffnetes KMyMoney hält die Datei im Speicher und schreibt sie beim Speichern komplett neu –
 * ohne nachzusehen, ob sie sich inzwischen geändert hat. Ein Export in dieser Zeit wäre beim nächsten
 * Speichern am Rechner still verloren, und die App schickte die Buchungen nie wieder, weil sie sie für
 * exportiert hält.</p>
 *
 * <p>Ab KMyMoney 5.2 legt es beim Öffnen {@code <Datei>.lck} neben die Datei ({@code QLockFile} in
 * {@code plugins/xml/xmlstorage.cpp}) und räumt sie beim Schließen weg – allerdings nur, wenn es die
 * Datei über einen Dateipfad öffnet; bei {@code smb://}- oder {@code webdavs://}-Adressen arbeitet es
 * auf einer heruntergeladenen Kopie. Ältere Fassungen sperren gar nicht. Die Prüfung schützt also
 * nicht in jedem Fall, schadet aber in keinem.</p>
 */
public final class KmyLock {

    /** Endung der Sperrdatei, die {@code QLockFile} anlegt. */
    static final String LOCK_SUFFIX = ".lck";

    private KmyLock() {
    }

    /** Liegt unter {@code names} die Sperrdatei zu {@code file}? */
    public static boolean isLocked(List<String> names, String file) {
        return names != null && file != null && names.contains(file + LOCK_SUFFIX);
    }

    /**
     * Sieht auf dem Server nach, ob neben {@code file} eine Sperrdatei liegt.
     *
     * <p>Lässt sich der Ordner nicht auflisten, gilt die Datei als frei: Die Prüfung ist ein
     * zusätzlicher Schutz und darf einen Export nicht verhindern, der bisher funktioniert hat.</p>
     */
    public static boolean isOpenInKmyMoney(RemoteStorage storage, String folder, String file) {
        try {
            return isLocked(storage.listAllFiles(folder), file);
        } catch (Exception e) {
            return false;
        }
    }
}
