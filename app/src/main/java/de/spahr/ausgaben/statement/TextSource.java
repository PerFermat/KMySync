package de.spahr.ausgaben.statement;

import de.spahr.ausgaben.pdf.PdfText;

/**
 * Woher ein Text stammt, aus dem Buchungen gelesen werden — eine Abrechnung als PDF oder die
 * Benachrichtigung einer Bank-App.
 *
 * <p>Die Erkennung selbst ({@link AnchorRule}, {@link TemplateLearner}) arbeitet auf {@link PdfText} und
 * bleibt, wie sie ist: Zeilen aus Wörtern, Beschriftungen als Anker. Die Quelle trägt dazu, was der
 * Text allein nicht hergibt — wer ihn geschickt hat und wann. Eine Benachrichtigung nennt kein Datum
 * und keine Bank; beides steht an ihr, nicht in ihr.</p>
 */
public interface TextSource {

    /** Der Text in Zeilen. Ohne Layout gilt {@link PdfText#hasWordPositions()} als {@code false}. */
    PdfText text();

    /** Die Kennung des Absenders — der Paketname der App; {@code ""} bei einem PDF. */
    String sender();

    /** Wann der Text eintraf, in epoch millis; -1, wenn die Quelle das nicht weiß (PDF). */
    long timeMillis();

    /** Ein PDF als Quelle: nur der Text, kein Absender, keine Zeit. */
    static TextSource ofPdf(final PdfText text) {
        return new TextSource() {
            @Override
            public PdfText text() {
                return text;
            }

            @Override
            public String sender() {
                return "";
            }

            @Override
            public long timeMillis() {
                return -1;
            }
        };
    }
}
