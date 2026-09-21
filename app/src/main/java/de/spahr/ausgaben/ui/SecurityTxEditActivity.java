package de.spahr.ausgaben.ui;

import android.app.DatePickerDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.Repository;
import de.spahr.ausgaben.db.SecurityTx;
import de.spahr.ausgaben.db.SecurityTxSplit;
import de.spahr.ausgaben.settings.AmountExpression;
import de.spahr.ausgaben.settings.MoneyFormat;
import de.spahr.ausgaben.settings.SettingsStore;
import de.spahr.ausgaben.settings.StatementTemplates;
import de.spahr.ausgaben.statement.StatementTemplate;
import de.spahr.ausgaben.statement.TemplateCheck;
import de.spahr.ausgaben.statement.TemplateLearner;
import de.spahr.ausgaben.util.CategorySplits;
import de.spahr.ausgaben.util.SecurityAmounts;
import de.spahr.ausgaben.util.SecurityAmounts.Field;
import de.spahr.ausgaben.settings.DateFormats;

/**
 * Erfassen und Ansehen einer Depot-Bewegung eines <b>bestehenden</b> Wertpapiers. Anlegbar sind nur Kauf,
 * Verkauf und Dividende; Ein-/Ausbuchungen und Wiederanlagen aus KMyMoney lassen sich hier nur ansehen.
 *
 * <p>Drei Modi: <b>Neu</b> (das Plus in der Bewegungsliste), <b>Ansehen</b> (Tipp auf eine importierte
 * Bewegung – dieser Modus hat die frühere Info-Popup abgelöst) und <b>Ändern</b> (Tipp auf eine selbst
 * erfasste, noch nicht exportierte Bewegung).</p>
 *
 * <p>Von den vier Zahlenfeldern ergänzt {@link SecurityAmounts} jeweils das fehlende. Die Maske merkt sich
 * dafür, welche Felder der Nutzer selbst gefüllt hat – nur die übrigen werden überschrieben.</p>
 */
public class SecurityTxEditActivity extends LocalizedActivity implements HostedDialog.Host {

    public static final String EXTRA_DEPOT = "depot";
    public static final String EXTRA_KMY_ID = "kmyId";
    public static final String EXTRA_NAME = "name";
    /** Id der anzusehenden/zu ändernden Bewegung; fehlt sie, wird eine neue angelegt. */
    public static final String EXTRA_TX_ID = "txId";

    // ---- Vorbelegung aus einer eingelesenen Bankabrechnung (siehe StatementImport) ----
    public static final String EXTRA_PREFILL_ACTION = "prefillAction";
    public static final String EXTRA_PREFILL_DATE = "prefillDate";
    public static final String EXTRA_PREFILL_SHARES = "prefillShares";
    public static final String EXTRA_PREFILL_PRICE = "prefillPrice";
    public static final String EXTRA_PREFILL_FEE = "prefillFee";
    public static final String EXTRA_PREFILL_NET = "prefillNet";
    public static final String EXTRA_PREFILL_GROSS = "prefillGross";
    public static final String EXTRA_PREFILL_ACCOUNT = "prefillAccount";
    /**
     * Die Kategoriezeilen als drei gleich lange Reihen (Kategorie, Betrag, Herkunftsbeschriftung);
     * der Name ist das Vorsilbe, an die {@link #putParts} die drei Endungen hängt.
     */
    public static final String EXTRA_PREFILL_FEE_PARTS = "prefillFeeParts";
    public static final String EXTRA_PREFILL_INCOME_PARTS = "prefillIncomeParts";
    /**
     * Kategorie einer festen Gebühr aus der Erkennungsregel. Sie steht dort von Hand und schlägt
     * deshalb die aus der letzten Bewegung erschlossene.
     */
    public static final String EXTRA_PREFILL_FIXED_FEE_CATEGORY = "prefillFixedFeeCategory";
    /**
     * Die Maske gehört zu einem Eintrag der Erkennungsliste ({@link StatementBatchActivity}): dort wird
     * nicht gespeichert, sondern berichtigt. Gebucht wird der ganze Stapel erst am Ende.
     */
    public static final String EXTRA_BATCH = "batch";
    /** Zurück an die Liste: Brutto, Steuer und Netto gehen nicht auf. */
    public static final String EXTRA_CONFLICT = "conflict";
    /**
     * Der Doppelungs-Hinweis, den die Erkennungsliste schon kennt (Textbaustein, 0 = keiner). Die
     * Doppelung <b>innerhalb der Auswahl</b> sieht nur die Liste – die Maske kennt immer nur einen Beleg.
     */
    public static final String EXTRA_DUPLICATE = "duplicate";
    /** Zurück an die Liste: diese Bewegung steht schon im Depot. */
    public static final String EXTRA_DUP_BOOKED = "dupBooked";
    /**
     * Zurück an die Liste: ein erkannter Schedule-Treffer wurde aktiv abgewählt (siehe
     * {@link #cbScheduleMatch}). Fehlt das Extra oder steht es auf {@code false}, bleibt die Zuordnung
     * beim endgültigen Speichern des Stapels aktiv – auch wenn dieser Entwurf nie geöffnet wurde.
     */
    public static final String EXTRA_SCHEDULE_MATCH_OPT_OUT = "scheduleMatchOptOut";
    /** Pfad zum zwischengespeicherten Abrechnungstext; daraus lernt die App beim Speichern die Anker. */
    public static final String EXTRA_STATEMENT_TEXT = "statementText";
    public static final String EXTRA_STATEMENT_ISIN = "statementIsin";
    /** Pfad der schon in die Belegablage kopierten Abrechnung; wird beim Speichern zum Beleg. */
    public static final String EXTRA_STATEMENT_FILE = "statementFile";

    private static final String BUY = SecurityTx.BUY;
    private static final String SELL = SecurityTx.SELL;
    private static final String DIVIDEND = SecurityTx.DIVIDEND;

    private final Calendar selectedDate = Calendar.getInstance();

    private Repository repository;
    private String depot;
    private String kmyId;
    private String securityName = "";
    private double taxRate;

    /** Die geladene Bewegung; {@code null} im Neu-Modus. */
    private SecurityTx loaded;
    private boolean readOnly;
    /** Berichtigen für die Erkennungsliste statt Speichern (siehe {@link #EXTRA_BATCH}). */
    private boolean batchMode;
    /** Die Maske kam aus einer eingelesenen Abrechnung — dann gilt: nicht gefunden heißt leer. */
    private boolean fromStatement;
    /**
     * Steht das Datum fest? {@code selectedDate} allein sagt das nicht: es trägt immer einen Wert, damit
     * der Kalender irgendwo aufschlägt. Ohne diese Unterscheidung würde ein nicht erkanntes Datum als das
     * heutige gebucht, ohne dass es jemand merkt.
     */
    private boolean dateKnown;
    /** Dasselbe für Kauf/Verkauf/Dividende: ohne erkannte Art ist kein Knopf vorgewählt. */
    private boolean actionKnown;

    /**
     * Das Zahlenfeld, in dem der Nutzer gerade steht — dort schreibt die Rechnung nicht hinein.
     *
     * <p>Ohne diese Sperre ließe sich eine vorbelegte Steuer nicht löschen: mit dem letzten gelöschten
     * Zeichen gilt das Feld als frei, die Rechnung setzt sofort wieder den Steuersatz hinein, und wer
     * eine 0 eintragen will, kommt nie dazu. Beim Verlassen des Feldes greift die Vorbelegung wieder —
     * dann ist es eine Hilfe und keine Bevormundung.</p>
     */
    private Field focusedField;

    /** Diese Bewegung steht schon im Depot — geht so an die Erkennungsliste zurück. */
    private boolean dupBooked;
    /**
     * Der Stand, für den zuletzt nach einer Doppelung gefragt wurde ({@code null} = noch nie). Solange
     * er sich nicht ändert, wird die Datenbank nicht erneut befragt — sonst liefe bei jedem Tastendruck
     * eine Abfrage.
     */
    private String lastDupKey;
    /** Der Hinweis, den die Erkennungsliste mitgab, und der Stand, für den er galt. */
    private int listHint;
    private String listHintKey;

    private MaterialToolbar toolbar;
    private MaterialButtonToggleGroup toggleAction;
    private TextView actionHeading;
    /** Hinweis unter den Umschaltknöpfen, wenn die Abrechnung die Art nicht hergab. */
    private TextView actionHint;
    /** Gelber Hinweis darunter: dieselbe Buchung scheint es schon zu geben. */
    private TextView duplicateWarning;
    private TextView textSecurity;
    private TextInputLayout dateLayout;
    private TextInputEditText editDate;
    private TextInputLayout grossLayout;
    private TextInputLayout feeLayout;
    private TextInputLayout netLayout;
    private TextInputLayout priceLayout;
    private TextInputLayout sharesLayout;
    private View sharesRow;
    private TextInputLayout accountLayout;
    private View feeSplitBox;
    private View incomeSplitBox;
    private TextView feeSplitHeading;
    private PickerTextView editAccount;
    private com.google.android.material.checkbox.MaterialCheckBox cbScheduleMatch;
    /** Der zuletzt live erkannte Schedule-Treffer; {@code null}, solange keiner ansteht. */
    private de.spahr.ausgaben.db.ScheduleMatch.Result currentScheduleMatch;
    /** Verwirft veraltete Antworten, wenn der Nutzer weitertippt, während eine Anfrage noch läuft. */
    private long scheduleMatchToken;
    private Runnable scheduleMatchPending;
    /**
     * Die Kategoriezeilen der beiden Rollen — bedient wie die Splitbuchung einer Geldbuchung, nur je
     * Rolle eine eigene Liste mit ihrem eigenen Gesamtbetrag darüber.
     *
     * <p>Vorher trug die Maske je Rolle ein einzelnes Kategoriefeld, und Kapitalertragsteuer,
     * Solidaritätszuschlag und Gebühren mussten zu einer Zahl addiert werden — die dann unter einer
     * dieser Kategorien stand und dort falsch war.</p>
     */
    private SplitRowController feeSplits;
    private SplitRowController incomeSplits;
    /** Was die Abrechnung an Teilbeträgen hergab — Beträge und, wenn gelernt, ihre Beschriftungen. */
    private final List<CategorySplits.Part> foundFeeParts = new ArrayList<>();
    private final List<CategorySplits.Part> foundIncomeParts = new ArrayList<>();
    /** Die Kategorien der letzten Buchung derselben Art; sie sagen, wohin die Beträge gehören. */
    private final List<CategorySplits.Part> knownFeeParts = new ArrayList<>();
    private final List<CategorySplits.Part> knownIncomeParts = new ArrayList<>();
    /** Kategorie einer festen Gebühr aus der Regel; sie schlägt die erschlossene (siehe Extra). */
    private String fixedFeeCategory = "";
    /** Kategorieliste nach Ausgabe/Einnahme gruppiert – dieselbe wie in der Buchungsmaske. */
    private CategoryFilterAdapter categoryAdapter;
    private MaterialButton btnSave;
    private MaterialButton btnDelete;
    /** Gelbe Statuszeile beim Nachladen der Abrechnung – dieselbe wie im Konto. */
    private ImportBanner receiptBanner;
    /** Notiz der Bewegung – nur zum Ansehen; geändert wird sie auf dem Verrechnungskonto. */
    private android.widget.TextView textNote;
    private LinearLayout rowReceipt;
    private android.widget.TextView textReceipt;
    private LinearLayout receiptPageIcons;
    private LinearLayout detailBox;
    private CalcKeyboardView calcKeyboard;

    private final Map<Field, TextInputEditText> numberFields = new EnumMap<>(Field.class);
    /** Felder, die der Nutzer selbst gefüllt hat – nur die übrigen darf die Rechnung überschreiben. */
    private final Set<Field> userSet = EnumSet.noneOf(Field.class);
    private Field lastComputed;
    private Field justEdited;
    /** Schützt vor Rückkopplung, während die Rechnung Felder beschreibt. */
    private boolean writingBack;
    /** Läuft gerade die Vorbelegung aus einer Abrechnung? Dann verdrängt kein Wert den anderen. */
    private boolean prefilling;
    private boolean conflict;
    /** Abrechnungstext der Sitzung; gesetzt, wenn die Maske aus einem eingelesenen PDF kam. */
    private String statementTextPath;
    private String statementIsin;
    /** Die noch nicht endgültig abgelegte Abrechnung; beim Speichern wird sie zum Beleg. */
    private java.io.File pendingStatement;
    /** Beleg-Tag einer bereits gespeicherten Abrechnung (aus der Notiz der Gegenbuchung). */
    private String savedStatementTag;
    /** Beschriftung des aus der Abrechnung gewählten Datums; sie wird zum Anker. */
    private String chosenDateLabel;
    /**
     * Die Regel hinter der Wahl. Zu einem Datum gibt es zwei Lesarten — die Beschriftung daneben und
     * die Spaltenüberschrift darüber —, und die Beschriftung allein sagt nicht, welche gemeint war.
     */
    private de.spahr.ausgaben.statement.AnchorRule chosenDateRule;
    /**
     * Dasselbe für die Wertfelder: die beim Verlassen des Feldes gewählte Beschriftung.
     *
     * <p>Gefragt wird nur beim <b>ersten</b> Beleg einer Bank — danach steht die Vorlage, und die
     * kennt die Antwort schon. Siehe {@link #ankerAuswahlAnbieten}.</p>
     */
    private final Map<Field, de.spahr.ausgaben.statement.AnchorRule> chosenValueRules =
            new EnumMap<>(Field.class);
    /**
     * Ob für diesen Beleg überhaupt zu fragen ist: {@code null} heißt „noch nicht nachgesehen".
     *
     * <p>Die Antwort kostet ein Einlesen des PDF und einen Durchgang durch die gespeicherten Vorlagen;
     * beim Verlassen jedes Feldes von vorn zu beginnen wäre Verschwendung. {@code volatile}, weil sie
     * im Hintergrund gefunden und auf dem Bedienfaden gelesen wird — wie {@link #statementText}.</p>
     */
    private volatile Boolean ankerAuswahlMoeglich;
    /** Siehe {@link #matchedTemplate(de.spahr.ausgaben.pdf.PdfText)}. */
    private volatile StatementTemplate matchedTemplate;
    private volatile boolean matchedTemplateComputed;
    /**
     * Wie {@link #ankerAuswahlMoeglich}, nur ohne die Vorlagen-Prüfung — siehe {@link #ankerSucheMoeglich}.
     */
    private volatile Boolean ankerSucheMoeglichCache;
    /**
     * Sucht die Beschriftung bereits während der Eingabe (siehe {@link #planeAnkerSuche}) — nicht bei
     * jedem Tastendruck, sondern erst, wenn eine kurze Weile nichts mehr getippt wurde: eine
     * Volltextsuche im PDF bei jedem einzelnen Zeichen wäre unnötige Arbeit, und während einer Ziffernfolge
     * ({@code 1}, {@code 10}, {@code 100}, …) will ohnehin nur der zuletzt eingetippte Wert eine Antwort.
     */
    private final android.os.Handler ankerHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final long ANKER_DEBOUNCE_MS = 300;
    private final Map<Field, Runnable> ankerPending = new EnumMap<>(Field.class);
    /** Dasselbe für die Teilbetrag-Zeilen einer Aufteilung — mit schwachen Schlüsseln wie unten. */
    private final Map<TextInputEditText, Runnable> splitAnkerPending = new java.util.WeakHashMap<>();
    /**
     * Der ursprüngliche Feldtitel (z. B. „Stückzahl"), bevor {@link #ankerAuswahlAnbieten} ihn durch die
     * im PDF gefundene Beschriftung ersetzt hat — für die Rückkehr nach „Selbst entscheiden" oder erneuter
     * Eingabe.
     */
    private final Map<Field, CharSequence> originalHints = new EnumMap<>(Field.class);
    /**
     * Ob die Einführung ins Lernen (siehe {@link #DLG_LEARN_INTRO}) schon einmal aufging — sonst käme
     * sie nach jeder Drehung noch einmal, obwohl der Nutzer sie längst weggetippt hat.
     */
    private boolean learnIntroShown;
    private static final String STATE_LEARN_INTRO_SHOWN = "s_learnIntroShown";
    /**
     * Die einmal gelesene Abrechnung. Ohne sie läse jeder Tipp aufs Datumsfeld das PDF neu ein.
     *
     * <p>{@code volatile}, weil geschrieben und gelesen wird das Feld auf zwei Fäden: das Einlesen läuft
     * im Hintergrund, die Maske greift danach vom Bedienfaden aus darauf zu. Ohne die Kennzeichnung darf
     * der zweite Faden den geschriebenen Wert schlicht nicht sehen und läse das PDF ein zweites Mal.</p>
     */
    private volatile de.spahr.ausgaben.pdf.PdfText statementText;
    /**
     * Die einmal ermittelten Datumskandidaten – {@link #datesOf}/{@code StatementScan.dates} sucht dafür
     * jede Fundstelle im ganzen Text ({@code AnchorRule.readDate}), das kostet bei einer mehrseitigen
     * Abrechnung spürbar Zeit. Ohne diesen Cache liefe genau diese Suche bei jedem Antippen des
     * Datumsfeldes neu – auch dann, wenn {@link #statementText} längst feststeht.
     */
    private volatile java.util.List<de.spahr.ausgaben.statement.StatementScan.DateCandidate> statementDates;
    /**
     * Die Felder, in die der Nutzer <b>selbst</b> geschrieben hat — nur aus ihnen wird gelernt.
     *
     * <p>Nicht zu verwechseln mit {@code userSet}: dort stehen auch die Werte, welche die Maske aus der
     * Abrechnung vorbelegt hat. Hier landet nur, was durch den Beobachter kam, und der schweigt bei jedem
     * programmatischen Schreiben ({@code writingBack}). Genau diese Unterscheidung ist der Punkt: die App
     * soll die Beschriftung zu einer Zahl suchen, die der Nutzer abgetippt hat — nicht zu einer, die sie
     * sich selbst vorgelegt hat.</p>
     */
    private final Set<Field> typedFields = EnumSet.noneOf(Field.class);
    /**
     * Felder, deren live gefundene Beschriftung einer schon in der Bank-Vorlage stehenden, ANDEREN Regel
     * widerspricht — dort lernt {@link #lernen} nur, wenn das Feld auch in {@link #ersetzteRegeln} steht.
     */
    private final Set<Field> konfliktFelder = EnumSet.noneOf(Field.class);
    /**
     * Felder, für die der Nutzer übers Stift-Symbol ausdrücklich eine Beschriftung bestätigt hat — nur
     * dann darf eine in {@link #konfliktFelder} stehende Korrektur die Bank-Vorlage wirklich ersetzen.
     */
    private final Set<Field> ersetzteRegeln = EnumSet.noneOf(Field.class);
    /**
     * Teilmenge von {@link #ersetzteRegeln}: Felder, bei denen der Schalter im Stift-Dialog auf
     * „hinzufügen" stand. Dort soll die neue Beschriftung die alte Regel nicht ablösen, sondern als
     * weitere Möglichkeit in deren Kette stehen — siehe {@link StatementTemplate#appendedTo}.
     */
    private final Set<Field> anhaengenFelder = EnumSet.noneOf(Field.class);
    /**
     * Felder, für die der Nutzer im Stift-Dialog „Nicht lernen" gewählt hat: der gefundene Wert gilt für
     * diese eine Buchung, die Bank-Vorlage bleibt unangetastet. Nötig neben {@link #ersetzteRegeln},
     * weil ohne Widerspruch ({@link #konfliktFelder}) sonst stillschweigend gelernt würde.
     */
    private final Set<Field> nichtLernenFelder = EnumSet.noneOf(Field.class);
    /**
     * Der Wert, für den der Nutzer im Stift-Dialog entschieden hat — je Feld, das dort war.
     *
     * <p>Die Entscheidung hängt am <b>Wert</b>, nicht an einem Merker, den irgendein Textereignis
     * wieder löscht. Genau daran scheiterte es zuvor: zwischen Wahl und Speichern lief die Suche noch
     * einmal (das Feld bekommt beim Schließen des Fensters wieder den Fokus, die Maske rechnet und
     * schreibt zurück) und nahm die Bestätigung wieder heraus — unsichtbar, und beim Speichern war
     * dann „nichts Neues" zu lernen. Ändert der Nutzer den Wert wirklich, stimmt der Vergleich nicht
     * mehr und die Entscheidung verfällt von selbst; siehe {@link #entscheidungGilt}.</p>
     */
    private final Map<Field, Double> entschiedenFuer = new EnumMap<>(Field.class);
    /** Hat der Nutzer das Datum selbst gewählt? Dann gehört auch dessen Beschriftung gelernt. */
    private boolean dateTyped;
    /** Das Speichern läuft schon — siehe {@link #save()}. */
    private boolean saving;

    /**
     * Schlüssel für {@link #onSaveInstanceState}.
     *
     * <p>Gesichert wird nur, was die Views selbst <b>nicht</b> tragen: Merker, Mengen und die
     * Datumsregel. Die Texte der Eingabefelder stellt Android über die View-Hierarchie wieder her, und
     * zwar nach {@code onCreate} — die Vorbelegung aus dem Intent überschreibt sie also nicht.</p>
     *
     * <p>Ohne diese Sicherung war die Maske nach einer Drehung nicht mehr zu bedienen: Das Datum stand
     * sichtbar im Feld, {@code dateKnown} war aber wieder {@code false}, und Speichern meldete „Datum
     * fehlt", ohne dass der Nutzer etwas dagegen tun konnte. Zugleich waren {@code typedFields} und
     * {@code dateTyped} leer, sodass {@code offerToLearn} wortlos abbrach — die Bank-Vorlage wurde nicht
     * gelernt, obwohl der Nutzer alles abgetippt hatte.</p>
     */
    private static final String STATE_DATE_MILLIS = "s_dateMillis";
    private static final String STATE_DATE_KNOWN = "s_dateKnown";
    private static final String STATE_ACTION_KNOWN = "s_actionKnown";
    private static final String STATE_DATE_TYPED = "s_dateTyped";
    private static final String STATE_DUP_BOOKED = "s_dupBooked";
    private static final String STATE_CONFLICT = "s_conflict";
    private static final String STATE_SAVING = "s_saving";
    private static final String STATE_USER_SET = "s_userSet";
    private static final String STATE_TYPED_FIELDS = "s_typedFields";
    private static final String STATE_KONFLIKT_FELDER = "s_konfliktFelder";
    private static final String STATE_ERSETZTE_REGELN = "s_ersetzteRegeln";
    private static final String STATE_ANHAENGEN_FELDER = "s_anhaengenFelder";
    private static final String STATE_NICHT_LERNEN_FELDER = "s_nichtLernenFelder";
    private static final String STATE_GEWAEHLTE_FELDER = "s_gewaehlteFelder";
    private static final String STATE_LAST_COMPUTED = "s_lastComputed";
    private static final String STATE_DATE_LABEL = "s_dateLabel";
    private static final String STATE_DATE_RULE = "s_dateRule";
    private static final String STATE_STATEMENT_TAG = "s_statementTag";
    private static final String STATE_FIXED_FEE_CATEGORY = "s_fixedFeeCategory";
    private static final String STATE_VALUE_RULES = "s_valueRules";

