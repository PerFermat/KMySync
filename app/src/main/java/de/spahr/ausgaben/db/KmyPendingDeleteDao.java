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

    /**
     * Verwirft die Vormerkungen, die auf eine Buchung mit dieser Signatur zielen – Konto, Betrag, Tag
     * und, soweit die Vormerkung einen kennt, Empfänger; dieselben Merkmale, über die der Export die
     * Transaktion sucht. Gebraucht, wenn die Transaktion in KMyMoney abgeglichen ist und deshalb nie
     * mehr gelöscht wird.
     */
    @Query("DELETE FROM kmy_pending_delete WHERE account = :account COLLATE NOCASE "
            + "AND signed_cents = :signedCents AND created_at >= :dayStart AND created_at < :dayEnd "
            + "AND (payee = '' OR payee = :payee COLLATE NOCASE)")
    void deleteBySignature(String account, long signedCents, long dayStart, long dayEnd, String payee);

    /** Alle Vormerkungen verwerfen – beim Wechsel weg vom .kmy-Schreibziel. */
    @Query("DELETE FROM kmy_pending_delete")
    void deleteAll();
}
