package de.spahr.ausgaben.receipt;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import de.spahr.ausgaben.net.Net;
import de.spahr.ausgaben.net.RemoteStorage;
import de.spahr.ausgaben.settings.SettingsStore;
import de.spahr.ausgaben.util.ForegroundGate;
import de.spahr.ausgaben.util.ProgressListener;

/**
 * Packt die Belege einer gefilterten Buchungsliste in eine ZIP-Datei – unter sprechenden Namen
 * (siehe {@link ReceiptExportName}) statt der UUIDs, unter denen die App sie führt.
 *
 * <p>Was auf dem Gerät fehlt, wird aus dem Sync-Ordner nachgeholt. Der Lauf nimmt sich dafür Zeit: Ein
 * Beleg gilt erst als unerreichbar, wenn mehrere Versuche gescheitert sind, und wenn die App im
 * Hintergrund ist oder das Netz weg, <b>pausiert</b> er, statt reihenweise Belege abzuhaken. Genau
 * daran ist ein Export über 238 Belege einmal gescheitert: Android drosselt das Netz einer App im
 * Hintergrund, der Nutzer war kurz in einer anderen App, und 199 vorhandene Belege galten als fehlend.</p>
 *
 * <p>Ein Beleg, den der Server nicht kennt, hält den Lauf dagegen nicht auf – er wird getrennt gezählt
 * und gemeldet.</p>
 *
 * <p>Gibt der Lauf das Warten auf (Pausenfrist abgelaufen), bricht er <b>nicht</b> ab, sondern geht die
 * Liste ohne Netz zu Ende: Jeder Beleg, der ohnehin schon auf dem Gerät liegt, kommt in die Datei. Was
 * geladen werden müsste, wird als offen gemeldet – ein erneuter Lauf holt genau diese nach und findet
 * die bereits geholten lokal vor.</p>
 *
 * <p><b>Blockierend</b> – vom Aufrufer auf einem Hintergrund-Thread nutzen.</p>
 */
public final class ReceiptZip {

    /** Versuche je Datei, bevor sie als unerreichbar gilt – wie in {@link ReceiptSync#ensureLocalWaiting}. */
    private static final int MAX_ATTEMPTS = 6;
    /** Pause zwischen zwei Versuchen. */
    private static final long RETRY_DELAY_MS = 3000L;
    /**
     * So lange wartet ein pausierter Lauf auf die Rückkehr in den Vordergrund. Unbegrenzt zu warten
     * wäre falsch: Einen Hintergrundprozess ohne sichtbare Benachrichtigung darf Android jederzeit
     * beenden, und dann bliebe eine halb geschriebene Datei zurück. Nach Ablauf wird der Lauf sauber
     * beendet – die Datei ist gültig, der Rest wird als offen gemeldet.
     */
    private static final long MAX_PAUSE_MS = 10 * 60 * 1000L;

    /** Was ein Lauf zustande gebracht hat. */
    public static final class Result {
        /** Seiten, die in der Datei gelandet sind. */
        public int written;
        /** Belege, die der Server nicht kennt. */
        public int notFound;
        /** Belege, an die der Lauf nicht herankam (Verbindung). */
        public int unreachable;
        /** Belege, die nur deshalb fehlen, weil der Lauf nach zu langer Pause nicht mehr gewartet hat. */
        public int pending;
        /** Belege, die auf Wunsch des Nutzers gar nicht erst geladen wurden. */
        public int skipped;
        /** Behandelte Belege (Buchungen mit Beleg). */
        public int receipts;
        /** {@code true}, wenn der Nutzer den Lauf abgebrochen hat. */
        public boolean cancelled;

        /** Belege, die aus welchem Grund auch immer nicht in der Datei stehen. */
        public int missing() {
            return notFound + unreachable + pending + skipped;
        }

        /**
         * Lohnt ein zweiter Lauf? Nur bei Belegen, die beim nächsten Mal anders ausgehen können: Was
         * der Server nicht hat, hat er auch morgen nicht, und „auf Wunsch nicht geladen" war eine
         * Entscheidung, die nicht ungefragt wieder aufgemacht wird.
         */
        public boolean worthRetrying() {
            return unreachable + pending > 0;
        }
    }

    /** Meldet der Oberfläche, dass der Lauf gerade schläft (App im Hintergrund oder kein Netz). */
    public interface PauseListener {
        void onPaused(boolean paused);
    }

    private ReceiptZip() {
    }

