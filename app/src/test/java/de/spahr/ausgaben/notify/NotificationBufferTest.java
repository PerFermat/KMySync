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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    // ---- erledigt je Profil ----

    private static Map<String, Set<String>> profile(String... idUndApps) {
        Map<String, Set<String>> out = new HashMap<>();
        for (int i = 0; i + 1 < idUndApps.length; i += 2) {
            Set<String> apps = out.get(idUndApps[i]);
            if (apps == null) {
                apps = new HashSet<>();
                out.put(idUndApps[i], apps);
            }
            if (!idUndApps[i + 1].isEmpty()) {
                apps.add(idUndApps[i + 1]);
            }
        }
        return out;
    }

    /** Was ein Profil erledigt hat, sieht es nicht mehr — das andere schon. */
    @Test
    public void erledigtGiltNurFuerDasEigeneProfil() {
        long id = add("de.bank", JETZT, "k1");
        buffer.markDone(Collections.singletonList(id), "a");
        assertTrue(buffer.allFor("a").isEmpty());
        assertEquals(1, buffer.allFor("b").size());
        assertEquals(1, buffer.all().size());
    }

    /** Die gemeinsame Karte: gelöscht wird erst, wenn beide Profile fertig sind. */
    @Test
    public void geloeschtWirdErstWennAlleBeteiligtenFertigSind() {
        long id = add("de.bank", JETZT, "k1");
        Map<String, Set<String>> beide = profile("a", "de.bank", "b", "de.bank");
        buffer.markDone(Collections.singletonList(id), "a");
        assertEquals(0, buffer.purge(JETZT, beide, beide));
        assertEquals(1, buffer.all().size());
        buffer.markDone(Collections.singletonList(id), "b");
        assertEquals(1, buffer.purge(JETZT, beide, beide));
        assertTrue(buffer.all().isEmpty());
    }

    /** Ein Profil, das die App nicht freigegeben hat, ist nicht beteiligt und hält nichts auf. */
    @Test
    public void unbeteiligtesProfilHaeltNichtAuf() {
        long id = add("de.bank", JETZT, "k1");
        Map<String, Set<String>> nurA = profile("a", "de.bank", "b", "de.andere");
        buffer.markDone(Collections.singletonList(id), "a");
        assertEquals(1, buffer.purge(JETZT, nurA, nurA));
    }

    /** Wählt A die App ab, bleibt die Meldung, solange B sie freigegeben hat. */
    @Test
    public void abwaehlenInEinemProfilLoeschtNichtFuerDasAndere() {
        add("de.bank", JETZT, "k1");
        Map<String, Set<String>> nurNochB = profile("a", "", "b", "de.bank");
        assertEquals(0, buffer.purge(JETZT, nurNochB, nurNochB));
        assertEquals(1, buffer.allFor("b").size());
    }

    /** Hat kein Profil die App (mehr) freigegeben, geht die Meldung sofort. */
    @Test
    public void ohneBeteiligteSofortWeg() {
        add("de.bank", JETZT, "k1");
        Map<String, Set<String>> keiner = profile("a", "", "b", "de.andere");
        assertEquals(1, buffer.purge(JETZT, keiner, keiner));
    }

    // ---- Verfall ----

    /** Freigegeben, aber ohne Regel: das Profil hat einen Tag Zeit, die Buchung anzulegen. */
    @Test
    public void ohneRegelVerfaelltNachEinemTag() {
        add("de.bank", JETZT - TAG - 1, "alt");
        add("de.bank", JETZT - TAG + 1, "frisch");
        Map<String, Set<String>> frei = profile("a", "de.bank");
        assertEquals(1, buffer.purge(JETZT, frei, profile("a", "")));
        assertEquals("frisch", buffer.all().get(0).key);
    }

    /** Mit Regel wartet die Meldung auf den nächsten Durchlauf des Profils, so lang es dauert. */
    @Test
    public void mitRegelVerfaelltNicht() {
        add("de.bank", JETZT - 5 * TAG, "alt");
        Map<String, Set<String>> frei = profile("a", "de.bank");
        assertEquals(0, buffer.purge(JETZT, frei, frei));
    }

    /** Der Verfall gilt je Profil: B hat eine Regel und hält die Meldung, auch wenn sie für A alt ist. */
    @Test
    public void verfallGiltJeProfil() {
        long id = add("de.bank", JETZT - 5 * TAG, "alt");
        Map<String, Set<String>> frei = profile("a", "de.bank", "b", "de.bank");
        Map<String, Set<String>> regeln = profile("a", "", "b", "de.bank");
        assertEquals(0, buffer.purge(JETZT, frei, regeln));
        buffer.markDone(Collections.singletonList(id), "b");
        assertEquals(1, buffer.purge(JETZT, frei, regeln));
    }

    // ---- Löschen ----

    /** Erledigt wird nach Nummern: was während der Liste eintrifft, bleibt. */
    @Test
    public void erledigtWirdNurWasGenanntIst() {
        long gezeigt1 = add("de.bank", JETZT, "k1");
        long gezeigt2 = add("de.bank", JETZT, "k2");
        long spaeter = add("de.bank", JETZT, "k3");
        Map<String, Set<String>> frei = profile("a", "de.bank");
        buffer.markDone(Arrays.asList(gezeigt1, gezeigt2), "a");
        buffer.purge(JETZT, frei, frei);
        List<NotificationBuffer.Entry> rest = buffer.all();
        assertEquals(1, rest.size());
        assertEquals(spaeter, rest.get(0).id);
    }

    @Test
    public void leereListeAendertNichts() {
        add("de.bank", 1L, "k1");
        buffer.markDone(Collections.<Long>emptyList(), "a");
        buffer.markDone(null, "a");
        buffer.delete(null);
        assertEquals(1, buffer.allFor("a").size());
    }

    /** Mit dem Eintrag gehen seine Vermerke – eine neue Meldung mit derselben Nummer wäre sonst „erledigt". */
    @Test
    public void mitDemEintragGehenSeineVermerke() {
        long id = add("de.bank", JETZT, "k1");
        buffer.markDone(Collections.singletonList(id), "a");
        buffer.delete(Collections.singletonList(id));
        buffer.markDone(Collections.<Long>emptyList(), "a");
        long neu = add("de.bank", JETZT + 1, "k2");
        assertEquals(1, buffer.allFor("a").size());
        assertEquals(neu, buffer.allFor("a").get(0).id);
    }

    @Test
    public void ausschaltenLeertAlles() {
        long id = add("de.bank", 1L, "k1");
        add("de.andere", 2L, "k2");
        buffer.markDone(Collections.singletonList(id), "a");
        buffer.clear();
        assertTrue(buffer.all().isEmpty());
    }

    /**
     * Eine Puffer-Datei aus der ersten Fassung (ohne Erledigt-Vermerke) wird beim Öffnen ergänzt, und
     * was darin lag, ist noch da.
     */
    @Test
    public void alteDateiWirdErgaenztStattVerworfen() {
        Context ctx = ApplicationProvider.getApplicationContext();
        buffer.close();
        ctx.deleteDatabase(NotificationBuffer.FILE);
        android.database.sqlite.SQLiteDatabase alt = ctx.openOrCreateDatabase(
                NotificationBuffer.FILE, Context.MODE_PRIVATE, null);
        alt.execSQL("CREATE TABLE notification_buffer (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "package_name TEXT NOT NULL, post_time INTEGER NOT NULL, title TEXT NOT NULL, "
                + "text TEXT NOT NULL, notif_key TEXT NOT NULL, UNIQUE(notif_key, post_time))");
        alt.execSQL("INSERT INTO notification_buffer (package_name, post_time, title, text, notif_key) "
                + "VALUES ('de.bank', 5, 'Titel', '12,34 EUR', 'k1')");
        alt.setVersion(1);
        alt.close();

        buffer = new NotificationBuffer(ctx);
        assertEquals(1, buffer.allFor("a").size());
        assertEquals("", buffer.all().get(0).coords);
        buffer.markDone(Collections.singletonList(buffer.all().get(0).id), "a");
        assertTrue(buffer.allFor("a").isEmpty());
    }

    /** Der Standort kommt nach, sobald die Ortung fertig ist, und reist dann mit dem Eintrag. */
    @Test
    public void standortWirdNachgetragen() {
        long id = add("de.bank", JETZT, "k1");
        assertEquals("", buffer.all().get(0).coords);
        buffer.setCoords(id, "50.123456, 8.654321");
        assertEquals("50.123456, 8.654321", buffer.allFor("a").get(0).coords);
        // Kein Standort heißt: nichts überschreiben.
        buffer.setCoords(id, "");
        assertEquals("50.123456, 8.654321", buffer.all().get(0).coords);
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
        assertEquals(Collections.singleton("profil-b"), apps.byProfile().keySet());
    }
}
