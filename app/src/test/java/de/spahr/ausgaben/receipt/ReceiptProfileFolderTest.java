package de.spahr.ausgaben.receipt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Der Unterordnername eines Profils ({@link Receipts#folderFor}).
 *
 * <p>Der wichtigste Fall ist die leere Id: Ohne festen Ersatznamen wäre der Profilordner die Wurzel
 * selbst, und {@link ReceiptProfileMigration} räumte sich beim Zuordnen selbst aus.</p>
 */
public class ReceiptProfileFolderTest {

    @Test
    public void leereIdErgibtEinenFestenErsatznamen() {
        assertEquals("p_default", Receipts.folderFor(null));
        assertEquals("p_default", Receipts.folderFor(""));
        assertEquals("p_default", Receipts.folderFor("   "));
    }

    /** Eine Id, die nur aus unerlaubten Zeichen besteht, darf nicht zur Wurzel werden. */
    @Test
    public void idAusLauterSonderzeichenErgibtDenErsatznamen() {
        assertEquals("p_default", Receipts.folderFor("../.."));
        assertEquals("p_default", Receipts.folderFor("///"));
    }

    @Test
    public void gewoehnlicheIdBekommtDasPraefix() {
        assertEquals("p_a1b2c3", Receipts.folderFor("a1b2c3"));
        assertTrue(Receipts.folderFor("a1b2c3").startsWith("p_"));
    }

    /** Pfadtrenner und Punkte fallen weg – der Name muss ein einzelner Ordner bleiben. */
    @Test
    public void pfadangabenWerdenBereinigt() {
        assertEquals("p_ab", Receipts.folderFor("a/b"));
        assertEquals("p_ab", Receipts.folderFor("a.b"));
        assertEquals("p_abc", Receipts.folderFor("../a/b/c"));
    }

    /** Bindestriche und Unterstriche bleiben: Profil-Ids sind UUIDs, teils mit Bindestrich. */
    @Test
    public void bindestricheBleiben() {
        assertEquals("p_a1-b2_c3", Receipts.folderFor("a1-b2_c3"));
    }

    @Test
    public void verschiedeneIdsErgebenVerschiedeneOrdner() {
        assertNotEquals(Receipts.folderFor("aaa"), Receipts.folderFor("bbb"));
    }

    /** Zweimal dieselbe Id muss denselben Ordner ergeben – sonst fände niemand seine Belege wieder. */
    @Test
    public void gleicheIdErgibtImmerDenselbenOrdner() {
        assertEquals(Receipts.folderFor("a1b2c3"), Receipts.folderFor("a1b2c3"));
    }
}
