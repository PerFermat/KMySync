package de.spahr.ausgaben.export;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.spahr.ausgaben.net.RemoteConflictException;
import de.spahr.ausgaben.net.RemoteStorage;

/**
 * Nach dem Export: Eine lesbare Datei gilt, eine defekt angekommene wird durch den Stand von vor dem
 * Export ersetzt – aber nur sie, nie die Datei eines anderen.
 */
public class KmyRuecklesenTest {

    private static final String DATEI = "m.kmy";

    private static byte[] kmy(String inhalt) throws IOException {
        return KmyDocument.gzip("<?xml version=\"1.0\"?>\n<KMYMONEY-FILE>\n" + inhalt
                + "\n</KMYMONEY-FILE>\n");
    }

    private static byte[] abgeschnitten(byte[] b) {
        return Arrays.copyOf(b, b.length / 2);
    }

    private static final KmyRuecklesen.Leser LESER = KmyDocument::gunzip;

    private static KmyRuecklesen.Ergebnis pruefe(Ablage s, byte[] gelesen, byte[] hochgeladen,
                                                 byte[] vorher) {
        return KmyRuecklesen.pruefe(s, "", DATEI, gelesen, hochgeladen, vorher, LESER, "t1");
    }

    @Test
    public void genauDieHochgeladeneDatei_gilt() throws Exception {
        byte[] vorher = kmy("alt");
        byte[] neu = kmy("neu");
        Ablage s = new Ablage(neu);

        KmyRuecklesen.Ergebnis e = pruefe(s, neu, neu, vorher);

        assertEquals(KmyRuecklesen.Ausgang.LESBAR, e.ausgang);
        assertArrayEquals(neu, e.bytes);
        assertEquals(0, s.gelesen);
        assertArrayEquals(neu, s.files.get(DATEI));
    }

    @Test
    public void abweichendAberLesbar_istDieDateiEinesAnderenUndBleibt() throws Exception {
        byte[] vorher = kmy("alt");
        byte[] neu = kmy("neu");
        byte[] fremd = kmy("von KMyMoney gespeichert");
        Ablage s = new Ablage(fremd);

        KmyRuecklesen.Ergebnis e = pruefe(s, fremd, neu, vorher);

        assertEquals(KmyRuecklesen.Ausgang.LESBAR, e.ausgang);
        assertArrayEquals(fremd, e.bytes);
        assertArrayEquals(fremd, s.files.get(DATEI));
    }

    @Test
    public void einmalDefektDannLesbar_giltOhneZurueckspielen() throws Exception {
        byte[] vorher = kmy("alt");
        byte[] neu = kmy("neu");
        Ablage s = new Ablage(neu);

        KmyRuecklesen.Ergebnis e = pruefe(s, abgeschnitten(neu), neu, vorher);

        assertEquals(KmyRuecklesen.Ausgang.LESBAR, e.ausgang);
        assertArrayEquals(neu, e.bytes);
        assertArrayEquals(neu, s.files.get(DATEI));
    }

    @Test
    public void zweimalDefekt_derAlteStandLiegtWiederAufDemServer() throws Exception {
        byte[] vorher = kmy("alt");
        byte[] neu = kmy("neu");
        byte[] torso = abgeschnitten(neu);
        Ablage s = new Ablage(torso);

        KmyRuecklesen.Ergebnis e = pruefe(s, torso, neu, vorher);

        assertEquals(KmyRuecklesen.Ausgang.WIEDERHERGESTELLT, e.ausgang);
        assertArrayEquals(torso, e.bytes);
        assertArrayEquals(vorher, s.files.get(DATEI));
        // Nichts bleibt liegen: weder die Zwischendatei noch die zur Seite gelegte defekte.
        assertEquals(1, s.files.size());
    }

    @Test
    public void keinGzipMehr_giltAlsDefekt() throws Exception {
        byte[] vorher = kmy("alt");
        byte[] neu = kmy("neu");
        byte[] fehlerseite = "<html><body>502 Bad Gateway</body></html>".getBytes(StandardCharsets.UTF_8);
        Ablage s = new Ablage(fehlerseite);

        KmyRuecklesen.Ergebnis e = pruefe(s, fehlerseite, neu, vorher);

        assertEquals(KmyRuecklesen.Ausgang.WIEDERHERGESTELLT, e.ausgang);
        assertArrayEquals(vorher, s.files.get(DATEI));
    }

