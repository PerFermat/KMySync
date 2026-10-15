package de.spahr.ausgaben.ui;

import android.app.Dialog;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.notify.NotificationBuffer;
import de.spahr.ausgaben.notify.NotificationMatcher;
import de.spahr.ausgaben.settings.SettingsStore;
import de.spahr.ausgaben.statement.NotificationLearner;
import de.spahr.ausgaben.statement.NotificationSource;
import de.spahr.ausgaben.statement.NotificationTemplate;

/**
 * Nach dem Speichern einer von Hand angelegten Buchung: gibt es eine Benachrichtigung, die denselben
 * Umsatz gemeldet hat, fragt die Maske, ob künftige Meldungen dieser App für das Konto verarbeitet
 * werden sollen — und lernt bei „Ja" die Vorlage daraus.
 *
 * <p>Die Buchung ist zu diesem Zeitpunkt <b>schon gespeichert</b>. Jeder Ausgang hier endet deshalb im
 * Schließen der Maske, und die Dialoge laufen über {@link HostedDialog}: ginge einer bei einer Drehung
 * verloren, stünde die ausgefüllte Maske wieder da und ein zweiter Tipp auf „Speichern" buchte
 * doppelt.</p>
 */
final class NotificationLearnFlow {

    static final String DLG_ASK = "dlg_notifyLearnAsk";
    static final String DLG_PICK = "dlg_notifyLearnPick";

    private static final String ARG_IDS = "a_nlIds";
    private static final String ARG_APPS = "a_nlApps";
    private static final String ARG_TITLES = "a_nlTitles";
    private static final String ARG_TEXTS = "a_nlTexts";
    private static final String ARG_TIMES = "a_nlTimes";
    private static final String ARG_INDEX = "a_nlIndex";
    private static final String ARG_CENTS = "a_nlCents";
    private static final String ARG_INCOME = "a_nlIncome";
    private static final String ARG_PAYEE = "a_nlPayee";
    private static final String ARG_ACCOUNT = "a_nlAccount";

    private NotificationLearnFlow() {
    }

