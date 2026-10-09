package de.spahr.ausgaben.export;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import java.util.HashMap;
import java.util.Map;

import de.spahr.ausgaben.db.Account;
import de.spahr.ausgaben.db.AnalysisExtra;
import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.BookingSplit;
import de.spahr.ausgaben.db.ScheduledSplit;
import de.spahr.ausgaben.db.ScheduledTransaction;
import de.spahr.ausgaben.db.Security;
import de.spahr.ausgaben.db.SecurityPrice;
import de.spahr.ausgaben.db.SecurityTx;

/**
 * Liest aus einer {@link KmyDocument} die Buchungen eines gewählten (Bargeld-/Vermögens-)Kontos und
 * bildet sie auf App-{@link Booking}s ab. Betrag/Vorzeichen stammen aus dem Konto-Split, Kategorie aus
 * dem Gegen-Split, Empfänger aus der Payee-Referenz.
 */
public class KmyImporter {

    private final KmyDocument doc;
    private final android.content.Context ctx;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    public KmyImporter(KmyDocument doc, android.content.Context context) {
        this.doc = doc;
        this.ctx = de.spahr.ausgaben.i18n.LocaleManager.localizedContext(context);
    }

    public List<String> accountNames() {
        return doc.accountNames();
    }

    /** Die Stichwörter der Datei – daraus wird die Liste des in der App Wählbaren. */
    public List<String> tagNames() {
        return doc.tagNames();
    }

    /** Anzeigenamen der Depots (Investment-Konten). */
    public List<String> depotNames() {
        return doc.depotNames();
    }

    /** Budgetjahre aus der Datei. */
    public List<Integer> budgetYears() {
        return doc.budgetYears();
    }

    /** Kategorie-Soll-Werte (Jahressumme) des Budgetjahres. */
    public List<KmyDocument.BudgetEntry> budgetEntries(int year) {
        return doc.budgetEntries(year);
    }

    /** Ergebnis eines Depot-Imports: Wertpapiere (mit letztem Kurs) + Bewegungen + Kurshistorie. */
    public static final class DepotData {
        public final List<Security> securities = new ArrayList<>();
        public final List<SecurityTx> transactions = new ArrayList<>();
        public final List<SecurityPrice> prices = new ArrayList<>();
    }

    /**
     * Importiert ein Depot: seine Wertpapiere (Typ-15-Unterkonten) samt letztem Kurs und die
     * Käufe/Verkäufe/Dividenden/Einbuchungen aus dem Hauptbuch.
     */
    public DepotData importDepot(String depotName) throws IOException {
        return importDepot(depotName, null);
    }

