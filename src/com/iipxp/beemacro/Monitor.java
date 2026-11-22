package com.iipxp.beemacro;

import android.content.Context;
import android.os.SystemClock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Single analyzer thread: grabs frames, runs the HUD reader (Vision), the buff row (Buffs) and the world detector +
 *  tracker, publishes the latest results (+ a copy for the debug/labelling screen). Nothing else reads the screen. */
final class Monitor {
    static volatile Vision.Result last;
    static volatile long lastTime;
    static volatile int[] debugPx; static volatile int debugW, debugH; static volatile long debugTime;
    /** One raw frame on request (Discord screenshots): set shotWanted, the next frame is copied here. */
    /** Red error banner check on the next raw frame (sprinkler placement). */
    /** Last time the Pollen counter's digits changed (tablet): pollen is coming in. */
    static volatile long pollenChanged; private static boolean[] pollenSig;
    /** The game's own messages: last time it named Pine Tree Forest / another field, and since when another field
     *  has been the latest one named (0 = Pine Tree Forest was last). */
    static volatile long msgPine, msgOther, msgOtherSince;
    static volatile boolean bannerWanted; static volatile int bannerRows; static volatile long bannerTime;
    static volatile boolean shotWanted; static volatile int[] shotPx; static volatile int shotW, shotH; static volatile long shotTime;
    static volatile Vision.Result debugResult;
    static volatile List<Detector.Det> debugDets = new ArrayList<>();
    static final Detector.Tracker tracker = new Detector.Tracker();
    static volatile Buffs buffs;
    static volatile List<Buffs.Effect> buffBoxes = new ArrayList<>();
    static volatile float fps;
    static volatile long lastFrameMs;
    static volatile String state = "off";            // shown in the panel: never claims analysis that isn't running
    static volatile List<int[]> ignore = new ArrayList<>();
    static volatile Remap.Out remap;
    static volatile int[] thumb; static volatile long thumbTime;   // Align thumbnail of the latest raw frame                 // last frame's layout mapping (overlay draws through it)
    static List<int[]> ignoreRemapped(Remap.Out ro) { if (!ro.tablet && !ro.phone) return ignore; List<int[]> o = new ArrayList<>(); for (int[] r : ignore) o.add(Remap.fromScreen(ro, r)); return o; }
    static volatile NavGuard.View view;               // flowers around the character (null = not in gameplay)
    /** Set a name to save the next raw frame as a PNG in the app's files/guard folder (for checking guard decisions). */
    static volatile String saveFrame;
    static volatile FieldMap.Read fieldRead;          // latest map of the field around the character
    static final FieldMap.Tracker fieldTrack = new FieldMap.Tracker();
    static volatile long honey = -1, honeyTime; private static long honeyPending = -1, honeyRead;
    /** Honey only rises (spending aside): a jump outside that range must repeat on the next read before it counts. */
    static void honeySeen(long v, long now) {
        if (honey < 0 || (v >= honey * 0.97 && v <= honey * 1.6 + 1_000_000_000L) || v == honeyPending) { honey = v; honeyTime = now; honeyPending = -1; }
        else honeyPending = v;
    }
    /** Auto screen calibration: a few agreeing honey-counter measurements set Remap.uiCal. */
    static volatile String calState = "not measured yet";
    private static final List<Double> calSamples = new ArrayList<>(); private static long calTry;
    static void recalibrate() {
        synchronized (calSamples) { calSamples.clear(); } synchronized (phoneSamples) { phoneSamples.clear(); }
        Remap.uiCal = 0; Remap.phoneCal = null; calTry = 0; calState = "not measured yet";
    }
    private static void calibrateStep(Profile p, CaptureService.Frame f, long now) {
        if (p.uiScale > 0) { Remap.uiCal = p.uiScale / 100.0; calState = "set by hand: " + p.uiScale + "%"; return; }
        if (!Remap.tablet(f.w, f.h)) { phoneStep(f, now); return; }
        if (Remap.uiCal > 0 || now - calTry < 3000) return;
        calTry = now;
        double c = Remap.calibrate(f.px, f.w, f.h);
        if (c <= 0) { calState = "waiting for the honey counter"; return; }
        synchronized (calSamples) {
            calSamples.add(c);
            if (calSamples.size() < 3) { calState = "measuring (" + calSamples.size() + "/3)"; return; }
            List<Double> s = new ArrayList<>(calSamples.subList(calSamples.size() - 3, calSamples.size()));
            java.util.Collections.sort(s);
            if (s.get(2) - s.get(0) > 0.015 * s.get(1)) { calState = "measuring (readings disagree)"; return; }
            double m = s.get(1), def = f.h / 1440.0;
            // within 1.5% of the screen-height guess: keep the guess (exactly the tested layout on the Tab S9 FE)
            Remap.uiCal = Math.abs(m - def) < 0.015 * m ? def : m;
            calState = String.format(java.util.Locale.US, "measured %.1f%% on %dx%d", Remap.uiCal * 100, f.w, f.h);
            Log.add("screen: UI scale " + calState);
        }
    }
    private static final List<Remap.Phone> phoneSamples = new ArrayList<>();
    /** Phones: find where this phone draws the HUD (3 matching readings), so other phones than the S23 read right. */
    private static void phoneStep(CaptureService.Frame f, long now) {
        if (now - calTry < (Remap.phoneCal == null ? 1500 : 60_000)) return;   // re-checked every minute (UI scale can change)
        calTry = now;
        Remap.Phone ph = Remap.locatePhone(f.px, f.w, f.h);
        if (ph == null) { if (Remap.phoneCal == null) calState = "phone: looking for the Honey/Pollen counters"; return; }
        synchronized (phoneSamples) {
            phoneSamples.add(ph);
            while (phoneSamples.size() > 3) phoneSamples.remove(0);
            if (phoneSamples.size() < 3) return;
            Remap.Phone a = phoneSamples.get(0), b = phoneSamples.get(1), c = phoneSamples.get(2);
            if (Math.abs(a.k - c.k) > 0.02 * c.k || Math.abs(a.cx - c.cx) > 8 || Math.abs(a.top - c.top) > 6 || Math.abs(a.inset - c.inset) > 10) return;
            Remap.Phone old = Remap.phoneCal;
            Remap.phoneCal = b;
            calState = (b.s23(f.w, f.h) ? "S23 layout" : "phone layout " + b) + " on " + f.w + "x" + f.h;
            if (old == null || Math.abs(old.k - b.k) > 0.02 || Math.abs(old.inset - b.inset) > 10) Log.add("screen: " + calState);
        }
    }
    static volatile double goldRow;                   // hive gold across the upper half of the view (NavGuard.goldRow)
    static volatile double facing;                    // gold hive walls on both sides of the view (NavGuard.hiveFacing)
    static volatile double motion = 99; static volatile long motionTime;   // world change over the last ~second
    private static int[] motionGrid; private static long motionGridTime;
    private static volatile Thread thread;
    private static long lastFpsLog;
    static final int CLASSIFY_BUDGET = 6; static final long RECHECK_MS = 400;

