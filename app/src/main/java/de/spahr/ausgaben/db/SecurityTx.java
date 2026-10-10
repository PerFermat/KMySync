package de.spahr.ausgaben.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Eine Depot-Bewegung eines Wertpapiers (Kauf/Verkauf/Dividende/Einbuchung), importiert aus KMyMoney.
 * Reine Anzeige-/Auswertungsdaten; wirkt nicht auf die Konto-Salden (der Geldfluss läuft über das jeweilige
 * Geldkonto und wird dort separat importiert).
 */
@Entity(tableName = "security_tx", indices = {@Index(value = "depot")})
public class SecurityTx {

    /**
     * Die Arten einer Bewegung. Standen bis 1.12 als Zeichenketten in vier Klassen nebeneinander
     * ({@code StatementScan}, {@code StatementDraft}, {@code SecurityTxEditActivity},
     * {@code StatementRulesActivity}) — und die Regeln, die daran hängen, gleich mit.
     */
    public static final String BUY = "buy";
    public static final String SELL = "sell";
    public static final String DIVIDEND = "dividend";

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    @ColumnInfo(name = "depot")
    public String depot = "";

    /** KMyMoney-Wertpapier-ID (verknüpft mit {@link Security#kmyId}). */
    @NonNull
    @ColumnInfo(name = "security_kmy_id")
    public String securityKmyId = "";

    @NonNull
    @ColumnInfo(name = "security_name")
    public String securityName = "";

    @ColumnInfo(name = "date")
    public long date;

    /** buy | sell | dividend | add | remove | reinvest */
    @NonNull
    @ColumnInfo(name = "action")
    public String action = "";

    /** Vorzeichenbehaftete Stückzahl (Kauf/Add +, Verkauf/Remove −, Dividende 0). */
    @ColumnInfo(name = "shares")
    public double shares;

    /** Geldbetrag in Cent (Kauf = Kosten, Verkauf = Erlös, Dividende = <b>Brutto</b>, Add/Remove = 0). */
    @ColumnInfo(name = "amount_cents")
    public long amountCents;

    /**
     * Das tatsächlich bewegte Geld in Cent — der Betrag, der auf dem Geldkonto steht.
     *
     * <p>Kauf: Brutto <b>plus</b> Gebühr. Verkauf: Brutto <b>minus</b> Gebühr. Dividende: die
     * Gutschrift, also Brutto minus Steuer. Ein-/Ausbuchungen bewegen kein Geld und tragen 0.</p>
     *
     * <p>Bis 1.12 stand hier bei Kauf und Verkauf der <b>Bruttobetrag</b>, also {@link #amountCents}
     * noch einmal. Bei jeder Bewegung mit Gebühr wich das von der Geldbuchung ab —
     * {@link SecurityTxMatch} vergleicht genau dieses Feld gegen den Buchungsbetrag und fand die
     * Bewegung dann nicht mehr. Eine gelöschte Buchung ließ ihre Bewegung stehen.</p>
     */
    @ColumnInfo(name = "net_cents")
    public long netCents;

    /**
     * Gebühren in Cent (immer ≥ 0): bei Kauf/Verkauf/Wiederanlage die Kosten, die in KMyMoney als eigener
     * Ausgabe-Kategorie-Split neben dem Wertpapier-Split hängen. {@link #amountCents} enthält sie nicht.
     * Bei Dividenden 0 – dort stecken die Abzüge in der Differenz {@link #amountCents} − {@link #netCents}.
     */
    @ColumnInfo(name = "fee_cents")
    public long feeCents;

    /**
     * In der App erfasst und noch nicht in die KMyMoney-Datei geschrieben. Solche Bewegungen überleben
     * einen Depot-Reimport (siehe {@code SecurityDao.deleteTx}); nach dem Export fällt das Kennzeichen
     * weg und die Bewegung wird beim nächsten Import wie jede andere aus der Datei ersetzt.
     */
    @ColumnInfo(name = "pending")
    public boolean pending;

