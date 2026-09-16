package de.spahr.ausgaben.receipt;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Merkt sich einen begonnenen Beleg-Export, damit er nach einem Abbruch wieder aufgenommen werden kann.
 *
 * <p>Der Export schreibt unmittelbar in die gewählte Datei. Räumt Android den Prozess mittendrin ab,
 * bleibt ein unbrauchbarer Torso zurück, und der Nutzer erfährt davon nichts – die App startet beim
 * nächsten Mal, als wäre nie etwas gewesen. Deshalb steht hier ab dem Start des Laufs, was zu tun war;
 * gelöscht wird es erst, wenn nichts mehr offen ist.</p>
 *
 * <p>Gemerkt werden nicht die Belege selbst, sondern die <b>Buchungsnummern</b>. Daraus lässt sich die
 * Liste mit {@link ReceiptExportJobs#collect} neu bilden – dieselbe Rechnung wie beim ersten Mal, und
 * unempfindlich dagegen, dass sich an einer Buchung inzwischen etwas geändert hat.</p>
 */
public final class ReceiptExportResume {

    private static final String PREFS = "receipt_export";
    private static final String KEY_URI = "uri";
    private static final String KEY_NAME = "name";
    private static final String KEY_DOWNLOAD = "download";
    private static final String KEY_IDS = "ids";
    private static final String KEY_STARTED = "started";

    /**
     * Läuft in diesem Prozess gerade ein Export? Dann ist der Merker kein Hinweis auf einen Abbruch,
     * sondern schlicht aktuell – gefragt wird dann nicht.
     */
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    /** Verhindert, dass die Rückfrage bei jedem {@code onResume} erneut aufpoppt. */
    private static final AtomicBoolean ASKED = new AtomicBoolean();

    /** Ein gemerkter Lauf. */
    public static final class Pending {
        public final String uri;
        public final String name;
        public final boolean allowDownload;
        public final List<Long> bookingIds;
        public final long startedAt;

        Pending(String uri, String name, boolean allowDownload, List<Long> bookingIds,
                long startedAt) {
            this.uri = uri;
            this.name = name;
            this.allowDownload = allowDownload;
            this.bookingIds = bookingIds;
            this.startedAt = startedAt;
        }
    }

    private ReceiptExportResume() {
    }

    /** Merkt den beginnenden Lauf. Wird <b>vor</b> dem Start des Hintergrund-Threads gerufen. */
    public static void start(Context ctx, String uri, String name, boolean allowDownload,
                             List<Long> bookingIds) {
        RUNNING.set(true);
        prefs(ctx).edit()
                .putString(KEY_URI, uri == null ? "" : uri)
                .putString(KEY_NAME, name == null ? "" : name)
                .putBoolean(KEY_DOWNLOAD, allowDownload)
                .putString(KEY_IDS, joinIds(bookingIds))
                .putLong(KEY_STARTED, System.currentTimeMillis())
                .apply();
    }

    /** Der Lauf ist zu Ende – ob erfolgreich oder nicht, entscheidet der Aufrufer. */
    public static void finished(Context ctx, boolean keepForRetry) {
        RUNNING.set(false);
        if (!keepForRetry) {
            clear(ctx);
        }
    }

    /** Merker verwerfen (Nutzer will nicht mehr, oder er ist gegenstandslos geworden). */
    public static void clear(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }

    /**
     * Der gemerkte Lauf, falls es einen gibt und in diesem Prozess gerade keiner läuft; sonst
     * {@code null}. Liefert höchstens <b>einmal je App-Start</b> etwas – danach wäre die Rückfrage
     * bei jeder Rückkehr auf die Hauptseite eine Belästigung.
     */
    public static Pending askOncePerStart(Context ctx) {
        if (RUNNING.get() || !ASKED.compareAndSet(false, true)) {
            return null;
        }
        SharedPreferences p = prefs(ctx);
        String uri = p.getString(KEY_URI, "");
        if (uri.isEmpty()) {
            return null;
        }
        return new Pending(uri, p.getString(KEY_NAME, ""), p.getBoolean(KEY_DOWNLOAD, true),
                splitIds(p.getString(KEY_IDS, "")), p.getLong(KEY_STARTED, 0L));
    }

    /** Buchungsnummern als Komma-Liste – klein genug für die Einstellungen, auch bei Hunderten. */
    public static String joinIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id.longValue());
        }
        return sb.toString();
    }

    /** Gegenstück zu {@link #joinIds}; unbrauchbare Teile werden übergangen. */
    public static List<Long> splitIds(String value) {
        List<Long> out = new ArrayList<>();
        if (value == null || value.isEmpty()) {
            return out;
        }
        for (String teil : value.split(",")) {
            try {
                out.add(Long.parseLong(teil.trim()));
            } catch (NumberFormatException ignored) {
                // durchgerutschter Unsinn – der eine Beleg fehlt dann eben
            }
        }
        return out;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
