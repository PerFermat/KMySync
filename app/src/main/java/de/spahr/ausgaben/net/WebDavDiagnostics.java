package de.spahr.ausgaben.net;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLException;

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
 * {@code <Benutzer>} bzw. „gesetzt"/„leer".</p>
 */
public final class WebDavDiagnostics {

    /** Platzhalter, der im Bericht überall dort steht, wo der Benutzername stünde. */
    static final String USER_MASK = "<Benutzer>";

    private static final String UMBENENNEN_NOETIG =
            "der Server erlaubt kein MOVE – " + Diagnostics.UMBENENNEN_NOETIG;

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
        List<Step> steps = new ArrayList<>();
        NextcloudUploader urls = new NextcloudUploader(nextcloudLayout);
        String base = baseUrl == null ? "" : baseUrl.trim();
        String who = "Benutzer " + (user == null || user.trim().isEmpty() ? "leer" : "gesetzt");

        HttpUrl parsed = base.isEmpty() ? null : HttpUrl.parse(base);
        if (parsed == null) {
            // Ohne brauchbare Adresse gibt es nichts zu prüfen – das ist die ganze Auskunft.
            steps.add(new Step("Adresse", false, (base.isEmpty() ? "keine Adresse" : mask(base, user))
                    + " – erwartet wird https://server" + (nextcloudLayout ? "" : "/pfad/zur/dav-wurzel"),
                    -1));
            return steps;
        }

        String root = urls.rootUrl(base, user == null ? "" : user);
        StringBuilder wie = new StringBuilder(mask(root, user)).append(", ").append(who);
        boolean adresseOk = true;
        if (!"https".equals(parsed.scheme())) {
            // Kein Abbruch: manch ein Heimserver läuft bewusst ohne TLS. Gesagt gehört es trotzdem,
            // denn bei http geht das Passwort im Klartext über die Leitung.
            wie.append(" – Achtung: ohne https geht das Passwort im Klartext über die Leitung");
        }
        if (nextcloudLayout && base.contains("/remote.php")) {
            // Der häufigste Zuschnittfehler: der Pfad steht dann zweimal in der URL.
            wie.append(" – die Basis-URL enthält bereits „/remote.php\"; bei Servertyp Nextcloud gehört"
                    + " dort nur https://server hin, den Rest hängt die App an");
            adresseOk = false;
        }
        steps.add(new Step("Adresse", adresseOk, wie.toString(), -1));
        if (!adresseOk) {
            return steps;
        }

        // 2./3. Erreichbarkeit und Umleitung: bewusst ohne Anmeldung, damit ein 401 hier nicht mit
        // einem Netzproblem verwechselt wird. OPTIONS verrät zudem, ob dort überhaupt WebDAV spricht.
        long t0 = System.currentTimeMillis();
        Response options;
        try {
            options = CLIENT.newCall(new Request.Builder().url(root + "/")
                    .method("OPTIONS", null).build()).execute();
        } catch (Exception e) {
            steps.add(new Step("Erreichbarkeit", false, netzgrund(e), System.currentTimeMillis() - t0));
            return steps;
        }
        try {
            StringBuilder was = new StringBuilder("HTTP ").append(options.code());
            String dav = options.header("DAV");
            was.append(dav == null || dav.isEmpty() ? ", kein DAV-Kopfzeilenfeld – dort antwortet"
                    + " etwas, das kein WebDAV spricht" : ", DAV: " + dav);
            String allow = options.header("Allow");
            if (allow != null && !allow.isEmpty()) {
                was.append(", erlaubt: ").append(allow.toUpperCase(Locale.US).contains("MOVE")
                        ? "MOVE dabei" : "MOVE nicht dabei");
            }
            steps.add(new Step("Erreichbarkeit", true, was.toString(),
                    System.currentTimeMillis() - t0));
            Response first = options.priorResponse();
            if (first != null) {
                steps.add(new Step("Umleitung", true, mask(first.request().url().toString(), user)
                        + " → " + mask(options.request().url().toString(), user)
                        + " – besser gleich die Zieladresse eintragen", -1));
            }
        } finally {
            options.close();
        }

