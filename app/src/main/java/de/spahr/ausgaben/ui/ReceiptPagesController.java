package de.spahr.ausgaben.ui;

import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.util.Calendar;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.receipt.NoteReceipt;
import de.spahr.ausgaben.receipt.ReceiptImage;
import de.spahr.ausgaben.receipt.ReceiptPages;
import de.spahr.ausgaben.receipt.ReceiptSync;
import de.spahr.ausgaben.receipt.Receipts;

/**
 * Die Belegseiten einer Buchung im Buchungseditor: Fotos oder PDFs anhängen, laden, zuschneiden,
 * löschen und ansehen, und beim Speichern die endgültigen Namen vergeben und hochladen. Ausgelagert aus
 * {@link BookingEditActivity}, um deren Umfang zu verringern – der Code ist dabei unverändert umgezogen.
 *
 * <p>Die Activity-Result-Launcher registriert der Konstruktor; er muss deshalb wie sie vor STARTED laufen,
 * also in {@code onCreate}. Die Kopfzeile des Belegs (Sichtbarkeit, Text, Kamera-Knopf) baut weiter die
 * Activity; dieser Controller liefert ihr {@link #isEmpty()}/{@link #countText()} und füllt darunter die
 * Seiten ({@link #fill()}).</p>
 */
class ReceiptPagesController {

    /** Was der Controller von der Buchungsmaske braucht. */
    interface Host {
        /** Reine Ansicht – kann sich nach {@code onCreate} noch ändern, deshalb jedes Mal gefragt. */
        boolean isReadOnly();

        /** Jahr des (ggf. gerade geänderten) Buchungsdatums – der Jahresordner der Belege. */
        int receiptYear();

        /** Ist eine bestehende Buchung geladen (nicht Neu/Vorlage)? */
        boolean hasBooking();

        /** Die Seiten haben sich geändert – Notiz-/Beleg-Zeilen neu aufbauen. */
        void onReceiptPagesChanged();

        /** Ein verschwundener Beleg wurde entfernt – die Buchung sofort speichern. */
        void saveAfterRemovingMissing();
    }

    /**
     * Eine Belegseite: entweder bereits gespeichert ({@code savedName}) oder frisch aufgenommen
     * ({@code pending}, ein komprimiertes Temp, das beim Speichern seinen endgültigen Namen bekommt).
     * Ob es ein Foto oder ein PDF ist, sagt die Endung des Namens – ein eigenes Feld braucht es nicht.
     */
    private static final class Page {
        String savedName;
        java.io.File pending;

        Page(String savedName, java.io.File pending) {
            this.savedName = savedName;
            this.pending = pending;
        }

        java.io.File file(android.content.Context ctx) {
            return pending != null ? pending : Receipts.localFile(ctx, savedName);
        }

        boolean isPdf() {
            return NoteReceipt.isPdf(pending != null ? pending.getName() : savedName);
        }
    }

    private final ComponentActivity activity;
    private final Host host;
    private final View rowReceipt;
    private final LinearLayout receiptPagesView;
    private final LinearLayout receiptPageIcons;
    private final ImportBanner receiptBanner;

    /** Die Seiten des Belegs in Seitenreihenfolge; leer = kein Beleg. */
    private final java.util.List<Page> receiptPages = new java.util.ArrayList<>();
    /** Beim Speichern zu löschende, bereits gespeicherte Seiten. */
    private final java.util.List<String> removedReceipts = new java.util.ArrayList<>();
    private android.net.Uri cameraTempUri;
    private java.io.File cameraTempFile;
    private final ActivityResultLauncher<android.net.Uri> takePictureLauncher;
    private final ActivityResultLauncher<String> pickImageLauncher;
    /** Dateiauswahl des Systems für ein PDF-Dokument. */
    private final ActivityResultLauncher<String[]> pickPdfLauncher;
    /** Zuschneiden/Begradigen/Aufhellen eines Belegs ({@link ReceiptEditActivity}). */
    private final ActivityResultLauncher<Intent> receiptEditLauncher;
    /** Beim Bearbeiten einer bereits gespeicherten Seite: deren Name für das Hochladen danach. */
    private String editingSavedReceipt;
    /** Jahresordner der Belege beim Öffnen der Buchung; {@code -1} = keine gespeicherten Belege. */
    private int origReceiptYear = -1;

