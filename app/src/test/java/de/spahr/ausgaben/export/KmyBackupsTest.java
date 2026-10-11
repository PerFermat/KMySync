package de.spahr.ausgaben.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Welche Sicherungen der Export wegräumen darf. Das Löschen selbst fasst den Server an und bleibt
 * außen vor – hier steht nur die Auswahl.
 */
public class KmyBackupsTest {

    /** {@code n} Sicherungen zu {@code file}, aufsteigend datiert. */
    private static List<String> sicherungen(String file, int n) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add(String.format("%s.bak-202609%02d-120000", file, i));
        }
        return out;
    }

    @Test
    public void unterDemDeckelBleibtAllesLiegen() {
        List<String> namen = sicherungen("michael.kmy", 5);
        assertTrue(KmyBackups.obsolete(namen, "michael.kmy", 20).isEmpty());
    }

    @Test
    public void ueberDemDeckelFallenDieAeltestenWeg() {
        List<String> namen = sicherungen("michael.kmy", 23);
        List<String> weg = KmyBackups.obsolete(namen, "michael.kmy", 20);
        assertEquals(3, weg.size());
        // Die drei ältesten, nicht irgendwelche drei.
        assertEquals(Arrays.asList(
                "michael.kmy.bak-20260903-120000",
                "michael.kmy.bak-20260902-120000",
                "michael.kmy.bak-20260901-120000"), weg);
    }

    @Test
    public void dieJuengstenBleibenGarantiert() {
        List<String> namen = sicherungen("michael.kmy", 30);
        List<String> weg = KmyBackups.obsolete(namen, "michael.kmy", 20);
        assertTrue(weg.contains("michael.kmy.bak-20260910-120000"));
        assertTrue("die jüngste darf nie weg", !weg.contains("michael.kmy.bak-20260930-120000"));
        assertEquals(10, weg.size());
    }

    @Test
    public void andereProfileHabenIhrEigenesKontingent() {
        // Im selben Ordner liegen Sicherungen mehrerer .kmy-Dateien; jede zählt für sich.
        List<String> namen = new ArrayList<>(sicherungen("michael.kmy", 22));
        namen.addAll(sicherungen("Rolf.kmy", 25));
        List<String> weg = KmyBackups.obsolete(namen, "michael.kmy", 20);
        assertEquals(2, weg.size());
        for (String n : weg) {
            assertTrue(n.startsWith("michael.kmy.bak-"));
        }
    }

    @Test
    public void fremdeDateienImOrdnerBleibenUnberuehrt() {
        // Ein Name, der nur zufällig so anfängt, und die .kmy selbst.
        List<String> namen = new ArrayList<>(sicherungen("michael.kmy", 22));
        namen.add("michael.kmy");
        namen.add("Notizen.txt");
        namen.add("michael.kmy.20260101.tmp");
        List<String> weg = KmyBackups.obsolete(namen, "michael.kmy", 20);
        assertEquals(2, weg.size());
        for (String n : weg) {
            assertTrue(n.startsWith("michael.kmy.bak-"));
        }
    }

    @Test
    public void leereEingabeUndNullSindHarmlos() {
        assertTrue(KmyBackups.obsolete(null, "michael.kmy", 20).isEmpty());
        assertTrue(KmyBackups.obsolete(Collections.emptyList(), "michael.kmy", 20).isEmpty());
        assertTrue(KmyBackups.obsolete(sicherungen("michael.kmy", 3), null, 20).isEmpty());
    }

    /** 0 heißt: dauerhaft aufheben. */
    @Test
    public void deckelNullLoeschtNichts() {
        assertTrue(KmyBackups.obsolete(sicherungen("michael.kmy", 30), "michael.kmy", 0).isEmpty());
        assertEquals(27, KmyBackups.obsolete(sicherungen("michael.kmy", 30), "michael.kmy", 3).size());
        assertEquals(0, KmyBackups.begrenzt(-4));
        assertEquals(KmyBackups.MAX_KEEP, KmyBackups.begrenzt(5000));
    }

    // ---- Monatssicherungen ----

    private static final String F = "michael.kmy";

    private static String name(String stempel) {
        return F + ".bak-" + stempel;
    }

    @Test
    public void monatAusDemNamen() {
        assertEquals("202609", KmyBackups.monat(name("20260914-111705"), F));
        assertEquals("", KmyBackups.monat("anders.kmy.bak-20260914-111705", F));
        assertEquals("", KmyBackups.monat(name("kaputt"), F));
        assertTrue(KmyBackups.hatMonat(Arrays.asList(name("20260901-080000")), F, "202609"));
        assertFalse(KmyBackups.hatMonat(Arrays.asList(name("20260801-080000")), F, "202609"));
        // Die Sicherung eines anderen Profils zählt nicht.
        assertFalse(KmyBackups.hatMonat(
                Arrays.asList("anders.kmy.bak-20260901-080000"), F, "202609"));
    }

    @Test
    public void verschobenWirdDieAeltesteIhresMonatsWennDerMonatFehlt() {
        List<String> alle = Arrays.asList(
                name("20260703-100000"), name("20260720-100000"),
                name("20260805-100000"), name("20260806-100000"),
                name("20260901-100000"), name("20260902-100000"));
        // Zwei bleiben, vier sollen weg.
        List<String> weg = KmyBackups.obsolete(alle, F, 2);
        assertEquals(4, weg.size());

        // Juli fehlt im Monatsordner, August ist schon da.
        List<String> monatlich = Arrays.asList(name("20260801-090000"));
        assertEquals(Arrays.asList(name("20260703-100000")),
                KmyBackups.zuVerschieben(weg, alle, monatlich, F));

        // Ist die älteste eines Monats nicht unter den zu löschenden, wird aus dem Monat nichts
        // verschoben – auch nicht die zweitälteste.
        List<String> nurZweite = Arrays.asList(name("20260720-100000"));
        assertTrue(KmyBackups.zuVerschieben(nurZweite, alle, new ArrayList<>(), F).isEmpty());
    }

    @Test
    public void aufraeumenVerschiebtInDenMonatsordnerUndLoeschtDenRest() {
        Ablage s = new Ablage();
        for (String n : Arrays.asList(name("20260703-100000"), name("20260720-100000"),
                name("20260805-100000"), name("20260901-100000"), name("20260902-100000"))) {
            s.put("Backup", n);
        }
        s.put("Backup", "anders.kmy.bak-20260101-100000");
        s.put("Backup/Monat", name("20260601-100000"));

        int geloescht = KmyBackups.prune(s, "Backup", "Backup/Monat", F, 2);

        assertEquals(1, geloescht);   // nur die zweite vom Juli
        assertEquals(Arrays.asList("anders.kmy.bak-20260101-100000",
                name("20260901-100000"), name("20260902-100000")), s.namen("Backup"));
        // Juli und August sind dazugekommen, der Juni liegt unverändert da.
        assertEquals(Arrays.asList(name("20260601-100000"), name("20260703-100000"),
                name("20260805-100000")), s.namen("Backup/Monat"));
    }

    @Test
    public void verschiebenScheitert_dieSicherungBleibtLiegen() {
        Ablage s = new Ablage();
        s.put("Backup", name("20260703-100000"));
        s.put("Backup", name("20260901-100000"));
        s.verschiebenScheitert = true;

        assertEquals(0, KmyBackups.prune(s, "Backup", "Backup/Monat", F, 1));
        assertEquals(2, s.namen("Backup").size());
    }

    @Test
    public void ohneBlickInDenMonatsordnerWirdNichtsGeloescht() {
        Ablage s = new Ablage();
        s.put("Backup", name("20260703-100000"));
        s.put("Backup", name("20260901-100000"));
        s.auflistenScheitertIn = "Backup/Monat";

        assertEquals(0, KmyBackups.prune(s, "Backup", "Backup/Monat", F, 1));
        assertEquals(2, s.namen("Backup").size());
    }

    @Test
    public void ersterExportDesMonatsLegtDieMonatssicherungAn_derZweiteNicht() {
        Ablage s = new Ablage();
        byte[] inhalt = {1, 2, 3};

        assertTrue(KmyBackups.sichereMonat(s, "Backup", "Backup/Monat", F, name("20261003-080000"), inhalt));
        assertFalse(KmyBackups.sichereMonat(s, "Backup", "Backup/Monat", F, name("20261004-080000"), inhalt));
        assertTrue(KmyBackups.sichereMonat(s, "Backup", "Backup/Monat", F, name("20261101-080000"), inhalt));

        assertEquals(Arrays.asList(name("20261003-080000"), name("20261101-080000")),
                s.namen("Backup/Monat"));
        // Kopiert wurde auf dem Server; durch die Leitung ging nichts.
        assertEquals(2, s.kopiert);
        assertEquals(0, s.hochgeladen);
    }

    /** Kann der Server nicht kopieren, wird hochgeladen – die Monatssicherung entsteht trotzdem. */
    @Test
    public void ohneKopierenAufDemServerWirdHochgeladen() {
        Ablage s = new Ablage();
        s.kopierenScheitert = true;

        assertTrue(KmyBackups.sichereMonat(s, "Backup", "Backup/Monat", F,
                name("20261003-080000"), new byte[]{1}));

        assertEquals(Arrays.asList(name("20261003-080000")), s.namen("Backup/Monat"));
        assertEquals(1, s.hochgeladen);
    }

    /** Ordner mit Dateinamen im Speicher; kann beim Verschieben oder Auflisten scheitern. */
    private static final class Ablage implements de.spahr.ausgaben.net.RemoteStorage {
        final java.util.Map<String, java.util.TreeSet<String>> ordner = new java.util.HashMap<>();
        boolean verschiebenScheitert;
        boolean kopierenScheitert;
        int kopiert;
        int hochgeladen;
        String auflistenScheitertIn;

        void put(String folder, String name) {
            ordner.computeIfAbsent(folder, k -> new java.util.TreeSet<>()).add(name);
        }

        List<String> namen(String folder) {
            return new ArrayList<>(ordner.getOrDefault(folder, new java.util.TreeSet<>()));
        }

        @Override
        public List<String> listAllFiles(String folder) throws java.io.IOException {
            if (folder.equals(auflistenScheitertIn)) {
                throw new java.io.IOException("offline");
            }
            return namen(folder);
        }

        @Override
        public List<String> listFiles(String folder, String ext) {
            return namen(folder);
        }

        @Override
        public void ensureFolder(String folder) {
            ordner.computeIfAbsent(folder, k -> new java.util.TreeSet<>());
        }

        @Override
        public void delete(String folder, String fileName) {
            ordner.get(folder).remove(fileName);
        }

        @Override
        public void move(String fromFolder, String fromName, String toFolder, String toName)
                throws java.io.IOException {
            if (verschiebenScheitert) {
                throw new java.io.IOException("nicht erlaubt");
            }
            ordner.get(fromFolder).remove(fromName);
            put(toFolder, toName);
        }

        @Override
        public void copy(String fromFolder, String fromName, String toFolder, String toName)
                throws java.io.IOException {
            if (kopierenScheitert) {
                throw new java.io.IOException("nicht unterstützt");
            }
            kopiert++;
            put(toFolder, toName);
        }

        @Override
        public void uploadBytes(String folder, String fileName, byte[] content) {
            hochgeladen++;
            put(folder, fileName);
        }

        @Override
        public void uploadText(String folder, String fileName, String content) {
            put(folder, fileName);
        }

        @Override
        public String downloadText(String folder, String fileName) {
            return "";
        }

        @Override
        public byte[] downloadBytes(String folder, String fileName) {
            return new byte[0];
        }

        @Override
        public void testConnection() {
        }
    }
}
