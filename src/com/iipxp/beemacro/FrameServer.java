package com.iipxp.beemacro;

import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

/**
 * Screen frames without MediaProjection. Runs inside the key sender's shell process (KeyServer.main starts it), where
 * Android lets the shell user mirror the display the same way scrcpy does: no consent dialog, no cast icon, nothing
 * recorded or stored. The app asks for ONE frame at a time on 127.0.0.1:{@link #PORT} (same token as KeyServer).
 *
 * Battery: the mirror only receives frames while the app is asking. After {@link #IDLE_MS} with no request its
 * surface is detached, so SurfaceFlinger does no work for it at all until the next request.
 *
 * Paths, best first: (1) Android 14+ DisplayManager.createVirtualDisplay(name, w, h, displayToMirror, surface);
 * (2) Android 9-13 SurfaceControl.createDisplay + layer-stack projection; (3) the `screencap` tool (slow, ~0.3 s,
 * but works everywhere the shell user exists).
 *
 * Wire format per request: the app sends "f MAXW\n" (0 = native size); the reply is 4 big-endian ints
 * MAGIC, status (0 ok), width, height, then width*height*4 bytes RGBA_8888 with no row padding.
 */
public final class FrameServer {
    public static final int PORT = KeyServer.PORT + 1;
    public static final int MAGIC = FrameWire.MAGIC;
    static final long IDLE_MS = 1500, FIRST_FRAME_MS = 700;
    static volatile String mode = "starting";
    static final Mirror mirror = new Mirror();

    /** Call from KeyServer.main (its main thread). */
    public static void start() {
        try { if (Looper.getMainLooper() == null) Looper.prepareMainLooper(); } catch (Throwable ignored) { }
        Thread t = new Thread(FrameServer::acceptLoop, "frames"); t.setDaemon(true); t.start();
        Thread idle = new Thread(() -> { while (true) { SystemClock.sleep(500); mirror.idleCheck(); } }, "frames-idle");
        idle.setDaemon(true); idle.start();
    }

    static void acceptLoop() {
        try (ServerSocket ss = new ServerSocket(PORT, 2, InetAddress.getByName("127.0.0.1"))) {
            mode = "ready (no mirror yet)";
            System.out.println("frames: ready on 127.0.0.1:" + PORT);
            while (true) { Socket s = ss.accept(); new Thread(() -> serve(s), "frames-client").start(); }
        } catch (Exception e) { mode = "none: " + e.getMessage(); System.out.println("frames: not available - " + e); }
    }

