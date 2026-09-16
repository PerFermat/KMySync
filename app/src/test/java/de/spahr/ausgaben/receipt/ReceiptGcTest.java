package de.spahr.ausgaben.receipt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Tests für die Auswahl des Aufräumlaufs: welche Belegdateien zu keiner Buchung mehr gehören. Das
 * eigentliche Löschen fasst Dateien und Netzlaufwerk an und bleibt außen vor.
 */
public class ReceiptGcTest {

    @Test
    public void basesOf_readsTheTagsFromNotes() {
        Set<String> bases = ReceiptGc.basesOf(Arrays.asList(
                "Kaffee BELEG: abc123",
                "Rechnung BELEG: 2026_alt.jpg",
                "ohne Beleg",
                null));
        assertEquals(2, bases.size());
        assertTrue(bases.contains("abc123"));
        assertTrue(bases.contains("2026_alt"));
    }

    @Test
    public void basesOf_readsThePdfTagToo() {
        // Ohne diesen Zweig hielte der Aufräumlauf jedes PDF für herrenlos und löschte es beim nächsten Start.
        Set<String> bases = ReceiptGc.basesOf(Arrays.asList(
                "Rechnung BELEG (PDF): pdf123",
                "Kaffee BELEG: abc123"));
        assertEquals(2, bases.size());
        assertTrue(bases.contains("pdf123"));
        assertTrue(bases.contains("abc123"));
    }

    @Test
    public void orphans_keepsThePdfsOfAUsedBase() {
        List<String> files = Arrays.asList("pdf123_p1.pdf", "pdf123_p2.pdf", "weg_p1.pdf");
        List<String> orphans = ReceiptGc.orphans(files, Collections.singleton("pdf123"));
        assertEquals(Collections.singletonList("weg_p1.pdf"), orphans);
    }

    @Test
    public void orphans_keepsEveryPageAndOriginalOfAUsedBase() {
        List<String> files = Arrays.asList(
                "abc123_p1.jpg", "abc123_p2.jpg", "abc123_p2_original.jpg",
                "weg_p1.jpg", "weg_p1_original.jpg");
        List<String> orphans = ReceiptGc.orphans(files, Collections.singleton("abc123"));
        assertEquals(Arrays.asList("weg_p1.jpg", "weg_p1_original.jpg"), orphans);
    }

    @Test
    public void orphans_keepsLegacyNames() {
        List<String> files = Arrays.asList("2026_alt.jpg", "2026_alt_original.jpg", "2026_weg.jpg");
        List<String> orphans = ReceiptGc.orphans(files, Collections.singleton("2026_alt"));
        assertEquals(Collections.singletonList("2026_weg.jpg"), orphans);
    }

    @Test
    public void orphans_ignoresWorkFilesOfARunningCapture() {
        List<String> files = Arrays.asList("pend_1234.jpg", "cam_9999.jpg");
        assertTrue(ReceiptGc.orphans(files, Collections.emptySet()).isEmpty());
    }

    @Test
    public void yearFolders_laesstDenPapierkorbAus() {
        // Der Papierkorb liegt neben den Jahresordnern und ist damit selbst ein Unterordner. Zählte er
        // mit, räumte der Lauf ihn in sich selbst und schöbe das Weggeräumte eine Ebene tiefer.
        List<String> ordner = ReceiptGc.yearFolders(
                new FolderStub("2025", "2026", "Papierkorb"), "Sync/Belege");
        assertEquals(Arrays.asList("Sync/Belege/2025", "Sync/Belege/2026"), ordner);
    }

    @Test
    public void yearFolders_ohneServerLeer() {
        assertTrue(ReceiptGc.yearFolders(null, "Sync/Belege").isEmpty());
    }

    /** Liefert feste Unterordner; alles andere wird vom Aufräumlauf hier nicht angefasst. */
    private static final class FolderStub implements de.spahr.ausgaben.net.RemoteStorage {
        private final List<String> folders;

        FolderStub(String... names) {
            this.folders = Arrays.asList(names);
        }

        @Override
        public List<String> listFolders(String folder) {
            return folders;
        }

        @Override
        public List<String> listFiles(String folder, String ext) {
            return Collections.emptyList();
        }

        @Override
        public void uploadText(String folder, String fileName, String content) {
        }

        @Override
        public void uploadBytes(String folder, String fileName, byte[] content) {
        }

        @Override
        public String downloadText(String folder, String fileName) {
            return "";
        }

        @Override
        public byte[] downloadBytes(String folder, String fileName) {
            return new byte[0];
        }

        @Override
        public void testConnection() {
        }
    }

    @Test
    public void orphans_withoutAnyBaseEverythingGoes() {
        // Die Sicherheitsleine gegen die leere Buchungstabelle sitzt im Aufräumlauf selbst, nicht hier.
        assertEquals(Collections.singletonList("abc_p1.jpg"),
                ReceiptGc.orphans(Collections.singletonList("abc_p1.jpg"), Collections.emptySet()));
    }
}
