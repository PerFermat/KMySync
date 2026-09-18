package de.spahr.ausgaben.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.util.Map;
import java.util.Set;

/**
 * Einmalige Übernahme des Server-Passworts aus der alten {@code EncryptedSharedPreferences}-Datei.
 *
 * <h2>Diese Klasse ist zum Wegwerfen gebaut</h2>
 *
 * <p>Sie ist der <b>einzige</b> Ort, an dem die App {@code androidx.security:security-crypto} noch
 * anfasst – und zwar nur lesend. Steht 2.2 an, genügt es, diese Datei zu löschen, den einen Aufruf in
 * {@link SecretStore#open(Context)} zu entfernen und die Abhängigkeit samt Katalogeintrag zu
 * streichen. Deshalb liegt sie getrennt und nicht als Methode in {@link SecretStore}: Eine
 * Abhängigkeit, die man nur an einer Stelle findet, wird auch wieder los.</p>
 *
 * <p>Die Notiz dazu steht in {@code HANDOFF.md}. Ohne sie bliebe eine abgekündigte Bibliothek für
 * immer liegen – das ist der übliche Weg, auf dem so etwas geschieht.</p>
 *
 * <h2>Reihenfolge</h2>
 *
 * <p>Die alte Datei wird <b>erst nach einem erfolgreichen {@code commit()}</b> gelöscht, nicht
 * vorher und nicht nebenher. Bricht etwas dazwischen ab, steht beim nächsten Start noch alles da und
 * die Übernahme läuft erneut. Der umgekehrte Weg – löschen und dann schreiben – kostet im Fehlerfall
 * genau das Passwort, das gerettet werden soll.</p>
 */
final class SecretMigration {

    /** Der Dateiname, unter dem bis 2.0 die Tink-verschlüsselte Ablage lag. */
    private static final String ALT = "ausgaben_secret";

    private SecretMigration() {
    }

    static void uebernehmenFallsNoetig(Context app, SecretStore neu) {
        if (!SecretStore.dateiVorhanden(app, ALT)) {
            return;
        }
        if (!neu.getAll().isEmpty()) {
            // Schon übernommen, nur das Löschen der alten Datei ist beim letzten Mal nicht
            // durchgekommen. Dann jetzt nachholen und nichts überschreiben.
            app.deleteSharedPreferences(ALT);
            return;
        }
        try {
            SharedPreferences alt = alteAblage(app);
            SharedPreferences.Editor editor = neu.edit();
            for (Map.Entry<String, ?> eintrag : alt.getAll().entrySet()) {
                uebertragen(editor, eintrag.getKey(), eintrag.getValue());
            }
            if (editor.commit()) {
                app.deleteSharedPreferences(ALT);
                android.util.Log.i("SecretMigration",
                        "Server-Passwort in die neue Ablage übernommen, alte Datei entfernt");
            }
        } catch (Exception nichtUebernehmbar) {
            // Die alte Datei bleibt liegen. Das ist der bessere der beiden Ausgänge: Sie schadet
            // nicht, und ein späterer Start kann es noch einmal versuchen.
            android.util.Log.w("SecretMigration",
                    "Altes Server-Passwort ließ sich nicht übernehmen – es ist neu einzugeben",
                    nichtUebernehmbar);
        }
    }

    private static SharedPreferences alteAblage(Context app) throws Exception {
        MasterKey masterKey = new MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();
        return EncryptedSharedPreferences.create(
                app,
                ALT,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }

    @SuppressWarnings("unchecked")
    private static void uebertragen(SharedPreferences.Editor editor, String key, Object wert) {
        if (wert instanceof String) {
            editor.putString(key, (String) wert);
        } else if (wert instanceof Boolean) {
            editor.putBoolean(key, (Boolean) wert);
        } else if (wert instanceof Integer) {
            editor.putInt(key, (Integer) wert);
        } else if (wert instanceof Long) {
            editor.putLong(key, (Long) wert);
        } else if (wert instanceof Float) {
            editor.putFloat(key, (Float) wert);
        } else if (wert instanceof Set) {
            editor.putStringSet(key, (Set<String>) wert);
        }
    }
}
