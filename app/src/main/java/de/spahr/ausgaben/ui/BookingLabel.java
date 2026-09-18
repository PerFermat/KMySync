package de.spahr.ausgaben.ui;

import android.content.Context;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Booking;

/**
 * Was in einer Liste dort steht, wo sonst der Empfänger steht.
 *
 * <p>Seit 2.1 darf der Empfänger leer bleiben – wie in KMyMoney, wo er nie Pflicht war. Damit wird aus
 * einer bis dahin theoretischen Frage eine, die jede Liste beantworten muß. Vorher gab es auf sie vier
 * Antworten an vier Stellen: {@code BookingAdapter} und {@code WidgetLarge} setzten einen festen
 * Gedankenstrich, {@code BudgetActivity} und {@code CategoryChartActivity} fielen auf die Kategorie
 * zurück. Hier stehen sie zusammen; {@code PayeeGuardTest} hält sie zusammen.</p>
 *
 * <h2>Die Rangfolge</h2>
 *
 * <ol>
 *   <li><b>Umbuchung</b> – Richtungspfeil und Empfänger, sonst das Gegenkonto, sonst „Umbuchung".
 *       Unverändert; eine Umbuchung hat immer ein Gegenüber.</li>
 *   <li><b>Empfänger</b>, wenn einer da ist.</li>
 *   <li><b>„Split-Buchung"</b> bei zwei oder mehr Kategorie-Teilen.</li>
 *   <li><b>Kategorie</b>.</li>
 *   <li><b>„*** NICHT ZUGEWIESEN ***"</b> – KMyMoneys eigener Wortlaut für eine Buchung, der noch
 *       jede Zuordnung fehlt. Dort ist das ebenfalls reine Anzeige und keine Kategorie, die es
 *       wirklich gäbe.</li>
 * </ol>
 *
 * <p><b>Warum Split vor die Kategorie gehört</b>, und zwar nicht aus Geschmack: {@link Booking#category}
 * ist bei einer Splitbuchung <em>nicht</em> leer. Sie trägt den betragsmäßig größten Teil – so legt
 * {@code KmyImporter} sie an, damit eine Dividende mit kleiner Gebühr nicht unter „Bankgebühren"
 * erscheint. Stünde Stufe 4 vorn, sähe eine Splitbuchung ohne Empfänger wie eine gewöhnliche Buchung
 * mit einer einzigen Kategorie aus, und von der Aufteilung wäre nichts zu sehen.</p>
 */
public final class BookingLabel {

    private BookingLabel() {
    }

    /**
     * @param istSplit ob diese Buchung zwei oder mehr Kategorie-Teile hat. Als Parameter statt aus
     *                 {@link Booking#parts} gelesen, weil die Teile in einer Liste nicht am einzelnen
     *                 {@code Booking} hängen: Die Masken halten sie als Karte je Buchung
     *                 ({@code splitsByBooking}), das Widget holt sie sich über
     *                 {@code BookingDao.splitBookingIds}. Beide wüßten sonst nichts davon.
     */
    public static String title(Context ctx, Booking b, boolean istSplit) {
        if (b.isTransfer) {
            String name = !b.payee.isEmpty() ? b.payee
                    : (b.transferAccount == null || b.transferAccount.isEmpty()
                        ? ctx.getString(R.string.type_transfer) : b.transferAccount);
            return (b.isIncome ? "← " : "→ ") + name;
        }
        if (!b.payee.isEmpty()) {
            return b.payee;
        }
        if (istSplit) {
            return ctx.getString(R.string.label_split_booking);
        }
        if (b.category != null && !b.category.isEmpty()) {
            return b.category;
        }
        return ctx.getString(R.string.label_unassigned);
    }
}
