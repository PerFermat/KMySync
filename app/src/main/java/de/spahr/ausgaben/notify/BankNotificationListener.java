package de.spahr.ausgaben.notify;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Hebt Benachrichtigungen der vom Nutzer freigegebenen Apps auf, die einen Geldbetrag nennen — die
 * Quelle für „Buchungen aus Benachrichtigungen".
 *
 * <p>Der Dienst tut nichts als aufheben: Er bucht nicht, er zeigt nichts, er schickt nichts weg. Was
 * aus einer Meldung wird, entscheidet der Nutzer in der Liste beim Aktualisieren. Ohne den Schalter in
 * den Einstellungen sieht er die Meldungen zwar (das System bindet ihn, sobald der Zugriff erteilt
 * ist), wirft sie aber ungelesen weg.</p>
 */
public class BankNotificationListener extends NotificationListenerService {

    /**
     * Der laufende Dienst. Das System bindet ihn, nicht die App — wer aus der App heraus nach den
     * Meldungen in der Benachrichtigungsleiste fragen will ({@link #scanSeen}, {@link #importActive}),
     * erreicht ihn nur so.
     */
    private static volatile BankNotificationListener running;

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    public void onListenerConnected() {
        running = this;
        if (SettingsStore.isNotifyLearnEnabled(this)) {
            io.execute(() -> NotificationBuffer.purgeNow(this));
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (running == this) {
            running = null;
        }
    }

    @Override
    public void onNotificationPosted(final StatusBarNotification sbn) {
        if (!SettingsStore.isNotifyLearnEnabled(this)) {
            return;
        }
        final Captured c = read(sbn, getPackageName());
        if (c == null) {
            return;
        }
        io.execute(() -> {
            final long id = keep(this, c, null);
            NotificationBuffer.purgeNow(this);
            // Nur hier, beim Eintreffen: jetzt ist das Handy noch dort, wo bezahlt wurde. Eine später
            // aus der Leiste übernommene Meldung bekommt keinen Standort.
            if (id > 0) {
                ArrivalLocation.fetch(this, io,
                        coords -> NotificationBuffer.get(this).setCoords(id, coords));
            }
        });
    }

    /** Was von einer Meldung gebraucht wird. */
    static final class Captured {
        final String packageName;
        final long postTime;
        final String title;
        final String text;
        final String key;

        Captured(String packageName, long postTime, String title, String text, String key) {
            this.packageName = packageName;
            this.postTime = postTime;
            this.title = title;
            this.text = text;
            this.key = key;
        }
    }

    /**
     * Liest eine Meldung; {@code null}, wenn sie nicht in Frage kommt: die eigene, die Sammelzeile einer
     * Gruppe (sie wiederholt nur ihre Einzelmeldungen) oder eine ohne Geldbetrag.
     */
    static Captured read(StatusBarNotification sbn, String ownPackage) {
        if (sbn == null || ownPackage.equals(sbn.getPackageName())) {
            return null;
        }
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            return null;
        }
        Bundle extras = n.extras;
        String title = text(extras.getCharSequence(Notification.EXTRA_TITLE));
        String big = text(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String body = big.isEmpty() ? text(extras.getCharSequence(Notification.EXTRA_TEXT)) : big;
        if (!AmountFilter.hasAmount(title, body)) {
            return null;
        }
        return new Captured(sbn.getPackageName(), sbn.getPostTime(), title, body, sbn.getKey());
    }

    /**
     * Hebt die Meldung auf, wenn ihre App freigegeben ist; sonst bleibt nur der Name der App als Angebot
     * für die Auswahl, der Text wird verworfen.
     *
     * @param only wenn gesetzt: nur Meldungen dieser Apps aufheben (für {@link #importActive})
     * @return die Nummer des neuen Puffereintrags; -1, wenn nichts neu aufgehoben wurde
     */
    static long keep(Context context, Captured c, Set<String> only) {
        if (only != null && !only.contains(c.packageName)) {
            return -1;
        }
        if (!AppSelection.isAllowedAnywhere(context, c.packageName)) {
            AppSelection.noteSeen(context, c.packageName);
            return -1;
        }
        return NotificationBuffer.get(context).add(c.packageName, c.postTime, c.title, c.text, c.key);
    }

    /**
     * Merkt die Namen aller Apps, von denen gerade eine Meldung mit Betrag in der Benachrichtigungsleiste
     * steht — damit eine Bank-App auch dann zur Auswahl steht, wenn ihre Meldung schon da war, bevor
     * die Funktion eingeschaltet wurde. Texte werden dabei nicht aufgehoben.
     */
    public static void scanSeen(Context context) {
        for (StatusBarNotification sbn : active(context)) {
            Captured c = read(sbn, context.getPackageName());
            if (c != null) {
                AppSelection.noteSeen(context, c.packageName);
            }
        }
    }

    /**
     * Übernimmt die Meldungen der genannten Apps, die noch in der Benachrichtigungsleiste stehen — der
     * Schritt nach dem Anhaken. Ohne ihn ginge die Meldung verloren, durch die die App überhaupt in der
     * Auswahl erschien, und man bräuchte eine zweite Zahlung, bevor sich etwas lernen ließe.
     *
     * <p>Was weggewischt wurde, gibt das System nicht mehr heraus.</p>
     *
     * @return wie viele Meldungen neu aufgehoben wurden
     */
    public static int importActive(Context context, Set<String> packages) {
        int added = 0;
        for (StatusBarNotification sbn : active(context)) {
            Captured c = read(sbn, context.getPackageName());
            if (c != null && keep(context, c, packages) > 0) {
                added++;
            }
        }
        return added;
    }

    /** Die Meldungen in der Leiste; leer, wenn der Dienst gerade nicht gebunden ist. */
    private static StatusBarNotification[] active(Context context) {
        BankNotificationListener service = running;
        if (service == null) {
            // Nicht gebunden (frisch installiert, vom System beendet): um Neubindung bitten. Diesmal
            // gibt es nichts; beim nächsten Öffnen der Auswahl ist er da.
            requestRebind(new ComponentName(context, BankNotificationListener.class));
            return new StatusBarNotification[0];
        }
        try {
            StatusBarNotification[] all = service.getActiveNotifications();
            return all == null ? new StatusBarNotification[0] : all;
        } catch (RuntimeException e) {
            return new StatusBarNotification[0];
        }
    }

    private static String text(CharSequence value) {
        return value == null ? "" : value.toString();
    }
}
