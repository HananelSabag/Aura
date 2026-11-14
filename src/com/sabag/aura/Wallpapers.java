package com.sabag.aura;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Photo backgrounds. Wallpaper collections are copied to the TV from the PC already sized for it
 * (tools\wallpapers.ps1 → Android/data/com.sabag.aura/files/wallpapers/(collection)/, or
 * Pictures/Aura/). A picked picture is prepared once, off the UI thread: darkened where the home's
 * text sits (no per-frame overlay — this TV's GPU can't afford one), plus a small frosted copy that
 * the glass surfaces show through.
 */
final class Wallpapers {
    private Wallpapers() {}

    static List<File> list(Context c) {
        List<File> out = new ArrayList<>();
        File own = c.getExternalFilesDir("wallpapers");
        collect(own, out);
        collect(new File(android.os.Environment.getExternalStorageDirectory(), "Pictures/Aura"), out);
        out.sort((a, b) -> a.getPath().compareToIgnoreCase(b.getPath()));
        return out;
    }

    private static void collect(File dir, List<File> out) {
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return;
        Arrays.sort(files);
        for (File f : files) {
            if (f.isDirectory()) collect(f, out);
            else {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")) out.add(f);
            }
        }
    }

    /** A collection's name for the screen: its folder ("dragonball" → "דרגון בול", "family" → "המשפחה"). */
    static String collectionName(File picture) {
        String n = picture == null || picture.getParentFile() == null ? "" : picture.getParentFile().getName();
        switch (n.toLowerCase()) {
            case "dragonball": return "דרגון בול";
            case "family": case "משפחה": return "המשפחה";
            case "aura": case "wallpapers": case "": return "טפטים";
            default: return n;
        }
    }

    /** The picture covering a w×h screen (centre crop), decoded no larger than needed. */
    static Bitmap load(File f, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap src = BitmapFactory.decodeFile(f.getPath(), o);
        if (src == null) return null;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        int cw = Math.round(w / scale), ch = Math.round(h / scale);
        int cx = (src.getWidth() - cw) / 2, cy = (src.getHeight() - ch) / 2;
        new Canvas(out).drawBitmap(src, new Rect(cx, cy, cx + cw, cy + ch), new Rect(0, 0, w, h),
                new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
        src.recycle();
        return out;
    }

    static Bitmap thumb(File f) {
        return load(f, Ui.dpi(120), Ui.dpi(68));
    }

    /**
     * Bakes legibility into the pixels: dark behind the clock, a lighter band for the picture to show,
     * darker toward the rows, and a little extra behind the hero title at the reading start.
     */
    static void bake(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            float v = y / (float) (h - 1);
            float k = v < 0.28f ? 0.55f + 0.30f * (v / 0.28f)
                    : v < 0.45f ? 0.85f + 0.03f * ((v - 0.28f) / 0.17f)
                    : 0.88f - 0.48f * ((v - 0.45f) / 0.55f);
            float heroBand = v > 0.12f && v < 0.42f ? (float) Math.sin(Math.PI * (v - 0.12f) / 0.30f) : 0f;
            b.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                float u = x / (float) (w - 1);
                float start = Ui.rtl ? u : 1f - u; // 1 at the reading start
                float kk = k * (1f - 0.30f * heroBand * Math.max(0f, (start - 0.45f) / 0.55f));
                int c = row[x];
                int r = (c >> 16) & 255, g = (c >> 8) & 255, bl = c & 255;
                int l = (r * 77 + g * 150 + bl * 29) >> 8; // saturation ×0.92 keeps the art calmer behind text
                r = Math.round((l + (r - l) * 0.92f) * kk);
                g = Math.round((l + (g - l) * 0.92f) * kk);
                bl = Math.round((l + (bl - l) * 0.92f) * kk);
                row[x] = 0xFF000000 | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, bl);
            }
            b.setPixels(row, 0, w, 0, y, w, 1);
        }
    }

    /** A small, heavily blurred copy for glass to sample (≈ a 60 px blur on the 1080p screen). */
    static Bitmap frost(Bitmap b) {
        int w = Math.max(8, b.getWidth() / 8), h = Math.max(8, b.getHeight() / 8);
        Bitmap s = Bitmap.createScaledBitmap(b, w, h, true);
        if (!s.isMutable()) s = s.copy(Bitmap.Config.ARGB_8888, true);
        int[] px = new int[w * h], tmp = new int[w * h];
        s.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < 3; i++) {
            blur(px, tmp, w, h, 3, true);
            blur(tmp, px, w, h, 3, false);
        }
        s.setPixels(px, 0, w, 0, 0, w, h);
        return s;
    }

    private static void blur(int[] src, int[] dst, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h, step = horizontal ? 1 : w, div = r * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int base = horizontal ? line * w : line;
            int ra = 0, ga = 0, ba = 0;
            for (int k = -r; k <= r; k++) {
                int c = src[base + Math.max(0, Math.min(len - 1, k)) * step];
                ra += (c >> 16) & 255; ga += (c >> 8) & 255; ba += c & 255;
            }
            for (int i = 0; i < len; i++) {
                dst[base + i * step] = 0xFF000000 | ((ra / div) << 16) | ((ga / div) << 8) | (ba / div);
                int out = src[base + Math.max(0, Math.min(len - 1, i - r)) * step];
                int in = src[base + Math.max(0, Math.min(len - 1, i + r + 1)) * step];
                ra += ((in >> 16) & 255) - ((out >> 16) & 255);
                ga += ((in >> 8) & 255) - ((out >> 8) & 255);
                ba += (in & 255) - (out & 255);
            }
        }
    }
}
