package de.spahr.ausgaben.ui;

import android.os.Bundle;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import de.spahr.ausgaben.util.SecurityAmounts.Field;

/**
 * Der Stand der Wertpapier-Erfassungsmaske, der eine Drehung (und den Prozesstod) überleben muss: was
 * erkannt, getippt, gewählt und gelernt wurde. Ausgelagert aus {@link SecurityTxEditActivity}, um deren
 * Umfang zu verringern – die Felder und ihre Sicherung sind dabei unverändert umgezogen. Das Datum
 * selbst ({@code selectedDate}) bleibt in der Maske.
 *
 * <p>Sätze und Karten über {@link Field} gehen als Feldnamen ins Bundle; ein Bundle aus einer anderen
 * Fassung darf dabei nicht abstürzen (siehe {@link #fieldOf}).</p>
 */
final class SecurityTxEditState {

    private static final String STATE_LEARN_INTRO_SHOWN = "s_learnIntroShown";
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
    private static final String STATE_LEARN_ACTION = "s_learnAction";
    private static final String STATE_LEARN_SHARES = "s_learnShares";
    private static final String STATE_LEARN_PRICE = "s_learnPrice";
    private static final String STATE_LEARN_FEE = "s_learnFee";
    private static final String STATE_LEARN_NET = "s_learnNet";
    private static final String STATE_LEARN_GROSS = "s_learnGross";
    private static final String STATE_LIST_HINT = "s_listHint";
    private static final String STATE_LIST_HINT_KEY = "s_listHintKey";
    private static final String STATE_LAST_DUP_KEY = "s_lastDupKey";

