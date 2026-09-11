package de.spahr.ausgaben.i18n;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Die Layouts und {@link I18nViewFactory} müssen zusammenpassen.
 *
 * <p>Hintergrund: Texte, die im Layout als {@code @string/…} stehen, kommen aus dem kompilierten
 * String-Pool und gehen an der Sprachumschaltung der App vorbei – deshalb übersetzt die Factory sie
 * beim Aufblasen nach. Sie kennt dafür eine Liste von Attributnamen. Steht im Layout ein
 * übersetzbares Attribut, das nicht in dieser Liste steht, bleibt genau dieser eine Text in der
 * Sprache des <b>Geräts</b> stehen, während alles ringsum der Sprache der <b>App</b> folgt.</p>
 *
 * <p>Genau so fiel {@code helperText} auf: In einer englischen Oberfläche stand unter dem Betragsfeld
 * ein deutscher Hinweis. Der Test findet den nächsten Fall von selbst.</p>
 */
public class I18nLayoutAttrsTest {

    /** Attribute, die einen sichtbaren Text tragen und deshalb übersetzt gehören. */
    private static final Set<String> UEBERSETZBAR = new LinkedHashSet<>(java.util.Arrays.asList(
            "text", "hint", "title", "subtitle", "contentDescription", "helperText",
            "placeholderText", "prefixText", "suffixText"));

    private static final Pattern ATTRIBUT =
            Pattern.compile("(?:android|app):([A-Za-z]+)\\s*=\\s*\"@string/");

    @Test
    public void jedesUebersetzbareLayoutAttributKenntDieFactory() throws IOException {
        File ordner = new File("src/main/res/layout");
        assertTrue("Layout-Ordner nicht gefunden: " + ordner.getAbsolutePath(), ordner.isDirectory());

        Set<String> unbekannt = new LinkedHashSet<>();
        File[] dateien = ordner.listFiles((d, n) -> n.endsWith(".xml"));
        assertTrue("keine Layouts gefunden", dateien != null && dateien.length > 0);
        for (File f : dateien) {
            Matcher m = ATTRIBUT.matcher(new String(Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8));
            while (m.find()) {
                String attr = m.group(1);
                if (UEBERSETZBAR.contains(attr) && !I18nViewFactory.isTextAttr(attr)) {
                    unbekannt.add(attr + "  (" + f.getName() + ")");
                }
            }
        }
        if (!unbekannt.isEmpty()) {
            fail("Diese Layout-Attribute tragen Text, werden aber nicht übersetzt – sie blieben in "
                    + "der Sprache des Geräts stehen: " + unbekannt);
        }
    }

    /** Was die Factory zu übersetzen verspricht, muss sie auch annehmen. */
    @Test
    public void dieBekanntenAttributeBleibenBekannt() {
        for (String attr : new String[]{"text", "hint", "title", "subtitle", "contentDescription",
                "helperText"}) {
            assertTrue(attr + " fehlt in isTextAttr", I18nViewFactory.isTextAttr(attr));
        }
    }
}
