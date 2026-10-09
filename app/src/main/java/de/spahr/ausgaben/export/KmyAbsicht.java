package de.spahr.ausgaben.export;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.BookingSplit;
import de.spahr.ausgaben.db.SecurityTx;
import de.spahr.ausgaben.db.SecurityTxSplit;

/**
 * Was eine Buchung der App auf jedem Konto bewegen <b>soll</b> – berechnet aus den Daten der App
 * ({@link Booking}, {@link BookingSplit}, {@link SecurityTx}) in Cent, nicht aus dem XML, das der
 * {@link KmyExporter} daraus baut.
 *
 * <p>Das ist die zweite, unabhängige Rechnung, gegen die {@link KmyExportCheck} das Geschriebene
 * hält: Baut der Exporter einen Split mit falschem Betrag, falschem Vorzeichen oder auf dem falschen
 * Konto, stimmt die Datei nicht mehr mit dem überein, was hier steht. Aus der Datei kommt allein die
 * Übersetzung der Namen in Konto-ids ({@link Konten}). Ohne Android.</p>
 */
final class KmyAbsicht {

    /** Die Namen der App, übersetzt in die ids der Datei. */
    interface Konten {
        String accountId(String name);

        String categoryId(String pathOrName);

        /** Konto des Wertpapiers unterhalb des Depots; {@code null}, wenn es dort keines gibt. */
        String wertpapierKontoId(String depot, String securityKmyId);
    }

    private KmyAbsicht() {
    }

    private static void buche(Map<String, KmyBruch> soll, String kontoId, long cent) {
        KmyBruch bisher = soll.get(kontoId);
        KmyBruch dazu = KmyBruch.ausCent(cent);
        soll.put(kontoId, bisher == null ? dazu : bisher.plus(dazu));
    }

    /**
     * Soll je Konto-id für eine Einnahme, Ausgabe oder Umbuchung.
     *
     * @param teile die Kategorie-Teile einer Splitbuchung, sonst {@code null}
     * @return {@code null}, wenn ein Konto oder eine Kategorie in der Datei fehlt
     */
    static Map<String, KmyBruch> fuerBuchung(Booking b, List<BookingSplit> teile, Konten konten) {
        Map<String, KmyBruch> soll = new LinkedHashMap<>();
        if (b.isTransfer) {
            // Aus Sicht dieser Zeile: Einnahme = das Geld kam auf dieses Konto.
            String von = konten.accountId(b.isIncome ? b.transferAccount : b.account);
            String nach = konten.accountId(b.isIncome ? b.account : b.transferAccount);
            if (von == null || nach == null) {
                return null;
            }
            buche(soll, von, -b.amountCents);
            buche(soll, nach, b.amountCents);
            return soll;
        }
        String konto = konten.accountId(b.account);
        if (konto == null) {
            return null;
        }
        long betrag = b.isIncome ? b.amountCents : -b.amountCents;
        buche(soll, konto, betrag);
        if (teile != null && teile.size() >= 2) {
            for (BookingSplit t : teile) {
                String kategorie = konten.categoryId(t.category == null ? "" : t.category.trim());
                if (kategorie == null) {
                    return null;
                }
                buche(soll, kategorie, b.isIncome ? -t.amountCents : t.amountCents);
            }
            return soll;
        }
        String name = b.category == null ? "" : b.category.trim();
        if (!name.isEmpty()) {
            String kategorie = konten.categoryId(name);
            if (kategorie == null) {
                return null;
            }
            buche(soll, kategorie, -betrag);
        }
        // Ohne Kategorie bleibt es bei der einen Seite: die Buchung ist „nicht zugeordnet" und geht
        // bewusst unausgeglichen in die Datei.
        return soll;
    }

    /**
     * Soll je Konto-id für eine Depot-Bewegung: das Geld auf dem Verrechnungskonto, der Gegenwert auf
     * dem Wertpapier, Ertrag und Abzüge auf ihren Kategorien.
     *
     * @return {@code null}, wenn ein Konto, das Wertpapier oder eine Kategorie in der Datei fehlt
     */
    static Map<String, KmyBruch> fuerBewegung(SecurityTx tx, Konten konten) {
        String geld = konten.accountId(tx.moneyAccount);
        String papier = konten.wertpapierKontoId(tx.depot, tx.securityKmyId);
        if (geld == null || papier == null) {
            return null;
        }
        Map<String, KmyBruch> soll = new LinkedHashMap<>();
        long brutto = tx.amountCents;
        if (SecurityTx.DIVIDEND.equals(tx.action)) {
            long abzug = brutto - tx.netCents;
            buche(soll, geld, tx.netCents);
            buche(soll, papier, 0);
            // Der Ertrag kommt von der Kategorie, der Abzug geht auf sie.
            return verteile(soll, tx.partsOf(true), -1, brutto, konten)
                    && verteile(soll, tx.partsOf(false), 1, abzug, konten) ? soll : null;
        }
        boolean verkauf = SecurityTx.SELL.equals(tx.action);
        long gebuehr = tx.feeCents;
        buche(soll, geld, verkauf ? brutto - gebuehr : -(brutto + gebuehr));
        buche(soll, papier, verkauf ? -brutto : brutto);
        return verteile(soll, tx.partsOf(false), 1, gebuehr, konten) ? soll : null;
    }

    /**
     * Verteilt {@code summe} auf die Kategoriezeilen. Die letzte Zeile nimmt, was übrig ist – die
     * Zeilen einer eingelesenen Abrechnung dürfen um einen Rundungscent neben der Summe liegen, die
     * Transaktion aber nicht.
     */
    private static boolean verteile(Map<String, KmyBruch> soll, List<SecurityTxSplit> zeilen,
                                    int vorzeichen, long summe, Konten konten) {
        if (summe == 0) {
            return true;
        }
        if (zeilen.isEmpty()) {
            return false;
        }
        long rest = summe;
        for (int i = 0; i < zeilen.size(); i++) {
            String name = zeilen.get(i).category.trim();
            String kategorie = name.isEmpty() ? null : konten.categoryId(name);
            if (kategorie == null) {
                return false;
            }
            long betrag = i == zeilen.size() - 1 ? rest : zeilen.get(i).amountCents;
            rest -= betrag;
            buche(soll, kategorie, vorzeichen * betrag);
        }
        return true;
    }
}
