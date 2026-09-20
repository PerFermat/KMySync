package de.spahr.ausgaben.ui;

import android.net.Uri;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.db.SecurityTx;
import de.spahr.ausgaben.receipt.ReceiptExportJobs;
import de.spahr.ausgaben.receipt.ReceiptExportPlan;
import de.spahr.ausgaben.receipt.ReceiptExportResume;
import de.spahr.ausgaben.receipt.ReceiptZip;
import de.spahr.ausgaben.receipt.ZipCheck;

/**
 * Belege der ausgewählten Buchungen in eine ZIP-Datei packen.
 *
 * <p>Der Ablauf hat vier Stationen: sammeln, was überhaupt einen Beleg trägt — fragen, ob Fehlendes
 * vom Server geholt werden soll — Ziel wählen lassen — schreiben und melden. Dazwischen kann ein Lauf
 * über hunderte Belege Minuten dauern, deshalb gibt es ein Band mit Zählstand und einen Abbruch.</p>
 *
 * <h2>Was hier <em>nicht</em> steht</h2>
 *
 * <p>Die Fachlogik liegt im Paket {@code receipt/} und ist dort geprüft: {@link ReceiptExportJobs}
 * sammelt, {@link ReceiptExportPlan} sortiert (erst was schon hier liegt), {@link ReceiptZip} packt,
 * {@link ReceiptExportResume} merkt sich einen angefangenen Lauf, {@link ZipCheck} beurteilt eine
 * abgebrochene Datei. Übrig bleibt die Verdrahtung — Dialoge, Band, Rückrufe — und die stand bis 2.1
 * in {@code MainActivity}, wo sie kein Test erreichte.</p>
 *
 * <h2>Warum die Auswahl hereingereicht wird</h2>
 *
 * <p>{@link #start(List)} bekommt die <b>fertig gefilterte</b> Liste. Der Filter gehört der Maske, und
 * er ist verwickelt genug (Kategorie, Betrag, Zeitraum, Umkreis, Schnellsuche); hier hätte er nichts
 * zu suchen. Der Gewinn ist nicht nur Ordnung: Ohne Filterzustand läßt sich diese Klasse mit einer
 * Handvoll Buchungen prüfen, und genau das tut {@code ReceiptExportControllerTest}.</p>
 *
 * <h2>Aufräumen</h2>
 *
 * <p>Ein Lauf hängt an einem eigenen Faden, der den Abbruch-Merker abfragt. {@link #detach()} setzt
 * ihn — sonst schriebe der Faden weiter und meldete am Ende an eine Maske, die es nicht mehr gibt.
 * Die bereits geschriebene Datei bleibt; sie enthält ja Belege.</p>
 */
final class ReceiptExportController {

    private final AppCompatActivity maske;
    private final Repository repository;
    private final ImportBanner banner;
    private final ActivityResultLauncher<String> zipLauncher;

    /** Was gepackt werden soll, sobald das Ziel feststeht. */
    private List<ReceiptExportJobs.Job> jobs;
    /** Fehlendes vom Server holen? Bei einem Lauf ohne Fehlendes erübrigt sich die Frage. */
    private boolean download = true;
    /** Wie viele der Belege schon auf dem Gerät liegen — für den Text im Band. */
    private int local;
    /** Dateiname des laufenden Exports; beim Wiederaufnehmen der von damals. */
    private String name = "";
    /** Gesetzt, solange ein Lauf läuft. Der Faden fragt ihn ab. */
    private AtomicBoolean cancel;

    ReceiptExportController(AppCompatActivity maske, Repository repository, ImportBanner banner,
                            ActivityResultLauncher<String> zipLauncher) {
        this.maske = maske;
        this.repository = repository;
        this.banner = banner;
        this.zipLauncher = zipLauncher;
    }

