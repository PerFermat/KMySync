package de.spahr.ausgaben.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.BookingSplit;
import de.spahr.ausgaben.db.SecurityTx;
import de.spahr.ausgaben.db.SecurityTxSplit;

/** Das Soll je Konto, berechnet aus den Daten der App – ohne Datei, ohne Android. */
public class KmyAbsichtTest {

    /** Kontoname → id nach dem Muster „k:Name", Kategorie „c:Name", Wertpapier „w:Depot/id". */
    private static final KmyAbsicht.Konten KONTEN = new KmyAbsicht.Konten() {
        @Override
        public String accountId(String name) {
            return name == null || name.isEmpty() || name.startsWith("?") ? null : "k:" + name;
        }

        @Override
        public String categoryId(String pathOrName) {
            return pathOrName == null || pathOrName.startsWith("?") ? null : "c:" + pathOrName;
        }

        @Override
        public String wertpapierKontoId(String depot, String securityKmyId) {
            return "w:" + depot + "/" + securityKmyId;
        }
    };

    private static Map<String, KmyBruch> soll(Object... paare) {
        Map<String, KmyBruch> m = new HashMap<>();
        for (int i = 0; i < paare.length; i += 2) {
            m.put((String) paare[i], KmyBruch.ausCent(((Number) paare[i + 1]).longValue()));
        }
        return m;
    }

    private static Booking buchung(boolean einnahme, long cent, String kategorie) {
        Booking b = new Booking();
        b.account = "Bargeld";
        b.isIncome = einnahme;
        b.amountCents = cent;
        b.category = kategorie;
        return b;
    }

    private static KmyBruch summe(Map<String, KmyBruch> soll) {
        KmyBruch s = KmyBruch.NULL;
        for (KmyBruch b : soll.values()) {
            s = s.plus(b);
        }
        return s;
    }

    @Test
    public void ausgabeUndEinnahme() {
        assertEquals(soll("k:Bargeld", -250, "c:Essen", 250),
                KmyAbsicht.fuerBuchung(buchung(false, 250, "Essen"), null, KONTEN));
        assertEquals(soll("k:Bargeld", 900, "c:Lohn", -900),
                KmyAbsicht.fuerBuchung(buchung(true, 900, " Lohn "), null, KONTEN));
    }

    /** Ohne Kategorie nur die Kontoseite – die Summe ist dann der Betrag, nicht 0. */
    @Test
    public void ohneKategorie() {
        Map<String, KmyBruch> s = KmyAbsicht.fuerBuchung(buchung(false, 250, ""), null, KONTEN);
        assertEquals(soll("k:Bargeld", -250), s);
        assertEquals(KmyBruch.ausCent(-250), summe(s));
    }

    @Test
    public void splitbuchung() {
        Map<String, KmyBruch> s = KmyAbsicht.fuerBuchung(buchung(false, 300, "Essen"), Arrays.asList(
                new BookingSplit(1, "Essen", 100), new BookingSplit(1, "Auto", 200)), KONTEN);
        assertEquals(soll("k:Bargeld", -300, "c:Essen", 100, "c:Auto", 200), s);
        // Zwei Teile auf dieselbe Kategorie laufen zusammen.
        assertEquals(soll("k:Bargeld", 300, "c:Lohn", -300),
                KmyAbsicht.fuerBuchung(buchung(true, 300, "Lohn"), Arrays.asList(
                        new BookingSplit(1, "Lohn", 100), new BookingSplit(1, "Lohn", 200)), KONTEN));
    }

    /** Beide Zeilen einer Umbuchung ergeben dieselbe Absicht. */
    @Test
    public void umbuchungAusBeidenSichten() {
        Booking raus = buchung(false, 1500, "");
        raus.isTransfer = true;
        raus.transferAccount = "Girokonto";
        Booking rein = buchung(true, 1500, "");
        rein.account = "Girokonto";
        rein.isTransfer = true;
        rein.transferAccount = "Bargeld";
        Map<String, KmyBruch> erwartet = soll("k:Bargeld", -1500, "k:Girokonto", 1500);
        assertEquals(erwartet, KmyAbsicht.fuerBuchung(raus, null, KONTEN));
        assertEquals(erwartet, KmyAbsicht.fuerBuchung(rein, null, KONTEN));
    }

    @Test
    public void unbekanntesKontoOderKategorie_keineAbsicht() {
        Booking b = buchung(false, 250, "Essen");
        b.account = "?";
        assertNull(KmyAbsicht.fuerBuchung(b, null, KONTEN));
        assertNull(KmyAbsicht.fuerBuchung(buchung(false, 250, "?"), null, KONTEN));
    }

    private static SecurityTx bewegung(String art, long brutto, long netto, long gebuehr) {
        SecurityTx tx = new SecurityTx();
        tx.depot = "Depot";
        tx.securityKmyId = "E000001";
        tx.moneyAccount = "Giro";
        tx.action = art;
        tx.amountCents = brutto;
        tx.netCents = netto;
        tx.feeCents = gebuehr;
        return tx;
    }

    @Test
    public void kaufUndVerkauf() {
        SecurityTx kauf = bewegung(SecurityTx.BUY, 100000, 100990, 990);
        kauf.parts.add(new SecurityTxSplit(0, false, "Gebühren", 990, "", 0));
        Map<String, KmyBruch> s = KmyAbsicht.fuerBewegung(kauf, KONTEN);
        assertEquals(soll("k:Giro", -100990, "w:Depot/E000001", 100000, "c:Gebühren", 990), s);
        assertEquals(KmyBruch.NULL, summe(s));

        SecurityTx verkauf = bewegung(SecurityTx.SELL, 100000, 99010, 990);
        verkauf.parts.add(new SecurityTxSplit(0, false, "Gebühren", 990, "", 0));
        s = KmyAbsicht.fuerBewegung(verkauf, KONTEN);
        assertEquals(soll("k:Giro", 99010, "w:Depot/E000001", -100000, "c:Gebühren", 990), s);
        assertEquals(KmyBruch.NULL, summe(s));
    }

    /** Dividende mit zwei Abzügen; der letzte nimmt den Rundungscent. */
    @Test
    public void dividende() {
        SecurityTx div = bewegung(SecurityTx.DIVIDEND, 10000, 7362, 0);
        div.parts.add(new SecurityTxSplit(0, true, "Dividenden", 10000, "", 0));
        div.parts.add(new SecurityTxSplit(0, false, "KESt", 2500, "", 1));
        div.parts.add(new SecurityTxSplit(0, false, "Soli", 137, "", 2));
        Map<String, KmyBruch> s = KmyAbsicht.fuerBewegung(div, KONTEN);
        assertEquals(soll("k:Giro", 7362, "w:Depot/E000001", 0, "c:Dividenden", -10000,
                "c:KESt", 2500, "c:Soli", 138), s);
        assertEquals(KmyBruch.NULL, summe(s));
    }

    @Test
    public void bewegungOhneGegenkategorie_keineAbsicht() {
        assertNull(KmyAbsicht.fuerBewegung(bewegung(SecurityTx.BUY, 100000, 100990, 990), KONTEN));
    }
}
