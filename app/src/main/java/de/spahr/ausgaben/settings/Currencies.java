package de.spahr.ausgaben.settings;

import android.content.Context;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import de.spahr.ausgaben.db.Account;
import de.spahr.ausgaben.db.AppDatabase;

/**
 * Hält je Konto das Währungskennzeichen (aus der DB) plus die globale Standardwährung, damit die
 * Betrags-Formatierung überall das richtige Kennzeichen anhängen kann. Wird bei Bedarf aktualisiert.
 */
public final class Currencies {

    private static volatile Map<String, String> byAccount = Collections.emptyMap();
    private static volatile String defaultCurrency = "€";

    private Currencies() {
    }

    /** Lädt die Konto-Währungen + Standardwährung neu. Läuft im Hintergrund (DB-Zugriff), von überall
     * aufrufbar. */
    public static void refresh(Context context) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            String def = new SettingsStore(app).getCurrency();
            Map<String, String> map = new HashMap<>();
            try {
                for (Account a : AppDatabase.getInstance(app).accountDao().getAll()) {
                    if (a.currency != null && !a.currency.trim().isEmpty()) {
                        map.put(a.name, a.currency.trim());
                    }
                }
            } catch (Exception kontenNichtLesbar) {
                // Der folgenreichste der stillen Fehler: Bleibt die Tabelle leer, hängt die App an
                // *jeden* Betrag der App die Standardwährung – auch an die eines Fremdwährungskontos.
                // Falsche Währungszeichen an richtigen Zahlen sieht man nicht sofort, und wer es sieht,
                // sucht den Fehler beim Konto statt bei einer gescheiterten Abfrage.
                android.util.Log.w("Currencies",
                        "Konto-Währungen nicht lesbar – alle Beträge zeigen die Standardwährung",
                        kontenNichtLesbar);
            }
            byAccount = map;
            defaultCurrency = def;
        }).start();
    }

    /** Währungskennzeichen des Kontos oder die Standardwährung. */
    public static String forAccount(String account) {
        String c = account == null ? null : byAccount.get(account);
        return c != null && !c.isEmpty() ? c : defaultCurrency;
    }

    public static String getDefault() {
        return defaultCurrency;
    }
}
