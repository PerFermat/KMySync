package de.spahr.ausgaben.ui;

import android.app.DatePickerDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.listener.OnChartValueSelectedListener;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.slider.RangeSlider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.settings.CategoryColorStore;
import de.spahr.ausgaben.settings.Currencies;
import de.spahr.ausgaben.settings.MoneyFormat;
import de.spahr.ausgaben.settings.DateFormats;

/**
 * Kreisdiagramm der Wertpapiere eines Depots – im Design der Kategorien-Seite. Ein Umschalter oben wählt die
 * <b>Ansicht</b> (Aktueller Wert / Netto-Einzahlungen / Summe Dividenden), ein monatlicher Zeitraumfilter
 * (Bereichsslider + Von/Bis-Datumsfelder) grenzt die Auswertung ein. Darunter, in einer eigenen Scroll-Liste,
 * die Wertpapiere. Grafik und Liste sind <b>immer</b> absteigend nach dem aktuellen Wert des Zeitraums
 * sortiert; die Segmente sind unbeschriftet (Tipp zeigt Name + Betrag, ohne Auswahl „Gesamt").
 */
public class DepotChartActivity extends LocalizedActivity {

    public static final String EXTRA_DEPOT = "depot";

    private static final int VIEW_VALUE = 0;
    private static final int VIEW_NETTO = 1;
    private static final int VIEW_DIVIDEND = 2;
    private static final int VIEW_GAIN = 3;

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private Repository repository;
    private MaterialToolbar toolbar;
    private PieChart pie;
    private LinearLayout list;
    private TextView empty;
    private RangeSlider periodSlider;
    private TextInputEditText dateFrom;
    private TextInputEditText dateTo;


    private String depot = "";
    private int view = VIEW_VALUE;
    private long firstTxMs = 0;
    private final Calendar firstMonth = Calendar.getInstance();
    private int monthCount = 0;
    private long fromMs;
    private long toMsExcl;   // exklusives Ende ([from, to))
    private boolean ready = false;
    private boolean showSold = false;   // komplett verkaufte Wertpapiere standardmäßig ausblenden
    private boolean sliderSyncing = false;
    private int reloadSeq = 0;   // gegen veraltete Ergebnisse bei schnellen Zeitraum-Änderungen

