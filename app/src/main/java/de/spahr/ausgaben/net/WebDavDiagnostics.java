package de.spahr.ausgaben.net;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLException;

import static de.spahr.ausgaben.net.Diagnostics.t;

import de.spahr.ausgaben.net.Diagnostics.Log;
import de.spahr.ausgaben.net.Diagnostics.Step;
import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Prüft eine WebDAV-/Nextcloud-Verbindung Schritt für Schritt und schreibt einen Bericht, den ein
 * Nutzer unverändert weiterschicken kann – das Gegenstück zu {@code SmbDiagnostics}.
 *
 * <p>Warum das nötig ist: Bei WebDAV fallen die häufigen Fehler alle in denselben einen Satz
 * zusammen. Ob die Basis-URL schon {@code /remote.php/dav/...} enthält (dann steht es zweimal da), ob
 * bei aktivierter Zwei-Faktor-Anmeldung das Kontopasswort statt eines App-Passworts eingetragen ist,
 * ob ein vorgeschalteter Server 403 sagt, ob das Zertifikat abgelaufen ist, ob umgeleitet wird oder ob
 * der Server schlicht kein {@code MOVE} erlaubt – aus „Verbindung fehlgeschlagen" ist keines davon zu
 * erkennen. Hier steht je Schritt, <b>was</b> versucht wurde, <b>ob</b> es ging und im Fehlerfall der
 * <b>rohe Statuscode</b> samt Deutung.</p>
 *
 * <p>Der Bericht enthält <b>nie</b> das Passwort. Schema, Host, Port und Pfadaufbau stehen im
 * Klartext – ohne sie kann niemand aus der Ferne helfen –, der Benutzername dagegen nur als
 * {@code <user>} bzw. „gesetzt"/„leer".</p>
 *
 * <p>Die Texte stehen deutsch und englisch nebeneinander in {@link Diagnostics#t}; warum nicht in
 * {@code strings.xml}, steht dort.</p>
 */
public final class WebDavDiagnostics {

    /**
     * Platzhalter, der im Bericht überall dort steht, wo der Benutzername stünde.
     *
     * <p>Bewusst in beiden Sprachen dasselbe Zeichen: Er steht mitten in einer URL, wo ein deutsches
     * Wort nur verwirrte – und wer den Bericht bekommt, soll ihn in beiden Fällen wiedererkennen.</p>
     */
    static final String USER_MASK = "<user>";

    private static String umbenennenNoetig() {
        return t("der Server erlaubt kein MOVE – ", "the server does not allow MOVE – ")
                + Diagnostics.umbenennenNoetig();
    }

    private static final MediaType XML = MediaType.parse("application/xml; charset=utf-8");
    private static final MediaType OCTET = MediaType.parse("application/octet-stream");
    private static final byte[] PROBE = "KMySync".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /**
     * Kurz getaktet: Der Normalbetrieb darf 120 s auf eine große Datei warten, eine Diagnose nicht –
     * wer wissen will, warum nichts geht, soll nicht minutenlang vor einem Wartehinweis sitzen.
     * Umleitungen werden bewusst verfolgt, damit der Schritt „Umleitung" sie überhaupt sehen kann.
     */
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    private WebDavDiagnostics() {
    }

    /** Bequemlichkeit für einen Dateipfad ({@code Ordner/Datei.kmy}); zerlegt ihn und prüft beides. */
    public static List<Step> run(String baseUrl, String user, String password,
                                 boolean nextcloudLayout, String kmyPath) {
        return run(baseUrl, user, password, nextcloudLayout,
                RemotePath.folderOf(kmyPath), RemotePath.fileOf(kmyPath));
    }

    /**
     * Läuft die Kette Adresse → Erreichbarkeit → Umleitung → Anmelden → Ordner lesen →
     * <b>Schreiben → Umbenennen → Aufräumen</b> → Datei → Versionskennung durch und bricht beim ersten
     * Fehler ab, der alles Weitere sinnlos macht.
     *
     * @param baseUrl         wie in den Einstellungen: bei Nextcloud {@code https://host}, bei
     *                        generischem WebDAV die vollständige DAV-Wurzel
     * @param nextcloudLayout {@code true} = Pfadschema {@code /remote.php/dav/files/<Benutzer>}
     * @param folder          Zielordner relativ zur Wurzel (leer = die Wurzel selbst)
     * @param file            zu prüfende Datei in diesem Ordner (leer = nur den Ordner prüfen)
     */
    public static List<Step> run(String baseUrl, String user, String password,
                                 boolean nextcloudLayout, String folder, String file) {
        return run(baseUrl, user, password, nextcloudLayout, folder, file, null);
    }

    /**
     * Wie oben, mit Anzeige für den gerade laufenden Schritt.
     *
     * @param progress Anzeige; {@code null} = ohne
     */
    public static List<Step> run(String baseUrl, String user, String password,
                                 boolean nextcloudLayout, String folder, String file,
                                 Diagnostics.Progress progress) {
        Log log = new Log(progress);
        NextcloudUploader urls = new NextcloudUploader(nextcloudLayout);
        String base = baseUrl == null ? "" : baseUrl.trim();
        String who = t("Benutzer ", "user ") + (user == null || user.trim().isEmpty()
                ? t("leer", "empty") : t("gesetzt", "set"));

        HttpUrl parsed = base.isEmpty() ? null : HttpUrl.parse(base);
        if (parsed == null) {
            // Ohne brauchbare Adresse gibt es nichts zu prüfen – das ist die ganze Auskunft.
            log.note(t("Adresse", "Address"), false,
                    (base.isEmpty() ? t("keine Adresse", "no address") : mask(base, user))
                    + t(" – erwartet wird https://server", " – expected is https://server")
                    + (nextcloudLayout ? "" : t("/pfad/zur/dav-wurzel", "/path/to/dav-root")));
            return log.steps();
        }

        String root = urls.rootUrl(base, user == null ? "" : user);
        StringBuilder wie = new StringBuilder(mask(root, user)).append(", ").append(who);
        boolean adresseOk = true;
        if (!"https".equals(parsed.scheme())) {
            // Kein Abbruch: manch ein Heimserver läuft bewusst ohne TLS. Gesagt gehört es trotzdem,
            // denn bei http geht das Passwort im Klartext über die Leitung.
            wie.append(t(" – Achtung: ohne https geht das Passwort im Klartext über die Leitung",
                    " – careful: without https the password travels in the clear"));
        }
        if (nextcloudLayout && base.contains("/remote.php")) {
            // Der häufigste Zuschnittfehler: der Pfad steht dann zweimal in der URL.
            wie.append(t(" – die Basis-URL enthält bereits „/remote.php\"; bei Servertyp Nextcloud"
                            + " gehört dort nur https://server hin, den Rest hängt die App an",
                    " – the base URL already contains \"/remote.php\"; with server type Nextcloud only"
                            + " https://server belongs there, the app appends the rest"));
            adresseOk = false;
        }
        log.note(t("Adresse", "Address"), adresseOk, wie.toString());
        if (!adresseOk) {
            return log.steps();
        }

        // 2./3. Erreichbarkeit und Umleitung: bewusst ohne Anmeldung, damit ein 401 hier nicht mit
        // einem Netzproblem verwechselt wird. OPTIONS verrät zudem, ob dort überhaupt WebDAV spricht.
        log.begin(t("Erreichbarkeit", "Reachability"));
        Response options;
        try {
            options = CLIENT.newCall(new Request.Builder().url(root + "/")
                    .method("OPTIONS", null).build()).execute();
        } catch (Exception e) {
            log.fail(netzgrund(e));
            return log.steps();
        }
        try {
            StringBuilder was = new StringBuilder("HTTP ").append(options.code());
            String dav = options.header("DAV");
            was.append(dav == null || dav.isEmpty()
                    ? t(", kein DAV-Kopfzeilenfeld – dort antwortet etwas, das kein WebDAV spricht",
                        ", no DAV header – whatever answers there does not speak WebDAV")
                    : ", DAV: " + dav);
            String allow = options.header("Allow");
            if (allow != null && !allow.isEmpty()) {
                was.append(t(", erlaubt: ", ", allowed: "))
                        .append(allow.toUpperCase(Locale.US).contains("MOVE")
                                ? t("MOVE dabei", "MOVE included")
                                : t("MOVE nicht dabei", "MOVE missing"));
            }
            log.ok(was.toString());
            Response first = options.priorResponse();
            if (first != null) {
                log.note(t("Umleitung", "Redirect"), true,
                        mask(first.request().url().toString(), user)
                        + " → " + mask(options.request().url().toString(), user)
                        + t(" – besser gleich die Zieladresse eintragen",
                            " – better to enter the target address right away"));
            }
        } finally {
            options.close();
        }

        // 4. Anmelden: PROPFIND Depth 0 auf die Wurzel. Erst hier zählt das Passwort.
        log.begin(t("Anmelden", "Sign in"));
        try {
            propfind(root + "/", user, password, "0",
                    "<d:prop><d:resourcetype/></d:prop>");
            log.ok("");
        } catch (Exception e) {
            log.fail(grundMitDeutung(e, anmeldeDeutung(code(e), nextcloudLayout)));
            return log.steps();
        }

        // 5. Zielordner lesen.
        String folderUrl = urls.buildFolderUrl(base, user == null ? "" : user, folder);
        String ordner = folder == null || folder.trim().isEmpty()
                ? t("die Wurzel", "the root") : "„" + folder + "\"";
        log.begin(t("Ordner " + ordner + " lesen", "Read folder " + ordner));
        try {
            String xml = propfind(folderUrl, user, password, "1",
                    "<d:prop><d:resourcetype/></d:prop>");
            log.ok(eintraege(xml) + t(" Einträge", " entries"));
        } catch (Exception e) {
            log.fail(grundMitDeutung(e, code(e) == 404
                    ? t("diesen Ordner gibt es dort nicht", "that folder does not exist there")
                    : ordnerDeutung(code(e))));
            return log.steps();
        }

        writeRenameCleanup(urls, base, user, password, folder, log);

        if (file != null && !file.trim().isEmpty()) {
            dateiUndVersion(urls, base, user, password, folder, file.trim(), log);
        }
        return log.steps();
    }

    /**
     * Die drei Rechte, die ein Export wirklich braucht: <b>schreiben</b>, <b>umbenennen</b>,
     * <b>löschen</b>. Der Export schreibt die Datei erst vollständig unter einem Zwischennamen und hängt
     * sie dann per {@code MOVE} ein (siehe {@link SafeReplace}) – nur so kann ein Abbruch die Datei
     * nicht halb überschreiben. Ein Server, der {@code MOVE} verbietet, fiele sonst erst beim ersten
     * echten Übertragen auf.
     */
    private static void writeRenameCleanup(NextcloudUploader urls, String base, String user,
                                           String password, String folder, Log log) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String u = user == null ? "" : user;
        String fromName = Diagnostics.probeName(stamp);
        String toName = Diagnostics.renamedProbeName(stamp);
        String from = urls.buildUrl(base, u, folder, fromName);
        String to = urls.buildUrl(base, u, folder, toName);

        log.begin(t("Schreiben im Ordner", "Write in the folder"));
        try {
            send(new Request.Builder().url(from).put(RequestBody.create(PROBE, OCTET)), user, password);
        } catch (Exception e) {
            log.fail(grundMitDeutung(e, t("die App braucht ein beschreibbares Verzeichnis",
                    "the app needs a writable directory")));
            return;
        }
        log.ok("");

        log.begin(t("Umbenennen im Ordner", "Rename in the folder"));
        String liegengeblieben = fromName;
        String weg = from;
        try {
            send(new Request.Builder().url(from)
                    .header("Destination", to)
                    .header("Overwrite", "T")
                    .method("MOVE", null), user, password);
            liegengeblieben = toName;
            weg = to;
            log.ok("");
        } catch (Exception e) {
            log.fail(grundMitDeutung(e, umbenennenNoetig()));
        }

        log.begin(t("Aufräumen im Ordner", "Clean up in the folder"));
        try {
            send(new Request.Builder().url(weg).delete(), user, password);
            log.ok("");
        } catch (Exception e) {
            log.fail(grundMitDeutung(e, t("bitte " + liegengeblieben + " von Hand löschen",
                    "please delete " + liegengeblieben + " by hand")));
        }
    }

    /**
     * Gibt es die .kmy, und liefert der Server eine Versionskennung dazu? Der ETag ist die WebDAV-
     * Entsprechung zu „Datei beschreibbar" bei SMB: Ohne ihn schreibt die App zwar, kann aber nicht
     * mehr erkennen, ob jemand anders die Datei zwischenzeitlich geändert hat (siehe
     * {@link WebDavStorage#fileVersion} und {@link SafeReplace}).
     */
    private static void dateiUndVersion(NextcloudUploader urls, String base, String user,
                                        String password, String folder, String file, Log log) {
        String url = urls.buildUrl(base, user == null ? "" : user, folder, file);
        log.begin(t("Datei „" + file + "\" prüfen", "Check file \"" + file + "\""));
        String xml;
        try {
            xml = propfind(url, user, password, "0",
                    "<d:prop><d:getetag/><d:getcontentlength/></d:prop>");
        } catch (Exception e) {
            log.fail(grundMitDeutung(e, code(e) == 404
                    ? t("diese Datei gibt es dort nicht", "that file does not exist there") : ""));
            return;
        }
        String size = tag(xml, "getcontentlength");
        log.ok(size.isEmpty() ? t("vorhanden", "present")
                : t("vorhanden, " + size + " Bytes", "present, " + size + " bytes"));
        String etag = tag(xml, "getetag");
        log.note(t("Versionskennung (ETag)", "Version marker (ETag)"), !etag.isEmpty(),
                etag.isEmpty()
                        ? t("der Server liefert keine – dann erkennt die App beim Rückschreiben nicht,"
                                + " ob jemand anders die Datei zwischenzeitlich geändert hat",
                            "the server supplies none – then the app cannot tell on write-back whether"
                                + " somebody else changed the file in the meantime")
                        : t("vorhanden", "present"));
    }

    // ---- HTTP ----

    /** PROPFIND mit Anmeldung; wirft {@link HttpStatusException} mit dem rohen Statuscode. */
    private static String propfind(String url, String user, String password, String depth, String props)
            throws IOException {
        String body = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\">" + props + "</d:propfind>";
        return send(new Request.Builder().url(url)
                .header("Depth", depth)
                .method("PROPFIND", RequestBody.create(body, XML)), user, password);
    }

    /** Führt die Anfrage mit Basic-Auth aus und gibt den Rumpf zurück; Statuscode != 2xx wirft. */
    private static String send(Request.Builder builder, String user, String password)
            throws IOException {
        Request request = builder
                .header("Authorization", Credentials.basic(user == null ? "" : user,
                        password == null ? "" : password))
                .build();
        try (Response response = CLIENT.newCall(request).execute()) {
            ResponseBody rb = response.body();
            String text = rb == null ? "" : rb.string();
            if (!response.isSuccessful()) {
                throw new HttpStatusException(response.code(), response.message());
            }
            return text;
        }
    }

    // ---- Deutung ----

    private static int code(Throwable e) {
        return HttpStatusException.codeOf(e);
    }

    /**
     * Was ein Statuscode beim <b>Anmelden</b> bedeutet. Rein und ohne Netz, damit es sich gewöhnlich
     * testen läßt.
     */
    static String anmeldeDeutung(int code, boolean nextcloudLayout) {
        switch (code) {
            case 401:
                return t("Benutzername oder Passwort stimmen nicht – ist die Zwei-Faktor-Anmeldung"
                                + " eingeschaltet, braucht es hier ein App-Passwort statt des"
                                + " Kontopassworts",
                        "user name or password are wrong – with two-factor sign-in switched on, an"
                                + " app password is needed here instead of the account password");
            case 403:
                return t("der Server verweigert die Anmeldung – oft ein vorgeschalteter Schutz oder"
                                + " eine Sperre nach zu vielen Fehlversuchen",
                        "the server refuses the sign-in – often a protection in front of it or a"
                                + " lockout after too many failed attempts");
            case 404:
                return nextcloudLayout
                        ? t("diese WebDAV-Wurzel gibt es nicht – bei Nextcloud steht der Benutzername"
                                + " im Pfad und muss zu dem passen, mit dem sich die App anmeldet",
                            "that WebDAV root does not exist – with Nextcloud the user name is part of"
                                + " the path and must match the one the app signs in with")
                        : t("diese WebDAV-Wurzel gibt es nicht – bei generischem WebDAV gehört die"
                                + " vollständige DAV-Wurzel in das Adressfeld",
                            "that WebDAV root does not exist – with generic WebDAV the full DAV root"
                                + " belongs in the address field");
            case 405:
            case 501:
                return t("dort spricht kein WebDAV – oft ist versehentlich die Adresse der"
                                + " Weboberfläche eingetragen",
                        "nothing there speaks WebDAV – often the address of the web interface was"
                                + " entered by mistake");
            default:
                return code >= 500
                        ? t("der Server meldet einen eigenen Fehler", "the server reports an error of its own")
                        : "";
        }
    }

    /** Was ein Statuscode beim <b>Ordner lesen</b> bedeutet. */
    static String ordnerDeutung(int code) {
        switch (code) {
            case 403:
                return t("der Ordner ist für dieses Konto gesperrt",
                        "the folder is barred for this account");
            case 404:
                return t("diesen Ordner gibt es dort nicht", "that folder does not exist there");
            case 423:
                return t("der Ordner ist gesperrt (jemand anders bearbeitet ihn gerade)",
                        "the folder is locked (somebody else is working on it)");
            default:
                return code >= 500
                        ? t("der Server meldet einen eigenen Fehler", "the server reports an error of its own")
                        : "";
        }
    }

    /** Rohgrund plus Deutung, falls es eine gibt – der rohe Code bleibt immer stehen. */
    private static String grundMitDeutung(Throwable e, String deutung) {
        String roh = e instanceof HttpStatusException
                ? Diagnostics.shorten(e.getMessage()) : netzgrund(e);
        return deutung == null || deutung.isEmpty() ? roh : roh + " – " + deutung;
    }

    /** Netzfehler in Worten – hier ist die Art der Störung die eigentliche Auskunft. */
    static String netzgrund(Throwable e) {
        if (e instanceof UnknownHostException) {
            return t("der Name ist nicht auflösbar – Schreibweise, DNS oder gar keine Verbindung",
                    "the name cannot be resolved – spelling, DNS, or no connection at all");
        }
        if (e instanceof SSLException) {
            return Diagnostics.shorten(e.getMessage())
                    + t(" – das Zertifikat wird nicht angenommen (abgelaufen, selbst ausgestellt"
                            + " oder für einen anderen Namen)",
                        " – the certificate is not accepted (expired, self-signed, or issued for a"
                            + " different name)");
        }
        if (e instanceof SocketTimeoutException) {
            return t("keine Antwort in der Wartezeit – Port gesperrt oder Server überlastet",
                    "no answer within the timeout – port blocked or server overloaded");
        }
        if (e instanceof ConnectException) {
            return t("die Gegenstelle nimmt keine Verbindung an – Port, Firewall oder falscher Server",
                    "the other end refuses the connection – port, firewall, or wrong server");
        }
        String raw = Diagnostics.shorten(e.getMessage());
        return raw.isEmpty() ? e.getClass().getSimpleName() : raw;
    }

    // ---- Text ----

    /** Die Kopfzeile des Berichts – sie sagt dem Empfänger, worum es überhaupt geht. */
    public static String title() {
        return t("WebDAV-Diagnose (KMySync)", "WebDAV diagnostics (KMySync)");
    }

    /** Kompletter Bericht als Text – genau das, was der Nutzer kopiert und schickt. */
    public static String report(List<Step> steps) {
        return Diagnostics.report(title(), steps);
    }

    /**
     * Ersetzt den Benutzernamen überall durch {@link #USER_MASK}. Der Bericht ist zum Verschicken
     * gedacht; Host und Pfadaufbau gehören hinein, der Kontoname nicht.
     */
    static String mask(String text, String user) {
        String t = text == null ? "" : text;
        String u = user == null ? "" : user.trim();
        if (u.isEmpty()) {
            return t;
        }
        // Auch die URL-kodierte Schreibweise treffen – im Pfad steht sie so.
        String encoded;
        try {
            encoded = java.net.URLEncoder.encode(u, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            encoded = u;
        }
        t = t.replace(u, USER_MASK);
        return encoded.equals(u) ? t : t.replace(encoded, USER_MASK);
    }

    private static final Pattern RESPONSE = Pattern.compile("(?i)<(?:[a-z0-9]+:)?response[\\s>]");

    /**
     * Zahl der Einträge in einer PROPFIND-Antwort mit Depth 1. Der Ordner selbst ist die erste Antwort
     * und zählt nicht mit. Absichtlich ohne XML-Parser: hier interessiert nur die Größenordnung, und
     * so bleibt der Schritt ohne Android testbar.
     */
    static int eintraege(String xml) {
        Matcher m = RESPONSE.matcher(xml == null ? "" : xml);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return Math.max(0, n - 1);
    }

    /** Inhalt des ersten Elements mit diesem lokalen Namen; "" wenn keines da ist. */
    static String tag(String xml, String localName) {
        Matcher m = Pattern.compile("(?is)<(?:[a-z0-9]+:)?" + localName + "[^>]*>(.*?)</")
                .matcher(xml == null ? "" : xml);
        return m.find() ? m.group(1).trim() : "";
    }
}
