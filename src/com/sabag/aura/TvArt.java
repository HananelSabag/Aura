package com.sabag.aura;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * A small living portrait of this TV (TCL, 75", thin bezel, blade feet): its screen shows a miniature
 * of the home — the same drifting colors and a row of tiles — so it moves with the real background.
 */
final class TvArt extends View {
    private final Ambient ambient;
    private int[] tiles = {0xFFFF3D3D, 0xFFE50914, 0xFF00C2FF, 0xFF2F7BFF};
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint screen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blob = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint glare = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint foot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint floor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tile = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint logo = Ui.text(600, 5, 0x99FFFFFF);
    private final Paint led = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path p = new Path();
    private final Matrix m = new Matrix();
    private RadialGradient unit;

    TvArt(Context c, Ambient ambient) {
        super(c);
        this.ambient = ambient;
        logo.setTextAlign(Paint.Align.CENTER);
        logo.setLetterSpacing(0.2f);
        unit = new RadialGradient(0, 0, 1, 0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
    }

    void setTiles(int[] colors) {
        if (colors != null && colors.length > 0) tiles = colors;
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float sh = w * 9f / 16f;
        frame.setShader(new LinearGradient(0, 0, 0, sh, 0xFF3A3F49, 0xFF16181D, Shader.TileMode.CLAMP));
        glare.setShader(new LinearGradient(0, 0, w * 0.6f, sh, 0x26FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        foot.setShader(new LinearGradient(0, sh, 0, h, 0xFF4A4F59, 0xFF1C1F25, Shader.TileMode.CLAMP));
        floor.setShader(new RadialGradient(w / 2f, h - Ui.dp(3), w * 0.55f, 0x73000000, 0x00000000, Shader.TileMode.CLAMP));
        logo.setTextSize(Math.max(Ui.dp(4), w / 46f));
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float sh = w * 9f / 16f;
        // floor shadow
        c.save();
        c.scale(1f, 0.16f, w / 2f, h - Ui.dp(3));
        c.drawCircle(w / 2f, h - Ui.dp(3), w * 0.55f, floor);
        c.restore();
        // two slim feet near the ends, leaning outward, each on a small floor plate
        float bez = Math.max(Ui.dp(1.6f), w / 140f), chin = bez * 3.2f;
        float legTop = sh - chin * 0.5f, floorY = h - Ui.dp(5);
        for (int side = 0; side < 2; side++) {
            float x = side == 0 ? w * 0.12f : w * 0.88f;
            float dir = side == 0 ? -1 : 1;
            float lean = dir * w * 0.03f;
            p.reset();
            p.moveTo(x - Ui.dp(1.1f), legTop);
            p.lineTo(x + Ui.dp(1.1f), legTop);
            p.lineTo(x + lean + Ui.dp(1.5f), floorY);
            p.lineTo(x + lean - Ui.dp(1.5f), floorY);
            p.close();
            c.drawPath(p, foot);
            r.set(x + lean - w * 0.045f, floorY - Ui.dp(1.2f), x + lean + w * 0.045f, floorY + Ui.dp(1.2f));
            c.drawRoundRect(r, Ui.dp(1.2f), Ui.dp(1.2f), foot);
        }
        // body
        r.set(0, 0, w, sh);
        float rad = Math.max(Ui.dp(2), w / 90f);
        c.drawRoundRect(r, rad, rad, frame);
        r.set(bez, bez, w - bez, sh - chin);
        c.save();
        c.clipRect(r);
        screen.setColor(0xFF06070B);
        c.drawRect(r, screen);
        drawScreen(c, r);
        c.drawRect(r, glare);
        c.restore();
        logo.setTextSize(chin * 0.62f);
        c.drawText(Device.get().logo, w / 2f, sh - chin * 0.26f, logo);
        float pulse = 0.55f + 0.45f * (float) Math.sin(ambient.seconds() * 1.3f);
        led.setColor(Ui.alpha(0xFFFFFFFF, 0.25f + 0.35f * pulse));
        c.drawCircle(w / 2f + chin * 1.6f, sh - chin * 0.45f, Ui.dp(0.7f), led);
    }

    private void drawScreen(Canvas c, RectF s) {
        float t = ambient.seconds();
        int[] col = ambient.colors();
        float sw = s.width(), shh = s.height();
        for (int i = 0; i < 4; i++) {
            float cx = s.left + sw * (0.2f + 0.6f * (i % 2) + 0.12f * (float) Math.sin(t / (6 + i) + i));
            float cy = s.top + shh * (0.25f + 0.5f * (i / 2) + 0.12f * (float) Math.cos(t / (7 + i) + i));
            float rr = sw * 0.55f;
            m.setScale(rr, rr);
            m.postTranslate(cx, cy);
            unit.setLocalMatrix(m);
            blob.setShader(unit);
            blob.setColorFilter(new android.graphics.PorterDuffColorFilter(Ui.alpha(col[i], 0.75f), android.graphics.PorterDuff.Mode.MULTIPLY));
            c.drawCircle(cx, cy, rr, blob);
        }
        // A tiny copy of the home (laid out like the real one): clock, title, favorites, apps.
        float u = sw / 100f; // the screen is 100 x 56.25 units
        float right = s.right - 5 * u;
        tile.setColor(0xD9FFFFFF);
        r.set(right - 9 * u, s.top + 4 * u, right, s.top + 7 * u);
        c.drawRoundRect(r, 1.5f * u, 1.5f * u, tile);
        tile.setColor(0x8CFFFFFF);
        r.set(right - 20 * u, s.top + 14 * u, right, s.top + 17.5f * u);
        c.drawRoundRect(r, 1.7f * u, 1.7f * u, tile);
        float fw = (90f - 3 * 3f) / 4f * u, fh = fw * 9f / 16f, gap = 3 * u;
        float fy = s.top + 22 * u;
        for (int i = 0; i < 4; i++) {
            float x = right - (i + 1) * fw - i * gap;
            r.set(x, fy, x + fw, fy + fh);
            tile.setColor(tiles[i % tiles.length]);
            c.drawRoundRect(r, 1.6f * u, 1.6f * u, tile);
        }
        float aw = (90f - 5 * 2.4f) / 6f * u, ah = aw * 9f / 16f;
        float ay = fy + fh + 5 * u;
        tile.setColor(0x4DFFFFFF);
        for (int i = 0; i < 6; i++) {
            float x = right - (i + 1) * aw - i * 2.4f * u;
            r.set(x, ay, x + aw, ay + ah);
            c.drawRoundRect(r, 1.1f * u, 1.1f * u, tile);
        }
    }
}
