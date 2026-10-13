package de.spahr.ausgaben.statement;

import java.util.ArrayList;
import java.util.List;

/**
 * Eine Regel für Buchungen aus Benachrichtigungen in der Form, in der die Regelseite sie zeigt: als
 * Text und Auswahl statt als {@link AnchorRule} — und der Weg zurück.
 *
 * <p>Getrennt von der Seite gehalten, damit sich das Hin und Her ohne Gerät prüfen lässt. Der wichtige
 * Fall ist die <b>Kette</b>: eine Beschriftung je Zeile, die erste zuerst. So liest eine einzige Regel
 * „kontaktlos", ersatzweise „online", ersatzweise „haben" — wofür sonst je Zahlungsart einmal gelernt
 * werden müsste.</p>
 */
public final class NotificationRuleForm {

    /** Die wählbaren Richtungen, in der Reihenfolge der Auswahl. */
    public static final AnchorRule.Direction[] DIRECTIONS = {AnchorRule.Direction.SAME_LINE,
            AnchorRule.Direction.LINE_BELOW, AnchorRule.Direction.LINE_ABOVE};

    /** So viele Stellen von jedem Ende bietet die Auswahl an. */
    public static final int MAX_NTH = 3;

    public String account = "";
    public boolean isIncome;
    public String discriminator = "";

    /** Die Beschriftungen des Betrags, eine je Zeile. */
    public String amountAnchors = "";
    /** Index in {@link #DIRECTIONS}. */
    public int amountDirection;
    /** Welche Zahl: 0 = erste, 1 = letzte, 2 = zweite von links, 3 = zweite von rechts, … */
    public int amountPosition;
    public String currency = "";
    private int amountDistance;

    /** Die Beschriftungen des Empfängers, eine je Zeile; leer = die Regel liest keinen. */
    public String payeeAnchors = "";
    public int payeeDirection;
    /** Wo der Empfänger endet: leer = Zeilenende, {@code #} = vor der ersten Zahl, sonst vor diesem Wort. */
    public String payeeUntil = "";
    private int payeeDistance = 1;

    public static NotificationRuleForm of(NotificationTemplate t) {
        NotificationRuleForm f = new NotificationRuleForm();
        f.account = t.account;
        f.isIncome = t.isIncome;
        f.discriminator = t.discriminator;
        f.amountAnchors = join(t.amountRule.anchors);
        f.amountDirection = indexOf(t.amountRule.direction);
        f.amountPosition = positionIndex(t.amountRule.position, t.amountRule.nth);
        f.currency = t.amountRule.currency;
        f.amountDistance = t.amountRule.lineDistance;
        if (t.payeeRule != null) {
            f.payeeAnchors = join(t.payeeRule.rule.anchors);
            f.payeeDirection = indexOf(t.payeeRule.rule.direction);
            f.payeeUntil = t.payeeRule.until;
            f.payeeDistance = Math.max(1, t.payeeRule.rule.lineDistance);
        }
        return f;
    }

    /**
     * Die Regel, wie sie dasteht; {@code null}, wenn sie so nicht gelten kann — ohne Konto oder ohne
     * Beschriftung für den Betrag gäbe es nichts, woran sie eine Buchung festmachen könnte.
     */
    public NotificationTemplate toTemplate(long id, String packageName) {
        List<String> amount = lines(amountAnchors);
        if (account.trim().isEmpty() || amount.isEmpty()) {
            return null;
        }
        AnchorRule.Direction ad = direction(amountDirection);
        boolean first = amountPosition % 2 == 0;
        AnchorRule amountRule = new AnchorRule(amount, ad, false, currency.trim(),
                first ? AnchorRule.Position.FIRST : AnchorRule.Position.LAST, amountPosition / 2 + 1,
                ad == AnchorRule.Direction.SAME_LINE ? 0 : amountDistance);
        TextRule payeeRule = null;
        List<String> payee = lines(payeeAnchors);
        if (!payee.isEmpty()) {
            AnchorRule.Direction pd = direction(payeeDirection);
            payeeRule = new TextRule(new AnchorRule(payee, pd, false, "", AnchorRule.Position.LAST, 1,
                    pd == AnchorRule.Direction.SAME_LINE ? 0 : payeeDistance), payeeUntil);
        }
        return new NotificationTemplate(id, packageName, discriminator.trim(), account.trim(),
                isIncome, amountRule, payeeRule);
    }

    /** Die nicht leeren Zeilen, getrimmt, ohne Doppelte, in ihrer Reihenfolge. */
    public static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    private static String join(List<String> anchors) {
        StringBuilder sb = new StringBuilder();
        for (String a : anchors) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(a);
        }
        return sb.toString();
    }

    private static int indexOf(AnchorRule.Direction direction) {
        for (int i = 0; i < DIRECTIONS.length; i++) {
            if (DIRECTIONS[i] == direction) {
                return i;
            }
        }
        return 0;
    }

    private static AnchorRule.Direction direction(int index) {
        return index >= 0 && index < DIRECTIONS.length ? DIRECTIONS[index] : DIRECTIONS[0];
    }

    /** Die Spaltenlage kennt eine Benachrichtigung nicht; sie zählt hier als „letzte Zahl". */
    private static int positionIndex(AnchorRule.Position position, int nth) {
        int n = Math.max(1, Math.min(MAX_NTH, nth));
        return (n - 1) * 2 + (position == AnchorRule.Position.FIRST ? 0 : 1);
    }
}
