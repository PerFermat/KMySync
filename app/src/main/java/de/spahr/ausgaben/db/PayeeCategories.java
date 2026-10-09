package de.spahr.ausgaben.db;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Die Kategorien, die zu einem Empfänger gehören – Vorbelegung der ersten Kategoriezeile und
 * Favoritenblock der Auswahlliste im Buchungs-Editor.
 *
 * <p>Gefragt wird in derselben Reihenfolge, in der auch die Sprach-Erfassung ihre Vorlage sucht
 * ({@code AliasResolver.resolvePass}): erst die <b>bevorzugten Aliase</b> (★), dann die
 * <b>Buchungen</b> dieses Empfängers (jüngste zuerst), zuletzt die <b>übrigen Aliase</b>. Der erste
 * Fund ist die Vorbelegung, die weiteren stehen im Block dahinter.</p>
 *
 * <p>Rein rechnend, ohne Android: die Datenbankabfragen macht der Aufrufer.</p>
 */
public final class PayeeCategories {

    /** So viele Kategorien stehen höchstens im Block – wie beim Vorspann der Empfängerliste. */
    public static final int LIMIT = 6;

    private PayeeCategories() {
    }

    /**
     * Die Kategorien dieses Empfängers in der Reihenfolge, in der sie oben stehen sollen – jede mit der
     * Seite, auf der ihre Quelle sie führte.
     *
     * @param aliases Aliase, deren Zielname der Empfänger ist (jüngste zuerst)
     * @param uses    Kategorien seiner Buchungen samt Teilzeilen, jüngste zuerst
     * @param income  {@code true} = Kategorien für Einnahmen, {@code false} = für Ausgaben
     * @param limit   Länge der Liste
     */
    public static List<PayeeCategory> ranked(List<PayeeCorrection> aliases, List<PayeeCategory> uses,
                                             boolean income, int limit) {
        // Reihenfolge der ersten Eintragung zählt, Doppelte fallen weg (Groß-/Kleinschreibung egal).
        Map<String, PayeeCategory> gefunden = new LinkedHashMap<>();
        vonAliasen(gefunden, aliases, income, true);
        if (uses != null) {
            for (PayeeCategory use : uses) {
                if (use != null) {
                    merke(gefunden, use.category, use.isIncome);
                }
            }
        }
        vonAliasen(gefunden, aliases, income, false);

        List<PayeeCategory> out = new ArrayList<>();
        for (PayeeCategory cat : gefunden.values()) {
            if (out.size() >= limit) {
                break;
            }
            out.add(cat);
        }
        return out;
    }

    /** Wie {@link #ranked(List, List, boolean, int)} mit {@link #LIMIT}. */
    public static List<PayeeCategory> ranked(List<PayeeCorrection> aliases, List<PayeeCategory> uses,
                                             boolean income) {
        return ranked(aliases, uses, income, LIMIT);
    }

    /** Nur die Namen – für Aufrufer, denen die Seite gleichgültig ist. */
    public static List<String> rank(List<PayeeCorrection> aliases, List<String> uses,
                                    boolean income, int limit) {
        List<PayeeCategory> mitSeite = null;
        if (uses != null) {
            mitSeite = new ArrayList<>();
            for (String use : uses) {
                mitSeite.add(new PayeeCategory(use == null ? "" : use, null));
            }
        }
        List<String> out = new ArrayList<>();
        for (PayeeCategory cat : ranked(aliases, mitSeite, income, limit)) {
            out.add(cat.category);
        }
        return out;
    }

    /** Wie {@link #rank} mit {@link #LIMIT}. */
    public static List<String> rank(List<PayeeCorrection> aliases, List<String> uses, boolean income) {
        return rank(aliases, uses, income, LIMIT);
    }

    /** Die zur Buchungsart passenden Kategorien der bevorzugten bzw. der übrigen Aliase. */
    private static void vonAliasen(Map<String, PayeeCategory> gefunden, List<PayeeCorrection> aliases,
                                   boolean income, boolean preferred) {
        if (aliases == null) {
            return;
        }
        for (PayeeCorrection a : aliases) {
            if (a == null || a.preferred != preferred) {
                continue;
            }
            merke(gefunden, income ? a.catIncome1 : a.catExpense1,
                    income ? a.catIncome1IsIncome : a.catExpense1IsIncome);
            merke(gefunden, income ? a.catIncome2 : a.catExpense2,
                    income ? a.catIncome2IsIncome : a.catExpense2IsIncome);
        }
    }

    /**
     * Der erste Fund eines Namens bestimmt seinen Platz. Kennt er seine Seite nicht, darf ein späterer
     * gleichnamiger sie nachtragen – der Platz bleibt.
     */
    private static void merke(Map<String, PayeeCategory> gefunden, String category, Boolean isIncome) {
        if (category == null || category.trim().isEmpty()) {
            return;
        }
        String cat = category.trim();
        String key = cat.toLowerCase(Locale.ROOT);
        PayeeCategory schon = gefunden.get(key);
        if (schon == null) {
            gefunden.put(key, new PayeeCategory(cat, isIncome));
        } else if (schon.isIncome == null) {
            schon.isIncome = isIncome;
        }
    }
}
