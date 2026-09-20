package de.spahr.ausgaben.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.listener.OnChartValueSelectedListener;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButtonToggleGroup;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.BookingSplit;
import de.spahr.ausgaben.db.CategoryBookingFilter;
import de.spahr.ausgaben.db.CategorySum;
import de.spahr.ausgaben.db.PlannedExpense;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.db.ScheduleProjection;
import de.spahr.ausgaben.db.ScheduledSplit;
import de.spahr.ausgaben.db.ScheduledTransaction;
import de.spahr.ausgaben.settings.CategoryColorStore;
import de.spahr.ausgaben.settings.Currencies;
import de.spahr.ausgaben.settings.MoneyFormat;
import de.spahr.ausgaben.settings.DateFormats;

/**
 * „Wofür geht mein Geld?" – die <b>Ausgaben je Kategorie</b> für einen Zeitraum als Kreisdiagramm plus
 * absteigend sortierte Liste. Ergänzt die zeitliche Auswertung ({@link AnalysisActivity}), die nur nach
 * Konto/Ort/Gesamt über die Zeit aggregiert.
 *
 * <p>Datenbasis ist {@code BookingDao.getCategoryActuals} (dieselbe Quelle wie die Budget-Seite): sie
 * berücksichtigt Splitbuchungen über ihre Teilbeträge, lässt Umbuchungen außen vor und gilt über
 * <b>alle Konten</b>. Gezeigt werden nur Kategorien mit Netto-<b>Abfluss</b>.</p>
 *
 * <p>Optional („mit geplanten Buchungen", Menü) werden die im Zeitraum fälligen geplanten Auszahlungen
 * dazugerechnet – heller eingefärbt und je Zeile aufklappbar in Ist- und Plan-Anteil. Die Farbe einer
 * Kategorie ist über {@link CategoryColorStore} <b>fest</b> (unabhängig vom Listenplatz) und im Farb-Editor
 * ({@link CategoryColorActivity}) änderbar.</p>
 */
public class CategoryChartActivity extends LocalizedActivity {

    /** Wie weit die Plan-Projektion (und damit der Zukunfts-Slider) höchstens vorausschaut. */
    private static final int PLANNED_HORIZON_MONTHS = 24;
    /** Sicherheitsgrenze für die Termine je Planung in einem Fenster. */
    private static final int MAX_OCC = 400;

    private Repository repository;
    private CategoryColorStore colors;
    private PieChart pie;
    private LinearLayout list;
    private LinearLayout monthHeader;
    private TextView empty;
    private String totalText = "";
    /** true = Monatssicht, false = Jahressicht. */
    private boolean monthRange = true;
    /** Monatssicht: 0 = aktueller Monat, ±n = vor/zurück (Wischgeste bzw. Tipp auf die Kopfzeile). */
    private int monthOffset = 0;
    /** Jahressicht: 0 = aktuelles Jahr, ±n = vor/zurück. */
    private int yearOffset = 0;
    /** Menüzustand: geplante Buchungen mitrechnen. */
    private boolean includePlanned = false;
    private GestureDetector swipeDetector;

    // Einmalig geladene Daten für Plan-Anteil und Slider-Grenzen.
    private boolean dataReady = false;
    private int pending = 4;
    private List<ScheduledTransaction> scheduled = new ArrayList<>();
    private final Map<Long, List<ScheduledSplit>> splitsBySched = new LinkedHashMap<>();
    private long firstDataMs = 0;
    private long lastActualMs = 0;
    /** Alle Kategorie-Teile, gruppiert nach Buchungs-ID – für den Buchungs-Drilldown je Kreissegment. */
    private final Map<Long, List<BookingSplit>> splitsByBooking = new LinkedHashMap<>();

