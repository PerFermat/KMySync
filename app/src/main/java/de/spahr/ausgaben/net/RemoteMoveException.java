package de.spahr.ausgaben.net;

import java.io.IOException;

/**
 * Das Umbenennen auf dem Server ist gescheitert – der letzte Schritt des gefahrlosen Ersetzens (siehe
 * {@link SafeReplace}). Eigene Ausnahme, damit die Maske das von einem gewöhnlichen Netzfehler
 * unterscheiden und den wahren Grund nennen kann: geschrieben wurde vollständig, nur das Ersetzen ging
 * nicht.
 *
 * <p>Die Zieldatei ist dabei <b>unberührt</b> geblieben. Ursachen sind etwa eine gesperrte Datei
 * (Nextcloud-Locking, HTTP 423), ein Proxy, der die Methode {@code MOVE} nicht durchläßt, oder – bei
 * SMB – eine Freigabe, die Schreiben erlaubt, Löschen aber nicht.</p>
 */
public class RemoteMoveException extends IOException {

    public RemoteMoveException(String message, Throwable cause) {
        super(message, cause);
    }
}
