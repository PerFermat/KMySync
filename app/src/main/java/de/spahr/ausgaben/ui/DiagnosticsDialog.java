package de.spahr.ausgaben.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.Callable;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.net.Diagnostics;
import de.spahr.ausgaben.net.Diagnostics.Step;
import de.spahr.ausgaben.net.WebDavDiagnostics;
import de.spahr.ausgaben.net.smb.SmbDiagnostics;

/**
 * „Verbindung testen": prüft die ganze Kette und zeigt das Ergebnis – gemeinsam genutzt von
 * Einstellungen und Erststart-Assistent und von beiden Serverarten, weil die Frage „geht es, und wenn
 * nicht, warum?" überall dieselbe ist (beim Erststart sogar die wichtigere).
 *
 * <p>Es gab hier lange zwei Knöpfe: einen für die schnelle Ja/Nein-Antwort und einen für den
 * ausführlichen Bericht. Sie prüften dasselbe und waren nebeneinander eher Rätsel als Auswahl.
 * Geblieben ist einer, der sich nach dem Ergebnis richtet: <b>Geht alles</b>, genügt ein Satz – die
 * Technik will dann niemand sehen. <b>Hakt etwas</b>, steht sofort der ganze Bericht da, samt
 * Statuscodes und „Bericht kopieren", denn genau dann braucht man ihn.</p>
 *
 * <p>Der Bericht enthält kein Passwort und keinen Benutzernamen und ist zum Verschicken gedacht.</p>
 */
final class DiagnosticsDialog {

    private DiagnosticsDialog() {
    }

    /** SMB: prüft im Hintergrund und zeigt das Ergebnis. */
    static void runSmb(Activity activity, DiagnosticsBanner banner, String url, String user,
                       String password, String folder, String file) {
        run(activity, banner, R.string.diag_title_smb, SmbDiagnostics.TITLE,
                () -> SmbDiagnostics.run(url, user, password, folder, file, banner));
    }

    /** WebDAV/Nextcloud: dasselbe, nur die andere Kette. */
    static void runWebDav(Activity activity, DiagnosticsBanner banner, String url, String user,
                          String password, boolean nextcloudLayout, String folder, String file) {
        run(activity, banner, R.string.diag_title_webdav, WebDavDiagnostics.TITLE,
                () -> WebDavDiagnostics.run(url, user, password, nextcloudLayout, folder, file,
                        banner));
    }

    private static void run(Activity activity, DiagnosticsBanner banner, int title,
                            String reportTitle, Callable<List<Step>> work) {
        banner.start();
        new Thread(() -> {
            final String report;
            final boolean ok;
            List<Step> steps;
            try {
                steps = work.call();
            } catch (Exception e) {
                // Die Prüfung selbst darf nicht wortlos scheitern – dann wäre gar nichts gewonnen.
                steps = null;
            } finally {
                // Auch im Fehlerfall: ein Band, das bleibt, ginge nie wieder weg.
                banner.stop();
            }
            if (steps == null) {
                ok = false;
                report = reportTitle + "\n✗ Die Prüfung ließ sich nicht durchführen.";
            } else {
                ok = Diagnostics.firstFailure(steps) == null;
                report = Diagnostics.report(reportTitle, steps);
            }
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing()) {
                    if (ok) {
                        showSuccess(activity, title, report);
                    } else {
                        showReport(activity, title, report);
                    }
                }
            });
        }, "verbindungspruefung").start();
    }

    /** Alles gut: ein Satz genügt – den Bericht gibt es auf Wunsch trotzdem. */
    private static void showSuccess(Activity activity, int title, String report) {
        new AppDialog(activity)
                .setTitle(title)
                .setMessage(R.string.conn_ok)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(R.string.diag_show_report,
                        (d, w) -> showReport(activity, title, report))
                .show();
    }

    /** Etwas hakt (oder der Bericht wurde angefordert): die ganze Kette, zum Kopieren. */
    private static void showReport(Activity activity, int title, String report) {
        new AppDialog(activity)
                .setTitle(title)
                .setMessage(report)
                .setPositiveButton(R.string.diag_copy, (d, w) -> copy(activity, report))
                .setNegativeButton(R.string.dialog_close, null)
                .show();
    }

    private static void copy(Activity activity, String report) {
        ClipboardManager clipboard =
                (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Diagnose", report));
            Toast.makeText(activity, R.string.diag_copied, Toast.LENGTH_SHORT).show();
        }
    }
}
