package de.spahr.ausgaben.notify;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Welche Apps der Nutzer für „Buchungen aus Benachrichtigungen" freigegeben hat — und welche sich
 * dafür anbieten.
 *
 * <p>Aufgehoben wird nur, was von einer <b>freigegebenen</b> App kommt. Von allen anderen merkt sich
 * die App höchstens den Paketnamen, und auch den nur, wenn die Meldung einen Geldbetrag nannte: daraus
 * entsteht die Auswahlliste in den Einstellungen. Der Text einer nicht freigegebenen App liegt damit
 * zu keinem Zeitpunkt auf dem Gerät.</p>
 *
 * <p>Der Umweg über „gesehene" Apps erspart die Berechtigung, alle installierten Apps aufzulisten.
 * Sein Preis: die erste Meldung einer neuen Bank-App geht verloren, gesammelt wird ab dem Anhaken.</p>
 *
 * <p>Die Freigabe steht bei den Einstellungen und reist mit der Sicherung; die Liste der gesehenen
 * Apps ist bloß ein Angebot und bleibt in einer eigenen Datei, die keine Sicherung kennt.</p>
 */
public final class AppSelection {

    private static final String SEEN_PREFS = "notify_seen_apps";
    private static final String KEY_SEEN = "seen";

    private AppSelection() {
    }

    public static Set<String> allowed(Context context) {
        return SettingsStore.notifyApps(context);
    }

    public static boolean isAllowed(Context context, String packageName) {
        return allowed(context).contains(packageName);
    }

    /**
     * Setzt die Freigabe. Was im Puffer von einer App liegt, die nicht mehr dabei ist, wird sofort
     * gelöscht — abgewählt heißt auch: was schon da ist, soll weg.
     */
    public static void setAllowed(Context context, Collection<String> packages) {
        Set<String> before = allowed(context);
        Set<String> now = new HashSet<>(packages);
        SettingsStore.setNotifyApps(context, now);
        for (String old : before) {
            if (!now.contains(old)) {
                NotificationBuffer.get(context).deletePackage(old);
            }
        }
    }

    /** Merkt sich, dass diese App eine Meldung mit Betrag geschickt hat — nur den Namen. */
    public static void noteSeen(Context context, String packageName) {
        SharedPreferences prefs = seenPrefs(context);
        Set<String> seen = prefs.getStringSet(KEY_SEEN, Collections.<String>emptySet());
        if (packageName == null || packageName.isEmpty() || seen.contains(packageName)) {
            return;
        }
        Set<String> next = new HashSet<>(seen);
        next.add(packageName);
        prefs.edit().putStringSet(KEY_SEEN, next).apply();
    }

    /**
     * Was die Einstellungen zur Auswahl stellen: gesehene Apps, dazu die freigegebenen — eine
     * Freigabe aus einer eingespielten Sicherung muss sich auch wieder abwählen lassen.
     */
    public static Set<String> candidates(Context context) {
        Set<String> out = new TreeSet<>(
                seenPrefs(context).getStringSet(KEY_SEEN, Collections.<String>emptySet()));
        out.addAll(allowed(context));
        return out;
    }

    /** Beim Ausschalten der Funktion: auch die Liste der gesehenen Apps ist dann weg. */
    public static void clearSeen(Context context) {
        seenPrefs(context).edit().clear().commit();
    }

    private static SharedPreferences seenPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(SEEN_PREFS, Context.MODE_PRIVATE);
    }
}
