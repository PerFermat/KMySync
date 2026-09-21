package de.spahr.ausgaben.receipt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.HashSet;

import de.spahr.ausgaben.settings.ProfileManager;

/**
 * Der einmalige Umzug des Belegbestands ins Profil-Layout ({@link ReceiptProfileMigration}).
 *
 * <p>Geprüft wird vor allem, was <b>nicht</b> passiert: Keine Datei geht verloren, auch keine, die
 * sich keinem Profil zuordnen lässt. Über Herrenlosigkeit urteilt der Aufräumlauf – der hat dafür
 * einen Papierkorb, die Migration hätte keinen.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ReceiptProfileMigrationTest {

    private Context ctx;
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        prefs = ctx.getSharedPreferences("receipts", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        loescheRekursiv(Receipts.root(ctx));
    }

    private static void loescheRekursiv(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                loescheRekursiv(f);
            }
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private void legeFlachAn(String name) {
        try (FileOutputStream out = new FileOutputStream(new File(Receipts.root(ctx), name))) {
            out.write(new byte[]{1, 2, 3});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String aktivesProfil() {
        return new ProfileManager(ctx).getActiveProfileId();
    }

    private File imProfilOrdner(String profilId, String datei) {
        return new File(new File(Receipts.root(ctx), Receipts.folderFor(profilId)), datei);
    }

    /** Ohne Altbestand ist der Lauf ein No-op – und muss es auch bleiben. */
    @Test
    public void ohneAltbestandPassiertNichts() {
        assertFalse(ReceiptProfileMigration.hasLegacyState(ctx));
        ReceiptProfileMigration.ensureDone(ctx);
        assertFalse(ReceiptProfileMigration.hasLegacyState(ctx));
    }

    @Test
    public void eineFlacheDateiWirdAlsAltbestandErkannt() {
        legeFlachAn("aaaa1111_p1.jpg");
        assertTrue(ReceiptProfileMigration.hasLegacyState(ctx));
    }

    @Test
    public void unpraefixierteMerklisteWirdAlsAltbestandErkannt() {
        prefs.edit().putStringSet("pending", new HashSet<>(Arrays.asList("2026|a_p1.jpg"))).commit();
        assertTrue(ReceiptProfileMigration.hasLegacyState(ctx));
    }

    /**
     * Der Kern: Eine Datei, die keinem Profil zuzuordnen ist, wird <b>nicht gelöscht</b>, sondern
     * dem aktiven Profil übergeben. Dort urteilt der reguläre Aufräumlauf über sie – mit Papierkorb.
     */
    @Test
    public void herrenloseDateiLandetBeimAktivenProfilUndUeberlebt() {
        legeFlachAn("cccc3333_p1.jpg");

        ReceiptProfileMigration.ensureDone(ctx);

        assertTrue("die Datei darf nicht verschwinden",
                imProfilOrdner(aktivesProfil(), "cccc3333_p1.jpg").isFile());
        assertFalse("und nicht mehr flach liegen",
                new File(Receipts.root(ctx), "cccc3333_p1.jpg").exists());
    }

    /** Nach dem Lauf liegen in der Wurzel nur noch Ordner. */
    @Test
    public void dieWurzelEnthaeltDanachNurNochOrdner() {
        legeFlachAn("aaaa1111_p1.jpg");
        legeFlachAn("bbbb2222_p1.jpg");

        ReceiptProfileMigration.ensureDone(ctx);

        File[] rest = Receipts.root(ctx).listFiles();
        assertTrue(rest != null);
        for (File f : rest) {
            assertTrue("in der Wurzel liegt noch eine Datei: " + f.getName(), f.isDirectory());
        }
    }

    /** Die unpräfixierten Schlüssel verschwinden, der Inhalt taucht unter dem Profil wieder auf. */
    @Test
    public void merklisteWandertUntersProfil() {
        prefs.edit().putStringSet("pending",
                new HashSet<>(Arrays.asList("2026|cccc3333_p1.jpg"))).commit();

        ReceiptProfileMigration.ensureDone(ctx);

        assertFalse("der blanke Schlüssel muss weg sein", prefs.contains("pending"));
        assertEquals(new HashSet<>(Arrays.asList("2026|cccc3333_p1.jpg")), Receipts.pending(ctx));
    }

    /** Zweimal laufen darf nichts kaputt machen – ein Abbruch führt genau dazu. */
    @Test
    public void zweiterLaufAendertNichts() {
        legeFlachAn("cccc3333_p1.jpg");
        ReceiptProfileMigration.ensureDone(ctx);
        long groesse = imProfilOrdner(aktivesProfil(), "cccc3333_p1.jpg").length();

        ReceiptProfileMigration.ensureDone(ctx);

        assertTrue(imProfilOrdner(aktivesProfil(), "cccc3333_p1.jpg").isFile());
        assertEquals(groesse, imProfilOrdner(aktivesProfil(), "cccc3333_p1.jpg").length());
    }

    /**
     * Eine eingespielte Sicherung aus einer älteren Fassung schreibt die blanken Schlüssel wieder
     * hin. Weil der Zustand geprüft wird und kein Erledigt-Schalter gesetzt ist, sammelt der nächste
     * Lauf sie erneut ein.
     */
    @Test
    public void nachEingespielterAltsicherungLaeuftEsErneut() {
        legeFlachAn("cccc3333_p1.jpg");
        ReceiptProfileMigration.ensureDone(ctx);
        assertFalse(ReceiptProfileMigration.hasLegacyState(ctx));

        prefs.edit().putStringSet("moves",
                new HashSet<>(Arrays.asList("2025|2026|dddd4444_p1.jpg"))).commit();

        assertTrue("der Altstand muss wieder auffallen", ReceiptProfileMigration.hasLegacyState(ctx));
        ReceiptProfileMigration.ensureDone(ctx);
        assertFalse(prefs.contains("moves"));
        assertEquals(new HashSet<>(Arrays.asList("2025|2026|dddd4444_p1.jpg")), Receipts.moves(ctx));
    }
}
