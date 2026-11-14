package com.sabag.aura;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

import java.util.Random;

/**
 * The living background: four soft light blobs drifting over a dark base, tinted by the selected
 * palette or by the focused app ("app colors").
 *
 * This TV's GPU drops to 7-15 fps on any full-screen gradient, and Skia's CPU gradients are too slow
 * too, so the light field is computed directly: a smooth (1 - d²/r²)² falloff per blob at 384x216,
 * with a tiny static dither (±1 level, invisible) so dark gradients never band. The GPU only scales
 * the image up (bilinear), which is cheap and stays smooth on a 75" screen.
 */
final class Ambient extends View implements Choreographer.FrameCallback {
    static final String DYNAMIC = "dynamic", PHOTO = "photo", WALL = "wall";
    static final String[] IDS = {DYNAMIC, "aurora", "sunset", "ocean", "rose", "forest", "amber", "night"};
    static final String[] NAMES = {"צבעי האפליקציה", "זוהר צפוני", "שקיעה", "אוקיינוס", "ורוד", "יער", "ענבר", "לילה"};

    static int[] preset(String id) {
        switch (id) {
            case "sunset": return new int[]{0xFFFF6A3D, 0xFFE0218A, 0xFF6A2CFF, 0xFFFFB347};
            case "ocean":  return new int[]{0xFF00B4D8, 0xFF1E5BFF, 0xFF20E3B2, 0xFF5B2EFF};
            case "rose":   return new int[]{0xFFFF4F8B, 0xFFB5179E, 0xFFFF9EBB, 0xFF7B2CBF};
            case "forest": return new int[]{0xFF2DC653, 0xFF0B7A5E, 0xFFB5E550, 0xFF1B8EAA};
            case "amber":  return new int[]{0xFFFFA62B, 0xFFE85D04, 0xFFFFD166, 0xFF9D0208};
            case "night":  return new int[]{0xFF27408B, 0xFF3B2A6E, 0xFF14475C, 0xFF2A2F55};
            default:       return new int[]{0xFF19D3C5, 0xFF7C4DFF, 0xFF2F7BFF, 0xFFFF4FA3}; // aurora
        }
    }

    // Lite renders a smaller field (it is still smooth when scaled up) and only when the colors change.
    private final int SW = Ui.lite ? 320 : 384, SH = Ui.lite ? 180 : 216;
    private static final float BASE_R = 5, BASE_G = 6, BASE_B = 10;
    private static final float[][] BLOB = { // x, y (fractions of the screen), radius (fraction of width), strength
            {0.22f, 0.14f, 0.62f, 0.72f},
            {0.84f, 0.32f, 0.52f, 0.58f},
            {0.50f, 0.98f, 0.58f, 0.56f},
            {0.06f, 0.82f, 0.44f, 0.50f},
    };
    // Visible but calm drift: each blob travels about a fifth of the screen on its own slow loop.
    private static final float[][] DRIFT = { // amplitude x, y (fractions), period x, y (s), phase
            {0.20f, 0.13f, 19f, 15f, 0.0f},
            {0.16f, 0.17f, 17f, 23f, 1.7f},
            {0.22f, 0.10f, 23f, 14f, 3.1f},
            {0.14f, 0.15f, 15f, 19f, 4.4f},
    };

    private final Bitmap small = Bitmap.createBitmap(SW, SH, Bitmap.Config.ARGB_8888);
    private final int[] pixels = new int[SW * SH];
    private final float[] dither = new float[SW * SH];
    private final float[] rowShade = new float[SH];
    private final float[] fr = new float[SW * SH], fg = new float[SW * SH], fb = new float[SW * SH];
    private final Paint up = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint photoPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final int[] from = preset("aurora").clone(), to = from.clone(), cur = from.clone();
    private ValueAnimator colorAnim;
    private Bitmap photo;
    private final Rect src = new Rect(0, 0, SW, SH);
    private final RectF dst = new RectF();
    private boolean motion = true, running, frameQueued;
    private long lastFrame;
    private final long epoch = SystemClock.uptimeMillis() - 120_000; // start mid-drift
    private Runnable onFrame;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService worker = Ui.lite ? java.util.concurrent.Executors.newSingleThreadExecutor() : null;
    private boolean rendering, dirty;

    Ambient(Context c) {
        super(c);
        // Triangular dither in [-1, 1] levels: breaks 8-bit steps, far below what the eye can see as grain.
        Random rnd = new Random(11);
        for (int i = 0; i < dither.length; i++) dither[i] = rnd.nextFloat() + rnd.nextFloat() - 1f;
        // Darker at the top (clock) and much darker at the bottom (rows), as before.
        for (int y = 0; y < SH; y++) {
            float v = y / (float) (SH - 1), k = 1f;
            if (v < 0.34f) k *= 1f - 0.6f * (1f - v / 0.34f);
            if (v > 0.40f) k *= 1f - 0.72f * ((v - 0.40f) / 0.60f);
            rowShade[y] = k;
        }
        render(cur, seconds());
        small.setPixels(pixels, 0, SW, 0, 0, SW, SH);
    }

    /** Called on every drawn frame (lets the TV illustration share the same motion). */
    void setOnFrame(Runnable r) { onFrame = r; }

    int[] colors() { return cur; }

    /** The light field as drawn (glass sees through it). */
    Bitmap frame() { return small; }

