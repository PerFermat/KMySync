package de.spahr.ausgaben.ui;

import android.text.InputType;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Das Passwort einer mit SQLCipher verschlüsselten KMyMoney-Datenbank in den Profileinstellungen
 * (siehe {@link de.spahr.ausgaben.export.KmySqlCipher}). Ob es passt, zeigt der nächste Import – ohne
 * die Datei lässt es sich nicht prüfen. Gespeichert wird sofort, im {@code SecretStore} wie das
 * Server-Passwort.
 */
final class DbPasswordController {

    private final AppCompatActivity activity;
    private final SettingsStore settings;
    private final TextView status;

    DbPasswordController(AppCompatActivity activity, SettingsStore settings, View knopf, TextView status) {
        this.activity = activity;
        this.settings = settings;
        this.status = status;
        knopf.setOnClickListener(v -> fragen());
        aktualisieren();
    }

    private void aktualisieren() {
        status.setText(settings.getDbPassword().isEmpty() ? R.string.db_password_none : R.string.db_password_set);
    }

    private void fragen() {
        TextInputLayout rahmen = new TextInputLayout(activity);
        rahmen.setHint(activity.getString(R.string.db_password_hint));
        rahmen.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        TextInputEditText feld = new TextInputEditText(rahmen.getContext());
        feld.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        rahmen.addView(feld);
        int rand = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        android.widget.FrameLayout huelle = new android.widget.FrameLayout(activity);
        huelle.setPadding(rand, rand / 3, rand, 0);
        huelle.addView(rahmen);
        AppDialog d = new AppDialog(activity);
        d.setTitle(R.string.db_password_title)
                .setMessage(R.string.db_password_message)
                .setView(huelle)
                .setPositiveButton(android.R.string.ok, (dialog, w) -> {
                    // Nicht getrimmt: Leerzeichen am Rand können Teil des Passworts sein.
                    String pw = feld.getText() == null ? "" : feld.getText().toString();
                    if (pw.isEmpty()) {
                        return;
                    }
                    settings.setDbPassword(pw);
                    aktualisieren();
                    Toast.makeText(activity, R.string.db_password_saved, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null);
        if (!settings.getDbPassword().isEmpty()) {
            d.setNeutralButton(R.string.db_password_remove, (dialog, w) -> {
                settings.setDbPassword("");
                aktualisieren();
                Toast.makeText(activity, R.string.db_password_removed, Toast.LENGTH_SHORT).show();
            });
        }
        d.show();
    }
}
