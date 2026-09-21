package de.spahr.ausgaben.ui;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.SecurityTx;
import de.spahr.ausgaben.db.SecurityTxSplit;
import de.spahr.ausgaben.util.CategorySplits;
import de.spahr.ausgaben.util.SecurityAmounts;

/**
 * Eine eingelesene Abrechnung auf dem Weg zur Buchung — noch nichts Gespeichertes, sondern der Stand
 * dessen, was aus der Datei herausgelesen und vom Nutzer berichtigt wurde.
 *
 * <p>Beim Einlesen mehrerer Abrechnungen auf einmal reicht ein einzelner Vorgang nicht mehr: die
 * Erkennungsliste ({@link StatementBatchActivity}) hält für jede Datei einen solchen Entwurf, gibt ihn
 * zum Berichtigen an die Erfassungsmaske und bekommt ihn von dort zurück. Erst „Alle speichern" macht
 * daraus Bewegungen und Geldbuchungen.</p>
 *
 * <p>Der Entwurf urteilt nicht selbst über Zahlen — {@link SecurityAmounts} ergänzt sie, {@link #problem()}
 * sagt nur, was zum Buchen noch fehlt. Damit gilt in der Liste dieselbe Rechnung und dieselbe Prüfung wie
 * in der Maske.</p>
 */
public class StatementDraft implements Parcelable {

    static final String BUY = SecurityTx.BUY;
    static final String SELL = SecurityTx.SELL;
    static final String DIVIDEND = SecurityTx.DIVIDEND;

    /** Anzeigename der Datei — das Einzige, was von einer unlesbaren Abrechnung bleibt. */
    public String fileName = "";
    /** Die schon in die Belegablage kopierte Abrechnung; beim Speichern wird sie zum Beleg. */
    public String stagedPath;
    /** Zwischengespeicherter Text der Abrechnung; die Maske zeigt daraus die Datumsauswahl. */
    public String textPath;

    public String isin;
    public String depot = "";
    public String kmyId = "";
    public String securityName = "";

    /** Eine der Aktionen {@code buy}, {@code sell}, {@code dividend}; {@code null} = nicht erkannt. */
    public String action;
    public long dateMillis = -1;
    public Double shares;
    public Double price;
    public Long grossCents;
    public Long feeCents;
    public Long netCents;

    public String moneyAccount = "";
    /**
     * Kategorie einer festen Gebühr aus der Erkennungsregel; leer, wenn keine angesetzt wurde.
     *
     * <p>Sie steht in der Regel und nicht im Beleg und schlägt deshalb die aus der letzten Bewegung
     * geratene: sie ist von Hand festgelegt, die andere nur erschlossen.</p>
     */
    public String fixedFeeCategory = "";
    /**
     * Die Kategoriezeilen der Steuer bzw. Gebühr und des Ertrags — dieselbe Aufteilung, die auch die
     * fertige Bewegung trägt (siehe {@link de.spahr.ausgaben.db.SecurityTxSplit}).
     *
     * <p>Solange die Kategorien noch nicht zugeordnet sind, stehen hier die reinen Beträge aus der
     * Abrechnung; die Zuordnung besorgt {@link de.spahr.ausgaben.util.CategorySplits}, sobald die
     * letzte Buchung derselben Art bekannt ist.</p>
     */
    public List<CategorySplits.Part> feeParts = new ArrayList<>();
    public List<CategorySplits.Part> incomeParts = new ArrayList<>();

    /**
     * Warum die Datei gar nicht erst ausgewertet werden konnte (Textbaustein), sonst 0. Ein solcher
     * Eintrag bleibt in der Liste stehen, statt still zu verschwinden — sonst suchte der Nutzer eine
     * Buchung, die es nie gab.
     */
    public int failure;
    /** Brutto, Steuer und Netto gehen nicht auf; dann wird nicht gerechnet, sondern berichtigt. */
    public boolean conflict;

    /** Diese Bewegung steht schon im Depot — ein Hinweis, keine Sperre. */
    public boolean dupBooked;
    /** Dieselbe Bewegung kam in der Auswahl schon vorher vor (die erste bleibt unmarkiert). */
    public boolean dupSelected;

    /**
     * In der Detailmaske wurde ein erkannter Schedule-Treffer (siehe
     * {@link de.spahr.ausgaben.db.ScheduleMatch}) aktiv abgewählt. Bleibt {@code false} (Regelfall,
     * auch wenn die Maske nie geöffnet wurde), wird beim endgültigen Speichern des Stapels trotzdem
     * automatisch zugeordnet — Opt-out, nicht Opt-in.
     */
    public boolean scheduleMatchOptOut;

