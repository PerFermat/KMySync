package de.spahr.ausgaben.export;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.BookingSplit;
import de.spahr.ausgaben.db.KmyPendingDelete;
import de.spahr.ausgaben.db.SecurityTx;
import de.spahr.ausgaben.db.ScheduledAdvance;

/**
 * Fügt App-Buchungen als KMyMoney-Transaktionen in die XML-Struktur einer {@link KmyDocument} ein.
 * Konto und Kategorie werden per Namensabgleich aufgelöst (nicht gefunden → übersprungen); ein noch
 * unbekannter, nicht-leerer Empfänger wird als neuer {@code <PAYEE>} angelegt.
 */
public class KmyExporter {

    /** Ergebnis eines Export-Laufs. */
    public static class Result {
        public String xml;
        public final List<Long> writtenIds = new ArrayList<>();
        public final List<String> skipped = new ArrayList<>();
        public int newPayees;
        /** Wie viele Transaktionen in der Datei geändert wurden (Buchungen mit Status „bearbeitet"). */
        public int updated;
        /**
         * Bearbeitete Buchungen, deren Transaktion in der Datei nicht zu finden war. Sie bleiben
         * „bearbeitet"; es wird nichts eingefügt, damit keine Dubletten entstehen.
         */
        public final List<Long> notFound = new ArrayList<>();
    }

    /** Eine zusammengebaute Transaktion: die fertigen Splits samt Währung und Notiz. */
    private static class Built {
        final List<String> splits;
        final String commodity;
        final String memo;

        Built(List<String> splits, String commodity, String memo) {
            this.splits = splits;
            this.commodity = commodity;
            this.memo = memo;
        }
    }

    /** Ergebnis des Entfernens bereits vorhandener, aber lokal gelöschter Transaktionen. */
    public static class DeleteResult {
        public String xml;
        /** In dieser Runde tatsächlich gefundene und entfernte Vormerkungen (per {@link KmyPendingDelete#id}). */
        public final List<Long> resolvedIds = new ArrayList<>();
    }

    /** Ergebnis des Schreibens der in der App erfassten Depot-Bewegungen. */
    public static class SecurityResult {
        public String xml;
        /** Geschriebene Bewegungen ({@link de.spahr.ausgaben.db.SecurityTx#id}). */
        public final List<Long> writtenIds = new ArrayList<>();
        public final List<String> skipped = new ArrayList<>();
    }

    /** Ergebnis des Weiterstellens erledigter/übersprungener geplanter Buchungen. */
    public static class ScheduleResult {
        public String xml;
        /** Erledigte Vormerkungen (per {@link ScheduledAdvance#id}) – geschriebene und verworfene. */
        public final List<Long> resolvedIds = new ArrayList<>();
        /** Tatsächlich in der Datei weitergestellte Vormerkungen (Teilmenge von {@link #resolvedIds}). */
        public final List<Long> writtenIds = new ArrayList<>();
    }

    /** Ende des Hauptbuchs; alles dahinter (z. B. geplante Buchungen) bleibt bei der Suche außen vor. */
    private static final String LEDGER_END = "</TRANSACTIONS>";

    private static final Pattern TX_BLOCK = Pattern.compile("<TRANSACTION\\b.*?</TRANSACTION>",
            Pattern.DOTALL);
    private static final Pattern TX_OPEN_TAG = Pattern.compile("<TRANSACTION\\b[^>]*>");
    private static final Pattern POSTDATE_ATTR = Pattern.compile("\\bpostdate=\"([^\"]*)\"");
    private static final Pattern ID_ATTR = Pattern.compile("\\bid=\"([^\"]*)\"");
    /**
     * Öffnendes Split-Tag, mit und ohne „/": Splits können Kindelemente haben
     * ({@code <SPLIT …><TAG id=…/></SPLIT>}). Mit einem Muster nur für {@code <SPLIT …/>} blieben
     * getaggte Buchungen unauffindbar und damit unlöschbar.
     */
    private static final Pattern SPLIT_TAG = Pattern.compile("<SPLIT\\b[^>]*>");
    private static final Pattern ACCOUNT_ATTR = Pattern.compile("\\baccount=\"([^\"]*)\"");
    private static final Pattern VALUE_ATTR = Pattern.compile("\\bvalue=\"([^\"]*)\"");
    /** Das {@code memo}-Attribut eines Splits – das einzige, das an einer Wertpapier-Buchung wandert. */
    private static final Pattern MEMO_ATTR = Pattern.compile("\\bmemo=\"[^\"]*\"");
    /** Nummernteil einer Transaktions-id ({@code T000000000000000042}). */
    private static final Pattern TX_ID_NUMBER = Pattern.compile("id=\"T(\\d+)\"");

    private final KmyDocument doc;
    private final android.content.Context ctx;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    public KmyExporter(KmyDocument doc, android.content.Context context) {
        this.doc = doc;
        this.ctx = de.spahr.ausgaben.i18n.LocaleManager.localizedContext(context);
    }

    public Result build(List<Booking> bookings) {
        return build(bookings, new HashMap<>());
    }

    public Result build(List<Booking> bookings, Map<Long, List<BookingSplit>> splitsMap) {
        return build(bookings, new ArrayList<>(), splitsMap);
    }