    /**
     * Schreibt die Belege der {@code jobs} als ZIP nach {@code out}. Der Strom wird geschlossen.
     *
     * <p>Die Reihenfolge der Liste ist die Reihenfolge des Packens – der Aufrufer stellt über
     * {@link ReceiptExportPlan#ordered()} die schon vorhandenen Belege voran, damit sie in der Datei
     * stehen, bevor die erste Netzanfrage läuft.</p>
     *
     * @param progress      darf {@code null} sein; wird je fertigem Beleg gemeldet
     * @param paused        darf {@code null} sein; meldet Beginn und Ende einer Pause
     * @param allowDownload {@code false} = nur packen, was auf dem Gerät liegt; der Rest wird als
     *                      {@link Result#skipped} gezählt, ohne dass eine Anfrage hinausgeht
     * @param cancelled     darf {@code null} sein; meldet den Abbruchwunsch des Nutzers. Abgebrochen
     *                      wird geordnet: Die Datei wird regulär geschlossen und bleibt lesbar.
     */
    public static Result write(Context context, OutputStream out, List<ReceiptExportJobs.Job> jobs,
                               ProgressListener progress, PauseListener paused, boolean allowDownload,
                               ReceiptSync.Cancelled cancelled) throws IOException {
        Context app = context.getApplicationContext();
        Result result = new Result();
        Set<String> usedNames = new HashSet<>();
        // Eine Verbindung für den ganzen Lauf statt einer je Datei – siehe ReceiptSync.ensureLocal.
        RemoteStorage storage = allowDownload ? openStorage(app) : null;
        // Ist die Pausenfrist einmal abgelaufen, wird nicht mehr aufs Netz gewartet – der Lauf geht die
        // Liste aber zu Ende. Alles, was schon auf dem Gerät liegt, kommt so trotzdem in die Datei.
        boolean[] gaveUp = {false};
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            int total = jobs == null ? 0 : jobs.size();
            for (int i = 0; jobs != null && i < total; i++) {
                if (stopped(cancelled)) {
                    // Abbruch: Der Rest ist offen, die Datei wird gleich ordentlich geschlossen.
                    result.cancelled = true;
                    result.pending += total - i;
                    break;
                }
                ReceiptExportJobs.Job job = jobs.get(i);
                result.receipts++;
                pack(app, zip, job, storage, usedNames, result, paused, gaveUp, allowDownload,
                        cancelled);
                if (progress != null) {
                    progress.onProgress(i + 1, total);
                }
            }
        }
        return result;
    }

    /** Wie oben, mit Nachladen, ohne Pausenmeldung und ohne Abbruchmöglichkeit. */
    public static Result write(Context context, OutputStream out, List<ReceiptExportJobs.Job> jobs,
                               ProgressListener progress) throws IOException {
        return write(context, out, jobs, progress, null, true, null);
    }

    /** Will der Nutzer aufhören? */
    private static boolean stopped(ReceiptSync.Cancelled cancelled) {
        return cancelled != null && cancelled.get();
    }

    /** Einen Beleg samt aller Seiten in die Datei legen und das Ergebnis verbuchen. */
    private static void pack(Context app, ZipOutputStream zip, ReceiptExportJobs.Job job,
                             RemoteStorage storage, Set<String> usedNames, Result result,
                             PauseListener paused, boolean[] gaveUp, boolean allowDownload,
                             ReceiptSync.Cancelled cancelled) throws IOException {
        String firstName = ReceiptPages.firstPageName(job.tagName, job.ext);
        boolean lagSchonDa = Receipts.localFile(app, firstName).exists();
        if (!allowDownload && !lagSchonDa) {
            result.skipped++; // der Nutzer wollte nicht laden – keine Anfrage, keine Wartezeit
            return;
        }
        // Erst die erste Seite, und zwar einzeln: Nur an ihr lässt sich „kennt der Server nicht" von
        // „komme gerade nicht dran" unterscheiden.
        ReceiptSync.Fetched first = fetchWithRetry(app, firstName, job.year, storage, paused, gaveUp,
                cancelled);
        if (first.file == null) {
            if (first.notFound) {
                result.notFound++;
            } else if (gaveUp[0]) {
                result.pending++; // nur wegen der abgelaufenen Pause nicht geholt
            } else {
                result.unreachable++;
            }
            return;
        }
        // Die erste Seite liegt jetzt vor; von dort aus die Folgeseiten einsammeln.
        //
        // Beim Server nachfragen aber nur, wenn dieser Beleg ohnehin von dort kam: Es gibt keine
        // Seitenzahl in der Notiz, das Ende einer Folge zeigt sich erst an einer Anfrage, die ins Leere
        // greift. Für einen einseitigen Beleg ist das eine vergebliche Anfrage (und wegen des zweiten,
        // früheren Ablageorts sogar zwei) – über eine Internetverbindung knapp eine Sekunde. Bei einem
        // Beleg, der schon auf dem Gerät liegt, wäre diese Sekunde vollends umsonst: Seine Seiten sind
        // vollständig da, sonst hätte ihn die Vorsortierung gar nicht als vorhanden gezählt.
        for (String page : ReceiptPages.pagesFrom(app, firstName, job.year, job.ext, storage,
                allowDownload && !lagSchonDa)) {
            File file = Receipts.localFile(app, page);
            if (!file.exists()) {
                continue; // Folgeseite nicht zu holen – der Beleg selbst ist trotzdem dabei
            }
            put(zip, entryName(job, page, usedNames), file);
            result.written++;
        }
    }

    /**
     * Holt eine Datei und gibt dabei nicht beim ersten Fehlversuch auf. Zwischen den Versuchen wird
     * gewartet; ist die App inzwischen im Hintergrund oder das Netz weg, pausiert der Lauf. Ein Beleg,
     * den der Server nicht kennt, bricht sofort ab – ein zweiter Versuch änderte daran nichts.
     *
     * <p>Der <b>erste</b> Versuch läuft ohne jede Schranke. Eine Datei, die schon auf dem Gerät liegt,
     * braucht kein Netz und keinen Vordergrund; sie kommt in die ZIP-Datei, auch wenn der Lauf längst
     * aufgehört hat zu warten. Erst wenn wirklich geladen werden muss, wird pausiert.</p>
     */
    private static ReceiptSync.Fetched fetchWithRetry(Context app, String file, int year,
                                                      RemoteStorage storage, PauseListener paused,
                                                      boolean[] gaveUp,
                                                      ReceiptSync.Cancelled cancelled) {
        ReceiptSync.Fetched last = ReceiptSync.fetch(app, file, year, storage);
        if (last.file != null || last.notFound || gaveUp[0]) {
            return last;
        }
        for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
            if (stopped(cancelled) || !awaitReady(app, paused, cancelled)) {
                gaveUp[0] = true; // ab hier nur noch, was ohne Netz zu haben ist
                return last;
            }
            try {
                Thread.sleep(RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return last;
            }
            last = ReceiptSync.fetch(app, file, year, storage);
            if (last.file != null || last.notFound) {
                return last;
            }
        }
        return last;
    }

    /**
     * Wartet, bis die App im Vordergrund und online ist.
     *
     * @return {@code false}, wenn die Frist abgelaufen ist – dann soll der Lauf aufhören
     */
    private static boolean awaitReady(Context app, PauseListener paused,
                                      ReceiptSync.Cancelled cancelled) {
        if (ForegroundGate.isForeground() && Net.isOnline(app)) {
            return true;
        }
        if (paused != null) {
            paused.onPaused(true);
        }
        try {
            long deadline = System.currentTimeMillis() + MAX_PAUSE_MS;
            while (System.currentTimeMillis() < deadline) {
                if (stopped(cancelled)) {
                    return false; // aus der Pause heraus abgebrochen
                }
                long rest = deadline - System.currentTimeMillis();
                if (!ForegroundGate.awaitForeground(Math.min(rest, RETRY_DELAY_MS))) {
                    continue; // noch im Hintergrund – weiter warten, bis die Frist abläuft
                }
                if (Net.isOnline(app)) {
                    return true;
                }
                // Vordergrund, aber (noch) kein Netz: kurz durchatmen, statt zu drehen.
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        } finally {
            if (paused != null) {
                paused.onPaused(false);
            }
        }
    }

    /** Die gemeinsame Verbindung des Laufs; {@code null}, wenn keine zustande kommt (dann wie bisher). */
    private static RemoteStorage openStorage(Context app) {
        try {
            SettingsStore settings = new SettingsStore(app);
            return settings.hasRemoteConfig() ? RemoteStorage.from(settings) : null;
        } catch (Exception e) {
            return null; // ensureLocal baut dann je Datei selbst eine auf
        }
    }

    /**
     * Der Eintragsname, und zwar ein noch freier. Die Buchungsnummer im Namen macht eine
     * Namensgleichheit eigentlich unmöglich – aber ein doppelter Eintrag wäre ein stiller Verlust
     * beim Auspacken, und ein {@link HashSet} dagegen kostet nichts.
     */
    private static String entryName(ReceiptExportJobs.Job job, String page, Set<String> used) {
        String name = job.istWertpapier()
                ? ReceiptExportName.ofSecurity(job.createdAt, job.action, job.securityName,
                        job.amountCents, job.bookingId, page)
                : ReceiptExportName.of(job.createdAt, job.payee, job.amountCents, job.bookingId, page);
        String candidate = name;
        int n = 2;
        while (!used.add(candidate.toLowerCase(Locale.ROOT))) {
            int dot = name.lastIndexOf('.');
            candidate = name.substring(0, dot) + "-" + n++ + name.substring(dot);
        }
        return candidate;
    }

    private static void put(ZipOutputStream zip, String name, File file) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        byte[] buf = new byte[8192];
        try (InputStream in = new FileInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                zip.write(buf, 0, n);
            }
        }
        zip.closeEntry();
    }
}