    /**
     * Geldkonto der Gegenbuchung (Belastung beim Kauf, Gutschrift bei Verkauf/Dividende). Wird beim
     * Import aus dem Geld-Split mitgelesen und dient in der Erfassungsmaske als Vorbelegung.
     */
    @NonNull
    @ColumnInfo(name = "money_account")
    public String moneyAccount = "";

    /**
     * Stillgelegt: die Kategorie der Gebühren bzw. der Steuer stand hier, solange es je Bewegung nur
     * eine geben konnte. Seit v48 führen das die {@link SecurityTxSplit}-Zeilen; die Migration hat
     * den Bestand dorthin umgezogen und diese Spalte geleert. Sie bleibt allein deshalb stehen, weil
     * SQLite sie nur mit einem vollständigen Neuaufbau der Tabelle hergäbe — beim nächsten Neuaufbau
     * fällt sie weg.
     */
    @NonNull
    @ColumnInfo(name = "fee_category")
    public String feeCategory = "";

    /** Stillgelegt wie {@link #feeCategory} – die Ertragskategorie einer Dividende. */
    @NonNull
    @ColumnInfo(name = "income_category")
    public String incomeCategory = "";

    /** Zugehörige Geldbuchung ({@code Booking.id}); 0 = keine (alle importierten Bewegungen). */
    @ColumnInfo(name = "booking_id")
    public long bookingId;

    /**
     * Notiz der Bewegung – dieselbe wie an der Geldbuchung, und damit der Ort für den Beleg-Tag einer
     * eingelesenen Abrechnung ({@code BELEG (PDF): …}, siehe {@code NoteReceipt}).
     *
     * <p>Warum doppelt zur Buchung: Bis 1.13 hing der Tag allein an der Gegenbuchung, und der Weg
     * dorthin führte über {@link #bookingId}. Beide gingen beim Rundlauf durch die KMyMoney-Datei
     * verloren — das Memo wurde gar nicht erst geschrieben, und {@code importDepot} stellt
     * {@code bookingId} nicht wieder her. Nach einem Depot-Reimport stand die Bewegung ohne ihre
     * Abrechnung da. Mit eigener Notiz trägt sie den Verweis selbst.</p>
     */
    @NonNull
    @ColumnInfo(name = "note")
    public String note = "";

    /**
     * Die Kategoriezeilen dieser Bewegung – Ertrag und Steuer/Gebühr, siehe {@link SecurityTxSplit}.
     * Wie {@code Booking.parts} nicht in dieser Tabelle gespeichert, sondern in ihrer eigenen; das
     * Repository füllt sie beim Laden und schreibt sie beim Speichern mit.
     */
    @Ignore
    public java.util.List<SecurityTxSplit> parts = new java.util.ArrayList<>();

    /** Die Kategoriezeilen einer der beiden Rollen, in ihrer Reihenfolge. */
    public java.util.List<SecurityTxSplit> partsOf(boolean income) {
        java.util.List<SecurityTxSplit> out = new java.util.ArrayList<>();
        for (SecurityTxSplit p : parts) {
            if (p.income == income) {
                out.add(p);
            }
        }
        return out;
    }

    public SecurityTx() {
    }

    @Ignore
    public SecurityTx(@NonNull String depot, @NonNull String securityKmyId, @NonNull String securityName,
                      long date, @NonNull String action, double shares, long amountCents) {
        this(depot, securityKmyId, securityName, date, action, shares, amountCents, amountCents);
    }

    @Ignore
    public SecurityTx(@NonNull String depot, @NonNull String securityKmyId, @NonNull String securityName,
                      long date, @NonNull String action, double shares, long amountCents, long netCents) {
        this(depot, securityKmyId, securityName, date, action, shares, amountCents, netCents, 0L);
    }

    @Ignore
    public SecurityTx(@NonNull String depot, @NonNull String securityKmyId, @NonNull String securityName,
                      long date, @NonNull String action, double shares, long amountCents, long netCents,
                      long feeCents) {
        this.depot = depot;
        this.securityKmyId = securityKmyId;
        this.securityName = securityName;
        this.date = date;
        this.action = action;
        this.shares = shares;
        this.amountCents = amountCents;
        this.netCents = netCents;
        this.feeCents = feeCents;
    }

