package de.spahr.ausgaben.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Ignore;

/**
 * Eine Kategorie samt der Seite, auf der sie geführt wird – so, wie sie an ihrer Quelle stand: an der
 * früheren Buchung, an deren Splitzeile oder im Alias.
 *
 * <p>Der Name allein genügt nicht: KMyMoney erlaubt denselben Kategorienamen im Einnahmen- und im
 * Ausgabenbaum. Wer eine Kategorie weiterreicht – als Vorschlag, als Vorlage –, reicht deshalb auch
 * weiter, welche von beiden gemeint war. Sonst müsste der Empfänger raten, und eine Erstattung auf
 * eine Ausgabekategorie gälte plötzlich als Einnahmekategorie.</p>
 */
public class PayeeCategory {

    @NonNull
    @ColumnInfo(name = "category")
    public String category = "";

    /** {@code true} = Einnahmekategorie, {@code false} = Ausgabekategorie, {@code null} = unbekannt. */
    @ColumnInfo(name = "category_is_income")
    public Boolean isIncome;

    public PayeeCategory() {
    }

    @Ignore
    public PayeeCategory(@NonNull String category, Boolean isIncome) {
        this.category = category;
        this.isIncome = isIncome;
    }
}
