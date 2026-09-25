package de.spahr.ausgaben.wear;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.UUID;

/**
 * Einziger Screen der Wear-App, in vier Flächen (siehe {@code showOnly}):
 *
 * <ul>
 *   <li><b>Typ wählen</b> – Einnahme/Umbuchung/Ausgabe; danach hört die App über die
 *       {@link SpeechRecognizer}-API automatisch zu (kein manuelles Bestätigen).</li>
 *   <li><b>Zuhören</b> – „Moment…"/„Sprich jetzt", mit dem Weg in den Zahlenblock.</li>
 *   <li><b>Zahlenblock</b> – stille Eingabe nur des Betrags.</li>
 *   <li><b>Bestätigen</b> – für <i>beide</i> Wege dieselbe Fläche: Betrag, Empfänger und
 *       „Abbrechen (n)". Wird 10 s lang nichts geändert, wird gebucht.</li>
 * </ul>
 *
 * <p>Auf der Bestätigungsfläche steht ein gesprochener Empfänger vorn und bleibt, wenn man nichts
 * tut; dahinter stehen die Empfänger im 100-m-Umkreis, zwischen denen der graue Knopf durchschaltet.
 * Wurde nur ein Betrag gesagt oder getippt, ist die Runde dieselbe.</p>
 *
 * <p>Der Eintrag wird sofort lokal gespeichert (nichts geht verloren), aber erst nach Ablauf der 10 s
 * gesendet (über {@link PendingEntry#readyAt}). „Abbrechen" entfernt ihn vorher wieder.</p>
 */
public class WearMainActivity extends WearLocalizedActivity {

    /** Von der Kachel gesetzter Typ → App startet direkt die Sprache dafür. */
    public static final String EXTRA_TYPE = "de.spahr.ausgaben.wear.TYPE";

    private static final long CANCEL_WINDOW_MS = 10_000L;
    /** Reserve, bis der Standort aufgelöst ist (danach setzt updateGps readyAt auf jetzt). */
    private static final long LOCATION_WAIT_MS = 65_000L;

    private PendingStore store;
    private View typeSelection;
    private View confirmView;
    /** Einmalige Meldung (Mikrofon/kein Erkenner/nicht verstanden/offline) – ersetzt in der einzigen Zeile
     * Konto+Saldo; {@code null} = keine. Es gibt auf der Uhr bewusst nur eine Zeile. */
    private String transientStatus;
    private TextView balanceView;
    private View btnCycle;
    private final android.os.Handler revertHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable revertRunnable = () -> {
        BalanceStore.reset(this);
        updateBalance();
    };
    private TextView confirmType;
    private TextView confirmText;
    private View btnNumberPad;
    private View numberView;
    private TextView numberDisplay;
    /** Aktuell eingegebener Betrag (stille Zifferneingabe). */
    private final StringBuilder amountInput = new StringBuilder();
    private boolean numberEntryActive;

    /** Zweite Seite der Zifferneingabe: Betrag, Empfänger, Widerruf. */
    private View numberConfirmView;
    private TextView confirmAmount;
    private Button btnCancelNumber;
    private View btnPayeeNext;
    private TextView payeeName;
    /** Betrag der laufenden Bestätigung – der Text wird bei jedem Empfängerwechsel neu gebaut. */
    private String pendingAmount = "";
    /** Gesprochener Empfänger dieser Bestätigung; steht als Vorgabe vorn (leer beim Zahlenblock). */
    private String spokenPayee = "";
    /** Der gesprochene Satz im Wortlaut – er geht unverändert hinaus, solange die Vorgabe steht. */
    private String spokenText = "";
    /** Empfänger im 100-m-Umkreis, der nächstgelegene zuerst; leer = keiner in der Nähe. */
    private final java.util.List<String> payeeCandidates = new java.util.ArrayList<>();
    /** Gewählter Empfänger; die Stelle hinter dem letzten steht für „ohne Empfänger". */
    private int payeePick;

    /** Zuletzt geprüfte Erreichbarkeit des Phones; optimistisch, bis die Abfrage antwortet. */
    private boolean phoneConnected = true;

    /** Offline-Sprachmodell nur einmal je Prozess prüfen/anstoßen. */
    private static boolean offlineModelChecked;

    private String pendingType = WearPaths.TYPE_EXPENSE;
    private String confirmEntryId;
    private CountDownTimer confirmTimer;
    private SpeechRecognizer speech;
    /**
     * Ob der Erkennungsdienst dieses Versuchs schon <b>Sprache angefangen</b> hat.
     *
     * <p>Der Unterschied zwischen „nichts gesagt" und „zu früh gesagt": Hat der Dienst nie einen
     * Sprachanfang gemeldet und trotzdem nichts erkannt, hat er beim Losreden noch gar nicht
     * aufgenommen. Genau dann lohnt eine stille Wiederholung — siehe {@link #wiederholungsVersuche}.</p>
     */
    private boolean speechBegonnen;
    /**
     * Wie oft für diese Eingabe schon still wiederholt wurde. Höchstens einmal: eine Uhr, die
     * unbemerkt immer weiter zuhört, wäre schlimmer als eine, die aufgibt.
     */
    private int wiederholungsVersuche;
    /**
     * Wache über die Aufnahmebereitschaft: Meldet der Dienst sie nicht, bliebe „Moment…" für immer
     * stehen — und die Uhr wäre stumm, ohne es zu sagen. Dann lieber in den Zahlenblock, wie bei jedem
     * anderen Scheitern auch: die Eingabe muss möglich bleiben.
     */
    private final android.os.Handler bereitschaftsWache =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable bereitschaftAbgelaufen = new Runnable() {
        @Override
        public void run() {
            destroySpeech();
            onListenError(SpeechRecognizer.ERROR_CLIENT);
        }
    };
    /** So lange wird auf die Aufnahmebereitschaft gewartet. Der Kaltstart braucht knapp eine Sekunde. */
    private static final long BEREITSCHAFT_TIMEOUT_MS = 6000L;
    private ActivityResultLauncher<String> permissionLauncher;
    private WearLocation location;
    private ActivityResultLauncher<String> locationPermissionLauncher;

