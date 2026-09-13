package de.spahr.ausgaben.util;

/**
 * Ob die App gerade im Vordergrund ist – und eine Möglichkeit, darauf zu warten.
 *
 * <p>Gedacht für lange Läufe, die viel vom Netz holen. Android drosselt das Netz einer App im
 * Hintergrund, und ein Lauf, der davon nichts weiß, hält jede Datei für unerreichbar und hakt sie ab.
 * Genau das ist dem Beleg-Export widerfahren: von 238 Belegen kamen 39 an, der Rest galt als fehlend,
 * obwohl jede Datei auf dem Server lag. Mit dieser Schranke legt sich der Lauf stattdessen schlafen
 * und macht weiter, sobald der Nutzer zurückkommt.</p>
 *
 * <p>Gefüttert wird sie vom Activity-Zähler in {@code AusgabenApp} – dieselbe Buchführung, die auch die
 * App-Sperre benutzt. Bei einer Drehung fällt der Zähler zwischen alter und neuer Activity kurz auf
 * null; ein wartender Lauf hält dann einen Wimpernschlag inne und läuft sofort weiter, sobald die neue
 * Ansicht steht. Eine eigene Ausnahme für den Konfigurationswechsel braucht es dafür nicht.</p>
 *
 * <p>Ohne Android-Bezug, damit das Zusammenspiel von Warten und Wecken im JVM-Test festgehalten werden
 * kann.</p>
 */
public final class ForegroundGate {

    private static final Object LOCK = new Object();

    /** Anzahl sichtbarer Activities. Kaltstart: 0, also Hintergrund, bis die erste Ansicht steht. */
    private static int visible;

    private ForegroundGate() {
    }

    /** Eine Activity ist sichtbar geworden. Weckt alles, was auf den Vordergrund wartet. */
    public static void enter() {
        synchronized (LOCK) {
            visible++;
            LOCK.notifyAll();
        }
    }

    /** Eine Activity ist nicht mehr sichtbar. */
    public static void leave() {
        synchronized (LOCK) {
            if (visible > 0) {
                visible--;
            }
        }
    }

    /** Ist gerade mindestens eine Ansicht der App sichtbar? */
    public static boolean isForeground() {
        synchronized (LOCK) {
            return visible > 0;
        }
    }

    /**
     * Wartet, bis die App im Vordergrund ist.
     *
     * <p><b>Blockierend</b> – nur von einem Hintergrund-Thread aufrufen.</p>
     *
     * @param timeoutMs Obergrenze des Wartens. Unbegrenzt zu warten wäre verlockend, aber falsch:
     *                  Einen Hintergrundprozess ohne sichtbare Benachrichtigung darf Android jederzeit
     *                  abräumen, und dann bliebe eine halb geschriebene Datei zurück. Wer wartet, muss
     *                  also auch aufgeben können.
     * @return {@code true}, wenn die App (wieder) im Vordergrund ist; {@code false} bei Ablauf der Frist
     *         oder wenn der wartende Thread unterbrochen wurde
     */
    public static boolean awaitForeground(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (LOCK) {
            while (visible <= 0) {
                long rest = deadline - System.currentTimeMillis();
                if (rest <= 0) {
                    return false;
                }
                try {
                    LOCK.wait(rest);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    /** Nur für Tests: setzt den Zähler zurück. */
    static void resetForTest() {
        synchronized (LOCK) {
            visible = 0;
            LOCK.notifyAll();
        }
    }
}
