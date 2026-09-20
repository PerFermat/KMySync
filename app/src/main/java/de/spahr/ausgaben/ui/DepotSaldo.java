package de.spahr.ausgaben.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.settings.Currencies;
import de.spahr.ausgaben.settings.MoneyFormat;

/**
 * Gemeinsame Logik der durchschaltbaren Depot-Saldenzeile (Depot- wie Einzelwertpapier-Ansicht):
 * Depotwert → Käufe → Verkäufe (falls vorhanden) → Dividenden (falls vorhanden) → Nettoeinsatz →
 * Gewinn/Verlust. Verkäufe/Dividenden erscheinen nur, wenn dazu Werte vorliegen.
 */
final class DepotSaldo {

    static final int VALUE = 0;
    static final int BUY = 1;
    static final int SELL = 2;
    static final int DIVIDEND = 3;
    static final int NET = 4;
    static final int GAIN = 5;

    private DepotSaldo() {
    }

    /** Aktive Ansichten in Reihenfolge; Verkäufe/Dividenden nur bei vorhandenen Beträgen. */
    static List<Integer> modes(Repository.DepotMetrics m) {
        List<Integer> l = new ArrayList<>();
        l.add(VALUE);
        l.add(BUY);
        if (m.sellCents != 0) {
            l.add(SELL);
        }
        if (m.dividendCents != 0) {
            l.add(DIVIDEND);
        }
        l.add(NET);
        l.add(GAIN);
        return l;
    }

    /** Setzt Label + Betrag + Farbe für die gegebene Ansicht. {@code valueLabel} = Text der Wert-Ansicht. */
    @SuppressLint("SetTextI18n")   // Daten mit Trennzeichen, kein Satz
    static void apply(Context c, TextView label, TextView value, Repository.DepotMetrics m, int mode,
                      String valueLabel) {
        int neutral = primaryText(c);
        switch (mode) {
            case BUY:
                label.setText(c.getString(R.string.depot_buys));
                value.setText(money(m.buyCents));
                value.setTextColor(neutral);
                break;
            case SELL:
                label.setText(c.getString(R.string.depot_sells));
                value.setText(money(m.sellCents));
                value.setTextColor(neutral);
                break;
            case DIVIDEND:
                boolean gross = new de.spahr.ausgaben.settings.SettingsStore(c).isDividendGross();
                label.setText(c.getString(gross
                        ? R.string.depot_dividends_gross : R.string.depot_dividends_net));
                value.setText(money(m.dividendCents));
                value.setTextColor(neutral);
                break;
            case NET:
                label.setText(c.getString(R.string.depot_net_invested));
                value.setText(money(m.netInvestedCents));
                value.setTextColor(neutral);
                break;
            case GAIN:
                boolean gain = m.gainCents >= 0;
                label.setText(c.getString(gain ? R.string.depot_gain : R.string.depot_loss));
                value.setText("(" + MoneyFormat.decimal(m.gainPct, 2, 2) + " %) "
                        + money(m.gainCents));
                value.setTextColor(c.getColor(gain ? R.color.income_green : R.color.expense_red));
                break;
            case VALUE:
            default:
                label.setText(valueLabel);
                value.setText(money(m.valueCents));
                value.setTextColor(neutral);
                break;
        }
    }

    static String money(long cents) {
        return de.spahr.ausgaben.settings.MoneyFormat.display(cents, Currencies.getDefault());
    }

    private static int primaryText(Context c) {
        android.util.TypedValue tv = new android.util.TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.textColorPrimary, tv, true);
        return c.getColor(tv.resourceId != 0 ? tv.resourceId : android.R.color.black);
    }
}
