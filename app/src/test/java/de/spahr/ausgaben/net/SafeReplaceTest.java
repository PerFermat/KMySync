package de.spahr.ausgaben.net;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Nagelt das Versprechen fest, an dem der Verlust einer .kmy hing: <b>was auch schiefgeht, unter einem
 * bekannten Namen liegt immer ein vollständiger Stand</b>. Ohne Emulator und ohne Mock-Bibliothek – die
 * Attrappe unten ist eine gewöhnliche Map, die auf Wunsch scheitert, kürzt oder Antworten verliert.
 */
public class SafeReplaceTest {

    private static final byte[] ALT = "die gute alte Datei".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEU = "der frische Stand".getBytes(StandardCharsets.UTF_8);

    private static final String STAMP = "20260925-132129";
    private static final String TMP = "michael.kmy." + STAMP + ".tmp";
    private static final String OLD = "michael.kmy." + STAMP + ".old";

    private static FakeStorage mitAlterDatei() {
        FakeStorage s = new FakeStorage();
        s.files.put("michael.kmy", ALT);
        return s;
    }

    @Test
    public void erfolg_zieldateiTraegtDenNeuenInhalt() throws Exception {
        FakeStorage s = mitAlterDatei();

        SafeReplace.replace(s, "", "michael.kmy", NEU, "", STAMP);

        assertArrayEquals(NEU, s.files.get("michael.kmy"));
        assertTrue("keine Zwischendatei darf zurückbleiben", tmpNames(s).isEmpty());
        assertTrue("die alte Datei ist nach dem Tausch weg", oldNames(s).isEmpty());
    }

    @Test
    public void abbruchBeimSchreiben_alteDateiBleibtBytegleich() {
        FakeStorage s = mitAlterDatei();
        s.failOnUpload = true; // wie ein Timeout mitten im Hochladen

        erwarte(IOException.class, s, "");

        assertArrayEquals("die vorhandene Datei darf nicht angefasst worden sein",
                ALT, s.files.get("michael.kmy"));
        assertTrue("die halbe Zwischendatei muss weg sein", tmpNames(s).isEmpty());
    }

    /** Ein Proxy kürzt und meldet trotzdem Erfolg: Die gekürzte Datei darf nie an ihren Platz. */
    @Test
    public void gekuerzterUpload_wirdNichtEingesetzt() {
        FakeStorage s = mitAlterDatei();
        s.kuerztUpload = true;

        erwarte(IOException.class, s, "");

        assertArrayEquals(ALT, s.files.get("michael.kmy"));
        assertTrue(tmpNames(s).isEmpty());
        assertTrue(oldNames(s).isEmpty());
    }

    @Test
    public void abbruchBeimUmbenennen_alteDateiBleibtBytegleich() {
        FakeStorage s = mitAlterDatei();
        s.failMoveFrom.add("michael.kmy");

        erwarte(RemoteMoveException.class, s, "");

        assertArrayEquals(ALT, s.files.get("michael.kmy"));
        assertTrue(tmpNames(s).isEmpty());
        assertTrue(oldNames(s).isEmpty());
    }

    /**
     * Der Fall vom 25.09.2026: Das Einsetzen der neuen Datei scheitert. Die alte kommt zurück an ihren
     * Platz – Stand wie vor dem Export.
     */
    @Test
    public void einsetzenScheitert_alteDateiKommtZurueck() {
        FakeStorage s = mitAlterDatei();
        s.failMoveFrom.add(TMP);

        erwarte(RemoteMoveException.class, s, "");

        assertArrayEquals("die alte Datei muss wieder an ihrem Platz stehen",
                ALT, s.files.get("michael.kmy"));
        assertTrue(tmpNames(s).isEmpty());
        assertTrue(oldNames(s).isEmpty());
    }

    /** Scheitert auch das Zurückbenennen, wird nichts gelöscht – beide Stände bleiben liegen. */
    @Test
    public void auchZurueckbenennenScheitert_beideStaendeBleibenErhalten() {
        FakeStorage s = mitAlterDatei();
        s.failMoveFrom.add(TMP);
        s.failMoveFrom.add(OLD);

        RemoteReplaceStuckException stuck = erwarte(RemoteReplaceStuckException.class, s, "");

        assertEquals(OLD, stuck.oldName);
        assertEquals("michael.kmy", stuck.file);
        assertArrayEquals(ALT, s.files.get(OLD));
        assertArrayEquals(NEU, s.files.get(TMP));
    }

