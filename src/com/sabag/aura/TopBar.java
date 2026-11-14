package com.sabag.aura;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.format.DateFormat;
import android.widget.FrameLayout;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Clock, greeting and date at the reading start; glass chips (network, open apps, settings) at the end. */
final class TopBar extends FrameLayout {
    final Chip network, tasks, settings;
    final List<Chip> chips = new ArrayList<>();
    private final Paint clock = Ui.text(500, 38, Ui.WHITE);
    private final Paint greet = Ui.text(600, 16, Ui.WHITE);
    private final Paint date = Ui.text(400, 13, Ui.TEXT_2);
    private final Paint divider = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE · d בMMMM", new Locale("iw"));
    private String time = "", greeting = "", day = "";
    boolean clock24 = true;
    private int focus = -1;

    TopBar(Context c) {
        super(c);
        setClipChildren(false);
        setWillNotDraw(false);
        clock.setTextAlign(Ui.startAlign());
        greet.setTextAlign(Ui.startAlign());
        date.setTextAlign(Ui.startAlign());
        clock.setLetterSpacing(-0.02f);
        divider.setColor(0x33FFFFFF);
        network = new Chip(c, Icons.WIFI);
        tasks = new Chip(c, Icons.MEMORY);
        settings = new Chip(c, Icons.GEAR);
        // Reading order (start → end): network, open apps, settings at the far end.
        for (Chip ch : new Chip[]{network, tasks, settings}) {
            chips.add(ch);
            addView(ch, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        }
        tick();
    }

    void tick() {
        Calendar cal = Calendar.getInstance();
        Date now = cal.getTime();
        time = DateFormat.format(clock24 ? "HH:mm" : "h:mm", now).toString();
        int h = cal.get(Calendar.HOUR_OF_DAY);
        greeting = h < 5 ? "לילה טוב" : h < 12 ? "בוקר טוב" : h < 17 ? "צהריים טובים" : h < 21 ? "ערב טוב" : "לילה טוב";
        day = dateFmt.format(now);
        invalidate();
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        float x = Ui.dp(Ui.SAFE_X);
        // Lay chips from the end edge inward: settings, tasks, network.
        float gapBetween = Ui.dp(12);
        float cursor = x;
        for (int i = chips.size() - 1; i >= 0; i--) {
            Chip ch = chips.get(i);
            int cw = ch.getMeasuredWidth(), chh = ch.getMeasuredHeight();
            int left = Math.round(Ui.rtl ? cursor : w - cursor - cw);
            int top = Math.round(Ui.dp(Ui.SAFE_Y) + Ui.dp(4));
            ch.layout(left, top, left + cw, top + chh);
            cursor += cw + gapBetween;
        }
    }

    @Override protected void onDraw(Canvas c) {
        float sx = Ui.rtl ? getWidth() - Ui.dp(Ui.SAFE_X) : Ui.dp(Ui.SAFE_X);
        float base = Ui.dp(Ui.SAFE_Y) + Ui.dp(38);
        c.drawText(time, sx, base, clock);
        float tw = clock.measureText(time);
        float dx = Ui.rtl ? sx - tw - Ui.dp(16) : sx + tw + Ui.dp(16);
        c.drawRect(dx - Ui.dp(0.6f), base - Ui.dp(30), dx + Ui.dp(0.6f), base - Ui.dp(2), divider);
        float gx = Ui.rtl ? dx - Ui.dp(16) : dx + Ui.dp(16);
        c.drawText(greeting, gx, base - Ui.dp(17), greet);
        c.drawText(day, gx, base + Ui.dp(1), date);
    }

    // ------------------------------------------------------------------ focus

    int focus() { return focus; }

    void setFocus(int i) {
        if (focus >= 0 && focus < chips.size()) chips.get(focus).setFocusedState(false);
        focus = i;
        if (focus >= 0 && focus < chips.size()) chips.get(focus).setFocusedState(true);
    }

    /** Visual move: +1 = right on screen. */
    boolean moveVisual(int dir) {
        int logical = Ui.rtl ? -dir : dir;
        int next = focus + logical;
        if (next < 0 || next >= chips.size()) return false;
        setFocus(next);
        return true;
    }

    void focusNearest(float screenX) {
        int best = 0;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < chips.size(); i++) {
            Chip ch = chips.get(i);
            float d = Math.abs(ch.getX() + ch.getWidth() / 2f - screenX);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        setFocus(best);
    }

    /** Glass changed what it sees through: every chip redraws. */
    void invalidateAll() {
        invalidate();
        for (Chip ch : chips) ch.invalidate();
    }

    Chip focusedChip() { return focus >= 0 && focus < chips.size() ? chips.get(focus) : null; }

    int preferredHeight() { return Math.round(Ui.dp(Ui.SAFE_Y) + Ui.dp(60)); }
}
