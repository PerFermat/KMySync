package de.spahr.ausgaben.notify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.spahr.ausgaben.statement.NotificationLearner;
import de.spahr.ausgaben.statement.NotificationSource;
import de.spahr.ausgaben.statement.NotificationTemplate;

/**
 * Der Durchlauf beim Aktualisieren: aus Puffer und Vorlagen werden die Zeilen der Liste.
 *
 * <p>Geprüft wird die Zusage „lieber nicht erkennen als falsch erkennen" an den Stellen, an denen sie
 * etwas kostet: zwei Konten hinter einer App, Vorlagen, die sich widersprechen, eine fremde Währung —
 * und die Markierung möglicher Duplikate innerhalb des Puffers.</p>
 */
public class NotificationRunTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final long TAG = 24L * 60 * 60 * 1000;
    private static final long HEUTE = 20000 * TAG + TAG / 2;
    private static final String BANK = "de.bank";

    private long nextId = 1;

    private NotificationBuffer.Entry meldung(String app, long time, String text, String key) {
        return new NotificationBuffer.Entry(nextId++, app, time, "Kartenzahlung", text, key);
    }

    private static String zahlung(String haendler, String betrag, String karte) {
        return "Zahlung bei " + haendler + " über " + betrag + " EUR mit Karte " + karte + ".";
    }

    /** Eine Vorlage, gelernt an einer Zahlung bei REWE mit der genannten Karte. */
    private static NotificationTemplate vorlage(String account, String karte) {
        return NotificationLearner.learn(new NotificationSource(BANK, 0L, "Kartenzahlung",
                zahlung("REWE Markt", "12,34", karte)), 1234L, false, "Rewe", account);
    }

    private static List<NotificationDraft> lauf(List<NotificationBuffer.Entry> buffer,
                                                NotificationTemplate... templates) {
        return NotificationRun.drafts(buffer, Arrays.asList(templates), account -> "EUR", UTC);
    }

    // ---- Erkennen ----

    @Test
    public void erkannteMeldungWirdZurBuchbarenZeile() {
        NotificationBuffer.Entry e = meldung(BANK, HEUTE, zahlung("Lidl Markt", "7,50", "*1234"), "a");
        List<NotificationDraft> drafts = lauf(Collections.singletonList(e), vorlage("Kreditkarte", "*1234"));
        assertEquals(1, drafts.size());
        NotificationDraft d = drafts.get(0);
        assertTrue(d.recognized);
        assertEquals(e.id, d.entryId);
        assertEquals(750L, d.cents);
        assertFalse(d.isIncome);
        assertEquals("Lidl Markt", d.payee);
        assertEquals("Kreditkarte", d.account);
        assertEquals(HEUTE, d.dateMillis);
        assertTrue(d.isBookable());
    }

    /** Eine App ohne Vorlage gehört diesem Profil nicht: keine Zeile, und damit auch kein Löschen. */
    @Test
    public void appOhneVorlageKommtNichtVor() {
        List<NotificationDraft> drafts = lauf(Collections.singletonList(
                meldung("de.fremd", HEUTE, zahlung("Lidl", "7,50", "*1234"), "a")),
                vorlage("Kreditkarte", "*1234"));
        assertTrue(drafts.isEmpty());
    }

    /** Bekannte App, unbekannte Meldung: sie erscheint, ist aber nicht buchbar und hält nichts auf. */
    @Test
    public void nichtErkannteMeldungErscheintUndHaeltNichtAuf() {
        List<NotificationDraft> drafts = lauf(Collections.singletonList(
                meldung(BANK, HEUTE, "Gutschrift von 20,00 EUR von Amazon auf Karte *1234.", "a")),
                vorlage("Kreditkarte", "*1234"));
        assertEquals(1, drafts.size());
        NotificationDraft d = drafts.get(0);
        assertFalse(d.recognized);
        assertEquals(NotificationDraft.PROBLEM_UNRECOGNIZED, d.problem());
        assertFalse(d.isBookable());
        assertFalse(d.blocksSave());
        // Vorbelegt ist, was ohne Raten feststeht: das einzige Konto der App und der einzige Betrag.
        assertEquals("Kreditkarte", d.account);
        assertEquals(2000L, d.cents);
    }

    /** Nennt die Meldung zwei Beträge, wird keiner vorbelegt. */
    @Test
    public void nichtErkannteMeldungMitZweiBetraegenBleibtOhneBetrag() {
        NotificationDraft d = lauf(Collections.singletonList(
                meldung(BANK, HEUTE, "Limit 500,00 EUR, verfügbar 12,00 EUR", "a")),
                vorlage("Kreditkarte", "*1234")).get(0);
        assertEquals(0L, d.cents);
    }

    // ---- mehrere Konten hinter einer App ----

    @Test
    public void dieKartenendungEntscheidetUeberDasKonto() {
        List<NotificationDraft> drafts = lauf(Arrays.asList(
                meldung(BANK, HEUTE, zahlung("Lidl", "7,50", "*1234"), "a"),
                meldung(BANK, HEUTE, zahlung("Aldi", "9,00", "*9999"), "b")),
                vorlage("Kreditkarte", "*1234"), vorlage("Zweitkarte", "*9999"));
        assertEquals("Kreditkarte", drafts.get(0).account);
        assertEquals("Zweitkarte", drafts.get(1).account);
    }

    /** Zwei Konten, kein Merkmal: erkannt, aber ohne Konto — der Nutzer muss es wählen. */
    @Test
    public void ohneMerkmalBleibtDasKontoOffen() {
        NotificationTemplate a = NotificationLearner.learn(new NotificationSource(BANK, 0L,
                "Kartenzahlung", "Zahlung bei REWE über 12,34 EUR"), 1234L, false, "Rewe", "Kreditkarte");
        NotificationTemplate b = NotificationLearner.learn(new NotificationSource(BANK, 0L,
                "Kartenzahlung", "Zahlung bei REWE über 12,34 EUR"), 1234L, false, "Rewe", "Zweitkarte");
        NotificationDraft d = lauf(Collections.singletonList(
                meldung(BANK, HEUTE, "Zahlung bei Lidl über 7,50 EUR", "a")), a, b).get(0);
        assertTrue(d.recognized);
        assertEquals("", d.account);
        assertEquals(NotificationDraft.PROBLEM_ACCOUNT, d.problem());
        assertTrue(d.blocksSave());
    }

    /** Eine Vorlage mit Merkmal geht der ohne vor. */
    @Test
    public void vorlageMitMerkmalGehtVor() {
        NotificationTemplate ohne = NotificationLearner.learn(new NotificationSource(BANK, 0L,
                "Kartenzahlung", "Zahlung bei REWE über 12,34 EUR"), 1234L, false, "Rewe", "Girokonto");
        NotificationDraft d = lauf(Collections.singletonList(
                meldung(BANK, HEUTE, zahlung("Lidl", "7,50", "*1234"), "a")),
                ohne, vorlage("Kreditkarte", "*1234")).get(0);
        assertEquals("Kreditkarte", d.account);
    }

    /** Dieselbe Meldung, einmal als Ausgabe und einmal als Einnahme gelernt: dann lieber nichts. */
    @Test
    public void widerspruechlicheVorlagenErkennenNichts() {
        NotificationSource s = new NotificationSource(BANK, 0L, "Kartenzahlung",
                "Zahlung bei REWE über 12,34 EUR");
        NotificationTemplate ausgabe = NotificationLearner.learn(s, 1234L, false, "Rewe", "Kreditkarte");
        NotificationTemplate einnahme = NotificationLearner.learn(s, 1234L, true, "Rewe", "Kreditkarte");
        NotificationDraft d = lauf(Collections.singletonList(
                meldung(BANK, HEUTE, "Zahlung bei Lidl über 7,50 EUR", "a")), ausgabe, einnahme).get(0);
        assertFalse(d.recognized);
    }

    // ---- Fremdwährung ----

    /** Die Vorlage ist an EUR gebunden und liest Dollar nicht; die Zeile sagt, warum. */
    @Test
    public void fremdwaehrungWirdNichtGelesenAberBenannt() {
        NotificationDraft d = lauf(Collections.singletonList(
                meldung(BANK, HEUTE, zahlung("Walmart", "7,50", "*1234").replace("EUR", "USD"), "a")),
                vorlage("Kreditkarte", "*1234")).get(0);
        assertFalse(d.recognized);
        assertEquals("USD", d.foreignCurrency);
    }

    /** Erkannt, aber die Meldung nennt daneben einen Fremdbetrag: buchbar erst nach dem Blick darauf. */
    @Test
    public void erkannteZeileMitFremdbetragBrauchtDenBlickDesNutzers() {
        NotificationDraft d = lauf(Collections.singletonList(meldung(BANK, HEUTE,
                "Zahlung bei Walmart über 6,90 EUR (7,50 USD) mit Karte *1234.", "a")),
                vorlage("Kreditkarte", "*1234")).get(0);
        assertTrue(d.recognized);
        assertEquals(690L, d.cents);
        assertEquals("USD", d.foreignCurrency);
        assertEquals(NotificationDraft.PROBLEM_FOREIGN, d.problem());
        assertTrue(d.blocksSave());
        d.edited = true;
        assertTrue(d.isBookable());
    }

    @Test
    public void eigeneWaehrungAlsSymbolIstKeineFremde() {
        NotificationBuffer.Entry e = meldung(BANK, HEUTE, "Zahlung über 7,50 € bei Lidl", "a");
        assertEquals("", NotificationRun.foreignCurrency(e, "EUR"));
        assertEquals("", NotificationRun.foreignCurrency(e, "€"));
        assertEquals("EUR", NotificationRun.foreignCurrency(e, "CHF"));
        // Ohne bekannte Kontowährung wird nichts behauptet.
        assertEquals("", NotificationRun.foreignCurrency(e, ""));
    }

    // ---- Duplikate im Puffer ----

    /** Die App hat ihre Meldung aktualisiert: die ältere Fassung ist das mögliche Duplikat. */
    @Test
    public void aktualisierteMeldungMarkiertDieAeltereFassung() {
        List<NotificationDraft> drafts = lauf(Arrays.asList(
                meldung(BANK, HEUTE, zahlung("Lidl", "7,50", "*1234"), "a"),
                meldung(BANK, HEUTE + 5000, zahlung("Lidl", "7,50", "*1234"), "a")),
                vorlage("Kreditkarte", "*1234"));
        assertTrue(drafts.get(0).dupBuffer);
        assertFalse(drafts.get(1).dupBuffer);
    }

    /** Neuer Schlüssel, gleicher Inhalt am selben Tag: die spätere wird markiert, nicht entfernt. */
    @Test
    public void gleicherInhaltUnterNeuemSchluesselWirdMarkiert() {
        List<NotificationDraft> drafts = lauf(Arrays.asList(
                meldung(BANK, HEUTE, zahlung("Lidl", "7,50", "*1234"), "a"),
                meldung(BANK, HEUTE + 5000, zahlung("Lidl", "7,50", "*1234"), "b")),
                vorlage("Kreditkarte", "*1234"));
        assertEquals(2, drafts.size());
        assertFalse(drafts.get(0).dupBuffer);
        assertTrue(drafts.get(1).dupBuffer);
    }

    @Test
    public void andererBetragOderAndererTagIstKeinDuplikat() {
        List<NotificationDraft> drafts = lauf(Arrays.asList(
                meldung(BANK, HEUTE, zahlung("Lidl", "7,50", "*1234"), "a"),
                meldung(BANK, HEUTE, zahlung("Lidl", "7,51", "*1234"), "b"),
                meldung(BANK, HEUTE + TAG, zahlung("Lidl", "7,50", "*1234"), "c")),
                vorlage("Kreditkarte", "*1234"));
        for (NotificationDraft d : drafts) {
            assertFalse(d.dupBuffer);
        }
    }

    /** Nimmt der Nutzer die erste Zeile heraus, ist die zweite kein Duplikat mehr. */
    @Test
    public void markierungFolgtDemStandDerListe() {
        List<NotificationDraft> drafts = new ArrayList<>(lauf(Arrays.asList(
                meldung(BANK, HEUTE, zahlung("Lidl", "7,50", "*1234"), "a"),
                meldung(BANK, HEUTE + 5000, zahlung("Lidl", "7,50", "*1234"), "b")),
                vorlage("Kreditkarte", "*1234")));
        drafts.remove(0);
        NotificationRun.markBufferDuplicates(drafts, UTC);
        assertFalse(drafts.get(0).dupBuffer);
    }

    /** Die Grenze zwischen den Profilen: nur Meldungen der Apps, die das Profil freigegeben hat. */
    @Test
    public void nurMeldungenFreigegebenerApps() {
        List<NotificationBuffer.Entry> buffer = Arrays.asList(
                meldung(BANK, HEUTE, zahlung("Lidl", "7,50", "*1234"), "a"),
                meldung("de.andere", HEUTE, zahlung("Aldi", "9,00", "*1234"), "b"));
        List<NotificationBuffer.Entry> eigene = NotificationRun.ofApps(buffer,
                Collections.singleton(BANK));
        assertEquals(1, eigene.size());
        assertEquals(BANK, eigene.get(0).packageName);
        assertTrue(NotificationRun.ofApps(buffer, Collections.<String>emptySet()).isEmpty());
    }

    // ---- Wortlaut für die Notiz ----

    private static NotificationDraft meldungMit(String title, String text) {
        NotificationDraft d = new NotificationDraft();
        d.title = title;
        d.text = text;
        return d;
    }

    @Test
    public void wortlautIstUeberschriftUndTextInZweiZeilen() {
        assertEquals("Neuer Umsatz\nSie haben kontaktlos 23,45 € bei Rewe-Markt bezahlt.",
                meldungMit("Neuer Umsatz", "Sie haben kontaktlos 23,45 € bei Rewe-Markt bezahlt.")
                        .wording());
    }

    /** Exakt heißt: mit Satzzeichen – nicht die Fassung, an der die Erkennung arbeitet. */
    @Test
    public void wortlautBehaeltDieSatzzeichen() {
        assertEquals("Umsatz!\nZahlung bei \u201EREWE\u201C, Köln: 12,34 EUR.",
                meldungMit("Umsatz!", "Zahlung bei \u201EREWE\u201C, Köln: 12,34 EUR.").wording());
    }

    @Test
    public void wortlautOhneTitelIstNurDerText() {
        assertEquals("12,34 € bei REWE", meldungMit("", "12,34 € bei REWE").wording());
    }

    /** Wiederholt der Text den Titel schon, steht er nicht doppelt da. */
    @Test
    public void titelDerSchonImTextStehtKommtNichtDoppelt() {
        assertEquals("Neuer Umsatz über 12,34 €",
                meldungMit("Neuer Umsatz", "Neuer Umsatz über 12,34 €").wording());
    }

    /** Zeilenumbrüche gehören zum Wortlaut und bleiben; nur ihre Schreibweise wird einheitlich. */
    @Test
    public void zeilenumbruecheBleiben() {
        assertEquals("Neuer Umsatz\n12,34 €\nbei REWE\nKarte *1234",
                meldungMit("Neuer Umsatz", "12,34 €\r\nbei REWE\rKarte *1234\n").wording());
    }
}
