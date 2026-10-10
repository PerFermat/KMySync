package de.spahr.ausgaben.export;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Baut für die Tests eine KMyMoney-Datenbank aus einer der XML-Testdateien – mit dem echten Schema
 * ({@code kmy/sqlite/schema.sql}, ein Abzug aus KMyMoney 5.1.3) und denselben Daten, die KMyMoney
 * beim „Als Datenbank speichern" hineinschriebe.
 *
 * <p>So laufen die Datenbank-Tests auf denselben Fällen wie die XML-Tests, und was der eine Weg
 * liefert, lässt sich am anderen messen.</p>
 */
final class KmyTestDb {

    private KmyTestDb() {
    }

    /** Die Bytes einer Datenbank mit dem Inhalt der Testdatei {@code fixture}. */
    static byte[] ausFixture(Context ctx, String fixture) throws Exception {
        return ausXml(ctx, new String(KmyRobustnessTest.fixture(fixture), StandardCharsets.UTF_8));
    }

    static byte[] ausXml(Context ctx, String xml) throws Exception {
        File f = File.createTempFile("testdb", ".sqlite", ctx.getCacheDir());
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        SQLiteDatabase db = SQLiteDatabase.openDatabase(f.getPath(), null,
                SQLiteDatabase.CREATE_IF_NECESSARY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
        try {
            String schema = new String(KmyRobustnessTest.fixture("sqlite/schema.sql"), StandardCharsets.UTF_8);
            for (String befehl : schema.split(";\\s*\n")) {
                String b = befehl.replaceAll("(?m)^--.*$", "").trim();
                if (!b.isEmpty()) {
                    db.execSQL(b);
                }
            }
            fuellen(db, xml);
        } finally {
            db.close();
        }
        byte[] roh = Files.readAllBytes(f.toPath());
        KmySqlite.entfernen(f);
        return roh;
    }

    private static String a(XmlPullParser p, String name) {
        String v = p.getAttributeValue(null, name);
        return v == null ? "" : v;
    }

    private static String jn(XmlPullParser p, String name) {
        return "1".equals(a(p, name)) ? "Y" : "N";
    }

    private static void fuellen(SQLiteDatabase db, String xml) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new StringReader(xml));
        java.util.ArrayDeque<String> pfad = new java.util.ArrayDeque<>();
        String lastModified = "";
        String baseCurrency = "";
        String fixVersion = "";
        // Worauf sich die nächsten Kindelemente beziehen.
        String txId = null;
        String txType = "N";
        String txPostDate = "";
        int splitNummer = 0;
        String payeeId = null;
        String institutId = null;
        String kvpArt = null;
        String kvpId = null;
        String schedId = null;
        String preisVon = null;
        String preisNach = null;
        int planungsNummer = 0;
        long splits = 0;
        long buchungen = 0;
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG) {
                String n = p.getName();
                String eltern = pfad.peek();
                ContentValues v = new ContentValues();
                if ("LAST_MODIFIED_DATE".equals(n)) {
                    lastModified = a(p, "date");
                } else if ("FIXVERSION".equals(n)) {
                    fixVersion = a(p, "id");
                } else if ("INSTITUTION".equals(n)) {
                    institutId = a(p, "id");
                    v.put("id", institutId);
                    v.put("name", a(p, "name"));
                    v.put("manager", a(p, "manager"));
                    v.put("routingCode", a(p, "sortcode"));
                    db.insertOrThrow("kmmInstitutions", null, v);
                } else if ("PAYEE".equals(n) && "PAYEES".equals(eltern)) {
                    payeeId = a(p, "id");
                    v.put("id", payeeId);
                    v.put("name", a(p, "name"));
                    v.put("reference", a(p, "reference"));
                    v.put("email", a(p, "email"));
                    v.put("matchData", "1".equals(a(p, "usingmatchkey")) ? 2
                            : ("1".equals(a(p, "matchingenabled")) ? 1 : 0));
                    v.put("matchIgnoreCase", jn(p, "matchignorecase"));
                    v.put("matchKeys", a(p, "matchkey"));
                    db.insertOrThrow("kmmPayees", null, v);
                } else if ("ADDRESS".equals(n) && "PAYEE".equals(eltern) && payeeId != null) {
                    v.put("addressStreet", a(p, "street"));
                    v.put("addressCity", a(p, "city"));
                    v.put("addressState", a(p, "state"));
                    v.put("addressZipcode", a(p, "postcode"));
                    v.put("telephone", a(p, "telephone"));
                    db.update("kmmPayees", v, "id = ?", new String[]{payeeId});
                } else if ("ADDRESS".equals(n) && "INSTITUTION".equals(eltern) && institutId != null) {
                    v.put("addressStreet", a(p, "street"));
                    v.put("addressCity", a(p, "city"));
                    v.put("addressZipcode", a(p, "zip"));
                    v.put("telephone", a(p, "telephone"));
                    db.update("kmmInstitutions", v, "id = ?", new String[]{institutId});
                } else if ("TAG".equals(n) && "TAGS".equals(eltern)) {
                    v.put("id", a(p, "id"));
                    v.put("name", a(p, "name"));
                    v.put("closed", jn(p, "closed"));
                    v.put("tagColor", a(p, "tagcolor"));
                    db.insertOrThrow("kmmTags", null, v);
                } else if ("ACCOUNT".equals(n) && "ACCOUNTS".equals(eltern)) {
                    kvpArt = "ACCOUNT";
                    kvpId = a(p, "id");
                    v.put("id", kvpId);
                    v.put("institutionId", a(p, "institution"));
                    v.put("parentId", a(p, "parentaccount"));
                    v.put("lastReconciled", a(p, "lastreconciled"));
                    v.put("lastModified", a(p, "lastmodified"));
                    v.put("openingDate", a(p, "opened"));
                    v.put("accountNumber", a(p, "number"));
                    v.put("accountType", a(p, "type"));
                    v.put("isStockAccount", "15".equals(a(p, "type")) ? "Y" : "N");
                    v.put("accountName", a(p, "name"));
                    v.put("description", a(p, "description"));
                    v.put("currencyId", a(p, "currency"));
                    v.put("balance", "0/1");
                    v.put("balanceFormatted", "0");
                    v.put("transactionCount", 0);
                    db.insertOrThrow("kmmAccounts", null, v);
                } else if ("SECURITY".equals(n) && "SECURITIES".equals(eltern)) {
                    kvpArt = "SECURITY";
                    kvpId = a(p, "id");
                    v.put("id", kvpId);
                    v.put("name", a(p, "name"));
                    v.put("symbol", a(p, "symbol"));
                    v.put("type", a(p, "type").isEmpty() ? "0" : a(p, "type"));
                    v.put("smallestAccountFraction", a(p, "saf"));
                    if (!a(p, "pp").isEmpty()) {
                        v.put("pricePrecision", a(p, "pp"));
                    }
                    v.put("tradingMarket", a(p, "trading-market"));
                    v.put("tradingCurrency", a(p, "trading-currency"));
                    db.insertOrThrow("kmmSecurities", null, v);
                } else if ("PAIR".equals(n) && "KEYVALUEPAIRS".equals(eltern)) {
                    if (pfad.size() == 2) {
                        if ("kmm-baseCurrency".equals(a(p, "key"))) {
                            baseCurrency = a(p, "value");
                        }
                        v.put("kvpType", "STORAGE");
                        v.put("kvpId", "");
                    } else {
                        v.put("kvpType", kvpArt);
                        v.put("kvpId", kvpId);
                    }
                    v.put("kvpKey", a(p, "key"));
                    v.put("kvpData", a(p, "value"));
                    db.insertOrThrow("kmmKeyValuePairs", null, v);
                } else if ("SCHEDULED_TX".equals(n)) {
                    schedId = a(p, "id").isEmpty()
                            ? String.format(java.util.Locale.US, "SCH%06d", ++planungsNummer) : a(p, "id");
                    v.put("id", schedId);
                    v.put("name", a(p, "name"));
                    v.put("type", a(p, "type").isEmpty() ? "1" : a(p, "type"));
                    v.put("occurence", a(p, "occurence").isEmpty() ? "32" : a(p, "occurence"));
                    v.put("occurenceMultiplier",
                            a(p, "occurenceMultiplier").isEmpty() ? "1" : a(p, "occurenceMultiplier"));
                    v.put("paymentType", a(p, "paymentType"));
                    v.put("startDate", a(p, "startDate"));
                    v.put("endDate", a(p, "endDate"));
                    v.put("fixed", jn(p, "fixed"));
                    v.put("lastDayInMonth", jn(p, "lastDayInMonth"));
                    v.put("autoEnter", jn(p, "autoEnter"));
                    v.put("lastPayment", a(p, "lastPayment"));
                    v.put("weekendOption", a(p, "weekendOption").isEmpty() ? "2" : a(p, "weekendOption"));
                    db.insertOrThrow("kmmSchedules", null, v);
                } else if ("TRANSACTION".equals(n)) {
                    boolean planung = "SCHEDULED_TX".equals(eltern);
                    txId = planung ? schedId : a(p, "id");
                    txType = planung ? "S" : "N";
                    txPostDate = a(p, "postdate");
                    splitNummer = 0;
                    v.put("id", txId);
                    v.put("txType", txType);
                    v.put("postDate", txPostDate);
                    v.put("memo", a(p, "memo"));
                    v.put("entryDate", a(p, "entrydate"));
                    v.put("currencyId", a(p, "commodity"));
                    db.insertOrThrow("kmmTransactions", null, v);
                    if (planung) {
                        ContentValues s = new ContentValues();
                        s.put("nextPaymentDue", txPostDate);
                        db.update("kmmSchedules", s, "id = ?", new String[]{schedId});
                    } else {
                        buchungen++;
                    }
                } else if ("SPLIT".equals(n) && txId != null) {
                    v.put("transactionId", txId);
                    v.put("txType", txType);
                    v.put("splitId", splitNummer++);
                    v.put("payeeId", a(p, "payee"));
                    v.put("reconcileDate", a(p, "reconciledate"));
                    v.put("action", a(p, "action"));
                    v.put("reconcileFlag", a(p, "reconcileflag"));
                    v.put("value", a(p, "value"));
                    v.put("shares", a(p, "shares"));
                    v.put("price", a(p, "price"));
                    v.put("memo", a(p, "memo"));
                    v.put("accountId", a(p, "account"));
                    v.put("checkNumber", a(p, "number"));
                    v.put("postDate", txPostDate);
                    v.put("bankId", a(p, "bankid"));
                    db.insertOrThrow("kmmSplits", null, v);
                    splits++;
                } else if ("TAG".equals(n) && "SPLIT".equals(eltern) && txId != null) {
                    v.put("transactionId", txId);
                    v.put("tagId", a(p, "id"));
                    v.put("splitId", splitNummer - 1);
                    db.insertOrThrow("kmmTagSplits", null, v);
                } else if ("CURRENCY".equals(n)) {
                    v.put("ISOcode", a(p, "id"));
                    v.put("name", a(p, "name"));
                    v.put("type", a(p, "type"));
                    v.put("symbolString", a(p, "symbol"));
                    v.put("smallestCashFraction", a(p, "scf"));
                    v.put("smallestAccountFraction", a(p, "saf"));
                    v.put("pricePrecision", a(p, "pp").isEmpty() ? "4" : a(p, "pp"));
                    db.insertOrThrow("kmmCurrencies", null, v);
                } else if ("PRICEPAIR".equals(n)) {
                    preisVon = a(p, "from");
                    preisNach = a(p, "to");
                } else if ("PRICE".equals(n) && preisVon != null) {
                    v.put("fromId", preisVon);
                    v.put("toId", preisNach);
                    v.put("priceDate", a(p, "date"));
                    v.put("price", a(p, "price"));
                    v.put("priceSource", a(p, "source"));
                    db.insertOrThrow("kmmPrices", null, v);
                }
                pfad.push(n);
            } else if (ev == XmlPullParser.END_TAG) {
                pfad.pop();
                String n = p.getName();
                if ("TRANSACTION".equals(n)) {
                    txId = null;
                } else if ("PAYEE".equals(n)) {
                    payeeId = null;
                } else if ("INSTITUTION".equals(n)) {
                    institutId = null;
                }
            }
        }
        // Der Besitzer der Datei steht in KMyMoney als Zeile USER in der Empfänger-Tabelle.
        ContentValues user = new ContentValues();
        user.put("id", KmySqlite.BESITZER);
        user.put("name", "Testnutzer");
        db.insertOrThrow("kmmPayees", null, user);

        ContentValues info = new ContentValues();
        info.put("version", KmySqlite.SCHEMA);
        info.put("lastModified", lastModified);
        info.put("baseCurrency", baseCurrency);
        info.put("fixLevel", fixVersion);
        info.put("transactions", buchungen);
        info.put("splits", splits);
        info.put("updateInProgress", "N");
        info.put("logonUser", "");
        db.insertOrThrow("kmmFileInfo", null, info);
        db.execSQL("UPDATE kmmFileInfo SET payees = (SELECT count(*) FROM kmmPayees), "
                + "accounts = (SELECT count(*) FROM kmmAccounts), "
                + "hiTransactionId = 1 + (SELECT count(*) FROM kmmTransactions WHERE txType = 'N'), "
                + "hiPayeeId = (SELECT count(*) FROM kmmPayees)");
        db.execSQL("UPDATE kmmAccounts SET transactionCount = (SELECT count(DISTINCT transactionId) "
                + "FROM kmmSplits s WHERE s.accountId = kmmAccounts.id AND s.txType = 'N')");
    }
}
