package de.spahr.ausgaben.export;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

/**
 * GPG-verschlüsselte .kmy. Die Prüflinge in {@code kmy/gpg/} sind echt: {@code edited.kmy} hat
 * GnuPG 2.4 so verschlüsselt, wie KMyMoney 5.2 es tut – als Text, AEAD (OCB), ZLIB, darin das XML –,
 * an den Testschlüssel {@code testschluessel.asc} (Passphrase „michael", nur für Tests).
 */
public class KmyGpgTest {

    private static final String PASSPHRASE = "michael";
    private static final long EMPFAENGER = 0xB06AB31369FE6554L;

    private static String schluesselText() throws Exception {
        return new String(KmyRobustnessTest.fixture("gpg/testschluessel.asc"), StandardCharsets.US_ASCII);
    }

    private static KmyGpg.Schluessel schluessel() throws Exception {
        return KmyGpg.Schluessel.aus(schluesselText(), PASSPHRASE);
    }

    @Test
    public void erkenntVerschluesselteDateien() throws Exception {
        assertTrue(KmyGpg.istVerschluesselt(KmyRobustnessTest.fixture("gpg/edited.kmy")));
        assertFalse(KmyGpg.istVerschluesselt(KmyRobustnessTest.fixture("edited.xml")));
        assertFalse(KmyGpg.istVerschluesselt(KmyDocument.gzip("<KMYMONEY-FILE/>")));
        assertFalse(KmyGpg.istVerschluesselt("SQLite format 3\u0000".getBytes(StandardCharsets.US_ASCII)));
        assertFalse(KmyGpg.istVerschluesselt(new byte[0]));
    }

    /**
     * Zufällige Bytes, deren erstes Byte wie ein OpenPGP-Paketkopf aussieht – so beginnt jede 25.
     * SQLCipher-Datenbank. Erst die Pakete dahinter entscheiden, und die passen nicht.
     */
    @Test
    public void zufaelligerAnfangIstKeinGpg() {
        java.util.Random r = new java.util.Random(13);
        int[] koepfe = {0x84, 0x85, 0x86, 0x87, 0x8C, 0x8D, 0x8E, 0x8F, 0xC1, 0xC3};
        for (int i = 0; i < 500; i++) {
            byte[] b = new byte[1024];
            r.nextBytes(b);
            b[0] = (byte) koepfe[i % koepfe.length];
            assertFalse("Versuch " + i, KmyGpg.istVerschluesselt(b));
        }
    }

    @Test
    public void entschluesseltWieGnuPG() throws Exception {
        KmyGpg.Ergebnis e = KmyGpg.entschluesseln(KmyRobustnessTest.fixture("gpg/edited.kmy"), schluessel());
        assertArrayEquals(KmyRobustnessTest.fixture("edited.xml"), e.klar);
        assertTrue(e.huelle.text);
        assertTrue(e.huelle.aead);
        assertFalse(e.huelle.innenGzip);
        assertEquals(Collections.singletonList(EMPFAENGER), e.huelle.empfaenger);
    }

    /** Hin und zurück in derselben Hülle: Empfänger, Text, AEAD bleiben, der Inhalt auch. */
    @Test
    public void zurueckInDerselbenHuelle() throws Exception {
        KmyGpg.Schluessel s = schluessel();
        KmyGpg.Ergebnis vorher = KmyGpg.entschluesseln(KmyRobustnessTest.fixture("gpg/edited.kmy"), s);
        byte[] neu = KmyGpg.verschluesseln(vorher.klar, vorher.huelle, s);
        String text = new String(neu, StandardCharsets.US_ASCII);
        assertTrue(text.startsWith("-----BEGIN PGP MESSAGE-----"));
        assertFalse("keine Kopfzeile, die das Werkzeug verrät", text.contains("Version:"));

        KmyGpg.Ergebnis nachher = KmyGpg.entschluesseln(neu, s);
        assertArrayEquals(vorher.klar, nachher.klar);
        assertEquals(vorher.huelle.empfaenger, nachher.huelle.empfaenger);
        assertTrue(nachher.huelle.aead);
        assertTrue(nachher.huelle.text);
    }

