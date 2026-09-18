package de.spahr.ausgaben.ui;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Locale;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.settings.SettingsStore;
import de.spahr.ausgaben.voice.VoiceRecognizer;

/**
 * Unsichtbare Aktivität für die Sprach-Schnellerfassung aus dem Typ-Widget: startet sofort die
 * Spracherkennung (nur der System-Dialog erscheint, die App wird nicht geöffnet) und legt aus dem
 * gesprochenen Satz eine Buchung des vorgegebenen Typs an – wie auf der Uhr. Kein sichtbares UI.
 */
public class VoiceCaptureActivity extends LocalizedActivity {

    /** Vorgegebener Buchungstyp (siehe {@code Repository.VOICE_TYPE_*}). */
    public static final String EXTRA_TYPE = "type";
    /** Zielkonto (aus dem Typ-Widget); leer/fehlend = Standardkonto. */
    public static final String EXTRA_ACCOUNT = "account";
    /** Gewählter Ort des Kontos (aus dem Typ-Widget); leer = Standardort. */
    public static final String EXTRA_PLACE = "place";

    private String type;
    private ActivityResultLauncher<Intent> speechLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        type = getIntent().getStringExtra(EXTRA_TYPE);
        if (type == null) {
            type = Repository.VOICE_TYPE_EXPENSE;
        }
        speechLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        ArrayList<String> res = result.getData()
                                .getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                        createBooking(VoiceRecognizer.pickBestSpoken(res));
                    } else {
                        finish();
                    }
                });
        if (savedInstanceState == null) {
            startSpeech();
        }
    }

    private void startSpeech() {
        // Kein Vorab-Check über SpeechRecognizer.isRecognitionAvailable(): der prüft einen eigenen
        // RecognitionService-Dienst und liefert auf manchen Geräten fälschlich „nicht verfügbar", obwohl
        // z. B. die Google-Suchleiste per Mikrofon-Symbol funktioniert (gleiche ACTION_RECOGNIZE_SPEECH-
        // Activity). Der catch-Block unten (ActivityNotFoundException) fängt das echte „kein Erkenner" ab.
        Intent intent = VoiceRecognizer.freeFormIntent(this);
        try {
            speechLauncher.launch(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.voice_no_recognizer, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    /** Legt aus dem gesprochenen Text eine Buchung des vorgegebenen Typs an (im Hintergrund). */
    private void createBooking(String text) {
        if (text == null || text.trim().isEmpty()) {
            Toast.makeText(this, R.string.voice_not_understood, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        final String spoken = text.trim();
        final String coords = lastKnownCoords();
        String extraAccount = getIntent().getStringExtra(EXTRA_ACCOUNT);
        final String targetAccount = extraAccount != null && !extraAccount.isEmpty()
                ? extraAccount
                : new SettingsStore(getApplicationContext()).getDefaultAccount();
        String extraPlace = getIntent().getStringExtra(EXTRA_PLACE);
        final String targetPlace = extraPlace == null ? "" : extraPlace;
        new Thread(() -> {
            Repository repository = new Repository(getApplicationContext());
            boolean created = repository.createVoiceBookingBlocking(
                    spoken, targetAccount, targetPlace, type, coords);
            post(() -> {
                Toast.makeText(this, created ? R.string.booking_saved : R.string.voice_not_understood,
                        Toast.LENGTH_LONG).show();
                if (created) {
                    // Offene App + Widgets + Uhr über die neue Buchung informieren.
                    sendBroadcast(new Intent(de.spahr.ausgaben.wear.WearBridge.ACTION_BOOKINGS_CHANGED)
                            .setPackage(getPackageName()));
                    de.spahr.ausgaben.widget.AusgabenWidget.refreshAll(this);
                    de.spahr.ausgaben.wear.BalanceSync.publish(this);
                }
                finish();
            });
        }).start();
    }

    /** Letzte bekannte Position als „lat, lon" (nur bei aktivem GPS + Berechtigung), sonst {@code null}. */
    private String lastKnownCoords() {
        if (!new SettingsStore(this).isGpsEnabled()) {
            return null;
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm == null) {
                return null;
            }
            Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc == null) {
                loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
            if (loc == null) {
                return null;
            }
            return String.format(Locale.US, "%.6f, %.6f", loc.getLatitude(), loc.getLongitude());
        } catch (SecurityException e) {
            return null;
        }
    }
}
