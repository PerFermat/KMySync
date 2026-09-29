package de.spahr.ausgaben.ui;

import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.location.LocationTagger;
import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Die stille Zifferneingabe der Buchungsliste: Betrag eintippen → Betrag-only-Pfad (Auflösung per
 * Standort). Ausgelagert aus {@link MainActivity}, um deren Umfang zu verringern – der Code ist dabei
 * unverändert umgezogen.
 *
 * <p>Der Dialog wird über {@link HostedDialog} gebaut, beim ersten Mal und nach jeder Drehung erneut.
 * Die Felder tragen keine ids, das Fenstersystem kann sie also nicht selbst wiederherstellen; deshalb
 * merkt sich dieser Controller den Betrag und die Stelle im Empfänger-Rundlauf ({@link #save}/
 * {@link #restore}).</p>
 */
class NumberEntryController {

    /** Schlüssel des Dialogs für {@link HostedDialog}. */
    static final String DLG_NUMBER_ENTRY = "dlg_numberEntry";

    private static final String STATE_NUMBER_AMOUNT = "s_numberAmount";
    private static final String STATE_NUMBER_PICK = "s_numberPick";
    private static final String STATE_NUMBER_TAPPED = "s_numberTapped";

    /** Was die Zifferneingabe von der Liste braucht; alles erst beim Bauen des Dialogs gefragt. */
    interface Host {
        /** Das Repository der Maske (entsteht in {@code onCreate} erst nach diesem Controller). */
        Repository repository();

        SettingsStore settings();

        /** {@code null}, solange es keinen Standort-Tagger gibt. */
        LocationTagger locationTagger();

        boolean hasLocationPermission();

        void requestLocationPermission();

        /** Die gerade angezeigten Konten für die Empfängersuche; leer = keine Einschränkung. */
        Set<String> visibleAccounts();

        VoiceEntryController voiceEntry();
    }

    private final AppCompatActivity activity;
    private final Host host;

    /**
     * Stand der stillen Zifferneingabe – überlebt die Drehung, siehe {@link #buildDialog}. Die Felder des
     * Dialogs entstehen im Code und tragen keine ids; das Fenstersystem kann sie also nicht selbst retten.
     */
    private String numberEntryAmount = "";
    private int numberEntryPick;
    private boolean numberEntryTapped;

    NumberEntryController(AppCompatActivity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    void save(Bundle out) {
        out.putString(STATE_NUMBER_AMOUNT, numberEntryAmount);
        out.putInt(STATE_NUMBER_PICK, numberEntryPick);
        out.putBoolean(STATE_NUMBER_TAPPED, numberEntryTapped);
    }

    /**
     * Den Stand der Zifferneingabe zurücklesen — <b>in {@code onCreate}</b> und nicht in
     * {@code onRestoreInstanceState}.
     *
     * <p>Die Reihenfolge entscheidet: Das Fenstersystem stellt den Dialog beim Wechsel nach
     * {@code onStart} wieder her und ruft dabei {@link #buildDialog}; {@code onRestoreInstanceState}
     * kommt erst danach. Der Dialog läse dann noch den leeren Anfangswert — was genau der Fehler war,
     * den ein Test hier zutage gefördert hat.</p>
     */
    void restore(Bundle in) {
        if (in == null) {
            return;
        }
        numberEntryAmount = in.getString(STATE_NUMBER_AMOUNT, "");
        numberEntryPick = in.getInt(STATE_NUMBER_PICK, 0);
        numberEntryTapped = in.getBoolean(STATE_NUMBER_TAPPED, false);
    }

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
    void show() {
        HostedDialog.show(activity, DLG_NUMBER_ENTRY, null);
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
    android.app.Dialog buildDialog() {
        final Repository repository = host.repository();
        final SettingsStore settings = host.settings();
        final boolean betragZaehlt = settings.isAmountSuggestEnabled();
        final LocationTagger locationTagger = host.locationTagger();
        if (locationTagger != null && !host.hasLocationPermission()) {
            host.requestLocationPermission();
        }
        final com.google.android.material.textfield.TextInputEditText field =
                new com.google.android.material.textfield.TextInputEditText(activity);
        field.setHint(R.string.amount_hint);
        // Ziffern, das eingestellte Dezimalzeichen und + - * (kleine Rechnung wie 10+20*3); Struktur
        // regelt der CalcInputFilter. Ausgewertet wird beim Speichern über AmountExpression.
        AmountField.prepareCalc(field);

        // Was vor der Drehung dastand, steht wieder da.
        field.setText(numberEntryAmount);
        field.setSelection(field.getText() == null ? 0 : field.getText().length());
        field.addTextChangedListener(new SimpleWatcher(
                () -> numberEntryAmount = field.getText() == null ? "" : field.getText().toString()));

        final android.widget.TextView payeeView = new android.widget.TextView(activity);
        payeeView.setText(activity.getString(R.string.voice_payee_resolved, "—"));

        int pad = Math.round(16 * activity.getResources().getDisplayMetrics().density);
        // Der Betrag ist das Einzige, was hier eingegeben wird – er darf größer stehen als sonstiger Text.
        field.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22);
        android.widget.LinearLayout box = new android.widget.LinearLayout(activity);
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
                name = activity.getString(R.string.nearby_payee_none);
            } else if (betragZaehlt && pick[0] == 0 && candidates.size() > 1
                    && !angetippt[0] && !hasAmount(field)) {
                zeigtAnzahl[0] = true;
                // Ohne Betrag ist noch nichts entschieden – dann nur sagen, wie viele in Frage kommen.
                // Nur solange nicht getippt wurde: sonst verdeckte die Anzahl den ersten Namen und der
                // Rundlauf zeigte ihn nie.
                name = activity.getString(R.string.nearby_payee_count, candidates.size());
            } else {
                name = candidates.get(pick[0]).payee;
            }
            payeeView.setText(activity.getString(R.string.voice_payee_resolved, name));
        };
        final Runnable resolveShow = () -> {
            String coords = settings.isGpsEnabled() && locationTagger != null
                    ? locationTagger.currentCoordinates() : null;
            if (coords == null) {
                return;
            }
            // Ohne Betrag (0) urteilt niemand: alle Nachbarn bleiben stehen, geordnet nach Nähe.
            long cents = betragZaehlt ? amountOf(field) : 0;
            repository.resolveNearby(coords, cents, Repository.VOICE_TYPE_EXPENSE, host.visibleAccounts(),
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
        if (activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
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
        final CalcKeyboardView calc = new CalcKeyboardView(activity);
        calc.attachTo(field);
        calc.setOnOk(valid -> {
            if (!valid) {
                Toast.makeText(activity, R.string.error_amount_calc, Toast.LENGTH_SHORT).show();
                return;
            }
            String raw = field.getText() == null ? "" : field.getText().toString().trim();
            if (raw.isEmpty()) {
                return;
            }
            Long cents = de.spahr.ausgaben.settings.AmountExpression.toCents(raw);
            if (cents == null || cents <= 0) {
                Toast.makeText(activity, R.string.error_amount_calc, Toast.LENGTH_SHORT).show();
                return;
            }
            String amt = de.spahr.ausgaben.settings.MoneyFormat.plain(cents);
            VoiceEntryController voiceEntry = host.voiceEntry();
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
            forget();
            if (dialogRef[0] != null) {
                dialogRef[0].dismiss();
            }
        });
        field.requestFocus();
        box.addView(calc);

        androidx.appcompat.app.AlertDialog dialog = new AppDialog(activity)
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
    void forget() {
        numberEntryAmount = "";
        numberEntryPick = 0;
        numberEntryTapped = false;
    }
}
