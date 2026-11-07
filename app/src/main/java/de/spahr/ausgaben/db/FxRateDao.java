package de.spahr.ausgaben.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Transaction;

import java.util.List;

@Dao
public abstract class FxRateDao {

    @Query("SELECT * FROM fx_rate")
    public abstract List<FxRate> getAll();

    @Query("DELETE FROM fx_rate")
    abstract void clear();

    @Insert
    abstract void insertAll(List<FxRate> rates);

    /** Ersetzt alle Kurse durch die der zuletzt eingelesenen Datei. */
    @Transaction
    public void replaceAll(List<FxRate> rates) {
        clear();
        insertAll(rates);
    }
}