    float seconds() { return (SystemClock.uptimeMillis() - epoch) / 1000f; }

    void setMotion(boolean on) {
        motion = on;
        if (on) kick();
        else refresh();
    }

    void setPhoto(Bitmap b) {
        photo = b;
        if (b == null) kick();
        invalidate();
    }

    /** Crossfades to new blob colors. */
    void setColors(int[] c, boolean animate) {
        if (c == null || c.length < 4) return;
        if (colorAnim != null) colorAnim.cancel();
        System.arraycopy(cur, 0, from, 0, 4);
        System.arraycopy(c, 0, to, 0, 4);
        if (!animate || !isAttachedToWindow()) {
            System.arraycopy(to, 0, cur, 0, 4);
            refresh();
            return;
        }
        colorAnim = ValueAnimator.ofFloat(0f, 1f).setDuration(Ui.ms(750));
        colorAnim.setInterpolator(Ui.EASE);
        colorAnim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            for (int i = 0; i < 4; i++) cur[i] = Ui.mix(from[i], to[i], t);
            if (!motion || !running) refresh();
        });
        colorAnim.start();
    }

    private void refresh() {
        if (!Ui.lite) {
            render(cur, seconds());
            small.setPixels(pixels, 0, SW, 0, 0, SW, SH);
            invalidate();
            return;
        }
        // Lite: compute on a worker so moving between apps never waits for the background.
        if (rendering) {
            dirty = true;
            return;
        }
        rendering = true;
        final int[] c = cur.clone();
        final float t = seconds();
        worker.execute(() -> {
            render(c, t);
            main.post(() -> {
                small.setPixels(pixels, 0, SW, 0, 0, SW, SH);
                invalidate();
                rendering = false;
                if (dirty) {
                    dirty = false;
                    refresh();
                }
            });
        });
    }

    void start() {
        running = true;
        kick();
    }

    void stop() {
        running = false;
        if (frameQueued) Choreographer.getInstance().removeFrameCallback(this);
        frameQueued = false;
    }

    private void kick() {
        if (running && motion && !frameQueued) {
            frameQueued = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override public void doFrame(long nanos) {
        frameQueued = false;
        if (!running || !motion || photo != null) return;
        if (nanos - lastFrame >= 32_000_000L) { // ~30 fps
            lastFrame = nanos;
            refresh();
            if (onFrame != null) onFrame.run();
        }
        kick();
    }

    /** Computes the light field: base color, then each blob composited over it, shaded per row, dithered. */
    private void render(int[] colors, float t) {
        int n = SW * SH;
        java.util.Arrays.fill(fr, BASE_R);
        java.util.Arrays.fill(fg, BASE_G);
        java.util.Arrays.fill(fb, BASE_B);
        for (int i = 0; i < 4; i++) {
            float[] b = BLOB[i], d = DRIFT[i];
            float cx = SW * (b[0] + d[0] * (float) Math.sin(2 * Math.PI * t / d[2] + d[4]));
            float cy = SH * (b[1] + d[1] * (float) Math.cos(2 * Math.PI * t / d[3] + d[4] * 0.7f));
            float r = SW * b[2] * (1f + 0.10f * (float) Math.sin(2 * Math.PI * t / 11f + i * 1.3f));
            int col = colors[i];
            float cr = (col >> 16) & 255, cg = (col >> 8) & 255, cb = col & 255, s = b[3];
            float inv = 1f / (r * r);
            int x0 = Math.max(0, (int) (cx - r)), x1 = Math.min(SW - 1, (int) (cx + r) + 1);
            int y0 = Math.max(0, (int) (cy - r)), y1 = Math.min(SH - 1, (int) (cy + r) + 1);
            for (int y = y0; y <= y1; y++) {
                float dy = y - cy, dy2 = dy * dy;
                int row = y * SW;
                for (int x = x0; x <= x1; x++) {
                    float dx = x - cx;
                    float k = 1f - (dx * dx + dy2) * inv;
                    if (k <= 0f) continue;
                    float a = s * k * k;
                    int p = row + x;
                    fr[p] += (cr - fr[p]) * a;
                    fg[p] += (cg - fg[p]) * a;
                    fb[p] += (cb - fb[p]) * a;
                }
            }
        }
        for (int y = 0; y < SH; y++) {
            float k = rowShade[y];
            int row = y * SW;
            for (int x = 0; x < SW; x++) {
                int p = row + x;
                float dz = dither[p];
                int r = (int) (fr[p] * k + dz + 0.5f), g = (int) (fg[p] * k + dz + 0.5f), b = (int) (fb[p] * k + dz + 0.5f);
                r = r < 0 ? 0 : r > 255 ? 255 : r;
                g = g < 0 ? 0 : g > 255 ? 255 : g;
                b = b < 0 ? 0 : b > 255 ? 255 : b;
                pixels[p] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (photo != null) {
            drawPhoto(c, w, h, seconds());
        } else {
            dst.set(0, 0, w, h);
            c.drawBitmap(small, src, dst, up);
        }
    }

    /** Pictures are prepared once (sized, darkened where the text sits): one still bitmap per frame. */
    private void drawPhoto(Canvas c, int w, int h, float t) {
        dst.set(0, 0, w, h);
        c.drawBitmap(photo, null, dst, photoPaint);
    }
}
