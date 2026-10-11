package de.spahr.ausgaben.ui;

import android.app.Activity;
import android.net.Uri;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Der Dokumentenscanner der Google-Play-Dienste: erkennt die Kanten des Belegs, entzerrt ihn und
 * nimmt mehrere Seiten hintereinander auf. Die App bekommt fertige Bilder zurück.
 *
 * <p>Nur in der Variante „full". Die Bibliothek ist proprietär, und der Scan-Bildschirm läuft in den
 * Play-Diensten – die Variante „foss" hat unter demselben Namen eine Klasse, die nichts davon
 * enthält. Auf einem Gerät ohne Play-Dienste scheitert der Start; dann geht es den Weg, den der
 * Aufrufer als Rückfall mitgibt.</p>
 */
final class DocumentScan {

    /** Empfängt die Seiten eines Scans, in der gescannten Reihenfolge. */
    interface Seiten {
        void da(List<Uri> seiten);
    }

    /** Mehr Seiten nimmt ein Scan nicht auf; ein Beleg mit mehr gehört als PDF angehängt. */
    private static final int MAX_SEITEN = 20;

    private final ComponentActivity activity;
    private final ActivityResultLauncher<IntentSenderRequest> launcher;

    /** Muss vor STARTED angelegt werden: Der Launcher lässt sich nur dann registrieren. */
    DocumentScan(ComponentActivity activity, Seiten empfaenger) {
        this.activity = activity;
        this.launcher = activity.registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                    if (result.getResultCode() != Activity.RESULT_OK) {
                        return;   // abgebrochen
                    }
                    GmsDocumentScanningResult scan =
                            GmsDocumentScanningResult.fromActivityResultIntent(result.getData());
                    List<Uri> seiten = new ArrayList<>();
                    if (scan != null && scan.getPages() != null) {
                        for (GmsDocumentScanningResult.Page page : scan.getPages()) {
                            seiten.add(page.getImageUri());
                        }
                    }
                    if (!seiten.isEmpty()) {
                        empfaenger.da(seiten);
                    }
                });
    }

    /** Gibt es den Scanner in diesem Build? */
    static boolean available() {
        return true;
    }

    /**
     * Öffnet den Scan-Bildschirm.
     *
     * @param beiFehler läuft, wenn er sich nicht öffnen lässt
     */
    void start(Runnable beiFehler) {
        GmsDocumentScannerOptions options = new GmsDocumentScannerOptions.Builder()
                // Aus der Galerie holt die App Bilder über ihren eigenen Eintrag.
                .setGalleryImportAllowed(false)
                .setPageLimit(MAX_SEITEN)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build();
        try {
            GmsDocumentScanning.getClient(options)
                    .getStartScanIntent(activity)
                    .addOnSuccessListener(sender ->
                            launcher.launch(new IntentSenderRequest.Builder(sender).build()))
                    .addOnFailureListener(e -> beiFehler.run());
        } catch (RuntimeException e) {
            beiFehler.run();
        }
    }
}
