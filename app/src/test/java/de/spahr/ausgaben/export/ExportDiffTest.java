package de.spahr.ausgaben.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.List;

/** Die Anzeigeblöcke: Umgebung, Zusammenlaufen naher Änderungen, Lücken, Zeilennummern, Ablage. */
public class ExportDiffTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** {@code n} Zeilen „1" … „n", jede mit Zeilenende. */
    private static List<String> zeilen(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add("Zeile " + i);
        }
        return out;
    }

    private static String text(List<String> zeilen) {
        return String.join("\n", zeilen) + "\n";
    }

    /** Die Anzeige in Kurzform: je Zeile Art und Nummer, eine Lücke als „~". */
    private static String kurz(ExportDiff d) {
        StringBuilder sb = new StringBuilder();
        for (ExportDiff.Zeile z : d.zeilen) {
            sb.append(z.art == ExportDiff.LUECKE ? "~" : (z.art == ExportDiff.GLEICH ? "" : "" + z.art)
                    + z.nummer).append(' ');
        }
        return sb.toString().trim();
    }

    private static int anzahl(ExportDiff d, char art) {
        int n = 0;
        for (ExportDiff.Zeile z : d.zeilen) {
            n += z.art == art ? 1 : 0;
        }
        return n;
    }

    @Test
    public void zehnZeilenDavorUndDanach() {
        List<String> alt = zeilen(100);
        List<String> neu = new ArrayList<>(alt);
        neu.add(50, "NEU");                       // wird Zeile 51 der neuen Datei
        ExportDiff d = ExportDiff.von(text(alt), text(neu));
        assertEquals(1, d.hinzu);
        assertEquals(0, d.entfernt);
        assertEquals("41 42 43 44 45 46 47 48 49 50 +51 52 53 54 55 56 57 58 59 60 61", kurz(d));
        assertEquals("NEU", d.zeilen.get(10).text);
        assertEquals("Zeile 50", d.zeilen.get(9).text);
        assertEquals("die folgende Zeile trägt ihre Nummer in der neuen Datei", "Zeile 51",
                d.zeilen.get(11).text);
    }

    /** Am Dateianfang und -ende gibt es eben weniger Umgebung – und keine Lücke davor oder dahinter. */
    @Test
    public void amRandDerDatei() {
        List<String> alt = zeilen(30);
        List<String> neu = new ArrayList<>(alt);
        neu.set(1, "GEÄNDERT");
        ExportDiff d = ExportDiff.von(text(alt), text(neu));
        assertEquals("1 -2 +2 3 4 5 6 7 8 9 10 11 12", kurz(d));
        assertEquals(0, anzahl(d, ExportDiff.LUECKE));
    }

    /** Entfernte Zeilen tragen die Nummer der alten Datei, alles andere die der neuen. */
    @Test
    public void zeilennummernAltUndNeu() {
        List<String> alt = zeilen(40);
        List<String> neu = new ArrayList<>(alt);
        neu.remove(19);                           // Zeile 20 der alten Datei fällt weg
        ExportDiff d = ExportDiff.von(text(alt), text(neu));
        assertEquals("10 11 12 13 14 15 16 17 18 19 -20 20 21 22 23 24 25 26 27 28 29", kurz(d));
        // Hinter der Lücke heißt die alte Zeile 21 jetzt 20.
        assertEquals("Zeile 21", d.zeilen.get(11).text);
    }

    /**
     * Genau an der Grenze: Liegen 20 unveränderte Zeilen zwischen zwei Änderungen, entsteht ein Block,
     * in dem jede dieser Zeilen genau einmal steht. Bei 21 sind es zwei Blöcke mit einer Lücke.
     */
    @Test
    public void zwanzigZeilenAbstandEinBlock_einundzwanzigZwei() {
        List<String> alt = zeilen(200);

        List<String> nah = new ArrayList<>(alt);
        nah.set(49, "A");                         // Zeile 50
        nah.set(70, "B");                         // Zeile 71: dazwischen 51 … 70, also 20 Zeilen
        ExportDiff d = ExportDiff.von(text(alt), text(nah));
        assertEquals(0, anzahl(d, ExportDiff.LUECKE));
        // 10 davor, 2 für die erste Änderung, 20 dazwischen, 2 für die zweite, 10 danach.
        assertEquals(44, d.zeilen.size());
        List<Integer> gesehen = new ArrayList<>();
        for (ExportDiff.Zeile z : d.zeilen) {
            if (z.art == ExportDiff.GLEICH) {
                assertFalse("Zeile " + z.nummer + " steht doppelt", gesehen.contains(z.nummer));
                gesehen.add(z.nummer);
            }
        }
        assertEquals(40, gesehen.size());

        List<String> fern = new ArrayList<>(alt);
        fern.set(49, "A");                        // Zeile 50
        fern.set(71, "B");                        // Zeile 72: dazwischen 21 Zeilen
        d = ExportDiff.von(text(alt), text(fern));
        assertEquals(1, anzahl(d, ExportDiff.LUECKE));
        // 10 + 2 + 10, Lücke, 10 + 2 + 10 – die eine Zeile in der Mitte (61) fehlt.
        assertEquals(45, d.zeilen.size());
        assertEquals(ExportDiff.LUECKE, d.zeilen.get(22).art);
        assertEquals(60, d.zeilen.get(21).nummer);
        assertEquals(62, d.zeilen.get(23).nummer);
    }

    @Test
    public void ohneAenderungIstDieAnzeigeLeer() {
        ExportDiff d = ExportDiff.von(text(zeilen(50)), text(zeilen(50)));
        assertTrue(d.zeilen.isEmpty());
        assertEquals(0, d.hinzu + d.entfernt);
    }

    @Test
    public void ueberlangeZeilenWerdenGekapptUndWindowsEndenEntfernt() {
        StringBuilder lang = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            lang.append('x');
        }
        ExportDiff d = ExportDiff.von("a\r\nb\r\n", "a\r\n" + lang + "\r\nb\r\n");
        assertEquals(1, d.hinzu);
        assertEquals("a", d.zeilen.get(0).text);
        assertEquals(ExportDiff.MAX_ZEICHEN + 2, d.zeilen.get(1).text.length());
        assertTrue(d.zeilen.get(1).text.endsWith(" …"));
    }

    // ---- Ablage ----

    @Test
    public void textformHinUndZurueck() {
        List<String> alt = zeilen(100);
        List<String> neu = new ArrayList<>(alt);
        neu.set(10, "mit\tTab und = Zeichen");
        neu.remove(80);
        ExportDiff d = ExportDiff.von(text(alt), text(neu));
        d.zeit = 1_780_000_000_000L;
        d.datei = "michael.kmy";
        d.abweichung = true;

        ExportDiff zurueck = ExportDiff.ausText(d.alsText(), false);
        assertEquals(kurz(d), kurz(zurueck));
        assertEquals(d.zeilen.get(10).text, zurueck.zeilen.get(10).text);
        assertEquals(d.zeit, zurueck.zeit);
        assertEquals("michael.kmy", zurueck.datei);
        assertEquals(d.hinzu, zurueck.hinzu);
        assertEquals(d.entfernt, zurueck.entfernt);
        assertTrue(zurueck.abweichung);

        ExportDiff kopf = ExportDiff.ausText(d.alsText(), true);
        assertTrue(kopf.zeilen.isEmpty());
        assertEquals(d.hinzu, kopf.hinzu);
        assertNull(ExportDiff.ausText("irgendetwas anderes", false));
    }

    @Test
    public void defektUndWiederhergestellt_ueberstehenDieAblage() {
        ExportDiff d = ExportDiff.von("a\nb\n", "a\n");
        d.defekt = true;
        d.wiederhergestellt = true;
        ExportDiff zurueck = ExportDiff.ausText(d.alsText(), true);
        assertTrue(zurueck.defekt);
        assertTrue(zurueck.wiederhergestellt);

        d.wiederhergestellt = false;
        zurueck = ExportDiff.ausText(d.alsText(), true);
        assertTrue(zurueck.defekt);
        assertFalse(zurueck.wiederhergestellt);
    }

    @Test
    public void eintragOhneDieNeuenKopfzeilen_liestSichAlsNichtDefekt() {
        String alt = "KMYDIFF 1\nzeit=5\ndatei=m.kmy\nhinzu=1\nentfernt=0\nabweichung=0\ngekuerzt=0\n"
                + "---\n+2\tb\n";
        ExportDiff d = ExportDiff.ausText(alt, false);
        assertFalse(d.defekt);
        assertFalse(d.wiederhergestellt);
        assertEquals(1, d.zeilen.size());
    }

    private static ExportDiff einer(long zeit) {
        ExportDiff d = ExportDiff.von("a\n", "a\nb\n");
        d.zeit = zeit;
        d.datei = "m.kmy";
        return d;
    }

    @Test
    public void ablageListetDenJuengstenZuerstUndRaeumtWieDieSicherungen() throws Exception {
        ExportDiffStore store = new ExportDiffStore(
                ExportDiffStore.ordnerFuer(tmp.getRoot(), "profil1"));
        assertTrue(store.liste().isEmpty());
        store.speichere("m.kmy.bak-20260101-100000", einer(1000));
        store.speichere("m.kmy.bak-20260103-100000", einer(3000));
        store.speichere("m.kmy.bak-20260102-100000", einer(2000));
        store.speichere("andere.kmy.bak-20250101-100000", einer(500));

        List<ExportDiffStore.Eintrag> liste = store.liste();
        assertEquals(4, liste.size());
        assertEquals("m.kmy.bak-20260103-100000", liste.get(0).name);
        assertEquals(1, liste.get(0).kopf.hinzu);
        assertEquals(1, store.lade("m.kmy.bak-20260102-100000").zeilen.size() - 1);
        assertNull(store.lade("gibt-es-nicht"));

        // Zwei bleiben je Datei – die ältere von m.kmy geht, die der anderen Datei bleibt unberührt.
        assertEquals(1, store.raeumeAuf("m.kmy", 2));
        assertEquals(3, store.liste().size());
        assertNull(store.lade("m.kmy.bak-20260101-100000"));
        assertEquals(1, new ExportDiffStore(ExportDiffStore.ordnerFuer(tmp.getRoot(), "profil1"))
                .lade("andere.kmy.bak-20250101-100000").hinzu);
        // Ein anderes Profil sieht davon nichts.
        assertTrue(new ExportDiffStore(ExportDiffStore.ordnerFuer(tmp.getRoot(), "profil2"))
                .liste().isEmpty());
    }
}
