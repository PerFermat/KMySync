package de.spahr.ausgaben.ui;

import android.content.res.Configuration;
import android.os.Bundle;
import android.view.MotionEvent;
import android.widget.TextView;

import com.github.mikephil.charting.charts.CombinedChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.CombinedData;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.github.mikephil.charting.listener.ChartTouchListener;
import com.github.mikephil.charting.listener.OnChartGestureListener;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.db.ScheduleProjection;
import de.spahr.ausgaben.db.ScheduledTransaction;
import de.spahr.ausgaben.settings.Currencies;
import de.spahr.ausgaben.settings.MoneyFormat;
import de.spahr.ausgaben.settings.DateFormats;

/**
 * Grafik der geplanten Buchungen – wie die Auswertung der echten Buchungen (grüne/rote Balken je Zeit-Bucket
 * + kumulierte Entwicklungslinie). Der Zeitraum beginnt am aktuellen Tag; die Linie startet dort bei 0 und
 * läuft nach rechts in die Zukunft. Übernimmt den Listenfilter (Name/Buchungsart/Zeitraum); eigene Sicht
 * (Gesamt/Konto) und Granularität (Tag/Woche/Monat).
 */
public class ScheduledChartActivity extends LocalizedActivity {

    public static final String EXTRA_FILTER_NAME = "sched_filter_name";
    /** Bitmaske: 1 = Einzahlung, 2 = Auszahlung, 4 = Umbuchung (0 → alle). */
    public static final String EXTRA_FILTER_KINDS = "sched_filter_kinds";
    public static final String EXTRA_FILTER_DATE_FROM = "sched_filter_date_from";
    public static final String EXTRA_FILTER_DATE_TO = "sched_filter_date_to";

    private static final int FORWARD_MONTHS = 24;
    private static final int MAX_PER_SCHEDULE = 120;
    private static final int DEFAULT_BARS = 12;

    private enum Granularity {DAY, WEEK, MONTH}

    private Repository repository;
    private CombinedChart chart;
    private TextView textTotal;
    private FloatingActionButton fabScrollLeft;
    private MaterialAutoCompleteTextView viewSelector;

    private List<ScheduledTransaction> all = new ArrayList<>();
    private boolean loaded = false;
    private Set<String> activeAccounts = null;

    private Granularity granularity = Granularity.MONTH;

    private String filterName = "";
    private int filterKinds = 0;   // 0 = alle
    private Long filterDateFrom = null;
    private Long filterDateTo = null;

