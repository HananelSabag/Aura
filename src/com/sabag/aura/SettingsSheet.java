package com.sabag.aura;

import android.animation.ValueAnimator;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Home settings: a glass side panel at the reading end, with background, clock, layout and "about this TV". */
final class SettingsSheet extends Sheet {
    private static final int MAIN = 0, BG = 1, ABOUT = 2, DISPLAY = 3, KEYS = 4, LEARN = 5, PICK = 6;
    // A floating glass panel: 24 dp from the screen edges, every item inside the safe area.
    // Eight rows (full mode) still end inside the safe area: 89 + 8 x 52 = 505 dp.
    private static final float PW = 420, ROW = 52, CARD = 104, EDGE = 24, COLS = 3;

    // Lite has no blur behind sheets, so their glass is nearly opaque to keep the text clean.
    private final Glass glass = new Glass().tint(Ui.lite ? 0xC70F1219 : 0x940F1219).solid(Ui.lite ? 0xF70F1219 : 0xD10F1219).brightness(0.55f);
    private final Paint h1 = Ui.text(600, 26, Ui.WHITE);
    private final Paint label = Ui.text(500, 17, Ui.WHITE);
    private final Paint value = Ui.text(400, 14, Ui.TEXT_3);
    private final Paint swName = Ui.text(500, 14, Ui.WHITE);
    private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sw = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blob = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint icon = Icons.stroke(Ui.WHITE, Ui.dp(22));
    private final RectF r = new RectF();
    private final Matrix m = new Matrix();
    private final RadialGradient unit = new RadialGradient(0, 0, 1, 0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
    private final TvArt tv;
    private int page = MAIN, focus, mainFocus;
    private float pillY = -1, pillH = -1;
    private ValueAnimator pillAnim;
    private boolean confirmReset;
    private final List<String[]> specs = new ArrayList<>();

    SettingsSheet(HomeActivity home) {
        super(home);
        tv = new TvArt(home, home.ambient);
        tv.setVisibility(GONE);
        addView(tv);
        for (Paint p : new Paint[]{h1, label}) p.setTextAlign(Ui.startAlign());
        value.setTextAlign(Ui.rtl ? Paint.Align.LEFT : Paint.Align.RIGHT);
        swName.setTextAlign(Paint.Align.CENTER);
        pill.setColor(Ui.WHITE);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dp(3));
        ring.setColor(Ui.WHITE);
        loadWallThumb();
    }

    private float panelX() { return Ui.rtl ? Ui.dp(EDGE) : getWidth() - Ui.dp(EDGE) - Ui.dp(PW); }
    private float innerStart() { return Ui.rtl ? panelX() + Ui.dp(PW) - Ui.dp(30) : panelX() + Ui.dp(30); }
    /** The inner end edge also respects the screen's safe area. */
    private float innerEnd() {
        return Ui.rtl ? Math.max(panelX() + Ui.dp(30), Ui.dp(Ui.SAFE_X) + Ui.dp(6))
                : Math.min(panelX() + Ui.dp(PW) - Ui.dp(30), getWidth() - Ui.dp(Ui.SAFE_X) - Ui.dp(6));
    }

    // ------------------------------------------------------------------ main list

    // Settings rows. Lite mode has no background motion at all, so that row is not shown there
    // (a row that does nothing reads as broken).
    private static final int R_BG = 0, R_MOTION = 1, R_CLOCK = 2, R_LITE = 3, R_ADB = 4, R_RESET = 5, R_ABOUT = 6, R_SYSTEM = 7,
            R_MEM = 8, R_TASKS = 9, R_DISPLAY = 10, R_KEYS = 11;
    private static final int[] ROW_ICONS = {Icons.PALETTE, Icons.SPARKLE, Icons.CLOCK, Icons.MEMORY, Icons.LINK, Icons.RESET, Icons.INFO,
            Icons.SETTINGS_SYS, Icons.MEMORY, Icons.WINDOWS, Icons.SPARKLE, Icons.LINK};

    /** Main: the memory card first, then the lists. Display holds the look-and-feel switches. */
    private int[] rowIds() {
        if (page == DISPLAY) return Ui.lite ? new int[]{R_CLOCK, R_LITE, R_RESET} : new int[]{R_MOTION, R_CLOCK, R_LITE, R_RESET};
        return new int[]{R_MEM, R_TASKS, R_BG, R_DISPLAY, R_KEYS, R_ABOUT, R_SYSTEM};
    }

