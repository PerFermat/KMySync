package de.spahr.ausgaben.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Ein Kategorie-Teil einer Depotbewegung — das Gegenstück zu {@link BookingSplit} für
 * {@link SecurityTx}.
 *
 * <p>In KMyMoney hängen an einer Wertpapierbuchung beliebig viele Kategoriesplits, und zwar in zwei
 * Rollen: der Ertrag einer Dividende und die abgezogenen Steuern bzw. Gebühren. Beide Rollen führen
 * hier dieselbe Tabelle und unterscheiden sich allein in {@link #income}. Die Summe der Teile einer
 * Rolle ergibt den Betrag, der in der Maske darüber steht — beim Ertrag das Brutto, bei Steuer und
 * Gebühr das Gebührenfeld.</p>
 *
 * <p>Vorher trug jede Bewegung genau eine Kategorie je Rolle, und Kapitalertragsteuer,
 * Solidaritätszuschlag und Kirchensteuer mussten zu einer Zahl addiert werden — die dann unter einer
 * dieser drei Kategorien stand und dort falsch war.</p>
 */
@Entity(tableName = "security_tx_split", indices = {@Index("tx_id")})
public class SecurityTxSplit {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "tx_id")
    public long txId;

    /** {@code true} = Ertragsteil einer Dividende, {@code false} = Steuer- bzw. Gebührenteil. */
    @ColumnInfo(name = "income")
    public boolean income;

    @NonNull
    @ColumnInfo(name = "category")
    public String category = "";

    /**
     * Teilbetrag, <b>vorzeichenbehaftet</b> — genau wie bei {@link BookingSplit}, und die Summe der
     * Teile einer Rolle ergibt den (positiven) Betrag, der in der Maske darüber steht.
     *
     * <p>Eine Zeile darf gegen die Richtung ihrer Rolle laufen: in einem Ertrag steht die
     * Kapitalertragsteuer als Abzug. Zinsertrag 100 €, Kapitalertragsteuer −20 €, Gutschrift 80 € —
     * drei Zahlen, die genau so zusammengehören, und die Erfassungsmaske rechnet seit jeher damit
     * ({@code SplitRowController.isValid} summiert vorzeichenbehaftet).</p>
     *
     * <p>Bis 1.12 wurde hier {@code Math.abs} gespeichert. Die Maske nahm die Eingabe an und erklärte
     * sie für stimmig, in der Datenbank stand danach etwas anderes: aus 100 und −20 (Summe 80) wurden
     * 100 und 20 (Summe 120).</p>
     */
    @ColumnInfo(name = "amount_cents")
    public long amountCents;

    /**
     * Die Beschriftung, unter der dieser Betrag in der Abrechnung stand („Kapitalertragsteuer").
     *
     * <p>Sie ist der Schlüssel, über den eine gelernte Vorlage beim nächsten Mal wiederfindet, welche
     * Kategorie zu welchem Betrag gehört — reihenfolgeunabhängig und auch dann, wenn eine Zeile
     * einmal fehlt. Leer bleibt sie bei fest programmierten Banken, die nichts zu lernen haben und
     * ihre Teile nur der Reihe nach liefern, und bei Bestandsdaten aus der Zeit vor den Teilen.</p>
     */
    @NonNull
    @ColumnInfo(name = "label")
    public String label = "";

    /** Reihenfolge in der Maske. */
    @ColumnInfo(name = "sort")
    public int sort;

    /**
     * Die Seite von {@link #category} ({@code true} = Einnahme-, {@code false} = Ausgabekategorie);
     * {@code null}, solange sie nicht ermittelt ist. Nicht dasselbe wie {@link #income}: Das ist die
     * Rolle der Zeile, und im Ertragsteil einer Dividende steht die Kapitalertragsteuer – eine
     * Ausgabekategorie. Siehe {@link Booking#categoryIsIncome}.
     */
    @ColumnInfo(name = "category_is_income")
    public Boolean categoryIsIncome;

    /**
     * Die Seite der Kategorie: die gespeicherte. Solange sie fehlt, sagt es die Rolle der Zeile – ein
     * Ertrag in Laufrichtung kommt aus einer Einnahmekategorie; Steuer, Gebühr und der Abzug innerhalb
     * eines Ertrags gehen auf eine Ausgabekategorie.
     */
    public boolean categorySide() {
        return categoryIsIncome != null ? categoryIsIncome : income && amountCents >= 0;
    }

    public SecurityTxSplit() {
    }

    @Ignore
    public SecurityTxSplit(long txId, boolean income, String category, long amountCents,
                           String label, int sort, Boolean categoryIsIncome) {
        this(txId, income, category, amountCents, label, sort);
        this.categoryIsIncome = categoryIsIncome;
    }

    @Ignore
    public SecurityTxSplit(long txId, boolean income, String category, long amountCents,
                           String label, int sort) {
        this.txId = txId;
        this.income = income;
        this.category = category == null ? "" : category;
        this.amountCents = amountCents;
        this.label = label == null ? "" : label;
        this.sort = sort;
    }
}
