package de.spahr.ausgaben.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

import java.util.List;

/**
 * Eine einzelne Bargeld-Buchung.
 * Der Betrag wird immer positiv in Cent gespeichert; das Vorzeichen ergibt sich
 * aus {@link #isIncome} (Einnahme = positiv, Ausgabe = negativ im Export).
 */
@Entity(tableName = "booking")
public class Booking {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "amount_cents")
    public long amountCents;

    @ColumnInfo(name = "is_income")
    public boolean isIncome;

    @NonNull
    @ColumnInfo(name = "payee")
    public String payee = "";

    @NonNull
    @ColumnInfo(name = "account")
    public String account = "";

    @NonNull
    @ColumnInfo(name = "category")
    public String category = "";

    /**
     * Typ von {@link #category} (true = Einnahme, false = Ausgabe), {@code null} = unbekannt (Zeile vor
     * dieser Migration). kMyMoney erlaubt dieselbe Kategorie-Bezeichnung unabhängig im Einnahme- und im
     * Ausgabe-Baum (z. B. „Versicherung:Krankenzusatz"); dieses Feld hält den Typ je Zeile fest, statt ihn
     * nur global pro Text zu speichern (siehe {@link CategoryType}, das bei einer Namenskollision einen der
     * beiden Typen überschreiben würde). Import: aus der kMyMoney-Konto-ID (eindeutig). App-Editor: aus der
     * angetippten Gruppe der Kategorie-Auswahlliste, sonst Rückfall auf {@link #isIncome}.
     */
    @ColumnInfo(name = "category_is_income")
    public Boolean categoryIsIncome;

    @NonNull
    @ColumnInfo(name = "note")
    public String note = "";

    /**
     * Die Stichwörter dieser Buchung (die „Tags" aus KMyMoney), durch {@code |} getrennt – siehe
     * {@link BookingTags}. In der Datei hängen sie am einzelnen Split; die App führt sie je Buchung
     * und schreibt sie beim Export an jeden Split derselben Transaktion.
     */
    @NonNull
    @ColumnInfo(name = "tags", defaultValue = "")
    public String tags = "";

    /**
     * Loser Link auf den Bargeld-Ort, dem diese Buchung ihre Ort-Bewegung gutgeschrieben hat
     * (leer = „ohne Ort"). Nur relevant, solange {@link #placeManaged} gesetzt ist; dient dazu,
     * spätere Betrags-/Ort-/Lösch-Änderungen als Ausgleichs-Bewegung im Ort-Journal nachzuziehen.
     * Bestimmt NICHT den Ortssaldo (der kommt aus dem place_entry-Journal).
     */
    @NonNull
    @ColumnInfo(name = "place")
    public String place = "";

    /**
     * True, wenn diese Buchung in dieser App angelegt und mit einem Ort verknüpft wurde (dann wird ihr
     * Ort-Feld im Editor gezeigt und Folgeänderungen erzeugen Ort-Ausgleichsbewegungen). Importierte
     * Buchungen sind {@code false} – für sie gibt es keine Ort-Verknüpfung.
     */
    @ColumnInfo(name = "place_managed")
    public boolean placeManaged;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "exported")
    public boolean exported;

    /**
     * Nach dem Export geändert: die Buchung steht in der .kmy-Datei noch in ihrer alten Fassung. Beim
     * nächsten Übertragen wird die dortige Transaktion geändert (statt eine neue anzulegen) und die
     * Buchung wieder auf {@link #exported} gestellt. Ist dieses Feld gesetzt, ist {@code exported}
     * immer {@code false}. Nur der kmy-Modus kennt diesen Zustand; von Hand nicht setzbar (siehe
     * {@link EditStatus}).
     */
    @ColumnInfo(name = "edited")
    public boolean edited;

    /**
     * Konto der exportierten Fassung – nur gefüllt, solange {@link #edited} gilt. Zusammen mit
     * {@link #origSignedCents} und {@link #origCreatedAt} die Signatur, an der die Transaktion in der
     * .kmy-Datei wiedergefunden wird, auch wenn die Bearbeitung Konto, Betrag oder Datum verändert hat.
     */
    @NonNull
    @ColumnInfo(name = "orig_account")
    public String origAccount = "";

    /** Vorzeichenbehafteter Betrag in Cent der exportierten Fassung ({@code +} = Einnahme). */
    @ColumnInfo(name = "orig_signed_cents")
    public long origSignedCents;

    /** {@link #createdAt} der exportierten Fassung. */
    @ColumnInfo(name = "orig_created_at")
    public long origCreatedAt;

    /**
     * Empfänger der exportierten Fassung – nur gefüllt, solange {@link #edited} gilt. Vervollständigt
     * die Signatur aus {@link #origAccount}/{@link #origSignedCents}/{@link #origCreatedAt}: verhindert,
     * dass zwei zufällig gleich signierte Transaktionen (gleiches Konto, Datum, Betrag) verwechselt
     * werden, solange wenigstens eine von beiden einen Empfänger trägt.
     */
    @NonNull
    @ColumnInfo(name = "orig_payee")
    public String origPayee = "";

    /**
     * Unbenutzt (Spalte bleibt aus Migrationsgründen bestehen). Die Lösch-Synchronisierung mit der
     * .kmy-Datei ({@code KmyPendingDelete}) identifiziert Transaktionen stattdessen über Konto, Datum
     * und Betrag, da importierte Buchungen keine KMyMoney-Transaktions-id kennen.
     */
    @NonNull
    @ColumnInfo(name = "kmy_tx_id")
    public String kmyTxId = "";

    /** Umbuchung (Kontotransfer): dann ist {@link #transferAccount} das Gegenkonto und es gibt keine Kategorie. */
    @ColumnInfo(name = "is_transfer")
    public boolean isTransfer;

    /** Gegenkonto einer Umbuchung (Anzeigename). */
    @NonNull
    @ColumnInfo(name = "transfer_account")
    public String transferAccount = "";

    /** Verknüpft die beiden Seiten einer in der App erstellten Umbuchung (leer bei importierten Einseitern). */
    @NonNull
    @ColumnInfo(name = "transfer_group")
    public String transferGroup = "";

    /** Transient: Kategorie-Teile einer Splitbuchung (nicht in dieser Tabelle gespeichert, siehe {@link BookingSplit}). */
    @Ignore
    public List<BookingSplit> parts;

    /**
     * Transient: in einer Umbuchung versteckte Ausgaben/Einnahmen (z. B. Wertpapier-Gebühren) – landen in
     * {@link AnalysisExtra}, nicht in dieser Tabelle, und nur für die Kategorien-Auswertungen.
     */
    @Ignore
    public List<AnalysisExtra> analysisExtras;

    public Booking() {
    }
}
