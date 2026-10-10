package de.spahr.ausgaben.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Bringt die aus einer Abrechnung gelesenen Teilbeträge mit den Kategorien der letzten Buchung
 * zusammen — die Schaltstelle zwischen dem, was in der Abrechnung steht, und dem, was in KMyMoney
 * gebucht wird.
 *
 * <p>Die Abrechnung nennt Beträge, keine Kategorien: dass „Kapitalertragsteuer" unter
 * {@code Steuern:Kapitalertragsteuer} zu buchen ist, weiß nur der Nutzer, und bei einem anderen
 * Papier kann es eine andere sein. Die Zuordnung stammt deshalb immer aus der letzten Buchung
 * derselben Art; hier wird sie auf die neuen Beträge übertragen.</p>
 *
 * <p>Drei Quellen, in dieser Reihenfolge:</p>
 * <ol>
 *   <li>Die <b>Erkennungsregel</b>, wenn dort eine Kategorie eingetragen oder gelernt wurde. Sie ist
 *   festgelegt worden und gewinnt deshalb.</li>
 *   <li>Die <b>Beschriftung</b> — „Kapitalertragsteuer" findet die Kategorie wieder, unter der ein
 *   Betrag dieser Zeile zuletzt gebucht wurde, gleich in welcher Reihenfolge die Zeilen stehen und
 *   auch dann, wenn eine davon in dieser Abrechnung fehlt.</li>
 *   <li>Die <b>Stelle</b> in der Reihe. Darauf läuft es hinaus, wo es keine Beschriftungen gibt: ein
 *   fest programmierter Bankleser kennt keine, und die zuletzt gebuchten Zeilen tragen erst welche,
 *   seit es die Aufteilung gibt.</li>
 * </ol>
 *
 * <p>Was auch geschieht: die Beträge der Zeilen ergeben zusammen den Betrag, der darüber steht. Eine
 * Buchung, die sich nicht ausgleicht, nimmt KMyMoney nicht an — und das fiele erst dort auf.</p>
 */
public final class CategorySplits {

    private CategorySplits() {
    }

    /** Eine Kategoriezeile: was gebucht wird, wie viel, und woher der Betrag stammt. */
    public static final class Part {
        public final String category;
        public final long cents;
        /** Die Beschriftung aus der Abrechnung; leer, wenn es keine gibt. */
        public final String label;
        /**
         * Die Seite von {@link #category} ({@code true} = Einnahme-, {@code false} =
         * Ausgabekategorie); {@code null}, solange sie niemand kennt.
         */
        public final Boolean categoryIsIncome;

        public Part(String category, long cents, String label) {
            this(category, cents, label, null);
        }

        public Part(String category, long cents, String label, Boolean categoryIsIncome) {
            this.category = category == null ? "" : category;
            this.cents = cents;
            this.label = label == null ? "" : label;
            this.categoryIsIncome = this.category.trim().isEmpty() ? null : categoryIsIncome;
        }

        /** Die Seite als Zahl für Intent, Parcel und gemerkten Zustand: −1 unbekannt, 0 Ausgabe, 1 Einnahme. */
        public static int alsZahl(Boolean seite) {
            return seite == null ? -1 : seite ? 1 : 0;
        }

        public static Boolean ausZahl(int zahl) {
            return zahl < 0 ? null : Boolean.valueOf(zahl == 1);
        }
    }

    /**
     * Gibt jeder Zeile die Seite ihrer Kategorie zurück. Beim Zuordnen wandern die Kategorien als Namen
     * von Zeile zu Zeile; die Seite steht bei der Zeile, von der der Name stammt – in der Abrechnung
     * ({@code found}, sie geht vor) oder in der letzten Buchung ({@code known}).
     */
    private static List<Part> mitSeiten(List<Part> out, List<Part> found, List<Part> known) {
        for (int i = 0; i < out.size(); i++) {
            Part part = out.get(i);
            if (part.categoryIsIncome != null || part.category.trim().isEmpty()) {
                continue;
            }
            Boolean seite = seiteVon(part.category, found);
            if (seite == null) {
                seite = seiteVon(part.category, known);
            }
            if (seite != null) {
                out.set(i, new Part(part.category, part.cents, part.label, seite));
            }
        }
        return out;
    }

