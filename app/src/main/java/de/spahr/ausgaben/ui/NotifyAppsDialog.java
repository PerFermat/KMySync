package de.spahr.ausgaben.ui;

import android.view.View;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.notify.AppSelection;
import de.spahr.ausgaben.notify.BankNotificationListener;
import de.spahr.ausgaben.statement.NotificationTemplate;

/**
 * Die Auswahl der Apps, deren Benachrichtigungen das aktive Profil liest — der Dialog hinter der
 * Schaltfläche in den Profileinstellungen.
 *
 * <p>Angeboten werden die Apps, die seit dem Einschalten eine Meldung mit Betrag geschickt haben; eine
 * Liste aller installierten Apps bräuchte eine Berechtigung, die diese Funktion nicht wert ist.</p>
 *
 * <p>Hinter jeder App steht ein Papierkorb. Er löscht zuerst, was dieses Profil für sie gelernt hat,
 * und beim nächsten Tipp die App selbst aus der Liste. Beides wirkt sofort und für sich; der Haken
 * davor gilt erst mit OK.</p>
 */
final class NotifyAppsDialog {

    private NotifyAppsDialog() {
    }

    static void show(final FragmentActivity activity, final Repository repository) {
        // Erst nachsehen, was gerade in der Benachrichtigungsleiste steht: eine Bank-App, deren
        // Meldung schon vor dem Einschalten da war, soll zur Auswahl stehen.
        BankNotificationListener.scanSeen(activity);
        repository.getNotificationTemplates(templates -> {
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                show(activity, repository, templates);
            }
        });
    }

    private static void show(final FragmentActivity activity, final Repository repository,
                             List<NotificationTemplate> templates) {
        // Auch Apps, für die es nur noch Regeln gibt, gehören in die Liste – sonst ließen die sich
        // nicht mehr löschen.
        java.util.Set<String> names = new java.util.TreeSet<>(
                AppSelection.candidates(activity));
        final Map<String, java.util.Set<String>> accountsByApp = new java.util.HashMap<>();
        for (NotificationTemplate t : templates) {
            names.add(t.packageName);
            java.util.Set<String> accounts = accountsByApp.get(t.packageName);
            if (accounts == null) {
                accounts = new java.util.TreeSet<>();
                accountsByApp.put(t.packageName, accounts);
            }
            accounts.add(t.account);
        }
        final List<String> packages = new ArrayList<>(names);
        if (packages.isEmpty()) {
            new AppDialog(activity)
                    .setTitle(R.string.notify_apps_button)
                    .setMessage(R.string.notify_apps_none)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        java.util.Set<String> allowed = AppSelection.allowed(activity);
        final boolean[] checked = new boolean[packages.size()];
        final boolean[] removed = new boolean[packages.size()];
        android.widget.LinearLayout list = new android.widget.LinearLayout(activity);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Math.round(16 * activity.getResources().getDisplayMetrics().density);
        list.setPadding(pad, pad / 2, pad / 2, 0);
        for (int i = 0; i < packages.size(); i++) {
            final int index = i;
            final String packageName = packages.get(i);
            final String label = NotificationLearnFlow.appLabel(activity, packageName);
            final View row = activity.getLayoutInflater().inflate(R.layout.item_notify_app, list, false);
            final android.widget.CheckBox check = row.findViewById(R.id.checkNotifyApp);
            checked[i] = allowed.contains(packageName);
            check.setChecked(checked[i]);
            check.setOnCheckedChangeListener(
                    (b, isChecked) -> checked[index] = isChecked && !removed[index]);
            row.findViewById(R.id.notifyAppTexts).setOnClickListener(v -> check.toggle());
            ((android.widget.TextView) row.findViewById(R.id.textNotifyApp)).setText(label);
            final android.widget.TextView rules = row.findViewById(R.id.textNotifyAppRules);
            final View forget = row.findViewById(R.id.btnNotifyAppForget);
            java.util.Set<String> accounts = accountsByApp.get(packageName);
            final boolean[] hasRules = {accounts != null};
            if (accounts != null) {
                rules.setText(activity.getString(R.string.notify_rules_for,
                        android.text.TextUtils.join(", ", accounts)));
                rules.setVisibility(View.VISIBLE);
            }
            // Der Papierkorb in zwei Stufen: erst die gelernten Regeln, und wenn keine (mehr) da
            // sind, die App selbst – samt dem, was von ihr aufgehoben wurde.
            forget.setVisibility(View.VISIBLE);
            forget.setContentDescription(activity.getString(hasRules[0]
                    ? R.string.notify_rules_delete : R.string.notify_app_remove));
            forget.setOnClickListener(v -> {
                if (hasRules[0]) {
                    AppDialog.destructive(activity)
                            .setTitle(R.string.notify_rules_delete)
                            .setMessage(activity.getString(R.string.notify_rules_delete_message, label))
                            .setPositiveButton(R.string.delete, (d, w) ->
                                    repository.deleteNotificationTemplates(packageName, () -> {
                                        hasRules[0] = false;
                                        rules.setVisibility(View.GONE);
                                        forget.setContentDescription(
                                                activity.getString(R.string.notify_app_remove));
                                        Toast.makeText(activity, R.string.notify_rules_deleted,
                                                Toast.LENGTH_SHORT).show();
                                    }))
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                    return;
                }
                AppDialog.destructive(activity)
                        .setTitle(R.string.notify_app_remove)
                        .setMessage(activity.getString(R.string.notify_app_remove_message, label))
                        .setPositiveButton(R.string.delete, (d, w) -> {
                            AppSelection.forget(activity, packageName);
                            // Auch für das OK des Dialogs gilt sie nicht mehr als gewählt.
                            checked[index] = false;
                            removed[index] = true;
                            row.setVisibility(View.GONE);
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            });
            list.addView(row);
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        scroll.addView(list);
        new AppDialog(activity)
                .setTitle(R.string.notify_apps_button)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    List<String> chosen = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) {
                        if (checked[i]) {
                            chosen.add(packages.get(i));
                        }
                    }
                    final java.util.Set<String> neu = new java.util.HashSet<>(chosen);
                    neu.removeAll(AppSelection.allowed(activity));
                    AppSelection.setAllowed(activity, chosen);
                    // Frisch angehakt: was von diesen Apps noch in der Leiste steht, gleich mitnehmen –
                    // dann reicht die eine Meldung, die schon da ist, zum Lernen.
                    if (!neu.isEmpty()) {
                        int imported = BankNotificationListener.importActive(activity, neu);
                        if (imported > 0) {
                            Toast.makeText(activity, activity.getString(
                                    R.string.notify_apps_imported, imported), Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
