package de.spahr.ausgaben.location;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Hält den zuletzt bekannten Standort, um ihn (nur als Koordinaten) an Buchungsnotizen anzuhängen.
 * Die Ermittlung ist bewusst rein lokal: {@link LocationManager} ohne Google-Play-Dienste, kein
 * Netzwerk, kein externer Geocoder. Weiter als in die Notiz gehen die Koordinaten nicht – mit ihr
 * landen sie beim Synchronisieren in der eigenen KMyMoney-Datei auf dem Speicher des Nutzers, nie
 * bei einem Dritten.
 *
 * <p>{@link #start()} beginnt (mit vorhandener Standort-Berechtigung) mit dem Empfang von Updates,
 * {@link #stop()} beendet ihn. {@link #currentCoordinates()} liefert „lat, lon" oder {@code null}, wenn
 * keine (aktuelle) Position verfügbar ist.</p>
 */
public class LocationTagger {

    /** Fixe, die älter als das sind, gelten nicht mehr als „aktueller" Standort. */
    private static final long MAX_AGE_MS = 10 * 60 * 1000L;

    /** Wird bei jedem neuen Fix aufgerufen (auf dem Thread, der {@link #start()} aufgerufen hat). */
    public interface OnLocationUpdate {
        void onUpdate();
    }

    private final LocationManager manager;
    private Location latest;
    private OnLocationUpdate updateCallback;

    private final LocationListener listener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            latest = location;
            if (updateCallback != null) {
                updateCallback.onUpdate();
            }
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) {
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
        }
    };

    public LocationTagger(Context context) {
        manager = (LocationManager) context.getApplicationContext()
                .getSystemService(Context.LOCATION_SERVICE);
    }

    public void setOnLocationUpdate(OnLocationUpdate callback) {
        this.updateCallback = callback;
    }

    /** Standort-Updates anfordern (Netzwerk + GPS). Ohne Berechtigung wird still nichts getan. */
    public void start() {
        if (manager == null) {
            return;
        }
        requestFrom(LocationManager.NETWORK_PROVIDER);
        requestFrom(LocationManager.GPS_PROVIDER);
    }

    private void requestFrom(String provider) {
        try {
            if (manager.isProviderEnabled(provider)) {
                manager.requestLocationUpdates(provider, 2000L, 5f, listener);
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
            // Keine Berechtigung oder Provider nicht vorhanden → Standort bleibt einfach leer.
        }
    }

    /** Updates abbestellen (z. B. in {@code onPause}). */
    public void stop() {
        if (manager == null) {
            return;
        }
        try {
            manager.removeUpdates(listener);
        } catch (SecurityException ignored) {
        }
    }

    /**
     * Bester aktuell verfügbarer Fix als „lat, lon" (6 Nachkommastellen, Punkt als Dezimaltrenner) oder
     * {@code null}, wenn kein hinreichend aktueller Standort vorliegt.
     */
    @Nullable
    public String currentCoordinates() {
        Location best = latest;
        best = newer(best, lastKnown(LocationManager.GPS_PROVIDER));
        best = newer(best, lastKnown(LocationManager.NETWORK_PROVIDER));
        if (best == null || System.currentTimeMillis() - best.getTime() > MAX_AGE_MS) {
            return null;
        }
        return String.format(Locale.US, "%.6f, %.6f", best.getLatitude(), best.getLongitude());
    }

    @Nullable
    private Location lastKnown(String provider) {
        if (manager == null) {
            return null;
        }
        try {
            return manager.getLastKnownLocation(provider);
        } catch (SecurityException | IllegalArgumentException e) {
            return null;
        }
    }

    private static Location newer(Location a, Location b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return b.getTime() > a.getTime() ? b : a;
    }
}
