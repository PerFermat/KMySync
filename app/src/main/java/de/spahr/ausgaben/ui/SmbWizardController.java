package de.spahr.ausgaben.ui;

import android.app.Activity;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.net.smb.SmbDiscovery;
import de.spahr.ausgaben.net.smb.SmbErrors;
import de.spahr.ausgaben.net.smb.SmbSessions;
import de.spahr.ausgaben.net.smb.SmbShares;
import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Steuert den SMB-Einrichtungsassistenten aus {@code view_smb_wizard.xml}: Server suchen → anmelden →
 * Freigabe wählen → speichern. Den Zielordner wählt danach wie bisher der „Durchsuchen"-Button des
 * jeweiligen Feldes. Bewusst keine Activity, damit Einstellungen und Onboarding dieselbe Bedienung
 * teilen; die Activity liefert nur den Speicherpfad und den Rückfall auf die manuelle Eingabe.
 */
public class SmbWizardController {

    /** Anbindung an die Activity, in der der Assistent steckt. */
    public interface Host {
        /** Fertige Konfiguration übernehmen und speichern (URL im Format {@code smb://Host/Freigabe}). */
        void onSmbConfigured(String url, String user, String password);

        /** Der Benutzer will die Adresse selbst eintippen: klassische Felder zeigen. */
        void onSmbManualRequested();
    }

    private final Activity activity;
    private final SettingsStore settings;
    private final Host host;

    private final View root;
    private final View stepSearch;
    private final View stepLogin;
    private final View stepShares;
    private final View stepDone;
    private final View searchProgress;
    private final TextView searchEmpty;
    private final RadioGroup serverList;
    private final RadioGroup shareList;
    private final TextInputEditText editShare;
    private final TextInputEditText editPort;
    private final TextInputEditText editUser;
    private final TextInputEditText editPassword;
    private final TextView loginTitle;
    private final TextView doneText;
    private final View busy;
    private final TextView busyText;
    private final TextView errorText;
    private final MaterialButton btnToLogin;

    /**
     * Wird gerufen, wenn der Assistent einen anderen Schritt zeigt. Die Maske entscheidet daraufhin
     * neu, was <b>um</b> den Assistenten herum sichtbar ist – „Verbindung testen" etwa gehört an den
     * Schluss, wenn Freigabe und Zugangsdaten feststehen, und vorher nirgendwo hin.
     */
    private Runnable onStepChanged;

    private SmbDiscovery discovery;
    private final List<SmbDiscovery.Server> servers = new ArrayList<>();
    private String selectedHost = "";
    private String selectedName = "";
    /** Port des gewählten Servers; 0 bedeutet den Standardport 445. */
    private int selectedPort;
    /** Zuletzt selbst ins Portfeld geschriebener Wert – nur der darf wieder ersetzt werden. */
    private int prefilledPort;
    private String selectedWorkgroup = "";
    private String selectedShare = "";
    private boolean started;
    private boolean manual;
    /** Beim nächsten Einblenden direkt suchen, auch wenn schon eine SMB-Verbindung gespeichert ist. */
    private boolean forceSearch;

    public SmbWizardController(Activity activity, View root, SettingsStore settings, Host host) {
        this.activity = activity;
        this.root = root;
        this.settings = settings;
        this.host = host;

        stepSearch = root.findViewById(R.id.smbStepSearch);
        stepLogin = root.findViewById(R.id.smbStepLogin);
        stepShares = root.findViewById(R.id.smbStepShares);
        stepDone = root.findViewById(R.id.smbStepDone);
        searchProgress = root.findViewById(R.id.smbSearchProgress);
        searchEmpty = root.findViewById(R.id.smbSearchEmpty);
        serverList = root.findViewById(R.id.smbServerList);
        shareList = root.findViewById(R.id.smbShareList);
        editShare = root.findViewById(R.id.smbEditShare);
        editPort = root.findViewById(R.id.smbEditPort);
        editUser = root.findViewById(R.id.smbEditUser);
        editPassword = root.findViewById(R.id.smbEditPassword);
        loginTitle = root.findViewById(R.id.smbLoginTitle);
        doneText = root.findViewById(R.id.smbDoneText);
        busy = root.findViewById(R.id.smbBusy);
        busyText = root.findViewById(R.id.smbBusyText);
        errorText = root.findViewById(R.id.smbError);
        btnToLogin = root.findViewById(R.id.smbBtnToLogin);

        root.<MaterialButton>findViewById(R.id.smbBtnRescan).setOnClickListener(v -> search());
        btnToLogin.setOnClickListener(v -> showLogin());
        root.<MaterialButton>findViewById(R.id.smbBtnManual).setOnClickListener(v -> {
            manual = true;
            setVisible(false);
            host.onSmbManualRequested();
        });
        root.<MaterialButton>findViewById(R.id.smbBtnBackToSearch).setOnClickListener(v -> show(stepSearch));
        root.<MaterialButton>findViewById(R.id.smbBtnConnect).setOnClickListener(v -> connect());
        root.<MaterialButton>findViewById(R.id.smbBtnBackToLogin).setOnClickListener(v -> show(stepLogin));
        root.<MaterialButton>findViewById(R.id.smbBtnSave).setOnClickListener(v -> saveConfig());
        root.<MaterialButton>findViewById(R.id.smbBtnRestart).setOnClickListener(v -> {
            restart();
            setVisible(true);
        });
    }

