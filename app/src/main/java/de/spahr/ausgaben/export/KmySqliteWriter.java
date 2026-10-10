package de.spahr.ausgaben.export;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Schreibt die Änderungen eines Exports in eine KMyMoney-Datenbank (SQLite).
 *
 * <p>Der Export selbst arbeitet auf dem XML-Abbild der Datenbank ({@link KmySqlite}): Der
 * {@link KmyExporter} baut daraus die neue Fassung und sagt in {@link KmyAenderungen} an, was er
 * geändert hat, {@link KmyExportCheck} prüft sie. Hier werden genau diese angesagten Änderungen in
 * die Tabellen übertragen – neue, geänderte und gelöschte Transaktionen samt Splits und Stichwörtern,
 * neue Empfänger, weitergestellte Planungen.</p>
 *
 * <p><b>Die Gegenprobe.</b> Am Ende wird die geänderte Datenbank noch einmal als XML abgebildet. Das
 * Ergebnis muss Zeichen für Zeichen die Fassung sein, die die Selbstprüfung freigegeben hat. Stimmt
 * es nicht, wird alles zurückgerollt und nichts hochgeladen. Damit gilt alles, was die Selbstprüfung
 * für die .kmy sichert, auch für die Datenbank: was nicht angesagt war, steht unverändert da.</p>
 *
 * <p><b>Abgeleitete Werte.</b> Anders als die .kmy führt die Datenbank Werte doppelt: Zähler in
 * {@code kmmFileInfo}, Saldo und Buchungszahl je Konto, ausgeschriebene Beträge je Split. KMyMoney
 * liest sie nicht zurück, sondern schreibt sie bei jedem Speichern neu; sie werden hier trotzdem
 * nachgezogen, damit die Datenbank auch für andere Leser stimmig bleibt. Vorbild ist
 * {@code mymoneystoragesql_p.h} in KMyMoney 5.1.3.</p>
 */
public final class KmySqliteWriter {

    private KmySqliteWriter() {
    }

