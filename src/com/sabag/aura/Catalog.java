package com.sabag.aura;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every app and TV input the home can show, and where the person put each one:
 * the favorites row, the apps row, or a folder (tools / not in use / inputs).
 */
final class Catalog {
    static final String FAV = "fav", APPS = "apps", TOOLS = "tools", UNUSED = "unused", INPUTS = "inputs";
    static final String[] FOLDERS = {TOOLS, UNUSED, INPUTS};

    static String name(String list) {
        switch (list) {
            case FAV: return "מועדפים";
            case APPS: return "אפליקציות";
            case TOOLS: return "כלים";
            case UNUSED: return "לא בשימוש";
            case INPUTS: return "כניסות";
            default: return list;
        }
    }

    // First-run layout for this TV (from what the household actually opens).
    private static final List<String> DEFAULT_FAV = Arrays.asList(
            "com.google.android.youtube.tv", "com.netflix.ninja", "il.co.yes.yesplus", "com.hanan.streambrowser");
    private static final List<String> DEFAULT_APPS = Arrays.asList(
            "com.tcl.ui_mediaCenter", "com.xiaomi.mitv.mediaexplorer", "com.applicaster.il.ch1", "tv.freetv.androidtv",
            "com.iqiyi.i18n.tv", "com.amazon.amazonvideo.livingroom", "com.google.android.youtube.tvmusic",
            "com.tcl.browser", "com.internet.tvbrowser", "com.mitv.gallery");
    private static final List<String> DEFAULT_TOOLS = Arrays.asList(
            "com.android.vending", "com.android.tv.settings", "com.tcl.guard", "com.tcl.dashboard", "com.tcl.t_solo");
    private static final List<String> DEFAULT_UNUSED = Arrays.asList(
            "com.vectorunit.purple.googleplay", "com.disneyplus.mea", "com.wbd.stream", "com.android.deskclock");
    /** Never shown: launchers, and TCL entry points that duplicate the remote's own buttons. */
    private static final Set<String> HIDDEN = new HashSet<>(Arrays.asList(
            "com.sabag.aura", "com.sabag.shket.pro", "com.sabag.shket", "com.tcl.tv.tclhome_passive",
            "com.tcl.suspension", "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher",
            "com.google.android.tv.remote.service"));
    /** Live TV apps (TCL, Xiaomi/MediaTek): shown in the inputs folder next to HDMI. */
    private static final Set<String> LIVE_TV = new HashSet<>(Arrays.asList("com.tcl.tv", "com.mediatek.wwtv.tvcenter"));

    private static final Map<String, String> LABELS = new HashMap<>();
    static {
        LABELS.put("com.tcl.ui_mediaCenter", "מדיה ו-USB");
        LABELS.put("com.android.tv.settings", "הגדרות");
        LABELS.put("com.tcl.guard", "ניהול מכשיר");
        LABELS.put("com.tcl.t_solo", "האזנה בנפרד");
        LABELS.put("com.android.vending", "חנות Play");
        LABELS.put("com.tcl.browser", "דפדפן TCL");
        LABELS.put("com.amazon.amazonvideo.livingroom", "Prime Video");
        LABELS.put("com.vectorunit.purple.googleplay", "Beach Buggy 2");
        LABELS.put("com.tcl.tv", "ערוצי טלוויזיה");
        LABELS.put("com.mediatek.wwtv.tvcenter", "ערוצי טלוויזיה");
        LABELS.put("com.xiaomi.mitv.mediaexplorer", "מדיה ו-USB");
        LABELS.put("com.mitv.gallery", "גלריה");
    }

    private final Context ctx;
    private final PackageManager pm;
    private final SharedPreferences prefs;
    /** Items survive reloads (same object, same art) while their package is unchanged. */
    private final Map<String, Item> cache = new HashMap<>();
    private final Map<String, Long> versions = new HashMap<>();

    final Map<String, Item> byKey = new LinkedHashMap<>();
    final Map<String, List<Item>> lists = new HashMap<>();
    final Map<String, Item> folders = new LinkedHashMap<>();

    Catalog(Context c, SharedPreferences prefs) {
        ctx = c;
        pm = c.getPackageManager();
        this.prefs = prefs;
        for (String f : FOLDERS) folders.put(f, Item.folder(f, name(f)));
    }

    // ------------------------------------------------------------------ loading (worker thread)

    /** Reads apps and inputs; returns the fresh key→item map. Safe to call off the main thread. */
    synchronized Map<String, Item> scan() {
        Map<String, Item> out = new LinkedHashMap<>();
        Map<String, Long> used = usage();
        addApps(out, Intent.CATEGORY_LEANBACK_LAUNCHER, used);
        addApps(out, Intent.CATEGORY_LAUNCHER, used);
        addInputs(out);
        return out;
    }

