package de.spahr.ausgaben.net.smb;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import de.spahr.ausgaben.net.Diagnostics;
import de.spahr.ausgaben.net.Diagnostics.Step;
import de.spahr.ausgaben.net.RemoteSelfTest;
import de.spahr.ausgaben.settings.SettingsStore;

/**
 * Prüft eine SMB-Verbindung Schritt für Schritt und schreibt einen Bericht, den ein Nutzer
 * unverändert weiterschicken kann.
 *
 * <p>Hintergrund: Eine Meldung wie „Server nicht erreichbar" sagt aus der Ferne nichts – der Nutzer
 * hat oft gerade eben noch Freigaben gesehen. Hier steht deshalb je Schritt, <b>was</b> gemacht wurde,
 * <b>ob</b> es geklappt hat und – im Fehlerfall – der <b>rohe Statuscode</b>. Die ganze Kette läuft in
 * <b>einer</b> Anmeldung, so wie es auch der Normalbetrieb tun sollte.</p>
 *
 * <p>Der Bericht enthält <b>nie</b> das Passwort und den Benutzernamen nur als „gesetzt"/„leer" –
 * er ist zum Verschicken gedacht.</p>
 */
public final class SmbDiagnostics {

    private SmbDiagnostics() {
    }

    /**
     * Bequemlichkeit für einen Dateipfad ({@code Ordner/Datei.kmy}); zerlegt ihn und prüft beides.
     */
    public static List<Step> run(String url, String user, String password, String kmyPath) {
        return run(url, user, password, folderOf(kmyPath), fileOf(kmyPath));
    }

    /**
     * Läuft die Kette Verbinden → Anmelden → Freigaben → Freigabe öffnen → Ordner lesen →
     * <b>Schreiben → Umbenennen → Aufräumen</b> → Datei durch und bricht beim ersten Fehler ab, der
     * alles Weitere sinnlos macht.
     *
     * @param url    {@code smb://Host[:Port]/Freigabe[/Basis]} wie in den Einstellungen
     * @param folder Zielordner relativ zur Freigabe (leer = die Freigabe selbst)
     * @param file   zu prüfende Datei in diesem Ordner (leer = nur den Ordner prüfen)
     */
    public static List<Step> run(String url, String user, String password, String folder,
                                 String file) {
        List<Step> steps = new ArrayList<>();
        String[] parts = SettingsStore.parseSmb(url);
        String host = parts[0];
        String share = parts[1];
        String base = parts[2];
        int port = parts[3].isEmpty() ? 0 : Integer.parseInt(parts[3]);
        String who = "Benutzer " + (user == null || user.trim().isEmpty()
                ? "leer (Gast/anonym)" : "gesetzt");
        if (host.isEmpty() || share.isEmpty()) {
            // Ohne Host oder Freigabe gibt es nichts zu prüfen – das ist die ganze Auskunft.
            steps.add(new Step("Adresse", false, (host.isEmpty() ? "kein Host" : host)
                    + ", " + (share.isEmpty() ? "keine Freigabe" : "Freigabe „" + share + "\"")
                    + " – erwartet wird smb://Host/Freigabe", -1));
            return steps;
        }
        steps.add(new Step("Adresse " + host + ":" + (port > 0 ? port : 445)
                + ", Freigabe „" + share + "\"" + (base.isEmpty() ? "" : ", Basis „" + base + "\"")
                + ", " + who, true, "", -1));

        long t0 = System.currentTimeMillis();
        SmbSessions.Link link;
        try {
            link = SmbSessions.open(host, port, false);
        } catch (Exception e) {
            steps.add(new Step("Verbinden", false, reason(e), System.currentTimeMillis() - t0));
            return steps;
        }
        try {
            StringBuilder how = new StringBuilder();
            if (link.usedPort != port) {
                how.append("über Port ").append(link.usedPort > 0 ? link.usedPort : 445)
                        .append(" statt ").append(port);
            }
            if (link.plainFallback) {
                how.append(how.length() > 0 ? ", " : "").append("erst ohne Verschlüsselungs-/DFS-Zusage");
            }
            steps.add(new Step("Verbinden", true, how.toString(), System.currentTimeMillis() - t0));
            steps.add(new Step("Aushandeln", true, negotiated(link), -1));

            t0 = System.currentTimeMillis();
            Session session;
            try {
                session = SmbSessions.authenticate(link.connection, user, password);
            } catch (Exception e) {
                steps.add(new Step("Anmelden", false, reason(e), System.currentTimeMillis() - t0));
                return steps;
            }
            steps.add(new Step("Anmelden", true,
                    (session.isGuest() ? "als Gast" : "als Benutzer") + ", " + encryption(session),
                    System.currentTimeMillis() - t0));

            t0 = System.currentTimeMillis();
            try {
                List<String> shares = SmbShares.listOn(session, host);
                steps.add(new Step("Freigaben lesen (IPC$)", true, shares.size() + " gefunden"
                        + (shares.contains(share) ? "" : ", „" + share + "\" ist nicht darunter"),
                        System.currentTimeMillis() - t0));
            } catch (Exception e) {
                // Kein Abbruch: manche Server verbieten nur die Auskunft, nicht den Zugriff.
                steps.add(new Step("Freigaben lesen (IPC$)", false, reason(e),
                        System.currentTimeMillis() - t0));
            }

            t0 = System.currentTimeMillis();
            DiskShare disk;
            try {
                disk = (DiskShare) session.connectShare(share);
            } catch (Exception e) {
                steps.add(new Step("Freigabe „" + share + "\" öffnen", false, reason(e),
                        System.currentTimeMillis() - t0));
                return steps;
            }
            try {
                steps.add(new Step("Freigabe „" + share + "\" öffnen", true, "",
                        System.currentTimeMillis() - t0));
                String dir = join(base, folder);
                t0 = System.currentTimeMillis();
                try {
                    int count = 0;
                    for (Object ignored : disk.list(dir)) {
                        count++;
                    }
                    steps.add(new Step("Ordner „" + (dir.isEmpty() ? "\\" : dir) + "\" lesen", true,
                            count + " Einträge", System.currentTimeMillis() - t0));
                } catch (Exception e) {
                    steps.add(new Step("Ordner „" + (dir.isEmpty() ? "\\" : dir) + "\" lesen", false,
                            reason(e), System.currentTimeMillis() - t0));
                    return steps;
                }
                writeRenameCleanup(disk, dir, steps);
                if (!file.isEmpty()) {
                    t0 = System.currentTimeMillis();
                    boolean exists;
                    try {
                        exists = disk.fileExists(join(dir, file));
                    } catch (Exception e) {
                        steps.add(new Step("Datei „" + file + "\" prüfen", false, reason(e),
                                System.currentTimeMillis() - t0));
                        return steps;
                    }
                    steps.add(new Step("Datei „" + file + "\" prüfen", exists,
                            exists ? "vorhanden" : "nicht gefunden", System.currentTimeMillis() - t0));
                    if (exists) {
                        steps.add(fileWritableStep(disk, join(dir, file), file));
                    }
                }
            } finally {
                try {
                    disk.close();
                } catch (Exception ignored) {
                    // Aufräumen; das Ergebnis steht schon fest.
                }
            }
        } finally {
            link.close();
        }
        return steps;
    }

