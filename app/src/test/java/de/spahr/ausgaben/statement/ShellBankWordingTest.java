package de.spahr.ausgaben.statement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Ein Satzbau, wie Bank-Apps ihn für Kartenumsätze verwenden: „Neuer Umsatz" / „Sie haben kontaktlos
 * 9,12 € bei … bezahlt." Absender ist hier die Shell — die „Bank", unter der sich die Funktion am
 * Gerät mit {@code adb shell cmd notification post} ausprobieren lässt.
 */
public class ShellBankWordingTest {

    private static NotificationSource meldung(String body) {
        return new NotificationSource("com.android.shell", 0L, "Neuer Umsatz", body);
    }

    private static NotificationTemplate gelernt() {
        return NotificationLearner.learn(
                meldung("Sie haben kontaktlos 12,34 € bei REWE Markt bezahlt."), 1234L, false, "Rewe",
                "Kreditkarte");
    }

    @Test
    public void lerntUndLiestDieNaechsteKontaktloseZahlung() {
        NotificationTemplate t = gelernt();
        assertNotNull(t);
        NotificationTemplate.Hit hit = t.apply(meldung("Sie haben kontaktlos 7,50 € bei Lidl bezahlt."));
        assertNotNull(hit);
        assertEquals(750L, hit.cents);
        assertEquals("Lidl", hit.payee);
    }

    /** Händler mit Ziffern im Namen: gelesen wird der Betrag, nicht die Filialnummer. */
    @Test
    public void ziffernImHaendlernamenStoerenNicht() {
        NotificationTemplate.Hit hit = gelernt().apply(
                meldung("Sie haben kontaktlos 50,00 € bei Shell 4711 bezahlt."));
        assertNotNull(hit);
        assertEquals(5000L, hit.cents);
        assertEquals("Shell 4711", hit.payee);
    }

    /**
     * Der Anker ist das Wort vor dem Betrag — hier „kontaktlos". Eine Zahlung, die anders zustande kam,
     * liest diese Vorlage deshalb nicht: sie erscheint als „nicht erkannt" und wird, von Hand gebucht,
     * zur zweiten Vorlage. Festgehalten, damit das niemand für einen Fehler hält.
     */
    @Test
    public void andereZahlungsartBrauchtIhreEigeneVorlage() {
        assertNull(gelernt().apply(meldung("Sie haben 7,50 € bei Lidl bezahlt.")));
        assertNull(gelernt().apply(meldung("Sie haben online 19,99 € bei Amazon bezahlt.")));
    }
}