    public StatementDraft() {
    }

    public boolean isDividend() {
        return DIVIDEND.equals(action);
    }

    /**
     * Ergänzt die fehlenden Zahlen — genau wie die Maske, nur ohne Anzeige. Alle abgelesenen Werte
     * stehen dabei fest ({@code keepGiven}): der Stückpreis der Bank ist genauer als einer, den man aus
     * Summe geteilt durch Stückzahl zurückrechnet.
     *
     * <p>Ohne Steuersatz: ein Entwurf stammt immer aus einer eingelesenen Abrechnung, und dort hat die
     * Regel gesucht. Was sie nicht fand, wurde nicht abgezogen — eine gerechnete Steuer wäre erfunden.</p>
     */
    public void resolve() {
        SecurityAmounts.Input in = new SecurityAmounts.Input();
        in.action = action == null ? BUY : action;
        in.keepGiven = true;
        in.shares = isDividend() ? null : shares;
        in.price = isDividend() ? null : price;
        in.grossCents = grossCents;
        in.feeCents = feeCents;
        in.netCents = netCents;

        SecurityAmounts.Result r = SecurityAmounts.solve(in);
        conflict = r.conflict;
        if (conflict) {
            return;
        }
        shares = isDividend() ? null : r.shares;
        price = isDividend() ? null : r.price;
        grossCents = r.grossCents;
        feeCents = r.feeCents;
        netCents = r.netCents;
    }

    /**
     * Was dem Eintrag zum Buchen fehlt, als Textbaustein; 0, wenn nichts fehlt. Die Reihenfolge geht vom
     * Grundsätzlichen zum Beiläufigen — genannt wird immer nur der erste Grund, denn wer die Datei nicht
     * lesen kann, dem nützt der Hinweis auf das fehlende Datum nichts.
     */
    public int problem() {
        if (failure != 0) {
            return failure;
        }
        if (kmyId.isEmpty()) {
            // Ohne ISIN kann die App gar nicht suchen; mit einer unbekannten immerhin fragen. Seit die
            // Zuordnung auch über Kennnummer und Kürzel geht, ist eine fehlende ISIN kein Ausschluss
            // mehr — sie wird erst zum Mangel, wenn auch sonst nichts passt.
            return isin == null || isin.isEmpty()
                    ? R.string.statement_problem_isin : R.string.statement_problem_security;
        }
        if (action == null) {
            return R.string.statement_problem_action;
        }
        if (conflict) {
            return R.string.security_tx_conflict;
        }
        if (dateMillis <= 0) {
            return R.string.statement_problem_date;
        }
        if (grossCents == null || grossCents <= 0 || netCents == null) {
            return R.string.statement_problem_amounts;
        }
        if (!isDividend() && (shares == null || shares <= 0)) {
            return R.string.statement_problem_shares;
        }
        if (moneyAccount.trim().isEmpty()) {
            return R.string.statement_problem_account;
        }
        return 0;
    }

    public boolean isBookable() {
        return problem() == 0;
    }

    /**
     * Der Hinweis auf eine Doppelung als Textbaustein; 0, wenn keine vorliegt. Anders als {@link #problem()}
     * hält er nichts auf — zweimal am selben Tag dasselbe Papier zum selben Preis zu kaufen ist selten,
     * aber möglich. Die schon gebuchte Doppelung sticht: sie ist der schwerere Fall.
     */
    public int duplicateHint() {
        if (dupBooked) {
            return R.string.statement_dup_booked;
        }
        return dupSelected ? R.string.statement_dup_selected : 0;
    }

    /**
     * Kennzeichnet in einer Auswahl jede Wiederholung — die <b>erste</b> ihrer Art bleibt unmarkiert,
     * damit ohne Rätselraten klar ist, welche Zeile weg kann.
     *
     * <p>Verglichen wird die fertige Bewegung ({@link #toTx()}), nicht der Entwurf: dort stehen die
     * Vorzeichen und die Dividendenregeln schon so, wie sie im Depot landen. Unvollständige Einträge
     * bleiben außen vor — was noch nicht buchbar ist, kann auch keine Doppelung sein.</p>
     */
    public static void markSelectionDuplicates(java.util.List<StatementDraft> drafts) {
        java.util.List<SecurityTx> seen = new java.util.ArrayList<>();
        for (StatementDraft d : drafts) {
            d.dupSelected = false;
            if (d.problem() != 0) {
                continue;
            }
            SecurityTx tx = d.toTx();
            for (SecurityTx earlier : seen) {
                if (tx.sameMovement(earlier)) {
                    d.dupSelected = true;
                    break;
                }
            }
            seen.add(tx);
        }
    }