    /** Schlüssel der Dialoge dieser Maske – siehe {@link HostedDialog}. */
    private static final String DLG_DATE_CHOICE = "dlg_dateChoice";
    private static final String DLG_CALENDAR = "dlg_calendar";
    private static final String DLG_LEARN = "dlg_learn";
    private static final String DLG_VERIFY = "dlg_verify";
    private static final String DLG_ANCHOR_CHOICE = "dlg_anchorChoice";
    private static final String DLG_LEARN_INTRO = "dlg_learnIntro";
    private static final String ARG_DATE_LABELS = "a_labels";
    private static final String ARG_DATE_MILLIS = "a_millis";
    private static final String ARG_DATE_ANCHORS = "a_anchors";
    private static final String ARG_DATE_RULES = "a_rules";
    private static final String ARG_ANCHOR_FIELD = "a_anchorField";
    private static final String ARG_ANCHOR_LABELS = "a_anchorLabels";
    private static final String ARG_ANCHOR_RULES = "a_anchorRules";

    /**
     * Die beiden Rückfragen am Ende des Speicherns hängen an einem gelesenen PDF und an gelernten
     * Vorlagen. Beides gehört nicht in ein {@link Bundle} – es bleibt hier im Speicher, und was nach
     * einer Drehung fehlt, holt {@link #wiederaufnahme()} neu.
     */
    private LernAngebot lernAngebot;
    private PruefErgebnis pruefErgebnis;
    /** Die Angaben, mit denen sich die Lern-Rückfrage nach einer Drehung neu aufsetzen lässt. */
    private String learnAction;
    private Double learnShares;
    private Double learnPrice;
    private Long learnFeeCents;
    private Long learnNetCents;
    private Long learnGrossCents;
    private static final String STATE_LEARN_ACTION = "s_learnAction";
    private static final String STATE_LEARN_SHARES = "s_learnShares";
    private static final String STATE_LEARN_PRICE = "s_learnPrice";
    private static final String STATE_LEARN_FEE = "s_learnFee";
    private static final String STATE_LEARN_NET = "s_learnNet";
    private static final String STATE_LEARN_GROSS = "s_learnGross";
    private static final String STATE_LIST_HINT = "s_listHint";
    private static final String STATE_LIST_HINT_KEY = "s_listHintKey";
    private static final String STATE_LAST_DUP_KEY = "s_lastDupKey";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_security_tx);

        depot = orEmpty(getIntent().getStringExtra(EXTRA_DEPOT));
        kmyId = orEmpty(getIntent().getStringExtra(EXTRA_KMY_ID));
        securityName = orEmpty(getIntent().getStringExtra(EXTRA_NAME));
        repository = new Repository(this);
        taxRate = new SettingsStore(this).getDividendTaxPercent() / 100.0;
        batchMode = getIntent().getBooleanExtra(EXTRA_BATCH, false);

        setupToolbar();
        findViews();
        setupNumberFields();
        setupDateField();
        setupActionToggle();
        btnSave.setOnClickListener(v -> save());
        btnDelete.setOnClickListener(v -> confirmDelete());
        loadPickers();
        readStatementExtras();

        long txId = getIntent().getLongExtra(EXTRA_TX_ID, -1);
        if (txId >= 0) {
            repository.getSecurityTx(txId, this::bind);
        } else {
            setupNewMode();
        }
        // Zum Schluss: setupNewMode/applyPrefill setzen dieselben Merker aus dem Intent und würden den
        // wiederhergestellten Stand sonst wieder überschreiben. Im Bearbeiten-Modus kommt danach noch
        // bind() aus der Datenbank — das ist gewollt, dort stehen die verbindlichen Werte.
        restoreState(savedInstanceState);
    }

    private void setupToolbar() {
        toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> zurueck());
        // Der Systemzurück muss denselben Weg nehmen wie der Pfeil in der Leiste – sonst hängt es vom
        // Griff des Nutzers ab, ob seine Korrekturen in der Liste ankommen (siehe zurueck()).
        getOnBackPressedDispatcher().addCallback(this,
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        zurueck();
                    }
                });
    }

    /** Die Ansichten der Maske einsammeln – reines Zuordnen, keine Logik. */
    private void findViews() {
        toggleAction = findViewById(R.id.toggleAction);
        actionHeading = findViewById(R.id.actionHeading);
        actionHint = findViewById(R.id.actionHint);
        duplicateWarning = findViewById(R.id.duplicateWarning);
        textSecurity = findViewById(R.id.textSecurity);
        textSecurity.setText(securityName);
        dateLayout = findViewById(R.id.dateLayout);
        editDate = findViewById(R.id.editDate);
        grossLayout = findViewById(R.id.grossLayout);
        feeLayout = findViewById(R.id.feeLayout);
        netLayout = findViewById(R.id.netLayout);
        priceLayout = findViewById(R.id.priceLayout);
        accountLayout = findViewById(R.id.accountLayout);
        feeSplitBox = findViewById(R.id.feeSplitBox);
        incomeSplitBox = findViewById(R.id.incomeSplitBox);
        feeSplitHeading = findViewById(R.id.feeSplitHeading);
        editAccount = findViewById(R.id.editAccount);
        cbScheduleMatch = findViewById(R.id.cbScheduleMatch);
        editAccount.addTextChangedListener(new SimpleWatcher(this::planeScheduleMatchCheck));
        btnSave = findViewById(R.id.btnSave);
        btnDelete = findViewById(R.id.btnDelete);
        sharesRow = findViewById(R.id.sharesRow);
        ShimmerView receiptShimmer = findViewById(R.id.receiptShimmer);
        receiptShimmer.setColors(getColor(R.color.import_banner_bg),
                getColor(R.color.import_banner_shimmer));
        receiptBanner = new ImportBanner(findViewById(R.id.receiptBanner), receiptShimmer,
                findViewById(R.id.receiptStatus), null);
        textNote = findViewById(R.id.textNote);
        rowReceipt = findViewById(R.id.rowReceipt);
        textReceipt = findViewById(R.id.textReceipt);
        receiptPageIcons = findViewById(R.id.receiptPageIcons);
        detailBox = findViewById(R.id.detailBox);
        calcKeyboard = findViewById(R.id.calcKeyboard);
        // Im Hochformat haelt der Platzhalter die Hoehe der Tastatur frei, damit das Formular
        // wie bisher ueber ihr endet; quer schwebt sie darueber und der Platzhalter bleibt weg.
        calcKeyboard.reserveSpaceWith(findViewById(R.id.calcSpacer));
    }

    private void setupNumberFields() {
        numberFields.put(Field.SHARES, (TextInputEditText) findViewById(R.id.editShares));
        numberFields.put(Field.PRICE, (TextInputEditText) findViewById(R.id.editPrice));
        numberFields.put(Field.GROSS, (TextInputEditText) findViewById(R.id.editGross));
        numberFields.put(Field.FEE, (TextInputEditText) findViewById(R.id.editFee));
        numberFields.put(Field.NET, (TextInputEditText) findViewById(R.id.editNet));
        sharesLayout = findViewById(R.id.sharesLayout);
        // Wer den bemängelten Wert nachträgt, soll die Markierung sofort loswerden.
        markierungAufhebenBeimTippen(Field.GROSS, grossLayout);
        markierungAufhebenBeimTippen(Field.NET, netLayout);
        markierungAufhebenBeimTippen(Field.SHARES, sharesLayout);
        // Gesichert, bevor ankerAuswahlAnbieten() den Titel ggf. durch die erkannte Beschriftung ersetzt.
        for (Field f : new Field[]{Field.SHARES, Field.PRICE, Field.FEE, Field.NET, Field.GROSS}) {
            TextInputLayout layout = layoutFor(f);
            if (layout != null) {
                originalHints.put(f, layout.getHint());
            }
        }
    }

    private void setupDateField() {
        // Der Hinweis am Datumsfeld soll das Kalendersymbol nicht verdrängen – sonst verschwände mit
        // ihm der Weg, den Mangel zu beheben.
        dateLayout.setErrorIconDrawable(null);
        editDate.setOnClickListener(v -> showDatePicker());
        // Das Kalendersymbol liegt über dem Feld und würde den Tipper sonst schlucken.
        dateLayout.setEndIconOnClickListener(v -> showDatePicker());
    }

    private void setupActionToggle() {
        toggleAction.addOnButtonCheckedListener((group, id, checked) -> {
            if (checked) {
                actionKnown = true;
                actionHint.setVisibility(View.GONE);
                applyAction();
                loadCategoryFavorites();
                // Gegenkonto und Kategorien hängen an der Aktion: eine Dividende wird über eine
                // Ertragskategorie gebucht, ein Kauf über eine Gebührenkategorie. Der Listener greift
                // auch bei programmatischem Setzen – damit deckt er die aus dem PDF erkannte Aktion mit
                // ab. Bei einer geladenen Bewegung bleibt es bei ihren gespeicherten Werten.
                // In der Erkennungsliste bringt der Eintrag Konto und Kategorien schon mit – dort
                // stünde die Nachfrage gegen das, was der Nutzer eben erst berichtigt hat.
                if (loaded == null && !batchMode) {
                    loadDefaults(currentAction(), true);
                }
                recompute(null);
            }
        });
    }

    /** Was der Aufrufer über eine eingelesene Abrechnung mitgibt. */
    private void readStatementExtras() {
        statementTextPath = getIntent().getStringExtra(EXTRA_STATEMENT_TEXT);
        statementIsin = getIntent().getStringExtra(EXTRA_STATEMENT_ISIN);
        String staged = getIntent().getStringExtra(EXTRA_STATEMENT_FILE);
        if (staged != null) {
            pendingStatement = new java.io.File(staged);
        }
        fromStatement = statementTextPath != null || pendingStatement != null;
        updateStatementButton();
        // Vorlesen, solange der Nutzer noch Aktion/Konto prüft – ohne das läse showDatePicker()/
        // ankerAuswahlAnbieten() erst beim ersten Antippen, und genau das fror sichtbar ein.
        if (fromStatement) {
            repository.executor().execute(() -> {
                de.spahr.ausgaben.pdf.PdfText text = readStatementText();
                // Fehlt für diese Bank noch eine Vorlage, wartet auf den Nutzer echtes Lernen – bevor er
                // loslegt, kurz erklären, wie das abläuft (siehe zeigeLernEinfuehrungFallsNoetig). Das
                // steht bewusst VOR der Datumssuche unten: die kostet bei einer mehrseitigen Abrechnung
                // selbst noch einmal spürbar Zeit, und genau die soll nicht mehr zwischen dem Öffnen der
                // Maske und der Einführung liegen – man will ja oft schon lostippen.
                if (ohneVorlage(text)) {
                    post(this::zeigeLernEinfuehrungFallsNoetig);
                }
                readStatementDates();
            });
        }
    }

    /**
     * Erste Abrechnung dieser Bank: erklärt einmal (pro Aufruf der Maske), wie das Lernen abläuft –
     * Datum wählen, Zahlen zuordnen, der Rest bucht sich beim Speichern wie gewohnt. Ohne sie stünde der
     * Nutzer beim ersten Antippen des Datumsfeldes vor einer Auswahl, deren Sinn sich nicht von selbst
     * erschließt.
     */
    private void zeigeLernEinfuehrungFallsNoetig() {
        if (learnIntroShown || isFinishing() || isDestroyed()) {
            return;
        }
        learnIntroShown = true;
        HostedDialog.show(this, DLG_LEARN_INTRO, null);
    }

    private android.app.Dialog buildLearnIntroDialog() {
        return new AppDialog(this)
                .setTitle(R.string.statement_learn_intro_title)
                .setMessage(R.string.statement_learn_intro_message)
                .setPositiveButton(android.R.string.ok, null)
                .create();
    }

    /**
     * Nimmt zurück, was noch aussteht: Die verzögerten Läufe auf {@link #ankerHandler} hielten sonst
     * bis zu {@link #ANKER_DEBOUNCE_MS} lang eine Maske fest, die es nicht mehr gibt, und stießen danach
     * noch eine Datenbankabfrage an, deren Antwort niemanden mehr erreicht.
     */
    @Override
    protected void onDestroy() {
        ankerHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(@androidx.annotation.NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putLong(STATE_DATE_MILLIS, selectedDate.getTimeInMillis());
        out.putBoolean(STATE_DATE_KNOWN, dateKnown);
        out.putBoolean(STATE_ACTION_KNOWN, actionKnown);
        out.putBoolean(STATE_DATE_TYPED, dateTyped);
        out.putBoolean(STATE_DUP_BOOKED, dupBooked);
        out.putBoolean(STATE_CONFLICT, conflict);
        out.putBoolean(STATE_SAVING, saving);
        out.putBoolean(STATE_LEARN_INTRO_SHOWN, learnIntroShown);
        out.putStringArray(STATE_USER_SET, namesOf(userSet));
        out.putStringArray(STATE_TYPED_FIELDS, namesOf(typedFields));
        out.putStringArray(STATE_KONFLIKT_FELDER, namesOf(konfliktFelder));
        out.putStringArray(STATE_ERSETZTE_REGELN, namesOf(ersetzteRegeln));
        out.putStringArray(STATE_ANHAENGEN_FELDER, namesOf(anhaengenFelder));
        out.putStringArray(STATE_NICHT_LERNEN_FELDER, namesOf(nichtLernenFelder));
        java.util.HashMap<String, Double> entschieden = new java.util.HashMap<>();
        for (Map.Entry<Field, Double> e : entschiedenFuer.entrySet()) {
            entschieden.put(e.getKey().name(), e.getValue());
        }
        out.putSerializable(STATE_GEWAEHLTE_FELDER, entschieden);
        out.putString(STATE_LAST_COMPUTED, lastComputed == null ? null : lastComputed.name());
        out.putString(STATE_DATE_LABEL, chosenDateLabel);
        out.putSerializable(STATE_DATE_RULE, chosenDateRule);
        // Als HashMap mit den Feldnamen als Schlüssel: ein EnumMap ist zwar serialisierbar, aber die
        // Karte geht durch ein Bundle, und dort ist die schlichtere Form die haltbarere.
        java.util.HashMap<String, de.spahr.ausgaben.statement.AnchorRule> regeln =
                new java.util.HashMap<>();
        for (Map.Entry<Field, de.spahr.ausgaben.statement.AnchorRule> e : chosenValueRules.entrySet()) {
            regeln.put(e.getKey().name(), e.getValue());
        }
        out.putSerializable(STATE_VALUE_RULES, regeln);
        out.putString(STATE_STATEMENT_TAG, savedStatementTag);
        out.putString(STATE_FIXED_FEE_CATEGORY, fixedFeeCategory);
        out.putInt(STATE_LIST_HINT, listHint);
        out.putString(STATE_LIST_HINT_KEY, listHintKey);
        out.putString(STATE_LAST_DUP_KEY, lastDupKey);
        out.putString(STATE_LEARN_ACTION, learnAction);
        putBoxed(out, STATE_LEARN_SHARES, learnShares);
        putBoxed(out, STATE_LEARN_PRICE, learnPrice);
        putBoxed(out, STATE_LEARN_FEE, learnFeeCents);
        putBoxed(out, STATE_LEARN_NET, learnNetCents);
        putBoxed(out, STATE_LEARN_GROSS, learnGrossCents);
    }

    private void restoreState(Bundle in) {
        if (in == null) {
            return;
        }
        selectedDate.setTimeInMillis(in.getLong(STATE_DATE_MILLIS, selectedDate.getTimeInMillis()));
        dateKnown = in.getBoolean(STATE_DATE_KNOWN, dateKnown);
        actionKnown = in.getBoolean(STATE_ACTION_KNOWN, actionKnown);
        dateTyped = in.getBoolean(STATE_DATE_TYPED, dateTyped);
        dupBooked = in.getBoolean(STATE_DUP_BOOKED, dupBooked);
        conflict = in.getBoolean(STATE_CONFLICT, conflict);
        saving = in.getBoolean(STATE_SAVING, false);
        learnIntroShown = in.getBoolean(STATE_LEARN_INTRO_SHOWN, learnIntroShown);
        readFields(in.getStringArray(STATE_USER_SET), userSet);
        readFields(in.getStringArray(STATE_TYPED_FIELDS), typedFields);
        readFields(in.getStringArray(STATE_KONFLIKT_FELDER), konfliktFelder);
        readFields(in.getStringArray(STATE_ERSETZTE_REGELN), ersetzteRegeln);
        readFields(in.getStringArray(STATE_ANHAENGEN_FELDER), anhaengenFelder);
        readFields(in.getStringArray(STATE_NICHT_LERNEN_FELDER), nichtLernenFelder);
        entschiedenFuer.clear();
        Object entschieden = in.getSerializable(STATE_GEWAEHLTE_FELDER);
        if (entschieden instanceof java.util.Map) {
            for (Map.Entry<?, ?> e : ((java.util.Map<?, ?>) entschieden).entrySet()) {
                Field f = fieldOf(String.valueOf(e.getKey()));
                if (f != null && e.getValue() instanceof Double) {
                    entschiedenFuer.put(f, (Double) e.getValue());
                }
            }
        }
        lastComputed = fieldOf(in.getString(STATE_LAST_COMPUTED));
        chosenDateLabel = in.getString(STATE_DATE_LABEL);
        Object rule = in.getSerializable(STATE_DATE_RULE);
        chosenDateRule = rule instanceof de.spahr.ausgaben.statement.AnchorRule
                ? (de.spahr.ausgaben.statement.AnchorRule) rule : null;
        chosenValueRules.clear();
        Object regeln = in.getSerializable(STATE_VALUE_RULES);
        if (regeln instanceof java.util.Map) {
            for (Map.Entry<?, ?> e : ((java.util.Map<?, ?>) regeln).entrySet()) {
                Field f = fieldOf(String.valueOf(e.getKey()));
                if (f != null && e.getValue() instanceof de.spahr.ausgaben.statement.AnchorRule) {
                    chosenValueRules.put(f, (de.spahr.ausgaben.statement.AnchorRule) e.getValue());
                }
            }
        }
        savedStatementTag = in.getString(STATE_STATEMENT_TAG);
        fixedFeeCategory = orEmpty(in.getString(STATE_FIXED_FEE_CATEGORY));
        listHint = in.getInt(STATE_LIST_HINT, 0);
        listHintKey = in.getString(STATE_LIST_HINT_KEY);
        lastDupKey = in.getString(STATE_LAST_DUP_KEY);
        learnAction = in.getString(STATE_LEARN_ACTION);
        learnShares = in.containsKey(STATE_LEARN_SHARES) ? in.getDouble(STATE_LEARN_SHARES) : null;
        learnPrice = in.containsKey(STATE_LEARN_PRICE) ? in.getDouble(STATE_LEARN_PRICE) : null;
        learnFeeCents = in.containsKey(STATE_LEARN_FEE) ? in.getLong(STATE_LEARN_FEE) : null;
        learnNetCents = in.containsKey(STATE_LEARN_NET) ? in.getLong(STATE_LEARN_NET) : null;
        learnGrossCents = in.containsKey(STATE_LEARN_GROSS) ? in.getLong(STATE_LEARN_GROSS) : null;
        if (saving) {
            btnSave.setEnabled(false);
            wiederaufnahme();
        }
    }

    private static void putBoxed(Bundle out, String key, Double value) {
        if (value != null) {
            out.putDouble(key, value);
        }
    }

    private static void putBoxed(Bundle out, String key, Long value) {
        if (value != null) {
            out.putLong(key, value);
        }
    }

    /**
     * Nach einer Drehung mitten in der Lern-Rückfrage: die Bewegung ist gebucht, der Dialog ist neu zu
     * bauen — aber das gelesene PDF und die gelernten Vorlagen lagen nur im Speicher der alten Maske.
     *
     * <p>Also wird der Weg noch einmal gegangen: {@link #offerToLearn} liest die Abrechnung im
     * Hintergrund erneut und stellt die Rückfrage wieder hin. Ohne das bliebe die Maske als Sackgasse
     * stehen — ausgefüllt, mit gesperrtem Speichern-Knopf und ohne die Rückfrage, für die sie
     * überhaupt noch offen war; die Bank-Vorlage wäre verloren.</p>
     */
    private void wiederaufnahme() {
        if (learnAction == null) {
            return;
        }
        offerToLearn(learnAction, learnShares, learnPrice, learnFeeCents, learnNetCents,
                learnGrossCents);
    }

    private static String[] namesOf(Set<Field> fields) {
        String[] out = new String[fields.size()];
        int i = 0;
        for (Field f : fields) {
            out[i++] = f.name();
        }
        return out;
    }

    private static void readFields(String[] names, Set<Field> into) {
        if (names == null) {
            return;
        }
        into.clear();
        for (String name : names) {
            Field f = fieldOf(name);
            if (f != null) {
                into.add(f);
            }
        }
    }

    /** {@code null} statt einer Ausnahme: ein Bundle aus einer anderen Fassung darf nicht abstürzen. */
    private static Field fieldOf(String name) {
        if (name == null) {
            return null;
        }
        try {
            return Field.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Legt die beiden Kategorielisten an. Erst hier und nicht schon beim Aufbau der Maske: ob nur
     * angesehen oder bearbeitet wird, steht erst fest, wenn die Bewegung geladen ist.
     */
    private void buildSplitControllers() {
        feeSplits = new SplitRowController(findViewById(R.id.feeSplitContainer),
                numberFields.get(Field.FEE), getLayoutInflater(), readOnly, this::updateSaveEnabled);
        incomeSplits = new SplitRowController(findViewById(R.id.incomeSplitContainer),
                numberFields.get(Field.GROSS), getLayoutInflater(), readOnly, this::updateSaveEnabled);
        for (SplitRowController ctl : splitControllers()) {
            ctl.setAmountBinder(this::bindCalcSplitField);
            if (categoryAdapter != null) {
                ctl.setAdapter(categoryAdapter);
            }
            ctl.ensureTrailingRow();
        }
        // Ändert sich der Betrag darüber — getippt oder von der Rechnung der Maske eingesetzt —, zieht
        // die Rest-Zeile nach. Ohne das stünde nach einer berichtigten Steuer eine Aufteilung da, die
        // nicht mehr aufgeht, und der Speichern-Knopf bliebe grau, ohne dass man wüsste warum.
        watchTotal(Field.FEE, feeSplits);
        watchTotal(Field.GROSS, incomeSplits);
    }

    private void watchTotal(Field field, SplitRowController ctl) {
        numberFields.get(field).addTextChangedListener(new SimpleWatcher(() -> {
            ctl.onTotalChanged();
            updateSaveEnabled();
        }));
    }

    private java.util.List<SplitRowController> splitControllers() {
        java.util.List<SplitRowController> out = new java.util.ArrayList<>();
        if (feeSplits != null) {
            out.add(feeSplits);
        }
        if (incomeSplits != null) {
            out.add(incomeSplits);
        }
        return out;
    }

    /**
     * Hängt ein Teilbetragsfeld an die Rechentastatur — ohne die Rechnung der Maske: ein Teilbetrag
     * ist keine der Größen, aus denen sie Brutto, Gebühr und Netto auseinander ableitet. Zusätzlich, wie
     * bei den Hauptfeldern ({@link #wireCalcField}): schon während der Eingabe wird nachgesehen, unter
     * welcher Beschriftung dieser Teilbetrag in der Abrechnung steht (siehe
     * {@link #ankerAuswahlAnbietenSplit}).
     */
    private void bindCalcSplitField(TextInputLayout layout, TextInputEditText input) {
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
                // Läuft die Suche noch (Wartezeit nach dem letzten Tastendruck), soll sie hier nicht erst
                // abwarten, sondern sofort antworten.
                Runnable alt = splitAnkerPending.remove(input);
                if (alt != null) {
                    ankerHandler.removeCallbacks(alt);
                }
                ankerAuswahlAnbietenSplit(layout, input);
            }
        });
        // Sucht schon während der Eingabe, mit derselben Wartezeit wie bei den Hauptfeldern (siehe
        // planeAnkerSuche) — SplitRowControllers eigener TextWatcher (registriert vor diesem hier, siehe
        // addRow) setzt den „getippt"-Merker und den Rücksetzer auf den Standardtitel bereits synchron.
        input.addTextChangedListener(new SimpleWatcher(() -> {
            Runnable alt = splitAnkerPending.remove(input);
            if (alt != null) {
                ankerHandler.removeCallbacks(alt);
            }
            Runnable lauf = () -> ankerAuswahlAnbietenSplit(layout, input);
            splitAnkerPending.put(input, lauf);
            ankerHandler.postDelayed(lauf, ANKER_DEBOUNCE_MS);
        }));
    }

    /**
     * Der Speichern-Knopf hängt daran, dass beide Kategorielisten aufgehen — die Summe der Teile muss
     * den Betrag darüber ergeben, sonst stünde in KMyMoney eine Buchung, die sich nicht ausgleicht.
     */
    private void updateSaveEnabled() {
        // Läuft das Speichern bereits, bleibt der Knopf aus: die Kategoriezeilen melden sich beim
        // Schreiben noch einmal und würden ihn sonst wieder freigeben.
        btnSave.setEnabled(!saving
                && splitsOk(feeSplits, Field.FEE) && splitsOk(incomeSplits, Field.GROSS));
    }

    /**
     * Ohne Betrag darüber gibt es nichts aufzuteilen — bei einem Kauf ohne Gebühr bleibt die Liste
     * eben leer. Erst wenn ein Betrag dasteht, muss die Aufteilung ihn treffen.
     */
    private boolean splitsOk(SplitRowController ctl, Field total) {
        if (ctl == null) {
            return true;
        }
        Long value = money(total);
        return value == null || value == 0 || ctl.isValid();
    }

    // ---- Modi ----

    private void setupNewMode() {
        toolbar.setTitle(batchMode ? R.string.security_tx_edit_title : R.string.security_tx_new_title);
        if (batchMode) {
            // Hier wird nichts gebucht: der Eintrag geht berichtigt an die Liste zurück, und erst dort
            // entscheidet „Alle speichern" über den ganzen Stapel.
            btnSave.setText(R.string.statement_batch_apply);
        }
        // Ohne Abrechnung ist der Kauf die richtige Annahme, und heute das richtige Datum – dort gibt es
        // keine Quelle, der man widersprechen könnte. Kam die Maske dagegen aus einem Dokument, wird
        // nichts vorgewählt, was nicht darin stand; ergänzt wird gleich darauf aus der Auslese.
        selectedDate.setTime(new Date());
        if (fromStatement) {
            clearDateField();
        } else {
            toggleAction.check(R.id.btnBuy);
            updateDateField();
        }
        applyAction();
        wireNumberFields();
        buildSplitControllers();
        applyPrefill();
    }

    /**
     * Übernimmt, was aus einer eingelesenen Abrechnung erkannt wurde. Die Werte gelten wie selbst
     * eingetippt — die Rechnung darf sie nicht überschreiben, sie stehen ja so im Dokument. Was nicht
     * erkannt wurde, bleibt leer; <b>geraten wird nichts</b>.
     */
    private void applyPrefill() {
        android.content.Intent in = getIntent();
        String action = in.getStringExtra(EXTRA_PREFILL_ACTION);
        Integer button = action == null ? null : buttonFor(action);
        if (button != null) {
            toggleAction.check(button);
        }
        long date = in.getLongExtra(EXTRA_PREFILL_DATE, -1);
        if (date > 0) {
            selectedDate.setTimeInMillis(date);
            updateDateField();
        }
        if (!DIVIDEND.equals(currentAction())) {
            prefillNumber(Field.SHARES, in.hasExtra(EXTRA_PREFILL_SHARES)
                    ? in.getDoubleExtra(EXTRA_PREFILL_SHARES, 0) : null);
            prefillNumber(Field.PRICE, in.hasExtra(EXTRA_PREFILL_PRICE)
                    ? in.getDoubleExtra(EXTRA_PREFILL_PRICE, 0) : null);
        }
        // Auch bei Kauf und Verkauf, wo das Feld verborgen ist: es rechnet dort mit, und eine von Hand
        // angelegte Brutto-Regel liest gerade dort den umgerechneten Betrag eines Dollar-Papiers.
        prefillMoney(Field.GROSS, in.hasExtra(EXTRA_PREFILL_GROSS)
                ? in.getLongExtra(EXTRA_PREFILL_GROSS, 0) : null);
        prefillMoney(Field.FEE, in.hasExtra(EXTRA_PREFILL_FEE)
                ? in.getLongExtra(EXTRA_PREFILL_FEE, 0) : null);
        prefillMoney(Field.NET, in.hasExtra(EXTRA_PREFILL_NET)
                ? in.getLongExtra(EXTRA_PREFILL_NET, 0) : null);
        // Nichts vorgewählt und nichts erkannt: dann fehlt die Art, und das gehört gesagt.
        actionHint.setVisibility(actionKnown ? View.GONE : View.VISIBLE);
        // Was die Liste schon weiß, sagt die Maske sofort mit – die eigene Prüfung braucht eine Runde
        // über die Datenbank, und so lange stünde hier sonst nichts.
        listHint = in.getIntExtra(EXTRA_DUPLICATE, 0);
        dupBooked = listHint == R.string.statement_dup_booked;
        prefillPicker(editAccount, in.getStringExtra(EXTRA_PREFILL_ACCOUNT));
        fixedFeeCategory = orEmptyText(in.getStringExtra(EXTRA_PREFILL_FIXED_FEE_CATEGORY));
        foundFeeParts.clear();
        foundFeeParts.addAll(readParts(in, EXTRA_PREFILL_FEE_PARTS));
        foundIncomeParts.clear();
        foundIncomeParts.addAll(readParts(in, EXTRA_PREFILL_INCOME_PARTS));
        // Aus der Erkennungsliste kommen die Kategorien schon zugeordnet zurück; dann sind die Teile
        // selbst die Vorbelegung und es gibt nichts mehr zuzuordnen.
        if (batchMode) {
            knownFeeParts.addAll(foundFeeParts);
            knownIncomeParts.addAll(foundIncomeParts);
        }
        // Die Werte stammen aus dem Dokument und stehen fest – der Stückpreis der Bank ist genauer als
        // einer, den die Maske aus Summe und Stückzahl zurückrechnet.
        prefilling = true;
        recompute(null);
        prefilling = false;
        applySplitRows();
    }

    /**
     * Schreibt die Kategoriezeilen als drei gleich lange Reihen in einen Intent — Kategorie, Betrag
     * und Herkunftsbeschriftung. Drei einfache Reihen statt eines eigenen Parcelable: die Maske und
     * die Erkennungsliste reichen sie nur durch.
     */
    static void putParts(android.content.Intent out, String key, List<CategorySplits.Part> parts) {
        String[] categories = new String[parts.size()];
        long[] cents = new long[parts.size()];
        String[] labels = new String[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            categories[i] = parts.get(i).category;
            cents[i] = parts.get(i).cents;
            labels[i] = parts.get(i).label;
        }
        out.putExtra(key + "Cat", categories);
        out.putExtra(key + "Cents", cents);
        out.putExtra(key + "Label", labels);
    }

    /** Die Gegenrichtung zu {@link #putParts}; leere Liste, wenn nichts mitgegeben wurde. */
    static List<CategorySplits.Part> readParts(android.content.Intent in, String key) {
        List<CategorySplits.Part> out = new ArrayList<>();
        String[] categories = in.getStringArrayExtra(key + "Cat");
        long[] cents = in.getLongArrayExtra(key + "Cents");
        String[] labels = in.getStringArrayExtra(key + "Label");
        if (categories == null || cents == null || labels == null
                || categories.length != cents.length || labels.length != cents.length) {
            return out;
        }
        for (int i = 0; i < cents.length; i++) {
            out.add(new CategorySplits.Part(categories[i], cents[i], labels[i]));
        }
        return out;
    }

    /**
     * Legt die Kategoriezeilen neu aus: die Beträge der Abrechnung treffen auf die Kategorien der
     * letzten Buchung (siehe {@link CategorySplits}).
     */
    private void applySplitRows() {
        if (feeSplits == null) {
            return;
        }
        boolean dividend = DIVIDEND.equals(currentAction());
        List<CategorySplits.Part> gebuehr =
                CategorySplits.rows(foundFeeParts, orZero(money(Field.FEE)), knownFeeParts);
        if (!fixedFeeCategory.isEmpty() && !gebuehr.isEmpty()) {
            CategorySplits.Part erste = gebuehr.get(0);
            gebuehr.set(0, new CategorySplits.Part(fixedFeeCategory, erste.cents, erste.label));
        }
        fillMatched(feeSplits, gebuehr);
        fillMatched(incomeSplits, dividend
                ? CategorySplits.rows(foundIncomeParts, orZero(money(Field.GROSS)), knownIncomeParts)
                : new ArrayList<>());
        updateSaveEnabled();
    }

    private static long orZero(Long value) {
        return value == null ? 0 : Math.abs(value);
    }

    private static String orEmptyText(String value) {
        return value == null ? "" : value.trim();
    }

    private void fillMatched(SplitRowController ctl, List<CategorySplits.Part> parts) {
        ctl.setSuppressEvents(true);
        ctl.clear();
        for (CategorySplits.Part part : parts) {
            // Ohne Betrag bleibt das Feld leer statt „0,00": die Zeile wartet auf ihren Betrag, und
            // eine geschriebene Null sähe aus wie eine Angabe.
            ctl.addRow(part.category, part.cents == 0 ? null : MoneyFormat.plain(part.cents),
                    null, part.label);
        }
        ctl.ensureTrailingRow();
        ctl.setSuppressEvents(false);
    }

    /**
     * Setzt ein Feld aus der Abrechnung. Der Beobachter bleibt dabei stumm: liefe er mit, rechnete die
     * Maske nach jedem einzelnen Feld neu, und beim letzten wäre die Stück-Gruppe überbestimmt — dann
     * gäbe der Stückpreis nach und würde durch einen zurückgerechneten ersetzt (164,0401 statt der 164,04
     * aus dem Dokument). Gerechnet wird deshalb erst am Ende, in einem Durchgang.
     */
    private void prefillNumber(Field field, Double value) {
        if (value != null) {
            writingBack = true;
            numberFields.get(field).setText(field == Field.SHARES
                    ? MoneyFormat.shares(value) : MoneyFormat.decimal(value, 0, 4));
            writingBack = false;
            userSet.add(field);
        }
    }

    /** Konto oder Kategorie aus dem Eintrag der Erkennungsliste; leer bleibt leer. */
    private void prefillPicker(PickerTextView field, String value) {
        if (value != null && !value.trim().isEmpty()) {
            field.setText(value, false);
        }
    }

    private void prefillMoney(Field field, Long cents) {
        if (cents != null) {
            writingBack = true;
            numberFields.get(field).setText(MoneyFormat.plain(cents));
            writingBack = false;
            userSet.add(field);
        }
    }

    private void bind(SecurityTx tx) {
        if (tx == null) {
            finish();
            return;
        }
        loaded = tx;
        // Nur was noch nicht in der Datei steht, lässt sich hier ändern; alles andere gehört KMyMoney.
        readOnly = !tx.pending;
        selectedDate.setTimeInMillis(tx.date);
        updateDateField();

        Integer button = buttonFor(tx.action);
        if (button != null) {
            toggleAction.check(button);
        }
        // Ein-/Ausbuchung oder Wiederanlage: dafür gibt es keinen Umschalter – nur die Überschrift.
        toggleAction.setVisibility(button != null && !readOnly ? View.VISIBLE : View.GONE);
        if (button == null || readOnly) {
            actionHeading.setVisibility(View.VISIBLE);
            actionHeading.setText(actionLabel(tx.action));
            actionHeading.setTextColor(amountColor(tx.action));
        }
        applyAction();

        writingBack = true;
        boolean dividend = DIVIDEND.equals(tx.action);
        // Bei einer Dividende bleiben Anzahl und je Stück leer: die Stückzahl am Ex-Tag ist nicht bekannt.
        double count = dividend ? 0 : Math.abs(tx.shares);
        if (count > 0) {
            setNumber(Field.SHARES, count);
            setNumber(Field.PRICE, tx.amountCents / 100.0 / count);
        }
        setMoney(Field.GROSS, tx.amountCents);
        setMoney(Field.FEE, dividend ? tx.amountCents - tx.netCents : tx.feeCents);
        setMoney(Field.NET, dividend ? tx.netCents : totalOf(tx));
        writingBack = false;

        editAccount.setText(tx.moneyAccount, false);
        buildSplitControllers();
        fillSplitRows(feeSplits, tx.partsOf(false));
        fillSplitRows(incomeSplits, tx.partsOf(true));
        loadSavedStatement(tx.bookingId);

        if (readOnly) {
            applyReadOnly();
        } else {
            toolbar.setTitle(R.string.security_tx_edit_title);
            btnDelete.setVisibility(View.VISIBLE);
            // Alles Geladene gilt als gesetzt – sonst würde die erste Rechnung es überschreiben.
            userSet.addAll(numberFields.keySet());
            wireNumberFields();
        }
        updateSaveEnabled();
    }

    /** Die gespeicherten Kategoriezeilen in die Liste schreiben, ohne dabei etwas nachzurechnen. */
    private void fillSplitRows(SplitRowController ctl, List<SecurityTxSplit> parts) {
        ctl.setSuppressEvents(true);
        ctl.clear();
        for (SecurityTxSplit part : parts) {
            ctl.addRow(part.category, MoneyFormat.plain(part.amountCents), null, part.label);
        }
        ctl.ensureTrailingRow();
        ctl.setSuppressEvents(false);
    }

    /** Belastung bzw. Gutschrift einer Kauf-/Verkaufsbuchung (Betrag plus/minus Gebühr). */
    private static long totalOf(SecurityTx tx) {
        return SELL.equals(tx.action) ? tx.amountCents - tx.feeCents : tx.amountCents + tx.feeCents;
    }

    /** Reine Ansicht: alle Felder gesperrt, keine Knöpfe, leere Felder fallen weg. */
    private void applyReadOnly() {
        toolbar.setTitle(actionLabel(loaded.action));
        btnSave.setVisibility(View.GONE);
        btnDelete.setVisibility(View.GONE);
        for (TextInputEditText f : numberFields.values()) {
            lockField(f);
        }
        lockField(editDate);
        dateLayout.setEndIconMode(TextInputLayout.END_ICON_NONE);
        lockDropdown(editAccount, accountLayout);
        hideIfEmpty(accountLayout, editAccount);
        hideIfEmpty(feeLayout, numberFields.get(Field.FEE));
        // Ohne Kategoriezeilen bliebe nur eine Überschrift über einer leeren Fläche stehen.
        if (loaded.partsOf(false).isEmpty()) {
            feeSplitBox.setVisibility(View.GONE);
        }
        if (loaded.partsOf(true).isEmpty()) {
            incomeSplitBox.setVisibility(View.GONE);
        }
        // Ein-/Ausbuchungen tragen in KMyMoney keinen Geldwert – dort bliebe nur die Stückzahl übrig.
        if (loaded.amountCents == 0) {
            grossLayout.setVisibility(View.GONE);
            netLayout.setVisibility(View.GONE);
            priceLayout.setVisibility(View.GONE);
        }
        if (loaded.pending) {
            detailRow(getString(R.string.security_tx_pending), "");
        }
    }

    private void lockField(TextInputEditText field) {
        field.setFocusable(false);
        field.setClickable(false);
        field.setKeyListener(null);
        field.setOnClickListener(null);
    }

    private void lockDropdown(PickerTextView field, TextInputLayout layout) {
        field.setFocusable(false);
        field.setOnClickListener(null);
        field.setAdapter(null);
        // Der Pfeil verspräche eine Auswahl, die es hier nicht gibt.
        layout.setEndIconMode(TextInputLayout.END_ICON_NONE);
    }

    private void hideIfEmpty(TextInputLayout layout, TextInputEditText field) {
        if (textOf(field).trim().isEmpty()) {
            layout.setVisibility(View.GONE);
        }
    }

    private void hideIfEmpty(TextInputLayout layout, PickerTextView field) {
        if (field.getText() == null || field.getText().toString().trim().isEmpty()) {
            layout.setVisibility(View.GONE);
        }
    }

    private void detailRow(String label, String value) {
        detailBox.setVisibility(View.VISIBLE);
        TextView t = new TextView(this);
        t.setText(value.isEmpty() ? label : label + ": " + value);
        t.setTextSize(14f);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
        t.setTextColor(getColor(R.color.grey_text));
        detailBox.addView(t);
    }

    // ---- Felder je Aktion ----

    /** Beschriftungen und Sichtbarkeit an die gewählte Aktion anpassen. */
    private void applyAction() {
        boolean dividend = DIVIDEND.equals(currentAction());
        priceLayout.setHint(getString(dividend
                ? R.string.security_tx_price_dividend : R.string.security_tx_price));
        // Die Namen der beiden Geldfelder hängen an der Art und stehen deshalb nur an einer Stelle
        // (siehe StatementFieldNames): die Regelseite muss dieselben Wörter benutzen wie die Maske.
        feeLayout.setHint(StatementFieldNames.of(this, StatementTemplate.Field.FEE, currentAction()));
        netLayout.setHint(StatementFieldNames.of(this, StatementTemplate.Field.NET, currentAction()));
        feeSplitHeading.setText(StatementFieldNames.feeCategoryHeading(this, currentAction()));
        // Der Bruttobetrag ist nur bei einer Dividende ein eigenes Feld – bei Kauf/Verkauf steckt er
        // zwischen Anzahl × Stückpreis und der Gesamtsumme und wäre eine dritte Zahl für dieselbe Sache.
        grossLayout.setVisibility(dividend ? View.VISIBLE : View.GONE);
        incomeSplitBox.setVisibility(dividend ? View.VISIBLE : View.GONE);
        // Umgekehrt bei Anzahl und Dividende je Stück: die Stückzahl, auf die eine Ausschüttung entfällt,
        // ist der Bestand am Ex-Tag – und der steht in der Abrechnung nicht. Beides wäre hier geraten,
        // deshalb gibt es die Felder bei einer Dividende gar nicht erst.
        sharesRow.setVisibility(dividend ? View.GONE : View.VISIBLE);
        moveTotalField(dividend);
        if (dividend) {
            userSet.remove(Field.SHARES);
            userSet.remove(Field.PRICE);
            clearField(Field.SHARES);
            clearField(Field.PRICE);
        } else {
            userSet.remove(Field.GROSS);
        }
    }

    /**
     * Rückt die Gesamtsumme an den Platz, der zur Aktion passt.
     *
     * <p>Bei Kauf und Verkauf ist sie die Zahl, die man vom Beleg abliest – sie steht deshalb gleich
     * unter dem Datum, Anzahl und Stückpreis folgen darunter. Bei einer Dividende ist das Netto dagegen
     * das Ende einer Kette (Brutto minus Steuer) und bleibt an deren Ende stehen.</p>
     */
    private void moveTotalField(boolean dividend) {
        android.view.ViewGroup form = (android.view.ViewGroup) netLayout.getParent();
        if (form == null) {
            return;
        }
        View predecessor = dividend ? feeLayout : dateLayout;
        if (form.indexOfChild(netLayout) == form.indexOfChild(predecessor) + 1) {
            return;   // steht schon dort
        }
        // Erst aushängen, dann den Platz bestimmen: alles hinter dem alten Platz rückt sonst um eins vor,
        // und ein vorher berechneter Index läge eine Zeile zu tief.
        form.removeView(netLayout);
        form.addView(netLayout, form.indexOfChild(predecessor) + 1);
    }

    private void clearField(Field field) {
        writingBack = true;
        numberFields.get(field).setText("");
        writingBack = false;
    }

    private String currentAction() {
        if (loaded != null && (readOnly || buttonFor(loaded.action) == null)) {
            return loaded.action;
        }
        int id = toggleAction.getCheckedButtonId();
        if (id == R.id.btnSell) {
            return SELL;
        }
        return id == R.id.btnDividend ? DIVIDEND : BUY;
    }

    private static Integer buttonFor(String action) {
        if (BUY.equals(action)) {
            return R.id.btnBuy;
        }
        if (SELL.equals(action)) {
            return R.id.btnSell;
        }
        return DIVIDEND.equals(action) ? R.id.btnDividend : null;
    }

    // ---- Rechnen ----

    private void wireNumberFields() {
        for (Map.Entry<Field, TextInputEditText> e : numberFields.entrySet()) {
            wireCalcField(e.getKey(), e.getValue());
        }
    }

    /**
     * Bindet ein Zahlenfeld an die gemeinsame Rechentastatur und meldet jede Änderung an die Rechnung.
     * Ein Feld, in das der Nutzer schreibt, gilt fortan als von ihm gesetzt und wird nicht überschrieben;
     * leert er es wieder, gibt er es für die Rechnung frei.
     */
    private void wireCalcField(final Field field, final TextInputEditText input) {
        AmountField.prepareCalc(input);
        input.setShowSoftInputOnFocus(false);
        input.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                focusedField = field;
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
                if (focusedField == field) {
                    focusedField = null;
                    // Jetzt erst: wer das Feld leer verlässt, bekommt die Vorbelegung zurück; wer eine
                    // 0 hineingeschrieben hat, behält sie.
                    recompute(null);
                    // Die Suche läuft längst während der Eingabe (siehe unten); beim Verlassen soll sie
                    // aber nicht erst nach der Wartezeit antworten, deshalb hier sofort statt geplant.
                    ankerSucheSofort(field);
                }
            }
        });
        input.addTextChangedListener(new SimpleWatcher(() -> {
            if (writingBack) {
                return;
            }
            if (textOf(input).trim().isEmpty()) {
                userSet.remove(field);
                typedFields.remove(field);
            } else {
                userSet.add(field);
                // Hierher kommt nur, was der Nutzer wirklich getippt hat – programmatisches Schreiben
                // hat oben schon abgedreht.
                typedFields.add(field);
            }
            // Der Wert hat sich geändert: eine schon gezeigte Regel galt dem alten Wert und ist jetzt
            // hinfällig – Titel und Symbol verschwinden, bis die Suche (unten) einen neuen Treffer meldet.
            TextInputLayout layout = layoutFor(field);
            if (layout != null && layout.getEndIconMode() == TextInputLayout.END_ICON_CUSTOM) {
                layout.setEndIconMode(TextInputLayout.END_ICON_NONE);
                layout.setHint(originalHints.get(field));
                chosenValueRules.remove(field);
                konfliktFelder.remove(field);
            }
            // Die im Stift-Fenster getroffene Entscheidung wird hier bewusst NICHT angerührt: dieser
            // Beobachter meldet sich auch, wenn sich der Wert gar nicht wirklich geändert hat, und
            // löschte damit eine eben getroffene Wahl. Sie verfällt stattdessen von selbst, sobald der
            // Wert nicht mehr der ist, für den sie galt – siehe entscheidungGilt.
            planeAnkerSuche(field);
            recompute(field);
        }));
    }

    /** Verwirft eine noch anstehende Suche und stößt sie mit Wartezeit neu an (siehe {@link #ankerHandler}). */
    private void planeAnkerSuche(final Field field) {
        Runnable alt = ankerPending.remove(field);
        if (alt != null) {
            ankerHandler.removeCallbacks(alt);
        }
        Runnable lauf = () -> ankerAuswahlAnbieten(field);
        ankerPending.put(field, lauf);
        ankerHandler.postDelayed(lauf, ANKER_DEBOUNCE_MS);
    }

    /** Wie {@link #planeAnkerSuche}, nur ohne Wartezeit — für den Moment, in dem das Feld verlassen wird. */
    private void ankerSucheSofort(final Field field) {
        Runnable alt = ankerPending.remove(field);
        if (alt != null) {
            ankerHandler.removeCallbacks(alt);
        }
        ankerAuswahlAnbieten(field);
    }

    /** Ergänzt die fehlenden Zahlen und schreibt sie in die Felder, die der Nutzer nicht selbst gefüllt hat. */
    private void recompute(Field edited) {
        if (readOnly) {
            return;
        }
        justEdited = edited;
        SecurityAmounts.Input in = new SecurityAmounts.Input();
        in.action = currentAction();
        // Der Steuersatz ist eine Hilfe beim Eintippen von Hand. Für eine eingelesene Abrechnung ist er
        // die falsche Quelle: dort hat die Regel gesucht, und was sie nicht fand, wurde nicht abgezogen.
        // Sonst zeigt eine Dividende innerhalb des Freibetrags eine gerechnete Steuer, die nirgends steht.
        in.taxRate = fromStatement ? 0 : taxRate;
        in.lastComputed = lastComputed;
        in.justEdited = justEdited;
        in.keepGiven = prefilling;
        in.shares = userSet.contains(Field.SHARES) ? number(Field.SHARES) : null;
        in.price = userSet.contains(Field.PRICE) ? number(Field.PRICE) : null;
        in.grossCents = userSet.contains(Field.GROSS) ? money(Field.GROSS) : null;
        in.feeCents = userSet.contains(Field.FEE) ? money(Field.FEE) : null;
        in.netCents = userSet.contains(Field.NET) ? money(Field.NET) : null;

        SecurityAmounts.Result r = SecurityAmounts.solve(in);
        conflict = r.conflict;
        netLayout.setError(conflict ? getString(R.string.security_tx_conflict) : null);
        if (conflict) {
            // Auch hier prüfen: an einem widersprüchlichen Stand ist keine Doppelung zu erkennen, und
            // ein noch stehender Hinweis von vorhin verschwindet damit.
            checkDuplicate();
            planeScheduleMatchCheck();
            return;
        }
        if (r.computed != null) {
            lastComputed = r.computed;
            // Das nachgebende Feld ist keine Nutzereingabe mehr, sonst bliebe es für immer stehen.
            userSet.remove(r.computed);
        }
        writingBack = true;
        writeUnset(Field.SHARES, r.shares == null ? null : MoneyFormat.shares(r.shares));
        writeUnset(Field.PRICE, r.price == null ? null : MoneyFormat.decimal(r.price, 0, 4));
        writeUnset(Field.GROSS, r.grossCents == null ? null : MoneyFormat.plain(r.grossCents));
        // Die stillschweigende 0 bei Kauf/Verkauf bleibt ungeschrieben: stünde „0,00" im Feld, verdeckte
        // sie die Beschriftung, und wer dann hineintippt, schreibt vor oder hinter die Null statt sie zu
        // ersetzen. Bei einer Dividende ist die berechnete Steuer dagegen eine echte Auskunft.
        if (DIVIDEND.equals(in.action) || userSet.contains(Field.FEE)) {
            writeUnset(Field.FEE, r.feeCents == null ? null : MoneyFormat.plain(r.feeCents));
        }
        writeUnset(Field.NET, r.netCents == null ? null : MoneyFormat.plain(r.netCents));
        writingBack = false;
        // Für das gerechnete Feld sucht sein eigener Beobachter nicht: der schweigt bei allem, was die
        // Maske selbst schreibt (writingBack). Die Suche gehört hier angestoßen — sonst bliebe bei einer
        // Dividende gerade die Zahl ohne Beschriftung, die aus den beiden getippten herausfällt.
        if (r.computed != null && fromStatement && !readOnly) {
            planeAnkerSuche(r.computed);
        }
        checkDuplicate();
        planeScheduleMatchCheck();
    }

    /**
     * Gibt es diese Buchung schon? Der Hinweis steht gleich unter der Auswahl der Art und hält nichts
     * auf — zweimal am selben Tag dasselbe Papier zum selben Preis zu kaufen ist selten, aber möglich.
     *
     * <p>Gefragt wird nur, wenn sich an Art, Datum oder Beträgen etwas geändert hat: sonst liefe bei
     * jedem Tastendruck eine Abfrage. Beim Bearbeiten einer gespeicherten Bewegung nimmt {@code exceptId}
     * sie selbst aus, sonst meldete sie sich als ihre eigene Doppelung.</p>
     */
    private void checkDuplicate() {
        if (readOnly) {
            return;
        }
        SecurityTx candidate = duplicateCandidate();
        String key = candidate == null ? "" : candidate.depot + "|" + candidate.securityKmyId + "|"
                + candidate.action + "|" + candidate.date + "|" + candidate.shares + "|"
                + candidate.amountCents + "|" + candidate.netCents + "|" + candidate.feeCents;
        if (key.equals(lastDupKey)) {
            return;
        }
        lastDupKey = key;
        if (listHintKey == null) {
            // Der erste Stand ist der, für den der Hinweis der Liste galt.
            listHintKey = key;
            showDuplicate(listHint);
        }
        if (candidate == null) {
            dupBooked = false;
            showDuplicate(key.equals(listHintKey) ? listHint : 0);
            return;
        }
        repository.findExistingSecurityTx(java.util.Collections.singletonList(candidate),
                loaded == null ? 0 : loaded.id, found -> {
                    if (!key.equals(lastDupKey)) {
                        return; // Zwischenzeitlich weitergetippt; die spätere Antwort gilt.
                    }
                    dupBooked = found[0];
                    if (dupBooked) {
                        showDuplicate(R.string.statement_dup_booked);
                    } else {
                        // Die Doppelung innerhalb der Auswahl kennt nur die Liste; sie gilt weiter,
                        // solange an den Werten nichts geändert wurde.
                        showDuplicate(key.equals(listHintKey) ? listHint : 0);
                    }
                });
    }

    private void showDuplicate(int hint) {
        duplicateWarning.setVisibility(hint == 0 ? View.GONE : View.VISIBLE);
        if (hint != 0) {
            duplicateWarning.setText(hint);
        }
    }

    /**
     * Die Bewegung, wie sie beim Speichern entstünde — aber nur so weit, wie {@code sameMovement} sie
     * vergleicht. {@code null}, solange noch etwas Wesentliches fehlt: an einem halben Stand ist keine
     * Doppelung zu erkennen.
     */
    private SecurityTx duplicateCandidate() {
        String action = currentAction();
        if (!actionKnown || !dateKnown || action == null || conflict || kmyId.isEmpty()) {
            return null;
        }
        Long gross = money(Field.GROSS);
        Long net = money(Field.NET);
        Double count = number(Field.SHARES);
        boolean dividend = DIVIDEND.equals(action);
        if (gross == null || net == null || gross <= 0 || (!dividend && (count == null || count <= 0))) {
            return null;
        }
        Long fee = money(Field.FEE);
        SecurityTx tx = new SecurityTx();
        tx.depot = depot;
        tx.securityKmyId = kmyId;
        tx.securityName = securityName;
        tx.date = selectedDate.getTimeInMillis();
        // Dieselben Regeln wie beim Speichern – buchstäblich dieselben, siehe SecurityTx.applyAmounts.
        tx.applyAmounts(action, count, gross, net, fee == null ? 0 : fee);
        return tx;
    }

    /**
     * Wie {@link #duplicateCandidate()}, nur zusätzlich mit dem Geldkonto befüllt — das braucht
     * {@link de.spahr.ausgaben.db.ScheduleMatch}, aber keine Doppelungsprüfung.
     */
    private SecurityTx scheduleMatchCandidate() {
        SecurityTx tx = duplicateCandidate();
        if (tx == null) {
            return null;
        }
        tx.moneyAccount = textOf(editAccount).trim();
        return tx.moneyAccount.isEmpty() ? null : tx;
    }

    /**
     * Verwirft eine noch anstehende Prüfung und stößt sie mit Wartezeit neu an — wie
     * {@link #planeAnkerSuche}, nur für den einen Schedule-Abgleich statt je Feld.
     */
    private void planeScheduleMatchCheck() {
        if (readOnly) {
            return;
        }
        if (scheduleMatchPending != null) {
            ankerHandler.removeCallbacks(scheduleMatchPending);
        }
        scheduleMatchPending = this::performScheduleMatchCheck;
        ankerHandler.postDelayed(scheduleMatchPending, ANKER_DEBOUNCE_MS);
    }

    /**
     * Sucht live nach einer passenden geplanten Umbuchung (siehe {@link de.spahr.ausgaben.db.ScheduleMatch})
     * und zeigt bei einem Treffer {@link #cbScheduleMatch} an — vorbelegt angehakt. Ohne Treffer oder bei
     * unvollständigen Angaben bleibt sie versteckt.
     */
    private void performScheduleMatchCheck() {
        SecurityTx candidate = scheduleMatchCandidate();
        final long token = ++scheduleMatchToken;
        if (candidate == null) {
            currentScheduleMatch = null;
            cbScheduleMatch.setVisibility(View.GONE);
            return;
        }
        repository.findScheduleMatches(java.util.Collections.singletonList(candidate), matches -> {
            // Zwei Gründe, diese Antwort fallen zu lassen: Der Nutzer hat weitergetippt (dann gilt sie
            // nicht mehr), oder die Maske ist inzwischen weg. Letzteres prüft sonst Ui#post für die
            // ganze App; dieser Rückweg führt aber über den Handler von Repository, also hier.
            if (token != scheduleMatchToken || isFinishing() || isDestroyed()) {
                return;
            }
            if (matches.isEmpty()) {
                currentScheduleMatch = null;
                cbScheduleMatch.setVisibility(View.GONE);
                return;
            }
            currentScheduleMatch = matches.get(0);
            String date = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT,
                    getResources().getConfiguration().getLocales().get(0))
                    .format(new java.util.Date(currentScheduleMatch.schedule.nextDueMs));
            cbScheduleMatch.setText(getString(R.string.schedule_match_confirm,
                    currentScheduleMatch.schedule.name, date, MoneyFormat.shares(currentScheduleMatch.newShares)));
            cbScheduleMatch.setChecked(true);
            cbScheduleMatch.setVisibility(View.VISIBLE);
        });
    }

    /**
     * Schreibt einen berechneten Wert – aber nie in ein Feld, das der Nutzer selbst gefüllt hat, und
     * nie in das, in dem er gerade steht.
     */
    private void writeUnset(Field field, String text) {
        if (userSet.contains(field) || field == focusedField) {
            return;
        }
        TextInputEditText input = numberFields.get(field);
        String now = textOf(input);
        String next = text == null ? "" : text;
        if (!now.equals(next)) {
            input.setText(next);
        }
    }

    private void setNumber(Field field, double value) {
        // Stückzahlen feiner als Kurse: Sparplan-Anteile haben fünf Nachkommastellen.
        numberFields.get(field).setText(field == Field.SHARES
                ? MoneyFormat.shares(value) : MoneyFormat.decimal(value, 0, 4));
    }

    private void setMoney(Field field, long cents) {
        numberFields.get(field).setText(MoneyFormat.plain(cents));
    }

    /**
     * Markiert ein Zahlenfeld als fehlend beziehungsweise räumt die Markierung wieder weg.
     *
     * <p>Ohne Text: die Umrandung genügt, und ein zusätzlicher Satz unter jedem der drei Felder
     * verschöbe die Maske. Weg ist die Markierung, sobald jemand in das Feld tippt (siehe der
     * {@code SimpleWatcher} in {@code setupNumberField}).</p>
     */
    private void markierungAufhebenBeimTippen(Field field, TextInputLayout layout) {
        numberFields.get(field).addTextChangedListener(new SimpleWatcher(() -> layout.setError(null)));
    }

    private void markiereFehlendes(TextInputLayout layout, boolean fehlt) {
        layout.setErrorIconDrawable(null);
        layout.setError(fehlt ? " " : null);
    }

    private Long money(Field field) {
        String raw = textOf(numberFields.get(field)).trim();
        return raw.isEmpty() ? null : AmountExpression.toCents(raw);
    }

    /**
     * Eine Zahl aus einem der Felder — durch <b>denselben</b> Parser wie {@link #money(Field)}.
     *
     * <p>Bis 1.12 stand hier {@code Double.parseDouble(raw.replace(',', '.'))}, während die
     * Betragsfelder über {@link AmountExpression} liefen. Die Maske hatte damit zwei Parser für
     * Felder, über denen dieselbe Rechentastatur sitzt: Wer die Stückzahl als {@code 10*3} eintippte —
     * was die Tastatur ausdrücklich anbietet —, bekam beim Speichern „Beträge fehlen", obwohl sichtbar
     * etwas im Feld stand. Schwerer wiegt, dass die Stückzahl in {@code duplicateCandidate} und
     * {@code expectation} still wegfiel: Doppelungsprüfung und Lern-Nachprüfung rechneten dann mit
     * einer Lücke, ohne dass jemand etwas davon merkte.</p>
     *
     * <p>{@code evaluate} und nicht {@code toCents}: eine Stückzahl hat mehr Nachkommastellen als
     * Geld ({@code 1,839801}) und darf nicht auf Cent gerundet werden.</p>
     */
    private Double number(Field field) {
        java.math.BigDecimal wert = AmountExpression.evaluate(textOf(numberFields.get(field)).trim());
        return wert == null ? null : wert.doubleValue();
    }

    // ---- Auswahllisten ----

    private void loadPickers() {
        // Konto: Favoriten, dann die Konten der gewählten Gruppe, dann der Rest – mit Tastatur und Liste,
        // genau wie in der Buchungsmaske.
        repository.getAccountNames(names -> PickerAdapters.accounts(repository, editAccount, names));
        repository.getCategoriesGrouped(g -> {
            categoryAdapter = new CategoryFilterAdapter(this, null,
                    getString(R.string.category_group_expense), g.expense,
                    getString(R.string.category_group_income), g.income);
            for (SplitRowController ctl : splitControllers()) {
                ctl.setAdapter(categoryAdapter);
            }
            loadCategoryFavorites();
        });
    }

    /**
     * Die an diesem Wertpapier schon verwendeten Kategorien als Vorspann der Auswahlliste – dieselbe
     * Hilfe wie „bei diesem Empfänger" in der Buchungsmaske, nur bezogen auf das Wertpapier.
     *
     * <p>Beide Felder teilen sich einen Adapter, also auch den Vorspann. Gezeigt wird deshalb, was zur
     * gerade sichtbaren Aktion passt: bei einer Dividende die Ertragskategorien, sonst die Gebühren.</p>
     */
    private void loadCategoryFavorites() {
        if (categoryAdapter == null) {
            return;
        }
        repository.getSecurityUsedCategories(depot, kmyId, lists -> {
            if (categoryAdapter == null || lists.size() < 2) {
                return;
            }
            List<String> used = DIVIDEND.equals(currentAction()) ? lists.get(1) : lists.get(0);
            categoryAdapter.setFavorites(getString(R.string.category_group_security), used);
            for (SplitRowController ctl : splitControllers()) {
                ctl.applyAdapterToRows();
            }
        });
    }

    /**
     * Gegenkonto und Kategorien aus der jüngsten Bewegung derselben Art übernehmen — zuerst von diesem
     * Wertpapier, sonst von einem beliebigen anderen (siehe {@code DepotRepository.getTxDefaults}).
     *
     * @param overwrite beim Aktionswechsel {@code true}: die Felder tragen dann noch die Werte der
     *                  vorherigen Aktion und sind damit überholt — sonst bliebe die Gebührenkategorie
     *                  eines Kaufs stehen, obwohl daneben „Steuerkategorie" steht
     */
    private void loadDefaults(String action, boolean overwrite) {
        repository.getSecurityTxDefaults(depot, kmyId, action, last -> {
            // Die Abfrage lief über den Executor. Wer zweimal schnell umschaltet, bekommt die Antwort auf
            // die alte Frage womöglich nach der neuen – dann ist sie überholt und wird verworfen.
            if (last == null || !action.equals(currentAction())) {
                return;
            }
            setDefault(editAccount, last.moneyAccount, overwrite);
            if (overwrite || (knownFeeParts.isEmpty() && knownIncomeParts.isEmpty())) {
                knownFeeParts.clear();
                knownIncomeParts.clear();
                for (SecurityTxSplit part : last.parts) {
                    (part.income ? knownIncomeParts : knownFeeParts)
                            .add(new CategorySplits.Part(part.category, 0, part.label));
                }
                applySplitRows();
            }
        });
    }

    private void setDefault(PickerTextView field, String value, boolean overwrite) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (overwrite || textOf(field).trim().isEmpty()) {
            field.setText(value, false);
        }
    }

    // ---- Speichern ----

    /**
     * Speichern: prüfen, zusammenbauen, schreiben — und danach anbieten, aus der Abrechnung zu lernen.
     *
     * <p>Die drei Schritte stehen in eigenen Methoden. Vorher waren es knapp hundert Zeilen am Stück,
     * in denen Prüfungen, Vorzeichenregeln, Belegablage und Datenbankaufruf ineinanderliefen.</p>
     */
    private void save() {
        if (saving) {
            // Zwischen dem Tipp und dem finish() aus offerToLearn liegen eine Datenbankschreibung und
            // womöglich zwei Rückfragen – die Maske steht so lange sichtbar offen. Ohne diese Sperre
            // legt ein zweiter Tipp Bewegung und Gegenbuchung ein zweites Mal an, und weil
            // pendingStatement beim ersten Durchlauf verbraucht wurde, hinge der Beleg nur an der
            // ersten von beiden.
            return;
        }
        if (batchMode) {
            returnToList();
            return;
        }
        if (!eingabenSindVollstaendig()) {
            return;
        }
        schreibe(bewegungAusDerMaske());
    }

    /**
     * Was fehlt, wird gesagt — und zwar am Feld, an dem es fehlt.
     *
     * @return {@code false}, wenn nicht gespeichert werden kann; die Meldung steht dann schon
     */
    private boolean eingabenSindVollstaendig() {
        if (conflict) {
            Toast.makeText(this, R.string.security_tx_conflict, Toast.LENGTH_LONG).show();
            return false;
        }
        if (!actionKnown) {
            Toast.makeText(this, R.string.security_tx_need_action, Toast.LENGTH_LONG).show();
            return false;
        }
        if (!dateKnown) {
            Toast.makeText(this, R.string.security_tx_need_date, Toast.LENGTH_LONG).show();
            return false;
        }
        boolean dividend = DIVIDEND.equals(currentAction());
        Long gross = money(Field.GROSS);
        Long net = money(Field.NET);
        Double count = number(Field.SHARES);
        if (gross == null || net == null || gross <= 0 || (!dividend && (count == null || count <= 0))) {
            // Der Sammel-Toast sagte nur, dass etwas fehlt, nicht was. Bei fünf Zahlenfeldern
            // untereinander ist das eine Suchaufgabe – also wird das Feld selbst markiert.
            markiereFehlendes(grossLayout, gross == null || gross <= 0);
            markiereFehlendes(netLayout, net == null);
            markiereFehlendes(sharesLayout, !dividend && (count == null || count <= 0));
            Toast.makeText(this, R.string.security_tx_need_amounts, Toast.LENGTH_LONG).show();
            return false;
        }
        if (textOf(editAccount).trim().isEmpty()) {
            Toast.makeText(this, R.string.security_tx_need_account, Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    /**
     * Die Bewegung, wie sie in der Maske steht. Beim Ändern wird die geladene weiterverwendet, damit
     * ihre id und ihre Verknüpfung zur Buchung erhalten bleiben.
     *
     * <p>Vorzeichen und Dividendenregeln stehen nicht hier, sondern in
     * {@link SecurityTx#applyAmounts} — an einer Stelle für alle, die sie brauchen.</p>
     */
    private SecurityTx bewegungAusDerMaske() {
        String action = currentAction();
        Long fee = money(Field.FEE);
        SecurityTx tx = loaded != null ? loaded : new SecurityTx();
        tx.depot = depot;
        tx.securityKmyId = kmyId;
        tx.securityName = securityName;
        tx.date = selectedDate.getTimeInMillis();
        tx.applyAmounts(action, number(Field.SHARES), money(Field.GROSS), money(Field.NET),
                fee == null ? 0 : Math.abs(fee));
        tx.moneyAccount = textOf(editAccount).trim();
        tx.parts.clear();
        tx.parts.addAll(splitsOf(feeSplits, false));
        if (DIVIDEND.equals(action)) {
            tx.parts.addAll(splitsOf(incomeSplits, true));
        }
        return tx;
    }

    /** Bewegung und Gegenbuchung wegschreiben, den Beleg vormerken und danach zum Lernen überleiten. */
    private void schreibe(SecurityTx tx) {
        final String action = tx.action;
        // Der Buchungsbetrag kommt aus tx.netCents und nicht noch einmal aus dem Feld: Damit gilt
        // „Buchungsbetrag == netCents" von selbst, und genau daran hängt SecurityTxMatch.
        Booking booking = buildBooking(tx, tx.netCents);
        // Die Abrechnung wird zum Beleg der Gegenbuchung – dauerhaft und über denselben Weg wie die
        // Belege der übrigen Buchungen (Ablage, Jahresordner, Abgleich, Export).
        // Der Name des Belegs steht damit fest und kann in die Notiz; bewegt wird die Datei erst, wenn
        // die Buchung wirklich in der Datenbank steht (siehe SingleReceipt.plan).
        final de.spahr.ausgaben.receipt.SingleReceipt.Planned beleg =
                de.spahr.ausgaben.receipt.SingleReceipt.plan(pendingStatement, booking.note,
                        booking.createdAt);
        if (beleg.hatBeleg()) {
            booking.note = beleg.note;
            // Wohin die Datei wandern wird, steht jetzt in der Notiz. Der Tag wird gebraucht: gleich
            // danach wird aus dieser Abrechnung gelernt und an ihr nachgeprüft.
            savedStatementTag = de.spahr.ausgaben.receipt.NoteReceipt.pdfName(booking.note);
        }
        // Dieselbe Notiz auch an der Bewegung: Nur so übersteht der Beleg-Tag den Rundlauf durch die
        // KMyMoney-Datei. Der Weg über booking_id tut es nicht – importDepot stellt die Verknüpfung
        // nicht wieder her (siehe SecurityTx#note).
        tx.note = booking.note == null ? "" : booking.note;
        final Double sharesGiven = number(Field.SHARES);
        final Double priceGiven = number(Field.PRICE);
        final Long feeGiven = money(Field.FEE);
        final Long netGiven = money(Field.NET);
        final Long grossGiven = money(Field.GROSS);
        Runnable done = () -> {
            if (de.spahr.ausgaben.receipt.SingleReceipt.attach(this, beleg)) {
                pendingStatement = null;
            } else if (beleg.hatBeleg()) {
                Toast.makeText(this, R.string.statement_receipt_failed, Toast.LENGTH_LONG).show();
            }
            Toast.makeText(this, R.string.security_tx_saved, Toast.LENGTH_SHORT).show();
            Runnable weiter = () -> offerToLearn(action, sharesGiven, priceGiven, feeGiven, netGiven,
                    grossGiven);
            // Die Checkbox trägt die Entscheidung schon: angehakt zuordnen, abgewählt nichts weiter tun –
            // keine Rückfrage mehr nach dem Speichern.
            if (cbScheduleMatch.getVisibility() == View.VISIBLE && cbScheduleMatch.isChecked()
                    && currentScheduleMatch != null) {
                repository.confirmScheduleMatch(currentScheduleMatch, () -> {
                    Toast.makeText(this, R.string.schedule_match_done, Toast.LENGTH_SHORT).show();
                    weiter.run();
                });
            } else {
                weiter.run();
            }
        };
        // Ab hier ist geschrieben; erst jetzt sperren, damit eine abgebrochene Prüfung oben den Knopf
        // nicht für immer stilllegt.
        saving = true;
        btnSave.setEnabled(false);
        if (loaded != null) {
            repository.updateManualSecurityTx(tx, booking, done);
        } else {
            repository.saveManualSecurityTx(tx, booking, done);
        }
    }

    /**
     * Verlassen der Maske ohne „Speichern" beziehungsweise „Übernehmen".
     *
     * <p>Beim Berichtigen eines Stapels ist das <b>kein</b> Verwerfen: die Maske ist dort nur die
     * Detailansicht einer Zeile der Erkennungsliste, gebucht wird erst dort. Der Stand geht deshalb
     * genauso zurück wie über „Übernehmen" — bis 1.12 gaben Pfeil und Systemzurück schlicht kein
     * Ergebnis zurück, und alles Getippte war weg, obwohl der Javadoc von {@link #returnToList} das
     * Gegenteil verspricht.</p>
     *
     * <p>Außerhalb des Stapels bleibt es beim Beenden: dort ist die Maske eine eigene Erfassung, und
     * wer sie ohne Speichern verlässt, will nichts anlegen.</p>
     */
    private void zurueck() {
        if (batchMode) {
            returnToList();
        } else {
            finish();
        }
    }

    /**
     * Der Weg zurück in die Erkennungsliste. Übergeben wird der Stand der Maske, wie er dasteht — auch
     * ein unfertiger: geprüft wird in der Liste, und dort bleibt die Zeile dann eben rot. Wer beim
     * Berichtigen zwischendurch aufhört, soll das Erreichte nicht verlieren.
     */
    private void returnToList() {
        String action = currentAction();
        android.content.Intent out = new android.content.Intent();
        // Was hier nicht feststeht, wird auch nicht übergeben: der Eintrag bleibt in der Liste rot,
        // statt über den Umweg durch die Maske stillschweigend das heutige Datum zu erben.
        if (actionKnown) {
            out.putExtra(EXTRA_PREFILL_ACTION, action);
        }
        if (dateKnown) {
            out.putExtra(EXTRA_PREFILL_DATE, selectedDate.getTimeInMillis());
        }
        putNumber(out, EXTRA_PREFILL_SHARES, number(Field.SHARES));
        putNumber(out, EXTRA_PREFILL_PRICE, number(Field.PRICE));
        putMoney(out, EXTRA_PREFILL_GROSS, money(Field.GROSS));
        putMoney(out, EXTRA_PREFILL_FEE, money(Field.FEE));
        putMoney(out, EXTRA_PREFILL_NET, money(Field.NET));
        out.putExtra(EXTRA_PREFILL_ACCOUNT, textOf(editAccount).trim());
        putParts(out, EXTRA_PREFILL_FEE_PARTS, collectedParts(feeSplits));
        putParts(out, EXTRA_PREFILL_INCOME_PARTS,
                DIVIDEND.equals(action) ? collectedParts(incomeSplits)
                        : new ArrayList<>());
        out.putExtra(EXTRA_CONFLICT, conflict);
        out.putExtra(EXTRA_DUP_BOOKED, dupBooked);
        out.putExtra(EXTRA_SCHEDULE_MATCH_OPT_OUT,
                cbScheduleMatch.getVisibility() == View.VISIBLE && !cbScheduleMatch.isChecked());
        setResult(RESULT_OK, out);
        finish();
    }

    /** Die Kategoriezeilen einer Liste als Bewegungsteile. */
    private List<SecurityTxSplit> splitsOf(SplitRowController ctl, boolean income) {
        List<SecurityTxSplit> out = new ArrayList<>();
        int sort = 0;
        for (SplitRowController.Part part : ctl.collectParts()) {
            // Ohne Betrag: das Vorzeichen gehört zur Zeile (siehe SecurityTxSplit).
            out.add(new SecurityTxSplit(0, income, part.category, part.cents,
                    part.label, sort++));
        }
        return out;
    }

    /**
     * Die Kategoriezeilen für den Lerner: Betrag und Kategorie.
     *
     * <p>Gelernt wird beides — wo der Betrag in der Abrechnung steht und wohin er gebucht gehört. Die
     * Beschriftung fehlt hier mit Absicht: die leitet der Lerner aus der gefundenen Zeile ab, und was
     * in der Maske steht, kann von einer fest programmierten Bank stammen, die gar keine kennt. Die beim
     * Verlassen des Feldes erkannte (oder vom Nutzer gewählte) Regel wandert dagegen mit — sie hat wie
     * bei den Hauptfeldern Vorrang vor der eigenen Suche des Lerners (siehe {@code TemplateLearner.learnParts}).</p>
     */
    private List<StatementTemplate.Part> lernbareTeile(SplitRowController ctl) {
        List<StatementTemplate.Part> out = new ArrayList<>();
        for (SplitRowController.Part part : ctl.collectParts()) {
            out.add(new StatementTemplate.Part("", Math.abs(part.cents), part.category, part.chosenRule));
        }
        return out;
    }

    /** Die Beträge der Kategoriezeilen — was der Lerner in der Abrechnung wiederfinden soll. */
    private List<Long> partAmounts(SplitRowController ctl) {
        List<Long> out = new ArrayList<>();
        for (SplitRowController.Part part : ctl.collectParts()) {
            out.add(Math.abs(part.cents));
        }
        return out;
    }

    /** Die erste Kategorie einer Liste; leer, wenn keine dasteht. */
    private String firstCategory(SplitRowController ctl) {
        for (SplitRowController.Part part : ctl.collectParts()) {
            return part.category;
        }
        return "";
    }

    /**
     * Dieselben Zeilen für den Weg zurück in die Erkennungsliste — <b>mit</b> Vorzeichen.
     *
     * <p>Anders als bei {@link #lernbareTeile} und {@link #partAmounts}: die suchen den Betrag im
     * Text der Abrechnung, und dort steht die Kapitalertragsteuer als positive Zahl unter ihrer
     * Beschriftung. Hier dagegen gehen die Zeilen unverändert weiter in eine Bewegung, und dort ist
     * das Vorzeichen Teil der Angabe (siehe {@link SecurityTxSplit}).</p>
     */
    private List<CategorySplits.Part> collectedParts(SplitRowController ctl) {
        List<CategorySplits.Part> out = new ArrayList<>();
        for (SplitRowController.Part part : ctl.collectParts()) {
            out.add(new CategorySplits.Part(part.category, part.cents, part.label));
        }
        return out;
    }

    private static void putNumber(android.content.Intent out, String key, Double value) {
        if (value != null) {
            out.putExtra(key, (double) value);
        }
    }

    private static void putMoney(android.content.Intent out, String key, Long value) {
        if (value != null) {
            out.putExtra(key, (long) value);
        }
    }

    /**
     * Kam die Maske aus einer eingelesenen Abrechnung, leitet die App jetzt ab, wo die Werte darin
     * standen — und fragt einmal, ob sie sich das für diese Bank merken soll.
     *
     * <p>Das ist der Kern des Verfahrens: die erste Abrechnung einer Bank tippt man ohnehin ab, und
     * genau daraus lernt die App die Beschriftungen. Eine Markier-Oberfläche, in der man auf dem Handy
     * kleine Zahlen antippt, wird damit überflüssig.</p>
     */
    private void offerToLearn(String action, Double shares, Double price, Long feeCents, Long netCents,
                              Long grossCents) {
        learnAction = action;
        learnShares = shares;
        learnPrice = price;
        learnFeeCents = feeCents;
        learnNetCents = netCents;
        learnGrossCents = grossCents;
        if (statementTextPath == null && statementPdf() == null) {
            finish();
            return;
        }
        // Wer nichts angefasst hat, hat der App nichts beizubringen – dann bleibt die Rückfrage aus.
        // Das steht vor dem Einlesen: sonst läge das PDF umsonst auf dem Tisch.
        if (typedFields.isEmpty() && !dateTyped) {
            finish();
            return;
        }
        // Das Einlesen ist keine Kleinigkeit mehr, seit es aus dem PDF selbst kommt – also nicht im
        // Vordergrund. Gelernt wird gleich mit; der Dialog kommt danach auf dem Bedienfaden.
        repository.executor().execute(() -> {
            final de.spahr.ausgaben.pdf.PdfText text = readStatementText();
            post(() -> {
                // Wer inzwischen weggegangen ist, bekommt keinen Dialog mehr auf ein Fenster, das es
                // nicht mehr gibt.
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (text == null) {
                    finish();
                    return;
                }
                learnFrom(text, action, shares, price, feeCents, netCents, grossCents);
            });
        });
    }

    /** Der zweite Teil von {@link #offerToLearn}: die Abrechnung liegt gelesen vor. */
    private void learnFrom(de.spahr.ausgaben.pdf.PdfText text, String action, Double shares,
                           Double price, Long feeCents, Long netCents, Long grossCents) {
        final StatementTemplates store = new StatementTemplates(this);
        final StatementTemplate existing = matchedTemplate(text);

        // Wieviel gelernt wird, hängt daran, ob es für diese Bank schon eine Vorlage gibt.
        //
        // <b>Noch keine:</b> dann zählt jeder Wert der Maske, auch ein gerechneter. Bei einer Dividende
        // tippt man Brutto und Steuer, und das Netto rechnet die Maske – ohne diesen Fall entstünde eine
        // Vorlage ohne Gesamtbetrag-Regel, und die erkennt kein Dokument wieder ({@code score}).
        //
        // <b>Schon eine:</b> dann nur das selbst Getippte. Alles andere hat die Vorlage vorgelegt, und
        // dazu erneut eine Beschriftung zu suchen hieße, den eigenen Vorschlag wiederzufinden und für
        // eine Bestätigung zu halten.
        final boolean ersteVorlage = existing == null;
        TemplateLearner.Known known = new TemplateLearner.Known();
        known.action = action;
        // Bei einer Dividende gibt es weder Stückzahl noch Stückpreis: die Anzahl am Ex-Tag steht nicht in
        // der Abrechnung, und die Ausschüttung je Stück ist dort in Fremdwährung ausgewiesen.
        known.shares = DIVIDEND.equals(action) || !lernen(ersteVorlage, Field.SHARES) ? null : shares;
        known.price = DIVIDEND.equals(action) || !lernen(ersteVorlage, Field.PRICE) ? null : price;
        known.feeCents = lernen(ersteVorlage, Field.FEE) ? feeCents : null;
        known.netCents = lernen(ersteVorlage, Field.NET) ? netCents : null;
        // Das Brutto nur bei einer Dividende — sonst ist es die gerechnete dritte Zahl und steht nicht
        // einmal in der Maske. Und auch dort nur als Rückhalt für eine ausdrücklich gewählte
        // Beschriftung: von selbst sucht der Lerner dafür nichts (siehe TemplateLearner.Known#grossCents).
        known.grossCents = DIVIDEND.equals(action) && lernen(ersteVorlage, Field.GROSS)
                ? grossCents : null;
        // Wird aus der Gebühr eine feste Ordergebühr, braucht sie eine Kategorie – und die steht hier.
        known.feeCategory = firstCategory(feeSplits);
        // Die Aufteilung wird immer gelernt, gleich ob es schon eine Vorlage gibt: sie steht nur dann
        // in der Maske, wenn der Nutzer sie selbst so eingetragen hat.
        known.feeParts = lernbareTeile(feeSplits);
        known.incomeParts = DIVIDEND.equals(action) ? lernbareTeile(incomeSplits) : new ArrayList<>();
        // Beim Datum zählt die eigene Wahl: hat der Nutzer sie nicht getroffen, bleibt die schon gelernte
        // Beschriftung gültig. Ohne das würde bei zwei Zeilen mit demselben Datum („Zahltag" und
        // „Valuta") jedes Mal die unterste neu gelernt.
        known.dateMillis = ersteVorlage || dateTyped ? selectedDate.getTimeInMillis() : -1;
        known.dateAnchor = chosenDateLabel;
        known.dateRule = chosenDateRule;
        // Und die Beschriftungen, die der Nutzer beim Verlassen der Wertfelder ausgewählt hat. Sie
        // gelten nur für die Felder, aus denen hier überhaupt gelernt wird — was oben auf null gesetzt
        // wurde, bekommt auch keine Regel.
        for (Map.Entry<Field, de.spahr.ausgaben.statement.AnchorRule> e : chosenValueRules.entrySet()) {
            StatementTemplate.Field lernfeld = lernfeldVon(e.getKey());
            if (lernfeld != null) {
                known.chosenRules.put(lernfeld, e.getValue());
            }
        }

        if (known.dateAnchor == null && existing != null
                && existing.rule(StatementTemplate.Field.DATE) != null) {
            known.dateAnchor = existing.rule(StatementTemplate.Field.DATE).anchors.get(0);
        }
        final StatementTemplate raw = TemplateLearner.learn(text, known);
        // Zwei Lesarten dessen, was dabei herauskam:
        // „Ersetzen" – die neue Regel gilt, das bisher Gelernte bleibt nur, wo diese Abrechnung nichts
        //   hergab (eine fehlende Zeile). Sonst verlernte die App an einer unvollständigen Abrechnung.
        // „Hinzufügen" – die bisherige Reihenfolge behält Vorrang, die neue Beschriftung kommt als
        //   weiterer Rückfall dahinter. Das schützt, was auf der Regelseite von Hand geordnet wurde.
        // Was der Nutzer im Stift-Dialog schon entschieden hat (siehe buildAnchorChoiceDialog), wird
        // feldweise eingesetzt, statt es unten noch einmal fürs ganze Formular zu fragen: Felder mit dem
        // Schalter auf „hinzufügen" bekommen ihre Regel aus `appended`, ausdrücklich ersetzte direkt aus
        // `raw`. Nur was danach noch strittig ist – Datum, Teilbeträge, ein nicht angetipptes Feld –
        // geht überhaupt noch in die Rückfrage.
        //
        // Ausdrücklich ersetzte Felder gehen bewusst an `mergedOver` vorbei: das behält eine alte Regel,
        // wenn die neue wie ein Auszug aus ihr aussieht (eine Ankerkette, aus der ein Glied fehlt —
        // siehe StatementTemplate#isExcerptOf). Gegen eine unvollständige Abrechnung ist das richtig,
        // gegen eine ausdrückliche Wahl im Stift-Dialog wäre es ein stilles Verwerfen: wer eine
        // Beschriftung antippt, die schon in der Kette steht, bekäme seine Entscheidung nicht gelernt.
        Set<StatementTemplate.Field> anhaengen = lernfelder(anhaengenFelder, true);
        Set<StatementTemplate.Field> ersetzen = lernfelder(ersetzteRegeln, false);
        final StatementTemplate replaced = raw.mergedOver(existing).withRulesFrom(raw, ersetzen);
        final StatementTemplate appended = raw.appendedTo(existing, text);
        final StatementTemplate replacedMix = replaced.withRulesFrom(appended, anhaengen);
        final StatementTemplate appendedMix = appended.withRulesFrom(raw, ersetzen);
        // Nur fragen, wenn dabei wirklich etwas Neues herauskam. Wer nichts korrigiert hat, bekommt
        // dieselben Regeln zurück – dann gibt es nichts zu merken, und die Rückfrage wäre nur Lärm.
        // Dasselbe, wenn der korrigierte Wert im PDF gar nicht vorkommt: dann entsteht keine Regel.
        if (replacedMix.isEmpty() || replacedMix.sameAs(existing)) {
            // Wer im Stift-Fenster ausdrücklich „Lernen" gedrückt hat, darf hier nicht ins Leere laufen:
            // dass dabei nichts herauskam, ist dann eine Nachricht wert. Ohne eigene Entscheidung ist es
            // dagegen der Normalfall (nichts korrigiert) und bliebe besser still.
            if (!entschiedenFuer.isEmpty()) {
                Toast.makeText(this, R.string.statement_learn_nothing_new, Toast.LENGTH_LONG).show();
            }
            finish();
            return;
        }
        // Ohne eine solche Entscheidung bleibt alles beim Alten: dann fragt die Rückfrage auch dann noch,
        // wenn Ersetzen und Hinzufügen aufs Gleiche hinauslaufen (sie heißt dann schlicht „Regeln merken?").
        boolean entschieden = !anhaengen.isEmpty() || !ersetzen.isEmpty();
        if (entschieden && replacedMix.sameAs(appendedMix)) {
            keep(store, replacedMix, text);
            return;
        }
        // Der Dialog wird nicht hier gebaut, sondern in buildDialog – siehe HostedDialog: nach einer
        // Drehung baut ihn die neue Maske erneut, und zwar aus diesen Angaben.
        lernAngebot = new LernAngebot(store, raw, existing, replacedMix, appendedMix, text);
        HostedDialog.show(this, DLG_LEARN, null);
    }

    /** Was die Lern-Rückfrage zum Bauen braucht. Lebt nur im Speicher – siehe {@link #buildDialog}. */
    private static final class LernAngebot {
        final StatementTemplates store;
        final StatementTemplate raw;
        final StatementTemplate existing;
        final StatementTemplate replaced;
        final StatementTemplate appended;
        final de.spahr.ausgaben.pdf.PdfText text;

        LernAngebot(StatementTemplates store, StatementTemplate raw, StatementTemplate existing,
                    StatementTemplate replaced, StatementTemplate appended,
                    de.spahr.ausgaben.pdf.PdfText text) {
            this.store = store;
            this.raw = raw;
            this.existing = existing;
            this.replaced = replaced;
            this.appended = appended;
            this.text = text;
        }
    }

    private android.app.Dialog buildLearnDialog() {
        final LernAngebot a = lernAngebot;
        AppDialog dialog = new AppDialog(this);
        dialog.setTitle(R.string.statement_learn_title);
        if (a.appended.sameAs(a.replaced)) {
            dialog.setMessage(learnMessage(a.raw, a.existing));
            dialog.setPositiveButton(R.string.statement_learn_yes,
                    (d, w) -> keep(a.store, a.replaced, a.text));
        } else {
            // Es gibt einen echten Widerspruch: die neue Beschriftung tritt an die Stelle einer
            // vorhandenen. Das ist nicht zu entscheiden, ohne zu wissen, ob die alte weiter gebraucht
            // wird – also wird gefragt, statt zu raten. Der (i)-Knopf zeigt auf Wunsch, welche
            // Beschriftung je Feld alt und welche neu ist – ohne ihn steht nur der generische Hinweistext.
            View view = getLayoutInflater().inflate(R.layout.dialog_learn_conflict, null);
            TextView details = view.findViewById(R.id.learnConflictDetails);
            details.setText(konfliktDetails(a.raw, a.existing));
            view.findViewById(R.id.btnLearnConflictInfo).setOnClickListener(v ->
                    details.setVisibility(details.getVisibility() == View.VISIBLE
                            ? View.GONE : View.VISIBLE));
            dialog.setView(view);
            dialog.setPositiveButton(R.string.statement_learn_append,
                    (d, w) -> keep(a.store, a.appended, a.text));
            dialog.setNeutralButton(R.string.statement_learn_replace,
                    (d, w) -> keep(a.store, a.replaced, a.text));
        }
        dialog.setNegativeButton(R.string.cancel, (d, w) -> finish());
        return dialog.create();
    }

    /**
     * Der Text hinter dem (i)-Knopf im Widerspruch-Dialog: je Feld und Teilbetrag, wo sich die schon
     * gemerkte Beschriftung von der neu gefundenen unterscheidet — sonst wüsste man beim Klick auf
     * „Ersetzen" nicht, was genau man damit aufgibt.
     */
    private String konfliktDetails(StatementTemplate raw, StatementTemplate existing) {
        StringBuilder out = new StringBuilder();
        for (StatementTemplate.Field f : StatementTemplate.Field.values()) {
            de.spahr.ausgaben.statement.AnchorRule alt = existing == null ? null : existing.rule(f);
            de.spahr.ausgaben.statement.AnchorRule neu = raw.rule(f);
            if (neu == null || neu.equals(alt)) {
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(StatementFieldNames.of(this, f, raw.action)).append(": ")
                    .append(alt == null ? getString(R.string.statement_anchor_none) : anchorText(alt))
                    .append(" → ").append(anchorText(neu));
        }
        konfliktDetailsTeile(out, raw.feeParts, existing == null ? null : existing.feeParts);
        konfliktDetailsTeile(out, raw.incomeParts, existing == null ? null : existing.incomeParts);
        return out.length() == 0 ? getString(R.string.statement_learn_conflict) : out.toString();
    }

    private void konfliktDetailsTeile(StringBuilder out, List<StatementTemplate.PartRule> neu,
                                      List<StatementTemplate.PartRule> alt) {
        for (StatementTemplate.PartRule teil : neu) {
            StatementTemplate.PartRule alterTeil = alt == null ? null : partMitKategorie(alt, teil.category);
            if (alterTeil != null && alterTeil.rule.equals(teil.rule)) {
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(teil.category.isEmpty() ? getString(R.string.split_partial_hint) : teil.category)
                    .append(": ")
                    .append(alterTeil == null ? getString(R.string.statement_anchor_none)
                            : anchorText(alterTeil.rule))
                    .append(" → ").append(anchorText(teil.rule));
        }
    }

    private static StatementTemplate.PartRule partMitKategorie(List<StatementTemplate.PartRule> parts,
                                                                String category) {
        for (StatementTemplate.PartRule p : parts) {
            if (p.category.equals(category)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Baut die Dialoge dieser Maske – beim ersten Mal und nach jeder Drehung erneut (siehe
     * {@link HostedDialog}).
     *
     * <p>Die beiden Rückfragen am Ende des Speicherns liefern hier {@code null}, wenn ihre Grundlage
     * nach einer Drehung fehlt. Sie verschwinden dann kurz und kommen über {@link #wiederaufnahme()}
     * wieder – neu berechnet aus der erneut gelesenen Abrechnung.</p>
     */
    @Override
    public android.app.Dialog buildDialog(String key, Bundle args) {
        switch (key) {
            case DLG_DATE_CHOICE:
                return buildDateChoiceDialog(args);
            case DLG_ANCHOR_CHOICE:
                return buildAnchorChoiceDialog(args);
            case DLG_LEARN_INTRO:
                return buildLearnIntroDialog();
            case DLG_CALENDAR:
                return buildCalendarDialog();
            case DLG_LEARN:
                return lernAngebot == null ? null : buildLearnDialog();
            case DLG_VERIFY:
                return pruefErgebnis == null ? null : buildVerifyDialog();
            default:
                return null;
        }
    }

    /**
     * Weggetippt oder mit Zurück verlassen. Bei den beiden Rückfragen am Ende des Speicherns ist das
     * der Weg hinaus: Die Bewegung ist gebucht, die Maske hat nichts mehr zu tun.
     */
    @Override
    public void onDialogCancelled(String key, Bundle args) {
        if (DLG_LEARN.equals(key) || DLG_VERIFY.equals(key)) {
            finish();
        }
    }

    /**
     * Ob dieses Feld in den Lernvorgang geht (siehe {@code offerToLearn}).
     *
     * <p>Stand für dieses Feld schon eine ANDERE Regel in der Bank-Vorlage ({@link #konfliktFelder}),
     * zählt die Korrektur nur, wenn der Nutzer sie übers Stift-Symbol ausdrücklich bestätigt hat
     * ({@link #ersetzteRegeln}) — sonst bucht sie nur diese eine Bewegung richtig, ohne die für künftige
     * Belege dieser Bank gespeicherte Regel anzutasten. Und wer dort „Nicht lernen" gewählt hat
     * ({@link #nichtLernenFelder}), bekommt dasselbe auch ohne Widerspruch.</p>
     */
    private boolean lernen(boolean ersteVorlage, Field field) {
        // Selbst getippt, erster Beleg dieser Bank — oder im Stift-Fenster ausdrücklich entschieden.
        // Letzteres zählt auch für ein gerechnetes Feld: Bei einer Dividende fällt eine der drei Zahlen
        // aus den beiden anderen heraus, und wer für sie eine Beschriftung antippt, meint sie.
        if (!(ersteVorlage || typedFields.contains(field) || entscheidungGilt(field))) {
            return false;
        }
        if (nichtLernenFelder.contains(field) && entscheidungGilt(field)) {
            return false;
        }
        return !konfliktFelder.contains(field)
                || (ersetzteRegeln.contains(field) && entscheidungGilt(field));
    }

    /**
     * Ob die im Stift-Fenster getroffene Entscheidung noch für den Wert gilt, der jetzt im Feld steht.
     *
     * <p>Ein Feld, dessen Wert sich seither wirklich geändert hat, braucht eine neue Entscheidung — die
     * alte galt einer anderen Zahl. Umgekehrt darf sie nicht verfallen, bloß weil die Maske
     * zwischendurch etwas in das Feld zurückgeschrieben hat: siehe {@link #entschiedenFuer}.</p>
     */
    private boolean entscheidungGilt(Field field) {
        Double entschieden = entschiedenFuer.get(field);
        return entschieden != null && entschieden.equals(wertVon(field));
    }

    /**
     * Die Vorlagenfelder zu einer im Stift-Dialog getroffenen Entscheidung — siehe {@link #learnFrom}.
     *
     * <p>Es zählt allein, dass der Nutzer im Stift-Fenster für den Wert entschieden hat, der jetzt im
     * Feld steht ({@link #entscheidungGilt}). Bewusst <b>nicht</b> zusätzlich {@link #konfliktFelder}:
     * dieser Merker wird von jedem Textereignis im Feld geleert und danach nur von der Suche wieder
     * nachgetragen — die aber hält sich hinter einer gültigen Entscheidung heraus. Die Frage wäre
     * dadurch beim Speichern ein zweites Mal gestellt worden, obwohl sie längst beantwortet war.</p>
     *
     * @param anhaengen {@code true} für die Felder mit dem Schalter auf „hinzufügen", {@code false} für
     *                  die ausdrücklich ersetzten (das sind alle bestätigten <b>ohne</b> jene).
     */
    private Set<StatementTemplate.Field> lernfelder(Set<Field> quelle, boolean anhaengen) {
        Set<StatementTemplate.Field> out = EnumSet.noneOf(StatementTemplate.Field.class);
        for (Field f : quelle) {
            if (!entscheidungGilt(f) || (!anhaengen && anhaengenFelder.contains(f))) {
                continue;
            }
            StatementTemplate.Field lernfeld = lernfeldVon(f);
            if (lernfeld != null) {
                out.add(lernfeld);
            }
        }
        return out;
    }

    /**
     * Der Text der Rückfrage. Hat der Lerner eine <b>feste Ordergebühr</b> erschlossen, sagt er das mit
     * dem Betrag.
     *
     * <p>Das ist der einzige Wert, den die App nicht im Dokument gefunden, sondern aus einer Differenz
     * gefolgert hat: der Gesamtbetrag stand nicht darin, der Betrag ohne die Gebühr schon. Ein solcher
     * Wert darf nicht stillschweigend entstehen — wer später auf der Regelseite eine Gebühr vorfindet,
     * die er nie eingetragen hat, rätselt sonst, woher sie kommt.</p>
     */
    private String learnMessage(StatementTemplate raw, StatementTemplate existing) {
        boolean neu = raw.fixedFeeCents > 0
                && (existing == null || existing.fixedFeeCents != raw.fixedFeeCents);
        return neu
                ? getString(R.string.statement_learn_fixed_fee, MoneyFormat.plain(raw.fixedFeeCents))
                : getString(R.string.statement_learn_message);
    }

    /** Die gewählte Fassung merken – und sie sogleich an derselben Abrechnung nachprüfen. */
    private void keep(StatementTemplates store, StatementTemplate template,
                      de.spahr.ausgaben.pdf.PdfText text) {
        store.save(template, depot);
        // Auch die Zuordnung merken – dann findet die nächste Abrechnung das Wertpapier selbst dann,
        // wenn die ISIN in KMyMoney nicht gepflegt ist.
        store.rememberSecurity(statementIsin, depot, kmyId, securityName);
        Toast.makeText(this, R.string.statement_learned, Toast.LENGTH_SHORT).show();
        verifyLearned(template, text);
    }

    /**
     * Die Probe aufs Gemerkte: liest die eben gespeicherte Vorlage diese Abrechnung so, wie sie am Ende
     * in der Maske stand?
     *
     * <p>Bisher zeigte sich das erst bei der nächsten Abrechnung dieser Bank – Wochen später, vor einer
     * wieder leeren Maske und ohne Anhalt, woran es lag. Dabei liegt hier alles vor: die Regeln und das
     * Dokument. Stimmt es, bleibt die App still; sonst sagt sie, was nicht stimmt, und bietet den Weg
     * auf die Regelseite an – mit dieser Abrechnung schon als Probe.</p>
     */
    private void verifyLearned(StatementTemplate template, de.spahr.ausgaben.pdf.PdfText text) {
        java.util.List<TemplateCheck.Complaint> maengel =
                TemplateCheck.check(template, text, expectation(template.action));
        if (maengel.isEmpty()) {
            finish();
            return;
        }
        pruefErgebnis = new PruefErgebnis(template.action, maengel);
        HostedDialog.show(this, DLG_VERIFY, null);
    }

    /** Was die Nachprüfung ergeben hat – siehe {@link #buildDialog}. */
    private static final class PruefErgebnis {
        final String action;
        final java.util.List<TemplateCheck.Complaint> maengel;

        PruefErgebnis(String action, java.util.List<TemplateCheck.Complaint> maengel) {
            this.action = action;
            this.maengel = maengel;
        }
    }

    private android.app.Dialog buildVerifyDialog() {
        StringBuilder message = new StringBuilder(getString(R.string.statement_check_intro));
        for (TemplateCheck.Complaint c : pruefErgebnis.maengel) {
            message.append("\n\n").append(complaintLine(c, pruefErgebnis.action));
        }
        message.append("\n\n").append(getString(R.string.statement_check_hint));
        AppDialog dialog = new AppDialog(this);
        dialog.setTitle(R.string.statement_check_title);
        dialog.setMessage(message.toString());
        dialog.setPositiveButton(R.string.statement_check_rules, (d, w) -> openRules());
        dialog.setNegativeButton(android.R.string.ok, (d, w) -> finish());
        return dialog.create();
    }

    /** Was in der Maske stand – der Sollwert der Nachprüfung. */
    private TemplateCheck.Expected expectation(String action) {
        TemplateCheck.Expected soll = new TemplateCheck.Expected();
        soll.action = action;
        soll.dateMillis = selectedDate.getTimeInMillis();
        soll.shares = number(Field.SHARES);
        soll.price = number(Field.PRICE);
        soll.feeCents = money(Field.FEE);
        soll.netCents = money(Field.NET);
        soll.grossCents = money(Field.GROSS);
        soll.feeParts.addAll(partAmounts(feeSplits));
        if (DIVIDEND.equals(action)) {
            soll.incomeParts.addAll(partAmounts(incomeSplits));
        }
        for (Field field : typedFields) {
            soll.typed.add(StatementTemplate.Field.valueOf(field.name()));
        }
        if (dateTyped) {
            soll.typed.add(StatementTemplate.Field.DATE);
        }
        return soll;
    }

    /** „Gebühr: erwartet 9,90, gelesen 4,95" – Feld, Soll und Ist in einer Zeile. */
    private String complaintLine(TemplateCheck.Complaint c, String action) {
        String name = StatementFieldNames.of(this, c.field, action);
        String soll = valueText(c.field, c.expected);
        switch (c.kind) {
            case NO_RULE:
                return getString(R.string.statement_check_norule, name, soll);
            case NOT_FOUND:
                return getString(R.string.statement_check_missing, name, soll);
            case PARTS:
                return getString(R.string.statement_check_parts, name, soll,
                        valueText(c.field, c.actual));
            default:
                return getString(R.string.statement_check_wrong, name, soll,
                        valueText(c.field, c.actual));
        }
    }

    /** Ein Wert der Nachprüfung so geschrieben, wie ihn die Maske zeigt. */
    private String valueText(StatementTemplate.Field field, double value) {
        switch (field) {
            case DATE:
                return DateFormats.date((long) value);
            case SHARES:
                return MoneyFormat.shares(value);
            case PRICE:
                return MoneyFormat.decimal(value, 2, 4);
            default:
                return MoneyFormat.plain(Math.round(value * 100.0));
        }
    }

    /**
     * Auf die Regelseite – mit der Abrechnung als Probe.
     *
     * <p>Erst schließen, dann öffnen: die Buchung ist gespeichert, die Maske hat ihren Zweck erfüllt und
     * hat im Rückweg nichts mehr zu suchen.</p>
     */
    private void openRules() {
        android.content.Intent i = new android.content.Intent(this, StatementRulesActivity.class);
        i.putExtra(StatementRulesActivity.EXTRA_DEPOT, depot);
        java.io.File pdf = statementPdf();
        if (pdf != null) {
            try {
                i.setData(androidx.core.content.FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", pdf));
                i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
                // Ohne Probe ist die Seite immer noch zu gebrauchen – die Regeln stehen dort so oder so.
            }
        }
        // Erst starten, dann schließen: andersherum verschluckt Android die Übergangsanimation, und
        // für einen Moment steht der Startbildschirm dazwischen. Gerufen wird das aus einem
        // Dialog-Knopf heraus — die Maske kann inzwischen weg sein.
        if (isFinishing() || isDestroyed()) {
            return;
        }
        startActivity(i);
        finish();
    }

    /**
     * Die Abrechnung einlesen, aus der gelernt und an der nachgeprüft wird.
     *
     * <p>Zuerst aus dem <b>PDF selbst</b>. Der Zwischenspeicher trägt nur den Text, und der wird beim
     * Zurückbauen Wort für Wort mit einem Leerzeichen zusammengesetzt – die Wortpositionen sind dahin.
     * Genau daraus lebt aber die Spaltenregel: gelernt würde an einer Lage, die es im Dokument gar nicht
     * gibt, und beim nächsten Import läse dieselbe Regel etwas anderes. Der Text bleibt der Rückfall für
     * den Stapelweg, der nur ihn durchreicht.</p>
     *
     * @return {@code null}, wenn beides nicht mehr da ist
     */
    private de.spahr.ausgaben.pdf.PdfText readStatementText() {
        if (statementText != null) {
            return statementText;
        }
        statementText = liesAbrechnung();
        return statementText;
    }

    /** Das eigentliche Einlesen – siehe {@link #readStatementText()}. */
    private de.spahr.ausgaben.pdf.PdfText liesAbrechnung() {
        java.io.File pdf = statementPdf();
        if (pdf != null) {
            try {
                de.spahr.ausgaben.pdf.PdfText text = de.spahr.ausgaben.pdf.PdfTextExtractor.read(
                        this, android.net.Uri.fromFile(pdf));
                if (text.hasText()) {
                    return text;
                }
            } catch (Exception e) {
                // Dann eben aus dem Zwischenspeicher – besser als gar nicht zu lernen.
            }
        }
        if (statementTextPath == null) {
            return null;
        }
        try {
            byte[] raw = java.nio.file.Files.readAllBytes(
                    new java.io.File(statementTextPath).toPath());
            return de.spahr.ausgaben.pdf.PdfText.fromLines(
                    new String(raw, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Die Abrechnung als Beleg ----

    /**
     * Ein PDF im Betrachter des Geräts öffnen; {@code null} oder fehlend meldet sich als Fehler.
     *
     * <p>Beim Eintippen der Werte hat man die Abrechnung damit nebenher offen, und später ist sie der
     * Beleg zur Buchung. Aufgerufen wird sie über die Symbole der Belegzeile.</p>
     */
    private void oeffnePdf(java.io.File file) {
        if (file == null || !file.exists()) {
            Toast.makeText(this, R.string.receipt_error, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", file);
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/pdf")
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, R.string.receipt_pdf_no_viewer, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.receipt_error, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Die Abrechnung als Datei – die noch nicht abgelegte oder die schon abgelegte; {@code null}, wenn
     * es keine (mehr) gibt.
     */
    private java.io.File statementPdf() {
        if (pendingStatement != null && pendingStatement.exists()) {
            return pendingStatement;
        }
        if (savedStatementTag == null) {
            return null;
        }
        java.io.File file = statementFile(savedStatementTag, yearOf(selectedDate.getTimeInMillis()));
        return file != null && file.exists() ? file : null;
    }

    /**
     * Die gespeicherte Belegdatei zum Tag aus der Notiz; {@code null}, wenn sie sich nicht auftreiben
     * läßt.
     *
     * <p><b>Nicht auf dem Bedienfaden rufen:</b> Liegt die Datei nicht lokal, holt
     * {@code ReceiptPages.find} sie vom Netzlaufwerk.</p>
     */
    private java.io.File statementFile(String tag, int jahr) {
        java.util.List<String> pages = de.spahr.ausgaben.receipt.ReceiptPages.find(
                this, tag, jahr, de.spahr.ausgaben.receipt.NoteReceipt.PDF);
        if (pages.isEmpty()) {
            return null;
        }
        return de.spahr.ausgaben.receipt.Receipts.localFile(this, pages.get(0));
    }

    private static int yearOf(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return c.get(Calendar.YEAR);
    }

    /**
     * Die Belegzeile neu aufbauen. Hieß bis 1.13 {@code updateStatementButton} und schaltete einen
     * eigenen Knopf „Abrechnung anzeigen" sichtbar; seit die Zeile je Seite ein Symbol trägt, wäre der
     * Knopf ein zweiter Weg zum selben PDF.
     */
    private void updateStatementButton() {
        fuelleBelegzeile();
    }

    /**
     * Sucht die angehängte Abrechnung einer gespeicherten Bewegung. Sie hängt an der Gegenbuchung — die
     * Bewegung selbst führt keine Notiz, die Buchung schon, und damit greift die vorhandene Belegablage
     * samt Abgleich und Export.
     */
    private void loadSavedStatement(long bookingId) {
        // Seit 1.13 trägt die Bewegung ihre Notiz selbst (siehe SecurityTx#note) – nur so übersteht der
        // Beleg-Tag den Weg durch die KMyMoney-Datei. Sie hat deshalb Vorrang; die Gegenbuchung wird nur
        // noch für Bewegungen befragt, die vor der Umstellung entstanden sind.
        String note = loaded == null || loaded.note == null ? "" : loaded.note;
        if (!note.isEmpty()) {
            uebernehmeNotiz(note);
            return;
        }
        if (bookingId <= 0) {
            zeigeNotiz("");
            return;
        }
        repository.getBookingById(bookingId, b -> {
            if (b != null) {
                uebernehmeNotiz(b.note == null ? "" : b.note);
            }
        });
    }

    /** Beleg-Tag und Anzeige aus einer Notiz ableiten. */
    private void uebernehmeNotiz(String note) {
        savedStatementTag = de.spahr.ausgaben.receipt.NoteReceipt.pdfName(note);
        updateStatementButton();
        zeigeNotiz(note);
    }

    /**
     * Notiz und Belegzeile der gespeicherten Bewegung — beides nur zum Ansehen.
     *
     * <p>Der Beleg-Tag steht in der Notiz und wäre dort als {@code BELEG (PDF): …} nur Rauschen; gezeigt
     * wird deshalb der Text ohne ihn, und der Beleg bekommt seine eigene Zeile. Bleibt nach dem Entfernen
     * nichts übrig, entfällt die Notizzeile ganz.</p>
     */
    private void zeigeNotiz(String note) {
        String sichtbar = de.spahr.ausgaben.receipt.NoteReceipt.strip(note).trim();
        textNote.setText(sichtbar);
        textNote.setVisibility(sichtbar.isEmpty() ? View.GONE : View.VISIBLE);
        fuelleBelegzeile();
    }

    /**
     * Je Belegseite ein Symbol, wie im Konto. Ein PDF öffnet der Betrachter des Geräts — genauso hält es
     * die Buchungsmaske ({@code openPdf}), ein eingebauter Betrachter zeigt dort nur Bilder.
     *
     * <p>Gezeigt wird auch die noch <b>nicht abgelegte</b> Abrechnung: Während der Eingabe will man sie
     * nebenher ansehen, und abgelegt wird sie erst beim Speichern (siehe {@code SingleReceipt.plan}).</p>
     */
    private void fuelleBelegzeile() {
        receiptPageIcons.removeAllViews();
        // Nur der Verweis entscheidet, nicht die Frage, wo die Datei gerade liegt: Ob sie lokal
        // vorliegt oder erst vom Netzlaufwerk zu holen ist, darf man der Zeile nicht ansehen. Und
        // nachsehen dürfte man hier ohnehin nicht — ReceiptPages.find lädt bei Bedarf selbst nach,
        // und das ist Netzverkehr auf dem Bedienfaden.
        boolean vorhanden = pendingStatement != null || savedStatementTag != null;
        if (!vorhanden) {
            rowReceipt.setVisibility(View.GONE);
            return;
        }
        rowReceipt.setVisibility(View.VISIBLE);
        textReceipt.setText(getString(R.string.receipt_pdf_label, 1));
        android.widget.ImageButton icon = new android.widget.ImageButton(this);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        icon.setImageResource(R.drawable.ic_pdf);
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(androidx.appcompat.R.attr.selectableItemBackgroundBorderless,
                tv, true);
        icon.setBackgroundResource(tv.resourceId);
        icon.setContentDescription(getString(R.string.statement_show));
        icon.setOnClickListener(v -> oeffneAbrechnung());
        receiptPageIcons.addView(icon);
    }

    /**
     * Die Abrechnung öffnen — und sie dafür notfalls erst holen.
     *
     * <p>Eine Abrechnung ist genau <b>ein</b> PDF ({@code SingleReceipt}), deshalb genügt ein Symbol.
     * Liegt die Datei nicht lokal, holt {@code ReceiptPages.find} sie vom Netzlaufwerk; das darf nicht
     * auf dem Bedienfaden geschehen und wird deshalb im Hintergrund erledigt. Mißlingt es, sagt es die
     * Meldung — angezeigt wird das Symbol trotzdem, denn ob die Datei erreichbar ist, weiß man vorher
     * nicht.</p>
     */
    private void oeffneAbrechnung() {
        if (pendingStatement != null && pendingStatement.exists()) {
            oeffnePdf(pendingStatement);
            return;
        }
        final int jahr = yearOf(selectedDate.getTimeInMillis());
        final String tag = savedStatementTag;
        // Das Holen vom Netzlaufwerk kann dauern – dieselbe gelbe Statuszeile wie im Konto.
        receiptBanner.start(getString(R.string.receipt_loading_wait));
        repository.executor().execute(() -> {
            final java.io.File datei = statementFile(tag, jahr);
            final boolean offline = datei == null
                    && !de.spahr.ausgaben.net.Net.isOnline(getApplicationContext());
            post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                receiptBanner.finishNow();
                if (datei == null) {
                    // Ohne Verbindung ist der Beleg nur gerade nicht da; online heißt es, er fehlt.
                    Toast.makeText(this, offline ? R.string.receipt_offline : R.string.receipt_error,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                oeffnePdf(datei);
            });
        });
    }

    private int dp(int wert) {
        return Math.round(wert * getResources().getDisplayMetrics().density);
    }

    /**
     * Die Geldbuchung zur Bewegung: eine Umbuchung zwischen dem Geldkonto und dem Wertpapier – genau die
     * Form, die auch der KMyMoney-Import erzeugt. Beim Kauf verlässt das Geld das Konto, bei Verkauf und
     * Dividende kommt es an (bei der Dividende der <b>Nettobetrag</b>, denn nur der wird gutgeschrieben).
     */
    /** Die Geldbuchung zur Bewegung – gebaut aus ihr selbst, siehe {@link SecurityTx#toMoneyBooking}. */
    private Booking buildBooking(SecurityTx tx, long moneyCents) {
        return tx.toMoneyBooking(moneyCents);
    }

    private void confirmDelete() {
        if (loaded == null) {
            return;
        }
        AppDialog.destructive(this)
                .setTitle(R.string.security_tx_delete_title)
                .setMessage(R.string.security_tx_delete_message)
                .setPositiveButton(R.string.security_tx_delete, (d, w) ->
                        repository.deleteManualSecurityTx(loaded.id, () -> {
                            Toast.makeText(this, R.string.security_tx_deleted, Toast.LENGTH_SHORT).show();
                            finish();
                        }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---- Kleinkram ----

    /**
     * Tipp aufs Datumsfeld. Kam die Maske aus einer Abrechnung, in der die App das gebuchte Datum noch
     * nicht kennt, legt sie erst die im Dokument gefundenen zur Auswahl vor — eine Abrechnung trägt
     * mehrere (Briefdatum, Ex-Tag, Zahltag, Valuta), und welches gemeint ist, wäre geraten. Die Wahl
     * bringt der Vorlage beim Speichern den Anker bei, ab dann kommt das Datum von selbst.
     */
    private void showDatePicker() {
        if (statementTextPath == null || readOnly) {
            showCalendar();
            return;
        }
        if (statementDates != null) {
            // Schon gelesen und ausgewertet – dann sofort, ohne den Umweg über den Hintergrund und ohne
            // Zucken. Die reine PdfText-Prüfung reichte hier nicht: das Absuchen nach Datumskandidaten
            // (StatementScan.dates) kostet selbst noch einmal Zeit und lief bislang bei jedem Antippen neu.
            showDateChoice(statementDates);
            return;
        }
        // Das Einlesen holt das PDF von der Platte und schickt es durch pdfbox, und die Kandidatensuche
        // durchsucht danach den ganzen Text noch einmal; bei einer mehrseitigen Abrechnung dauert das
        // zusammen lange genug, um das Antippen des Datumsfeldes einfrieren zu lassen. Der Hinweis sagt in
        // der Zwischenzeit, was passiert – und macht zugleich sichtbar, dass hier noch gelesen/gelernt
        // wird, nicht schon aus einer feststehenden Vorlage erkannt.
        dateLayout.setError(getString(R.string.statement_learning_preparing, getString(R.string.date_hint)));
        repository.executor().execute(() -> {
            final java.util.List<de.spahr.ausgaben.statement.StatementScan.DateCandidate> found =
                    readStatementDates();
            post(() -> {
                dateLayout.setError(null);
                // Wer inzwischen weggegangen ist, bekommt keinen Dialog mehr.
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                showDateChoice(found);
            });
        });
    }

    /** Der zweite Teil von {@link #showDatePicker()}: die Datumsangaben liegen vor. */
    private void showDateChoice(
            java.util.List<de.spahr.ausgaben.statement.StatementScan.DateCandidate> found) {
        if (found.isEmpty()) {
            showCalendar();
            return;
        }
        CharSequence[] labels = new CharSequence[found.size() + 1];
        for (int i = 0; i < found.size(); i++) {
            // Manche Beschriftungen tragen den Doppelpunkt schon („Datum:"), dann keinen zweiten.
            String label = found.get(i).label.trim();
            if (label.endsWith(":")) {
                label = label.substring(0, label.length() - 1).trim();
            }
            labels[i] = label + ":  " + DateFormats.date(found.get(i).millis);
        }
        labels[found.size()] = getString(R.string.statement_date_other);
        // Die Auswahl kommt als HostedDialog: Sie ist aus diesen Angaben jederzeit neu zu bauen, also
        // wandern sie ins Bundle und überstehen damit eine Drehung.
        Bundle args = new Bundle();
        args.putCharSequenceArray(ARG_DATE_LABELS, labels);
        long[] millis = new long[found.size()];
        java.util.ArrayList<de.spahr.ausgaben.statement.AnchorRule> rules = new java.util.ArrayList<>();
        String[] anker = new String[found.size()];
        for (int i = 0; i < found.size(); i++) {
            millis[i] = found.get(i).millis;
            anker[i] = found.get(i).label;
            rules.add(found.get(i).rule);
        }
        args.putLongArray(ARG_DATE_MILLIS, millis);
        args.putStringArray(ARG_DATE_ANCHORS, anker);
        args.putSerializable(ARG_DATE_RULES, rules);
        HostedDialog.show(this, DLG_DATE_CHOICE, args);
    }

    @SuppressWarnings("unchecked")
    private android.app.Dialog buildDateChoiceDialog(Bundle args) {
        CharSequence[] labels = args.getCharSequenceArray(ARG_DATE_LABELS);
        long[] millis = args.getLongArray(ARG_DATE_MILLIS);
        String[] anker = args.getStringArray(ARG_DATE_ANCHORS);
        java.util.List<de.spahr.ausgaben.statement.AnchorRule> rules =
                (java.util.List<de.spahr.ausgaben.statement.AnchorRule>)
                        args.getSerializable(ARG_DATE_RULES);
        final int anzahl = millis == null ? 0 : millis.length;
        return new AppDialog(this)
                .setTitle(R.string.statement_date_title)
                .setItems(labels, (d, which) -> {
                    if (which >= anzahl) {
                        showCalendar();
                        return;
                    }
                    selectedDate.setTimeInMillis(millis[which]);
                    chosenDateLabel = anker[which];
                    chosenDateRule = rules == null ? null : rules.get(which);
                    dateTyped = true;
                    updateDateField();
                })
                .create();
    }

    /** Die Datumsangaben einer eingelesenen Abrechnung — leer, wenn keine da ist. */
    private static java.util.List<de.spahr.ausgaben.statement.StatementScan.DateCandidate> datesOf(
            de.spahr.ausgaben.pdf.PdfText text) {
        return text == null ? java.util.Collections.emptyList()
                : de.spahr.ausgaben.statement.StatementScan.dates(text);
    }

    /**
     * Wie {@link #readStatementText()}, nur eine Ebene weiter: das Ergebnis von {@link #datesOf} gecacht,
     * nicht nur der rohe Text. Aufruf nur vom Hintergrundfaden aus (siehe {@link #showDatePicker()}).
     */
    private java.util.List<de.spahr.ausgaben.statement.StatementScan.DateCandidate> readStatementDates() {
        if (statementDates != null) {
            return statementDates;
        }
        statementDates = datesOf(readStatementText());
        return statementDates;
    }

    // ---- Die Beschriftung zu einem Wert ----

    /**
     * Sucht — mit kurzer Wartezeit während des Tippens (siehe {@link #planeAnkerSuche}), sofort beim
     * Verlassen des Feldes ({@link #ankerSucheSofort}) — nach, woran der gerade eingegebene Wert in der
     * Abrechnung hängt.
     *
     * <p>Der Lerner müsste sonst raten, und an einer Tabellenzeile ist das nicht zu entscheiden:
     * „STK 86 … EUR 116,20" unter der Überschrift „Nominale … Kurs" gibt für den Kurs drei
     * Beschriftungen her, von denen beim nächsten Fonds nur noch eine trifft. Für das Datum fragt die
     * Maske längst so ({@link #showDatePicker()}).</p>
     *
     * <p>Gefragt wird nur beim <b>ersten</b> Beleg einer Bank. Steht die Vorlage, hat sie die Antwort
     * schon; und bei einer fest programmierten Bank gibt es gar keine Vorlage zu lernen. Ist die Lage
     * eindeutig, wird die eine Möglichkeit wortlos genommen — und steht der Wert überhaupt nicht im
     * Dokument (die gerechnete Gutschrift, eine feste Ordergebühr), gibt es nichts zu fragen: der Titel
     * bleibt beim Standardnamen, den der Feldwechsel oben schon wiederhergestellt hat.</p>
     */
    private void ankerAuswahlAnbieten(final Field field) {
        final StatementTemplate.Field lernfeld = lernfeldVon(field);
        if (readOnly || !fromStatement || lernfeld == null || !ankerSuchbar(field)) {
            return;
        }
        final Double wert = wertVon(field);
        if (wert == null || wert == 0) {
            return;
        }
        final TextInputLayout layout = layoutFor(field);
        repository.executor().execute(() -> {
            final de.spahr.ausgaben.pdf.PdfText text = readStatementText();
            final java.util.List<de.spahr.ausgaben.statement.AnchorRule> kandidaten = ankerSucheMoeglich(text)
                    ? de.spahr.ausgaben.statement.TemplateLearner.kandidaten(text, lernfeld, wert)
                    : java.util.Collections.emptyList();
            // Kennt die Bank-Vorlage für dieses Feld schon eine ANDERE Regel, ist das ein Widerspruch:
            // die Korrektur gilt dann erst als bestätigt (und damit beim Speichern lernbar), wenn der
            // Nutzer das Stift-Symbol antippt – siehe die Weiche unten und buildAnchorChoiceDialog.
            final de.spahr.ausgaben.statement.AnchorRule alt = kandidaten.isEmpty() ? null
                    : (matchedTemplate(text) == null ? null : matchedTemplate(text).rule(lernfeld));
            post(() -> {
                if (isFinishing() || isDestroyed() || kandidaten.isEmpty()) {
                    return;
                }
                // Der erste Kandidat ist in derselben Reihenfolge gesucht wie der automatische Lerner
                // selbst sucht (siehe TemplateLearner.kandidaten). Der Feldtitel zeigt ihn sofort, egal
                // ob es ein Widerspruch ist – nur ob er auch gelernt wird, hängt von ersetzteRegeln ab.
                de.spahr.ausgaben.statement.AnchorRule top = kandidaten.get(0);
                boolean widerspruch = alt != null && !alt.equals(top);
                // Der Widerspruch wird auch dann festgehalten, wenn gleich abgebrochen wird: sonst stünde
                // beim erneuten Öffnen des Stift-Fensters der Ersetzen/Hinzufügen-Schalter nicht mehr da.
                if (widerspruch) {
                    konfliktFelder.add(field);
                } else {
                    konfliktFelder.remove(field);
                }
                if (entscheidungGilt(field)) {
                    // Der Nutzer hat für genau diesen Wert schon selbst entschieden. Diese Suche läuft
                    // beim Verlassen des Feldes noch einmal und ersetzte seine Wahl sonst
                    // stillschweigend durch ihren eigenen ersten Vorschlag.
                    return;
                }
                if (widerspruch) {
                    ersetzteRegeln.remove(field);
                } else {
                    ersetzteRegeln.add(field);
                }
                chosenValueRules.put(field, top);
                zeigeErkannteRegel(field, layout, top, kandidaten);
            });
        });
    }

    /**
     * Feldtitel durch die im PDF gefundene (oder gewählte) Beschriftung ersetzen, Stift-Symbol daneben,
     * mit dem sich unter allen gefundenen Kandidaten eine andere wählen lässt.
     */
    private void zeigeErkannteRegel(Field field, TextInputLayout layout,
                                     de.spahr.ausgaben.statement.AnchorRule regel,
                                     java.util.List<de.spahr.ausgaben.statement.AnchorRule> kandidaten) {
        if (layout == null) {
            return;
        }
        layout.setHint(anchorText(regel));
        layout.setEndIconMode(TextInputLayout.END_ICON_CUSTOM);
        layout.setEndIconDrawable(R.drawable.ic_rules);
        layout.setEndIconTintList(android.content.res.ColorStateList.valueOf(colorPrimary()));
        layout.setEndIconContentDescription(getString(R.string.statement_anchor_change));
        layout.setEndIconOnClickListener(v -> showAnchorChoice(field, kandidaten));
    }

    /** Auffälliger Ton fürs Stift-Symbol der Anker-Auswahl, dem Tages-/Nachtdesign entsprechend. */
    private int colorPrimary() {
        android.util.TypedValue tv = new android.util.TypedValue();
        // Aus androidx.appcompat, nicht aus Material: Material fuehrt colorPrimary seit 1.14 nicht
        // mehr im eigenen R.attr. Das Attribut ist dasselbe, nur der Ort ein anderer.
        getTheme().resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tv, true);
        return getColor(tv.resourceId);
    }

    /**
     * Dieselbe Logik wie {@link #ankerAuswahlAnbieten} — nur für eine Kategoriezeile einer Aufteilung
     * (Gebühr/Steuer oder Ertrag) statt für ein Hauptfeld. Ein Teilbetrag kennt kein {@link Field} und
     * keine eigene Vorlagen-Spalte; gesucht wird darum mit {@link TemplateLearner#kandidatenFuerBetrag},
     * derselben Suche, die auch {@code TemplateLearner.learnParts} beim Speichern selbst anstellt.
     */
    private void ankerAuswahlAnbietenSplit(final TextInputLayout layout, final TextInputEditText input) {
        if (readOnly || !fromStatement || !Boolean.TRUE.equals(input.getTag(R.id.splitAmountTyped))) {
            return;
        }
        final Long cents = SplitRowController.parseCents(textOf(input));
        if (cents == null || cents == 0) {
            return;
        }
        final Double wert = Math.abs(cents) / 100.0;
        final String kategorie = kategorieVon(layout);
        repository.executor().execute(() -> {
            final de.spahr.ausgaben.pdf.PdfText text = readStatementText();
            final java.util.List<de.spahr.ausgaben.statement.AnchorRule> kandidaten = ankerSucheMoeglich(text)
                    ? de.spahr.ausgaben.statement.TemplateLearner.kandidatenFuerBetrag(text, wert)
                    : java.util.Collections.emptyList();
            // Was für diese Zeile schon in der Vorlage steht — für „Nicht lernen" im Stift-Fenster.
            final de.spahr.ausgaben.statement.AnchorRule alt =
                    alteTeilregel(matchedTemplate(text), layout, kategorie);
            post(() -> {
                if (isFinishing() || isDestroyed() || kandidaten.isEmpty()) {
                    return;
                }
                input.setTag(R.id.splitAltRule, alt);
                if (cents.equals(input.getTag(R.id.splitEntschiedenFuer))) {
                    // Für genau diesen Betrag hat der Nutzer im Stift-Fenster schon entschieden; diese
                    // Suche läuft beim Verlassen des Feldes noch einmal und ersetzte seine Wahl sonst
                    // durch ihren eigenen ersten Vorschlag. Wie bei den Hauptfeldern, siehe
                    // entscheidungGilt.
                    return;
                }
                zeigeErkannteSplitRegel(layout, input, kandidaten.get(0), kandidaten);
            });
        });
    }

    /** Die Kategorie, die in dieser Teilbetrag-Zeile steht — leer, solange keine gewählt ist. */
    private static String kategorieVon(TextInputLayout layout) {
        View zeile = layout.getParent() instanceof View ? (View) layout.getParent() : null;
        android.widget.TextView cat = zeile == null ? null : zeile.findViewById(R.id.splitCategory);
        return cat == null ? "" : cat.getText().toString().trim();
    }

    /**
     * Die schon gelernte Regel dieser Teilbetrag-Zeile, gefunden über ihre <b>Kategorie</b>.
     *
     * <p>Anders als ein Hauptfeld hat ein Teilbetrag keine feste Spalte in der Vorlage: dort stehen die
     * Zeilen unter ihrer Beschriftung, und die entsteht erst beim Lernen aus der gefundenen Zeile (siehe
     * {@code TemplateLearner.learnParts}). Was beide Seiten kennen, ist die Kategorie — sie gehört zur
     * Bank und wird mitgelernt ({@link StatementTemplate.PartRule#category}).</p>
     */
    private static de.spahr.ausgaben.statement.AnchorRule alteTeilregel(StatementTemplate vorlage,
                                                                       TextInputLayout layout,
                                                                       String kategorie) {
        if (vorlage == null || kategorie.isEmpty()) {
            return null;
        }
        boolean ertrag = istErtragsZeile(layout);
        for (StatementTemplate.PartRule teil : ertrag ? vorlage.incomeParts : vorlage.feeParts) {
            if (kategorie.equalsIgnoreCase(teil.category)) {
                return teil.rule;
            }
        }
        return null;
    }

    /** Ob diese Zeile zur Ertragsaufteilung gehört (sonst zur Gebühren-/Steueraufteilung). */
    private static boolean istErtragsZeile(TextInputLayout layout) {
        for (android.view.ViewParent p = layout.getParent(); p instanceof View; p = p.getParent()) {
            int id = ((View) p).getId();
            if (id == R.id.incomeSplitContainer) {
                return true;
            }
            if (id == R.id.feeSplitContainer) {
                return false;
            }
        }
        return false;
    }

    /**
     * Ob für dieses Feld überhaupt nach einer Beschriftung gesucht werden darf.
     *
     * <p>Selbst getippt: immer. Dazu ein von der Maske <b>gerechnetes</b> Feld — bei einer Dividende
     * sind Brutto und Netto genau das (getippt werden zwei der drei Zahlen, die dritte fällt heraus),
     * und beide stehen trotzdem schwarz auf weiß in der Abrechnung. Ohne diesen Zusatz blieben sie
     * stumm, obwohl der Gesamtbetrag beim ersten Beleg ohnehin gelernt wird — der Nutzer bekäme also
     * keine Wahl über etwas, das ihn betrifft.</p>
     *
     * <p>Was die Vorlage selbst vorgelegt hat, bleibt dagegen außen vor: dort fände die Suche nur den
     * eigenen Vorschlag wieder und hielte ihn für eine Bestätigung. Vorbelegte Werte stehen in
     * {@code userSet} (siehe {@code prefillMoney}), gerechnete nimmt {@link #recompute} dort heraus.</p>
     *
     * <p>Und ein ausgeblendetes Feld gar nicht: bei Kauf und Verkauf ist das Brutto die gerechnete
     * dritte Zahl und steht nicht in der Maske — eine Regel dafür füllte nur etwas Unsichtbares.</p>
     */
    private boolean ankerSuchbar(Field field) {
        TextInputLayout layout = layoutFor(field);
        if (layout == null || layout.getVisibility() != View.VISIBLE) {
            return false;
        }
        return typedFields.contains(field) || !userSet.contains(field);
    }

    /** Wie {@link #zeigeErkannteRegel}, aber der Merkposten geht ins Tag der Zeile statt in {@code chosenValueRules}. */
    private void zeigeErkannteSplitRegel(TextInputLayout layout, TextInputEditText input,
                                         de.spahr.ausgaben.statement.AnchorRule regel,
                                         java.util.List<de.spahr.ausgaben.statement.AnchorRule> kandidaten) {
        layout.setHint(anchorText(regel));
        input.setTag(R.id.splitAnchorRule, regel);
        layout.setEndIconMode(TextInputLayout.END_ICON_CUSTOM);
        layout.setEndIconDrawable(R.drawable.ic_rules);
        layout.setEndIconTintList(android.content.res.ColorStateList.valueOf(colorPrimary()));
        layout.setEndIconContentDescription(getString(R.string.statement_anchor_change));
        layout.setEndIconOnClickListener(v -> showSplitAnchorChoice(layout, input, kandidaten));
    }

    /**
     * Die Auswahl für eine Kategoriezeile — anders als {@link #showAnchorChoice} kein {@link HostedDialog}:
     * die Zeile ist eine zur Laufzeit erzeugte Ansicht ohne über eine Drehung hinweg stabile Kennung, ein
     * Wiederaufbau lohnte den Aufwand nicht. Übersteht die Drehung nicht — einfach erneut antippen.
     */
    private void showSplitAnchorChoice(TextInputLayout layout, final TextInputEditText input,
                                       java.util.List<de.spahr.ausgaben.statement.AnchorRule> kandidaten) {
        View view = getLayoutInflater().inflate(R.layout.dialog_anchor_choice, null);
        final android.widget.RadioGroup gruppe = view.findViewById(R.id.anchorChoices);
        for (int i = 0; i <= kandidaten.size(); i++) {
            com.google.android.material.radiobutton.MaterialRadioButton knopf =
                    new com.google.android.material.radiobutton.MaterialRadioButton(this);
            knopf.setId(i + 1);
            knopf.setText(i < kandidaten.size() ? anchorText(kandidaten.get(i))
                    : getString(R.string.statement_anchor_none));
            gruppe.addView(knopf);
        }
        Object gilt = input.getTag(R.id.splitAnchorRule);
        int vorwahl = gilt instanceof de.spahr.ausgaben.statement.AnchorRule
                ? kandidaten.indexOf(gilt) : -1;
        gruppe.check((vorwahl < 0 ? 0 : vorwahl) + 1);
        // Der Ersetzen/Hinzufügen-Schalter der Hauptfelder bleibt hier weg: eine Teilbetrag-Regel wird
        // über ihre Beschriftung wiedergefunden, und eine Kette aus alter und neuer Beschriftung stünde
        // in der Vorlage als zwei Zeilen — die Bank zählte dieselbe Steuer beim nächsten Beleg doppelt.
        new AppDialog(this)
                .setTitle(getString(R.string.statement_anchor_title, getString(R.string.split_partial_hint)))
                .setView(view)
                .setPositiveButton(R.string.statement_anchor_learn, (d, w) -> {
                    input.setTag(R.id.splitEntschiedenFuer, SplitRowController.parseCents(textOf(input)));
                    int gewaehlt = gewaehlterIndex(gruppe);
                    if (gewaehlt < 0 || gewaehlt >= kandidaten.size()) {
                        // „Die App entscheiden lassen": der Lerner sucht beim Speichern selbst
                        // (siehe learnParts).
                        input.setTag(R.id.splitAnchorRule, null);
                        layout.setHint(getString(R.string.split_partial_hint));
                        return;
                    }
                    zeigeErkannteSplitRegel(layout, input, kandidaten.get(gewaehlt), kandidaten);
                })
                .setNegativeButton(R.string.statement_anchor_dont_learn, (d, w) -> {
                    input.setTag(R.id.splitEntschiedenFuer, SplitRowController.parseCents(textOf(input)));
                    // „Nicht lernen": die für diese Kategorie gespeicherte Regel bleibt stehen — sie geht
                    // als Wahl in den Lernvorgang, kommt also unverändert wieder heraus. Kennt die Vorlage
                    // für die Zeile noch gar keine, gibt es nichts zu bewahren, und der Lerner sucht wie
                    // bisher selbst.
                    Object alt = input.getTag(R.id.splitAltRule);
                    if (alt instanceof de.spahr.ausgaben.statement.AnchorRule) {
                        zeigeErkannteSplitRegel(layout, input,
                                (de.spahr.ausgaben.statement.AnchorRule) alt, kandidaten);
                    } else {
                        input.setTag(R.id.splitAnchorRule, null);
                        layout.setHint(getString(R.string.split_partial_hint));
                    }
                })
                .create()
                .show();
    }

    /** Die Eingabehülle eines Zahlenfelds – für den „wird gelernt"-Hinweis in {@link #ankerAuswahlAnbieten}. */
    private TextInputLayout layoutFor(Field field) {
        switch (field) {
            case GROSS:
                return grossLayout;
            case FEE:
                return feeLayout;
            case NET:
                return netLayout;
            case SHARES:
                return sharesLayout;
            case PRICE:
                return priceLayout;
            default:
                return null;
        }
    }

    /**
     * Ob für diesen Beleg überhaupt zu fragen ist — einmal ermittelt, dann gemerkt.
     *
     * <p>Läuft auf dem Hintergrundfaden: {@code match} geht durch alle gespeicherten Vorlagen und
     * lässt jede den Text bewerten.</p>
     */
    private boolean ohneVorlage(de.spahr.ausgaben.pdf.PdfText text) {
        if (text == null) {
            return false;
        }
        Boolean bekannt = ankerAuswahlMoeglich;
        if (bekannt != null) {
            return bekannt;
        }
        boolean moeglich = de.spahr.ausgaben.statement.bank.BankReaders.find(text) == null
                && matchedTemplate(text) == null;
        ankerAuswahlMoeglich = moeglich;
        return moeglich;
    }

    /**
     * Die zu diesem Beleg passende, schon gespeicherte Vorlage — {@code null}, wenn keine passt. Einmal
     * ermittelt, dann gemerkt (der Abgleich geht durch alle gespeicherten Vorlagen); gebraucht sowohl beim
     * Vorschlagen während der Eingabe ({@link #ankerAuswahlAnbieten}, um einen Widerspruch zur Vorlage zu
     * erkennen) als auch beim Speichern ({@link #learnFrom}).
     */
    private StatementTemplate matchedTemplate(de.spahr.ausgaben.pdf.PdfText text) {
        if (matchedTemplateComputed) {
            return matchedTemplate;
        }
        if (text == null) {
            return null;
        }
        matchedTemplate = new de.spahr.ausgaben.settings.StatementTemplates(this).match(text, depot);
        matchedTemplateComputed = true;
        return matchedTemplate;
    }

    /**
     * Ob sich für diesen Beleg überhaupt eine Beschriftung suchen lässt. Anders als {@link #ohneVorlage}:
     * gilt auch, wenn für die Bank schon eine Vorlage besteht — genau dann hilft die Suche beim
     * Korrigieren eines falsch (oder gar nicht) erkannten Wertes, siehe {@link #ankerAuswahlAnbieten}.
     * Nur eine fest programmierte Bank ({@code BankReaders}) hat kein lernbares Format und bleibt darum
     * außen vor.
     */
    private boolean ankerSucheMoeglich(de.spahr.ausgaben.pdf.PdfText text) {
        if (text == null) {
            return false;
        }
        Boolean b = ankerSucheMoeglichCache;
        if (b != null) {
            return b;
        }
        boolean moeglich = de.spahr.ausgaben.statement.bank.BankReaders.find(text) == null;
        ankerSucheMoeglichCache = moeglich;
        return moeglich;
    }

    /** Das Feld der Vorlage zu einem Feld der Maske; {@code null} für die, aus denen nicht gelernt wird. */
    private static StatementTemplate.Field lernfeldVon(Field field) {
        switch (field) {
            case SHARES:
                return StatementTemplate.Field.SHARES;
            case PRICE:
                return StatementTemplate.Field.PRICE;
            case FEE:
                return StatementTemplate.Field.FEE;
            case NET:
                return StatementTemplate.Field.NET;
            case GROSS:
                // Bei einer Dividende steht das Brutto als eigene Zeile in der Abrechnung und ist damit
                // so suchbar wie jede andere Zahl. Bei Kauf und Verkauf ist das Feld ausgeblendet (es
                // wird aus Anzahl × Kurs gerechnet) – dort kommt die Suche gar nicht erst dazu, siehe
                // ankerSuchbar(). Von selbst lernt der Lerner dafür weiterhin nichts, nur die
                // ausdrückliche Wahl des Nutzers (siehe TemplateLearner.Known#grossCents).
                return StatementTemplate.Field.GROSS;
            default:
                return null;
        }
    }

    /** Der Wert eines Feldes als Zahl: Geld in Einheiten, keine Cent — so sucht ihn der Lerner. */
    private Double wertVon(Field field) {
        if (field == Field.SHARES || field == Field.PRICE) {
            return number(field);
        }
        Long cents = money(field);
        return cents == null ? null : cents / 100.0;
    }

    /**
     * Welcher Knopf der Auswahl gerade steht, als Index in die Kandidatenliste — {@code -1}, wenn
     * keiner steht.
     *
     * <p>Die Knöpfe entstehen zur Laufzeit und bekommen künstliche Kennungen {@code 1..n}: Eine
     * {@link android.widget.RadioGroup} deutet die {@code 0} als „nichts gewählt", also kann der Index
     * dort nicht unverschoben stehen. Bis 2.1 wurde der Rückweg als {@code getCheckedRadioButtonId() - 1}
     * gerechnet. Das lief richtig, band aber die Auswertung an die Kennungen aus der Schleife — wer dort
     * den Zähler anfaßte, brach sie still. Über die Kindposition gefragt, steht dieselbe Antwort ohne
     * diese Kopplung da, und sie ist genau das, was gemeint ist.</p>
     */
    static int gewaehlterIndex(android.widget.RadioGroup gruppe) {
        // findViewById gibt bei NO_ID (-1, also „nichts gewählt") null, indexOfChild dafür -1.
        return gruppe.indexOfChild(gruppe.findViewById(gruppe.getCheckedRadioButtonId()));
    }

    /** Die Auswahl — als {@link HostedDialog}, damit sie eine Drehung übersteht. */
    private void showAnchorChoice(Field field,
                                  java.util.List<de.spahr.ausgaben.statement.AnchorRule> kandidaten) {
        CharSequence[] labels = new CharSequence[kandidaten.size() + 1];
        for (int i = 0; i < kandidaten.size(); i++) {
            labels[i] = anchorText(kandidaten.get(i));
        }
        labels[kandidaten.size()] = getString(R.string.statement_anchor_none);
        Bundle args = new Bundle();
        args.putString(ARG_ANCHOR_FIELD, field.name());
        args.putCharSequenceArray(ARG_ANCHOR_LABELS, labels);
        args.putSerializable(ARG_ANCHOR_RULES,
                new java.util.ArrayList<>(kandidaten));
        HostedDialog.show(this, DLG_ANCHOR_CHOICE, args);
    }

    @SuppressWarnings("unchecked")
    private android.app.Dialog buildAnchorChoiceDialog(Bundle args) {
        final Field field = fieldOf(args.getString(ARG_ANCHOR_FIELD));
        CharSequence[] labels = args.getCharSequenceArray(ARG_ANCHOR_LABELS);
        final java.util.List<de.spahr.ausgaben.statement.AnchorRule> rules =
                (java.util.List<de.spahr.ausgaben.statement.AnchorRule>)
                        args.getSerializable(ARG_ANCHOR_RULES);
        if (field == null || labels == null || rules == null) {
            return null;
        }
        StatementTemplate.Field lernfeld = lernfeldVon(field);
        // Stand für dieses Feld schon eine andere Regel in der Bank-Vorlage (siehe konfliktFelder), fällt
        // hier – genau dort, wo die Beschriftung gewählt wird – auch die Entscheidung, was mit der alten
        // Regel geschieht: der Schalter wählt zwischen ersetzen und hinzufügen, „Nicht lernen" lässt sie
        // ganz in Ruhe. Ohne Widerspruch gibt es nichts zu entscheiden, dann bleibt der Schalter weg.
        final boolean konflikt = konfliktFelder.contains(field);
        View view = getLayoutInflater().inflate(R.layout.dialog_anchor_choice, null);
        final android.widget.RadioGroup gruppe = view.findViewById(R.id.anchorChoices);
        for (int i = 0; i < labels.length; i++) {
            com.google.android.material.radiobutton.MaterialRadioButton knopf =
                    new com.google.android.material.radiobutton.MaterialRadioButton(this);
            knopf.setId(i + 1);
            knopf.setText(labels[i]);
            gruppe.addView(knopf);
        }
        // Vorausgewählt ist, was schon gilt – so steht nach einer Drehung wieder dasselbe da, ohne dass
        // die Auswahl eigens durchs Bundle müsste.
        int vorwahl = rules.indexOf(chosenValueRules.get(field));
        gruppe.check((vorwahl < 0 ? 0 : vorwahl) + 1);
        final com.google.android.material.materialswitch.MaterialSwitch schalter =
                view.findViewById(R.id.anchorReplace);
        if (konflikt) {
            view.findViewById(R.id.anchorReplaceRow).setVisibility(View.VISIBLE);
            schalter.setChecked(!anhaengenFelder.contains(field));
            schalter.setText(schalter.isChecked()
                    ? R.string.statement_anchor_replace_on : R.string.statement_anchor_replace_off);
            schalter.setOnCheckedChangeListener((b, an) -> schalter.setText(an
                    ? R.string.statement_anchor_replace_on : R.string.statement_anchor_replace_off));
        }
        return new AppDialog(this)
                .setTitle(getString(R.string.statement_anchor_title,
                        StatementFieldNames.of(this, lernfeld, currentAction())))
                .setView(view)
                .setPositiveButton(R.string.statement_anchor_learn, (d, w) -> {
                    TextInputLayout layout = layoutFor(field);
                    int gewaehlt = gewaehlterIndex(gruppe);
                    if (gewaehlt < 0 || gewaehlt >= rules.size()) {
                        // „Die App entscheiden lassen": der vorgeschlagene Anker wird verworfen, der
                        // Lerner sucht beim Speichern selbst (der berücksichtigt dann auch, was die
                        // anderen Felder inzwischen belegt haben). Der Feldtitel bekommt seinen
                        // ursprünglichen Namen zurück, das Symbol bleibt stehen.
                        chosenValueRules.remove(field);
                        if (layout != null) {
                            layout.setHint(originalHints.get(field));
                        }
                    } else {
                        chosenValueRules.put(field, rules.get(gewaehlt));
                        zeigeErkannteRegel(field, layout, rules.get(gewaehlt), rules);
                    }
                    // Erst dieser Knopf ist die Bestätigung: bestand für dieses Feld ein Widerspruch zur
                    // Bank-Vorlage, darf jetzt auch tatsächlich gelernt werden (siehe lernen()).
                    ersetzteRegeln.add(field);
                    nichtLernenFelder.remove(field);
                    entschiedenFuer.put(field, wertVon(field));
                    if (konflikt && !schalter.isChecked()) {
                        anhaengenFelder.add(field);
                    } else {
                        anhaengenFelder.remove(field);
                    }
                })
                .setNegativeButton(R.string.statement_anchor_dont_learn, (d, w) -> {
                    // Nur diese Buchung bekommt den gefundenen Wert, die für diese Bank gespeicherte
                    // Regel bleibt unangetastet.
                    ersetzteRegeln.remove(field);
                    anhaengenFelder.remove(field);
                    nichtLernenFelder.add(field);
                    entschiedenFuer.put(field, wertVon(field));
                })
                .create();
    }

    /** Ein Eintrag der Auswahl: die Beschriftung und — entscheidend — wo sie steht. */
    private CharSequence anchorText(de.spahr.ausgaben.statement.AnchorRule rule) {
        StringBuilder anker = new StringBuilder();
        for (String a : rule.anchors) {
            if (anker.length() > 0) {
                anker.append(" + ");
            }
            anker.append(a);
        }
        return getString(R.string.statement_anchor_item, anker.toString(), anchorPlace(rule));
    }

    /**
     * Wo die Beschriftung zum Wert steht. Ohne diese Angabe wäre die Liste nicht zu lesen: dieselbe
     * Beschriftung kann als Spaltenüberschrift und als Wort daneben auftauchen, und beide meinen
     * verschiedene Zahlen.
     */
    private String anchorPlace(de.spahr.ausgaben.statement.AnchorRule rule) {
        if (rule.sum) {
            return getString(R.string.statement_anchor_sum);
        }
        if (rule.position == de.spahr.ausgaben.statement.AnchorRule.Position.COLUMN) {
            return getString(R.string.statement_anchor_column);
        }
        switch (rule.direction) {
            case LINE_BELOW:
                return getString(R.string.statement_anchor_heading);
            case LINE_ABOVE:
                return getString(R.string.statement_anchor_under);
            default:
                return getString(
                        rule.position == de.spahr.ausgaben.statement.AnchorRule.Position.FIRST
                                ? R.string.statement_anchor_same_first
                                : R.string.statement_anchor_same_last);
        }
    }

    private void showCalendar() {
        HostedDialog.show(this, DLG_CALENDAR, null);
    }

    /**
     * Der Kalender – gebaut aus {@link #selectedDate}, das {@code onSaveInstanceState} mitsichert.
     * Deshalb braucht er keine eigenen Angaben im Bundle: Nach der Drehung steht dort dasselbe Datum
     * wie vorher.
     */
    private android.app.Dialog buildCalendarDialog() {
        return new DatePickerDialog(this, (view, year, month, day) -> {
            selectedDate.set(Calendar.YEAR, year);
            selectedDate.set(Calendar.MONTH, month);
            selectedDate.set(Calendar.DAY_OF_MONTH, day);
            // Von Hand gewählt: eine vorher angetippte Beschriftung meint jetzt ein anderes Datum.
            chosenDateLabel = null;
            chosenDateRule = null;
            dateTyped = true;
            updateDateField();
        }, selectedDate.get(Calendar.YEAR), selectedDate.get(Calendar.MONTH),
                selectedDate.get(Calendar.DAY_OF_MONTH));
    }

    /** Schreibt das gewählte Datum ins Feld – damit steht es fest. */
    private void updateDateField() {
        dateKnown = true;
        editDate.setText(DateFormats.date(selectedDate.getTimeInMillis()));
        dateLayout.setError(null);
        planeScheduleMatchCheck();
    }

    /**
     * In der Abrechnung stand kein Datum, das die Vorlage kennt. Das Feld bleibt leer und sagt, warum –
     * ein Tipp legt die im Dokument gefundenen Angaben vor.
     */
    private void clearDateField() {
        dateKnown = false;
        editDate.setText("");
        dateLayout.setError(getString(R.string.statement_date_missing));
    }

    private String actionLabel(String action) {
        switch (action == null ? "" : action) {
            case BUY:
                return getString(R.string.action_buy);
            case SELL:
                return getString(R.string.action_sell);
            case DIVIDEND:
                return getString(R.string.action_dividend);
            case "add":
                return getString(R.string.action_add);
            case "remove":
                return getString(R.string.action_remove);
            case "reinvest":
                return getString(R.string.action_reinvest);
            default:
                return action == null ? "" : action;
        }
    }

    /** Verkauf/Ausbuchung = rot, Kauf/Wiederanlage/Einbuchung = grün, Dividende = Standardfarbe. */
    private int amountColor(String action) {
        switch (action == null ? "" : action) {
            case SELL:
            case "remove":
                return getColor(R.color.expense_red);
            case BUY:
            case "reinvest":
            case "add":
                return getColor(R.color.income_green);
            default:
                android.util.TypedValue tv = new android.util.TypedValue();
                getTheme().resolveAttribute(android.R.attr.textColorPrimary, tv, true);
                return getColor(tv.resourceId);
        }
    }

    private static String textOf(android.widget.TextView view) {
        return view.getText() == null ? "" : view.getText().toString();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
