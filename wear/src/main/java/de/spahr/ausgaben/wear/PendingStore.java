package de.spahr.ausgaben.wear;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Lokale Warteschlange der noch nicht bestätigten Ausgaben – bewusst einfach über SharedPreferences + JSON
 * (kein Room). Es werden nur PENDING-Einträge gespeichert; ein bestätigter Eintrag wird entfernt.
 */
public class PendingStore {

    private static final String PREFS = "wear_pending";
    private static final String KEY_ENTRIES = "entries";

    private final SharedPreferences prefs;

    public PendingStore(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized List<PendingEntry> getPending() {
        List<PendingEntry> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY_ENTRIES, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                out.add(PendingEntry.fromJson(arr.getJSONObject(i)));
            }
        } catch (JSONException e) {
            // defekter Inhalt → als leer behandeln
        }
        return out;
    }

    public synchronized void add(PendingEntry entry) {
        List<PendingEntry> entries = getPending();
        entries.add(entry);
        save(entries);
    }

    /** Setzt Koordinaten und Sendezeitpunkt eines Eintrags (nach der GPS-Auflösung). */
    public synchronized void updateGps(String id, String gps, long readyAt) {
        List<PendingEntry> entries = getPending();
        List<PendingEntry> out = new ArrayList<>();
        for (PendingEntry e : entries) {
            if (e.id.equals(id)) {
                out.add(new PendingEntry(e.id, e.text, e.type, gps, e.account, e.place,
                        e.timestamp, readyAt, e.noPayee));
            } else {
                out.add(e);
            }
        }
        save(out);
    }

    /**
     * Setzt den Buchungstext neu – auf der Bestätigungsseite des Zahlenblocks, wenn der Empfänger
     * durchgeschaltet wird. Der Eintrag liegt da schon, damit ein Absturz in den zehn Sekunden den
     * getippten Betrag nicht verschluckt.
     *
     * @param noPayee ausdrücklich „ohne Empfänger" gewählt (siehe {@link PendingEntry#noPayee})
     */
    public synchronized void updateText(String id, String text, boolean noPayee) {
        List<PendingEntry> entries = getPending();
        List<PendingEntry> out = new ArrayList<>();
        for (PendingEntry e : entries) {
            if (e.id.equals(id)) {
                out.add(new PendingEntry(e.id, text, e.type, e.gps, e.account, e.place,
                        e.timestamp, e.readyAt, noPayee));
            } else {
                out.add(e);
            }
        }
        save(out);
    }

    public synchronized void remove(String id) {
        List<PendingEntry> entries = getPending();
        List<PendingEntry> kept = new ArrayList<>();
        for (PendingEntry e : entries) {
            if (!e.id.equals(id)) {
                kept.add(e);
            }
        }
        save(kept);
    }

    public synchronized int count() {
        return getPending().size();
    }

    private void save(List<PendingEntry> entries) {
        JSONArray arr = new JSONArray();
        try {
            for (PendingEntry e : entries) {
                arr.put(new JSONObject(e.toJson()));
            }
        } catch (JSONException ex) {
            return;
        }
        prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply();
    }
}