    private static Boolean seiteVon(String category, List<Part> parts) {
        if (parts != null) {
            for (Part part : parts) {
                if (part != null && part.categoryIsIncome != null
                        && part.category.trim().equalsIgnoreCase(category.trim())) {
                    return part.categoryIsIncome;
                }
            }
        }
        return null;
    }

    /**
     * Die Zeilen, mit denen die Maske aufmacht.
     *
     * @param found       was aus der Abrechnung gelesen wurde, in deren Reihenfolge
     * @param totalCents  die Summe, die darüber im Betragsfeld steht
     * @param known       die Kategoriezeilen der letzten Buchung derselben Art (nur Kategorie und
     *                    Beschriftung zählen, nicht ihre Beträge)
     * @return Zeilen, deren Beträge zusammen immer {@code totalCents} ergeben
     */
    public static List<Part> match(List<Part> found, long totalCents, List<Part> known) {
        List<Part> out = new ArrayList<>();
        if (totalCents == 0) {
            return out;
        }
        List<Part> beträge = withoutEmpty(found);
        if (beträge.isEmpty() || sum(beträge) != totalCents) {
            // Nichts gelesen – oder eine Aufteilung, die nicht auf die Summe aufgeht und der man
            // deshalb nicht trauen darf. Dann ist der ganze Betrag ein einziger Teil, und die
            // Zuordnung fällt auf die erste bekannte Kategorie zurück.
            beträge = new ArrayList<>();
            beträge.add(new Part("", totalCents, ""));
        }
        return mitSeiten(allLabeled(beträge) ? byLabel(beträge, known) : byOrder(beträge, known),
                found, known);
    }

    /**
     * Die Zeilen, mit denen die <b>Maske</b> aufmacht — {@link #match} und, solange es nichts
     * zuzuordnen gibt, wenigstens die bekannten Kategorien ohne Betrag.
     *
     * <p>Der Unterschied zählt bei der Eingabe von Hand: dort steht noch kein Betrag da, wenn die
     * letzte Buchung nachgeschlagen ist. Gäbe es dann keine Zeile, bliebe die Kategorie aus, und man
     * müsste sie jedes Mal neu heraussuchen — obwohl die App längst weiß, welche es ist. Ihr Betrag
     * folgt von selbst, sobald der Betrag darüber eingetippt ist.</p>
     */
    public static List<Part> rows(List<Part> found, long totalCents, List<Part> known) {
        List<Part> zugeordnet = match(found, totalCents, known);
        if (!zugeordnet.isEmpty()) {
            return zugeordnet;
        }
        List<Part> out = new ArrayList<>();
        for (Part part : known) {
            if (!part.category.trim().isEmpty()) {
                out.add(new Part(part.category, 0, part.label, part.categoryIsIncome));
            }
        }
        return out;
    }

