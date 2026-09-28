package de.spahr.ausgaben.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Der Vermerk, der nach einem Absturz zwischen Schreiben und lokalem Markieren verhindert, dass eine
 * Buchung ein zweites Mal exportiert wird.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PendingExportTest {

    private final SettingsStore settings = new SettingsStore(ApplicationProvider.getApplicationContext());

    @Test
    public void ohneVermerkIstDieListeLeer() {
        assertTrue(PendingExport.read(settings).isEmpty());
    }

    @Test
    public void schreibenUndLesenRundtrip() {
        List<PendingExport.Entry> entries = new ArrayList<>();
        entries.add(new PendingExport.Entry(42, "Girokonto", -1234, 5000L, "Bäcker"));
        entries.add(new PendingExport.Entry(43, "Bargeld:Konto", 250, 6000L, ""));

        PendingExport.write(settings, entries);
        List<PendingExport.Entry> back = PendingExport.read(settings);

        assertEquals(2, back.size());
        assertEquals(42, back.get(0).bookingId);
        assertEquals("Girokonto", back.get(0).account);
        assertEquals(-1234, back.get(0).signedCents);
        assertEquals(5000L, back.get(0).createdAt);
        assertEquals("Bäcker", back.get(0).payee);
        assertEquals("Bargeld:Konto", back.get(1).account);
        assertEquals("", back.get(1).payee);
    }

    /** Ein Vermerk aus einer älteren Version ohne „payee"-Schlüssel bleibt lesbar. */
    @Test
    public void fehlenderPayeeSchluesselGiltAlsLeer() {
        settings.setPendingExportRaw("[{\"id\":1,\"account\":\"A\",\"cents\":100,\"created\":1}]");

        List<PendingExport.Entry> back = PendingExport.read(settings);

        assertEquals(1, back.size());
        assertEquals("", back.get(0).payee);
    }

    @Test
    public void clearLoeschtDenVermerk() {
        PendingExport.write(settings,
                Collections.singletonList(new PendingExport.Entry(1, "A", 1, 1, "")));
        PendingExport.clear(settings);

        assertTrue(PendingExport.read(settings).isEmpty());
    }

    @Test
    public void beschaedigterInhaltGiltAlsLeer() {
        settings.setPendingExportRaw("{das ist kein JSON-Array");

        assertTrue(PendingExport.read(settings).isEmpty());
    }
}
