package de.spahr.ausgaben.ui;

import android.Manifest;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Locale;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.BookingSplit;
import de.spahr.ausgaben.db.BookingTags;
import de.spahr.ausgaben.db.PayeeCorrection;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.location.LocationTagger;
import de.spahr.ausgaben.receipt.NoteReceipt;
import de.spahr.ausgaben.receipt.ReceiptImage;
import de.spahr.ausgaben.receipt.ReceiptPages;
import de.spahr.ausgaben.receipt.ReceiptSync;
import de.spahr.ausgaben.receipt.Receipts;
import de.spahr.ausgaben.settings.PlacesStore;
import de.spahr.ausgaben.settings.SettingsStore;
import de.spahr.ausgaben.settings.DateFormats;

/**
 * Vereinheitlichter Editor für Neueingabe und Bearbeitung.
 * Unterstützt drei Typen: Ausgabe / Umbuchung / Einnahme. Bei Ausgabe/Einnahme können mehrere
 * Kategorien mit Teilbeträgen erfasst werden (Splitbuchung); bei Umbuchung zwei Konten (Von/Nach).
 */
public class BookingEditActivity extends LocalizedActivity {

    public static final String EXTRA_BOOKING_ID = "booking_id";
    /** Öffnet den Editor als NEUE Buchung, vorbefüllt aus dieser Vorlage-Buchung (Sprach-Schnellerfassung). */
    public static final String EXTRA_TEMPLATE_BOOKING_ID = "template_booking_id";
    /** Gesprochener Betrag in Cent (−1 = keiner). */
    public static final String EXTRA_VOICE_AMOUNT_CENTS = "voice_amount_cents";
    /** Vorbelegter Empfänger (falls keine Vorlage gefunden wurde). */
    public static final String EXTRA_PREFILL_PAYEE = "prefill_payee";
    /** Ursprünglich gesprochener Empfänger – zum Anbieten einer Namenskorrektur beim Speichern. */
    public static final String EXTRA_VOICE_SPOKEN_PAYEE = "voice_spoken_payee";
    /** Passender Alias (ID) für eine neue Sprachbuchung – füllt Konto/Kategorien/Von-Bis vor. */
    public static final String EXTRA_ALIAS_ID = "alias_id";
    /** Neue Buchung mit vorbelegtem Konto (das in der Buchungsliste angezeigte). */
    public static final String EXTRA_PRESET_ACCOUNT = "preset_account";
    /**
     * Bei einer per Alias/Vorlage aufgelösten Umbuchung: das in der Buchungsliste angezeigte Konto
     * (Nutzereingabe). Steckt es bereits als Von- oder Nach-Konto in Alias/Vorlage, bleiben beide Konten
     * unverändert wie dort hinterlegt; sonst wird das Von-Konto damit ersetzt (Nach-Konto bleibt), siehe
     * {@link MainActivity#openVoiceEditor}.
     */
    public static final String EXTRA_PRESET_TRANSFER_FROM_ACCOUNT = "preset_transfer_from_account";
    /** Öffnet eine bestehende Buchung nur zur Ansicht (keine Änderung möglich). */
    public static final String EXTRA_READ_ONLY = "read_only";
    /**
     * Ergebnis-Extra nach dem Löschen einer normalen Buchung: {@link Bundle} mit allem zum Wiederanlegen –
     * die Buchungsliste bietet damit „Rückgängig" an. Bei Umbuchungen nicht gesetzt.
     */
    public static final String EXTRA_UNDO_BOOKING = "undo_booking";
    /**
     * Ergebnis-Extra der Ansicht: der Nutzer hat den Stift getippt und will diese Buchung bearbeiten.
     * Die Ansicht macht dafür zu und die Liste öffnet den Editor frisch – die Sperre in der laufenden
     * Activity wieder aufzuheben wäre der fehleranfälligere Weg, weil {@code applyReadOnly} Felder
     * sperrt, Dropdown-Pfeile entfernt und Knöpfe versteckt, und weil Zweige wie die GPS-Zeile nur bei
     * {@code !readOnly} überhaupt anlaufen.
     */
    public static final String EXTRA_REQUEST_EDIT = "request_edit";
    /** Öffnet eine geplante Buchung ({@link de.spahr.ausgaben.db.ScheduledTransaction}) nur zur Ansicht. */
    public static final String EXTRA_SCHEDULED_ID = "scheduled_id";
    /** Öffnet eine geplante Buchung als NEUE Buchung vorbefüllt („jetzt buchen"); nicht schreibgeschützt. */
    public static final String EXTRA_SCHEDULED_BOOK_ID = "scheduled_book_id";
    /** Fälligkeitstermin (ms) der getippten Planung – als Buchungsdatum in der Vorschau. */
    public static final String EXTRA_SCHEDULED_DUE_MS = "scheduled_due_ms";

    private Repository repository;
    private SettingsStore settings;
    private PlacesStore placesStore;
    private Booking booking; // null = Neu-Modus
    /** true = reine Ansicht (kurzer Druck): alle Felder gesperrt, keine Aktionsknöpfe. */
    private boolean readOnly;

    // Ursprünglicher Typ beim Bearbeiten (für Umbuchung ↔ normale Buchung Umwandlungen).
    private boolean origIsTransfer;
    private String origTransferGroup = "";
    // True, wenn die bearbeitete Buchung in der App angelegt (ort-verknüpft) ist – nur dann Ort-Feld zeigen.
    private boolean origPlaceManaged;
    // Für die Datum-Abfrage: wurde der Editor aus einer bestehenden Buchung geöffnet, und hat der Nutzer
    // das Datum selbst geändert? Abfrage nur beim Kopieren (Vorlage) mit unverändertem Datum.
    private boolean openedFromExistingBooking;
    /** Vorlagen-Kopie (Sprach-/Betrag-Schnellerfassung): leerer Vorlagen-Ort → Standardort des Kontos. */
    private boolean templatePlaceFallback;
    private boolean dateChangedByUser;
    // „Jetzt buchen" aus den geplanten Buchungen: Planung + geplanter Termin, die beim Speichern
    // weitergestellt werden (null = normale Buchung).
    private de.spahr.ausgaben.db.ScheduledTransaction bookedSchedule;
    private long bookedScheduleDueMs;

    private MaterialToolbar toolbar;
    private MaterialButtonToggleGroup toggleType;
    private android.widget.TextView typeHeading;
    private android.widget.TextView textBalanceBefore;
    private android.widget.TextView textBalanceAfter;
    private android.widget.ImageButton btnNoteMap;
    private android.widget.ImageButton btnGpsClear;
    private TextInputEditText editAmount;
    private TextInputLayout amountLayout;
    private CalcKeyboardView calcKeyboard;
    private TextInputLayout payeeLayout;
    private MaterialAutoCompleteTextView editPayee;
    private TextInputLayout accountLayout;
    private TextInputLayout dateLayout;
    private MaterialAutoCompleteTextView editAccount;
    private TextInputLayout accountToLayout;
    private MaterialAutoCompleteTextView editAccountTo;
    private TextInputLayout placeLayout;
    private MaterialAutoCompleteTextView editPlace;
    private TextInputLayout placeToLayout;
    private MaterialAutoCompleteTextView editPlaceTo;
    private View splitSection;
    private android.widget.LinearLayout splitContainer;
    private TextInputEditText editNote;
    private TextInputEditText editDate;
    private com.google.android.material.materialswitch.MaterialSwitch switchExported;
    private MaterialButton btnToday;
    private MaterialButton btnSaveNew;
    private MaterialButton btnSkipSchedule;
    private MaterialButton btnUpdate;
    private MaterialButton btnDelete;
    /** Statuszeile beim Nachladen eines Belegs (Shimmer + Text, unbestimmt). */
    private ImportBanner receiptBanner;

    /** GPS-Anhang an die Notiz – nur im Neu-Modus aktiv (bei bestehenden Buchungen bleibt der Ort unberührt). */
    private LocationTagger locationTagger;
    private ActivityResultLauncher<String> locationPermissionLauncher;

    // ---- GPS-/Stichwort-/Beleg-Ausgabezeilen ----
    private android.view.View rowGps;
    private android.view.View rowTags;
    private android.view.View rowReceipt;
    private android.widget.TextView textGps;
    private android.widget.TextView textTags;
    private android.widget.ImageButton btnTagsEdit;
    private android.widget.ImageButton btnTagsClear;
    private android.widget.TextView textReceipt;
    private android.widget.ImageButton btnReceipt;   // btnNoteMap ist ein eigenes Feld
    private android.widget.LinearLayout receiptPagesView;
    private android.widget.LinearLayout receiptPageIcons;
    private boolean receiptEnabled;
    /** Zu speichernde Koordinaten „lat, lon" (aus Standort bzw. bestehender Buchung); null = keine. */
    private String gpsRowCoords;
    /** Stichwörter dieser Buchung, so wie sie gespeichert werden (siehe {@link BookingTags}). */
    private String bookingTags = "";
    /** Die in KMyMoney vorhandenen Stichwörter – nur daraus lässt sich wählen; leer = Zeile aus. */
    private java.util.List<String> knownTagNames = new ArrayList<>();
    /** Zu welchem Empfänger schon vorbelegt wurde – spart die Abfrage bei jedem Tastendruck. */
    private String payeeTagKey;
    /**
     * Wertpapier-Buchung: nur Notiz, Stichwörter und Beleg sind änderbar, Löschen entfällt.
     * Siehe {@link #applyNotesOnlyIfNeeded()}.
     */
    private boolean notesOnly;
    /**
     * Zu dieser Buchung gehört eine Depot-Bewegung, die beim Löschen mitgehen muss. Erst nach der
     * Rückfrage an die Datenbank gesetzt; solange {@code false}, gibt es keinen Löschknopf.
     */
    private boolean securityTxFound;
    /** Die Namen der Wertpapiere aller Depots (klein); {@code null} = noch nicht geladen. */
    private java.util.Set<String> knownSecurityNames;

    /**
     * Ist {@code counterAccount} das Gegenkonto einer Wertpapier-Buchung? Zwei Wege führen dahin, und
     * es genügt einer: Der Name ist ein <b>Wertpapier</b> eines importierten Depots – in der
     * KMyMoney-Datei heißt das Unterkonto genauso wie das Wertpapier –, oder die App kennt das
     * Gegenkonto gar nicht als Konto, was beim Import eines Wertpapiers der Regelfall ist.
     */
    private boolean isSecurityCounterpart(String counterAccount, boolean isTransfer) {
        if (!isTransfer || counterAccount == null || counterAccount.trim().isEmpty()) {
            return false;
        }
        String key = counterAccount.trim().toLowerCase(Locale.ROOT);
        return (knownSecurityNames != null && knownSecurityNames.contains(key))
                || !knownAccountNames.contains(key);
    }
    /**
     * Empfänger (klein), bei dem die Stichwörter von Hand gesetzt wurden. Für ihn schlägt die App
     * nichts mehr vor – sonst käme ein gelöschtes Stichwort beim nächsten Öffnen des Fensters wieder.
     */
    private String tagsEditedForPayee;
    /** True, sobald der Standort auf der Karte manuell gewählt wurde – dann kein Überschreiben per Live-GPS. */
    private boolean gpsEditedByUser;
    /** Karten-Auswahl (OpenStreetMap) für den Standort der Buchung. */
    private ActivityResultLauncher<Intent> gpsMapLauncher;
    /**
     * Eine Belegseite: entweder bereits gespeichert ({@code savedName}) oder frisch aufgenommen
     * ({@code pending}, ein komprimiertes Temp, das beim Speichern seinen endgültigen Namen bekommt).
     * Ob es ein Foto oder ein PDF ist, sagt die Endung des Namens – ein eigenes Feld braucht es nicht.
     */
    private static final class Page {
        String savedName;
        java.io.File pending;

        Page(String savedName, java.io.File pending) {
            this.savedName = savedName;
            this.pending = pending;
        }

        java.io.File file(android.content.Context ctx) {
            return pending != null ? pending : Receipts.localFile(ctx, savedName);
        }

        boolean isPdf() {
            return NoteReceipt.isPdf(pending != null ? pending.getName() : savedName);
        }
    }

    /** Die Seiten des Belegs in Seitenreihenfolge; leer = kein Beleg. */
    private final java.util.List<Page> receiptPages = new java.util.ArrayList<>();
    /** Beim Speichern zu löschende, bereits gespeicherte Seiten. */
    private final java.util.List<String> removedReceipts = new java.util.ArrayList<>();
    private android.net.Uri cameraTempUri;
    private java.io.File cameraTempFile;
    private ActivityResultLauncher<android.net.Uri> takePictureLauncher;
    private ActivityResultLauncher<String> pickImageLauncher;
    /** Dateiauswahl des Systems für ein PDF-Dokument. */
    private ActivityResultLauncher<String[]> pickPdfLauncher;
    /** Zuschneiden/Begradigen/Aufhellen eines Belegs ({@link ReceiptEditActivity}). */
    private ActivityResultLauncher<Intent> receiptEditLauncher;
    /** Beim Bearbeiten einer bereits gespeicherten Seite: deren Name für das Hochladen danach. */
    private String editingSavedReceipt;
    /** Jahresordner der Belege beim Öffnen der Buchung; {@code -1} = keine gespeicherten Belege. */
    private int origReceiptYear = -1;

    private static final java.util.regex.Pattern GPS_PAIR = java.util.regex.Pattern.compile(
            "GPS:\\s*(-?\\d+(?:\\.\\d+)?\\s*,\\s*-?\\d+(?:\\.\\d+)?)", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Ursprünglich gesprochener Empfänger (aus der Sprach-Erfassung) – für die Korrektur-Nachfrage. */
    private String voiceSpokenPayee;
    /** Ursprünglicher Empfänger beim Bearbeiten – „Von"-Name für die Alias-Nachfrage. */
    private String origPayee;
    /** Vorbelegter Empfänger (Prefill/Alias/geladene Buchung) – Abfrage nur bei manueller Änderung. */
    private String prefilledPayee;
    /** Passender Alias für die Vorbelegung einer neuen Sprachbuchung (null = keiner). */
    private PayeeCorrection activeAlias;
    /** Nutzereingabe-Vorrang bei Alias-Umbuchung, siehe {@link #EXTRA_PRESET_TRANSFER_FROM_ACCOUNT}. */
    private String presetTransferFromAccount = "";
    private final Set<String> knownAccountNames = new HashSet<>();

    /** Alle Empfänger (alphabetisch) für die Vorschlagsliste; wird einmal aus der Datenbank geholt. */
    private List<String> payeeNames = new ArrayList<>();
    /** Die nächstgelegenen Empfänger – der Vorspann der Vorschlagsliste (nur bei neuer Buchung). */
    private List<String> nearbyPayees = new ArrayList<>();
    /** Position, zu der {@link #nearbyPayees} gehört – erst ein deutlicher Ortswechsel rechnet neu. */
    private double[] nearbyCenter;
    /** Empfänger und Buchungsart, zu denen die Kategorie-Favoriten gehören („name|true/false"). */
    private String payeeCategoryKey;
    /** Empfänger und Buchungsart, für die die vorbelegten Kategoriezeilen gelten – sonst {@code null}. */
    private String categorySourceKey;
    /** Kommen die geladenen Kategorien aus einer echten Buchung oder Planung? Dann bleiben sie stehen. */
    private boolean keepLoadedCategories;
    /** Betrag, Art und Ort, zu denen zuletzt ein Empfänger vorgeschlagen wurde – fragt nicht zweimal. */
    private String payeeAmountKey;
    /** So weit muß der Standort wandern, damit der Vorspann neu gerechnet wird (Meter). */
    private static final int NEARBY_AGAIN_M = 100;
    /** Bis hierhin nennt die Stichwort-Zeile die Namen; darüber nur noch ihre Anzahl. */
    private static final int TAGS_LABEL_MAX = 40;

    /** Verwaltet die dynamische Kategorie-/Teilbetrag-Liste (Splitbuchung). */
    private SplitRowController splitCtl;

    private final Calendar selectedDate = Calendar.getInstance();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_booking);

        toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        repository = new Repository(this);
        settings = new SettingsStore(this);
        placesStore = new PlacesStore(this);

