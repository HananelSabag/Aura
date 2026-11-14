package com.sabag.aura;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Line icons drawn with paths on a 24-unit grid: crisp at any size, no image files. */
final class Icons {
    static final int GEAR = 1, WIFI = 2, ETHERNET = 3, WINDOWS = 4, CLOSE = 5, STAR = 6, STAR_OFF = 7,
            MOVE = 8, FOLDER = 9, INFO = 10, TRASH = 11, PLAY = 12, TV = 13, HDMI = 14, PALETTE = 15,
            SPARKLE = 16, TEXT = 17, CLOCK = 18, RESET = 19, LINK = 20, CHEVRON = 21, CHECK = 22,
            MEMORY = 23, PLUS = 24, ANTENNA = 25, ARROW_UP = 26, SETTINGS_SYS = 27, NO_WIFI = 28;

    private static final RectF R = new RectF();
    private static final Path P = new Path();
    private static final Path GEAR_PATH = gear();

    private static Path gear() {
        float[] x = new float[32], y = new float[32];
        float[] offsets = {-17f, -9f, 9f, 17f};
        for (int i = 0; i < 32; i++) {
            double a = Math.toRadians((i / 4) * 45f + offsets[i % 4]);
            float r = i % 4 == 1 || i % 4 == 2 ? 9.6f : 7.2f;
            x[i] = (float) Math.cos(a) * r;
            y[i] = (float) Math.sin(a) * r;
        }
        Path p = new Path();
        p.moveTo((x[31] + x[0]) / 2f, (y[31] + y[0]) / 2f);
        for (int i = 0; i < 32; i++) {
            int n = (i + 1) % 32;
            p.quadTo(x[i], y[i], (x[i] + x[n]) / 2f, (y[i] + y[n]) / 2f);
        }
        p.close();
        return p;
    }

