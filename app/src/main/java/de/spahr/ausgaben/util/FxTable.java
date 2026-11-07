package de.spahr.ausgaben.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Devisenkurse aus KMyMoney ({@code PRICEPAIR} zwischen zwei Währungen), je Paar als Historie.
 * {@link #rate} liefert den Kurs eines Tages – auch über das Gegenpaar, denn KMyMoney legt je nach
 * Eingabe {@code EUR→USD} oder {@code USD→EUR} ab.
 */
public final class FxTable {

    /** von-Währung → nach-Währung → Historie {kurs, dateMillis}. */
    private final Map<String, Map<String, List<double[]>>> pairs = new LinkedHashMap<>();
    private boolean sorted = true;

    public void add(String from, String to, double rate, long date) {
        if (from == null || to == null || from.isEmpty() || to.isEmpty() || !(rate > 0)) {
            return;
        }
        pairs.computeIfAbsent(from, k -> new LinkedHashMap<>())
                .computeIfAbsent(to, k -> new ArrayList<>())
                .add(new double[]{rate, date});
        sorted = false;
    }

    public boolean isEmpty() {
        return pairs.isEmpty();
    }

    /**
     * Wie viele Einheiten {@code to} eine Einheit {@code from} am Tag {@code date} kostet. Gilt der
     * jüngste Kurs bis zu diesem Tag; liegt keiner davor, der älteste. Ist nur das Gegenpaar gepflegt,
     * sein Kehrwert. Gleiche Währung = 1; ohne passenden Kurs {@code NaN}.
     */
    public double rate(String from, String to, long date) {
        if (from == null || to == null || from.isEmpty() || to.isEmpty()) {
            return Double.NaN;
        }
        if (from.equalsIgnoreCase(to)) {
            return 1;
        }
        sort();
        double direct = valueAt(pair(from, to), date);
        if (!Double.isNaN(direct)) {
            return direct;
        }
        double inverse = valueAt(pair(to, from), date);
        return Double.isNaN(inverse) ? Double.NaN : 1 / inverse;
    }

    /** Alle Einträge als {von, nach, kurs, datum} – zum Ablegen in der Datenbank. */
    public List<Object[]> entries() {
        sort();
        List<Object[]> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<double[]>>> f : pairs.entrySet()) {
            for (Map.Entry<String, List<double[]>> t : f.getValue().entrySet()) {
                for (double[] ph : t.getValue()) {
                    out.add(new Object[]{f.getKey(), t.getKey(), ph[0], (long) ph[1]});
                }
            }
        }
        return out;
    }

    private List<double[]> pair(String from, String to) {
        for (Map.Entry<String, Map<String, List<double[]>>> f : pairs.entrySet()) {
            if (f.getKey().equalsIgnoreCase(from)) {
                for (Map.Entry<String, List<double[]>> t : f.getValue().entrySet()) {
                    if (t.getKey().equalsIgnoreCase(to)) {
                        return t.getValue();
                    }
                }
            }
        }
        return null;
    }

    private void sort() {
        if (sorted) {
            return;
        }
        for (Map<String, List<double[]>> byTo : pairs.values()) {
            for (List<double[]> h : byTo.values()) {
                h.sort((a, b) -> Long.compare((long) a[1], (long) b[1]));
            }
        }
        sorted = true;
    }

    private static double valueAt(List<double[]> history, long date) {
        if (history == null || history.isEmpty()) {
            return Double.NaN;
        }
        double v = history.get(0)[0];
        for (double[] ph : history) {
            if ((long) ph[1] > date) {
                break;
            }
            v = ph[0];
        }
        return v;
    }
}
