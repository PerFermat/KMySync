package de.spahr.ausgaben.net;

import java.util.List;

/**
 * Die gemeinsame Form eines Diagnose-Berichts – für SMB ({@code SmbDiagnostics}) und für
 * WebDAV/Nextcloud ({@link WebDavDiagnostics}) dieselbe.
 *
 * <p>Hintergrund: Eine Meldung wie „Server nicht erreichbar" sagt aus der Ferne nichts. Deshalb hält
 * jeder Schritt fest, <b>was</b> gemacht wurde, <b>ob</b> es geklappt hat und – im Fehlerfall – den
 * <b>rohen Grund</b> samt Statuscode. Der fertige Bericht ist zum Verschicken gedacht und enthält
 * <b>nie</b> das Passwort und den Benutzernamen nur als „gesetzt"/„leer".</p>
 */
public final class Diagnostics {

    /** Ein Schritt der Kette: Beschriftung, Ergebnis, Dauer und im Fehlerfall der rohe Grund. */
    public static final class Step {
        public final String label;
        public final boolean ok;
        public final String detail;
        public final long millis;

        /** @param millis Dauer in Millisekunden; negativ heißt „keine Dauer anzeigen". */
        public Step(String label, boolean ok, String detail, long millis) {
            this.label = label;
            this.ok = ok;
            this.detail = detail == null ? "" : detail;
            this.millis = millis;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(ok ? "✓ " : "✗ ").append(label);
            if (!detail.isEmpty()) {
                sb.append(": ").append(detail);
            }
            if (millis >= 0) {
                sb.append(" (").append(millis).append(" ms)");
            }
            return sb.toString();
        }
    }

    /**
     * Warum ausgerechnet das Umbenennen dazugehört – die Erklärung, die vorher in einem eigenen
     * Rechte-Dialog stand und jetzt dort steht, wo sie gebraucht wird: an der gescheiterten Zeile.
     */
    public static final String UMBENENNEN_NOETIG =
            "beim Übertragen schreibt die App die KMyMoney-Datei erst vollständig unter einem"
                    + " Zwischennamen und läßt sie dann einhängen – nur so kann ein Abbruch die Datei"
                    + " nicht halb überschreiben. Der Ordner braucht dafür schreiben, umbenennen und"
                    + " löschen; ohne Umbenennen ist ein Export nicht möglich";

    private Diagnostics() {
    }

    /** Kompletter Bericht als Text – genau das, was der Nutzer kopiert und schickt. */
    public static String report(String title, List<Step> steps) {
        StringBuilder sb = new StringBuilder(title).append('\n');
        for (Step s : steps) {
            sb.append(s).append('\n');
        }
        return sb.toString().trim();
    }

    /** Erster Fehlerschritt oder {@code null}, wenn alles geklappt hat. */
    public static Step firstFailure(List<Step> steps) {
        for (Step s : steps) {
            if (!s.ok) {
                return s;
            }
        }
        return null;
    }

    /**
     * Rohtext einer Ausnahme, einzeilig und gekürzt – der Statuscode ist hier das Wertvolle, ein
     * seitenlanger Stapelauszug wäre es nicht.
     */
    public static String shorten(String raw) {
        String s = raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
