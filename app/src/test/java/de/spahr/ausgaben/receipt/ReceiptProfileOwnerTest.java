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
 * Wem eine Belegdatei gehört ({@link ReceiptProfileMigration#owners}).
 *
 * <p>Der Fall „niemand" ist der wichtigste: Solche Dateien dürfen <b>nicht</b> gelöscht werden. Sie
 * gehen ans aktive Profil und werden dort regulär beurteilt – vom Aufräumlauf, der dafür einen
 * Papierkorb hat.</p>
 */
public class ReceiptProfileOwnerTest {

    private static final String BASIS_A = "aaaa1111";
    private static final String BASIS_B = "bbbb2222";

    private static Map<String, Set<String>> bestand() {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        m.put("profilA", new HashSet<>(Arrays.asList(BASIS_A)));
        m.put("profilB", new HashSet<>(Arrays.asList(BASIS_B)));
        return m;
    }

    @Test
    public void jedeDateiFindetIhrProfil() {
        Map<String, List<String>> o = ReceiptProfileMigration.owners(
                Arrays.asList(BASIS_A + "_p1.jpg", BASIS_B + "_p1.jpg"), bestand());

        assertEquals(Arrays.asList("profilA"), o.get(BASIS_A + "_p1.jpg"));
        assertEquals(Arrays.asList("profilB"), o.get(BASIS_B + "_p1.jpg"));
    }

    /** Folgeseiten und Originale hängen an derselben Basis und folgen ihr. */
    @Test
    public void weitereSeitenUndOriginaleFolgenIhrerBasis() {
        Map<String, List<String>> o = ReceiptProfileMigration.owners(
                Arrays.asList(BASIS_A + "_p2.jpg", BASIS_A + "_p3.jpg"), bestand());

        assertEquals(Arrays.asList("profilA"), o.get(BASIS_A + "_p2.jpg"));
        assertEquals(Arrays.asList("profilA"), o.get(BASIS_A + "_p3.jpg"));
    }

    @Test
    public void herrenloseDateiMeldetNiemanden() {
        Map<String, List<String>> o = ReceiptProfileMigration.owners(
                Arrays.asList("cccc3333_p1.jpg"), bestand());

        assertTrue(o.containsKey("cccc3333_p1.jpg"));
        assertTrue(o.get("cccc3333_p1.jpg").isEmpty());
    }

    /**
     * Beanspruchen zwei Profile dieselbe Basis, werden beide genannt – die Migration kopiert dann,
     * statt zu verschieben. Ein Umzug ließe eines der beiden leer ausgehen.
     */
    @Test
    public void doppelterAnspruchNenntBeide() {
        Map<String, Set<String>> beide = bestand();
        beide.get("profilB").add(BASIS_A);

        List<String> profile = ReceiptProfileMigration.owners(
                Arrays.asList(BASIS_A + "_p1.jpg"), beide).get(BASIS_A + "_p1.jpg");

        assertEquals(2, profile.size());
        assertTrue(profile.contains("profilA"));
        assertTrue(profile.contains("profilB"));
    }

    @Test
    public void ohneBestandBeanspruchtNiemandEtwas() {
        Map<String, List<String>> o = ReceiptProfileMigration.owners(
                Arrays.asList(BASIS_A + "_p1.jpg"), new LinkedHashMap<>());

        assertTrue(o.get(BASIS_A + "_p1.jpg").isEmpty());
    }
}
