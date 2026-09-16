package de.spahr.ausgaben.wear;

import android.content.Context;

/**
 * FOSS-Variante (F-Droid) ohne Google Play Services: keine Wear-Kopplung über die Data-Layer-API, daher
 * ist das Veröffentlichen der Empfänger-Standorte hier ein bewusster No-op. Gleiche Signatur wie die
 * {@code full}-Variante, damit Aufrufer in beiden Flavors unverändert bauen.
 */
public final class PayeeSync {

    private PayeeSync() {
    }

    /** No-op: In der GMS-freien Variante gibt es keine Uhr-Anbindung. */
    public static void publish(Context context) {
        // absichtlich leer (kein Wear Data Layer in der foss-Variante)
    }
}
