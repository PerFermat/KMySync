package de.spahr.ausgaben.ui;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.Toast;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.slider.Slider;

import java.io.File;
import java.io.FileOutputStream;

import de.spahr.ausgaben.R;
import de.spahr.ausgaben.receipt.ReceiptEdit;

/**
 * Nachbearbeitung eines Belegfotos: rechteckiger Zuschnitt, Trapezkorrektur für schräg fotografierte
 * Rechnungen sowie Helligkeit und Kontrast.
 *
 * <p>Geladen wird {@link #EXTRA_SOURCE} (die aktuelle Datei oder die Sicherung – das entscheidet der
 * Aufrufer), geschrieben wird nach {@link #EXTRA_PATH}. Beim Übernehmen legt der Editor <b>zuvor</b> die
 * Sicherung {@link #EXTRA_BACKUP} an, falls es sie noch nicht gibt; eine vorhandene bleibt unangetastet.
 * So ist das unbearbeitete Bild dauerhaft erreichbar, und dass die Sicherung existiert, heißt zugleich:
 * dieser Beleg wurde schon einmal bearbeitet. Abbrechen oder Zurück lässt alle Dateien unberührt.</p>
 */
public class ReceiptEditActivity extends LocalizedActivity {

    /** Zieldatei – hierhin wird das Ergebnis geschrieben. */
    public static final String EXTRA_PATH = "path";
    /** Was geladen wird; fehlt es oder gibt es die Datei nicht, dient die Zieldatei als Quelle. */
    public static final String EXTRA_SOURCE = "source";
    /** Wohin die unbearbeitete Fassung gesichert wird, bevor zum ersten Mal überschrieben wird. */
    public static final String EXTRA_BACKUP = "backup";

    /** Arbeitsauflösung der Vorschau – groß genug zum Zielen, klein genug fürs Bildgedächtnis. */
    private static final int PREVIEW_MAX_EDGE = 1600;
    private static final int JPEG_QUALITY = 85;

    private ReceiptCropView cropView;
    private Slider brightness;
    private Slider contrast;
    private File target;
    private File backup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_receipt_edit);

        String path = getIntent().getStringExtra(EXTRA_PATH);
        String src = getIntent().getStringExtra(EXTRA_SOURCE);
        String back = getIntent().getStringExtra(EXTRA_BACKUP);
        if (path == null || path.isEmpty()) {
            finish();
            return;
        }
        target = new File(path);
        backup = back == null || back.isEmpty() ? null : new File(back);
        File source = src == null || src.isEmpty() ? target : new File(src);
        if (!source.exists()) {
            source = target;
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        cropView = findViewById(R.id.cropView);
        cropView.setAccentColor(androidx.core.content.ContextCompat.getColor(this, R.color.green_primary));

        MaterialButtonToggleGroup mode = findViewById(R.id.receiptEditMode);
        mode.check(R.id.btnModeRect);
        mode.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                cropView.setRectMode(checkedId == R.id.btnModeRect);
            }
        });

        brightness = findViewById(R.id.sliderBrightness);
        contrast = findViewById(R.id.sliderContrast);
        Slider.OnChangeListener adjust = (slider, value, fromUser) ->
                cropView.setAdjust(brightness.getValue(), contrast.getValue());
        brightness.addOnChangeListener(adjust);
        contrast.addOnChangeListener(adjust);

        MaterialButton reset = findViewById(R.id.btnReceiptEditReset);
        reset.setOnClickListener(v -> {
            cropView.reset();
            brightness.setValue(0);
            contrast.setValue(0);
        });
        findViewById(R.id.btnReceiptEditApply).setOnClickListener(v -> apply());

        load(source);
    }

    /** Lädt die Vorlage verkleinert in die Vorschau (Vollauflösung erst beim Übernehmen). */
    private void load(File source) {
        final File src = source;
        new Thread(() -> {
            Bitmap bmp = ReceiptEdit.decode(src, PREVIEW_MAX_EDGE);
            post(() -> {
                if (isFinishing()) {
                    return;
                }
                if (bmp == null) {
                    Toast.makeText(this, R.string.receipt_error, Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                cropView.setBitmap(bmp);
                cropView.setAdjust(0, 0);
            });
        }).start();
    }

    /** Rechnet den Rahmen auf das Vorschaubild an und schreibt das Ergebnis in die Zieldatei. */
    private void apply() {
        Bitmap src = cropView.bitmap();
        float[] quad = cropView.quad();
        if (src == null || quad == null) {
            return;
        }
        final float[] copy = quad.clone();
        final boolean rect = cropView.isRectMode();
        final float b = brightness.getValue();
        final float c = contrast.getValue();
        new Thread(() -> {
            boolean ok;
            try {
                Bitmap out = ReceiptEdit.apply(src, copy, rect, b, c);
                // Vor dem ersten Überschreiben die unbearbeitete Fassung sichern; eine vorhandene Sicherung
                // bleibt, damit auch nach mehreren Runden noch das Ausgangsbild erreichbar ist.
                if (backup != null && !backup.exists() && !copyFile(target, backup)) {
                    throw new java.io.IOException("Sicherung fehlgeschlagen");
                }
                try (FileOutputStream fos = new FileOutputStream(target)) {
                    out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
                }
                out.recycle();
                ok = target.length() > 0;
            } catch (Exception e) {
                ok = false;
            }
            final boolean done = ok;
            post(() -> {
                if (done) {
                    setResult(RESULT_OK);
                    finish();
                } else {
                    Toast.makeText(this, R.string.receipt_error, Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private static boolean copyFile(File from, File to) {
        try (java.io.InputStream in = new java.io.FileInputStream(from);
             java.io.OutputStream out = new java.io.FileOutputStream(to)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return true;
        } catch (Exception e) {
            to.delete();
            return false;
        }
    }
}
