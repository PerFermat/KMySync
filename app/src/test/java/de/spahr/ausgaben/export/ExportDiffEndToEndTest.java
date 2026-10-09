package de.spahr.ausgaben.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import de.spahr.ausgaben.db.Booking;

/**
 * Vom Export bis zur Anzeige: Der Zeilenvergleich einer wirklich geschriebenen Datei zeigt genau die
 * Zeilen, die der Export angelegt hat – und sonst nur die drei, an denen sich ein Wert bewegt.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ExportDiffEndToEndTest {

    private final Context ctx = ApplicationProvider.getApplicationContext();

    @Test
    public void neueBuchungErscheintAlsGrueneZeilen() throws Exception {
        KmyDocument d = new KmyDocument(KmyRobustnessTest.fixture("edited.xml"), ctx);
        Booking b = new Booking();
        b.id = 5;
        b.account = "Bargeld";
        b.category = "Essen";
        b.payee = "Kiosk";
        b.amountCents = 111;
        b.createdAt = KmyDocument.parseKmyDate("2026-02-01");
        KmyExporter.Result r = new KmyExporter(d, ctx).build(Collections.singletonList(b),
                Collections.emptyList(), new HashMap<>());
        // Der Weg der Datei: gepackt hinauf, gepackt zurück, entpackt verglichen.
        String zurueck = KmyDocument.gunzip(KmyDocument.gzip(r.xml));

        ExportDiff diff = ExportDiff.von(d.xml(), zurueck);

        // Drei Zeilen Empfänger, sechs Zeilen Transaktion, dazu beide Zähler und das Änderungsdatum.
        assertEquals(12, diff.hinzu);
        assertEquals(3, diff.entfernt);
        List<String> gruen = new ArrayList<>();
        List<String> rot = new ArrayList<>();
        for (ExportDiff.Zeile z : diff.zeilen) {
            if (z.art == ExportDiff.HINZU) {
                gruen.add(z.text.trim());
                assertEquals("die Nummer zeigt auf die Zeile der neuen Datei", z.text,
                        LineDiff.zeilen(zurueck)[z.nummer - 1]);
            } else if (z.art == ExportDiff.ENTFERNT) {
                rot.add(z.text.trim());
                assertEquals("die Nummer zeigt auf die Zeile der alten Datei", z.text,
                        LineDiff.zeilen(d.xml())[z.nummer - 1]);
            }
        }
        assertTrue(gruen.contains("<SPLITS>"));
        assertTrue(gruen.contains("</TRANSACTION>"));
        assertTrue(gruen.contains("<TRANSACTIONS count=\"5\">"));
        assertTrue(rot.contains("<TRANSACTIONS count=\"4\">"));
        assertTrue(rot.contains("<PAYEES count=\"1\">"));
        int splits = 0;
        for (String z : gruen) {
            splits += z.startsWith("<SPLIT ") ? 1 : 0;
        }
        assertEquals("je Split eine eigene Zeile", 2, splits);
        // Nichts Fremdes: Keine der alten Transaktionen taucht als geändert auf.
        for (String z : gruen) {
            assertTrue(z, !z.contains("T000000000000000001") && !z.contains("T000000000000000002"));
        }
    }

    /** Ein Export ohne Wirkung auf die Datei ergäbe einen leeren Vergleich – kein Rauschen. */
    @Test
    public void unveraenderteDateiErgibtNichts() throws Exception {
        KmyDocument d = new KmyDocument(KmyRobustnessTest.fixture("edited.xml"), ctx);
        ExportDiff diff = ExportDiff.von(d.xml(), KmyDocument.gunzip(KmyDocument.gzip(d.xml())));
        assertTrue(diff.zeilen.isEmpty());
    }
}
