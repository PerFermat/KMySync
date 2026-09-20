package de.spahr.ausgaben.settings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Verwaltet die Definition der Bargeld-Orte – jetzt **pro Konto**: je Konto eine geordnete Ortsliste und
 * ein Standardort. Liegt bewusst außerhalb der Room-Datenbank, damit die Orte „Datenbank zurücksetzen"
 * und Importe überstehen. Die Salden/Bewegungen der Orte stehen separat im Room-Journal {@code place_entry}
 * (dort ebenfalls kontobezogen).
 *
 * <p>Speicherformat (JSON unter {@code accounts_v2}):
 * {@code { "<Konto>": { "p": ["Ort1","Ort2"], "d": "Standardort" }, … } }</p>
 */
public class PlacesStore {

    public static final String NO_PLACE = "ohne Ort";

    static final String PREFS = "ausgaben_places";
    static final String KEY_ACCOUNTS = "accounts_v2";
    private static final String KEY_MIGRATED = "migrated_v2";
    // Alte (globale) Schlüssel – nur noch für die einmalige Migration.
    private static final String KEY_LEGACY_PLACES = "places";
    private static final String KEY_LEGACY_DEFAULT = "default_place";

    private final SharedPreferences prefs;
    private final String profilePrefix;

    public PlacesStore(Context context) {
        Context app = context.getApplicationContext();
        this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.profilePrefix = "p_" + new ProfileManager(app).getActiveProfileId() + "_";
    }

    private String accountsKey() {
        return profilePrefix + KEY_ACCOUNTS;
    }

    // ---- Lesen ----

    /** Geordnete Orte eines Kontos (ohne „ohne Ort"). */
    public List<String> getPlaces(String account) {
        List<String> result = new ArrayList<>();
        JSONObject acc = accountObject(account);
        if (acc != null) {
            JSONArray arr = acc.optJSONArray("p");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String name = arr.optString(i, "").trim();
                    if (!name.isEmpty() && !result.contains(name)) {
                        result.add(name);
                    }
                }
            }
        }
        return result;
    }

    /** Standardort eines Kontos (leer = keiner / „ohne Ort"). */
    public String getDefaultPlace(String account) {
        JSONObject acc = accountObject(account);
        return acc == null ? "" : acc.optString("d", "").trim();
    }

    // ---- Schreiben ----

    public void addPlace(String account, String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            return;
        }
        List<String> places = getPlaces(account);
        if (!places.contains(n)) {
            places.add(n);
            writePlaces(account, places, getDefaultPlace(account));
        }
    }

    public void removePlace(String account, String name) {
        List<String> places = getPlaces(account);
        if (places.remove(name)) {
            String def = getDefaultPlace(account);
            if (name != null && name.equals(def)) {
                def = places.isEmpty() ? "" : places.get(0);
            }
            writePlaces(account, places, def);
        }
    }

    public void renamePlace(String account, String oldName, String newName) {
        String n = newName == null ? "" : newName.trim();
        if (n.isEmpty()) {
            return;
        }
        List<String> places = getPlaces(account);
        int idx = places.indexOf(oldName);
        if (idx >= 0 && !places.contains(n)) {
            places.set(idx, n);
            String def = getDefaultPlace(account);
            if (oldName != null && oldName.equals(def)) {
                def = n;
            }
            writePlaces(account, places, def);
        }
    }

    public void setDefaultPlace(String account, String name) {
        writePlaces(account, getPlaces(account), name == null ? "" : name.trim());
    }

    /** Entfernt alle Orte eines Kontos (beim Löschen des Kontos). */
    public void removeAccount(String account) {
        JSONObject root = root();
        root.remove(account);
        prefs.edit().putString(accountsKey(), root.toString()).apply();
    }

    /**
     * Einmalige Migration der früheren globalen Orte auf das Standardkonto.
     * Muss beim App-Start mit dem aktuellen Standardkonto aufgerufen werden.
     */
    public void migrateLegacyGlobalPlaces(String defaultAccount) {
        if (prefs.getBoolean(KEY_MIGRATED, false)) {
            return;
        }
        String def = defaultAccount == null ? "" : defaultAccount.trim();
        if (!def.isEmpty()) {
            List<String> legacy = new ArrayList<>();
            try {
                JSONArray arr = new JSONArray(prefs.getString(KEY_LEGACY_PLACES, "[]"));
                for (int i = 0; i < arr.length(); i++) {
                    String name = arr.optString(i, "").trim();
                    if (!name.isEmpty() && !legacy.contains(name)) {
                        legacy.add(name);
                    }
                }
            } catch (Exception ignored) {
            }
            String legacyDefault = prefs.getString(KEY_LEGACY_DEFAULT, "").trim();
            if (!legacy.isEmpty() && getPlaces(def).isEmpty()) {
                writePlaces(def, legacy, legacyDefault);
            }
        }
        prefs.edit()
                .putBoolean(KEY_MIGRATED, true)
                .remove(KEY_LEGACY_PLACES)
                .remove(KEY_LEGACY_DEFAULT)
                .apply();
    }

    // ---- intern ----

    private JSONObject root() {
        try {
            return new JSONObject(prefs.getString(accountsKey(), "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private JSONObject accountObject(String account) {
        if (account == null || account.trim().isEmpty()) {
            return null;
        }
        return root().optJSONObject(account.trim());
    }

    /** Auslieferungszustand: alle Orte-Definitionen (je Konto) löschen. */
    @SuppressLint("ApplySharedPref")   // Begruendung an SettingsActivity#finishReset
    public void clearAll() {
        prefs.edit().clear().commit();
    }

    private void writePlaces(String account, List<String> places, String defaultPlace) {
        if (account == null || account.trim().isEmpty()) {
            return;
        }
        try {
            JSONObject root = root();
            JSONObject acc = new JSONObject();
            JSONArray arr = new JSONArray();
            for (String p : places) {
                arr.put(p);
            }
            acc.put("p", arr);
            acc.put("d", defaultPlace == null ? "" : defaultPlace.trim());
            root.put(account.trim(), acc);
            prefs.edit().putString(accountsKey(), root.toString()).apply();
        } catch (Exception schreibenGescheitert) {
            // Scheitert das Schreiben, ist die gerade bearbeitete Ortsliste des Kontos verloren – die
            // Maske hat aber bereits zugeklappt, als sei alles gespeichert. Ein Widerspruch, den der
            // Nutzer allein nicht auflösen kann; hier steht wenigstens, woran es lag.
            android.util.Log.w("PlacesStore",
                    "Ortsliste des Kontos konnte nicht gespeichert werden", schreibenGescheitert);
        }
    }
}
