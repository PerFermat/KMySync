package de.spahr.ausgaben.statement;

import de.spahr.ausgaben.pdf.PdfText;

/**
 * Eine gelernte Vorlage für die Benachrichtigungen <b>einer</b> App an <b>ein</b> Konto: wo der Betrag
 * steht, wo der Empfänger, und ob es eine Einnahme ist.
 *
 * <p>Das Gegenstück zu {@link StatementTemplate}, mit denselben Bausteinen ({@link AnchorRule}), aber
 * anderen Feldern — eine Wertpapierabrechnung kennt Stückzahl und Kurs, eine Kartenzahlung Betrag und
 * Händler. Schlüssel ist die App samt Unterscheidungsmerkmal; das Konto steht an der Vorlage, weil ein
 * Konto in dieser App keine Bank kennt.</p>
 *
 * <p>Eine App darf mehrere Vorlagen je Konto haben: die Zahlung liest sich anders als die Gutschrift.</p>
 */
public final class NotificationTemplate {

    /** Was eine Vorlage aus einer Meldung gelesen hat. */
    public static final class Hit {
        public final NotificationTemplate template;
        /** Der Betrag in Cent, immer positiv; die Richtung steht in {@link NotificationTemplate#isIncome}. */
        public final long cents;
        /** Der Empfänger, wie er in der Meldung steht; {@code ""}, wenn die Vorlage keinen liest. */
        public final String payee;

        Hit(NotificationTemplate template, long cents, String payee) {
            this.template = template;
            this.cents = cents;
            this.payee = payee;
        }
    }

    /** Die Nummer der gespeicherten Regel – gebraucht, um sie zu ändern oder zu löschen; sonst 0. */
    public final long id;
    public final String packageName;
    /** Ein Wort, das in der Meldung stehen muss — etwa die Kartenendung; {@code ""}, wenn keines. */
    public final String discriminator;
    public final String account;
    public final boolean isIncome;
    public final AnchorRule amountRule;
    /** {@code null}, wenn sich der Empfänger beim Lernen nicht anankern ließ. */
    public final TextRule payeeRule;

    public NotificationTemplate(String packageName, String discriminator, String account,
                                boolean isIncome, AnchorRule amountRule, TextRule payeeRule) {
        this(0, packageName, discriminator, account, isIncome, amountRule, payeeRule);
    }

    /** @param id die Nummer der gespeicherten Regel; 0, solange sie noch nicht gespeichert ist */
    public NotificationTemplate(long id, String packageName, String discriminator, String account,
                                boolean isIncome, AnchorRule amountRule, TextRule payeeRule) {
        this.id = id;
        this.packageName = packageName == null ? "" : packageName;
        this.discriminator = discriminator == null ? "" : discriminator.trim();
        this.account = account == null ? "" : account;
        this.isIncome = isIncome;
        this.amountRule = amountRule;
        this.payeeRule = payeeRule;
    }

    /**
     * Liest die Meldung; {@code null}, wenn sie nicht zu dieser Vorlage gehört.
     *
     * <p>Verlangt wird <b>alles</b>, was gelernt wurde: die App, das Merkmal, der Betrag und — wenn die
     * Vorlage einen liest — der Empfänger. Fehlt eines, ist die Meldung anders gebaut als die gelernte,
     * etwa eine abgelehnte Zahlung statt einer ausgeführten. Dann lieber nichts erkennen: die Liste
     * zeigt sie als „nicht erkannt", und der Nutzer entscheidet.</p>
     */
    public Hit apply(TextSource source) {
        if (source == null || amountRule == null || !packageName.equals(source.sender())) {
            return null;
        }
        PdfText text = source.text();
        if (!discriminator.isEmpty() && !hasWord(text, discriminator)) {
            return null;
        }
        Long cents = amountRule.readCents(text);
        if (cents == null || cents <= 0) {
            return null;
        }
        String payee = "";
        if (payeeRule != null) {
            payee = payeeRule.read(text);
            if (payee == null) {
                return null;
            }
        }
        return new Hit(this, cents, payee);
    }

    /** Ob das Wort in der Meldung als eigenes steht. */
    static boolean hasWord(PdfText text, String word) {
        for (PdfText.Line line : text.lines()) {
            for (PdfText.Word w : line.words) {
                if (w.text.equals(word)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Ob beide Vorlagen dasselbe auf dieselbe Weise lesen — dann ist die zweite überflüssig. */
    public boolean sameAs(NotificationTemplate o) {
        return o != null && packageName.equals(o.packageName) && discriminator.equals(o.discriminator)
                && account.equals(o.account) && isIncome == o.isIncome
                && amountRule.equals(o.amountRule)
                && (payeeRule == null ? o.payeeRule == null : o.payeeRule != null
                        && payeeRule.rule.equals(o.payeeRule.rule)
                        && payeeRule.until.equals(o.payeeRule.until));
    }
}