    private final BroadcastReceiver pendingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateStatus();
            updateBalance();   // Grund bzw. Saldo folgt der Warteschlange
        }
    };

    private final BroadcastReceiver balanceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateBalance();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wear_main);
        store = new PendingStore(this);
        typeSelection = findViewById(R.id.typeSelection);
        confirmView = findViewById(R.id.confirmView);
        balanceView = findViewById(R.id.balanceView);
        confirmType = findViewById(R.id.confirmType);
        confirmText = findViewById(R.id.confirmText);

        findViewById(R.id.btnIncome).setOnClickListener(v -> chooseType(WearPaths.TYPE_INCOME));
        findViewById(R.id.btnTransfer).setOnClickListener(v -> chooseType(WearPaths.TYPE_TRANSFER));
        findViewById(R.id.btnExpense).setOnClickListener(v -> chooseType(WearPaths.TYPE_EXPENSE));

        // Grauer Wechsel-Knopf: Konto/Ort durchschalten (Auto-Rücksprung nach dem Timeout).
        btnCycle = findViewById(R.id.btnCycle);
        btnCycle.setOnClickListener(v -> cycleSelection());

        // Stille Zifferneingabe (Zahlenblock).
        btnNumberPad = findViewById(R.id.btnNumberPad);
        numberView = findViewById(R.id.numberView);
        numberDisplay = findViewById(R.id.numberDisplay);
        btnNumberPad.setOnClickListener(v -> showNumberPad());
        int[] digitIds = {R.id.btnD0, R.id.btnD1, R.id.btnD2, R.id.btnD3, R.id.btnD4,
                R.id.btnD5, R.id.btnD6, R.id.btnD7, R.id.btnD8, R.id.btnD9};
        for (int id : digitIds) {
            Button b = findViewById(id);
            b.setOnClickListener(v -> appendDigit(((Button) v).getText().toString()));
        }
        findViewById(R.id.btnComma).setOnClickListener(v -> appendComma());
        findViewById(R.id.btnBack).setOnClickListener(v -> backspace());
        findViewById(R.id.btnEnter).setOnClickListener(v -> submitNumber());

        // Zweite Seite: Betrag bestätigen, Empfänger durchschalten, Widerruf mit Countdown.
        numberConfirmView = findViewById(R.id.numberConfirmView);
        confirmAmount = findViewById(R.id.confirmAmount);
        btnCancelNumber = findViewById(R.id.btnCancelNumber);
        btnCancelNumber.setOnClickListener(v -> cancelConfirm());
        payeeName = findViewById(R.id.payeeName);
        btnPayeeNext = findViewById(R.id.btnPayeeNext);
        // Ein Tipp auf den Namen schaltet ebenfalls weiter – auf dem kleinen Bildschirm trifft man
        // ihn eher als den Knopf.
        btnPayeeNext.setOnClickListener(v -> cyclePayee());
        payeeName.setOnClickListener(v -> cyclePayee());

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        startListening();
                    } else {
                        showTypeSelection();
                        showStatus(getString(R.string.wear_no_mic));
                    }
                });

        // Standort zum Sprechzeitpunkt bestimmen: Berechtigung anfragen und Empfang vorwärmen.
        location = new WearLocation(this);
        locationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        location.start();
                    }
                });
        if (hasLocationPermission()) {
            location.start();
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        // Direkter Start aus der Kachel: sofort für den gewählten Typ zuhören.
        handleLaunchType(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchType(intent);
    }

    private void handleLaunchType(Intent intent) {
        if (intent == null) {
            return;
        }
        String type = intent.getStringExtra(EXTRA_TYPE);
        intent.removeExtra(EXTRA_TYPE); // nur einmal auslösen
        if (WearPaths.TYPE_INCOME.equals(type) || WearPaths.TYPE_TRANSFER.equals(type)
                || WearPaths.TYPE_EXPENSE.equals(type)) {
            chooseType(type);
        }
    }

    private void chooseType(String type) {
        pendingType = type;
        wiederholungsVersuche = 0;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startListening();
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    /** Programmatische Spracherkennung starten – liefert das Ergebnis automatisch (kein Bestätigen). */
    private void startListening() {
        startListening(true);
    }

    /**
     * @param allowOnDevice bei {@code true} wird – falls das Offline-Modell laut Opt-in installiert ist –
     *     der On-Device-Recognizer bevorzugt (auch online). Der automatische Online-Rückfall setzt dies auf
     *     {@code false}, damit der Standard-Recognizer greift, wenn das On-Device-Modell doch scheitert.
     */
    private void startListening(boolean allowOnDevice) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showTypeSelection();
            showStatus(getString(R.string.wear_no_recognizer));
            return;
        }
        stopTimer();
        // Erst „Moment…", nicht schon „Sprich jetzt": Zwischen dem Tippen und der ersten aufgenommenen
        // Silbe liegt der Kaltstart des Erkennungsdienstes — über die Kachel gestartet gut eine
        // Sekunde. Bis 1.13 stand hier sofort „Sprich jetzt…", und wer dem folgte, sprach ins Nichts:
        // der Dienst meldete „nicht erkannt", und beim zweiten Mal ging es, weil er dann warm war.
        showWarten();

        destroySpeech();
        speechBegonnen = false;
        // Ist das Offline-Modell laut Opt-in installiert, hat es Vorrang – auch online (nutzt den
        // On-Device-Recognizer, der das per triggerModelDownload geladene Modell zuverlässig verwendet;
        // der Standard-Recognizer greift trotz PREFER_OFFLINE auf Wear OS oft nicht darauf zu). Ist der
        // Schalter aus oder kein Modell da, läuft die Online-Erkennung; klappt die gar nicht (kein Netz,
        // kein Modell), fällt onListenError auf den Zahlenblock zurück.
        boolean online = hasValidatedInternet();
        final boolean usingOnDevice = allowOnDevice
                && android.os.Build.VERSION.SDK_INT >= 33
                && WearStrings.installModelEnabled(this)
                && SpeechRecognizer.isOnDeviceRecognitionAvailable(this);
        if (usingOnDevice) {
            speech = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
        } else {
            speech = SpeechRecognizer.createSpeechRecognizer(this);
        }
        speech.setRecognitionListener(new SimpleRecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                // Jetzt erst nimmt der Dienst wirklich auf. Das ist der Moment für das Startzeichen.
                bereitschaftsWache.removeCallbacks(bereitschaftAbgelaufen);
                showListening();
            }

            @Override
            public void onBeginningOfSpeech() {
                speechBegonnen = true;
            }

            @Override
            public void onResults(Bundle results) {
                ArrayList<String> list = results == null ? null
                        : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                destroySpeech();
                String best = pickBest(list);
                if (best != null) {
                    onRecognized(best);
                } else {
                    onListenError(SpeechRecognizer.ERROR_NO_MATCH);
                }
            }

            @Override
            public void onError(int error) {
                destroySpeech();
                // Scheitert das On-Device-Modell (z. B. noch nicht fertig geladen) und ist Internet da,
                // einmalig auf die Online-Erkennung zurückfallen statt gleich den Zahlenblock zu zeigen.
                if (usingOnDevice && hasValidatedInternet()) {
                    startListening(false);
                } else {
                    onListenError(error);
                }
            }
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        // Erkennungssprache folgt der gewählten App-Sprache (auch hochgeladene). „Prefer offline" setzen,
        // wenn wir den On-Device-Recognizer nutzen oder gar kein Internet da ist.
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognizerLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, usingOnDevice || !online);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        // Mehrere Alternativen anfordern (die erste mit einer Zahl wird bevorzugt).
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        try {
            speech.startListening(intent);
            bereitschaftsWache.postDelayed(bereitschaftAbgelaufen, BEREITSCHAFT_TIMEOUT_MS);
        } catch (Exception e) {
            destroySpeech();
            onListenError(SpeechRecognizer.ERROR_CLIENT);
        }
    }

    /** BCP-47-Tag der gewählten App-Sprache; leer/unbekannt → Gerätesprache. */
    private String recognizerLanguageTag() {
        String tag = WearStrings.locale().toLanguageTag();
        if (tag == null || tag.isEmpty() || "und".equals(tag)) {
            return java.util.Locale.getDefault().toLanguageTag();
        }
        return tag;
    }

    /**
     * Hat die Uhr gerade tatsächlich nutzbares Internet? Prüft <b>alle</b> Netze (nicht nur das per-UID-
     * Default): Wear OS routet App-Verkehr standardmäßig über den Bluetooth-Proxy, der zwar {@code INTERNET}
     * meldet, aber nicht {@code VALIDATED} ist – ein daneben laufendes WLAN würde bei {@code getActiveNetwork()}
     * dann übersehen. {@code NET_CAPABILITY_VALIDATED} ist bewusst verlangt: Ist das Handy selbst offline
     * (Test 5), bleibt der BT-Proxy unvalidiert und es greift richtigerweise das Offline-Modell.
     */
    private boolean hasValidatedInternet() {
        android.net.ConnectivityManager cm =
                (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        for (android.net.Network net : cm.getAllNetworks()) {
            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps != null
                    && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Erkennung fehlgeschlagen. Ohne nutzbares Internet (dann ist ein Sprach-Wiederholen sinnlos – ein
     * Offline-Modell hätte schon gegriffen) oder bei einem Netzwerk-/Serverfehler direkt in den stillen
     * Zahlenblock wechseln (Betrag+GPS werden gepuffert und später gesendet), damit die Eingabe <b>immer</b>
     * möglich bleibt. Nur bei vorhandenem Internet und einem reinen „nicht erkannt" → „Nicht verstanden"
     * (Sprache erneut versuchen; der Zahlenblock-Knopf steht dort ohnehin bereit).
     */
    private void onListenError(int code) {
        if (stillWiederholen(code)) {
            return;
        }
        boolean offline = !hasValidatedInternet()
                || !phoneConnected
                || code == SpeechRecognizer.ERROR_NETWORK
                || code == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                || code == SpeechRecognizer.ERROR_SERVER
                || code == SpeechRecognizer.ERROR_CLIENT
                || (android.os.Build.VERSION.SDK_INT >= 31
                    && code == SpeechRecognizer.ERROR_SERVER_DISCONNECTED);
        if (offline) {
            android.widget.Toast.makeText(this, R.string.wear_offline_number,
                    android.widget.Toast.LENGTH_SHORT).show();
            showNumberPad();
        } else {
            showTypeSelection();
            showStatus(getString(R.string.wear_not_understood));
        }
    }

    /**
     * Noch einmal still zuhören, statt zur Auswahl zurückzuspringen — {@code true}, wenn das gerade
     * geschieht.
     *
     * <p>Nur unter einer Bedingung: Der Dienst hat nichts erkannt und dabei <b>nie einen Sprachanfang
     * gemeldet</b>. Dann lief er beim Losreden noch nicht, und genau das ist der Fall, den der Nutzer
     * bisher von Hand ausgeglichen hat („beim zweiten Mal geht es"). Hat er dagegen Sprache gehört und
     * sie nicht verstanden, wäre eine Wiederholung nur Zeitverlust: dann liegt es nicht am Zeitpunkt.</p>
     *
     * <p>Höchstens einmal. Eine Uhr, die unbemerkt immer weiter zuhört, wäre schlimmer als eine, die
     * aufgibt.</p>
     */
    private boolean stillWiederholen(int code) {
        if (speechBegonnen || wiederholungsVersuche >= 1
                || (code != SpeechRecognizer.ERROR_NO_MATCH
                    && code != SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
            return false;
        }
        wiederholungsVersuche++;
        startListening();
        return true;
    }

    /** Bevorzugt die erste Erkennungs-Alternative mit einer Zahl (sonst die beste), damit ein Betrag nicht
     * verloren geht, falls das Top-Ergebnis keine Ziffer enthält. */
    private String pickBest(ArrayList<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        for (String s : list) {
            if (s != null && s.matches(".*\\d.*")) {
                return s.trim();
            }
        }
        String first = list.get(0) == null ? "" : list.get(0).trim();
        return first.isEmpty() ? null : first;
    }

    /**
     * Der Zustand zwischen Tippen und Aufnahmebereitschaft: die Art steht schon, gesprochen wird noch
     * nicht. Dieselbe Fläche wie {@link #showListening()}, nur mit anderem Text — so springt nichts,
     * wenn gleich darauf umgeschaltet wird.
     */
    private void showWarten() {
        showSprachflaeche();
        confirmText.setText(R.string.wear_preparing);
    }

    /** „Höre zu"-Zustand: gleiche Fläche wie die Bestätigung, aber ohne Abbrechen/Countdown. */
    private void showListening() {
        showSprachflaeche();
        confirmText.setText(R.string.wear_listening);
        // Das Startzeichen ans Handgelenk: unterwegs schaut man nicht auf die Uhr, um den richtigen
        // Augenblick abzupassen — man spürt ihn.
        vibriere();
    }

    /** Was beiden Zuständen gemeinsam ist. */
    private void showSprachflaeche() {
        showOnly(confirmView);
        confirmType.setText(typeLabel(pendingType));
        confirmType.setTextColor(typeColor(pendingType));
        btnNumberPad.setVisibility(View.VISIBLE); // stille Zifferneingabe anbieten
        keepScreenOn(true);
    }

    /**
     * Zeigt genau eine der vier Flächen und blendet die übrigen aus.
     *
     * <p>Vorher hat jeder Übergang seine eigenen Sichtbarkeiten gesetzt – und einer vergaß die
     * Bestätigungsseite. Die blieb dann stehen und schien unter dem Zahlenblock durch, weil der als
     * letzter im Layout steht und deshalb obenauf liegt. Mit einer Stelle für alle vier kann das
     * nicht mehr passieren.</p>
     */
    private void showOnly(View sichtbar) {
        typeSelection.setVisibility(sichtbar == typeSelection ? View.VISIBLE : View.GONE);
        confirmView.setVisibility(sichtbar == confirmView ? View.VISIBLE : View.GONE);
        numberView.setVisibility(sichtbar == numberView ? View.VISIBLE : View.GONE);
        numberConfirmView.setVisibility(sichtbar == numberConfirmView ? View.VISIBLE : View.GONE);
    }

    /** Ein kurzer Stoß. Fehlt der Uhr ein Vibrationsmotor, geschieht schlicht nichts. */
    private void vibriere() {
        android.os.Vibrator vibrator;
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            android.os.VibratorManager manager =
                    (android.os.VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
            vibrator = manager == null ? null : manager.getDefaultVibrator();
        } else {
            vibrator = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
        }
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        vibrator.vibrate(android.os.VibrationEffect.createOneShot(
                60, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
    }

    /** Display während der aktiven Erfassung (Zuhören / 10-s-Timer / Zahlenblock) wach halten – kein
     * Abdunkeln/Ambient-Overlay der Uhr. */
    private void keepScreenOn(boolean on) {
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /** Zeigt den Zahlenblock (stille Eingabe); Art ist bereits gewählt. */
    private void showNumberPad() {
        stopTimer();
        destroySpeech();
        numberEntryActive = true;
        amountInput.setLength(0);
        updateNumberDisplay();
        showOnly(numberView);
        keepScreenOn(true);
    }

    private void appendDigit(String d) {
        int comma = amountInput.indexOf(",");
        if (comma >= 0 && amountInput.length() - comma - 1 >= 2) {
            return; // max. 2 Nachkommastellen
        }
        if (amountInput.length() >= 9) {
            return;
        }
        amountInput.append(d);
        updateNumberDisplay();
    }

    private void appendComma() {
        if (amountInput.indexOf(",") >= 0) {
            return;
        }
        if (amountInput.length() == 0) {
            amountInput.append("0");
        }
        amountInput.append(",");
        updateNumberDisplay();
    }

    private void backspace() {
        if (amountInput.length() > 0) {
            amountInput.deleteCharAt(amountInput.length() - 1);
        }
        updateNumberDisplay();
    }

    private void updateNumberDisplay() {
        numberDisplay.setText(amountInput.length() == 0 ? "0" : amountInput.toString());
    }

    /**
     * Die Auswahl neu aufbauen: der gesprochene Empfänger zuerst (er bleibt, wenn man nichts tut),
     * dahinter die im 100-m-Umkreis aus der vom Handy übertragenen Liste.
     *
     * <p>Läuft auch während des Countdowns noch einmal, falls der Standort erst dann eintrifft.
     * Deshalb wird die bereits getroffene Wahl über den Namen gemerkt und wiederhergestellt – sonst
     * spränge sie dem Nutzer unter den Fingern weg.</p>
     */
    private void refreshPayees() {
        String gewaehlt = payeeCandidates.isEmpty() ? null : chosenPayee();
        payeeCandidates.clear();
        payeePick = 0;
        if (!spokenPayee.isEmpty()) {
            payeeCandidates.add(spokenPayee);
        }
        String coords = location.currentCoords();
        double[] ll = parseCoords(coords);
        if (ll != null) {
            for (String name : PayeeStore.nearby(this, ll[0], ll[1],
                    BalanceStore.selectedAccount(this))) {
                // Den gesprochenen nicht doppelt führen, auch wenn er zufällig in der Nähe liegt.
                if (!name.equalsIgnoreCase(spokenPayee)) {
                    payeeCandidates.add(name);
                }
            }
        }
        // Eine schon getroffene Wahl überlebt den Neuaufbau.
        if (gewaehlt != null) {
            for (int i = 0; i < payeeCandidates.size(); i++) {
                if (payeeCandidates.get(i).equalsIgnoreCase(gewaehlt)) {
                    payeePick = i;
                    break;
                }
            }
        }
        // Bleibt die Zeile leer, sind drei Dinge möglich: kein Fix, keine übertragene Liste, oder
        // wirklich keiner in der Nähe. Ohne diese Zeile ist das am Handgelenk nicht zu unterscheiden.
        // Die Koordinaten selbst stehen bewusst nicht im Protokoll – wo jemand einkauft, gehört
        // nicht ins Systemlog.
        android.util.Log.d("AusgabenWearPayees", "Standort " + (coords == null ? "fehlt" : "da")
                + ", Kandidaten=" + payeeCandidates.size());
        updatePayeeRow();
    }

    /** „lat, lon" in Zahlen; {@code null}, wenn nichts Brauchbares dasteht. */
    private static double[] parseCoords(String coords) {
        if (coords == null) {
            return null;
        }
        int comma = coords.indexOf(',');
        if (comma <= 0) {
            return null;
        }
        try {
            return new double[]{Double.parseDouble(coords.substring(0, comma).trim()),
                    Double.parseDouble(coords.substring(comma + 1).trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Weiter zum nächsten Empfänger; nach dem letzten kommt „ohne Empfänger", dann wieder von vorn.
     * Jede Änderung stellt den Countdown zurück – gebucht wird, wenn zehn Sekunden nichts passiert.
     */
    private void cyclePayee() {
        if (payeeCandidates.isEmpty()) {
            return;
        }
        payeePick = (payeePick + 1) % (payeeCandidates.size() + 1);
        updatePayeeRow();
        if (confirmEntryId != null) {
            store.updateText(confirmEntryId, buchungstext(), ohneEmpfaengerGewaehlt());
            startCancelCountdown(btnCancelNumber);
        }
    }

    private void updatePayeeRow() {
        if (payeeCandidates.isEmpty()) {
            // Keiner in der Nähe: Zeile und Knopf bleiben weg, gebucht wird der reine Betrag – das
            // Handy sucht den Empfänger dann wie bisher selbst. Die Seite kommt trotzdem, damit der
            // Widerruf überall gleich funktioniert.
            payeeName.setVisibility(View.GONE);
            btnPayeeNext.setVisibility(View.GONE);
            return;
        }
        payeeName.setVisibility(View.VISIBLE);
        btnPayeeNext.setVisibility(View.VISIBLE);
        payeeName.setText(payeePick < payeeCandidates.size()
                ? payeeCandidates.get(payeePick) : getString(R.string.wear_payee_none));
    }

    /** Der gewählte Empfänger oder leer („ohne Empfänger" bzw. keiner in der Nähe). */
    private String chosenPayee() {
        return payeePick < payeeCandidates.size() ? payeeCandidates.get(payeePick) : "";
    }

    /**
     * Ausdrücklich „ohne Empfänger" gewählt – im Unterschied zu „keiner in der Nähe", wo das Handy
     * weiterhin selbst im Umkreis sucht. Beides ergibt denselben Buchungstext; das Handy kann es nur
     * an diesem Merkmal auseinanderhalten.
     */
    private boolean ohneEmpfaengerGewaehlt() {
        return !payeeCandidates.isEmpty() && payeePick == payeeCandidates.size();
    }

    /** Enter: Betrag als stille Buchung ablegen (Art = gewählter Typ) und übertragen. */
    private void submitNumber() {
        String amt = amountInput.toString();
        if (amt.endsWith(",")) {
            amt = amt.substring(0, amt.length() - 1);
        }
        double val;
        try {
            val = Double.parseDouble(amt.replace(",", "."));
        } catch (NumberFormatException e) {
            return;
        }
        if (val <= 0) {
            return;
        }
        spokenText = "";
        showConfirm(amt, "");
    }

    /**
     * Die eine Bestätigungsseite für beide Wege: Betrag, Empfänger und der 10-Sekunden-Widerruf.
     *
     * <p>Der Eintrag wird schon hier abgelegt (mit zurückgehaltenem {@code readyAt}), damit ein
     * Absturz in den zehn Sekunden die Eingabe nicht verschluckt; „Abbrechen" nimmt ihn wieder
     * heraus.</p>
     *
     * @param amt    angezeigter Betrag (bzw. der ganze Satz, wenn kein Betrag zu erkennen war)
     * @param spoken gesprochener Empfänger; steht als Vorgabe vorn, leer beim Zahlenblock
     */
    private void showConfirm(String amt, String spoken) {
        numberEntryActive = false;
        pendingAmount = amt;
        spokenPayee = spoken == null ? "" : spoken.trim();
        refreshPayees();

        long now = System.currentTimeMillis();
        confirmEntryId = UUID.randomUUID().toString();
        store.add(new PendingEntry(confirmEntryId, buchungstext(), pendingType, "",
                BalanceStore.selectedAccount(this), BalanceStore.selectedPlace(this),
                now, now + CANCEL_WINDOW_MS + LOCATION_WAIT_MS, ohneEmpfaengerGewaehlt()));
        requestTileUpdate();

        confirmAmount.setText(amt);
        showOnly(numberConfirmView);
        keepScreenOn(true);
        startCancelCountdown(btnCancelNumber);
    }

    /**
     * Baut den Satz, den das Phone auswertet – so, wie man ihn auch sprechen würde:
     * „Edeka 12,50 €". Damit läuft die Buchung durch dieselbe Kette wie eine gesprochene, inklusive
     * Alias-Korrektur, Vorlage, Kategorie und Konto.
     *
     * <p>Der Name steht <b>vorn</b> und die Währung hinten, und das ist kein Geschmack: Der Auswerter
     * nimmt bevorzugt die Zahl vor einem Währungswort und sonst die letzte Zahl im Satz. Bei
     * „12,50 Aral 24" wäre die letzte Zahl die 24 aus dem Namen – der Betrag wäre falsch.</p>
     *
     * <p>Steht die Wahl noch auf dem <b>gesprochenen</b> Empfänger, geht der Satz unverändert
     * hinaus. So kann die eigene Aufteilung der Uhr ({@link WearSpoken}) höchstens die Anzeige
     * verfehlen, nie die Buchung.</p>
     */
    private String buchungstext() {
        String payee = chosenPayee();
        if (!spokenText.isEmpty() && payee.equals(spokenPayee)) {
            return spokenText;
        }
        if (payee.isEmpty()) {
            return pendingAmount;
        }
        String currency = PayeeStore.currency(this);
        return currency.isEmpty() ? payee + " " + pendingAmount
                : payee + " " + pendingAmount + " " + currency;
    }

    /** Löst den Standort auf (Warten auf frischen Fix / Rückfall) und sendet den Eintrag danach. */
    private void resolveLocationThenSend(String id) {
        final Context app = getApplicationContext();
        location.resolve(coords -> {
            new PendingStore(app).updateGps(id, coords == null ? "" : coords,
                    System.currentTimeMillis());
            WearSync.syncPending(app);
            // Der Grund wechselt jetzt von „Warten auf GPS" auf den Übertragungszustand.
            requestTileUpdate();
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    refreshPhoneConnection();
                    updateBalance();
                }
            });
        });
    }

    /**
     * Erkannter Text → dieselbe Bestätigungsseite wie nach dem Zahlenblock.
     *
     * <p>Der gesprochene Empfänger steht dabei vorn und bleibt, wenn man nichts tut; die Empfänger
     * im Umkreis stehen dahinter und lassen sich durchschalten. Wurde nur ein Betrag gesagt, ist die
     * Runde dieselbe wie im Zahlenblock.</p>
     */
    private void onRecognized(String text) {
        WearSpoken.Result geteilt = WearSpoken.parse(text);
        spokenText = text;
        // Ohne erkannten Betrag bleibt der Satz als Ganzes stehen – lieber unaufgeteilt anzeigen als
        // falsch aufgeteilt. Das Handy wertet ihn ohnehin selbst aus.
        showConfirm(geteilt.amount.isEmpty() ? text : geteilt.amount, geteilt.payee);
    }

    /**
     * Der 10-Sekunden-Widerruf: Der Knopf zählt herunter, danach wird gebucht. Beide Wege teilen ihn
     * sich – die Spracherfassung und die Bestätigungsseite des Zahlenblocks –, damit sich das Warten
     * überall gleich anfühlt.
     */
    private void startCancelCountdown(Button target) {
        stopTimer();
        confirmTimer = new CountDownTimer(CANCEL_WINDOW_MS, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                long secs = (millisUntilFinished + 999) / 1000;
                target.setText(getString(R.string.wear_cancel) + " (" + secs + ")");
                // Beim Öffnen liegt oft noch kein Fix vor. Kommt er während des Countdowns, soll die
                // Empfängerzeile noch erscheinen, statt bis zur nächsten Buchung zu fehlen.
                if (numberConfirmView.getVisibility() == View.VISIBLE && payeeCandidates.isEmpty()) {
                    refreshPayees();
                }
            }

            @Override
            public void onFinish() {
                finalizeConfirm();
            }
        };
        confirmTimer.start();
    }

    /** 10 s abgelaufen → jetzt Standort auflösen und danach senden. */
    private void finalizeConfirm() {
        stopTimer();
        String id = confirmEntryId;
        confirmEntryId = null;
        if (id != null) {
            resolveLocationThenSend(id);
        }
        showTypeSelection();
    }

    /** „Abbrechen" innerhalb der 10 s → Eintrag wieder entfernen. */
    private void cancelConfirm() {
        stopTimer();
        if (confirmEntryId != null) {
            store.remove(confirmEntryId);
            confirmEntryId = null;
            requestTileUpdate();
        }
        showTypeSelection();
    }

    private void stopTimer() {
        if (confirmTimer != null) {
            confirmTimer.cancel();
            confirmTimer = null;
        }
    }

    private void destroySpeech() {
        bereitschaftsWache.removeCallbacks(bereitschaftAbgelaufen);
        if (speech != null) {
            speech.destroy();
            speech = null;
        }
    }

    private void showTypeSelection() {
        confirmView.setVisibility(View.GONE);
        numberView.setVisibility(View.GONE);
        numberConfirmView.setVisibility(View.GONE);
        numberEntryActive = false;
        keepScreenOn(false);
        typeSelection.setVisibility(View.VISIBLE);
        updateStatus();
    }

    @Override
    public void onBackPressed() {
        // Aus dem Zahlenblock zurück zur Typauswahl (statt die App zu verlassen).
        if (numberEntryActive) {
            showTypeSelection();
            return;
        }
        // Auf der Bestätigungsseite wirkt Zurück wie „Abbrechen": Der Eintrag liegt schon, er muß
        // wieder heraus – sonst würde er trotz Verlassens gebucht.
        if (numberConfirmView.getVisibility() == View.VISIBLE) {
            cancelConfirm();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ContextCompat.registerReceiver(this, pendingReceiver,
                new IntentFilter(WearPaths.ACTION_PENDING_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED);
        ContextCompat.registerReceiver(this, balanceReceiver,
                new IntentFilter(WearPaths.ACTION_BALANCE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED);
        updateBalance();
        refreshFromDataLayer();
        refreshPhoneConnection();
        if (location != null && hasLocationPermission()) {
            location.start();
        }
        WearSync.syncPending(this);
        ensureOfflineModel();
        // Nicht mitten in Aufnahme/Bestätigung/Zifferneingabe zurücksetzen; sonst Typauswahl zeigen.
        if (confirmTimer == null && speech == null && !numberEntryActive) {
            showTypeSelection();
        }
    }

    /**
     * Bei Opt-in (Phone-Einstellung „Offline-Sprachpaket auf der Uhr installieren") das Modell der gewählten
     * Sprache bereitstellen. Ab Wear OS 4 (API 33) automatisch anstoßen; auf älteren Uhren nur ein einmaliger
     * Hinweis, es manuell in den Systemeinstellungen zu laden. Ohne Opt-in passiert nichts (Offline greift
     * dann der Zahlenblock-Fallback).
     */
    private void ensureOfflineModel() {
        if (offlineModelChecked || !WearStrings.installModelEnabled(this)) {
            return;
        }
        offlineModelChecked = true;
        // Diese Uhr stellt Apps gar keinen Offline-/On-Device-Erkenner bereit (z. B. nur der Online-Dienst
        // des Google-TTS-Pakets ist gesetzt). Dann kann weder ein Modell geladen noch offline erkannt werden
        // – ehrlich sagen statt einen wirkungslosen Download anzustoßen.
        if (android.os.Build.VERSION.SDK_INT < 33
                || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            android.widget.Toast.makeText(this, R.string.wear_model_unsupported,
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        triggerOfflineModelDownload();
    }

    @androidx.annotation.RequiresApi(33)
    private void triggerOfflineModelDownload() {
        final String tag = recognizerLanguageTag();
        final Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag);
        final SpeechRecognizer rec;
        try {
            rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
        } catch (Exception e) {
            return;
        }
        try {
            rec.checkRecognitionSupport(intent, getMainExecutor(),
                    new android.speech.RecognitionSupportCallback() {
                        @Override
                        public void onSupportResult(android.speech.RecognitionSupport support) {
                            boolean installed = containsLang(support.getInstalledOnDeviceLanguages(), tag);
                            boolean pending = containsLang(support.getPendingOnDeviceLanguages(), tag);
                            boolean supported = containsLang(support.getSupportedOnDeviceLanguages(), tag);
                            if (!installed && !pending && supported) {
                                android.widget.Toast.makeText(WearMainActivity.this,
                                        R.string.wear_model_downloading,
                                        android.widget.Toast.LENGTH_SHORT).show();
                                try {
                                    rec.triggerModelDownload(intent);
                                } catch (Exception ignored) {
                                }
                            }
                            rec.destroy();
                        }

                        @Override
                        public void onError(int error) {
                            rec.destroy();
                        }
                    });
        } catch (Exception e) {
            rec.destroy();
        }
    }

    /** Grober Sprach-Vergleich: exakt oder gleicher Sprachteil (z. B. „de" ~ „de-DE"). */
    private static boolean containsLang(java.util.List<String> langs, String tag) {
        if (langs == null || tag == null) {
            return false;
        }
        String base = tag.split("-")[0].toLowerCase(java.util.Locale.ROOT);
        for (String l : langs) {
            if (l == null) {
                continue;
            }
            if (l.equalsIgnoreCase(tag)
                    || l.split("-")[0].toLowerCase(java.util.Locale.ROOT).equals(base)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(pendingReceiver);
        unregisterReceiver(balanceReceiver);
        revertHandler.removeCallbacks(revertRunnable);
        if (location != null) {
            location.stop();
        }
        // Auf der Bestätigungsseite des Zahlenblocks zählt Weggehen als Zustimmung: Wer den Betrag
        // getippt und den Empfänger gewählt hat, ist fertig – er wartet nur noch. Also sofort
        // buchen, statt den Eintrag mit zurückgehaltenem Sendezeitpunkt liegen zu lassen.
        if (numberConfirmView.getVisibility() == View.VISIBLE && confirmEntryId != null) {
            finalizeConfirm();
        }
        // Sonst: App verlassen zählt nicht als Abbrechen → Eintrag bleibt gespeichert und wird
        // später gesendet.
        stopTimer();
        destroySpeech();
        keepScreenOn(false);
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Einmalige Meldung setzen – sie ersetzt in der einzigen Zeile Konto+Saldo (keine zweite Zeile). */
    private void showStatus(String text) {
        transientStatus = text;
        updateBalance();
    }

    /** Einmalige Meldung löschen und die eine Zeile neu berechnen. */
    private void updateStatus() {
        transientStatus = null;
        updateBalance();
    }

    /**
     * Füllt die einzige Zeile auf dem Rundschirm. Es gibt bewusst <b>nur eine</b> Zeile: jede Meldung
     * (einmalige Meldung oder Übertragungs-Hinweis) <b>ersetzt</b> Konto+Saldo. Priorität:
     * <ol>
     *   <li>Hat der Nutzer gerade bewusst umgeschaltet (60‑s‑Fenster nach dem Wechsel-Knopf) → Konto/Saldo.</li>
     *   <li>Einmalige Meldung (Mikrofon/kein Erkenner/nicht verstanden/offline) → diese Meldung.</li>
     *   <li>Noch nicht übertragene Buchungen → Anzahl + Grund.</li>
     *   <li>Sonst Konto/Saldo vom Phone.</li>
     * </ol>
     * Wechsel-Knopf nur ab 2 Positionen.
     */
    private void updateBalance() {
        String text;
        if (BalanceStore.isRecentlySelected(this)) {
            text = BalanceStore.get(this);
        } else if (transientStatus != null && !transientStatus.isEmpty()) {
            text = transientStatus;
        } else {
            text = pendingReason();
            if (text == null) {
                text = BalanceStore.get(this);
            }
        }
        if (text != null && !text.isEmpty()) {
            balanceView.setText(text);
            balanceView.setVisibility(View.VISIBLE);
        } else {
            balanceView.setVisibility(View.GONE);
        }
        btnCycle.setVisibility(BalanceStore.count(this) >= 2 ? View.VISIBLE : View.GONE);
    }

    /**
     * Warum sind Einträge noch nicht übertragen? {@code null} = nichts offen.
     * <ul>
     *   <li>{@code readyAt} in der Zukunft und noch keine Koordinaten → der Standort wird noch aufgelöst.</li>
     *   <li>Kein verbundener Knoten → das Phone ist nicht erreichbar.</li>
     *   <li>Sonst liegt der Eintrag im Data Layer und wird gerade übertragen.</li>
     * </ul>
     */
    private String pendingReason() {
        java.util.List<PendingEntry> pending = store.getPending();
        int reason = WearPendingReason.of(pending, phoneConnected);
        String label;
        switch (reason) {
            case WearPendingReason.GPS:
                label = getString(R.string.wear_reason_gps);
                break;
            case WearPendingReason.NO_PHONE:
                label = getString(R.string.wear_reason_no_phone);
                break;
            case WearPendingReason.SENDING:
                label = getString(R.string.wear_reason_sending);
                break;
            default:
                return null;
        }
        return getString(R.string.wear_pending, pending.size(), label);
    }

    /**
     * Stößt eine Neuberechnung der Kachel (Tile/„Widget") an. Nötig, sobald sich auf der Uhr selbst die
     * Warteschlange ändert (Buchung angelegt/entfernt/gesendet) – sonst zeigt die Kachel bis zur nächsten
     * Phone-Nachricht weiter den alten Saldo statt des Übertragungs-Hinweises.
     */
    private void requestTileUpdate() {
        try {
            androidx.wear.tiles.TileService.getUpdater(this).requestUpdate(ExpenseTileService.class);
        } catch (Exception ignored) {
        }
    }

    /** Ist das Phone erreichbar? Ergebnis kommt asynchron und aktualisiert die Anzeige. */
    private void refreshPhoneConnection() {
        try {
            com.google.android.gms.wearable.Wearable.getNodeClient(this).getConnectedNodes()
                    .addOnSuccessListener(nodes -> {
                        phoneConnected = nodes != null && !nodes.isEmpty();
                        updateBalance();
                    })
                    .addOnFailureListener(e -> {
                        phoneConnected = false;
                        updateBalance();
                    });
        } catch (Exception ignored) {
            // Ohne Play-Services bleibt die letzte Annahme stehen.
        }
    }

    /** Auf die nächste Konto/Ort-Position schalten und den Auto-Rücksprung (Timeout) neu planen. */
    private void cycleSelection() {
        BalanceStore.advance(this);
        updateBalance();
        revertHandler.removeCallbacks(revertRunnable);
        revertHandler.postDelayed(revertRunnable, BalanceStore.TIMEOUT_MS);
    }

    /**
     * Liest Saldo und Empfänger einmalig aus dem lokalen Data-Layer-Cache (billig, kein Netz) und zeigt
     * den Saldo an. Nötig, weil der reine Change-Listener bereits (unverändert) vorliegende Daten nicht
     * liefert – nach einer Neuinstallation der Uhr-App stünde sonst beides dauerhaft leer da.
     */
    private void refreshFromDataLayer() {
        try {
            com.google.android.gms.wearable.Wearable.getDataClient(this).getDataItems()
                    .addOnSuccessListener(items -> {
                        try {
                            for (com.google.android.gms.wearable.DataItem item : items) {
                                if (WearPaths.PATH_BALANCE.equals(item.getUri().getPath())) {
                                    com.google.android.gms.wearable.DataMap m =
                                            com.google.android.gms.wearable.DataMapItem.fromDataItem(item)
                                                    .getDataMap();
                                    BalanceStore.save(this, m.getString("text", ""));
                                    BalanceStore.saveList(this, m.getString("list", ""));
                                } else if (WearPaths.PATH_PAYEES.equals(item.getUri().getPath())) {
                                    // Im selben Durchgang: onDataChanged feuert nur bei Änderungen,
                                    // und nach einer Neuinstallation der Uhr-App liegt das DataItem
                                    // längst unverändert da – das Handy schickt es nie wieder.
                                    com.google.android.gms.wearable.DataMap m =
                                            com.google.android.gms.wearable.DataMapItem.fromDataItem(item)
                                                    .getDataMap();
                                    String liste = m.getString("list", "");
                                    PayeeStore.save(this, liste, m.getString("currency", ""));
                                    android.util.Log.d("AusgabenWearPayees", "aus dem Cache: "
                                            + (liste.isEmpty() ? 0 : liste.split("\n").length)
                                            + " Empfänger");
                                }
                            }
                        } finally {
                            items.release();
                        }
                        updateBalance();
                    });
        } catch (Exception ignored) {
        }
    }

    private String typeLabel(String type) {
        if (WearPaths.TYPE_INCOME.equals(type)) {
            return getString(R.string.wear_type_income);
        }
        if (WearPaths.TYPE_TRANSFER.equals(type)) {
            return getString(R.string.wear_type_transfer);
        }
        return getString(R.string.wear_type_expense);
    }

    private int typeColor(String type) {
        if (WearPaths.TYPE_INCOME.equals(type)) {
            return ContextCompat.getColor(this, R.color.type_income);
        }
        if (WearPaths.TYPE_TRANSFER.equals(type)) {
            return ContextCompat.getColor(this, R.color.type_transfer);
        }
        return ContextCompat.getColor(this, R.color.type_expense);
    }

    /** RecognitionListener mit leeren Standard-Implementierungen; nur onResults/onError werden genutzt. */
    private abstract static class SimpleRecognitionListener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle params) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onPartialResults(Bundle partialResults) { }
        @Override public void onEvent(int eventType, Bundle params) { }
    }
}
