package de.spahr.ausgaben.export;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Liest eine KMyMoney-Datei (gzip-XML oder reines XML) ein und baut daraus die Zuordnungen zwischen
 * den Freitext-Namen der App und den internen KMyMoney-IDs. Wird von {@link KmyExporter} und
 * {@link KmyImporter} gemeinsam genutzt.
 *
 * <p>Konten der Typen 12 (Einnahme) und 13 (Ausgabe) sind Kategorien; Typ 16 ist Eigenkapital.
 * Alle übrigen Konten (Bargeld/Bank/Vermögen …) gelten als wählbare „Konten".</p>
 */
public class KmyDocument implements KmyAbsicht.Konten {

    private static final int TYPE_INCOME = 12;
    private static final int TYPE_EXPENSE = 13;
    private static final int TYPE_EQUITY = 16;
    private static final int TYPE_INVESTMENT = 7; // Depot
    private static final int TYPE_STOCK = 15;     // Wertpapier im Depot

    private final String xml;
    /** Die Quelle ist eine KMyMoney-Datenbank: ihr Abbild samt Zustand; sonst {@code null}. */
    private final KmySqlite.Abbild sqlite;
    /** Lazy: alles hinter dem Hauptbuch, siehe {@link #xmlTail()}. */
    private String xmlTail;

    /** id → einfacher (Blatt-)Kontoname, für alle Konten. */
    private final Map<String, String> accountName = new LinkedHashMap<>();
    /** id → parentaccount-id. */
    private final Map<String, String> accountParent = new LinkedHashMap<>();
    /** id → KMyMoney-Kontotyp. */
    private final Map<String, Integer> accountType = new LinkedHashMap<>();
    /** id → Währungskennzeichen (currency-Attribut, z. B. „EUR"). */
    private final Map<String, String> accountCurrency = new LinkedHashMap<>();
    /** id → Institut-id (institution-Attribut); leer, wenn dem Konto keine Bank zugeordnet ist. */
    private final Map<String, String> accountInstitution = new LinkedHashMap<>();
    /** Institut-id → Name der Bank aus dem INSTITUTIONS-Block. */
    private final Map<String, String> institutionName = new LinkedHashMap<>();
    /** id der in KMyMoney bevorzugten Konten (PAIR „PreferredAccount" im Konto-Block). */
    private final java.util.Set<String> preferredAccounts = new java.util.LinkedHashSet<>();

    /** Anzeigename → id der wählbaren Konten (Reihenfolge = Eingabereihenfolge). */
    private final Map<String, String> selectableAccounts = new LinkedHashMap<>();
    /** kleingeschriebener Kontoname → id (für den Export-Lookup). */
    private final Map<String, String> assetNameToId = new LinkedHashMap<>();
    /** Anzeigename → id der Depots (Investment-Konten, Typ 7). */
    private final Map<String, String> depotAccounts = new LinkedHashMap<>();

    /** Wertpapier-ID (E00000x) → Anzeigedaten aus dem SECURITY-Block. */
    private final Map<String, String[]> securityInfo = new LinkedHashMap<>(); // {name, symbol, currency}
    /** Wertpapier-ID → ISIN aus {@code <PAIR key="kmm-security-id">}; leer, wenn nicht gepflegt. */
    private final Map<String, String> securityIsin = new LinkedHashMap<>();
    /** Wertpapier-ID → Nachkommastellen für Kurse (Attribut {@code pp} am SECURITY). */
    private final Map<String, Integer> securityPricePrecision = new LinkedHashMap<>();
    /** Wertpapier-ID → letzter Kurs {price, dateMillis}. */
    private final Map<String, double[]> securityPrice = new LinkedHashMap<>();
    /** Wertpapier-ID → vollständige Kurshistorie (je Eintrag {price, dateMillis}), zeitlich aufsteigend. */
    private final Map<String, List<double[]>> securityPriceHistory = new LinkedHashMap<>();

    /** Budgetjahr → Liste der Kategorie-Soll-Werte (Jahressumme) aus dem BUDGETS-Block. */
    private final Map<Integer, List<BudgetEntry>> budgetsByYear = new LinkedHashMap<>();

    /** kleingeschriebener Kategorie-Pfad (bzw. Blattname) → id. */
    private final Map<String, String> categoryToId = new LinkedHashMap<>();
    /**
     * Dasselbe je Seite. KMyMoney erlaubt denselben Kategorie-Pfad im Einnahme- und im Ausgabebaum;
     * in {@link #categoryToId} überschreibt dann der eine den anderen.
     */
    private final Map<String, String> incomeCategoryToId = new LinkedHashMap<>();
    private final Map<String, String> expenseCategoryToId = new LinkedHashMap<>();
    /** id → Kategorie-Pfad (für den Import). */
    private final Map<String, String> categoryIdToPath = new LinkedHashMap<>();
    /** Kategorie-Pfad → Typ ({@code true} = Einnahme/Typ 12, {@code false} = Ausgabe/Typ 13). */
    private final Map<String, Boolean> categoryIncomeByPath = new LinkedHashMap<>();
    /** Jede Kategorie der Datei mit ihrer Seite – auch beide, wenn es den Pfad in beiden Bäumen gibt. */
    private final List<de.spahr.ausgaben.db.CategoryType> categoryTypes = new ArrayList<>();

    private final Map<String, String> payeeNameToId = new LinkedHashMap<>();
    private final Map<String, String> payeeIdToName = new LinkedHashMap<>();

    /** Stichwörter aus dem {@code <TAGS>}-Block: id → Name und Name (klein) → id. */
    private final Map<String, String> tagIdToName = new LinkedHashMap<>();
    private final Map<String, String> tagNameToId = new LinkedHashMap<>();

