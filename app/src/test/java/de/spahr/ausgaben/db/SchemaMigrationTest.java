package de.spahr.ausgaben.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.room.Room;
import androidx.room.migration.Migration;
import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Die andere Hälfte der Gefahr, die {@link MigrationChainTest} offenlässt.
 *
 * <p>Jener prüft die <b>Verdrahtung</b> — dass eine Migration eingetragen ist und die Kette kein Loch
 * hat. Was er nicht prüfen kann, ist das <b>Ergebnis</b>: ob das, was eine Migration hinterlässt, dem
 * entspricht, was Room aus den Entities erwartet. Ein {@code TEXT} statt {@code INTEGER} oder ein
 * vergessenes {@code DEFAULT} fällt auf einem frischen Gerät nie auf — dort legt Room das Schema neu
 * an — und schlägt erst beim Nutzer zu, der aktualisiert.</p>
 *
 * <p>Still bliebe das nicht: {@code fallbackToDestructiveMigration} gibt es bewusst nicht, Room wirft
 * bei Abweichung. Es wäre also kein Datenverlust, sondern ein Absturz beim Öffnen — bloß eben auf dem
 * fremden Gerät statt hier.</p>
 *
 * <h2>Wie geprüft wird</h2>
 *
 * <p>Nicht mit einer selbstgebauten Schemavergleichung. Room bringt seine eigene mit, sie muss nur
 * ausgelöst werden: Der Test baut aus dem ältesten eingecheckten Schema-JSON eine echte Datenbank,
 * setzt {@code user_version} und lässt Room sie öffnen. Room führt die fehlenden Migrationen aus und
 * vergleicht danach gegen die Entities.</p>
 *
 * <p>Ein Kniff ist dabei nötig: Die {@code setupQueries} des JSON legen {@code room_master_table} mit
 * dem Identitäts-Hash an. Findet Room die Tabelle vor und stimmt der Hash, <b>überspringt</b> es die
 * Prüfung — der Test liefe stumm durch. Deshalb werden nur die Tabellen selbst angelegt, nicht die
 * {@code setupQueries}. Ohne die Master-Tabelle validiert Room das Schema in jedem Fall.</p>
 *
 * <p><b>Reichweite.</b> Der Schema-Export wurde bei Version 51 eingeschaltet; für die 50
 * zurückliegenden Migrationen gibt es kein JSON und rückwirkend keines. Solange nur eine Version
 * vorliegt, prüft dieser Test, dass das eingecheckte Schema zu den Entities passt. Mit der nächsten
 * Migration prüft er den Weg dorthin gleich mit.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SchemaMigrationTest {

    private static final String SCHEMA_DIR = "schemas/de.spahr.ausgaben.db.AppDatabase";

    private final Context ctx = ApplicationProvider.getApplicationContext();

    /** Die eingecheckten Schemaversionen, aufsteigend. */
    private static TreeMap<Integer, File> schemata() {
        File dir = new File(SCHEMA_DIR);
        assertTrue("Schemaordner " + dir.getAbsolutePath() + " fehlt – exportSchema abgeschaltet "
                + "oder die JSON-Dateien nicht eingecheckt?", dir.isDirectory());

        TreeMap<Integer, File> out = new TreeMap<>();
        File[] dateien = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (dateien != null) {
            for (File f : dateien) {
                out.put(Integer.parseInt(f.getName().replace(".json", "")), f);
            }
        }
        assertTrue("keine Schema-JSON gefunden – sonst liefe dieser Test stumm durch",
                !out.isEmpty());
        return out;
    }

    private static JSONObject gelesen(File f) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))
                .getJSONObject("database");
    }

    /** Die Version aus {@code @Database} – die Annotation ist zur Laufzeit nicht lesbar. */
    private static int datenbankVersion() throws Exception {
        String quelle = new String(Files.readAllBytes(
                Paths.get("src/main/java/de/spahr/ausgaben/db/AppDatabase.java")),
                StandardCharsets.UTF_8);
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("version\\s*=\\s*(\\d+)").matcher(quelle);
        assertTrue("version = … in @Database nicht gefunden", m.find());
        return Integer.parseInt(m.group(1));
    }

    /**
     * Zur aktuellen Datenbankversion muss ein Schema vorliegen.
     *
     * <p><b>Was das nicht leistet:</b> Es fängt <i>nicht</i> ab, dass jemand die Version erhöht und
     * die neue JSON-Datei nicht eincheckt — der Annotationsprozessor läuft beim Bauen mit und legt
     * sie einfach wieder an, auch im frischen Klon. Nachgestellt und gesehen: Nach dem Erhöhen auf 52
     * lag 52.json sofort da und diese Prüfung blieb grün. Ein nicht eingecheckter Stand ist auch kein
     * Korrektheitsproblem, sondern kostet nur die Vergleichsgrundlage für die übernächste Migration
     * — dafür ist {@code git status} die richtige Stelle, nicht ein Test.</p>
     *
     * <p><b>Was es leistet:</b> Es schlägt an, wenn der Export abgeschaltet oder {@code
     * room.schemaLocation} verlorengegangen ist und der Ordner dadurch veraltet oder leer bleibt.
     * Ohne diese Prüfung liefe {@link #vomAeltestenSchemaAusMigriertRoomOhneKlage} dann stumm durch
     * und täuschte Sicherheit vor.</p>
     */
    @Test
    public void zurAktuellenVersionLiegtEinSchemaVor() throws Exception {
        assertEquals("für die aktuelle Datenbankversion fehlt das Schema-JSON in " + SCHEMA_DIR
                        + " – ist exportSchema aus oder room.schemaLocation verlorengegangen?",
                datenbankVersion(), (int) schemata().lastKey());
    }

    /**
     * Der eigentliche Test: vom ältesten vorliegenden Schema aus öffnen und Room urteilen lassen.
     */
    @Test
    public void vomAeltestenSchemaAusMigriertRoomOhneKlage() throws Exception {
        TreeMap<Integer, File> alle = schemata();
        int von = alle.firstKey();

        File datei = ctx.getDatabasePath("schema_probe_" + von + ".db");
        //noinspection ResultOfMethodCallIgnored
        datei.getParentFile().mkdirs();
        //noinspection ResultOfMethodCallIgnored
        datei.delete();
        tabellenAnlegen(gelesen(alle.get(von)), datei, von);

        AppDatabase db = Room.databaseBuilder(ctx, AppDatabase.class, datei.getName())
                .addMigrations(migrationen())
                .allowMainThreadQueries()
                .build();
        try {
            // Erst hier passiert etwas: Room migriert auf die aktuelle Version und validiert danach
            // das Ergebnis gegen die Entities. Weicht es ab, fliegt eine IllegalStateException.
            db.getOpenHelper().getWritableDatabase();
        } finally {
            db.close();
            //noinspection ResultOfMethodCallIgnored
            datei.delete();
        }
    }

    /** Legt die Tabellen und Indizes des Schemas an – ohne {@code setupQueries}, siehe Klassen-Javadoc. */
    private static void tabellenAnlegen(JSONObject schema, File datei, int version) throws Exception {
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(datei, null);
        try {
            JSONArray entities = schema.getJSONArray("entities");
            for (int i = 0; i < entities.length(); i++) {
                JSONObject e = entities.getJSONObject(i);
                String tabelle = e.getString("tableName");
                db.execSQL(e.getString("createSql").replace("${TABLE_NAME}", tabelle));

                JSONArray indices = e.optJSONArray("indices");
                for (int k = 0; indices != null && k < indices.length(); k++) {
                    db.execSQL(indices.getJSONObject(k).getString("createSql")
                            .replace("${TABLE_NAME}", tabelle));
                }
            }
            JSONArray views = schema.optJSONArray("views");
            for (int i = 0; views != null && i < views.length(); i++) {
                db.execSQL(views.getJSONObject(i).getString("createSql")
                        .replace("${VIEW_NAME}", views.getJSONObject(i).getString("viewName")));
            }
            db.execSQL("PRAGMA user_version = " + version);
        } finally {
            db.close();
        }
    }

    /**
     * Alle {@code MIGRATION_x_y}-Felder per Reflexion – aus demselben Grund wie in
     * {@link MigrationChainTest}: Eine von Hand gepflegte Liste hätte genau denselben Fehler wie
     * {@code addMigrations}, nämlich den, dass man sie vergisst.
     */
    private static Migration[] migrationen() throws IllegalAccessException {
        List<Migration> out = new ArrayList<>();
        for (Field f : AppDatabase.class.getDeclaredFields()) {
            if (Migration.class.isAssignableFrom(f.getType()) && Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);
                out.add((Migration) f.get(null));
            }
        }
        assertTrue("keine Migration gefunden – Feldnamen geändert?", out.size() > 40);
        return out.toArray(new Migration[0]);
    }
}