    private final List<String> viewKeys = new ArrayList<>();
    private final List<String> viewLabels = new ArrayList<>();
    private String viewKey = MainActivity.VIEW_TOTAL;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scheduled_chart);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        filterName = orEmpty(getIntent().getStringExtra(EXTRA_FILTER_NAME));
        filterKinds = getIntent().getIntExtra(EXTRA_FILTER_KINDS, 0);
        long dFrom = getIntent().getLongExtra(EXTRA_FILTER_DATE_FROM, Long.MIN_VALUE);
        long dTo = getIntent().getLongExtra(EXTRA_FILTER_DATE_TO, Long.MAX_VALUE);
        filterDateFrom = dFrom == Long.MIN_VALUE ? null : dFrom;
        filterDateTo = dTo == Long.MAX_VALUE ? null : dTo;

        repository = new Repository(this);
        chart = findViewById(R.id.barChart);
        // Ohne Daten zeichnet MPAndroidChart sonst sein englisches „No chart data available."
        chart.setNoDataText(getString(R.string.scheduled_chart_empty));
        chart.setNoDataTextColor(getColor(R.color.grey_text));
        textTotal = findViewById(R.id.textTotal);
        fabScrollLeft = findViewById(R.id.fabScrollLeft);
        viewSelector = findViewById(R.id.viewSelector);

        setupChart();

        MaterialButtonToggleGroup toggle = findViewById(R.id.toggleGranularity);
        toggle.check(R.id.btnMonth);
        toggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            if (checkedId == R.id.btnDay) {
                granularity = Granularity.DAY;
            } else if (checkedId == R.id.btnWeek) {
                granularity = Granularity.WEEK;
            } else {
                granularity = Granularity.MONTH;
            }
            renderChart();
        });

        // „Springen"-Knopf zielt auf den Anfang (heute) links.
        fabScrollLeft.setOnClickListener(v -> {
            chart.moveViewToX(0f);
            updateScrollLeftVisibility();
        });

        repository.getScheduledTransactions(list -> {
            all = list;
            loaded = true;
            setupViewSelector();
            renderChart();
        });
        repository.getAccountNames(names -> {
            activeAccounts = new HashSet<>(names);
            if (loaded) {
                setupViewSelector();
            }
        });
    }

    private void setupViewSelector() {
        viewKeys.clear();
        viewLabels.clear();
        viewKeys.add(MainActivity.VIEW_TOTAL);
        viewLabels.add(getString(R.string.saldo_total));
        // Konten aus den geplanten Buchungen (nur aktive), stabile Reihenfolge.
        LinkedHashSet<String> accounts = new LinkedHashSet<>();
        for (ScheduledTransaction st : all) {
            addAccount(accounts, st.account);
            if (st.kind == ScheduledTransaction.KIND_TRANSFER) {
                addAccount(accounts, st.counterparty);
            }
        }
        for (String acc : accounts) {
            viewKeys.add(MainActivity.VIEW_ACCOUNT_PREFIX + acc);
            viewLabels.add(acc);
        }
        if (!viewKeys.contains(viewKey)) {
            viewKey = MainActivity.VIEW_TOTAL;
        }
        PickerAdapters.plainSearchable(viewSelector, viewLabels);
        viewSelector.setText(viewLabels.get(viewKeys.indexOf(viewKey)), false);
        // Über die Beschriftung und nicht über den Listenplatz: sobald gesucht wird, stimmt der Platz in
        // der Trefferliste nicht mehr mit dem in viewKeys überein.
        PickerBehaviour.onCommitted(viewSelector, value -> {
            int gewaehlt = viewLabels.indexOf(value);
            if (gewaehlt >= 0) {
                viewKey = viewKeys.get(gewaehlt);
                renderChart();
            }
        });
    }

    private void addAccount(LinkedHashSet<String> set, String acc) {
        if (acc != null && !acc.isEmpty() && activeAccounts != null && activeAccounts.contains(acc)) {
            set.add(acc);
        }
    }

    // ---- Ereignisstrom je Sicht (Zeit, vorzeichenbehaftete Cent) ----

    private List<long[]> eventsForView() {
        long now = System.currentTimeMillis();
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long fromMs = c.getTimeInMillis();
        c.setTimeInMillis(now);
        c.add(Calendar.MONTH, FORWARD_MONTHS);
        long toMs = c.getTimeInMillis();

        String account = viewKey.startsWith(MainActivity.VIEW_ACCOUNT_PREFIX)
                ? viewKey.substring(MainActivity.VIEW_ACCOUNT_PREFIX.length()) : null;

        List<long[]> events = new ArrayList<>();
        for (ScheduledTransaction st : all) {
            if (st.nextDueMs <= 0 || !kindSelected(st.kind) || !nameMatches(st)) {
                continue;
            }
            for (long due : ScheduleProjection.occurrences(st.nextDueMs, st.occurrence,
                    st.occurrenceMultiplier, st.endMs, fromMs, toMs, MAX_PER_SCHEDULE)) {
                if (filterDateFrom != null && due < filterDateFrom) {
                    continue;
                }
                if (filterDateTo != null && due > filterDateTo) {
                    continue;
                }
                addEvents(events, st, due, account);
            }
        }
        Collections.sort(events, Comparator.comparingLong(a -> a[0]));
        return events;
    }

    private void addEvents(List<long[]> events, ScheduledTransaction st, long due, String account) {
        long amt = st.amountCents;
        if (account == null) {   // Gesamt: Umbuchungen saldieren zu 0 → weglassen
            if (st.kind == ScheduledTransaction.KIND_INCOME) {
                events.add(new long[]{due, amt});
            } else if (st.kind == ScheduledTransaction.KIND_EXPENSE) {
                events.add(new long[]{due, -amt});
            }
            return;
        }
        if (st.kind == ScheduledTransaction.KIND_INCOME && account.equalsIgnoreCase(st.account)) {
            events.add(new long[]{due, amt});
        } else if (st.kind == ScheduledTransaction.KIND_EXPENSE && account.equalsIgnoreCase(st.account)) {
            events.add(new long[]{due, -amt});
        } else if (st.kind == ScheduledTransaction.KIND_TRANSFER) {
            // Richtung: incoming = Geld fließt IN st.account (dieses Konto steigt, das Gegenkonto sinkt).
            boolean accountIsDest = st.incoming == 1;
            if (account.equalsIgnoreCase(st.account)) {
                events.add(new long[]{due, accountIsDest ? amt : -amt});
            }
            if (account.equalsIgnoreCase(st.counterparty)) {
                events.add(new long[]{due, accountIsDest ? -amt : amt});
            }
        }
    }

    private boolean kindSelected(int kind) {
        if (filterKinds == 0) {
            return true;
        }
        int bit = kind == ScheduledTransaction.KIND_INCOME ? 1
                : kind == ScheduledTransaction.KIND_EXPENSE ? 2 : 4;
        return (filterKinds & bit) != 0;
    }

    private boolean nameMatches(ScheduledTransaction st) {
        if (filterName.isEmpty()) {
            return true;
        }
        String needle = filterName.toLowerCase(Locale.GERMANY);
        return st.name.toLowerCase(Locale.GERMANY).contains(needle)
                || st.payee.toLowerCase(Locale.GERMANY).contains(needle);
    }

    private void renderChart() {
        List<long[]> events = eventsForView();

        long total = 0;
        for (long[] e : events) {
            total += e[1];
        }
        textTotal.setText(getString(R.string.analysis_total, formatEuro(total)));

        // Fenster beginnt beim aktuellen Tag (Linie startet dort bei 0), auch wenn erst später etwas kommt.
        long minMs = periodStart(System.currentTimeMillis());
        long maxMs = minMs;
        Map<Long, Long> netByPeriod = new HashMap<>();
        Set<Long> hasEvent = new HashSet<>();
        for (long[] e : events) {
            long ps = periodStart(e[0]);
            Long curVal = netByPeriod.get(ps);
            netByPeriod.put(ps, (curVal == null ? 0L : curVal) + e[1]);
            hasEvent.add(ps);
            if (ps > maxMs) {
                maxMs = ps;
            }
        }

        List<Long> periods = new ArrayList<>();
        Calendar cur = Calendar.getInstance();
        cur.setTimeInMillis(minMs);
        while (cur.getTimeInMillis() <= maxMs && periods.size() <= 5000) {
            periods.add(cur.getTimeInMillis());
            advance(cur);
        }

        List<BarEntry> barEntries = new ArrayList<>();
        List<Integer> barColors = new ArrayList<>();
        List<Entry> lineEntries = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        int green = getColor(R.color.income_green);
        int red = getColor(R.color.expense_red);
        long running = 0;
        for (int i = 0; i < periods.size(); i++) {
            long ps = periods.get(i);
            labels.add(label(ps));
            Long net = netByPeriod.get(ps);
            long netVal = net == null ? 0L : net;
            running += netVal;
            if (hasEvent.contains(ps)) {
                barEntries.add(new BarEntry(i, netVal / 100f));
                barColors.add(netVal >= 0 ? green : red);
            }
            lineEntries.add(new Entry(i, running / 100f));
        }

        if (periods.isEmpty()) {
            chart.clear();
            chart.invalidate();
            fabScrollLeft.hide();
            return;
        }

        int chartText = getColor(R.color.chart_text);

        BarDataSet barSet = new BarDataSet(barEntries, "");
        barSet.setColors(barColors);
        barSet.setValueTextColor(chartText);
        barSet.setValueTextSize(11f * de.spahr.ausgaben.settings.FontScale.factor());
        BarData barData = new BarData(barSet);
        barData.setBarWidth(0.6f);

        LineDataSet lineSet = new LineDataSet(lineEntries, "");
        lineSet.setColor(getColor(R.color.chart_line));
        lineSet.setLineWidth(2.2f);
        lineSet.setDrawCircles(false);
        lineSet.setDrawValues(false);
        lineSet.setMode(LineDataSet.Mode.LINEAR);
        LineData lineData = new LineData(lineSet);

        CombinedData combined = new CombinedData();
        combined.setData(barData);
        combined.setData(lineData);
        chart.setData(combined);

        XAxis x = chart.getXAxis();
        x.setValueFormatter(new IndexAxisValueFormatter(labels));
        x.setAxisMinimum(-0.5f);
        x.setAxisMaximum(periods.size() - 0.5f);
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        x.setGranularity(1f);
        x.setGranularityEnabled(true);
        x.setLabelCount(landscape ? 12 : 6, false);

        chart.setVisibleXRangeMinimum(2f);
        chart.setVisibleXRangeMaximum(Math.max(2f, periods.size()));
        chart.fitScreen();
        if (periods.size() > DEFAULT_BARS) {
            chart.zoom((float) periods.size() / DEFAULT_BARS, 1f, 0f, 0f);
        }
        chart.moveViewToX(0f);   // ganz links (heute) starten, nach rechts in die Zukunft
        chart.invalidate();
        updateScrollLeftVisibility();
    }

    private void setupChart() {
        chart.getDescription().setEnabled(false);
        chart.getAxisRight().setEnabled(false);
        chart.getLegend().setEnabled(false);
        chart.setScaleXEnabled(true);
        chart.setScaleYEnabled(true);
        chart.setPinchZoom(false);
        chart.setDoubleTapToZoomEnabled(false);
        chart.setDragEnabled(true);
        chart.setDrawOrder(new CombinedChart.DrawOrder[]{
                CombinedChart.DrawOrder.BAR, CombinedChart.DrawOrder.LINE});

        int chartText = getColor(R.color.chart_text);
        XAxis x = chart.getXAxis();
        x.setPosition(XAxis.XAxisPosition.BOTTOM);
        x.setDrawGridLines(false);
        x.setAvoidFirstLastClipping(true);
        x.setTextColor(chartText);
        x.setTextSize(12f * de.spahr.ausgaben.settings.FontScale.factor());
        chart.getAxisLeft().setTextColor(chartText);
        chart.getAxisLeft().setTextSize(12f * de.spahr.ausgaben.settings.FontScale.factor());

        chart.setOnChartGestureListener(new OnChartGestureListener() {
            @Override public void onChartGestureStart(MotionEvent me, ChartTouchListener.ChartGesture g) { }
            @Override public void onChartGestureEnd(MotionEvent me, ChartTouchListener.ChartGesture g) {
                updateScrollLeftVisibility();
            }
            @Override public void onChartLongPressed(MotionEvent me) { }
            @Override public void onChartDoubleTapped(MotionEvent me) { }
            @Override public void onChartSingleTapped(MotionEvent me) { }
            @Override public void onChartFling(MotionEvent e1, MotionEvent e2, float vx, float vy) { }
            @Override public void onChartScale(MotionEvent me, float sx, float sy) { }
            @Override public void onChartTranslate(MotionEvent me, float dx, float dy) {
                updateScrollLeftVisibility();
            }
        });
    }

    /** „Zurück zum Anfang"-Knopf sichtbar, sobald nach rechts (in die Zukunft) gescrollt wurde. */
    private void updateScrollLeftVisibility() {
        chart.post(() -> {
            if (chart.getData() == null) {
                fabScrollLeft.hide();
                return;
            }
            if (chart.getLowestVisibleX() <= 0.5f) {
                fabScrollLeft.hide();
            } else {
                fabScrollLeft.show();
            }
        });
    }

    // ---- Perioden-Hilfen (wie Auswertung) ----

    private long periodStart(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        switch (granularity) {
            case WEEK:
                c.set(Calendar.DAY_OF_WEEK, c.getFirstDayOfWeek());
                break;
            case MONTH:
                c.set(Calendar.DAY_OF_MONTH, 1);
                break;
            case DAY:
            default:
                break;
        }
        return c.getTimeInMillis();
    }

    private void advance(Calendar c) {
        switch (granularity) {
            case WEEK:
                c.add(Calendar.DAY_OF_MONTH, 7);
                break;
            case MONTH:
                c.add(Calendar.MONTH, 1);
                break;
            case DAY:
            default:
                c.add(Calendar.DAY_OF_MONTH, 1);
                break;
        }
    }

    private String label(long periodStartMs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(periodStartMs);
        switch (granularity) {
            case DAY:
                return DateFormats.shortDate(periodStartMs);
            case WEEK:
                return String.format(Locale.GERMANY, "%02d/%02d",
                        c.get(Calendar.WEEK_OF_YEAR), c.get(Calendar.YEAR) % 100);
            case MONTH:
            default:
                return String.format(Locale.GERMANY, "%02d/%02d",
                        c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR) % 100);
        }
    }

    private String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private String formatEuro(long signedCents) {
        String acc = viewKey.startsWith(MainActivity.VIEW_ACCOUNT_PREFIX)
                ? viewKey.substring(MainActivity.VIEW_ACCOUNT_PREFIX.length()) : null;
        return MoneyFormat.display(signedCents,
                acc == null || acc.isEmpty() ? Currencies.getDefault() : Currencies.forAccount(acc));
    }
}
