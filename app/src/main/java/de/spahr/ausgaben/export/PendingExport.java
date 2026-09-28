package de.spahr.ausgaben.export;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Vermerkt vor einem Schreibversuch, welche Buchungen mit welcher Signatur (Konto, Betrag, Datum)
 * gleich in die .kmy-Datei geschrieben werden sollen, und löscht den Vermerk wieder, sobald sie lokal
 * als exportiert markiert sind.
 *
 * <p>Grund: Zwischen dem erfolgreichen Schreiben auf den Server ({@code SafeReplace.replace}) und dem
 * lokalen Markieren ({@code BookingDao.markExported}) liegt in {@code KmyExportCoordinator} ein
 * kleines Zeitfenster. Stirbt der Prozess genau dort, bliebe die Buchung lokal „offen" und würde beim
 * nächsten Export ein zweites Mal als neue Transaktion geschrieben – ein Duplikat, das der bloße
 * Zählvergleich in {@code KmyExportCheck} nicht erkennt. Ein Export-Lauf prüft deshalb zuerst, ob ein
 * stehengebliebener Vermerk aus einem vorherigen, abgebrochenen Lauf vorliegt, und gleicht ihn gegen
 * die frisch heruntergeladene Datei ab ({@link KmyExporter#transactionExists}), bevor er irgendetwas
 * Neues schreibt.</p>
 *
 * <p>Die Signatur wird zum <b>Schreibzeitpunkt</b> gespeichert, nicht erst beim nächsten Lauf aus der
 * Buchung neu abgeleitet – sonst würde eine zwischenzeitliche Bearbeitung der Buchung die
 * Wiedererkennung unterlaufen.</p>
 */
final class PendingExport {

    private PendingExport() {
    }

    /** Eine vermerkte Buchung mit der Signatur, unter der sie geschrieben wurde bzw. wird. */
    static final class Entry {
        final long bookingId;
        final String account;
        final long signedCents;
        final long createdAt;

        Entry(long bookingId, String account, long signedCents, long createdAt) {
            this.bookingId = bookingId;
            this.account = account;
            this.signedCents = signedCents;
            this.createdAt = createdAt;
        }
    }

    static List<Entry> read(SettingsStore settings) {
        List<Entry> result = new ArrayList<>();
        String raw = settings.getPendingExportRaw();
        if (raw == null || raw.trim().isEmpty()) {
            return result;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                result.add(new Entry(o.getLong("id"), o.getString("account"), o.getLong("cents"),
                        o.getLong("created")));
            }
        } catch (JSONException e) {
            // Beschädigter/fremder Inhalt: lieber so tun, als gäbe es keinen Vermerk, als abzustürzen.
            return new ArrayList<>();
        }
        return result;
    }

    /** Speichert synchron ({@code commit()}): der Vermerk muss stehen, bevor gleich geschrieben wird. */
    static void write(SettingsStore settings, List<Entry> entries) {
        JSONArray arr = new JSONArray();
        try {
            for (Entry e : entries) {
                JSONObject o = new JSONObject();
                o.put("id", e.bookingId);
                o.put("account", e.account);
                o.put("cents", e.signedCents);
                o.put("created", e.createdAt);
                arr.put(o);
            }
        } catch (JSONException e) {
            // "put" auf JSONObject/JSONArray wirft hier praktisch nie – falls doch, lieber ohne
            // Vermerk weiterschreiben, als den Export daran scheitern zu lassen.
            return;
        }
        settings.setPendingExportRaw(arr.toString());
    }

    static void clear(SettingsStore settings) {
        settings.clearPendingExportRaw();
    }
}
