package de.spahr.ausgaben.net;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.StringReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * WebDAV-Client: Hochladen (PUT), Auflisten (PROPFIND) und Herunterladen (GET).
 * Unterstützt das Nextcloud-Layout ({@code /remote.php/dav/files/<user>/…}) sowie generisches WebDAV,
 * bei dem die eingetragene Basis-URL bereits die DAV-Wurzel ist.
 */
public class NextcloudUploader {

    private static final MediaType CSV = MediaType.parse("text/csv; charset=utf-8");
    private static final MediaType XML = MediaType.parse("application/xml; charset=utf-8");
    private static final MediaType OCTET = MediaType.parse("application/octet-stream");

    /**
     * Die Vorgabe von OkHttp sind 10 s je Lese-/Schreibvorgang – zu knapp für eine KMyMoney-Datei von
     * einigen hundert Kilobyte über eine langsame Leitung. Ein Abbruch mittendrin ließ früher eine halb
     * geschriebene Datei auf dem Server zurück; abgesichert ist das inzwischen über {@link SafeReplace},
     * aber der Export soll gar nicht erst grundlos scheitern.
     */
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build();
    /** true = Nextcloud-Pfadschema, false = generisches WebDAV (Basis-URL ist die Wurzel). */
    private final boolean nextcloudLayout;

    public NextcloudUploader() {
        this(true);
    }

    public NextcloudUploader(boolean nextcloudLayout) {
        this.nextcloudLayout = nextcloudLayout;
    }

