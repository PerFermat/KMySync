package de.spahr.ausgaben.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

/**
 * Eine lokal gelöschte, bereits in der .kmy-Datei vorhandene Buchung (egal ob von dieser App exportiert
 * oder von dort importiert) – wird beim nächsten „An kMyMoney übertragen" (kmy-Modus) gesucht und aus der
 * Datei entfernt, danach hier wieder gelöscht. KMyMoney-Transaktionen haben aus App-Sicht keine bekannte
 * id, deshalb wird die Transaktion über Konto + Datum + vorzeichenbehafteten Betrag des Kontosplits
 * wiedergefunden, ergänzt um den Empfänger, wenn einer bekannt ist (siehe {@code KmyExporter.removeTransactions}).
 */
@Entity(tableName = "kmy_pending_delete")
public class KmyPendingDelete {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** Anzeigename des Kontos (wie in {@code Booking#account}). */
    @NonNull
    @ColumnInfo(name = "account")
    public String account = "";

    /** Vorzeichenbehafteter Betrag in Cent ({@code +} = Zufluss), wie im Kontosplit der Transaktion. */
    @ColumnInfo(name = "signed_cents")
    public long signedCents;

    /** {@code Booking#createdAt} der gelöschten Buchung (für den Datumsabgleich). */
    @ColumnInfo(name = "created_at")
    public long createdAt;

    /**
     * Empfänger der gelöschten Buchung, leer wenn unbekannt – verschärft den Abgleich zusätzlich zu
     * Konto/Datum/Betrag, wenn zwei Transaktionen sonst zufällig dieselbe Signatur hätten.
     */
    @NonNull
    @ColumnInfo(name = "payee")
    public String payee = "";

    @ColumnInfo(name = "queued_at")
    public long queuedAt;

    public KmyPendingDelete() {
    }

    @Ignore
    public KmyPendingDelete(@NonNull String account, long signedCents, long createdAt,
                            @NonNull String payee, long queuedAt) {
        this.account = account;
        this.signedCents = signedCents;
        this.createdAt = createdAt;
        this.payee = payee;
        this.queuedAt = queuedAt;
    }
}
