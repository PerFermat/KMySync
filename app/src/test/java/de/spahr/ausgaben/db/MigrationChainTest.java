package de.spahr.ausgaben.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.room.migration.Migration;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Die Verdrahtung der Migrationen – die Lücke, die {@link NetCentsMigrationTest} in seinem eigenen
 * Javadoc offenlässt: dass eine neue Migration auch wirklich eingetragen und die Datenbankversion
 * erhöht wurde.
 *
 * <p>Vergisst man den Eintrag in {@code addMigrations}, fällt nichts auf: Auf einem frischen Gerät
 * legt Room das Schema einfach neu an, alle Tests laufen grün. Erst auf einem Bestandsgerät fehlt der
 * Weg von der alten zur neuen Version – und {@code fallbackToDestructiveMigration} wäre dort das
 * Ende der Daten. Diese Prüfung kostet nichts und greift bei jeder künftigen Migration mit.</p>
 *
 * <p>Reflexion statt Aufzählung: Eine von Hand gepflegte Liste hätte genau denselben Fehler wie
 * {@code addMigrations} – man vergisst, sie nachzuziehen.</p>
 *
 * <p>Die andere Hälfte prüft {@link SchemaMigrationTest}: nicht, <i>dass</i> eine Migration da ist,
 * sondern <i>was</i> sie hinterlässt. Beide zusammen decken die Gefahr ab; wer nur an eine denkt,
 * lässt die halbe Tür offen.</p>
 */
public class MigrationChainTest {

    /** Alle {@code MIGRATION_x_y}-Felder der Datenbankklasse, nach Startversion sortiert. */
    private static TreeMap<Integer, Migration> migrationen() throws IllegalAccessException {
        TreeMap<Integer, Migration> out = new TreeMap<>();
        for (Field f : AppDatabase.class.getDeclaredFields()) {
            if (!Migration.class.isAssignableFrom(f.getType()) || !Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            Migration m = (Migration) f.get(null);
            assertEquals("zwei Migrationen mit derselben Startversion " + m.startVersion,
                    null, out.put(m.startVersion, m));
        }
        assertTrue("keine Migration gefunden – Feldnamen geändert?", out.size() > 40);
        return out;
    }

    /** Der Quelltext der Datenbankklasse – {@code @Database} trägt nur CLASS-Retention und ist zur
     * Laufzeit nicht lesbar, die Version steht also nur dort. */
    private static String quelltext() throws Exception {
        return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/de/spahr/ausgaben/db/AppDatabase.java")),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private static int datenbankVersion() throws Exception {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("version\\s*=\\s*(\\d+)").matcher(quelltext());
        assertTrue("version = … in @Database nicht gefunden", m.find());
        return Integer.parseInt(m.group(1));
    }

    /** Von 1 bis zur aktuellen Version muss jeder Schritt vorhanden sein, ohne Loch. */
    @Test
    public void dieKetteReichtLueckenlosBisZurAktuellenVersion() throws Exception {
        TreeMap<Integer, Migration> alle = migrationen();
        int version = datenbankVersion();
        List<Integer> fehlend = new ArrayList<>();
        for (int von = 1; von < version; von++) {
            Migration m = alle.get(von);
            if (m == null || m.endVersion != von + 1) {
                fehlend.add(von);
            }
        }
        assertEquals("Migrationen fehlen (jeweils von Version → Version+1)", "[]", fehlend.toString());
    }

    /** Und keine Migration darf über die aktuelle Version hinausführen. */
    @Test
    public void keineMigrationZeigtUeberDieAktuelleVersionHinaus() throws Exception {
        int version = datenbankVersion();
        for (Migration m : migrationen().values()) {
            assertTrue("Migration " + m.startVersion + "→" + m.endVersion
                    + " führt über die Datenbankversion " + version + " hinaus",
                    m.endVersion <= version);
        }
    }

    /**
     * Jede vorhandene Migration muss auch in {@code addMigrations} stehen. Gelesen wird der Quelltext:
     * Der Aufruf steckt in einer privaten Methode, und die Liste dort ist genau die Stelle, die beim
     * Nachziehen vergessen wird.
     */
    @Test
    public void jedeMigrationIstAuchEingetragen() throws Exception {
        String quelle = quelltext();
        int ab = quelle.indexOf("addMigrations(");
        assertTrue("addMigrations( nicht gefunden", ab > 0);
        String liste = quelle.substring(ab, quelle.indexOf(')', ab));

        List<String> fehlend = new ArrayList<>();
        for (Migration m : migrationen().values()) {
            String name = "MIGRATION_" + m.startVersion + "_" + m.endVersion;
            if (!liste.contains(name)) {
                fehlend.add(name);
            }
        }
        assertEquals("nicht in addMigrations eingetragen", "[]", fehlend.toString());
    }
}
