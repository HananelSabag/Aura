package com.sabag.aura;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** A folder opened as a glass panel rising from the bottom, with a grid of its apps and their names. */
final class FolderSheet extends Sheet {
    final String folderId;
    private final Glass glass = new Glass().tint(Ui.lite ? 0xC7141821 : 0x8C141821).solid(Ui.lite ? 0xF5141821 : 0xB8141821).brightness(0.6f);
    private final Paint titleP = Ui.text(600, 26, Ui.WHITE);
    private final Paint countP = Ui.text(400, 14, Ui.TEXT_3);
    private final Paint nameP = Ui.text(500, 14, Ui.TEXT_2);
    private final Paint nameFocusP = Ui.text(500, 14, Ui.WHITE);
    private final Paint icon = Icons.stroke(Ui.WHITE, Ui.dp(24));
    private final List<Card> cards = new ArrayList<>();
    private int focus;
    private float px, py, pw, ph, scrollY;

    private static final int COLS = 5;
    private static final float CW = 144, CH = 81, GX = 22, ROW_H = 81 + 44, PAD = 28, HEAD = 78;

    FolderSheet(HomeActivity home, String folderId) {
        super(home);
        this.folderId = folderId;
        titleP.setTextAlign(Ui.startAlign());
        countP.setTextAlign(Ui.startAlign());
        nameP.setTextAlign(Paint.Align.CENTER);
        nameFocusP.setTextAlign(Paint.Align.CENTER);
        rebuild();
    }

    private List<Item> items() { return home.catalog.list(folderId); }

    private void rebuild() {
        for (Card c : cards) removeView(c);
        cards.clear();
        for (Item it : items()) {
            Card c = new Card(getContext(), it, 12);
            c.scaleFocused = 1.09f;
            cards.add(c);
            addView(c);
        }
        focus = Math.max(0, Math.min(focus, cards.size() - 1));
        if (!cards.isEmpty()) cards.get(focus).setFocusedState(true, false);
        requestLayout();
        invalidate();
    }

    @Override void onDataChanged() {
        if (items().isEmpty()) {
            close();
            return;
        }
        rebuild();
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        super.onMeasure(wSpec, hSpec);
        int cw = MeasureSpec.makeMeasureSpec(Ui.dpi(CW), MeasureSpec.EXACTLY);
        int ch = MeasureSpec.makeMeasureSpec(Ui.dpi(CH), MeasureSpec.EXACTLY);
        for (Card c : cards) c.measure(cw, ch);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        pw = w - 2 * Ui.dp(Ui.SAFE_X);
        px = Ui.dp(Ui.SAFE_X);
        int rows = Math.max(1, (cards.size() + COLS - 1) / COLS);
        float visibleRows = Math.min(rows, 2.4f);
        ph = Ui.dp(HEAD + PAD) + visibleRows * Ui.dp(ROW_H) + Ui.dp(6);
        py = h - Ui.dp(Ui.SAFE_Y) - ph;
        float inner = pw - 2 * Ui.dp(PAD);
        float gx = (inner - COLS * Ui.dp(CW)) / (COLS - 1);
        for (int i = 0; i < cards.size(); i++) {
            int col = i % COLS, row = i / COLS;
            float s = Ui.dp(PAD) + col * (Ui.dp(CW) + gx);
            float x = Ui.rtl ? px + pw - s - Ui.dp(CW) : px + s;
            float y = py + Ui.dp(HEAD) + row * Ui.dp(ROW_H) - scrollY;
            cards.get(i).layout(Math.round(x), Math.round(y), Math.round(x + Ui.dp(CW)), Math.round(y + Ui.dp(CH)));
        }
        setPivotY(h);
        setPivotX(w / 2f);
    }