    /**
     * Schreibt die Buchungen in die XML.
     *
     * @param bookings noch nie geschriebene Buchungen – sie kommen als neue Transaktionen hinzu
     * @param edited   bearbeitete Buchungen (Status „bearbeitet"): ihre Transaktion steht schon in der
     *                 Datei und wird an derselben Stelle und mit derselben id ersetzt. Nicht gefundene
     *                 landen in {@link Result#notFound} – dann wird nichts eingefügt, damit keine
     *                 Dublette entsteht.
     */
    public Result build(List<Booking> bookings, List<Booking> edited,
                        Map<Long, List<BookingSplit>> splitsMap) {
        Result result = new Result();
        long[] nextTx = {doc.maxTransactionNumber() + 1};
        int[] nextPayee = {doc.maxPayeeNumber() + 1};

        // In diesem Lauf neu angelegte Empfänger: kleingeschriebener Name → id (zur Wiederverwendung).
        Map<String, String> newPayeeIds = new HashMap<>();
        StringBuilder txFragments = new StringBuilder();
        StringBuilder payeeFragments = new StringBuilder();
        // Bereits geschriebene Umbuchungs-Gruppen (die zweite Seite nur als „exportiert" markieren).
        Set<String> doneTransferGroups = new HashSet<>();
        String today = dateFormat.format(new Date());

        String xml = doc.xml();

        // Erst die bearbeiteten: solange noch keine neue Transaktion eingefügt ist, kann die Suche im
        // Hauptbuch nicht auf einen frisch geschriebenen Block treffen.
        Set<String> replacedTxIds = new HashSet<>();
        for (Booking b : edited) {
            String group = b.transferGroup == null ? "" : b.transferGroup;
            if (!group.isEmpty() && doneTransferGroups.contains(group)) {
                // In der Datei ist die Umbuchung eine Transaktion: die zweite Zeile nur mitmarkieren.
                result.writtenIds.add(b.id);
                continue;
            }
            // Erst suchen, dann entscheiden: eine Wertpapier-Transaktion darf nicht neu gebaut werden,
            // denn Stückzahl, Kurs und Aktion stehen in keiner Buchung – sie wären danach fort.
            Found found = findTransaction(xml, b, replacedTxIds);
            if (found == null) {
                result.notFound.add(b.id);
                continue;
            }
            if (hasSecuritySplit(found.block)) {
                xml = found.replacedBy(patchedSplits(found.block, b));
            } else {
                Built built = b.isTransfer
                        ? buildTransfer(b, result, newPayeeIds, payeeFragments, nextPayee)
                        : buildNormal(b, result, splitsMap, newPayeeIds, payeeFragments, nextPayee);
                if (built == null) {
                    continue; // übersprungen (Konto/Kategorie/Währung), in result.skipped vermerkt
                }
                // Was KMyMoney führt und die App nicht kennt (Abgleich, Aktion, Bankimport), aus der
                // vorhandenen Transaktion übernehmen – sonst fiele es beim Neubau auf die Vorgaben.
                xml = found.replacedBy(withCarriedAttributes(
                        transactionElement(found.txId, dateFor(b.createdAt), today,
                                built.memo, built.commodity, built.splits),
                        found.block));
            }
            result.updated++;
            result.writtenIds.add(b.id);
            if (!group.isEmpty()) {
                doneTransferGroups.add(group);
            }
        }

        int newTx = 0;
        for (Booking b : bookings) {
            String group = b.transferGroup == null ? "" : b.transferGroup;
            if (b.isTransfer && !group.isEmpty() && doneTransferGroups.contains(group)) {
                result.writtenIds.add(b.id); // zweite Seite: nur als exportiert markieren
                continue;
            }
            Built built = b.isTransfer
                    ? buildTransfer(b, result, newPayeeIds, payeeFragments, nextPayee)
                    : buildNormal(b, result, splitsMap, newPayeeIds, payeeFragments, nextPayee);
            if (built == null) {
                continue;
            }
            String txId = String.format(Locale.US, "T%018d", nextTx[0]++);
            txFragments.append(transactionElement(txId, dateFor(b.createdAt), today, built.memo,
                    built.commodity, built.splits));
            newTx++;
            result.writtenIds.add(b.id);
            if (b.isTransfer && !group.isEmpty()) {
                doneTransferGroups.add(group);
            }
        }

        // Gezählt wird, was wirklich als Fragment entstanden ist – nicht die Zahl der Buchungen: eine
        // Umbuchung sind zwei Buchungen, aber eine Transaktion, und bearbeitete kommen nicht hinzu.
        int written = newTx;
        if (written > 0 || result.newPayees > 0) {
            String merged = xml;
            if (result.newPayees > 0) {
                merged = insertIntoBlock(merged, "PAYEES", payeeFragments.toString());
                if (merged != null) {
                    merged = bumpCount(merged, "PAYEES", result.newPayees);
                }
            }
            if (merged != null && written > 0) {
                String withTx = insertIntoBlock(merged, "TRANSACTIONS", txFragments.toString());
                merged = withTx == null ? null : bumpCount(withTx, "TRANSACTIONS", written);
            }
            if (merged == null) {
                // Ohne <PAYEES>- bzw. <TRANSACTIONS>-Block lieber gar nichts schreiben als eine Datei,
                // deren count-Attribut Buchungen behauptet, die nirgends stehen. Auch die bereits
                // ersetzten Blöcke fallen dabei weg – sonst stünde die Änderung in der Datei, ohne dass
                // die Buchung als exportiert markiert würde.
                xml = doc.xml();
                result.writtenIds.clear();
                result.notFound.clear();
                result.updated = 0;
                result.newPayees = 0;
                result.skipped.add(ctx.getString(de.spahr.ausgaben.R.string.err_kmy_read));
            } else {
                xml = merged;
            }
        }
        xml = updateLastModified(xml, today);
        result.xml = xml;
        return result;
    }

    /**
     * Entfernt Transaktionen, die einer lokal gelöschten Buchung entsprechen, aus der XML – für die
     * Buchungs-Lösch-Synchronisierung im kmy-Modus. KMyMoney-Transaktionen tragen aus App-Sicht keine
     * bekannte id (weder von der App selbst importierte noch am Rechner angelegte Buchungen werden bisher
     * mit ihrer Transaktions-id verknüpft), daher wird stattdessen über den Inhalt gesucht: Konto + Datum
     * (auf den Tag genau) + der vorzeichenbehaftete Betrag des Kontosplits müssen zu {@link KmyPendingDelete}
     * passen. Trifft eine Vormerkung auf mehrere gleichartige Transaktionen (z. B. zwei identische Beträge
     * am selben Tag), wird nur die erste noch nicht verbrauchte entfernt – ein bekanntes, seltenes Risiko.
     */
    public DeleteResult removeTransactions(String xml, List<KmyPendingDelete> deletes) {
        DeleteResult result = new DeleteResult();
        if (deletes == null || deletes.isEmpty()) {
            result.xml = xml;
            return result;
        }
        // Je Vormerkung: Konto-id + Datum + Betrag, sofern das Konto (noch) existiert.
        List<String> sigAccountId = new ArrayList<>();
        List<String> sigDate = new ArrayList<>();
        List<Long> sigCents = new ArrayList<>();
        List<Long> sigDeleteId = new ArrayList<>();
        for (KmyPendingDelete d : deletes) {
            String assetId = doc.accountId(d.account);
            if (assetId == null) {
                continue; // Konto nicht mehr vorhanden → nichts zuzuordnen
            }
            sigAccountId.add(assetId);
            sigDate.add(dateFor(d.createdAt));
            sigCents.add(d.signedCents);
            sigDeleteId.add(d.id);
        }
        if (sigAccountId.isEmpty()) {
            result.xml = xml;
            return result;
        }
        boolean[] consumed = new boolean[sigAccountId.size()];

        // Nur im Hauptbuch suchen: hinter </TRANSACTIONS> stehen u. a. die geplanten Buchungen, deren
        // eingebettete <TRANSACTION> sonst zufällig auf eine Vormerkung passen und die Regel zerstören könnte.
        int ledgerIdx = xml.lastIndexOf(LEDGER_END);
        String tail = "";
        if (ledgerIdx >= 0) {
            tail = xml.substring(ledgerIdx);
            xml = xml.substring(0, ledgerIdx);
        }

        Matcher m = TX_BLOCK.matcher(xml);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        int removed = 0;
        while (m.find()) {
            String tx = m.group();
            Matcher dm = POSTDATE_ATTR.matcher(tx);
            // postdate steht im öffnenden <TRANSACTION …> vor den Splits – erster Treffer genügt.
            String date = dm.find() ? dm.group(1) : null;
            int matchIdx = -1;
            if (date != null) {
                for (int i = 0; i < sigAccountId.size(); i++) {
                    if (!consumed[i] && sigDate.get(i).equals(date)
                            && hasSplit(tx, sigAccountId.get(i), sigCents.get(i))) {
                        matchIdx = i;
                        break;
                    }
                }
            }
            if (matchIdx >= 0) {
                consumed[matchIdx] = true;
                result.resolvedIds.add(sigDeleteId.get(matchIdx));
                sb.append(xml, last, m.start());
                last = m.end();
                removed++;
            }
        }
        sb.append(xml.substring(last));
        String out = sb.toString();
        if (removed > 0) {
            out = bumpCount(out, "TRANSACTIONS", -removed);
        }
        result.xml = out + tail;
        return result;
    }

