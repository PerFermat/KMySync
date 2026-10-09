package de.spahr.ausgaben.export;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Ein Betrag, wie KMyMoney ihn schreibt: ein Bruch aus zwei ganzen Zahlen ({@code "-1240/100"}, aber
 * auch {@code "488/25"} oder {@code "-218677/25"}). Gerechnet wird exakt – in der Selbstprüfung darf
 * kein Cent in einer Rundung verschwinden, und {@code double} hätte genau das zur Folge.
 *
 * <p>Unveränderlich und immer gekürzt, der Nenner positiv: zwei Brüche sind gleich, wenn sie denselben
 * Wert haben, gleichgültig, wie er dastand.</p>
 */
public final class KmyBruch {

    public static final KmyBruch NULL = new KmyBruch(BigInteger.ZERO, BigInteger.ONE);

    private final BigInteger zaehler;
    private final BigInteger nenner;

    private KmyBruch(BigInteger zaehler, BigInteger nenner) {
        this.zaehler = zaehler;
        this.nenner = nenner;
    }

    private static KmyBruch von(BigInteger zaehler, BigInteger nenner) {
        if (nenner.signum() == 0) {
            throw new NumberFormatException("Nenner 0");
        }
        if (nenner.signum() < 0) {
            zaehler = zaehler.negate();
            nenner = nenner.negate();
        }
        BigInteger teiler = zaehler.gcd(nenner);
        return teiler.signum() == 0 || teiler.equals(BigInteger.ONE)
                ? new KmyBruch(zaehler, nenner)
                : new KmyBruch(zaehler.divide(teiler), nenner.divide(teiler));
    }

    /** Ein Betrag in Cent. */
    public static KmyBruch ausCent(long cent) {
        return von(BigInteger.valueOf(cent), BigInteger.valueOf(100));
    }

    /**
     * Liest {@code "Zähler/Nenner"} oder eine Dezimalzahl. Ein leerer Wert gilt wie in KMyMoney als 0.
     *
     * @throws NumberFormatException wenn dort etwas anderes steht oder der Nenner 0 ist
     */
    public static KmyBruch lesen(String wert) {
        if (wert == null || wert.trim().isEmpty()) {
            return NULL;
        }
        String w = wert.trim();
        int strich = w.indexOf('/');
        if (strich < 0) {
            BigDecimal d = new BigDecimal(w);
            return d.scale() <= 0
                    ? von(d.toBigIntegerExact(), BigInteger.ONE)
                    : von(d.unscaledValue(), BigInteger.TEN.pow(d.scale()));
        }
        return von(new BigInteger(w.substring(0, strich).trim()),
                new BigInteger(w.substring(strich + 1).trim()));
    }

    public KmyBruch plus(KmyBruch b) {
        return von(zaehler.multiply(b.nenner).add(b.zaehler.multiply(nenner)), nenner.multiply(b.nenner));
    }

    public KmyBruch minus(KmyBruch b) {
        return plus(b.negiert());
    }

    public KmyBruch negiert() {
        return new KmyBruch(zaehler.negate(), nenner);
    }

    public boolean istNull() {
        return zaehler.signum() == 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof KmyBruch && zaehler.equals(((KmyBruch) o).zaehler)
                && nenner.equals(((KmyBruch) o).nenner);
    }

    @Override
    public int hashCode() {
        return zaehler.hashCode() * 31 + nenner.hashCode();
    }

    @Override
    public String toString() {
        return zaehler + "/" + nenner;
    }
}
