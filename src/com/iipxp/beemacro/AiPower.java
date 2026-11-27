package com.iipxp.beemacro;

/**
 * Heat governor for long runs. It decides how hard the macro may work:
 *   NORMAL    the vision loop runs at its normal rate
 *   THROTTLED warm: vision runs 2.5x slower (Monitor)
 *   HOT       too hot: the trip ends early and the macro waits at the hive until the phone cools (Engine)
 * With hysteresis, so it doesn't flap around the limit. Pure Java (desktop-tested); AiSensors feeds it.
 */
public final class AiPower {
    public enum Level { NORMAL, THROTTLED, HOT }

    static volatile Level level = Level.NORMAL;
    static volatile float tempC = -1; static volatile int thermal = -1, battery = -1; static volatile boolean charging;
    static volatile String why = "not measured yet";

    /** Battery temp (°C, <0 unknown), thermal status (PowerManager.THERMAL_STATUS_*: 2 moderate, 3 severe; -1 unknown),
     *  and whether we are HOT already (stays HOT until below resumeC and thermal is under MODERATE). */
    public static Level classify(float tempC, int thermal, boolean wasHot, float throttleC, float pauseC, float resumeC) {
        boolean hot = (tempC >= 0 && tempC >= pauseC) || thermal >= 3;
        boolean cooled = (tempC < 0 || tempC <= resumeC) && thermal < 2;
        if (hot || (wasHot && !cooled)) return Level.HOT;
        if ((tempC >= 0 && tempC >= throttleC) || thermal >= 2) return Level.THROTTLED;
        return Level.NORMAL;
    }

    /** How much slower the vision loop should run. */
    public static float periodFactor(Level l) { return l == Level.THROTTLED ? 2.5f : l == Level.HOT ? 4f : 1f; }
}
