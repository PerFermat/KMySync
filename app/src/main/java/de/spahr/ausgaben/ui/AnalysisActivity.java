package de.spahr.ausgaben.ui;

import android.content.res.Configuration;
import android.os.Bundle;
import android.view.MotionEvent;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.PlaceEntry;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.settings.PlacesStore;
import de.spahr.ausgaben.settings.DateFormats;

public class AnalysisActivity extends LocalizedActivity {

    public static final String EXTRA_FILTER_PAYEE = "filter_payee";
    public static final String EXTRA_FILTER_CATEGORY = "filter_category";
    public static final String EXTRA_FILTER_CATEGORY_MAIN = "filter_category_main";
    /** -1 = kein Typ ("Alle"), 0 = Ausgabe, 1 = Einnahme. */
    public static final String EXTRA_FILTER_CATEGORY_INCOME = "filter_category_income";
    public static final String EXTRA_FILTER_AMOUNT_FROM = "filter_amount_from";
    public static final String EXTRA_FILTER_AMOUNT_TO = "filter_amount_to";
    public static final String EXTRA_FILTER_DATE_FROM = "filter_date_from";
    public static final String EXTRA_FILTER_DATE_TO = "filter_date_to";
    public static final String EXTRA_VIEW_KEY = "view_key";

    private enum Granularity {DAY, WEEK, MONTH, YEAR}

    private static final int DEFAULT_BARS = 12;

    private Repository repository;
    private CombinedChart chart;
    private TextView textTotal;
    private FloatingActionButton fabScrollRight;
    private MaterialAutoCompleteTextView viewSelector;

    private List<Booking> allBookings = new ArrayList<>();
    private List<PlaceEntry> allPlaceEntries = new ArrayList<>();
    /** Depotübergreifende Zeitreihen-Bewertung (Depot-Linie in „Gesamt"/„Depot"); {@code null} bis geladen. */
    private de.spahr.ausgaben.db.DepotValuation depotValuation = null;
    private boolean bookingsLoaded = false;
    private boolean placesLoaded = false;
    /** Aktive (nicht geschlossene) Konten – nur diese sind als Einzel-/Orts-Sicht wählbar. */
    private java.util.Set<String> activeAccounts = null;
    /** Name der in der Schublade gewählten Kontengruppe; leer = keine Gruppe gewählt. */
    private String groupLabel = "";
    /** Konten dieser Gruppe, klein geschrieben – Depots sind darin enthalten. */
    private java.util.Set<String> groupAccounts = new java.util.HashSet<>();

    private Granularity granularity = Granularity.MONTH;
    private int lastIndex = 0;

    private String filterPayee = "";
    private String filterCategory = "";
    private boolean filterCategoryIsMain = false;
    /** Typ der gefilterten Kategorie (Einnahme/Ausgabe), {@code null} = kein Typ gewählt ("Alle"). */
    private Boolean filterCategoryIsIncome = null;
    private Long filterAmountFrom = null;
    private Long filterAmountTo = null;
    private Long filterDateFrom = null;
    private Long filterDateTo = null;
    /** Kategorie-Pfad → Typ (globaler Rückfall für Buchungszeilen ohne eigenen Typ, siehe Booking#categoryIsIncome). */
    private java.util.Map<String, Boolean> categoryTypes = new java.util.HashMap<>();

    private String defaultAccount = "";
    /** Trennt Konto und Ort im View-Key (nicht in Namen enthalten). */
    private static final String PLACE_SEP = "\u001f";

    private final List<String> viewKeys = new ArrayList<>();
    private final List<String> viewLabels = new ArrayList<>();
    private String viewKey = MainActivity.VIEW_TOTAL;

