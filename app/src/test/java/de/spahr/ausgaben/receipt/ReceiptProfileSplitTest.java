package de.spahr.ausgaben.receipt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aufteilung der Merklisten auf die Profile
 * ({@link ReceiptProfileMigration#splitPending}/{@link ReceiptProfileMigration#splitMoves}).
 *
 * <p>Die Einträge selbst bleiben dabei unverändert – nur ihr Schlüssel wandert unters Profil. Das
 * Jahr eines {@code pending}-Eintrags etwa bestimmt den Zielordner beim Hochladen; ginge es
 * verloren, landete der Beleg im falschen Jahr.</p>
 */
public class ReceiptProfileSplitTest {

    private static final String DATEI_A = "aaaa1111_p1.jpg";
    private static final String DATEI_B = "bbbb2222_p1.jpg";

    private static Map<String, List<String>> besitzer() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put(DATEI_A, Arrays.asList("profilA"));
        m.put(DATEI_B, Arrays.asList("profilB"));
        return m;
    }

    private static Set<String> menge(String... e) {
        return new HashSet<>(Arrays.asList(e));
    }

    @Test
    public void pendingWirdAufgeteiltUndBehaeltDasJahr() {
        Map<String, Set<String>> out = ReceiptProfileMigration.splitPending(
                menge("2026|" + DATEI_A, "2025|" + DATEI_B), besitzer(), "aktiv");

        assertEquals(menge("2026|" + DATEI_A), out.get("profilA"));
        assertEquals(menge("2025|" + DATEI_B), out.get("profilB"));
    }

    /** Altstand ohne Jahresangabe: Der Eintrag ist nur der Dateiname und muss trotzdem ankommen. */
    @Test
    public void pendingOhneJahrFindetSeinProfil() {
        Map<String, Set<String>> out =
                ReceiptProfileMigration.splitPending(menge(DATEI_A), besitzer(), "aktiv");

        assertEquals(menge(DATEI_A), out.get("profilA"));
    }

    @Test
    public void ohneBesitzerGehtEsAnsAktiveProfil() {
        Map<String, Set<String>> out = ReceiptProfileMigration.splitPending(
                menge("2026|cccc3333_p1.jpg"), besitzer(), "aktiv");

        assertEquals(menge("2026|cccc3333_p1.jpg"), out.get("aktiv"));
    }

    @Test
    public void jahreswechselWirdAufgeteilt() {
        Map<String, Set<String>> out = ReceiptProfileMigration.splitMoves(
                menge("2025|2026|" + DATEI_A), besitzer(), "aktiv");

        assertEquals(menge("2025|2026|" + DATEI_A), out.get("profilA"));
    }

    @Test
    public void ordnerwechselWirdAufgeteilt() {
        String eintrag = "KMy/Belege/2026|KMy/Belege-Firma/2026|" + DATEI_B;
        Map<String, Set<String>> out =
                ReceiptProfileMigration.splitMoves(menge(eintrag), besitzer(), "aktiv");

        assertEquals(menge(eintrag), out.get("profilB"));
    }

    /** Unbrauchbare Einträge werden nicht weitergeschleppt. */
    @Test
    public void unbrauchbaresFaelltWeg() {
        Map<String, Set<String>> out =
                ReceiptProfileMigration.splitMoves(menge("kaputt"), besitzer(), "aktiv");

        assertTrue(out.isEmpty());
    }

    @Test
    public void leereEingabeErgibtLeeresErgebnis() {
        assertTrue(ReceiptProfileMigration.splitPending(null, besitzer(), "aktiv").isEmpty());
        assertTrue(ReceiptProfileMigration.splitMoves(menge(), besitzer(), "aktiv").isEmpty());
    }

    /** Beanspruchen zwei Profile dieselbe Datei, bekommen beide den Eintrag. */
    @Test
    public void doppelterAnspruchLandetBeiBeiden() {
        Map<String, List<String>> beide = new LinkedHashMap<>();
        beide.put(DATEI_A, Arrays.asList("profilA", "profilB"));

        Map<String, Set<String>> out = ReceiptProfileMigration.splitPending(
                menge("2026|" + DATEI_A), beide, "aktiv");

        assertEquals(menge("2026|" + DATEI_A), out.get("profilA"));
        assertEquals(menge("2026|" + DATEI_A), out.get("profilB"));
    }
}
