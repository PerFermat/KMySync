package de.spahr.ausgaben.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.Set;

import de.spahr.ausgaben.receipt.ReceiptGc;

/**
 * Der Aufräumlauf muss die Wertpapierabrechnungen sehen. Eine aus KMyMoney eingelesene Bewegung hat
 * keine Geldbuchung in der App ({@code booking_id = 0}) und trägt ihren Beleg-Tag allein selbst –
 * fragte der Lauf nur die Buchungen, hielte er die Abrechnung für verwaist und löschte sie lokal wie
 * auf dem Server.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SecurityReceiptNotesTest {

    private AppDatabase db;
    private SecurityDao dao;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase.class).allowMainThreadQueries().build();
        dao = db.securityDao();
    }

    @After
    public void tearDown() {
        db.close();
    }

    private void insert(String note) {
        SecurityTx tx = new SecurityTx();
        tx.depot = "Depot";
        tx.securityName = "ETF";
        tx.moneyAccount = "Verrechnungskonto";
        tx.note = note;
        dao.insertTx(tx);
    }

    @Test
    public void getReceiptNotes_liefertNurDieMitBeleg() {
        insert("BELEG (PDF): pdf123");
        insert("BELEG: foto456");
        insert("Sparplan, ohne Beleg");
        insert("");

        List<String> notes = dao.getReceiptNotes();
        assertEquals(2, notes.size());
    }

    @Test
    public void abrechnungEinerEingelesenenBewegungGiltNichtAlsVerwaist() {
        insert("BELEG (PDF): pdf123"); // booking_id bleibt 0 – so kommt sie aus KMyMoney

        Set<String> keep = ReceiptGc.basesOf(dao.getReceiptNotes());
        assertTrue(keep.contains("pdf123"));
        assertTrue(ReceiptGc.orphans(
                java.util.Collections.singletonList("pdf123_p1.pdf"), keep).isEmpty());
    }
}