    /**
     * Sammelt die Belege der übergebenen Buchungen und öffnet den Speicherdialog. Hat keine davon einen
     * Beleg, bleibt es bei einer kurzen Meldung – ein Dialog, an dessen Ende eine leere Datei stünde,
     * hilft niemandem.
     */
    void start(List<Booking> auswahl) {
        // Zweimal sammeln ist billig (reine Rechnung) und erspart eine Datenbankabfrage je Umbuchung:
        // Erst steht fest, welche Buchungen überhaupt einen Beleg tragen, und nur die werden nachgeschlagen.
        List<ReceiptExportJobs.Job> vorlaeufig = ReceiptExportJobs.collect(auswahl);
        if (vorlaeufig.isEmpty()) {
            Toast.makeText(maske, R.string.receipt_export_none, Toast.LENGTH_LONG).show();
            return;
        }
        Set<Long> mitBeleg = new HashSet<>();
        for (ReceiptExportJobs.Job j : vorlaeufig) {
            mitBeleg.add(j.bookingId);
        }
        List<Booking> kandidaten = new ArrayList<>();
        for (Booking b : auswahl) {
            if (mitBeleg.contains(b.id)) {
                kandidaten.add(b);
            }
        }
        repository.securityInfoForBookings(kandidaten, info -> {
            ReceiptExportPlan plan = ReceiptExportPlan.of(maske,
                    ReceiptExportJobs.collect(auswahl, uebersetzteBewegungsarten(info)));
            // Gepackt wird in der Reihenfolge der Liste: erst die Belege, die schon hier liegen. Bricht
            // das Nachladen später ab, stehen sie deshalb auf jeden Fall in der Datei.
            jobs = plan.ordered();
            local = plan.local.size();
            if (!plan.needsDownload()) {
                download = true; // nichts zu holen – die Frage erübrigt sich
                zielWaehlen();
                return;
            }
            askDownload(plan);
        });
    }

