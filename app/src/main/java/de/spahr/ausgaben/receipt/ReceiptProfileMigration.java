package de.spahr.ausgaben.receipt;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.spahr.ausgaben.db.AppDatabase;
import de.spahr.ausgaben.settings.ProfileManager;

/**
 * Ordnet den Belegbestand einmalig den Profilen zu.
 *
 * <p>Bis 2.1 lagen die Dateien aller Profile flach in {@code belege/}, und die Merklisten standen
 * unter blanken Schlüsseln. Beides gehört seit 2.2 je Profil getrennt (siehe {@link Receipts}) – der
 * vorhandene Bestand muss dorthin gebracht werden, und zwar <b>bevor</b> ihn jemand liest.</p>
 *
 * <h2>Diese Klasse löscht nie</h2>
 *
 * <p>Sie urteilt nicht über Herrenlosigkeit. Das ist die Aufgabe von {@link ReceiptGc}, und der hat
 * dafür zwei Sicherungen, die hier beide fehlten: einen Papierkorb auf dem Server und den Riegel
 * „keine Buchungen, kein Aufräumen". Was sich keinem Profil zuordnen lässt, wandert deshalb zum
 * aktiven Profil und wird dort regulär beurteilt.</p>
 */
public final class ReceiptProfileMigration {

    private ReceiptProfileMigration() {
    }

    /**
     * Bringt den Altbestand ins Profil-Layout – und ist ein No-op, sobald es keinen mehr gibt.
     *
     * <p><b>Synchron, nicht nebenläufig.</b> Liefe sie auf einem eigenen Faden, könnte der
     * Aufräumlauf sie überholen: Er sähe einen halb geleerten flachen Ordner, hielte den Rest für
     * verwaist und schöbe genau die Dateien in den Papierkorb, die gerade zugeordnet werden. Das ist
     * derselbe Fehler, den der ganze Umbau beseitigen soll – nur einmalig und besonders bitter.</p>
     *
     * <p><b>Kein Erledigt-Schalter</b>, sondern die Zustandsprüfung {@link #hasLegacyState}: Eine
     * eingespielte Sicherung aus einer älteren Fassung schreibt die unpräfixierten Merklisten wieder
     * hin, ohne dass der Prozess neu startet. Ein Flag stünde dann auf „fertig", während der Altstand
     * daneben liegt.</p>
     */
    public static synchronized void ensureDone(Context context) {
        Context app = context.getApplicationContext();
        if (!hasLegacyState(app)) {
            return;
        }
        try {
            lauf(app);
        } catch (Exception e) {
            // Beiwerk im Startpfad: Ein Fehlschlag darf die App nicht am Starten hindern. Der
            // Altbestand bleibt liegen, hasLegacyState meldet weiter true, der nächste Start
            // versucht es erneut.
            android.util.Log.w("ReceiptMigration", "Belege konnten nicht zugeordnet werden", e);
        }
    }

