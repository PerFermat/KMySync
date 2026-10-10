package de.spahr.ausgaben.net;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import de.spahr.ausgaben.util.ForegroundGate;

/**
 * Im Hintergrund wartet ein Zugriff, statt zu scheitern; reißt er dort ab, wird er nach der Rückkehr
 * einmal wiederholt – aber nur, was sich wiederholen lässt.
 */
public class WaitingStorageTest {

    private static final byte[] INHALT = {1, 2, 3};

    private Server server;
    private final List<String> meldungen = Collections.synchronizedList(new ArrayList<>());

    @Before
    public void setUp() {
        nachHinten();
        server = new Server();
    }

    @After
    public void tearDown() {
        nachHinten();
    }

    /** Der Zähler ist app-weit; nach jedem Test steht er wieder auf „Hintergrund". */
    private static void nachHinten() {
        while (ForegroundGate.isForeground()) {
            ForegroundGate.leave();
        }
    }

    private WaitingStorage speicher(long fristMs) {
        return new WaitingStorage(server, fristMs, "zu lange im Hintergrund",
                ja -> meldungen.add(ja ? "wartet" : "weiter"));
    }

    @Test
    public void imVordergrundGehtAllesDurch() throws Exception {
        ForegroundGate.enter();
        WaitingStorage s = speicher(1000);
        assertArrayEquals(INHALT, s.downloadBytes("", "a"));
        s.uploadBytes("", "b", INHALT);
        s.moveNoReplace("", "b", "c", "");
        assertEquals("[download a, upload b, move b]", server.aufrufe.toString());
        assertTrue(meldungen.isEmpty());
    }

    @Test
    public void imHintergrundWartetDerZugriffBisDieAppVornIst() throws Exception {
        WaitingStorage s = speicher(10_000);
        AtomicReference<byte[]> ergebnis = new AtomicReference<>();
        CountDownLatch fertig = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                ergebnis.set(s.downloadBytes("", "a"));
            } catch (IOException e) {
                // bleibt null
            }
            fertig.countDown();
        });
        t.start();
        // Solange die App hinten ist, geschieht nichts.
        assertFalse(fertig.await(200, TimeUnit.MILLISECONDS));
        assertTrue(server.aufrufe.isEmpty());

        ForegroundGate.enter();

        assertTrue(fertig.await(5, TimeUnit.SECONDS));
        assertArrayEquals(INHALT, ergebnis.get());
        assertEquals("[wartet, weiter]", meldungen.toString());
    }

    @Test
    public void fristAbgelaufen_wirdGemeldet() {
        try {
            speicher(50).downloadBytes("", "a");
            fail("hätte aufgeben müssen");
        } catch (IOException e) {
            assertEquals("zu lange im Hintergrund", e.getMessage());
        }
        assertTrue(server.aufrufe.isEmpty());
    }

    /** Die App geht mitten im Zugriff nach hinten, das Netz reißt ab; nach der Rückkehr klappt es. */
    @Test
    public void abrissImHintergrund_wirdNachDerRueckkehrEinmalWiederholt() throws Exception {
        ForegroundGate.enter();
        server.beimNaechsten = () -> {
            ForegroundGate.leave();
            // Die Rückkehr kommt etwas später, wie auf dem Gerät.
            new Thread(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                    // egal
                }
                ForegroundGate.enter();
            }).start();
            throw new IOException("Verbindung abgerissen");
        };

        assertArrayEquals(INHALT, speicher(10_000).downloadBytes("", "a"));

        assertEquals("[download a, download a]", server.aufrufe.toString());
        assertEquals("[wartet, weiter]", meldungen.toString());
    }

    @Test
    public void fehlerImVordergrund_wirdNichtWiederholt() {
        ForegroundGate.enter();
        server.beimNaechsten = () -> {
            throw new IOException("404");
        };
        try {
            speicher(1000).downloadBytes("", "a");
            fail("der Fehler gehört weitergereicht");
        } catch (IOException e) {
            assertEquals("404", e.getMessage());
        }
        assertEquals("[download a]", server.aufrufe.toString());
    }

    /** Ob der Server schon umbenannt hat, bevor die Antwort ausblieb, weiß nur, wer nachsieht. */
    @Test
    public void umbenennenUndLoeschenWerdenNieWiederholt() {
        for (String was : new String[]{"move", "delete"}) {
            nachHinten();
            ForegroundGate.enter();
            server.aufrufe.clear();
            server.beimNaechsten = () -> {
                ForegroundGate.leave();
                throw new IOException("Antwort verloren");
            };
            try {
                if ("move".equals(was)) {
                    speicher(1000).moveNoReplace("", "a", "b", "");
                } else {
                    speicher(1000).delete("", "a");
                }
                fail(was + " hätte den Fehler weiterreichen müssen");
            } catch (IOException e) {
                assertEquals("Antwort verloren", e.getMessage());
            }
            assertEquals(1, server.aufrufe.size());
        }
    }

    /** Eine Auskunft des Servers ist kein Abriss – auch nicht, wenn die App gerade hinten ist. */
    @Test
    public void konfliktWirdNichtWiederholt() {
        ForegroundGate.enter();
        server.beimNaechsten = () -> {
            ForegroundGate.leave();
            throw new RemoteConflictException("geändert");
        };
        try {
            speicher(1000).fileVersion("", "a");
            fail("Konflikt gehört weitergereicht");
        } catch (IOException e) {
            assertTrue(e instanceof RemoteConflictException);
        }
        assertEquals(1, server.aufrufe.size());
    }

    private interface Stoerung {
        void los() throws IOException;
    }

    /** Merkt sich die Aufrufe; der nächste lässt sich einmalig stören. */
    private static final class Server implements RemoteStorage {
        final List<String> aufrufe = Collections.synchronizedList(new ArrayList<>());
        volatile Stoerung beimNaechsten;

        private void ruf(String was) throws IOException {
            aufrufe.add(was);
            Stoerung s = beimNaechsten;
            beimNaechsten = null;
            if (s != null) {
                s.los();
            }
        }

        @Override
        public void uploadText(String folder, String fileName, String content) throws IOException {
            ruf("upload " + fileName);
        }

        @Override
        public void uploadBytes(String folder, String fileName, byte[] content) throws IOException {
            ruf("upload " + fileName);
        }

        @Override
        public String fileVersion(String folder, String fileName) throws IOException {
            ruf("version " + fileName);
            return "v";
        }

        @Override
        public List<String> listFiles(String folder, String ext) throws IOException {
            ruf("list");
            return new ArrayList<>();
        }

        @Override
        public void delete(String folder, String fileName) throws IOException {
            ruf("delete " + fileName);
        }

        @Override
        public void moveNoReplace(String folder, String fromName, String toName, String expectedVersion)
                throws IOException {
            ruf("move " + fromName);
        }

        @Override
        public String downloadText(String folder, String fileName) throws IOException {
            ruf("download " + fileName);
            return "";
        }

        @Override
        public byte[] downloadBytes(String folder, String fileName) throws IOException {
            ruf("download " + fileName);
            return INHALT;
        }

        @Override
        public void testConnection() {
        }
    }
}
