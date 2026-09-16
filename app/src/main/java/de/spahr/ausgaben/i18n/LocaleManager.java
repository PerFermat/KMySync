package de.spahr.ausgaben.i18n;

import android.content.Context;
import android.content.res.Configuration;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.AppDatabase;
import de.spahr.ausgaben.db.Language;
import de.spahr.ausgaben.db.Translation;
import de.spahr.ausgaben.db.TranslationDao;
import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Steuert die Mehrsprachigkeit: seedt beim ersten Start Deutsch/Englisch aus den kompilierten Ressourcen
 * in die DB und lädt die aktive Sprache in den {@link Strings}-Cache. Wird beim App-Start blockierend
 * aufgerufen, damit {@code attachBaseContext} bereits übersetzen kann.
 */
public final class LocaleManager {

    public static final String LANG_DE = "de";
    public static final String LANG_EN = "en";
    public static final String LANG_ES = "es";

    /**
     * Ist die Sprache mit der App ausgeliefert? Diese drei werden bei jedem Start aus den kompilierten
     * Ressourcen neu geseedet; alle anderen stammen aus einer hochgeladenen Datei und leben nur in der DB.
     */
    public static boolean isBuiltIn(String code) {
        return LANG_DE.equals(code) || LANG_EN.equals(code) || LANG_ES.equals(code);
    }

    /**
     * Wear-Texte (Schlüssel, Deutsch, Englisch, Spanisch). Das Phone kann die Wear-Ressourcen nicht lesen,
     * daher hier fest hinterlegt – für DB, Export-Vorlage und die Sprach-Übertragung an die Uhr.
     *
     * <p>Die Liste muß jeden {@code wear_*}-Schlüssel aus {@code wear/src/main/res/values/strings.xml}
     * enthalten: {@code Repository} überträgt genau diese Präfix-Schlüssel an die Uhr, und was hier fehlt,
     * fällt dort auf die gebündelte DE/EN/ES-Ressource zurück – eine hochgeladene Sprache kann es also nie
     * übersetzen. {@code WearStringsCompleteTest} nagelt das fest.
     */
    public static final String[][] WEAR = {
            {"wear_title", "Buchung erfassen", "Record booking", "Registrar apunte"},
            {"wear_type_income", "Einnahme", "Income", "Ingreso"},
            {"wear_type_transfer", "Umbuchung", "Transfer", "Traspaso"},
            {"wear_type_expense", "Ausgabe", "Expense", "Gasto"},
            {"wear_cancel", "Abbrechen", "Cancel", "Cancelar"},
            {"wear_payee_none", "ohne Empfänger", "no payee", "sin destinatario"},
            {"wear_payee_next", "Nächster Empfänger", "Next payee", "Siguiente destinatario"},
            {"wear_switch", "Konto/Ort wechseln", "Switch account/place", "Cambiar cuenta/lugar"},
            {"wear_prompt", "Buchung sagen, z. B. „Frisör 20 Euro\"", "Say a booking, e.g. \"Barber 20 euros\"", "Di un apunte, p. ej. \"peluquería 20 euros\""},
            {"wear_preparing", "Moment…", "One moment…", "Un momento…"},
            {"wear_listening", "Sprich jetzt…", "Speak now…", "Habla ahora…"},
            {"wear_not_understood", "Nicht erkannt", "Not recognized", "No reconocido"},
            {"wear_no_mic", "Mikrofon nötig", "Mic needed", "Se necesita micrófono"},
            {"wear_no_recognizer", "Keine Erkennung", "No recognizer", "Sin reconocimiento"},
            {"wear_offline_number", "Offline – Betrag eingeben", "Offline – enter amount", "Sin conexión – introduce importe"},
            {"wear_number_entry", "Betrag per Ziffern", "Enter amount by digits", "Introduce el importe con el teclado"},
            {"wear_model_downloading", "Offline-Sprachmodell wird geladen…", "Downloading offline speech model…", "Descargando modelo de voz sin conexión…"},
            {"wear_model_hint", "Offline-Sprache für diese Sprache in den Uhr-Einstellungen aktivieren (System → Sprachen & Eingabe).", "Enable offline speech for this language in the watch settings (System → Languages & input).", "Activa el reconocimiento de voz sin conexión para este idioma en los ajustes del reloj (Sistema → Idiomas y entrada)."},
            {"wear_model_unsupported", "Diese Uhr unterstützt keine Offline-Spracherkennung. Ohne Verbindung greift die Zifferneingabe.", "This watch does not support offline speech recognition. The number pad is used when offline.", "Este reloj no admite reconocimiento de voz sin conexión. Se usa el teclado numérico cuando no hay conexión."},
            {"wear_pending", "%1$d offen · %2$s", "%1$d pending · %2$s", "%1$d pendiente(s) · %2$s"},
            {"wear_reason_gps", "GPS", "GPS", "GPS"},
            {"wear_reason_no_phone", "Kein Handy", "No phone", "Sin teléfono"},
            {"wear_reason_sending", "sendet…", "sending…", "enviando…"},
    };

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    private LocaleManager() {
    }

