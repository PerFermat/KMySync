package de.spahr.ausgaben.notify;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Der Puffer der aufgehobenen Benachrichtigungen — eine eigene kleine Datenbankdatei, <b>nicht</b> eine
 * Tabelle der App-Datenbank.
 *
 * <p>Der Grund ist die Sicherung: {@code BackupStore} kopiert die App-Datenbank als ganze Datei. Eine
 * Tabelle dort trüge die Rohtexte fremder Apps in jede Sicherung und von da auf jeden Server, auf dem
 * eine liegt. Hier stehen sie in einer Datei, die keine Sicherung kennt, und nur so lange, bis der
 * nächste Durchlauf sie verarbeitet hat.</p>
 *
 * <p>Die Datei gilt für das ganze Gerät, nicht je Profil: der Dienst läuft im Hintergrund und weiß
 * nicht, für welches Profil eine Meldung gedacht ist. Jedes Profil sieht die Meldungen der Apps, die
 * es freigegeben hat, und erledigt sie für sich; gelöscht wird erst, wenn alle fertig sind.</p>
 */
public final class NotificationBuffer extends SQLiteOpenHelper {

    static final String FILE = "notify_buffer.db";

    /** So lange bleibt eine Meldung, deren App keine Vorlage hat — Zeit genug, die Buchung anzulegen. */
    public static final long UNKNOWN_TTL_MS = 24L * 60 * 60 * 1000;

    /**
     * So lange wartet eine Meldung, deren App Regeln hat, auf den Durchlauf ihres Profils — danach gilt
     * das Profil als fertig, auch ohne Durchlauf.
     *
     * <p>Ohne Frist wüchse der Puffer für ein Profil, das niemand mehr aktualisiert, ohne Ende, mit
     * beliebig alten Rohtexten. Großzügig bemessen, weil eine verfallene Meldung eine Buchung kostet,
     * die niemand vermisst, bis der Abgleich nicht aufgeht: 30 Tage reichen auch für ein Profil, das
     * man nur zum Monatsabschluss öffnet.</p>
     */
    public static final long KNOWN_TTL_MS = 30L * 24 * 60 * 60 * 1000;

    private static NotificationBuffer instance;

    /** Eine aufgehobene Benachrichtigung. */
    public static final class Entry {
        public final long id;
        public final String packageName;
        public final long postTime;
        public final String title;
        public final String text;
        public final String key;
        /** Wo das Handy beim Eintreffen war („lat, lon"); leer, wenn unbekannt. */
        public final String coords;

        public Entry(long id, String packageName, long postTime, String title, String text,
                     String key) {
            this(id, packageName, postTime, title, text, key, "");
        }

        public Entry(long id, String packageName, long postTime, String title, String text,
                     String key, String coords) {
            this.coords = coords == null ? "" : coords;
            this.id = id;
            this.packageName = packageName;
            this.postTime = postTime;
            this.title = title;
            this.text = text;
            this.key = key;
        }
    }

    public static synchronized NotificationBuffer get(Context context) {
        if (instance == null) {
            instance = new NotificationBuffer(context.getApplicationContext());
        }
        return instance;
    }

    /** Für Tests: eine eigene Instanz statt der gemeinsamen. */
    NotificationBuffer(Context context) {
        super(context, FILE, null, 4);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE notification_buffer (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "package_name TEXT NOT NULL, post_time INTEGER NOT NULL, title TEXT NOT NULL, "
                + "text TEXT NOT NULL, notif_key TEXT NOT NULL, coords TEXT NOT NULL DEFAULT '', "
                + "ready_at INTEGER NOT NULL DEFAULT 0, UNIQUE(notif_key, post_time))");
        createDone(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createDone(db);
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE notification_buffer ADD COLUMN coords TEXT NOT NULL DEFAULT ''");
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE notification_buffer ADD COLUMN ready_at INTEGER NOT NULL DEFAULT 0");
        }
    }

