package de.spahr.ausgaben.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * „ohne Ort" in der Ortsauswahl: angezeigt in der Sprache der App, erkannt in jeder Schreibweise –
 * auch in der alten deutschen, die ältere Fassungen gespeichert haben.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "en")
public class NoPlaceLabelTest {

    @Test
    public void englischeOberflaecheZeigtEnglisch() {
        Context ctx = ApplicationProvider.getApplicationContext();
        assertEquals("no place", PlacesStore.noPlace(ctx));
    }

    @Test
    public void angezeigteUndAlteSchreibweiseGeltenAlsKeinOrt() {
        new PlacesStore(ApplicationProvider.getApplicationContext());
        assertTrue(PlacesStore.isNoPlace("no place"));
        assertTrue(PlacesStore.isNoPlace("ohne Ort"));
        assertTrue(PlacesStore.isNoPlace(" no place "));
        assertFalse(PlacesStore.isNoPlace("Kasse"));
        assertFalse(PlacesStore.isNoPlace(null));
    }
}