    static void serve(Socket s) {
        try (Socket sock = s; BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
             DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(sock.getOutputStream(), 1 << 16))) {
            sock.setTcpNoDelay(true);
            String token = new String(Files.readAllBytes(new File(KeyServer.TOKEN_FILE).toPath())).trim();
            String first = in.readLine();
            if (token.isEmpty() || first == null || !first.trim().equals(token)) { out.writeInt(MAGIC); out.writeInt(2); out.writeInt(0); out.writeInt(0); out.flush(); return; }
            if (Looper.myLooper() == null) Looper.prepare();   // display APIs may create Handlers on this thread
            for (String line; (line = in.readLine()) != null; ) {
                String[] p = line.trim().split(" ");
                if (!p[0].equals("f")) { out.writeInt(MAGIC); out.writeInt(3); out.writeInt(0); out.writeInt(0); out.flush(); continue; }
                int maxW = p.length > 1 ? Integer.parseInt(p[1]) : 0;
                synchronized (mirror) {
                    Mirror.Shot shot = mirror.grab(maxW);
                    out.writeInt(MAGIC);
                    if (shot == null) { out.writeInt(1); out.writeInt(0); out.writeInt(0); }
                    else { out.writeInt(0); out.writeInt(shot.w); out.writeInt(shot.h); out.write(shot.rgba, 0, shot.w * shot.h * 4); }
                    out.flush();
                }
            }
        } catch (Exception e) { System.out.println("frames client: " + e); }
    }

    // ------------------------------------------------------------------ display mirror

    static final class Mirror {
        static final class Shot { byte[] rgba; int w, h; long time; }
        private ImageReader reader;
        private android.hardware.display.VirtualDisplay vd;   // Android 14+ path
        private IBinder scDisplay;                             // SurfaceControl path
        private int w, h, srcW, srcH, rotation = -1;
        private boolean attached;
        private long attachNs, lastReq;
        private final Shot shot = new Shot();
        private boolean mirrorBroken;   // both mirror paths failed on this device: screencap only

        synchronized Shot grab(int maxW) {
            lastReq = SystemClock.uptimeMillis();
            try {
                if (!mirrorBroken) {
                    int[] di = displayInfo();   // logical w, h, rotation, layerStack
                    int tw = di[0], th = di[1];
                    if (maxW > 0 && tw > maxW) { th = Math.round(th * (maxW / (float) tw)); tw = maxW; }
                    tw &= ~1; th &= ~1;
                    if (reader == null || tw != w || th != h || di[2] != rotation) { release(); create(tw, th, di); }
                    Shot s = fromMirror();
                    if (s != null) return s;
                }
            } catch (Throwable e) {
                System.out.println("frames: mirror failed (" + e + "), using screencap from now on");
                release(); mirrorBroken = true;
            }
            Shot s = screencap();
            if (s != null) mode = "screencap (slow fallback)";
            return s;
        }

        private void create(int tw, int th, int[] di) throws Exception {
            w = tw; h = th; srcW = di[0]; srcH = di[1]; rotation = di[2];
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            attached = false;
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    Method m = android.hardware.display.DisplayManager.class.getMethod("createVirtualDisplay",
                            String.class, int.class, int.class, int.class, Surface.class);
                    vd = (android.hardware.display.VirtualDisplay) m.invoke(null, "silly-frames", w, h, 0, null);
                    mode = "display mirror (Android 14+)";
                    System.out.println("frames: " + mode + " " + w + "x" + h);
                    return;
                } catch (Throwable e) { System.out.println("frames: DisplayManager mirror unavailable (" + e + "), trying SurfaceControl"); vd = null; }
            }
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            boolean secure = Build.VERSION.SDK_INT < 30 || (Build.VERSION.SDK_INT == 30 && !"S".equals(Build.VERSION.CODENAME));
            scDisplay = (IBinder) sc.getMethod("createDisplay", String.class, boolean.class).invoke(null, "silly-frames", secure);
            scTransaction(null, di[3]);
            mode = "display mirror (SurfaceControl)";
            System.out.println("frames: " + mode + " " + w + "x" + h);
        }

        /** Points the SurfaceControl display at `surface` (null = detached) showing the main layer stack. */
        private void scTransaction(Surface surface, int layerStack) throws Exception {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            sc.getMethod("openTransaction").invoke(null);
            try {
                sc.getMethod("setDisplaySurface", IBinder.class, Surface.class).invoke(null, scDisplay, surface);
                sc.getMethod("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class)
                        .invoke(null, scDisplay, 0, new Rect(0, 0, srcW, srcH), new Rect(0, 0, w, h));
                sc.getMethod("setDisplayLayerStack", IBinder.class, int.class).invoke(null, scDisplay, layerStack);
            } finally { sc.getMethod("closeTransaction").invoke(null); }
        }
        private int layerStack = 0;

        private void attach(boolean on) throws Exception {
            if (attached == on) return;
            Surface s = on ? reader.getSurface() : null;
            if (vd != null) vd.setSurface(s); else if (scDisplay != null) scTransaction(s, layerStack);
            attached = on;
            if (on) { attachNs = System.nanoTime(); Image old; while ((old = reader.acquireLatestImage()) != null) old.close(); }
        }

        /** Newest frame from the mirror. Attached continuously while requests keep coming; a static screen produces no
         *  new frames, so the previous one is returned again then. */
        private Shot fromMirror() throws Exception {
            boolean fresh = !attached;
            attach(true);
            long end = SystemClock.uptimeMillis() + (fresh ? FIRST_FRAME_MS : 120);
            Image img = null;
            while (img == null && SystemClock.uptimeMillis() < end) {
                img = reader.acquireLatestImage();
                if (img == null) SystemClock.sleep(4);
            }
            if (img == null) {
                if (!fresh && shot.rgba != null && shot.w == w && shot.h == h) return shot;   // screen unchanged
                if (fresh) throw new IllegalStateException("mirror gave no frame in " + FIRST_FRAME_MS + " ms");
                return null;
            }
            try {
                Image.Plane pl = img.getPlanes()[0];
                ByteBuffer b = pl.getBuffer();
                int iw = img.getWidth(), ih = img.getHeight(), rs = pl.getRowStride(), row = iw * 4;
                if (shot.rgba == null || shot.rgba.length < iw * ih * 4) shot.rgba = new byte[iw * ih * 4];
                if (rs == row) b.get(shot.rgba, 0, row * ih);
                else for (int y = 0; y < ih; y++) { b.position(y * rs); b.get(shot.rgba, y * row, row); }
                shot.w = iw; shot.h = ih; shot.time = SystemClock.uptimeMillis();
                return shot;
            } finally { img.close(); }
        }

        /** Detach the mirror after IDLE_MS without requests: no compositing for us while nobody looks. */
        synchronized void idleCheck() {
            if (attached && SystemClock.uptimeMillis() - lastReq > IDLE_MS) {
                try { attach(false); } catch (Throwable e) { System.out.println("frames: detach failed " + e); }
            }
        }

        private void release() {
            try { if (vd != null) vd.release(); } catch (Throwable ignored) { }
            try { if (scDisplay != null) Class.forName("android.view.SurfaceControl").getMethod("destroyDisplay", IBinder.class).invoke(null, scDisplay); } catch (Throwable ignored) { }
            try { if (reader != null) reader.close(); } catch (Throwable ignored) { }
            vd = null; scDisplay = null; reader = null; attached = false; w = h = 0;
        }

        /** Last resort: the screencap tool's raw output (header + RGBA). */
        private Shot screencap() {
            try {
                Process pr = new ProcessBuilder("screencap").redirectErrorStream(false).start();
                byte[] all;
                try (InputStream is = pr.getInputStream()) { all = readAll(is); }
                pr.waitFor();
                int[] whh = screencapLayout(all);
                if (whh == null) return null;
                int n = whh[0] * whh[1] * 4;
                if (shot.rgba == null || shot.rgba.length < n) shot.rgba = new byte[n];
                System.arraycopy(all, whh[2], shot.rgba, 0, n);
                shot.w = whh[0]; shot.h = whh[1]; shot.time = SystemClock.uptimeMillis();
                return shot;
            } catch (Exception e) { System.out.println("frames: screencap failed " + e); return null; }
        }
    }

    static int[] screencapLayout(byte[] all) { return FrameWire.screencapLayout(all); }

    static byte[] readAll(InputStream is) throws java.io.IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(1 << 20); byte[] buf = new byte[1 << 16];
        for (int n; (n = is.read(buf)) > 0; ) o.write(buf, 0, n);
        return o.toByteArray();
    }

    // Fresh display info straight from the display service (like KeyServer.deviceIds): {logicalWidth, logicalHeight, rotation, layerStack}
    private static Object displayService; private static Method getInfo;
    static int[] displayInfo() throws Exception {
        if (displayService == null) {
            Object b = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "display");
            displayService = Class.forName("android.hardware.display.IDisplayManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, b);
            getInfo = displayService.getClass().getMethod("getDisplayInfo", int.class);
        }
        Object info = getInfo.invoke(displayService, 0);
        Class<?> c = info.getClass();
        int ls = 0; try { ls = c.getField("layerStack").getInt(info); } catch (Throwable ignored) { }
        mirror.layerStack = ls;
        return new int[]{c.getField("logicalWidth").getInt(info), c.getField("logicalHeight").getInt(info), c.getField("rotation").getInt(info), ls};
    }
}
