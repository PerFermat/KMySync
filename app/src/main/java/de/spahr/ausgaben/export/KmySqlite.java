package de.spahr.ausgaben.export;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Liest eine KMyMoney-<b>Datenbank</b> (SQLite) und bildet sie als das XML ab, das KMyMoney aus
 * denselben Daten in eine .kmy-Datei schriebe.
 *
 * <p>KMyMoney kann seine Daten statt als .kmy auch in einer SQLite-Datenbank führen. Import, Export,
 * Selbstprüfung und Zeilenvergleich der App arbeiten aber alle auf dem XML – statt das alles ein
 * zweites Mal für Tabellen zu bauen, wird die Datenbank hier in genau dieses XML übersetzt. Alles
 * dahinter ({@link KmyDocument}, {@link KmyImporter}, {@link KmyExporter}, {@link KmyExportCheck})
 * merkt keinen Unterschied.</p>
 *
 * <p>Die Abbildung ist fest: gleiche Datenbank, gleiches XML, Zeichen für Zeichen. Darauf stützt sich
 * das Schreiben ({@link KmySqliteWriter}) – nach einer Änderung muss die Abbildung der Datenbank
 * genau die Fassung ergeben, die die Selbstprüfung freigegeben hat.</p>
 *
 * <p>Geschrieben wird, wie KMyMoney 5 schreibt: ein Element je Zeile, eine Stelle Einrückung je
 * Ebene. Tabellen und Spalten: {@code mymoneydbdef.cpp} in KMyMoney, Schema-Version 12.</p>
 */
public final class KmySqlite {

    /** Die Schema-Version, gegen die diese Klasse geprüft ist ({@code kmmFileInfo.version}). */
    public static final String SCHEMA = "12";

    /** So beginnt jede SQLite-Datei. */
    private static final byte[] KOPF = "SQLite format 3\u0000".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    /** Die Datenbank als XML, dazu ihr Zustand. */
    public static final class Abbild {
        public final String xml;
        /** {@code kmmFileInfo.version}; leer, wenn die Tabelle leer ist. */
        public final String version;
        /**
         * Wer die Datenbank gerade geöffnet hat ({@code kmmFileInfo.logonUser}), leer = niemand. KMyMoney
         * trägt sich beim Öffnen ein und beim Schließen wieder aus – das Gegenstück zur Sperrdatei
         * neben einer .kmy.
         */
        public final String logonUser;
        /** KMyMoney war mitten in einem Schreibvorgang ({@code updateInProgress} ist nicht „N"). */
        public final boolean updateInProgress;

        Abbild(String xml, String version, String logonUser, boolean updateInProgress) {
            this.xml = xml;
            this.version = version;
            this.logonUser = logonUser;
            this.updateInProgress = updateInProgress;
        }
    }

    private KmySqlite() {
    }

