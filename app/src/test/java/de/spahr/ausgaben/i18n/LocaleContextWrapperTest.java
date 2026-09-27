package de.spahr.ausgaben.i18n;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.Locale;

import de.spahr.ausgaben.R;

/**
 * Eine importierte Sprachdatei kommt von außen und wird nicht validiert (siehe {@link TranslationIo}) –
 * ein falsch gesetzter Platzhalter darf die App deshalb nicht zum Absturz bringen, sondern muss auf die
 * eingebaute Übersetzung zurückfallen.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LocaleContextWrapperTest {

    @After
    public void tearDown() {
        Strings.set(Collections.emptyMap(), Locale.ENGLISH);
    }

    @Test
    public void kaputterPlatzhalterStuerztNichtAbSondernFaelltZurueck() {
        // „reminder_due" erwartet in der App genau ein %d – die „Übersetzung" hier passt nicht dazu.
        Strings.set(Collections.singletonMap("reminder_due", "Fällig: %2$s kaputt"), Locale.GERMAN);
        Context ctx = ApplicationProvider.getApplicationContext();
        android.content.res.Resources res = LocaleContextWrapper.translate(ctx.getResources());

        String result = res.getString(R.string.reminder_due, 3);

        // Kein Absturz, und statt der kaputten „Übersetzung" kommt die eingebaute Ressource zurück.
        assertEquals(ctx.getResources().getString(R.string.reminder_due, 3), result);
    }

    @Test
    public void passenderPlatzhalterWirdWieGewohntEingesetzt() {
        Strings.set(Collections.singletonMap("reminder_due", "Fällig: %d"), Locale.GERMAN);
        Context ctx = ApplicationProvider.getApplicationContext();
        android.content.res.Resources res = LocaleContextWrapper.translate(ctx.getResources());

        String result = res.getString(R.string.reminder_due, 3);

        assertTrue(result.contains("3"));
    }
}
