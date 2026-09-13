package de.spahr.ausgaben.receipt;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * Was ein Beleg-Export zu tun hätte: welche Belege schon auf dem Gerät liegen und welche erst vom
 * Server geholt werden müssten.
 *
 * <p>Zwei Dinge hängen daran. Erstens die <b>Rückfrage</b>: 199 Dateien zu laden dauert und kann an
 * einer getakteten Verbindung Geld kosten – das ist eine Entscheidung des Nutzers, keine
 * Selbstverständlichkeit. Zweitens die <b>Reihenfolge</b>: Der Schreiblauf nimmt sich erst die
 * lokalen vor. Bricht das Laden später ab, sind die ohne Netz erreichbaren längst in der Datei.</p>
 *
 * <p>Die Prüfung greift nur auf das Dateisystem zu – für ein paar hundert Belege eine Sache von
 * Millisekunden und vor allem ohne jede Netzanfrage.</p>
 */
public final class ReceiptExportPlan {

    /** Belege, die ohne Netz zu haben sind. */
    public final List<ReceiptExportJobs.Job> local;
    /** Belege, die erst geholt werden müssten. */
    public final List<ReceiptExportJobs.Job> remote;

    private ReceiptExportPlan(List<ReceiptExportJobs.Job> local, List<ReceiptExportJobs.Job> remote) {
        this.local = local;
        this.remote = remote;
    }

    /**
     * Teilt die Aufträge auf. Als „lokal" gilt ein Beleg, dessen <b>erste</b> Seite auf dem Gerät
     * liegt – dieselbe Datei, die {@link ReceiptPages#find} sonst als erste vom Server holen würde.
     * Eine Folgeseite, die noch fehlt, macht den Beleg nicht zum Download-Fall: Sein Bild ist da, und
     * der Lauf holt den Rest nebenbei, wenn er drankommt.
     */
    public static ReceiptExportPlan of(Context context, List<ReceiptExportJobs.Job> jobs) {
        Context app = context.getApplicationContext();
        List<ReceiptExportJobs.Job> local = new ArrayList<>();
        List<ReceiptExportJobs.Job> remote = new ArrayList<>();
        if (jobs != null) {
            for (ReceiptExportJobs.Job job : jobs) {
                String first = ReceiptPages.firstPageName(job.tagName, job.ext);
                if (Receipts.localFile(app, first).exists()) {
                    local.add(job);
                } else {
                    remote.add(job);
                }
            }
        }
        return new ReceiptExportPlan(local, remote);
    }

    /** Die Aufträge in der Reihenfolge, in der gepackt werden soll: erst die lokalen. */
    public List<ReceiptExportJobs.Job> ordered() {
        List<ReceiptExportJobs.Job> out = new ArrayList<>(local.size() + remote.size());
        out.addAll(local);
        out.addAll(remote);
        return out;
    }

    /** Muss überhaupt etwas geholt werden? Sonst erübrigt sich die Rückfrage. */
    public boolean needsDownload() {
        return !remote.isEmpty();
    }
}
