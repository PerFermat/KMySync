package de.spahr.ausgaben.receipt;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Unterscheidet die Prüfung ein abgebrochenes Archiv von einem bloß unvollständigen? Genau daran
 * hängt, ob die App eine Datei wegwirft oder liegen lässt.
 */
public class ZipCheckTest {

    /** Ein echtes, ordentlich geschlossenes ZIP mit {@code n} Einträgen. */
    private static byte[] zip(int n) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            for (int i = 1; i <= n; i++) {
                zip.putNextEntry(new ZipEntry("beleg-" + i + ".pdf"));
                zip.write(("Inhalt " + i).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    @Test
    public void einGeschlossenesArchivGiltAlsHeil() throws Exception {
        assertTrue(ZipCheck.looksComplete(zip(3)));
    }

    @Test
    public void einArchivMitEinemEintragGenauso() throws Exception {
        assertTrue(ZipCheck.looksComplete(zip(1)));
    }

    @Test
    public void auchDasLeereArchivIstHeil() throws Exception {
        // Ein Lauf, der nichts fand, schreibt ein gültiges ZIP ohne Einträge – kein Grund zum Löschen.
        assertTrue(ZipCheck.looksComplete(zip(0)));
    }

    @Test
    public void abgeschnittenIstKaputt() throws Exception {
        byte[] ganz = zip(5);
        // Der Prozess starb mitten im Schreiben: das Ende fehlt.
        byte[] torso = Arrays.copyOf(ganz, ganz.length / 2);
        assertFalse(ZipCheck.looksComplete(torso));
    }

    @Test
    public void einEinzigesFehlendesByteGenuegt() throws Exception {
        byte[] ganz = zip(3);
        assertFalse(ZipCheck.looksComplete(Arrays.copyOf(ganz, ganz.length - 1)));
    }

    @Test
    public void garNichtsGeschriebenIstKaputt() {
        assertFalse(ZipCheck.looksComplete(new byte[0]));
        assertFalse(ZipCheck.looksComplete(null));
    }

    @Test
    public void inhaltDerZufaelligWieDieSignaturAussiehtStoertNicht() throws Exception {
        // Die Signatur kann in den gepackten Daten vorkommen; ohne den vollständigen Eintrag dahinter
        // darf sie nicht als Dateiende durchgehen.
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(new byte[]{0x50, 0x4B, 0x05, 0x06});
        bos.write("noch nicht fertig".getBytes(StandardCharsets.UTF_8));
        byte[] daten = bos.toByteArray();
        // 4 + 17 Bytes: zu kurz für einen echten Eintrag.
        assertFalse(ZipCheck.looksComplete(daten));
    }
}
