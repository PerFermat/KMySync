package de.spahr.ausgaben.db;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import de.spahr.ausgaben.util.FxTable;

/**
 * Umrechnung von Fremdwährungskonten für Summen über mehrere Konten. Ein USD-Konto bleibt in der
 * Kontoansicht in Dollar; in „Gesamt", der Gruppensumme und dem Vermögensverlauf zählt es mit seinem
 * Saldo × Devisenkurs in der Basiswährung der KMyMoney-Datei – wie KMyMoney sein Vermögen bewertet.
 *
 * <p>Ohne Basiswährung oder ohne passenden Devisenkurs zählt das Konto wie bisher mit seinem
 * unveränderten Saldo: lieber eine ungenaue Summe als eine, in der ein Konto fehlt.</p>
 */
public final class CurrencyContext {

    public final String base;
    /** Kontoname (klein) → Währung. */
    private final Map<String, String> currencies;
    private final FxTable fx;

    public CurrencyContext(String base, Map<String, String> currencies, FxTable fx) {
        this.base = base == null ? "" : base.trim();
        this.currencies = currencies == null ? Collections.emptyMap() : currencies;
        this.fx = fx == null ? new FxTable() : fx;
    }

    /** Konto in einer anderen Währung als der Basiswährung, für das ein Devisenkurs vorliegt? */
    public boolean isForeign(String account) {
        String c = currencyOf(account);
        return !base.isEmpty() && !c.isEmpty() && !c.equalsIgnoreCase(base)
                && !Double.isNaN(fx.rate(c, base, System.currentTimeMillis()));
    }

    private String currencyOf(String account) {
        String c = account == null ? null : currencies.get(account.toLowerCase(Locale.ROOT));
        return c == null ? "" : c.trim();
    }

    /** Salden der Fremdwährungskonten aus diesen Buchungen, bewertbar zu jedem Stichtag. */
    public ForeignBalances foreignBalances(List<Booking> bookings) {
        return new ForeignBalances(bookings);
    }

    /** Saldenverlauf je Fremdwährungskonto; {@link #valueCentsAt} bewertet ihn in Basiswährung. */
    public final class ForeignBalances {

        /** Konto (klein) → {Datum[], laufender Saldo[]}, zeitlich aufsteigend. */
        private final Map<String, long[][]> series = new HashMap<>();
        private final Map<String, String> currencyByAccount = new HashMap<>();

        ForeignBalances(List<Booking> bookings) {
            Map<String, List<long[]>> flows = new HashMap<>();
            for (Booking b : bookings) {
                if (!isForeign(b.account)) {
                    continue;
                }
                String key = b.account.toLowerCase(Locale.ROOT);
                currencyByAccount.put(key, currencyOf(b.account));
                flows.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new long[]{b.createdAt, b.isIncome ? b.amountCents : -b.amountCents});
            }
            for (Map.Entry<String, List<long[]>> e : flows.entrySet()) {
                List<long[]> l = e.getValue();
                l.sort((a, c) -> Long.compare(a[0], c[0]));
                long[] dates = new long[l.size()];
                long[] running = new long[l.size()];
                long sum = 0;
                for (int i = 0; i < l.size(); i++) {
                    sum += l.get(i)[1];
                    dates[i] = l.get(i)[0];
                    running[i] = sum;
                }
                series.put(e.getKey(), new long[][]{dates, running});
            }
        }

        public boolean isEmpty() {
            return series.isEmpty();
        }

        /** Früheste Buchung eines Fremdwährungskontos (ms); {@code Long.MAX_VALUE} ohne. */
        public long firstMs() {
            long min = Long.MAX_VALUE;
            for (long[][] s : series.values()) {
                if (s[0].length > 0) {
                    min = Math.min(min, s[0][0]);
                }
            }
            return min;
        }

        /** Gehört das Konto dazu? Schnell – für den Durchlauf über alle Buchungen. */
        public boolean contains(String account) {
            return account != null && series.containsKey(account.toLowerCase(Locale.ROOT));
        }

        /**
         * Summe der Fremdwährungskonten, die {@code accountFilter} (Kontoname klein) annimmt: Saldo am
         * Stichtag × Devisenkurs dieses Tages, in Cent der Basiswährung.
         */
        public long valueCentsAt(long t, Predicate<String> accountFilter) {
            double sum = 0;
            for (Map.Entry<String, long[][]> e : series.entrySet()) {
                if (accountFilter != null && !accountFilter.test(e.getKey())) {
                    continue;
                }
                long balance = balanceAt(e.getValue(), t);
                if (balance != 0) {
                    sum += balance * fx.rate(currencyByAccount.get(e.getKey()), base, t);
                }
            }
            return Math.round(sum);
        }

        private long balanceAt(long[][] s, long t) {
            int lo = 0;
            int hi = s[0].length - 1;
            int found = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (s[0][mid] <= t) {
                    found = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return found < 0 ? 0 : s[1][found];
        }
    }
}
