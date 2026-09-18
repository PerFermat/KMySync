package de.spahr.ausgaben.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface BookingDao {

    @Insert
    long insert(Booking booking);

    @Update
    void update(Booking booking);

    @Query("DELETE FROM booking WHERE id = :id")
    void delete(long id);

    @Query("SELECT * FROM booking WHERE id = :id")
    Booking getById(long id);

    /**
     * Buchungen zu einer Liste von Nummern – für den wiederaufgenommenen Beleg-Export, der sich nur
     * die Nummern gemerkt hat und die Belegliste daraus neu bildet. Eine inzwischen gelöschte Buchung
     * fehlt dann einfach.
     */
    @Query("SELECT * FROM booking WHERE id IN (:ids) ORDER BY created_at DESC, id DESC")
    List<Booking> getByIds(List<Long> ids);

    /** Zuletzt angelegte Buchung, deren Empfänger den Suchbegriff enthält (für die Sprach-Schnellerfassung). */
    @Query("SELECT * FROM booking WHERE payee LIKE '%' || :term || '%' COLLATE NOCASE "
            + "ORDER BY created_at DESC, id DESC LIMIT 1")
    Booking findLatestByPayeeLike(String term);

    /** Alle Buchungen, deren Empfänger den Suchbegriff enthält (neueste zuerst) – für die Nächster-Auswahl. */
    @Query("SELECT * FROM booking WHERE payee LIKE '%' || :term || '%' COLLATE NOCASE "
            + "ORDER BY created_at DESC, id DESC")
    List<Booking> findByPayeeLike(String term);

    /** Vorhandene Empfängernamen (je einmal), neueste Buchung zuerst – für die unscharfe Sprachsuche. */
    @Query("SELECT payee FROM booking WHERE payee != '' GROUP BY payee ORDER BY MAX(created_at) DESC")
    List<String> getDistinctPayees();

    @Query("SELECT * FROM booking ORDER BY created_at DESC, id DESC")
    List<Booking> getAllBookings();

    /** Buchungen im Zeitraum [fromMs, toMs), ohne Umbuchungen – Grundlage für den Kategorie-Drilldown. */
    @Query("SELECT * FROM booking WHERE created_at >= :fromMs AND created_at < :toMs AND is_transfer = 0 "
            + "ORDER BY created_at DESC, id DESC")
    List<Booking> getBookingsBetween(long fromMs, long toMs);

    /** Die letzten Buchungen (für das Homescreen-Widget). */
    @Query("SELECT * FROM booking ORDER BY created_at DESC, id DESC LIMIT :limit")
    List<Booking> getRecent(int limit);

    /**
     * Netto-Geldzufluss je Kategorie <b>und Kategorietyp</b> im Zeitraum [fromMs, toMs): eine Zeile je
     * (Kategorie, Typ), {@code total} ist <b>vorzeichenbehaftet</b> ({@code +} = Zufluss, {@code −} =
     * Abfluss). Getrennt nach Typ gruppiert (statt nur nach Text), da kMyMoney dieselbe Kategorie-
     * Bezeichnung unabhängig im Einnahme- und im Ausgabe-Baum haben kann; ohne Typtrennung würden solche
     * Namenskollisionen vermischt (siehe {@link Booking#categoryIsIncome}). Ist der Typ einer Zeile
     * unbekannt (NULL, vor dieser Migration), gruppiert SQLite sie automatisch in einen eigenen
     * „unbekannt"-Eimer – der Aufrufer fällt dafür weiter auf den globalen {@code category_type}-Typ
     * zurück. Splitbuchungen zählen über ihre vorzeichenbehafteten Teilbeträge (nicht doppelt), Umbuchungen
     * bleiben außen vor.
     */
    @Query("SELECT cat AS category, typ AS cat_type, SUM(signed) AS total FROM ("
            + " SELECT category AS cat, category_is_income AS typ, "
            + "        (CASE WHEN is_income THEN amount_cents ELSE -amount_cents END) AS signed "
            + "   FROM booking b "
            + "   WHERE category != '' AND is_transfer = 0 "
            + "     AND created_at >= :fromMs AND created_at < :toMs "
            + "     AND NOT EXISTS (SELECT 1 FROM booking_split s WHERE s.booking_id = b.id) "
            + " UNION ALL "
            + " SELECT bs.category AS cat, bs.category_is_income AS typ, "
            + "        (CASE WHEN b.is_income THEN bs.amount_cents ELSE -bs.amount_cents END) AS signed "
            + "   FROM booking_split bs JOIN booking b ON bs.booking_id = b.id "
            + "   WHERE bs.category != '' AND b.is_transfer = 0 "
            + "     AND b.created_at >= :fromMs AND b.created_at < :toMs "
            + " UNION ALL "
            + " SELECT category AS cat, is_income AS typ, "
            + "        (CASE WHEN is_income THEN amount_cents ELSE -amount_cents END) AS signed "
            + "   FROM analysis_extra "
            + "   WHERE category != '' AND created_at >= :fromMs AND created_at < :toMs) "
            + "GROUP BY cat, typ")
    List<CategorySum> getCategoryActuals(long fromMs, long toMs);

    /**
     * Historische Zahlungs-Magnitude je Kategorie und Tag im Monat (1–31) über den gesamten Verlauf –
     * für den „erwarteten Fortschritt" (Balkenfarbe, Monatssicht). Split-Teile wie in getCategoryActuals.
     */
    @Query("SELECT cat AS category, bucket AS bucket, SUM(amt) AS total FROM ("
            + " SELECT category AS cat, "
            + "        CAST(strftime('%d', created_at/1000, 'unixepoch', 'localtime') AS INTEGER) AS bucket, "
            + "        amount_cents AS amt "
            + "   FROM booking b "
            + "   WHERE category != '' AND is_transfer = 0 "
            + "     AND NOT EXISTS (SELECT 1 FROM booking_split s WHERE s.booking_id = b.id) "
            + " UNION ALL "
            + " SELECT bs.category AS cat, "
            + "        CAST(strftime('%d', b.created_at/1000, 'unixepoch', 'localtime') AS INTEGER) AS bucket, "
            + "        ABS(bs.amount_cents) AS amt "
            + "   FROM booking_split bs JOIN booking b ON bs.booking_id = b.id "
            + "   WHERE bs.category != '' AND b.is_transfer = 0 "
            + " UNION ALL "
            + " SELECT category AS cat, "
            + "        CAST(strftime('%d', created_at/1000, 'unixepoch', 'localtime') AS INTEGER) AS bucket, "
            + "        ABS(amount_cents) AS amt "
            + "   FROM analysis_extra WHERE category != '') "
            + "GROUP BY cat, bucket")
    List<CategoryBucket> getDayOfMonthHistogram();

    /**
     * Historische Zahlungs-Magnitude je Kategorie und Monat im Jahr (1–12) über den gesamten Verlauf –
     * für den „erwarteten Fortschritt" (Balkenfarbe, Jahressicht).
     */
    @Query("SELECT cat AS category, bucket AS bucket, SUM(amt) AS total FROM ("
            + " SELECT category AS cat, "
            + "        CAST(strftime('%m', created_at/1000, 'unixepoch', 'localtime') AS INTEGER) AS bucket, "
            + "        amount_cents AS amt "
            + "   FROM booking b "
            + "   WHERE category != '' AND is_transfer = 0 "
            + "     AND NOT EXISTS (SELECT 1 FROM booking_split s WHERE s.booking_id = b.id) "
            + " UNION ALL "
            + " SELECT bs.category AS cat, "
            + "        CAST(strftime('%m', b.created_at/1000, 'unixepoch', 'localtime') AS INTEGER) AS bucket, "
            + "        ABS(bs.amount_cents) AS amt "
            + "   FROM booking_split bs JOIN booking b ON bs.booking_id = b.id "
            + "   WHERE bs.category != '' AND b.is_transfer = 0 "
            + " UNION ALL "
            + " SELECT category AS cat, "
            + "        CAST(strftime('%m', created_at/1000, 'unixepoch', 'localtime') AS INTEGER) AS bucket, "
            + "        ABS(amount_cents) AS amt "
            + "   FROM analysis_extra WHERE category != '') "
            + "GROUP BY cat, bucket")
    List<CategoryBucket> getMonthOfYearHistogram();

    /** Jahre (mit Daten) mit Buchungen vor {@code ms} – Teiler für die Verlaufs-Budgetberechnung. */
    @Query("SELECT DISTINCT CAST(strftime('%Y', created_at / 1000, 'unixepoch', 'localtime') AS INTEGER) "
            + "FROM booking WHERE created_at < :ms")
    List<Integer> getDataYearsBefore(long ms);

    /** Anzahl aller Buchungen – Sicherheitsleine vor dem Aufräumen (leere Tabelle = frische Installation). */
    @Query("SELECT COUNT(*) FROM booking")
    int countAll();

    /**
     * Nur die Notizen mit Beleg-Verweis – Grundlage für das Aufräumen verwaister Belegdateien. Beide Arten:
     * {@code BELEG:} für Fotoseiten, {@code BELEG (PDF):} für PDF-Belege.
     */
    @Query("SELECT note FROM booking WHERE note LIKE '%BELEG:%' OR note LIKE '%BELEG (PDF):%'")
    List<String> getReceiptNotes();

    /** Buchungen mit Standort in der Notiz (neueste zuerst) – Vorlagen für die Betrag-only-Erfassung. */
    @Query("SELECT * FROM booking WHERE note LIKE '%GPS:%' ORDER BY created_at DESC, id DESC LIMIT 500")
    List<Booking> getWithGpsNote();

    /**
     * Die Kategorien, auf die dieser Empfänger schon gebucht wurde (jüngste zuerst) – Grundlage der
     * Vorbelegung im Editor. Die Teilzeilen einer Splitbuchung zählen mit, denn in
     * {@code booking.category} steht dort nur die erste. Der Empfängername muß genau stimmen
     * (Groß-/Kleinschreibung egal); Umbuchungen haben keine Kategorie.
     */
    @Query("SELECT category FROM ("
            + "SELECT category AS category, created_at AS created_at FROM booking "
            + "WHERE payee = :payee COLLATE NOCASE AND is_transfer = 0 AND is_income = :income "
            + "AND category != '' "
            + "UNION ALL "
            + "SELECT s.category AS category, b.created_at AS created_at FROM booking_split s "
            + "JOIN booking b ON s.booking_id = b.id "
            + "WHERE b.payee = :payee COLLATE NOCASE AND b.is_transfer = 0 AND b.is_income = :income "
            + "AND s.category != '') "
            + "ORDER BY created_at DESC LIMIT 200")
    List<String> getCategoriesByPayee(String payee, boolean income);

    /**
     * Die Stichwortfelder der Buchungen dieses Empfängers, jüngste zuerst – Quelle des Vorspanns im
     * Stichwort-Fenster ({@link PayeeTags}). Je Zeile steht dort eine ganze Liste, das Zerlegen
     * übernimmt {@link BookingTags}. Ohne Trennung nach Einnahme und Ausgabe: die kennt ein
     * Stichwort nicht. Umbuchungen zählen mit, denn auch sie können Stichwörter tragen.
     */
    @Query("SELECT tags FROM booking WHERE payee = :payee COLLATE NOCASE AND tags != '' "
            + "ORDER BY created_at DESC LIMIT 200")
    List<String> getTagsByPayee(String payee);

    /**
     * Die Beträge, auf die dieser Empfänger schon gebucht wurde – Grundlage des Betragssiebs
     * ({@link PayeeAmounts}). Bewußt <b>ohne</b> Standort-Bedingung: die Koordinaten entscheiden, wer
     * überhaupt Kandidat ist, die Beträge steuert der volle Bestand bei. Und ohne Teilzeilen, denn
     * verglichen wird mit dem Gesamtbetrag, und der steht auch bei einer Splitbuchung hier.
     *
     * <p>Eine Umbuchung liegt als <b>zwei</b> Zeilen mit demselben Betrag vor (Ausgangs- und
     * Eingangsseite). Der Aufrufer fragt deshalb bei {@code transfer} immer die Ausgangsseite
     * ({@code income = false}) ab, sonst zählte jeder Betrag doppelt.</p>
     */
    @Query("SELECT amount_cents FROM booking "
            + "WHERE payee = :payee COLLATE NOCASE "
            + "AND is_transfer = :transfer AND is_income = :income "
            + "ORDER BY created_at DESC LIMIT 50")
    List<Long> getAmountsByPayee(String payee, boolean income, boolean transfer);

    /** Ort-Link an allen Buchungen eines Kontos umbenennen (folgt dem Umbenennen in der Ortsverwaltung). */
    @Query("UPDATE booking SET place = :newName WHERE account = :account AND place = :oldName")
    void renamePlace(String account, String oldName, String newName);

    /**
     * Noch nie geschriebene Buchungen – die kommen als neue Transaktion in die Datei. Bearbeitete sind
     * bewußt nicht dabei: die stehen dort schon und werden geändert, siehe {@link #getEdited()}.
     *
     * <p>Ausgenommen sind die Geldbuchungen erfasster Depot-Bewegungen: in KMyMoney ist ein Kauf
     * <b>eine</b> Transaktion aus Wertpapier- und Geld-Split. Sie wird beim Export gemeinsam mit der
     * Bewegung geschrieben – käme die Buchung zusätzlich hier vorbei, stünde sie doppelt in der Datei.</p>
     */
    @Query("SELECT * FROM booking WHERE exported = 0 AND edited = 0 "
            + "AND id NOT IN (SELECT booking_id FROM security_tx WHERE booking_id > 0) "
            + "ORDER BY created_at ASC, id ASC")
    List<Booking> getUnexported();

    /** Nach dem Export geänderte Buchungen (Status „bearbeitet", siehe {@link EditStatus}). */
    @Query("SELECT * FROM booking WHERE edited = 1 ORDER BY created_at ASC, id ASC")
    List<Booking> getEdited();

    /** Setzt geschriebene Buchungen auf „exportiert" und räumt einen etwaigen Status „bearbeitet" ab. */
    @Query("UPDATE booking SET exported = 1, edited = 0, orig_account = '', orig_signed_cents = 0, "
            + "orig_created_at = 0 WHERE id IN (:ids)")
    void markExported(List<Long> ids);

    @Query("DELETE FROM booking")
    void deleteAll();

    /** Frühester bzw. spätester Buchungszeitpunkt (ms); {@code null} bei leerer Tabelle. */
    @Query("SELECT MIN(created_at) FROM booking")
    Long getFirstBookingMs();

    @Query("SELECT MAX(created_at) FROM booking")
    Long getLastBookingMs();

    /** Gesamtsaldo aller Buchungen (Einnahmen − Ausgaben). */
    @Query("SELECT COALESCE(SUM(CASE WHEN is_income THEN amount_cents ELSE -amount_cents END), 0) FROM booking")
    long getTotalBalance();

    /** Saldo eines einzelnen Kontos (Einnahmen − Ausgaben). */
    @Query("SELECT COALESCE(SUM(CASE WHEN is_income THEN amount_cents ELSE -amount_cents END), 0) "
            + "FROM booking WHERE account = :account")
    long getBalanceByAccount(String account);

    /** Saldo je Konto (Einnahmen − Ausgaben); Konten ganz ohne Buchungen fehlen (Saldo 0). */
    @Query("SELECT account AS name, "
            + "COALESCE(SUM(CASE WHEN is_income THEN amount_cents ELSE -amount_cents END), 0) AS balance "
            + "FROM booking GROUP BY account")
    List<AccountBalance> getAllAccountBalances();

    /** Kontosaldo bis einschließlich dieser Buchung (nach created_at, bei Gleichstand nach id). */
    @Query("SELECT COALESCE(SUM(CASE WHEN is_income THEN amount_cents ELSE -amount_cents END), 0) "
            + "FROM booking WHERE account = :account "
            + "AND (created_at < :createdAt OR (created_at = :createdAt AND id <= :id))")
    long getBalanceUpTo(String account, long createdAt, long id);

    /**
     * Alles, was aus der .kmy-Datei stammt oder dorthin gehört – auch bearbeitete Buchungen. Der Import
     * ist die Wahrheit: eine Bearbeitung, die noch nicht übertragen wurde, wird dabei überschrieben.
     * Bliebe sie stehen, stünde sie nach dem Import doppelt da.
     */
    @Query("DELETE FROM booking WHERE account = :account AND (exported = 1 OR edited = 1)")
    void deleteExportedByAccount(String account);

    @Query("DELETE FROM booking WHERE account = :account")
    void deleteAllByAccount(String account);

    @Query("DELETE FROM booking WHERE transfer_group = :group")
    void deleteByTransferGroup(String group);

    @Query("SELECT * FROM booking WHERE transfer_group = :group")
    List<Booking> getByTransferGroup(String group);

    /** Kategorien aus Einzelbuchungen UND Splitbuchungs-Teilen (für Filter/Baum/Auswertung). */
    @Query("SELECT DISTINCT category FROM ("
            + "SELECT category FROM booking WHERE category != '' "
            + "UNION SELECT category FROM booking_split WHERE category != '') "
            + "ORDER BY category COLLATE NOCASE ASC")
    List<String> getDistinctCategories();

    /**
     * Einnahme-Kategorien für die Auswahlliste: jede Kategorie, die entweder in mindestens einer
     * Buchung/Split explizit als Einnahme markiert ist ({@code category_is_income = 1}) oder – ohne
     * eigene Typangabe (NULL, vor der Kategorietyp-je-Zeile-Migration) – laut globalem kmy-Typ
     * ({@code category_type.is_income = 1}) eine Einnahme ist. Eine Kategorie mit echten Zeilen beider
     * Typen (z. B. „Versicherung:Krankenzusatz" sowohl im Einnahme- als auch im Ausgabe-Baum) erscheint
     * dadurch bewusst in <b>beiden</b> Listen – anders als vorher, wo die globale Typtabelle nur einen
     * Typ pro Text kennen konnte.
     */
    @Query("SELECT DISTINCT c.category FROM ("
            + "SELECT category, category_is_income AS typ FROM booking WHERE category != '' "
            + "UNION ALL "
            + "SELECT category, category_is_income AS typ FROM booking_split WHERE category != '') c "
            + "WHERE c.typ = 1 "
            + "   OR (c.typ IS NULL AND EXISTS ("
            + "         SELECT 1 FROM category_type ct "
            + "         WHERE ct.category = c.category COLLATE NOCASE AND ct.is_income = 1)) "
            + "ORDER BY c.category COLLATE NOCASE ASC")
    List<String> getIncomeCategories();

    /** Ausgabe-Kategorien für die Auswahlliste – spiegelbildlich zu {@link #getIncomeCategories()}. */
    @Query("SELECT DISTINCT c.category FROM ("
            + "SELECT category, category_is_income AS typ FROM booking WHERE category != '' "
            + "UNION ALL "
            + "SELECT category, category_is_income AS typ FROM booking_split WHERE category != '') c "
            + "WHERE c.typ = 0 "
            + "   OR (c.typ IS NULL AND EXISTS ("
            + "         SELECT 1 FROM category_type ct "
            + "         WHERE ct.category = c.category COLLATE NOCASE AND ct.is_income = 0)) "
            + "ORDER BY c.category COLLATE NOCASE ASC")
    List<String> getExpenseCategories();

    // ---- Splitbuchungs-Teile ----

    @Insert
    long insertSplit(BookingSplit split);

    @Query("SELECT * FROM booking_split WHERE booking_id = :bookingId ORDER BY id ASC")
    List<BookingSplit> getSplits(long bookingId);

    @Query("SELECT * FROM booking_split ORDER BY id ASC")
    List<BookingSplit> getAllSplits();

    /**
     * Welche dieser Buchungen sind Splitbuchungen? Nur die Kennungen, nicht die Teile selbst.
     *
     * <p>Für das Widget: Es zeigt drei Zeilen und braucht davon allein die Ja/Nein-Antwort, um bei
     * fehlendem Empfänger „Split-Buchung" statt der Kategorie zu schreiben. Die Masken halten ohnehin
     * ihre {@code splitsByBooking}-Karte; das Widget läuft in einem fremden Prozeß und lädt nur
     * {@link #getRecent(int)}. Eine Abfrage für alle drei Zeilen statt dreier einzelner.</p>
     */
    @Query("SELECT booking_id FROM booking_split WHERE booking_id IN (:ids) "
            + "GROUP BY booking_id HAVING COUNT(*) >= 2")
    List<Long> splitBookingIds(List<Long> ids);

    @Query("DELETE FROM booking_split WHERE booking_id = :bookingId")
    void deleteSplits(long bookingId);

    @Query("DELETE FROM booking_split")
    void deleteAllSplits();

    @Query("DELETE FROM booking_split WHERE booking_id IN "
            + "(SELECT id FROM booking WHERE account = :account AND (exported = 1 OR edited = 1))")
    void deleteSplitsForExportedAccount(String account);

    @Query("DELETE FROM booking_split WHERE booking_id IN "
            + "(SELECT id FROM booking WHERE account = :account)")
    void deleteSplitsForAccount(String account);
}
