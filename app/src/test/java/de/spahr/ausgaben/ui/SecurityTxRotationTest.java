package de.spahr.ausgaben.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.app.DatePickerDialog;
import android.content.DialogInterface;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.TextInputEditText;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;

import de.spahr.ausgaben.R;

/**
 * Was die Erfassungsmaske über eine Bildschirmdrehung retten muss.
 *
 * <p>Der fachliche Zustand lag ausschließlich in Instanzfeldern, und die Activity trägt im Manifest
 * kein {@code configChanges}. Nach dem Drehen lief {@code onCreate} neu und las nur die Intent-Extras
 * — das gewählte Datum stand danach zwar sichtbar im Feld (die View-Hierarchie stellt ihren Text von
 * selbst wieder her), der Merker {@code dateKnown} war aber wieder {@code false}. Speichern meldete
 * „Datum fehlt", und der Nutzer hatte keine Möglichkeit, den Widerspruch aufzulösen: das Datum
 * <em>stand</em> ja da.</p>
 *
 * <p>Geprüft wird deshalb von außen, über die Meldung: kommt nach dem Drehen die Beschwerde über die
 * fehlenden Beträge, ist das Datum durchgekommen — {@code save()} prüft es davor.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SecurityTxRotationTest {

    /**
     * Die Maske kommt aus einer eingelesenen Abrechnung, in der kein Datum steht — nur so ist der Fall
     * überhaupt zu erreichen: Ohne Abrechnung setzt {@code setupNewMode} das heutige Datum und
     * {@code dateKnown} gleich mit, dort gibt es nichts zu verlieren. Erst wenn die App
     * <em>bewusst</em> nichts vorwählt, weil das Dokument nichts hergab, hängt alles an dem Merker.
     */
    private static Intent intent() throws Exception {
        java.io.File beleg = java.io.File.createTempFile("abrechnung", ".txt");
        beleg.deleteOnExit();
        java.nio.file.Files.write(beleg.toPath(),
                "Wertpapierabrechnung Kauf\nKurswert EUR 100,00\n"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        Intent i = new Intent(ApplicationProvider.getApplicationContext(),
                SecurityTxEditActivity.class);
        i.putExtra(SecurityTxEditActivity.EXTRA_DEPOT, "Depot");
        i.putExtra(SecurityTxEditActivity.EXTRA_KMY_ID, "S1");
        i.putExtra(SecurityTxEditActivity.EXTRA_NAME, "Testpapier");
        i.putExtra(SecurityTxEditActivity.EXTRA_STATEMENT_TEXT, beleg.getAbsolutePath());
        return i;
    }

    /** Datum über den Kalender wählen – der Weg, den ein Nutzer ohne erkannte Abrechnung nimmt. */
    private static void waehleDatum(SecurityTxEditActivity a, int jahr, int monat0, int tag)
            throws InterruptedException {
        a.findViewById(R.id.editDate).performClick();
        DatePickerDialog dialog = (DatePickerDialog) warteAufDialog();
        dialog.updateDate(jahr, monat0, tag);
        dialog.onClick(dialog, DialogInterface.BUTTON_POSITIVE);
    }

    /**
     * Auf den Dialog warten. Der Tipp aufs Datumsfeld liest erst die Abrechnung ein, und das geschieht
     * im Hintergrund — der Dialog steht also nicht schon beim Rücksprung aus {@code performClick} da.
     * Gewartet wird abwechselnd: den Bedienfaden abarbeiten, kurz schlafen, wieder nachsehen.
     */
    private static android.app.Dialog warteAufDialog() throws InterruptedException {
        for (int versuch = 0; versuch < 300; versuch++) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            android.app.Dialog dialog = ShadowDialog.getLatestDialog();
            if (dialog != null) {
                return dialog;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("der Datumsdialog kam nicht");
    }

    private static String text(SecurityTxEditActivity a, int id) {
        return ((TextInputEditText) a.findViewById(id)).getText().toString();
    }

    /**
     * Der 17. August 2026 in der Schreibweise der eingestellten Sprache.
     *
     * <p>Hier stand einmal die Zeichenkette „17.08.2026". Seit das Datumsformat der Sprache folgt
     * ({@code DateFormats}), hing der Test damit an der Gerätesprache – unter Robolectric ist die
     * Englisch, und er fiel prompt um. Geprüft werden soll aber der <b>Tag</b>, der die Drehung
     * übersteht, nicht die Schreibweise.</p>
     */
    private static String derSiebzehnteAugust() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.clear();
        c.set(2026, java.util.Calendar.AUGUST, 17);
        return de.spahr.ausgaben.settings.DateFormats.date(c.getTimeInMillis());
    }

    @Test
    public void dasGewaehlteDatumUeberstehtDieDrehung() throws Exception {
        ActivityController<SecurityTxEditActivity> controller =
                Robolectric.buildActivity(SecurityTxEditActivity.class, intent()).setup();
        SecurityTxEditActivity vorher = controller.get();
        ((MaterialButtonToggleGroup) vorher.findViewById(R.id.toggleAction)).check(R.id.btnBuy);
        waehleDatum(vorher, 2026, 7, 17);
        assertEquals(derSiebzehnteAugust(), text(vorher, R.id.editDate));

        controller.recreate();
        SecurityTxEditActivity nachher = controller.get();

        assertEquals("das Feld zeigt es weiterhin", derSiebzehnteAugust(), text(nachher, R.id.editDate));

        nachher.findViewById(R.id.btnSave).performClick();
        String meldung = ShadowToast.getTextOfLatestToast();
        assertNotEquals("das Datum steht im Feld – es darf nicht als fehlend gelten",
                nachher.getString(R.string.security_tx_need_date), meldung);
        assertEquals("stattdessen fehlen die Beträge, wie vor der Drehung auch",
                nachher.getString(R.string.security_tx_need_amounts), meldung);
    }

    /**
     * Auch die Art muss stehen bleiben. Sie hängt zwar am Umschalter, den die View-Hierarchie
     * wiederherstellt — geprüft wird hier, dass der Merker dahinter mitkommt und {@code save()} nicht
     * vorher über die fehlende Art stolpert.
     */
    @Test
    public void dieGewaehlteArtUeberstehtDieDrehung() throws Exception {
        ActivityController<SecurityTxEditActivity> controller =
                Robolectric.buildActivity(SecurityTxEditActivity.class, intent()).setup();
        ((MaterialButtonToggleGroup) controller.get().findViewById(R.id.toggleAction))
                .check(R.id.btnDividend);
        waehleDatum(controller.get(), 2026, 7, 17);

        controller.recreate();
        SecurityTxEditActivity nachher = controller.get();

        nachher.findViewById(R.id.btnSave).performClick();
        assertNotEquals(nachher.getString(R.string.security_tx_need_action),
                ShadowToast.getTextOfLatestToast());
    }
}
