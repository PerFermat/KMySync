package de.spahr.ausgaben.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Dialog;
import android.os.Looper;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityOptionsCompat;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.receipt.NoteReceipt;
import de.spahr.ausgaben.receipt.ReceiptPages;
import de.spahr.ausgaben.receipt.Receipts;

/**
 * Der Beleg-Export lag bis 2.1 als Verdrahtung in {@code MainActivity} und war dort von keinem Test
 * erreichbar — 13 Methoden, die nur am Gerät zu prüfen waren. Seit er in
 * {@link ReceiptExportController} steht, geht das hier.
 *
 * <p>Geprüft wird der <b>Einstieg</b>: Was passiert, bis der Datei-Wähler aufgeht oder eben nicht.
 * Das Packen selbst ersetzt keine Prüfung hier — dafür ist {@code ReceiptZip} zuständig und dort mit
 * eigenen Tests hinterlegt.</p>
 *
 * <p>Der Weg gabelt sich an einer Stelle, die man leicht übersieht: Liegen alle Belege schon auf dem
 * Gerät, geht es ohne Rückfrage zum Speicherdialog. Fehlt einer, kommt erst die Frage, ob vom Server
 * geholt werden soll. Beide Äste stehen unten, und unterschieden werden sie allein dadurch, ob die
 * Belegdatei im Verzeichnis liegt.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ReceiptExportControllerTest {

    /** Eine leere Maske im Erscheinungsbild der App – wie in {@code BackupRestoreControllerTest}. */
    public static class LeereMaske extends AppCompatActivity {
        @Override
        protected void onCreate(android.os.Bundle savedInstanceState) {
            setTheme(R.style.Theme_Ausgaben);
            super.onCreate(savedInstanceState);
        }
    }

    /**
     * Ein Datei-Wähler, der nichts öffnet, sondern nur merkt, womit er gerufen wurde. Mehr braucht es
     * nicht: Ob der Speicherdialog aufgeht, ist genau die Frage, und der Dateiname ist die Antwort.
     */
    private static final class MerkenderWaehler extends ActivityResultLauncher<String> {
        private final List<String> aufrufe = new ArrayList<>();

        @Override
        public void launch(String input, ActivityOptionsCompat options) {
            aufrufe.add(input);
        }

        @Override
        public void unregister() {
        }

        @Override
        public ActivityResultContract<String, ?> getContract() {
            return new ActivityResultContracts.CreateDocument("application/zip");
        }
    }

    private LeereMaske maske;
    private MerkenderWaehler waehler;
    private ReceiptExportController regler;

    @Before
    public void aufbauen() {
        maske = Robolectric.buildActivity(LeereMaske.class).setup().get();
        waehler = new MerkenderWaehler();
        // Das Band ohne Ansichten: Alle seine Methoden prüfen auf null, und für den Einstieg zeigt es
        // ohnehin nichts an.
        regler = new ReceiptExportController(maske, new Repository(maske),
                new ImportBanner(null, null, null, null), waehler);
    }

    @After
    public void aufraeumen() {
        Receipts.reset(maske);
    }

    /** Eine Buchung mit Beleg in der Notiz; {@code null} für „ohne Beleg". */
    private static Booking buchung(long id, String belegDatei) {
        Booking b = new Booking();
        b.id = id;
        b.payee = "Bäckerei";
        b.amountCents = 250;
        b.createdAt = System.currentTimeMillis();
        b.note = belegDatei == null ? "Frühstück" : "Frühstück BELEG: " + belegDatei;
        return b;
    }

    /**
     * Legt die Belegdatei wirklich an – nur dann gilt der Beleg als „liegt schon hier".
     *
     * <p>Wie die Datei zum Tag heißt, entscheidet {@link ReceiptPages#firstPageName}; hier wird sie
     * deshalb erfragt und nicht geraten. Ein selbst zusammengesetzter Name ginge am Schema vorbei, und
     * der Test prüfte dann still den falschen Ast.</p>
     */
    private void belegAblegen(String tagName) throws Exception {
        File f = Receipts.localFile(maske, ReceiptPages.firstPageName(tagName, NoteReceipt.JPG));
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        java.nio.file.Files.write(f.toPath(), new byte[]{1, 2, 3});
        assertTrue("Vorbedingung: die Belegdatei liegt da", f.exists());
    }

    /** Auf den Rückruf aus dem Repository warten – gelesen wird im Hintergrund. */
    private void warte() throws InterruptedException {
        for (int versuch = 0; versuch < 300; versuch++) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (!waehler.aufrufe.isEmpty() || ShadowDialog.getLatestDialog() != null
                    || ShadowToast.getTextOfLatestToast() != null) {
                return;
            }
            Thread.sleep(10);
        }
    }

    /**
     * Trägt keine der Buchungen einen Beleg, bleibt es bei einer Meldung. Ein Speicherdialog, an
     * dessen Ende eine leere Datei stünde, hilft niemandem — und war der Fall, den man am Gerät am
     * leichtesten übersieht, weil er wie „nichts passiert" aussieht.
     */
    @Test
    public void ohneBelegKommtNurEineMeldung() throws Exception {
        regler.start(Arrays.asList(buchung(1, null), buchung(2, null)));
        warte();

        assertEquals(maske.getString(R.string.receipt_export_none), ShadowToast.getTextOfLatestToast());
        assertTrue("kein Speicherdialog", waehler.aufrufe.isEmpty());
        assertNull("und auch sonst kein Dialog", ShadowDialog.getLatestDialog());
    }

    /**
     * Liegen alle Belege schon auf dem Gerät, gibt es nichts zu fragen: Es geht gleich zum
     * Speicherdialog, und der bekommt einen Namen mit Zeitstempel.
     */
    @Test
    public void liegenDieBelegeSchonHierGehtEsGleichZumSpeichern() throws Exception {
        belegAblegen("2026_abc123.jpg");
        regler.start(Arrays.asList(buchung(1, "2026_abc123.jpg"), buchung(2, null)));
        warte();

        assertEquals("genau ein Speicherdialog", 1, waehler.aufrufe.size());
        String name = waehler.aufrufe.get(0);
        assertTrue("Dateiname war: " + name, name.startsWith("belege-") && name.endsWith(".zip"));
        assertNull("keine Rückfrage, es fehlt ja nichts", ShadowDialog.getLatestDialog());
    }

    /**
     * Fehlt ein Beleg, kommt erst die Frage nach dem Nachladen — und der Speicherdialog noch nicht.
     * Die Frage steht da auch im WLAN: Ein paar hundert Dateien zu holen dauert, und wer das vorher
     * weiß, entscheidet anders.
     */
    @Test
    public void fehltEinBelegKommtErstDieFrage() throws Exception {
        regler.start(Arrays.asList(buchung(1, "nicht-vorhanden.jpg")));
        warte();

        Dialog dialog = ShadowDialog.getLatestDialog();
        assertTrue("es kommt eine Rückfrage", dialog != null && dialog.isShowing());
        assertTrue("und noch kein Speicherdialog", waehler.aufrufe.isEmpty());
    }

    /**
     * {@code onZipPicked} ohne vorherigen Lauf darf nicht stürzen.
     *
     * <p>Das ist kein erfundener Fall: Der Datei-Wähler ist ein eigener Bildschirm, und Android darf
     * die Maske darunter abräumen. Kommt der Nutzer mit einer gewählten Datei zurück, ist die Maske
     * neu — und die Merkliste leer.</p>
     */
    @Test
    public void einZielOhneVorherigenLaufTutNichts() {
        regler.onZipPicked(android.net.Uri.parse("content://test/egal.zip"));

        assertNull(ShadowToast.getTextOfLatestToast());
        assertNull(ShadowDialog.getLatestDialog());
    }

    /** {@code detach()} ohne laufenden Export ist ebenso harmlos – die Maske ruft es immer. */
    @Test
    public void detachOhneLaufIstHarmlos() {
        regler.detach();
        regler.detach();
    }

    /** Die Namen, die nach dem Umzug nur noch in dieser Klasse stehen dürfen. */
    private static final String[] UMGEZOGEN = {
            "askReceiptDownload", "resumeReceiptExport", "startReceiptZipPicker",
            "showExportBannerCancel", "askCancelReceiptExport", "writeReceiptZip",
            "reportReceiptZip", "zipLooksComplete", "uebersetzteBewegungsarten"};

    private static String ohneKommentare(String pfad) throws java.io.IOException {
        String quelle = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(pfad)),
                java.nio.charset.StandardCharsets.UTF_8);
        return quelle.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    /**
     * Der Umzug bleibt vollständig: Keiner der umgezogenen Namen steht wieder in {@code MainActivity}.
     *
     * <p>Ohne das könnte beim nächsten Mal eine Hälfte zurückwandern – und weil beides kompiliert und
     * beides läuft, fiele es niemandem auf. Kommentare fallen vorher heraus, sonst schlüge der Wächter
     * an der Erklärung an, warum dort nichts mehr steht.</p>
     */
    @Test
    public void derUmzugIstVollstaendig() throws Exception {
        String maskenQuelle = ohneKommentare("src/main/java/de/spahr/ausgaben/ui/MainActivity.java");
        List<String> geblieben = new ArrayList<>();
        for (String name : UMGEZOGEN) {
            if (maskenQuelle.contains(name)) {
                geblieben.add(name);
            }
        }
        assertEquals("Beleg-Export gehört in ReceiptExportController, nicht zurück in die Maske",
                "[]", geblieben.toString());
    }

    /**
     * Gegenprobe: Der Wächter muß die Namen überhaupt finden können. Ohne sie bewiese er oben nur,
     * daß eine Suche durch eine Datei läuft, die er vielleicht gar nicht liest.
     */
    @Test
    public void derWaechterFindetDieNamenAmNeuenOrt() throws Exception {
        String reglerQuelle =
                ohneKommentare("src/main/java/de/spahr/ausgaben/ui/ReceiptExportController.java");
        List<String> fehlen = new ArrayList<>();
        for (String name : new String[]{"askDownload", "resume", "zielWaehlen", "askCancel",
                "schreibe", "report", "zipLooksComplete", "uebersetzteBewegungsarten"}) {
            if (!reglerQuelle.contains(name)) {
                fehlen.add(name);
            }
        }
        assertEquals("im Regler fehlen Methoden, die dort stehen müßten", "[]", fehlen.toString());
    }
}
