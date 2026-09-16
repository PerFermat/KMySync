package de.spahr.ausgaben.db;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Kapselt den Datenbankzugriff. Alle Operationen laufen auf einem Hintergrund-Thread;
 * Ergebnisse werden über {@link Callback} auf dem Main-Thread zurückgegeben.
 */
public class Repository {

    public interface Callback<T> {
        void onResult(T result);
    }

    /** Für {@code runInTransaction} beim Import (Massen-Insert in einer Transaktion). */
    private final AppDatabase db;
    private final BookingDao bookingDao;
    private final AccountDao accountDao;
    private final AccountGroupDao accountGroupDao;
    private final PayeeDao payeeDao;
    private final TagDao tagDao;
    private final PlaceEntryDao placeEntryDao;
    private final PayeeCorrectionDao correctionDao;
    private final TranslationDao translationDao;
    private final SecurityDao securityDao;
    private final BudgetDao budgetDao;
    private final CategoryTypeDao categoryTypeDao;
    private final ScheduledTransactionDao scheduledTransactionDao;
    private final ScheduledSplitDao scheduledSplitDao;
    private final AnalysisExtraDao analysisExtraDao;
    private final KmyPendingDeleteDao kmyPendingDeleteDao;
    private final ScheduledAdvanceDao scheduledAdvanceDao;
    private final Context appContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Fokussierte Kollaboratoren; teilen sich Executor + Main-Handler dieser Fassade (Reihenfolge bleibt). */
    private final BudgetRepository budgetRepo;
    private final DepotRepository depotRepo;
    private final AccountGroupRepository groupRepo;
    private final AliasResolver aliasResolver;

    public Repository(Context context) {
        this.appContext = context.getApplicationContext();
        this.db = AppDatabase.getInstance(context);
        AppDatabase db = this.db;
        this.bookingDao = db.bookingDao();
        this.accountDao = db.accountDao();
        this.accountGroupDao = db.accountGroupDao();
        this.payeeDao = db.payeeDao();
        this.tagDao = db.tagDao();
        this.placeEntryDao = db.placeEntryDao();
        this.correctionDao = db.payeeCorrectionDao();
        this.translationDao = db.translationDao();
        this.securityDao = db.securityDao();
        this.budgetDao = db.budgetDao();
        this.categoryTypeDao = db.categoryTypeDao();
        this.scheduledTransactionDao = db.scheduledTransactionDao();
        this.scheduledSplitDao = db.scheduledSplitDao();
        this.analysisExtraDao = db.analysisExtraDao();
        this.kmyPendingDeleteDao = db.kmyPendingDeleteDao();
        this.scheduledAdvanceDao = db.scheduledAdvanceDao();
        this.budgetRepo = new BudgetRepository(bookingDao, budgetDao, categoryTypeDao, executor, mainHandler);
        this.depotRepo = new DepotRepository(db, securityDao, appContext, executor, mainHandler);
        this.groupRepo = new AccountGroupRepository(accountDao, accountGroupDao, executor, mainHandler);
        this.aliasResolver = new AliasResolver(bookingDao, correctionDao, accountDao, executor, mainHandler);
    }

    // ---- Mehrsprachigkeit ----

    /** Setzt das Währungskennzeichen eines Kontos (legt es bei Bedarf an). Leere Währung wird ignoriert. */
    public void setAccountCurrency(final String account, final String currency) {
        if (account == null || account.trim().isEmpty() || currency == null || currency.trim().isEmpty()) {
            return;
        }
        executor.execute(() -> {
            accountDao.insertIfAbsent(new Account(account.trim()));
            accountDao.setCurrency(account.trim(), currency.trim());
        });
    }

    /** KMyMoney-Kontotyp beim Import übernehmen (Trennung Anlage/Verbindlichkeit). Typ 0 = ignorieren. */
    public void setAccountType(final String account, final int type) {
        if (account == null || account.trim().isEmpty() || type == 0) {
            return;
        }
        executor.execute(() -> {
            accountDao.insertIfAbsent(new Account(account.trim()));
            accountDao.setType(account.trim(), type);
        });
    }

    /**
     * Klassifiziert beim Import <b>alle</b> bereits vorhandenen Konten neu (Name → KMyMoney-Typ). Nur ein
     * reines UPDATE – Konten, die (noch) nicht existieren, werden nicht angelegt. Typ 0 wird übersprungen.
     */
    public void applyAccountTypes(final java.util.Map<String, Integer> types) {
        if (types == null || types.isEmpty()) {
            return;
        }
        executor.execute(() -> {
            for (java.util.Map.Entry<String, Integer> e : types.entrySet()) {
                if (e.getKey() != null && !e.getKey().trim().isEmpty()
                        && e.getValue() != null && e.getValue() != 0) {
                    accountDao.setType(e.getKey().trim(), e.getValue());
                }
            }
        });
    }

    /**
     * Übernimmt Bankinstitute und bevorzugte Konten aus der .kmy als Kontengruppen. Diese Gruppen
     * spiegeln nur die Datei: ihre Mitglieder werden bei jedem Import neu gesetzt, von Hand sind sie
     * nicht änderbar.
     */
    public void applyFileGroups(final java.util.Map<String, String> institutions,
                                final java.util.List<String> favorites, final String favoritesLabel) {
        boolean leer = (institutions == null || institutions.isEmpty())
                && (favorites == null || favorites.isEmpty());
        if (leer) {
            return;
        }
        executor.execute(() -> groupRepo.applyFileGroups(institutions, favorites, favoritesLabel));
    }

    /** Setzt den Namen der Favoritengruppe auf die Sprache der Oberfläche (Aufruf beim Start). */
    public void renameFavoritesGroup(final String label) {
        executor.execute(() -> groupRepo.renameFavorites(label));
    }

