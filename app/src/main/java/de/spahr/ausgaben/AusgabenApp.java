package de.spahr.ausgaben;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;

import de.spahr.ausgaben.security.AppLockGate;
import de.spahr.ausgaben.settings.SettingsStore;
import de.spahr.ausgaben.ui.LockActivity;

/**
 * Application-Einstieg. Setzt den Nacht-Modus und steuert – bei aktiver Einstellung – die optionale
 * biometrische App-Sperre.
 *
 * <p>Über {@link ActivityLifecycleCallbacks} wird der Wechsel in den Hintergrund erkannt (Zähler
 * gestarteter Activities). Kehrt die App in den Vordergrund zurück (oder startet kalt) und ist die Sperre
 * aktiv und noch nicht entsperrt, wird eine {@link LockActivity} über die aktuelle Ansicht gelegt. Ein
 * Konfigurationswechsel (Rotation) löst dank {@link Activity#isChangingConfigurations()} keine erneute
 * Sperre aus.</p>
 *
 * <p>Der Zähler allein kann „der Nutzer ist weggegangen" nicht von „die App hat selbst die Kamera
 * gestartet" unterscheiden – in beiden Fällen läuft keine eigene Activity mehr. Deshalb meldet
 * {@link de.spahr.ausgaben.ui.LocalizedActivity} jeden Absprung in eine fremde App über
 * {@link #noteExternalHandoff()}; nach einer solchen Übergabe bleibt die App beim Zurückkommen entsperrt,
 * solange die Kulanzfrist {@link AppLockGate#GRACE_MS} nicht überschritten ist.</p>
 */
public class AusgabenApp extends Application implements Application.ActivityLifecycleCallbacks {

    private SettingsStore settings;
    private int startedActivities;
    /** false = App muss (wieder) entsperrt werden. Startet gesperrt (Kaltstart erzwingt Auth). */
    private boolean unlocked = false;
    /** true, solange die LockActivity bereits angezeigt wird (verhindert Mehrfach-Start). */
    private boolean lockShowing = false;
    /** true, sobald die App selbst eine fremde App gestartet hat (Kamera, Dateiwahl, Spracherkennung). */
    private boolean handoffPending = false;
    /** elapsedRealtime beim Wechsel in den Hintergrund; 0 = seit der Übergabe kein Wechsel. */
    private long leftAt;

    @Override
    public void onCreate() {
        super.onCreate();
        // Vor allem anderen: Bestandsinstallationen bekommen einmalig ein Profil (bestehende DB
        // bleibt liegen), Neuinstallationen ein leeres Erstprofil. SettingsStore liest ab hier
        // profilbezogen, muss also erst danach konstruiert werden.
        de.spahr.ausgaben.settings.ProfileManager.migrateLegacyInstallationIfNeeded(this);
        settings = new SettingsStore(this);
        // Sprache seeden (falls leer) und aktive Sprache laden, bevor die erste Activity Texte anfragt.
        de.spahr.ausgaben.i18n.LocaleManager.init(this);
        // Per-App-Sprache anwenden: nutzt die kompilierten Ressourcen (DE/EN) für die gesamte Oberfläche.
        AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.forLanguageTags(settings.getLanguage()));
        AppCompatDelegate.setDefaultNightMode(settings.getNightMode());
        de.spahr.ausgaben.settings.Currencies.refresh(this);
        de.spahr.ausgaben.settings.MoneyFormat.refresh(this);
        // Globale Schriftgröße laden, bevor die erste Activity ihren (skalierten) Context aufbaut.
        de.spahr.ausgaben.settings.FontScale.refresh(this);
        // Tageswecker der Erinnerung sicherstellen (bzw. abbestellen, wenn ausgeschaltet).
        de.spahr.ausgaben.notify.ScheduledReminder.apply(this);
        // Wear-Texte einmal je App-Start an die Uhr publizieren, damit Textänderungen (auch nach einem
        // App-Update) ankommen (full: echte Sync, foss: No-op-Stub).
        de.spahr.ausgaben.wear.LanguageSync.publish(this);
        registerActivityLifecycleCallbacks(this);
    }

    /** Markiert die App als entsperrt (nach erfolgreicher Auth bzw. bei ausgeschalteter Sperre). */
    public void markUnlocked() {
        unlocked = true;
        lockShowing = false;
    }

    /**
     * Meldet, dass die App gerade selbst eine fremde App startet. Der folgende Wechsel in den Hintergrund
     * stellt die Sperre dann nicht sofort scharf – siehe Klassenkommentar.
     */
    public void noteExternalHandoff() {
        handoffPending = true;
        leftAt = 0;
    }

    private boolean lockRequired() {
        return settings.isAppLockEnabled() && !unlocked;
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        if (handoffPending) {
            // leftAt == 0: der Absprung kam nie zustande (z. B. keine passende App) – nichts nachzuholen.
            if (leftAt != 0 && AppLockGate.graceExpired(leftAt, SystemClock.elapsedRealtime())) {
                unlocked = false; // zu lange weg → die Übergabe zählt nicht mehr
            }
            handoffPending = false;
            leftAt = 0;
        }
        if (activity instanceof LockActivity) {
            return; // der Sperrbildschirm selbst darf laufen
        }
        if (activity instanceof de.spahr.ausgaben.ui.VoiceCaptureActivity) {
            return; // unsichtbare Sprach-Schnellerfassung (Widget) legt nur eine Buchung an – kein Datenzugriff
        }
        if (lockRequired() && !lockShowing) {
            lockShowing = true;
            Intent intent = new Intent(activity, LockActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            activity.startActivity(intent);
        }
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        startedActivities++;
        // Derselbe Zähler, zweiter Zweck: lange Netzläufe (Beleg-Export) legen sich schlafen, solange
        // keine Ansicht sichtbar ist – siehe ForegroundGate.
        de.spahr.ausgaben.util.ForegroundGate.enter();
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        startedActivities--;
        de.spahr.ausgaben.util.ForegroundGate.leave();
        // Alle Activities gestoppt und kein reiner Konfigurationswechsel → App ist im Hintergrund → sperren.
        if (startedActivities <= 0 && !activity.isChangingConfigurations()) {
            startedActivities = 0;
            // elapsedRealtime, damit eine gestellte Uhr die Kulanzfrist nicht verlängern kann.
            leftAt = SystemClock.elapsedRealtime();
            if (!handoffPending) {
                unlocked = false;
            }
        }
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
    }
}