    /**
     * Steht das Datum fest? {@code selectedDate} allein sagt das nicht: es trägt immer einen Wert, damit
     * der Kalender irgendwo aufschlägt. Ohne diese Unterscheidung würde ein nicht erkanntes Datum als das
     * heutige gebucht, ohne dass es jemand merkt.
     */
    boolean dateKnown;
    /** Dasselbe für Kauf/Verkauf/Dividende: ohne erkannte Art ist kein Knopf vorgewählt. */
    boolean actionKnown;
    /** Hat der Nutzer das Datum selbst gewählt? Dann gehört auch dessen Beschriftung gelernt. */
    boolean dateTyped;
    /** Diese Bewegung steht schon im Depot — geht so an die Erkennungsliste zurück. */
    boolean dupBooked;
    boolean conflict;
    /** Das Speichern läuft schon — siehe {@link #save()}. */
    boolean saving;
    /**
     * Ob die Einführung ins Lernen (siehe {@link #DLG_LEARN_INTRO}) schon einmal aufging — sonst käme
     * sie nach jeder Drehung noch einmal, obwohl der Nutzer sie längst weggetippt hat.
     */
    boolean learnIntroShown;
    /** Felder, die der Nutzer selbst gefüllt hat – nur die übrigen darf die Rechnung überschreiben. */
    final Set<Field> userSet = EnumSet.noneOf(Field.class);
    /**
     * Die Felder, in die der Nutzer <b>selbst</b> geschrieben hat — nur aus ihnen wird gelernt.
     *
     * <p>Nicht zu verwechseln mit {@code userSet}: dort stehen auch die Werte, welche die Maske aus der
     * Abrechnung vorbelegt hat. Hier landet nur, was durch den Beobachter kam, und der schweigt bei jedem
     * programmatischen Schreiben ({@code writingBack}). Genau diese Unterscheidung ist der Punkt: die App
     * soll die Beschriftung zu einer Zahl suchen, die der Nutzer abgetippt hat — nicht zu einer, die sie
     * sich selbst vorgelegt hat.</p>
     */
    final Set<Field> typedFields = EnumSet.noneOf(Field.class);
    /**
     * Felder, deren live gefundene Beschriftung einer schon in der Bank-Vorlage stehenden, ANDEREN Regel
     * widerspricht — dort lernt {@link #lernen} nur, wenn das Feld auch in {@link #ersetzteRegeln} steht.
     */
    final Set<Field> konfliktFelder = EnumSet.noneOf(Field.class);
    /**
     * Felder, für die der Nutzer übers Stift-Symbol ausdrücklich eine Beschriftung bestätigt hat — nur
     * dann darf eine in {@link #konfliktFelder} stehende Korrektur die Bank-Vorlage wirklich ersetzen.
     */
    final Set<Field> ersetzteRegeln = EnumSet.noneOf(Field.class);
    /**
     * Teilmenge von {@link #ersetzteRegeln}: Felder, bei denen der Schalter im Stift-Dialog auf
     * „hinzufügen" stand. Dort soll die neue Beschriftung die alte Regel nicht ablösen, sondern als
     * weitere Möglichkeit in deren Kette stehen — siehe {@link StatementTemplate#appendedTo}.
     */
    final Set<Field> anhaengenFelder = EnumSet.noneOf(Field.class);
    /**
     * Felder, für die der Nutzer im Stift-Dialog „Nicht lernen" gewählt hat: der gefundene Wert gilt für
     * diese eine Buchung, die Bank-Vorlage bleibt unangetastet. Nötig neben {@link #ersetzteRegeln},
     * weil ohne Widerspruch ({@link #konfliktFelder}) sonst stillschweigend gelernt würde.
     */
    final Set<Field> nichtLernenFelder = EnumSet.noneOf(Field.class);
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
    final Map<Field, Double> entschiedenFuer = new EnumMap<>(Field.class);
    Field lastComputed;
    /** Beschriftung des aus der Abrechnung gewählten Datums; sie wird zum Anker. */
    String chosenDateLabel;
    /**
     * Die Regel hinter der Wahl. Zu einem Datum gibt es zwei Lesarten — die Beschriftung daneben und
     * die Spaltenüberschrift darüber —, und die Beschriftung allein sagt nicht, welche gemeint war.
     */
    de.spahr.ausgaben.statement.AnchorRule chosenDateRule;
    /**
     * Dasselbe für die Wertfelder: die beim Verlassen des Feldes gewählte Beschriftung.
     *
     * <p>Gefragt wird nur beim <b>ersten</b> Beleg einer Bank — danach steht die Vorlage, und die
     * kennt die Antwort schon. Siehe {@link #ankerAuswahlAnbieten}.</p>
     */
    final Map<Field, de.spahr.ausgaben.statement.AnchorRule> chosenValueRules =
            new EnumMap<>(Field.class);
    /** Beleg-Tag einer bereits gespeicherten Abrechnung (aus der Notiz der Gegenbuchung). */
    String savedStatementTag;
    /** Kategorie einer festen Gebühr aus der Regel; sie schlägt die erschlossene (siehe Extra). */
    String fixedFeeCategory = "";
    /** Der Hinweis, den die Erkennungsliste mitgab, und der Stand, für den er galt. */
    int listHint;
    String listHintKey;
    /**
     * Der Stand, für den zuletzt nach einer Doppelung gefragt wurde ({@code null} = noch nie). Solange
     * er sich nicht ändert, wird die Datenbank nicht erneut befragt — sonst liefe bei jedem Tastendruck
     * eine Abfrage.
     */
    String lastDupKey;
    /** Die Angaben, mit denen sich die Lern-Rückfrage nach einer Drehung neu aufsetzen lässt. */
    String learnAction;
    Double learnShares;
    Double learnPrice;
    Long learnFeeCents;
    Long learnNetCents;
    Long learnGrossCents;

    void save(Bundle out) {
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

    /** Liest den Stand zurück; {@code in} ist nicht {@code null}. */
    void restore(Bundle in) {
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
        String fee = in.getString(STATE_FIXED_FEE_CATEGORY);
        fixedFeeCategory = fee == null ? "" : fee;
        listHint = in.getInt(STATE_LIST_HINT, 0);
        listHintKey = in.getString(STATE_LIST_HINT_KEY);
        lastDupKey = in.getString(STATE_LAST_DUP_KEY);
        learnAction = in.getString(STATE_LEARN_ACTION);
        learnShares = in.containsKey(STATE_LEARN_SHARES) ? in.getDouble(STATE_LEARN_SHARES) : null;
        learnPrice = in.containsKey(STATE_LEARN_PRICE) ? in.getDouble(STATE_LEARN_PRICE) : null;
        learnFeeCents = in.containsKey(STATE_LEARN_FEE) ? in.getLong(STATE_LEARN_FEE) : null;
        learnNetCents = in.containsKey(STATE_LEARN_NET) ? in.getLong(STATE_LEARN_NET) : null;
        learnGrossCents = in.containsKey(STATE_LEARN_GROSS) ? in.getLong(STATE_LEARN_GROSS) : null;
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
    static Field fieldOf(String name) {
        if (name == null) {
            return null;
        }
        try {
            return Field.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
