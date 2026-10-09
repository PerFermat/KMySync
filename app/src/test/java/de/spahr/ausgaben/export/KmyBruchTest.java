package de.spahr.ausgaben.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/** Beträge aus der Datei werden exakt gelesen und gerechnet. */
public class KmyBruchTest {

    @Test
    public void gleicherWertIstGleich_egalWieErDasteht() {
        assertEquals(KmyBruch.lesen("-1240/100"), KmyBruch.lesen("-62/5"));
        assertEquals(KmyBruch.lesen("-1240/100"), KmyBruch.ausCent(-1240));
        assertEquals(KmyBruch.lesen("1240/-100"), KmyBruch.ausCent(-1240));
        assertEquals(KmyBruch.lesen(" 12.40 "), KmyBruch.ausCent(1240));
        assertEquals(KmyBruch.lesen("488/25"), KmyBruch.ausCent(1952));
        assertEquals("-62/5", KmyBruch.lesen("-1240/100").toString());
    }

    @Test
    public void falscherNennerIstEinAndererWert() {
        assertNotEquals(KmyBruch.lesen("-1240/100"), KmyBruch.lesen("-1240/10"));
    }

    @Test
    public void rechnetOhneRundung() {
        // Ein Drittel dreimal ist eins – mit double oder Cent-Rundung wäre es das nicht.
        KmyBruch drittel = KmyBruch.lesen("1/3");
        assertEquals(KmyBruch.lesen("1/1"), drittel.plus(drittel).plus(drittel));
        assertTrue(KmyBruch.lesen("-218677/25").plus(KmyBruch.lesen("218677/25")).istNull());
        assertFalse(KmyBruch.lesen("1/100000000000000000000").istNull());
        assertEquals(KmyBruch.ausCent(-150), KmyBruch.ausCent(250).minus(KmyBruch.ausCent(400)));
        assertEquals(KmyBruch.ausCent(-250), KmyBruch.ausCent(250).negiert());
    }

    @Test
    public void leerGiltAlsNull() {
        assertTrue(KmyBruch.lesen("").istNull());
        assertTrue(KmyBruch.lesen(null).istNull());
        assertEquals(KmyBruch.NULL, KmyBruch.lesen("0/100"));
    }

    @Test
    public void unsinnWirdAbgelehnt() {
        for (String s : new String[]{"elf", "1/0", "1/2/3", "12,40", "/100"}) {
            try {
                KmyBruch.lesen(s);
                fail(s + " hätte abgelehnt werden müssen");
            } catch (NumberFormatException erwartet) {
                // so soll es sein
            }
        }
    }
}