    /** Stroke paint suited to icons of size `sizePx`. */
    static Paint stroke(int color, float sizePx) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(Math.max(1.5f, sizePx / 13f));
        p.setColor(color);
        return p;
    }

    /** Draws `type` centered at (cx, cy) in a square of side s with stroke paint p. `level` is used by WIFI (0..4). */
    static void draw(Canvas c, int type, float cx, float cy, float s, Paint p, int level) {
        float u = s / 24f;
        switch (type) {
            case GEAR: {
                float sw = p.getStrokeWidth();
                c.save();
                c.translate(cx, cy);
                c.scale(u, u);
                p.setStrokeWidth(sw / u);
                c.drawPath(GEAR_PATH, p);
                c.drawCircle(0, 0, 3f, p);
                p.setStrokeWidth(sw);
                c.restore();
                break;
            }
            case WIFI:
            case NO_WIFI: {
                float by = cy + 6.5f * u;
                int alpha = p.getAlpha();
                for (int i = 0; i < 3; i++) {
                    float r = (4.5f + 4.8f * i) * u;
                    R.set(cx - r, by - r, cx + r, by + r);
                    if (type == WIFI && level >= 0 && i >= Math.max(1, level - 1)) p.setAlpha(alpha / 3);
                    c.drawArc(R, 225, 90, false, p);
                    p.setAlpha(alpha);
                }
                dot(c, cx, by, 1.7f * u, p);
                if (type == NO_WIFI) c.drawLine(cx - 8 * u, cy - 8 * u, cx + 8 * u, cy + 8 * u, p);
                break;
            }
            case ETHERNET: {
                R.set(cx - 8 * u, cy - 6 * u, cx + 8 * u, cy + 6 * u);
                c.drawRoundRect(R, 2 * u, 2 * u, p);
                for (int i = -1; i <= 1; i++) c.drawLine(cx + i * 4 * u, cy - 2 * u, cx + i * 4 * u, cy + 2 * u, p);
                break;
            }
            case WINDOWS: { // stacked cards: open apps
                R.set(cx - 7 * u, cy - 5 * u, cx + 9 * u, cy + 7 * u);
                c.drawRoundRect(R, 2.5f * u, 2.5f * u, p);
                P.reset();
                P.moveTo(cx - 10 * u, cy + 3 * u);
                P.lineTo(cx - 10 * u, cy - 6 * u);
                P.quadTo(cx - 10 * u, cy - 8 * u, cx - 8 * u, cy - 8 * u);
                P.lineTo(cx + 5 * u, cy - 8 * u);
                c.drawPath(P, p);
                break;
            }
            case CLOSE:
                c.drawLine(cx - 6 * u, cy - 6 * u, cx + 6 * u, cy + 6 * u, p);
                c.drawLine(cx + 6 * u, cy - 6 * u, cx - 6 * u, cy + 6 * u, p);
                break;
            case STAR:
            case STAR_OFF: {
                P.reset();
                for (int i = 0; i < 10; i++) {
                    double a = Math.toRadians(-90 + i * 36);
                    float r = (i % 2 == 0 ? 9.5f : 4.2f) * u;
                    float x = cx + (float) Math.cos(a) * r, y = cy + 0.8f * u + (float) Math.sin(a) * r;
                    if (i == 0) P.moveTo(x, y); else P.lineTo(x, y);
                }
                P.close();
                c.drawPath(P, p);
                if (type == STAR_OFF) c.drawLine(cx - 9 * u, cy - 9 * u, cx + 9 * u, cy + 9 * u, p);
                break;
            }
            case MOVE: {
                c.drawLine(cx - 9 * u, cy, cx + 9 * u, cy, p);
                c.drawLine(cx - 9 * u, cy, cx - 5.5f * u, cy - 3.5f * u, p);
                c.drawLine(cx - 9 * u, cy, cx - 5.5f * u, cy + 3.5f * u, p);
                c.drawLine(cx + 9 * u, cy, cx + 5.5f * u, cy - 3.5f * u, p);
                c.drawLine(cx + 9 * u, cy, cx + 5.5f * u, cy + 3.5f * u, p);
                break;
            }
            case FOLDER: {
                P.reset();
                P.moveTo(cx - 10 * u, cy + 7 * u);
                P.lineTo(cx - 10 * u, cy - 6 * u);
                P.lineTo(cx - 3.5f * u, cy - 6 * u);
                P.lineTo(cx - 1.5f * u, cy - 3.5f * u);
                P.lineTo(cx + 10 * u, cy - 3.5f * u);
                P.lineTo(cx + 10 * u, cy + 7 * u);
                P.close();
                c.drawPath(P, p);
                break;
            }
            case INFO:
                c.drawCircle(cx, cy, 9.5f * u, p);
                c.drawLine(cx, cy - 0.5f * u, cx, cy + 5 * u, p);
                dot(c, cx, cy - 4.5f * u, 1.3f * u, p);
                break;
            case TRASH: {
                c.drawLine(cx - 8 * u, cy - 5.5f * u, cx + 8 * u, cy - 5.5f * u, p);
                c.drawLine(cx - 2.5f * u, cy - 8 * u, cx + 2.5f * u, cy - 8 * u, p);
                P.reset();
                P.moveTo(cx - 6 * u, cy - 5.5f * u);
                P.lineTo(cx - 5 * u, cy + 8 * u);
                P.lineTo(cx + 5 * u, cy + 8 * u);
                P.lineTo(cx + 6 * u, cy - 5.5f * u);
                c.drawPath(P, p);
                break;
            }
            case PLAY: {
                P.reset();
                P.moveTo(cx - 4.5f * u, cy - 7.5f * u);
                P.lineTo(cx + 7.5f * u, cy);
                P.lineTo(cx - 4.5f * u, cy + 7.5f * u);
                P.close();
                c.drawPath(P, p);
                break;
            }
            case TV:
                R.set(cx - 10 * u, cy - 7 * u, cx + 10 * u, cy + 5 * u);
                c.drawRoundRect(R, 2 * u, 2 * u, p);
                c.drawLine(cx - 5 * u, cy + 8.5f * u, cx + 5 * u, cy + 8.5f * u, p);
                break;
            case HDMI: {
                P.reset();
                P.moveTo(cx - 9.5f * u, cy - 4.5f * u);
                P.lineTo(cx + 9.5f * u, cy - 4.5f * u);
                P.lineTo(cx + 9.5f * u, cy + 1 * u);
                P.lineTo(cx + 6.5f * u, cy + 4.5f * u);
                P.lineTo(cx - 6.5f * u, cy + 4.5f * u);
                P.lineTo(cx - 9.5f * u, cy + 1 * u);
                P.close();
                c.drawPath(P, p);
                c.drawLine(cx - 5 * u, cy - 0.5f * u, cx + 5 * u, cy - 0.5f * u, p);
                break;
            }
            case PALETTE: {
                P.reset();
                P.addCircle(cx, cy, 9.5f * u, Path.Direction.CW);
                c.drawPath(P, p);
                dot(c, cx - 4 * u, cy - 3 * u, 1.6f * u, p);
                dot(c, cx + 1 * u, cy - 5.5f * u, 1.6f * u, p);
                dot(c, cx + 5 * u, cy - 1.5f * u, 1.6f * u, p);
                c.drawCircle(cx - 2 * u, cy + 4.5f * u, 2.2f * u, p);
                break;
            }
            case SPARKLE: {
                P.reset();
                P.moveTo(cx, cy - 9.5f * u);
                P.quadTo(cx + 1.2f * u, cy - 1.2f * u, cx + 9.5f * u, cy);
                P.quadTo(cx + 1.2f * u, cy + 1.2f * u, cx, cy + 9.5f * u);
                P.quadTo(cx - 1.2f * u, cy + 1.2f * u, cx - 9.5f * u, cy);
                P.quadTo(cx - 1.2f * u, cy - 1.2f * u, cx, cy - 9.5f * u);
                c.drawPath(P, p);
                break;
            }
            case TEXT:
                c.drawLine(cx - 8 * u, cy - 7 * u, cx + 8 * u, cy - 7 * u, p);
                c.drawLine(cx, cy - 7 * u, cx, cy + 8 * u, p);
                break;
            case CLOCK:
                c.drawCircle(cx, cy, 9.5f * u, p);
                c.drawLine(cx, cy, cx, cy - 5.5f * u, p);
                c.drawLine(cx, cy, cx + 4 * u, cy + 2 * u, p);
                break;
            case RESET: {
                R.set(cx - 8 * u, cy - 8 * u, cx + 8 * u, cy + 8 * u);
                c.drawArc(R, -60, 300, false, p);
                float ax = cx + 8 * u * (float) Math.cos(Math.toRadians(-60));
                float ay = cy + 8 * u * (float) Math.sin(Math.toRadians(-60));
                c.drawLine(ax, ay, ax - 4.5f * u, ay - 0.5f * u, p);
                c.drawLine(ax, ay, ax + 0.5f * u, ay + 4.5f * u, p);
                break;
            }
            case LINK: {
                R.set(cx - 10 * u, cy - 4 * u, cx + 1 * u, cy + 4 * u);
                c.drawRoundRect(R, 4 * u, 4 * u, p);
                R.set(cx - 1 * u, cy - 4 * u, cx + 10 * u, cy + 4 * u);
                c.drawRoundRect(R, 4 * u, 4 * u, p);
                break;
            }
            case CHEVRON: { // points toward the reading end
                float d = Ui.rtl ? -1 : 1;
                c.drawLine(cx - 2.5f * u * d, cy - 6 * u, cx + 3 * u * d, cy, p);
                c.drawLine(cx + 3 * u * d, cy, cx - 2.5f * u * d, cy + 6 * u, p);
                break;
            }
            case CHECK:
                c.drawLine(cx - 6.5f * u, cy + 0.5f * u, cx - 2 * u, cy + 5 * u, p);
                c.drawLine(cx - 2 * u, cy + 5 * u, cx + 7.5f * u, cy - 5 * u, p);
                break;
            case MEMORY: {
                R.set(cx - 7 * u, cy - 7 * u, cx + 7 * u, cy + 7 * u);
                c.drawRoundRect(R, 2 * u, 2 * u, p);
                for (int i = -1; i <= 1; i++) {
                    float k = i * 4 * u;
                    c.drawLine(cx + k, cy - 7 * u, cx + k, cy - 10 * u, p);
                    c.drawLine(cx + k, cy + 7 * u, cx + k, cy + 10 * u, p);
                    c.drawLine(cx - 7 * u, cy + k, cx - 10 * u, cy + k, p);
                    c.drawLine(cx + 7 * u, cy + k, cx + 10 * u, cy + k, p);
                }
                break;
            }
            case PLUS:
                c.drawLine(cx - 7 * u, cy, cx + 7 * u, cy, p);
                c.drawLine(cx, cy - 7 * u, cx, cy + 7 * u, p);
                break;
            case ANTENNA:
                c.drawLine(cx, cy - 2 * u, cx - 7 * u, cy - 10 * u, p);
                c.drawLine(cx, cy - 2 * u, cx + 7 * u, cy - 10 * u, p);
                R.set(cx - 10 * u, cy - 2 * u, cx + 10 * u, cy + 9 * u);
                c.drawRoundRect(R, 2 * u, 2 * u, p);
                break;
            case ARROW_UP:
                c.drawLine(cx, cy + 8 * u, cx, cy - 8 * u, p);
                c.drawLine(cx, cy - 8 * u, cx - 5.5f * u, cy - 2.5f * u, p);
                c.drawLine(cx, cy - 8 * u, cx + 5.5f * u, cy - 2.5f * u, p);
                break;
            case SETTINGS_SYS: { // sliders
                float[] knob = {-3, 4, -1};
                for (int i = 0; i < 3; i++) {
                    float y = cy + (i - 1) * 6 * u;
                    c.drawLine(cx - 9 * u, y, cx + 9 * u, y, p);
                    dot(c, cx + knob[i] * u, y, 2.3f * u, p);
                }
                break;
            }
            default:
                break;
        }
    }

    private static void dot(Canvas c, float x, float y, float r, Paint p) {
        Paint.Style st = p.getStyle();
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(x, y, r, p);
        p.setStyle(st);
    }

    private Icons() {}
}
