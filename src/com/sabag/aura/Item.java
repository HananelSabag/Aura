package com.sabag.aura;

import android.content.ComponentName;
import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.List;

/** Something the home screen can show as a card: an app, a TV input (HDMI/antenna) or a folder. */
final class Item {
    static final int APP = 0, INPUT = 1, FOLDER = 2, CLOSE_ALL = 3;

    final int type;
    final String key;          // package name, "in:<input id>" or "folder:<id>"
    String label = "";
    String pkg;
    ComponentName component;
    boolean system, archived;

    // inputs
    String inputId;
    boolean tuner, connected;

    // folders
    String folderId;
    final List<Item> children = new ArrayList<>();

    /** 16:9 art (an app's own TV banner when it has one), shared by every card that shows the item. */
    Bitmap art;
    /** Smaller copies of `art` by pixel width (see Art.scaled). */
    final java.util.Map<Integer, Bitmap> scaled = new java.util.HashMap<>();
    /** Four colors for the ambient background when this item is focused, and its glow color. */
    int[] palette;
    int glow = 0xFF6E8BFF;

    // live status
    long lastUsed;             // wall clock ms, 0 = unknown
    boolean running;
    long rssKb;
    long inactiveMs = -1;

    Item(int type, String key) {
        this.type = type;
        this.key = key;
    }

    static Item folder(String id, String label) {
        Item f = new Item(FOLDER, "folder:" + id);
        f.folderId = id;
        f.label = label;
        return f;
    }

    boolean isApp() { return type == APP; }
}
