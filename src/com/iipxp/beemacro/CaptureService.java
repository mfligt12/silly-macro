package com.iipxp.beemacro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.SystemClock;

/**
 * Screen reading WITHOUT MediaProjection: frames come from the key sender's shell process ({@link FrameServer},
 * started with the key sender through Shizuku). No consent dialog, no cast icon, nothing recorded or saved.
 * Keeps the same API as before (inst, Frame, grab(), stopCapture) so the rest of the app is unchanged.
 */
public class CaptureService extends Service {
    public static volatile CaptureService inst;
    /** Only meaningful for MediaProjection's "single app" sharing; the shell mirror always sees the whole screen. */
    public static volatile boolean contentHidden;
    public static final class Frame { public int[] px; public int w, h; public long time; }

    private final Frame frame = new Frame();
    private long lastFix, failSince;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("cap", "Screen recognition", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "cap").setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Silly Macro is reading the screen").setContentText("On this phone only, through the key sender (no recording)").setOngoing(true).build();
        if (android.os.Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
        if (inst != null) return START_NOT_STICKY;
        contentHidden = false;
        inst = this;
        Monitor.start(this);
        Log.add("capture: started (shell frames, no MediaProjection)");
        return START_NOT_STICKY;
    }

    /** Stops screen reading: pauses the macro (onDestroy) and removes the notification. */
    public static void stopCapture(android.content.Context c) {
        Log.add("capture: stopped by user");
        c.stopService(new Intent(c, CaptureService.class));
    }

    /** Latest frame, or null (the key sender isn't running yet: it is started through Shizuku when possible). */
    public synchronized Frame grab() {
        if (ShellFrames.connect(this) && ShellFrames.grab(frame, 0)) { failSince = 0; return frame; }
        long now = SystemClock.elapsedRealtime();
        if (failSince == 0) failSince = now;
        // The frame sender lives in the key sender: start it, or restart an older one that has no frames (protocol < 6)
        if (now - lastFix > 30_000 && now - failSince > 2000) {
            lastFix = now;
            boolean up = Keys.connect(this);
            if (up) Keys.emulatorState();
            if (up && Keys.senderProto < 6 && ShizukuKeys.ready()) Log.add("capture: key sender is too old for screen frames, restarting it: " + ShizukuKeys.start(this));
            else if (!up) Log.add("capture: no frames (" + ShellFrames.status + ", keys " + Keys.status + ")");
        }
        return null;
    }

    @Override public void onDestroy() {
        inst = null;
        Monitor.stop();
        if (Engine.inst != null && !Engine.inst.finished()) Engine.inst.pause("screen reading stopped");
        ShellFrames.close();
        super.onDestroy();
    }
}
