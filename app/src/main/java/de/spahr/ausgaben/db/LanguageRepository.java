package de.spahr.ausgaben.db;

import android.os.Handler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import de.spahr.ausgaben.db.Repository.Callback;

/**
 * Sprachen in der Datenbank: Liste, Export-Vorlage, Import einer hochgeladenen Sprachdatei und die
 * Texte für die Uhr. Kollaborator hinter der {@link Repository}-Fassade; teilt sich deren
 * Hintergrund-Executor und Main-Handler.
 */
class LanguageRepository {

    private final TranslationDao translationDao;
    private final ExecutorService executor;
    private final Handler mainHandler;

    LanguageRepository(TranslationDao translationDao, ExecutorService executor, Handler mainHandler) {
        this.translationDao = translationDao;
        this.executor = executor;
        this.mainHandler = mainHandler;
    }

    void getLanguages(final Callback<List<Language>> callback) {
        executor.execute(() -> {
            final List<Language> result = translationDao.getLanguages();
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /**
     * Baut die JSON-Export-Vorlage (alle Schlüssel mit DE/EN als Referenz). Bei einer hochgeladenen
     * Sprache ist sie mit deren Texten vorbelegt, bei einer eingebauten bleibt sie leer.
     */
    void buildLanguageTemplate(final String lang,
                               final Callback<de.spahr.ausgaben.i18n.TranslationIo.Template> callback) {
        executor.execute(() -> {
            de.spahr.ausgaben.i18n.TranslationIo.Template template;
            try {
                List<TranslationDao.KeyValue> current = null;
                Language language = null;
                if (!de.spahr.ausgaben.i18n.LocaleManager.isBuiltIn(lang)) {
                    current = translationDao.getPairsOrdered(lang);
                    for (Language l : translationDao.getLanguages()) {
                        if (l.code.equals(lang)) {
                            language = l;
                            break;
                        }
                    }
                }
                template = de.spahr.ausgaben.i18n.TranslationIo.buildTemplate(
                        translationDao.getPairsOrdered("de"), translationDao.getPairsOrdered("en"),
                        current, language);
            } catch (Exception e) {
                template = null;
            }
            final de.spahr.ausgaben.i18n.TranslationIo.Template result = template;
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /** Importiert eine (geparste) Sprache in die DB; ersetzt eine bestehende gleichen Codes. */
    void importLanguage(final de.spahr.ausgaben.i18n.TranslationIo.Parsed parsed, final Runnable onDone) {
        executor.execute(() -> {
            List<Translation> rows = new ArrayList<>();
            for (Map.Entry<String, String> e : parsed.values.entrySet()) {
                rows.add(new Translation(parsed.code, e.getKey(), e.getValue()));
            }
            translationDao.deleteTranslations(parsed.code);
            translationDao.insertAll(rows);
            translationDao.upsertLanguage(
                    new Language(parsed.code, parsed.name, parsed.defaultCurrency, parsed.numberFormat));
            if (onDone != null) {
                mainHandler.post(onDone);
            }
        });
    }

    /** Wear-relevante Texte (Schlüssel „wear_*") der Sprache – für die Übertragung an die Uhr. */
    void getWearStrings(final String lang, final Callback<Map<String, String>> callback) {
        executor.execute(() -> {
            Map<String, String> m = new HashMap<>();
            for (TranslationDao.KeyValue kv : translationDao.getPairs(lang)) {
                if (kv.key.startsWith("wear_")) {
                    m.put(kv.key, kv.value);
                }
            }
            final Map<String, String> result = m;
            mainHandler.post(() -> callback.onResult(result));
        });
    }
}
