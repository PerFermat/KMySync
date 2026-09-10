package de.spahr.ausgaben.net;

import java.io.IOException;

/**
 * Ein HTTP-Fehler mit erhaltenem Statuscode.
 *
 * <p>Bisher steckte der Code nur im Meldungstext („HTTP 401 Unauthorized"). Zum Anzeigen reichte das,
 * zum <b>Deuten</b> nicht: Die Diagnose in {@link WebDavDiagnostics} sagt je nach Code etwas ganz
 * anderes – 401 meint die Zugangsdaten, 403 den vorgeschalteten Server, 405 „dort spricht kein
 * WebDAV". Der Text bleibt unverändert, damit alles, was ihn bisher anzeigt, weiter dasselbe zeigt.</p>
 */
public class HttpStatusException extends IOException {

    /** Der HTTP-Statuscode, z. B. 401. */
    public final int code;

    public HttpStatusException(int code, String message) {
        super("HTTP " + code + (message == null || message.isEmpty() ? "" : " " + message));
        this.code = code;
    }

    /** Der Statuscode einer Ausnahme oder 0, wenn es keiner war (Netzfehler, Zeitüberschreitung). */
    public static int codeOf(Throwable t) {
        return t instanceof HttpStatusException ? ((HttpStatusException) t).code : 0;
    }
}
