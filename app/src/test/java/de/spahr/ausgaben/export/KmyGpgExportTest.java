package de.spahr.ausgaben.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;

import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Eine GPG-verschlüsselte .kmy auf dem ganzen Weg durch die App: mit den Schlüsseln des Profils lesen,
 * eine Buchung exportieren, in derselben Hülle verschlüsseln – und das Ergebnis liest sich wieder als
 * genau die geprüfte Fassung.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KmyGpgExportTest {

    private final Context ctx = ApplicationProvider.getApplicationContext();

    private void schluesselHinterlegen(String passphrase) throws Exception {
        new SettingsStore(ctx).setGpg(new String(KmyRobustnessTest.fixture("gpg/testschluessel.asc"),
                StandardCharsets.US_ASCII), passphrase);
    }

    @After
    public void aufraeumen() {
        new SettingsStore(ctx).setGpg("", "");
    }

    private static Booking ausgabe() {
        Booking b = new Booking();
        b.id = 5;
        b.account = "Bargeld";
        b.category = "Essen";
        b.payee = "Kiosk";
        b.amountCents = 1240;
        b.createdAt = KmyDocument.parseKmyDate("2026-02-01");
        return b;
    }

    @Test
    public void lesenExportierenZurueckschreiben() throws Exception {
        schluesselHinterlegen("michael");
        byte[] raw = KmyRobustnessTest.fixture("gpg/edited.kmy");
        KmyDocument d = new KmyDocument(raw, ctx);
        assertNotNull(d.gpg());
        assertEquals(new KmyDocument(KmyRobustnessTest.fixture("edited.xml"), ctx).xml(), d.xml());
        assertNull(new KmyDocument(KmyRobustnessTest.fixture("edited.xml"), ctx).gpg());

        KmyExporter.Result r = new KmyExporter(d, ctx).build(
                Collections.singletonList(ausgabe()), Collections.emptyList(), new HashMap<>());
        byte[] inhalt = r.xml.getBytes(StandardCharsets.UTF_8);
        KmyExportCheck.pruefen(d.xml(), r.xml, inhalt, r.aenderungen);
        byte[] hoch = KmyExportCoordinator.verschluesseln(ctx, inhalt, d.gpg());

        assertTrue(new String(hoch, StandardCharsets.US_ASCII).startsWith("-----BEGIN PGP MESSAGE-----"));
        KmyDocument zurueck = new KmyDocument(hoch, ctx);
        assertEquals(r.xml, zurueck.xml());
        assertEquals(d.gpg().empfaenger, zurueck.gpg().empfaenger);
        assertEquals(r.xml, KmyDocument.alsXml(ctx, hoch));
    }

    @Test
    public void ohneSchluesselSagtDieMeldungWelcher() throws Exception {
        try {
            new KmyDocument(KmyRobustnessTest.fixture("gpg/edited.kmy"), ctx);
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("B06AB31369FE6554"));
        }
    }

    @Test
    public void falschePassphrase() throws Exception {
        schluesselHinterlegen("falsch");
        try {
            new KmyDocument(KmyRobustnessTest.fixture("gpg/edited.kmy"), ctx);
            fail();
        } catch (IOException e) {
            assertTrue(e.getCause() instanceof KmyGpg.Fehler);
            assertEquals(KmyGpg.Art.PASSPHRASE, ((KmyGpg.Fehler) e.getCause()).art);
        }
    }

    /** Ein weiterer Empfänger ohne hinterlegten öffentlichen Schlüssel: nichts geht auf den Server. */
    @Test
    public void fehlenderEmpfaengerVerhindertDasSchreiben() throws Exception {
        schluesselHinterlegen("michael");
        KmyDocument d = new KmyDocument(KmyRobustnessTest.fixture("gpg/edited.kmy"), ctx);
        KmyGpg.Huelle mitFremdem = new KmyGpg.Huelle(true, true,
                Arrays.asList(d.gpg().empfaenger.get(0), 0x0123456789ABCDEFL), false);
        try {
            KmyExportCoordinator.verschluesseln(ctx, d.xml().getBytes(StandardCharsets.UTF_8), mitFremdem);
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("0123456789ABCDEF"));
            assertEquals(KmyGpg.Art.EMPFAENGER_FEHLT, ((KmyGpg.Fehler) e.getCause()).art);
        }
    }
}
