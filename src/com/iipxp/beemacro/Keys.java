package com.iipxp.beemacro;

import android.content.Context;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;

/** App side of {@link KeyServer}: holds and taps real keyboard keys in Roblox. All calls block until acknowledged. */
final class Keys {
    static final int W = KeyEvent.KEYCODE_W, A = KeyEvent.KEYCODE_A, S = KeyEvent.KEYCODE_S, D = KeyEvent.KEYCODE_D,
            SPACE = KeyEvent.KEYCODE_SPACE, E = KeyEvent.KEYCODE_E, R = KeyEvent.KEYCODE_R, ENTER = KeyEvent.KEYCODE_ENTER,
            ESC = KeyEvent.KEYCODE_ESCAPE, SHIFT = KeyEvent.KEYCODE_SHIFT_LEFT, COMMA = KeyEvent.KEYCODE_COMMA,
            PERIOD = KeyEvent.KEYCODE_PERIOD, PGUP = KeyEvent.KEYCODE_PAGE_UP, PGDN = KeyEvent.KEYCODE_PAGE_DOWN;

    private static Socket sock; private static PrintWriter out; private static BufferedReader in;
    static volatile String status = "not connected";
    static volatile long lastSent;
    /** Runs after every NEW connection (a release, e.g. on pause, drops the old one and the key sender stops clicking). */
    static volatile Runnable onReconnect;   // uptime of the last command (any key or click) sent to the game

    /** Random token in the app's external files dir; KeyServer (shell) reads it to accept only this app. */
    static String token(Context c) {
        try {
            File dir = c.getExternalFilesDir(null);
            File f = new File(dir, "key_token");
            if (!f.exists()) {
                String t = Long.toHexString(new java.security.SecureRandom().nextLong()) + Long.toHexString(System.nanoTime());
                try (FileWriter w = new FileWriter(f)) { w.write(t); }
            }
            return new String(java.nio.file.Files.readAllBytes(f.toPath())).trim();
        } catch (Exception e) { return ""; }
    }

    private static long lastAutoStart;
    /** Connects; if the key sender isn't running and Shizuku is set up, starts it on the tablet (at most every 30 s). */
    static boolean connect(Context c) {
        if (connect(c, false)) return true;
        boolean bg = android.os.Looper.myLooper() != android.os.Looper.getMainLooper();
        long now = android.os.SystemClock.elapsedRealtime();
        if (!bg || now - lastAutoStart < 30_000 || !ShizukuKeys.ready()) return false;
        lastAutoStart = now;
        Log.add("keys: not running, starting it through Shizuku: " + ShizukuKeys.start(c));
        return connect(c, false);
    }
    static synchronized boolean connect(Context c, boolean unused) {
        if (sock != null && sock.isConnected() && !sock.isClosed()) return true;
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", KeyServer.PORT), 500);
            s.setTcpNoDelay(true); s.setSoTimeout(2000);
            PrintWriter o = new PrintWriter(s.getOutputStream(), true);
            BufferedReader i = new BufferedReader(new InputStreamReader(s.getInputStream()));
            o.println(token(c));
            String r = i.readLine();
            if (!"ok".equals(r)) { s.close(); status = "key sender refused the connection (token)"; return false; }
            sock = s; out = o; in = i; status = "connected";
            Runnable after = onReconnect; if (after != null) after.run();   // e.g. switch the tool's auto-click back on
            return true;
        } catch (Exception e) { status = "key sender not running (start it with Shizuku in the app, or from the PC)"; close(); return false; }
    }
    static synchronized void close() { try { if (sock != null) sock.close(); } catch (Exception ignored) { } sock = null; out = null; in = null; }

    private static synchronized boolean send(String cmd) {
        if (out == null) return false;
        lastSent = android.os.SystemClock.uptimeMillis();
        try { out.println(cmd); return "ok".equals(in.readLine()); } catch (Exception e) { close(); status = "lost connection"; return false; }
    }
    static boolean down(int code) { return send("d " + code); }
    static boolean up(int code) { return send("u " + code); }
    static boolean ping() { return send("p"); }
    /** The key sender's keyboard/mouse emulator: "hid tool", "uhid", "none: why", or null if it can't say (older sender). */
    static volatile String emulator;
    static synchronized String emulatorState() {
        if (out == null) return null;
        try {
            out.println("st"); String r = in.readLine();
            int[] proto = {0};
            String rest = SenderStatus.parse(r, proto);
            hkKnown = proto[0] >= 1; senderProto = proto[0];
            emulator = rest;
        }
        catch (Exception e) { close(); emulator = null; }
        return emulator;
    }
    static boolean mouseDown(int x, int y) { return send("md " + x + " " + y); }
    static boolean mouseUp(int x, int y) { return send("mu " + x + " " + y); }
    static boolean rejoin(String link) { return send("rj " + link); }
    /** `wm density DPI` as the shell user (protocol 4+: an older sender would answer "ok" to anything). */
    static boolean setDensity(int dpi) { emulatorState(); return senderProto >= 4 && send("sw " + dpi); }
    /** `wm size` (portrait W x H) + density, or back to the real size (w <= 0). Protocol 4+. */
    static boolean setResolution(int w, int h, int dpi) { emulatorState(); return senderProto >= 4 && send(w <= 0 ? "sr reset" : "sr " + w + " " + h + " " + dpi); }
    static boolean autoClick(boolean on, int x, int y) { return send("ac " + (on ? 1 : 0) + " " + x + " " + y); }
    /** Samsung Game Booster locks the screen ("touch protection") after ~3 min with no input; Scroll Lock does
     *  nothing in Bee Swarm but counts as input (tested live 2026-11-21: no lock in 5 min with a tap every 45 s). */
    static boolean nudge() { return tap(KeyEvent.KEYCODE_SCROLL_LOCK, 40); }
    /** Press and release, holding ms in between. */
    static boolean tap(int code, long ms) {
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {   // hotbar: as a real key press on the virtual keyboard
            if (sendHk(code, ms)) return true;
        }
        boolean ok = down(code); android.os.SystemClock.sleep(ms); return up(code) && ok;
    }
    /** "hk": the number key as a virtual-keyboard report. Only sent to a key sender that said "+hk" in its status
     *  reply: an older one never answers it (the connection would time out and drop). */
    private static synchronized boolean sendHk(int code, long ms) {
        if (out == null || !hkKnown) return false;
        lastSent = android.os.SystemClock.uptimeMillis();
        try { out.println("hk " + code + " " + ms); return "ok".equals(in.readLine()); }
        catch (Exception e) { close(); return false; }
    }
    static volatile boolean hkKnown;
    /** The running key sender's protocol (SenderStatus.PROTO): 0 = older than "+hk", 1 = "+hk" only, 2+ = says its number. */
    static volatile int senderProto;
    /** Drop the connection: the server releases every held key. Safe from any thread. */
    static void releaseAll() { Socket s = sock; try { if (s != null) s.close(); } catch (Exception ignored) { } }
}
