package com.iipxp.beemacro;

import android.content.Context;
import android.content.pm.PackageManager;

import rikka.shizuku.Shizuku;

/** Starts {@link KeyServer} on the tablet itself, no PC: Shizuku (started by the user with the tablet's own wireless
 *  debugging) runs our command as the shell user, exactly what start_keys.ps1 does over adb. Uses Shizuku's API
 *  (dev.rikka.shizuku 13.1.5, Apache-2.0). */
final class ShizukuKeys {
    private static final int REQ = 7;
    private static boolean listening;

    /** Shizuku is running and has allowed Silly Macro. */
    static boolean ready() {
        try { return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
        catch (Throwable t) { return false; }
    }

    /** Blocking (call off the main thread). Returns a message for the user. */
    static String start(Context c) {
        try {
            if (!Shizuku.pingBinder()) return "Shizuku isn't running: open the Shizuku app and press Start (" + (android.os.Build.VERSION.SDK_INT >= 30 ? "wireless debugging" : "Android 10 and older: start it from a PC or with root") + ")";
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                synchronized (ShizukuKeys.class) {
                    if (!listening) {   // start as soon as the user allows it in Shizuku's prompt
                        listening = true;
                        Context app = c.getApplicationContext();
                        Shizuku.addRequestPermissionResultListener((code, res) -> {
                            if (code == REQ && res == PackageManager.PERMISSION_GRANTED) new Thread(() -> Log.add("shizuku: " + start(app))).start();
                        });
                    }
                }
                Shizuku.requestPermission(REQ);
                return "Allow Silly Macro in the Shizuku prompt; the key sender starts right after";
            }
            String apk = c.getApplicationInfo().sourceDir;
            // Android app dirs look like /data/app/~~abc==/pkg-xyz==/base.apk; anything else (spaces, quotes) is refused
            if (!apk.matches("[\\w./=+~-]+")) return "unexpected app path: " + apk;
            // same command as start_keys.ps1: stop an old Silly Macro key sender, start a detached one
            String cmd = "for p in $(ps -A -o PID,ARGS | grep com.iipxp.beemacro.KeyServer | grep -v grep | awk '{print $1}'); do kill $p; done; "
                    + "CLASSPATH=" + apk + " setsid nohup app_process / com.iipxp.beemacro.KeyServer > /data/local/tmp/keyserver.log 2>&1 < /dev/null &";
            // newProcess is private in API 13 but still the supported way to run a command; it is our own bundled class
            java.lang.reflect.Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            Process pr = (Process) m.invoke(null, new String[]{"sh", "-c", cmd}, null, null);
            pr.waitFor();   // sh returns at once; the key sender keeps running on its own
            Keys.close();
            for (int i = 0; i < 16; i++) {   // up to 8 s: app_process can take a few seconds to come up on slow devices
                android.os.SystemClock.sleep(500);
                if (Keys.connect(c, false)) return "key sender running";
            }
            return "started, but no answer after 8 s: " + Keys.status + ". Restart Shizuku and try again";
        } catch (Throwable t) { return "Shizuku start failed: " + t; }
    }
}
