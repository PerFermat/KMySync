package de.spahr.ausgaben.ui;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.slider.RangeSlider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;


import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.db.SecurityTx;
import de.spahr.ausgaben.settings.AmountExpression;
import de.spahr.ausgaben.settings.Currencies;
import de.spahr.ausgaben.settings.MoneyFormat;
import de.spahr.ausgaben.settings.DateFormats;

/**
 * Vollbild-Historie eines Wertpapiers: im grünen Kopf der Wertpapiername, in der Saldenzeile per Klick
 * durchschaltbar Wert → Nettoeinsatz → Gewinn/Verlust – jeweils nur für dieses Wertpapier. Darunter alle
 * Käufe/Verkäufe/Dividenden.
 */
public class SecurityHistoryActivity extends LocalizedActivity {

    public static final String EXTRA_DEPOT = "depot";
    public static final String EXTRA_KMY_ID = "kmyId";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_SECURITY_VALUE = "securityValue";


    private String depot;
    private String kmyId;
    private String securityName = "";
    private Repository repository;
    private Repository.DepotMetrics metrics;
    private java.util.List<Integer> saldoModes = new java.util.ArrayList<>();
    private int saldoIndex = 0;

    private TextView saldoLabel;
    private TextView saldoValue;
    private LinearLayout container;

