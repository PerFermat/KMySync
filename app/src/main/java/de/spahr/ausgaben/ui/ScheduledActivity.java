package de.spahr.ausgaben.ui;

import de.spahr.ausgaben.net.RemotePath;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.slider.RangeSlider;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.db.ScheduleProjection;
import de.spahr.ausgaben.db.ScheduledTransaction;
import de.spahr.ausgaben.export.KmyDocument;
import de.spahr.ausgaben.export.KmyImporter;
import de.spahr.ausgaben.net.RemoteStorage;
import de.spahr.ausgaben.settings.Currencies;
import de.spahr.ausgaben.settings.MoneyFormat;
import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Seite „Geplante Buchungen": faltet jede aus KMyMoney importierte Planung in ihre einzelnen Termine auf
 * (chronologisch). Spalten: Datum · Name + Empfänger · Konto (bei Umbuchung beide) · Betrag; farbiger Strich
 * grün/rot/gelb. Oben ein durchschaltbarer Saldo-Streifen (Überschuss/Fehlbetrag → Einzahlungen →
 * Rechnungen). Filter (Symbol oben rechts) nach Buchungsart, Konto, Name und Zeitraum; ein Grafik-Knopf
 * öffnet die Verlaufsgrafik. Scroll-nach-oben-Knopf beim Scrollen.
 */
public class ScheduledActivity extends LocalizedActivity {

    private static final int FORWARD_MONTHS = 24;
    private static final int MAX_PER_SCHEDULE = 120;
    private static final int GREY = 0xFF9E9E9E;

    private Repository repository;
    private SettingsStore settings;
    private LinearLayout container;
    private ScrollView scroll;
    private TextView saldoValue;
    private TextView saldoLabel;
    private FloatingActionButton fabScrollTop;
    private SwipeRefreshLayout swipeRefresh;
    private ImportBanner importBanner;

    private List<ScheduledTransaction> all = new ArrayList<>();
    /** {@code kmy_id} → bereits erledigte/übersprungene Termine: neue nächste Fälligkeit ({@code 0} = fertig). */
    private java.util.Map<String, Long> advances = new java.util.HashMap<>();

    // Filter (0 gewählte Buchungsarten = alle).
    private boolean fIncome = true;
    private boolean fExpense = true;
    private boolean fTransfer = true;
    private String fAccount = "";   // "" = alle
    private String fName = "";
    private Long fDateFrom = null;
    private Long fDateTo = null;

    // Saldo-Streifen: 0 = Überschuss/Fehlbetrag, 1 = Summe Einzahlungen, 2 = Summe Rechnungen,
    // 3 = Summe Umbuchungen.
    private int saldoIndex = 0;
    private long sumIncomeCents = 0;
    private long sumExpenseCents = 0;
    private long sumTransferCents = 0;

