package de.spahr.ausgaben.export;

import org.bouncycastle.bcpg.AEADAlgorithmTags;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.CompressionAlgorithmTags;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPCompressedDataGenerator;
import org.bouncycastle.openpgp.PGPEncryptedData;
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator;
import org.bouncycastle.openpgp.PGPEncryptedDataList;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPLiteralDataGenerator;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider;
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyDataDecryptorFactory;
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyKeyEncryptionMethodGenerator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GPG-verschlüsselte .kmy: KMyMoney verschlüsselt die Datei auf Wunsch über GnuPG an einen oder
 * mehrere öffentliche Schlüssel (<i>Einstellungen → Verschlüsselung</i>). Die Datei bleibt eine .kmy,
 * ist aber eine OpenPGP-Nachricht.
 *
 * <p>So schreibt sie KMyMoney 5.2 mit GnuPG 2.4, nachgesehen an einer echten Datei: als Text
 * („ASCII-armored"), darin ein AEAD-Paket (AES-256, OCB), darin ZLIB, darin das XML – ohne gzip.
 * Ältere GnuPG-Fassungen schreiben statt AEAD das klassische Paket mit MDC. Gelesen wird beides.</p>
 *
 * <p>Zurückgeschrieben wird in derselben Hülle: an genau dieselben Empfänger, als Text, wenn die
 * Datei Text war, mit AEAD, wenn sie AEAD war. Fehlt der öffentliche Schlüssel eines Empfängers,
 * wird nicht geschrieben – sonst könnte dieser Empfänger, etwa KMyMoney am Rechner, die eigene Datei
 * nicht mehr öffnen.</p>
 *
 * <p>Verwendet das leichte Bc-API von BouncyCastle, keinen JCA-Provider: Das kommt ohne Reflexion
 * aus, und R8 braucht keine Keep-Regeln dafür. Ohne Android.</p>
 */
public final class KmyGpg {

    private KmyGpg() {
    }

    /** Was beim Entschlüsseln oder Verschlüsseln schiefging – für eine verständliche Meldung. */
    public enum Art {
        /** Kein hinterlegter privater Schlüssel passt zu einem der Empfänger der Datei. */
        KEIN_SCHLUESSEL,
        /** Der private Schlüssel passt, die Passphrase nicht. */
        PASSPHRASE,
        /** Beim Zurückschreiben fehlt der öffentliche Schlüssel eines Empfängers. */
        EMPFAENGER_FEHLT,
        /** Die Datei ist keine lesbare OpenPGP-Nachricht oder wurde verändert. */
        DEFEKT
    }

    /** Fehler mit Art und, wo es um einen bestimmten Schlüssel geht, dessen Key-ID. */
    public static final class Fehler extends IOException {
        public final Art art;
        /** Die betroffenen Key-IDs, sechzehnstellig hexadezimal; leer, wenn es um keinen geht. */
        public final List<String> schluessel;

        Fehler(Art art, List<String> schluessel, String text, Throwable ursache) {
            super(text, ursache);
            this.art = art;
            this.schluessel = schluessel == null ? Collections.emptyList()
                    : Collections.unmodifiableList(schluessel);
        }
    }

    /** Die Bytes beginnen wie eine OpenPGP-Nachricht – als Text oder binär. */
    public static boolean istVerschluesselt(byte[] raw) {
        if (raw == null || raw.length < 2) {
            return false;
        }
        int i = 0;
        while (i < raw.length && i < 64 && (raw[i] == ' ' || raw[i] == '\t' || raw[i] == '\r'
                || raw[i] == '\n' || (raw[i] & 0xFF) == 0xEF || (raw[i] & 0xFF) == 0xBB
                || (raw[i] & 0xFF) == 0xBF)) {
            i++;
        }
        byte[] kopf = "-----BEGIN PGP MESSAGE-----".getBytes(StandardCharsets.US_ASCII);
        if (raw.length - i >= kopf.length) {
            boolean text = true;
            for (int k = 0; k < kopf.length; k++) {
                if (raw[i + k] != kopf[k]) {
                    text = false;
                    break;
                }
            }
            if (text) {
                return true;
            }
        }
        // Binär: das erste Paket ist ein mit öffentlichem Schlüssel (Tag 1) oder mit Passwort (Tag 3)
        // verschlüsselter Sitzungsschlüssel – im alten Format (0x84–0x8F) wie im neuen (0xC1/0xC3).
        int b = raw[0] & 0xFF;
        if ((b & 0x80) == 0) {
            return false;
        }
        int tag = (b & 0x40) != 0 ? b & 0x3F : (b >> 2) & 0x0F;
        if (tag != 1 && tag != 3) {
            return false;
        }
        // Das erste Byte allein trifft auch zufällige Daten – etwa das Salz einer SQLCipher-Datenbank
        // in jedem 25. Fall. Sicher ist es erst, wenn sich die Pakete davor als Liste lesen lassen.
        try {
            PGPObjectFactory f = new PGPObjectFactory(new ByteArrayInputStream(raw),
                    new BcKeyFingerprintCalculator());
            Object o = f.nextObject();
            return o instanceof PGPEncryptedDataList && ((PGPEncryptedDataList) o).size() > 0;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Ein Schlüssel aus dem hinterlegten Bund, für die Anzeige. */
    public static final class Info {
        /** Key-ID, sechzehnstellig hexadezimal, groß geschrieben. */
        public final String keyId;
        /** Die erste Benutzerkennung („Name &lt;mail&gt;"), leer, wenn keine. */
        public final String benutzer;
        /** Ob der private Teil hinterlegt ist – nur mit ihm lässt sich entschlüsseln. */
        public final boolean privat;
        /**
         * Privat ist nur der Unterschlüssel; der Hauptschlüssel ist ein leerer Platzhalter
         * ({@code gpg --export-secret-subkeys … <ID>!}) – das empfohlene Mindestmaß fürs Handy.
         */
        public final boolean nurUnterschluessel;

        Info(String keyId, String benutzer, boolean privat, boolean nurUnterschluessel) {
            this.keyId = keyId;
            this.benutzer = benutzer;
            this.privat = privat;
            this.nurUnterschluessel = nurUnterschluessel;
        }
    }

    /**
     * Die hinterlegten Schlüssel samt Passphrase. Der Text darf mehrere Blöcke enthalten – den
     * privaten Schlüssel aus {@code gpg --export-secret-keys --armor} und weitere öffentliche aus
     * {@code gpg --export --armor}, auch hintereinander in einer Datei.
     */
    public static final class Schluessel {
        final List<PGPSecretKeyRing> geheim = new ArrayList<>();
        final List<PGPPublicKeyRing> oeffentlich = new ArrayList<>();
        final char[] passphrase;

        private Schluessel(char[] passphrase) {
            this.passphrase = passphrase;
        }

        /** Liest die Schlüssel; wirft, wenn der Text keinen einzigen enthält. */
        public static Schluessel aus(String text, String passphrase) throws Fehler {
            Schluessel s = new Schluessel(passphrase == null ? new char[0] : passphrase.toCharArray());
            if (text != null) {
                for (String block : bloecke(text)) {
                    s.lies(block);
                }
            }
            if (s.geheim.isEmpty() && s.oeffentlich.isEmpty()) {
                throw new Fehler(Art.DEFEKT, null, "kein OpenPGP-Schlüssel im Text", null);
            }
            return s;
        }

        private void lies(String block) throws Fehler {
            try (InputStream in = PGPUtil.getDecoderStream(
                    new ByteArrayInputStream(block.getBytes(StandardCharsets.US_ASCII)))) {
                PGPObjectFactory f = new PGPObjectFactory(in, new BcKeyFingerprintCalculator());
                Object o;
                while ((o = f.nextObject()) != null) {
                    if (o instanceof PGPSecretKeyRing) {
                        geheim.add((PGPSecretKeyRing) o);
                    } else if (o instanceof PGPPublicKeyRing) {
                        oeffentlich.add((PGPPublicKeyRing) o);
                    }
                }
            } catch (IOException | RuntimeException e) {
                throw new Fehler(Art.DEFEKT, null, "Schlüssel nicht lesbar: " + e.getMessage(), e);
            }
        }

        /** Die Schlüssel für die Anzeige: Hauptschlüssel mit ihrer ersten Benutzerkennung. */
        public List<Info> beschreibe() {
            Map<Long, Info> out = new LinkedHashMap<>();
            for (PGPSecretKeyRing r : geheim) {
                PGPPublicKey haupt = r.getPublicKey();
                PGPSecretKey hauptGeheim = r.getSecretKey();
                boolean nurUnter = hauptGeheim == null || hauptGeheim.isPrivateKeyEmpty();
                out.put(haupt.getKeyID(), new Info(hex(haupt.getKeyID()), benutzer(haupt), true, nurUnter));
            }
            for (PGPPublicKeyRing r : oeffentlich) {
                PGPPublicKey haupt = r.getPublicKey();
                if (!out.containsKey(haupt.getKeyID())) {
                    out.put(haupt.getKeyID(), new Info(hex(haupt.getKeyID()), benutzer(haupt), false, false));
                }
            }
            return new ArrayList<>(out.values());
        }

        /** Ob ein privater Schlüssel dabei ist. */
        public boolean hatPrivat() {
            return !geheim.isEmpty();
        }

        /**
         * Ob der private Teil eines <b>Hauptschlüssels</b> dabei ist – der signiert und beglaubigt und
         * öffnet oft weit mehr als die .kmy. Ein Export nur des Unterschlüssels
         * ({@code gpg --export-secret-subkeys … <ID>!}) enthält ihn als leeren Platzhalter („GNU-dummy");
         * zum Lesen und Schreiben der Datei genügt das.
         */
        public boolean hatPrivatenHauptschluessel() {
            for (PGPSecretKeyRing r : geheim) {
                PGPSecretKey haupt = r.getSecretKey();
                if (haupt != null && !haupt.isPrivateKeyEmpty()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Prüft die Passphrase an jedem privaten Schlüssel: wirft {@link Art#PASSPHRASE}, wenn sie
         * für keinen taugt. Für das Einspielen in den Einstellungen.
         */
        public void pruefePassphrase() throws Fehler {
            PGPException letzter = null;
            for (PGPSecretKeyRing r : geheim) {
                for (Iterator<PGPSecretKey> it = r.getSecretKeys(); it.hasNext(); ) {
                    PGPSecretKey k = it.next();
                    if (k.isPrivateKeyEmpty()) {
                        continue;
                    }
                    try {
                        k.extractPrivateKey(entsperrer(passphrase));
                        return;
                    } catch (PGPException e) {
                        letzter = e;
                    }
                }
            }
            throw new Fehler(Art.PASSPHRASE, null, "Passphrase passt nicht", letzter);
        }

        PGPSecretKey geheimerSchluessel(long keyId) {
            for (PGPSecretKeyRing r : geheim) {
                PGPSecretKey k = r.getSecretKey(keyId);
                if (k != null && !k.isPrivateKeyEmpty()) {
                    return k;
                }
            }
            return null;
        }

        PGPPublicKey oeffentlicherSchluessel(long keyId) {
            for (PGPSecretKeyRing r : geheim) {
                PGPPublicKey k = r.getPublicKey(keyId);
                if (k != null) {
                    return k;
                }
            }
            for (PGPPublicKeyRing r : oeffentlich) {
                PGPPublicKey k = r.getPublicKey(keyId);
                if (k != null) {
                    return k;
                }
            }
            return null;
        }
    }

    /** Wie die gelesene Datei verpackt war – danach wird zurückgeschrieben. */
    public static final class Huelle {
        /** Als Text („-----BEGIN PGP MESSAGE-----") statt binär. */
        public final boolean text;
        /** AEAD-Paket (GnuPG 2.4) statt des klassischen mit MDC. */
        public final boolean aead;
        /** Die Key-IDs der Empfänger, in der Reihenfolge der Datei. */
        public final List<Long> empfaenger;
        /** Ob innen noch gzip steckte (KMyMoney schreibt reines XML). */
        public final boolean innenGzip;

        Huelle(boolean text, boolean aead, List<Long> empfaenger, boolean innenGzip) {
            this.text = text;
            this.aead = aead;
            this.empfaenger = Collections.unmodifiableList(new ArrayList<>(empfaenger));
            this.innenGzip = innenGzip;
        }
    }

    /** Der entschlüsselte Inhalt und die Hülle, in der er stand. */
    public static final class Ergebnis {
        public final byte[] klar;
        public final Huelle huelle;

        Ergebnis(byte[] klar, Huelle huelle) {
            this.klar = klar;
            this.huelle = huelle;
        }
    }

    /** Entschlüsselt die Datei mit dem ersten passenden privaten Schlüssel. */
    public static Ergebnis entschluesseln(byte[] raw, Schluessel s) throws Fehler {
        boolean text = istText(raw);
        PGPEncryptedDataList liste;
        try {
            InputStream in = PGPUtil.getDecoderStream(new ByteArrayInputStream(raw));
            PGPObjectFactory f = new PGPObjectFactory(in, new BcKeyFingerprintCalculator());
            Object o = f.nextObject();
            liste = (PGPEncryptedDataList) (o instanceof PGPEncryptedDataList ? o : f.nextObject());
            if (liste == null) {
                throw new IOException("keine verschlüsselten Daten");
            }
        } catch (IOException | RuntimeException e) {
            throw new Fehler(Art.DEFEKT, null, "keine lesbare OpenPGP-Nachricht", e);
        }

        List<Long> empfaenger = new ArrayList<>();
        PGPPublicKeyEncryptedData treffer = null;
        PGPSecretKey schluessel = null;
        boolean aead = false;
        for (PGPEncryptedData ed : liste) {
            aead |= ed.isAEAD();
            if (!(ed instanceof PGPPublicKeyEncryptedData)) {
                continue; // mit Passwort verschlüsselt – das macht KMyMoney nicht
            }
            PGPPublicKeyEncryptedData p = (PGPPublicKeyEncryptedData) ed;
            empfaenger.add(p.getKeyID());
            if (treffer == null) {
                PGPSecretKey k = s == null ? null : s.geheimerSchluessel(p.getKeyID());
                if (k != null) {
                    treffer = p;
                    schluessel = k;
                }
            }
        }
        if (treffer == null) {
            throw new Fehler(Art.KEIN_SCHLUESSEL, hexListe(empfaenger),
                    "kein passender privater Schlüssel für " + hexListe(empfaenger), null);
        }
        PGPPrivateKey privat;
        try {
            privat = schluessel.extractPrivateKey(entsperrer(s.passphrase));
        } catch (PGPException e) {
            throw new Fehler(Art.PASSPHRASE, Collections.singletonList(hex(schluessel.getKeyID())),
                    "Passphrase passt nicht", e);
        }
        try {
            InputStream klar = treffer.getDataStream(new BcPublicKeyDataDecryptorFactory(privat));
            PGPObjectFactory pf = new PGPObjectFactory(klar, new BcKeyFingerprintCalculator());
            Object m = pf.nextObject();
            if (m instanceof PGPCompressedData) {
                pf = new PGPObjectFactory(((PGPCompressedData) m).getDataStream(),
                        new BcKeyFingerprintCalculator());
                m = pf.nextObject();
            }
            if (!(m instanceof PGPLiteralData)) {
                throw new IOException("kein Inhalt in der Nachricht");
            }
            byte[] inhalt = lesen(((PGPLiteralData) m).getInputStream());
            // Beim klassischen Paket prüft erst verify() die Prüfsumme (MDC); beim AEAD-Paket hat
            // schon das Lesen jeden Abschnitt geprüft und bei einer Veränderung geworfen.
            if (!treffer.isAEAD() && treffer.isIntegrityProtected() && !treffer.verify()) {
                throw new IOException("Prüfsumme stimmt nicht – die Datei wurde verändert");
            }
            boolean gzip = inhalt.length >= 2 && (inhalt[0] & 0xFF) == 0x1f && (inhalt[1] & 0xFF) == 0x8b;
            return new Ergebnis(inhalt, new Huelle(text, aead, empfaenger, gzip));
        } catch (PGPException | IOException | RuntimeException e) {
            throw new Fehler(Art.DEFEKT, null, "Entschlüsseln fehlgeschlagen: " + e.getMessage(), e);
        }
    }

    /**
     * Verschlüsselt {@code klar} in derselben Hülle wie die gelesene Datei. Wirft
     * {@link Art#EMPFAENGER_FEHLT}, wenn der öffentliche Schlüssel eines Empfängers nicht hinterlegt ist.
     */
    public static byte[] verschluesseln(byte[] klar, Huelle h, Schluessel s) throws Fehler {
        List<PGPPublicKey> an = new ArrayList<>();
        List<String> fehlen = new ArrayList<>();
        for (long id : h.empfaenger) {
            PGPPublicKey k = s == null ? null : s.oeffentlicherSchluessel(id);
            if (k == null) {
                fehlen.add(hex(id));
            } else {
                an.add(k);
            }
        }
        if (!fehlen.isEmpty() || an.isEmpty()) {
            throw new Fehler(Art.EMPFAENGER_FEHLT, fehlen,
                    "öffentlicher Schlüssel fehlt für " + fehlen, null);
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(klar.length / 2 + 1024);
            OutputStream ziel = bytes;
            ArmoredOutputStream text = null;
            if (h.text) {
                // Ohne die Kopfzeile „Version: BCPG …" – wie GnuPG, und ohne zu verraten, womit.
                text = ArmoredOutputStream.builder().build(bytes);
                ziel = text;
            }
            BcPGPDataEncryptorBuilder art = new BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                    .setWithIntegrityPacket(true);
            if (h.aead) {
                art.setWithAEAD(AEADAlgorithmTags.OCB, 16);
            }
            PGPEncryptedDataGenerator gen = new PGPEncryptedDataGenerator(art);
            for (PGPPublicKey k : an) {
                gen.addMethod(new BcPublicKeyKeyEncryptionMethodGenerator(k));
            }
            try (OutputStream verschluesselt = gen.open(ziel, new byte[1 << 16])) {
                PGPCompressedDataGenerator zlib = new PGPCompressedDataGenerator(CompressionAlgorithmTags.ZLIB);
                try (OutputStream gepackt = zlib.open(verschluesselt)) {
                    PGPLiteralDataGenerator lit = new PGPLiteralDataGenerator();
                    try (OutputStream inhalt = lit.open(gepackt, PGPLiteralData.BINARY, "",
                            klar.length, new Date())) {
                        inhalt.write(klar);
                    }
                }
            }
            if (text != null) {
                text.close();
            }
            return bytes.toByteArray();
        } catch (PGPException | IOException | RuntimeException e) {
            throw new Fehler(Art.DEFEKT, null, "Verschlüsseln fehlgeschlagen: " + e.getMessage(), e);
        }
    }

    /** Key-ID sechzehnstellig hexadezimal, groß – so wie {@code gpg --keyid-format long} sie zeigt. */
    public static String hex(long keyId) {
        return String.format(Locale.ROOT, "%016X", keyId);
    }

    private static List<String> hexListe(List<Long> ids) {
        List<String> out = new ArrayList<>();
        for (long id : ids) {
            out.add(hex(id));
        }
        return out;
    }

    private static String benutzer(PGPPublicKey k) {
        Iterator<String> ids = k.getUserIDs();
        return ids.hasNext() ? ids.next() : "";
    }

    private static org.bouncycastle.openpgp.operator.PBESecretKeyDecryptor entsperrer(char[] passphrase)
            throws PGPException {
        return new BcPBESecretKeyDecryptorBuilder(new BcPGPDigestCalculatorProvider()).build(passphrase);
    }

    private static boolean istText(byte[] raw) {
        for (byte b : raw) {
            if (b == '-') {
                return true;
            }
            if (b != ' ' && b != '\t' && b != '\r' && b != '\n' && (b & 0x80) == 0) {
                return false;
            }
            if ((b & 0x80) != 0 && (b & 0xFF) != 0xEF && (b & 0xFF) != 0xBB && (b & 0xFF) != 0xBF) {
                return false;
            }
        }
        return false;
    }

    /** Teilt einen Text in seine „-----BEGIN PGP …"-Blöcke; ohne solche ist der ganze Text ein Block. */
    static List<String> bloecke(String text) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "-----BEGIN PGP [A-Z ]+-----.*?-----END PGP [A-Z ]+-----", java.util.regex.Pattern.DOTALL)
                .matcher(text);
        while (m.find()) {
            out.add(m.group());
        }
        if (out.isEmpty()) {
            out.add(text);
        }
        return out;
    }

    private static byte[] lesen(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
