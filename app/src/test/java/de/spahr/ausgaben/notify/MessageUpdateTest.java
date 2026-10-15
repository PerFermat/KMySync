package de.spahr.ausgaben.notify;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Wann zwei Meldungen dieselbe sind: gleicher Schlüssel, gemeinsamer Betrag, höchstens zehn Minuten
 * auseinander. Fehlt eines davon, sind es zwei Umsätze.
 */
public class MessageUpdateTest {

    private static final long MIN = 60_000L;
    private static final List<Long> ZWOELF = Collections.singletonList(1234L);

    private static boolean update(String keyB, long gap, List<Long> amountsB) {
        return MessageUpdate.isUpdate("de.bank", "k", 0L, ZWOELF, "de.bank", keyB, gap, amountsB);
    }

    @Test
    public void vorgemerktDannGebucht() {
        assertTrue(update("k", 5_000L, ZWOELF));
        assertTrue(update("k", 10 * MIN, ZWOELF));
    }

    @Test
    public void mehrAlsZehnMinutenSindZweiUmsaetze() {
        assertFalse(update("k", 10 * MIN + 1, ZWOELF));
    }

    @Test
    public void andererBetragIstImmerEinZweiterUmsatz() {
        assertFalse(update("k", 5_000L, Collections.singletonList(750L)));
    }

    /** Ein gemeinsamer Betrag genügt: die Aktualisierung kann einen Fremdwährungsbetrag dazuschreiben. */
    @Test
    public void einGemeinsamerBetragGenuegt() {
        assertTrue(update("k", 5_000L, Arrays.asList(1100L, 1234L)));
    }

    @Test
    public void andererSchluesselOderAndereAppNie() {
        assertFalse(update("anders", 5_000L, ZWOELF));
        assertFalse(MessageUpdate.isUpdate("de.bank", "k", 0L, ZWOELF, "de.karte", "k", 0L, ZWOELF));
    }

    /** Ohne Schlüssel (Testeingang, alte Einträge) lässt sich nichts zusammenführen. */
    @Test
    public void leererSchluesselNie() {
        assertFalse(MessageUpdate.isUpdate("de.bank", "", 0L, ZWOELF, "de.bank", "", 0L, ZWOELF));
    }
}
