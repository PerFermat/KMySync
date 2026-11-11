package de.spahr.ausgaben.ui;

import android.net.Uri;
import android.text.InputType;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.export.KmyGpg;
import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Die GPG-Schlüssel eines Profils in den Profileinstellungen: einspielen, anzeigen, entfernen. Nötig
 * nur, wenn KMyMoney die .kmy verschlüsselt (siehe {@link KmyGpg}).
 *
 * <p>Eingespielt wird eine Textdatei aus {@code gpg --export-secret-keys --armor} – und für weitere
 * Empfänger der Datei deren öffentliche Schlüssel aus {@code gpg --export --armor}. Die Passphrase wird
 * beim Einspielen einmal geprüft und dann mit den Schlüsseln im {@code SecretStore} abgelegt, so wie
 * das Server-Passwort. Gespeichert wird sofort, nicht erst mit „Speichern“ der Maske.</p>
 */
final class GpgKeysController {

    /** Größer ist kein Schlüsselbund; was darüber liegt, ist sicher die falsche Datei. */
    private static final int MAX_BYTES = 1 << 20;

    private final AppCompatActivity activity;
    private final SettingsStore settings;
    private final TextView status;
    private final ActivityResultLauncher<String[]> waehlen;
    /** Läuft, nachdem Schlüssel übernommen wurden – etwa der Import, der an ihnen gescheitert war. */
    private Runnable danach;

    /** Muss in {@code onCreate} entstehen – dort registriert er die Dateiauswahl. */
    GpgKeysController(AppCompatActivity activity, SettingsStore settings, View knopf, TextView status) {
        this.activity = activity;
        this.settings = settings;
        this.status = status;
        this.waehlen = activity.registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        eingelesen(uri);
                    }
                });
        knopf.setOnClickListener(v -> oeffnen(null));
        aktualisieren();
    }

    /** Die Zeile unter dem Knopf: welche Schlüssel hinterlegt sind. */
    void aktualisieren() {
        List<KmyGpg.Info> liste = hinterlegt();
        if (liste.isEmpty()) {
            status.setText(R.string.gpg_none);
            return;
        }
        List<String> namen = new ArrayList<>();
        for (KmyGpg.Info i : liste) {
            namen.add(zeile(i));
        }
        status.setText(activity.getString(R.string.gpg_status, android.text.TextUtils.join("; ", namen)));
    }

    private List<KmyGpg.Info> hinterlegt() {
        String text = settings.getGpgKeys();
        if (text.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return KmyGpg.Schluessel.aus(text, settings.getGpgPassphrase()).beschreibe();
        } catch (KmyGpg.Fehler e) {
            return new ArrayList<>();
        }
    }

    private String zeile(KmyGpg.Info i) {
        String wer = i.benutzer.isEmpty() ? "" : i.benutzer + " ";
        return wer + "(" + i.keyId + ", "
                + activity.getString(i.privat ? R.string.gpg_private : R.string.gpg_public) + ")";
    }

    /** Wie der Knopf; {@code danach} läuft, sobald ein Schlüssel übernommen ist. */
    void oeffnen(Runnable danach) {
        this.danach = danach;
        zeigen();
    }

    private void zeigen() {
        List<KmyGpg.Info> liste = hinterlegt();
        if (liste.isEmpty()) {
            waehlen.launch(new String[]{"*/*"});
            return;
        }
        List<String> zeilen = new ArrayList<>();
        for (KmyGpg.Info i : liste) {
            zeilen.add(zeile(i));
        }
        new AppDialog(activity)
                .setTitle(R.string.gpg_dialog_title)
                .setItems(zeilen.toArray(new String[0]), null)
                .setPositiveButton(R.string.gpg_add, (d, w) -> waehlen.launch(new String[]{"*/*"}))
                .setNegativeButton(R.string.gpg_remove, (d, w) -> entfernenFragen())
                .setNeutralButton(android.R.string.cancel, null)
                .show();
    }

    private void entfernenFragen() {
        new AppDialog(activity)
                .setTitle(R.string.gpg_dialog_title)
                .setMessage(R.string.gpg_remove_confirm)
                .setPositiveButton(R.string.gpg_remove, (d, w) -> {
                    settings.setGpg("", "");
                    aktualisieren();
                    Toast.makeText(activity, R.string.gpg_removed, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void eingelesen(Uri uri) {
        String neu;
        try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (in != null && (n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) {
                    throw new java.io.IOException("zu groß");
                }
            }
            neu = new String(out.toByteArray(), StandardCharsets.US_ASCII);
        } catch (Exception e) {
            Toast.makeText(activity, R.string.gpg_file_unreadable, Toast.LENGTH_LONG).show();
            return;
        }
        KmyGpg.Schluessel nurNeu;
        try {
            nurNeu = KmyGpg.Schluessel.aus(neu, "");
        } catch (KmyGpg.Fehler e) {
            Toast.makeText(activity, R.string.gpg_file_unreadable, Toast.LENGTH_LONG).show();
            return;
        }
        String alt = settings.getGpgKeys();
        String zusammen = alt.isEmpty() ? neu.trim() : alt.trim() + "\n" + neu.trim();
        if (nurNeu.hatPrivatenHauptschluessel()) {
            // Der ganze private Schlüssel aufs Handy: einmal deutlich sagen, was sicherer wäre.
            new AppDialog(activity)
                    .setTitle(R.string.gpg_master_warning_title)
                    .setMessage(R.string.gpg_master_warning)
                    .setPositiveButton(R.string.gpg_master_continue, (d, w) -> passphraseFragen(zusammen))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else if (nurNeu.hatPrivat()) {
            passphraseFragen(zusammen);
        } else {
            uebernehmen(zusammen, settings.getGpgPassphrase());
        }
    }

    private void passphraseFragen(String zusammen) {
        TextInputLayout rahmen = new TextInputLayout(activity);
        rahmen.setHint(activity.getString(R.string.gpg_passphrase_title));
        rahmen.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        TextInputEditText feld = new TextInputEditText(rahmen.getContext());
        feld.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        rahmen.addView(feld);
        int rand = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        android.widget.FrameLayout huelle = new android.widget.FrameLayout(activity);
        huelle.setPadding(rand, rand / 3, rand, 0);
        huelle.addView(rahmen);
        AlertDialog dialog = new AppDialog(activity)
                .setTitle(R.string.gpg_dialog_title)
                .setView(huelle)
                .setPositiveButton(android.R.string.ok, null)   // erst prüfen, dann schließen
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            // Nicht getrimmt: Leerzeichen am Rand können Teil einer Passphrase sein.
            String passphrase = feld.getText() == null ? "" : feld.getText().toString();
            try {
                KmyGpg.Schluessel.aus(zusammen, passphrase).pruefePassphrase();
            } catch (KmyGpg.Fehler e) {
                rahmen.setError(activity.getString(R.string.gpg_passphrase_wrong));
                return;
            }
            dialog.dismiss();
            uebernehmen(zusammen, passphrase);
        }));
        dialog.show();
    }

    private void uebernehmen(String schluessel, String passphrase) {
        settings.setGpg(schluessel, passphrase);
        aktualisieren();
        Toast.makeText(activity, R.string.gpg_saved, Toast.LENGTH_SHORT).show();
        Runnable weiter = danach;
        danach = null;
        if (weiter != null) {
            weiter.run();
        }
    }
}
