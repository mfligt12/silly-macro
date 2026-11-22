package com.iipxp.beemacro;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.EnumSet;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The ONE input path for everything (navigation, farming, recovery, calibration) plus the floating overlay.
 * Android does not let a normal app inject key events into Roblox, so W/A/S/D are translated into touches on
 * Roblox's on-screen thumbstick via AccessibilityService.dispatchGesture (the supported API). Every method
 * is synchronized on {@link #lock}, so two systems can never drive input at the same time.
 */
public class MacroService extends AccessibilityService {
    public static volatile MacroService inst;
    final Object lock = new Object();
    final Handler ui = new Handler(Looper.getMainLooper());
    volatile String held = "none";
    volatile long heldSince;
    volatile boolean cancelled;
    private WindowManager wm;
    private View overlay;
    private WindowManager.LayoutParams lp;
    private TextView status, details, cal, temp;
    private long tempRead;

    /** Battery temperature (°C) plus Android's own thermal rating (whichever is worse), as one label and colour. */
    static String[] heat(double c, int thermal) {
        // thermal: PowerManager.THERMAL_STATUS_* (0 none, 1 light, 2 moderate, 3 severe, 4 critical, 5 emergency, 6 shutdown)
        int lvl = c >= 45 || thermal >= 4 ? 3 : c >= 40 || thermal == 3 ? 2 : c >= 35 || thermal == 2 ? 1 : 0;   // "light" (1) shows on this tablet even when cool (26.7°C): ignored
        String[] name = {"Good", "Warm", "Hot", "CRITICAL"}, color = {"#7CD992", "#F2D04B", "#F29B4B", "#FF5A5A"};
        return new String[]{String.format(java.util.Locale.US, "🌡 %.1f°C / %.1f°F · %s", c, c * 9 / 5 + 32, name[lvl]), color[lvl]};
    }
    private Button playBtn;
    private LinearLayout expandedBox, calBox, patternBox, fieldBox;
    private Runnable fieldShow;
    private Runnable patShow;

    @Override public void onCreate() { super.onCreate(); wm = getSystemService(WindowManager.class); Log.add("input: service created"); }
    @Override protected void onServiceConnected() { inst = this; Log.add("input: connected"); showOverlay(App.profile(this).overlayVisible); }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) { if (inst == null) { inst = this; Log.add("input: connected (late)"); showOverlay(App.profile(this).overlayVisible); } }
    @Override public void onInterrupt() { emergencyRelease(); }
    @Override public boolean onUnbind(Intent i) { Log.add("input: unbound"); emergencyRelease(); removeOverlay(); inst = null; return super.onUnbind(i); }
    @Override public void onDestroy() { Log.add("input: destroyed"); inst = null; removeOverlay(); super.onDestroy(); }

    // ------------------------------------------------------------------ input
    Point screen() { Point p = new Point(); wm.getDefaultDisplay().getRealSize(p); return p; }
    float px(float fx) { return fx * screen().x; }
    float py(float fy) { return fy * screen().y; }

    private boolean dispatch(GestureDescription g, long timeoutMs) {
        CountDownLatch done = new CountDownLatch(1);
        boolean[] ok = {false};
        boolean sent = dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) { ok[0] = true; done.countDown(); }
            @Override public void onCancelled(GestureDescription d) { done.countDown(); }
        }, null);
        if (!sent) return false;
        try { done.await(timeoutMs, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return ok[0];
    }

    /** For SHORT gestures only (taps, jump, camera swipes): if the point is under our panel, let touches pass through
     *  the panel for that gesture so the game gets it (otherwise the injected tap presses our own buttons).
     *  Long thumbstick holds never touch the panel, so the panel and its E-stop stay live during walks. */
    private boolean dispatchAt(GestureDescription g, long timeoutMs, float x, float y) {
        boolean hit = overlayHit(x, y);
        if (hit) passThrough(true);
        try { return dispatch(g, timeoutMs); } finally { if (hit) passThrough(false); }
    }
    private boolean overlayHit(float x, float y) {
        View o = overlay;
        if (o == null || !o.isAttachedToWindow()) return false;
        int[] at = new int[2]; o.getLocationOnScreen(at);
        return x >= at[0] && x < at[0] + o.getWidth() && y >= at[1] && y < at[1] + o.getHeight();
    }
    private void passThrough(boolean on) {
        CountDownLatch l = new CountDownLatch(1);
        ui.post(() -> {
            try {
                if (overlay != null && overlay.isAttachedToWindow()) {
                    if (on) lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; else lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    wm.updateViewLayout(overlay, lp);
                }
            } finally { l.countDown(); }
        });
        try { l.await(500, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
        if (on) SystemClock.sleep(50);   // let the window flag reach the input dispatcher
    }

    /** Hold a key combination for ms (key down -> held -> key up). Diagonals = normalized full thumbstick deflection. */
    boolean keyboard() { return App.profile(this).keyboard(); }

    /** Android key codes for the macro's keys. */
    static int code(Moves.Key k) {
        switch (k) { case W: return Keys.W; case A: return Keys.A; case S: return Keys.S; case D: return Keys.D; case SPACE: return Keys.SPACE; default: return Keys.SHIFT; }
    }
    /** Keyboard mode: press keys, wait (or until stop), release. Released keys on any failure (server also releases
     *  everything if the connection drops). */
    private boolean kbHold(EnumSet<Moves.Key> keys, long maxMs, java.util.function.BooleanSupplier stop) {
        if (!Keys.connect(this)) { Log.add("keys: " + Keys.status); return false; }
        setHeld(keys + " (keyboard)");
        boolean hit = false;
        try {
            for (Moves.Key k : keys) if (!Keys.down(code(k))) return false;
            long end = SystemClock.uptimeMillis() + maxMs;
            while (!cancelled && SystemClock.uptimeMillis() < end) {
                if (stop != null && (hit = stop.getAsBoolean())) break;
                SystemClock.sleep(Math.min(20, Math.max(1, end - SystemClock.uptimeMillis())));
            }
            return stop == null ? !cancelled : hit && !cancelled;
        } finally {
            for (Moves.Key k : keys) Keys.up(code(k));
            setHeld("none");
        }
    }
    /** Replays a recorded keyboard route: {ms, code, down?} events at their recorded times. Any key still down at the
     *  end, on cancel or on error is released. */
    public boolean keyScript(int[][] ev) {
        synchronized (lock) {
            if (cancelled || !Keys.connect(this)) { Log.add("keys: " + Keys.status); return false; }
            java.util.Set<Integer> down = new java.util.HashSet<>();
            long t0 = SystemClock.uptimeMillis();
            setHeld("keyboard route");
            try {
                for (int[] e : ev) {
                    long at = t0 + e[0];
                    while (!cancelled && SystemClock.uptimeMillis() < at) SystemClock.sleep(Math.min(5, Math.max(1, at - SystemClock.uptimeMillis())));
                    if (cancelled) return false;
                    boolean ok = e[2] == 1 ? Keys.down(e[1]) : Keys.up(e[1]);
                    if (e[2] == 1) down.add(e[1]); else down.remove(e[1]);
                    if (!ok) return false;
                }
                return true;
            } finally { for (int c : down) Keys.up(c); setHeld("none"); }
        }
    }


    private boolean keepAwake;
    /** Simulated keys don't count as user activity, so the screen stays on through this window while a run is active. */
    private void setKeepAwake(boolean on) {
        if (on == keepAwake || overlay == null || !overlay.isAttachedToWindow()) return;
        keepAwake = on;
        if (on) lp.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON; else lp.flags &= ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        wm.updateViewLayout(overlay, lp);
    }

    /** While gathering: repeated left clicks so the tool keeps swinging. */
    /** The tool should be clicking (farming): kept so a new key sender connection turns it back on. */
    private volatile boolean toolWanted; private volatile int toolPx, toolPy;
    public boolean toolHold(boolean on) {
        Profile p = App.profile(this);
        android.graphics.Point z = screen();
        int x = Math.round(p.toolX * Math.max(z.x, z.y)), y = Math.round(p.toolY * Math.min(z.x, z.y));
        toolWanted = on; toolPx = x; toolPy = y;   // before connecting: a reconnect must not turn it back on when switching off
        Keys.onReconnect = () -> {   // after a pause / release, clicking resumes as soon as the macro talks to the key sender again
            Engine e = Engine.inst;
            if (toolWanted && e != null && !e.finished() && !e.paused) { boolean k = Keys.autoClick(true, toolPx, toolPy); Log.add("tool: auto-click back on after reconnect" + (k ? "" : " (FAILED)")); }
        };
        if (!keyboard() || !Keys.connect(this)) return false;
        boolean ok = Keys.autoClick(on, x, y);
        Log.add("tool: auto-click " + (on ? "on" : "off") + (ok ? "" : " (FAILED)"));
        return ok;
    }

    /** One key press (UI actions in keyboard mode). */
    public boolean key(int code, long ms) {
        synchronized (lock) {
            if (!Keys.connect(this)) { Log.add("keys: " + Keys.status); return false; }
            return Keys.tap(code, ms);
        }
    }
    /** Hotbar slot 1-7: number key in keyboard mode, otherwise a tap at the S23 hotbar spot. */
    public boolean hotbar(int slot, float fx, float fy) { return keyboard() ? key(android.view.KeyEvent.KEYCODE_1 + slot - 1, 60) : tap(fx, fy); }

    public boolean hold(EnumSet<Moves.Key> keys, long ms) {
        if (keyboard()) { synchronized (lock) { if (cancelled) return false; return kbHold(keys, ms, null); } }
        synchronized (lock) {
            if (cancelled) return false;
            Profile p = App.profile(this);
            float dx = (keys.contains(Moves.Key.D) ? 1 : 0) - (keys.contains(Moves.Key.A) ? 1 : 0);
            float dy = (keys.contains(Moves.Key.S) ? 1 : 0) - (keys.contains(Moves.Key.W) ? 1 : 0);
            boolean jump = keys.contains(Moves.Key.SPACE);
            setHeld(keys.toString());
            try {
                if (dx == 0 && dy == 0) {
                    if (jump) tap(p.jumpX, p.jumpY);
                    sleepCancellable(ms);
                    return !cancelled;
                }
                float[] t = stickTarget(keys), st = pressStart(t);
                float tx = t[0], ty = t[1];
                Path p1 = new Path(); p1.moveTo(st[0], st[1]); p1.lineTo(tx, ty);
                GestureDescription.StrokeDescription s1 = new GestureDescription.StrokeDescription(p1, 0, 40, true);
                GestureDescription.Builder b = new GestureDescription.Builder().addStroke(s1);
                if (jump) { Path j = new Path(); j.moveTo(px(p.jumpX), py(p.jumpY)); b.addStroke(new GestureDescription.StrokeDescription(j, 0, 60)); }
                boolean sent = jump ? dispatchAt(b.build(), 2000, px(p.jumpX), py(p.jumpY)) : dispatch(b.build(), 2000);
                if (!sent || cancelled) return false;
                long rest = Math.max(1, ms - 40);
                Path p2 = new Path(); p2.moveTo(tx, ty); p2.lineTo(tx + 0.5f, ty);
                GestureDescription.StrokeDescription s2 = s1.continueStroke(p2, 0, rest, false);
                return dispatch(new GestureDescription.Builder().addStroke(s2).build(), rest + 3000) && !cancelled;
            } finally { setHeld("none"); }
        }
    }

    /** Where the finger must end up for these keys. D-pad: the arrow cell (a corner cell for diagonals, verified live);
     *  thumbstick: full deflection from the stick centre (diagonals normalized). */
    float[] stickTarget(EnumSet<Moves.Key> keys) {
        Profile p = App.profile(this);
        float dx = (keys.contains(Moves.Key.D) ? 1 : 0) - (keys.contains(Moves.Key.A) ? 1 : 0);
        float dy = (keys.contains(Moves.Key.S) ? 1 : 0) - (keys.contains(Moves.Key.W) ? 1 : 0);
        if (p.dpad) { float c = p.padCell * screen().y; return new float[]{px(p.padX) + dx * c, py(p.padY) + dy * c}; }
        float len = (float) Math.max(1e-6, Math.hypot(dx, dy)), r = (dx == 0 && dy == 0) ? 0 : p.joyR * screen().y;
        return new float[]{px(p.joyX) + dx / len * r, py(p.joyY) + dy / len * r};
    }

    /** Where the finger first touches down: on the D-pad you press the arrow itself; the thumbstick is grabbed at its
     *  centre and dragged. */
    float[] pressStart(float[] target) {
        Profile p = App.profile(this);
        return p.dpad ? new float[]{target[0] - 0.5f, target[1]} : new float[]{px(p.joyX), py(p.joyY)};
    }
    static EnumSet<Moves.Key> set(Moves.Key[] ks) { EnumSet<Moves.Key> s = EnumSet.noneOf(Moves.Key.class); if (ks != null) for (Moves.Key k : ks) s.add(k); return s; }

    /** Hold keys until stop() is true (checked every ~150 ms) or maxMs passes. Returns true if stop() fired. */
    public boolean holdUntil(EnumSet<Moves.Key> keys, long maxMs, java.util.function.BooleanSupplier stop) {
        if (keyboard()) { synchronized (lock) { if (cancelled) return false; return kbHold(keys, maxMs, stop); } }
        synchronized (lock) {
            if (cancelled) return false;
            float[] t = stickTarget(keys);
            Profile p = App.profile(this);
            setHeld(keys + " until…");
            try {
                float[] st = pressStart(t);
                Path p1 = new Path(); p1.moveTo(st[0], st[1]); p1.lineTo(t[0], t[1]);
                GestureDescription.StrokeDescription s = new GestureDescription.StrokeDescription(p1, 0, 40, true);
                if (!dispatch(new GestureDescription.Builder().addStroke(s).build(), 2000)) return false;
                // each continuation must start exactly where the previous one ended: wiggle between t and t+0.5
                long end = SystemClock.uptimeMillis() + maxMs; boolean hit = false; float at = t[0];
                while (!cancelled && SystemClock.uptimeMillis() < end && !(hit = stop.getAsBoolean())) {
                    float next = at == t[0] ? t[0] + 0.5f : t[0];
                    Path h = new Path(); h.moveTo(at, t[1]); h.lineTo(next, t[1]); at = next;
                    s = s.continueStroke(h, 0, 150, true);
                    if (!dispatch(new GestureDescription.Builder().addStroke(s).build(), 3000)) return false;
                }
                float next = at == t[0] ? t[0] + 0.5f : t[0];
                Path up = new Path(); up.moveTo(at, t[1]); up.lineTo(next, t[1]);
                dispatch(new GestureDescription.Builder().addStroke(s.continueStroke(up, 0, 10, false)).build(), 2000);
                return hit && !cancelled;
            } finally { setHeld("none"); }
        }
    }

    /** Replays a route recorded from the user's own touches (RecordedRoutes): every finger's path and timing, scaled
     *  to this screen. Played in 250 ms windows; a finger still down at a window's end continues (stays pressed) into
     *  the next window, so held sticks never lift. Returns false if cancelled or a window was rejected. */
    public boolean replay(int[][] strokes) { return replay(strokes, () -> false); }

    /** Same, but stops early (all fingers lifted) as soon as stopWhen() is true — checked between 250 ms slices. */
    public boolean replay(int[][] strokes, java.util.function.BooleanSupplier stopWhen) {
        synchronized (lock) {
            if (cancelled) return false;
            final int WIN = 250;
            float sx = screen().x / 2340f, sy = screen().y / 1080f;
            int endAll = 0;
            for (int[] s : strokes) endAll = Math.max(endAll, s[0] + s[s.length - 1]);
            // The expanded panel covers spots the recorded route touches (camera swipes, glider taps); the compact
            // panel does not, and keeps ⛔ reachable. Collapse before replaying.
            CountDownLatch collapsed = new CountDownLatch(1);
            ui.post(() -> { if (expandedBox != null) expandedBox.setVisibility(View.GONE); collapsed.countDown(); });
            try { collapsed.await(500, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
            SystemClock.sleep(150);
            GestureDescription.StrokeDescription[] prev = new GestureDescription.StrokeDescription[strokes.length];
            float[][] lastEnd = new float[strokes.length][];
            long t0 = SystemClock.uptimeMillis();
            setHeld("replaying recorded route");
            try {
                for (int w0 = 0; w0 <= endAll; w0 += WIN) {
                    if (cancelled) return false;
                    if (w0 > 0 && stopWhen.getAsBoolean()) {   // e.g. the cannon prompt is up: lift every finger, done
                        Path up = new Path(); up.moveTo(px(App.profile(this).idleX), py(App.profile(this).idleY));
                        dispatch(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(up, 0, 1)).build(), 1000);
                        Log.add("replay: stopped early at " + w0 + " ms (target reached)");
                        return true;
                    }
                    int w1 = w0 + WIN;
                    GestureDescription.Builder b = new GestureDescription.Builder();
                    java.util.List<Integer> used = new java.util.ArrayList<>();
                    GestureDescription.StrokeDescription[] made = new GestureDescription.StrokeDescription[strokes.length];
                    for (int i = 0; i < strokes.length; i++) {
                        int[] s = strokes[i];
                        int start = s[0], end = start + s[s.length - 1];
                        if (end < w0 || start >= w1 || (prev[i] == null && start < w0)) continue;
                        int a = Math.max(start, w0), z = Math.min(end, w1);
                        boolean cont = end > w1;
                        Path path = new Path();
                        float[] p0 = prev[i] != null ? lastEnd[i] : Moves.at(s, a - start, sx, sy);
                        path.moveTo(p0[0], p0[1]);
                        float[] last = p0;
                        for (int k = 1; k + 2 < s.length; k += 3) {
                            int tt = start + s[k + 2];
                            if (tt > a && tt < z) { last = new float[]{s[k] * sx, s[k + 1] * sy}; path.lineTo(last[0], last[1]); }
                        }
                        float[] pz = Moves.at(s, z - start, sx, sy);
                        if (z > a || prev[i] != null) {
                            // A (nearly) still finger still needs a real path: Android rejected some sub-pixel
                            // continuation slices live. If this slice moves < 2 px, detour 1.5 px sideways and back
                            // (negligible on a ~100 px thumbstick); the end point stays exactly where it should be.
                            if (Math.hypot(pz[0] - p0[0], pz[1] - p0[1]) < 2f && last == p0) {
                                float my = (p0[1] + pz[1]) / 2 + (p0[1] > 3 ? -1.5f : 1.5f);
                                path.lineTo((p0[0] + pz[0]) / 2, my);
                            }
                            if (Math.abs(pz[0] - last[0]) < 0.01f && Math.abs(pz[1] - last[1]) < 0.01f) pz = new float[]{pz[0] + 0.5f, pz[1]};
                            path.lineTo(pz[0], pz[1]); last = pz;
                        }
                        long dur = Math.max(1, z - a);
                        made[i] = prev[i] != null ? prev[i].continueStroke(path, 0, dur, cont)
                                                  : new GestureDescription.StrokeDescription(path, a - w0, dur, cont);
                        lastEnd[i] = last;
                        b.addStroke(made[i]); used.add(i);
                    }
                    if (!used.isEmpty() && !dispatch(b.build(), WIN + 3000)) {
                        if (cancelled) return false;
                        // Rebuild the slice with fresh touches. A held finger restarts at its ORIGINAL touch-down
                        // point and slides to where it should be (the dynamic thumbstick centres on the first touch).
                        Log.add("replay: window at " + w0 + " ms rejected — retrying with re-pressed fingers");
                        GestureDescription.Builder rb = new GestureDescription.Builder();
                        long now = SystemClock.uptimeMillis() - t0;
                        int left = (int) Math.max(40, w1 - now);
                        for (int i : used) {
                            int[] s = strokes[i];
                            boolean cont = s[0] + s[s.length - 1] > w1;
                            Path rp = new Path(); rp.moveTo(s[1] * sx, s[2] * sy); rp.lineTo(lastEnd[i][0], lastEnd[i][1] + 0.5f);
                            made[i] = new GestureDescription.StrokeDescription(rp, 0, left, cont);
                            lastEnd[i] = new float[]{lastEnd[i][0], lastEnd[i][1] + 0.5f};
                            rb.addStroke(made[i]);
                        }
                        if (!dispatch(rb.build(), left + 3000)) { Log.add("replay: retry at " + w0 + " ms rejected too"); return false; }
                    }
                    for (int i = 0; i < strokes.length; i++) prev[i] = made[i] != null && strokes[i][0] + strokes[i][strokes[i].length - 1] > w1 ? made[i] : null;
                    long wait = t0 + w1 - SystemClock.uptimeMillis();
                    if (wait > 0) SystemClock.sleep(wait);
                }
                return !cancelled;
            } finally { setHeld("none"); }
        }
    }


    /** Natro RotLeft/RotRight: 45° per count. Measured: swiping right turns the camera left. */
    public boolean rotate(int count) {
        if (keyboard()) {   // Roblox: "," turns the camera 45° left, "." 45° right (measured on the tablet)
            synchronized (lock) {
                for (int i = 0; i < Math.abs(count) && !cancelled; i++) { if (!Keys.connect(this) || !Keys.tap(count > 0 ? Keys.PERIOD : Keys.COMMA, 50)) return false; SystemClock.sleep(120); }
                return !cancelled;
            }
        }
        synchronized (lock) {
            Profile p = App.profile(this);
            setHeld(count > 0 ? "Rot→ " + count : "Rot← " + -count);
            try {
                int left = Math.abs(count);
                while (left > 0 && !cancelled) {
                    int n = Math.min(2, left); left -= n;
                    float d = n * p.pxPer45 * ((count > 0) != p.yawInvert ? -1 : 1);
                    if (!swipe(px(p.camX) - d / 2, py(p.camY), px(p.camX) + d / 2, py(p.camY), 90L * n + 60)) return false;
                    SystemClock.sleep(60);
                }
                return !cancelled;
            } finally { setHeld("none"); }
        }
    }

    /** Natro RotUp/RotDown (camera pitch). Pattern uses Up 4 ... Down 4, so it nets to zero. */
    public boolean tilt(int count) {
        if (keyboard()) {   // PageUp / PageDown tilt the camera one step per press
            synchronized (lock) {
                for (int i = 0; i < Math.abs(count) && !cancelled; i++) { if (!Keys.connect(this) || !Keys.tap(count > 0 ? Keys.PGUP : Keys.PGDN, 50)) return false; SystemClock.sleep(100); }
                return !cancelled;
            }
        }
        synchronized (lock) {
            Profile p = App.profile(this);
            float d = count * p.tiltPx;
            setHeld("Tilt " + count);
            try { return swipe(px(p.camX), py(p.camY) - d / 2, px(p.camX), py(p.camY) + d / 2, 150); } finally { setHeld("none"); }
        }
    }

    boolean swipe(float x0, float y0, float x1, float y1, long ms) {
        Path path = new Path(); path.moveTo(x0, y0); path.lineTo(x1, y1);
        return dispatchAt(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path, 0, ms)).build(), ms + 2000, x0, y0);
    }

    public boolean tap(float fx, float fy) {
        synchronized (lock) {
            Path path = new Path(); path.moveTo(px(fx), py(fy));
            return dispatchAt(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path, 0, 60)).build(), 2000, px(fx), py(fy));
        }
    }

    /** Called from ANY thread (e-stop, pause, errors). Dispatching a new gesture cancels the held one;
     *  we touch an empty spot with no UI (bottom-left, below the D-pad), so nothing else gets pressed. */
    public void emergencyRelease() {
        cancelled = true;
        Keys.releaseAll();   // key sender lifts every held key when the connection drops
        if (keyboard()) { setHeld("none (released)"); return; }   // no touch: it would switch Roblox to mobile controls
        try {
            Profile p = App.profile(this);
            Path path = new Path(); path.moveTo(px(p.idleX), py(p.idleY));   // a spot with no UI: cancels any held touch
            dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path, 0, 1)).build(), null, null);
        } catch (Exception ignored) { }
        setHeld("none (released)");
    }
    public void arm() { cancelled = false; }

    private void sleepCancellable(long ms) { long end = SystemClock.uptimeMillis() + ms; while (!cancelled && SystemClock.uptimeMillis() < end) SystemClock.sleep(20); }
    private void setHeld(String s) { held = s; heldSince = SystemClock.uptimeMillis(); }

    public String inputState() {
        return held.startsWith("none") ? held : String.format(Locale.US, "%s %.1fs", held, (SystemClock.uptimeMillis() - heldSince) / 1000f);
    }

    // ------------------------------------------------------------------ overlay
    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    public void showOverlay(boolean show) {
        ui.post(() -> {
            if (!show) {
                if (overlay == null || !motion()) { removeOverlay(); return; }
                View o = overlay;
                o.setPivotX(lp.x < screen().x / 2 ? 0 : o.getWidth()); o.setPivotY(0);
                o.animate().alpha(0).scaleX(0.95f).scaleY(0.95f).setDuration(160).setInterpolator(EASE_OUT).withEndAction(() -> { if (overlay == o) removeOverlay(); }).start();
                return;
            }
            if (overlay != null) return;
            buildOverlay();
            if (motion()) { overlay.setAlpha(0); overlay.setScaleX(0.95f); overlay.setScaleY(0.95f); }
            try { wm.addView(overlay, lp); }
            catch (Exception e) { Log.add("overlay: couldn't show the floating panel: " + e); overlay = null; return; }
            if (motion()) overlay.post(() -> {   // grows out of the corner it is docked to
                if (overlay == null) return;
                overlay.setPivotX(lp.x < screen().x / 2 ? 0 : overlay.getWidth()); overlay.setPivotY(0);
                overlay.animate().alpha(App.profile(this).overlayAlpha).scaleX(1).scaleY(1).setDuration(220).setInterpolator(EASE_OUT).start();   // ends at your panel transparency
            });
            ui.post(tick);
        });
    }
    void removeOverlay() { if (overlay != null) { try { wm.removeView(overlay); } catch (Exception ignored) { } overlay = null; ui.removeCallbacks(tick); } }
    public boolean overlayShown() { return overlay != null; }

    private void buildOverlay() {
        Profile p = App.profile(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(6), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xF0201C2B, 0xF0161A26});
        bg.setCornerRadius(dp(14)); bg.setStroke(dp(1), 0x667C5CFF);
        root.setBackground(bg);
        root.setAlpha(p.overlayAlpha);

        LinearLayout head = row();
        TextView title = text("🐝 Silly Macro", 12, 0xFFB9A8FF); title.setTypeface(null, android.graphics.Typeface.BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button exp = small("▢", 0xFF2A2F45); exp.setOnClickListener(v -> { p.overlayExpanded = !p.overlayExpanded; App.save(this); if (p.overlayExpanded) reveal(expandedBox); else conceal(expandedBox, null); });
        Button min = small("–", 0xFF2A2F45);
        Button close = small("✕", 0xFF2A2F45);
        Button miniStop = small("⛔", 0xFFD83A4A);
        miniStop.setOnClickListener(v -> { emergencyRelease(); if (Engine.inst != null) Engine.inst.stop("EMERGENCY STOP"); });
        close.setOnClickListener(v -> {
            if (Engine.inst != null && !Engine.inst.finished()) { toast("Macro is running — use – to shrink the panel instead, so the emergency stop stays on screen"); return; }
            p.overlayVisible = false; App.save(this); removeOverlay();
            toast("Panel hidden. Turn it back on in Silly Macro › Settings › Show floating overlay");
        });
        head.addView(exp); head.addView(min); head.addView(close); head.addView(miniStop);
        root.addView(head);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        root.addView(body);
        // Minimized = a small bee bubble with the emergency stop; tap – / 🐝 again to restore.
        Runnable applyMin = () -> {
            boolean m = p.overlayMinimized;
            body.setVisibility(m ? View.GONE : View.VISIBLE);
            exp.setVisibility(m ? View.GONE : View.VISIBLE); close.setVisibility(m ? View.GONE : View.VISIBLE);
            miniStop.setVisibility(m ? View.VISIBLE : View.GONE);
            min.setText(m ? "+" : "–");
            title.setText(m ? "🐝" : "🐝 Silly Macro");
            title.setLayoutParams(m ? new LinearLayout.LayoutParams(-2, -2) : new LinearLayout.LayoutParams(0, -2, 1));
            if (lp != null) { lp.width = m ? WindowManager.LayoutParams.WRAP_CONTENT : dp(p.overlayWidthDp); if (overlay != null && overlay.isAttachedToWindow()) wm.updateViewLayout(overlay, lp); }
        };
        min.setOnClickListener(v -> {
            p.overlayMinimized = !p.overlayMinimized; App.save(this);
            if (p.overlayMinimized) conceal(body, () -> { applyMin.run(); settle(); });   // content fades, then the panel folds to the bubble
            else { applyMin.run(); reveal(body); settle(); }                          // panel opens, then the content fades in
        });
        LinearLayout panel = root;
        temp = text("🌡 …", 11, 0xFFB8BCD0);
        temp.setVisibility(p.showTemp ? View.VISIBLE : View.GONE);
        panel.addView(temp);   // after the body: stays visible when the panel is minimized
        root = body;   // everything below goes inside the collapsible body
        status = text("Idle", 12, 0xFFE8E8F0);
        root.addView(status);

        LinearLayout btns = row();
        playBtn = small("▶ Start", 0xFF5B47E0);
        playBtn.setOnClickListener(v -> {
            Engine e = Engine.inst;
            if (e == null || e.finished()) {
                // collapse first so the panel can't cover the backpack bar / hive prompt the macro has to read
                p.overlayExpanded = false; App.save(this); expandedBox.setVisibility(View.GONE);
                if (patternBox != null) { patternBox.setVisibility(View.GONE); patShow.run(); }   // keep the game HUD clear
                if (fieldBox != null) { fieldBox.setVisibility(View.GONE); fieldShow.run(); }
                // off the UI thread: preflight waits for a gesture callback, which is delivered on the UI thread
                new Thread(() -> { SystemClock.sleep(400); String why = Engine.start(this); if (why != null) { startWhy = why; startWhyAt = SystemClock.uptimeMillis(); toast(why); } }, "preflight").start();
            }
            else if (e.paused) e.resume(); else e.pause("paused by you");
        });
        Button stop = small("■ Stop", 0xFF2E6BD8);
        stop.setOnClickListener(v -> { if (Engine.inst != null) Engine.inst.stop("stopped by you"); emergencyRelease(); });
        Button estop = small("⛔", 0xFFD83A4A);
        estop.setOnClickListener(v -> { emergencyRelease(); if (Engine.inst != null) Engine.inst.stop("EMERGENCY STOP"); });
        btns.addView(playBtn, new LinearLayout.LayoutParams(0, dp(36), 1.3f));
        btns.addView(stop, new LinearLayout.LayoutParams(0, dp(36), 1));
        btns.addView(estop, new LinearLayout.LayoutParams(dp(44), dp(36)));
        root.addView(btns);

        // ---- field tab: which field to farm (Natro walking routes), right above the pattern tab
        Button fieldTab = small("", 0xFF3A3350);
        fieldTab.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(32)));
        fieldBox = new LinearLayout(this); fieldBox.setOrientation(LinearLayout.VERTICAL);
        Button fPrev = small("◀", 0xFF2A2F45), fName = small("", 0xFF5B47E0), fNext = small("▶", 0xFF2A2F45);
        Button fDefault = small("", 0xFF2A2F45);
        TextView fNote = text("", 10, 0xFFB8BCD0);
        fieldShow = () -> {
            int i = Routes.index(p.field); String[] d = Routes.DEFAULTS[i];
            fieldTab.setText("📍 Field: " + Routes.name(p.field) + (fieldBox.getVisibility() == View.VISIBLE ? "  ▾" : "  ▸"));
            fName.setText(Routes.name(p.field));
            fDefault.setText("Use Natro's pattern here: " + d[0] + " " + d[1] + " ×" + d[2]);
            fNote.setText((p.field.equals("pinetree") ? "Live-tested route." : "Natro's walk route, not tried on this tablet yet.")
                    + (Routes.blueField(p.field) ? " Field guard on (blue/white flowers)." : " Field colour checks off here; stuck/lag and no-pollen resets still on.")
                    + " Used from the next trip.");
        };
        Runnable fieldSave = () -> { App.save(this); fieldShow.run(); if (patShow != null) patShow.run(); Log.add("overlay: field set to " + Routes.name(p.field)); };
        fPrev.setOnClickListener(v -> { int i = Routes.index(p.field); p.field = Routes.FIELDS[(i - 1 + Routes.FIELDS.length) % Routes.FIELDS.length][0]; fieldSave.run(); });
        fNext.setOnClickListener(v -> { int i = Routes.index(p.field); p.field = Routes.FIELDS[(i + 1) % Routes.FIELDS.length][0]; fieldSave.run(); });
        fName.setOnClickListener(v -> fNext.performClick());
        fDefault.setOnClickListener(v -> { String[] d = Routes.DEFAULTS[Routes.index(p.field)]; p.pattern = d[0]; p.patternSize = d[1]; p.patternWidth = Integer.parseInt(d[2]); fieldSave.run(); });
        LinearLayout f1 = row();
        f1.addView(fPrev, new LinearLayout.LayoutParams(dp(40), dp(34))); f1.addView(fName, new LinearLayout.LayoutParams(0, dp(34), 1)); f1.addView(fNext, new LinearLayout.LayoutParams(dp(40), dp(34)));
        fieldBox.addView(f1);
        fieldBox.addView(fDefault, new LinearLayout.LayoutParams(-1, dp(34)));
        fieldBox.addView(fNote);
        fieldBox.setVisibility(View.GONE);
        fieldTab.setOnClickListener(v -> { if (fieldBox.getVisibility() == View.VISIBLE) conceal(fieldBox, fieldShow); else { reveal(fieldBox); fieldShow.run(); } });
        fieldShow.run();
        root.addView(fieldTab);
        root.addView(fieldBox);

        // ---- pattern tab: pick the farming pattern from the overlay (used from the next field run on)
        String[] pats = new String[Patterns.NAMES.length + 1]; pats[0] = "Skillet"; System.arraycopy(Patterns.NAMES, 0, pats, 1, Patterns.NAMES.length);
        Button patTab = small("", 0xFF3A3350);
        patTab.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(32)));
        patternBox = new LinearLayout(this); patternBox.setOrientation(LinearLayout.VERTICAL);
        Button pPrev = small("◀", 0xFF2A2F45), pName = small("", 0xFF5B47E0), pNext = small("▶", 0xFF2A2F45);
        Button pSize = small("", 0xFF2A2F45), wMinus = small("−", 0xFF2A2F45), wPlus = small("+", 0xFF2A2F45);
        TextView wVal = text("", 12, 0xFFE8E8F0);
        Button invFB = small("", 0xFF2A2F45), invLR = small("", 0xFF2A2F45);
        TextView pNote = text("", 10, 0xFFB8BCD0);
        patShow = () -> {
            boolean natro = !p.pattern.equals("Skillet");
            patTab.setText("🧭 Pattern: " + p.pattern + (natro ? " " + p.patternSize + " ×" + p.patternWidth : "") + (patternBox.getVisibility() == View.VISIBLE ? "  ▾" : "  ▸"));
            pName.setText(p.pattern);
            pSize.setText("Size " + p.patternSize); wVal.setText("  width " + p.patternWidth + "  ");
            invFB.setText("⇅ " + (p.patternInvertFB ? "inverted" : "normal")); invLR.setText("⇄ " + (p.patternInvertLR ? "inverted" : "normal"));
            // Skillet has fixed measurements: no size, width or inversion to set, so those rows go away
            ((View) pSize.getParent()).setVisibility(natro ? View.VISIBLE : View.GONE);
            ((View) invFB.getParent()).setVisibility(natro ? View.VISIBLE : View.GONE);
            pNote.setText(natro ? "Natro Macro pattern · used from the next field run" : "Skillet · sprinkler Upper Left 7, camera Right ×4 · used from the next field run");
        };
        Runnable patSave = () -> { App.save(this); patShow.run(); Log.add("overlay: pattern set to " + p.pattern + " " + p.patternSize + " ×" + p.patternWidth); };
        pPrev.setOnClickListener(v -> { int i = java.util.Arrays.asList(pats).indexOf(p.pattern); p.pattern = pats[(i - 1 + pats.length) % pats.length]; patSave.run(); });
        pNext.setOnClickListener(v -> { int i = java.util.Arrays.asList(pats).indexOf(p.pattern); p.pattern = pats[(i + 1) % pats.length]; patSave.run(); });
        pName.setOnClickListener(v -> pNext.performClick());
        pSize.setOnClickListener(v -> { int i = java.util.Arrays.asList(Patterns.SIZES).indexOf(p.patternSize); p.patternSize = Patterns.SIZES[(i + 1) % Patterns.SIZES.length]; patSave.run(); });
        wMinus.setOnClickListener(v -> { p.patternWidth = Math.max(1, p.patternWidth - 1); patSave.run(); });
        wPlus.setOnClickListener(v -> { p.patternWidth = Math.min(9, p.patternWidth + 1); patSave.run(); });
        invFB.setOnClickListener(v -> { p.patternInvertFB = !p.patternInvertFB; patSave.run(); });
        invLR.setOnClickListener(v -> { p.patternInvertLR = !p.patternInvertLR; patSave.run(); });
        LinearLayout r1 = row();
        r1.addView(pPrev, new LinearLayout.LayoutParams(dp(40), dp(34))); r1.addView(pName, new LinearLayout.LayoutParams(0, dp(34), 1)); r1.addView(pNext, new LinearLayout.LayoutParams(dp(40), dp(34)));
        LinearLayout r2 = row();
        r2.addView(pSize, new LinearLayout.LayoutParams(0, dp(34), 1)); r2.addView(wMinus, new LinearLayout.LayoutParams(dp(36), dp(34))); r2.addView(wVal); r2.addView(wPlus, new LinearLayout.LayoutParams(dp(36), dp(34)));
        LinearLayout r3 = row();
        r3.addView(invFB, new LinearLayout.LayoutParams(0, dp(34), 1)); r3.addView(invLR, new LinearLayout.LayoutParams(0, dp(34), 1));
        patternBox.addView(r1); patternBox.addView(r2); patternBox.addView(r3); patternBox.addView(pNote);
        patternBox.setVisibility(View.GONE);
        patTab.setOnClickListener(v -> {
            if (patternBox.getVisibility() == View.VISIBLE) conceal(patternBox, patShow); else { reveal(patternBox); patShow.run(); }
        });
        patShow.run();
        root.addView(patTab);
        root.addView(patternBox);

        expandedBox = new LinearLayout(this); expandedBox.setOrientation(LinearLayout.VERTICAL);
        details = text("", 11, 0xFFB8BCD0);
        expandedBox.addView(details);
        Button capOff = small("Stop screen capture", 0xFF2A2F45);
        capOff.setOnClickListener(v -> {
            if (Engine.inst != null && !Engine.inst.finished()) Engine.inst.stop("screen capture stopped");
            emergencyRelease();
            if (CaptureService.inst != null) { CaptureService.stopCapture(this); toast("Screen capture stopped"); } else toast("Screen capture is not running");
        });
        expandedBox.addView(capOff);
        Button calT = small("Calibration keypad", 0xFF2A2F45);
        calT.setOnClickListener(v -> {
            boolean open = calBox.getVisibility() != View.VISIBLE;   // keypad replaces the details so the panel fits on screen
            calBox.setVisibility(open ? View.VISIBLE : View.GONE);
            details.setVisibility(open ? View.GONE : View.VISIBLE);
            capOff.setVisibility(open ? View.GONE : View.VISIBLE);
        });
        expandedBox.addView(calT);
        calBox = new LinearLayout(this); calBox.setOrientation(LinearLayout.VERTICAL); calBox.setVisibility(View.GONE);
        cal = text("", 11, 0xFF8FD3FF);
        calBox.addView(cal);
        String[][] pad = {{"W+A", "W", "W+D", "Rot←"}, {"A", "SPACE", "D", "Rot→"}, {"S+A", "S", "S+D", "360°"}, {"Tilt↑", "Tilt↓", "W+SPACE"}, {"Route→Field", "Route→Hive"}};
        for (String[] r : pad) {
            LinearLayout rr = row();
            for (String k : r) {
                Button bt = small(k, 0xFF262B3D); bt.setTextSize(11); bt.setOnClickListener(v -> calibrate(k));
                LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(0, dp(30), 1); kl.setMargins(dp(1), dp(1), dp(1), dp(1));
                rr.addView(bt, kl);
            }
            calBox.addView(rr);
        }
        expandedBox.addView(calBox);
        expandedBox.setVisibility(p.overlayExpanded ? View.VISIBLE : View.GONE);
        root.addView(expandedBox);

        TextView grip = text("⇲", 14, 0x887C5CFF); grip.setGravity(Gravity.END);
        root.addView(grip);

        lp = new WindowManager.LayoutParams(dp(p.overlayWidthDp), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        place();

        title.setOnTouchListener(new Drag(false));
        grip.setOnTouchListener(new Drag(true));
        overlay = panel;
        applyMin.run();
    }

    /** Default dock: right edge, below the Honey/Pollen HUD and hive prompt (y≈290 px on the S23), clear of the
     *  thumbstick and camera-swipe zone. A dragged position is kept but clamped onto the current screen. */
    void place() {
        Profile p = App.profile(this);
        Point s = screen();
        int w = lp.width > 0 ? lp.width : dp(80);
        lp.x = p.overlayX >= 0 ? Math.min(p.overlayX, s.x - w) : s.x - w - dp(8);
        lp.y = Math.max(0, Math.min(p.overlayY, s.y - dp(60)));
        if (s.x > s.y) {   // in the game: never over the backpack bar, hive prompt or the menu's Resume button
            lp.x = Math.max(lp.x, Math.round(0.705f * s.x));
            lp.y = Math.max(lp.y, Math.round(0.18f * s.y));
        }
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (overlay != null && overlay.isAttachedToWindow()) { place(); wm.updateViewLayout(overlay, lp); }
    }

    /** Drag the title to move; drag ⇲ to resize width. Saved to the profile. */
    class Drag implements View.OnTouchListener {
        final boolean resize; float sx, sy; int ox, oy, ow;
        Drag(boolean resize) { this.resize = resize; }
        @Override public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: sx = e.getRawX(); sy = e.getRawY(); ox = lp.x; oy = lp.y; ow = lp.width; return true;
                case MotionEvent.ACTION_MOVE:
                    if (resize) lp.width = Math.max(dp(170), Math.min(dp(230), ow + (int) (e.getRawX() - sx)));
                    else { lp.x = ox + (int) (e.getRawX() - sx); lp.y = oy + (int) (e.getRawY() - sy); }
                    wm.updateViewLayout(overlay, lp); return true;
                case MotionEvent.ACTION_UP:
                    Profile p = App.profile(MacroService.this);
                    if (resize) p.overlayWidthDp = Math.round(lp.width / getResources().getDisplayMetrics().density);
                    else if (screen().x > screen().y) { p.overlayX = lp.x; p.overlayY = lp.y; }  // remember game (landscape) position only
                    App.save(MacroService.this); return true;
            }
            return false;
        }
    }

    public void setOverlayAlpha(float a) { ui.post(() -> { if (overlay != null) overlay.setAlpha(a); }); }

    /** Runs a recorded route's key holds / taps / pauses without vision (calibration keypad test). */
    boolean runHolds(java.util.List<Moves.Step> steps) {
        for (Moves.Step s : steps) {
            if (cancelled) return false;
            switch (s.type) {
                case 'H': if (!hold(set(s.keys), Math.round(s.count * s.tiles))) return false; break;
                case 'K': { String[] xy = s.label.split(","); if (!hotbar(Moves.hotbarSlot(Integer.parseInt(xy[0])), Integer.parseInt(xy[0]) / 2340f, Integer.parseInt(xy[1]) / 1080f)) return false; break; }
                case 'P': SystemClock.sleep(s.count); break;
                default: break;   // vision steps (walk-until-prompt) need screen capture
            }
        }
        return true;
    }

    /** Calibration keypad: runs through the same locked input path as the macro and reports what was held. */
    void calibrate(String k) {
        if (Engine.inst != null && !Engine.inst.finished()) { toast("Stop the macro first"); return; }
        new Thread(() -> {
            arm();
            Profile p = App.profile(this);
            long t0 = SystemClock.uptimeMillis();
            boolean ok;
            switch (k) {
                case "Rot←": ok = rotate(-1); break;
                case "Rot→": ok = rotate(1); break;
                case "360°": ok = true; for (int i = 0; i < 4 && ok; i++) ok = rotate(2); break;
                case "Tilt↑": ok = tilt(1); break;
                case "Tilt↓": ok = tilt(-1); break;
                case "Route→Field": ok = keyboard() ? keyScript(RecordedKeys.TO_FIELD_KB) : runHolds(Moves.toPineTree()); break;
                case "Route→Hive": ok = runHolds(Moves.toHive()); break;
                default:
                    EnumSet<Moves.Key> ks = EnumSet.noneOf(Moves.Key.class);
                    for (String s : k.split("\\+")) ks.add(Moves.Key.valueOf(s));
                    ok = hold(ks, Moves.tileMs(4, p.moveSpeed));
            }
            String msg = String.format(Locale.US, "%s → %s in %d ms (4 tiles = %d ms)", k, ok ? "done, released" : "FAILED/cancelled",
                    SystemClock.uptimeMillis() - t0, Moves.tileMs(4, p.moveSpeed));
            Log.add("cal: " + msg);
            ui.post(() -> cal.setText(msg));
        }, "calibrate").start();
    }

    private int lastW;
    final Runnable tick = new Runnable() {
        @Override public void run() {
            if (overlay == null) return;
            Point sz = screen();   // accessibility services don't reliably get config changes: re-dock on rotation here
            if (sz.x != lastW) { lastW = sz.x; if (overlay.isAttachedToWindow()) { place(); wm.updateViewLayout(overlay, lp); } }
            Engine e = Engine.inst;
            setKeepAwake(e != null && !e.finished());
            // nothing sent for 45 s (converting, balloon, waits): one harmless key so Samsung's touch lock doesn't appear
            if (e != null && !e.finished() && !e.paused && App.profile(MacroService.this).keepAlive && keyboard()
                    && SystemClock.uptimeMillis() - Keys.lastSent > 45_000) {
                Keys.lastSent = SystemClock.uptimeMillis();   // one at a time
                new Thread(() -> { if (Keys.connect(MacroService.this)) Keys.nudge(); }, "keep-alive").start();
            }
            boolean showT = App.profile(MacroService.this).showTemp;
            temp.setVisibility(showT ? View.VISIBLE : View.GONE);
            if (showT && SystemClock.uptimeMillis() - tempRead > 5000) {
                tempRead = SystemClock.uptimeMillis();
                android.content.Intent b = registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
                int t10 = b == null ? Integer.MIN_VALUE : b.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
                int th = android.os.Build.VERSION.SDK_INT >= 29 ? ((android.os.PowerManager) getSystemService(POWER_SERVICE)).getCurrentThermalStatus() : 0;   // Android 9: battery temp only
                if (t10 != Integer.MIN_VALUE) {
                    String[] h = heat(t10 / 10.0, th);
                    int lvl = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) * 100 / Math.max(1, b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100));
                    boolean chg = b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0;
                    temp.setText(h[0] + (lvl >= 0 ? " · 🔋 " + lvl + "%" + (chg ? " ⚡" : "") : ""));
                    temp.setTextColor(android.graphics.Color.parseColor(h[1]));
                }
            }
            Vision.Result v = Monitor.last;
            String bp = v != null && v.pollen >= 0 && v.pollenConf >= App.profile(MacroService.this).minConfidence ? String.format(Locale.US, "%.0f%%", v.pollen) : "?";
            if (e != null && !e.finished()) {
                String hr = e.stats.honeyRate();
                status.setText(String.format(Locale.US, "%s · 🎒 %s · ⏱ %s%s", e.phase, bp, Stats.fmt(e.stats.elapsed()), hr.isEmpty() ? "" : " · " + hr));
                playBtn.setText(e.paused ? "▶ Resume" : "⏸ Pause");
            } else {
                // why Start didn't start, in full on the panel (a toast cuts it to two lines)
                if (startWhy != null && SystemClock.uptimeMillis() - startWhyAt < 30000) status.setText("⚠ " + startWhy);
                else status.setText((e != null ? "Ended: " + e.reason : "Ready") + " · 🎒 " + bp);
                playBtn.setText("▶ Start");
            }
            if (expandedBox.getVisibility() == View.VISIBLE) {
                details.setText((e != null ? e.detail() + "\n" : "") + "Input: " + inputState() + "\nVision: " + (v == null ? "no frame" : v.summary())
                        + "\n" + Monitor.aiSummary(App.profile(MacroService.this)));
                cal.setText(cal.getText().length() == 0 || inputState().startsWith("none") ? cal.getText() : "Holding: " + inputState());
            }
            ui.postDelayed(this, 500);
        }
    };

    LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    TextView text(String s, int sp, int color) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); return t; }
    Button small(String s, int color) {
        Button b = new Button(this); b.setText(s); b.setTextSize(12); b.setTextColor(Color.WHITE); b.setAllCaps(false);
        b.setPadding(dp(4), 0, dp(4), 0); b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(0); b.setMinimumHeight(0);
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(9)); b.setBackground(d);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, dp(34)); l.setMargins(dp(2), dp(3), dp(2), dp(3)); b.setLayoutParams(l);
        return b;
    }
    // ---- overlay motion: strong ease-out (0.23, 1, 0.32, 1); transform + opacity only; off when Android's animations are off
    static final android.view.animation.Interpolator EASE_OUT = new android.view.animation.PathInterpolator(0.23f, 1f, 0.32f, 1f);
    static boolean motion() { return android.animation.ValueAnimator.areAnimatorsEnabled(); }
    /** Show a section: fades in while sliding down 6 dp from where it hangs (200 ms). */
    void reveal(View v) {
        v.animate().cancel();
        v.setVisibility(View.VISIBLE);
        if (!motion()) { v.setAlpha(1); v.setTranslationY(0); return; }
        v.setAlpha(0); v.setTranslationY(-dp(6));
        v.animate().alpha(1).translationY(0).setDuration(200).setInterpolator(EASE_OUT).start();
    }
    /** Hide a section the way it came (140 ms, a little faster than it appeared), then run after. */
    void conceal(View v, Runnable after) {
        v.animate().cancel();
        if (!motion() || v.getVisibility() != View.VISIBLE) { v.setVisibility(View.GONE); if (after != null) after.run(); return; }
        v.animate().alpha(0).translationY(-dp(4)).setDuration(140).setInterpolator(EASE_OUT).withEndAction(() -> {
            v.setVisibility(View.GONE); v.setAlpha(1); v.setTranslationY(0); if (after != null) after.run();
        }).start();
    }
    /** The panel settles into its new size: scale 0.96 → 1 from its docked corner (180 ms). */
    void settle() {
        if (overlay == null || !motion()) return;
        overlay.post(() -> {
            if (overlay == null) return;
            overlay.setPivotX(lp != null && lp.x < screen().x / 2 ? 0 : overlay.getWidth()); overlay.setPivotY(0);
            overlay.setScaleX(0.96f); overlay.setScaleY(0.96f);
            overlay.animate().scaleX(1).scaleY(1).setDuration(180).setInterpolator(EASE_OUT).start();
        });
    }

    static volatile String startWhy; static volatile long startWhyAt;
    void toast(String s) { ui.post(() -> android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()); }
}
