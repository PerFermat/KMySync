package de.spahr.ausgaben.i18n;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kein Text darf eine Zahl unmittelbar vor einem Wort führen — sonst steht bei {@code 1} dort
 * „1 Konten gelöscht" oder „1 were not reached".
 *
 * <p><b>Warum nicht einfach {@code <plurals>}?</b> Die App läßt Sprachen hochladen. Deren Texte liegen
 * in der Datenbank und werden von {@code LocaleContextWrapper.TranslatedResources} untergeschoben, die
 * {@code getText}/{@code getString} überschreibt — aber <em>nicht</em> {@code getQuantityString}. Der
 * Katalog entsteht über Reflexion auf {@code R.string} ({@link LocaleManager}); {@code R.plurals} käme
 * dort nie an, und die Export-Vorlage für Übersetzer speist sich aus derselben Tabelle. Ein
 * {@code <plurals>} löste seinen Text also aus dem Übersetzungssystem heraus. Deshalb die Form
 * „Substantiv voran, Zahl hinten".</p>
 *
 * <p>Der Wächter liest die drei Sprachdateien, nicht die kompilierten Ressourcen: Er soll auch die
 * Übersetzungen erwischen, und dort steht in {@code values-es} gelegentlich etwas, das im englischen
 * Original unauffällig ist — genau daran hing die Hälfte der Funde.</p>
 */
public class ZahlwortGuardTest {

    /**
     * Was hier stehen darf, und warum. Wer einen Schlüssel hinzufügt, muß den Grund danebenschreiben —
     * die Liste ist die Begründungspflicht, nicht die Ausnahmeregel.
     */
    private static final List<String> ERLAUBT = Arrays.asList(
            "alias_band_too_few",            // Zahl ist PayeeAmounts.MIN_COUNT = 5
            "backup_password_too_short",     // Zahl ist BackupCrypto.MIN_PASSWORD_LENGTH = 8
            "language_export_complete",      // Zahl ist die Gesamtzahl aller Texte, Hunderte
            "statement_rules_below_n",       // bei 1 greift statement_rules_below_one
            "statement_rules_above_n",       // bei 1 greift statement_rules_above_one
            // Keine Anzahlen: Der Ausdruck sieht nur „Zahl, dann Wort" und kann eine Jahreszahl oder
            // eine Portnummer nicht von einer Stückzahl unterscheiden.
            "budget_compute_confirm_message",// die Zahl ist ein Jahr
            "smb_port_corrected",            // die Zahl ist eine Portnummer
            "receipt_view_page",             // „Seite 1 von 3" – eine Nummer, keine Menge
            "language_export_missing",       // „%1$d von %2$d" – „von/of/de" beugt sich nicht
            // Partizipien beugen sich im Englischen und Deutschen nicht; nur die spanischen
            // Entsprechungen mußten umgestellt werden, und die stehen nicht mehr so da.
            "kmy_result_updated",
            "kmy_result_deleted",
            "kmy_skipped");

    /** Eine Zahl-Ersetzung, direkt gefolgt von einem Wort aus Buchstaben. */
    private static final Pattern ZAHL_VOR_WORT =
            Pattern.compile("%(?:\\d+\\$)?d\\s+\\p{L}");

    private static final Pattern STRING =
            Pattern.compile("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", Pattern.DOTALL);

    @Test
    public void keinTextStelltDieZahlVorDasWort() throws IOException {
        List<String> treffer = new ArrayList<>();
        for (String ordner : new String[]{"values", "values-de", "values-es"}) {
            Path p = Paths.get("src/main/res/" + ordner + "/strings.xml");
            assertTrue("Nicht gefunden: " + p.toAbsolutePath(), Files.isRegularFile(p));
            Matcher m = STRING.matcher(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
            while (m.find()) {
                if (ERLAUBT.contains(m.group(1))) {
                    continue;
                }
                if (ZAHL_VOR_WORT.matcher(m.group(2)).find()) {
                    treffer.add(ordner + "/" + m.group(1) + ": " + m.group(2).trim());
                }
            }
        }
        assertEquals("Zahl vor Wort — bei 1 liest sich das falsch. Substantiv voran, Zahl hinten "
                + "(\"Konten gelöscht: %1$d\"). Geht das nicht, Grund in ERLAUBT eintragen.",
                "[]", treffer.toString());
    }

    /**
     * Gegenprobe: Der Ausdruck muß die alte Schreibweise überhaupt finden. Ohne sie bewiese der Test
     * oben nur, daß irgendein Muster auf nichts paßt.
     */
    @Test
    public void derAusdruckFindetDieAlteSchreibweise() {
        assertTrue(ZAHL_VOR_WORT.matcher("%1$d Konten gelöscht").find());
        assertTrue(ZAHL_VOR_WORT.matcher("%d scheduled transactions due today").find());
        assertTrue(ZAHL_VOR_WORT.matcher("• %1$d no están en el servidor").find());
    }

    /** Und die neue nicht — sonst wäre er nicht zu erfüllen. */
    @Test
    public void dieNeueSchreibweiseGehtDurch() {
        assertTrue(!ZAHL_VOR_WORT.matcher("Konten gelöscht: %1$d").find());
        assertTrue(!ZAHL_VOR_WORT.matcher("Auf dem Gerät: %1$d. Erst vom Server zu laden: %2$d.").find());
        // Zahl vor Satzzeichen oder am Ende ist in Ordnung – da folgt kein Wort, das sich beugen müßte.
        assertTrue(!ZAHL_VOR_WORT.matcher("Seiten: %1$d").find());
    }
}
