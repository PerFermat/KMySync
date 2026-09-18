package de.spahr.ausgaben.ui;

import android.graphics.Color;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.BookingSplit;
import de.spahr.ausgaben.db.Budget;
import de.spahr.ausgaben.db.CategoryBookingFilter;
import de.spahr.ausgaben.db.CategorySum;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.settings.Currencies;
import de.spahr.ausgaben.settings.MoneyFormat;
import de.spahr.ausgaben.settings.SettingsStore;
import de.spahr.ausgaben.settings.DateFormats;

/**
 * Budgetplanung: je Kategorie Ist vs. Soll mit farbigem Fortschrittsbalken (grün = im Plan, rot = daneben);
 * umschaltbar Jahres-/Monatssicht und nur Haupt- / mit Unterkategorien. Soll stammt aus KMyMoney (read-only)
 * oder wird app-intern aus dem Verlauf berechnet und ist dann manuell editierbar.
 */
public class BudgetActivity extends LocalizedActivity {

    private Repository repository;
    private SettingsStore settings;
    private LinearLayout container;

    private int year;
    private boolean monthView = false;      // false = Jahr, true = Monat
    private boolean includeSubs = false;    // false = nur Haupt, true = mit Unter
    private int monthOffset = 0;            // Monatssicht: 0 = aktueller Monat, ±n = vor/zurück (Wischgeste)
    private int displayYear;                // Jahr des aktuell angezeigten Zeitraums (folgt monthOffset)
    private int displayMonth;               // 1–12 = angezeigter Monat (Monatssicht), 0 = Jahressicht
    private long periodFromMs;              // Zeitraum-Grenzen des angezeigten Monats/Jahres – für den
    private long periodToMs;                // Buchungs-Drilldown beim Aufklappen einer Kategoriezeile

    private final List<CategorySum> actuals = new ArrayList<>();
    /** Ist-Summen der letzten 2 Jahre – für Kategorie-Typ und Aktivitätsfilter. */
    private final List<CategorySum> recentActuals = new ArrayList<>();
    private String budgetSource;            // null = kein Budget vorhanden
    private final List<Budget> budgetLines = new ArrayList<>();
    /** Kategorie-Pfad → Typ ({@code true} = Einnahme) aus der Datei; verlässliche Einordnung. */
    private final Map<String, Boolean> categoryTypes = new java.util.HashMap<>();
    private double elapsed = 0;
    private GestureDetector swipeDetector;   // Monats-Wischgeste, ganzflächig via dispatchTouchEvent

