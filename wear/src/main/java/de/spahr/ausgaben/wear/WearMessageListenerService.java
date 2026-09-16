package de.spahr.ausgaben.wear;

import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.WearableListenerService;

/**
 * Reagiert auf Data-Layer-Ereignisse des Phones: Löscht das Phone einen {@code /expense/new/<id>}-DataItem
 * (= Buchung verarbeitet), wird der Eintrag aus der Warteschlange entfernt. Empfängt außerdem die aktive
 * Sprache ({@code /language}) und stößt bei Wiederverbindung die Synchronisation an ({@code onPeerConnected})
 * – auch bei geschlossener App, da das System den Service startet.
 */
public class WearMessageListenerService extends WearableListenerService {

    private static final String TAG = "AusgabenWearAck";

    @Override
    public void onDataChanged(@NonNull DataEventBuffer dataEvents) {
        for (DataEvent event : dataEvents) {
            String path = event.getDataItem().getUri().getPath();
            if (path == null) {
                continue;
            }
            if (event.getType() == DataEvent.TYPE_DELETED
                    && path.startsWith(WearPaths.PATH_NEW_PREFIX)) {
                // Phone hat den Eintrag verarbeitet → aus der Warteschlange nehmen.
                String id = path.substring(WearPaths.PATH_NEW_PREFIX.length());
                Log.d(TAG, "verarbeitet (DataItem gelöscht): " + id);
                new PendingStore(this).remove(id);
                sendBroadcast(new Intent(WearPaths.ACTION_PENDING_CHANGED).setPackage(getPackageName()));
            } else if (event.getType() == DataEvent.TYPE_CHANGED
                    && WearPaths.PATH_LANGUAGE.equals(path)) {
                DataMap map = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();
                String code = map.getString("code", "de");
                WearStrings.update(this, code, map.getString("strings", "{}"),
                        map.getBoolean("installModel", false));
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                        androidx.core.os.LocaleListCompat.forLanguageTags(code));
                Log.d(TAG, "Sprache empfangen: " + code);
                sendBroadcast(new Intent(WearPaths.ACTION_LANGUAGE_CHANGED).setPackage(getPackageName()));
            } else if (event.getType() == DataEvent.TYPE_CHANGED
                    && WearPaths.PATH_BALANCE.equals(path)) {
                DataMap map = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();
                BalanceStore.save(this, map.getString("text", ""));
                BalanceStore.saveList(this, map.getString("list", ""));
                Log.d(TAG, "Saldo empfangen");
                sendBroadcast(new Intent(WearPaths.ACTION_BALANCE_CHANGED).setPackage(getPackageName()));
                // Tile ereignisgesteuert aktualisieren (kein Polling).
                androidx.wear.tiles.TileService.getUpdater(this)
                        .requestUpdate(ExpenseTileService.class);
            } else if (event.getType() == DataEvent.TYPE_CHANGED
                    && WearPaths.PATH_PAYEES.equals(path)) {
                // Empfänger mit Standorten: liegen auf der Uhr, damit die Umkreisliste auch ohne
                // Handy steht – unterwegs ist es oft nicht dabei.
                DataMap map = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();
                PayeeStore.save(this, map.getString("list", ""), map.getString("currency", ""));
                Log.d(TAG, "Empfänger empfangen");
            }
        }
    }

    @Override
    public void onPeerConnected(@NonNull Node peer) {
        Log.d(TAG, "onPeerConnected: " + peer.getId());
        WearSync.syncPending(this);
    }
}
