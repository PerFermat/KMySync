package de.spahr.ausgaben.ui;

import de.spahr.ausgaben.net.RemotePath;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.BookingSplit;
import de.spahr.ausgaben.db.PlaceBalance;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.export.CsvImporter;
import de.spahr.ausgaben.export.ExportCoordinator;
import de.spahr.ausgaben.export.KmyDocument;
import de.spahr.ausgaben.export.KmyExportCoordinator;
import de.spahr.ausgaben.export.KmyImporter;
import de.spahr.ausgaben.net.RemoteStorage;
import de.spahr.ausgaben.settings.PlacesStore;
import de.spahr.ausgaben.settings.SettingsStore;
import de.spahr.ausgaben.voice.VoiceRecognizer;

public class MainActivity extends LocalizedActivity implements HostedDialog.Host {

    /** Schlüssel und Angaben der Dialoge dieser Maske – siehe {@link HostedDialog}. */
    private static final String DLG_CSV_PICK = "dlg_csvPick";
    private static final String DLG_NUMBER_ENTRY = "dlg_numberEntry";

    /**
     * Der Stand der stillen Zifferneingabe: eingetippter Betrag und Stelle im Empfänger-Rundlauf.
     *
     * <p>Die Felder dieses Dialogs entstehen im Code und tragen keine ids — das Fenstersystem kann sie
     * deshalb nicht selbst wiederherstellen. Ohne diese drei Werte begänne man nach jeder Drehung von
     * vorn.</p>
     */
    private String numberEntryAmount = "";
    private int numberEntryPick;
    private boolean numberEntryTapped;
    private static final String STATE_NUMBER_AMOUNT = "s_numberAmount";
    private static final String STATE_NUMBER_PICK = "s_numberPick";
    private static final String STATE_NUMBER_TAPPED = "s_numberTapped";
    private static final String STATE_SEARCH_QUERY = "s_searchQuery";
    private static final String STATE_SEARCH_OPEN = "s_searchOpen";

