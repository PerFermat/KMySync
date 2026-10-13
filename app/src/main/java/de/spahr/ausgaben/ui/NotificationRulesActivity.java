package de.spahr.ausgaben.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.notify.NotificationBuffer;
import de.spahr.ausgaben.notify.NotificationRunner;
import de.spahr.ausgaben.settings.Currencies;
import de.spahr.ausgaben.settings.MoneyFormat;
import de.spahr.ausgaben.statement.NotificationRuleForm;
import de.spahr.ausgaben.statement.NotificationSource;
import de.spahr.ausgaben.statement.NotificationTemplate;

/**
 * Die gelernten Regeln für Buchungen aus Benachrichtigungen von Hand nachbessern — das Gegenstück zu
 * {@link StatementRulesActivity} für die Depots.
 *
 * <p>Der Lerner nimmt als Beschriftung des Betrags das Wort davor, und das ist bei „Sie haben kontaktlos
 * 9,12 € bezahlt" eben „kontaktlos". Eine Zahlung im Netz liest diese Regel nicht mehr. Hier lässt sich
 * die Kette verlängern — „kontaktlos", ersatzweise „online", ersatzweise „haben" —, statt für jede
 * Zahlungsart einmal neu zu lernen.</p>
 *
 * <p>Gearbeitet wird an einer <b>Testmeldung</b>: eingetippt oder aus dem genommen, was von der App
 * gerade aufgehoben ist. Unter ihr steht, was die Regel in ihrem jetzigen Stand daraus liest, und zwar
 * nach jeder Änderung neu.</p>
 *
 * <p>Neue Regeln entstehen hier nicht — die entstehen aus einer von Hand angelegten Buchung. Von Hand
 * wird nachgebessert, nicht angefangen.</p>
 */
public class NotificationRulesActivity extends LocalizedActivity {

    /** Der Paketname der App, deren Regeln gezeigt werden. */
    public static final String EXTRA_PACKAGE = "package";

    private static final String STATE_INDEX = "s_index";
    private static final String STATE_TEST = "s_test";

    private Repository repository;
    private String packageName = "";
    private final List<NotificationTemplate> rules = new ArrayList<>();
    private int current = -1;
    /** Die wievielte aufgehobene Meldung zuletzt als Testmeldung eingesetzt wurde. */
    private int testIndex = -1;
    /** Solange die Felder von der Seite selbst gefüllt werden, rechnet das Ergebnis nicht mit. */
    private boolean filling;

    private PickerTextView editRule;
    private PickerTextView editAccount;
    private MaterialSwitch switchIncome;
    private TextInputEditText editDiscriminator;
    private TextInputEditText editAmountAnchors;
    private PickerTextView editAmountDirection;
    private PickerTextView editAmountPosition;
    private TextInputEditText editAmountCurrency;
    private TextInputEditText editPayeeAnchors;
    private PickerTextView editPayeeDirection;
    private TextInputEditText editPayeeUntil;
    private TextInputEditText editTestTitle;
    private TextInputEditText editTestText;
    private TextView textResult;

    private int amountDirection;
    private int amountPosition;
    private int payeeDirection;

    static void start(Context context, String packageName) {
        context.startActivity(new Intent(context, NotificationRulesActivity.class)
                .putExtra(EXTRA_PACKAGE, packageName));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notification_rules);
        repository = new Repository(this);
        String given = getIntent().getStringExtra(EXTRA_PACKAGE);
        packageName = given == null ? "" : given;

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setSubtitle(NotificationLearnFlow.appLabel(this, packageName));
        toolbar.setNavigationOnClickListener(v -> finish());

        editRule = findViewById(R.id.editRule);
        editAccount = findViewById(R.id.editRuleAccount);
        switchIncome = findViewById(R.id.switchRuleIncome);
        editDiscriminator = findViewById(R.id.editRuleDiscriminator);
        editAmountAnchors = findViewById(R.id.editAmountAnchors);
        editAmountDirection = findViewById(R.id.editAmountDirection);
        editAmountPosition = findViewById(R.id.editAmountPosition);
        editAmountCurrency = findViewById(R.id.editAmountCurrency);
        editPayeeAnchors = findViewById(R.id.editPayeeAnchors);
        editPayeeDirection = findViewById(R.id.editPayeeDirection);
        editPayeeUntil = findViewById(R.id.editPayeeUntil);
        editTestTitle = findViewById(R.id.editTestTitle);
        editTestText = findViewById(R.id.editTestText);
        textResult = findViewById(R.id.textResult);