    /** Ist das eine SQLite-Datei? Entschieden wird am Dateikopf, nicht an der Endung. */
    public static boolean istSqlite(byte[] raw) {
        if (raw == null || raw.length < KOPF.length) {
            return false;
        }
        for (int i = 0; i < KOPF.length; i++) {
            if (raw[i] != KOPF[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Legt die Bytes als Zwischendatei im App-Cache ab – SQLite arbeitet auf Dateien, nicht auf Bytes.
     * Der Aufrufer löscht sie wieder.
     */
    static File zwischendatei(Context context, byte[] raw) throws IOException {
        File f = File.createTempFile("kmydb", ".sqlite", context.getCacheDir());
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(raw);
        }
        return f;
    }

    /** Räumt die Zwischendatei samt dem ab, was SQLite daneben gelegt haben könnte. */
    static void entfernen(File f) {
        if (f == null) {
            return;
        }
        for (String anhang : new String[]{"", "-journal", "-wal", "-shm"}) {
            //noinspection ResultOfMethodCallIgnored
            new File(f.getPath() + anhang).delete();
        }
    }

    /** Bildet die Datenbank aus {@code raw} ab. */
    public static Abbild abbilden(Context context, byte[] raw) throws IOException {
        File f = zwischendatei(context, raw);
        try {
            // Ohne NO_LOCALIZED_COLLATORS legte Android seine Tabelle android_metadata in der fremden
            // Datenbank an.
            SQLiteDatabase db = SQLiteDatabase.openDatabase(f.getPath(), null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            try {
                return abbilden(db);
            } finally {
                db.close();
            }
        } catch (android.database.SQLException e) {
            throw new IOException("KMyMoney-Datenbank nicht lesbar: " + e.getMessage(), e);
        } finally {
            entfernen(f);
        }
    }

    // ---- Die Abbildung ----

    /** Schreibt Zeilen mit Einrückung; eine Stelle je Ebene, wie KMyMoney. */
    private static final class Schreiber {
        final StringBuilder sb = new StringBuilder(1 << 20);

        Schreiber zeile(int ebene, String text) {
            for (int i = 0; i < ebene; i++) {
                sb.append(' ');
            }
            sb.append(text).append('\n');
            return this;
        }
    }

    /** {@code name="wert"} mit vorangestelltem Leerzeichen; der Wert maskiert wie beim Export. */
    private static String a(String name, String wert) {
        return " " + name + "=\"" + KmyExporter.esc(wert == null ? "" : wert) + "\"";
    }

    private static String s(Cursor c, String spalte) {
        int i = c.getColumnIndexOrThrow(spalte);
        return c.isNull(i) ? "" : c.getString(i);
    }

    /** {@code Y}/{@code N} der Datenbank als {@code 1}/{@code 0} des XML. */
    private static String jn(Cursor c, String spalte) {
        return "Y".equalsIgnoreCase(s(c, spalte)) ? "1" : "0";
    }

    static Abbild abbilden(SQLiteDatabase db) {
        Schreiber w = new Schreiber();
        String version = "";
        String logonUser = "";
        String lastModified = "";
        String created = "";
        String baseCurrency = "";
        String fixLevel = "";
        boolean update = false;
        try (Cursor c = db.rawQuery("SELECT * FROM kmmFileInfo LIMIT 1", null)) {
            if (c.moveToFirst()) {
                version = s(c, "version");
                logonUser = s(c, "logonUser");
                lastModified = s(c, "lastModified");
                created = s(c, "created");
                baseCurrency = s(c, "baseCurrency");
                fixLevel = s(c, "fixLevel");
                String u = s(c, "updateInProgress");
                update = !u.isEmpty() && !"N".equalsIgnoreCase(u);
            }
        }
        w.zeile(0, "<?xml version=\"1.0\" encoding=\"utf-8\"?>");
        w.zeile(0, "<!DOCTYPE KMYMONEY-FILE>");
        w.zeile(0, "<KMYMONEY-FILE>");
        w.zeile(1, "<FILEINFO>");
        w.zeile(2, "<CREATION_DATE" + a("date", created) + "/>");
        w.zeile(2, "<LAST_MODIFIED_DATE" + a("date", lastModified) + "/>");
        w.zeile(2, "<VERSION id=\"1\"/>");
        w.zeile(2, "<FIXVERSION" + a("id", fixLevel) + "/>");
        w.zeile(1, "</FILEINFO>");

        Map<String, List<String[]>> paare = schluesselWerte(db);
        institute(db, w);
        empfaenger(db, w);
        stichwoerter(db, w);
        konten(db, w, paare);
        Map<String, List<String>> splitStichwoerter = stichwoerterDerSplits(db);
        hauptbuch(db, w, splitStichwoerter);

        w.zeile(1, "<KEYVALUEPAIRS>");
        if (!baseCurrency.isEmpty()) {
            w.zeile(2, "<PAIR key=\"kmm-baseCurrency\"" + a("value", baseCurrency) + "/>");
        }
        for (String[] p : paareVon(paare, "STORAGE", "")) {
            if (!"kmm-baseCurrency".equals(p[0])) {
                w.zeile(2, "<PAIR" + a("key", p[0]) + a("value", p[1]) + "/>");
            }
        }
        w.zeile(1, "</KEYVALUEPAIRS>");

        planungen(db, w, splitStichwoerter);
        wertpapiere(db, w, paare);
        waehrungen(db, w);
        kurse(db, w);
        budgets(db, w);
        w.zeile(0, "</KMYMONEY-FILE>");
        return new Abbild(w.sb.toString(), version, logonUser, update);
    }

    /** Öffnet einen Behälter mit {@code count}; ein leerer steht selbstschließend da, wie in KMyMoney. */
    private static boolean behaelter(Schreiber w, String name, long anzahl) {
        if (anzahl == 0) {
            w.zeile(1, "<" + name + " count=\"0\"/>");
            return false;
        }
        w.zeile(1, "<" + name + " count=\"" + anzahl + "\">");
        return true;
    }

    private static long anzahl(SQLiteDatabase db, String tabelleUndBedingung) {
        try (Cursor c = db.rawQuery("SELECT count(*) FROM " + tabelleUndBedingung, null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    /** Alle Schlüssel-Wert-Paare: „Art|id" → Liste aus {Schlüssel, Wert}, nach Schlüssel geordnet. */
    private static Map<String, List<String[]>> schluesselWerte(SQLiteDatabase db) {
        Map<String, List<String[]>> out = new HashMap<>();
        try (Cursor c = db.rawQuery("SELECT kvpType, kvpId, kvpKey, kvpData FROM kmmKeyValuePairs "
                + "ORDER BY kvpType, kvpId, kvpKey", null)) {
            while (c.moveToNext()) {
                String key = c.getString(0) + "|" + (c.isNull(1) ? "" : c.getString(1));
                List<String[]> l = out.get(key);
                if (l == null) {
                    l = new ArrayList<>();
                    out.put(key, l);
                }
                l.add(new String[]{c.isNull(2) ? "" : c.getString(2), c.isNull(3) ? "" : c.getString(3)});
            }
        }
        return out;
    }

    private static List<String[]> paareVon(Map<String, List<String[]>> paare, String art, String id) {
        List<String[]> l = paare.get(art + "|" + id);
        return l == null ? new ArrayList<>() : l;
    }

    private static void paareSchreiben(Schreiber w, int ebene, List<String[]> paare) {
        w.zeile(ebene, "<KEYVALUEPAIRS>");
        for (String[] p : paare) {
            w.zeile(ebene + 1, "<PAIR" + a("key", p[0]) + a("value", p[1]) + "/>");
        }
        w.zeile(ebene, "</KEYVALUEPAIRS>");
    }

    private static void institute(SQLiteDatabase db, Schreiber w) {
        if (!behaelter(w, "INSTITUTIONS", anzahl(db, "kmmInstitutions"))) {
            return;
        }
        try (Cursor c = db.rawQuery("SELECT * FROM kmmInstitutions ORDER BY id", null)) {
            while (c.moveToNext()) {
                w.zeile(2, "<INSTITUTION" + a("id", s(c, "id")) + a("sortcode", s(c, "routingCode"))
                        + a("manager", s(c, "manager")) + a("name", s(c, "name")) + ">");
                w.zeile(3, "<ADDRESS" + a("city", s(c, "addressCity")) + a("street", s(c, "addressStreet"))
                        + a("zip", s(c, "addressZipcode")) + a("telephone", s(c, "telephone")) + "/>");
                w.zeile(2, "</INSTITUTION>");
            }
        }
        w.zeile(1, "</INSTITUTIONS>");
    }

    /** Die Zeile {@code USER} in {@code kmmPayees} ist der Besitzer der Datei, kein Empfänger. */
    static final String BESITZER = "USER";

    private static void empfaenger(SQLiteDatabase db, Schreiber w) {
        if (!behaelter(w, "PAYEES", anzahl(db, "kmmPayees WHERE id != '" + BESITZER + "'"))) {
            return;
        }
        try (Cursor c = db.rawQuery("SELECT * FROM kmmPayees WHERE id != '" + BESITZER
                + "' ORDER BY id", null)) {
            while (c.moveToNext()) {
                // matchData: 0 = kein Abgleich, 1 = über den Namen, 2 = über Schlüssel.
                String art = s(c, "matchData");
                w.zeile(2, "<PAYEE" + a("id", s(c, "id")) + a("name", s(c, "name"))
                        + a("reference", s(c, "reference")) + a("email", s(c, "email"))
                        + a("matchingenabled", art.isEmpty() || "0".equals(art) ? "0" : "1")
                        + a("usingmatchkey", "2".equals(art) ? "1" : "0")
                        + a("matchignorecase", jn(c, "matchIgnoreCase"))
                        + a("matchkey", s(c, "matchKeys")) + ">");
                w.zeile(3, "<ADDRESS" + a("street", s(c, "addressStreet")) + a("city", s(c, "addressCity"))
                        + a("state", s(c, "addressState")) + a("postcode", s(c, "addressZipcode"))
                        + a("telephone", s(c, "telephone")) + "/>");
                w.zeile(2, "</PAYEE>");
            }
        }
        w.zeile(1, "</PAYEES>");
    }

    private static void stichwoerter(SQLiteDatabase db, Schreiber w) {
        if (!behaelter(w, "TAGS", anzahl(db, "kmmTags"))) {
            return;
        }
        try (Cursor c = db.rawQuery("SELECT * FROM kmmTags ORDER BY id", null)) {
            while (c.moveToNext()) {
                w.zeile(2, "<TAG" + a("id", s(c, "id")) + a("name", s(c, "name"))
                        + a("closed", jn(c, "closed")) + a("tagcolor", s(c, "tagColor")) + "/>");
            }
        }
        w.zeile(1, "</TAGS>");
    }

    private static void konten(SQLiteDatabase db, Schreiber w, Map<String, List<String[]>> paare) {
        if (!behaelter(w, "ACCOUNTS", anzahl(db, "kmmAccounts"))) {
            return;
        }
        try (Cursor c = db.rawQuery("SELECT * FROM kmmAccounts ORDER BY id", null)) {
            while (c.moveToNext()) {
                String id = s(c, "id");
                String kopf = "<ACCOUNT" + a("id", id) + a("parentaccount", s(c, "parentId"))
                        + a("lastreconciled", s(c, "lastReconciled"))
                        + a("lastmodified", s(c, "lastModified"))
                        + a("institution", s(c, "institutionId")) + a("opened", s(c, "openingDate"))
                        + a("number", s(c, "accountNumber")) + a("type", s(c, "accountType"))
                        + a("name", s(c, "accountName")) + a("description", s(c, "description"))
                        + a("currency", s(c, "currencyId"));
                List<String[]> kvp = paareVon(paare, "ACCOUNT", id);
                if (kvp.isEmpty()) {
                    w.zeile(2, kopf + "/>");
                } else {
                    w.zeile(2, kopf + ">");
                    paareSchreiben(w, 3, kvp);
                    w.zeile(2, "</ACCOUNT>");
                }
            }
        }
        w.zeile(1, "</ACCOUNTS>");
    }

    /** „Transaktion|Split-Nummer" → ids der Stichwörter dieses Splits. */
    private static Map<String, List<String>> stichwoerterDerSplits(SQLiteDatabase db) {
        Map<String, List<String>> out = new HashMap<>();
        try (Cursor c = db.rawQuery("SELECT transactionId, splitId, tagId FROM kmmTagSplits "
                + "ORDER BY transactionId, splitId, tagId", null)) {
            while (c.moveToNext()) {
                String key = c.getString(0) + "|" + c.getLong(1);
                List<String> l = out.get(key);
                if (l == null) {
                    l = new ArrayList<>();
                    out.put(key, l);
                }
                l.add(c.getString(2));
            }
        }
        return out;
    }

    /** Die Split-id des XML zur Split-Nummer der Datenbank: dort ab 0 gezählt, im XML {@code S0001}. */
    static String splitId(long nummer) {
        return String.format(java.util.Locale.US, "S%04d", nummer + 1);
    }

    /** Schreibt die Splits einer Transaktion; der Zeiger steht auf ihrem ersten Split oder dahinter. */
    private static void splits(Cursor sp, String txId, Schreiber w, int ebene,
                               Map<String, List<String>> stichwoerter) {
        w.zeile(ebene, "<SPLITS>");
        while (!sp.isAfterLast() && txId.equals(sp.getString(0))) {
            long nummer = sp.getLong(sp.getColumnIndexOrThrow("splitId"));
            String kopf = "<SPLIT" + a("id", splitId(nummer)) + a("payee", s(sp, "payeeId"))
                    + a("reconciledate", s(sp, "reconcileDate")) + a("action", s(sp, "action"))
                    + a("reconcileflag", s(sp, "reconcileFlag")) + a("value", s(sp, "value"))
                    + a("shares", s(sp, "shares")) + a("price", s(sp, "price")) + a("memo", s(sp, "memo"))
                    + a("account", s(sp, "accountId")) + a("number", s(sp, "checkNumber"))
                    + a("bankid", s(sp, "bankId"));
            List<String> tags = stichwoerter.get(txId + "|" + nummer);
            if (tags == null) {
                w.zeile(ebene + 1, kopf + "/>");
            } else {
                w.zeile(ebene + 1, kopf + ">");
                for (String tag : tags) {
                    w.zeile(ebene + 2, "<TAG" + a("id", tag) + "/>");
                }
                w.zeile(ebene + 1, "</SPLIT>");
            }
            sp.moveToNext();
        }
        w.zeile(ebene, "</SPLITS>");
    }

    private static Cursor splitZeiger(SQLiteDatabase db, String txType) {
        // transactionId steht bewusst an erster Stelle: splits() liest sie über den Index 0.
        Cursor c = db.rawQuery("SELECT transactionId, splitId, payeeId, reconcileDate, action, "
                + "reconcileFlag, value, shares, price, memo, accountId, checkNumber, bankId "
                + "FROM kmmSplits WHERE txType = ? ORDER BY transactionId, splitId", new String[]{txType});
        c.moveToFirst();
        return c;
    }

    private static void hauptbuch(SQLiteDatabase db, Schreiber w, Map<String, List<String>> stichwoerter) {
        if (!behaelter(w, "TRANSACTIONS", anzahl(db, "kmmTransactions WHERE txType = 'N'"))) {
            return;
        }
        // Nach id geordnet: neue Buchungen bekommen die nächsthöhere und stehen damit am Ende – dort,
        // wo auch der Export sie anhängt.
        try (Cursor tx = db.rawQuery("SELECT * FROM kmmTransactions WHERE txType = 'N' ORDER BY id", null);
             Cursor sp = splitZeiger(db, "N")) {
            while (tx.moveToNext()) {
                String id = s(tx, "id");
                w.zeile(2, "<TRANSACTION" + a("id", id) + a("postdate", s(tx, "postDate"))
                        + a("memo", s(tx, "memo")) + a("entrydate", s(tx, "entryDate"))
                        + a("commodity", s(tx, "currencyId")) + ">");
                // Splits ohne Transaktion (dürfte es nicht geben) überspringen, statt aus dem Tritt zu
                // kommen.
                while (!sp.isAfterLast() && sp.getString(0).compareTo(id) < 0) {
                    sp.moveToNext();
                }
                splits(sp, id, w, 3, stichwoerter);
                w.zeile(2, "</TRANSACTION>");
            }
        }
        w.zeile(1, "</TRANSACTIONS>");
    }

    private static void planungen(SQLiteDatabase db, Schreiber w, Map<String, List<String>> stichwoerter) {
        if (!behaelter(w, "SCHEDULES", anzahl(db, "kmmSchedules"))) {
            return;
        }
        // Die Transaktion einer Planung steht in kmmTransactions unter der id der Planung, Art „S".
        try (Cursor c = db.rawQuery("SELECT s.*, t.postDate AS txPostDate, t.memo AS txMemo, "
                + "t.entryDate AS txEntryDate, t.currencyId AS txCurrency FROM kmmSchedules s "
                + "LEFT JOIN kmmTransactions t ON t.id = s.id AND t.txType = 'S' ORDER BY s.id", null);
             Cursor sp = splitZeiger(db, "S")) {
            while (c.moveToNext()) {
                String id = s(c, "id");
                w.zeile(2, "<SCHEDULED_TX" + a("id", id) + a("name", s(c, "name")) + a("type", s(c, "type"))
                        + a("occurence", s(c, "occurence"))
                        + a("occurenceMultiplier", s(c, "occurenceMultiplier"))
                        + a("paymentType", s(c, "paymentType")) + a("startDate", s(c, "startDate"))
                        + a("endDate", s(c, "endDate")) + a("fixed", jn(c, "fixed"))
                        + a("lastDayInMonth", jn(c, "lastDayInMonth")) + a("autoEnter", jn(c, "autoEnter"))
                        + a("lastPayment", s(c, "lastPayment")) + a("weekendOption", s(c, "weekendOption"))
                        + ">");
                w.zeile(3, "<PAYMENTS/>");
                w.zeile(3, "<TRANSACTION id=\"\"" + a("postdate", s(c, "txPostDate"))
                        + a("memo", s(c, "txMemo")) + a("entrydate", s(c, "txEntryDate"))
                        + a("commodity", s(c, "txCurrency")) + ">");
                while (!sp.isAfterLast() && sp.getString(0).compareTo(id) < 0) {
                    sp.moveToNext();
                }
                splits(sp, id, w, 4, stichwoerter);
                w.zeile(3, "</TRANSACTION>");
                w.zeile(2, "</SCHEDULED_TX>");
            }
        }
        w.zeile(1, "</SCHEDULES>");
    }

    private static void wertpapiere(SQLiteDatabase db, Schreiber w, Map<String, List<String[]>> paare) {
        if (!behaelter(w, "SECURITIES", anzahl(db, "kmmSecurities"))) {
            return;
        }
        try (Cursor c = db.rawQuery("SELECT * FROM kmmSecurities ORDER BY id", null)) {
            while (c.moveToNext()) {
                String id = s(c, "id");
                String kopf = "<SECURITY" + a("id", id) + a("name", s(c, "name")) + a("symbol", s(c, "symbol"))
                        + a("type", s(c, "type")) + a("rounding-method", s(c, "roundingMethod"))
                        + a("saf", s(c, "smallestAccountFraction")) + a("pp", s(c, "pricePrecision"))
                        + a("trading-currency", s(c, "tradingCurrency"))
                        + a("trading-market", s(c, "tradingMarket"));
                List<String[]> kvp = paareVon(paare, "SECURITY", id);
                if (kvp.isEmpty()) {
                    w.zeile(2, kopf + "/>");
                } else {
                    w.zeile(2, kopf + ">");
                    paareSchreiben(w, 3, kvp);
                    w.zeile(2, "</SECURITY>");
                }
            }
        }
        w.zeile(1, "</SECURITIES>");
    }

    private static void waehrungen(SQLiteDatabase db, Schreiber w) {
        if (!behaelter(w, "CURRENCIES", anzahl(db, "kmmCurrencies"))) {
            return;
        }
        try (Cursor c = db.rawQuery("SELECT * FROM kmmCurrencies ORDER BY ISOcode", null)) {
            while (c.moveToNext()) {
                w.zeile(2, "<CURRENCY" + a("id", s(c, "ISOcode")) + a("name", s(c, "name"))
                        + a("symbol", s(c, "symbolString").trim()) + a("type", s(c, "type"))
                        + a("saf", s(c, "smallestAccountFraction"))
                        + a("pp", s(c, "pricePrecision")) + a("scf", s(c, "smallestCashFraction")) + "/>");
            }
        }
        w.zeile(1, "</CURRENCIES>");
    }

    private static void kurse(SQLiteDatabase db, Schreiber w) {
        long paare = anzahl(db, "(SELECT DISTINCT fromId, toId FROM kmmPrices)");
        if (!behaelter(w, "PRICES", paare)) {
            return;
        }
        try (Cursor c = db.rawQuery("SELECT fromId, toId, priceDate, price, priceSource FROM kmmPrices "
                + "ORDER BY fromId, toId, priceDate", null)) {
            String offen = null;
            while (c.moveToNext()) {
                String paar = c.getString(0) + "|" + c.getString(1);
                if (!paar.equals(offen)) {
                    if (offen != null) {
                        w.zeile(2, "</PRICEPAIR>");
                    }
                    w.zeile(2, "<PRICEPAIR" + a("from", c.getString(0)) + a("to", c.getString(1)) + ">");
                    offen = paar;
                }
                w.zeile(3, "<PRICE" + a("date", s(c, "priceDate")) + a("price", s(c, "price"))
                        + a("source", s(c, "priceSource")) + "/>");
            }
            if (offen != null) {
                w.zeile(2, "</PRICEPAIR>");
            }
        }
        w.zeile(1, "</PRICES>");
    }

    /**
     * Budgets führt die Datenbank schon als XML-Text ({@code <BUDGETS><BUDGET …>…</BUDGET></BUDGETS>}
     * je Zeile). Übernommen wird das Innere, Zeile für Zeile, ohne es zu deuten.
     */
    private static void budgets(SQLiteDatabase db, Schreiber w) {
        if (!behaelter(w, "BUDGETS", anzahl(db, "kmmBudgetConfig"))) {
            return;
        }
        try (Cursor c = db.rawQuery("SELECT XML FROM kmmBudgetConfig ORDER BY id", null)) {
            while (c.moveToNext()) {
                String xml = c.isNull(0) ? "" : c.getString(0);
                for (String zeile : xml.split("\n")) {
                    String t = zeile.trim();
                    if (t.isEmpty() || t.startsWith("<BUDGETS") || t.startsWith("</BUDGETS")
                            || t.startsWith("<?xml") || t.startsWith("<!DOCTYPE")) {
                        continue;
                    }
                    // Die Einrückung des gespeicherten Textes beginnt eine Ebene zu weit links.
                    w.zeile(1, zeile.replace("\r", ""));
                }
            }
        }
        w.zeile(1, "</BUDGETS>");
    }
}
