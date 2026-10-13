package de.spahr.ausgaben.receipt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Charakterisierungstests für {@link NoteReceipt} – der Beleg-Verweis im Notizfeld (GPS bleibt unberührt). */
public class NoteReceiptTest {

    @Test
    public void fileName_readsTag() {
        assertEquals("2025_ab12.jpg", NoteReceipt.fileName("Kaffee BELEG: 2025_ab12.jpg"));
    }

    @Test
    public void fileName_absentOrNull() {
        assertNull(NoteReceipt.fileName("nur eine Notiz"));
        assertNull(NoteReceipt.fileName(null));
    }

    @Test
    public void withFileName_appendsAndReplaces() {
        String n1 = NoteReceipt.withFileName("Kaffee", "2025_a.jpg");
        assertEquals("Kaffee\n\nBELEG: 2025_a.jpg", n1);
        // Ersetzen: kein doppelter Tag.
        String n2 = NoteReceipt.withFileName(n1, "2025_b.jpg");
        assertEquals("Kaffee\n\nBELEG: 2025_b.jpg", n2);
    }

    @Test
    public void withFileName_keepsGpsTag() {
        String note = "Kaffee GPS: 48.1, 11.5";
        String out = NoteReceipt.withFileName(note, "2025_a.jpg");
        assertTrue(out.contains("GPS: 48.1, 11.5"));
        assertEquals("2025_a.jpg", NoteReceipt.fileName(out));
    }

    @Test
    public void strip_removesOnlyBeleg() {
        assertEquals("Kaffee GPS: 48.1, 11.5",
                NoteReceipt.strip("Kaffee GPS: 48.1, 11.5 BELEG: 2025_a.jpg"));
        assertEquals("Kaffee", NoteReceipt.strip("BELEG: 2025_a.jpg Kaffee"));
        assertEquals("", NoteReceipt.strip("BELEG: 2025_a.jpg"));
    }

    @Test
    public void pdfName_readsItsOwnTag() {
        assertEquals("ab12", NoteReceipt.pdfName("Rechnung BELEG (PDF): ab12"));
        assertNull(NoteReceipt.pdfName("Kaffee BELEG: 2025_a.jpg"));
    }

    @Test
    public void fileName_doesNotSeeThePdfTag() {
        // Die beiden Muster dürfen sich nicht überschneiden: auf BELEG folgt hier kein Doppelpunkt.
        assertNull(NoteReceipt.fileName("Rechnung BELEG (PDF): ab12"));
    }

    @Test
    public void withPdfName_appendsAndKeepsGps() {
        String out = NoteReceipt.withPdfName("Rechnung GPS: 48.1, 11.5", "ab12");
        assertEquals("Rechnung GPS: 48.1, 11.5\n\nBELEG (PDF): ab12", out);
        assertEquals("ab12", NoteReceipt.pdfName(out));
    }

    @Test
    public void theTwoReceiptTagsExcludeEachOther() {
        // Eine Buchung trägt entweder Fotoseiten oder PDFs – der jeweils andere Tag fällt weg.
        String pdf = NoteReceipt.withPdfName("Kaffee BELEG: 2025_a.jpg", "ab12");
        assertEquals("Kaffee\n\nBELEG (PDF): ab12", pdf);
        assertNull(NoteReceipt.fileName(pdf));
        String foto = NoteReceipt.withFileName(pdf, "cd34_p1.jpg");
        assertEquals("Kaffee\n\nBELEG: cd34_p1.jpg", foto);
        assertNull(NoteReceipt.pdfName(foto));
    }

    @Test
    public void strip_removesBothReceiptTags() {
        assertEquals("Rechnung GPS: 48.1, 11.5",
                NoteReceipt.strip("Rechnung GPS: 48.1, 11.5 BELEG (PDF): ab12"));
        assertEquals("", NoteReceipt.strip("BELEG (PDF): ab12"));
    }

    @Test
    public void pdfPagesShareTheNamingOfPhotos() {
        assertEquals("ab12_p2.pdf", NoteReceipt.pageName("ab12", 2, NoteReceipt.PDF));
        assertEquals("ab12", NoteReceipt.baseOf("ab12_p2.pdf"));
        assertEquals(2, NoteReceipt.pageOf("ab12_p2.pdf"));
        assertTrue(NoteReceipt.isPdf("ab12_p2.pdf"));
        assertFalse(NoteReceipt.isPdf("ab12_p2.jpg"));
    }

    @Test
    public void newFileName_isPageOneWithoutYear() {
        String f = NoteReceipt.newFileName();
        assertTrue(f.endsWith("_p1.jpg"));
        // Das Jahr steckt nicht mehr im Namen, sondern im Jahresordner auf dem Server.
        assertEquals(-1, NoteReceipt.yearOf(f));
    }

    @Test
    public void newBase_isABareUuid() {
        String base = NoteReceipt.newBase();
        assertEquals(32, base.length());
        assertTrue(base.matches("[0-9a-f]{32}"));
    }

    @Test
    public void pageName_sharesTheBase() {
        String base = NoteReceipt.newBase();
        assertEquals(base + "_p1.jpg", NoteReceipt.pageName(base, 1));
        assertEquals(base + "_p7.jpg", NoteReceipt.pageName(base, 7));
        assertEquals(base, NoteReceipt.baseOf(NoteReceipt.pageName(base, 7)));
    }