    private List<Repository.DepotChartRow> rows = new ArrayList<>();
    private String totalText = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_depot_chart);
        toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.depot_chart);
        android.view.MenuItem sold = toolbar.getMenu().findItem(R.id.action_show_sold);
        sold.setChecked(showSold);
        updateSoldIcon(sold);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_show_sold) {
                showSold = !showSold;
                item.setChecked(showSold);
                updateSoldIcon(item);
                render();   // aus dem Cache – kein Neuladen nötig
                return true;
            }
            return false;
        });

        repository = new Repository(this);
        pie = findViewById(R.id.depotPie);
        list = findViewById(R.id.depotList);
        empty = findViewById(R.id.depotChartEmpty);
        periodSlider = findViewById(R.id.periodSlider);
        dateFrom = findViewById(R.id.dateFrom);
        dateTo = findViewById(R.id.dateTo);

        // Diagramm höchstens halb so hoch wie der Bildschirm; die Liste darunter bleibt scrollbar.
        pie.getLayoutParams().height = getResources().getDisplayMetrics().heightPixels / 2;

        MaterialButtonToggleGroup toggle = findViewById(R.id.toggleView);
        toggle.check(R.id.btnViewValue);
        toggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            if (checkedId == R.id.btnViewValue) {
                view = VIEW_VALUE;
            } else if (checkedId == R.id.btnViewNetto) {
                view = VIEW_NETTO;
            } else if (checkedId == R.id.btnViewDividend) {
                view = VIEW_DIVIDEND;
            } else {
                view = VIEW_GAIN;
            }
            updateTitle();
            render();   // aus dem Cache – kein Neuladen nötig
        });

        periodSlider.addOnChangeListener((s, val, fromUser) -> {
            if (!sliderSyncing) {
                onSliderChanged();
            }
        });
        dateFrom.setOnClickListener(v -> pickDate(true));
        dateTo.setOnClickListener(v -> pickDate(false));
        // Das Kalendersymbol liegt über dem Feld und würde den Tipper sonst schlucken.
        ((TextInputLayout) findViewById(R.id.dateFromLayout))
                .setEndIconOnClickListener(v -> pickDate(true));
        ((TextInputLayout) findViewById(R.id.dateToLayout))
                .setEndIconOnClickListener(v -> pickDate(false));

        depot = getIntent().getStringExtra(EXTRA_DEPOT);
        if (depot == null || depot.isEmpty()) {
            finish();
            return;
        }
        updateTitle();   // Titel = gewählte Ansicht (statt Depotname)
        repository.getDepotFirstTx(depot, first -> {
            firstTxMs = first;
            setupPeriod();
            ready = true;
            reload();
        });
    }

    // ---- Zeitraum ----

    private void setupPeriod() {
        long base = firstTxMs > 0 ? firstTxMs : System.currentTimeMillis();
        firstMonth.setTimeInMillis(base);
        toStartOfMonth(firstMonth);
        Calendar cur = Calendar.getInstance();
        toStartOfMonth(cur);
        monthCount = (cur.get(Calendar.YEAR) - firstMonth.get(Calendar.YEAR)) * 12
                + (cur.get(Calendar.MONTH) - firstMonth.get(Calendar.MONTH));
        if (monthCount < 0) {
            monthCount = 0;
        }
        // Standard: voller Zeitraum – erste Buchung bis heute.
        fromMs = firstMonth.getTimeInMillis();
        toMsExcl = startOfTomorrow();

        sliderSyncing = true;
        periodSlider.setValueFrom(0f);
        periodSlider.setValueTo(Math.max(1, monthCount));
        periodSlider.setStepSize(1f);
        periodSlider.setValues(0f, (float) Math.max(1, monthCount));
        periodSlider.setEnabled(monthCount >= 1);
        sliderSyncing = false;
        updateDateFields();
    }

    private void onSliderChanged() {
        List<Float> vals = periodSlider.getValues();
        int fromIdx = Math.round(vals.get(0));
        int toIdx = Math.round(vals.get(1));
        fromMs = monthStart(fromIdx);
        toMsExcl = toIdx >= monthCount ? startOfTomorrow() : monthStart(toIdx + 1);
        updateDateFields();
        reload();
    }

    private void pickDate(boolean isFrom) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(isFrom ? fromMs : toMsExcl - DAY_MS);
        new DatePickerDialog(this, (dp, y, m, d) -> {
            Calendar sel = Calendar.getInstance();
            sel.set(y, m, d, 0, 0, 0);
            sel.set(Calendar.MILLISECOND, 0);
            if (isFrom) {
                fromMs = sel.getTimeInMillis();
            } else {
                toMsExcl = sel.getTimeInMillis() + DAY_MS;   // gewählter Tag ist inklusive
            }
            if (fromMs >= toMsExcl) {   // Von hinter Bis → gleicher Tag
                if (isFrom) {
                    toMsExcl = fromMs + DAY_MS;
                } else {
                    fromMs = toMsExcl - DAY_MS;
                }
            }
            syncSliderToDates();
            updateDateFields();
            reload();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void syncSliderToDates() {
        if (monthCount < 1) {
            return;
        }
        int fi = clampIdx(monthIndexOf(fromMs));
        int ti = clampIdx(monthIndexOf(toMsExcl - DAY_MS));
        if (fi > ti) {
            ti = fi;
        }
        sliderSyncing = true;
        periodSlider.setValues((float) fi, (float) ti);
        sliderSyncing = false;
    }

    private void updateDateFields() {
        dateFrom.setText(DateFormats.date(fromMs));
        dateTo.setText(DateFormats.date(toMsExcl - DAY_MS));   // inklusiver letzter Tag
    }

    private long monthStart(int idx) {
        Calendar c = (Calendar) firstMonth.clone();
        c.add(Calendar.MONTH, idx);
        return c.getTimeInMillis();
    }

    private int monthIndexOf(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return (c.get(Calendar.YEAR) - firstMonth.get(Calendar.YEAR)) * 12
                + (c.get(Calendar.MONTH) - firstMonth.get(Calendar.MONTH));
    }

    private int clampIdx(int idx) {
        return Math.max(0, Math.min(monthCount, idx));
    }

    private static void toStartOfMonth(Calendar c) {
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    private static long startOfTomorrow() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    // ---- Laden / Rendern ----

    private void reload() {
        if (!ready) {
            return;
        }
        boolean whole = fromMs <= firstTxMs && toMsExcl > System.currentTimeMillis();
        final long from = fromMs;
        final long to = toMsExcl;
        final int seq = ++reloadSeq;
        repository.getDepotChartRows(depot, from, to, whole, r -> {
            if (seq != reloadSeq) {
                return;   // ein neuerer Zeitraum wurde inzwischen angefordert → dieses Ergebnis verwerfen
            }
            rows = r != null ? r : new ArrayList<>();
            render();
        });
    }

    private long displayValue(Repository.DepotChartRow r) {
        if (view == VIEW_NETTO) {
            return r.netDepositsCents;
        }
        if (view == VIEW_DIVIDEND) {
            return r.dividendCents;
        }
        if (view == VIEW_GAIN) {
            return gainOf(r);
        }
        return r.currentValueCents;
    }

    /** Gewinn/Verlust eines Wertpapiers = aktueller Wert − Netto-Einzahlungen (vorzeichenbehaftet). */
    private long gainOf(Repository.DepotChartRow r) {
        return r.currentValueCents - r.netDepositsCents;
    }

    /** Titel der gewählten Ansicht (steht in der Toolbar). */
    private String viewLabel() {
        switch (view) {
            case VIEW_NETTO:
                return getString(R.string.depot_view_netto);
            case VIEW_DIVIDEND:
                return getString(R.string.depot_view_dividend);
            case VIEW_GAIN:
                return getString(R.string.depot_view_gain);
            default:
                return getString(R.string.depot_view_value);
        }
    }

    private void updateTitle() {
        if (toolbar != null) {
            toolbar.setTitle(viewLabel());
        }
    }

    /** Zustand des Umschalters sichtbar machen: volles Icon = verkaufte eingeblendet, gedimmt = ausgeblendet. */
    private void updateSoldIcon(android.view.MenuItem item) {
        if (item.getIcon() != null) {
            item.getIcon().setAlpha(showSold ? 255 : 90);
        }
    }

    private void render() {
        // Immer absteigend nach aktuellem Wert des Zeitraums; Gleichstand → nach angezeigtem Wert.
        List<Repository.DepotChartRow> sorted = new ArrayList<>(rows);
        if (!showSold) {
            // Am Ende des Zeitraums komplett verkaufte Wertpapiere ausblenden (Standard).
            sorted.removeIf(r -> r.fullySold);
        }
        Collections.sort(sorted, (a, b) -> {
            int c = Long.compare(b.currentValueCents, a.currentValueCents);
            return c != 0 ? c : Long.compare(displayValue(b), displayValue(a));
        });

        list.removeAllViews();
        boolean any = false;
        for (Repository.DepotChartRow r : sorted) {
            if (displayValue(r) != 0) {
                any = true;
                break;
            }
        }
        if (!any) {
            pie.setVisibility(View.GONE);
            empty.setVisibility(View.VISIBLE);
            return;
        }

        if (view == VIEW_GAIN) {
            long totalGain = 0;
            long totalInvested = 0;
            for (Repository.DepotChartRow r : sorted) {
                totalGain += gainOf(r);
                totalInvested += r.investedCents;   // Einstandspreis als Rendite-Nenner
            }
            buildPieGain(sorted, totalGain, totalInvested);
            for (Repository.DepotChartRow r : sorted) {
                if (gainOf(r) != 0) {
                    list.addView(buildRowGain(r));
                }
            }
        } else {
            long total = 0;
            for (Repository.DepotChartRow r : sorted) {
                long v = displayValue(r);
                if (v > 0) {
                    total += v;
                }
            }
            buildPie(sorted, total);
            for (Repository.DepotChartRow r : sorted) {
                if (displayValue(r) != 0) {
                    list.addView(buildRow(r, total));
                }
            }
        }
        pie.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
    }

    private void buildPie(List<Repository.DepotChartRow> sorted, long total) {
        List<PieEntry> entries = new ArrayList<>();
        List<Integer> pieColors = new ArrayList<>();
        for (Repository.DepotChartRow r : sorted) {
            long v = displayValue(r);
            if (v <= 0) {
                continue;   // Kreissegmente nur für positive Werte
            }
            PieEntry e = new PieEntry(v / 100f, r.name);
            e.setData(v);
            entries.add(e);
            pieColors.add(CategoryColorStore.defaultColor(r.name));
        }
        totalText = getString(R.string.saldo_total) + ":\n" + money(total);

        PieDataSet set = new PieDataSet(entries, "");
        set.setColors(pieColors);
        set.setSliceSpace(2f);   // schmale schwarze Standard-Umrandung zwischen den Segmenten
        set.setDrawValues(false);

        pie.setData(new PieData(set));
        pie.getDescription().setEnabled(false);
        pie.getLegend().setEnabled(false);
        pie.setDrawEntryLabels(false);
        pie.setUsePercentValues(false);
        pie.setDrawHoleEnabled(true);
        pie.setHoleColor(0x00000000);
        pie.setHoleRadius(62f);
        pie.setTransparentCircleAlpha(0);
        pie.setDrawCenterText(true);
        pie.setCenterText(totalText);
        pie.setCenterTextSize(16f);
        pie.setCenterTextColor(getColor(R.color.chart_text));
        pie.setRotationEnabled(false);
        pie.setHighlightPerTapEnabled(true);
        pie.setExtraOffsets(8f, 8f, 8f, 8f);
        pie.setOnChartValueSelectedListener(new OnChartValueSelectedListener() {
            @Override
            public void onValueSelected(Entry e, Highlight h) {
                String name = e instanceof PieEntry ? ((PieEntry) e).getLabel() : "";
                long cents = e.getData() instanceof Long ? (Long) e.getData() : 0L;
                pie.setCenterText(name + "\n" + money(cents));
            }

            @Override
            public void onNothingSelected() {
                pie.setCenterText(totalText);
            }
        });
        pie.invalidate();
    }

    /** Listenzeile: Farbpunkt · Name · Anteil in Prozent · Wert der gewählten Ansicht (rechts). */
    private View buildRow(Repository.DepotChartRow r, long total) {
        long v = displayValue(r);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        View dot = new View(this);
        dot.setBackgroundColor(CategoryColorStore.defaultColor(r.name));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dotLp.setMarginEnd(dp(10));
        row.addView(dot, dotLp);

        TextView name = new TextView(this);
        name.setText(r.name);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView pct = new TextView(this);
        pct.setText(total > 0 && v > 0 ? Math.round(100.0 * v / total) + " %" : "");
        pct.setTextSize(12);
        pct.setTextColor(0xFF9E9E9E);
        LinearLayout.LayoutParams pctLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pctLp.setMarginEnd(dp(10));
        row.addView(pct, pctLp);

        TextView amount = new TextView(this);
        amount.setText(money(v));
        amount.setGravity(Gravity.END);
        amount.setTypeface(amount.getTypeface(), Typeface.BOLD);
        row.addView(amount, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** Kreisdiagramm der Gewinn/Verlust-Ansicht: Segmente nach |Gewinn|, grün = Gewinn, rot = Verlust. */
    private void buildPieGain(List<Repository.DepotChartRow> sorted, long totalGain, long totalNet) {
        List<PieEntry> entries = new ArrayList<>();
        List<Integer> pieColors = new ArrayList<>();
        for (Repository.DepotChartRow r : sorted) {
            long g = gainOf(r);
            if (g == 0) {
                continue;
            }
            PieEntry e = new PieEntry(Math.abs(g) / 100f, r.name);
            e.setData(g);
            entries.add(e);
            // Segmente wie bei den anderen Ansichten in den Kategorie-Farben (nicht grün/rot).
            pieColors.add(CategoryColorStore.defaultColor(r.name));
        }
        final int centerColor = getColor(R.color.chart_text);
        totalText = gainText(totalGain, totalNet);

        PieDataSet set = new PieDataSet(entries, "");
        set.setColors(pieColors);
        set.setSliceSpace(2f);   // schmale schwarze Standard-Umrandung zwischen den Segmenten
        set.setDrawValues(false);

        pie.setData(new PieData(set));
        pie.getDescription().setEnabled(false);
        pie.getLegend().setEnabled(false);
        pie.setDrawEntryLabels(false);
        pie.setUsePercentValues(false);
        pie.setDrawHoleEnabled(true);
        pie.setHoleColor(0x00000000);
        pie.setHoleRadius(62f);
        pie.setTransparentCircleAlpha(0);
        pie.setDrawCenterText(true);
        pie.setCenterText(totalText);
        pie.setCenterTextSize(16f);
        pie.setCenterTextColor(centerColor);
        pie.setRotationEnabled(false);
        pie.setHighlightPerTapEnabled(true);
        pie.setExtraOffsets(8f, 8f, 8f, 8f);
        pie.setOnChartValueSelectedListener(new OnChartValueSelectedListener() {
            @Override
            public void onValueSelected(Entry e, Highlight h) {
                String name = e instanceof PieEntry ? ((PieEntry) e).getLabel() : "";
                long g = e.getData() instanceof Long ? (Long) e.getData() : 0L;
                pie.setCenterText(name + "\n" + money(g));
            }

            @Override
            public void onNothingSelected() {
                pie.setCenterText(totalText);
                pie.setCenterTextColor(centerColor);
            }
        });
        pie.invalidate();
    }

    /** Mittiger Text der Gewinn/Verlust-Ansicht: „Gewinn/Verlust (x %)\nBetrag" – %-Basis = Einstandspreis. */
    private String gainText(long totalGain, long totalInvested) {
        String pct = totalInvested > 0
                ? " (" + de.spahr.ausgaben.settings.MoneyFormat.decimal(100.0 * totalGain / totalInvested, 2, 2) + " %)"
                : "";
        String label = getString(totalGain >= 0 ? R.string.depot_gain : R.string.depot_loss);
        return label + pct + "\n" + money(totalGain);
    }

    /** Listenzeile der Gewinn/Verlust-Ansicht: Farbpunkt · Name · Rendite-% · Gewinn/Verlust (grün/rot). */
    private View buildRowGain(Repository.DepotChartRow r) {
        long g = gainOf(r);
        int signColor = getColor(g >= 0 ? R.color.income_green : R.color.expense_red);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        View dot = new View(this);
        dot.setBackgroundColor(CategoryColorStore.defaultColor(r.name));   // Farbpunkt wie im Kreis
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dotLp.setMarginEnd(dp(10));
        row.addView(dot, dotLp);

        TextView name = new TextView(this);
        name.setText(r.name);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView pct = new TextView(this);
        // Rendite = Gewinn/Verlust im Verhältnis zum Einstandspreis (Käufe), auch bei verkauften Papieren.
        pct.setText(r.investedCents > 0
                ? de.spahr.ausgaben.settings.MoneyFormat.decimal(100.0 * g / r.investedCents, 1, 1) + " %" : "");
        pct.setTextSize(12);
        pct.setTextColor(signColor);
        LinearLayout.LayoutParams pctLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pctLp.setMarginEnd(dp(10));
        row.addView(pct, pctLp);

        TextView amount = new TextView(this);
        amount.setText(money(g));
        amount.setGravity(Gravity.END);
        amount.setTextColor(signColor);
        amount.setTypeface(amount.getTypeface(), Typeface.BOLD);
        row.addView(amount, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private String money(long cents) {
        return MoneyFormat.display(cents, Currencies.getDefault());
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
