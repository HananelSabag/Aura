package com.sabag.aura;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewOutlineProvider;

/**
 * One tile. Focus lifts it on a spring (scale + colored glow shadow), draws a bright rim and sends a
 * specular sweep across it once. Folders draw a mini grid of their apps on glass.
 */
final class Card extends View {
    final Item item;
    private final float radius;
    private final Paint art = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mini = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint title, sub, icon;
    private final Glass glass = new Glass();
    private final RectF r = new RectF(), cropR = new RectF(), m = new RectF();
    private final Matrix matrix = new Matrix();
    private Bitmap shaderBitmap;
    private BitmapShader shader;
    private LinearGradient sweepShader;
    private float focusT, sweep = -1f, dim;
    private boolean focused, moving;
    private ValueAnimator focusAnim, sweepAnim;
    float scaleFocused = 1.1f;
    /** Target alpha from the row (1 = inside the safe area, faint = peek, 0 = off). */
    float visibility = 1f;
    /** Being dismissed (task manager): its label is no longer drawn. */
    boolean closing;

    /** Alpha is applied per drawing call, so fading a card never needs an offscreen layer. */
    @Override public boolean hasOverlappingRendering() { return false; }

    Card(Context c, Item item, float radiusDp) {
        super(c);
        this.item = item;
        radius = Ui.dp(radiusDp);
        rim.setStyle(Paint.Style.STROKE);
        title = Ui.text(600, 15, Ui.WHITE);
        title.setTextAlign(Ui.startAlign());
        sub = Ui.text(400, 12, Ui.TEXT_3);
        sub.setTextAlign(Ui.startAlign());
        icon = Icons.stroke(Ui.WHITE, Ui.dp(20));
        final boolean glassy = item.type == Item.FOLDER || item.type == Item.CLOSE_ALL;
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                // A shadow would show through translucent glass as a dark slab: glass cards cast none.
                // Lite mode casts no shadows at all (each one is an extra blurred mesh per frame).
                o.setAlpha(glassy || Ui.lite ? 0f : 1f);
            }
        });
        setGlow(item.glow);
        if (!Ui.lite) setElevation(Ui.dp(1));
    }

    private void setGlow(int color) {
        setOutlineSpotShadowColor(color);
        setOutlineAmbientShadowColor(color);
    }

    // ------------------------------------------------------------------ state

    void setFocusedState(boolean f, boolean animate) {
        if (focused == f) return;
        focused = f;
        if (focusAnim != null) focusAnim.cancel();
        float target = f ? 1f : 0f;
        if (!animate) {
            applyFocus(target);
            return;
        }
        focusAnim = ValueAnimator.ofFloat(focusT, target).setDuration(Ui.ms(f ? 520 : 260));
        focusAnim.setInterpolator(f ? Ui.spring() : Ui.EASE);
        focusAnim.addUpdateListener(a -> applyFocus((float) a.getAnimatedValue()));
        focusAnim.start();
        if (f && !Ui.lite) startSweep();
    }

    private void applyFocus(float t) {
        focusT = t;
        float s = 1f + (scaleFocused - 1f) * t;
        setScaleX(s);
        setScaleY(s);
        if (!Ui.lite) setTranslationZ(Ui.dp(22) * Math.max(0, Math.min(1, t)));
        invalidate();
    }

    private void startSweep() {
        if (sweepAnim != null) sweepAnim.cancel();
        sweepAnim = ValueAnimator.ofFloat(-0.4f, 1.4f).setDuration(Ui.ms(900));
        sweepAnim.setStartDelay(Ui.ms(90));
        sweepAnim.setInterpolator(Ui.EASE);
        sweepAnim.addUpdateListener(a -> {
            sweep = (float) a.getAnimatedValue();
            invalidate();
        });
        sweepAnim.start();
    }

    /** Move mode: the card floats and shows arrows. */
    void setMoving(boolean on) {
        moving = on;
        invalidate();
    }

    /** 0 = normal, 1 = strongly dimmed (rows without focus are slightly quieter). */
    void setDim(float d) {
        if (dim == d) return;
        dim = d;
        invalidate();
    }

    void refreshArt() {
        shaderBitmap = null;
        setGlow(item.glow);
        invalidate();
    }

    // ------------------------------------------------------------------ drawing

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        sweepShader = new LinearGradient(0, 0, w * 0.35f, h,
                new int[]{0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        sweepPaint.setShader(sweepShader);
        shaderBitmap = null;
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        r.set(0, 0, w, h);
        if (item.type == Item.FOLDER) {
            drawFolder(c, w, h);
        } else if (item.type == Item.CLOSE_ALL) {
            drawCloseAll(c, w, h);
        } else {
            drawArt(c, w, h);
        }
        if (dim > 0) {
            shade.setColor(Ui.alpha(0xFF000000, 0.45f * dim));
            c.drawRoundRect(r, radius, radius, shade);
        }
        if (sweep > -0.4f && sweep < 1.4f && focusT > 0.01f) {
            matrix.setTranslate(-w * 0.35f + sweep * (w * 1.35f), 0);
            if (Ui.rtl) {
                matrix.postScale(-1, 1, w / 2f, h / 2f);
            }
            sweepShader.setLocalMatrix(matrix);
            c.drawRoundRect(r, radius, radius, sweepPaint);
        }
        // Rim: a faint hairline at rest, a crisp white ring when focused.
        float sw = Ui.lerp(Ui.dp(1), Ui.dp(2.6f), focusT);
        rim.setStrokeWidth(sw);
        rim.setColor(Ui.mix(0x24FFFFFF, 0xF2FFFFFF, Math.max(0, Math.min(1, focusT))));
        r.set(sw / 2, sw / 2, w - sw / 2, h - sw / 2);
        c.drawRoundRect(r, radius - sw / 2, radius - sw / 2, rim);
        if (moving) drawMoveArrows(c, w, h);
    }

    private void drawArt(Canvas c, int w, int h) {
        Bitmap b = Art.scaled(item, Math.round(w * scaleFocused));
        if (b == null) {
            shade.setColor(0x33FFFFFF);
            c.drawRoundRect(r, radius, radius, shade);
            return;
        }
        if (shaderBitmap != b) {
            shaderBitmap = b;
            shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Art.crop(b, w, h, cropR);
            matrix.setRectToRect(cropR, new RectF(0, 0, w, h), Matrix.ScaleToFit.FILL);
            shader.setLocalMatrix(matrix);
            art.setShader(shader);
        }
        art.setAlpha(item.archived ? 150 : 255);
        c.drawRoundRect(r, radius, radius, art);
    }

    private void drawFolder(Canvas c, int w, int h) {
        glass.tint(Ui.mix(0x1AFFFFFF, 0x30FFFFFF, focusT));
        glass.draw(c, this, 0, 0, w, h, radius);
        float pad = Ui.dp(12);
        // Text block at the reading start.
        float tx = Ui.rtl ? w - pad : pad;
        Icons.draw(c, item.folderId.equals(Catalog.INPUTS) ? Icons.HDMI : Icons.FOLDER,
                Ui.rtl ? w - pad - Ui.dp(10) : pad + Ui.dp(10), pad + Ui.dp(10), Ui.dp(20), icon, 0);
        c.drawText(Ui.fit(title, item.label, w * 0.42f), tx, h - pad - Ui.dp(18), title);
        int n = item.children.size();
        c.drawText(n == 1 ? "פריט אחד" : n + " פריטים", tx, h - pad, sub);
        // 2x2 mini banners at the reading end.
        float gw = w * 0.5f, cell = (gw - Ui.dp(6)) / 2f, ch = cell * 9f / 16f;
        float gh = ch * 2 + Ui.dp(6);
        float gx = Ui.rtl ? pad : w - pad - gw, gy = (h - gh) / 2f;
        for (int i = 0; i < 4; i++) {
            float x = gx + (i % 2) * (cell + Ui.dp(6));
            if (Ui.rtl) x = gx + (1 - i % 2) * (cell + Ui.dp(6));
            float y = gy + (i / 2) * (ch + Ui.dp(6));
            m.set(x, y, x + cell, y + ch);
            if (i < n && item.children.get(i).art != null) {
                Bitmap b = Art.scaled(item.children.get(i), Math.round(cell * scaleFocused));
                Art.crop(b, cell, ch, cropR);
                c.save();
                android.graphics.Path clip = new android.graphics.Path();
                clip.addRoundRect(m, Ui.dp(5), Ui.dp(5), android.graphics.Path.Direction.CW);
                c.clipPath(clip);
                android.graphics.Rect src = new android.graphics.Rect((int) cropR.left, (int) cropR.top, (int) cropR.right, (int) cropR.bottom);
                c.drawBitmap(b, src, m, mini);
                c.restore();
            } else {
                shade.setColor(0x14FFFFFF);
                c.drawRoundRect(m, Ui.dp(5), Ui.dp(5), shade);
            }
        }
    }

    private void drawCloseAll(Canvas c, int w, int h) {
        glass.tint(Ui.mix(0x1AFFFFFF, 0xE6FF5A5F, focusT));
        glass.draw(c, this, 0, 0, w, h, radius);
        icon.setColor(Ui.WHITE);
        Icons.draw(c, Icons.CLOSE, w / 2f, h / 2f - Ui.dp(12), Ui.dp(30), icon, 0);
        Paint t = title;
        Paint.Align a = t.getTextAlign();
        t.setTextAlign(Paint.Align.CENTER);
        c.drawText("סגור הכל", w / 2f, h / 2f + Ui.dp(26), t);
        t.setTextAlign(a);
    }

    private void drawMoveArrows(Canvas c, int w, int h) {
        shade.setColor(0xCC000000);
        float cy = h / 2f, rr = Ui.dp(15);
        for (int side = 0; side < 2; side++) {
            float cx = side == 0 ? Ui.dp(4) : w - Ui.dp(4);
            c.drawCircle(cx, cy, rr, shade);
            icon.setColor(Ui.WHITE);
            c.save();
            if (side == 0) c.scale(-1, 1, cx, cy);
            float u = rr / 24f * 1.6f;
            c.drawLine(cx - 3 * u, cy - 5 * u, cx + 3 * u, cy, icon);
            c.drawLine(cx + 3 * u, cy, cx - 3 * u, cy + 5 * u, icon);
            c.restore();
        }
    }
}
