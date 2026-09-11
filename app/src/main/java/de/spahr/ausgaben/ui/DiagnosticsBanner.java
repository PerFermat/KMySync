package de.spahr.ausgaben.ui;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.net.Diagnostics;

/**
 * Das gelbe Band während „Verbindung testen" – die Entsprechung zum {@link ImportBanner}, nur für eine
 * Schrittkette statt für einen Prozentwert.
 *
 * <p>Warum es das braucht: Eine Prüfung geht Schritt für Schritt über die Leitung, und hakt es
 * wirklich, läuft jeder einzelne in seine Zeitüberschreitung. Vorher blitzte nur ein Hinweis auf, der
 * nach zwei Sekunden weg war; danach stand die Maske still und niemand konnte erkennen, ob noch etwas
 * passiert.</p>
 *
 * <p>In einer Zeile steht deshalb <b>links</b> der zuletzt fertige Schritt mit grünem Haken oder rotem
 * Kreuz und <b>rechts</b> der gerade laufende samt Warte-Symbol. Fortschritt und Ergebnis zugleich:
 * Man sieht, wie weit es gekommen ist und woran es gerade arbeitet.</p>
 *
 * <p>Alle Methoden dürfen aus jedem Thread gerufen werden – die Diagnose läuft im Hintergrund, die
 * Oberfläche rührt nur der {@link Handler} an.</p>
 */
final class DiagnosticsBanner implements Diagnostics.Progress {

    private final View banner;
    private final ShimmerView shimmer;
    private final TextView last;
    private final TextView current;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int okColor;
    private final int failColor;

    /** Sucht sich die Teile selbst; die Kennungen sind in beiden Masken dieselben. */
    DiagnosticsBanner(android.app.Activity activity) {
        this.banner = activity.findViewById(R.id.diagBanner);
        this.shimmer = activity.findViewById(R.id.diagShimmer);
        this.last = activity.findViewById(R.id.diagLast);
        this.current = activity.findViewById(R.id.diagCurrent);
        this.okColor = activity.getColor(R.color.diag_ok);
        this.failColor = activity.getColor(R.color.diag_fail);
        if (shimmer != null) {
            shimmer.setColors(activity.getColor(R.color.import_banner_bg),
                    activity.getColor(R.color.import_banner_shimmer));
        }
    }

    /** Die Prüfung fängt an: Band zeigen, beide Seiten leeren, Schimmer laufen lassen. */
    void start() {
        handler.post(() -> {
            if (banner == null) {
                return;
            }
            last.setText("");
            current.setText("");
            banner.setVisibility(View.VISIBLE);
            if (shimmer != null) {
                shimmer.start();
            }
        });
    }

    @Override
    public void beginning(String label) {
        handler.post(() -> {
            if (current != null) {
                current.setText(label);
            }
        });
    }

    @Override
    public void finished(Diagnostics.Step step) {
        handler.post(() -> {
            if (last != null) {
                last.setText((step.ok ? "✓ " : "✗ ") + step.label);
                last.setTextColor(step.ok ? okColor : failColor);
            }
        });
    }

    /** Fertig – auch im Fehlerfall, sonst bliebe ein Band stehen, das nie wieder weggeht. */
    void stop() {
        handler.post(() -> {
            if (shimmer != null) {
                shimmer.stop();
            }
            if (banner != null) {
                banner.setVisibility(View.GONE);
            }
        });
    }
}
