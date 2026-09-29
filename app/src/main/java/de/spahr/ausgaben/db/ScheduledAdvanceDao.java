package de.spahr.ausgaben.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/** Zugriff auf die noch nicht nach KMyMoney übertragenen Vorrück-Vormerkungen ({@link ScheduledAdvance}). */
@Dao
public interface ScheduledAdvanceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(ScheduledAdvance advance);

    @Update
    void update(ScheduledAdvance advance);

    @Query("SELECT * FROM scheduled_advance")
    List<ScheduledAdvance> getAll();

    @Query("SELECT * FROM scheduled_advance WHERE kmy_id = :kmyId")
    ScheduledAdvance getByKmyId(String kmyId);

    @Query("DELETE FROM scheduled_advance WHERE id IN (:ids)")
    void deleteByIds(List<Long> ids);

    /** Alle Vormerkungen verwerfen – beim Wechsel weg vom .kmy-Schreibziel. */
    @Query("DELETE FROM scheduled_advance")
    void deleteAll();
}
