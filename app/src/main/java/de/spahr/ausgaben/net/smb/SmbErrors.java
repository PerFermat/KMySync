package de.spahr.ausgaben.net.smb;

import android.content.Context;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import de.spahr.ausgaben.R;

/**
 * Übersetzt die Ausnahmen der SMB-Schicht in Meldungen, die dem Benutzer sagen, was zu tun ist –
 * statt der rohen smbj-Texte („Authentication failed for …", „STATUS_BAD_NETWORK_NAME").
 *
 * <p>Zwei Regeln, aus einem Fehlerbericht gelernt: „Server nicht erreichbar" darf nur kommen, wenn
 * wirklich noch keine Verbindung stand – wer schon Freigaben gesehen hat, wird von diesem Satz in die
 * Irre geschickt. Und der <b>rohe Grund</b> hängt immer hinten dran, damit ein Bericht aus der Ferne
 * überhaupt zuzuordnen ist.</p>
 */
public final class SmbErrors {

    /** In welchem Schritt der Fehler auftrat; entscheidet über die Deutung von „Zugriff verweigert". */
    public enum Step { CONNECT, LOGIN, SHARE, FOLDER }

    /** Länge des angehängten Rohtexts – genug für einen Statuscode, kurz genug für einen Toast. */
    private static final int RAW_LIMIT = 120;

    private SmbErrors() {
    }

    public static String messageFor(Context context, Step step, Throwable error) {
        return withRaw(context.getString(reasonFor(step, error)), textOf(error));
    }

    /**
     * Die passende Textressource – ohne den angehängten Rohtext. Getrennt, damit die Auswahl in
     * {@code SmbErrorsTest} ohne Android-Ressourcen prüfbar bleibt.
     */
    static int reasonFor(Step step, Throwable error) {
        String raw = textOf(error);
        String upper = raw.toUpperCase(java.util.Locale.ROOT);
        // Ab SHARE steht die Verbindung nachweislich – dann ist „nicht erreichbar" schlicht falsch.
        boolean connected = step == Step.SHARE || step == Step.FOLDER;
        if (!connected && (hasCause(error, UnknownHostException.class, SocketTimeoutException.class,
                ConnectException.class, NoRouteToHostException.class)
                || upper.contains("STATUS_IO_TIMEOUT")
                || upper.contains("STATUS_BAD_NETWORK_PATH"))) {
            return R.string.smb_err_unreachable;
        }
        if (upper.contains("STATUS_LOGON_FAILURE") || upper.contains("STATUS_ACCOUNT_")
                || upper.contains("STATUS_PASSWORD_") || upper.contains("STATUS_WRONG_PASSWORD")
                || upper.contains("STATUS_NO_SUCH_USER") || upper.contains("AUTHENTICATION")) {
            return R.string.smb_err_credentials;
        }
        // Der Server verlangt Signierung, hat die Anmeldung aber als Gast eingestuft.
        if (upper.contains("GUESTSIGNINGREQUIRED") || upper.contains("SMB2GUEST")) {
            return R.string.smb_err_guest_signing;
        }
        if (upper.contains("ENCRYPTION IS REQUIRED") || upper.contains("STATUS_DATA_ERROR")
                || upper.contains("NO ENCRYPTION KEY")) {
            return R.string.smb_err_encryption;
        }
        if (upper.contains("STATUS_PATH_NOT_COVERED") || upper.contains("STATUS_DFS_")) {
            return R.string.smb_err_dfs;
        }
        // smbj spricht kein SMB1: ein Server ohne SMB2/3 scheitert schon beim Aushandeln.
        if (upper.contains("SMB1") || upper.contains("NOT AN SMB2")
                || upper.contains("NO SUPPORTED DIALECT") || upper.contains("COULD NOT NEGOTIATE")) {
            return R.string.smb_err_smb1;
        }
        if (upper.contains("STATUS_BAD_NETWORK_NAME") || upper.contains("STATUS_OBJECT_PATH_NOT_FOUND")
                || upper.contains("STATUS_NETWORK_NAME_DELETED")) {
            return R.string.smb_err_share;
        }
        if (upper.contains("STATUS_ACCESS_DENIED") || upper.contains("STATUS_SHARING_VIOLATION")) {
            return connected ? R.string.smb_err_folder_denied : R.string.smb_err_credentials;
        }
        switch (step) {
            case CONNECT:
                return R.string.smb_err_unreachable;
            case LOGIN:
                return R.string.smb_err_credentials;
            case SHARE:
                return R.string.smb_err_share;
            default:
                return R.string.smb_err_unknown;
        }
    }

    /** Hängt den rohen Grund in Klammern an – einzeilig und gekürzt. */
    static String withRaw(String message, String raw) {
        String one = raw.replaceAll("\\s+", " ").trim();
        if (one.isEmpty() || message.contains(one)) {
            return message;
        }
        if (one.length() > RAW_LIMIT) {
            one = one.substring(0, RAW_LIMIT) + "…";
        }
        return message + " (" + one + ")";
    }

    /**
     * Läuft die Ursachenkette ab und hängt jede Stufe an – aber nur, wenn ihr Text nicht schon
     * drinsteht. Nötig, weil manche Ausnahmen (allen voran {@code ExecutionException}, meist über den
     * Einzelargument-Konstruktor {@code new ExecutionException(cause)} gebaut) ihre eigene
     * {@code getMessage()} bereits mit dem vollen {@code toString()} der Ursache füllen – ohne die
     * Prüfung stünde derselbe Grund zweimal hintereinander im Bericht.
     */
    static String textOf(Throwable error) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = error; t != null && sb.length() < 500; t = t.getCause()) {
            String piece = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            if (sb.indexOf(piece) < 0) {
                sb.append(piece).append(' ');
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return sb.toString().trim();
    }

    @SafeVarargs
    private static boolean hasCause(Throwable error, Class<? extends IOException>... types) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            for (Class<?> type : types) {
                if (type.isInstance(t)) {
                    return true;
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