    private long maxTransactionNumber = 0;
    private int maxPayeeNumber = 0;
    /** Anzahl der Buchungen laut {@code <TRANSACTIONS count="…">}; 0 = unbekannt. */
    private int transactionCount = 0;
    /** Basiswährung der Datei aus {@code <PAIR key="kmm-baseCurrency">}; leer, wenn nicht angegeben. */
    private String baseCurrency = "";

    private final android.content.Context ctx;

    public KmyDocument(byte[] raw, android.content.Context context) throws IOException {
        this(raw, context, null);
    }

    /**
     * Wie oben, meldet aber den Fortschritt der (teuren) Aufbereitung: Entpacken plus vier Durchläufe über
     * den entpackten Text. Gemeldet wird jeweils <b>nach</b> einem Teilschritt als {@code done} von
     * {@link #PREPARE_STEPS} – die Phase ist sonst ein minutenlanges schwarzes Loch in der Anzeige.
     */
    public KmyDocument(byte[] raw, android.content.Context context,
                       de.spahr.ausgaben.util.ProgressListener listener) throws IOException {
        this.ctx = de.spahr.ausgaben.i18n.LocaleManager.localizedContext(context);
        // Eine KMyMoney-Datenbank (SQLite) wird als das XML abgebildet, das KMyMoney aus denselben Daten
        // in eine .kmy schriebe – alles Weitere merkt keinen Unterschied. Siehe KmySqlite.
        this.sqlite = KmySqlite.istSqlite(raw) ? KmySqlite.abbilden(context, raw) : null;
        this.xml = sqlite != null ? sqlite.xml : gunzip(raw);
        String badEncoding = declaredNonUtf8Encoding(xml);
        if (badEncoding != null) {
            // Gelesen wird immer als UTF-8 (siehe gunzip); eine Datei, die selbst etwas anderes
            // deklariert, würde sonst still falsch interpretiert statt einen erkennbaren Fehler zu
            // geben.
            throw new IOException(ctx.getString(de.spahr.ausgaben.R.string.err_kmy_wrong_encoding,
                    badEncoding));
        }
        if (!looksLikeKmyXml(xml)) {
            // Sonst liefe das hier in einen nichtssagenden XML-Parserfehler (bzw. gar keinen, weil der
            // Parser einfach nichts findet). Typische Fälle: GPG-verschlüsselte .kmy und SQL-Ablagen.
            throw new IOException(ctx.getString(de.spahr.ausgaben.R.string.err_kmy_not_xml));
        }
        step(listener, 1);
        parseHeader();
        step(listener, 2);
        buildDerivedMaps();
        step(listener, 3);
        parseSecuritiesAndPrices();
        step(listener, 4);
        parseBudgets();
        step(listener, 5);
        scanMaxNumbers();
        step(listener, 6);
    }

    /** Anzahl der Teilschritte beim Aufbereiten (Nenner für die Fortschrittsanzeige). */
    public static final int PREPARE_STEPS = 6;

    private static void step(de.spahr.ausgaben.util.ProgressListener l, int done) {
        if (l != null) {
            l.onProgress(done, PREPARE_STEPS);
        }
    }

    /**
     * Grobprüfung, ob {@code content} überhaupt eine KMyMoney-XML-Datei ist: irgendwo in den ersten
     * Zeilen muss {@code <KMYMONEY-FILE} stehen. Vorher kommen je nach Version XML-Deklaration,
     * DOCTYPE und Kommentare.
     */
    static boolean looksLikeKmyXml(String content) {
        if (content == null) {
            return false;
        }
        String head = content.length() > 4096 ? content.substring(0, 4096) : content;
        return head.contains("<KMYMONEY-FILE");
    }

