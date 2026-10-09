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
import de.spahr.ausgaben.db.CsvModeGuard;
import de.spahr.ausgaben.db.PayeeCorrection;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.location.LocationTagger;
import de.spahr.ausgaben.receipt.NoteReceipt;
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
    /** true = bereits exportierte Buchung im CSV-Modus: erzwingt {@link #readOnly}, s. {@link CsvModeGuard}. */
    private boolean csvLocked;
    /**
     * true = in KMyMoney abgeglichene Buchung: nur zur Ansicht, erzwingt {@link #readOnly}, s.
     * {@link de.spahr.ausgaben.db.ReconciledGuard}. Anders als bei {@link #csvLocked} bleibt auch
     * „Neue Buchung" mit diesen Daten weg – die Maske ist dann eine reine Ansicht.
     */
    private boolean reconciledLocked;
    /**
     * true = kein .kmy-Schreibziel: keine Splitbuchungen, s. {@link CsvModeGuard#splitBlocked}. Eine
     * Quelle mit mehreren Kategorien (Vorlage, Planung, Alias, alte Splitbuchung) belegt dann gar keine
     * Kategorie vor – der Nutzer wählt selbst eine.
     */
    private boolean splitLocked;

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
    private android.view.View rowReceipt;
    private android.widget.TextView textReceipt;
    private android.widget.ImageButton btnReceipt;   // btnNoteMap ist ein eigenes Feld
    private android.widget.LinearLayout receiptPagesView;
    private android.widget.LinearLayout receiptPageIcons;
    /** Die Belegseiten dieser Buchung, siehe {@link ReceiptPagesController}. */
    private ReceiptPagesController receipts;
    private boolean receiptEnabled;
    /** Die Standort-Zeile, siehe {@link GpsRowController}. */
    private GpsRowController gps;
    /** Die Stichwort-Zeile, siehe {@link TagsRowController}. */
    private TagsRowController tags;
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
    /** Für eine Buchung ohne Umbuchung schon nach einer verknüpften Depot-Bewegung gefragt? */
    private boolean linkedTxAsked;
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

    /** Verwaltet die dynamische Kategorie-/Teilbetrag-Liste (Splitbuchung). */
    private SplitRowController splitCtl;

    private final Calendar selectedDate = Calendar.getInstance();

    /** Was die Standort-Zeile von dieser Maske braucht. */
    private GpsRowController.Host gpsHost() {
        return new GpsRowController.Host() {
            @Override
            public boolean isReadOnly() {
                return readOnly;
            }

            @Override
            public boolean isTransferType() {
                return BookingEditActivity.this.isTransferType();
            }

            @Override
            public boolean isGpsEnabled() {
                return settings.isGpsEnabled();
            }

            @Override
            public void onGpsChanged() {
                updateNoteTagRows();
            }

        };
    }

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
        // Standort- und Stichwort-Zeile; der Standort registriert seine Karten-Auswahl selbst (vor STARTED).
        gps = new GpsRowController(this, gpsHost());
        tags = new TagsRowController(this, repository, editPayee, () -> readOnly);
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
        splitLocked = CsvModeGuard.splitBlocked(settings.isKmyMode());
        splitCtl = new SplitRowController(splitContainer, editAmount, getLayoutInflater(),
                readOnly, splitLocked, this::updateSaveEnabled);
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
            tags.setKnownTags(names);
            updateNoteTagRows();
            // Die Liste kommt aus der Datenbank und damit womöglich später als der vorbelegte
            // Empfänger – dann ist sein Vorspann noch nirgends angekommen. Also noch einmal fragen.
            tags.forgetPayee();
            tags.refreshForPayee();
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
                setupPlaceOptions(editPlace, Ui.text(editAccount).trim(), false);
            } else {
                setupPlaceDropdown(Ui.text(editAccount).trim());
            }
            applyTypeVisibility();
        });
        // Steht der Empfänger fest, richten sich die Kategorien nach ihm: Vorspann der Auswahlliste und
        // Vorbelegung der ersten Zeile.
        PickerBehaviour.onCommitted(editPayee, value -> {
            refreshPayeeCategories();
            tags.refreshForPayee();
        });
        // Bei einer Umbuchung folgt der Nach-Ort dem Nach-Konto.
        PickerBehaviour.onCommitted(editAccountTo, value -> {
            if (isTransferType()) {
                setupPlaceOptions(editPlaceTo, Ui.text(editAccountTo).trim(), false);
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

        rowReceipt = findViewById(R.id.rowReceipt);
        textReceipt = findViewById(R.id.textReceipt);
        btnReceipt = findViewById(R.id.btnReceipt);
        receiptPagesView = findViewById(R.id.receiptPages);
        receiptPageIcons = findViewById(R.id.receiptPageIcons);
        // Belegseiten: registriert seine Launcher selbst – deshalb hier, noch in onCreate (vor STARTED).
        receipts = new ReceiptPagesController(this, receiptHost, rowReceipt, receiptPagesView,
                receiptPageIcons, receiptBanner);
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
                editAmount.setText(de.spahr.ausgaben.settings.MoneyFormat.plain(voiceAmount));
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
        if (receipts != null) {
            receipts.clear(); // nicht gespeicherte Beleg-Temps aufräumen
        }
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
        if (locationTagger == null || booking != null || gps.editedByUser()) {
            return;
        }
        String coords = locationTagger.currentCoordinates();
        if (coords == null) {
            return;
        }
        gps.setCoords(coords);
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
        double[] hier = readOnly ? null : de.spahr.ausgaben.location.Geo.parse(gps.coords());
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
            a.fromAccount = Ui.text(editAccount).trim();
            a.toAccount = Ui.text(editAccountTo).trim();
            a.fromPlace = selectedPlace();
            a.toPlace = selectedPlaceTo();
        } else {
            a.account = Ui.text(editAccount).trim();
            a.place = selectedPlace();
            List<SplitRowController.Part> parts = splitCtl.collectParts();
            String c1 = parts.size() > 0 ? parts.get(0).category : "";
            String c2 = parts.size() > 1 ? parts.get(1).category : "";
            // Mit jeder Kategorie ihre Seite: „für Einnahmen vorgesehen" (catIncome…) heißt nicht
            // „Einnahmekategorie" – eine Erstattung führt eine Ausgabekategorie.
            Boolean s1 = c1.isEmpty() ? null : seiteVon(parts.get(0));
            Boolean s2 = c2.isEmpty() ? null : seiteVon(parts.get(1));
            if (toggleType.getCheckedButtonId() == R.id.btnIncome) {
                a.catIncome1 = c1;
                a.catIncome2 = c2;
                a.catIncome1IsIncome = s1;
                a.catIncome2IsIncome = s2;
            } else {
                a.catExpense1 = c1;
                a.catExpense2 = c2;
                a.catExpense1IsIncome = s1;
                a.catExpense2IsIncome = s2;
            }
        }
        // Standort der Buchung (aus der GPS-Zeile) übernehmen → Alias per GPS auffindbar (Betrag-only).
        double[] ll = de.spahr.ausgaben.location.Geo.parse(gps.coords());
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
        // Die Seite steht im Alias neben der Kategorie; ein Alias aus der Zeit davor kennt sie nicht,
        // dann sagt es die Auswahlliste.
        Boolean s1 = income ? activeAlias.catIncome1IsIncome : activeAlias.catExpense1IsIncome;
        Boolean s2 = income ? activeAlias.catIncome2IsIncome : activeAlias.catExpense2IsIncome;
        splitCtl.setSuppressEvents(true);
        splitCtl.clear();
        boolean hasC1 = c1 != null && !c1.trim().isEmpty();
        boolean hasC2 = c2 != null && !c2.trim().isEmpty();
        // Ohne .kmy-Schreibziel ergäben zwei Kategorien eine Splitbuchung – dann keine vorbelegen.
        if (!(splitLocked && hasC1 && hasC2)) {
            if (hasC1) {
                splitCtl.addRow(c1, null, s1 != null ? s1 : splitCtl.sideOf(c1));
            }
            if (hasC2) {
                splitCtl.addRow(c2, null, s2 != null ? s2 : splitCtl.sideOf(c2));
            }
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
        gps.setCoords(null);
        receipts.clear();
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
        // Gesperrt heißt hier nur: nicht mehr änderbar/löschbar (siehe unten bei btnUpdate/btnDelete) –
        // die Daten bleiben als Vorlage für „Neue Buchung" nutzbar, deshalb kein genereller readOnly.
        csvLocked = CsvModeGuard.lockedForEdit(b, settings.isKmyMode());
        // In KMyMoney abgeglichen: gleichgültig, wie die Maske geöffnet wurde, bleibt sie Ansicht.
        reconciledLocked = de.spahr.ausgaben.db.ReconciledGuard.locked(b);
        if (reconciledLocked) {
            readOnly = true;
            findViewById(R.id.textReconciledHint).setVisibility(View.VISIBLE);
        }
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
        switchExported.setEnabled(!b.edited && !csvLocked);
        switchExported.setText(b.edited ? R.string.edited_locked : R.string.mark_exported);
        // Gesperrte Buchung: nicht mehr änderbar/löschbar, aber „Neue Buchung" mit diesen Daten bleibt.
        btnUpdate.setVisibility(csvLocked ? View.GONE : View.VISIBLE);
        btnDelete.setVisibility(csvLocked ? View.GONE : View.VISIBLE);
        emphasizeUpdate();
        // Bestehende Buchung: GPS/Beleg aus der Notiz in die zwei Zeilen (bleiben beim Aktualisieren erhalten).
        gps.setFromNote(b.note);
        receipts.load(b.note, ReceiptPagesController.yearOf(b.createdAt));
        populateFrom(b, null);
        updateNoteTagRows();
        if (readOnly) {
            applyReadOnly();
        }
        if (reconciledLocked) {
            // Die Kategoriezeilen sind schon aufgebaut – der Schalter beim Anlegen kam dafür zu früh.
            splitCtl.lockRows();
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
        if (!booking.isTransfer) {
            // Die in der App erfasste Dividende ist eine Einnahme, keine Umbuchung (siehe
            // SecurityTx#toMoneyBooking) – ihr sieht man die Bewegung nicht an. Sie gehört allein über
            // die gespeicherte Verknüpfung dazu; danach fragen, einmal.
            if (!linkedTxAsked) {
                linkedTxAsked = true;
                repository.getSecurityTxForBooking(booking, tx -> {
                    if (tx != null && !isFinishing() && !notesOnly) {
                        lockAsSecurityBooking();
                        securityTxFound = true;
                        btnDelete.setVisibility(View.VISIBLE);
                    }
                });
            }
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
        lockAsSecurityBooking();
        // Der Löschknopf kommt erst zurück, wenn feststeht, dass wirklich eine Depot-Bewegung dazugehört;
        // bis dahin bleibt er weg. Ein Knopf, der sich gleich wieder verabschiedet, wäre schlimmer.
        repository.getSecurityTxForBooking(booking, tx -> {
            if (tx != null && !isFinishing()) {
                securityTxFound = true;
                btnDelete.setVisibility(View.VISIBLE);
            }
        });
    }

    /**
     * Sperrt alles außer Notiz, Stichwort und Beleg. Löschen darf sein, Ändern nicht – der Unterschied
     * ist entscheidend: beim Löschen verschwindet die ganze Transaktion samt Stückzahl und Kurs, es kann
     * nichts halb stehenbleiben.
     */
    private void lockAsSecurityBooking() {
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
        // Kategorien und Teilbeträge stehen an der Bewegung (Ertrag, Steuer) – hier nur ansehen.
        splitCtl.lockRows();
        btnDelete.setVisibility(View.GONE);
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
        // „Überspringen" bewirkt nur eine Weiterstell-Vormerkung, keine Buchung – ohne Schreibziel kmy
        // hätte der Knopf keinerlei Wirkung (die Vormerkung würde nie exportiert).
        if (st != null && settings.isKmyMode()) {
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
        // Ohne Schreibziel kmy bleibt die Buchung selbst normal gespeichert (per CSV exportierbar) –
        // nur die Weiterstell-Vormerkung entfällt, sie würde ohnehin nie in die .kmy geschrieben.
        if (bookedSchedule == null || !settings.isKmyMode()) {
            bookedSchedule = null;
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
        gps.setFromNote(booking.note);
        receipts.load(booking.note, ReceiptPagesController.yearOf(booking.createdAt));
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
        if (reconciledLocked) {
            return; // in KMyMoney abgeglichen: es gibt kein Bearbeiten, also auch keinen Stift
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
        gps.setCoords(null);
        receipts.clear();
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
        tags.set(b.tags);

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
            editAmount.setText(de.spahr.ausgaben.settings.MoneyFormat.plain(total));
            applyTypeVisibility();
            // Von-/Nach-Ort aus beiden Seiten der Umbuchung vorbelegen.
            if (b.transferGroup != null && !b.transferGroup.isEmpty()) {
                repository.getTransferGroup(b.transferGroup, pair -> {
                    for (Booking side : pair) {
                        if (side.isIncome) {
                            String acc = Ui.text(editAccountTo).trim();
                            setupPlaceOptions(editPlaceTo, acc, false);
                            editPlaceTo.setText(templatePlace(side.place, acc), false);
                        } else {
                            String acc = Ui.text(editAccount).trim();
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
        if (splitLocked && !readOnly && splits != null && splits.size() > 1) {
            // Ohne .kmy-Schreibziel keine Splitbuchung: Aus mehreren Kategorien wird keine vorbelegt,
            // der Gesamtbetrag bleibt. Die reine Ansicht zeigt die Teile dagegen weiterhin.
            splits = null;
            singleCategory = "";
        }
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
                splitCtl.addRow(s.category, de.spahr.ausgaben.settings.MoneyFormat.plain(part), s.categoryIsIncome);
            }
        } else if (!singleCategory.isEmpty()) {
            splitCtl.addRow(singleCategory, de.spahr.ausgaben.settings.MoneyFormat.plain(total), singleCategoryIsIncome);
        }
        splitCtl.setSuppressEvents(false);
        splitCtl.ensureTrailingRow();
        // Die Teile können nach der Sperre einer Depot-Buchung eintreffen (beides läuft nebenher).
        if (notesOnly) {
            splitCtl.lockRows();
        }
        // Aus einer echten Buchung oder Planung geladen? Dann bleibt die Kategorie, wie sie ist. Aus
        // einer Vorlage der Spracherfassung gehört sie zum gefundenen Empfänger – und weicht einem
        // anderen, falls die Automatik danebenlag.
        if (keepLoadedCategories) {
            splitCtl.lockCategories();
        } else {
            markCategorySource();
        }
        editAmount.setText(de.spahr.ausgaben.settings.MoneyFormat.plain(total));
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
            setupPlaceOptions(editPlace, Ui.text(editAccount).trim(), true);
            setupPlaceOptions(editPlaceTo, Ui.text(editAccountTo).trim(), true);
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
        if (readOnly || !Ui.text(editPayee).trim().isEmpty()) {
            return;
        }
        double[] hier = de.spahr.ausgaben.location.Geo.parse(gps.coords());
        Long cents = parseAmountToCents(Ui.text(editAmount));
        if (hier == null || cents == null || cents <= 0) {
            return;
        }
        String type = currentVoiceType();
        String key = cents + "|" + type + "|" + gps.coords();
        if (key.equals(payeeAmountKey)) {
            return;
        }
        payeeAmountKey = key;
        repository.suggestPayeeByAmount(hier[0], hier[1], cents, type, name -> {
            // Die Antwort kommt später; inzwischen kann der Empfänger von Hand gefüllt sein.
            if (name == null || name.isEmpty() || !key.equals(payeeAmountKey)
                    || !Ui.text(editPayee).trim().isEmpty()) {
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
        categorySourceKey = Ui.text(editPayee).trim().toLowerCase(Locale.ROOT) + "|" + isIncomeType();
    }

    private void refreshPayeeCategories() {
        if (readOnly || isTransferType()) {
            return;
        }
        String payee = Ui.text(editPayee).trim();
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
                splitCtl.replaceAutoCategories(cats.isEmpty() ? null : cats.get(0).category,
                        cats.isEmpty() ? null : cats.get(0).isIncome);
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
            String from = Ui.text(editAccount).trim();
            String to = Ui.text(editAccountTo).trim();
            Long cents = parseAmountToCents(Ui.text(editAmount));
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
            String place = Ui.text(placeField).trim();
            return !place.isEmpty() && !place.equals(PlacesStore.NO_PLACE);
        }
        return hasPlaces(Ui.text(accountField));
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
        String cur = Ui.text(field).trim();
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
        String sel = Ui.text(editPlaceTo);
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
        final String place = Ui.text(editPlace);
        maybeAskCorrection(b.payee, () -> maybeDateConfirm(() -> {
            b.createdAt = composeTimestamp();
            persistNew(b, place, parts);
        }));
    }

    private void saveTransferNew() {
        final String from = Ui.text(editAccount).trim();
        final String to = Ui.text(editAccountTo).trim();
        final Long cents = parseAmountToCents(Ui.text(editAmount));
        if (cents == null || cents <= 0) {
            Toast.makeText(this, R.string.error_amount, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isKnownAccount(from) || !isKnownAccount(to) || from.equalsIgnoreCase(to)) {
            Toast.makeText(this, R.string.error_transfer_accounts, Toast.LENGTH_SHORT).show();
            return;
        }
        final String note = composeNoteForSave(true);
        final String payee = Ui.text(editPayee).trim();
        final String fromPlace = selectedPlace();
        final String toPlace = selectedPlaceTo();
        maybeAskCorrection(payee, () -> maybeDateConfirm(() -> {
            long ts = composeTimestamp();
            // Der Beleg wird erst hier festgeschrieben – bis zur Bestätigung ist nichts gespeichert.
            // Beide Seiten bekommen dieselbe Notiz und damit denselben BELEG:-Tag – und dieselben
            // Stichwörter, denn in der .kmy-Datei ist die Umbuchung eine einzige Transaktion.
            repository.saveTransferBooking(from, to, cents, payee, receipts.withReceiptTag(note, ts, true),
                tags.tags(), ts, fromPlace, toPlace, () -> {
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
        String sel = Ui.text(editPlace);
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
        // Ohne btnUpdate (gesperrte Buchung im CSV-Modus) ist „Neue Buchung" die einzige Aktion und
        // verdient deshalb die auffällige Farbe statt der sonst zweitrangigen Umrandung.
        MaterialButton primary = csvLocked ? btnSaveNew : btnUpdate;
        MaterialButton secondary = csvLocked ? btnUpdate : btnSaveNew;

        primary.setBackgroundTintList(accentList);
        primary.setTextColor(getColor(R.color.white));
        primary.setStrokeWidth(0);

        secondary.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                android.graphics.Color.TRANSPARENT));
        secondary.setTextColor(accent);
        secondary.setStrokeColor(accentList);
        secondary.setStrokeWidth(Math.round(getResources().getDisplayMetrics().density));
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

    /** Aktualisiert die drei Ausgabezeilen (GPS, Stichwörter, Beleg) je nach Ansicht-/Bearbeiten-Modus. */
    private void updateNoteTagRows() {
        if (receipts == null) {
            return; // Views noch nicht gebunden
        }
        // GPS-Zeile. Hier läuft jede Änderung des Buchungs-Standorts zusammen – neue Buchung, geladene
        // Buchung, Kartenwahl –, deshalb hängt der Vorspann der Empfängerliste an dieser einen Stelle.
        refreshNearbyPayees();
        gps.update();
        tags.update();
        // Beleg-Kopfzeile + eine Zeile je Seite
        if (readOnly) {
            rowReceipt.setVisibility(receipts.isEmpty() ? View.GONE : View.VISIBLE);
            textReceipt.setText(getString(R.string.receipt_row_label, receipts.countText()));
            btnReceipt.setVisibility(View.GONE);
        } else if (receiptEnabled) {
            // Auch bei einer Umbuchung: der Tag steht in der gemeinsamen Notiz, also zeigen beide Seiten
            // denselben Beleg.
            rowReceipt.setVisibility(View.VISIBLE);
            textReceipt.setText(getString(R.string.receipt_row_label, receipts.isEmpty()
                    ? getString(R.string.receipt_none)
                    : receipts.countText()));
            btnReceipt.setVisibility(View.VISIBLE);
            btnReceipt.setImageResource(android.R.drawable.ic_menu_camera);
            btnReceipt.setOnClickListener(v -> receipts.showSourceDialog());
        } else {
            rowReceipt.setVisibility(View.GONE);
        }
        receipts.fill();
    }



    /** Freier Text + (je nach Kopie/Update) GPS-Tag. Der BELEG:-Tag kommt in {@link #attachReceipt}. */
    private String composeNoteForSave(boolean asNew) {
        String free = Ui.text(editNote).trim();
        String coords;
        if (asNew) {
            // Neu/Vorlage (booking == null): der Zeilenwert ist bereits die aktuelle Position.
            // „Als neue speichern" aus einer bestehenden Buchung: frische Position vom Tagger holen –
            // außer der Nutzer hat den Standort manuell auf der Karte gewählt (dann gilt dieser).
            coords = (booking == null || gps.editedByUser()) ? gps.coords()
                    : (locationTagger != null ? locationTagger.currentCoordinates() : null);
        } else {
            coords = gps.coords();
        }
        if (coords != null && !coords.trim().isEmpty()) {
            free = free.isEmpty() ? "GPS: " + coords : free + " GPS: " + coords;
        }
        return free;
    }

    // ---- Beleg (siehe ReceiptPagesController) ----

    /**
     * Hängt den {@code BELEG:}-Tag an die (bereits aus freiem Text + GPS gebaute) Notiz an und finalisiert das
     * Bild. Bei {@code asNew} (Kopie/Neu) wird ein <b>bestehender</b> Beleg NICHT übernommen – nur ein neu
     * angehängtes Bild verlinkt. Danach {@code then} (der eigentliche Speichervorgang).
     */
    private void attachReceipt(Booking b, boolean asNew, Runnable then) {
        b.note = receipts.withReceiptTag(b.note, b.createdAt, asNew);
        then.run();
    }

    /** Was der Beleg-Controller von dieser Maske braucht. */
    private final ReceiptPagesController.Host receiptHost = new ReceiptPagesController.Host() {
        @Override
        public boolean isReadOnly() {
            return readOnly;
        }

        @Override
        public int receiptYear() {
            return selectedDate.get(Calendar.YEAR);
        }

        @Override
        public boolean hasBooking() {
            return booking != null;
        }

        @Override
        public void onReceiptPagesChanged() {
            updateNoteTagRows();
        }

        @Override
        public void saveAfterRemovingMissing() {
            update();
        }
    };

    // ---- Aktualisieren (bestehende Buchung) ----

    private void update() {
        if (booking == null || csvLocked || reconciledLocked) {
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
        booking.tags = tags.tags();
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
        final String from = Ui.text(editAccount).trim();
        final String to = Ui.text(editAccountTo).trim();
        // Unverändert übernommenes Gegenkonto, das die App nicht als Konto führt (ein Wertpapier des
        // Depots): keine Fehleingabe, sondern eine Buchung, an der nur Notiz, Stichwörter und Beleg zu
        // ändern sind. Ohne diesen Ausweg täte der Knopf gar nichts – die Prüfung unten schlüge fehl.
        if (isSecurityCounterpart(booking.transferAccount, booking.isTransfer)
                && to.equalsIgnoreCase(booking.transferAccount.trim())) {
            updateNotesOnly();
            return;
        }
        final Long cents = parseAmountToCents(Ui.text(editAmount));
        if (cents == null || cents <= 0) {
            Toast.makeText(this, R.string.error_amount, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isKnownAccount(from) || !isKnownAccount(to) || from.equalsIgnoreCase(to)) {
            Toast.makeText(this, R.string.error_transfer_accounts, Toast.LENGTH_SHORT).show();
            return;
        }
        final String note = composeNoteForSave(false);
        final String payee = Ui.text(editPayee).trim();
        final String fromPlace = selectedPlace();
        final String toPlace = selectedPlaceTo();
        // Export-Status aus dem Schalter übernehmen; updateTransferBooking überträgt ihn auf beide Seiten.
        booking.exported = switchExported.isChecked();
        maybeAskCorrection(payee, () -> {
            long ts = composeTimestamp();
            repository.updateTransferBooking(booking, from, to, cents, payee,
                receipts.withReceiptTag(note, ts, false), tags.tags(), ts, fromPlace, toPlace, () -> {
                    Toast.makeText(this, R.string.booking_updated, Toast.LENGTH_SHORT).show();
                    finish();
                });
        });
    }

    private void convertNormalToTransfer() {
        final String from = Ui.text(editAccount).trim();
        final String to = Ui.text(editAccountTo).trim();
        final Long cents = parseAmountToCents(Ui.text(editAmount));
        if (cents == null || cents <= 0) {
            Toast.makeText(this, R.string.error_amount, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isKnownAccount(from) || !isKnownAccount(to) || from.equalsIgnoreCase(to)) {
            Toast.makeText(this, R.string.error_transfer_accounts, Toast.LENGTH_SHORT).show();
            return;
        }
        final String note = composeNoteForSave(true);
        final String payee = Ui.text(editPayee).trim();
        final String fromPlace = selectedPlace();
        final String toPlace = selectedPlaceTo();
        final long oldId = booking.id;
        maybeAskCorrection(payee, () -> {
            long ts = composeTimestamp();
            repository.deleteBooking(oldId, null);
            // Umwandeln heißt löschen und neu anlegen – der Beleg gehört aber weiter zu dieser Buchung
            // (asNew = false), sonst bliebe er nach dem Wechsel der Buchungsart herrenlos liegen.
            repository.saveTransferBooking(from, to, cents, payee,
                    receipts.withReceiptTag(note, ts, false), tags.tags(), ts, fromPlace, toPlace, () -> {
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
        final String place = Ui.text(editPlace);
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
        if (booking == null || csvLocked || reconciledLocked) {
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
        Long cents = parseAmountToCents(Ui.text(editAmount));
        if (cents == null || cents <= 0) {
            Toast.makeText(this, R.string.error_amount, Toast.LENGTH_SHORT).show();
            return null;
        }
        // Der Empfänger darf leer bleiben – wie in KMyMoney, wo er nie Pflicht war. Hier stand bis 2.0
        // eine Abweisung mit R.string.error_payee; sie war die einzige Stelle der App, die einen leeren
        // Empfänger nicht vertrug (die Umbuchung nebenan ließ ihn seit jeher frei, der Import schreibt
        // ihn durch). Was in den Listen anstelle des Namens steht, entscheidet BookingLabel.title(…).
        String payee = Ui.text(editPayee).trim();
        String account = Ui.text(editAccount).trim();
        if (!isKnownAccount(account)) {
            Toast.makeText(this, kontoMeldung(account), Toast.LENGTH_SHORT).show();
            return null;
        }
        target.amountCents = cents;
        target.isIncome = toggleType.getCheckedButtonId() == R.id.btnIncome;
        target.payee = payee;
        target.account = account;
        target.note = Ui.text(editNote).trim();
        target.tags = tags.tags();
        target.createdAt = composeTimestamp();
        return target;
    }

       private boolean isKnownAccount(String account) {
        return account != null && knownAccountNames.contains(account.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Warum das Feld abgewiesen wurde – drei Lagen, die bis 2.1 dieselbe Meldung bekamen.
     *
     * <p>Die mittlere war die falsche: Wer „Bargeld" eintippt, obwohl es dieses Konto nicht gibt, las
     * „Bitte ein Konto eingeben" – eine Bitte um etwas, das schon dasteht. Die dritte Lage trifft jede
     * frische Installation, in der beim Einrichten das Feld „Standardkonto" leer blieb: Dann gibt es
     * überhaupt kein Konto, und keine Eingabe in dieses Feld kann je durchkommen. Dort hilft nur der
     * Hinweis, wo Konten entstehen.</p>
     */
    private String kontoMeldung(String account) {
        if (knownAccountNames.isEmpty()) {
            return getString(R.string.error_account_none);
        }
        return account == null || account.isEmpty()
                ? getString(R.string.error_account)
                : getString(R.string.error_account_unknown, account);
    }

    private List<BookingSplit> toSplits(List<SplitRowController.Part> parts) {
        List<BookingSplit> out = new ArrayList<>();
        for (SplitRowController.Part p : parts) {
            out.add(new BookingSplit(0, p.category, p.cents, resolvePartType(p)));
        }
        return out;
    }

    /**
     * Die Seite der Kategorie eines Teils, soweit sie feststeht: von der Zeile gemerkt (aus der Liste
     * gewählt, getippt und in der Liste gefunden, oder mit der Kategorie von ihrer Quelle übernommen),
     * sonst aus der Auswahlliste nachgeschlagen. {@code null} = nicht zu ermitteln.
     */
    private Boolean seiteVon(SplitRowController.Part p) {
        return p.categoryIsIncome != null ? p.categoryIsIncome : splitCtl.sideOf(p.category);
    }

    /**
     * Kategorietyp eines Teils für das Speichern. Erst wenn weder die Zeile noch die Auswahlliste die
     * Seite kennen – die Liste ist noch nicht geladen –, entscheidet der Einnahme/Ausgabe-Umschalter
     * der Buchung. Frei eingeben lässt sich eine Kategorie nicht; sie stammt immer aus der Liste.
     */
    private boolean resolvePartType(SplitRowController.Part p) {
        Boolean seite = seiteVon(p);
        return seite != null ? seite : toggleType.getCheckedButtonId() == R.id.btnIncome;
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
        String raw = Ui.text(field).trim();
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
        String result = de.spahr.ausgaben.settings.MoneyFormat.plain(cents);
        if (!result.equals(raw)) {
            field.setText(result);   // Feldinhalt durch das Ergebnis ersetzen
        }
    }

}
