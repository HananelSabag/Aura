package com.sabag.aura;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Aura: the TV's home screen. Owns the layout, the focus model and every action. */
public final class HomeActivity extends Activity {
    interface TasksCallback { void done(List<Item> open, int adbState); }

    SharedPreferences prefs;
    Catalog catalog;
    Ambient ambient;
    private FrameLayout root, content, overlay;
    private TopBar top;
    private Hero hero;
    private Row favRow, appsRow, folderRow;
    private final List<Row> rows = new ArrayList<>();
    private final List<Sheet> sheets = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private NetworkStatus network;
    private NetworkStatus.State net;
    private Adb adb;

    private MoreHint moreHint;
    private View scrim;
    /** The once-per-run settings check over adb has been done. */
    private static boolean maintained;

    /** "Folders ⌄" at the bottom edge while the folders row is below the screen. */
    private static final class MoreHint extends View {
        private final android.graphics.Paint text = Ui.text(500, 14, Ui.TEXT_3);
        private final android.graphics.Paint icon = Icons.stroke(Ui.TEXT_3, Ui.dp(16));

        MoreHint(Context c) {
            super(c);
            text.setTextAlign(android.graphics.Paint.Align.CENTER);
        }

        @Override protected void onDraw(android.graphics.Canvas c) {
            float cx = getWidth() / 2f, base = getHeight() - Ui.dp(8);
            String s = "תיקיות";
            float tw = text.measureText(s);
            c.drawText(s, cx + (Ui.rtl ? Ui.dp(10) : -Ui.dp(10)), base, text);
            float ix = Ui.rtl ? cx - tw / 2f - Ui.dp(4) : cx + tw / 2f + Ui.dp(4);
            float iy = base - Ui.dp(5);
            c.drawLine(ix - Ui.dp(5), iy - Ui.dp(3), ix, iy + Ui.dp(2), icon);
            c.drawLine(ix, iy + Ui.dp(2), ix + Ui.dp(5), iy - Ui.dp(3), icon);
        }
    }

