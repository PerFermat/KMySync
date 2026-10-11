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
 * nicht, für welches Profil eine Meldung gedacht ist. Welches Profil sie zeigt, entscheiden dessen
 * Vorlagen.</p>
 */
public final class NotificationBuffer extends SQLiteOpenHelper {

    static final String FILE = "notify_buffer.db";

    /** So lange bleibt eine Meldung, deren App keine Vorlage hat — Zeit genug, die Buchung anzulegen. */
    public static final long UNKNOWN_TTL_MS = 24L * 60 * 60 * 1000;

    private static NotificationBuffer instance;

    /** Eine aufgehobene Benachrichtigung. */
    public static final class Entry {
        public final long id;
        public final String packageName;
        public final long postTime;
        public final String title;
        public final String text;
        public final String key;

        public Entry(long id, String packageName, long postTime, String title, String text,
                     String key) {
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
        super(context, FILE, null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE notification_buffer (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "package_name TEXT NOT NULL, post_time INTEGER NOT NULL, title TEXT NOT NULL, "
                + "text TEXT NOT NULL, notif_key TEXT NOT NULL, UNIQUE(notif_key, post_time))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 ist die erste.
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
        ContentValues v = new ContentValues();
        v.put("package_name", packageName == null ? "" : packageName);
        v.put("post_time", postTime);
        v.put("title", title == null ? "" : title);
        v.put("text", text == null ? "" : text);
        v.put("notif_key", key == null ? "" : key);
        return getWritableDatabase().insertWithOnConflict("notification_buffer", null, v,
                SQLiteDatabase.CONFLICT_IGNORE);
    }

    /** Alle Einträge, die ältesten zuerst. */
    public synchronized List<Entry> all() {
        List<Entry> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id, package_name, post_time, title, "
                + "text, notif_key FROM notification_buffer ORDER BY post_time, id", null)) {
            while (c.moveToNext()) {
                out.add(new Entry(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3),
                        c.getString(4), c.getString(5)));
            }
        }
        return out;
    }

    /**
     * Löscht genau diese Einträge. Nur über die Nummern, nie „alles von dieser App": was eintrifft,
     * während die Liste offen ist, hat der Nutzer nicht gesehen und muss bleiben.
     */
    public synchronized void delete(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Long id : ids) {
                db.delete("notification_buffer", "id = ?", new String[]{String.valueOf(id)});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Lässt verfallen, was älter als {@link #UNKNOWN_TTL_MS} ist und von einer App stammt, für die es
     * keine Vorlage gibt. Meldungen bekannter Apps bleiben bis zum nächsten Durchlauf.
     */
    public synchronized int expire(long now, Set<String> knownPackages) {
        int removed = 0;
        List<Long> old = new ArrayList<>();
        for (Entry e : all()) {
            if (e.postTime < now - UNKNOWN_TTL_MS && !knownPackages.contains(e.packageName)) {
                old.add(e.id);
                removed++;
            }
        }
        delete(old);
        return removed;
    }

    /** Löscht alles, was von dieser App aufgehoben wurde — wenn der Nutzer sie abwählt. */
    public synchronized void deletePackage(String packageName) {
        getWritableDatabase().delete("notification_buffer", "package_name = ?",
                new String[]{packageName});
    }

    public synchronized void clear() {
        getWritableDatabase().delete("notification_buffer", null, null);
    }
}