    /**
     * Sucht zur gerade gespeicherten Buchung die Benachrichtigung und fragt gegebenenfalls nach.
     * {@code close} läuft, wenn es nichts zu fragen gibt; mit Dialog schließt dessen Ausgang die Maske.
     */
    static void afterSave(final FragmentActivity activity, final Repository repository,
                          final Booking b, final Runnable close) {
        if (b == null || b.isTransfer || b.amountCents <= 0
                || !SettingsStore.isNotifyLearnEnabled(activity)) {
            close.run();
            return;
        }
        final String account = b.account == null ? "" : b.account;
        final String payee = b.payee == null ? "" : b.payee;
        repository.runInBackground(() -> offers(activity, repository, b, account, payee), found -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            if (found.isEmpty()) {
                close.run();
                return;
            }
            Bundle args = new Bundle();
            long[] ids = new long[found.size()];
            long[] times = new long[found.size()];
            String[] apps = new String[found.size()];
            String[] titles = new String[found.size()];
            String[] texts = new String[found.size()];
            for (int i = 0; i < found.size(); i++) {
                NotificationBuffer.Entry e = found.get(i);
                ids[i] = e.id;
                times[i] = e.postTime;
                apps[i] = e.packageName;
                titles[i] = e.title;
                texts[i] = e.text;
            }
            args.putLongArray(ARG_IDS, ids);
            args.putLongArray(ARG_TIMES, times);
            args.putStringArray(ARG_APPS, apps);
            args.putStringArray(ARG_TITLES, titles);
            args.putStringArray(ARG_TEXTS, texts);
            args.putLong(ARG_CENTS, b.amountCents);
            args.putBoolean(ARG_INCOME, b.isIncome);
            args.putString(ARG_PAYEE, payee);
            args.putString(ARG_ACCOUNT, account);
            if (found.size() == 1) {
                args.putInt(ARG_INDEX, 0);
                HostedDialog.show(activity, DLG_ASK, args);
            } else {
                HostedDialog.show(activity, DLG_PICK, args);
            }
        });
    }

    /**
     * Die Meldungen, aus denen sich etwas lernen ließe: je App höchstens eine, und keine, die eine
     * Vorlage dieses Kontos schon liest — dann gibt es nichts zu fragen.
     */
    private static List<NotificationBuffer.Entry> offers(FragmentActivity activity,
                                                         Repository repository, Booking b,
                                                         String account, String payee) {
        // Nur Meldungen von Apps, die dieses Profil freigegeben hat, und die es noch nicht erledigt
        // hat – sonst böte ein Profil an, aus der Bank-Meldung eines anderen zu lernen.
        List<NotificationBuffer.Entry> found = new ArrayList<>(NotificationMatcher.match(
                de.spahr.ausgaben.notify.NotificationRun.ofApps(
                        NotificationBuffer.get(activity).allFor(
                                de.spahr.ausgaben.notify.NotificationRunner.profileOf(activity)),
                        de.spahr.ausgaben.notify.AppSelection.allowed(activity)),
                b.amountCents, b.createdAt, payee, ZoneId.systemDefault()));
        if (found.isEmpty()) {
            return found;
        }
        List<NotificationTemplate> templates = repository.notificationTemplates().all();
        for (Iterator<NotificationBuffer.Entry> it = found.iterator(); it.hasNext(); ) {
            NotificationSource source = sourceOf(it.next());
            for (NotificationTemplate t : templates) {
                NotificationTemplate.Hit hit = t.account.equals(account) ? t.apply(source) : null;
                if (hit != null && hit.cents == b.amountCents) {
                    it.remove();
                    break;
                }
            }
        }
        return found;
    }

    private static NotificationSource sourceOf(NotificationBuffer.Entry e) {
        return new NotificationSource(e.packageName, e.postTime, e.title, e.text);
    }

    /** Baut den Dialog zu {@code key}; {@code null}, wenn der Schlüssel nicht hierher gehört. */
    static Dialog build(final FragmentActivity activity, final Repository repository, String key,
                        final Bundle args) {
        if (DLG_PICK.equals(key)) {
            return buildPick(activity, args);
        }
        if (DLG_ASK.equals(key)) {
            return buildAsk(activity, repository, args);
        }
        return null;
    }

    static boolean owns(String key) {
        return DLG_ASK.equals(key) || DLG_PICK.equals(key);
    }

    /**
     * Mehrere Meldungen passen — aus verschiedenen Apps oder aus einer, ohne dass der Empfänger sie
     * trennt: der Nutzer sagt, welche gemeint ist.
     */
    private static Dialog buildPick(final FragmentActivity activity, final Bundle args) {
        String[] apps = args.getStringArray(ARG_APPS);
        String[] texts = args.getStringArray(ARG_TEXTS);
        long[] times = args.getLongArray(ARG_TIMES);
        CharSequence[] labels = new CharSequence[apps.length];
        for (int i = 0; i < apps.length; i++) {
            // Mit Uhrzeit: zwei Meldungen derselben App können gleich lauten (zweimal derselbe
            // Kaffee), und dann trennt sie nur noch der Zeitpunkt.
            labels[i] = appLabel(activity, apps[i]) + " · "
                    + de.spahr.ausgaben.settings.DateFormats.dateTime(times[i]) + "\n" + texts[i];
        }
        return new AppDialog(activity)
                .setTitle(R.string.notify_offer_pick_title)
                .setItems(labels, (d, which) -> {
                    Bundle chosen = new Bundle(args);
                    chosen.putInt(ARG_INDEX, which);
                    HostedDialog.show(activity, DLG_ASK, chosen);
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> activity.finish())
                .create();
    }

    private static Dialog buildAsk(final FragmentActivity activity, final Repository repository,
                                   final Bundle args) {
        final int i = args.getInt(ARG_INDEX, 0);
        final String app = args.getStringArray(ARG_APPS)[i];
        final String title = args.getStringArray(ARG_TITLES)[i];
        final String text = args.getStringArray(ARG_TEXTS)[i];
        final long id = args.getLongArray(ARG_IDS)[i];
        final long time = args.getLongArray(ARG_TIMES)[i];
        final String account = args.getString(ARG_ACCOUNT, "");
        String shown = title.isEmpty() ? text : title + "\n" + text;
        return new AppDialog(activity)
                .setTitle(R.string.notify_offer_title)
                .setMessage(activity.getString(R.string.notify_offer_message,
                        appLabel(activity, app), shown, account))
                .setPositiveButton(R.string.notify_offer_yes, (d, w) -> learn(activity, repository,
                        new NotificationBuffer.Entry(id, app, time, title, text, ""),
                        args.getLong(ARG_CENTS), args.getBoolean(ARG_INCOME),
                        args.getString(ARG_PAYEE, ""), account))
                .setNegativeButton(R.string.notify_offer_no, (d, w) -> activity.finish())
                .create();
    }

    private static void learn(final FragmentActivity activity, final Repository repository,
                              final NotificationBuffer.Entry entry, final long cents,
                              final boolean isIncome, final String payee, final String account) {
        repository.runInBackground(() -> {
            NotificationTemplate template = NotificationLearner.learn(sourceOf(entry), cents,
                    isIncome, payee, account);
            if (template == null) {
                return Boolean.FALSE;
            }
            repository.notificationTemplates().save(template);
            // Aus dieser Meldung wurde gelernt, und die Buchung dazu steht schon da: erledigt.
            NotificationBuffer.get(activity).markDone(Collections.singletonList(entry.id),
                    de.spahr.ausgaben.notify.NotificationRunner.profileOf(activity));
            NotificationBuffer.purgeNow(activity);
            return Boolean.TRUE;
        }, learned -> {
            Toast.makeText(activity.getApplicationContext(),
                    learned ? R.string.notify_learned : R.string.notify_learn_failed,
                    Toast.LENGTH_LONG).show();
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                activity.finish();
            }
        });
    }

    /** Der Name der App, wie der Nutzer ihn kennt; lässt er sich nicht erfragen, der Paketname. */
    static String appLabel(FragmentActivity activity, String packageName) {
        try {
            PackageManager pm = activity.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return packageName;
        }
    }
}