    /** -1 = top bar, otherwise index into rows. */
    private int zone = 0;
    private boolean moving, loaded, okDown, okLong;
    private long okDownAt;
    private final List<Item> openApps = new ArrayList<>();
    private boolean receiversOn;

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { reload(); }
    };
    private final BroadcastReceiver time = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { top.tick(); }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        prefs = getSharedPreferences("aura", MODE_PRIVATE);
        catalog = new Catalog(this, prefs);
        adb = Adb.get(this);
        adb.setStateListener(s -> {
            if (s == Adb.READY) prefs.edit().putBoolean("adbOk", true).apply();
            for (Sheet sh : sheets) sh.onDataChanged();
        });

        root = new FrameLayout(this);
        root.setClipChildren(false);
        ambient = new Ambient(this);
        root.addView(ambient, new FrameLayout.LayoutParams(-1, -1));
        content = new FrameLayout(this);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        // Lite: sheets dim the home with a plain black layer instead of fading/zooming/blurring it.
        scrim = new View(this);
        scrim.setBackgroundColor(0xFF000000);
        scrim.setAlpha(0f);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        overlay = new FrameLayout(this);
        overlay.setClipChildren(false);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        top = new TopBar(this);
        top.clock24 = prefs.getBoolean("clock24", true);
        top.network.action = () -> startActivitySafe(new Intent(Settings.ACTION_WIFI_SETTINGS));
        top.tasks.action = this::cleanFromTop;
        top.settings.action = () -> openSheet(new SettingsSheet(this));
        top.tasks.setLabel(memoryLabel());
        content.addView(top, new FrameLayout.LayoutParams(-1, top.preferredHeight()));
        hero = new Hero(this);
        content.addView(hero, new FrameLayout.LayoutParams(-1, Ui.dpi(124)));
        hero.setY(Ui.dp(60));

        favRow = new Row(this, Catalog.FAV, "מועדפים", 198, 111.4f, 24, 18);
        appsRow = new Row(this, Catalog.APPS, "אפליקציות", 127, 71.4f, 20, 13);
        folderRow = new Row(this, "folders", "תיקיות", 240, 112, 24, 20);
        folderRow.cards.clear();
        for (Row r : new Row[]{favRow, appsRow, folderRow}) {
            rows.add(r);
            content.addView(r, new FrameLayout.LayoutParams(-1, r.preferredHeight()));
        }
        layoutRows();

        moreHint = new MoreHint(this);
        root.addView(moreHint, root.indexOfChild(scrim), new FrameLayout.LayoutParams(-1, Ui.dpi(30), android.view.Gravity.BOTTOM));
        moreHint.setTranslationY(-Ui.dp(Ui.SAFE_Y - 6));

        network = new NetworkStatus(this, s -> {
            net = s;
            if (!s.connected) {
                top.network.setIcon(Icons.NO_WIFI, -1);
                top.network.setLabel("אין רשת");
            } else if (s.wired) {
                top.network.setIcon(Icons.ETHERNET, -1);
                top.network.setLabel("רשת קווית");
            } else {
                int level = s.maxSignalLevel > 0 && s.signalLevel >= 0 ? Math.round(4f * s.signalLevel / s.maxSignalLevel) : -1;
                top.network.setIcon(Icons.WIFI, level);
                top.network.setLabel(s.ssid != null ? s.ssid : "Wi-Fi");
            }
        });

        ambient.setMotion(!Ui.lite && prefs.getBoolean("motion", true));
        ambient.setOnFrame(() -> {
            for (Sheet s : sheets) if (s instanceof TasksSheet || s instanceof SettingsSheet) s.onAmbientFrame();
        });
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        Glass.screenW = dm.widthPixels;
        Glass.screenH = dm.heightPixels;
        Glass.backdrop = ambient.frame();
        // The PC can pick the wallpaper background for the TV (tv\wallpapers.ps1 -Apply leaves this file).
        java.io.File marker = new java.io.File(getExternalFilesDir("wallpapers"), ".apply");
        if (marker.exists()) {
            prefs.edit().putString("bg", Ambient.WALL).apply();
            marker.delete();
        }
        applyBackground(false);
        reload();
    }

    @Override protected void onStart() {
        super.onStart();
        ambient.start();
        network.start();
        top.tick();
        if (!receiversOn) {
            IntentFilter p = new IntentFilter();
            p.addAction(Intent.ACTION_PACKAGE_ADDED);
            p.addAction(Intent.ACTION_PACKAGE_REMOVED);
            p.addAction(Intent.ACTION_PACKAGE_CHANGED);
            p.addAction(Intent.ACTION_PACKAGE_REPLACED);
            p.addDataScheme("package");
            // The exported flag exists from Android 13; Android 11 (the Xiaomi) takes 0.
            int flags = android.os.Build.VERSION.SDK_INT >= 33 ? RECEIVER_EXPORTED : 0;
            registerReceiver(packages, p, flags);
            IntentFilter t = new IntentFilter(Intent.ACTION_TIME_TICK);
            t.addAction(Intent.ACTION_TIME_CHANGED);
            t.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            registerReceiver(time, t, flags);
            receiversOn = true;
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (loaded) {
            reload();
            if (prefs.getBoolean("adbOk", false)) refreshTasks(null);
        }
    }

    @Override protected void onRestart() {
        super.onRestart();
        if (Ui.lite) return;
        content.setAlpha(0.4f);
        content.setScaleX(1.03f);
        content.setScaleY(1.03f);
        content.animate().alpha(sheets.isEmpty() ? 1f : content.getAlpha()).scaleX(1f).scaleY(1f)
                .setDuration(Ui.ms(420)).setInterpolator(Ui.EASE_OUT).start();
    }

    @Override protected void onStop() {
        super.onStop();
        ambient.stop();
        network.stop();
        if (receiversOn) {
            unregisterReceiver(packages);
            unregisterReceiver(time);
            receiversOn = false;
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while home is showing: close overlays, back to the first favorite.
        while (!sheets.isEmpty()) closeSheet(sheets.get(sheets.size() - 1));
        if (moving) endMove();
        if (loaded) {
            setZone(0, true);
            favRow.setFocus(0, true);
            onFocusChanged();
        }
    }

    // ------------------------------------------------------------------ data

    private void reload() {
        worker.execute(() -> {
            Map<String, Item> scanned = catalog.scan();
            ui.post(() -> {
                Item keep = focusedItem();
                catalog.arrange(scanned);
                rebuildRows(keep);
                if (!loaded) {
                    loaded = true;
                    setZone(0, false);
                    favRow.animateIn(80);
                    appsRow.animateIn(200);
                    folderRow.animateIn(320);
                    hero.setAlpha(0f);
                    hero.animate().alpha(1f).setDuration(Ui.ms(600)).setStartDelay(Ui.ms(150)).start();
                    onFocusChanged();
                    if (prefs.getBoolean("adbOk", false)) refreshTasks(null);
                }
                for (Sheet s : sheets) s.onDataChanged();
            });
        });
    }

    /** Re-fills the rows from the catalog, keeping focus on the same item when it is still there. */
    private void rebuildRows(Item keep) {
        List<Item> folders = new ArrayList<>();
        for (String f : catalog.folderOrder()) {
            Item folder = catalog.folders.get(f);
            if (!folder.children.isEmpty()) folders.add(folder);
        }
        int[] focus = new int[rows.size()];
        for (int i = 0; i < rows.size(); i++) focus[i] = rows.get(i).focus();
        favRow.setItems(catalog.list(Catalog.FAV), focus[0]);
        appsRow.setItems(catalog.list(Catalog.APPS), focus[1]);
        folderRow.setItems(folders, focus[2]);
        layoutRows();
        if (keep != null) {
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                for (int k = 0; k < r.size(); k++) {
                    if (r.cards.get(k).item == keep) {
                        if (zone >= 0) {
                            rows.get(zone).setActive(false, false);
                            zone = i;
                        }
                        r.setFocus(k, false);
                    }
                }
            }
        }
        if (zone >= 0) {
            if (rows.get(zone).size() == 0) zone = firstNonEmpty();
            for (int i = 0; i < rows.size(); i++) rows.get(i).setActive(i == zone, false);
        }
        applyScroll(false);
    }

    /**
     * Stacks the rows that have something in them. An empty row (everything moved into folders) is
     * gone entirely: no orphan title, and the rows below move up into its place.
     */
    private void layoutRows() {
        float y = Ui.dp(198);
        for (Row r : rows) {
            boolean shown = r.size() > 0;
            r.setVisibility(shown ? View.VISIBLE : View.GONE);
            if (!shown) continue;
            r.setY(y);
            y += r.preferredHeight() + Ui.dp(4);
        }
    }

    private int firstNonEmpty() {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).size() > 0) return i;
        return 0;
    }

    private Item focusedItem() {
        if (zone < 0 || zone >= rows.size()) return null;
        return rows.get(zone).focusedItem();
    }

    /** Reads which apps are open (through adb) and marks them; the callback gets the open list. */
    void refreshTasks(TasksCallback cb) {
        adb.run(Tasks.QUERY, (out, err) -> {
            openApps.clear();
            for (Item it : catalog.byKey.values()) {
                it.running = false;
                it.rssKb = 0;
                it.inactiveMs = -1;
            }
            if (err == null) {
                for (Tasks.Entry e : Tasks.parse(out, getPackageName(), catalog.byKey.keySet())) {
                    Item it = catalog.byKey.get(e.pkg);
                    if (it == null) continue;
                    it.running = true;
                    it.rssKb = e.rssKb;
                    it.inactiveMs = e.inactiveMs;
                    openApps.add(it);
                }
            }
            if (err == null && !maintained) {
                maintained = true;
                // Some firmware (the Xiaomi) loses device_config values; put the background-process
                // limit back when it is unset (6 on the 2 GB TV, 10 on the bigger one).
                adb.run("v=$(device_config get activity_manager max_cached_processes); [ \"$v\" = null ] && "
                        + "device_config put activity_manager max_cached_processes " + (Ui.lite ? 6 : 10), null);
            }
            if (SystemClock.uptimeMillis() > chipMessageUntil) top.tasks.setLabel(memoryLabel());
            for (Row r : rows) r.refreshStatus();
            onFocusChanged();
            if (cb != null) cb.done(new ArrayList<>(openApps), err == null ? Adb.READY : adb.state());
        });
    }

    void forceStop(Item it, Runnable done) {
        if (it.pkg == null) return;
        adb.run("am force-stop " + it.pkg, (o, e) -> {
            it.running = false;
            for (Row r : rows) r.refreshStatus();
            refreshTasks(null);
            if (done != null) done.run();
        });
    }

    void forceStopAll(List<Item> items, Runnable done) {
        StringBuilder cmd = new StringBuilder();
        for (Item it : items) {
            if (it.pkg == null) continue;
            if (cmd.length() > 0) cmd.append("; ");
            cmd.append("am force-stop ").append(it.pkg);
        }
        if (cmd.length() == 0) return;
        adb.run(cmd.toString(), (o, e) -> {
            refreshTasks(null);
            if (done != null) done.run();
        });
    }

    void connectAdb(Runnable after) {
        adb.run("echo ok", (o, e) -> {
            if (e == null) refreshTasks(null);
            if (after != null) after.run();
        });
        ui.postDelayed(() -> { if (after != null) after.run(); }, 600);
    }

    // ------------------------------------------------------------------ focus & scrolling

    private void setZone(int z, boolean animate) {
        if (zone >= 0 && zone < rows.size()) rows.get(zone).setActive(false, animate);
        if (zone == -1) top.setFocus(-1);
        zone = z;
        if (zone >= 0) rows.get(zone).setActive(true, animate);
        applyScroll(animate);
    }

    private void applyScroll(boolean animate) {
        // Below the favorites the page moves once, so the apps row takes the favorites' place and
        // the folders sit fully inside the safe area; moving between apps and folders then never scrolls.
        float shift = 0;
        if (zone >= 1) {
            // The first row below the favorites takes their place, and the last row must fit.
            Row next = null, last = null;
            for (int i = 1; i < rows.size(); i++) {
                if (rows.get(i).size() == 0) continue;
                if (next == null) next = rows.get(i);
                last = rows.get(i);
            }
            if (next != null) {
                float screenH = root.getHeight() > 0 ? root.getHeight() : Ui.dp(540);
                float lastBottom = last.getY() + last.cardTop() + last.cardH * 1.06f + Ui.dp(8);
                shift = Math.max(next.getY() - favRow.getY(), lastBottom - (screenH - Ui.dp(Ui.SAFE_Y)));
            }
        }
        favRow.setTitleShown(favRow.getY() - shift >= Ui.dp(Ui.SAFE_Y) - Ui.dp(2));
        float heroAlpha = zone <= 0 ? 1f : 0f;
        boolean hint = zone == 0 && folderRow.size() > 0;
        moreHint.animate().alpha(hint ? 1f : 0f).setDuration(Ui.ms(hint ? 500 : 160)).start();
        if (animate) {
            content.animate().translationY(-shift).setDuration(Ui.ms(560)).setInterpolator(Ui.EASE_OUT).start();
            hero.animate().alpha(heroAlpha).setDuration(Ui.ms(300)).start();
            top.animate().alpha(zone <= 0 ? 1f : 0f).setDuration(Ui.ms(300)).start();
        } else {
            content.setTranslationY(-shift);
            hero.setAlpha(heroAlpha);
            top.setAlpha(zone <= 0 ? 1f : 0f);
        }
    }

    private void onFocusChanged() {
        if (zone < 0) {
            Chip ch = top.focusedChip();
            if (ch == top.tasks) {
                long[] m = memory();
                int n = openApps.size();
                hero.show("זיכרון · " + Ui.ltr(memoryPercent() + "%") + " בשימוש",
                        Tasks.gb(m[1] - m[0]) + " מתוך " + Tasks.gb(m[1]) + " · " + (n == 0 ? "אין אפליקציות פתוחות" : n == 1 ? "אפליקציה פתוחה אחת" : n + " אפליקציות פתוחות") + " · OK לניקוי", false);
            }
            else if (ch == top.settings) hero.show("הגדרות הבית", "רקע, שעון, סידור ואודות הטלוויזיה", false);
            else if (ch == top.network) hero.show(net != null && net.ssid != null ? net.ssid : "רשת", networkSummary(), net != null && net.connected);
            return;
        }
        Item it = focusedItem();
        if (it == null) return;
        hero.show(it.label, status(it), it.running);
        if (Ambient.DYNAMIC.equals(prefs.getString("bg", Ambient.DYNAMIC))) {
            ambient.setColors(Art.ambient(it.palette), true);
        }
    }

    String status(Item it) {
        switch (it.type) {
            case Item.INPUT:
                return it.connected ? "מחובר · לחצו למעבר" : "לא מחובר";
            case Item.FOLDER: {
                StringBuilder b = new StringBuilder();
                int n = it.children.size();
                for (int i = 0; i < Math.min(3, n); i++) {
                    if (i > 0) b.append(", ");
                    b.append(it.children.get(i).label);
                }
                if (n > 3) b.append(" ועוד ").append(n - 3);
                return b.toString();
            }
            default:
                if (it.running) return "פתוחה ברקע · " + Tasks.mb(it.rssKb);
                if (it.archived) return "לא מותקנת כרגע · לחיצה תוריד אותה מחדש";
                if (it.lastUsed > 0) return "נפתחה " + when(it.lastUsed);
                return it.system ? "אפליקציית מערכת" : "";
        }
    }

    private static String when(long t) {
        Calendar now = Calendar.getInstance(), then = Calendar.getInstance();
        then.setTimeInMillis(t);
        String hm = new SimpleDateFormat("HH:mm", Locale.US).format(new Date(t));
        int days = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
                + 365 * (now.get(Calendar.YEAR) - then.get(Calendar.YEAR));
        if (days <= 0) return "היום ב-" + hm;
        if (days == 1) return "אתמול ב-" + hm;
        return "לפני " + days + " ימים";
    }

    String networkSummary() {
        if (net == null || !net.connected) return "לא מחובר";
        String ip = "";
        try {
            android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
            android.net.LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
            if (lp != null) for (android.net.LinkAddress a : lp.getLinkAddresses()) {
                if (a.getAddress() instanceof java.net.Inet4Address) ip = a.getAddress().getHostAddress();
            }
        } catch (RuntimeException ignored) {
        }
        String kind = net.wired ? "קווי" : "Wi-Fi";
        return kind + (net.ssid != null ? " · " + net.ssid : "") + (ip.isEmpty() ? "" : " · " + ip);
    }

    int[] favoriteGlows() {
        List<Item> f = catalog.list(Catalog.FAV);
        int[] out = new int[Math.max(1, Math.min(4, f.size()))];
        for (int i = 0; i < out.length; i++) out[i] = f.isEmpty() ? 0xFF5E7CFF : f.get(i).glow;
        return out;
    }

    // ------------------------------------------------------------------ keys

    private static boolean isOk(int k) {
        return k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (isOk(k)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) {
                    okDown = true;
                    okLong = false;
                    okDownAt = e.getEventTime();
                } else if (okDown && !okLong && e.getEventTime() - okDownAt >= 400) {
                    okLong = true;
                    longOk();
                }
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                if (okDown && !okLong) ok();
                okDown = false;
            }
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) {
            return k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_MENU || isDpad(k) || super.dispatchKeyEvent(e);
        }
        if (k == KeyEvent.KEYCODE_MENU) {
            if (e.getRepeatCount() == 0) longOk();
            return true;
        }
        if (!sheets.isEmpty()) {
            Sheet s = sheets.get(sheets.size() - 1);
            if (s.onKey(k, e)) return true;
            return isDpad(k) || k == KeyEvent.KEYCODE_BACK || super.dispatchKeyEvent(e);
        }
        if (!loaded) return true;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT: horizontal(-1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: horizontal(1); return true;
            case KeyEvent.KEYCODE_DPAD_UP: vertical(-1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: vertical(1); return true;
            case KeyEvent.KEYCODE_BACK:
                if (moving) endMove();
                else if (zone != 0 || favRow.focus() != 0) {
                    setZone(0, true);
                    favRow.setFocus(0, true);
                    onFocusChanged();
                }
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private static boolean isDpad(int k) {
        return k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN;
    }

    /** dir: -1 = left on screen, +1 = right on screen. */
    private void horizontal(int dir) {
        int logical = Ui.rtl ? -dir : dir;
        if (zone < 0) {
            if (top.moveVisual(dir)) onFocusChanged();
            return;
        }
        Row r = rows.get(zone);
        if (moving) {
            int from = r.focus();
            if (r.shift(logical)) {
                if (r == folderRow) catalog.swapFolders(r.cards.get(r.focus()).item.folderId, r.cards.get(from).item.folderId);
                else catalog.swap(r.id, from, r.focus());
            }
            return;
        }
        if (r.move(logical)) onFocusChanged();
    }

    private void vertical(int dir) {
        if (moving) return;
        if (zone < 0) {
            if (dir > 0) {
                int z = firstNonEmpty();
                Row r = rows.get(z);
                float x = top.focusedChip() == null ? 0 : top.focusedChip().getX() + top.focusedChip().getWidth() / 2f;
                top.setFocus(-1);
                setZone(z, true);
                r.focusNearest(x, true);
                onFocusChanged();
            }
            return;
        }
        float x = rows.get(zone).focusedScreenCenter();
        int next = zone + dir;
        while (next >= 0 && next < rows.size() && rows.get(next).size() == 0) next += dir;
        if (next < 0) {
            rows.get(zone).setActive(false, true);
            zone = -1;
            top.focusNearest(x);
            applyScroll(true);
            onFocusChanged();
            return;
        }
        if (next >= rows.size()) {
            rows.get(zone).bump(0);
            return;
        }
        setZone(next, true);
        rows.get(next).focusNearest(x, true);
        onFocusChanged();
    }

    private void ok() {
        if (!sheets.isEmpty()) {
            sheets.get(sheets.size() - 1).onOk();
            return;
        }
        if (!loaded) return;
        if (moving) {
            endMove();
            return;
        }
        if (zone < 0) {
            Chip ch = top.focusedChip();
            if (ch != null && ch.action != null) ch.action.run();
            return;
        }
        Row r = rows.get(zone);
        Card c = r.focusedCard();
        if (c != null) launch(c.item, c);
    }

    private void longOk() {
        if (!sheets.isEmpty()) {
            sheets.get(sheets.size() - 1).onLongOk();
            return;
        }
        if (!loaded || zone < 0 || moving) return;
        Row r = rows.get(zone);
        Card c = r.focusedCard();
        if (c == null) return;
        openMenu(c.item, c, r.id);
    }

    // ------------------------------------------------------------------ actions

    void launch(Item it, View from) {
        switch (it.type) {
            case Item.FOLDER:
                openSheet(new FolderSheet(this, it.folderId));
                return;
            case Item.INPUT: {
                Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(it.inputId));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivitySafe(i);
                return;
            }
            case Item.APP: {
                Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(it.pkg);
                if (i == null) i = getPackageManager().getLaunchIntentForPackage(it.pkg);
                if (i == null && it.component != null) i = new Intent(Intent.ACTION_MAIN).setComponent(it.component);
                if (i == null) return;
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                Bundle opts = null;
                if (from != null && from.isAttachedToWindow()) {
                    opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
                }
                try {
                    startActivity(i, opts);
                } catch (RuntimeException e) {
                    startActivitySafe(i);
                }
                return;
            }
            default:
        }
    }

    /** Runs after the current key event, whatever happens to the views involved. */
    void runSoon(Runnable r) { ui.post(r); }

    void startActivitySafe(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (RuntimeException ignored) {
        }
    }

    void openAppInfo(Item it) {
        if (it.pkg == null) return;
        startActivitySafe(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + it.pkg)));
    }

    void openSystemSettings() {
        startActivitySafe(new Intent(Settings.ACTION_SETTINGS));
    }

    void openTasks() {
        openSheet(new TasksSheet(this));
    }

    // ------------------------------------------------------------------ memory

    private long chipMessageUntil;

    /** {available, total} bytes. */
    long[] memory() {
        android.app.ActivityManager am = getSystemService(android.app.ActivityManager.class);
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return new long[]{mi.availMem, mi.totalMem};
    }

    int memoryPercent() {
        long[] m = memory();
        return m[1] == 0 ? 0 : Math.round(100f * (m[1] - m[0]) / m[1]);
    }

    private String memoryLabel() { return "זיכרון " + Ui.ltr(memoryPercent() + "%"); }

    int openCount() { return openApps.size(); }

    /**
     * Closes every open app (never this home) and says what it did. The freed amount is shown only when
     * it is real (Android's free-memory number is noisy): at least 50 MB, rounded to 10.
     */
    void cleanMemory(java.util.function.Consumer<String> done) {
        if (adb.state() != Adb.READY) {
            connectAdb(null);
            done.accept(adb.state() == Adb.WAITING_APPROVAL ? "אשרו את החיבור על המסך ונסו שוב" : "מתחבר לניהול האפליקציות…");
            return;
        }
        long before = memory()[0];
        refreshTasks((open, st) -> {
            if (open.isEmpty()) {
                done.accept("אין עוד מה לסגור");
                return;
            }
            int n = open.size();
            forceStopAll(open, () -> ui.postDelayed(() -> {
                long gainMb = (memory()[0] - before) / 1048576L;
                String s = n == 1 ? "אפליקציה פתוחה אחת נסגרה" : n == 2 ? "שתי אפליקציות נסגרו" : n + " אפליקציות נסגרו";
                if (gainMb >= 50) s += " · התפנו " + Ui.ltr((gainMb / 10 * 10) + " MB");
                done.accept(s);
            }, 800));
        });
    }

    /** OK on the memory chip: clean, and say so on the chip for a few seconds. */
    private void cleanFromTop() {
        top.tasks.setLabel("מנקה…");
        chipMessageUntil = SystemClock.uptimeMillis() + 60_000;
        cleanMemory(msg -> {
            top.tasks.setLabel(msg);
            chipMessageUntil = SystemClock.uptimeMillis() + 4000;
            ui.postDelayed(() -> {
                if (SystemClock.uptimeMillis() >= chipMessageUntil) top.tasks.setLabel(memoryLabel());
            }, 4100);
            onFocusChanged();
        });
    }

    void openMenu(Item it, View anchor, String fromList) {
        List<MenuSheet.Entry> list = MenuSheet.list();
        if (it.type == Item.FOLDER) {
            list.add(new MenuSheet.Entry(Icons.FOLDER, "פתיחה", () -> launch(it, null), false));
            list.add(new MenuSheet.Entry(Icons.MOVE, "שינוי מיקום", this::startMove, false));
            showMenu(it, anchor, list);
            return;
        }
        String where = catalog.listOf(it);
        if (where == null) where = fromList;
        final String inList = where;
        list.add(new MenuSheet.Entry(Icons.PLAY, it.type == Item.INPUT ? "מעבר לכניסה" : "פתיחה", () -> launch(it, null), false));
        if (!Catalog.FAV.equals(inList)) {
            list.add(new MenuSheet.Entry(Icons.STAR, "הוספה למועדפים", () -> moveItem(it, Catalog.FAV), false));
        } else {
            list.add(new MenuSheet.Entry(Icons.STAR_OFF, "הסרה מהמועדפים",
                    () -> moveItem(it, it.type == Item.INPUT ? Catalog.INPUTS : Catalog.APPS), false));
        }
        boolean inRow = Catalog.FAV.equals(inList) || Catalog.APPS.equals(inList);
        if (inRow && sheets.isEmpty()) list.add(new MenuSheet.Entry(Icons.MOVE, "שינוי מיקום", this::startMove, false));
        if (it.type == Item.APP) {
            MenuSheet.Entry move = new MenuSheet.Entry(Icons.FOLDER, "העברה אל…", null, false);
            move.sub = new ArrayList<>();
            for (String target : new String[]{Catalog.APPS, Catalog.TOOLS, Catalog.UNUSED}) {
                if (target.equals(inList)) continue;
                int ic = target.equals(Catalog.APPS) ? Icons.WINDOWS : Icons.FOLDER;
                String label = target.equals(Catalog.APPS) ? "שורת האפליקציות" : "תיקיית " + Catalog.name(target);
                move.sub.add(new MenuSheet.Entry(ic, label, () -> moveItem(it, target), false));
            }
            list.add(move);
            if (it.running && adb.state() == Adb.READY) {
                list.add(new MenuSheet.Entry(Icons.CLOSE, "סגירת האפליקציה", () -> forceStop(it, null), false));
            }
            list.add(new MenuSheet.Entry(Icons.INFO, "פרטי אפליקציה", () -> openAppInfo(it), false));
            if (!it.system) {
                list.add(new MenuSheet.Entry(Icons.TRASH, "הסרת התקנה", () -> startActivitySafe(
                        new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + it.pkg))), true));
            }
        }
        showMenu(it, anchor, list);
    }

    private void showMenu(Item it, View anchor, List<MenuSheet.Entry> list) {
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        float cx = loc[0] + anchor.getWidth() * anchor.getScaleX() / 2f;
        float topY = loc[1], bottomY = loc[1] + anchor.getHeight() * anchor.getScaleY();
        openSheet(new MenuSheet(this, it.label, status(it), list, cx, topY, bottomY));
    }

    private void moveItem(Item it, String target) {
        catalog.moveTo(it, target);
        rebuildRows(focusedItem());
        for (Sheet s : sheets) s.onDataChanged();
        onFocusChanged();
    }

    private void startMove() {
        if (zone < 0) return;
        moving = true;
        rows.get(zone).setMoving(true);
        hero.show("שינוי מיקום", "חיצים להזזה · OK לסיום", false);
    }

    private void endMove() {
        moving = false;
        if (zone >= 0) rows.get(zone).setMoving(false);
        catalog.save();
        onFocusChanged();
    }

    void resetLayout() {
        catalog.resetLayout();
        rebuildRows(null);
        setZone(0, true);
        favRow.setFocus(0, true);
        onFocusChanged();
    }

    // ------------------------------------------------------------------ sheets

    void openSheet(Sheet s) {
        sheets.add(s);
        overlay.addView(s, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        s.post(s::animateIn);
        pushBack();
    }

    void closeSheet(Sheet s) {
        if (!sheets.remove(s)) return;
        s.animateOut(() -> overlay.removeView(s));
        pushBack();
        if (s instanceof TasksSheet || s instanceof SettingsSheet) {
            for (Row r : rows) r.refreshStatus();
            onFocusChanged();
        }
    }

    /** The home recedes (dim, slight zoom-out, blur) while a sheet is open. */
    private void pushBack() {
        boolean any = !sheets.isEmpty();
        boolean large = false;
        for (Sheet s : sheets) large |= s.large();
        moreHint.animate().alpha(!any && zone == 0 && folderRow.size() > 0 ? 1f : 0f).setDuration(Ui.ms(200)).start();
        if (Ui.lite) {
            // One flat black layer: no offscreen buffers, no blur, no zoom.
            // Without blur, full-screen sheets need an almost opaque layer or the home text shows through.
            scrim.setAlpha(!any ? 0f : large ? 0.93f : 0.55f);
            return;
        }
        float a = !any ? 1f : large ? 0.3f : 0.55f;
        float sc = !any ? 1f : large ? 0.94f : 0.985f;
        content.animate().alpha(a).scaleX(sc).scaleY(sc).setDuration(Ui.ms(any ? 420 : 300)).setInterpolator(Ui.EASE_OUT).start();
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            if (any) {
                float radius = Ui.dp(large ? 26 : 16);
                content.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
            } else {
                content.setRenderEffect(null);
            }
        }
    }

    // ------------------------------------------------------------------ settings

    void setMotion(boolean on) {
        prefs.edit().putBoolean("motion", on).apply();
        ambient.setMotion(on);
    }

    /** Switches lite mode (saved, overriding the automatic choice) and rebuilds the home with it. */
    void setLite(boolean on) {
        prefs.edit().putBoolean("lite", on).commit();
        recreate();
    }

    void setClock24(boolean on) {
        prefs.edit().putBoolean("clock24", on).apply();
        top.clock24 = on;
        top.tick();
    }

    void setBackground(String id) {
        prefs.edit().putString("bg", id).apply();
        applyBackground(true);
    }

    private void applyBackground(boolean animate) {
        String id = prefs.getString("bg", Ambient.DYNAMIC);
        if (Ambient.PHOTO.equals(id) || Ambient.WALL.equals(id)) {
            final int gen = ++bgGeneration;
            worker.execute(() -> {
                Bitmap b = Ambient.WALL.equals(id) ? currentWallpaper() : newestPhoto();
                Bitmap frost = null;
                if (b != null) {
                    if (!b.isMutable()) b = b.copy(Bitmap.Config.ARGB_8888, true);
                    Wallpapers.bake(b);
                    frost = Wallpapers.frost(b);
                }
                final Bitmap pic = b, fr = frost;
                ui.post(() -> {
                    if (gen != bgGeneration) return;
                    ambient.setPhoto(pic);
                    Glass.backdrop = fr != null ? fr : ambient.frame();
                    invalidateTree(content);
                    for (Sheet s : sheets) invalidateTree(s);
                });
            });
            return;
        }
        ++bgGeneration;
        ambient.setPhoto(null);
        Glass.backdrop = ambient.frame();
        invalidateTree(content);
        if (Ambient.DYNAMIC.equals(id)) {
            Item it = focusedItem();
            ambient.setColors(it == null ? Ambient.preset("aurora") : Art.ambient(it.palette), animate);
        } else {
            ambient.setColors(Ambient.preset(id), animate);
        }
    }

    private int bgGeneration;

    private static void invalidateTree(View v) {
        v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) invalidateTree(g.getChildAt(i));
        }
    }

    List<java.io.File> wallpapers() { return Wallpapers.list(this); }

    /** "דרגון בול" / "המשפחה": the collection of the picture shown now. */
    String wallpaperCollection() {
        List<java.io.File> all = wallpapers();
        return all.isEmpty() ? "טפטים" : Wallpapers.collectionName(all.get(wallpaperIndex()));
    }

    int wallpaperIndex() {
        List<java.io.File> all = wallpapers();
        return all.isEmpty() ? 0 : Math.floorMod(prefs.getInt("wallIndex", 0), all.size());
    }

    /** The picked wallpaper, at the screen's size (half that on the lite TV). */
    private Bitmap currentWallpaper() {
        List<java.io.File> all = wallpapers();
        if (all.isEmpty()) return null;
        // A new picture every day.
        int today = (int) (System.currentTimeMillis() / 86_400_000L);
        int day = prefs.getInt("wallDay", -1);
        if (day != today) {
            prefs.edit().putInt("wallDay", today).putInt("wallIndex", prefs.getInt("wallIndex", 0) + (day < 0 ? 0 : 1)).apply();
        }
        int w = Ui.lite ? Glass.screenW / 2 : Glass.screenW, h = Ui.lite ? Glass.screenH / 2 : Glass.screenH;
        return Wallpapers.load(all.get(wallpaperIndex()), w, h);
    }

    /** OK on the wallpaper tile again: the next picture. */
    void nextWallpaper(int step) {
        prefs.edit().putInt("wallIndex", prefs.getInt("wallIndex", 0) + step).apply();
        setBackground(Ambient.WALL);
    }

    /** The newest picture on the TV (Pictures / DCIM / Download), scaled to the screen. */
    private Bitmap newestPhoto() {
        String[] cols = {MediaStore.Images.Media._ID};
        try (Cursor c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, null, null,
                MediaStore.Images.Media.DATE_MODIFIED + " DESC")) {
            if (c == null || !c.moveToFirst()) return null;
            Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0));
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), uri), (d, info, src) -> {
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                float s = Math.min(1f, Math.max(1920f / w, 1080f / h));
                d.setTargetSize(Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)));
                d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        } catch (Exception e) {
            return null;
        }
    }
}
