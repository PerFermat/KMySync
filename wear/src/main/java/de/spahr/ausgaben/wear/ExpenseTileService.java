package de.spahr.ausgaben.wear;

import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.wear.tiles.ActionBuilders;
import androidx.wear.tiles.ColorBuilders;
import androidx.wear.tiles.DimensionBuilders;
import androidx.wear.tiles.LayoutElementBuilders;
import androidx.wear.tiles.ModifiersBuilders;
import androidx.wear.tiles.RequestBuilders;
import androidx.wear.tiles.ResourceBuilders;
import androidx.wear.tiles.TileBuilders;
import androidx.wear.tiles.TileService;
import androidx.wear.tiles.TimelineBuilders;

import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;
import com.google.common.util.concurrent.ListenableFuture;

/**
 * Wear-Tile („Widget"): zeigt „Buchung erfassen" und die drei Typ-Knöpfe (Einnahme grün, Umbuchung gelb,
 * Ausgabe rot). Ein Tipp auf einen Knopf öffnet die App und startet direkt die Spracheingabe für diesen
 * Typ. Bewusst minimal (Tiles-1.1-Layout-Builder, kein Compose, keine Bild-Ressourcen).
 */
public class ExpenseTileService extends TileService {

    private static final String RESOURCES_VERSION = "1";

    private static final int GREEN = 0xFF2E7D32;
    private static final int YELLOW = 0xFFF9A825;
    private static final int RED = 0xFFC62828;
    private static final int WHITE = 0xFFFFFFFF;