    private static final Pattern ENCODING_ATTR =
            Pattern.compile("<\\?xml\\b[^>]*\\bencoding=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    /**
     * Deklarierte Kodierung der XML-Deklaration, wenn sie von UTF-8 abweicht – sonst {@code null}.
     * Gelesen wird ({@code gunzip}) immer als UTF-8; die ASCII-Deklaration selbst liest sich dabei auch
     * dann richtig, wenn der restliche Text durch die falsche Annahme verstümmelt wäre (Single-Byte-
     * Kodierungen wie ISO-8859-1 lassen reines ASCII unverändert). Echtes UTF-16/UTF-32 fiele schon vorher
     * durch {@link #looksLikeKmyXml} auf – hier bewusst nicht gesondert behandelt.
     *
     * <p>Ohne {@code Context}, damit auch die Diagnose-Funktion (komplett statisch, siehe
     * {@code Diagnostics.java}) sie ohne Weiteres nutzen kann.</p>
     */
    public static String declaredNonUtf8Encoding(String xml) {
        if (xml == null || xml.isEmpty()) {
            return null;
        }
        Matcher m = ENCODING_ATTR.matcher(xml.length() > 200 ? xml.substring(0, 200) : xml);
        if (!m.find()) {
            return null;
        }
        String enc = m.group(1).trim();
        return enc.isEmpty() || enc.equalsIgnoreCase("UTF-8") ? null : enc;
    }

    /** Anzahl der Buchungen im Hauptbuch laut Datei-Kopf; {@code 0} = unbekannt. */
    public int transactionCount() {
        return transactionCount;
    }

    /**
     * Der Teil der Datei <b>hinter</b> {@code </TRANSACTIONS>} (SCHEDULES, SECURITIES, PRICES, BUDGETS …),
     * eingepackt in ein künstliches Wurzelelement und damit für sich wohlgeformt.
     *
     * <p>Wer nur diese Blöcke braucht, musste bisher vom Dateianfang an durch das komplette Hauptbuch
     * laufen – bei ~10.000 Buchungen der teuerste Teil des Einlesens, und das gleich dreifach
     * ({@code parseSecuritiesAndPrices}, {@code parseBudgets}, {@code KmyImporter.scheduledTransactions}).</p>
     *
     * <p>Fällt auf das ganze Dokument zurück, wenn es keinen Hauptbuch-Endtag gibt (z. B. leere Datei mit
     * {@code <TRANSACTIONS count="0"/>}) – dann ist der Rest ohnehin klein.</p>
     */
    String xmlTail() {
        if (xmlTail == null) {
            final String end = "</TRANSACTIONS>";
            int idx = xml.indexOf(end);
            // In Attributwerten steht "<" escaped, ein falscher Treffer ist damit ausgeschlossen.
            xmlTail = idx < 0 ? xml : "<KMYMONEY-FILE>" + xml.substring(idx + end.length());
        }
        return xmlTail;
    }

    private static int parseIntOr(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ---- Öffentliche Zugriffe ----

    public String xml() {
        return xml;
    }

    /** Stammt dieses Dokument aus einer KMyMoney-Datenbank (SQLite) statt aus einer .kmy-Datei? */
    public boolean istDatenbank() {
        return sqlite != null;
    }

    /** Zustand der Datenbank (Schema-Version, geöffnet von, Schreibvorgang offen); {@code null} bei .kmy. */
    public KmySqlite.Abbild datenbank() {
        return sqlite;
    }

    /**
     * Der Inhalt einer heruntergeladenen Datei als XML, gleich ob sie eine .kmy (gepackt oder nicht)
     * oder eine KMyMoney-Datenbank ist. Für Stellen, die nur den Text brauchen – den Zeilenvergleich
     * nach dem Export etwa.
     */
    public static String alsXml(android.content.Context context, byte[] raw) throws IOException {
        return KmySqlite.istSqlite(raw) ? KmySqlite.abbilden(context, raw).xml : gunzip(raw);
    }

    /** Wählbare Konten (Anzeigenamen) in Dateireihenfolge. */
    public List<String> accountNames() {
        return new ArrayList<>(selectableAccounts.keySet());
    }

    @Override
    public String accountId(String name) {
        return name == null ? null : assetNameToId.get(name.trim().toLowerCase(Locale.GERMANY));
    }

    /** Basiswährung der Datei (z. B. „USD") oder leer, wenn die Datei keine angibt. */
    public String baseCurrency() {
        return baseCurrency;
    }

    /** Währungskennzeichen des Kontos (z. B. „EUR") oder leer, wenn keins hinterlegt ist. */
    public String currencyOfAccount(String name) {
        String id = accountId(name);
        String c = id == null ? null : accountCurrency.get(id);
        return c == null ? "" : c;
    }

    /** Findet die Kategorie-id per vollem Pfad (bevorzugt) oder Blattnamen. */
    @Override
    public String categoryId(String pathOrName) {
        return pathOrName == null ? null : categoryToId.get(pathOrName.trim().toLowerCase(Locale.GERMANY));
    }

    /**
     * Wie {@link #categoryId(String)}, aber mit der Seite, auf der die Kategorie liegen soll
     * ({@code true} = Einnahme, {@code false} = Ausgabe, {@code null} = unbekannt). Gibt es den Namen
     * in beiden Bäumen, entscheidet die Seite. Gibt es ihn nur im anderen, wird der geliefert – ob er
     * zur Buchung passt, beurteilt die Selbstprüfung.
     */
    @Override
    public String categoryId(String pathOrName, Boolean einnahme) {
        if (pathOrName == null) {
            return null;
        }
        String key = pathOrName.trim().toLowerCase(Locale.GERMANY);
        String id = einnahme == null ? null
                : (einnahme ? incomeCategoryToId : expenseCategoryToId).get(key);
        return id != null ? id : categoryToId.get(key);
    }

    public String categoryPath(String id) {
        return categoryIdToPath.get(id);
    }

    /**
     * Kategorie-Pfad → Typ aus der Datei ({@code true} = Einnahme/Typ 12, {@code false} = Ausgabe/Typ 13)
     * für <b>alle</b> Kategorien der Datei (auch ohne Buchungen). Einzige verlässliche Typ-Quelle.
     */
    public Map<String, Boolean> categoryTypesByPath() {
        return new LinkedHashMap<>(categoryIncomeByPath);
    }

    /**
     * Alle Kategorien der Datei, jede mit ihrer Seite. Anders als {@link #categoryTypesByPath()} geht
     * hier nichts verloren, wenn derselbe Pfad im Einnahme- und im Ausgabebaum vorkommt: dann stehen
     * beide da.
     */
    public List<de.spahr.ausgaben.db.CategoryType> categoryTypeList() {
        return new ArrayList<>(categoryTypes);
    }

    /** Die Seite der Kategorie mit dieser Konto-id; {@code null}, wenn es keine Kategorie ist. */
    public Boolean categorySideOf(String id) {
        Integer type = id == null ? null : accountType.get(id);
        return type == null || (type != TYPE_INCOME && type != TYPE_EXPENSE) ? null
                : Boolean.valueOf(type == TYPE_INCOME);
    }

    public String payeeId(String name) {
        return name == null ? null : payeeNameToId.get(name.trim().toLowerCase(Locale.GERMANY));
    }

    public String payeeName(String id) {
        return payeeIdToName.get(id);
    }

    /** Alle Stichwörter der Datei in Dateireihenfolge. */
    public List<String> tagNames() {
        return new ArrayList<>(tagIdToName.values());
    }

    /** Name eines Stichworts zu seiner id (beim Lesen der Splits). */
    public String tagName(String id) {
        return tagIdToName.get(id);
    }

    /** id eines Stichworts zu seinem Namen, oder {@code null}: dann kennt die Datei es nicht. */
    public String tagId(String name) {
        return name == null ? null : tagNameToId.get(name.trim().toLowerCase(Locale.GERMANY));
    }

    /** Anzeigename eines beliebigen Kontos (auch Depot/Aktie/ETF), für Investment-Umbuchungen. */
    public String accountNameById(String id) {
        return accountName.get(id);
    }

    /** KMyMoney-Kontotyp eines Kontos (15 = Aktie/ETF), sonst 0. */
    public int accountTypeOf(String id) {
        Integer t = accountType.get(id);
        return t == null ? 0 : t;
    }

    /** Übergeordnetes Konto (parentaccount) eines Kontos, sonst leer. */
    public String accountParentOf(String id) {
        String p = accountParent.get(id);
        return p == null ? "" : p;
    }

    /** Währungskennzeichen eines beliebigen Kontos (bei Typ 15 = Wertpapier-ID E00000x). */
    public String accountCurrencyOf(String id) {
        String c = accountCurrency.get(id);
        return c == null ? "" : c;
    }

    /**
     * Kontoname → Name des Bankinstituts, für alle wählbaren Konten und alle Depots. Konten ohne
     * hinterlegtes Institut fehlen in der Abbildung – für sie entsteht keine Bank-Kontengruppe.
     */
    public Map<String, String> institutionsByAccount() {
        Map<String, String> out = new LinkedHashMap<>();
        collectInstitutions(selectableAccounts, out);
        collectInstitutions(depotAccounts, out);
        return out;
    }

    /**
     * Namen der in KMyMoney bevorzugten Konten – Grundlage der Kontengruppe „Favoriten". Depots zählen
     * mit: sie sind in der App gewöhnliche Konten und lassen sich ebenso als Favorit kennzeichnen.
     */
    public List<String> favoriteAccounts() {
        List<String> out = new ArrayList<>();
        collectFavorites(selectableAccounts, out);
        collectFavorites(depotAccounts, out);
        return out;
    }

    private void collectFavorites(Map<String, String> nameToId, List<String> out) {
        for (Map.Entry<String, String> e : nameToId.entrySet()) {
            if (preferredAccounts.contains(e.getValue())) {
                out.add(e.getKey());
            }
        }
    }

    private void collectInstitutions(Map<String, String> nameToId, Map<String, String> out) {
        for (Map.Entry<String, String> e : nameToId.entrySet()) {
            String bank = institutionName.get(accountInstitution.get(e.getValue()));
            if (bank != null && !bank.isEmpty()) {
                out.put(e.getKey(), bank);
            }
        }
    }

    /** Anzeigenamen der Depots (Investment-Konten, Typ 7). */
    public List<String> depotNames() {
        return new ArrayList<>(depotAccounts.keySet());
    }

    public String depotId(String name) {
        return name == null ? null : depotAccounts.get(name);
    }

    /** Konto-id des Wertpapiers (Typ 15) unterhalb des Depots; {@code null}, wenn es dort keines gibt. */
    @Override
    public String wertpapierKontoId(String depot, String securityKmyId) {
        String depotId = depotId(depot);
        if (depotId == null || securityKmyId == null) {
            return null;
        }
        for (Map.Entry<String, Integer> e : accountType.entrySet()) {
            if (e.getValue() == TYPE_STOCK && depotId.equals(accountParent.get(e.getKey()))
                    && securityKmyId.equals(accountCurrency.get(e.getKey()))) {
                return e.getKey();
            }
        }
        return null;
    }

    /** Alle Konto-IDs (für den Import über Stock-Konten eines Depots). */
    public java.util.Set<String> allAccountIds() {
        return accountName.keySet();
    }

    /** SECURITY-Anzeigedaten {name, symbol, currency} zur Wertpapier-ID, oder {@code null}. */
    public String[] securityInfo(String kmyId) {
        return securityInfo.get(kmyId);
    }

    /** ISIN eines Wertpapiers, oder leer, wenn sie in KMyMoney nicht gepflegt ist. */
    public String securityIsin(String kmyId) {
        String isin = securityIsin.get(kmyId);
        return isin == null ? "" : isin;
    }

    /** KMyMoneys eigener Standard, wenn ein Wertpapier keine Preisgenauigkeit trägt. */
    public static final int DEFAULT_PRICE_PRECISION = 4;

    /**
     * Nachkommastellen, mit denen KMyMoney die Kurse dieses Wertpapiers führt (Attribut {@code pp} am
     * {@code SECURITY}). Auf diese Stelle wird beim Export gerundet – KMyMoney tut in der eigenen Datei
     * dasselbe. Unbekanntes Wertpapier oder fehlendes Attribut → {@link #DEFAULT_PRICE_PRECISION}.
     */
    public int securityPricePrecision(String kmyId) {
        Integer pp = securityPricePrecision.get(kmyId);
        return pp == null ? DEFAULT_PRICE_PRECISION : pp;
    }

    /** {@code pp} als Stellenzahl; unbrauchbare oder unsinnige Werte → {@code null} (Standard greift). */
    private static Integer parsePrecision(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            // 0 wäre ein ganzzahliger Kurs, mehr als 9 sprengt den long-Nenner in decimalFraction.
            return v >= 0 && v <= 9 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Letzter Kurs {price, dateMillis} zur Wertpapier-ID, oder {@code null}. */
    public double[] securityPrice(String kmyId) {
        return securityPrice.get(kmyId);
    }

    /** Vollständige Kurshistorie (je Eintrag {price, dateMillis}) zur Wertpapier-ID; nie {@code null}. */
    public List<double[]> securityPriceHistory(String kmyId) {
        List<double[]> l = securityPriceHistory.get(kmyId);
        return l != null ? l : new ArrayList<>();
    }

    public long maxTransactionNumber() {
        return maxTransactionNumber;
    }

    public int maxPayeeNumber() {
        return maxPayeeNumber;
    }

    // ---- Parsing ----

    /** Liest PAYEE- und ACCOUNT-Einträge; bricht ab, sobald der TRANSACTIONS-Block beginnt. */
    private void parseHeader() throws IOException {
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(new StringReader(xml));
            int event = parser.getEventType();
            String openAccount = null; // Konto, dessen KEYVALUEPAIRS-Block gerade gelesen wird
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("TRANSACTIONS".equals(tag)) {
                        // KMyMoney schreibt hier die Anzahl der Buchungen – gratis als Nenner für die
                        // Fortschrittsanzeige (der Exporter zählt dasselbe Attribut beim Schreiben hoch).
                        transactionCount = parseIntOr(parser.getAttributeValue(null, "count"), 0);
                        break; // Kopfdaten vollständig
                    } else if ("PAYEE".equals(tag)) {
                        String id = parser.getAttributeValue(null, "id");
                        String name = parser.getAttributeValue(null, "name");
                        if (id != null && name != null) {
                            payeeNameToId.put(name.trim().toLowerCase(Locale.GERMANY), id);
                            payeeIdToName.put(id, name);
                        }
                    } else if ("TAG".equals(tag)) {
                        // Der <TAGS>-Kopfblock steht vor <ACCOUNTS>; die gleichnamigen Verweise in den
                        // Splits liegen jenseits von <TRANSACTIONS>, wo diese Schleife längst abbricht.
                        String id = parser.getAttributeValue(null, "id");
                        String name = parser.getAttributeValue(null, "name");
                        if (id != null && name != null && !name.trim().isEmpty()) {
                            tagIdToName.put(id, name.trim());
                            tagNameToId.put(name.trim().toLowerCase(Locale.GERMANY), id);
                        }
                    } else if ("INSTITUTION".equals(tag)) {
                        // Steht vor dem ACCOUNTS-Block; liefert die Namen der Bank-Kontengruppen.
                        String id = parser.getAttributeValue(null, "id");
                        String name = parser.getAttributeValue(null, "name");
                        if (id != null && name != null && !name.trim().isEmpty()) {
                            institutionName.put(id, name.trim());
                        }
                    } else if ("ACCOUNT".equals(tag)) {
                        String id = parser.getAttributeValue(null, "id");
                        String name = parser.getAttributeValue(null, "name");
                        String parent = parser.getAttributeValue(null, "parentaccount");
                        String type = parser.getAttributeValue(null, "type");
                        String currency = parser.getAttributeValue(null, "currency");
                        String institution = parser.getAttributeValue(null, "institution");
                        if (id != null && name != null) {
                            accountName.put(id, name);
                            accountParent.put(id, parent == null ? "" : parent);
                            accountType.put(id, parseIntSafe(type));
                            accountCurrency.put(id, currency == null ? "" : currency.trim());
                            accountInstitution.put(id, institution == null ? "" : institution.trim());
                        }
                        openAccount = id;
                    } else if ("PAIR".equals(tag) && openAccount != null
                            && "PreferredAccount".equals(parser.getAttributeValue(null, "key"))) {
                        // KMyMoney kennzeichnet bevorzugte Konten so; daraus entsteht die Gruppe
                        // „Favoriten". Ein „No" kommt in der Datei zwar nicht vor, wäre aber kein Favorit.
                        if ("Yes".equalsIgnoreCase(
                                orEmpty(parser.getAttributeValue(null, "value")).trim())) {
                            preferredAccounts.add(openAccount);
                        }
                    }
                } else if (event == XmlPullParser.END_TAG && "ACCOUNT".equals(parser.getName())) {
                    openAccount = null;
                }
                event = parser.next();
            }
        } catch (XmlPullParserException e) {
            throw new IOException(ctx.getString(de.spahr.ausgaben.R.string.err_kmy_read), e);
        }
    }

    /**
     * Liest den (nach TRANSACTIONS stehenden) SECURITIES- und PRICES-Block: Wertpapier-Stammdaten und je
     * Wertpapier den <b>letzten</b> Kurs in seiner Handelswährung.
     */
    private void parseSecuritiesAndPrices() throws IOException {
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(new StringReader(xmlTail()));
            int event = parser.getEventType();
            String curFrom = null;
            String curTo = null;
            String openSecurity = null;   // Wertpapier, dessen KEYVALUEPAIRS gerade gelesen werden
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("SECURITY".equals(tag)) {
                        String id = parser.getAttributeValue(null, "id");
                        if (id != null) {
                            securityInfo.put(id, new String[]{
                                    orEmpty(parser.getAttributeValue(null, "name")).trim(),
                                    orEmpty(parser.getAttributeValue(null, "symbol")).trim(),
                                    orEmpty(parser.getAttributeValue(null, "trading-currency")).trim()});
                            Integer pp = parsePrecision(parser.getAttributeValue(null, "pp"));
                            if (pp != null) {
                                securityPricePrecision.put(id, pp);
                            }
                        }
                        openSecurity = id;
                    } else if ("PAIR".equals(tag) && openSecurity != null
                            && "kmm-security-id".equals(parser.getAttributeValue(null, "key"))) {
                        // KMyMoney legt die ISIN als Schlüssel-Wert-Paar am Wertpapier ab (Feld
                        // „Identifikation"). Sie ist der Anker, an dem die PDF-Auslese eine Abrechnung
                        // dem richtigen Wertpapier zuordnet – ohne Namensvergleich.
                        securityIsin.put(openSecurity,
                                orEmpty(parser.getAttributeValue(null, "value")).trim());
                    } else if ("PAIR".equals(tag) && baseCurrency.isEmpty()
                            && "kmm-baseCurrency".equals(parser.getAttributeValue(null, "key"))) {
                        // Steht im Datei-KEYVALUEPAIRS ganz am Ende; die Konto-Blöcke davor tragen
                        // andere Schlüssel (kmm-id, OpeningBalanceAccount …).
                        baseCurrency = orEmpty(parser.getAttributeValue(null, "value")).trim();
                    } else if ("PRICEPAIR".equals(tag)) {
                        curFrom = parser.getAttributeValue(null, "from");
                        curTo = parser.getAttributeValue(null, "to");
                    } else if ("PRICE".equals(tag) && curFrom != null) {
                        String[] info = securityInfo.get(curFrom);
                        String tc = info == null ? "" : info[2];
                        // Kurs in der Handelswährung des Wertpapiers (bei EUR-Papieren „to=EUR").
                        if (tc.isEmpty() || tc.equalsIgnoreCase(curTo)) {
                            long d = parseKmyDate(parser.getAttributeValue(null, "date"));
                            double p = fractionToDouble(parser.getAttributeValue(null, "price"));
                            if (d >= 0 && p > 0) {
                                double[] prev = securityPrice.get(curFrom);
                                if (prev == null || d >= (long) prev[1]) {
                                    securityPrice.put(curFrom, new double[]{p, d});
                                }
                                securityPriceHistory.computeIfAbsent(curFrom, k -> new ArrayList<>())
                                        .add(new double[]{p, d});
                            }
                        }
                    }
                } else if (event == XmlPullParser.END_TAG && "PRICEPAIR".equals(parser.getName())) {
                    curFrom = null;
                    curTo = null;
                } else if (event == XmlPullParser.END_TAG && "SECURITY".equals(parser.getName())) {
                    openSecurity = null;
                }
                event = parser.next();
            }
        } catch (XmlPullParserException e) {
            throw new IOException(ctx.getString(de.spahr.ausgaben.R.string.err_kmy_read), e);
        }
    }

    /**
     * Ein Kategorie-Soll aus dem KMyMoney-Budget. {@link #yearlyCents} ist die Jahressumme;
     * {@link #monthlyCents} ist bei {@code monthbymonth}-Budgets die monatsgenaue Aufteilung
     * (Länge 12, Index 0 = Januar), sonst {@code null} (Jahr/gleichmäßig).
     */
    public static final class BudgetEntry {
        public final String category;
        public final boolean isIncome;
        public final long yearlyCents;
        public final long[] monthlyCents;
        public BudgetEntry(String category, boolean isIncome, long yearlyCents, long[] monthlyCents) {
            this.category = category;
            this.isIncome = isIncome;
            this.yearlyCents = yearlyCents;
            this.monthlyCents = monthlyCents;
        }
    }

    /** Ergebnis der {@link #budgetLevelResult} – Jahres-Cent + optional monatsgenaue Cents (Länge 12). */
    static final class BudgetLevelResult {
        final long yearlyCents;
        final long[] monthlyCents; // null oder Länge 12
        BudgetLevelResult(long yearlyCents, long[] monthlyCents) {
            this.yearlyCents = yearlyCents;
            this.monthlyCents = monthlyCents;
        }
    }

    /** Monat 1–12 aus einem KMyMoney-Datum „yyyy-MM-dd", sonst 0. */
    static int monthOfKmyDate(String s) {
        if (s == null || s.trim().length() < 7) {
            return 0;
        }
        try {
            int m = Integer.parseInt(s.trim().substring(5, 7));
            return (m >= 1 && m <= 12) ? m : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Wertet die Budget-Perioden einer Kategorie aus (reine Logik, JVM-testbar).
     * @param level      budgetlevel-Attribut („monthly"/„yearly"/„monthbymonth"/"")
     * @param monthCents Cent je Monat (Index 0 = Januar … 11 = Dezember)
     * @param totalCents Summe aller Perioden in Cent (maßgeblich für das Jahr)
     * @param periods    Anzahl gelesener PERIOD-Einträge
     */
    static BudgetLevelResult budgetLevelResult(String level, long[] monthCents, long totalCents,
                                               int periods) {
        if ("monthly".equalsIgnoreCase(level) && periods <= 1) {
            return new BudgetLevelResult(totalCents * 12, null); // ein Monatswert → Jahr = ×12
        }
        int monthsWithValue = 0;
        for (long c : monthCents) {
            if (c != 0) {
                monthsWithValue++;
            }
        }
        boolean perMonth = "monthbymonth".equalsIgnoreCase(level) || periods > 1 || monthsWithValue > 1;
        if (perMonth) {
            long[] m = new long[12];
            System.arraycopy(monthCents, 0, m, 0, 12);
            return new BudgetLevelResult(totalCents, m);
        }
        return new BudgetLevelResult(totalCents, null); // yearly / einzelner Jahreswert
    }

    /** Budgetjahre aus der Datei (aufsteigend nach Reihenfolge). */
    public List<Integer> budgetYears() {
        return new ArrayList<>(budgetsByYear.keySet());
    }

    /** Kategorie-Soll-Werte (Jahressumme) des Budgetjahres. */
    public List<BudgetEntry> budgetEntries(int year) {
        List<BudgetEntry> l = budgetsByYear.get(year);
        return l == null ? new ArrayList<>() : new ArrayList<>(l);
    }

    /**
     * Liest den BUDGETS-Block: je {@code <BUDGET year>} und {@code <ACCOUNT id budgetlevel>} das Soll.
     * {@code monthbymonth} (mehrere {@code <PERIOD start amount>}) wird monatsgenau übernommen, sonst als
     * Jahreswert (bei {@code budgetlevel="monthly"} mit einer Periode ×12). Siehe {@link #budgetLevelResult}.
     */
    private void parseBudgets() throws IOException {
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(new StringReader(xmlTail()));
            int event = parser.getEventType();
            int curYear = 0;
            String curAcctId = null;
            String curLevel = null;
            double curSum = 0;
            double[] curMonth = new double[12];
            int curPeriods = 0;
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("BUDGET".equals(tag)) {
                        curYear = 0;
                        String start = parser.getAttributeValue(null, "start");
                        if (start != null && start.length() >= 4) {
                            try {
                                curYear = Integer.parseInt(start.substring(0, 4));
                            } catch (NumberFormatException ignore) {
                                curYear = 0;
                            }
                        }
                        if (curYear == 0) {
                            String y = parser.getAttributeValue(null, "year");
                            if (y != null) {
                                try {
                                    curYear = Integer.parseInt(y.trim());
                                } catch (NumberFormatException ignore) {
                                    curYear = 0;
                                }
                            }
                        }
                    } else if ("ACCOUNT".equals(tag) && curYear != 0) {
                        curAcctId = parser.getAttributeValue(null, "id");
                        curLevel = orEmpty(parser.getAttributeValue(null, "budgetlevel")).trim();
                        curSum = 0;
                        curMonth = new double[12];
                        curPeriods = 0;
                    } else if ("PERIOD".equals(tag) && curAcctId != null) {
                        double amt = fractionToDouble(parser.getAttributeValue(null, "amount"));
                        curSum += amt;
                        int m = monthOfKmyDate(parser.getAttributeValue(null, "start"));
                        if (m >= 1 && m <= 12) {
                            curMonth[m - 1] += amt;
                        }
                        curPeriods++;
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    String tag = parser.getName();
                    if ("ACCOUNT".equals(tag) && curAcctId != null && curYear != 0) {
                        String path = categoryIdToPath.get(curAcctId);
                        Integer type = accountType.get(curAcctId);
                        if (path != null && type != null) {
                            long totalCents = Math.round(Math.abs(curSum) * 100);
                            long[] monthCents = new long[12];
                            for (int i = 0; i < 12; i++) {
                                monthCents[i] = Math.round(Math.abs(curMonth[i]) * 100);
                            }
                            BudgetLevelResult r = budgetLevelResult(curLevel, monthCents, totalCents,
                                    curPeriods);
                            if (r.yearlyCents > 0) {
                                List<BudgetEntry> list = budgetsByYear.get(curYear);
                                if (list == null) {
                                    list = new ArrayList<>();
                                    budgetsByYear.put(curYear, list);
                                }
                                list.add(new BudgetEntry(path, type == TYPE_INCOME, r.yearlyCents,
                                        r.monthlyCents));
                            }
                        }
                        curAcctId = null;
                    } else if ("BUDGET".equals(tag)) {
                        curYear = 0;
                    }
                }
                event = parser.next();
            }
        } catch (XmlPullParserException e) {
            throw new IOException(ctx.getString(de.spahr.ausgaben.R.string.err_kmy_read), e);
        }
    }

    /** KMyMoney-Bruch „num/den" (oder Dezimalzahl) → double. */
    static double fractionToDouble(String v) {
        if (v == null || v.trim().isEmpty()) {
            return 0;
        }
        try {
            int slash = v.indexOf('/');
            if (slash < 0) {
                return Double.parseDouble(v.trim());
            }
            double num = Double.parseDouble(v.substring(0, slash).trim());
            double den = Double.parseDouble(v.substring(slash + 1).trim());
            return den == 0 ? 0 : num / den;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** „yyyy-MM-dd" → ms, sonst -1. */
    static long parseKmyDate(String s) {
        if (s == null || s.trim().isEmpty()) {
            return -1;
        }
        try {
            return new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s.trim()).getTime();
        } catch (ParseException e) {
            return -1;
        }
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private void buildDerivedMaps() {
        // Doppelte Namen kommen in echten Dateien vor („Ausgabe" in zwei Zweigen, „Girokonto" bei zwei
        // Banken). Ohne Vorprüfung überschriebe der zweite Eintrag den ersten in den Name→id-Karten und
        // der Export landete auf dem falschen Konto. Mehrdeutige Namen bekommen deshalb ihren Pfad.
        Map<String, Integer> accountNameCount = new LinkedHashMap<>();
        Map<String, Integer> categoryLeafCount = new LinkedHashMap<>();
        Map<String, Integer> incomeLeafCount = new LinkedHashMap<>();
        Map<String, Integer> expenseLeafCount = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : accountName.entrySet()) {
            String id = e.getKey();
            if (id.startsWith("AStd::")) {
                continue;
            }
            int type = accountType.get(id) == null ? 0 : accountType.get(id);
            String key = e.getValue().trim().toLowerCase(Locale.GERMANY);
            if (type == TYPE_EXPENSE || type == TYPE_INCOME) {
                count(categoryLeafCount, key);
                count(type == TYPE_INCOME ? incomeLeafCount : expenseLeafCount, key);
            } else if (type != TYPE_EQUITY && type != TYPE_STOCK) {
                count(accountNameCount, key); // Konten und Depots teilen sich die Anzeigenamen
            }
        }

        // In der App ist der Kontoname der Schlüssel – zwei Konten gleichen Namens kann sie nicht
        // halten. Der Vorrat der schon vergebenen Bezeichner läuft deshalb über Konten UND Depots.
        java.util.Set<String> takenLabels = new java.util.HashSet<>();

        // Erster Durchgang: die Depots. Sie kommen zuerst, damit bei Namensgleichheit das Depot den
        // schlichten Namen behält und das gleichnamige Konto ausweicht – die Trägerzeile des Depots
        // trägt in der App die Kontenart und darf nicht vom Typ des Namensvetters überschrieben werden.
        for (Map.Entry<String, String> e : accountName.entrySet()) {
            String id = e.getKey();
            if (id.startsWith("AStd::")) {
                continue;
            }
            int type = accountType.get(id) == null ? 0 : accountType.get(id);
            if (type == TYPE_INVESTMENT) {
                String label = displayName(id, e.getValue(), accountNameCount, takenLabels);
                depotAccounts.put(label, id); // Depot – eigener Import-Pfad (Wertpapiere)
                takenLabels.add(label);
            }
        }

        for (Map.Entry<String, String> e : accountName.entrySet()) {
            String id = e.getKey();
            String name = e.getValue();
            int type = accountType.get(id) == null ? 0 : accountType.get(id);
            if (id.startsWith("AStd::")) {
                continue; // Standard-Wurzelkonten (id AStd::Asset/Expense/…) überspringen
            }
            if (type == TYPE_EXPENSE || type == TYPE_INCOME) {
                String path = buildPath(id);
                categoryToId.put(path.toLowerCase(Locale.GERMANY), id);
                String leaf = name.trim().toLowerCase(Locale.GERMANY);
                if (one(categoryLeafCount, leaf)) {
                    categoryToId.put(leaf, id); // Blatt-Fallback nur, wenn er eindeutig ist
                }
                Map<String, String> seite = type == TYPE_INCOME ? incomeCategoryToId : expenseCategoryToId;
                seite.put(path.toLowerCase(Locale.GERMANY), id);
                if (one(type == TYPE_INCOME ? incomeLeafCount : expenseLeafCount, leaf)) {
                    seite.put(leaf, id);
                }
                categoryIdToPath.put(id, path);
                categoryIncomeByPath.put(path, type == TYPE_INCOME);
                categoryTypes.add(new de.spahr.ausgaben.db.CategoryType(path, type == TYPE_INCOME));
            } else if (type != TYPE_INVESTMENT && type != TYPE_EQUITY && type != TYPE_STOCK) {
                // Wertpapier-Unterkonten (Typ 15) NICHT als wählbare Konten führen.
                String label = displayName(id, name, accountNameCount, takenLabels);
                selectableAccounts.put(label, id);
                takenLabels.add(label);
                assetNameToId.put(label.trim().toLowerCase(Locale.GERMANY), id);
            }
        }
    }

    /**
     * Anzeigename eines Kontos: der schlichte Name, solange er in der Datei eindeutig ist, sonst der
     * Pfad („Sparen:Ausgabe"). Sind auch die Pfade gleich (zwei Geschwister gleichen Namens, oder ein
     * Depot und ein Konto gleichen Namens direkt unter der Wurzel), hängt die id an – Hauptsache, jedes
     * Konto bleibt einzeln ansprechbar.
     *
     * @param taken bereits vergebene Bezeichner, über Konten und Depots hinweg
     */
    private String displayName(String id, String name, Map<String, Integer> nameCount,
                               java.util.Set<String> taken) {
        if (one(nameCount, name.trim().toLowerCase(Locale.GERMANY)) && !taken.contains(name)) {
            return name;
        }
        String path = buildPath(id);
        if (path.isEmpty()) {
            path = name;
        }
        return taken.contains(path) ? path + " (" + id + ")" : path;
    }

    private static void count(Map<String, Integer> counts, String key) {
        Integer n = counts.get(key);
        counts.put(key, n == null ? 1 : n + 1);
    }

    private static boolean one(Map<String, Integer> counts, String key) {
        Integer n = counts.get(key);
        return n != null && n == 1;
    }

    /** Baut den Pfad „Haupt:Unter" durch Hochlaufen der parentaccount-Kette (ohne AStd::-Wurzel). */
    private String buildPath(String id) {
        List<String> parts = new ArrayList<>();
        String cur = id;
        int guard = 0;
        while (cur != null && !cur.isEmpty() && !cur.startsWith("AStd::") && guard++ < 64) {
            String name = accountName.get(cur);
            if (name == null) {
                break;
            }
            parts.add(0, name);
            cur = accountParent.get(cur);
        }
        return android.text.TextUtils.join(":", parts);
    }

    /** Höchste vorhandene Transaktions- und Payee-Nummer per Regex über das ganze Dokument. */
    private void scanMaxNumbers() {
        // Überlange Ziffernfolgen kommen in gültigen Dateien nicht vor, dürfen aber auch nicht den
        // ganzen Import mit einer NumberFormatException abbrechen – solche Treffer werden übergangen.
        Matcher tm = Pattern.compile("id=\"T(\\d{1,18})\"").matcher(xml);
        while (tm.find()) {
            long n = Long.parseLong(tm.group(1));
            if (n > maxTransactionNumber) {
                maxTransactionNumber = n;
            }
        }
        Matcher pm = Pattern.compile("id=\"P(\\d{1,9})\"").matcher(xml);
        while (pm.find()) {
            int n = Integer.parseInt(pm.group(1));
            if (n > maxPayeeNumber) {
                maxPayeeNumber = n;
            }
        }
    }

    private static int parseIntSafe(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---- Gzip-Helfer ----

    /** Entpackt gzip (Magic 1f 8b), sonst als UTF-8-Text interpretiert. */
    public static String gunzip(byte[] raw) throws IOException {
        if (raw == null || raw.length == 0) {
            return "";
        }
        boolean gz = raw.length >= 2 && (raw[0] & 0xFF) == 0x1f && (raw[1] & 0xFF) == 0x8b;
        if (!gz) {
            return new String(raw, StandardCharsets.UTF_8);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length * 4));
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Wie {@link #gunzip}, wirft aber nicht: Bricht der gzip-Strom mittendrin ab, kommt zurück, was
     * sich bis dahin entpacken ließ. Für die Anzeige einer defekt angekommenen Datei.
     */
    public static String gunzipSoweitMoeglich(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return "";
        }
        boolean gz = raw.length >= 2 && (raw[0] & 0xFF) == 0x1f && (raw[1] & 0xFF) == 0x8b;
        if (!gz) {
            return new String(raw, StandardCharsets.UTF_8);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length * 4));
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            // Die Bruchstelle. Was bis hierher kam, steht in out.
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Packt XML als gzip (bleibt eine normale .kmy). */
    public static byte[] gzip(String content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }
}