    /**
     * Öffnet geschlossene Konten wieder, die durch einen Import erneut einen Saldo bekommen haben.
     * Geschlossen wird nur bei Saldo 0 – ein Saldo ungleich 0 heißt also, dass das Konto wieder lebt.
     */
    public void reopenAccountsWithBalance(final Runnable onDone) {
        executor.execute(() -> {
            Map<String, Long> balances = new HashMap<>();
            for (AccountBalance ab : bookingDao.getAllAccountBalances()) {
                if (ab.name != null) {
                    balances.put(ab.name, ab.balance);
                }
            }
            for (String closed : accountDao.getClosedNames()) {
                Long balance = balances.get(closed);
                if (balance != null && balance != 0) {
                    accountDao.setClosed(closed, false);
                }
            }
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /**
     * Übernimmt beim .kmy-Import den Typ <b>aller</b> Kategorien der Datei (Pfad → Einnahme/Ausgabe).
     * Verlässliche, einzige Typ-Quelle für die Budget-Einordnung. Reines Upsert (mergt, löscht nichts).
     */
    public void applyCategoryTypes(final java.util.Map<String, Boolean> types) {
        if (types == null || types.isEmpty()) {
            return;
        }
        executor.execute(() -> {
            for (java.util.Map.Entry<String, Boolean> e : types.entrySet()) {
                if (e.getKey() != null && !e.getKey().trim().isEmpty() && e.getValue() != null) {
                    categoryTypeDao.upsert(new CategoryType(e.getKey().trim(), e.getValue()));
                }
            }
        });
    }

    /**
     * Ersetzt beim .kmy-Import die geplanten Buchungen komplett (leeren + neu einfügen), damit sie sich
     * „sobald ein Konto neu eingelesen wurde" aktualisieren. {@code onDone} optional (Main-Thread).
     */
    public void applyScheduledTransactions(final List<ScheduledTransaction> list, final Runnable onDone) {
        executor.execute(() -> {
            scheduledTransactionDao.deleteAll();
            scheduledSplitDao.deleteAll();
            if (list != null) {
                for (ScheduledTransaction st : list) {
                    if (st != null) {
                        long id = scheduledTransactionDao.insert(st);
                        if (st.splitParts != null) {
                            for (ScheduledSplit part : st.splitParts) {
                                part.scheduledId = id;
                                scheduledSplitDao.insert(part);
                            }
                        }
                    }
                }
            }
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /** Eine geplante Buchung nach id (für die Detail-Maske). */
    public void getScheduledById(final long id, final Callback<ScheduledTransaction> callback) {
        executor.execute(() -> {
            final ScheduledTransaction result = scheduledTransactionDao.getById(id);
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Die Kategorie-Teile einer geplanten Splitbuchung (für die Detail-Maske). */
    public void getScheduledSplits(final long scheduledId, final Callback<List<ScheduledSplit>> callback) {
        executor.execute(() -> {
            final List<ScheduledSplit> result = scheduledSplitDao.getForScheduled(scheduledId);
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Geplante Buchungen nach nächster Fälligkeit (für die Seite „Geplante Buchungen"). */
    public void getScheduledTransactions(final Callback<List<ScheduledTransaction>> callback) {
        executor.execute(() -> {
            final List<ScheduledTransaction> result = scheduledTransactionDao.getAllByDue();
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Geplante Buchungen samt der noch nicht übertragenen Vorrück-Vormerkungen. */
    public static class ScheduledData {
        public final List<ScheduledTransaction> transactions;
        /** {@code kmy_id} → neue nächste Fälligkeit; {@code 0} = Regel ist abgearbeitet. */
        public final java.util.Map<String, Long> advances;

        ScheduledData(List<ScheduledTransaction> transactions, java.util.Map<String, Long> advances) {
            this.transactions = transactions;
            this.advances = advances;
        }
    }

    /** Geplante Buchungen + Vormerkungen in einem Zug (für die Seite „Geplante Buchungen"). */
    public void getScheduledTransactionsWithAdvances(final Callback<ScheduledData> callback) {
        executor.execute(() -> {
            final List<ScheduledTransaction> list = scheduledTransactionDao.getAllByDue();
            final java.util.Map<String, Long> advances = new java.util.HashMap<>();
            for (ScheduledAdvance a : scheduledAdvanceDao.getAll()) {
                advances.put(a.kmyId, a.nextDueMs);
            }
            final ScheduledData result = new ScheduledData(list, advances);
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /**
     * Merkt vor, dass der Termin {@code dueMs} einer geplanten Buchung erledigt ({@code executed}) oder
     * übersprungen wurde: Die Regel rückt lokal um eine Periode weiter und wird beim nächsten kmy-Export
     * auch in der Datei weitergestellt. Die Regel selbst bleibt in jedem Fall bestehen.
     */
    public void advanceScheduled(final ScheduledTransaction st, final long dueMs, final boolean executed,
                                 final Runnable onDone) {
        if (st == null || st.kmyId == null || st.kmyId.trim().isEmpty()) {
            if (onDone != null) {
                mainHandler.post(onDone);
            }
            return;
        }
        final long next = ScheduleProjection.nextDue(dueMs, st.occurrence, st.occurrenceMultiplier);
        executor.execute(() -> {
            ScheduledAdvance a = scheduledAdvanceDao.getByKmyId(st.kmyId);
            long lastPayment = a == null ? 0 : a.lastPaymentMs;
            if (executed) {
                lastPayment = dueMs;
            }
            if (a == null) {
                scheduledAdvanceDao.insert(new ScheduledAdvance(st.kmyId, dueMs, next, lastPayment,
                        System.currentTimeMillis()));
            } else {
                a.fromDueMs = dueMs;
                a.nextDueMs = next;
                a.lastPaymentMs = lastPayment;
                a.updatedAt = System.currentTimeMillis();
                scheduledAdvanceDao.update(a);
            }
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /** Alle Kategorie-Teile geplanter Splitbuchungen auf einmal (für die Kategorien-Auswertung). */
    public void getAllScheduledSplits(final Callback<List<ScheduledSplit>> callback) {
        executor.execute(() -> {
            final List<ScheduledSplit> result = scheduledSplitDao.getAll();
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Frühester und spätester Buchungszeitpunkt als {@code {min, max}} (0/0 bei leerer DB). */
    public void getBookingDateRange(final Callback<long[]> callback) {
        executor.execute(() -> {
            final Long min = bookingDao.getFirstBookingMs();
            final Long max = bookingDao.getLastBookingMs();
            final long[] result = {min == null ? 0L : min, max == null ? 0L : max};
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /**
     * Aktive Konten nach Kontenart getrennt – für Schublade und Bestände.
     *
     * @param groupId Kontengruppe, auf die eingeschränkt wird; 0 = alle Konten
     */
    public void getAccountsGrouped(final long groupId, final Callback<AccountGroups> callback) {
        executor.execute(() -> {
            final AccountGroups g = new AccountGroups(accountDao.getAssetNames(groupId),
                    accountDao.getLiabilityNames(groupId), accountDao.getDepotNames(groupId));
            mainHandler.post(() -> callback.onResult(g));
        });
    }

    /** Aktive Konten nach Kontenart getrennt. */
    public static final class AccountGroups {
        public final List<String> assets;
        public final List<String> liabilities;
        public final List<String> depots;
        public AccountGroups(List<String> assets, List<String> liabilities, List<String> depots) {
            this.assets = assets;
            this.liabilities = liabilities;
            this.depots = depots;
        }
    }

    // ---- Kontengruppen und Kontenreihenfolge ----

    /** Alle Gruppen – für das Zuordnungs-Menü am Konto. */
    public void getAccountGroups(final Callback<List<AccountGroup>> callback) {
        groupRepo.getGroups(callback);
    }

    /** Gruppen für die Auswahl – nur solche mit mindestens einem offenen Konto. */
    public void getSelectableAccountGroups(final Callback<List<AccountGroup>> callback) {
        groupRepo.getSelectableGroups(callback);
    }

    public void getAccountGroup(final long groupId, final Callback<AccountGroup> callback) {
        groupRepo.getGroup(groupId, callback);
    }

    public void getAccountNamesInGroup(final long groupId, final Callback<List<String>> callback) {
        groupRepo.getNamesInGroup(groupId, callback);
    }

    /** Favoriten und Konten der gewählten Gruppe – die beiden Blöcke vorn in jedem Kontenfeld. */
    public void getAccountPickerBlocks(final long groupId, final Callback<PickerBlocks> callback) {
        groupRepo.getPickerBlocks(groupId, callback);
    }

    /** Favoriten, Konten der gewählten Gruppe und die Gruppe selbst – letztere für ihr Symbol. */
    public static final class PickerBlocks {
        public final List<String> favorites;
        public final List<String> group;
        public final AccountGroup groupInfo;

        PickerBlocks(List<String> favorites, List<String> group, AccountGroup groupInfo) {
            this.favorites = favorites;
            this.group = group;
            this.groupInfo = groupInfo;
        }
    }

    /** Der Name der neuen Gruppe gehört zu einer aus der .kmy abgeleiteten – nichts wurde geschrieben. */
    public static final int GROUPS_NAME_FROM_FILE = AccountGroupRepository.APPLY_NAME_FROM_FILE;

    /**
     * Übernimmt die Gruppen eines Kontos so, wie sie im Zuordnungsdialog angekreuzt sind.
     *
     * @param selected     vollständiger Sollzustand über alle eigenen Gruppen
     * @param newGroupName zusätzlich anzulegende Gruppe; leer, wenn keine
     * @param callback     {@link #GROUPS_NAME_FROM_FILE} bei belegtem Namen, sonst 0
     */
    public void applyAccountGroups(final String account, final java.util.Set<Long> selected,
                                   final String newGroupName, final Callback<Integer> callback) {
        groupRepo.applyMembership(account, selected, newGroupName, callback);
    }

    public void getAccountGroupIds(final String account, final Callback<java.util.Set<Long>> callback) {
        groupRepo.getGroupIdsOfAccount(account, callback);
    }

    public void getAccountKindOrder(final Callback<int[]> callback) {
        groupRepo.getKindOrder(callback);
    }

    public void saveAccountKindOrder(final int[] kinds, final Runnable onDone) {
        groupRepo.saveKindOrder(kinds, onDone);
    }

    public void saveAccountOrder(final List<Account> accountsInOrder, final Runnable onDone) {
        groupRepo.saveAccountOrder(accountsInOrder, onDone);
    }

    public void getLanguages(final Callback<List<Language>> callback) {
        executor.execute(() -> {
            final List<Language> result = translationDao.getLanguages();
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /**
     * Baut die JSON-Export-Vorlage (alle Schlüssel mit DE/EN als Referenz). Bei einer hochgeladenen
     * Sprache ist sie mit deren Texten vorbelegt, bei einer eingebauten bleibt sie leer.
     */
    public void buildLanguageTemplate(final String lang,
                                      final Callback<de.spahr.ausgaben.i18n.TranslationIo.Template>
                                              callback) {
        executor.execute(() -> {
            de.spahr.ausgaben.i18n.TranslationIo.Template template;
            try {
                List<TranslationDao.KeyValue> current = null;
                Language language = null;
                if (!de.spahr.ausgaben.i18n.LocaleManager.isBuiltIn(lang)) {
                    current = translationDao.getPairsOrdered(lang);
                    for (Language l : translationDao.getLanguages()) {
                        if (l.code.equals(lang)) {
                            language = l;
                            break;
                        }
                    }
                }
                template = de.spahr.ausgaben.i18n.TranslationIo.buildTemplate(
                        translationDao.getPairsOrdered("de"), translationDao.getPairsOrdered("en"),
                        current, language);
            } catch (Exception e) {
                template = null;
            }
            final de.spahr.ausgaben.i18n.TranslationIo.Template result = template;
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Importiert eine (geparste) Sprache in die DB; ersetzt eine bestehende gleichen Codes. */
    public void importLanguage(final de.spahr.ausgaben.i18n.TranslationIo.Parsed parsed,
                               final Runnable onDone) {
        executor.execute(() -> {
            List<Translation> rows = new ArrayList<>();
            for (Map.Entry<String, String> e : parsed.values.entrySet()) {
                rows.add(new Translation(parsed.code, e.getKey(), e.getValue()));
            }
            translationDao.deleteTranslations(parsed.code);
            translationDao.insertAll(rows);
            translationDao.upsertLanguage(
                    new Language(parsed.code, parsed.name, parsed.defaultCurrency, parsed.numberFormat));
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /** Wear-relevante Texte (Schlüssel „wear_*") der Sprache – für die Übertragung an die Uhr. */
    public void getWearStrings(final String lang, final Callback<Map<String, String>> callback) {
        executor.execute(() -> {
            Map<String, String> m = new HashMap<>();
            for (TranslationDao.KeyValue kv : translationDao.getPairs(lang)) {
                if (kv.key.startsWith("wear_")) {
                    m.put(kv.key, kv.value);
                }
            }
            final Map<String, String> result = m;
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /**
     * Speichert eine Buchung und legt Konto/Empfänger bei Bedarf als Auswahlwert an.
     */
    public void saveBooking(final Booking booking, final Runnable onDone) {
        executor.execute(() -> {
            payeeDao.insertIfAbsent(new Payee(booking.payee));
            accountDao.insertIfAbsent(new Account(booking.account));
            bookingDao.insert(booking);
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /** Aktualisiert eine Buchung und ergänzt geänderte Konto-/Empfängerwerte. */
    public void updateBooking(final Booking booking, final Runnable onDone) {
        executor.execute(() -> {
            payeeDao.insertIfAbsent(new Payee(booking.payee));
            accountDao.insertIfAbsent(new Account(booking.account));
            bookingDao.update(booking);
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /**
     * Speichert an einer Buchung <b>nur</b> Notiz und Stichwörter (und damit den Belegverweis). Für
     * Wertpapier-Buchungen: Betrag, Konten und Datum stammen aus der KMyMoney-Datei und dürfen sich
     * nicht ändern – die Stückzahl dazu kennt die App gar nicht. Der Status „bearbeitet" wird gesetzt,
     * damit der Export die Angaben nachträgt; dort wird die Transaktion dann gezielt geändert statt
     * neu gebaut (siehe {@code KmyExporter}).
     */
    public void updateNotesAndTags(final Booking booking, final Runnable onDone) {
        executor.execute(() -> {
            applyEditStatus(booking);
            bookingDao.update(booking);
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /**
     * Aktualisiert eine Buchung und ersetzt ihre Kategorie-Teile (Splitbuchung). {@code parts} leer/null →
     * die Buchung wird zur Einzelkategorie ({@link Booking#category}); vorhandene Teile werden entfernt.
     */
    public void updateSplitBooking(final Booking booking, final List<BookingSplit> parts,
                                   final Runnable onDone) {
        executor.execute(() -> {
            applyEditStatus(booking);
            payeeDao.insertIfAbsent(new Payee(booking.payee));
            accountDao.insertIfAbsent(new Account(booking.account));
            bookingDao.update(booking);
            bookingDao.deleteSplits(booking.id);
            if (parts != null) {
                for (BookingSplit p : parts) {
                    p.bookingId = booking.id;
                    bookingDao.insertSplit(p);
                }
            }
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /**
     * Setzt an einer zu speichernden Buchung den Status „bearbeitet", wenn sie in der .kmy-Datei schon
     * steht (siehe {@link EditStatus}). Läuft bereits auf dem Executor-Thread.
     */
    private void applyEditStatus(Booking updated) {
        if (updated == null || updated.id == 0) {
            return;
        }
        EditStatus.apply(bookingDao.getById(updated.id), updated, isKmyMode());
    }

    /** Ob die Buchungen mit einer gemeinsamen .kmy-Datei abgeglichen werden. */
    private boolean isKmyMode() {
        return new de.spahr.ausgaben.settings.SettingsStore(appContext).isKmyMode();
    }

    /**
     * Speichert eine neue Splitbuchung (Buchung + Kategorie-Teile) und verschiebt zusätzlich optional den
     * Saldo eines Ortes (wie {@link #saveBookingWithPlace}). {@code parts} sind die Kategorie-Teile.
     */
    public void saveSplitBooking(final Booking booking, final List<BookingSplit> parts,
                                 final String place, final Runnable onDone) {
        booking.place = isRealPlace(place) ? place.trim() : "";
        booking.placeManaged = true;
        executor.execute(() -> {
            payeeDao.insertIfAbsent(new Payee(booking.payee));
            accountDao.insertIfAbsent(new Account(booking.account));
            long id = bookingDao.insert(booking);
            if (parts != null) {
                for (BookingSplit p : parts) {
                    p.bookingId = id;
                    bookingDao.insertSplit(p);
                }
            }
            insertBookingMovement(booking);
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /** Legt eine Umbuchung als zwei verknüpfte Buchungen an (Ausgabe auf {@code from}, Einnahme auf {@code to}). */
    public void saveTransferBooking(final String from, final String to, final long cents,
                                    final String payee, final String note, final long createdAt,
                                    final Runnable onDone) {
        saveTransferBooking(from, to, cents, payee, note, "", createdAt, "", "", onDone);
    }

    /**
     * Wie {@link #saveTransferBooking}, füllt zusätzlich das Ortsjournal für Von-/Nach-Ort. Die
     * Stichwörter bekommen <b>beide</b> Zeilen: in der {@code .kmy}-Datei ist die Umbuchung eine
     * Transaktion, also gehören sie zu ihr als Ganzes.
     */
    public void saveTransferBooking(final String from, final String to, final long cents,
                                    final String payee, final String note, final String tags,
                                    final long createdAt,
                                    final String fromPlace, final String toPlace,
                                    final Runnable onDone) {
        executor.execute(() -> {
            String group = UUID.randomUUID().toString();
            insertTransferPair(from, to, cents, payee, note, tags, createdAt, null, group,
                    fromPlace, toPlace);
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /**
     * Aktualisiert eine Umbuchung. Bei App-Umbuchungen (nicht leere {@code transfer_group}) werden beide
     * Seiten neu aufgebaut; bei importierten Einseitern wird die einzelne Buchung angepasst.
     */
    public void updateTransferBooking(final Booking existing, final String from, final String to,
                                      final long cents, final String payee, final String note,
                                      final long createdAt, final Runnable onDone) {
        updateTransferBooking(existing, from, to, cents, payee, note, "", createdAt, "", "", onDone);
    }

    /** Wie {@link #updateTransferBooking}, aktualisiert zusätzlich das Ortsjournal (Von-/Nach-Ort). */
    public void updateTransferBooking(final Booking existing, final String from, final String to,
                                      final long cents, final String payee, final String note,
                                      final String tags, final long createdAt,
                                      final String fromPlace, final String toPlace,
                                      final Runnable onDone) {
        executor.execute(() -> {
            boolean kmy = isKmyMode();
            if (existing.transferGroup != null && !existing.transferGroup.isEmpty()) {
                // Beide Seiten werden neu angelegt; der Status muß die alte Von-Zeile überdauern, denn nur
                // ihre Signatur findet die eine Transaktion in der .kmy-Datei wieder.
                Booking status = new Booking();
                status.exported = existing.exported;
                EditStatus.apply(fromSideOf(existing.transferGroup), status, kmy);
                rollbackTransferPlaces(existing.transferGroup);
                bookingDao.deleteByTransferGroup(existing.transferGroup);
                insertTransferPair(from, to, cents, payee, note, tags, createdAt, status,
                        existing.transferGroup, fromPlace, toPlace);
            } else {
                // Importierte einseitige Umbuchung: an das ursprüngliche Konto gebunden lassen.
                String orig = existing.account;
                if (to.equalsIgnoreCase(orig)) {
                    existing.isIncome = true;
                    existing.account = orig;
                    existing.transferAccount = from;
                } else {
                    existing.isIncome = false;
                    existing.account = from.equalsIgnoreCase(orig) ? orig : from;
                    existing.transferAccount = to;
                }
                existing.amountCents = cents;
                existing.note = note == null ? "" : note;
                existing.tags = tags == null ? "" : tags;
                existing.createdAt = createdAt;
                existing.isTransfer = true;
                existing.category = "";
                existing.payee = payee == null ? "" : payee.trim();
                EditStatus.apply(bookingDao.getById(existing.id), existing, kmy);
                bookingDao.deleteSplits(existing.id);
                if (!existing.payee.isEmpty()) {
                    payeeDao.insertIfAbsent(new Payee(existing.payee));
                }
                accountDao.insertIfAbsent(new Account(existing.account));
                accountDao.insertIfAbsent(new Account(existing.transferAccount));
                bookingDao.update(existing);
            }
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /**
     * Fügt die beiden Seiten einer Umbuchung ein und – falls ein echter Ort gewählt ist – die passenden
     * Ort-Bewegungen (Von-Konto: −Betrag am {@code fromPlace}, Nach-Konto: +Betrag am {@code toPlace}).
     * Läuft bereits auf dem Executor-Thread.
     *
     * @param status Buchung, deren Export-Status (exportiert/bearbeitet samt Signatur der exportierten
     *               Fassung) beide neuen Zeilen erben; {@code null} = frische, noch nicht exportierte
     *               Umbuchung. Nötig, weil das Ändern einer Umbuchung beide Zeilen neu anlegt.
     */
    private void insertTransferPair(String from, String to, long cents, String payee, String note,
                                    String tags, long createdAt, Booking status, String group,
                                    String fromPlace, String toPlace) {
        accountDao.insertIfAbsent(new Account(from));
        accountDao.insertIfAbsent(new Account(to));
        String memo = note == null ? "" : note;
        String tagList = tags == null ? "" : tags;
        String p = payee == null ? "" : payee.trim();
        if (!p.isEmpty()) {
            payeeDao.insertIfAbsent(new Payee(p));
        }
        boolean fromManaged = isRealPlace(fromPlace);
        boolean toManaged = isRealPlace(toPlace);
        String fromP = fromManaged ? fromPlace.trim() : "";
        String toP = toManaged ? toPlace.trim() : "";
        String moveNote = p.isEmpty() ? "Umbuchung" : "Umbuchung: " + p;

        Booking out = new Booking();
        out.amountCents = cents;
        out.isIncome = false;
        out.account = from;
        out.transferAccount = to;
        out.isTransfer = true;
        out.transferGroup = group;
        out.payee = p;
        out.note = memo;
        out.tags = tagList;
        out.createdAt = createdAt;
        EditStatus.inherit(status, out);
        out.place = fromP;
        out.placeManaged = fromManaged;
        bookingDao.insert(out);
        if (fromManaged) {
            placeEntryDao.insert(new PlaceEntry(from, fromP, -cents, createdAt, "transfer", moveNote));
        }

        Booking in = new Booking();
        in.amountCents = cents;
        in.isIncome = true;
        in.account = to;
        in.transferAccount = from;
        in.isTransfer = true;
        in.transferGroup = group;
        in.payee = p;
        in.note = memo;
        in.tags = tagList;
        in.createdAt = createdAt;
        EditStatus.inherit(status, in);
        in.place = toP;
        in.placeManaged = toManaged;
        bookingDao.insert(in);
        if (toManaged) {
            placeEntryDao.insert(new PlaceEntry(to, toP, cents, createdAt, "transfer", moveNote));
        }
    }

    /** Die Ausgabe-Seite („von") einer Umbuchungs-Gruppe, sonst irgendeine; {@code null} bei leerer Gruppe. */
    private Booking fromSideOf(String group) {
        Booking any = null;
        for (Booking b : bookingDao.getByTransferGroup(group)) {
            if (!b.isIncome) {
                return b;
            }
            any = b;
        }
        return any;
    }

    /**
     * Zieht die Ort-Bewegungen einer bestehenden Umbuchung zurück (Gegenbewegung je Seite mit echtem Ort),
     * bevor die Buchungen gelöscht/neu aufgebaut werden. Läuft auf dem Executor-Thread.
     */
    private void rollbackTransferPlaces(String group) {
        if (group == null || group.isEmpty()) {
            return;
        }
        for (Booking b : bookingDao.getByTransferGroup(group)) {
            if (b.placeManaged && isRealPlace(b.place)) {
                placeEntryDao.insert(new PlaceEntry(b.account, b.place, -signed(b),
                        System.currentTimeMillis(), "transfer", "Umbuchung geändert"));
            }
        }
    }

    /** Kategorie-Teile einer Buchung (für den Editor im Bearbeiten-Modus). */
    public void getSplits(final long bookingId, final Callback<List<BookingSplit>> callback) {
        executor.execute(() -> {
            final List<BookingSplit> result = bookingDao.getSplits(bookingId);
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Buchungen zu einer Liste von Nummern – für den wiederaufgenommenen Beleg-Export. */
    public void getBookingsByIds(final List<Long> ids, final Callback<List<Booking>> callback) {
        executor.execute(() -> {
            final List<Booking> result = ids == null || ids.isEmpty()
                    ? new java.util.ArrayList<>()
                    : bookingDao.getByIds(ids);
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Buchungen im Zeitraum, ohne Umbuchungen – Grundlage für den Kategorie-Drilldown. */
    public void getBookingsBetween(final long fromMs, final long toMs, final Callback<List<Booking>> callback) {
        executor.execute(() -> {
            final List<Booking> result = bookingDao.getBookingsBetween(fromMs, toMs);
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Alle Kategorie-Teile, gruppiert nach Buchungs-ID (für Anzeige/Filter in der Liste). */
    public void getAllSplitsMap(final Callback<Map<Long, List<BookingSplit>>> callback) {
        executor.execute(() -> {
            final Map<Long, List<BookingSplit>> map = new HashMap<>();
            for (BookingSplit s : bookingDao.getAllSplits()) {
                List<BookingSplit> list = map.get(s.bookingId);
                if (list == null) {
                    list = new ArrayList<>();
                    map.put(s.bookingId, list);
                }
                list.add(s);
            }
            mainHandler.post(() -> callback.onResult(map));
        });
    }

    public void deleteBooking(final long id, final Runnable onDone) {
        executor.execute(() -> {
            Booking old = bookingDao.getById(id);
            queueKmyDeleteIfNeeded(old);
            bookingDao.deleteSplits(id);
            bookingDao.delete(id);
            // Ort-Journal nachziehen: Gegenbewegung anhängen (alte Bewegung bleibt als Historie stehen).
            if (old != null && old.placeManaged && isRealPlace(old.place)) {
                placeEntryDao.insert(new PlaceEntry(old.account, old.place, -signed(old),
                        System.currentTimeMillis(), "booking", "Buchung gelöscht"));
            }
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /**
     * Merkt eine bereits in der .kmy-Datei vorhandene Buchung (egal ob von der App exportiert oder von
     * dort importiert – beides markiert {@link Booking#exported}, ebenso eine seither geänderte mit
     * {@link Booking#edited}) zum Löschen vor, nur im kmy-Modus (in
     * anderen Speicherarten gibt es keine gemeinsame Datei, aus der etwas entfernt werden müsste). Die
     * nächste {@code KmyExportCoordinator}-Übertragung sucht die passende Transaktion über Konto, Datum
     * und Betrag und entfernt sie; siehe {@link KmyPendingDelete}.
     */
    private void queueKmyDeleteIfNeeded(Booking old) {
        if (old == null || (!old.exported && !old.edited)) {
            return;
        }
        if (!isKmyMode()) {
            return;
        }
        // Bei „bearbeitet" steht in der Datei noch die exportierte Fassung – nur deren Signatur trifft sie.
        kmyPendingDeleteDao.insert(new KmyPendingDelete(EditStatus.fileAccount(old),
                EditStatus.fileSignedCents(old), EditStatus.fileCreatedAt(old),
                System.currentTimeMillis()));
    }

    /** Löscht eine Umbuchung: beide Seiten (über {@code group}) oder die einzelne (importierte) Buchung. */
    public void deleteTransfer(final String group, final long fallbackId, final Runnable onDone) {
        executor.execute(() -> {
            if (group != null && !group.isEmpty()) {
                for (Booking b : bookingDao.getByTransferGroup(group)) {
                    queueKmyDeleteIfNeeded(b);
                }
                rollbackTransferPlaces(group);
                bookingDao.deleteByTransferGroup(group);
            } else {
                queueKmyDeleteIfNeeded(bookingDao.getById(fallbackId));
                bookingDao.delete(fallbackId);
            }
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /**
     * Die Depot-Bewegung zu dieser Geldbuchung – oder {@code null}, wenn es keine gibt.
     *
     * <p>Die Maske fragt damit vorab, ob sie «Löschen» überhaupt anbieten darf: bei einer gewöhnlichen
     * Umbuchung zwischen zwei eigenen Konten kommt hier nichts zurück.</p>
     */
    public void getSecurityTxForBooking(final Booking booking, final Callback<SecurityTx> callback) {
        executor.execute(() -> {
            final SecurityTx tx = findSecurityTx(booking);
            mainHandler.post(() -> callback.onResult(tx));
        });
    }

    /**
     * Löscht eine Wertpapier-Buchung samt ihrer Depot-Bewegung – beides in <b>einer</b> Transaktion,
     * damit nie eine Bewegung ohne Buchung (oder umgekehrt) übrig bleibt.
     *
     * <p>Steht die Buchung bereits in der KMyMoney-Datei, wird die Löschung über
     * {@link #queueKmyDeleteIfNeeded} vorgemerkt: der nächste Export entfernt dort die <b>ganze</b>
     * Transaktion, also Geld-, Wertpapier- und Gebühren-Split zusammen. Anders als beim Ändern kann dabei
     * nichts halb stehenbleiben – deshalb ist Löschen erlaubt, wo Ändern gesperrt ist.</p>
     *
     * <p>Anders als {@link #deleteManualSecurityTx} fasst dieser Weg auch eine bereits exportierte
     * Bewegung an. Das ist gewollt und die einzige Stelle, an der das geschieht.</p>
     */
    public void deleteSecurityBooking(final Booking booking, final Runnable onDone) {
        executor.execute(() -> {
            deleteSecurityBookingNow(booking);
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /**
     * Dieselbe Arbeit auf dem rufenden Faden – für den Test, wie {@code applyMembershipNow}. Darf aus der
     * App heraus <b>nicht</b> gerufen werden: der Hauptfaden fasst die Datenbank nicht an.
     */
    void deleteSecurityBookingNow(final Booking booking) {
        db.runInTransaction(() -> {
            SecurityTx tx = findSecurityTx(booking);
            if (tx != null) {
                securityDao.deleteTxById(tx.id);
            }
            queueKmyDeleteIfNeeded(bookingDao.getById(booking.id));
            bookingDao.delete(booking.id);
        });
    }

    /** Vorsieben in SQL, entscheiden in {@link SecurityTxMatch} – auf dem Hintergrund-Faden zu rufen. */
    private SecurityTx findSecurityTx(Booking booking) {
        if (booking == null || !booking.isTransfer || booking.transferAccount == null) {
            return null;
        }
        java.util.Calendar day = java.util.Calendar.getInstance();
        day.setTimeInMillis(booking.createdAt);
        day.set(java.util.Calendar.HOUR_OF_DAY, 0);
        day.set(java.util.Calendar.MINUTE, 0);
        day.set(java.util.Calendar.SECOND, 0);
        day.set(java.util.Calendar.MILLISECOND, 0);
        long from = day.getTimeInMillis();
        day.add(java.util.Calendar.DAY_OF_MONTH, 1);
        return SecurityTxMatch.forBooking(booking, securityDao.getTxForBooking(
                booking.id, booking.transferAccount, booking.account, from, day.getTimeInMillis()));
    }

    /**
     * Zu welchen dieser Buchungen gehört eine Depot-Bewegung? Buchungsnummer →
     * {@code {Bewegungsart, Wertpapiername}}, die Art unübersetzt („buy"/„sell"/„dividend").
     *
     * <p>Für den Beleg-Export: Die Geldbuchung einer Bewegung geht ohne Empfänger in die KMyMoney-Datei
     * und hieße nach dem Reimport „ohne-Empfaenger". Art und Wertpapier stehen an der Bewegung — siehe
     * {@code ReceiptExportName#ofSecurity}.</p>
     */
    public void securityInfoForBookings(final List<Booking> bookings,
                                        final Callback<java.util.Map<Long, String[]>> callback) {
        executor.execute(() -> {
            java.util.Map<Long, String[]> out = new java.util.HashMap<>();
            if (bookings != null) {
                for (Booking b : bookings) {
                    // findSecurityTx steigt bei allem aus, was keine Umbuchung ist – der Regelfall.
                    SecurityTx tx = findSecurityTx(b);
                    if (tx != null) {
                        out.put(b.id, new String[]{tx.action, tx.securityName});
                    }
                }
            }
            final java.util.Map<Long, String[]> result = out;
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Beide Seiten einer Umbuchung (für die Ort-Vorbelegung im Editor). */
    public void getTransferGroup(final String group, final Callback<List<Booking>> callback) {
        executor.execute(() -> {
            final List<Booking> result = bookingDao.getByTransferGroup(group);
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    public void getBookingById(final long id, final Callback<Booking> callback) {
        executor.execute(() -> {
            final Booking result = bookingDao.getById(id);
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Buchungstyp der Wear-Sprach-Erfassung (per Knopf gewählt). */
    public static final String VOICE_TYPE_INCOME = "income";
    public static final String VOICE_TYPE_EXPENSE = "expense";
    public static final String VOICE_TYPE_TRANSFER = "transfer";

    /**
     * Legt aus einem gesprochenen Satz (z. B. „Frisör 20 Euro") synchron eine Buchung an – für die
     * Wear-Anbindung, deren {@code WearableListenerService} bereits auf einem Hintergrund-Thread läuft.
     * Nutzt denselben Parser ({@link de.spahr.ausgaben.voice.VoiceInput}) und dieselbe Vorlagen-/Split-Logik
     * wie die Sprach-Schnellerfassung am Phone. Der {@code type} (Einnahme/Ausgabe/Umbuchung) wird von der
     * Uhr per Knopf vorgegeben und erzwungen. Liefert {@code true}, wenn eine Buchung entstanden ist.
     */
    public boolean createVoiceBookingBlocking(String spokenText, String defaultAccount, String type,
                                              String coords) {
        return createVoiceBookingBlocking(spokenText, defaultAccount, "", type, coords);
    }

    /**
     * @param account gewähltes Konto (Widget/Uhr); bei Umbuchung das Von-Konto.
     * @param place   gewählter Ort des Kontos (leer = Standardort des Kontos).
     */
    public boolean createVoiceBookingBlocking(String spokenText, String account, String place, String type,
                                              String coords) {
        // Bei ausgeschaltetem GPS keinen Standort verwenden: reiner Betrag von der Uhr → leerer Empfänger,
        // keine GPS-Notiz. (Auf der Uhr bleibt die Betrag-only-Erfassung damit möglich.)
        de.spahr.ausgaben.settings.SettingsStore settings =
                new de.spahr.ausgaben.settings.SettingsStore(appContext);
        if (!settings.isGpsEnabled()) {
            coords = null;
        }
        de.spahr.ausgaben.voice.VoiceInput.Result parsed =
                de.spahr.ausgaben.voice.VoiceInput.parse(spokenText, settings.getCurrency());
        String term = parsed.payee == null ? "" : parsed.payee.trim();
        long amountCents = parsed.amountCents == null ? 0 : parsed.amountCents;
        if (term.isEmpty() && amountCents <= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        String def = account == null ? "" : account.trim();
        String selPlace = place == null ? "" : place.trim();

        // Auflösung: mit Empfänger normal; bei reinem Betrag über den aktuellen Standort (100 m).
        Booking[] resolvedBooking = new Booking[1];
        PayeeCorrection[] resolvedAlias = new PayeeCorrection[1];
        java.util.Set<String> closed = aliasResolver.closedAccounts();
        if (term.isEmpty()) {
            double[] ll = de.spahr.ausgaben.location.Geo.parse(coords);
            if (ll != null) {
                // Der Betrag siebt mit: bei mehreren Empfängern am selben Ort fällt heraus, wer
                // solche Beträge nachweislich nie hat (80 € sind keine Autowäsche). Das auf der Uhr
                // (graue Taste) bzw. im Widget gewählte Konto grenzt die Kandidaten zusätzlich ein.
                aliasResolver.resolveGps(ll[0], ll[1], closed, AccountScope.of(def), type, amountCents,
                        resolvedBooking, resolvedAlias);
            }
        } else {
            // Mit Empfänger: bei mehreren gleichnamigen Treffern den zur aktuellen Position nächsten wählen.
            // Die gewünschte Buchungsart (Knopf auf der Uhr) grenzt die Treffer im ersten Durchlauf ein.
            aliasResolver.resolve(term, de.spahr.ausgaben.location.Geo.parse(coords), closed, type,
                    resolvedBooking, resolvedAlias);
        }
        Booking template = resolvedBooking[0];
        PayeeCorrection alias = resolvedAlias[0];
        if (alias != null) {
            term = alias.corrected;
        }
        long amount = amountCents > 0 ? amountCents : (template != null ? template.amountCents : 0);

        // Umbuchung: steckt das gewählte Konto (Uhr/Widget) bereits als Von- ODER Nach-Konto in der
        // gefundenen Vorlage/im Alias, gelten beide Konten unverändert wie dort hinterlegt (die Auswahl
        // bestätigt nur, welche der beiden Umbuchungsseiten gemeint ist). Steckt es in keinem von beiden,
        // wird das Von-Konto durch die Uhr-/Widget-Auswahl ersetzt; das Nach-Konto bleibt wie gehabt aus
        // Alias/Vorlage.
        if (VOICE_TYPE_TRANSFER.equals(type)) {
            String from;
            String to;
            String payee = "";
            String note = "";
            if (alias != null) {
                String aliasFrom = alias.fromAccount == null ? "" : alias.fromAccount.trim();
                String aliasTo = alias.toAccount == null ? "" : alias.toAccount.trim();
                boolean selMatches = (!aliasFrom.isEmpty() && def.equalsIgnoreCase(aliasFrom))
                        || (!aliasTo.isEmpty() && def.equalsIgnoreCase(aliasTo));
                from = selMatches && !aliasFrom.isEmpty() ? aliasFrom : def;
                to = aliasTo.isEmpty() ? term : aliasTo;
                payee = alias.corrected;
            } else if (template != null && template.isTransfer) {
                String tplFrom = template.isIncome ? template.transferAccount : template.account;
                String tplTo = template.isIncome ? template.account : template.transferAccount;
                boolean selMatches = (!tplFrom.isEmpty() && def.equalsIgnoreCase(tplFrom))
                        || (!tplTo.isEmpty() && def.equalsIgnoreCase(tplTo));
                from = selMatches && !tplFrom.isEmpty() ? tplFrom : def;
                to = tplTo;
                payee = template.payee;
                note = template.note;
            } else {
                from = def;
                // Kein Umbuchungs-Treffer. Wurde im 2. Durchlauf dennoch eine (typfremde) Vorlage gefunden,
                // korrigiert deren Empfänger den Namen; sonst der gesprochene Name. Nach-Konto: ist das
                // Von-Konto das Standardkonto → leer (am Handy ergänzen), sonst das Standardkonto.
                payee = template != null ? template.payee : term;
                String phoneDefault = new de.spahr.ausgaben.settings.SettingsStore(appContext)
                        .getDefaultAccount().trim();
                to = from.equalsIgnoreCase(phoneDefault) ? "" : phoneDefault;
            }
            note = AliasResolver.appendGps(note, coords);
            de.spahr.ausgaben.settings.PlacesStore ps =
                    new de.spahr.ausgaben.settings.PlacesStore(appContext);
            String fromPlace = isRealPlace(selPlace) ? selPlace : ps.getDefaultPlace(from);
            String toPlace = alias != null ? alias.toPlace
                    : (to.isEmpty() ? "" : ps.getDefaultPlace(to));
            // Sprach-/Widget-Erfassung: Stichwörter gibt es erst in der Bearbeitungsmaske.
            insertTransferPair(from, to, amount, payee, note, "", now, null,
                    UUID.randomUUID().toString(), fromPlace, toPlace);
            return true;
        }

        // Einnahme/Ausgabe: Richtung per Knopf erzwungen; Konto/Ort sind die Uhr-/Widget-Auswahl und
        // stehen fest (nie Alias/Vorlage) – nur Kategorie/Empfänger/Notiz kommen aus Alias bzw. Vorlage.
        boolean income = VOICE_TYPE_INCOME.equals(type);
        // Vorlage vom exakt passenden Typ (Einnahme/Ausgabe wie angefordert): dann auch Kategorie/Notiz
        // übernehmen. Eine im 2. Durchlauf gefundene typfremde Vorlage korrigiert nur den Empfänger.
        boolean templateSameType = template != null && !template.isTransfer && template.isIncome == income;
        Booking b = new Booking();
        b.amountCents = amount;
        b.createdAt = now;
        b.exported = false;
        b.isIncome = income;
        b.account = def;
        if (alias != null) {
            b.payee = alias.corrected;
            b.category = income ? AliasResolver.firstNonEmpty(alias.catIncome1, alias.catIncome2)
                    : AliasResolver.firstNonEmpty(alias.catExpense1, alias.catExpense2);
            b.note = "";
        } else if (template != null) {
            b.payee = template.payee;
            b.category = templateSameType ? template.category : "";
            b.note = templateSameType ? template.note : "";
        } else {
            b.payee = term;
            b.category = "";
            b.note = "";
        }
        b.note = AliasResolver.appendGps(b.note, coords);
        // Ort ist die Uhr-/Widget-Auswahl (nie Alias/Vorlage); ohne Auswahl der Standardort des Kontos.
        String resolvedPlace = isRealPlace(selPlace) ? selPlace
                : new de.spahr.ausgaben.settings.PlacesStore(appContext).getDefaultPlace(b.account);
        b.place = isRealPlace(resolvedPlace) ? resolvedPlace.trim() : "";
        b.placeManaged = true;
        if (!b.payee.trim().isEmpty()) {
            payeeDao.insertIfAbsent(new Payee(b.payee));
        }
        if (!b.account.trim().isEmpty()) {
            accountDao.insertIfAbsent(new Account(b.account));
        }
        long id = bookingDao.insert(b);
        insertBookingMovement(b);

        // Splitbuchungs-Vorlage: Teilbeträge proportional auf den neuen Betrag skalieren (Rest in letzte
        // Zeile). Nur von einer typgleichen Vorlage übernehmen (eine typfremde korrigiert nur den Empfänger).
        if (templateSameType && template.amountCents != 0) {
            List<BookingSplit> tmplSplits = bookingDao.getSplits(template.id);
            if (tmplSplits != null && tmplSplits.size() >= 2) {
                long assigned = 0;
                for (int i = 0; i < tmplSplits.size(); i++) {
                    BookingSplit s = tmplSplits.get(i);
                    long part;
                    if (i < tmplSplits.size() - 1) {
                        part = Math.round((double) s.amountCents * amount / template.amountCents);
                        assigned += part;
                    } else {
                        part = amount - assigned;
                    }
                    bookingDao.insertSplit(new BookingSplit(id, s.category, part));
                }
            }
        }
        return true;
    }

    // Aliase + Sprach-/Standort-Auflösung → AliasResolver.

    public void saveAlias(PayeeCorrection alias) {
        aliasResolver.saveAlias(alias);
    }

    public void saveAlias(PayeeCorrection alias, boolean mergeGps) {
        aliasResolver.saveAlias(alias, mergeGps);
    }

    public void getAllAliases(Callback<List<PayeeCorrection>> callback) {
        aliasResolver.getAllAliases(callback);
    }

    /** Die nächstgelegenen Empfänger (höchstens {@code NearbyPayees.LIMIT}) für den Buchungs-Editor. */
    public void getNearbyPayees(double lat, double lon, Callback<List<String>> callback) {
        aliasResolver.getNearbyPayees(lat, lon, callback);
    }

    /** Die bisherigen Beträge dieses Empfängers, sortiert – für den Betragsband-Regler im Alias. */
    public void getPayeeAmounts(String payee, String type, Callback<long[]> callback) {
        aliasResolver.getPayeeAmounts(payee, type, callback);
    }

    /** Die Kategorien dieses Empfängers (höchstens {@code PayeeCategories.LIMIT}) für den Editor. */
    public void getPayeeCategories(String payee, boolean income, Callback<List<String>> callback) {
        aliasResolver.getPayeeCategories(payee, income, callback);
    }

    /**
     * Die Namen aller Wertpapiere aus den importierten Depots. Eine Buchung, deren Gegenkonto so heißt,
     * ist ein Wertpapierkauf oder -verkauf: an ihr darf die App nur Notiz, Stichwörter und Beleg ändern.
     */
    public void getSecurityNames(final Callback<List<String>> callback) {
        executor.execute(() -> {
            final List<String> result = securityDao.getSecurityNames();
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Vorspann und Vorbelegung der Stichwörter zu diesem Empfänger (siehe {@link PayeeTags}). */
    public void getPayeeTags(String payee, Callback<PayeeTagSuggestion> callback) {
        aliasResolver.getPayeeTags(payee, callback);
    }

    public void getAlias(long id, Callback<PayeeCorrection> callback) {
        aliasResolver.getAlias(id, callback);
    }

    public void deleteAlias(long id, Runnable onDone) {
        aliasResolver.deleteAlias(id, onDone);
    }

    /** Ergebnis der Sprach-Empfängersuche: Vorlage-Buchung und/oder Alias + aufzulösender Empfänger. */
    public static final class VoiceResolution {
        /** Passende Vorlage-Buchung oder {@code null}. */
        public final Booking booking;
        /** Passender Alias oder {@code null}. */
        public final PayeeCorrection alias;
        /** Aufzulösender Empfänger – bereits korrigiert, falls ein Alias greift. */
        public final String payee;

        public VoiceResolution(Booking booking, PayeeCorrection alias, String payee) {
            this.booking = booking;
            this.alias = alias;
            this.payee = payee;
        }
    }

    public void resolveVoice(String term, String coords, Callback<VoiceResolution> callback) {
        aliasResolver.resolveVoice(term, coords, callback);
    }

    /**
     * Die zum Betrag passenden Empfänger im 100-m-Umkreis, der beste zuerst (Ziffernmaske).
     *
     * @param scope die angezeigten Konten ({@link AccountScope}); leer = alle
     */
    public void resolveNearby(String coords, long amountCents, String type, java.util.Set<String> scope,
                              Callback<List<VoiceResolution>> callback) {
        aliasResolver.resolveNearby(coords, amountCents, type, scope, callback);
    }

    /** Der vom Betrag eindeutig belegte Empfänger im 100-m-Umkreis, sonst {@code null} (Editor). */
    public void suggestPayeeByAmount(double lat, double lon, long amountCents, String type,
                                     Callback<String> callback) {
        aliasResolver.suggestPayeeByAmount(lat, lon, amountCents, type, callback);
    }

    /**
     * Fügt importierte Buchungen ein (jeweils mit gesetztem exported-Flag) und ergänzt
     * Konto/Empfänger als Auswahlwerte. Liefert die Anzahl eingefügter Buchungen.
     */
    public void importBookings(final List<Booking> bookings, final Callback<Integer> callback) {
        executor.execute(() -> {
            for (Booking b : bookings) {
                insertImported(b);
            }
            final int count = bookings.size();
            mainHandler.post(() -> callback.onResult(count));
        });
    }

    /**
     * Ersetzt den Import eines Kontos: löscht zuerst alle bereits exportierten Buchungen dieses Kontos
     * und fügt anschließend die importierten Buchungen ein. Liefert die Anzahl eingefügter Buchungen.
     */
    public void replaceImport(final String account, final List<Booking> bookings,
                              final Callback<Integer> callback) {
        executor.execute(() -> {
            if (account != null && !account.trim().isEmpty()) {
                bookingDao.deleteSplitsForExportedAccount(account.trim());
                bookingDao.deleteExportedByAccount(account.trim());
                analysisExtraDao.deleteByAccount(account.trim());
            }
            for (Booking b : bookings) {
                insertImported(b);
            }
            final int count = bookings.size();
            mainHandler.post(() -> callback.onResult(count));
        });
    }

    /** Fügt eine importierte Buchung samt ihren Kategorie-Teilen ein (läuft auf dem Executor-Thread). */
    private void insertImported(Booking b) {
        if (!b.payee.trim().isEmpty()) {
            payeeDao.insertIfAbsent(new Payee(b.payee));
        }
        accountDao.insertIfAbsent(new Account(b.account));
        long id = bookingDao.insert(b);
        if (b.parts != null) {
            for (BookingSplit p : b.parts) {
                p.bookingId = id;
                bookingDao.insertSplit(p);
            }
        }
        if (b.analysisExtras != null) {
            for (AnalysisExtra ex : b.analysisExtras) {
                analysisExtraDao.insert(ex);
            }
        }
    }

    /**
     * Ersetzt den Import mehrerer Konten in einem Durchgang: je Konto zuerst die bereits exportierten
     * Buchungen löschen, dann die importierten einfügen. Liefert {@code [Konten, Buchungen]}.
     */
    public void replaceImportAccounts(final java.util.LinkedHashMap<String, List<Booking>> byAccount,
                                      final Callback<int[]> callback) {
        replaceImportAccounts(byAccount, null, callback);
    }

    /**
     * Wie oben, meldet aber den Fortschritt (geschriebene von insgesamt zu schreibenden Buchungen).
     *
     * <p>Der ganze Block läuft in <b>einer</b> Transaktion: vorher bekam jede einzelne Buchung ihre eigene
     * (inkl. fsync) – bei ~10.000 Buchungen war das der Hauptgrund für die lange Pause in der Anzeige.</p>
     */
    public void replaceImportAccounts(final java.util.LinkedHashMap<String, List<Booking>> byAccount,
                                      final de.spahr.ausgaben.util.ProgressListener listener,
                                      final Callback<int[]> callback) {
        executor.execute(() -> {
            int total = 0;
            for (List<Booking> l : byAccount.values()) {
                total += l.size();
            }
            final int fTotal = total;
            final int[] counters = new int[2];   // [0] = Konten, [1] = Buchungen
            db.runInTransaction(() -> {
                for (java.util.Map.Entry<String, List<Booking>> e : byAccount.entrySet()) {
                    String account = e.getKey();
                    if (account != null && !account.trim().isEmpty()) {
                        bookingDao.deleteSplitsForExportedAccount(account.trim());
                        bookingDao.deleteExportedByAccount(account.trim());
                        analysisExtraDao.deleteByAccount(account.trim());
                        accountDao.insertIfAbsent(new Account(account.trim()));
                    }
                    for (Booking b : e.getValue()) {
                        insertImported(b);
                        counters[1]++;
                        if (listener != null) {
                            listener.onProgress(counters[1], fTotal);
                        }
                    }
                    counters[0]++;
                }
            });
            final int fa = counters[0];
            final int fi = counters[1];
            mainHandler.post(() -> callback.onResult(new int[]{fa, fi}));
        });
    }

    /** Löscht ein komplettes Konto: alle Buchungen dieses Kontos und den Konto-Eintrag selbst. */
    public void deleteAccount(final String account, final Runnable onDone) {
        executor.execute(() -> {
            if (account != null && !account.trim().isEmpty()) {
                bookingDao.deleteSplitsForAccount(account.trim());
                bookingDao.deleteAllByAccount(account.trim());
                analysisExtraDao.deleteByAccount(account.trim());
                placeEntryDao.deleteByAccount(account.trim());
                accountDao.deleteByName(account.trim());
            }
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /** Löscht alle Buchungen sowie die Konto-/Empfänger-Vorschlagslisten (Einstellungen bleiben). */
    /**
     * Setzt <b>alle</b> Datenbank-Tabellen zurück (Auslieferungszustand): Buchungen, Splits, Konten,
     * Kontengruppen, Payees, Stichwörter, Alias-Korrekturen, Orte-Journal, Übersetzungen, Depot
     * (Wertpapiere/Transaktionen), Budget, Kategorietypen, geplante Buchungen und Merker. Einstellungen,
     * Orte-Konfiguration und Belegdateien liegen außerhalb der DB und werden vom Aufrufer geleert.
     */
    public void resetAllData(final Runnable onDone) {
        executor.execute(() -> {
            db.clearAllTables();
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    public void getAllBookings(final Callback<List<Booking>> callback) {
        executor.execute(() -> {
            final List<Booking> result = bookingDao.getAllBookings();
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Die wählbaren Stichwörter – leer, solange keine {@code .kmy}-Datei gelesen wurde. */
    public void getTagNames(final Callback<List<String>> callback) {
        executor.execute(() -> {
            final List<String> result = tagDao.getAllNames();
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /**
     * Übernimmt die Stichwortliste aus der {@code .kmy}-Datei. Ersetzt statt zu ergänzen, damit ein
     * dort gelöschtes Stichwort auch hier verschwindet; eine leere Liste lässt den Bestand stehen
     * (die Datei hatte dann schlicht keinen Stichwort-Block).
     */
    public void replaceTags(final List<String> names) {
        if (names == null || names.isEmpty()) {
            return;
        }
        executor.execute(() -> {
            tagDao.deleteAll();
            for (String name : names) {
                String clean = BookingTags.sanitize(name);
                if (!clean.isEmpty()) {
                    tagDao.insertIfAbsent(new Tag(clean));
                }
            }
        });
    }

    public void getPayeeNames(final Callback<List<String>> callback) {
        executor.execute(() -> {
            final List<String> result = payeeDao.getAllNames();
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Nur aktive Konten – geschlossene Konten sind nirgends auswählbar. */
    public void getAccountNames(final Callback<List<String>> callback) {
        executor.execute(() -> {
            // Ohne die Trägerzeilen der Depots: auf ein Depot lässt sich nicht buchen.
            final List<String> result = accountDao.getBookableNames();
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Alle Konten (auch geschlossene) – zum Ausblenden bereits importierter Konten im Import-Dialog. */
    public void getAllAccountNames(final Callback<List<String>> callback) {
        executor.execute(() -> {
            final List<String> result = accountDao.getAllNames();
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /**
     * Kontenart je Kontoname – für das Symbol vor jedem Eintrag der Auswahllisten. Der Name ist
     * kleingeschrieben hinterlegt, weil die Felder mit frei getipptem Text arbeiten.
     */
    public void getAccountKinds(final Callback<java.util.Map<String, Integer>> callback) {
        executor.execute(() -> {
            final java.util.Map<String, Integer> result = new java.util.HashMap<>();
            for (Account account : accountDao.getAll()) {
                if (account.name != null) {
                    result.put(account.name.toLowerCase(java.util.Locale.ROOT),
                            AccountKind.of(account.kmyType));
                }
            }
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Alle Konten mit Status (aktiv/geschlossen) – für die Konto-Verwaltung. */
    public void getAllAccountsWithStatus(final Callback<List<Account>> callback) {
        executor.execute(() -> {
            final List<Account> result = accountDao.getAllOrdered();
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Konto schließen (inaktiv) oder wieder öffnen. */
    public void setAccountClosed(final String name, final boolean closed, final Runnable onDone) {
        executor.execute(() -> {
            if (name != null && !name.trim().isEmpty()) {
                accountDao.setClosed(name.trim(), closed);
            }
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /** Saldo (Einnahmen − Ausgaben) je Konto; fehlende Konten = 0. Für die Mehrfach-Konto-Verwaltung. */
    public void getAllAccountBalances(final Callback<Map<String, Long>> callback) {
        executor.execute(() -> {
            final Map<String, Long> result = new HashMap<>();
            for (AccountBalance ab : bookingDao.getAllAccountBalances()) {
                if (ab.name != null) {
                    result.put(ab.name, ab.balance);
                }
            }
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Schließt/öffnet mehrere Konten in einem Rutsch; {@code onDone} läuft einmal am Ende (Main-Thread). */
    public void setAccountsClosed(final List<String> names, final boolean closed, final Runnable onDone) {
        executor.execute(() -> {
            if (names != null) {
                for (String name : names) {
                    if (name != null && !name.trim().isEmpty()) {
                        accountDao.setClosed(name.trim(), closed);
                    }
                }
            }
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /** Löscht mehrere Konten samt Buchungen in einem Rutsch; {@code onDone} läuft einmal am Ende. */
    public void deleteAccounts(final List<String> accounts, final Runnable onDone) {
        deleteAccountsAndDepots(accounts, null, onDone);
    }

    /**
     * Löscht Konten und/oder Depots in einem Rutsch. Ein Depot hat keine eigenen Buchungen – gelöscht
     * werden seine Wertpapiere samt Bewegungen/Kursen ({@link SecurityDao}) und die Trägerzeile in der
     * Konto-Tabelle. {@code onDone} läuft einmal am Ende.
     *
     * <p>Der ganze Block läuft in <b>einer</b> Transaktion: er löscht über fünf Tabellen hinweg, und ein
     * Abbruch in der Mitte hinterließe ein halb gelöschtes Depot – Wertpapiere weg, Bewegungen noch da.</p>
     */
    public void deleteAccountsAndDepots(final List<String> accounts, final List<String> depots,
                                        final Runnable onDone) {
        executor.execute(() -> {
            db.runInTransaction(() -> {
                if (accounts != null) {
                    for (String account : accounts) {
                        if (account != null && !account.trim().isEmpty()) {
                            bookingDao.deleteSplitsForAccount(account.trim());
                            bookingDao.deleteAllByAccount(account.trim());
                            placeEntryDao.deleteByAccount(account.trim());
                            accountDao.deleteByName(account.trim());
                        }
                    }
                }
                if (depots != null) {
                    for (String depot : depots) {
                        if (depot != null && !depot.trim().isEmpty()) {
                            String name = depot.trim();
                            // Hier bewusst die bedingungslosen Fassungen: anders als beim Reimport
                            // kommt das Depot nicht wieder. Eine geschonte pending-Bewegung fände der
                            // Export weiterhin, ohne dass es Depot oder Wertpapier dazu noch gäbe.
                            securityDao.deleteAllSplitsOf(name);
                            securityDao.deleteAllTx(name);
                            securityDao.deleteValueOverrides(name);
                            securityDao.deleteSecurities(name);
                            securityDao.deletePrices(name);
                            accountDao.deleteByName(name);
                        }
                    }
                }
                // Die Zuordnungen fallen mit dem Konto weg; dabei leer gewordene Gruppen mit entsorgen.
                groupRepo.deleteEmptyCustomGroups();
            });
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    public void getCategoryNames(final Callback<List<String>> callback) {
        executor.execute(() -> {
            final List<String> result = bookingDao.getDistinctCategories();
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Kategorien getrennt nach Ausgabe/Einnahme (eine Kategorie darf in beiden vorkommen). */
    public void getCategoriesGrouped(final Callback<CategoryGroups> callback) {
        executor.execute(() -> {
            final CategoryGroups g = new CategoryGroups(
                    bookingDao.getExpenseCategories(), bookingDao.getIncomeCategories());
            mainHandler.post(() -> callback.onResult(g));
        });
    }

    /** Kategorien nach Buchungsart getrennt. */
    public static final class CategoryGroups {
        public final List<String> expense;
        public final List<String> income;
        public CategoryGroups(List<String> expense, List<String> income) {
            this.expense = expense;
            this.income = income;
        }
    }

    // ---- Budgetplanung ----

    /** Gespeichertes Budget eines Jahres (Soll-Werte je Kategorie + Herkunft). */
    public static final class YearBudget {
        /** {@code "kmy"}/{@code "internal"}; {@code null} = keins vorhanden. */
        public final String source;
        public final List<Budget> lines;
        public YearBudget(String source, List<Budget> lines) {
            this.source = source;
            this.lines = lines;
        }
    }

    // Budgetplanung → BudgetRepository.

    public void getCategoryActuals(long fromMs, long toMs, Callback<List<CategorySum>> callback) {
        budgetRepo.getCategoryActuals(fromMs, toMs, callback);
    }

    /** Kategorie-Pfad → Typ ({@code true} = Einnahme) aus der Datei; verlässliche Budget-Einordnung. */
    public void getCategoryTypes(Callback<Map<String, Boolean>> callback) {
        budgetRepo.getCategoryTypes(callback);
    }

    /** Historische Zahlungs-Verteilung je Kategorie (für die verlaufsbasierte Budget-Balkenfarbe). */
    public void getCategoryTiming(boolean monthView, Callback<List<CategoryBucket>> callback) {
        budgetRepo.getCategoryTiming(monthView, callback);
    }

    public void getBudget(int year, Callback<YearBudget> callback) {
        budgetRepo.getBudget(year, callback);
    }

    public void saveBudgetLine(int year, String category, boolean isIncome, long amountCents,
                               Runnable onDone) {
        budgetRepo.saveBudgetLine(year, category, isIncome, amountCents, onDone);
    }

    public void replaceBudget(int year, String source, List<Budget> lines, Runnable onDone) {
        budgetRepo.replaceBudget(year, source, lines, onDone);
    }

    public void computeBudgetFromHistory(int year, Runnable onDone) {
        budgetRepo.computeBudgetFromHistory(year, onDone);
    }

    // ---- Depot (Wertpapiere) ----

    /** Aktueller Bestand eines Wertpapiers: Name/Symbol, Stückzahl, Kurs, Wert (Cent). */
    public static final class DepotHolding {
        public final String name;
        public final String symbol;
        public final String kmyId;
        public final double shares;
        public final double price;
        public final long valueCents;

        DepotHolding(String name, String symbol, String kmyId, double shares, double price,
                     long valueCents) {
            this.name = name;
            this.symbol = symbol;
            this.kmyId = kmyId;
            this.shares = shares;
            this.price = price;
            this.valueCents = valueCents;
        }
    }

    /** Kennzahlen: Depotwert, Käufe/Verkäufe/Dividenden, Nettoeinsatz (Käufe − Verkäufe − Dividenden),
     *  Gewinn/Verlust (Cent + Prozent). */
    public static final class DepotMetrics {
        public final long valueCents;
        public final long buyCents;
        public final long sellCents;
        public final long dividendCents;
        public final long netInvestedCents;
        public final long gainCents;
        public final double gainPct;
        DepotMetrics(long value, long buy, long sell, long dividend) {
            this.valueCents = value;
            this.buyCents = buy;
            this.sellCents = sell;
            this.dividendCents = dividend;
            this.netInvestedCents = buy - sell - dividend;
            this.gainCents = value - netInvestedCents;
            this.gainPct = buy != 0
                    ? (double) gainCents / buy * 100.0 : 0.0;
        }
    }

    // Depot → DepotRepository.

    public void replaceDepotImport(String depot, List<Security> securities,
                                   List<SecurityTx> transactions, List<SecurityPrice> prices,
                                   Runnable onDone) {
        replaceDepotImport(depot, securities, transactions, prices, null, onDone);
    }

    /** Wie oben, meldet aber den Fortschritt des Schreibens (Wertpapiere, Bewegungen, Kurse). */
    public void replaceDepotImport(String depot, List<Security> securities,
                                   List<SecurityTx> transactions, List<SecurityPrice> prices,
                                   de.spahr.ausgaben.util.ProgressListener listener,
                                   Runnable onDone) {
        ensureDepotAccount(depot);
        depotRepo.replaceDepotImport(depot, securities, transactions, prices, listener, onDone);
    }

    // ---- In der App erfasste Depot-Bewegungen ----

    /** Legt eine erfasste Bewegung samt Geldbuchung an (vorgemerkt für den nächsten Export). */
    public void saveManualSecurityTx(SecurityTx tx, Booking booking, Runnable onDone) {
        depotRepo.saveManualTx(tx, booking, onDone);
    }

    /**
     * Legt einen ganzen Stapel erfasster Bewegungen an — alle oder keine (Erkennungsliste).
     *
     * @param onError läuft auf dem Bedienfaden, wenn die Transaktion gescheitert ist; gebucht wurde
     *                dann nichts
     */
    public void saveManualSecurityTxBatch(List<SecurityTx> txs, List<Booking> bookings,
                                          Runnable onDone, Runnable onError) {
        depotRepo.saveManualTxBatch(txs, bookings, onDone, onError);
    }

    /** Ändert eine noch nicht exportierte Bewegung samt Geldbuchung. */
    public void updateManualSecurityTx(SecurityTx tx, Booking booking, Runnable onDone) {
        depotRepo.updateManualTx(tx, booking, onDone);
    }

    /** Entfernt eine noch nicht exportierte Bewegung samt Geldbuchung. */
    public void deleteManualSecurityTx(long txId, Runnable onDone) {
        depotRepo.deleteManualTx(txId, onDone);
    }

    /** Eine einzelne Bewegung für die Erfassungsmaske. */
    public void getSecurityTx(long id, Callback<SecurityTx> callback) {
        depotRepo.getSecurityTx(id, callback);
    }

    /** Alle Wertpapiere aller Depots, nach Namen sortiert. */
    public void getAllSecurities(Callback<List<Security>> callback) {
        depotRepo.getAllSecurities(callback);
    }

    /** Wertpapier zu einer ISIN – Zuordnung einer eingelesenen Bankabrechnung. */
    public void getSecurityByIsin(String isin, Callback<Security> callback) {
        depotRepo.getSecurityByIsin(isin, callback);
    }

    /** Schon verwendete Kategorien eines Wertpapiers: Liste 0 = Gebühr/Steuer, Liste 1 = Ertrag. */
    public void getSecurityUsedCategories(String depot, String kmyId,
                                          Callback<java.util.List<java.util.List<String>>> callback) {
        depotRepo.getUsedCategories(depot, kmyId, callback);
    }

    /** Vorbelegung der Erfassungsmaske aus der jüngsten Bewegung derselben Art. */
    public void getSecurityTxDefaults(String depot, String kmyId, String action,
                                      Callback<SecurityTx> callback) {
        depotRepo.getTxDefaults(depot, kmyId, action, callback);
    }

    /**
     * Dieselbe Vorbelegung für mehrere Einträge auf einmal; je Eintrag {@code {Depot, Wertpapier-Id,
     * Aktion}}, {@code null} für einen Eintrag, der keine braucht.
     */
    public void getSecurityTxDefaultsBatch(List<String[]> keys, Callback<List<SecurityTx>> callback) {
        depotRepo.getTxDefaultsBatch(keys, callback);
    }

    /**
     * Steht jede dieser Bewegungen schon im Depot? Für den Hinweis auf eine doppelt eingelesene
     * Abrechnung; {@code exceptId} nimmt die gerade bearbeitete Bewegung aus (0 = keine Ausnahme).
     */
    public void findExistingSecurityTx(List<SecurityTx> candidates, long exceptId,
                                       Callback<boolean[]> callback) {
        depotRepo.findExisting(candidates, exceptId, callback);
    }

    /**
     * Legt die Trägerzeile eines Depots in der Konto-Tabelle an. Sie trägt nur Name, Sortierplatz und
     * Gruppen; Wertpapiere und Bewertung bleiben in den {@code security}-Tabellen.
     */
    public void ensureDepotAccount(final String depot) {
        ensureDepotAccounts(java.util.Collections.singletonList(depot), null);
    }

    /**
     * Gleicht die Trägerzeilen mit den tatsächlich vorhandenen Depots ab: fehlende werden angelegt,
     * gleichnamige vorhandene Konten als Depot gekennzeichnet.
     *
     * <p>Der Abgleich läuft bei jedem Start und nicht nur einmal in der Migration – ein Depot kann auch
     * aus einer Sicherung zurückkommen, und ohne Trägerzeile fiele es aus Schublade und Verwaltung
     * heraus.</p>
     *
     * @param onDone läuft im Main-Thread, sobald der Abgleich steht; {@code null} = kein Rückruf
     */
    public void ensureDepotAccounts(final List<String> depots, final Runnable onDone) {
        executor.execute(() -> {
            if (depots != null) {
                for (String depot : depots) {
                    if (depot == null || depot.trim().isEmpty()) {
                        continue;
                    }
                    String name = depot.trim();
                    accountDao.insertIfAbsent(new Account(name));
                    accountDao.setType(name, Account.KMY_TYPE_DEPOT);
                    // Ein Depot mit Wertpapieren ist in Gebrauch: geschlossen gehörte es weder in die
                    // Schublade noch in die Bestände, und sein Wert fiele aus der Summe. Dieselbe Regel
                    // wie bei Konten, die durch einen Import wieder einen Saldo bekommen.
                    accountDao.setClosed(name, false);
                }
            }
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    public void getDepots(Callback<List<String>> callback) {
        depotRepo.getDepots(callback);
    }

    /** Depotübergreifende Bewertung (Zeitreihe) für die Vermögensgrafik. */
    public void getDepotValuation(Callback<DepotValuation> callback) {
        depotRepo.getDepotValuation(callback);
    }

    public void getDepotHoldings(String depot, Callback<List<DepotHolding>> callback) {
        depotRepo.getDepotHoldings(depot, callback);
    }

    public void getSecurityTransactions(String depot, String kmyId,
                                        Callback<List<SecurityTx>> callback) {
        depotRepo.getSecurityTransactions(depot, kmyId, callback);
    }

    public void getDepotMetrics(String depot, Callback<DepotMetrics> callback) {
        depotRepo.getDepotMetrics(depot, callback);
    }

    public void getSecurityMetrics(String depot, String kmyId, Callback<DepotMetrics> callback) {
        depotRepo.getSecurityMetrics(depot, kmyId, callback);
    }

    /** Setzt den manuellen Geldwert einer Ein-/Ausbuchung (KMyMoney liefert dafür nie einen Wert). */
    public void saveSecurityTxValue(String depot, String kmyId, long date, String action, double shares,
                                    long amountCents, Runnable onDone) {
        depotRepo.saveSecurityTxValue(depot, kmyId, date, action, shares, amountCents, onDone);
    }

    /** Entfernt einen zuvor manuell gesetzten Wert einer Ein-/Ausbuchung wieder. */
    public void clearSecurityTxValue(String depot, String kmyId, long date, String action, double shares,
                                     Runnable onDone) {
        depotRepo.clearSecurityTxValue(depot, kmyId, date, action, shares, onDone);
    }

    /** Frühester Bewegungszeitpunkt eines Depots (ms; 0 wenn leer) – Untergrenze des Zeitraumfilters. */
    public void getDepotFirstTx(String depot, Callback<Long> callback) {
        depotRepo.getDepotFirstTx(depot, callback);
    }

    /**
     * Zeitraumbezogene Auswertungszeilen je Wertpapier (aktueller Wert / Netto-Einzahlungen / Dividenden im
     * Zeitraum). Bei {@code wholePeriod} kommt der aktuelle Wert direkt aus dem Depotstand.
     */
    public void getDepotChartRows(String depot, long fromMs, long toMs, boolean wholePeriod,
                                  Callback<List<DepotChartRow>> callback) {
        depotRepo.getDepotChartRows(depot, fromMs, toMs, wholePeriod, callback);
    }

    /** Eine Auswertungszeile der Depot-Kreisgrafik: je Wertpapier alle drei Kennzahlen des Zeitraums. */
    public static final class DepotChartRow {
        public final String name;
        /** Heutiger Wert der im Zeitraum aufgebauten Position (bzw. Depotstand bei vollem Zeitraum). */
        public final long currentValueCents;
        /** Netto-Einzahlungen im Zeitraum = Käufe − Verkäufe − Dividenden. */
        public final long netDepositsCents;
        /** Summe der Dividenden im Zeitraum (brutto/netto laut Einstellung). */
        public final long dividendCents;
        /** Einstandspreis im Zeitraum = Summe der Käufe (Nenner für die Rendite). */
        public final long investedCents;
        /** Aktueller Netto-Bestand = 0 (komplett verkauft), unabhängig vom Zeitraumfilter. */
        public final boolean fullySold;

        public DepotChartRow(String name, long currentValueCents, long netDepositsCents,
                             long dividendCents, long investedCents, boolean fullySold) {
            this.name = name;
            this.currentValueCents = currentValueCents;
            this.netDepositsCents = netDepositsCents;
            this.dividendCents = dividendCents;
            this.investedCents = investedCents;
            this.fullySold = fullySold;
        }
    }

    /** Stellt sicher, dass ein Kontoname als Auswahlwert existiert (z. B. Standardkonto). */
    public void ensureAccount(final String name) {
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        executor.execute(() -> accountDao.insertIfAbsent(new Account(name.trim())));
    }

    // ---- Bargeld-Orte ----

    private static final String NO_PLACE = de.spahr.ausgaben.settings.PlacesStore.NO_PLACE;

    private boolean isRealPlace(String place) {
        return place != null && !place.trim().isEmpty() && !place.equals(NO_PLACE);
    }

    /**
     * Speichert eine neue (in der App angelegte) Buchung und legt – falls ein echter Ort gewählt ist
     * (Standardort zählt dazu) – eine passende Ort-Bewegung im Journal an. {@code place} leer/„ohne Ort"
     * → keine Bewegung, die Buchung fällt in den Rest „ohne Ort". Die Buchung merkt sich ihren Ort-Link
     * ({@code place_managed}), damit spätere Änderungen als Ausgleichs-Bewegung nachgezogen werden.
     */
    public void saveBookingWithPlace(final Booking booking, final String place, final Runnable onDone) {
        booking.place = isRealPlace(place) ? place.trim() : "";
        booking.placeManaged = true;
        executor.execute(() -> {
            payeeDao.insertIfAbsent(new Payee(booking.payee));
            accountDao.insertIfAbsent(new Account(booking.account));
            bookingDao.insert(booking);
            insertBookingMovement(booking);
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /** Vorzeichenbehafteter Betrag einer Buchung (Einnahme = +, Ausgabe = −). */
    private static long signed(Booking b) {
        return b.isIncome ? b.amountCents : -b.amountCents;
    }

    /**
     * Legt für eine neu angelegte, ort-verknüpfte Buchung die anfängliche Ort-Bewegung an (nur wenn ein
     * echter Ort hinterlegt ist). Datum = Buchungsdatum. Läuft auf dem Executor-Thread.
     */
    private void insertBookingMovement(Booking b) {
        if (b.placeManaged && isRealPlace(b.place)) {
            String note = b.payee == null || b.payee.trim().isEmpty()
                    ? "Buchung" : "Buchung: " + b.payee.trim();
            placeEntryDao.insert(new PlaceEntry(b.account, b.place, signed(b), b.createdAt, "booking", note));
        }
    }

    /** Ordnet noch nicht zugeordnete Ort-Bewegungen einmalig dem Standardkonto zu (Migration v4→v5). */
    public void migratePlaceEntryAccounts(final String defaultAccount) {
        if (defaultAccount == null || defaultAccount.trim().isEmpty()) {
            return;
        }
        executor.execute(() -> placeEntryDao.assignEmptyAccount(defaultAccount.trim()));
    }

    public void getTotalBalance(final Callback<Long> callback) {
        executor.execute(() -> {
            final long result = bookingDao.getTotalBalance();
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Saldo eines einzelnen Kontos (für „Orte nur fürs Standardkonto"). */
    public void getAccountBalance(final String account, final Callback<Long> callback) {
        executor.execute(() -> {
            final long result = bookingDao.getBalanceByAccount(account == null ? "" : account);
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Kontosaldo bis einschließlich der angegebenen Buchung (für die „vorher/nachher"-Anzeige). */
    public void getAccountBalanceUpTo(final String account, final long createdAt, final long id,
                                      final Callback<Long> callback) {
        executor.execute(() -> {
            final long result = bookingDao.getBalanceUpTo(account == null ? "" : account, createdAt, id);
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Ort-Salden eines Kontos aus dem Journal (Σ Bewegungen je Ort). „ohne Ort" ist der berechnete Rest. */
    public void getPlaceBalances(final String account, final Callback<List<PlaceBalance>> callback) {
        executor.execute(() -> {
            final List<PlaceBalance> result = placeEntryDao.getBalances(account == null ? "" : account);
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Ort-Salden je (Konto, Ort) über alle Konten aus dem Journal (für die Bestände-Gruppenliste). */
    public void getAllPlaceBalances(final Callback<List<PlaceBalance>> callback) {
        executor.execute(() -> {
            final List<PlaceBalance> result = placeEntryDao.getAllBalances();
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    public void getPlaceHistory(final String account, final String place,
                                final Callback<List<PlaceEntry>> callback) {
        executor.execute(() -> {
            final List<PlaceEntry> result = placeEntryDao.getByPlace(
                    account == null ? "" : account, place);
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    public void getAllPlaceEntries(final Callback<List<PlaceEntry>> callback) {
        executor.execute(() -> {
            final List<PlaceEntry> result = placeEntryDao.getAll();
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Umbuchen zwischen Orten desselben Kontos (keine Buchung). */
    public void saveTransfer(final String account, final String from, final String to,
                             final long cents, final Runnable onDone) {
        executor.execute(() -> {
            long now = System.currentTimeMillis();
            if (isRealPlace(from)) {
                placeEntryDao.insert(new PlaceEntry(account, from.trim(), -cents, now, "transfer"));
            }
            if (isRealPlace(to)) {
                placeEntryDao.insert(new PlaceEntry(account, to.trim(), cents, now, "transfer"));
            }
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /**
     * Kassensturz: setzt den Saldo eines Ortes (im Konto {@code account}) auf {@code targetCents} und
     * bucht die Differenz optional als Buchung auf dieses Konto. Empfänger und Kategorie kommen aus den
     * Einstellungen (siehe {@code SettingsStore#getReconcilePayee()}).
     */
    public void saveReconcile(final String account, final String place, final long targetCents,
                              final boolean createBooking, final String payee, final String category,
                              final Runnable onDone) {
        executor.execute(() -> {
            String acct = account == null ? "" : account;
            String pl = place == null ? "" : place.trim();
            // Mit Ort: Ist-Saldo aus dem Ort-Journal. Ohne Ort (Konto ohne angelegte Orte): Kontosaldo –
            // die Ausgleichsbuchung landet dann im Rest „ohne Ort" und stimmt das Konto als Ganzes ab.
            long current = pl.isEmpty() ? bookingDao.getBalanceByAccount(acct)
                    : placeEntryDao.getBalance(acct, pl);
            long diff = targetCents - current;
            if (diff != 0) {
                long now = System.currentTimeMillis();
                if (!pl.isEmpty()) {
                    placeEntryDao.insert(new PlaceEntry(account, pl, diff, now, "reconcile"));
                }
                if (createBooking) {
                    Booking b = new Booking();
                    b.amountCents = Math.abs(diff);
                    b.isIncome = diff >= 0;
                    b.payee = payee == null ? "" : payee.trim();
                    b.account = account == null ? "" : account;
                    b.category = category == null ? "" : category.trim();
                    b.note = pl.isEmpty() ? "Kassensturz" : "Kassensturz " + pl;
                    b.createdAt = now;
                    b.exported = false;
                    if (!b.account.isEmpty()) {
                        accountDao.insertIfAbsent(new Account(b.account));
                    }
                    if (!b.payee.isEmpty()) {
                        payeeDao.insertIfAbsent(new Payee(b.payee));
                    }
                    bookingDao.insert(b);
                }
            }
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /**
     * Aktualisiert eine in der App angelegte, ort-verknüpfte Buchung und zieht die Ort-Salden per
     * angehängter Ausgleichs-Bewegung(en) nach – die alten Bewegungen bleiben als Historie stehen (Datum
     * der Änderung = jetzt). {@code newPlace} leer/„ohne Ort" → keine Bewegung (fällt in den Rest).
     * {@code parts} ersetzt die Kategorie-Teile (leer/null → Einzelkategorie). Erzeugt/ändert keine
     * andere Buchung.
     */
    public void updateBookingWithPlace(final Booking booking, final String newPlace,
                                       final List<BookingSplit> parts, final Runnable onDone) {
        executor.execute(() -> {
            Booking old = bookingDao.getById(booking.id);
            String np = isRealPlace(newPlace) ? newPlace.trim() : "";
            long now = System.currentTimeMillis();
            boolean oldReal = old != null && old.placeManaged && isRealPlace(old.place);
            long oldSigned = old != null ? signed(old) : 0;
            String oldPlace = old != null ? old.place : "";
            String oldAccount = old != null ? old.account : booking.account;
            boolean newReal = isRealPlace(np);
            long newSigned = signed(booking);
            String bookingNote = booking.payee == null || booking.payee.trim().isEmpty()
                    ? "Buchung geändert" : "Buchung: " + booking.payee.trim();
            if (oldReal && newReal && oldPlace.equals(np) && oldAccount.equals(booking.account)) {
                long delta = newSigned - oldSigned;
                if (delta != 0) {
                    placeEntryDao.insert(new PlaceEntry(booking.account, np, delta, now, "booking",
                            "Buchung geändert"));
                }
            } else {
                if (oldReal) {
                    placeEntryDao.insert(new PlaceEntry(oldAccount, oldPlace, -oldSigned, now, "booking",
                            "Buchung umgebucht/geändert"));
                }
                if (newReal) {
                    placeEntryDao.insert(new PlaceEntry(booking.account, np, newSigned, now, "booking",
                            bookingNote));
                }
            }
            booking.place = np;
            booking.placeManaged = true;
            EditStatus.apply(old, booking, isKmyMode());
            payeeDao.insertIfAbsent(new Payee(booking.payee));
            accountDao.insertIfAbsent(new Account(booking.account));
            bookingDao.update(booking);
            bookingDao.deleteSplits(booking.id);
            if (parts != null) {
                for (BookingSplit p : parts) {
                    p.bookingId = booking.id;
                    bookingDao.insertSplit(p);
                }
            }
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    // ---- Ort-Bewegungen einzeln bearbeiten (nur Ortssaldo, keine Buchung) ----

    /** Fügt eine manuelle Ort-Bewegung ins Journal ein. */
    public void addPlaceMovement(final String account, final String place, final long cents,
                                 final long dateMillis, final String note, final Runnable onDone) {
        executor.execute(() -> {
            placeEntryDao.insert(new PlaceEntry(account == null ? "" : account,
                    place == null ? "" : place, cents, dateMillis, "transfer",
                    note == null ? "" : note));
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /** Aktualisiert eine einzelne Ort-Bewegung (Datum/Betrag/Notiz). */
    public void updatePlaceMovement(final PlaceEntry entry, final Runnable onDone) {
        executor.execute(() -> {
            placeEntryDao.update(entry);
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /** Löscht eine einzelne Ort-Bewegung. */
    public void deletePlaceMovement(final long id, final Runnable onDone) {
        executor.execute(() -> {
            placeEntryDao.delete(id);
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    public void renamePlaceEntries(final String account, final String oldName, final String newName,
                                   final Runnable onDone) {
        executor.execute(() -> {
            String acct = account == null ? "" : account;
            placeEntryDao.renamePlace(acct, oldName, newName);
            bookingDao.renamePlace(acct, oldName, newName); // Buchungen folgen dem Umbenennen
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    public void deletePlaceEntries(final String account, final String place, final Runnable onDone) {
        executor.execute(() -> {
            placeEntryDao.deleteByPlace(account == null ? "" : account, place);
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /**
     * Mehrere Schreibvorgänge als <b>ein</b> Vorgang — nur für Hintergrund-Aufgaben, die ihre Daos
     * direkt ansprechen.
     *
     * <p>Gedacht für Nachbereitungen, die entweder ganz oder gar nicht gelten dürfen: nach einem
     * geglückten Export etwa steht in der Datei alles, und lokal muss dann auch alles als geschrieben
     * vermerkt sein. Bleibt davon die Hälfte liegen, exportiert der nächste Lauf eine Bewegung ein
     * zweites Mal oder verliert eine Buchung dauerhaft.</p>
     */
    public void inTransaction(Runnable work) {
        db.runInTransaction(work);
    }

    /** Liefert die Direktreferenz auf den BookingDao – nur für Hintergrund-Aufgaben verwenden. */
    public BookingDao bookingDao() {
        return bookingDao;
    }

    /** Liefert die Direktreferenz auf den SecurityDao – nur für Hintergrund-Aufgaben verwenden. */
    public SecurityDao securityDao() {
        return securityDao;
    }

    /** Liefert die Direktreferenz auf den KmyPendingDeleteDao – nur für Hintergrund-Aufgaben verwenden. */
    public KmyPendingDeleteDao kmyPendingDeleteDao() {
        return kmyPendingDeleteDao;
    }

    /** Liefert die Direktreferenz auf den ScheduledAdvanceDao – nur für Hintergrund-Aufgaben verwenden. */
    public ScheduledAdvanceDao scheduledAdvanceDao() {
        return scheduledAdvanceDao;
    }

    /** Liefert die Direktreferenz auf den ScheduledTransactionDao – nur für Hintergrund-Aufgaben. */
    public ScheduledTransactionDao scheduledTransactionDao() {
        return scheduledTransactionDao;
    }

    public ExecutorService executor() {
        return executor;
    }

    public Handler mainHandler() {
        return mainHandler;
    }
}
