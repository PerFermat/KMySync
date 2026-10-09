package de.spahr.ausgaben.export;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Der Zeilenvergleich, auf dem die Anzeige „was hat der Export geändert" steht. */
public class LineDiffTest {

    private static String[] z(String... zeilen) {
        return zeilen;
    }

    /** Die Zeilen, die als entfernt bzw. hinzugekommen gelten – als „-b +x". */
    private static String kurz(String[] alt, String[] neu) {
        LineDiff d = LineDiff.vergleiche(alt, neu);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < alt.length; i++) {
            if (d.entfernt[i]) {
                sb.append('-').append(alt[i]).append(' ');
            }
        }
        for (int j = 0; j < neu.length; j++) {
            if (d.hinzu[j]) {
                sb.append('+').append(neu[j]).append(' ');
            }
        }
        return sb.toString().trim();
    }

    /** Wendet das Ergebnis an: aus {@code alt} muss damit genau {@code neu} werden. */
    private static void stimmt(String[] alt, String[] neu) {
        LineDiff d = LineDiff.vergleiche(alt, neu);
        List<String> gemeinsamAlt = new ArrayList<>();
        for (int i = 0; i < alt.length; i++) {
            if (!d.entfernt[i]) {
                gemeinsamAlt.add(alt[i]);
            }
        }
        List<String> gemeinsamNeu = new ArrayList<>();
        for (int j = 0; j < neu.length; j++) {
            if (!d.hinzu[j]) {
                gemeinsamNeu.add(neu[j]);
            }
        }
        assertEquals("was bleibt, muss in beiden dasselbe sein", gemeinsamAlt, gemeinsamNeu);
    }

    @Test
    public void gleicheTexteHabenKeinenUnterschied() {
        assertEquals("", kurz(z("a", "b", "c"), z("a", "b", "c")));
        assertEquals("", kurz(z(), z()));
    }

    @Test
    public void einfuegenLoeschenErsetzen() {
        assertEquals("+x", kurz(z("a", "b", "c"), z("a", "x", "b", "c")));
        assertEquals("-b", kurz(z("a", "b", "c"), z("a", "c")));
        assertEquals("-b +x", kurz(z("a", "b", "c"), z("a", "x", "c")));
    }

    @Test
    public void amAnfangUndAmEnde() {
        assertEquals("+x", kurz(z("a", "b"), z("x", "a", "b")));
        assertEquals("+x", kurz(z("a", "b"), z("a", "b", "x")));
        assertEquals("-a", kurz(z("a", "b"), z("b")));
        assertEquals("-b", kurz(z("a", "b"), z("a")));
        assertEquals("+a +b", kurz(z(), z("a", "b")));
        assertEquals("-a -b", kurz(z("a", "b"), z()));
    }

    /** Zwei Änderungen weit auseinander – der Fall eines Exports: eine Buchung geändert, eine angehängt. */
    @Test
    public void zweiStellen() {
        assertEquals("-b -e +B +x", kurz(z("a", "b", "c", "d", "e", "f"), z("a", "B", "c", "d", "x", "f")));
    }

    /** Gleichlautende Zeilen (jedes {@code </SPLITS>} sieht gleich aus) verwirren den Vergleich nicht. */
    @Test
    public void wiederholteZeilen() {
        String[] alt = z("<T1>", "<S>", "</S>", "</T>", "<T2>", "<S>", "</S>", "</T>");
        String[] neu = z("<T1>", "<S>", "</S>", "</T>", "<T3>", "<S>", "</S>", "</T>", "<T2>", "<S>",
                "</S>", "</T>");
        LineDiff d = LineDiff.vergleiche(alt, neu);
        int hinzu = 0;
        for (boolean b : d.hinzu) {
            hinzu += b ? 1 : 0;
        }
        assertEquals(4, hinzu);
        for (boolean b : d.entfernt) {
            assertTrue(!b);
        }
        stimmt(alt, neu);
    }

    @Test
    public void zeilenTrennenAmZeilenende() {
        assertArrayEquals(z("a", "b"), LineDiff.zeilen("a\nb\n"));
        assertArrayEquals(z("a", "b"), LineDiff.zeilen("a\nb"));
        assertArrayEquals(z("a", "", "b"), LineDiff.zeilen("a\n\nb"));
        assertArrayEquals("das \\r bleibt an der Zeile", z("a\r", "b\r"), LineDiff.zeilen("a\r\nb\r\n"));
        assertArrayEquals(z(), LineDiff.zeilen(""));
    }

    /** Eine große Datei mit wenigen Änderungen: schnell, und das Ergebnis geht auf. */
    @Test
    public void grosseDateiMitWenigenAenderungen() {
        int n = 200_000;
        String[] alt = new String[n];
        for (int i = 0; i < n; i++) {
            alt[i] = "Zeile " + (i % 5000) + " von Block " + (i / 5000);
        }
        List<String> liste = new ArrayList<>(java.util.Arrays.asList(alt));
        liste.set(1000, "geändert");
        liste.remove(50_000);
        liste.add(120_000, "neu 1");
        liste.add(120_001, "neu 2");
        liste.add("ganz am Ende");
        String[] neu = liste.toArray(new String[0]);

        long start = System.currentTimeMillis();
        LineDiff d = LineDiff.vergleiche(alt, neu);
        assertTrue("zu langsam: " + (System.currentTimeMillis() - start) + " ms",
                System.currentTimeMillis() - start < 5000);
        int hinzu = 0;
        int entfernt = 0;
        for (boolean b : d.hinzu) {
            hinzu += b ? 1 : 0;
        }
        for (boolean b : d.entfernt) {
            entfernt += b ? 1 : 0;
        }
        assertEquals(4, hinzu);
        assertEquals(2, entfernt);
        stimmt(alt, neu);
    }

    /** Zufällige Paare: was auch herauskommt, es muss aufgehen. */
    @Test
    public void zufaelligePaareGehenAuf() {
        Random r = new Random(7);
        for (int lauf = 0; lauf < 300; lauf++) {
            String[] alt = new String[r.nextInt(30)];
            for (int i = 0; i < alt.length; i++) {
                alt[i] = "z" + r.nextInt(6);
            }
            List<String> liste = new ArrayList<>(java.util.Arrays.asList(alt));
            for (int k = r.nextInt(6); k > 0; k--) {
                if (!liste.isEmpty() && r.nextBoolean()) {
                    liste.remove(r.nextInt(liste.size()));
                } else {
                    liste.add(r.nextInt(liste.size() + 1), "z" + r.nextInt(6));
                }
            }
            stimmt(alt, liste.toArray(new String[0]));
        }
    }

    /** Zwei völlig verschiedene, lange Texte: kein Feinvergleich mehr, aber ein richtiges Ergebnis. */
    @Test
    public void voelligVerschiedeneTexte() {
        int n = LineDiff.MAX_UNTERSCHIEDE + 500;
        String[] alt = new String[n];
        String[] neu = new String[n];
        for (int i = 0; i < n; i++) {
            alt[i] = "alt " + i;
            neu[i] = "neu " + i;
        }
        LineDiff d = LineDiff.vergleiche(alt, neu);
        for (int i = 0; i < n; i++) {
            assertTrue(d.entfernt[i] && d.hinzu[i]);
        }
    }
}
