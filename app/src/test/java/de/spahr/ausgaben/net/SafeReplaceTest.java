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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nagelt das Versprechen fest, an dem der Verlust einer .kmy hing: <b>bricht das Schreiben ab, bleibt die
 * vorhandene Datei unangetastet</b>. Ohne Emulator und ohne Mock-Bibliothek – die Attrappe unten ist eine
 * gewöhnliche Map, die auf Wunsch mittendrin abbricht.
 */
public class SafeReplaceTest {

    private static final byte[] ALT = "die gute alte Datei".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEU = "der frische Stand".getBytes(StandardCharsets.UTF_8);

    @Test
    public void erfolg_zieldateiTraegtDenNeuenInhalt() throws Exception {
        FakeStorage s = new FakeStorage();
        s.files.put("michael.kmy", ALT);

        SafeReplace.replace(s, "", "michael.kmy", NEU, "", "20260909-0337");

        assertArrayEquals(NEU, s.files.get("michael.kmy"));
        assertTrue("keine Zwischendatei darf zurückbleiben", tmpNames(s).isEmpty());
        assertTrue("die alte Datei ist nach dem Tausch weg", oldNames(s).isEmpty());
    }

    @Test
    public void abbruchBeimSchreiben_alteDateiBleibtBytegleich() {
        FakeStorage s = new FakeStorage();
        s.files.put("michael.kmy", ALT);
        s.failOnUpload = true; // wie ein Timeout mitten im Hochladen

        try {
            SafeReplace.replace(s, "", "michael.kmy", NEU, "", "20260909-0337");
            fail("der Fehler muss durchgereicht werden");
        } catch (IOException expected) {
            // so gewollt
        }

        assertArrayEquals("die vorhandene Datei darf nicht angefasst worden sein",
                ALT, s.files.get("michael.kmy"));
        assertTrue("die halbe Zwischendatei muss weg sein", tmpNames(s).isEmpty());
    }

    @Test
    public void abbruchBeimUmbenennen_alteDateiBleibtBytegleich() {
        FakeStorage s = new FakeStorage();
        s.files.put("michael.kmy", ALT);
        s.failOnMove = true;

        try {
            SafeReplace.replace(s, "", "michael.kmy", NEU, "", "20260909-0337");
            fail("der Fehler muss durchgereicht werden");
        } catch (RemoteMoveException expected) {
            // Eigene Ausnahme: alles geschrieben, nur das Ersetzen ging nicht.
        } catch (IOException e) {
            fail("erwartet war RemoteMoveException, nicht " + e);
        }

        assertArrayEquals(ALT, s.files.get("michael.kmy"));
        assertTrue(tmpNames(s).isEmpty());
        assertTrue(oldNames(s).isEmpty());
    }

    /**
     * Der Fall vom 25.09.2026: Nextcloud löscht bei einem überschreibenden MOVE zuerst das Ziel und
     * benennt dann um – und das Umbenennen scheiterte. Mit dem alten Ablauf waren danach die .kmy
     * <b>und</b> die Zwischendatei weg. Beim Tausch ist das Ziel des MOVE nie belegt; scheitert das
     * Einsetzen, kommt die alte Datei zurück.
     */
    @Test
    public void einsetzenScheitertWieBeiNextcloud_alteDateiKommtZurueck() {
        FakeStorage s = new FakeStorage();
        s.files.put("michael.kmy", ALT);
        s.loeschtZielZuerst = true;
        s.failMoveFrom = "michael.kmy.20260925-132129.tmp";

        try {
            SafeReplace.replace(s, "", "michael.kmy", NEU, "", "20260925-132129");
            fail("der Fehler muss durchgereicht werden");
        } catch (RemoteMoveException expected) {
            // Zustand wie vor dem Export
        } catch (IOException e) {
            fail("erwartet war RemoteMoveException, nicht " + e);
        }

        assertArrayEquals("die alte Datei muss wieder an ihrem Platz stehen",
                ALT, s.files.get("michael.kmy"));
        assertTrue(tmpNames(s).isEmpty());
        assertTrue(oldNames(s).isEmpty());
    }

