package de.spahr.ausgaben.receipt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Der Regressionstest zum gemeldeten Datenverlust.
 *
 * <h2>Was passiert war</h2>
 *
 * <p>Zwei .kmy-Dateien im selben Ordner teilten sich einen Belegordner, und die lokalen Dateien
 * aller Profile lagen in <b>einem</b> Verzeichnis. {@code ReceiptGc} bildet seine Behalte-Liste aus
 * der Datenbank des <b>aktiven</b> Profils, listete aber alle Dateien dieses gemeinsamen
 * Verzeichnisses – und erklärte alles Übrige zu Waisen. Bei jedem Kaltstart traf es die Belege des
 * jeweils anderen Profils: lokal gelöscht, auf dem Server in den Papierkorb geschoben.</p>
 *
 * <h2>Warum die Trennung des lokalen Ordners genügt</h2>
 *
 * <p>Der Aufräumlauf urteilt <b>ausschließlich</b> über lokal vorhandene Dateien: Die einzige Quelle
 * seiner Kandidaten ist {@code Receipts.dir(ctx).listFiles()}, und verschoben wird auf dem Server
 * nur, was in dieser Liste steht. Liegt die Datei von Profil B nicht mehr im Ordner von Profil A, so
 * nennt A sie nie – weder lokal noch auf dem Server.</p>
 *
 * <p>Genau das hält dieser Test fest, ohne Netz und ohne Datenbank: Aus der Sicht von Profil A
 * tauchen die Dateien von Profil B gar nicht erst in der Eingabemenge auf.</p>
 */
public class ReceiptGcCrossProfileTest {

    private static final String BASIS_A = "aaaa1111";
    private static final String BASIS_B = "bbbb2222";

    /** Was in Profil As Ordner liegt – nach der Trennung nur noch As eigene Dateien. */
    private static List<String> dateienVonA() {
        return Arrays.asList(BASIS_A + "_p1.jpg", BASIS_A + "_p2.jpg");
    }

    private static Set<String> behalteVonA() {
        return new HashSet<>(Arrays.asList(BASIS_A));
    }

    @Test
    public void profilASiehtDieDateienVonProfilBGarNicht() {
        List<String> waisen = ReceiptGc.orphans(dateienVonA(), behalteVonA());

        assertTrue("As eigene Belege sind keine Waisen", waisen.isEmpty());
        for (String w : waisen) {
            assertFalse("eine Datei von B darf hier nie auftauchen", w.startsWith(BASIS_B));
        }
    }

    /**
     * Die Gegenprobe – der Zustand vor der Trennung. Läge Bs Datei in derselben Eingabemenge, gälte
     * sie sofort als Waise. Das ist genau der Weg, auf dem die Belege verschwanden.
     */
    @Test
    public void imGemeinsamenOrdnerWaereBsDateiEineWaise() {
        List<String> gemeinsam = Arrays.asList(BASIS_A + "_p1.jpg", BASIS_B + "_p1.jpg");

        List<String> waisen = ReceiptGc.orphans(gemeinsam, behalteVonA());

        assertEquals(1, waisen.size());
        assertEquals(BASIS_B + "_p1.jpg", waisen.get(0));
    }

    /** Eigene Waisen findet der Lauf weiterhin – das soll er ja. */
    @Test
    public void eigeneWaisenFindetErWeiterhin() {
        List<String> mitWaise = Arrays.asList(BASIS_A + "_p1.jpg", "cccc3333_p1.jpg");

        List<String> waisen = ReceiptGc.orphans(mitWaise, behalteVonA());

        assertEquals(Arrays.asList("cccc3333_p1.jpg"), waisen);
    }

    /** Arbeitsdateien einer laufenden Aufnahme gehören dem Editor, nicht dem Aufräumlauf. */
    @Test
    public void arbeitsdateienBleibenUnangetastet() {
        List<String> mitArbeitsdateien =
                Arrays.asList("pend_1234.jpg", "cam_5678.jpg", BASIS_A + "_p1.jpg");

        assertTrue(ReceiptGc.orphans(mitArbeitsdateien, behalteVonA()).isEmpty());
    }
}