    /**
     * Zeigt oder verbirgt den Assistenten. Beim ersten Einblenden startet die Suche automatisch – es sei
     * denn, es ist schon eine SMB-Verbindung eingerichtet; dann erscheint zuerst die Zusammenfassung.
     */
    public void setVisible(boolean visible) {
        root.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) {
            stopDiscovery();
            return;
        }
        if (started) {
            return;
        }
        started = true;
        if (!forceSearch && settings.isSmbServer() && settings.hasRemoteConfig()) {
            showDone(settings.getUrl());
        } else {
            forceSearch = false;
            search();
        }
    }

    /** Die Maske hängt sich hier ein, um auf Schrittwechsel zu reagieren (siehe {@link #onStepChanged}). */
    public void setOnStepChanged(Runnable listener) {
        this.onStepChanged = listener;
    }

    /**
     * true, wenn der Assistent bei seiner Zusammenfassung steht – Freigabe gewählt, Zugangsdaten
     * übernommen. Erst dann gibt es überhaupt etwas zu testen.
     */
    public boolean isDone() {
        return stepDone.getVisibility() == View.VISIBLE;
    }

    /** true, sobald der Benutzer „Server manuell eingeben" gewählt hat. */
    public boolean isManual() {
        return manual;
    }

    /** Assistent statt manueller Eingabe (z. B. nach einem Wechsel des Server-Typs). */
    public void resetManual() {
        manual = false;
    }

    /** Zurück zum Assistenten: beim nächsten Einblenden beginnt er wieder mit der Serversuche. */
    public void restart() {
        manual = false;
        started = false;
        forceSearch = true;
    }

    public void stopDiscovery() {
        if (discovery != null) {
            discovery.cancel();
            discovery = null;
        }
    }

    // --------------------------------------------------------- Schritt 1

    private void search() {
        show(stepSearch);
        stopDiscovery();
        servers.clear();
        serverList.removeAllViews();
        btnToLogin.setEnabled(false);
        searchEmpty.setVisibility(View.GONE);
        searchProgress.setVisibility(View.VISIBLE);
        for (String[] known : settings.getKnownSmbHosts()) {
            addServer(new SmbDiscovery.Server(known[0], known[1], known[2],
                    Math.max(0, portOf(known[3]))));
        }
        discovery = new SmbDiscovery(activity);
        discovery.start(new SmbDiscovery.Listener() {
            @Override
            public void onServer(SmbDiscovery.Server server) {
                if (activity.isFinishing()) {
                    return;
                }
                addServer(server);
            }

            @Override
            public void onFinished() {
                if (activity.isFinishing()) {
                    return;
                }
                searchProgress.setVisibility(View.GONE);
                searchEmpty.setVisibility(servers.isEmpty() ? View.VISIBLE : View.GONE);
                List<String[]> keep = new ArrayList<>();
                for (SmbDiscovery.Server s : servers) {
                    keep.add(new String[]{s.name, s.host, s.workgroup, portText(s.port)});
                }
                settings.setKnownSmbHosts(keep);
            }
        });
    }

    /** Fügt einen Treffer hinzu oder ersetzt den Namen eines bereits gelisteten Hosts. */
    private void addServer(SmbDiscovery.Server server) {
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).host.equals(server.host)) {
                servers.set(i, server);
                ((MaterialRadioButton) serverList.getChildAt(i)).setText(serverLabel(server));
                return;
            }
        }
        servers.add(server);
        searchEmpty.setVisibility(View.GONE);
        MaterialRadioButton rb = new MaterialRadioButton(activity);
        rb.setId(View.generateViewId());
        rb.setText(serverLabel(server));
        rb.setMinHeight((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48,
                activity.getResources().getDisplayMetrics()));
        rb.setGravity(Gravity.CENTER_VERTICAL);
        final int index = servers.size() - 1;
        rb.setOnClickListener(v -> {
            selectedHost = servers.get(index).host;
            selectedName = servers.get(index).name;
            selectedWorkgroup = servers.get(index).workgroup;
            selectedPort = servers.get(index).port;
            btnToLogin.setEnabled(true);
        });
        serverList.addView(rb);
    }

    /** Name groß, darunter Host/IP (mit Port, falls abweichend) und – falls bekannt – die Arbeitsgruppe. */
    private CharSequence serverLabel(SmbDiscovery.Server server) {
        String address = server.port > 0 ? server.host + ":" + server.port : server.host;
        String detail = server.workgroup.isEmpty() ? address : address + " · " + server.workgroup;
        if (server.name.equals(server.host) && server.workgroup.isEmpty() && server.port == 0) {
            return server.host;
        }
        String title = server.name.equals(server.host) ? server.host : server.name;
        SpannableString s = new SpannableString(title + "\n" + detail);
        s.setSpan(new RelativeSizeSpan(0.8f), title.length() + 1, s.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    // --------------------------------------------------------- Schritt 2

    private void showLogin() {
        if (selectedHost.isEmpty()) {
            return;
        }
        stopDiscovery();
        loginTitle.setText(selectedName.isEmpty() ? selectedHost : selectedName);
        // Den per mDNS gemeldeten Port vorbelegen – aber nur, solange das Feld leer ist oder noch die
        // Vorbelegung des zuvor gewählten Servers enthält; eine eigene Eingabe bleibt stehen.
        String typed = textOf(editPort);
        if (typed.isEmpty() || typed.equals(portText(prefilledPort))) {
            editPort.setText(portText(selectedPort));
            prefilledPort = selectedPort;
        }
        prefillDomain();
        show(stepLogin);
    }

    /**
     * Gehört der Server einer anderen Arbeitsgruppe/Domäne als der üblichen an, wird das Benutzerfeld mit
     * {@code DOMÄNE\} vorbelegt – in einer Windows-Domäne scheitert die Anmeldung sonst leicht, obwohl das
     * Passwort stimmt. Eine eigene Eingabe wird nie überschrieben.
     */
    private void prefillDomain() {
        if (selectedWorkgroup.isEmpty() || !textOf(editUser).isEmpty()
                || selectedWorkgroup.equalsIgnoreCase("WORKGROUP")) {
            return;
        }
        editUser.setText(selectedWorkgroup + "\\");
        editUser.setSelection(editUser.getText() == null ? 0 : editUser.getText().length());
    }

    private void connect() {
        final String user = textOf(editUser);
        final String password = textOf(editPassword);
        final String h = selectedHost;
        int typed = portOf(textOf(editPort));
        if (typed < 0) {
            showError(activity.getString(R.string.smb_err_port));
            return;
        }
        selectedPort = typed;
        final int p = selectedPort;
        setBusy(R.string.smb_connecting);
        new Thread(() -> {
            try {
                final int used;
                try (SmbSessions.Link link = SmbSessions.open(h, p, true)) {
                    SmbSessions.authenticate(link.connection, user, password);
                    used = link.usedPort;
                }
                post(() -> {
                    // Kam die Verbindung nur über 445 zustande, gilt ab jetzt dieser Port – sonst
                    // stünde in der gespeicherten Adresse weiter der (falsche) gemeldete Port.
                    if (used != p) {
                        // 445 ist der Standard und bleibt aus Adresse und Feld heraus.
                        selectedPort = used == SmbSessions.DEFAULT_PORT ? 0 : used;
                        prefilledPort = selectedPort;
                        editPort.setText(portText(selectedPort));
                        android.widget.Toast.makeText(activity,
                                activity.getString(R.string.smb_port_corrected, used),
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                    loadShares(user, password);
                });
            } catch (Exception e) {
                post(() -> fail(SmbErrors.Step.LOGIN, e, stepLogin));
            }
        }, "smb-login").start();
    }

    // --------------------------------------------------------- Schritt 3

    private void loadShares(final String user, final String password) {
        setBusy(R.string.smb_shares_loading);
        shareList.removeAllViews();
        selectedShare = "";
        editShare.setText("");
        final String h = selectedHost;
        final int p = selectedPort;
        new Thread(() -> {
            List<String> shares;
            String problem = null;
            try {
                shares = SmbShares.list(h, p, user, password);
            } catch (Exception e) {
                shares = new ArrayList<>();
                problem = SmbErrors.messageFor(activity, SmbErrors.Step.SHARE, e);
                // Der Benutzer sieht nur die verständliche Meldung; die Ursache landet im Log.
                android.util.Log.w("SmbWizard", "Freigaben von " + h + " nicht lesbar", e);
            }
            final List<String> result = shares;
            final String message = problem;
            post(() -> {
                clearBusy();
                show(stepShares);
                for (String share : result) {
                    addShare(share);
                }
                if (result.isEmpty()) {
                    showError(message == null ? activity.getString(R.string.smb_shares_none) : message);
                }
            });
        }, "smb-shares").start();
    }

    private void addShare(String share) {
        MaterialRadioButton rb = new MaterialRadioButton(activity);
        rb.setId(View.generateViewId());
        rb.setText(share);
        rb.setMinHeight((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48,
                activity.getResources().getDisplayMetrics()));
        rb.setOnClickListener(v -> {
            selectedShare = share;
            editShare.setText(share);   // Auswahl landet im Feld und lässt sich dort noch ändern
            // Sofort übernehmen: „.kmy auswählen" arbeitet mit den Feldern der Activity und würde
            // sonst noch die alte Adresse benutzen – bis dahin ein Fehler ohne erkennbaren Grund.
            apply();
        });
        shareList.addView(rb);
    }

    // ------------------------------------------------------------ Speichern

    private void saveConfig() {
        String url = apply();
        if (url.isEmpty()) {
            showError(activity.getString(R.string.smb_err_share));
            return;
        }
        showDone(url);
    }

    /**
     * Übernimmt Host/Port/Freigabe in die Einstellungen und liefert die gebaute Adresse (leer, wenn
     * noch keine Freigabe feststeht). Wird sowohl beim Antippen einer Freigabe als auch beim
     * „Speichern" aufgerufen – das Feld gewinnt, es kann von Hand überschrieben werden.
     */
    private String apply() {
        selectedShare = textOf(editShare).isEmpty() ? selectedShare : textOf(editShare);
        if (selectedShare.isEmpty()) {
            return "";
        }
        String address = selectedPort > 0 ? selectedHost + ":" + selectedPort : selectedHost;
        String url = "smb://" + address + "/" + selectedShare;
        host.onSmbConfigured(url, textOf(editUser), textOf(editPassword));
        return url;
    }

    private void showDone(String url) {
        clearBusy();
        doneText.setText(activity.getString(R.string.smb_saved, url));
        show(stepDone);
    }

    // ------------------------------------------------------------- Helfer

    private void show(View step) {
        clearBusy();
        stepSearch.setVisibility(step == stepSearch ? View.VISIBLE : View.GONE);
        stepLogin.setVisibility(step == stepLogin ? View.VISIBLE : View.GONE);
        stepShares.setVisibility(step == stepShares ? View.VISIBLE : View.GONE);
        stepDone.setVisibility(step == stepDone ? View.VISIBLE : View.GONE);
        stepChanged();
    }

    /** Blendet alle Schritte aus und zeigt nur die Fortschrittszeile mit dem passenden Text. */
    private void setBusy(int textRes) {
        errorText.setVisibility(View.GONE);
        stepSearch.setVisibility(View.GONE);
        stepLogin.setVisibility(View.GONE);
        stepShares.setVisibility(View.GONE);
        stepDone.setVisibility(View.GONE);
        busyText.setText(textRes);
        busy.setVisibility(View.VISIBLE);
        stepChanged();
    }

    private void stepChanged() {
        if (onStepChanged != null) {
            onStepChanged.run();
        }
    }

    private void clearBusy() {
        busy.setVisibility(View.GONE);
        errorText.setVisibility(View.GONE);
    }

    private void fail(SmbErrors.Step step, Exception e, View backTo) {
        show(backTo);
        showError(SmbErrors.messageFor(activity, step, e));
    }

    private void showError(String message) {
        errorText.setText(message);
        errorText.setVisibility(View.VISIBLE);
    }

    private void post(Runnable r) {
        if (!activity.isFinishing()) {
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing()) {
                    r.run();
                }
            });
        }
    }

    /**
     * Portnummer aus einer Eingabe: {@code 0} bei leerem Feld oder dem Standardport 445 (dann bleibt er
     * aus Adresse und Aufruf heraus), {@code -1} bei einer unbrauchbaren Eingabe.
     */
    private static int portOf(String text) {
        String t = text.trim();
        if (t.isEmpty()) {
            return 0;
        }
        try {
            int value = Integer.parseInt(t);
            if (value < 1 || value > 65535) {
                return -1;
            }
            return value == 445 ? 0 : value;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Portnummer für Anzeige und Adresse; der Standardport bleibt leer. */
    private static String portText(int port) {
        return port > 0 ? String.valueOf(port) : "";
    }

    private static String textOf(TextInputEditText field) {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }
}