    /** Toleranzband für die Balkenfarbe (gegen Flackern am Grenzwert). */
    private static final double PROGRESS_TOL = 0.05;
    /** Kategorie-Pfad → historische Magnitude je Bucket (Index = Bucket−1; Tag im Monat bzw. Monat). */
    private final Map<String, long[]> histoByPath = new java.util.HashMap<>();
    /** Bucket-Schwelle „bis jetzt" für den erwarteten Fortschritt (aus elapsed). */
    private int histoThreshold;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_budget);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        repository = new Repository(this);
        settings = new SettingsStore(this);
        container = findViewById(R.id.budgetContainer);

        year = Calendar.getInstance().get(Calendar.YEAR);

        MaterialButtonToggleGroup toggleView = findViewById(R.id.toggleView);
        toggleView.check(R.id.btnYearView);
        toggleView.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            monthView = checkedId == R.id.btnMonthView;
            monthOffset = 0;   // Monatssicht startet immer beim aktuellen Monat
            reload();
        });

        // Monatssicht: Wischen nach rechts = Monat davor, nach links = Monat danach.
        // Über dispatchTouchEvent aktiv auf dem ganzen Bildschirm (auch über Zeilen mit Long-Press).
        swipeDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (!monthView || e1 == null || e2 == null) {
                    return false;
                }
                float dx = e2.getX() - e1.getX();
                if (Math.abs(dx) > Math.abs(e2.getY() - e1.getY())
                        && Math.abs(dx) > dp(60) && Math.abs(vx) > dp(60)) {
                    monthOffset += dx > 0 ? -1 : 1;
                    reload();
                    return true;
                }
                return false;
            }
        });

        MaterialButtonToggleGroup toggleScope = findViewById(R.id.toggleScope);
        toggleScope.check(R.id.btnScopeMain);
        toggleScope.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            includeSubs = checkedId == R.id.btnScopeAll;
            render();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    /** Wischgeste ganzflächig auswerten, danach normal weiterreichen (Scrollen/Klicks bleiben). */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (swipeDetector != null) {
            swipeDetector.onTouchEvent(ev);
        }
        return super.dispatchTouchEvent(ev);
    }

    // ---- Laden ----

    private void reload() {
        long start, end;
        Calendar c = Calendar.getInstance();
        if (monthView) {
            c.set(Calendar.DAY_OF_MONTH, 1);
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            c.add(Calendar.MONTH, monthOffset);   // per Wischgeste vor/zurück
            start = c.getTimeInMillis();
            displayYear = c.get(Calendar.YEAR);
            displayMonth = c.get(Calendar.MONTH) + 1;
            c.add(Calendar.MONTH, 1);
            end = c.getTimeInMillis();
        } else {
            c.clear();
            c.set(Calendar.YEAR, year);
            start = c.getTimeInMillis();
            displayYear = year;
            displayMonth = 0;
            c.add(Calendar.YEAR, 1);
            end = c.getTimeInMillis();
        }
        periodFromMs = start;
        periodToMs = end;
        long now = System.currentTimeMillis();
        elapsed = now <= start ? 0 : (now >= end ? 1 : (double) (now - start) / (end - start));

        Calendar rc = Calendar.getInstance();
        rc.add(Calendar.YEAR, -2);
        final long recentStart = rc.getTimeInMillis();
        final long recentEnd = now;
        final long fStart = start, fEnd = end;
        final int budgetYear = displayYear;
        final boolean forMonth = monthView;
        repository.getCategoryTiming(forMonth, timing -> {
            buildHistogram(timing, forMonth);
            repository.getCategoryTypes(types -> {
            categoryTypes.clear();
            categoryTypes.putAll(types);
            repository.getCategoryActuals(fStart, fEnd, list -> {
                actuals.clear();
                actuals.addAll(list);
                repository.getCategoryActuals(recentStart, recentEnd, recent -> {
                    recentActuals.clear();
                    recentActuals.addAll(recent);
                    repository.getBudget(budgetYear, yb -> {
                        budgetSource = yb.source;
                        budgetLines.clear();
                        budgetLines.addAll(yb.lines);
                        if (budgetSource == null && settings.isBudgetInternal()) {
                            // Automatisch aus dem Verlauf berechnen und erneut laden.
                            repository.computeBudgetFromHistory(budgetYear, this::reload);
                            return;
                        }
                        render();
                    });
                });
            });
            });
        });
    }

    /** Baut aus dem Verlaufs-Histogramm die Magnitude je Kategorie-Pfad und Bucket (Index = Bucket−1). */
    private void buildHistogram(List<de.spahr.ausgaben.db.CategoryBucket> timing, boolean monthBuckets) {
        histoByPath.clear();
        int size = monthBuckets ? 31 : 12;
        for (de.spahr.ausgaben.db.CategoryBucket cb : timing) {
            if (cb.category == null || cb.category.isEmpty() || cb.bucket < 1 || cb.bucket > size) {
                continue;
            }
            long[] arr = histoByPath.get(cb.category);
            if (arr == null) {
                arr = new long[size];
                histoByPath.put(cb.category, arr);
            }
            arr[cb.bucket - 1] += cb.total;
        }
    }

    // ---- Rendern ----

    private void render() {
        container.removeAllViews();

        if (budgetSource == null) {
            renderEmptyState();
            return;
        }

        if (monthView) {
            addMonthNavHeader();
        }
        // Erwarteter-Fortschritt-Schwelle: Bucket bis „jetzt" (elapsed) im aktuellen Zeitraum.
        int maxBucket = monthView ? 31 : 12;
        histoThreshold = elapsed <= 0 ? 0
                : (elapsed >= 1 ? maxBucket : (int) Math.round(elapsed * maxBucket));
        List<Cat> cats = buildCats();
        String currency = Currencies.getDefault();
        renderSection(cats, true, getString(R.string.budget_income_header), currency);
        renderSection(cats, false, getString(R.string.budget_expense_header), currency);
    }

    /**
     * Erwarteter Ausschöpfungsanteil bis jetzt aus dem Verlauf der angegebenen Kategorie-Pfade;
     * {@code < 0} → keine Historie → Aufrufer nutzt {@link #elapsed} (linearer Rückfall).
     */
    private double expectedFor(java.util.Collection<String> paths) {
        int size = monthView ? 31 : 12;
        long[] combined = new long[size];
        boolean any = false;
        for (String p : paths) {
            long[] h = histoByPath.get(p);
            if (h != null) {
                any = true;
                for (int i = 0; i < size && i < h.length; i++) {
                    combined[i] += h[i];
                }
            }
        }
        if (!any) {
            return -1;
        }
        return de.spahr.ausgaben.db.BudgetProgress.expectedFraction(combined, histoThreshold);
    }

    /**
     * Kopfzeile der Monatssicht: zentriert der angezeigte Monat, links (grau) der vorige, rechts (grau)
     * der nächste. Tippen auf links/rechts blättert – wie die Wischgeste – einen Monat zurück/vor.
     */
    private void addMonthNavHeader() {
        Calendar cur = Calendar.getInstance();
        cur.set(Calendar.DAY_OF_MONTH, 1);
        cur.add(Calendar.MONTH, monthOffset);
        Calendar prev = (Calendar) cur.clone();
        prev.add(Calendar.MONTH, -1);
        Calendar next = (Calendar) cur.clone();
        next.add(Calendar.MONTH, 1);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(0, dp(4), 0, dp(8));

        TextView left = monthLabel(prev, false);
        left.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        left.setOnClickListener(v -> {
            monthOffset--;
            reload();
        });
        TextView center = monthLabel(cur, true);
        center.setGravity(Gravity.CENTER);
        TextView right = monthLabel(next, false);
        right.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        right.setOnClickListener(v -> {
            monthOffset++;
            reload();
        });

        header.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(center, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        container.addView(header);
    }

    /** Monatslabel; {@code center} = angezeigter Monat (fett, mit Jahr), sonst grauer Monatsname. */
    private TextView monthLabel(Calendar cal, boolean center) {
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        TextView tv = new TextView(this);
        tv.setText(new SimpleDateFormat(center ? "MMMM yyyy" : "MMMM", locale).format(cal.getTime()));
        if (center) {
            tv.setTextSize(18);
            tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        } else {
            tv.setTextColor(Color.GRAY);
        }
        return tv;
    }

    private void renderEmptyState() {
        TextView hint = new TextView(this);
        hint.setText(R.string.budget_empty);
        hint.setPadding(0, dp(16), 0, dp(16));
        container.addView(hint);

        MaterialButton compute = new MaterialButton(this);
        compute.setText(R.string.budget_compute);
        compute.setOnClickListener(v ->
                repository.computeBudgetFromHistory(displayYear, this::reload));
        container.addView(compute);

        if (settings.isKmyMode()) {
            MaterialButton imp = new MaterialButton(this,
                    null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            imp.setText(R.string.budget_import);
            imp.setOnClickListener(v ->
                    BudgetImportFlow.run(this, settings, repository, displayYear, this::reload));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            container.addView(imp, lp);
        }
    }

    /** Kategorie-Typ + Kategorie-Pfad + aktueller Ist (vorzeichenbehaftet) + Soll. */
    private static final class Cat {
        String categoryPath;
        boolean isIncome;
        long ist;          // aktueller Zeitraum, vorzeichenbehaftet Richtung Kategorietyp
        long displaySoll;  // Jahr bzw. /12
        long annualSoll;
    }

    /**
     * Baut je Kategorie (und – bei einer Namenskollision zwischen gleichnamiger Einnahme- und
     * Ausgabekategorie in kMyMoney – je Typ <b>zwei</b> Einträge) den Typ, den aktuellen Ist und das Soll.
     * Der Typ kommt bevorzugt aus dem Kategorietyp je Zeile ({@link CategorySum#catIsIncome}, siehe
     * {@link #sums}); nur für Zeilen ohne eigenen Typ (NULL, vor der Kategorietyp-je-Zeile-Migration)
     * greift wie bisher der globale Rückfall über {@link #categoryTypes} bzw. den Typ der Budgetzeile.
     * Eine Einnahme auf einer Ausgabekategorie (z. B. Erstattung) mindert dort den Ist, kippt die
     * Kategorie aber nicht. Kategorien ohne ermittelbaren Typ werden übersprungen. Es zählen nur
     * Kategorien mit Zahlungen in den letzten 2 Jahren; ein negativer Netto-Ist wird auf 0 gesetzt.
     *
     * <p>Soll: Jahressicht = Jahreswert (Summe der Monatszeilen). Monatssicht = der Wert des aktuellen
     * Monats bei monatsgenauen Budgets ({@code month=1..12}), sonst Jahreswert/12.</p>
     */
    private List<Cat> buildCats() {
        Map<String, Map<Boolean, Long>> period = sums(actuals);
        Map<String, Map<Boolean, Long>> recent = sums(recentActuals);

        // Budgetzeilen je Kategorie einsammeln: Jahres-Soll (month=0) bzw. Monatswerte (month=1..12).
        Map<String, Long> annualByCat = new java.util.HashMap<>();
        Map<String, long[]> monthlyByCat = new java.util.HashMap<>();
        Map<String, Boolean> budgetTypeByCat = new java.util.HashMap<>();
        for (Budget b : budgetLines) {
            budgetTypeByCat.put(b.category, b.isIncome);
            if (b.month >= 1 && b.month <= 12) {
                long[] m = monthlyByCat.get(b.category);
                if (m == null) {
                    m = new long[12];
                    monthlyByCat.put(b.category, m);
                }
                m[b.month - 1] += b.amountCents;
            } else {
                annualByCat.put(b.category, b.amountCents);
            }
        }
        int curMonth = monthView ? displayMonth : 1;   // angezeigter Monat (folgt der Wischgeste)

        List<Cat> cats = new ArrayList<>();
        for (Map.Entry<String, Map<Boolean, Long>> e : recent.entrySet()) {
            String cat = e.getKey();   // in der 2-Jahres-Liste = Zahlung im Zeitraum vorhanden
            Map<Boolean, Long> recentByType = e.getValue();
            // Ist-Betrag kommt aus dem aktuell angezeigten Zeitraum (period), recent dient nur der
            // Typerkennung ("welche Typen kommen für diese Kategorie überhaupt vor").
            Map<Boolean, Long> periodByType = period.containsKey(cat)
                    ? period.get(cat) : java.util.Collections.emptyMap();
            List<Boolean> knownTypes = new ArrayList<>();
            if (recentByType.containsKey(Boolean.TRUE)) {
                knownTypes.add(Boolean.TRUE);
            }
            if (recentByType.containsKey(Boolean.FALSE)) {
                knownTypes.add(Boolean.FALSE);
            }

            if (knownTypes.isEmpty()) {
                // Nur unbekannte (NULL-)Zeilen: wie bisher global auflösen, Ist = Summe aller
                // period-Zeilen dieser Kategorie (es gibt ja keine Typtrennung für sie).
                Boolean isInc = categoryTypes.get(cat);
                if (isInc == null) {
                    isInc = budgetTypeByCat.get(cat);   // Rückfall auf den kmy-Typ der Budgetzeile
                }
                if (isInc == null) {
                    continue;   // strikt: nur Kategorien mit ermittelbarem Typ
                }
                long net = 0;
                for (long v : periodByType.values()) {
                    net += v;
                }
                cats.add(buildCat(cat, isInc, net, curMonth, monthlyByCat, annualByCat));
                continue;
            }

            // Echte Kollision (beide Typen mit eigenen Zeilen) → zwei getrennte Einträge; sonst den
            // NULL-Eimer (falls vorhanden) dem einzigen bekannten Typ zuschlagen.
            Boolean globalType = categoryTypes.get(cat);
            for (Boolean type : knownTypes) {
                boolean foldNull = knownTypes.size() == 1 || (globalType != null && globalType.equals(type));
                long net = periodByType.containsKey(type) ? periodByType.get(type) : 0;
                if (foldNull && periodByType.containsKey(null)) {
                    net += periodByType.get(null);
                }
                cats.add(buildCat(cat, type, net, curMonth, monthlyByCat, annualByCat));
            }
        }
        return cats;
    }

    /** Baut einen einzelnen {@link Cat}-Eintrag für {@code cat}/{@code isInc} aus dem Ist-Netto {@code net}. */
    private Cat buildCat(String cat, boolean isInc, long net,
                         int curMonth, Map<String, long[]> monthlyByCat, Map<String, Long> annualByCat) {
        Cat c = new Cat();
        c.categoryPath = cat;
        c.isIncome = isInc;
        c.ist = de.spahr.ausgaben.db.BudgetMath.ist(isInc, net);   // Typ-Richtung, Clamp auf 0
        long[] monthly = monthlyByCat.get(cat);
        if (monthly != null) {
            long annual = 0;
            for (long v : monthly) {
                annual += v;
            }
            c.annualSoll = annual;
            c.displaySoll = monthView ? monthly[curMonth - 1] : annual;   // Monat = konkreter Monatswert
        } else {
            c.annualSoll = annualByCat.containsKey(cat) ? annualByCat.get(cat) : 0;
            c.displaySoll = monthView ? Math.round(c.annualSoll / 12.0) : c.annualSoll;
        }
        return c;
    }

    /**
     * Vorzeichenbehafteter Netto-Zufluss je Kategorie ({@code +} = rein, {@code −} = raus), je
     * Kategorietyp getrennt (innerer Schlüssel {@code TRUE}/{@code FALSE}/{@code null} = unbekannt) –
     * damit gleichnamige Einnahme-/Ausgabekategorien (kMyMoney erlaubt das) nicht vermischt werden.
     */
    private Map<String, Map<Boolean, Long>> sums(List<CategorySum> list) {
        Map<String, Map<Boolean, Long>> m = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (CategorySum s : list) {
            if (s.category == null || s.category.isEmpty()) {
                continue;
            }
            Map<Boolean, Long> byType = m.computeIfAbsent(s.category, k -> new java.util.HashMap<>());
            Long v = byType.get(s.catIsIncome);
            byType.put(s.catIsIncome, (v == null ? 0L : v) + s.total);
        }
        return m;
    }

    /** Sichtbar, wenn ein aktueller Ist vorliegt oder das Jahres-Soll ≥ 10 € ist. */
    private boolean visible(long ist, long annualSoll) {
        return ist != 0 || annualSoll >= 1000;
    }

    private static String mainOf(String cat) {
        int i = cat.indexOf(':');
        return i < 0 ? cat : cat.substring(0, i);
    }

    /** Ein Abschnitt (Einnahmen bzw. Ausgaben) mit Überschrift und aggregierten Kategoriezeilen. */
    private void renderSection(List<Cat> cats, boolean income, String header, String currency) {
        // Haupt → [ist, displaySoll, annualSoll]; Haupt → (Unter-Vollpfad → [ist, displaySoll, annualSoll]).
        Map<String, long[]> mainTot = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, Map<String, long[]>> subTot = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        // Haupt → alle zugehörigen Kategorie-Pfade dieses Abschnitts (für den erwarteten Fortschritt).
        Map<String, List<String>> pathsByMain = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        for (Cat c : cats) {
            if (c.isIncome != income) {
                continue;
            }
            String cat = c.categoryPath;
            String main = mainOf(cat);
            long[] mv = mainTot.get(main);
            if (mv == null) {
                mv = new long[3];
                mainTot.put(main, mv);
            }
            mv[0] += c.ist;
            mv[1] += c.displaySoll;
            mv[2] += c.annualSoll;
            List<String> mp = pathsByMain.get(main);
            if (mp == null) {
                mp = new ArrayList<>();
                pathsByMain.put(main, mp);
            }
            mp.add(cat);
            if (cat.contains(":")) {
                Map<String, long[]> subs = subTot.get(main);
                if (subs == null) {
                    subs = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                    subTot.put(main, subs);
                }
                subs.put(cat, new long[]{c.ist, c.displaySoll, c.annualSoll});
            }
        }

        boolean headerAdded = false;
        for (Map.Entry<String, long[]> e : mainTot.entrySet()) {
            String main = e.getKey();
            long[] mv = e.getValue();

            List<Map.Entry<String, long[]>> visibleSubs = new ArrayList<>();
            if (includeSubs) {
                Map<String, long[]> subs = subTot.get(main);
                if (subs != null) {
                    for (Map.Entry<String, long[]> se : subs.entrySet()) {
                        if (visible(se.getValue()[0], se.getValue()[2])) {
                            visibleSubs.add(se);
                        }
                    }
                }
            }

            if (!visible(mv[0], mv[2]) && visibleSubs.isEmpty()) {
                continue;
            }

            if (!headerAdded) {
                addHeader(header);
                headerAdded = true;
            }
            List<String> mainPaths = pathsByMain.get(main);
            double mainExpected = expectedFor(mainPaths == null ? java.util.Collections.emptyList() : mainPaths);
            addRow(main, main, income, mv[0], mv[1], true, currency,
                    mainExpected >= 0 ? mainExpected : elapsed, !visibleSubs.isEmpty());
            for (Map.Entry<String, long[]> se : visibleSubs) {
                String label = se.getKey().substring(se.getKey().indexOf(':') + 1);
                double subExpected = expectedFor(java.util.Collections.singletonList(se.getKey()));
                addRow(label, se.getKey(), income, se.getValue()[0], se.getValue()[1], false, currency,
                        subExpected >= 0 ? subExpected : elapsed, false);
            }
        }
    }

    private void addHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(20);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setPadding(0, dp(16), 0, dp(4));
        container.addView(tv);
    }

    /**
     * Kategoriezeile; bei {@code main} nur aufklappbar, wenn diese Hauptkategorie <b>keine</b> sichtbaren
     * Unterzeilen hat (sonst listen die Unterzeilen die Buchungen schon einzeln auf – Tippen auf die
     * Hauptzeile macht dann nichts), Unterzeilen sind immer aufklappbar. Aufklappen zeigt die im
     * angezeigten Zeitraum bereits getätigten Buchungen dieser Kategorie (bei Hauptkategorien inkl. aller
     * Unterkategorien).
     */
    private void addRow(String label, String category, boolean income, long ist, long soll,
                        boolean main, String currency, double base, boolean hasVisibleSubs) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(main ? 0 : dp(16), dp(6), 0, dp(2));

        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText(label);
        if (main) {
            name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        }
        LinearLayout.LayoutParams namLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        line.addView(name, namLp);

        TextView value = new TextView(this);
        value.setText(getString(R.string.budget_ist_soll,
                MoneyFormat.display(ist, currency), MoneyFormat.display(soll, currency)));
        value.setGravity(Gravity.END);
        line.addView(value);

        boolean expandable = !main || !hasVisibleSubs;
        TextView caret = new TextView(this);
        if (expandable) {
            caret.setTextColor(0xFF9E9E9E);
            LinearLayout.LayoutParams caretLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            caretLp.setMarginStart(dp(8));
            caret.setLayoutParams(caretLp);
            caret.setText("▸");
            line.addView(caret);
        }

        header.addView(line);
        header.addView(buildBar(income, ist, soll, base));
        wrapper.addView(header);

        boolean editable = !Budget.SOURCE_KMY.equals(budgetSource);
        if (editable) {
            // Der lange Druck ist der einzige Weg zum Soll-Wert. Ohne ein Zeichen dafür findet ihn nur,
            // wer das Handbuch gelesen hat – deshalb ein Stift in derselben Machart wie der ▸/▾-Caret.
            TextView pencil = new TextView(this);
            pencil.setText("\u270E");
            pencil.setTextColor(0xFF9E9E9E);
            LinearLayout.LayoutParams pencilLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            pencilLp.setMarginStart(dp(8));
            pencil.setLayoutParams(pencilLp);
            pencil.setContentDescription(getString(R.string.budget_edit_long_press));
            line.addView(pencil, line.indexOfChild(value) + 1);

            header.setOnLongClickListener(v -> {
                showEditDialog(category, income, currency);
                return true;
            });
        }

        if (expandable) {
            LinearLayout detail = new LinearLayout(this);
            detail.setOrientation(LinearLayout.VERTICAL);
            detail.setPadding(dp(20), dp(4), 0, dp(6));
            detail.setVisibility(View.GONE);
            wrapper.addView(detail);

            boolean isMain = main;
            header.setOnClickListener(v -> {
                boolean show = detail.getVisibility() != View.VISIBLE;
                if (show && detail.getChildCount() == 0) {
                    loadBookingDetail(detail, category, isMain, income);
                }
                detail.setVisibility(show ? View.VISIBLE : View.GONE);
                caret.setText(show ? "▾" : "▸");
            });
        }

        container.addView(wrapper);
    }

    /**
     * Lädt die Buchungen des angezeigten Zeitraums für {@code filterCategory} (bei Hauptkategorie inkl.
     * Unterkategorien) und füllt sie einmalig in {@code detail} – wird nur beim ersten Aufklappen gerufen.
     * Zusätzlich zum Kategorie-Präfix wird der Kategorietyp geprüft ({@link #categoryTypes}): kMyMoney
     * erlaubt gleichnamige Einnahme- und Ausgabekategorien (z. B. Einnahme „Geschenke" und Ausgabe
     * „Geschenke:Geschäft") – ohne diesen Zusatz würde die Präfix-Zuordnung der Hauptkategorie „Geschenke"
     * fälschlich auch die gleichnamige Kategorie des jeweils anderen Typs einschließen.
     */
    private void loadBookingDetail(LinearLayout detail, String filterCategory, boolean isMain, boolean income) {
        repository.getBookingsBetween(periodFromMs, periodToMs, bookings ->
                repository.getAllSplitsMap(splits -> {
                    detail.removeAllViews();
                    boolean any = false;
                    if (bookings != null) {
                        for (Booking b : bookings) {
                            if (!CategoryBookingFilter.matchesBooking(
                                    b, splits, filterCategory, isMain, categoryTypes, income)) {
                                continue;
                            }
                            long signed = b.isIncome ? b.amountCents : -b.amountCents;
                            long display = CategoryBookingFilter.displaySigned(
                                    b, splits, filterCategory, isMain, signed, categoryTypes, income);
                            boolean partial = CategoryBookingFilter.isPartial(
                                    b, splits, filterCategory, isMain, categoryTypes, income);
                            detail.addView(bookingRow(b, display, partial));
                            any = true;
                        }
                    }
                    if (!any) {
                        TextView hint = new TextView(this);
                        hint.setText(R.string.category_chart_no_bookings);
                        hint.setTextColor(0xFF9E9E9E);
                        detail.addView(hint);
                    }
                }));
    }

    /** Buchungszeile im Drilldown: Empfänger (fett) · Konto/Datum (grau) · Betrag (farbig, ggf. „Anteil"). */
    private View bookingRow(Booking b, long displayCents, boolean partial) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);

        TextView payee = new TextView(this);
        payee.setText(b.payee.isEmpty() ? b.category : b.payee);
        text.addView(payee);

        TextView sub = new TextView(this);
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        sub.setText(b.account + " · " + DateFormats.date(b.createdAt));
        sub.setTextSize(12);
        sub.setTextColor(0xFF9E9E9E);
        text.addView(sub);

        row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView amount = new TextView(this);
        String currency = Currencies.getDefault();
        String suffix = partial ? " " + getString(R.string.booking_row_partial_suffix) : "";
        amount.setText(MoneyFormat.display(displayCents, currency) + suffix);
        amount.setTextColor(getColor(displayCents >= 0 ? R.color.income_green : R.color.expense_red));
        amount.setGravity(Gravity.END);
        row.addView(amount);
        return row;
    }

    /**
     * Dünner Balken: Breite = min(Ist/Soll,1); Farbe grün/rot je nach <b>erwartetem Fortschritt</b>
     * {@code base} (aus dem Verlauf, sonst linear). Ausgabe grün wenn nicht mehr ausgeschöpft als
     * erwartet, Einnahme grün wenn mindestens so viel erwartet – siehe {@link de.spahr.ausgaben.db.BudgetProgress}.
     */
    private View buildBar(boolean income, long ist, long soll, double base) {
        double used = soll > 0 ? (double) ist / soll : (ist > 0 ? 1 : 0);
        double filled = Math.max(0, Math.min(used, 1));

        boolean good = de.spahr.ausgaben.db.BudgetProgress.onTrack(income, used, base, PROGRESS_TOL);
        int color = getColor(good ? R.color.income_green : R.color.expense_red);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6));
        barLp.topMargin = dp(3);
        bar.setLayoutParams(barLp);

        View fill = new View(this);
        fill.setBackgroundColor(color);
        fill.setAlpha(0.4f);
        bar.addView(fill, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, (float) filled));

        if (filled < 1) {
            View rest = new View(this);
            bar.addView(rest, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, (float) (1 - filled)));
        }
        return bar;
    }

    private void showEditDialog(String category, boolean income, String currency) {
        long current = 0;
        for (Budget b : budgetLines) {
            if (b.isIncome == income && b.category.equals(category)) {
                current = b.amountCents;
                break;
            }
        }

        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        TextInputLayout til = new TextInputLayout(this);
        til.setHint(getString(R.string.budget_edit_hint));
        til.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText input = new TextInputEditText(til.getContext());
        if (current > 0) {
            input.setText(MoneyFormat.plain(current));
        }
        til.addView(input);
        int pad = dp(16);
        box.setPadding(pad, 0, pad, 0);
        box.addView(til);
        // Eigene Rechentastatur statt der System-Tastatur (erscheint bei Fokus des Betragsfelds).
        CalcKeyboardView calc = CalcKeyboardView.installToggling(input, box, false);
        input.requestFocus();

        // Übernahme – von der Rechentastatur (OK-Taste) und vom OK-Knopf des Dialogs aus derselben Stelle.
        final androidx.appcompat.app.AlertDialog[] dialogRef = new androidx.appcompat.app.AlertDialog[1];
        final Runnable commit = () -> {
            Long cents = parseCents(input.getText() == null ? "" : input.getText().toString());
            if (cents == null || cents < 0) {
                Toast.makeText(this, R.string.budget_invalid_amount, Toast.LENGTH_SHORT).show();
                return;
            }
            repository.saveBudgetLine(displayYear, category, income, cents, this::reload);
            if (dialogRef[0] != null) {
                dialogRef[0].dismiss();
            }
        };
        calc.setOnOk(valid -> {
            if (!valid) {
                Toast.makeText(this, R.string.budget_invalid_amount, Toast.LENGTH_SHORT).show();
                return;
            }
            commit.run();
        });

        // Der Dialog braucht einen eigenen OK-Knopf: wer die Rechentastatur wegklappt, hätte sonst keinen
        // sichtbaren Weg mehr zu speichern.
        androidx.appcompat.app.AlertDialog dialog = new AppDialog(this)
                .setTitle(getString(R.string.budget_edit_title, category))
                .setView(AppDialog.scrollable(box))
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialogRef[0] = dialog;
        // Das fokussierte Betragsfeld darf nicht die System-Tastatur des Dialogfensters hochziehen –
        // die eigene Rechentastatur erscheint stattdessen über den Fokus-Listener.
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        dialog.show();
        // Eigener Listener statt setPositiveButton(..., listener): bei ungültiger Eingabe soll der Dialog
        // offen bleiben, statt sich wie sonst nach dem Klick zu schließen.
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> commit.run());
    }

    private Long parseCents(String s) {
        return de.spahr.ausgaben.settings.AmountExpression.toCents(s);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