    /**
     * Der Server benennt die alte Datei um, die Antwort geht verloren. Früher hätte der Code
     * „unverändert" gemeldet und die neue Datei weggeworfen – unter dem eigentlichen Namen läge dann
     * nichts. Jetzt sieht er nach und macht weiter.
     */
    @Test
    public void antwortVerlorenBeimBeiseitelegen_exportLaeuftDurch() throws Exception {
        FakeStorage s = mitAlterDatei();
        s.antwortVerlorenBeiMove.add("michael.kmy");

        SafeReplace.replace(s, "", "michael.kmy", NEU, "", STAMP);

        assertArrayEquals(NEU, s.files.get("michael.kmy"));
        assertTrue(tmpNames(s).isEmpty());
        assertTrue(oldNames(s).isEmpty());
    }

    /** Die neue Datei steht schon, nur die Antwort fehlt: Das ist ein Erfolg, kein Rückbau. */
    @Test
    public void antwortVerlorenBeimEinsetzen_exportLaeuftDurch() throws Exception {
        FakeStorage s = mitAlterDatei();
        s.antwortVerlorenBeiMove.add(TMP);

        SafeReplace.replace(s, "", "michael.kmy", NEU, "", STAMP);

        assertArrayEquals(NEU, s.files.get("michael.kmy"));
        assertTrue(tmpNames(s).isEmpty());
        assertTrue(oldNames(s).isEmpty());
    }

    /** Lässt sich nach einem Fehlschlag nicht feststellen, was liegt, wird nichts gelöscht. */
    @Test
    public void zustandNichtLesbar_nichtsWirdGeloescht() {
        FakeStorage s = mitAlterDatei();
        s.antwortVerlorenBeiMove.add("michael.kmy");
        s.listenScheitert = true;

        erwarte(RemoteReplaceStuckException.class, s, "");

        assertArrayEquals("der alte Stand liegt unter seinem Zwischennamen", ALT, s.files.get(OLD));
        assertArrayEquals("die neue Fassung wurde nicht weggeräumt", NEU, s.files.get(TMP));
    }

    /**
     * Die Stand-Prüfung sitzt im Umbenennen selbst: Ändert jemand die Datei nach der Frühwarnung, aber
     * vor dem Beiseitelegen, wird nichts angefasst.
     */
    @Test
    public void aenderungKurzVorDemTausch_wirdImUmbenennenErkannt() {
        FakeStorage s = mitAlterDatei();
        s.version = "etag-alt";
        s.versionNachFruehwarnung = "etag-neu";

        erwarte(RemoteConflictException.class, s, "etag-alt");

        assertArrayEquals(ALT, s.files.get("michael.kmy"));
        assertTrue(tmpNames(s).isEmpty());
        assertTrue(oldNames(s).isEmpty());
    }

    @Test
    public void fremdeAenderung_meldetKonfliktUndSchreibtNicht() {
        FakeStorage s = mitAlterDatei();
        s.version = "etag-neu"; // erwartet wurde „etag-alt"

        erwarte(RemoteConflictException.class, s, "etag-alt");

        assertArrayEquals(ALT, s.files.get("michael.kmy"));
        assertTrue(tmpNames(s).isEmpty());
    }

    @Test
    public void aufraeumen_entferntNurEigeneReste() {
        FakeStorage s = mitAlterDatei();
        s.files.put("michael.kmy.20260908-2200.tmp", NEU);   // Rest eines Abbruchs
        s.files.put("michael.kmy.20260908-2200.old", ALT);   // liegengebliebene alte Datei
        s.files.put("fremd.kmy.20260908-2200.tmp", NEU);     // gehört einer anderen Datei
        s.files.put("michael2.kmy", ALT);

        SafeReplace.cleanUp(s, "", "michael.kmy");

        assertFalse(s.files.containsKey("michael.kmy.20260908-2200.tmp"));
        assertFalse(s.files.containsKey("michael.kmy.20260908-2200.old"));
        assertTrue("fremde Zwischendatei bleibt", s.files.containsKey("fremd.kmy.20260908-2200.tmp"));
        assertTrue(s.files.containsKey("michael.kmy"));
        assertTrue(s.files.containsKey("michael2.kmy"));
    }

    private static <T extends IOException> T erwarte(Class<T> art, FakeStorage s, String version) {
        try {
            SafeReplace.replace(s, "", "michael.kmy", NEU, version, STAMP);
        } catch (IOException e) {
            if (art.isInstance(e)) {
                return art.cast(e);
            }
            fail("erwartet war " + art.getSimpleName() + ", nicht " + e);
        }
        fail("der Fehler muss durchgereicht werden");
        return null;
    }

