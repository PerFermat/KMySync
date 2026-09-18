package de.spahr.ausgaben.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.LayoutInflaterCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import de.spahr.ausgaben.AusgabenApp;
import de.spahr.ausgaben.i18n.I18nViewFactory;
import de.spahr.ausgaben.i18n.LocaleContextWrapper;
import de.spahr.ausgaben.security.AppLockGate;

/**
 * Basis-Activity aller App-Screens. Hüllt den Context in einen {@link LocaleContextWrapper} (übersetzt
 * Code-Texte) und installiert eine {@link I18nViewFactory} (übersetzt Layout-Texte beim Aufblasen). So
 * erscheinen alle Texte in der gewählten Sprache.
 *
 * <p>Außerdem die eine Stelle, an der jeder Absprung in eine fremde App auffällt: sowohl
 * {@code startActivity} als auch die {@code ActivityResultLauncher} (Kamera, Galerie, Dateiauswahl,
 * Spracherkennung) laufen am Ende durch die hier überschriebenen Methoden. Führt der Intent aus der App
 * heraus, erfährt {@link AusgabenApp} davon und setzt die Sperre nicht sofort scharf.</p>
 */
public class LocalizedActivity extends AppCompatActivity {

    private Resources translatedResources;
    private Resources translatedBase;
    /** Schriftgrößen-Faktor, mit dem diese Activity aufgebaut wurde (für Live-Neuaufbau bei Änderung). */
    private float appliedFontScale = 1f;
    @Override
    protected void attachBaseContext(Context base) {
        // Globale Schriftgröße anwenden: fontScale multiplikativ (stapelt auf die System-Schriftgröße,
        // „normal" = Faktor 1,0 = heutiges Verhalten). Alle sp-Texte skalieren dadurch automatisch.
        appliedFontScale = de.spahr.ausgaben.settings.FontScale.factor();
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.fontScale *= appliedFontScale;
        Context scaled = base.createConfigurationContext(cfg);
        super.attachBaseContext(LocaleContextWrapper.wrap(scaled));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Wurde die Schriftgröße in den Einstellungen geändert, zieht jede Ansicht beim Anzeigen nach.
        if (appliedFontScale != de.spahr.ausgaben.settings.FontScale.factor()) {
            recreate();
            return;
        }
        // Akzentfarbe (Profilwechsel/-bearbeitung): ohne Neuaufbau neu einfärben.
        applyAccentColor();
    }

    /**
     * Schließt die System-Tastatur, sobald sie sichtbar wird, während ein an die eigene Rechentastatur
     * (CalcKeyboardView) gebundenes Feld den Fokus hält.
     *
     * <p>Zwei einfachere Ansätze scheiterten: {@code onWindowFocusChanged} (Fensterfokus nach Rückkehr aus
     * einer Fremd-App, z. B. PDF-Anzeige) kommt teils zu früh, weil das System die Tastatur dort erst
     * <em>danach</em> wieder einblendet. Ein {@code OnApplyWindowInsetsListener} direkt an der
     * {@code DecorView} verhindert deren eigene interne Einfärbung von Statusleiste/Kamera-Ausschnitt; am
     * Content-Container ({@code android.R.id.content}) gesetzt, erreichte ihn die Dispatch-Kette in dieser
     * (nicht auf Edge-to-Edge umgestellten) App gar nicht erst (per Logcat bestätigt: keine einzige
     * Auslösung trotz mehrfacher Tastatur-Ein-/Ausblendungen).</p>
     *
     * <p>Ein {@link android.view.ViewTreeObserver.OnGlobalLayoutListener} läuft unabhängig von dieser
     * Dispatch-Kette bei jeder Layout-Änderung — auch beim Ein-/Ausblenden der Tastatur — und fragt den
     * aktuellen Sichtbarkeitsstatus direkt über {@link ViewCompat#getRootWindowInsets} ab.</p>
     */
    private void installSystemKeyboardGuard() {
        View content = getWindow().getDecorView().findViewById(android.R.id.content);
        content.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(content);
            if (insets != null && insets.isVisible(WindowInsetsCompat.Type.ime())) {
                View focused = getCurrentFocus();
                if (focused instanceof EditText && CalcKeyboardView.isBoundField(focused)) {
                    focused.post(() -> Keyboard.hide(focused));
                }
            }
        });
    }

    /**
     * Etwas auf dem Bedienfaden tun — aber nur, solange es diese Maske noch gibt. Der Rückweg für alles,
     * was aus einem Hintergrundfaden zurückkommt; die Begründung steht bei {@link Ui#post}.
     */
    protected void post(Runnable r) {
        Ui.post(this, r);
    }

    /** Färbt Toolbar/Statusleiste/Buttons mit der aktuellen Profil-Akzentfarbe ein. */
    protected void applyAccentColor() {
        de.spahr.ausgaben.settings.AccentColor.apply(this);
    }

    /**
     * Übersetzte {@link Resources} auch für die Activity selbst – so ketten Material-getönte Contexts
     * (Dialog-Buttons, {@code MaterialButton.setText(resId)}) und die Menü-Inflation an die
     * Übersetzungstabelle. Bei Konfigurationswechsel (neue Basis-Resources) wird neu umhüllt.
     */
    @Override
    public Resources getResources() {
        Resources base = super.getResources();
        if (translatedResources == null || translatedBase != base) {
            translatedBase = base;
            translatedResources = LocaleContextWrapper.translate(base);
        }
        return translatedResources;
    }

    @Override
    public void startActivity(Intent intent, @Nullable Bundle options) {
        noteHandoff(intent);
        super.startActivity(intent, options);
    }

    @Override
    public void startActivityForResult(Intent intent, int requestCode, @Nullable Bundle options) {
        noteHandoff(intent);
        super.startActivityForResult(intent, requestCode, options);
    }

    /** Verlässt der Absprung die eigene App, merkt sich die Application die Übergabe. */
    private void noteHandoff(Intent intent) {
        ComponentName target = intent.getComponent();
        if (AppLockGate.leavesApp(getPackageName(), target == null ? null : target.getPackageName())
                && getApplication() instanceof AusgabenApp) {
            ((AusgabenApp) getApplication()).noteExternalHandoff();
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LayoutInflater inflater = LayoutInflater.from(this);
        // Vor super.onCreate setzen, damit AppCompat keine eigene Factory installiert.
        if (inflater.getFactory2() == null) {
            LayoutInflaterCompat.setFactory2(inflater, new I18nViewFactory(getDelegate(), inflater));
        }
        super.onCreate(savedInstanceState);
        installSystemKeyboardGuard();
    }
}
