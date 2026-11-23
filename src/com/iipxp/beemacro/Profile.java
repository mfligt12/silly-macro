package com.iipxp.beemacro;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/**
 * PINE_TREE_MASTER_PROFILE. Pure Java (Properties file) so it round-trips in desktop tests.
 * Defaults come from the user's Natro config (settings/nm_config.ini) and live measurements on the S23.
 * Skillet originals are constants; calibration lives in separate keys so originals are never overwritten.
 */
public final class Profile {
    public static final String NAME = "PINE_TREE_MASTER_PROFILE";
    public static final String PRESET = "Pine Tree Forest — Skillet";

    // Skillet originals (Dully176). Never edited at runtime.
    public static final double DW_FDC = 0.2, HW_FDC = 0.3, CR_FDC = 0.3, DW_ALIGN = 1.5, HW_ALIGN = 1.5;

    // Hive (identity only — reaching it is verified separately by the on-screen prompt)
    public int hiveSlot = 3;
    public boolean hiveConfirmed = false;
    /** If the chosen hive is someone else's, claim the nearest free one instead (for this run; your slot stays saved). */
    public boolean autoHiveSlot = true;
    /** Slots to try after `first`: 3 (straight ahead of spawn, the most tested) then the ones closest to it. */
    public static int[] slotOrder(int first) {
        int[] all = {3, 2, 4, 1, 5, 6}, o = new int[6]; o[0] = first; int n = 1;
        for (int s : all) if (s != first) o[n++] = s;
        return o;
    }

    // Gather (from nm_config.ini: MoveSpeedNum=33.35, FieldUntilMins1=10, FieldRotateDirection1=Right, FieldRotateTimes1=4)
    public double moveSpeed = 33.35;
    public int backpackThreshold = 90;
    public int fieldMinutes = 10;
    public boolean returnByCannon = true;   // cannon+glider back like the reference video; false = respawn at hive
    // Shift-lock toggle: its touch area is up-left of the drawn icon (147,700) because the D-pad covers the rest.
    // Measured live 2026-11-17: (126,680) toggled 3/3; the icon centre and 5 other points did nothing.
    public float shiftTapX = 126 / 2340f, shiftTapY = 680 / 1080f;
    public boolean autoConvert = true, autoReturn = true, recovery = true;
    public int maxRecoveries = 3;
    public int convertTimeoutSec = 360;
    // Hive balloon (Natro ConvertBalloon): after the backpack, keep converting while the balloon empties
    public String balloonMode = "Never";   // Never / Always / Every
    public int balloonMins = 30;

    // Calibration (separate from pattern values). Fractions of landscape screen; measured on 2340x1080.
    public int pxPer45 = 335;             // 8 swipes ≈ 360° measured live
    public int tiltPx = 60;
    public boolean yawInvert = false;     // swipe right = camera turns left (measured)
    public float joyX = 0.127f, joyY = 0.844f, joyR = 0.10f;      // thumbstick (Dynamic Thumbstick mode)
    public boolean dpad = true;                                   // Roblox Movement Mode = DPad (user's choice)
    public float padX = 417 / 2340f, padY = 680 / 1080f, padCell = 188 / 1080f; // D-pad centre + cell size (measured)
    public float idleX = 600 / 2340f, idleY = 1040 / 1080f;       // no-UI spot for the release/test tap
    public float jumpX = 0.880f, jumpY = 0.847f;
    public float camX = 0.47f, camY = 0.62f;
    public double moveScale = 1.0;        // multiplies walk durations if drift is observed

    // Your private server (used by the "Open Bee Swarm" button)
    /** Your private server link (empty = Bee Swarm's normal game page). Used by "Open Bee Swarm" and overnight rejoins. */
    public String serverLink = "";
    /** Without a private server link: Roblox's start link joins a public Bee Swarm server. The game page link used
     *  before only showed the page and waited for Play, so a rejoin never got back in (Discord: "can't rejoin normal servers"). */
    public static final String BEE_SWARM = "roblox://experiences/start?placeId=1537690962";
    public String joinLink() { return serverLink == null || serverLink.trim().isEmpty() ? BEE_SWARM : serverLink.trim(); }

