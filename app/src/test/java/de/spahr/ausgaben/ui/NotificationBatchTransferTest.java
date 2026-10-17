package de.spahr.ausgaben.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;

import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.button.MaterialButtonToggleGroup;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.ArrayList;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.notify.NotificationDraft;

/**
 * Die Art einer Zeile in der Liste der Benachrichtigungen: umstellen in der Zeile, ändern in der Maske,
 * und beim nächsten Öffnen der Maske gilt, was zuletzt eingestellt war.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NotificationBatchTransferTest {

    private static NotificationDraft zeile() {
        NotificationDraft d = new NotificationDraft();
        d.entryId = 1L;
        d.packageName = "de.bank";
        d.recognized = true;
        d.cents = 2500L;
        d.account = "Kreditkarte";
        d.payee = "PayPal";
        d.rawPayee = "PayPal";
        d.title = "Kartenzahlung";
        d.text = "Zahlung bei PayPal über 25,00 EUR.";
        d.dateMillis = 1_700_000_000_000L;
        return d;
    }

    private static NotificationBatchActivity liste(NotificationDraft d) {
        ArrayList<NotificationDraft> drafts = new ArrayList<>();
        drafts.add(d);
        Intent i = new Intent(ApplicationProvider.getApplicationContext(),
                NotificationBatchActivity.class)
                .putParcelableArrayListExtra(NotificationBatchActivity.EXTRA_DRAFTS, drafts)
                .putExtra(NotificationBatchActivity.EXTRA_SHOWN_IDS, new long[]{d.entryId});
        NotificationBatchActivity a = Robolectric.buildActivity(NotificationBatchActivity.class, i)
                .setup().get();
        idle();
        return a;
    }

    private static void idle() {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    @SuppressWarnings("unchecked")
    private static NotificationDraft ersteZeile(NotificationBatchActivity a) throws Exception {
        java.lang.reflect.Field f = NotificationBatchActivity.class.getDeclaredField("drafts");
        f.setAccessible(true);
        return ((ArrayList<NotificationDraft>) f.get(a)).get(0);
    }

    private static void aufrufen(NotificationBatchActivity a, String name, Class<?> type, Object arg)
            throws Exception {
        java.lang.reflect.Method m = NotificationBatchActivity.class.getDeclaredMethod(name, type);
        m.setAccessible(true);
        m.invoke(a, arg);
        idle();
    }

    /** Die erste Zeile der Liste, wie sie dasteht. */
    private static String titel(NotificationBatchActivity a) {
        LinearLayout container = a.findViewById(R.id.batchContainer);
        android.widget.TextView t = container.getChildAt(0).findViewById(R.id.textDraftSecurity);
        return t.getText().toString();
    }

    /** Der nächste Aufruf der Maske – mit allem, was die Liste mitgibt. */
    private static Intent maskeOeffnen(NotificationBatchActivity a) throws Exception {
        aufrufen(a, "edit", int.class, 0);
        Intent next = Shadows.shadowOf(a).getNextStartedActivity();
        assertNotNull(next);
        return next;
    }

    /** In der Liste zeigt nur der Pfeil vor dem Empfänger die Umbuchung an. */
    @Test
    public void umbuchungZeigtDenPfeilVorDemEmpfaenger() throws Exception {
        NotificationDraft d = zeile();
        d.isTransfer = true;
        d.transferAccount = "PayPal-Konto";
        assertEquals("→ PayPal", titel(liste(d)));
    }

    @Test
    public void zugangZeigtDenPfeilInDieAndereRichtung() throws Exception {
        NotificationDraft d = zeile();
        d.isIncome = true;
        d.isTransfer = true;
        d.transferAccount = "Girokonto";
        assertEquals("← PayPal", titel(liste(d)));
    }

    @Test
    public void ausgabeOhnePfeil() throws Exception {
        assertEquals("PayPal", titel(liste(zeile())));
    }

    @Test
    public void umbuchungsZeileOeffnetDieMaskeAlsUmbuchung() throws Exception {
        NotificationDraft d = zeile();
        d.isTransfer = true;
        d.transferAccount = "PayPal-Konto";
        Intent next = maskeOeffnen(liste(d));
        assertTrue(next.getBooleanExtra(BookingEditActivity.EXTRA_PREFILL_TRANSFER, false));
        assertEquals("PayPal-Konto",
                next.getStringExtra(BookingEditActivity.EXTRA_PREFILL_TRANSFER_ACCOUNT));
        assertEquals("Kreditkarte", next.getStringExtra(BookingEditActivity.EXTRA_PRESET_ACCOUNT));
        NotificationDraft row = next.getParcelableExtra(BookingEditActivity.EXTRA_BATCH_ROW);
        assertTrue(row.isTransfer);
    }

    /** Was die Maske zurückgibt, gilt – auch die Art – und reist beim nächsten Öffnen wieder mit. */
    @Test
    public void aenderungInDerMaskeWirdUebernommen() throws Exception {
        // Schon einmal geändert: dann öffnet die Maske sofort, ohne neue Auflösung im Hintergrund.
        NotificationDraft start = zeile();
        start.edited = true;
        NotificationBatchActivity a = liste(start);
        maskeOeffnen(a);

        Intent result = new Intent()
                .putExtra(BookingEditActivity.RESULT_TRANSFER, true)
                .putExtra(BookingEditActivity.RESULT_ACCOUNT, "Kreditkarte")
                .putExtra(BookingEditActivity.RESULT_TRANSFER_ACCOUNT, "Barkasse")
                .putExtra(BookingEditActivity.RESULT_INCOME, false)
                .putExtra(BookingEditActivity.RESULT_PAYEE, "Abhebung")
                .putExtra(BookingEditActivity.RESULT_CENTS, 2500L)
                .putExtra(BookingEditActivity.RESULT_DATE_MS, 1_700_000_000_000L)
                .putExtra(BookingEditActivity.RESULT_NOTE, "Bargeld")
                .putExtra(BookingEditActivity.RESULT_TAGS, "");
        aufrufen(a, "applyEdit", Intent.class, result);

        NotificationDraft d = ersteZeile(a);
        assertTrue(d.isTransfer);
        assertTrue(d.edited);
        assertEquals("Barkasse", d.transferAccount);
        assertEquals("Abhebung", d.payee);
        assertEquals("Bargeld", d.note);
        assertEquals("→ Abhebung", titel(a));

        Intent again = maskeOeffnen(a);
        assertTrue(again.getBooleanExtra(BookingEditActivity.EXTRA_PREFILL_TRANSFER, false));
        assertEquals("Barkasse",
                again.getStringExtra(BookingEditActivity.EXTRA_PREFILL_TRANSFER_ACCOUNT));
        assertEquals("Bargeld", again.getStringExtra(BookingEditActivity.EXTRA_PREFILL_NOTE));
        NotificationDraft row = again.getParcelableExtra(BookingEditActivity.EXTRA_BATCH_ROW);
        assertEquals("Abhebung", row.payee);

        // Und zurück zur Ausgabe in der Maske: auch das gilt.
        Intent zurueck = new Intent(result).putExtra(BookingEditActivity.RESULT_TRANSFER, false)
                .putExtra(BookingEditActivity.RESULT_CATEGORY, "Bargeld");
        aufrufen(a, "applyEdit", Intent.class, zurueck);
        assertFalse(ersteZeile(a).isTransfer);
        assertEquals("Bargeld", ersteZeile(a).category);
        assertEquals("Abhebung", titel(a));
    }

    private static BookingEditActivity maske(NotificationDraft d) {
        Intent i = new Intent(ApplicationProvider.getApplicationContext(), BookingEditActivity.class)
                .putExtra(BookingEditActivity.EXTRA_BATCH, true)
                .putExtra(BookingEditActivity.EXTRA_PREFILL_DATE_MS, d.dateMillis)
                .putExtra(BookingEditActivity.EXTRA_PREFILL_INCOME, d.isIncome)
                .putExtra(BookingEditActivity.EXTRA_BATCH_ROW, d)
                .putExtra(BookingEditActivity.EXTRA_PREFILL_TRANSFER, d.isTransfer)
                .putExtra(BookingEditActivity.EXTRA_PREFILL_TRANSFER_ACCOUNT, d.transferAccount)
                .putExtra(BookingEditActivity.EXTRA_PRESET_ACCOUNT, d.account)
                .putExtra(BookingEditActivity.EXTRA_PREFILL_NOTE, d.note);
        BookingEditActivity a = Robolectric.buildActivity(BookingEditActivity.class, i).setup().get();
        idle();
        return a;
    }

    private static String text(android.app.Activity a, int id) {
        android.widget.TextView v = a.findViewById(id);
        return v.getText().toString();
    }

    /** Die Maske zeigt die Umbuchung der Zeile: Art, Von und Nach, Empfänger. */
    @Test
    public void maskeZeigtDieUmbuchungDerZeile() {
        NotificationDraft d = zeile();
        d.isTransfer = true;
        d.transferAccount = "PayPal-Konto";
        BookingEditActivity a = maske(d);
        MaterialButtonToggleGroup type = a.findViewById(R.id.toggleType);
        assertEquals(R.id.btnTransfer, type.getCheckedButtonId());
        assertEquals("Kreditkarte", text(a, R.id.editAccount));
        assertEquals("PayPal-Konto", text(a, R.id.editAccountTo));
        assertEquals("PayPal", text(a, R.id.editPayee));
    }

    /** Ein Zugang: das Konto der Meldung steht als Nach-Konto. */
    @Test
    public void maskeZeigtEinenZugangAlsNachKonto() {
        NotificationDraft d = zeile();
        d.isIncome = true;
        d.isTransfer = true;
        d.transferAccount = "Girokonto";
        BookingEditActivity a = maske(d);
        assertEquals("Girokonto", text(a, R.id.editAccount));
        assertEquals("Kreditkarte", text(a, R.id.editAccountTo));
    }

    /** Eine geänderte Ausgabe kommt mit ihrer Kategorie zurück in die Maske, nicht leer. */
    @Test
    public void geaenderteAusgabeBehaeltIhreKategorie() {
        NotificationDraft d = zeile();
        d.edited = true;
        d.category = "Online";
        d.note = "eigene Notiz";
        BookingEditActivity a = maske(d);
        MaterialButtonToggleGroup type = a.findViewById(R.id.toggleType);
        assertEquals(R.id.btnExpense, type.getCheckedButtonId());
        assertEquals("Kreditkarte", text(a, R.id.editAccount));
        assertEquals("eigene Notiz", text(a, R.id.editNote));
        android.view.ViewGroup splits = a.findViewById(R.id.splitContainer);
        assertTrue(splits.getChildCount() > 0);
        boolean found = false;
        for (android.widget.TextView v : textViews(splits)) {
            found |= "Online".equals(v.getText().toString());
        }
        assertTrue(found);
    }

    private static java.util.List<android.widget.TextView> textViews(android.view.ViewGroup g) {
        java.util.List<android.widget.TextView> out = new java.util.ArrayList<>();
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof android.widget.TextView) {
                out.add((android.widget.TextView) c);
            } else if (c instanceof android.view.ViewGroup) {
                out.addAll(textViews((android.view.ViewGroup) c));
            }
        }
        return out;
    }
}
