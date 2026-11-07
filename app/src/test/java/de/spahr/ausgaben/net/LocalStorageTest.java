package de.spahr.ausgaben.net;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Der lokale Ordner als Sync-Ziel – gegen einen Dateianbieter, der ein echtes Verzeichnis zeigt, so
 * wie der Speicher des Geräts es tut. Geprüft wird vor allem, was das sichere Ersetzen der
 * KMyMoney-Datei braucht ({@link SafeReplace}).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LocalStorageTest {

    static final String AUTHORITY = "de.spahr.ausgaben.test.documents";
    static File root;

    /** Ein Anbieter wie der des Gerätespeichers: Dokumentkennung = Pfad relativ zur Wurzel. */
    public static class DirProvider extends DocumentsProvider {
        private static final String[] COLS = {Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
                Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE, Document.COLUMN_FLAGS};

        // Kennungen wie beim Gerätespeicher: „root" ist die Wurzel, darunter „root/Pfad".
        private static File fileOf(String id) {
            return "root".equals(id) ? root : new File(root, id.substring("root/".length()));
        }

        private static String idOf(File f) {
            String r = root.getAbsolutePath();
            String p = f.getAbsolutePath();
            return p.equals(r) ? "root" : "root/" + p.substring(r.length() + 1);
        }

        private static void row(MatrixCursor c, File f) {
            c.addRow(new Object[]{idOf(f), f.getName(),
                    f.isDirectory() ? Document.MIME_TYPE_DIR : "application/octet-stream",
                    f.lastModified(), f.length(),
                    Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE
                            | Document.FLAG_SUPPORTS_RENAME | Document.FLAG_DIR_SUPPORTS_CREATE});
        }

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Cursor queryRoots(String[] projection) {
            return new MatrixCursor(new String[]{DocumentsContract.Root.COLUMN_ROOT_ID});
        }

        @Override
        public Cursor queryDocument(String documentId, String[] projection) {
            MatrixCursor c = new MatrixCursor(COLS);
            row(c, fileOf(documentId));
            return c;
        }

        @Override
        public Cursor queryChildDocuments(String parentDocumentId, String[] projection,
                                          String sortOrder) {
            MatrixCursor c = new MatrixCursor(COLS);
            File[] list = fileOf(parentDocumentId).listFiles();
            if (list != null) {
                for (File f : list) {
                    row(c, f);
                }
            }
            return c;
        }

        @Override
        public ParcelFileDescriptor openDocument(String documentId, String mode,
                                                 CancellationSignal signal) throws FileNotFoundException {
            File f = fileOf(documentId);
            int m = ParcelFileDescriptor.parseMode(mode);
            return ParcelFileDescriptor.open(f, m);
        }

        @Override
        public String createDocument(String parentDocumentId, String mimeType, String displayName)
                throws FileNotFoundException {
            File f = new File(fileOf(parentDocumentId), displayName);
            try {
                if (Document.MIME_TYPE_DIR.equals(mimeType) ? !f.mkdir() : !f.createNewFile()) {
                    throw new FileNotFoundException("exists " + displayName);
                }
            } catch (IOException e) {
                throw new FileNotFoundException(e.getMessage());
            }
            return idOf(f);
        }

        @Override
        public String renameDocument(String documentId, String displayName)
                throws FileNotFoundException {
            File f = fileOf(documentId);
            File to = new File(f.getParentFile(), displayName);
            if (to.exists() || !f.renameTo(to)) {
                throw new FileNotFoundException("rename " + displayName);
            }
            return idOf(to);
        }

        @Override
        public void deleteDocument(String documentId) {
            fileOf(documentId).delete();
        }

        @Override
        public boolean isChildDocument(String parentDocumentId, String documentId) {
            return true;
        }
    }

    private Context ctx;
    private LocalStorage storage;

    @Before
    public void setUp() throws Exception {
        ctx = ApplicationProvider.getApplicationContext();
        root = Files.createTempDirectory("lokal").toFile();
        ProviderInfo info = new ProviderInfo();
        info.authority = AUTHORITY;
        info.exported = true;
        info.grantUriPermissions = true;
        info.readPermission = android.Manifest.permission.MANAGE_DOCUMENTS;
        info.writePermission = android.Manifest.permission.MANAGE_DOCUMENTS;
        Robolectric.buildContentProvider(DirProvider.class).create(info);
        Uri tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root");
        storage = new LocalStorage(ctx, tree.toString());
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void schreibenLesenUndAuflisten() throws Exception {
        storage.uploadText("KMyMoney/Belege", "a.txt", "hallo");
        storage.uploadBytes("KMyMoney", "test.kmy", bytes("<KMY/>"));
        assertEquals("hallo", storage.downloadText("KMyMoney/Belege", "a.txt"));
        assertArrayEquals(bytes("<KMY/>"), storage.downloadBytes("KMyMoney", "test.kmy"));
        assertEquals(Collections.singletonList("test.kmy"), storage.listFiles("KMyMoney", "kmy"));
        assertEquals(Collections.singletonList("Belege"), storage.listFolders("KMyMoney"));
        assertEquals(6, storage.fileSize("KMyMoney", "test.kmy"));
        assertEquals(-1, storage.fileSize("KMyMoney", "fehlt.kmy"));
        assertTrue(storage.listAllFiles("gibtsnicht").isEmpty());
    }

    @Test
    public void ueberschreibenKuerztDieDatei() throws Exception {
        storage.uploadText("", "x.csv", "eine lange erste Fassung");
        storage.uploadText("", "x.csv", "kurz");
        assertEquals("kurz", storage.downloadText("", "x.csv"));
    }

    @Test
    public void geaenderteDateiWirdNichtUeberschrieben() throws Exception {
        storage.uploadText("", "d.kmy", "v1");
        String version = storage.fileVersion("", "d.kmy");
        assertFalse(version.isEmpty());
        // Jemand anderes schreibt dazwischen (z. B. die Sync-App) – andere Größe, anderer Stand.
        Files.write(new File(root, "d.kmy").toPath(), bytes("v2 von woanders"));
        try {
            storage.uploadBytes("", "d.kmy", bytes("v3"), version);
            fail("Konflikt erwartet");
        } catch (RemoteConflictException expected) {
            // gut
        }
        assertEquals("v2 von woanders", storage.downloadText("", "d.kmy"));
    }

    @Test
    public void umbenennenErsetztNieEinVorhandenesZiel() throws Exception {
        storage.uploadText("", "a.tmp", "neu");
        storage.uploadText("", "a.kmy", "alt");
        try {
            storage.moveNoReplace("", "a.tmp", "a.kmy", "");
            fail("Konflikt erwartet");
        } catch (RemoteConflictException expected) {
            // gut
        }
        assertEquals("alt", storage.downloadText("", "a.kmy"));
        storage.moveNoReplace("", "a.tmp", "b.kmy", "");
        assertEquals("neu", storage.downloadText("", "b.kmy"));
        assertFalse(storage.listAllFiles("").contains("a.tmp"));
    }

    @Test
    public void sicheresErsetzenDerKmyDatei() throws Exception {
        storage.uploadText("KMyMoney", "haushalt.kmy", "alter Stand");
        String version = storage.fileVersion("KMyMoney", "haushalt.kmy");
        SafeReplace.replace(storage, "KMyMoney", "haushalt.kmy", bytes("neuer Stand"), version,
                "20261107-120000");
        assertEquals("neuer Stand", storage.downloadText("KMyMoney", "haushalt.kmy"));
        List<String> rest = storage.listAllFiles("KMyMoney");
        assertEquals(Collections.singletonList("haushalt.kmy"), rest);
    }

    @Test
    public void kopierenUndVerschiebenUeberOrdner() throws Exception {
        storage.uploadText("Backup", "x.kmy.bak-1", "s");
        storage.copy("Backup", "x.kmy.bak-1", "Backup/Monat", "x.kmy.bak-1");
        assertEquals("s", storage.downloadText("Backup/Monat", "x.kmy.bak-1"));
        try {
            storage.copy("Backup", "x.kmy.bak-1", "Backup/Monat", "x.kmy.bak-1");
            fail("Belegtes Ziel muss scheitern");
        } catch (RemoteConflictException expected) {
            // gut
        }
        try {
            storage.move("Belege/2026", "fehlt.jpg", "Papierkorb", "fehlt.jpg");
            fail("Fehlende Quelle muss scheitern");
        } catch (IOException expected) {
            // gut
        }
    }

    @Test
    public void verschiebenUeberOrdner() throws Exception {
        storage.uploadText("Belege/2026", "b.jpg", "bild");
        storage.move("Belege/2026", "b.jpg", "Belege/Papierkorb", "b.jpg");
        assertEquals("bild", storage.downloadText("Belege/Papierkorb", "b.jpg"));
        assertTrue(storage.listAllFiles("Belege/2026").isEmpty());
        storage.delete("Belege/Papierkorb", "b.jpg");
        assertTrue(storage.listAllFiles("Belege/Papierkorb").isEmpty());
        storage.delete("Belege/Papierkorb", "gibtsnicht.jpg");
    }

    @Test
    public void ohneOrdnerScheitertJederZugriffVerstaendlich() {
        RemoteStorage broken = RemoteStorage.localOrBroken(ctx, "");
        try {
            broken.listFiles("", "kmy");
            fail("Fehler erwartet");
        } catch (IOException e) {
            assertTrue(e.getMessage(), !e.getMessage().isEmpty());
        }
    }

    @Test
    public void diagnoseOhneFreigabeSagtWasZuTunIst() {
        List<Diagnostics.Step> steps = LocalDiagnostics.run(ctx, "", "", "");
        assertEquals(1, steps.size());
        assertFalse(steps.get(0).ok);
        assertTrue(Arrays.asList(steps.get(0).toString()).get(0).length() > 0);
    }

    /** In den Einstellungen: „lokal" ist eine eigene Serverart, und erst ein gewählter Ordner zählt. */
    @Test
    public void lokalerOrdnerAlsServerart() {
        de.spahr.ausgaben.settings.SettingsStore s = new de.spahr.ausgaben.settings.SettingsStore(ctx);
        s.setServerType(de.spahr.ausgaben.settings.SettingsStore.SERVER_LOCAL);
        s.setUrl("");
        assertTrue(s.isLocalServer());
        assertFalse(s.hasRemoteConfig());
        String tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root").toString();
        s.setUrl(tree);
        assertTrue(s.hasRemoteConfig());
        assertTrue(RemoteStorage.from(s) instanceof LocalStorage);
    }
}