    /**
     * Welches Profil welche Meldung schon erledigt hat. Dieselbe App kann in zwei Profilen freigegeben
     * sein — eine gemeinsame Karte —, und dann darf der Abschluss im einen die Meldung dem anderen
     * nicht wegnehmen.
     */
    private static void createDone(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS notification_done (entry_id INTEGER NOT NULL, "
                + "profile_id TEXT NOT NULL, PRIMARY KEY(entry_id, profile_id))");
    }

    /**
     * Hebt eine Meldung auf; -1, wenn genau diese schon da ist.
     *
     * <p>„Genau diese" heißt gleicher Schlüssel <b>und</b> gleiche Zeit: das System meldet dieselbe
     * Benachrichtigung mehrfach, etwa wenn sich nur ihre Rangfolge ändert. Aktualisiert eine App ihre
     * Meldung, trägt sie eine neue Zeit und bleibt als zweiter Eintrag — den Unterschied beurteilt
     * später die Duplikatprüfung, nicht der Puffer.</p>
     */
    public synchronized long add(String packageName, long postTime, String title, String text,
                                 String key) {
        return add(packageName, postTime, title, text, key, 0L);
    }

    /**
     * Wie oben, aber erst ab {@code readyAt} für das Aktualisieren bestimmt ({@link #readyFor}): so
     * lange läuft die Ortung beim Eintreffen. Steht sie vorher fest, gibt {@link #setCoords} die
     * Meldung frei; bricht sie ab, wird sie mit {@code readyAt} von selbst frei.
     */
    public synchronized long add(String packageName, long postTime, String title, String text,
                                 String key, long readyAt) {
        ContentValues v = new ContentValues();
        v.put("ready_at", readyAt);
        v.put("package_name", packageName == null ? "" : packageName);
        v.put("post_time", postTime);
        v.put("title", title == null ? "" : title);
        v.put("text", text == null ? "" : text);
        v.put("notif_key", key == null ? "" : key);
        return getWritableDatabase().insertWithOnConflict("notification_buffer", null, v,
                SQLiteDatabase.CONFLICT_IGNORE);
    }

    /**
     * Trägt nach, wo das Handy beim Eintreffen der Meldung war, und gibt sie für das Aktualisieren
     * frei – auch ohne Standort: dann ist die Ortung eben zu Ende. Getrennt vom Aufheben, weil die
     * Ortung bis zu einer Minute braucht.
     */
    public synchronized void setCoords(long id, String coords) {
        if (id <= 0) {
            return;
        }
        ContentValues v = new ContentValues();
        if (coords != null && !coords.isEmpty()) {
            v.put("coords", coords);
        }
        v.put("ready_at", 0L);
        getWritableDatabase().update("notification_buffer", v, "id = ?",
                new String[]{String.valueOf(id)});
    }

    /** Alle Einträge, die ältesten zuerst. */
    public synchronized List<Entry> all() {
        return query("", null);
    }

    /** Die Einträge, die dieses Profil noch nicht erledigt hat, die ältesten zuerst. */
    public synchronized List<Entry> allFor(String profileId) {
        return query("WHERE id NOT IN (SELECT entry_id FROM notification_done WHERE profile_id = ?) ",
                new String[]{profileId});
    }

    /**
     * Wie {@link #allFor}, aber ohne Meldungen, deren Ortung beim Eintreffen noch läuft – für das
     * Aktualisieren. Die Lernfrage nimmt {@link #allFor}: zum Lernen braucht es keinen Standort.
     */
    public synchronized List<Entry> readyFor(String profileId, long now) {
        return query("WHERE id NOT IN (SELECT entry_id FROM notification_done WHERE profile_id = ?) "
                + "AND ready_at <= ? ", new String[]{profileId, String.valueOf(now)});
    }

