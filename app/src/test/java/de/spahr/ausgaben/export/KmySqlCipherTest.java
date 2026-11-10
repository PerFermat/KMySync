package de.spahr.ausgaben.export;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;

import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Mit SQLCipher verschlüsselte KMyMoney-Datenbanken. Die Prüflinge verschlüsselt der Test selbst, so
 * wie SQLCipher 3 es tut – dass das zu dem passt, was KMyMoney schreibt, zeigt {@link #echteDatenbank}
 * mit einer von KMyMoney 5.2.2 gespeicherten Datei ({@code -Dkmy.sqlcipher=…}, Passwort „michael").
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KmySqlCipherTest {

    private static final String PASSWORT = "michael";
    private static final KmySqlCipher.Art V3 = KmySqlCipher.ARTEN[0];
    private static final KmySqlCipher.Art V4 = KmySqlCipher.ARTEN[1];

    private final Context ctx = ApplicationProvider.getApplicationContext();

    @After
    public void aufraeumen() {
        new SettingsStore(ctx).setDbPassword("");
    }

    private static byte[] salz() {
        byte[] s = new byte[16];
        new java.security.SecureRandom().nextBytes(s);
        return s;
    }

    /** Eine verschlüsselte Datenbank mit dem Inhalt von edited.xml. */
    private byte[] verschluesselt(KmySqlCipher.Art art, int seite) throws Exception {
        byte[] klar = KmyTestDb.ausFixture(ctx, "edited.xml", seite, art.reserve);
        return KmySqlCipher.verschluesseln(klar, KmySqlCipher.huelle(PASSWORT, art, seite, salz()));
    }

    @Test
    public void hinUndZurueck() throws Exception {
        for (KmySqlCipher.Art art : KmySqlCipher.ARTEN) {
            int seite = art == V3 ? 1024 : 4096;
            byte[] klar = KmyTestDb.ausFixture(ctx, "edited.xml", seite, art.reserve);
            byte[] zu = KmySqlCipher.verschluesseln(klar, KmySqlCipher.huelle(PASSWORT, art, seite, salz()));
            assertFalse(KmySqlite.istSqlite(zu));
            assertTrue(KmySqlCipher.vielleicht(zu));
            KmySqlCipher.Ergebnis e = KmySqlCipher.entschluesseln(zu, PASSWORT);
            assertEquals("SQLCipher " + art.name + ", Seite " + seite, e.huelle.toString());
            assertArrayEquals(KmySqlCipher.ohneReserve(klar, e.huelle), e.klar);
        }
    }

    @Test
    public void unverschluesseltesWirdNichtFuerSQLCipherGehalten() throws Exception {
        assertFalse(KmySqlCipher.vielleicht(KmyTestDb.ausFixture(ctx, "edited.xml")));
        assertFalse(KmySqlCipher.vielleicht(KmyRobustnessTest.fixture("edited.xml")));
        assertFalse(KmySqlCipher.vielleicht(KmyDocument.gzip(new String(
                KmyRobustnessTest.fixture("edited.xml"), java.nio.charset.StandardCharsets.UTF_8))));
        assertFalse(KmySqlCipher.vielleicht(KmyRobustnessTest.fixture("gpg/edited.kmy")));
    }

    @Test
    public void falschesPasswortUndVeraenderteSeite() throws Exception {
        byte[] zu = verschluesselt(V3, 1024);
        try {
            KmySqlCipher.entschluesseln(zu, "falsch");
            fail();
        } catch (KmySqlCipher.Fehler e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Passwort"));
        }
        zu[3 * 1024 + 100] ^= 0x01;
        try {
            KmySqlCipher.entschluesseln(zu, PASSWORT);
            fail();
        } catch (KmySqlCipher.Fehler e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Seite 4"));
        }
    }

    /** Mit dem Passwort im Profil: lesen, eine Buchung schreiben, verschlüsseln, wieder lesen. */
    @Test
    public void lesenExportierenZurueckschreiben() throws Exception {
        new SettingsStore(ctx).setDbPassword(PASSWORT);
        byte[] zu = verschluesselt(V3, 1024);
        KmyDocument d = new KmyDocument(zu, ctx);
        assertNotNull(d.sqlcipher());
        assertTrue(d.istDatenbank());
        assertEquals(new KmyDocument(KmyTestDb.ausFixture(ctx, "edited.xml"), ctx).xml(), d.xml());
        schreibeUndPruefe(d);
    }

    /** Eine Testbuchung, die in jeder Datei geht: erstes Konto, erste Ausgabekategorie. */
    static Booking testbuchung(KmyDocument d) {
        Booking b = new Booking();
        b.id = 5;
        b.account = d.accountNames().get(0);
        for (java.util.Map.Entry<String, Boolean> k : d.categoryTypesByPath().entrySet()) {
            if (!k.getValue()) {
                b.category = k.getKey();
                break;
            }
        }
        b.payee = "Kiosk";
        b.note = "KMySync-Test";
        b.amountCents = 1240;
        b.createdAt = KmyDocument.parseKmyDate("2026-02-01");
        return b;
    }

    private byte[] schreibeUndPruefe(KmyDocument d) throws Exception {
        Booking b = testbuchung(d);
        KmyExporter.Result r = new KmyExporter(d, ctx).build(
                Collections.singletonList(b), Collections.emptyList(), new HashMap<>());
        assertEquals(Collections.emptyList(), r.skipped);
        assertEquals(1, r.writtenIds.size());
        KmyExportCheck.pruefen(d.xml(), r.xml, KmyDocument.gzip(r.xml), r.aenderungen);
        byte[] neu = KmySqliteWriter.schreibe(ctx, d.datenbankRoh(), r.xml, r.aenderungen);
        byte[] hoch = KmyExportCoordinator.verschluesseln(neu, d.sqlcipher());
        assertFalse(KmySqlite.istSqlite(hoch));
        assertEquals(r.xml, new KmyDocument(hoch, ctx).xml());
        assertEquals(r.xml, KmyDocument.alsXml(ctx, hoch));
        return hoch;
    }

    @Test
    public void ohneOderMitFalschemPasswortSagtDieMeldungWas() throws Exception {
        byte[] zu = verschluesselt(V3, 1024);
        try {
            new KmyDocument(zu, ctx);
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("SQLCipher"));
            // Daran erkennt die Oberfläche, dass sie nach dem Passwort fragen soll.
            assertTrue(e.getCause() instanceof KmySqlCipher.Fehler);
        }
        new SettingsStore(ctx).setDbPassword("falsch");
        try {
            new KmyDocument(zu, ctx);
            fail();
        } catch (IOException e) {
            assertTrue(e.getCause() instanceof KmySqlCipher.Fehler);
        }
    }

    /** Die echte, von KMyMoney 5.2.2 mit QSQLCIPHER gespeicherte Datenbank – nur, wo sie liegt. */
    @Test
    public void echteDatenbank() throws Exception {
        String pfad = System.getProperty("kmy.sqlcipher");
        Assume.assumeTrue("ohne -Dkmy.sqlcipher übersprungen", pfad != null && !pfad.isEmpty());
        byte[] zu = Files.readAllBytes(new File(pfad).toPath());
        assertTrue(KmySqlCipher.vielleicht(zu));
        KmySqlCipher.Ergebnis e = KmySqlCipher.entschluesseln(zu, PASSWORT);
        assertEquals("SQLCipher 3, Seite 1024", e.huelle.toString());

        new SettingsStore(ctx).setDbPassword(PASSWORT);
        KmyDocument d = new KmyDocument(zu, ctx);
        assertTrue(KmySqlite.geprueft(d.datenbank().version));
        assertFalse(d.accountNames().isEmpty());
        byte[] hoch = schreibeUndPruefe(d);
        String aus = System.getProperty("kmy.sqlcipher.out");
        if (aus != null && !aus.isEmpty()) {
            Files.write(new File(aus).toPath(), hoch);
        }
    }
}
