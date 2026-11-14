package com.sabag.aura;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * The remote's spare buttons open the apps the user picked ("כפתורי השלט" in Settings). Android lets
 * only an accessibility service see keys before the app in front does; this one looks at nothing
 * else (no screen content). Navigation, Back, Home, volume and power are never taken over.
 *
 * Learning: while Settings asks "press a button", the next spare key goes to it instead.
 */
public final class Buttons extends AccessibilityService {
    static final String PREFS = "buttons";

    /** Settings' "press the button now" listener (main thread), or null. */
    static volatile Learner learner;

    interface Learner { void pressed(int keyCode); }

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}

    @Override protected boolean onKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (isReserved(code)) return false;
        Learner l = learner;
        if (l != null) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) main.post(() -> l.pressed(code));
            return true;
        }
        String pkg = prefs(this).getString(key(code), null);
        if (pkg == null) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) open(this, pkg);
        return true; // the button is ours now: its old action (if any) does not run as well
    }

    static void open(Context c, String pkg) {
        Intent i = c.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { c.startActivity(i); } catch (Exception ignored) {}
    }

    /** Keys the TV can't do without: arrows, OK, Back, Home, volume, power, the search/voice key. */
    static boolean isReserved(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN: case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_POWER: case KeyEvent.KEYCODE_TV_POWER: case KeyEvent.KEYCODE_SLEEP:
            case KeyEvent.KEYCODE_WAKEUP: case KeyEvent.KEYCODE_SEARCH: case KeyEvent.KEYCODE_VOICE_ASSIST:
            case KeyEvent.KEYCODE_ASSIST: case KeyEvent.KEYCODE_UNKNOWN:
                return true;
            default:
                return false;
        }
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, MODE_PRIVATE); }

    static String key(int code) { return "k" + code; }

    static boolean enabled(Context c) {
        String on = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return on != null && on.contains(c.getPackageName() + "/") && on.contains("Buttons");
    }

    /** "אדום", "Netflix", or the key's Android name when it has no common name. */
    static String name(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_PROG_RED: return "הכפתור האדום";
            case KeyEvent.KEYCODE_PROG_GREEN: return "הכפתור הירוק";
            case KeyEvent.KEYCODE_PROG_YELLOW: return "הכפתור הצהוב";
            case KeyEvent.KEYCODE_PROG_BLUE: return "הכפתור הכחול";
            case KeyEvent.KEYCODE_MENU: return "כפתור התפריט ☰";
            case KeyEvent.KEYCODE_SETTINGS: return "כפתור ההגדרות";
            case KeyEvent.KEYCODE_TV_INPUT: return "כפתור הכניסות";
            case KeyEvent.KEYCODE_GUIDE: return "כפתור המדריך";
            case KeyEvent.KEYCODE_INFO: return "כפתור המידע";
            case KeyEvent.KEYCODE_ALL_APPS: return "כפתור האפליקציות";
            case KeyEvent.KEYCODE_BOOKMARK: return "כפתור המועדפים";
            case KeyEvent.KEYCODE_CAPTIONS: return "כפתור הכתוביות";
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return "ניגון / השהיה";
            case KeyEvent.KEYCODE_TV: return "כפתור TV";
            case KeyEvent.KEYCODE_DVR: return "כפתור ההקלטות";
            case KeyEvent.KEYCODE_CHANNEL_UP: return "ערוץ למעלה";
            case KeyEvent.KEYCODE_CHANNEL_DOWN: return "ערוץ למטה";
            default:
                String s = KeyEvent.keyCodeToString(code);
                return Ui.ltr((s.startsWith("KEYCODE_") ? s.substring(8) : s) + " · " + code);
        }
    }
}