    /** Eine im Hauptbuch gefundene Transaktion samt allem, was zum Austauschen nötig ist. */
    private static final class Found {
        final String txId;
        final String block;
        private final String head;
        private final String tail;
        private final int start;
        private final int end;

        Found(String txId, String block, String head, String tail, int start, int end) {
            this.txId = txId;
            this.block = block;
            this.head = head;
            this.tail = tail;
            this.start = start;
            this.end = end;
        }

        /** Die XML mit {@code replacement} an der Stelle des Fundes. */
        String replacedBy(String replacement) {
            return head.substring(0, start) + replacement + head.substring(end) + tail;
        }
    }

    /**
     * Sucht die Transaktion einer bearbeiteten Buchung über die Signatur der exportierten Fassung
     * (Konto + Datum + Betrag, siehe {@link de.spahr.ausgaben.db.EditStatus}). Was mit dem Fund
     * geschieht, entscheidet der Aufrufer – ersetzen oder gezielt ändern.
     *
     * @param replacedTxIds in diesem Lauf schon getroffene Transaktions-ids; verhindert, daß zwei
     *                      gleichartige Buchungen (gleicher Tag, gleicher Betrag) denselben Block treffen
     * @return der Fund oder {@code null}, wenn keine passende Transaktion (mehr) zu finden war
     */
    private Found findTransaction(String xml, Booking b, Set<String> replacedTxIds) {
        String accountId = doc.accountId(de.spahr.ausgaben.db.EditStatus.fileAccount(b));
        if (accountId == null) {
            return null; // Konto der exportierten Fassung gibt es in der Datei nicht (mehr)
        }
        String date = dateFor(de.spahr.ausgaben.db.EditStatus.fileCreatedAt(b));
        long cents = de.spahr.ausgaben.db.EditStatus.fileSignedCents(b);

        // Nur im Hauptbuch suchen: hinter </TRANSACTIONS> stehen u. a. die geplanten Buchungen, deren
        // eingebettete <TRANSACTION> sonst zufällig passen und deren Regel zerstört werden könnte.
        int ledgerIdx = xml.lastIndexOf(LEDGER_END);
        String tail = "";
        String head = xml;
        if (ledgerIdx >= 0) {
            tail = xml.substring(ledgerIdx);
            head = xml.substring(0, ledgerIdx);
        }

        Matcher m = TX_BLOCK.matcher(head);
        while (m.find()) {
            String tx = m.group();
            String txId = attributeOfOpeningTag(tx, ID_ATTR);
            if (txId == null || replacedTxIds.contains(txId)) {
                continue;
            }
            Matcher dm = POSTDATE_ATTR.matcher(tx);
            String postdate = dm.find() ? dm.group(1) : null;
            if (postdate == null || !postdate.equals(date) || !hasSplit(tx, accountId, cents)) {
                continue;
            }
            replacedTxIds.add(txId);
            return new Found(txId, tx, head, tail, m.start(), m.end());
        }
        return null;
    }

    /**
     * Trägt die Transaktion einen Split auf einem <b>Wertpapier</b> (KMyMoney-Kontotyp 15)? Dann darf
     * sie nicht neu gebaut werden: Stückzahl, Kurs und Aktion stehen in keiner Buchung der App, und der
     * Bauplan für gewöhnliche Umbuchungen kennt sie nicht – aus dem Wertpapierkauf würde eine nackte
     * Umbuchung, und das Depot wäre in der Datei zerstört.
     *
     * <p>Entschieden wird bewußt am Fund und nicht am Kontonamen der Buchung: maßgeblich ist, was in
     * der Datei steht.</p>
     */
    private boolean hasSecuritySplit(String tx) {
        Matcher sm = SPLIT_TAG.matcher(tx);
        while (sm.find()) {
            Matcher am = ACCOUNT_ATTR.matcher(sm.group());
            if (am.find() && doc.accountTypeOf(am.group(1)) == 15) {
                return true;
            }
        }
        return false;
    }

    /**
     * Attribute, die <b>KMyMoney</b> führt und die App gar nicht kennt: der Abgleich-Zustand samt Datum,
     * die Aktion und die Angaben des Bankimports. Ein neu gebauter Split schreibt hier Vorgabewerte –
     * beim Ersetzen einer vorhandenen Transaktion würden sie damit stillschweigend verlorengehen.
     */
    private static final String[] CARRIED_ATTRS =
            {"reconcileflag", "reconciledate", "action", "bankid", "number"};

    /**
     * Überträgt {@link #CARRIED_ATTRS} aus der vorhandenen Transaktion in die neu gebaute. Zugeordnet
     * wird über das <b>Konto</b> des Splits, weil sich Reihenfolge und Anzahl der Splits beim Bearbeiten
     * ändern können (aus einer Kategorie werden zwei); mehrere Splits auf dasselbe Konto werden der
     * Reihe nach bedient. Ein Split, den es vorher nicht gab, behält die Vorgabewerte.
     *
     * <p>Ohne das verlor eine bearbeitete Buchung beim Rückschreiben ihren Abgleich-Status
     * ({@code reconcileflag="1"} → {@code "0"}) und ihre Aktion – in der Datei unauffällig, in KMyMoney
     * aber der Unterschied zwischen „abgeglichen" und „offen".</p>
     */
    private String withCarriedAttributes(String newTx, String oldBlock) {
        // Konto → Werte der alten Splits, in Reihenfolge.
        Map<String, List<Map<String, String>>> old = new HashMap<>();
        Matcher om = SPLIT_TAG.matcher(oldBlock);
        while (om.find()) {
            String tag = om.group();
            Matcher am = ACCOUNT_ATTR.matcher(tag);
            if (!am.find()) {
                continue;
            }
            Map<String, String> values = new HashMap<>();
            for (String attr : CARRIED_ATTRS) {
                Matcher vm = Pattern.compile("\\b" + attr + "=\"([^\"]*)\"").matcher(tag);
                if (vm.find()) {
                    values.put(attr, vm.group(1));
                }
            }
            old.computeIfAbsent(am.group(1), k -> new ArrayList<>()).add(values);
        }
        if (old.isEmpty()) {
            return newTx;
        }
        StringBuilder out = new StringBuilder();
        Matcher nm = SPLIT_TAG.matcher(newTx);
        int last = 0;
        while (nm.find()) {
            out.append(newTx, last, nm.start());
            String tag = nm.group();
            Matcher am = ACCOUNT_ATTR.matcher(tag);
            List<Map<String, String>> queue = am.find() ? old.get(am.group(1)) : null;
            if (queue != null && !queue.isEmpty()) {
                Map<String, String> values = queue.remove(0);
                for (Map.Entry<String, String> e : values.entrySet()) {
                    tag = Pattern.compile("\\b" + e.getKey() + "=\"[^\"]*\"")
                            .matcher(tag)
                            .replaceFirst(Matcher.quoteReplacement(
                                    e.getKey() + "=\"" + e.getValue() + "\""));
                }
            }
            out.append(tag);
            last = nm.end();
        }
        out.append(newTx.substring(last));
        return out.toString();
    }

