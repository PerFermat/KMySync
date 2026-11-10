package de.spahr.ausgaben.export;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Eine mit SQLCipher verschlüsselte KMyMoney-Datenbank: KMyMoney speichert sie so, wenn beim Anlegen
 * der Treiber {@code QSQLCIPHER} gewählt und ein Passwort vergeben wurde.
 *
 * <p>Statt der Bibliothek von SQLCipher, die native Bibliotheken für jede Prozessorart mitbrächte, wandelt
 * diese Klasse die Datei Seite für Seite um – das Format ist offen beschrieben: Die ersten 16 Bytes
 * sind das Salz; jede Seite ist mit AES-256-CBC verschlüsselt, ihre IV und ihr HMAC stehen im
 * reservierten Bereich am Seitenende; der Schlüssel entsteht per PBKDF2 aus dem Passwort. Heraus kommt
 * eine gewöhnliche SQLite-Datei, die {@link KmySqlite} wie jede andere liest und
 * {@link KmySqliteWriter} beschreibt. Danach wird sie mit demselben Salz und denselben Einstellungen,
 * aber neuen IVs wieder verschlüsselt.</p>
 *
 * <p>Erkannt werden SQLCipher 3 und 4 mit ihren Grundeinstellungen. KMyMoney 5.2.2 mit dem
 * {@code qsqlcipher}-Treiber schreibt Version 3: Seiten zu 1024 Bytes, 48 davon reserviert.
 * SQLite übernimmt die Reserve beim Schreiben aus dem Dateikopf; neue Seiten haben sie also auch.</p>
 *
 * <p>Ohne Android.</p>
 */
public final class KmySqlCipher {

    private KmySqlCipher() {
    }

    /** Die Einstellungen einer SQLCipher-Version. */
    public static final class Art {
        final String name;
        final String kdfHash;
        final int runden;
        final String hmac;
        final int hmacLaenge;
        final int reserve;

        Art(String name, String kdfHash, int runden, String hmac, int hmacLaenge, int reserve) {
            this.name = name;
            this.kdfHash = kdfHash;
            this.runden = runden;
            this.hmac = hmac;
            this.hmacLaenge = hmacLaenge;
            this.reserve = reserve;
        }
    }

    /** Die günstigere zuerst: Version 3 ist die, die KMyMoney schreibt. */
    static final Art[] ARTEN = {
            new Art("3", "HmacSHA1", 64000, "HmacSHA1", 20, 48),
            new Art("4", "HmacSHA512", 256000, "HmacSHA512", 64, 80),
    };

    private static final int SALZ = 16;
    private static final int IV = 16;
    private static final int[] SEITEN = {1024, 4096, 8192, 16384, 32768, 65536, 2048, 512};
    private static final byte[] KOPF = "SQLite format 3\u0000".getBytes(StandardCharsets.US_ASCII);

    /** Kein passendes Passwort oder keine SQLCipher-Datei. */
    public static final class Fehler extends IOException {
        Fehler(String text, Throwable ursache) {
            super(text, ursache);
        }
    }

    /** Wie die gelesene Datei verschlüsselt war – danach wird zurückgeschrieben. */
    public static final class Huelle {
        final Art art;
        final int seite;
        final byte[] salz;
        final byte[] schluessel;
        final byte[] hmacSchluessel;

        Huelle(Art art, int seite, byte[] salz, byte[] schluessel, byte[] hmacSchluessel) {
            this.art = art;
            this.seite = seite;
            this.salz = salz;
            this.schluessel = schluessel;
            this.hmacSchluessel = hmacSchluessel;
        }

