package de.spahr.ausgaben.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import de.spahr.ausgaben.util.SecurityAmounts.Field;

/** Sichern und Wiederherstellen des Maskenstands der Wertpapier-Erfassung über ein Bundle. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SecurityTxEditStateTest {

    @Test
    public void rundreiseUeberDasBundle() {
        SecurityTxEditState a = new SecurityTxEditState();
        Field f = Field.values()[0];
        a.dateKnown = true;
        a.saving = true;
        a.userSet.add(f);
        a.entschiedenFuer.put(f, 12.5);
        a.lastComputed = f;
        a.fixedFeeCategory = "Gebühren";
        a.listHint = 3;
        a.learnAction = "buy";
        a.learnShares = 1.5;
        a.learnFeeCents = 250L;

        Bundle out = new Bundle();
        a.save(out);
        SecurityTxEditState b = new SecurityTxEditState();
        b.restore(out);

        assertTrue(b.dateKnown);
        assertTrue(b.saving);
        assertTrue(b.userSet.contains(f));
        assertEquals(12.5, b.entschiedenFuer.get(f), 0.0);
        assertEquals(f, b.lastComputed);
        assertEquals("Gebühren", b.fixedFeeCategory);
        assertEquals(3, b.listHint);
        assertEquals("buy", b.learnAction);
        assertEquals(1.5, b.learnShares, 0.0);
        assertEquals(Long.valueOf(250L), b.learnFeeCents);
        assertNull("nie gesetzt bleibt leer", b.learnNetCents);
    }

    /** Ein Bundle aus einer anderen Fassung mit unbekanntem Feldnamen darf nicht abstürzen. */
    @Test
    public void unbekannterFeldnameWirdUebergangen() {
        Bundle in = new Bundle();
        in.putStringArray("s_userSet", new String[]{"GIBT_ES_NICHT"});
        SecurityTxEditState s = new SecurityTxEditState();
        s.restore(in);
        assertTrue(s.userSet.isEmpty());
        assertEquals("", s.fixedFeeCategory);
    }
}
