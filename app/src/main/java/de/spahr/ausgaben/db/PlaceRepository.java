package de.spahr.ausgaben.db;

import android.os.Handler;

import java.util.List;
import java.util.concurrent.ExecutorService;

import de.spahr.ausgaben.db.Repository.Callback;

/**
 * Das Journal der Bargeld-Orte: Salden, Verlauf, Umbuchen zwischen Orten und einzelne Ort-Bewegungen.
 * Kollaborator hinter der {@link Repository}-Fassade; teilt sich deren Hintergrund-Executor und Main-Handler.
 *
 * <p>Die Wege, die dabei auch eine <b>Buchung</b> schreiben (Speichern/Ändern mit Ort, Kassensturz),
 * bleiben im {@link Repository}: Sie gehen durch dessen einzige Tür zur Empfängerliste
 * ({@code rememberPayee}) und den Bearbeitet-Status; hier steht nur, was allein das Journal anfasst.</p>
 */
class PlaceRepository {

    private static final String NO_PLACE = de.spahr.ausgaben.settings.PlacesStore.NO_PLACE;

    private final PlaceEntryDao placeEntryDao;
    private final BookingDao bookingDao;
    private final ExecutorService executor;
    private final Handler mainHandler;

    PlaceRepository(PlaceEntryDao placeEntryDao, BookingDao bookingDao, ExecutorService executor,
                    Handler mainHandler) {
        this.placeEntryDao = placeEntryDao;
        this.bookingDao = bookingDao;
        this.executor = executor;
        this.mainHandler = mainHandler;
    }

    /** Ein echter Ort – nicht leer und nicht „ohne Ort". */
    static boolean isRealPlace(String place) {
        return place != null && !place.trim().isEmpty() && !place.equals(NO_PLACE);
    }

    /** Vorzeichenbehafteter Betrag einer Buchung (Einnahme = +, Ausgabe = −). */
    static long signed(Booking b) {
        return b.isIncome ? b.amountCents : -b.amountCents;
    }

    /**
     * Legt für eine neu angelegte, ort-verknüpfte Buchung die anfängliche Ort-Bewegung an (nur wenn ein
     * echter Ort hinterlegt ist). Datum = Buchungsdatum. Läuft auf dem Executor-Thread.
     */
    void insertBookingMovement(Booking b) {
        if (b.placeManaged && isRealPlace(b.place)) {
            String note = b.payee == null || b.payee.trim().isEmpty()
                    ? "Buchung" : "Buchung: " + b.payee.trim();
            placeEntryDao.insert(new PlaceEntry(b.account, b.place, signed(b), b.createdAt, "booking", note));
        }
    }

    /** Ordnet noch nicht zugeordnete Ort-Bewegungen einmalig dem Standardkonto zu (Migration v4→v5). */
    void migratePlaceEntryAccounts(final String defaultAccount) {
        if (defaultAccount == null || defaultAccount.trim().isEmpty()) {
            return;
        }
        executor.execute(() -> placeEntryDao.assignEmptyAccount(defaultAccount.trim()));
    }

    /** Ort-Salden eines Kontos aus dem Journal (Σ Bewegungen je Ort). „ohne Ort" ist der berechnete Rest. */
    void getPlaceBalances(final String account, final Callback<List<PlaceBalance>> callback) {
        executor.execute(() -> {
            final List<PlaceBalance> result = placeEntryDao.getBalances(account == null ? "" : account);
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Ort-Salden je (Konto, Ort) über alle Konten aus dem Journal (für die Bestände-Gruppenliste). */
    void getAllPlaceBalances(final Callback<List<PlaceBalance>> callback) {
        executor.execute(() -> {
            final List<PlaceBalance> result = placeEntryDao.getAllBalances();
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    void getPlaceHistory(final String account, final String place, final Callback<List<PlaceEntry>> callback) {
        executor.execute(() -> {
            final List<PlaceEntry> result = placeEntryDao.getByPlace(
                    account == null ? "" : account, place);
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    void getAllPlaceEntries(final Callback<List<PlaceEntry>> callback) {
        executor.execute(() -> {
            final List<PlaceEntry> result = placeEntryDao.getAll();
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Umbuchen zwischen Orten desselben Kontos (keine Buchung). */
    void saveTransfer(final String account, final String from, final String to,
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

    /** Fügt eine manuelle Ort-Bewegung ins Journal ein. */
    void addPlaceMovement(final String account, final String place, final long cents,
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
    void updatePlaceMovement(final PlaceEntry entry, final Runnable onDone) {
        executor.execute(() -> {
            placeEntryDao.update(entry);
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /** Löscht eine einzelne Ort-Bewegung. */
    void deletePlaceMovement(final long id, final Runnable onDone) {
        executor.execute(() -> {
            placeEntryDao.delete(id);
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    void renamePlaceEntries(final String account, final String oldName, final String newName,
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

    void deletePlaceEntries(final String account, final String place, final Runnable onDone) {
        executor.execute(() -> {
            placeEntryDao.deleteByPlace(account == null ? "" : account, place);
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }
}