    // Zustand des Buchungs-Drilldowns (Tipp auf ein Kreissegment).
    private String selectedCategory = null;
    private long periodFromMs = 0;
    private long periodToMs = 0;
    private List<Cat> lastOut = new ArrayList<>();
    private long lastTotal = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_category_chart);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        setupMenu(toolbar);

        repository = new Repository(this);
        colors = new CategoryColorStore(this);
        pie = findViewById(R.id.categoryPie);
        list = findViewById(R.id.categoryList);
        monthHeader = findViewById(R.id.monthHeader);
        empty = findViewById(R.id.categoryEmpty);

        // Diagramm höchstens halb so hoch wie der Bildschirm; die Liste darunter bleibt scrollbar.
        pie.getLayoutParams().height = getResources().getDisplayMetrics().heightPixels / 2;

        MaterialButtonToggleGroup toggle = findViewById(R.id.toggleRange);
        toggle.check(R.id.btnRangeMonth);
        toggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                monthRange = checkedId == R.id.btnRangeMonth;
                monthOffset = 0;   // Sicht startet immer beim aktuellen Zeitraum
                yearOffset = 0;
                load();
            }
        });

        // Wischen nach rechts = Zeitraum davor, nach links = danach (wie im Budget); gilt für Monat und Jahr.
        swipeDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) {
                    return false;
                }
                float dx = e2.getX() - e1.getX();
                if (Math.abs(dx) > Math.abs(e2.getY() - e1.getY())
                        && Math.abs(dx) > dp(60) && Math.abs(vx) > dp(60)) {
                    step(dx > 0 ? -1 : 1);
                    return true;
                }
                return false;
            }
        });

        loadData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Nach Rückkehr aus dem Farb-Editor sofort die (evtl. geänderten) Farben zeigen.
        if (dataReady) {
            load();
        }
    }

    private void setupMenu(MaterialToolbar toolbar) {
        toolbar.inflateMenu(R.menu.category_menu);
        Menu menu = toolbar.getMenu();
        MenuItem planned = menu.findItem(R.id.action_include_planned);
        planned.setChecked(includePlanned);
        updatePlannedIcon(planned);
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_include_planned) {
                includePlanned = !includePlanned;
                item.setChecked(includePlanned);
                updatePlannedIcon(item);
                load();
                return true;
            } else if (id == R.id.action_colors) {
                startActivity(new Intent(this, CategoryColorActivity.class));
                return true;
            }
            return false;
        });
    }

    /** Zustand des Umschalters sichtbar machen: volles Icon = an, gedimmt = aus. */
    private void updatePlannedIcon(MenuItem item) {
        if (item.getIcon() != null) {
            item.getIcon().setAlpha(includePlanned ? 255 : 90);
        }
    }

    private void loadData() {
        repository.getScheduledTransactions(l -> {
            scheduled = l != null ? l : new ArrayList<>();
            ready();
        });
        repository.getAllScheduledSplits(l -> {
            splitsBySched.clear();
            if (l != null) {
                for (ScheduledSplit s : l) {
                    List<ScheduledSplit> bucket = splitsBySched.get(s.scheduledId);
                    if (bucket == null) {
                        bucket = new ArrayList<>();
                        splitsBySched.put(s.scheduledId, bucket);
                    }
                    bucket.add(s);
                }
            }
            ready();
        });
        repository.getBookingDateRange(r -> {
            firstDataMs = r[0];
            lastActualMs = Math.max(System.currentTimeMillis(), r[1]);
            ready();
        });
        repository.getAllSplitsMap(m -> {
            splitsByBooking.clear();
            if (m != null) {
                splitsByBooking.putAll(m);
            }
            ready();
        });
    }

    private void ready() {
        if (--pending == 0) {
            dataReady = true;
            load();
        }
    }

    /** Blättert um {@code dir} Perioden (Monat oder Jahr) und lädt neu. */
    private void step(int dir) {
        if (monthRange) {
            monthOffset += dir;
        } else {
            yearOffset += dir;
        }
        load();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (swipeDetector != null) {
            swipeDetector.onTouchEvent(ev);
        }
        return super.dispatchTouchEvent(ev);
    }

    private void load() {
        if (!dataReady) {
            return;   // wird nach dem einmaligen Laden erneut aufgerufen
        }
        clampOffset();
        Calendar c = startOfCurrentPeriod();
        if (monthRange) {
            c.add(Calendar.MONTH, monthOffset);
        } else {
            c.add(Calendar.YEAR, yearOffset);
        }
        long from = c.getTimeInMillis();
        c.add(monthRange ? Calendar.MONTH : Calendar.YEAR, 1);
        long to = c.getTimeInMillis();
        buildHeader();
        final long fromMs = from;
        final long toMs = to;
        repository.getCategoryActuals(fromMs, toMs, sums -> render(sums, fromMs, toMs));
    }

    /** Anfang des aktuellen Zeitraums (Monat: 1. des Monats; Jahr: 1. Januar) auf Mitternacht. */
    private Calendar startOfCurrentPeriod() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (!monthRange) {
            c.set(Calendar.MONTH, Calendar.JANUARY);
        }
        return c;
    }

    /**
     * Hält den Offset in den erlaubten Grenzen: nicht vor den ersten Buchungszeitraum und – ohne Planungen –
     * nicht hinter den aktuellen; mit Planungen bis zum spätesten geplanten Termin.
     */
    private void clampOffset() {
        Calendar cur = startOfCurrentPeriod();
        long upperMs = includePlanned ? Math.max(lastActualMs, lastPlannedMs()) : lastActualMs;
        int min = firstDataMs > 0 ? periodsBetween(cur, firstDataMs) : 0;   // ≤ 0
        int max = periodsBetween(cur, upperMs);                              // ≥ 0
        if (min > 0) {
            min = 0;
        }
        if (max < 0) {
            max = 0;
        }
        if (monthRange) {
            monthOffset = Math.max(min, Math.min(max, monthOffset));
        } else {
            yearOffset = Math.max(min, Math.min(max, yearOffset));
        }
    }

    /** Perioden-Abstand (Monate bzw. Jahre) von {@code cur} bis zum Zeitraum von {@code targetMs}. */
    private int periodsBetween(Calendar cur, long targetMs) {
        Calendar t = Calendar.getInstance();
        t.setTimeInMillis(targetMs);
        if (monthRange) {
            return (t.get(Calendar.YEAR) - cur.get(Calendar.YEAR)) * 12
                    + (t.get(Calendar.MONTH) - cur.get(Calendar.MONTH));
        }
        return t.get(Calendar.YEAR) - cur.get(Calendar.YEAR);
    }

    /** Spätester geplanter Auszahlungstermin innerhalb des Vorausschau-Horizonts (0 = keiner). */
    private long lastPlannedMs() {
        long now = System.currentTimeMillis();
        Calendar h = Calendar.getInstance();
        h.add(Calendar.MONTH, PLANNED_HORIZON_MONTHS);
        long horizon = h.getTimeInMillis();
        long max = 0;
        for (ScheduledTransaction st : scheduled) {
            if (st.kind != ScheduledTransaction.KIND_EXPENSE || st.nextDueMs <= 0) {
                continue;
            }
            for (long d : ScheduleProjection.occurrences(st.nextDueMs, st.occurrence,
                    st.occurrenceMultiplier, st.endMs, now, horizon, MAX_OCC)) {
                if (d > max) {
                    max = d;
                }
            }
        }
        return max;
    }

    /**
     * Kopfzeile wie im Budget: zentriert der angezeigte Zeitraum (fett), links und rechts grau der vorige
     * bzw. nächste. Tippen blättert – wie die Wischgeste – eine Periode. Gilt für Monat und Jahr.
     */
    private void buildHeader() {
        monthHeader.removeAllViews();
        monthHeader.setVisibility(View.VISIBLE);
        Calendar cur = startOfCurrentPeriod();
        int field = monthRange ? Calendar.MONTH : Calendar.YEAR;
        cur.add(field, monthRange ? monthOffset : yearOffset);
        Calendar prev = (Calendar) cur.clone();
        prev.add(field, -1);
        Calendar next = (Calendar) cur.clone();
        next.add(field, 1);

        TextView left = periodLabel(prev, false);
        left.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        left.setOnClickListener(v -> step(-1));
        TextView center = periodLabel(cur, true);
        center.setGravity(Gravity.CENTER);
        TextView right = periodLabel(next, false);
        right.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        right.setOnClickListener(v -> step(1));
        monthHeader.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        monthHeader.addView(center, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        monthHeader.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    }

    /** Perioden-Label; {@code center} = angezeigter Zeitraum (fett), sonst grau. */
    private TextView periodLabel(Calendar cal, boolean center) {
        java.util.Locale locale = getResources().getConfiguration().getLocales().get(0);
        String pattern = monthRange ? (center ? "MMMM yyyy" : "MMMM") : "yyyy";
        TextView tv = new TextView(this);
        tv.setText(new java.text.SimpleDateFormat(pattern, locale).format(cal.getTime()));
        if (center) {
            tv.setTextSize(18);
            tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        } else {
            tv.setTextColor(android.graphics.Color.GRAY);
        }
        return tv;
    }

    /** Eine Kategorie mit Ist- und Plan-Anteil (Anzeige-Modell; {@link CategorySum} ist ein Room-Typ). */
    private static final class Cat {
        final String category;
        long actual;    // bereits gezahlt (Cent, ≥ 0)
        long planned;   // geplant (Cent, ≥ 0)

        Cat(String category) {
            this.category = category;
        }

        long total() {
            return actual + planned;
        }
    }

    /** Betrag + Kennzeichen je Kreissegment (für den Tipp-Text). */
    private static final class SliceData {
        final long cents;
        final boolean planned;

        SliceData(long cents, boolean planned) {
            this.cents = cents;
            this.planned = planned;
        }
    }

    private void render(List<CategorySum> sums, long fromMs, long toMs) {
        Map<String, Cat> byCat = new LinkedHashMap<>();
        // Ist-Ausgaben: nur Abflüsse (negative Netto-Summe) als positive Beträge.
        if (sums != null) {
            for (CategorySum s : sums) {
                if (s.total < 0) {
                    cat(byCat, s.category).actual += -s.total;
                }
            }
        }
        if (includePlanned) {
            addPlanned(byCat, fromMs, toMs);
        }

        List<Cat> out = new ArrayList<>();
        long total = 0;
        for (Cat c : byCat.values()) {
            if (c.total() > 0) {
                out.add(c);
                total += c.total();
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.total(), a.total()));

        periodFromMs = fromMs;
        periodToMs = toMs;
        selectedCategory = null;   // Zeitraum-/Menüwechsel verwirft eine evtl. offene Buchungsauswahl
        lastOut = out;
        lastTotal = total;

        if (out.isEmpty()) {
            list.removeAllViews();
            pie.setVisibility(View.GONE);
            empty.setVisibility(View.VISIBLE);
            return;
        }
        buildPie(out, total);
        showCategoryList();
        pie.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
    }

    /** Listenanzeige zurücksetzen auf die normale Kategorie-Übersicht (Name/Prozent/Betrag je Kategorie). */
    private void showCategoryList() {
        list.removeAllViews();
        for (Cat c : lastOut) {
            list.addView(buildRow(c, lastTotal));
        }
    }

    /** Eine Drilldown-Zeile mit Datum (für die Sortierung) und fertig gebauter View. */
    private static final class DrilldownRow {
        final long dateMs;
        final boolean planned;
        final View view;

        DrilldownRow(long dateMs, boolean planned, View view) {
            this.dateMs = dateMs;
            this.planned = planned;
            this.view = view;
        }
    }

    /**
     * Listenanzeige auf die Einzelbuchungen einer Kategorie im aktuellen Zeitraum umschalten – aufsteigend
     * sortiert (älteste oben); bei „geplante Buchungen einbeziehen" stehen die im Zeitraum fälligen
     * geplanten Termine (grau) unabhängig von ihrem Datum stets unten, nach den Ist-Buchungen.
     */
    private void showCategoryBookings(String category) {
        boolean isMain = !category.contains(":");
        repository.getBookingsBetween(periodFromMs, periodToMs, bookings -> {
            // Auswahl könnte sich zwischenzeitlich geändert haben (schnelles Antippen) – dann verwerfen.
            if (!category.equals(selectedCategory)) {
                return;
            }
            List<DrilldownRow> rows = new ArrayList<>();
            if (bookings != null) {
                for (Booking b : bookings) {
                    // Dieser Bildschirm zeigt nur Ausgaben (siehe render(), s.total < 0) – der Typ ist
                    // daher immer „Ausgabe"; ohne diesen Zusatz würde bei gleichnamigen Einnahme-/
                    // Ausgabekategorien (kMyMoney erlaubt das) die Einnahme-Buchung fälschlich mit erscheinen.
                    if (!CategoryBookingFilter.matchesBooking(
                            b, splitsByBooking, category, isMain, null, Boolean.FALSE)) {
                        continue;
                    }
                    long signed = b.isIncome ? b.amountCents : -b.amountCents;
                    long display = CategoryBookingFilter.displaySigned(
                            b, splitsByBooking, category, isMain, signed, null, Boolean.FALSE);
                    boolean partial = CategoryBookingFilter.isPartial(
                            b, splitsByBooking, category, isMain, null, Boolean.FALSE);
                    List<BookingSplit> parts = splitsByBooking.get(b.id);
                    rows.add(new DrilldownRow(b.createdAt, false, bookingRow(b, display, partial,
                            parts != null && parts.size() >= 2)));
                }
            }
            if (includePlanned) {
                rows.addAll(plannedRows(category, isMain));
            }
            Collections.sort(rows, (a, b2) -> {
                if (a.planned != b2.planned) {
                    return a.planned ? 1 : -1;   // Ist-Buchungen immer vor den geplanten
                }
                return Long.compare(a.dateMs, b2.dateMs);   // aufsteigend: älteste zuerst
            });

            list.removeAllViews();
            if (rows.isEmpty()) {
                TextView hint = new TextView(this);
                hint.setText(R.string.category_chart_no_bookings);
                hint.setTextColor(0xFF9E9E9E);
                hint.setPadding(0, dp(8), 0, dp(8));
                list.addView(hint);
                return;
            }
            for (DrilldownRow r : rows) {
                list.addView(r.view);
            }
        });
    }

    /**
     * Im Zeitraum fällige geplante Abflüsse einer Kategorie – eine Zeile je Termin. Dieselbe Auswahl wie
     * in {@link #addPlanned}, sonst nennt die Torte einen Betrag, den die Liste darunter nicht erklärt.
     */
    private List<DrilldownRow> plannedRows(String category, boolean isMain) {
        List<DrilldownRow> out = new ArrayList<>();
        for (ScheduledTransaction st : scheduled) {
            if (st.kind == ScheduledTransaction.KIND_TRANSFER || st.nextDueMs <= 0) {
                continue;
            }
            List<Long> occ = ScheduleProjection.occurrences(st.nextDueMs, st.occurrence,
                    st.occurrenceMultiplier, st.endMs, periodFromMs, periodToMs - 1, MAX_OCC);
            if (occ.isEmpty()) {
                continue;
            }
            if (st.split == 1) {
                List<ScheduledSplit> parts = splitsBySched.get(st.id);
                if (parts == null) {
                    continue;
                }
                for (ScheduledSplit p : parts) {
                    long cents = PlannedExpense.expenseCents(st.kind, p.amountCents);
                    if (p.category.isEmpty() || cents == 0
                            || !CategoryBookingFilter.matches(p.category, category, isMain)) {
                        continue;
                    }
                    for (long d : occ) {
                        out.add(new DrilldownRow(d, true, plannedRow(st, d, cents)));
                    }
                }
            } else if (!st.counterparty.isEmpty()
                    && CategoryBookingFilter.matches(st.counterparty, category, isMain)) {
                long cents = PlannedExpense.expenseCents(st.kind, st.amountCents);
                if (cents > 0) {
                    for (long d : occ) {
                        out.add(new DrilldownRow(d, true, plannedRow(st, d, cents)));
                    }
                }
            }
        }
        return out;
    }

    /** Geplante Zeile im Drilldown: komplett grau, Datum + „(geplant)"-Hinweis. */
    @SuppressLint("SetTextI18n")   // Daten mit Trennzeichen, kein Satz
    private View plannedRow(ScheduledTransaction st, long dateMs, long cents) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);

        TextView payee = new TextView(this);
        payee.setText(st.payee.isEmpty() ? st.name : st.payee);
        payee.setTextColor(0xFF9E9E9E);
        text.addView(payee);

        TextView sub = new TextView(this);
        java.util.Locale locale = getResources().getConfiguration().getLocales().get(0);
        sub.setText(st.account + " · " + DateFormats.date(dateMs) + " " + getString(R.string.category_planned_suffix));
        sub.setTextSize(12);
        sub.setTextColor(0xFF9E9E9E);
        text.addView(sub);

        row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView amount = new TextView(this);
        amount.setText("-" + money(cents));
        amount.setTextColor(0xFF9E9E9E);
        amount.setGravity(Gravity.END);
        row.addView(amount);
        return row;
    }

    /** Buchungszeile im Drilldown: Empfänger (fett) · Konto/Datum (grau) · Betrag (farbig, ggf. „Anteil"). */
    @SuppressLint("SetTextI18n")   // Daten mit Trennzeichen, kein Satz
    private View bookingRow(Booking b, long displayCents, boolean partial, boolean istSplit) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);

        TextView payee = new TextView(this);
        // Ohne Empfänger die Kategorie – oder „Split-Buchung", denn b.category trägt dann nur den
        // größten Teil und die Aufteilung wäre nicht zu sehen. Rangfolge: BookingLabel.
        payee.setText(BookingLabel.title(this, b, istSplit));
        text.addView(payee);

        TextView sub = new TextView(this);
        java.util.Locale locale = getResources().getConfiguration().getLocales().get(0);
        sub.setText(b.account + " · " + DateFormats.date(b.createdAt));
        sub.setTextSize(12);
        sub.setTextColor(0xFF9E9E9E);
        text.addView(sub);

        row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView amount = new TextView(this);
        String suffix = partial ? " " + getString(R.string.booking_row_partial_suffix) : "";
        amount.setText(money(displayCents) + suffix);
        amount.setTextColor(getColor(displayCents >= 0 ? R.color.income_green : R.color.expense_red));
        amount.setGravity(Gravity.END);
        row.addView(amount);
        return row;
    }

    /**
     * Zählt die im Fenster fälligen geplanten Abflüsse je Kategorie hinzu. Was ein Abfluß ist, entscheidet
     * {@link PlannedExpense} nach derselben Vorzeichenregel wie bei den Ist-Buchungen – auch geplante
     * Einzahlungen laufen deshalb mit, denn in ihnen steckt die Kapitalertragssteuer als negativer Teil.
     */
    private void addPlanned(Map<String, Cat> byCat, long fromMs, long toMs) {
        for (ScheduledTransaction st : scheduled) {
            if (st.kind == ScheduledTransaction.KIND_TRANSFER || st.nextDueMs <= 0) {
                continue;   // Umbuchungen bleiben außen vor
            }
            int n = ScheduleProjection.occurrences(st.nextDueMs, st.occurrence, st.occurrenceMultiplier,
                    st.endMs, fromMs, toMs - 1, MAX_OCC).size();
            if (n == 0) {
                continue;
            }
            if (st.split == 1) {
                List<ScheduledSplit> parts = splitsBySched.get(st.id);
                if (parts != null) {
                    for (ScheduledSplit p : parts) {
                        long cents = PlannedExpense.expenseCents(st.kind, p.amountCents);
                        if (!p.category.isEmpty() && cents > 0) {
                            cat(byCat, p.category).planned += n * cents;
                        }
                    }
                }
            } else if (!st.counterparty.isEmpty()) {
                long cents = PlannedExpense.expenseCents(st.kind, st.amountCents);
                if (cents > 0) {
                    cat(byCat, st.counterparty).planned += n * cents;
                }
            }
        }
    }

    private Cat cat(Map<String, Cat> byCat, String category) {
        Cat c = byCat.get(category);
        if (c == null) {
            c = new Cat(category);
            byCat.put(category, c);
        }
        return c;
    }

    private void buildPie(List<Cat> out, long total) {
        List<PieEntry> entries = new ArrayList<>();
        List<Integer> pieColors = new ArrayList<>();
        for (Cat c : out) {
            int base = colors.colorFor(c.category);
            if (c.actual > 0) {
                PieEntry e = new PieEntry(c.actual / 100f, c.category);
                e.setData(new SliceData(c.actual, false));
                entries.add(e);
                pieColors.add(base);
            }
            if (c.planned > 0) {
                PieEntry e = new PieEntry(c.planned / 100f, c.category);
                e.setData(new SliceData(c.planned, true));
                entries.add(e);
                pieColors.add(base);   // gleiche Farbe wie der Ist-Anteil, nur per Randlinie getrennt
            }
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
                SliceData d = e.getData() instanceof SliceData ? (SliceData) e.getData() : null;
                long cents = d != null ? d.cents : 0L;
                String suffix = d != null && d.planned ? " " + getString(R.string.category_planned_suffix) : "";
                pie.setCenterText(name + suffix + "\n" + money(cents));
                selectedCategory = name;
                showCategoryBookings(name);
            }

            @Override
            public void onNothingSelected() {
                pie.setCenterText(totalText);
                selectedCategory = null;
                showCategoryList();
            }
        });
        pie.invalidate();
    }

    /** Aufhellen für den Plan-Anteil: Grundfarbe nur leicht Richtung Weiß mischen (kleiner Unterschied). */
    private static int lighten(int color) {
        return ColorUtils.blendARGB(color, 0xFFFFFFFF, 0.20f);
    }

    /**
     * Listenzeile: Farbpunkt · Kategorie · Anteil in Prozent · Summe (rechts). Hat die Kategorie einen
     * Plan-Anteil, klappt ein Tipp die Aufteilung „Bereits gezahlt" / „Geplant" auf.
     */
    private View buildRow(Cat c, long total) {
        int base = colors.colorFor(c.category);
        boolean expandable = includePlanned && c.planned > 0;

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(8), 0, dp(8));

        header.addView(dot(base));

        TextView name = new TextView(this);
        name.setText(c.category);
        header.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView pct = new TextView(this);
        pct.setText(total > 0 ? Math.round(100.0 * c.total() / total) + " %" : "");
        pct.setTextSize(12);
        pct.setTextColor(0xFF9E9E9E);
        LinearLayout.LayoutParams pctLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pctLp.setMarginEnd(dp(10));
        header.addView(pct, pctLp);

        TextView amount = new TextView(this);
        amount.setText(money(c.total()));
        amount.setGravity(Gravity.END);
        amount.setTypeface(amount.getTypeface(), Typeface.BOLD);
        header.addView(amount, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView caret = new TextView(this);
        caret.setTextColor(0xFF9E9E9E);
        LinearLayout.LayoutParams caretLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        caretLp.setMarginStart(dp(8));
        caret.setLayoutParams(caretLp);
        caret.setText(expandable ? "▸" : "");   // ▸
        header.addView(caret);

        wrapper.addView(header);

        if (expandable) {
            LinearLayout detail = new LinearLayout(this);
            detail.setOrientation(LinearLayout.VERTICAL);
            detail.setPadding(dp(20), 0, 0, dp(6));
            detail.setVisibility(View.GONE);
            detail.addView(detailRow(base, getString(R.string.category_paid), c.actual));
            detail.addView(detailRow(lighten(base), getString(R.string.category_planned), c.planned));
            wrapper.addView(detail);

            header.setOnClickListener(v -> {
                boolean show = detail.getVisibility() != View.VISIBLE;
                detail.setVisibility(show ? View.VISIBLE : View.GONE);
                caret.setText(show ? "▾" : "▸");   // ▾ / ▸
            });
        }
        return wrapper;
    }

    /** Unterzeile im aufgeklappten Zustand: Farbpunkt · Bezeichnung · Betrag. */
    private View detailRow(int color, String label, long cents) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        row.addView(dot(color));

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(0xFF9E9E9E);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView amount = new TextView(this);
        amount.setText(money(cents));
        amount.setTextColor(0xFF9E9E9E);
        amount.setGravity(Gravity.END);
        row.addView(amount, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private View dot(int color) {
        View dot = new View(this);
        dot.setBackgroundColor(color);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dotLp.setMarginEnd(dp(10));
        dot.setLayoutParams(dotLp);
        return dot;
    }

    private String money(long cents) {
        return MoneyFormat.display(cents, Currencies.getDefault());
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