    @Test
    public void klassischeHuelleBleibtKlassisch() throws Exception {
        KmyGpg.Schluessel s = schluessel();
        KmyGpg.Huelle aeadFrei = new KmyGpg.Huelle(false, false,
                Collections.singletonList(EMPFAENGER), false);
        byte[] binaer = KmyGpg.verschluesseln("<KMYMONEY-FILE/>".getBytes(StandardCharsets.UTF_8), aeadFrei, s);
        assertTrue(KmyGpg.istVerschluesselt(binaer));
        KmyGpg.Ergebnis e = KmyGpg.entschluesseln(binaer, s);
        assertFalse(e.huelle.aead);
        assertFalse(e.huelle.text);
        assertEquals("<KMYMONEY-FILE/>", new String(e.klar, StandardCharsets.UTF_8));
    }

    @Test
    public void falschePassphrase() throws Exception {
        try {
            KmyGpg.entschluesseln(KmyRobustnessTest.fixture("gpg/edited.kmy"),
                    KmyGpg.Schluessel.aus(schluesselText(), "falsch"));
            fail();
        } catch (KmyGpg.Fehler e) {
            assertEquals(KmyGpg.Art.PASSPHRASE, e.art);
        }
        try {
            KmyGpg.Schluessel.aus(schluesselText(), "falsch").pruefePassphrase();
            fail();
        } catch (KmyGpg.Fehler e) {
            assertEquals(KmyGpg.Art.PASSPHRASE, e.art);
        }
        schluessel().pruefePassphrase();
    }

    /** Nur der öffentliche Teil hinterlegt: Lesen geht nicht, und die Meldung nennt den Schlüssel. */
    @Test
    public void keinPrivaterSchluessel() throws Exception {
        KmyGpg.Huelle fremd = new KmyGpg.Huelle(true, true, Collections.singletonList(0x1234L), false);
        try {
            KmyGpg.verschluesseln(new byte[]{1}, fremd, schluessel());
            fail();
        } catch (KmyGpg.Fehler e) {
            assertEquals(KmyGpg.Art.EMPFAENGER_FEHLT, e.art);
            assertEquals(Collections.singletonList("0000000000001234"), e.schluessel);
        }
        byte[] anFremd = KmyRobustnessTest.fixture("gpg/edited.kmy");
        try {
            KmyGpg.entschluesseln(anFremd, KmyGpg.Schluessel.aus(
                    oeffentlicherTeil(), PASSPHRASE));
            fail();
        } catch (KmyGpg.Fehler e) {
            assertEquals(KmyGpg.Art.KEIN_SCHLUESSEL, e.art);
            assertEquals(Collections.singletonList("B06AB31369FE6554"), e.schluessel);
        }
    }

    /** Ein verändertes Byte mitten in den verschlüsselten Daten fällt auf, statt still Unsinn zu liefern. */
    @Test
    public void veraenderteDateiWirdErkannt() throws Exception {
        KmyGpg.Schluessel s = schluessel();
        KmyGpg.Huelle binaer = new KmyGpg.Huelle(false, true, Collections.singletonList(EMPFAENGER), false);
        byte[] inhalt = KmyRobustnessTest.fixture("edited.xml");
        for (boolean aead : new boolean[]{true, false}) {
            KmyGpg.Huelle h = new KmyGpg.Huelle(false, aead, binaer.empfaenger, false);
            byte[] bytes = KmyGpg.verschluesseln(inhalt, h, s);
            bytes[bytes.length - 40] ^= 0x01;
            try {
                KmyGpg.entschluesseln(bytes, s);
                fail("aead=" + aead);
            } catch (KmyGpg.Fehler e) {
                assertEquals(KmyGpg.Art.DEFEKT, e.art);
            }
        }
    }

    @Test
    public void beschreibtDieSchluessel() throws Exception {
        java.util.List<KmyGpg.Info> l = schluessel().beschreibe();
        assertEquals(1, l.size());
        assertEquals("E77040F3ADA7FB54", l.get(0).keyId);
        assertTrue(l.get(0).privat);
        assertTrue(l.get(0).benutzer, l.get(0).benutzer.contains("Testschlüssel"));
    }

