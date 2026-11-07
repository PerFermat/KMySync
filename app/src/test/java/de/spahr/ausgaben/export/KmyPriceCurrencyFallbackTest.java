package de.spahr.ausgaben.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;

/**
 * Kurse eines Wertpapiers, die in KMyMoney gegen eine andere Währung als seine Handelswährung abgelegt
 * sind, gehen nicht mehr verloren (GitHub-Issue #12): Fehlt ein Kurs in der Handelswährung, gilt die
 * Währung mit dem jüngsten Kurs.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KmyPriceCurrencyFallbackTest {

    private final Context ctx = ApplicationProvider.getApplicationContext();

    private KmyDocument doc(String prices) throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<KMYMONEY-FILE>"
                + "<TRANSACTIONS count=\"0\"></TRANSACTIONS>"
                + "<SECURITIES count=\"1\"><SECURITY id=\"E000001\" name=\"Fonds\" symbol=\"F\""
                + " trading-currency=\"INR\" pp=\"4\"/></SECURITIES>"
                + "<PRICES>" + prices + "</PRICES></KMYMONEY-FILE>";
        return new KmyDocument(xml.getBytes(StandardCharsets.UTF_8), ctx);
    }

    private static String pair(String to, String... datePrice) {
        StringBuilder sb = new StringBuilder("<PRICEPAIR from=\"E000001\" to=\"" + to + "\">");
        for (int i = 0; i < datePrice.length; i += 2) {
            sb.append("<PRICE date=\"").append(datePrice[i]).append("\" price=\"")
                    .append(datePrice[i + 1]).append("\" source=\"User\"/>");
        }
        return sb.append("</PRICEPAIR>").toString();
    }

    @Test
    public void handelswaehrungGehtVor() throws Exception {
        KmyDocument d = doc(pair("USD", "2026-11-06", "99/1") + pair("INR", "2026-11-01", "12345/100"));
        assertEquals(123.45, d.securityPrice("E000001")[0], 1e-9);
        assertEquals(1, d.securityPriceHistory("E000001").size());
    }

    @Test
    public void ohneKursInHandelswaehrungGiltDieJuengsteAndere() throws Exception {
        KmyDocument d = doc(pair("EUR", "2026-10-01", "2/1")
                + pair("E000002", "2026-11-05", "3125/100", "2026-11-06", "3150/100"));
        assertEquals(31.50, d.securityPrice("E000001")[0], 1e-9);
        assertEquals(KmyDocument.parseKmyDate("2026-11-06"), (long) d.securityPrice("E000001")[1]);
        assertEquals(2, d.securityPriceHistory("E000001").size());
    }

    @Test
    public void ohneKurseKeinKurs() throws Exception {
        assertNull(doc("").securityPrice("E000001"));
    }
}
