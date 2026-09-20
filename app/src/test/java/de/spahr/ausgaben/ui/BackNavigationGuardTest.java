package de.spahr.ausgaben.ui;

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
 * Die Zurück-Taste läuft über den {@code OnBackPressedDispatcher}, nicht über eine überschriebene
 * {@code onBackPressed()}.
 *
 * <p>Das ist keine Stilfrage. {@code onBackPressed()} ist seit API 33 überholt, und sobald
 * {@code android:enableOnBackInvokedCallback} gesetzt ist, ruft das System die Überschreibung gar nicht
 * mehr auf — sie wird still übergangen. Wo daran ein Schutz hängt, verschwindet er lautlos: In
 * {@code OnboardingActivity} und {@code ProfileSettingsActivity} verhindert {@code blockIfImporting()},
 * daß die Maske mitten in einem laufenden Konten-Import schließt. Täte sie es doch, wechselte
 * {@code ProfileManager.switchTo} das Profil unter dem Import weg und schlösse die Datenbank — die App
 * stürzt mit „connection pool has been closed" ab.</p>
 *
 * <p>Fünf Masken waren schon umgestellt, {@code OnboardingActivity} blieb bis 2.1 übrig. Lint hatte sie
 * als {@code MissingSuperCall} gemeldet, allerdings mit einer anderen Begründung — daß die
 * Überschreibung {@code super} nicht ruft, ist hier ja gerade beabsichtigt. Der eigentliche Grund
 * steckte im Kommentar der bereits umgestellten Schwestermaske.</p>
 *
 * <p>Gesucht wird nur im Code; Kommentare fallen vorher heraus, sonst schlüge der Wächter an diesem
 * Javadoc selbst an.</p>
 */
public class BackNavigationGuardTest {

    @Test
    public void keineMaskeUeberschreibtOnBackPressed() throws IOException {
        assertEquals("Zurück bitte über getOnBackPressedDispatcher().addCallback(), "
                        + "nicht über onBackPressed() — siehe Javadoc dieses Wächters",
                "[]", treffer("public void onBackPressed(").toString());
    }

    /**
     * Gegenprobe: Der Wächter muß den Aufruf des Dispatchers <em>finden</em> können. Ohne sie bewiese
     * der Test oben nur, daß die Suche durch ein leeres Verzeichnis läuft — und bliebe auch dann grün,
     * wenn jemand den Pfad verstellt.
     */
    @Test
    public void dieMaskenNutzenDenDispatcher() throws IOException {
        List<String> mitDispatcher = treffer("getOnBackPressedDispatcher()");
        assertTrue("Keine einzige Maske nutzt den Dispatcher — sucht der Wächter am richtigen Ort?",
                mitDispatcher.size() >= 5);
        assertTrue("OnboardingActivity muß dabei sein, dort hängt der Import-Schutz daran",
                mitDispatcher.contains("OnboardingActivity.java"));
    }

    private List<String> treffer(String gesucht) throws IOException {
        Path quellen = Paths.get("src/main/java/de/spahr/ausgaben");
        assertTrue("Pfad " + quellen.toAbsolutePath() + " nicht gefunden", Files.isDirectory(quellen));
        List<String> namen = new ArrayList<>();
        try (Stream<Path> dateien = Files.walk(quellen)) {
            for (Path f : (Iterable<Path>) dateien.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String quelle = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                String ohneKommentare = quelle.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
                if (ohneKommentare.contains(gesucht)) {
                    namen.add(f.getFileName().toString());
                }
            }
        }
        return namen;
    }
}
