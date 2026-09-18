package de.spahr.ausgaben.settings;

import android.content.Context;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.spahr.ausgaben.i18n.LocaleManager;

/**
 * Zentrale Datumsanzeige für die gesamte App – das Gegenstück zu {@link MoneyFormat} und nach demselben
 * Muster gebaut: statischer Zustand, {@link #refresh(Context)} lädt ihn aus den Einstellungen.
 *
 * <p>Bis 2.0 stand in zwölf Masken ein eigenes {@code new SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)}.
 * Wer die App auf Englisch oder Spanisch stellte, bekam den Text übersetzt, das Datum daneben aber
 * weiter deutsch – in Buchungsliste, Depot, Budget und Kontoauszug gleichermaßen.</p>
 *
 * <h2>Was hier <b>nicht</b> hingehört</h2>
 *
 * <p>Nicht jedes feste Datumsmuster im Quelltext ist ein Fehler, und die folgenden drei sind bewusst
 * fest. Wer sie „vereinheitlicht", macht etwas kaputt:</p>
 *
 * <ul>
 *   <li>{@code CsvExporter} – das CSV-Format ist im Handbuch als deutsch festgeschrieben
 *       (Spaltentrenner «;», Datum TT.MM.JJJJ). Es folgt der Datei, nicht der Anzeige.</li>
 *   <li>{@code TextValues} – dort ist {@code dd.MM.yyyy} ein Eintrag in einer <i>Kandidatenliste</i>
 *       neben {@code MM/dd/yyyy} und {@code dd/MM/yyyy}, mit der eingelesene Texte gedeutet werden.</li>
 *   <li>Zeitstempel wie {@code yyyyMMdd-HHmmss} in Dateinamen – die sollen sortierbar sein, nicht
 *       hübsch.</li>
 * </ul>
 *
 * <p>Ein Wächter in {@code DateFormatsTest} meldet, wenn im {@code ui}-Paket ein neues festes
 * Tagesmuster auftaucht.</p>
 */
public final class DateFormats {

    /** Datum wie es der deutsche Nutzer kennt – hier unverändert, damit sich für ihn nichts ändert. */
    private static final String PATTERN_DE = "dd.MM.yyyy";
    /** Nur der Rückfall ohne Landeskennung; wer ein englisches Gerät hat, erbt dessen Land. */
    private static final String PATTERN_EN = "MM/dd/yyyy";
    private static final String PATTERN_ES = "dd/MM/yyyy";

    private static volatile String pattern = PATTERN_DE;
    private static volatile Locale locale = Locale.GERMANY;

    private DateFormats() {
    }

    /** Lädt das Muster zur eingestellten Sprache (synchron, nur SharedPreferences). */
    public static void refresh(Context context) {
        apply(new SettingsStore(context.getApplicationContext()).getLanguage());
    }

    static void apply(String lang) {
        apply(lang, systemLocale());
    }

    /**
     * Die Sprache des Geräts, <b>mit Land</b> – und ausdrücklich die des Systems, nicht die der App:
     * Stellt der Nutzer die App auf Englisch, wird {@code Locale.getDefault()} zu einem blanken
     * {@code en} ohne Land (siehe {@link LocaleManager#toLocale}), und genau das Land brauchen wir hier.
     */
    private static Locale systemLocale() {
        try {
            return android.content.res.Resources.getSystem().getConfiguration().getLocales().get(0);
        } catch (Exception keinSystemContext) {
            return null;
        }
    }

