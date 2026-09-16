package de.spahr.ausgaben.wear;

import android.content.Context;

import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.spahr.ausgaben.db.AppDatabase;
import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.PayeeCorrection;
import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Veröffentlicht die Empfänger samt ihren Standorten als Data-Layer-DataItem.
 *
 * <p>Damit kann die Uhr die Umkreisliste <b>ohne Handy</b> aufbauen – unterwegs ist es oft nicht
 * dabei, und ein Abruf fiele dann aus. Bisher fand den Empfänger erst das Handy, wenn der Eintrag
 * eintraf ({@code Repository.createVoiceBookingBlocking} → {@code resolveGps}); jetzt sieht man auf
 * der Uhr, wer gemeint ist, und kann weiterschalten.</p>
 *
 * <p>Eine Zeile je Empfänger: {@code name + SEP + stufe + SEP + konto + SEP + "lat,lon;lat,lon"}.
 * Die Stufen bilden die Rangordnung von {@code AliasResolver.nearbyCandidates} ab: bevorzugte
 * Aliase (0), Buchungen (1), übrige Aliase (2). Batterie-neutral, weil der Data Layer unveränderte
 * DataItems nicht erneut überträgt.</p>
 */
public final class PayeeSync {

    private static final String TAG = "AusgabenPayeeSync";
    static final String PATH = "/payees";
    /** Trennzeichen innerhalb einer Zeile (identisch zu {@code PayeeStore.SEP} auf der Uhr). */
    private static final char SEP = '\u001F';

    /**
     * Obergrenzen gegen die 100-KB-Schranke eines DataItems. Eine Zeile wiegt rund 50 Byte, also
     * bleiben 300 Empfänger mit je vier Punkten bei etwa 15 KB – reichlich Luft. Fünf
     * Nachkommastellen sind gut einen Meter genau und damit für einen 100-m-Umkreis mehr als genug.
     */
    private static final int MAX_PAYEES = 300;
    private static final int MAX_POINTS = 4;

    private PayeeSync() {
    }

    public static void publish(Context context) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                String list = buildList(app);
                android.util.Log.d(TAG, "sende " + (list.isEmpty() ? 0 : list.split("\n").length)
                        + " Empfänger (" + list.length() + " Zeichen)");
                PutDataMapRequest req = PutDataMapRequest.create(PATH);
                req.getDataMap().putString("list", list);
                // Das Währungszeichen reist mit, damit die Uhr den Satz so bauen kann, wie man ihn
                // sprechen würde („Edeka 12,50 €"). Ohne Währungswort nähme der Auswerter die letzte
                // Zahl im Satz – bei einem Namen wie „Aral 24" wäre das die falsche.
                req.getDataMap().putString("currency", new SettingsStore(app).getCurrency());
                Wearable.getDataClient(app).putDataItem(req.asPutDataRequest().setUrgent());
            } catch (Exception e) {
                // Nicht still schlucken: Bleibt die Liste aus, steht die Uhr ohne Vorschläge da und
                // man sucht den Fehler auf der falschen Seite.
                android.util.Log.w(TAG, "Empfänger konnten nicht gesendet werden", e);
            }
        }).start();
    }

    /** Die Zeilen in der Rangordnung des Handys; je Empfänger bleibt das beste Auftreten. */
    private static String buildList(Context app) {
        // Ist der Standort in den Einstellungen abgeschaltet, geht gar nichts an die Uhr. Das Handy
        // verwirft dann auch die Koordinaten einer Uhr-Buchung und blendet die eigene Ziffernmaske
        // aus – dann darf die Uhr nicht als Einzige weiter nach Standort vorschlagen. Die leere
        // Liste räumt außerdem auf, was vor dem Abschalten schon drüben lag.
        if (!new SettingsStore(app).isGpsEnabled()) {
            return "";
        }
        AppDatabase db = AppDatabase.getInstance(app);
        Map<String, String[]> gefunden = new LinkedHashMap<>();   // key = Name klein → Zeilenfelder
        for (PayeeCorrection a : db.payeeCorrectionDao().getWithGps(1)) {
            merke(gefunden, a.corrected, 0, a.account, a.gpsPoints());
        }
        for (Booking b : db.bookingDao().getWithGpsNote()) {
            double[] ll = de.spahr.ausgaben.location.Geo.parse(b.note);
            if (ll != null) {
                merke(gefunden, b.payee, 1, b.account, java.util.Collections.singletonList(ll));
            }
        }
        for (PayeeCorrection a : db.payeeCorrectionDao().getWithGps(0)) {
            merke(gefunden, a.corrected, 2, a.account, a.gpsPoints());
        }

        StringBuilder out = new StringBuilder();
        int n = 0;
        for (String[] f : gefunden.values()) {
            if (n++ >= MAX_PAYEES) {
                break;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(f[0]).append(SEP).append(f[1]).append(SEP).append(f[2]).append(SEP).append(f[3]);
        }
        return out.toString();
    }

    /**
     * Nimmt den Empfänger auf, sofern er nicht schon aus einer besseren Stufe dabei ist. Steht er
     * schon, kommen nur seine Punkte dazu – derselbe Laden zählt an jedem bekannten Standort.
     */
    private static void merke(Map<String, String[]> gefunden, String name, int tier, String account,
                              List<double[]> points) {
        if (name == null || name.trim().isEmpty() || points == null || points.isEmpty()) {
            return;
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        String[] vorhanden = gefunden.get(key);
        if (vorhanden == null) {
            gefunden.put(key, new String[]{name.trim(), String.valueOf(tier),
                    account == null ? "" : account.trim(), joinPoints(points, new ArrayList<>())});
            return;
        }
        vorhanden[3] = joinPoints(points, split(vorhanden[3]));
    }

    private static List<String> split(String joined) {
        List<String> out = new ArrayList<>();
        for (String p : joined.split(";")) {
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    /** Punkte anhängen, entdoppelt und auf {@link #MAX_POINTS} begrenzt. */
    private static String joinPoints(List<double[]> points, List<String> vorhanden) {
        for (double[] p : points) {
            if (vorhanden.size() >= MAX_POINTS) {
                break;
            }
            String s = String.format(Locale.US, "%.5f,%.5f", p[0], p[1]);
            if (!vorhanden.contains(s)) {
                vorhanden.add(s);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String s : vorhanden) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(s);
        }
        return sb.toString();
    }
}
