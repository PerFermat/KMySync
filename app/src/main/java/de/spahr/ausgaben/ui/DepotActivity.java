package de.spahr.ausgaben.ui;

import de.spahr.ausgaben.net.RemotePath;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.export.ExportCoordinator;
import de.spahr.ausgaben.export.KmyAccountImport;
import de.spahr.ausgaben.export.KmyDocument;
import de.spahr.ausgaben.export.KmyExportCoordinator;
import de.spahr.ausgaben.export.KmyImporter;
import de.spahr.ausgaben.net.RemoteStorage;
import de.spahr.ausgaben.settings.Currencies;
import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Depot-Ansicht im Konto-Look: Schublade (Hamburger) + Depotname im Kopf, Menü (Export/Filter/Bestände/
 * Einstellungen) und eine per Klick durchschaltbare Saldenzeile (Depotwert → Nettoeinsatz → Gewinn/Verlust)
 * für das komplette Depot. Darunter die Wertpapiere; ein Tipp öffnet die Einzel-Historie.
 */
public class DepotActivity extends LocalizedActivity {

    /** Optional: dieses Depot anzeigen (aus der Kontenschublade). Leer = erstes Depot. */
    public static final String EXTRA_DEPOT = "depot";

    private Repository repository;
    private SettingsStore settings;
    private DrawerLayout drawerLayout;
    private MaterialToolbar toolbar;
    private AccountDrawerAdapter accountAdapter;
    /** Kopf der Kontenschublade: Gruppenauswahl, Kontenverwaltung, Suchfeld. */
    private AccountDrawerHeader drawerHeader;
    private LinearLayout container;
    private TextView saldoLabel;
    private TextView saldoValue;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefresh;

    private ActivityResultLauncher<Uri> exportTreeLauncher;
    /**
     * Dateiauswahl für PDF-Abrechnungen der Bank (siehe {@link StatementImport}). Mehrfachauswahl: eine
     * einzelne Datei geht direkt in die Erfassungsmaske, ein Stapel erst in die Erkennungsliste.
     */
    private ActivityResultLauncher<String[]> statementLauncher;
    private AlertDialog progressDialog;
    private TextView progressTextView;

    /** Gelber Import-Banner (Depot-Aktualisierung im Hintergrund), wie im Hauptbildschirm. */
    private ImportBanner importBanner;

    /** Konten der Schublade – Grundlage für „Alle Konten aktualisieren". */
    private final List<String> appAccounts = new ArrayList<>();
    /** In der App vorhandene Depots – Grundlage für „alles neu importieren". */
    private final List<String> appDepots = new ArrayList<>();

    private String depot;
    private Repository.DepotMetrics metrics;
    private java.util.List<Integer> saldoModes = new java.util.ArrayList<>();
    private int saldoIndex = 0;

