package de.spahr.ausgaben.db;

import android.os.Handler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import de.spahr.ausgaben.db.Repository.Callback;
import de.spahr.ausgaben.db.Repository.ScheduledData;

/**
 * Geplante Buchungen aus der .kmy: Ersetzen beim Import, Lesen für Liste und Maske, Weiterstellen
 * (Vormerkung für den Export) und der Abgleich mit frisch erfassten Wertpapier-Bewegungen.
 * Kollaborator hinter der {@link Repository}-Fassade; teilt sich deren Hintergrund-Executor und Main-Handler.
 */
class ScheduledRepository {

    private final ScheduledTransactionDao scheduledTransactionDao;
    private final ScheduledSplitDao scheduledSplitDao;
    private final ScheduledAdvanceDao scheduledAdvanceDao;
    private final SecurityDao securityDao;
    private final ExecutorService executor;
    private final Handler mainHandler;

    ScheduledRepository(ScheduledTransactionDao scheduledTransactionDao, ScheduledSplitDao scheduledSplitDao,
                        ScheduledAdvanceDao scheduledAdvanceDao, SecurityDao securityDao,
                        ExecutorService executor, Handler mainHandler) {
        this.scheduledTransactionDao = scheduledTransactionDao;
        this.scheduledSplitDao = scheduledSplitDao;
        this.scheduledAdvanceDao = scheduledAdvanceDao;
        this.securityDao = securityDao;
        this.executor = executor;
        this.mainHandler = mainHandler;
    }

    /**
     * Ersetzt beim .kmy-Import die geplanten Buchungen komplett (leeren + neu einfügen), damit sie sich
     * „sobald ein Konto neu eingelesen wurde" aktualisieren. {@code onDone} optional (Main-Thread).
     */
    void applyScheduledTransactions(final List<ScheduledTransaction> list, final Runnable onDone) {
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
    void getScheduledById(final long id, final Callback<ScheduledTransaction> callback) {
        executor.execute(() -> {
            final ScheduledTransaction result = scheduledTransactionDao.getById(id);
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Die Kategorie-Teile einer geplanten Splitbuchung (für die Detail-Maske). */
    void getScheduledSplits(final long scheduledId, final Callback<List<ScheduledSplit>> callback) {
        executor.execute(() -> {
            final List<ScheduledSplit> result = scheduledSplitDao.getForScheduled(scheduledId);
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Geplante Buchungen nach nächster Fälligkeit (für die Seite „Geplante Buchungen"). */
    void getScheduledTransactions(final Callback<List<ScheduledTransaction>> callback) {
        executor.execute(() -> {
            final List<ScheduledTransaction> result = scheduledTransactionDao.getAllByDue();
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Geplante Buchungen + Vormerkungen in einem Zug (für die Seite „Geplante Buchungen"). */
    void getScheduledTransactionsWithAdvances(final Callback<ScheduledData> callback) {
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
    void advanceScheduled(final ScheduledTransaction st, final long dueMs, final boolean executed,
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

    /**
     * Sucht für jede frisch gespeicherte Wertpapier-Bewegung eine passende geplante Umbuchung auf das
     * Wertpapierkonto (siehe {@link ScheduleMatch}). Läuft erst nach dem Speichern, damit die Bewegung
     * schon in der DB steht; ändert selbst nichts, meldet nur Treffer zurück – bestätigt werden sie über
     * {@link #confirmScheduleMatch}.
     */
    void findScheduleMatches(final List<SecurityTx> txs, final Callback<List<ScheduleMatch.Result>> callback) {
        executor.execute(() -> {
            final List<ScheduleMatch.Result> results = new ArrayList<>();
            final List<ScheduledTransaction> schedules = scheduledTransactionDao.getAllByDue();
            for (SecurityTx tx : txs) {
                Security security = securityDao.getSecurity(tx.depot, tx.securityKmyId);
                if (security == null) {
                    continue;
                }
                ScheduledTransaction match = ScheduleMatch.findMatch(schedules, tx, security.name);
                if (match == null || tx.shares == 0) {
                    continue;
                }
                double price = Math.abs(tx.amountCents) / 100.0 / Math.abs(tx.shares);
                double newShares = ScheduleMatch.newShares(match, price);
                if (newShares <= 0) {
                    // Kein Kurs, also keine Stückzahl: Bei einer Bewegung über 0 € (Gratisstücke,
                    // Berichtigung) käme hier 0 heraus, und genau die stünde später im Split der
                    // Planung – zusammen mit dem Ersatzkurs „1/1" aus KmyExporter#priceFraction.
                    // Lieber keinen Treffer melden als die Planung mit einer 0 überschreiben.
                    continue;
                }
                results.add(new ScheduleMatch.Result(tx, match, newShares));
            }
            mainHandler.post(() -> callback.onResult(results));
        });
    }

    /**
     * Übernimmt einen vom Nutzer bestätigten Schedule-Treffer: stellt den Termin erledigt weiter (wie
     * {@link #advanceScheduled}). Die Stückzahl der Planung selbst wird nicht mehr angefasst – der Export
     * schreibt an dieser Stelle nichts in die .kmy-Datei zurück.
     */
    void confirmScheduleMatch(final ScheduleMatch.Result match, final Runnable onDone) {
        final ScheduledTransaction st = match.schedule;
        if (st == null || st.kmyId == null || st.kmyId.trim().isEmpty()) {
            if (onDone != null) {
                mainHandler.post(onDone);
            }
            return;
        }
        final long dueMs = st.nextDueMs;
        final long next = ScheduleProjection.nextDue(dueMs, st.occurrence, st.occurrenceMultiplier);
        executor.execute(() -> {
            ScheduledAdvance a = scheduledAdvanceDao.getByKmyId(st.kmyId);
            boolean isNew = a == null;
            if (isNew) {
                a = new ScheduledAdvance(st.kmyId, dueMs, next, dueMs, System.currentTimeMillis());
            } else {
                a.fromDueMs = dueMs;
                a.nextDueMs = next;
                a.lastPaymentMs = dueMs;
                a.updatedAt = System.currentTimeMillis();
            }
            if (isNew) {
                scheduledAdvanceDao.insert(a);
            } else {
                scheduledAdvanceDao.update(a);
            }
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /** Alle Kategorie-Teile geplanter Splitbuchungen auf einmal (für die Kategorien-Auswertung). */
    void getAllScheduledSplits(final Callback<List<ScheduledSplit>> callback) {
        executor.execute(() -> {
            final List<ScheduledSplit> result = scheduledSplitDao.getAll();
            mainHandler.post(() -> callback.onResult(result));
        });
    }
}
