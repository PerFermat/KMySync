package de.spahr.ausgaben.export;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.List;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.ExportLock;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.net.RemoteStorage;
import de.spahr.ausgaben.settings.SettingsStore;
import de.spahr.ausgaben.util.ForegroundGate;
import de.spahr.ausgaben.util.ProgressListener;

/**
 * Der Export in die KMyMoney-Datei als Hintergrundlauf: schreiben, die geschriebene Datei vom Server
 * zurücklesen und daraus alle Konten aktualisieren. Die App bleibt dabei bedienbar.
 *
 * <p>Es läuft höchstens einer, und er gehört der App, nicht einer Ansicht: Wer gerade vorn ist, hängt
 * sich als {@link Observer} an und zeigt Band und Abschlussmeldung. Ist am Ende niemand angehängt,
 * wartet die Meldung auf die nächste Ansicht.</p>
 *
 * <p>Für die Dauer des Laufs hält {@link ExportLock} die Buchungen fest, die es beim Start schon gab.
 * Neue lassen sich anlegen; sie stehen nicht im festgehaltenen Stand und gehen mit dem nächsten Export
 * hinaus.</p>
 */
public final class KmyExportRun {

    /** Wer Band und Meldung zeigt. Alle Aufrufe kommen auf dem Main-Thread. */
    public interface Observer {
        void onProgress(String label, int percent);

        /**
         * @param failed  abgebrochen, nichts wurde geschrieben – die Meldung gehört in einen Dialog
         * @param refresh die Buchungen haben sich geändert, Listen neu laden
         */
        void onEnd(String message, boolean failed, boolean refresh);
    }

    /** So lange wartet der Lauf nach dem Schreiben darauf, dass die App wieder nach vorn kommt. */
    private static final long WARTEN_MS = 10 * 60 * 1000L;

    /** Anteile des Bandes: der Export selbst, das Zurücklesen, das Aktualisieren. */
    private static final int BIS_GESCHRIEBEN = 45;
    private static final int BIS_GELESEN = 55;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // Zustand; gelesen und geschrieben nur auf dem Main-Thread.
    private static boolean running;
    private static String label = "";
    private static int percent;
    private static Observer observer;
    private static Object[] pending;   // {message, failed, refresh} einer noch nicht gezeigten Meldung

    private KmyExportRun() {
    }

    public static boolean isRunning() {
        return running;
    }

    /**
     * Hängt eine Ansicht an. Läuft gerade ein Export, bekommt sie sofort den Stand; liegt eine Meldung
     * eines beendeten Laufs bereit, bekommt sie die.
     */
    public static void attach(Observer o) {
        observer = o;
        if (running) {
            o.onProgress(label, percent);
        } else if (pending != null) {
            Object[] p = pending;
            pending = null;
            o.onEnd((String) p[0], (Boolean) p[1], (Boolean) p[2]);
        }
    }

    public static void detach(Observer o) {
        if (observer == o) {
            observer = null;
        }
    }

    /**
     * Startet den Lauf. Auf dem Main-Thread zu rufen.
     *
     * @return {@code false}, wenn schon einer läuft
     */
    public static boolean start(Context context, final Repository repository,
                                final SettingsStore settings) {
        if (running) {
            return false;
        }
        final Context app = context.getApplicationContext();
        running = true;
        percent = 0;
        label = de.spahr.ausgaben.i18n.LocaleManager.localizedContext(app)
                .getString(R.string.progress_exporting);
        if (observer != null) {
            observer.onProgress(label, percent);
        }
        new Thread(() -> lauf(app, repository, settings), "kmy-export").start();
        return true;
    }

    private static void lauf(Context app, Repository repository, SettingsStore settings) {
        final Context r = de.spahr.ausgaben.i18n.LocaleManager.localizedContext(app);
        try {
            // Erst sperren, dann den Stand festhalten: was der Export gleich liest, kann sich ab jetzt
            // nicht mehr ändern.
            Long max = repository.bookingDao().getMaxId();
            ExportLock.begin(max == null ? 0 : max);

            final KmyExportCoordinator.Outcome o = new KmyExportCoordinator(app, repository, settings)
                    .exportNow((stage, p) -> melde(stage, p));
            if (!o.written) {
                ende(o.message, o.failed, false);
                return;
            }
            melde(r.getString(R.string.kmy_progress_readback), BIS_GESCHRIEBEN);

            // Zurücklesen, was jetzt wirklich auf dem Server liegt. Im Hintergrund drosselt Android das
            // Netz; dann lieber warten, bis die App wieder vorn ist, als einen Fehler zu melden, den es
            // nicht gibt.
            final byte[] zurueck;
            try {
                if (!ForegroundGate.awaitForeground(WARTEN_MS)) {
                    throw new IOException(r.getString(R.string.kmy_result_background));
                }
                final String lesen = r.getString(R.string.kmy_progress_readback);
                ProgressListener gelesen = (done, total) -> melde(lesen,
                        ImportPhase.map(done, total, BIS_GESCHRIEBEN, BIS_GELESEN));
                zurueck = RemoteStorage.from(settings).downloadBytes(o.folder, o.file, gelesen);
            } catch (Exception e) {
                ende(o.message + "\n" + r.getString(R.string.kmy_result_readback_failed, grund(e)),
                        false, true);
                return;
            }

            // Alle Konten, Depots und Planungen aus der eben gelesenen Datei – ohne zweiten Download.
            List<String> konten = repository.accountNamesNow();
            KmyAccountImport.startWithBytes(app, repository, zurueck, konten, konten, true,
                    new KmyAccountImport.Ui() {
                        @Override
                        public ProgressListener phase(String phaseLabel, int from, int to) {
                            return (done, total) -> melde(phaseLabel, BIS_GELESEN
                                    + ImportPhase.map(done, total, from, to) * (100 - BIS_GELESEN) / 100);
                        }

                        @Override
                        public void noMatchingAccount() {
                            ende(o.message, false, true);
                        }

                        @Override
                        public void failed(Exception e) {
                            ende(o.message + "\n"
                                    + r.getString(R.string.kmy_result_refresh_failed, grund(e)), false, true);
                        }

                        @Override
                        public void finished() {
                            ende(o.message + "\n" + r.getString(R.string.kmy_result_refreshed), false, true);
                        }
                    });
        } catch (RuntimeException | Error e) {
            // Was auch geschieht: die Sperre muss sich wieder lösen.
            ende(r.getString(R.string.export_failed, String.valueOf(e)), true, false);
        }
    }

    private static String grund(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    /** Zwischenstand; aus jedem Thread erlaubt. */
    private static void melde(final String stage, final int p) {
        MAIN.post(() -> {
            if (!running) {
                return;
            }
            label = stage;
            percent = Math.max(percent, Math.min(100, p));
            if (observer != null) {
                observer.onProgress(label, percent);
            }
        });
    }

    /** Der Lauf ist zu Ende, gleich wie: Sperre lösen, Meldung zustellen. Aus jedem Thread erlaubt. */
    private static void ende(final String message, final boolean failed, final boolean refresh) {
        ExportLock.end();
        MAIN.post(() -> {
            if (!running) {
                return;
            }
            running = false;
            if (observer != null) {
                observer.onEnd(message, failed, refresh);
            } else {
                pending = new Object[]{message, failed, refresh};
            }
        });
    }
}
