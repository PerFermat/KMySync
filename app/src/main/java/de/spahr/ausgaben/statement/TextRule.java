package de.spahr.ausgaben.statement;

import de.spahr.ausgaben.pdf.PdfText;

/**
 * Eine Regel für ein <b>Textfeld</b> — den Empfänger: wo der Text beginnt ({@link #rule}) und wo er
 * aufhört ({@link #until}).
 *
 * <p>Eine Zahl braucht kein Ende, sie ist ein Wort. Ein Name nicht: „bei REWE Markt über 12,34 EUR"
 * nennt zwei Wörter, beim nächsten Händler sind es drei. Was bleibt, ist das Wort dahinter.</p>
 */
public final class TextRule {

    public final AnchorRule rule;
    /** Siehe {@link AnchorRule#readText(PdfText, String)}. */
    public final String until;

    public TextRule(AnchorRule rule, String until) {
        this.rule = rule;
        this.until = until == null ? "" : until.trim();
    }

    /** Der Text dieser Regel; {@code null}, wenn die Meldung anders gebaut ist als die gelernte. */
    public String read(PdfText text) {
        return rule.readText(text, until);
    }

    @Override
    public String toString() {
        return rule + (until.isEmpty() ? "" : " bis " + until);
    }
}
