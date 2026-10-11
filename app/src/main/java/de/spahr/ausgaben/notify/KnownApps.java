package de.spahr.ausgaben.notify;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * Die Apps, für die irgendein Profil eine Vorlage hat — nur ihre Paketnamen, je Profil eine Menge.
 *
 * <p>Gebraucht vom Dienst im Hintergrund: er sieht nur die Datenbank des gerade aktiven Profils und
 * ließe sonst die Meldungen für das andere nach 24 Stunden als „unbekannt" verfallen. Die Vorlagen
 * selbst stehen in der Datenbank des Profils; das hier ist ihr Schatten und lässt sich aus ihnen
 * jederzeit neu aufbauen.</p>
 */
public final class KnownApps {

    private static final String PREFS = "notify_known_apps";

    private final SharedPreferences prefs;

    public KnownApps(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Die Vereinigung über alle Profile. */
    public Set<String> all() {
        Set<String> out = new HashSet<>();
        for (Object value : prefs.getAll().values()) {
            if (value instanceof Set) {
                for (Object name : (Set<?>) value) {
                    out.add(String.valueOf(name));
                }
            }
        }
        return out;
    }

    public void put(String profileId, Set<String> packages) {
        prefs.edit().putStringSet(profileId, new HashSet<>(packages)).apply();
    }

    public void remove(String profileId) {
        prefs.edit().remove(profileId).apply();
    }

    public void clear() {
        prefs.edit().clear().commit();
    }
}
