package de.spahr.ausgaben.export;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;

import de.spahr.ausgaben.db.Security;
import de.spahr.ausgaben.db.SecurityTx;

/**
 * Ein USD-Papier in einer EUR-Datei: Kurse, Kurshistorie und Beträge kommen mit dem Devisenkurs ihres
 * Tages in Euro an; der Originalkurs bleibt für die Anzeige stehen. Grundlage ist
 * {@code security-tx.xml}, auf USD umgestellt.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KmyFxConversionTest {

    private final Context ctx = ApplicationProvider.getApplicationContext();

    private static final String USD_PRICES = "<PRICEPAIR from=\"E000001\" to=\"USD\">"
            + "<PRICE date=\"2026-03-10\" price=\"50/1\" source=\"User\"/>"
            + "<PRICE date=\"2026-11-06\" price=\"180/1\" source=\"User\"/></PRICEPAIR>";

    private KmyImporter.DepotData depot(String fx) throws Exception {
        String xml = new String(KmyRobustnessTest.fixture("security-tx.xml"), StandardCharsets.UTF_8)
                .replace("trading-currency=\"EUR\"", "trading-currency=\"USD\"")
                .replace("commodity=\"EUR\"", "commodity=\"USD\"")
                // Wie KMyMoney es schreibt: value in USD, shares der Euro-Konten in Euro (Kurs 0,9).
                .replace("value=\"-101000/100\" shares=\"-101000/100\"",
                        "value=\"-101000/100\" shares=\"-90900/100\"")
                .replace("value=\"1000/100\" shares=\"1000/100\"",
                        "value=\"1000/100\" shares=\"900/100\"")
                .replace("<PRICES/>", "<PRICES>" + USD_PRICES + fx + "</PRICES>");
        KmyDocument doc = new KmyDocument(xml.getBytes(StandardCharsets.UTF_8), ctx);
        return new KmyImporter(doc, ctx).importDepot("Depot");
    }

    @Test
    public void kursUndHistorieMitDemDevisenkursIhresTages() throws Exception {
        KmyImporter.DepotData d = depot("<PRICEPAIR from=\"USD\" to=\"EUR\">"
                + "<PRICE date=\"2026-03-01\" price=\"9/10\" source=\"User\"/>"
                + "<PRICE date=\"2026-11-01\" price=\"92/100\" source=\"User\"/></PRICEPAIR>");
        Security s = d.securities.get(0);
        assertEquals(165.6, s.price, 1e-9);
        assertEquals(180, s.origPrice, 1e-9);
        assertEquals("USD", s.currency);
        assertEquals(2, d.prices.size());
        assertEquals(45, d.prices.get(0).price, 1e-9);
        assertEquals(165.6, d.prices.get(1).price, 1e-9);
    }

    @Test
    public void betraegeMitDemGebuchtenKurs() throws Exception {
        SecurityTx tx = depot("<PRICEPAIR from=\"USD\" to=\"EUR\">"
                + "<PRICE date=\"2026-03-01\" price=\"9/10\" source=\"User\"/>"
                + "<PRICE date=\"2026-11-01\" price=\"92/100\" source=\"User\"/></PRICEPAIR>")
                .transactions.get(0);
        assertEquals(90000L, tx.amountCents);
        assertEquals(900L, tx.feeCents);
    }

    @Test
    public void nurDasGegenpaarGepflegtGiltDerKehrwert() throws Exception {
        Security s = depot("<PRICEPAIR from=\"EUR\" to=\"USD\">"
                + "<PRICE date=\"2026-01-01\" price=\"125/100\" source=\"User\"/></PRICEPAIR>")
                .securities.get(0);
        assertEquals(144, s.price, 1e-9);
    }

    @Test
    public void ohneDevisenkursBleibtDerKursDerBetragKommtAusDerBuchung() throws Exception {
        KmyImporter.DepotData d = depot("");
        assertEquals(180, d.securities.get(0).price, 1e-9);
        assertEquals(0, d.securities.get(0).origPrice, 1e-9);
        // Der Euro-Betrag am Geldkonto braucht keinen Devisenkurs aus der Kursliste.
        assertEquals(90000L, d.transactions.get(0).amountCents);
    }

    /**
     * Echte KMyMoney-Datei des Nutzers ({@code fremdwaehrung-depot.kmy}): 100 € Bargeld, Kurs USD→EUR
     * 0,5, Kauf von 1 Anteil „Vontobel Fund - US Dollar Money A USD“ zu 83,49 USD. KMyMoney bucht den
     * Kauf in USD und das Bargeld mit 41,75 €.
     */
    @Test
    public void echteDateiVontobelInUsd() throws Exception {
        KmyDocument doc = new KmyDocument(KmyRobustnessTest.fixture("fremdwaehrung-depot.kmy"), ctx);
        KmyImporter.DepotData d = new KmyImporter(doc, ctx).importDepot("Depot");
        Security s = d.securities.get(0);
        assertEquals("USD", s.currency);
        assertEquals(83.49, s.origPrice, 1e-9);
        assertEquals(41.745, s.price, 1e-6);
        SecurityTx tx = d.transactions.get(0);
        assertEquals("buy", tx.action);
        assertEquals(1, tx.shares, 1e-9);
        // Der von KMyMoney gebuchte Euro-Betrag, nicht 83,49 × Tageskurs mit Rundungsfehler.
        assertEquals(4175L, tx.amountCents);
    }
}
