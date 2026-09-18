package de.spahr.ausgaben.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.BookingSplit;
import de.spahr.ausgaben.settings.DateFormats;

public class BookingAdapter extends RecyclerView.Adapter<BookingAdapter.VH> {

    public interface Listener {
        /** Wird bei kurzem Druck auf eine Buchung ausgelöst (Ansicht ohne Änderung). */
        void onClick(Booking b);

        /** Wird bei langem Druck auf eine Buchung ausgelöst (Bearbeiten). */
        void onLongClick(Booking b);
    }

    private final List<Booking> items = new ArrayList<>();
    private Map<Long, List<BookingSplit>> splitsByBooking = new HashMap<>();
    /** Angezeigter (vorzeichenbehafteter) Betrag je Buchung – überschreibt den Gesamtbetrag (Kategorie-Filter). */
    private Map<Long, Long> amountOverride = new HashMap<>();
    /** Zeigt die Liste nur ein einziges Konto? Dann ist sein Name in jeder Zeile überflüssig. */
    private boolean singleAccount;
    private Listener listener;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Kategorie-Teile je Buchung, um Splitbuchungen in der Liste zu markieren. */
    public void setSplits(Map<Long, List<BookingSplit>> splits) {
        splitsByBooking = splits == null ? new HashMap<>() : splits;
        notifyDataSetChanged();
    }

    /** Setzt Betrags-Overrides (z. B. Teilbetrag der gefilterten Kategorie); leer = Gesamtbeträge. */
    public void setAmountOverride(Map<Long, Long> override) {
        amountOverride = override == null ? new HashMap<>() : override;
    }

    public void setItems(List<Booking> bookings) {
        items.clear();
        if (bookings != null) {
            items.addAll(bookings);
        }
        singleAccount = onlyOneAccount(items);
        notifyDataSetChanged();
    }

