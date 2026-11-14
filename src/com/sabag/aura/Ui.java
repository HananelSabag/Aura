package com.sabag.aura;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.PathInterpolator;

/** Shared measurements, fonts, colors and motion curves. The TV UI is 960x540 dp (1920x1080 px at 2x). */
final class Ui {
    static float density = 2f;
    static boolean rtl = true;
    /**
     * Lite mode for weak TVs (the Xiaomi: 2 GB, Android 11, Mali-450): a still background, no
     * shadows, no sweeps or entrance choreography, and every animation short and spring-free.
     * Automatic below 2.5 GB of RAM; the "lite" preference overrides it.
     */
    static boolean lite;
    private static AssetManager assets;
    private static final Typeface[] FONTS = new Typeface[10];

    // Safe area (5% overscan guide): every element stays inside it.
    static final float SAFE_X = 48, SAFE_Y = 27;

    static final int WHITE = 0xFFFFFFFF;
    static final int TEXT_2 = 0xC8FFFFFF;
    static final int TEXT_3 = 0x8CFFFFFF;
    static final int INK = 0xFF0B0D12;      // text on white focus surfaces
    static final int INK_2 = 0xA60B0D12;
    static final int DANGER = 0xFFFF5A5F;
    static final int OK_GREEN = 0xFF4ADE80;

    /** Material 3 "emphasized decelerate": quick start, long soft landing. */
    static final TimeInterpolator EASE_OUT = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    static final TimeInterpolator EASE = new PathInterpolator(0.3f, 0f, 0f, 1f);
    static final TimeInterpolator EASE_IN = new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);
    /** Springs settled by the end of the animation: about 12% overshoot (lively) and 3% (calm). */
    static final TimeInterpolator SPRING = new Spring(0.55f, 13f);
    static final TimeInterpolator SPRING_SOFT = new Spring(0.75f, 11f);

    static void init(Context c) {
        density = c.getResources().getDisplayMetrics().density;
        rtl = c.getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        assets = c.getAssets();
        android.app.ActivityManager am = c.getSystemService(android.app.ActivityManager.class);
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        boolean weak = am.isLowRamDevice() || mi.totalMem < 2_500L * 1024 * 1024;
        android.content.SharedPreferences p = c.getSharedPreferences("aura", Context.MODE_PRIVATE);
        lite = p.contains("lite") ? p.getBoolean("lite", false) : weak;
    }

    /** Animation length: shorter in lite mode, where every frame costs more. */
    static long ms(long full) { return lite ? Math.max(90, full * 2 / 5) : full; }

    /** Springs need many frames to read well; lite uses a plain ease-out instead. */
    static TimeInterpolator spring() { return lite ? EASE_OUT : SPRING; }
    static TimeInterpolator springSoft() { return lite ? EASE_OUT : SPRING_SOFT; }

    static float dp(float v) { return v * density; }
    static int dpi(float v) { return Math.round(v * density); }

    /** Rubik (variable, Hebrew + Latin) at weight 300..800. */
    static Typeface font(int weight) {
        int i = Math.max(3, Math.min(8, Math.round(weight / 100f)));
        Typeface t = FONTS[i];
        if (t == null) {
            try {
                t = new Typeface.Builder(assets, "fonts/Rubik.ttf")
                        .setFontVariationSettings("'wght' " + (i * 100)).setWeight(i * 100).build();
            } catch (RuntimeException e) {
                t = null;
            }
            if (t == null) t = Typeface.create(Typeface.SANS_SERIF, i * 100, false);
            FONTS[i] = t;
        }
        return t;
    }

    static Paint text(int weight, float sizeDp, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(font(weight));
        p.setTextSize(dp(sizeDp));
        p.setColor(color);
        return p;
    }

    /** x of a box `w` wide that starts `start` px from the reading start edge, in a parent `parentW` wide. */
    static float startX(float start, float w, float parentW) {
        return rtl ? parentW - start - w : start;
    }

    /** Draws text so it begins at `start` from the reading start edge. */
    static Paint.Align startAlign() { return rtl ? Paint.Align.RIGHT : Paint.Align.LEFT; }

    static int alpha(int color, float a) {
        int base = color >>> 24;
        return (color & 0x00FFFFFF) | (Math.round(base * Math.max(0, Math.min(1, a))) << 24);
    }

    static int mix(int a, int b, float t) {
        float u = 1 - t;
        return ((int) (((a >>> 24) & 255) * u + ((b >>> 24) & 255) * t) << 24)
                | ((int) (((a >> 16) & 255) * u + ((b >> 16) & 255) * t) << 16)
                | ((int) (((a >> 8) & 255) * u + ((b >> 8) & 255) * t) << 8)
                | (int) ((a & 255) * u + (b & 255) * t);
    }

    static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    /** Keeps "0.9 GB" in that order inside Hebrew text (a left-to-right isolate). */
    static String ltr(String s) { return "⁦" + s + "⁩"; }

    /** Text that would overflow `max` px is cut with an ellipsis, so labels never spill out of their frame. */
    static String fit(Paint p, String s, float max) {
        if (s == null) return "";
        if (p.measureText(s) <= max) return s;
        int n = p.breakText(s, true, max - p.measureText("…"), null);
        return s.substring(0, Math.max(0, n)).trim() + "…";
    }

    /** Damped spring response normalized to [0,1] time. */
    static final class Spring implements TimeInterpolator {
        private final float zeta, omega, wd;

        Spring(float damping, float frequency) {
            zeta = damping;
            omega = frequency;
            wd = omega * (float) Math.sqrt(1 - zeta * zeta);
        }

        @Override public float getInterpolation(float t) {
            if (t >= 1f) return 1f;
            double e = Math.exp(-zeta * omega * t);
            return (float) (1 - e * (Math.cos(wd * t) + (zeta * omega / wd) * Math.sin(wd * t)));
        }
    }

    private Ui() {}
}