    /** Kategorie-Teile je Buchung (Splitbuchungen), damit der Kategorie-Filter alle Teile berücksichtigt. */
    private java.util.Map<Long, List<de.spahr.ausgaben.db.BookingSplit>> splitsByBooking =
            new java.util.HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_analysis);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        filterPayee = orEmpty(getIntent().getStringExtra(EXTRA_FILTER_PAYEE));
        filterCategory = orEmpty(getIntent().getStringExtra(EXTRA_FILTER_CATEGORY));
        filterCategoryIsMain = getIntent().getBooleanExtra(EXTRA_FILTER_CATEGORY_MAIN, false);
        int catIncomeExtra = getIntent().getIntExtra(EXTRA_FILTER_CATEGORY_INCOME, -1);
        filterCategoryIsIncome = catIncomeExtra < 0 ? null : catIncomeExtra == 1;
        long from = getIntent().getLongExtra(EXTRA_FILTER_AMOUNT_FROM, Long.MIN_VALUE);
        long to = getIntent().getLongExtra(EXTRA_FILTER_AMOUNT_TO, Long.MAX_VALUE);
        filterAmountFrom = from == Long.MIN_VALUE ? null : from;
        filterAmountTo = to == Long.MAX_VALUE ? null : to;
        long dFrom = getIntent().getLongExtra(EXTRA_FILTER_DATE_FROM, Long.MIN_VALUE);
        long dTo = getIntent().getLongExtra(EXTRA_FILTER_DATE_TO, Long.MAX_VALUE);
        filterDateFrom = dFrom == Long.MIN_VALUE ? null : dFrom;
        filterDateTo = dTo == Long.MAX_VALUE ? null : dTo;
        viewKey = orEmpty(getIntent().getStringExtra(EXTRA_VIEW_KEY));
        if (viewKey.isEmpty()) {
            viewKey = MainActivity.VIEW_TOTAL;
        }

        repository = new Repository(this);
        de.spahr.ausgaben.settings.SettingsStore settings =
                new de.spahr.ausgaben.settings.SettingsStore(this);
        defaultAccount = settings.getDefaultAccount();
        chart = findViewById(R.id.barChart);
        // Ohne Daten zeichnet MPAndroidChart sonst sein englisches „No chart data available."
        chart.setNoDataText(getString(R.string.analysis_empty));
        chart.setNoDataTextColor(getColor(R.color.grey_text));
        textTotal = findViewById(R.id.textTotal);
        fabScrollRight = findViewById(R.id.fabScrollRight);
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
            } else if (checkedId == R.id.btnMonth) {
                granularity = Granularity.MONTH;
            } else if (checkedId == R.id.btnYear) {
                granularity = Granularity.YEAR;
            }
            renderChart();
        });

        fabScrollRight.setOnClickListener(v -> {
            chart.moveViewToX(lastIndex);
            updateScrollRightVisibility();
        });

        repository.getCategoryTypes(types -> categoryTypes = types);
        repository.getAllBookings(result -> {
            allBookings = result;
            repository.getAllSplitsMap(m -> {
                splitsByBooking = m;
                bookingsLoaded = true;
                setupViewSelector();
                if (placesLoaded) renderChart();
            });
        });
        repository.getAllPlaceEntries(result -> {
            allPlaceEntries = result;
            placesLoaded = true;
            if (bookingsLoaded) renderChart();
        });
        // Depotwert-Zeitreihe (für die „Gesamt"- und „Depot"-Sicht); danach Sichten + Grafik auffrischen.
        repository.getDepotValuation(v -> {
            depotValuation = v;
            if (bookingsLoaded) setupViewSelector();
            if (bookingsLoaded && placesLoaded) renderChart();
        });
        // Aktive Konten laden → geschlossene Konten nicht als Einzel-/Orts-Sicht anbieten.
        repository.getAccountNames(names -> {
            activeAccounts = new java.util.HashSet<>(names);
            if (bookingsLoaded) setupViewSelector();
        });
        // Die in der Schublade gewählte Kontengruppe – sie kommt als eigene Sicht dazu.
        long groupId = settings.getAccountGroup();
        if (groupId > 0) {
            repository.getAccountGroup(groupId, g -> {
                groupLabel = g == null ? "" : g.name;
                if (bookingsLoaded) setupViewSelector();
            });
            repository.getAccountNamesInGroup(groupId, names -> {
                java.util.Set<String> set = new java.util.HashSet<>();
                for (String n : names) {
                    set.add(n.toLowerCase(java.util.Locale.ROOT));
                }
                groupAccounts = set;
                if (bookingsLoaded && placesLoaded) renderChart();
            });
        }
    }

    private void setupViewSelector() {
        viewKeys.clear();
        viewLabels.clear();
        // Gesamt (alle Konten + Depot)
        viewKeys.add(MainActivity.VIEW_TOTAL);
        viewLabels.add(getString(R.string.saldo_total));
        // Bei vorhandenem Depot zusätzlich „Gesamt ohne Depot" (nur Konten) und „Depot" (nur Depotwert).
        if (depotValuation != null && !depotValuation.isEmpty()) {
            viewKeys.add(MainActivity.VIEW_TOTAL_NODEPOT);
            viewLabels.add(getString(R.string.saldo_total_nodepot));
            viewKeys.add(MainActivity.VIEW_DEPOT_TOTAL);
            viewLabels.add(getString(R.string.saldo_depot_total));
        }
        // Die gewählte Kontengruppe – dieselbe Summe wie in der Saldo-Leiste der Hauptseite, hier im Verlauf.
        if (!groupLabel.isEmpty()) {
            viewKeys.add(MainActivity.VIEW_GROUP_TOTAL);
            viewLabels.add(groupLabel);
        }
        // Jedes aktive Konto (aus den vorhandenen Buchungen, stabile Reihenfolge); geschlossene Konten
        // erscheinen nicht als Einzel-/Orts-Sicht – nur die Gesamtsicht enthält ihren historischen Saldo.
        java.util.LinkedHashSet<String> accounts = new java.util.LinkedHashSet<>();
        for (Booking b : allBookings) {
            if (b.account != null && !b.account.isEmpty()
                    && activeAccounts != null && activeAccounts.contains(b.account)) {
                accounts.add(b.account);
            }
        }
        for (String acc : accounts) {
            viewKeys.add(MainActivity.VIEW_ACCOUNT_PREFIX + acc);
            viewLabels.add(acc);
        }
        // Orte je Konto: alle definierten Orte (inkl. Standardort) + „ohne Ort" (Rest).
        PlacesStore ps = new PlacesStore(this);
        for (String acc : accounts) {
            List<String> pl = ps.getPlaces(acc);
            for (String place : pl) {
                viewKeys.add(MainActivity.VIEW_PLACE_PREFIX + acc + PLACE_SEP + place);
                viewLabels.add(acc + " · " + place);
            }
            if (!pl.isEmpty()) {
                viewKeys.add(MainActivity.VIEW_NOPLACE + PLACE_SEP + acc);
                viewLabels.add(acc + " · " + getString(R.string.no_place));
            }
        }
        if (isFilterActive()) {
            viewKeys.add(MainActivity.VIEW_FILTERED);
            viewLabels.add(getString(R.string.saldo_filtered));
        }
        int idx = viewKeys.indexOf(viewKey);
        if (idx < 0) {
            // Depot- und Gruppen-Sicht erscheinen erst nach dem Laden von Bewertung bzw. Gruppe – die
            // gewünschte Sicht dann noch nicht zurücksetzen, nur vorübergehend „Gesamt" anzeigen (ein
            // späterer Aufruf korrigiert das).
            boolean sichtNochNichtGeladen = viewKey.equals(MainActivity.VIEW_TOTAL_NODEPOT)
                    || viewKey.equals(MainActivity.VIEW_DEPOT_TOTAL)
                    || viewKey.equals(MainActivity.VIEW_GROUP_TOTAL);
            if (!sichtNochNichtGeladen) {
                viewKey = MainActivity.VIEW_TOTAL;
            }
            idx = 0;
        }
        PickerAdapters.plainSearchable(viewSelector, viewLabels);
        viewSelector.setText(viewLabels.get(idx), false);
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

    private boolean isFilterActive() {
        return !filterPayee.isEmpty() || !filterCategory.isEmpty()
                || filterAmountFrom != null || filterAmountTo != null
                || filterDateFrom != null || filterDateTo != null;
    }

    // ---- Ereignisstrom je Sicht (Zeit, vorzeichenbehaftete Cent) ----

    private List<long[]> eventsForView() {
        List<long[]> events = new ArrayList<>();
        // „Depot" = reine Depotwert-Linie ohne Buchungs-Ereignisse (die Linie kommt aus der Bewertung).
        if (viewKey.equals(MainActivity.VIEW_DEPOT_TOTAL)) {
            return events;
        }
        if (viewKey.equals(MainActivity.VIEW_GROUP_TOTAL)) {
            // Kontengruppe: alle Buchungen ihrer Konten – dieselbe Regel wie MainActivity.inSelectedGroup().
            for (Booking b : allBookings) {
                if (b.account != null && groupAccounts.contains(b.account.toLowerCase(Locale.ROOT))) {
                    events.add(new long[]{b.createdAt, b.isIncome ? b.amountCents : -b.amountCents});
                }
            }
        } else if (viewKey.startsWith(MainActivity.VIEW_ACCOUNT_PREFIX)) {
            String account = viewKey.substring(MainActivity.VIEW_ACCOUNT_PREFIX.length());
            for (Booking b : allBookings) {
                if (b.account.equalsIgnoreCase(account)) {
                    events.add(new long[]{b.createdAt, b.isIncome ? b.amountCents : -b.amountCents});
                }
            }
        } else if (viewKey.startsWith(MainActivity.VIEW_PLACE_PREFIX)) {
            String rest = viewKey.substring(MainActivity.VIEW_PLACE_PREFIX.length());
            int i = rest.indexOf(PLACE_SEP);
            String acc = i < 0 ? "" : rest.substring(0, i);
            String place = i < 0 ? rest : rest.substring(i + PLACE_SEP.length());
            // Echter Ort = seine Bewegungen aus dem Journal (Buchungsbewegung/Umbuchen/Kassensturz).
            for (PlaceEntry e : allPlaceEntries) {
                if (e.account.equalsIgnoreCase(acc) && e.place.equals(place)) {
                    events.add(new long[]{e.createdAt, e.amountCents});
                }
            }
        } else if (viewKey.startsWith(MainActivity.VIEW_NOPLACE)) {
            // „ohne Ort" eines Kontos = Rest = alle Konto-Buchungen − alle Ort-Bewegungen des Kontos
            // (Σ echte Orte). So entspricht der Verlauf jederzeit Kontosaldo − Σ echte Orts-Salden.
            String acc = viewKey.length() > MainActivity.VIEW_NOPLACE.length()
                    ? viewKey.substring(MainActivity.VIEW_NOPLACE.length() + PLACE_SEP.length())
                    : defaultAccount;
            for (Booking b : allBookings) {
                if (acc.isEmpty() || b.account.equalsIgnoreCase(acc)) {
                    events.add(new long[]{b.createdAt, b.isIncome ? b.amountCents : -b.amountCents});
                }
            }
            for (PlaceEntry e : allPlaceEntries) {
                if (acc.isEmpty() || e.account.equalsIgnoreCase(acc)) {
                    events.add(new long[]{e.createdAt, -e.amountCents});
                }
            }
        } else { // TOTAL oder FILTERED (über Buchungen)
            boolean onlyFiltered = viewKey.equals(MainActivity.VIEW_FILTERED);
            for (Booking b : allBookings) {
                if (onlyFiltered && !matchesFilter(b)) {
                    continue;
                }
                long signed = b.isIncome ? b.amountCents : -b.amountCents;
                // Gefilterte Sicht mit Kategorie-Filter: Splitbuchung nur mit dem passenden Teilbetrag.
                if (onlyFiltered) {
                    signed = displaySignedForFilter(b, signed);
                }
                events.add(new long[]{b.createdAt, signed});
            }
        }
        Collections.sort(events, Comparator.comparingLong(a -> a[0]));
        return events;
    }

    private boolean matchesFilter(Booking b) {
        // Suchfeld: Empfänger, Notiz oder Kategorie (gemeinsame Logik mit der Buchungsliste).
        if (!de.spahr.ausgaben.db.BookingSearch.matches(b, filterPayee)) {
            return false;
        }
        if (!filterCategory.isEmpty() && !categoryMatchesBooking(b)) {
            return false;
        }
        // Vorzeichenbehaftet wie in der Buchungsliste: Ausgaben negativ, Einnahmen positiv.
        long signed = b.isIncome ? b.amountCents : -b.amountCents;
        if (filterAmountFrom != null && signed < filterAmountFrom) {
            return false;
        }
        if (filterAmountTo != null && signed > filterAmountTo) {
            return false;
        }
        if (filterDateFrom != null && b.createdAt < filterDateFrom) {
            return false;
        }
        return filterDateTo == null || b.createdAt <= filterDateTo;
    }

    /**
     * Bei Kategorie-Filter nur den Teilbetrag der gewählten Kategorie einer Splitbuchung; sonst
     * {@code full}. Zusätzlich typgeprüft (Einnahme/Ausgabe) für gleichnamige Kategorien unterschiedlichen
     * Typs, siehe {@link #categoryMatchesBooking}.
     */
    private long displaySignedForFilter(Booking b, long full) {
        if (filterCategory.isEmpty()) {
            return full;
        }
        return de.spahr.ausgaben.db.CategoryBookingFilter.displaySigned(b, splitsByBooking,
                filterCategory, filterCategoryIsMain, full, categoryTypes, filterCategoryIsIncome);
    }

    /**
     * Treffer, wenn die (Haupt-)Kategorie oder eine Teilkategorie einer Splitbuchung passt – zusätzlich
     * typgeprüft ({@link #filterCategoryIsIncome}): kMyMoney erlaubt dieselbe Kategorie-Bezeichnung
     * unabhängig im Einnahme- und im Ausgabe-Baum, maßgeblich ist der Typ der jeweiligen Zeile selbst.
     */
    private boolean categoryMatchesBooking(Booking b) {
        return de.spahr.ausgaben.db.CategoryBookingFilter.matchesBooking(b, splitsByBooking,
                filterCategory, filterCategoryIsMain, categoryTypes, filterCategoryIsIncome);
    }

    /**
     * Depotwert zum Stichtag für die gerade gewählte Sicht: in der Gruppen-Sicht nur die Depots der
     * Gruppe, sonst alle. {@code 0}, solange die Bewertung noch nicht geladen ist.
     */
    private long depotCentsAt(long t) {
        if (depotValuation == null) {
            return 0;
        }
        return viewKey.equals(MainActivity.VIEW_GROUP_TOTAL)
                ? depotValuation.valueCentsAt(t, groupAccounts)
                : depotValuation.valueCentsAt(t);
    }

    private void renderChart() {
        // Depot-Beteiligung der Sicht: „Gesamt" enthält den Depotwert zusätzlich, „Depot" zeigt nur ihn.
        // Die Kontengruppe zählt ihre eigenen Depots mit – wie die Gruppensumme in der Saldo-Leiste.
        // Steht heute nichts mehr darin, bleibt die Linie weg; ein leerer Strich erklärt nichts.
        boolean depotReady = depotValuation != null && !depotValuation.isEmpty();
        boolean groupWithDepot = viewKey.equals(MainActivity.VIEW_GROUP_TOTAL) && depotReady
                && depotCentsAt(System.currentTimeMillis()) > 0;
        boolean includeDepot = (viewKey.equals(MainActivity.VIEW_TOTAL) || groupWithDepot) && depotReady;
        boolean depotOnly = viewKey.equals(MainActivity.VIEW_DEPOT_TOTAL) && depotReady;
        boolean useDepot = includeDepot || depotOnly;

        List<long[]> events = eventsForView();

        long total = 0;
        for (long[] e : events) {
            total += e[1];
        }
        if (useDepot) {
            total += depotCentsAt(System.currentTimeMillis());
        }
        textTotal.setText(getString(R.string.analysis_total, formatEuro(total)));

        if (events.isEmpty() && !depotOnly) {
            chart.clear();
            chart.invalidate();
            fabScrollRight.hide();
            return;
        }

        // Netto + Vorhandensein je Periode
        Map<Long, Long> netByPeriod = new HashMap<>();
        Set<Long> hasEvent = new HashSet<>();
        long minMs = Long.MAX_VALUE;
        long maxMs = Long.MIN_VALUE;
        for (long[] e : events) {
            long ps = periodStart(e[0]);
            Long curVal = netByPeriod.get(ps);
            netByPeriod.put(ps, (curVal == null ? 0L : curVal) + e[1]);
            hasEvent.add(ps);
            if (ps < minMs) minMs = ps;
            if (ps > maxMs) maxMs = ps;
        }
        // Depot-Sichten: Zeitraum um die Depot-Historie (frühester Kauf … heute) erweitern, damit die
        // Depot-Linie ihren gesamten Verlauf bis zum aktuellen Wert zeigt.
        if (useDepot) {
            long first = depotValuation.firstTxMs();
            if (first > 0) {
                long fps = periodStart(first);
                if (fps < minMs) minMs = fps;
            }
            long nowPs = periodStart(System.currentTimeMillis());
            if (nowPs > maxMs) maxMs = nowPs;
        }
        if (minMs == Long.MAX_VALUE || maxMs == Long.MIN_VALUE) {
            chart.clear();
            chart.invalidate();
            fabScrollRight.hide();
            return;
        }

        // Perioden aufbauen. Bei sehr feinem Raster (Tag) über viele Jahre nur die JÜNGSTEN Perioden
        // behalten (Fenster endet heute). Sonst schneidet die Kappung die letzten Jahre inkl. heute ab und
        // die letzte gezeigte Periode nimmt den gesamten heutigen Depotwert auf einmal auf (großer Sprung).
        final int maxBars = 5000;
        List<Long> allPeriods = new ArrayList<>();
        Calendar cur = Calendar.getInstance();
        cur.setTimeInMillis(minMs);
        int guard = 0;
        while (cur.getTimeInMillis() <= maxMs && guard++ < 40000) {
            allPeriods.add(cur.getTimeInMillis());
            advance(cur);
        }
        int drop = Math.max(0, allPeriods.size() - maxBars);
        List<Long> periods = new ArrayList<>(allPeriods.subList(drop, allPeriods.size()));

        List<BarEntry> barEntries = new ArrayList<>();
        List<Integer> barColors = new ArrayList<>();
        List<Entry> lineEntries = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        int green = getColor(R.color.income_green);
        int red = getColor(R.color.expense_red);
        // Depot-Balken (nur „Depot"-Sicht) = Kursgewinn/-verlust + Käufe − Dividenden − Verkäufe
        // = (Depotwert am Periodenende − am Anfang) − Dividenden der Periode.
        boolean depotGross = depotOnly && new de.spahr.ausgaben.settings.SettingsStore(this).isDividendGross();
        // Laufenden Saldo mit dem Netto der weggelassenen (älteren) Perioden vorbelegen, damit die Linie im
        // Fenster beim korrekten Kontostand startet.
        long running = 0;
        for (int k = 0; k < drop; k++) {
            Long dn = netByPeriod.get(allPeriods.get(k));
            if (dn != null) running += dn;
        }
        // Depotwert unmittelbar vor Fensterbeginn – Basis für die Wertänderung der ersten gezeigten Periode.
        long prevDepotCents = (useDepot && !periods.isEmpty())
                ? depotCentsAt(periods.get(0) - 1) : 0;
        for (int i = 0; i < periods.size(); i++) {
            long ps = periods.get(i);
            labels.add(label(ps));
            Long net = netByPeriod.get(ps);
            long netVal = net == null ? 0L : net;
            running += netVal;

            long depotCents = 0;
            long depotDelta = 0;
            long nextBoundary = (i + 1 < periods.size())
                    ? periods.get(i + 1) : System.currentTimeMillis() + 1;
            if (useDepot) {
                // Depotwert am Ende der Periode (für die letzte Periode: aktueller Wert).
                depotCents = depotCentsAt(nextBoundary - 1);
                depotDelta = depotCents - prevDepotCents;
            }

            long bar;
            boolean drawBar;
            if (depotOnly) {
                bar = depotDelta - depotValuation.dividendCentsIn(ps, nextBoundary, depotGross);
                drawBar = bar != 0;
            } else if (includeDepot) {
                // „Gesamt" = Buchungs-Netto + Wertentwicklung des Depots (Δ Depotwert) je Periode.
                bar = netVal + depotDelta;
                drawBar = bar != 0;
            } else {
                bar = netVal;
                drawBar = hasEvent.contains(ps);
            }
            if (drawBar) {
                barEntries.add(new BarEntry(i, bar / 100f));
                barColors.add(bar >= 0 ? green : red);
            }

            prevDepotCents = depotCents;
            lineEntries.add(new Entry(i, (running + depotCents) / 100f));
        }
        lastIndex = periods.size() - 1;

        int chartText = getColor(R.color.chart_text);

        BarDataSet barSet = new BarDataSet(barEntries, "");
        barSet.setColors(barColors);
        barSet.setValueTextColor(chartText);
        barSet.setValueTextSize(11f * de.spahr.ausgaben.settings.FontScale.factor());
        BarData barData = new BarData(barSet);
        barData.setBarWidth(0.6f);

        LineDataSet lineSet = new LineDataSet(lineEntries, "");
        int lineColor = getColor(R.color.chart_line);
        lineSet.setColor(lineColor);
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

        // X frei zoombar (Balkenanzahl per Geste), Anfangsansicht ≈ DEFAULT_BARS; Y frei zoombar.
        chart.setVisibleXRangeMinimum(2f);
        chart.setVisibleXRangeMaximum(Math.max(2f, periods.size()));
        chart.fitScreen();
        if (periods.size() > DEFAULT_BARS) {
            chart.zoom((float) periods.size() / DEFAULT_BARS, 1f, 0f, 0f);
        }
        chart.moveViewToX(lastIndex);
        chart.invalidate();
        updateScrollRightVisibility();
    }

    private void setupChart() {
        chart.getDescription().setEnabled(false);
        chart.getAxisRight().setEnabled(false);
        chart.getLegend().setEnabled(false);
        // Zoom per Fingergeste: horizontal = Balkenanzahl (X), vertikal = Y-Achse. Unabhängig (kein Pinch-Both).
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
                updateScrollRightVisibility();
            }
            @Override public void onChartLongPressed(MotionEvent me) { }
            @Override public void onChartDoubleTapped(MotionEvent me) { }
            @Override public void onChartSingleTapped(MotionEvent me) { }
            @Override public void onChartFling(MotionEvent e1, MotionEvent e2, float vx, float vy) { }
            @Override public void onChartScale(MotionEvent me, float sx, float sy) { }
            @Override public void onChartTranslate(MotionEvent me, float dx, float dy) {
                updateScrollRightVisibility();
            }
        });
    }

    private void updateScrollRightVisibility() {
        chart.post(() -> {
            if (chart.getData() == null) {
                fabScrollRight.hide();
                return;
            }
            boolean atRight = chart.getHighestVisibleX() >= lastIndex - 0.5f;
            if (atRight) {
                fabScrollRight.hide();
            } else {
                fabScrollRight.show();
            }
        });
    }

    // ---- Perioden-Hilfen ----

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
            case YEAR:
                c.set(Calendar.DAY_OF_YEAR, 1);
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
            case YEAR:
                c.add(Calendar.YEAR, 1);
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
            case YEAR:
                return String.valueOf(c.get(Calendar.YEAR));
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
        return de.spahr.ausgaben.settings.MoneyFormat.display(signedCents, viewCurrency());
    }

    /** Währungskennzeichen der aktuellen Sicht: bei Konto-/Ort-Sichten die Konto-Währung, sonst Standard. */
    private String viewCurrency() {
        String acc = null;
        if (viewKey.startsWith(MainActivity.VIEW_ACCOUNT_PREFIX)) {
            acc = viewKey.substring(MainActivity.VIEW_ACCOUNT_PREFIX.length());
        } else if (viewKey.startsWith(MainActivity.VIEW_PLACE_PREFIX)) {
            String rest = viewKey.substring(MainActivity.VIEW_PLACE_PREFIX.length());
            int i = rest.indexOf(PLACE_SEP);
            acc = i < 0 ? rest : rest.substring(0, i);
        } else if (viewKey.startsWith(MainActivity.VIEW_NOPLACE)) {
            acc = viewKey.length() > MainActivity.VIEW_NOPLACE.length()
                    ? viewKey.substring(MainActivity.VIEW_NOPLACE.length() + PLACE_SEP.length())
                    : defaultAccount;
        }
        return acc == null || acc.isEmpty()
                ? de.spahr.ausgaben.settings.Currencies.getDefault()
                : de.spahr.ausgaben.settings.Currencies.forAccount(acc);
    }
}
