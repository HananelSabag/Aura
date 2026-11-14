package com.sabag.aura;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewOutlineProvider;

/** A glass pill (icon, optional text and badge). Focused: solid white with dark content, lifted. */
final class Chip extends View {
    private final Glass glass = new Glass();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = Ui.text(500, 15, Ui.WHITE);
    private final Paint badgeText = Ui.text(600, 11, Ui.WHITE);
    private final Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon;
    private final RectF r = new RectF();
    private int iconType;
    private int level = -1;
    private String label;
    private int count;
    private float focusT;
    private boolean focused;
    private ValueAnimator anim;
    Runnable action;

    Chip(Context c, int iconType) {
        super(c);
        this.iconType = iconType;
        icon = Icons.stroke(Ui.WHITE, Ui.dp(20));
        text.setTextAlign(Paint.Align.LEFT);
        badgeText.setTextAlign(Paint.Align.CENTER);
        badge.setColor(0xFFFF4F6D);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), v.getHeight() / 2f);
            }
        });
        setOutlineSpotShadowColor(0xFF000000);
    }

    void setIcon(int type, int level) {
        iconType = type;
        this.level = level;
        invalidate();
    }

    void setLabel(String s) {
        if (s != null && s.equals(label)) return;
        label = s;
        requestLayout();
        invalidate();
    }

    void setCount(int n) {
        count = n;
        invalidate();
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int h = Ui.dpi(42);
        int w = h;
        if (label != null && !label.isEmpty()) {
            w = Math.round(Ui.dp(14) + Ui.dp(22) + Ui.dp(8) + text.measureText(label) + Ui.dp(16));
        }
        setMeasuredDimension(w, h);
    }

    void setFocusedState(boolean f) {
        if (focused == f) return;
        focused = f;
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(focusT, f ? 1f : 0f).setDuration(Ui.ms(f ? 460 : 220));
        anim.setInterpolator(f ? Ui.spring() : Ui.EASE);
        anim.addUpdateListener(a -> {
            focusT = (float) a.getAnimatedValue();
            float s = 1f + 0.1f * focusT;
            setScaleX(s);
            setScaleY(s);
            setTranslationZ(Ui.dp(10) * Math.max(0, focusT));
            invalidate();
        });
        anim.start();
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float t = Math.max(0, Math.min(1, focusT));
        glass.tint(0x1FFFFFFF);
        glass.draw(c, this, 0, 0, w, h, h / 2f);
        if (t > 0) {
            fill.setColor(Ui.alpha(0xFFFFFFFF, t));
            r.set(0, 0, w, h);
            c.drawRoundRect(r, h / 2f, h / 2f, fill);
        }
        int ink = Ui.mix(Ui.WHITE, Ui.INK, t);
        icon.setColor(ink);
        text.setColor(ink);
        boolean hasLabel = label != null && !label.isEmpty();
        float iconCx = hasLabel ? (Ui.rtl ? w - Ui.dp(14) - Ui.dp(11) : Ui.dp(14) + Ui.dp(11)) : w / 2f;
        Icons.draw(c, iconType, iconCx, h / 2f, Ui.dp(22), icon, level);
        if (hasLabel) {
            float tw = text.measureText(label);
            float tx = Ui.rtl ? iconCx - Ui.dp(11) - Ui.dp(8) - tw : iconCx + Ui.dp(11) + Ui.dp(8);
            c.drawText(label, tx, h / 2f + text.getTextSize() * 0.36f, text);
        }
        if (count > 0) {
            float br = Ui.dp(9);
            float bx = Ui.rtl ? Ui.dp(6) : w - Ui.dp(6), by = Ui.dp(6);
            c.drawCircle(bx, by, br, badge);
            c.drawText(String.valueOf(count), bx, by + badgeText.getTextSize() * 0.36f, badgeText);
        }
    }
}
