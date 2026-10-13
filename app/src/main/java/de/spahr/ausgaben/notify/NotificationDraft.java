package de.spahr.ausgaben.notify;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.BookingSplit;

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
    /** Wo das Handy beim Eintreffen war („lat, lon"); leer, wenn unbekannt. */
    public String coords = "";

    // ---- was daraus gebucht würde ----
    /** Ob eine Vorlage die Meldung gelesen hat. */
    public boolean recognized;
    /** In Cent, positiv; 0 = unbekannt. */
    public long cents;
    public boolean isIncome;
    public String payee = "";
    /** Der Empfänger, wie die Meldung ihn schreibt – daraus entsteht der Satz für die Spracheingabe. */
    public String rawPayee = "";
    public String account = "";
    public long dateMillis;
    public String category = "";
    public Boolean categoryIsIncome;
    public String note = "";
    public String tags = "";
    /** Eine Aufteilung (zwei oder mehr Teile), gleich lang: Kategorie, Cent, Seite (-1/0/1). */
    public ArrayList<String> splitCategories = new ArrayList<>();
    public long[] splitCents = new long[0];
    public int[] splitSides = new int[0];
    /**
     * Die Spracheingabe hat diesen Satz gelesen wie die Vorlage: derselbe Betrag. Dann bucht beim
     * Abschluss die Sprachroutine selbst; sonst die Zeile, wie sie dasteht.
     */
    public boolean viaVoice;

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

    /**
     * Der Wortlaut der Meldung, wie er in die Notiz der Buchung kommt: die Überschrift, eine Leerzeile,
     * der Text — so, wie die Meldung selbst gegliedert ist.
     *
     * <p>Unverändert, samt Satzzeichen und Zeilenumbrüchen — ausdrücklich nicht die Fassung, an der die
     * Erkennung arbeitet. Die Umbrüche dürfen bleiben: das Notizfeld ist mehrzeilig, der Export in die
     * KMyMoney-Datei schreibt sie als {@code &#xa;} (so hält es KMyMoney selbst), die CSV setzt den
     * Wert in Anführungszeichen. Nur die Schreibweise des Umbruchs wird vereinheitlicht.</p>
     *
     * <p>Wiederholt der Text den Titel schon am Anfang, steht er nicht doppelt da.</p>
     */
    public String wording() {
        String t = clean(title);
        String x = clean(text);
        if (t.isEmpty() || x.startsWith(t)) {
            return x;
        }
        return x.isEmpty() ? t : t + "\n\n" + x;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    public boolean isSplit() {
        return splitCategories.size() >= 2 && splitCents.length == splitCategories.size()
                && splitSides.length == splitCategories.size();
    }

    /** Übernimmt die Aufteilung einer vorgeschlagenen Buchung; ohne Teile ist es keine. */
    public void setSplits(List<BookingSplit> parts) {
        splitCategories = new ArrayList<>();
        int n = parts == null ? 0 : parts.size();
        splitCents = new long[n];
        splitSides = new int[n];
        for (int i = 0; i < n; i++) {
            BookingSplit part = parts.get(i);
            splitCategories.add(part.category);
            splitCents[i] = part.amountCents;
            splitSides[i] = part.categoryIsIncome == null ? -1 : part.categoryIsIncome ? 1 : 0;
        }
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
        b.spokenText = rawPayee;
        if (isSplit()) {
            b.parts = new ArrayList<>();
            for (int i = 0; i < splitCategories.size(); i++) {
                b.parts.add(new BookingSplit(0, splitCategories.get(i), splitCents[i],
                        splitSides[i] < 0 ? null : Boolean.valueOf(splitSides[i] == 1)));
            }
        }
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
        rawPayee = in.readString();
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
        splitCategories = in.createStringArrayList();
        splitCents = in.createLongArray();
        splitSides = in.createIntArray();
        viaVoice = in.readInt() != 0;
        coords = in.readString();
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
        out.writeString(rawPayee);
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
        out.writeStringList(splitCategories);
        out.writeLongArray(splitCents);
        out.writeIntArray(splitSides);
        out.writeInt(viaVoice ? 1 : 0);
        out.writeString(coords);
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
