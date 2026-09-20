package de.spahr.ausgaben.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.spahr.ausgaben.net.smb.SmbDiagnostics;

/**
 * Der Diagnosebericht ist auf Deutsch nur in deutscher Oberfläche; sonst Englisch.
 *
 * <p>Bis 2.1 war er <b>immer</b> deutsch. Gefunden bei der F-Droid-Prüfliste am 20.09.2026: Die App
 * lief auf Englisch, die WebDAV-Diagnose schrieb „Adresse", „Erreichbarkeit" und „ohne https geht das
 * Passwort im Klartext über die Leitung". Für einen Prüfer sieht das nicht nach einem Werkzeug aus,
 * sondern nach einer halb übersetzten App.</p>
 *
 * <p>Spanisch gibt es hier bewusst nicht: Der Bericht ist ein Werkzeug für die Fehlersuche zwischen
 * Nutzer und Entwickler. Wer die App nicht auf Deutsch führt, bekommt Englisch — siehe die Begründung
 * an {@link Diagnostics#t}.</p>
 *
 * <p>Geprüft wird das <b>Ergebnis</b>, nicht der Quelltext: Ein Wächter, der nach {@code t(} sucht,
 * bewiese nur, dass jemand die Schreibweise getroffen hat. Hier läuft stattdessen jeder Text durch,
 * den die Diagnosen ohne Server erzeugen können, und wird auf deutsche Spuren abgeklopft.</p>
 */
public class DiagnosticsLanguageTest {

    /** Umlaute, Eszett und das deutsche Anführungszeichen – im Englischen kommt nichts davon vor. */
    private static final Pattern DEUTSCHE_ZEICHEN = Pattern.compile("[äöüÄÖÜß„]");

    /**
     * Wörter ohne Sonderzeichen, die sonst durchrutschten. Kurze wie „der" wären zu gierig — „order"
     * enthält es –, deshalb mit Wortgrenzen und nur eindeutige Fälle.
     */
    private static final Pattern DEUTSCHE_WOERTER = Pattern.compile(
            "\\b(Adresse|Benutzer|Ordner|Datei|Freigabe|Anmelden|Verbinden|Aushandeln|Umleitung"
                    + "|Erreichbarkeit|Schreiben|Umbenennen|vorhanden|gesetzt|gefunden|leer"
                    + "|nicht|kein|keine|gibt|braucht|wird|dort|bitte|Diagnose)\\b");

    private Locale vorher;

    @Before
    public void merken() {
        vorher = Locale.getDefault();
    }

    @After
    public void zurueck() {
        Locale.setDefault(vorher);
    }

    /**
     * Alles, was die beiden Diagnosen ohne erreichbaren Server hervorbringen: die Kopfzeilen, die
     * Deutung sämtlicher Statuscodes, die vier Netzfehler und je ein echter Lauf gegen einen toten
     * Port. Damit sind alle Zweige abgedeckt, die keinen Server brauchen.
     */
    private static List<String> alleTexte() {
        List<String> texte = new ArrayList<>(Arrays.asList(
                WebDavDiagnostics.title(),
                SmbDiagnostics.title(),
                Diagnostics.umbenennenNoetig(),
                WebDavDiagnostics.netzgrund(new java.net.UnknownHostException("x")),
                WebDavDiagnostics.netzgrund(new javax.net.ssl.SSLHandshakeException("x")),
                WebDavDiagnostics.netzgrund(new java.net.SocketTimeoutException()),
                WebDavDiagnostics.netzgrund(new java.net.ConnectException())));
        for (int code : new int[]{401, 403, 404, 405, 423, 500, 501}) {
            texte.add(WebDavDiagnostics.anmeldeDeutung(code, true));
            texte.add(WebDavDiagnostics.anmeldeDeutung(code, false));
            texte.add(WebDavDiagnostics.ordnerDeutung(code));
        }
        // Echte Läufe: leere Adresse, unbrauchbare Adresse, doppelter Nextcloud-Pfad, toter Port.
        texte.add(WebDavDiagnostics.report(WebDavDiagnostics.run("", "", "", true, "", "")));
        texte.add(WebDavDiagnostics.report(
                WebDavDiagnostics.run("cloud.example.de", "", "", true, "", "")));
        texte.add(WebDavDiagnostics.report(WebDavDiagnostics.run(
                "https://cloud.example.de/remote.php/dav/files/x", "x", "g", true, "", "")));
        texte.add(WebDavDiagnostics.report(WebDavDiagnostics.run(
                "http://127.0.0.1:1", "x", "g", true, "Finanzen/test.kmy")));
        texte.add(SmbDiagnostics.report(SmbDiagnostics.run("", "", "", "", "")));
        texte.add(SmbDiagnostics.report(
                SmbDiagnostics.run("smb://127.0.0.1:1/freigabe", "x", "g", "", "")));
        return texte;
    }

    private static List<String> deutscheSpuren(List<String> texte) {
        List<String> treffer = new ArrayList<>();
        for (String text : texte) {
            Matcher zeichen = DEUTSCHE_ZEICHEN.matcher(text);
            if (zeichen.find()) {
                treffer.add("Zeichen „" + zeichen.group() + "\" in: " + kurz(text));
                continue;
            }
            Matcher wort = DEUTSCHE_WOERTER.matcher(text);
            if (wort.find()) {
                treffer.add("Wort \"" + wort.group() + "\" in: " + kurz(text));
            }
        }
        return treffer;
    }

    private static String kurz(String s) {
        String eine = s.replace('\n', '|');
        return eine.length() > 120 ? eine.substring(0, 120) + "…" : eine;
    }

    /** Läuft die App auf Englisch, darf kein deutsches Wort im Bericht stehen. */
    @Test
    public void aufEnglischStehtKeinDeutschImBericht() {
        Locale.setDefault(Locale.US);
        assertEquals("deutsche Reste im englischen Bericht", "[]",
                deutscheSpuren(alleTexte()).toString());
    }

    /**
     * Gegenprobe, und zwar die wichtige: Der Prüfausdruck muss das Deutsche überhaupt finden können.
     * Ohne sie bewiese der Test oben nur, dass zwei Muster auf nichts passen — und er bliebe grün,
     * wenn jemand die Texte versehentlich sämtlich auf Englisch stellte.
     */
    @Test
    public void aufDeutschFindetDerAusdruckAuchWelches() {
        Locale.setDefault(Locale.GERMANY);
        List<String> treffer = deutscheSpuren(alleTexte());
        assertTrue("der Ausdruck findet im deutschen Bericht nichts – dann prüft er oben nichts",
                treffer.size() > 10);
    }

    /** Und die Sprachwahl selbst: Sie hängt an der Sprache, nicht am Land. */
    @Test
    public void dieSprachwahlHaengtAnDerSpracheNichtAmLand() {
        Locale.setDefault(Locale.forLanguageTag("de-AT"));
        assertTrue("Österreich ist auch deutsch", Diagnostics.deutsch());
        Locale.setDefault(Locale.forLanguageTag("de-CH"));
        assertTrue("die Schweiz auch", Diagnostics.deutsch());
        Locale.setDefault(Locale.forLanguageTag("es-ES"));
        assertTrue("Spanisch bekommt Englisch", !Diagnostics.deutsch());
        Locale.setDefault(Locale.UK);
        assertTrue("Englisch sowieso", !Diagnostics.deutsch());
    }
}