    /**
     * Tragen alle Buchungen denselben Kontonamen? Das trifft die Kontowahl aus der Schublade, greift
     * aber auch, wenn eine Kontengruppe oder ein Filter faktisch nur ein Konto übrig lässt.
     */
    static boolean onlyOneAccount(List<Booking> bookings) {
        if (bookings == null || bookings.isEmpty()) {
            return false;
        }
        String first = bookings.get(0).account;
        for (Booking b : bookings) {
            if (!first.equalsIgnoreCase(b.account)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Die graue Zeile unter dem Empfänger. Das Datum steht vorn, weil es in jeder Zeile gebraucht
     * wird: Früher kam der Kontoname zuerst, und bei langen Namen war vom Datum nichts mehr zu sehen
     * (die Zeile ist einzeilig und wird hinten abgeschnitten).
     *
     * <p>Die Uhrzeit kommt nur dazu, wenn sie etwas aussagt und Platz ist – also wenn die Liste
     * ohnehin nur ein Konto zeigt und die Zeit nicht 00:00 ist. Importierte Buchungen tragen immer
     * 00:00, dort wäre sie eine Scheingenauigkeit.
     */
    static String subtitle(long createdAt, String account, boolean singleAccount) {
        String date = singleAccount && hasTime(createdAt)
                ? DateFormats.dateTime(createdAt) : DateFormats.date(createdAt);
        if (singleAccount || account == null || account.isEmpty()) {
            return date;
        }
        return date + " · " + account;
    }

    /**
     * Gehört „Split" hinten an die Unterzeile?
     *
     * <p>Die Markierung entfällt genau dann, wenn oben schon „Split-Buchung" steht – sonst stünde das
     * Wort zweimal in derselben Reihe. Das ist der Fall, in dem {@link BookingLabel} auf seiner dritten
     * Stufe landet: keine Umbuchung und kein Empfänger. Eine Umbuchung zeigt oben ihr Gegenkonto, dort
     * wird die Markierung weiterhin gebraucht.</p>
     *
     * <p>Herausgezogen, damit die Regel prüfbar ist: Im Binden steckend wäre sie nur am fertigen Bild
     * zu sehen, und die doppelte Nennung fällt dort erst auf, wenn man eine Splitbuchung ohne
     * Empfänger vor sich hat.</p>
     */
    static boolean zeigeSplitMarker(Booking b, boolean istSplit) {
        return istSplit && (b.isTransfer || !b.payee.isEmpty());
    }

    /** Steckt in dem Zeitstempel eine echte Uhrzeit? Sekunden zählen dabei nicht mit. */
    private static boolean hasTime(long createdAt) {
        // Ohne Datum in der .kmy-Datei fällt der Import auf 0 zurück – daraus wäre je nach Zeitzone
        // eine erfundene Uhrzeit geworden.
        if (createdAt <= 0) {
            return false;
        }
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(createdAt);
        return c.get(Calendar.HOUR_OF_DAY) != 0 || c.get(Calendar.MINUTE) != 0;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_booking, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Booking b = items.get(position);
        List<BookingSplit> parts = splitsByBooking.get(b.id);
        boolean istSplit = parts != null && parts.size() >= 2;
        // Umbuchung, Empfänger, „Split-Buchung", Kategorie, „*** NICHT ZUGEWIESEN ***" – die Rangfolge
        // steht in BookingLabel, damit Liste, Widget, Budget und Kategorie-Aufriß dieselbe zeigen.
        h.payee.setText(BookingLabel.title(h.payee.getContext(), b, istSplit));
        // Datum und Konto in einer kompakten Zeile: „TT.MM.JJJJ · Konto".
        String line = subtitle(b.createdAt, b.account, singleAccount);
        if (zeigeSplitMarker(b, istSplit)) {
            line = line + "  ·  " + h.account.getContext().getString(R.string.split_marker);
        }
        h.account.setText(line);
        h.date.setVisibility(View.GONE);

        // Ohne die technischen GPS:/BELEG:-Tags – sonst steht bei aktivem GPS in jeder Zeile
        // „GPS: 50.1109,8.6821", und bei Buchungen ohne freien Text ist das sogar die einzige Notiz.
        String note = BookingEditActivity.stripTags(b.note);
        if (note.isEmpty()) {
            h.note.setVisibility(View.GONE);
        } else {
            h.note.setVisibility(View.VISIBLE);
            h.note.setText(note);
        }

        long signed = b.isIncome ? b.amountCents : -b.amountCents;
        // Bei Kategorie-Filter zeigt eine Splitbuchung nur den Teilbetrag der gewählten Kategorie.
        Long override = amountOverride.get(b.id);
        if (override != null) {
            signed = override;
        }
        String euros = formatEuro(signed, b.account);
        h.amount.setText(euros);
        // Farbe nach angezeigtem Vorzeichen: negativ (auch negativer Teilbetrag) = rot, sonst grün.
        int color = signed < 0
                ? h.amount.getResources().getColor(R.color.expense_red, null)
                : h.amount.getResources().getColor(R.color.income_green, null);
        h.amount.setTextColor(color);

        // Drei Zustände: nach dem Export geändert (gelb), exportiert (grün) oder noch keines von beidem.
        if (b.edited) {
            h.exported.setVisibility(View.VISIBLE);
            h.exported.setText(R.string.edited_badge);
            h.exported.setTextColor(h.exported.getResources().getColor(R.color.transfer_yellow, null));
        } else if (b.exported) {
            h.exported.setVisibility(View.VISIBLE);
            h.exported.setText(R.string.exported_badge);
            h.exported.setTextColor(h.exported.getResources().getColor(R.color.income_green, null));
        } else {
            h.exported.setVisibility(View.GONE);
        }

        h.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onClick(b);
            }
        });
        h.itemView.setOnLongClickListener(v -> {
            if (listener != null) {
                listener.onLongClick(b);
                return true;
            }
            return false;
        });
    }

    private String formatEuro(long signedCents, String account) {
        return de.spahr.ausgaben.settings.MoneyFormat.display(signedCents,
                de.spahr.ausgaben.settings.Currencies.forAccount(account));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView payee;
        final TextView account;
        final TextView date;
        final TextView note;
        final TextView amount;
        final TextView exported;

        VH(@NonNull View itemView) {
            super(itemView);
            payee = itemView.findViewById(R.id.textPayee);
            account = itemView.findViewById(R.id.textAccount);
            date = itemView.findViewById(R.id.textDate);
            note = itemView.findViewById(R.id.textNote);
            amount = itemView.findViewById(R.id.textAmount);
            exported = itemView.findViewById(R.id.textExported);
            // Laufschrift für lange Empfänger-Namen (bei großer Schrift), damit nichts abgeschnitten wird.
            payee.setSelected(true);
        }
    }
}
