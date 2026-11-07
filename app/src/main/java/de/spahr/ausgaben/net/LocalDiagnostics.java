package de.spahr.ausgaben.net;

import android.content.Context;
import android.content.UriPermission;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

import de.spahr.ausgaben.net.Diagnostics.Step;

/**
 * „Verbindung testen" für den lokalen Ordner ({@link LocalStorage}): dieselbe Form wie die Berichte für
 * SMB und WebDAV, nur ohne Netz. Geprüft wird, was beim Export wirklich gebraucht wird – die Freigabe,
 * das Lesen, der Zielordner, die Datei und das Schreiben.
 */
public final class LocalDiagnostics {

    private LocalDiagnostics() {
    }

    public static String title() {
        return Diagnostics.t("Prüfung des lokalen Ordners", "Local folder check");
    }

    /**
     * @param folder Zielordner relativ zum freigegebenen Ordner; leer = dieser selbst
     * @param file   Datei darin, die es geben muss; leer = keine
     */
    public static List<Step> run(Context context, String treeUri, String folder, String file) {
        List<Step> steps = new ArrayList<>();
        if (treeUri == null || treeUri.trim().isEmpty()) {
            steps.add(new Step(Diagnostics.t("Ordner gewählt", "Folder chosen"), false,
                    Diagnostics.t("noch keiner – „Ordner wählen…“ antippen",
                            "none yet – tap \"Choose folder…\""), -1));
            return steps;
        }
        boolean granted = granted(context, treeUri.trim());
        steps.add(new Step(Diagnostics.t("Freigabe dauerhaft erteilt", "Access granted permanently"),
                granted, granted ? "" : Diagnostics.t("bitte den Ordner neu wählen",
                "please choose the folder again"), -1));
        if (!granted) {
            return steps;
        }
        LocalStorage storage;
        try {
            storage = new LocalStorage(context, treeUri);
        } catch (Exception e) {
            steps.add(new Step(Diagnostics.t("Ordner öffnen", "Open folder"), false, e.getMessage(), -1));
            return steps;
        }
        long t0 = System.currentTimeMillis();
        try {
            storage.testConnection();
            steps.add(new Step(Diagnostics.t("Ordner lesen", "Read folder"), true, "",
                    System.currentTimeMillis() - t0));
        } catch (Exception e) {
            steps.add(new Step(Diagnostics.t("Ordner lesen", "Read folder"), false, e.getMessage(),
                    System.currentTimeMillis() - t0));
            return steps;
        }
        String f = folder == null ? "" : folder.trim();
        if (!f.isEmpty()) {
            t0 = System.currentTimeMillis();
            try {
                storage.ensureFolder(f);
                steps.add(new Step(Diagnostics.t("Zielordner „", "Target folder \"") + f
                        + Diagnostics.t("“", "\""), true, "", System.currentTimeMillis() - t0));
            } catch (Exception e) {
                steps.add(new Step(Diagnostics.t("Zielordner „", "Target folder \"") + f
                        + Diagnostics.t("“", "\""), false, e.getMessage(), System.currentTimeMillis() - t0));
                return steps;
            }
        }
        if (file != null && !file.trim().isEmpty()) {
            t0 = System.currentTimeMillis();
            try {
                long size = storage.fileSize(f, file.trim());
                boolean da = size >= 0;
                steps.add(new Step(Diagnostics.t("Datei „", "File \"") + file.trim()
                        + Diagnostics.t("“ vorhanden", "\" present"), da,
                        da ? size + " Bytes" : Diagnostics.t("nicht gefunden", "not found"),
                        System.currentTimeMillis() - t0));
            } catch (Exception e) {
                steps.add(new Step(Diagnostics.t("Datei lesen", "Read file"), false, e.getMessage(),
                        System.currentTimeMillis() - t0));
            }
        }
        // Schreiben: eine kleine Datei anlegen und wieder löschen – genau das tut der Export.
        String probe = ".kmysync-probe-" + System.currentTimeMillis();
        t0 = System.currentTimeMillis();
        try {
            storage.uploadText(f, probe, "probe");
            storage.delete(f, probe);
            steps.add(new Step(Diagnostics.t("Schreiben und Löschen", "Write and delete"), true, "",
                    System.currentTimeMillis() - t0));
        } catch (Exception e) {
            steps.add(new Step(Diagnostics.t("Schreiben und Löschen", "Write and delete"), false,
                    e.getMessage(), System.currentTimeMillis() - t0));
        }
        return steps;
    }

    private static boolean granted(Context context, String treeUri) {
        Uri uri = Uri.parse(treeUri);
        for (UriPermission p : context.getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(uri) && p.isReadPermission() && p.isWritePermission()) {
                return true;
            }
        }
        return false;
    }
}