    /**
     * @param roh    die Datenbank, wie sie heruntergeladen wurde
     * @param neuXml die geprüfte neue Fassung ihres Abbilds
     * @return die Bytes der geänderten Datenbank
     * @throws KmyExportCheck.Failed wenn die Gegenprobe nicht aufgeht; geschrieben ist dann nichts
     */
    public static byte[] schreibe(Context context, byte[] roh, String neuXml, KmyAenderungen aenderungen)
            throws IOException {
        File f = KmySqlite.zwischendatei(context, roh);
        try {
            SQLiteDatabase db = SQLiteDatabase.openDatabase(f.getPath(), null,
                    SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            try {
                db.beginTransaction();
                try {
                    uebertragen(db, neuXml, aenderungen);
                    String danach = KmySqlite.abbilden(db).xml;
                    if (!danach.equals(neuXml)) {
                        throw new KmyExportCheck.Failed("Datenbank weicht nach dem Schreiben von der "
                                + "geprüften Fassung ab (" + ersterUnterschied(neuXml, danach) + ")");
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            } finally {
                db.close();
            }
            // Was SQLite beim Schreiben neben die Datei gelegt hat, muss wieder eingearbeitet sein: es
            // geht allein die Datei auf den Server.
            if (new File(f.getPath() + "-wal").length() > 0 || new File(f.getPath() + "-journal").length() > 0) {
                throw new KmyExportCheck.Failed("Datenbank nicht sauber geschlossen");
            }
            return Files.readAllBytes(f.toPath());
        } catch (KmyGliederung.Fehler | android.database.SQLException | NumberFormatException e) {
            throw new KmyExportCheck.Failed("Datenbank nicht schreibbar (" + e.getMessage() + ")", e);
        } finally {
            KmySqlite.entfernen(f);
        }
    }

    /** Die erste Zeile, in der sich beide Fassungen unterscheiden – für die Fehlermeldung. */
    private static String ersterUnterschied(String soll, String ist) {
        String[] a = soll.split("\n");
        String[] b = ist.split("\n");
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            if (!a[i].equals(b[i])) {
                String z = b[i].trim();
                return "Zeile " + (i + 1) + ": " + (z.length() > 120 ? z.substring(0, 120) + "…" : z);
            }
        }
        return a.length + " statt " + b.length + " Zeilen";
    }

    // ---- XML lesen ----

    /** Hebt die Maskierung eines Attributwerts auf – das Gegenstück zu {@code KmyExporter.esc}. */
    static String klar(String wert) {
        if (wert == null) {
            return "";
        }
        if (wert.indexOf('&') < 0) {
            return wert;
        }
        StringBuilder sb = new StringBuilder(wert.length());
        int i = 0;
        while (i < wert.length()) {
            char c = wert.charAt(i);
            int ende = c == '&' ? wert.indexOf(';', i) : -1;
            if (ende < 0) {
                sb.append(c);
                i++;
                continue;
            }
            String name = wert.substring(i + 1, ende);
            switch (name) {
                case "amp": sb.append('&'); break;
                case "lt": sb.append('<'); break;
                case "gt": sb.append('>'); break;
                case "quot": sb.append('"'); break;
                case "apos": sb.append('\''); break;
                default:
                    if (name.startsWith("#x") || name.startsWith("#X")) {
                        sb.appendCodePoint(Integer.parseInt(name.substring(2), 16));
                    } else if (name.startsWith("#")) {
                        sb.appendCodePoint(Integer.parseInt(name.substring(1)));
                    } else {
                        sb.append(wert, i, ende + 1); // unbekannt: stehen lassen
                    }
            }
            i = ende + 1;
        }
        return sb.toString();
    }

    private static String a(Map<String, String> attribute, String name) {
        return klar(attribute.get(name));
    }

    /** Der Kindblock mit dieser id, oder {@code null}. */
    private static KmyGliederung.Element mitId(KmyGliederung.Element behaelter, String id)
            throws KmyGliederung.Fehler {
        if (behaelter == null) {
            return null;
        }
        for (KmyGliederung.Element e : behaelter.inhalt().kinder) {
            if (id.equals(e.id())) {
                return e;
            }
        }
        return null;
    }

    // ---- Übertragen ----

    private static void uebertragen(SQLiteDatabase db, String neuXml, KmyAenderungen aenderungen)
            throws KmyGliederung.Fehler, KmyExportCheck.Failed {
        KmyGliederung.Inhalt wurzel = KmyGliederung.lesen(neuXml).wurzel.inhalt();
        KmyGliederung.Element buch = wurzel.kind("TRANSACTIONS");
        Set<String> konten = new LinkedHashSet<>();

        for (KmyAenderungen.Absicht ab : aenderungen.transaktionen()) {
            kontenVon(db, ab.txId, konten);
            if (ab.art == KmyAenderungen.Art.GELOESCHT) {
                loescheSplits(db, ab.txId);
                db.delete("kmmKeyValuePairs", "kvpType = 'TRANSACTION' AND kvpId = ?", new String[]{ab.txId});
                db.delete("kmmTransactions", "id = ? AND txType = 'N'", new String[]{ab.txId});
                continue;
            }
            KmyGliederung.Element block = mitId(buch, ab.txId);
            if (block == null) {
                throw new KmyExportCheck.Failed("TRANSACTION " + ab.txId + " fehlt in der neuen Fassung");
            }
            Map<String, String> tx = block.attribute();
            ContentValues v = new ContentValues();
            v.put("postDate", a(tx, "postdate"));
            v.put("memo", a(tx, "memo"));
            v.put("entryDate", a(tx, "entrydate"));
            v.put("currencyId", a(tx, "commodity"));
            if (ab.art == KmyAenderungen.Art.NEU) {
                v.put("id", ab.txId);
                v.put("txType", "N");
                db.insertOrThrow("kmmTransactions", null, v);
            } else {
                // Die Zeile bleibt, damit erhalten bleibt, was das Abbild nicht führt (bankId).
                db.update("kmmTransactions", v, "id = ? AND txType = 'N'", new String[]{ab.txId});
                loescheSplits(db, ab.txId);
            }
            schreibeSplits(db, ab.txId, a(tx, "postdate"), block);
            kontenVon(db, ab.txId, konten);
        }

        KmyGliederung.Element empfaenger = wurzel.kind("PAYEES");
        for (String id : aenderungen.neueEmpfaenger()) {
            KmyGliederung.Element block = mitId(empfaenger, id);
            if (block == null) {
                throw new KmyExportCheck.Failed("PAYEE " + id + " fehlt in der neuen Fassung");
            }
            Map<String, String> p = block.attribute();
            KmyGliederung.Element adresse = block.inhalt().kind("ADDRESS");
            Map<String, String> ad = adresse == null ? new HashMap<>() : adresse.attribute();
            ContentValues v = new ContentValues();
            v.put("id", id);
            v.put("name", a(p, "name"));
            v.put("reference", a(p, "reference"));
            v.put("email", a(p, "email"));
            v.put("addressStreet", a(ad, "street"));
            v.put("addressCity", a(ad, "city"));
            v.put("addressZipcode", a(ad, "postcode"));
            v.put("addressState", a(ad, "state"));
            v.put("telephone", a(ad, "telephone"));
            // matchData: 0 = kein Abgleich, 1 = über den Namen, 2 = über Schlüssel.
            v.put("matchData", "1".equals(a(p, "usingmatchkey")) ? 2
                    : ("1".equals(a(p, "matchingenabled")) ? 1 : 0));
            v.put("matchIgnoreCase", "1".equals(a(p, "matchignorecase")) ? "Y" : "N");
            if (!a(p, "matchkey").isEmpty()) {
                v.put("matchKeys", a(p, "matchkey"));
            }
            db.insertOrThrow("kmmPayees", null, v);
        }

        KmyGliederung.Element planungen = wurzel.kind("SCHEDULES");
        for (String id : aenderungen.planungen()) {
            KmyGliederung.Element block = mitId(planungen, id);
            KmyGliederung.Element tx = block == null ? null : block.inhalt().kind("TRANSACTION");
            if (tx == null) {
                throw new KmyExportCheck.Failed("SCHEDULED_TX " + id + " fehlt in der neuen Fassung");
            }
            // Der Export stellt nur die nächste Fälligkeit und die letzte Zahlung weiter.
            String faellig = a(tx.attribute(), "postdate");
            ContentValues s = new ContentValues();
            s.put("lastPayment", leerAlsNull(a(block.attribute(), "lastPayment")));
            s.put("nextPaymentDue", leerAlsNull(faellig));
            db.update("kmmSchedules", s, "id = ?", new String[]{id});
            ContentValues t = new ContentValues();
            t.put("postDate", faellig);
            db.update("kmmTransactions", t, "id = ? AND txType = 'S'", new String[]{id});
            db.update("kmmSplits", t, "transactionId = ? AND txType = 'S'", new String[]{id});
        }

        KmyGliederung.Element info = wurzel.kind("FILEINFO");
        KmyGliederung.Element geaendert = info == null ? null : info.inhalt().kind("LAST_MODIFIED_DATE");
        if (geaendert != null) {
            ContentValues v = new ContentValues();
            v.put("lastModified", a(geaendert.attribute(), "date"));
            db.update("kmmFileInfo", v, null, null);
        }

        for (String konto : konten) {
            kontoNachziehen(db, konto);
        }
        zaehlerNachziehen(db, aenderungen.neueEmpfaenger().size());
    }

    private static String leerAlsNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** Merkt die Konten, die diese Transaktion gerade berührt – ihre Salden ändern sich. */
    private static void kontenVon(SQLiteDatabase db, String txId, Set<String> konten) {
        try (Cursor c = db.rawQuery("SELECT DISTINCT accountId FROM kmmSplits WHERE transactionId = ? "
                + "AND txType = 'N'", new String[]{txId})) {
            while (c.moveToNext()) {
                konten.add(c.getString(0));
            }
        }
    }

    private static void loescheSplits(SQLiteDatabase db, String txId) {
        // Schlüssel-Wert-Paare eines Splits hängen an „Transaktion + Split-Nummer".
        db.execSQL("DELETE FROM kmmKeyValuePairs WHERE kvpType = 'SPLIT' AND kvpId IN "
                + "(SELECT transactionId || splitId FROM kmmSplits WHERE transactionId = ?)",
                new Object[]{txId});
        db.delete("kmmTagSplits", "transactionId = ?", new String[]{txId});
        db.delete("kmmSplits", "transactionId = ? AND txType = 'N'", new String[]{txId});
    }

    private static void schreibeSplits(SQLiteDatabase db, String txId, String postDate,
                                       KmyGliederung.Element block)
            throws KmyGliederung.Fehler, KmyExportCheck.Failed {
        KmyGliederung.Element splits = block.inhalt().kind("SPLITS");
        if (splits == null) {
            return;
        }
        int nummer = 0;
        for (KmyGliederung.Element e : splits.inhalt().kinder) {
            Map<String, String> s = e.attribute();
            // Im Abbild heißt der Split mit der Nummer n „S" + (n+1); der Export zählt lückenlos.
            if (!KmySqlite.splitId(nummer).equals(a(s, "id"))) {
                throw new KmyExportCheck.Failed("TRANSACTION " + txId + ": Split-id " + a(s, "id")
                        + " statt " + KmySqlite.splitId(nummer));
            }
            String konto = a(s, "account");
            int[] genauigkeit = genauigkeit(db, konto);
            KmyBruch wert = KmyBruch.lesen(a(s, "value"));
            KmyBruch kurs = KmyBruch.lesen(a(s, "price"));
            ContentValues v = new ContentValues();
            v.put("transactionId", txId);
            v.put("txType", "N");
            v.put("splitId", nummer);
            v.put("payeeId", a(s, "payee"));
            v.put("reconcileDate", leerAlsNull(a(s, "reconciledate")));
            v.put("action", a(s, "action"));
            v.put("reconcileFlag", a(s, "reconcileflag"));
            v.put("value", a(s, "value"));
            v.put("valueFormatted", wert.dezimal(-1));
            v.put("shares", a(s, "shares"));
            // So schreibt es KMyMoney: in „sharesFormatted" steht der Kurs, nicht die Stückzahl.
            v.put("sharesFormatted", kurs.dezimal(genauigkeit[0]));
            v.put("price", a(s, "price"));
            v.put("priceFormatted", kurs.istNull() ? "" : kurs.dezimal(genauigkeit[1]));
            v.put("memo", a(s, "memo"));
            v.put("accountId", konto);
            v.put("checkNumber", a(s, "number"));
            v.put("postDate", postDate);
            v.put("bankId", a(s, "bankid"));
            db.insertOrThrow("kmmSplits", null, v);
            for (KmyGliederung.Element tag : e.inhalt().kinder) {
                if ("TAG".equals(tag.name)) {
                    ContentValues t = new ContentValues();
                    t.put("transactionId", txId);
                    t.put("tagId", klar(tag.id()));
                    t.put("splitId", nummer);
                    db.insertOrThrow("kmmTagSplits", null, t);
                }
            }
            nummer++;
        }
    }

    /**
     * Nachkommastellen für Stückzahl und Kurs eines Kontos: aus der Währung bzw. dem Wertpapier, in dem
     * es geführt wird ({@code smallestAccountFraction} 100 → 2 Stellen, {@code pricePrecision}).
     */
    private static int[] genauigkeit(SQLiteDatabase db, String kontoId) {
        int[] out = {2, 4};
        try (Cursor c = db.rawQuery("SELECT COALESCE(w.smallestAccountFraction, p.smallestAccountFraction), "
                + "COALESCE(w.pricePrecision, p.pricePrecision) FROM kmmAccounts k "
                + "LEFT JOIN kmmCurrencies w ON w.ISOcode = k.currencyId "
                + "LEFT JOIN kmmSecurities p ON p.id = k.currencyId WHERE k.id = ?", new String[]{kontoId})) {
            if (c.moveToFirst()) {
                if (!c.isNull(0)) {
                    try {
                        long bruchteil = Long.parseLong(c.getString(0).trim());
                        int stellen = 0;
                        while (bruchteil > 1) {
                            bruchteil /= 10;
                            stellen++;
                        }
                        out[0] = stellen;
                    } catch (NumberFormatException ignored) {
                        // bleibt bei zwei Stellen
                    }
                }
                if (!c.isNull(1)) {
                    out[1] = c.getInt(1);
                }
            }
        }
        return out;
    }

    /** Saldo und Buchungszahl eines Kontos aus seinen Splits neu bilden. */
    private static void kontoNachziehen(SQLiteDatabase db, String kontoId) {
        KmyBruch saldo = KmyBruch.NULL;
        try (Cursor c = db.rawQuery("SELECT shares FROM kmmSplits WHERE accountId = ? AND txType = 'N'",
                new String[]{kontoId})) {
            while (c.moveToNext()) {
                saldo = saldo.plus(KmyBruch.lesen(c.getString(0)));
            }
        }
        // KMyMoney schreibt den ausgeschriebenen Saldo mit dem Dezimalzeichen der Oberfläche. Das wird
        // hier von einem vorhandenen Wert abgelesen.
        boolean komma;
        try (Cursor c = db.rawQuery("SELECT count(*) FROM kmmAccounts WHERE balanceFormatted LIKE '%,%'",
                null)) {
            komma = c.moveToFirst() && c.getLong(0) > 0;
        }
        String ausgeschrieben = saldo.dezimal(-1);
        ContentValues v = new ContentValues();
        v.put("balance", saldo.toString());
        v.put("balanceFormatted", komma ? ausgeschrieben.replace('.', ',') : ausgeschrieben);
        db.update("kmmAccounts", v, "id = ?", new String[]{kontoId});
        db.execSQL("UPDATE kmmAccounts SET transactionCount = (SELECT count(DISTINCT transactionId) "
                + "FROM kmmSplits WHERE accountId = ? AND txType = 'N') WHERE id = ?",
                new Object[]{kontoId, kontoId});
    }

    /** Zähler und höchste ids in {@code kmmFileInfo} – KMyMoney führt sie nur noch der Form halber. */
    private static void zaehlerNachziehen(SQLiteDatabase db, int neueEmpfaenger) {
        db.execSQL("UPDATE kmmFileInfo SET "
                + "transactions = (SELECT count(*) FROM kmmTransactions WHERE txType = 'N'), "
                + "splits = (SELECT count(*) FROM kmmSplits), "
                + "payees = COALESCE(payees, 0) + " + neueEmpfaenger + ", "
                + "hiTransactionId = 1 + COALESCE((SELECT max(CAST(substr(id, 2) AS INTEGER)) "
                + "FROM kmmTransactions WHERE txType = 'N' AND id LIKE 'T%'), 0), "
                + "hiPayeeId = 1 + COALESCE((SELECT max(CAST(substr(id, 2) AS INTEGER)) "
                + "FROM kmmPayees WHERE id LIKE 'P%'), 0)");
    }
}
