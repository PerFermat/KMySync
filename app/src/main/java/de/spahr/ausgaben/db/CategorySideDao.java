package de.spahr.ausgaben.db;

import androidx.room.Dao;
import androidx.room.Query;
import androidx.room.Transaction;

/**
 * Trägt die Seite einer Kategorie (Einnahme/Ausgabe) dort nach, wo sie fehlt – in Zeilen aus der Zeit,
 * bevor die jeweilige Tabelle sie führte.
 *
 * <p>Angefasst wird nur, was {@code NULL} ist und eine Kategorie trägt; was einmal gespeichert ist,
 * bleibt. Die Regel, je Zeile:</p>
 * <ol>
 *   <li>Steht der Name in {@code category_type} auf genau einer Seite, ist es diese.</li>
 *   <li>Sonst – es gibt ihn in beiden Bäumen, oder die Tabelle kennt ihn nicht – entscheidet die
 *       Richtung dessen, was die Kategorie trägt: Einnahme oder Ausgabe.</li>
 * </ol>
 */
@Dao
public interface CategorySideDao {

    /** Die Seite laut Typtabelle, wenn sie eindeutig ist; sonst NULL. Für die Spalte {@code %s}. */
    String EINDEUTIG = "(SELECT CASE WHEN COUNT(DISTINCT ct.is_income) = 1 THEN MAX(ct.is_income) END "
            + "FROM category_type ct WHERE ct.category = ";

    // ---- Buchungen ----

    @Query("UPDATE booking SET category_is_income = COALESCE(" + EINDEUTIG
            + "booking.category COLLATE NOCASE), is_income) "
            + "WHERE category_is_income IS NULL AND category != '' AND is_transfer = 0")
    void buchungen();

    /** Ein Teil läuft mit seiner Buchung; steht sein Betrag gegen sie, liegt er auf der anderen Seite. */
    @Query("UPDATE booking_split SET category_is_income = COALESCE(" + EINDEUTIG
            + "booking_split.category COLLATE NOCASE), "
            + "(SELECT CASE WHEN booking_split.amount_cents < 0 THEN 1 - b.is_income ELSE b.is_income END "
            + " FROM booking b WHERE b.id = booking_split.booking_id)) "
            + "WHERE category_is_income IS NULL AND category != ''")
    void buchungsTeile();

    // ---- Alias: die Seite ist die des Feldes ----

    @Query("UPDATE payee_correction SET cat_expense_1_is_income = COALESCE(" + EINDEUTIG
            + "payee_correction.cat_expense_1 COLLATE NOCASE), 0) "
            + "WHERE cat_expense_1_is_income IS NULL AND cat_expense_1 != ''")
    void aliasAusgabe1();

    @Query("UPDATE payee_correction SET cat_expense_2_is_income = COALESCE(" + EINDEUTIG
            + "payee_correction.cat_expense_2 COLLATE NOCASE), 0) "
            + "WHERE cat_expense_2_is_income IS NULL AND cat_expense_2 != ''")
    void aliasAusgabe2();

    @Query("UPDATE payee_correction SET cat_income_1_is_income = COALESCE(" + EINDEUTIG
            + "payee_correction.cat_income_1 COLLATE NOCASE), 1) "
            + "WHERE cat_income_1_is_income IS NULL AND cat_income_1 != ''")
    void aliasEinnahme1();

    @Query("UPDATE payee_correction SET cat_income_2_is_income = COALESCE(" + EINDEUTIG
            + "payee_correction.cat_income_2 COLLATE NOCASE), 1) "
            + "WHERE cat_income_2_is_income IS NULL AND cat_income_2 != ''")
    void aliasEinnahme2();

    // ---- Depot ----

    /** Ein Ertrag in Laufrichtung ist eine Einnahme; Steuer, Gebühr und Abzug sind Ausgaben. */
    @Query("UPDATE security_tx_split SET category_is_income = COALESCE(" + EINDEUTIG
            + "security_tx_split.category COLLATE NOCASE), "
            + "CASE WHEN income = 1 AND amount_cents >= 0 THEN 1 ELSE 0 END) "
            + "WHERE category_is_income IS NULL AND category != ''")
    void depotZeilen();

    // ---- Planungen (kind: 0 = Ausgabe, 1 = Einnahme, 2 = Umbuchung) ----

    @Query("UPDATE scheduled_transaction SET counterparty_is_income = COALESCE(" + EINDEUTIG
            + "scheduled_transaction.counterparty COLLATE NOCASE), kind) "
            + "WHERE counterparty_is_income IS NULL AND counterparty != '' AND kind != 2 AND split = 0")
    void planungen();

    @Query("UPDATE scheduled_split SET category_is_income = COALESCE(" + EINDEUTIG
            + "scheduled_split.category COLLATE NOCASE), "
            + "(SELECT CASE WHEN scheduled_split.amount_cents < 0 THEN 1 - s.kind ELSE s.kind END "
            + " FROM scheduled_transaction s WHERE s.id = scheduled_split.scheduled_id AND s.kind != 2)) "
            + "WHERE category_is_income IS NULL AND category != ''")
    void planungsTeile();

    /** Alles auf einmal. Wiederholbar: ein zweiter Lauf findet nichts mehr vor. */
    @Transaction
    default void fillMissing() {
        buchungen();
        buchungsTeile();
        aliasAusgabe1();
        aliasAusgabe2();
        aliasEinnahme1();
        aliasEinnahme2();
        depotZeilen();
        planungen();
        planungsTeile();
    }
}