        // 4. Anmelden: PROPFIND Depth 0 auf die Wurzel. Erst hier zählt das Passwort.
        t0 = System.currentTimeMillis();
        try {
            propfind(root + "/", user, password, "0",
                    "<d:prop><d:resourcetype/></d:prop>");
            steps.add(new Step("Anmelden", true, "", System.currentTimeMillis() - t0));
        } catch (Exception e) {
            steps.add(new Step("Anmelden", false, grundMitDeutung(e, anmeldeDeutung(code(e), nextcloudLayout)),
                    System.currentTimeMillis() - t0));
            return steps;
        }

        // 5. Zielordner lesen.
        String folderUrl = urls.buildFolderUrl(base, user == null ? "" : user, folder);
        String ordner = folder == null || folder.trim().isEmpty() ? "die Wurzel" : "„" + folder + "\"";
        t0 = System.currentTimeMillis();
        try {
            String xml = propfind(folderUrl, user, password, "1",
                    "<d:prop><d:resourcetype/></d:prop>");
            steps.add(new Step("Ordner " + ordner + " lesen", true, eintraege(xml) + " Einträge",
                    System.currentTimeMillis() - t0));
        } catch (Exception e) {
            steps.add(new Step("Ordner " + ordner + " lesen", false, grundMitDeutung(e,
                    code(e) == 404 ? "diesen Ordner gibt es dort nicht" : ordnerDeutung(code(e))),
                    System.currentTimeMillis() - t0));
            return steps;
        }

        writeRenameCleanup(urls, base, user, password, folder, steps);

