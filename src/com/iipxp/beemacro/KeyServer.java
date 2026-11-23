package com.iipxp.beemacro;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * Keyboard sender. Android lets only system/shell-level code inject key events into another app, so this runs as
 * the adb shell user (started with app_process, see README/start command), never inside the app. Roblox receives
 * the keys as a real keyboard (source KEYBOARD) and stays in its keyboard/mouse layout.
 *
 * Listens on 127.0.0.1:{@link #PORT} only. The first line from a client must be the token the Bee Macro app wrote
 * to its external files dir (shell can read it, other apps can't), otherwise the connection is closed.
 * Commands: "d CODE" key down, "u CODE" key up, "p" ping. Every key still held is released when the client
 * disconnects, so a crashed or stopped macro can never leave W held.
 */
public final class KeyServer {
    public static final int PORT = 28761;

    public static final String TOKEN_FILE = "/sdcard/Android/data/com.iipxp.beemacro/files/key_token";
    private static Object im; private static Method inject;

    public static void main(String[] args) throws Exception {
        try {   // Android 14+ moved injection to InputManagerGlobal
            Class<?> c = Class.forName("android.hardware.input.InputManagerGlobal");
            im = c.getMethod("getInstance").invoke(null);
            inject = c.getMethod("injectInputEvent", InputEvent.class, int.class);
        } catch (ClassNotFoundException e) {
            Class<?> c = android.hardware.input.InputManager.class;
            im = c.getMethod("getInstance").invoke(null);
            inject = c.getMethod("injectInputEvent", InputEvent.class, int.class);
        }
        if (java.util.Arrays.asList(args).contains("--uhid")) { uhidOnly = true; }   // testing: skip the hid tool
        // listen first: creating the virtual keyboard can take 12 s where it fails (cloud phones), and the app gave up
        // after 2 s with "started, but no answer yet" (Discord, 2026-11-22). Until it's done, "st" answers "starting".
        ServerSocket ss = new ServerSocket(PORT, 2, InetAddress.getByName("127.0.0.1"));
        new Thread(KeyServer::startVirtualDevice, "virtual-device").start();
        System.out.println("beemacro keyserver ready on 127.0.0.1:" + PORT);
        while (true) {
            Socket s = ss.accept();
            new Thread(() -> serve(s), "client").start();
        }
    }

    /** The connection that turned auto-click on: only its end may stop it (a second client closing, e.g. a test,
     *  stopped the macro's tool clicks mid-farm on 2026-11-21). */
    static volatile Socket clickOwner;

    static void serve(Socket s) {
        Map<Integer, Long> held = new HashMap<>();
        try (Socket sock = s; BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
             PrintWriter out = new PrintWriter(sock.getOutputStream(), true)) {
            String token = new String(Files.readAllBytes(new File(TOKEN_FILE).toPath())).trim();
            String first = in.readLine();
            if (token.isEmpty() || first == null || !first.trim().equals(token)) { out.println("denied"); return; }
            out.println("ok");
            for (String line; (line = in.readLine()) != null; ) {
                line = line.trim();
                if (line.equals("p")) { out.println("ok"); continue; }
                if (line.equals("st")) { out.println("ok " + emulator + " (keyboard " + keyboardId() + ", mouse " + mouseId() + ") +hk +p" + SenderStatus.PROTO); continue; }   // +hk: knows "hk"; +pN: this protocol (the app restarts an older sender)   // which emulator, and the device ids keys/clicks use
                String[] p = line.split(" ");
                if (p[0].equals("rj") && p.length == 2) { out.println(rejoin(p[1]) ? "ok" : "bad link"); continue; }
                if (p[0].equals("hk") && p.length == 3) { out.println(hidKey(Integer.parseInt(p[1]), Long.parseLong(p[2])) ? "ok" : "no hid"); continue; }   // "hk CODE MS": number key on the virtual keyboard
                int code = Integer.parseInt(p[1]);
                if (p[0].equals("ac")) {   // "ac 1|0 X Y": only the connection that switched clicking on may switch it off
                    if (code == 1) { clickOwner = sock; autoClick(true, Integer.parseInt(p[2]), Integer.parseInt(p[3])); }
                    else if (clickOwner == null || clickOwner == sock) { clickOwner = null; autoClick(false, 0, 0); }
                    out.println("ok"); continue;
                }
                if (p[0].equals("md") || p[0].equals("mu")) {   // "md X Y" / "mu X Y": left mouse button at X,Y (screen px)
                    int x = Integer.parseInt(p[1]), y = Integer.parseInt(p[2]);
                    if (p[0].equals("md")) { mouseDown = SystemClock.uptimeMillis(); mx = x; my = y; mouse(true, x, y, mouseDown); }
                    else if (mouseDown != 0) { mouse(false, x, y, mouseDown); mouseDown = 0; }
                    out.println("ok"); continue;
                }
                if (p.length != 2) continue;
                if (p[0].equals("d")) { long t = SystemClock.uptimeMillis(); held.put(code, t); key(KeyEvent.ACTION_DOWN, code, t); }
                else if (p[0].equals("u")) { Long t = held.remove(code); key(KeyEvent.ACTION_UP, code, t == null ? SystemClock.uptimeMillis() : t); }
                out.println("ok");
            }
        } catch (Exception e) {
            System.out.println("client error: " + e);
        } finally {
            if (clickOwner == s) { clickOwner = null; autoClick(false, 0, 0); }
            for (Map.Entry<Integer, Long> e : held.entrySet()) key(KeyEvent.ACTION_UP, e.getKey(), e.getValue());   // never leave a key held
            if (mouseDown != 0) { mouse(false, mx, my, mouseDown); mouseDown = 0; }                               // nor the mouse button
        }
    }

    // ------------------------------------------------------------------ virtual USB keyboard + mouse
    // Android's "hid" developer tool (run as the adb shell user) adds a USB-style device, so Roblox keeps seeing a
    // keyboard and mouse with nothing plugged in and the tablet's port stays free for charging. It lasts as long as
    // this process; the device disappears when the key sender stops.
    static final String HID_DESCRIPTOR =
            "05 01 09 06 A1 01 85 01 05 07 19 E0 29 E7 15 00 25 01 75 01 95 08 81 02 95 01 75 08 81 01 95 06 75 08 15 00 26 FF 00 05 07 19 00 2A FF 00 81 00 C0 "
          + "05 01 09 02 A1 01 85 02 09 01 A1 00 05 09 19 01 29 03 15 00 25 01 95 03 75 01 81 02 95 01 75 05 81 01 05 01 09 30 09 31 09 38 15 81 25 7F 75 08 95 03 81 06 C0 C0";
    static java.io.OutputStream hid;            // Android's hid tool (JSON commands), or null
    static java.io.FileOutputStream uhid;       // or the kernel's /dev/uhid directly (binary events), or null
    static Process hidProc;
    static volatile String emulator = "starting";   // what the app sees: "hid tool", "uhid", or "none: why"
    static volatile boolean clicking;
    static final Object hidLock = new Object();
    static final String NO_KB = " - this device doesn't let Shizuku create a keyboard + mouse (cloud phones and PC emulators usually block it)";
    static final String DEVICE_NAME = "Bee Macro Virtual Keyboard Mouse";

    /** A keyboard + mouse Roblox treats as real, on any device where the shell user may use uhid (Android 11+ hid
     *  tool first; if it is missing or the device never shows up, the same thing straight through /dev/uhid). */
    static boolean uhidOnly;
    static void startVirtualDevice() {
        if (!uhidOnly) try {
            hidProc = new ProcessBuilder("/system/bin/hid", "-").redirectErrorStream(true)
                    .redirectOutput(new File("/data/local/tmp/beemacro_hid.log")).start();
            StringBuilder d = new StringBuilder();
            for (String b : HID_DESCRIPTOR.split(" ")) d.append(d.length() == 0 ? "" : ",").append("\"0x").append(b).append('"');
            hid = hidProc.getOutputStream();
            hidWrite("{\"id\":1,\"command\":\"register\",\"name\":\"" + DEVICE_NAME + "\",\"vid\":\"0x18d1\",\"pid\":\"0x4ee7\",\"bus\":\"usb\",\"descriptor\":[" + d + "]}");
            if (waitDevice()) { emulator = "hid tool"; System.out.println("virtual keyboard + mouse: ready (hid tool)"); return; }
            System.out.println("virtual keyboard + mouse: hid tool gave no device, trying /dev/uhid");
        } catch (Exception e) { System.out.println("hid tool unavailable (" + e.getMessage() + "), trying /dev/uhid"); }
        if (hidProc != null) hidProc.destroy();
        hid = null; hidProc = null;
        try {
            uhid = new java.io.FileOutputStream("/dev/uhid");
            uhidWrite(uhidCreate2(DEVICE_NAME, hexBytes(HID_DESCRIPTOR)));
            if (waitDevice()) { emulator = "uhid"; System.out.println("virtual keyboard + mouse: ready (uhid)"); return; }
            emulator = "none: the uhid device never appeared" + NO_KB;
        } catch (Exception e) { emulator = "none: " + e.getMessage() + NO_KB; }
        try { if (uhid != null) uhid.close(); } catch (Exception ignored) { }
        uhid = null;
        System.out.println("virtual keyboard + mouse: NOT available - " + emulator);
    }

    /** Up to 6 s for Android to add the device (it took longer than 1.5 s on the Tab S9 FE once). */
    static boolean waitDevice() {
        for (int i = 0; i < 30; i++) { if (ourDevice()) return true; SystemClock.sleep(200); }
        StringBuilder seen = new StringBuilder();
        for (int id : deviceIds()) { InputDevice d = device(id); if (d != null) seen.append(id).append('=').append(d.getName()).append(d.isVirtual() ? "(virtual)" : "").append("; "); }
        System.out.println("devices seen: " + seen);
        return false;
    }
    /** Our device is in Android's input list (by name: the keyboard half and the mouse half both carry it). */
    static boolean ourDevice() {
        for (int id : deviceIds()) { InputDevice d = device(id); if (d != null && d.getName().startsWith(DEVICE_NAME)) return true; }
        return false;
    }

    // ---- /dev/uhid: struct uhid_event { u32 type; union {...} } packed, little-endian (linux/uhid.h)
    static final int UHID_EVENT_SIZE = 4 + 128 + 64 + 64 + 2 + 2 + 4 + 4 + 4 + 4 + 4096, UHID_CREATE2 = 11, UHID_INPUT2 = 12;
    static byte[] uhidCreate2(String name, byte[] desc) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(UHID_EVENT_SIZE).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.putInt(UHID_CREATE2);
        byte[] n = name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        b.put(n, 0, Math.min(n.length, 127)); b.position(4 + 128 + 64 + 64);   // name, phys, uniq
        b.putShort((short) desc.length).putShort((short) 0x03)                    // rd_size, bus = BUS_USB
         .putInt(0x18d1).putInt(0x4ee7).putInt(0).putInt(0)                       // vendor, product, version, country
         .put(desc);
        return b.array();
    }
    static byte[] uhidInput2(byte[] report) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(UHID_EVENT_SIZE).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.putInt(UHID_INPUT2).putShort((short) report.length).put(report);
        return b.array();
    }
    static void uhidWrite(byte[] ev) throws java.io.IOException { synchronized (hidLock) { uhid.write(ev); uhid.flush(); } }
    static byte[] hexBytes(String hex) {
        String[] p = hex.trim().split("\\s+"); byte[] o = new byte[p.length];
        for (int i = 0; i < p.length; i++) o[i] = (byte) Integer.parseInt(p[i], 16);
        return o;
    }
    /** One HID input report through whichever path is up. */
    static void report(byte[] r) throws java.io.IOException {
        if (uhid != null) { uhidWrite(uhidInput2(r)); return; }
        StringBuilder j = new StringBuilder();
        for (byte x : r) j.append(j.length() == 0 ? "" : ",").append(String.format("\"0x%02x\"", x & 255));
        hidWrite("{\"id\":1,\"command\":\"report\",\"report\":[" + j + "]}");
    }

    static void hidWrite(String json) throws java.io.IOException {
        synchronized (hidLock) { hid.write((json + "\n").getBytes("UTF-8")); hid.flush(); }
    }
    /** A key pressed on the virtual USB keyboard itself (HID report id 1: modifiers, reserved, 6 keys), the way a real
     *  keyboard sends it. Injected number keys didn't place the sprinkler on a phone without a real keyboard (S23,
     *  2026-11-22) while the tablet, which had one plugged in, did. Returns false when no virtual keyboard is up. */
    static boolean hidKey(int code, long ms) {
        int u = code >= KeyEvent.KEYCODE_1 && code <= KeyEvent.KEYCODE_9 ? 0x1E + (code - KeyEvent.KEYCODE_1) : code == KeyEvent.KEYCODE_0 ? 0x27 : 0;
        if (u == 0 || (hid == null && uhid == null)) return false;
        try {
            report(new byte[]{1, 0, 0, (byte) u, 0, 0, 0, 0, 0});
            SystemClock.sleep(Math.max(40, ms));
            report(new byte[]{1, 0, 0, 0, 0, 0, 0, 0, 0});
            return true;
        } catch (Exception e) { System.out.println("hid key failed: " + e); return false; }
    }
    static void mouseButton(boolean down) throws java.io.IOException { report(new byte[]{2, (byte) (down ? 1 : 0), 0, 0, 0}); }

    /** Fast repeated left clicks (about 10 per second) so the tool keeps swinging. */
    static void autoClick(boolean on, int x, int y) {
        if (on == clicking) return;
        clicking = on;
        if (!on) return;
        new Thread(() -> {
            try {
                while (clicking) {
                    long t = SystemClock.uptimeMillis();
                    if (hid != null || uhid != null) mouseButton(true); else mouse(true, x, y, t);
                    SystemClock.sleep(45);
                    if (hid != null || uhid != null) mouseButton(false); else mouse(false, x, y, t);
                    SystemClock.sleep(55);
                }
            } catch (Exception e) { clicking = false; System.out.println("auto-click stopped: " + e); }
        }, "auto-click").start();
    }

    /** Close Roblox and open the given server link again (only roblox.com share links or roblox:// links). Runs in
     *  the background so the reply comes back at once; the app watches the screen for the game to return. */
    /** Only roblox.com share/game links or roblox:// links, with no characters a shell or intent could misread. */
    static boolean linkOk(String link) { return link != null && link.matches("(https://www\\.roblox\\.com/|roblox://)[A-Za-z0-9/?=&._%-]+"); }

    static boolean rejoin(String link) {
        if (!linkOk(link)) return false;
        new Thread(() -> {
            try {
                new ProcessBuilder("am", "force-stop", "com.roblox.client").start().waitFor();
                SystemClock.sleep(2000);
                new ProcessBuilder("am", "start", "-a", "android.intent.action.VIEW", "-d", link, "-p", "com.roblox.client").start().waitFor();
                System.out.println("rejoin: opened server link");
            } catch (Exception e) { System.out.println("rejoin failed: " + e); }
        }, "rejoin").start();
        return true;
    }

    /** A real (non-virtual) alphabetic keyboard, if one is plugged in: Roblox moved the character only for keys
     *  that looked like they came from a hardware keyboard (measured: virtual-device keys switched the layout but
     *  W did not walk). */
    static volatile long mouseDown; static volatile int mx, my;

    // Fresh device list straight from the input service: the process-local InputDevice cache updated late (a device
    // that existed was reported "NOT found" on 2026-11-21 04:42 and 06:20), so never trust it for decisions.
    private static Object inputService; private static Method svcIds, svcDevice;
    static int[] deviceIds() {
        try {
            if (inputService == null) {
                Object b = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "input");
                inputService = Class.forName("android.hardware.input.IInputManager$Stub").getMethod("asInterface", android.os.IBinder.class).invoke(null, b);
                svcIds = inputService.getClass().getMethod("getInputDeviceIds");
                svcDevice = inputService.getClass().getMethod("getInputDevice", int.class);
            }
            return (int[]) svcIds.invoke(inputService);
        } catch (Exception e) { return InputDevice.getDeviceIds(); }
    }
    static InputDevice device(int id) {
        try { if (svcDevice != null) return (InputDevice) svcDevice.invoke(inputService, id); } catch (Exception ignored) { }
        return InputDevice.getDevice(id);
    }

    static int mouseId() {
        for (int id : deviceIds()) {
            InputDevice d = device(id);
            if (d != null && !d.isVirtual() && (d.getSources() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) return id;
        }
        return 0;
    }
    /** Left button press/release as a real mouse would send it: DOWN + BUTTON_PRESS, BUTTON_RELEASE + UP. */
    static void mouse(boolean down, int x, int y, long downTime) {
        try {
            MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties(); pp.id = 0; pp.toolType = MotionEvent.TOOL_TYPE_MOUSE;
            MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords(); pc.x = x; pc.y = y; pc.pressure = 1; pc.size = 1;
            int[] actions = down ? new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS} : new int[]{MotionEvent.ACTION_BUTTON_RELEASE, MotionEvent.ACTION_UP};
            for (int a : actions) {
                int buttons = down ? MotionEvent.BUTTON_PRIMARY : 0;
                MotionEvent e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), a, 1, new MotionEvent.PointerProperties[]{pp},
                        new MotionEvent.PointerCoords[]{pc}, 0, buttons, 1f, 1f, mouseId(), 0, InputDevice.SOURCE_MOUSE, 0);
                if (a == MotionEvent.ACTION_BUTTON_PRESS || a == MotionEvent.ACTION_BUTTON_RELEASE)
                    MotionEvent.class.getMethod("setActionButton", int.class).invoke(e, MotionEvent.BUTTON_PRIMARY);
                inject.invoke(im, e, 0);
                e.recycle();
            }
        } catch (Exception ex) { System.out.println("mouse inject failed: " + ex); }
    }

    static int keyboardId() {
        for (int id : deviceIds()) {
            InputDevice d = device(id);
            if (d != null && !d.isVirtual() && d.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) return id;
        }
        return KeyCharacterMap.VIRTUAL_KEYBOARD;
    }
    /** Linux scan codes (what a USB keyboard reports) for the keys the macro uses. */
    static int scan(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_W: return 17; case KeyEvent.KEYCODE_A: return 30; case KeyEvent.KEYCODE_S: return 31; case KeyEvent.KEYCODE_D: return 32;
            case KeyEvent.KEYCODE_E: return 18; case KeyEvent.KEYCODE_R: return 19; case KeyEvent.KEYCODE_SPACE: return 57; case KeyEvent.KEYCODE_ENTER: return 28;
            case KeyEvent.KEYCODE_ESCAPE: return 1; case KeyEvent.KEYCODE_SHIFT_LEFT: return 42; case KeyEvent.KEYCODE_COMMA: return 51; case KeyEvent.KEYCODE_PERIOD: return 52;
            case KeyEvent.KEYCODE_PAGE_UP: return 104; case KeyEvent.KEYCODE_PAGE_DOWN: return 109; case KeyEvent.KEYCODE_I: return 23; case KeyEvent.KEYCODE_O: return 24;
            default: return code >= KeyEvent.KEYCODE_1 && code <= KeyEvent.KEYCODE_9 ? 2 + (code - KeyEvent.KEYCODE_1) : 0;
        }
    }

    static void key(int action, int code, long downTime) {
        try {
            KeyEvent e = new KeyEvent(downTime, SystemClock.uptimeMillis(), action, code, 0, 0,
                    keyboardId(), scan(code), KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
            inject.invoke(im, e, 0);   // INJECT_INPUT_EVENT_MODE_ASYNC
        } catch (Exception ex) { System.out.println("inject failed: " + ex); }
    }
}
