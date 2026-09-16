package de.spahr.ausgaben.receipt;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Set;

/**
 * Die Merkliste der offenen Jahreswechsel. Ohne sie war ein Beleg verloren, dessen Buchung offline
 * über einen Jahreswechsel geschoben wurde: Die Notiz nannte das neue Jahr, die Datei lag im alten,
 * und ein zweiter Versuch fand nie statt.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ReceiptMovesTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        for (String e : Receipts.moves(ctx)) {
            Receipts.removeMove(ctx, e);
        }
    }

    @Test
    public void einUmzugWirdVorgemerkt() {
        Receipts.addMove(ctx, "abc_p1.pdf", 2025, 2026);
        Set<String> offen = Receipts.moves(ctx);
        assertEquals(1, offen.size());
        assertArrayEquals(new String[]{"2025", "2026", "abc_p1.pdf"},
                Receipts.moveParts(offen.iterator().next()));
    }

    @Test
    public void erledigterUmzugVerschwindet() {
        Receipts.addMove(ctx, "abc_p1.pdf", 2025, 2026);
        Receipts.removeMove(ctx, Receipts.moves(ctx).iterator().next());
        assertTrue(Receipts.moves(ctx).isEmpty());
    }

    @Test
    public void zweiterUmzugBehaeltDenUrsprungsordner() {
        // 2024 → 2025 misslingt, danach 2025 → 2026: Die Datei liegt immer noch in 2024, also muss
        // der Eintrag von dort holen und nicht aus dem nie erreichten 2025.
        Receipts.addMove(ctx, "abc_p1.pdf", 2024, 2025);
        Receipts.addMove(ctx, "abc_p1.pdf", 2025, 2026);
        Set<String> offen = Receipts.moves(ctx);
        assertEquals(1, offen.size());
        assertArrayEquals(new String[]{"2024", "2026", "abc_p1.pdf"},
                Receipts.moveParts(offen.iterator().next()));
    }

    @Test
    public void zurueckZumAusgangsjahrLoeschtDenVorsatz() {
        // 2024 → 2025 und wieder zurück: Es gibt nichts mehr zu tun.
        Receipts.addMove(ctx, "abc_p1.pdf", 2024, 2025);
        Receipts.addMove(ctx, "abc_p1.pdf", 2025, 2024);
        assertTrue(Receipts.moves(ctx).isEmpty());
    }

    @Test
    public void verschiedeneDateienStoerenEinanderNicht() {
        Receipts.addMove(ctx, "abc_p1.pdf", 2025, 2026);
        Receipts.addMove(ctx, "abc_p1_original.jpg", 2025, 2026);
        assertEquals(2, Receipts.moves(ctx).size());
    }

    @Test
    public void gleichesJahrIstKeinUmzug() {
        Receipts.addMove(ctx, "abc_p1.pdf", 2026, 2026);
        assertTrue(Receipts.moves(ctx).isEmpty());
    }

    @Test
    public void unbrauchbareEintraegeWerdenErkannt() {
        assertNull(Receipts.moveParts(null));
        assertNull(Receipts.moveParts("abc_p1.pdf"));
        assertNull(Receipts.moveParts("2025|abc_p1.pdf"));
    }
}
