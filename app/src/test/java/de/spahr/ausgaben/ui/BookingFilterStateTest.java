package de.spahr.ausgaben.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Wann der Trichter als gesetzt gilt – und dass Zurücksetzen wirklich alles abräumt. */
public class BookingFilterStateTest {

    @Test
    public void frischIstNichtsGesetzt() {
        assertFalse(new BookingFilterState().isActive());
    }

    @Test
    public void jedesKriteriumZaehltEinzeln() {
        BookingFilterState f = new BookingFilterState();
        f.payee = "Bäcker";
        assertTrue(f.isActive());

        f = new BookingFilterState();
        f.tag = "Urlaub";
        assertTrue(f.isActive());

        f = new BookingFilterState();
        f.amountTo = -1000L;
        assertTrue(f.isActive());

        f = new BookingFilterState();
        f.dateFrom = 0L;
        assertTrue(f.isActive());

        f = new BookingFilterState();
        f.radiusM = 500;
        assertTrue(f.isActive());
    }

    /** Der Kategorietyp allein ist kein Kriterium – ohne Kategorie filtert er nichts. */
    @Test
    public void typOhneKategorieZaehltNicht() {
        BookingFilterState f = new BookingFilterState();
        f.categoryIsIncome = Boolean.TRUE;
        assertFalse(f.isActive());
    }

    @Test
    public void zuruecksetzenRaeumtAllesAb() {
        BookingFilterState f = new BookingFilterState();
        f.payee = "x";
        f.category = "Lebensmittel";
        f.categoryIsMain = true;
        f.categoryIsIncome = Boolean.FALSE;
        f.tag = "t";
        f.amountFrom = 1L;
        f.amountTo = 2L;
        f.dateFrom = 3L;
        f.dateTo = 4L;
        f.radiusM = 100;
        f.center = new double[]{50, 8};

        f.reset();

        assertFalse(f.isActive());
        assertFalse(f.categoryIsMain);
        assertNull(f.categoryIsIncome);
        assertNull(f.center);
    }
}
