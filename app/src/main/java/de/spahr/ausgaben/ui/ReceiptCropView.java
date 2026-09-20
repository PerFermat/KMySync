package de.spahr.ausgaben.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import de.spahr.ausgaben.receipt.ReceiptEdit;

/**
 * Zeigt ein Belegfoto und darüber einen Rahmen mit vier ziehbaren Eckgriffen. Je nach Modus bleibt der
 * Rahmen rechteckig oder lässt sich frei als Viereck legen, um eine schräg fotografierte Rechnung
 * einzufassen. Helligkeit und Kontrast wirken sofort in der Vorschau.
 */
public class ReceiptCropView extends View {

    /** Kein Griff angefasst. */
    private static final int NONE = -1;

    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix imageToView = new Matrix();
    private final Matrix viewToImage = new Matrix();
    // Beim Ziehen an einer Ecke laeuft onDraw fuer jedes Bild; die beiden Rechtecke wandern deshalb
    // hierher statt jedesmal neu zu entstehen. Gesetzt werden sie gleich zu Beginn von onDraw.
    private final RectF bildRect = new RectF();
    private final RectF flaecheRect = new RectF();
    private final Path quadPath = new Path();
    private final float[] point = new float[2];
    private final float[] screen = new float[8];

    private Bitmap bitmap;
    private float[] quad;
    private boolean rectMode = true;
    private int dragging = NONE;
    private float handleRadius;
    private float touchRadius;

    public ReceiptCropView(Context context) {
        super(context);
        init();
    }

    public ReceiptCropView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        handleRadius = 10f * density;
        touchRadius = 28f * density;
        shadePaint.setColor(0xA0000000);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2f * density);
        linePaint.setColor(Color.WHITE);
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setColor(Color.WHITE);
    }

    /** Farbe der Griffe und des Umrisses (Akzentfarbe des Themes). */
    public void setAccentColor(int color) {
        linePaint.setColor(color);
        handlePaint.setColor(color);
        invalidate();
    }

    public void setBitmap(Bitmap bmp) {
        this.bitmap = bmp;
        reset();
    }

    public Bitmap bitmap() {
        return bitmap;
    }

    /** Setzt den Rahmen aufs ganze Bild zurück (Tonwerte bleiben, die steuert die Activity). */
    public void reset() {
        quad = bitmap == null ? null : ReceiptEdit.startQuad(bitmap.getWidth(), bitmap.getHeight());
        invalidate();
    }

    public void setRectMode(boolean rect) {
        if (this.rectMode == rect) {
            return;
        }
        this.rectMode = rect;
        if (rect && quad != null && bitmap != null) {
            // Beim Zurückschalten das Viereck auf sein umschließendes Rechteck begradigen: erst die Ecke
            // unten-rechts ans Maximum, dann oben-links ans Minimum – moveCorner zieht die Nachbarn mit.
            float minX = Math.min(Math.min(quad[0], quad[2]), Math.min(quad[4], quad[6]));
            float maxX = Math.max(Math.max(quad[0], quad[2]), Math.max(quad[4], quad[6]));
            float minY = Math.min(Math.min(quad[1], quad[3]), Math.min(quad[5], quad[7]));
            float maxY = Math.max(Math.max(quad[1], quad[3]), Math.max(quad[5], quad[7]));
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            ReceiptEdit.moveCorner(quad, ReceiptEdit.BOTTOM_RIGHT, maxX, maxY, true, w, h);
            ReceiptEdit.moveCorner(quad, ReceiptEdit.TOP_LEFT, minX, minY, true, w, h);
        }
        invalidate();
    }

    public boolean isRectMode() {
        return rectMode;
    }

    public float[] quad() {
        return quad;
    }

    /** Helligkeit und Kontrast jeweils −100…+100 für die Vorschau. */
    public void setAdjust(float brightness, float contrast) {
        imagePaint.setColorFilter(new ColorMatrixColorFilter(
                new ColorMatrix(ReceiptEdit.colorMatrix(brightness, contrast))));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (bitmap == null || quad == null || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        bildRect.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
        flaecheRect.set(handleRadius, handleRadius,
                getWidth() - handleRadius, getHeight() - handleRadius);
        imageToView.setRectToRect(bildRect, flaecheRect, Matrix.ScaleToFit.CENTER);
        imageToView.invert(viewToImage);
        canvas.drawBitmap(bitmap, imageToView, imagePaint);

        imageToView.mapPoints(screen, quad);
        quadPath.reset();
        quadPath.moveTo(screen[0], screen[1]);
        quadPath.lineTo(screen[2], screen[3]);
        quadPath.lineTo(screen[4], screen[5]);
        quadPath.lineTo(screen[6], screen[7]);
        quadPath.close();

        // Alles außerhalb des Rahmens abdunkeln, damit der Zuschnitt sofort ins Auge fällt.
        canvas.save();
        canvas.clipPath(quadPath, android.graphics.Region.Op.DIFFERENCE);
        canvas.drawRect(0, 0, getWidth(), getHeight(), shadePaint);
        canvas.restore();

        canvas.drawPath(quadPath, linePaint);
        for (int i = 0; i < 4; i++) {
            canvas.drawCircle(screen[i * 2], screen[i * 2 + 1], handleRadius, handlePaint);
        }
    }

    @Override
    // Lint verlangt hier performClick (ClickableViewAccessibility). Es gibt nichts zu klicken: Diese
    // Ansicht zieht Ecken, ein Tipp ohne Bewegung tut gar nichts. Ein performClick bei jedem Abheben
    // des Fingers erfände ein Ereignis, das keine Bedeutung hat - und feuerte jeden Zuhörer mit, den
    // später jemand anhängt, nach jedem Ziehen.
    @SuppressLint("ClickableViewAccessibility")
    public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null || quad == null) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = nearestHandle(event.getX(), event.getY());
                if (dragging != NONE) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                return false;
            case MotionEvent.ACTION_MOVE:
                if (dragging == NONE) {
                    return false;
                }
                point[0] = event.getX();
                point[1] = event.getY();
                viewToImage.mapPoints(point);
                ReceiptEdit.moveCorner(quad, dragging, point[0], point[1], rectMode,
                        bitmap.getWidth(), bitmap.getHeight());
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = NONE;
                return true;
            default:
                return false;
        }
    }

    /** Index des Griffs unter dem Finger, sonst {@link #NONE}. */
    private int nearestHandle(float x, float y) {
        imageToView.mapPoints(screen, quad);
        int best = NONE;
        float bestDist = touchRadius;
        for (int i = 0; i < 4; i++) {
            float d = (float) Math.hypot(screen[i * 2] - x, screen[i * 2 + 1] - y);
            if (d <= bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }
}
