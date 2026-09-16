package de.spahr.ausgaben.receipt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Der Merker für einen abgebrochenen Beleg-Export: die Kodierung der Buchungsnummern und die Frage,
 * ob ein zweiter Lauf überhaupt etwas bringt.
 */
public class ReceiptExportResumeTest {

    @Test
    public void nummernUeberstehenDenRundlauf() {
        List<Long> ids = Arrays.asList(1L, 42L, 4711L);
        assertEquals(ids, ReceiptExportResume.splitIds(ReceiptExportResume.joinIds(ids)));
    }

    @Test
    public void leereListeBleibtLeer() {
        assertEquals("", ReceiptExportResume.joinIds(null));
        assertEquals("", ReceiptExportResume.joinIds(Collections.emptyList()));
        assertTrue(ReceiptExportResume.splitIds("").isEmpty());
        assertTrue(ReceiptExportResume.splitIds(null).isEmpty());
    }

    @Test
    public void unsinnFaelltWegStattAllesZuVerderben() {
        // Ein durchgerutschter Eintrag darf nicht den ganzen Merker unbrauchbar machen – dann wäre
        // der Export gar nicht mehr aufzunehmen.
        assertEquals(Arrays.asList(1L, 3L), ReceiptExportResume.splitIds("1,zwei,3"));
    }

    @Test
    public void hunderteNummernBleibenHandlich() {
        List<Long> ids = new java.util.ArrayList<>();
        for (long i = 1; i <= 238; i++) {
            ids.add(i * 1000);
        }
        String kodiert = ReceiptExportResume.joinIds(ids);
        assertTrue("sollte in die Einstellungen passen", kodiert.length() < 4000);
        assertEquals(ids, ReceiptExportResume.splitIds(kodiert));
    }

    @Test
    public void einZweiterLaufLohntNurBeiHolbarem() {
        ReceiptZip.Result r = new ReceiptZip.Result();
        r.unreachable = 3;
        assertTrue(r.worthRetrying());

        r = new ReceiptZip.Result();
        r.pending = 7;
        assertTrue(r.worthRetrying());
    }

    @Test
    public void einZweiterLaufLohntNichtBeiFehlendenOderUebersprungenen() {
        // Was der Server nicht hat, hat er auch morgen nicht.
        ReceiptZip.Result r = new ReceiptZip.Result();
        r.notFound = 22;
        assertFalse(r.worthRetrying());

        // „Nur die vorhandenen" war eine Entscheidung – die wird nicht ungefragt wieder aufgemacht.
        r = new ReceiptZip.Result();
        r.skipped = 199;
        assertFalse(r.worthRetrying());
    }

    @Test
    public void einVollstaendigerLaufLaesstNichtsOffen() {
        ReceiptZip.Result r = new ReceiptZip.Result();
        r.receipts = 238;
        r.written = 238;
        assertFalse(r.worthRetrying());
    }
}
