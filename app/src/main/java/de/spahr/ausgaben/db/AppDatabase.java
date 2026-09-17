package de.spahr.ausgaben.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import de.spahr.ausgaben.settings.ProfileManager;

@Database(entities = {Booking.class, BookingSplit.class, Account.class, Payee.class, PlaceEntry.class,
        PayeeCorrection.class, Translation.class, Language.class, Security.class, SecurityTx.class,
        Budget.class, CategoryType.class, ScheduledTransaction.class, ScheduledSplit.class,
        AnalysisExtra.class, SecurityTxValueOverride.class, KmyPendingDelete.class, SecurityPrice.class,
        ScheduledAdvance.class, AccountGroup.class, AccountGroupMember.class, AccountKindOrder.class,
        Tag.class, SecurityTxSplit.class},
        version = 51, exportSchema = true)
public abstract class AppDatabase extends RoomDatabase {

    /** v1 → v2: Notiz-Spalte ergänzen (bestehende Buchungen bleiben erhalten). */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE booking ADD COLUMN note TEXT NOT NULL DEFAULT ''");
        }
    };

    /** v2 → v3: Kategorie-Spalte ergänzen. */
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE booking ADD COLUMN category TEXT NOT NULL DEFAULT ''");
        }
    };

    /** v3 → v4: Orts-Bewegungsjournal (Bargeld-Orte) anlegen. */
    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS place_entry ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "place TEXT NOT NULL, "
                    + "amount_cents INTEGER NOT NULL, "
                    + "created_at INTEGER NOT NULL, "
                    + "type TEXT NOT NULL)");
        }
    };

    /** v4 → v5: Orte an Konten binden – Konto-Spalte im Orts-Journal ergänzen. */
    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE place_entry ADD COLUMN account TEXT NOT NULL DEFAULT ''");
        }
    };

    /** v5 → v6: Splitbuchungen (booking_split) + Umbuchungs-Felder an der Buchung. */
    static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS booking_split ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "booking_id INTEGER NOT NULL, "
                    + "category TEXT NOT NULL, "
                    + "amount_cents INTEGER NOT NULL)");
            db.execSQL("ALTER TABLE booking ADD COLUMN is_transfer INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE booking ADD COLUMN transfer_account TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE booking ADD COLUMN transfer_group TEXT NOT NULL DEFAULT ''");
        }
    };

    /** v6 → v7: gelernte Namenskorrekturen für die Spracherkennung. */
    static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS payee_correction ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "spoken TEXT NOT NULL, "
                    + "corrected TEXT NOT NULL, "
                    + "created_at INTEGER NOT NULL)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_payee_correction_spoken "
                    + "ON payee_correction(spoken)");
        }
    };

    /** v7 → v8: Alias-Felder (Konto, Kategorien, Von/Bis-Konto) an der Korrektur-/Alias-Tabelle. */
    static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE payee_correction ADD COLUMN account TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE payee_correction ADD COLUMN cat_income_1 TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE payee_correction ADD COLUMN cat_income_2 TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE payee_correction ADD COLUMN cat_expense_1 TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE payee_correction ADD COLUMN cat_expense_2 TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE payee_correction ADD COLUMN from_account TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE payee_correction ADD COLUMN to_account TEXT NOT NULL DEFAULT ''");
        }
    };

    /** v8 → v9: „preferred"-Kennzeichen (Alias vor bestehender Buchung berücksichtigen). */
    static final Migration MIGRATION_8_9 = new Migration(8, 9) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE payee_correction ADD COLUMN preferred INTEGER NOT NULL DEFAULT 0");
        }
    };

    /** v9 → v10: bevorzugte Buchungsart des Alias. */
    static final Migration MIGRATION_9_10 = new Migration(9, 10) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE payee_correction ADD COLUMN type TEXT NOT NULL DEFAULT ''");
        }
    };

    /** v10 → v11: Übersetzungs-Tabellen für die Mehrsprachigkeit. */
    static final Migration MIGRATION_10_11 = new Migration(10, 11) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS translation ("
                    + "lang TEXT NOT NULL, "
                    + "key TEXT NOT NULL, "
                    + "value TEXT NOT NULL, "
                    + "PRIMARY KEY(lang, key))");
            db.execSQL("CREATE TABLE IF NOT EXISTS language ("
                    + "code TEXT NOT NULL PRIMARY KEY, "
                    + "name TEXT NOT NULL)");
        }
    };

    /** v11 → v12: Währungskennzeichen je Konto. */
    static final Migration MIGRATION_11_12 = new Migration(11, 12) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE account ADD COLUMN currency TEXT NOT NULL DEFAULT ''");
        }
    };

    /** v12 → v13: Standort (lat/lon) am Alias für die Betrag-only-Erfassung per GPS. */
    static final Migration MIGRATION_12_13 = new Migration(12, 13) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE payee_correction ADD COLUMN lat REAL NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE payee_correction ADD COLUMN lon REAL NOT NULL DEFAULT 0");
        }
    };

    /** v13 → v14: Ort an der Buchung; Orts-Bewegungsjournal auf 0 zurücksetzen (Salden aus Buchungen). */
    static final Migration MIGRATION_13_14 = new Migration(13, 14) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE booking ADD COLUMN place TEXT NOT NULL DEFAULT ''");
            db.execSQL("DELETE FROM place_entry");
        }
    };

    /**
     * v14 → v15: Ort-Bewegungsjournal ist wieder die Saldo-Quelle. {@code place_entry.note} für die
     * Bewegungsbeschreibung; {@code booking.place_managed} kennzeichnet in der App angelegte Buchungen
     * mit Ort-Verknüpfung (importierte bleiben 0). {@code booking.place} bleibt als loser Link erhalten.
     */
    static final Migration MIGRATION_14_15 = new Migration(14, 15) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE place_entry ADD COLUMN note TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE booking ADD COLUMN place_managed INTEGER NOT NULL DEFAULT 0");
        }
    };

    /** v15 → v16: Konten können geschlossen (inaktiv) werden. */
    static final Migration MIGRATION_15_16 = new Migration(15, 16) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE account ADD COLUMN closed INTEGER NOT NULL DEFAULT 0");
        }
    };

    /** v16 → v17: Depot-Import – Wertpapiere (mit letztem Kurs) und ihre Bewegungen. */
    static final Migration MIGRATION_16_17 = new Migration(16, 17) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS security ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "depot TEXT NOT NULL, kmy_id TEXT NOT NULL, name TEXT NOT NULL, "
                    + "symbol TEXT NOT NULL, currency TEXT NOT NULL, "
                    + "price REAL NOT NULL, price_date INTEGER NOT NULL)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_security_depot_kmy_id "
                    + "ON security(depot, kmy_id)");
            db.execSQL("CREATE TABLE IF NOT EXISTS security_tx ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "depot TEXT NOT NULL, security_kmy_id TEXT NOT NULL, security_name TEXT NOT NULL, "
                    + "date INTEGER NOT NULL, action TEXT NOT NULL, shares REAL NOT NULL, "
                    + "amount_cents INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_security_tx_depot ON security_tx(depot)");
        }
    };

    // Alias-Unique von spoken → (spoken, corrected): gleicher gesprochener Begriff darf auf mehrere
    // Empfänger zeigen (per GPS unterschieden). Bestehende Daten sind eindeutig, daher unproblematisch.
    static final Migration MIGRATION_17_18 = new Migration(17, 18) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("DROP INDEX IF EXISTS index_payee_correction_spoken");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_payee_correction_spoken_corrected "
                    + "ON payee_correction(spoken, corrected)");
        }
    };

    // Konto-Typ (KMyMoney) für die Trennung in Anlage-/Verbindlichkeitskonten. 0 = unbekannt/Anlage.
    static final Migration MIGRATION_18_19 = new Migration(18, 19) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE account ADD COLUMN acct_type INTEGER NOT NULL DEFAULT 0");
        }
    };

    // Namenloses Konto entfernen; Orte im Alias (Ausgabe/Einnahme + Umbuchung Von/Nach).
    static final Migration MIGRATION_19_20 = new Migration(19, 20) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("DELETE FROM account WHERE TRIM(name) = ''");
            db.execSQL("ALTER TABLE payee_correction ADD COLUMN place TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE payee_correction ADD COLUMN from_place TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE payee_correction ADD COLUMN to_place TEXT NOT NULL DEFAULT ''");
        }
    };

    // Netto-Betrag je Depot-Bewegung (für Dividenden brutto/netto). Vorbelegt = amount_cents; echter
    // Netto-Wert kommt beim nächsten Depot-Import.
    static final Migration MIGRATION_20_21 = new Migration(20, 21) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE security_tx ADD COLUMN net_cents INTEGER NOT NULL DEFAULT 0");
            db.execSQL("UPDATE security_tx SET net_cents = amount_cents");
        }
    };

    /** Alias-Standorte: von einer Koordinate (lat/lon) auf eine Koordinatenliste (gps_list) erweitern. */
    static final Migration MIGRATION_21_22 = new Migration(21, 22) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE payee_correction ADD COLUMN gps_list TEXT NOT NULL DEFAULT ''");
            db.execSQL("UPDATE payee_correction SET gps_list = lat || ',' || lon "
                    + "WHERE lat != 0 OR lon != 0");
        }
    };

    /** Budgetplanung: Soll-Werte je Kategorie und Jahr. */
    static final Migration MIGRATION_22_23 = new Migration(22, 23) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS budget ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "year INTEGER NOT NULL, "
                    + "category TEXT NOT NULL, "
                    + "is_income INTEGER NOT NULL, "
                    + "amount_cents INTEGER NOT NULL, "
                    + "source TEXT NOT NULL)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_budget_year_category_is_income "
                    + "ON budget(year, category, is_income)");
        }
    };

    /** Kategorie-Typ (Einnahme/Ausgabe) aus der KMyMoney-Datei – verlässliche Budget-Einordnung. */
    static final Migration MIGRATION_23_24 = new Migration(23, 24) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS category_type ("
                    + "category TEXT PRIMARY KEY NOT NULL, "
                    + "is_income INTEGER NOT NULL)");
        }
    };

    /** Monatsbudgets: Spalte month (0 = Jahr, 1–12 = Monat) + Unique-Index inkl. month. */
    static final Migration MIGRATION_24_25 = new Migration(24, 25) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE budget ADD COLUMN month INTEGER NOT NULL DEFAULT 0");
            db.execSQL("DROP INDEX IF EXISTS index_budget_year_category_is_income");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_budget_year_month_category_is_income "
                    + "ON budget(year, month, category, is_income)");
        }
    };

    /** Geplante Buchungen aus KMyMoney (bei jedem Import neu eingelesen). */
    static final Migration MIGRATION_25_26 = new Migration(25, 26) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS scheduled_transaction ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "kmy_id TEXT NOT NULL, "
                    + "name TEXT NOT NULL, "
                    + "kind INTEGER NOT NULL, "
                    + "next_due_ms INTEGER NOT NULL, "
                    + "amount_cents INTEGER NOT NULL, "
                    + "payee TEXT NOT NULL, "
                    + "account TEXT NOT NULL, "
                    + "counterparty TEXT NOT NULL)");
        }
    };

    /** Geplante Buchungen: Wiederholungsangaben (zum Auffalten in die einzelnen Termine). */
    static final Migration MIGRATION_26_27 = new Migration(26, 27) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE scheduled_transaction ADD COLUMN occurrence INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE scheduled_transaction "
                    + "ADD COLUMN occurrence_multiplier INTEGER NOT NULL DEFAULT 1");
        }
    };

    /** Geplante Buchungen: Enddatum (begrenzt die Projektion in die Zukunft). */
    static final Migration MIGRATION_27_28 = new Migration(27, 28) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE scheduled_transaction ADD COLUMN end_ms INTEGER NOT NULL DEFAULT 0");
        }
    };

    /** v28 → v29: Split-Kennzeichen + eigene Split-Tabelle für geplante Buchungen. */
    static final Migration MIGRATION_28_29 = new Migration(28, 29) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE scheduled_transaction ADD COLUMN split INTEGER NOT NULL DEFAULT 0");
            db.execSQL("CREATE TABLE IF NOT EXISTS scheduled_split ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "scheduled_id INTEGER NOT NULL, "
                    + "category TEXT NOT NULL, "
                    + "amount_cents INTEGER NOT NULL)");
        }
    };

    /** v29 → v30: Umbuchungs-Richtung (Geld in das Primärkonto oder heraus) für die geplanten Buchungen. */
    static final Migration MIGRATION_29_30 = new Migration(29, 30) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE scheduled_transaction ADD COLUMN incoming INTEGER NOT NULL DEFAULT 0");
        }
    };

    /** v30 → v31: eigene Tabelle für in Umbuchungen versteckte Ausgaben/Einnahmen (z. B. Wertpapier-Gebühren). */
    static final Migration MIGRATION_30_31 = new Migration(30, 31) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS analysis_extra ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "account TEXT NOT NULL, "
                    + "category TEXT NOT NULL, "
                    + "amount_cents INTEGER NOT NULL, "
                    + "is_income INTEGER NOT NULL, "
                    + "created_at INTEGER NOT NULL)");
        }
    };

    /**
     * Korrigiert Verkäufe, die aus KMyMoney-Dateien mit leerer Action fälschlich als Kauf gespeichert wurden.
     * Ein echter Kauf hat nie eine negative Stückzahl – solche Zeilen sind in Wahrheit Verkäufe. Ohne diese
     * Korrektur würde der Verkaufserlös zum Einstand addiert (Gewinn/Verlust falsch, z. B. −100 %).
     */
    static final Migration MIGRATION_31_32 = new Migration(31, 32) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("UPDATE security_tx SET action = 'sell' WHERE action = 'buy' AND shares < 0");
        }
    };

    /**
     * Neue Tabelle für manuell gesetzte Werte von Ein-/Ausbuchungen (KMyMoney liefert dafür nie einen
     * Geldwert). Eigenständig von security_tx, damit ein Depot-Reimport (löscht+schreibt security_tx neu)
     * die manuell gesetzten Werte nicht mitlöscht.
     */
    static final Migration MIGRATION_32_33 = new Migration(32, 33) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS security_tx_value_override ("
                    + "depot TEXT NOT NULL, "
                    + "security_kmy_id TEXT NOT NULL, "
                    + "date INTEGER NOT NULL, "
                    + "action TEXT NOT NULL, "
                    + "shares REAL NOT NULL, "
                    + "amount_cents INTEGER NOT NULL, "
                    + "PRIMARY KEY(depot, security_kmy_id, date, action, shares))");
        }
    };

    /**
     * Vormerkte, aber ungenutzte Spalte für eine Kmy-Transaktions-id + erste Fassung der Warteliste
     * lokal gelöschter, bereits in der .kmy-Datei vorhandener Buchungen (siehe {@link #MIGRATION_34_35}
     * für die tatsächlich genutzte Fassung – Transaktionen haben aus App-Sicht keine bekannte id).
     */
    static final Migration MIGRATION_33_34 = new Migration(33, 34) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE booking ADD COLUMN kmy_tx_id TEXT NOT NULL DEFAULT ''");
            db.execSQL("CREATE TABLE IF NOT EXISTS kmy_pending_delete ("
                    + "kmy_tx_id TEXT NOT NULL PRIMARY KEY, "
                    + "created_at INTEGER NOT NULL)");
        }
    };

    /**
     * Warteliste lokal gelöschter, bereits in der .kmy-Datei vorhandener Buchungen neu aufgesetzt: statt
     * einer (nie befüllten) Transaktions-id werden jetzt Konto, Datum und Betrag gespeichert – damit
     * funktioniert die Lösch-Synchronisierung auch für aus der .kmy-Datei importierte Buchungen, nicht
     * nur für von der App selbst geschriebene.
     */
    static final Migration MIGRATION_34_35 = new Migration(34, 35) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("DROP TABLE IF EXISTS kmy_pending_delete");
            db.execSQL("CREATE TABLE IF NOT EXISTS kmy_pending_delete ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "account TEXT NOT NULL, "
                    + "signed_cents INTEGER NOT NULL, "
                    + "created_at INTEGER NOT NULL, "
                    + "queued_at INTEGER NOT NULL)");
        }
    };

    /**
     * Kategorietyp je Zeile: kMyMoney erlaubt dieselbe Kategorie-Bezeichnung unabhängig im Einnahme- und
     * im Ausgabe-Baum (z. B. „Versicherung:Krankenzusatz"); der bisher rein globale Typ (category_type)
     * kann das nicht abbilden (Namenskollision überschreibt einen der beiden Typen). NULL = unbekannt
     * (bestehende Zeile) – kein Backfill, ein erneuter kMyMoney-Import befüllt importierte Zeilen neu.
     */
    static final Migration MIGRATION_35_36 = new Migration(35, 36) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE booking ADD COLUMN category_is_income INTEGER");
            db.execSQL("ALTER TABLE booking_split ADD COLUMN category_is_income INTEGER");
        }
    };

    /**
     * Kurshistorie je Wertpapier (vollständige KMyMoney-PRICES). Erlaubt die Bewertung des Depots zu
     * vergangenen Stichtagen (Vermögensgrafik). Wird beim nächsten Depot-Import befüllt.
     */
    static final Migration MIGRATION_36_37 = new Migration(36, 37) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS security_price ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "depot TEXT NOT NULL, "
                    + "security_kmy_id TEXT NOT NULL, "
                    + "date INTEGER NOT NULL, "
                    + "price REAL NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_security_price_depot_security_kmy_id_date "
                    + "ON security_price (depot, security_kmy_id, date)");
        }
    };

    /**
     * Erledigte/übersprungene geplante Buchungen: bis zum nächsten kmy-Export vorgemerktes Weiterstellen
     * der KMyMoney-Regel (siehe {@link ScheduledAdvance}).
     */
    static final Migration MIGRATION_37_38 = new Migration(37, 38) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS scheduled_advance ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "kmy_id TEXT NOT NULL, "
                    + "from_due_ms INTEGER NOT NULL, "
                    + "next_due_ms INTEGER NOT NULL, "
                    + "last_payment_ms INTEGER NOT NULL, "
                    + "updated_at INTEGER NOT NULL)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_scheduled_advance_kmy_id "
                    + "ON scheduled_advance (kmy_id)");
        }
    };

    /**
     * Kontengruppen: eine zweite, frei wählbare Ordnungsebene neben der Kontenart, dazu ein Sortierplatz
     * je Konto und je Kontenart. Alle bestehenden Konten bekommen Sortierplatz 0 – solange nichts von Hand
     * sortiert wurde, entscheidet weiterhin der Name, die Reihenfolge bleibt also unverändert.
     */
    static final Migration MIGRATION_38_39 = new Migration(38, 39) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE account ADD COLUMN sort_pos INTEGER NOT NULL DEFAULT 0");
            db.execSQL("CREATE TABLE IF NOT EXISTS account_group ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "name TEXT NOT NULL, "
                    + "auto INTEGER NOT NULL, "
                    + "sort_pos INTEGER NOT NULL)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_account_group_name "
                    + "ON account_group (name)");
            db.execSQL("CREATE TABLE IF NOT EXISTS account_group_member ("
                    + "group_id INTEGER NOT NULL, "
                    + "account_id INTEGER NOT NULL, "
                    + "PRIMARY KEY(group_id, account_id), "
                    + "FOREIGN KEY(group_id) REFERENCES account_group(id) ON DELETE CASCADE, "
                    + "FOREIGN KEY(account_id) REFERENCES account(id) ON DELETE CASCADE)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_account_group_member_account_id "
                    + "ON account_group_member (account_id)");
            db.execSQL("CREATE TABLE IF NOT EXISTS account_kind_order ("
                    + "kind INTEGER PRIMARY KEY NOT NULL, "
                    + "sort_pos INTEGER NOT NULL)");
            // Trägerzeile je bereits importiertem Depot, damit auch Bestandsnutzer ihre Depots sofort
            // sortieren und Gruppen zuordnen können. Gleichnamige Konten bleiben unangetastet (OR IGNORE).
            db.execSQL("INSERT OR IGNORE INTO account (name, currency, closed, acct_type, sort_pos) "
                    + "SELECT DISTINCT depot, '', 0, 7, 0 FROM security WHERE depot <> ''");
        }
    };

    /**
     * v39 → v40: Herkunftskennzeichen an der Kontengruppe. Bisher unterschied nur {@code auto} zwischen
     * selbst angelegt und aus der Datei abgeleitet; mit den Favoriten gibt es eine zweite abgeleitete
     * Gruppe, und die ist am Namen nicht wiederzuerkennen, weil er übersetzt ist.
     */
    static final Migration MIGRATION_39_40 = new Migration(39, 40) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE account_group ADD COLUMN source_key TEXT NOT NULL DEFAULT ''");
            db.execSQL("UPDATE account_group SET source_key = 'bank' WHERE auto = 1");
        }
    };

    /**
     * v40 → v41: Status „bearbeitet" für exportierte Buchungen, die nachträglich geändert wurden. Die drei
     * {@code orig_*}-Spalten halten die Signatur der exportierten Fassung fest, damit die Transaktion in der
     * .kmy-Datei auch dann noch zu finden ist, wenn Konto, Betrag oder Datum geändert wurden.
     */
    static final Migration MIGRATION_40_41 = new Migration(40, 41) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE booking ADD COLUMN edited INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE booking ADD COLUMN orig_account TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE booking ADD COLUMN orig_signed_cents INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE booking ADD COLUMN orig_created_at INTEGER NOT NULL DEFAULT 0");
        }
    };

    /**
     * v41 → v42: einstellbares Betragsband je Alias. Über die beiden Ränge (Vorgabe 10–90 %) entscheidet
     * sich, welche Beträge als typisch für diesen Empfänger gelten – das Sieb der Standort-Auflösung.
     */
    static final Migration MIGRATION_41_42 = new Migration(41, 42) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE payee_correction ADD COLUMN pct_low REAL NOT NULL DEFAULT 10");
            db.execSQL("ALTER TABLE payee_correction ADD COLUMN pct_high REAL NOT NULL DEFAULT 90");
        }
    };

    /**
     * v42 → v43: Stichwörter (die „Tags" aus KMyMoney). Je Buchung und je geplanter Buchung ein
     * Textfeld mit den Namen, dazu die aus der Datei übernommene Liste des Wählbaren.
     */
    static final Migration MIGRATION_42_43 = new Migration(42, 43) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE booking ADD COLUMN tags TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE scheduled_transaction ADD COLUMN tags TEXT NOT NULL DEFAULT ''");
            db.execSQL("CREATE TABLE IF NOT EXISTS tag ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "name TEXT NOT NULL)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_tag_name ON tag (name)");
        }
    };

    /**
     * v43 → v44: Stichwörter auch am Alias. Damit lernt ein Empfänger seine Stichwörter, so wie er
     * heute schon seine Kategorien lernt.
     */
    static final Migration MIGRATION_43_44 = new Migration(43, 44) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE payee_correction ADD COLUMN tags TEXT NOT NULL DEFAULT ''");
        }
    };

    /**
     * v44 → v45: Gebühren je Depot-Bewegung (für die Detailansicht einer Kauf-/Verkaufsbuchung). Nicht
     * rückrechenbar – die Spalte bleibt 0, bis das Depot erneut aus KMyMoney importiert wird.
     */
    static final Migration MIGRATION_44_45 = new Migration(44, 45) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE security_tx ADD COLUMN fee_cents INTEGER NOT NULL DEFAULT 0");
        }
    };

    /**
     * v45 → v46: in der App erfasste Depot-Bewegungen. {@code pending} kennzeichnet die noch nicht
     * exportierten (sie überleben einen Reimport), die übrigen Spalten halten Gegenkonto, Kategorien und
     * die zugehörige Geldbuchung. Alle Bestandszeilen stammen aus der Datei und bleiben mit den
     * Vorgabewerten korrekt.
     */
    static final Migration MIGRATION_45_46 = new Migration(45, 46) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE security_tx ADD COLUMN pending INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE security_tx ADD COLUMN money_account TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE security_tx ADD COLUMN fee_category TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE security_tx ADD COLUMN income_category TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE security_tx ADD COLUMN booking_id INTEGER NOT NULL DEFAULT 0");
        }
    };

    /**
     * v46 → v47: ISIN am Wertpapier. KMyMoney führt sie am Wertpapier als {@code kmm-security-id}; die
     * Spalte bleibt leer, bis das Depot erneut importiert wurde — und leer auch dann, wenn das Feld
     * „Identifikation" in KMyMoney nicht gepflegt ist.
     */
    static final Migration MIGRATION_46_47 = new Migration(46, 47) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE security ADD COLUMN isin TEXT NOT NULL DEFAULT ''");
        }
    };

    /**
     * v47 → v48: Kategoriezeilen an der Depotbewegung. Bisher trug jede Bewegung genau eine
     * Gebühren- und eine Ertragskategorie; Kapitalertragsteuer, Solidaritätszuschlag und Gebühren
     * mussten deshalb zu einer Zahl addiert und einer von ihnen zugeschlagen werden.
     *
     * <p>Der Bestand zieht in die neue Tabelle um, damit es nur eine Quelle gibt: aus jeder
     * gepflegten Kategorie wird eine Zeile über den vollen Betrag — bei der Dividende ist die Steuer
     * die Differenz von Brutto und Netto, sonst steht sie in {@code fee_cents}. Danach werden die
     * beiden alten Spalten geleert; sie bleiben nur deshalb in der Tabelle, weil SQLite sie ohne
     * einen vollständigen Neuaufbau nicht hergibt, und den ist der Bestand nicht wert.</p>
     */
    static final Migration MIGRATION_47_48 = new Migration(47, 48) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS security_tx_split ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "tx_id INTEGER NOT NULL, "
                    + "income INTEGER NOT NULL, "
                    + "category TEXT NOT NULL, "
                    + "amount_cents INTEGER NOT NULL, "
                    + "label TEXT NOT NULL, "
                    + "sort INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_security_tx_split_tx_id "
                    + "ON security_tx_split (tx_id)");
            db.execSQL("INSERT INTO security_tx_split "
                    + "(tx_id, income, category, amount_cents, label, sort) "
                    + "SELECT id, 0, fee_category, "
                    + "CASE WHEN action = 'dividend' THEN amount_cents - net_cents "
                    + "ELSE fee_cents END, '', 0 "
                    + "FROM security_tx WHERE fee_category <> ''");
            db.execSQL("INSERT INTO security_tx_split "
                    + "(tx_id, income, category, amount_cents, label, sort) "
                    + "SELECT id, 1, income_category, amount_cents, '', 0 "
                    + "FROM security_tx WHERE income_category <> ''");
            db.execSQL("UPDATE security_tx SET fee_category = '', income_category = ''");
        }
    };

    /**
     * v48 → v49: Sprachen tragen jetzt eine Standard-Währung und ein Standard-Zahlenformat, aus denen
     * das (schlanke) Onboarding die Vorbelegung eines neuen Profils ableitet, statt sie fest auf
     * „de"/„en" zu verdrahten. Die eingebauten Sprachen Deutsch/Englisch bekommen ihre Werte beim
     * nächsten Start ohnehin frisch über {@code LocaleManager.seed()} (REPLACE); hier reicht der
     * Default für bereits importierte, eigene Sprachen.
     */
    static final Migration MIGRATION_48_49 = new Migration(48, 49) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE language ADD COLUMN defaultCurrency TEXT NOT NULL DEFAULT '€'");
            db.execSQL("ALTER TABLE language ADD COLUMN numberFormat TEXT NOT NULL DEFAULT 'plain_comma'");
        }
    };

    /**
     * {@code net_cents} trägt bei Kauf und Verkauf das <b>bewegte Geld</b> statt noch einmal den
     * Bruttobetrag.
     *
     * <p>Bis dahin stand dort {@code amount_cents} — bei jeder Bewegung mit Gebühr wich das von der
     * Geldbuchung ab, und {@code SecurityTxMatch} fand die Bewegung zu einer Buchung nicht mehr; eine
     * gelöschte Buchung ließ ihre Bewegung stehen. Nachgerechnet wird aus den vorhandenen Spalten:
     * beim Verkauf geht die Gebühr ab, sonst kommt sie hinzu. Geraten wird dabei nichts.</p>
     *
     * <p>Dividenden bleiben unberührt — dort war {@code net_cents} schon immer die Gutschrift. Ebenso
     * {@code add}/{@code remove}: Sie bewegen kein Geld und tragen in beiden Spalten 0.</p>
     */
    static final Migration MIGRATION_49_50 = new Migration(49, 50) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("UPDATE security_tx SET net_cents = amount_cents - ABS(fee_cents) "
                    + "WHERE action = 'sell'");
            db.execSQL("UPDATE security_tx SET net_cents = amount_cents + ABS(fee_cents) "
                    + "WHERE action NOT IN ('sell', 'dividend')");
        }
    };

    /**
     * v50 → v51: Notiz an der Depot-Bewegung. Der Beleg-Tag einer eingelesenen Abrechnung hing bisher
     * allein an der Gegenbuchung und ging beim Rundlauf durch die KMyMoney-Datei verloren – siehe
     * {@link SecurityTx#note}. Bestehende Bewegungen starten leer; ihr Beleg steht weiterhin an der
     * Buchung, auf die {@code booking_id} zeigt.
     */
    static final Migration MIGRATION_50_51 = new Migration(50, 51) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE security_tx ADD COLUMN note TEXT NOT NULL DEFAULT ''");
        }
    };

    public abstract BookingDao bookingDao();

    public abstract AccountDao accountDao();

    public abstract AccountGroupDao accountGroupDao();

    public abstract PayeeDao payeeDao();

    public abstract TagDao tagDao();

    public abstract PlaceEntryDao placeEntryDao();

    public abstract PayeeCorrectionDao payeeCorrectionDao();

    public abstract TranslationDao translationDao();

    public abstract SecurityDao securityDao();

    public abstract BudgetDao budgetDao();

    public abstract CategoryTypeDao categoryTypeDao();

    public abstract ScheduledTransactionDao scheduledTransactionDao();

    public abstract ScheduledSplitDao scheduledSplitDao();

    public abstract AnalysisExtraDao analysisExtraDao();

    public abstract KmyPendingDeleteDao kmyPendingDeleteDao();

    public abstract ScheduledAdvanceDao scheduledAdvanceDao();

    private static volatile AppDatabase instance;
    private static volatile String openedDbFileName;

    public static AppDatabase getInstance(Context context) {
        String activeDbFileName = ProfileManager.currentDbFileName(context);
        // Prüfung und Öffnen/Schließen müssen atomar sein – sonst könnten zwei gleichzeitige Aufrufer
        // rund um einen Profilwechsel sich überschneiden und am Ende auf der falschen (oder einer
        // halb geschlossenen) Datenbank landen.
        synchronized (AppDatabase.class) {
            if (instance != null && !activeDbFileName.equals(openedDbFileName)) {
                if (instance.isOpen()) {
                    instance.close();
                }
                instance = null;
            }
            if (instance == null) {
                openedDbFileName = activeDbFileName;
                instance = Room.databaseBuilder(
                                context.getApplicationContext(),
                                AppDatabase.class,
                                activeDbFileName)
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                                MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                                MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                                MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16,
                                MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19,
                                MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22,
                                MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25,
                                MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28,
                                MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31,
                                MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35,
                                MIGRATION_35_36, MIGRATION_36_37, MIGRATION_37_38,
                                MIGRATION_38_39, MIGRATION_39_40, MIGRATION_40_41,
                                MIGRATION_41_42, MIGRATION_42_43, MIGRATION_43_44,
                                MIGRATION_44_45, MIGRATION_45_46, MIGRATION_46_47,
                                MIGRATION_47_48, MIGRATION_48_49, MIGRATION_49_50,
                                MIGRATION_50_51)
                        .build();
            }
            return instance;
        }
    }

    /** Schließt die offene Datenbank und verwirft die Singleton-Instanz (für Restore, Profilwechsel). */
    public static void closeInstance() {
        synchronized (AppDatabase.class) {
            if (instance != null) {
                if (instance.isOpen()) {
                    instance.close();
                }
                instance = null;
            }
            openedDbFileName = null;
        }
    }
}
