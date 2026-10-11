package de.spahr.ausgaben.notify;

import android.os.Parcel;
import android.os.Parcelable;

import de.spahr.ausgaben.db.Booking;

/**
 * Eine Zeile der Vorschlagsliste: eine aufgehobene Benachrichtigung und was die Vorlagen daraus gelesen
 * haben — oder dass sie nichts gelesen haben.
 *
 * <p>Das Gegenstück zu {@code StatementDraft} für gewöhnliche Buchungen. Der Entwurf lebt nur, solange
 * die Liste offen ist; er trägt den Rohtext der Meldung, und der wird nirgends abgelegt.</p>
 */
public class NotificationDraft implements Parcelable {

    public static final int PROBLEM_NONE = 0;
    /** Keine Vorlage hat die Meldung gelesen. Hält die Liste nicht auf — die Zeile wird nur nicht gebucht. */
    public static final int PROBLEM_UNRECOGNIZED = 1;
    /** Die App meldet für mehrere Konten, und nichts in der Meldung sagt, für welches. */
    public static final int PROBLEM_ACCOUNT = 2;
    /** Die Meldung nennt einen Betrag in anderer Währung als der des Kontos. */
    public static final int PROBLEM_FOREIGN = 3;

    // ---- die Meldung ----
    public long entryId;
    public String packageName = "";
    public String key = "";
    public long postTime;
    public String title = "";
    public String text = "";

    // ---- was daraus gebucht würde ----
    /** Ob eine Vorlage die Meldung gelesen hat. */
    public boolean recognized;
    /** In Cent, positiv; 0 = unbekannt. */
    public long cents;
    public boolean isIncome;
    public String payee = "";
    public String account = "";
    public long dateMillis;
    public String category = "";
    public Boolean categoryIsIncome;
    public String note = "";
    public String tags = "";

    // ---- Markierungen ----
    /** Das Kürzel der fremden Währung, wenn die Meldung eine nennt; sonst leer. */
    public String foreignCurrency = "";
    /** Im Konto steht schon eine Buchung über diesen Betrag, höchstens zwei Tage daneben. */
    public boolean dupBooked;
    /** Dieselbe Meldung liegt noch einmal im Puffer (die App hat sie aktualisiert). */
    public boolean dupBuffer;
    /** Der Nutzer hat die Zeile in der Maske bestätigt oder berichtigt. */
    public boolean edited;
    /** Aus dieser Zeile soll beim Abschluss eine Vorlage gelernt werden („nicht erkannt", dann gebucht). */
    public boolean learn;

    public NotificationDraft() {
    }

    public int problem() {
        if ((!recognized && !edited) || cents <= 0) {
            return PROBLEM_UNRECOGNIZED;
        }
        if (account.isEmpty()) {
            return PROBLEM_ACCOUNT;
        }
        // Erst nach dem Blick in die Maske: dort steht der Betrag, den der Nutzer für richtig hält.
        if (!foreignCurrency.isEmpty() && !edited) {
            return PROBLEM_FOREIGN;
        }
        return PROBLEM_NONE;
    }

    public boolean isBookable() {
        return problem() == PROBLEM_NONE;
    }

    /** Ob die Zeile den Abschluss aufhält: erkannt, aber so nicht buchbar. Nicht erkannte tun das nicht. */
    public boolean blocksSave() {
        int p = problem();
        return p == PROBLEM_ACCOUNT || p == PROBLEM_FOREIGN;
    }

    public Booking toBooking() {
        Booking b = new Booking();
        b.amountCents = cents;
        b.isIncome = isIncome;
        b.payee = payee;
        b.account = account;
        b.category = category;
        b.categoryIsIncome = categoryIsIncome;
        b.note = note;
        b.tags = tags;
        b.createdAt = dateMillis;
        b.exported = false;
        return b;
    }

    // ---- Parcelable ----

    protected NotificationDraft(Parcel in) {
        entryId = in.readLong();
        packageName = in.readString();
        key = in.readString();
        postTime = in.readLong();
        title = in.readString();
        text = in.readString();
        recognized = in.readInt() != 0;
        cents = in.readLong();
        isIncome = in.readInt() != 0;
        payee = in.readString();
        account = in.readString();
        dateMillis = in.readLong();
        category = in.readString();
        int side = in.readInt();
        categoryIsIncome = side < 0 ? null : Boolean.valueOf(side == 1);
        note = in.readString();
        tags = in.readString();
        foreignCurrency = in.readString();
        dupBooked = in.readInt() != 0;
        dupBuffer = in.readInt() != 0;
        edited = in.readInt() != 0;
        learn = in.readInt() != 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(entryId);
        out.writeString(packageName);
        out.writeString(key);
        out.writeLong(postTime);
        out.writeString(title);
        out.writeString(text);
        out.writeInt(recognized ? 1 : 0);
        out.writeLong(cents);
        out.writeInt(isIncome ? 1 : 0);
        out.writeString(payee);
        out.writeString(account);
        out.writeLong(dateMillis);
        out.writeString(category);
        out.writeInt(categoryIsIncome == null ? -1 : categoryIsIncome ? 1 : 0);
        out.writeString(note);
        out.writeString(tags);
        out.writeString(foreignCurrency);
        out.writeInt(dupBooked ? 1 : 0);
        out.writeInt(dupBuffer ? 1 : 0);
        out.writeInt(edited ? 1 : 0);
        out.writeInt(learn ? 1 : 0);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<NotificationDraft> CREATOR = new Creator<NotificationDraft>() {
        @Override
        public NotificationDraft createFromParcel(Parcel in) {
            return new NotificationDraft(in);
        }

        @Override
        public NotificationDraft[] newArray(int size) {
            return new NotificationDraft[size];
        }
    };
}
