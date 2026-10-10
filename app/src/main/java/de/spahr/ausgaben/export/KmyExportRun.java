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
            final RemoteStorage storage = RemoteStorage.from(settings);
            final byte[] gelesen;
            try {
                if (!ForegroundGate.awaitForeground(WARTEN_MS)) {
                    throw new IOException(r.getString(R.string.kmy_result_background));
                }
                final String lesen = r.getString(R.string.kmy_progress_readback);
                ProgressListener fortschritt = (done, total) -> melde(lesen,
                        ImportPhase.map(done, total, BIS_GESCHRIEBEN, BIS_GELESEN));
                gelesen = storage.downloadBytes(o.folder, o.file, fortschritt);
            } catch (Exception e) {
                // Geschrieben ist geschrieben; dass sich nicht nachsehen ließ, ändert daran nichts.
                o.nachziehen.run();
                ende(o.message + "\n" + r.getString(R.string.kmy_result_readback_failed, grund(e)),
                        false, true);
                return;
            }

            // Kam die Datei defekt an, liegt danach wieder der Stand von vor dem Export auf dem
            // Server – und nichts gilt als exportiert.
            KmyRuecklesen.Ergebnis e = KmyRuecklesen.pruefe(storage, o.folder, o.file, gelesen,
                    o.uploaded, o.vorher, raw -> KmyDocument.alsXml(app, raw),
                    new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                            .format(new java.util.Date()));
            if (e.ausgang != KmyRuecklesen.Ausgang.LESBAR) {
                boolean zurueckgespielt = e.ausgang == KmyRuecklesen.Ausgang.WIEDERHERGESTELLT;
                vermerkeDefekt(app, o, e.bytes, zurueckgespielt);
                String sicherung = KmyExportCoordinator.BACKUP_DIR + "/" + o.backupName;
                if (zurueckgespielt) {
                    o.verwerfen.run();
                    ende(r.getString(R.string.kmy_result_defect_restored, o.file), true, false);
                } else {
                    // Der Vermerk des Laufs bleibt: Steht die Datei doch richtig da, erkennt der
                    // nächste Export die Buchungen wieder, statt sie doppelt anzulegen.
                    ende(r.getString(R.string.kmy_result_defect_not_restored, o.file, sicherung)
                            + (e.grund.isEmpty() ? "" : "\n" + e.grund), true, false);
                }
                return;
            }
            o.nachziehen.run();
            final byte[] zurueck = e.bytes;

            final String meldung = o.message + vergleiche(app, r, o, zurueck);

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
                            ende(meldung, false, true);
                        }

                        @Override
                        public void failed(Exception e) {
                            ende(meldung + "\n"
                                    + r.getString(R.string.kmy_result_refresh_failed, grund(e)), false, true);
                        }

                        @Override
                        public void finished() {
                            ende(meldung + "\n" + r.getString(R.string.kmy_result_refreshed), false, true);
                        }
                    });
        } catch (RuntimeException | Error e) {
            // Was auch geschieht: die Sperre muss sich wieder lösen.
            ende(r.getString(R.string.export_failed, String.valueOf(e)), true, false);
        }
    }

    /**
     * Hält fest, was sich in der Datei wirklich geändert hat: der Stand vor dem Export gegen das, was
     * eben vom Server zurückkam, Zeile für Zeile ({@link ExportDiff}). Abgelegt unter dem Namen der
     * Sicherung dieses Exports; aufgeräumt wie die Sicherungen.
     *
     * <p>Beiwerk: Scheitert der Vergleich, ist der Export trotzdem gelungen und es geht weiter.</p>
     *
     * @return ein Zusatz für die Abschlussmeldung, leer im Normalfall
     */
    private static String vergleiche(Context app, Context r, KmyExportCoordinator.Outcome o,
                                     byte[] zurueck) {
        try {
            // Eine Datenbank wird als das verglichen, was sie ist: Tabelle gegen Tabelle. Über ihr
            // XML-Abbild fiele alles weg, was die Abbildung nicht liest – Zähler, Salden –, und
            // zwischen Datei und Anzeige läge Fachlogik.
            ExportDiff d = KmySqlite.istSqlite(o.vorher) && KmySqlite.istSqlite(zurueck)
                    ? ExportDiff.ausTabellen(TabellenDiff.von(app, o.vorher, zurueck))
                    : ExportDiff.von(o.oldXml, KmyDocument.alsXml(app, zurueck));
            o.oldXml = null;   // mehrere Megabyte, die ab hier niemand mehr braucht
            d.zeit = System.currentTimeMillis();
            d.datei = o.file;
            d.abweichung = !java.util.Arrays.equals(zurueck, o.uploaded);
            ExportDiffStore store = new ExportDiffStore(ExportDiffStore.ordnerFuer(app.getFilesDir(),
                    new de.spahr.ausgaben.settings.ProfileManager(app).getActiveProfileId()));
            store.speichere(o.backupName, d);
            store.raeumeAuf(o.file, KmyBackups.KEEP);
            return d.abweichung ? "\n" + r.getString(R.string.kmy_result_differs) : "";
        } catch (Exception | OutOfMemoryError e) {
            android.util.Log.w("KmyExport", "Vergleich nach dem Export nicht möglich", e);
            return "";
        }
    }

    /**
     * Legt unter „Änderungen" ab, dass die Datei defekt ankam: der Stand vor dem Export gegen das, was
     * sich von ihr noch entpacken ließ. Eine Datenbank hat keinen Text, der sich so zeigen ließe; dort
     * steht nur der Hinweis.
     */
    private static void vermerkeDefekt(Context app, KmyExportCoordinator.Outcome o, byte[] defekt,
                                       boolean wiederhergestellt) {
        try {
            ExportDiff d = KmySqlite.istSqlite(o.vorher) ? ExportDiff.ausTabellen(
                    new java.util.ArrayList<>())
                    : ExportDiff.von(o.oldXml, KmyDocument.gunzipSoweitMoeglich(defekt));
            o.oldXml = null;
            d.zeit = System.currentTimeMillis();
            d.datei = o.file;
            d.abweichung = true;
            d.defekt = true;
            d.wiederhergestellt = wiederhergestellt;
            ExportDiffStore store = new ExportDiffStore(ExportDiffStore.ordnerFuer(app.getFilesDir(),
                    new de.spahr.ausgaben.settings.ProfileManager(app).getActiveProfileId()));
            store.speichere(o.backupName, d);
            store.raeumeAuf(o.file, KmyBackups.KEEP);
        } catch (Exception | OutOfMemoryError e) {
            android.util.Log.w("KmyExport", "Defekt ließ sich nicht vermerken", e);
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
