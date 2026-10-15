package de.spahr.ausgaben.notify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Die Suche nach der Benachrichtigung zu einer von Hand angelegten Buchung.
 *
 * <p>Betrag auf den Cent, Tag über die Zeit der Meldung, Empfänger nur als Zünglein an der Waage. Die
 * Fälle ohne Treffer zählen doppelt: aus der falschen Meldung zu lernen ist schlimmer, als nicht zu
 * lernen.</p>
 */
public class NotificationMatcherTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final long TAG = 24L * 60 * 60 * 1000;
    /** Mittag eines beliebigen Tages — weit genug von beiden Tagesgrenzen. */
    private static final long HEUTE = 20000 * TAG + TAG / 2;

    private long nextId = 1;

    private NotificationBuffer.Entry meldung(String app, long time, String text, String key) {
        return new NotificationBuffer.Entry(nextId++, app, time, "Kartenzahlung", text, key);
    }

    private static List<NotificationBuffer.Entry> suche(List<NotificationBuffer.Entry> buffer,
                                                        long cents, String payee) {
        return NotificationMatcher.match(buffer, cents, HEUTE, payee, UTC);
    }

    // ---- Betrag ----

    @Test
    public void betragAufDenCent() {
        List<NotificationBuffer.Entry> buffer = Arrays.asList(
                meldung("de.bank", HEUTE, "Zahlung bei REWE über 12,34 EUR", "a"),
                meldung("de.bank", HEUTE, "Zahlung bei REWE über 12,35 EUR", "b"));
        List<NotificationBuffer.Entry> found = suche(buffer, 1234L, "Rewe");
        assertEquals(1, found.size());
        assertEquals("a", found.get(0).key);
    }

    /** Die Buchung ist eine Ausgabe, die Meldung nennt den Betrag ohne Minus — und umgekehrt. */
    @Test
    public void vorzeichenIstEgal() {
        List<NotificationBuffer.Entry> ohne = Arrays.asList(
                meldung("de.bank", HEUTE, "Zahlung über 12,34 EUR", "a"));
        assertEquals(1, suche(ohne, -1234L, "").size());
        List<NotificationBuffer.Entry> mit = Arrays.asList(
                meldung("de.bank", HEUTE, "Umsatz -12,34 € bei REWE", "a"));
        assertEquals(1, suche(mit, 1234L, "").size());
    }

    /** Eine Zahl ohne Währung ist kein Betrag, auch wenn sie zufällig passt. */
    @Test
    public void zahlOhneWaehrungZaehltNicht() {
        List<NotificationBuffer.Entry> buffer = Arrays.asList(
                meldung("de.bank", HEUTE, "Kurs 12,34 erreicht, Gebühr 1,00 EUR", "a"));
        assertTrue(suche(buffer, 1234L, "").isEmpty());
    }

    // ---- Datum ----

    @Test
    public void einTagDanebenGiltNoch() {
        List<NotificationBuffer.Entry> gestern = Arrays.asList(
                meldung("de.bank", HEUTE - TAG, "Zahlung über 12,34 EUR", "a"));
        assertEquals(1, suche(gestern, 1234L, "").size());
        List<NotificationBuffer.Entry> morgen = Arrays.asList(
                meldung("de.bank", HEUTE + TAG, "Zahlung über 12,34 EUR", "a"));
        assertEquals(1, suche(morgen, 1234L, "").size());
    }

    @Test
    public void zweiTageDanebenNichtMehr() {
        List<NotificationBuffer.Entry> buffer = Arrays.asList(
                meldung("de.bank", HEUTE - 2 * TAG, "Zahlung über 12,34 EUR", "a"));
        assertTrue(suche(buffer, 1234L, "").isEmpty());
    }

    /** Gezählt wird in Kalendertagen: 23:59 gestern und 00:01 heute liegen einen Tag auseinander. */
    @Test
    public void gezaehltWirdInKalendertagen() {
        long kurzVorMitternachtVorgestern = HEUTE - TAG / 2 - TAG - 60_000;
        List<NotificationBuffer.Entry> buffer = Arrays.asList(
                meldung("de.bank", kurzVorMitternachtVorgestern, "Zahlung über 12,34 EUR", "a"));
        assertTrue(suche(buffer, 1234L, "").isEmpty());
    }

    // ---- mehrere Treffer ----

    /** Zwei Apps melden denselben Betrag: beide kommen zurück, der Nutzer wählt. */
    @Test
    public void verschiedeneAppsKommenBeideZurueck() {
        List<NotificationBuffer.Entry> buffer = Arrays.asList(
                meldung("de.bank", HEUTE, "Zahlung über 12,34 EUR", "a"),
                meldung("de.paypal", HEUTE, "Sie haben 12,34 EUR gesendet", "b"));
        assertEquals(2, suche(buffer, 1234L, "").size());
    }

    /** Dieselbe App, derselbe Betrag, zwei Händler: der Empfänger entscheidet. */
    @Test
    public void empfaengerEntscheidetInnerhalbEinerApp() {
        List<NotificationBuffer.Entry> buffer = Arrays.asList(
                meldung("de.bank", HEUTE, "Zahlung bei REWE Markt über 12,34 EUR", "a"),
                meldung("de.bank", HEUTE, "Zahlung bei Tankstelle Nord über 12,34 EUR", "b"));
        List<NotificationBuffer.Entry> found = suche(buffer, 1234L, "Rewe");
        assertEquals(1, found.size());
        assertEquals("a", found.get(0).key);
    }

    /** Unscharf: gebucht „Bäckerei Schmitt", gemeldet „BAECKEREI SCHMIDT". */
    @Test
    public void empfaengerWirdUnscharfVerglichen() {
        List<NotificationBuffer.Entry> buffer = Arrays.asList(
                meldung("de.bank", HEUTE, "Zahlung bei BAECKEREI SCHMIDT über 3,50 EUR", "a"),
                meldung("de.bank", HEUTE, "Zahlung bei Kiosk am Eck über 3,50 EUR", "b"));
        List<NotificationBuffer.Entry> found = suche(buffer, 350L, "Bäckerei Schmitt");
        assertEquals(1, found.size());
        assertEquals("a", found.get(0).key);
    }

    /**
     * Zweimal derselbe Kaffee: nicht zu unterscheiden. Beide kommen zurück, und der Nutzer sagt,
     * welcher gemeint ist — bis 2.2 gab es hier gar keinen Treffer.
     */
    @Test
    public void nichtUnterscheidbareMeldungenKommenBeideZurueck() {
        List<NotificationBuffer.Entry> buffer = Arrays.asList(
                meldung("de.bank", HEUTE, "Zahlung bei Café Central über 3,50 EUR", "a"),
                meldung("de.bank", HEUTE + 1000, "Zahlung bei Café Central über 3,50 EUR", "b"));
        assertEquals(2, suche(buffer, 350L, "Café Central").size());
        assertEquals(2, suche(buffer, 350L, "").size());
    }

    /** Trennt der Empfänger mehrere, aber nicht genau einen, bleiben die passenden zur Wahl. */
    @Test
    public void mehrerePassendeEmpfaengerBleibenZurWahl() {
        List<NotificationBuffer.Entry> buffer = Arrays.asList(
                meldung("de.bank", HEUTE, "Zahlung bei REWE Markt über 3,50 EUR", "a"),
                meldung("de.bank", HEUTE + 1000, "Zahlung bei REWE City über 3,50 EUR", "b"),
                meldung("de.bank", HEUTE + 2000, "Zahlung bei Kiosk über 3,50 EUR", "c"));
        List<NotificationBuffer.Entry> found = suche(buffer, 350L, "Rewe");
        assertEquals(2, found.size());
        assertEquals("a", found.get(0).key);
        assertEquals("b", found.get(1).key);
    }

    // ---- gleicher Schlüssel ----

    /** Viele Bank-Apps posten jede Zahlung unter derselben ID: verschiedene Beträge sind zwei Umsätze. */
    @Test
    public void gleicherSchluesselVerschiedeneBetraegeSindBeideAuffindbar() {
        List<NotificationBuffer.Entry> buffer = Arrays.asList(
                meldung("de.bank", HEUTE, "Zahlung bei REWE über 12,34 EUR", "fest"),
                meldung("de.bank", HEUTE + 60_000, "Zahlung bei Lidl über 7,50 EUR", "fest"));
        assertEquals(1, suche(buffer, 1234L, "").size());
        assertEquals(1, suche(buffer, 750L, "").size());
    }

    /** Gleicher Schlüssel, gleicher Betrag, Stunden auseinander: zwei Einkäufe, beide zur Wahl. */
    @Test
    public void gleicherSchluesselGleicherBetragStundenAuseinanderBeideZurWahl() {
        List<NotificationBuffer.Entry> buffer = Arrays.asList(
                meldung("de.bank", HEUTE - 3 * 3_600_000L, "Zahlung bei Bäckerei über 3,50 EUR", "fest"),
                meldung("de.bank", HEUTE, "Zahlung bei Bäckerei über 3,50 EUR", "fest"));
        assertEquals(2, suche(buffer, 350L, "").size());
    }

    /** Der Empfänger ist nur Zünglein: passt er nicht, bleibt der einzige Treffer trotzdem einer. */
    @Test
    public void einzigerTrefferBrauchtKeinenPassendenEmpfaenger() {
        List<NotificationBuffer.Entry> buffer = Arrays.asList(
                meldung("de.bank", HEUTE, "Zahlung bei REWE SAGT DANKE 4711 über 12,34 EUR", "a"));
        assertEquals(1, suche(buffer, 1234L, "Supermarkt").size());
    }

    /** Die App hat ihre Meldung aktualisiert: gleicher Schlüssel, es gilt die jüngste Fassung. */
    @Test
    public void aktualisierteMeldungZaehltEinmal() {
        List<NotificationBuffer.Entry> buffer = new ArrayList<>();
        buffer.add(meldung("de.bank", HEUTE, "Vorgemerkt: 12,34 EUR bei REWE", "a"));
        NotificationBuffer.Entry neu = meldung("de.bank", HEUTE + 5000, "Gebucht: 12,34 EUR bei REWE", "a");
        buffer.add(neu);
        List<NotificationBuffer.Entry> found = suche(buffer, 1234L, "");
        assertEquals(1, found.size());
        assertEquals(neu.id, found.get(0).id);
    }

    @Test
    public void leererPuffer() {
        assertTrue(suche(new ArrayList<NotificationBuffer.Entry>(), 1234L, "Rewe").isEmpty());
    }

    /** Mit dem Zahlenformat des Profils findet die Buchung über 1.234,00 auch „1.234 €". */
    @Test
    public void ganzzahligerBetragNachZahlenformat() {
        List<NotificationBuffer.Entry> buffer = Arrays.asList(
                meldung("de.bank", HEUTE, "Sie haben 1.234 € bei Media Markt bezahlt", "a"));
        assertTrue(suche(buffer, 123400L, "").isEmpty());
        assertEquals(1, NotificationMatcher.match(buffer, 123400L, HEUTE, "", UTC,
                de.spahr.ausgaben.statement.MoneyStyle.forNumberFormat("de_group")).size());
    }
}
