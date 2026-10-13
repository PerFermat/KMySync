package de.spahr.ausgaben.export;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Eine mehrzeilige Notiz auf dem Weg in die KMyMoney-Datei.
 *
 * <p>Seit Buchungen aus Benachrichtigungen den Wortlaut der Meldung als Notiz tragen, kommen
 * Zeilenumbrüche dort regelmäßig vor. In einem XML-Attribut darf keiner roh stehen — ein Parser macht
 * daraus ein Leerzeichen. KMyMoney schreibt ihn als Zeichenverweis, und so muss er auch hier
 * herauskommen, sonst steht nach dem Zurücklesen alles in einer Zeile.</p>
 */
public class KmyMemoNewlineTest {

    @Test
    public void zeilenumbruchWirdZumZeichenverweis() {
        assertEquals("Neuer Umsatz: 12,34 €&#xa;bei REWE", KmyExporter.esc("Neuer Umsatz: 12,34 €\nbei REWE"));
    }

    @Test
    public void auchDerWagenruecklauf() {
        assertEquals("a&#xd;&#xa;b", KmyExporter.esc("a\r\nb"));
    }
}
