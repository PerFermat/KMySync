package de.spahr.ausgaben.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Zwei Wächter um den <b>leeren</b> Empfänger — seit er erlaubt ist.
 *
 * <p>Bis 2.0 sperrte {@code BookingEditActivity.readValidFields} eine Buchung ohne Empfänger ab. Alles
 * darunter vertrug sie längst: Das Feld ist ein leerer String, kein {@code null}, und der Import
 * schreibt leere Empfänger seit jeher durch. Mit dem Fall der Sperre werden aus zwei bis dahin
 * theoretischen Fragen zwei echte — und beide sind am Quelltext prüfbar, am fertigen Bild dagegen
 * kaum.</p>
 *
 * <p>Gebaut wie {@code LocaleGuardTest} und {@code DateFormatsTest}: Kommentare fallen vorher heraus,
 * sonst schlägt schon dieses Javadoc an.</p>
 */
public class PayeeGuardTest {

    private static final Path QUELLEN = Paths.get("src/main/java/de/spahr/ausgaben");

    /** Quelltext ohne Kommentare, damit Erklärungen nicht als Fund gezählt werden. */
    private static String ohneKommentare(Path f) throws IOException {
        String quelle = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        return quelle.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    /** Zählt die Zeilen bis zum Fund, damit der Bericht direkt anspringbar ist. */
    private static void sammle(List<String> treffer, String datei, String code, String muster) {
        Matcher m = Pattern.compile(muster).matcher(code);
        while (m.find()) {
            int zeile = 1 + (int) code.substring(0, m.start()).chars().filter(c -> c == '\n').count();
            treffer.add(datei + ":~" + zeile);
        }
    }

    /**
     * Ein leerer Empfänger darf nicht in der Stammdatenliste landen.
     *
     * <p>{@code PayeeDao.getAllNames()} sortiert {@code ORDER BY name}. Eine namenlose Zeile stünde
     * damit <b>ganz oben</b> in jeder Empfängerauswahl — eine leere erste Zeile, die niemand als
     * Eintrag erkennt und die beim nächsten Export als eigener Empfänger mitginge.</p>
     *
     * <p>Geprüft wird die Anzahl der Türen, nicht die Prüfung selbst: Vor dem Umbau standen <b>elf</b>
     * {@code payeeDao.insertIfAbsent(…)} in {@code Repository}, und genau zwei davon prüften auf leer
     * — ausgerechnet die beiden Wege (Umbuchung, Import), auf denen ein leerer Empfänger damals schon
     * vorkam. Die übrigen neun waren nie falsch, sie verließen sich nur auf die Maske. Eine einzige
     * Tür ist die Zusicherung, die man beim nächsten neuen Speicherweg nicht vergessen kann.</p>
     */
    @Test
    public void nurEineTuerZurEmpfaengerliste() throws IOException {
        Path repo = QUELLEN.resolve("db/Repository.java");
        assertTrue("Datei " + repo.toAbsolutePath() + " nicht gefunden", Files.isRegularFile(repo));

        List<String> treffer = new ArrayList<>();
        sammle(treffer, "Repository.java", ohneKommentare(repo), "payeeDao\\.insertIfAbsent\\(");

        assertEquals("Empfänger bitte über rememberPayee anlegen – das ist die Stelle, die Leeres abweist",
                1, treffer.size());
    }

    /**
     * Wie ein fehlender Empfänger angezeigt wird, entscheidet <b>eine</b> Stelle.
     *
     * <p>Es gab sechs: {@code BookingAdapter} und {@code WidgetLarge} setzten einen festen
     * Gedankenstrich, {@code BudgetActivity} und {@code CategoryChartActivity} fielen auf die
     * Kategorie zurück. Vier Listen, zwei verschiedene Antworten — und keine davon kannte die
     * Splitbuchung oder KMyMoneys „*** NICHT ZUGEWIESEN ***".</p>
     *
     * <p>Die Rangfolge steckt jetzt in {@code BookingLabel}. Der Wächter sucht nach der Bauart, die
     * sie umgeht: das Ternär direkt an {@code payee.isEmpty()}. Es ist schnell hingeschrieben und
     * fällt in keinem Bild auf, solange man keine Buchung ohne Empfänger vor sich hat.</p>
     *
     * <p>{@code if (payee.isEmpty())} bleibt erlaubt: In {@code VoiceEntryController} und
     * {@code BookingEditActivity} ist das eine Ablauf-Entscheidung, keine Anzeige.</p>
     *
     * <p>Ausgenommen wird <b>ausdrücklich</b> {@code st.payee} — eine Liste, die man liest, statt
     * einer Auslassung, die man übersieht. Die Gegenprobe hat den Fall gefunden und mich berichtigt:
     * {@code st} ist im ganzen Paket {@link ScheduledTransaction}, nicht {@code Booking}. Eine
     * Dauerbuchung entsteht in dieser App gar nicht, sie wird nur eingelesen ({@code kmy_id}), und
     * ihr {@code name} kommt aus KMyMoney, wo er Pflicht ist. Der Rückfall auf den Namen ist dort
     * also richtig und trifft nie ins Leere. Die Datei-Ebene half hier nicht:
     * {@code CategoryChartActivity} enthält beide Fälle nebeneinander.</p>
     */
    @Test
    public void keineListeEntscheidetSelbstUeberDenLeerenEmpfaenger() throws IOException {
        List<String> treffer = new ArrayList<>();
        try (Stream<Path> dateien = Files.walk(QUELLEN)) {
            for (Path f : (Iterable<Path>) dateien.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String pfad = f.toString().replace('\\', '/');
                boolean anzeige = pfad.contains("/ui/") || pfad.contains("/widget/");
                if (!anzeige || pfad.endsWith("/BookingLabel.java")) {
                    continue;
                }
                sammle(treffer, f.getFileName().toString(), ohneKommentare(f),
                        "(?<!st\\.)payee\\.isEmpty\\(\\)\\s*\\?");
            }
        }
        assertEquals("Ersatz für den fehlenden Empfänger bitte über BookingLabel.title(…)",
                "[]", treffer.toString());
    }
}