    /**
     * Dieselbe Bewegung wie eine andere — für den Hinweis auf eine doppelt eingelesene Abrechnung.
     *
     * <p>Verglichen werden Depot, Wertpapier, Art, Tag und alle Beträge; nicht verglichen werden
     * {@link #id}, {@link #pending}, Konto und Kategorien: ob eine Buchung schon exportiert wurde oder
     * über welche Kategorie sie läuft, macht sie nicht zu einer anderen Bewegung.</p>
     *
     * <p>Beim Datum zählt der <b>Kalendertag</b>, nicht der Zeitstempel. Eine aus KMyMoney importierte
     * Zeile und eine aus einer Abrechnung gesetzte kommen aus verschiedenen Quellen; auf die Uhrzeit ist
     * dabei kein Verlass, auf den Tag schon.</p>
     */
    public boolean sameMovement(SecurityTx other) {
        return other != null
                && depot.equals(other.depot)
                && securityKmyId.equals(other.securityKmyId)
                && action.equals(other.action)
                && amountCents == other.amountCents
                && netCents == other.netCents
                && feeCents == other.feeCents
                && Math.abs(shares - other.shares)
                        < de.spahr.ausgaben.util.SecurityAmounts.SHARE_EPSILON
                && sameDay(date, other.date);
    }

    /**
     * Setzt Art, Stückzahl und Beträge nach den Regeln der Bewegungsart.
     *
     * <p>Diese vier Zeilen standen dreimal fast gleichlautend im Code: beim Speichern der
     * Erfassungsmaske, in ihrer Dublettenprüfung und im Entwurf der Erkennungsliste. Die Kommentare
     * verwiesen wechselseitig aufeinander („dieselben Regeln wie in …") — das Zeichen dafür, dass die
     * Regel einen eigenen Ort braucht. Läuft eine der Fassungen auseinander, bucht die Maske etwas
     * anderes, als ihre eigene Dublettenprüfung sucht.</p>
     *
     * <p>Die Regeln selbst: Ein <b>Verkauf</b> bewegt die Stücke aus dem Depot heraus, trägt also ein
     * Minus. Eine <b>Dividende</b> bewegt gar keine Stücke — der Bestand am Ex-Tag steht nicht in der
     * Abrechnung — und führt keine Gebühr; ihre Abzüge stecken in der Differenz zwischen Brutto und
     * Netto. Bei allen anderen Arten sind Brutto und Netto derselbe Betrag; was zusätzlich abgeht,
     * steht in der Gebühr.</p>
     *
     * @param shares     Stückzahl ohne Vorzeichen; {@code null} zählt wie 0
     * @param grossCents Bruttobetrag
     * @param netCents   bei einer Dividende der gutgeschriebene Betrag, sonst ohne Bedeutung
     * @param feeCents   Gebühr ohne Vorzeichen; bei einer Dividende ohne Bedeutung
     */
    public void applyAmounts(@NonNull String action, Double shares, long grossCents, long netCents,
                             long feeCents) {
        boolean dividend = DIVIDEND.equals(action);
        double count = shares == null ? 0 : Math.abs(shares);
        this.action = action;
        this.shares = dividend ? 0 : (SELL.equals(action) ? -count : count);
        this.amountCents = grossCents;
        this.feeCents = dividend ? 0 : Math.abs(feeCents);
        this.netCents = dividend ? netCents : moneyOf(action, grossCents, this.feeCents);
    }

    /**
     * Das Geld, das eine Bewegung bewegt: beim Verkauf geht die Gebühr von der Gutschrift ab, beim Kauf
     * kommt sie zum Kaufpreis hinzu.
     *
     * <p>Für {@code add}/{@code remove} ist beides 0 — sie bewegen keine Stücke gegen Geld —, die
     * Rechnung liefert dort also von selbst 0.</p>
     */
    public static long moneyOf(String action, long grossCents, long feeCents) {
        long fee = Math.abs(feeCents);
        return SELL.equals(action) ? grossCents - fee : grossCents + fee;
    }