    @Override
    protected ListenableFuture<TileBuilders.Tile> onTileRequest(
            RequestBuilders.TileRequest requestParams) {
        // Wechsel-Knopf gedrückt? → auf die nächste Konto/Ort-Position schalten (vor dem Lesen/Bauen).
        String clicked = requestParams.getState() != null
                ? requestParams.getState().getLastClickableId() : "";
        if ("cycle".equals(clicked)) {
            BalanceStore.advance(this);
        }
        // Aktuellen Saldo aus dem lokalen Data-Layer-Cache lesen (billig, kein Netz), dann bauen.
        return CallbackToFutureAdapter.getFuture(completer -> {
            Wearable.getDataClient(this).getDataItems().addOnCompleteListener(task -> {
                if (task.isSuccessful() && task.getResult() != null) {
                    DataItemBuffer items = task.getResult();
                    try {
                        for (DataItem item : items) {
                            if (WearPaths.PATH_BALANCE.equals(item.getUri().getPath())) {
                                com.google.android.gms.wearable.DataMap m =
                                        DataMapItem.fromDataItem(item).getDataMap();
                                BalanceStore.save(this, m.getString("text", ""));
                                BalanceStore.saveList(this, m.getString("list", ""));
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        items.release();
                    }
                }
                // Innerhalb der Anzeige-Minute nach einem Wechsel-Knopf-Druck immer das gewählte
                // Konto/Ort zeigen; sonst wie in der App den Übertragungs-Hinweis, falls Buchungen
                // noch nicht raus sind.
                if (BalanceStore.isRecentlySelected(this)) {
                    completer.set(buildTile(BalanceStore.get(this)));
                    return;
                }
                java.util.List<PendingEntry> pending = new PendingStore(this).getPending();
                if (pending.isEmpty()) {
                    completer.set(buildTile(BalanceStore.get(this)));
                    return;
                }
                try {
                    Wearable.getNodeClient(this).getConnectedNodes().addOnCompleteListener(nodeTask -> {
                        boolean phoneConnected = nodeTask.isSuccessful() && nodeTask.getResult() != null
                                && !nodeTask.getResult().isEmpty();
                        completer.set(buildTile(pendingText(pending, phoneConnected)));
                    });
                } catch (Exception e) {
                    completer.set(buildTile(pendingText(pending, false)));
                }
            });
            // Was der Adapter im Fehlerfall protokolliert – rein zur Fehlersuche.
            return "onTileRequest";
        });
    }

    /**
     * Übersetzter Hinweistext „Anzahl – Grund" (nur eine Zeile Platz in der Kachel), oder der normale
     * Saldo, wenn nichts offen ist.
     */
    private String pendingText(java.util.List<PendingEntry> pending, boolean phoneConnected) {
        int reason = WearPendingReason.of(pending, phoneConnected);
        String label;
        switch (reason) {
            case WearPendingReason.GPS:
                label = tr("wear_reason_gps", R.string.wear_reason_gps);
                break;
            case WearPendingReason.NO_PHONE:
                label = tr("wear_reason_no_phone", R.string.wear_reason_no_phone);
                break;
            case WearPendingReason.SENDING:
                label = tr("wear_reason_sending", R.string.wear_reason_sending);
                break;
            default:
                return BalanceStore.get(this);
        }
        String format = tr("wear_pending", R.string.wear_pending);
        return String.format(java.util.Locale.getDefault(), format, pending.size(), label);
    }

    private TileBuilders.Tile buildTile(String balance) {

        LayoutElementBuilders.Row buttons = new LayoutElementBuilders.Row.Builder()
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .addContent(typeButton(WearPaths.TYPE_INCOME, GREEN, "+"))
                .addContent(spacerW())
                .addContent(typeButton(WearPaths.TYPE_TRANSFER, YELLOW, "↔"))
                .addContent(spacerW())
                .addContent(typeButton(WearPaths.TYPE_EXPENSE, RED, "−"))
                .build();

        LayoutElementBuilders.Column.Builder contentBuilder = new LayoutElementBuilders.Column.Builder()
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER);

        // Grauer Wechsel-Knopf oberhalb des Titels (mittig), sobald mehrere Konten/Orte vorliegen.
        if (BalanceStore.count(this) >= 2) {
            contentBuilder
                    .addContent(cycleButton())
                    .addContent(new LayoutElementBuilders.Spacer.Builder()
                            .setHeight(DimensionBuilders.dp(6)).build());
        }

        contentBuilder
                .addContent(new LayoutElementBuilders.Text.Builder()
                        .setText(tr("wear_title", R.string.wear_title))
                        .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                                .setColor(ColorBuilders.argb(WHITE))
                                .setSize(DimensionBuilders.sp(15))
                                .build())
                        .build())
                .addContent(new LayoutElementBuilders.Spacer.Builder()
                        .setHeight(DimensionBuilders.dp(10))
                        .build())
                .addContent(buttons);

        // Standardort-Saldo vom Phone unter den Knöpfen (z. B. „Geldbeutel: 70,00 €"); leer → weglassen.
        if (balance != null && !balance.isEmpty()) {
            contentBuilder
                    .addContent(new LayoutElementBuilders.Spacer.Builder()
                            .setHeight(DimensionBuilders.dp(8))
                            .build())
                    .addContent(new LayoutElementBuilders.Text.Builder()
                            .setText(balance)
                            .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                                    .setColor(ColorBuilders.argb(WHITE))
                                    .setSize(DimensionBuilders.sp(12))
                                    .build())
                            .build());
        }
        LayoutElementBuilders.Column content = contentBuilder.build();

        // Auf dem Rundschirm den Inhalt vertikal + horizontal zentrieren (sonst wird der Titel oben
        // vom runden Rand beschnitten).
        LayoutElementBuilders.Box root = new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .addContent(content)
                .build();

        TileBuilders.Tile.Builder tile = new TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setTimeline(new TimelineBuilders.Timeline.Builder()
                        .addTimelineEntry(new TimelineBuilders.TimelineEntry.Builder()
                                .setLayout(new LayoutElementBuilders.Layout.Builder()
                                        .setRoot(root)
                                        .build())
                                .build())
                        .build());

        // Ist eine Nicht-Standard-Position gewählt, die Kachel kurz vor dem Timeout neu anfordern, damit sie
        // automatisch auf Standardkonto/-ort zurückspringt (der Rücklauf-Zeitpunkt greift dann).
        long remaining = BalanceStore.remainingMs(this);
        if (remaining > 0) {
            tile.setFreshnessIntervalMillis(remaining + 500);
        }

