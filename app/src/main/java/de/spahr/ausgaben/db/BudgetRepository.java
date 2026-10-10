package de.spahr.ausgaben.db;

import android.os.Handler;

import java.util.List;
import java.util.concurrent.ExecutorService;

import de.spahr.ausgaben.db.Repository.Callback;
import de.spahr.ausgaben.db.Repository.YearBudget;

/**
 * Budgetplanung: Ist-Summen je Kategorie sowie Soll-Werte (Speichern, Import-Ersetzen, Verlaufsberechnung).
 * Kollaborator hinter der {@link Repository}-Fassade; teilt sich deren Hintergrund-Executor und Main-Handler.
 */
class BudgetRepository {

    private final BookingDao bookingDao;
    private final BudgetDao budgetDao;
    private final CategoryTypeDao categoryTypeDao;
    private final ExecutorService executor;
    private final Handler mainHandler;

    BudgetRepository(BookingDao bookingDao, BudgetDao budgetDao, CategoryTypeDao categoryTypeDao,
                     ExecutorService executor, Handler mainHandler) {
        this.bookingDao = bookingDao;
        this.budgetDao = budgetDao;
        this.categoryTypeDao = categoryTypeDao;
        this.executor = executor;
        this.mainHandler = mainHandler;
    }

    /** Netto-Zuflüsse je Kategorie (vorzeichenbehaftet) im Zeitraum. */
    void getCategoryActuals(final long fromMs, final long toMs,
                            final Callback<List<CategorySum>> callback) {
        executor.execute(() -> {
            final List<CategorySum> result = bookingDao.getCategoryActuals(fromMs, toMs);
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Historische Zahlungs-Verteilung je Kategorie (Tag im Monat bzw. Monat im Jahr) für die Balkenfarbe. */
    void getCategoryTiming(final boolean monthView, final Callback<List<CategoryBucket>> callback) {
        executor.execute(() -> {
            final List<CategoryBucket> result = monthView
                    ? bookingDao.getDayOfMonthHistogram()
                    : bookingDao.getMonthOfYearHistogram();
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Kategorie-Pfad → Typ ({@code true} = Einnahme) aus der Datei; verlässliche Budget-Einordnung. */
    void getCategoryTypes(final Callback<java.util.Map<String, Boolean>> callback) {
        executor.execute(() -> {
            final java.util.Map<String, Boolean> map = loadTypeMap();
            mainHandler.post(() -> callback.onResult(map));
        });
    }

    /** Lädt die Kategorie-Typen (nur auf dem Executor-Thread aufrufen). */
    private java.util.Map<String, Boolean> loadTypeMap() {
        java.util.Map<String, Boolean> map = new java.util.HashMap<>();
        java.util.Set<String> beide = new java.util.HashSet<>();
        for (CategoryType t : categoryTypeDao.getAll()) {
            if (t.category == null || t.category.isEmpty()) {
                continue;
            }
            Boolean vorher = map.put(t.category, t.isIncome);
            if (vorher != null && vorher != t.isIncome) {
                beide.add(t.category);
            }
        }
        // Ein Name, den es im Einnahme- und im Ausgabebaum gibt, sagt allein nichts über die Seite.
        // Wer hier nachschlägt, tut das für Zeilen ohne eigene Seite und fällt dann auf seine
        // Richtung zurück – besser als eine der beiden zu würfeln.
        map.keySet().removeAll(beide);
        return map;
    }

    void getBudget(final int year, final Callback<YearBudget> callback) {
        executor.execute(() -> {
            final List<Budget> lines = budgetDao.getForYear(year);
            final String source = budgetDao.sourceForYear(year);
            mainHandler.post(() -> callback.onResult(new YearBudget(source, lines)));
        });
    }

    /** Setzt/ändert einen Soll-Wert manuell (Herkunft = intern). */
    void saveBudgetLine(final int year, final String category, final boolean isIncome,
                        final long amountCents, final Runnable onDone) {
        executor.execute(() -> {
            budgetDao.upsert(new Budget(year, category, isIncome, amountCents, Budget.SOURCE_INTERNAL));
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /** Ersetzt das Budget eines Jahres komplett (Import/Berechnung). */
    void replaceBudget(final int year, final String source, final List<Budget> lines,
                       final Runnable onDone) {
        executor.execute(() -> {
            budgetDao.deleteYear(year);
            for (Budget b : lines) {
                b.year = year;
                b.source = source;
                budgetDao.upsert(b);
            }
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /**
     * Berechnet das Budget fürs {@code year} aus dem Verlauf: Ist-Summe aller Buchungen VOR dem Jahresbeginn
     * geteilt durch die Anzahl der Jahre mit Daten. Speichert als internes Budget.
     */
    void computeBudgetFromHistory(final int year, final Runnable onDone) {
        executor.execute(() -> {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.clear();
            c.set(java.util.Calendar.YEAR, year);
            long yearStart = c.getTimeInMillis();
            List<CategorySum> sums = bookingDao.getCategoryActuals(0L, yearStart);
            List<Integer> years = bookingDao.getDataYearsBefore(yearStart);
            int divisor = years == null || years.isEmpty() ? 1 : years.size();
            // Kein Vorjahr vorhanden → Fallback: laufendes Jahr als einzige Datenbasis.
            List<CategorySum> basis = sums;
            if (basis.isEmpty()) {
                long yearEnd;
                java.util.Calendar c2 = java.util.Calendar.getInstance();
                c2.clear();
                c2.set(java.util.Calendar.YEAR, year + 1);
                yearEnd = c2.getTimeInMillis();
                basis = bookingDao.getCategoryActuals(yearStart, yearEnd);
                divisor = 1;
            }
            // Je Kategorie UND Kategorietyp den vorzeichenbehafteten Netto-Zufluss summieren (getrennt
            // gehalten, da kMyMoney dieselbe Kategorie-Bezeichnung unabhängig im Einnahme- und im
            // Ausgabe-Baum haben kann, z. B. „Versicherung:Krankenzusatz" – siehe getCategoryActuals).
            // Der Typ kommt bevorzugt aus dem Kategorietyp je Zeile (CategorySum.catIsIncome); nur für
            // Zeilen ohne eigenen Typ (NULL, vor der Kategorietyp-je-Zeile-Migration) greift der globale
            // Rückfall über die kmy-Datei (typeMap). Eine Einnahme auf einer Ausgabekategorie (Erstattung)
            // mindert deren Soll, die Kategorie bleibt Ausgabe. Kategorien ohne ermittelbaren Typ werden
            // übersprungen. Der Unique-Index (year, month, category, is_income) erlaubt zwei getrennte
            // Budget-Zeilen für dieselbe Kategorie mit unterschiedlichem Typ.
            java.util.Map<String, Boolean> typeMap = loadTypeMap();
            java.util.Map<String, java.util.Map<Boolean, Long>> perCat = new java.util.HashMap<>();
            for (CategorySum s : basis) {
                if (s.category == null || s.category.isEmpty()) {
                    continue;
                }
                java.util.Map<Boolean, Long> byType =
                        perCat.computeIfAbsent(s.category, k -> new java.util.HashMap<>());
                Long v = byType.get(s.catIsIncome);
                byType.put(s.catIsIncome, (v == null ? 0L : v) + s.total);
            }
            budgetDao.deleteYear(year);
            for (java.util.Map.Entry<String, java.util.Map<Boolean, Long>> e : perCat.entrySet()) {
                String cat = e.getKey();
                java.util.Map<Boolean, Long> byType = e.getValue();
                java.util.List<Boolean> knownTypes = new java.util.ArrayList<>();
                if (byType.containsKey(Boolean.TRUE)) {
                    knownTypes.add(Boolean.TRUE);
                }
                if (byType.containsKey(Boolean.FALSE)) {
                    knownTypes.add(Boolean.FALSE);
                }
                if (knownTypes.isEmpty()) {
                    Boolean isIncome = typeMap.get(cat);
                    if (isIncome == null) {
                        continue; // strikt: nur kmy-Kategorien erhalten ein Soll
                    }
                    long net = 0;
                    for (long v : byType.values()) {
                        net += v;
                    }
                    upsertComputedBudget(year, cat, isIncome, net, divisor);
                    continue;
                }
                Boolean globalType = typeMap.get(cat);
                for (Boolean type : knownTypes) {
                    boolean foldNull = knownTypes.size() == 1 || (globalType != null && globalType.equals(type));
                    long net = byType.containsKey(type) ? byType.get(type) : 0;
                    if (foldNull && byType.containsKey(null)) {
                        net += byType.get(null);
                    }
                    upsertComputedBudget(year, cat, type, net, divisor);
                }
            }
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /** Legt (bei positivem Soll) eine berechnete Budgetzeile für {@code cat}/{@code isIncome} an. */
    private void upsertComputedBudget(int year, String cat, boolean isIncome, long net, int divisor) {
        long ist = BudgetMath.ist(isIncome, net);
        if (ist <= 0) {
            return; // negativer/0-Netto → kein Soll (Clamp auf 0)
        }
        long soll = Math.round((double) ist / divisor);
        if (soll <= 0) {
            return;
        }
        budgetDao.upsert(new Budget(year, cat, isIncome, soll, Budget.SOURCE_INTERNAL));
    }
}
