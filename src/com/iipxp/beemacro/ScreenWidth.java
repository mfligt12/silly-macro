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
    static final int TARGET = Profile.PHONE_SW;
    /** The width to use on this device: 360 on phones (required), the Tab S9 FE's 822 on tablets (recommended). */
    static int target(Context c) { return tablet(c) ? Profile.TABLET_SW : TARGET; }
    /** This device isn't at its target width (phones: required, see needed(); tablets: a recommendation). */
    static boolean off(Context c) { return current(c) != target(c); }

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
    static int densityFor(int shortPx) { return Profile.densityFor(shortPx, TARGET); }

    /** Needs fixing: a phone whose smallest width isn't 360. */
    static boolean needed(Context c) { return !tablet(c) && current(c) != TARGET; }

    /** Sets it through the key sender. Returns null when sent, otherwise why not. */
    static String apply(Context c) {
        int dpi = Profile.densityFor(shortPx(c), target(c));
        String why = sender(c); if (why != null) return why;
        if (!Keys.setDensity(dpi)) return "The key sender couldn't change it. Start Shizuku, then Home → step 2, and try again";
        Log.add("screen width: was " + current(c) + " dp, set density " + dpi + " for " + target(c) + " dp (short side " + shortPx(c) + " px)");
        return null;
    }

    /** S23 resolution switch: the screen draws at 1080 x 2340, 480 dpi (the phone every reader was measured on; its
     *  smallest width is 360 by itself). Off: the phone's own size again, at Smallest width 360. Null when done. */
    static String s23(Context c, boolean on) {
        String why = sender(c); if (why != null) return why;
        boolean tab = tablet(c);   // tablets: the Tab S9 FE's screen instead (2304 x 1440 at 280 dpi, smallest width 822)
        int w = tab ? 1440 : 1080, h = tab ? 2304 : 2340, dpi = tab ? 280 : 480, own = Profile.densityFor(shortPx(c), target(c));
        if (on ? !Keys.setResolution(w, h, dpi) : !(Keys.setResolution(0, 0, 0) && Keys.setDensity(own)))
            return "The key sender couldn't change the resolution. Start Shizuku, then Home → step 2, and try again";
        Log.add("screen: " + (tab ? "S9 FE" : "S23") + " resolution " + (on ? "on (" + w + "x" + h + ", " + dpi + " dpi)" : "off (own size, " + own + " dpi)"));
        return null;
    }
    /** The reference screen this device is matched to, for labels. */
    static String reference(Context c) { return tablet(c) ? "Tab S9 FE resolution (2304 × 1440, 280 dpi)" : "S23 resolution (1080 × 2340)"; }

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
