package de.spahr.ausgaben.export;

import de.spahr.ausgaben.net.RemotePath;
import android.content.Context;
import android.text.TextUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.net.RemoteStorage;
import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Schreibt die noch nicht exportierten Buchungen direkt in eine KMyMoney-Datei auf Nextcloud:
 * herunterladen → entpacken → Transaktionen einfügen → Original sichern → gepackt zurückschreiben →
 * geschriebene Buchungen als exportiert markieren. Meldet den Fortschritt über {@link Listener}.
 */
public class KmyExportCoordinator {

    public interface Listener {
        /** Auf dem Main-Thread: Zwischenschritt (z. B. „Lade KMyMoney-Datei…"). */
        void onProgress(String stage);

        /** Auf dem Main-Thread: Endergebnis. */
        void onComplete(String message, boolean refreshNeeded);
    }

    /** Unterordner neben der .kmy, in dem die Sicherungen vor jedem Export abgelegt werden. */
    private static final String BACKUP_DIR = "Backup";

    private final Repository repository;
    private final SettingsStore settings;
    private final Context appContext;
    private final SimpleDateFormat tsFormat = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.GERMANY);

    public KmyExportCoordinator(Context context, Repository repository, SettingsStore settings) {
        this.repository = repository;
        this.settings = settings;
        this.appContext = context.getApplicationContext();
    }

    /** Context in der aktuell gewählten Sprache für die Meldungen. */
    private Context res() {
        return de.spahr.ausgaben.i18n.LocaleManager.localizedContext(appContext);
    }

    public void exportUnexported(Listener listener) {
        repository.executor().execute(() -> {
            Context r = res();
            if (!settings.hasRemoteConfig()) {
                complete(listener, r.getString(de.spahr.ausgaben.R.string.export_no_config), false);
                return;
            }
            String path = settings.getKmyPath();
            if (path.isEmpty()) {
                complete(listener, r.getString(de.spahr.ausgaben.R.string.kmy_path_missing), false);
                return;
            }
            String folder = RemotePath.folderOf(path);
            String file = RemotePath.fileOf(path);

            List<Booking> bookings = repository.bookingDao().getUnexported();
            // Nach dem Export geänderte Buchungen: ihre Transaktion wird in der Datei geändert, nicht neu
            // angelegt (siehe KmyExporter.build).
            List<Booking> edited = repository.bookingDao().getEdited();
            List<de.spahr.ausgaben.db.KmyPendingDelete> pendingDeletes =
                    repository.kmyPendingDeleteDao().getAll();
            // Erledigte/übersprungene geplante Buchungen: die zugehörige KMyMoney-Regel wird weitergestellt.
            List<de.spahr.ausgaben.db.ScheduledAdvance> advances =
                    repository.scheduledAdvanceDao().getAll();
            // In der App erfasste Depot-Bewegungen: sie werden als eigene Transaktion geschrieben; ihre
            // Geldbuchung hängt daran und ist deshalb aus getUnexported() ausgenommen.
            List<de.spahr.ausgaben.db.SecurityTx> securityTx = repository.securityDao().getPendingTx();
            // Die Kategoriezeilen kommen mit: aus ihnen entstehen die Splits der Transaktion.
            for (de.spahr.ausgaben.db.SecurityTx tx : securityTx) {
                tx.parts = repository.securityDao().getSplits(tx.id);
            }
            if (bookings.isEmpty() && edited.isEmpty() && pendingDeletes.isEmpty()
                    && advances.isEmpty() && securityTx.isEmpty()) {
                complete(listener, r.getString(de.spahr.ausgaben.R.string.export_none), false);
                return;
            }

            RemoteStorage storage = RemoteStorage.from(settings);
            try {
                progress(listener, r.getString(de.spahr.ausgaben.R.string.progress_download));
                byte[] raw = storage.downloadBytes(folder, file);
                // Stand der Datei merken: Schreibt KMyMoney am Rechner in der Zwischenzeit, bricht das
                // Rückschreiben unten ab, statt die fremden Änderungen still zu überschreiben.
                // Lässt sich der Stand nicht ermitteln (alter/eigenwilliger Server), wird ungeprüft
                // geschrieben wie bisher – der Export darf daran nicht scheitern.
                String version;
                try {
                    version = storage.fileVersion(folder, file);
                } catch (Exception e) {
                    version = "";
                }

                progress(listener, r.getString(de.spahr.ausgaben.R.string.kmy_progress_processing));
                KmyDocument doc = new KmyDocument(raw, appContext);
                // Der Export liest die Datei ohnehin – dabei bleibt die Stichwortliste frisch, auch für
                // Nutzer, die nie zurückimportieren.
                repository.replaceTags(doc.tagNames());
                KmyExporter exporter = new KmyExporter(doc, r);
                KmyExporter.Result res = exporter.build(bookings, edited, loadSplits());

                // Bereits vorhandene, lokal inzwischen gelöschte Buchungen aus der XML entfernen (nur im
                // kmy-Modus vorgemerkt, siehe Repository.queueKmyDeleteIfNeeded); Suche über Konto/Datum/
                // Betrag, da Transaktionen aus App-Sicht keine bekannte id haben.
                KmyExporter.DeleteResult delRes = exporter.removeTransactions(res.xml, pendingDeletes);
                res.xml = delRes.xml;

                // Geplante Buchungen weiterstellen (nur postdate/lastPayment – die Regel bleibt bestehen).
                KmyExporter.ScheduleResult schedRes = exporter.applyScheduleAdvances(res.xml, advances);
                res.xml = schedRes.xml;

                // Erfasste Depot-Bewegungen als vollständige Wertpapier-Transaktionen anhängen.
                KmyExporter.SecurityResult secRes =
                        exporter.buildSecurityTransactions(res.xml, securityTx);
                res.xml = secRes.xml;
                res.skipped.addAll(secRes.skipped);

                if (res.writtenIds.isEmpty() && delRes.resolvedIds.isEmpty()
                        && schedRes.resolvedIds.isEmpty() && secRes.writtenIds.isEmpty()) {
                    complete(listener, r.getString(de.spahr.ausgaben.R.string.kmy_none_matched)
                            + "\n" + skippedText(r, res) + notFoundText(r, res), false);
                    return;
                }

                // Sicherung in den Unterordner „Backup" neben der .kmy (wird bei Bedarf angelegt).
                progress(listener, r.getString(de.spahr.ausgaben.R.string.kmy_progress_backup));
                String backupFolder = folder.isEmpty() ? BACKUP_DIR : folder + "/" + BACKUP_DIR;
                String backupName = file + ".bak-" + tsFormat.format(new Date());
                String backup = BACKUP_DIR + "/" + backupName;
                storage.ensureFolder(backupFolder);
                storage.uploadBytes(backupFolder, backupName, raw);

                progress(listener, r.getString(de.spahr.ausgaben.R.string.kmy_progress_writing));
                byte[] packed = KmyDocument.gzip(res.xml);
                // Nicht über die vorhandene Datei schreiben: erst vollständig in eine Zwischendatei,
                // dann auf dem Server umbenennen. Ein Abbruch mittendrin (Timeout, Funkloch) läßt sonst
                // einen unlesbaren Torso zurück – genau so ging schon einmal eine .kmy verloren.
                de.spahr.ausgaben.net.SafeReplace.cleanUp(storage, folder, file);
                de.spahr.ausgaben.net.SafeReplace.replace(storage, folder, file, packed, version,
                        tsFormat.format(new Date()));

                // Die Datei ist geschrieben; jetzt zieht der lokale Stand nach — und zwar als ein
                // Vorgang. Vorher waren das bis zu fünf einzelne Schreibzugriffe, und ein Abbruch
                // dazwischen (Absturz, Speicher voll) hinterließ eine halbe Wahrheit: eine Bewegung
                // bliebe „Vormerkung" und käme beim nächsten Export ein zweites Mal in die Datei, oder
                // eine Buchung bliebe ungemarkt und käme über BookingDao nie wieder durch.
                repository.inTransaction(() -> {
                    repository.bookingDao().markExported(res.writtenIds);
                    if (!secRes.writtenIds.isEmpty()) {
                        // Bewegung und ihre Geldbuchung stehen jetzt gemeinsam in der Datei: beide sind
                        // keine Vormerkung mehr. Beim nächsten Depot-Import wird die Bewegung von dort
                        // ersetzt.
                        repository.securityDao().markTxExported(secRes.writtenIds);
                        List<Long> bookingIds = new java.util.ArrayList<>();
                        for (de.spahr.ausgaben.db.SecurityTx tx : securityTx) {
                            if (tx.bookingId > 0 && secRes.writtenIds.contains(tx.id)) {
                                bookingIds.add(tx.bookingId);
                            }
                        }
                        if (!bookingIds.isEmpty()) {
                            repository.bookingDao().markExported(bookingIds);
                        }
                    }
                    if (!delRes.resolvedIds.isEmpty()) {
                        repository.kmyPendingDeleteDao().deleteByIds(delRes.resolvedIds);
                    }
                    if (!schedRes.resolvedIds.isEmpty()) {
                        // Nur wirklich geschriebene Regeln lokal nachziehen, damit die Liste bis zum
                        // nächsten Import denselben Stand zeigt wie die Datei.
                        for (de.spahr.ausgaben.db.ScheduledAdvance a : advances) {
                            if (schedRes.writtenIds.contains(a.id)) {
                                repository.scheduledTransactionDao().updateNextDue(a.kmyId, a.nextDueMs);
                            }
                        }
                        repository.scheduledAdvanceDao().deleteByIds(schedRes.resolvedIds);
                    }
                });
                complete(listener, buildMessage(r, res, secRes.writtenIds.size(),
                        delRes.resolvedIds.size(), schedRes.writtenIds.size(), file, backup), true);
            } catch (de.spahr.ausgaben.net.RemoteConflictException e) {
                // Fremdänderung erkannt: nichts geschrieben, nichts als exportiert markiert.
                complete(listener, r.getString(de.spahr.ausgaben.R.string.kmy_conflict), false);
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                complete(listener, r.getString(de.spahr.ausgaben.R.string.export_failed, msg), false);
            }
        });
    }

    /** Alle Kategorie-Teile (Splitbuchungen) nach Buchungs-ID gruppiert laden. */
    private java.util.Map<Long, List<de.spahr.ausgaben.db.BookingSplit>> loadSplits() {
        java.util.Map<Long, List<de.spahr.ausgaben.db.BookingSplit>> map = new java.util.HashMap<>();
        for (de.spahr.ausgaben.db.BookingSplit s : repository.bookingDao().getAllSplits()) {
            List<de.spahr.ausgaben.db.BookingSplit> list = map.get(s.bookingId);
            if (list == null) {
                list = new ArrayList<>();
                map.put(s.bookingId, list);
            }
            list.add(s);
        }
        return map;
    }

    /**
     * Was am Ende dasteht.
     *
     * <p>Die Depot-Bewegungen zählen mit. Sie stehen in einer eigenen Ergebnisliste, weil sie einen
     * anderen Weg durch den Exporter nehmen — gezählt wurden bis 1.13 aber nur die gewöhnlichen
     * Buchungen, und wer eine einzelne Wertpapierbuchung übertrug, bekam „0 Buchung(en) geschrieben"
     * zu lesen, während die Transaktion längst in der Datei stand.</p>
     */
    static String buildMessage(Context r, KmyExporter.Result res, int securityCount, int removedCount,
                               int advancedCount, String file, String backup) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.getString(de.spahr.ausgaben.R.string.kmy_result_written,
                res.writtenIds.size() - res.updated + securityCount, file));
        if (res.updated > 0) {
            sb.append(r.getString(de.spahr.ausgaben.R.string.kmy_result_updated, res.updated));
        }
        if (res.newPayees > 0) {
            sb.append(r.getString(de.spahr.ausgaben.R.string.kmy_result_new_payees, res.newPayees));
        }
        if (removedCount > 0) {
            sb.append(r.getString(de.spahr.ausgaben.R.string.kmy_result_deleted, removedCount));
        }
        if (advancedCount > 0) {
            sb.append(r.getString(de.spahr.ausgaben.R.string.kmy_result_scheduled, advancedCount));
        }
        sb.append(".\n").append(r.getString(de.spahr.ausgaben.R.string.kmy_result_backup, backup));
        if (!res.skipped.isEmpty()) {
            sb.append("\n").append(skippedText(r, res));
        }
        sb.append(notFoundText(r, res));
        return sb.toString();
    }

    /**
     * Hinweis auf bearbeitete Buchungen, deren Transaktion in der Datei fehlt (etwa weil sie am Rechner
     * gelöscht wurde). Sie bleiben „bearbeitet"; eingefügt wird nichts, damit keine Dublette entsteht.
     */
    private static String notFoundText(Context r, KmyExporter.Result res) {
        if (res.notFound.isEmpty()) {
            return "";
        }
        return "\n" + r.getString(de.spahr.ausgaben.R.string.kmy_edited_not_found, res.notFound.size());
    }

    private static String skippedText(Context r, KmyExporter.Result res) {
        if (res.skipped.isEmpty()) {
            return "";
        }
        List<String> show = res.skipped;
        String more = "";
        if (show.size() > 5) {
            show = new ArrayList<>(res.skipped.subList(0, 5));
            more = " … (+" + (res.skipped.size() - 5) + ")";
        }
        return r.getString(de.spahr.ausgaben.R.string.kmy_skipped, res.skipped.size(),
                TextUtils.join("; ", show) + more);
    }

    private void progress(Listener l, String stage) {
        repository.mainHandler().post(() -> l.onProgress(stage));
    }

    private void complete(Listener l, String message, boolean refresh) {
        repository.mainHandler().post(() -> l.onComplete(message, refresh));
    }
}
