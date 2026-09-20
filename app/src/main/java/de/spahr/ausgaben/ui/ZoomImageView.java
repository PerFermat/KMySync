package de.spahr.ausgaben.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * Zeigt ein Bild eingepasst an und lässt es vergrößern, verschieben und per Doppeltipp wieder einpassen.
 *
 * <p>Bewusst eine eigene {@link View} statt einer fremden Bibliothek – gezeichnet wird wie in
 * {@link ReceiptCropView} mit einer Matrix auf das Bitmap. Solange das Bild eingepasst ist, gibt die
 * Ansicht waagerechte Wischgesten an den Eltern-Container weiter, damit dort geblättert werden kann; ist
 * vergrößert, behält sie die Geste für sich.</p>
 */
public class ZoomImageView extends View {

    /** Größte Vergrößerung, gemessen an der eingepassten Darstellung. */
    private static final float MAX_SCALE = 8f;
    /** Vergrößerung, auf die ein Doppeltipp springt. */
    private static final float DOUBLE_TAP_SCALE = 2.5f;

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF bounds = new RectF();
    private final float[] values = new float[9];

    private Bitmap bitmap;
    /** Maßstab der eingepassten Darstellung – die 1 der obigen Grenzen. */
    private float fitScale = 1f;
    private ScaleGestureDetector scaleDetector;
    private GestureDetector tapDetector;
    private float lastX;
    private float lastY;
    private boolean dragging;

    public ZoomImageView(Context context) {
        super(context);
        init();
    }

    public ZoomImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        scaleDetector = new ScaleGestureDetector(getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                zoomBy(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
            }
        });
        tapDetector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (scale() > fitScale * 1.05f) {
                    fit();
                } else {
                    zoomBy(DOUBLE_TAP_SCALE, e.getX(), e.getY());
                }
                return true;
            }
        });
    }

    public void setBitmap(Bitmap bmp) {
        this.bitmap = bmp;
        fit();
    }

    public Bitmap bitmap() {
        return bitmap;
    }

    /** Passt das Bild wieder vollständig in die Ansicht ein. */
    public void fit() {
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) {
            invalidate();
            return;
        }
        matrix.setRectToRect(
                new RectF(0, 0, bitmap.getWidth(), bitmap.getHeight()),
                new RectF(0, 0, getWidth(), getHeight()),
                Matrix.ScaleToFit.CENTER);
        fitScale = scale();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fit();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, matrix, paint);
        }
    }

    /** Ist das Bild vergrößert? Dann gehört das Wischen dem Verschieben, nicht dem Blättern. */
    public boolean isZoomed() {
        return scale() > fitScale * 1.05f;
    }

    @Override
    // Lint verlangt performClick (ClickableViewAccessibility). Tipp und Doppeltipp nimmt hier schon
    // tapDetector entgegen; ein zusätzliches performClick wäre ein zweiter Klick auf dieselbe Geste.
    @SuppressLint("ClickableViewAccessibility")
    public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null) {
            return false;
        }
        scaleDetector.onTouchEvent(event);
        tapDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                dragging = true;
                // Bei zwei Fingern oder im vergrößerten Zustand gehört die Geste uns.
                getParent().requestDisallowInterceptTouchEvent(isZoomed());
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (scaleDetector.isInProgress()) {
                    lastX = event.getX();
                    lastY = event.getY();
                    return true;
                }
                if (dragging && isZoomed()) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                    matrix.postTranslate(event.getX() - lastX, event.getY() - lastY);
                    clamp();
                    invalidate();
                }
                lastX = event.getX();
                lastY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return true;
        }
    }

    /** Vergrößert um {@code factor} mit dem angetippten Punkt als Fixpunkt und hält die Grenzen ein. */
    private void zoomBy(float factor, float focusX, float focusY) {
        if (bitmap == null) {
            return;
        }
        float current = scale();
        float wanted = Math.max(fitScale, Math.min(current * factor, fitScale * MAX_SCALE));
        if (current != 0) {
            matrix.postScale(wanted / current, wanted / current, focusX, focusY);
        }
        clamp();
        invalidate();
    }

    /**
     * Hält das Bild im Bild: was größer als die Ansicht ist, darf keine Lücke am Rand lassen; was kleiner
     * ist, wird mittig gehalten.
     */
    private void clamp() {
        if (bitmap == null) {
            return;
        }
        bounds.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
        matrix.mapRect(bounds);
        float dx = 0;
        float dy = 0;
        if (bounds.width() <= getWidth()) {
            dx = (getWidth() - bounds.width()) / 2f - bounds.left;
        } else if (bounds.left > 0) {
            dx = -bounds.left;
        } else if (bounds.right < getWidth()) {
            dx = getWidth() - bounds.right;
        }
        if (bounds.height() <= getHeight()) {
            dy = (getHeight() - bounds.height()) / 2f - bounds.top;
        } else if (bounds.top > 0) {
            dy = -bounds.top;
        } else if (bounds.bottom < getHeight()) {
            dy = getHeight() - bounds.bottom;
        }
        matrix.postTranslate(dx, dy);
    }

    /** Der aktuelle Maßstab (waagerecht; die Matrix skaliert immer gleichmäßig). */
    private float scale() {
        matrix.getValues(values);
        return values[Matrix.MSCALE_X];
    }
}