    /** Liegt etwas direkt in {@code belege/}, oder stehen unpräfixierte Merklisten in den Prefs? */
    static boolean hasLegacyState(Context app) {
        File[] files = Receipts.root(app).listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    return true;
                }
            }
        }
        SharedPreferences prefs = prefs(app);
        return prefs.contains(Receipts.KEY_PENDING) || prefs.contains(Receipts.KEY_MOVES)
                || prefs.contains(Receipts.KEY_FOLDER_MOVES);
    }

    /**
     * Der eigentliche Lauf.
     *
     * <p>Die Reihenfolge ist Absicht: erst die Merklisten aufteilen, dann die Dateien bewegen,
     * <b>zuletzt</b> die Altschlüssel löschen. Ein Abbruch dazwischen bedeutet damit eine
     * Wiederholung, keinen Verlust – jeder Schritt verträgt es, zweimal zu laufen.</p>
     *
     * <p><b>{@code commit()} statt {@code apply()}</b>, und Lint meldet das zu Recht als
     * ungewöhnlich: Es blockiert. Genau darum geht es. Dieser Lauf steht im Startpfad vor allem, was
     * Belege liest; stünden die neuen Schlüssel noch in einer Warteschlange, sähe der gleich darauf
     * startende Aufräumlauf leere Merklisten und hielte die Dateien für herrenlos. Der Bedienfaden
     * ist dabei nicht in Gefahr: Es geht um zwei kleine Schlüsselmengen, einmal je Installation.</p>
     */
    @android.annotation.SuppressLint("ApplySharedPref")
    private static void lauf(Context app) {
        ProfileManager pm = new ProfileManager(app);
        String aktiv = pm.getActiveProfileId();
        Map<String, Set<String>> basenJeProfil = basesByProfile(app, pm, aktiv);

        File wurzel = Receipts.root(app);
        List<String> flach = new ArrayList<>();
        File[] files = wurzel.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    flach.add(f.getName());
                }
            }
        }
        Map<String, List<String>> besitzer = owners(flach, basenJeProfil);

        SharedPreferences prefs = prefs(app);
        SharedPreferences.Editor editor = prefs.edit();
        schreibe(editor, splitPending(prefs.getStringSet(Receipts.KEY_PENDING, null), besitzer, aktiv),
                Receipts.KEY_PENDING);
        schreibe(editor, splitMoves(prefs.getStringSet(Receipts.KEY_MOVES, null), besitzer, aktiv),
                Receipts.KEY_MOVES);
        schreibe(editor, splitMoves(prefs.getStringSet(Receipts.KEY_FOLDER_MOVES, null), besitzer, aktiv),
                Receipts.KEY_FOLDER_MOVES);
        editor.commit();

        for (String name : flach) {
            verschiebe(wurzel, name, besitzer.get(name), aktiv);
        }

        prefs.edit()
                .remove(Receipts.KEY_PENDING)
                .remove(Receipts.KEY_MOVES)
                .remove(Receipts.KEY_FOLDER_MOVES)
                .commit();
    }

    /**
     * Bringt eine Datei dorthin, wo sie hingehört.
     *
     * <table>
     *   <tr><td>genau ein Profil</td><td>umziehen ({@code renameTo}, derselbe Datenträger)</td></tr>
     *   <tr><td>mehrere Profile</td><td><b>kopieren</b> – ein Umzug ließe alle bis auf eines leer
     *       ausgehen</td></tr>
     *   <tr><td>niemand</td><td>zum aktiven Profil; dort urteilt der reguläre Aufräumlauf</td></tr>
     * </table>
     */
    private static void verschiebe(File wurzel, String name, List<String> ziele, String aktiv) {
        File quelle = new File(wurzel, name);
        if (!quelle.isFile()) {
            return;
        }
        List<String> profile = ziele == null || ziele.isEmpty()
                ? java.util.Collections.singletonList(aktiv) : ziele;
        for (int i = 0; i < profile.size(); i++) {
            File ordner = new File(wurzel, Receipts.folderFor(profile.get(i)));
            //noinspection ResultOfMethodCallIgnored
            ordner.mkdirs();
            File ziel = new File(ordner, name);
            if (i == profile.size() - 1) {
                if (!quelle.renameTo(ziel)) {
                    kopiere(quelle, ziel);
                    //noinspection ResultOfMethodCallIgnored
                    quelle.delete();
                }
            } else {
                kopiere(quelle, ziel);
            }
        }
    }

    private static void kopiere(File von, File nach) {
        try (FileInputStream in = new FileInputStream(von);
             FileOutputStream out = new FileOutputStream(nach)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (Exception e) {
            // Die Quelle bleibt liegen; der nächste Start versucht es erneut.
        }
    }

    private static void schreibe(SharedPreferences.Editor editor, Map<String, Set<String>> jeProfil,
                                 String baseKey) {
        for (Map.Entry<String, Set<String>> e : jeProfil.entrySet()) {
            if (!e.getValue().isEmpty()) {
                editor.putStringSet(Receipts.folderFor(e.getKey()) + "_" + baseKey, e.getValue());
            }
        }
    }

    // ---- Zuordnung ----

    /** Die Beleg-Basen jedes Profils, gelesen aus dessen eigener Datenbank. */
    private static Map<String, Set<String>> basesByProfile(Context app, ProfileManager pm, String aktiv) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (ProfileManager.Profile p : pm.getProfiles()) {
            out.put(p.id, p.id.equals(aktiv) ? basesOfActive(app) : basesOfDatabase(app, p.dbFileName));
        }
        return out;
    }

    /** Das aktive Profil über Room – die Datei ist ohnehin offen und migriert. */
    private static Set<String> basesOfActive(Context app) {
        try {
            AppDatabase db = AppDatabase.getInstance(app);
            Set<String> basen = ReceiptGc.basesOf(db.bookingDao().getReceiptNotes());
            basen.addAll(ReceiptGc.basesOf(db.securityDao().getReceiptNotes()));
            return basen;
        } catch (Exception e) {
            return new HashSet<>();
        }
    }

    /**
     * Die Beleg-Basen einer <b>nicht geöffneten</b> Profildatenbank, gelesen ohne Room.
     *
     * <p>Geöffnet wird mit {@code OPEN_READWRITE}, obwohl nur gelesen wird, und das ist Absicht: Ein
     * nicht aktives Profil kann einen ungeschriebenen WAL-Puffer haben, an den rein lesend niemand
     * herankommt. Frisch erfasste Buchungen fehlten dann in der Zuordnung, und deren Belege landeten
     * beim falschen Profil. {@code BackupStore.checkpointClosedDatabase} löst dasselbe Problem an
     * derselben Stelle.</p>
     *
     * <p>Jede Abfrage in ihrem eigenen {@code try}: {@code security_tx} gibt es in sehr alten
     * Schemata noch nicht, und daran soll die Zuordnung der Buchungsbelege nicht scheitern.</p>
     */
    private static Set<String> basesOfDatabase(Context app, String dbFileName) {
        Set<String> basen = new HashSet<>();
        File datei = app.getDatabasePath(dbFileName);
        if (datei == null || !datei.exists()) {
            return basen;
        }
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(datei.getPath(), null, SQLiteDatabase.OPEN_READWRITE);
            for (String tabelle : new String[]{"booking", "security_tx"}) {
                try (Cursor c = db.rawQuery("SELECT note FROM " + tabelle
                        + " WHERE note LIKE '%BELEG:%' OR note LIKE '%BELEG (PDF):%'", null)) {
                    List<String> notizen = new ArrayList<>();
                    while (c.moveToNext()) {
                        notizen.add(c.getString(0));
                    }
                    basen.addAll(ReceiptGc.basesOf(notizen));
                } catch (Exception keineTabelle) {
                    // alter Schemastand – die andere Tabelle zählt trotzdem
                }
            }
        } catch (Exception nichtLesbar) {
            // Dann gilt dieses Profil als „beansprucht nichts"; seine Dateien gehen ans aktive und
            // werden dort regulär beurteilt – mit Papierkorb.
        } finally {
            if (db != null) {
                try {
                    db.close();
                } catch (Exception ignored) {
                    // nichts zu retten
                }
            }
        }
        return basen;
    }

    /**
     * Zu jedem Dateinamen die Profile, die ihn über einen Beleg-Tag beanspruchen.
     *
     * <p>Rein und testbar: kein Android, keine Datei wird angefasst. Leere Liste = niemand
     * beansprucht sie.</p>
     */
    public static Map<String, List<String>> owners(Collection<String> fileNames,
                                                   Map<String, Set<String>> basesByProfile) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (fileNames == null) {
            return out;
        }
        for (String name : fileNames) {
            List<String> profile = new ArrayList<>();
            String basis = NoteReceipt.baseOf(name);
            if (basesByProfile != null) {
                for (Map.Entry<String, Set<String>> e : basesByProfile.entrySet()) {
                    if (e.getValue() != null && e.getValue().contains(basis)) {
                        profile.add(e.getKey());
                    }
                }
            }
            out.put(name, profile);
        }
        return out;
    }

    /**
     * Teilt die {@code pending}-Einträge ({@code <jahr>|<datei>}) nach Besitzer auf.
     *
     * <p>Ein Eintrag ohne Besitzer geht ans {@code fallback}-Profil: Ein offener Upload gehört
     * irgendwohin, und im ungünstigsten Fall lädt das falsche Profil eine Datei in einen Ordner, in
     * den sie ohnehin gehört.</p>
     */
    public static Map<String, Set<String>> splitPending(Set<String> entries,
                                                        Map<String, List<String>> owners,
                                                        String fallback) {
        return split(entries, owners, fallback, true);
    }

    /** Dasselbe für {@code moves}/{@code folder_moves}; unbrauchbare Einträge fallen weg. */
    public static Map<String, Set<String>> splitMoves(Set<String> entries,
                                                      Map<String, List<String>> owners,
                                                      String fallback) {
        return split(entries, owners, fallback, false);
    }

    private static Map<String, Set<String>> split(Set<String> entries,
                                                  Map<String, List<String>> owners,
                                                  String fallback, boolean pending) {
        Map<String, Set<String>> out = new HashMap<>();
        if (entries == null) {
            return out;
        }
        for (String entry : entries) {
            String datei;
            if (pending) {
                datei = Receipts.entryFile(entry);
            } else {
                String[] teile = Receipts.folderMoveParts(entry);
                if (teile == null) {
                    teile = Receipts.moveParts(entry);
                }
                datei = teile == null ? null : teile[2];
            }
            if (datei == null || datei.isEmpty()) {
                continue; // unbrauchbar – nicht mitschleppen
            }
            List<String> profile = owners == null ? null : owners.get(datei);
            if (profile == null || profile.isEmpty()) {
                profile = java.util.Collections.singletonList(fallback);
            }
            for (String p : profile) {
                Set<String> s = out.get(p);
                if (s == null) {
                    s = new HashSet<>();
                    out.put(p, s);
                }
                s.add(entry);
            }
        }
        return out;
    }

    private static SharedPreferences prefs(Context app) {
        return app.getSharedPreferences("receipts", Context.MODE_PRIVATE);
    }
}
