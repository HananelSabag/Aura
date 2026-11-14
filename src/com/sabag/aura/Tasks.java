package com.sabag.aura;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Open apps = apps that still have a live window (an activity record), read through adb, ordered by
 * Recents. A running process alone is not enough: some apps are restarted by the system as background
 * services right after they are closed (Settings and Netflix on the Xiaomi) and would never "close".
 */
final class Tasks {
    static final String QUERY = "ps -A -o RSS,NAME; echo @@; dumpsys activity recents | grep -E 'Recent #|lastActiveTime='; "
            + "echo @@; dumpsys activity activities | grep -F 'ActivityRecord{'";

    static final class Entry {
        final String pkg;
        final long rssKb, inactiveMs;

        Entry(String pkg, long rssKb, long inactiveMs) {
            this.pkg = pkg;
            this.rssKb = rssKb;
            this.inactiveMs = inactiveMs;
        }
    }

    // "* Recent #2: Task{4d26b6e #1927 type=standard A=10075:com.netflix.ninja}" (A = task affinity)
    // or "... I=com.android.vending/.SomeActivity}"; the next line has "(inactive for 1378s)".
    private static final Pattern TASK = Pattern.compile("Recent #\\d+: Task\\{.*?(?:A=\\d+:([^}\\s:]+)|I=([\\w.]+)/)");
    private static final Pattern INACTIVE = Pattern.compile("inactive for (\\d+)s");
    // "ActivityRecord{c8f2f u0 com.netflix.ninja/.MainActivity t1927}"
    private static final Pattern ACTIVITY = Pattern.compile("ActivityRecord\\{\\S+ u\\d+ ([\\w.]+)/");

    /** Most recently used first; `known` = installed packages (affinities like "<pkg>.main" map back to them). */
    static List<Entry> parse(String out, String self, java.util.Collection<String> known) {
        List<Entry> result = new ArrayList<>();
        if (out == null) return result;
        String[] parts = out.split("@@", 3);
        String ps = parts[0];
        String recents = parts.length > 1 ? parts[1] : "";
        java.util.Set<String> live = new java.util.HashSet<>();
        if (parts.length > 2) {
            Matcher a = ACTIVITY.matcher(parts[2]);
            while (a.find()) live.add(a.group(1));
        }

        Map<String, Long> rss = new HashMap<>();
        for (String line : ps.split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 2) continue;
            try {
                long kb = Long.parseLong(f[0]);
                Long prev = rss.get(f[1]);
                rss.put(f[1], prev == null ? kb : prev + kb);
            } catch (NumberFormatException ignored) {
            }
        }

        Map<String, Long> order = new LinkedHashMap<>();
        String pending = null;
        for (String line : recents.split("\n")) {
            Matcher m = TASK.matcher(line);
            if (m.find()) {
                pending = owner(m.group(1) != null ? m.group(1) : m.group(2), known);
                if (pending != null && !order.containsKey(pending)) order.put(pending, -1L);
                continue;
            }
            Matcher t = INACTIVE.matcher(line);
            if (t.find() && pending != null) {
                if (order.get(pending) == -1L) order.put(pending, Long.parseLong(t.group(1)) * 1000L);
                pending = null;
            }
        }
        for (Map.Entry<String, Long> e : order.entrySet()) {
            String pkg = e.getKey();
            if (pkg.equals(self) || !rss.containsKey(pkg) || (parts.length > 2 && !live.contains(pkg))) continue;
            result.add(new Entry(pkg, rss.get(pkg), e.getValue()));
        }
        return result;
    }

    /** The installed package an affinity belongs to: the longest package name it starts with. */
    private static String owner(String affinity, java.util.Collection<String> known) {
        String best = null;
        for (String p : known) {
            if ((affinity.equals(p) || affinity.startsWith(p + ".")) && (best == null || p.length() > best.length())) best = p;
        }
        return best;
    }

    static String ago(long ms) {
        if (ms < 0) return "";
        long m = ms / 60_000;
        if (m < 1) return "ממש עכשיו";
        if (m < 60) return m == 1 ? "לפני דקה" : "לפני " + m + " דק׳";
        long h = m / 60;
        if (h < 24) return h == 1 ? "לפני שעה" : h == 2 ? "לפני שעתיים" : "לפני " + h + " שעות";
        return "לפני יותר מיום";
    }

    static String mb(long kb) {
        if (kb >= 1024 * 1024) return Ui.ltr(String.format(java.util.Locale.US, "%.1f GB", kb / 1048576f));
        return Ui.ltr((kb / 1024) + " MB");
    }

    static String gb(long bytes) {
        return Ui.ltr(String.format(java.util.Locale.US, "%.1f GB", bytes / 1073741824f));
    }

    private Tasks() {}
}