    /**
     * Die drei Rechte, die ein Export wirklich braucht: <b>schreiben</b>, <b>umbenennen</b>,
     * <b>löschen</b>. Ein nur lesbares Verzeichnis fällt sonst erst beim Rückschreiben auf – nach dem
     * Herunterladen, Bearbeiten und Zusammenführen.
     *
     * <p>Warum Umbenennen einen eigenen Schritt bekommt: Der Export schreibt die Datei erst vollständig
     * unter einem Zwischennamen und hängt sie dann ein (siehe {@code SafeReplace}). SMB benennt „durch
     * Löschen des alten Namens" um und verlangt dafür <b>DELETE auf der Quelle</b> – ein anderes Recht
     * als das Anlegen. Ein Ordner, in dem Anlegen und Löschen geht, das Umbenennen aber nicht, kam
     * früher hier sauber durch und scheiterte erst beim ersten echten Übertragen.</p>
     *
     * <p>Geprüft wird mit einer winzigen Datei, die sofort wieder verschwindet; ihr Name kommt aus
     * {@link RemoteSelfTest#probeName(String)}, damit ein Überbleibsel zuzuordnen ist.</p>
     */
    private static void writeRenameCleanup(DiskShare disk, String dir, List<Step> steps) {
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
        String from = join(dir, RemoteSelfTest.probeName(stamp));
        String to = join(dir, RemoteSelfTest.renamedProbeName(stamp));

        long t0 = System.currentTimeMillis();
        try {
            disk.openFile(from, EnumSet.of(AccessMask.GENERIC_WRITE),
                    null, SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OVERWRITE_IF, null).close();
        } catch (Exception e) {
            steps.add(new Step("Schreiben im Ordner", false,
                    reason(e) + " – die App braucht ein beschreibbares Verzeichnis",
                    System.currentTimeMillis() - t0));
            return;
        }
        steps.add(new Step("Schreiben im Ordner", true, "", System.currentTimeMillis() - t0));

        t0 = System.currentTimeMillis();
        String liegengeblieben = from;
        try {
            try (com.hierynomus.smbj.share.File f = disk.openFile(from,
                    EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ), null,
                    SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)) {
                f.rename(to, true);
            }
            liegengeblieben = to;
            steps.add(new Step("Umbenennen im Ordner", true, "", System.currentTimeMillis() - t0));
        } catch (Exception e) {
            steps.add(new Step("Umbenennen im Ordner", false,
                    reason(e) + " – " + Diagnostics.UMBENENNEN_NOETIG,
                    System.currentTimeMillis() - t0));
        }

