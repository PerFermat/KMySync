package de.spahr.ausgaben.ui;

import android.net.Uri;
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
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.pdf.PdfText;
import de.spahr.ausgaben.pdf.PdfTextExtractor;
import de.spahr.ausgaben.settings.MoneyFormat;
import de.spahr.ausgaben.settings.StatementTemplates;
import de.spahr.ausgaben.statement.AnchorRule;
import de.spahr.ausgaben.statement.StatementTemplate;
import de.spahr.ausgaben.statement.StatementTemplate.Field;

/**
 * Die gelernten Erkennungsregeln von Hand nachbessern.
 *
 * <p>Der Lerner trifft das Meiste, aber er kennt je Feld nur <b>eine</b> Beschriftung — und die reicht
 * nicht, wo eine Bank Zeilen weglässt oder wechselt. Hier lassen sich deshalb vor allem Rückfälle
 * angeben: {@code Valuta}, ersatzweise {@code Zahltag}, ersatzweise {@code Ex-Tag}. Gesucht wird in dieser
 * Reihenfolge, es gilt die erste Beschriftung, die einen Wert trägt.</p>
 *
 * <p>Dieselbe Kette löst den Währungsfall: das Brutto steht bei einem dollarnotierten Papier in der
 * Umrechnungszeile und bei einem euronotierten in der Bruttozeile.</p>
 *
 * <p>Gearbeitet wird an einer <b>Testabrechnung</b>: eine einmal gewählte PDF-Datei hängt an der Seite,
 * stellt die zu ihr passende Vorlage ein und lässt jeden Bereich unter seiner Überschrift zeigen, was
 * seine Regel darin liest. Die Beschriftungsfelder legen dann die im Dokument gefundenen Werte zur
 * Auswahl vor, statt sie erraten zu lassen — buchstabengenau so, wie die App sie sieht.</p>
 *
 * <p>Neue Vorlagen entstehen hier nicht — die entstehen beim ersten Erfassen einer Abrechnung und sind
 * dann vollständig. Von Hand wird nachgebessert, nicht angefangen.</p>
 */
public class StatementRulesActivity extends LocalizedActivity implements HostedDialog.Host {

    /** Schlüssel und Angaben der Ankerauswahl – siehe {@link HostedDialog}. */
    private static final String DLG_PICK_ANCHOR = "dlg_pickAnchor";
    private static final String ARG_ANCHOR_TARGET = "a_anchorTarget";
    /** Die Rückfrage vor dem Einspielen einer fremden Regeldatei. */
    private static final String DLG_IMPORT = "dlg_importRules";
    /** Die Rückfrage vor dem Teilen, wenn im Formular noch etwas Ungespeichertes steht. */
    private static final String DLG_SHARE_UNSAVED = "dlg_shareUnsaved";

    /**
     * Der Bereich, dessen Ankerauswahl gerade offen ist.
     *
     * <p>Er lässt sich nicht in ein {@link Bundle} legen — es ist ein Formularabschnitt mit seinen
     * Ansichten. Nach einer Drehung ist er weg, und mit ihm die eingelesene Testabrechnung: Der Dialog
     * lässt sich dann nicht wieder aufbauen und verschwindet. Ein erneuter Tipp aufs Feld stellt ihn
     * her.</p>
     */
    private FieldForm offeneAnkerauswahl;

    @Override
    public android.app.Dialog buildDialog(String key, Bundle args) {
        if (DLG_IMPORT.equals(key)) {
            return offenerImport == null ? null : buildImportDialog();
        }
        if (DLG_SHARE_UNSAVED.equals(key)) {
            return buildShareUnsavedDialog();
        }
        if (!DLG_PICK_ANCHOR.equals(key) || offeneAnkerauswahl == null || testText == null) {
            return null;
        }
        TextInputEditText ziel = findViewById(args.getInt(ARG_ANCHOR_TARGET));
        return ziel == null ? null : offeneAnkerauswahl.buildAnchorDialog(ziel);
    }

    @Override
    public void onDialogCancelled(String key, Bundle args) {
        offeneAnkerauswahl = null;
        offenerImport = null;
    }


    public static final String EXTRA_DEPOT = "depot";

    private static final String DIVIDEND = "dividend";

