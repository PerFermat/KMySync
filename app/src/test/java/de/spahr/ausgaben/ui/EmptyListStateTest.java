package de.spahr.ausgaben.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Warum ist die Buchungsliste leer? Ein leerer Bildschirm sieht bei einem zu engen Filter genauso
 * aus wie eine App ohne Import – der Hinweis muss den Unterschied treffen.
 */
public class EmptyListStateTest {

    @Test
    public void volleListeSchweigt() {
        assertEquals(EmptyListState.NONE, EmptyListState.of(120, 120, false, false));
        // Auch mit Filter: Solange etwas zu sehen ist, gibt es nichts zu erklären.
        assertEquals(EmptyListState.NONE, EmptyListState.of(120, 3, true, true));
    }

    @Test
    public void frischeAppSagtWieEsWeitergeht() {
        assertEquals(EmptyListState.NO_BOOKINGS, EmptyListState.of(0, 0, false, false));
    }

    @Test
    public void zuEngerFilter() {
        assertEquals(EmptyListState.NO_MATCH, EmptyListState.of(120, 0, true, false));
    }

    @Test
    public void kontoOhneBuchungen() {
        assertEquals(EmptyListState.EMPTY_ACCOUNT, EmptyListState.of(120, 0, false, true));
    }

    @Test
    public void leereDatenbankSchlaegtDenFilter() {
        // Sonst stuende dort „Filter zuruecksetzen" – der Nutzer tippt und nichts passiert, weil es
        // ueberhaupt keine Buchungen gibt.
        assertEquals(EmptyListState.NO_BOOKINGS, EmptyListState.of(0, 0, true, true));
    }

    @Test
    public void leereKontengruppeBekommtDenselbenSatz() {
        // Kein Filter, kein einzelnes Konto, trotzdem nichts zu sehen: dann steht eine Kontengruppe
        // davor, die nichts enthaelt.
        assertEquals(EmptyListState.EMPTY_ACCOUNT, EmptyListState.of(120, 0, false, false));
    }

    @Test
    public void nurDerFilterfallBietetEinenKnopf() {
        assertTrue(EmptyListState.NO_MATCH.offersFilterReset());
        assertFalse(EmptyListState.NO_BOOKINGS.offersFilterReset());
        assertFalse(EmptyListState.EMPTY_ACCOUNT.offersFilterReset());
        assertFalse(EmptyListState.NONE.offersFilterReset());
    }
}