    /**
     * Die echte, von KMyMoney 5.2.2 gespeicherte Datei – nur wo sie liegt
     * ({@code -Dkmy.gpg=…/test.verschluesselt.kmy}); sie ist zu groß fürs Repo.
     */
    @Test
    public void echteKMyMoneyDatei() throws Exception {
        String pfad = System.getProperty("kmy.gpg");
        Assume.assumeTrue("ohne -Dkmy.gpg übersprungen", pfad != null && !pfad.isEmpty());
        byte[] raw = Files.readAllBytes(new File(pfad).toPath());
        KmyGpg.Schluessel s = schluessel();
        KmyGpg.Ergebnis e = KmyGpg.entschluesseln(raw, s);
        assertTrue(new String(e.klar, 0, 64, StandardCharsets.UTF_8).startsWith("<?xml"));
        assertArrayEquals(e.klar, KmyGpg.entschluesseln(KmyGpg.verschluesseln(e.klar, e.huelle, s), s).klar);
    }

    /** Der öffentliche Teil des Testschlüssels, aus dem privaten gewonnen. */
    private static String oeffentlicherTeil() throws Exception {
        KmyGpg.Schluessel s = schluessel();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (org.bouncycastle.bcpg.ArmoredOutputStream a = new org.bouncycastle.bcpg.ArmoredOutputStream(out)) {
            for (org.bouncycastle.openpgp.PGPSecretKeyRing r : s.geheim) {
                java.util.List<org.bouncycastle.openpgp.PGPPublicKey> keys = new java.util.ArrayList<>();
                r.getPublicKeys().forEachRemaining(keys::add);
                new org.bouncycastle.openpgp.PGPPublicKeyRing(keys).encode(a);
            }
        }
        return out.toString("US-ASCII");
    }

    private static KmyGpg.Schluessel nurUnterschluessel(String passphrase) throws Exception {
        return KmyGpg.Schluessel.aus(new String(KmyRobustnessTest.fixture("gpg/nur-unterschluessel.asc"),
                StandardCharsets.US_ASCII), passphrase);
    }

    /**
     * Das Mindestmaß fürs Handy: nur der Unterschlüssel zum Verschlüsseln, der Hauptschlüssel als leerer
     * Platzhalter. Damit liest und schreibt die App die Datei genauso.
     */
    @Test
    public void nurDerUnterschluesselGenuegt() throws Exception {
        KmyGpg.Schluessel s = nurUnterschluessel(PASSPHRASE);
        s.pruefePassphrase();
        KmyGpg.Ergebnis vorher = KmyGpg.entschluesseln(KmyRobustnessTest.fixture("gpg/edited.kmy"), s);
        assertArrayEquals(KmyRobustnessTest.fixture("edited.xml"), vorher.klar);
        byte[] neu = KmyGpg.verschluesseln(vorher.klar, vorher.huelle, s);
        KmyGpg.Ergebnis nachher = KmyGpg.entschluesseln(neu, s);
        assertArrayEquals(vorher.klar, nachher.klar);
        assertEquals(Collections.singletonList(EMPFAENGER), nachher.huelle.empfaenger);
    }

    @Test
    public void nurDerUnterschluesselFalschePassphrase() throws Exception {
        try {
            nurUnterschluessel("falsch").pruefePassphrase();
            fail("falsche Passphrase nicht erkannt");
        } catch (KmyGpg.Fehler e) {
            assertEquals(KmyGpg.Art.PASSPHRASE, e.art);
        }
    }

    /** Die Warnung beim Einspielen hängt daran, ob der private Hauptschlüssel mitkommt. */
    @Test
    public void erkenntDenPrivatenHauptschluessel() throws Exception {
        assertTrue(schluessel().hatPrivatenHauptschluessel());
        assertFalse(nurUnterschluessel(PASSPHRASE).hatPrivatenHauptschluessel());
        assertTrue(nurUnterschluessel(PASSPHRASE).hatPrivat());
    }
}
