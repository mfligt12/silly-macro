package com.iipxp.beemacro;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.Locale;

/** Reads battery temperature and Android's thermal status (at most every 5 s) and updates {@link AiPower}. */
final class AiSensors {
    private static long lastRead;

    static AiPower.Level update(Context c, Profile p) {
        if (!p.aiThermal) { AiPower.level = AiPower.Level.NORMAL; AiPower.why = "heat guard off"; return AiPower.level; }
        long now = SystemClock.elapsedRealtime();
        if (now - lastRead < 5000) return AiPower.level;
        lastRead = now;
        try {
            Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                AiPower.tempC = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -10) / 10f;
                AiPower.battery = b.getIntExtra("level", -1) * 100 / Math.max(1, b.getIntExtra("scale", 100));
                AiPower.charging = b.getIntExtra("plugged", 0) != 0;
            }
            if (Build.VERSION.SDK_INT >= 29) AiPower.thermal = c.getSystemService(PowerManager.class).getCurrentThermalStatus();
        } catch (Exception ignored) { }
        AiPower.Level was = AiPower.level;
        AiPower.level = AiPower.classify(AiPower.tempC, AiPower.thermal, was == AiPower.Level.HOT, p.aiThrottleC, p.aiPauseC, p.aiResumeC);
        AiPower.why = String.format(Locale.US, "%s · battery %.1f°C · thermal %d%s", AiPower.level.name().toLowerCase(Locale.US),
                AiPower.tempC, AiPower.thermal, AiPower.charging ? " · charging" : "");
        if (AiPower.level != was) Log.add("ai power: " + AiPower.why);
        return AiPower.level;
    }
}