    @Override protected void dispatchDraw(Canvas c) {
        float radius = Ui.dp(30);
        glass.draw(c, this, px, py, px + pw, py + ph, radius);
        float sx = Ui.rtl ? px + pw - Ui.dp(PAD) : px + Ui.dp(PAD);
        Icons.draw(c, folderId.equals(Catalog.INPUTS) ? Icons.HDMI : Icons.FOLDER,
                Ui.rtl ? sx - Ui.dp(13) : sx + Ui.dp(13), py + Ui.dp(38), Ui.dp(26), icon, 0);
        float tx = Ui.rtl ? sx - Ui.dp(38) : sx + Ui.dp(38);
        c.drawText(Catalog.name(folderId), tx, py + Ui.dp(47), titleP);
        float tw = titleP.measureText(Catalog.name(folderId));
        int n = cards.size();
        c.drawText(n == 1 ? "פריט אחד" : n + " פריטים", Ui.rtl ? tx - tw - Ui.dp(14) : tx + tw + Ui.dp(14), py + Ui.dp(47), countP);
        c.save();
        c.clipRect(px, py + Ui.dp(HEAD - 14), px + pw, py + ph - Ui.dp(4));
        super.dispatchDraw(c);
        for (int i = 0; i < cards.size(); i++) {
            Card cd = cards.get(i);
            Paint p = i == focus ? nameFocusP : nameP;
            float y = cd.getBottom() + Ui.dp(i == focus ? 27 : 23);
            c.drawText(Ui.fit(p, cd.item.label, Ui.dp(CW + 14)), cd.getLeft() + cd.getWidth() / 2f, y, p);
        }
        c.restore();
    }

    private void setFocus(int i) {
        if (i < 0 || i >= cards.size() || i == focus) return;
        cards.get(focus).setFocusedState(false, true);
        focus = i;
        cards.get(focus).setFocusedState(true, true);
        // Keep the focused row visible.
        int row = focus / COLS;
        float visibleH = ph - Ui.dp(HEAD + PAD);
        float top = row * Ui.dp(ROW_H);
        if (top - scrollY + Ui.dp(ROW_H) > visibleH) scrollY = top + Ui.dp(ROW_H) - visibleH;
        if (top < scrollY) scrollY = top;
        requestLayout();
        invalidate();
    }

    @Override boolean onKey(int keyCode, KeyEvent e) {
        int col = focus % COLS;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                int dir = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                int logical = Ui.rtl ? -dir : dir;
                int next = focus + logical;
                if (next >= 0 && next < cards.size() && next / COLS == focus / COLS) setFocus(next);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_UP:
                if (focus - COLS >= 0) setFocus(focus - COLS);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (focus + COLS < cards.size()) setFocus(focus + COLS);
                else if ((focus / COLS) < (cards.size() - 1) / COLS) setFocus(cards.size() - 1);
                return true;
            case KeyEvent.KEYCODE_BACK:
                close();
                return true;
            default:
                return col >= 0;
        }
    }

    Card focusedCard() { return cards.isEmpty() ? null : cards.get(focus); }

    @Override void onOk() {
        Card c = focusedCard();
        if (c != null) home.launch(c.item, c);
    }

    @Override void onLongOk() {
        Card c = focusedCard();
        if (c != null) home.openMenu(c.item, c, folderId);
    }

    @Override void animateIn() {
        if (Ui.lite) { // a short slide only: moving a view is free, fading it is not
            setTranslationY(Ui.dp(30));
            animate().translationY(0).setDuration(140).setInterpolator(Ui.EASE_OUT).start();
            return;
        }
        setAlpha(0f);
        setTranslationY(Ui.dp(60));
        animate().alpha(1f).translationY(0).setDuration(Ui.ms(520)).setInterpolator(Ui.springSoft()).start();
    }

    @Override void animateOut(Runnable end) {
        if (Ui.lite) {
            end.run();
            return;
        }
        animate().alpha(0f).translationY(Ui.dp(40)).setDuration(Ui.ms(200)).setInterpolator(Ui.EASE_IN).withEndAction(end).start();
    }

    View anchor() { return focusedCard(); }
}