    ReceiptPagesController(ComponentActivity activity, Host host, View rowReceipt,
                           LinearLayout receiptPagesView, LinearLayout receiptPageIcons,
                           ImportBanner receiptBanner) {
        this.activity = activity;
        this.host = host;
        this.rowReceipt = rowReceipt;
        this.receiptPagesView = receiptPagesView;
        this.receiptPageIcons = receiptPageIcons;
        this.receiptBanner = receiptBanner;

        // Beleg-Foto: Launcher (vor STARTED registrieren).
        takePictureLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.TakePicture(), success -> {
                    if (Boolean.TRUE.equals(success) && cameraTempUri != null) {
                        ingestReceipt(cameraTempUri, cameraTempFile);
                    } else if (cameraTempFile != null) {
                        cameraTempFile.delete();
                    }
                    cameraTempUri = null;
                    cameraTempFile = null;
                });
        pickImageLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.GetContent(), uri -> {
                    if (uri != null) {
                        ingestReceipt(uri, null);
                    }
                });
        pickPdfLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri != null) {
                        ingestPdf(uri);
                    }
                });
        receiptEditLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    String saved = editingSavedReceipt;
                    editingSavedReceipt = null;
                    if (result.getResultCode() != android.app.Activity.RESULT_OK) {
                        return;
                    }
                    if (saved != null) {
                        // Bereits gespeicherter Beleg: Datei ist ersetzt, also samt Original neu hochladen.
                        Receipts.addPending(activity, saved, host.receiptYear());
                        Receipts.addPending(activity, NoteReceipt.originalName(saved), host.receiptYear());
                        ReceiptSync.syncPending(activity);
                    }
                    host.onReceiptPagesChanged();
                    Toast.makeText(activity, R.string.receipt_edit_done, Toast.LENGTH_SHORT).show();
                });
    }

    /** Jahr eines Zeitpunkts – der Jahresordner der Belege folgt dem Buchungsdatum. */
    static int yearOf(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return c.get(Calendar.YEAR);
    }

    /** Hängt kein Beleg an der Buchung? */
    boolean isEmpty() {
        return receiptPages.isEmpty();
    }

    private void post(Runnable r) {
        Ui.post(activity, r);
    }

    /**
     * Baut die Anzeige der Belegseiten neu auf. Bei <b>Fotos</b> genügt in der Ansicht ein Bild-Symbol
     * rechtsbündig in der Kopfzeile – geblättert wird dann im eigenen Betrachter; im Bearbeiten-Modus steht
     * je Seite eine Zeile mit Beschriftung, Zuschneiden und Löschen darunter.
     *
     * <p>Ein <b>PDF</b> bekommt immer eine eigene Zeile, auch in der Ansicht: es öffnet sich einzeln im
     * Betrachter des Geräts, und bei mehreren muss zu sehen sein, welches man antippt. Zuschneiden gibt es
     * dort nicht, in der Ansicht auch kein Löschen.</p>
     */
    void fill() {
        receiptPagesView.removeAllViews();
        receiptPageIcons.removeAllViews();
        if (rowReceipt.getVisibility() != View.VISIBLE) {
            return;
        }
        boolean readOnly = host.isReadOnly();
        if (readOnly && !hasPdfPages()) {
            // Ein einziges Symbol – wie viele Seiten es sind, steht schon im Text daneben; im Betrachter
            // wird dann geblättert.
            if (savedPageNames().isEmpty()) {
                return;
            }
            android.widget.ImageButton icon = new android.widget.ImageButton(activity);
            icon.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                    Ui.dp(activity, 44), Ui.dp(activity, 44)));
            icon.setImageResource(android.R.drawable.ic_menu_gallery);
            icon.setBackgroundResource(backgroundBorderless());
            icon.setContentDescription(activity.getString(R.string.receipt_view_title));
            icon.setOnClickListener(v -> openReceiptViewer(0));
            receiptPageIcons.addView(icon);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (int i = 0; i < receiptPages.size(); i++) {
            final Page page = receiptPages.get(i);
            final boolean pdf = page.isPdf();
            View row = inflater.inflate(R.layout.item_receipt_page, receiptPagesView, false);
            android.widget.TextView label = row.findViewById(R.id.textReceiptPage);
            label.setText(activity.getString(pdf
                    ? (page.pending != null ? R.string.receipt_pdf_new : R.string.receipt_pdf_label)
                    : (page.pending != null ? R.string.receipt_page_new : R.string.receipt_page_label),
                    i + 1));
            label.setCompoundDrawablesRelativeWithIntrinsicBounds(pdf ? R.drawable.ic_pdf : 0, 0, 0, 0);
            label.setCompoundDrawablePadding(pdf ? Ui.dp(activity, 8) : 0);
            if (pdf) {
                // Ein PDF öffnet der Betrachter des Geräts – auch ein noch nicht gespeichertes Temp.
                label.setOnClickListener(v -> openPdf(page));
            } else if (page.savedName != null) {
                // Eine bereits gespeicherte Seite lässt sich ansehen; ein frisches Bild liegt nur als Temp vor.
                final int index = savedPageNames().indexOf(page.savedName);
                label.setOnClickListener(v -> openReceiptViewer(index));
            }
            View edit = row.findViewById(R.id.btnReceiptPageEdit);
            View delete = row.findViewById(R.id.btnReceiptPageDelete);
            edit.setVisibility(pdf || readOnly ? View.GONE : View.VISIBLE);
            delete.setVisibility(readOnly ? View.GONE : View.VISIBLE);
            edit.setOnClickListener(v -> editReceipt(page));
            delete.setOnClickListener(v -> removeReceiptPage(page));
            receiptPagesView.addView(row);
        }
    }

    /**
     * Öffnet ein PDF im Standard-Betrachter des Geräts. Eine bereits gespeicherte Datei wird bei Bedarf
     * erst vom Netzlaufwerk geholt (deshalb der Hintergrund-Thread), dann als {@code content://}-Verweis
     * des FileProviders weitergereicht – der fremden App wird nur Lesen für diese eine Datei gestattet.
     */
    private void openPdf(Page page) {
        final java.io.File pending = page.pending;
        final String saved = page.savedName;
        final int year = host.receiptYear();
        // Nur ein noch nicht lokaler Beleg wird geholt – dann die Statuszeile zeigen (kann dauern).
        final boolean willWait = pending == null;
        if (willWait) {
            receiptBanner.start(activity.getString(R.string.receipt_loading_wait));
        }
        new Thread(() -> {
            final java.io.File file;
            final boolean offline;
            if (pending != null) {
                file = pending;
                offline = false;
            } else {
                ReceiptSync.Loaded loaded = ReceiptSync.ensureLocalWaiting(activity, saved, year, null);
                file = loaded.file;
                offline = loaded.offline;
            }
            post(() -> {
                if (willWait) {
                    receiptBanner.finishNow();
                }
                if (file == null || !file.exists()) {
                    // Ohne Verbindung nur ein Hinweis; online, aber unauffindbar → Entfernen anbieten.
                    if (offline) {
                        Toast.makeText(activity, R.string.receipt_offline, Toast.LENGTH_SHORT).show();
                    } else {
                        promptRemoveUnavailableReceipt(page);
                    }
                    return;
                }
                try {
                    android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                            activity, activity.getPackageName() + ".fileprovider", file);
                    activity.startActivity(new Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/pdf")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                } catch (android.content.ActivityNotFoundException e) {
                    Toast.makeText(activity, R.string.receipt_pdf_no_viewer, Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(activity, R.string.receipt_error, Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    /** Der randlose Tipp-Hintergrund des Themes – wie bei den Knöpfen im Layout. */
    private int backgroundBorderless() {
        android.util.TypedValue out = new android.util.TypedValue();
        activity.getTheme().resolveAttribute(
                androidx.appcompat.R.attr.selectableItemBackgroundBorderless, out, true);
        return out.resourceId;
    }

    /**
     * Kamera, Galerie oder PDF-Dokument. Eine Buchung trägt entweder Fotoseiten oder PDFs – der unpassende
     * Eintrag ist deshalb abgeblendet, bis alle vorhandenen Seiten gelöscht sind.
     */
    void showSourceDialog() {
        String[] items = {
                activity.getString(R.string.receipt_source_camera),
                activity.getString(R.string.receipt_source_gallery),
                activity.getString(R.string.receipt_source_document)
        };
        final boolean pdf = hasPdfPages();
        final boolean photo = hasPhotoPages();
        // Ein ArrayAdapter statt setItems: nur er kann einzelne Einträge sperren (die Liste des Dialogs
        // richtet sich nach isEnabled).
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<String>(
                activity, android.R.layout.simple_list_item_1, items) {
            @Override
            public boolean isEnabled(int position) {
                return position == 2 ? !photo : !pdf;
            }

            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                v.setAlpha(isEnabled(position) ? 1f : 0.4f);
                return v;
            }
        };
        new AppDialog(activity)
                .setTitle(R.string.receipt_add)
                .setAdapter(adapter, (d, which) -> {
                    if (which == 0) {
                        startReceiptCamera();
                    } else if (which == 1) {
                        pickImageLauncher.launch("image/*");
                    } else {
                        pickPdfLauncher.launch(new String[]{"application/pdf"});
                    }
                })
                .show();
    }

    /** „3 Seite(n)" bzw. „2 Dokument(e)" – je nachdem, woraus der Beleg besteht. */
    String countText() {
        return activity.getString(hasPdfPages() ? R.string.receipt_pdfs_count : R.string.receipt_pages_count,
                receiptPages.size());
    }

    /** Hängt an der Buchung mindestens ein PDF? */
    private boolean hasPdfPages() {
        for (Page p : receiptPages) {
            if (p.isPdf()) {
                return true;
            }
        }
        return false;
    }

    /** Hängt an der Buchung mindestens eine Fotoseite? */
    private boolean hasPhotoPages() {
        for (Page p : receiptPages) {
            if (!p.isPdf()) {
                return true;
            }
        }
        return false;
    }

    private void startReceiptCamera() {
        try {
            cameraTempFile = new java.io.File(Receipts.dir(activity), "cam_" + System.currentTimeMillis() + ".jpg");
            cameraTempUri = androidx.core.content.FileProvider.getUriForFile(
                    activity, activity.getPackageName() + ".fileprovider", cameraTempFile);
            takePictureLauncher.launch(cameraTempUri);
        } catch (Exception e) {
            Toast.makeText(activity, R.string.receipt_error, Toast.LENGTH_SHORT).show();
        }
    }

    /** Komprimiert die Quelle sofort in ein Temp (Berechtigung ist jetzt gültig); Finalisierung erst beim Speichern. */
    private void ingestReceipt(android.net.Uri src, java.io.File cleanup) {
        final java.io.File tmp = new java.io.File(Receipts.dir(activity),
                "pend_" + java.util.UUID.randomUUID() + ".jpg");
        new Thread(() -> {
            boolean ok;
            try {
                ReceiptImage.saveScaledJpeg(activity, src, tmp, 2000, 75);
                ok = tmp.exists() && tmp.length() > 0;
            } catch (Exception e) {
                ok = false;
            }
            if (cleanup != null) {
                cleanup.delete();
            }
            final boolean fok = ok;
            post(() -> {
                if (fok) {
                    Page page = new Page(null, tmp);
                    receiptPages.add(page);
                    host.onReceiptPagesChanged();
                    Toast.makeText(activity, R.string.receipt_attached, Toast.LENGTH_SHORT).show();
                    askEditReceipt(page);
                } else {
                    tmp.delete();
                    Toast.makeText(activity, R.string.receipt_error, Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    /**
     * Übernimmt ein gewähltes PDF: es wird unverändert ins Temp kopiert – anders als beim Foto gibt es
     * nichts zu skalieren, zu drehen oder nachzubearbeiten. Finalisierung wie dort erst beim Speichern.
     */
    private void ingestPdf(android.net.Uri src) {
        final java.io.File tmp = new java.io.File(Receipts.dir(activity),
                "pend_" + java.util.UUID.randomUUID() + NoteReceipt.PDF);
        new Thread(() -> {
            boolean ok;
            try (java.io.InputStream in = activity.getContentResolver().openInputStream(src);
                 java.io.OutputStream out = new java.io.FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while (in != null && (n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                ok = tmp.exists() && tmp.length() > 0;
            } catch (Exception e) {
                ok = false;
            }
            final boolean fok = ok;
            post(() -> {
                if (fok) {
                    receiptPages.add(new Page(null, tmp));
                    host.onReceiptPagesChanged();
                    Toast.makeText(activity, R.string.receipt_pdf_attached, Toast.LENGTH_SHORT).show();
                } else {
                    tmp.delete();
                    Toast.makeText(activity, R.string.receipt_error, Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    /** Alle Seiten vergessen (Neu-Modus/Kopie); noch nicht gespeicherte Temps werden gelöscht. */
    void clear() {
        for (Page p : receiptPages) {
            if (p.pending != null) {
                originalOf(p.pending).delete();
                p.pending.delete();
            }
        }
        receiptPages.clear();
        removedReceipts.clear();
    }

    /**
     * Ermittelt zum Beleg-Tag einer Notiz alle Seiten (siehe {@link ReceiptPages#find}) und zeigt sie an.
     * Welche Art es ist, sagt die Notiz selbst: {@code BELEG:} steht für Fotoseiten, {@code BELEG (PDF):}
     * für PDF-Dokumente. Das Suchen kann den Server befragen und läuft deshalb im Hintergrund.
     */
    void load(String note, int year) {
        clear();
        origReceiptYear = -1;
        final String pdfTag = NoteReceipt.pdfName(note);
        final String tagName = pdfTag != null ? pdfTag : NoteReceipt.fileName(note);
        if (tagName == null) {
            return;
        }
        final String ext = pdfTag != null ? NoteReceipt.PDF : NoteReceipt.JPG;
        origReceiptYear = year;
        // Seite 1 steht sofort fest, damit die Zeile nicht erst leer aufblitzt.
        receiptPages.add(new Page(pdfTag != null ? NoteReceipt.pageName(tagName, 1, ext) : tagName, null));
        new Thread(() -> {
            final java.util.List<String> found = ReceiptPages.find(activity, tagName, year, ext);
            post(() -> {
                // Nichts gefunden (Datei weg oder offline) → die Vorbelegung mit dem Tag-Namen bleibt stehen.
                if (activity.isFinishing() || found.isEmpty() || found.equals(savedNames())) {
                    return;
                }
                receiptPages.clear();
                for (String name : found) {
                    receiptPages.add(new Page(name, null));
                }
                host.onReceiptPagesChanged();
            });
        }).start();
    }

    /**
     * Wurde das Buchungsdatum über einen Jahreswechsel geschoben, wandern die bereits hochgeladenen Bilder
     * auf dem Server in den neuen Jahresordner. Läuft im Hintergrund.
     *
     * <p>Misslingt es (offline), bleibt der Umzug vorgemerkt und wird beim nächsten Abgleich nachgeholt
     * ({@code ReceiptPages.movePending}). Darauf kommt es an: Die Notiz nennt ab sofort das neue Jahr,
     * und {@code ensureLocal} sucht nur dort. Hier stand früher, ein Rückfall in {@code ensureLocal}
     * finde die Datei weiterhin – das stimmte nie, denn der Rückfall probiert einen anderen
     * Basisordner, aber dasselbe Jahr. Auf diesem Gerät fiel es nur deshalb nicht auf, weil die lokale
     * Kopie liegen bleibt; auf jedem anderen war der Beleg weg.</p>
     */
    private void moveReceiptYear(int newYear) {
        if (!host.hasBooking() || origReceiptYear < 0 || origReceiptYear == newYear) {
            return;
        }
        final java.util.List<String> names = new java.util.ArrayList<>(savedNames());
        names.removeIf(java.util.Objects::isNull);
        final int from = origReceiptYear;
        origReceiptYear = newYear;
        if (!names.isEmpty()) {
            new Thread(() -> ReceiptPages.moveYear(activity.getApplicationContext(), names, from, newYear)).start();
        }
    }

    /** Die Namen der bereits gespeicherten Seiten in Reihenfolge (neue Seiten liefern {@code null}). */
    private java.util.List<String> savedNames() {
        java.util.List<String> names = new java.util.ArrayList<>(receiptPages.size());
        for (Page p : receiptPages) {
            names.add(p.savedName);
        }
        return names;
    }

    /** Nimmt eine Seite aus der Liste; gespeicherte Dateien werden erst beim Speichern gelöscht. */
    private void removeReceiptPage(Page page) {
        if (page.pending != null) {
            originalOf(page.pending).delete();
            page.pending.delete();
        } else if (page.savedName != null) {
            removedReceipts.add(page.savedName);
        }
        receiptPages.remove(page);
        host.onReceiptPagesChanged();
    }

    /**
     * Beleg vorhanden (Verbindung besteht), aber auf dem Server nicht auffindbar: fragt, ob der
     * verwaiste Verweis aus der Buchung entfernt werden soll. Diese Ansicht kennt keinen eigenen
     * Speichern-Schritt, darum wird das Entfernen sofort mit «Entfernen» geschrieben – über den
     * normalen Aktualisieren-Pfad der Maske, der die (exportierte) Buchung dabei auf „bearbeitet" setzt.
     */
    private void promptRemoveUnavailableReceipt(Page page) {
        if (!receiptPages.contains(page)) {
            return; // schon entfernt
        }
        new AppDialog(activity)
                .setTitle(R.string.receipt_missing_title)
                .setMessage(R.string.receipt_missing_delete)
                .setPositiveButton(R.string.remove, (d, w) -> {
                    removeReceiptPage(page);
                    host.saveAfterRemovingMissing();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Fragt direkt nach der Aufnahme, ob das Bild noch zugeschnitten/begradigt werden soll. */
    private void askEditReceipt(Page page) {
        new AppDialog(activity)
                .setTitle(R.string.receipt_edit_title)
                .setMessage(R.string.receipt_edit_question)
                .setPositiveButton(R.string.receipt_edit_yes, (d, w) -> editReceipt(page))
                .setNegativeButton(R.string.receipt_edit_no, null)
                .show();
    }

    /**
     * Öffnet den Bild-Editor für eine Seite. Eine noch nicht gespeicherte wird direkt bearbeitet; bei einer
     * bereits gespeicherten holt {@link ReceiptSync#ensureLocal} Bild und Original bei Bedarf erst vom
     * Netzlaufwerk. Vor der ersten Bearbeitung entsteht die Sicherheitskopie {@code …_original.jpg}.
     */
    private void editReceipt(Page page) {
        if (page.pending != null && page.pending.exists()) {
            startReceiptEditor(page.pending, originalOf(page.pending), null);
            return;
        }
        if (page.savedName == null) {
            return;
        }
        final String file = page.savedName;
        final String originalName = NoteReceipt.originalName(file);
        final int year = host.receiptYear();
        // Gespeicherter Beleg: kann erst vom Server geholt werden – Statuszeile zeigen (kann dauern).
        receiptBanner.start(activity.getString(R.string.receipt_loading_wait));
        new Thread(() -> {
            final ReceiptSync.Loaded loaded = ReceiptSync.ensureLocalWaiting(activity, file, year, null);
            final java.io.File local = loaded.file;
            // Altbelege haben kein Original auf dem Server – dann dient der Beleg selbst als Vorlage.
            final java.io.File original =
                    local == null ? null : ReceiptSync.ensureLocal(activity, originalName, year);
            post(() -> {
                receiptBanner.finishNow();
                if (local == null || !local.exists()) {
                    // Ohne Verbindung nur ein Hinweis; online, aber unauffindbar → Entfernen anbieten.
                    if (loaded.offline) {
                        Toast.makeText(activity, R.string.receipt_offline, Toast.LENGTH_SHORT).show();
                    } else {
                        promptRemoveUnavailableReceipt(page);
                    }
                    return;
                }
                startReceiptEditor(local,
                        original != null && original.exists() ? original : Receipts.localFile(activity, originalName),
                        file);
            });
        }).start();
    }

    /**
     * Startet den Bild-Editor. Gibt es bereits eine Sicherung, wurde dieser Beleg schon einmal bearbeitet –
     * dann wird gefragt, ob die bisherige Bearbeitung fortgesetzt oder wieder beim Original begonnen wird.
     * Die Sicherung selbst legt der Editor beim Übernehmen an; sie wird nie überschrieben.
     */
    private void startReceiptEditor(java.io.File target, java.io.File original, String savedName) {
        if (!original.exists()) {
            launchReceiptEditor(target, original, false, savedName);
            return;
        }
        new AppDialog(activity)
                .setTitle(R.string.receipt_edit_again_title)
                .setMessage(R.string.receipt_edit_again_message)
                .setPositiveButton(R.string.receipt_edit_resume,
                        (d, w) -> launchReceiptEditor(target, original, false, savedName))
                .setNegativeButton(R.string.receipt_edit_from_original,
                        (d, w) -> launchReceiptEditor(target, original, true, savedName))
                .show();
    }

    private void launchReceiptEditor(java.io.File target, java.io.File backup, boolean fromBackup,
                                     String savedName) {
        editingSavedReceipt = savedName;
        String source = de.spahr.ausgaben.receipt.ReceiptEdit.sourceFor(fromBackup,
                target.getAbsolutePath(), backup.getAbsolutePath(), backup.exists());
        receiptEditLauncher.launch(new Intent(activity, ReceiptEditActivity.class)
                .putExtra(ReceiptEditActivity.EXTRA_PATH, target.getAbsolutePath())
                .putExtra(ReceiptEditActivity.EXTRA_SOURCE, source)
                .putExtra(ReceiptEditActivity.EXTRA_BACKUP, backup.getAbsolutePath()));
    }

    /** Datei des unbearbeiteten Originals zu einem Beleg-Temp bzw. einer gespeicherten Datei. */
    private java.io.File originalOf(java.io.File file) {
        return new java.io.File(file.getParentFile(), NoteReceipt.originalName(file.getName()));
    }

    /**
     * Hängt den {@code BELEG:}-Tag an die Notiz an und finalisiert die Seiten – für eine Buchung ebenso wie
     * für die <b>Umbuchung</b>, die keine {@code Booking} zum Füllen hat, sondern ihre Notiz als Text an
     * beide Seiten weiterreicht. Bei {@code asNew} (Kopie/Neu) wird ein <b>bestehender</b> Beleg NICHT
     * übernommen – nur ein neu angehängtes Bild verlinkt.
     *
     * @param createdAt Zeitpunkt der Buchung; sein Jahr bestimmt den Ordner der Belege
     * @return die Notiz mit dem {@code BELEG:}-Tag, falls es Seiten gibt
     */
    String withReceiptTag(String note, long createdAt, boolean asNew) {
        // Der Jahresordner der Belege folgt dem Buchungsdatum – er steckt nicht mehr im Dateinamen.
        final int year = yearOf(createdAt);
        if (asNew) {
            // Kopie/Neu: bestehende Seiten gehören zur Vorlage und werden nicht übernommen.
            for (java.util.Iterator<Page> it = receiptPages.iterator(); it.hasNext(); ) {
                if (it.next().pending == null) {
                    it.remove();
                }
            }
            removedReceipts.clear();
        } else {
            for (String name : removedReceipts) {
                ReceiptPages.delete(activity, name);
            }
            removedReceipts.clear();
        }
        // Die Basis stammt von der ersten bereits gespeicherten Seite – so bleibt die UUID der Buchung
        // erhalten, auch wenn genau diese Seite gerade gelöscht wurde.
        String base = null;
        for (Page p : receiptPages) {
            if (p.savedName != null) {
                base = NoteReceipt.baseOf(p.savedName);
                break;
            }
        }
        if (base == null) {
            base = NoteReceipt.newBase();
        }
        // Neuen Seiten die kleinste freie Nummer geben …
        java.util.List<String> taken = new java.util.ArrayList<>(savedNames());
        taken.removeIf(java.util.Objects::isNull);
        for (Page p : receiptPages) {
            if (p.pending == null) {
                continue;
            }
            String name = NoteReceipt.pageName(base, ReceiptPages.nextFreePage(taken),
                    p.isPdf() ? NoteReceipt.PDF : NoteReceipt.JPG);
            if (finalizeReceipt(p, name, year)) {
                taken.add(name);
            }
        }
        receiptPages.removeIf(p -> p.savedName == null);
        // … und danach lückenlos durchnummerieren, damit die Suche bei der ersten Lücke aufhören kann.
        java.util.List<String> names = savedNames();
        java.util.List<String> target = ReceiptPages.renumber(names);
        for (int i = 0; i < receiptPages.size(); i++) {
            ReceiptPages.rename(activity, names.get(i), target.get(i), year);
            receiptPages.get(i).savedName = target.get(i);
        }
        // In die Notiz kommt nur die Basis (die UUID); die Seiten findet die App darüber selbst. Bei PDFs
        // steht dort der eigene Tag – daran erkennt das Laden später, welche Endung zu suchen ist.
        if (!receiptPages.isEmpty()) {
            String tag = NoteReceipt.tagOf(receiptPages.get(0).savedName);
            note = hasPdfPages() ? NoteReceipt.withPdfName(note, tag) : NoteReceipt.withFileName(note, tag);
            moveReceiptYear(year);
            ReceiptSync.syncPending(activity);
        }
        return note;
    }

    /**
     * Benennt das Temp einer Seite auf ihren endgültigen Namen um und merkt sie zum Hochladen vor – zusammen
     * mit dem unbearbeiteten Original, falls die Aufnahme nachbearbeitet wurde.
     */
    private boolean finalizeReceipt(Page page, String file, int year) {
        if (!page.pending.renameTo(Receipts.localFile(activity, file))) {
            return false;
        }
        Receipts.addPending(activity, file, year);
        java.io.File original = originalOf(page.pending);
        if (original.exists() && original.renameTo(Receipts.localFile(activity, NoteReceipt.originalName(file)))) {
            Receipts.addPending(activity, NoteReceipt.originalName(file), year);
        }
        page.pending = null;
        page.savedName = file;
        return true;
    }

    /** Die Namen der gespeicherten Seiten – die Reihenfolge im Betrachter. */
    private java.util.List<String> savedPageNames() {
        java.util.List<String> names = savedNames();
        names.removeIf(java.util.Objects::isNull);
        return names;
    }

    /**
     * Öffnet die Belegseiten im <b>eigenen</b> Betrachter, beginnend bei {@code index}. Eine fremde Foto-App
     * kam hier nicht in Frage: sie cacht auf den Dateinamen, und ein bearbeiteter Beleg behält seinen Namen –
     * angezeigt wurde dann die alte Fassung.
     */
    private void openReceiptViewer(int index) {
        java.util.List<String> names = savedPageNames();
        if (names.isEmpty()) {
            Toast.makeText(activity, R.string.receipt_not_found, Toast.LENGTH_SHORT).show();
            return;
        }
        activity.startActivity(new Intent(activity, ReceiptViewActivity.class)
                .putExtra(ReceiptViewActivity.EXTRA_FILES, names.toArray(new String[0]))
                .putExtra(ReceiptViewActivity.EXTRA_YEAR, host.receiptYear())
                .putExtra(ReceiptViewActivity.EXTRA_INDEX, Math.max(0, index)));
    }
}
