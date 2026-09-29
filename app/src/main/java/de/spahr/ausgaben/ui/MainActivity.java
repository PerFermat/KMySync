package de.spahr.ausgaben.ui;

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

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.BookingSplit;
import de.spahr.ausgaben.db.PlaceBalance;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.export.ExportCoordinator;
import de.spahr.ausgaben.export.KmyExportCoordinator;
import de.spahr.ausgaben.settings.PlacesStore;
import de.spahr.ausgaben.settings.SettingsStore;
import de.spahr.ausgaben.voice.VoiceRecognizer;

public class MainActivity extends LocalizedActivity implements HostedDialog.Host {

    /** Schlüssel und Angaben der Dialoge dieser Maske – siehe {@link HostedDialog}. */

    /** Die stille Zifferneingabe samt ihrem über die Drehung geretteten Stand. */
    private NumberEntryController numberEntry;
    private static final String STATE_SEARCH_QUERY = "s_searchQuery";
    private static final String STATE_SEARCH_OPEN = "s_searchOpen";

    @Override
    protected void onDestroy() {
        // Ein entprellter Filterlauf, der nach dem Ende der Maske feuert, arbeitet auf Ansichten, die
        // es nicht mehr gibt. Ui.post fängt das nicht: Der Handler gehört der Suchleiste, nicht ihr.
        if (searchBar != null) {
            searchBar.detach();
        }
        // Ein laufender Beleg-Export liefe sonst bis zum letzten Beleg weiter, um dann an eine Maske
        // zu melden, die es nicht mehr gibt. Was bis dahin gepackt wurde, bleibt in der Datei.
        if (receiptExport != null) {
            receiptExport.detach();
        }
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(android.os.Bundle out) {
        super.onSaveInstanceState(out);
        numberEntry.save(out);
        // Die Kontenschublade rettet ihre Suche nicht – dort ist der Begriff zwei Wörter. Hier hat man
        // womöglich gerade eine Buchung von 2019 eingekreist; die Drehung dürfte das nicht wegwerfen.
        out.putString(STATE_SEARCH_QUERY, searchQuery);
        out.putBoolean(STATE_SEARCH_OPEN, searchBar != null && searchBar.istOffen());
    }


    @Override
    public android.app.Dialog buildDialog(String key, Bundle args) {
        if (MainImportFlow.DLG_CSV_PICK.equals(key)) {
            return importFlow.buildCsvPick(args);
        }
        return NumberEntryController.DLG_NUMBER_ENTRY.equals(key) ? numberEntry.buildDialog() : null;
    }

    @Override
    public void onDialogCancelled(String key, Bundle args) {
        if (NumberEntryController.DLG_NUMBER_ENTRY.equals(key)) {
            // Weggetippt heißt verworfen – beim nächsten Öffnen soll nicht der alte Betrag dastehen.
            numberEntry.forget();
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
    /** Die ganze Zeile aus X und Trefferzahl – sie erscheint und verschwindet gemeinsam. */
    private View toolbarSubtitleRow;
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
     * Der Suchtext aus der <b>Live-Suche in der Titelzeile</b> – bewusst neben {@link BookingFilterState#payee}
     * und nicht an seiner Stelle: Beide gelten zusammen, damit man innerhalb eines gesetzten Filters
     * weitersuchen kann. Gehalten wird er hier und nicht nur im Feld, weil das Feld eingeklappt wird,
     * ohne daß die Suche endet.
     */
    private String searchQuery = "";

    /** Die Kriterien des Filtertrichters, siehe {@link FilterDialog}. */
    private final BookingFilterState filter = new BookingFilterState();
    /** Die in KMyMoney vorhandenen Stichwörter; leer = das Filterfeld erscheint gar nicht. */
    private List<String> knownTagNames = new ArrayList<>();
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
    /** Konten/Depots/Planungen aus der .kmy und CSV-Import, siehe {@link MainImportFlow}. */
    private MainImportFlow importFlow;
    /** Speicherdialog für die ZIP-Datei mit den Belegen der gefilterten Buchungen. */
    private ActivityResultLauncher<String> receiptZipLauncher;
    /** Sammeln, fragen, packen, melden – siehe {@link ReceiptExportController}. */
    private ReceiptExportController receiptExport;
    private ActivityResultLauncher<Intent> voiceLauncher;
    private VoiceEntryController voiceEntry;
    private ActivityResultLauncher<Intent> editLauncher;
    private ActivityResultLauncher<String> locationPermissionLauncher;

    /** Aktueller Standort für die Betrag-only-Auflösung (rein lokal, nur Koordinaten). */
    private de.spahr.ausgaben.location.LocationTagger locationTagger;

    /** Was die Zifferneingabe von dieser Maske braucht – erst beim Bauen des Dialogs gefragt. */
    private NumberEntryController.Host numberEntryHost() {
        return new NumberEntryController.Host() {
            @Override
            public Repository repository() {
                return repository;
            }

            @Override
            public SettingsStore settings() {
                return settings;
            }

            @Override
            public de.spahr.ausgaben.location.LocationTagger locationTagger() {
                return locationTagger;
            }

            @Override
            public boolean hasLocationPermission() {
                return MainActivity.this.hasLocationPermission();
            }

            @Override
            public void requestLocationPermission() {
                locationPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION);
            }

            @Override
            public java.util.Set<String> visibleAccounts() {
                return MainActivity.this.visibleAccounts();
            }

            @Override
            public VoiceEntryController voiceEntry() {
                return voiceEntry;
            }

        };
    }

    /** Was der Import-Ablauf von dieser Maske braucht. */
    private MainImportFlow.Host importHost() {
        return new MainImportFlow.Host() {
            @Override
            public void showProgress(String text) {
                MainActivity.this.showProgress(text);
            }

            @Override
            public void dismissProgress() {
                MainActivity.this.dismissProgress();
            }

            @Override
            public void refreshBookings() {
                MainActivity.this.refreshBookings();
            }

        };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Vor allem anderen: der Dialog der Zifferneingabe wird gleich mit der Activity wiederhergestellt
        // und liest diese Werte (siehe NumberEntryController.restore).
        numberEntry = new NumberEntryController(this, numberEntryHost());
        numberEntry.restore(savedInstanceState);
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
        toolbarSubtitleRow = findViewById(R.id.toolbarSubtitleRow);
        // Das X vor „Filter aktiv (n)" räumt in zwei Stufen, weil zwei Dinge dahinterstecken können.
        // Erst die Schnellsuche – die ist die flüchtige von beiden, meist eben erst getippt und
        // schneller wieder weg als das, was man im Trichter eingestellt hat. Die Zeile bleibt dann
        // stehen und zeigt den Stand des Trichters allein; das X daran ist der zweite Schritt.
        //
        // Alles auf einmal zu räumen wäre der kürzere Weg und der ärgerlichere: Wer nach einem Namen
        // gesucht hat und wieder heraus will, verlöre dabei den Zeitraum oder den Betragsbereich mit,
        // den er sich vorher zurechtgelegt hat.
        findViewById(R.id.filterClear).setOnClickListener(v -> {
            if (!searchQuery.isEmpty()) {
                // clear() meldet selbst; der Rückruf setzt searchQuery und filtert neu.
                searchBar.clear();
            } else {
                resetFilter();
            }
        });
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

        searchBar = new BookingSearchBar(toolbarTitle,
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
                        importFlow.onImportRequested(account, isAll);
                    }

                    @Override
                    public void onDepotSelect(String depot) {
                        // Depots gibt es nur, wenn aus der .kmy gelesen wird; die Schublade blendet sie
                        // sonst schon aus, das hier ist nur die zweite Verteidigungslinie.
                        if (!settings.isKmySource()) {
                            return;
                        }
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
                        importFlow.reimportDepot(depot);
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
        findViewById(R.id.addAccount).setOnClickListener(v -> importFlow.onAddAccountClicked());

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
            if (settings.isKmySource() && settings.hasRemoteConfig() && !settings.getKmyPath().isEmpty()) {
                importFlow.runKmyImport(selectedAccount.isEmpty() ? null : selectedAccount);
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
        findViewById(R.id.fabNumber).setOnClickListener(v -> numberEntry.show());

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
        importFlow = new MainImportFlow(this, settings, repository, importBanner, appAccounts,
                importedAccounts, appDepots, importHost());
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
                        receiptExport.onZipPicked(uri);
                    }
                });
        // Erst hier, nicht früher: Der Regler braucht den Datei-Wähler und das Band, und beide stehen
        // erst jetzt. Vor dem Aufruf von offerResume() weiter unten muß er da sein.
        receiptExport = new ReceiptExportController(this, repository, importBanner, receiptZipLauncher);
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
        // Ein angefangener Wechsel des Belegordners wird hier zu Ende gebracht (No-op, wenn keiner
        // offen ist). Vor dem Aufräumlauf: Solange Belege noch im alten Ordner liegen, soll der
        // Umzug laufen, nicht das Aufräumen.
        de.spahr.ausgaben.receipt.ReceiptFolderMove.run(this);
        // Belege gelöschter Buchungen entsorgen – nur einmal je App-Start, damit das „Rückgängig" nach
        // dem Löschen (onResume läuft auch beim Zurückkommen aus dem Editor) seine Bilder behält.
        de.spahr.ausgaben.receipt.ReceiptGc.runOncePerStart(this);
        // Kam der letzte Beleg-Export nie ans Ende? Dann einmal je App-Start anbieten, ihn zu
        // wiederholen – ebenfalls nur einmal, sonst stünde die Frage bei jeder Rückkehr aus dem Editor.
        receiptExport.offerResume();
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
                numberEntry.show();
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
        if (toolbarSubtitleRow != null) {
            // Bleibt während der Suche stehen und zählt beim Tippen die Treffer mit – das ist der
            // Grund, warum das Suchfeld nur den Kontonamen ersetzt und nicht die ganze Zeile.
            // Geschaltet wird die Zeile samt X: Ein X ohne Zahl daneben wäre sinnlos, eine Zahl ohne
            // X ein Filter, den man nur über den Trichter wieder los wird.
            toolbarSubtitle.setText(active ? getString(R.string.filter_active, filtered.size()) : "");
            toolbarSubtitleRow.setVisibility(active ? View.VISIBLE : View.GONE);
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
        if (!de.spahr.ausgaben.db.BookingSearch.matches(b, filter.payee)) {
            return false;   // aus dem Trichter
        }
        if (!de.spahr.ausgaben.db.BookingSearch.matches(b, searchQuery)) {
            return false;   // aus der Live-Suche in der Titelzeile
        }
        if (!filter.category.isEmpty() && !categoryMatchesBooking(b)) {
            return false;
        }
        if (!de.spahr.ausgaben.db.BookingTags.contains(b.tags, filter.tag)) {
            return false;
        }
        // Betragsgrenzen sind vorzeichenbehaftet: −50 … −10 meint Ausgaben zwischen 10 und 50.
        long signed = signedOf(b);
        if (filter.amountFrom != null && signed < filter.amountFrom) {
            return false;
        }
        if (filter.amountTo != null && signed > filter.amountTo) {
            return false;
        }
        if (filter.dateFrom != null && b.createdAt < filter.dateFrom) {
            return false;
        }
        if (filter.dateTo != null && b.createdAt > filter.dateTo) {
            return false;
        }
        // Umkreis um die beim Anwenden eingefrorene Position; ohne Position bleibt nichts übrig.
        return de.spahr.ausgaben.location.RadiusFilter.matches(
                filter.center, filter.radiusM, b.note, aliasPointsFor(b.payee));
    }

    /**
     * Bei aktivem Kategorie-Filter zeigt eine Splitbuchung nur den Teilbetrag der gewählten Kategorie;
     * sonst den vollen (vorzeichenbehafteten) Betrag {@code full}. Zusätzlich typgeprüft (Einnahme/
     * Ausgabe, siehe {@link #categoryMatchesBooking}) für gleichnamige Kategorien unterschiedlichen Typs.
     */
    private long displaySignedForFilter(Booking b, long full) {
        if (filter.category.isEmpty()) {
            return full;
        }
        return de.spahr.ausgaben.db.CategoryBookingFilter.displaySigned(b, splitsByBooking,
                filter.category, filter.categoryIsMain, full, categoryTypes, filter.categoryIsIncome);
    }

    /**
     * Treffer, wenn die (Haupt-)Kategorie oder eine Teilkategorie einer Splitbuchung passt – zusätzlich
     * typgeprüft ({@link BookingFilterState#categoryIsIncome}): kMyMoney erlaubt dieselbe Kategorie-Bezeichnung
     * unabhängig im Einnahme- und im Ausgabe-Baum (z. B. „Versicherung:Krankenzusatz"), maßgeblich ist
     * dabei der Typ der jeweiligen Buchungs-/Split-Zeile selbst (siehe {@link Booking#categoryIsIncome}).
     */
    private boolean categoryMatchesBooking(Booking b) {
        return de.spahr.ausgaben.db.CategoryBookingFilter.matchesBooking(b, splitsByBooking,
                filter.category, filter.categoryIsMain, categoryTypes, filter.categoryIsIncome);
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
        return !searchQuery.isEmpty() || filter.isActive();
    }

    // ---- Belege der gefilterten Buchungen ausgeben ----
    // Der Ablauf steht in ReceiptExportController; hier bleibt nur die Auswahl, denn der
    // Filter gehoert der Maske. Aufgerufen aus dem Menue, beim Start und aus dem Datei-Waehler.

    /** Die gerade sichtbare Auswahl – dieselbe Kette wie in {@link #applyFilter()}. */
    private java.util.List<Booking> gefilterteBuchungen() {
        java.util.List<Booking> treffer = new ArrayList<>();
        for (Booking b : allBookings) {
            if (matchesFilter(b)) {
                treffer.add(b);
            }
        }
        return treffer;
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
        FilterDialog.show(this, filter, new FilterDialog.Host() {
            @Override
            public List<String> expenseCategories() {
                return catExpense;
            }

            @Override
            public List<String> incomeCategories() {
                return catIncome;
            }

            @Override
            public List<String> knownTagNames() {
                return knownTagNames;
            }

            @Override
            public List<Long> amountsInScope() {
                List<Long> scope = new ArrayList<>();
                for (Booking b : allBookings) {
                    if (inCurrentScope(b)) {
                        scope.add(signedOf(b));
                    }
                }
                return scope;
            }

            @Override
            public List<Long> bookingDates() {
                List<Long> dates = new ArrayList<>();
                for (Booking b : allBookings) {
                    dates.add(b.createdAt);
                }
                return dates;
            }

            @Override
            public boolean isGpsEnabled() {
                return settings.isGpsEnabled();
            }

            @Override
            public boolean hasLocationPermission() {
                return MainActivity.this.hasLocationPermission();
            }

            @Override
            public void requestLocationPermission() {
                locationPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION);
            }

            @Override
            public String currentCoordinates() {
                return locationTagger != null ? locationTagger.currentCoordinates() : null;
            }

            @Override
            public String formatAmount(long signedCents) {
                return formatEuro(signedCents);
            }

            @Override
            public void onFilterApplied() {
                applyFilter();
                // Filter angelegt/geändert → automatisch die gefilterte Summe anzeigen.
                if (isFilterActive()) {
                    saldoIndex = indexOfFilteredView();
                    showSaldo();
                    flashSaldoBar();
                }
            }

            @Override
            public void onFilterReset() {
                resetFilter();
            }
        });
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
        filter.reset();
        saldoIndex = 0;
        applyFilter();
        showSaldo();
        flashSaldoBar();
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
        // „Geplante Buchungen": sichtbar, sobald aus der .kmy gelesen wird (auch im gemischten Modus,
        // dort ist nur das Weiterstellen gesperrt, siehe BookingEditActivity).
        android.view.MenuItem scheduled = menu.findItem(R.id.action_scheduled);
        if (scheduled != null) {
            scheduled.setVisible(settings.isKmySource());
        }
        // „Alles importieren" ebenfalls dort: Im reinen CSV-Modus gibt es weder Depots noch Planungen,
        // „alles" hätte also keine Bedeutung – der CSV-Import bleibt, wo er ist.
        android.view.MenuItem importAll = menu.findItem(R.id.action_import_all);
        if (importAll != null) {
            importAll.setVisible(settings.isKmySource());
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
            importFlow.onImportRequested("", true);
            return true;
        } else if (id == R.id.action_export_receipts) {
            receiptExport.start(gefilterteBuchungen());
            return true;
        } else if (id == R.id.action_filter) {
            showFilterDialog();
            return true;
        } else if (id == R.id.action_analysis) {
            Intent i = new Intent(this, AnalysisActivity.class);
            i.putExtra(AnalysisActivity.EXTRA_FILTER_PAYEE, filter.payee);
            i.putExtra(AnalysisActivity.EXTRA_FILTER_CATEGORY, filter.category);
            i.putExtra(AnalysisActivity.EXTRA_FILTER_CATEGORY_MAIN, filter.categoryIsMain);
            i.putExtra(AnalysisActivity.EXTRA_FILTER_CATEGORY_INCOME,
                    filter.categoryIsIncome == null ? -1 : (filter.categoryIsIncome ? 1 : 0));
            i.putExtra(AnalysisActivity.EXTRA_FILTER_AMOUNT_FROM,
                    filter.amountFrom == null ? Long.MIN_VALUE : filter.amountFrom);
            i.putExtra(AnalysisActivity.EXTRA_FILTER_AMOUNT_TO,
                    filter.amountTo == null ? Long.MAX_VALUE : filter.amountTo);
            i.putExtra(AnalysisActivity.EXTRA_FILTER_DATE_FROM,
                    filter.dateFrom == null ? Long.MIN_VALUE : filter.dateFrom);
            i.putExtra(AnalysisActivity.EXTRA_FILTER_DATE_TO,
                    filter.dateTo == null ? Long.MAX_VALUE : filter.dateTo);
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

                    @Override
                    public void onFailed(String message) {
                        dismissProgress();
                        // Kein Toast: der zeigt zwei Zeilen und ist nach Sekunden weg – ausgerechnet
                        // bei den Meldungen, die man lesen muss.
                        new AppDialog(MainActivity.this)
                                .setTitle(R.string.kmy_export_stopped_title)
                                .setMessage(message)
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
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

    static boolean containsIgnoreCase(List<String> values, String needle) {
        for (String value : values) {
            if (value != null && value.equalsIgnoreCase(needle)) {
                return true;
            }
        }
        return false;
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

}
