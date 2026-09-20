package de.spahr.ausgaben.ui;

import android.annotation.SuppressLint;
import android.app.DatePickerDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Calendar;
import java.util.List;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.PlaceEntry;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.settings.Currencies;
import de.spahr.ausgaben.settings.PlacesStore;
import de.spahr.ausgaben.settings.DateFormats;

/**
 * Zeigt die Bewegungen (place_entry) eines Ortes eines Kontos als editierbares Journal: Klick auf eine
 * Bewegung öffnet den Bearbeiten-Dialog (Datum/Betrag/Notiz, mit Löschen); der FAB legt eine neue Bewegung
 * an. Alle Änderungen betreffen NUR den Ortssaldo – es entsteht bzw. ändert sich keine echte Buchung.
 */
public class PlaceHistoryActivity extends LocalizedActivity {

    public static final String EXTRA_PLACE = "place";
    public static final String EXTRA_ACCOUNT = "account";


    private Repository repository;
    private LinearLayout container;
    private TextView placeBalance;
    private String account = "";
    private String place = "";
    private String currency = Currencies.getDefault();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_place_history);
        repository = new Repository(this);

        account = orEmpty(getIntent().getStringExtra(EXTRA_ACCOUNT));
        place = orEmpty(getIntent().getStringExtra(EXTRA_PLACE));
        currency = Currencies.forAccount(account);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setTitle(place.isEmpty() ? getString(R.string.no_place) : place);
        if (!account.isEmpty()) {
            toolbar.setSubtitle(account);
        }

        placeBalance = findViewById(R.id.placeBalance);
        container = findViewById(R.id.historyContainer);

        FloatingActionButton fab = findViewById(R.id.fabNewHere);
        fab.setOnClickListener(v -> showMovementDialog(null));

        // Wie in MainActivity: erst sichtbar, sobald nach unten gescrollt wurde.
        ScrollView scroll = findViewById(R.id.historyScroll);
        FloatingActionButton fabScrollTop = findViewById(R.id.fabScrollTop);
        fabScrollTop.setOnClickListener(v -> scroll.smoothScrollTo(0, 0));
        scroll.setOnScrollChangeListener((View v, int scrollX, int scrollY, int oldScrollX, int oldScrollY) -> {
            if (scrollY > 0) {
                fabScrollTop.show();
            } else {
                fabScrollTop.hide();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        repository.getPlaceHistory(account, place, this::render);
    }

    private void render(List<PlaceEntry> entries) {
        container.removeAllViews();
        long running = 0;
        // Saldo chronologisch aufsummieren (jede Zeile zeigt ihren korrekten Zwischenstand), die Zeilen
        // aber jeweils oben einfügen → neueste Buchung steht oben.
        for (PlaceEntry e : entries) {
            running += e.amountCents;
            container.addView(buildRow(e, running), 0);
        }
        placeBalance.setText(getString(R.string.balance, formatEuro(running)));
        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.no_movements);
            empty.setPadding(0, 24, 0, 0);
            container.addView(empty);
        }
    }

    @SuppressLint("SetTextI18n")   // Daten mit Trennzeichen, kein Satz
    private View buildRow(final PlaceEntry e, long running) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 16, 0, 16);
        row.setClickable(true);
        row.setOnClickListener(v -> showMovementDialog(e));
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(tv.resourceId);

        TextView left = new TextView(this);
        String desc = e.note == null || e.note.trim().isEmpty() ? typeLabel(e.type) : e.note.trim();
        left.setText(DateFormats.date(e.createdAt) + "  ·  " + desc);
        left.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView amount = new TextView(this);
        amount.setText(formatEuro(e.amountCents));
        amount.setTextColor(e.amountCents < 0 ? getColor(R.color.expense_red) : getColor(R.color.income_green));

        TextView bal = new TextView(this);
        bal.setText("  = " + formatEuro(running));
        bal.setGravity(Gravity.END);
        bal.setTextColor(getColor(R.color.grey_text));
        bal.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f));

        row.addView(left);
        row.addView(amount);
        row.addView(bal);
        return row;
    }

    /** Dialog zum Anlegen ({@code existing == null}) oder Bearbeiten/Löschen einer Ort-Bewegung. */
    @SuppressLint("InflateParams")   // Dialoginhalt, siehe Begruendung an der inflate-Zeile
    private void showMovementDialog(final PlaceEntry existing) {
        // inflate(..., null, false) ist bei einem Dialog richtig: Die Ansicht geht an setView und hat zu
        // diesem Zeitpunkt keine Eltern, von denen sie Layout-Vorgaben übernehmen könnte. Lint meldet das
        // trotzdem als InflateParams.
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_place_movement, null, false);
        final TextInputEditText dateField = view.findViewById(R.id.movementDate);
        final TextInputEditText amountField = view.findViewById(R.id.movementAmount);
        final TextInputEditText noteField = view.findViewById(R.id.movementNote);

        final Calendar cal = Calendar.getInstance();
        if (existing != null) {
            cal.setTimeInMillis(existing.createdAt);
            amountField.setText(formatSigned(existing.amountCents));
            noteField.setText(existing.note);
        }
        dateField.setText(DateFormats.date(cal.getTimeInMillis()));
        dateField.setOnClickListener(v -> new DatePickerDialog(this, (dp, y, m, d) -> {
            cal.set(Calendar.YEAR, y);
            cal.set(Calendar.MONTH, m);
            cal.set(Calendar.DAY_OF_MONTH, d);
            dateField.setText(DateFormats.date(cal.getTimeInMillis()));
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show());
        // Das Kalendersymbol liegt über dem Feld und würde den Tipper sonst schlucken.
        ((TextInputLayout) view.findViewById(R.id.movementDateLayout))
                .setEndIconOnClickListener(v -> dateField.performClick());
        // Betrag über die eigene Rechentastatur (vorzeichenbehaftet: Bewegungen dürfen negativ sein).
        CalcKeyboardView.installToggling(amountField, (android.widget.LinearLayout) view, true);

        MaterialAlertDialogBuilder b = new AppDialog(this)
                .setTitle(existing == null ? R.string.movement_add : R.string.movement_edit)
                .setView(AppDialog.scrollable(view))
                .setPositiveButton(R.string.save, (d, w) -> {
                    Long cents = parseSignedCents(text(amountField));
                    if (cents == null) {
                        Toast.makeText(this, R.string.error_amount, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String note = text(noteField);
                    if (existing == null) {
                        repository.addPlaceMovement(account, place, cents, cal.getTimeInMillis(), note,
                                this::reload);
                    } else {
                        existing.amountCents = cents;
                        existing.createdAt = cal.getTimeInMillis();
                        existing.note = note == null ? "" : note;
                        repository.updatePlaceMovement(existing, this::reload);
                    }
                })
                .setNegativeButton(R.string.cancel, null);
        if (existing != null) {
            // Der Neutral-Knopf liegt direkt neben „Abbrechen" – ohne Rückfrage kostet ein Fehlgriff die
            // Bewegung. Überall sonst in der App fragt eine zerstörende Aktion nach (AppDialog.destructive).
            b.setNeutralButton(R.string.delete, (d, w) -> AppDialog.destructive(this)
                    .setTitle(R.string.movement_delete_confirm_title)
                    .setMessage(R.string.movement_delete_confirm_message)
                    .setPositiveButton(R.string.delete, (d2, w2) ->
                            repository.deletePlaceMovement(existing.id, this::reload))
                    .setNegativeButton(R.string.cancel, null)
                    .show());
        }
        b.show();
    }

    private String typeLabel(String type) {
        switch (type == null ? "" : type) {
            case "transfer":
                return getString(R.string.movement_transfer);
            case "reconcile":
                return getString(R.string.movement_reconcile);
            default:
                return getString(R.string.movement_booking);
        }
    }

    private String text(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    /** Parst einen vorzeichenbehafteten Betrag bzw. eine kleine Rechnung („-20", „5,50", „30-5") in Cent. */
    private Long parseSignedCents(String raw) {
        return de.spahr.ausgaben.settings.AmountExpression.toCents(raw);
    }

    private String formatSigned(long signedCents) {
        return de.spahr.ausgaben.settings.MoneyFormat.plain(signedCents);
    }

    private String formatEuro(long signedCents) {
        return de.spahr.ausgaben.settings.MoneyFormat.display(signedCents, currency);
    }

    private static String orEmpty(String s) {
        // „ohne Ort" wird als leerer Ort geführt.
        return s == null || s.equals(PlacesStore.NO_PLACE) ? "" : s;
    }
}