    // Which layout these input coordinates were set for ("s23" / "tablet"); set once on first start
    public String device = "";
    /** "keyboard" = real keyboard keys via the key sender (Roblox stays in keyboard/mouse layout), "touch" = on-screen
     *  controls via accessibility gestures, "" = automatic: keyboard on the tablet, touch on the S23. */
    public String inputMode = "";
    // Field start, as Natro Macro does it (keyboard trip): sprinkler spot + distance (Natro's Pine Tree default
    // "Upper Left", 7), camera turns before the pattern (your Natro config: Right ×4; negative = left), tool held down.
    public String sprinklerLoc = "Upper Left";
    public int sprinklerDist = 7, fieldRotate = 4;
    public int sprinklerSlot = 1;
    /** Zoom-in steps from fully out to the best hive view when standing on our pad, learned on this device (0 = not yet).
     *  The central spawn is 2+ steps further; phones and tablets differ (tablet pad 1 / spawn 3, S23 pad ~5). */
    public int padZoomStep = 0;
    public String sprinklerType = "Basic";   // Natro's types: how many to place (Basic 1, Silver 2, Golden 3, Diamond 4, Supreme 1)
    public static final String[] SPRINKLERS = {"Basic", "Silver", "Golden", "Diamond", "Supreme", "None"};
    public int sprinklerCount() { switch (sprinklerType) { case "Silver": return 2; case "Golden": return 3; case "Diamond": return 4; case "None": return 0; default: return 1; } }
    // Farming pattern: "Skillet" (default) or a Natro Macro pattern (Patterns.NAMES) with Natro's size / width / inversion
    public String pattern = "Skillet", patternSize = "M";
    public String field = "pinetree";                 // gathering field (Routes.FIELDS key); routes are Natro's walk paths
    public int patternWidth = 3;                       // Natro "width" (reps): Pine Tree default 3
    public boolean patternInvertFB = false, patternInvertLR = false;        // hotbar slot (number key) holding the sprinkler, as in Natro
    public boolean toolHold = true;
    // Screen guard (keyboard trip): find the field again, get unstuck, reset when truly lost; edge = nudge back inside
    public boolean navGuard = true, edgeCorrect = true;
    // Overnight: never stop to ask; rejoin the server when Roblox disconnects or crashes; claim a hive after joining
    public boolean overnight = false;
    // Discord webhook reports (like Natro): your webhook URL, which events, screenshots, and who to ping on problems
    public String webhookUrl = "", discordPingId = "";
    public boolean whScreens = true, whConvert = true, whField = true, whProblems = true, whHourly = true;
    public int whEveryMins = 60;   // timed report interval: 10, 20, 30, 60 or 120 min
    // Other screens: Roblox UI scale vs the Tab S9 FE, in %; 0 = measure it from the honey counter (auto)
    public int uiScale = 0;   // no longer used: the HUD is always measured (a hand-set value stopped phones measuring)
    public boolean advanced = false;
    public boolean shizukuOffered = false;
    public boolean showTemp = true;
    public boolean keepAlive = true;          // idle at the hive: a harmless key every 45 s so Game Booster's touch lock stays away           // device temperature line under the overlay   // first-launch "Get Shizuku" offer shown once
    // Natro "Mondo Buff": in the first 14 min of each hour, stand beside Mountain Top for this long to get Mondo's blessing
    public boolean mondoBuff = false;
    public int mondoSecs = 60;
    public int rejoinAfterSec = 60;      // game screen gone this long = disconnected
    public int lowBatteryStop = 12;      // stop (with a report) at this battery % when not charging
    public int fieldMargin = 5;          // start steering back when a field edge is this many map cells away
    public boolean showFieldMap = false; // draw the detected field edges on screen (debugging)
    public float toolX = 0.50f, toolY = 0.45f;          // where the held "click" lands (open world, no UI)
    /** Movement and camera always go through the keyboard + mouse emulator (key sender): only it swings the tool and
     *  presses hotbar keys. ponytail: the old touch-gesture branches behind keyboard() are now dead code; delete them when touched. */
    public boolean keyboard() { return true; }

    /** Touch-layout defaults measured on the Galaxy Tab S9 FE (2304×1440) on 2026-11-17: Roblox shows a
     *  thumbstick (not the D-pad) there, centre (212,1178), ring radius ~100 px; jump (2112,1178). Player
     *  position is in the remapped (S23-layout) frame. */
    public void applyTabletDefaults() {
        dpad = false;
        joyX = 212 / 2304f; joyY = 1178 / 1440f; joyR = 100 / 1440f;
        jumpX = 2112 / 2304f; jumpY = 1178 / 1440f;
        idleX = 1000 / 2304f; idleY = 1420 / 1440f;
        playerX = 0.486f; playerY = 0.73f;
        shiftTapX = 52 / 2304f; shiftTapY = 888 / 1440f;   // icon centre: toggled 5/5 taps (no D-pad over it here)
    }

    // Vision
    public double minConfidence = 0.80;

