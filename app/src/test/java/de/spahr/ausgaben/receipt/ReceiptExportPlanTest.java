package de.spahr.ausgaben.receipt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.spahr.ausgaben.db.Booking;

/**
 * Die Aufteilung vor dem Beleg-Export: Was liegt schon hier, was müsste geladen werden – und in
 * welcher Reihenfolge wird gepackt.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ReceiptExportPlanTest {

    private Context ctx;

    @Before
    public void setUp() throws Exception {
        ctx = ApplicationProvider.getApplicationContext();
        File[] alt = Receipts.dir(ctx).listFiles();
        if (alt != null) {
            for (File f : alt) {
                f.delete();
            }
        }
    }

    /** Legt eine Belegdatei im lokalen Ordner an. */
    private void lokal(String name) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(Receipts.localFile(ctx, name))) {
            fos.write(new byte[]{1, 2, 3});
        }
    }

    private static Booking mitBeleg(long id, String note) {
        Booking b = new Booking();
        b.id = id;
        b.note = note;
        b.payee = "Irgendwer";
        b.createdAt = 1_756_000_000_000L; // 2025
        return b;
    }

    private static List<ReceiptExportJobs.Job> jobs(Booking... bookings) {
        return ReceiptExportJobs.collect(new ArrayList<>(Arrays.asList(bookings)));
    }

    @Test
    public void teiltNachDemWasSchonDaIst() throws Exception {
        lokal("aaa_p1.pdf");
        ReceiptExportPlan plan = ReceiptExportPlan.of(ctx, jobs(
                mitBeleg(1, "BELEG (PDF): aaa"),
                mitBeleg(2, "BELEG (PDF): bbb")));
        assertEquals(1, plan.local.size());
        assertEquals("aaa", plan.local.get(0).tagName);
        assertEquals(1, plan.remote.size());
        assertEquals("bbb", plan.remote.get(0).tagName);
        assertTrue(plan.needsDownload());
    }

    @Test
    public void ohneNachzuladendeErübrigtSichDieFrage() throws Exception {
        lokal("aaa_p1.pdf");
        ReceiptExportPlan plan = ReceiptExportPlan.of(ctx, jobs(mitBeleg(1, "BELEG (PDF): aaa")));
        assertFalse(plan.needsDownload());
    }

    @Test
    public void ordered_stelltDieVorhandenenVoran() throws Exception {
        // Das ist die Zusage: Bricht das Nachladen ab, sind die lokalen längst in der Datei.
        lokal("ccc_p1.pdf");
        ReceiptExportPlan plan = ReceiptExportPlan.of(ctx, jobs(
                mitBeleg(1, "BELEG (PDF): aaa"),
                mitBeleg(2, "BELEG (PDF): bbb"),
                mitBeleg(3, "BELEG (PDF): ccc")));
        List<String> reihenfolge = new ArrayList<>();
        for (ReceiptExportJobs.Job j : plan.ordered()) {
            reihenfolge.add(j.tagName);
        }
        assertEquals(Arrays.asList("ccc", "aaa", "bbb"), reihenfolge);
    }

    @Test
    public void einAltbelegMitJahresPraefixWirdAnSeinemEigenenNamenErkannt() throws Exception {
        // Der Tag benennt hier die Datei selbst – wer stur „_p1" anhängte, hielte ihn für fehlend.
        lokal("2025_alt.jpg");
        ReceiptExportPlan plan = ReceiptExportPlan.of(ctx, jobs(mitBeleg(1, "BELEG: 2025_alt.jpg")));
        assertEquals(1, plan.local.size());
        assertFalse(plan.needsDownload());
    }

    @Test
    public void eineFehlendeFolgeseiteMachtDenBelegNichtZumDownloadFall() throws Exception {
        // Seite 1 ist da, Seite 2 nicht: Der Beleg gilt als vorhanden, den Rest holt der Lauf nebenbei.
        lokal("aaa_p1.pdf");
        ReceiptExportPlan plan = ReceiptExportPlan.of(ctx, jobs(mitBeleg(1, "BELEG (PDF): aaa")));
        assertEquals(1, plan.local.size());
        assertEquals(Collections.emptyList(), plan.remote);
    }
}