        t0 = System.currentTimeMillis();
        try {
            disk.rm(liegengeblieben);
            steps.add(new Step("Aufräumen im Ordner", true, "", System.currentTimeMillis() - t0));
        } catch (Exception e) {
            steps.add(new Step("Aufräumen im Ordner", false,
                    reason(e) + " – bitte " + liegengeblieben + " von Hand löschen",
                    System.currentTimeMillis() - t0));
        }
    }

    /**
     * Und darf sie die .kmy selbst überschreiben? Ein schreibgeschützter Ordner ist der eine Fall, eine
     * schreibgeschützte Datei im offenen Ordner der andere. Die Datei wird nur zum Schreiben
     * <b>geöffnet</b> und sofort wieder geschlossen – ihr Inhalt bleibt unberührt.
     */
    private static Step fileWritableStep(DiskShare disk, String path, String name) {
        long t0 = System.currentTimeMillis();
        try {
            disk.openFile(path, EnumSet.of(AccessMask.GENERIC_WRITE),
                    null, SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN, null).close();
            return new Step("Datei „" + name + "\" beschreibbar", true, "",
                    System.currentTimeMillis() - t0);
        } catch (Exception e) {
            return new Step("Datei „" + name + "\" beschreibbar", false,
                    reason(e) + " – Rückschreiben wäre nicht möglich", System.currentTimeMillis() - t0);
        }
    }

    /** Ausgehandelter Dialekt samt Signierung/Verschlüsselung – die Kernauskunft des Berichts. */
    private static String negotiated(SmbSessions.Link link) {
        try {
            com.hierynomus.smbj.connection.ConnectionContext ctx = link.connection.getConnectionContext();
            return link.connection.getNegotiatedProtocol().getDialect()
                    + ", Signierung " + (ctx.isServerRequiresSigning() ? "verlangt" : "optional")
                    + ", Verschlüsselung " + (ctx.supportsEncryption()
                            ? "möglich (" + ctx.getCipherId() + ")" : "nicht ausgehandelt");
        } catch (Exception e) {
            return "unbekannt";
        }
    }

    /** Ob der Server für diese Sitzung Verschlüsselung <b>verlangt</b> (nur dann verschlüsselt smbj). */
    private static String encryption(Session session) {
        try {
            return session.shouldEncryptData() ? "verschlüsselt" : "unverschlüsselt";
        } catch (Exception e) {
            return "Verschlüsselungsstatus unbekannt";
        }
    }

    /** Die Kopfzeile des Berichts – sie sagt dem Empfänger, worum es überhaupt geht. */
    public static final String TITLE = "SMB-Diagnose (KMySync)";

    /** Kompletter Bericht als Text – genau das, was der Nutzer kopiert und schickt. */
    public static String report(List<Step> steps) {
        return Diagnostics.report(TITLE, steps);
    }

    /** Erster Fehlerschritt oder {@code null}, wenn alles geklappt hat. */
    public static Step firstFailure(List<Step> steps) {
        return Diagnostics.firstFailure(steps);
    }

    /**
     * Rohtext einer Ausnahme, einzeilig – der Statuscode ist hier das Wertvolle. Bei SMB kommt er aus
     * {@link SmbErrors#textOf}, das hinter einem nackten Statuscode die Erklärung mitliefert.
     */
    private static String reason(Throwable e) {
        String raw = Diagnostics.shorten(SmbErrors.textOf(e));
        return raw.isEmpty() ? e.getClass().getSimpleName() : raw;
    }

    private static String folderOf(String path) {
        String p = path == null ? "" : path.trim().replace('\\', '/');
        int slash = p.lastIndexOf('/');
        return slash < 0 ? "" : p.substring(0, slash);
    }

    private static String fileOf(String path) {
        String p = path == null ? "" : path.trim().replace('\\', '/');
        int slash = p.lastIndexOf('/');
        return slash < 0 ? p : p.substring(slash + 1);
    }

    /** Wie {@code SmbStorage.joinPath}, hier nur für die Anzeige/Prüfung im Bericht. */
    private static String join(String a, String b) {
        String left = strip(a);
        String right = strip(b);
        if (left.isEmpty()) {
            return right;
        }
        return right.isEmpty() ? left : left + "\\" + right;
    }

    private static String strip(String p) {
        if (p == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String seg : p.trim().replace('/', '\\').split("\\\\")) {
            if (seg.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append('\\');
            }
            out.append(seg);
        }
        return out.toString();
    }
}