    @Test
    public void defektUndInzwischenFremdGeaendert_nichtsWirdUeberschrieben() throws Exception {
        byte[] vorher = kmy("alt");
        byte[] neu = kmy("neu");
        byte[] torso = abgeschnitten(neu);
        Ablage s = new Ablage(torso);
        // Zwischen dem zweiten Lesen und dem Zurückspielen schreibt jemand anderes.
        s.versionen.add("v1");
        s.versionen.add("v2");

        KmyRuecklesen.Ergebnis e = pruefe(s, torso, neu, vorher);

        assertEquals(KmyRuecklesen.Ausgang.NICHT_WIEDERHERGESTELLT, e.ausgang);
        assertArrayEquals(torso, s.files.get(DATEI));
        assertEquals(1, s.files.size());
    }

    @Test
    public void zurueckschreibenScheitert_wirdGemeldetMitGrund() throws Exception {
        byte[] vorher = kmy("alt");
        byte[] neu = kmy("neu");
        byte[] torso = abgeschnitten(neu);
        Ablage s = new Ablage(torso);
        s.uploadScheitert = true;

        KmyRuecklesen.Ergebnis e = pruefe(s, torso, neu, vorher);

        assertEquals(KmyRuecklesen.Ausgang.NICHT_WIEDERHERGESTELLT, e.ausgang);
        assertTrue(e.grund.contains("timeout"));
        assertArrayEquals(torso, s.files.get(DATEI));
    }

    @Test
    public void zweitesLesenScheitert_nichtsWirdAngefasst() throws Exception {
        byte[] vorher = kmy("alt");
        byte[] neu = kmy("neu");
        byte[] torso = abgeschnitten(neu);
        Ablage s = new Ablage(torso);
        s.downloadScheitert = true;

        KmyRuecklesen.Ergebnis e = pruefe(s, torso, neu, vorher);

        assertEquals(KmyRuecklesen.Ausgang.NICHT_WIEDERHERGESTELLT, e.ausgang);
        assertFalse(e.grund.isEmpty());
        assertArrayEquals(torso, s.files.get(DATEI));
    }

    @Test
    public void abgeschnittenesGzip_liefertDenAnfangStattZuWerfen() throws Exception {
        StringBuilder lang = new StringBuilder();
        for (int i = 0; i < 20000; i++) {
            lang.append("<TRANSACTION id=\"T").append(i).append("\"/>\n");
        }
        byte[] ganz = kmy(lang.toString());
        String anfang = KmyDocument.gunzipSoweitMoeglich(abgeschnitten(ganz));
        assertTrue(anfang.startsWith("<?xml version=\"1.0\"?>\n<KMYMONEY-FILE>\n<TRANSACTION id=\"T0\"/>"));
        assertFalse(anfang.contains("</KMYMONEY-FILE>"));
        assertEquals(KmyDocument.gunzip(ganz), KmyDocument.gunzipSoweitMoeglich(ganz));
        assertEquals("kein gzip", KmyDocument.gunzipSoweitMoeglich(
                "kein gzip".getBytes(StandardCharsets.UTF_8)));
    }

    /** Ablage im Speicher mit bedingtem Umbenennen wie auf dem Server. */
    private static final class Ablage implements RemoteStorage {

        final Map<String, byte[]> files = new LinkedHashMap<>();
        /** Aufeinanderfolgende Antworten auf die Frage nach dem Stand; leer = immer derselbe. */
        final Deque<String> versionen = new ArrayDeque<>();
        String version = "";
        boolean uploadScheitert;
        boolean downloadScheitert;
        int gelesen;

        Ablage(byte[] inhalt) {
            files.put(DATEI, inhalt);
        }

        @Override
        public void uploadBytes(String folder, String fileName, byte[] content) throws IOException {
            if (uploadScheitert) {
                throw new IOException("timeout");
            }
            files.put(fileName, content);
        }

        @Override
        public long fileSize(String folder, String fileName) {
            byte[] content = files.get(fileName);
            return content == null ? -1 : content.length;
        }

        @Override
        public String fileVersion(String folder, String fileName) {
            if (!versionen.isEmpty()) {
                version = versionen.poll();
            }
            return version;
        }

        @Override
        public void moveNoReplace(String folder, String fromName, String toName, String expectedVersion)
                throws IOException {
            if (expectedVersion != null && !expectedVersion.isEmpty()
                    && !expectedVersion.equals(version)) {
                throw new RemoteConflictException("Quelle geändert");
            }
            if (files.containsKey(toName)) {
                throw new RemoteConflictException("Ziel belegt: " + toName);
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
        public List<String> listAllFiles(String folder) {
            return new ArrayList<>(files.keySet());
        }

        @Override
        public List<String> listFiles(String folder, String ext) {
            List<String> out = new ArrayList<>();
            for (String name : files.keySet()) {
                if (name.endsWith("." + ext)) {
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
        public byte[] downloadBytes(String folder, String fileName) throws IOException {
            if (downloadScheitert) {
                throw new IOException("offline");
            }
            gelesen++;
            return files.get(fileName);
        }

        @Override
        public void testConnection() {
        }
    }
}
