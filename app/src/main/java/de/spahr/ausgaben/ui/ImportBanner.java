package de.spahr.ausgaben.ui;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.export.ImportPhase;
import de.spahr.ausgaben.util.ProgressListener;

/**
 * Das gelbe Fortschrittsbanner über der Liste – einmal für alle Ansichten, die einen Import anstoßen
 * (Hauptseite, Depot, geplante Buchungen). Vorher stand dieselbe Logik dreimal fast gleich da.
 *
 * <p>Die angezeigte Zahl kommt nicht unmittelbar aus den Meldungen, sondern aus dem
 * {@link ProgressSmoother}: sie läuft weich nach und kriecht weiter, solange nichts gemeldet wird. Der
 * Takt schreibt Text und Zahl nur, wenn sie sich geändert haben – das ersetzt zugleich die frühere
 * Drosselung gegen die 8-KB-Meldungen des Downloads.</p>
 *
 * <p>{@link #set} und {@link #phase} dürfen aus jedem Thread gerufen werden; die Oberfläche rührt nur
 * der Takt an.</p>
 */
public final class ImportBanner {

    private final View banner;
    private final ShimmerView shimmer;
    private final TextView status;
    private final TextView percent;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ProgressSmoother smoother = new ProgressSmoother();

    /** Gleichzeitig laufende Importe; das Banner geht erst mit dem letzten wieder weg. */
    private int active;

    private volatile String label = "";
    private String shownLabel;
    private int shownPercent = -1;
    private boolean ticking;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            draw(smoother.tick(SystemClock.uptimeMillis()));
            if (ticking) {
                handler.postDelayed(this, ProgressSmoother.TICK_MS);
            }
        }
    };

    public ImportBanner(View banner, ShimmerView shimmer, TextView status, TextView percent) {
        this.banner = banner;
        this.shimmer = shimmer;
        this.status = status;
        this.percent = percent;
    }

    /** Ein Import beginnt: Banner zeigen, Anzeige auf 0 und den Takt starten. */
    public void start(String startLabel) {
        active++;
        smoother.reset();
        shownPercent = -1;
        shownLabel = null;
        label = startLabel == null ? "" : startLabel;
        if (banner != null) {
            banner.setVisibility(View.VISIBLE);
        }
        if (shimmer != null) {
            shimmer.start();
        }
        if (!ticking) {
            ticking = true;
            handler.post(tick);
        }
    }

    /** Fortschritts-Empfänger für eine Phase (bildet {@code done/total} auf {@code from..to} ab). */
    public ProgressListener phase(String phaseLabel, int from, int to) {
        return (done, total) -> set(phaseLabel, ImportPhase.map(done, total, from, to));
    }

    /**
     * Nur den Text wechseln, die Zahl unberührt lassen – für einen Lauf, der zwischendurch pausiert und
     * danach dort weitermacht, wo er war. Aus jedem Thread erlaubt ({@code label} ist volatile).
     */
    public void label(String phaseLabel) {
        if (phaseLabel != null) {
            label = phaseLabel;
        }
    }

    /** Meldung von Hand – für Phasen, die von sich aus nicht zählen. */
    public void set(String phaseLabel, int p) {
        if (phaseLabel != null) {
            label = phaseLabel;
        }
        smoother.report(p, SystemClock.uptimeMillis());
    }

    /** Fertig: 100 % kurz stehen lassen, dann ausblenden. */
    public void finish() {
        if (banner != null) {
            label = banner.getContext().getString(R.string.import_stage_done);
        }
        smoother.finish();
        draw(100);
        handler.postDelayed(this::finishNow, 600);
    }

    /** Sofort weg – bei Fehlern und Läufen, die nichts zu tun fanden. */
    public void finishNow() {
        active = Math.max(0, active - 1);
        if (active > 0) {
            return;
        }
        ticking = false;
        handler.removeCallbacks(tick);
        if (shimmer != null) {
            shimmer.stop();
        }
        if (banner != null) {
            banner.setVisibility(View.GONE);
        }
    }

    /** Läuft gerade ein Import? */
    public boolean isRunning() {
        return active > 0;
    }

    private void draw(int p) {
        if (p != shownPercent) {
            shownPercent = p;
            if (percent != null) {
                // Über eine Ressource, nicht zusammengesetzt: Ob vor dem Prozentzeichen ein
                // Abstand steht, ist Sache der Sprache.
                percent.setText(percent.getContext().getString(R.string.percent_value, p));
            }
        }
        String l = label;
        if (!l.equals(shownLabel)) {
            shownLabel = l;
            if (status != null) {
                status.setText(l);
            }
        }
    }
}