    /**
     * Jeder Betrag bekommt die Kategorie, die unter derselben Beschriftung gebucht wurde — und wo das
     * nicht trifft, die an seiner Stelle.
     *
     * <p>Der Rückfall auf die Stelle ist kein Notbehelf, sondern der Regelfall beim Übergang: die
     * zuletzt gebuchten Zeilen tragen noch gar keine Beschriftung, wenn sie aus einer fest
     * programmierten Bank stammen oder aus der Zeit vor der Aufteilung. Ohne ihn bliebe die Kategorie
     * leer, obwohl sie danebensteht. Beim nächsten Buchen ist die Beschriftung dann mit gespeichert,
     * und der Rückfall wird nicht mehr gebraucht.</p>
     *
     * <p>Die Beschriftung behält dabei den Vorrang, und keine Kategorie wird zweimal vergeben: sonst
     * verschöbe ein einziger Fehlschlag die ganze übrige Zuordnung.</p>
     */
    private static List<Part> byLabel(List<Part> found, List<Part> known) {
        String[] kategorien = new String[found.size()];
        boolean[] vergeben = new boolean[known.size()];
        for (int i = 0; i < found.size(); i++) {
            Part part = found.get(i);
            if (!part.category.trim().isEmpty()) {
                kategorien[i] = part.category;
                continue;
            }
            int at = indexOfLabel(known, part.label);
            if (at >= 0) {
                kategorien[i] = known.get(at).category;
                vergeben[at] = true;
            }
        }
        List<Part> out = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            String kategorie = kategorien[i] == null ? anDerStelle(known, vergeben, i) : kategorien[i];
            out.add(new Part(kategorie, found.get(i).cents, found.get(i).label));
        }
        return out;
    }

    /** Die noch freie Kategorie an dieser Stelle, sonst die erste freie überhaupt. */
    private static String anDerStelle(List<Part> known, boolean[] vergeben, int at) {
        if (at < known.size() && !vergeben[at]) {
            vergeben[at] = true;
            return known.get(at).category;
        }
        for (int i = 0; i < known.size(); i++) {
            if (!vergeben[i]) {
                vergeben[i] = true;
                return known.get(i).category;
            }
        }
        return "";
    }

    private static int indexOfLabel(List<Part> known, String label) {
        if (label.trim().isEmpty()) {
            return -1;
        }
        for (int i = 0; i < known.size(); i++) {
            if (known.get(i).label.equalsIgnoreCase(label)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Die Kategorie, die der Teil selbst mitbringt — sonst die aus der Historie.
     *
     * <p>Bringt er eine mit, steht sie in der Erkennungsregel: dort ist sie festgelegt worden, von
     * Hand oder beim Lernen. Die Historie ist demgegenüber nur ein Schluss aus dem, was zuletzt
     * gebucht wurde, und tritt deshalb zurück.</p>
     */
    private static String eigeneOder(Part part, String ausDerHistorie) {
        return part.category.trim().isEmpty() ? ausDerHistorie : part.category;
    }

    /**
     * Der i-te Betrag bekommt die i-te Kategorie; was darüber hinausgeht, wird der ersten
     * zugeschlagen.
     *
     * <p>So bleibt die Summe in jedem Fall gewahrt, und der Anfang ist von selbst richtig: gibt es
     * bisher nur eine Kategorie — der Zustand vor der ersten Aufteilung —, landen alle Steuerzeilen
     * in dieser einen Zeile mit dem vollen Betrag. Wer sie dann von Hand aufteilt, hat beim nächsten
     * Mal so viele Kategorien, wie die Abrechnung Zeilen hat.</p>
     */
    private static List<Part> byOrder(List<Part> found, List<Part> known) {
        if (known.isEmpty()) {
            return one("", sum(found));
        }
        List<Part> out = new ArrayList<>();
        long überhang = 0;
        for (int i = 0; i < found.size(); i++) {
            String ausDerHistorie = i < known.size() ? known.get(i).category : "";
            String kategorie = eigeneOder(found.get(i), ausDerHistorie);
            if (!kategorie.isEmpty()) {
                out.add(new Part(kategorie, found.get(i).cents, found.get(i).label));
            } else {
                überhang += found.get(i).cents;
            }
        }
        if (out.isEmpty()) {
            // Keine einzige Kategorie ließ sich zuordnen — dann steht der Betrag ungeteilt da und
            // wartet darauf, von Hand zugewiesen zu werden.
            return one("", sum(found));
        }
        if (überhang != 0) {
            Part erste = out.get(0);
            out.set(0, new Part(erste.category, erste.cents + überhang, erste.label));
        }
        return out;
    }

    private static List<Part> one(String category, long cents) {
        List<Part> out = new ArrayList<>();
        out.add(new Part(category, cents, ""));
        return out;
    }

    /** Beträge von 0 tragen nichts bei und brächten nur eine leere Kategoriezeile mit sich. */
    private static List<Part> withoutEmpty(List<Part> parts) {
        List<Part> out = new ArrayList<>();
        if (parts == null) {
            return out;
        }
        for (Part part : parts) {
            if (part != null && part.cents != 0) {
                out.add(part);
            }
        }
        return out;
    }

    /**
     * Ob sich diese Zeilen über die Beschriftung zuordnen lassen.
     *
     * <p>Eine Zeile, die ihre Kategorie schon mitbringt, braucht dafür keine: die feste Ordergebühr
     * etwa steht in keiner Zeile der Abrechnung und hat deshalb auch keine Beschriftung — sie weiß
     * aber von der Regelseite her, wohin sie gebucht gehört. Ohne diese Nachsicht fiele die ganze
     * Aufteilung ihretwegen auf die Reihenfolge zurück.</p>
     */
    private static boolean allLabeled(List<Part> parts) {
        for (Part part : parts) {
            if (part.label.trim().isEmpty() && part.category.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public static long sum(List<Part> parts) {
        long total = 0;
        for (Part part : parts) {
            total += part.cents;
        }
        return total;
    }
}
