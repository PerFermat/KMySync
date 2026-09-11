package de.spahr.ausgaben.net;

import java.util.ArrayList;
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

    /**
     * Empfänger des Fortschritts, während die Kette läuft.
     *
     * <p>Eine Prüfung dauert: Jeder Schritt geht über die Leitung, und hakt es wirklich, läuft jeder
     * einzelne in seine Zeitüberschreitung. Ohne Rückmeldung steht die Maske dann minutenlang still
     * und niemand weiß, ob noch etwas passiert. Darf {@code null} sein – im Bericht selbst und in den
     * Tests wird nichts angezeigt.</p>
     *
     * <p>Gerufen wird aus dem Hintergrund-Thread; das Umschalten auf die Oberfläche ist Sache des
     * Empfängers.</p>
     */
    public interface Progress {
        /** Dieser Schritt fängt gerade an. */
        void beginning(String label);

        /** Dieser Schritt ist fertig – mit seinem Ergebnis. */
        void finished(Step step);
    }

    /**
     * Sammelt die Schritte, misst nebenbei die Dauer und meldet beides weiter.
     *
     * <p>Vorher stand {@code t0 = System.currentTimeMillis()} in jeder Diagnose ein gutes Dutzend Mal
     * wiederholt da, und die Beschriftung noch einmal beim Anlegen des Schrittes. Hier steht jede der
     * beiden nur noch einmal je Schritt – und der Fortschritt fällt dabei von selbst ab.</p>
     */
    public static final class Log {

        private final List<Step> steps = new ArrayList<>();
        private final Progress progress;
        private String label = "";
        private long startedAt;

        public Log(Progress progress) {
            this.progress = progress;
        }

        /** Ein Schritt fängt an: Beschriftung merken, Uhr stellen, Anzeige benachrichtigen. */
        public void begin(String label) {
            this.label = label == null ? "" : label;
            this.startedAt = System.currentTimeMillis();
            if (progress != null) {
                progress.beginning(this.label);
            }
        }

        /** Der laufende Schritt hat geklappt. */
        public Step ok(String detail) {
            return add(new Step(label, true, detail, System.currentTimeMillis() - startedAt));
        }

        /** Der laufende Schritt ist gescheitert. */
        public Step fail(String detail) {
            return add(new Step(label, false, detail, System.currentTimeMillis() - startedAt));
        }

        /**
         * Eine Zeile ohne eigene Dauer – für Auskünfte, die nebenbei abfallen (die Adresse selbst, der
         * ausgehandelte Dialekt, eine Umleitung). Sie zu stoppen wäre sinnlos.
         */
        public Step note(String label, boolean ok, String detail) {
            this.label = label == null ? "" : label;
            if (progress != null) {
                progress.beginning(this.label);
            }
            return add(new Step(this.label, ok, detail, -1));
        }

        private Step add(Step step) {
            steps.add(step);
            if (progress != null) {
                progress.finished(step);
            }
            return step;
        }

        /** Die bisher gesammelten Schritte – die Liste selbst, nicht eine Abschrift. */
        public List<Step> steps() {
            return steps;
        }
    }

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
