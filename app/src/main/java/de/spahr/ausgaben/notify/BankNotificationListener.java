package de.spahr.ausgaben.notify;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

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

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    public void onListenerConnected() {
        if (SettingsStore.isNotifyLearnEnabled(this)) {
            io.execute(this::expire);
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || getPackageName().equals(sbn.getPackageName())
                || !SettingsStore.isNotifyLearnEnabled(this)) {
            return;
        }
        Notification n = sbn.getNotification();
        // Die Sammelzeile einer Gruppe wiederholt nur, was ihre Einzelmeldungen schon sagen.
        if (n == null || n.extras == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            return;
        }
        Bundle extras = n.extras;
        final String title = text(extras.getCharSequence(Notification.EXTRA_TITLE));
        String big = text(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        final String body = big.isEmpty() ? text(extras.getCharSequence(Notification.EXTRA_TEXT)) : big;
        if (!AmountFilter.hasAmount(title, body)) {
            return;
        }
        final String packageName = sbn.getPackageName();
        // Nur freigegebene Apps. Von den übrigen bleibt der Name – als Angebot für die Auswahl in
        // den Einstellungen –, der Text wird hier verworfen.
        if (!AppSelection.isAllowed(this, packageName)) {
            AppSelection.noteSeen(this, packageName);
            return;
        }
        final long postTime = sbn.getPostTime();
        final String key = sbn.getKey();
        io.execute(() -> {
            NotificationBuffer.get(this).add(packageName, postTime, title, body, key);
            expire();
        });
    }

    private void expire() {
        NotificationBuffer.get(this).expire(System.currentTimeMillis(), new KnownApps(this).all());
    }

    private static String text(CharSequence value) {
        return value == null ? "" : value.toString();
    }
}