    @Override
    protected void onDestroy() {
        // Ein entprellter Filterlauf, der nach dem Ende der Maske feuert, arbeitet auf Ansichten, die
        // es nicht mehr gibt. Ui.post fängt das nicht: Der Handler gehört der Suchleiste, nicht ihr.
        if (searchBar != null) {
            searchBar.detach();
        }
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(android.os.Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(STATE_NUMBER_AMOUNT, numberEntryAmount);
        out.putInt(STATE_NUMBER_PICK, numberEntryPick);
        out.putBoolean(STATE_NUMBER_TAPPED, numberEntryTapped);
        // Die Kontenschublade rettet ihre Suche nicht – dort ist der Begriff zwei Wörter. Hier hat man
        // womöglich gerade eine Buchung von 2019 eingekreist; die Drehung dürfte das nicht wegwerfen.
        out.putString(STATE_SEARCH_QUERY, searchQuery);
        out.putBoolean(STATE_SEARCH_OPEN, searchBar != null && searchBar.istOffen());
    }

    /**
     * Den Stand der Zifferneingabe zurücklesen — <b>in {@code onCreate}</b> und nicht in
     * {@code onRestoreInstanceState}.
     *
     * <p>Die Reihenfolge entscheidet: Das Fenstersystem stellt den Dialog beim Wechsel nach
     * {@code onStart} wieder her und ruft dabei {@code buildNumberEntry}; {@code onRestoreInstanceState}
     * kommt erst danach. Der Dialog läse dann noch den leeren Anfangswert — was genau der Fehler war,
     * den ein Test hier zutage gefördert hat.</p>
     */
    private void restoreNumberEntryState(android.os.Bundle in) {
        if (in == null) {
            return;
        }
        numberEntryAmount = in.getString(STATE_NUMBER_AMOUNT, "");
        numberEntryPick = in.getInt(STATE_NUMBER_PICK, 0);
        numberEntryTapped = in.getBoolean(STATE_NUMBER_TAPPED, false);
    }
    private static final String ARG_CSV_FOLDER = "a_csvFolder";
    private static final String ARG_CSV_FOLDERS = "a_csvFolders";
    private static final String ARG_CSV_FILES = "a_csvFiles";

    @Override
    public android.app.Dialog buildDialog(String key, Bundle args) {
        if (DLG_CSV_PICK.equals(key)) {
            return buildCsvPick(args);
        }
        return DLG_NUMBER_ENTRY.equals(key) ? buildNumberEntry() : null;
    }

    @Override
    public void onDialogCancelled(String key, Bundle args) {
        if (DLG_NUMBER_ENTRY.equals(key)) {
            // Weggetippt heißt verworfen – beim nächsten Öffnen soll nicht der alte Betrag dastehen.
            vergissZifferneingabe();
        }
        // Der Datei-Browser darf ebenso weggetippt werden; es folgt nichts daraus.
    }


    private Repository repository;
    private SettingsStore settings;
    private PlacesStore placesStore;

    private BookingAdapter adapter;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefresh;
    private TextView textBalance;
    private TextView textSaldoLabel;
    private ImportBanner importBanner;

    /** Uhr-Buchung wurde im Hintergrund angelegt → Liste live aktualisieren. */
    private final android.content.BroadcastReceiver bookingsChangedReceiver =
            new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context, Intent intent) {
                    refreshBookings();
                }
            };

    /**
     * Kontoname und „Filter aktiv (n)" – eigene Ansichten statt der Beschriftung der ActionBar, damit
     * links davon die Lupe Platz hat (siehe {@code activity_main.xml}).
     */
    private android.widget.TextView toolbarTitle;
    private android.widget.TextView toolbarSubtitle;
    /** Die Live-Suche in der Titelzeile; sie hält den Suchtext und schaltet Name/Feld um. */
    private BookingSearchBar searchBar;

    private List<Booking> allBookings = new ArrayList<>();
    private java.util.Map<Long, List<BookingSplit>> splitsByBooking = new java.util.HashMap<>();
    private java.util.Map<String, Long> placeBalances = new java.util.LinkedHashMap<>();
    private long totalBalance = 0;
    private long depotValueCents = 0;
    private long allPlaceEntrySum = 0;
    private long filteredSum = 0;
    private final List<SaldoView> saldoViews = new ArrayList<>();
    private int saldoIndex = 0;

    /**
     * Der Suchtext aus der <b>Live-Suche in der Titelzeile</b> – bewusst neben {@link #filterPayee}
     * und nicht an seiner Stelle: Beide gelten zusammen, damit man innerhalb eines gesetzten Filters
     * weitersuchen kann. Gehalten wird er hier und nicht nur im Feld, weil das Feld eingeklappt wird,
     * ohne daß die Suche endet.
     */
    private String searchQuery = "";

    private String filterPayee = "";
    private String filterCategory = "";
    private boolean filterCategoryIsMain = false;
    /** Typ der gefilterten Kategorie (Einnahme/Ausgabe), {@code null} = kein Typ gewählt ("Alle"). */
    private Boolean filterCategoryIsIncome = null;
    /** Gefiltertes Stichwort (leer = alle); die Buchung muß es tragen. */
    private String filterTag = "";
    /** Die in KMyMoney vorhandenen Stichwörter; leer = das Filterfeld erscheint gar nicht. */
    private List<String> knownTagNames = new ArrayList<>();
    private Long filterAmountFrom = null;
    private Long filterAmountTo = null;
    private Long filterDateFrom = null;
    private Long filterDateTo = null;
    /** Umkreis in Metern um {@link #filterCenter}; 0 = aus (siehe
     * {@link de.spahr.ausgaben.location.RadiusFilter}). */
    private int filterRadiusM = 0;
    /** Eigene Position „lat, lon" im Moment des Anwendens – eingefroren, damit die Liste ruhig bleibt. */
    private double[] filterCenter = null;
    /** Empfänger (klein) → gelernte Standorte seines Alias; Rückfall für Buchungen ohne eigenes GPS. */
    private final java.util.Map<String, java.util.List<double[]>> aliasPoints = new java.util.HashMap<>();

    private List<String> catExpense = new ArrayList<>();
    private List<String> catIncome = new ArrayList<>();
    /** Kategorie-Pfad → Typ (globaler Rückfall für Buchungszeilen ohne eigenen Typ, siehe Booking#categoryIsIncome). */
    private java.util.Map<String, Boolean> categoryTypes = new java.util.HashMap<>();

    /** Gewähltes Konto (leer = „Alle Konten"). Standard beim Start = Standardkonto. */
    private String selectedAccount = "";
    /** true, wenn Depotdaten importiert wurden (steuert das „Depot"-Menü). */
    private boolean hasDepot = false;
    private boolean accountInitialized = false;
    /** true, sobald das On-Boarding in dieser App-Sitzung einmal automatisch gezeigt wurde. */
    private boolean onboardingShown = false;
    private long selectedAccountBalance = 0;
    /** Gewählte Kontengruppe (0 = alle Konten) und ihre Beschriftung. */
    private long selectedGroup = 0;
    private String selectedGroupLabel = "";
    /** Konten der gewählten Gruppe, kleingeschrieben; leer = keine Einschränkung. */
    private final java.util.Set<String> groupAccounts = new java.util.HashSet<>();
    private long groupBalance = 0;
    /** Depotname (klein) → Wert der gehaltenen Wertpapiere; für die Summe einer Kontengruppe. */
    private final java.util.Map<String, Long> depotValues = new java.util.HashMap<>();
    /** Kopf der Schublade: Gruppenauswahl, Kontenverwaltung, Suchfeld. */
    private AccountDrawerHeader drawerHeader;
    /** Aktuell in der App vorhandene Konten (für „Alle Konten aktualisieren"). */
    private final List<String> appAccounts = new ArrayList<>();
    /** Alle bereits importierten Konten inkl. geschlossener – zum Ausblenden im Import-Auswahldialog. */
    private final List<String> importedAccounts = new ArrayList<>();
    /** Bereits importierte Depots – um sie im Import-Auswahldialog auszublenden. */
    private final List<String> appDepots = new ArrayList<>();

    private androidx.drawerlayout.widget.DrawerLayout drawerLayout;
    private androidx.recyclerview.widget.RecyclerView accountList;
    private AccountDrawerAdapter accountAdapter;

    /** Eine Sicht in der Saldo-Leiste. key: ACCOUNT | TOTAL | PLACE:<name> | FILTERED */
    private static final class SaldoView {
        final String key;
        final String label;
        final long cents;

        SaldoView(String key, String label, long cents) {
            this.key = key;
            this.label = label;
            this.cents = cents;
        }
    }

    /** Extra: dieses Konto beim Start auswählen (z. B. aus der Depot-Schublade). */
    public static final String EXTRA_SELECT_ACCOUNT = "select_account";
    /** Extra: nach dem Start sofort den Export/Sync ausführen (z. B. aus dem Depot-Menü). */
    public static final String EXTRA_RUN_EXPORT = "run_export";
    /** Extra: Launcher-Shortcut „Neue Ausgabe" – öffnet direkt den Buchungs-Editor (siehe xml/shortcuts.xml). */
    public static final String EXTRA_NEW_BOOKING = "de.spahr.ausgaben.NEW_BOOKING";
    /** Extra: aus dem Homescreen-Widget angeforderte Aktion (Werte {@code WIDGET_ACTION_*}). */
    public static final String EXTRA_WIDGET_ACTION = "widget_action";
    public static final String WIDGET_ACTION_NEW = "new";
    public static final String WIDGET_ACTION_VOICE = "voice";
    public static final String WIDGET_ACTION_DIGITS = "digits";
    public static final String WIDGET_ACTION_BALANCES = "balances";

    public static final String VIEW_TOTAL = "TOTAL";
    /** Summe der Konten der gewählten Kontengruppe (nur bei aktiver Gruppe). */
    public static final String VIEW_GROUP_TOTAL = "GROUP_TOTAL";
    /** Gesamt ohne Depot = nur Konten (heutiges „Gesamt"-Verhalten). */
    public static final String VIEW_TOTAL_NODEPOT = "TOTAL_NODEPOT";
    /** Nur Depotwert. */
    public static final String VIEW_DEPOT_TOTAL = "DEPOT_TOTAL";
    public static final String VIEW_NOPLACE = "NOPLACE";
    public static final String VIEW_FILTERED = "FILTERED";
    public static final String VIEW_PLACE_PREFIX = "PLACE:";
    public static final String VIEW_ACCOUNT_PREFIX = "ACCOUNT:";

    private ActivityResultLauncher<Uri> exportTreeLauncher;
    private ActivityResultLauncher<String[]> importLauncher;
    /** Speicherdialog für die ZIP-Datei mit den Belegen der gefilterten Buchungen. */
    private ActivityResultLauncher<String> receiptZipLauncher;
    /** Was beim Antippen des Menüpunkts feststand – der Speicherdialog kommt ja erst danach. */
    private java.util.List<de.spahr.ausgaben.receipt.ReceiptExportJobs.Job> receiptExportJobs;
    /** Antwort auf die Rückfrage vor dem Speicherdialog: fehlende Belege nachladen? */
    private boolean receiptExportDownload = true;
    /** Wieviele der vorgemerkten Belege schon auf dem Gerät liegen – sie stehen in der Liste vorn. */
    private int receiptExportLocal;
    /** Der Dateiname, unter dem gespeichert wird – gemerkt für einen späteren zweiten Anlauf. */
    private String receiptExportName = "";
    /** Gesetzt, solange ein Beleg-Export läuft; darüber bricht das Banner ihn ab. */
    private java.util.concurrent.atomic.AtomicBoolean receiptExportCancel;
    private ActivityResultLauncher<Intent> voiceLauncher;
    private VoiceEntryController voiceEntry;
    private ActivityResultLauncher<Intent> editLauncher;
    private ActivityResultLauncher<String> locationPermissionLauncher;

    /** Aktueller Standort für die Betrag-only-Auflösung (rein lokal, nur Koordinaten). */
    private de.spahr.ausgaben.location.LocationTagger locationTagger;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Vor allem anderen: der Dialog der Zifferneingabe wird gleich mit der Activity wiederhergestellt
        // und liest diese Werte (siehe restoreNumberEntryState).
        restoreNumberEntryState(savedInstanceState);
        setContentView(R.layout.activity_main);

        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        // Kein Logo mehr in der Toolbar; das kMyMoney-Logo sitzt im Kopf der Konten-Schublade.
        // Titel und Untertitel stellt das Kind-Layout der Toolbar dar, nicht die ActionBar selbst –
        // nur so kann die Lupe links vom Kontonamen stehen (siehe activity_main.xml). Ohne das
        // stünde hier zusätzlich der App-Name aus dem Manifest.
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        toolbarTitle = findViewById(R.id.toolbarTitle);
        toolbarSubtitle = findViewById(R.id.toolbarSubtitle);
        // Bei offenem Suchfeld schließt Zurück erst das Feld, statt die Maske zu verlassen. Der
        // Rückruf schaltet ihn scharf — auch dann, wenn nicht die Zurück-Taste, sondern die Lupe oder
        // die Tastatur das Feld geschlossen hat.
        final androidx.activity.OnBackPressedCallback zurueckSchliesstSuche =
                new androidx.activity.OnBackPressedCallback(false) {
                    @Override
                    public void handleOnBackPressed() {
                        searchBar.collapse();
                    }
                };
        getOnBackPressedDispatcher().addCallback(this, zurueckSchliesstSuche);

        searchBar = new BookingSearchBar(findViewById(R.id.bookingSearchIcon), toolbarTitle,
                findViewById(R.id.bookingSearch),
                () -> {
                    searchQuery = searchBar.query();
                    applyFilter();
                },
                zurueckSchliesstSuche::setEnabled);
        if (savedInstanceState != null) {
            searchQuery = savedInstanceState.getString(STATE_SEARCH_QUERY, "");
            searchBar.restore(searchQuery, savedInstanceState.getBoolean(STATE_SEARCH_OPEN, false));
        }

        repository = new Repository(this);
        settings = new SettingsStore(this);
        placesStore = new PlacesStore(this);

        // Von der Depot-Schublade aus kann ein Konto vorgewählt werden.
        String preselect = getIntent().getStringExtra(EXTRA_SELECT_ACCOUNT);
        if (preselect != null) {
            selectedAccount = preselect;
            accountInitialized = true;
        }

        // Einmalige Migration: früher globale Orte + Ort-Bewegungen dem Standardkonto zuordnen.
        placesStore.migrateLegacyGlobalPlaces(settings.getDefaultAccount());
        repository.migratePlaceEntryAccounts(settings.getDefaultAccount());

        // Navigations-Schublade (Konten) links
        drawerLayout = findViewById(R.id.drawerLayout);
        androidx.appcompat.app.ActionBarDrawerToggle toggle =
                new androidx.appcompat.app.ActionBarDrawerToggle(this, drawerLayout, toolbar,
                        R.string.drawer_open, R.string.drawer_close);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();
        toggle.getDrawerArrowDrawable().setColor(getColor(R.color.white));

        accountList = findViewById(R.id.accountList);
        accountList.setLayoutManager(new LinearLayoutManager(this));
        accountAdapter = new AccountDrawerAdapter(getString(R.string.account_all),
                new AccountDrawerAdapter.Listener() {
                    @Override
                    public void onSelect(String account, boolean isAll) {
                        selectAccount(account);
                        drawerHeader.clearSearch(); // die Suche ist flüchtig
                        drawerLayout.closeDrawers();
                    }

                    @Override
                    public void onImport(String account, boolean isAll) {
                        // Schublade offen lassen; Import-Dialog/Datei-Browser erscheint darüber.
                        onImportRequested(account, isAll);
                    }

                    @Override
                    public void onDepotSelect(String depot) {
                        drawerLayout.closeDrawers();
                        // Ein Depot ist eine Auswahl wie ein Konto: die Depot-Ansicht ersetzt diese
                        // Kontoansicht, statt sich darüberzulegen. Darum hier beenden – so bleibt
                        // immer nur eine Ledger-Ansicht übrig und Zurück beendet die App.
                        Intent i = new Intent(MainActivity.this, DepotActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        i.putExtra(DepotActivity.EXTRA_DEPOT, depot);
                        startActivity(i);
                        finish();
                        overridePendingTransition(0, 0);
                    }

                    @Override
                    public void onDepotImport(String depot) {
                        // Schublade offen lassen; Bestätigungsdialog erscheint darüber.
                        reimportDepot(depot);
                    }
                });
        accountList.setAdapter(accountAdapter);
        drawerHeader = new AccountDrawerHeader(this, repository, settings, accountAdapter,
                this::onGroupChanged);
        // Schublade zugeschoben (auch per Wischen) beendet eine laufende Kontensuche.
        drawerLayout.addDrawerListener(new androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerClosed(@NonNull View drawerView) {
                drawerHeader.clearSearch();
            }
        });
        findViewById(R.id.addAccount).setOnClickListener(v -> onAddAccountClicked());

        textBalance = findViewById(R.id.textBalance);
        textSaldoLabel = findViewById(R.id.textSaldoLabel);
        // Laufschrift für lange Kontonamen (bei großer Schrift), damit der Saldo daneben Platz behält.
        textSaldoLabel.setSelected(true);
        findViewById(R.id.saldoHeader).setOnClickListener(v -> cycleSaldo());

        RecyclerView recycler = findViewById(R.id.recyclerBookings);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        // Haarfeine Trennlinien zwischen den Buchungen (kMyMoney-Ledger-Optik).
        com.google.android.material.divider.MaterialDividerItemDecoration divider =
                new com.google.android.material.divider.MaterialDividerItemDecoration(
                        this, com.google.android.material.divider.MaterialDividerItemDecoration.VERTICAL);
        divider.setDividerColor(getColor(R.color.list_divider));
        divider.setDividerThickness(Math.max(1, Math.round(getResources().getDisplayMetrics().density)));
        divider.setDividerInsetStart(0);
        divider.setDividerInsetEnd(0);
        divider.setLastItemDecorated(false);
        recycler.addItemDecoration(divider);
        adapter = new BookingAdapter();
        adapter.setListener(new BookingAdapter.Listener() {
            @Override
            public void onClick(Booking b) {
                // Kurzer Druck: Buchung nur ansehen (ohne Änderungsmöglichkeit). Über denselben
                // Launcher wie das Bearbeiten, weil die Ansicht ihren Stift als Ergebnis zurückmeldet.
                Intent i = new Intent(MainActivity.this, BookingEditActivity.class);
                i.putExtra(BookingEditActivity.EXTRA_BOOKING_ID, b.id);
                i.putExtra(BookingEditActivity.EXTRA_READ_ONLY, true);
                editLauncher.launch(i);
            }

            @Override
            public void onLongClick(Booking b) {
                // Langer Druck: Buchung bearbeiten. Über den Launcher, damit nach einem Löschen
                // „Rückgängig" angeboten werden kann.
                Intent i = new Intent(MainActivity.this, BookingEditActivity.class);
                i.putExtra(BookingEditActivity.EXTRA_BOOKING_ID, b.id);
                editLauncher.launch(i);
            }
        });
        recycler.setAdapter(adapter);

        // Wischgeste nach unten: in der kmy-Variante das aktuelle Konto neu aus der .kmy einlesen,
        // sonst nur die DB neu anzeigen (in der CSV-Variante nur übers Kontenmenü aktualisierbar).
        ShimmerView importShimmer = findViewById(R.id.importShimmer);
        importShimmer.setColors(getColor(R.color.import_banner_bg), getColor(R.color.import_banner_shimmer));
        importBanner = new ImportBanner(findViewById(R.id.importBanner), importShimmer,
                findViewById(R.id.importStatus), findViewById(R.id.importPercent));
        swipeRefresh = findViewById(R.id.swipeRefresh);
        swipeRefresh.setOnRefreshListener(() -> {
            swipeRefresh.setRefreshing(false);
            // „Alle Konten" (leerer selectedAccount) zieht alles nach: Konten, Depots und Planungen.
            if (settings.isKmyMode() && settings.hasRemoteConfig() && !settings.getKmyPath().isEmpty()) {
                runKmyImport(selectedAccount.isEmpty() ? null : selectedAccount);
            } else {
                refreshBookings();
            }
        });

        ExtendedFloatingActionButton fab = findViewById(R.id.fabNew);
        fab.setOnClickListener(v -> {
            Intent i = new Intent(this, BookingEditActivity.class);
            // Ist ein einzelnes Konto in der Ansicht, die neue Buchung auf jeden Fall dort anlegen.
            if (!selectedAccount.isEmpty()) {
                i.putExtra(BookingEditActivity.EXTRA_PRESET_ACCOUNT, selectedAccount);
            }
            startActivity(i);
        });
        // Langer Druck → Buchung per Sprache anlegen (z. B. „Frisör 20€").
        fab.setOnLongClickListener(v -> {
            voiceEntry.startVoiceEntry();
            return true;
        });

        // Ziffern-Symbol: stille Betrag-Eingabe (ohne Mikrofon) → Auflösung per Standort.
        findViewById(R.id.fabNumber).setOnClickListener(v -> showNumberEntry());

        // Standort für die Betrag-only-Auflösung (nur Koordinaten, rein lokal – wie im Editor).
        locationTagger = new de.spahr.ausgaben.location.LocationTagger(this);
        locationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        locationTagger.start();
                    }
                });

        exportTreeLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(), uri -> {
                    if (uri != null) {
                        getContentResolver().takePersistableUriPermission(uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        settings.setLocalExportTree(uri.toString());
                        runExport();
                    }
                });
        importLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri != null) {
                        doImportLocal(uri);
                    }
                });
        receiptZipLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/zip"), uri -> {
                    if (uri != null) {
                        // Dauerhaftes Schreibrecht erbitten: Nur damit läßt sich ein abgebrochener
                        // Lauf später in dieselbe Datei wiederholen. Ob der Dateianbieter das gewährt,
                        // steht ihm frei – ohne das Recht entfällt nur die Wiederaufnahme.
                        try {
                            getContentResolver().takePersistableUriPermission(
                                    uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        } catch (Exception ignored) {
                            // kein dauerhaftes Recht – der Lauf selbst geht trotzdem
                        }
                        writeReceiptZip(uri);
                    }
                });
        // Buchungs-Editor: liefert nach dem Löschen die Daten für „Rückgängig" zurück – und aus der
        // Ansicht den Wunsch, dieselbe Buchung jetzt zu bearbeiten (Stift in der Toolbar).
        editLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() != RESULT_OK) {
                        return;
                    }
                    if (openEditorFromView(result.getData())) {
                        return;
                    }
                    showUndoDelete(result.getData());
                });
        voiceLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        java.util.ArrayList<String> spoken = result.getData()
                                .getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
                        if (spoken != null && !spoken.isEmpty()) {
                            voiceEntry.handleVoiceResult(VoiceRecognizer.pickBestSpoken(spoken));
                            return;
                        }
                    }
                    Toast.makeText(this, R.string.voice_not_understood, Toast.LENGTH_SHORT).show();
                });
        voiceEntry = new VoiceEntryController(this, repository, settings, locationTagger, voiceLauncher,
                locationPermissionLauncher, () -> selectedAccount, this::visibleAccounts);

        FloatingActionButton fabScrollTop = findViewById(R.id.fabScrollTop);
        fabScrollTop.setOnClickListener(v -> ScrollToTop.rolle(recycler));
        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                // Sichtbar, sobald die Liste nach unten gescrollt wurde.
                if (rv.canScrollVertically(-1)) {
                    fabScrollTop.show();
                } else {
                    fabScrollTop.hide();
                }
            }
        });
    }

    /**
     * Hat die Ansicht den Stift zurückgemeldet, öffnet das denselben Editor im Bearbeiten-Modus.
     * Wieder über den Launcher, damit ein Löschen von dort aus weiterhin „Rückgängig" anbietet.
     *
     * @return true, wenn es um das Bearbeiten ging – dann ist nichts zu löschen gewesen.
     */
    private boolean openEditorFromView(Intent data) {
        long id = data == null ? -1 : data.getLongExtra(BookingEditActivity.EXTRA_REQUEST_EDIT, -1);
        if (id < 0) {
            return false;
        }
        Intent i = new Intent(this, BookingEditActivity.class);
        i.putExtra(BookingEditActivity.EXTRA_BOOKING_ID, id);
        editLauncher.launch(i);
        return true;
    }

    /**
     * Zeigt nach dem Löschen „Rückgängig" an und legt die Buchung auf Wunsch wieder an. Sie bekommt dabei
     * eine neue id, und im Ort-Journal bleiben Löschung und Wiederanlage als Bewegungen stehen – so
     * arbeitet das Journal ohnehin (die Historie bleibt erhalten, der Saldo stimmt).
     */
    private void showUndoDelete(Intent data) {
        final Bundle u = data == null ? null : data.getBundleExtra(BookingEditActivity.EXTRA_UNDO_BOOKING);
        if (u == null) {
            return;
        }
        com.google.android.material.snackbar.Snackbar
                .make(findViewById(android.R.id.content), R.string.booking_deleted,
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .setAction(R.string.undo, v -> restoreBooking(u))
                .show();
    }

    private void restoreBooking(Bundle u) {
        Booking b = new Booking();
        b.payee = u.getString("payee", "");
        b.account = u.getString("account", "");
        b.category = u.getString("category", "");
        b.note = u.getString("note", "");
        b.amountCents = u.getLong("amount");
        b.isIncome = u.getBoolean("income");
        b.createdAt = u.getLong("created");
        b.exported = u.getBoolean("exported");
        // Status „bearbeitet" samt Signatur der exportierten Fassung wiederherstellen.
        b.edited = u.getBoolean("edited");
        b.origAccount = u.getString("origAccount", "");
        b.origSignedCents = u.getLong("origSignedCents");
        b.origCreatedAt = u.getLong("origCreatedAt");
        final String place = u.getString("place", "");
        final Runnable done = () -> {
            refreshBookings();
            Toast.makeText(this, R.string.booking_restored, Toast.LENGTH_SHORT).show();
        };
        java.util.ArrayList<String> cats = u.getStringArrayList("splitCats");
        long[] amounts = u.getLongArray("splitAmounts");
        if (cats != null && amounts != null && cats.size() >= 2 && amounts.length == cats.size()) {
            List<de.spahr.ausgaben.db.BookingSplit> parts = new ArrayList<>();
            for (int i = 0; i < cats.size(); i++) {
                parts.add(new de.spahr.ausgaben.db.BookingSplit(0, cats.get(i), amounts[i]));
            }
            repository.saveSplitBooking(b, parts, place, done);
        } else {
            repository.saveBookingWithPlace(b, place, done);
        }
    }

    /**
     * Launcher-Shortcut „Neue Ausgabe": Der Shortcut zielt auf diese (exportierte) Activity – die
     * BookingEditActivity ist nicht exportiert und dürfte vom Launcher nicht direkt gestartet werden.
     * Das Extra wird nach dem Öffnen entfernt, damit der Editor bei jedem Zurück nicht erneut aufgeht.
     */
    private void handleShortcutIntent() {
        if (getIntent() == null || !getIntent().getBooleanExtra(EXTRA_NEW_BOOKING, false)) {
            return;
        }
        getIntent().removeExtra(EXTRA_NEW_BOOKING);
        Intent i = new Intent(this, BookingEditActivity.class);
        if (!selectedAccount.isEmpty()) {
            i.putExtra(BookingEditActivity.EXTRA_PRESET_ACCOUNT, selectedAccount);
        }
        startActivity(i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handleShortcutIntent();
        androidx.core.content.ContextCompat.registerReceiver(this, bookingsChangedReceiver,
                new android.content.IntentFilter(
                        de.spahr.ausgaben.wear.WearBridge.ACTION_BOOKINGS_CHANGED),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        de.spahr.ausgaben.settings.Currencies.refresh(this);
        de.spahr.ausgaben.settings.MoneyFormat.refresh(this);
        de.spahr.ausgaben.settings.DateFormats.refresh(this);
        // Offene Belegfotos im Hintergrund ins Netzlaufwerk hochladen (No-op ohne offene/ohne Config).
        de.spahr.ausgaben.receipt.ReceiptSync.syncPending(this);
        // Belege gelöschter Buchungen entsorgen – nur einmal je App-Start, damit das „Rückgängig" nach
        // dem Löschen (onResume läuft auch beim Zurückkommen aus dem Editor) seine Bilder behält.
        de.spahr.ausgaben.receipt.ReceiptGc.runOncePerStart(this);
        // Kam der letzte Beleg-Export nie ans Ende? Dann einmal je App-Start anbieten, ihn zu
        // wiederholen – ebenfalls nur einmal, sonst stünde die Frage bei jeder Rückkehr aus dem Editor.
        offerReceiptExportResume();
        boolean gps = settings.isGpsEnabled();
        // Ziffern-Button (stille Betrag-only-Erfassung) nur bei aktivem Standort anbieten.
        findViewById(R.id.fabNumber).setVisibility(gps ? View.VISIBLE : View.GONE);
        if (gps && locationTagger != null && hasLocationPermission()) {
            locationTagger.start();
        }
        if (gps) {
            loadAliasPoints();
        } else {
            aliasPoints.clear();
        }
        // Die Favoritengruppe trägt einen übersetzten Namen; nach einem Sprachwechsel bliebe sonst das
        // alte Wort stehen. Ihre Mitglieder rührt das nicht an.
        repository.renameFavoritesGroup(getString(R.string.accounts_group_favorites));
        // Depotwert (für „Gesamtvermögen") laden; die Schublade füllt der Schubladenkopf, weil dort
        // die Kontengruppe und die festgelegte Reihenfolge gelten.
        repository.getDepots(depots -> {
            hasDepot = !depots.isEmpty();
            appDepots.clear();
            appDepots.addAll(depots);
            // Trägerzeilen abgleichen, damit jedes vorhandene Depot auch in Schublade und Verwaltung steht.
            repository.ensureDepotAccounts(depots, () -> drawerHeader.reload());
            loadDepotTotal(depots);
        });
        refreshBookings();
        // Standardort-Saldo an die Uhr spiegeln (No-op im foss-Flavor; nur bei Änderung übertragen).
        de.spahr.ausgaben.wear.BalanceSync.publish(this);
        // Dasselbe für die Empfänger mit Standort: Die Uhr baut ihre Umkreisliste daraus auch dann,
        // wenn das Handy gar nicht dabei ist.
        de.spahr.ausgaben.wear.PayeeSync.publish(this);
        // Homescreen-Widgets mit dem aktuellen Saldo/den letzten Buchungen versorgen.
        de.spahr.ausgaben.widget.AusgabenWidget.refreshAll(this);
        // Export/Sync aus dem Depot-Menü nachholen.
        if (getIntent().getBooleanExtra(EXTRA_RUN_EXPORT, false)) {
            getIntent().removeExtra(EXTRA_RUN_EXPORT);
            doExport();
        }
        // Aus dem Homescreen-Widget angeforderte Aktion ausführen (nach dem Entsperren).
        String widgetAction = getIntent().getStringExtra(EXTRA_WIDGET_ACTION);
        if (widgetAction != null) {
            getIntent().removeExtra(EXTRA_WIDGET_ACTION);
            handleWidgetAction(widgetAction);
        }
    }

    /** Führt die vom Homescreen-Widget angeforderte Schnellaktion aus. */
    private void handleWidgetAction(String action) {
        switch (action) {
            case WIDGET_ACTION_VOICE:
                voiceEntry.startVoiceEntry();
                break;
            case WIDGET_ACTION_DIGITS:
                showNumberEntry();
                break;
            case WIDGET_ACTION_BALANCES:
                startActivity(new Intent(this, BalanceActivity.class));
                break;
            case WIDGET_ACTION_NEW:
            default: {
                Intent i = new Intent(this, BookingEditActivity.class);
                if (!selectedAccount.isEmpty()) {
                    i.putExtra(BookingEditActivity.EXTRA_PRESET_ACCOUNT, selectedAccount);
                }
                startActivity(i);
                break;
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Die Kontensuche ist flüchtig: sie überlebt das Verlassen der App nicht.
        if (drawerHeader != null) {
            drawerHeader.clearSearch();
        }
        try {
            unregisterReceiver(bookingsChangedReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        if (locationTagger != null) {
            locationTagger.stop();
        }
    }

    // ---- Buchung per Sprache: ausgelagert in VoiceEntryController ----

    /** Der eingetippte Betrag in Cent, {@code 0} bei leerem oder unfertigem Feld. */
    private static long amountOf(android.widget.EditText field) {
        String raw = field.getText() == null ? "" : field.getText().toString().trim();
        Long cents = raw.isEmpty() ? null : de.spahr.ausgaben.settings.AmountExpression.toCents(raw);
        return cents == null || cents < 0 ? 0 : cents;
    }

    private static boolean hasAmount(android.widget.EditText field) {
        return amountOf(field) > 0;
    }

    /** Die Empfängernamen in ihrer Reihenfolge – daran erkennt man, ob sich der Vorschlag geändert hat. */
    private static List<String> payeeNames(List<Repository.VoiceResolution> list) {
        List<String> namen = new ArrayList<>();
        for (Repository.VoiceResolution r : list) {
            namen.add(r.payee);
        }
        return namen;
    }

    /**
     * Stille Zifferneingabe: Betrag eintippen → Betrag-only-Pfad (Auflösung per Standort). Unter dem
     * Betrag steht, wer in Frage kommt – vor der Eingabe die Anzahl, beim Tippen der zum Betrag
     * passende Empfänger. Antippen läuft im Kreis durch die Kandidaten; was dasteht, wird gebucht.
     *
     * <p>Ist der Betragsvorschlag abgeschaltet (Standard), steht sofort der nächstgelegene Empfänger
     * da und bleibt beim Tippen stehen – gewählt wird dann allein durch Antippen.
     */
    private void showNumberEntry() {
        HostedDialog.show(this, DLG_NUMBER_ENTRY, null);
    }

    /**
     * Baut die stille Zifferneingabe — beim ersten Mal und nach jeder Drehung erneut (siehe
     * {@link HostedDialog}).
     *
     * <p>Der Dialog hing bis 1.12 am Fenster der Maske. Beim Drehen war er weg und der eingetippte
     * Betrag mit ihm — man fing von vorn an. Die Felder tragen keine ids (sie entstehen hier im Code),
     * das Fenstersystem kann sie also nicht selbst wiederherstellen; deshalb merkt sich die Maske den
     * Betrag und die Stelle im Empfänger-Rundlauf selbst.</p>
     */
    private android.app.Dialog buildNumberEntry() {
        final boolean betragZaehlt = settings.isAmountSuggestEnabled();
        if (locationTagger != null && !hasLocationPermission()) {
            locationPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION);
        }
        final com.google.android.material.textfield.TextInputEditText field =
                new com.google.android.material.textfield.TextInputEditText(this);
        field.setHint(R.string.amount_hint);
        // Ziffern, das eingestellte Dezimalzeichen und + - * (kleine Rechnung wie 10+20*3); Struktur
        // regelt der CalcInputFilter. Ausgewertet wird beim Speichern über AmountExpression.
        AmountField.prepareCalc(field);

        // Was vor der Drehung dastand, steht wieder da.
        field.setText(numberEntryAmount);
        field.setSelection(field.getText() == null ? 0 : field.getText().length());
        field.addTextChangedListener(new SimpleWatcher(
                () -> numberEntryAmount = field.getText() == null ? "" : field.getText().toString()));

        final android.widget.TextView payeeView = new android.widget.TextView(this);
        payeeView.setText(getString(R.string.voice_payee_resolved, "—"));

        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        // Der Betrag ist das Einzige, was hier eingegeben wird – er darf größer stehen als sonstiger Text.
        field.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        // Oben Luft zum grünen Band, unten unter der OK-Taste – sonst klebt der Inhalt an beiden Rändern.
        box.setPadding(pad, pad + pad / 4, pad, pad);
        box.addView(field);
        // Unten Luft, damit der ermittelte Empfänger nicht auf der Rechentastatur sitzt.
        payeeView.setPadding(0, pad / 2, 0, pad);
        box.addView(payeeView);

        // Empfänger anhand von Position und Betrag ermitteln. Vor der Eingabe steht die Anzahl da,
        // beim Tippen der passende Name – 8 € sind die Waschanlage, 80 € die Tankstelle.
        final List<Repository.VoiceResolution> candidates = new ArrayList<>();
        final int[] pick = {numberEntryPick};          // Stelle im Rundlauf; hinter dem Ende: ohne Empfänger
        final boolean[] angetippt = {numberEntryTapped};   // ab dem ersten Tipp stehen Namen statt der Anzahl
        final boolean[] zeigtAnzahl = {false};        // steht gerade die Anzahl statt eines Namens da?
        final Runnable showPick = () -> {
            String name;
            zeigtAnzahl[0] = false;
            if (candidates.isEmpty()) {
                name = "—";
            } else if (pick[0] >= candidates.size()) {
                name = getString(R.string.nearby_payee_none);
            } else if (betragZaehlt && pick[0] == 0 && candidates.size() > 1
                    && !angetippt[0] && !hasAmount(field)) {
                zeigtAnzahl[0] = true;
                // Ohne Betrag ist noch nichts entschieden – dann nur sagen, wie viele in Frage kommen.
                // Nur solange nicht getippt wurde: sonst verdeckte die Anzahl den ersten Namen und der
                // Rundlauf zeigte ihn nie.
                name = getString(R.string.nearby_payee_count, candidates.size());
            } else {
                name = candidates.get(pick[0]).payee;
            }
            payeeView.setText(getString(R.string.voice_payee_resolved, name));
        };
        final Runnable resolveShow = () -> {
            String coords = settings.isGpsEnabled() && locationTagger != null
                    ? locationTagger.currentCoordinates() : null;
            if (coords == null) {
                return;
            }
            // Ohne Betrag (0) urteilt niemand: alle Nachbarn bleiben stehen, geordnet nach Nähe.
            long cents = betragZaehlt ? amountOf(field) : 0;
            repository.resolveNearby(coords, cents, Repository.VOICE_TYPE_EXPENSE, visibleAccounts(),
                    list -> {
                        if (!payeeNames(list).equals(payeeNames(candidates))) {
                            // Andere Reihenfolge (neuer Betrag, neuer Fix) → wieder der beste Vorschlag.
                            // Bei gleicher Liste bleibt stehen, was der Nutzer angetippt hat.
                            pick[0] = 0;
                            angetippt[0] = false;
                            numberEntryPick = 0;
                            numberEntryTapped = false;
                        }
                        candidates.clear();
                        candidates.addAll(list);
                        showPick.run();
                    });
        };
        showPick.run();
        resolveShow.run();
        if (locationTagger != null) {
            locationTagger.setOnLocationUpdate(resolveShow::run);
        }
        if (betragZaehlt) {
            // Jede Ziffer ändert das Bild – aber erst, wenn die Eingabe kurz ruht (sonst zählt „8" von „80" mit).
            final android.os.Handler typed = new android.os.Handler(android.os.Looper.getMainLooper());
            field.addTextChangedListener(new SimpleWatcher(() -> {
                typed.removeCallbacksAndMessages(null);
                typed.postDelayed(resolveShow, 250L);
            }));
        }
        // Antippen läuft im Kreis durch die Kandidaten und zuletzt über „ohne Empfänger“.
        android.util.TypedValue ripple = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
            payeeView.setBackgroundResource(ripple.resourceId);   // sichtbar machen, daß man tippen darf
        }
        payeeView.setOnClickListener(v -> {
            if (candidates.isEmpty()) {
                return;
            }
            if (zeigtAnzahl[0]) {
                // Hinter der Anzahl steckt der erste Kandidat – der erste Tipp deckt ihn auf,
                // sonst käme er im Rundlauf nie zum Vorschein.
                angetippt[0] = true;
            } else {
                pick[0] = pick[0] >= candidates.size() ? 0 : pick[0] + 1;
            }
            numberEntryPick = pick[0];
            numberEntryTapped = angetippt[0];
            showPick.run();
        });

        // Einziges Feld im Dialog: OK auf der Rechentastatur übernimmt direkt (kein separater
        // Speichern-Knopf nötig) – Rechnung ist bereits ausgewertet, wenn valid == true.
        final androidx.appcompat.app.AlertDialog[] dialogRef = new androidx.appcompat.app.AlertDialog[1];
        final CalcKeyboardView calc = new CalcKeyboardView(this);
        calc.attachTo(field);
        calc.setOnOk(valid -> {
            if (!valid) {
                Toast.makeText(this, R.string.error_amount_calc, Toast.LENGTH_SHORT).show();
                return;
            }
            String raw = field.getText() == null ? "" : field.getText().toString().trim();
            if (raw.isEmpty()) {
                return;
            }
            Long cents = de.spahr.ausgaben.settings.AmountExpression.toCents(raw);
            if (cents == null || cents <= 0) {
                Toast.makeText(this, R.string.error_amount_calc, Toast.LENGTH_SHORT).show();
                return;
            }
            String amt = de.spahr.ausgaben.settings.MoneyFormat.plain(cents);
            if (pick[0] != 0 || (!betragZaehlt && !candidates.isEmpty())) {
                // Genau das gilt, was dasteht (auch „ohne Empfänger"). Bei abgeschaltetem
                // Betragsvorschlag auch ohne Antippen – sonst zöge das erneute Auflösen doch wieder
                // das Betragssieb der Spracheingabe und buchte einen anderen als den angezeigten.
                voiceEntry.openVoiceEditor(
                        pick[0] >= candidates.size() ? VoiceEntryController.NO_PAYEE
                                : candidates.get(pick[0]), cents, "");
            } else {
                // Nichts angetippt → mit dem endgültigen Betrag noch einmal auflösen; die Anzeige
                // hinkt sonst um die Entprellung hinterher, wenn OK gleich nach der letzten Ziffer kommt.
                voiceEntry.handleVoiceResult(amt); // payee leer + Betrag → Betrag-only-Pfad
            }
            vergissZifferneingabe();
            if (dialogRef[0] != null) {
                dialogRef[0].dismiss();
            }
        });
        field.requestFocus();
        box.addView(calc);

        androidx.appcompat.app.AlertDialog dialog = new AppDialog(this)
                .setTitle(R.string.new_booking)
                .setView(AppDialog.scrollable(box))
                .setOnDismissListener(d -> {
                    if (locationTagger != null) {
                        locationTagger.setOnLocationUpdate(null);
                    }
                })
                .create();
        dialogRef[0] = dialog;
        // Nur die eigene Rechentastatur zeigen – die System-Tastatur des Dialogs unterdrücken.
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        return dialog;
    }

    /** Der eingetippte Stand ist gebucht (oder verworfen) – beim nächsten Öffnen wieder leer. */
    private void vergissZifferneingabe() {
        numberEntryAmount = "";
        numberEntryPick = 0;
        numberEntryTapped = false;
    }

    private boolean hasLocationPermission() {
        return androidx.core.content.ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED
                || androidx.core.content.ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void refreshBookings() {
        repository.getCategoriesGrouped(g -> {
            catExpense = g.expense;
            catIncome = g.income;
        });
        repository.getCategoryTypes(types -> categoryTypes = types);
        // Die wählbaren Stichwörter; sie ändern sich mit jedem Abgleich mit der .kmy-Datei.
        repository.getTagNames(names -> knownTagNames = names == null ? new ArrayList<>() : names);
        repository.getAccountNames(this::populateAccountDrawer);
        repository.getAllBookings(result -> {
            allBookings = result;
            repository.getAllSplitsMap(map -> {
                splitsByBooking = map;
                adapter.setSplits(map);
                reloadPlacesAndApply();
            });
        });
    }

    /** Lädt die Ort-Salden des aktuell gewählten Kontos und baut Liste/Saldo neu auf. */
    private void reloadPlacesAndApply() {
        repository.getPlaceBalances(selectedAccount, pb -> {
            placeBalances = new java.util.LinkedHashMap<>();
            allPlaceEntrySum = 0;
            for (PlaceBalance b : pb) {
                placeBalances.put(b.place, b.balanceCents);
                allPlaceEntrySum += b.balanceCents;
            }
            applyFilter();
        });
    }

    // ---- Konten-Schublade ----

    /** Füllt die Schublade mit „Alle Konten" + allen Konten; wählt beim ersten Mal das Standardkonto. */
    private void populateAccountDrawer(List<String> names) {
        appAccounts.clear();
        if (names != null) {
            appAccounts.addAll(names);
        }
        // Alle bereits importierten Konten (auch geschlossene) merken – zum Ausblenden im Import-Dialog.
        repository.getAllAccountNames(all -> {
            importedAccounts.clear();
            importedAccounts.addAll(all);
        });
        // Kontenart-Blöcke, Gruppenfilter und Beschriftung der obersten Zeile übernimmt der Schubladenkopf.
        drawerHeader.reload();
        if (!accountInitialized) {
            String def = settings.getDefaultAccount();
            selectedAccount = def == null ? "" : def.trim();
            accountInitialized = true;
        }
        updateAccountUi();
        // On-Boarding automatisch anstoßen, solange noch keine Konten existieren (nur einmal je Sitzung).
        if (!onboardingShown && (names == null || names.isEmpty())) {
            onboardingShown = true;
            startActivity(new Intent(this, OnboardingActivity.class));
        }
    }

    /**
     * Die gültige Kontengruppe hat sich geändert (oder wurde neu geladen). Ein echter Gruppenwechsel
     * setzt die Kontoauswahl zurück – sonst bliebe womöglich ein Konto gewählt, das in der Schublade
     * gar nicht mehr auftaucht.
     */
    private void onGroupChanged(long groupId, String label, List<String> accounts) {
        boolean switched = accountInitialized && groupId != selectedGroup;
        selectedGroup = groupId;
        selectedGroupLabel = label;
        groupAccounts.clear();
        for (String name : accounts) {
            groupAccounts.add(name.toLowerCase(java.util.Locale.ROOT));
        }
        if (switched) {
            selectAccount("");
        } else {
            updateAccountUi();
            applyFilter();
        }
    }

    private void selectAccount(String name) {
        selectedAccount = name == null ? "" : name;
        accountInitialized = true;
        saldoIndex = 0;
        updateAccountUi();
        reloadPlacesAndApply();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Kontowahl aus der Depot-Schublade übernehmen, wenn MainActivity wiederverwendet wird.
        String sel = intent.getStringExtra(EXTRA_SELECT_ACCOUNT);
        if (sel != null) {
            selectAccount(sel);
        }
    }

    /** Aktualisiert Toolbar-Titel und markiert den gewählten Schubladen-Eintrag. */
    private void updateAccountUi() {
        if (toolbarTitle != null) {
            // Ohne Kontowahl steht dort die gewählte Kontengruppe – bzw. „Alle Konten".
            String all = selectedGroupLabel.isEmpty() ? getString(R.string.account_all) : selectedGroupLabel;
            toolbarTitle.setText(selectedAccount.isEmpty() ? all : selectedAccount);
        }
        accountAdapter.setSelected(selectedAccount);
    }

    // ---- Filter ----

    private void applyFilter() {
        List<Booking> filtered = new ArrayList<>();
        java.util.Map<Long, Long> amountOverride = new java.util.HashMap<>();
        filteredSum = 0;
        totalBalance = 0;
        selectedAccountBalance = 0;
        groupBalance = 0;
        for (Booking b : allBookings) {
            long signed = signedOf(b);
            totalBalance += signed;
            if (selectedAccount.isEmpty() || b.account.equalsIgnoreCase(selectedAccount)) {
                selectedAccountBalance += signed;
            }
            if (inSelectedGroup(b.account)) {
                groupBalance += signed;
            }
            if (matchesFilter(b)) {
                long disp = displaySignedForFilter(b, signed);
                if (disp != signed) {
                    amountOverride.put(b.id, disp);
                }
                filtered.add(b);
                filteredSum += disp;
            }
        }
        // Depots der Gruppe zählen mit – sie haben keine Buchungen, ihr Wert steckt in den Wertpapieren.
        for (java.util.Map.Entry<String, Long> e : depotValues.entrySet()) {
            if (inSelectedGroup(e.getKey())) {
                groupBalance += e.getValue();
            }
        }
        adapter.setAmountOverride(amountOverride);
        adapter.setItems(filtered);
        buildSaldoViews();
        showSaldo();
        showEmptyHint(filtered.size());

        boolean active = isFilterActive();
        if (toolbarSubtitle != null) {
            // Bleibt während der Suche stehen und zählt beim Tippen die Treffer mit – das ist der
            // Grund, warum das Suchfeld nur den Kontonamen ersetzt und nicht die ganze Zeile.
            toolbarSubtitle.setText(active ? getString(R.string.filter_active, filtered.size()) : "");
            toolbarSubtitle.setVisibility(active ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Sagt bei leerer Liste, warum sie leer ist. Ein leerer Bildschirm sieht bei einem zu engen
     * Filter sonst genauso aus wie eine App, in die noch nichts importiert wurde.
     */
    private void showEmptyHint(int shown) {
        EmptyListState state = EmptyListState.of(allBookings.size(), shown, isFilterActive(),
                !selectedAccount.isEmpty());
        View box = findViewById(R.id.emptyBox);
        if (state == EmptyListState.NONE) {
            box.setVisibility(View.GONE);
            return;
        }
        int text;
        if (state == EmptyListState.NO_BOOKINGS) {
            text = R.string.empty_no_bookings;
        } else if (state == EmptyListState.NO_MATCH) {
            text = R.string.empty_no_match;
        } else {
            text = R.string.empty_account;
        }
        ((TextView) findViewById(R.id.emptyText)).setText(text);
        View action = findViewById(R.id.emptyAction);
        action.setVisibility(state.offersFilterReset() ? View.VISIBLE : View.GONE);
        action.setOnClickListener(v -> resetFilter());
        box.setVisibility(View.VISIBLE);
    }

    /** Vorzeichenbehafteter Betrag: Ausgaben zählen negativ, Einnahmen positiv. */
    private static long signedOf(Booking b) {
        return b.isIncome ? b.amountCents : -b.amountCents;
    }

    /**
     * Gehört die Buchung zur gerade gezeigten Auswahl (Konto aus der Schublade, sonst Kontengruppe)?
     * Grundmenge sowohl für den Filter als auch für die Grenzen des Betrags-Reglers.
     */
    private boolean inCurrentScope(Booking b) {
        // Konto ist die primäre Auswahl (Schublade), „" = alle Konten.
        if (!selectedAccount.isEmpty()) {
            return b.account.equalsIgnoreCase(selectedAccount);
        }
        // Ohne Kontowahl schränkt die gewählte Kontengruppe auf ihre Konten ein.
        return inSelectedGroup(b.account);
    }

    /**
     * Lädt die gelernten Standorte der Aliase für den Umkreis-Filter: hat eine Buchung selbst keine
     * Koordinaten in der Notiz, zählen die ihres Empfängers. Nur bei eingeschaltetem Standort.
     */
    private void loadAliasPoints() {
        repository.getAllAliases(list -> {
            aliasPoints.clear();
            for (de.spahr.ausgaben.db.PayeeCorrection a : list) {
                java.util.List<double[]> punkte = a.gpsPoints();
                if (punkte.isEmpty()) {
                    continue;
                }
                // Mehrere Aliase können auf denselben Empfänger zeigen (z. B. zwei Filialen) – ihre
                // Standorte gelten zusammen.
                String key = a.corrected.toLowerCase(java.util.Locale.ROOT);
                java.util.List<double[]> alle = aliasPoints.get(key);
                if (alle == null) {
                    alle = new ArrayList<>();
                    aliasPoints.put(key, alle);
                }
                alle.addAll(punkte);
            }
        });
    }

    /** Die Alias-Standorte des Empfängers oder {@code null}. */
    private java.util.List<double[]> aliasPointsFor(String payee) {
        if (payee == null || payee.isEmpty() || aliasPoints.isEmpty()) {
            return null;
        }
        return aliasPoints.get(payee.toLowerCase(java.util.Locale.ROOT));
    }

    private boolean matchesFilter(Booking b) {
        if (!inCurrentScope(b)) {
            return false;
        }
        // Zwei Suchtexte, beide über dieselbe Logik (Empfänger, Notiz oder Kategorie) – und beide
        // müssen zutreffen. Wer im Trichter „Netto" gesetzt hat und oben „Benzin" tippt, sucht
        // innerhalb der Netto-Buchungen weiter: Das Angezeigte ist die Grundlage der nächsten Suche.
        if (!de.spahr.ausgaben.db.BookingSearch.matches(b, filterPayee)) {
            return false;   // aus dem Trichter
        }
        if (!de.spahr.ausgaben.db.BookingSearch.matches(b, searchQuery)) {
            return false;   // aus der Live-Suche in der Titelzeile
        }
        if (!filterCategory.isEmpty() && !categoryMatchesBooking(b)) {
            return false;
        }
        if (!de.spahr.ausgaben.db.BookingTags.contains(b.tags, filterTag)) {
            return false;
        }
        // Betragsgrenzen sind vorzeichenbehaftet: −50 … −10 meint Ausgaben zwischen 10 und 50.
        long signed = signedOf(b);
        if (filterAmountFrom != null && signed < filterAmountFrom) {
            return false;
        }
        if (filterAmountTo != null && signed > filterAmountTo) {
            return false;
        }
        if (filterDateFrom != null && b.createdAt < filterDateFrom) {
            return false;
        }
        if (filterDateTo != null && b.createdAt > filterDateTo) {
            return false;
        }
        // Umkreis um die beim Anwenden eingefrorene Position; ohne Position bleibt nichts übrig.
        return de.spahr.ausgaben.location.RadiusFilter.matches(
                filterCenter, filterRadiusM, b.note, aliasPointsFor(b.payee));
    }

    /**
     * Bei aktivem Kategorie-Filter zeigt eine Splitbuchung nur den Teilbetrag der gewählten Kategorie;
     * sonst den vollen (vorzeichenbehafteten) Betrag {@code full}. Zusätzlich typgeprüft (Einnahme/
     * Ausgabe, siehe {@link #categoryMatchesBooking}) für gleichnamige Kategorien unterschiedlichen Typs.
     */
    private long displaySignedForFilter(Booking b, long full) {
        if (filterCategory.isEmpty()) {
            return full;
        }
        return de.spahr.ausgaben.db.CategoryBookingFilter.displaySigned(b, splitsByBooking,
                filterCategory, filterCategoryIsMain, full, categoryTypes, filterCategoryIsIncome);
    }

    /**
     * Treffer, wenn die (Haupt-)Kategorie oder eine Teilkategorie einer Splitbuchung passt – zusätzlich
     * typgeprüft ({@link #filterCategoryIsIncome}): kMyMoney erlaubt dieselbe Kategorie-Bezeichnung
     * unabhängig im Einnahme- und im Ausgabe-Baum (z. B. „Versicherung:Krankenzusatz"), maßgeblich ist
     * dabei der Typ der jeweiligen Buchungs-/Split-Zeile selbst (siehe {@link Booking#categoryIsIncome}).
     */
    private boolean categoryMatchesBooking(Booking b) {
        return de.spahr.ausgaben.db.CategoryBookingFilter.matchesBooking(b, splitsByBooking,
                filterCategory, filterCategoryIsMain, categoryTypes, filterCategoryIsIncome);
    }

    /**
     * Die gerade angezeigten Konten für die automatische Empfängersuche: das gewählte Konto, sonst die
     * Konten der gewählten Kontengruppe, sonst (Alle Konten ohne Gruppe) keine Einschränkung.
     */
    private java.util.Set<String> visibleAccounts() {
        if (!selectedAccount.isEmpty()) {
            return de.spahr.ausgaben.db.AccountScope.of(selectedAccount);
        }
        return selectedGroup > 0
                ? de.spahr.ausgaben.db.AccountScope.of(groupAccounts)
                : java.util.Collections.emptySet();
    }

    /** Gehört das Konto zur gewählten Kontengruppe? Ohne Gruppe gehört jedes Konto dazu. */
    private boolean inSelectedGroup(String account) {
        return selectedGroup <= 0 || (account != null
                && groupAccounts.contains(account.toLowerCase(java.util.Locale.ROOT)));
    }

    private boolean isFilterActive() {
        // Die Live-Suche zählt mit: Sonst verschwiege der Untertitel, daß die Liste eingeengt ist –
        // und das gerade dann, wenn das Feld eingeklappt ist und man es nicht mehr sieht.
        return !searchQuery.isEmpty()
                || !filterPayee.isEmpty() || !filterCategory.isEmpty() || !filterTag.isEmpty()
                || filterAmountFrom != null || filterAmountTo != null
                || filterDateFrom != null || filterDateTo != null
                || filterRadiusM > 0;
    }

    // ---- Belege der gefilterten Buchungen ausgeben ----

    /**
     * Sammelt die Belege der gerade gefilterten Buchungen und öffnet den Speicherdialog. Hat keine der
     * Buchungen einen Beleg, bleibt es bei einer kurzen Meldung – ein Dialog, an dessen Ende eine leere
     * Datei stünde, hilft niemandem.
     */
    private void exportReceipts() {
        java.util.List<Booking> filtered = new ArrayList<>();
        for (Booking b : allBookings) {
            if (matchesFilter(b)) {
                filtered.add(b);
            }
        }
        // Zweimal sammeln ist billig (reine Rechnung) und erspart eine Datenbankabfrage je Umbuchung:
        // Erst steht fest, welche Buchungen überhaupt einen Beleg tragen, und nur die werden nachgeschlagen.
        java.util.List<de.spahr.ausgaben.receipt.ReceiptExportJobs.Job> vorlaeufig =
                de.spahr.ausgaben.receipt.ReceiptExportJobs.collect(filtered);
        if (vorlaeufig.isEmpty()) {
            Toast.makeText(this, R.string.receipt_export_none, Toast.LENGTH_LONG).show();
            return;
        }
        java.util.Set<Long> mitBeleg = new java.util.HashSet<>();
        for (de.spahr.ausgaben.receipt.ReceiptExportJobs.Job j : vorlaeufig) {
            mitBeleg.add(j.bookingId);
        }
        java.util.List<Booking> kandidaten = new ArrayList<>();
        for (Booking b : filtered) {
            if (mitBeleg.contains(b.id)) {
                kandidaten.add(b);
            }
        }
        repository.securityInfoForBookings(kandidaten, info -> {
            de.spahr.ausgaben.receipt.ReceiptExportPlan plan =
                    de.spahr.ausgaben.receipt.ReceiptExportPlan.of(this,
                            de.spahr.ausgaben.receipt.ReceiptExportJobs.collect(
                                    filtered, uebersetzteBewegungsarten(info)));
            // Gepackt wird in der Reihenfolge der Liste: erst die Belege, die schon hier liegen. Bricht
            // das Nachladen später ab, stehen sie deshalb auf jeden Fall in der Datei.
            receiptExportJobs = plan.ordered();
            receiptExportLocal = plan.local.size();
            if (!plan.needsDownload()) {
                receiptExportDownload = true; // nichts zu holen – die Frage erübrigt sich
                startReceiptZipPicker();
                return;
            }
            askReceiptDownload(plan);
        });
    }

    /**
     * Vor dem Nachladen fragen. Nicht nur wegen der Kosten an einer getakteten Verbindung: Ein paar
     * hundert Dateien vom Server zu holen dauert, und wer das vorher weiß, entscheidet anders, als wenn
     * die App wortlos loslegt. Deshalb kommt die Frage auch im WLAN.
     */
    private void askReceiptDownload(de.spahr.ausgaben.receipt.ReceiptExportPlan plan) {
        int da = plan.local.size();
        int fehlt = plan.remote.size();
        StringBuilder text = new StringBuilder()
                .append(getString(R.string.receipt_download_ask, da, fehlt))
                .append("\n\n")
                .append(getString(R.string.receipt_download_takes_time));
        if (de.spahr.ausgaben.net.Net.isMetered(this)) {
            text.append("\n\n").append(getString(R.string.receipt_download_metered));
        }
        new AppDialog(this)
                .setTitle(R.string.receipt_export_title)
                .setMessage(text.toString())
                .setPositiveButton(R.string.receipt_download_all, (d, w) -> {
                    receiptExportDownload = true;
                    startReceiptZipPicker();
                })
                .setNeutralButton(getString(R.string.receipt_download_local_only, da), (d, w) -> {
                    receiptExportDownload = false;
                    startReceiptZipPicker();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * Bietet an, einen unterbrochenen oder unvollständig gebliebenen Beleg-Export zu wiederholen.
     *
     * <p>Ein zweiter Lauf ist billig: Alles, was der erste schon geholt hat, liegt jetzt auf dem Gerät
     * und rauscht durch. Geschrieben wird in dieselbe Datei – ohne weitere Rückfrage, auch die
     * Entscheidung über das Nachladen gilt von damals.</p>
     */
    private void offerReceiptExportResume() {
        final de.spahr.ausgaben.receipt.ReceiptExportResume.Pending offen =
                de.spahr.ausgaben.receipt.ReceiptExportResume.askOncePerStart(this);
        if (offen == null) {
            return;
        }
        String wann = java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(
                        new java.util.Date(offen.startedAt));
        new AppDialog(this)
                .setTitle(R.string.receipt_export_title)
                .setMessage(getString(R.string.receipt_export_resume_ask, offen.name, wann))
                .setPositiveButton(R.string.receipt_export_resume, (d, w) -> resumeReceiptExport(offen))
                .setNegativeButton(R.string.cancel, (d, w) -> {
                    // Der Nutzer will die Datei nicht mehr. Unbrauchbar? Dann weg damit – lesbar?
                    // Dann bleibt sie, sie enthält ja Belege.
                    de.spahr.ausgaben.receipt.ReceiptExportResume.clear(this);
                    removeIfBroken(android.net.Uri.parse(offen.uri));
                })
                .show();
    }

    /** Baut die Belegliste aus den gemerkten Buchungsnummern neu und schreibt dieselbe Datei. */
    private void resumeReceiptExport(de.spahr.ausgaben.receipt.ReceiptExportResume.Pending offen) {
        final android.net.Uri uri = android.net.Uri.parse(offen.uri);
        repository.getBookingsByIds(offen.bookingIds, bookings -> {
            if (bookings.isEmpty()) {
                de.spahr.ausgaben.receipt.ReceiptExportResume.clear(this);
                Toast.makeText(this, R.string.receipt_export_none, Toast.LENGTH_LONG).show();
                return;
            }
            repository.securityInfoForBookings(bookings, info -> {
                de.spahr.ausgaben.receipt.ReceiptExportPlan plan =
                        de.spahr.ausgaben.receipt.ReceiptExportPlan.of(this,
                                de.spahr.ausgaben.receipt.ReceiptExportJobs.collect(
                                        bookings, uebersetzteBewegungsarten(info)));
                receiptExportName = offen.name;
                writeReceiptZip(uri, plan.ordered(), offen.allowDownload, plan.local.size(), true);
            });
        });
    }

    /** Speicherort wählen lassen; geschrieben wird erst in {@link #writeReceiptZip}. */
    private void startReceiptZipPicker() {
        receiptExportName = "belege-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",
                java.util.Locale.US).format(new java.util.Date()) + ".zip";
        receiptZipLauncher.launch(receiptExportName);
    }

    /**
     * Macht das Fortschrittsbanner zum Abbruchknopf, solange ein Beleg-Export läuft. Ein Lauf über
     * hunderte Belege dauert; ohne diesen Ausweg bliebe nur, die App abzuwürgen – und genau das
     * hinterlässt die unbrauchbare Datei, um die es hier geht.
     */
    private void showExportBannerCancel(boolean laeuft) {
        View banner = findViewById(R.id.importBanner);
        if (banner == null) {
            return;
        }
        banner.setClickable(laeuft);
        banner.setOnClickListener(laeuft ? v -> askCancelReceiptExport() : null);
    }

    private void askCancelReceiptExport() {
        final java.util.concurrent.atomic.AtomicBoolean flag = receiptExportCancel;
        if (flag == null) {
            return;
        }
        new AppDialog(this)
                .setTitle(R.string.receipt_export_title)
                .setMessage(R.string.receipt_export_cancel_ask)
                .setPositiveButton(R.string.receipt_export_cancel, (d, w) -> {
                    flag.set(true);
                    importBanner.label(getString(R.string.receipt_export_cancelling));
                })
                .setNegativeButton(R.string.receipt_export_keep_running, null)
                .show();
    }

    /**
     * Die Bewegungsarten aus der Datenbank in die Sprache der App übersetzen – sie stehen später im
     * Dateinamen des Belegs, und dort will man „Kauf" lesen, nicht „buy".
     */
    private java.util.Map<Long, String[]> uebersetzteBewegungsarten(
            java.util.Map<Long, String[]> roh) {
        java.util.Map<Long, String[]> out = new java.util.HashMap<>();
        for (java.util.Map.Entry<Long, String[]> e : roh.entrySet()) {
            String art = e.getValue()[0];
            int text = de.spahr.ausgaben.db.SecurityTx.SELL.equals(art) ? R.string.action_sell
                    : de.spahr.ausgaben.db.SecurityTx.DIVIDEND.equals(art) ? R.string.action_dividend
                    : R.string.action_buy;
            out.put(e.getKey(), new String[]{getString(text), e.getValue()[1]});
        }
        return out;
    }

    /** Packt die vorgemerkten Belege in die gewählte Datei; das Holen vom Server kann dauern. */
    private void writeReceiptZip(android.net.Uri uri) {
        writeReceiptZip(uri, receiptExportJobs, receiptExportDownload, receiptExportLocal, false);
        receiptExportJobs = null;
    }

    /**
     * Der eigentliche Lauf.
     *
     * @param wieder {@code true} beim Wiederaufnehmen: Die Datei wird dann gekürzt und von vorn
     *               beschrieben, statt eine frisch angelegte zu füllen.
     */
    private void writeReceiptZip(android.net.Uri uri,
                                 java.util.List<de.spahr.ausgaben.receipt.ReceiptExportJobs.Job> jobs,
                                 final boolean download, int lokalCount, boolean wieder) {
        // Bis hierher liegen die Belege schon auf dem Gerät – ab da wird geholt (die Liste ist
        // vorsortiert, siehe ReceiptExportPlan.ordered).
        final int lokal = download ? lokalCount : jobs == null ? 0 : jobs.size();
        if (jobs == null || jobs.isEmpty()) {
            return;
        }
        // Ab jetzt gilt der Lauf als begonnen. Stirbt der Prozess mittendrin, findet die App den
        // Merker beim nächsten Start und bietet an, ihn zu wiederholen.
        if (!wieder) {
            java.util.List<Long> ids = new ArrayList<>(jobs.size());
            for (de.spahr.ausgaben.receipt.ReceiptExportJobs.Job j : jobs) {
                ids.add(j.bookingId);
            }
            de.spahr.ausgaben.receipt.ReceiptExportResume.start(this, uri.toString(),
                    receiptExportName, download, ids);
        }
        final java.util.concurrent.atomic.AtomicBoolean abbruch =
                new java.util.concurrent.atomic.AtomicBoolean();
        receiptExportCancel = abbruch;
        final String pausedLabel = getString(R.string.receipt_export_paused);
        importBanner.start(getString(lokal > 0
                ? R.string.receipt_export_running
                : R.string.receipt_export_running_fetch));
        // Der Text zählt mit: Bei 238 Belegen sagt ein Prozentwert allein zu wenig darüber, wie weit
        // der Lauf ist und wie lange er noch braucht. Und er sagt, woran es gerade liegt, wenn es
        // langsam vorangeht – Packen dauert Millisekunden, Holen dauert.
        final String[] zaehlend = {getString(R.string.receipt_export_running)};
        final de.spahr.ausgaben.util.ProgressListener progress = (done, total) -> {
            boolean holt = done >= lokal && total > lokal;
            zaehlend[0] = getString(holt
                    ? R.string.receipt_export_running_fetch_count
                    : R.string.receipt_export_running_count, done, total);
            importBanner.set(zaehlend[0],
                    de.spahr.ausgaben.export.ImportPhase.map(done, total, 0, 100));
        };
        // Der Lauf schläft, solange die App im Hintergrund ist – das Banner sagt, warum nichts vorangeht.
        // Danach steht wieder der Zählstand da, bei dem er stehengeblieben ist.
        final de.spahr.ausgaben.receipt.ReceiptZip.PauseListener pause =
                p -> importBanner.label(p ? pausedLabel : zaehlend[0]);
        // Das Banner ist jetzt der Abbruchknopf.
        showExportBannerCancel(true);
        final java.util.List<de.spahr.ausgaben.receipt.ReceiptExportJobs.Job> auftraege = jobs;
        new Thread(() -> {
            de.spahr.ausgaben.receipt.ReceiptZip.Result result = null;
            String error = null;
            // „wt" kürzt eine vorhandene Datei auf null – beim Wiederaufnehmen darf hinter dem neuen
            // Archiv nichts vom alten stehenbleiben.
            try (java.io.OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) {
                    throw new java.io.IOException("kein Schreibzugriff");
                }
                result = de.spahr.ausgaben.receipt.ReceiptZip.write(this, out, auftraege, progress,
                        pause, download, abbruch::get);
            } catch (Exception e) {
                error = String.valueOf(e.getMessage());
            }
            final de.spahr.ausgaben.receipt.ReceiptZip.Result done = result;
            final String failed = error;
            // Offen bleibt der Merker nur, wenn ein zweiter Lauf etwas bringt. Nach einem Abbruch
            // gehört er ebenfalls weg – der Nutzer hat gerade gesagt, dass er aufhören will.
            de.spahr.ausgaben.receipt.ReceiptExportResume.finished(this,
                    done != null && done.worthRetrying() && !done.cancelled);
            if (done != null && done.cancelled) {
                // Regulär geschlossen heißt in aller Regel: lesbar. Prüfen und nur wegwerfen, was
                // wirklich niemand mehr öffnen kann.
                removeIfBroken(uri);
            }
            if (isFinishing() || isDestroyed()) {
                return; // niemand mehr da, dem man etwas melden könnte – die Datei steht trotzdem
            }
            post(() -> {
                receiptExportCancel = null;
                showExportBannerCancel(false);
                importBanner.finish();
                if (failed != null) {
                    Toast.makeText(this, getString(R.string.receipt_export_failed, failed),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                reportReceiptZip(uri, done);
            });
        }).start();
    }

    /** Meldet das Ergebnis; ist nichts hineingekommen, verschwindet die eben angelegte Datei wieder. */
    private void reportReceiptZip(android.net.Uri uri, de.spahr.ausgaben.receipt.ReceiptZip.Result r) {
        if (r.written == 0) {
            try {
                android.provider.DocumentsContract.deleteDocument(getContentResolver(), uri);
            } catch (Exception ignored) {
                // Manche Anbieter lassen das nicht zu – dann bleibt es bei der Meldung.
            }
            new AppDialog(this)
                    .setTitle(R.string.receipt_export_title)
                    .setMessage(getString(R.string.receipt_export_empty, r.missing())
                            + gruende(r))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        String text = getString(R.string.receipt_export_done, r.written);
        if (r.missing() > 0) {
            text += "\n\n" + getString(R.string.receipt_export_missing, r.missing()) + gruende(r);
        }
        new AppDialog(this)
                .setTitle(R.string.receipt_export_title)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /**
     * Wirft die Datei weg, wenn sie <b>sicher</b> unbrauchbar ist – sonst bleibt sie liegen.
     *
     * <p>Eine abgebrochene Datei ist lesbar und enthält, was bis dahin gepackt wurde; die ist etwas
     * wert. Nur wo der Prozess mitten im Schreiben starb, fehlt das Dateiende und kein Packprogramm
     * kommt mehr hinein. Lässt sich das nicht feststellen, wird nicht gelöscht: Eine kaputte Datei,
     * die herumsteht, ist der kleinere Schaden als eine gelöschte, die in Ordnung war.</p>
     */
    private void removeIfBroken(android.net.Uri uri) {
        Boolean heil = zipLooksComplete(uri);
        if (heil == null || heil) {
            return;
        }
        try {
            android.provider.DocumentsContract.deleteDocument(getContentResolver(), uri);
        } catch (Exception ignored) {
            // Manche Anbieter lassen das nicht zu – dann bleibt die Datei eben liegen.
        }
    }

    /**
     * Ist die ZIP-Datei vollständig geschlossen? {@code null} = nicht feststellbar.
     *
     * <p>Gelesen wird nur der Schwanz der Datei, nicht ihr Inhalt – ein Beleg-Archiv kann hundert
     * Megabyte haben.</p>
     */
    private Boolean zipLooksComplete(android.net.Uri uri) {
        try (android.os.ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) {
                return null;
            }
            long size = pfd.getStatSize();
            if (size <= 0) {
                return Boolean.FALSE; // leer angelegt und nie beschrieben
            }
            int len = (int) Math.min(size, de.spahr.ausgaben.receipt.ZipCheck.TAIL_BYTES);
            byte[] tail = new byte[len];
            try (java.io.FileInputStream in =
                         new java.io.FileInputStream(pfd.getFileDescriptor())) {
                in.getChannel().position(size - len);
                int gelesen = 0;
                while (gelesen < len) {
                    int n = in.read(tail, gelesen, len - gelesen);
                    if (n < 0) {
                        break;
                    }
                    gelesen += n;
                }
                if (gelesen < len) {
                    return null; // unvollständig gelesen – lieber nichts behaupten
                }
            }
            return de.spahr.ausgaben.receipt.ZipCheck.looksComplete(tail);
        } catch (Exception e) {
            return null; // kein Zugriff, kein Positionieren – im Zweifel nicht löschen
        }
    }

    /**
     * Die Gründe hinter der Zahl der fehlenden Belege – je Grund eine Zeile, und nur die, die
     * zutreffen. „Nicht gefunden" und „keine Verbindung" verlangen ganz verschiedene Schritte, deshalb
     * standen sie nie zu Recht in derselben Zahl.
     */
    private String gruende(de.spahr.ausgaben.receipt.ReceiptZip.Result r) {
        StringBuilder sb = new StringBuilder();
        if (r.notFound > 0) {
            sb.append("\n").append(getString(R.string.receipt_export_not_found, r.notFound));
        }
        if (r.unreachable > 0) {
            sb.append("\n").append(getString(R.string.receipt_export_unreachable, r.unreachable));
        }
        if (r.pending > 0) {
            sb.append("\n").append(getString(R.string.receipt_export_pending, r.pending));
        }
        if (r.skipped > 0) {
            sb.append("\n").append(getString(R.string.receipt_export_skipped, r.skipped));
        }
        // Nur wo ein zweiter Anlauf etwas bringt: Was der Server nicht hat, holt auch die zehnte
        // Wiederholung nicht.
        if (r.unreachable + r.pending + r.skipped > 0) {
            sb.append("\n\n").append(getString(R.string.receipt_export_retry));
        }
        return sb.toString();
    }

    // ---- Saldo-Leiste (Durchschalten) ----

    private void buildSaldoViews() {
        saldoViews.clear();
        // 1. Saldo des gewählten Kontos (außer bei „Alle Konten")
        if (!selectedAccount.isEmpty()) {
            saldoViews.add(new SaldoView(VIEW_ACCOUNT_PREFIX + selectedAccount, selectedAccount,
                    selectedAccountBalance));
        }
        // 1b. Summe der gewählten Kontengruppe – „Gesamt" bleibt bewusst app-weit.
        if (selectedGroup > 0) {
            saldoViews.add(new SaldoView(VIEW_GROUP_TOTAL, selectedGroupLabel, groupBalance));
        }
        // 2. Gesamt (alle Konten + Depotwert). Ohne Depot bleibt es beim reinen Kontosaldo.
        saldoViews.add(new SaldoView(VIEW_TOTAL, getString(R.string.saldo_total),
                totalBalance + depotValueCents));
        // 2b. Sobald ein Depot importiert wurde, zusätzlich „Gesamt ohne Depot" und „Depot".
        if (hasDepot) {
            saldoViews.add(new SaldoView(VIEW_TOTAL_NODEPOT, getString(R.string.saldo_total_nodepot),
                    totalBalance));
            saldoViews.add(new SaldoView(VIEW_DEPOT_TOTAL, getString(R.string.saldo_depot_total),
                    depotValueCents));
        }
        // 3. Orte des gewählten Kontos (Standardort = Rest-Topf des Kontos).
        if (!selectedAccount.isEmpty()) {
            List<String> places = placesStore.getPlaces(selectedAccount);
            String standardort = placesStore.getDefaultPlace(selectedAccount);
            long otherSum = 0;
            for (String place : places) {
                if (!place.equals(standardort)) {
                    otherSum += placeBalances.containsKey(place) ? placeBalances.get(place) : 0L;
                }
            }
            for (String place : places) {
                long bal = place.equals(standardort)
                        ? selectedAccountBalance - otherSum
                        : (placeBalances.containsKey(place) ? placeBalances.get(place) : 0L);
                if (bal != 0) {
                    saldoViews.add(new SaldoView(VIEW_PLACE_PREFIX + place, place, bal));
                }
            }
        }
        // 4. Gefiltert
        if (isFilterActive()) {
            saldoViews.add(new SaldoView(VIEW_FILTERED, getString(R.string.saldo_filtered), filteredSum));
        }
        if (saldoIndex >= saldoViews.size()) {
            saldoIndex = 0;
        }
    }

    /** Summiert den Depotwert über alle Depots (für „Gesamtvermögen") und baut die Saldo-Leiste neu. */
    private void loadDepotTotal(List<String> depots) {
        if (depots == null || depots.isEmpty()) {
            depotValueCents = 0;
            buildSaldoViews();
            showSaldo();
            return;
        }
        final long[] total = {0};
        final int[] pending = {depots.size()};
        depotValues.clear();
        for (String depot : depots) {
            repository.getDepotHoldings(depot, holdings -> {
                long sum = 0;
                for (Repository.DepotHolding h : holdings) {
                    sum += h.valueCents;
                }
                depotValues.put(depot.toLowerCase(java.util.Locale.ROOT), sum);
                total[0] += sum;
                if (--pending[0] == 0) {
                    depotValueCents = total[0];
                    applyFilter(); // die Gruppensumme enthält auch die Depots der Gruppe
                }
            });
        }
    }

    private void showSaldo() {
        if (saldoViews.isEmpty()) {
            return;
        }
        SaldoView v = saldoViews.get(saldoIndex % saldoViews.size());
        textSaldoLabel.setText(v.label);
        textBalance.setText(getString(R.string.balance, formatEuro(v.cents)));
        textBalance.setTextColor(v.cents < 0 ? getColor(R.color.expense_red) : getColor(R.color.income_green));
    }

    private void cycleSaldo() {
        if (saldoViews.isEmpty()) {
            return;
        }
        saldoIndex = (saldoIndex + 1) % saldoViews.size();
        showSaldo();
        flashSaldoBar();
    }

    /** Kurzes Aufhellen der (dauerhaft grauen) Saldo-Leiste beim Wechsel. */
    private void flashSaldoBar() {
        final View bar = findViewById(R.id.saldoHeader);
        int from = getColor(R.color.saldo_bar_flash);
        int to = getColor(R.color.saldo_bar_bg);
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofObject(
                new android.animation.ArgbEvaluator(), from, to);
        anim.setDuration(350);
        anim.addUpdateListener(a -> bar.setBackgroundColor((int) a.getAnimatedValue()));
        anim.start();
    }

    private int indexOfFilteredView() {
        for (int i = 0; i < saldoViews.size(); i++) {
            if (VIEW_FILTERED.equals(saldoViews.get(i).key)) {
                return i;
            }
        }
        return 0;
    }

    private String currentViewKey() {
        if (saldoViews.isEmpty()) {
            return VIEW_TOTAL;
        }
        return saldoViews.get(saldoIndex % saldoViews.size()).key;
    }

    private String formatEuro(long signedCents) {
        return de.spahr.ausgaben.settings.MoneyFormat.display(signedCents,
                de.spahr.ausgaben.settings.Currencies.forAccount(selectedAccount));
    }

    private void showFilterDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_filter, null, false);
        TextInputEditText fPayee = view.findViewById(R.id.filterPayee);
        MaterialAutoCompleteTextView fCategory = view.findViewById(R.id.filterCategory);
        ZeroMarkSlider slider = view.findViewById(R.id.filterAmountSlider);
        TextInputEditText fFrom = view.findViewById(R.id.filterAmountFrom);
        TextInputEditText fTo = view.findViewById(R.id.filterAmountTo);
        AmountField.prepareNumber(fFrom);
        AmountField.prepareNumber(fTo);

        fPayee.setText(filterPayee);

        // Kategorie-Baum
        final String[] catValue = {filterCategory};
        final boolean[] catIsMain = {filterCategoryIsMain};
        // "Alle" (leerer Wert) setzt bewusst keinen Typ – kein Ausschluss.
        final Boolean[] catIsIncome = {filterCategory.isEmpty() ? null : filterCategoryIsIncome};
        CategoryFilterAdapter catAdapter = new CategoryFilterAdapter(this,
                getString(R.string.category_all),
                getString(R.string.category_group_expense), catExpense,
                getString(R.string.category_group_income), catIncome);
        PickerAdapters.categories(fCategory, catAdapter);
        fCategory.setText(filterCategory, false);
        // Über PickerBehaviour: die Kategorie kann auch getippt und stehengelassen werden, dann fällt
        // kein Antippen eines Listeneintrags an. Den Text setzt PickerBehaviour selbst.
        PickerBehaviour.onCommitted(fCategory, value -> {
            CategoryFilterAdapter.CatItem it = catAdapter.itemFor(value);
            if (it != null) {
                catValue[0] = it.value;
                catIsMain[0] = it.isMain;
                catIsIncome[0] = it.value.isEmpty() ? null : it.groupIsIncome;
            }
        });

        // Stichwort: dieselbe Bedienung wie das Kategoriefeld, mit „Alle" als erstem Eintrag. Ohne
        // bekannte Stichwörter (CSV-Betrieb, noch kein Abgleich) bleibt das ganze Feld weg.
        View tagLayout = view.findViewById(R.id.filterTagLayout);
        MaterialAutoCompleteTextView fTag = view.findViewById(R.id.filterTag);
        if (knownTagNames.isEmpty()) {
            tagLayout.setVisibility(View.GONE);
        } else {
            tagLayout.setVisibility(View.VISIBLE);
            List<String> tagChoices = new ArrayList<>();
            tagChoices.add(getString(R.string.category_all));
            tagChoices.addAll(knownTagNames);
            PickerAdapters.plainSearchable(fTag, tagChoices);
            fTag.setText(filterTag, false);
        }

        // Betrag-Range: vorzeichenbehaftete Beträge der gerade sichtbaren Buchungen, sortiert – der
        // Regler läuft über ihre Ränge, nicht über die Beträge (siehe AmountRange).
        java.util.List<Long> scope = new ArrayList<>();
        for (Booking b : allBookings) {
            if (inCurrentScope(b)) {
                scope.add(signedOf(b));
            }
        }
        final long[] sortedCents = new long[scope.size()];
        for (int i = 0; i < sortedCents.length; i++) {
            sortedCents[i] = scope.get(i);
        }
        java.util.Arrays.sort(sortedCents);
        final boolean hasRange = sortedCents.length > 1
                && sortedCents[0] < sortedCents[sortedCents.length - 1];
        final AmountRange amountRange = hasRange
                ? AmountRange.attach(slider, fFrom, fTo, sortedCents,
                        filterAmountFrom, filterAmountTo, this::formatEuro)
                : null;
        if (!hasRange) {
            // Kein sinnvoller Bereich (0/1 Buchung oder alle gleich) → deaktivieren.
            slider.setValueFrom(0f);
            slider.setValueTo(1f);
            slider.setValues(0f, 1f);
            slider.setEnabled(false);
            fFrom.setEnabled(false);
            fTo.setEnabled(false);
        }

        // Datums-Range (Slider in Monatsschritten; taggenau direkt im Feld eingebbar).
        com.google.android.material.slider.RangeSlider dateSlider = view.findViewById(R.id.filterDateSlider);
        TextInputEditText dFrom = view.findViewById(R.id.filterDateFrom);
        TextInputEditText dTo = view.findViewById(R.id.filterDateTo);
        long dtMin = Long.MAX_VALUE;
        long dtMax = Long.MIN_VALUE;
        for (Booking b : allBookings) {
            dtMin = Math.min(dtMin, b.createdAt);
            dtMax = Math.max(dtMax, b.createdAt);
        }
        final MonthRange dateRange;
        if (!allBookings.isEmpty()) {
            dateRange = MonthRange.attach(dateSlider, dFrom, dTo, dtMin, dtMax, filterDateFrom, filterDateTo);
        } else {
            dateRange = null;
            dateSlider.setValueFrom(0f);
            dateSlider.setValueTo(1f);
            dateSlider.setValues(0f, 1f);
            dateSlider.setEnabled(false);
            dFrom.setEnabled(false);
            dTo.setEnabled(false);
        }
        final long dtDataMin = dtMin;
        final long dtDataMax = dtMax;

        // Umkreis: nur bei eingeschaltetem Standort; jeder Tipp schaltet eine Stufe weiter.
        com.google.android.material.button.MaterialButton radius = view.findViewById(R.id.filterRadius);
        final int[] radiusM = {filterRadiusM};
        if (!settings.isGpsEnabled()) {
            radius.setVisibility(View.GONE);
        } else {
            setRadiusLabel(radius, radiusM[0]);
            radius.setOnClickListener(v -> {
                radiusM[0] = de.spahr.ausgaben.location.RadiusFilter.next(radiusM[0]);
                setRadiusLabel(radius, radiusM[0]);
                // Ohne Berechtigung gäbe es nie eine Position – einmal danach fragen.
                if (radiusM[0] > 0 && !hasLocationPermission()) {
                    locationPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION);
                }
            });
        }

        androidx.appcompat.app.AlertDialog dialog = new AppDialog(this)
                .setTitle(R.string.filter_title)
                .setView(view)
                .setPositiveButton(R.string.filter_apply, (d, w) -> {
                    // Der Knopf nimmt dem Feld nicht zwangsläufig den Fokus; ein Feld mitten in der Suche
                    // ist leer. Erst die Suche beenden, dann lesen.
                    PickerBehaviour.settleAll(view);

                    filterPayee = textOf(fPayee).trim();
                    String typedCategory = textOf(fCategory).trim();
                    if (!typedCategory.equals(catValue[0])) {
                        catValue[0] = typedCategory;
                        catIsMain[0] = isKnownMainCategory(typedCategory);
                        catIsIncome[0] = typeForCategory(typedCategory);
                    }

                    // „Alle" heißt: kein Stichwort gewählt.
                    String tag = textOf(fTag).trim();
                    filterTag = knownTagNames.isEmpty() || tag.equals(getString(R.string.category_all))
                            ? "" : tag;

                    filterCategory = catValue[0] == null ? "" : catValue[0].trim();
                    filterCategoryIsMain = catIsMain[0];
                    filterCategoryIsIncome = filterCategory.isEmpty() ? null : catIsIncome[0];
                    if (dateRange != null) {
                        long df = dateRange.getFromMillis();
                        long dt = dateRange.getToMillis();
                        if (df <= dtDataMin && dt >= dtDataMax) {
                            filterDateFrom = null;
                            filterDateTo = null;
                        } else {
                            filterDateFrom = df;
                            filterDateTo = dt;
                        }
                    } else {
                        filterDateFrom = null;
                        filterDateTo = null;
                    }
                    if (amountRange != null) {
                        if (amountRange.isFullRange()) {
                            filterAmountFrom = null;
                            filterAmountTo = null;
                        } else {
                            filterAmountFrom = amountRange.getFromCents();
                            filterAmountTo = amountRange.getToCents();
                        }
                    } else {
                        filterAmountFrom = null;
                        filterAmountTo = null;
                    }
                    filterRadiusM = radiusM[0];
                    // Die Position einmal einfrieren: die Liste soll nicht mitwandern, wenn man weitergeht.
                    filterCenter = filterRadiusM > 0 && locationTagger != null
                            ? de.spahr.ausgaben.location.Geo.parse(locationTagger.currentCoordinates())
                            : null;
                    if (filterRadiusM > 0 && filterCenter == null) {
                        Toast.makeText(this, R.string.filter_radius_no_fix, Toast.LENGTH_SHORT).show();
                    }
                    applyFilter();
                    // Filter angelegt/geändert → automatisch die gefilterte Summe anzeigen.
                    if (isFilterActive()) {
                        saldoIndex = indexOfFilteredView();
                        showSaldo();
                        flashSaldoBar();
                    }
                })
                .setNeutralButton(R.string.filter_reset, (d, w) -> resetFilter())
                .show();

        // Der Dialog ist lang – Suche, Kategorie, Stichwort, Betrag, Datum, Umkreis – und „Übernehmen"
        // sitzt an seinem Ende. Tippt man ins Suchfeld ganz oben, fährt die Tastatur hoch und verdeckt
        // ihn: Für den häufigsten Fall überhaupt, „ich suche einen Namen", waren erst zwei zusätzliche
        // Handgriffe nötig. Zwei Auswege, je nach Absicht:
        //   die Lupe auf der Tastatur übernimmt sofort,
        Keyboard.onCommitAction(fPayee, () ->
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick());
        //   und wer weiterschiebt, will unten etwas einstellen – dann geht sie nur weg.
        Keyboard.hideOnScroll((android.widget.ScrollView) view);
    }

    /**
     * Räumt alle Filterkriterien ab. Gerufen vom Neutral-Knopf des Filterdialogs und vom Knopf im
     * Leer-Hinweis – die Zuweisungen stehen deshalb nur einmal da, sonst würde das nächste neue
     * Kriterium an einer der beiden Stellen vergessen.
     */
    private void resetFilter() {
        // Auch die Live-Suche: Ein „Zurücksetzen", nach dem die Liste eingeengt bleibt, hätte gelogen.
        // Still, weil gleich unten ohnehin applyFilter() läuft.
        searchQuery = "";
        if (searchBar != null) {
            searchBar.clearSilently();
        }
        filterPayee = "";
        filterCategory = "";
        filterTag = "";
        filterCategoryIsMain = false;
        filterCategoryIsIncome = null;
        filterAmountFrom = null;
        filterAmountTo = null;
        filterDateFrom = null;
        filterDateTo = null;
        filterRadiusM = 0;
        filterCenter = null;
        saldoIndex = 0;
        applyFilter();
        showSaldo();
        flashSaldoBar();
    }

    /** Beschriftet den Umkreis-Knopf mit der eingestellten Stufe („Umkreis aus", „Umkreis 500 m"). */
    private void setRadiusLabel(com.google.android.material.button.MaterialButton button, int radiusM) {
        button.setText(radiusM <= 0
                ? getString(R.string.filter_radius_off)
                : getString(R.string.filter_radius,
                        de.spahr.ausgaben.location.RadiusFilter.label(radiusM)));
    }

    private String formatCents(long cents) {
        return de.spahr.ausgaben.settings.MoneyFormat.plain(cents);
    }

    private Long parseAmountToCents(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().replace(" ", "").replace(",", ".");
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(normalized).movePointRight(2)
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }

    private String textOf(android.widget.EditText e) {
        return e.getText() == null ? "" : e.getText().toString();
    }
    /** True für bekannte Hauptkategorien; frei getippte Unterkategorien bleiben exakte Filter. */
    private boolean isKnownMainCategory(String category) {
        if (category == null || category.trim().isEmpty() || category.contains(":")) {
            return false;
        }
        String c = category.trim();
        return containsIgnoreCase(catExpense, c) || containsIgnoreCase(catIncome, c);
    }

    /** Ermittelt den Kategorie-Typ auch dann, wenn der Text getippt statt aus der Liste gewählt wurde. */
    private Boolean typeForCategory(String category) {
        if (category == null || category.trim().isEmpty()) {
            return null;
        }
        String c = category.trim();
        if (containsIgnoreCase(catIncome, c)) {
            return Boolean.TRUE;
        }
        if (containsIgnoreCase(catExpense, c)) {
            return Boolean.FALSE;
        }
        return null;
    }


    // ---- Menü / Aktionen ----

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        // Menü-Titel kommen aus dem String-Pool (umgehen die Übersetzung) → per getString neu setzen.
        setMenuTitle(menu, R.id.action_export, R.string.action_export);
        setMenuTitle(menu, R.id.action_import_all, R.string.action_import_all);
        setMenuTitle(menu, R.id.action_export_receipts, R.string.action_export_receipts);
        setMenuTitle(menu, R.id.action_filter, R.string.action_filter);
        setMenuTitle(menu, R.id.action_reports, R.string.action_reports);
        setMenuTitle(menu, R.id.action_analysis, R.string.action_analysis);
        setMenuTitle(menu, R.id.action_categories, R.string.action_categories);
        setMenuTitle(menu, R.id.action_balance, R.string.action_balance);
        setMenuTitle(menu, R.id.action_budget, R.string.action_budget);
        setMenuTitle(menu, R.id.action_scheduled, R.string.action_scheduled);
        setMenuTitle(menu, R.id.action_switch_profile, R.string.action_switch_profile);
        setMenuTitle(menu, R.id.action_settings, R.string.action_settings);
        MenuIcons.tintOverflow(this, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(android.view.Menu menu) {
        // „Geplante Buchungen" nur im KMyMoney-Modus (aus der .kmy importiert).
        android.view.MenuItem scheduled = menu.findItem(R.id.action_scheduled);
        if (scheduled != null) {
            scheduled.setVisible(settings.isKmyMode());
        }
        // „Alles importieren" ebenfalls nur dort: Im CSV-Modus gibt es weder Depots noch Planungen,
        // „alles" hätte also keine Bedeutung – der CSV-Import bleibt, wo er ist.
        android.view.MenuItem importAll = menu.findItem(R.id.action_import_all);
        if (importAll != null) {
            importAll.setVisible(settings.isKmyMode());
        }
        // „Belege exportieren" bezieht sich auf die gefilterte Auswahl – ohne Filter ergäbe es nichts.
        android.view.MenuItem receipts = menu.findItem(R.id.action_export_receipts);
        if (receipts != null) {
            receipts.setVisible(isFilterActive());
        }
        // „Profil wechseln" nur, solange es überhaupt etwas zum Wechseln gibt.
        android.view.MenuItem switchProfile = menu.findItem(R.id.action_switch_profile);
        if (switchProfile != null) {
            switchProfile.setVisible(new de.spahr.ausgaben.settings.ProfileManager(this).getProfiles().size() > 1);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    private void setMenuTitle(android.view.Menu menu, int itemId, int stringId) {
        android.view.MenuItem item = menu.findItem(itemId);
        if (item != null) {
            item.setTitle(getString(stringId));
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull android.view.MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_export) {
            doExport();
            return true;
        } else if (id == R.id.action_import_all) {
            // Derselbe Weg wie der lange Druck auf „Alle Konten" in der Schublade: Prüfungen,
            // Fehlermeldungen und die Sicherheitsfrage stecken schon dort drin.
            onImportRequested("", true);
            return true;
        } else if (id == R.id.action_export_receipts) {
            exportReceipts();
            return true;
        } else if (id == R.id.action_filter) {
            showFilterDialog();
            return true;
        } else if (id == R.id.action_analysis) {
            Intent i = new Intent(this, AnalysisActivity.class);
            i.putExtra(AnalysisActivity.EXTRA_FILTER_PAYEE, filterPayee);
            i.putExtra(AnalysisActivity.EXTRA_FILTER_CATEGORY, filterCategory);
            i.putExtra(AnalysisActivity.EXTRA_FILTER_CATEGORY_MAIN, filterCategoryIsMain);
            i.putExtra(AnalysisActivity.EXTRA_FILTER_CATEGORY_INCOME,
                    filterCategoryIsIncome == null ? -1 : (filterCategoryIsIncome ? 1 : 0));
            i.putExtra(AnalysisActivity.EXTRA_FILTER_AMOUNT_FROM,
                    filterAmountFrom == null ? Long.MIN_VALUE : filterAmountFrom);
            i.putExtra(AnalysisActivity.EXTRA_FILTER_AMOUNT_TO,
                    filterAmountTo == null ? Long.MAX_VALUE : filterAmountTo);
            i.putExtra(AnalysisActivity.EXTRA_FILTER_DATE_FROM,
                    filterDateFrom == null ? Long.MIN_VALUE : filterDateFrom);
            i.putExtra(AnalysisActivity.EXTRA_FILTER_DATE_TO,
                    filterDateTo == null ? Long.MAX_VALUE : filterDateTo);
            i.putExtra(AnalysisActivity.EXTRA_VIEW_KEY, currentViewKey());
            startActivity(i);
            return true;
        } else if (id == R.id.action_categories) {
            startActivity(new Intent(this, CategoryChartActivity.class));
            return true;
        } else if (id == R.id.action_balance) {
            startActivity(new Intent(this, BalanceActivity.class));
            return true;
        } else if (id == R.id.action_budget) {
            startActivity(new Intent(this, BudgetActivity.class));
            return true;
        } else if (id == R.id.action_scheduled) {
            startActivity(new Intent(this, ScheduledActivity.class));
            return true;
        } else if (id == R.id.action_switch_profile) {
            ProfileSwitchDialog.show(this);
            return true;
        } else if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void doExport() {
        if (settings.isKmyMode()) {
            runKmyExport();
            return;
        }
        // Ohne Nextcloud-Config lokal exportieren; ggf. zuerst Zielordner wählen.
        if (!settings.hasRemoteConfig() && settings.getLocalExportTree().isEmpty()) {
            Toast.makeText(this, R.string.choose_export_folder, Toast.LENGTH_LONG).show();
            exportTreeLauncher.launch(null);
            return;
        }
        runExport();
    }

    private void runKmyExport() {
        showProgress(getString(R.string.progress_exporting));
        new KmyExportCoordinator(this, repository, settings).exportUnexported(
                new KmyExportCoordinator.Listener() {
                    @Override
                    public void onProgress(String stage) {
                        updateProgress(stage);
                    }

                    @Override
                    public void onComplete(String message, boolean refreshNeeded) {
                        dismissProgress();
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                        if (refreshNeeded) {
                            refreshBookings();
                        }
                    }
                });
    }

    private void runExport() {
        Toast.makeText(this, R.string.export_running, Toast.LENGTH_SHORT).show();
        String tree = settings.hasRemoteConfig() ? null : settings.getLocalExportTree();
        new ExportCoordinator(this, repository, settings, tree).exportUnexported((message, refreshNeeded) -> {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            if (refreshNeeded) {
                refreshBookings();
            }
        });
    }

    // ---- Import (Schublade: langer Tipp = importieren) ----

    /** Langer Tipp auf ein Konto (bzw. „Alle Konten") in der Schublade. */
    private void onImportRequested(String account, boolean isAll) {
        if (!settings.isKmyMode()) {
            startCsvImport();
            return;
        }
        if (!settings.hasRemoteConfig()) {
            Toast.makeText(this, R.string.export_no_config, Toast.LENGTH_LONG).show();
            return;
        }
        if (settings.getKmyPath().isEmpty()) {
            Toast.makeText(this, R.string.kmy_path_missing, Toast.LENGTH_LONG).show();
            return;
        }
        MaterialAlertDialogBuilder b = new AppDialog(this)
                .setNegativeButton(R.string.cancel, null);
        if (isAll) {
            b.setTitle(R.string.kmy_import_all_title)
                    .setMessage(R.string.kmy_import_all_message)
                    .setPositiveButton(R.string.kmy_import_replace, (d, w) -> runKmyImport(null));
        } else {
            b.setTitle(R.string.kmy_replace_title)
                    .setMessage(getString(R.string.kmy_replace_message, account))
                    .setPositiveButton(R.string.kmy_import_replace, (d, w) -> runKmyImport(account));
        }
        b.show();
    }

    /** „Neues Konto hinzufügen": lädt die .kmy und zeigt den Konto-Auswahldialog. */
    private void onAddAccountClicked() {
        if (!settings.isKmyMode()) {
            startCsvImport();
            return;
        }
        if (!settings.hasRemoteConfig()) {
            Toast.makeText(this, R.string.export_no_config, Toast.LENGTH_LONG).show();
            return;
        }
        String path = settings.getKmyPath();
        if (path.isEmpty()) {
            Toast.makeText(this, R.string.kmy_path_missing, Toast.LENGTH_LONG).show();
            return;
        }
        showProgress(getString(R.string.progress_download));
        new Thread(() -> {
            try {
                byte[] raw = RemoteStorage.from(settings).downloadBytes(RemotePath.folderOf(path), RemotePath.fileOf(path));
                KmyImporter importer = new KmyImporter(
                        new KmyDocument(raw, getApplicationContext()), getApplicationContext());
                // Stichwortliste der Datei übernehmen – nur was dort steht, ist in der App wählbar.
                // (Wie beim Aktualisieren/Export; sonst fehlten die Stichwörter nach dem Neuimport.)
                repository.replaceTags(importer.tagNames());
                post(() -> {
                    dismissProgress();
                    List<String> accounts = importer.accountNames();
                    List<String> depots = importer.depotNames();
                    if (accounts.isEmpty() && depots.isEmpty()) {
                        Toast.makeText(this, R.string.kmy_no_files, Toast.LENGTH_LONG).show();
                    } else {
                        chooseAccountForImport(importer, accounts, depots);
                    }
                });
            } catch (Exception e) {
                postImportError(e);
            }
        }).start();
    }

    /**
     * Auswahl-Dialog mit Mehrfachauswahl: mehrere Konten (und/oder Depots) auf einmal importieren.
     * Bereits importierte Konten (in der App vorhanden) werden ausgeblendet; Depots mit „(Depot)" markiert.
     */
    private void chooseAccountForImport(KmyImporter importer, List<String> accounts, List<String> depots) {
        // Bereits vorhandene App-Konten/Depots ausblenden – nur noch nicht importierte anbieten.
        // Konten inkl. geschlossener (importedAccounts), damit auch geschlossene nicht erneut erscheinen.
        final List<String> newAccounts = new ArrayList<>();
        for (String a : accounts) {
            if (!containsIgnoreCase(importedAccounts, a)) {
                newAccounts.add(a);
            }
        }
        final List<String> newDepots = new ArrayList<>();
        for (String d : depots) {
            if (!containsIgnoreCase(appDepots, d)) {
                newDepots.add(d);
            }
        }
        List<String> labels = new ArrayList<>(newAccounts);
        for (String d : newDepots) {
            labels.add(getString(R.string.kmy_choose_depot, d));
        }
        if (labels.isEmpty()) {
            Toast.makeText(this, R.string.kmy_no_new_accounts, Toast.LENGTH_LONG).show();
            return;
        }
        final int accountCount = newAccounts.size();
        final boolean[] checked = new boolean[labels.size()];
        String[] items = labels.toArray(new String[0]);
        new AppDialog(this)
                .setTitle(R.string.kmy_choose_account)
                .setMultiChoiceItems(items, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.kmy_import_selected, (d, w) -> {
                    List<String> accountTargets = new ArrayList<>();
                    List<String> depotTargets = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) {
                        if (!checked[i]) {
                            continue;
                        }
                        if (i < accountCount) {
                            accountTargets.add(newAccounts.get(i));
                        } else {
                            depotTargets.add(newDepots.get(i - accountCount));
                        }
                    }
                    if (accountTargets.isEmpty() && depotTargets.isEmpty()) {
                        return; // nichts angehakt
                    }
                    startBatchImport(importer, accountTargets, depotTargets);
                })
                .show();
    }

    /**
     * Importiert die gewählten Konten als Batch und anschließend die gewählten Depots nacheinander.
     * Läuft komplett im Hintergrund – die Oberfläche bleibt bedienbar; nur bei einem Fehler kommt eine
     * Meldung, am Ende wird die Liste still aktualisiert.
     */
    private void startBatchImport(KmyImporter importer, List<String> accountTargets,
                                  List<String> depotTargets) {
        importBanner.start(getString(R.string.import_running_banner));
        // Die Mengen stehen fest – daraus ergeben sich die Prozentbereiche dieses Laufs.
        final de.spahr.ausgaben.export.ImportBudget budget =
                de.spahr.ausgaben.export.KmyAccountImport.budgetFor(importer, accountTargets.size(),
                        depotTargets, false);
        new Thread(() -> {
            try {
                if (accountTargets.isEmpty()) {
                    post(() -> importDepotsThenFinish(importer, budget, depotTargets));
                    return;
                }
                // Ein Lesedurchlauf für ALLE Konten (vorher: einer je Konto über die ganze Datei).
                java.util.LinkedHashMap<String, List<Booking>> map = importer.bookingsForAccounts(
                        accountTargets, importBanner.phase(getString(R.string.import_stage_bookings),
                                budget.from(de.spahr.ausgaben.export.KmyAccountImport.BOOKINGS_READ),
                                budget.to(de.spahr.ausgaben.export.KmyAccountImport.BOOKINGS_READ)));
                for (String acc : accountTargets) {
                    repository.setAccountCurrency(acc, importer.currencyOf(acc));
                }
                // Konto- und Kategorietypen für ALLE Konten/Kategorien der .kmy übernehmen.
                repository.applyAccountTypes(importer.accountTypes());
                repository.applyCategoryTypes(importer.categoryTypes());
                int written = 0;
                for (List<Booking> l : map.values()) {
                    written += l.size();
                }
                budget.resize(de.spahr.ausgaben.export.KmyAccountImport.BOOKINGS_WRITE,
                        written * de.spahr.ausgaben.export.ImportBudget.BOOKING_WRITE);
                post(() -> repository.replaceImportAccounts(map,
                        importBanner.phase(getString(R.string.import_stage_saving),
                                budget.from(de.spahr.ausgaben.export.KmyAccountImport.BOOKINGS_WRITE),
                                budget.to(de.spahr.ausgaben.export.KmyAccountImport.BOOKINGS_WRITE)),
                        res -> importDepotsThenFinish(importer, budget, depotTargets)));
            } catch (Exception e) {
                postImportError(e);
            }
        }).start();
    }

    /** Importiert die Depots der Reihe nach; am Ende Banner auf 100 % und Liste aktualisieren. */
    private void importDepotsThenFinish(KmyImporter importer,
                                        de.spahr.ausgaben.export.ImportBudget budget,
                                        List<String> depots) {
        if (depots.isEmpty()) {
            completeImport();
            return;
        }
        final String depot = depots.get(0);
        final List<String> rest = new ArrayList<>(depots.subList(1, depots.size()));
        final String label = getString(R.string.import_stage_depot, depot);
        final String lesen = de.spahr.ausgaben.export.KmyAccountImport.depotRead(depot);
        final String schreiben = de.spahr.ausgaben.export.KmyAccountImport.depotWrite(depot);
        final de.spahr.ausgaben.util.ProgressListener readListener =
                importBanner.phase(label, budget.from(lesen), budget.to(lesen));
        final de.spahr.ausgaben.util.ProgressListener writeListener =
                importBanner.phase(label, budget.from(schreiben), budget.to(schreiben));
        new Thread(() -> {
            try {
                KmyImporter.DepotData data = importer.importDepot(depot, readListener);
                repository.replaceDepotImport(depot, data.securities, data.transactions, data.prices,
                        writeListener, () -> importDepotsThenFinish(importer, budget, rest));
            } catch (Exception e) {
                postImportError(e);
            }
        }).start();
    }

    /**
     * Langer Tipp in der Schublade: lädt die .kmy und aktualisiert genau dieses Depot – im Hintergrund
     * mit dem gelben Fortschrittsbanner; die Oberfläche bleibt bedienbar, nur bei Fehlern kommt eine Meldung.
     */
    private void reimportDepot(String depotName) {
        if (!settings.isKmyMode()) {
            Toast.makeText(this, R.string.export_no_config, Toast.LENGTH_LONG).show();
            return;
        }
        if (!settings.hasRemoteConfig()) {
            Toast.makeText(this, R.string.export_no_config, Toast.LENGTH_LONG).show();
            return;
        }
        final String path = settings.getKmyPath();
        if (path.isEmpty()) {
            Toast.makeText(this, R.string.kmy_path_missing, Toast.LENGTH_LONG).show();
            return;
        }
        importBanner.start(getString(R.string.import_running_banner));
        new Thread(() -> {
            try {
                byte[] raw = RemoteStorage.from(settings).downloadBytes(RemotePath.folderOf(path), RemotePath.fileOf(path),
                        importBanner.phase(getString(R.string.import_stage_download),
                                de.spahr.ausgaben.export.ImportPhase.DOWNLOAD_FROM,
                                de.spahr.ausgaben.export.ImportPhase.DOWNLOAD_TO));
                KmyImporter importer = new KmyImporter(
                        new KmyDocument(raw, getApplicationContext(),
                                importBanner.phase(getString(R.string.import_stage_reading),
                                        de.spahr.ausgaben.export.ImportPhase.READ_FILE_FROM,
                                        de.spahr.ausgaben.export.ImportPhase.READ_FILE_TO)),
                        getApplicationContext());
                // Nur dieses eine Depot: ihm gehört der ganze Rest des Balkens.
                final String label = getString(R.string.import_stage_depot, depotName);
                final de.spahr.ausgaben.export.ImportBudget budget =
                        de.spahr.ausgaben.export.KmyAccountImport.budgetFor(importer, 0,
                                java.util.Collections.singletonList(depotName), false);
                final String lesen = de.spahr.ausgaben.export.KmyAccountImport.depotRead(depotName);
                final String schreiben = de.spahr.ausgaben.export.KmyAccountImport.depotWrite(depotName);
                KmyImporter.DepotData data = importer.importDepot(depotName,
                        importBanner.phase(label, budget.from(lesen), budget.to(lesen)));
                final de.spahr.ausgaben.util.ProgressListener writeListener =
                        importBanner.phase(label, budget.from(schreiben), budget.to(schreiben));
                post(() -> repository.replaceDepotImport(depotName, data.securities,
                        data.transactions, data.prices, writeListener, this::completeImport));
            } catch (Exception e) {
                postImportError(e);
            }
        }).start();
    }

    /** Lädt die .kmy und importiert ein Konto ({@code null} = alle bereits vorhandenen App-Konten). */
    private void runKmyImport(final String account) {
        importBanner.start(getString(R.string.import_running_banner));
        // „Alle Konten" (account == null) heißt: Konten, Depots und geplante Buchungen in einem Zug.
        de.spahr.ausgaben.export.KmyAccountImport.start(this, settings, repository, appAccounts, account,
                account == null ? appDepots : java.util.Collections.emptyList(), account == null,
                new de.spahr.ausgaben.export.KmyAccountImport.Ui() {
                    @Override
                    public de.spahr.ausgaben.util.ProgressListener phase(String label, int from, int to) {
                        return importBanner.phase(label, from, to);
                    }

                    @Override
                    public void noMatchingAccount() {
                        post(() -> {
                            importBanner.finishNow();
                            Toast.makeText(MainActivity.this, R.string.kmy_account_not_found,
                                    Toast.LENGTH_LONG).show();
                        });
                    }

                    @Override
                    public void failed(Exception e) {
                        postImportError(e);
                    }

                    @Override
                    public void finished() {
                        completeImport();
                    }
                });
    }

    private boolean containsIgnoreCase(List<String> values, String needle) {
        for (String value : values) {
            if (value != null && value.equalsIgnoreCase(needle)) {
                return true;
            }
        }
        return false;
    }
    private void postImportError(Exception e) {
        final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        post(() -> {
            dismissProgress();
            importBanner.finishNow();
            Toast.makeText(this, getString(R.string.import_failed, msg), Toast.LENGTH_LONG).show();
        });
    }

    /** Import abgeschlossen: 100 % kurz zeigen, dann Banner ausblenden und Liste aktualisieren. */
    private void completeImport() {
        importBanner.finish();
        refreshBookings();
    }

    // ---- CSV-Import (Nextcloud-Liste oder lokaler Picker) ----

    private void startCsvImport() {
        if (settings.hasRemoteConfig()) {
            browseCsvAt(settings.getImportFolder());
        } else {
            importLauncher.launch(new String[]{
                    "text/*", "text/csv", "text/comma-separated-values", "application/octet-stream"});
        }
    }

    // ---- Fortschrittsdialog ----

    private androidx.appcompat.app.AlertDialog progressDialog;
    private TextView progressTextView;

    private void showProgress(String text) {
        if (progressDialog != null && progressDialog.isShowing()) {
            updateProgress(text);
            return;
        }
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_progress, null, false);
        progressTextView = view.findViewById(R.id.progressText);
        progressTextView.setText(text);
        progressDialog = new AppDialog(this)
                .setView(view)
                .setCancelable(false)
                .create();
        progressDialog.show();
    }

    private void updateProgress(String text) {
        if (progressTextView != null) {
            progressTextView.setText(text);
        }
    }

    /**
     * Den Fortschrittsdialog schließen — auch wenn sein Fenster schon weg ist. Die Begründung steht
     * wortgleich bei {@code DepotActivity.dismissProgress()}; das sind die beiden einzigen Masken der
     * App mit einem eigenen Fortschrittsdialog.
     */
    private void dismissProgress() {
        android.app.Dialog offen = progressDialog;
        progressDialog = null;
        progressTextView = null;
        if (offen == null) {
            return;
        }
        try {
            offen.dismiss();
        } catch (IllegalArgumentException fensterSchonFort) {
            android.util.Log.w("MainActivity",
                    "Fortschrittsdialog ließ sich nicht mehr schließen – sein Fenster war schon fort",
                    fensterSchonFort);
        }
    }

    /** Navigierbarer CSV-Browser (Unterordner + CSV-Dateien) im entfernten Importordner. */
    private void browseCsvAt(String folder) {
        Toast.makeText(this, R.string.loading_files, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                // Ordner und Dateien in einem Aufruf: SMB meldet sich sonst zweimal hintereinander an.
                RemoteStorage.Entries entries = RemoteStorage.from(settings).listEntries(folder, "csv");
                List<String> folders = entries.folders;
                List<String> files = entries.files;
                java.util.Collections.sort(folders, String.CASE_INSENSITIVE_ORDER);
                java.util.Collections.sort(files, String.CASE_INSENSITIVE_ORDER);
                post(() -> {
                    if (folder.isEmpty() && folders.isEmpty() && files.isEmpty()) {
                        Toast.makeText(this, R.string.no_files, Toast.LENGTH_LONG).show();
                    } else {
                        showCsvPick(folder, folders, files);
                    }
                });
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                post(() -> Toast.makeText(this,
                        getString(R.string.import_failed, msg), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void showCsvPick(String folder, List<String> folders, List<String> files) {
        Bundle args = new Bundle();
        args.putString(ARG_CSV_FOLDER, folder);
        args.putStringArray(ARG_CSV_FOLDERS, folders.toArray(new String[0]));
        args.putStringArray(ARG_CSV_FILES, files.toArray(new String[0]));
        HostedDialog.show(this, DLG_CSV_PICK, args);
    }

    /**
     * Baut den Datei-Browser aus dem, was im Bundle steht — beim ersten Mal und nach jeder Drehung.
     * Der Serverzugriff bleibt dabei aus: Die Liste dieses Ordners steht schon in den Angaben.
     */
    private android.app.Dialog buildCsvPick(Bundle args) {
        final String folder = args.getString(ARG_CSV_FOLDER, "");
        final List<String> labels = new java.util.ArrayList<>();
        final List<Runnable> actions = new java.util.ArrayList<>();
        if (!folder.isEmpty()) {
            labels.add("↑  ..");
            actions.add(() -> browseCsvAt(RemotePath.parentFolder(folder)));
        }
        for (String d : args.getStringArray(ARG_CSV_FOLDERS)) {
            labels.add("📁  " + d);
            final String target = folder.isEmpty() ? d : folder + "/" + d;
            actions.add(() -> browseCsvAt(target));
        }
        for (String f : args.getStringArray(ARG_CSV_FILES)) {
            labels.add(f);
            actions.add(() -> downloadAndImport(folder, f));
        }
        String title = folder.isEmpty() ? getString(R.string.choose_import_file) : "/" + folder;
        return new AppDialog(this)
                .setTitle(title)
                .setItems(labels.toArray(new String[0]), (d, w) -> actions.get(w).run())
                .create();
    }

    private void downloadAndImport(String folder, String fileName) {
        // Ohne das Banner sah ein CSV-Reimport nach nichts aus – anders als der KMY-Reimport
        // (reimportDepot/runKmyImport), der immer schon importBanner.start()/finish() nutzt.
        importBanner.start(getString(R.string.import_running_banner));
        // Bewußt ein eigener Faden und nicht repository.executor(): der ist einfach besetzt
        // (newSingleThreadExecutor) und trägt die gesamte Datenbankarbeit. Ein hängender Server
        // würde dort jede andere Abfrage der App mit blockieren, bis der Timeout greift.
        new Thread(() -> {
            try {
                String content = RemoteStorage.from(settings).downloadText(folder, fileName);
                processImport(content);
            } catch (Exception e) {
                importFehlgeschlagen(e);
            }
        }).start();
    }

    private void doImportLocal(Uri uri) {
        importBanner.start(getString(R.string.import_running_banner));
        new Thread(() -> {
            try {
                processImport(readText(uri));
            } catch (Exception e) {
                importFehlgeschlagen(e);
            }
        }).start();
    }

    /** Parst den Inhalt und ersetzt die exportierten Buchungen des Kontos. Aufruf aus Hintergrund-Thread. */
    private void processImport(String content) {
        try {
            CsvImporter importer = new CsvImporter(this);
            List<Booking> bookings = importer.parse(content);
            String account = importer.getParsedAccount();
            post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                repository.replaceImport(account, bookings, count -> {
                    importBanner.finish();
                    Toast.makeText(this, getString(R.string.import_done, count), Toast.LENGTH_LONG).show();
                    refreshBookings();
                });
            });
        } catch (Exception e) {
            importFehlgeschlagen(e);
        }
    }

    /**
     * Meldet einen gescheiterten Import auf dem Bedienfaden. Der Import läuft im Hintergrund weiter,
     * auch wenn der Nutzer die Maske inzwischen verlassen hat – am geschlossenen Fenster darf dann
     * weder das Banner noch ein Toast mehr angefaßt werden.
     */
    private void importFehlgeschlagen(Exception e) {
        final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        post(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            importBanner.finishNow();
            Toast.makeText(this, getString(R.string.import_failed, msg), Toast.LENGTH_LONG).show();
        });
    }

    private String readText(Uri uri) throws Exception {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (is != null && (n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