    /**
     * Ändert an einer Wertpapier-Transaktion <b>nur</b> Notiz und Stichwörter: in jedem Split wird der
     * Wert von {@code memo} ersetzt und der Satz der {@code <TAG …/>}-Kindelemente neu gesetzt. Alles
     * andere – {@code shares}, {@code price}, {@code value}, {@code action}, die ids, das Datum – bleibt
     * Zeichen für Zeichen stehen.
     */
    private String patchedSplits(String tx, Booking b) {
        String memo = esc(b.note == null ? "" : b.note);
        String tags = tagChildren(b.tags);
        StringBuilder out = new StringBuilder();
        Matcher sm = SPLIT_TAG.matcher(tx);
        int last = 0;
        while (sm.find()) {
            out.append(tx, last, sm.start());
            String open = sm.group();
            boolean selfClosing = open.endsWith("/>");
            // Ende des ganzen Splits: bei Kindelementen bis hinter </SPLIT>, sonst hinter dem Tag.
            int splitEnd = sm.end();
            if (!selfClosing) {
                int close = tx.indexOf("</SPLIT>", sm.end());
                splitEnd = close < 0 ? sm.end() : close + "</SPLIT>".length();
            }
            String opening = selfClosing
                    ? open.substring(0, open.length() - 2)
                    : open.substring(0, open.length() - 1);
            String memoAttr = "memo=\"" + memo + "\"";
            Matcher mm = MEMO_ATTR.matcher(opening);
            opening = mm.find()
                    ? opening.substring(0, mm.start()) + memoAttr + opening.substring(mm.end())
                    : opening + " " + memoAttr; // Split ohne memo-Attribut: dazuschreiben
            out.append(opening);
            out.append(tags.isEmpty() ? "/>" : ">" + tags + "</SPLIT>");
            last = splitEnd;
        }
        out.append(tx.substring(last));
        return out.toString();
    }

    /** Trägt die Transaktion einen Split auf diesem Konto mit genau diesem Betrag? */
    private static boolean hasSplit(String tx, String accountId, long cents) {
        Matcher sm = SPLIT_TAG.matcher(tx);
        while (sm.find()) {
            String splitTagXml = sm.group();
            Matcher am = ACCOUNT_ATTR.matcher(splitTagXml);
            Matcher vm = VALUE_ATTR.matcher(splitTagXml);
            if (am.find() && vm.find() && am.group(1).equals(accountId)
                    && valueToCents(vm.group(1)) == cents) {
                return true;
            }
        }
        return false;
    }

    /** Wert eines Attributs aus dem öffnenden Tag (nicht aus den Kindelementen); {@code null} = keins. */
    private static String attributeOfOpeningTag(String block, Pattern attr) {
        Matcher om = TX_OPEN_TAG.matcher(block);
        if (!om.find()) {
            return null;
        }
        Matcher am = attr.matcher(om.group());
        return am.find() ? am.group(1) : null;
    }

    /**
     * Stellt erledigte bzw. übersprungene geplante Buchungen in der Datei weiter: {@code postdate} der in
     * {@code <SCHEDULED_TX>} eingebetteten Transaktion (= nächste Fälligkeit) auf den neuen Termin, bei einer
     * tatsächlich gebuchten Zahlung zusätzlich {@code lastPayment}. Die Regel selbst bleibt erhalten, Splits,
     * {@code startDate} und alles Übrige unverändert.
     *
     * <p>Steht in der Datei nicht mehr der erwartete Termin ({@link ScheduledAdvance#fromDueMs}), hat
     * KMyMoney die Regel selbst weitergestellt – dann wird nichts geschrieben und die Vormerkung nur
     * verworfen (die Datei gewinnt). Eine unbekannte Regel-id bleibt vorgemerkt liegen.
     */
    public static ScheduleResult applyScheduleAdvances(String xml, List<ScheduledAdvance> advances) {
        ScheduleResult result = new ScheduleResult();
        result.xml = xml;
        if (advances == null || advances.isEmpty()) {
            return result;
        }
        for (ScheduledAdvance a : advances) {
            if (a.kmyId == null || a.kmyId.trim().isEmpty()) {
                result.resolvedIds.add(a.id);
                continue;
            }
            Pattern block = Pattern.compile("<SCHEDULED_TX\\b[^>]*\\bid=\"" + Pattern.quote(a.kmyId.trim())
                    + "\"[^>]*>.*?</SCHEDULED_TX>", Pattern.DOTALL);
            Matcher m = block.matcher(result.xml);
            if (!m.find()) {
                continue; // Regel nicht in dieser Datei – Vormerkung liegen lassen
            }
            String tx = m.group();
            Matcher pd = Pattern.compile("(<TRANSACTION\\b[^>]*\\bpostdate=\")([^\"]*)(\")").matcher(tx);
            if (!pd.find()) {
                result.resolvedIds.add(a.id);
                continue;
            }
            if (!kmyDate(a.fromDueMs).equals(pd.group(2))) {
                result.resolvedIds.add(a.id); // Datei ist weiter als erwartet → nicht überschreiben
                continue;
            }
            // Leeres postdate = keine weitere Fälligkeit (so schreibt es auch KMyMoney bei einmaligen Regeln).
            String nextDate = a.nextDueMs > 0 ? kmyDate(a.nextDueMs) : "";
            String updated = tx.substring(0, pd.start()) + pd.group(1) + nextDate + pd.group(3)
                    + tx.substring(pd.end());
            if (a.lastPaymentMs > 0) {
                Matcher lp = Pattern.compile("(<SCHEDULED_TX\\b[^>]*\\blastPayment=\")([^\"]*)(\")")
                        .matcher(updated);
                if (lp.find()) {
                    updated = updated.substring(0, lp.start()) + lp.group(1) + kmyDate(a.lastPaymentMs)
                            + lp.group(3) + updated.substring(lp.end());
                }
            }
            result.xml = result.xml.substring(0, m.start()) + updated + result.xml.substring(m.end());
            result.resolvedIds.add(a.id);
            result.writtenIds.add(a.id);
        }
        return result;
    }

