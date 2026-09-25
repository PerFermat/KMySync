package de.spahr.ausgaben.export;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import de.spahr.ausgaben.db.Booking;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.HashMap;

/**
 * Die Selbstprüfung vor dem Hochladen: Ein echter Export muss sie bestehen, eine beschädigte Fassung
 * nicht. Grundlage ist {@code src/test/resources/kmy/edited.xml} (vier Buchungen).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KmyExportCheckTest {

    private final Context ctx = ApplicationProvider.getApplicationContext();

    private String alt() throws Exception {
        return new KmyDocument(KmyRobustnessTest.fixture("edited.xml"), ctx).xml();
    }

    private static void besteht(String alt, String neu, int geloescht, int geschrieben) throws Exception {
        KmyExportCheck.pruefen(alt, neu, KmyDocument.gzip(neu), geloescht, geschrieben);
    }

    private static void faelltDurch(String alt, String neu, int geloescht, int geschrieben,
                                    String grundEnthaelt) throws Exception {
        try {
            KmyExportCheck.pruefen(alt, neu, KmyDocument.gzip(neu), geloescht, geschrieben);
            fail("die Prüfung hätte anschlagen müssen");
        } catch (KmyExportCheck.Failed e) {
            assertTrue(e.getMessage(), e.getMessage().contains(grundEnthaelt));
        }
    }

    @Test
    public void echterExport_bestehtDiePruefung() throws Exception {
        KmyDocument d = new KmyDocument(KmyRobustnessTest.fixture("edited.xml"), ctx);
        Booking neu = new Booking();
        neu.id = 5;
        neu.account = "Bargeld";
        neu.category = "Essen";
        neu.amountCents = 111;
        neu.isIncome = false;
        neu.createdAt = KmyDocument.parseKmyDate("2026-02-01");

        KmyExporter.Result r = new KmyExporter(d, ctx).build(Collections.singletonList(neu),
                Collections.emptyList(), new HashMap<>());

        besteht(d.xml(), r.xml, 0, r.writtenIds.size());
    }

    @Test
    public void unveraenderteDatei_besteht() throws Exception {
        String alt = alt();
        besteht(alt, alt, 0, 0);
    }

    @Test
    public void abgeschnitteneDatei_faelltDurch() throws Exception {
        String alt = alt();
        faelltDurch(alt, alt.substring(0, alt.length() / 2), 0, 0, "wohlgeformt");
    }

    @Test
    public void verlorenesKonto_faelltDurch() throws Exception {
        String alt = alt();
        // In der Testdatei sind die Konten selbstschließend geschrieben.
        int start = alt.indexOf("<ACCOUNT ");
        int ende = alt.indexOf("/>", start) + 2;
        faelltDurch(alt, alt.substring(0, start) + alt.substring(ende), 0, 0, "ACCOUNT");
    }

    @Test
    public void verschwundeneBuchung_faelltDurch() throws Exception {
        String alt = alt();
        int start = alt.indexOf("<TRANSACTION ");
        int ende = alt.indexOf("</TRANSACTION>", start) + "</TRANSACTION>".length();
        String ohne = alt.substring(0, start) + alt.substring(ende);
        faelltDurch(alt, ohne, 0, 0, "TRANSACTION");
        // Mit angekündigter Löschung und passend nachgezogenem Zähler ist dasselbe in Ordnung.
        besteht(alt, ohne.replace("<TRANSACTIONS count=\"4\"", "<TRANSACTIONS count=\"3\""), 1, 0);
    }

    @Test
    public void verschobenerZaehler_faelltDurch() throws Exception {
        String alt = alt();
        faelltDurch(alt, alt.replace("<TRANSACTIONS count=\"4\"", "<TRANSACTIONS count=\"5\""), 0, 0,
                "count");
    }

    @Test
    public void gepackteBytesWeichenAb_faelltDurch() throws Exception {
        String alt = alt();
        try {
            KmyExportCheck.pruefen(alt, alt, KmyDocument.gzip(alt + " "), 0, 0);
            fail("die Prüfung hätte anschlagen müssen");
        } catch (KmyExportCheck.Failed e) {
            assertTrue(e.getMessage(), e.getMessage().contains("gepackt"));
        }
    }
}
