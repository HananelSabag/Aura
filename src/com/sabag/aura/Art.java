package com.sabag.aura;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/** Card art and color extraction. All bitmaps are 16:9, made once per app (off the main thread). */
final class Art {
    static final int W = 480, H = 270;
    static final int[] NEUTRAL = {0xFF3D6BFF, 0xFF7C4DFF, 0xFF00B8D4, 0xFF5E7CFF};

    /** The banner bitmap itself when possible (no copy), otherwise the drawable rendered at 480x270. */
    static Bitmap banner(Drawable d) {
        if (d instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) d).getBitmap();
            if (b != null && b.getConfig() != Bitmap.Config.HARDWARE && b.getWidth() >= 160) {
                // Some apps ship 1280x720 banners; cards never need more than 480 px wide.
                if (b.getWidth() <= W) return b;
                return Bitmap.createScaledBitmap(b, W, Math.max(1, Math.round(b.getHeight() * (float) W / b.getWidth())), true);
            }
        }
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, W, H);
        d.draw(c);
        return b;
    }

    /** For apps without a TV banner: their icon over a gradient in the icon's own colors, with the name. */
    static Bitmap fromIcon(Drawable icon, String label, int[] pal) {
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int a = pal == null ? 0xFF2A3350 : darken(pal[0], 0.42f);
        int z = pal == null ? 0xFF161B2B : darken(pal[1], 0.22f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, W, H, a, z, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        p.setShader(new RadialGradient(W * 0.5f, H * 0.38f, W * 0.42f, 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        p.setShader(null);
        int s = 112;
        int top = label == null ? (H - s) / 2 : 46;
        icon.setBounds((W - s) / 2, top, (W + s) / 2, top + s);
        icon.draw(c);
        if (label != null) {
            Paint t = Ui.text(500, 0, 0xF2FFFFFF);
            t.setTextSize(31);
            t.setTextAlign(Paint.Align.CENTER);
            c.drawText(Ui.fit(t, label, W - 48), W / 2f, top + s + 52, t);
        }
        return b;
    }

    /** HDMI / TV source card. */
    static Bitmap input(String label, boolean tuner, boolean connected) {
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, W, H, 0xFF2B3446, 0xFF121722, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        p.setShader(new RadialGradient(W * 0.5f, 0, W * 0.6f, connected ? 0x3360E0A0 : 0x22FFFFFF, 0, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        Paint ic = Icons.stroke(0xEFFFFFFF, 96);
        ic.setStrokeWidth(7);
        Icons.draw(c, tuner ? Icons.ANTENNA : Icons.HDMI, W / 2f, 104, 96, ic, 0);
        Paint t = Ui.text(600, 0, 0xFFFFFFFF);
        t.setTextSize(38);
        t.setTextAlign(Paint.Align.CENTER);
        c.drawText(Ui.fit(t, label, W - 60), W / 2f, 206, t);
        if (connected) {
            p.setShader(null);
            p.setColor(Ui.OK_GREEN);
            c.drawCircle(W - 34, 34, 9, p);
        }
        return b;
    }

    /**
     * Picks the two strongest hues of the art (weighted by saturation and brightness) and builds a
     * four-color ambient palette plus a glow color: {c0, c1, c2, c3, glow}. Null for gray art.
     */
    static int[] palette(Bitmap src) {
        if (src == null || src.getConfig() == Bitmap.Config.HARDWARE) return null;
        Bitmap s = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(s);
        Paint f = new Paint(Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(src, null, new Rect(0, 0, 32, 18), f);
        int[] px = new int[32 * 18];
        s.getPixels(px, 0, 32, 0, 0, 32, 18);
        s.recycle();
        float[] w = new float[12], r = new float[12], g = new float[12], bl = new float[12];
        float[] hsv = new float[3];
        float total = 0;
        for (int v : px) {
            if ((v >>> 24) < 128) continue;
            Color.colorToHSV(v, hsv);
            if (hsv[1] < 0.28f || hsv[2] < 0.22f) continue;
            float wt = hsv[1] * hsv[1] * hsv[2];
            int k = ((int) (hsv[0] / 30f)) % 12;
            w[k] += wt;
            r[k] += Color.red(v) * wt;
            g[k] += Color.green(v) * wt;
            bl[k] += Color.blue(v) * wt;
            total += wt;
        }
        if (total < 6f) return null;
        int t1 = 0;
        for (int k = 1; k < 12; k++) if (w[k] > w[t1]) t1 = k;
        int t2 = -1;
        for (int k = 0; k < 12; k++) {
            int d = Math.abs(k - t1);
            d = Math.min(d, 12 - d);
            if (d >= 2 && w[k] >= w[t1] * 0.18f && (t2 < 0 || w[k] > w[t2])) t2 = k;
        }
        int main = Color.rgb((int) (r[t1] / w[t1]), (int) (g[t1] / w[t1]), (int) (bl[t1] / w[t1]));
        int c1 = vivid(main, 0.84f);
        // Companions lean toward cool hues (red → pink → violet, green → cyan → blue), which reads
        // as a rich glow instead of the muddy brown that red + orange + yellow mixes into.
        Color.colorToHSV(c1, hsv);
        float h = hsv[0];
        float d1, d2;
        if (h < 50 || h >= 300) { d1 = -38; d2 = -78; }
        else if (h < 170) { d1 = 42; d2 = 92; }
        else { d1 = 42; d2 = -40; }
        int c2 = t2 >= 0 ? vivid(Color.rgb((int) (r[t2] / w[t2]), (int) (g[t2] / w[t2]), (int) (bl[t2] / w[t2])), 0.74f)
                : shift(c1, d1, 0.74f);
        return new int[]{c1, c2, shift(c1, t2 >= 0 ? d1 : d2, 0.68f), shift(c1, t2 >= 0 ? d2 : d1 * 0.5f, 0.54f), vivid(main, 1f)};
    }

    static int[] ambient(int[] pal) {
        return pal == null ? NEUTRAL : new int[]{pal[0], pal[1], pal[2], pal[3]};
    }

    private static int vivid(int color, float value) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.max(hsv[1], 0.62f);
        hsv[2] = value;
        return Color.HSVToColor(hsv);
    }

    private static int shift(int color, float degrees, float value) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[0] = (hsv[0] + degrees + 360f) % 360f;
        hsv[2] = value;
        return Color.HSVToColor(hsv);
    }

    static int darken(int color, float value) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.min(hsv[2], value);
        return Color.HSVToColor(hsv);
    }

    /**
     * The item's art at about `w` px wide. The GPU's plain bilinear filter makes text shimmer and look
     * jagged when it shrinks an image by more than ~25%, so smaller copies are made once, in halving
     * steps (each step averages 2x2 pixels), and cached on the item.
     */
    static Bitmap scaled(Item it, int w) {
        Bitmap b = it.art;
        if (b == null || w <= 0 || b.getWidth() <= w * 1.3f) return b;
        Bitmap cached = it.scaled.get(w);
        if (cached != null) return cached;
        Bitmap cur = b;
        while (cur.getWidth() / 2 >= w) {
            Bitmap half = Bitmap.createScaledBitmap(cur, cur.getWidth() / 2, Math.max(1, cur.getHeight() / 2), true);
            if (cur != b) cur.recycle();
            cur = half;
        }
        if (cur.getWidth() != w) {
            Bitmap fin = Bitmap.createScaledBitmap(cur, w, Math.max(1, Math.round(cur.getHeight() * (float) w / cur.getWidth())), true);
            if (cur != b) cur.recycle();
            cur = fin;
        }
        it.scaled.put(w, cur);
        return cur;
    }

    /** Center-crop rectangle of a bitmap for a box of aspect w:h. */
    static void crop(Bitmap b, float w, float h, RectF out) {
        float bw = b.getWidth(), bh = b.getHeight();
        float scale = Math.max(w / bw, h / bh);
        float cw = w / scale, ch = h / scale;
        out.set((bw - cw) / 2f, (bh - ch) / 2f, (bw + cw) / 2f, (bh + ch) / 2f);
    }

    private Art() {}
}
