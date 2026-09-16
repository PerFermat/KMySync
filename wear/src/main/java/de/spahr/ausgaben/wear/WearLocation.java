package de.spahr.ausgaben.wear;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.Locale;

/**
 * Bestimmt auf der Uhr den Standort (nur Koordinaten) für eine Buchung. Statt sofort einen evtl. veralteten
 * {@code getLastKnownLocation}-Fix zu nehmen, wartet {@link #resolve} nach der Eingabe bis zu einer Minute
 * auf einen <b>frischen</b> Fix. Gibt es keinen, wird die zuletzt intern gespeicherte Messung genutzt,
 * sofern sie höchstens fünf Minuten alt ist; sonst {@code null} (Buchung ohne Koordinaten).
 *
 * <p>{@link #start()} wärmt den Empfang vor; jede Messung wird in den Prefs zwischengespeichert, damit die
 * „letzte Messung" auch einen kurzen App-Neustart übersteht.</p>
 */
public class WearLocation {

    /** So lange wird nach der Eingabe auf einen frischen Fix gewartet. */
    private static final long FRESH_TIMEOUT_MS = 60_000L;
    /** So alt darf eine zwischengespeicherte Messung als Rückfall höchstens sein. */
    private static final long CACHE_MAX_AGE_MS = 5 * 60_000L;
    /** Ein „frischer" Fix ist höchstens so viel älter als der Start der Auflösung (gegen Alt-Replays). */
    private static final long FRESH_TOLERANCE_MS = 30_000L;
    private static final String PREFS = "wear_location";

    /** Ergebnis der Standortauflösung. */
    public interface Callback {
        void onCoords(@Nullable String coords);
    }

    private final Context app;
    private final LocationManager manager;
    private Location latest;

    private final LocationListener warm = new SimpleListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            setLatest(location);
        }
    };

    public WearLocation(Context context) {
        app = context.getApplicationContext();
        manager = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        latest = loadPersisted();
    }

    /** Empfang vorwärmen (GPS + Netzwerk). Ohne Berechtigung/Provider passiert still nichts. */
    public void start() {
        request(warm);
    }

    public void stop() {
        try {
            if (manager != null) {
                manager.removeUpdates(warm);
            }
        } catch (SecurityException ignored) {
        }
    }

    /**
     * Löst die Koordinaten für eine Buchung auf: wartet bis {@link #FRESH_TIMEOUT_MS} auf einen frischen
     * Fix; sonst zuletzt gespeicherte Messung (falls ≤ {@link #CACHE_MAX_AGE_MS}); sonst {@code null}.
     * Läuft eigenständig (eigener Listener) und übersteht ein Pausieren der Activity.
     */
    public void resolve(Callback cb) {
        if (manager == null || !hasPermission()) {
            cb.onCoords(cachedCoords());
            return;
        }
        final long startT = System.currentTimeMillis();
        final Location[] fresh = {null};
        final LocationListener rl = new SimpleListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                setLatest(location);
                if (location.getTime() >= startT - FRESH_TOLERANCE_MS) {
                    fresh[0] = location;   // ein aktueller Fix (kein alter Replay)
                }
            }
        };
        request(rl);

        final Handler h = new Handler(Looper.getMainLooper());
        final boolean[] done = {false};
        h.post(new Runnable() {
            @Override
            public void run() {
                if (done[0]) {
                    return;
                }
                boolean timeout = System.currentTimeMillis() - startT >= FRESH_TIMEOUT_MS;
                if (fresh[0] != null || timeout) {
                    done[0] = true;
                    try {
                        manager.removeUpdates(rl);
                    } catch (SecurityException ignored) {
                    }
                    cb.onCoords(fresh[0] != null ? format(fresh[0]) : cachedCoords());
                    return;
                }
                h.postDelayed(this, 2000L);
            }
        });
    }

    private void request(LocationListener l) {
        requestFrom(LocationManager.GPS_PROVIDER, l);
        requestFrom(LocationManager.NETWORK_PROVIDER, l);
    }

    private void requestFrom(String provider, LocationListener l) {
        try {
            if (manager != null && manager.isProviderEnabled(provider)) {
                manager.requestLocationUpdates(provider, 2000L, 5f, l);
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
    }

    private void setLatest(Location loc) {
        latest = loc;
        persist(loc);
    }

    /**
     * Der zwischengespeicherte Standort <b>ohne Warten</b> – für die Empfängerliste im Zahlenblock,
     * die sofort dastehen soll. Liefert {@code null}, wenn kein Fix der letzten fünf Minuten
     * vorliegt; dann bleibt die Zeile weg und der Aufrufer fragt später noch einmal.
     */
    @Nullable
    public String currentCoords() {
        String cached = cachedCoords();
        if (cached != null) {
            return cached;
        }
        Location persisted = loadPersisted();
        return persisted == null ? null : format(persisted);
    }

    /** Zuletzt gespeicherte Messung als „lat, lon", falls ≤ 5 Minuten alt; sonst {@code null}. */
    @Nullable
    private String cachedCoords() {
        if (latest != null && System.currentTimeMillis() - latest.getTime() <= CACHE_MAX_AGE_MS) {
            return format(latest);
        }
        return null;
    }

    private static String format(Location l) {
        return String.format(Locale.US, "%.6f, %.6f", l.getLatitude(), l.getLongitude());
    }

    private boolean hasPermission() {
        return ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void persist(Location l) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("lat", Double.toString(l.getLatitude()))
                .putString("lon", Double.toString(l.getLongitude()))
                .putLong("time", l.getTime())
                .apply();
    }

    @Nullable
    private Location loadPersisted() {
        SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long t = p.getLong("time", 0);
        if (t <= 0 || System.currentTimeMillis() - t > CACHE_MAX_AGE_MS) {
            return null;
        }
        try {
            Location l = new Location("cache");
            l.setLatitude(Double.parseDouble(p.getString("lat", "0")));
            l.setLongitude(Double.parseDouble(p.getString("lon", "0")));
            l.setTime(t);
            return l;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** LocationListener mit leeren Standard-Callbacks; nur onLocationChanged wird überschrieben. */
    private abstract static class SimpleListener implements LocationListener {
        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
        @Override public void onProviderEnabled(@NonNull String provider) { }
        @Override public void onProviderDisabled(@NonNull String provider) { }
    }
}
