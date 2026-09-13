package de.spahr.ausgaben.receipt;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Tests für die Seitenvergabe mehrseitiger Belege – die Nummernwahl und das lückenlose Durchnummerieren.
 * {@code find}/{@code rename}/{@code delete} fassen Dateien und Netzlaufwerk an und bleiben außen vor.
 */
public class ReceiptPagesTest {

    @Test
    public void nextFreePage_startsAtOne() {
        assertEquals(1, ReceiptPages.nextFreePage(Collections.emptyList()));
    }

    @Test
    public void nextFreePage_legacyNameOccupiesPageOne() {
        // Altbeleg ohne Zusatz ist Seite 1 – die nächste Aufnahme bekommt die 2.
        assertEquals(2, ReceiptPages.nextFreePage(Collections.singletonList("2026_abc.jpg")));
    }

    @Test
    public void nextFreePage_afterExistingPages() {
        assertEquals(3, ReceiptPages.nextFreePage(
                Arrays.asList("2026_abc_p1.jpg", "2026_abc_p2.jpg")));
    }

    @Test
    public void nextFreePage_fillsAGap() {
        assertEquals(2, ReceiptPages.nextFreePage(
                Arrays.asList("2026_abc_p1.jpg", "2026_abc_p3.jpg")));
    }

    @Test
    public void renumber_leavesCorrectOrderAlone() {
        List<String> pages = Arrays.asList("2026_abc_p1.jpg", "2026_abc_p2.jpg");
        assertEquals(pages, ReceiptPages.renumber(pages));
    }

    @Test
    public void renumber_keepsLegacyNameOnFirstPage() {
        // Ein Altbeleg wird nicht umbenannt – sonst läge auf dem Server eine Karteileiche.
        List<String> pages = Arrays.asList("2026_abc.jpg", "2026_abc_p2.jpg");
        assertEquals(pages, ReceiptPages.renumber(pages));
    }

    @Test
    public void renumber_closesGapAfterDeletingMiddlePage() {
        assertEquals(Arrays.asList("2026_abc_p1.jpg", "2026_abc_p2.jpg"),
                ReceiptPages.renumber(Arrays.asList("2026_abc_p1.jpg", "2026_abc_p3.jpg")));
    }

    @Test
    public void renumber_promotesSecondPageWhenTheFirstIsGone() {
        // Die Basis – und damit die UUID der Buchung – bleibt dabei erhalten.
        assertEquals(Collections.singletonList("2026_abc_p1.jpg"),
                ReceiptPages.renumber(Collections.singletonList("2026_abc_p2.jpg")));
    }

    @Test
    public void nextFreePage_countsPdfsLikePages() {
        assertEquals(3, ReceiptPages.nextFreePage(Arrays.asList("abc_p1.pdf", "abc_p2.pdf")));
    }

    @Test
    public void renumber_keepsThePdfExtension() {
        // Ohne diese Rücksicht würde ein nachrückendes PDF beim Umbenennen zur .jpg-Datei.
        assertEquals(Collections.singletonList("abc_p1.pdf"),
                ReceiptPages.renumber(Collections.singletonList("abc_p2.pdf")));
    }

    @Test
    public void firstPageName_istSeiteEinsDerBasis() {
        assertEquals("abc_p1.pdf", ReceiptPages.firstPageName("abc", NoteReceipt.PDF));
        assertEquals("abc_p1.jpg", ReceiptPages.firstPageName("abc", NoteReceipt.JPG));
    }

    @Test
    public void firstPageName_laesstAltbelegeUnangetastet() {
        // Ein Foto mit Jahres-Präfix benennt seine Datei selbst; daraus „2026_abc_p1.jpg" zu machen
        // würde auf dem Server ins Leere greifen.
        assertEquals("2026_abc.jpg", ReceiptPages.firstPageName("2026_abc.jpg", NoteReceipt.JPG));
    }
}
