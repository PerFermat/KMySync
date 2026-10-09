package de.spahr.ausgaben.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/** Zugriff auf die Kategorie-Typen ({@link CategoryType}) aus der KMyMoney-Datei. */
@Dao
public interface CategoryTypeDao {

    /** Setzt/aktualisiert den Typ einer Kategorie (Pfad = Primärschlüssel). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(CategoryType type);

    @Query("SELECT * FROM category_type")
    List<CategoryType> getAll();

    /**
     * Typ einer Kategorie nach ihrem Namen ({@code null} = unbekannt). Nur der Rückfall für Stellen,
     * die eine Kategorie ohne ihre Seite gespeichert haben (alte Aliase, alte Kassensturz-Vorgabe):
     * je Name gibt es hier nur einen Eintrag, bei gleichem Namen in beiden Bäumen also nur einen der
     * beiden.
     */
    @Query("SELECT is_income FROM category_type WHERE category = :category COLLATE NOCASE LIMIT 1")
    Boolean isIncome(String category);
}
