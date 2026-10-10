package de.spahr.ausgaben.export;

import java.io.IOException;
import java.util.Arrays;

import de.spahr.ausgaben.net.RemoteStorage;
import de.spahr.ausgaben.net.SafeReplace;

/**
 * Was nach dem Export mit der zurückgelesenen Datei geschieht: Ist sie lesbar, gilt der Export. Kam sie
 * defekt an – abgeschnitten, beschädigt –, wird der Stand von vor dem Export zurück auf den Server
 * geschrieben.
 *
 * <p>Defekt heißt: Die Bytes sind nicht die hochgeladenen <b>und</b> sie lassen sich nicht als
 * KMyMoney-Datei lesen. Weichen sie nur ab, hat jemand anderes geschrieben; das ist dessen Stand und
 * wird nicht angefasst.</p>
 *
 * <p>Ohne Android.</p>
 */
public final class KmyRuecklesen {

    /** Liest die Bytes einer KMyMoney-Datei als XML; wirft, wenn das nicht geht. */
    public interface Leser {
        String alsXml(byte[] raw) throws Exception;
    }

    public enum Ausgang {
        /** Die Datei auf dem Server ist lesbar: der Export gilt. */
        LESBAR,
        /** Sie war defekt; jetzt liegt wieder der Stand von vor dem Export dort. */
        WIEDERHERGESTELLT,
        /** Sie war defekt, und der alte Stand ließ sich nicht zurückschreiben. */
        NICHT_WIEDERHERGESTELLT
    }

    public static final class Ergebnis {
        public final Ausgang ausgang;
        /** Was zuletzt vom Server gelesen wurde: die lesbare Datei oder die defekte. */
        public final byte[] bytes;
        /** Bei {@link Ausgang#NICHT_WIEDERHERGESTELLT}: woran es lag, soweit bekannt; sonst leer. */
        public final String grund;

        private Ergebnis(Ausgang ausgang, byte[] bytes, String grund) {
            this.ausgang = ausgang;
            this.bytes = bytes;
            this.grund = grund == null ? "" : grund;
        }
    }

    private static final String WURZEL = "<KMYMONEY-FILE";

    private KmyRuecklesen() {
    }

    /**
     * @param gelesen     was das erste Zurücklesen lieferte
     * @param hochgeladen was der Export hochgeladen hat
     * @param vorher      die Datei, wie sie vor dem Export auf dem Server lag
     * @param stamp       Zeitstempel für die Zwischennamen von {@link SafeReplace}
     */
    public static Ergebnis pruefe(RemoteStorage storage, String folder, String file, byte[] gelesen,
                                  byte[] hochgeladen, byte[] vorher, Leser leser, String stamp) {
        if (lesbar(gelesen, hochgeladen, leser)) {
            return new Ergebnis(Ausgang.LESBAR, gelesen, "");
        }
        // Ein zweites Mal lesen, bevor etwas überschrieben wird: Der Fehler kann auch auf dem Weg
        // hierher entstanden sein. Den Stand vorher merken – schreibt bis zum Zurückspielen jemand
        // anderes, bleibt dessen Datei stehen.
        String version;
        try {
            version = storage.fileVersion(folder, file);
        } catch (IOException | RuntimeException e) {
            version = "";
        }
        byte[] zweite;
        try {
            zweite = storage.downloadBytes(folder, file);
        } catch (IOException | RuntimeException e) {
            return new Ergebnis(Ausgang.NICHT_WIEDERHERGESTELLT, gelesen, grund(e));
        }
        if (lesbar(zweite, hochgeladen, leser)) {
            return new Ergebnis(Ausgang.LESBAR, zweite, "");
        }
        try {
            SafeReplace.replace(storage, folder, file, vorher, version, stamp);
            // Nachsehen statt annehmen: Diesmal muss genau der alte Stand zurückkommen.
            if (!Arrays.equals(storage.downloadBytes(folder, file), vorher)) {
                return new Ergebnis(Ausgang.NICHT_WIEDERHERGESTELLT, zweite, "");
            }
        } catch (IOException | RuntimeException e) {
            return new Ergebnis(Ausgang.NICHT_WIEDERHERGESTELLT, zweite, grund(e));
        }
        return new Ergebnis(Ausgang.WIEDERHERGESTELLT, zweite, "");
    }

    private static boolean lesbar(byte[] bytes, byte[] hochgeladen, Leser leser) {
        if (Arrays.equals(bytes, hochgeladen)) {
            // Genau die Bytes, die die Selbstprüfung freigegeben hat.
            return true;
        }
        try {
            String xml = leser.alsXml(bytes);
            return xml != null && xml.contains(WURZEL);
        } catch (Exception | OutOfMemoryError e) {
            return false;
        }
    }

    private static String grund(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