        toggleType = findViewById(R.id.toggleType);
        typeHeading = findViewById(R.id.typeHeading);
        textBalanceBefore = findViewById(R.id.textBalanceBefore);
        textBalanceAfter = findViewById(R.id.textBalanceAfter);
        btnNoteMap = findViewById(R.id.btnNoteMap);
        btnGpsClear = findViewById(R.id.btnGpsClear);
        editAmount = findViewById(R.id.editAmount);
        amountLayout = findViewById(R.id.amountLayout);
        calcKeyboard = findViewById(R.id.calcKeyboard);
        // Im Hochformat haelt der Platzhalter die Hoehe der Tastatur frei, damit das Formular
        // wie bisher ueber ihr endet; quer schwebt sie darueber und der Platzhalter bleibt weg.
        calcKeyboard.reserveSpaceWith(findViewById(R.id.calcSpacer));
        // Haupt-Betragsfeld an die eigene Rechentastatur binden (Teilbeträge folgen unten über den Binder).
        // Steht der Betrag fest, darf er einen Empfänger vorschlagen – während des Tippens stünde
        // zwischendurch „8" da, wo „80" gemeint ist.
        wireCalcField(editAmount, amountLayout, this::suggestPayeeFromAmount);
        payeeLayout = findViewById(R.id.payeeLayout);
        editPayee = findViewById(R.id.editPayee);
        accountLayout = findViewById(R.id.accountLayout);
        editAccount = findViewById(R.id.editAccount);
        accountToLayout = findViewById(R.id.accountToLayout);
        editAccountTo = findViewById(R.id.editAccountTo);
        placeLayout = findViewById(R.id.placeLayout);
        editPlace = findViewById(R.id.editPlace);
        placeToLayout = findViewById(R.id.placeToLayout);
        editPlaceTo = findViewById(R.id.editPlaceTo);
        splitSection = findViewById(R.id.splitSection);
        splitContainer = findViewById(R.id.splitContainer);
        // Geplante Buchungen öffnen ebenfalls schreibgeschützt – readOnly muss VOR dem SplitRowController
        // feststehen, damit auch die Kategorie-Zeilen nicht editierbar sind.
        readOnly = getIntent().getBooleanExtra(EXTRA_READ_ONLY, false)
                || getIntent().getLongExtra(EXTRA_SCHEDULED_ID, -1) >= 0;
        splitCtl = new SplitRowController(splitContainer, editAmount, getLayoutInflater(),
                readOnly, this::updateSaveEnabled);
        // Teilbeträge an die Rechentastatur; der Feldrahmen wird hier nicht gebraucht (keine PDF-Erkennung).
        splitCtl.setAmountBinder((layout, field) -> wireCalcField(field, null));
        editNote = findViewById(R.id.editNote);
        editDate = findViewById(R.id.editDate);
        switchExported = findViewById(R.id.switchExported);
        editDate.setOnClickListener(v -> showDatePicker());
        // Das Kalendersymbol liegt über dem Feld und würde den Tipper sonst schlucken.
        dateLayout = findViewById(R.id.dateLayout);
        dateLayout.setEndIconOnClickListener(v -> showDatePicker());

        btnToday = findViewById(R.id.btnToday);
        btnToday.setOnClickListener(v -> {
            selectedDate.setTime(new java.util.Date());
            dateChangedByUser = true;
            updateDateField();
        });

        btnSaveNew = findViewById(R.id.btnSaveNew);
        btnSkipSchedule = findViewById(R.id.btnSkipSchedule);
        btnUpdate = findViewById(R.id.btnUpdate);
        btnDelete = findViewById(R.id.btnDelete);

        ShimmerView receiptShimmer = findViewById(R.id.receiptShimmer);
        receiptShimmer.setColors(getColor(R.color.import_banner_bg), getColor(R.color.import_banner_shimmer));
        // Unbestimmtes Nachladen eines Belegs – Shimmer + Text, ohne Prozentanzeige.
        receiptBanner = new ImportBanner(findViewById(R.id.receiptBanner), receiptShimmer,
                findViewById(R.id.receiptStatus), null);

        repository.getPayeeNames(names -> {
            payeeNames = names == null ? new ArrayList<>() : names;
            refreshPayeeSuggestions();
        });
        repository.getTagNames(names -> {
            // Kennt die App keine Stichwörter (CSV-Betrieb, noch kein Abgleich), bleibt die Zeile weg.
            knownTagNames = names == null ? new ArrayList<>() : names;
            updateNoteTagRows();
            // Die Liste kommt aus der Datenbank und damit womöglich später als der vorbelegte
            // Empfänger – dann ist sein Vorspann noch nirgends angekommen. Also noch einmal fragen.
            payeeTagKey = null;
            refreshPayeeTags();
        });
        repository.getAccountNames(names -> {
            knownAccountNames.clear();
            for (String name : names) {
                if (name != null) {
                    knownAccountNames.add(name.trim().toLowerCase(Locale.ROOT));
                }
            }
            PickerAdapters.accounts(repository, names, editAccount, editAccountTo);
            // Die Kontenliste entscheidet mit, ob dies eine Wertpapier-Buchung ist – sie kommt aus der
            // Datenbank und damit womöglich erst nach der Buchung.
            applyNotesOnlyIfNeeded();
        });
        repository.getSecurityNames(names -> {
            knownSecurityNames = new HashSet<>();
            for (String name : names) {
                if (name != null) {
                    knownSecurityNames.add(name.trim().toLowerCase(Locale.ROOT));
                }
            }
            applyNotesOnlyIfNeeded();
        });

        repository.getCategoriesGrouped(g -> {
            // Kategoriefeld nach Ausgabe/Einnahme gruppiert (Überschriften), ohne „alle"-Eintrag.
            splitCtl.setAdapter(new CategoryFilterAdapter(this, null,
                    getString(R.string.category_group_expense), g.expense,
                    getString(R.string.category_group_income), g.income));
            // Die Liste kommt aus der Datenbank und damit womöglich später als der vorbelegte Empfänger –
            // dann sind seine Kategorien noch an keiner Liste angekommen. Also noch einmal fragen.
            payeeCategoryKey = null;
            refreshPayeeCategories();
        });

        // Ort-Dropdown folgt dem gewählten Konto: bei Ausgabe/Einnahme der Ort, bei Umbuchung der Von-Ort.
        // Danach die Sichtbarkeit aktualisieren (Ortsfeld nur bei Konten mit Orten).
        PickerBehaviour.onCommitted(editAccount, value -> {
            if (isTransferType()) {
                setupPlaceOptions(editPlace, textOf(editAccount).trim(), false);
            } else {
                setupPlaceDropdown(textOf(editAccount).trim());
            }
            applyTypeVisibility();
        });
        // Steht der Empfänger fest, richten sich die Kategorien nach ihm: Vorspann der Auswahlliste und
        // Vorbelegung der ersten Zeile.
        PickerBehaviour.onCommitted(editPayee, value -> {
            refreshPayeeCategories();
            refreshPayeeTags();
        });
        // Bei einer Umbuchung folgt der Nach-Ort dem Nach-Konto.
        PickerBehaviour.onCommitted(editAccountTo, value -> {
            if (isTransferType()) {
                setupPlaceOptions(editPlaceTo, textOf(editAccountTo).trim(), false);
            }
            applyTypeVisibility();
        });

        // Gesamtbetrag ↔ Teilbeträge koppeln; Konto wirkt auf die Freischaltung der Buttons.
        editAmount.addTextChangedListener(new SimpleWatcher(splitCtl::onTotalChanged));
        editAccount.addTextChangedListener(new SimpleWatcher(this::updateSaveEnabled));
        editAccountTo.addTextChangedListener(new SimpleWatcher(this::updateSaveEnabled));

