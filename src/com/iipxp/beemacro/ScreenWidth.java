package com.iipxp.beemacro;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

/**
 * Android's "Smallest width" (Developer options; Samsung: "Minimum width") must be 360 dp on phones: at other sizes
 * Roblox draws a HUD the macro can't read (announced to everyone 2026-11-23). The key sender runs as the shell user
 * through Shizuku, so it can set it with `wm density` itself instead of the player digging through Developer options.
 * Tablets are left alone: their HUD is read in Roblox's tablet layout, measured at their own width.
 */
final class ScreenWidth {
    static final int TARGET = 360;

    static int current(Context c) { return c.getResources().getConfiguration().smallestScreenWidthDp; }

    /** Physical short side in pixels, whatever density is set now. */
    static int shortPx(Context c) {
        Display d = ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        Display.Mode m = d.getMode();
        return Math.min(m.getPhysicalWidth(), m.getPhysicalHeight());
    }

    /** A tablet: short side over 3.8 inches (phones measure 2.6-3.2", tablets 4.5" and up). */
    static boolean tablet(Context c) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        double dpi = Math.min(dm.xdpi, dm.ydpi);
        return dpi > 50 && shortPx(c) / dpi > 3.8;
    }

    /** The density that makes the short side exactly 360 dp (rounded down: never below 360). */
    static int densityFor(int shortPx) { return shortPx * 160 / TARGET; }

    /** Needs fixing: a phone whose smallest width isn't 360. */
    static boolean needed(Context c) { return !tablet(c) && current(c) != TARGET; }

    /** Sets it through the key sender. Returns null when sent, otherwise why not. */
    static String apply(Context c) {
        int dpi = densityFor(shortPx(c));
        String why = sender(c); if (why != null) return why;
        if (!Keys.setDensity(dpi)) return "The key sender couldn't change it. Start Shizuku, then Home → step 2, and try again";
        Log.add("screen width: was " + current(c) + " dp, set density " + dpi + " for " + TARGET + " dp (short side " + shortPx(c) + " px)");
        return null;
    }

    /** S23 resolution switch: the screen draws at 1080 x 2340, 480 dpi (the phone every reader was measured on; its
     *  smallest width is 360 by itself). Off: the phone's own size again, at Smallest width 360. Null when done. */
    static String s23(Context c, boolean on) {
        String why = sender(c); if (why != null) return why;
        if (on ? !Keys.setResolution(1080, 2340, 480) : !(Keys.setResolution(0, 0, 0) && Keys.setDensity(densityFor(shortPx(c)))))
            return "The key sender couldn't change the resolution. Start Shizuku, then Home → step 2, and try again";
        Log.add("screen: S23 resolution " + (on ? "on (1080x2340, 480 dpi)" : "off (own size, " + densityFor(shortPx(c)) + " dpi)"));
        return null;
    }

    /** Connected to a key sender that knows these commands (an older one is restarted). Null when ready. */
    private static String sender(Context c) {
        if (!Keys.connect(c)) return "The key sender isn't running: start Shizuku, then Home → step 2";
        Keys.emulatorState();
        if (Keys.senderProto < SenderStatus.PROTO && ShizukuKeys.ready()) {   // one from an older Silly Macro: restart it
            Log.add("screen: restarting the older key sender (" + ShizukuKeys.start(c) + ")");
            if (!Keys.connect(c)) return "The key sender didn't come back: Home → step 2";
        }
        return null;
    }
}
