package de.spahr.ausgaben.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/** Zugriff auf die Kategorie-Typen ({@link CategoryType}) aus der KMyMoney-Datei. */
@Dao
public interface CategoryTypeDao {

    /** Trägt eine Kategorie mit ihrer Seite ein (Pfad und Seite = Primärschlüssel). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(CategoryType type);

    @Query("SELECT * FROM category_type")
    List<CategoryType> getAll();

    /**
     * Die Seite einer Kategorie nach ihrem Namen – aber nur, wenn sie daraus eindeutig hervorgeht:
     * {@code null} heißt „unbekannt" <b>oder</b> „es gibt den Namen im Einnahme- und im Ausgabebaum".
     * Der Rückfall für Stellen, die eine Kategorie ohne ihre Seite bekommen; wer die Seite kennt,
     * fragt hier nicht.
     */
    @Query("SELECT CASE WHEN COUNT(DISTINCT is_income) = 1 THEN MAX(is_income) END "
            + "FROM category_type WHERE category = :category COLLATE NOCASE")
    Boolean isIncome(String category);
}