    private String[] row(int id) {
        switch (id) {
            case R_BG: {
                String bg = home.prefs.getString("bg", Ambient.DYNAMIC);
                return new String[]{"רקע", Ambient.PHOTO.equals(bg) ? "תמונה שלי" : Ambient.WALL.equals(bg) ? wallName()
                        : Ambient.NAMES[Math.max(0, indexOf(Ambient.IDS, bg))]};
            }
            case R_MOTION: return new String[]{"תנועת הרקע", home.prefs.getBoolean("motion", true) ? "פועלת" : "כבויה"};
            case R_CLOCK: return new String[]{"שעון 24 שעות", home.prefs.getBoolean("clock24", true) ? "16:30" : "4:30"};
            case R_LITE: return new String[]{"מצב קליל", Ui.lite ? "פועל: בלי אנימציות ורקע זז, מהיר יותר" : "כבוי: כל האפקטים"};
            case R_ADB: {
                int st = Adb.get(getContext()).state();
                return new String[]{"ניהול אפליקציות פתוחות", st == Adb.READY ? "מחובר" : st == Adb.WAITING_APPROVAL ? "ממתין לאישור על המסך" : "לחצו לחיבור"};
            }
            case R_RESET: return new String[]{"סידור הבית", confirmReset ? "לחצו שוב לאישור" : "החזרה לסידור המקורי"};
            case R_TASKS: {
                int n = home.openCount();
                return new String[]{"אפליקציות פתוחות", n == 0 ? "לראות מה פועל ברקע" : n == 1 ? "אחת פתוחה עכשיו" : n + " פתוחות עכשיו"};
            }
            case R_KEYS: {
                int n = mappedKeys().size();
                return new String[]{"כפתורי השלט", !Buttons.enabled(getContext()) ? "כפתור פנוי פותח אפליקציה · לא מופעל"
                        : n == 0 ? "כפתור פנוי פותח אפליקציה" : n == 1 ? "כפתור אחד ממופה" : n + " כפתורים ממופים"};
            }
            case R_DISPLAY: return new String[]{"תצוגה", Ui.lite ? "שעון, מצב קליל, סידור הבית" : "תנועת הרקע, שעון, מצב קליל, סידור"};
            case R_ABOUT: return new String[]{"אודות הטלוויזיה", ""};
            default: return new String[]{"הגדרות המערכת", ""};
        }
    }

    private boolean isSwitch(int id) { return id == R_MOTION || id == R_CLOCK || id == R_LITE; }

    private boolean switchOn(int id) {
        if (id == R_MOTION) return home.prefs.getBoolean("motion", true);
        if (id == R_CLOCK) return home.prefs.getBoolean("clock24", true);
        return Ui.lite;
    }

    private static int indexOf(String[] a, String v) {
        for (int i = 0; i < a.length; i++) if (a[i].equals(v)) return i;
        return -1;
    }

    private boolean hasCard() { return page == MAIN; }

    private float rowTop(int i) {
        return Ui.dp(Ui.SAFE_Y + 62) + i * Ui.dp(ROW) + (hasCard() && i > 0 ? Ui.dp(CARD - ROW) : 0);
    }

    private float rowH(int i) { return hasCard() && i == 0 ? Ui.dp(CARD) : Ui.dp(ROW); }

