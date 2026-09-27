package de.spahr.ausgaben.i18n;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Legt sich über einen Context und liefert {@link Resources}, die Texte aus dem {@link Strings}-Katalog der
 * aktiven Sprache zurückgeben. Dadurch werden sowohl {@code @string/…} in Layouts als auch
 * {@code getString(R.string.…)} im Code automatisch übersetzt; fehlt ein Schlüssel, greift die kompilierte
 * Ressource.
 */
public class LocaleContextWrapper extends ContextWrapper {

    private Resources wrapped;

    private LocaleContextWrapper(Context base) {
        super(base);
    }

    public static ContextWrapper wrap(Context base) {
        return new LocaleContextWrapper(base);
    }

    /**
     * Erzeugt {@link Resources}, die Texte aus dem {@link Strings}-Katalog liefern (sonst kompilierte
     * Ressource). Auch von {@code LocalizedActivity.getResources()} genutzt, damit Material-getönte
     * Contexts und die Menü-Inflation die Übersetzungstabelle treffen.
     */
    public static Resources translate(Resources base) {
        return new TranslatedResources(base);
    }

    @Override
    public Resources getResources() {
        if (wrapped == null) {
            wrapped = translate(super.getResources());
        }
        return wrapped;
    }

    /** {@link Resources}, die für bekannte Schlüssel den übersetzten Text liefern. */
    private static final class TranslatedResources extends Resources {

        private static final ConcurrentHashMap<Integer, String> NAME_CACHE = new ConcurrentHashMap<>();

        @SuppressWarnings("deprecation")
        TranslatedResources(Resources base) {
            super(base.getAssets(), base.getDisplayMetrics(), base.getConfiguration());
        }

        @Override
        public CharSequence getText(int id) throws NotFoundException {
            String v = lookup(id);
            return v != null ? v : super.getText(id);
        }

        @Override
        public String getString(int id) throws NotFoundException {
            String v = lookup(id);
            return v != null ? v : super.getString(id);
        }

        @Override
        public String getString(int id, Object... formatArgs) throws NotFoundException {
            String v = lookup(id);
            if (v != null) {
                try {
                    return String.format(Strings.locale(), v, formatArgs);
                } catch (java.util.IllegalFormatException e) {
                    // Eine importierte Sprachdatei kann fehlerhafte Platzhalter enthalten (z.B. „%d"
                    // statt „%s", oder falsch nummerierte „%2$s") – dann lieber die eingebaute
                    // Übersetzung zeigen, als an dieser Stelle abzustürzen. Wichtig: super.getText(id)
                    // statt super.getString(id, formatArgs) – Letzteres ruft intern wieder virtuell
                    // getText(id) auf dieser Klasse auf und landete so erneut bei der kaputten
                    // Übersetzung statt bei der eingebauten Ressource.
                    return String.format(Strings.locale(), super.getText(id).toString(), formatArgs);
                }
            }
            return super.getString(id, formatArgs);
        }

        private String lookup(int id) {
            String name = NAME_CACHE.get(id);
            if (name == null) {
                try {
                    name = getResourceEntryName(id);
                } catch (NotFoundException e) {
                    return null;
                }
                NAME_CACHE.put(id, name);
            }
            return Strings.get(name);
        }
    }
}
