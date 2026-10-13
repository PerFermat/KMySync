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
 * <p>Die Freigabe gilt je Profil; aufgehoben wird, was von einer App kommt, die <b>irgendein</b> Profil
 * freigegeben hat, und jedes Profil sieht nur die seinen. Von allen anderen merkt sich
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

    /** Die Freigabe des aktiven Profils. */
    public static Set<String> allowed(Context context) {
        return SettingsStore.notifyApps(context);
    }

    public static boolean isAllowed(Context context, String packageName) {
        return allowed(context).contains(packageName);
    }

    /** Ob <b>irgendein</b> Profil die App freigegeben hat — die Frage des Dienstes, der kein Profil kennt. */
    public static boolean isAllowedAnywhere(Context context, String packageName) {
        for (Set<String> apps : SettingsStore.notifyAppsByProfile(context).values()) {
            if (apps.contains(packageName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Setzt die Freigabe des aktiven Profils. Was dadurch kein Profil mehr braucht, geht sofort —
     * was ein anderes Profil noch freigegeben hat, bleibt.
     */
    public static void setAllowed(Context context, Collection<String> packages) {
        SettingsStore.setNotifyApps(context, new HashSet<>(packages));
        NotificationBuffer.purgeNow(context);
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

    /**
     * Nimmt eine App aus der Auswahl des aktiven Profils: nicht mehr freigegeben und, wenn auch kein
     * anderes Profil sie hält, nicht mehr angeboten. Schickt sie wieder eine Meldung mit Betrag, steht
     * sie erneut zur Auswahl — vergessen heißt nicht sperren.
     */
    public static void forget(Context context, String packageName) {
        Set<String> allowed = allowed(context);
        allowed.remove(packageName);
        SettingsStore.setNotifyApps(context, allowed);
        if (!isAllowedAnywhere(context, packageName)) {
            SharedPreferences prefs = seenPrefs(context);
            Set<String> seen = new HashSet<>(
                    prefs.getStringSet(KEY_SEEN, Collections.<String>emptySet()));
            seen.remove(packageName);
            prefs.edit().putStringSet(KEY_SEEN, seen).apply();
        }
        NotificationBuffer.purgeNow(context);
    }

    /** Beim Ausschalten der Funktion: auch die Liste der gesehenen Apps ist dann weg. */
    public static void clearSeen(Context context) {
        seenPrefs(context).edit().clear().commit();
    }

    private static SharedPreferences seenPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(SEEN_PREFS, Context.MODE_PRIVATE);
    }
}
