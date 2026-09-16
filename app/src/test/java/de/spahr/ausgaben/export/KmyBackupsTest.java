package de.spahr.ausgaben.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Welche Sicherungen der Export wegräumen darf. Das Löschen selbst fasst den Server an und bleibt
 * außen vor – hier steht nur die Auswahl.
 */
public class KmyBackupsTest {

    /** {@code n} Sicherungen zu {@code file}, aufsteigend datiert. */
    private static List<String> sicherungen(String file, int n) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add(String.format("%s.bak-202609%02d-120000", file, i));
        }
        return out;
    }

    @Test
    public void unterDemDeckelBleibtAllesLiegen() {
        List<String> namen = sicherungen("michael.kmy", 5);
        assertTrue(KmyBackups.obsolete(namen, "michael.kmy", 20).isEmpty());
    }

    @Test
    public void ueberDemDeckelFallenDieAeltestenWeg() {
        List<String> namen = sicherungen("michael.kmy", 23);
        List<String> weg = KmyBackups.obsolete(namen, "michael.kmy", 20);
        assertEquals(3, weg.size());
        // Die drei ältesten, nicht irgendwelche drei.
        assertEquals(Arrays.asList(
                "michael.kmy.bak-20260903-120000",
                "michael.kmy.bak-20260902-120000",
                "michael.kmy.bak-20260901-120000"), weg);
    }

    @Test
    public void dieJuengstenBleibenGarantiert() {
        List<String> namen = sicherungen("michael.kmy", 30);
        List<String> weg = KmyBackups.obsolete(namen, "michael.kmy", 20);
        assertTrue(weg.contains("michael.kmy.bak-20260910-120000"));
        assertTrue("die jüngste darf nie weg", !weg.contains("michael.kmy.bak-20260930-120000"));
        assertEquals(10, weg.size());
    }

    @Test
    public void andereProfileHabenIhrEigenesKontingent() {
        // Im selben Ordner liegen Sicherungen mehrerer .kmy-Dateien; jede zählt für sich.
        List<String> namen = new ArrayList<>(sicherungen("michael.kmy", 22));
        namen.addAll(sicherungen("Rolf.kmy", 25));
        List<String> weg = KmyBackups.obsolete(namen, "michael.kmy", 20);
        assertEquals(2, weg.size());
        for (String n : weg) {
            assertTrue(n.startsWith("michael.kmy.bak-"));
        }
    }

    @Test
    public void fremdeDateienImOrdnerBleibenUnberuehrt() {
        // Ein Name, der nur zufällig so anfängt, und die .kmy selbst.
        List<String> namen = new ArrayList<>(sicherungen("michael.kmy", 22));
        namen.add("michael.kmy");
        namen.add("Notizen.txt");
        namen.add("michael.kmy.20260101.tmp");
        List<String> weg = KmyBackups.obsolete(namen, "michael.kmy", 20);
        assertEquals(2, weg.size());
        for (String n : weg) {
            assertTrue(n.startsWith("michael.kmy.bak-"));
        }
    }

    @Test
    public void leereEingabeUndNullSindHarmlos() {
        assertTrue(KmyBackups.obsolete(null, "michael.kmy", 20).isEmpty());
        assertTrue(KmyBackups.obsolete(Collections.emptyList(), "michael.kmy", 20).isEmpty());
        assertTrue(KmyBackups.obsolete(sicherungen("michael.kmy", 3), null, 20).isEmpty());
    }

    @Test
    public void deckelNullRaeumtAllesWeg() {
        assertEquals(3, KmyBackups.obsolete(sicherungen("michael.kmy", 3), "michael.kmy", 0).size());
    }
}