    // Filter (leer = alles): Wertpapiername + Wert von/bis (Cent, null = offen).
    private String filterName = "";
    private Long filterFrom;
    private Long filterTo;
    private boolean filterShowActive = true;
    private boolean filterShowClosed = false;
    /** Zuletzt gerenderte Bestände – für die Slider-Grenzen des Wertfilters. */
    private List<Repository.DepotHolding> lastHoldings = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_depot);
        repository = new Repository(this);
        settings = new SettingsStore(this);

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

        statementLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                    if (uris != null && !uris.isEmpty()) {
                        StatementImport.openAll(this, repository, uris);
                    }
                });
        findViewById(R.id.fabReadStatement).setOnClickListener(
                v -> statementLauncher.launch(new String[]{"application/pdf"}));
        applyStatementSetting();

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        drawerLayout = findViewById(R.id.drawerLayout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(this, drawerLayout, toolbar,
                R.string.drawer_open, R.string.drawer_close);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();
        toggle.getDrawerArrowDrawable().setColor(getColor(R.color.white));

        container = findViewById(R.id.depotContainer);
        saldoLabel = findViewById(R.id.textSaldoLabel);
        saldoValue = findViewById(R.id.textBalance);
        findViewById(R.id.saldoHeader).setOnClickListener(v -> {
            if (!saldoModes.isEmpty()) {
                saldoIndex = (saldoIndex + 1) % saldoModes.size();
                showSaldo();
            }
        });

        setupDrawer();
        depot = getIntent().getStringExtra(EXTRA_DEPOT);

        // Herunterziehen: das angezeigte Depot neu aus der .kmy einlesen (nur kmy-Modus).
        ShimmerView importShimmer = findViewById(R.id.importShimmer);
        importShimmer.setColors(getColor(R.color.import_banner_bg), getColor(R.color.import_banner_shimmer));
        importBanner = new ImportBanner(findViewById(R.id.importBanner), importShimmer,
                findViewById(R.id.importStatus), findViewById(R.id.importPercent));
        swipeRefresh = findViewById(R.id.swipeRefresh);
        swipeRefresh.setOnRefreshListener(() -> {
            swipeRefresh.setRefreshing(false);
            if (depot != null && !depot.isEmpty()) {
                reimportDepot(depot);
            }
        });

        // Scroll-nach-oben-Knopf, sobald nach unten gescrollt wurde.
        android.widget.ScrollView depotScroll = findViewById(R.id.depotScroll);
        com.google.android.material.floatingactionbutton.FloatingActionButton fabScrollTop =
                findViewById(R.id.fabScrollTop);
        fabScrollTop.setOnClickListener(v -> depotScroll.smoothScrollTo(0, 0));
        depotScroll.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            if (y > 0) {
                fabScrollTop.show();
            } else {
                fabScrollTop.hide();
            }
        });
    }

    private void setupDrawer() {
        RecyclerView list = findViewById(R.id.accountList);
        list.setLayoutManager(new LinearLayoutManager(this));
        accountAdapter = new AccountDrawerAdapter(getString(R.string.account_all),
                new AccountDrawerAdapter.Listener() {
                    @Override
                    public void onSelect(String account, boolean isAll) {
                        drawerHeader.clearSearch(); // die Suche ist flüchtig
                        drawerLayout.closeDrawers();
                        openMainAccount(isAll ? "" : account);
                    }

                    @Override
                    public void onImport(String account, boolean isAll) {
                        // Wie im Hauptbildschirm: Schublade offen lassen, Abfrage und Banner
                        // erscheinen darüber.
                        onImportRequested(isAll ? null : account);
                    }

                    @Override
                    public void onDepotSelect(String d) {
                        drawerLayout.closeDrawers();
                        if (!d.equals(depot)) {
                            depot = d;
                            saldoIndex = 0;
                            render();
                        }
                    }

                    @Override
                    public void onDepotImport(String d) {
                        // Langer Druck auf das Depot: dieses Depot neu einlesen (Schublade bleibt offen).
                        reimportDepot(d);
                    }
                });
        list.setAdapter(accountAdapter);
        // In der Depot-Ansicht wirkt die Gruppe nur auf die Schublade; der Import bleibt davon unberührt.
        drawerHeader = new AccountDrawerHeader(this, repository, settings, accountAdapter,
                (groupId, label, accounts) -> { });
        // Schublade zugeschoben (auch per Wischen) beendet eine laufende Kontensuche.
        drawerLayout.addDrawerListener(new androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerClosed(@NonNull View drawerView) {
                drawerHeader.clearSearch();
            }
        });
        repository.getAccountNames(names -> {
            appAccounts.clear();
            appAccounts.addAll(names);
        });
        findViewById(R.id.addAccount).setOnClickListener(v -> {
            drawerLayout.closeDrawers();
            openMainAccount("");
        });
        loadDrawerAccounts();
        repository.getDepots(depots -> {
            appDepots.clear();
            appDepots.addAll(depots);
        });
    }

    /** Kontenliste der Schublade laden (Kontenart-Blöcke, Gruppenfilter und Reihenfolge). */
    private void loadDrawerAccounts() {
        drawerHeader.reload();
    }

    private void setTitleText(String title) {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(title);
        } else {
            toolbar.setTitle(title);
        }
    }

    /** Wechselt zum Hauptbildschirm und wählt dort ein Konto (leer = „Alle Konten"). */
    private void openMainAccount(String account) {
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra(MainActivity.EXTRA_SELECT_ACCOUNT, account);
        startActivity(i);
        // Gegenstück zum Depot-Aufruf: die Kontoansicht ersetzt das Depot, statt darüberzuliegen.
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Depotwahl aus der Kontenschublade übernehmen, wenn diese Ansicht wiederverwendet wird.
        String d = intent.getStringExtra(EXTRA_DEPOT);
        if (d != null && !d.equals(depot)) {
            depot = d;
            saldoIndex = 0;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadDrawerAccounts(); // Sortierung und Gruppen können in der Verwaltung geändert worden sein
        applyStatementSetting();
        render();
    }

    /**
     * Beide Wege zur Abrechnungs-Erkennung hängen an der Einstellung: der Knopf und das Regelsymbol.
     * Nachgezogen wird bei jedem Erscheinen, damit ein Wechsel in den Einstellungen sofort greift und
     * nicht erst beim nächsten Öffnen des Depots.
     *
     * <p>Ausblenden ist kein Löschen: die gelernten Vorlagen bleiben liegen und stehen wieder bereit,
     * sobald jemand die Erkennung erneut einschaltet.</p>
     */
    private void applyStatementSetting() {
        findViewById(R.id.fabReadStatement).setVisibility(
                settings.isStatementEnabled() ? View.VISIBLE : View.GONE);
        invalidateOptionsMenu();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Die Kontensuche ist flüchtig: sie überlebt das Verlassen der App nicht.
        if (drawerHeader != null) {
            drawerHeader.clearSearch();
        }
    }

    private void render() {
        if (depot == null || depot.isEmpty()) {
            repository.getDepots(depots -> {
                if (!depots.isEmpty()) {
                    depot = depots.get(0);
                    render();
                } else {
                    setTitleText(getString(R.string.depot_title));
                    container.removeAllViews();
                    TextView empty = new TextView(this);
                    empty.setText(R.string.depot_empty);
                    empty.setPadding(0, 24, 0, 0);
                    container.addView(empty);
                }
            });
            return;
        }
        setTitleText(depot);
        accountAdapter.setSelectedDepot(depot);
        repository.getDepotMetrics(depot, m -> {
            metrics = m;
            saldoModes = DepotSaldo.modes(m);
            if (saldoIndex >= saldoModes.size()) {
                saldoIndex = 0;
            }
            showSaldo();
        });
        repository.getDepotHoldings(depot, this::renderHoldings);
    }

    private void renderHoldings(List<Repository.DepotHolding> holdings) {
        lastHoldings = holdings;
        container.removeAllViews();
        boolean any = false;
        for (Repository.DepotHolding h : holdings) {
            boolean closed = Math.abs(h.shares) < de.spahr.ausgaben.util.SecurityAmounts.SHARE_EPSILON;
            boolean visible = closed ? filterShowClosed : filterShowActive;
            if (!visible || !matchesFilter(h)) {
                continue;
            }
            any = true;
            String left = h.name + (h.symbol.isEmpty() ? "" : "  ·  " + h.symbol)
                    + "\n" + shares(h.shares) + " × " + price(h.price);
            addRow(left, money(h.valueCents), v -> openHistory(h));
        }
        if (!any) {
            TextView empty = new TextView(this);
            empty.setText(isFilterActive() ? R.string.depot_no_match : R.string.depot_empty);
            empty.setPadding(0, 24, 0, 0);
            container.addView(empty);
        }
    }

    private boolean matchesFilter(Repository.DepotHolding h) {
        if (!filterName.isEmpty()
                && !h.name.toLowerCase(Locale.getDefault()).contains(filterName.toLowerCase(Locale.getDefault()))) {
            return false;
        }
        if (filterFrom != null && h.valueCents < filterFrom) {
            return false;
        }
        return filterTo == null || h.valueCents <= filterTo;
    }

    private boolean isFilterActive() {
        return !filterName.isEmpty() || filterFrom != null || filterTo != null
                || !filterShowActive || filterShowClosed;
    }

    // ---- Saldenzeile ----

    private void showSaldo() {
        if (metrics == null || saldoModes.isEmpty()) {
            return;
        }
        int mode = saldoModes.get(saldoIndex % saldoModes.size());
        DepotSaldo.apply(this, saldoLabel, saldoValue, metrics, mode,
                getString(R.string.depot_value_label));
    }

    // ---- Menü ----

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.depot_menu, menu);
        setMenuTitle(menu, R.id.action_export, R.string.action_export);
        setMenuTitle(menu, R.id.action_import_all, R.string.action_import_all);
        setMenuTitle(menu, R.id.action_filter, R.string.action_filter);
        setMenuTitle(menu, R.id.action_reports, R.string.action_reports);
        setMenuTitle(menu, R.id.action_analysis, R.string.action_analysis);
        setMenuTitle(menu, R.id.action_statement_rules, R.string.statement_rules);
        MenuItem rules = menu.findItem(R.id.action_statement_rules);
        if (rules != null) {
            rules.setVisible(settings.isStatementEnabled());
        }
        setMenuTitle(menu, R.id.action_categories, R.string.action_categories);
        setMenuTitle(menu, R.id.action_balance, R.string.action_balance);
        setMenuTitle(menu, R.id.action_budget, R.string.action_budget);
        setMenuTitle(menu, R.id.action_scheduled, R.string.action_scheduled);
        setMenuTitle(menu, R.id.action_switch_profile, R.string.action_switch_profile);
        setMenuTitle(menu, R.id.action_settings, R.string.action_settings);
        MenuIcons.tintOverflow(this, menu);
        return true;
    }

    /**
     * „Profil wechseln" nur, solange es überhaupt etwas zum Wechseln gibt — wie im Hauptmenü.
     *
     * <p>In {@code onPrepareOptionsMenu} und nicht in {@code onCreateOptionsMenu}: Die Zahl der Profile
     * kann sich ändern, während diese Maske im Hintergrund steht.</p>
     */
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem switchProfile = menu.findItem(R.id.action_switch_profile);
        if (switchProfile != null) {
            switchProfile.setVisible(
                    new de.spahr.ausgaben.settings.ProfileManager(this).getProfiles().size() > 1);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    private void setMenuTitle(Menu menu, int itemId, int stringId) {
        MenuItem item = menu.findItem(itemId);
        if (item != null) {
            item.setTitle(getString(stringId));
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_export) {
            // Export direkt hier ausführen – die Depot-Ansicht bleibt geöffnet (kein Wechsel zur Liste).
            doExport();
            return true;
        } else if (id == R.id.action_import_all) {
            // Derselbe Weg wie der lange Druck auf „Alle Konten" in der Schublade – samt
            // Sicherheitsfrage. null heißt: Konten, Depots und geplante Buchungen in einem Zug.
            onImportRequested(null);
            return true;
        } else if (id == R.id.action_analysis) {
            if (depot != null && !depot.isEmpty()) {
                Intent i = new Intent(this, DepotChartActivity.class);
                i.putExtra(DepotChartActivity.EXTRA_DEPOT, depot);
                startActivity(i);
            }
            return true;
        } else if (id == R.id.action_filter) {
            showFilterDialog();
            return true;
        } else if (id == R.id.action_statement_rules) {
            Intent i = new Intent(this, StatementRulesActivity.class);
            i.putExtra(StatementRulesActivity.EXTRA_DEPOT, depot);
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

    // ---- Export (in der Depot-Ansicht, wie in MainActivity) ----

    private void doExport() {
        if (settings.isKmyMode()) {
            runKmyExport();
            return;
        }
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
                        Toast.makeText(DepotActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void runExport() {
        Toast.makeText(this, R.string.export_running, Toast.LENGTH_SHORT).show();
        String tree = settings.hasRemoteConfig() ? null : settings.getLocalExportTree();
        new ExportCoordinator(this, repository, settings, tree).exportUnexported((message, refreshNeeded) ->
                Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    private void showProgress(String text) {
        if (progressDialog != null) {
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
     * Den Fortschrittsdialog schließen — auch wenn sein Fenster schon weg ist.
     *
     * <p>Der Weg aus dem Hintergrund ist seit {@link Ui#post} geprüft, aber das ist nicht der einzige:
     * Geschlossen wird auch aus Lebenszyklus-Rückrufen, und dort ist die Maske definitionsgemäß gerade
     * am Verschwinden. Ein {@code dismiss()} auf ein abgehängtes Fenster wirft
     * {@code IllegalArgumentException: View not attached to window manager} — ein Absturz beim
     * Aufräumen, also an der denkbar unpassendsten Stelle. Die Felder werden in jedem Fall geleert,
     * auch wenn das Schließen schiefging; der Dialog ist dann ohnehin mit seinem Fenster fort.</p>
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
            android.util.Log.w("DepotActivity",
                    "Fortschrittsdialog ließ sich nicht mehr schließen – sein Fenster war schon fort",
                    fensterSchonFort);
        }
    }

    // ---- Konten aktualisieren (langer Druck in der Schublade – wie im Hauptbildschirm) ----

    /**
     * Langer Druck auf ein Konto bzw. „Alle Konten": ersetzt die Buchungen dieser Konten ohne Rückfrage
     * aus der .kmy. Läuft hier im Depot ab, damit die Schublade offen bleibt.
     *
     * @param account einzelnes Konto oder {@code null} für „Alle Konten"
     */
    private void onImportRequested(final String account) {
        if (!settings.isKmyMode() || !settings.hasRemoteConfig()) {
            Toast.makeText(this, R.string.export_no_config, Toast.LENGTH_LONG).show();
            return;
        }
        if (settings.getKmyPath().isEmpty()) {
            Toast.makeText(this, R.string.kmy_path_missing, Toast.LENGTH_LONG).show();
            return;
        }
        // Ohne Rückfrage: die KMyMoney-Datei ist führend.
        runAccountImport(account);
    }

    /** Konto-Import mit dem gelben Banner dieser Ansicht (gleiche Logik wie im Hauptbildschirm). */
    private void runAccountImport(final String account) {
        importBanner.start(getString(R.string.import_running_banner));
        // „Alle Konten" (account == null) heißt: Konten, Depots und geplante Buchungen in einem Zug.
        KmyAccountImport.start(this, settings, repository, appAccounts, account,
                account == null ? appDepots : java.util.Collections.emptyList(), account == null,
                new KmyAccountImport.Ui() {
                    @Override
                    public de.spahr.ausgaben.util.ProgressListener phase(String label, int from, int to) {
                        return importBanner.phase(label, from, to);
                    }

                    @Override
                    public void noMatchingAccount() {
                        post(() -> {
                            importBanner.finishNow();
                            Toast.makeText(DepotActivity.this, R.string.kmy_account_not_found,
                                    Toast.LENGTH_LONG).show();
                        });
                    }

                    @Override
                    public void failed(Exception e) {
                        post(() -> {
                            importBanner.finishNow();
                            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                            Toast.makeText(DepotActivity.this, getString(R.string.import_failed, msg),
                                    Toast.LENGTH_LONG).show();
                        });
                    }

                    @Override
                    public void finished() {
                        // Das Depot selbst ändert sich nicht, aber die Kontenliste der Schublade kann.
                        loadDrawerAccounts();
                        completeImport();
                    }
                });
    }

    // ---- Depot neu einlesen (Herunterziehen / langer Druck auf das Depot) ----

    /**
     * Lädt die .kmy und aktualisiert genau dieses Depot – im Hintergrund mit dem gelben Fortschrittsbanner
     * (wie im Hauptbildschirm); die Oberfläche bleibt bedienbar, nur bei Fehlern kommt eine Meldung.
     */
    private void reimportDepot(String depotName) {
        if (!settings.isKmyMode() || !settings.hasRemoteConfig()) {
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
                repository.replaceDepotImport(depotName, data.securities, data.transactions, data.prices,
                        importBanner.phase(label, budget.from(schreiben), budget.to(schreiben)),
                        () -> post(this::completeImport));
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                post(() -> {
                    importBanner.finishNow();
                    // Mit Grund – „import_failed" enthält einen Platzhalter.
                    Toast.makeText(this, getString(R.string.import_failed, msg),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /** Fertig: 100 % kurz zeigen, Banner ausblenden und Depot neu zeichnen. */
    private void completeImport() {
        importBanner.finish();
        render();
    }

    private void showFilterDialog() {
        View view = android.view.LayoutInflater.from(this)
                .inflate(R.layout.dialog_depot_filter, null, false);
        final com.google.android.material.textfield.TextInputEditText name =
                view.findViewById(R.id.depotFilterName);
        final ZeroMarkSlider slider = view.findViewById(R.id.depotFilterSlider);
        final com.google.android.material.textfield.TextInputEditText from =
                view.findViewById(R.id.depotFilterFrom);
        final com.google.android.material.textfield.TextInputEditText to =
                view.findViewById(R.id.depotFilterTo);
        final com.google.android.material.checkbox.MaterialCheckBox showActive =
                view.findViewById(R.id.depotFilterShowActive);
        final com.google.android.material.checkbox.MaterialCheckBox showClosed =
                view.findViewById(R.id.depotFilterShowClosed);
        AmountField.prepareNumber(from);
        AmountField.prepareNumber(to);
        name.setText(filterName);
        showActive.setChecked(filterShowActive);
        showClosed.setChecked(filterShowClosed);

        // Wert-Range aus den aktuellen Beständen (nur sichtbare Positionen zählen); der Regler läuft
        // über die Ränge, damit ein großer Posten ihn nicht aufzieht (siehe AmountRange).
        java.util.List<Long> werte = new ArrayList<>();
        for (Repository.DepotHolding h : lastHoldings) {
            boolean closed = Math.abs(h.shares) < de.spahr.ausgaben.util.SecurityAmounts.SHARE_EPSILON;
            if (closed ? filterShowClosed : filterShowActive) {
                werte.add(h.valueCents);
            }
        }
        final long[] sortedCents = new long[werte.size()];
        for (int i = 0; i < sortedCents.length; i++) {
            sortedCents[i] = werte.get(i);
        }
        java.util.Arrays.sort(sortedCents);
        final boolean hasRange = sortedCents.length > 1
                && sortedCents[0] < sortedCents[sortedCents.length - 1];
        final AmountRange amountRange = hasRange
                ? AmountRange.attach(slider, from, to, sortedCents, filterFrom, filterTo, this::money)
                : null;
        if (!hasRange) {
            slider.setValueFrom(0f);
            slider.setValueTo(1f);
            slider.setValues(0f, 1f);
            slider.setEnabled(false);
            if (filterFrom != null) from.setText(centsPlain(filterFrom));
            if (filterTo != null) to.setText(centsPlain(filterTo));
        }

        new AppDialog(this)
                .setTitle(R.string.action_filter)
                .setView(view)
                .setNeutralButton(R.string.filter_reset, (d, w) -> {
                    filterName = "";
                    filterFrom = null;
                    filterTo = null;
                    filterShowActive = true;
                    filterShowClosed = false;
                    render();
                })
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    filterName = name.getText() == null ? "" : name.getText().toString().trim();
                    filterShowActive = showActive.isChecked();
                    filterShowClosed = showClosed.isChecked();
                    if (amountRange != null) {
                        if (amountRange.isFullRange()) {
                            filterFrom = null;
                            filterTo = null;
                        } else {
                            filterFrom = amountRange.getFromCents();
                            filterTo = amountRange.getToCents();
                        }
                    } else {
                        filterFrom = parseEuros(from.getText() == null ? "" : from.getText().toString());
                        filterTo = parseEuros(to.getText() == null ? "" : to.getText().toString());
                    }
                    render();
                })
                .show();
    }

    /** Cent → „12,50" (ohne Währung), für die von/bis-Eingabefelder. */
    private static String centsPlain(long cents) {
        return de.spahr.ausgaben.settings.MoneyFormat.plain(cents);
    }

    /** „12,50" bzw. „12" → Cent; leer/ungültig → null. */
    private static Long parseEuros(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim().replace('.', ',');
        if (t.isEmpty()) {
            return null;
        }
        try {
            String[] parts = t.split(",", 2);
            long euros = parts[0].isEmpty() ? 0 : Long.parseLong(parts[0]);
            long cents = 0;
            if (parts.length == 2 && !parts[1].isEmpty()) {
                String c = (parts[1] + "00").substring(0, 2);
                cents = Long.parseLong(c);
            }
            return euros * 100 + cents;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- Wertpapierliste ----

    private void openHistory(Repository.DepotHolding h) {
        Intent i = new Intent(this, SecurityHistoryActivity.class);
        i.putExtra(SecurityHistoryActivity.EXTRA_DEPOT, depot);
        i.putExtra(SecurityHistoryActivity.EXTRA_KMY_ID, h.kmyId);
        i.putExtra(SecurityHistoryActivity.EXTRA_NAME, h.name);
        i.putExtra(SecurityHistoryActivity.EXTRA_SECURITY_VALUE, h.valueCents);
        startActivity(i);
    }

    private void addRow(String label, String value, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 14, 0, 14);
        row.setClickable(true);
        row.setOnClickListener(onClick);
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(tv.resourceId);

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextSize(15f);
        name.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView val = new TextView(this);
        val.setText(value);
        val.setTextSize(15f);
        val.setGravity(Gravity.END);
        val.setTypeface(android.graphics.Typeface.MONOSPACE);

        row.addView(name);
        row.addView(val);
        container.addView(row);
    }

    private String money(long cents) {
        return de.spahr.ausgaben.settings.MoneyFormat.display(cents, Currencies.getDefault());
    }

    /** Stückzahl im eingestellten Zahlenformat (siehe {@code MoneyFormat.SHARE_DECIMALS}). */
    private static String shares(double v) {
        return de.spahr.ausgaben.settings.MoneyFormat.shares(v);
    }

    /** Kurs: bis zu vier Nachkommastellen, im eingestellten Zahlenformat. */
    private static String price(double v) {
        return de.spahr.ausgaben.settings.MoneyFormat.decimal(v, 0, 4);
    }
}
