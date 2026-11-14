package com.sabag.aura;

import android.app.ActivityManager;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Open apps, like a phone's recents: the TV and its memory on top, the open apps as large cards.
 * ▲ on a card closes that app (force-stop through adb), OK switches to it, the last card closes all.
 */
final class TasksSheet extends Sheet {
    private final TvArt tv;
    private final Paint h1 = Ui.text(600, 24, Ui.WHITE);
    private final Paint h2 = Ui.text(400, 14, Ui.TEXT_2);
    private final Paint small = Ui.text(400, 13, Ui.TEXT_3);
    private final Paint section = Ui.text(600, 18, Ui.WHITE);
    private final Paint name = Ui.text(500, 16, Ui.WHITE);
    private final Paint meta = Ui.text(400, 13, Ui.TEXT_3);
    private final Paint hint = Ui.text(500, 14, Ui.TEXT_2);
    private final Paint keycap = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint keycapText = Ui.text(600, 12, Ui.INK);
    private final Paint barBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barFg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrim = new Paint();
    private final Paint icon = Icons.stroke(Ui.WHITE, Ui.dp(40));
    private final RectF r = new RectF();
    private final List<Card> cards = new ArrayList<>();
    private int focus;
    private float scroll;
    private boolean loading = true;
    private String status = "";
    private boolean limited;          // no adb: list recent apps, "close" opens App info
    private long memTotal, memAvail, freedKb = -1;
    private float memShown = -1f;

    // Four cards fill the safe width exactly (same size as the favorites).
    private static final float CW = 198, CH = 111.4f, GAP = 24, TOP = 240;

    TasksSheet(HomeActivity home) {
        super(home);
        tv = new TvArt(home, home.ambient);
        addView(tv);
        for (Paint p : new Paint[]{h1, h2, small, section, hint}) p.setTextAlign(Ui.startAlign());
        name.setTextAlign(Paint.Align.CENTER);
        meta.setTextAlign(Paint.Align.CENTER);
        keycapText.setTextAlign(Paint.Align.CENTER);
        keycap.setColor(0xE6FFFFFF);
        barBg.setColor(0x26FFFFFF);
        readMemory();
        load();
    }

    private void readMemory() {
        ActivityManager am = getContext().getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        memTotal = mi.totalMem;
        memAvail = mi.availMem;
        invalidate();
    }

    private void load() {
        loading = true;
        home.refreshTasks((open, adbState) -> {
            loading = false;
            limited = adbState != Adb.READY;
            List<Item> items = new ArrayList<>(open);
            if (limited) {
                items.clear();
                long cutoff = System.currentTimeMillis() - 4 * 3600_000L;
                for (Item it : home.catalog.byKey.values()) {
                    if (it.isApp() && it.lastUsed > cutoff) items.add(it);
                }
                items.sort((a, b) -> Long.compare(b.lastUsed, a.lastUsed));
                status = adbState == Adb.WAITING_APPROVAL
                        ? "אשרו את החיבור בחלון שעל המסך וסמנו \"אפשר תמיד\" — ואז תוכלו לסגור אפליקציות מכאן"
                        : "מצב מוגבל: אלה האפליקציות שנפתחו לאחרונה. סגירה תפתח את דף האפליקציה (עצירה בכוח)";
            } else {
                status = "";
            }
            setItems(items);
        });
    }

    private void setItems(List<Item> items) {
        for (Card c : cards) removeView(c);
        cards.clear();
        for (Item it : items) addCard(it);
        if (!items.isEmpty() && !limited) {
            Item all = new Item(Item.CLOSE_ALL, "close-all");
            all.label = "סגור הכל";
            all.glow = 0xFFFF5A5F;
            addCard(all);
        }
        focus = 0;
        scroll = 0;
        if (!cards.isEmpty()) cards.get(0).setFocusedState(true, true);
        requestLayout();
        invalidate();
        for (int i = 0; i < cards.size(); i++) {
            Card c = cards.get(i);
            c.visibility = startOf(i) + cardW(c) <= getWidth() - 2 * Ui.dp(Ui.SAFE_X) + Ui.dp(12) ? 1f : 0.32f;
            c.setAlpha(0f);
            c.setTranslationY(Ui.dp(30));
            c.animate().alpha(c.visibility).translationY(0).setStartDelay(i * 50L).setDuration(Ui.ms(520)).setInterpolator(Ui.springSoft()).start();
        }
    }

