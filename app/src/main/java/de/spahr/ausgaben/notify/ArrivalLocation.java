package de.spahr.ausgaben.notify;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import java.util.Locale;
import java.util.concurrent.Executor;

import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Wo das Handy war, als die Benachrichtigung eintraf — für die Suche nach dem Empfänger, wie die
 * Spracheingabe sie über den Standort führt.
 *
 * <p>Der Augenblick zählt: eine Kartenzahlung meldet die Bank binnen Sekunden, verarbeitet wird die
 * Meldung aber erst beim nächsten Aktualisieren, und dann ist man längst woanders. Festgehalten wird
 * der Standort deshalb hier, beim Eintreffen.</p>
 *
 * <p>Das geht nur mit dem Standortzugriff „immer": der Dienst läuft im Hintergrund. Ohne ihn, oder mit
 * ausgeschaltetem GPS-Schalter, gibt es keinen Standort, und alles läuft wie ohne diese Klasse.</p>
 */
final class ArrivalLocation {

    /** Älter darf ein schon bekannter Standort nicht sein, um als „beim Eintreffen" zu gelten. */
    static final long MAX_AGE_MS = 2 * 60 * 1000L;
    /** So lange wird auf eine frische Ortung gewartet. */
    private static final long TIMEOUT_MS = 30 * 1000L;

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
     * Bestimmt den Standort und meldet ihn genau einmal — leer, wenn es keinen gibt. Die Antwort kommt
     * auf {@code executor}.
     */
    static void fetch(final Context context, final Executor executor, final Callback callback) {
        if (!available(context)) {
            executor.execute(() -> callback.onResult(""));
            return;
        }
        final LocationManager manager =
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        try {
            String provider = providerOf(manager);
            if (manager == null || provider == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                // Vor Android 11 gibt es keine einmalige Ortung ohne eigenen Looper; dort gilt, was
                // das System gerade weiß – wenn es frisch genug ist.
                final String known = format(recent(manager));
                executor.execute(() -> callback.onResult(known));
                return;
            }
            final CancellationSignal cancel = new CancellationSignal();
            final boolean[] done = {false};
            final Handler main = new Handler(Looper.getMainLooper());
            final Runnable timeout = () -> {
                synchronized (done) {
                    if (done[0]) {
                        return;
                    }
                    done[0] = true;
                }
                cancel.cancel();
                final String known = format(recent(manager));
                executor.execute(() -> callback.onResult(known));
            };
            main.postDelayed(timeout, TIMEOUT_MS);
            manager.getCurrentLocation(provider, cancel, executor, location -> {
                synchronized (done) {
                    if (done[0]) {
                        return;
                    }
                    done[0] = true;
                }
                main.removeCallbacks(timeout);
                callback.onResult(format(location != null ? location : recent(manager)));
            });
        } catch (SecurityException | IllegalArgumentException e) {
            executor.execute(() -> callback.onResult(""));
        }
    }

    /** Der erste eingeschaltete Anbieter: erst der zusammengeführte, dann Netz, dann Satellit. */
    private static String providerOf(LocationManager manager) {
        if (manager == null) {
            return null;
        }
        String[] order = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new String[]{LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER,
                        LocationManager.GPS_PROVIDER}
                : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER};
        for (String provider : order) {
            try {
                if (manager.getAllProviders().contains(provider) && manager.isProviderEnabled(provider)) {
                    return provider;
                }
            } catch (RuntimeException ignored) {
                // nächster Anbieter
            }
        }
        return null;
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
