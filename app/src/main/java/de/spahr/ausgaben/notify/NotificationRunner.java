package de.spahr.ausgaben.notify;

import android.content.Context;
import android.util.Log;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.BookingDao;
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

    private static final String TAG = "NotificationRunner";

    /** So viele Tage neben dem Datum der Meldung gilt eine Buchung gleichen Betrags als mögliches Duplikat. */
    static final int DUPLICATE_DAYS = 2;

    private NotificationRunner() {
    }

    /** Die Zeilen der Liste für das aktive Profil; leer, wenn es nichts zu zeigen gibt. */
    public static ArrayList<NotificationDraft> prepare(Context context, Repository repository) {
        // Nach dem Einspielen einer Sicherung kennt der Dienst die Apps dieses Profils noch nicht.
        repository.notificationTemplates().syncKnownApps();
        List<NotificationTemplate> templates = repository.notificationTemplates().all();
        // Nur was dieses Profil freigegeben und noch nicht erledigt hat.
        ArrayList<NotificationDraft> drafts = NotificationRun.drafts(
                NotificationRun.ofApps(NotificationBuffer.get(context).allFor(profileOf(context)),
                        AppSelection.allowed(context)),
                templates, Currencies::forAccount, ZoneId.systemDefault(), styleOf(context));
        if (drafts.isEmpty()) {
            return drafts;
        }
        de.spahr.ausgaben.settings.SettingsStore settings =
                new de.spahr.ausgaben.settings.SettingsStore(context);
        String currency = settings.getCurrency();
        // Meldungen ohne Standort vom Eintreffen (kein Zugriff „immer", aus der Leiste übernommen,
        // Ortung misslungen) bekommen den vom Import – so, als wäre der Satz jetzt diktiert worden.
        // Mit ausgeschaltetem GPS-Schalter gibt es gar keinen.
        String here = settings.isGpsEnabled() ? importCoords(context) : "";
        for (NotificationDraft d : drafts) {
            if (d.coords.isEmpty()) {
                d.coords = here;
            }
            // Die Notiz ist der Wortlaut der Meldung – auch für Zeilen, die nicht über die
            // Sprachroutine gebucht werden.
            d.note = d.wording();
            if (d.recognized) {
                propose(repository, d, currency);
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
     * Schließt den Durchlauf ab: bucht die buchbaren Zeilen, lernt aus den von Hand gebuchten und
     * vermerkt dann die gezeigten Einträge für dieses Profil als erledigt.
     *
     * @param remaining die Zeilen, die noch in der Liste stehen
     * @param shownIds  die Nummern <b>aller</b> Einträge, die die Liste gezeigt hat — auch der vom
     *                  Nutzer entfernten. Was später eintraf, steht nicht darin und bleibt.
     * @return wie viele Buchungen angelegt wurden; -1, wenn das Speichern scheiterte (dann ist nichts
     *         gebucht und nichts erledigt)
     */
    public static int complete(Context context, Repository repository,
                               List<NotificationDraft> remaining, long[] shownIds) {
        final de.spahr.ausgaben.statement.MoneyStyle style = styleOf(context);
        return complete(context, repository, remaining, shownIds, d -> {
            NotificationTemplate t = NotificationLearner.learn(
                    new NotificationSource(d.packageName, d.postTime, d.title, d.text, style),
                    d.cents, d.isIncome, d.payee, d.account);
            if (t != null) {
                repository.notificationTemplates().save(t);
            }
        });
    }

    /** Lernt aus einer von Hand gebuchten Zeile und legt die Vorlage ab — austauschbar für den Test. */
    interface Learner {
        void learn(NotificationDraft d);
    }

    static int complete(Context context, Repository repository, List<NotificationDraft> remaining,
                        long[] shownIds, Learner learner) {
        final String currency = new de.spahr.ausgaben.settings.SettingsStore(context).getCurrency();
        final int[] booked = {0};
        try {
            // Alles oder nichts, wie bisher – auch wenn ein Teil über die Sprachroutine läuft.
            repository.inTransaction(() -> {
                List<Booking> direct = new ArrayList<>();
                for (NotificationDraft d : remaining) {
                    if (!d.isBookable()) {
                        continue;
                    }
                    booked[0]++;
                    if (d.isTransfer) {
                        // Mit den Konten, die die Zeile zeigt – vorgeschlagen, umgestellt oder in der
                        // Maske geändert. Gebucht wird, was dasteht.
                        repository.insertTransferNow(d.fromAccount(), d.toAccount(), d.cents, d.payee,
                                d.note, d.tags, d.dateMillis);
                    } else if (d.viaVoice && !d.edited) {
                        // Unverändert übernommen: bucht dieselbe Routine, die den Vorschlag gemacht hat.
                        repository.voiceBookingBlocking(sentence(d, currency), d.account, d.isIncome,
                                d.coords, d.dateMillis, true, d.wording());
                    } else {
                        direct.add(d.toBooking());
                    }
                }
                repository.insertBookingsNow(direct);
            });
        } catch (RuntimeException e) {
            return -1;
        }
        // Ab hier stehen die Buchungen. Was jetzt noch schiefgeht, darf höchstens eine Vorlage kosten –
        // nie das Erledigt: sonst stünden dieselben Zeilen beim nächsten Aktualisieren wieder da und
        // würden ein zweites Mal gebucht.
        try {
            for (NotificationDraft d : remaining) {
                if (!d.learn || !d.isBookable()) {
                    continue;
                }
                try {
                    learner.learn(d);
                } catch (RuntimeException e) {
                    // Je Zeile: eine, aus der sich nichts lernen lässt, hält die übrigen nicht auf.
                    Log.w(TAG, "Vorlage aus Benachrichtigung nicht gelernt", e);
                }
            }
        } finally {
            List<Long> ids = new ArrayList<>();
            for (long id : shownIds) {
                ids.add(id);
            }
            try {
                // Für dieses Profil erledigt. Hat ein anderes Profil dieselbe App freigegeben, bleibt
                // die Meldung, bis auch es fertig ist.
                NotificationBuffer.get(context).markDone(ids, profileOf(context));
                NotificationBuffer.purgeNow(context);
            } catch (RuntimeException e) {
                Log.w(TAG, "Benachrichtigungen nicht als erledigt vermerkt", e);
            }
        }
        return booked[0];
    }

    /**
     * Der Satz, den man für diese Zeile diktiert hätte: „Lidl 12,13 €". Das Währungszeichen des Profils
     * steht dabei, damit die Spracheingabe den Betrag auch dann findet, wenn der Händler Ziffern im
     * Namen trägt („Shell 4711").
     */
    static String sentence(NotificationDraft d, String currency) {
        String amount = String.format(java.util.Locale.ROOT, "%d,%02d", d.cents / 100, d.cents % 100);
        return (d.rawPayee + " " + amount + " " + currency).trim();
    }

    /**
     * Lässt die Spracheingabe den Satz lesen, ohne zu buchen, und trägt ihr Ergebnis in die Zeile ein:
     * Empfänger (über Alias oder alte Buchungen, bei mehreren gleichnamigen der dem Standort beim
     * Eintreffen nächste), Kategorie samt Seite, Aufteilung. Das Konto bleibt das der Regel, und die
     * Notiz ist der Wortlaut der Meldung.
     *
     * <p>Liest sie einen anderen Betrag als die Regel, gilt die Regel und die Zeile bleibt, wie sie
     * ist: der Betrag steht dann fest, nur die Zuordnung fehlt.</p>
     *
     * <p>Ob es eine Umbuchung ist, entscheidet der Treffer dieser Auflösung wie nach einem Diktat am
     * Handy: bevorzugter Alias, dann Buchungen, dann übrige Aliase. Dann schlägt die Sprachroutine die
     * Umbuchung vor, und das Gegenkonto ist die Seite, die nicht das Konto der Regel ist. Die Richtung
     * bleibt die der Meldung.</p>
     */
    private static void propose(Repository repository, NotificationDraft d, String currency) {
        // Die Ausgabe bzw. Einnahme immer: stellt der Nutzer eine Umbuchung zurück, steht sie bereit.
        Booking v = repository.voiceBookingBlocking(sentence(d, currency), d.account, d.isIncome,
                d.coords, d.dateMillis, false, d.wording());
        if (v != null && v.amountCents == d.cents) {
            d.viaVoice = true;
            d.payee = v.payee;
            d.category = v.category == null ? "" : v.category;
            d.categoryIsIncome = v.categoryIsIncome;
            d.note = v.note == null ? "" : v.note;
            d.setSplits(v.parts);
        }
        if (!Repository.VOICE_TYPE_TRANSFER.equals(
                repository.voiceTypeBlocking(d.rawPayee, d.coords))) {
            return;
        }
        Booking t = repository.voiceBookingBlocking(sentence(d, currency), d.account,
                Repository.VOICE_TYPE_TRANSFER, d.coords, d.dateMillis, false, d.wording());
        if (t == null || t.amountCents != d.cents) {
            return;
        }
        d.isTransfer = true;
        d.transferAccount = counterpart(d.account, t.account, t.transferAccount);
        d.payee = t.payee;
        d.note = t.note == null ? "" : t.note;
    }

    /** Die Seite einer Umbuchung, die nicht {@code own} ist; leer, wenn es keine andere gibt. */
    static String counterpart(String own, String from, String to) {
        String f = from == null ? "" : from.trim();
        String t = to == null ? "" : to.trim();
        if (!f.isEmpty() && !f.equalsIgnoreCase(own)) {
            return t.equalsIgnoreCase(own) || t.isEmpty() ? f : t;
        }
        return t.equalsIgnoreCase(own) ? "" : t;
    }

    /** Der Standort jetzt, wie die Spracheingabe am Handy ihn liest; leer, wenn es keinen gibt. */
    private static String importCoords(Context context) {
        try {
            String coords = new de.spahr.ausgaben.location.LocationTagger(context).currentCoordinates();
            return coords == null ? "" : coords;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Wie das aktive Profil Beträge schreibt — für Beträge ohne Nachkommastellen in Meldungen. */
    public static de.spahr.ausgaben.statement.MoneyStyle styleOf(Context context) {
        return de.spahr.ausgaben.statement.MoneyStyle.forNumberFormat(
                new de.spahr.ausgaben.settings.SettingsStore(context).getNumberFormat());
    }

    public static String profileOf(Context context) {
        return new de.spahr.ausgaben.settings.ProfileManager(context.getApplicationContext())
                .getActiveProfileId();
    }
}
