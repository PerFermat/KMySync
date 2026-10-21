package de.spahr.ausgaben.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Collections;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.notify.NotificationDraft;
import de.spahr.ausgaben.notify.NotificationRun;
import de.spahr.ausgaben.notify.NotificationRunner;
import de.spahr.ausgaben.settings.Currencies;
import de.spahr.ausgaben.settings.DateFormats;
import de.spahr.ausgaben.settings.MoneyFormat;
import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Die Durchsicht der Buchungen aus Benachrichtigungen: was die Vorlagen aus den aufgehobenen Meldungen
 * gelesen haben, bevor irgendetwas gebucht wird.
 *
 * <p>Dieselbe Liste wie nach dem Einlesen mehrerer Abrechnungen ({@link StatementBatchActivity}), mit
 * denselben Zeilen und Farben — nur für gewöhnliche Buchungen statt Wertpapierbewegungen. Der Stift
 * führt in die Buchungsmaske, die hier nicht speichert, sondern zurückgibt
 * ({@link BookingEditActivity#EXTRA_BATCH}).</p>
 *
 * <p>Drei Ausgänge, und nur einer löscht. <b>OK</b> bucht die buchbaren Zeilen und löscht danach alle
 * gezeigten Meldungen aus dem Puffer, auch die entfernten und die nicht erkannten. <b>Abbrechen</b> und
 * Zurück löschen nichts: die Liste kommt beim nächsten Aktualisieren wieder. Dasselbe gilt, wenn die
 * App beendet wird.</p>
 */
public class NotificationBatchActivity extends LocalizedActivity {

    public static final String EXTRA_DRAFTS = "drafts";
    /** Die Nummern aller gezeigten Puffereinträge – auch derer, die der Nutzer aus der Liste nimmt. */
    public static final String EXTRA_SHOWN_IDS = "shown_ids";

    private static final String STATE_EDITING = "s_editing";
    private static final String STATE_SAVING = "s_saving";

    /** Steht die Liste schon offen? Zweimal Aktualisieren öffnete sie sonst zweimal. */
    private static boolean open;

    private Repository repository;
    private LinearLayout container;
    private MaterialButton btnSave;
    private final ArrayList<NotificationDraft> drafts = new ArrayList<>();
    private long[] shownIds = new long[0];
    /** Der Eintrag, der gerade in der Maske liegt; die Antwort kommt ohne eigene Kennung zurück. */
    private int editing = -1;
    private ActivityResultLauncher<Intent> editLauncher;
    /** Läuft der Abschluss schon? Ein zweiter Tipp buchte sonst alles ein zweites Mal. */
    private boolean saving;

    /**
     * Der Benachrichtigungs-Durchlauf nach dem Aktualisieren: bereitet die Liste vor und öffnet sie,
     * wenn es etwas zu zeigen gibt. Ohne den Schalter in den Einstellungen geschieht nichts.
     */
    static void startIfAny(final Activity activity, final Repository repository) {
        if (open || !SettingsStore.isNotifyLearnEnabled(activity)) {
            return;
        }
        final android.content.Context app = activity.getApplicationContext();
        repository.runInBackground(() -> NotificationRunner.prepare(app, repository), found -> {
            if (open || found.isEmpty() || activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            long[] ids = new long[found.size()];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = found.get(i).entryId;
            }
            activity.startActivity(new Intent(activity, NotificationBatchActivity.class)
                    .putParcelableArrayListExtra(EXTRA_DRAFTS, found)
                    .putExtra(EXTRA_SHOWN_IDS, ids));
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_statement_batch);
        open = true;
        repository = new Repository(this);

        Bundle from = savedInstanceState != null ? savedInstanceState : getIntent().getExtras();
        if (from != null) {
            ArrayList<NotificationDraft> given = from.getParcelableArrayList(EXTRA_DRAFTS);
            if (given != null) {
                drafts.addAll(given);
            }
            long[] ids = from.getLongArray(EXTRA_SHOWN_IDS);
            if (ids != null) {
                shownIds = ids;
            }
        }
        if (savedInstanceState != null) {
            editing = savedInstanceState.getInt(STATE_EDITING, -1);
            saving = savedInstanceState.getBoolean(STATE_SAVING, false);
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.notify_batch_title);
        toolbar.setNavigationOnClickListener(v -> confirmCancel());
        container = findViewById(R.id.batchContainer);
        btnSave = findViewById(R.id.btnBatchSave);
        btnSave.setText(android.R.string.ok);
        btnSave.setOnClickListener(v -> complete());
        findViewById(R.id.btnBatchCancel).setOnClickListener(v -> confirmCancel());

        editLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        if (result.getData().getBooleanExtra(BookingEditActivity.RESULT_REMOVE, false)) {
                            // „Löschen" in der Maske: wie der Papierkorb der Zeile.
                            int index = editing;
                            editing = -1;
                            remove(index);
                            return;
                        }
                        applyEdit(result.getData());
                    }
                    editing = -1;
                });

        getOnBackPressedDispatcher().addCallback(this,
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        confirmCancel();
                    }
                });

        if (shownIds.length == 0) {
            finish();
            return;
        }
        render();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) {
            open = false;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putParcelableArrayList(EXTRA_DRAFTS, drafts);
        out.putLongArray(EXTRA_SHOWN_IDS, shownIds);
        out.putInt(STATE_EDITING, editing);
        out.putBoolean(STATE_SAVING, saving);
    }

    // ---- Anzeige ----

    private void render() {
        // Vor jeder Anzeige neu: Bearbeiten und Entfernen können eine Doppelung schaffen oder auflösen.
        NotificationRun.markBufferDuplicates(drafts, java.time.ZoneId.systemDefault(),
                NotificationRunner.styleOf(this));
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        boolean blocked = false;
        for (int i = 0; i < drafts.size(); i++) {
            container.addView(row(inflater, drafts.get(i), i));
            blocked |= drafts.get(i).blocksSave();
        }
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setSubtitle(blocked ? getString(R.string.statement_batch_incomplete) : null);
        btnSave.setEnabled(!blocked && !saving);
    }

    private View row(LayoutInflater inflater, NotificationDraft d, int index) {
        View view = inflater.inflate(R.layout.item_statement_draft, container, false);
        TextView name = view.findViewById(R.id.textDraftSecurity);
        TextView amount = view.findViewById(R.id.textDraftAmount);
        TextView details = view.findViewById(R.id.textDraftDetails);
        TextView problem = view.findViewById(R.id.textDraftProblem);

        // Erste Zeile: so, wie die Buchung nachher in der Liste des Kontos heißt – auch ohne
        // Empfänger (BookingLabel). Von einer nicht erkannten Meldung steht nur die App fest.
        name.setText(d.problem() == NotificationDraft.PROBLEM_UNRECOGNIZED
                ? NotificationLearnFlow.appLabel(this, d.packageName)
                : BookingLabel.title(this, d.toBooking(), d.isSplit()));
        String currency = d.account.isEmpty() ? Currencies.getDefault() : Currencies.forAccount(d.account);
        amount.setText(d.cents <= 0 ? ""
                : MoneyFormat.display(d.isIncome ? d.cents : -d.cents, currency));
        details.setText(detailsOf(d));

        int reason = d.problem();
        String hint = hintOf(d, reason);
        if (hint == null) {
            view.setBackgroundColor(0);
            problem.setVisibility(View.GONE);
        } else {
            // Rot nur, was den Abschluss aufhält. Nicht erkannte Zeilen und mögliche Duplikate sind
            // Hinweise in gedecktem Gelb.
            view.setBackgroundColor(getColor(d.blocksSave()
                    ? R.color.statement_error_bg : R.color.statement_warn_bg));
            problem.setVisibility(View.VISIBLE);
            problem.setText(hint);
        }

        ImageButton edit = view.findViewById(R.id.btnDraftEdit);
        edit.setOnClickListener(v -> edit(index));
        view.findViewById(R.id.btnDraftDelete).setOnClickListener(v -> remove(index));
        return view;
    }

    /** Die zweite Zeile: Datum, Konto, Kategorie – und der Wortlaut der Meldung, wo er gebraucht wird. */
    private String detailsOf(NotificationDraft d) {
        StringBuilder b = new StringBuilder(DateFormats.date(d.dateMillis));
        if (!d.account.isEmpty()) {
            b.append(" · ").append(d.account);
        }
        // Eine Umbuchung hat keine Kategorie – sie zeigt sich am Pfeil vor dem Empfänger.
        if (!d.isTransfer && d.isSplit()) {
            b.append(" · ").append(android.text.TextUtils.join(" + ", d.splitCategories));
        } else if (!d.isTransfer && !d.category.isEmpty()) {
            b.append(" · ").append(d.category);
        }
        // Der Wortlaut steht dabei, sobald die Zeile eine Frage aufwirft – dann muss der Nutzer
        // nachlesen können, was die App gemeldet hat.
        if (d.problem() != NotificationDraft.PROBLEM_NONE || d.dupBooked || d.dupBuffer) {
            b.append('\n');
            if (!d.title.isEmpty()) {
                b.append(d.title).append('\n');
            }
            b.append(d.text);
        }
        return b.toString();
    }

    private String hintOf(NotificationDraft d, int reason) {
        switch (reason) {
            case NotificationDraft.PROBLEM_UNRECOGNIZED:
                return getString(R.string.notify_problem_unrecognized);
            case NotificationDraft.PROBLEM_ACCOUNT:
                return getString(R.string.notify_problem_account);
            case NotificationDraft.PROBLEM_FOREIGN:
                return getString(R.string.notify_problem_foreign, d.foreignCurrency);
            case NotificationDraft.PROBLEM_TRANSFER:
                return getString(R.string.notify_problem_transfer);
            default:
                break;
        }
        if (d.dupBooked) {
            return getString(R.string.notify_dup_booked);
        }
        if (d.dupBuffer) {
            return getString(R.string.notify_dup_buffer);
        }
        // Auch nach dem Blick in die Maske bleibt sichtbar, dass hier eine fremde Währung im Spiel war.
        return d.foreignCurrency.isEmpty() ? null
                : getString(R.string.notify_foreign_checked, d.foreignCurrency);
    }

    // ---- Ändern ----

    /**
     * Öffnet die Buchungsmaske so, wie die Spracheingabe am Handy es nach einem Diktat tut
     * ({@code VoiceEntryController.openVoiceEditor}): mit der gefundenen Vorlagebuchung oder dem Alias
     * und dem Erkannten als Ausgangsname für einen neuen Alias. Dazu Stapelmodus, Konto und Datum der
     * Regel.
     */
    private void edit(final int index) {
        if (saving || index < 0 || index >= drafts.size()) {
            return;
        }
        final NotificationDraft d = drafts.get(index);
        // Schon einmal in der Maske gewesen, Umbuchung oder ohne Empfänger in der Meldung: nichts
        // aufzulösen – die Maske zeigt den Stand der Zeile.
        if (d.edited || d.isTransfer || d.rawPayee.isEmpty()) {
            openEditor(index, d, null);
            return;
        }
        repository.resolveVoice(d.rawPayee, d.coords.isEmpty() ? null : d.coords, res -> {
            if (!isFinishing() && !isDestroyed()) {
                openEditor(index, d, res);
            }
        });
    }

    private void openEditor(int index, NotificationDraft d, Repository.VoiceResolution res) {
        editing = index;
        Intent intent = new Intent(this, BookingEditActivity.class)
                .putExtra(BookingEditActivity.EXTRA_BATCH, true)
                .putExtra(BookingEditActivity.EXTRA_PREFILL_DATE_MS, d.dateMillis)
                .putExtra(BookingEditActivity.EXTRA_PREFILL_INCOME, d.isIncome)
                .putExtra(BookingEditActivity.EXTRA_BATCH_ROW, d)
                .putExtra(BookingEditActivity.EXTRA_PREFILL_TRANSFER, d.isTransfer)
                .putExtra(BookingEditActivity.EXTRA_PREFILL_TRANSFER_ACCOUNT, d.transferAccount);
        if (!d.account.isEmpty()) {
            intent.putExtra(BookingEditActivity.EXTRA_PRESET_ACCOUNT, d.account);
        }
        if (d.cents > 0) {
            intent.putExtra(BookingEditActivity.EXTRA_VOICE_AMOUNT_CENTS, d.cents);
        }
        if (!d.rawPayee.isEmpty()) {
            intent.putExtra(BookingEditActivity.EXTRA_VOICE_SPOKEN_PAYEE, d.rawPayee);
        }
        // Der Wortlaut der Meldung als Notiz – beim ersten Öffnen. Danach gilt, was die Maske
        // zurückgegeben hat: der Nutzer kann ihn dort geändert oder gelöscht haben.
        intent.putExtra(BookingEditActivity.EXTRA_PREFILL_NOTE, d.edited ? d.note : d.wording());
        if (res != null && res.booking != null) {
            intent.putExtra(BookingEditActivity.EXTRA_TEMPLATE_BOOKING_ID, res.booking.id);
        } else {
            String payee = res == null ? d.payee : res.payee;
            if (!payee.isEmpty()) {
                intent.putExtra(BookingEditActivity.EXTRA_PREFILL_PAYEE, payee);
            }
            if (res != null && res.alias != null) {
                intent.putExtra(BookingEditActivity.EXTRA_ALIAS_ID, res.alias.id);
            }
        }
        editLauncher.launch(intent);
    }

    private void applyEdit(Intent data) {
        if (editing < 0 || editing >= drafts.size()) {
            return;
        }
        final NotificationDraft d = drafts.get(editing);
        d.payee = text(data, BookingEditActivity.RESULT_PAYEE);
        // Was der Nutzer in der Maske eingestellt hat, gilt – auch die Art.
        d.isTransfer = data.getBooleanExtra(BookingEditActivity.RESULT_TRANSFER, false);
        if (d.isTransfer) {
            d.transferAccount = text(data, BookingEditActivity.RESULT_TRANSFER_ACCOUNT);
        }
        d.cents = data.getLongExtra(BookingEditActivity.RESULT_CENTS, d.cents);
        d.isIncome = data.getBooleanExtra(BookingEditActivity.RESULT_INCOME, d.isIncome);
        d.account = text(data, BookingEditActivity.RESULT_ACCOUNT);
        d.dateMillis = data.getLongExtra(BookingEditActivity.RESULT_DATE_MS, d.dateMillis);
        d.category = text(data, BookingEditActivity.RESULT_CATEGORY);
        int side = data.getIntExtra(BookingEditActivity.RESULT_CATEGORY_SIDE, -1);
        d.categoryIsIncome = side < 0 ? null : Boolean.valueOf(side == 1);
        d.note = text(data, BookingEditActivity.RESULT_NOTE);
        d.tags = text(data, BookingEditActivity.RESULT_TAGS);
        ArrayList<String> categories =
                data.getStringArrayListExtra(BookingEditActivity.RESULT_SPLIT_CATEGORIES);
        long[] cents = data.getLongArrayExtra(BookingEditActivity.RESULT_SPLIT_CENTS);
        int[] sides = data.getIntArrayExtra(BookingEditActivity.RESULT_SPLIT_SIDES);
        d.splitCategories = categories == null ? new ArrayList<String>() : categories;
        d.splitCents = cents == null ? new long[0] : cents;
        d.splitSides = sides == null ? new int[0] : sides;
        d.edited = true;
        // Eine nicht erkannte Meldung, die der Nutzer selbst gebucht hat, ist das Lernbeispiel für
        // die nächste dieser Art.
        d.learn = !d.recognized;
        render();
        // Betrag, Konto oder Datum können sich geändert haben – die Frage nach dem Bestand neu stellen.
        repository.runInBackground(() -> {
            NotificationRunner.markBooked(repository, Collections.singletonList(d));
            return d;
        }, done -> {
            if (!isFinishing() && !isDestroyed()) {
                render();
            }
        });
    }

    private static String text(Intent data, String key) {
        String value = data.getStringExtra(key);
        return value == null ? "" : value;
    }

    // ---- Entfernen, Abschließen, Abbrechen ----

    private void remove(int index) {
        if (saving || index < 0 || index >= drafts.size()) {
            return;
        }
        drafts.remove(index);
        if (drafts.isEmpty()) {
            // Nichts mehr zu entscheiden: der Durchlauf ist damit abgeschlossen.
            complete();
            return;
        }
        render();
    }

    private void complete() {
        if (saving) {
            return;
        }
        saving = true;
        btnSave.setEnabled(false);
        final android.content.Context app = getApplicationContext();
        final ArrayList<NotificationDraft> remaining = new ArrayList<>(drafts);
        final long[] ids = shownIds;
        repository.runInBackground(
                () -> NotificationRunner.complete(app, repository, remaining, ids), saved -> {
                    if (saved < 0) {
                        saving = false;
                        Toast.makeText(app, R.string.statement_batch_save_failed,
                                Toast.LENGTH_LONG).show();
                        if (!isFinishing() && !isDestroyed()) {
                            render();
                        }
                        return;
                    }
                    if (saved > 0) {
                        Toast.makeText(app, getString(R.string.notify_batch_saved, saved),
                                Toast.LENGTH_LONG).show();
                        // Offene App, Widgets und Uhr über die neuen Buchungen informieren.
                        app.sendBroadcast(new Intent(
                                de.spahr.ausgaben.wear.WearBridge.ACTION_BOOKINGS_CHANGED)
                                .setPackage(app.getPackageName()));
                        de.spahr.ausgaben.widget.AusgabenWidget.refreshAll(app);
                        de.spahr.ausgaben.wear.BalanceSync.publish(app);
                    }
                    finish();
                });
    }

    private void confirmCancel() {
        if (saving) {
            return;
        }
        new AppDialog(this)
                .setTitle(R.string.notify_batch_cancel_title)
                .setMessage(R.string.notify_batch_cancel_message)
                .setPositiveButton(R.string.notify_batch_cancel, (d, w) -> finish())
                .setNegativeButton(R.string.statement_batch_apply, null)
                .show();
    }
}
