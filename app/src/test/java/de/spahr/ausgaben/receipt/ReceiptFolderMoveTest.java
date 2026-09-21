package de.spahr.ausgaben.receipt;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Zerlegung der Einträge des Ordnerwechsels ({@link Receipts#folderMoveParts}).
 *
 * <p>Warum das einen eigenen Test verdient: Anders als beim Jahreswechsel – wo links und rechts nur
 * Zahlen stehen – sind hier zwei <b>Serverpfade</b> im Spiel. Von vorn zerlegt (wie
 * {@code moveParts} es tut) risse ein Pfad mit einem {@code |} darin den Eintrag an der falschen
 * Stelle auseinander. Zerlegt wird deshalb von hinten: Der Dateiname einer Beleg­seite ist eine UUID
 * plus {@code _p1.jpg} und trägt nie ein {@code |}.</p>
 */
public class ReceiptFolderMoveTest {

    @Test
    public void gewoehnlicherEintrag() {
        assertArrayEquals(new String[]{"KMyMoney/Belege/2026", "KMyMoney/Belege-Firma/2026", "abc_p1.jpg"},
                Receipts.folderMoveParts("KMyMoney/Belege/2026|KMyMoney/Belege-Firma/2026|abc_p1.jpg"));
    }

    /** Der eigentliche Grund für das Zerlegen von hinten. */
    @Test
    public void pfadMitSenkrechtemStrichBleibtHeil() {
        String[] teile = Receipts.folderMoveParts("Ab|lage/Belege/2026|Neu/Belege/2026|abc_p1.jpg");
        assertArrayEquals(new String[]{"Ab|lage/Belege/2026", "Neu/Belege/2026", "abc_p1.jpg"}, teile);
    }

    @Test
    public void unbrauchbaresWirdAlsSolchesGemeldet() {
        assertNull(Receipts.folderMoveParts(null));
        assertNull(Receipts.folderMoveParts(""));
        assertNull(Receipts.folderMoveParts("ohneTrenner"));
        assertNull(Receipts.folderMoveParts("nur|einTrenner"));
    }

    /** Leere Abschnitte sind unbrauchbar – ein Umzug ohne Quelle, Ziel oder Datei ergibt nichts. */
    @Test
    public void leereAbschnitteSindUnbrauchbar() {
        assertNull(Receipts.folderMoveParts("|Neu/Belege/2026|abc_p1.jpg"));
        assertNull(Receipts.folderMoveParts("Alt/Belege/2026||abc_p1.jpg"));
        assertNull(Receipts.folderMoveParts("Alt/Belege/2026|Neu/Belege/2026|"));
    }
}
