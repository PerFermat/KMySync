package de.spahr.ausgaben.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import de.spahr.ausgaben.receipt.NoteReceipt;

/**
 * Die Buchführung der Belegseiten im Buchungseditor – ohne Kamera, ohne Netz. Geprüft wird, was beim
 * Öffnen und beim Speichern einer Kopie mit den Seiten geschieht.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ReceiptPagesControllerTest {

    private ReceiptPagesController receipts;
    private int changes;

    @Before
    public void maske() {
        // Nur erzeugt, nicht gestartet: Die Launcher müssen vor STARTED registriert werden.
        AppCompatActivity activity = Robolectric.buildActivity(AppCompatActivity.class).create().get();
        activity.setTheme(com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar);
        LinearLayout row = new LinearLayout(activity);
        ReceiptPagesController.Host host = new ReceiptPagesController.Host() {
            @Override
            public boolean isReadOnly() {
                return false;
            }

            @Override
            public int receiptYear() {
                return 2026;
            }

            @Override
            public boolean hasBooking() {
                return false;
            }

            @Override
            public void onReceiptPagesChanged() {
                changes++;
            }

            @Override
            public void saveAfterRemovingMissing() {
            }
        };
        receipts = new ReceiptPagesController(activity, host, row, new LinearLayout(activity),
                new LinearLayout(activity), null);
    }

    @Test
    public void ohneBelegTagBleibtEsLeer() {
        receipts.load("Einkauf GPS: 50.1,8.6", 2026);
        assertTrue(receipts.isEmpty());
    }

    /** Seite 1 steht sofort da, noch bevor die Suche im Hintergrund antwortet – keine leere Zeile. */
    @Test
    public void mitBelegTagStehtSeiteEinsSofort() {
        String note = NoteReceipt.withFileName("Einkauf", NoteReceipt.tagOf(
                NoteReceipt.pageName(NoteReceipt.newBase(), 1, NoteReceipt.JPG)));
        receipts.load(note, 2026);
        assertFalse(receipts.isEmpty());
    }

    /** „Als neue speichern": der Beleg der Vorlage wird nicht mitgenommen. */
    @Test
    public void kopieUebernimmtDenBelegDerVorlageNicht() {
        String note = NoteReceipt.withFileName("Einkauf", NoteReceipt.tagOf(
                NoteReceipt.pageName(NoteReceipt.newBase(), 1, NoteReceipt.JPG)));
        receipts.load(note, 2026);

        String saved = receipts.withReceiptTag("Einkauf", System.currentTimeMillis(), true);

        assertEquals("Einkauf", saved);
        assertTrue(receipts.isEmpty());
    }

    @Test
    public void leerenVergisstAlleSeiten() {
        String note = NoteReceipt.withFileName("x", NoteReceipt.tagOf(
                NoteReceipt.pageName(NoteReceipt.newBase(), 1, NoteReceipt.JPG)));
        receipts.load(note, 2026);
        receipts.clear();
        assertTrue(receipts.isEmpty());
    }
}