    private void addCard(Item it) {
        Card c = new Card(getContext(), it, 16);
        c.scaleFocused = 1.08f;
        cards.add(c);
        addView(c);
    }

    // ------------------------------------------------------------------ layout & drawing

    private float cardW(Card c) { return c.item.type == Item.CLOSE_ALL ? Ui.dp(150) : Ui.dp(CW); }

    private float startOf(int i) {
        float s = 0;
        for (int k = 0; k < i; k++) s += cardW(cards.get(k)) + Ui.dp(GAP);
        return s;
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        super.onMeasure(wSpec, hSpec);
        int ch = MeasureSpec.makeMeasureSpec(Ui.dpi(CH), MeasureSpec.EXACTLY);
        for (Card c : cards) c.measure(MeasureSpec.makeMeasureSpec(Math.round(cardW(c)), MeasureSpec.EXACTLY), ch);
        tv.measure(MeasureSpec.makeMeasureSpec(Ui.dpi(176), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Ui.dpi(120), MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int rr, int b) {
        int w = rr - l;
        float safe = Ui.dp(Ui.SAFE_X);
        int tvx = Math.round(Ui.rtl ? w - safe - tv.getMeasuredWidth() : safe);
        int tvy = Math.round(Ui.dp(Ui.SAFE_Y + 14));
        tv.layout(tvx, tvy, tvx + tv.getMeasuredWidth(), tvy + tv.getMeasuredHeight());
        for (int i = 0; i < cards.size(); i++) {
            Card c = cards.get(i);
            float s = startOf(i) - scroll;
            float x = Ui.rtl ? w - safe - s - cardW(c) : safe + s;
            int y = Math.round(Ui.dp(TOP));
            c.layout(Math.round(x), y, Math.round(x + cardW(c)), y + Math.round(Ui.dp(CH)));
            // Same rule as the home rows: only cards inside the safe area at full strength.
            float lo = safe - Ui.dp(12), hi = w - safe + Ui.dp(12);
            float onScreen = Math.max(0, Math.min(x + cardW(c), w) - Math.max(x, 0)) / cardW(c);
            float vis = x >= lo && x + cardW(c) <= hi ? 1f : onScreen >= 0.3f ? 0.32f : 0f;
            if (vis != c.visibility && !c.closing) {
                c.visibility = vis;
                c.animate().alpha(vis).setDuration(Ui.ms(260)).start();
            }
        }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        scrim.setColor(0xD9050608); // flat: this TV's GPU can't shade a full-screen gradient per frame
    }

    @Override protected void dispatchDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawRect(0, 0, w, h, scrim);
        float safe = Ui.dp(Ui.SAFE_X);
        float sx = Ui.rtl ? w - safe - Ui.dp(176) - Ui.dp(24) : safe + Ui.dp(176) + Ui.dp(24);
        float y0 = Ui.dp(Ui.SAFE_Y + 44);
        c.drawText(Device.get().name, sx, y0, h1);
        c.drawText((Device.get().screen.isEmpty() ? "" : Device.get().screen + " · ") + Device.get().platform, sx, y0 + Ui.dp(24), h2);
        // memory bar
        float used = memTotal == 0 ? 0 : 1f - (float) memAvail / memTotal;
        if (memShown < 0) memShown = used;
        memShown += (used - memShown) * 0.2f;
        if (Math.abs(used - memShown) > 0.002f) postInvalidateOnAnimation();
        float bw = Ui.dp(320), bh = Ui.dp(10);
        float bx = Ui.rtl ? sx - bw : sx, by = y0 + Ui.dp(44);
        r.set(bx, by, bx + bw, by + bh);
        c.drawRoundRect(r, bh / 2, bh / 2, barBg);
        float fw = bw * Math.max(0.02f, Math.min(1, memShown));
        if (Ui.rtl) r.set(bx + bw - fw, by, bx + bw, by + bh);
        else r.set(bx, by, bx + fw, by + bh);
        barFg.setShader(new LinearGradient(r.left, 0, r.right, 0,
                Ui.rtl ? 0xFFFF7A59 : 0xFF33D6C5, Ui.rtl ? 0xFF33D6C5 : 0xFFFF7A59, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, bh / 2, bh / 2, barFg);
        String memText = "זיכרון: " + Tasks.gb(memAvail) + " פנוי מתוך " + Tasks.gb(memTotal);
        if (freedKb > 0) memText += "  ·  שוחררו " + Tasks.mb(freedKb);
        c.drawText(memText, sx, by + Ui.dp(30), small);

        // section title
        float tx = Ui.rtl ? w - safe : safe;
        String head = limited ? "נפתחו לאחרונה" : "אפליקציות פתוחות";
        int count = 0;
        for (Card cd : cards) if (cd.item.type != Item.CLOSE_ALL) count++;
        c.drawText(head + (count > 0 ? "  ·  " + count : ""), tx, Ui.dp(TOP - 24), section);
        if (!status.isEmpty()) c.drawText(Ui.fit(small, status, w - 2 * safe), tx, Ui.dp(TOP - 6) + Ui.dp(4), small);

        super.dispatchDraw(c);

        // names under the cards (cards fade in on the render thread, so labels use the card's target alpha)
        for (Card cd : cards) {
            if (cd.item.type == Item.CLOSE_ALL || cd.closing) continue;
            float cx = cd.getLeft() + cd.getWidth() / 2f + cd.getTranslationX();
            float yb = cd.getBottom() + Ui.dp(32);
            name.setAlpha(Math.round(255 * cd.visibility));
            meta.setAlpha(Math.round(200 * cd.visibility));
            if (cd.visibility < 0.5f) continue;
            c.drawText(Ui.fit(name, cd.item.label, Ui.dp(CW)), cx, yb, name);
            String m = limited ? Tasks.ago(System.currentTimeMillis() - cd.item.lastUsed)
                    : Tasks.mb(cd.item.rssKb) + (cd.item.inactiveMs >= 0 ? " · " + Tasks.ago(cd.item.inactiveMs) : "");
            c.drawText(m, cx, yb + Ui.dp(20), meta);
        }

        if (loading) {
            c.drawText("בודק מה פתוח…", tx, Ui.dp(TOP + 70), section);
        } else if (cards.isEmpty()) {
            float cx = w / 2f, cy = Ui.dp(TOP + 64);
            icon.setColor(Ui.OK_GREEN);
            Icons.draw(c, Icons.CHECK, cx, cy, Ui.dp(44), icon, 0);
            section.setTextAlign(Paint.Align.CENTER);
            c.drawText("הכל סגור", cx, cy + Ui.dp(54), section);
            section.setTextAlign(Ui.startAlign());
            meta.setAlpha(255);
            c.drawText("אין אפליקציות שפועלות ברקע", cx, cy + Ui.dp(78), meta);
        }

        // key hints
        float hy = h - Ui.dp(Ui.SAFE_Y + 14);
        float hx = Ui.rtl ? w - safe : safe;
        hx = drawHint(c, hx, hy, "OK", "מעבר לאפליקציה");
        if (!cards.isEmpty()) hx = drawHint(c, hx, hy, "▲", limited ? "פרטי אפליקציה" : "סגירה");
        drawHint(c, hx, hy, "חזרה", "");
    }

    private float drawHint(Canvas c, float x, float y, String key, String label) {
        float kw = Math.max(Ui.dp(30), keycapText.measureText(key) + Ui.dp(16)), kh = Ui.dp(24);
        float kx = Ui.rtl ? x - kw : x;
        r.set(kx, y - kh + Ui.dp(6), kx + kw, y + Ui.dp(6));
        c.drawRoundRect(r, Ui.dp(7), Ui.dp(7), keycap);
        c.drawText(key, r.centerX(), r.centerY() + Ui.dp(4.2f), keycapText);
        float lx = Ui.rtl ? kx - Ui.dp(10) : kx + kw + Ui.dp(10);
        if (!label.isEmpty()) c.drawText(label, lx, y + Ui.dp(1), hint);
        float lw = label.isEmpty() ? 0 : hint.measureText(label);
        return Ui.rtl ? lx - lw - Ui.dp(30) : lx + lw + Ui.dp(30);
    }

    // ------------------------------------------------------------------ keys

    private void setFocus(int i) {
        if (cards.isEmpty()) return;
        i = Math.max(0, Math.min(i, cards.size() - 1));
        if (i != focus && focus < cards.size()) cards.get(focus).setFocusedState(false, true);
        focus = i;
        cards.get(focus).setFocusedState(true, true);
        float view = getWidth() - 2 * Ui.dp(Ui.SAFE_X);
        float s = startOf(focus), e = s + cardW(cards.get(focus));
        if (e - scroll > view - Ui.dp(40)) scroll = e - view + Ui.dp(40);
        if (s - scroll < 0) scroll = Math.max(0, s - Ui.dp(40));
        requestLayout();
    }

    @Override boolean onKey(int keyCode, KeyEvent e) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                setFocus(focus + (Ui.rtl ? 1 : -1));
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                setFocus(focus + (Ui.rtl ? -1 : 1));
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (e.getRepeatCount() == 0) closeFocused();
                return true;
            case KeyEvent.KEYCODE_BACK:
                close();
                return true;
            default:
                return true;
        }
    }