    /**
     * Vor dem Nachladen fragen. Nicht nur wegen der Kosten an einer getakteten Verbindung: Ein paar
     * hundert Dateien vom Server zu holen dauert, und wer das vorher weiß, entscheidet anders, als wenn
     * die App wortlos loslegt. Deshalb kommt die Frage auch im WLAN.
     */
    private void askDownload(ReceiptExportPlan plan) {
        int da = plan.local.size();
        int fehlt = plan.remote.size();
        StringBuilder text = new StringBuilder()
                .append(s(R.string.receipt_download_ask, da, fehlt))
                .append("\n\n")
                .append(s(R.string.receipt_download_takes_time));
        if (de.spahr.ausgaben.net.Net.isMetered(maske)) {
            text.append("\n\n").append(s(R.string.receipt_download_metered));
        }
        new AppDialog(maske)
                .setTitle(R.string.receipt_export_title)
                .setMessage(text.toString())
                .setPositiveButton(R.string.receipt_download_all, (d, w) -> {
                    download = true;
                    zielWaehlen();
                })
                .setNeutralButton(s(R.string.receipt_download_local_only, da), (d, w) -> {
                    download = false;
                    zielWaehlen();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * Bietet an, einen unterbrochenen oder unvollständig gebliebenen Beleg-Export zu wiederholen.
     *
     * <p>Ein zweiter Lauf ist billig: Alles, was der erste schon geholt hat, liegt jetzt auf dem Gerät
     * und rauscht durch. Geschrieben wird in dieselbe Datei – ohne weitere Rückfrage, auch die
     * Entscheidung über das Nachladen gilt von damals.</p>
     */
    void offerResume() {
        final ReceiptExportResume.Pending offen = ReceiptExportResume.askOncePerStart(maske);
        if (offen == null) {
            return;
        }
        String wann = java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(
                        new java.util.Date(offen.startedAt));
        new AppDialog(maske)
                .setTitle(R.string.receipt_export_title)
                .setMessage(s(R.string.receipt_export_resume_ask, offen.name, wann))
                .setPositiveButton(R.string.receipt_export_resume, (d, w) -> resume(offen))
                .setNegativeButton(R.string.cancel, (d, w) -> {
                    // Der Nutzer will die Datei nicht mehr. Unbrauchbar? Dann weg damit – lesbar?
                    // Dann bleibt sie, sie enthält ja Belege.
                    ReceiptExportResume.clear(maske);
                    removeIfBroken(Uri.parse(offen.uri));
                })
                .show();
    }

    /** Baut die Belegliste aus den gemerkten Buchungsnummern neu und schreibt dieselbe Datei. */
    private void resume(ReceiptExportResume.Pending offen) {
        final Uri uri = Uri.parse(offen.uri);
        repository.getBookingsByIds(offen.bookingIds, bookings -> {
            if (bookings.isEmpty()) {
                ReceiptExportResume.clear(maske);
                Toast.makeText(maske, R.string.receipt_export_none, Toast.LENGTH_LONG).show();
                return;
            }
            repository.securityInfoForBookings(bookings, info -> {
                ReceiptExportPlan plan = ReceiptExportPlan.of(maske,
                        ReceiptExportJobs.collect(bookings, uebersetzteBewegungsarten(info)));
                name = offen.name;
                schreibe(uri, plan.ordered(), offen.allowDownload, plan.local.size(), true);
            });
        });
    }

    /** Speicherort wählen lassen; geschrieben wird erst in {@link #onZipPicked}. */
    private void zielWaehlen() {
        name = "belege-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",
                java.util.Locale.US).format(new java.util.Date()) + ".zip";
        zipLauncher.launch(name);
    }

    /** Das Ziel steht fest – packen. Kommt aus dem Rückruf des Datei-Wählers in der Maske. */
    void onZipPicked(Uri uri) {
        schreibe(uri, jobs, download, local, false);
        jobs = null;
    }

    /**
     * Bricht einen laufenden Export ab. Die Maske ruft das in {@code onDestroy}.
     *
     * <p>Der Faden läuft weiter, bis er den Merker sieht; was bis dahin gepackt wurde, bleibt in der
     * Datei. Ohne das käme am Ende ein Rückruf auf eine Maske, die es nicht mehr gibt — {@code Ui.post}
     * finge ihn zwar ab, aber der Lauf liefe sinnlos bis zum letzten Beleg weiter.</p>
     */
    void detach() {
        AtomicBoolean flag = cancel;
        if (flag != null) {
            flag.set(true);
        }
    }

    private void askCancel() {
        final AtomicBoolean flag = cancel;
        if (flag == null) {
            return;
        }
        new AppDialog(maske)
                .setTitle(R.string.receipt_export_title)
                .setMessage(R.string.receipt_export_cancel_ask)
                .setPositiveButton(R.string.receipt_export_cancel, (d, w) -> {
                    flag.set(true);
                    banner.label(s(R.string.receipt_export_cancelling));
                })
                .setNegativeButton(R.string.receipt_export_keep_running, null)
                .show();
    }

    /**
     * Die Bewegungsarten aus der Datenbank in die Sprache der App übersetzen – sie stehen später im
     * Dateinamen des Belegs, und dort will man „Kauf" lesen, nicht „buy".
     */
    private Map<Long, String[]> uebersetzteBewegungsarten(Map<Long, String[]> roh) {
        Map<Long, String[]> out = new HashMap<>();
        for (Map.Entry<Long, String[]> e : roh.entrySet()) {
            String art = e.getValue()[0];
            int text = SecurityTx.SELL.equals(art) ? R.string.action_sell
                    : SecurityTx.DIVIDEND.equals(art) ? R.string.action_dividend
                    : R.string.action_buy;
            out.put(e.getKey(), new String[]{s(text), e.getValue()[1]});
        }
        return out;
    }

    /**
     * Der eigentliche Lauf.
     *
     * @param wieder {@code true} beim Wiederaufnehmen: Die Datei wird dann gekürzt und von vorn
     *               beschrieben, statt eine frisch angelegte zu füllen.
     */
    private void schreibe(Uri uri, List<ReceiptExportJobs.Job> auftraege,
                          final boolean holen, int lokalCount, boolean wieder) {
        // Bis hierher liegen die Belege schon auf dem Gerät – ab da wird geholt (die Liste ist
        // vorsortiert, siehe ReceiptExportPlan.ordered).
        final int lokal = holen ? lokalCount : auftraege == null ? 0 : auftraege.size();
        if (auftraege == null || auftraege.isEmpty()) {
            return;
        }
        // Ab jetzt gilt der Lauf als begonnen. Stirbt der Prozess mittendrin, findet die App den
        // Merker beim nächsten Start und bietet an, ihn zu wiederholen.
        if (!wieder) {
            List<Long> ids = new ArrayList<>(auftraege.size());
            for (ReceiptExportJobs.Job j : auftraege) {
                ids.add(j.bookingId);
            }
            ReceiptExportResume.start(maske, uri.toString(), name, holen, ids);
        }
        final AtomicBoolean abbruch = new AtomicBoolean();
        cancel = abbruch;
        final String pausedLabel = s(R.string.receipt_export_paused);
        banner.start(s(lokal > 0
                ? R.string.receipt_export_running
                : R.string.receipt_export_running_fetch));
        // Der Text zählt mit: Bei 238 Belegen sagt ein Prozentwert allein zu wenig darüber, wie weit
        // der Lauf ist und wie lange er noch braucht. Und er sagt, woran es gerade liegt, wenn es
        // langsam vorangeht – Packen dauert Millisekunden, Holen dauert.
        final String[] zaehlend = {s(R.string.receipt_export_running)};
        final de.spahr.ausgaben.util.ProgressListener progress = (done, total) -> {
            boolean holt = done >= lokal && total > lokal;
            zaehlend[0] = s(holt
                    ? R.string.receipt_export_running_fetch_count
                    : R.string.receipt_export_running_count, done, total);
            banner.set(zaehlend[0], de.spahr.ausgaben.export.ImportPhase.map(done, total, 0, 100));
        };
        // Der Lauf schläft, solange die App im Hintergrund ist – das Banner sagt, warum nichts vorangeht.
        // Danach steht wieder der Zählstand da, bei dem er stehengeblieben ist.
        final ReceiptZip.PauseListener pause =
                p -> banner.label(p ? pausedLabel : zaehlend[0]);
        // Das Band ist jetzt der Abbruchknopf.
        banner.setClickAction(this::askCancel);
        new Thread(() -> {
            ReceiptZip.Result result = null;
            String error = null;
            // „wt" kürzt eine vorhandene Datei auf null – beim Wiederaufnehmen darf hinter dem neuen
            // Archiv nichts vom alten stehenbleiben.
            try (OutputStream out = maske.getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) {
                    throw new IOException("kein Schreibzugriff");
                }
                result = ReceiptZip.write(maske, out, auftraege, progress, pause, holen, abbruch::get);
            } catch (Exception e) {
                error = String.valueOf(e.getMessage());
            }
            final ReceiptZip.Result done = result;
            final String failed = error;
            // Offen bleibt der Merker nur, wenn ein zweiter Lauf etwas bringt. Nach einem Abbruch
            // gehört er ebenfalls weg – der Nutzer hat gerade gesagt, dass er aufhören will.
            ReceiptExportResume.finished(maske,
                    done != null && done.worthRetrying() && !done.cancelled);
            if (done != null && done.cancelled) {
                // Regulär geschlossen heißt in aller Regel: lesbar. Prüfen und nur wegwerfen, was
                // wirklich niemand mehr öffnen kann.
                removeIfBroken(uri);
            }
            if (maske.isFinishing() || maske.isDestroyed()) {
                return; // niemand mehr da, dem man etwas melden könnte – die Datei steht trotzdem
            }
            Ui.post(maske, () -> {
                cancel = null;
                banner.setClickAction(null);
                banner.finish();
                if (failed != null) {
                    Toast.makeText(maske, s(R.string.receipt_export_failed, failed),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                report(uri, done);
            });
        }).start();
    }

    /** Meldet das Ergebnis; ist nichts hineingekommen, verschwindet die eben angelegte Datei wieder. */
    private void report(Uri uri, ReceiptZip.Result r) {
        if (r.written == 0) {
            loesche(uri);
            new AppDialog(maske)
                    .setTitle(R.string.receipt_export_title)
                    .setMessage(s(R.string.receipt_export_empty, r.missing()) + gruende(r))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        String text = s(R.string.receipt_export_done, r.written);
        if (r.missing() > 0) {
            text += "\n\n" + s(R.string.receipt_export_missing, r.missing()) + gruende(r);
        }
        new AppDialog(maske)
                .setTitle(R.string.receipt_export_title)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /**
     * Wirft die Datei weg, wenn sie <b>sicher</b> unbrauchbar ist – sonst bleibt sie liegen.
     *
     * <p>Eine abgebrochene Datei ist lesbar und enthält, was bis dahin gepackt wurde; die ist etwas
     * wert. Nur wo der Prozess mitten im Schreiben starb, fehlt das Dateiende und kein Packprogramm
     * kommt mehr hinein. Lässt sich das nicht feststellen, wird nicht gelöscht: Eine kaputte Datei,
     * die herumsteht, ist der kleinere Schaden als eine gelöschte, die in Ordnung war.</p>
     */
    private void removeIfBroken(Uri uri) {
        Boolean heil = zipLooksComplete(uri);
        if (heil == null || heil) {
            return;
        }
        loesche(uri);
    }

    private void loesche(Uri uri) {
        try {
            android.provider.DocumentsContract.deleteDocument(maske.getContentResolver(), uri);
        } catch (Exception ignored) {
            // Manche Anbieter lassen das nicht zu – dann bleibt die Datei eben liegen.
        }
    }

    /**
     * Ist die ZIP-Datei vollständig geschlossen? {@code null} = nicht feststellbar.
     *
     * <p>Gelesen wird nur der Schwanz der Datei, nicht ihr Inhalt – ein Beleg-Archiv kann hundert
     * Megabyte haben.</p>
     */
    private Boolean zipLooksComplete(Uri uri) {
        try (android.os.ParcelFileDescriptor pfd =
                     maske.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) {
                return null;
            }
            long size = pfd.getStatSize();
            if (size <= 0) {
                return Boolean.FALSE; // leer angelegt und nie beschrieben
            }
            int len = (int) Math.min(size, ZipCheck.TAIL_BYTES);
            byte[] tail = new byte[len];
            try (java.io.FileInputStream in =
                         new java.io.FileInputStream(pfd.getFileDescriptor())) {
                in.getChannel().position(size - len);
                int gelesen = 0;
                while (gelesen < len) {
                    int n = in.read(tail, gelesen, len - gelesen);
                    if (n < 0) {
                        break;
                    }
                    gelesen += n;
                }
                if (gelesen < len) {
                    return null; // unvollständig gelesen – lieber nichts behaupten
                }
            }
            return ZipCheck.looksComplete(tail);
        } catch (Exception e) {
            return null; // kein Zugriff, kein Positionieren – im Zweifel nicht löschen
        }
    }

    /**
     * Die Gründe hinter der Zahl der fehlenden Belege – je Grund eine Zeile, und nur die, die
     * zutreffen. „Nicht gefunden" und „keine Verbindung" verlangen ganz verschiedene Schritte, deshalb
     * standen sie nie zu Recht in derselben Zahl.
     */
    private String gruende(ReceiptZip.Result r) {
        StringBuilder sb = new StringBuilder();
        if (r.notFound > 0) {
            sb.append("\n").append(s(R.string.receipt_export_not_found, r.notFound));
        }
        if (r.unreachable > 0) {
            sb.append("\n").append(s(R.string.receipt_export_unreachable, r.unreachable));
        }
        if (r.pending > 0) {
            sb.append("\n").append(s(R.string.receipt_export_pending, r.pending));
        }
        if (r.skipped > 0) {
            sb.append("\n").append(s(R.string.receipt_export_skipped, r.skipped));
        }
        // Nur wo ein zweiter Anlauf etwas bringt: Was der Server nicht hat, holt auch die zehnte
        // Wiederholung nicht.
        if (r.unreachable + r.pending + r.skipped > 0) {
            sb.append("\n\n").append(s(R.string.receipt_export_retry));
        }
        return sb.toString();
    }

    /** Kurz für {@code maske.getString(…)} – in dieser Klasse steht das zwanzigmal. */
    private String s(int id, Object... args) {
        return args.length == 0 ? maske.getString(id) : maske.getString(id, args);
    }
}