    private static List<String> tmpNames(FakeStorage s) {
        return namesWith(s, "." + SafeReplace.TMP_EXT);
    }

    private static List<String> oldNames(FakeStorage s) {
        return namesWith(s, "." + SafeReplace.OLD_EXT);
    }

    private static List<String> namesWith(FakeStorage s, String suffix) {
        List<String> out = new ArrayList<>();
        for (String name : s.files.keySet()) {
            if (name.endsWith(suffix)) {
                out.add(name);
            }
        }
        return out;
    }

    /** Ablage im Speicher; kann scheitern, kürzen, Antworten verlieren oder das Auflisten verweigern. */
    private static final class FakeStorage implements RemoteStorage {

        final Map<String, byte[]> files = new LinkedHashMap<>();
        boolean failOnUpload;
        boolean kuerztUpload;
        boolean listenScheitert;
        /** Das Umbenennen dieser Quellen scheitert, ohne etwas zu tun. */
        final Set<String> failMoveFrom = new HashSet<>();
        /** Das Umbenennen dieser Quellen wird ausgeführt, meldet aber einen Fehler. */
        final Set<String> antwortVerlorenBeiMove = new HashSet<>();
        String version = "";
        /** Stand, den die Datei nach der ersten Abfrage annimmt (fremde Änderung im Zeitfenster). */
        String versionNachFruehwarnung;

        @Override
        public void uploadBytes(String folder, String fileName, byte[] content) throws IOException {
            if (failOnUpload) {
                // Wie ein Timeout: ein Teil ist schon geschrieben, dann bricht es ab.
                files.put(fileName, new byte[]{content[0]});
                throw new IOException("timeout");
            }
            if (kuerztUpload) {
                byte[] halb = new byte[content.length / 2];
                System.arraycopy(content, 0, halb, 0, halb.length);
                files.put(fileName, halb);
                return; // meldet Erfolg
            }
            files.put(fileName, content);
        }

        @Override
        public long fileSize(String folder, String fileName) {
            byte[] content = files.get(fileName);
            return content == null ? -1 : content.length;
        }

        @Override
        public void moveNoReplace(String folder, String fromName, String toName, String expectedVersion)
                throws IOException {
            if (expectedVersion != null && !expectedVersion.isEmpty()
                    && !expectedVersion.equals(version)) {
                throw new RemoteConflictException("Quelle geändert");
            }
            if (failMoveFrom.contains(fromName)) {
                throw new IOException("move fehlgeschlagen");
            }
            if (files.containsKey(toName)) {
                throw new RemoteConflictException("Ziel belegt: " + toName);
            }
            byte[] content = files.remove(fromName);
            if (content == null) {
                throw new IOException("Quelle fehlt: " + fromName);
            }
            files.put(toName, content);
            if (antwortVerlorenBeiMove.remove(fromName)) {
                throw new IOException("timeout – ausgeführt, aber keine Antwort");
            }
        }

        @Override
        public void move(String folder, String fromName, String toName) {
            throw new AssertionError("SafeReplace darf nicht mehr überschreibend verschieben");
        }

        @Override
        public void delete(String folder, String fileName) {
            files.remove(fileName);
        }

        @Override
        public String fileVersion(String folder, String fileName) {
            String jetzt = version;
            if (versionNachFruehwarnung != null) {
                version = versionNachFruehwarnung;
                versionNachFruehwarnung = null;
            }
            return jetzt;
        }

        @Override
        public List<String> listAllFiles(String folder) throws IOException {
            if (listenScheitert) {
                throw new IOException("offline");
            }
            return new ArrayList<>(files.keySet());
        }

        @Override
        public List<String> listFiles(String folder, String ext) {
            List<String> out = new ArrayList<>();
            String suffix = "." + ext;
            for (String name : files.keySet()) {
                if (name.endsWith(suffix)) {
                    out.add(name);
                }
            }
            return out;
        }

        @Override
        public void uploadText(String folder, String fileName, String content) {
            files.put(fileName, content.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String downloadText(String folder, String fileName) {
            return new String(files.get(fileName), StandardCharsets.UTF_8);
        }

        @Override
        public byte[] downloadBytes(String folder, String fileName) {
            return files.get(fileName);
        }

        @Override
        public void testConnection() {
        }
    }
}
