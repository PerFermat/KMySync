package de.spahr.ausgaben.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Rückläufer aus dem Hintergrund dürfen keine tote Maske mehr anfassen.
 *
 * <p>Die App erledigt Netz-, Datei- und Importarbeit in eigenen Fäden. Kam einer davon zurück, während
 * der Nutzer weggetippt oder gedreht hatte, faßte er ein geschlossenes Fenster an — bei Views nur
 * folgenlos, bei Dialogen ein Absturz. {@link Ui#post} prüft das an einer Stelle für die ganze App.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class UiPostTest {

    /**
     * Die eigentliche Zusicherung: derselbe Aufruf, zweimal, mit zwei verschiedenen Ergebnissen. Nur
     * deshalb kann dieser Test nicht zufällig grün sein — liefe er ungeprüft durch, stünde am Ende 2.
     */
    @Test
    public void nachDemZerstoerenLaeuftNichtsMehr() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        AtomicInteger laeufe = new AtomicInteger();

        Ui.post(controller.get(), laeufe::incrementAndGet);
        assertEquals("solange die Maske lebt, läuft der Rückläufer", 1, laeufe.get());

        controller.pause().stop().destroy();
        Ui.post(controller.get(), laeufe::incrementAndGet);
        assertEquals("nach dem Zerstören nicht mehr", 1, laeufe.get());
    }

    /**
     * Der zweite Weg hinaus: Die Maske ist noch nicht zerstört, aber schon am Gehen. {@code isFinishing}
     * greift früher als {@code isDestroyed} — zwischen {@code finish()} und dem tatsächlichen Abbau
     * liegt ein Durchlauf der Nachrichtenschleife, und genau dort landen die Rückläufer.
     */
    @Test
    public void einAbgehenderMaskeWirdNichtsMehrZugestellt() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        AtomicInteger laeufe = new AtomicInteger();

        activity.finish();
        Ui.post(activity, laeufe::incrementAndGet);

        assertEquals(0, laeufe.get());
    }

    /** Ohne Maske ist nichts zu tun – die Regler geben ihre Activity frei, ohne selbst zu prüfen. */
    @Test
    public void ohneMaskePassiertNichts() {
        Ui.post(null, () -> {
            throw new AssertionError("hätte nicht laufen dürfen");
        });
    }

    /**
     * Wächter gegen den Rückfall ins Alte.
     *
     * <p>Die 69 umgestellten Stellen sind das eine, aber nichts hindert die nächste Maske daran, wieder
     * ein rohes {@code runOnUiThread} zu schreiben — und aufgefallen wäre das erst als Absturzbericht
     * eines Nutzers, der zum falschen Zeitpunkt gedreht hat.</p>
     *
     * <p>Erlaubt sind genau zwei Stellen, und beide sind im Quelltext begründet:
     * {@link Ui} selbst, und {@code BackupRestoreController.postRestoreDone()} — nach einer
     * eingespielten Sicherung muß die App neu aufgesetzt werden, und das <em>gerade auch dann</em>,
     * wenn der Nutzer inzwischen weggetippt hat.</p>
     *
     * <p>Gesucht wird nur im Code: Kommentare fallen vorher heraus. Sonst schlüge der Wächter bei
     * jedem Javadoc an, das die alte Schreibweise erklärt — und das Erklären ist ja gerade erwünscht.</p>
     */
    @Test
    public void keineMaskeKehrtRohAufDenBedienfadenZurueck() throws IOException {
        Path quellen = Paths.get("src/main/java/de/spahr/ausgaben");
        assertTrue("Pfad " + quellen.toAbsolutePath() + " nicht gefunden", Files.isDirectory(quellen));

        List<String> erlaubt = java.util.Arrays.asList("Ui.java", "BackupRestoreController.java");
        List<String> treffer = new ArrayList<>();
        try (Stream<Path> dateien = Files.walk(quellen)) {
            for (Path f : (Iterable<Path>) dateien.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String name = f.getFileName().toString();
                if (erlaubt.contains(name)) {
                    continue;
                }
                String quelle = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                String ohneKommentare = quelle.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
                if (ohneKommentare.contains("runOnUiThread(")) {
                    treffer.add(name);
                }
            }
        }
        assertEquals("Rückläufer bitte über Ui.post bzw. LocalizedActivity.post, nicht roh",
                "[]", treffer.toString());
    }
}
