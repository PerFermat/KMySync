package de.spahr.ausgaben.i18n;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Groß- und Kleinschreibung braucht ein festes Locale — sonst entscheidet das Gerät mit.
 *
 * <h2>Warum das kein Formalismus ist</h2>
 *
 * <p>Im Türkischen wird aus dem großen {@code I} ein <b>punktloses</b> {@code ı}, nicht das gewohnte
 * {@code i}. Ein {@code "COLLECTION".toLowerCase()} ergibt dort {@code collectıon}, und der Vergleich
 * mit {@code "collection"} scheitert. Genau das stand in
 * {@code NextcloudUploader.localName}: Meldet ein WebDAV-Server seine Ordner als
 * {@code <D:COLLECTION/>}, wurden sie auf einem türkischen Gerät nicht als Ordner erkannt und
 * erschienen in der Dateiauswahl als Dateien.</p>
 *
 * <p>Der Fehler ist heimtückisch, weil er auf dem Gerät des Entwicklers nie auftritt und auch kein
 * Testgerät ihn zeigt, solange es nicht auf Türkisch steht. Deshalb dieser Wächter statt einer
 * einmaligen Durchsicht.</p>
 *
 * <h2>Was geprüft wird</h2>
 *
 * <p>Die Umkehrung — {@code toLowerCase()} ohne Argument und {@code String.format} ohne Locale —
 * lässt sich am Quelltext eindeutig erkennen, die richtige Verwendung nicht. Der Wächter sucht also
 * nach dem Fehlen, nicht nach dem Vorhandensein.</p>
 *
 * <p>Welches Locale das richtige ist, sagt er bewusst nicht: Für Vergleiche mit festen
 * ASCII-Zeichenketten ist {@code Locale.ROOT} richtig, für Anzeigetexte das der Sprache. Beides ist
 * eine Entscheidung, keine Regel.</p>
 */
public class LocaleGuardTest {

    private static final Path QUELLEN = Paths.get("src/main/java/de/spahr/ausgaben");

    /** Liest alle Java-Dateien ohne Kommentare — sonst schlägt schon das Javadoc hier drüben an. */
    private static List<String[]> quellen() throws IOException {
        assertTrue("Pfad " + QUELLEN.toAbsolutePath() + " nicht gefunden", Files.isDirectory(QUELLEN));
        List<String[]> out = new ArrayList<>();
        try (Stream<Path> dateien = Files.walk(QUELLEN)) {
            for (Path f : (Iterable<Path>) dateien.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String quelle = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                String code = quelle.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
                out.add(new String[]{f.getFileName().toString(), code});
            }
        }
        return out;
    }

    /** Zählt die Zeilen bis zum Fund, damit der Bericht direkt anspringbar ist. */
    private static void sammle(List<String> treffer, String datei, String code, String muster) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(muster).matcher(code);
        while (m.find()) {
            int zeile = 1 + (int) code.substring(0, m.start()).chars().filter(c -> c == '\n').count();
            treffer.add(datei + ":~" + zeile);
        }
    }

    @Test
    public void keinKleinschreibenOhneLocale() throws IOException {
        List<String> treffer = new ArrayList<>();
        for (String[] datei : quellen()) {
            sammle(treffer, datei[0], datei[1], "to(?:Lower|Upper)Case\\(\\s*\\)");
        }
        assertEquals("Groß-/Kleinschreibung bitte mit festem Locale – sonst bricht es auf Türkisch",
                "[]", treffer.toString());
    }

    /**
     * {@code String.format} ohne Locale nimmt das des Geräts. Bei {@code %d} und {@code %f} ändert das
     * die Ziffern und das Dezimalzeichen — in einer Zeichenkette, die oft weiterverarbeitet und nicht
     * nur angezeigt wird.
     */
    @Test
    public void keinFormatOhneLocale() throws IOException {
        List<String> treffer = new ArrayList<>();
        for (String[] datei : quellen()) {
            sammle(treffer, datei[0], datei[1], "String\\.format\\(\\s*\"");
        }
        assertEquals("String.format bitte mit Locale als erstem Argument",
                "[]", treffer.toString());
    }
}
