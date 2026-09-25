package de.spahr.ausgaben.net;

import java.io.IOException;

/**
 * Der Tausch in {@link SafeReplace} ist mittendrin stehengeblieben: Die neue Datei ließ sich nicht an
 * ihren Platz setzen, und das Zurückbenennen der alten scheiterte ebenfalls. Unter dem eigentlichen
 * Namen liegt dann <b>nichts</b>; die alte Datei liegt vollständig unter {@link #oldName}.
 *
 * <p>Eigene Ausnahme, weil der Nutzer hier handeln muss – und genau wissen soll, was: {@link #oldName}
 * zurück in {@link #file} umbenennen. Das ist der Stand vor dem Export; da nichts als exportiert
 * markiert wird, schreibt der nächste Export die Buchungen nach.</p>
 */
public class RemoteReplaceStuckException extends IOException {

    /** Der eigentliche Dateiname, unter dem gerade nichts liegt. */
    public final String file;
    /** Name, unter dem der alte, vollständige Stand liegt. */
    public final String oldName;

    public RemoteReplaceStuckException(String file, String oldName, Throwable cause) {
        super("Tausch steckengeblieben: " + file + " fehlt, alter Stand liegt als " + oldName, cause);
        this.file = file;
        this.oldName = oldName;
    }
}