    /** Scheitert auch das Zurückbenennen, wird nichts gelöscht – beide Stände bleiben liegen. */
    @Test
    public void auchZurueckbenennenScheitert_beideStaendeBleibenErhalten() {
        FakeStorage s = new FakeStorage();
        s.files.put("michael.kmy", ALT);
        s.failMoveFrom = "michael.kmy.20260925-132129.tmp";
        s.failMoveFrom2 = "michael.kmy.20260925-132129.old";

        try {
            SafeReplace.replace(s, "", "michael.kmy", NEU, "", "20260925-132129");
            fail("der Fehler muss durchgereicht werden");
        } catch (RemoteReplaceStuckException expected) {
            assertEquals("michael.kmy.20260925-132129.old", expected.oldName);
            assertEquals("michael.kmy", expected.file);
        } catch (IOException e) {
            fail("erwartet war RemoteReplaceStuckException, nicht " + e);
        }

        assertArrayEquals(ALT, s.files.get("michael.kmy.20260925-132129.old"));
        assertArrayEquals(NEU, s.files.get("michael.kmy.20260925-132129.tmp"));
    }

    @Test
    public void fremdeAenderung_meldetKonfliktUndSchreibtNicht() {
        FakeStorage s = new FakeStorage();
        s.files.put("michael.kmy", ALT);
        s.version = "etag-neu"; // erwartet wurde „etag-alt"

        try {
            SafeReplace.replace(s, "", "michael.kmy", NEU, "etag-alt", "20260909-0337");
            fail("ein Konflikt muss gemeldet werden");
        } catch (RemoteConflictException expected) {
            // so gewollt
        } catch (IOException e) {
            fail("erwartet war RemoteConflictException, nicht " + e);
        }

        assertArrayEquals(ALT, s.files.get("michael.kmy"));
        assertTrue(tmpNames(s).isEmpty());
    }

    @Test
    public void aufraeumen_entferntNurEigeneReste() {
        FakeStorage s = new FakeStorage();
        s.files.put("michael.kmy", ALT);
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

    private static List<String> tmpNames(FakeStorage s) {
        List<String> out = new ArrayList<>();
        for (String name : s.files.keySet()) {
            if (name.endsWith("." + SafeReplace.TMP_EXT)) {
                out.add(name);
            }
        }
        return out;
    }

    private static List<String> oldNames(FakeStorage s) {
        List<String> out = new ArrayList<>();
        for (String name : s.files.keySet()) {
            if (name.endsWith("." + SafeReplace.OLD_EXT)) {
                out.add(name);
            }
        }
        return out;
    }

    /** Ablage im Speicher; kann das Hochladen oder das Umbenennen scheitern lassen. */
    private static final class FakeStorage implements RemoteStorage {

        final Map<String, byte[]> files = new LinkedHashMap<>();
        boolean failOnUpload;
        boolean failOnMove;
        /** Nur das Umbenennen dieser Quelle(n) scheitert. */
        String failMoveFrom;
        String failMoveFrom2;
        /** Wie Nextcloud: ein belegtes Ziel wird vor dem Umbenennen gelöscht – auch wenn es danach scheitert. */
        boolean loeschtZielZuerst;
        String version = "";

        @Override
        public void uploadBytes(String folder, String fileName, byte[] content) throws IOException {
            if (failOnUpload) {
                // Wie ein Timeout: ein Teil ist schon geschrieben, dann bricht es ab.
                files.put(fileName, new byte[]{content[0]});
                throw new IOException("timeout");
            }
            files.put(fileName, content);
        }

        @Override
        public void move(String folder, String fromName, String toName) throws IOException {
            if (loeschtZielZuerst) {
                files.remove(toName);
            }
            if (failOnMove || fromName.equals(failMoveFrom) || fromName.equals(failMoveFrom2)) {
                throw new IOException("move fehlgeschlagen");
            }
            byte[] content = files.remove(fromName);
            if (content == null) {
                throw new IOException("Quelle fehlt: " + fromName);
            }
            files.put(toName, content);
        }

        @Override
        public void delete(String folder, String fileName) {
            files.remove(fileName);
        }

        @Override
        public String fileVersion(String folder, String fileName) {
            return version;
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