    /** Beim Start blockierend: DB seeden (falls leer) und aktive Sprache in den Cache laden. */
    public static void init(Context context) {
        run(context, true);
    }

    /** Nach einem Sprachwechsel: aktive Sprache neu in den Cache laden. */
    public static void reload(Context context) {
        run(context, false);
    }

    private static void run(Context context, boolean maySeed) {
        final Context app = context.getApplicationContext();
        Future<?> f = EXEC.submit(() -> {
            AppDatabase db = AppDatabase.getInstance(app);
            TranslationDao dao = db.translationDao();
            // Beim Start DE/EN immer neu seeden (REPLACE), damit neue Schlüssel nach App-Updates in der DB
            // und der Export-Vorlage landen. Hochgeladene Sprachen bleiben unberührt.
            if (maySeed) {
                seed(app, dao);
            }
            String lang = new SettingsStore(app).getLanguage();
            Map<String, String> m = new HashMap<>();
            // Für jede Sprache außer Englisch zuerst Englisch als Basis, dann die aktive Sprache darüber →
            // fehlende Übersetzungen erscheinen immer auf Englisch (nie auf einer anderen kompilierten Sprache).
            if (!LANG_EN.equals(lang)) {
                for (TranslationDao.KeyValue kv : dao.getPairs(LANG_EN)) {
                    m.put(kv.key, kv.value);
                }
            }
            for (TranslationDao.KeyValue kv : dao.getPairs(lang)) {
                m.put(kv.key, kv.value);
            }
            Strings.set(m, toLocale(lang));
        });
        try {
            f.get();
        } catch (Exception ignored) {
        }
    }

    private static void seed(Context app, TranslationDao dao) {
        List<Translation> rows = new ArrayList<>();
        Context de = localeContext(app, Locale.GERMAN);
        Context en = localeContext(app, Locale.ENGLISH);
        Context es = localeContext(app, new Locale("es"));
        for (Field field : R.string.class.getFields()) {
            try {
                int id = field.getInt(null);
                String key = field.getName();
                rows.add(new Translation(LANG_DE, key, de.getString(id)));
                rows.add(new Translation(LANG_EN, key, en.getString(id)));
                rows.add(new Translation(LANG_ES, key, es.getString(id)));
            } catch (Exception ignored) {
            }
        }
        for (String[] w : WEAR) {
            rows.add(new Translation(LANG_DE, w[0], w[1]));
            rows.add(new Translation(LANG_EN, w[0], w[2]));
            rows.add(new Translation(LANG_ES, w[0], w[3]));
        }
        dao.insertAll(rows);
        dao.upsertLanguage(new Language(LANG_DE, "Deutsch", "€", SettingsStore.NUMBER_FORMAT_DE_GROUP));
        dao.upsertLanguage(new Language(LANG_EN, "English", "$", SettingsStore.NUMBER_FORMAT_EN_GROUP));
        dao.upsertLanguage(new Language(LANG_ES, "Español", "€", SettingsStore.NUMBER_FORMAT_DE_GROUP));
    }

    private static Context localeContext(Context app, Locale locale) {
        Configuration cfg = new Configuration(app.getResources().getConfiguration());
        cfg.setLocale(locale);
        return app.createConfigurationContext(cfg);
    }

    /**
     * Context in der aktuell gewählten Sprache – für {@code getString(...)} in Hintergrund-Code (z. B.
     * Export), dessen App-Context die Per-App-Sprache erst nach Neustart übernimmt. Liest die Einstellung
     * bei jedem Aufruf, greift also auch nach einem Wechsel zur Laufzeit.
     */
    public static Context localizedContext(Context base) {
        Context cfg = localeContext(base.getApplicationContext(),
                toLocale(new SettingsStore(base).getLanguage()));
        // Zusätzlich die Übersetzungstabelle überlagern (mit Englisch-Fallback), damit auch Hintergrund-/
        // Export-Meldungen hochgeladene Sprachen nutzen statt nur der kompilierten Ressource.
        return LocaleContextWrapper.wrap(cfg);
    }

    public static Locale toLocale(String lang) {
        if (lang == null || lang.isEmpty()) {
            return Locale.ENGLISH;
        }
        return new Locale(lang);
    }
}