    /**
     * Die festen Felder dieser Art in der Reihenfolge, in der sie auf der Seite stehen.
     *
     * <p>Maßgeblich ist die Buchungsmaske: Wer hier eine falsch gelernte Regel nachbessert, hat die
     * Maske im Kopf, und zwei verschieden sortierte Listen für dieselbe Sache muss dann jedes Mal
     * jemand aufeinander abbilden. Darum genau dieselben Felder in genau derselben Folge wie
     * {@code activity_edit_security_tx.xml} samt {@code SecurityTxEditActivity#moveTotalField} sie
     * zeigt — bei Kauf und Verkauf der Gesamtbetrag gleich unter dem Datum, bei einer Dividende das
     * Netto am Ende der Kette Brutto − Steuer.</p>
     *
     * <p>Bei einer Dividende fehlen Anzahl und Kurs: eine Ertragsgutschrift bucht keine Stücke, und ein
     * Kurs je Stück ist dort die Dividende selbst, die schon als Brutto dasteht. Umgekehrt fehlt bei
     * Kauf und Verkauf das Brutto — es wird aus Anzahl × Kurs gerechnet, und die Maske blendet es
     * deshalb aus. Eine dennoch gespeicherte Brutto-Regel bleibt erhalten, siehe {@link #edited()}.</p>
     *
     * <p>Die Teilbetrag-Blöcke stehen nicht hier, sondern hinter allen festen Feldern — siehe
     * {@link #addPartSection}.</p>
     */
    private static Field[] fieldsFor(String action) {
        if (DIVIDEND.equals(action)) {
            return new Field[]{Field.DATE, Field.GROSS, Field.FEE, Field.NET};
        }
        return new Field[]{Field.DATE, Field.NET, Field.SHARES, Field.PRICE, Field.FEE};
    }

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY);

    private Repository repository;
    private StatementTemplates store;
    private String depot;
    private final List<StatementTemplate> templates = new ArrayList<>();
    private int current = -1;

    private MaterialToolbar toolbar;
    private PickerTextView editTemplate;
    private TextInputLayout templateLayout;
    private TextView textEmpty;
    private LinearLayout fieldContainer;
    private View btnTry;
    private View btnSave;
    private View btnDelete;
    private View testButtons;
    private TextView textTestFile;

    /**
     * Die Testabrechnung, an der die Regeln gemessen werden — sie hält die ganze Sitzung über und wird
     * nicht gespeichert.
     *
     * <p>Sie ist der Unterschied zwischen Raten und Sehen: ohne sie tippt man Beschriftungen ein und
     * erfährt erst beim nächsten Import, ob sie greifen.</p>
     */
    private Uri testUri;
    private PdfText testText;
    /** Die eigene Rechentastatur; sie bedient das Feld für die feste Gebühr. */
    private CalcKeyboardView calcKeyboard;

    /** Der Arbeitsstand je Feld; er lebt in den Eingabefeldern und wird erst beim Speichern eingesammelt. */
    private final Map<Field, FieldForm> forms = new EnumMap<>(Field.class);
    /**
     * Die Bereiche der einzelnen Steuer- bzw. Gebührenzeilen unter dem Gesamtbetrag — und dasselbe
     * für den Ertrag. Sie stehen unter ihrem Oberbereich und sind so gebaut wie er; was sie von den
     * festen Bereichen unterscheidet, ist allein, dass es beliebig viele davon geben darf.
     */
    private final List<FieldForm> feePartForms = new ArrayList<>();
    private final List<FieldForm> incomePartForms = new ArrayList<>();

    /**
     * Die Kategorienliste für die feste Gebühr; {@code null}, solange sie noch lädt.
     *
     * <p>Sie trifft asynchron ein, die Feldformulare entstehen aber sofort. Deshalb wird sie hier
     * gehalten und beim Eintreffen nachgereicht — sonst hinge es am Zufall, was zuerst da ist.</p>
     */
    private CategoryFilterAdapter categoryAdapter;

    private ActivityResultLauncher<String[]> tryLauncher;
    private ActivityResultLauncher<String[]> importLauncher;

    /**
     * Die eingelesene Regeldatei, solange die Rückfrage dazu offen ist.
     *
     * <p>Wie {@link #offeneAnkerauswahl} nicht in ein {@link Bundle} zu legen — es sind fertige
     * Vorlagen mit ihren Regeln. Nach einer Drehung ist sie weg und die Rückfrage verschwindet; ein
     * erneuter Griff ins Menü holt die Datei wieder. Das ist der harmlose Ausgang: verschwindet die
     * Frage, ist nichts eingespielt.</p>
     */
    private de.spahr.ausgaben.statement.StatementRulesIo.Parsed offenerImport;

    /**
     * Das Leserecht an der Testabrechnung über einen Prozesstod hinweg festhalten.
     *
     * <p>Was der Dateiwähler liefert, gilt zunächst nur für diesen Lauf der App. Die Maske hält die
     * Adresse aber weiter (Anzeigen der PDF, erneutes Prüfen) — nach einem Prozesstod scheiterte der
     * Zugriff dann mit einer {@code SecurityException}, die als allgemeines „Beleg nicht lesbar"
     * beim Nutzer ankam.</p>
     *
     * <p>Scheitert das Festhalten selbst, geht es ohne weiter: Das Recht für diesen Lauf besteht
     * bereits, und die Maske funktioniert bis zum Schließen. Manche Dateianbieter geben kein
     * dauerhaftes Recht heraus — daran soll die Prüfung nicht scheitern.</p>
     */
    private void merkeZugriffsrecht(android.net.Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            android.util.Log.i("StatementRules", "kein dauerhaftes Leserecht für " + uri, e);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_statement_rules);
        repository = new Repository(this);
        store = new StatementTemplates(this);
        depot = getIntent().getStringExtra(EXTRA_DEPOT);
        if (depot == null) {
            depot = "";
        }

        toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.statement_rules_menu);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_rules_share) {
                shareRules();
                return true;
            }
            if (item.getItemId() == R.id.action_rules_import) {
                // Ohne Einschränkung auf application/json: Eine Regeldatei kommt meist als Anhang aus
                // Mail oder Messenger, und die geben ihr oft text/plain oder octet-stream mit. Ein
                // enger Filter blendete dann genau die Datei aus, die der Nutzer gerade bekommen hat;
                // ob sie taugt, entscheidet ohnehin erst das Lesen.
                importLauncher.launch(new String[]{"*/*"});
                return true;
            }
            return false;
        });
        editTemplate = findViewById(R.id.editTemplate);
        templateLayout = findViewById(R.id.templateLayout);
        textEmpty = findViewById(R.id.textEmpty);
        fieldContainer = findViewById(R.id.fieldContainer);
        btnTry = findViewById(R.id.btnTry);
        btnSave = findViewById(R.id.btnSave);
        btnDelete = findViewById(R.id.btnDelete);
        testButtons = findViewById(R.id.testButtons);
        textTestFile = findViewById(R.id.textTestFile);
        calcKeyboard = findViewById(R.id.calcKeyboard);
        calcKeyboard.reserveSpaceWith(findViewById(R.id.calcSpacer));

        btnTry.setOnClickListener(v -> tryLauncher.launch(new String[]{"application/pdf"}));
        btnSave.setOnClickListener(v -> save());
        btnDelete.setOnClickListener(v -> confirmDelete());
        findViewById(R.id.btnShowPdf).setOnClickListener(v -> showPdf());
        findViewById(R.id.btnShowText).setOnClickListener(v -> {
            if (testText != null) {
                showText(testText);
            }
        });

        tryLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri != null) {
                        merkeZugriffsrecht(uri);
                        useTestStatement(uri);
                    }
                });
        importLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri != null) {
                        readRulesFile(uri);
                    }
                });

        repository.getCategoriesGrouped(g -> {
            categoryAdapter = new CategoryFilterAdapter(this, null,
                    getString(R.string.category_group_expense), g.expense,
                    getString(R.string.category_group_income), g.income);
            for (FieldForm form : alleFormulare()) {
                form.attachCategories(categoryAdapter);
            }
        });

        load();

        // Kommt die Seite aus der Rückmeldung nach dem Merken, bringt sie die Abrechnung gleich mit:
        // Ohne sie stünde man vor Regeln, von denen man eben erfahren hat, dass sie nicht greifen – und
        // müsste die Datei von Hand wieder heraussuchen, um zu sehen, woran es liegt.
        if (getIntent().getData() != null) {
            useTestStatement(getIntent().getData());
        }
    }

    // ---- Vorlagen ----

    private void load() {
        templates.clear();
        templates.addAll(store.all(depot));
        boolean any = !templates.isEmpty();
        textEmpty.setVisibility(any ? View.GONE : View.VISIBLE);
        templateLayout.setVisibility(any ? View.VISIBLE : View.GONE);
        btnTry.setVisibility(any ? View.VISIBLE : View.GONE);
        btnSave.setVisibility(any ? View.VISIBLE : View.GONE);
        btnDelete.setVisibility(any ? View.VISIBLE : View.GONE);
        // Zu teilen gibt es nur etwas, wo etwas gelernt wurde. Das Einspielen bleibt dagegen immer
        // erreichbar – es ist der einzige Weg, auf dem diese Seite eine Vorlage überhaupt anlegen kann.
        toolbar.getMenu().findItem(R.id.action_rules_share).setVisible(any);
        fieldContainer.removeAllViews();
        forms.clear();
        if (!any) {
            current = -1;
            return;
        }
        List<String> labels = new ArrayList<>();
        for (StatementTemplate t : templates) {
            labels.add(labelOf(t));
        }
        PickerAdapters.plain(editTemplate, labels);
        editTemplate.setOnItemClickListener((parent, view, position, id) -> show(position));
        show(0);
    }

    /** „Kauf — Endbetrag zu Ihren Lasten": Art und Gesamtsumme unterscheiden die Vorlagen einer Bank. */
    private String labelOf(StatementTemplate t) {
        AnchorRule net = t.rule(Field.NET);
        String anchor = net == null || net.anchors.isEmpty() ? "" : net.anchors.get(0);
        String action = actionLabel(t.action);
        return anchor.isEmpty() ? action : action + "  —  " + anchor;
    }

    private String actionLabel(String action) {
        if ("sell".equals(action)) {
            return getString(R.string.action_sell);
        }
        return DIVIDEND.equals(action) ? getString(R.string.action_dividend)
                : getString(R.string.action_buy);
    }

    private void show(int index) {
        if (index < 0 || index >= templates.size()) {
            return;
        }
        current = index;
        StatementTemplate t = templates.get(index);
        editTemplate.setText(labelOf(t), false);
        fieldContainer.removeAllViews();
        forms.clear();
        feePartForms.clear();
        incomePartForms.clear();
        for (Field field : fieldsFor(t.action)) {
            FieldForm form = new FieldForm(field, t);
            forms.put(field, form);
            fieldContainer.addView(form.view);
        }
        // Und dahinter die Blöcke, in denen beliebig viele Zeilen entstehen können — erst die festen
        // Felder, die es immer gibt. Ertrag vor Gebühr, wie in der Maske (incomeSplitBox steht dort vor
        // feeSplitBox).
        if (DIVIDEND.equals(t.action)) {
            addPartSection(Field.GROSS, t.incomeParts, incomePartForms);
        }
        addPartSection(Field.FEE, t.feeParts, feePartForms);
        updateAllFound();
    }

    /**
     * Ein Teilbetrag-Block: Überschrift, die vorhandenen Zeilen, darunter der Knopf für eine weitere.
     *
     * <p>Am Ende der Seite und nicht mehr unter dem Feld, zu dem er gehört — so steht er auch in der
     * Buchungsmaske, und beide Listen sollen sich decken. Die Überschrift wird damit unentbehrlich:
     * bei einer Dividende folgen zwei solche Blöcke aufeinander, und ohne sie wäre nicht zu sehen,
     * welche Zeile zum Ertrag und welche zur Steuer gehört. Sie nimmt dieselben Wörter wie die Maske
     * (siehe {@link StatementFieldNames#feeCategoryHeading}).</p>
     */
    private void addPartSection(Field field, List<StatementTemplate.PartRule> parts,
                                List<FieldForm> into) {
        TextView heading = (TextView) LayoutInflater.from(this)
                .inflate(R.layout.item_statement_part_heading, fieldContainer, false);
        heading.setText(field == Field.FEE
                ? StatementFieldNames.feeCategoryHeading(this, templates.get(current).action)
                : getString(R.string.security_tx_income_category));
        fieldContainer.addView(heading);
        for (StatementTemplate.PartRule part : parts) {
            into.add(addPartForm(field, part, into));
        }
        View add = LayoutInflater.from(this)
                .inflate(R.layout.item_statement_add_part, fieldContainer, false);
        add.setOnClickListener(v -> {
            FieldForm form = addPartForm(field, null, into);
            into.add(form);
            // Vor den Knopf, damit er unten bleibt und der neue Bereich bei den anderen steht.
            fieldContainer.addView(form.view, fieldContainer.indexOfChild(add));
            form.updateFound();
        });
        fieldContainer.addView(add);
    }

    private FieldForm addPartForm(Field field, StatementTemplate.PartRule part,
                                  List<FieldForm> into) {
        FieldForm form = new FieldForm(field, templates.get(current), part);
        form.view.findViewById(R.id.btnRemoveField).setOnClickListener(v -> {
            into.remove(form);
            fieldContainer.removeView(form.view);
        });
        if (part != null) {
            fieldContainer.addView(form.view);
        }
        return form;
    }

    /** Der Name eines Teilbetrags in der Überschrift; solange keiner feststeht, ein Platzhalter. */
    private String partName(String label) {
        return label == null || label.trim().isEmpty()
                ? getString(R.string.statement_rules_part_new) : label.trim();
    }

    /** Alle Bereiche der Seite: die festen Felder und die Teilbeträge darunter. */
    private List<FieldForm> alleFormulare() {
        List<FieldForm> out = new ArrayList<>(forms.values());
        out.addAll(feePartForms);
        out.addAll(incomePartForms);
        return out;
    }

    /** Nach jedem Wechsel der Vorlage oder der Testabrechnung: alle Bereiche neu ausrechnen. */
    private void updateAllFound() {
        for (FieldForm form : alleFormulare()) {
            form.updateFound();
        }
    }

    // ---- Ein Feld ----

    private final class FieldForm {

        private final Field field;
        private final View view;
        private final LinearLayout container;
        private final PickerTextView direction;
        private final PickerTextView position;
        private final TextInputEditText currency;
        private final MaterialSwitch sum;
        /** Nur bei der Gebühr sichtbar: ein Betrag, den die Bank nicht ausdruckt. */
        private final TextInputEditText fixedFee;
        private final PickerTextView fixedFeeCategory;
        /** Nur beim Gesamtbetrag sichtbar: steckt die feste Gebühr schon darin? */
        private final MaterialSwitch fixedFeeInTotal;
        /** Was die Regel dieses Bereichs in der Testabrechnung liest. */
        private final TextView found;
        private final List<String> anchors = new ArrayList<>();
        /**
         * Die Überschrift; sie ist bei einem Teilbetrag zugleich sein Name.
         *
         * <p>Und der ist die erste Beschriftung — dieselbe, unter der der Betrag in der Abrechnung
         * steht. Ein eigenes Namensfeld gäbe es nur her, dass Name und Beschriftung auseinanderlaufen,
         * und der Name ist genau der Schlüssel, über den die gebuchte Kategorie ihren Betrag
         * wiederfindet.</p>
         */
        private final TextView heading;
        /** {@code true}, wenn dieser Bereich einen Teilbetrag beschreibt und keinen der festen Werte. */
        private final boolean isPart;
        /** Wohin dieser Betrag gebucht wird — nur bei Steuer/Gebühr und Ertrag. */
        private final PickerTextView fieldCategory;
        private final View fieldCategoryLayout;

        FieldForm(Field field, StatementTemplate template) {
            this(field, template, null, false);
        }

        FieldForm(Field field, StatementTemplate template, StatementTemplate.PartRule part) {
            this(field, template, part, true);
        }

        private FieldForm(Field field, StatementTemplate template, StatementTemplate.PartRule part,
                          boolean isPart) {
            this.field = field;
            this.isPart = isPart;
            LayoutInflater inflater = LayoutInflater.from(StatementRulesActivity.this);
            view = inflater.inflate(R.layout.item_statement_field, fieldContainer, false);
            heading = view.findViewById(R.id.textFieldName);
            heading.setText(isPart
                    ? partName(part == null ? "" : part.label)
                    : nameOf(field, template.action));
            view.findViewById(R.id.btnRemoveField)
                    .setVisibility(isPart ? View.VISIBLE : View.GONE);
            fieldCategory = view.findViewById(R.id.editFieldCategory);
            fieldCategoryLayout = view.findViewById(R.id.fieldCategoryLayout);
            // Eine Kategorie hat nur, was auch gebucht wird: die Steuer bzw. Gebühr und – bei einer
            // Dividende – der Ertrag. Anzahl, Kurs, Datum und Gesamtbetrag haben keine.
            boolean mitKategorie = field == Field.FEE
                    || (field == Field.GROSS && DIVIDEND.equals(template.action));
            fieldCategoryLayout.setVisibility(mitKategorie ? View.VISIBLE : View.GONE);
            if (mitKategorie) {
                fieldCategory.setText(isPart
                        ? (part == null ? "" : part.category)
                        : (field == Field.FEE ? template.feeCategory : template.incomeCategory),
                        false);
            }
            container = view.findViewById(R.id.anchorContainer);
            direction = view.findViewById(R.id.editDirection);
            position = view.findViewById(R.id.editPosition);
            currency = view.findViewById(R.id.editCurrency);
            sum = view.findViewById(R.id.switchSum);
            fixedFee = view.findViewById(R.id.editFixedFee);
            fixedFeeCategory = view.findViewById(R.id.editFixedFeeCategory);
            fixedFeeInTotal = view.findViewById(R.id.switchFixedFeeInTotal);
            found = view.findViewById(R.id.textFieldFound);
            // Der feste Betrag gehört zur Gebühr, die Frage nach dem Gesamtbetrag zu diesem – jede an
            // ihren Platz, statt beide in einen Kasten für sich. Ein Teilbetrag hat mit beidem nichts
            // zu tun: die feste Gebühr steht ja gerade nicht in der Abrechnung.
            view.findViewById(R.id.fixedFeeBox)
                    .setVisibility(field == Field.FEE && !isPart ? View.VISIBLE : View.GONE);
            fixedFeeInTotal.setVisibility(field == Field.NET && !isPart ? View.VISIBLE : View.GONE);
            if (field == Field.FEE && !isPart) {
                wireCalcField(fixedFee);
                if (template.fixedFeeCents > 0) {
                    fixedFee.setText(MoneyFormat.plain(template.fixedFeeCents));
                }
                fixedFeeCategory.setText(template.fixedFeeCategory, false);
                // Der Schalter beim Gesamtbetrag hat erst einen Gegenstand, wenn hier etwas steht.
                fixedFee.addTextChangedListener(new SimpleWatcher(
                        StatementRulesActivity.this::updateFixedFeeSwitch));
            }
            if (field == Field.NET && !isPart) {
                fixedFeeInTotal.setChecked(template.fixedFeeInTotal);
                // Ohne festen Betrag hat die Frage keinen Gegenstand.
                dim(fixedFeeInTotal, template.fixedFeeCents > 0);
            }
            // Einmal, und zwar hier: Das Anhängen des Adapters leert das Feld und stellt es wieder her
            // (siehe anhaengen). Stand es davor, wurde der eben gesetzte Text durch den zweiten Aufruf
            // noch einmal durchgereicht — zweimal Arbeit für dasselbe Ergebnis.
            attachCategories(categoryAdapter);

            regelUebernehmen(template, part);
        }

        /**
         * Der zweite Teil des Aufbaus: die gelernte Regel in die Felder legen und die Bedienung
         * verdrahten.
         *
         * <p>Getrennt vom Konstruktor, weil dort nur noch stehen soll, was die {@code final}-Felder
         * belegt — alles Übrige ließ ihn auf knapp hundert Zeilen anwachsen.</p>
         */
        private void regelUebernehmen(StatementTemplate template, StatementTemplate.PartRule part) {
            PickerAdapters.plain(direction, richtungen());

            AnchorRule rule = isPart ? (part == null ? null : part.rule) : template.rule(field);
            if (rule != null) {
                anchors.addAll(rule.anchors);
                direction.setText(richtung(rule.direction, rule.lineDistance), false);
                currency.setText(rule.currency);
                sum.setChecked(rule.sum);
                position.setText(stelle(rule.position, rule.nth), false);
            } else {
                direction.setText(getString(R.string.statement_rules_same_line), false);
                position.setText(stelle(AnchorRule.Position.LAST, 1), false);
            }
            if (isPart && anchors.isEmpty()) {
                // Ein frischer Teilbetrag beginnt mit einer leeren Zeile: sie ist der Griff, mit dem
                // sich die Werteliste der Testabrechnung öffnen lässt.
                anchors.add("");
            }
            updateStellen();
            view.findViewById(R.id.btnAddAnchor).setOnClickListener(v -> {
                readBack();
                anchors.add("");
                renderRows();
            });
            // Jede Einstellung, die das Lesen ändert, rechnet die Fundstelle sofort neu: sonst müsste
            // man raten, ob eine Änderung etwas gebracht hat.
            direction.setOnItemClickListener((p, v, at, id) -> {
                updateStellen();   // «in der Spalte» gibt es nur, wenn der Wert nicht hier steht
                updateFound();
            });
            position.setOnItemClickListener((p, v, at, id) -> updateFound());
            currency.addTextChangedListener(new SimpleWatcher(this::updateFound));
            sum.setOnCheckedChangeListener((b, checked) -> {
                readBack();     // sonst gingen gerade getippte Beschriftungen beim Neuaufbau verloren
                renderRows();
                updateFound();
            });
            renderRows();
        }

        /**
         * Die Beschriftungen als Zeilen in ihrer Rangfolge. Neu gebaut wird bei jeder Verschiebung — das
         * ist bei einer Handvoll Zeilen billiger als der Versuch, Ansichten zu tauschen.
         */
        private void renderRows() {
            container.removeAllViews();
            LayoutInflater inflater = LayoutInflater.from(StatementRulesActivity.this);
            for (int i = 0; i < anchors.size(); i++) {
                final int at = i;
                View row = inflater.inflate(R.layout.item_statement_anchor, container, false);
                TextInputEditText edit = row.findViewById(R.id.editAnchor);
                edit.setText(anchors.get(i));
                edit.addTextChangedListener(new SimpleWatcher(this::updateFound));
                if (testText != null) {
                    // Mit einer Testabrechnung ist das Feld zuerst eine Auswahl und erst auf Wunsch ein
                    // Eingabefeld: die Beschriftung muss buchstabengenau so dastehen, wie die App sie
                    // sieht, und das trifft man tippend selten auf Anhieb.
                    edit.setShowSoftInputOnFocus(false);
                    edit.setOnClickListener(v -> pickAnchor(edit));
                }
                ImageButton up = row.findViewById(R.id.btnAnchorUp);
                ImageButton down = row.findViewById(R.id.btnAnchorDown);
                // An den Enden führt der Pfeil nirgendwohin; abgeblendet sagt das, ohne ihn zu verstecken.
                dim(up, at > 0);
                dim(down, at < anchors.size() - 1);
                up.setOnClickListener(v -> swap(at, at - 1));
                down.setOnClickListener(v -> swap(at, at + 1));
                row.findViewById(R.id.btnAnchorRemove).setOnClickListener(v -> {
                    readBack();
                    anchors.remove(at);
                    renderRows();
                });
                container.addView(row);
            }
            // Der Hinweis auf die Rangfolge lohnt erst, wenn es eine gibt.
            view.findViewById(R.id.textFieldHint)
                    .setVisibility(anchors.size() > 1 && !sum.isChecked() ? View.VISIBLE : View.GONE);
        }

        private void swap(int from, int to) {
            readBack();
            if (from < 0 || to < 0 || from >= anchors.size() || to >= anchors.size()) {
                return;
            }
            String moved = anchors.remove(from);
            anchors.add(to, moved);
            renderRows();
        }

        /** Was in den Feldern steht, zurück ins Modell — vor jedem Verschieben und vor dem Speichern. */
        private void readBack() {
            for (int i = 0; i < container.getChildCount() && i < anchors.size(); i++) {
                TextInputEditText edit = container.getChildAt(i).findViewById(R.id.editAnchor);
                anchors.set(i, edit.getText() == null ? "" : edit.getText().toString().trim());
            }
        }

        /** Die Regel, wie sie jetzt dasteht; {@code null}, wenn keine Beschriftung übrig blieb. */
        AnchorRule toRule() {
            readBack();
            List<String> kept = keptAnchors();
            return kept.isEmpty() ? null : ruleFor(kept, sum.isChecked());
        }

        /**
         * Die Überschrift eines Teilbetrags folgt seiner ersten Beschriftung — sie ist sein Name.
         * Die festen Bereiche behalten ihren.
         */
        private void updateHeading() {
            if (isPart) {
                heading.setText(partName(partLabel()));
            }
        }

        /** Der Name dieses Teilbetrags: die erste eingetragene Beschriftung. */
        String partLabel() {
            List<String> kept = keptAnchors();
            return kept.isEmpty() ? "" : kept.get(0);
        }

        /** Die Teilbetragsregel, wie sie jetzt dasteht; {@code null}, wenn noch nichts eingetragen ist. */
        StatementTemplate.PartRule toPartRule() {
            AnchorRule rule = toRule();
            return rule == null ? null
                    : new StatementTemplate.PartRule(partLabel(), rule, category());
        }

        /** Die eingetragenen Beschriftungen ohne die leeren Zeilen. */
        private List<String> keptAnchors() {
            List<String> kept = new ArrayList<>();
            for (String anchor : anchors) {
                if (!anchor.trim().isEmpty()) {
                    kept.add(anchor.trim());
                }
            }
            return kept;
        }

        /**
         * Setzt die Auswahl „welche Zahl" passend zur Richtung.
         *
         * <p>Bei „in derselben Zeile" fehlt die Spaltenwahl — sie fände dort nie etwas. Stand sie
         * vorher, fällt die Einstellung auf den Regelfall zurück, statt stumm eine Regel zu
         * hinterlassen, die nichts liest.</p>
         */
        private void updateStellen() {
            boolean mitSpalte = chosenDirection() != AnchorRule.Direction.SAME_LINE;
            if (!mitSpalte && chosenPosition() == AnchorRule.Position.COLUMN) {
                position.setText(stelle(AnchorRule.Position.LAST, 1), false);
            }
            PickerAdapters.plain(position, stellen(mitSpalte));
        }

        /**
         * Eine Regel aus den gerade eingestellten Angaben. Der Anker kommt von aussen, damit sich
         * damit auch ein einzelner prüfen lässt — für die Anzeige der Einzelwerte beim Summieren und
         * für die Auswahlliste, die ja nur zeigen darf, was die fertige Regel auch findet.
         */
        private AnchorRule ruleFor(List<String> anchorList, boolean summed) {
            return new AnchorRule(anchorList, chosenDirection(), summed, chosenCurrency(),
                    chosenPosition(), chosenNth(), chosenLineDistance());
        }

        private AnchorRule.Direction chosenDirection() {
            String gewaehlt = direction.getText() == null ? "" : direction.getText().toString();
            if (getString(R.string.statement_rules_same_line).equals(gewaehlt)) {
                return AnchorRule.Direction.SAME_LINE;
            }
            for (int i = 0; i <= AnchorRule.MAX_DISTANCE; i++) {
                if (richtung(AnchorRule.Direction.LINE_ABOVE, i).equals(gewaehlt)) {
                    return AnchorRule.Direction.LINE_ABOVE;
                }
            }
            return AnchorRule.Direction.LINE_BELOW;
        }

        /** 0 heisst «suchend» – siehe {@link AnchorRule#lineDistance}. */
        private int chosenLineDistance() {
            String gewaehlt = direction.getText() == null ? "" : direction.getText().toString();
            for (int i = 1; i <= AnchorRule.MAX_DISTANCE; i++) {
                if (richtung(AnchorRule.Direction.LINE_BELOW, i).equals(gewaehlt)
                        || richtung(AnchorRule.Direction.LINE_ABOVE, i).equals(gewaehlt)) {
                    return i;
                }
            }
            return 0;
        }

        private AnchorRule.Position chosenPosition() {
            String gewaehlt = position.getText() == null ? "" : position.getText().toString();
            if (stelle(AnchorRule.Position.COLUMN, 1).equals(gewaehlt)) {
                return AnchorRule.Position.COLUMN;
            }
            for (int i = 1; i <= MAX_STELLE; i++) {
                if (stelle(AnchorRule.Position.FIRST, i).equals(gewaehlt)) {
                    return AnchorRule.Position.FIRST;
                }
            }
            return AnchorRule.Position.LAST;
        }

        private int chosenNth() {
            String gewaehlt = position.getText() == null ? "" : position.getText().toString();
            for (int i = 1; i <= MAX_STELLE; i++) {
                if (stelle(AnchorRule.Position.FIRST, i).equals(gewaehlt)
                        || stelle(AnchorRule.Position.LAST, i).equals(gewaehlt)) {
                    return i;
                }
            }
            return 1;
        }

        private String chosenCurrency() {
            return currency.getText() == null ? "" : currency.getText().toString().trim();
        }

        /**
         * Schreibt unter die Überschrift, was die Regel in der Testabrechnung liest.
         *
         * <p>Beim Summieren stehen die Einzelwerte da und dahinter ihre Summe — sonst sähe man einer
         * zu hohen Steuer nicht an, welche Zeile zu viel mitgezählt hat.</p>
         */
        void updateFound() {
            if (testText == null) {
                found.setVisibility(View.GONE);
                readBack();
                updateHeading();
                return;
            }
            found.setVisibility(View.VISIBLE);
            readBack();
            updateHeading();
            List<String> kept = keptAnchors();
            AnchorRule rule = kept.isEmpty() ? null : ruleFor(kept, sum.isChecked());
            String value = rule == null ? null : valueOf(field, rule, testText);
            if (value == null) {
                found.setText(getString(R.string.statement_rules_found,
                        getString(R.string.statement_rules_nothing)));
                return;
            }
            StringBuilder was = new StringBuilder();
            if (sum.isChecked() && kept.size() > 1) {
                for (String anchor : kept) {
                    String part = valueOf(field,
                            ruleFor(java.util.Collections.singletonList(anchor), false), testText);
                    if (part != null) {
                        was.append(was.length() > 0 ? " + " : "").append(anchor).append(' ').append(part);
                    }
                }
                was.append(was.length() > 0 ? "  =  " : "").append(value);
            } else {
                was.append(value);
                String anchor = rule.matchedAnchor(testText);
                if (anchor != null && kept.size() > 1) {
                    was.append("   (").append(anchor).append(')');
                }
            }
            found.setText(getString(R.string.statement_rules_found, was.toString()));
        }

        /**
         * Legt die Werte der Testabrechnung zur Auswahl vor — beim Datum die gefundenen Datumsangaben,
         * sonst die Zahlen, jeweils mit ihrer Beschriftung.
         *
         * <p>Gelesen wird mit den Einstellungen, die im Bereich gerade stehen: wer „eine Zeile tiefer"
         * und „die 2. von rechts" gewählt hat, bekommt die Zahlen zu sehen, die <b>so</b> erreichbar
         * sind. Alles andere wäre eine Liste voller Werte, die die fertige Regel nie fände.</p>
         */
        private void pickAnchor(TextInputEditText edit) {
            offeneAnkerauswahl = this;
            Bundle args = new Bundle();
            args.putInt(ARG_ANCHOR_TARGET, edit.getId());
            HostedDialog.show(StatementRulesActivity.this, DLG_PICK_ANCHOR, args);
        }

        /**
         * Baut die Auswahlliste — beim ersten Mal und nach jeder Drehung erneut (siehe
         * {@link HostedDialog}).
         *
         * <p>Was zur Auswahl steht, wird jedes Mal frisch aus der Testabrechnung gelesen; eine
         * Zwischenspeicherung wäre hier falsch, denn die Einstellungen des Bereichs können sich
         * zwischendurch geändert haben.</p>
         */
        android.app.Dialog buildAnchorDialog(TextInputEditText edit) {
            readBack();
            List<String> labels = new ArrayList<>();
            List<AnchorRule> rules = new ArrayList<>();
            List<CharSequence> shown = new ArrayList<>();
            if (field == Field.DATE) {
                for (de.spahr.ausgaben.statement.StatementScan.DateCandidate c
                        : de.spahr.ausgaben.statement.StatementScan.dates(testText)) {
                    labels.add(c.label);
                    rules.add(c.rule);
                    shown.add(beschreibung(c.label, dateFormat.format(new Date(c.millis)), c.rule));
                }
            } else {
                for (de.spahr.ausgaben.statement.StatementScan.ValueCandidate c
                        : de.spahr.ausgaben.statement.StatementScan.values(testText, chosenDirection(),
                        chosenLineDistance(), chosenPosition(), chosenNth(), chosenCurrency())) {
                    labels.add(c.label);
                    rules.add(c.rule);
                    shown.add(beschreibung(c.label, formatValue(field, c.value), c.rule));
                }
            }
            shown.add(getString(R.string.statement_rules_type_own));
            return new AppDialog(StatementRulesActivity.this)
                    .setTitle(R.string.statement_rules_pick_value)
                    .setItems(shown.toArray(new CharSequence[0]), (d, which) -> {
                        if (which >= labels.size()) {
                            // Von Hand: ab jetzt ist das Feld ein gewöhnliches Eingabefeld.
                            edit.setOnClickListener(null);
                            edit.setShowSoftInputOnFocus(true);
                            edit.requestFocus();
                            return;
                        }
                        edit.setText(labels.get(which));
                        // Die Wahl bringt ihre Leseart mit: wer die Spaltenüberschrift antippt, meint
                        // „in der Spalte, so viele Zeilen tiefer" – und nicht die Einstellung, die
                        // vorher im Bereich stand. Sonst stünde die Beschriftung da und läse nichts.
                        uebernimm(rules.get(which));
                        readBack();
                        updateFound();
                    })
                    .create();
        }

        /**
         * Ein Eintrag der Auswahlliste. Dasselbe Datum steht oft zweimal darin – einmal über die
         * Beschriftung daneben, einmal über die Spaltenüberschrift darüber. Ohne den Zusatz wären die
         * beiden Zeilen nicht auseinanderzuhalten.
         */
        private CharSequence beschreibung(String label, String wert, AnchorRule rule) {
            String zusatz = rule == null || rule.direction == AnchorRule.Direction.SAME_LINE ? ""
                    : "   (" + richtung(rule.direction, rule.lineDistance) + ")";
            return label + ":  " + wert + zusatz;
        }

        /** Richtung, Abstand und Stelle einer angetippten Auswahl in den Bereich übernehmen. */
        private void uebernimm(AnchorRule rule) {
            if (rule == null) {
                return;
            }
            direction.setText(richtung(rule.direction, rule.lineDistance), false);
            updateStellen();
            position.setText(stelle(rule.position, rule.nth), false);
        }

        /** Die Kategorienliste nachreichen – sie trifft später ein als das Formular. */
        void attachCategories(CategoryFilterAdapter adapter) {
            if (adapter == null) {
                return;
            }
            if (field == Field.FEE && !isPart) {
                anhaengen(fixedFeeCategory, adapter);
            }
            if (fieldCategoryLayout.getVisibility() == View.VISIBLE) {
                anhaengen(fieldCategory, adapter);
            }
        }

        /** Das Anhängen des Adapters leert das Feld – der Stand von vorhin gehört zurück. */
        private void anhaengen(PickerTextView field, CategoryFilterAdapter adapter) {
            String stand = field.getText() == null ? "" : field.getText().toString();
            PickerAdapters.categories(field, adapter);
            field.setText(stand, false);
        }

        /** Die eingetragene Kategorie dieses Bereichs. */
        String category() {
            return fieldCategory.getText() == null ? "" : fieldCategory.getText().toString().trim();
        }

        /** Der feste Betrag in Cent; 0, wenn keiner eingetragen ist. */
        long fixedFeeCents() {
            if (field != Field.FEE) {
                return 0;
            }
            String raw = fixedFee.getText() == null ? "" : fixedFee.getText().toString().trim();
            if (raw.isEmpty()) {
                return 0;
            }
            Long cents = de.spahr.ausgaben.settings.AmountExpression.toCents(raw);
            return cents == null ? 0 : Math.abs(cents);
        }

        String fixedFeeCategory() {
            if (field != Field.FEE || fixedFeeCategory.getText() == null) {
                return "";
            }
            return fixedFeeCategory.getText().toString().trim();
        }

        boolean fixedFeeInTotal() {
            return field == Field.NET && fixedFeeInTotal.isChecked();
        }

        MaterialSwitch fixedFeeInTotalSwitch() {
            return fixedFeeInTotal;
        }

        Field field() {
            return field;
        }
    }

    /**
     * Bis zu dieser Stelle lässt sich von Hand wählen.
     *
     * <p>Der Kommentar sagte hier bis 1.12 „so weit sucht auch der Lerner" — das stimmte nicht: der
     * kommt nur bis {@link de.spahr.ausgaben.statement.TemplateLearner#MAX_STELLE}. Die beiden Zahlen
     * gehören auch nicht gleichgezogen. Der Lerner hört früh auf, weil jede weitere Stelle die Aussicht
     * erhöht, dass irgendeine Zahl der Zeile zufällig passt und eine Regel entsteht, die beim nächsten
     * Beleg etwas anderes meint. Wer die Stelle dagegen <b>selbst</b> wählt, weiß, was er meint — dort
     * ist die Beschränkung nur im Weg.</p>
     */
    private static final int MAX_STELLE = 6;

    /**
     * Die Auswahl „wo steht der Wert": in derselben Zeile, irgendwo darunter, oder genau eine, zwei,
     * drei Zeilen tiefer.
     *
     * <p>Die suchende Fassung steht vorn, weil sie in den meisten Belegen das Richtige tut. Die festen
     * Abstände sind für die Fälle, in denen sie an einer Zwischenzeile hängenbleibt, die zufällig eine
     * Zahl trägt.</p>
     */
    private List<String> richtungen() {
        List<String> out = new ArrayList<>();
        out.add(getString(R.string.statement_rules_same_line));
        for (int i = 0; i <= AnchorRule.MAX_DISTANCE; i++) {
            out.add(richtung(AnchorRule.Direction.LINE_BELOW, i));
            out.add(richtung(AnchorRule.Direction.LINE_ABOVE, i));
        }
        return out;
    }

    /** Die Beschriftung zu einer Richtung samt Abstand. */
    private String richtung(AnchorRule.Direction dir, int lineDistance) {
        if (dir == AnchorRule.Direction.SAME_LINE) {
            return getString(R.string.statement_rules_same_line);
        }
        boolean above = dir == AnchorRule.Direction.LINE_ABOVE;
        if (lineDistance <= 0) {
            return getString(above ? R.string.statement_rules_line_above
                    : R.string.statement_rules_line_below);
        }
        if (lineDistance == 1) {
            return getString(above ? R.string.statement_rules_above_one
                    : R.string.statement_rules_below_one);
        }
        return getString(above ? R.string.statement_rules_above_n : R.string.statement_rules_below_n,
                lineDistance);
    }

    /**
     * Die Auswahl „welche Zahl der Zeile", von aussen nach innen — und zuletzt die Spalte.
     *
     * <p>Die Spaltenwahl steht nur zur Verfügung, wenn der Wert nicht in derselben Zeile steht: dort
     * wäre die Spalte der Beschriftung die Beschriftung selbst, die Einstellung fände also nie
     * etwas.</p>
     */
    private List<String> stellen(boolean mitSpalte) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= MAX_STELLE; i++) {
            out.add(stelle(AnchorRule.Position.LAST, i));
        }
        for (int i = 1; i <= MAX_STELLE; i++) {
            out.add(stelle(AnchorRule.Position.FIRST, i));
        }
        if (mitSpalte) {
            out.add(stelle(AnchorRule.Position.COLUMN, 1));
        }
        return out;
    }

    /** Die Beschriftung zu einer Stelle: „die letzte", „die 2. von rechts", „die erste (Spalte)" … */
    private String stelle(AnchorRule.Position wo, int nth) {
        if (wo == AnchorRule.Position.COLUMN) {
            return getString(R.string.statement_rules_column);
        }
        if (nth == 1) {
            return getString(wo == AnchorRule.Position.FIRST
                    ? R.string.statement_rules_first_number : R.string.statement_rules_last_number);
        }
        return getString(wo == AnchorRule.Position.FIRST
                ? R.string.statement_rules_nth_left : R.string.statement_rules_nth_right, nth);
    }

    /**
     * Hängt ein Betragsfeld an die eigene Rechentastatur — dieselbe wie in der Erfassungsmaske.
     *
     * <p>Die System-Tastatur taugt hier nicht: ihr Ziffernblock kennt nur den Punkt, und wer das Komma
     * als Dezimalzeichen eingestellt hat, könnte gar keines eingeben. Nebenbei kommt damit das Rechnen
     * mit: eine Ordergebühr steht auch mal als {@code 0,99*2} da.</p>
     */
    private void wireCalcField(TextInputEditText input) {
        AmountField.prepareCalc(input);
        input.setShowSoftInputOnFocus(false);
        input.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                calcKeyboard.attachTo(input);
                calcKeyboard.setOnOk(valid -> {
                    if (valid) {
                        input.clearFocus();
                    }
                });
                calcKeyboard.setVisibility(View.VISIBLE);
                CalcKeyboardView.hideSystemKeyboard(input);
            } else {
                calcKeyboard.setVisibility(View.GONE);
            }
        });
    }

    /**
     * Blendet den Schalter «Feste Gebühr steckt schon im Gesamtbetrag» ab, solange keine feste Gebühr
     * eingetragen ist. Beide Formulare stehen gleichzeitig auf der Seite, deshalb geht das sofort — ohne
     * das müsste man die Seite verlassen und wieder aufsuchen, damit der Schalter erwacht.
     */
    private void updateFixedFeeSwitch() {
        FieldForm fee = forms.get(Field.FEE);
        FieldForm net = forms.get(Field.NET);
        if (fee != null && net != null) {
            dim(net.fixedFeeInTotalSwitch(), fee.fixedFeeCents() > 0);
        }
    }

    private static void dim(View button, boolean usable) {
        button.setEnabled(usable);
        button.setAlpha(usable ? 1f : 0.3f);
    }

    /** Die Feldnamen sind dieselben wie in der Erfassungsmaske – sie richten sich nach der Art. */
    private String nameOf(Field field, String action) {
        return StatementFieldNames.of(this, field, action);
    }

    // ---- Speichern und Löschen ----

    /** Die Vorlage, wie sie gerade in den Feldern steht. */
    private StatementTemplate edited() {
        Map<Field, AnchorRule> rules = new EnumMap<>(Field.class);
        for (FieldForm form : forms.values()) {
            AnchorRule rule = form.toRule();
            if (rule != null) {
                rules.put(form.field(), rule);
            }
        }
        // Was die Seite gar nicht anzeigt, darf sie auch nicht wegwerfen: bei Kauf und Verkauf steht das
        // Brutto hier nicht (siehe fieldsFor), eine von Hand angelegte Regel dafür bleibt trotzdem
        // gültig. Ein leeres Formular ist etwas anderes — dort hat der Nutzer die Regel eben entfernt.
        StatementTemplate bisher = templates.get(current);
        for (Field field : Field.values()) {
            if (!forms.containsKey(field) && bisher.rule(field) != null) {
                rules.put(field, bisher.rule(field));
            }
        }
        long fixedFee = 0;
        String fixedCategory = "";
        boolean inTotal = false;
        String feeCategory = "";
        // Dieselbe Überlegung: die Ertragskategorie hängt am Brutto-Formular und überlebt dessen Fehlen.
        String incomeCategory = forms.containsKey(Field.GROSS) ? "" : bisher.incomeCategory;
        for (FieldForm form : forms.values()) {
            if (form.field() == Field.FEE) {
                fixedFee = form.fixedFeeCents();
                fixedCategory = form.fixedFeeCategory();
                feeCategory = form.category();
            } else if (form.field() == Field.NET) {
                inTotal = form.fixedFeeInTotal();
            } else if (form.field() == Field.GROSS) {
                incomeCategory = form.category();
            }
        }
        return new StatementTemplate(templates.get(current).action, rules,
                fixedFee, fixedCategory, inTotal,
                partRules(feePartForms), partRules(incomePartForms),
                feeCategory, incomeCategory);
    }

    /** Die Teilbetragsregeln eines Abschnitts; unfertige Bereiche fallen dabei weg. */
    private List<StatementTemplate.PartRule> partRules(List<FieldForm> forms) {
        List<StatementTemplate.PartRule> out = new ArrayList<>();
        for (FieldForm form : forms) {
            StatementTemplate.PartRule part = form.toPartRule();
            if (part != null && !part.label.isEmpty()) {
                out.add(part);
            }
        }
        return out;
    }

    private void save() {
        if (current < 0) {
            return;
        }
        StatementTemplate built = edited();
        if (built.rule(Field.NET) == null) {
            // Ohne die Gesamtsumme erkennt sich die Vorlage nicht wieder und könnte auch nichts buchen.
            Toast.makeText(this, R.string.security_tx_need_amounts, Toast.LENGTH_LONG).show();
            return;
        }
        templates.set(current, built);
        store.saveAll(depot, templates);
        Toast.makeText(this, R.string.statement_rules_saved, Toast.LENGTH_SHORT).show();
    }

    private void confirmDelete() {
        if (current < 0) {
            return;
        }
        AppDialog.destructive(this)
                .setTitle(R.string.statement_rules_delete_title)
                .setMessage(R.string.statement_rules_delete_message)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    templates.remove(current);
                    store.saveAll(depot, templates);
                    load();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---- Teilen und Einspielen ----

    /**
     * Die gelernten Regeln dieses Depots als kleine JSON-Datei weitergeben.
     *
     * <p>Zwei Zwecke, ein Griff: Wer die Beschriftungen seiner Bank mühsam nachgebessert hat, kann sie
     * jedem geben, der bei derselben Bank ist — und wessen Abrechnung nicht erkannt wird, schickt die
     * Regel, an der es liegt, statt sie zu beschreiben.</p>
     *
     * <p>In der Datei stehen <b>nur</b> die Vorlagen: Beschriftungen, Leserichtungen, Kategorien. Keine
     * Beträge, keine Wertpapiere, keine ISIN-Zuordnung — das Gelernte ist die Bank, nicht der
     * Bestand.</p>
     */
    private void shareRules() {
        if (templates.isEmpty()) {
            Toast.makeText(this, R.string.statement_rules_share_none, Toast.LENGTH_LONG).show();
            return;
        }
        if (hatUngespeichertes()) {
            // Sonst verschickt man den Stand von vorhin und wundert sich, dass die Nachbesserung, die
            // man gerade eingetragen hat, beim Empfänger fehlt.
            HostedDialog.show(this, DLG_SHARE_UNSAVED, new Bundle());
            return;
        }
        doShareRules();
    }

    /** Steht im Formular etwas, das so noch nicht gespeichert ist? */
    private boolean hatUngespeichertes() {
        return current >= 0 && !edited().sameAs(templates.get(current));
    }

    private android.app.Dialog buildShareUnsavedDialog() {
        return new AppDialog(this)
                .setTitle(R.string.statement_rules_share_unsaved_title)
                .setMessage(R.string.statement_rules_share_unsaved_message)
                .setPositiveButton(R.string.statement_rules_share_unsaved_save, (d, w) -> {
                    save();
                    doShareRules();
                })
                .setNegativeButton(R.string.statement_rules_share_unsaved_anyway,
                        (d, w) -> doShareRules())
                .create();
    }

    /**
     * Schreibt die Datei in den Cache und reicht sie ans Teilen-Blatt weiter.
     *
     * <p>Über den FileProvider und nicht als Pfad: der fremden App wird Lesen für diese eine Datei
     * gestattet, mehr nicht — dasselbe Verfahren wie beim Weitergeben eines Belegs.</p>
     */
    private void doShareRules() {
        try {
            java.io.File dir = new java.io.File(getCacheDir(), "regeln");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new java.io.IOException("Verzeichnis " + dir + " nicht angelegt");
            }
            java.io.File file = new java.io.File(dir, dateiname());
            try (java.io.OutputStream out = new java.io.FileOutputStream(file)) {
                out.write(de.spahr.ausgaben.statement.StatementRulesIo.toFile(depot, templates)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", file);
            android.content.Intent send = new android.content.Intent(
                    android.content.Intent.ACTION_SEND)
                    .setType("application/json")
                    .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    .putExtra(android.content.Intent.EXTRA_SUBJECT,
                            getString(R.string.statement_rules_share_subject, depotName()))
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(android.content.Intent.createChooser(
                    send, getString(R.string.statement_rules_share)));
        } catch (Exception e) {
            android.util.Log.w("StatementRules", "Regeln nicht zum Teilen bereitzulegen", e);
            Toast.makeText(this, R.string.statement_rules_share_failed, Toast.LENGTH_LONG).show();
        }
    }

    /** Das Depot, wie es sich anschreiben lässt; ohne eins der Name der Seite. */
    private String depotName() {
        return depot.trim().isEmpty() ? getString(R.string.statement_rules) : depot.trim();
    }

    /**
     * Der Dateiname, den der Empfänger sieht: {@code ausgaben-regeln-DKB.json}.
     *
     * <p>Aus dem Depotnamen bleibt nur, was in jedem Dateisystem und in jedem Mailanhang unfällig
     * ist — alles andere wird zum Bindestrich. Ein Depot heißt auch mal „Depot 1234/5678", und ein
     * Schrägstrich darin träfe beim Anlegen der Datei auf ein Verzeichnis, das es nicht gibt.</p>
     */
    private String dateiname() {
        String rein = depotName().replaceAll("[^\\p{L}\\p{N}]+", "-")
                .replaceAll("^-+|-+$", "");
        if (rein.length() > 40) {
            rein = rein.substring(0, 40);
        }
        return "ausgaben-regeln" + (rein.isEmpty() ? "" : "-" + rein) + ".json";
    }

    /** Eine gewählte Regeldatei einlesen; erst die Rückfrage entscheidet, ob sie auch gilt. */
    private void readRulesFile(Uri uri) {
        try {
            String json = new String(de.spahr.ausgaben.util.UriBytes.read(this, uri),
                    java.nio.charset.StandardCharsets.UTF_8);
            offenerImport = de.spahr.ausgaben.statement.StatementRulesIo.parse(json);
            HostedDialog.show(this, DLG_IMPORT, new Bundle());
        } catch (de.spahr.ausgaben.statement.StatementRulesIo.StatementRulesFormatException e) {
            Toast.makeText(this, grund(e.problem), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.statement_rules_import_unreadable, Toast.LENGTH_LONG).show();
        }
    }

    /** Warum eine Datei nicht taugt — in einem Satz, den der Nutzer lesen kann. */
    private String grund(de.spahr.ausgaben.statement.StatementRulesIo.Problem problem) {
        switch (problem) {
            case NOT_OURS:
                return getString(R.string.statement_rules_import_not_ours);
            case TOO_NEW:
                return getString(R.string.statement_rules_import_too_new);
            case NO_TEMPLATES:
                return getString(R.string.statement_rules_import_empty);
            default:
                return getString(R.string.statement_rules_import_unreadable);
        }
    }

    /**
     * Die Rückfrage vor dem Einspielen: was in der Datei steht — und dass alles andere weicht.
     *
     * <p>Ersetzt wird der ganze Bestand dieses Depots und nicht je Art: Wer eine fremde Regeldatei
     * einspielt, will die Regeln <b>dieser</b> Bank, nicht eine Mischung aus seinen und fremden, die
     * hinterher niemand mehr auseinanderhält.</p>
     */
    private android.app.Dialog buildImportDialog() {
        final de.spahr.ausgaben.statement.StatementRulesIo.Parsed parsed = offenerImport;
        StringBuilder arten = new StringBuilder();
        for (StatementTemplate t : parsed.templates) {
            arten.append(arten.length() > 0 ? ", " : "").append(actionLabel(t.action));
        }
        String was = parsed.depot.trim().isEmpty() ? arten.toString()
                : getString(R.string.statement_rules_import_from, arten.toString(),
                parsed.depot.trim());
        return AppDialog.destructive(this)
                .setTitle(R.string.statement_rules_import_title)
                .setMessage(getString(R.string.statement_rules_import_message, was))
                .setPositiveButton(R.string.statement_rules_import, (d, w) -> {
                    store.saveAll(depot, parsed.templates);
                    offenerImport = null;
                    load();
                    Toast.makeText(this, getString(R.string.statement_rules_import_done,
                            parsed.templates.size()), Toast.LENGTH_LONG).show();
                    // Liegt eine Testabrechnung an, zeigt die Seite sofort, was die neuen Regeln
                    // darin lesen – genau die Frage, wegen der man sie eingespielt hat.
                    if (testText != null) {
                        switchToMatching();
                    }
                })
                .setNegativeButton(R.string.cancel, (d, w) -> offenerImport = null)
                .create();
    }

    // ---- Probe ----

    /**
     * Nimmt eine echte Abrechnung als Messlatte an: ab dann zeigt jeder Bereich, was seine Regel darin
     * liest, und die Beschriftungsfelder legen die gefundenen Werte zur Auswahl vor.
     */
    private void useTestStatement(Uri uri) {
        Toast.makeText(this, R.string.statement_reading, Toast.LENGTH_SHORT).show();
        repository.executor().execute(() -> {
            final PdfText text;
            try {
                text = PdfTextExtractor.read(this, uri);
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, R.string.statement_unreadable, Toast.LENGTH_LONG).show());
                return;
            }
            if (!text.hasText()) {
                runOnUiThread(() ->
                        Toast.makeText(this, R.string.statement_no_text, Toast.LENGTH_LONG).show());
                return;
            }
            runOnUiThread(() -> {
                testUri = uri;
                testText = text;
                testButtons.setVisibility(View.VISIBLE);
                textTestFile.setVisibility(View.VISIBLE);
                textTestFile.setText(getString(R.string.statement_rules_test_file, fileName(uri)));
                switchToMatching();
            });
        });
    }

    /**
     * Stellt die Vorlage ein, die zur Testabrechnung passt.
     *
     * <p>Ohne das müsste man selbst wissen, ob die Abrechnung ein Kauf, ein Verkauf oder eine
     * Ertragsgutschrift ist — und säße sonst still vor einer Vorlage, die nichts findet, weil sie gar
     * nicht gemeint war. Passt keine, bleibt die offene stehen: die Werte darunter sind trotzdem zu
     * gebrauchen, denn genau daran bessert man die Regeln nach.</p>
     */
    private void switchToMatching() {
        StatementTemplate passend = StatementTemplates.best(templates, testText);
        int index = passend == null ? -1 : templates.indexOf(passend);
        if (index < 0) {
            Toast.makeText(this, R.string.statement_rules_try_nomatch, Toast.LENGTH_LONG).show();
            updateAllFound();
            return;
        }
        if (index == current) {
            updateAllFound();
            return;
        }
        if (current >= 0 && !edited().sameAs(templates.get(current))) {
            AppDialog.destructive(this)
                    .setTitle(R.string.statement_rules_discard_title)
                    .setMessage(R.string.statement_rules_discard_message)
                    .setPositiveButton(R.string.statement_rules_discard_title, (d, w) -> show(index))
                    .setNegativeButton(R.string.cancel, (d, w) -> updateAllFound())
                    .show();
            return;
        }
        show(index);
    }

    /** Die Testabrechnung im PDF-Betrachter des Geräts – man liest daneben, was man hier einträgt. */
    private void showPdf() {
        if (testUri == null) {
            return;
        }
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW)
                    .setDataAndType(testUri, "application/pdf")
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, R.string.receipt_pdf_no_viewer, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.receipt_error, Toast.LENGTH_SHORT).show();
        }
    }

    /** Der Anzeigename der gewählten Datei; im Zweifel das letzte Stück des Uri. */
    /**
     * Der Anzeigename einer gewählten Datei.
     *
     * <p>Denselben Zweck erfüllte {@code StatementImport.displayName} mit einer zweiten, leicht
     * abweichenden Fassung; beide fragen jetzt über {@link DocumentName} und mit <b>ausdrücklicher
     * Spaltenauswahl</b>. Ein {@code query(uri, null, …)} lässt sich jeden Anbieter alle Spalten
     * zusammenstellen — bei einem Dokument in der Cloud kann das einen Netzzugriff bedeuten, für einen
     * einzigen Namen.</p>
     */
    private String fileName(Uri uri) {
        String name = DocumentName.of(this, uri);
        return name.isEmpty() ? uri.toString() : name;
    }

    /**
     * Der geparste Text der Abrechnung, Zeile für Zeile numeriert.
     *
     * <p>Die Hilfe beim Eintippen einer Regel: eine Beschriftung muss so dastehen, wie die App sie sieht,
     * und ob der Wert eine oder zwei Zeilen tiefer steht, ist hier abzuzählen. Ohne diese Ansicht rät
     * man — die Zeilen der App sind nicht die Zeilen, die man im PDF-Betrachter sieht: sie entstehen aus
     * den Wortpositionen, und eine optisch leere Zeile gibt es hier gar nicht.</p>
     */
    private void showText(PdfText text) {
        StringBuilder all = new StringBuilder();
        int i = 0;
        for (PdfText.Line line : text.lines()) {
            all.append(String.format(Locale.GERMANY, "%2d", i++))
                    .append("  ").append(line.text()).append('\n');
        }
        new AppDialog(this)
                .setTitle(R.string.statement_rules_show_text)
                .setMessage(all.toString().trim())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** Der gelesene Wert im Format des jeweiligen Feldes; {@code null}, wenn die Regel nichts findet. */
    private String valueOf(Field field, AnchorRule rule, PdfText text) {
        if (field == Field.DATE) {
            long millis = rule.readDate(text);
            return millis > 0 ? dateFormat.format(new Date(millis)) : null;
        }
        Double raw = rule.read(text);
        return raw == null ? null : formatValue(field, raw);
    }

    /** Eine gelesene Zahl so geschrieben, wie das Feld sie später zeigt. */
    private String formatValue(Field field, double raw) {
        if (field == Field.SHARES) {
            return MoneyFormat.shares(raw);
        }
        if (field == Field.PRICE) {
            return MoneyFormat.decimal(raw, 2, 4);
        }
        return MoneyFormat.plain(Math.round(raw * 100.0));
    }
}
