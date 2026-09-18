package de.spahr.ausgaben.widget;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import de.spahr.ausgaben.db.Account;
import de.spahr.ausgaben.db.AppDatabase;
import de.spahr.ausgaben.settings.PlacesStore;

/**
 * Das Durchschalten des Typ-Widgets über Konten und Orte.
 *
 * <p>Bis hierher hatte {@code widget/} keinen einzigen Test — bei sechs Klassen, die im Betrieb in
 * einem <b>fremden Prozess</b> laufen. Dort wird ein Fehler nicht als Absturz sichtbar, sondern als
 * ein Widget, das stehenbleibt oder leer ist; im Logcat der App steht nichts davon.</p>
 *
 * <p>Geprüft wird die Stelle mit echter Logik: {@code advance} baut die Liste aus allen aktiven Konten
 * und deren Orten und schaltet eins weiter. Die Fälle unten sind die, bei denen es schiefgehen kann —
 * der Umlauf am Ende, ein Konto ganz ohne Orte, und eine gespeicherte Auswahl, die es nicht mehr
 * gibt.</p>
 *
 * <p>Alles läuft über einen Hintergrundfaden, und das ist keine Testkosmetik: {@code advance} liest
 * die Datenbank, Room verbietet das auf dem Bedienfaden, und im Betrieb ruft
 * {@link WidgetType} es ebenfalls aus einem eigenen Faden. Der Test bildet damit den echten Aufrufweg
 * nach statt einen bequemeren zu erfinden.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WidgetSelectionTest {

    private Context ctx;
    private ExecutorService executor;

    @Before
    public void setUp() throws Exception {
        ctx = ApplicationProvider.getApplicationContext();
        executor = Executors.newSingleThreadExecutor();
        imHintergrund(() -> {
            AppDatabase.getInstance(ctx).clearAllTables();
            return null;
        });
        ctx.getSharedPreferences("widget_selection", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After
    public void tearDown() {
        executor.shutdownNow();
    }

    private <T> T imHintergrund(Callable<T> arbeit) throws Exception {
        return executor.submit(arbeit).get(10, TimeUnit.SECONDS);
    }

    private void konto(String name, int sortPos) throws Exception {
        Account a = new Account(name);
        a.sortPos = sortPos;
        imHintergrund(() -> {
            AppDatabase.getInstance(ctx).accountDao().insertIfAbsent(a);
            return null;
        });
    }

    private void weiter() throws Exception {
        imHintergrund(() -> {
            WidgetSelection.advance(ctx);
            return null;
        });
    }

    private String[] jetzt() throws Exception {
        return imHintergrund(() -> WidgetSelection.current(ctx));
    }

    /** Die Stationen eines Umlaufs als {@code Konto/Ort}, damit sich Mengen vergleichen lassen. */
    private List<String> umlauf(int schritte) throws Exception {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < schritte; i++) {
            weiter();
            String[] s = jetzt();
            out.add(s[0] + "/" + s[1]);
        }
        return out;
    }

    /**
     * Ein Konto ohne Orte ist selbst eine Station — sonst fiele es beim Durchschalten heraus und wäre
     * über das Widget nicht mehr erreichbar. Der leere Ort bedeutet „ganzes Konto".
     */
    @Test
    public void kontoOhneOrteIstEineEigeneStation() throws Exception {
        konto("Bargeld", 1);

        weiter();

        assertArrayEquals(new String[]{"Bargeld", ""}, jetzt());
    }

    /**
     * Der Umlauf muss sich schließen. Bliebe er am Ende stehen, käme der Nutzer über den
     * Wechsel-Knopf nie wieder zum ersten Konto zurück — das Widget hat keinen Weg zurück.
     */
    @Test
    public void nachDerLetztenStationBeginntEsVonVorn() throws Exception {
        konto("Bargeld", 1);
        konto("Giro", 2);
        new PlacesStore(ctx).addPlace("Giro", "Büro");

        List<String> stationen = umlauf(3);

        assertEquals("der Umlauf schließt sich", stationen.get(0), stationen.get(2));
        assertEquals("dazwischen liegt genau eine andere Station",
                2, new HashSet<>(stationen).size());
    }

    /**
     * Jeder Ort eines Kontos ist eine eigene Station; das Konto selbst erscheint dann <b>nicht</b>
     * zusätzlich. Daran hängt, ob der Nutzer den Kontosaldo oder den Ortssaldo sieht.
     */
    @Test
    public void mitOrtenTrittJederOrtEinzelnAuf() throws Exception {
        konto("Giro", 1);
        PlacesStore places = new PlacesStore(ctx);
        places.addPlace("Giro", "Büro");
        places.addPlace("Giro", "Zuhause");

        List<String> stationen = umlauf(2);

        assertEquals("zwei Orte, also zwei Stationen", 2, new HashSet<>(stationen).size());
        assertEquals("und keine Station für das Konto allein",
                0, stationen.stream().filter(s -> s.endsWith("/")).count());
    }

    /**
     * Die gespeicherte Auswahl kann verschwinden — das Konto wird geschlossen oder gelöscht, während
     * das Widget noch darauf steht. Dann darf {@code advance} nicht ins Leere laufen. Das ergibt sich
     * heute daraus, dass die Suche mit {@code idx = -1} endet und {@code (idx + 1) % size} auf 0
     * zeigt; hier steht es als Zusicherung, nicht als Zufall.
     */
    @Test
    public void verschwundeneAuswahlLandetBeimErstenEintrag() throws Exception {
        konto("Bargeld", 1);
        konto("Giro", 2);
        weiter();
        weiter();
        String[] vorher = jetzt();

        imHintergrund(() -> {
            AppDatabase.getInstance(ctx).clearAllTables();
            return null;
        });
        konto("Sparbuch", 1);

        weiter();

        assertArrayEquals("nicht bei der alten Auswahl hängenbleiben",
                new String[]{"Sparbuch", ""}, jetzt());
        assertEquals("und die alte Auswahl war wirklich eine andere", "Giro", vorher[0]);
    }

    /** Ohne Konten gibt es nichts durchzuschalten – und schon gar keinen Absturz. */
    @Test
    public void ohneKontenPassiertNichts() throws Exception {
        weiter();

        assertArrayEquals(new String[]{"", ""}, jetzt());
    }
}
