package de.spahr.ausgaben.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import de.spahr.ausgaben.R;

/**
 * „Was ist neu": die drei Listen passen zueinander, und der Dialog kommt nur nach einem Update auf
 * eine Version mit neuen Funktionen – nie nach einer Neuinstallation, nie zweimal.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WhatsNewTest {

    private static List<WhatsNew.Entry> file(String dir) throws Exception {
        File f = new File("src/main/res/" + dir + "/news.json");
        return WhatsNew.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void alleSprachenHabenDieselbenVersionenAbsteigend() throws Exception {
        List<WhatsNew.Entry> de = file("raw-de");
        List<WhatsNew.Entry> en = file("raw");
        List<WhatsNew.Entry> es = file("raw-es");
        assertFalse(de.isEmpty());
        for (List<WhatsNew.Entry> other : List.of(en, es)) {
            assertEquals(de.size(), other.size());
            for (int i = 0; i < de.size(); i++) {
                assertEquals(de.get(i).code, other.get(i).code);
                assertEquals(de.get(i).version, other.get(i).version);
                assertEquals(de.get(i).items.size(), other.get(i).items.size());
            }
        }
        for (List<WhatsNew.Entry> list : List.of(de, en, es)) {
            for (int i = 0; i < list.size(); i++) {
                WhatsNew.Entry e = list.get(i);
                if (i > 0) {
                    assertTrue("absteigend bei " + e.version, list.get(i - 1).code > e.code);
                }
                assertFalse(e.items.isEmpty());
                for (WhatsNew.Item it : e.items) {
                    assertFalse(it.title.trim().isEmpty());
                    assertFalse(it.text.trim().isEmpty());
                }
            }
        }
    }

    @Test
    public void dieLaufendeVersionHatEinenEintragOderKeinen() throws Exception {
        List<WhatsNew.Entry> de = file("raw-de");
        assertNotNull(WhatsNew.entryFor(de, 20));
        assertNull("2.1.1 brachte nur Korrekturen", WhatsNew.entryFor(de, 16));
    }

    @Test
    public void dialogNurNachUpdateMitNeuemUndNurEinmal() {
        assertTrue(WhatsNew.shouldShow(20, 19, false, true));
        assertTrue("Merker fehlt noch, App aber aktualisiert", WhatsNew.shouldShow(20, -1, false, true));
        assertFalse("Neuinstallation", WhatsNew.shouldShow(20, -1, true, true));
        assertFalse("schon gesehen", WhatsNew.shouldShow(20, 20, false, true));
        assertFalse("nur Korrekturen", WhatsNew.shouldShow(21, 20, false, false));
    }

    @Test
    public void seiteZeigtAlleVersionenNeuesteOben() {
        NewsActivity a = Robolectric.buildActivity(NewsActivity.class).setup().get();
        LinearLayout c = a.findViewById(R.id.newsContainer);
        List<WhatsNew.Entry> all = WhatsNew.load(a);
        assertFalse(all.isEmpty());
        assertEquals(all.size() * 2, c.getChildCount());
        assertEquals(a.getString(R.string.news_version, all.get(0).version),
                ((TextView) c.getChildAt(0)).getText().toString());
        assertTrue(((TextView) c.getChildAt(1)).getText().toString()
                .contains(all.get(0).items.get(0).title));
    }
}
