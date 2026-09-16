package de.spahr.ausgaben.wear;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Empfängt von der Wear-OS-Uhr per Data Layer gesprochene Ausgaben (DataItems unter {@code /expense/new/<id>}),
 * legt daraus über den bestehenden Parser eine Buchung an und löscht anschließend den DataItem – die Löschung
 * bestätigt der Uhr die Verarbeitung (sie räumt ihre Warteschlange).
 *
 * <p>Data Layer statt Message: DataItems werden persistent gehalten und automatisch zugestellt, sobald die
 * Verbindung steht – auch aus dem kalten Zustand. Doppelte Verarbeitung verhindert die mitgesendete ID.</p>
 */
public class ExpenseWearListenerService extends WearableListenerService {

    private static final String TAG = "AusgabenWearListener";
    private static final String PATH_NEW_PREFIX = "/expense/new/";
    private static final String PREFS = "wear_processed";
    private static final String KEY_IDS = "processed_ids";

    /** Interner Broadcast: eine (Uhr-)Buchung wurde angelegt → offene Anzeige aktualisieren.
     *  Die Konstante liegt GMS-frei in {@link WearBridge}, damit MainActivity sie in beiden Flavors nutzt. */
    public static final String ACTION_BOOKINGS_CHANGED = WearBridge.ACTION_BOOKINGS_CHANGED;

    @Override
    public void onDataChanged(@NonNull DataEventBuffer dataEvents) {
        for (DataEvent event : dataEvents) {
            Uri uri = event.getDataItem().getUri();
            String path = uri.getPath();
            if (event.getType() != DataEvent.TYPE_CHANGED || path == null
                    || !path.startsWith(PATH_NEW_PREFIX)) {
                continue; // eigene Löschungen u. a. ignorieren
            }
            handleNew(DataMapItem.fromDataItem(event.getDataItem()).getDataMap(), uri);
        }
    }

    private void handleNew(DataMap map, Uri uri) {
        String id;
        try {
            JSONObject json = new JSONObject(map.getString("payload", "{}"));
            id = json.optString("id", "");
            String text = json.optString("text", "");
            String type = json.optString("type", Repository.VOICE_TYPE_EXPENSE);
            String gps = json.optString("gps", "");
            String account = json.optString("account", "");
            String place = json.optString("place", "");
            Log.d(TAG, "empfangen id=" + id + " type=" + type + " text=" + text + " gps=" + gps
                    + " account=" + account + " place=" + place);
            if (id.isEmpty()) {
                return;
            }
            // Nur einmal verarbeiten; bei erneuter Zustellung nur den DataItem aufräumen.
            if (!isProcessed(id)) {
                if (!text.trim().isEmpty()) {
                    Repository repository = new Repository(this);
                    // Vom Wechsel-Knopf gewähltes Konto hat Vorrang, sonst das Standardkonto.
                    String targetAccount = account != null && !account.isEmpty()
                            ? account : new SettingsStore(this).getDefaultAccount();
                    boolean created = repository.createVoiceBookingBlocking(
                            text, targetAccount, place, type, gps);
                    Log.d(TAG, "Buchung angelegt: " + created);
                    if (created) {
                        // Offene App/MainActivity sofort aktualisieren.
                        sendBroadcast(new Intent(ACTION_BOOKINGS_CHANGED).setPackage(getPackageName()));
                        // Uhr-Buchung ändert ggf. den Standardort-Saldo → an die Uhr zurückspiegeln.
                        BalanceSync.publish(this);
                        // Die Buchung bringt womöglich einen neuen Standort mit – der gehört in die
                        // Umkreisliste der Uhr, sonst fehlt genau der Ort, an dem man gerade steht.
                        PayeeSync.publish(this);
                    }
                }
                markProcessed(id);
            } else {
                Log.d(TAG, "bereits verarbeitet, nur aufräumen");
            }
        } catch (Exception e) {
            Log.w(TAG, "Fehler beim Verarbeiten, DataItem bleibt bestehen", e);
            return;
        }
        // Verarbeiteten DataItem löschen → bestätigt der Uhr die Übertragung.
        Wearable.getDataClient(this).deleteDataItems(uri);
    }

    @Override
    public void onPeerConnected(@NonNull Node peer) {
        // Bei Verbindung mit der Uhr die aktuelle Sprache + Wear-Texte, den Standardort-Saldo und die
        // Empfänger mit Standort senden – Letztere braucht die Uhr, wenn sie später allein unterwegs ist.
        LanguageSync.publish(this);
        BalanceSync.publish(this);
        PayeeSync.publish(this);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private boolean isProcessed(String id) {
        return prefs().getStringSet(KEY_IDS, new HashSet<>()).contains(id);
    }

    private void markProcessed(String id) {
        Set<String> ids = new HashSet<>(prefs().getStringSet(KEY_IDS, new HashSet<>()));
        ids.add(id);
        prefs().edit().putStringSet(KEY_IDS, ids).apply();
    }
}
