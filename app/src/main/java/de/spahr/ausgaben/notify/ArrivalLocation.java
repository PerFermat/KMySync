package de.spahr.ausgaben.notify;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationListener;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

import de.spahr.ausgaben.location.Geo;

import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Wo das Handy war, als die Benachrichtigung eintraf — für die Suche nach dem Empfänger, wie die
 * Spracheingabe sie über den Standort führt.
 *
 * <p>Der Augenblick zählt: eine Kartenzahlung meldet die Bank binnen Sekunden, verarbeitet wird die
 * Meldung aber erst beim nächsten Aktualisieren, und dann ist man längst woanders. Festgehalten wird
 * der Standort deshalb hier, beim Eintreffen.</p>
 *
 * <p>Gewartet wird wie auf der Uhr ({@code WearLocation}): bis zu {@link #WAIT_MS} auf eine frische
 * Ortung, mit laufenden Updates aller Anbieter. Eine einmalige Abfrage beim zusammengeführten
 * Anbieter lieferte oft sofort nichts, ohne dass GPS überhaupt anlief. Solange gewartet wird, hält
 * der Puffer die Meldung vom Aktualisieren zurück ({@link #HOLD_MS}).</p>
 *
 * <p>Das geht nur mit dem Standortzugriff „immer": der Dienst läuft im Hintergrund. Ohne ihn, oder mit
 * ausgeschaltetem GPS-Schalter, gibt es keinen Standort, und alles läuft wie ohne diese Klasse.</p>
 */
final class ArrivalLocation {

    /** So lange wird höchstens auf eine frische Ortung gewartet – eine Minute, wie auf der Uhr. */
    static final long WAIT_MS = 60_000L;
    /** So lange hält der Puffer eine Meldung höchstens zurück: die Wartezeit und etwas Reserve. */
    static final long HOLD_MS = WAIT_MS + 5_000L;
    /** Ein Fix ist frisch, wenn er höchstens so viel älter ist als der Start der Ortung. */
    static final long FRESH_TOLERANCE_MS = 30_000L;
    /** Älter darf ein schon bekannter Standort als Rückfall nicht sein (wie auf der Uhr). */
    static final long MAX_AGE_MS = 5 * 60 * 1000L;
    /** Abstand der Updates während der Wartezeit. */
    private static final long INTERVAL_MS = 2_000L;

    interface Callback {
        /** @param coords „lat, lon" wie in der GPS-Notiz einer Buchung; nie {@code null}, höchstens leer */
        void onResult(String coords);
    }

    private ArrivalLocation() {
    }

    /** Ob der Dienst den Standort überhaupt lesen darf und soll. */
    static boolean available(Context context) {
        if (!new SettingsStore(context).isGpsEnabled()) {
            return false;
        }
        boolean foreground = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
                || granted(context, Manifest.permission.ACCESS_COARSE_LOCATION);
        boolean background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        return foreground && background;
    }

    private static boolean granted(Context context, String permission) {
        return ContextCompat.checkSelfPermission(context, permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Die Fixe einer Ortung: welcher frisch ist, welcher der beste, und wann es genug ist. Ohne
     * Android-Dienste, damit sich die Auswahl prüfen lässt.
     */
    static final class Fixes {
        private final long startMs;
        private Location best;

        Fixes(long startMs) {
            this.startMs = startMs;
        }

        /**
         * Nimmt einen Fix entgegen. Alte (Wiederholungen eines früheren Standorts) zählen nicht.
         *
         * @return ob die Ortung damit fertig ist: frisch und so genau wie der Umkreis, in dem
         *         Empfänger am Standort gesucht werden
         */
        boolean offer(Location l) {
            if (l == null || l.getTime() < startMs - FRESH_TOLERANCE_MS) {
                return false;
            }
            if (best == null || moreAccurate(l, best)) {
                best = l;
            }
            return l.hasAccuracy() && l.getAccuracy() <= Geo.RADIUS_M;
        }

        /** Der beste frische Fix bisher; {@code null}, wenn keiner kam. */
        Location best() {
            return best;
        }

        private static boolean moreAccurate(Location a, Location b) {
            if (!a.hasAccuracy()) {
                return false;
            }
            return !b.hasAccuracy() || a.getAccuracy() < b.getAccuracy();
        }
    }

    /**
     * Bestimmt den Standort und meldet ihn genau einmal — leer, wenn es keinen gibt. Die Antwort kommt
     * auf {@code executor}, spätestens nach {@link #WAIT_MS}.
     */
    static void fetch(final Context context, final Executor executor, final Callback callback) {
        if (!available(context)) {
            executor.execute(() -> callback.onResult(""));
            return;
        }
        LocationManager manager =
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            executor.execute(() -> callback.onResult(""));
            return;
        }
        new Handler(Looper.getMainLooper()).post(
                new Run(manager, executor, callback, System.currentTimeMillis()));
    }

    /** Eine Ortung: hört auf alle Anbieter, bis ein Fix genügt oder die Zeit um ist. Nur auf dem Main-Looper. */
    private static final class Run implements LocationListener, Runnable {
        private final LocationManager manager;
        private final Executor executor;
        private final Callback callback;
        private final Fixes fixes;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final Runnable timeout = this::finish;
        private boolean done;

        Run(LocationManager manager, Executor executor, Callback callback, long startMs) {
            this.manager = manager;
            this.executor = executor;
            this.callback = callback;
            this.fixes = new Fixes(startMs);
        }

        @Override
        public void run() {
            // Was das System gerade weiß, kann schon frisch genug sein – dann ist nichts zu warten.
            for (String provider : manager.getAllProviders()) {
                try {
                    if (fixes.offer(manager.getLastKnownLocation(provider))) {
                        finish();
                        return;
                    }
                } catch (SecurityException | IllegalArgumentException ignored) {
                    // nächster Anbieter
                }
            }
            int listening = 0;
            for (String provider : providers()) {
                try {
                    manager.requestLocationUpdates(provider, INTERVAL_MS, 0f, this,
                            Looper.getMainLooper());
                    listening++;
                } catch (SecurityException | IllegalArgumentException ignored) {
                    // nächster Anbieter
                }
            }
            if (listening == 0) {
                finish();
                return;
            }
            main.postDelayed(timeout, WAIT_MS);
        }

        /** Alle eingeschalteten Anbieter: der zusammengeführte (ab Android 12), GPS und Netz. */
        private List<String> providers() {
            List<String> out = new ArrayList<>();
            String[] order = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? new String[]{LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER,
                            LocationManager.NETWORK_PROVIDER}
                    : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER};
            for (String provider : order) {
                try {
                    if (manager.getAllProviders().contains(provider)
                            && manager.isProviderEnabled(provider)) {
                        out.add(provider);
                    }
                } catch (RuntimeException ignored) {
                    // nächster Anbieter
                }
            }
            return out;
        }

        @Override
        public void onLocationChanged(Location location) {
            if (!done && fixes.offer(location)) {
                finish();
            }
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            // Vor Android 11 Pflicht; ohne Bedeutung hier.
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }

        private void finish() {
            if (done) {
                return;
            }
            done = true;
            main.removeCallbacks(timeout);
            try {
                manager.removeUpdates(this);
            } catch (SecurityException ignored) {
                // nichts mehr abzumelden
            }
            Location best = fixes.best() != null ? fixes.best() : recent(manager);
            final String coords = format(best);
            executor.execute(() -> callback.onResult(coords));
        }
    }

    /** Der jüngste dem System bekannte Standort, wenn er höchstens {@link #MAX_AGE_MS} alt ist. */
    private static Location recent(LocationManager manager) {
        if (manager == null) {
            return null;
        }
        Location best = null;
        for (String provider : manager.getAllProviders()) {
            try {
                Location l = manager.getLastKnownLocation(provider);
                if (l != null && (best == null
                        || l.getElapsedRealtimeNanos() > best.getElapsedRealtimeNanos())) {
                    best = l;
                }
            } catch (SecurityException | IllegalArgumentException ignored) {
                // nächster Anbieter
            }
        }
        if (best == null) {
            return null;
        }
        long ageMs = (SystemClock.elapsedRealtimeNanos() - best.getElapsedRealtimeNanos()) / 1_000_000L;
        return ageMs <= MAX_AGE_MS ? best : null;
    }

    /** Dieselbe Schreibweise wie {@code LocationTagger.currentCoordinates()}. */
    static String format(Location location) {
        return location == null ? ""
                : String.format(Locale.US, "%.6f, %.6f", location.getLatitude(), location.getLongitude());
    }
}
