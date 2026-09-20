package de.spahr.ausgaben.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.PlaceBalance;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.settings.PlacesStore;
import de.spahr.ausgaben.settings.ReconcileTarget;
import de.spahr.ausgaben.settings.SettingsStore;

/** Bestands-Übersicht: Saldo je Ort, „ohne Ort", Gesamt; Umbuchen und Kassensturz; Ort → Verlauf. */
public class BalanceActivity extends LocalizedActivity {

    private Repository repository;
    private PlacesStore placesStore;
    private SettingsStore settings;
    private LinearLayout container;

    /** Konto → Kontosaldo. */
    private final LinkedHashMap<String, Long> accountBalances = new LinkedHashMap<>();
    /** Konto → (Ort → Saldo). */
    private final Map<String, Map<String, Long>> placeBalances = new LinkedHashMap<>();
    private List<String> accountsOrder = new ArrayList<>();
    private List<String> assetAccounts = new ArrayList<>();
    private List<String> liabilityAccounts = new ArrayList<>();
    /** Kopfzeile mit der gewählten Kontengruppe (app-weit, dieselbe wie in der Schublade). */
    private android.widget.TextView groupButton;
    /** „Umbuchen" – steht nur da, solange es überhaupt ein Konto mit Orten gibt. */
    private MaterialButton btnTransfer;
    /** Reihenfolge der Kontenart-Blöcke, wie in der Kontenverwaltung festgelegt. */
    private int[] kindOrder = de.spahr.ausgaben.db.AccountKind.ALL;
    private final List<String> depotOrder = new ArrayList<>();
    private final Map<String, List<Repository.DepotHolding>> depotHoldings = new LinkedHashMap<>();
    private long total = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_balance);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        repository = new Repository(this);
        placesStore = new PlacesStore(this);
        settings = new SettingsStore(this);
        container = findViewById(R.id.balanceContainer);

        groupButton = findViewById(R.id.balanceGroup);
        groupButton.setOnClickListener(v ->
                AccountGroupPicker.show(this, groupButton, null, repository, settings, this::refresh));

        btnTransfer = findViewById(R.id.btnTransfer);
        btnTransfer.setOnClickListener(v -> showTransferDialog());
        ((MaterialButton) findViewById(R.id.btnReconcile)).setOnClickListener(v -> showReconcileDialog());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        repository.getTotalBalance(t -> {
            total = t;
            repository.getAccountNames(names -> {
                accountsOrder = names;
                // Ohne einen einzigen Ort gibt es nichts umzubuchen; „Ausgleichen" füllt die Zeile dann allein.
                btnTransfer.setVisibility(accountsWithPlaces().isEmpty() ? View.GONE : View.VISIBLE);
                repository.getAllBookings(bks -> {
                    accountBalances.clear();
                    for (Booking b : bks) {
                        long s = b.isIncome ? b.amountCents : -b.amountCents;
                        Long prev = accountBalances.get(b.account);
                        accountBalances.put(b.account, (prev == null ? 0L : prev) + s);
                    }
                    repository.getAllPlaceBalances(pb -> {
                        placeBalances.clear();
                        for (PlaceBalance p : pb) {
                            Map<String, Long> m = placeBalances.get(p.account);
                            if (m == null) {
                                m = new LinkedHashMap<>();
                                placeBalances.put(p.account, m);
                            }
                            m.put(p.place, p.balanceCents);
                        }
                        loadGroupsAndDepots();
                    });
                });
            });
        });
    }

    /** Lädt die Konten der gewählten Kontengruppe (nach Kontenart getrennt) und deren Depots. */
    private void loadGroupsAndDepots() {
        final long groupId = settings.getAccountGroup();
        repository.getAccountGroup(groupId, group -> {
            groupButton.setText(group == null ? getString(R.string.account_all) : group.name);
            repository.getAccountKindOrder(order -> {
                kindOrder = order;
                repository.getAccountsGrouped(group == null ? 0L : group.id, g -> {
                    assetAccounts = g.assets;
                    liabilityAccounts = g.liabilities;
                    loadDepots(g.depots);
                });
            });
        });
    }

    private void loadDepots(final List<String> depots) {
        depotOrder.clear();
        depotHoldings.clear();
        if (depots.isEmpty()) {
            render();
            return;
        }
        final int[] pending = {depots.size()};
        for (String d : depots) {
            repository.getDepotHoldings(d, h -> {
                depotOrder.add(d);
                depotHoldings.put(d, h);
                if (--pending[0] == 0) {
                    // Die Reihenfolge der Rückrufe ist beliebig – die festgelegte wiederherstellen.
                    depotOrder.clear();
                    depotOrder.addAll(depots);
                    render();
                }
            });
        }
    }

    /** Gruppenliste: Anlage-/Verbindlichkeitskonten (mit Überschrift) → Orte → Depots → Gesamt. */
    private void render() {
        container.removeAllViews();
        boolean night = isNight();
        String defCur = de.spahr.ausgaben.settings.Currencies.getDefault();
        long depotTotal = 0;
        for (String depot : depotOrder) {
            depotTotal += depotValue(depot);
        }
        // Die Kontenart-Blöcke in der in der Kontenverwaltung festgelegten Reihenfolge.
        for (int kind : kindOrder) {
            if (kind == de.spahr.ausgaben.db.AccountKind.LIABILITY) {
                if (liabilityAccounts.isEmpty()) {
                    continue;
                }
                addCategoryHeader(getString(R.string.accounts_liability), sumOf(liabilityAccounts),
                        night ? CategoryColors.DARK_LIABILITY : CategoryColors.LIGHT_LIABILITY,
                        night, defCur);
                for (String account : liabilityAccounts) {
                    renderAccount(account);
                }
            } else if (kind == de.spahr.ausgaben.db.AccountKind.DEPOT) {
                if (depotOrder.isEmpty()) {
                    continue;
                }
                addCategoryHeader(getString(R.string.accounts_depot), depotTotal,
                        night ? CategoryColors.DARK_DEPOT : CategoryColors.LIGHT_DEPOT, night, defCur);
                for (String depot : depotOrder) {
                    renderDepotSection(depot);
                }
            } else {
                if (assetAccounts.isEmpty()) {
                    continue;
                }
                addCategoryHeader(getString(R.string.accounts_asset), sumOf(assetAccounts),
                        night ? CategoryColors.DARK_ASSET : CategoryColors.LIGHT_ASSET, night, defCur);
                for (String account : assetAccounts) {
                    renderAccount(account);
                }
            }
        }
        // Gesamt = Konten-Gesamt + Depotwert; alte Invers-Farbe (grau/schwarz). Bei gewählter Gruppe ist
        // das die Summe der Gruppe, sonst die aller Konten.
        addCategoryHeader(getString(R.string.saldo_total), groupTotal() + depotTotal,
                night ? CategoryColors.DARK_TOTAL : CategoryColors.LIGHT_TOTAL,
                !night, defCur);
    }

    private boolean isNight() {
        return (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * Kontensumme der Anzeige: ohne Gruppe der Gesamtsaldo aller Konten, mit Gruppe nur die angezeigten.
     */
    private long groupTotal() {
        if (settings.getAccountGroup() <= 0) {
            return total;
        }
        return sumOf(assetAccounts) + sumOf(liabilityAccounts);
    }

    private long sumOf(List<String> accounts) {
        long sum = 0;
        for (String a : accounts) {
            Long b = accountBalances.get(a);
            if (b != null) {
                sum += b;
            }
        }
        return sum;
    }

    private long depotValue(String depot) {
        List<Repository.DepotHolding> holdings = depotHoldings.get(depot);
        long sub = 0;
        if (holdings != null) {
            for (Repository.DepotHolding h : holdings) {
                sub += h.valueCents;
            }
        }
        return sub;
    }

    /** Ein Konto (fett + Saldo) mit seinen Orten (eingerückt). */
    private void renderAccount(final String account) {
        final String currency = de.spahr.ausgaben.settings.Currencies.forAccount(account);
        long accBal = accountBalances.containsKey(account) ? accountBalances.get(account) : 0L;
        addRow(account, accBal, true, false, false, null, currency);

        Map<String, Long> pbal = placeBalances.get(account);
        List<String> places = placesStore.getPlaces(account);

        long realSum = 0;
        for (final String place : places) {
            long bal = placeBal(pbal, place);
            realSum += bal;
            addRow(place, bal, false, true, true, v -> openHistory(account, place), currency);
        }
        if (!places.isEmpty()) {
            long rest = accBal - realSum;
            addRow(getString(R.string.no_place), rest, false, true, false, null, currency);
        }
    }

    /** Depot-Sektion in den Beständen: nur der Depotwert (keine Einzel-Wertpapiere). Liefert den Wert. */
    private long renderDepotSection(String depot) {
        List<Repository.DepotHolding> holdings = depotHoldings.get(depot);
        if (holdings == null) {
            return 0;
        }
        long sub = 0;
        for (Repository.DepotHolding h : holdings) {
            sub += h.valueCents;
        }
        addRow(depot, sub, true, false, false, null, de.spahr.ausgaben.settings.Currencies.getDefault());
        return sub;
    }

    /**
     * Kategorie-Überschrift mit Kategoriesumme rechtsbündig. Farbcode je Kontokategorie
     * (siehe {@link CategoryColors}); {@code whiteText} steuert die Schriftfarbe.
     */
    private void addCategoryHeader(String title, long sum, int bg, boolean whiteText, String currency) {
        int textColor = whiteText ? android.graphics.Color.WHITE : android.graphics.Color.BLACK;
        float d = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(bg);
        int padH = Math.round(16 * d);
        row.setPadding(padH, Math.round(10 * d), padH, Math.round(8 * d));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(12 * d);
        row.setLayoutParams(lp);

        TextView name = new TextView(this);
        name.setText(title);
        name.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        name.setTextSize(15f);
        name.setTextColor(textColor);
        name.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = new TextView(this);
        value.setText(formatEuro(sum, currency));
        value.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        value.setTextSize(15f);
        value.setTextColor(textColor);
        value.setGravity(Gravity.END);

        row.addView(name);
        row.addView(value);
        container.addView(row);
    }

    private long placeBal(Map<String, Long> map, String place) {
        return map != null && map.containsKey(place) ? map.get(place) : 0L;
    }

    private void openHistory(String account, String place) {
        Intent i = new Intent(this, PlaceHistoryActivity.class);
        i.putExtra(PlaceHistoryActivity.EXTRA_ACCOUNT, account);
        i.putExtra(PlaceHistoryActivity.EXTRA_PLACE, place);
        startActivity(i);
    }

    private void addDivider() {
        View divider = new View(this);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 2);
        dp.topMargin = 12;
        dp.bottomMargin = 4;
        divider.setLayoutParams(dp);
        divider.setBackgroundColor(getColor(R.color.grey_text));
        container.addView(divider);
    }

    private void addRow(String label, long cents, boolean bold, boolean indent,
                        boolean clickable, View.OnClickListener onClick, String currency) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int lead = indent ? Math.round(24 * getResources().getDisplayMetrics().density) : 0;
        row.setPadding(lead, indent ? 16 : 22, 0, indent ? 16 : 22);
        if (clickable) {
            row.setClickable(true);
            row.setOnClickListener(onClick);
            android.util.TypedValue tv = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            row.setBackgroundResource(tv.resourceId);
        }

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextSize(bold ? 17f : 16f);
        name.setTypeface(android.graphics.Typeface.DEFAULT, bold ? android.graphics.Typeface.BOLD
                : android.graphics.Typeface.NORMAL);
        name.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = new TextView(this);
        value.setText(formatEuro(cents, currency));
        value.setTextSize(bold ? 17f : 16f);
        value.setGravity(Gravity.END);
        value.setTypeface(android.graphics.Typeface.MONOSPACE, bold ? android.graphics.Typeface.BOLD
                : android.graphics.Typeface.NORMAL);
        value.setTextColor(cents < 0 ? getColor(R.color.expense_red) : getColor(R.color.income_green));

        row.addView(name);
        row.addView(value);
        container.addView(row);
    }

    // ---- Umbuchen ----

    /**
     * Konten mit mindestens einem Ort – nur zwischen Orten läßt sich umbuchen. Die Reihenfolge ist die der
     * Kontenliste. Ein Konto, für das Orte angelegt sind, das aber geschlossen ist, fällt mit heraus:
     * dorthin bucht man ohnehin nichts mehr.
     */
    private List<String> accountsWithPlaces() {
        List<String> out = new ArrayList<>();
        for (String account : accountsOrder) {
            if (!placesStore.getPlaces(account).isEmpty()) {
                out.add(account);
            }
        }
        return out;
    }

    @SuppressLint("InflateParams")   // Dialoginhalt, siehe Begruendung an der inflate-Zeile
    private void showTransferDialog() {
        final List<String> accounts = accountsWithPlaces();
        if (accounts.isEmpty()) {
            Toast.makeText(this, R.string.no_accounts, Toast.LENGTH_LONG).show();
            return;
        }
        // inflate(..., null, false) ist bei einem Dialog richtig: Die Ansicht geht an setView und hat zu
        // diesem Zeitpunkt keine Eltern, von denen sie Layout-Vorgaben übernehmen könnte. Lint meldet das
        // trotzdem als InflateParams.
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_transfer, null, false);
        MaterialAutoCompleteTextView accountField = view.findViewById(R.id.transferAccount);
        MaterialAutoCompleteTextView from = view.findViewById(R.id.transferFrom);
        MaterialAutoCompleteTextView to = view.findViewById(R.id.transferTo);
        TextInputEditText amount = view.findViewById(R.id.transferAmount);
        CalcKeyboardView.installToggling(amount, (android.widget.LinearLayout) view, false);

        PickerAdapters.accounts(repository, accountField, accounts);
        final String[] account = {startAccount(accounts)};
        accountField.setText(account[0], false);
        fillTransferPlaces(from, to, account[0]);
        // Über PickerBehaviour: das Konto kann auch getippt und stehengelassen werden, dann fällt kein
        // Antippen eines Listeneintrags an – die Ortslisten blieben sonst die des alten Kontos.
        PickerBehaviour.onCommitted(accountField, value -> {
            account[0] = value;
            fillTransferPlaces(from, to, account[0]);
        });

        new AppDialog(this)
                .setTitle(R.string.transfer_title)
                .setView(AppDialog.scrollable(view))
                .setPositiveButton(R.string.transfer_do, (d, w) -> {
                    // Der Knopf nimmt dem Feld nicht zwangsläufig den Fokus; ein Feld mitten in der Suche
                    // ist leer. Erst die Suche beenden, dann lesen.
                    PickerBehaviour.settleAll(view);

                    String f = textOf(from);
                    String t = textOf(to);
                    Long cents = parseCents(textOf(amount));
                    if (cents == null || cents <= 0 || f.equals(t)) {
                        Toast.makeText(this, R.string.transfer_invalid, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    repository.saveTransfer(account[0], f, t, cents, this::refresh);
                })
                .show();
    }

    /** Von/Nach-Dropdowns auf die Orte des Kontos setzen; „Nach" = Standardort des Kontos. */
    private void fillTransferPlaces(MaterialAutoCompleteTextView from, MaterialAutoCompleteTextView to,
                                    String account) {
        List<String> options = placeOptions(account);
        PickerAdapters.places(from, options);
        PickerAdapters.places(to, options);
        String standardort = placesStore.getDefaultPlace(account);
        String toPreset = (!standardort.isEmpty() && options.contains(standardort))
                ? standardort : options.get(options.size() - 1);
        to.setText(toPreset, false);
        // „Von" auf den ersten Ort, der nicht dem „Nach"-Ort entspricht.
        String fromPreset = options.get(0);
        if (fromPreset.equals(toPreset) && options.size() > 1) {
            fromPreset = options.get(1);
        }
        from.setText(fromPreset, false);
    }

    // ---- Kassensturz ----

    @SuppressLint("InflateParams")   // Dialoginhalt, siehe Begruendung an der inflate-Zeile
    private void showReconcileDialog() {
        if (accountsOrder.isEmpty()) {
            Toast.makeText(this, R.string.no_accounts, Toast.LENGTH_LONG).show();
            return;
        }
        // inflate(..., null, false) ist bei einem Dialog richtig: Die Ansicht geht an setView und hat zu
        // diesem Zeitpunkt keine Eltern, von denen sie Layout-Vorgaben übernehmen könnte. Lint meldet das
        // trotzdem als InflateParams.
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_reconcile, null, false);
        MaterialAutoCompleteTextView accountField = view.findViewById(R.id.reconcileAccount);
        MaterialAutoCompleteTextView place = view.findViewById(R.id.reconcilePlace);
        View placeLayout = view.findViewById(R.id.reconcilePlaceLayout);
        TextInputEditText amount = view.findViewById(R.id.reconcileAmount);
        com.google.android.material.checkbox.MaterialCheckBox createBooking =
                view.findViewById(R.id.reconcileCreateBooking);
        CalcKeyboardView.installToggling(amount, (android.widget.LinearLayout) view, false);

        PickerAdapters.accounts(repository, accountField, accountsOrder);
        final String[] account = {startAccount(accountsOrder)};
        accountField.setText(account[0], false);
        fillReconcilePlaces(place, placeLayout, account[0]);
        PickerBehaviour.onCommitted(accountField, value -> {
            account[0] = value;
            fillReconcilePlaces(place, placeLayout, account[0]);
        });

        // Empfänger/Kategorie der Ausgleichsbuchung: dauerhaft gemerkte Vorgabe, anfangs leer.
        MaterialButton target = view.findViewById(R.id.btnReconcileTarget);
        final String[] payee = {settings.getReconcilePayee()};
        final String[] category = {settings.getReconcileCategory()};
        showReconcileTarget(target, payee[0], category[0]);

        androidx.appcompat.app.AlertDialog dialog =
                new AppDialog(this)
                        .setTitle(R.string.reconcile_title)
                        .setView(AppDialog.scrollable(view))
                        .setPositiveButton(R.string.reconcile_do, (d, w) -> {
                            // Der Knopf nimmt dem Feld nicht zwangsläufig den Fokus; ein Feld mitten in der Suche
                            // ist leer. Erst die Suche beenden, dann lesen.
                            PickerBehaviour.settleAll(view);

                            // Ort bleibt frei: Konten ohne angelegte Orte werden als Ganzes abgestimmt.
                            String p = textOf(place);
                            Long cents = parseCents(textOf(amount));
                            if (cents == null) {
                                Toast.makeText(this, R.string.error_amount, Toast.LENGTH_SHORT).show();
                                return;
                            }
                            repository.saveReconcile(account[0], p, cents, createBooking.isChecked(),
                                    payee[0], category[0], this::refresh);
                        })
                        .create();
        // „Übernehmen" bleibt gesperrt, solange eine Buchung erzeugt werden soll, aber Empfänger oder
        // Kategorie fehlen.
        dialog.setOnShowListener(d -> {
            final Runnable update = () -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    .setEnabled(ReconcileTarget.canApply(createBooking.isChecked(), payee[0], category[0]));
            createBooking.setOnCheckedChangeListener((b, checked) -> update.run());
            target.setOnClickListener(v -> showReconcileTargetDialog(payee, category, target, update));
            update.run();
        });
        dialog.show();
    }

    /** Beschriftet den Festlegen-Knopf mit „Empfänger / Kategorie" bzw. der Aufforderung. */
    private void showReconcileTarget(MaterialButton button, String payee, String category) {
        String label = ReconcileTarget.label(payee, category);
        button.setText(label == null ? getString(R.string.reconcile_target_empty) : label);
    }

    /** Empfänger und Kategorie der Ausgleichsbuchung festlegen – „Speichern" merkt sie dauerhaft. */
    private void showReconcileTargetDialog(String[] payee, String[] category, MaterialButton button,
                                           Runnable onSaved) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_reconcile_target, null, false);
        MaterialAutoCompleteTextView payeeField = view.findViewById(R.id.reconcileTargetPayee);
        MaterialAutoCompleteTextView categoryField = view.findViewById(R.id.reconcileTargetCategory);
        payeeField.setText(payee[0]);
        categoryField.setText(category[0]);
        repository.getPayeeNames(names -> PickerAdapters.payees(payeeField, names));
        repository.getCategoriesGrouped(g -> PickerAdapters.categories(categoryField, new CategoryFilterAdapter(this, null,
                getString(R.string.category_group_expense), g.expense,
                getString(R.string.category_group_income), g.income)));

        new AppDialog(this)
                .setTitle(R.string.reconcile_target_title)
                .setView(view)
                .setPositiveButton(R.string.reconcile_target_save, (d, w) -> {
                    // Der Knopf nimmt dem Feld nicht zwangsläufig den Fokus; ein Feld mitten in der Suche
                    // ist leer. Erst die Suche beenden, dann lesen.
                    PickerBehaviour.settleAll(view);

                    payee[0] = textOf(payeeField);
                    category[0] = textOf(categoryField);
                    settings.setReconcileTarget(payee[0], category[0]);
                    showReconcileTarget(button, payee[0], category[0]);
                    onSaved.run();
                })
                .show();
    }

    /**
     * Ortsauswahl des Kassensturzes; ohne angelegte Orte verschwindet sie ganz – ein Konto ohne Orte wird
     * als Ganzes abgestimmt, ein leeres Feld wäre dafür nur Beiwerk.
     */
    private void fillReconcilePlaces(MaterialAutoCompleteTextView place, View layout, String account) {
        List<String> places = placesStore.getPlaces(account);
        PickerAdapters.places(place, places);
        place.setText(places.isEmpty() ? "" : places.get(0), false);
        layout.setVisibility(places.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Standardkonto, sonst das erste der übergebenen Konten. */
    private String startAccount(List<String> candidates) {
        String def = settings.getDefaultAccount();
        if (!def.isEmpty() && candidates.contains(def)) {
            return def;
        }
        return candidates.get(0);
    }

    private List<String> placeOptions(String account) {
        List<String> options = new ArrayList<>(placesStore.getPlaces(account));
        options.add(PlacesStore.NO_PLACE);
        return options;
    }

    private String textOf(android.widget.EditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    private Long parseCents(String raw) {
        return de.spahr.ausgaben.settings.AmountExpression.toCents(raw);
    }

    private String formatEuro(long signedCents, String currency) {
        return de.spahr.ausgaben.settings.MoneyFormat.display(signedCents, currency);
    }
}
