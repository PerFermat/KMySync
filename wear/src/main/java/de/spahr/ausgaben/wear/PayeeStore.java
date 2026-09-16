package de.spahr.ausgaben.wear;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Hält die vom Phone übertragenen Empfänger mit ihren Standorten, damit die Uhr die Umkreisliste auch
 * <b>ohne Handy</b> aufbauen kann – unterwegs ist es oft nicht dabei.
 *
 * <p>Eine Zeile je Empfänger: {@code name + SEP + stufe + SEP + konto + SEP + "lat,lon;lat,lon"}.
 * Die Stufe (0/1/2) ist die Rangordnung des Handys – bevorzugter Alias, Buchung, übriger Alias –,
 * damit die Uhr dieselbe Reihenfolge zeigt, ohne Aliase zu verstehen.</p>
 */
public final class PayeeStore {

    private static final String PREFS = "wear_payees";
    private static final String KEY_LIST = "list";
    private static final String KEY_CURRENCY = "currency";
    /** Trennzeichen innerhalb einer Zeile (Unit Separator) – identisch zum Phone. */
    static final char SEP = '\u001F';

    /** Umkreis, in dem ein Empfänger als „hier" gilt – derselbe Wert wie am Handy. */
    static final double RADIUS_M = 100.0;

    private PayeeStore() {
    }

    /** Ein Empfänger mit seinen bekannten Standorten. */
    static final class Entry {
        final String name;
        final int tier;
        final String account;
        final List<double[]> points;

        Entry(String name, int tier, String account, List<double[]> points) {
            this.name = name;
            this.tier = tier;
            this.account = account;
            this.points = points;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void save(Context context, String list, String currency) {
        prefs(context).edit()
                .putString(KEY_LIST, list == null ? "" : list)
                .putString(KEY_CURRENCY, currency == null ? "" : currency)
                .apply();
    }

    /** Währungszeichen des Profils; leer, wenn das Phone noch keines geschickt hat. */
    public static String currency(Context context) {
        return prefs(context).getString(KEY_CURRENCY, "");
    }

    /**
     * Die Empfänger im 100-m-Umkreis, der nächstgelegene zuerst.
     *
     * @param account gewähltes Konto der Uhr (leer = nicht einschränken)
     */
    public static List<String> nearby(Context context, double lat, double lon, String account) {
        return rank(parse(prefs(context).getString(KEY_LIST, "")), lat, lon, account);
    }

    /**
     * Zerlegt die übertragenen Zeilen. Eine unbrauchbare Zeile wird übergangen und verdirbt nicht die
     * ganze Liste – sonst stünde die Uhr wegen eines einzigen krummen Namens ohne Vorschläge da.
     */
    static List<Entry> parse(String raw) {
        List<Entry> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String line : raw.split("\n")) {
            String[] f = line.split(String.valueOf(SEP), -1);
            if (f.length < 4 || f[0].trim().isEmpty()) {
                continue;
            }
            int tier;
            try {
                tier = Integer.parseInt(f[1].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            List<double[]> points = points(f[3]);
            if (points.isEmpty()) {
                continue;   // ohne Standort ist der Empfänger für die Umkreisliste wertlos
            }
            out.add(new Entry(f[0].trim(), tier, f[2].trim(), points));
        }
        return out;
    }

    private static List<double[]> points(String raw) {
        List<double[]> out = new ArrayList<>();
        for (String part : raw.split(";")) {
            int comma = part.indexOf(',');
            if (comma <= 0) {
                continue;
            }
            try {
                out.add(new double[]{
                        Double.parseDouble(part.substring(0, comma).trim()),
                        Double.parseDouble(part.substring(comma + 1).trim())});
            } catch (NumberFormatException ignored) {
                // einzelner krummer Punkt – die übrigen des Empfängers zählen weiter
            }
        }
        return out;
    }

    /**
     * Die Namen im Umkreis in der Reihenfolge des Handys: erst die Stufe, innerhalb der Stufe der
     * nächstgelegene. Jeder Name kommt nur einmal vor, mit seinem besten Auftreten.
     */
    static List<String> rank(List<Entry> entries, double lat, double lon, String account) {
        List<Object[]> passend = new ArrayList<>();   // [tier, distanz, name]
        for (Entry e : entries) {
            if (account != null && !account.isEmpty() && !e.account.isEmpty()
                    && !e.account.equalsIgnoreCase(account)) {
                continue;
            }
            double best = Double.MAX_VALUE;
            for (double[] p : e.points) {
                best = Math.min(best, WearGeo.distanceMeters(lat, lon, p[0], p[1]));
            }
            if (best <= RADIUS_M) {
                passend.add(new Object[]{e.tier, best, e.name});
            }
        }
        Collections.sort(passend, (a, b) -> {
            int t = Integer.compare((Integer) a[0], (Integer) b[0]);
            return t != 0 ? t : Double.compare((Double) a[1], (Double) b[1]);
        });
        Set<String> gesehen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (Object[] p : passend) {
            String name = (String) p[2];
            if (gesehen.add(name.toLowerCase(Locale.ROOT))) {
                out.add(name);
            }
        }
        return out;
    }
}
