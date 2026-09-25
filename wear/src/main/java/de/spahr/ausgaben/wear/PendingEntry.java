package de.spahr.ausgaben.wear;

import org.json.JSONException;
import org.json.JSONObject;

/** Eine noch nicht (bestätigt) übertragene Sprach-Buchung auf der Uhr. */
public class PendingEntry {

    public final String id;
    public final String text;
    /** Buchungstyp: {@link WearPaths#TYPE_INCOME}/{@code TYPE_EXPENSE}/{@code TYPE_TRANSFER}. */
    public final String type;
    /** Zum Sprechzeitpunkt bestimmte Koordinaten „lat, lon" (leer = keine). */
    public final String gps;
    /** Zielkonto (per Wechsel-Knopf gewählt); leer = Standardkonto des Phones. Bei Umbuchung das Von-Konto. */
    public final String account;
    /** Gewählter Ort des Kontos (leer = Standardort). */
    public final String place;
    public final long timestamp;
    /** Frühester Sendezeitpunkt (lokal): erlaubt das 10-Sekunden-Abbrechen vor der Übertragung. */
    public final long readyAt;
    /**
     * Auf der Uhr ausdrücklich „ohne Empfänger" gewählt. Der Text ist dann derselbe reine Betrag wie
     * ohne Kandidaten in der Nähe – nur dieses Merkmal hält das Handy davon ab, selbst im Umkreis zu
     * suchen.
     */
    public final boolean noPayee;

    public PendingEntry(String id, String text, String type, String gps, String account, String place,
                        long timestamp, long readyAt, boolean noPayee) {
        this.id = id;
        this.text = text;
        this.type = type;
        this.gps = gps == null ? "" : gps;
        this.account = account == null ? "" : account;
        this.place = place == null ? "" : place;
        this.timestamp = timestamp;
        this.readyAt = readyAt;
        this.noPayee = noPayee;
    }

    /** JSON: {"id","text","type","gps","account","place","timestamp","readyAt","noPayee"}. */
    public String toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("text", text);
        o.put("type", type);
        o.put("gps", gps);
        o.put("account", account);
        o.put("place", place);
        o.put("timestamp", timestamp);
        o.put("readyAt", readyAt);
        o.put("noPayee", noPayee);
        return o.toString();
    }

    static PendingEntry fromJson(JSONObject o) {
        long ts = o.optLong("timestamp", 0);
        return new PendingEntry(
                o.optString("id", ""),
                o.optString("text", ""),
                o.optString("type", WearPaths.TYPE_EXPENSE),
                o.optString("gps", ""),
                o.optString("account", ""),
                o.optString("place", ""),
                ts,
                o.optLong("readyAt", ts),
                o.optBoolean("noPayee", false));
    }
}
