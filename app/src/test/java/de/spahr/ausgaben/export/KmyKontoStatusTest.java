package de.spahr.ausgaben.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Das Schlüsselpaar {@code mm-closed} am Konto setzen und entfernen, so wie KMyMoney es schreibt.
 * Die Beispiele stammen aus einer echten, von KMyMoney 5.2 gespeicherten .kmy (zwei Leerzeichen je
 * Ebene) und aus dem Abbild einer Datenbank (eins je Ebene).
 */
public class KmyKontoStatusTest {

    private static final String KOPF = "<ACCOUNT id=\"A1\" type=\"10\" name=\"Karte\" currency=\"EUR\"";

    @Test
    public void inVorhandenenBlockSortiertEinfuegen() {
        String alt = KOPF + ">\n"
                + "      <KEYVALUEPAIRS>\n"
                + "        <PAIR key=\"kmm-iconpos\" value=\"850;5\"/>\n"
                + "        <PAIR key=\"lastStatementBalance\" value=\"1/1\"/>\n"
                + "        <PAIR key=\"zzz\" value=\"x\"/>\n"
                + "      </KEYVALUEPAIRS>\n"
                + "    </ACCOUNT>";
        String zu = KmyKontoStatus.setze(alt, "    ", true);
        assertEquals(KOPF + ">\n"
                + "      <KEYVALUEPAIRS>\n"
                + "        <PAIR key=\"kmm-iconpos\" value=\"850;5\"/>\n"
                + "        <PAIR key=\"lastStatementBalance\" value=\"1/1\"/>\n"
                + "        <PAIR key=\"mm-closed\" value=\"yes\"/>\n"
                + "        <PAIR key=\"zzz\" value=\"x\"/>\n"
                + "      </KEYVALUEPAIRS>\n"
                + "    </ACCOUNT>", zu);
        assertTrue(KmyKontoStatus.geschlossen(zu));
        assertEquals(alt, KmyKontoStatus.setze(zu, "    ", false));
    }

    @Test
    public void selbstschliessendesKontoBekommtEinenBlock() {
        String alt = KOPF + "/>";
        String zu = KmyKontoStatus.setze(alt, "    ", true);
        assertEquals(KOPF + ">\n"
                + "      <KEYVALUEPAIRS>\n"
                + "        <PAIR key=\"mm-closed\" value=\"yes\"/>\n"
                + "      </KEYVALUEPAIRS>\n"
                + "    </ACCOUNT>", zu);
        assertEquals(alt, KmyKontoStatus.setze(zu, "    ", false));
        // Im Abbild einer Datenbank: ein Leerzeichen je Ebene
        assertEquals(KOPF + ">\n   <KEYVALUEPAIRS>\n    <PAIR key=\"mm-closed\" value=\"yes\"/>\n"
                + "   </KEYVALUEPAIRS>\n  </ACCOUNT>", KmyKontoStatus.setze(alt, "  ", true));
    }

    @Test
    public void blockKommtHinterDieUnterkonten() {
        String alt = KOPF + ">\n"
                + "      <SUBACCOUNTS>\n"
                + "        <SUBACCOUNT id=\"A2\"/>\n"
                + "      </SUBACCOUNTS>\n"
                + "    </ACCOUNT>";
        String zu = KmyKontoStatus.setze(alt, "    ", true);
        assertEquals(KOPF + ">\n"
                + "      <SUBACCOUNTS>\n"
                + "        <SUBACCOUNT id=\"A2\"/>\n"
                + "      </SUBACCOUNTS>\n"
                + "      <KEYVALUEPAIRS>\n"
                + "        <PAIR key=\"mm-closed\" value=\"yes\"/>\n"
                + "      </KEYVALUEPAIRS>\n"
                + "    </ACCOUNT>", zu);
        assertEquals(alt, KmyKontoStatus.setze(zu, "    ", false));
    }

    @Test
    public void unveraendertWennSchonSo() {
        String zu = KmyKontoStatus.setze(KOPF + "/>", "    ", true);
        assertEquals(zu, KmyKontoStatus.setze(zu, "    ", true));
        assertEquals(KOPF + "/>", KmyKontoStatus.setze(KOPF + "/>", "    ", false));
    }

    /** Im ganzen Dokument: nur im ACCOUNTS-Block, nicht in den gleichnamigen Tags der Budgets. */
    @Test
    public void nurImKontenbereich() {
        String xml = "<KMYMONEY-FILE>\n"
                + "  <ACCOUNTS count=\"1\">\n"
                + "    <ACCOUNT id=\"A1\" name=\"Karte\"/>\n"
                + "  </ACCOUNTS>\n"
                + "  <BUDGETS count=\"1\">\n"
                + "    <BUDGET id=\"B1\">\n"
                + "      <ACCOUNT id=\"A1\" budgetlevel=\"monthly\"/>\n"
                + "    </BUDGET>\n"
                + "  </BUDGETS>\n"
                + "</KMYMONEY-FILE>\n";
        Set<String> geaendert = new LinkedHashSet<>();
        Map<String, Boolean> soll = Collections.singletonMap("A1", true);
        String neu = KmyKontoStatus.setze(xml, soll, geaendert);
        assertEquals(Collections.singleton("A1"), geaendert);
        assertTrue(neu.contains("    <ACCOUNT id=\"A1\" name=\"Karte\">\n"
                + "      <KEYVALUEPAIRS>\n        <PAIR key=\"mm-closed\" value=\"yes\"/>\n"
                + "      </KEYVALUEPAIRS>\n    </ACCOUNT>\n"));
        assertTrue(neu.contains("      <ACCOUNT id=\"A1\" budgetlevel=\"monthly\"/>\n"));
        geaendert.clear();
        assertEquals(xml, KmyKontoStatus.setze(neu, Collections.singletonMap("A1", false), geaendert));
        assertFalse(geaendert.isEmpty());
    }
}
