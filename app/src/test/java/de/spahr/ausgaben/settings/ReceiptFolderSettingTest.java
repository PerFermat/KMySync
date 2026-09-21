package de.spahr.ausgaben.settings;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Hält fest, was aus einer Eingabe im Feld „Belegordner" wird
 * ({@link SettingsStore#normalizeReceiptFolder}).
 *
 * <p>Die Schrägstriche sind der Anlass: {@code RemotePath.join} fügt selbst einen ein, und eine
 * Eingabe wie {@code "/Belege/"} ergäbe sonst einen Pfad mit leeren Abschnitten. Auf SMB kommt dabei
 * kein Fehler heraus, sondern ein Ordner mit seltsamem Namen – ein Fehler, den man erst bemerkt,
 * wenn die Belege verschwunden scheinen.</p>
 */
public class ReceiptFolderSettingTest {

    @Test
    public void leeresFeldErgibtDieVorgabe() {
        assertEquals("Belege", SettingsStore.normalizeReceiptFolder(null));
        assertEquals("Belege", SettingsStore.normalizeReceiptFolder(""));
        assertEquals("Belege", SettingsStore.normalizeReceiptFolder("   "));
    }

    /** Ein Feld, in dem nur Schrägstriche stehen, ist so gut wie leer – und darf keinen leeren Namen ergeben. */
    @Test
    public void nurSchraegstricheErgebenDieVorgabe() {
        assertEquals("Belege", SettingsStore.normalizeReceiptFolder("/"));
        assertEquals("Belege", SettingsStore.normalizeReceiptFolder("///"));
    }

    @Test
    public void schraegstricheUndLeerzeichenFallenWeg() {
        assertEquals("Belege", SettingsStore.normalizeReceiptFolder("  Belege  "));
        assertEquals("Belege", SettingsStore.normalizeReceiptFolder("/Belege"));
        assertEquals("Belege", SettingsStore.normalizeReceiptFolder("Belege/"));
        assertEquals("Belege", SettingsStore.normalizeReceiptFolder("/Belege/"));
        assertEquals("Belege-Firma", SettingsStore.normalizeReceiptFolder(" /Belege-Firma/ "));
    }

    /** Ein Name mit Leerzeichen darin bleibt, wie er ist – nur außen wird geputzt. */
    @Test
    public void innereZeichenBleiben() {
        assertEquals("Belege Firma", SettingsStore.normalizeReceiptFolder("  Belege Firma  "));
        assertEquals("Belege_2026", SettingsStore.normalizeReceiptFolder("Belege_2026"));
    }
}