        return tile.build();
    }

    /** Grauer, runder Wechsel-Knopf: schaltet Konto/Ort weiter (LoadAction lädt die Kachel neu). */
    private LayoutElementBuilders.LayoutElement cycleButton() {
        ActionBuilders.LoadAction reload = new ActionBuilders.LoadAction.Builder().build();
        return new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.dp(30))
                .setHeight(DimensionBuilders.dp(30))
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                        .setBackground(new ModifiersBuilders.Background.Builder()
                                .setColor(ColorBuilders.argb(0xFF757575))
                                .setCorner(new ModifiersBuilders.Corner.Builder()
                                        .setRadius(DimensionBuilders.dp(15))
                                        .build())
                                .build())
                        .setClickable(new ModifiersBuilders.Clickable.Builder()
                                .setId("cycle")
                                .setOnClick(reload)
                                .build())
                        .build())
                .addContent(new LayoutElementBuilders.Text.Builder()
                        .setText("↻")
                        .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                                .setColor(ColorBuilders.argb(WHITE))
                                .setSize(DimensionBuilders.sp(16))
                                .build())
                        .build())
                .build();
    }

    /** Ein farbiger, runder Knopf mit Symbol, der die App für {@code type} startet. */
    private LayoutElementBuilders.LayoutElement typeButton(String type, int color, String symbol) {
        ActionBuilders.LaunchAction launch = new ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(new ActionBuilders.AndroidActivity.Builder()
                        .setPackageName(getPackageName())
                        .setClassName(WearMainActivity.class.getName())
                        .addKeyToExtraMapping(WearMainActivity.EXTRA_TYPE,
                                new ActionBuilders.AndroidStringExtra.Builder().setValue(type).build())
                        .build())
                .build();

        return new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.dp(48))
                .setHeight(DimensionBuilders.dp(48))
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                        .setBackground(new ModifiersBuilders.Background.Builder()
                                .setColor(ColorBuilders.argb(color))
                                .setCorner(new ModifiersBuilders.Corner.Builder()
                                        .setRadius(DimensionBuilders.dp(24))
                                        .build())
                                .build())
                        .setClickable(new ModifiersBuilders.Clickable.Builder()
                                .setId(type)
                                .setOnClick(launch)
                                .build())
                        .build())
                .addContent(new LayoutElementBuilders.Text.Builder()
                        .setText(symbol)
                        .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                                .setColor(ColorBuilders.argb(WHITE))
                                .setSize(DimensionBuilders.sp(22))
                                .build())
                        .build())
                .build();
    }

    /** Übersetzter Text der aktiven Sprache (vom Phone) oder gebündelte Ressource als Fallback. */
    private String tr(String key, int resId) {
        WearStrings.ensureLoaded(this);
        String v = WearStrings.get(key);
        return v != null ? v : getString(resId);
    }

    private LayoutElementBuilders.Spacer spacerW() {
        return new LayoutElementBuilders.Spacer.Builder()
                .setWidth(DimensionBuilders.dp(8))
                .build();
    }

    @Override
    protected ListenableFuture<ResourceBuilders.Resources> onResourcesRequest(
            RequestBuilders.ResourcesRequest requestParams) {
        return immediate(new ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .build());
    }

    /**
     * Ein Future, das den Wert schon kennt.
     *
     * <p>Hier stand bis 2.1 {@code ResolvableFuture}. Das trägt {@code @RestrictTo(LIBRARY_GROUP_PREFIX)}
     * und ist für androidx-interne Verwendung gedacht — Lint hat das neunmal angemahnt. Der öffentliche
     * Weg aus demselben Artefakt ist {@link CallbackToFutureAdapter}; er tut dasselbe und ist zugesagt.</p>
     */
    private static <T> ListenableFuture<T> immediate(T value) {
        return CallbackToFutureAdapter.getFuture(completer -> {
            completer.set(value);
            return "immediate";
        });
    }
}