    /**
     * Wie oben, meldet aber den Fortschritt des Hauptbuch-Durchlaufs (gesehene von insgesamt
     * {@code transactionCount()} Transaktionen) – er ist der lange Teil eines Depot-Imports.
     */
    public DepotData importDepot(String depotName, de.spahr.ausgaben.util.ProgressListener listener)
            throws IOException {
        DepotData data = new DepotData();
        String depotId = doc.depotId(depotName);
        if (depotId == null) {
            return data;
        }
        // Stock-Konten des Depots (Typ 15, parent = Depot): id → {securityKmyId, securityName}.
        Map<String, String[]> stockAccounts = stockAccountsOf(depotId);
        for (Map.Entry<String, String[]> e : stockAccounts.entrySet()) {
            String secId = e.getValue()[0];
            String secName = e.getValue()[1];
            String[] info = doc.securityInfo(secId);
            double[] price = doc.securityPrice(secId);
            String name = info != null && !info[0].isEmpty() ? info[0] : secName;
            String symbol = info != null ? info[1] : "";
            String currency = info != null && !info[2].isEmpty() ? info[2] : "EUR";
            Security security = new Security(depotName, secId, name, symbol, currency,
                    price != null ? price[0] : 0, price != null ? (long) price[1] : 0);
            security.isin = doc.securityIsin(secId);
            data.securities.add(security);
            // Vollständige Kurshistorie (für die zeitliche Depotbewertung in der Vermögensgrafik).
            for (double[] ph : doc.securityPriceHistory(secId)) {
                data.prices.add(new SecurityPrice(depotName, secId, (long) ph[1], ph[0]));
            }
        }
        if (stockAccounts.isEmpty()) {
            return data;
        }

        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(new StringReader(doc.xml()));
            int event = parser.getEventType();
            boolean inLedger = false;
            String postdate = null;
            String entrydate = null;
            List<String[]> splits = null; // je Split: {account, value, action, shares, memo}
            int seen = 0;
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("TRANSACTIONS".equals(tag)) {
                        inLedger = true;
                    } else if (inLedger && "TRANSACTION".equals(tag)) {
                        postdate = parser.getAttributeValue(null, "postdate");
                        entrydate = parser.getAttributeValue(null, "entrydate");
                        splits = new ArrayList<>();
                    } else if (inLedger && "SPLIT".equals(tag) && splits != null) {
                        splits.add(new String[]{
                                orEmpty(parser.getAttributeValue(null, "account")),
                                orEmpty(parser.getAttributeValue(null, "value")),
                                orEmpty(parser.getAttributeValue(null, "action")),
                                orEmpty(parser.getAttributeValue(null, "shares")),
                                orEmpty(parser.getAttributeValue(null, "memo"))});
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    String tag = parser.getName();
                    if ("TRANSACTIONS".equals(tag)) {
                        break;
                    } else if (inLedger && "TRANSACTION".equals(tag)) {
                        SecurityTx tx = toSecurityTx(depotName, stockAccounts, postdate, entrydate, splits);
                        if (tx != null) {
                            data.transactions.add(tx);
                        }
                        splits = null;
                        seen++;
                        if (listener != null) {
                            listener.onProgress(seen, doc.transactionCount());
                        }
                    }
                }
                event = parser.next();
            }
        } catch (XmlPullParserException e) {
            throw new IOException(ctx.getString(de.spahr.ausgaben.R.string.err_kmy_read), e);
        }
        return data;
    }

    /** Wertpapier-Unterkonten eines Depots (Typ 15): Konto-Id → {Wertpapier-Id, Wertpapiername}. */
    private Map<String, String[]> stockAccountsOf(String depotId) {
        Map<String, String[]> stockAccounts = new HashMap<>();
        for (String id : doc.allAccountIds()) {
            if (doc.accountTypeOf(id) == 15 && depotId.equals(doc.accountParentOf(id))) {
                stockAccounts.put(id, new String[]{doc.accountCurrencyOf(id),
                        orEmpty(doc.accountNameById(id)).trim()});
            }
        }
        return stockAccounts;
    }

    /** Zahl der Buchungen im Hauptbuch – Gewicht für die Aufteilung des Fortschritts. */
    public int transactionCount() {
        return doc.transactionCount();
    }

    /**
     * Zahl der Kurszeilen, die dieses Depot schreiben wird – Gewicht für die Aufteilung des
     * Fortschritts ({@link ImportBudget}).
     */
    public int priceCount(String depotName) {
        String depotId = doc.depotId(depotName);
        if (depotId == null) {
            return 0;
        }
        int count = 0;
        for (String[] sec : stockAccountsOf(depotId).values()) {
            count += doc.securityPriceHistory(sec[0]).size();
        }
        return count;
    }

    private SecurityTx toSecurityTx(String depotName, Map<String, String[]> stockAccounts,
                                    String postdate, String entrydate, List<String[]> splits) {
        if (splits == null) {
            return null;
        }
        String[] stock = null;
        for (String[] s : splits) {
            if (stockAccounts.containsKey(s[0])) {
                stock = s;
                break;
            }
        }
        if (stock == null) {
            return null; // Transaktion betrifft kein Wertpapier dieses Depots
        }
        String[] sec = stockAccounts.get(stock[0]);
        double shares = de.spahr.ausgaben.export.KmyDocument.fractionToDouble(stock[3]);
        String action = normalizeAction(stock[2], shares);
        if ("dividend".equals(action)) {
            long gross = dividendGross(splits);
            long net = dividendNet(splits);
            if (net == 0) {
                net = gross;
            }
            SecurityTx tx = new SecurityTx(depotName, sec[0], sec[1],
                    parseDate(postdate, entrydate), action, shares, gross, net);
            tx.note = memoOf(stock);
            fillOrigin(tx, splits);
            return tx;
        }
        boolean moved = "add".equals(action) || "remove".equals(action);
        // Ein-/Ausbuchungen tragen in KMyMoney keinen Geldwert – dort gibt es folglich auch keine Gebühr.
        long amountCents = moved ? 0 : Math.abs(valueToCents(stock[1]));
        long feeCents = moved ? 0 : fees(splits);
        // Netto ist auch hier das bewegte Geld, nicht noch einmal das Brutto: beim Kauf kommt die
        // Gebühr hinzu, beim Verkauf geht sie ab (siehe SecurityTx#netCents).
        SecurityTx tx = new SecurityTx(depotName, sec[0], sec[1],
                parseDate(postdate, entrydate), action, shares, amountCents,
                SecurityTx.moneyOf(action, amountCents, feeCents), feeCents);
        tx.note = memoOf(stock);
        fillOrigin(tx, splits);
        return tx;
    }

    /**
     * Das Memo des Wertpapier-Splits – dort steht der Beleg-Tag einer eingelesenen Abrechnung
     * (siehe {@link SecurityTx#note}). Ältere Dateien haben die Spalte nicht, dann bleibt sie leer.
     */
    private static String memoOf(String[] split) {
        return split.length > 4 ? orEmpty(split[4]) : "";
    }

    /**
     * Trägt Geldkonto und Kategorien der Transaktion an der Bewegung nach. Für die Auswertung spielen sie
     * keine Rolle – sie belegen die Erfassungsmaske vor, damit schon die erste selbst angelegte Bewegung
     * dieselbe Kontenwahl und dieselben Kategorien vorschlägt wie die zuletzt importierte.
     */
    private void fillOrigin(SecurityTx tx, List<String[]> splits) {
        for (String[] s : splits) {
            int type = doc.accountTypeOf(s[0]);
            if (type == 15 || type == 16) {
                continue;   // das Wertpapier selbst und Eigenkapital sind kein Gegenkonto
            }
            if (type == 12 || type == 13) {
                // Je Kategoriesplit eine eigene Zeile: in KMyMoney hängen an einer Dividende oft
                // mehrere (Kapitalertragsteuer, Solidaritätszuschlag, Kirchensteuer), und sie zu einer
                // zusammenzuziehen wäre genau der Verlust, den die Aufteilung beheben soll. Eine
                // Herkunftsbeschriftung gibt es hier nicht – die Datei nennt Konten, keine Belegzeilen.
                String category = orEmpty(doc.categoryPath(s[0]));
                if (!category.isEmpty()) {
                    // Die Gegenrechnung zum Export: dort wird der Ertragsteil mit umgekehrtem
                    // Vorzeichen geschrieben. Also hier durch dasselbe Vorzeichen teilen, statt den
                    // Betrag über Math.abs einzuebnen — sonst würde aus einer gutgeschriebenen
                    // Steuer (Erstattung) beim Einlesen ein Abzug.
                    boolean income = type == 12;
                    long value = income ? -valueToCents(s[1]) : valueToCents(s[1]);
                    tx.parts.add(new de.spahr.ausgaben.db.SecurityTxSplit(0, income, category,
                            value, "", tx.parts.size()));
                }
            } else if (tx.moneyAccount.isEmpty()) {
                tx.moneyAccount = orEmpty(doc.accountNameById(s[0])).trim();
            }
        }
    }

    /**
     * Gebühren einer Kauf-/Verkaufsbuchung: die Ausgabe-Kategorie-Splits (Typ 13) neben dem Wertpapier-Split.
     * Der Wertpapier-Split trägt nur Stücke × Kurs; die Gebühr hängt in KMyMoney als eigene Kategorie daran.
     */
    private long fees(List<String[]> splits) {
        long sum = 0;
        for (String[] s : splits) {
            if (doc.accountTypeOf(s[0]) == 13) {
                sum += Math.abs(valueToCents(s[1]));
            }
        }
        return sum;
    }

    /** Brutto-Dividende: aus dem Einnahme-Kategorie-Split (Typ 12), sonst dem positiven Geld-Split. */
    private long dividendGross(List<String[]> splits) {
        for (String[] s : splits) {
            if (doc.accountTypeOf(s[0]) == 12) {
                return Math.abs(valueToCents(s[1]));
            }
        }
        for (String[] s : splits) {
            int t = doc.accountTypeOf(s[0]);
            long v = valueToCents(s[1]);
            if (t != 15 && v > 0) {
                return v;
            }
        }
        return 0;
    }

    /**
     * Netto-Dividende = tatsächlich gutgeschriebenes Geld: der positive Split auf einem Geld-/Bankkonto
     * (kein Kategorie-Typ 12/13, keine Aktie 15, kein Eigenkapital 16). Fehlt einer, wird Brutto genutzt.
     */
    private long dividendNet(List<String[]> splits) {
        for (String[] s : splits) {
            int t = doc.accountTypeOf(s[0]);
            long v = valueToCents(s[1]);
            if (t != 12 && t != 13 && t != 15 && t != 16 && v > 0) {
                return v;
            }
        }
        return 0;
    }

    private static String normalizeAction(String a, double shares) {
        String x = a == null ? "" : a.trim().toLowerCase(Locale.US);
        switch (x) {
            case "dividend":
            case "add":
            case "remove":
            case "reinvest":
                return x;
            case "buy":
            case "sell":
                // KMyMoney schreibt für einen Verkauf "Buy" — das ist keine Nachlässigkeit, sondern das
                // Format: actionNamesLUT (mymoney/mymoneysplit.cpp) kennt gar kein "Sell", dort steht
                // ausdrücklich „SellShares is not present as action". Unterschieden wird allein am
                // Vorzeichen der Stückzahl: negativ = Verkauf, positiv = Kauf. Ohne diese Regel würde ein
                // Verkauf als Kauf gezählt und der Erlös zum Einstand addiert. "sell" bleibt hier
                // trotzdem stehen: ältere Dateien tragen es, weil die App es bis 1.13 selbst schrieb.
                return shares < 0 ? "sell" : "buy";
            default:
                // Unbekannte Action: nur bei echter Stückbewegung als Kauf/Verkauf einordnen (leere Action).
                // Erträge ohne Stückzahl (z. B. Zins/Yield) bleiben unverändert und werden nicht mitgezählt.
                if (shares != 0) {
                    return shares < 0 ? "sell" : "buy";
                }
                return x;
        }
    }

    /** Währungskennzeichen des Kontos aus der KMyMoney-Datei (z. B. „EUR"), sonst leer. */
    public String currencyOf(String accountName) {
        return doc.currencyOfAccount(accountName);
    }

    /** KMyMoney-Kontotyp (für die Trennung Anlage/Verbindlichkeit); 0 wenn unbekannt. */
    public int accountType(String accountName) {
        String id = doc.accountId(accountName);
        return id == null ? 0 : doc.accountTypeOf(id);
    }

    /** Kontoname → KMyMoney-Typ für alle wählbaren Konten (zum Klassifizieren aller App-Konten beim Import). */
    public java.util.Map<String, Integer> accountTypes() {
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (String name : doc.accountNames()) {
            out.put(name, accountType(name));
        }
        // Ein Name, den ein Depot trägt, gehört in der App dem Depot: seine Trägerzeile führt die
        // Kontenart, und mit einem fremden Typ fiele das Depot aus Schublade und Kontenverwaltung
        // heraus. Angelegt wird dadurch nichts – applyAccountTypes ist ein reines UPDATE.
        for (String depot : doc.depotNames()) {
            out.put(depot, Account.KMY_TYPE_DEPOT);
        }
        return out;
    }

    /** Kontoname → Bankinstitut für alle Konten und Depots der Datei (Grundlage der Bank-Kontengruppen). */
    public java.util.Map<String, String> institutions() {
        return doc.institutionsByAccount();
    }

    /** Namen der in KMyMoney bevorzugten Konten (Grundlage der Kontengruppe „Favoriten"). */
    public java.util.List<String> favorites() {
        return doc.favoriteAccounts();
    }

    /**
     * Kategorie-Pfad → Typ ({@code true} = Einnahme, {@code false} = Ausgabe) für alle Kategorien der
     * Datei (zum Klassifizieren aller Budget-Kategorien beim Import). Siehe {@code KmyDocument}.
     */
    public java.util.Map<String, Boolean> categoryTypes() {
        return doc.categoryTypesByPath();
    }

    /**
     * Buchungen <b>mehrerer</b> Konten in <b>einem</b> Durchlauf – je Transaktion wird für jedes Zielkonto
     * geprüft, ob sie es betrifft ({@code toBooking} liefert sonst {@code null}). Semantisch identisch zu
     * {@link #bookingsForAccount(String)} je Konto, aber ohne den kompletten Neuaufbau des Parsers pro Konto
     * (bei 20 Konten und ~10.000 Buchungen war das der Löwenanteil der Wartezeit).
     *
     * @param listener meldet die verarbeiteten Transaktionen gegen {@code doc.transactionCount()}
     */
    public java.util.LinkedHashMap<String, List<Booking>> bookingsForAccounts(
            List<String> accountNames, de.spahr.ausgaben.util.ProgressListener listener)
            throws IOException {
        java.util.LinkedHashMap<String, List<Booking>> out = new java.util.LinkedHashMap<>();
        // Konto → id; Konten ohne id in der Datei liefern (wie bisher) eine leere Liste.
        java.util.LinkedHashMap<String, String> ids = new java.util.LinkedHashMap<>();
        for (String name : accountNames) {
            out.put(name, new ArrayList<>());
            String id = doc.accountId(name);
            if (id != null) {
                ids.put(name, id);
            }
        }
        if (ids.isEmpty()) {
            return out;
        }
        final int total = doc.transactionCount();
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(new StringReader(doc.xml()));

            int event = parser.getEventType();
            boolean inLedger = false;
            String postdate = null;
            String entrydate = null;
            String txMemo = "";
            String txCommodity = "";
            List<String[]> splits = null;
            int seen = 0;
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("TRANSACTIONS".equals(tag)) {
                        inLedger = true;
                    } else if (inLedger && "TRANSACTION".equals(tag)) {
                        postdate = parser.getAttributeValue(null, "postdate");
                        entrydate = parser.getAttributeValue(null, "entrydate");
                        txMemo = orEmpty(parser.getAttributeValue(null, "memo"));
                        txCommodity = orEmpty(parser.getAttributeValue(null, "commodity")).trim();
                        splits = new ArrayList<>();
                    } else if (inLedger && "SPLIT".equals(tag) && splits != null) {
                        splits.add(newSplit(parser));
                    } else if (inLedger && "TAG".equals(tag)) {
                        addTagId(splits, parser.getAttributeValue(null, "id"));
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    String tag = parser.getName();
                    if ("TRANSACTIONS".equals(tag)) {
                        break; // Hauptbuch vollständig; <SCHEDULES> ignorieren
                    } else if (inLedger && "TRANSACTION".equals(tag)) {
                        for (java.util.Map.Entry<String, String> e : ids.entrySet()) {
                            Booking b = toBooking(e.getValue(), e.getKey(), postdate, entrydate, txMemo,
                                    txCommodity, splits);
                            if (b != null) {
                                out.get(e.getKey()).add(b);
                            }
                        }
                        splits = null;
                        seen++;
                        if (listener != null) {
                            listener.onProgress(seen, total);
                        }
                    }
                }
                event = parser.next();
            }
        } catch (XmlPullParserException e) {
            throw new IOException(ctx.getString(de.spahr.ausgaben.R.string.err_kmy_read), e);
        }
        return out;
    }

    /** Alle Buchungen, die einen Split auf dem gewählten Konto haben. */
    public List<Booking> bookingsForAccount(String accountName) throws IOException {
        List<Booking> out = new ArrayList<>();
        String accountId = doc.accountId(accountName);
        if (accountId == null) {
            return out;
        }
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(new StringReader(doc.xml()));

            int event = parser.getEventType();
            boolean inLedger = false; // nur echte Buchungen aus <TRANSACTIONS>, nicht aus <SCHEDULES>
            String postdate = null;
            String entrydate = null;
            String txMemo = "";
            String txCommodity = "";
            List<String[]> splits = null; // je Split: {account, value, payeeId, memo, shares, tagIds}
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("TRANSACTIONS".equals(tag)) {
                        inLedger = true;
                    } else if (inLedger && "TRANSACTION".equals(tag)) {
                        postdate = parser.getAttributeValue(null, "postdate");
                        entrydate = parser.getAttributeValue(null, "entrydate");
                        txMemo = orEmpty(parser.getAttributeValue(null, "memo"));
                        txCommodity = orEmpty(parser.getAttributeValue(null, "commodity")).trim();
                        splits = new ArrayList<>();
                    } else if (inLedger && "SPLIT".equals(tag) && splits != null) {
                        splits.add(newSplit(parser));
                    } else if (inLedger && "TAG".equals(tag)) {
                        addTagId(splits, parser.getAttributeValue(null, "id"));
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    String tag = parser.getName();
                    if ("TRANSACTIONS".equals(tag)) {
                        break; // Hauptbuch vollständig gelesen; <SCHEDULES> (geplante Buchungen) ignorieren
                    } else if (inLedger && "TRANSACTION".equals(tag)) {
                        Booking b = toBooking(accountId, accountName, postdate, entrydate, txMemo,
                                txCommodity, splits);
                        if (b != null) {
                            out.add(b);
                        }
                        splits = null;
                    }
                }
                event = parser.next();
            }
        } catch (XmlPullParserException e) {
            throw new IOException(ctx.getString(de.spahr.ausgaben.R.string.err_kmy_read), e);
        }
        return out;
    }

    /**
     * Betrag eines Splits in der Währung <b>seines</b> Kontos. KMyMoney führt {@code value} in der Währung
     * der Transaktion ({@code commodity}) und {@code shares} in der des Kontos – bei einem Konto in
     * Fremdwährung sind das zwei verschiedene Zahlen (value=-10123/1, shares=-218677/25). Für die App
     * zählt, was auf dem Konto ankommt, also {@code shares}.
     */
    private long amountOf(String[] split, String commodity) {
        if (doc.accountTypeOf(split[0]) == 15) {
            return valueToCents(split[1]); // Wertpapierkonto: shares ist die Stückzahl, kein Geldbetrag
        }
        String own = doc.accountCurrencyOf(split[0]);
        boolean foreign = !own.isEmpty() && !commodity.isEmpty() && !own.equalsIgnoreCase(commodity);
        if (foreign && split.length > 4 && !split[4].isEmpty()) {
            return valueToCents(split[4]);
        }
        return valueToCents(split[1]);
    }

    private Booking toBooking(String accountId, String accountName, String postdate, String entrydate,
                              String txMemo, String commodity, List<String[]> splits) {
        if (splits == null) {
            return null;
        }
        String[] own = null;
        for (String[] s : splits) {
            if (accountId.equals(s[0])) {
                own = s;
                break;
            }
        }
        if (own == null) {
            return null; // Buchung betrifft dieses Konto nicht
        }

        long signedCents = amountOf(own, commodity);
        Booking b = new Booking();
        b.amountCents = Math.abs(signedCents);
        b.isIncome = signedCents > 0;
        b.account = accountName;
        b.note = !own[3].isEmpty() ? own[3] : txMemo;
        b.tags = tagsOf(splits);
        b.createdAt = parseDate(postdate, entrydate);
        b.exported = true;
        b.reconciled = isReconciled(splits);

        // Gegen-Splits klassifizieren: Kategorie (Typ 12/13) vs. Konto; Aktien/ETF (Typ 15) gesondert.
        List<String[]> categorySplits = new ArrayList<>();
        List<String[]> nonCatCounters = new ArrayList<>();
        for (String[] s : splits) {
            if (s == own) {
                continue;
            }
            int type = doc.accountTypeOf(s[0]);
            if (type == 12 || type == 13) {
                categorySplits.add(s);
            } else {
                nonCatCounters.add(s);
            }
        }

        // Wertpapierkauf/-verkauf: Ein Aktien-/ETF-Split (Typ 15) mit echtem Wert bedeutet, dass Geld
        // zwischen diesem Konto und dem Wertpapier fließt – das ist eine Umbuchung, auch wenn zusätzlich
        // eine kleine Gebühren-Kategorie (z. B. „Sonstiges:Bankgebühren") mitläuft. Ohne diese Regel würde
        // der komplette Kaufbetrag fälschlich als Gebühr/Ausgabe (bzw. beim Verkauf als Einnahme) gezählt.
        // Die Gebühr fließt in die Umbuchung ein (kein separater Ausgabe-Eintrag). Dividenden haben einen
        // Wertpapier-Split mit Wert 0 und bleiben dadurch normale Einnahmen.
        String[] stockSplit = null;
        for (String[] s : nonCatCounters) {
            if (doc.accountTypeOf(s[0]) == 15 && amountOf(s, commodity) != 0) {
                stockSplit = s;
                break;
            }
        }
        if (stockSplit != null) {
            b.isTransfer = true;
            b.transferAccount = orEmpty(doc.accountNameById(stockSplit[0])).trim();
            b.category = "";
            String payeeId = !own[2].isEmpty() ? own[2] : stockSplit[2];
            b.payee = payeeId.isEmpty() ? "" : orEmpty(doc.payeeName(payeeId));
            // In der Umbuchung versteckte Ausgaben/Einnahmen (Gebühr/Steuer): separat sichern, damit sie in
            // den Kategorien-Auswertungen auftauchen, ohne die Umbuchung/Buchungsliste zu verändern.
            for (String[] cs : categorySplits) {
                long catValue = amountOf(cs, commodity);   // Ausgabe-Kategorie in kMyMoney: > 0
                if (catValue == 0) {
                    continue;
                }
                if (b.analysisExtras == null) {
                    b.analysisExtras = new ArrayList<>();
                }
                b.analysisExtras.add(new AnalysisExtra(accountName, orEmpty(doc.categoryPath(cs[0])),
                        Math.abs(catValue), catValue < 0, b.createdAt));
            }
            return b;
        }

        // Umbuchung zwischen zwei Konten: genau ein Nicht-Kategorie-Gegenkonto, keine Kategorien.
        if (categorySplits.isEmpty() && nonCatCounters.size() == 1) {
            String[] counterSplit = nonCatCounters.get(0);
            b.isTransfer = true;
            b.transferAccount = orEmpty(doc.accountNameById(counterSplit[0])).trim();
            b.category = "";
            // Empfänger der Umbuchung aus dem Payee-Attribut der Splits (kein Kontoname-Fallback).
            String payeeId = !own[2].isEmpty() ? own[2] : counterSplit[2];
            b.payee = payeeId.isEmpty() ? "" : orEmpty(doc.payeeName(payeeId));
            return b;
        }

        // Splitbuchung: mehrere Kategorie-Splits → Kategorie-Teile aufbauen (Umkehr des Exports).
        if (categorySplits.size() >= 2) {
            b.parts = new ArrayList<>();
            for (String[] cs : categorySplits) {
                long catValue = amountOf(cs, commodity);
                long partial = b.isIncome ? -catValue : catValue;
                // Kategorietyp aus der (noch eindeutigen) kMyMoney-Konto-ID dieses Splits – Typ 12 =
                // Einnahme, 13 = Ausgabe (categorySplits ist bereits auf 12/13 gefiltert).
                boolean catIsIncome = doc.accountTypeOf(cs[0]) == 12;
                b.parts.add(new BookingSplit(0, orEmpty(doc.categoryPath(cs[0])), partial, catIsIncome));
            }
            // Kopf-Kategorie = betragsmäßig größter Teil (nicht einfach der erste). Sonst bekäme z. B. eine
            // Dividende mit kleiner Gebühr fälschlich „Bankgebühren" als Haupt-Kategorie, wenn der
            // Gebühren-Split in der Datei zuerst steht.
            BookingSplit main = null;
            for (BookingSplit p : b.parts) {
                if (main == null || Math.abs(p.amountCents) > Math.abs(main.amountCents)) {
                    main = p;
                }
            }
            b.category = main == null ? "" : main.category;
            b.categoryIsIncome = main == null ? null : main.categoryIsIncome;
            b.payee = resolveImportPayee(own, categorySplits.get(0), splits);
            return b;
        }

        // Einzelbuchung: Kategorie/Empfänger aus dem ersten Gegen-Split (bisheriges Verhalten).
        String[] counter = null;
        for (String[] s : splits) {
            if (s != own) {
                counter = s;
                break;
            }
        }
        b.category = counter == null ? "" : orEmpty(doc.categoryPath(counter[0]));
        if (counter != null) {
            int counterType = doc.accountTypeOf(counter[0]);
            // Kategorietyp aus der Konto-ID (eindeutig); nur setzen, wenn es wirklich eine Kategorie ist
            // (12=Einnahme, 13=Ausgabe) – ein Nicht-Kategorie-Gegenkonto bleibt hier ohnehin unerreicht,
            // da Umbuchungen weiter oben schon zurückkehren.
            b.categoryIsIncome = (counterType == 12 || counterType == 13) ? counterType == 12 : null;
        }
        b.payee = resolveImportPayee(own, counter, splits);
        return b;
    }

    /**
     * Empfänger einer importierten Buchung: aus dem eigenen bzw. Gegen-Split; fällt auf den Namen des
     * Aktien-/ETF-Kontos (Typ 15) bzw. eines Nicht-Kategorie-Gegenkontos zurück (statt „—").
     */
    private String resolveImportPayee(String[] own, String[] counter, List<String[]> splits) {
        String payeeId = !own[2].isEmpty() ? own[2] : (counter == null ? "" : counter[2]);
        String payee = payeeId.isEmpty() ? "" : orEmpty(doc.payeeName(payeeId));
        if (!payee.isEmpty()) {
            return payee;
        }
        // 1) Aktien-/ETF-Konto (Typ 15) – deckt Kauf, Kauf-mit-Gebühr und Dividende ab.
        for (String[] s : splits) {
            if (doc.accountTypeOf(s[0]) == 15) {
                String stockName = doc.accountNameById(s[0]);
                if (stockName != null && !stockName.trim().isEmpty()) {
                    return stockName.trim();
                }
            }
        }
        // 2) Nicht-Kategorie-Gegenkonto → dessen Name.
        if (counter != null) {
            int t = doc.accountTypeOf(counter[0]);
            if (t != 12 && t != 13) {
                return orEmpty(doc.accountNameById(counter[0])).trim();
            }
        }
        return "";
    }

    /**
     * Liest den (nach {@code <TRANSACTIONS>} stehenden) {@code <SCHEDULES>}-Block: je {@code <SCHEDULED_TX>}
     * die eingebettete Transaktion. Klassifikation (Einzahlung/Auszahlung/Umbuchung, Betrag, Empfänger,
     * Kategorie/Zielkonto) über die vorhandene {@link #toBooking}-Logik; nächste Fälligkeit = postdate.
     */
    public List<ScheduledTransaction> scheduledTransactions() throws IOException {
        List<ScheduledTransaction> out = new ArrayList<>();
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            // SCHEDULES steht hinter dem Hauptbuch – der Rest reicht, das spart einen vollen Durchlauf.
            parser.setInput(new StringReader(doc.xmlTail()));
            int event = parser.getEventType();
            boolean inSchedules = false;
            String schedId = null;
            String schedName = null;
            String schedEnd = "";
            int schedOcc = 0;
            int schedMult = 1;
            String postdate = null;
            String entrydate = null;
            String txMemo = "";
            String txCommodity = "";
            List<String[]> splits = null;
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("SCHEDULES".equals(tag)) {
                        inSchedules = true;
                    } else if (inSchedules && "SCHEDULED_TX".equals(tag)) {
                        schedId = orEmpty(parser.getAttributeValue(null, "id"));
                        schedName = orEmpty(parser.getAttributeValue(null, "name"));
                        schedEnd = orEmpty(parser.getAttributeValue(null, "endDate"));
                        schedOcc = parseIntOr(parser.getAttributeValue(null, "occurence"), 0);
                        schedMult = parseIntOr(parser.getAttributeValue(null, "occurenceMultiplier"), 1);
                        postdate = null;
                        entrydate = null;
                        txMemo = "";
                        splits = null;
                    } else if (inSchedules && "TRANSACTION".equals(tag)) {
                        postdate = parser.getAttributeValue(null, "postdate");
                        entrydate = parser.getAttributeValue(null, "entrydate");
                        txMemo = orEmpty(parser.getAttributeValue(null, "memo"));
                        txCommodity = orEmpty(parser.getAttributeValue(null, "commodity")).trim();
                        splits = new ArrayList<>();
                    } else if (inSchedules && "SPLIT".equals(tag) && splits != null) {
                        splits.add(newSplit(parser));
                    } else if (inSchedules && "TAG".equals(tag)) {
                        addTagId(splits, parser.getAttributeValue(null, "id"));
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    String tag = parser.getName();
                    if ("SCHEDULES".equals(tag)) {
                        break; // Block vollständig
                    } else if (inSchedules && "SCHEDULED_TX".equals(tag)) {
                        ScheduledTransaction st = buildScheduled(schedId, schedName, schedEnd, schedOcc,
                                schedMult, postdate, entrydate, txMemo, txCommodity, splits);
                        if (st != null) {
                            out.add(st);
                        }
                        schedId = null;
                        schedName = null;
                        splits = null;
                    }
                }
                event = parser.next();
            }
        } catch (XmlPullParserException e) {
            throw new IOException(ctx.getString(de.spahr.ausgaben.R.string.err_kmy_read), e);
        }
        return out;
    }

    /**
     * Baut aus der Transaktion einer geplanten Buchung den DB-Eintrag (Wiederverwendung von toBooking).
     * Nur <b>aktive</b> Planungen: mit gesetztem {@code endDate} in der Vergangenheit werden übersprungen.
     */
    private ScheduledTransaction buildScheduled(String schedId, String name, String endDate,
                                                int occurrence, int occurrenceMultiplier, String postdate,
                                                String entrydate, String txMemo, String commodity,
                                                List<String[]> splits) {
        if (splits == null || splits.isEmpty()) {
            return null;
        }
        // Nur aktive Planungen: endDate gesetzt und vor heute → abgelaufen, überspringen.
        long endMs = de.spahr.ausgaben.export.KmyDocument.parseKmyDate(endDate);
        if (endMs >= 0 && endMs < System.currentTimeMillis()) {
            return null;
        }
        // Primäres Konto: erster Split auf einem echten Konto (nicht Kategorie 12/13, nicht Aktie 15).
        String primaryId = null;
        for (String[] s : splits) {
            int t = doc.accountTypeOf(s[0]);
            if (t != 12 && t != 13 && t != 15) {
                primaryId = s[0];
                break;
            }
        }
        if (primaryId == null) {
            return null; // kein Kontobezug
        }
        String primaryName = orEmpty(doc.accountNameById(primaryId)).trim();
        Booking b = toBooking(primaryId, primaryName, postdate, entrydate, txMemo, commodity, splits);
        if (b == null) {
            return null;
        }
        int kind = b.isTransfer ? ScheduledTransaction.KIND_TRANSFER
                : (b.isIncome ? ScheduledTransaction.KIND_INCOME : ScheduledTransaction.KIND_EXPENSE);
        String counterparty = b.isTransfer ? orEmpty(b.transferAccount) : orEmpty(b.category);
        ScheduledTransaction st = new ScheduledTransaction(orEmpty(schedId), orEmpty(name).trim(), kind,
                b.createdAt, b.amountCents, orEmpty(b.payee), primaryName, counterparty,
                occurrence, occurrenceMultiplier <= 0 ? 1 : occurrenceMultiplier,
                endMs < 0 ? 0 : endMs);
        // Umbuchungs-Richtung: b.isIncome = Geld fließt IN das Primärkonto (dieses Konto ist „Nach").
        st.incoming = (b.isTransfer && b.isIncome) ? 1 : 0;
        st.tags = b.tags; // toBooking hat die Stichwörter der Splits schon vereinigt
        // Splitbuchung: mehrere Kategorien → Kennzeichen + Kategorie-Teile für die Detail-Maske sichern.
        if (b.parts != null && b.parts.size() >= 2) {
            st.split = 1;
            st.splitParts = new ArrayList<>();
            for (BookingSplit part : b.parts) {
                st.splitParts.add(new ScheduledSplit(0, orEmpty(part.category), part.amountCents));
            }
        }
        return st;
    }

    private static int parseIntOr(String s, int fallback) {
        if (s == null || s.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** KMyMoney-Betrag „num/den" → Cent (gerundet, mit Vorzeichen). */
    private long valueToCents(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0;
        }
        try {
            int slash = value.indexOf('/');
            if (slash < 0) {
                return new BigDecimal(value.trim()).multiply(BigDecimal.valueOf(100))
                        .setScale(0, RoundingMode.HALF_UP).longValueExact();
            }
            BigDecimal num = new BigDecimal(value.substring(0, slash).trim());
            BigDecimal den = new BigDecimal(value.substring(slash + 1).trim());
            return num.multiply(BigDecimal.valueOf(100))
                    .divide(den, 0, RoundingMode.HALF_UP).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return 0;
        }
    }

    /**
     * postdate → Cent-Zeit; bei leerem/ungültigem postdate auf entrydate zurückfallen, sonst {@code 0}
     * (Epoche), damit Buchungen ohne Datum ans Ende (statt mit „heute" an den Anfang) sortiert werden.
     */
    private long parseDate(String postdate, String entrydate) {
        long t = parseOne(postdate);
        if (t >= 0) {
            return t;
        }
        t = parseOne(entrydate);
        return t >= 0 ? t : 0L;
    }

    /** Parst „yyyy-MM-dd" oder liefert -1 bei leer/ungültig. */
    private long parseOne(String s) {
        if (s == null || s.trim().isEmpty()) {
            return -1;
        }
        try {
            return dateFormat.parse(s.trim()).getTime();
        } catch (ParseException e) {
            return -1;
        }
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Feld einer gelesenen Split-Zeile, das die Kennungen ihrer Stichwörter sammelt. */
    private static final int SPLIT_TAGS = 5;
    /** Feld einer gelesenen Split-Zeile mit ihrem {@code reconcileflag}. */
    private static final int SPLIT_RECONCILE = 6;
    /** {@code reconcileflag} eines abgeglichenen Splits; „1" ist nur „geklärt" und sperrt nichts. */
    static final String RECONCILED = "2";

    /** Eine Split-Zeile: {@code {account, value, payeeId, memo, shares, tagIds, reconcileflag}}. */
    private static String[] newSplit(XmlPullParser parser) {
        return new String[]{
                orEmpty(parser.getAttributeValue(null, "account")),
                orEmpty(parser.getAttributeValue(null, "value")),
                orEmpty(parser.getAttributeValue(null, "payee")),
                orEmpty(parser.getAttributeValue(null, "memo")),
                orEmpty(parser.getAttributeValue(null, "shares")),
                "",
                orEmpty(parser.getAttributeValue(null, "reconcileflag")).trim()};
    }

    /**
     * Ist die Transaktion in KMyMoney abgeglichen? Es genügt ein einziger Split: der Abgleich hängt am
     * Konto, die Buchung der App steht aber für die ganze Transaktion – wer sie ändert, ändert auch
     * die abgeglichene Seite.
     */
    private static boolean isReconciled(List<String[]> splits) {
        for (String[] s : splits) {
            if (s.length > SPLIT_RECONCILE && RECONCILED.equals(s[SPLIT_RECONCILE])) {
                return true;
            }
        }
        return false;
    }

    /**
     * Merkt eine Stichwort-Kennung am zuletzt gelesenen Split: {@code <TAG>} steht als Kindelement im
     * Split und kommt daher immer nach dessen öffnendem Tag.
     */
    private static void addTagId(List<String[]> splits, String id) {
        if (splits == null || splits.isEmpty() || id == null || id.trim().isEmpty()) {
            return;
        }
        String[] last = splits.get(splits.size() - 1);
        last[SPLIT_TAGS] = last[SPLIT_TAGS].isEmpty()
                ? id.trim()
                : last[SPLIT_TAGS] + "," + id.trim();
    }

    /**
     * Die Stichwörter aller Splits einer Transaktion, zu Namen aufgelöst. Die App führt sie je Buchung,
     * also werden die der einzelnen Splits vereinigt; unbekannte Kennungen fallen weg.
     */
    private String tagsOf(List<String[]> splits) {
        String tags = "";
        if (splits == null) {
            return tags;
        }
        for (String[] s : splits) {
            if (s.length <= SPLIT_TAGS || s[SPLIT_TAGS].isEmpty()) {
                continue;
            }
            for (String id : s[SPLIT_TAGS].split(",")) {
                String name = doc.tagName(id);
                if (name != null) {
                    tags = de.spahr.ausgaben.db.BookingTags.add(tags, name);
                }
            }
        }
        return tags;
    }
}
