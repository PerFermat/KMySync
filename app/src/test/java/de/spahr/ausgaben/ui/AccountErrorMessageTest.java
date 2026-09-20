package de.spahr.ausgaben.ui;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;
import android.widget.AutoCompleteTextView;

import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.db.Account;
import de.spahr.ausgaben.db.AppDatabase;

/**
 * Warum das Konto abgewiesen wurde, steht jetzt in der Meldung.
 *
 * <p>Bis 2.1 bekamen drei verschiedene Lagen denselben Satz: „Bitte ein Konto eingeben". Bei zweien
 * davon war er falsch. Wer ein Konto eintippt, das es nicht gibt, wird gebeten, das einzugeben, was
 * schon dasteht — und liest den Satz als Fehlfunktion, nicht als Hinweis. Und in einer frischen
 * Installation, in der beim Einrichten das Feld „Standardkonto" leer blieb, kann <b>keine</b> Eingabe
 * je durchkommen, weil es überhaupt kein Konto gibt; dort führte der Satz in eine Sackgasse.</p>
 *
 * <p>Gefunden bei der F-Droid-Prüfliste am 20.09.2026: frisch installiert, Einrichtung durchgeklickt,
 * erste Buchung nicht speicherbar. Genau der Weg, den ein Prüfer nimmt.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AccountErrorMessageTest {

    private Context ctx;

    @Before
    public void leereDatenbank() throws Exception {
        ctx = ApplicationProvider.getApplicationContext();
        // Die Instanz ist statisch und überlebt die einzelne Testmethode; ohne das Leeren hinge das
        // Ergebnis an der Reihenfolge der Tests.
        imHintergrund(() -> AppDatabase.getInstance(ctx).clearAllTables());
    }

    private static void imHintergrund(Runnable r) throws InterruptedException {
        Thread t = new Thread(r);
        t.start();
        t.join();
    }

    private void kontenAnlegen(String... namen) throws InterruptedException {
        imHintergrund(() -> {
            for (String n : namen) {
                AppDatabase.getInstance(ctx).accountDao().insertIfAbsent(new Account(n));
            }
        });
    }

    /**
     * Öffnet den Editor und wartet auf die Kontenliste — sie kommt aus der Datenbank und damit später
     * als die Maske. Ohne das Warten wäre {@code knownAccountNames} noch leer und jeder Test bekäme
     * die Meldung für „gar keine Konten".
     */
    private BookingEditActivity maskeMitKonten(boolean erwarteKonten) throws InterruptedException {
        BookingEditActivity a = Robolectric.buildActivity(BookingEditActivity.class).setup().get();
        AutoCompleteTextView konto = a.findViewById(R.id.editAccount);
        for (int i = 0; i < 50; i++) {
            shadowOf(Looper.getMainLooper()).idle();
            if (!erwarteKonten
                    || (konto.getAdapter() != null && konto.getAdapter().getCount() > 0)) {
                break;
            }
            Thread.sleep(20);
        }
        shadowOf(Looper.getMainLooper()).idle();
        return a;
    }

    private static void speichernVersuchen(BookingEditActivity a, String betrag, String konto) {
        TextInputEditText feld = a.findViewById(R.id.editAmount);
        feld.setText(betrag);
        ((AutoCompleteTextView) a.findViewById(R.id.editAccount)).setText(konto);
        ShadowToast.reset();
        ((MaterialButton) a.findViewById(R.id.btnSaveNew)).performClick();
        shadowOf(Looper.getMainLooper()).idle();
    }

    /**
     * Die Lage, die jede frische Installation trifft: Es gibt kein einziges Konto. Dann hilft keine
     * Eingabe, sondern nur der Hinweis, wo Konten entstehen.
     */
    @Test
    public void ohneJedesKontoNenntDieMeldungDenWegDorthin() throws Exception {
        BookingEditActivity a = maskeMitKonten(false);
        speichernVersuchen(a, "12,34", "Bargeld");

        assertEquals(a.getString(R.string.error_account_none), ShadowToast.getTextOfLatestToast());
    }

    /** Konten gibt es, das Feld ist leer — hier war der alte Satz von jeher richtig. */
    @Test
    public void beiLeeremFeldBleibtEsBeiDerBitteUmEineEingabe() throws Exception {
        kontenAnlegen("Giro");
        BookingEditActivity a = maskeMitKonten(true);
        speichernVersuchen(a, "12,34", "");

        assertEquals(a.getString(R.string.error_account), ShadowToast.getTextOfLatestToast());
    }

    /**
     * Der Fall, der die Änderung ausgelöst hat: Es steht etwas da, nur kein bekanntes Konto. Die
     * Meldung nennt den Namen, sonst rät der Nutzer, welches seiner Felder gemeint ist.
     */
    @Test
    public void beiUnbekanntemKontoNenntDieMeldungDenNamen() throws Exception {
        kontenAnlegen("Giro");
        BookingEditActivity a = maskeMitKonten(true);
        speichernVersuchen(a, "12,34", "Bargeld");

        String text = ShadowToast.getTextOfLatestToast();
        assertEquals(a.getString(R.string.error_account_unknown, "Bargeld"), text);
        org.junit.Assert.assertTrue("der Name muss vorkommen", text.contains("Bargeld"));
    }

    /**
     * Gegenprobe: Mit einem bekannten Konto kommt keine der drei Abweisungen mehr, sondern die
     * Bestätigung. Ohne sie bewiesen die Tests oben nur, dass irgendeine Meldung erscheint — nicht,
     * dass die Prüfung überhaupt durchlässig ist.
     */
    @Test
    public void mitBekanntemKontoWirdGespeichert() throws Exception {
        kontenAnlegen("Giro");
        BookingEditActivity a = maskeMitKonten(true);
        speichernVersuchen(a, "12,34", "Giro");

        assertEquals(a.getString(R.string.booking_saved), ShadowToast.getTextOfLatestToast());
    }

    /**
     * Und die drei Texte müssen verschieden sein. Wären zwei davon gleich, liefe die Unterscheidung
     * ins Leere und die Tests oben blieben trotzdem grün.
     */
    @Test
    public void dieDreiMeldungenUnterscheidenSich() {
        String leer = ctx.getString(R.string.error_account);
        String unbekannt = ctx.getString(R.string.error_account_unknown, "Bargeld");
        String keine = ctx.getString(R.string.error_account_none);
        org.junit.Assert.assertNotEquals(leer, unbekannt);
        org.junit.Assert.assertNotEquals(leer, keine);
        org.junit.Assert.assertNotEquals(unbekannt, keine);
    }
}
