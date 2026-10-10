package de.spahr.ausgaben.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.BookingSplit;
import de.spahr.ausgaben.db.ScheduledTransaction;
import de.spahr.ausgaben.db.Security;
import de.spahr.ausgaben.db.SecurityTx;

/**
 * Dieselben Daten, einmal als .kmy und einmal als KMyMoney-Datenbank gespeichert: Der Import muss aus
 * beiden dasselbe liefern – jedes Konto, jede Buchung, Depots, Planungen, Budgets.
 *
 * <p>Das ist die Probe darauf, dass {@link KmySqlite} die Datenbank richtig liest: Als Maßstab dient
 * nicht eine eigene Erwartung, sondern KMyMoneys eigene zweite Schreibweise derselben Daten.</p>
 *
 * <p>Läuft nur, wo beide Dateien liegen ({@code -Dkmy.sqlite=…/test.anon.sqlite}, daneben
 * {@code test.anon.kmy}); sonst wird der Test übersprungen. Die Dateien sind groß und gehören nicht
 * ins Repo.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KmySqliteVergleichTest {

    private final Context ctx = ApplicationProvider.getApplicationContext();

    private static String beschreibe(Booking b) {
        StringBuilder sb = new StringBuilder();
        sb.append(b.createdAt).append('|').append(b.isIncome ? '+' : '-').append(b.amountCents)
                .append('|').append(b.payee).append('|').append(b.category).append('|')
                .append(b.categoryIsIncome).append('|').append(b.isTransfer).append('|')
                .append(b.transferAccount).append('|').append(b.note).append('|').append(stichwoerter(b.tags))
                .append('|').append(b.reconciled);
        if (b.parts != null) {
            for (BookingSplit p : b.parts) {
                sb.append("|[").append(p.category).append(' ').append(p.amountCents).append(' ')
                        .append(p.categoryIsIncome).append(']');
            }
        }
        if (b.analysisExtras != null) {
            sb.append("|extras=").append(b.analysisExtras.size());
        }
        return sb.toString();
    }

    /**
     * Beide Listen müssen dieselben Einträge tragen (Reihenfolge gleichgültig). Gemeldet werden nur die
     * Unterschiede – bei tausenden Buchungen wäre die ganze Liste unlesbar.
     */
    private static void gleich(String was, List<String> ausKmy, List<String> ausDb) {
        List<String> nurKmy = new ArrayList<>(ausKmy);
        List<String> nurDb = new ArrayList<>(ausDb);
        for (String x : ausDb) {
            nurKmy.remove(x);
        }
        for (String x : ausKmy) {
            nurDb.remove(x);
        }
        if (nurKmy.isEmpty() && nurDb.isEmpty()) {
            return;
        }
        Collections.sort(nurKmy);
        Collections.sort(nurDb);
        org.junit.Assert.fail(was + ": " + nurKmy.size() + " nur in der .kmy, " + nurDb.size()
                + " nur in der Datenbank\n  kmy: "
                + nurKmy.subList(0, Math.min(4, nurKmy.size())) + "\n  db:  "
                + nurDb.subList(0, Math.min(4, nurDb.size())));
    }

    /**
     * Die Stichwörter einer Buchung, geordnet. Ihre Reihenfolge am Split kennt nur die .kmy; die
     * Datenbank führt sie als Menge ({@code kmmTagSplits} hat keine Reihenfolge) – das ist KMyMoneys
     * eigener Unterschied zwischen beiden Formaten, kein Lesefehler.
     */
    private static String stichwoerter(String tags) {
        List<String> l = new ArrayList<>(de.spahr.ausgaben.db.BookingTags.parse(tags));
        Collections.sort(l);
        return l.toString();
    }

    private static List<String> sortiert(List<String> l) {
        List<String> out = new ArrayList<>(l);
        Collections.sort(out);
        return out;
    }

    @Test
    public void datenbankUndKmyLiefernDasselbe() throws Exception {
        String pfad = System.getProperty("kmy.sqlite");
        Assume.assumeTrue("ohne -Dkmy.sqlite übersprungen", pfad != null && !pfad.isEmpty());
        File sqlite = new File(pfad);
        File kmy = new File(pfad.replaceAll("\\.[^./]+$", ".kmy"));
        Assume.assumeTrue(sqlite + " fehlt", sqlite.isFile());
        Assume.assumeTrue(kmy + " fehlt", kmy.isFile());

        byte[] roh = Files.readAllBytes(sqlite.toPath());
        assertTrue(KmySqlite.istSqlite(roh));
        KmyDocument ausDb = new KmyDocument(roh, ctx);
        KmyDocument ausKmy = new KmyDocument(Files.readAllBytes(kmy.toPath()), ctx);
        assertTrue(ausDb.istDatenbank());
        assertEquals(KmySqlite.SCHEMA, ausDb.datenbank().version);

        // Stammdaten
        assertEquals(sortiert(ausKmy.accountNames()), sortiert(ausDb.accountNames()));
        assertEquals(sortiert(ausKmy.depotNames()), sortiert(ausDb.depotNames()));
        assertEquals(sortiert(ausKmy.tagNames()), sortiert(ausDb.tagNames()));
        assertEquals(ausKmy.baseCurrency(), ausDb.baseCurrency());
        assertEquals(new TreeMap<>(ausKmy.categoryTypesByPath()), new TreeMap<>(ausDb.categoryTypesByPath()));
        assertEquals(new TreeMap<>(ausKmy.institutionsByAccount()),
                new TreeMap<>(ausDb.institutionsByAccount()));
        assertEquals(sortiert(ausKmy.favoriteAccounts()), sortiert(ausDb.favoriteAccounts()));
        assertEquals(ausKmy.maxTransactionNumber(), ausDb.maxTransactionNumber());
        assertEquals(ausKmy.maxPayeeNumber(), ausDb.maxPayeeNumber());

        KmyImporter a = new KmyImporter(ausKmy, ctx);
        KmyImporter b = new KmyImporter(ausDb, ctx);

        // Buchungen je Konto
        List<String> konten = sortiert(ausKmy.accountNames());
        Map<String, List<Booking>> ba = a.bookingsForAccounts(konten, null);
        Map<String, List<Booking>> bb = b.bookingsForAccounts(konten, null);
        int gesamt = 0;
        for (String konto : konten) {
            List<String> ea = new ArrayList<>();
            for (Booking x : ba.get(konto)) {
                ea.add(beschreibe(x));
            }
            List<String> eb = new ArrayList<>();
            for (Booking x : bb.get(konto)) {
                eb.add(beschreibe(x));
            }
            gleich("Buchungen von " + konto, ea, eb);
            assertEquals("Währung von " + konto, a.currencyOf(konto), b.currencyOf(konto));
            assertEquals("Typ von " + konto, a.accountType(konto), b.accountType(konto));
            gesamt += ea.size();
        }
        assertTrue("nur " + gesamt + " Buchungen verglichen", gesamt > 1000);

        // Depots
        for (String depot : ausKmy.depotNames()) {
            KmyImporter.DepotData da = a.importDepot(depot);
            KmyImporter.DepotData db = b.importDepot(depot);
            List<String> sa = new ArrayList<>();
            for (Security s : da.securities) {
                sa.add(s.kmyId + '|' + s.name + '|' + s.symbol + '|' + s.currency + '|' + s.isin + '|'
                        + s.price + '|' + s.priceDate);
            }
            List<String> sb = new ArrayList<>();
            for (Security s : db.securities) {
                sb.add(s.kmyId + '|' + s.name + '|' + s.symbol + '|' + s.currency + '|' + s.isin + '|'
                        + s.price + '|' + s.priceDate);
            }
            gleich("Wertpapiere in " + depot, sa, sb);
            List<String> ta = new ArrayList<>();
            for (SecurityTx t : da.transactions) {
                ta.add(t.securityKmyId + '|' + t.date + '|' + t.action + '|' + t.shares + '|'
                        + t.amountCents + '|' + t.netCents + '|' + t.feeCents + '|' + t.moneyAccount + '|'
                        + t.parts.size());
            }
            List<String> tb = new ArrayList<>();
            for (SecurityTx t : db.transactions) {
                tb.add(t.securityKmyId + '|' + t.date + '|' + t.action + '|' + t.shares + '|'
                        + t.amountCents + '|' + t.netCents + '|' + t.feeCents + '|' + t.moneyAccount + '|'
                        + t.parts.size());
            }
            gleich("Bewegungen in " + depot, ta, tb);
            assertEquals("Kurse in " + depot, da.prices.size(), db.prices.size());
        }

        // Planungen
        List<String> pa = new ArrayList<>();
        for (ScheduledTransaction s : a.scheduledTransactions()) {
            pa.add(s.kmyId + '|' + s.name + '|' + s.kind + '|' + s.nextDueMs + '|' + s.amountCents + '|'
                    + s.payee + '|' + s.account + '|' + s.counterparty + '|' + s.occurrence + '|'
                    + s.occurrenceMultiplier + '|' + s.endMs + '|' + s.split);
        }
        List<String> pb = new ArrayList<>();
        for (ScheduledTransaction s : b.scheduledTransactions()) {
            pb.add(s.kmyId + '|' + s.name + '|' + s.kind + '|' + s.nextDueMs + '|' + s.amountCents + '|'
                    + s.payee + '|' + s.account + '|' + s.counterparty + '|' + s.occurrence + '|'
                    + s.occurrenceMultiplier + '|' + s.endMs + '|' + s.split);
        }
        gleich("Planungen", pa, pb);

        // Budgets
        assertEquals(sortiert(strings(a.budgetYears())), sortiert(strings(b.budgetYears())));
        for (int jahr : a.budgetYears()) {
            List<String> xa = new ArrayList<>();
            for (KmyDocument.BudgetEntry e : a.budgetEntries(jahr)) {
                xa.add(e.category + '|' + e.isIncome + '|' + e.yearlyCents);
            }
            List<String> xb = new ArrayList<>();
            for (KmyDocument.BudgetEntry e : b.budgetEntries(jahr)) {
                xb.add(e.category + '|' + e.isIncome + '|' + e.yearlyCents);
            }
            gleich("Budget " + jahr, xa, xb);
        }
    }

    private static List<String> strings(List<Integer> l) {
        List<String> out = new ArrayList<>();
        for (Integer i : l) {
            out.add(String.valueOf(i));
        }
        return out;
    }

    private static byte[] echteDatenbank() throws Exception {
        String pfad = System.getProperty("kmy.sqlite");
        Assume.assumeTrue("ohne -Dkmy.sqlite übersprungen", pfad != null && !pfad.isEmpty());
        File sqlite = new File(pfad);
        Assume.assumeTrue(sqlite + " fehlt", sqlite.isFile());
        return Files.readAllBytes(sqlite.toPath());
    }

    /**
     * Die Annahme hinter dem Nachziehen der Kontosalden, an KMyMoneys eigenen Zahlen gemessen: In
     * einer von KMyMoney gespeicherten Datenbank ist {@code balance} jedes Kontos die Summe der
     * {@code shares} seiner Buchungs-Splits, und {@code transactionCount} die Zahl seiner Buchungen.
     */
    @Test
    public void kontosaldenFolgenDenSplits() throws Exception {
        byte[] roh = echteDatenbank();
        String konten = KmyTestDb.frage(ctx, roh,
                "SELECT id, balance, transactionCount FROM kmmAccounts ORDER BY id");
        Map<String, KmyBruch> summe = new TreeMap<>();
        for (String zeile : KmyTestDb.frage(ctx, roh,
                "SELECT accountId, shares FROM kmmSplits WHERE txType = 'N'").split("\n")) {
            String[] t = zeile.split("\\|");
            KmyBruch bisher = summe.get(t[0]);
            summe.put(t[0], (bisher == null ? KmyBruch.NULL : bisher).plus(KmyBruch.lesen(t[1])));
        }
        List<String> falsch = new ArrayList<>();
        int geprueft = 0;
        for (String zeile : konten.split("\n")) {
            String[] t = zeile.split("\\|");
            KmyBruch soll = summe.get(t[0]) == null ? KmyBruch.NULL : summe.get(t[0]);
            if ("∅".equals(t[1])) {
                continue;
            }
            geprueft++;
            if (!soll.equals(KmyBruch.lesen(t[1]))) {
                falsch.add(t[0] + ": Datenbank " + t[1] + ", Splits " + soll);
            }
        }
        assertTrue("nur " + geprueft + " Konten geprüft", geprueft > 50);
        assertEquals("Konten, deren Saldo nicht die Summe ihrer Splits ist", "[]",
                falsch.subList(0, Math.min(6, falsch.size())).toString() );
    }

    /**
     * In eine Kopie der echten Datenbank schreiben: neue Buchung mit neuem Empfänger. Die Gegenprobe
     * muss auch bei 8000 Buchungen aufgehen, und außer den angesagten Zeilen bleibt jede Tabelle gleich.
     */
    @Test
    public void schreibenInDieEchteDatenbank() throws Exception {
        byte[] roh = echteDatenbank();
        KmyDocument d = new KmyDocument(roh, ctx);
        String konto = sortiert(d.accountNames()).get(0);
        Booking b = new Booking();
        b.id = 1;
        b.account = konto;
        b.payee = "KMySync-Probe";
        b.amountCents = 1240;
        b.note = "erste\nzweite Zeile";
        b.createdAt = KmyDocument.parseKmyDate("2026-10-01");
        KmyExporter.Result r = new KmyExporter(d, ctx).build(Collections.singletonList(b),
                Collections.emptyList(), new java.util.HashMap<>());
        assertEquals(Collections.emptyList(), r.skipped);
        KmyExportCheck.pruefen(d.xml(), r.xml, KmyDocument.gzip(r.xml), r.aenderungen);

        byte[] neu = KmySqliteWriter.schreibe(ctx, roh, r.xml, r.aenderungen);
        assertEquals(r.xml, KmyDocument.alsXml(ctx, neu));
        // Auf Wunsch ablegen, um die geschriebene Datenbank in KMyMoney zu öffnen (-Dkmy.sqlite.out=…).
        String ablage = System.getProperty("kmy.sqlite.out");
        if (ablage != null && !ablage.isEmpty()) {
            Files.write(new File(ablage).toPath(), neu);
        }

        String neueId = null;
        for (KmyAenderungen.Absicht ab : r.aenderungen.transaktionen()) {
            neueId = ab.txId;
        }
        for (String tabelle : new String[]{"kmmInstitutions", "kmmTags", "kmmSchedules", "kmmSecurities",
                "kmmCurrencies", "kmmPrices", "kmmBudgetConfig", "kmmKeyValuePairs", "kmmTagSplits",
                "kmmReportConfig", "kmmSchedulePaymentHistory", "kmmCostCenter", "kmmOnlineJobs"}) {
            String alle = "SELECT * FROM " + tabelle;
            assertTrue(tabelle + " verändert",
                    KmyTestDb.frage(ctx, roh, alle).equals(KmyTestDb.frage(ctx, neu, alle)));
        }
        String andereSplits = "SELECT * FROM kmmSplits WHERE transactionId != '" + neueId + "'";
        assertTrue("fremde Splits verändert",
                KmyTestDb.frage(ctx, roh, andereSplits).equals(KmyTestDb.frage(ctx, neu, andereSplits)));
        String andereBuchungen = "SELECT * FROM kmmTransactions WHERE id != '" + neueId + "'";
        assertTrue("fremde Transaktionen verändert", KmyTestDb.frage(ctx, roh, andereBuchungen)
                .equals(KmyTestDb.frage(ctx, neu, andereBuchungen)));
        String andereKonten = "SELECT * FROM kmmAccounts WHERE id != '" + d.accountId(konto) + "'";
        assertTrue("fremde Konten verändert",
                KmyTestDb.frage(ctx, roh, andereKonten).equals(KmyTestDb.frage(ctx, neu, andereKonten)));
        assertEquals("8374|17940|8375", KmyTestDb.frage(ctx, neu,
                "SELECT transactions, splits, hiTransactionId FROM kmmFileInfo"));
        assertEquals("erste\nzweite Zeile", KmyTestDb.frage(ctx, neu,
                "SELECT memo FROM kmmSplits WHERE transactionId = '" + neueId + "'"));
    }
}