    /** Ein einzelner Fälligkeitstermin einer Planung. */
    private static final class Occurrence {
        final long dueMs;
        final ScheduledTransaction st;
        /** Der nächste offene Termin dieser Planung – nur er lässt sich buchen bzw. überspringen. */
        final boolean next;
        Occurrence(long dueMs, ScheduledTransaction st, boolean next) {
            this.dueMs = dueMs;
            this.st = st;
            this.next = next;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scheduled);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.scheduled_menu);
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_filter) {
                showFilterDialog();
                return true;
            }
            if (id == R.id.action_analysis) {
                openChart();
                return true;
            }
            return false;
        });

        repository = new Repository(this);
        settings = new SettingsStore(this);
        container = findViewById(R.id.scheduledContainer);
        scroll = findViewById(R.id.scheduledScroll);

        ShimmerView importShimmer = findViewById(R.id.importShimmer);
        importShimmer.setColors(getColor(R.color.import_banner_bg), getColor(R.color.import_banner_shimmer));
        importBanner = new ImportBanner(findViewById(R.id.importBanner), importShimmer,
                findViewById(R.id.importStatus), findViewById(R.id.importPercent));

        // Wischgeste nach unten aktualisiert die geplanten Buchungen aus der .kmy (nur hier, nicht beim Konto-Import).
        swipeRefresh = findViewById(R.id.swipeRefresh);
        swipeRefresh.setOnRefreshListener(this::refreshFromKmy);

        saldoValue = findViewById(R.id.textBalance);
        saldoLabel = findViewById(R.id.textSaldoLabel);
        findViewById(R.id.saldoHeader).setOnClickListener(v -> {
            saldoIndex = (saldoIndex + 1) % 4;
            showSaldo();
        });

        fabScrollTop = findViewById(R.id.fabScrollTop);
        fabScrollTop.setOnClickListener(v -> scroll.smoothScrollTo(0, 0));
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            if (y > 0) {
                fabScrollTop.show();
            } else {
                fabScrollTop.hide();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    /** Planungen + Vormerkungen laden und neu zeichnen. */
    private void reload() {
        repository.getScheduledTransactionsWithAdvances(data -> {
            all = data.transactions;
            advances = data.advances;
            render();
        });
    }

    // ---- Wisch-nach-unten: geplante Buchungen aus der .kmy neu einlesen (mit gelbem Banner) ----

    /** Lädt die .kmy und liest NUR die geplanten Buchungen neu ein (nicht beim normalen Konto-Import). */
    private void refreshFromKmy() {
        String path = settings.getKmyPath();
        if (path == null || path.trim().isEmpty()) {
            swipeRefresh.setRefreshing(false);
            Toast.makeText(this, R.string.kmy_path_missing, Toast.LENGTH_LONG).show();
            return;
        }
        // Der gelbe Banner übernimmt die Fortschrittsanzeige – den Kreis-Spinner der Geste ausblenden.
        swipeRefresh.setRefreshing(false);
        importBanner.start(getString(R.string.import_running_banner));
        final String folder = RemotePath.folderOf(path);
        final String file = RemotePath.fileOf(path);
        new Thread(() -> {
            try {
                byte[] raw = RemoteStorage.from(settings).downloadBytes(folder, file,
                        importBanner.phase(getString(R.string.import_stage_download),
                                de.spahr.ausgaben.export.ImportPhase.DOWNLOAD_FROM,
                                de.spahr.ausgaben.export.ImportPhase.DOWNLOAD_TO));
                KmyImporter importer = new KmyImporter(
                        new KmyDocument(raw, getApplicationContext(),
                                importBanner.phase(getString(R.string.import_stage_reading),
                                        de.spahr.ausgaben.export.ImportPhase.READ_FILE_FROM,
                                        de.spahr.ausgaben.export.ImportPhase.READ_FILE_TO)),
                        getApplicationContext());
                // Hinter dem Lesen bleiben nur zwei kurze Schritte, die nicht zählen können; das
                // Nachlaufen des Banners überbrückt sie.
                importBanner.set(getString(R.string.import_stage_bookings),
                        de.spahr.ausgaben.export.ImportBudget.HEAD_END);
                List<ScheduledTransaction> list = importer.scheduledTransactions();
                importBanner.set(getString(R.string.import_stage_saving),
                        (de.spahr.ausgaben.export.ImportBudget.HEAD_END
                                + de.spahr.ausgaben.export.ImportBudget.TAIL_END) / 2);
                repository.applyScheduledTransactions(list, () -> {
                    reload();
                    importBanner.finish();
                });
            } catch (Exception e) {
                post(() -> {
                    importBanner.finishNow();
                    String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    // ---- Fenster / Auffalten / Filter ----

    private long windowFromMs() {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.MONTH, -1);
        return c.getTimeInMillis();
    }

    private long windowToMs() {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.MONTH, FORWARD_MONTHS);
        return c.getTimeInMillis();
    }

    private boolean kindSelected(int kind) {
        if (fIncome == fExpense && fExpense == fTransfer && !fIncome) {
            return true;   // keine Art angehakt → als „alle" behandeln
        }
        if (kind == ScheduledTransaction.KIND_INCOME) {
            return fIncome;
        }
        if (kind == ScheduledTransaction.KIND_EXPENSE) {
            return fExpense;
        }
        return fTransfer;
    }

    private boolean accountMatches(ScheduledTransaction st) {
        if (fAccount.isEmpty()) {
            return true;
        }
        return fAccount.equalsIgnoreCase(st.account)
                || (st.kind == ScheduledTransaction.KIND_TRANSFER
                    && fAccount.equalsIgnoreCase(st.counterparty));
    }

    private boolean nameMatches(ScheduledTransaction st) {
        if (fName.isEmpty()) {
            return true;
        }
        String needle = fName.toLowerCase(Locale.GERMANY);
        return st.name.toLowerCase(Locale.GERMANY).contains(needle)
                || st.payee.toLowerCase(Locale.GERMANY).contains(needle);
    }

    /**
     * Nächste Fälligkeit unter Berücksichtigung bereits erledigter/übersprungener Termine, die noch nicht
     * nach KMyMoney übertragen sind. {@code 0} = nichts (mehr) offen.
     */
    private long effectiveDue(ScheduledTransaction st) {
        Long advanced = advances.get(st.kmyId);
        if (advanced == null) {
            return st.nextDueMs;
        }
        return advanced <= 0 ? 0 : Math.max(st.nextDueMs, advanced);
    }

    private void render() {
        container.removeAllViews();
        long fromMs = windowFromMs();
        long toMs = windowToMs();

        List<Occurrence> items = new ArrayList<>();
        sumIncomeCents = 0;
        sumExpenseCents = 0;
        sumTransferCents = 0;
        for (ScheduledTransaction st : all) {
            // Erledigte/übersprungene Termine sind bereits weitergestellt (noch nicht in der .kmy).
            long base = effectiveDue(st);
            if (base <= 0 || !kindSelected(st.kind) || !accountMatches(st) || !nameMatches(st)) {
                continue;
            }
            for (long due : ScheduleProjection.occurrences(base, st.occurrence,
                    st.occurrenceMultiplier, st.endMs, fromMs, toMs, MAX_PER_SCHEDULE)) {
                if (fDateFrom != null && due < fDateFrom) {
                    continue;
                }
                if (fDateTo != null && due > fDateTo) {
                    continue;
                }
                items.add(new Occurrence(due, st, due == base));
                if (st.kind == ScheduledTransaction.KIND_INCOME) {
                    sumIncomeCents += st.amountCents;
                } else if (st.kind == ScheduledTransaction.KIND_EXPENSE) {
                    sumExpenseCents += st.amountCents;
                } else {
                    sumTransferCents += st.amountCents;
                }
            }
        }
        showSaldo();

        if (items.isEmpty()) {
            TextView hint = new TextView(this);
            // Unterscheiden, ob es wirklich nichts gibt oder nur der Filter zu eng steht – sonst liest man
            // „keine geplanten Buchungen", während vierzig davon existieren.
            hint.setText(isFilterActive() ? R.string.scheduled_no_match : R.string.scheduled_empty);
            hint.setPadding(0, dp(16), 0, dp(16));
            container.addView(hint);
            return;
        }
        Collections.sort(items, Comparator.comparingLong(o -> o.dueMs));

        Locale locale = getResources().getConfiguration().getLocales().get(0);
        DateFormat df = DateFormat.getDateInstance(DateFormat.SHORT, locale);
        for (Occurrence o : items) {
            container.addView(buildRow(o, df));
        }
    }

    // ---- Saldo-Streifen ----

    private void showSaldo() {
        long value;
        int labelRes;
        int color;
        if (saldoIndex == 1) {
            value = sumIncomeCents;
            labelRes = R.string.sched_saldo_income;
            color = R.color.income_green;
        } else if (saldoIndex == 2) {
            value = sumExpenseCents;
            labelRes = R.string.sched_saldo_expense;
            color = R.color.expense_red;
        } else if (saldoIndex == 3) {
            value = sumTransferCents;
            labelRes = R.string.sched_saldo_transfer;
            color = R.color.transfer_yellow;
        } else {
            // Überschuss (≥ 0, grün) ODER Fehlbetrag (< 0, rot) – nur der zutreffende Zustand.
            value = sumIncomeCents - sumExpenseCents;
            labelRes = value < 0 ? R.string.sched_saldo_deficit : R.string.sched_saldo_surplus;
            color = value < 0 ? R.color.expense_red : R.color.income_green;
        }
        saldoValue.setText(MoneyFormat.display(value, Currencies.getDefault()));
        saldoValue.setTextColor(getColor(color));
        saldoLabel.setText(getString(labelRes));
    }

    // ---- Zeile ----

    private View buildRow(Occurrence o, DateFormat df) {
        ScheduledTransaction st = o.st;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        // Farbstrich: grün = Einzahlung, rot = Auszahlung, gelb = Umbuchung.
        View strip = new View(this);
        int color;
        if (st.kind == ScheduledTransaction.KIND_INCOME) {
            color = R.color.income_green;
        } else if (st.kind == ScheduledTransaction.KIND_TRANSFER) {
            color = R.color.transfer_yellow;
        } else {
            color = R.color.expense_red;
        }
        strip.setBackgroundColor(getColor(color));
        LinearLayout.LayoutParams stripLp = new LinearLayout.LayoutParams(dp(4), dp(38));
        stripLp.setMarginEnd(dp(10));
        row.addView(strip, stripLp);

        // Datumsspalte.
        TextView date = new TextView(this);
        date.setText(df.format(new java.util.Date(o.dueMs)));
        date.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dateLp.setMarginEnd(dp(10));
        date.setMinWidth(dp(64));
        row.addView(date, dateLp);

        // Titel-Block: Name (fett) + Empfänger dahinter, darunter Kategorie bzw. „Splitbuchung".
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(nameWithPayee(st));
        titleBox.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        String sub = categorySubline(st);
        if (!sub.isEmpty()) {
            TextView cat = new TextView(this);
            cat.setText(sub);
            cat.setTextSize(12);
            cat.setTextColor(GREY);
            titleBox.addView(cat, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        // Konto (bei Umbuchung beide) kursiv als Unterzeile – statt einer eigenen Spalte.
        TextView acc = new TextView(this);
        acc.setText(accountSubline(st));
        acc.setTextSize(12);
        acc.setTextColor(GREY);
        acc.setTypeface(acc.getTypeface(), Typeface.ITALIC);
        titleBox.addView(acc, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(titleBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Betrag rechts.
        TextView amount = new TextView(this);
        amount.setText(MoneyFormat.display(st.amountCents, Currencies.forAccount(st.account)));
        amount.setGravity(Gravity.END);
        amount.setTypeface(amount.getTypeface(), Typeface.BOLD);
        row.addView(amount, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // „Jetzt buchen" liegt allein auf dem langen Druck und gilt nur für den nächsten Termin. Ein Haken
        // an genau dieser Zeile verrät beides: dass es die Aktion gibt und wo sie greift.
        if (o.next) {
            TextView bookable = new TextView(this);
            bookable.setText("\u2713");
            bookable.setTextColor(GREY);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(dp(8));
            bookable.setLayoutParams(lp);
            bookable.setContentDescription(getString(R.string.scheduled_book_hint));
            row.addView(bookable);
        }

        // Klick → Detail-Maske (1:1 wie eine normale Buchung, schreibgeschützt).
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);
        row.setClickable(true);
        row.setOnClickListener(v -> {
            Intent i = new Intent(this, BookingEditActivity.class);
            i.putExtra(BookingEditActivity.EXTRA_SCHEDULED_ID, st.id);
            i.putExtra(BookingEditActivity.EXTRA_SCHEDULED_DUE_MS, o.dueMs);
            startActivity(i);
        });
        // Langer Druck → „jetzt buchen": Editor als NEUE Buchung vorbefüllt (wie in der Buchungsliste:
        // kurzer Tipp = ansehen, langer Druck = handeln). Dort steht auch „Buchung überspringen".
        // Beides stellt die KMyMoney-Regel eine Periode weiter und gilt deshalb nur für den nächsten Termin.
        row.setOnLongClickListener(v -> {
            if (!o.next) {
                Toast.makeText(this, R.string.scheduled_only_next, Toast.LENGTH_LONG).show();
                return true;
            }
            Intent i = new Intent(this, BookingEditActivity.class);
            i.putExtra(BookingEditActivity.EXTRA_SCHEDULED_BOOK_ID, o.st.id);
            i.putExtra(BookingEditActivity.EXTRA_SCHEDULED_DUE_MS, o.dueMs);
            startActivity(i);
            return true;
        });

        return row;
    }

    /** Kategorie-Untertitel: „Splitbuchung" bei mehreren Kategorien, sonst die Kategorie (nicht bei Umbuchung). */
    private String categorySubline(ScheduledTransaction st) {
        if (st.split == 1) {
            return getString(R.string.sched_split);
        }
        if (st.kind == ScheduledTransaction.KIND_TRANSFER) {
            return "";   // Konten stehen bereits in der Konto-Spalte
        }
        return st.counterparty;
    }

    /** Konto als Unterzeile: bei Umbuchung „KontoA → KontoB", sonst das einzelne Konto. */
    private String accountSubline(ScheduledTransaction st) {
        if (st.kind == ScheduledTransaction.KIND_TRANSFER && !st.counterparty.isEmpty()) {
            return st.account + " → " + st.counterparty;
        }
        return st.account;
    }

    /** Name (fett) + Zahlungsempfänger dahinter in kleinerer, grauer Schrift. */
    private CharSequence nameWithPayee(ScheduledTransaction st) {
        String name = st.name.isEmpty() ? st.payee : st.name;
        SpannableStringBuilder sb = new SpannableStringBuilder(name);
        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, name.length(), 0);
        if (!st.name.isEmpty() && !st.payee.isEmpty()) {
            int start = sb.length();
            sb.append("  ").append(st.payee);
            sb.setSpan(new RelativeSizeSpan(0.85f), start, sb.length(), 0);
            sb.setSpan(new ForegroundColorSpan(GREY), start, sb.length(), 0);
        }
        return sb;
    }

    // ---- Filter-Dialog ----

    /** Greift gerade ein Filter? Spiegelt genau die Felder, die „Filter zurücksetzen" wieder freigibt. */
    private boolean isFilterActive() {
        return !(fIncome && fExpense && fTransfer)
                || !fAccount.isEmpty()
                || !fName.isEmpty()
                || fDateFrom != null
                || fDateTo != null;
    }

    private void showFilterDialog() {
        repository.getAllAccountNames(this::buildFilterDialog);
    }

    private void buildFilterDialog(List<String> accountNames) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_scheduled_filter, null, false);
        CheckBox cbIncome = view.findViewById(R.id.filterKindIncome);
        CheckBox cbExpense = view.findViewById(R.id.filterKindExpense);
        CheckBox cbTransfer = view.findViewById(R.id.filterKindTransfer);
        cbIncome.setChecked(fIncome);
        cbExpense.setChecked(fExpense);
        cbTransfer.setChecked(fTransfer);

        final String all = getString(R.string.sched_account_all);
        List<String> accOptions = new ArrayList<>();
        accOptions.add(all);
        accOptions.addAll(accountNames);
        MaterialAutoCompleteTextView acc = view.findViewById(R.id.filterAccount);
        PickerAdapters.accounts(repository, acc, accOptions);
        acc.setText(fAccount.isEmpty() ? all : fAccount, false);

        TextInputEditText name = view.findViewById(R.id.filterName);
        name.setText(fName);

        RangeSlider dateSlider = view.findViewById(R.id.filterDateSlider);
        TextInputEditText dFrom = view.findViewById(R.id.filterDateFrom);
        TextInputEditText dTo = view.findViewById(R.id.filterDateTo);
        final long dtMin = windowFromMs();
        final long dtMax = windowToMs();
        final MonthRange dateRange = MonthRange.attach(dateSlider, dFrom, dTo, dtMin, dtMax,
                fDateFrom, fDateTo);

        new AppDialog(this)
                .setTitle(R.string.filter_title)
                .setView(view)
                .setPositiveButton(R.string.filter_apply, (d, w) -> {
                    // Der Knopf nimmt dem Feld nicht zwangsläufig den Fokus; ein Feld mitten in der Suche
                    // ist leer. Erst die Suche beenden, dann lesen.
                    PickerBehaviour.settleAll(view);

                    fIncome = cbIncome.isChecked();
                    fExpense = cbExpense.isChecked();
                    fTransfer = cbTransfer.isChecked();
                    String a = acc.getText() == null ? "" : acc.getText().toString().trim();
                    fAccount = (a.isEmpty() || a.equals(all)) ? "" : a;
                    fName = name.getText() == null ? "" : name.getText().toString().trim();
                    long df = dateRange.getFromMillis();
                    long dt = dateRange.getToMillis();
                    if (df <= dtMin && dt >= dtMax) {
                        fDateFrom = null;
                        fDateTo = null;
                    } else {
                        fDateFrom = df;
                        fDateTo = dt;
                    }
                    render();
                })
                .setNeutralButton(R.string.filter_reset, (d, w) -> {
                    fIncome = fExpense = fTransfer = true;
                    fAccount = "";
                    fName = "";
                    fDateFrom = null;
                    fDateTo = null;
                    render();
                })
                .show();
    }

    // ---- Grafik ----

    private void openChart() {
        int mask = (fIncome ? 1 : 0) | (fExpense ? 2 : 0) | (fTransfer ? 4 : 0);
        Intent i = new Intent(this, ScheduledChartActivity.class);
        i.putExtra(ScheduledChartActivity.EXTRA_FILTER_NAME, fName);
        i.putExtra(ScheduledChartActivity.EXTRA_FILTER_KINDS, mask == 7 ? 0 : mask);
        i.putExtra(ScheduledChartActivity.EXTRA_FILTER_DATE_FROM,
                fDateFrom == null ? Long.MIN_VALUE : fDateFrom);
        i.putExtra(ScheduledChartActivity.EXTRA_FILTER_DATE_TO,
                fDateTo == null ? Long.MAX_VALUE : fDateTo);
        startActivity(i);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
