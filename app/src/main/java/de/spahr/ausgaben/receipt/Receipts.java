package de.spahr.ausgaben.receipt;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * Lokale Ablage der Belegfotos (app-privates {@code belege/}-Verzeichnis) und die Merkliste der noch nicht
 * hochgeladenen Dateien (SharedPreferences – keine Room-Tabelle, keine Migration).
 *
 * <h2>Alles hier gehört einem Profil</h2>
 *
 * <p>Bis 2.1 lagen die Dateien aller Profile in <b>einem</b> Ordner, und die beiden Merklisten
 * standen unter blanken Schlüsseln – als einzige Einstellungen der App ohne Profil-Präfix. Das war
 * der Boden für einen Datenverlust: {@code ReceiptGc} bildet seine Behalte-Liste aus der Datenbank
 * des <b>aktiven</b> Profils, listete aber alle Dateien des gemeinsamen Ordners und erklärte den
 * Rest zu Waisen. Bei jedem Kaltstart traf es die Belege des jeweils anderen Profils.</p>
 *
 * <p>Jedes Profil hat deshalb seinen eigenen Unterordner und seine eigenen Merklisten. Die
 * Signaturen sind dabei absichtlich <b>unverändert</b> geblieben: Die rund vierzig Aufrufstellen
 * arbeiten weiter mit {@link #dir(Context)} und den Merklisten des aktiven Profils, ohne davon zu
 * wissen. Den Bestand ordnet {@link ReceiptProfileMigration} einmalig zu.</p>
 */
public final class Receipts {

    private static final String PREFS = "receipts";
    static final String KEY_PENDING = "pending";
    static final String KEY_MOVES = "moves";
    static final String KEY_FOLDER_MOVES = "folder_moves";

    /** Name des Wurzelverzeichnisses; darunter liegt je Profil ein Unterordner. */
    private static final String ROOT = "belege";

    private Receipts() {
    }

    /**
     * Die Wurzel aller Belegordner. Enthält seit 2.2 nur noch Profil-Unterordner – wer hier Dateien
     * findet, sieht Altbestand (siehe {@link ReceiptProfileMigration}).
     */
    static File root(Context ctx) {
        File d = ctx.getExternalFilesDir(ROOT);
        if (d == null) {
            d = new File(ctx.getFilesDir(), ROOT);
        }
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    /**
     * Der Unterordnername eines Profils.
     *
     * <p>Das Präfix {@code p_} ist nicht Zierrat: Es macht im Dateisystem auf einen Blick
     * unterscheidbar, was Profilordner und was Altbestand ist, und es hält künftige Zusatzordner
     * (etwa ein lokaler Zwischenspeicher) von einer Profil-Id fern.</p>
     *
     * <p>Eine leere Id – im Test, und theoretisch vor der Profil-Migration – ergibt einen
     * <b>festen Ersatznamen</b> statt nichts. Ohne ihn wäre der Profilordner die Wurzel selbst, und
     * die Bestandsmigration räumte sich selbst aus.</p>
     *
     * <p>Rein und ohne Android, damit {@code ReceiptProfileFolderTest} die Randfälle festhält.</p>
     */
    public static String folderFor(String profileId) {
        String id = profileId == null ? "" : profileId.replaceAll("[^A-Za-z0-9_-]", "");
        return id.isEmpty() ? "p_default" : "p_" + id;
    }

    /** Belegordner des aktiven Profils (wird bei Bedarf angelegt). */
    public static File dir(Context ctx) {
        return dirOf(ctx, activeProfileId(ctx));
    }

    /** Belegordner eines bestimmten Profils – für die Migration und das Löschen eines Profils. */
    public static File dirOf(Context ctx, String profileId) {
        File d = new File(root(ctx), folderFor(profileId));
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    public static File localFile(Context ctx, String file) {
        return new File(dir(ctx), file);
    }

    /**
     * Setzt <b>dieses</b> Profil zurück: seine Belegdateien und seine drei Merklisten.
     *
     * <p>Bis 2.1 löschte diese Methode die Belege aller Profile – deshalb durfte sie beim
     * Zurücksetzen eines einzelnen Profils gar nicht gerufen werden, und dessen Belege blieben
     * liegen. Beides ist jetzt in Ordnung; für den Werksreset gibt es {@link #resetAll(Context)}.</p>
     */
    public static synchronized void reset(Context ctx) {
        deleteProfile(ctx, activeProfileId(ctx));
    }

    /** Werksreset: der ganze Baum und die komplette Merklisten-Datei. */
    public static synchronized void resetAll(Context ctx) {
        loescheInhalt(root(ctx));
        prefs(ctx).edit().clear().apply();
    }

    /** Beim Löschen eines Profils: dessen Ordner und dessen Merklisten. */
    public static synchronized void deleteProfile(Context ctx, String profileId) {
        File d = new File(root(ctx), folderFor(profileId));
        loescheInhalt(d);
        //noinspection ResultOfMethodCallIgnored
        d.delete();
        String prefix = folderFor(profileId) + "_";
        prefs(ctx).edit()
                .remove(prefix + KEY_PENDING)
                .remove(prefix + KEY_MOVES)
                .remove(prefix + KEY_FOLDER_MOVES)
                .apply();
    }

    /** Löscht den Inhalt eines Ordners rekursiv, den Ordner selbst nicht. */
    private static void loescheInhalt(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                loescheInhalt(f);
            }
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static String activeProfileId(Context ctx) {
        return new de.spahr.ausgaben.settings.ProfileManager(ctx.getApplicationContext())
                .getActiveProfileId();
    }

    /**
     * Der Merklisten-Schlüssel des aktiven Profils, etwa {@code p_a1b2_pending}.
     *
     * <p>Derselbe Gedanke wie der Profil-Präfix in {@code SettingsStore#pk} – nur dass er hier gut
     * zwei Jahre gefehlt hat.</p>
     */
    private static String key(Context ctx, String baseKey) {
        return folderFor(activeProfileId(ctx)) + "_" + baseKey;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Offene Einträge in der Form {@code <jahr>|<datei>}. Seit die Dateinamen kein Jahr mehr tragen, muss
     * der Jahresordner mitgeführt werden; Einträge aus älteren Versionen stehen ohne {@code |} darin und
     * werden weiter gelesen (Jahr dann aus dem Dateinamen).
     */
    public static synchronized Set<String> pending(Context ctx) {
        return new HashSet<>(prefs(ctx).getStringSet(key(ctx, KEY_PENDING), new HashSet<>()));
    }

    /** Dateiname eines Merklisten-Eintrags. */
    public static String entryFile(String entry) {
        int bar = entry == null ? -1 : entry.indexOf('|');
        return bar < 0 ? entry : entry.substring(bar + 1);
    }

    /** Jahresordner eines Merklisten-Eintrags; {@code -1}, wenn er sich nicht ermitteln lässt. */
    public static int entryYear(String entry) {
        int bar = entry == null ? -1 : entry.indexOf('|');
        if (bar > 0) {
            try {
                return Integer.parseInt(entry.substring(0, bar));
            } catch (NumberFormatException ignored) {
                // fällt unten auf den Dateinamen zurück
            }
        }
        return NoteReceipt.yearOf(entryFile(entry));
    }

    /** Merkt eine Datei zum Hochladen vor; das Jahr bestimmt den Zielordner auf dem Server. */
    public static synchronized void addPending(Context ctx, String file, int year) {
        Set<String> s = pending(ctx);
        removeFile(s, file);
        if (s.add(year + "|" + file)) {
            prefs(ctx).edit().putStringSet(key(ctx, KEY_PENDING), s).apply();
        }
    }

    public static synchronized void removePending(Context ctx, String file) {
        Set<String> s = pending(ctx);
        if (removeFile(s, file)) {
            prefs(ctx).edit().putStringSet(key(ctx, KEY_PENDING), s).apply();
        }
    }

    // ---- Offene Jahreswechsel ----

    /**
     * Offene Umzüge in der Form {@code <vonJahr>|<nachJahr>|<datei>}.
     *
     * <p>Wird das Buchungsdatum über einen Jahreswechsel geschoben, muss die Datei auf dem Server in
     * den neuen Jahresordner. Klappt das gerade nicht (offline), war der Beleg bisher verloren: Die
     * Notiz nennt das neue Jahr, die Datei liegt im alten, und niemand versuchte es je wieder. Deshalb
     * steht der Vorsatz hier, bis er ausgeführt ist – wie die Merkliste der offenen Uploads.</p>
     */
    public static synchronized Set<String> moves(Context ctx) {
        return new HashSet<>(prefs(ctx).getStringSet(key(ctx, KEY_MOVES), new HashSet<>()));
    }

    /** Merkt einen Umzug vor. Ein schon vorgemerkter Umzug derselben Datei wird zusammengefasst. */
    public static synchronized void addMove(Context ctx, String file, int fromYear, int toYear) {
        if (file == null || fromYear == toYear) {
            return;
        }
        Set<String> s = moves(ctx);
        // Schon vorgemerkt? Dann zählt der ursprüngliche Ausgangsordner, nicht der zwischenzeitliche:
        // Verschiebt der Nutzer 2024 → 2025 → 2026, ohne dass es dazwischen klappte, liegt die Datei
        // immer noch in 2024.
        int von = fromYear;
        for (java.util.Iterator<String> it = s.iterator(); it.hasNext(); ) {
            String e = it.next();
            String[] teile = e.split("\\|", 3);
            if (teile.length == 3 && teile[2].equals(file)) {
                try {
                    von = Integer.parseInt(teile[0]);
                } catch (NumberFormatException ignored) {
                    // unbrauchbarer Altstand – dann gilt das übergebene Jahr
                }
                it.remove();
            }
        }
        if (von != toYear) {
            s.add(von + "|" + toYear + "|" + file);
        }
        prefs(ctx).edit().putStringSet(key(ctx, KEY_MOVES), s).apply();
    }

    /** Streicht einen erledigten (oder gegenstandslosen) Umzug. */
    public static synchronized void removeMove(Context ctx, String entry) {
        Set<String> s = moves(ctx);
        if (s.remove(entry)) {
            prefs(ctx).edit().putStringSet(key(ctx, KEY_MOVES), s).apply();
        }
    }

    /** Ausgangsjahr, Zieljahr und Datei eines Umzugs-Eintrags; {@code null}, wenn unbrauchbar. */
    public static String[] moveParts(String entry) {
        String[] teile = entry == null ? null : entry.split("\\|", 3);
        return teile != null && teile.length == 3 ? teile : null;
    }

    // ---- Offener Wechsel des Belegordners ----

    /**
     * Offene Ordnerwechsel in der Form {@code <vonOrdner>|<nachOrdner>|<datei>}, beide Ordner als
     * vollständige Serverpfade einschließlich Jahresordner.
     *
     * <h2>Warum eine eigene Liste neben {@link #moves(Context)}</h2>
     *
     * <p>Der Jahreswechsel kennt nur zwei Zahlen und leitet die Pfade beim Ausführen aus den
     * Einstellungen ab. Genau das geht hier nicht: Der Ausgangsordner ist der <b>alte</b>
     * Belegordner, und der steht nach dem Umstellen in keiner Einstellung mehr. Er muss deshalb im
     * Eintrag selbst stehen.</p>
     *
     * <p>Vorgemerkt wird, wenn der Nutzer den Belegordner seines Profils ändert. Ausgeführt wird im
     * Hintergrund ({@code ReceiptFolderMove}), damit ein Funkloch die Umstellung nicht blockiert –
     * solange etwas offen ist, sucht {@code ReceiptPages} zusätzlich am alten Ort.</p>
     */
    public static synchronized Set<String> folderMoves(Context ctx) {
        return new HashSet<>(prefs(ctx).getStringSet(key(ctx, KEY_FOLDER_MOVES), new HashSet<>()));
    }

    /**
     * Merkt einen Ordnerwechsel vor. Ein schon vorgemerkter Wechsel derselben Datei wird
     * zusammengefasst: Es zählt der <b>ursprüngliche</b> Ausgangsordner, denn dort liegt die Datei
     * noch – dieselbe Überlegung wie bei {@link #addMove}.
     */
    public static synchronized void addFolderMove(Context ctx, String file, String fromFolder,
                                                  String toFolder) {
        if (file == null || fromFolder == null || toFolder == null || fromFolder.equals(toFolder)) {
            return;
        }
        Set<String> s = folderMoves(ctx);
        String von = fromFolder;
        for (java.util.Iterator<String> it = s.iterator(); it.hasNext(); ) {
            String[] teile = folderMoveParts(it.next());
            if (teile != null && teile[2].equals(file)) {
                von = teile[0];
                it.remove();
            }
        }
        if (!von.equals(toFolder)) {
            s.add(von + "|" + toFolder + "|" + file);
        }
        prefs(ctx).edit().putStringSet(key(ctx, KEY_FOLDER_MOVES), s).apply();
    }

    /** Streicht einen erledigten (oder gegenstandslosen) Ordnerwechsel. */
    public static synchronized void removeFolderMove(Context ctx, String entry) {
        Set<String> s = folderMoves(ctx);
        if (s.remove(entry)) {
            prefs(ctx).edit().putStringSet(key(ctx, KEY_FOLDER_MOVES), s).apply();
        }
    }

    /**
     * Ausgangsordner, Zielordner und Datei eines Ordnerwechsel-Eintrags; {@code null}, wenn
     * unbrauchbar.
     *
     * <p>Zerlegt wird von <b>hinten</b>, nicht mit {@code split("\\|", 3)} wie bei den Jahren: Ein
     * Serverpfad darf ein {@code |} enthalten, ein Belegdateiname (UUID plus {@code _p1.jpg}) nicht.
     * Von vorn zerlegt risse ein solcher Pfad den Eintrag an der falschen Stelle auseinander.</p>
     */
    public static String[] folderMoveParts(String entry) {
        if (entry == null) {
            return null;
        }
        int letzter = entry.lastIndexOf('|');
        if (letzter < 0) {
            return null;
        }
        int vorletzter = entry.lastIndexOf('|', letzter - 1);
        if (vorletzter < 0) {
            return null;
        }
        String von = entry.substring(0, vorletzter);
        String nach = entry.substring(vorletzter + 1, letzter);
        String datei = entry.substring(letzter + 1);
        if (von.isEmpty() || nach.isEmpty() || datei.isEmpty()) {
            return null;
        }
        return new String[]{von, nach, datei};
    }

    /** Entfernt alle Einträge zu {@code file} – mit und ohne Jahresangabe. */
    private static boolean removeFile(Set<String> entries, String file) {
        boolean changed = false;
        for (java.util.Iterator<String> it = entries.iterator(); it.hasNext(); ) {
            if (entryFile(it.next()).equals(file)) {
                it.remove();
                changed = true;
            }
        }
        return changed;
    }
}
