package de.spahr.ausgaben.statement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/**
 * Eine gelernte Regel auf dem Weg in die Regelseite und zurück — und was das Nachbessern von Hand
 * leisten soll: aus einer Regel, die nur „kontaktlos" kennt, eine machen, die jede Zahlungsart liest.
 */
public class NotificationRuleFormTest {

    private static final String APP = "com.android.shell";

    private static NotificationSource meldung(String body) {
        return new NotificationSource(APP, 0L, "Neuer Umsatz", body);
    }

    private static NotificationTemplate gelernt() {
        return NotificationLearner.learn(
                meldung("Sie haben kontaktlos 12,34 € bei REWE Markt bezahlt."), 1234L, false, "Rewe",
                "Kreditkarte");
    }

    /** Unverändert durch die Seite gereicht, ist es dieselbe Regel. */
    @Test
    public void hinUndZurueckIstDieselbeRegel() {
        NotificationTemplate t = gelernt();
        NotificationTemplate back = NotificationRuleForm.of(t).toTemplate(7L, APP);
        assertNotNull(back);
        assertTrue(back.sameAs(t));
        assertEquals(7L, back.id);
    }

    @Test
    public void dieSeiteZeigtDieRegelAlsText() {
        NotificationRuleForm f = NotificationRuleForm.of(gelernt());
        assertEquals("Kreditkarte", f.account);
        assertEquals("kontaktlos", f.amountAnchors);
        assertEquals(0, f.amountDirection);
        assertEquals(0, f.amountPosition);   // die erste Zahl hinter der Beschriftung
        assertEquals("€", f.currency);
        assertEquals("bei", f.payeeAnchors);
        assertEquals("bezahlt", f.payeeUntil);
    }

    /** Der eigentliche Zweck: Rückfälle anhängen, eine je Zeile. */
    @Test
    public void rueckfaelleMachenAusEinerRegelEineFuerAlleZahlungsarten() {
        NotificationTemplate enger = gelernt();
        assertNull(enger.apply(meldung("Sie haben online 19,99 € bei Amazon bezahlt.")));
        assertNull(enger.apply(meldung("Sie haben 7,50 € bei Lidl bezahlt.")));

        NotificationRuleForm f = NotificationRuleForm.of(enger);
        f.amountAnchors = "kontaktlos\nonline\nhaben";
        NotificationTemplate weiter = f.toTemplate(1L, APP);
        assertEquals(Arrays.asList("kontaktlos", "online", "haben"), weiter.amountRule.anchors);

        assertEquals(1234L, weiter.apply(
                meldung("Sie haben kontaktlos 12,34 € bei REWE Markt bezahlt.")).cents);
        NotificationTemplate.Hit online = weiter.apply(
                meldung("Sie haben online 19,99 € bei Amazon bezahlt."));
        assertEquals(1999L, online.cents);
        assertEquals("Amazon", online.payee);
        assertEquals(750L, weiter.apply(meldung("Sie haben 7,50 € bei Lidl bezahlt.")).cents);
    }

    /** Leerzeilen und Doppelte in der Kette zählen nicht. */
    @Test
    public void ketteWirdAufgeraeumt() {
        assertEquals(Arrays.asList("kontaktlos", "online"),
                NotificationRuleForm.lines("  kontaktlos \n\nonline\nkontaktlos\n"));
        assertTrue(NotificationRuleForm.lines(null).isEmpty());
    }

    @Test
    public void kontoRichtungUndMerkmalLassenSichAendern() {
        NotificationRuleForm f = NotificationRuleForm.of(gelernt());
        f.account = "Zweitkarte";
        f.isIncome = true;
        f.discriminator = "*1234";
        NotificationTemplate t = f.toTemplate(1L, APP);
        assertEquals("Zweitkarte", t.account);
        assertTrue(t.isIncome);
        // Das Merkmal muss jetzt in der Meldung stehen.
        assertNull(t.apply(meldung("Sie haben kontaktlos 12,34 € bei REWE Markt bezahlt.")));
        assertNotNull(t.apply(meldung("Sie haben kontaktlos 12,34 € bei REWE Markt bezahlt. Karte *1234")));
    }

    /** Ohne Beschriftung für den Empfänger liest die Regel keinen – und verlangt auch keinen. */
    @Test
    public void ohneEmpfaengerBeschriftungKeinEmpfaenger() {
        NotificationRuleForm f = NotificationRuleForm.of(gelernt());
        f.payeeAnchors = " \n";
        NotificationTemplate t = f.toTemplate(1L, APP);
        assertNull(t.payeeRule);
        assertEquals("", t.apply(meldung("Sie haben kontaktlos 12,34 € bezahlt.")).payee);
    }

    /** Das Ende des Empfängers: ein Wort, das Zeilenende oder die erste Zahl. */
    @Test
    public void endeDesEmpfaengers() {
        NotificationRuleForm f = NotificationRuleForm.of(gelernt());
        f.payeeUntil = "";
        assertEquals("REWE Markt bezahlt", f.toTemplate(1L, APP).apply(
                meldung("Sie haben kontaktlos 12,34 € bei REWE Markt bezahlt.")).payee);
        f.amountAnchors = "über";
        f.payeeUntil = "#";
        assertEquals("REWE Markt", f.toTemplate(1L, APP).apply(
                meldung("Zahlung über 12,34 € bei REWE Markt 4711")).payee);
    }

    /** Welche Zahl: die zweite von rechts, wenn dahinter noch eine Kartenendung steht. */
    @Test
    public void stelleDerZahl() {
        NotificationRuleForm f = NotificationRuleForm.of(gelernt());
        f.amountAnchors = "Umsatz";
        f.currency = "";
        f.payeeAnchors = "";
        f.amountPosition = 3;   // zweite von rechts
        NotificationTemplate t = f.toTemplate(1L, APP);
        assertEquals(AnchorRule.Position.LAST, t.amountRule.position);
        assertEquals(2, t.amountRule.nth);
        assertEquals(1234L, t.apply(meldung("Umsatz 5 12,34 1234")).cents);
    }

    /** Ohne Konto oder ohne Beschriftung für den Betrag ist es keine Regel. */
    @Test
    public void unvollstaendigesIstKeineRegel() {
        NotificationRuleForm f = NotificationRuleForm.of(gelernt());
        f.account = "  ";
        assertNull(f.toTemplate(1L, APP));
        f = NotificationRuleForm.of(gelernt());
        f.amountAnchors = "\n";
        assertNull(f.toTemplate(1L, APP));
    }

    /** Der feste Zeilenabstand einer am Titel gelernten Regel übersteht die Seite, obwohl sie ihn nicht zeigt. */
    @Test
    public void gelernterZeilenabstandBleibt() {
        NotificationTemplate t = NotificationLearner.learn(new NotificationSource(APP, 0L, "REWE Markt",
                "Zahlung über 12,34 EUR"), 1234L, false, "Rewe", "Kreditkarte");
        assertNotNull(t.payeeRule);
        assertEquals(AnchorRule.Direction.LINE_ABOVE, t.payeeRule.rule.direction);
        NotificationTemplate back = NotificationRuleForm.of(t).toTemplate(1L, APP);
        assertTrue(back.sameAs(t));
        assertEquals("Shell Tankstelle", back.apply(new NotificationSource(APP, 0L,
                "Shell Tankstelle", "Zahlung über 50,00 EUR")).payee);
    }
}
