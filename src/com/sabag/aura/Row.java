package com.sabag.aura;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * A titled, horizontally scrolling row of cards. Scrolls just enough to keep the focused card (and a
 * peek of the next one) inside the safe area; both screen edges fade so nothing is cut off hard.
 */
final class Row extends FrameLayout {
    final String id;
    private final String title;
    final float cardW, cardH, gap, radiusDp;
    private final float titleH = Ui.dp(22), titleGap = Ui.dp(12);
    private final Strip strip;
    private final Caption caption;
    private final Paint titlePaint = Ui.text(500, 15, Ui.TEXT_2);
    final List<Card> cards = new ArrayList<>();
    private int focus;
    private boolean active;
    private float scroll;
    private ValueAnimator bumpAnim;

    Row(Context c, String id, String title, float cardWdp, float cardHdp, float gapDp, float radiusDp) {
        super(c);
        this.id = id;
        this.title = title;
        cardW = Ui.dp(cardWdp);
        cardH = Ui.dp(cardHdp);
        gap = Ui.dp(gapDp);
        this.radiusDp = radiusDp;
        setClipChildren(false);
        setClipToPadding(false);
        setWillNotDraw(false);
        titlePaint.setTextAlign(Ui.startAlign());
        strip = new Strip(c);
        addView(strip, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        caption = new Caption(c);
        caption.setTranslationZ(Ui.dp(40)); // above the lifted cards and their colored shadows
        strip.addView(caption);
    }

    int preferredHeight() { return Math.round(titleH + titleGap + cardH + Ui.dp(40)); }

    float cardTop() { return titleH + titleGap; }

    // ------------------------------------------------------------------ content

    void setItems(List<Item> items, int newFocus) {
        for (Card cd : cards) strip.removeView(cd);
        cards.clear();
        for (Item it : items) {
            Card cd = new Card(getContext(), it, radiusDp);
            cards.add(cd);
            strip.addView(cd, strip.indexOfChild(caption));
        }
        focus = Math.max(0, Math.min(newFocus, cards.size() - 1));
        scroll = 0;
        strip.setTranslationX(0);
        strip.requestLayout();
        if (active && !cards.isEmpty()) {
            cards.get(focus).setFocusedState(true, false);
        }
        post(() -> scrollTo(focus, false));
        caption.update(false);
    }

    int size() { return cards.size(); }
    int focus() { return focus; }
    Card focusedCard() { return cards.isEmpty() ? null : cards.get(focus); }
    Item focusedItem() { return cards.isEmpty() ? null : cards.get(focus).item; }

    void setActive(boolean on, boolean animate) {
        active = on;
        titlePaint.setColor(on ? Ui.WHITE : Ui.TEXT_3);
        Card f = focusedCard();
        if (f != null) f.setFocusedState(on, animate);
        caption.update(animate);
        invalidate();
    }

    /** Logical move (+1 = toward the reading end). False at the row's end. */
    boolean move(int delta) {
        int next = focus + delta;
        if (next < 0 || next >= cards.size()) {
            bump(delta);
            return false;
        }
        setFocus(next, true);
        return true;
    }

    void setFocus(int index, boolean animate) {
        if (cards.isEmpty()) return;
        index = Math.max(0, Math.min(index, cards.size() - 1));
        if (index != focus) {
            cards.get(focus).setFocusedState(false, animate);
            focus = index;
        }
        if (active) cards.get(focus).setFocusedState(true, animate);
        scrollTo(focus, animate);
        caption.update(animate);
    }

    /** Focus the card whose center is closest to screen x (used when moving between rows). */
    void focusNearest(float screenX, boolean animate) {
        int best = 0;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < cards.size(); i++) {
            float d = Math.abs(screenCenter(i) - screenX);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        setFocus(best, animate);
    }

    float screenCenter(int i) {
        return restX(i) + cardW / 2f + strip.getTranslationX() + getX();
    }

    float focusedScreenCenter() { return cards.isEmpty() ? getWidth() / 2f : screenCenter(focus); }

    /** Card i's x with no scrolling. */
    private float restX(int i) {
        float s = i * (cardW + gap);
        return Ui.rtl ? getWidth() - Ui.dp(Ui.SAFE_X) - s - cardW : Ui.dp(Ui.SAFE_X) + s;
    }

    private void scrollTo(int i, boolean animate) {
        if (getWidth() == 0) return;
        float view = getWidth() - 2 * Ui.dp(Ui.SAFE_X);
        float s = i * (cardW + gap), e = s + cardW;
        float peek = cardW * 0.32f;
        float margin = cardW * 0.06f; // room for the focus scale
        float next = scroll;
        if (e + (i < cards.size() - 1 ? peek : margin) - next > view) next = e + (i < cards.size() - 1 ? peek : margin) - view;
        if (s - (i > 0 ? peek : 0) - next < 0) next = Math.max(0, s - (i > 0 ? peek : 0));
        if (i == 0) next = 0;
        scroll = next;
        float tx = Ui.rtl ? scroll : -scroll;
        strip.animate().cancel();
        if (animate) {
            strip.animate().translationX(tx).setDuration(Ui.ms(420)).setInterpolator(Ui.EASE_OUT).start();
        } else {
            strip.setTranslationX(tx);
        }
        updateVisibility(tx, animate);
        invalidate();
    }

    /**
     * Cards fully inside the safe area are shown; the next one past the edge shows as a faint peek
     * only when a good part of it is on screen. Nothing is ever cut off by the screen edge at full strength.
     */
    private void updateVisibility(float tx, boolean animate) {
        // A few dp of slack: at the row's end the scroll leaves room for the focus scale.
        float w = getWidth(), lo = Ui.dp(Ui.SAFE_X) - Ui.dp(12), hi = w - Ui.dp(Ui.SAFE_X) + Ui.dp(12);
        for (int i = 0; i < cards.size(); i++) {
            Card c = cards.get(i);
            float x0 = restX(i) + tx, x1 = x0 + cardW;
            float target;
            if (x0 >= lo && x1 <= hi) {
                target = 1f;
            } else {
                float onScreen = Math.max(0, Math.min(x1, w) - Math.max(x0, 0)) / cardW;
                target = onScreen >= 0.3f ? 0.32f : 0f;
            }
            c.visibility = target;
            if (c.getAlpha() == target) continue;
            if (animate) c.animate().alpha(target).setDuration(Ui.ms(300)).start();
            else c.setAlpha(target);
        }
    }

    /** A short rubber-band nudge when there is nothing further in that direction. */
    void bump(int delta) {
        Card f = focusedCard();
        if (f == null) return;
        if (bumpAnim != null) bumpAnim.cancel();
        float dir = (Ui.rtl ? -1 : 1) * Math.signum(delta);
        bumpAnim = ValueAnimator.ofFloat(0, 1).setDuration(Ui.ms(360));
        bumpAnim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            f.setTranslationX(dir * Ui.dp(10) * (float) Math.sin(Math.PI * t) * (1 - t * 0.5f));
        });
        bumpAnim.start();
    }

    // ------------------------------------------------------------------ move mode

    void setMoving(boolean on) {
        Card f = focusedCard();
        if (f != null) f.setMoving(on);
        caption.update(true);
    }

    /** Swaps the focused card with its neighbor; the caller persists the order. */
    boolean shift(int delta) {
        int j = focus + delta;
        if (j < 0 || j >= cards.size()) {
            bump(delta);
            return false;
        }
        Card a = cards.get(focus), b = cards.get(j);
        float ax = a.getX(), bx = b.getX();
        cards.set(focus, b);
        cards.set(j, a);
        focus = j;
        strip.requestLayout();
        strip.post(() -> {
            a.setTranslationX(ax - a.getLeft());
            b.setTranslationX(bx - b.getLeft());
            a.animate().translationX(0).setDuration(Ui.ms(320)).setInterpolator(Ui.springSoft()).start();
            b.animate().translationX(0).setDuration(Ui.ms(320)).setInterpolator(Ui.springSoft()).start();
        });
        scrollTo(focus, true);
        caption.update(true);
        return true;
    }

    void refreshStatus() {
        for (Card c : cards) c.invalidate();
        caption.update(false);
        strip.invalidate();
    }

    /** Cards rise and fade in, one after another. */
    void animateIn(long delay) {
        if (Ui.lite) return;
        for (int i = 0; i < cards.size(); i++) {
            Card c = cards.get(i);
            c.setAlpha(0f);
            c.setTranslationY(Ui.dp(26));
            c.animate().alpha(c.visibility).translationY(0).setStartDelay(delay + Math.min(i, 6) * 45L)
                    .setDuration(Ui.ms(560)).setInterpolator(Ui.springSoft()).start();
        }
    }

    // ------------------------------------------------------------------ drawing

    private float titleAlpha = 1f;
    private ValueAnimator titleAnim;

    /** Hides the title when scrolling would push it above the safe area. */
    void setTitleShown(boolean shown) {
        float target = shown ? 1f : 0f;
        if (titleAlpha == target) return;
        if (titleAnim != null) titleAnim.cancel();
        titleAnim = ValueAnimator.ofFloat(titleAlpha, target).setDuration(Ui.ms(240));
        titleAnim.addUpdateListener(a -> {
            titleAlpha = (float) a.getAnimatedValue();
            invalidate();
        });
        titleAnim.start();
    }

    @Override protected void onDraw(Canvas c) {
        if (titleAlpha <= 0.01f) return;
        float x = Ui.rtl ? getWidth() - Ui.dp(Ui.SAFE_X) : Ui.dp(Ui.SAFE_X);
        int base = titlePaint.getColor();
        titlePaint.setAlpha(Math.round((base >>> 24) * titleAlpha));
        c.drawText(title, x, titleH - Ui.dp(5), titlePaint);
        titlePaint.setColor(base);
    }

    /** Lays the cards out at rest; scrolling is the strip's translation. */
    private final class Strip extends ViewGroup {
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);

        Strip(Context c) {
            super(c);
            setClipChildren(false);
            setClipToPadding(false);
            setWillNotDraw(false);
            dot.setColor(0xD9FFFFFF);
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int cw = MeasureSpec.makeMeasureSpec(Math.round(cardW), MeasureSpec.EXACTLY);
            int ch = MeasureSpec.makeMeasureSpec(Math.round(cardH), MeasureSpec.EXACTLY);
            for (Card cd : cards) cd.measure(cw, ch);
            caption.measure(MeasureSpec.makeMeasureSpec(Math.round(cardW * 1.6f), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Ui.dpi(26), MeasureSpec.EXACTLY));
            setMeasuredDimension(MeasureSpec.getSize(wSpec), MeasureSpec.getSize(hSpec));
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int top = Math.round(cardTop());
            for (int i = 0; i < cards.size(); i++) {
                int x = Math.round(restX(i));
                cards.get(i).layout(x, top, x + Math.round(cardW), top + Math.round(cardH));
            }
            caption.layout(0, 0, caption.getMeasuredWidth(), caption.getMeasuredHeight());
            caption.place(false);
        }

        @Override protected void dispatchDraw(Canvas c) {
            super.dispatchDraw(c);
            // Open apps get a small dot under their card (like a dock).
            float y = cardTop() + cardH + Ui.dp(9);
            for (int i = 0; i < cards.size(); i++) {
                Card cd = cards.get(i);
                if (!cd.item.running || (i == focus && active) || cd.getAlpha() < 0.5f) continue;
                dot.setAlpha(Math.round(217 * cd.getAlpha()));
                c.drawCircle(cd.getX() + cardW / 2f, y, Ui.dp(2.3f), dot);
            }
        }
    }

    /** The focused card's name, under it. */
    private final class Caption extends View {
        private final Paint p = Ui.text(500, 15, Ui.WHITE);
        private final Paint d = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String text = "";
        private boolean running;

        Caption(Context c) {
            super(c);
            p.setTextAlign(Paint.Align.CENTER);
            d.setColor(Ui.OK_GREEN);
            setAlpha(0f);
        }

        void update(boolean animate) {
            Card f = focusedCard();
            boolean show = active && f != null && f.item.type != Item.FOLDER && f.item.type != Item.CLOSE_ALL;
            if (show) {
                text = Ui.fit(p, f.item.label, cardW * 1.5f);
                running = f.item.running;
                place(animate);
                invalidate();
            }
            animate().cancel();
            if (animate) {
                setAlpha(0f);
                if (show) animate().alpha(1f).setStartDelay(Ui.ms(120)).setDuration(Ui.ms(260)).start();
            } else {
                setAlpha(show ? 1f : 0f);
            }
        }

        void place(boolean animate) {
            Card f = focusedCard();
            if (f == null) return;
            float x = f.getLeft() + cardW / 2f - getWidth() / 2f;
            float y = cardTop() + cardH + cardH * (f.scaleFocused - 1f) / 2f + Ui.dp(6);
            setTranslationX(x);
            setTranslationY(y);
        }

        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, base = getHeight() - Ui.dp(7);
            c.drawText(text, cx, base, p);
            if (running) {
                float tw = p.measureText(text);
                float dx = Ui.rtl ? cx + tw / 2f + Ui.dp(9) : cx - tw / 2f - Ui.dp(9);
                c.drawCircle(dx, base - Ui.dp(5), Ui.dp(3), d);
            }
        }
    }
}