    /**
     * Lädt {@code content} unter {@code fileName} hoch.
     *
     * @throws IOException bei Netzwerk- oder HTTP-Fehlern (Status != 2xx)
     */
    public void upload(String baseUrl, String user, String password, String folder,
                       String fileName, String content) throws IOException {
        String url = buildUrl(baseUrl, user, folder, fileName);

        RequestBody body = RequestBody.create(content.getBytes(StandardCharsets.UTF_8), CSV);
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", Credentials.basic(user, password))
                .put(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " " + response.message());
            }
        }
    }

    /** Lädt {@code content} (Rohbytes) unter {@code fileName} hoch, z. B. eine gepackte .kmy. */
    public void uploadBytes(String baseUrl, String user, String password, String folder,
                            String fileName, byte[] content) throws IOException {
        uploadBytes(baseUrl, user, password, folder, fileName, content, "");
    }

    /**
     * Wie {@link #uploadBytes(String, String, String, String, String, byte[])}, aber mit optionaler
     * Vorbedingung: Ist {@code expectedEtag} gesetzt, schreibt der Server per {@code If-Match} nur, wenn
     * die Datei noch diesen ETag hat – sonst HTTP 412 → {@link RemoteConflictException}. Die Prüfung
     * findet auf dem Server statt und ist damit atomar.
     */
    public void uploadBytes(String baseUrl, String user, String password, String folder,
                            String fileName, byte[] content, String expectedEtag) throws IOException {
        String url = buildUrl(baseUrl, user, folder, fileName);
        RequestBody body = RequestBody.create(content, OCTET);
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Authorization", Credentials.basic(user, password))
                .put(body);
        if (expectedEtag != null && !expectedEtag.isEmpty()) {
            builder.header("If-Match", expectedEtag);
        }
        try (Response response = client.newCall(builder.build()).execute()) {
            if (response.code() == 412) {
                throw new RemoteConflictException("HTTP 412 (If-Match): " + fileName);
            }
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " " + response.message());
            }
        }
    }

    /**
     * ETag der Datei per PROPFIND (Depth 0). Leerer String, wenn der Server keinen ETag liefert
     * (dann gibt es keinen Konflikt-Schutz).
     */
    public String etag(String baseUrl, String user, String password, String folder, String fileName)
            throws IOException {
        String url = buildUrl(baseUrl, user, folder, fileName);
        String body = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\">"
                + "<d:prop><d:getetag/></d:prop></d:propfind>";
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", Credentials.basic(user, password))
                .header("Depth", "0")
                .method("PROPFIND", RequestBody.create(body, XML))
                .build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody rb = response.body();
            String xml = rb == null ? "" : rb.string();
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " " + response.message());
            }
            return parseEtag(xml);
        }
    }

    /** Erstes {@code <d:getetag>} aus der PROPFIND-Antwort; "" wenn keins vorhanden. */
    private String parseEtag(String xml) throws IOException {
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(new StringReader(xml));
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && "getetag".equals(localName(parser.getName()))) {
                    String v = parser.nextText();
                    return v == null ? "" : v.trim();
                }
                event = parser.next();
            }
        } catch (XmlPullParserException e) {
            throw new IOException("PROPFIND (ETag) nicht lesbar", e);
        }
        return "";
    }

    /**
     * Listet die CSV-Dateinamen im angegebenen Nextcloud-Ordner per WebDAV-PROPFIND.
     *
     * @throws IOException bei Netzwerk-/HTTP-Fehlern
     */
    public List<String> listCsvFiles(String baseUrl, String user, String password, String folder)
            throws IOException {
        return listFiles(baseUrl, user, password, folder, "csv");
    }

    /**
     * Legt den Ordner per WebDAV-MKCOL an. Existiert er bereits (HTTP 405), ist das kein Fehler.
     * Übergeordnete Ordner müssen vorhanden sein (MKCOL legt nur eine Ebene an).
     */
    public void createFolder(String baseUrl, String user, String password, String folder)
            throws IOException {
        String url = buildFolderUrl(baseUrl, user, folder);
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", Credentials.basic(user, password))
                .method("MKCOL", null)
                .build();
        try (Response response = client.newCall(request).execute()) {
            // 201 = angelegt, 405 = existiert bereits.
            if (!response.isSuccessful() && response.code() != 405) {
                throw new IOException("HTTP " + response.code() + " " + response.message());
            }
        }
    }

    /**
     * Benennt eine Datei im selben Ordner per WebDAV-MOVE um; ein vorhandenes Ziel wird ersetzt
     * ({@code Overwrite: T}). Der Server führt das in einem Zug aus – deshalb kann das Ziel dabei nie
     * halb geschrieben zurückbleiben.
     */
    public void move(String baseUrl, String user, String password, String folder,
                     String fromName, String toName) throws IOException {
        Request request = new Request.Builder()
                .url(buildUrl(baseUrl, user, folder, fromName))
                .header("Authorization", Credentials.basic(user, password))
                .header("Destination", buildUrl(baseUrl, user, folder, toName))
                .header("Overwrite", "T")
                .method("MOVE", null)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " " + response.message());
            }
        }
    }

    /** Löscht eine Datei per WebDAV-DELETE; ein 404 (gibt es nicht mehr) gilt als Erfolg. */
    public void delete(String baseUrl, String user, String password, String folder, String fileName)
            throws IOException {
        Request request = new Request.Builder()
                .url(buildUrl(baseUrl, user, folder, fileName))
                .header("Authorization", Credentials.basic(user, password))
                .delete()
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() && response.code() != 404) {
                throw new IOException("HTTP " + response.code() + " " + response.message());
            }
        }
    }

    /** Listet die Dateinamen mit der angegebenen Endung (ohne Punkt, z. B. "csv" oder "kmy"). */
    public List<String> listFiles(String baseUrl, String user, String password, String folder,
                                  String ext) throws IOException {
        String url = buildFolderUrl(baseUrl, user, folder);
        String body = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\">"
                + "<d:prop><d:resourcetype/></d:prop></d:propfind>";
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", Credentials.basic(user, password))
                .header("Depth", "1")
                .method("PROPFIND", RequestBody.create(body, XML))
                .build();

        try (Response response = client.newCall(request).execute()) {
            ResponseBody rb = response.body();
            String xml = rb == null ? "" : rb.string();
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " " + response.message());
            }
            return parseNames(xml, ext);
        }
    }

    /** Listet die Unterordner-Namen im angegebenen Ordner (ohne Dateien, ohne den Ordner selbst). */
    public List<String> listFolders(String baseUrl, String user, String password, String folder)
            throws IOException {
        String url = buildFolderUrl(baseUrl, user, folder);
        String body = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\">"
                + "<d:prop><d:resourcetype/></d:prop></d:propfind>";
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", Credentials.basic(user, password))
                .header("Depth", "1")
                .method("PROPFIND", RequestBody.create(body, XML))
                .build();

        try (Response response = client.newCall(request).execute()) {
            ResponseBody rb = response.body();
            String xml = rb == null ? "" : rb.string();
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " " + response.message());
            }
            return parseFolders(xml, pathOf(url));
        }
    }

    /** Lädt den Textinhalt einer Datei aus dem Ordner herunter. */
    public String downloadText(String baseUrl, String user, String password, String folder,
                               String fileName) throws IOException {
        String url = buildUrl(baseUrl, user, folder, fileName);
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", Credentials.basic(user, password))
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody rb = response.body();
            String content = rb == null ? "" : rb.string();
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " " + response.message());
            }
            return content;
        }
    }

    /** Lädt die Rohbytes einer Datei aus dem Ordner herunter (z. B. eine gepackte .kmy). */
    public byte[] downloadBytes(String baseUrl, String user, String password, String folder,
                                String fileName) throws IOException {
        return downloadBytes(baseUrl, user, password, folder, fileName, null);
    }

    /**
     * Wie oben, meldet aber die gelesenen Bytes. Statt {@code body().bytes()} (liest alles in einem Rutsch,
     * ohne jede Beobachtbarkeit) wird der Datenstrom in Blöcken gelesen. Gesamtgröße aus
     * {@code Content-Length}; liefert der Server keine (chunked, {@code -1}), wird {@code total = -1}
     * gemeldet – die Anzeige überspringt die Phase dann einfach.
     */
    public byte[] downloadBytes(String baseUrl, String user, String password, String folder,
                                String fileName, de.spahr.ausgaben.util.ProgressListener listener)
            throws IOException {
        String url = buildUrl(baseUrl, user, folder, fileName);
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", Credentials.basic(user, password))
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody rb = response.body();
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " " + response.message());
            }
            if (rb == null) {
                return new byte[0];
            }
            long total = rb.contentLength();
            try (java.io.InputStream in = rb.byteStream()) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(
                        total > 0 ? (int) Math.min(total, Integer.MAX_VALUE) : 32768);
                byte[] buf = new byte[8192];
                long read = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    read += n;
                    if (listener != null) {
                        listener.onProgress(read, total);
                    }
                }
                return bos.toByteArray();
            }
        }
    }

    /** Extrahiert aus der Multistatus-Antwort die Dateinamen mit der Endung {@code ext} (ohne Collections). */
    private List<String> parseNames(String xml, String ext) throws IOException {
        String suffix = "." + ext.toLowerCase();
        List<String> names = new ArrayList<>();
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(new StringReader(xml));
            int event = parser.getEventType();
            String currentHref = null;
            boolean isCollection = false;
            while (event != XmlPullParser.END_DOCUMENT) {
                String name = parser.getName();
                if (event == XmlPullParser.START_TAG && name != null) {
                    String local = localName(name);
                    if (local.equals("response")) {
                        currentHref = null;
                        isCollection = false;
                    } else if (local.equals("href")) {
                        currentHref = parser.nextText();
                    } else if (local.equals("collection")) {
                        isCollection = true;
                    }
                } else if (event == XmlPullParser.END_TAG && "response".equals(localName(name))) {
                    if (currentHref != null && !isCollection) {
                        String fileName = lastSegment(currentHref);
                        if (fileName.toLowerCase().endsWith(suffix)) {
                            names.add(fileName);
                        }
                    }
                }
                event = parser.next();
            }
        } catch (XmlPullParserException e) {
            throw new IOException("Antwort konnte nicht gelesen werden", e);
        }
        return names;
    }

    /** Extrahiert die Unterordner-Namen (Collections) aus der Multistatus-Antwort, ohne den Ordner selbst. */
    private List<String> parseFolders(String xml, String selfPath) throws IOException {
        List<String> names = new ArrayList<>();
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(new StringReader(xml));
            int event = parser.getEventType();
            String currentHref = null;
            boolean isCollection = false;
            while (event != XmlPullParser.END_DOCUMENT) {
                String name = parser.getName();
                if (event == XmlPullParser.START_TAG && name != null) {
                    String local = localName(name);
                    if (local.equals("response")) {
                        currentHref = null;
                        isCollection = false;
                    } else if (local.equals("href")) {
                        currentHref = parser.nextText();
                    } else if (local.equals("collection")) {
                        isCollection = true;
                    }
                } else if (event == XmlPullParser.END_TAG && "response".equals(localName(name))) {
                    if (currentHref != null && isCollection) {
                        // Den Ordner selbst (gleicher Pfad wie die Anfrage) auslassen.
                        if (!stripSlash(decode(hrefPath(currentHref))).equals(stripSlash(selfPath))) {
                            names.add(lastSegment(currentHref));
                        }
                    }
                }
                event = parser.next();
            }
        } catch (XmlPullParserException e) {
            throw new IOException("Antwort konnte nicht gelesen werden", e);
        }
        return names;
    }

    /** Pfad-Anteil einer (evtl. absoluten) URL bzw. eines href (host wird entfernt). */
    private String pathOf(String urlOrHref) {
        String s = urlOrHref;
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            int slash = s.indexOf('/', scheme + 3);
            s = slash < 0 ? "" : s.substring(slash);
        }
        return s;
    }

    private String hrefPath(String href) {
        return pathOf(href);
    }

    private String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private String stripSlash(String s) {
        String r = s == null ? "" : s;
        while (r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }

    private String localName(String qName) {
        if (qName == null) {
            return "";
        }
        int i = qName.indexOf(':');
        return (i >= 0 ? qName.substring(i + 1) : qName).toLowerCase();
    }

    private String lastSegment(String href) {
        String h = href;
        while (h.endsWith("/")) {
            h = h.substring(0, h.length() - 1);
        }
        int slash = h.lastIndexOf('/');
        String seg = slash >= 0 ? h.substring(slash + 1) : h;
        try {
            return URLDecoder.decode(seg, "UTF-8");
        } catch (Exception e) {
            return seg;
        }
    }

    /**
     * WebDAV-Wurzel: bei Nextcloud {@code <base>/remote.php/dav/files/<user>}, bei generischem WebDAV die
     * eingetragene Basis-URL selbst (der Nutzer gibt dort die vollständige DAV-Wurzel an).
     */
    private String rootUrl(String baseUrl, String user) {
        String base = baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (nextcloudLayout) {
            return base + "/remote.php/dav/files/" + encodePath(user);
        }
        return base;
    }

    /** Hängt die (bereinigten) Ordner-Segmente an den Builder an. */
    private void appendFolder(StringBuilder sb, String folder) {
        String cleanFolder = folder == null ? "" : folder.trim();
        while (cleanFolder.startsWith("/")) {
            cleanFolder = cleanFolder.substring(1);
        }
        while (cleanFolder.endsWith("/")) {
            cleanFolder = cleanFolder.substring(0, cleanFolder.length() - 1);
        }
        if (!cleanFolder.isEmpty()) {
            for (String part : cleanFolder.split("/")) {
                if (!part.isEmpty()) {
                    sb.append("/").append(encodePath(part));
                }
            }
        }
    }

    private String buildFolderUrl(String baseUrl, String user, String folder) {
        StringBuilder sb = new StringBuilder(rootUrl(baseUrl, user));
        appendFolder(sb, folder);
        sb.append("/");
        return sb.toString();
    }

    private String buildUrl(String baseUrl, String user, String folder, String fileName) {
        StringBuilder sb = new StringBuilder(rootUrl(baseUrl, user));
        appendFolder(sb, folder);
        sb.append("/").append(encodePath(fileName));
        return sb.toString();
    }

    /** URL-kodiert einen Pfadabschnitt, lässt aber Slashes unberührt (werden separat gesetzt). */
    private String encodePath(String segment) {
        StringBuilder out = new StringBuilder();
        for (byte b : segment.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append(String.format("%02X", c));
            }
        }
        return out.toString();
    }
}