    private void moveFocus(int i, int count) {
        if (i < 0 || i >= count) return;
        focus = i;
        if (page != MAIN) {
            invalidate();
            return;
        }
        if (pillAnim != null) pillAnim.cancel();
        final float y0 = pillY < 0 ? rowTop(i) : pillY, h0 = pillH < 0 ? rowH(i) : pillH;
        final float y1 = rowTop(i), h1 = rowH(i);
        pillAnim = ValueAnimator.ofFloat(0f, 1f).setDuration(Ui.ms(300));
        pillAnim.setInterpolator(Ui.springSoft());
        pillAnim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            pillY = y0 + (y1 - y0) * t;
            pillH = h0 + (h1 - h0) * t;
            invalidate();
        });
        pillAnim.start();
    }

    @Override boolean onKey(int keyCode, KeyEvent e) {
        if (page == MAIN || page == DISPLAY) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP: confirmReset = false; moveFocus(focus - 1, rowIds().length); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: confirmReset = false; moveFocus(focus + 1, rowIds().length); return true;
                case KeyEvent.KEYCODE_BACK:
                    if (page == DISPLAY) back();
                    else close();
                    return true;
                default: return true;
            }
        }
        if (page == KEYS || page == PICK) {
            int n = page == KEYS ? keyRows().size() : pickApps().size() + 1;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP: if (focus > 0) focus--; invalidate(); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: if (focus < n - 1) focus++; invalidate(); return true;
                case KeyEvent.KEYCODE_BACK:
                    if (page == PICK) openKeys(0);
                    else back();
                    return true;
                default: return true;
            }
        }
        if (page == LEARN) {
            // Keys the service passes on are the reserved ones (arrows, OK…): they stay navigation.
            if (keyCode == KeyEvent.KEYCODE_BACK) openKeys(keyRows().size() - 1);
            else {
                learnNote = "הכפתור הזה שמור לניווט · נסו כפתור אחר";
                invalidate();
            }
            return true;
        }
        if (page == BG) {
            int n = bgTiles(), cols = (int) COLS, col = focus % cols;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT: {
                    int dir = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                    int logical = Ui.rtl ? -dir : dir;
                    int nc = col + logical;
                    if (nc >= 0 && nc < cols && focus - col + nc < n) previewBg(focus - col + nc);
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_UP: if (focus >= cols) previewBg(focus - cols); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: if (focus + cols < n) previewBg(focus + cols); else if (focus / cols < (n - 1) / cols) previewBg(n - 1); return true;
                case KeyEvent.KEYCODE_BACK: back(); return true;
                default: return true;
            }
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) back();
        return true;
    }

    private void back() {
        Buttons.learner = null;
        page = MAIN;
        tv.setVisibility(GONE);
        confirmReset = false;
        focus = mainFocus;
        pillY = rowTop(focus);
        pillH = rowH(focus);
        invalidate();
    }

    @Override void onOk() {
        if (page == BG && focus == wallTile() && Ambient.WALL.equals(home.prefs.getString("bg", ""))) {
            // OK on the wallpaper tile again: the next picture, right behind the panel.
            home.nextWallpaper(1);
            loadWallThumb();
            invalidate();
            return;
        }
        if (page == BG || page == ABOUT) {
            back();
            return;
        }
        if (page == KEYS) {
            okKeys();
            return;
        }
        if (page == PICK) {
            okPick();
            return;
        }
        if (page == LEARN) return;
        if (page == MAIN) mainFocus = focus;
        switch (rowIds()[focus]) {
            case R_MEM:
                memStatus = "מנקה…";
                memStatusAt = SystemClock.uptimeMillis();
                invalidate();
                home.cleanMemory(msg -> {
                    memStatus = msg;
                    memStatusAt = SystemClock.uptimeMillis();
                    readMemory();
                    invalidate();
                });
                return;
            case R_TASKS:
                home.openTasks();
                return;
            case R_KEYS:
                openKeys(0);
                return;
            case R_DISPLAY:
                page = DISPLAY;
                focus = 0;
                pillY = rowTop(0);
                pillH = rowH(0);
                break;
            case R_BG:
                page = BG;
                String bg = home.prefs.getString("bg", Ambient.DYNAMIC);
                focus = Ambient.PHOTO.equals(bg) ? Ambient.IDS.length : Ambient.WALL.equals(bg) ? wallTile() : Math.max(0, indexOf(Ambient.IDS, bg));
                loadWallThumb();
                break;
            case R_MOTION:
                home.setMotion(!home.prefs.getBoolean("motion", true));
                break;
            case R_CLOCK:
                home.setClock24(!home.prefs.getBoolean("clock24", true));
                break;
            case R_LITE:
                home.setLite(!Ui.lite);
                return;
            case R_ADB:
                home.connectAdb(this::invalidate);
                break;
            case R_RESET:
                if (confirmReset) {
                    confirmReset = false;
                    home.resetLayout();
                } else {
                    confirmReset = true;
                }
                break;
            case R_ABOUT:
                page = ABOUT;
                buildSpecs();
                tv.setTiles(home.favoriteGlows());
                tv.setVisibility(VISIBLE);
                requestLayout();
                break;
            case R_SYSTEM:
                close();
                home.openSystemSettings();
                return;
        }
        invalidate();
    }

    // ------------------------------------------------------------------ background page

    private void previewBg(int i) {
        focus = i;
        if (i == Ambient.IDS.length) home.setBackground(Ambient.PHOTO);
        else if (i == wallTile()) home.setBackground(Ambient.WALL);
        else home.setBackground(Ambient.IDS[i]);
        invalidate();
    }

    // The wallpaper collection (pictures copied to the TV) is one more tile; OK on it shows the next one.
    private android.graphics.Bitmap wallThumb;
    private int wallCount = -1;

    private int wallTile() { return Ambient.IDS.length + 1; }

    private int bgTiles() { return wallCount > 0 ? Ambient.IDS.length + 2 : Ambient.IDS.length + 1; }

    private String wallName() {
        int n = Math.max(0, wallCount);
        return n == 0 ? "טפטים" : home.wallpaperCollection() + " · " + Ui.ltr((home.wallpaperIndex() + 1) + "/" + n);
    }

    private void loadWallThumb() {
        new Thread(() -> {
            java.util.List<java.io.File> all = home.wallpapers();
            android.graphics.Bitmap t = all.isEmpty() ? null : Wallpapers.thumb(all.get(home.wallpaperIndex()));
            post(() -> {
                wallCount = all.size();
                wallThumb = t;
                invalidate();
            });
        }).start();
    }

    private void drawBgPage(Canvas c) {
        float sx = innerStart();
        c.drawText("רקע", sx, Ui.dp(Ui.SAFE_Y + 44), h1);
        String about = focus == 0 ? "הרקע מקבל את הצבעים של האפליקציה שעומדים עליה"
                : focus == Ambient.IDS.length ? "התמונה האחרונה שנשמרה בטלוויזיה (תיקיית Pictures)"
                : focus == wallTile() ? "OK שוב: התמונה הבאה (" + wallName() + ")"
                : "צבעים קבועים שזזים לאט";
        value.setColor(Ui.TEXT_2);
        c.drawText(about, sx, Ui.dp(Ui.SAFE_Y + 70), value);
        float inner = Math.abs(sx - innerEnd());
        int cols = (int) COLS;
        float gap = Ui.dp(14), tw = (inner - gap * (cols - 1)) / cols, th = tw * 9f / 16f;
        int n = bgTiles();
        for (int i = 0; i < n; i++) {
            int col = i % cols, row = i / cols;
            float s = col * (tw + gap);
            float x = Ui.rtl ? sx - s - tw : sx + s;
            float y = Ui.dp(Ui.SAFE_Y + 90) + row * (th + Ui.dp(34));
            r.set(x, y, x + tw, y + th);
            drawSwatch(c, i, r);
            c.drawText(i == Ambient.IDS.length ? "תמונה שלי" : i == wallTile() ? home.wallpaperCollection() : Ambient.NAMES[i], r.centerX(), r.bottom + Ui.dp(20), swName);
            if (i == focus) {
                r.inset(-Ui.dp(5), -Ui.dp(5));
                c.drawRoundRect(r, Ui.dp(18), Ui.dp(18), ring);
            }
        }
    }

    /** The "app colors" preview cycles through the favorites' own colors, so its meaning shows itself. */
    private int[] appColorsNow() {
        List<int[]> pals = new ArrayList<>();
        for (Item it : home.catalog.list(Catalog.FAV)) pals.add(Art.ambient(it.palette));
        for (Item it : home.catalog.list(Catalog.APPS)) if (it.palette != null && pals.size() < 6) pals.add(Art.ambient(it.palette));
        if (pals.isEmpty()) return Ambient.preset("aurora");
        float t = home.ambient.seconds() / 2.2f;
        int a = (int) t % pals.size(), b = (a + 1) % pals.size();
        float f = Math.max(0, (t - (int) t - 0.7f) / 0.3f);
        int[] out = new int[4];
        for (int k = 0; k < 4; k++) out[k] = Ui.mix(pals.get(a)[k], pals.get(b)[k], f);
        return out;
    }

    private final android.graphics.Path clipPath = new android.graphics.Path();
    private final Paint thumbPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final int[] filterColor = new int[4];
    private final android.graphics.PorterDuffColorFilter[] filters = new android.graphics.PorterDuffColorFilter[4];

    /** The swatch blobs' tints, rebuilt only when their color changes (they were allocated every frame). */
    private android.graphics.ColorFilter tintFilter(int k, int color) {
        if (filters[k] == null || filterColor[k] != color) {
            filterColor[k] = color;
            filters[k] = new android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.MULTIPLY);
        }
        return filters[k];
    }

    private void drawSwatch(Canvas c, int i, RectF box) {
        float rad = Ui.dp(14);
        sw.setColor(0xFF07080C);
        c.drawRoundRect(box, rad, rad, sw);
        if (i == wallTile() && wallThumb != null) {
            c.save();
            clipPath.reset();
            clipPath.addRoundRect(box, rad, rad, android.graphics.Path.Direction.CW);
            c.clipPath(clipPath);
            c.drawBitmap(wallThumb, null, box, thumbPaint);
            c.restore();
            return;
        }
        int[] col;
        if (i == 0) col = appColorsNow();
        else if (i >= Ambient.IDS.length) col = null;
        else col = Ambient.preset(Ambient.IDS[i]);
        c.save();
        clipPath.reset();
        clipPath.addRoundRect(box, rad, rad, android.graphics.Path.Direction.CW);
        c.clipPath(clipPath);
        if (col != null) {
            for (int k = 0; k < 4; k++) {
                float cx = box.left + box.width() * (k % 2 == 0 ? 0.25f : 0.8f);
                float cy = box.top + box.height() * (k < 2 ? 0.2f : 0.95f);
                float rr = box.width() * 0.55f;
                m.setScale(rr, rr);
                m.postTranslate(cx, cy);
                unit.setLocalMatrix(m);
                blob.setShader(unit);
                blob.setColorFilter(tintFilter(k, Ui.alpha(col[k], 0.8f)));
                c.drawCircle(cx, cy, rr, blob);
            }
        } else {
            icon.setColor(Ui.TEXT_2);
            Icons.draw(c, Icons.PALETTE, box.centerX(), box.centerY(), Ui.dp(28), icon, 0);
        }
        c.restore();
    }

    // ------------------------------------------------------------------ about page

    private void buildSpecs() {
        specs.clear();
        android.app.ActivityManager am = getContext().getSystemService(android.app.ActivityManager.class);
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        long up = SystemClock.elapsedRealtime() / 60000;
        String name = Settings.Global.getString(getContext().getContentResolver(), "device_name");
        specs.add(new String[]{"שם", name == null ? Device.get().logo : name});
        specs.add(new String[]{"דגם", Device.get().name + " (" + Build.DEVICE + ")"});
        specs.add(new String[]{"מסך", (Device.get().screen.isEmpty() ? "" : Device.get().screen + " · ") + "ממשק 1080p"});
        specs.add(new String[]{"מערכת", Device.get().platform + (Ui.lite ? " · מצב קליל" : "")});
        specs.add(new String[]{"מעבד", Device.get().chip + " · " + Runtime.getRuntime().availableProcessors() + " ליבות"});
        specs.add(new String[]{"זיכרון", Tasks.gb(mi.totalMem) + " · פנוי " + Tasks.gb(mi.availMem)});
        specs.add(new String[]{"אחסון", "פנוי " + Ui.ltr(String.format(Locale.US, "%.1f GB", fs.getAvailableBytes() / 1e9f))
                + " מתוך " + Ui.ltr(String.format(Locale.US, "%.1f GB", fs.getTotalBytes() / 1e9f))});
        specs.add(new String[]{"רשת", home.networkSummary()});
        long d = up / 1440, h = up % 1440 / 60, m = up % 60;
        String uptime = d > 0 ? (d == 1 ? "יום אחד" : d + " ימים") + (h > 0 ? " ו-" + h + " שעות" : "")
                : h > 0 ? h + " שעות" + (m > 0 ? " ו-" + m + " דקות" : "") : m + " דקות";
        specs.add(new String[]{"פועלת ברציפות", uptime});
    }

    @Override protected void onLayout(boolean changed, int l, int t, int rr, int b) {
        int w = Ui.dpi(262), h = Ui.dpi(180);
        tv.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        float cx = panelX() + Ui.dp(PW) / 2f + (Ui.rtl ? Ui.dp(8) : -Ui.dp(8));
        int x = Math.round(cx - w / 2f), y = Math.round(Ui.dp(Ui.SAFE_Y + 56));
        tv.layout(x, y, x + w, y + h);
    }

    private void drawAboutPage(Canvas c) {
        float sx = innerStart(), ex = innerEnd();
        c.drawText("אודות הטלוויזיה", sx, Ui.dp(Ui.SAFE_Y + 44), h1);
        float y = Ui.dp(Ui.SAFE_Y + 56) + Ui.dp(180) + Ui.dp(26);
        for (String[] s : specs) {
            c.drawText(s[0], sx, y, value);
            label.setTextSize(Ui.dp(15));
            Paint.Align a = label.getTextAlign();
            label.setTextAlign(Ui.rtl ? Paint.Align.LEFT : Paint.Align.RIGHT);
            c.drawText(Ui.fit(label, s[1], Ui.dp(270)), ex, y, label);
            label.setTextAlign(a);
            label.setTextSize(Ui.dp(17));
            y += Ui.dp(26);
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override protected void dispatchDraw(Canvas c) {
        float x = panelX();
        glass.draw(c, this, x, Ui.dp(EDGE), x + Ui.dp(PW), getHeight() - Ui.dp(EDGE), Ui.dp(34));
        value.setTextAlign(Ui.startAlign());
        if (page == MAIN || page == DISPLAY) drawMain(c);
        else if (page == KEYS) drawKeys(c);
        else if (page == LEARN) drawLearn(c);
        else if (page == PICK) drawPick(c);
        else if (page == BG) drawBgPage(c);
        else drawAboutPage(c);
        super.dispatchDraw(c);
    }

    private void drawMain(Canvas c) {
        float sx = innerStart(), ex = innerEnd();
        c.drawText(page == DISPLAY ? "תצוגה" : "הגדרות הבית", sx, Ui.dp(Ui.SAFE_Y + 44), h1);
        if (pillY < 0) pillY = rowTop(focus);
        if (pillH < 0) pillH = rowH(focus);
        float px0 = Ui.rtl ? ex - Ui.dp(14) : sx - Ui.dp(14);
        float px1 = Ui.rtl ? sx + Ui.dp(14) : ex + Ui.dp(14);
        if (hasCard()) {
            // The memory card is a widget even when not focused: a quiet glass well.
            r.set(Math.min(px0, px1), rowTop(0) + Ui.dp(5), Math.max(px0, px1), rowTop(0) + Ui.dp(CARD) - Ui.dp(5));
            well.setColor(0x14FFFFFF);
            c.drawRoundRect(r, Ui.dp(18), Ui.dp(18), well);
        }
        r.set(Math.min(px0, px1), pillY + Ui.dp(5), Math.max(px0, px1), pillY + pillH - Ui.dp(5));
        c.drawRoundRect(r, Ui.dp(18), Ui.dp(18), pill);
        int[] ids = rowIds();
        for (int i = 0; i < ids.length; i++) {
            float top = rowTop(i);
            float closeness = 1f - Math.min(1f, Math.abs(pillY - top) / Ui.dp(ROW));
            if (ids[i] == R_MEM) {
                drawMemory(c, sx, ex, top, closeness);
                continue;
            }
            String[] rowText = row(ids[i]);
            float cy = top + Ui.dp(ROW) / 2f;
            int ink = Ui.mix(Ui.WHITE, Ui.INK, closeness);
            icon.setColor(ink);
            label.setColor(ink);
            value.setColor(Ui.mix(Ui.TEXT_3, Ui.INK_2, closeness));
            float ix = Ui.rtl ? sx - Ui.dp(12) : sx + Ui.dp(12);
            Icons.draw(c, ROW_ICONS[ids[i]], ix, cy, Ui.dp(22), icon, 0);
            float tx = Ui.rtl ? ix - Ui.dp(24) : ix + Ui.dp(24);
            boolean twoLine = !rowText[1].isEmpty();
            c.drawText(rowText[0], tx, twoLine ? cy - Ui.dp(2) : cy + Ui.dp(6), label);
            if (twoLine) c.drawText(Ui.fit(value, rowText[1], Ui.dp(PW - 150)), tx, cy + Ui.dp(17), value);
            if (isSwitch(ids[i])) drawSwitch(c, ex, cy, switchOn(ids[i]), closeness);
            else {
                float chx = Ui.rtl ? ex + Ui.dp(10) : ex - Ui.dp(10);
                Icons.draw(c, Icons.CHEVRON, chx, cy, Ui.dp(16), icon, 0);
            }
        }
    }

    // ------------------------------------------------------------------ remote buttons

    private int learnedCode;
    private String learnNote;
    private int listScroll;

    /** Mapped keycodes, in key order. */
    private java.util.List<Integer> mappedKeys() {
        java.util.List<Integer> out = new ArrayList<>();
        for (String k : Buttons.prefs(getContext()).getAll().keySet()) {
            if (k.startsWith("k")) try { out.add(Integer.parseInt(k.substring(1))); } catch (NumberFormatException ignored) {}
        }
        java.util.Collections.sort(out);
        return out;
    }

    private String appName(String pkg) {
        Item it = home.catalog.byKey.get(pkg);
        return it != null && !it.label.isEmpty() ? it.label : pkg;
    }

    /** Status, one row per mapped button, "add". Each row: {title, detail, keycode or -1}. */
    private java.util.List<String[]> keyRows() {
        java.util.List<String[]> rows = new ArrayList<>();
        boolean on = Buttons.enabled(getContext());
        rows.add(on ? new String[]{"פעיל", "השירות מקשיב רק לכפתורים הפנויים", "-1"}
                : new String[]{"הפעלה (פעם אחת)", "נגישות › Aura · כפתורי השלט › הפעלה", "-1"});
        for (int code : mappedKeys()) {
            String pkg = Buttons.prefs(getContext()).getString(Buttons.key(code), "");
            rows.add(new String[]{Buttons.name(code), "פותח את " + appName(pkg), String.valueOf(code)});
        }
        rows.add(new String[]{"הוספת כפתור", "לוחצים כאן, ואז על הכפתור בשלט", "-1"});
        return rows;
    }

    /** Apps a button can open: favorites first, then the rest by name. */
    private java.util.List<Item> pickApps() {
        java.util.List<Item> out = new ArrayList<>();
        for (Item it : home.catalog.list(Catalog.FAV)) if (it.type == Item.APP && it.pkg != null) out.add(it);
        java.util.List<Item> rest = new ArrayList<>();
        for (Item it : home.catalog.byKey.values()) {
            if (it.type == Item.APP && it.pkg != null && !out.contains(it) && !it.pkg.equals(getContext().getPackageName())) rest.add(it);
        }
        rest.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        out.addAll(rest);
        return out;
    }

    private void openKeys(int f) {
        Buttons.learner = null;
        page = KEYS;
        focus = Math.max(0, Math.min(f, keyRows().size() - 1));
        listScroll = 0;
        invalidate();
    }

    private void okKeys() {
        java.util.List<String[]> rows = keyRows();
        boolean on = Buttons.enabled(getContext());
        int code = Integer.parseInt(rows.get(focus)[2]);
        if (code >= 0) { // change what a mapped button opens
            learnedCode = code;
            page = PICK;
            focus = 0;
            listScroll = 0;
        } else if (!on) { // "switch on" or "add" before the service is on: Android's accessibility screen
            try {
                getContext().startActivity(new android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                home.openSystemSettings();
            }
            close();
            return;
        } else if (focus == rows.size() - 1) { // add: wait for a press
            page = LEARN;
            learnNote = null;
            Buttons.learner = k -> {
                Buttons.learner = null;
                learnedCode = k;
                page = PICK;
                focus = 0;
                listScroll = 0;
                invalidate();
            };
        }
        invalidate();
    }

    private void okPick() {
        SharedPreferences.Editor e = Buttons.prefs(getContext()).edit();
        if (focus == 0) e.remove(Buttons.key(learnedCode));
        else e.putString(Buttons.key(learnedCode), pickApps().get(focus - 1).pkg);
        e.apply();
        java.util.List<Integer> keys = mappedKeys();
        int i = keys.indexOf(learnedCode);
        openKeys(i >= 0 ? i + 1 : keyRows().size() - 1);
    }

    private void drawKeys(Canvas c) {
        boolean on = Buttons.enabled(getContext());
        drawList(c, "כפתורי השלט", on ? "כפתור פנוי → אפליקציה. ניווט, חזור, בית ועוצמה לא משתנים."
                : "קודם מפעילים פעם אחת בהגדרות הנגישות של הטלוויזיה", keyRows());
    }

    private void drawPick(Canvas c) {
        java.util.List<String[]> rows = new ArrayList<>();
        String now = Buttons.prefs(getContext()).getString(Buttons.key(learnedCode), null);
        rows.add(new String[]{"בלי (הכפתור חוזר לרגיל)", now == null ? "✓" : ""});
        for (Item it : pickApps()) rows.add(new String[]{it.label, it.pkg.equals(now) ? "✓" : ""});
        drawList(c, Buttons.name(learnedCode), "איזו אפליקציה הכפתור יפתח?", rows);
    }

    private void drawLearn(Canvas c) {
        float sx = innerStart();
        c.drawText("כפתורי השלט", sx, Ui.dp(Ui.SAFE_Y + 44), h1);
        label.setColor(Ui.WHITE);
        label.setTextSize(Ui.dp(24));
        c.drawText("לחצו עכשיו על הכפתור בשלט", sx, Ui.dp(Ui.SAFE_Y + 150), label);
        label.setTextSize(Ui.dp(17));
        value.setColor(learnNote != null ? 0xFFFFB347 : Ui.TEXT_2);
        c.drawText(learnNote != null ? learnNote : "כל כפתור פנוי: צבעים, אפליקציות, ☰… · חזור לביטול", sx, Ui.dp(Ui.SAFE_Y + 182), value);
    }

    /** A plain list page with a static white pill and scrolling. Rows: {title, detail}. */
    private void drawList(Canvas c, String title, String sub, java.util.List<String[]> items) {
        float sx = innerStart(), ex = innerEnd();
        c.drawText(Ui.fit(h1, title, Ui.dp(PW - 60)), sx, Ui.dp(Ui.SAFE_Y + 44), h1);
        value.setColor(Ui.TEXT_2);
        c.drawText(Ui.fit(value, sub, Ui.dp(PW - 60)), sx, Ui.dp(Ui.SAFE_Y + 70), value);
        float top0 = Ui.dp(Ui.SAFE_Y + 84);
        int visible = Math.max(1, (int) ((getHeight() - Ui.dp(EDGE) - Ui.dp(14) - top0) / Ui.dp(ROW)));
        if (focus < listScroll) listScroll = focus;
        if (focus >= listScroll + visible) listScroll = focus - visible + 1;
        float px0 = Ui.rtl ? ex - Ui.dp(14) : sx - Ui.dp(14), px1 = Ui.rtl ? sx + Ui.dp(14) : ex + Ui.dp(14);
        for (int i = listScroll; i < Math.min(items.size(), listScroll + visible); i++) {
            float top = top0 + (i - listScroll) * Ui.dp(ROW), cy = top + Ui.dp(ROW) / 2f;
            boolean f = i == focus;
            if (f) {
                r.set(Math.min(px0, px1), top + Ui.dp(5), Math.max(px0, px1), top + Ui.dp(ROW) - Ui.dp(5));
                c.drawRoundRect(r, Ui.dp(18), Ui.dp(18), pill);
            }
            String[] it = items.get(i);
            label.setColor(f ? Ui.INK : Ui.WHITE);
            boolean check = "✓".equals(it[1]);
            boolean twoLine = !it[1].isEmpty() && !check;
            c.drawText(Ui.fit(label, it[0], Ui.dp(PW - 120)), sx, twoLine ? cy - Ui.dp(2) : cy + Ui.dp(6), label);
            if (twoLine) {
                value.setColor(f ? Ui.INK_2 : Ui.TEXT_3);
                c.drawText(Ui.fit(value, it[1], Ui.dp(PW - 90)), sx, cy + Ui.dp(17), value);
            }
            if (check) {
                Paint.Align a = label.getTextAlign();
                label.setTextAlign(Ui.rtl ? Paint.Align.LEFT : Paint.Align.RIGHT);
                c.drawText("✓", ex, cy + Ui.dp(6), label);
                label.setTextAlign(a);
            }
        }
    }

    // ------------------------------------------------------------------ memory card

    private final Paint well = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pct = Ui.text(600, 15, Ui.WHITE);
    private final Paint btn = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnText = Ui.text(600, 14, Ui.WHITE);
    private long memAvail, memTotal;
    private float memShown = -1;
    private String memStatus;
    private long memStatusAt;

    private void readMemory() {
        long[] m = home.memory();
        memAvail = m[0];
        memTotal = m[1];
    }

    private final Runnable memPoll = new Runnable() {
        @Override public void run() {
            if (page == MAIN) {
                readMemory();
                invalidate();
            }
            postDelayed(this, 3000);
        }
    };

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        readMemory();
        post(memPoll);
    }

    @Override protected void onDetachedFromWindow() {
        Buttons.learner = null;
        removeCallbacks(memPoll);
        super.onDetachedFromWindow();
    }

    /** RAM in use as a ring (green → amber → red), the numbers, what cleaning did, and the "ניקוי" button. */
    private void drawMemory(Canvas c, float sx, float ex, float top, float closeness) {
        float used = memTotal == 0 ? 0 : 1f - (float) memAvail / memTotal;
        if (memShown < 0 || Ui.lite) memShown = used;
        memShown += (used - memShown) * 0.18f;
        if (Math.abs(used - memShown) > 0.002f) postInvalidateOnAnimation();
        float cy = top + Ui.dp(CARD) / 2f;
        float ringR = Ui.dp(25), stroke = Ui.dp(6);
        float rx = Ui.rtl ? sx - Ui.dp(4) - ringR : sx + Ui.dp(4) + ringR;
        int ink = Ui.mix(Ui.WHITE, Ui.INK, closeness);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(stroke);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(Ui.mix(0x33FFFFFF, 0x220B0D12, closeness));
        c.drawCircle(rx, cy, ringR, arc);
        int level = used < 0.70f ? 0xFF33D6C5 : used < 0.85f ? 0xFFFFB347 : 0xFFFF7A59;
        arc.setColor(closeness > 0.5f ? Ui.mix(level, 0xFF000000, 0.25f) : level);
        r.set(rx - ringR, cy - ringR, rx + ringR, cy + ringR);
        float sweep = 360f * Math.max(0.02f, Math.min(1f, memShown));
        c.drawArc(r, -90, Ui.rtl ? -sweep : sweep, false, arc);
        pct.setColor(ink);
        pct.setTextAlign(Paint.Align.CENTER);
        c.drawText(Ui.ltr(Math.round(memShown * 100) + "%"), rx, cy + Ui.dp(5), pct);

        float tx = Ui.rtl ? rx - ringR - Ui.dp(18) : rx + ringR + Ui.dp(18);
        label.setColor(ink);
        c.drawText("זיכרון", tx, cy - Ui.dp(14), label);
        value.setColor(Ui.mix(Ui.TEXT_2, Ui.INK_2, closeness));
        c.drawText(Tasks.gb(memTotal - memAvail) + " מתוך " + Tasks.gb(memTotal) + " בשימוש", tx, cy + Ui.dp(6), value);
        boolean fresh = memStatus != null && SystemClock.uptimeMillis() - memStatusAt < 5000;
        if (fresh) {
            value.setColor(closeness > 0.5f ? 0xFF1E8E3E : Ui.OK_GREEN);
            c.drawText("✓ " + memStatus, tx, cy + Ui.dp(25), value);
            postInvalidateDelayed(5000);
        } else {
            value.setColor(Ui.mix(Ui.TEXT_3, Ui.INK_2, closeness));
            int n = home.openCount();
            c.drawText(n == 0 ? "OK לניקוי הזיכרון" : (n == 1 ? "אפליקציה אחת פתוחה" : n + " אפליקציות פתוחות") + " · OK לניקוי", tx, cy + Ui.dp(25), value);
        }

        float bw = Ui.dp(76), bh = Ui.dp(32);
        float bx = Ui.rtl ? ex : ex - bw;
        r.set(bx, cy - bh / 2, bx + bw, cy + bh / 2);
        btn.setColor(Ui.mix(0x29FFFFFF, Ui.INK, closeness));
        c.drawRoundRect(r, bh / 2, bh / 2, btn);
        btnText.setColor(Ui.WHITE);
        btnText.setTextAlign(Paint.Align.CENTER);
        c.drawText("ניקוי", r.centerX(), cy + Ui.dp(5), btnText);
    }

    private void drawSwitch(Canvas c, float endX, float cy, boolean on, float closeness) {
        float w = Ui.dp(46), h = Ui.dp(26);
        float x = Ui.rtl ? endX : endX - w;
        r.set(x, cy - h / 2, x + w, cy + h / 2);
        sw.setColor(on ? Ui.OK_GREEN : Ui.mix(0x40FFFFFF, 0x330B0D12, closeness));
        c.drawRoundRect(r, h / 2, h / 2, sw);
        sw.setColor(Ui.WHITE);
        boolean knobRight = Ui.rtl != on;
        float kx = knobRight ? x + w - h / 2 : x + h / 2;
        c.drawCircle(kx, cy, h / 2 - Ui.dp(3), sw);
    }

    @Override void animateIn() {
        if (Ui.lite) {
            setTranslationX((Ui.rtl ? -1 : 1) * Ui.dp(40));
            animate().translationX(0).setDuration(140).setInterpolator(Ui.EASE_OUT).start();
            return;
        }
        setAlpha(0f);
        setTranslationX((Ui.rtl ? -1 : 1) * Ui.dp(120));
        animate().alpha(1f).translationX(0).setDuration(Ui.ms(480)).setInterpolator(Ui.springSoft()).start();
    }

    @Override void animateOut(Runnable end) {
        if (Ui.lite) {
            end.run();
            return;
        }
        animate().alpha(0f).translationX((Ui.rtl ? -1 : 1) * Ui.dp(80)).setDuration(Ui.ms(200)).setInterpolator(Ui.EASE_IN).withEndAction(end).start();
    }

    @Override boolean large() { return false; }

    @Override void onDataChanged() { invalidate(); }

    /** Only the background and "about" pages show the moving background. */
    @Override void onAmbientFrame() {
        if (page == BG || page == ABOUT) invalidate();
    }
}