        if (file != null && !file.trim().isEmpty()) {
            dateiUndVersion(urls, base, user, password, folder, file.trim(), steps);
        }
        return steps;
    }

    /**
     * Die drei Rechte, die ein Export wirklich braucht: <b>schreiben</b>, <b>umbenennen</b>,
     * <b>löschen</b>. Der Export schreibt die Datei erst vollständig unter einem Zwischennamen und hängt
     * sie dann per {@code MOVE} ein (siehe {@link SafeReplace}) – nur so kann ein Abbruch die Datei
     * nicht halb überschreiben. Ein Server, der {@code MOVE} verbietet, fiele sonst erst beim ersten
     * echten Übertragen auf.
     */
    private static void writeRenameCleanup(NextcloudUploader urls, String base, String user,
                                           String password, String folder, List<Step> steps) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String u = user == null ? "" : user;
        String fromName = RemoteSelfTest.probeName(stamp);
        String toName = RemoteSelfTest.renamedProbeName(stamp);
        String from = urls.buildUrl(base, u, folder, fromName);
        String to = urls.buildUrl(base, u, folder, toName);

        long t0 = System.currentTimeMillis();
        try {
            send(new Request.Builder().url(from).put(RequestBody.create(PROBE, OCTET)), user, password);
        } catch (Exception e) {
            steps.add(new Step("Schreiben im Ordner", false, grundMitDeutung(e,
                    "die App braucht ein beschreibbares Verzeichnis"),
                    System.currentTimeMillis() - t0));
            return;
        }
        steps.add(new Step("Schreiben im Ordner", true, "", System.currentTimeMillis() - t0));

        t0 = System.currentTimeMillis();
        String liegengeblieben = fromName;
        String weg = from;
        try {
            send(new Request.Builder().url(from)
                    .header("Destination", to)
                    .header("Overwrite", "T")
                    .method("MOVE", null), user, password);
            liegengeblieben = toName;
            weg = to;
            steps.add(new Step("Umbenennen im Ordner", true, "", System.currentTimeMillis() - t0));
        } catch (Exception e) {
            steps.add(new Step("Umbenennen im Ordner", false, grundMitDeutung(e, UMBENENNEN_NOETIG),
                    System.currentTimeMillis() - t0));
        }

        t0 = System.currentTimeMillis();
        try {
            send(new Request.Builder().url(weg).delete(), user, password);
            steps.add(new Step("Aufräumen im Ordner", true, "", System.currentTimeMillis() - t0));
        } catch (Exception e) {
            steps.add(new Step("Aufräumen im Ordner", false, grundMitDeutung(e,
                    "bitte " + liegengeblieben + " von Hand löschen"),
                    System.currentTimeMillis() - t0));
        }
    }

    /**
     * Gibt es die .kmy, und liefert der Server eine Versionskennung dazu? Der ETag ist die WebDAV-
     * Entsprechung zu „Datei beschreibbar" bei SMB: Ohne ihn schreibt die App zwar, kann aber nicht
     * mehr erkennen, ob jemand anders die Datei zwischenzeitlich geändert hat (siehe
     * {@link WebDavStorage#fileVersion} und {@link SafeReplace}).
     */
    private static void dateiUndVersion(NextcloudUploader urls, String base, String user,
                                        String password, String folder, String file,
                                        List<Step> steps) {
        String url = urls.buildUrl(base, user == null ? "" : user, folder, file);
        long t0 = System.currentTimeMillis();
        String xml;
        try {
            xml = propfind(url, user, password, "0",
                    "<d:prop><d:getetag/><d:getcontentlength/></d:prop>");
        } catch (Exception e) {
            steps.add(new Step("Datei „" + file + "\" prüfen", false, grundMitDeutung(e,
                    code(e) == 404 ? "diese Datei gibt es dort nicht" : ""),
                    System.currentTimeMillis() - t0));
            return;
        }
        String size = tag(xml, "getcontentlength");
        steps.add(new Step("Datei „" + file + "\" prüfen", true,
                size.isEmpty() ? "vorhanden" : "vorhanden, " + size + " Bytes",
                System.currentTimeMillis() - t0));
        String etag = tag(xml, "getetag");
        steps.add(new Step("Versionskennung (ETag)", !etag.isEmpty(),
                etag.isEmpty() ? "der Server liefert keine – dann erkennt die App beim Rückschreiben"
                        + " nicht, ob jemand anders die Datei zwischenzeitlich geändert hat" : "vorhanden",
                -1));
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
                return "Benutzername oder Passwort stimmen nicht – ist die Zwei-Faktor-Anmeldung"
                        + " eingeschaltet, braucht es hier ein App-Passwort statt des Kontopassworts";
            case 403:
                return "der Server verweigert die Anmeldung – oft ein vorgeschalteter Schutz oder eine"
                        + " Sperre nach zu vielen Fehlversuchen";
            case 404:
                return nextcloudLayout
                        ? "diese WebDAV-Wurzel gibt es nicht – bei Nextcloud steht der Benutzername im"
                        + " Pfad und muss zu dem passen, mit dem sich die App anmeldet"
                        : "diese WebDAV-Wurzel gibt es nicht – bei generischem WebDAV gehört die"
                        + " vollständige DAV-Wurzel in das Adressfeld";
            case 405:
            case 501:
                return "dort spricht kein WebDAV – oft ist versehentlich die Adresse der"
                        + " Weboberfläche eingetragen";
            default:
                return code >= 500 ? "der Server meldet einen eigenen Fehler" : "";
        }
    }

    /** Was ein Statuscode beim <b>Ordner lesen</b> bedeutet. */
    static String ordnerDeutung(int code) {
        switch (code) {
            case 403:
                return "der Ordner ist für dieses Konto gesperrt";
            case 404:
                return "diesen Ordner gibt es dort nicht";
            case 423:
                return "der Ordner ist gesperrt (jemand anders bearbeitet ihn gerade)";
            default:
                return code >= 500 ? "der Server meldet einen eigenen Fehler" : "";
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
            return "der Name ist nicht auflösbar – Schreibweise, DNS oder gar keine Verbindung";
        }
        if (e instanceof SSLException) {
            return Diagnostics.shorten(e.getMessage()) + " – das Zertifikat wird nicht angenommen"
                    + " (abgelaufen, selbst ausgestellt oder für einen anderen Namen)";
        }
        if (e instanceof SocketTimeoutException) {
            return "keine Antwort in der Wartezeit – Port gesperrt oder Server überlastet";
        }
        if (e instanceof ConnectException) {
            return "die Gegenstelle nimmt keine Verbindung an – Port, Firewall oder falscher Server";
        }
        String raw = Diagnostics.shorten(e.getMessage());
        return raw.isEmpty() ? e.getClass().getSimpleName() : raw;
    }

    // ---- Text ----

    /** Die Kopfzeile des Berichts – sie sagt dem Empfänger, worum es überhaupt geht. */
    public static final String TITLE = "WebDAV-Diagnose (KMySync)";

    /** Kompletter Bericht als Text – genau das, was der Nutzer kopiert und schickt. */
    public static String report(List<Step> steps) {
        return Diagnostics.report(TITLE, steps);
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