    /** Uncertain detections kept for you to review (one per tracked object). */
    static final ArrayDeque<Detector.Det> review = new ArrayDeque<>();
    private static final java.util.Set<Integer> reviewed = new java.util.HashSet<>();

    static synchronized void start(Context c) {
        if (thread != null) return;
        thread = new Thread(() -> {
            long winStart = SystemClock.uptimeMillis(); int frames = 0;
            Catalog cat = App.catalog(c);
            if (buffs == null && cat != null) buffs = new Buffs(cat);
            while (thread == Thread.currentThread()) {
                long t0 = SystemClock.uptimeMillis();
                Profile p = App.profile(c);
                CaptureService cs = CaptureService.inst;
                CaptureService.Frame f = cs == null ? null : cs.grab();
                if (f == null) state = "no frame from capture";
                else {
                    if (shotWanted) { shotPx = f.px.clone(); shotW = f.w; shotH = f.h; shotWanted = false; shotTime = SystemClock.uptimeMillis(); }
                    if (bannerWanted) { bannerRows = Vision.redBannerRows(f.px, f.w, f.h); bannerWanted = false; bannerTime = SystemClock.uptimeMillis(); }
                    String save = saveFrame;
                    if (save != null) {
                        saveFrame = null;
                        android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(f.px, f.w, f.h, android.graphics.Bitmap.Config.ARGB_8888);
                        new Thread(() -> {
                            java.io.File dir = c.getExternalFilesDir("guard");
                            try (java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(dir, save + ".png"))) { bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o); }
                            catch (Exception e) { Log.add("guard frame not saved: " + e.getMessage()); }
                            bm.recycle();
                        }, "guard-frame").start();
                    }
                    // Other screen shapes (tablet) are rebuilt into the S23 layout every reader was measured on
                    calibrateStep(p, f, SystemClock.uptimeMillis());
                    Remap.Out ro = Remap.apply(f.px, f.w, f.h); remap = ro;
                    Vision.Result r = Vision.analyze(ro);
                    long tn = SystemClock.uptimeMillis();
                    if (ro.tablet && r.screen == Vision.Screen.GAME) {   // pollen counter digits changing = pollen coming in
                        boolean[] sig = Vision.pollenText(f.px, f.w, f.h);
                        int d = Vision.textDiff(pollenSig, sig);
                        if (d > Math.max(10, sig.length / 400)) pollenChanged = tn;
                        pollenSig = sig;
                        FieldMessages.Said said = FieldMessages.read(f.px, f.w, f.h);
                        if (said == FieldMessages.Said.PINE_TREE) { msgPine = tn; msgOtherSince = 0; }
                        else if (said == FieldMessages.Said.OTHER_FIELD) { msgOther = tn; if (msgOtherSince == 0) msgOtherSince = tn; }
                    }
                    if (ro.tablet && tn - honeyRead > 2000) { honeyRead = tn; long hv = HoneyOcr.read(f.px, f.w, f.h); if (hv > 0) honeySeen(hv, tn); }
                    final int[] fpx0 = ro.px; final int fw0 = ro.w, fh0 = ro.h;
                    List<Detector.Det> dets = new ArrayList<>();
                    boolean game = r.screen == Vision.Screen.GAME && r.screenConf >= p.minConfidence;
                    long now = SystemClock.uptimeMillis();
                    if (game) {
                        Detector.Library lib = App.library(c);
                        double wx0 = ro.worldX0, wx1 = fw0 - ro.worldX0;   // same view angles on every screen shape (Remap)
                        view = NavGuard.view(fpx0, fw0, fh0, wx0, wx1);
                        facing = NavGuard.hiveFacing(fpx0, fw0, fh0, wx0, wx1, ignoreRemapped(ro));   // our own panel hides part of the world
                        goldRow = NavGuard.goldRow(fpx0, fw0, fh0, wx0, wx1, ignoreRemapped(ro));
                        FieldMap.Read fm = FieldMap.analyze(fpx0, fw0, fh0, wx0, wx1);
                        fieldRead = fm; fieldTrack.add(fm, now);
                        if (now - motionGridTime >= 800) {
                            int[] g = NavGuard.grid(fpx0, fw0, fh0, wx0, wx1);
                            if (motionGrid != null && now - motionGridTime < 3000) { motion = NavGuard.motion(motionGrid, g); motionTime = now; }
                            motionGrid = g; motionGridTime = now;
                        }
                        // World objects only where the ground model applies (Pine Tree); at the hive everything is "not ground"
                        boolean field = r.field == Vision.Field.PINE_TREE;
                        dets = field ? Detector.detect(fpx0, fw0, fh0, cat, lib, p.boxMinConf, ignoreRemapped(ro), false) : new ArrayList<>();   // stage 1 only: cheap
                        int ax = Math.round(p.playerX * fw0), ay = Math.round(p.playerY * fh0), rad = Math.round(0.12f * fh0);
                        // Stages 2-3 per tracked object, not per candidate: new objects first, re-checked every RECHECK_MS,
                        // at most CLASSIFY_BUDGET per frame; in between the last answer is carried (and not re-voted).
                        final int[] budget = {CLASSIFY_BUDGET};
                        final List<Catalog.Entry> mine = lib.world();
                        final int[] fpx = fpx0; final int fw = fw0, fh = fh0; final long tnow = now; final double mc = p.boxMinConf;
                        tracker.update(dets, now, (x, y) -> Math.hypot(x - ax, y - ay) < rad, (t, d) -> {
                            boolean due = t == null || t.d.tpl == null || tnow - t.classifiedAt >= RECHECK_MS;
                            if (due && budget[0] > 0) { budget[0]--; Detector.classify(fpx, fw, fh, d, cat, mine, mc); return true; }
                            if (t != null) { d.label = t.d.label; d.cat = t.d.cat; d.conf = t.d.conf; d.guess = t.d.guess; d.guessScore = t.d.guessScore; d.tpl = t.d.tpl; }
                            return false;
                        });
                        if (buffs != null) { buffs.minConf = p.buffMinConf; buffBoxes = buffs.update(fpx0, fw0, fh0, r, lib.buffs(), now, s -> Log.add("buffs: " + s)); }
                        queueReview();
                        state = "analysing";
                    } else {
                        tracker.update(dets, now, null);
                        state = "paused: not in Bee Swarm gameplay (" + r.screen + ")";
                    }
                    last = r; lastTime = SystemClock.uptimeMillis(); frames++;
                    lastFrameMs = lastTime - t0;
                    if (r.w > r.h && lastTime - debugTime > 3000) { debugPx = fpx0.clone(); debugW = fw0; debugH = fh0; debugResult = r; debugDets = dets; debugTime = lastTime; }
                }
                long now = SystemClock.uptimeMillis();
                if (now - winStart >= 2000) {
                    fps = frames * 1000f / (now - winStart); frames = 0; winStart = now;
                    if (now - lastFpsLog > 30_000) { lastFpsLog = now; Log.add(String.format(Locale.US, "vision: %.1f fps, last frame %d ms, %s, %d tracks", fps, lastFrameMs, state, tracker.snapshot().size())); }
                }
                boolean busy = Engine.inst != null && !Engine.inst.finished();
                long period = busy ? 1000 / Math.max(1, p.detectFps) : p.batterySaver ? 1000 : 300;
                SystemClock.sleep(Math.max(10, period - (now - t0)));
            }
        }, "vision");
        thread.start();
    }
    static synchronized void stop() { thread = null; last = null; fps = 0; state = "off (screen capture stopped)"; }

    /** Objects tracked ≥3 frames that stayed Unknown but had a candidate name: one review item per object. */
    private static void queueReview() {
        for (Detector.Tracker.Track t : tracker.snapshot()) {
            if (t.hits < 3 || t.confirmed() != null || t.d.tpl == null || reviewed.contains(t.id)) continue;
            reviewed.add(t.id);
            synchronized (review) { review.addFirst(t.d); while (review.size() > 40) review.removeLast(); }
        }
        if (reviewed.size() > 5000) reviewed.clear();
    }

    static List<Buffs.Effect> activeBuffs() { Buffs b = buffs; return b == null ? new ArrayList<>() : b.active(); }

    /** Live panel text, in the sections you asked for. */
    static String aiSummary(Profile p) {
        StringBuilder s = new StringBuilder();
        s.append(String.format(Locale.US, "LIVE VISION · %s · %.1f fps · %d ms/frame", state, fps, lastFrameMs));
        int named = 0, unk = 0;
        for (Detector.Tracker.Track t : tracker.snapshot()) if (!t.d.buff) { if (t.confirmed() != null) named++; else unk++; }
        s.append("\n  objects: ").append(named).append(" confirmed, ").append(unk).append(" unknown");
        Engine en = Engine.inst;
        if (en != null && !en.finished()) s.append("\nFIELD POSITION\n").append(en.fieldDebug());
        s.append("\nACTIVE BUFFS & DEBUFFS");
        List<Buffs.Effect> act = activeBuffs();
        if (act.isEmpty()) s.append("\n  none recognised");
        for (Buffs.Effect e : act)
            s.append(String.format(Locale.US, "\n  %s %s — active%s — on %s — %.2f", e.debuff ? "🔴" : "🟣", e.name, e.stack == null ? "" : " — " + e.stack,
                    Stats.fmt(SystemClock.uptimeMillis() - e.since), e.conf));
        s.append("\n  (remaining time: the game shows no countdown on buff icons)");
        s.append("\nTOKENS");
        Vision.Result r = last;
        int ax = r == null ? 0 : Math.round(p.playerX * r.w), ay = r == null ? 0 : Math.round(p.playerY * r.h);
        int n = 0;
        for (Detector.Tracker.Track t : tracker.snapshot()) {
            String c = t.confirmed(); if (t.d.buff || c == null) continue;
            if (n++ >= 6) { s.append("\n  …"); break; }
            s.append(String.format(Locale.US, "\n  #%d %s %.2f · %d px away · priority %d", t.id, c, t.d.conf, (int) Math.hypot(t.d.cx() - ax, t.d.cy() - ay), Engine.prioOf(p, t.d.cat)));
        }
        if (n == 0) s.append("\n  none confirmed");
        if (r != null && r.screen == Vision.Screen.GAME) s.append("\nFARMING STATUS\n  Field: ").append(r.field == Vision.Field.PINE_TREE ? "Pine Tree Forest" : r.field == Vision.Field.OTHER_FIELD ? "another field" : "not in a field")
                .append(" · Bag ").append(r.pollenConf >= p.minConfidence ? Math.round(r.pollen) + "%" : "?")
                .append("\n  Position: ").append(view == null ? "?" : view.toString()).append(String.format(Locale.US, " · motion %.0f", motion))
                .append("\n  Tide Popper: not detectable (equipped tool isn't shown on the mobile HUD)");
        return s.toString();
    }

    /** Wait for a result analysed after `since` (ms uptime). */
    static Vision.Result fresh(long since, long timeoutMs) {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < end) {
            if (lastTime > since && last != null) return last;
            SystemClock.sleep(50);
        }
        return null;
    }
}
