package de.spahr.ausgaben.wear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class WearPayeeChoiceTest {

    /** Der Fall vom 25.09.2026: „Mama 10 €" gesagt, vorbelegt war der vorige Empfänger. */
    @Test
    public void neueEingabeUebernimmtKeineAlteWahl() {
        WearPayeeChoice c = new WearPayeeChoice();
        c.neu("Hermann Riedel", Arrays.asList("Hermann Riedel", "Bäckerei Mayer"));
        assertEquals("Hermann Riedel", c.gewaehlt());

        c.neu("Mama", Arrays.asList("Hermann Riedel", "Bäckerei Mayer"));

        assertEquals("Mama", c.gewaehlt());
        assertTrue(c.aufGesprochenem());
        assertEquals(Arrays.asList("Mama", "Hermann Riedel", "Bäckerei Mayer"), c.namen());
    }

    @Test
    public void gesprochenerStehtVornAuchOhneNahe() {
        WearPayeeChoice c = new WearPayeeChoice();
        c.neu("Mama", Collections.emptyList());
        assertEquals("Mama", c.gewaehlt());
        assertEquals(1, c.anzahl());
    }

    @Test
    public void gesprochenerNahEntfaelltDoppelt() {
        WearPayeeChoice c = new WearPayeeChoice();
        c.neu("bäckerei mayer", Arrays.asList("Bäckerei Mayer", "Edeka"));
        assertEquals(Arrays.asList("bäckerei mayer", "Edeka"), c.namen());
    }

    @Test
    public void zahlenblockWaehltDenNaechstenNahen() {
        WearPayeeChoice c = new WearPayeeChoice();
        c.neu("Mama", Arrays.asList("Edeka"));
        c.weiter();
        c.neu("", Arrays.asList("Edeka", "Aral"));
        assertEquals("Edeka", c.gewaehlt());
        assertFalse(c.aufGesprochenem());
    }

    @Test
    public void weiterLaeuftUeberOhneEmpfaengerZurueck() {
        WearPayeeChoice c = new WearPayeeChoice();
        c.neu("", Arrays.asList("Edeka", "Aral"));
        c.weiter();
        assertEquals("Aral", c.gewaehlt());
        c.weiter();
        assertTrue(c.ohneEmpfaenger());
        assertEquals("", c.gewaehlt());
        c.weiter();
        assertEquals("Edeka", c.gewaehlt());
    }

    /** Standort kommt während des Countdowns: die Wahl bleibt, auch „ohne Empfänger". */
    @Test
    public void aktualisierenBehaeltDieWahl() {
        WearPayeeChoice c = new WearPayeeChoice();
        c.neu("Mama", Collections.emptyList());
        c.weiter(); // ohne Empfänger
        assertTrue(c.ohneEmpfaenger());

        c.aktualisieren(Arrays.asList("Edeka", "Aral"));
        assertTrue(c.ohneEmpfaenger());

        c.neu("", Arrays.asList("Edeka"));
        c.aktualisieren(Arrays.asList("Aral", "Edeka"));
        assertEquals("Edeka", c.gewaehlt());
    }

    /** Mit gesprochenem Empfänger, aber noch ohne Standort: die nahen kommen später dazu. */
    @Test
    public void spaeterStandortErgaenztDieNahen() {
        WearPayeeChoice c = new WearPayeeChoice();
        c.neu("Mama", Collections.emptyList());
        assertFalse(c.hatNahe());
        c.aktualisieren(Arrays.asList("Edeka"));
        assertTrue(c.hatNahe());
        assertEquals("Mama", c.gewaehlt());
        assertEquals(Arrays.asList("Mama", "Edeka"), c.namen());
    }

    @Test
    public void leereRundeKenntKeinOhneEmpfaenger() {
        WearPayeeChoice c = new WearPayeeChoice();
        c.neu("", Collections.emptyList());
        assertTrue(c.leer());
        assertFalse(c.ohneEmpfaenger());
        c.weiter();
        assertEquals("", c.gewaehlt());
    }
}
