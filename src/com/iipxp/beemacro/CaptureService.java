package com.iipxp.beemacro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/** MediaProjection screen capture (user consent required by Android). Keeps the latest frame as ARGB ints. */
public class CaptureService extends Service {
    public static volatile CaptureService inst;
    public static final class Frame { public int[] px; public int w, h; public long time; }

    private MediaProjection mp;
    private VirtualDisplay vd;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler handler;
    private final Frame frame = new Frame();
    private int[] row;
    private int vw, vh;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("cap", "Screen recognition", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "cap").setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Silly Macro is watching the screen").setContentText("Pine Tree Forest recognition active").setOngoing(true).build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        if (mp != null || intent == null) return START_NOT_STICKY;
        try {
            mp = getSystemService(MediaProjectionManager.class).getMediaProjection(intent.getIntExtra("code", 0), intent.getParcelableExtra("data", Intent.class));
        } catch (Exception e) { Log.add("capture: projection failed " + e); stopSelf(); return START_NOT_STICKY; }
        if (mp == null) { stopSelf(); return START_NOT_STICKY; }
        thread = new HandlerThread("capture"); thread.start(); handler = new Handler(thread.getLooper());
        mp.registerCallback(new MediaProjection.Callback() { @Override public void onStop() { Log.add("capture: projection stopped by system/user"); stopSelf(); } }, handler);
        Point s = size();
        vw = s.x; vh = s.y;
        reader = ImageReader.newInstance(vw, vh, PixelFormat.RGBA_8888, 2);
        vd = mp.createVirtualDisplay("beemacro", vw, vh, getResources().getDisplayMetrics().densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
        inst = this;
        Monitor.start(this);
        Log.add("capture: started " + vw + "x" + vh);
        return START_NOT_STICKY;
    }

    /** Ends sharing: pauses the macro (onDestroy), releases the projection and removes the notification. */
    public static void stopCapture(android.content.Context c) {
        Log.add("capture: stopped by user");
        c.stopService(new Intent(c, CaptureService.class));
    }

    Point size() { Point p = new Point(); getSystemService(WindowManager.class).getDefaultDisplay().getRealSize(p); return p; }

    /** Latest frame (cached if the screen has not changed). Follows rotation by resizing the virtual display. */
    public synchronized Frame grab() {
        if (vd == null) return null;
        Point s = size();
        if (s.x != vw || s.y != vh) {
            vw = s.x; vh = s.y;
            ImageReader old = reader;
            reader = ImageReader.newInstance(vw, vh, PixelFormat.RGBA_8888, 2);
            vd.resize(vw, vh, getResources().getDisplayMetrics().densityDpi);
            vd.setSurface(reader.getSurface());
            old.close();
            frame.px = null;
            SystemClock.sleep(150);
        }
        Image img = null;
        try { img = reader.acquireLatestImage(); } catch (Exception ignored) { }
        if (img != null) {
            try {
                Image.Plane pl = img.getPlanes()[0];
                int w = img.getWidth(), h = img.getHeight(), stride = pl.getRowStride() / 4;
                if (frame.px == null || frame.px.length != w * h) frame.px = new int[w * h];
                IntBuffer ib = pl.getBuffer().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
                int[] o = frame.px;
                if (stride == w && ib.remaining() >= w * h) {          // no row padding: one bulk copy, swap in place
                    ib.get(o, 0, w * h);
                    for (int i = 0; i < o.length; i++) { int c = o[i]; o[i] = 0xFF000000 | ((c & 0xFF) << 16) | (c & 0xFF00) | ((c >> 16) & 0xFF); }
                } else {
                    if (row == null || row.length != stride) row = new int[stride];
                    for (int y = 0; y < h; y++) {
                        ib.position(y * stride); ib.get(row, 0, Math.min(stride, ib.remaining()));
                        for (int x = 0; x < w; x++) { int c = row[x]; o[y * w + x] = 0xFF000000 | ((c & 0xFF) << 16) | (c & 0xFF00) | ((c >> 16) & 0xFF); }
                    }
                }
                frame.w = w; frame.h = h; frame.time = SystemClock.uptimeMillis();
            } finally { img.close(); }
        }
        return frame.px == null ? null : frame;
    }

    @Override public void onDestroy() {
        inst = null;
        Monitor.stop();
        if (Engine.inst != null && !Engine.inst.finished()) Engine.inst.pause("screen capture stopped");
        try { if (vd != null) vd.release(); if (reader != null) reader.close(); if (mp != null) mp.stop(); } catch (Exception ignored) { }
        vd = null; mp = null;
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }
}
