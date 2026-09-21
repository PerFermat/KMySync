package de.spahr.ausgaben.backup;

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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import de.spahr.ausgaben.settings.ProfileManager;

/**
 * Einspielen einer Sicherung aus der Zeit vor den Profilen (Archivformat 1, App-Fassung 1.11).
 *
 * <p>Dort stehen Profil- und geräteweite Einstellungen unpräfixiert nebeneinander, und es gibt die
 * Datei {@code widget_selection}, die es heute nur noch in einer Alle-Profile-Sicherung gibt
 * ({@code receipts} gehört seit 2.2 zum Profil). Behandelte man ein solches Archiv wie ein heutiges, bekämen Sprache,
 * Nachtmodus, Schriftgröße, Kategoriefarben und App-Sperre ein Profil-Präfix – und wären damit still
 * verloren, während die alten Werte unberührt daneben stehen blieben.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BackupLegacyRestoreTest {

    private final Context ctx = ApplicationProvider.getApplicationContext();

    @Before
    public void resetProfileState() {
        ctx.getSharedPreferences("ausgaben_profiles", Context.MODE_PRIVATE).edit().clear().commit();
        ctx.getSharedPreferences("ausgaben_settings", Context.MODE_PRIVATE).edit().clear().commit();
        ctx.getSharedPreferences("receipts", Context.MODE_PRIVATE).edit().clear().commit();
        ProfileManager.migrateLegacyInstallationIfNeeded(ctx);
    }

    /** Ein Archiv, wie 1.11 es geschrieben hat: Format 1, kein {@code scope}, alles unpräfixiert. */
    private static byte[] altesArchiv(Map<String, Map<String, Object>> prefsDateien) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(BackupArchive.ENTRY_MANIFEST));
            zip.write(("{\"format\":1,\"created\":1700000000000,\"versionCode\":12}")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (Map.Entry<String, Map<String, Object>> e : prefsDateien.entrySet()) {
                zip.putNextEntry(new ZipEntry(BackupArchive.PREFS_DIR + e.getKey() + ".json"));
                zip.write(PrefsCodec.toJson(e.getValue()).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    public void globaleEinstellungenBleibenGlobalProfilEinstellungenWandernUntersPraefix()
            throws Exception {
        String prefix = "p_" + new ProfileManager(ctx).getActiveProfileId() + "_";

        Map<String, Object> einstellungen = new LinkedHashMap<>();
        einstellungen.put("nextcloud_url", "https://alt.example/");  // gehört zum Profil
        einstellungen.put("dividend_gross", true);                   // gehört zum Profil
        einstellungen.put("language", "en");                         // global
        einstellungen.put("night_mode", 2);                          // global
        einstellungen.put("color_Lebensmittel", -65536);             // global (Kategoriefarbe)
        Map<String, Map<String, Object>> dateien = new LinkedHashMap<>();
        dateien.put("ausgaben_settings", einstellungen);

        BackupArchive.Content content = BackupArchive.read(altesArchiv(dateien));
        assertEquals("Voraussetzung: das Archiv trägt das alte Format", 1, content.format);

        BackupStore.restoreProfileSettings(ctx, content);

        SharedPreferences prefs = ctx.getSharedPreferences("ausgaben_settings", Context.MODE_PRIVATE);
        assertEquals("https://alt.example/", prefs.getString(prefix + "nextcloud_url", ""));
        assertTrue(prefs.getBoolean(prefix + "dividend_gross", false));

        assertEquals("Die Sprache ist geräteweit und muss unpräfixiert landen",
                "en", prefs.getString("language", ""));
        assertEquals(2, prefs.getInt("night_mode", 0));
        assertEquals(-65536, prefs.getInt("color_Lebensmittel", 0));
        assertFalse("sonst liest sie niemand mehr", prefs.contains(prefix + "language"));
        assertFalse(prefs.contains(prefix + "night_mode"));
        assertFalse(prefs.contains(prefix + "color_Lebensmittel"));
    }

    /**
     * Die Beleg-Merkliste gehört seit 2.2 zum Profil und muss beim Einspielen unters Präfix wandern.
     *
     * <p>Bis dahin stand hier die umgekehrte Zusicherung. Sie war richtig, solange die Merklisten
     * geräteweit lagen – und genau das war der Boden für den Datenverlust bei zwei Profilen: Der
     * Aufräumlauf des aktiven Profils hielt die Belege des anderen für herrenlos.</p>
     */
    @Test
    public void belegMerklisteWandertUntersProfil() throws Exception {
        String prefix = "p_" + new ProfileManager(ctx).getActiveProfileId() + "_";

        Map<String, Object> belege = new LinkedHashMap<>();
        belege.put("pending", new java.util.HashSet<>(java.util.Arrays.asList("2026|abc_p1.jpg")));
        Map<String, Map<String, Object>> dateien = new LinkedHashMap<>();
        dateien.put("receipts", belege);

        BackupStore.restoreProfileSettings(ctx, BackupArchive.read(altesArchiv(dateien)));

        SharedPreferences prefs = ctx.getSharedPreferences("receipts", Context.MODE_PRIVATE);
        assertTrue("unters Profil, nicht blank", prefs.contains(prefix + "pending"));
    }

    /**
     * {@code widget_selection} ist die letzte wirklich geräteweite Prefs-Datei – ihr Zweig muss
     * gedeckt bleiben, auch nachdem {@code receipts} ihn verlassen hat.
     */
    @Test
    public void geraeteweiteDateienBekommenKeinProfilPraefix() throws Exception {
        String prefix = "p_" + new ProfileManager(ctx).getActiveProfileId() + "_";

        Map<String, Object> widgets = new LinkedHashMap<>();
        widgets.put("widget_17", "Girokonto");
        Map<String, Map<String, Object>> dateien = new LinkedHashMap<>();
        dateien.put("widget_selection", widgets);

        BackupStore.restoreProfileSettings(ctx, BackupArchive.read(altesArchiv(dateien)));

        SharedPreferences prefs = ctx.getSharedPreferences("widget_selection", Context.MODE_PRIVATE);
        assertEquals("Girokonto", prefs.getString("widget_17", null));
        assertFalse(prefs.contains(prefix + "widget_17"));
    }

    /** Eine heutige Sicherung darf der Sonderweg nicht anfassen: dort ist alles Profil-Sache. */
    @Test
    public void heutigeSicherungWandertUnveraendertUntersPraefix() throws Exception {
        String prefix = "p_" + new ProfileManager(ctx).getActiveProfileId() + "_";
        ctx.getSharedPreferences("ausgaben_settings", Context.MODE_PRIVATE).edit()
                .putString(prefix + "nextcloud_url", "https://neu.example/")
                .putString("language", "de")
                .commit();

        BackupArchive.Content content =
                BackupArchive.read(BackupStore.createProfile(ctx, false, ""));
        assertEquals(BackupArchive.FORMAT_PROFILES, content.format);

        BackupStore.restoreProfileSettings(ctx, content);

        SharedPreferences prefs = ctx.getSharedPreferences("ausgaben_settings", Context.MODE_PRIVATE);
        assertEquals("https://neu.example/", prefs.getString(prefix + "nextcloud_url", ""));
        assertEquals("die globale Sprache steht gar nicht erst im Archiv",
                "de", prefs.getString("language", ""));
    }
}