    /** KMyMoney-Betrag „num/den" → Cent (gerundet, mit Vorzeichen); wie {@code KmyImporter.valueToCents}. */
    private static long valueToCents(String value) {
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

    // ---- Transaktionen zusammenbauen ----

    /**
     * Einnahme/Ausgabe: Konto-Split und – sofern eine Kategorie bekannt ist – Kategorie-Splits. Eine leere
     * Kategorie ist erlaubt (nicht zugeordnet); ein unbekanntes Konto oder eine unbekannte Kategorie
     * liefert {@code null} und einen Eintrag in {@code result.skipped}.
     */
    private Built buildNormal(Booking b, Result result, Map<Long, List<BookingSplit>> splitsMap,
                              Map<String, String> newPayeeIds, StringBuilder payeeFragments,
                              int[] nextPayee) {
        String assetId = doc.accountId(b.account);
        if (assetId == null) {
            result.skipped.add(label(b) + ": "
                    + ctx.getString(de.spahr.ausgaben.R.string.skip_account_not_found, b.account));
            return null;
        }
        String commodity = commodityOf(b.account);
        String payeeId = resolvePayee(b.payee, result, newPayeeIds, payeeFragments, nextPayee);
        long signedCents = b.isIncome ? b.amountCents : -b.amountCents;
        String memo = b.note == null ? "" : b.note;

        // Splitbuchung (≥2 Kategorien): Konto-Split + je Teil ein Kategorie-Split (Gegen-Vorzeichen).
        List<BookingSplit> parts = splitsMap.get(b.id);
        if (parts != null && parts.size() >= 2) {
            List<String> splitXmls = buildSplitParts(b, assetId, payeeId, signedCents, memo, parts,
                    commodity, result);
            return splitXmls == null ? null : new Built(splitXmls, commodity, memo);
        }

        String cat = b.category == null ? "" : b.category.trim();
        String categoryId = null;
        if (!cat.isEmpty()) {
            categoryId = doc.categoryId(cat);
            if (categoryId == null) {
                result.skipped.add(label(b) + ": "
                        + ctx.getString(de.spahr.ausgaben.R.string.skip_category_not_found, cat));
                return null;
            }
            String clash = currencyClash(commodity, categoryId);
            if (clash != null) {
                result.skipped.add(label(b) + ": " + clash);
                return null;
            }
        }
        String tags = tagChildren(b.tags);
        List<String> splitXmls = new ArrayList<>();
        splitXmls.add(split("S0001", assetId, payeeId, fraction(signedCents), esc(memo), tags));
        if (categoryId != null && !categoryId.isEmpty()) {
            splitXmls.add(split("S0002", categoryId, payeeId, fraction(-signedCents), esc(memo), tags));
        }
        return new Built(splitXmls, commodity, memo);
    }

    /** Umbuchung: eine Transaktion mit zwei Konto-Splits (Quelle −, Ziel +), keine Kategorie; mit Empfänger. */
    private Built buildTransfer(Booking b, Result result, Map<String, String> newPayeeIds,
                                StringBuilder payeeFragments, int[] nextPayee) {
        // Aus der Sicht dieser Zeile Quelle/Ziel bestimmen (Einnahme = Geld kam auf dieses Konto).
        String fromAccount = b.isIncome ? b.transferAccount : b.account;
        String toAccount = b.isIncome ? b.account : b.transferAccount;
        String fromId = doc.accountId(fromAccount);
        String toId = doc.accountId(toAccount);
        if (fromId == null || toId == null) {
            String missing = fromId == null ? fromAccount : toAccount;
            result.skipped.add(ctx.getString(
                    de.spahr.ausgaben.R.string.skip_transfer_account_not_found,
                    fromAccount, toAccount, missing));
            return null;
        }
        String commodity = commodityOf(fromAccount);
        String clash = currencyClash(commodity, toId);
        if (clash != null) {
            // Umbuchung über Währungsgrenzen: ohne Kurs nicht schreibbar
            result.skipped.add(fromAccount + " → " + toAccount + ": " + clash);
            return null;
        }
        String payeeId = resolvePayee(b.payee, result, newPayeeIds, payeeFragments, nextPayee);
        String memo = b.note == null ? "" : b.note;
        String tags = tagChildren(b.tags);
        List<String> splitXmls = new ArrayList<>();
        splitXmls.add(split("S0001", fromId, payeeId, fraction(-b.amountCents), esc(memo), tags));
        splitXmls.add(split("S0002", toId, payeeId, fraction(b.amountCents), esc(memo), tags));
        return new Built(splitXmls, commodity, memo);
    }

    /**
     * Schreibt die in der App erfassten Depot-Bewegungen als neue Transaktionen in die XML.
     *
     * <p>Eine Wertpapier-Transaktion ist in KMyMoney <b>eine</b> Transaktion aus mehreren Splits: der
     * Geld-Split auf dem Verrechnungskonto, der Wertpapier-Split mit Stückzahl, Kurs und Aktion, dazu die
     * Gebühren- bzw. Steuer-Kategorie und bei einer Dividende die Ertragskategorie. Die Summe aller
     * {@code value} muss exakt 0 ergeben – daran erkennt KMyMoney eine ausgeglichene Buchung.</p>
     *
     * <pre>
     *   Kauf:      Geld −(Betrag+Gebühr) · Papier +Betrag (Buy, +Stück) · Gebührenkategorie +Gebühr
     *   Verkauf:   Geld +(Betrag−Gebühr) · Papier −Betrag (Buy, −Stück) · Gebührenkategorie +Gebühr
     *   Dividende: Geld +Netto · Papier 0 (Dividend, 0 Stück) · Ertrag −Brutto · Steuer +Steuer
     * </pre>
     *
     * <p>Der Verkauf trägt <b>dieselbe</b> Aktion wie der Kauf; unterschieden wird er allein am
     * Vorzeichen der Stückzahl. So führt KMyMoney es: {@code actionNamesLUT} in
     * {@code mymoney/mymoneysplit.cpp} kennt kein „Sell" — dort steht ausdrücklich
     * <i>„SellShares is not present as action"</i> —, und {@code investmentTransactionType} macht aus
     * {@code Buy} mit negativer Stückzahl den Verkauf.</p>
     *
     * <p>Der Kurs wird nicht gerundet, sondern als exakter Bruch {@code Betrag/Stückzahl} geschrieben –
     * sonst passte {@code shares × price} nicht mehr zu {@code value}.</p>
     *
     * <p>Findet sich ein Konto, ein Wertpapier oder eine Kategorie nicht in der Datei, wird die Bewegung
     * übersprungen und bleibt vorgemerkt. Lieber beim nächsten Mal, als eine unvollständige Transaktion
     * zu hinterlassen.</p>
     */
    public SecurityResult buildSecurityTransactions(String xml, List<SecurityTx> pending) {
        SecurityResult result = new SecurityResult();
        result.xml = xml;
        if (pending == null || pending.isEmpty()) {
            return result;
        }
        // Die Transaktionsnummern der Buchungen sind eben erst vergeben worden und stehen noch nicht im
        // Dokument – deshalb im aktuellen XML nachsehen und nicht in doc.maxTransactionNumber().
        long nextTx = maxTxNumberIn(xml) + 1;
        StringBuilder fragments = new StringBuilder();
        int written = 0;
        String today = dateFormat.format(new Date());

        for (SecurityTx tx : pending) {
            List<String> splits = securitySplits(tx, result);
            if (splits == null) {
                continue;
            }
            String txId = String.format(Locale.US, "T%018d", nextTx++);
            fragments.append(transactionElement(txId, dateFor(tx.date), today, "",
                    commodityOf(tx.moneyAccount), splits));
            written++;
            result.writtenIds.add(tx.id);
        }
        if (written == 0) {
            return result;
        }
        String merged = insertIntoBlock(xml, "TRANSACTIONS", fragments.toString());
        if (merged == null) {
            // Ohne <TRANSACTIONS>-Block lieber nichts schreiben, als ein count zu behaupten, das nirgends
            // steht – die Bewegungen bleiben dann vorgemerkt.
            result.writtenIds.clear();
            result.skipped.add(ctx.getString(de.spahr.ausgaben.R.string.err_kmy_read));
            return result;
        }
        result.xml = bumpCount(merged, "TRANSACTIONS", written);
        return result;
    }

    /** Die Splits einer Depot-Bewegung, oder {@code null}, wenn etwas in der Datei fehlt. */
    private List<String> securitySplits(SecurityTx tx, SecurityResult result) {
        String label = tx.securityName + " (" + tx.action + ")";
        String depotId = doc.depotId(tx.depot);
        String stockId = depotId == null ? null : stockAccountId(depotId, tx.securityKmyId);
        if (stockId == null) {
            result.skipped.add(label + ": " + ctx.getString(
                    de.spahr.ausgaben.R.string.skip_account_not_found, tx.depot));
            return null;
        }
        String moneyId = doc.accountId(tx.moneyAccount);
        if (moneyId == null) {
            result.skipped.add(label + ": " + ctx.getString(
                    de.spahr.ausgaben.R.string.skip_account_not_found, tx.moneyAccount));
            return null;
        }
        // Die Transaktion wird in der Währung des Verrechnungskontos geschrieben. Steht das Depot in
        // einer anderen, fehlt für den Wertpapier-Split der Umrechnungskurs — geschrieben würde er
        // trotzdem, mit „1/1", und stünde betragsmäßig falsch in der Datei. Bei den gewöhnlichen
        // Buchungen wird das seit jeher geprüft (siehe currencyClash in buildSingle/buildTransfer);
        // auf dem Wertpapierweg fehlte die Prüfung.
        String commodity = commodityOf(tx.moneyAccount);
        String depotClash = currencyClash(commodity, depotId);
        if (depotClash != null) {
            result.skipped.add(label + ": " + depotClash);
            return null;
        }

        boolean dividend = "dividend".equals(tx.action);
        boolean sell = "sell".equals(tx.action);
        long gross = tx.amountCents;
        long fee = dividend ? gross - tx.netCents : tx.feeCents;
        long money = dividend ? tx.netCents : (sell ? gross - fee : -(gross + fee));

        // Die Notiz steht auf beiden Seiten: Sie trägt den Beleg-Tag der eingelesenen Abrechnung, und
        // ohne sie ginge er beim nächsten Import verloren – die Geldbuchung wird nach dem Export als
        // geschrieben markiert, obwohl ihre Notiz die Datei nie erreicht hätte.
        String memo = esc(tx.note == null ? "" : tx.note);

        List<String> splits = new ArrayList<>();
        splits.add(split("S0001", moneyId, "", fraction(money), memo, ""));
        int[] index = {2};
        if (dividend) {
            splits.add(securitySplit(splitId(index), stockId, "0/100", "0/1", "1/1", "Dividend", memo));
            // Der Ertrag steht in KMyMoney mit umgekehrtem Vorzeichen: er kommt von der Kategorie
            // und geht aufs Konto.
            if (!addCategorySplits(splits, index, tx.partsOf(true), -1, gross, label, commodity,
                    result)) {
                return null;
            }
            return addCategorySplits(splits, index, tx.partsOf(false), 1, fee, label, commodity,
                    result) ? splits : null;
        }
        long stockValue = sell ? -gross : gross;
        String sharesFraction = decimalFraction(tx.shares, SHARE_SCALE);
        // „Buy" auch beim Verkauf: KMyMoney kennt keine „Sell"-Aktion (siehe oben). Bis 1.13 stand hier
        // eine, und weil actionStringToAction sie nicht auflösen konnte, zeigte das Depotbuch jeden
        // Verkauf als „Anteile kaufen" — bei richtigen Beträgen, weshalb es lange nicht auffiel.
        splits.add(securitySplit(splitId(index), stockId, fraction(stockValue), sharesFraction,
                priceFraction(gross, tx.shares, doc.securityPricePrecision(tx.securityKmyId)), "Buy",
                memo));
        return addCategorySplits(splits, index, tx.partsOf(false), 1, fee, label, commodity,
                result) ? splits : null;
    }

    /**
     * Je Kategoriezeile der Bewegung ein eigener Split — so, wie KMyMoney es führt.
     *
     * <p>Ein Betrag ohne Kategoriezeilen lässt die ganze Bewegung aus: ihm fehlt die Gegenseite, und
     * eine Transaktion, die sich nicht auf null summiert, nimmt KMyMoney nicht an. Gibt es Zeilen,
     * deren Summe den Betrag nicht trifft, wird die letzte auf ihn gezogen — ein verlorener Cent aus
     * einer Rundung darf die Übergabe nicht scheitern lassen.</p>
     *
     * @return {@code false}, wenn eine Kategorie fehlt (dann wird die Bewegung ausgelassen)
     */
    private boolean addCategorySplits(List<String> splits, int[] index,
                                      List<de.spahr.ausgaben.db.SecurityTxSplit> parts, int sign,
                                      long total, String label, String commodity,
                                      SecurityResult result) {
        if (total == 0) {
            return true;
        }
        if (parts.isEmpty()) {
            result.skipped.add(label + ": " + ctx.getString(
                    de.spahr.ausgaben.R.string.skip_category_not_found, ""));
            return false;
        }
        long rest = total;
        for (int i = 0; i < parts.size(); i++) {
            String category = parts.get(i).category.trim();
            String categoryId = category.isEmpty() ? null : doc.categoryId(category);
            if (categoryId == null) {
                result.skipped.add(label + ": " + ctx.getString(
                        de.spahr.ausgaben.R.string.skip_category_not_found, category));
                return false;
            }
            String clash = currencyClash(commodity, categoryId);
            if (clash != null) {
                result.skipped.add(label + ": " + clash);
                return false;
            }
            // Das gespeicherte Vorzeichen zählt. Mit Math.abs traf es nur zu, solange die
            // gegenläufige Zeile zufällig die letzte war — die bekommt ihr Vorzeichen ohnehin über
            // den Rest. Stand sie davor, wurde sie zur gleichgerichteten Zeile, und der Rest glich
            // die Differenz an der letzten wieder aus: die Transaktion ging auf null auf, die Beträge
            // standen aber auf den falschen Kategorien.
            long value = i == parts.size() - 1 ? rest : parts.get(i).amountCents;
            rest -= value;
            splits.add(split(splitId(index), categoryId, "", fraction(sign * value), "", ""));
        }
        return true;
    }

    private static String splitId(int[] index) {
        return String.format(Locale.US, "S%04d", index[0]++);
    }

    /** Konto-Id des Wertpapiers (Typ 15) unterhalb des Depots; {@code null}, wenn es dort keines gibt. */
    private String stockAccountId(String depotId, String securityKmyId) {
        for (String id : doc.allAccountIds()) {
            if (doc.accountTypeOf(id) == 15 && depotId.equals(doc.accountParentOf(id))
                    && securityKmyId.equals(doc.accountCurrencyOf(id))) {
                return id;
            }
        }
        return null;
    }

    /**
     * Ein Wertpapier-Split: anders als {@link #split} trägt er eine eigene Stückzahl, einen eigenen Kurs
     * und eine Aktion – die drei Angaben, die eine Depot-Bewegung überhaupt erst ausmachen.
     */
    private String securitySplit(String id, String accountId, String value, String shares,
                                 String price, String action, String memo) {
        return "<SPLIT reconcileflag=\"0\" payee=\"\" number=\"\" bankid=\"\" memo=\"" + memo
                + "\" value=\"" + value
                + "\" reconciledate=\"\" account=\"" + esc(accountId) + "\" id=\"" + id
                + "\" price=\"" + price + "\" shares=\"" + shares + "\" action=\"" + action + "\"/>";
    }

    /**
     * Nenner der Stückzahl-Brüche: sechs Nachkommastellen. Banken rechnen Sparplan-Anteile fein ab – die
     * ING weist {@code 6,09607} aus. Mit einem gröberen Nenner stünde eine gerundete Stückzahl in der
     * KMyMoney-Datei, und der Bestand liefe über die Jahre auseinander.
     */
    private static final long SHARE_SCALE = 1000000L;

    /**
     * Der Stückpreis {@code Betrag / Stückzahl}, gerundet auf die Kursgenauigkeit des Wertpapiers
     * ({@code precision} = Attribut {@code pp} am {@code SECURITY}).
     *
     * <p>Bis 1.13 stand hier der <b>exakte</b> Bruch, mit der Begründung, {@code shares × price} müsse
     * genau den {@code value} des Splits ergeben. Das ist nicht KMyMoneys Sicht: Bei einer Sparplan-
     * Ausführung über 1.000,00 € auf 24,91591 Stück wurde daraus {@code 100000000/2491591}, und die
     * Oberfläche zeigte 40,13499 statt der 40,135 der Abrechnung. In einer echten KMyMoney-Datei stehen
     * durchweg <b>gerundete</b> Kurse mit kleinen Nennern (19,52 = {@code 488/25}), die von
     * {@code value/shares} in der vierten bis sechsten Stelle abweichen – Betrag und Stückzahl stehen ja
     * ohnehin exakt daneben im Split, der Kurs ist die abgeleitete Angabe.</p>
     */
    private static String priceFraction(long grossCents, double shares, int precision) {
        double count = Math.abs(shares);
        if (count == 0) {
            return "1/1";
        }
        long scale = 1L;
        for (int i = 0; i < precision; i++) {
            scale *= 10L;
        }
        return decimalFraction(Math.abs(grossCents) / 100.0 / count, scale);
    }

    /** Eine Dezimalzahl als gekürzter Bruch mit {@code scale} als Nenner. */
    private static String decimalFraction(double value, long scale) {
        return reduced(Math.round(value * scale), scale);
    }

    /** Gekürzter Bruch; das Vorzeichen steht im Zähler, der Nenner bleibt positiv. */
    private static String reduced(long num, long den) {
        if (den == 0) {
            return num + "/1";
        }
        long g = gcd(Math.abs(num), Math.abs(den));
        if (g == 0) {
            return "0/1";
        }
        long n = num / g;
        long d = den / g;
        if (d < 0) {
            n = -n;
            d = -d;
        }
        return n + "/" + d;
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    /** Höchste vergebene Transaktionsnummer im übergebenen XML (auch die eben erst eingefügten). */
    static long maxTxNumberIn(String xml) {
        Matcher m = TX_ID_NUMBER.matcher(xml);
        long max = 0;
        while (m.find()) {
            try {
                max = Math.max(max, Long.parseLong(m.group(1)));
            } catch (NumberFormatException ignored) {
                // Eine eigenwillige id bringt die Nummernvergabe nicht durcheinander.
            }
        }
        return max;
    }

    // ---- XML-Bausteine ----

    /**
     * Baut die Kategorie-Splits einer Splitbuchung: Konto-Split (signierter Gesamtbetrag) + je Teil ein
     * Kategorie-Split mit Gegen-Vorzeichen. Gibt {@code null} zurück, wenn eine Kategorie unbekannt ist.
     */
    private List<String> buildSplitParts(Booking b, String assetId, String payeeId, long signedCents,
                                         String memo, List<BookingSplit> parts, String commodity,
                                         Result result) {
        String tags = tagChildren(b.tags);
        List<String> splitXmls = new ArrayList<>();
        splitXmls.add(split("S0001", assetId, payeeId, fraction(signedCents), esc(memo), tags));
        int idx = 2;
        for (BookingSplit p : parts) {
            String cat = p.category == null ? "" : p.category.trim();
            String categoryId = doc.categoryId(cat);
            if (categoryId == null) {
                result.skipped.add(label(b) + ": "
                        + ctx.getString(de.spahr.ausgaben.R.string.skip_category_not_found, cat));
                return null;
            }
            String clash = currencyClash(commodity, categoryId);
            if (clash != null) {
                result.skipped.add(label(b) + ": " + clash);
                return null;
            }
            // App-Teilbetrag (in Gesamt-Einheiten) → Kategorie-Split mit Gegen-Vorzeichen zum Konto-Split.
            long catValue = b.isIncome ? -p.amountCents : p.amountCents;
            splitXmls.add(split(String.format(Locale.US, "S%04d", idx++), categoryId, payeeId,
                    fraction(catValue), esc(memo), tags));
        }
        return splitXmls;
    }

    /** Löst einen Empfänger auf (bekannt/schon angelegt) oder legt ihn neu an; liefert die Payee-id ("" = keiner). */
    private String resolvePayee(String rawPayee, Result result, Map<String, String> newPayeeIds,
                                StringBuilder payeeFragments, int[] nextPayee) {
        String payee = rawPayee == null ? "" : rawPayee.trim();
        if (payee.isEmpty()) {
            return "";
        }
        String existing = doc.payeeId(payee);
        if (existing == null) {
            existing = newPayeeIds.get(payee.toLowerCase(Locale.GERMANY));
        }
        if (existing == null) {
            existing = String.format(Locale.US, "P%06d", nextPayee[0]++);
            newPayeeIds.put(payee.toLowerCase(Locale.GERMANY), existing);
            payeeFragments.append(payeeElement(existing, payee));
            result.newPayees++;
        }
        return existing;
    }

    /**
     * Währung, in der die Buchung geschrieben wird: die des Kontos aus der Datei, sonst die Basiswährung
     * der Datei, sonst EUR. Ein hartes „EUR" hätte in jeder nicht-EUR-Datei jede Buchung falsch
     * ausgezeichnet.
     */
    private String commodityOf(String accountName) {
        String c = doc.currencyOfAccount(accountName);
        if (c.isEmpty()) {
            c = doc.baseCurrency();
        }
        return c.isEmpty() ? "EUR" : c;
    }

    /**
     * Prüft, ob das Gegenkonto {@code accountId} in einer anderen Währung als {@code commodity} geführt
     * wird. Dann bräuchte der Split einen Umrechnungskurs (KMyMoney: {@code price}/{@code shares}), den
     * die App nicht kennt – Rückgabe ist die fertige Meldung für {@code result.skipped}, sonst
     * {@code null}.
     */
    private String currencyClash(String commodity, String accountId) {
        String other = doc.accountCurrencyOf(accountId);
        if (other.isEmpty() || other.equalsIgnoreCase(commodity)) {
            return null;
        }
        return ctx.getString(de.spahr.ausgaben.R.string.skip_currency_mismatch, commodity, other);
    }

    private String transactionElement(String txId, String postdate, String entrydate, String memo,
                                      String commodity, List<String> splitXmls) {
        String m = esc(memo);
        StringBuilder splits = new StringBuilder();
        for (String s : splitXmls) {
            splits.append(s);
        }
        return "<TRANSACTION postdate=\"" + postdate + "\" entrydate=\"" + entrydate + "\" memo=\"" + m
                + "\" id=\"" + txId + "\" commodity=\"" + esc(commodity) + "\">"
                + "<SPLITS>"
                + splits
                + "</SPLITS>"
                + "</TRANSACTION>";
    }

    /**
     * Ein Split. Trägt die Buchung Stichwörter, wird er nicht selbstschließend geschrieben, sondern
     * nimmt sie als {@code <TAG id=…/>}-Kindelemente auf – so, wie KMyMoney sie erwartet.
     */
    private String split(String id, String accountId, String payeeId, String value, String memo,
                         String tagChildren) {
        String open = "<SPLIT reconcileflag=\"0\" payee=\"" + esc(payeeId) + "\" number=\"\" bankid=\"\" memo=\""
                + memo + "\" value=\"" + value + "\" reconciledate=\"\" account=\"" + esc(accountId)
                + "\" id=\"" + id + "\" price=\"1/1\" shares=\"" + value + "\" action=\"\"";
        return tagChildren.isEmpty()
                ? open + "/>"
                : open + ">" + tagChildren + "</SPLIT>";
    }

    /**
     * Die {@code <TAG>}-Kindelemente zu den Stichwörtern einer Buchung. Die App führt sie je Buchung,
     * also bekommt jeder Split derselben Transaktion dieselben – damit trägt auch bei einer Umbuchung
     * jede Kontoseite das Stichwort. Ein Name, den die Datei nicht kennt, fällt weg: die App legt nie
     * einen Eintrag im {@code <TAGS>}-Block an.
     */
    private String tagChildren(String tags) {
        StringBuilder sb = new StringBuilder();
        for (String name : de.spahr.ausgaben.db.BookingTags.parse(tags)) {
            String id = doc.tagId(name);
            if (id != null) {
                sb.append("<TAG id=\"").append(esc(id)).append("\"/>");
            }
        }
        return sb.toString();
    }

    private String payeeElement(String id, String name) {
        return "<PAYEE reference=\"\" matchignorecase=\"1\" email=\"\" matchingenabled=\"1\" name=\""
                + esc(name) + "\" id=\"" + id + "\" matchkey=\"\" usingmatchkey=\"0\">"
                + "<ADDRESS city=\"\" street=\"\" telephone=\"\" postcode=\"\" state=\"\"/>"
                + "</PAYEE>";
    }

    private String fraction(long signedCents) {
        return signedCents + "/100";
    }

    private String dateFor(long millis) {
        return dateFormat.format(new Date(millis));
    }

    /** Datum im KMyMoney-Format {@code yyyy-MM-dd} – für die statischen Helfer. */
    private static String kmyDate(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(millis));
    }

    private String label(Booking b) {
        String p = b.payee == null || b.payee.trim().isEmpty()
                ? ctx.getString(de.spahr.ausgaben.R.string.no_payee) : b.payee.trim();
        return p;
    }

    // ---- String-Manipulation ----

    /**
     * Fügt {@code fragment} als letztes Kind in den Block {@code <TAG>…</TAG>} ein. Leere Blöcke schreibt
     * KMyMoney selbstschließend ({@code <PAYEES/>} bzw. {@code <PAYEES count="0"/>}); die werden hier
     * aufgeklappt. Ohne das gingen bei einer frischen Datei alle Buchungen lautlos verloren.
     *
     * @return das ergänzte XML oder {@code null}, wenn es den Block gar nicht gibt (dann darf auch das
     *         count-Attribut nicht hochgezählt werden)
     */
    static String insertIntoBlock(String xml, String tag, String fragment) {
        int idx = xml.lastIndexOf("</" + tag + ">");
        if (idx >= 0) {
            return xml.substring(0, idx) + fragment + xml.substring(idx);
        }
        Matcher m = Pattern.compile("<" + tag + "\\b[^>]*/>").matcher(xml);
        if (!m.find()) {
            return null;
        }
        String open = m.group();
        // „<PAYEES count="0"/>" → „<PAYEES count="0">…</PAYEES>"
        String opened = open.substring(0, open.length() - 2).trim() + ">";
        return xml.substring(0, m.start()) + opened + fragment + "</" + tag + ">"
                + xml.substring(m.end());
    }

    /** Erhöht das count-Attribut von {@code <TAG count="N" …>} um {@code delta}. */
    private static String bumpCount(String xml, String tag, int delta) {
        Pattern p = Pattern.compile("(<" + tag + " count=\")(\\d+)(\")");
        Matcher m = p.matcher(xml);
        if (m.find()) {
            long n = Long.parseLong(m.group(2)) + delta;
            return xml.substring(0, m.start()) + m.group(1) + n + m.group(3) + xml.substring(m.end());
        }
        return xml;
    }

    private static String updateLastModified(String xml, String today) {
        Pattern p = Pattern.compile("(<LAST_MODIFIED_DATE date=\")[^\"]*(\")");
        Matcher m = p.matcher(xml);
        if (m.find()) {
            return xml.substring(0, m.start()) + m.group(1) + today + m.group(2) + xml.substring(m.end());
        }
        return xml;
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
