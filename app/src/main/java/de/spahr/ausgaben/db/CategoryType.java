package de.spahr.ausgaben.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;

/**
 * Intrinsischer Typ einer Kategorie aus der KMyMoney-Datei: {@code true} = Einnahme (KMyMoney-Typ 12),
 * {@code false} = Ausgabe (Typ 13). Einzige verlässliche Typ-Quelle für die Budget-Einordnung – der
 * Betrag einer Buchung darf negativ sein, die Kategorie bleibt in ihrem hier gespeicherten Typ.
 * Wird bei jedem .kmy-Import aktualisiert ({@link Repository#applyCategoryTypes}).
 *
 * <p>Schlüssel ist der Pfad <b>zusammen mit der Seite</b>: KMyMoney erlaubt denselben Pfad im
 * Einnahme- und im Ausgabebaum, und dann stehen hier beide.</p>
 */
@Entity(tableName = "category_type", primaryKeys = {"category", "is_income"})
public class CategoryType {

    @NonNull
    @ColumnInfo(name = "category")
    public String category = "";

    @ColumnInfo(name = "is_income")
    public boolean isIncome;

    public CategoryType() {
    }

    @Ignore
    public CategoryType(@NonNull String category, boolean isIncome) {
        this.category = category;
        this.isIncome = isIncome;
    }
}
