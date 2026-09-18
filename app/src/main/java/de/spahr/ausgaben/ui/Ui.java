package de.spahr.ausgaben.ui;

import android.app.Activity;

/**
 * Der eine geprüfte Rückweg aus dem Hintergrund auf den Bedienfaden.
 *
 * <h2>Warum es das gibt</h2>
 *
 * <p>Die App erledigt Netz-, Datei- und Importarbeit in eigenen Fäden — bewusst nicht im Executor von
 * {@code Repository}: der ist einfach besetzt und trägt die gesamte Datenbankarbeit, ein hängender
 * Server würde dort jede andere Abfrage mitblockieren. Jeder dieser Fäden kommt irgendwann auf den
 * Bedienfaden zurück, und dazwischen kann der Nutzer weggegangen oder das Gerät gedreht worden sein.
 * Ein blankes {@code runOnUiThread} weiß davon nichts.</p>
 *
 * <p>Meist ist das nur ärgerlich — der Rückläufer schreibt in Views, die niemand mehr sieht. An
 * Dialogen ist es ein Absturz: {@code dismiss()} oder {@code show()} auf einem Fenster, das die
 * Activity nicht mehr besitzt, beendet die App mit einer {@code BadTokenException} bzw. einer
 * {@code IllegalArgumentException: View not attached to window manager}. Aufgeschlagen ist das schon
 * einmal: Ein CSV-Import lief weiter, nachdem der Nutzer die Maske verlassen hatte, und Banner wie
 * Toast faßten danach ein geschlossenes Fenster an.</p>
 *
 * <p>Die Prüfung stand danach in {@code BackupRestoreController} an einer Stelle für dessen Kette.
 * Hier steht sie an einer Stelle für die ganze App — auch für die Regler
 * ({@code SyncFieldsController}, {@code SmbWizardController}, {@code FullBackupRestoreFlow} …), die
 * keine Activity <em>sind</em>, sondern eine halten. Masken, die von {@link LocalizedActivity} erben,
 * rufen die bequeme Fassung {@link LocalizedActivity#post(Runnable)}.</p>
 *
 * <h2>Was das nicht leistet</h2>
 *
 * <p>Es verhindert den Absturz, nicht den Verlust: Läuft ein langer Import und der Nutzer dreht das
 * Gerät, wird der Rückläufer stillschweigend verworfen und der Vorgang ist neu anzustoßen. Ihn über
 * die Drehung zu retten wäre ein Halter außerhalb der Activity — eine andere, größere Baustelle.</p>
 *
 * <p>Ein Wächter in {@code UiPostTest} meldet, wenn irgendwo wieder ein rohes {@code runOnUiThread}
 * auftaucht. Genau <b>eine</b> begründete Ausnahme gibt es, und sie steht in
 * {@code BackupRestoreController.postRestoreDone()}: Nach einer eingespielten Sicherung muss die App
 * neu aufgesetzt werden, und das <em>gerade auch dann</em>, wenn der Nutzer inzwischen weggetippt hat
 * — sonst bliebe er in einer Maske mit den Daten von vorhin.</p>
 */
public final class Ui {

    private Ui() {
    }

    /**
     * Führt {@code r} auf dem Bedienfaden aus — aber nur, solange es die Maske dort noch gibt.
     *
     * @param activity die Maske; {@code null} ist erlaubt und tut dann nichts, damit Regler, die ihre
     *                 Activity bereits losgelassen haben, keine eigene Prüfung brauchen
     */
    public static void post(Activity activity, Runnable r) {
        if (activity == null || r == null) {
            return;
        }
        activity.runOnUiThread(() -> {
            // Zweimal geprüft, und das mit Absicht: Zwischen dem Einreihen und dem Abarbeiten dieser
            // Nachricht liegt beliebig viel Zeit – die Maske kann genau dazwischen sterben.
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            r.run();
        });
    }
}
