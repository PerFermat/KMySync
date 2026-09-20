package de.spahr.ausgaben.receipt;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Skaliert ein aufgenommenes/gewähltes Bild herunter, dreht es gemäß EXIF und speichert es als JPEG. */
public final class ReceiptImage {

    private ReceiptImage() {
    }

    /** Dekodiert {@code src}, skaliert auf max. {@code maxEdge} px (lange Kante) und schreibt JPEG nach {@code dest}. */
    public static void saveScaledJpeg(Context ctx, Uri src, File dest, int maxEdge, int quality)
            throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = open(ctx, src)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int longEdge = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        while (longEdge / (sample * 2) >= maxEdge) {
            sample *= 2;
        }
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream in = open(ctx, src)) {
            bmp = BitmapFactory.decodeStream(in, null, opt);
        }
        if (bmp == null) {
            throw new IOException("Bild konnte nicht gelesen werden");
        }
        bmp = transform(ctx, src, bmp, maxEdge);
        try (FileOutputStream fos = new FileOutputStream(dest)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, fos);
        }
        bmp.recycle();
    }

    /** Skaliert exakt auf {@code maxEdge} und dreht nach EXIF-Orientierung. */
    private static Bitmap transform(Context ctx, Uri src, Bitmap bmp, int maxEdge) {
        Matrix m = new Matrix();
        int longEdge = Math.max(bmp.getWidth(), bmp.getHeight());
        if (longEdge > maxEdge) {
            float s = (float) maxEdge / longEdge;
            m.postScale(s, s);
        }
        int rot = rotationDegrees(ctx, src);
        if (rot != 0) {
            m.postRotate(rot);
        }
        if (m.isIdentity()) {
            return bmp;
        }
        Bitmap out = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        if (out != bmp) {
            bmp.recycle();
        }
        return out;
    }

    /**
     * Die EXIF-Drehung des Bildes, oder {@code 0}, wenn keine zu lesen ist.
     *
     * <p>Bewußt {@code androidx.exifinterface}, nicht die eingebaute {@code android.media}-Fassung: Die
     * liest nur JPEG und Raw. Ein Handy im Modus „Hohe Effizienz" nimmt aber HEIC auf, und die wählt man
     * dann aus der Galerie aus. Die eingebaute Fassung liefert dafür nichts, das {@code catch} unten
     * gäbe stumm {@code 0} zurück, und der Beleg läge quer in der Datei – ohne jede Meldung.</p>
     */
    private static int rotationDegrees(Context ctx, Uri src) {
        try (InputStream in = open(ctx, src)) {
            return rotationDegrees(in);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Der eigentliche Lesevorgang, getrennt vom Öffnen — so läßt er sich mit einem Bild im Speicher
     * prüfen, ohne ContentResolver (siehe {@code ReceiptExifTest}).
     */
    static int rotationDegrees(InputStream in) throws IOException {
        int o = new ExifInterface(in).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        switch (o) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                return 90;
            case ExifInterface.ORIENTATION_ROTATE_180:
                return 180;
            case ExifInterface.ORIENTATION_ROTATE_270:
                return 270;
            default:
                return 0;
        }
    }

    private static InputStream open(Context ctx, Uri src) throws IOException {
        InputStream in = ctx.getContentResolver().openInputStream(src);
        if (in == null) {
            throw new IOException("Bildquelle nicht lesbar");
        }
        return in;
    }
}
