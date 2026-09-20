package de.spahr.ausgaben.notify;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Tägliche Erinnerung an <b>heute fällige</b> geplante Buchungen. Standardmäßig aus; einschaltbar in den
 * Einstellungen. Bewusst ein <b>ungenauer</b> Tageswecker ({@link AlarmManager}) statt WorkManager – die
 * Bibliothek ist nicht eingebunden und für einmal täglich reicht das (Muster: {@code widget/WidgetType}).
 * Der Empfänger {@link ScheduledReminderReceiver} prüft die Planungen und meldet nur, wenn es etwas gibt.
 */
public final class ScheduledReminder {

    static final String CHANNEL_ID = "scheduled_due";
    static final int NOTIFICATION_ID = 4711;
    /** Uhrzeit der täglichen Prüfung. */
    private static final int HOUR = 8;

    private ScheduledReminder() {
    }

    /** Stellt den Wecker, wenn die Erinnerung aktiv ist – sonst wird er abbestellt. */
    public static void apply(Context context) {
        if (new SettingsStore(context).isScheduledReminderEnabled()) {
            schedule(context);
        } else {
            cancel(context);
        }
    }

    private static void schedule(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, HOUR);
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (next.getTimeInMillis() <= System.currentTimeMillis()) {
            next.add(Calendar.DAY_OF_MONTH, 1);
        }
        am.setInexactRepeating(AlarmManager.RTC, next.getTimeInMillis(),
                AlarmManager.INTERVAL_DAY, pendingIntent(context));
    }

    private static void cancel(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(pendingIntent(context));
        }
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent i = new Intent(context.getApplicationContext(), ScheduledReminderReceiver.class);
        i.setAction(ScheduledReminderReceiver.ACTION_CHECK);
        return PendingIntent.getBroadcast(context.getApplicationContext(), 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * Legt den Benachrichtigungskanal an; mehrfaches Anlegen ist harmlos.
     *
     * <p>Kanäle gibt es ab Android 8, und darunter läuft die App nicht mehr ({@code minSdk 26} ist
     * genau diese Fassung). Die Abfrage darauf stand bis 2.1 hier und konnte nie zutreffen.</p>
     */
    static void ensureChannel(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(channel);
    }
}