        PickerAdapters.plain(editAmountDirection, directions(R.string.statement_rules_same_line));
        editAmountDirection.setOnItemClickListener((p, v, position, id) -> {
            amountDirection = position;
            updateResult();
        });
        PickerAdapters.plain(editAmountPosition, positions());
        editAmountPosition.setOnItemClickListener((p, v, position, id) -> {
            amountPosition = position;
            updateResult();
        });
        PickerAdapters.plain(editPayeeDirection, directions(R.string.statement_rules_same_line));
        editPayeeDirection.setOnItemClickListener((p, v, position, id) -> {
            payeeDirection = position;
            updateResult();
        });
        editRule.setOnItemClickListener((p, v, position, id) -> show(position));
        repository.getAccountNames(names -> PickerAdapters.accounts(repository, editAccount, names));

        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateResult();
            }
        };
        for (TextInputEditText field : new TextInputEditText[]{editDiscriminator, editAmountAnchors,
                editAmountCurrency, editPayeeAnchors, editPayeeUntil, editTestTitle, editTestText}) {
            field.addTextChangedListener(watcher);
        }

        findViewById(R.id.btnTestFromBuffer).setOnClickListener(v -> nextTestMessage());
        findViewById(R.id.btnSave).setOnClickListener(v -> save());
        findViewById(R.id.btnDelete).setOnClickListener(v -> confirmDelete());

        if (savedInstanceState != null) {
            testIndex = savedInstanceState.getInt(STATE_TEST, -1);
        }
        load(savedInstanceState == null ? 0 : savedInstanceState.getInt(STATE_INDEX, 0));
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(STATE_INDEX, current);
        out.putInt(STATE_TEST, testIndex);
    }

    // ---- Laden und Anzeigen ----

    private void load(final int select) {
        repository.getNotificationTemplates(all -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            rules.clear();
            for (NotificationTemplate t : all) {
                if (t.packageName.equals(packageName)) {
                    rules.add(t);
                }
            }
            if (rules.isEmpty()) {
                finish();   // nichts (mehr) zu ändern
                return;
            }
            List<String> labels = new ArrayList<>();
            for (NotificationTemplate t : rules) {
                labels.add(labelOf(t));
            }
            PickerAdapters.plain(editRule, labels);
            show(Math.max(0, Math.min(select, rules.size() - 1)));
        });
    }

    /** Woran man eine Regel in der Auswahl erkennt: Konto, Richtung und ihre erste Beschriftung. */
    private String labelOf(NotificationTemplate t) {
        String anchor = t.amountRule.anchors.isEmpty() ? "" : t.amountRule.anchors.get(0);
        return t.account + " · " + getString(t.isIncome ? R.string.type_income : R.string.type_expense)
                + " · " + anchor;
    }

    private void show(int index) {
        current = index;
        NotificationTemplate t = rules.get(index);
        NotificationRuleForm f = NotificationRuleForm.of(t);
        filling = true;
        editRule.setText(labelOf(t), false);
        editAccount.setText(f.account, false);
        switchIncome.setChecked(f.isIncome);
        editDiscriminator.setText(f.discriminator);
        editAmountAnchors.setText(f.amountAnchors);
        amountDirection = f.amountDirection;
        editAmountDirection.setText(directions(R.string.statement_rules_same_line).get(amountDirection), false);
        amountPosition = f.amountPosition;
        editAmountPosition.setText(positions().get(amountPosition), false);
        editAmountCurrency.setText(f.currency);
        editPayeeAnchors.setText(f.payeeAnchors);
        payeeDirection = f.payeeDirection;
        editPayeeDirection.setText(directions(R.string.statement_rules_same_line).get(payeeDirection), false);
        editPayeeUntil.setText(f.payeeUntil);
        filling = false;
        updateResult();
    }

    private List<String> directions(int sameLine) {
        List<String> out = new ArrayList<>();
        out.add(getString(sameLine));
        out.add(getString(R.string.notify_rules_dir_below));
        out.add(getString(R.string.notify_rules_dir_above));
        return out;
    }

    /** In der Reihenfolge von {@link NotificationRuleForm#amountPosition}: erste, letzte, 2. von links, … */
    private List<String> positions() {
        List<String> out = new ArrayList<>();
        out.add(getString(R.string.notify_rules_pos_first));
        out.add(getString(R.string.notify_rules_pos_last));
        for (int n = 2; n <= NotificationRuleForm.MAX_NTH; n++) {
            out.add(getString(R.string.notify_rules_pos_left, n));
            out.add(getString(R.string.notify_rules_pos_right, n));
        }
        return out;
    }

    // ---- Formular ↔ Regel ----

    private NotificationRuleForm form() {
        // Von der gespeicherten Regel ausgehen: sie trägt, was die Seite nicht zeigt (den festen
        // Zeilenabstand einer gelernten Regel).
        NotificationRuleForm f = current >= 0 && current < rules.size()
                ? NotificationRuleForm.of(rules.get(current)) : new NotificationRuleForm();
        f.account = Ui.text(editAccount).trim();
        f.isIncome = switchIncome.isChecked();
        f.discriminator = Ui.text(editDiscriminator).trim();
        f.amountAnchors = Ui.text(editAmountAnchors);
        f.amountDirection = amountDirection;
        f.amountPosition = amountPosition;
        f.currency = Ui.text(editAmountCurrency).trim();
        f.payeeAnchors = Ui.text(editPayeeAnchors);
        f.payeeDirection = payeeDirection;
        f.payeeUntil = Ui.text(editPayeeUntil).trim();
        return f;
    }

    private NotificationTemplate edited() {
        long id = current >= 0 && current < rules.size() ? rules.get(current).id : 0;
        return form().toTemplate(id, packageName);
    }

    // ---- Testmeldung ----

    /** Zeigt, was die Regel in ihrem jetzigen Stand aus der Testmeldung liest. */
    private void updateResult() {
        if (filling) {
            return;
        }
        String title = Ui.text(editTestTitle);
        String text = Ui.text(editTestText);
        if (title.trim().isEmpty() && text.trim().isEmpty()) {
            textResult.setText("");
            return;
        }
        NotificationTemplate t = edited();
        if (t == null) {
            textResult.setText(R.string.notify_rules_invalid);
            return;
        }
        NotificationSource source = new NotificationSource(packageName, 0L, title, text);
        Long cents = t.amountRule.readCents(source.text());
        String account = t.account;
        StringBuilder b = new StringBuilder();
        b.append(getString(R.string.notify_rules_read_amount, cents == null || cents <= 0
                ? getString(R.string.notify_rules_read_nothing)
                : MoneyFormat.display(cents, Currencies.forAccount(account))));
        b.append('\n');
        String payee = t.payeeRule == null ? null : t.payeeRule.read(source.text());
        b.append(getString(R.string.notify_rules_read_payee, t.payeeRule == null
                ? getString(R.string.notify_rules_read_no_payee)
                : payee == null ? getString(R.string.notify_rules_read_nothing) : payee));
        // Die Regel als Ganzes: fehlt das Merkmal, greift sie nicht, auch wenn Betrag und Empfänger
        // für sich lesbar wären.
        if (!t.discriminator.isEmpty() && t.apply(source) == null && cents != null
                && (t.payeeRule == null || payee != null)) {
            b.append('\n').append(getString(R.string.notify_rules_read_mark_missing, t.discriminator));
        }
        textResult.setText(b);
    }

    /** Setzt die nächste aufgehobene Meldung dieser App als Testmeldung ein; mit jedem Tipp eine weiter. */
    private void nextTestMessage() {
        final Context app = getApplicationContext();
        repository.runInBackground(() -> {
            List<NotificationBuffer.Entry> own = new ArrayList<>();
            for (NotificationBuffer.Entry e
                    : NotificationBuffer.get(app).allFor(NotificationRunner.profileOf(app))) {
                if (e.packageName.equals(packageName)) {
                    own.add(e);
                }
            }
            return own;
        }, own -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (own.isEmpty()) {
                Toast.makeText(this, R.string.notify_rules_test_none, Toast.LENGTH_LONG).show();
                return;
            }
            testIndex = (testIndex + 1) % own.size();
            // Die jüngste zuerst: sie ist am ehesten die, um die es gerade geht.
            NotificationBuffer.Entry e = own.get(own.size() - 1 - testIndex);
            filling = true;
            editTestTitle.setText(e.title);
            editTestText.setText(e.text);
            filling = false;
            updateResult();
        });
    }

    // ---- Speichern und Löschen ----

    private void save() {
        final NotificationTemplate t = edited();
        if (t == null || t.id <= 0) {
            Toast.makeText(this, R.string.notify_rules_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        final int keep = current;
        repository.runInBackground(() -> {
            repository.notificationTemplates().update(t);
            return Boolean.TRUE;
        }, done -> {
            Toast.makeText(getApplicationContext(), R.string.notify_rules_saved,
                    Toast.LENGTH_SHORT).show();
            if (!isFinishing() && !isDestroyed()) {
                load(keep);
            }
        });
    }

    private void confirmDelete() {
        if (current < 0 || current >= rules.size()) {
            return;
        }
        final long id = rules.get(current).id;
        final Context app = getApplicationContext();
        AppDialog.destructive(this)
                .setTitle(R.string.notify_rule_delete)
                .setMessage(R.string.notify_rule_delete_message)
                .setPositiveButton(R.string.delete, (d, w) -> repository.runInBackground(() -> {
                    repository.notificationTemplates().delete(id);
                    // Ohne Regel wartet dieses Profil nicht mehr unbegrenzt auf die Meldungen der App.
                    NotificationBuffer.purgeNow(app);
                    return Boolean.TRUE;
                }, done -> {
                    Toast.makeText(app, R.string.notify_rules_deleted, Toast.LENGTH_SHORT).show();
                    if (!isFinishing() && !isDestroyed()) {
                        load(0);
                    }
                }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
