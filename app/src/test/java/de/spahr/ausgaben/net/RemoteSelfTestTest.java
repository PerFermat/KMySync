package de.spahr.ausgaben.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Die Probe beim Einrichten muß den Unterschied kennen zwischen „darf gar nicht schreiben" und „darf
 * schreiben, aber nicht umbenennen" – nur so kann die Maske sagen, welches Recht fehlt. Und sie darf
 * nichts liegenlassen.
 */
public class RemoteSelfTestTest {

    @Test
    public void serverKannAlles_ergibtOkUndLaesstNichtsZurueck() {
        FakeStorage s = new FakeStorage();

        RemoteSelfTest.Result r = RemoteSelfTest.run(s, "", "20260909-0700");

        assertTrue(r.ok());
        assertEquals(RemoteSelfTest.Step.OK, r.step);
        assertTrue("keine Probe-Datei darf bleiben", s.files.isEmpty());
    }

    @Test
    public void ordnerNichtBeschreibbar_meldetSchreiben() {
        FakeStorage s = new FakeStorage();
        s.failOnUpload = true;

        RemoteSelfTest.Result r = RemoteSelfTest.run(s, "", "20260909-0700");

        assertEquals(RemoteSelfTest.Step.WRITE, r.step);
        assertNotNull("der Grund muß für die Meldung erhalten bleiben", r.cause);
    }

    @Test
    public void serverKannNichtUmbenennen_meldetUmbenennenUndRaeumtAuf() {
        FakeStorage s = new FakeStorage();
        s.failOnMove = true;

        RemoteSelfTest.Result r = RemoteSelfTest.run(s, "", "20260909-0700");

        assertEquals(RemoteSelfTest.Step.RENAME, r.step);
        assertNotNull(r.cause);
        assertTrue("die geschriebene Probe-Datei muß weg sein", s.files.isEmpty());
    }

    /** Kann der Server nicht einmal löschen, bleibt das Ergebnis trotzdem gültig. */
    @Test
    public void loeschenScheitert_kipptDasErgebnisNicht() {
        FakeStorage s = new FakeStorage();
        s.failOnDelete = true;

        RemoteSelfTest.Result r = RemoteSelfTest.run(s, "", "20260909-0700");

        assertTrue(r.ok());
    }

    private static final class FakeStorage implements RemoteStorage {

        final Map<String, byte[]> files = new LinkedHashMap<>();
        boolean failOnUpload;
        boolean failOnMove;
        boolean failOnDelete;

        @Override
        public void uploadBytes(String folder, String fileName, byte[] content) throws IOException {
            if (failOnUpload) {
                throw new IOException("HTTP 403 Forbidden");
            }
            files.put(fileName, content);
        }

        @Override
        public void move(String folder, String fromName, String toName) throws IOException {
            if (failOnMove) {
                throw new IOException("HTTP 405 Method Not Allowed");
            }
            byte[] c = files.remove(fromName);
            if (c == null) {
                throw new IOException("Quelle fehlt");
            }
            files.put(toName, c);
        }

        @Override
        public void delete(String folder, String fileName) throws IOException {
            if (failOnDelete) {
                throw new IOException("HTTP 403 Forbidden");
            }
            files.remove(fileName);
        }

        @Override
        public List<String> listFiles(String folder, String ext) {
            return new ArrayList<>();
        }

        @Override
        public void uploadText(String folder, String fileName, String content) {
            files.put(fileName, content.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String downloadText(String folder, String fileName) {
            return "";
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