    private void addApps(Map<String, Item> out, String category, Map<String, Long> used) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> found;
        try {
            found = pm.queryIntentActivities(q, 0);
        } catch (RuntimeException e) {
            return;
        }
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (out.containsKey(pkg) || HIDDEN.contains(pkg)) continue;
            long ver = 0;
            try {
                PackageInfo pi = pm.getPackageInfo(pkg, 0);
                ver = pi.lastUpdateTime ^ pi.getLongVersionCode();
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            Item it = cache.get(pkg);
            Long known = versions.get(pkg);
            if (it == null || known == null || known != ver) {
                it = new Item(Item.APP, pkg);
                it.pkg = pkg;
                it.component = new ComponentName(pkg, ri.activityInfo.name);
                ApplicationInfo ai = ri.activityInfo.applicationInfo;
                it.system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                it.archived = ri.activityInfo.name.endsWith("ReactivateActivity");
                String l = LABELS.get(pkg);
                if (l == null) {
                    CharSequence cs = ri.loadLabel(pm);
                    l = cs == null ? pkg : cs.toString().trim();
                }
                it.label = l;
                makeArt(it, ri, Intent.CATEGORY_LEANBACK_LAUNCHER.equals(category));
                cache.put(pkg, it);
                versions.put(pkg, ver);
            }
            Long lu = used.get(pkg);
            it.lastUsed = lu == null ? 0 : lu;
            out.put(pkg, it);
        }
    }

    private void makeArt(Item it, ResolveInfo ri, boolean tv) {
        Drawable banner = null;
        if (tv) {
            int res = ri.activityInfo.banner != 0 ? ri.activityInfo.banner : ri.activityInfo.applicationInfo.banner;
            banner = sharp(ri.activityInfo.applicationInfo, res, android.util.DisplayMetrics.DENSITY_XXHIGH);
            try {
                if (banner == null) banner = ri.activityInfo.loadBanner(pm);
                if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            } catch (RuntimeException ignored) {
            }
        }
        if (banner != null) {
            it.art = Art.banner(banner);
            it.palette = Art.palette(it.art);
        } else {
            Drawable icon = sharp(ri.activityInfo.applicationInfo, ri.getIconResource(), android.util.DisplayMetrics.DENSITY_XXXHIGH);
            if (icon == null) icon = ri.loadIcon(pm);
            android.graphics.Bitmap probe = Art.banner(icon);
            int[] pal = Art.palette(probe);
            it.palette = pal;
            it.art = Art.fromIcon(icon, it.label, pal);
        }
        if (it.palette != null) it.glow = it.palette[4];
    }

    /**
     * The app's own higher-density version of a drawable. The TV runs at xhdpi, so a normal load
     * gives a 320x180 banner that the focused card has to stretch; most apps also ship xxhdpi art.
     */
    private Drawable sharp(ApplicationInfo ai, int res, int density) {
        if (res == 0) return null;
        try {
            return pm.getResourcesForApplication(ai).getDrawableForDensity(res, density, null);
        } catch (Exception e) {
            return null;
        }
    }

    private void addInputs(Map<String, Item> out) {
        TvInputManager tim = ctx.getSystemService(TvInputManager.class);
        if (tim == null) return;
        List<TvInputInfo> list;
        try {
            list = tim.getTvInputList();
        } catch (RuntimeException e) {
            return;
        }
        List<Item> found = new ArrayList<>();
        for (TvInputInfo in : list) {
            if (!in.isPassthroughInput()) continue;
            try {
                if (in.isHidden(ctx)) continue;
            } catch (RuntimeException ignored) {
            }
            CharSequence l = in.loadCustomLabel(ctx);
            if (l == null || l.length() == 0) l = in.loadLabel(ctx);
            String label = l == null ? "HDMI" : l.toString();
            boolean connected = false;
            try {
                connected = tim.getInputState(in.getId()) == TvInputManager.INPUT_STATE_CONNECTED;
            } catch (RuntimeException ignored) {
            }
            String key = "in:" + in.getId();
            Item it = cache.get(key);
            if (it == null || !label.equals(it.label) || it.connected != connected) {
                it = new Item(Item.INPUT, key);
                it.inputId = in.getId();
                it.label = label;
                it.connected = connected;
                it.art = Art.input(label, false, connected);
                it.palette = new int[]{0xFF4F7BFF, 0xFF2EC5B6, 0xFF6A5BFF, 0xFF3A8DFF, 0xFF6E8BFF};
                it.glow = it.palette[4];
                cache.put(key, it);
            }
            found.add(it);
        }
        Collections.sort(found, (a, b) -> a.label.compareTo(b.label));
        for (Item it : found) out.put(it.key, it);
    }

    private Map<String, Long> usage() {
        Map<String, Long> m = new HashMap<>();
        try {
            UsageStatsManager us = ctx.getSystemService(UsageStatsManager.class);
            long now = System.currentTimeMillis();
            Map<String, UsageStats> agg = us.queryAndAggregateUsageStats(now - 30L * 86_400_000L, now);
            for (Map.Entry<String, UsageStats> e : agg.entrySet()) {
                long t = e.getValue().getLastTimeUsed();
                if (t > 0) m.put(e.getKey(), t);
            }
        } catch (RuntimeException ignored) {
            // Usage access is optional (granted over adb); subtitles just say less without it.
        }
        return m;
    }

    // ------------------------------------------------------------------ arrangement (main thread)

    /** Puts every scanned item in its list, following the saved layout and the first-run defaults. */
    void arrange(Map<String, Item> scanned) {
        byKey.clear();
        byKey.putAll(scanned);
        boolean first = !prefs.contains("list." + FAV);
        Map<String, List<String>> saved = new HashMap<>();
        for (String l : new String[]{FAV, APPS, TOOLS, UNUSED}) {
            saved.put(l, first ? defaults(l) : split(prefs.getString("list." + l, "")));
        }
        Set<String> placed = new HashSet<>();
        for (String l : new String[]{FAV, APPS, TOOLS, UNUSED}) {
            List<Item> items = new ArrayList<>();
            for (String k : saved.get(l)) {
                Item it = byKey.get(k);
                // First run: an app that is only an archived placeholder on this TV is not a favorite.
                if (it == null || (first && it.archived && (FAV.equals(l) || APPS.equals(l)))) continue;
                if (placed.add(k)) items.add(it);
            }
            lists.put(l, items);
        }
        List<Item> inputs = new ArrayList<>();
        for (Item it : byKey.values()) {
            if (placed.contains(it.key)) continue;
            if (it.type == Item.INPUT || LIVE_TV.contains(it.key)) {
                inputs.add(it);
            } else if (it.archived) {
                lists.get(UNUSED).add(it);
            } else if (it.system || it.key.startsWith("com.tcl.") || it.key.startsWith("com.realtek.")) {
                lists.get(TOOLS).add(it);
            } else {
                lists.get(APPS).add(it);
            }
            placed.add(it.key);
        }
        // Live TV first, then HDMI 1, 2, ...
        Collections.sort(inputs, (a, b) -> a.type == b.type ? a.label.compareTo(b.label) : a.type == Item.APP ? -1 : 1);
        lists.put(INPUTS, inputs);
        for (String f : FOLDERS) {
            Item folder = folders.get(f);
            folder.children.clear();
            folder.children.addAll(lists.get(f));
        }
        if (first) save();
    }

    private static List<String> defaults(String list) {
        switch (list) {
            case FAV: return DEFAULT_FAV;
            case APPS: return DEFAULT_APPS;
            case TOOLS: return DEFAULT_TOOLS;
            case UNUSED: return DEFAULT_UNUSED;
            default: return Collections.emptyList();
        }
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        for (String k : s.split("\\|")) if (!k.isEmpty()) out.add(k);
        return out;
    }

    void save() {
        SharedPreferences.Editor e = prefs.edit();
        for (String l : new String[]{FAV, APPS, TOOLS, UNUSED}) {
            StringBuilder b = new StringBuilder();
            for (Item it : lists.get(l)) {
                if (b.length() > 0) b.append('|');
                b.append(it.key);
            }
            e.putString("list." + l, b.toString());
        }
        e.apply();
    }

    void resetLayout() {
        SharedPreferences.Editor e = prefs.edit();
        for (String l : new String[]{FAV, APPS, TOOLS, UNUSED}) e.remove("list." + l);
        e.apply();
        arrange(new LinkedHashMap<>(byKey));
    }

    List<Item> list(String id) {
        List<Item> l = lists.get(id);
        return l == null ? Collections.emptyList() : l;
    }

    String listOf(Item it) {
        for (Map.Entry<String, List<Item>> e : lists.entrySet()) if (e.getValue().contains(it)) return e.getKey();
        return null;
    }

    /** Moves an item to the end of another list (inputs may only go to favorites and back). */
    void moveTo(Item it, String target) {
        String from = listOf(it);
        if (target.equals(from)) return;
        if (from != null) lists.get(from).remove(it);
        if (INPUTS.equals(target) || (it.type == Item.INPUT && !FAV.equals(target))) target = INPUTS;
        lists.get(target).add(it);
        for (String f : FOLDERS) {
            Item folder = folders.get(f);
            folder.children.clear();
            folder.children.addAll(lists.get(f));
        }
        save();
    }

    /** The folders' order on the home row (the person can rearrange it). */
    List<String> folderOrder() {
        List<String> out = split(prefs.getString("folders.order", ""));
        out.retainAll(Arrays.asList(FOLDERS));
        for (String f : FOLDERS) if (!out.contains(f)) out.add(f);
        return out;
    }

    void swapFolders(String a, String b) {
        List<String> order = folderOrder();
        int i = order.indexOf(a), j = order.indexOf(b);
        if (i < 0 || j < 0) return;
        Collections.swap(order, i, j);
        prefs.edit().putString("folders.order", String.join("|", order)).apply();
    }

    void swap(String list, int i, int j) {
        List<Item> l = lists.get(list);
        if (l == null || i < 0 || j < 0 || i >= l.size() || j >= l.size()) return;
        Collections.swap(l, i, j);
        save();
    }
}
