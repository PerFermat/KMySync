package de.spahr.ausgaben.ui;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.Set;
import java.util.function.Supplier;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.location.LocationTagger;
import de.spahr.ausgaben.settings.SettingsStore;
import de.spahr.ausgaben.voice.VoiceInput;
import de.spahr.ausgaben.voice.VoiceRecognizer;

/**
 * Kapselt den Sprach-Erfassungs-Fluss des Hauptbildschirms: Mikrofon starten, den erkannten Satz zerlegen
 * (Empfänger/Betrag) und den vorbefüllten Buchungs-Editor öffnen – mit Empfänger über Aliase/Buchungen,
 * bei reinem Betrag über den Standort. Ausgelagert aus {@code MainActivity}, das den Launcher registriert
 * und dessen Ergebnis hierher (bzw. an {@link #handleVoiceResult(String)}) weiterreicht.
 */
class VoiceEntryController {

    /** „Kein Treffer": der Editor öffnet nur mit dem Betrag, der Empfänger bleibt leer. */
    static final Repository.VoiceResolution NO_PAYEE =
            new Repository.VoiceResolution(null, null, "");

    private final AppCompatActivity activity;
    private final Repository repository;
    private final SettingsStore settings;
    private final LocationTagger locationTagger;
    private final ActivityResultLauncher<Intent> voiceLauncher;
    private final ActivityResultLauncher<String> locationPermissionLauncher;
    private final Supplier<String> selectedAccount;
    private final Supplier<Set<String>> visibleAccounts;

    VoiceEntryController(AppCompatActivity activity, Repository repository, SettingsStore settings,
                         LocationTagger locationTagger, ActivityResultLauncher<Intent> voiceLauncher,
                         ActivityResultLauncher<String> locationPermissionLauncher,
                         Supplier<String> selectedAccount, Supplier<Set<String>> visibleAccounts) {
        this.activity = activity;
        this.repository = repository;
        this.settings = settings;
        this.locationTagger = locationTagger;
        this.voiceLauncher = voiceLauncher;
        this.locationPermissionLauncher = locationPermissionLauncher;
        this.selectedAccount = selectedAccount;
        this.visibleAccounts = visibleAccounts;
    }

    void startVoiceEntry() {
        // Kein Vorab-Check über SpeechRecognizer.isRecognitionAvailable(): der prüft einen eigenen
        // RecognitionService-Dienst und liefert auf manchen Geräten fälschlich „nicht verfügbar",
        // obwohl z. B. die Google-Suchleiste per Mikrofon-Symbol einwandfrei funktioniert (die nutzt die
        // gleiche ACTION_RECOGNIZE_SPEECH-Activity). Stattdessen wird direkt versucht zu starten; schlägt
        // das fehl, fängt der catch-Block unten (ActivityNotFoundException) das echte „kein Erkenner" ab.
        // Standort für eine mögliche Betrag-only-Auflösung vorwärmen (nur bei aktivem GPS).
        if (settings.isGpsEnabled() && locationTagger != null && !hasLocationPermission()) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        Intent intent = VoiceRecognizer.freeFormIntent(activity);
        try {
            voiceLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, R.string.voice_no_recognizer, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Zerlegt den gesprochenen Satz und öffnet den vorbefüllten Editor. Mit Empfänger wird über
     * Aliase/Buchungen aufgelöst; bei <b>reinem Betrag</b> über den aktuellen Standort (100 m).
     */
    void handleVoiceResult(String spoken) {
        VoiceInput.Result parsed = VoiceInput.parse(spoken, settings.getCurrency());
        final long amount = parsed.amountCents == null ? -1 : parsed.amountCents;
        if (parsed.payee.isEmpty()) {
            // Ohne Betrag bleibt gar nichts übrig – dann wurde wirklich nichts verstanden.
            if (amount <= 0) {
                Toast.makeText(activity, R.string.voice_not_understood, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!settings.isGpsEnabled()) {
                // Hier stand bis 2.0 ebenfalls eine Abweisung: Ohne Standort gab es nichts aufzulösen,
                // und eine Buchung ohne Empfänger ließ sich nicht speichern. Der reine Betrag war damit
                // eine Sackgasse – auch aus dem Zifferndialog heraus, der genau hier hereinkommt, ging
                // die eingetippte Summe still verloren. Seit der Empfänger freiwillig ist, ist sie eine
                // vollständige Buchung: Editor auf, Betrag drin, Empfänger leer.
                openVoiceEditor(NO_PAYEE, amount, "");
                return;
            }
            // Nur Betrag → per Standort auflösen (kein Treffer → Editor nur mit Betrag).
            String coords = locationTagger != null ? locationTagger.currentCoordinates() : null;
            repository.resolveNearby(coords, amount, Repository.VOICE_TYPE_EXPENSE, visibleAccounts.get(),
                    list -> openVoiceEditor(list.isEmpty() ? NO_PAYEE : list.get(0), amount, ""));
            return;
        }
        // Aktuelle Position mitgeben (nur bei GPS an) → bei mehreren gleichnamigen Empfängern der nächste.
        String coords = settings.isGpsEnabled() && locationTagger != null
                ? locationTagger.currentCoordinates() : null;
        repository.resolveVoice(parsed.payee, coords, res -> openVoiceEditor(res, amount, parsed.payee));
    }

    void openVoiceEditor(Repository.VoiceResolution res, long amount, String spokenPayee) {
        String account = selectedAccount.get();
        Intent i = new Intent(activity, BookingEditActivity.class);
        // Angezeigtes Konto ist eine Nutzereingabe: steckt es bei einer Umbuchung bereits als Von- oder
        // Nach-Konto in Alias/Vorlage, bleiben dort beide Konten unverändert; sonst ersetzt es das
        // Von-Konto (nur bei „Alle Konten" – selectedAccount leer – bleibt Alias/Vorlage maßgeblich).
        if (!account.isEmpty()) {
            i.putExtra(BookingEditActivity.EXTRA_PRESET_TRANSFER_FROM_ACCOUNT, account);
        }
        if (res.booking != null) {
            i.putExtra(BookingEditActivity.EXTRA_TEMPLATE_BOOKING_ID, res.booking.id);
        } else {
            if (!res.payee.isEmpty()) {
                i.putExtra(BookingEditActivity.EXTRA_PREFILL_PAYEE, res.payee);
            }
            if (res.alias != null) {
                i.putExtra(BookingEditActivity.EXTRA_ALIAS_ID, res.alias.id);
            } else if (!account.isEmpty()) {
                // Kein Alias/keine Vorlage liefert ein Konto → angezeigtes Konto vorbelegen.
                i.putExtra(BookingEditActivity.EXTRA_PRESET_ACCOUNT, account);
            }
            if (spokenPayee != null && !spokenPayee.isEmpty()) {
                // Ursprünglich Gesprochenes: bei Änderung des Empfängers kann ein Alias gelernt werden.
                i.putExtra(BookingEditActivity.EXTRA_VOICE_SPOKEN_PAYEE, spokenPayee);
                Toast.makeText(activity, activity.getString(R.string.voice_not_found, res.payee),
                        Toast.LENGTH_SHORT).show();
            }
        }
        i.putExtra(BookingEditActivity.EXTRA_VOICE_AMOUNT_CENTS, amount);
        activity.startActivity(i);
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }
}
