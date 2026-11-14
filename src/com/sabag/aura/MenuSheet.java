package com.sabag.aura;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/** A glass popover with a short list of actions, anchored next to the card it belongs to. */
final class MenuSheet extends Sheet {
    static final class Entry {
        final int icon;
        final String label;
        final Runnable action;
        final boolean danger;
        /** Non-null: opens a sub-list instead of running an action. */
        List<Entry> sub;

        Entry(int icon, String label, Runnable action, boolean danger) {
            this.icon = icon;
            this.label = label;
            this.action = action;
            this.danger = danger;
        }
    }

    private final String title, subtitle;
    private final List<Entry> root;
    private List<Entry> entries;
    private final float anchorX, anchorTop, anchorBottom;
    private final Glass glass = new Glass().tint(Ui.lite ? 0xCC151922 : 0x99151922).solid(Ui.lite ? 0xF5151922 : 0xC7151922).brightness(0.6f);
    private final Paint titleP = Ui.text(600, 18, Ui.WHITE);
    private final Paint subP = Ui.text(400, 13, Ui.TEXT_3);
    private final Paint itemP = Ui.text(500, 16, Ui.WHITE);
    private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = Icons.stroke(Ui.WHITE, Ui.dp(20));
    private final RectF r = new RectF();
    private int focus;
    private float pillY = -1;
    private ValueAnimator pillAnim;
    private float px, py, pw, ph;

    private static final float ITEM_H = 48, HEAD_H = 66, PAD = 10, W = 300;

    MenuSheet(HomeActivity home, String title, String subtitle, List<Entry> entries,
              float anchorX, float anchorTop, float anchorBottom) {
        super(home);
        this.title = title;
        this.subtitle = subtitle == null ? "" : subtitle;
        this.root = entries;
        this.entries = entries;
        this.anchorX = anchorX;
        this.anchorTop = anchorTop;
        this.anchorBottom = anchorBottom;
        titleP.setTextAlign(Ui.startAlign());
        subP.setTextAlign(Ui.startAlign());
        itemP.setTextAlign(Ui.startAlign());
        pill.setColor(Ui.WHITE);
    }

    @Override boolean large() { return false; }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { measurePanel(); }

    private void measurePanel() {
        int w = getWidth(), h = getHeight();
        if (w == 0) return;
        pw = Ui.dp(W);
        ph = Ui.dp(HEAD_H + PAD) + entries.size() * Ui.dp(ITEM_H);
        float safe = Ui.dp(Ui.SAFE_X);
        px = Math.max(safe, Math.min(w - safe - pw, anchorX - pw / 2f));
        float gapY = Ui.dp(22);
        if (anchorTop - gapY - ph >= Ui.dp(Ui.SAFE_Y)) py = anchorTop - gapY - ph;
        else if (anchorBottom + gapY + ph <= h - Ui.dp(Ui.SAFE_Y)) py = anchorBottom + gapY;
        else py = Math.max(Ui.dp(Ui.SAFE_Y), (h - ph) / 2f);
        setPivotX(anchorX);
        setPivotY(py > anchorTop ? py : py + ph);
        pillY = itemTop(focus);
    }

    private float itemTop(int i) { return py + Ui.dp(HEAD_H) + i * Ui.dp(ITEM_H); }

    private void setFocus(int i) {
        if (i < 0 || i >= entries.size()) return;
        focus = i;
        if (pillAnim != null) pillAnim.cancel();
        pillAnim = ValueAnimator.ofFloat(pillY, itemTop(i)).setDuration(Ui.ms(340));
        pillAnim.setInterpolator(Ui.springSoft());
        pillAnim.addUpdateListener(a -> {
            pillY = (float) a.getAnimatedValue();
            invalidate();
        });
        pillAnim.start();
    }

    @Override boolean onKey(int keyCode, KeyEvent e) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                setFocus(focus - 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                setFocus(focus + 1);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (entries != root) {
                    entries = root;
                    focus = 0;
                    measurePanel();
                    invalidate();
                } else {
                    close();
                }
                return true;
            default:
                return true; // swallow left/right
        }
    }

    @Override void onOk() {
        Entry en = entries.get(focus);
        if (en.sub != null) {
            entries = en.sub;
            focus = 0;
            measurePanel();
            invalidate();
            return;
        }
        close();
        // Run it from the activity: in lite mode the menu is removed at once, and a view that is no
        // longer on screen never runs what was posted to it (every menu action silently did nothing).
        if (en.action != null) home.runSoon(en.action);
    }

    @Override protected void onDraw(Canvas c) {
        if (pw == 0) measurePanel();
        float radius = Ui.dp(26);
        glass.draw(c, this, px, py, px + pw, py + ph, radius);
        float sx = Ui.rtl ? px + pw - Ui.dp(22) : px + Ui.dp(22);
        c.drawText(Ui.fit(titleP, title, pw - Ui.dp(44)), sx, py + Ui.dp(32), titleP);
        if (!subtitle.isEmpty()) c.drawText(Ui.fit(subP, subtitle, pw - Ui.dp(44)), sx, py + Ui.dp(52), subP);
        else if (entries != root) c.drawText("בחרו יעד", sx, py + Ui.dp(52), subP);
        // Selection pill
        r.set(px + Ui.dp(PAD), pillY + Ui.dp(3), px + pw - Ui.dp(PAD), pillY + Ui.dp(ITEM_H) - Ui.dp(3));
        c.drawRoundRect(r, Ui.dp(16), Ui.dp(16), pill);
        for (int i = 0; i < entries.size(); i++) {
            Entry en = entries.get(i);
            float top = itemTop(i), cy = top + Ui.dp(ITEM_H) / 2f;
            float closeness = 1f - Math.min(1f, Math.abs(pillY - top) / Ui.dp(ITEM_H));
            int base = en.danger ? Ui.DANGER : Ui.WHITE;
            int ink = Ui.mix(base, en.danger ? 0xFFD62F35 : Ui.INK, closeness);
            icon.setColor(ink);
            itemP.setColor(ink);
            float ix = Ui.rtl ? px + pw - Ui.dp(PAD + 24) : px + Ui.dp(PAD + 24);
            Icons.draw(c, en.icon, ix, cy, Ui.dp(20), icon, -1);
            float tx = Ui.rtl ? ix - Ui.dp(22) : ix + Ui.dp(22);
            c.drawText(Ui.fit(itemP, en.label, pw - Ui.dp(90)), tx, cy + Ui.dp(5.5f), itemP);
            if (en.sub != null) {
                float chx = Ui.rtl ? px + Ui.dp(PAD + 18) : px + pw - Ui.dp(PAD + 18);
                Icons.draw(c, Icons.CHEVRON, chx, cy, Ui.dp(16), icon, -1);
            }
        }
    }

    static List<Entry> list() { return new ArrayList<>(); }
}
