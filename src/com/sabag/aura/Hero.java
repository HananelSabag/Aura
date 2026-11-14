package com.sabag.aura;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Big name of the focused item with one line of status; the old text slides out as the new slides in. */
final class Hero extends View {
    private final Paint title = Ui.text(600, 44, Ui.WHITE);
    private final Paint line = Ui.text(400, 16, Ui.TEXT_2);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String t0 = "", s0 = "", t1 = "", s1 = "";
    private boolean live0, live1;
    private float mix = 1f;
    private ValueAnimator anim;

    Hero(Context c) {
        super(c);
        title.setTextAlign(Ui.startAlign());
        line.setTextAlign(Ui.startAlign());
        title.setLetterSpacing(-0.015f);
        title.setShadowLayer(Ui.dp(18), 0, Ui.dp(2), 0x66000000);
        line.setShadowLayer(Ui.dp(10), 0, Ui.dp(1), 0x66000000);
        dot.setColor(Ui.OK_GREEN);
    }

    void show(String name, String status, boolean live) {
        if (name.equals(t1) && status.equals(s1) && live == live1) return;
        boolean sameName = name.equals(t1);
        t0 = t1;
        s0 = s1;
        live0 = live1;
        t1 = name;
        s1 = status;
        live1 = live;
        if (anim != null) anim.cancel();
        if (sameName) {
            mix = 1f;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(0f, 1f).setDuration(Ui.ms(460));
        anim.setInterpolator(Ui.EASE_OUT);
        anim.addUpdateListener(a -> {
            mix = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    @Override protected void onDraw(Canvas c) {
        float sx = Ui.rtl ? getWidth() - Ui.dp(Ui.SAFE_X) : Ui.dp(Ui.SAFE_X);
        float max = getWidth() * 0.62f;
        if (mix < 1f) draw(c, t0, s0, live0, sx, 1f - mix, -Ui.dp(10) * mix, max);
        draw(c, t1, s1, live1, sx, mix, Ui.dp(14) * (1f - mix), max);
    }

    private void draw(Canvas c, String t, String s, boolean live, float x, float alpha, float dy, float max) {
        if (t.isEmpty() || alpha <= 0.01f) return;
        int a = Math.round(255 * alpha);
        title.setAlpha(a);
        line.setAlpha(Math.round(a * 0.8f));
        float base = getHeight() - Ui.dp(34) + dy;
        c.drawText(Ui.fit(title, t, max), x, base, title);
        float lx = x;
        if (live) {
            dot.setAlpha(a);
            float cx = Ui.rtl ? x - Ui.dp(4) : x + Ui.dp(4);
            c.drawCircle(cx, base + Ui.dp(22), Ui.dp(4), dot);
            lx = Ui.rtl ? x - Ui.dp(15) : x + Ui.dp(15);
        }
        c.drawText(Ui.fit(line, s, max), lx, base + Ui.dp(28), line);
    }
}
