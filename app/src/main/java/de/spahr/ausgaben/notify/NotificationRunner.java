package de.spahr.ausgaben.notify;

import android.content.Context;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.BookingDao;
import de.spahr.ausgaben.db.PayeeCategory;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.settings.Currencies;
import de.spahr.ausgaben.statement.NotificationLearner;
import de.spahr.ausgaben.statement.NotificationSource;
import de.spahr.ausgaben.statement.NotificationTemplate;

/**
 * Der Durchlauf beim Aktualisieren, soweit er Puffer und Datenbank anfasst: die Liste vorbereiten und
 * sie am Ende abschließen. Alle Methoden arbeiten sofort und gehören auf den Hintergrundfaden.
 *
 * <p>Gelöscht wird an genau einer Stelle, in {@link #complete}, und nur dort: nach dem Speichern, nach
 * den Nummern der gezeigten Einträge. Ein Abbruch der Liste ruft sie nicht auf und löscht deshalb
 * nichts; ein Fehlschlag beim Speichern kehrt vor dem Löschen zurück.</p>
 */
public final class NotificationRunner {

    /** So viele Tage neben dem Datum der Meldung gilt eine Buchung gleichen Betrags als mögliches Duplikat. */
    static final int DUPLICATE_DAYS = 2;

    private NotificationRunner() {
    }

    /** Die Zeilen der Liste für das aktive Profil; leer, wenn es nichts zu zeigen gibt. */
    public static ArrayList<NotificationDraft> prepare(Context context, Repository repository) {
        // Nach dem Einspielen einer Sicherung kennt der Dienst die Apps dieses Profils noch nicht.
        repository.notificationTemplates().syncKnownApps();
        List<NotificationTemplate> templates = repository.notificationTemplates().all();
        ArrayList<NotificationDraft> drafts = NotificationRun.drafts(
                NotificationBuffer.get(context).all(), templates, Currencies::forAccount,
                ZoneId.systemDefault());
        if (drafts.isEmpty()) {
            return drafts;
        }
        BookingDao dao = repository.bookingDao();
        List<String> payees = dao.getDistinctPayees();
        for (NotificationDraft d : drafts) {
            if (!d.recognized) {
                continue;
            }
            String known = NotificationRun.knownPayee(d.payee, payees);
            if (known != null) {
                d.payee = known;
                List<PayeeCategory> usual = dao.getCategoriesByPayee(known, d.isIncome);
                if (!usual.isEmpty()) {
                    d.category = usual.get(0).category;
                    d.categoryIsIncome = usual.get(0).isIncome;
                }
            }
        }
        markBooked(repository, drafts);
        return drafts;
    }

    /**
     * Markiert, was schon im Konto stehen könnte: gleicher Betrag, höchstens {@link #DUPLICATE_DAYS}
     * Tage daneben. Die Richtung zählt nicht mit — der Hinweis soll lieber einmal zu oft erscheinen.
     */
    public static void markBooked(Repository repository, List<NotificationDraft> drafts) {
        BookingDao dao = repository.bookingDao();
        ZoneId zone = ZoneId.systemDefault();
        for (NotificationDraft d : drafts) {
            d.dupBooked = false;
            if (d.cents <= 0 || d.account.isEmpty()) {
                continue;
            }
            java.time.LocalDate day = Instant.ofEpochMilli(d.dateMillis).atZone(zone).toLocalDate();
            long from = day.minusDays(DUPLICATE_DAYS).atStartOfDay(zone).toInstant().toEpochMilli();
            long to = day.plusDays(DUPLICATE_DAYS + 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1;
            d.dupBooked = dao.countSameAmount(d.account, d.cents, from, to) > 0;
        }
    }

    /**
     * Schließt den Durchlauf ab: bucht die buchbaren Zeilen, lernt aus den von Hand gebuchten und löscht
     * dann die gezeigten Einträge aus dem Puffer.
     *
     * @param remaining die Zeilen, die noch in der Liste stehen
     * @param shownIds  die Nummern <b>aller</b> Einträge, die die Liste gezeigt hat — auch der vom
     *                  Nutzer entfernten. Was später eintraf, steht nicht darin und bleibt.
     * @return wie viele Buchungen angelegt wurden; -1, wenn das Speichern scheiterte (dann ist nichts
     *         gebucht und nichts gelöscht)
     */
    public static int complete(Context context, Repository repository,
                               List<NotificationDraft> remaining, long[] shownIds) {
        List<Booking> bookings = new ArrayList<>();
        for (NotificationDraft d : remaining) {
            if (d.isBookable()) {
                bookings.add(d.toBooking());
            }
        }
        try {
            repository.insertBookingsNow(bookings);
        } catch (RuntimeException e) {
            return -1;
        }
        for (NotificationDraft d : remaining) {
            if (d.learn && d.isBookable()) {
                NotificationTemplate t = NotificationLearner.learn(
                        new NotificationSource(d.packageName, d.postTime, d.title, d.text),
                        d.cents, d.isIncome, d.payee, d.account);
                if (t != null) {
                    repository.notificationTemplates().save(t);
                }
            }
        }
        List<Long> ids = new ArrayList<>();
        for (long id : shownIds) {
            ids.add(id);
        }
        NotificationBuffer.get(context).delete(ids);
        return bookings.size();
    }
}
