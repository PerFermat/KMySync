package de.spahr.ausgaben.receipt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.exifinterface.media.ExifInterface;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;

/**
 * Ein Beleg muß so herum in der Datei landen, wie er aufgenommen wurde.
 *
 * <p>Die Drehung steht nur im EXIF-Block, die Bildpunkte selbst liegen ungedreht da. Liest niemand das
 * EXIF, fällt {@code rotationDegrees} auf {@code 0} zurück — <em>stumm</em>, denn das {@code catch} dort
 * schluckt alles — und der Beleg liegt quer, ohne daß irgend etwas gemeldet würde.</p>
 *
 * <p><b>Was dieser Test nicht zeigt:</b> den eigentlichen Grund für den Wechsel auf
 * {@code androidx.exifinterface}. Der liegt darin, daß die eingebaute {@code android.media}-Fassung nur
 * JPEG und Raw liest, HEIC aber nicht — und genau das nimmt ein Handy im Modus „Hohe Effizienz" auf.
 * Eine HEIC-Probe habe ich nicht; das bleibt eine Zusicherung der Bibliothek, keine hier gemessene.
 * Geprüft wird die Zuordnung der vier Lagen, und daß das Auslesen überhaupt greift.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class ReceiptExifTest {

    /** Ein winziges, gültiges JPEG (1×1). Der EXIF-Block kommt im Test dazu. */
    private static final String PIXEL_JPEG = "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==";

    @Test
    public void jedeLageWirdAufIhrenWinkelAbgebildet() throws IOException {
        assertEquals(0, gradVon(ExifInterface.ORIENTATION_NORMAL));
        assertEquals(90, gradVon(ExifInterface.ORIENTATION_ROTATE_90));
        assertEquals(180, gradVon(ExifInterface.ORIENTATION_ROTATE_180));
        assertEquals(270, gradVon(ExifInterface.ORIENTATION_ROTATE_270));
    }

    /**
     * Ohne EXIF-Block bleibt es bei {@code 0} — das ist der Normalfall und darf nicht als Fehler
     * durchschlagen.
     */
    @Test
    public void ohneExifBleibtEsBeiNull() throws IOException {
        assertEquals(0, ReceiptImage.rotationDegrees(new ByteArrayInputStream(pixel())));
    }

    /**
     * Gegenprobe: Der Block muß tatsächlich im Bild stehen. Ohne sie bewiese der Test oben nur, daß
     * überall {@code 0} herauskommt — und das täte er auch, wenn das Schreiben nie ankäme.
     */
    @Test
    public void dieProbeTraegtDenBlockWirklich() throws IOException {
        byte[] mitBlock = mitLage(ExifInterface.ORIENTATION_ROTATE_90);
        assertTrue("Die Probe ist nicht größer als das nackte Bild — der EXIF-Block fehlt",
                mitBlock.length > pixel().length);
    }

    /**
     * Und weil der Test oben mit beiden Bibliotheken grün bliebe — bei JPEG tun sie dasselbe —, hält
     * dieser Wächter die Entscheidung fest: {@code android.media.ExifInterface} darf nirgends mehr
     * stehen. Sie liest kein HEIC, und der Rückfall wäre stumm.
     */
    @Test
    public void dieEingebauteFassungWirdNirgendsMehrBenutzt() throws IOException {
        java.nio.file.Path quellen = java.nio.file.Paths.get("src/main/java/de/spahr/ausgaben");
        assertTrue("Pfad nicht gefunden: " + quellen.toAbsolutePath(),
                java.nio.file.Files.isDirectory(quellen));
        java.util.List<String> treffer = new java.util.ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> dateien = java.nio.file.Files.walk(quellen)) {
            for (java.nio.file.Path f : (Iterable<java.nio.file.Path>)
                    dateien.filter(x -> x.toString().endsWith(".java"))::iterator) {
                String quelle = new String(java.nio.file.Files.readAllBytes(f),
                        java.nio.charset.StandardCharsets.UTF_8);
                String ohneKommentare = quelle.replaceAll("(?s)/\\*.*?\\*/", "")
                        .replaceAll("//[^\n]*", "");
                if (ohneKommentare.contains("android.media.ExifInterface")) {
                    treffer.add(f.getFileName().toString());
                }
            }
        }
        assertEquals("EXIF bitte über androidx.exifinterface — die eingebaute Fassung liest kein HEIC",
                "[]", treffer.toString());
    }

    private int gradVon(int lage) throws IOException {
        return ReceiptImage.rotationDegrees(new ByteArrayInputStream(mitLage(lage)));
    }

    /** Schreibt die Lage über dieselbe Bibliothek ins Bild, die die App zum Lesen benutzt. */
    private byte[] mitLage(int lage) throws IOException {
        File f = File.createTempFile("beleg", ".jpg");
        f.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(pixel());
        }
        ExifInterface exif = new ExifInterface(f.getAbsolutePath());
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(lage));
        exif.saveAttributes();
        return Files.readAllBytes(f.toPath());
    }

    private byte[] pixel() {
        return Base64.getDecoder().decode(PIXEL_JPEG);
    }
}
