package de.spahr.ausgaben.ui;

import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.export.KmyGpg;
import de.spahr.ausgaben.export.KmySqlCipher;
import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Schlüssel und Passwort für eine verschlüsselte KMyMoney-Datei ({@code include_kmy_encryption}), im
 * Willkommen-Assistenten wie in den Profileinstellungen: GPG-Schlüssel bei einer .kmy, das Passwort bei
 * einer Datenbank (SQLCipher).
 *
 * <p>Scheitert ein Import daran, dass Schlüssel oder Passwort fehlen oder nicht passen, öffnet
 * {@link #nachFehler} gleich den passenden Dialog und startet den Import danach noch einmal – so muss
 * niemand erst wissen, dass die Datei verschlüsselt ist.</p>
 */
final class EncryptionFields {

    private final View gpgKnopf;
    private final View gpgStatus;
    private final View dbKnopf;
    private final View dbStatus;
    private final GpgKeysController gpg;
    private final DbPasswordController db;

    /** Muss in {@code onCreate} entstehen – der GPG-Teil registriert dort die Dateiauswahl. */
    EncryptionFields(AppCompatActivity activity, SettingsStore settings) {
        gpgKnopf = activity.findViewById(R.id.btnGpgKeys);
        gpgStatus = activity.findViewById(R.id.gpgStatus);
        dbKnopf = activity.findViewById(R.id.btnDbPassword);
        dbStatus = activity.findViewById(R.id.dbPasswordStatus);
        gpg = new GpgKeysController(activity, settings, gpgKnopf, activity.findViewById(R.id.gpgStatus));
        db = new DbPasswordController(activity, settings, dbKnopf, activity.findViewById(R.id.dbPasswordStatus));
    }

    /** Verschlüsselt wird die .kmy mit GPG, die Datenbank mit einem Passwort. */
    void sichtbarkeit(boolean kmyQuelle, boolean datenbank) {
        int g = kmyQuelle && !datenbank ? View.VISIBLE : View.GONE;
        int d = kmyQuelle && datenbank ? View.VISIBLE : View.GONE;
        gpgKnopf.setVisibility(g);
        gpgStatus.setVisibility(g);
        dbKnopf.setVisibility(d);
        dbStatus.setVisibility(d);
    }

    /**
     * Lag der Fehler an Schlüssel oder Passwort, öffnet das den passenden Dialog und führt danach
     * {@code nochmal} aus; Rückgabe {@code true}. Sonst {@code false}, und der Aufrufer meldet den
     * Fehler wie bisher. Auf dem Main-Thread aufzurufen.
     */
    boolean nachFehler(Throwable fehler, Runnable nochmal) {
        for (Throwable t = fehler; t != null; t = t.getCause()) {
            if (t instanceof KmyGpg.Fehler) {
                KmyGpg.Art art = ((KmyGpg.Fehler) t).art;
                if (art != KmyGpg.Art.KEIN_SCHLUESSEL && art != KmyGpg.Art.PASSPHRASE) {
                    return false;
                }
                fragen(fehler, R.string.gpg_dialog_title, R.string.gpg_import, () -> gpg.oeffnen(nochmal));
                return true;
            }
            if (t instanceof KmySqlCipher.Fehler) {
                fragen(fehler, R.string.db_password_title, R.string.db_password_button,
                        () -> db.fragen(nochmal));
                return true;
            }
        }
        return false;
    }

    /** Die Meldung ganz, in einem Dialog – eine Einblendung schnitte sie nach zwei Zeilen ab. */
    private void fragen(Throwable fehler, int titel, int knopf, Runnable weiter) {
        new AppDialog(gpgKnopf.getContext())
                .setTitle(titel)
                .setMessage(fehler.getMessage())
                .setPositiveButton(knopf, (d, w) -> weiter.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
