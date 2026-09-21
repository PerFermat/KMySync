package de.spahr.ausgaben.receipt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import de.spahr.ausgaben.net.RemoteStorage;

/**
 * Was mit einem vorgemerkten Ordnerwechsel geschieht, wenn das Verschieben scheitert.
 *
 * <h2>Der Vorfall</h2>
 *
 * <p>Beim ersten echten Wechsel des Belegordners kam kein einziger Beleg an. Zwei Fehler trafen
 * zusammen: {@code ensureFolder} war auf SMB ein No-op – dort legt der <b>Upload</b> den Ordner an,
 * ein {@code rename} aber nicht –, also schlug das Verschieben in den frisch benannten Ordner fehl.
 * Und der Fehlerzweig warf den Vorsatz weg, sobald das Gerät online war. Der Umzug war damit nicht
 * nur gescheitert, sondern unwiederbringlich vergessen.</p>
 *
 * <p>Der zweite Fehler ist der schlimmere: Ein fehlgeschlagener Umzug muss vorgemerkt bleiben. Die
 * Datei wurde vorher aufgelistet – sie ist da. Nur „Quelle gibt es nicht" heißt wirklich, dass
 * nichts mehr zu tun ist.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ReceiptFolderMoveRetryTest {

    private static final String VON = "KMy/Belege/2026";
    private static final String NACH = "KMy/Belege-Firma/2026";
    private static final String DATEI = "aaaa1111_p1.jpg";

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        for (String e : Receipts.folderMoves(ctx)) {
            Receipts.removeFolderMove(ctx, e);
        }
        Receipts.addFolderMove(ctx, DATEI, VON, NACH);
    }

    /** Ein Stub, der beim Verschieben mit dem übergebenen Text scheitert. */
    private static final class Stub implements RemoteStorage {
        final String fehler;
        final List<String> angelegt = new ArrayList<>();

        Stub(String fehler) {
            this.fehler = fehler;
        }

        @Override
        public void ensureFolder(String folder) {
            angelegt.add(folder);
        }

        @Override
        public void move(String fromFolder, String fromName, String toFolder, String toName)
                throws IOException {
            if (fehler != null) {
                throw new IOException(fehler);
            }
        }

        @Override public void uploadText(String f, String n, String c) { }
        @Override public void uploadBytes(String f, String n, byte[] c) { }
        @Override public List<String> listFiles(String f, String ext) { return new ArrayList<>(); }
        @Override public String downloadText(String f, String n) { return ""; }
        @Override public byte[] downloadBytes(String f, String n) { return new byte[0]; }
        @Override public void testConnection() { }
    }

    @Test
    public void gelungenerUmzugStreichtDenVorsatz() {
        ReceiptFolderMove.movePending(ctx, new Stub(null));

        assertTrue(Receipts.folderMoves(ctx).isEmpty());
    }

    /** Der Zielordner muss vor dem Verschieben angelegt werden – daran ist es gescheitert. */
    @Test
    public void derZielordnerWirdAngelegt() {
        Stub stub = new Stub(null);

        ReceiptFolderMove.movePending(ctx, stub);

        assertTrue("ensureFolder muss den Zielordner bekommen", stub.angelegt.contains(NACH));
    }

    /** Der Kern: Ein Fehlschlag darf den Vorsatz nicht vernichten. */
    @Test
    public void fehlgeschlagenerUmzugBleibtVorgemerkt() {
        ReceiptFolderMove.movePending(ctx, new Stub("STATUS_ACCESS_DENIED"));

        assertEquals(1, Receipts.folderMoves(ctx).size());
        assertTrue(Receipts.folderMoves(ctx).contains(VON + "|" + NACH + "|" + DATEI));
    }

    /** Auch ein zweiter vergeblicher Anlauf behält ihn – sonst hülfe das Wiederholen nichts. */
    @Test
    public void auchNachMehrerenAnlaeufenBleibtErStehen() {
        ReceiptFolderMove.movePending(ctx, new Stub("STATUS_ACCESS_DENIED"));
        ReceiptFolderMove.movePending(ctx, new Stub("STATUS_ACCESS_DENIED"));

        assertEquals(1, Receipts.folderMoves(ctx).size());
    }

    /**
     * Die eine Ausnahme: Gibt es die Quelle nicht, ist die Datei längst umgezogen oder gelöscht –
     * dann bleibt nichts zu tun, und der Eintrag soll nicht ewig mitlaufen.
     */
    @Test
    public void fehlendeQuelleStreichtDenVorsatz() {
        ReceiptFolderMove.movePending(ctx, new Stub("STATUS_OBJECT_NAME_NOT_FOUND"));

        assertTrue(Receipts.folderMoves(ctx).isEmpty());
    }

    /** Unbrauchbare Einträge werden nicht endlos mitgeschleppt. */
    @Test
    public void unbrauchbarerEintragFaelltWeg() {
        for (String e : Receipts.folderMoves(ctx)) {
            Receipts.removeFolderMove(ctx, e);
        }
        ctx.getSharedPreferences("receipts", Context.MODE_PRIVATE).edit()
                .putStringSet(Receipts.folderFor(
                                new de.spahr.ausgaben.settings.ProfileManager(ctx).getActiveProfileId())
                                + "_folder_moves",
                        new java.util.HashSet<>(java.util.Arrays.asList("kaputt")))
                .commit();

        ReceiptFolderMove.movePending(ctx, new Stub(null));

        assertTrue(Receipts.folderMoves(ctx).isEmpty());
    }
}
