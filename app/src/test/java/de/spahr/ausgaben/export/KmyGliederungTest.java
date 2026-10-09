package de.spahr.ausgaben.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/** Der Zerleger, auf dem der Zeichen-für-Zeichen-Vergleich der Selbstprüfung steht. */
public class KmyGliederungTest {

    private static final String DATEI = "<?xml version=\"1.0\"?>\n<!DOCTYPE KMYMONEY-FILE>\n"
            + "<!-- vorweg -->\n<KMYMONEY-FILE>\n"
            + " <PAYEES count=\"1\"><PAYEE id=\"P1\" name=\"a &gt; b\"><ADDRESS/></PAYEE></PAYEES>\n"
            + " <TRANSACTIONS count=\"2\">\n"
            + "  <TRANSACTION id=\"T1\" memo=\"x > y\"><SPLITS><SPLIT id=\"S1\"/></SPLITS></TRANSACTION>\n"
            + "  <!-- <TRANSACTION id=\"T9\"/> -->\n"
            + "  <TRANSACTION id='T2'><SPLITS><SPLIT id=\"S1\"><TAG id=\"G1\"/></SPLIT></SPLITS></TRANSACTION>\n"
            + " </TRANSACTIONS>\n"
            + " <SCHEDULES/>\n"
            + "</KMYMONEY-FILE>\n";

    @Test
    public void kopfWurzelUndBereiche() throws Exception {
        KmyGliederung g = KmyGliederung.lesen(DATEI);
        assertTrue(g.kopf.endsWith("<!-- vorweg -->\n"));
        assertEquals("\n", g.schluss);
        assertEquals("KMYMONEY-FILE", g.wurzel.name);
        KmyGliederung.Inhalt bereiche = g.wurzel.inhalt();
        assertEquals(3, bereiche.kinder.size());
        assertEquals(4, bereiche.luecken.size());
        assertEquals("PAYEES", bereiche.kinder.get(0).name);
        assertTrue(bereiche.kinder.get(2).leer);
        assertEquals(0, bereiche.kinder.get(2).inhalt().kinder.size());
    }

    /** Ein {@code >} im Attributwert, ein Kommentar mit Tag darin und einfache Anführungszeichen. */
    @Test
    public void bloeckeMitIhrenGrenzen() throws Exception {
        KmyGliederung.Inhalt buch = KmyGliederung.lesen(DATEI).wurzel.inhalt().kind("TRANSACTIONS").inhalt();
        assertEquals(2, buch.kinder.size());
        KmyGliederung.Element t1 = buch.kinder.get(0);
        assertEquals("T1", t1.id());
        assertEquals("x > y", t1.attribute().get("memo"));
        assertTrue(t1.text().startsWith("<TRANSACTION id=\"T1\""));
        assertTrue(t1.text().endsWith("</TRANSACTION>"));
        assertEquals("T2", buch.kinder.get(1).id());
        // Der Kommentar ist kein Block, steht aber im Rest zwischen den Blöcken.
        assertEquals("<!--<TRANSACTIONid=\"T9\"/>-->", buch.rest());
        assertEquals("1", KmyGliederung.lesen(DATEI).wurzel.inhalt().kind("PAYEES").attribute().get("count"));
        assertNull(KmyGliederung.lesen(DATEI).wurzel.inhalt().kind("BUDGETS"));
    }

    @Test
    public void gleichVergleichtZeichenFuerZeichen() throws Exception {
        KmyGliederung.Element a = KmyGliederung.lesen(DATEI).wurzel.inhalt().kind("TRANSACTIONS")
                .inhalt().kinder.get(0);
        KmyGliederung.Element b = KmyGliederung.lesen("vorweg" + DATEI.substring(DATEI.indexOf("<KMY")))
                .wurzel.inhalt().kind("TRANSACTIONS").inhalt().kinder.get(0);
        assertTrue(a.gleich(b));
        KmyGliederung.Element c = KmyGliederung.lesen(DATEI.replace("x > y", "x > z")).wurzel.inhalt()
                .kind("TRANSACTIONS").inhalt().kinder.get(0);
        assertFalse(a.gleich(c));
    }

    @Test
    public void abgeschnittenerTextIstEinFehler() {
        try {
            KmyGliederung.lesen(DATEI.substring(0, DATEI.indexOf("</TRANSACTIONS>")));
            fail("hätte scheitern müssen");
        } catch (KmyGliederung.Fehler erwartet) {
            assertTrue(erwartet.getMessage(), erwartet.getMessage().contains("nicht geschlossen"));
        }
    }
}
