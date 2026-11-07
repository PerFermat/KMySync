package de.spahr.ausgaben.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

/**
 * Ein Devisenkurs aus KMyMoney: {@link #rate} Einheiten {@link #toCur} je Einheit {@link #fromCur} am
 * Tag {@link #date}. Damit rechnen Gesamtsumme und Vermögensverlauf Fremdwährungskonten in die
 * Basiswährung um; das Konto selbst bleibt in seiner Währung. Bei jedem Kontenimport neu befüllt.
 */
@Entity(tableName = "fx_rate")
public class FxRate {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    @ColumnInfo(name = "from_cur")
    public String fromCur = "";

    @NonNull
    @ColumnInfo(name = "to_cur")
    public String toCur = "";

    /** Kursdatum (ms). */
    @ColumnInfo(name = "date")
    public long date;

    @ColumnInfo(name = "rate")
    public double rate;

    public FxRate() {
    }

    @Ignore
    public FxRate(@NonNull String fromCur, @NonNull String toCur, long date, double rate) {
        this.fromCur = fromCur;
        this.toCur = toCur;
        this.date = date;
        this.rate = rate;
    }
}
