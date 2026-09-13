package de.spahr.ausgaben.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/**
 * Kleiner Verbindungs-Check. Dient nur dazu, beim Beleg-Nachladen „offline" von „Datei (noch) nicht da"
 * zu unterscheiden: bei fehlender Verbindung gibt es eine Fehlermeldung, sonst bleibt es beim Hinweis
 * „Wird geladen …" (und einem erneuten Versuch).
 */
public final class Net {

    private Net() {
    }

    /** true, wenn aktuell eine Netzwerkverbindung mit Internet-Fähigkeit besteht. */
    public static boolean isOnline(Context context) {
        ConnectivityManager cm = (ConnectivityManager)
                context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return true; // ohne Auskunft lieber optimistisch – der Download entscheidet dann selbst
        }
        Network network = cm.getActiveNetwork();
        if (network == null) {
            return false;
        }
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    /**
     * true, wenn die aktuelle Verbindung Geld kostet – Mobilfunk oder ein WLAN, das der Nutzer als
     * getaktet gekennzeichnet hat.
     *
     * <p>Im Zweifel {@code false}: Ohne Auskunft stünde die Warnung vor größeren Downloads sonst
     * ständig ohne Grund da, und eine Warnung, die immer kommt, liest niemand mehr.</p>
     */
    public static boolean isMetered(Context context) {
        ConnectivityManager cm = (ConnectivityManager)
                context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        Network network = cm.getActiveNetwork();
        if (network == null) {
            return false;
        }
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }
}
