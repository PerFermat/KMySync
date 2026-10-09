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
        /**
         * Bearbeitete Buchungen, deren Transaktion in der Datei inzwischen abgeglichen ist
         * ({@code reconcileflag="2"}). Die Datei bleibt dort unberührt, und die Buchung auch: Inhalt
         * und Status „bearbeitet" stehen weiter da, damit die Änderung sichtbar bleibt und in KMyMoney
         * nachgetragen werden kann. Erst der nächste Import setzt {@code reconciled}.
         */
        public final List<Long> reconciledIds = new ArrayList<>();
        /** Dieselben – und die abgelehnten Löschungen des Laufs – als Text für die Meldung. */
        public final List<String> reconciledSkipped = new ArrayList<>();
        /** Was dieser Schritt an der Datei ändern wollte – Maßstab der Selbstprüfung. */
        public final KmyAenderungen aenderungen = new KmyAenderungen();
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
        /**
         * Vormerkungen, deren Transaktion in der Datei inzwischen abgeglichen ist: sie wird nicht
         * entfernt, die Vormerkung bleibt stehen. Aufgelöst wird sie beim nächsten Import (siehe
         * {@code KmyAccountImport}).
         */
        public final List<Long> reconciledIds = new ArrayList<>();
        /** Dieselben als Text für die Meldung. */
        public final List<String> reconciledSkipped = new ArrayList<>();
        /** Was dieser Schritt an der Datei ändern wollte – Maßstab der Selbstprüfung. */
        public final KmyAenderungen aenderungen = new KmyAenderungen();
    }

    /** Ergebnis des Schreibens der in der App erfassten Depot-Bewegungen. */
    public static class SecurityResult {
        public String xml;
        /** Geschriebene Bewegungen ({@link de.spahr.ausgaben.db.SecurityTx#id}). */
        public final List<Long> writtenIds = new ArrayList<>();
        public final List<String> skipped = new ArrayList<>();
        /** Was dieser Schritt an der Datei ändern wollte – Maßstab der Selbstprüfung. */
        public final KmyAenderungen aenderungen = new KmyAenderungen();
    }

    /** Ergebnis des Weiterstellens erledigter/übersprungener geplanter Buchungen. */
    public static class ScheduleResult {
        public String xml;
        /** Erledigte Vormerkungen (per {@link ScheduledAdvance#id}) – geschriebene und verworfene. */
        public final List<Long> resolvedIds = new ArrayList<>();
        /** Tatsächlich in der Datei weitergestellte Vormerkungen (Teilmenge von {@link #resolvedIds}). */
        public final List<Long> writtenIds = new ArrayList<>();
        /** Was dieser Schritt an der Datei ändern wollte – Maßstab der Selbstprüfung. */
        public final KmyAenderungen aenderungen = new KmyAenderungen();
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
    private static final Pattern PAYEE_ATTR = Pattern.compile("\\bpayee=\"([^\"]*)\"");
    /** Das {@code memo}-Attribut eines Splits – das einzige, das an einer Wertpapier-Buchung wandert. */
    /** Ein in KMyMoney abgeglichener Split; „1" ist nur „geklärt" und bleibt bearbeitbar. */
    private static final Pattern RECONCILED_ATTR = Pattern.compile("\\breconcileflag=\"2\"");
    private static final Pattern MEMO_ATTR = Pattern.compile("\\bmemo=\"[^\"]*\"");
    /** Nummernteil einer Transaktions-id ({@code T000000000000000042}). */
    private static final Pattern TX_ID_NUMBER = Pattern.compile("id=\"T(\\d+)\"");

    private final KmyDocument doc;
    private final android.content.Context ctx;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    public KmyExporter(KmyDocument doc, android.content.Context context) {
        this.doc = doc;
        this.ctx = de.spahr.ausgaben.i18n.LocaleManager.localizedContext(context);
        String xml = doc.xml();
        int buch = xml.indexOf("<TRANSACTIONS");
        int buchEnde = xml.indexOf(LEDGER_END);
        this.txOrder = attributeNames(xml, "<TRANSACTION ", buch, buchEnde);
        this.splitOrder = attributeNames(xml, "<SPLIT ", buch, buchEnde);
        int empfaenger = xml.indexOf("<PAYEES");
        int empfaengerEnde = xml.indexOf("</PAYEES>");
        this.payeeOrder = attributeNames(xml, "<PAYEE ", empfaenger, empfaengerEnde);
        this.addressOrder = attributeNames(xml, "<ADDRESS ", empfaenger, empfaengerEnde);
    }

    // ---- Reihenfolge der Attribute ----

    /**
     * Die Attributfolge, in der die Datei ihre eigenen Elemente gerade führt – abgelesen an der ersten
     * Transaktion des Hauptbuchs, ihrem ersten Split und dem ersten Empfänger samt Adresse. Leer, wenn
     * es kein solches Element gibt.
     *
     * <p>Eine feste Reihenfolge gibt es nicht zu treffen: KMyMoney 5 schreibt die Attribute bei jedem
     * Speichern in einer anderen. Für KMyMoney ist das ohne Bedeutung, aber im Zeilenvergleich nach
     * dem Export fielen die Zeilen der App sonst neben ihren Nachbarn auf. Also richten sie sich nach
     * ihnen.</p>
     */
    private final String[] txOrder;
    private final String[] splitOrder;
    private final String[] payeeOrder;
    private final String[] addressOrder;

    /**
     * Die Reihenfolge, wenn die Datei keine vorgibt (frische Datei) oder ein Attribut nicht kennt: so,
     * wie die aktuelle Entwicklungsfassung von KMyMoney schreibt ({@code mymoneyxmlwriter.cpp}).
     */
    private static final String[] TX_DEFAULT = {"id", "postdate", "memo", "entrydate", "commodity"};
    private static final String[] SPLIT_DEFAULT = {"id", "payee", "reconciledate", "action",
            "reconcileflag", "value", "shares", "price", "memo", "account", "number", "bankid"};
    private static final String[] PAYEE_DEFAULT = {"id", "name", "reference", "email",
            "matchingenabled", "usingmatchkey", "matchignorecase", "matchkey"};
    private static final String[] ADDRESS_DEFAULT = {"street", "city", "state", "postcode", "telephone"};

    /**
     * Die Attributnamen des ersten Tags, das zwischen {@code from} und {@code to} mit {@code marker}
     * beginnt, in ihrer Reihenfolge. Gelesen wird Zeichen für Zeichen: ein {@code >} oder {@code =} in
     * einem Attributwert zählt nicht.
     */
    static String[] attributeNames(String xml, String marker, int from, int to) {
        int start = from < 0 ? -1 : xml.indexOf(marker, from);
        if (start < 0 || (to >= 0 && start >= to)) {
            return new String[0];
        }
        List<String> names = new ArrayList<>();
        int i = start + marker.length();
        while (i < xml.length()) {
            char c = xml.charAt(i);
            if (c == '>' || c == '/') {
                break;
            }
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            int eq = xml.indexOf('=', i);
            if (eq < 0) {
                break;
            }
            names.add(xml.substring(i, eq).trim());
            int q = eq + 1;
            while (q < xml.length() && Character.isWhitespace(xml.charAt(q))) {
                q++;
            }
            if (q >= xml.length() || (xml.charAt(q) != '"' && xml.charAt(q) != '\'')) {
                break;
            }
            int close = xml.indexOf(xml.charAt(q), q + 1);
            if (close < 0) {
                break;
            }
            i = close + 1;
        }
        return names.toArray(new String[0]);
    }

    /**
     * Das öffnende Tag {@code <name a="…" b="…"} ohne Abschluss. Die Attribute stehen in der
     * Reihenfolge {@code order}; was dort fehlt, folgt in der von {@code fallback}, der Rest zuletzt.
     * Geschrieben wird genau, was übergeben ist – ein Attribut, das nur die Vorlage kennt, wird nicht
     * erfunden.
     *
     * @param pairs abwechselnd Name und (bereits maskierter) Wert
     */
    static String openTag(String name, String[] order, String[] fallback, String... pairs) {
        Map<String, String> attrs = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            attrs.put(pairs[i], pairs[i + 1]);
        }
        StringBuilder sb = new StringBuilder("<").append(name);
        for (String[] folge : new String[][]{order, fallback, attrs.keySet().toArray(new String[0])}) {
            for (String attr : folge) {
                String value = attrs.remove(attr);
                if (value != null) {
                    sb.append(' ').append(attr).append("=\"").append(value).append('"');
                }
            }
        }
        return sb.toString();
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
        // Umbuchungs-Gruppen, deren Transaktion abgeglichen ist: die zweite Zeile teilt das Schicksal.
        Set<String> reconciledGroups = new HashSet<>();
        for (Booking b : edited) {
            String group = b.transferGroup == null ? "" : b.transferGroup;
            if (!group.isEmpty() && doneTransferGroups.contains(group)) {
                // In der Datei ist die Umbuchung eine Transaktion: die zweite Zeile nur mitmarkieren.
                result.writtenIds.add(b.id);
                continue;
            }
            if (!group.isEmpty() && reconciledGroups.contains(group)) {
                result.reconciledIds.add(b.id);
                continue;
            }
            // Erst suchen, dann entscheiden: eine Wertpapier-Transaktion darf nicht neu gebaut werden,
            // denn Stückzahl, Kurs und Aktion stehen in keiner Buchung – sie wären danach fort.
            Found found = findTransaction(xml, b, replacedTxIds);
            if (found == null) {
                result.notFound.add(b.id);
                continue;
            }
            if (isReconciled(found.block)) {
                // In KMyMoney inzwischen abgeglichen – nach der letzten Bearbeitung in der App. Die
                // Datei bleibt dort unberührt, und die Buchung auch: sie wird weder als geschrieben
                // gemeldet noch als fehlend. Der Block gilt als verbraucht (replacedTxIds), damit ein
                // Zwilling nicht an seiner Stelle auf dieselbe Transaktion trifft.
                result.reconciledIds.add(b.id);
                result.reconciledSkipped.add(label(b) + ", " + dateFor(b.createdAt));
                if (!group.isEmpty()) {
                    reconciledGroups.add(group);
                }
                continue;
            }
            if (hasSecuritySplit(found.block)) {
                xml = found.replacedBy(patchedSplits(found.block, b));
                result.aenderungen.nurNotiz(found.txId);
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
                absicht(result.aenderungen.geaendert(found.txId), b, splitsMap);
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
            absicht(result.aenderungen.neu(txId), b, splitsMap);
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
                result.reconciledIds.clear();
                result.reconciledSkipped.clear();
                result.updated = 0;
                result.newPayees = 0;
                result.aenderungen.leeren();
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
     * Füllt die Ansage zu einer geschriebenen Buchung mit dem, was die App mit ihr vorhat – berechnet
     * aus der Buchung selbst, nicht aus dem eben gebauten XML (siehe {@link KmyAbsicht}).
     */
    private void absicht(KmyAenderungen.Absicht ziel, Booking b, Map<Long, List<BookingSplit>> splitsMap) {
        List<BookingSplit> teile = splitsMap.get(b.id);
        ziel.soll = KmyAbsicht.fuerBuchung(b, teile, doc);
        KmyAbsicht.seiten(ziel, b, teile, doc);
        ziel.bezeichnung = label(b) + ", " + dateFor(b.createdAt);
    }

    /**
     * Entfernt Transaktionen, die einer lokal gelöschten Buchung entsprechen, aus der XML – für die
     * Buchungs-Lösch-Synchronisierung im kmy-Modus. KMyMoney-Transaktionen tragen aus App-Sicht keine
     * bekannte id (weder von der App selbst importierte noch am Rechner angelegte Buchungen werden bisher
     * mit ihrer Transaktions-id verknüpft), daher wird stattdessen über den Inhalt gesucht: Konto + Datum
     * (auf den Tag genau) + der vorzeichenbehaftete Betrag des Kontosplits müssen zu {@link KmyPendingDelete}
     * passen, sowie – falls bekannt – der Empfänger. Trifft eine Vormerkung auf mehrere gleichartige
     * Transaktionen (z. B. zwei identische Beträge am selben Tag mit demselben oder ohne Empfänger), wird
     * nur die erste noch nicht verbrauchte entfernt – ein bekanntes, seltenes Restrisiko.
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
        List<String> sigPayeeId = new ArrayList<>();
        List<Long> sigDeleteId = new ArrayList<>();
        List<String> sigLabel = new ArrayList<>();
        for (KmyPendingDelete d : deletes) {
            String assetId = doc.accountId(d.account);
            if (assetId == null) {
                continue; // Konto nicht mehr vorhanden → nichts zuzuordnen
            }
            sigAccountId.add(assetId);
            sigDate.add(dateFor(d.createdAt));
            sigCents.add(d.signedCents);
            sigPayeeId.add(doc.payeeId(d.payee));
            sigDeleteId.add(d.id);
            sigLabel.add((d.payee == null || d.payee.trim().isEmpty()
                    ? ctx.getString(de.spahr.ausgaben.R.string.no_payee) : d.payee.trim())
                    + ", " + dateFor(d.createdAt));
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
                            && hasSplit(tx, sigAccountId.get(i), sigCents.get(i), sigPayeeId.get(i))) {
                        matchIdx = i;
                        break;
                    }
                }
            }
            if (matchIdx >= 0 && isReconciled(tx)) {
                // In KMyMoney inzwischen abgeglichen: die Transaktion bleibt stehen, die Vormerkung
                // auch. Verbraucht ist sie für diesen Lauf trotzdem, damit sie keinen Zwilling trifft.
                consumed[matchIdx] = true;
                result.reconciledIds.add(sigDeleteId.get(matchIdx));
                result.reconciledSkipped.add(sigLabel.get(matchIdx));
            } else if (matchIdx >= 0) {
                consumed[matchIdx] = true;
                result.resolvedIds.add(sigDeleteId.get(matchIdx));
                result.aenderungen.geloescht(attributeOfOpeningTag(tx, ID_ATTR));
                // Steht der Block auf eigenen Zeilen, gehen sie ganz – samt Einrückung und
                // Zeilenende. Sonst bliebe eine leere Zeile zurück, und die Datei stünde nach
                // „schreiben und wieder löschen" nicht mehr da wie zuvor.
                int from = m.start();
                int to = m.end();
                String indent = indentBefore(xml, from);
                if (indent != null) {
                    int e = to;
                    while (e < xml.length() && (xml.charAt(e) == ' ' || xml.charAt(e) == '\t')) {
                        e++;
                    }
                    if (xml.startsWith("\r\n", e)) {
                        from -= indent.length();
                        to = e + 2;
                    } else if (xml.startsWith("\n", e)) {
                        from -= indent.length();
                        to = e + 1;
                    }
                }
                sb.append(xml, last, from);
                last = to;
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

        /**
         * Die XML mit {@code replacement} an der Stelle des Fundes. Steht der alte Block auf eigenen
         * Zeilen, kommt der neue ebenso zu stehen – ein Element je Zeile, mit der Einrückung des alten.
         */
        String replacedBy(String replacement) {
            String indent = indentBefore(head, start);
            if (indent != null) {
                // Eine Transaktion steht zwei Ebenen tief; die halbe Einrückung ist eine Ebene.
                String unit = indent.length() >= 2 && indent.length() % 2 == 0
                        ? indent.substring(0, indent.length() / 2) : " ";
                String eol = eolOf(head);
                String lines = gegliedert(replacement, indent, unit, eol);
                if (lines != null) {
                    replacement = lines.substring(indent.length(), lines.length() - eol.length());
                }
            }
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
        String payeeId = doc.payeeId(de.spahr.ausgaben.db.EditStatus.filePayee(b));
        return findTransactionBySignature(xml, accountId, date, cents, payeeId, replacedTxIds);
    }

    /**
     * Wie {@link #findTransaction}, aber mit einer schon aufgelösten Signatur statt eines
     * {@link Booking} – für den Wiederherstellungs-Abgleich nach einem Absturz
     * ({@link #transactionExists}), wo nur die zum Schreibzeitpunkt vermerkte Signatur vorliegt, nicht
     * mehr das (inzwischen vielleicht geänderte) Buchungsobjekt selbst.
     *
     * @param payeeId zusätzliches, optionales Kriterium (siehe {@link #hasSplit}); {@code null}, wenn
     *                der Empfänger unbekannt ist oder in dieser Datei keine passende {@code PAYEE}-id hat
     */
    private Found findTransactionBySignature(String xml, String accountId, String date, long cents,
                                              String payeeId, Set<String> replacedTxIds) {
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
            if (postdate == null || !postdate.equals(date) || !hasSplit(tx, accountId, cents, payeeId)) {
                continue;
            }
            replacedTxIds.add(txId);
            return new Found(txId, tx, head, tail, m.start(), m.end());
        }
        return null;
    }

    /**
     * Steht schon eine Transaktion mit dieser Signatur (Konto, Betrag, Datum, optional Empfänger) im
     * Hauptbuch? Für die Wiederherstellung nach einem Absturz zwischen erfolgreichem Schreiben und dem
     * lokalen Markieren als „exportiert" (siehe {@code KmyExportCoordinator.exportUnexported}): Vor
     * einem erneuten Schreibversuch wird geprüft, ob der vorherige Versuch die Datei doch schon erreicht
     * hat.
     *
     * @param payeeName Empfänger zum Schreibzeitpunkt, leer/unbekannt = kein zusätzliches Kriterium
     * @param replacedTxIds geteiltes Set über den ganzen Wiederherstellungs-Durchlauf (nicht pro
     *                      Buchung neu!), sonst könnten zwei zufällig gleich signierte Einträge
     *                      denselben einzelnen Transaktionsblock doppelt treffen
     */
    boolean transactionExists(String xml, String accountName, long signedCents, long createdAtMillis,
                               String payeeName, Set<String> replacedTxIds) {
        String accountId = doc.accountId(accountName);
        if (accountId == null) {
            return false;
        }
        String payeeId = doc.payeeId(payeeName);
        return findTransactionBySignature(xml, accountId, dateFor(createdAtMillis), signedCents, payeeId,
                replacedTxIds) != null;
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
     * Ist die Transaktion in KMyMoney abgeglichen, trägt also irgendeiner ihrer Splits
     * {@code reconcileflag="2"}? Dann gehört sie zu einer abgeschlossenen Periode, und die App ändert
     * und löscht sie nicht mehr – auch dann nicht, wenn der Abgleich erst nach der letzten Bearbeitung
     * in der App geschah und die Buchung hier noch als änderbar gilt.
     */
    private static boolean isReconciled(String tx) {
        Matcher sm = SPLIT_TAG.matcher(tx);
        while (sm.find()) {
            if (RECONCILED_ATTR.matcher(sm.group()).find()) {
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

    /**
     * Trägt die Transaktion einen Split auf diesem Konto mit genau diesem Betrag – und, falls
     * {@code payeeId} gesetzt ist, auch mit genau diesem Empfänger? Der Empfänger ist ein zusätzliches,
     * optionales Kriterium: {@code null}/leer bedeutet „unbekannt/keiner", dann entscheidet wie bisher
     * nur Konto+Betrag. Bei einer mehrteiligen Buchung darf ein Split mit passendem Konto/Betrag aber
     * falschem Empfänger einen später folgenden, wirklich passenden Split nicht verdecken – deshalb wird
     * bei einem Empfänger-Fehltreffer weitergesucht statt sofort aufzugeben.
     */
    private static boolean hasSplit(String tx, String accountId, long cents, String payeeId) {
        Matcher sm = SPLIT_TAG.matcher(tx);
        while (sm.find()) {
            String splitTagXml = sm.group();
            Matcher am = ACCOUNT_ATTR.matcher(splitTagXml);
            Matcher vm = VALUE_ATTR.matcher(splitTagXml);
            if (am.find() && vm.find() && am.group(1).equals(accountId)
                    && valueToCents(vm.group(1)) == cents) {
                if (payeeId == null || payeeId.isEmpty()) {
                    return true;
                }
                Matcher pm = PAYEE_ATTR.matcher(splitTagXml);
                if (pm.find() && payeeId.equals(pm.group(1))) {
                    return true;
                }
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
            result.aenderungen.planung(a.kmyId.trim());
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
            result.aenderungen.neu(txId).soll = KmyAbsicht.fuerBewegung(tx, doc);
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
            result.aenderungen.leeren();
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
            splits.add(securitySplit(splitId(index), stockId, fraction(0), "0/1", "1/1", "Dividend", memo));
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
        return openTag("SPLIT", splitOrder, SPLIT_DEFAULT,
                "id", id, "payee", "", "reconciledate", "", "action", action, "reconcileflag", "0",
                "value", value, "shares", shares, "price", price, "memo", memo,
                "account", esc(accountId), "number", "", "bankid", "") + "/>";
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
            result.aenderungen.neuerEmpfaenger(existing);
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
        return openTag("TRANSACTION", txOrder, TX_DEFAULT, "id", txId, "postdate", postdate,
                "memo", m, "entrydate", entrydate, "commodity", esc(commodity)) + ">"
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
        String open = openTag("SPLIT", splitOrder, SPLIT_DEFAULT,
                "id", id, "payee", esc(payeeId), "reconciledate", "", "action", "", "reconcileflag", "0",
                "value", value, "shares", value, "price", "1/1", "memo", memo,
                "account", esc(accountId), "number", "", "bankid", "");
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
        return openTag("PAYEE", payeeOrder, PAYEE_DEFAULT, "id", id, "name", esc(name),
                "reference", "", "email", "", "matchingenabled", "1", "usingmatchkey", "0",
                "matchignorecase", "1", "matchkey", "") + ">"
                + openTag("ADDRESS", addressOrder, ADDRESS_DEFAULT, addressPairs()) + "/>"
                + "</PAYEE>";
    }

    /**
     * Die Attribute der (leeren) Adresse eines neuen Empfängers. Wie die Postleitzahl heißt, hängt von
     * der KMyMoney-Fassung ab, die die Datei zuletzt geschrieben hat: {@code postcode} bei 5.x,
     * {@code zip} samt den alten Namen {@code zipcode} und {@code postcode} bei der aktuellen; ähnlich
     * {@code state}, {@code country} und {@code county}. Führt die Datei schon eine Empfänger-Adresse,
     * bekommt die neue deshalb genau deren Attribute – alle leer, es sind reine Textfelder. Ohne
     * Vorlage bleibt es bei den fünf, die jede Fassung liest.
     */
    private String[] addressPairs() {
        String[] names = addressOrder.length > 0 ? addressOrder : ADDRESS_DEFAULT;
        String[] pairs = new String[names.length * 2];
        for (int i = 0; i < names.length; i++) {
            pairs[2 * i] = names[i];
            pairs[2 * i + 1] = "";
        }
        return pairs;
    }

    /**
     * Ein Betrag in Cent als Bruch, gekürzt: 80,00 wird {@code 80/1}, 12,40 wird {@code 62/5}. So
     * schreibt KMyMoney seine Beträge selbst. Der Wert ist derselbe wie {@code 8000/100}, aber eine
     * geänderte Buchung unterscheidet sich im Zeilenvergleich dann nur im Betrag und nicht auch noch
     * in der Schreibweise.
     */
    static String fraction(long signedCents) {
        return reduced(signedCents, 100);
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
     * <p>Geschrieben wird, wie KMyMoney selbst schreibt: ein Element je Zeile, je Ebene eingerückt wie
     * der Bestand. Das ändert am Inhalt nichts, macht aber einen Zeilenvergleich der Datei lesbar – je
     * Split eine Zeile statt aller neuen Buchungen in einer einzigen. Nur eine Datei, die selbst keine
     * Zeilen kennt, bekommt das Fragment wie früher am Stück.</p>
     *
     * @param fragment die neuen Elemente am Stück, ohne Leerraum dazwischen
     * @return das ergänzte XML oder {@code null}, wenn es den Block gar nicht gibt (dann darf auch das
     *         count-Attribut nicht hochgezählt werden)
     */
    static String insertIntoBlock(String xml, String tag, String fragment) {
        boolean zeilenweise = xml.indexOf('\n') >= 0;
        String eol = eolOf(xml);
        int idx = xml.lastIndexOf("</" + tag + ">");
        if (idx >= 0) {
            String closing = zeilenweise ? indentBefore(xml, idx) : null;
            if (closing != null) {
                // Der Regelfall: das schließende Tag steht allein auf seiner Zeile. Der Behälter liegt
                // eine Ebene tief, seine Einrückung ist also zugleich die Schrittweite.
                String unit = closing.isEmpty() ? " " : closing;
                String lines = gegliedert(fragment, closing + unit, unit, eol);
                if (lines != null) {
                    int lineStart = idx - closing.length();
                    return xml.substring(0, lineStart) + lines + xml.substring(lineStart);
                }
            } else if (zeilenweise) {
                // Vor dem schließenden Tag steht noch etwas auf der Zeile – so schrieb die App bis
                // 2.2 ihre Buchungen. Ab hier geht es zeilenweise weiter.
                String line = lineIndent(xml, idx);
                String unit = line.isEmpty() ? " " : line;
                String lines = gegliedert(fragment, line + unit, unit, eol);
                if (lines != null) {
                    return xml.substring(0, idx) + eol + lines + line + xml.substring(idx);
                }
            }
            return xml.substring(0, idx) + fragment + xml.substring(idx);
        }
        Matcher m = Pattern.compile("<" + tag + "\\b[^>]*/>").matcher(xml);
        if (!m.find()) {
            return null;
        }
        String open = m.group();
        // „<PAYEES count="0"/>" → „<PAYEES count="0">…</PAYEES>"
        String opened = open.substring(0, open.length() - 2).trim() + ">";
        String indent = zeilenweise ? indentBefore(xml, m.start()) : null;
        if (indent != null) {
            String unit = indent.isEmpty() ? " " : indent;
            String lines = gegliedert(fragment, indent + unit, unit, eol);
            if (lines != null) {
                return xml.substring(0, m.start()) + opened + eol + lines + indent + "</" + tag + ">"
                        + xml.substring(m.end());
            }
        }
        return xml.substring(0, m.start()) + opened + fragment + "</" + tag + ">"
                + xml.substring(m.end());
    }

    /** Das Zeilenende, das die Datei benutzt. */
    private static String eolOf(String xml) {
        return xml.contains("\r\n") ? "\r\n" : "\n";
    }

    /**
     * Die Einrückung vor {@code pos}, wenn dort bis zum Zeilenanfang nichts als Leerzeichen und
     * Tabulatoren stehen – sonst {@code null}: dann steht das Element nicht am Anfang einer Zeile.
     */
    private static String indentBefore(String xml, int pos) {
        int i = pos;
        while (i > 0 && (xml.charAt(i - 1) == ' ' || xml.charAt(i - 1) == '\t')) {
            i--;
        }
        return i > 0 && xml.charAt(i - 1) == '\n' ? xml.substring(i, pos) : null;
    }

    /** Die Einrückung der Zeile, in der {@code pos} liegt. */
    private static String lineIndent(String xml, int pos) {
        int start = xml.lastIndexOf('\n', pos - 1) + 1;
        int i = start;
        while (i < pos && (xml.charAt(i) == ' ' || xml.charAt(i) == '\t')) {
            i++;
        }
        return xml.substring(start, i);
    }

    /**
     * Setzt am Stück gebaute Elemente auf Zeilen: jedes Tag auf eine eigene, die erste Ebene mit
     * {@code indent} eingerückt, jede tiefere um {@code unit} weiter; jede Zeile endet mit {@code eol}.
     *
     * @return {@code null}, wenn zwischen den Tags Text steht – der Exporter baut so etwas nicht, und
     *         falls doch, bleibt es lieber am Stück, als dass Leerraum in einen Textknoten geriete
     */
    static String gegliedert(String kompakt, String indent, String unit, String eol) {
        StringBuilder out = new StringBuilder(kompakt.length() + 64);
        int depth = 0;
        int pos = 0;
        while (pos < kompakt.length()) {
            if (kompakt.charAt(pos) != '<') {
                return null;
            }
            // Ende des Tags; ein „>" in einem Attributwert zählt nicht.
            int end = -1;
            char quote = 0;
            for (int i = pos + 1; i < kompakt.length(); i++) {
                char c = kompakt.charAt(i);
                if (quote != 0) {
                    if (c == quote) {
                        quote = 0;
                    }
                } else if (c == '"' || c == '\'') {
                    quote = c;
                } else if (c == '>') {
                    end = i + 1;
                    break;
                }
            }
            if (end < 0) {
                return null;
            }
            boolean closing = kompakt.charAt(pos + 1) == '/';
            if (closing) {
                depth--;
            }
            if (depth < 0) {
                return null;
            }
            out.append(indent);
            for (int d = 0; d < depth; d++) {
                out.append(unit);
            }
            out.append(kompakt, pos, end).append(eol);
            if (!closing && kompakt.charAt(end - 2) != '/') {
                depth++;
            }
            pos = end;
        }
        return depth == 0 ? out.toString() : null;
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

    /**
     * Maskiert einen Attributwert. Dazu gehören auch Zeilenumbruch und Tabulator: Ein XML-Leser macht
     * aus einem wörtlichen Zeilenumbruch in einem Attribut ein Leerzeichen – eine mehrzeilige Notiz
     * käme in KMyMoney als eine einzige Zeile an. KMyMoney selbst schreibt deshalb {@code &#xa;}, und
     * so steht die ganze Notiz auch weiter auf der Zeile ihres Tags.
     */
    static String esc(String s) {
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
                case '\n': sb.append("&#xa;"); break;
                case '\r': sb.append("&#xd;"); break;
                case '\t': sb.append("&#x9;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
