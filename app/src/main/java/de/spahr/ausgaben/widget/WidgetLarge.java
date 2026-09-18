package de.spahr.ausgaben.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;

import androidx.core.content.ContextCompat;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.settings.Currencies;
import de.spahr.ausgaben.settings.DateFormats;
import de.spahr.ausgaben.settings.MoneyFormat;
import de.spahr.ausgaben.ui.MainActivity;

/**
 * Großes Widget (4×4): Saldo-Kopf mit Aktualisieren, die letzten Buchungen und eine Aktionsleiste.
 *
 * <p>Hier stand einmal ein {@code private static final SimpleDateFormat DATE} mit festem
 * {@code "dd.MM."}. Zwei Fehler in einer Zeile: Das Muster war fest deutsch, und weil das Objekt
 * statisch ist, teilten sich zwei gleichzeitig auffrischende Widgets eines, das nicht fadensicher
 * ist — und aufgefrischt wird aus dem Hintergrundfaden in {@link AusgabenWidget}. Beides erledigt
 * {@link DateFormats#date(long)}: Es folgt der Sprache und baut sein Format je Aufruf neu.</p>
 */
public class WidgetLarge extends AusgabenWidget {

    @Override
    protected int layoutId() {
        return R.layout.widget_large;
    }

    @Override
    protected void bindExtra(Context ctx, RemoteViews v, WidgetData d) {
        v.setOnClickPendingIntent(R.id.widgetActionBook, openMain(ctx, MainActivity.WIDGET_ACTION_NEW, 11));
        v.setOnClickPendingIntent(R.id.widgetActionVoice, openMain(ctx, MainActivity.WIDGET_ACTION_VOICE, 12));
        v.setOnClickPendingIntent(R.id.widgetActionBalances, openMain(ctx, MainActivity.WIDGET_ACTION_BALANCES, 14));
        v.setOnClickPendingIntent(R.id.widgetRefresh, refreshIntent(ctx));

        int[] rows = {R.id.widgetRow0, R.id.widgetRow1, R.id.widgetRow2};
        int[] payees = {R.id.widgetRow0Payee, R.id.widgetRow1Payee, R.id.widgetRow2Payee};
        int[] subs = {R.id.widgetRow0Sub, R.id.widgetRow1Sub, R.id.widgetRow2Sub};
        int[] amounts = {R.id.widgetRow0Amount, R.id.widgetRow1Amount, R.id.widgetRow2Amount};
        for (int idx = 0; idx < 3; idx++) {
            if (d.recent != null && idx < d.recent.size()) {
                Booking b = d.recent.get(idx);
                v.setViewVisibility(rows[idx], View.VISIBLE);
                v.setTextViewText(payees[idx], label(ctx, b));
                v.setTextViewText(subs[idx], sub(b));
                long signed = b.isIncome ? b.amountCents : -b.amountCents;
                v.setTextViewText(amounts[idx], MoneyFormat.display(signed, Currencies.forAccount(b.account)));
                v.setTextColor(amounts[idx], ContextCompat.getColor(ctx,
                        signed < 0 ? R.color.expense_red : R.color.income_green));
            } else {
                v.setViewVisibility(rows[idx], View.GONE);
            }
        }
    }

    /** Der Aktualisieren-Knopf fordert ein erneutes onUpdate für alle großen Widgets an. */
    private PendingIntent refreshIntent(Context ctx) {
        Intent i = new Intent(ctx, WidgetLarge.class);
        i.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        int[] ids = AppWidgetManager.getInstance(ctx)
                .getAppWidgetIds(new ComponentName(ctx, WidgetLarge.class));
        i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
        return PendingIntent.getBroadcast(ctx, 20, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static String label(Context ctx, Booking b) {
        if (b.isTransfer) {
            String name = !b.payee.isEmpty() ? b.payee
                    : (b.transferAccount == null || b.transferAccount.isEmpty()
                        ? ctx.getString(R.string.type_transfer) : b.transferAccount);
            return (b.isIncome ? "← " : "→ ") + name;
        }
        return b.payee.isEmpty() ? "—" : b.payee;
    }

    /**
     * Die graue Zeile unter dem Empfänger: <b>Datum zuerst</b>, dann die Kategorie.
     *
     * <p>Hier stand die Kategorie vorn und das Datum hinten — genau die Reihenfolge, die in der
     * Buchungsliste schon einmal umgedreht wurde, und aus demselben Grund: Die Zeile ist
     * {@code maxLines="1"} mit {@code ellipsize="end"}, sie wird also <em>hinten</em> abgeschnitten.
     * KMyMoney-Kategorien sind hierarchisch und entsprechend lang («Versicherungen:Krankenzusatz»);
     * bei größerer Schrift oder schmalerem Widget verschwand damit ausgerechnet das Datum, das man
     * in jeder Zeile braucht. Abgeschnitten werden darf die Kategorie — vom Datum muss der Anfang
     * stehen bleiben.</p>
     *
     * <p>Beim Umbau der Buchungsliste ({@code BookingAdapter.subtitle}) wurde das Widget übersehen.
     * Der Wächter in {@code DateFormatsTest} konnte es nicht finden: Er sucht feste Datumsmuster,
     * nicht die Reihenfolge innerhalb einer Zeile. Deshalb gibt es jetzt {@code WidgetSubtitleTest} —
     * paketsichtbar statt privat ist der Preis dafür.</p>
     *
     * <p>Das Datum steht <b>vollständig</b> da, mit Jahr. Hier stand einmal nur Tag und Monat, um
     * Platz zu sparen — aber ein Datum ohne Jahr ist bei älteren Buchungen mehrdeutig, und gerade im
     * Widget sieht man sie ohne weiteren Zusammenhang. Den Platz kostet es die Kategorie, und die ist
     * der entbehrlichere der beiden.</p>
     */
    static String sub(Booking b) {
        String cat = b.category == null ? "" : b.category;
        String date = DateFormats.date(b.createdAt);
        return cat.isEmpty() ? date : date + " · " + cat;
    }
}