        /** „SQLCipher 3, Seite 1024" – für Fehlermeldungen und Tests. */
        @Override
        public String toString() {
            return "SQLCipher " + art.name + ", Seite " + seite;
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

    /**
     * Ob die Bytes eine verschlüsselte Datenbank sein <b>können</b>: Ihr Anfang ist weder SQLite noch
     * gzip noch Text, und die Länge geht in Seiten auf. Sicher sagt das erst das Passwort.
     */
    public static boolean vielleicht(byte[] raw) {
        if (raw == null || raw.length < 1024 || raw.length % 512 != 0) {
            return false;
        }
        if (KmySqlite.istSqlite(raw) || KmyGpg.istVerschluesselt(raw)) {
            return false;
        }
        if ((raw[0] & 0xFF) == 0x1f && (raw[1] & 0xFF) == 0x8b) {
            return false;
        }
        // Text (XML, mit oder ohne BOM) beginnt mit lauter druckbaren Zeichen; das Salz fast nie.
        int druckbar = 0;
        for (int i = 0; i < SALZ; i++) {
            int b = raw[i] & 0xFF;
            if (b == '\t' || b == '\n' || b == '\r' || (b >= 0x20 && b < 0x7f) || b == 0xEF || b == 0xBB
                    || b == 0xBF) {
                druckbar++;
            }
        }
        return druckbar < SALZ;
    }

    /** Entschlüsselt mit dem Passwort; probiert die bekannten Versionen und Seitengrößen. */
    public static Ergebnis entschluesseln(byte[] raw, String passwort) throws Fehler {
        if (raw == null || raw.length < 1024) {
            throw new Fehler("zu kurz für eine Datenbank", null);
        }
        byte[] salz = Arrays.copyOf(raw, SALZ);
        byte[] pw = (passwort == null ? "" : passwort).getBytes(StandardCharsets.UTF_8);
        try {
            for (Art art : ARTEN) {
                byte[] key = pbkdf2(art.kdfHash, pw, salz, art.runden, 32);
                byte[] hsalz = new byte[SALZ];
                for (int i = 0; i < SALZ; i++) {
                    hsalz[i] = (byte) (salz[i] ^ 0x3a);
                }
                byte[] hkey = pbkdf2(art.kdfHash, key, hsalz, 2, 32);
                for (int seite : SEITEN) {
                    if (raw.length % seite != 0 || seite <= art.reserve + SALZ) {
                        continue;
                    }
                    Huelle h = new Huelle(art, seite, salz, key, hkey);
                    if (echt(raw, 0, h)) {
                        return new Ergebnis(alleSeiten(raw, h), h);
                    }
                }
            }
        } catch (GeneralSecurityException e) {
            throw new Fehler("Entschlüsseln fehlgeschlagen: " + e.getMessage(), e);
        }
        throw new Fehler("Passwort passt nicht oder die Datei ist keine SQLCipher-Datenbank", null);
    }

    /** Verschlüsselt eine SQLite-Datei in der Hülle der gelesenen: dasselbe Salz, neue IVs. */
    public static byte[] verschluesseln(byte[] klar, Huelle h) throws Fehler {
        int ps = h.seite;
        if (klar.length % ps != 0 || !Arrays.equals(Arrays.copyOf(klar, KOPF.length), KOPF)) {
            throw new Fehler("keine SQLite-Datei mit Seiten zu " + ps + " Bytes", null);
        }
        int reserve = klar[20] & 0xFF;
        int seiteImKopf = ((klar[16] & 0xFF) << 8) | (klar[17] & 0xFF);
        if (seiteImKopf == 1) {
            seiteImKopf = 65536;
        }
        if (reserve != h.art.reserve || seiteImKopf != ps) {
            throw new Fehler("Seitengröße " + seiteImKopf + "/Reserve " + reserve + " passt nicht zu " + h, null);
        }
        SecureRandom zufall = new SecureRandom();
        byte[] out = new byte[klar.length];
        try {
            Cipher aes = Cipher.getInstance("AES/CBC/NoPadding");
            Mac mac = Mac.getInstance(h.art.hmac);
            mac.init(new SecretKeySpec(h.hmacSchluessel, h.art.hmac));
            for (int n = 0; n < klar.length / ps; n++) {
                int anfang = n * ps;
                int von = n == 0 ? SALZ : 0;
                int laenge = ps - h.art.reserve - von;
                byte[] iv = new byte[IV];
                zufall.nextBytes(iv);
                aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(h.schluessel, "AES"), new IvParameterSpec(iv));
                byte[] ct = aes.doFinal(klar, anfang + von, laenge);
                int pos = anfang;
                if (n == 0) {
                    System.arraycopy(h.salz, 0, out, pos, SALZ);
                    pos += SALZ;
                }
                System.arraycopy(ct, 0, out, pos, ct.length);
                pos += ct.length;
                System.arraycopy(iv, 0, out, pos, IV);
                pos += IV;
                mac.update(ct);
                mac.update(iv);
                mac.update(seitenNummer(n + 1));
                byte[] m = mac.doFinal();
                System.arraycopy(m, 0, out, pos, h.art.hmacLaenge);
                pos += h.art.hmacLaenge;
                byte[] rest = new byte[anfang + ps - pos];
                zufall.nextBytes(rest);
                System.arraycopy(rest, 0, out, pos, rest.length);
            }
        } catch (GeneralSecurityException e) {
            throw new Fehler("Verschlüsseln fehlgeschlagen: " + e.getMessage(), e);
        }
        return out;
    }

    /**
     * Entschlüsselt mit den Schlüsseln einer schon bekannten Hülle, ohne das Passwort erneut
     * abzuleiten – für die Probe nach dem Verschlüsseln.
     */
    public static byte[] entschluesseln(byte[] raw, Huelle h) throws Fehler {
        try {
            if (raw.length % h.seite != 0 || !echt(raw, 0, h)) {
                throw new Fehler("passt nicht zu " + h, null);
            }
            return alleSeiten(raw, h);
        } catch (GeneralSecurityException e) {
            throw new Fehler("Entschlüsseln fehlgeschlagen: " + e.getMessage(), e);
        }
    }

