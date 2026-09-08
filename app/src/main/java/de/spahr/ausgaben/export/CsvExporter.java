package de.spahr.ausgaben.export;

import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.BookingSplit;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Erzeugt eine kMyMoney-taugliche CSV: Spaltentrenner und Dezimaltrennzeichen folgen den Einstellungen
 * (Standard ';' bzw. ',', Datum TT.MM.JJJJ). Ausgaben werden mit negativem, Einnahmen mit positivem
 * Betrag exportiert.
 */
public class CsvExporter {

    /** Spaltentrenner – aus den Einstellungen (Standard ';'); in {@link #build} gesetzt. */
    private String separator = ";";
    private static final String NEWLINE = "\r\n";

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY);
    private final SimpleDateFormat timestampFormat = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.GERMANY);

    /**
     * Baut den CSV-Inhalt (beginnend mit „Kontentyp:&lt;Konto&gt;" + Leerzeile). Splitbuchungen (≥2
     * Kategorien in {@code splitsMap}) werden je Teil als eigene Zeile geschrieben; Umbuchungen bleiben
     * (als zwei getrennte Buchungen) je eine Zeile. Kopfzeile/Typ in der Sprache aus {@code context}.
     */
    public String build(String account, List<Booking> bookings,
                        Map<Long, List<BookingSplit>> splitsMap, android.content.Context context) {
        android.content.Context ctx = de.spahr.ausgaben.i18n.LocaleManager.localizedContext(context);
        // Beträge im eingestellten Zahlenformat (Dezimalzeichen aus den Einstellungen) ausgeben – ohne
        // Tausendertrennung, damit die CSV parser-/importsicher bleibt.
        de.spahr.ausgaben.settings.MoneyFormat.refresh(context);
        // Spaltentrenner aus den Einstellungen (Standard ';').
        this.separator = new de.spahr.ausgaben.settings.SettingsStore(context).getCsvSeparator();
        String[] header = {
                ctx.getString(de.spahr.ausgaben.R.string.csv_col_date),
                ctx.getString(de.spahr.ausgaben.R.string.csv_col_payee),
                ctx.getString(de.spahr.ausgaben.R.string.csv_col_account),
                ctx.getString(de.spahr.ausgaben.R.string.csv_col_type),
                ctx.getString(de.spahr.ausgaben.R.string.csv_col_amount),
                ctx.getString(de.spahr.ausgaben.R.string.csv_col_note),
                ctx.getString(de.spahr.ausgaben.R.string.csv_col_category)};
        String incomeLabel = ctx.getString(de.spahr.ausgaben.R.string.type_income);
        String expenseLabel = ctx.getString(de.spahr.ausgaben.R.string.type_expense);
        StringBuilder sb = new StringBuilder();
        sb.append(ctx.getString(de.spahr.ausgaben.R.string.csv_account_type_prefix))
                .append(account == null ? "" : account).append(NEWLINE).append(NEWLINE);
        sb.append(joinRow(header)).append(NEWLINE);
        for (Booking b : bookings) {
            String datum = dateFormat.format(new Date(b.createdAt));
            String typ = b.isIncome ? incomeLabel : expenseLabel;
            List<BookingSplit> parts = splitsMap.get(b.id);
            if (parts != null && parts.size() >= 2) {
                for (BookingSplit p : parts) {
                    long signed = b.isIncome ? p.amountCents : -p.amountCents;
                    sb.append(joinRow(new String[]{
                            datum, b.payee, b.account, typ,
                            formatSigned(signed), b.note, p.category
                    })).append(NEWLINE);
                }
            } else {
                sb.append(joinRow(new String[]{
                        datum,
                        b.payee,
                        b.account,
                        typ,
                        formatAmount(b.amountCents, b.isIncome),
                        b.note,
                        b.category
                })).append(NEWLINE);
            }
        }
        return sb.toString();
    }

    /** Erzeugt den Dateinamen mit Zeitstempel, z. B. KMySync-20260629-153012.csv */
    public String buildFileName() {
        return "KMySync-" + timestampFormat.format(new Date()) + ".csv";
    }

    /** Dateiname pro Konto, z. B. Bargeld-20260629-153012.csv (Kontoname bereinigt). */
    public String buildFileName(String account) {
        return sanitize(account) + "-" + timestampFormat.format(new Date()) + ".csv";
    }

    /** Macht einen Kontonamen für einen Dateinamen sicher. */
    private String sanitize(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "Konto";
        }
        String s = name.trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return s.isEmpty() ? "Konto" : s;
    }

    /** Betrag im eingestellten Zahlenformat (ohne Tausendertrennung); Ausgaben negativ. */
    private String formatAmount(long amountCents, boolean isIncome) {
        return formatSigned(isIncome ? amountCents : -amountCents);
    }

    /** Bereits vorzeichenbehafteter Cent-Betrag im eingestellten Zahlenformat (ohne Tausendertrennung). */
    private String formatSigned(long signed) {
        return de.spahr.ausgaben.settings.MoneyFormat.plain(signed);
    }

    private String joinRow(String[] fields) {
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                row.append(separator);
            }
            row.append(quote(fields[i]));
        }
        return row.toString();
    }

    /** RFC-4180-Quoting: nur quoten, wenn Trenner, Anführungszeichen oder Zeilenumbruch enthalten. */
    private String quote(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuote = value.contains(separator)
                || value.contains("\"")
                || value.contains("\n")
                || value.contains("\r");
        if (!needsQuote) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
