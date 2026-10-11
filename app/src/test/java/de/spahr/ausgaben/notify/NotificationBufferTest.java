package de.spahr.ausgaben.notify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Der Puffer der aufgehobenen Benachrichtigungen: was bleibt, was verfällt, was gelöscht wird.
 *
 * <p>Die Löschregeln sind der Kern der Zusage „Rohtexte werden nicht dauerhaft gespeichert" — und
 * zugleich die Stelle, an der zu viel Eifer eine Meldung verschluckt, die der Nutzer nie gesehen
 * hat.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NotificationBufferTest {

    private static final long TAG = 24L * 60 * 60 * 1000;
    private static final long JETZT = 10 * TAG;

    private NotificationBuffer buffer;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        buffer = new NotificationBuffer(ctx);
        buffer.clear();
    }

    @After
    public void tearDown() {
        buffer.close();
    }

    private long add(String app, long time, String key) {
        return buffer.add(app, time, "Kartenzahlung", "12,34 EUR bei REWE", key);
    }

    @Test
    public void aufgehobenesKommtVollstaendigZurueck() {
        buffer.add("de.bank", 5L, "Titel", "Text 12,34 EUR", "k1");
        List<NotificationBuffer.Entry> all = buffer.all();
        assertEquals(1, all.size());
        NotificationBuffer.Entry e = all.get(0);
        assertEquals("de.bank", e.packageName);
        assertEquals(5L, e.postTime);
        assertEquals("Titel", e.title);
        assertEquals("Text 12,34 EUR", e.text);
        assertEquals("k1", e.key);
    }

    /** Das System meldet dieselbe Benachrichtigung mehrfach; sie steht trotzdem nur einmal da. */
    @Test
    public void dieselbeMeldungNurEinmal() {
        assertTrue(add("de.bank", 5L, "k1") > 0);
        assertEquals(-1L, add("de.bank", 5L, "k1"));
        assertEquals(1, buffer.all().size());
    }

    /** Eine aktualisierte Meldung trägt eine neue Zeit — beide bleiben, die Duplikatprüfung urteilt. */
    @Test
    public void aktualisierteMeldungBleibtAlsZweiterEintrag() {
        add("de.bank", 5L, "k1");
        add("de.bank", 6L, "k1");
        assertEquals(2, buffer.all().size());
    }

    @Test
    public void aeltesteZuerst() {
        add("de.bank", 9L, "k2");
        add("de.bank", 3L, "k1");
        assertEquals("k1", buffer.all().get(0).key);
    }

    // ---- Verfall ----

    @Test
    public void unbekannteAppVerfaelltNachEinemTag() {
        add("de.unbekannt", JETZT - TAG - 1, "alt");
        add("de.unbekannt", JETZT - TAG + 1, "frisch");
        assertEquals(1, buffer.expire(JETZT, Collections.<String>emptySet()));
        assertEquals("frisch", buffer.all().get(0).key);
    }

    /** Für eine App mit Vorlage wartet die Meldung auf den nächsten Durchlauf, so lang es dauert. */
    @Test
    public void bekannteAppVerfaelltNicht() {
        add("de.bank", JETZT - 5 * TAG, "alt");
        Set<String> bekannt = new HashSet<>(Collections.singletonList("de.bank"));
        assertEquals(0, buffer.expire(JETZT, bekannt));
        assertEquals(1, buffer.all().size());
    }

    // ---- Löschen ----

    /** Gelöscht wird nach Nummern: was während der Liste eintrifft, bleibt. */
    @Test
    public void geloeschtWirdNurWasGenanntIst() {
        long gezeigt1 = add("de.bank", 1L, "k1");
        long gezeigt2 = add("de.bank", 2L, "k2");
        long spaeter = add("de.bank", 3L, "k3");
        buffer.delete(Arrays.asList(gezeigt1, gezeigt2));
        List<NotificationBuffer.Entry> rest = buffer.all();
        assertEquals(1, rest.size());
        assertEquals(spaeter, rest.get(0).id);
    }

    @Test
    public void leereListeLoeschtNichts() {
        add("de.bank", 1L, "k1");
        buffer.delete(Collections.<Long>emptyList());
        buffer.delete(null);
        assertEquals(1, buffer.all().size());
    }

    @Test
    public void ausschaltenLeertAlles() {
        add("de.bank", 1L, "k1");
        add("de.andere", 2L, "k2");
        buffer.clear();
        assertTrue(buffer.all().isEmpty());
    }

    // ---- bekannte Apps ----

    /** Der Dienst kennt nur das aktive Profil; gezählt werden die Vorlagen aller. */
    @Test
    public void bekannteAppsSindDieVereinigungAllerProfile() {
        KnownApps apps = new KnownApps(ApplicationProvider.getApplicationContext());
        apps.clear();
        apps.put("profil-a", new HashSet<>(Arrays.asList("de.bank", "de.karte")));
        apps.put("profil-b", new HashSet<>(Collections.singletonList("de.andere")));
        assertEquals(new HashSet<>(Arrays.asList("de.bank", "de.karte", "de.andere")), apps.all());
        apps.remove("profil-a");
        assertEquals(new HashSet<>(Collections.singletonList("de.andere")), apps.all());
    }
}
