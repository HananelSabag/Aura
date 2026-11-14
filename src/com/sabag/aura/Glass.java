package com.sabag.aura;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * Liquid-glass surface. It sees through: the background behind it (the light field, or the
 * wallpaper's frosted copy) is drawn inside it, brighter and more saturated; in full mode the rim
 * bends that picture like a lens. Over it a tint, a soft inner sheen, a dark hairline for separation
 * on bright wallpapers and a specular rim that is bright where light hits (top-start, bottom-end).
 * Shaders are rebuilt only on resize; the backdrop is one small bitmap, so this stays cheap.
 */
final class Glass {
    /** What every glass surface sees through: a soft picture of the whole screen. */
    static Bitmap backdrop;
    static int screenW = 1920, screenH = 1080;

    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hair = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint picture = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint lens = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF r = new RectF(), inner = new RectF();
    private final Matrix matrix = new Matrix(), lensMatrix = new Matrix();
    private final int[] loc = new int[2];
    private BitmapShader pictureShader, lensShader;
    private Bitmap shaderFor;
    private float shaderW = -1, shaderH = -1;

    /** Body tint (with alpha) and rim strength 0..1. */
    int tint = 0x1FFFFFFF;
    /** Tint where there is nothing to see through (no backdrop yet). */
    int solidTint = 0;
    float rimStrength = 1f;

    Glass() {
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Ui.dp(1.1f));
        hair.setStyle(Paint.Style.STROKE);
        hair.setStrokeWidth(Ui.dp(1));
        hair.setColor(0x40000000);
        brightness(1.12f);
    }

    Glass tint(int argb) { tint = argb; return this; }

    Glass solid(int argb) { solidTint = argb; return this; }

    /** How the picture behind shows: above 1 luminous (chips, cards), below 1 dark frosted (panels). */
    Glass brightness(float b) {
        picture.setColorFilter(filter(b, 1.35f));
        lens.setColorFilter(filter(b * 1.1f, 1.5f));
        return this;
    }

    private static ColorMatrixColorFilter filter(float brightness, float saturation) {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(saturation);
        ColorMatrix s = new ColorMatrix();
        s.setScale(brightness, brightness, brightness, 1f);
        m.postConcat(s);
        return new ColorMatrixColorFilter(m);
    }

    void draw(Canvas c, View v, float left, float top, float right, float bottom, float radius) {
        float w = right - left, h = bottom - top;
        if (w <= 0 || h <= 0) return;
        if (w != shaderW || h != shaderH) build(w, h);
        c.save();
        c.translate(left, top);
        r.set(0, 0, w, h);
        Bitmap bd = backdrop;
        boolean seeThrough = bd != null && v != null && !bd.isRecycled();
        if (seeThrough) {
            if (shaderFor != bd) {
                shaderFor = bd;
                pictureShader = new BitmapShader(bd, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                lensShader = new BitmapShader(bd, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                picture.setShader(pictureShader);
                lens.setShader(lensShader);
            }
            // Map the whole-screen picture so this surface shows exactly what is behind it.
            v.getLocationInWindow(loc);
            matrix.setScale(screenW / (float) bd.getWidth(), screenH / (float) bd.getHeight());
            matrix.postTranslate(-loc[0] - left, -loc[1] - top);
            pictureShader.setLocalMatrix(matrix);
            c.drawRoundRect(r, radius, radius, picture);
            if (!Ui.lite) {
                // The rim is a lens: the same picture, magnified about the centre, in a band along the edge.
                float band = Math.min(Ui.dp(12), Math.min(w, h) / 4f);
                lensMatrix.set(matrix);
                lensMatrix.postScale(1.08f, 1.08f, w / 2f, h / 2f);
                lensShader.setLocalMatrix(lensMatrix);
                inner.set(band, band, w - band, h - band);
                float ir = Math.max(0, radius - band);
                c.drawDoubleRoundRect(r, radius, radius, inner, ir, ir, lens);
            }
        }
        body.setColor(seeThrough || solidTint == 0 ? tint : solidTint);
        c.drawRoundRect(r, radius, radius, body);
        r.set(Ui.dp(0.8f), Ui.dp(0.8f), w - Ui.dp(0.8f), h - Ui.dp(0.8f));
        c.drawRoundRect(r, radius, radius, sheen);
        r.set(Ui.dp(0.5f), Ui.dp(0.5f), w - Ui.dp(0.5f), h - Ui.dp(0.5f));
        c.drawRoundRect(r, radius, radius, hair);
        rim.setAlpha(Math.round(255 * rimStrength));
        r.set(Ui.dp(0.55f), Ui.dp(0.55f), w - Ui.dp(0.55f), h - Ui.dp(0.55f));
        c.drawRoundRect(r, radius, radius, rim);
        c.restore();
    }

    private void build(float w, float h) {
        shaderW = w;
        shaderH = h;
        float sx = Ui.rtl ? w : 0, ex = Ui.rtl ? 0 : w;
        rim.setShader(new LinearGradient(sx, 0, ex, h,
                new int[]{0xB3FFFFFF, 0x2EFFFFFF, 0x0FFFFFFF, 0x14FFFFFF, 0x73FFFFFF},
                new float[]{0f, 0.28f, 0.5f, 0.72f, 1f}, Shader.TileMode.CLAMP));
        sheen.setShader(new LinearGradient(0, 0, 0, Math.min(h, Ui.dp(90)),
                new int[]{0x24FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
    }
}
