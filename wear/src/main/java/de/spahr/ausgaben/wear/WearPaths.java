package de.spahr.ausgaben.wear;

/** Gemeinsame Konstanten der Wear-Anbindung (Data-Layer-Pfade + interne Broadcast-Aktion). */
public final class WearPaths {

    private WearPaths() {
    }

    /** Uhr → Phone: neue gesprochene Ausgabe als DataItem unter {@code /expense/new/<id>}. */
    public static final String PATH_NEW_PREFIX = "/expense/new/";
    /** Phone → Uhr: aktive Sprache + Wear-Texte (DataItem {code, strings}). */
    public static final String PATH_LANGUAGE = "/language";
    /** Phone → Uhr: fertig formatierter Standardort-Saldo (DataItem {text}). */
    public static final String PATH_BALANCE = "/balance";
    /** Phone → Uhr: Empfänger mit Standorten für die Umkreisliste (DataItem {list, currency}). */
    public static final String PATH_PAYEES = "/payees";

    /** Interner Broadcast: offene Anzahl hat sich geändert (Zähler aktualisieren). */
    public static final String ACTION_PENDING_CHANGED = "de.spahr.ausgaben.wear.PENDING_CHANGED";
    /** Interner Broadcast: Sprache hat sich geändert (Anzeige neu aufbauen). */
    public static final String ACTION_LANGUAGE_CHANGED = "de.spahr.ausgaben.wear.LANGUAGE_CHANGED";
    /** Interner Broadcast: Standardort-Saldo hat sich geändert (Anzeige/Tile aktualisieren). */
    public static final String ACTION_BALANCE_CHANGED = "de.spahr.ausgaben.wear.BALANCE_CHANGED";

    /** Buchungstyp (per Knopf gewählt) – Drahtwerte, identisch zu {@code Repository.VOICE_TYPE_*}. */
    public static final String TYPE_INCOME = "income";
    public static final String TYPE_EXPENSE = "expense";
    public static final String TYPE_TRANSFER = "transfer";
}