    @Override void onOk() {
        if (cards.isEmpty()) return;
        Card c = cards.get(focus);
        if (c.item.type == Item.CLOSE_ALL) {
            closeAll();
            return;
        }
        close();
        home.launch(c.item, null);
    }

    private void closeFocused() {
        if (cards.isEmpty()) return;
        Card c = cards.get(focus);
        if (c.item.type == Item.CLOSE_ALL) {
            closeAll();
            return;
        }
        if (limited) {
            home.openAppInfo(c.item);
            return;
        }
        long before = memAvail;
        home.forceStop(c.item, () -> {
            postDelayed(() -> {
                readMemory();
                long diff = (memAvail - before) / 1024;
                if (diff > 0) freedKb = Math.max(0, freedKb) + diff;
                invalidate();
            }, 700);
        });
        removeCard(c);
    }

    private void removeCard(Card c) {
        c.closing = true;
        invalidate();
        c.animate().translationY(-Ui.dp(150)).alpha(0f).setDuration(Ui.ms(260)).setInterpolator(Ui.EASE_IN).withEndAction(() -> {
            int idx = cards.indexOf(c);
            if (idx < 0) return;
            float[] old = new float[cards.size()];
            for (int i = 0; i < cards.size(); i++) old[i] = cards.get(i).getLeft();
            cards.remove(idx);
            removeView(c);
            boolean onlyCloseAll = cards.size() == 1 && cards.get(0).item.type == Item.CLOSE_ALL;
            if (onlyCloseAll) {
                Card all = cards.remove(0);
                removeView(all);
            }
            focus = Math.min(idx, Math.max(0, cards.size() - 1));
            // Pull the row back so no empty space is left at its end.
            float content = cards.isEmpty() ? 0 : startOf(cards.size() - 1) + cardW(cards.get(cards.size() - 1));
            float view = getWidth() - 2 * Ui.dp(Ui.SAFE_X);
            scroll = Math.max(0, Math.min(scroll, content - view + Ui.dp(20)));
            if (!cards.isEmpty()) {
                float s = startOf(focus);
                if (s - scroll < 0) scroll = Math.max(0, s - Ui.dp(40));
            }
            requestLayout();
            post(() -> {
                for (int i = 0; i < cards.size(); i++) {
                    Card k = cards.get(i);
                    int from = i >= idx ? i + 1 : i;
                    if (from < old.length) {
                        k.setTranslationX(old[from] - k.getLeft());
                        k.animate().translationX(0).setDuration(Ui.ms(380)).setInterpolator(Ui.springSoft()).start();
                    }
                }
                if (!cards.isEmpty()) {
                    for (Card k : cards) k.setFocusedState(false, false);
                    cards.get(focus).setFocusedState(true, true);
                }
                invalidate();
            });
        }).start();
    }

    private void closeAll() {
        long before = memAvail;
        List<Item> victims = new ArrayList<>();
        for (Card c : cards) if (c.item.type != Item.CLOSE_ALL) victims.add(c.item);
        home.forceStopAll(victims, () -> postDelayed(() -> {
            readMemory();
            long diff = (memAvail - before) / 1024;
            freedKb = Math.max(0, diff);
            invalidate();
        }, 900));
        for (int i = 0; i < cards.size(); i++) {
            Card c = cards.get(i);
            c.closing = true;
            c.animate().translationY(-Ui.dp(150)).alpha(0f).setStartDelay(i * 60L).setDuration(Ui.ms(260))
                    .setInterpolator(Ui.EASE_IN).start();
        }
        invalidate();
        postDelayed(() -> {
            for (Card c : cards) removeView(c);
            cards.clear();
            invalidate();
        }, 260 + cards.size() * 60L);
    }

    @Override void onDataChanged() {
        tv.invalidate();
    }

    void tickArt() { tv.invalidate(); }
}
