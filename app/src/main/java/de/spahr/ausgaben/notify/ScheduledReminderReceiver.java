package de.spahr.ausgaben.notify;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.Calendar;
import java.util.List;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.AppDatabase;
import de.spahr.ausgaben.db.ScheduleProjection;
import de.spahr.ausgaben.db.ScheduledTransaction;
import de.spahr.ausgaben.settings.SettingsStore;
import de.spahr.ausgaben.ui.ScheduledActivity;

/**
 * Empfängt den Tageswecker (und {@code BOOT_COMPLETED}, um ihn nach einem Neustart neu zu stellen), zählt
 * die <b>heute fälligen</b> Planungen und meldet sie. Gibt es nichts, kommt auch keine Benachrichtigung.
 */
public class ScheduledReminderReceiver extends BroadcastReceiver {

    public static final String ACTION_CHECK = "de.spahr.ausgaben.CHECK_SCHEDULED";

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        String action = intent == null ? null : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            ScheduledReminder.apply(app);   // Wecker überlebt den Neustart nicht
            return;
        }
        if (!ACTION_CHECK.equals(action) || !new SettingsStore(app).isScheduledReminderEnabled()) {
            return;
        }
        final PendingResult result = goAsync();
        new Thread(() -> {
            try {
                int due = countDueToday(app);
                if (due > 0) {
                    notifyDue(app, due);
                }
            } catch (Exception ignored) {
                // Eine fehlgeschlagene Erinnerung darf nichts weiter beeinflussen.
            } finally {
                result.finish();
            }
        }).start();
    }

    /**
     * Anzahl der Termine, die heute fällig sind (alle Planungen über {@link ScheduleProjection}
     * aufgefaltet).
     *
     * <p>Paketsichtbar statt privat, damit {@code ScheduledReminderTest} das Tagesfenster prüfen kann.
     * Über {@link #onReceive} ginge das nur mit einem echten Wecker und einem
     * Benachrichtigungsdienst — geprüft werden soll aber das Fenster, nicht der Weg dorthin.</p>
     */
    int countDueToday(Context app) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long from = c.getTimeInMillis();
        long to = from + 24L * 60 * 60 * 1000 - 1;

        List<ScheduledTransaction> all = AppDatabase.getInstance(app).scheduledTransactionDao().getAllByDue();
        int count = 0;
        for (ScheduledTransaction st : all) {
            if (st.nextDueMs <= 0) {
                continue;
            }
            count += ScheduleProjection.occurrences(st.nextDueMs, st.occurrence, st.occurrenceMultiplier,
                    st.endMs, from, to, 50).size();
        }
        return count;
    }

    private void notifyDue(Context app, int count) {
        ScheduledReminder.ensureChannel(app);
        Intent open = new Intent(app, ScheduledActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(app, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(app, ScheduledReminder.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_calendar)
                .setContentTitle(app.getString(R.string.reminder_title))
                .setContentText(app.getString(R.string.reminder_due, count))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        try {
            NotificationManagerCompat.from(app).notify(ScheduledReminder.NOTIFICATION_ID, n);
        } catch (SecurityException ignored) {
            // Benachrichtigungs-Berechtigung entzogen – dann eben still.
        }
    }
}
