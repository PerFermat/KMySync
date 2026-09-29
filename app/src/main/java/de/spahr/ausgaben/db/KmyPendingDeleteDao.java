package de.spahr.ausgaben.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface KmyPendingDeleteDao {

    @Insert
    void insert(KmyPendingDelete d);

    @Query("SELECT * FROM kmy_pending_delete")
    List<KmyPendingDelete> getAll();

    @Query("DELETE FROM kmy_pending_delete WHERE id IN (:ids)")
    void deleteByIds(List<Long> ids);

    /** Alle Vormerkungen verwerfen – beim Wechsel weg vom .kmy-Schreibziel. */
    @Query("DELETE FROM kmy_pending_delete")
    void deleteAll();
}
