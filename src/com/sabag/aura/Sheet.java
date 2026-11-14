package com.sabag.aura;

import android.view.KeyEvent;
import android.widget.FrameLayout;

/** Base for overlays (menu, folder, open apps, settings). The home routes keys to the top sheet. */
abstract class Sheet extends FrameLayout {
    final HomeActivity home;

    Sheet(HomeActivity home) {
        super(home);
        this.home = home;
        setClipChildren(false);
        setWillNotDraw(false);
    }

    /** D-pad and Back key-downs (OK is delivered through onOk / onLongOk). Return true when handled. */
    abstract boolean onKey(int keyCode, KeyEvent e);

    void onOk() {}

    void onLongOk() {}

    /** Called after the sheet is attached and laid out. */
    void animateIn() {
        if (Ui.lite) return; // appears at once: fading a full-screen view costs an offscreen layer
        setAlpha(0f);
        setScaleX(0.96f);
        setScaleY(0.96f);
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Ui.ms(420)).setInterpolator(Ui.springSoft()).start();
    }

    void animateOut(Runnable end) {
        if (Ui.lite) {
            end.run();
            return;
        }
        animate().alpha(0f).scaleX(0.97f).scaleY(0.97f).setDuration(Ui.ms(180)).setInterpolator(Ui.EASE_IN)
                .withEndAction(end).start();
    }

    /** Sheets that cover most of the screen push the home back further. */
    boolean large() { return true; }

    void close() { home.closeSheet(this); }

    /** The catalog or open-app status changed while this sheet is open. */
    void onDataChanged() {}

    /** A frame of the moving background was drawn (only pages that show it need to redraw). */
    void onAmbientFrame() { onDataChanged(); }
}