    /**
     * <h3>Warum das Land mitzählt</h3>
     *
     * <p>Die Sprache allein legt das Zahlendatum nicht fest. Englisch schreibt sich in den USA
     * {@code MM/dd/yyyy}, in Großbritannien, Irland, Australien, Neuseeland, Indien und Südafrika
     * dagegen {@code dd/MM/yyyy}. Die USA sind der Sonderfall, nicht die Regel — und die feste Tabelle
     * traf ausgerechnet ihn. Ein Brite hätte amerikanische Daten gesehen, ohne es zu merken: Der 3.
     * Februar steht in beiden Schreibweisen als {@code 03/02} bzw. {@code 02/03} da, beides ist ein
     * gültiges Datum, nur ein anderes. Ein falsches Format, das nicht auffällt, ist schlimmer als eines,
     * das auffällt.</p>
     *
     * <p>Deshalb: Passt die gewählte Sprache zur Sprache des Geräts, erbt das Datum dessen <b>Land</b>
     * und wir fragen Android nach dem dort üblichen Muster. Der Brite bekommt {@code dd/MM/yyyy}, der
     * Amerikaner {@code MM/dd/yyyy}, ohne dass einer von beiden etwas einstellt. Passt sie nicht (ein
     * Deutscher stellt die App auf Englisch), gibt es kein Land, aus dem sich etwas ableiten ließe —
     * dann greift die Tabelle.</p>
     *
     * <p>Die Tabelle bleibt also stehen, und zwar als Tabelle statt durchgängig abgeleitet: So ist
     * belegbar, dass sich am gewohnten deutschen {@code dd.MM.yyyy} nichts ändert. Im
     * deutschsprachigen Raum tut es das auch über den Geräteweg nicht — Deutschland, Österreich, die
     * Schweiz und Liechtenstein schreiben alle {@code dd.MM.yyyy}; {@code DateFormatsTest} nagelt das
     * fest.</p>
     *
     * @param device Sprache und Land des Geräts, oder {@code null} im Test
     */
    static void apply(String lang, Locale device) {
        locale = LocaleManager.toLocale(lang);
        if (device != null && !device.getCountry().isEmpty()
                && device.getLanguage().equals(locale.getLanguage())) {
            String best = bestPattern(device);
            if (best != null) {
                locale = device;
                pattern = best;
                return;
            }
        }
        switch (lang == null ? "" : lang) {
            case LocaleManager.LANG_EN:
                pattern = PATTERN_EN;
                break;
            case LocaleManager.LANG_ES:
                pattern = PATTERN_ES;
                break;
            case LocaleManager.LANG_DE:
                pattern = PATTERN_DE;
                break;
            default:
                String best = bestPattern(locale);
                pattern = best == null ? PATTERN_DE : best;
                break;
        }
    }

    /** Das ortsübliche Zahlendatum; {@code null}, wenn Android nichts hergibt. */
    private static String bestPattern(Locale l) {
        String best = android.text.format.DateFormat.getBestDateTimePattern(l, "ddMMyyyy");
        return best == null || best.isEmpty() ? null : best;
    }

    /** Das gültige Muster – für Feldhinweise und für alles, was selbst formatieren muss. */
    public static String pattern() {
        return pattern;
    }

    /** Ein Datum in der eingestellten Sprache. */
    public static String date(long millis) {
        return new SimpleDateFormat(pattern, locale).format(new Date(millis));
    }

    /** Datum mit Uhrzeit; die Uhrzeit bleibt 24-stündig, wie überall sonst in der App. */
    public static String dateTime(long millis) {
        return new SimpleDateFormat(pattern + " HH:mm", locale).format(new Date(millis));
    }

    /**
     * Kurzform mit zweistelligem Jahr, für Achsenbeschriftungen in den Diagrammen – dort stehen die
     * Beschriftungen dicht an dicht, und ein vierstelliges Jahr verdrängt die Nachbarn.
     *
     * <p>Die Jahresstellen werden aus dem gültigen Muster abgeleitet statt eigens hinterlegt, damit
     * Kurz- und Langform gar nicht erst auseinanderlaufen können.</p>
     */
    public static String shortDate(long millis) {
        return new SimpleDateFormat(pattern.replaceAll("y+", "yy"), locale).format(new Date(millis));
    }

    /**
     * Nur Tag und Monat, ohne Jahr – für das große Widget, wo eine Zeile drei Buchungen tragen muss
     * und das Jahr bei allen dreien dasselbe ist.
     *
     * <p>Hier fragen wir Android nach dem ortsüblichen Muster, statt es aus {@link #pattern()} zu
     * schneiden. Der Grund ist der deutsche Schlusspunkt: „03.02." gehört dorthin, „03/02/" wäre im
     * Englischen falsch. Wo genau der Trenner wegfällt, weiß ICU besser als eine Regel, die wir uns
     * hier ausdenken.</p>
     */
    public static String dayMonth(long millis) {
        String muster = android.text.format.DateFormat.getBestDateTimePattern(locale, "ddMM");
        if (muster == null || muster.isEmpty()) {
            muster = "dd.MM.";
        }
        return new SimpleDateFormat(muster, locale).format(new Date(millis));
    }

    /**
     * Die Gegenrichtung zu {@link #date(long)} – aus <b>derselben</b> Quelle, damit ein Feld, das ein
     * Datum anzeigt, auch wieder lesen kann, was darin steht. Der Datumsfilter der Buchungsliste hängt
     * daran: Zeigte er englisch an und läse deutsch, wäre der Bereich falsch.
     */
    public static Date parse(String text) throws ParseException {
        return new SimpleDateFormat(pattern, locale).parse(text);
    }
}