    // AI vision: detection boxes drawn over the game
    public boolean showBoxes = true, showUnknown = true, showBuffBoxes = true;
    public int boxThickness = 2, boxLabelSp = 7, detectFps = 8;   // small labels: they must not hide the objects
    public float boxAlpha = 0.9f;
    public double boxMinConf = 0.80;          // world objects: below this a detection is drawn as "Unknown"
    public double buffMinConf = 0.70;         // buff row (measured: 0.70 gave the best precision on the test frames)
    public String hiddenCats = "";            // comma list of Detector.Cat names not drawn
    // AI decisions (all go through MacroService's one input lock)
    public boolean hasteAdjustSkillet = false; // Skillet walks covered by distance at the live Haste speed
    public boolean hastePatterns = true;
    public boolean cornerAnchor = true;
    public boolean visualAnchor = true;   // drift: remember the view at the start of farming, walk back to it after every loop   // corner sprinkler spot + a Natro pattern: line up in that corner every loop, like Skillet
    public boolean hastyGuard = false, giftedHasty = false;   // permanent speed boosts (Natro settings): only used to weigh Coconut Haste / Bear Morph       // Natro patterns: same, so Haste never stretches the pattern (Natro Walk() does this too)
    public boolean collectTokens = false;     // off until you have labelled tokens and checked the boxes
    public int prioBoost = 3, prioToken = 2, prioLoot = 2, prioMark = 1;   // 0 = never collect
    public int maxCollectMs = 1500;           // walking budget per token (base-speed ms, ≈12 tiles)
    public float collectRange = 0.30f;        // max token distance from the player, fraction of screen height
    public float playerX = 0.50f, playerY = 0.60f;   // where your character stands on screen (shift lock on)
    public boolean sprinklerAnchor = true;
    public boolean camGuided = false;         // this device needs the screen-guided camera reset (the fixed tablet zoom didn't find the hive)    // learn your sprinkler on screen each trip; re-centre on it after each loop and when lost
    public boolean pineSkilletV1 = false;     // the Pine Tree Skillet field config (user's Natro JSON) was applied once
    public boolean driftCorrect = false;      // re-centre on a labelled LANDMARK (your sprinkler) after each loop
    public float driftTolerance = 0.10f;      // fraction of screen height
    public int maxCorrectMs = 1200;

    // Overlay / app
    public float overlayAlpha = 0.92f;
    public int overlayX = -1, overlayY = 290, overlayWidthDp = 220;
    public boolean overlayExpanded = false, overlayVisible = true, overlayMinimized = false;
    public boolean batterySaver = true, debug = false, stats = true;

    public static File file(File dir) { return new File(dir, NAME + ".properties"); }

    public static Profile load(File dir) {
        Profile p = new Profile();
        File f = file(dir);
        if (!f.exists()) { p.save(dir); return p; }
        Properties pr = new Properties();
        try (FileInputStream in = new FileInputStream(f)) { pr.load(in); } catch (Exception e) { return p; }
        for (java.lang.reflect.Field fl : Profile.class.getFields()) {
            if (java.lang.reflect.Modifier.isStatic(fl.getModifiers())) continue;
            String v = pr.getProperty(fl.getName());
            if (v == null) continue;
            try {
                Class<?> t = fl.getType();
                if (t == int.class) fl.setInt(p, Integer.parseInt(v));
                else if (t == double.class) fl.setDouble(p, Double.parseDouble(v));
                else if (t == float.class) fl.setFloat(p, Float.parseFloat(v));
                else if (t == boolean.class) fl.setBoolean(p, Boolean.parseBoolean(v));
                else if (t == String.class) fl.set(p, v);
            } catch (Exception ignored) { }
        }
        // the first builds shipped the developer's own private server as the default link: never send people there
        if (p.serverLink != null && p.serverLink.contains("516f383a1f44dd44a7c85242f14be297")) { p.serverLink = ""; p.save(dir); }
        return p;
    }

    public synchronized boolean save(File dir) {
        Properties pr = new Properties();
        try {
            for (java.lang.reflect.Field fl : Profile.class.getFields())
                if (!java.lang.reflect.Modifier.isStatic(fl.getModifiers())) pr.setProperty(fl.getName(), String.valueOf(fl.get(this)));
        } catch (IllegalAccessException e) { return false; }
        pr.setProperty("profile", NAME);
        pr.setProperty("preset", PRESET);
        File tmp = new File(dir, NAME + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) { pr.store(out, NAME); } catch (Exception e) { return false; }
        return tmp.renameTo(file(dir)) || (file(dir).delete() && tmp.renameTo(file(dir)));
    }
}
