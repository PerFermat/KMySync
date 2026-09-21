package de.spahr.ausgaben.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Vormerkung, dass eine geplante Buchung ({@code <SCHEDULED_TX>} aus KMyMoney) erledigt oder übersprungen
 * wurde und die Regel deshalb um eine Periode weiterzustellen ist. Je Regel höchstens eine Zeile – mehrfaches
 * Erledigen schreibt {@link #nextDueMs} kumulativ fort.
 *
 * <p>Eigene Tabelle statt eines Feldes in {@link ScheduledTransaction}, weil {@code scheduled_transaction}
 * bei jedem .kmy-Import komplett neu aufgebaut wird (siehe {@code Repository.applyScheduledTransactions}).
 * Beim nächsten „An kMyMoney übertragen" wird die Vormerkung in die Datei geschrieben und hier gelöscht.
 */
@Entity(tableName = "scheduled_advance",
        indices = {@Index(value = "kmy_id", unique = true)})
public class ScheduledAdvance {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** id des {@code <SCHEDULED_TX>}-Elements, z. B. {@code SCH000056}. */
    @NonNull
    @ColumnInfo(name = "kmy_id")
    public String kmyId = "";

    /** Termin, der zuletzt erledigt/übersprungen wurde – erwarteter {@code postdate}-Stand in der Datei. */
    @ColumnInfo(name = "from_due_ms")
    public long fromDueMs;

    /** Neue nächste Fälligkeit; wird beim Export als {@code postdate} geschrieben. */
    @ColumnInfo(name = "next_due_ms")
    public long nextDueMs;

    /** Zuletzt tatsächlich <b>gebuchter</b> Termin ({@code 0} = bisher nur übersprungen). */
    @ColumnInfo(name = "last_payment_ms")
    public long lastPaymentMs;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;

    /**
     * Nur gesetzt, wenn zusammen mit dem Erledigen auch die Stückzahl einer geplanten Wertpapier-
     * Umbuchung korrigiert werden soll (siehe {@link ScheduleMatch}): Depot und KMyMoney-Wertpapier-Id,
     * damit der Export das betroffene Unterkonto der Planung findet.
     */
    @ColumnInfo(name = "security_depot")
    public String securityDepot;

    @ColumnInfo(name = "security_kmy_id")
    public String securityKmyId;

    /** Neue Stückzahl für den Wertpapier-Split der Planung; {@code null} = Stückzahl unverändert lassen. */
    @ColumnInfo(name = "new_shares")
    public Double newShares;

    public ScheduledAdvance() {
    }

    @Ignore
    public ScheduledAdvance(@NonNull String kmyId, long fromDueMs, long nextDueMs, long lastPaymentMs,
                            long updatedAt) {
        this.kmyId = kmyId;
        this.fromDueMs = fromDueMs;
        this.nextDueMs = nextDueMs;
        this.lastPaymentMs = lastPaymentMs;
        this.updatedAt = updatedAt;
    }
}
