package de.spahr.ausgaben.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Das Datum muss der eingestellten Sprache folgen – und Deutsch dabei unverändert bleiben.
 *
 * <p>Bis 2.0 stand in zwölf Masken ein festes {@code dd.MM.yyyy}. Wer die App auf Englisch stellte,
 * bekam die Texte übersetzt und das Datum daneben weiter deutsch.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DateFormatsTest {

    /**
     * Der Zustand ist statisch und überlebt die Testklasse – beim ersten Lauf hat das prompt
     * {@code SecurityTxRotationTest} umgeworfen, der ein deutsches Datum erwartet und nach diesen
     * Tests auf Englisch stand. Wer hier {@link DateFormats#apply} ruft, räumt hinterher auf.
     */
    @After
    public void zurueckAufDeutsch() {
        DateFormats.apply("de", null);
    }

    /** 3. Februar 2026 – Tag und Monat verschieden, sonst verriete die Reihenfolge nichts. */
    private static long derDritteFebruar() {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.FEBRUARY, 3, 12, 0, 0);
        return c.getTimeInMillis();
    }

    /**
     * Die Tabelle greift, wenn das Gerät nichts beisteuern kann – deshalb hier überall {@code null} als
     * Gerätesprache. Ohne diesen zweiten Parameter hinge der Test an der Sprache der Testumgebung.
     */
    @Test
    public void deutschBleibtWieGehabt() {
        DateFormats.apply("de", null);
        assertEquals("dd.MM.yyyy", DateFormats.pattern());
        assertEquals("03.02.2026", DateFormats.date(derDritteFebruar()));
    }

    @Test
    public void englischStelltDenMonatNachVorn() {
        DateFormats.apply("en", null);
        assertEquals("MM/dd/yyyy", DateFormats.pattern());
        assertEquals("02/03/2026", DateFormats.date(derDritteFebruar()));
    }

    @Test
    public void spanischSchreibtTagZuerstMitSchraegstrich() {
        DateFormats.apply("es", null);
        assertEquals("dd/MM/yyyy", DateFormats.pattern());
        assertEquals("03/02/2026", DateFormats.date(derDritteFebruar()));
    }

    /**
     * Der Grund für den ganzen Geräteweg: Englisch ist nicht gleich Englisch. Nur die USA schreiben den
     * Monat zuerst, alle übrigen englischsprachigen Länder den Tag. Der Fehler wäre still geblieben —
     * {@code 03/02} und {@code 02/03} sind beide gültige Daten, nur verschiedene.
     */
    @Test
    public void englischErbtDasLandDesGeraets() {
        DateFormats.apply("en", Locale.US);
        assertEquals("US: Monat zuerst", "02/03/2026", DateFormats.date(derDritteFebruar()));

        DateFormats.apply("en", Locale.UK);
        assertEquals("GB: Tag zuerst", "03/02/2026", DateFormats.date(derDritteFebruar()));
    }

    /** Im ganzen deutschen Sprachraum steht der Punkt – über den Geräteweg ändert sich nichts. */
    @Test
    public void deutschBleibtAuchUeberDasGeraetGleich() {
        for (String land : new String[]{"DE", "AT", "CH", "LI"}) {
            DateFormats.apply("de", new Locale("de", land));
            assertEquals("de-" + land, "03.02.2026", DateFormats.date(derDritteFebruar()));
        }
    }

    /**
     * Passt die gewählte Sprache nicht zum Gerät, gibt es kein Land, aus dem sich etwas ableiten ließe:
     * Ein Deutscher, der die App auf Englisch stellt, darf kein {@code dd.MM.yyyy} bekommen.
     */
    @Test
    public void fremdesGeraetelandWirdNichtVermischt() {
        DateFormats.apply("en", Locale.GERMANY);
        assertEquals("MM/dd/yyyy", DateFormats.pattern());
    }

    /** Die Uhrzeit bleibt 24-stündig, sonst stünde im Englischen plötzlich „AM". */
    @Test
    public void uhrzeitBleibtVierundzwanzigStuendig() {
        DateFormats.apply("en", null);
        assertTrue(DateFormats.dateTime(derDritteFebruar()).endsWith(" 12:00"));
    }

    /**
     * Die Zusicherung für den Datumsfilter: Was angezeigt wird, muss auch wieder lesbar sein. Liefen
     * Anzeige und Eingabe auseinander, wäre der gewählte Zeitraum still falsch.
     */
    @Test
    public void hinUndZurueckErgibtDenselbenTag() throws Exception {
        for (String lang : new String[]{"de", "en", "es", "fr"}) {
            DateFormats.apply(lang, null);
            long t = derDritteFebruar();
            Calendar zurueck = Calendar.getInstance();
            zurueck.setTime(DateFormats.parse(DateFormats.date(t)));
            assertEquals(lang + ": Jahr", 2026, zurueck.get(Calendar.YEAR));
            assertEquals(lang + ": Monat", Calendar.FEBRUARY, zurueck.get(Calendar.MONTH));
            assertEquals(lang + ": Tag", 3, zurueck.get(Calendar.DAY_OF_MONTH));
        }
    }

    /** Nachgeladene Sprachen gibt es auch – für die fragen wir Android nach dem ortsüblichen Muster. */
    @Test
    public void unbekannteSpracheBekommtEinMuster() {
        DateFormats.apply("fr", null);
        assertFalse("Rückfall lieferte kein Muster", DateFormats.pattern().isEmpty());
    }

    /**
     * Wächter gegen den Rückfall ins Alte.
     *
     * <p>Die umgestellten Stellen sind das eine, aber nichts hindert die nächste Maske daran, wieder
     * ein eigenes {@code new SimpleDateFormat("dd.MM.yyyy", …)} anzulegen – und aufgefallen wäre das
     * erst einem spanischen Nutzer. Beim ersten Lauf hat diese Prüfung prompt zwei Stellen gefunden,
     * die vorher niemand gesehen hatte: die Achsenbeschriftungen in {@code AnalysisActivity} und
     * {@code ScheduledChartActivity} mit {@code dd.MM.yy}. Wer nur nach {@code dd.MM.yyyy} sucht,
     * übersieht sie.</p>
     *
     * <p>Gesucht wird nur im {@code ui}-Paket und nur nach Mustern mit Tagesangabe.
     * Monatsüberschriften ({@code MMMM yyyy}) fallen schon deshalb heraus. Ausgenommen sind
     * Zeitstempel für Dateinamen: {@code yyyyMMdd-HHmmss} enthält zwar „dd", soll aber sortierbar
     * sein und nicht hübsch – daran erkennt man sie, dass zwischen den Feldern kein Trennzeichen
     * steht.</p>
     *
     * <p>Nicht durchsucht werden {@code export} und {@code util}: Dort ist das feste Muster Absicht –
     * das CSV-Format ist im Handbuch als deutsch festgeschrieben, und in {@code TextValues} steht
     * {@code dd.MM.yyyy} in einer Kandidatenliste zum Deuten eingelesener Texte. Die Begründung steht
     * ausführlich im Javadoc von {@link DateFormats}.</p>
     */
    @Test
    public void keineNeueMaskeFormatiertDasDatumSelbst() throws IOException {
        Path ui = Paths.get("src/main/java/de/spahr/ausgaben/ui");
        assertTrue("Pfad " + ui.toAbsolutePath() + " nicht gefunden", Files.isDirectory(ui));

        List<String> treffer = new ArrayList<>();
        try (Stream<Path> dateien = Files.walk(ui)) {
            for (Path f : (Iterable<Path>) dateien.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String quelle = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("SimpleDateFormat\\(\\s*\"([^\"]*)\"")
                        .matcher(quelle);
                while (m.find()) {
                    String muster = m.group(1);
                    if (muster.contains("dd") && !muster.contains("yyyyMMdd")) {
                        treffer.add(f.getFileName() + ": \"" + muster + "\"");
                    }
                }
            }
        }
        assertEquals("Datum bitte über DateFormats formatieren, nicht je Maske neu",
                "[]", treffer.toString());
    }
}