    private List<Entry> query(String where, String[] args) {
        List<Entry> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id, package_name, post_time, title, "
                + "text, notif_key, coords FROM notification_buffer " + where + "ORDER BY post_time, id", args)) {
            while (c.moveToNext()) {
                out.add(new Entry(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3),
                        c.getString(4), c.getString(5), c.getString(6)));
            }
        }
        return out;
    }

    /**
     * Vermerkt, dass dieses Profil mit diesen Einträgen fertig ist — gebucht, verworfen oder daraus
     * gelernt. Nur über die Nummern, nie „alles von dieser App": was eintrifft, während die Liste
     * offen ist, hat der Nutzer nicht gesehen und muss bleiben.
     *
     * <p>Gelöscht wird hier nichts. Das entscheidet {@link #purge}, sobald jedes beteiligte Profil
     * fertig ist.</p>
     */
    public synchronized void markDone(Collection<Long> ids, String profileId) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Long id : ids) {
                ContentValues v = new ContentValues();
                v.put("entry_id", id);
                v.put("profile_id", profileId);
                db.insertWithOnConflict("notification_done", null, v, SQLiteDatabase.CONFLICT_IGNORE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Löscht, was kein Profil mehr braucht — die einzige Stelle, die über das Löschen entscheidet.
     *
     * <p><b>Beteiligt</b> an einer Meldung ist jedes Profil, das ihre App freigegeben hat. <b>Fertig</b>
     * ist ein Profil, wenn es die Meldung erledigt hat ({@link #markDone}) — oder wenn sie älter ist
     * als seine Frist. Hat es für die App keine Regel, sind das {@link #UNKNOWN_TTL_MS}: einen Tag
     * Zeit, die Buchung dazu anzulegen, danach sähe es die Meldung in keiner Liste je. Hat es Regeln,
     * sind es {@link #KNOWN_TTL_MS}. Gelöscht wird, wenn alle Beteiligten fertig sind; ohne
     * Beteiligte sofort.</p>
     *
     * @param allowedByProfile Profil → freigegebene Apps
     * @param knownByProfile   Profil → Apps, für die es Regeln hat
     * @return wie viele Einträge gelöscht wurden
     */
    public synchronized int purge(long now, java.util.Map<String, Set<String>> allowedByProfile,
                                  java.util.Map<String, Set<String>> knownByProfile) {
        java.util.Map<Long, Set<String>> doneBy = new java.util.HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT entry_id, profile_id FROM notification_done", null)) {
            while (c.moveToNext()) {
                Set<String> profiles = doneBy.get(c.getLong(0));
                if (profiles == null) {
                    profiles = new java.util.HashSet<>();
                    doneBy.put(c.getLong(0), profiles);
                }
                profiles.add(c.getString(1));
            }
        }
        List<Long> gone = new ArrayList<>();
        for (Entry e : all()) {
            boolean needed = false;
            for (java.util.Map.Entry<String, Set<String>> profile : allowedByProfile.entrySet()) {
                if (!profile.getValue().contains(e.packageName)) {
                    continue;   // nicht beteiligt
                }
                Set<String> done = doneBy.get(e.id);
                if (done != null && done.contains(profile.getKey())) {
                    continue;
                }
                Set<String> known = knownByProfile.get(profile.getKey());
                boolean hasRules = known != null && known.contains(e.packageName);
                if (e.postTime < now - (hasRules ? KNOWN_TTL_MS : UNKNOWN_TTL_MS)) {
                    continue;   // Frist dieses Profils abgelaufen
                }
                needed = true;
                break;
            }
            if (!needed) {
                gone.add(e.id);
            }
        }
        delete(gone);
        return gone.size();
    }

    /** {@link #purge} mit dem, was Einstellungen und Regeln gerade sagen. */
    public static void purgeNow(Context context) {
        get(context).purge(System.currentTimeMillis(),
                de.spahr.ausgaben.settings.SettingsStore.notifyAppsByProfile(context),
                new KnownApps(context).byProfile());
    }

    /** Löscht genau diese Einträge samt ihren Erledigt-Vermerken. */
    public synchronized void delete(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Long id : ids) {
                String[] arg = {String.valueOf(id)};
                db.delete("notification_buffer", "id = ?", arg);
                db.delete("notification_done", "entry_id = ?", arg);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void clear() {
        getWritableDatabase().delete("notification_buffer", null, null);
        getWritableDatabase().delete("notification_done", null, null);
    }
}