    /**
     * Die SQLite-Datei mit leerem reservierten Bereich je Seite – so, wie sie aus dem Entschlüsseln
     * kommt. SQLite liest diesen Bereich nie; für den Vergleich nach der Probe zählt er also nicht.
     */
    public static byte[] ohneReserve(byte[] klar, Huelle h) {
        byte[] out = klar.clone();
        for (int anfang = 0; anfang + h.seite <= out.length; anfang += h.seite) {
            Arrays.fill(out, anfang + h.seite - h.art.reserve, anfang + h.seite, (byte) 0);
        }
        return out;
    }

    /** Eine neue Hülle aus Passwort und Salz – nur für Tests, die selbst eine Datenbank verschlüsseln. */
    static Huelle huelle(String passwort, Art art, int seite, byte[] salz) throws GeneralSecurityException {
        byte[] pw = passwort.getBytes(StandardCharsets.UTF_8);
        byte[] key = pbkdf2(art.kdfHash, pw, salz, art.runden, 32);
        byte[] hsalz = new byte[SALZ];
        for (int i = 0; i < SALZ; i++) {
            hsalz[i] = (byte) (salz[i] ^ 0x3a);
        }
        return new Huelle(art, seite, salz.clone(), key, pbkdf2(art.kdfHash, key, hsalz, 2, 32));
    }

    /** Prüft den HMAC einer Seite – so zeigt sich, ob Passwort und Seitengröße stimmen. */
    private static boolean echt(byte[] raw, int n, Huelle h) throws GeneralSecurityException {
        int ps = h.seite;
        int anfang = n * ps;
        int von = n == 0 ? SALZ : 0;
        int ende = anfang + ps - h.art.reserve;
        Mac mac = Mac.getInstance(h.art.hmac);
        mac.init(new SecretKeySpec(h.hmacSchluessel, h.art.hmac));
        mac.update(raw, anfang + von, ende - anfang - von);
        mac.update(raw, ende, IV);
        mac.update(seitenNummer(n + 1));
        byte[] soll = mac.doFinal();
        byte[] ist = Arrays.copyOfRange(raw, ende + IV, ende + IV + h.art.hmacLaenge);
        return MessageDigest.isEqual(Arrays.copyOf(soll, h.art.hmacLaenge), ist);
    }

    private static byte[] alleSeiten(byte[] raw, Huelle h) throws GeneralSecurityException, Fehler {
        int ps = h.seite;
        byte[] out = new byte[raw.length];
        Cipher aes = Cipher.getInstance("AES/CBC/NoPadding");
        for (int n = 0; n < raw.length / ps; n++) {
            if (!echt(raw, n, h)) {
                throw new Fehler("Seite " + (n + 1) + " ist beschädigt oder verändert", null);
            }
            int anfang = n * ps;
            int von = n == 0 ? SALZ : 0;
            int ende = anfang + ps - h.art.reserve;
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(h.schluessel, "AES"),
                    new IvParameterSpec(raw, ende, IV));
            byte[] klar = aes.doFinal(raw, anfang + von, ende - anfang - von);
            if (n == 0) {
                System.arraycopy(KOPF, 0, out, 0, SALZ);
            }
            System.arraycopy(klar, 0, out, anfang + von, klar.length);
            // Der reservierte Bereich bleibt leer – SQLite liest ihn nicht.
        }
        return out;
    }

    private static byte[] seitenNummer(int n) {
        return new byte[]{(byte) n, (byte) (n >>> 8), (byte) (n >>> 16), (byte) (n >>> 24)};
    }

    /** PBKDF2 über ein beliebiges HMAC; die Bytes des Passworts gehen unverändert ein. */
    static byte[] pbkdf2(String hmac, byte[] passwort, byte[] salz, int runden, int laenge)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance(hmac);
        // Ein leerer Schlüssel ist für SecretKeySpec ungültig. HMAC füllt den Schlüssel ohnehin mit
        // Nullen auf, ein einzelnes Null-Byte ergibt also dasselbe wie gar keins.
        mac.init(new SecretKeySpec(passwort.length == 0 ? new byte[1] : passwort, hmac));
        int hLen = mac.getMacLength();
        byte[] out = new byte[laenge];
        int bloecke = (laenge + hLen - 1) / hLen;
        for (int i = 1; i <= bloecke; i++) {
            mac.update(salz);
            mac.update(new byte[]{(byte) (i >>> 24), (byte) (i >>> 16), (byte) (i >>> 8), (byte) i});
            byte[] u = mac.doFinal();
            byte[] t = u.clone();
            for (int r = 1; r < runden; r++) {
                u = mac.doFinal(u);
                for (int k = 0; k < t.length; k++) {
                    t[k] ^= u[k];
                }
            }
            System.arraycopy(t, 0, out, (i - 1) * hLen, Math.min(hLen, laenge - (i - 1) * hLen));
        }
        return out;
    }
}
