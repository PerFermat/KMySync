package de.spahr.ausgaben.export;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;

import de.spahr.ausgaben.db.Booking;
import de.spahr.ausgaben.db.SecurityTx;
import de.spahr.ausgaben.db.SecurityTxSplit;

/**
 * Die App schreibt ihre Attribute in der Reihenfolge, die die Datei selbst gerade benutzt. KMyMoney 5
 * würfelt sie bei jedem Speichern neu; eine feste Reihenfolge träfe sie nur zufällig, und im
 * Zeilenvergleich nach dem Export fielen die Zeilen der App neben ihren Nachbarn auf.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KmyAttributeOrderTest {

    private final Context ctx = ApplicationProvider.getApplicationContext();

    private KmyDocument doc(String name) throws Exception {
        return new KmyDocument(KmyRobustnessTest.fixture(name), ctx);
    }

    private static Booking neu() {
        Booking b = new Booking();
        b.id = 5;
        b.account = "Bargeld";
        b.category = "Essen";
        b.payee = "Kiosk";
        b.amountCents = 111;
        b.createdAt = KmyDocument.parseKmyDate("2026-02-01");
        return b;
    }

    /** Die Attributnamen des Tags, das die Zeichenkette {@code marke} enthält. */
    private static String[] folge(String xml, String element, String marke) {
        int stelle = xml.indexOf(marke);
        assertTrue(marke + " nicht gefunden", stelle >= 0);
        int start = xml.lastIndexOf("<" + element + " ", stelle);
        return KmyExporter.attributeNames(xml, "<" + element + " ", start, -1);
    }

    @Test
    public void neueZeilenTragenDieFolgeIhrerNachbarn() throws Exception {
        // edited.xml führt TRANSACTION als id, postdate, memo, entrydate, commodity und SPLIT als
        // id, payee, reconciledate, action, reconcileflag, value, shares, price, memo, account, …
        KmyDocument d = doc("edited.xml");
        String[] txVorlage = folge(d.xml(), "TRANSACTION", "T000000000000000001");
        String[] splitVorlage = folge(d.xml(), "SPLIT", "value=\"-250/100\"");
        String[] payeeVorlage = folge(d.xml(), "PAYEE", "P000001");

        KmyExporter.Result r = new KmyExporter(d, ctx).build(Collections.singletonList(neu()),
                Collections.emptyList(), new HashMap<>());
        assertEquals(Collections.emptyList(), r.skipped);
        KmyExportCheck.pruefen(d.xml(), r.xml, KmyDocument.gzip(r.xml), r.aenderungen);

        assertArrayEquals(txVorlage, folge(r.xml, "TRANSACTION", "postdate=\"2026-02-01\""));
        assertArrayEquals(splitVorlage, folge(r.xml, "SPLIT", "value=\"-111/100\""));
        assertArrayEquals(splitVorlage, folge(r.xml, "SPLIT", "value=\"111/100\""));
        // Der Empfänger der Vorlage kennt nur fünf der acht Attribute, die die App schreibt: die
        // bekannten zuerst in seiner Folge, die übrigen dahinter.
        String[] neuerEmpfaenger = folge(r.xml, "PAYEE", "name=\"Kiosk\"");
        assertArrayEquals(payeeVorlage, Arrays.copyOf(neuerEmpfaenger, payeeVorlage.length));
        assertEquals(8, neuerEmpfaenger.length);
    }

    /** Dieselbe Datei mit umgestellten Attributen – die App folgt der neuen Reihenfolge. */
    @Test
    public void andereFolgeInDerDatei_andereFolgeImGeschriebenen() throws Exception {
        String xml = new String(KmyRobustnessTest.fixture("edited.xml"), StandardCharsets.UTF_8)
                .replace("<TRANSACTION id=\"T000000000000000001\" postdate=\"2026-01-05\" memo=\"\" "
                                + "entrydate=\"2026-01-05\" commodity=\"EUR\">",
                        "<TRANSACTION commodity=\"EUR\" entrydate=\"2026-01-05\" memo=\"\" "
                                + "postdate=\"2026-01-05\" id=\"T000000000000000001\">")
                .replaceFirst("<SPLIT id=\"S0001\" payee=\"P000001\" reconciledate=\"\"",
                        "<SPLIT reconciledate=\"\" payee=\"P000001\" id=\"S0001\"");
        assertTrue("die Vorlage ist wirklich umgestellt", xml.contains("<SPLIT reconciledate="));
        KmyDocument d = new KmyDocument(xml.getBytes(StandardCharsets.UTF_8), ctx);
        KmyExporter.Result r = new KmyExporter(d, ctx).build(Collections.singletonList(neu()),
                Collections.emptyList(), new HashMap<>());

        assertArrayEquals(new String[]{"commodity", "entrydate", "memo", "postdate", "id"},
                folge(r.xml, "TRANSACTION", "postdate=\"2026-02-01\""));
        String[] split = folge(r.xml, "SPLIT", "value=\"-111/100\"");
        assertArrayEquals(new String[]{"reconciledate", "payee", "id"}, Arrays.copyOf(split, 3));
        KmyExportCheck.pruefen(d.xml(), r.xml, KmyDocument.gzip(r.xml), r.aenderungen);
    }

    @Test
    public void geaenderteBuchungFolgtDerDatei() throws Exception {
        KmyDocument d = doc("edited.xml");
        Booking b = neu();
        b.payee = "";
        b.amountCents = 400;
        b.createdAt = KmyDocument.parseKmyDate("2026-01-06");
        b.edited = true;
        b.origAccount = "Bargeld";
        b.origSignedCents = -1000;
        b.origCreatedAt = b.createdAt;
        KmyExporter.Result r = new KmyExporter(d, ctx).build(Collections.emptyList(),
                Collections.singletonList(b), new HashMap<>());
        assertEquals(1, r.updated);
        assertArrayEquals(folge(d.xml(), "TRANSACTION", "T000000000000000001"),
                folge(r.xml, "TRANSACTION", "T000000000000000002"));
        assertArrayEquals(folge(d.xml(), "SPLIT", "value=\"-250/100\""),
                folge(r.xml, "SPLIT", "value=\"-4/1\""));
    }

    @Test
    public void wertpapierSplitFolgtDerDatei() throws Exception {
        KmyDocument d = doc("security-tx.xml");
        SecurityTx tx = new SecurityTx();
        tx.id = 1;
        tx.depot = "Depot";
        tx.securityKmyId = "E000001";
        tx.securityName = "Musterfonds";
        tx.moneyAccount = "Verrechnungskonto";
        tx.action = SecurityTx.BUY;
        tx.shares = 2;
        tx.amountCents = 10000;
        tx.feeCents = 100;
        tx.netCents = 10100;
        tx.date = KmyDocument.parseKmyDate("2026-04-01");
        tx.pending = true;
        tx.parts.add(new SecurityTxSplit(0, false, "Bankgebühren", 100, "", 0));
        KmyExporter.SecurityResult r = new KmyExporter(d, ctx)
                .buildSecurityTransactions(d.xml(), Collections.singletonList(tx));
        assertEquals(r.skipped.toString(), 1, r.writtenIds.size());
        assertArrayEquals(folge(d.xml(), "SPLIT", "action=\"Buy\""),
                folge(r.xml, "SPLIT", "shares=\"2/1\""));
    }

    /** Eine frische Datei gibt nichts vor: dann die Reihenfolge der aktuellen KMyMoney-Fassung. */
    @Test
    public void ohneVorlageDieFolgeVonKMyMoney() throws Exception {
        KmyDocument d = doc("empty-blocks.xml");
        KmyExporter.Result r = new KmyExporter(d, ctx).build(Collections.singletonList(neu()),
                Collections.emptyList(), new HashMap<>());
        assertEquals(Collections.emptyList(), r.skipped);
        assertArrayEquals(new String[]{"id", "postdate", "memo", "entrydate", "commodity"},
                folge(r.xml, "TRANSACTION", "postdate=\"2026-02-01\""));
        assertArrayEquals(new String[]{"id", "payee", "reconciledate", "action", "reconcileflag", "value",
                "shares", "price", "memo", "account", "number", "bankid"},
                folge(r.xml, "SPLIT", "value=\"-111/100\""));
        assertArrayEquals(new String[]{"street", "city", "state", "postcode", "telephone"},
                folge(r.xml, "ADDRESS", "postcode"));
    }

    /**
     * Die Adresse eines neuen Empfängers trägt genau die Attribute, die die Empfänger-Adressen der
     * Datei tragen – die Namen der Postleitzahl und des Landes wechseln zwischen den KMyMoney-Fassungen.
     */
    @Test
    public void adresseDesNeuenEmpfaengersWieInDerDatei() throws Exception {
        String basis = new String(KmyRobustnessTest.fixture("edited.xml"), StandardCharsets.UTF_8);
        String alt = "<PAYEE id=\"P000001\" name=\"Bäcker\" email=\"\" reference=\"\" "
                + "matchingenabled=\"0\"/>";
        assertTrue(basis.contains(alt));
        String offen = alt.substring(0, alt.length() - 2) + ">\n      ";

        // Wie KMyMoney 5.x: postcode und state.
        String xml5 = basis.replace(alt, offen
                + "<ADDRESS postcode=\"\" city=\"\" street=\"\" telephone=\"\" state=\"\"/>\n    </PAYEE>");
        KmyDocument d = new KmyDocument(xml5.getBytes(StandardCharsets.UTF_8), ctx);
        KmyExporter.Result r = new KmyExporter(d, ctx).build(Collections.singletonList(neu()),
                Collections.emptyList(), new HashMap<>());
        int kiosk = r.xml.indexOf("name=\"Kiosk\"");
        assertArrayEquals(new String[]{"postcode", "city", "street", "telephone", "state"},
                KmyExporter.attributeNames(r.xml, "<ADDRESS ", kiosk, -1));
        KmyExportCheck.pruefen(d.xml(), r.xml, KmyDocument.gzip(r.xml), r.aenderungen);

        // Wie die aktuelle Fassung: zip, dazu die alten Namen.
        String xmlNeu = basis.replace(alt, offen + "<ADDRESS street=\"\" city=\"\" state=\"\" zip=\"\" "
                + "telephone=\"\" country=\"\" county=\"\" zipcode=\"\" postcode=\"\"/>\n    </PAYEE>");
        d = new KmyDocument(xmlNeu.getBytes(StandardCharsets.UTF_8), ctx);
        r = new KmyExporter(d, ctx).build(Collections.singletonList(neu()),
                Collections.emptyList(), new HashMap<>());
        kiosk = r.xml.indexOf("name=\"Kiosk\"");
        assertArrayEquals(new String[]{"street", "city", "state", "zip", "telephone", "country", "county",
                "zipcode", "postcode"}, KmyExporter.attributeNames(r.xml, "<ADDRESS ", kiosk, -1));
        assertTrue(r.xml.substring(kiosk).contains("zip=\"\" telephone=\"\""));
    }

    @Test
    public void attributnamenLesen() {
        assertArrayEquals(new String[]{"a", "b", "c"}, KmyExporter.attributeNames(
                "<X a=\"1 > 2\" b='x=y' c=\"\"/>", "<X ", 0, -1));
        assertArrayEquals(new String[0], KmyExporter.attributeNames("<Y a=\"1\"/>", "<X ", 0, -1));
        assertEquals("<X b=\"2\" a=\"1\" c=\"3\"", KmyExporter.openTag("X", new String[]{"b", "zz"},
                new String[]{"a"}, "a", "1", "b", "2", "c", "3"));
    }
}