    private java.util.List<SecurityTx> allTx = new java.util.ArrayList<>();
    /** Aktive Bewegungsarten (leer = alle). */
    private final java.util.Set<String> filterActions = new java.util.HashSet<>();
    private Long filterFrom;
    private Long filterTo;
    /** Dividenden brutto (true) oder netto anzeigen – aus den Einstellungen. */
    private boolean dividendGross = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_security_history);

        depot = getIntent().getStringExtra(EXTRA_DEPOT);
        kmyId = getIntent().getStringExtra(EXTRA_KMY_ID);
        securityName = orEmpty(getIntent().getStringExtra(EXTRA_NAME));

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(securityName);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.security_menu);
        toolbar.getMenu().findItem(R.id.action_filter).setTitle(getString(R.string.action_filter));
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_filter) {
                showFilterDialog();
                return true;
            }
            return false;
        });

        saldoLabel = findViewById(R.id.textSaldoLabel);
        saldoValue = findViewById(R.id.textBalance);
        findViewById(R.id.saldoHeader).setOnClickListener(v -> {
            if (!saldoModes.isEmpty()) {
                saldoIndex = (saldoIndex + 1) % saldoModes.size();
                showSaldo();
            }
        });

        container = findViewById(R.id.historyContainer);
        findViewById(R.id.fabAddTx).setOnClickListener(v -> openEditor(null));

        androidx.core.widget.NestedScrollView historyScroll = findViewById(R.id.historyScroll);
        com.google.android.material.floatingactionbutton.FloatingActionButton fabScrollTop =
                findViewById(R.id.fabScrollTop);
        fabScrollTop.setOnClickListener(v -> historyScroll.smoothScrollTo(0, 0));
        historyScroll.setOnScrollChangeListener(
                (androidx.core.widget.NestedScrollView.OnScrollChangeListener) (v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                    if (scrollY > 0) {
                        fabScrollTop.show();
                    } else {
                        fabScrollTop.hide();
                    }
                });

        repository = new Repository(this);
    }

    /**
     * Neu laden statt nur beim Öffnen: aus der Erfassungsmaske kommt man mit einer angelegten, geänderten
     * oder gelöschten Bewegung zurück – die Liste und die Saldenzeile müssen das zeigen.
     */
    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    /** Kennzahlen (Saldozeile) und Bewegungen neu laden – nach dem Setzen/Löschen eines manuellen Werts. */
    private void reload() {
        repository.getSecurityMetrics(depot, kmyId, m -> {
            metrics = m;
            saldoModes = DepotSaldo.modes(m);
            if (saldoIndex >= saldoModes.size()) {
                saldoIndex = 0;
            }
            showSaldo();
        });
        repository.getSecurityTransactions(depot, kmyId, txs -> {
            allTx = txs;
            renderTx();
        });
    }

    private void renderTx() {
        dividendGross = new de.spahr.ausgaben.settings.SettingsStore(this).isDividendGross();
        container.removeAllViews();
        boolean any = false;
        for (SecurityTx tx : allTx) {
            if (!matchesFilter(tx)) {
                continue;
            }
            any = true;
            container.addView(buildRow(tx));
        }
        if (!any) {
            TextView t = new TextView(this);
            t.setText(allTx.isEmpty() ? R.string.depot_no_history : R.string.depot_no_match);
            t.setPadding(0, 24, 0, 0);
            container.addView(t);
        }
    }

    private void showSaldo() {
        if (metrics == null || saldoModes.isEmpty()) {
            return;
        }
        int mode = saldoModes.get(saldoIndex % saldoModes.size());
        DepotSaldo.apply(this, saldoLabel, saldoValue, metrics, mode, securityName);
    }

    private boolean matchesFilter(SecurityTx tx) {
        if (!filterActions.isEmpty() && !filterActions.contains(tx.action)) {
            return false;
        }
        if (filterFrom != null && tx.date < filterFrom) {
            return false;
        }
        return filterTo == null || tx.date <= filterTo;
    }

    private void showFilterDialog() {
        if (allTx.isEmpty()) {
            return;
        }
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_security_filter, null, false);
        MaterialCheckBox cbBuy = view.findViewById(R.id.cbBuy);
        MaterialCheckBox cbSell = view.findViewById(R.id.cbSell);
        MaterialCheckBox cbDividend = view.findViewById(R.id.cbDividend);
        boolean all = filterActions.isEmpty();
        cbBuy.setChecked(all || filterActions.contains("buy"));
        cbSell.setChecked(all || filterActions.contains("sell"));
        cbDividend.setChecked(all || filterActions.contains("dividend"));

        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (SecurityTx tx : allTx) {
            min = Math.min(min, tx.date);
            max = Math.max(max, tx.date);
        }
        RangeSlider slider = view.findViewById(R.id.dateSlider);
        EditText fromField = view.findViewById(R.id.dateFrom);
        EditText toField = view.findViewById(R.id.dateTo);
        final MonthRange range = MonthRange.attach(slider, fromField, toField, min, max, filterFrom, filterTo);

        new AppDialog(this)
                .setTitle(R.string.action_filter)
                .setView(view)
                .setNeutralButton(R.string.filter_reset, (d, w) -> {
                    filterActions.clear();
                    filterFrom = null;
                    filterTo = null;
                    renderTx();
                })
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    filterActions.clear();
                    // Alle drei gewählt (oder keine) → keine Aktions-Einschränkung (zeigt auch Ein-/Ausbuchungen).
                    if (!(cbBuy.isChecked() && cbSell.isChecked() && cbDividend.isChecked())) {
                        if (cbBuy.isChecked()) {
                            filterActions.add("buy");
                            filterActions.add("reinvest");
                        }
                        if (cbSell.isChecked()) {
                            filterActions.add("sell");
                        }
                        if (cbDividend.isChecked()) {
                            filterActions.add("dividend");
                        }
                    }
                    filterFrom = range.getFromMillis();
                    filterTo = range.getToMillis();
                    renderTx();
                })
                .show();
    }

    /** Tabellarische Zeile im Stil der Kontenbewegungen: links Aktion + Datum/Stück, rechts der Betrag (farbig). */
    private View buildRow(SecurityTx tx) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padV = dp(10);
        row.setPadding(0, padV, 0, padV);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView action = new TextView(this);
        action.setText(actionLabel(tx.action));
        action.setTextSize(16f);
        action.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        TextView sub = new TextView(this);
        String subLine = DateFormats.date(tx.date);
        if (tx.shares != 0) {
            subLine += "  ·  " + shares(tx.shares) + " " + getString(R.string.depot_shares_unit);
        }
        sub.setText(subLine);
        sub.setTextSize(13f);
        sub.setTextColor(getColor(R.color.grey_text));

        left.addView(action);
        left.addView(sub);

        // Dividenden je nach Einstellung brutto (amountCents) oder netto (netCents) anzeigen.
        long shown = "dividend".equals(tx.action) && !dividendGross ? tx.netCents : tx.amountCents;
        boolean editable = "add".equals(tx.action) || "remove".equals(tx.action);
        TextView amount = new TextView(this);
        if (shown != 0) {
            amount.setText(money(shown));
        } else if (editable) {
            // KMyMoney liefert für Ein-/Ausbuchungen nie einen Wert – Platzhalter statt leerer Spalte,
            // damit die Zeile erkennbar antippbar wirkt statt kaputt.
            amount.setText(R.string.depot_tx_set_value);
            amount.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
        } else {
            amount.setText("");
        }
        amount.setTextSize(16f);
        if (shown != 0) {
            amount.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        }
        amount.setGravity(Gravity.END);
        amount.setTextColor(shown != 0 ? amountColor(tx.action) : getColor(R.color.grey_text));

        // Rechte Spalte: Betrag, darunter das Kennzeichen – dieselbe Anordnung wie in der Buchungsliste
        // (item_booking.xml). Steht die Bewegung schon in der KMyMoney-Datei, trägt sie dort dasselbe
        // grüne Wort. Ein Gegenstück «noch nicht exportiert» gibt es bewusst nicht: die Abwesenheit des
        // Wortes ist dort wie hier die Auskunft.
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        right.addView(amount);
        if (!tx.pending) {
            TextView exported = new TextView(this);
            exported.setText(R.string.exported_badge);
            exported.setTextSize(11f);
            exported.setTextColor(getColor(R.color.income_green));
            exported.setGravity(Gravity.END);
            right.addView(exported);
        }

        row.addView(left);
        row.addView(right);
        // Kurzer Klick = Details ansehen, langer Klick = Wert bearbeiten (nur Ein-/Ausbuchungen).
        android.util.TypedValue bg = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, bg, true);
        row.setBackgroundResource(bg.resourceId);
        row.setOnClickListener(v -> openEditor(tx));
        if (editable) {
            row.setOnLongClickListener(v -> {
                showValueEditDialog(tx);
                return true;
            });
        }
        return row;
    }

    /**
     * Öffnet die Erfassungsmaske: mit {@code tx} zum Ansehen (bzw. Ändern, solange die Bewegung noch nicht
     * exportiert ist), ohne sie zum Anlegen einer neuen.
     */
    private void openEditor(SecurityTx tx) {
        android.content.Intent i = new android.content.Intent(this, SecurityTxEditActivity.class);
        i.putExtra(SecurityTxEditActivity.EXTRA_DEPOT, depot);
        i.putExtra(SecurityTxEditActivity.EXTRA_KMY_ID, kmyId);
        i.putExtra(SecurityTxEditActivity.EXTRA_NAME, securityName);
        if (tx != null) {
            i.putExtra(SecurityTxEditActivity.EXTRA_TX_ID, tx.id);
        }
        startActivity(i);
    }

    /**
     * Stückpreis/Dividende je Stück: bis zu vier Nachkommastellen (Kurse sind selten glatt), aber
     * mindestens zwei, plus Währungskennzeichen. Das Dezimalzeichen kommt aus den Einstellungen.
     */
    private String unitPrice(double value) {
        String s = MoneyFormat.decimal(value, 2, 4);
        String currency = Currencies.getDefault();
        if (!MoneyFormat.isCurrencyShown() || currency == null || currency.trim().isEmpty()) {
            return s;
        }
        return s + " " + currency.trim();
    }

    /** Verkauf/Ausbuchung = rot, Kauf/Wiederanlage/Einbuchung = grün, Dividende = Standardfarbe. */
    private int amountColor(String action) {
        switch (action == null ? "" : action) {
            case "sell":
            case "remove":
                return getColor(R.color.expense_red);
            case "buy":
            case "reinvest":
            case "add":
                return getColor(R.color.income_green);
            default:
                return primaryTextColor();
        }
    }

    /** Langer Klick auf eine Ein-/Ausbuchung: manuellen Wert festlegen (KMyMoney liefert dort nie einen). */
    private void showValueEditDialog(SecurityTx tx) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextInputLayout til = new TextInputLayout(this);
        til.setHint(getString(R.string.depot_tx_value_hint));
        til.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText input = new TextInputEditText(til.getContext());
        if (tx.amountCents > 0) {
            input.setText(MoneyFormat.plain(tx.amountCents));
        }
        til.addView(input);
        int pad = dp(16);
        box.setPadding(pad, 0, pad, 0);
        box.addView(til);
        // Eigene Rechentastatur statt der System-Tastatur (erscheint bei Fokus des Betragsfelds).
        CalcKeyboardView calc = CalcKeyboardView.installToggling(input, box, false);
        input.requestFocus();

        // Einziges Feld im Dialog: OK auf der Rechentastatur übernimmt direkt (kein separater OK-Knopf
        // nötig) – überschreibt den installToggling()-Standard (nur Fokus verlassen).
        final AlertDialog[] dialogRef = new AlertDialog[1];
        calc.setOnOk(valid -> {
            if (!valid) {
                Toast.makeText(this, R.string.budget_invalid_amount, Toast.LENGTH_SHORT).show();
                return;
            }
            String raw = input.getText() == null ? "" : input.getText().toString().trim();
            if (raw.isEmpty()) {
                repository.clearSecurityTxValue(depot, kmyId, tx.date, tx.action, tx.shares, this::reload);
            } else {
                Long cents = AmountExpression.toCents(raw);
                if (cents == null || cents < 0) {
                    Toast.makeText(this, R.string.budget_invalid_amount, Toast.LENGTH_SHORT).show();
                    return;
                }
                repository.saveSecurityTxValue(depot, kmyId, tx.date, tx.action, tx.shares, cents, this::reload);
            }
            if (dialogRef[0] != null) {
                dialogRef[0].dismiss();
            }
        });

        AlertDialog dialog = new AppDialog(this)
                .setTitle(getString(R.string.depot_tx_value_title, actionLabel(tx.action)))
                .setView(AppDialog.scrollable(box))
                .create();
        dialogRef[0] = dialog;
        // Das fokussierte Betragsfeld darf nicht die System-Tastatur des Dialogfensters hochziehen –
        // die eigene Rechentastatur erscheint stattdessen über den Fokus-Listener.
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        dialog.show();
    }

    private int primaryTextColor() {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.textColorPrimary, tv, true);
        return getColor(tv.resourceId);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String actionLabel(String action) {
        switch (action == null ? "" : action) {
            case "buy":
                return getString(R.string.action_buy);
            case "sell":
                return getString(R.string.action_sell);
            case "dividend":
                return getString(R.string.action_dividend);
            case "add":
                return getString(R.string.action_add);
            case "remove":
                return getString(R.string.action_remove);
            case "reinvest":
                return getString(R.string.action_reinvest);
            default:
                return action == null ? "" : action;
        }
    }

    private String money(long cents) {
        return de.spahr.ausgaben.settings.MoneyFormat.display(cents, Currencies.getDefault());
    }

    /** Stückzahl im eingestellten Zahlenformat (siehe {@code MoneyFormat.SHARE_DECIMALS}). */
    private static String shares(double v) {
        return MoneyFormat.shares(v);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