    /** Die Bewegung, wie sie im Depot steht — dieselben Regeln wie in {@code SecurityTxEditActivity}. */
    public SecurityTx toTx() {
        SecurityTx tx = new SecurityTx();
        tx.depot = depot;
        tx.securityKmyId = kmyId;
        tx.securityName = securityName;
        tx.date = dateMillis;
        // Vorzeichen und Dividendenregeln stehen an einer Stelle – siehe SecurityTx.applyAmounts.
        tx.applyAmounts(action == null ? "" : action, shares,
                grossCents == null ? 0 : grossCents,
                netCents == null ? 0 : netCents,
                feeCents == null ? 0 : feeCents);
        tx.moneyAccount = moneyAccount.trim();
        addParts(tx, feeParts, false);
        if (isDividend()) {
            addParts(tx, incomeParts, true);
        }
        return tx;
    }

    private static void addParts(SecurityTx tx, List<CategorySplits.Part> parts, boolean income) {
        for (CategorySplits.Part part : parts) {
            if (part.category.trim().isEmpty()) {
                continue;
            }
            tx.parts.add(new SecurityTxSplit(0, income, part.category.trim(), part.cents,
                    part.label, tx.parts.size()));
        }
    }

    /**
     * Die Geldbuchung zur Bewegung: eine Umbuchung zwischen Geldkonto und Wertpapier. Beim Kauf verlässt
     * das Geld das Konto, bei Verkauf und Dividende kommt es an — bei der Dividende der Nettobetrag,
     * denn nur der wird gutgeschrieben.
     */
    public Booking toBooking() {
        return toTx().toMoneyBooking(netCents == null ? 0 : netCents);
    }

    // ---- Parcelable ----

    protected StatementDraft(Parcel in) {
        fileName = orEmpty(in.readString());
        stagedPath = in.readString();
        textPath = in.readString();
        isin = in.readString();
        depot = orEmpty(in.readString());
        kmyId = orEmpty(in.readString());
        securityName = orEmpty(in.readString());
        action = in.readString();
        dateMillis = in.readLong();
        shares = (Double) in.readValue(Double.class.getClassLoader());
        price = (Double) in.readValue(Double.class.getClassLoader());
        grossCents = (Long) in.readValue(Long.class.getClassLoader());
        feeCents = (Long) in.readValue(Long.class.getClassLoader());
        netCents = (Long) in.readValue(Long.class.getClassLoader());
        moneyAccount = orEmpty(in.readString());
        fixedFeeCategory = orEmpty(in.readString());
        feeParts = readParts(in);
        incomeParts = readParts(in);
        failure = in.readInt();
        conflict = in.readInt() != 0;
        dupBooked = in.readInt() != 0;
        dupSelected = in.readInt() != 0;
        scheduleMatchOptOut = in.readInt() != 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(fileName);
        out.writeString(stagedPath);
        out.writeString(textPath);
        out.writeString(isin);
        out.writeString(depot);
        out.writeString(kmyId);
        out.writeString(securityName);
        out.writeString(action);
        out.writeLong(dateMillis);
        out.writeValue(shares);
        out.writeValue(price);
        out.writeValue(grossCents);
        out.writeValue(feeCents);
        out.writeValue(netCents);
        out.writeString(moneyAccount);
        out.writeString(fixedFeeCategory);
        writeParts(out, feeParts);
        writeParts(out, incomeParts);
        out.writeInt(failure);
        out.writeInt(conflict ? 1 : 0);
        out.writeInt(dupBooked ? 1 : 0);
        out.writeInt(dupSelected ? 1 : 0);
        out.writeInt(scheduleMatchOptOut ? 1 : 0);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<StatementDraft> CREATOR = new Creator<StatementDraft>() {
        @Override
        public StatementDraft createFromParcel(Parcel in) {
            return new StatementDraft(in);
        }

        @Override
        public StatementDraft[] newArray(int size) {
            return new StatementDraft[size];
        }
    };

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Kategoriezeilen im Parcel: Anzahl, dann je Zeile Kategorie, Betrag und Beschriftung. */
    private static void writeParts(Parcel out, List<CategorySplits.Part> parts) {
        out.writeInt(parts.size());
        for (CategorySplits.Part part : parts) {
            out.writeString(part.category);
            out.writeLong(part.cents);
            out.writeString(part.label);
        }
    }

    private static List<CategorySplits.Part> readParts(Parcel in) {
        List<CategorySplits.Part> out = new ArrayList<>();
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            out.add(new CategorySplits.Part(orEmpty(in.readString()), in.readLong(),
                    orEmpty(in.readString())));
        }
        return out;
    }
}