        toggleType.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                applyTypeVisibility();
                applyAlias();
            }
        });

        // Erst die Suche in allen Vorschlagsfeldern beenden, dann speichern: der Knopf nimmt dem Feld
        // nicht zwangsläufig den Fokus, und ein Feld mitten in der Suche ist leer.
        btnSaveNew.setOnClickListener(v -> {
            PickerBehaviour.settleAll(getWindow().getDecorView());
            saveAsNew();
        });
        btnUpdate.setOnClickListener(v -> {
            PickerBehaviour.settleAll(getWindow().getDecorView());
            update();
        });
        btnDelete.setOnClickListener(v -> confirmDelete());

        // Beleg-Foto: Launcher (vor STARTED registrieren) + Knöpfe.
        takePictureLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicture(), success -> {
                    if (Boolean.TRUE.equals(success) && cameraTempUri != null) {
                        ingestReceipt(cameraTempUri, cameraTempFile);
                    } else if (cameraTempFile != null) {
                        cameraTempFile.delete();
                    }
                    cameraTempUri = null;
                    cameraTempFile = null;
                });
        pickImageLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(), uri -> {
                    if (uri != null) {
                        ingestReceipt(uri, null);
                    }
                });
        pickPdfLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri != null) {
                        ingestPdf(uri);
                    }
                });
        receiptEditLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    String saved = editingSavedReceipt;
                    editingSavedReceipt = null;
                    if (result.getResultCode() != RESULT_OK) {
                        return;
                    }
                    if (saved != null) {
                        // Bereits gespeicherter Beleg: Datei ist ersetzt, also samt Original neu hochladen.
                        Receipts.addPending(this, saved, receiptYear());
                        Receipts.addPending(this, NoteReceipt.originalName(saved), receiptYear());
                        ReceiptSync.syncPending(this);
                    }
                    updateNoteTagRows();
                    Toast.makeText(this, R.string.receipt_edit_done, Toast.LENGTH_SHORT).show();
                });
        // Standort auf der Karte (OpenStreetMap) wählen/ändern – wie beim Alias. Die manuelle Wahl gewinnt
        // ab jetzt gegen den Live-GPS-Wert (siehe gpsEditedByUser).
        gpsMapLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        double lat = result.getData().getDoubleExtra(MapPickerActivity.EXTRA_LAT, 0);
                        double lon = result.getData().getDoubleExtra(MapPickerActivity.EXTRA_LON, 0);
                        gpsRowCoords = formatCoords(lat, lon);
                        gpsEditedByUser = true;
                        updateNoteTagRows();
                    }
                });
        rowGps = findViewById(R.id.rowGps);
        rowTags = findViewById(R.id.rowTags);
        rowReceipt = findViewById(R.id.rowReceipt);
        textGps = findViewById(R.id.textGps);
        textTags = findViewById(R.id.textTags);
        btnTagsEdit = findViewById(R.id.btnTagsEdit);
        btnTagsClear = findViewById(R.id.btnTagsClear);
        textReceipt = findViewById(R.id.textReceipt);
        btnReceipt = findViewById(R.id.btnReceipt);
        receiptPagesView = findViewById(R.id.receiptPages);
        receiptPageIcons = findViewById(R.id.receiptPageIcons);
        receiptEnabled = settings.isReceiptEnabled();
        // Klick-Verhalten (Karte / Bild öffnen bzw. Kamera) wird je nach Modus in updateNoteTagRows() gesetzt.

        long templateId = getIntent().getLongExtra(EXTRA_TEMPLATE_BOOKING_ID, -1);
        long id = getIntent().getLongExtra(EXTRA_BOOKING_ID, -1);
        long scheduledId = getIntent().getLongExtra(EXTRA_SCHEDULED_ID, -1);
        long scheduledBookId = getIntent().getLongExtra(EXTRA_SCHEDULED_BOOK_ID, -1);
        long voiceAmount = getIntent().getLongExtra(EXTRA_VOICE_AMOUNT_CENTS, -1);
        voiceSpokenPayee = getIntent().getStringExtra(EXTRA_VOICE_SPOKEN_PAYEE);
        String presetFrom = getIntent().getStringExtra(EXTRA_PRESET_TRANSFER_FROM_ACCOUNT);
        presetTransferFromAccount = presetFrom == null ? "" : presetFrom.trim();

        // Nur bei NEUEN Buchungen und aktivem GPS: Standort vorwärmen und ggf. Berechtigung anfragen,
        // damit beim Speichern Koordinaten an die Notiz angehängt werden können. Bei einer geplanten
        // Buchung ist der Empfänger bekannt – dort wären Koordinaten nur Rauschen.
        // Standort vorwärmen, wenn GPS aktiv ist und die Buchung bearbeitbar ist (nicht in der reinen Ansicht/
        // Vorschau). So kann auch „Als neue speichern" aus einer bestehenden Buchung aktuelle Koordinaten
        // anhängen. Die Berechtigung wird nur bei einer echten Neu-/Vorlage-Buchung aktiv angefragt.
        if (settings.isGpsEnabled() && !readOnly && scheduledId < 0) {
            locationTagger = new LocationTagger(this);
            locationTagger.setOnLocationUpdate(this::refreshNoteLocation);
            locationPermissionLauncher = registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(), granted -> {
                        if (granted) {
                            locationTagger.start();
                            refreshNoteLocation();
                        }
                    });
            boolean pureNew = id < 0 && scheduledBookId < 0;
            if (hasLocationPermission()) {
                locationTagger.start();
            } else if (pureNew) {
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        if (scheduledId >= 0) {
            // Geplante Buchung nur zur Ansicht (1:1 wie eine normale Buchung).
            readOnly = true;
            long dueMs = getIntent().getLongExtra(EXTRA_SCHEDULED_DUE_MS, System.currentTimeMillis());
            repository.getScheduledById(scheduledId, st -> bindScheduledPreview(st, dueMs));
        } else if (scheduledBookId >= 0) {
            // „Jetzt buchen": Planung als NEUE Buchung vorbefüllt, bearbeitbar.
            long dueMs = getIntent().getLongExtra(EXTRA_SCHEDULED_DUE_MS, System.currentTimeMillis());
            repository.getScheduledById(scheduledBookId, st -> bindScheduledBooking(st, dueMs));
        } else if (templateId >= 0) {
            // Sprach-Schnellerfassung: neue Buchung aus Vorlage vorbefüllen.
            final Long amount = voiceAmount >= 0 ? voiceAmount : null;
            repository.getBookingById(templateId, b -> bindTemplate(b, amount));
        } else if (id >= 0) {
            repository.getBookingById(id, this::bindEditMode);
        } else {
            setupNewMode();
            // Vorbelegtes Konto (das in der Buchungsliste angezeigte). Das Ortsfeld richtet sich dann nach
            // diesem Konto – also dessen Standardort – und nicht nach dem Standardkonto, das setupNewMode()
            // eben eingetragen hat. Ein per Code gesetzter Text meldet sich nicht von selbst.
            String presetAccount = getIntent().getStringExtra(EXTRA_PRESET_ACCOUNT);
            if (presetAccount != null && !presetAccount.isEmpty()) {
                editAccount.setText(presetAccount, false);
                setupPlaceDropdown(presetAccount);
                applyTypeVisibility();
            }
            // Fallback der Sprach-Erfassung (keine Vorlage gefunden): Empfänger/Betrag vorbelegen.
            String prefillPayee = getIntent().getStringExtra(EXTRA_PREFILL_PAYEE);
            if (prefillPayee != null && !prefillPayee.isEmpty()) {
                editPayee.setText(prefillPayee);
            }
            prefilledPayee = prefillPayee == null ? "" : prefillPayee;
            if (voiceAmount >= 0) {
                editAmount.setText(formatCents(voiceAmount));
            }
            // Alias-Treffer: bevorzugte Buchungsart setzen und Konto/Kategorien/Von-Bis vorbelegen.
            long aliasId = getIntent().getLongExtra(EXTRA_ALIAS_ID, -1);
            if (aliasId >= 0) {
                repository.getAlias(aliasId, a -> {
                    activeAlias = a;
                    if (a != null) {
                        toggleType.check(aliasTypeButton(a.type));
                    }
                    applyAlias();
                });
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (locationTagger != null && hasLocationPermission()) {
            locationTagger.start();
            refreshNoteLocation();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (locationTagger != null) {
            locationTagger.stop();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        clearReceiptPages(); // nicht gespeicherte Beleg-Temps aufräumen
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Zeigt die aktuellen Koordinaten als „GPS: lat, lon" bereits im Notizfeld an (nur Neu-Modus). Ein
     * vorhandener GPS-Zusatz wird durch den frischeren ersetzt, der übrige Notiztext bleibt erhalten.
     * Während der Nutzer im Feld tippt (Fokus), wird nicht überschrieben; ohne Standort passiert nichts.
     */
    private void refreshNoteLocation() {
        // Nur im Neu-/Vorlage-Modus (booking == null) die GPS-Zeile live mit der aktuellen Position füllen;
        // beim Bearbeiten bleiben die gespeicherten Koordinaten stehen (der Tagger läuft nur, damit „Als neue
        // speichern" aktuelle Koordinaten holen kann).
        if (locationTagger == null || booking != null || gpsEditedByUser) {
            return;
        }
        String coords = locationTagger.currentCoordinates();
        if (coords == null) {
            return;
        }
        gpsRowCoords = coords;
        updateNoteTagRows();
    }

    /**
     * Holt die nächstgelegenen Empfänger für den Vorspann der Vorschlagsliste. Maßgeblich ist der
     * <b>Standort der Buchung</b>, also die Standort-Zeile des Editors: bei einer neuen Buchung der
     * laufende Standort, beim Bearbeiten die gespeicherte Marke der Notiz, nach «Karte» der von Hand
     * gewählte Punkt. Ohne Marke gibt es keinen Vorspann – die jetzige Position sagt über eine alte
     * Buchung nichts.
     *
     * <p>Neu gerechnet wird erst, wenn der Punkt um mehr als {@link #NEARBY_AGAIN_M} gewandert ist: der
     * Standort meldet sich laufend, und die Liste soll nicht bei jedem Zucken neu aus der Datenbank
     * kommen.</p>
     */
    private void refreshNearbyPayees() {
        double[] hier = readOnly ? null : de.spahr.ausgaben.location.Geo.parse(gpsRowCoords);
        if (hier == null) {
            if (nearbyCenter != null) {
                nearbyCenter = null;
                nearbyPayees = new ArrayList<>();
                refreshPayeeSuggestions();
            }
            return;
        }
        if (nearbyCenter != null && de.spahr.ausgaben.location.Geo.distanceMeters(
                nearbyCenter[0], nearbyCenter[1], hier[0], hier[1]) <= NEARBY_AGAIN_M) {
            return;
        }
        nearbyCenter = hier;
        repository.getNearbyPayees(hier[0], hier[1], names -> {
            nearbyPayees = names == null ? new ArrayList<>() : names;
            refreshPayeeSuggestions();
        });
    }

    /**
     * Setzt die Vorschlagsliste des Empfängerfelds neu: die nahen Empfänger als Vorspann, darunter alle
     * alphabetisch. Steht der Nutzer gerade im Feld, bleibt die Liste, wie sie ist – ein Austausch unter
     * dem Finger ließe die offene Auswahl springen; der nächste Aufruf holt es nach.
     */
    private void refreshPayeeSuggestions() {
        if (editPayee == null || editPayee.hasFocus()) {
            return;
        }
        PickerAdapters.payees(editPayee, payeeNames, nearbyPayees);
    }

    /**
     * Bietet an, einen Alias zu lernen, falls der Empfänger gegenüber dem Ausgangs-Namen geändert wurde –
     * bei einer Sprach-Neubuchung der gesprochene Begriff ({@code voiceSpokenPayee}), beim Bearbeiten der
     * ursprüngliche Empfänger ({@code origPayee}). Gesteuert über den Einstellungs-Schalter. Der Alias
     * übernimmt den aktuellen Buchungskontext (Konto, Kategorien bzw. Von/Bis). Danach {@code proceed}.
     */
    private void maybeAskCorrection(String finalPayee, Runnable proceed) {
        String spoken = voiceSpokenPayee != null && !voiceSpokenPayee.trim().isEmpty()
                ? voiceSpokenPayee.trim()
                : (origPayee == null ? "" : origPayee.trim());
        String corrected = finalPayee == null ? "" : finalPayee.trim();
        String prefilled = prefilledPayee == null ? "" : prefilledPayee.trim();
        // Nur fragen, wenn der Empfänger gegenüber der Vorbelegung manuell geändert wurde.
        if (!settings.isAliasPromptEnabled() || spoken.isEmpty() || corrected.isEmpty()
                || corrected.equalsIgnoreCase(prefilled) || spoken.equalsIgnoreCase(corrected)) {
            proceed.run();
            return;
        }
        new AppDialog(this)
                .setTitle(R.string.correction_title)
                .setMessage(getString(R.string.correction_message, spoken, corrected))
                .setCancelable(false)
                .setPositiveButton(R.string.correction_save, (d, w) -> {
                    // Lernen: die Buchungsposition an die GPS-Liste des Alias anhängen (nicht ersetzen).
                    repository.saveAlias(buildAliasFromForm(spoken, corrected), true);
                    proceed.run();
                })
                .setNegativeButton(R.string.correction_discard, (d, w) -> proceed.run())
                .show();
    }

    /** Zugehöriger Typ-Knopf zur Alias-Buchungsart (Standard: Ausgabe). */
    private int aliasTypeButton(String type) {
        if (Repository.VOICE_TYPE_TRANSFER.equals(type)) {
            return R.id.btnTransfer;
        }
        if (Repository.VOICE_TYPE_INCOME.equals(type)) {
            return R.id.btnIncome;
        }
        return R.id.btnExpense;
    }

    private String currentTypeConstant() {
        if (isTransferType()) {
            return Repository.VOICE_TYPE_TRANSFER;
        }
        return toggleType.getCheckedButtonId() == R.id.btnIncome
                ? Repository.VOICE_TYPE_INCOME : Repository.VOICE_TYPE_EXPENSE;
    }

    /** Baut aus dem aktuellen Formular einen Alias mit passendem Kontext (Konto/Kategorien bzw. Von/Bis). */
    private PayeeCorrection buildAliasFromForm(String spoken, String corrected) {
        PayeeCorrection a = new PayeeCorrection();
        a.spoken = spoken;
        a.corrected = corrected;
        a.type = currentTypeConstant();
        if (isTransferType()) {
            a.fromAccount = textOf(editAccount).trim();
            a.toAccount = textOf(editAccountTo).trim();
            a.fromPlace = selectedPlace();
            a.toPlace = selectedPlaceTo();
        } else {
            a.account = textOf(editAccount).trim();
            a.place = selectedPlace();
            List<SplitRowController.Part> parts = splitCtl.collectParts();
            String c1 = parts.size() > 0 ? parts.get(0).category : "";
            String c2 = parts.size() > 1 ? parts.get(1).category : "";
            if (toggleType.getCheckedButtonId() == R.id.btnIncome) {
                a.catIncome1 = c1;
                a.catIncome2 = c2;
            } else {
                a.catExpense1 = c1;
                a.catExpense2 = c2;
            }
        }
        // Standort der Buchung (aus der GPS-Zeile) übernehmen → Alias per GPS auffindbar (Betrag-only).
        double[] ll = de.spahr.ausgaben.location.Geo.parse(gpsRowCoords);
        if (ll != null) {
            a.lat = ll[0];
            a.lon = ll[1];
        }
        return a;
    }

    /** Belegt bei einer neuen Sprachbuchung mit Alias-Treffer die Felder für den aktuellen Typ vor. */
    private void applyAlias() {
        if (activeAlias == null || booking != null) {
            return;
        }
        if (isTransferType()) {
            // Steckt das angezeigte Konto (Nutzereingabe) bereits als Von- oder Nach-Konto im Alias, gelten
            // beide Konten unverändert wie im Alias hinterlegt. Sonst ersetzt es das Von-Konto (Nach-Konto
            // bleibt aus dem Alias), siehe EXTRA_PRESET_TRANSFER_FROM_ACCOUNT.
            boolean selMatches = !presetTransferFromAccount.isEmpty()
                    && ((!activeAlias.fromAccount.isEmpty()
                            && presetTransferFromAccount.equalsIgnoreCase(activeAlias.fromAccount))
                        || (!activeAlias.toAccount.isEmpty()
                            && presetTransferFromAccount.equalsIgnoreCase(activeAlias.toAccount)));
            boolean fromPreset = !presetTransferFromAccount.isEmpty() && !selMatches;
            if (fromPreset) {
                editAccount.setText(presetTransferFromAccount, false);
            } else if (!activeAlias.fromAccount.isEmpty()) {
                editAccount.setText(activeAlias.fromAccount, false);
            }
            if (!activeAlias.toAccount.isEmpty()) {
                editAccountTo.setText(activeAlias.toAccount, false);
            }
            // Ort-Dropdowns/Sichtbarkeit für die neuen Konten aufbauen, dann Alias-Orte vorbelegen.
            applyTypeVisibility();
            // Der Alias-Von-Ort gehört zum Alias-Konto – beim Vorrang des angezeigten Kontos passt er nicht
            // mehr sicher dazu, applyTypeVisibility() hat dafür bereits einen sinnvollen Standardort gesetzt.
            if (!fromPreset && !activeAlias.fromPlace.isEmpty()) {
                editPlace.setText(activeAlias.fromPlace, false);
            }
            if (!activeAlias.toPlace.isEmpty()) {
                editPlaceTo.setText(activeAlias.toPlace, false);
            }
            return;
        }
        if (!activeAlias.account.isEmpty()) {
            editAccount.setText(activeAlias.account, false);
            setupPlaceDropdown(activeAlias.account);
            if (!activeAlias.place.isEmpty()) {
                editPlace.setText(activeAlias.place, false);
            }
        }
        boolean income = toggleType.getCheckedButtonId() == R.id.btnIncome;
        String c1 = income ? activeAlias.catIncome1 : activeAlias.catExpense1;
        String c2 = income ? activeAlias.catIncome2 : activeAlias.catExpense2;
        splitCtl.setSuppressEvents(true);
        splitCtl.clear();
        if (c1 != null && !c1.trim().isEmpty()) {
            splitCtl.addRow(c1, null);
        }
        if (c2 != null && !c2.trim().isEmpty()) {
            splitCtl.addRow(c2, null);
        }
        splitCtl.setSuppressEvents(false);
        splitCtl.ensureTrailingRow();
        // Diese Zeilen gehören zum Empfänger des Alias – erst ein anderer Empfänger zieht neue nach.
        markCategorySource();
        // Betrag (z. B. aus der Spracherfassung bereits im Gesamtfeld) in den Teilbetrag übernehmen,
        // sofern genau eine Kategorie gesetzt ist (Alias ohne echte zweite Splitkategorie).
        splitCtl.onTotalChanged();
        // Ortsfeld-Sichtbarkeit an das vom Alias gesetzte Konto anpassen.
        applyTypeVisibility();
        updateSaveEnabled();
    }

    private void setupNewMode() {
        booking = null;
        gpsRowCoords = null;
        clearReceiptPages();
        origIsTransfer = false;
        origTransferGroup = "";
        origPlaceManaged = true; // neue Buchung ist immer ort-verknüpft (Standardort)
        openedFromExistingBooking = false;
        toolbar.setTitle(R.string.new_booking_title);
        toggleType.check(R.id.btnExpense);
        selectedDate.setTime(new java.util.Date());
        updateDateField();
        String def = settings.getDefaultAccount();
        if (!def.isEmpty()) {
            editAccount.setText(def, false);
        }
        setupPlaceDropdown(def);
        splitCtl.clear();
        splitCtl.ensureTrailingRow();
        switchExported.setVisibility(View.GONE);
        btnUpdate.setVisibility(View.GONE);
        btnDelete.setVisibility(View.GONE);
        applyTypeVisibility();
        updateSaveEnabled();
        refreshNoteLocation();
    }

    private void bindEditMode(Booking b) {
        if (b == null) {
            finish();
            return;
        }
        booking = b;
        // Gespeicherte Buchung: die Kategorie ist gesetzte Wahrheit und keine Vorbelegung.
        keepLoadedCategories = true;
        origIsTransfer = b.isTransfer;
        origTransferGroup = b.transferGroup == null ? "" : b.transferGroup;
        origPlaceManaged = b.placeManaged; // importiert (false): ein Ort wird beim Ändern nicht übernommen
        openedFromExistingBooking = true; // aus bestehender Buchung → Datum-Abfrage nur beim Kopieren
        origPayee = b.payee;
        prefilledPayee = b.payee;
        toolbar.setTitle(R.string.edit_title);
        selectedDate.setTimeInMillis(b.createdAt);
        updateDateField();
        // Export-Status auch bei Umbuchungen änderbar (beide Seiten werden beim Speichern angepasst).
        switchExported.setVisibility(View.VISIBLE);
        switchExported.setChecked(b.exported);
        // „Bearbeitet" ist kein Schalterzustand, sondern die Folge einer Änderung: nur anzeigen, gesperrt.
        // Von Hand ist dieser Status damit nicht zu setzen und auch nicht wegzunehmen.
        switchExported.setEnabled(!b.edited);
        switchExported.setText(b.edited ? R.string.edited_locked : R.string.mark_exported);
        btnUpdate.setVisibility(View.VISIBLE);
        btnDelete.setVisibility(View.VISIBLE);
        emphasizeUpdate();
        // Bestehende Buchung: GPS/Beleg aus der Notiz in die zwei Zeilen (bleiben beim Aktualisieren erhalten).
        gpsRowCoords = parseGpsCoords(b.note);
        loadReceiptPages(b.note, yearFromMillis(b.createdAt));
        populateFrom(b, null);
        updateNoteTagRows();
        if (readOnly) {
            applyReadOnly();
        }
        applyNotesOnlyIfNeeded();
    }

    /**
     * Eine <b>Wertpapier-Buchung</b> aus dem Depot erkennt man daran, dass sie eine Umbuchung ist, deren
     * Gegenkonto die App gar nicht als Konto führt: beim Import wird nur das Buchungskonto angelegt, das
     * Wertpapier bleibt außen vor. An so einer Buchung lässt sich nur Notiz, Stichwort und Beleg ändern –
     * Betrag, Kurs und Stückzahl stehen in der KMyMoney-Datei, und die Stückzahl kennt die App nicht
     * einmal. Würde man sie wie eine gewöhnliche Umbuchung speichern und ausgeben, wäre der
     * Wertpapierkauf in der Datei danach eine nackte Umbuchung.
     *
     * <p>Wird zweimal gerufen – nach dem Laden der Buchung und nach dem Eintreffen der Kontenliste –,
     * weil erst beides zusammen die Frage beantwortet.</p>
     */
    private void applyNotesOnlyIfNeeded() {
        if (notesOnly || readOnly || booking == null || knownAccountNames.isEmpty()) {
            return;
        }
        // Ohne die Wertpapiernamen ist die Frage noch nicht zu beantworten; der Aufruf kommt wieder,
        // sobald sie da sind.
        if (knownSecurityNames == null) {
            return;
        }
        if (!isSecurityCounterpart(booking.transferAccount, booking.isTransfer)) {
            return;
        }
        notesOnly = true;
        lockField(editAmount);
        lockField(editPayee);
        lockField(editAccount);
        lockField(editAccountTo);
        lockField(editPlace);
        lockField(editPlaceTo);
        lockField(editDate);
        // Dropdown-Pfeile entfernen, damit sich keine Auswahl mehr öffnen lässt.
        accountLayout.setEndIconMode(TextInputLayout.END_ICON_NONE);
        accountToLayout.setEndIconMode(TextInputLayout.END_ICON_NONE);
        placeLayout.setEndIconMode(TextInputLayout.END_ICON_NONE);
        placeToLayout.setEndIconMode(TextInputLayout.END_ICON_NONE);
        dateLayout.setEndIconMode(TextInputLayout.END_ICON_NONE);
        toggleType.setVisibility(View.GONE);
        btnToday.setVisibility(View.GONE);
        // Löschen darf sein, Ändern nicht – der Unterschied ist entscheidend: beim Löschen verschwindet
        // die ganze Transaktion samt Stückzahl und Kurs, es kann nichts halb stehenbleiben. Der Knopf
        // kommt aber erst zurück, wenn feststeht, dass wirklich eine Depot-Bewegung dazugehört; bis
        // dahin bleibt er weg. Ein Knopf, der sich gleich wieder verabschiedet, wäre schlimmer.
        btnDelete.setVisibility(View.GONE);
        repository.getSecurityTxForBooking(booking, tx -> {
            if (tx != null && !isFinishing()) {
                securityTxFound = true;
                btnDelete.setVisibility(View.VISIBLE);
            }
        });
        // „Als neu speichern" ebensowenig: eine Wertpapier-Buchung kann die App nicht anlegen – ihr
        // fehlen Stückzahl und Kurs.
        btnSaveNew.setVisibility(View.GONE);
        // Der Export-Status ist hier keine Entscheidung des Nutzers – die Buchung stammt aus der Datei.
        switchExported.setVisibility(View.GONE);
        updateSaveEnabled();
    }

    /**
     * Zeigt eine geplante Buchung 1:1 wie eine normale Read-Only-Buchung: baut ein synthetisches
     * {@link Booking} aus der Planung (inkl. Split-Kategorien) und schickt es durch denselben
     * {@code populateFrom}+{@code applyReadOnly}-Pfad wie {@link #bindEditMode}.
     */
    private void bindScheduledPreview(de.spahr.ausgaben.db.ScheduledTransaction st, long dueMs) {
        bindSchedule(st, dueMs, true);
    }

    /**
     * Zeigt eine geplante Buchung im Editor, vorbefüllt als <b>neue</b> Buchung („jetzt buchen").
     * Speichern läuft über den normalen {@code saveAsNew()}-Pfad – inkl. Ort-Bewegung.
     */
    private void bindScheduledBooking(de.spahr.ausgaben.db.ScheduledTransaction st, long dueMs) {
        // Erst beim tatsächlichen Speichern gilt der Termin als erledigt (siehe finishAfterSave) – wer den
        // Editor abbricht, lässt die Planung unverändert stehen.
        bookedSchedule = st;
        bookedScheduleDueMs = dueMs;
        bindSchedule(st, dueMs, false);
        if (st != null) {
            btnSkipSchedule.setVisibility(View.VISIBLE);
            btnSkipSchedule.setOnClickListener(v -> confirmSkipSchedule(st, dueMs));
        }
    }

    /** „Buchung überspringen": keine Buchung, aber die Planung rückt (auch in der .kmy) eine Periode weiter. */
    private void confirmSkipSchedule(de.spahr.ausgaben.db.ScheduledTransaction st, long dueMs) {
        String date = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT,
                getResources().getConfiguration().getLocales().get(0)).format(new java.util.Date(dueMs));
        new AppDialog(this)
                .setTitle(R.string.scheduled_skip_booking)
                .setMessage(getString(R.string.scheduled_skip_confirm, date, st.name))
                .setPositiveButton(R.string.scheduled_skip_booking, (d, w) -> {
                    bookedSchedule = null;   // nicht zusätzlich über finishAfterSave weiterstellen
                    repository.advanceScheduled(st, dueMs, false, () -> {
                        Toast.makeText(this, R.string.scheduled_skipped, Toast.LENGTH_SHORT).show();
                        finish();
                    });
                })
                .show();
    }

    /**
     * Nach erfolgreichem Speichern schließen – bei „jetzt buchen" vorher die KMyMoney-Regel um eine Periode
     * weiterstellen. Maßgeblich ist der <b>geplante</b> Termin, auch wenn im Editor ein anderes Buchungsdatum
     * gewählt wurde: die Regel hängt am Plan, nicht am Zahltag.
     */
    private void finishAfterSave() {
        if (bookedSchedule == null) {
            finish();
            return;
        }
        de.spahr.ausgaben.db.ScheduledTransaction st = bookedSchedule;
        bookedSchedule = null;
        repository.advanceScheduled(st, bookedScheduleDueMs, true, this::finish);
    }

    /**
     * Gemeinsamer Weg für Ansicht und „jetzt buchen": baut aus der Planung ein {@link Booking} und schickt
     * es durch {@code populateFrom}. {@code preview} = schreibgeschützt ansehen, sonst als neue Buchung
     * bearbeitbar.
     */
    private void bindSchedule(de.spahr.ausgaben.db.ScheduledTransaction st, long dueMs, boolean preview) {
        if (st == null) {
            finish();
            return;
        }
        final Booking b = bookingFromSchedule(st, dueMs);
        // Ansicht: „booking" trägt den Typ für applyReadOnly. Buchen: null = NEUE Buchung (wie bindTemplate).
        booking = preview ? b : null;
        // Die Kategorien stehen so in der Planung – auch beim Buchen nicht überschreiben.
        keepLoadedCategories = true;
        origIsTransfer = b.isTransfer;
        origPlaceManaged = !preview;   // neue Buchung ist ort-verknüpft, die Vorschau nie
        openedFromExistingBooking = true;
        selectedDate.setTimeInMillis(dueMs);
        updateDateField();
        if (!preview) {
            toolbar.setTitle(R.string.new_booking_title);
            switchExported.setVisibility(View.GONE);
            btnUpdate.setVisibility(View.GONE);
            btnDelete.setVisibility(View.GONE);
        }
        final Runnable bind = () -> {
            populateFrom(b, null);
            if (preview) {
                applyReadOnly();
            }
        };
        if (st.split == 1) {
            repository.getScheduledSplits(st.id, parts -> {
                b.parts = new ArrayList<>();
                if (parts != null) {
                    for (de.spahr.ausgaben.db.ScheduledSplit p : parts) {
                        b.parts.add(new BookingSplit(0, p.category, p.amountCents));
                    }
                }
                bind.run();
            });
        } else {
            bind.run();
        }
    }

    /** Baut aus einer Planung + Fälligkeit ein {@link Booking} (ohne Split-Teile – die kommen asynchron). */
    private Booking bookingFromSchedule(de.spahr.ausgaben.db.ScheduledTransaction st, long dueMs) {
        Booking b = new Booking();
        b.id = -1;
        b.createdAt = dueMs;
        b.amountCents = st.amountCents;
        b.isTransfer = st.kind == de.spahr.ausgaben.db.ScheduledTransaction.KIND_TRANSFER;
        // Bei einer Umbuchung steuert isIncome die Von/Nach-Zuordnung: incoming = Geld fließt IN st.account.
        b.isIncome = b.isTransfer
                ? st.incoming == 1
                : st.kind == de.spahr.ausgaben.db.ScheduledTransaction.KIND_INCOME;
        b.account = st.account;
        b.payee = st.payee;
        b.note = "";
        // Die Stichwörter der Planung wandern mit: in der Vorschau nur zu sehen, in der daraus
        // angelegten Buchung dann auch zu ändern.
        b.tags = st.tags == null ? "" : st.tags;
        b.place = "";
        b.placeManaged = false;
        if (b.isTransfer) {
            b.transferAccount = st.counterparty;
            b.category = "";
        } else {
            b.category = st.counterparty;
        }
        return b;
    }

    /** Reine Ansicht: Titel setzen, alle Felder sperren, Aktionsknöpfe ausblenden. */
    private void applyReadOnly() {
        toolbar.setTitle(R.string.booking_view_title);
        lockField(editAmount);
        lockField(editPayee);
        lockField(editAccount);
        lockField(editAccountTo);
        lockField(editPlace);
        lockField(editPlaceTo);
        lockField(editNote);
        lockField(editDate);
        // Dropdown-Pfeile (Exposed-Menü) entfernen, damit sich keine Auswahl öffnen lässt.
        accountLayout.setEndIconMode(TextInputLayout.END_ICON_NONE);
        accountToLayout.setEndIconMode(TextInputLayout.END_ICON_NONE);
        placeLayout.setEndIconMode(TextInputLayout.END_ICON_NONE);
        placeToLayout.setEndIconMode(TextInputLayout.END_ICON_NONE);
        dateLayout.setEndIconMode(TextInputLayout.END_ICON_NONE);
        // Ansicht: „Als exportiert markiert" ausblenden (im Bearbeiten-Modus bleibt der Schalter).
        switchExported.setVisibility(View.GONE);
        btnToday.setVisibility(View.GONE);
        btnSaveNew.setVisibility(View.GONE);
        btnUpdate.setVisibility(View.GONE);
        btnDelete.setVisibility(View.GONE);

        // Umschaltknöpfe durch eine große farbige Typ-Überschrift ersetzen
        // (Einnahme = grün, Umbuchung = gelb, Ausgabe = rot).
        toggleType.setVisibility(View.GONE);
        int typeRes;
        int typeColor;
        if (booking.isTransfer) {
            typeRes = R.string.type_transfer;
            typeColor = R.color.transfer_yellow;
        } else if (booking.isIncome) {
            typeRes = R.string.type_income;
            typeColor = R.color.income_green;
        } else {
            typeRes = R.string.type_expense;
            typeColor = R.color.expense_red;
        }
        typeHeading.setText(typeRes);
        typeHeading.setTextColor(getColor(typeColor));
        typeHeading.setVisibility(View.VISIBLE);

        // Kontostand vor/nach dieser Buchung auf dem Konto der Buchung.
        showBalances();

        // GPS-/Beleg-Ausgabezeilen (Werte aus der Notiz; nicht editierbar, mit Karten- bzw. Bild-Icon).
        gpsRowCoords = parseGpsCoords(booking.note);
        loadReceiptPages(booking.note, yearFromMillis(booking.createdAt));
        updateNoteTagRows();
        showEditAction();
    }

    /**
     * Stift in der Toolbar – der einzige sichtbare Weg aus der Ansicht ins Bearbeiten. Vorher ging das
     * nur über den langen Druck in der Liste, und den sieht niemand.
     *
     * <p>Nur für echte Buchungen: Die Vorschau einer <b>geplanten</b> Buchung landet ebenfalls hier
     * ({@link #bindScheduledPreview}), ist aber gar nicht änderbar – sie wird gebucht oder
     * übersprungen. Ihr Vorschau-Objekt steht in keiner Tabelle und hat deshalb keine id.</p>
     */
    private void showEditAction() {
        if (booking == null || booking.id <= 0
                || getIntent().getLongExtra(EXTRA_SCHEDULED_ID, -1) >= 0) {
            return;
        }
        toolbar.inflateMenu(R.menu.booking_view_menu);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() != R.id.action_edit_booking) {
                return false;
            }
            // Die Liste öffnet den Editor frisch; sie hält dafür ohnehin den Launcher, über den nach
            // einem Löschen „Rückgängig" zurückkommt.
            Intent res = new Intent();
            res.putExtra(EXTRA_REQUEST_EDIT, booking.id);
            setResult(RESULT_OK, res);
            finish();
            return true;
        });
    }

    /** Zeigt „Kontostand vor/nach der Buchung" für das Konto dieser Buchung. */
    private void showBalances() {
        final long signed = booking.isIncome ? booking.amountCents : -booking.amountCents;
        final String currency = de.spahr.ausgaben.settings.Currencies.forAccount(booking.account);
        repository.getAccountBalanceUpTo(booking.account, booking.createdAt, booking.id, after -> {
            long before = after - signed;
            textBalanceBefore.setText(getString(R.string.balance_before,
                    de.spahr.ausgaben.settings.MoneyFormat.display(before, currency)));
            textBalanceAfter.setText(getString(R.string.balance_after,
                    de.spahr.ausgaben.settings.MoneyFormat.display(after, currency)));
            textBalanceBefore.setVisibility(View.VISIBLE);
            textBalanceAfter.setVisibility(View.VISIBLE);
        });
    }

    /** Macht ein Eingabefeld nicht editierbar, aber lesbar (kein Fokus/Cursor/Dropdown/Tastatur). */
    private void lockField(android.widget.EditText e) {
        e.setFocusable(false);
        e.setFocusableInTouchMode(false);
        e.setClickable(false);
        e.setLongClickable(false);
        e.setCursorVisible(false);
        e.setKeyListener(null);
        e.setOnClickListener(null);
    }

    /**
     * Öffnet als NEUE Buchung, vorbefüllt aus der Vorlage {@code b} (Sprach-Schnellerfassung): heutiges
     * Datum + {@code amountCents} (falls gesetzt), alle übrigen Daten aus der Vorlage.
     */
    private void bindTemplate(Booking b, Long amountCents) {
        if (b == null) {
            setupNewMode();
            return;
        }
        if (b.isTransfer && !presetTransferFromAccount.isEmpty()) {
            // Steckt das angezeigte Konto (Nutzereingabe) bereits als Von- oder Nach-Konto in der Vorlage,
            // gelten beide Konten unverändert wie dort hinterlegt. Sonst ersetzt es das Von-Konto
            // (Nach-Konto bleibt aus der Vorlage), siehe EXTRA_PRESET_TRANSFER_FROM_ACCOUNT.
            String tplFrom = b.isIncome ? b.transferAccount : b.account;
            String tplTo = b.isIncome ? b.account : b.transferAccount;
            boolean selMatches = (!tplFrom.isEmpty() && presetTransferFromAccount.equalsIgnoreCase(tplFrom))
                    || (!tplTo.isEmpty() && presetTransferFromAccount.equalsIgnoreCase(tplTo));
            if (!selMatches) {
                // transferGroup verwerfen: sonst würde populateFrom() den (zum neuen Von-Konto nicht mehr
                // passenden) Vorlagen-Ort asynchron nachträglich wieder setzen.
                if (b.isIncome) {
                    b.transferAccount = presetTransferFromAccount;
                } else {
                    b.account = presetTransferFromAccount;
                }
                b.transferGroup = "";
            }
        }
        booking = null; // Neu-Modus → Speichern legt eine neue Buchung an
        // Kopie aus einer Vorlage: GPS/Beleg NICHT übernehmen (GPS wird frisch bestimmt, Beleg nur bei neuem Bild).
        gpsRowCoords = null;
        clearReceiptPages();
        origIsTransfer = false;
        origTransferGroup = "";
        origPlaceManaged = true;
        openedFromExistingBooking = true; // Vorlage aus bestehender Buchung → Datum-Abfrage möglich
        templatePlaceFallback = true;
        toolbar.setTitle(R.string.new_booking_title);
        selectedDate.setTime(new java.util.Date());
        updateDateField();
        switchExported.setVisibility(View.GONE);
        btnUpdate.setVisibility(View.GONE);
        btnDelete.setVisibility(View.GONE);
        populateFrom(b, amountCents);
        refreshNoteLocation();
    }

    /**
     * Füllt die Felder aus {@code b}. {@code overrideAmountCents} (nicht null) ersetzt den Gesamtbetrag;
     * Splitbuchungen werden dann proportional skaliert (letzte Zeile nimmt den Rundungsrest).
     */
    private void populateFrom(Booking b, Long overrideAmountCents) {
        final long total = overrideAmountCents != null ? overrideAmountCents : b.amountCents;
        // Nur der freie Text ins Notizfeld – GPS/Beleg stehen in den zwei Ausgabezeilen darunter.
        editNote.setText(stripTags(b.note));
        // Die Stichwörter stehen an der Buchung, nicht in der Notiz; bei einer Umbuchung tragen beide
        // Zeilen dieselben.
        bookingTags = b.tags == null ? "" : b.tags;
        updateTagsRow();

        if (b.isTransfer) {
            toggleType.check(R.id.btnTransfer);
            editPayee.setText(b.payee);
            // Einnahme = Geld kam auf dieses Konto → dieses Konto ist „Nach".
            if (b.isIncome) {
                editAccount.setText(b.transferAccount, false);
                editAccountTo.setText(b.account, false);
            } else {
                editAccount.setText(b.account, false);
                editAccountTo.setText(b.transferAccount, false);
            }
            editAmount.setText(formatCents(total));
            applyTypeVisibility();
            // Von-/Nach-Ort aus beiden Seiten der Umbuchung vorbelegen.
            if (b.transferGroup != null && !b.transferGroup.isEmpty()) {
                repository.getTransferGroup(b.transferGroup, pair -> {
                    for (Booking side : pair) {
                        if (side.isIncome) {
                            String acc = textOf(editAccountTo).trim();
                            setupPlaceOptions(editPlaceTo, acc, false);
                            editPlaceTo.setText(templatePlace(side.place, acc), false);
                        } else {
                            String acc = textOf(editAccount).trim();
                            setupPlaceOptions(editPlace, acc, false);
                            editPlace.setText(templatePlace(side.place, acc), false);
                        }
                    }
                    // Die Orte kommen aus der Datenbank und damit erst nach applyTypeVisibility() oben.
                    // Beim Ansehen hängt die Sichtbarkeit gerade an ihnen, also noch einmal fragen.
                    applyTypeVisibility();
                });
            }
            updateSaveEnabled();
            return;
        }

        toggleType.check(b.isIncome ? R.id.btnIncome : R.id.btnExpense);
        editPayee.setText(b.payee);
        editAccount.setText(b.account, false);
        setupPlaceDropdown(b.account);
        // Ort der Vorlage vorbelegen; hat sie keinen, der Standardort des Kontos (Schnellerfassung).
        editPlace.setText(templatePlace(b.place, b.account), false);
        applyTypeVisibility();

        final long templateAmount = b.amountCents;
        final String singleCategory = b.category;
        final Boolean singleCategoryIsIncome = b.categoryIsIncome;
        // Geplante Vorschau: Split-Teile liegen direkt an {@code b.parts} (keine DB-Buchung vorhanden).
        if (b.parts != null) {
            fillSplitRows(b.parts, total, templateAmount, singleCategory, singleCategoryIsIncome);
            return;
        }
        // Kategorie-Teile laden (oder Einzelkategorie als eine Zeile); Betrag ggf. skaliert übernehmen.
        repository.getSplits(b.id, splits ->
                fillSplitRows(splits, total, templateAmount, singleCategory, singleCategoryIsIncome));
    }

    /**
     * Füllt die Split-Zeilen aus {@code splits} (proportional auf {@code total} skaliert). Übernimmt den
     * bereits gespeicherten Kategorietyp je Zeile, damit beim Bearbeiten/Duplizieren/Vorlagen keine
     * erneute Auswahl in der Kategorieliste nötig ist, um den Typ zu erhalten.
     */
    private void fillSplitRows(List<BookingSplit> splits, long total, long templateAmount,
                               String singleCategory, Boolean singleCategoryIsIncome) {
        splitCtl.setSuppressEvents(true);
        splitCtl.clear();
        if (splits != null && !splits.isEmpty()) {
            long assigned = 0;
            for (int idx = 0; idx < splits.size(); idx++) {
                BookingSplit s = splits.get(idx);
                long part;
                if (idx < splits.size() - 1 && templateAmount != 0) {
                    part = Math.round((double) s.amountCents * total / templateAmount);
                    assigned += part;
                } else {
                    part = total - assigned; // letzte Zeile → exakte Summe = Gesamtbetrag
                }
                splitCtl.addRow(s.category, formatCents(part), s.categoryIsIncome);
            }
        } else if (!singleCategory.isEmpty()) {
            splitCtl.addRow(singleCategory, formatCents(total), singleCategoryIsIncome);
        }
        splitCtl.setSuppressEvents(false);
        splitCtl.ensureTrailingRow();
        // Aus einer echten Buchung oder Planung geladen? Dann bleibt die Kategorie, wie sie ist. Aus
        // einer Vorlage der Spracherfassung gehört sie zum gefundenen Empfänger – und weicht einem
        // anderen, falls die Automatik danebenlag.
        if (keepLoadedCategories) {
            splitCtl.lockCategories();
        } else {
            markCategorySource();
        }
        editAmount.setText(formatCents(total));
        updateSaveEnabled();
    }

    private boolean isTransferType() {
        return toggleType.getCheckedButtonId() == R.id.btnTransfer;
    }

    /** Die gewählte Buchungsart als Drahtwert ({@code Repository.VOICE_TYPE_*}). */
    private String currentVoiceType() {
        if (isTransferType()) {
            return Repository.VOICE_TYPE_TRANSFER;
        }
        return isIncomeType() ? Repository.VOICE_TYPE_INCOME : Repository.VOICE_TYPE_EXPENSE;
    }

    private boolean isIncomeType() {
        return toggleType.getCheckedButtonId() == R.id.btnIncome;
    }

    /** Blendet Felder je nach Typ ein/aus (Umbuchung: zwei Konten, keine Kategorie/Ort/Empfänger). */
    private void applyTypeVisibility() {
        boolean transfer = isTransferType();
        accountToLayout.setVisibility(transfer ? View.VISIBLE : View.GONE);
        // Empfänger gibt es auch bei einer Umbuchung („Zahlungsempfänger"); Kategorien nicht.
        payeeLayout.setVisibility(View.VISIBLE);
        if (transfer) {
            // Umbuchung: Von- und Nach-Ort jeder für sich; die Dropdowns folgen ihrem Konto.
            placeLayout.setHint(getString(R.string.transfer_place_from));
            setupPlaceOptions(editPlace, textOf(editAccount).trim(), true);
            setupPlaceOptions(editPlaceTo, textOf(editAccountTo).trim(), true);
            placeLayout.setVisibility(showPlace(editPlace, editAccount) ? View.VISIBLE : View.GONE);
            placeToLayout.setVisibility(
                    showPlace(editPlaceTo, editAccountTo) ? View.VISIBLE : View.GONE);
        } else {
            placeLayout.setHint(getString(R.string.place_hint));
            placeLayout.setVisibility(showPlace(editPlace, editAccount) ? View.VISIBLE : View.GONE);
            placeToLayout.setVisibility(View.GONE);
        }
        splitSection.setVisibility(transfer ? View.GONE : View.VISIBLE);
        accountLayout.setHint(getString(transfer ? R.string.transfer_from : R.string.account_hint));
        payeeLayout.setHint(getString(transfer ? R.string.transfer_payee_hint : R.string.payee_hint));
        // GPS-/Beleg-Ausgabezeilen aktualisieren (GPS-Zeile z. B. bei Umbuchung ausblenden).
        updateNoteTagRows();
        // Durch diese Stelle läuft jeder Wechsel der Buchungsart und jedes Vorbelegen – also auch der
        // Anlaß, die Kategorien des Empfängers neu zu holen. Wiederholungen fängt der Schlüssel ab.
        refreshPayeeCategories();
        updateSaveEnabled();
    }

    /**
     * Schlägt aus dem eingetippten Betrag einen Empfänger vor: liegt im 100-m-Umkreis der
     * Standort-Marke genau <b>ein</b> Empfänger im Betragsband, wird er ins <b>leere</b> Feld
     * geschrieben – und zieht über {@link #refreshPayeeCategories()} seine Kategorie nach.
     *
     * <p>Bei mehreren oder keinem Treffer geschieht nichts: raten wäre schlimmer als nichts tun.
     * Abgeschaltet (Standard) unterbleibt der Vorschlag ganz – hier wählt man den Empfänger ohnehin
     * selbst.</p>
     */
    private void suggestPayeeFromAmount() {
        if (!settings.isAmountSuggestEnabled()) {
            return;
        }
        if (readOnly || !textOf(editPayee).trim().isEmpty()) {
            return;
        }
        double[] hier = de.spahr.ausgaben.location.Geo.parse(gpsRowCoords);
        Long cents = parseAmountToCents(textOf(editAmount));
        if (hier == null || cents == null || cents <= 0) {
            return;
        }
        String type = currentVoiceType();
        String key = cents + "|" + type + "|" + gpsRowCoords;
        if (key.equals(payeeAmountKey)) {
            return;
        }
        payeeAmountKey = key;
        repository.suggestPayeeByAmount(hier[0], hier[1], cents, type, name -> {
            // Die Antwort kommt später; inzwischen kann der Empfänger von Hand gefüllt sein.
            if (name == null || name.isEmpty() || !key.equals(payeeAmountKey)
                    || !textOf(editPayee).trim().isEmpty()) {
                return;
            }
            editPayee.setText(name, false);
            refreshPayeeCategories();
        });
    }

    /**
     * Holt die Kategorien des eingetragenen Empfängers: bevorzugte Aliase, dann seine Buchungen, dann
     * die übrigen Aliase (siehe {@link de.spahr.ausgaben.db.PayeeCategories}). Sie stehen als Vorspann
     * oben in jeder Kategorieliste; die <b>erste</b> belegt die Kategoriezeilen vor.
     *
     * <p>Die Vorbelegung <b>folgt dem Empfänger</b>: wählt man einen anderen (oder schaltet die
     * Buchungsart um), tritt dessen Kategorie an die Stelle der bisherigen – die Automatik hat sich ja
     * womöglich geirrt. Sobald der Nutzer in den Zeilen selbst etwas getan hat, bleibt seine Eingabe
     * ({@link SplitRowController#isCategoryAuto()}); {@link #categorySourceKey} verhindert, daß ein
     * mehrzeiliger Satz aus Alias oder Vorlage gleich beim Öffnen zusammenfällt.</p>
     *
     * <p>Umbuchungen haben keine Kategorien, die reine Ansicht nichts zu wählen. Gefragt wird erst,
     * wenn sich Empfänger oder Buchungsart wirklich geändert haben.</p>
     */
    /**
     * Hält fest, für welchen Empfänger und welche Buchungsart die eben vorbelegten Kategoriezeilen
     * gelten. Solange beides gleich bleibt, rührt {@link #refreshPayeeCategories()} sie nicht an – ein
     * Alias mit zwei Kategorien behält so seine zweite Zeile.
     */
    private void markCategorySource() {
        categorySourceKey = textOf(editPayee).trim().toLowerCase(Locale.ROOT) + "|" + isIncomeType();
    }

    private void refreshPayeeCategories() {
        if (readOnly || isTransferType()) {
            return;
        }
        String payee = textOf(editPayee).trim();
        boolean income = isIncomeType();
        String key = payee.toLowerCase(Locale.ROOT) + "|" + income;
        if (key.equals(payeeCategoryKey)) {
            return;
        }
        payeeCategoryKey = key;
        if (payee.isEmpty()) {
            splitCtl.setCategoryFavorites(getString(R.string.category_group_payee), null);
            return;
        }
        repository.getPayeeCategories(payee, income, cats -> {
            // Die Antwort kommt später; inzwischen kann ein anderer Empfänger im Feld stehen.
            if (!key.equals(payeeCategoryKey)) {
                return;
            }
            splitCtl.setCategoryFavorites(getString(R.string.category_group_payee), cats);
            if (splitCtl.isCategoryAuto() && !key.equals(categorySourceKey)) {
                splitCtl.replaceAutoCategories(cats.isEmpty() ? null : cats.get(0));
                categorySourceKey = key;
            }
        });
    }

    // ---- Dynamische Split-Liste ----

    private void updateSaveEnabled() {
        boolean enabled;
        if (notesOnly || (booking != null
                && isSecurityCounterpart(booking.transferAccount, booking.isTransfer))) {
            // Wertpapier-Buchung: es gibt nichts zu prüfen – Notiz, Stichwörter und Beleg sind immer
            // gültig. Ohne diesen Zweig bliebe der Knopf abgeschaltet, weil das Wertpapier für die App
            // kein Konto ist; genau daran ist das Ändern bisher gescheitert.
            enabled = true;
        } else if (isTransferType()) {
            String from = textOf(editAccount).trim();
            String to = textOf(editAccountTo).trim();
            Long cents = parseAmountToCents(textOf(editAmount));
            enabled = isKnownAccount(from) && isKnownAccount(to) && !from.equalsIgnoreCase(to)
                    && cents != null && cents > 0;
        } else {
            enabled = splitCtl.isValid();
        }
        btnSaveNew.setEnabled(enabled);
        btnUpdate.setEnabled(enabled);
    }

    // ---- Datum ----

    private void updateDateField() {
        editDate.setText(DateFormats.date(selectedDate.getTimeInMillis()));
        btnToday.setVisibility(isToday(selectedDate) ? View.GONE : View.VISIBLE);
    }

    private boolean isToday(Calendar c) {
        Calendar now = Calendar.getInstance();
        return c.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                && c.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR);
    }

    private void showDatePicker() {
        new DatePickerDialog(this, (view, year, month, day) -> {
            selectedDate.set(Calendar.YEAR, year);
            selectedDate.set(Calendar.MONTH, month);
            selectedDate.set(Calendar.DAY_OF_MONTH, day);
            dateChangedByUser = true;
            updateDateField();
        }, selectedDate.get(Calendar.YEAR), selectedDate.get(Calendar.MONTH),
                selectedDate.get(Calendar.DAY_OF_MONTH)).show();
    }

    /**
     * Führt {@code proceed} aus; fragt das Datum nur nach, wenn eine bestehende Buchung als Vorlage geöffnet
     * wurde, deren (altes) Datum unverändert blieb und daraus eine neue Buchung angelegt wird (Kopieren).
     * Beim Ändern der bestehenden Buchung oder bei selbst gesetztem/heutigem Datum kommt keine Abfrage.
     */
    private void maybeDateConfirm(Runnable proceed) {
        if (!openedFromExistingBooking || dateChangedByUser || isToday(selectedDate)) {
            proceed.run();
            return;
        }
        String dateStr = DateFormats.date(selectedDate.getTimeInMillis());
        new AppDialog(this)
                .setTitle(R.string.date_confirm_title)
                .setMessage(getString(R.string.date_confirm_message, dateStr))
                .setPositiveButton(getString(R.string.date_use_given, dateStr), (d, w) -> proceed.run())
                .setNegativeButton(R.string.date_use_today, (d, w) -> {
                    selectedDate.setTime(new java.util.Date());
                    updateDateField();
                    proceed.run();
                })
                .show();
    }

    /**
     * Ob ein Ortsfeld überhaupt hingehört.
     *
     * <p>Beim Ansehen zählt der Ort der Buchung selbst: „ohne Ort" ist keine Auskunft, und eine
     * importierte Buchung hat gar keinen. Beim Bearbeiten zählt dagegen das Konto – dort soll man einen
     * Ort ja erst setzen können, auch bei einer Buchung, die noch keinen hat.</p>
     */
    private boolean showPlace(android.widget.EditText placeField, android.widget.EditText accountField) {
        if (readOnly) {
            String place = textOf(placeField).trim();
            return !place.isEmpty() && !place.equals(PlacesStore.NO_PLACE);
        }
        return hasPlaces(textOf(accountField));
    }

    /** True, wenn das Konto mindestens einen Ort besitzt (steuert die Sichtbarkeit des Ortsfelds). */
    private boolean hasPlaces(String account) {
        return account != null && !account.trim().isEmpty()
                && !placesStore.getPlaces(account.trim()).isEmpty();
    }

    /** Ort-Dropdown der Ausgabe/Einnahme: Orte des Kontos, vorbelegt mit dessen Standardort. */
    private void setupPlaceDropdown(String account) {
        setupPlaceOptions(editPlace, account, false);
    }

    /**
     * Befüllt ein Ort-Dropdown mit den Orten des Kontos und belegt es mit dessen Standardort vor (hat das
     * Konto keinen, mit „ohne Ort"). {@code keepCurrent} behält stattdessen einen gültigen aktuellen Wert.
     */
    private void setupPlaceOptions(MaterialAutoCompleteTextView field, String account, boolean keepCurrent) {
        List<String> options = new ArrayList<>(placesStore.getPlaces(account));
        options.add(PlacesStore.NO_PLACE);
        PickerAdapters.places(field, options);
        String cur = textOf(field).trim();
        if (keepCurrent && !cur.isEmpty() && options.contains(cur)) {
            return;
        }
        // In der reinen Ansicht (geplante Buchung) gilt allein der gespeicherte Ort – ein Standardort würde
        // dort einen Ort vortäuschen, den die Planung gar nicht hat.
        String def = readOnly ? "" : placesStore.getDefaultPlace(account);
        field.setText(!def.isEmpty() && options.contains(def) ? def : PlacesStore.NO_PLACE, false);
    }

    /**
     * Ort für die Vorbelegung aus einer Vorlage: deren gespeicherter Ort. Hat die Vorlage keinen (etwa eine
     * importierte Buchung), greift bei der Schnellerfassung der Standardort des Kontos – sonst „ohne Ort".
     */
    private String templatePlace(String place, String account) {
        if (place != null && !place.trim().isEmpty()) {
            return place;
        }
        if (templatePlaceFallback) {
            String acc = account == null ? "" : account.trim();
            String def = placesStore.getDefaultPlace(acc);
            if (!def.isEmpty() && placesStore.getPlaces(acc).contains(def)) {
                return def;
            }
        }
        return PlacesStore.NO_PLACE;
    }

    /** Ausgewählter Nach-Ort (Umbuchung), normalisiert: „ohne Ort"/leer → {@code ""}. */
    private String selectedPlaceTo() {
        String sel = textOf(editPlaceTo);
        return (sel != null && !sel.trim().isEmpty() && !sel.equals(PlacesStore.NO_PLACE))
                ? sel.trim() : "";
    }

    // ---- Speichern (neu) ----

    private void saveAsNew() {
        if (isTransferType()) {
            saveTransferNew();
            return;
        }
        Booking b = readValidFields(new Booking());
        if (b == null) {
            return;
        }
        b.exported = false;
        final List<SplitRowController.Part> parts = splitCtl.collectParts();
        b.category = parts.isEmpty() ? "" : parts.get(0).category;
        b.categoryIsIncome = parts.isEmpty() ? null : resolvePartType(parts.get(0));
        final String place = textOf(editPlace);
        maybeAskCorrection(b.payee, () -> maybeDateConfirm(() -> {
            b.createdAt = composeTimestamp();
            persistNew(b, place, parts);
        }));
    }

    private void saveTransferNew() {
        final String from = textOf(editAccount).trim();
        final String to = textOf(editAccountTo).trim();
        final Long cents = parseAmountToCents(textOf(editAmount));
        if (cents == null || cents <= 0) {
            Toast.makeText(this, R.string.error_amount, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isKnownAccount(from) || !isKnownAccount(to) || from.equalsIgnoreCase(to)) {
            Toast.makeText(this, R.string.error_transfer_accounts, Toast.LENGTH_SHORT).show();
            return;
        }
        final String note = composeNoteForSave(true);
        final String payee = textOf(editPayee).trim();
        final String fromPlace = selectedPlace();
        final String toPlace = selectedPlaceTo();
        maybeAskCorrection(payee, () -> maybeDateConfirm(() -> {
            long ts = composeTimestamp();
            // Der Beleg wird erst hier festgeschrieben – bis zur Bestätigung ist nichts gespeichert.
            // Beide Seiten bekommen dieselbe Notiz und damit denselben BELEG:-Tag – und dieselben
            // Stichwörter, denn in der .kmy-Datei ist die Umbuchung eine einzige Transaktion.
            repository.saveTransferBooking(from, to, cents, payee, withReceiptTag(note, ts, true),
                bookingTags, ts, fromPlace, toPlace, () -> {
                    Toast.makeText(this, R.string.transfer_saved, Toast.LENGTH_SHORT).show();
                    finishAfterSave();
                });
        }));
    }

    private void persistNew(Booking b, String place, List<SplitRowController.Part> parts) {
        // Ort wird an der Buchung gespeichert (Standardort ist ein echter Ort; „ohne Ort" → leer).
        final String fp = place;
        Runnable done = () -> {
            Toast.makeText(this, R.string.booking_saved, Toast.LENGTH_SHORT).show();
            finishAfterSave();
        };
        // Neue Buchung: Notiz = freier Text + aktuelle GPS-Position; Beleg nur, wenn neu angehängt.
        b.note = composeNoteForSave(true);
        attachReceipt(b, true, () -> {
            if (parts.size() >= 2) {
                repository.saveSplitBooking(b, toSplits(parts), fp, done);
            } else {
                repository.saveBookingWithPlace(b, fp, done);
            }
        });
    }

    /** Ausgewählter Ort normalisiert: „ohne Ort" bzw. leer → {@code ""}, sonst der echte Ortsname. */
    private String selectedPlace() {
        String sel = textOf(editPlace);
        return (sel != null && !sel.trim().isEmpty() && !sel.equals(PlacesStore.NO_PLACE))
                ? sel.trim() : "";
    }

    // ---- GPS-/Beleg-Ausgabezeilen ----

    /**
     * Beim Bearbeiten ist „Buchung ändern" die gemeinte Aktion – also bekommt sie die gefüllte Optik, und
     * „Neue Buchung" (legt eine Kopie an, heißt aber genau wie der FAB der Liste) wird zum Umriss-Knopf.
     * Beim Neuanlegen bleibt alles, wie es ist; dort ist „Neue Buchung" ja die richtige Hauptaktion.
     */
    private void emphasizeUpdate() {
        int accent = getColor(R.color.button_accent);
        android.content.res.ColorStateList accentList =
                android.content.res.ColorStateList.valueOf(accent);

        btnUpdate.setBackgroundTintList(accentList);
        btnUpdate.setTextColor(getColor(R.color.white));
        btnUpdate.setStrokeWidth(0);

        btnSaveNew.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                android.graphics.Color.TRANSPARENT));
        btnSaveNew.setTextColor(accent);
        btnSaveNew.setStrokeColor(accentList);
        btnSaveNew.setStrokeWidth(Math.round(getResources().getDisplayMetrics().density));
    }

    /**
     * Freier Notiztext ohne die technischen {@code GPS:}- und {@code BELEG:}-Tags. Auch die Buchungsliste
     * zeigt die Notiz damit aufbereitet ({@link BookingAdapter}) – dieselbe Notiz darf nicht an einer
     * Stelle sauber und an der anderen roh erscheinen.
     */
    static String stripTags(String note) {
        if (note == null) {
            return "";
        }
        String s = note.replaceAll("\\s*GPS:\\s*-?\\d+(?:\\.\\d+)?\\s*,\\s*-?\\d+(?:\\.\\d+)?", "");
        s = NoteReceipt.strip(s);
        return s.trim();
    }

    /** Die „lat, lon" hinter einem {@code GPS:}-Tag (exakt wie gespeichert), sonst {@code null}. */
    private String parseGpsCoords(String note) {
        if (note == null) {
            return null;
        }
        java.util.regex.Matcher m = GPS_PAIR.matcher(note);
        return m.find() ? m.group(1).replaceAll("\\s+", "") : null;
    }

    /** Aktualisiert die drei Ausgabezeilen (GPS, Stichwörter, Beleg) je nach Ansicht-/Bearbeiten-Modus. */
    private void updateNoteTagRows() {
        if (rowGps == null) {
            return; // Views noch nicht gebunden
        }
        // GPS-Zeile. Hier läuft jede Änderung des Buchungs-Standorts zusammen – neue Buchung, geladene
        // Buchung, Kartenwahl –, deshalb hängt der Vorspann der Empfängerliste an dieser einen Stelle.
        refreshNearbyPayees();
        double[] ll = de.spahr.ausgaben.location.Geo.parse(gpsRowCoords);
        if (ll != null) {
            textGps.setText(getString(R.string.gps_row_label, gpsDisplay(gpsRowCoords)));
            final double lat = ll[0];
            final double lon = ll[1];
            // Ansicht: nur Karte zeigen. Bearbeiten/Neu: Standort auf der Karte ändern bzw. löschen.
            if (readOnly) {
                btnNoteMap.setOnClickListener(v -> openMapAt(lat, lon));
                btnGpsClear.setVisibility(View.GONE);
            } else {
                btnNoteMap.setOnClickListener(v -> openMapForEdit(lat, lon));
                btnGpsClear.setVisibility(View.VISIBLE);
                btnGpsClear.setOnClickListener(v -> {
                    // Ohne Rückfrage – wie das Löschkreuz der Stichwörter; rückgängig durch Verlassen
                    // der Maske, ohne zu speichern.
                    gpsRowCoords = null;
                    gpsEditedByUser = true;
                    updateNoteTagRows();
                });
            }
            rowGps.setVisibility(View.VISIBLE);
        } else if (!readOnly && settings.isGpsEnabled() && !isTransferType()) {
            // Noch kein Standort: Zeile zum Setzen eines Standorts anbieten.
            textGps.setText(R.string.gps_row_none);
            btnNoteMap.setOnClickListener(v -> openMapForEdit(null, null));
            btnGpsClear.setVisibility(View.GONE);
            rowGps.setVisibility(View.VISIBLE);
        } else {
            rowGps.setVisibility(View.GONE);
        }
        updateTagsRow();
        // Beleg-Kopfzeile + eine Zeile je Seite
        if (readOnly) {
            rowReceipt.setVisibility(receiptPages.isEmpty() ? View.GONE : View.VISIBLE);
            textReceipt.setText(getString(R.string.receipt_row_label, receiptCountText()));
            btnReceipt.setVisibility(View.GONE);
        } else if (receiptEnabled) {
            // Auch bei einer Umbuchung: der Tag steht in der gemeinsamen Notiz, also zeigen beide Seiten
            // denselben Beleg.
            rowReceipt.setVisibility(View.VISIBLE);
            textReceipt.setText(getString(R.string.receipt_row_label, receiptPages.isEmpty()
                    ? getString(R.string.receipt_none)
                    : receiptCountText()));
            btnReceipt.setVisibility(View.VISIBLE);
            btnReceipt.setImageResource(android.R.drawable.ic_menu_camera);
            btnReceipt.setOnClickListener(v -> showReceiptSourceDialog());
        } else {
            rowReceipt.setVisibility(View.GONE);
        }
        fillReceiptPages();
    }

    /**
     * Die Stichwort-Zeile. Sie erscheint nur, wenn die App überhaupt Stichwörter aus einer
     * {@code .kmy}-Datei kennt – ohne sie gäbe es nichts zu wählen. In der Ansicht bleibt der Text
     * stehen, die beiden Symbole verschwinden.
     */
    private void updateTagsRow() {
        if (rowTags == null) {
            return; // Views noch nicht gebunden
        }
        boolean show = !knownTagNames.isEmpty() && (!readOnly || !bookingTags.isEmpty());
        rowTags.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) {
            return;
        }
        String label = BookingTags.label(bookingTags, TAGS_LABEL_MAX);
        textTags.setText(getString(R.string.tags_row,
                label.isEmpty() ? getString(R.string.tags_none) : label));
        btnTagsEdit.setVisibility(readOnly ? View.GONE : View.VISIBLE);
        btnTagsClear.setVisibility(readOnly || bookingTags.isEmpty() ? View.GONE : View.VISIBLE);
        btnTagsEdit.setOnClickListener(v -> showTagsDialog());
        btnTagsClear.setOnClickListener(v -> {
            // Ohne Rückfrage – wie das Löschkreuz einer Belegseite; rückgängig durch Verlassen
            // der Maske, ohne zu speichern.
            bookingTags = "";
            noteTagsEdited();
            updateTagsRow();
        });
    }

    /**
     * Das Pop-Up zu den Stichwörtern: oben die vergebenen, jedes einzeln zu löschen, darunter ein
     * Feld zum Hinzufügen. Es verhält sich wie das Konto- und das Kategoriefeld – gesucht wird über
     * Teiltreffer, und was auf keinen Eintrag paßt, wird verworfen: eingebbar ist nur, was es in
     * KMyMoney gibt.
     */
    private void showTagsDialog() {
        // Der Stift nimmt dem Empfängerfeld nicht den Fokus, und ein Feld mitten in der Suche ist leer:
        // ohne dieses settleAll wäre ein nur getippter Empfängername hier noch nicht angekommen.
        PickerBehaviour.settleAll(getWindow().getDecorView());
        final String payee = textOf(editPayee).trim();
        if (payee.isEmpty() || knownTagNames.isEmpty()) {
            openTagsDialog(new ArrayList<>());
            return;
        }
        // Erst fragen, dann öffnen. Andersherum stünde das Fenster schon da, wenn die Antwort eintrifft –
        // dann bliebe der Vorspann leer und eine Vorbelegung ginge beim „Fertig" wieder verloren.
        repository.getPayeeTags(payee, suggestion -> {
            applyTagPreset(payee, suggestion);
            openTagsDialog(suggestion.ranked);
        });
    }

    private void openTagsDialog(java.util.List<String> lead) {
        TagsDialog.show(this, knownTagNames, lead, bookingTags, tags -> {
            bookingTags = tags;
            noteTagsEdited();
            updateTagsRow();
        });
    }

    /**
     * Merkt sich, dass die Stichwörter von Hand gesetzt wurden – für <b>diesen</b> Empfänger schlägt
     * die App dann nichts mehr vor. Was gelöscht wurde, bleibt gelöscht; wählen Sie dagegen einen
     * anderen Empfänger, gilt dessen Vorbelegung wieder.
     */
    private void noteTagsEdited() {
        tagsEditedForPayee = textOf(editPayee).trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Holt die Stichwörter des gewählten Empfängers und belegt eine <b>neue</b> Buchung damit vor.
     * Gegenstück zu {@link #refreshPayeeCategories()} und am selben Faden aufgehängt – jedem
     * bestätigten Empfänger folgt beides.
     */
    private void refreshPayeeTags() {
        if (readOnly || knownTagNames.isEmpty()) {
            return;
        }
        final String payee = textOf(editPayee).trim();
        final String key = payee.toLowerCase(Locale.ROOT);
        if (key.equals(payeeTagKey)) {
            return; // derselbe Empfänger – nichts zu tun
        }
        payeeTagKey = key;
        if (payee.isEmpty()) {
            return;
        }
        repository.getPayeeTags(payee, suggestion -> applyTagPreset(payee, suggestion));
    }

    /**
     * Übernimmt die Stichwörter des Empfängers in die Buchung – aber nur, wenn dort noch keine stehen:
     * eine geöffnete Buchung und eine Planung bringen ihre eigenen mit, und die soll ein
     * Empfängerwechsel nicht wegräumen.
     */
    private void applyTagPreset(String payee, de.spahr.ausgaben.db.PayeeTagSuggestion suggestion) {
        if (payee.toLowerCase(Locale.ROOT).equals(tagsEditedForPayee)) {
            return; // hier hat der Nutzer selbst entschieden – auch, wenn er alles gelöscht hat
        }
        if (bookingTags.isEmpty() && !suggestion.preset.isEmpty()) {
            bookingTags = suggestion.preset;
            updateTagsRow();
        }
    }

    /**
     * Baut die Anzeige der Belegseiten neu auf. Bei <b>Fotos</b> genügt in der Ansicht ein Bild-Symbol
     * rechtsbündig in der Kopfzeile – geblättert wird dann im eigenen Betrachter; im Bearbeiten-Modus steht
     * je Seite eine Zeile mit Beschriftung, Zuschneiden und Löschen darunter.
     *
     * <p>Ein <b>PDF</b> bekommt immer eine eigene Zeile, auch in der Ansicht: es öffnet sich einzeln im
     * Betrachter des Geräts, und bei mehreren muss zu sehen sein, welches man antippt. Zuschneiden gibt es
     * dort nicht, in der Ansicht auch kein Löschen.</p>
     */
    private void fillReceiptPages() {
        receiptPagesView.removeAllViews();
        receiptPageIcons.removeAllViews();
        if (rowReceipt.getVisibility() != View.VISIBLE) {
            return;
        }
        if (readOnly && !hasPdfPages()) {
            // Ein einziges Symbol – wie viele Seiten es sind, steht schon im Text daneben; im Betrachter
            // wird dann geblättert.
            if (savedPageNames().isEmpty()) {
                return;
            }
            android.widget.ImageButton icon = new android.widget.ImageButton(this);
            icon.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp(44), dp(44)));
            icon.setImageResource(android.R.drawable.ic_menu_gallery);
            icon.setBackgroundResource(backgroundBorderless());
            icon.setContentDescription(getString(R.string.receipt_view_title));
            icon.setOnClickListener(v -> openReceiptViewer(0));
            receiptPageIcons.addView(icon);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < receiptPages.size(); i++) {
            final Page page = receiptPages.get(i);
            final boolean pdf = page.isPdf();
            View row = inflater.inflate(R.layout.item_receipt_page, receiptPagesView, false);
            android.widget.TextView label = row.findViewById(R.id.textReceiptPage);
            label.setText(getString(pdf
                    ? (page.pending != null ? R.string.receipt_pdf_new : R.string.receipt_pdf_label)
                    : (page.pending != null ? R.string.receipt_page_new : R.string.receipt_page_label),
                    i + 1));
            label.setCompoundDrawablesRelativeWithIntrinsicBounds(pdf ? R.drawable.ic_pdf : 0, 0, 0, 0);
            label.setCompoundDrawablePadding(pdf ? dp(8) : 0);
            if (pdf) {
                // Ein PDF öffnet der Betrachter des Geräts – auch ein noch nicht gespeichertes Temp.
                label.setOnClickListener(v -> openPdf(page));
            } else if (page.savedName != null) {
                // Eine bereits gespeicherte Seite lässt sich ansehen; ein frisches Bild liegt nur als Temp vor.
                final int index = savedPageNames().indexOf(page.savedName);
                label.setOnClickListener(v -> openReceiptViewer(index));
            }
            View edit = row.findViewById(R.id.btnReceiptPageEdit);
            View delete = row.findViewById(R.id.btnReceiptPageDelete);
            edit.setVisibility(pdf || readOnly ? View.GONE : View.VISIBLE);
            delete.setVisibility(readOnly ? View.GONE : View.VISIBLE);
            edit.setOnClickListener(v -> editReceipt(page));
            delete.setOnClickListener(v -> removeReceiptPage(page));
            receiptPagesView.addView(row);
        }
    }

    /**
     * Öffnet ein PDF im Standard-Betrachter des Geräts. Eine bereits gespeicherte Datei wird bei Bedarf
     * erst vom Netzlaufwerk geholt (deshalb der Hintergrund-Thread), dann als {@code content://}-Verweis
     * des FileProviders weitergereicht – der fremden App wird nur Lesen für diese eine Datei gestattet.
     */
    private void openPdf(Page page) {
        final java.io.File pending = page.pending;
        final String saved = page.savedName;
        final int year = receiptYear();
        // Nur ein noch nicht lokaler Beleg wird geholt – dann die Statuszeile zeigen (kann dauern).
        final boolean willWait = pending == null;
        if (willWait) {
            receiptBanner.start(getString(R.string.receipt_loading_wait));
        }
        new Thread(() -> {
            final java.io.File file;
            final boolean offline;
            if (pending != null) {
                file = pending;
                offline = false;
            } else {
                ReceiptSync.Loaded loaded = ReceiptSync.ensureLocalWaiting(this, saved, year, null);
                file = loaded.file;
                offline = loaded.offline;
            }
            post(() -> {
                if (willWait) {
                    receiptBanner.finishNow();
                }
                if (file == null || !file.exists()) {
                    // Ohne Verbindung nur ein Hinweis; online, aber unauffindbar → Entfernen anbieten.
                    if (offline) {
                        Toast.makeText(this, R.string.receipt_offline, Toast.LENGTH_SHORT).show();
                    } else {
                        promptRemoveUnavailableReceipt(page);
                    }
                    return;
                }
                try {
                    android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                            this, getPackageName() + ".fileprovider", file);
                    startActivity(new Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/pdf")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                } catch (android.content.ActivityNotFoundException e) {
                    Toast.makeText(this, R.string.receipt_pdf_no_viewer, Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, R.string.receipt_error, Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Der randlose Tipp-Hintergrund des Themes – wie bei den Knöpfen im Layout. */
    private int backgroundBorderless() {
        android.util.TypedValue out = new android.util.TypedValue();
        getTheme().resolveAttribute(androidx.appcompat.R.attr.selectableItemBackgroundBorderless, out, true);
        return out.resourceId;
    }

    /** Anzeigeform der Koordinaten, z. B. „50.1109° N, 8.6821° O". */
    private String gpsDisplay(String coords) {
        double[] ll = de.spahr.ausgaben.location.Geo.parse(coords);
        if (ll == null) {
            return coords == null ? "" : coords;
        }
        String ns = getString(ll[0] >= 0 ? R.string.compass_n : R.string.compass_s);
        String ew = getString(ll[1] >= 0 ? R.string.compass_e : R.string.compass_w);
        return String.format(java.util.Locale.US, "%.4f° %s, %.4f° %s",
                Math.abs(ll[0]), ns, Math.abs(ll[1]), ew);
    }

    private void openMapAt(double lat, double lon) {
        Intent i = new Intent(this, MapPickerActivity.class);
        i.putExtra(MapPickerActivity.EXTRA_LAT, lat);
        i.putExtra(MapPickerActivity.EXTRA_LON, lon);
        i.putExtra(MapPickerActivity.EXTRA_VIEW_ONLY, true);
        startActivity(i);
    }

    /**
     * Öffnet die Karten-Auswahl (wählbar, wie im Alias), zentriert auf die aktuellen Koordinaten (falls
     * vorhanden – sonst letzte bekannte Position/Standard). Das Ergebnis übernimmt {@link #gpsMapLauncher}.
     */
    private void openMapForEdit(Double lat, Double lon) {
        Intent i = new Intent(this, MapPickerActivity.class);
        if (lat != null && lon != null) {
            i.putExtra(MapPickerActivity.EXTRA_LAT, (double) lat);
            i.putExtra(MapPickerActivity.EXTRA_LON, (double) lon);
        }
        gpsMapLauncher.launch(i);
    }

    /** Koordinaten als „lat,lon" mit sechs Nachkommastellen (wie die Karten-Auswahl liefert). */
    private static String formatCoords(double lat, double lon) {
        return String.format(java.util.Locale.US, "%.6f,%.6f", lat, lon);
    }

    /** Freier Text + (je nach Kopie/Update) GPS-Tag. Der BELEG:-Tag kommt in {@link #attachReceipt}. */
    private String composeNoteForSave(boolean asNew) {
        String free = textOf(editNote).trim();
        String coords;
        if (asNew) {
            // Neu/Vorlage (booking == null): der Zeilenwert ist bereits die aktuelle Position.
            // „Als neue speichern" aus einer bestehenden Buchung: frische Position vom Tagger holen –
            // außer der Nutzer hat den Standort manuell auf der Karte gewählt (dann gilt dieser).
            coords = (booking == null || gpsEditedByUser) ? gpsRowCoords
                    : (locationTagger != null ? locationTagger.currentCoordinates() : null);
        } else {
            coords = gpsRowCoords;
        }
        if (coords != null && !coords.trim().isEmpty()) {
            free = free.isEmpty() ? "GPS: " + coords : free + " GPS: " + coords;
        }
        return free;
    }

    // ---- Beleg-Foto ----

    /**
     * Kamera, Galerie oder PDF-Dokument. Eine Buchung trägt entweder Fotoseiten oder PDFs – der unpassende
     * Eintrag ist deshalb abgeblendet, bis alle vorhandenen Seiten gelöscht sind.
     */
    private void showReceiptSourceDialog() {
        String[] items = {
                getString(R.string.receipt_source_camera),
                getString(R.string.receipt_source_gallery),
                getString(R.string.receipt_source_document)
        };
        final boolean pdf = hasPdfPages();
        final boolean photo = hasPhotoPages();
        // Ein ArrayAdapter statt setItems: nur er kann einzelne Einträge sperren (die Liste des Dialogs
        // richtet sich nach isEnabled).
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<String>(
                this, android.R.layout.simple_list_item_1, items) {
            @Override
            public boolean isEnabled(int position) {
                return position == 2 ? !photo : !pdf;
            }

            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                v.setAlpha(isEnabled(position) ? 1f : 0.4f);
                return v;
            }
        };
        new AppDialog(this)
                .setTitle(R.string.receipt_add)
                .setAdapter(adapter, (d, which) -> {
                    if (which == 0) {
                        startReceiptCamera();
                    } else if (which == 1) {
                        pickImageLauncher.launch("image/*");
                    } else {
                        pickPdfLauncher.launch(new String[]{"application/pdf"});
                    }
                })
                .show();
    }

    /** „3 Seite(n)" bzw. „2 Dokument(e)" – je nachdem, woraus der Beleg besteht. */
    private String receiptCountText() {
        return getString(hasPdfPages() ? R.string.receipt_pdfs_count : R.string.receipt_pages_count,
                receiptPages.size());
    }

    /** Hängt an der Buchung mindestens ein PDF? */
    private boolean hasPdfPages() {
        for (Page p : receiptPages) {
            if (p.isPdf()) {
                return true;
            }
        }
        return false;
    }

    /** Hängt an der Buchung mindestens eine Fotoseite? */
    private boolean hasPhotoPages() {
        for (Page p : receiptPages) {
            if (!p.isPdf()) {
                return true;
            }
        }
        return false;
    }

    private void startReceiptCamera() {
        try {
            cameraTempFile = new java.io.File(Receipts.dir(this), "cam_" + System.currentTimeMillis() + ".jpg");
            cameraTempUri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", cameraTempFile);
            takePictureLauncher.launch(cameraTempUri);
        } catch (Exception e) {
            Toast.makeText(this, R.string.receipt_error, Toast.LENGTH_SHORT).show();
        }
    }

    /** Komprimiert die Quelle sofort in ein Temp (Berechtigung ist jetzt gültig); Finalisierung erst beim Speichern. */
    private void ingestReceipt(android.net.Uri src, java.io.File cleanup) {
        final java.io.File tmp = new java.io.File(Receipts.dir(this),
                "pend_" + java.util.UUID.randomUUID() + ".jpg");
        new Thread(() -> {
            boolean ok;
            try {
                ReceiptImage.saveScaledJpeg(this, src, tmp, 2000, 75);
                ok = tmp.exists() && tmp.length() > 0;
            } catch (Exception e) {
                ok = false;
            }
            if (cleanup != null) {
                cleanup.delete();
            }
            final boolean fok = ok;
            post(() -> {
                if (fok) {
                    Page page = new Page(null, tmp);
                    receiptPages.add(page);
                    updateNoteTagRows();
                    Toast.makeText(this, R.string.receipt_attached, Toast.LENGTH_SHORT).show();
                    askEditReceipt(page);
                } else {
                    tmp.delete();
                    Toast.makeText(this, R.string.receipt_error, Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    /**
     * Übernimmt ein gewähltes PDF: es wird unverändert ins Temp kopiert – anders als beim Foto gibt es
     * nichts zu skalieren, zu drehen oder nachzubearbeiten. Finalisierung wie dort erst beim Speichern.
     */
    private void ingestPdf(android.net.Uri src) {
        final java.io.File tmp = new java.io.File(Receipts.dir(this),
                "pend_" + java.util.UUID.randomUUID() + NoteReceipt.PDF);
        new Thread(() -> {
            boolean ok;
            try (java.io.InputStream in = getContentResolver().openInputStream(src);
                 java.io.OutputStream out = new java.io.FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while (in != null && (n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                ok = tmp.exists() && tmp.length() > 0;
            } catch (Exception e) {
                ok = false;
            }
            final boolean fok = ok;
            post(() -> {
                if (fok) {
                    receiptPages.add(new Page(null, tmp));
                    updateNoteTagRows();
                    Toast.makeText(this, R.string.receipt_pdf_attached, Toast.LENGTH_SHORT).show();
                } else {
                    tmp.delete();
                    Toast.makeText(this, R.string.receipt_error, Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    /** Alle Seiten vergessen (Neu-Modus/Kopie); noch nicht gespeicherte Temps werden gelöscht. */
    private void clearReceiptPages() {
        for (Page p : receiptPages) {
            if (p.pending != null) {
                originalOf(p.pending).delete();
                p.pending.delete();
            }
        }
        receiptPages.clear();
        removedReceipts.clear();
    }

    /**
     * Ermittelt zum Beleg-Tag einer Notiz alle Seiten (siehe {@link ReceiptPages#find}) und zeigt sie an.
     * Welche Art es ist, sagt die Notiz selbst: {@code BELEG:} steht für Fotoseiten, {@code BELEG (PDF):}
     * für PDF-Dokumente. Das Suchen kann den Server befragen und läuft deshalb im Hintergrund.
     */
    private void loadReceiptPages(String note, int year) {
        clearReceiptPages();
        origReceiptYear = -1;
        final String pdfTag = NoteReceipt.pdfName(note);
        final String tagName = pdfTag != null ? pdfTag : NoteReceipt.fileName(note);
        if (tagName == null) {
            return;
        }
        final String ext = pdfTag != null ? NoteReceipt.PDF : NoteReceipt.JPG;
        origReceiptYear = year;
        // Seite 1 steht sofort fest, damit die Zeile nicht erst leer aufblitzt.
        receiptPages.add(new Page(pdfTag != null ? NoteReceipt.pageName(tagName, 1, ext) : tagName, null));
        new Thread(() -> {
            final java.util.List<String> found = ReceiptPages.find(this, tagName, year, ext);
            post(() -> {
                // Nichts gefunden (Datei weg oder offline) → die Vorbelegung mit dem Tag-Namen bleibt stehen.
                if (isFinishing() || found.isEmpty() || found.equals(savedNames())) {
                    return;
                }
                receiptPages.clear();
                for (String name : found) {
                    receiptPages.add(new Page(name, null));
                }
                updateNoteTagRows();
            });
        }).start();
    }

    /**
     * Jahresordner der Belege dieser Buchung – aus dem (ggf. gerade geänderten) Buchungsdatum, denn der
     * Dateiname trägt das Jahr nicht mehr.
     */
    private int receiptYear() {
        return selectedDate.get(Calendar.YEAR);
    }

    /**
     * Wurde das Buchungsdatum über einen Jahreswechsel geschoben, wandern die bereits hochgeladenen Bilder
     * auf dem Server in den neuen Jahresordner. Läuft im Hintergrund.
     *
     * <p>Misslingt es (offline), bleibt der Umzug vorgemerkt und wird beim nächsten Abgleich nachgeholt
     * ({@code ReceiptPages.movePending}). Darauf kommt es an: Die Notiz nennt ab sofort das neue Jahr,
     * und {@code ensureLocal} sucht nur dort. Hier stand früher, ein Rückfall in {@code ensureLocal}
     * finde die Datei weiterhin – das stimmte nie, denn der Rückfall probiert einen anderen
     * Basisordner, aber dasselbe Jahr. Auf diesem Gerät fiel es nur deshalb nicht auf, weil die lokale
     * Kopie liegen bleibt; auf jedem anderen war der Beleg weg.</p>
     */
    private void moveReceiptYear(int newYear) {
        if (booking == null || origReceiptYear < 0 || origReceiptYear == newYear) {
            return;
        }
        final java.util.List<String> names = new java.util.ArrayList<>(savedNames());
        names.removeIf(java.util.Objects::isNull);
        final int from = origReceiptYear;
        origReceiptYear = newYear;
        if (!names.isEmpty()) {
            new Thread(() -> ReceiptPages.moveYear(getApplicationContext(), names, from, newYear)).start();
        }
    }

    /** Die Namen der bereits gespeicherten Seiten in Reihenfolge (neue Seiten liefern {@code null}). */
    private java.util.List<String> savedNames() {
        java.util.List<String> names = new java.util.ArrayList<>(receiptPages.size());
        for (Page p : receiptPages) {
            names.add(p.savedName);
        }
        return names;
    }

    /** Nimmt eine Seite aus der Liste; gespeicherte Dateien werden erst beim Speichern gelöscht. */
    private void removeReceiptPage(Page page) {
        if (page.pending != null) {
            originalOf(page.pending).delete();
            page.pending.delete();
        } else if (page.savedName != null) {
            removedReceipts.add(page.savedName);
        }
        receiptPages.remove(page);
        updateNoteTagRows();
    }

    /**
     * Beleg vorhanden (Verbindung besteht), aber auf dem Server nicht auffindbar: fragt, ob der
     * verwaiste Verweis aus der Buchung entfernt werden soll. Diese Ansicht kennt keinen eigenen
     * Speichern-Schritt, darum wird das Entfernen sofort mit «Entfernen» geschrieben – über den
     * normalen {@link #update()}-Pfad, der die (exportierte) Buchung dabei auf „bearbeitet" setzt.
     */
    private void promptRemoveUnavailableReceipt(Page page) {
        if (!receiptPages.contains(page)) {
            return; // schon entfernt
        }
        new AppDialog(this)
                .setTitle(R.string.receipt_missing_title)
                .setMessage(R.string.receipt_missing_delete)
                .setPositiveButton(R.string.remove, (d, w) -> {
                    removeReceiptPage(page);
                    update();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Fragt direkt nach der Aufnahme, ob das Bild noch zugeschnitten/begradigt werden soll. */
    private void askEditReceipt(Page page) {
        new AppDialog(this)
                .setTitle(R.string.receipt_edit_title)
                .setMessage(R.string.receipt_edit_question)
                .setPositiveButton(R.string.receipt_edit_yes, (d, w) -> editReceipt(page))
                .setNegativeButton(R.string.receipt_edit_no, null)
                .show();
    }

    /**
     * Öffnet den Bild-Editor für eine Seite. Eine noch nicht gespeicherte wird direkt bearbeitet; bei einer
     * bereits gespeicherten holt {@link ReceiptSync#ensureLocal} Bild und Original bei Bedarf erst vom
     * Netzlaufwerk. Vor der ersten Bearbeitung entsteht die Sicherheitskopie {@code …_original.jpg}.
     */
    private void editReceipt(Page page) {
        if (page.pending != null && page.pending.exists()) {
            startReceiptEditor(page.pending, originalOf(page.pending), null);
            return;
        }
        if (page.savedName == null) {
            return;
        }
        final String file = page.savedName;
        final String originalName = NoteReceipt.originalName(file);
        final int year = receiptYear();
        // Gespeicherter Beleg: kann erst vom Server geholt werden – Statuszeile zeigen (kann dauern).
        receiptBanner.start(getString(R.string.receipt_loading_wait));
        new Thread(() -> {
            final ReceiptSync.Loaded loaded = ReceiptSync.ensureLocalWaiting(this, file, year, null);
            final java.io.File local = loaded.file;
            // Altbelege haben kein Original auf dem Server – dann dient der Beleg selbst als Vorlage.
            final java.io.File original =
                    local == null ? null : ReceiptSync.ensureLocal(this, originalName, year);
            post(() -> {
                receiptBanner.finishNow();
                if (local == null || !local.exists()) {
                    // Ohne Verbindung nur ein Hinweis; online, aber unauffindbar → Entfernen anbieten.
                    if (loaded.offline) {
                        Toast.makeText(this, R.string.receipt_offline, Toast.LENGTH_SHORT).show();
                    } else {
                        promptRemoveUnavailableReceipt(page);
                    }
                    return;
                }
                startReceiptEditor(local,
                        original != null && original.exists() ? original : Receipts.localFile(this, originalName),
                        file);
            });
        }).start();
    }

    /**
     * Startet den Bild-Editor. Gibt es bereits eine Sicherung, wurde dieser Beleg schon einmal bearbeitet –
     * dann wird gefragt, ob die bisherige Bearbeitung fortgesetzt oder wieder beim Original begonnen wird.
     * Die Sicherung selbst legt der Editor beim Übernehmen an; sie wird nie überschrieben.
     */
    private void startReceiptEditor(java.io.File target, java.io.File original, String savedName) {
        if (!original.exists()) {
            launchReceiptEditor(target, original, false, savedName);
            return;
        }
        new AppDialog(this)
                .setTitle(R.string.receipt_edit_again_title)
                .setMessage(R.string.receipt_edit_again_message)
                .setPositiveButton(R.string.receipt_edit_resume,
                        (d, w) -> launchReceiptEditor(target, original, false, savedName))
                .setNegativeButton(R.string.receipt_edit_from_original,
                        (d, w) -> launchReceiptEditor(target, original, true, savedName))
                .show();
    }

    private void launchReceiptEditor(java.io.File target, java.io.File backup, boolean fromBackup,
                                     String savedName) {
        editingSavedReceipt = savedName;
        String source = de.spahr.ausgaben.receipt.ReceiptEdit.sourceFor(fromBackup,
                target.getAbsolutePath(), backup.getAbsolutePath(), backup.exists());
        receiptEditLauncher.launch(new Intent(this, ReceiptEditActivity.class)
                .putExtra(ReceiptEditActivity.EXTRA_PATH, target.getAbsolutePath())
                .putExtra(ReceiptEditActivity.EXTRA_SOURCE, source)
                .putExtra(ReceiptEditActivity.EXTRA_BACKUP, backup.getAbsolutePath()));
    }

    /** Datei des unbearbeiteten Originals zu einem Beleg-Temp bzw. einer gespeicherten Datei. */
    private java.io.File originalOf(java.io.File file) {
        return new java.io.File(file.getParentFile(), NoteReceipt.originalName(file.getName()));
    }


    /**
     * Hängt den {@code BELEG:}-Tag an die (bereits aus freiem Text + GPS gebaute) Notiz an und finalisiert das
     * Bild. Bei {@code asNew} (Kopie/Neu) wird ein <b>bestehender</b> Beleg NICHT übernommen – nur ein neu
     * angehängtes Bild verlinkt. Danach {@code then} (der eigentliche Speichervorgang).
     */
    private void attachReceipt(Booking b, boolean asNew, Runnable then) {
        b.note = withReceiptTag(b.note, b.createdAt, asNew);
        then.run();
    }

    /**
     * Wie {@link #attachReceipt}, aber allein auf der Notiz – für die <b>Umbuchung</b>, die keine
     * {@link Booking} zum Füllen hat, sondern ihre Notiz als Text an beide Seiten weiterreicht.
     *
     * @param createdAt Zeitpunkt der Buchung; sein Jahr bestimmt den Ordner der Belege
     * @return die Notiz mit dem {@code BELEG:}-Tag, falls es Seiten gibt
     */
    private String withReceiptTag(String note, long createdAt, boolean asNew) {
        // Der Jahresordner der Belege folgt dem Buchungsdatum – er steckt nicht mehr im Dateinamen.
        final int year = yearFromMillis(createdAt);
        if (asNew) {
            // Kopie/Neu: bestehende Seiten gehören zur Vorlage und werden nicht übernommen.
            for (java.util.Iterator<Page> it = receiptPages.iterator(); it.hasNext(); ) {
                if (it.next().pending == null) {
                    it.remove();
                }
            }
            removedReceipts.clear();
        } else {
            for (String name : removedReceipts) {
                ReceiptPages.delete(this, name);
            }
            removedReceipts.clear();
        }
        // Die Basis stammt von der ersten bereits gespeicherten Seite – so bleibt die UUID der Buchung
        // erhalten, auch wenn genau diese Seite gerade gelöscht wurde.
        String base = null;
        for (Page p : receiptPages) {
            if (p.savedName != null) {
                base = NoteReceipt.baseOf(p.savedName);
                break;
            }
        }
        if (base == null) {
            base = NoteReceipt.newBase();
        }
        // Neuen Seiten die kleinste freie Nummer geben …
        java.util.List<String> taken = new java.util.ArrayList<>(savedNames());
        taken.removeIf(java.util.Objects::isNull);
        for (Page p : receiptPages) {
            if (p.pending == null) {
                continue;
            }
            String name = NoteReceipt.pageName(base, ReceiptPages.nextFreePage(taken),
                    p.isPdf() ? NoteReceipt.PDF : NoteReceipt.JPG);
            if (finalizeReceipt(p, name, year)) {
                taken.add(name);
            }
        }
        receiptPages.removeIf(p -> p.savedName == null);
        // … und danach lückenlos durchnummerieren, damit die Suche bei der ersten Lücke aufhören kann.
        java.util.List<String> names = savedNames();
        java.util.List<String> target = ReceiptPages.renumber(names);
        for (int i = 0; i < receiptPages.size(); i++) {
            ReceiptPages.rename(this, names.get(i), target.get(i), year);
            receiptPages.get(i).savedName = target.get(i);
        }
        // In die Notiz kommt nur die Basis (die UUID); die Seiten findet die App darüber selbst. Bei PDFs
        // steht dort der eigene Tag – daran erkennt das Laden später, welche Endung zu suchen ist.
        if (!receiptPages.isEmpty()) {
            String tag = NoteReceipt.tagOf(receiptPages.get(0).savedName);
            note = hasPdfPages() ? NoteReceipt.withPdfName(note, tag) : NoteReceipt.withFileName(note, tag);
            moveReceiptYear(year);
            ReceiptSync.syncPending(this);
        }
        return note;
    }

    /**
     * Benennt das Temp einer Seite auf ihren endgültigen Namen um und merkt sie zum Hochladen vor – zusammen
     * mit dem unbearbeiteten Original, falls die Aufnahme nachbearbeitet wurde.
     */
    private boolean finalizeReceipt(Page page, String file, int year) {
        if (!page.pending.renameTo(Receipts.localFile(this, file))) {
            return false;
        }
        Receipts.addPending(this, file, year);
        java.io.File original = originalOf(page.pending);
        if (original.exists() && original.renameTo(Receipts.localFile(this, NoteReceipt.originalName(file)))) {
            Receipts.addPending(this, NoteReceipt.originalName(file), year);
        }
        page.pending = null;
        page.savedName = file;
        return true;
    }

    private int yearFromMillis(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return c.get(Calendar.YEAR);
    }

    /** Die Namen der gespeicherten Seiten – die Reihenfolge im Betrachter. */
    private java.util.List<String> savedPageNames() {
        java.util.List<String> names = savedNames();
        names.removeIf(java.util.Objects::isNull);
        return names;
    }

    /**
     * Öffnet die Belegseiten im <b>eigenen</b> Betrachter, beginnend bei {@code index}. Eine fremde Foto-App
     * kam hier nicht in Frage: sie cacht auf den Dateinamen, und ein bearbeiteter Beleg behält seinen Namen –
     * angezeigt wurde dann die alte Fassung.
     */
    private void openReceiptViewer(int index) {
        java.util.List<String> names = savedPageNames();
        if (names.isEmpty()) {
            Toast.makeText(this, R.string.receipt_not_found, Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(this, ReceiptViewActivity.class)
                .putExtra(ReceiptViewActivity.EXTRA_FILES, names.toArray(new String[0]))
                .putExtra(ReceiptViewActivity.EXTRA_YEAR, receiptYear())
                .putExtra(ReceiptViewActivity.EXTRA_INDEX, Math.max(0, index)));
    }

    // ---- Aktualisieren (bestehende Buchung) ----

    private void update() {
        if (booking == null) {
            return;
        }
        if (notesOnly) {
            updateNotesOnly();
            return;
        }
        boolean nowTransfer = isTransferType();
        if (origIsTransfer && nowTransfer) {
            updateTransferInPlace();
        } else if (!origIsTransfer && !nowTransfer) {
            updateNormalInPlace();
        } else if (!origIsTransfer) {
            convertNormalToTransfer();
        } else {
            convertTransferToNormal();
        }
    }

    /**
     * Speichert an einer Wertpapier-Buchung <b>nur</b> Notiz, Stichwörter und Beleg. Betrag, Konten,
     * Datum und Umbuchungsfelder bleiben, wie sie aus der Datei kamen – sie sind in der Maske gesperrt,
     * und der Exporter ändert an so einer Transaktion später ebenfalls nur diese drei Angaben.
     *
     * <p>Zu prüfen gibt es nichts: es gibt kein Feld, in das sich ein ungültiger Wert schreiben ließe.</p>
     */
    private void updateNotesOnly() {
        booking.tags = bookingTags;
        booking.note = composeNoteForSave(false);
        attachReceipt(booking, false, () -> repository.updateNotesAndTags(booking, () -> {
            Toast.makeText(this, R.string.booking_updated, Toast.LENGTH_SHORT).show();
            finish();
        }));
    }

    private void updateNormalInPlace() {
        if (readValidFields(booking) == null) {
            return;
        }
        final List<SplitRowController.Part> parts = splitCtl.collectParts();
        booking.category = parts.isEmpty() ? "" : parts.get(0).category;
        booking.categoryIsIncome = parts.isEmpty() ? null : resolvePartType(parts.get(0));
        booking.isTransfer = false;
        booking.transferAccount = "";
        booking.transferGroup = "";
        booking.exported = switchExported.isChecked();
        // Ort nur ignorieren, wenn die Buchung vorher KEINE Ort-Verknüpfung hatte UND bereits exportiert ist.
        final boolean ignorePlace = !origPlaceManaged && booking.exported;
        final String place = selectedPlace();
        maybeAskCorrection(booking.payee, () -> {
            booking.createdAt = composeTimestamp();
            final List<BookingSplit> splits = parts.size() >= 2 ? toSplits(parts) : new ArrayList<>();
            Runnable done = () -> {
                Toast.makeText(this, R.string.booking_updated, Toast.LENGTH_SHORT).show();
                finish();
            };
            // Aktualisieren: gespeicherte GPS/Beleg behalten (Notiz aus freiem Text + gespeichertem GPS neu bauen).
            booking.note = composeNoteForSave(false);
            attachReceipt(booking, false, () -> {
                if (!ignorePlace) {
                    // Ort-verknüpfte Buchung: Ort-Journal per Ausgleichs-Bewegung nachziehen.
                    repository.updateBookingWithPlace(booking, place, splits, done);
                } else {
                    // Exportierte Buchung ohne Ort-Verknüpfung: Ort ignorieren, Ort-Journal unberührt lassen.
                    repository.updateSplitBooking(booking, splits, done);
                }
            });
        });
    }

    private void updateTransferInPlace() {
        final String from = textOf(editAccount).trim();
        final String to = textOf(editAccountTo).trim();
        // Unverändert übernommenes Gegenkonto, das die App nicht als Konto führt (ein Wertpapier des
        // Depots): keine Fehleingabe, sondern eine Buchung, an der nur Notiz, Stichwörter und Beleg zu
        // ändern sind. Ohne diesen Ausweg täte der Knopf gar nichts – die Prüfung unten schlüge fehl.
        if (isSecurityCounterpart(booking.transferAccount, booking.isTransfer)
                && to.equalsIgnoreCase(booking.transferAccount.trim())) {
            updateNotesOnly();
            return;
        }
        final Long cents = parseAmountToCents(textOf(editAmount));
        if (cents == null || cents <= 0) {
            Toast.makeText(this, R.string.error_amount, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isKnownAccount(from) || !isKnownAccount(to) || from.equalsIgnoreCase(to)) {
            Toast.makeText(this, R.string.error_transfer_accounts, Toast.LENGTH_SHORT).show();
            return;
        }
        final String note = composeNoteForSave(false);
        final String payee = textOf(editPayee).trim();
        final String fromPlace = selectedPlace();
        final String toPlace = selectedPlaceTo();
        // Export-Status aus dem Schalter übernehmen; updateTransferBooking überträgt ihn auf beide Seiten.
        booking.exported = switchExported.isChecked();
        maybeAskCorrection(payee, () -> {
            long ts = composeTimestamp();
            repository.updateTransferBooking(booking, from, to, cents, payee,
                withReceiptTag(note, ts, false), bookingTags, ts, fromPlace, toPlace, () -> {
                    Toast.makeText(this, R.string.booking_updated, Toast.LENGTH_SHORT).show();
                    finish();
                });
        });
    }

    private void convertNormalToTransfer() {
        final String from = textOf(editAccount).trim();
        final String to = textOf(editAccountTo).trim();
        final Long cents = parseAmountToCents(textOf(editAmount));
        if (cents == null || cents <= 0) {
            Toast.makeText(this, R.string.error_amount, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isKnownAccount(from) || !isKnownAccount(to) || from.equalsIgnoreCase(to)) {
            Toast.makeText(this, R.string.error_transfer_accounts, Toast.LENGTH_SHORT).show();
            return;
        }
        final String note = composeNoteForSave(true);
        final String payee = textOf(editPayee).trim();
        final String fromPlace = selectedPlace();
        final String toPlace = selectedPlaceTo();
        final long oldId = booking.id;
        maybeAskCorrection(payee, () -> {
            long ts = composeTimestamp();
            repository.deleteBooking(oldId, null);
            // Umwandeln heißt löschen und neu anlegen – der Beleg gehört aber weiter zu dieser Buchung
            // (asNew = false), sonst bliebe er nach dem Wechsel der Buchungsart herrenlos liegen.
            repository.saveTransferBooking(from, to, cents, payee,
                    withReceiptTag(note, ts, false), bookingTags, ts, fromPlace, toPlace, () -> {
                Toast.makeText(this, R.string.booking_updated, Toast.LENGTH_SHORT).show();
                finish();
            });
        });
    }

    private void convertTransferToNormal() {
        Booking nb = readValidFields(new Booking());
        if (nb == null) {
            return;
        }
        nb.exported = false;
        final List<SplitRowController.Part> parts = splitCtl.collectParts();
        nb.category = parts.isEmpty() ? "" : parts.get(0).category;
        nb.categoryIsIncome = parts.isEmpty() ? null : resolvePartType(parts.get(0));
        final String place = textOf(editPlace);
        final String group = origTransferGroup;
        final long oldId = booking.id;
        maybeAskCorrection(nb.payee, () -> {
            nb.createdAt = composeTimestamp();
            repository.deleteTransfer(group, oldId, null);
            // Standardort ist jetzt ein echter Ort → keine Sonderbehandlung; „ohne Ort" filtert das Repository.
            final String fp = place;
            Runnable done = () -> {
                Toast.makeText(this, R.string.booking_updated, Toast.LENGTH_SHORT).show();
                finish();
            };
            nb.note = composeNoteForSave(true);
            // Wie beim Umwandeln in die andere Richtung: der Beleg bleibt an der Buchung.
            attachReceipt(nb, false, () -> {
                if (parts.size() >= 2) {
                    repository.saveSplitBooking(nb, toSplits(parts), fp, done);
                } else {
                    repository.saveBookingWithPlace(nb, fp, done);
                }
            });
        });
    }

    private void confirmDelete() {
        if (booking == null) {
            return;
        }
        if (securityTxFound) {
            confirmDeleteSecurity();
            return;
        }
        AppDialog.destructive(this)
                .setTitle(R.string.delete_confirm_title)
                .setMessage(R.string.delete_confirm_message)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    // Umbuchungen (zwei Seiten + Gruppe) lassen sich so nicht sauber wiederherstellen –
                    // dort bleibt es wie bisher beim Löschen ohne „Rückgängig".
                    final Bundle undo = origIsTransfer ? null : undoBundle();
                    Runnable done = () -> {
                        if (undo != null) {
                            Intent res = new Intent();
                            res.putExtra(EXTRA_UNDO_BOOKING, undo);
                            setResult(RESULT_OK, res);   // die Liste bietet „Rückgängig" an
                        } else {
                            Toast.makeText(this, R.string.booking_deleted, Toast.LENGTH_SHORT).show();
                        }
                        finish();
                    };
                    if (origIsTransfer) {
                        repository.deleteTransfer(origTransferGroup, booking.id, done);
                    } else {
                        repository.deleteBooking(booking.id, done);
                    }
                })
                .show();
    }

    /**
     * Eine Wertpapier-Buchung löschen — sie nimmt die Depot-Bewegung mit.
     *
     * <p>Steht sie schon in der KMyMoney-Datei, sagt die Rückfrage das ausdrücklich: beim nächsten Export
     * verschwindet dort die ganze Transaktion samt Stückzahl und Kurs. Das ist kein Nebeneffekt, sondern
     * der Zweck — und deshalb gehört es vor den Klick, nicht danach.</p>
     *
     * <p><b>Kein «Rückgängig».</b> Der Undo-Weg legt allein die Buchung wieder an; die Depot-Bewegung
     * käme nicht zurück, und die Vormerkung für die Datei bliebe stehen. Ein halbes Zurück wäre
     * schlimmer als keines.</p>
     */
    private void confirmDeleteSecurity() {
        AppDialog.destructive(this)
                .setTitle(R.string.delete_security_confirm_title)
                .setMessage(booking.exported || booking.edited
                        ? R.string.delete_security_confirm_message
                        : R.string.delete_security_pending_message)
                .setPositiveButton(R.string.delete, (d, w) -> repository.deleteSecurityBooking(booking,
                        () -> {
                            Toast.makeText(this, R.string.booking_deleted, Toast.LENGTH_SHORT).show();
                            finish();
                        }))
                .show();
    }

    /** Alles, was zum Wiederanlegen der gelöschten Buchung nötig ist (Werte wie gespeichert). */
    private Bundle undoBundle() {
        Bundle b = new Bundle();
        b.putString("payee", booking.payee);
        b.putString("account", booking.account);
        b.putString("category", booking.category);
        b.putString("note", booking.note);
        b.putLong("amount", booking.amountCents);
        b.putBoolean("income", booking.isIncome);
        b.putLong("created", booking.createdAt);
        b.putBoolean("exported", booking.exported);
        // Status „bearbeitet" samt Signatur der exportierten Fassung mitnehmen – sonst käme die Buchung
        // als vermeintlich neue zurück und stünde beim nächsten Übertragen doppelt in der Datei.
        b.putBoolean("edited", booking.edited);
        b.putString("origAccount", booking.origAccount);
        b.putLong("origSignedCents", booking.origSignedCents);
        b.putLong("origCreatedAt", booking.origCreatedAt);
        // Ort nur, wenn die Buchung ort-verknüpft war (importierte haben keine Verknüpfung).
        b.putString("place", booking.placeManaged ? booking.place : "");
        b.putBoolean("placeManaged", booking.placeManaged);
        List<SplitRowController.Part> parts = splitCtl.collectParts();
        if (parts.size() >= 2) {
            ArrayList<String> cats = new ArrayList<>();
            long[] amounts = new long[parts.size()];
            for (int i = 0; i < parts.size(); i++) {
                cats.add(parts.get(i).category);
                amounts[i] = parts.get(i).cents;
            }
            b.putStringArrayList("splitCats", cats);
            b.putLongArray("splitAmounts", amounts);
        }
        return b;
    }

    /** Validiert die gemeinsamen Felder (ohne Kategorie) und schreibt sie in {@code target}. */
    private Booking readValidFields(Booking target) {
        Long cents = parseAmountToCents(textOf(editAmount));
        if (cents == null || cents <= 0) {
            Toast.makeText(this, R.string.error_amount, Toast.LENGTH_SHORT).show();
            return null;
        }
        String payee = textOf(editPayee).trim();
        if (payee.isEmpty()) {
            Toast.makeText(this, R.string.error_payee, Toast.LENGTH_SHORT).show();
            return null;
        }
        String account = textOf(editAccount).trim();
        if (!isKnownAccount(account)) {
            Toast.makeText(this, R.string.error_account, Toast.LENGTH_SHORT).show();
            return null;
        }
        target.amountCents = cents;
        target.isIncome = toggleType.getCheckedButtonId() == R.id.btnIncome;
        target.payee = payee;
        target.account = account;
        target.note = textOf(editNote).trim();
        target.tags = bookingTags;
        target.createdAt = composeTimestamp();
        return target;
    }

       private boolean isKnownAccount(String account) {
        return account != null && knownAccountNames.contains(account.trim().toLowerCase(Locale.ROOT));
    }

    private List<BookingSplit> toSplits(List<SplitRowController.Part> parts) {
        List<BookingSplit> out = new ArrayList<>();
        for (SplitRowController.Part p : parts) {
            out.add(new BookingSplit(0, p.category, p.cents, resolvePartType(p)));
        }
        return out;
    }

    /**
     * Kategorietyp eines Teils: aus der Auswahlliste angetippt/vorbelegt, sonst Rückfall auf den
     * Einnahme/Ausgabe-Umschalter der Buchung (z. B. bei frei getipptem Kategorietext).
     */
    private boolean resolvePartType(SplitRowController.Part p) {
        return p.categoryIsIncome != null
                ? p.categoryIsIncome
                : toggleType.getCheckedButtonId() == R.id.btnIncome;
    }

    private long composeTimestamp() {
        Calendar time = Calendar.getInstance();
        if (booking != null) {
            time.setTimeInMillis(booking.createdAt);
        }
        Calendar c = (Calendar) selectedDate.clone();
        c.set(Calendar.HOUR_OF_DAY, time.get(Calendar.HOUR_OF_DAY));
        c.set(Calendar.MINUTE, time.get(Calendar.MINUTE));
        c.set(Calendar.SECOND, time.get(Calendar.SECOND));
        c.set(Calendar.MILLISECOND, time.get(Calendar.MILLISECOND));
        return c.getTimeInMillis();
    }

    private String formatCents(long cents) {
        return de.spahr.ausgaben.settings.MoneyFormat.plain(cents);
    }

    /** Betrag in Cent; akzeptiert auch eine kleine Rechnung wie {@code 12,50+3,20} (nur {@code + *}). */
    private Long parseAmountToCents(String raw) {
        return de.spahr.ausgaben.settings.AmountExpression.toCents(raw);
    }

    /**
     * Bindet ein Betragsfeld an die gemeinsame Rechentastatur: Eingabefilter, System-Tastatur unterdrücken,
     * bei Fokus die Tastatur zeigen (arbeitet auf dem fokussierten Feld) und beim Verlassen/„OK" auswerten.
     * {@code layout} darf {@code null} sein (Teilbeträge zeigen keinen Feld-Fehler).
     */
    void wireCalcField(final TextInputEditText field, final TextInputLayout layout) {
        wireCalcField(field, layout, null);
    }

    /**
     * @param onSettled läuft, nachdem das Feld verlassen und die Rechnung ausgewertet ist – erst dann
     *                  steht der Betrag endgültig fest ({@code null} = nichts zu tun)
     */
    void wireCalcField(final TextInputEditText field, final TextInputLayout layout,
                       final Runnable onSettled) {
        AmountField.prepareCalc(field);
        field.setShowSoftInputOnFocus(false);
        field.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && !readOnly) {
                calcKeyboard.attachTo(field);
                calcKeyboard.setOnOk(valid -> {
                    if (valid) {
                        if (layout != null) {
                            layout.setError(null);
                        }
                        field.clearFocus();   // blendet die Tastatur aus (Fokus-Listener)
                    } else if (layout != null) {
                        layout.setError(getString(R.string.error_amount_calc));
                    }
                });
                calcKeyboard.setVisibility(View.VISIBLE);
                CalcKeyboardView.hideSystemKeyboard(field);   // ggf. offene System-Tastatur des Vorfelds schließen
            } else {
                calcKeyboard.setVisibility(View.GONE);
                evaluateCalcField(field, layout);   // „=": beim Verlassen auswerten und ersetzen
                if (onSettled != null) {
                    onSettled.run();
                }
            }
        });
        if (layout != null) {
            field.addTextChangedListener(new SimpleWatcher(() -> layout.setError(null)));
        }
    }

    /** Wertet die Rechnung im Feld aus und ersetzt sie durch das Ergebnis; ungültig → Fehlermeldung (falls Layout). */
    private void evaluateCalcField(TextInputEditText field, TextInputLayout layout) {
        if (readOnly) {
            return;
        }
        String raw = textOf(field).trim();
        if (raw.isEmpty()) {
            if (layout != null) {
                layout.setError(null);
            }
            return;
        }
        Long cents = parseAmountToCents(raw);
        if (cents == null || cents < 0) {
            if (layout != null) {
                layout.setError(getString(R.string.error_amount_calc));
            }
            return;
        }
        if (layout != null) {
            layout.setError(null);
        }
        String result = formatCents(cents);
        if (!result.equals(raw)) {
            field.setText(result);   // Feldinhalt durch das Ergebnis ersetzen
        }
    }

    private String textOf(android.widget.EditText e) {
        return e.getText() == null ? "" : e.getText().toString();
    }
}