    @Test
    public void tagOf_shortForNewLongForLegacy() {
        // Neu: in die Notiz kommt nur die UUID.
        assertEquals("abc123", NoteReceipt.tagOf("abc123_p1.jpg"));
        // Altbeleg mit Jahres-Präfix behält seinen vollen Dateinamen, damit das Memo stabil bleibt.
        assertEquals("2026_abc.jpg", NoteReceipt.tagOf("2026_abc.jpg"));
    }

    @Test
    public void baseOf_handlesTheBareUuidFromTheNote() {
        assertEquals("abc123", NoteReceipt.baseOf("abc123"));
        assertEquals("abc123", NoteReceipt.baseOf("abc123_p3.jpg"));
        assertEquals("abc123", NoteReceipt.baseOf("abc123_p3_original.jpg"));
    }

    @Test
    public void pageSuffix_bothNotationsAreRead() {
        // Geschrieben wird das sprachneutrale _p; _Seite stammt aus einer früheren Fassung.
        assertEquals(2, NoteReceipt.pageOf("abc_p2.jpg"));
        assertEquals(2, NoteReceipt.pageOf("abc_Seite2.jpg"));
        assertEquals("abc", NoteReceipt.baseOf("abc_Seite2.jpg"));
        assertTrue(NoteReceipt.hasPageSuffix("abc_p2.jpg"));
        assertTrue(NoteReceipt.hasPageSuffix("abc_Seite2.jpg"));
        // Ein Altbeleg der ersten Stunde trägt gar keinen Zusatz.
        assertFalse(NoteReceipt.hasPageSuffix("2026_abc.jpg"));
    }

    @Test
    public void baseOf_stripsPageAndOriginal() {
        assertEquals("2026_abc", NoteReceipt.baseOf("2026_abc.jpg"));
        assertEquals("2026_abc", NoteReceipt.baseOf("2026_abc_Seite2.jpg"));
        assertEquals("2026_abc", NoteReceipt.baseOf("2026_abc_Seite2_original.jpg"));
        assertEquals("2026_abc", NoteReceipt.baseOf("2026_abc_original.jpg"));
        assertNull(NoteReceipt.baseOf(null));
    }

    @Test
    public void pageOf_defaultsToOneForTheOldNotation() {
        assertEquals(1, NoteReceipt.pageOf("2026_abc.jpg"));
        assertEquals(1, NoteReceipt.pageOf("2026_abc_Seite1.jpg"));
        assertEquals(3, NoteReceipt.pageOf("2026_abc_Seite3.jpg"));
        assertEquals(3, NoteReceipt.pageOf("2026_abc_Seite3_original.jpg"));
        assertEquals(1, NoteReceipt.pageOf(null));
    }

    @Test
    public void originalName_ofAPage() {
        assertEquals("2026_abc_Seite2_original.jpg", NoteReceipt.originalName("2026_abc_Seite2.jpg"));
    }

    @Test
    public void yearOf_invalid() {
        assertEquals(-1, NoteReceipt.yearOf("noprefix.jpg"));
        assertEquals(-1, NoteReceipt.yearOf(null));
    }

    @Test
    public void originalName_insertsBeforeExtension() {
        assertEquals("2026_ab12_original.jpg", NoteReceipt.originalName("2026_ab12.jpg"));
        // Das Jahres-Präfix bleibt lesbar – davon hängt der Jahresordner beim Hochladen ab.
        assertEquals(2026, NoteReceipt.yearOf(NoteReceipt.originalName("2026_ab12.jpg")));
    }

    @Test
    public void originalName_idempotentAndLenient() {
        assertEquals("2026_ab12_original.jpg", NoteReceipt.originalName("2026_ab12_original.jpg"));
        assertEquals("ohneEndung_original", NoteReceipt.originalName("ohneEndung"));
        assertNull(NoteReceipt.originalName(null));
    }

    @Test
    public void isOriginal_onlyForTheCopy() {
        assertTrue(NoteReceipt.isOriginal("2026_ab12_original.jpg"));
        assertFalse(NoteReceipt.isOriginal("2026_ab12.jpg"));
        assertFalse(NoteReceipt.isOriginal(null));
    }

    /**
     * Die Zusätze stehen durch eine Leerzeile getrennt – untereinander und vom eigenen Text. Gelesen
     * werden sie in der neuen Form genauso wie in der alten einzeiligen, die im Bestand steht.
     */
    @Test
    public void zusaetzeStehenDurchLeerzeilenGetrennt() {
        String note = NoteReceipt.append("Wocheneinkauf", "GPS: 48.1, 11.5");
        assertEquals("Wocheneinkauf\n\nGPS: 48.1, 11.5", note);
        note = NoteReceipt.withFileName(note, "ab12");
        assertEquals("Wocheneinkauf\n\nGPS: 48.1, 11.5\n\nBELEG: ab12", note);
        assertEquals("ab12", NoteReceipt.fileName(note));
        assertEquals("Wocheneinkauf\n\nGPS: 48.1, 11.5", NoteReceipt.strip(note));
        // Ohne Text davor keine führende Leerzeile.
        assertEquals("GPS: 48.1, 11.5", NoteReceipt.append("", "GPS: 48.1, 11.5"));
        // Die alte Form bleibt lesbar.
        assertEquals("ab12", NoteReceipt.fileName("Wocheneinkauf GPS: 48.1, 11.5 BELEG: ab12"));
    }
}