    /**
     * Die Geldbuchung zu dieser Bewegung – in derselben Form, die der .kmy-Import aus der geschriebenen
     * Transaktion später wieder macht (siehe {@code KmyImporter.toBooking}).
     *
     * <p>Kauf und Verkauf sind eine <b>Umbuchung</b> zwischen Geldkonto und Wertpapier: beim Kauf verlässt
     * das Geld das Konto, beim Verkauf kommt es an. Eine <b>Dividende</b> dagegen bewegt keine Stücke;
     * in KMyMoney kommt ihr Geld von der Ertragskategorie, und der Import liest sie als
     * <b>Einnahme</b> – mit mehreren Kategorien (Ertrag, Steuer) als Splitbuchung. Bis 2.2 stand sie
     * hier als Umbuchung und sah deshalb bis zum nächsten Einlesen anders aus als danach.</p>
     *
     * @param moneyCents der tatsächlich bewegte Betrag: beim Kauf der Gesamtbetrag samt Gebühr, bei
     *                   einer Dividende der gutgeschriebene Nettobetrag. <b>Nicht</b>
     *                   {@link #netCents} — das trägt bei Kauf und Verkauf den Bruttobetrag.
     */
    public Booking toMoneyBooking(long moneyCents) {
        if (DIVIDEND.equals(action)) {
            return dividendBooking(moneyCents);
        }
        Booking b = new Booking();
        b.account = moneyAccount;
        b.isTransfer = true;
        b.transferAccount = securityName;
        b.isIncome = !BUY.equals(action);
        b.amountCents = Math.abs(moneyCents);
        b.payee = securityName;
        b.createdAt = date;
        b.category = "";
        return b;
    }

    /**
     * Die Dividende als Einnahme, so wie der Import sie aus der Datei liest: Ertragsteile mit ihrem
     * Betrag, Steuer-/Gebührenteile als Abzug (negativ) – die Summe ist die Gutschrift. Kopf-Kategorie
     * ist der betragsmäßig größte Teil, wie beim Import. Eine einzige Kategorie ergibt keine Teile.
     */
    private Booking dividendBooking(long moneyCents) {
        Booking b = new Booking();
        b.account = moneyAccount;
        b.isTransfer = false;
        b.isIncome = moneyCents >= 0;
        b.amountCents = Math.abs(moneyCents);
        b.payee = securityName;
        b.createdAt = date;
        java.util.List<BookingSplit> teile = new java.util.ArrayList<>();
        for (SecurityTxSplit p : parts) {
            if (p.category == null || p.category.trim().isEmpty() || p.amountCents == 0) {
                continue;
            }
            // Ertrag kommt aufs Konto, Steuer/Gebühr geht davon ab.
            long teil = p.income ? p.amountCents : -p.amountCents;
            // Die Seite der Kategorie, nicht die Rolle der Zeile: Kapitalertragsteuer im Ertragsteil
            // ist eine Ausgabekategorie.
            teile.add(new BookingSplit(0, p.category.trim(), b.isIncome ? teil : -teil,
                    p.categorySide()));
        }
        BookingSplit haupt = null;
        for (BookingSplit t : teile) {
            if (haupt == null || Math.abs(t.amountCents) > Math.abs(haupt.amountCents)) {
                haupt = t;
            }
        }
        b.category = haupt == null ? "" : haupt.category;
        b.categoryIsIncome = haupt == null ? null : haupt.categoryIsIncome;
        b.parts = teile.size() > 1 ? teile : null;
        return b;
    }

    /** Zwei Zeitstempel am selben Kalendertag (lokale Zeitzone). */
    public static boolean sameDay(long a, long b) {
        java.util.Calendar ca = java.util.Calendar.getInstance();
        java.util.Calendar cb = java.util.Calendar.getInstance();
        ca.setTimeInMillis(a);
        cb.setTimeInMillis(b);
        return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR)
                && ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR);
    }
}
