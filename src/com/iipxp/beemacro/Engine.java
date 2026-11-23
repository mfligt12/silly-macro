package com.iipxp.beemacro;

import android.os.SystemClock;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * The farming cycle (same order as Natro): respawn -> verify own hive -> convert -> walk to Pine Tree -> Skillet
 * until the backpack threshold -> repeat. Decisions are only made on confident vision; otherwise it pauses.
 */
final class Engine implements Runnable {
    static volatile Engine inst;

    final MacroService in;
    final Profile p;
    final Stats stats = new Stats();
    volatile String phase = "Starting", nav = "—", step = "—", reason = "";
    volatile boolean paused, stopped, done;
    private int recoveries;

    static final class Stopped extends RuntimeException { }
    static final class Recover extends RuntimeException { Recover(String m) { super(m); } }

    private Engine(MacroService s) { in = s; p = App.profile(s); }
    /** Tap targets for this screen's layout (S23 or tablet; see Remap). */
    private Remap.Taps T() { android.graphics.Point z = in.screen(); return Remap.taps(Math.max(z.x, z.y), Math.min(z.x, z.y)); }
    boolean finished() { return done; }

    /** Returns null when started, otherwise what needs attention. */
    static synchronized String start(MacroService s) {
        String acc = Account.check(s, true);   // signed in and not disabled by the admin
        if (acc != null) { Log.add("preflight: " + acc); return acc; }
        String why = preflight(s);
        Log.add(why == null ? "preflight: all checks passed" : "preflight: " + why);
        if (why != null) return why;
        Engine e = new Engine(s);
        inst = e;
        new Thread(e, "engine").start();
        return null;
    }

    static String preflight(MacroService s) {
        Profile p = App.profile(s);
        if (CaptureService.inst == null) return "Screen capture is off — open Silly Macro and tap 'Allow screen capture'.";
        if (!Profile.NAME.equals("PINE_TREE_MASTER_PROFILE")) return "Wrong profile loaded.";
        if (!p.hiveConfirmed) return "Confirm your hive slot once in the Silly Macro app.";
        if (Moves.skillet(Profile.DW_FDC, Profile.HW_FDC, Profile.CR_FDC, Profile.DW_ALIGN, Profile.HW_ALIGN).size() < 50
                || Moves.recorded("TO_FIELD").length == 0 || Moves.recorded("TO_HIVE").length == 0) return "Pattern/route data missing.";
        Vision.Result r = Monitor.fresh(SystemClock.uptimeMillis() - 1, 3000);
        if (r == null) return "No screen frames yet — is capture running?";
        if (r.screen == Vision.Screen.UNKNOWN && !p.noHudForced) {   // don't send people to a button: re-measure and wait for the HUD (up to 20 s)
            MacroService.startWhy = "Reading this screen's Bee Swarm HUD…"; MacroService.startWhyAt = SystemClock.uptimeMillis();
            Monitor.hurry();
            for (long until = SystemClock.uptimeMillis() + 20_000; r.screen == Vision.Screen.UNKNOWN && SystemClock.uptimeMillis() < until; ) {
                SystemClock.sleep(500);
                Vision.Result q = Monitor.fresh(SystemClock.uptimeMillis() - 1, 1500); if (q != null) r = q;
            }
            MacroService.startWhy = null;
        }
        android.graphics.Point z = s.screen();
        Vision.Mode mode = Vision.startMode(p.noHudForced, r, p.minConfidence, z.x > z.y);
        if (mode == Vision.Mode.BLOCKED) {
            if (z.y > z.x) return "Turn the device sideways: Bee Swarm must be in landscape.";
            if (r.screen == Vision.Screen.DARK)
                return "Screen capture only sees a black screen. Settings → stop screen capture, then Home → 2 · Allow screen capture and pick \"Entire screen\" (not \"A single app\").";
            return "The Roblox menu is open: close it, then press Start.";
        }
        // The counter reader is optional: a screen it can't read starts in no-HUD mode instead of refusing to start
        startNoHud = mode == Vision.Mode.NO_HUD;
        if (startNoHud) Log.add("preflight: " + (p.noHudForced ? "no-HUD mode is switched on" : "Honey/Pollen counters not read (" + Monitor.calState + ")") + " - starting in no-HUD mode");
        s.arm();
        if (p.keyboard()) {
            if (!Keys.connect(s) || !Keys.ping()) return "Keyboard mode: the key sender isn't running on this device (" + Keys.status + "). Start it with Shizuku in Silly Macro (or start_keys from a PC) and try again.";
            String emu = Keys.emulatorState();
            for (int i = 0; i < 30 && "starting".equals(emu); i++) { SystemClock.sleep(500); emu = Keys.emulatorState(); }   // just started: give it the time to create the keyboard
            if ("starting".equals(emu)) return "The key sender is still creating its keyboard + mouse. Wait a few seconds and press Start again.";
            if (emu != null && emu.startsWith("none")) return "Keyboard mode: the key sender runs, but this device has no emulated keyboard/mouse (" + emu + "). Roblox ignores keys without one: plug in a USB/Bluetooth keyboard and mouse.";
        } else if (!s.tap(p.idleX, p.idleY)) return "Input test failed: accessibility gestures were not accepted.";
        return null;
    }

    // ------------------------------------------------------------------ control (any thread)
    void pause(String why) {
        if (done) return;
        // overnight, the macro's own pauses become "recover and carry on" (only on its own thread: your Pause button
        // runs on the screen thread, where that throw crashed the app, 2026-11-21 02:53 / 03:30 / 04:05)
        if (night() && !why.startsWith("Battery") && Thread.currentThread() == worker) { Log.add("overnight, not pausing: " + why); throw new Recover("overnight: " + why); }
        reason = why; paused = true; stats.interruptions++;
        in.emergencyRelease();
        Log.add("PAUSED: " + why);
        hook("problem", "⏸ Paused", why, Webhook.GOLD, true);
    }
    void resume() { if (done) return; reason = ""; paused = false; Log.add("resumed"); }
    void stop(String why) { reason = why; stopped = true; paused = false; in.emergencyRelease(); Log.add("STOP: " + why); }

    private void checkpoint() {
        if (stopped) throw new Stopped();
        if (paused) {
            String ph = phase; phase = "Paused";
            while (paused && !stopped) SystemClock.sleep(50);
            if (stopped) throw new Stopped();
            phase = ph;
        }
        in.arm();
    }
    private void sleep(long ms) { long end = SystemClock.uptimeMillis() + ms; while (SystemClock.uptimeMillis() < end) { checkpoint(); SystemClock.sleep(Math.min(50, ms)); } }
    private void phase(String name, String bucket) { phase = name; stats.enter(bucket); decide(name, "farming cycle step", 1); Log.add("phase: " + name); }

    // ------------------------------------------------------------------ main loop
    private volatile Thread worker;
    @Override public void run() {
        worker = Thread.currentThread();
        final int userSlot = p.hiveSlot;   // an auto-picked free hive is for this run only
        noHudNow = startNoHud;
        if (noHudNow) noHudNote(p.noHudForced ? "switched on in Settings" : "the Honey/Pollen counters weren't read on this screen");
        Log.add("engine: start (" + Profile.PRESET + ", hive slot " + p.hiveSlot + ", threshold " + p.backpackThreshold + "%)");
        lastHourly = SystemClock.uptimeMillis();
        hook("session", "▶ Macro started", Routes.name(p.field) + " · " + p.pattern + "\nHive slot " + p.hiveSlot + " · bag limit " + p.backpackThreshold + "% · " + p.fieldMinutes + " min"
                + (Monitor.honey > 0 ? "\nHoney: " + Stats.big(Monitor.honey) : ""), Webhook.PURPLE, true);
        try {
            while (true) {
                try { cycle(); recoveries = 0; afterTrip(); }
                catch (Recover r) {
                    stats.navFailures++; recoveries++;
                    if (phase.equals("Farming")) stats.patternRestarts++;
                    in.emergencyRelease();
                    Log.add("recover #" + recoveries + ": " + r.getMessage());
                    hook("problem", "↺ Reset #" + recoveries, r.getMessage(), Webhook.GOLD, true);
                    if (!p.recovery || recoveries > p.maxRecoveries) {
                        if (night()) { checkpoint(); rejoinUntilBack("recoveries kept failing: " + r.getMessage()); }
                        else { pause("Navigation failed " + recoveries + "×: " + r.getMessage()); checkpoint(); }
                        recoveries = 0;
                    }
                    checkpoint();   // emergencyRelease blocks all input: re-arm so the reset's first key isn't dropped
                    afterTrip();
                }
                catch (Stopped s) { throw s; }
                catch (RuntimeException crash) {
                    if (!night()) throw crash;
                    stats.errors++; Log.add("engine error, carrying on overnight: " + android.util.Log.getStackTraceString(crash));
                    in.emergencyRelease(); sleep(30_000);
                }
            }
        } catch (Stopped s) {
            // normal stop
        } catch (Throwable t) {
            stats.errors++; reason = "error: " + t; Log.add("engine crash: " + android.util.Log.getStackTraceString(t));
        } finally {
            in.emergencyRelease();
            if (p.hiveSlot != userSlot) { p.hiveSlot = userSlot; App.save(in); }
            noHudNow = false;
            stats.finish(reason.isEmpty() ? "stopped" : reason);
            Stats.save(in, stats);
            phase = "Stopped"; done = true;
            Log.add("engine: end — " + stats.summary().replace('\n', ' '));
            hook("session", "■ Macro stopped", (reason.isEmpty() ? "stopped" : reason) + "\n\n" + stats.summary(), Webhook.GREY, false);
        }
    }

    /** One trip, same order as the reference video (Natro GoGather): respawn at the hive (known spot + camera
     *  facing the hive) → convert → your recorded cannon route to Pine Tree → Skillet until the bag is full → your
     *  recorded route back → walk along the hives to the one showing YOUR prompt → convert. Next trip respawns first. */
    /** Keyboard trip (tablet), Natro Macro's flow (NatroTeam/NatroMacro, GPL-3.0): reset at the hive, convert, put
     *  the camera in the known pose, walk to the ramp and on to Pine Tree, Skillet until the bag
     *  is full, walk home (no respawn: a respawn empties the bag), convert at the hive. */
    private boolean firstKbTrip = true;
    private boolean night() { return p.overnight && p.keyboard(); }

    /** After every trip: honey stats, the running report, and the battery check. */
    private long lastHourly;
    /** Discord report (Settings → Discord). Never blocks the macro. */
    private void hook(String kind, String title, String text, int color, boolean shot) { Webhook.send(in, kind, title, text, color, shot); }

    private void afterTrip() {
        String acc = Account.check(in, false);   // every few minutes: the admin may have disabled this account
        if (acc != null) { stop(acc); in.toast(acc); throw new Stopped(); }
        if (Monitor.honey > 0) stats.honey(Monitor.honey);
        timedReport();
        stats.fieldExits = fieldOuts;
        Stats.saveCurrent(in, stats);
        afterTripRest();
    }
    /** The timed Discord report (Settings → Discord → Send it every N min), like Natro's hourly one. Checked after
     *  every trip and every farming step, so a short interval isn't held back by a long trip. */
    private void timedReport() {
        if (SystemClock.uptimeMillis() - lastHourly >= Math.max(10, p.whEveryMins) * 60_000L) {
            lastHourly = SystemClock.uptimeMillis();
            if (Monitor.honey > 0) stats.honey(Monitor.honey);
            hook("hourly", "⏱ " + (p.whEveryMins == 60 ? "Hourly" : p.whEveryMins + "-minute") + " report", "Session " + Stats.fmt(stats.elapsed()) + " · conversions " + stats.conversions
                    + (stats.honeyStart > 0 && stats.honeyNow > stats.honeyStart ? "\nHoney made: +" + Stats.big(stats.honeyNow - stats.honeyStart) : "")
                    + (stats.honeyRate().isEmpty() ? "" : "\nRate: " + stats.honeyRate().replace("🍯 ", "")) + (Monitor.honey > 0 ? "\nHoney: " + Stats.big(Monitor.honey) : ""), Webhook.CYAN, true);
        }
    }
    private void afterTripRest() {
        if (!night()) return;
        android.content.Intent b = in.registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return;
        int level = b.getIntExtra("level", -1) * 100 / Math.max(1, b.getIntExtra("scale", 100)), plugged = b.getIntExtra("plugged", 0);
        if (level >= 0 && level <= p.lowBatteryStop && plugged == 0) { stop("Battery " + level + "% and not charging: stopped overnight run"); checkpoint(); }
    }

    /** Close Roblox and open your server again, until the game is back (waits longer between failed tries). */
    private void rejoinUntilBack(String why) {
        for (int attempt = 1; ; attempt++) {
            checkpoint();
            Log.add("rejoin #" + attempt + ": " + why);
            hook("problem", "🔌 Rejoining (#" + attempt + ")", why, Webhook.RED, true);
            phase = "Rejoining"; decide("Rejoining", "The game is gone or stuck; opening your server again.", why, "claim the hive and carry on", 0.9);
            in.emergencyRelease(); in.arm();
            if (!Keys.connect(in) || !Keys.rejoin(p.joinLink())) Log.add("rejoin: key sender refused (" + Keys.status + ")");
            stats.rejoins++;
            long end = SystemClock.uptimeMillis() + 180_000; int good = 0;
            while (SystemClock.uptimeMillis() < end && good < 5) { sleep(1000); Vision.Result r = Monitor.last; good = r != null && sure(r) ? good + 1 : 0; }
            if (good >= 5) { Log.add("rejoin: back in the game"); sleep(8000); firstKbTrip = false; ownsHive = false; return; }   // a new server: no hive until the claim
            Log.add("rejoin: game not back after 3 min"); sleep(Math.min(10, attempt) * 60_000L);
        }
    }

    /** After the camera faces a hive: claim it if it is free (after a rejoin you own no hive). Overnight, if this
     *  slot is taken, try the others from the central spawn. */
    private void ensureHive() {
        Vision.Result r = waitFor(x -> promptKnown(x), 3000);
        if (r != null && ownHive(r)) { if (r.prompt != Vision.Prompt.BANNER_BLUE) ownsHive = true; return; }
        if (r != null && r.prompt == Vision.Prompt.TAP_CLAIM) { claimHere(); return; }
        if (!p.autoHiveSlot || ownsHive) return;   // we own one elsewhere: respawning brings us to it
        // Taken = the prompt is readable and it's neither our hive nor Claim Hive, on a second look too (a walk that
        // missed the pad reads NONE once; give the banner time). Unreadable = don't guess, keep the slot.
        if (r == null) { Log.add("hive: prompt not readable at slot " + p.hiveSlot + ", keeping it"); return; }
        sleep(2000);
        Vision.Result r2 = waitFor(x -> promptKnown(x), 3000);
        if (r2 == null || ownHive(r2) || r2.prompt == Vision.Prompt.TAP_CLAIM) { if (r2 != null && r2.prompt == Vision.Prompt.TAP_CLAIM) claimHere(); return; }
        int start = p.hiveSlot;
        int[] order = Profile.slotOrder(start);
        for (int i = 1; i < order.length; i++) {
            int s = order[i];
            Log.add("hive: slot " + p.hiveSlot + " is taken, trying slot " + s);
            decide("Finding a free hive", "Hive slot " + start + " belongs to someone else", "no Claim Hive / own-hive prompt at slot " + p.hiveSlot, "try slot " + s, 0.8);
            p.hiveSlot = s; respawn(); alignCamera();   // not saved: your chosen slot is tried first again next run / server
            Vision.Result q = waitFor(x -> promptKnown(x), 3000);
            if (q != null && ownHive(q)) { autoSlotNote(start, s); return; }
            if (q != null && q.prompt == Vision.Prompt.TAP_CLAIM) { claimHere(); autoSlotNote(start, s); return; }
        }
        p.hiveSlot = start;
        throw new Recover("no free hive found");
    }
    /** Panel + log + webhook: which slot was taken instead of the chosen one. */
    private void autoSlotNote(int wanted, int got) {
        String m = "Hive slot " + wanted + " was taken, using slot " + got + " instead (this run only)";
        Log.add("hive: " + m); decide("Using hive slot " + got, m, 1); nav = m;
        hook("problem", "🐝 Hive slot changed", m, Webhook.GOLD, true);
    }
    private void claimHere() {
        prompt(); sleep(2500);
        stats.hiveClaims++; ownsHive = true; Log.add("hive: claimed slot " + p.hiveSlot);
        if (waitFor(x -> promptKnown(x) && ownHive(x), 8000) == null) Log.add("hive: claim not confirmed yet");
    }

    private void cycleKb() {
        if (firstKbTrip) {   // a reset empties the bag: never throw away pollen the player already has
            firstKbTrip = false;
            Vision.Result r = waitFor(this::bagKnown, 3000);
            if (r != null && r.pollen >= 5) {   // live: paused over 1%, not worth stopping for
                if (promptKnown(r) && (r.prompt == Vision.Prompt.TAP_BLUE || r.prompt == Vision.Prompt.TAP_RED)) { Log.add(String.format(Locale.US, "start: bag at %.0f%% and at the hive, converting before the first reset", r.pollen)); phase("Converting", "convert"); convert(); }
                else { pause(String.format(Locale.US, "Your bag has %.0f%% pollen and you are not at your hive. Starting resets the character and empties the bag. Convert first, or press Resume to continue anyway.", r.pollen)); checkpoint(); }
            }
        }
        phase("Respawn → hive", "nav"); nav = "Reset → own hive";
        respawn();
        phase("Verify hive", "nav");
        verifyHive();
        if (!p.autoReturn) { pause("Auto return to field is off"); checkpoint(); }
        phase("Camera reset", "nav");
        alignCamera();
        ensureHive();
        if (!tripConverted && !p.balloonMode.equals("Never")) balloonAfterReset();   // a reset that didn't follow our own convert
        tripConverted = false;
        waitHasteClear();   // Haste changes every distance on the route: start at base speed when possible
        if (mondoDue()) {   // Natro nm_Mondo (Buff mode): stand beside Mountain Top, then the next trip resets as usual
            lastMondo = SystemClock.elapsedRealtime();
            phase("Mondo buff", "nav"); nav = "Natro path: hive → ramp → Mountain Top (Mondo Chick)";
            try {
                run(Moves.natroToRamp(p.hiveSlot, p.moveSpeed), "to the ramp");
                run(Moves.natroToMondo(p.moveSpeed), "walk to Mountain Top");
            } catch (Recover r) {   // already marked as tried: the reset goes back to normal farming, no retry loop
                Log.add("mondo: walk failed (" + r.getMessage() + "), skipping Mondo this hour");
                throw r;
            }
            saveGuardFrame("mondo_arrival");   // for checking where the walk ends
            Log.add("mondo: waiting " + p.mondoSecs + " s off the field");
            hook("field", "🐥 Mondo buff", "Standing by Mountain Top for " + p.mondoSecs + " s", Webhook.PURPLE, true);
            sleep(p.mondoSecs * 1000L);
            return;
        }
        String fname = Routes.name(p.field);
        boolean pine = p.field.equals("pinetree");
        phase("To " + fname, "nav"); nav = "Natro path: hive → ramp → on foot to " + fname;
        if (pine) {   // our live-tuned Pine Tree route (identical to Natro's gtf-pinetree walk branch; the self-test checks that)
            run(Moves.natroToRamp(p.hiveSlot, p.moveSpeed), "to the ramp");
            run(Moves.natroToPine(p.moveSpeed), "walk to Pine Tree");
        } else run(route("gtf-" + p.field), "walk to " + fname);
        in.tilt(FIELD_TILT);
        sleep(800);
        // blue/white fields only: the flower check knows Pine Tree-like flowers
        arrivalOk = false;
        if (p.navGuard && Routes.blueField(p.field) && !seekPine(10_000, "arrival")) {
            // twice in a row from what we took for the pad: the learned pad zoom step may be wrong, learn it again
            if (++arrivalFails >= 2 && p.padZoomStep > 0 && !ownsHive) { Log.add("camera: two walks found no field, forgetting this device's pad step " + p.padZoomStep); p.padZoomStep = 0; App.save(in); arrivalFails = 0; }
            throw new Recover("no " + fname + " flowers in sight after the walk");
        }
        arrivalOk = true; arrivalFails = 0;
        // The walk ends near the middle of the field: place the sprinkler here (Natro's walk to a corner climbs the hedge).
        // Corner mode places it later, at the pattern's start in the corner.
        if (!cornerWanted()) placeSprinkler();
        if (p.fieldRotate != 0) in.rotate(p.fieldRotate);   // Natro: field camera turns before the pattern
        phase("Farming", "farm"); nav = fname + " (verifying)";
        if (p.toolHold) in.toolHold(true);
        try { farm(); } finally { if (p.toolHold) in.toolHold(false); }
        if (p.fieldRotate != 0) in.rotate(-p.fieldRotate);  // Natro: rotate back before walking home
        waitHasteClear();
        phase("To hive", "nav"); nav = "Natro path: " + fname + " → hive pads (walk)";
        // the walk ends the moment our own pad's "Make Honey" shows (only our hive shows it): live, a speed boost the
        // buff row didn't show made the route run on past the pad, and the search then went the wrong way
        java.util.function.BooleanSupplier atPad = () -> { Vision.Result r = Monitor.last; return r != null && SystemClock.uptimeMillis() - Monitor.lastTime < 400 && ownHive(r); };
        if (run(pine ? Moves.natroPineHome(p.hiveSlot, p.moveSpeed) : route("wf-" + p.field), "walk home", atPad)) Log.add("hive: own pad's prompt seen on the way, stopped the walk there");
        nav = "Finding own hive pad";
        natroFindHive();
        if (p.autoConvert) { phase("Converting", "convert"); convert(); tripConverted = true; }
    }

    /** The character must be standing still on the ground: settle, press the slot key long enough for a frame to
     *  see it, then once more (a second press just re-places it on the same spot, so a missed first press is covered). */
    private void placeSprinkler() { placeSprinkler(true, inwardKeys()); }
    /** Keys that lead from the sprinkler spot back into the field (before the field camera turns). */
    private Moves.Key[] inwardKeys() {
        String l = p.sprinklerLoc; java.util.List<Moves.Key> k = new java.util.ArrayList<>();
        if (l.contains("Upper")) k.add(Moves.Key.S); else if (l.contains("Lower")) k.add(Moves.Key.W);
        if (l.contains("Left")) k.add(Moves.Key.D); else if (l.contains("Right")) k.add(Moves.Key.A);
        return k.isEmpty() ? new Moves.Key[]{Moves.Key.S} : k.toArray(new Moves.Key[0]);
    }
    /** Press the sprinkler key; if Bee Swarm answers with its red "You must be standing in a field" banner, step
     *  further into the field and try again (up to 3 times). Returns whether it was placed (no banner seen). */
    private boolean pressSprinkler(Moves.Key[] inward) {
        for (int attempt = 1; attempt <= 4; attempt++) {
            sleep(600);
            boolean a = in.key(android.view.KeyEvent.KEYCODE_1 + p.sprinklerSlot - 1, 150); sleep(800);
            int rows = redBanner();
            if (rows < 4) {
                int n = p.sprinklerCount();
                // a second press re-places a single sprinkler on the same spot; with Silver+ it would stack two on one spot
                boolean b = n == 1 && in.key(android.view.KeyEvent.KEYCODE_1 + p.sprinklerSlot - 1, 150); sleep(400);
                Log.add("sprinkler: placed with key " + p.sprinklerSlot + (attempt > 1 ? " on try " + attempt : "") + " (" + (a ? "sent" : "NOT sent") + (n == 1 ? ", " + (b ? "sent" : "NOT sent") : "") + ")");
                sprinklerPlaced = true;
                if (n > 1) placeMore(n - 1, inward);
                return true;
            }
            stats.corrections++; saveGuardFrame("sprinkler_refused");
            double in2 = 1 + attempt;   // 2, 3, 4, 5 tiles: live, 3×2 tiles weren't enough after the route came in off-line
            Log.add(String.format(Locale.US, "sprinkler: Bee Swarm says we're not standing in the field (try %d), stepping %.0f tiles in", attempt, in2));
            walkHold(new Moves.Step('H', inward, 1.0, (int) Moves.tileMs(in2, p.moveSpeed), null));
            long t0 = SystemClock.uptimeMillis();   // let the old banner fade so it isn't read as a new refusal
            while (redBanner() >= 4 && SystemClock.uptimeMillis() - t0 < 6000) sleep(400);
        }
        Log.add("sprinkler: still refused after 4 tries, farming without it");
        return false;
    }
    /** Silver / Golden / Diamond (Discord: "placed once", silver sprinkler): each extra one 2 tiles further into the
     *  field, then back to the first spot so the pattern starts where it always does. */
    private void placeMore(int extra, Moves.Key[] inward) {
        Moves.Key[] back = new Moves.Key[inward.length];
        for (int i = 0; i < inward.length; i++) back[i] = inward[i] == Moves.Key.W ? Moves.Key.S : inward[i] == Moves.Key.S ? Moves.Key.W : inward[i] == Moves.Key.A ? Moves.Key.D : Moves.Key.A;
        for (int k = 1; k <= extra; k++) {
            walkHold(new Moves.Step('H', inward, 1.0, (int) Moves.tileMs(2, p.moveSpeed), null));
            sleep(500);
            boolean ok = in.key(android.view.KeyEvent.KEYCODE_1 + p.sprinklerSlot - 1, 150); sleep(600);
            Log.add("sprinkler: extra " + k + "/" + extra + " (" + (ok ? "sent" : "NOT sent") + ")");
        }
        walkHold(new Moves.Step('H', back, 1.0, (int) Moves.tileMs(2.0 * extra, p.moveSpeed), null));
    }
    /** Rows of a red error banner on the next frame (0 if none or no frame in 1.5 s). */
    private int redBanner() {
        long since = Monitor.bannerTime; Monitor.bannerWanted = true;
        long end = SystemClock.uptimeMillis() + 1500;
        while (Monitor.bannerTime == since && SystemClock.uptimeMillis() < end) SystemClock.sleep(40);
        return Monitor.bannerTime == since ? 0 : Monitor.bannerRows;
    }
    private boolean sprinklerPlaced, arrivalOk;
    private int arrivalFails;
    private void placeSprinkler(boolean walk, Moves.Key[] inward) {
        sprinkler = null; sprinklerPlaced = false;
        if (walk) walkToSprinklerSpot();   // also where the pattern starts, so it runs with "None" too
        if (p.sprinklerCount() == 0) { Log.add("sprinkler: none set, not placing"); return; }
        boolean learn = p.sprinklerAnchor && p.keyboard();
        int[] before = null;
        if (learn) { in.hold(MacroService.set(new Moves.Key[]{Moves.Key.S}), Moves.tileMs(3, p.moveSpeed)); sleep(500); before = frames3(); in.hold(MacroService.set(new Moves.Key[]{Moves.Key.W}), Moves.tileMs(3, p.moveSpeed)); }
        if (!pressSprinkler(inward) || !learn) return;
        // step back 3 tiles again: the sprinkler is now on the spot just photographed empty
        in.hold(MacroService.set(new Moves.Key[]{Moves.Key.S}), Moves.tileMs(3, p.moveSpeed)); sleep(500);
        int[] after = frames3();
        Remap.Out ro = Monitor.remap;
        if (before != null && after != null && ro != null && before.length == after.length) {
            int w = ro.w, h = ro.h, py = Math.round(p.playerY * h), half = (int) (0.54 * h);   // ±0.54 x height around the centre = the tablet's box, any shape
            sprinkler = SprinklerFinder.learn(before, after, w, h, Math.max(0, w / 2 - half), (int) (0.18 * h), Math.min(w, w / 2 + half), Math.max((int) (0.3 * h), py - 20));
            if (sprinkler != null && (sprinkler.learnedW > 0.3 * h || sprinkler.learnedH > 0.3 * h)) {   // live: 445×423 px under the tree = leaves that moved, not a sprinkler
                Log.add("sprinkler: the change ahead was " + sprinkler.learnedW + "×" + sprinkler.learnedH + " px, far too big for a sprinkler (leaves moving?) - not used");
                sprinkler = null;
            }
            saveGuardFrame("sprinkler");
            Log.add(sprinkler == null ? "sprinkler: couldn't learn its look (no single new object ahead) - no sprinkler anchor this trip"
                    : "sprinkler: learned (" + sprinkler.learnedW + "×" + sprinkler.learnedH + " px at " + sprinkler.learnedX + "," + sprinkler.learnedY + ")");
        } else Log.add("sprinkler: no frames to learn from");
        in.hold(MacroService.set(new Moves.Key[]{Moves.Key.W}), Moves.tileMs(3, p.moveSpeed));   // back onto the sprinkler
    }

    private SprinklerFinder sprinkler;
    /** Median of three fresh frames (moving bees drop out), or null. Tablet frames come remapped, phone frames as they are. */
    private int[] frames3() {
        int[][] f = new int[3][];
        long since = SystemClock.uptimeMillis();
        for (int i = 0; i < 3; i++) {
            if (Monitor.fresh(since, 1500) == null) return null;
            since = Monitor.lastTime;
            Remap.Out ro = Monitor.remap;
            if (ro == null || (i > 0 && ro.px.length != f[0].length)) return null;   // any screen shape; all three the same size
            f[i] = ro.px.clone();
            sleep(120);
        }
        return SprinklerFinder.median(f[0], f[1], f[2]);
    }
    /** Where the sprinkler is now, {x, y} on the remapped frame, or null when it isn't in view. */
    private double[] sprinklerNow() {
        Remap.Out ro = Monitor.remap;
        if (sprinkler == null || ro == null || ro.px.length != ro.w * ro.h) return null;
        return sprinkler.locate(ro.px, ro.w, ro.h, (int) (0.16 * ro.h), ro.h - 60, 0.8);   // real 0.94 in tests; hive look-alikes up to 0.72
    }

    /** Natro nm_setSprinkler: from the field entry walk to the chosen spot (e.g. Upper Left, distance 7). On blue
     *  fields the walk stops early when the field map shows that edge coming close (the old full walk climbed the hedge). */
    private void walkToSprinklerSpot() {
        String loc = p.sprinklerLoc;
        if (loc.equalsIgnoreCase("Center")) return;
        double[] t = Routes.sprinklerTiles(p.field, p.sprinklerDist);
        Moves.Key fb = loc.contains("Upper") ? Moves.Key.W : loc.contains("Lower") ? Moves.Key.S : null;
        Moves.Key lr = loc.contains("Left") ? Moves.Key.A : loc.contains("Right") ? Moves.Key.D : null;
        // Pine Tree's upper corners are under the leaves: the field map reads the shade as the edge and stopped this walk
        // halfway (5 of 7.9 tiles), so Skillet never started in its corner. There the hedges are the limit instead.
        boolean shadeLies = p.field.equals("pinetree");
        for (Moves.Key k : new Moves.Key[]{fb, lr}) {
            if (k == null) continue;
            double tiles = k == fb ? t[0] : t[1];
            double[] w = walkUntilWall(k, tiles, !shadeLies);
            Log.add(String.format(Locale.US, "sprinkler: walked %s %.1f of %.1f tiles%s", k, w[0], tiles, w[1] == 1 ? " (stopped at the hedge)" : w[1] == 2 ? " (field edge close)" : ""));
        }
    }

    /** Walk one key a distance (Haste-corrected, like the patterns) but stop at a wall (the world stops moving) or, if
     *  asked, where the field map shows the edge close. Returns {tiles walked, 0 done / 1 wall / 2 edge}. Never jumps. */
    private double[] walkUntilWall(Moves.Key k, double tiles, boolean edgeStop) {
        final double total = Moves.tileMs(tiles, p.moveSpeed);
        final double[] left = {total}, cur = {liveSpeed}; final long t0 = SystemClock.uptimeMillis(); final long[] last = {t0}, still = {0};
        final int[] why = {0};
        in.holdUntil(MacroService.set(new Moves.Key[]{k}), (long) (total / Math.max(0.5, cur[0])) + 4000, () -> {
            long now = SystemClock.uptimeMillis();
            left[0] -= (now - last[0]) * cur[0]; last[0] = now;
            double nv = speedNow(cur[0]); if (nv > 0) cur[0] = nv;
            if (left[0] <= 150 * cur[0]) return true;
            if (now - t0 > 700 && now - Monitor.motionTime < 1500) {
                if (Monitor.motion >= STILL) still[0] = 0;
                else { if (still[0] == 0) still[0] = now; if (now - still[0] > 450) { why[0] = 1; return true; } }
            }
            if (edgeStop && fieldOn()) {
                FieldMap.Read r = Monitor.fieldRead;
                if (r != null && r.state == FieldMap.State.INSIDE) {
                    int edge = k == Moves.Key.W ? r.up : k == Moves.Key.S ? r.down : k == Moves.Key.A ? r.left : r.right;
                    if (edge <= p.fieldMargin) { why[0] = 2; return true; }
                }
            }
            return false;
        });
        if (why[0] == 0 && left[0] > 20) { in.hold(MacroService.set(new Moves.Key[]{k}), Math.round(left[0] / cur[0])); left[0] = 0; }
        liveSpeed = cur[0];
        checkpoint();
        return new double[]{Math.max(0, total - Math.max(0, left[0])) / Moves.tileMs(1, p.moveSpeed), why[0]};
    }

    private long lastMondo = -1_000_000_000L;
    /** Natro's rule: Mondo Chick appears at the top of each hour; go during minutes 0-13, at most once per 16 min. */
    private boolean mondoDue() {
        return p.mondoBuff && p.keyboard() && System.currentTimeMillis() / 60_000 % 60 < 14
                && SystemClock.elapsedRealtime() - lastMondo > 960_000;
    }

    private boolean ownHive(Vision.Result x) { return promptKnown(x) && (x.prompt == Vision.Prompt.TAP_BLUE || x.prompt == Vision.Prompt.TAP_RED || x.prompt == Vision.Prompt.BANNER_BLUE); }

    /** Natro nm_findHiveSlot: if our hive prompt isn't up, walk Left up to 15 s until it is; confirm 0.5 s later and
     *  step back Right 1.5 tiles if overrun (≤10 times). */
    private void natroFindHive() {
        // a frame older than 0.4 s doesn't count: at walking speed that is 3 tiles of hive row ago
        java.util.function.BooleanSupplier at = () -> { Vision.Result r = Monitor.last; return r != null && SystemClock.uptimeMillis() - Monitor.lastTime < 400 && ownHive(r); };
        Vision.Result now = waitFor(this::sure, 2000);
        if (now == null || !ownHive(now)) {
            // Natro holds Left up to 15 s; the route ends next to the pad (and now stops on it), so 6 s left (~50 tiles,
            // five hives), then back right as far again and on: a pad passed on the way is found from the other side
            boolean found = in.holdUntil(MacroService.set(new Moves.Key[]{Moves.Key.A}), 6_000, at);
            checkpoint();
            if (!found && p.navGuard) {
                decide("Finding hive", "Own hive not found to the left; searching right.", "no hive prompt for 6 s", "reset if not found", 0.5);
                found = in.holdUntil(MacroService.set(new Moves.Key[]{Moves.Key.D}), 16_000, at);
                checkpoint();
            }
            if (!found) throw new Recover("own hive not found along the hive row");
        }
        if (padHere()) { Log.add("hive: found own pad"); return; }
        // Slid past the pad before the key came up (screen frames arrive ~8 a second, so the stop is late; with Haste
        // later still). Natro steps back 1.5 tiles at a time, which here jumped over the pad both ways until it gave
        // up. Sweep back in short Haste-corrected steps instead, checking a fresh frame after each, then the other way.
        // Live: it had stopped SHORT of the pad (found after 6.6 tiles further left), which is also why Natro's
        // right-only steps used to end in "pad overrun". So: a little further left first, then right, then left again.
        double moved = 0;
        Moves.Key[] legs = {Moves.Key.A, Moves.Key.D, Moves.Key.A}; double[] reaches = {3, 8, 10};
        for (int leg = 0; leg < legs.length; leg++) {
            Moves.Key k = legs[leg]; double reach = reaches[leg];
            for (double d = 0; d < reach; d += 0.6) {
                walkHold(new Moves.Step('H', new Moves.Key[]{k}, 1.0, (int) Moves.tileMs(0.6, p.moveSpeed), null));
                moved += 0.6;
                if (padHere()) { Log.add(String.format(Locale.US, "hive: found own pad after sweeping %s %.1f tiles", k, d + 0.6)); return; }
            }
        }
        throw new Recover("own hive pad overrun");
    }
    /** Standing on our pad: the prompt on a frame taken after we stopped (two tries, frames arrive ~8 a second). */
    private boolean padHere() {
        // let the character stop sliding first (live: the prompt showed on arrival, then the slide carried it off the
        // pad and converting found no "Make Honey"), then the prompt must be there on 2 frames in a row
        sleep(350);
        int hits = 0;
        for (int i = 0; i < 3 && hits < 2; i++) {
            Vision.Result r = Monitor.fresh(SystemClock.uptimeMillis(), 900);
            if (r != null && sure(r) && ownHive(r)) hits++; else hits = 0;
        }
        return hits >= 2;
    }

    static final double FACING_PAD = 0.35;     // straight on from the hive pad: 0.40-0.74 live
    static final double FACING_SPAWN = 0.18;   // straight on from the central spawn: about 0.25; any other angle at most 0.14
    static final int FIELD_TILT = 6;           // PgUp steps: looks down over Pine Tree's hedges, so the flowers are in view

    /** Camera pose for Natro's paths: Shift Lock on (without it the tablet's camera swings round while strafing), fixed
     *  tilt and zoom, then turn in 45° steps until the hives are straight ahead. A respawn can land on the hive pad or on
     *  the central spawn in front of the hives; from the spawn, walk to the pad as Natro does. */
    private void alignCamera() {
        ensureShiftLock();
        in.tilt(-11); in.tilt(5);
        if (p.camGuided) { guidedCamera(); return; }   // this device's camera needs the guided reset (found earlier)
        for (int i = 0; i < 15; i++) { in.key(android.view.KeyEvent.KEYCODE_I, 60); SystemClock.sleep(70); }
        for (int i = 0; i < 3; i++) { in.key(android.view.KeyEvent.KEYCODE_O, 60); SystemClock.sleep(70); }
        sleep(600);
        int best = 0; double top = -1;
        for (int turn = 0; turn < 8; turn++) {     // a full circle ends where it began
            double s = readFacing();
            if (s > top) { top = s; best = turn; }
            if (s >= 0.6) { decideFacing(s, turn); return; }
            in.rotate(1);
            sleep(450);
        }
        if (top < FACING_SPAWN) {   // the fixed tablet zoom doesn't suit this device's camera: find the view by eye
            Log.add(String.format(Locale.US, "camera: fixed zoom found no hive view here (best %.2f), searching by eye", top));
            guidedCamera();
            p.camGuided = true; App.save(in);   // next resets go straight to the guided search on this device
            Log.add("camera: guided reset works on this device, using it from now on");
            return;
        }
        if (best != 0) { in.rotate(best > 4 ? best - 8 : best); sleep(450); }
        double s = readFacing();
        if (s < FACING_PAD) {
            decide("Walking to hive", "Respawned at the central spawn; walking to the hive pad.", String.format(Locale.US, "hive view %.2f", s), "face the hive from the pad", 0.8);
            run(Moves.natroSpawnToHive(p.hiveSlot, p.moveSpeed), "spawn to hive");
            sleep(500);
            s = readFacing();
            if (s < FACING_PAD) throw new Recover(String.format(Locale.US, "not at the hive pad after walking from spawn (%.2f)", s));
        }
        decideFacing(s, best);
    }

    /** Screen-guided camera reset (any device): zoom all the way out (the same start everywhere), turn to where the
     *  hive row's gold is, then zoom in a step at a time while the hive view improves and stop at its best. Measured
     *  on the S23: 5 zoom-ins from far out gave hive view 0.57 (pad threshold 0.35); the tablet's fixed 15 in / 3 out
     *  left it far out and level (best 0.15). */
    private void guidedCamera() {
        for (int i = 0; i < 20; i++) { in.key(android.view.KeyEvent.KEYCODE_O, 60); SystemClock.sleep(70); }
        sleep(600);
        int bestTurn = 0; double topRow = -1;
        for (int turn = 0; turn < 8; turn++) {
            double g = 0;
            for (int i = 0; i < 2; i++) { checkpoint(); Monitor.fresh(SystemClock.uptimeMillis(), 1000); g = Math.max(g, Monitor.goldRow); }
            if (g > topRow) { topRow = g; bestTurn = turn; }
            in.rotate(1); sleep(450);
        }
        if (bestTurn != 0) { in.rotate(bestTurn > 4 ? bestTurn - 8 : bestTurn); sleep(450); }
        Log.add(String.format(Locale.US, "camera: hive row %.2f after %d turns, zooming in by eye", topRow, bestTurn));
        double f = zoomToHive();
        // Where did the respawn put us? Bee Swarm respawns you on your hive's pad when you own one, else at the central
        // spawn (live: every spawn landing came before "claimed slot 3"). Far hives = the spawn, but how far depends on
        // the device: tablet pad step 1 / spawn step 3, the S23's pad ~5. A fixed "step 3+" sent phones standing on
        // their own pad on the spawn walk, then reset, over and over (Discord, Galaxy A26: "respawn loops once it
        // verifies my hive"). So: owning a hive this session = the pad; else this device's learned pad step + 2;
        // else (first time here) the tablet's rule plus "no Make Honey prompt", and the walk teaches the pad step.
        int step0 = zoomStep;
        Vision.Result pr = !ownsHive && p.padZoomStep <= 0 && step0 >= 3
                ? waitFor(x -> promptKnown(x) && (x.prompt == Vision.Prompt.TAP_BLUE || x.prompt == Vision.Prompt.TAP_RED), 2500) : null;   // the tap prompt only: the blue banner read "yes" at the spawn live
        boolean far = ownsHive ? false : p.padZoomStep > 0 ? step0 >= p.padZoomStep + 2 : step0 >= 3 && pr == null;
        boolean spawn = f >= FACING_SPAWN && (f < FACING_PAD || far);
        Log.add("camera: " + (spawn ? "at the central spawn" : "at our hive pad") + String.format(Locale.US, " (view %.2f, zoom step %d%s%s)", f, step0,
                ownsHive ? ", we own a hive" : p.padZoomStep > 0 ? ", this device's pad step " + p.padZoomStep : "", pr != null ? ", " + pr.prompt + " prompt" : ""));
        if (spawn) {
            decide("Walking to hive", "Respawned at the central spawn; walking to the hive pad.", String.format(Locale.US, "hive view %.2f", f), "face the hive from the pad", 0.8);
            run(Moves.natroSpawnToHive(p.hiveSlot, p.moveSpeed), "spawn to hive");
            sleep(500);
            for (int i = 0; i < 20; i++) { in.key(android.view.KeyEvent.KEYCODE_O, 60); SystemClock.sleep(70); }
            sleep(500);
            f = zoomToHive();
            if (f >= FACING_PAD && zoomStep < step0) learnPadStep(zoomStep, "after walking from the spawn");   // closer now: it was the spawn
            else if (far && f < FACING_PAD) {   // the walk lost the hives: the respawn had put us on our pad after all
                learnPadStep(step0, "the spawn walk lost the hives, so the respawn was on our pad");
                throw new Recover(String.format(Locale.US, "walked from the spawn but was on the pad (zoom step %d is this device's pad)", step0));
            }
        } else if (f >= FACING_PAD && (ownsHive || pr != null)) learnPadStep(step0, ownsHive ? "we own a hive" : "our Make Honey prompt is up");
        if (f < FACING_PAD) throw new Recover(String.format(Locale.US, "could not find the hives with the camera (guided, best %.2f)", f));
        decideFacing(f, bestTurn);
    }

    /** We own a hive in this server (claimed it, saw its prompt, or converted at it): respawns land on its pad. A
     *  rejoin puts us in a new server, where we own nothing until the claim. */
    private boolean ownsHive;
    private void learnPadStep(int step, String why) {
        if (step <= 0 || step == p.padZoomStep) return;
        Log.add("camera: this device's pad is at zoom step " + step + " (was " + (p.padZoomStep > 0 ? p.padZoomStep : "unknown") + "): " + why);
        p.padZoomStep = step; App.save(in);
    }

    /** Zoom in one step at a time while the hive view gets better; stop just after its peak and step back to it. */
    private int zoomStep;   // zoom-in steps from fully out to the best hive view, set by zoomToHive
    private double zoomToHive() {
        double best = -1; int bestStep = 0, step = 0;
        for (step = 1; step <= 16; step++) {
            in.key(android.view.KeyEvent.KEYCODE_I, 60);
            sleep(250);
            double f = readFacing();
            if (f > best) { best = f; bestStep = step; }
            if (best >= FACING_PAD && f < 0.8 * best) break;   // past the best view
        }
        // zooming in stops at Roblox's closest zoom, so stepping back out the same count overshot (0.55 at step 3 read
        // 0.20 after, live): go all the way out, the same start as above, and back in exactly bestStep times
        for (int k = 0; k < 20; k++) { in.key(android.view.KeyEvent.KEYCODE_O, 60); SystemClock.sleep(70); }
        sleep(900);   // the zoom-out keeps animating: zooming in at 400 ms landed far out (0.71 read 0.01, live)
        for (int k = 0; k < bestStep; k++) { in.key(android.view.KeyEvent.KEYCODE_I, 60); sleep(250); }
        zoomStep = bestStep;
        sleep(400);
        // one re-check after stepping back read a bit under the peak (0.40 → 0.33, bees/balloon in front) and threw a
        // good view away: reset twice in a row live (2026-11-22). Re-check up to 3 times; near the peak = the peak.
        double f = readFacing();
        for (int i = 0; i < 2 && f < 0.8 * best; i++) { sleep(300); f = Math.max(f, readFacing()); }
        Log.add(String.format(Locale.US, "camera: best hive view %.2f at zoom step %d (now %.2f)", best, bestStep, f));
        return f >= 0.8 * best ? Math.max(f, best) : f;
    }

    private double readFacing() {
        double top = 0;
        for (int i = 0; i < 2; i++) { checkpoint(); Monitor.fresh(SystemClock.uptimeMillis(), 1000); top = Math.max(top, Monitor.facing); }
        return top;
    }

    private void decideFacing(double score, int turns) {
        decide("Camera reset", "Camera faces the hive, the pose Natro's paths expect.", String.format(Locale.US, "hive view %.2f after %d turns", score, turns), "walk to the ramp", 1);
    }



    private void cycle() {
        if (p.keyboard()) { cycleKb(); return; }
        phase("Respawn → hive", "nav"); nav = "Respawn → own hive";
        respawn();
        phase("Verify hive", "nav");
        verifyHive();
        if (p.autoConvert) { phase("Converting", "convert"); convert(); }
        if (!p.autoReturn) { pause("Auto return to field is off"); checkpoint(); }
        ensureShiftLock();
        waitHasteClear();
        phase("To Pine Tree", "nav"); nav = "Your route: hive → cannon → glide to Pine Tree";
        run(p.keyboard() ? Moves.toPineTreeKb() : Moves.toPineTree(), "your route to Pine Tree");
        phase("Farming", "farm"); nav = "Pine Tree Forest (verifying)";
        farm();
        if (!p.returnByCannon) return;                           // next cycle() respawns at the hive with the pollen
        phase("To hive", "nav"); nav = "Your route: Pine Tree → cannon → glide → hive";
        run(Moves.toHive(), "your route to the hive");
        nav = "At own hive (found by its prompt)";
        if (p.autoConvert) { phase("Converting", "convert"); convert(); }
    }

    private void run(List<Moves.Step> steps, String what) { run(steps, what, null); }
    /** stopAt: checked while walking; when true the route ends there (e.g. our hive pad's prompt is up). Returns
     *  whether it stopped early. */
    private boolean run(List<Moves.Step> steps, String what, java.util.function.BooleanSupplier stopAt) {
        walkStop = stopAt; walkStopped = false;
        try {
            for (int i = 0; i < steps.size(); i++) {
                checkpoint();
                Moves.Step s = steps.get(i);
                step = what + " " + (i + 1) + "/" + steps.size() + ": " + s;
                exec(s);
                if (walkStopped || (stopAt != null && stopAt.getAsBoolean())) { in.emergencyRelease(); in.arm(); return true; }
                guardBag(watchScreen());
            }
            return false;
        } finally { walkStop = null; }
    }
    private volatile java.util.function.BooleanSupplier walkStop; private volatile boolean walkStopped;

    // ------------------------------------------------------------------ screen guard (keyboard trip)
    static final double STILL = 6;          // motion below this = the world isn't moving (walking measured 39-93)
    private double bagSeen = -1, bagBest = -1; private int bagDrops; private long lastRise, outsideSince;

    /** Pollen only falls at the hive. A big drop elsewhere (3 readings) means we died or were reset: start over. */
    private void guardBag(Vision.Result r) {
        if (Monitor.pollenChanged > lastRise && (phase.startsWith("Farming") || fieldRecovering)) lastRise = Monitor.pollenChanged;   // the counter's digits moved: pollen is coming in
        if (!p.navGuard || !p.keyboard() || r == null || !bagKnown(r)) return;
        // pollen also reads near 0 during normal farming (honey-making effects, bigger bag): only an empty bag AT the hive is a death
        if (bagSeen >= 0 && NavGuard.bagEmptied(bagSeen, r.pollen) && ownHive(r)) {
            if (++bagDrops >= 3) { String msg = String.format(Locale.US, "bag fell from %.0f%% to %.0f%%: character died or was reset", bagSeen, r.pollen); resetBagWatch(); throw new Recover(msg); }
            return;
        }
        bagDrops = 0; bagSeen = r.pollen;
        if (bagBest < 0) bagBest = r.pollen;
        if (NavGuard.bagRose(bagBest, r.pollen)) lastRise = SystemClock.uptimeMillis();
        bagBest = NavGuard.bagBase(bagBest, r.pollen);
    }
    private void resetBagWatch() { bagSeen = bagBest = -1; bagDrops = 0; lastRise = SystemClock.uptimeMillis(); outsideSince = 0; }

    private static Moves.Key keyFor(NavGuard.Dir d) { return d == NavGuard.Dir.LEFT ? Moves.Key.A : d == NavGuard.Dir.RIGHT ? Moves.Key.D : Moves.Key.W; }
    private NavGuard.View nextView() { Monitor.fresh(SystemClock.uptimeMillis(), 1500); return Monitor.view; }

    /** Find Pine Tree by sight: walk toward the side showing its flowers; if none are visible, look around in 90°
     *  steps (the camera is put back afterwards). True once two frames in a row show the field all around. */
    private boolean seekPine(long budgetMs, String why) {
        long walked = 0; int yaw = 0, looks = 0, inRow = 0;
        try {
            while (walked <= budgetMs) {
                checkpoint();
                NavGuard.View v = nextView();
                if (v == null) return false;
                if (v.state == NavGuard.State.IN_FIELD || v.state == NavGuard.State.NEAR_EDGE) { if (++inRow >= 2) { if (walked > 0) { stats.corrections++; Log.add("guard: back in Pine Tree after " + walked + " ms (" + why + ")"); } return true; } continue; }
                inRow = 0;
                if (v.toward == NavGuard.Dir.NONE) {
                    if (++looks > 3) break;
                    decide("Looking for the field", "No Pine Tree flowers in view; turning the camera to search.", v.toString(), "walk toward flowers, or reset", 0.5);
                    in.rotate(2); yaw += 2; sleep(500);
                    continue;
                }
                decide("Returning to the field", "Pine Tree flowers seen " + v.toward + "; walking to them.", v.toString(), "resume when surrounded by flowers", 0.7);
                in.hold(MacroService.set(new Moves.Key[]{keyFor(v.toward)}), 350); walked += 350;
                sleep(120);
            }
            Log.add("guard: Pine Tree not found (" + why + ", walked " + walked + " ms, looked " + looks + "×)");
            return false;
        } finally { if (yaw % 8 != 0) in.rotate(-yaw); }
    }

    // ------------------------------------------------------------------ staying inside the field
    private final FieldMap.Controller fieldCtl = new FieldMap.Controller();
    private volatile boolean fieldRecovering; private volatile String moveDir = "—";
    private volatile int fieldOuts, stepsSkipped;
    private long farmStart;
    // where the field middle was on the last good reading, and the camera turn (45° steps) at that moment
    private double lastInX, lastInY; private int lastInYaw, patYaw; private boolean haveLastIn;

    private static int stepX(Moves.Key[] k) { int x = 0; for (Moves.Key c : k) { if (c == Moves.Key.D) x++; else if (c == Moves.Key.A) x--; } return x; }
    private static int stepY(Moves.Key[] k) { int y = 0; for (Moves.Key c : k) { if (c == Moves.Key.W) y++; else if (c == Moves.Key.S) y--; } return y; }
    private static String dirName(int x, int y) { String s = (y > 0 ? "forward " : y < 0 ? "back " : "") + (x > 0 ? "right" : x < 0 ? "left" : ""); return s.isEmpty() ? "—" : s.trim(); }
    private boolean fieldOn() { return p.navGuard && p.keyboard() && p.edgeCorrect && Routes.blueField(p.field); }   // the field map reads blue/white fields

    private void walkField(int x, int y, long ms) {
        java.util.List<Moves.Key> k = new java.util.ArrayList<>();
        if (x > 0) k.add(Moves.Key.D); else if (x < 0) k.add(Moves.Key.A);
        if (y > 0) k.add(Moves.Key.W); else if (y < 0) k.add(Moves.Key.S);
        if (k.isEmpty()) return;
        moveDir = dirName(x, y);
        in.hold(MacroService.set(k.toArray(new Moves.Key[0])), ms);
        Monitor.fieldTrack.clear();                 // judge the result on new frames only
    }

    /** A pattern step that would run into a nearby edge is skipped; the correction below then turns us back. */
    private boolean stepBlocked(Moves.Step s) {
        if (!fieldOn() || s.type != 'W' || s.keys == null || wallBound()) return false;   // corner mode / Skillet: the walls keep the pattern in
        int x = stepX(s.keys), y = stepY(s.keys);
        moveDir = dirName(x, y);
        FieldMap.Read r = Monitor.fieldTrack.steady(SystemClock.uptimeMillis());
        if (r == null || r.state != FieldMap.State.INSIDE || !FieldMap.blocked(r, x, y, FieldMap.stepLimit(s.tiles))) return false;
        stepsSkipped++;
        return true;
    }

    /** After each pattern step: gentle steering near an edge, full recovery once really outside. */
    private void fieldControl() {
        if (!fieldOn()) return;
        long now = SystemClock.uptimeMillis();
        FieldMap.Read r = Monitor.fieldTrack.steady(now);
        if (r == null) return;
        if (r.state == FieldMap.State.OUTSIDE) {
            if (outsideSince == 0) outsideSince = now;
            if (now - outsideSince >= 2500 && (now - lastRise >= 8000 || r.wrongField)) recoverField();   // pollen still rising = still in the field (unless it is another field's)
            return;
        }
        outsideSince = 0;
        if (r.state == FieldMap.State.INSIDE && r.fieldCells >= 40) { lastInX = r.cx; lastInY = r.cy; lastInYaw = patYaw; haveLastIn = true; }
        if (wallBound()) return;   // no edge steering at a walled corner (it pulled patterns out from under the tree); outside = recovery above
        fieldCtl.margin = p.fieldMargin;
        double v = speedNow(1.0);
        FieldMap.Controller.Move m = fieldCtl.update(r, now, v > 0 ? v : 1);
        if (m == null) return;
        stats.corrections++;
        prevResult = "field correction: " + dirName(m.x, m.y) + " (" + r + ")";
        Log.add("field: " + prevResult);
        walkField(m.x, m.y, m.ms);
    }

    private long wrongSince; private int wrongSeen; private long wrongSample;
    /** While farming: another field all around for 8 s (pollen rises there too, so that is no excuse), or no pollen
     *  at all for 60 s (stuck somewhere) = reset and start the trip over. */
    private void checkLost() {
        long now = SystemClock.uptimeMillis();
        // The game names the field we gather in ("+N Honey (from Spider Field)"): another field named for 6 s with no
        // Pine Tree Forest in between = wrong field, whatever the flower colours say (live: blue bubbles passed Spider)
        if (p.field.equals("pinetree") && Monitor.msgOtherSince > 0 && now - Monitor.msgOther < 2500 && now - Monitor.msgOtherSince > 6000) {
            saveGuardFrame("wrongfield_msg"); stats.fieldExits++; Monitor.msgOtherSince = 0;
            throw new Recover("the game says we're gathering in another field (its messages name a Field, not Pine Tree Forest)");
        }
        FieldMap.Read r = Monitor.fieldRead;
        if (r != null && Routes.blueField(p.field) && now - wrongSample >= 400) {   // red flowers are only "wrong" around blue fields
            wrongSample = now;
            if (r.wrongField) { if (wrongSince == 0) wrongSince = now; wrongSeen++; }
            else if (r.state == FieldMap.State.INSIDE && r.fieldCells >= 40) { wrongSince = 0; wrongSeen = 0; }
            if (wrongSince > 0 && now - wrongSince > 8000 && wrongSeen >= 8) {
                saveGuardFrame("wrongfield"); stats.fieldExits++;
                wrongSince = 0; wrongSeen = 0;
                throw new Recover("in another field (red flowers all around for 8 s): resetting to start over");
            }
        }
        if (stalledFull()) return;   // farmLoop walks home with it
        if (!noHudNow && now - farmStart > 60_000 && now - lastRise > 60_000) {   // no-HUD: nothing tells pollen is coming in
            saveGuardFrame("nopollen");
            throw new Recover("no pollen for 60 s while farming (stuck or not in a field): resetting to start over");
        }
    }

    /** A full bag stops the pollen too. When the bar reads a bit under the limit (93% vs 95%), the 60 s "no pollen"
     *  reset fired and threw away a full bag (Discord: "when I get full the macro tries to reset"). Near the limit
     *  with nothing coming in for 30 s = full. ponytail: fixed 15-point window, make it a setting if bags vary more */
    private boolean stalledFull() {
        long now = SystemClock.uptimeMillis();
        return lastPct >= 60 && lastPct >= p.backpackThreshold - 15 && now - farmStart > 30_000 && now - lastRise > 30_000;
    }

    /** Pattern is suspended until several frames in a row confirm we are back on the field. */
    private void recoverField() {
        fieldRecovering = true; fieldOuts++;
        String back = phase; phase = "Recovering field";
        saveGuardFrame("outside");
        Log.add("field: outside the field, recovering (#" + fieldOuts + ")");
        decide("Recovering", "Left the field; the pattern is on hold until we are back inside.", String.valueOf(Monitor.fieldRead), "resume " + patName(), 0.8);
        int good = 0, yaw = 0, looks = 0, memoryTries = 0; boolean sawSprinkler = false; long t0 = SystemClock.uptimeMillis();
        try {
            while (true) {
                checkpoint();
                long now = SystemClock.uptimeMillis();
                if (now - farmStart > p.fieldMinutes * 60_000L) { Log.add("field: time limit reached during recovery"); return; }
                Vision.Result at = Monitor.last;
                if (now - t0 > 30_000 && now - lastRise > 30_000 && at != null && ownHive(at)) throw new Recover("back at the hive during farming: the character died");
                if (now - t0 > 90_000) throw new Recover("could not get back to Pine Tree in 90 s");   // lost in another part of the map: the route from the hive is the sure way back
                sleep(250);
                guardBag(Monitor.last);
                checkLost();
                FieldMap.Read raw = Monitor.fieldRead;
                if (lastRise > t0 + 1000 && !(raw != null && raw.wrongField)) { Log.add("field: pollen still coming in, so we are on the field: resuming"); return; }
                FieldMap.Read r = Monitor.fieldTrack.steady(SystemClock.uptimeMillis());
                if (r == null) continue;
                if (r.state == FieldMap.State.INSIDE && r.nearest() >= 2) {
                    if (++good >= 3) { Log.add("field: back inside after " + (SystemClock.uptimeMillis() - t0) / 1000 + " s"); lastRise = SystemClock.uptimeMillis(); return; }
                    Monitor.fieldTrack.clear(); continue;
                }
                good = 0;
                double[] sp = sprinklerNow();   // our sprinkler in view = the field is right there
                if (sp != null) {
                    int[] a = anchor(); double dx = sp[0] - a[0], dy = sp[1] - a[1];
                    if (Math.hypot(dx, dy) / a[2] > p.driftTolerance) {
                        walkField(Math.abs(dx) > 0.4 * Math.abs(dy) ? (dx > 0 ? 1 : -1) : 0, Math.abs(dy) > 0.4 * Math.abs(dx) ? (dy > 0 ? -1 : 1) : 0, 350);
                        if (!sawSprinkler) { sawSprinkler = true; Log.add("field: heading back to the sprinkler"); }
                        continue;
                    }
                }
                int[] to = r.fieldCells >= 3 ? FieldMap.toField(r) : new int[]{0, 0};
                if (to[0] != 0 || to[1] != 0) { walkField(to[0], to[1], 350); continue; }
                if (haveLastIn && memoryTries < 4) {   // nothing in view (e.g. up on the hedge): head back the way the field was
                    memoryTries++;
                    double a = Math.toRadians(45.0 * (patYaw + yaw - lastInYaw)), x = lastInX * Math.cos(a) - lastInY * Math.sin(a), y = lastInX * Math.sin(a) + lastInY * Math.cos(a);   // camera turned right by a: things appear turned left
                    walkField(Math.abs(x) < .5 ? 0 : x > 0 ? 1 : -1, Math.abs(y) < .5 ? 0 : y > 0 ? 1 : -1, 500); continue;
                }
                if (looks < 3) { looks++; in.rotate(2); yaw += 2; Monitor.fieldTrack.clear(); sleep(450); continue; }   // nothing in view: look around
                if (yaw != 0) { in.rotate(-yaw); yaw = 0; Monitor.fieldTrack.clear(); }
                looks = 0; walkField(0, -1, 500);
            }
        } finally {
            if (yaw != 0) in.rotate(-yaw);
            fieldRecovering = false; outsideSince = 0; phase = back; Monitor.fieldTrack.clear();
        }
    }

    /** Readout for the panel's expanded view. */
    String fieldDebug() {
        FieldMap.Read r = Monitor.fieldTrack.steady(SystemClock.uptimeMillis());
        if (r == null) return "  waiting for steady frames";
        String edge = r.nearest() == FieldMap.NO_EDGE ? "none in view" : r.nearest() + " cells";
        return String.format(Locale.US, "  %s · from visible field middle: %+.0f right, %+.0f forward\n  edges (cells): left %s, right %s, forward %s, back %s · nearest %s\n  moving: %s · correcting: %s · recovering: %s\n  left the field: %d× · corrections: %d · pattern steps skipped: %d",
                r.state, -r.cx, -r.cy, cell(r.left), cell(r.right), cell(r.up), cell(r.down), edge, moveDir, fieldCtl.correcting ? "yes" : "no", fieldRecovering ? "yes" : "no", fieldOuts, fieldCtl.corrections, stepsSkipped);
    }
    private static String cell(int v) { return v == FieldMap.NO_EDGE ? "—" : "" + v; }

    private int guardFrames;
    private void saveGuardFrame(String why) { Monitor.saveFrame = "guard_" + (guardFrames++ % 8) + "_" + why; }

    private void exec(Moves.Step s) {
        int interrupts = stats.interruptions;   // a user/vision pause cancels the gesture on purpose; that is not an error
        boolean ok;
        switch (s.type) {
            case 'W': {
                long ms = Math.round(s.ms(p.moveSpeed) * p.moveScale);
                // Farming: the same distance, covered at the live Haste speed (shorter press), so the pattern keeps its shape.
                if (hasteFix) { ok = walkHold(new Moves.Step('H', s.keys, 1.0, (int) ms, null)); break; }
                ok = in.hold(MacroService.set(s.keys), ms); break;
            }
            case 'Y': ok = in.replay(Moves.recorded(s.label)); break;
            case 'Q': ok = in.keyScript(RecordedKeys.get(s.label)); break;
            case 'H': ok = walkHold(s); break;
            case 'K': { String[] xy = s.label.split(","); ok = in.hotbar(Moves.hotbarSlot(Integer.parseInt(xy[0])), Integer.parseInt(xy[0]) / 2340f, Integer.parseInt(xy[1]) / 1080f); break; }
            case 'U': ok = walkUntil(s); break;
            case 'R': ok = in.rotate(s.count); patYaw += s.count; Monitor.fieldTrack.clear(); break;
            case 'T': ok = in.tilt(s.count); Monitor.fieldTrack.clear(); break;
            case 'X': ok = in.key(s.count, 80); break;   // a key tap on a route (Natro's E at the blue portal)
            default: sleep(s.count); ok = true;
        }
        checkpoint();
        if (!ok && interrupts == stats.interruptions) { stats.errors++; pause("Input gesture was rejected during: " + s); checkpoint(); }
    }

    /** Current walk speed as a multiple of base 33.35 (BSS Haste: +10% per stack, x10 = 2x), from the stack count
     *  on the Haste icon. Returns -1 when Haste is on but the count can't be read; keeps `prev` on unsure frames. */
    private double speedNow(double prev) {
        Vision.Result r = Monitor.last;
        if (r == null || !sure(r)) return prev;
        double h = !r.haste ? 1.0 : r.hasteStacks > 0 ? 1 + 0.1 * Math.min(10, r.hasteStacks) : -1;
        if (r.haste && h > 0 && h < hasteMul) h = hasteMul;   // stacks don't fall while Haste stays on: a lower number is a misread
        double v = h < 0 ? -1 : Math.round(speedFactor(r, h) * 1000) / 1000.0;
        // A new speed must be read on 2 different frames in a row (live: 1-stack Haste flickered x1.0/x1.1 every
        // ~0.3 s, which skews a 35 s distance-based walk by several tiles).
        // live: 1-stack Haste still flipped x1.0/x1.1 every second on 2-frame confirmation, so 3 different frames now
        long t = Monitor.lastTime;
        if (v != prev) {
            if (v == candSpeed && t != candTime) { candTime = t; if (++candCount >= 2) { candSpeed = Double.NaN; candCount = 0; if (h > 0) hasteMul = h; return v; } return prev; }
            if (v != candSpeed) { candSpeed = v; candTime = t; candCount = 0; }
            return prev;
        }
        candSpeed = Double.NaN; candCount = 0; if (h > 0) hasteMul = h;
        return prev;
    }
    private double candSpeed = Double.NaN, hasteMul = 1; private long candTime; private int candCount;
    /** Walk speed confirmed on the last walk, carried into the next one. */
    private double liveSpeed = 1.0;
    /** Live speed multiple: Haste stacks plus Coconut Haste, Bear Morph, Oil and Super Smoothie (Natro's formula, Moves.speedFactor). */
    private double speedFactor(Vision.Result r, double hasteMul) {
        boolean bear = false, oil = false, smoothie = false;
        for (Buffs.Effect e : Monitor.activeBuffs()) { bear |= e.name.endsWith("Bear Morph"); oil |= e.name.equals("Oil"); smoothie |= e.name.equals("Super Smoothie"); }
        return Moves.speedFactor(p.moveSpeed, p.hastyGuard, p.giftedHasty, hasteMul, r.coconutHaste, bear, false, oil, smoothie);
    }
    /** Which speed buffs are counted right now, for the log. */
    private String speedWhy() {
        Vision.Result r = Monitor.last; StringBuilder b = new StringBuilder();
        if (hasteMul > 1) b.append("Haste x").append(Math.round((hasteMul - 1) * 10)).append(" ");
        if (r != null && r.coconutHaste) b.append("Coconut Haste ");
        for (Buffs.Effect e : Monitor.activeBuffs()) if (e.name.endsWith("Bear Morph") || e.name.equals("Oil") || e.name.equals("Super Smoothie")) b.append(e.name).append(" ");
        return b.length() == 0 ? "base speed" : b.toString().trim();
    }

    /** The hive→field route was recorded at base speed. Over ~35 s of walking (~290 tiles) a 3% speed error is ~9
     *  tiles, i.e. the wrong field, so leave only once Haste (20 s max) has worn off. */
    private void waitHasteClear() {
        long t0 = SystemClock.uptimeMillis(); int same = 0, stacks = -2; long seen = 0;
        while (SystemClock.uptimeMillis() - t0 < 25_000) {
            Vision.Result r = waitFor(this::sure, 2000);
            if (r == null || !r.haste) { if (SystemClock.uptimeMillis() - t0 > 500) Log.add("haste: cleared after " + (SystemClock.uptimeMillis() - t0) / 1000 + " s, starting the walk"); return; }
            // Walks are Haste-corrected by distance now: only an unreadable or flickering stack count is worth waiting
            // out. The same count on 4 different frames in a row (~2 s) is trusted, and the walk starts at that speed.
            if (Monitor.lastTime != seen) {
                seen = Monitor.lastTime;
                if (r.hasteStacks > 0 && r.hasteStacks == stacks) same++; else { same = r.hasteStacks > 0 ? 1 : 0; stacks = r.hasteStacks; }
            }
            if (same >= 4) {
                liveSpeed = Math.round(speedFactor(r, 1 + 0.1 * Math.min(10, stacks)) * 1000) / 1000.0; hasteMul = 1 + 0.1 * Math.min(10, stacks);
                Log.add(String.format(Locale.US, "haste: x%d read steadily, walking now at x%.2f (distance-corrected) instead of waiting", stacks, liveSpeed));
                return;
            }
            decide("Waiting for Haste", "Haste is active; the route distances assume base speed, so walking now could overshoot.",
                    "buff row: Haste" + (r.hasteStacks > 0 ? " x" + r.hasteStacks : ""), "leave when Haste ends (≤20 s)", r.hasteConf);
            step = "Waiting for Haste to end before the walk (" + (SystemClock.uptimeMillis() - t0) / 1000 + " s)";
            sleep(500);
        }
        Log.add("haste: still on after 25 s, walking with live-speed correction");
    }

    /** A recorded D-pad press, replayed by DISTANCE: s.count ms at the recorded speed s.tiles = count*tiles ms of
     *  base-speed walking. The key stays pressed (holdUntil) while distance is integrated every 150 ms at the live
     *  Haste speed, and lifts when the distance is covered — so Haste gained or lost mid-walk is matched. If the stack
     *  count can't be read for >1 s, it stops and waits (max 60 s) until it can, or Haste wears off. */
    private boolean walkHold(Moves.Step s) {
        java.util.EnumSet<Moves.Key> keys = MacroService.set(s.keys);
        final double[] left = {s.count * s.tiles};          // ms of base-speed walking still to go
        final double total = s.count * s.tiles; final double[] moved = {0};   // ms of base-speed walking seen to move
        final boolean[] walled = {false};
        double v = liveSpeed;   // the speed read on the last step: a new step must not start at x1.0 and overshoot
        while (left[0] > 20) {
            checkpoint();
            v = waitForReadableSpeed(v);
            final double[] cur = {v};
            final long[] last = {SystemClock.uptimeMillis()}, unreadSince = {0};
            final boolean[] lost = {false}, hopped = {false};
            final long started = last[0]; final long[] stillSince = {0}, stillTotal = {0};
            final boolean guard = p.navGuard && p.keyboard() && !hasteFix;   // routes only: in the field the field guard decides (no jumps mid-pattern)
            final boolean[] lag = {false};
            Vision.Result bag0 = Monitor.last;
            final boolean emptyBag = bag0 != null && bagKnown(bag0) && bag0.pollen < 1   // a reset costs nothing yet
                    && !phase.startsWith("Farming") && !fieldRecovering;                      // routes only: in the field the field guard decides
            if (left[0] > 150 * v) {
                boolean hit = in.holdUntil(keys, (long) (left[0] / v) + 3000, () -> {
                    long now = SystemClock.uptimeMillis();
                    java.util.function.BooleanSupplier stopAt = walkStop;
                    if (stopAt != null && stopAt.getAsBoolean()) { walkStopped = true; left[0] = 0; return true; }
                    long dt = now - last[0];
                    left[0] -= dt * cur[0]; last[0] = now;
                    if (now - started <= 1200) moved[0] += dt * cur[0];   // motion is not judged yet: count it as walked
                    if (guard && now - started > 1200 && now - Monitor.motionTime < 1500) {
                        // Natro routes walk into walls on purpose to line up (e.g. Mountain Top's 60 tiles right): stopping
                        // after a good part of the step was really walked = at the wall, keep holding like Natro (a jump
                        // there threw the Mondo walk off course, 2026-11-21). Stopping early in a step = stuck or lag.
                        boolean atWall = moved[0] >= 0.3 * total;
                        if (Monitor.motion >= STILL) { stillSince[0] = 0; moved[0] += dt * cur[0]; }
                        else { if (!atWall) stillTotal[0] += dt; if (stillSince[0] == 0) stillSince[0] = now; }
                        if (atWall && stillSince[0] > 0) { if (!walled[0] && now - stillSince[0] > 1200) { walled[0] = true; Log.add("guard: at a wall after " + Math.round(100 * moved[0] / total) + "% of the step: holding on (Natro lines up on walls)"); } }
                        // lag (the game froze) or stuck: every later step of the route would start from the wrong spot
                        else if (emptyBag && stillSince[0] > 0 && (now - stillSince[0] > 4000 || stillTotal[0] > 5000)) { lag[0] = true; return true; }   // the jump at 2.5 s gets a chance first
                        if (stillSince[0] == 0 || atWall) { /* moving, or lined up on a wall */ }
                        else if (now - stillSince[0] > 8000) {   // blocked: end this step, the next checkpoint judges where we are
                            Log.add("guard: blocked for 8 s, ending step with " + Math.round(left[0]) + " ms left"); stats.corrections++; left[0] = 0; return true;
                        } else if (now - stillSince[0] > 2500 && !hopped[0]) { hopped[0] = true; Log.add("guard: not moving, jumping"); Keys.tap(Keys.SPACE, 80); }
                    }
                    double nv = speedNow(cur[0]);
                    if (nv < 0) { if (unreadSince[0] == 0) unreadSince[0] = now; else if (now - unreadSince[0] > 1000) { lost[0] = true; return true; } }
                    else { unreadSince[0] = 0; if (nv != cur[0]) { Log.add(String.format(Locale.US, "speed: x%.2f → x%.2f mid-walk (%s)", cur[0], nv, speedWhy())); cur[0] = nv; } }
                    step = String.format(Locale.US, "Walking %s at x%.2f speed · %.1f tiles left", Moves.Step.k(s.keys), cur[0], left[0] / Moves.tileMs(1, p.moveSpeed));
                    return left[0] <= 150 * cur[0];             // next 150 ms would overshoot: finish with a short press
                });
                if (lag[0]) {
                    in.emergencyRelease(); in.arm();
                    stats.navFailures++; saveGuardFrame("walklag");
                    throw new Recover(String.format(Locale.US, "walk: not moving for %.1f s (lag or stuck): resetting to start over", stillTotal[0] / 1000.0));
                }
                if (!hit && !lost[0]) {
                    if (!stopped && !paused) return false;      // gesture rejected
                    checkpoint();                               // waits out a pause (throws on stop), then
                    continue;                                   // walks the rest of this press
                }
                v = cur[0];
                if (lost[0]) continue;                          // wait until the count is readable, then go on
            }
            if (left[0] > 20) { long ms = Math.round(left[0] / v); if (!in.hold(keys, ms)) return false; left[0] = 0; }
        }
        liveSpeed = v;
        return true;
    }

    /** Stop (finger up) while Haste is on but its stack count is unreadable — the speed is unknown, so walking would
     *  miss the distance. Up to 60 s (Haste wears off), then assume the worst-case known speed and log it. */
    private double waitForReadableSpeed(double prev) {
        long t0 = SystemClock.uptimeMillis();
        boolean said = false;
        while (true) {
            checkpoint();
            double v = speedNow(prev);
            if (v > 0) { if (said) Log.add("haste readable again after " + (SystemClock.uptimeMillis() - t0) / 1000 + " s (x" + v + ")"); return v; }
            if (!said && SystemClock.uptimeMillis() - t0 > 1000) { Log.add("haste on but stack count unreadable — pausing the walk"); said = true; }
            step = "Waiting: Haste stack count unreadable (keeps walk distance exact)";
            if (SystemClock.uptimeMillis() - t0 > (p.keyboard() ? 20_000 : 60_000)) { Log.add("haste count unreadable — walking at x" + prev); return prev; }
            SystemClock.sleep(250);
        }
    }

    /** Shift lock keeps the camera from auto-turning when strafing (Roblox mobile's default Follow camera does),
     *  which is what makes PC Natro paths valid here. Measured: on = blue ring, off = gray ring. */
    private void ensureShiftLock() {
        for (int i = 0; i < 3; i++) {
            Vision.Result r = waitFor(this::sure, 3000);
            if (r != null && r.shiftLock == 1) return;
            if (r != null && r.shiftLock == 0) { if (p.keyboard()) in.key(Keys.SHIFT, 60); else in.tap(p.shiftTapX, p.shiftTapY); Log.add("shift lock: was off, toggled"); sleep(900); }
            else sleep(500);
        }
        pause(p.keyboard() ? "Couldn't turn on Shift Lock. In Roblox Settings set Shift Lock Switch to On, then resume." : "Couldn't confirm Roblox shift lock is on (lock button bottom-left). Turn it on and resume.");
        checkpoint();
    }

    /** Vision-ended walk (Natro nm_gotoCannon / nm_findHiveSlot): hold until the target prompt shows, then check it
     *  is still there after 500 ms; if we overran, step back 1.5 tiles and re-check (up to 10 times). */
    private boolean walkUntil(Moves.Step s) {
        boolean cannon = s.label.equals("cannon");
        java.util.function.Predicate<Vision.Result> at = r -> promptKnown(r) && (cannon
                ? r.prompt == Vision.Prompt.TAP_CANNON   // "Fire the Red Cannon" (measured), never a hive prompt
                : r.prompt == Vision.Prompt.TAP_BLUE || r.prompt == Vision.Prompt.BANNER_BLUE || r.prompt == Vision.Prompt.TAP_RED);
        java.util.function.BooleanSupplier seen = () -> {
            Vision.Result r = Monitor.last;
            return r != null && SystemClock.uptimeMillis() - Monitor.lastTime < 800 && at.test(r);
        };
        if (!in.holdUntil(MacroService.set(s.keys), s.count, seen)) {
            if (stopped || paused) return true;   // interrupted on purpose; checkpoint() handles it
            throw new Recover(s.label + " not found within " + s.count / 1000 + " s");
        }
        Moves.Key[] back = Moves.flip(s.keys);
        for (int i = 0; i < 10; i++) {
            sleep(500);
            Vision.Result r = waitFor(x -> true, 1500);
            if (r != null && at.test(r)) { Log.add("route: reached " + s.label + " (" + r.prompt + ")"); return true; }
            in.hold(MacroService.set(back), Moves.tileMs(1.5, p.moveSpeed));
        }
        throw new Recover(s.label + " overrun — prompt lost");
    }

    // ------------------------------------------------------------------ vision helpers
    private long unexpectedSince;
    /** Pauses on menus / disconnect / loading that last too long. */
    // ---- no-HUD mode: farm on timers when the Honey/Pollen counters can't be read (Monitor's world readers use it too)
    static volatile boolean noHudNow; private static volatile boolean startNoHud;
    private int hudBackFrames; private long hudFrameSeen;
    private void noHudNote(String why) {
        String m = "No-HUD mode: converting every " + p.fieldMinutes + " min (" + why + ")";
        Log.add(m); decide("No-HUD mode", m, "Honey/Pollen counters not read", "farm " + p.fieldMinutes + " min, convert " + p.noHudConvertSec + " s", 0.7);
        hook("problem", "🙈 No-HUD mode", m, Webhook.GOLD, false);
    }
    /** Counters readable again for 5 frames: back to full HUD mode (not when no-HUD mode is switched on by hand). */
    private void hudModeCheck(Vision.Result r) {
        if (!noHudNow || p.noHudForced || r == null || Monitor.lastTime == hudFrameSeen) return;
        hudFrameSeen = Monitor.lastTime;
        hudBackFrames = r.screen == Vision.Screen.GAME && r.screenConf >= p.minConfidence && r.pollenConf >= p.minConfidence ? hudBackFrames + 1 : 0;
        if (hudBackFrames >= 5) { noHudNow = false; hudBackFrames = 0; Log.add("hud: Honey/Pollen counters readable again - back to full HUD mode"); decide("Farming", "The counters are readable again: bag-full and prompt checks are back on.", 1); }
    }
    private Vision.Result watchScreen() {
        Vision.Result r = Monitor.last;
        hudModeCheck(r);
        if (r == null || Vision.playable(r, noHudNow)) { unexpectedSince = 0; return r; }
        if (unexpectedSince == 0) unexpectedSince = SystemClock.uptimeMillis();
        if (p.navGuard && p.keyboard() && (r.screen == Vision.Screen.MENU || r.screen == Vision.Screen.RESPAWN_DIALOG) && SystemClock.uptimeMillis() - unexpectedSince > 1000) {
            Log.add("guard: closing unexpected " + r.screen); in.key(Keys.ESC, 60); unexpectedSince = 0; sleep(700); return Monitor.last;
        }
        // the game is on screen, only the counters aren't read (30 s: a Bee Swarm window or the chat closes by itself
        // before that): carry on in no-HUD mode instead of pausing or rejoining
        if (r.screen == Vision.Screen.UNKNOWN && Vision.playable(r, true) && SystemClock.uptimeMillis() - unexpectedSince > 30_000) {
            unexpectedSince = 0; noHudNow = true; hudBackFrames = 0; noHudNote("the counters stopped being readable"); return r;
        }
        if (night() && SystemClock.uptimeMillis() - unexpectedSince > p.rejoinAfterSec * 1000L) {
            unexpectedSince = 0; rejoinUntilBack("game screen gone for " + p.rejoinAfterSec + " s (" + r.screen + ")");
            throw new Recover("rejoined the server");
        }
        if (!night() && SystemClock.uptimeMillis() - unexpectedSince > (r.screen == Vision.Screen.DARK ? 10000 : 4000)) {
            final long since = unexpectedSince; unexpectedSince = 0;
            // A Bee Swarm window, the chat or a notification over the counters is usually gone in a few seconds:
            // wait for the game to come back before pausing (Discord: "stops by itself", then "works again").
            String was = step; step = "Waiting for the Bee Swarm screen to come back (" + r.screen + ")";
            Vision.Result back = waitFor(x -> sure(x) || (x.screen == Vision.Screen.UNKNOWN && Vision.playable(x, true) && SystemClock.uptimeMillis() - since > 30_000), 30_000);
            if (back != null && !sure(back)) { noHudNow = true; hudBackFrames = 0; noHudNote("the counters stopped being readable"); step = was; return back; }
            step = was;
            if (back != null) { Log.add("screen: game back after " + r.screen + ", carrying on"); return back; }
            pause("Unexpected screen: " + r.screen + " — Roblox menu, loading or disconnected?");
            checkpoint();
        }
        return r;
    }

    interface Want { boolean ok(Vision.Result r); }
    private Vision.Result waitFor(Want w, long timeoutMs) {
        long end = SystemClock.uptimeMillis() + timeoutMs, since = SystemClock.uptimeMillis();
        while (SystemClock.uptimeMillis() < end) {
            checkpoint();
            Vision.Result r = Monitor.fresh(since, 1500);
            if (r != null) { since = Monitor.lastTime; if (w.ok(r)) return r; }
        }
        return null;
    }
    private boolean sure(Vision.Result r) { return Vision.playable(r, noHudNow) && (r.screen != Vision.Screen.GAME || r.screenConf >= p.minConfidence); }
    private boolean bagKnown(Vision.Result r) { return sure(r) && r.pollenConf >= p.minConfidence; }
    private boolean promptKnown(Vision.Result r) { return sure(r) && r.promptConf >= p.minConfidence; }

    // ------------------------------------------------------------------ phases
    /** The hive/cannon prompt: E on the keyboard, a tap otherwise. */
    private void prompt() { if (p.keyboard()) in.key(Keys.E, 60); else in.tap(T().promptX, T().promptY); }

    /** Roblox's blue button on the screen right now, as a tap point (screen fractions), or null. menu=true: Respawn,
     *  just left of the blue Resume (right half, upper part); menu=false: the dialog's blue Respawn (left of centre).
     *  Found by sight on every screen shape; the fixed spots hit Leave / Invite Friends on some devices (Discord). */
    private float[] blueTap(boolean menu) {
        long t = SystemClock.uptimeMillis(); Monitor.shotWanted = true;
        while (Monitor.shotTime < t && SystemClock.uptimeMillis() - t < 1500) SystemClock.sleep(40);
        int[] px = Monitor.shotPx; int w = Monitor.shotW, h = Monitor.shotH;
        if (px == null || Monitor.shotTime < t) return null;
        int[] b = Vision.blueButton(px, w, h); if (b == null) return null;
        double bw = b[1] - b[0], cx = (b[0] + b[1]) / 2.0, cy = (b[2] + b[3]) / 2.0;
        if (menu) { if (cx < 0.55 * w || cy > 0.6 * h) return null; cx = b[0] - 0.52 * bw; }   // Leave | Respawn | Resume, equal widths
        else if (cx > 0.5 * w || cy < 0.35 * h) return null;                                   // Respawn | Don't Respawn
        return cx < 0 ? null : new float[]{(float) (cx / w), (float) (cy / h)};
    }
    /** Is the "Are you sure you want to respawn?" dialog up? (recognised, or its blue Respawn button found) */
    private boolean dialogUp() { Vision.Result r = Monitor.last; return (r != null && r.screen == Vision.Screen.RESPAWN_DIALOG) || blueTap(false) != null; }

    /** Keyboard respawn: Esc opens the menu, then Respawn and its confirmation, by key where Roblox takes it and by a
     *  tap on the button found on screen where it doesn't (phones and tablets alike since Roblox's 2026 menu). */
    private boolean respawnKeys() {
        // Continue from whatever is already open: pressing Esc on an open menu would close it again.
        Vision.Screen now = Monitor.last == null ? null : Monitor.last.screen;
        if (now != Vision.Screen.RESPAWN_DIALOG && !dialogUp()) {
            if (now != Vision.Screen.MENU) {
                in.key(Keys.ESC, 60);
                if (waitFor(r -> r.screen == Vision.Screen.MENU, 3000) == null && blueTap(true) == null) return false;
            }
            sleep(400);
            // Roblox reopens the menu on the last tab used (Settings, after setting Camera Mode): Respawn is on People
            Vision.Result m = Monitor.last;
            if (m != null && m.screen == Vision.Screen.MENU && !m.menuPeople && T().peopleX >= 0) {
                Log.add("respawn: menu opened on another tab, going to People");
                in.tap(T().peopleX, T().peopleY); sleep(700);
            }
            in.key(Keys.R, 60);
            boolean dlg = waitFor(r -> r.screen == Vision.Screen.RESPAWN_DIALOG, 1200) != null || dialogUp();
            if (!dlg) {   // R is no shortcut in Roblox's newer menu: click its Respawn button
                float[] b = blueTap(true);
                // The "usual spot" is only safe on the menu we measured (Leave | Respawn | Resume with the blue Resume
                // where we expect it). Roblox's older menu is Reset | Leave | Resume: there the middle is Leave, and
                // the Leave dialog looks like the respawn one, so Enter then left the game (Discord: "it leaves the game").
                Vision.Result mm = Monitor.last;
                if (b == null && !(mm != null && mm.screen == Vision.Screen.MENU && mm.menuPeople)) {
                    Log.add("respawn: R gave no dialog and Respawn wasn't found on this menu - not clicking blind (could be Leave)");
                    in.key(Keys.ESC, 60); sleep(800); return false;
                }
                Log.add("respawn: R gave no dialog, clicking Respawn in the menu" + (b != null ? " (found next to Resume)" : " (usual spot)"));
                in.tap(b != null ? b[0] : T().respawnX, b != null ? b[1] : T().respawnY);
                long t0 = SystemClock.uptimeMillis();
                while (!(dlg = dialogUp()) && SystemClock.uptimeMillis() - t0 < 3000) sleep(200);
            }
            if (!dlg) { in.key(Keys.ESC, 60); sleep(800); return false; }
        }
        sleep(300);
        in.key(Keys.ENTER, 60);
        sleep(900);
        if (dialogUp()) {   // Enter didn't confirm: click the dialog's blue Respawn
            float[] b = blueTap(false);
            Log.add("respawn: Enter didn't confirm, clicking the dialog's Respawn" + (b != null ? " (found)" : " (usual spot)"));
            in.tap(b != null ? b[0] : T().confirmX, b != null ? b[1] : T().confirmY);
        }
        if (waitFor(this::sure, 8000) == null) return false;
        sleep(2500);
        return true;
    }

    private int respawnFails;
    private void respawn() {
        resetBagWatch();
        if (p.keyboard()) {
            for (int attempt = 0; attempt < 3; attempt++) if (respawnKeys()) { respawnFails = 0; return; }
            // keys not reaching Roblox looks like this too ("won't move, keeps trying to respawn"): stop with the fix
            // instead of looping forever (overnight turns pauses into retries)
            String fix = "Couldn't respawn: Roblox didn't react to the keyboard (Esc). Check Shizuku is running and press Home step 2, "
                    + "then close Roblox completely and open Bee Swarm again before pressing Start.";
            if (++respawnFails >= 3) { stop(fix); checkpoint(); }   // checkpoint() ends the run (Stopped)
            pause(fix); checkpoint(); throw new Recover("respawn failed");
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            in.tap(T().menuX, T().menuY);
            if (waitFor(r -> r.screen == Vision.Screen.MENU && r.screenConf >= p.minConfidence, 3000) == null) { sleep(800); continue; }
            sleep(700);   // let the menu finish sliding in: an early tap falls through to a hive cell and opens a bee window
            // Roblox reopens the menu on the last tab; Respawn only sits at this spot on the People tab
            // (on Settings that spot is the Graphics Quality slider), so switch tabs and confirm first.
            if (waitFor(r -> r.menuPeople, 800) == null) {
                if (T().peopleX < 0) { sleep(800); continue; }    // tablet dropdown has no People tab
                in.tap(T().peopleX, T().peopleY);
                if (waitFor(r -> r.menuPeople, 2500) == null) { in.tap(T().menuX, T().menuY); sleep(800); continue; }
                sleep(400);
            }
            in.tap(T().respawnX, T().respawnY);
            if (waitFor(r -> r.screen == Vision.Screen.RESPAWN_DIALOG && r.screenConf >= p.minConfidence, 3000) == null) {
                in.tap(T().menuX, T().menuY); sleep(800); continue;   // close menu, retry
            }
            sleep(500);
            in.tap(T().confirmX, T().confirmY);
            if (waitFor(this::sure, 8000) == null) continue;
            sleep(2500);                                                   // character respawn
            return;
        }
        pause("Could not open the Roblox menu to respawn (3 tries)");
        checkpoint();
        throw new Recover("respawn failed");
    }

    private void verifyHive() {
        if (p.keyboard()) {   // the camera alignment that follows finds the hive or resets; the banner can take 20+ s
            if (waitFor(this::sure, 8000) == null) throw new Recover("game screen not readable after respawn");
            nav = "Respawned"; return;
        }
        Vision.Result r = waitFor(x -> promptKnown(x) && x.prompt != Vision.Prompt.NONE && x.prompt != Vision.Prompt.TAP_CLAIM, 20000);   // live: banner took ~17 s after a respawn
        if (r != null) { nav = "At own hive (hive prompt visible, " + Math.round(r.promptConf * 100) + "%)"; Log.add("hive: verified " + r.prompt); return; }
        if (p.hiveSlot == 3) {   // no hive owned yet: respawn put us at spawn, which faces pad 3 (measured)
            nav = "No hive owned → claiming slot 3 from spawn";
            run(Moves.spawnToHive3(p.moveSpeed), "spawn→hive");
            r = waitFor(x -> promptKnown(x) && (x.prompt == Vision.Prompt.TAP_CLAIM || x.prompt == Vision.Prompt.TAP_BLUE), 3000);
            if (r != null) {
                prompt(); sleep(1500);
                Log.add("hive: tapped Claim Hive on slot 3 — respawning at the new hive");
                respawn();
                r = waitFor(x -> promptKnown(x) && x.prompt != Vision.Prompt.NONE, 10000);
                if (r != null) { nav = "At own hive (just claimed slot 3)"; Log.add("hive: verified after claim " + r.prompt); return; }
            }
        }
        if (!p.keyboard()) { pause("Not at your hive after respawn — hive prompt not visible. Check the hive slot in the app."); checkpoint(); }
        throw new Recover("hive not verified");
    }

    private int[] fieldFrames() {
        int pine = 0, other = 0;
        long since = SystemClock.uptimeMillis();
        for (int i = 0; i < 6; i++) {
            Vision.Result fr = Monitor.fresh(since, 1500);
            if (fr == null) break;
            since = Monitor.lastTime;
            NavGuard.View v = Monitor.view;
            FieldMap.Read fm = Monitor.fieldRead;
            if (fr.field == Vision.Field.OTHER_FIELD || (fm != null && fm.wrongField)) other++;
            else if (fr.field == Vision.Field.PINE_TREE || (v != null && v.best() >= NavGuard.IN && v.red < NavGuard.RED)) pine++;
        }
        return new int[]{pine, other};
    }

    private void convert() {
        if (noHudNow && waitFor(x -> bagKnown(x) && promptKnown(x), 2000) == null) { convertTimed(); return; }
        Vision.Result r = waitFor(x -> bagKnown(x) && promptKnown(x), 4000);
        if (r == null) { pause("Can't read backpack/hive prompt at hive"); checkpoint(); throw new Recover("convert: unreadable"); }
        // the bag bar rounds a big bag down to 0%; the game's own "Make Honey" prompt means there is pollen to convert
        if (r.pollen < 1 && r.prompt != Vision.Prompt.TAP_RED && r.prompt != Vision.Prompt.TAP_BLUE) { Log.add("convert: backpack empty, nothing to convert"); return; }
        // "To Make Honey, Collect Pollen From Flower Fields" = the game says the bag is empty (field conversions -
        // Token Link, Diamond Drain, the balloon - often leave it at 0%): we're on our pad and there's nothing to do
        if (r.prompt == Vision.Prompt.BANNER_BLUE) { Log.add("convert: the game says the bag is empty (pollen was converted in the field)"); return; }
        double startPct = r.pollen;
        resetBagWatch();
        if (r.prompt != Vision.Prompt.TAP_BLUE && r.prompt != Vision.Prompt.TAP_RED) {   // slid off the pad: find it again first
            Log.add("convert: no 'Make Honey' prompt (" + r.prompt + "), finding the pad again");
            natroFindHive();
            Vision.Result again = waitFor(x -> promptKnown(x), 2000);
            if (again != null) r = again;
        }
        if (r.prompt == Vision.Prompt.TAP_BLUE) prompt();
        Vision.Result started = waitFor(x -> promptKnown(x) && x.prompt == Vision.Prompt.TAP_RED, 5000);
        if (started == null) { pause("Conversion did not start (no 'Stop Making Honey')"); checkpoint(); throw new Recover("conversion not verified"); }
        stats.conversions++;
        ownsHive = true;   // only our own hive converts
        Log.add(String.format(Locale.US, "convert: started at %.0f%%", startPct));
        long honeyBefore = Monitor.honey;
        hook("convert", "🍯 Converting", String.format(Locale.US, "Backpack %.0f%% at hive slot %d", startPct, p.hiveSlot), Webhook.CYAN, true);
        long t0 = SystemClock.uptimeMillis();
        int doneFrames = 0;
        int need = p.keyboard() ? 4 : 2;
        while (doneFrames < need) {
            if (p.keyboard() && SystemClock.uptimeMillis() - t0 > p.convertTimeoutSec * 1000L) { Log.add("convert: time limit reached, moving on"); break; }
            if (SystemClock.uptimeMillis() - t0 > p.convertTimeoutSec * 1000L) { pause("Conversion not finished after " + p.convertTimeoutSec + " s"); checkpoint(); t0 = SystemClock.uptimeMillis(); }
            Vision.Result x = waitFor(this::bagKnown, 3000);
            watchScreen();
            if (x == null) continue;
            step = String.format(Locale.US, "Converting… backpack %.0f%%", x.pollen);
            doneFrames = x.pollen <= 0.5 && (p.keyboard() || (x.prompt != Vision.Prompt.TAP_RED && x.promptConf >= p.minConfidence)) ? doneFrames + 1 : 0;
        }
        stats.backpackPctCollected += startPct;
        Log.add("convert: complete in " + Stats.fmt(SystemClock.uptimeMillis() - t0));
        long honeyAfter = Monitor.honey;
        hook("convert", "✅ Converted", "Done in " + Stats.fmt(SystemClock.uptimeMillis() - t0)
                + (honeyAfter > 0 ? "\nHoney: " + Stats.big(honeyAfter) + (honeyBefore > 0 && honeyAfter > honeyBefore ? " (+" + Stats.big(honeyAfter - honeyBefore) + " this trip)" : "") : "")
                + (stats.honeyStart > 0 && honeyAfter > stats.honeyStart ? "\nThis session: +" + Stats.big(honeyAfter - stats.honeyStart) : "")
                + (stats.honeyRate().isEmpty() ? "" : "\nRate: " + stats.honeyRate().replace("🍯 ", "")), Webhook.GREEN, true);
        if (balloonDue()) convertBalloon();
        long hv = Monitor.honey; stats.honey(hv);
        stats.trip(String.format(Locale.US, "converted %.0f%% bag in %s · honey %s", startPct, Stats.fmt(SystemClock.uptimeMillis() - t0), Stats.big(hv)));
    }

    private long lastBalloon = -1_000_000_000L;
    private boolean tripConverted;
    /** After a reset that didn't come right after our own convert (first trip, recovery, rejoin): we stand at our
     *  hive with an empty bag. Press E: "Stop Making Honey" with nothing in the bag = the hive balloon is converting. */
    private void balloonAfterReset() {
        Vision.Result r = waitFor(this::promptKnown, 3000);
        if (r == null || !ownHive(r) || r.prompt != Vision.Prompt.TAP_BLUE || !bagKnown(r) || r.pollen >= 1) return;
        phase("Converting", "convert");
        Log.add("balloon: checking the hive balloon after the reset");
        prompt();
        Vision.Result c = waitFor(x -> promptKnown(x) && x.prompt == Vision.Prompt.TAP_RED, 4000);
        if (c == null) { Log.add("balloon: nothing converting (no balloon)"); return; }
        convertBalloon();
    }

    private boolean balloonDue() {
        if (p.balloonMode.equals("Always")) return true;
        return p.balloonMode.equals("Every") && SystemClock.elapsedRealtime() - lastBalloon > p.balloonMins * 60_000L;
    }

    /** Natro nm_convert "empty balloon": with the backpack empty, a hive balloon keeps the game converting
     *  ("Stop Making Honey" stays up). Wait for it to finish, up to 10 min; stop early if honey stops rising. */
    private void convertBalloon() {
        Vision.Result r = waitFor(this::promptKnown, 3000);
        lastBalloon = SystemClock.elapsedRealtime();
        if (r == null || r.prompt != Vision.Prompt.TAP_RED) { Log.add("balloon: no hive balloon converting"); return; }
        Log.add("balloon: hive balloon found, converting it");
        long t0 = SystemClock.uptimeMillis(), rise = t0, h = Monitor.honey;
        int ended = 0;
        while (SystemClock.uptimeMillis() - t0 < 600_000) {
            Vision.Result x = waitFor(this::promptKnown, 2000);
            watchScreen();
            long now = SystemClock.uptimeMillis();
            if (Monitor.honey > h) { h = Monitor.honey; rise = now; }
            step = "Converting hive balloon (" + (now - t0) / 1000 + " s)";
            if (h > 0 && now - t0 > 60_000 && now - rise > 30_000) { Log.add("balloon: honey stopped rising, moving on"); break; }
            ended = x != null && x.prompt != Vision.Prompt.TAP_RED ? ended + 1 : 0;
            if (ended >= 3) break;
            if (x != null) sleep(1000);
        }
        lastBalloon = SystemClock.elapsedRealtime();
        stats.balloons++;
        Log.add("balloon: done in " + Stats.fmt(SystemClock.uptimeMillis() - t0));
        hook("convert", "🎈 Hive balloon converted", "In " + Stats.fmt(SystemClock.uptimeMillis() - t0) + (Monitor.honey > 0 ? "\nHoney: " + Stats.big(Monitor.honey) : ""), Webhook.GREEN, true);
    }

    /** No-HUD conversion: E on the pad, then stand there for the set time (nothing reads the bag). Bee Swarm stops
     *  making honey by itself when the bag is empty, so waiting longer only costs time. */
    private void convertTimed() {
        resetBagWatch();
        prompt();
        stats.conversions++;
        Log.add("convert: no-HUD, pressed E and converting for " + p.noHudConvertSec + " s");
        hook("convert", "🍯 Converting (no-HUD)", "Hive slot " + p.hiveSlot + " · " + p.noHudConvertSec + " s", Webhook.CYAN, true);
        long t0 = SystemClock.uptimeMillis();
        while (SystemClock.uptimeMillis() - t0 < p.noHudConvertSec * 1000L) {
            step = "Converting (no-HUD) · " + (p.noHudConvertSec - (SystemClock.uptimeMillis() - t0) / 1000) + " s left";
            watchScreen(); sleep(1000);
            if (!noHudNow) { Log.add("convert: counters readable again, checking the bag"); break; }
        }
        if (balloonDue()) convertBalloon();
    }

    /** The chosen farming pattern: Skillet, or a Natro Macro pattern read from assets/patterns (falls back to Skillet). */
    private boolean natroPattern;   // the last farmPattern() is a Natro pattern (not Skillet or the Skillet fallback)
    /** The pattern really running, for the panel (it said "Skillet" during every pattern: Discord "using CornerXSnake but it still uses Skillet"). */
    private String patName() { return natroPattern ? p.pattern : "Skillet"; }
    private List<Moves.Step> farmPattern() {
        natroPattern = false;
        if (!p.pattern.equals("Skillet")) {
            try (java.io.InputStream is = in.getAssets().open("patterns/" + p.pattern + ".ahk")) {
                String src = new String(readAll(is), java.nio.charset.StandardCharsets.UTF_8);
                List<Moves.Step> st = Patterns.steps(src, Patterns.size(p.patternSize), p.patternWidth, false, p.patternInvertFB, p.patternInvertLR, p.moveSpeed);
                Log.add("farm: pattern " + p.pattern + " " + p.patternSize + " ×" + p.patternWidth + " (" + st.size() + " steps)");
                nav = Routes.name(p.field) + " · " + p.pattern;
                natroPattern = true;
                return st;
            } catch (Exception e) { Log.add("farm: pattern " + p.pattern + " unreadable (" + e.getMessage() + "), using Skillet"); }
        }
        return Moves.skillet(Profile.DW_FDC, Profile.HW_FDC, Profile.CR_FDC, Profile.DW_ALIGN, Profile.HW_ALIGN);
    }
    private static byte[] readAll(java.io.InputStream is) throws java.io.IOException {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); byte[] b = new byte[4096];
        for (int n; (n = is.read(b)) > 0; ) o.write(b, 0, n);
        return o.toByteArray();
    }

    /** A Natro walking route from assets/paths (walk branch), for this hive slot and speed. */
    private List<Moves.Step> route(String file) {
        try (java.io.InputStream is = in.getAssets().open("paths/" + file + ".ahk")) {
            return Routes.steps(new String(readAll(is), java.nio.charset.StandardCharsets.UTF_8), p.hiveSlot, p.moveSpeed);
        } catch (Exception e) { throw new Recover("route " + file + " unreadable: " + e.getMessage()); }
    }

    /** Farm walks are corrected for Haste (set per pattern in farm(), off for every other walk). */
    private boolean hasteFix;
    private void farm() {
        List<Moves.Step> sk = farmPattern();
        hasteFix = natroPattern ? p.hastePatterns : p.hasteAdjustSkillet;
        corner = natroPattern && cornerPlanned() ? cornerPlan(sk) : null;
        if (corner == null && cornerWanted()) {   // corner mode was planned but can't run: place the sprinkler the normal way (before the camera turn, like Natro)
            if (p.fieldRotate != 0) in.rotate(-p.fieldRotate);
            placeSprinkler();
            if (p.fieldRotate != 0) in.rotate(p.fieldRotate);
        }
        // Pine Tree's corner is under the leaves: look up under them like Skillet does (RotUp 4), or the screen is all leaves
        int tilt = corner != null && p.field.equals("pinetree") ? 4 : 0;
        if (tilt != 0) { in.tilt(tilt); Log.add("corner: camera tilted up " + tilt + " to see under the tree"); }
        try { farmLoop(sk); } finally { hasteFix = false; corner = null; anchorRef = null; if (tilt != 0) in.tilt(-tilt); }
    }
    /** Corner mode will be used this trip: corner sprinkler spot and a Natro pattern (Skillet lines up by itself). */
    private boolean cornerWanted() {
        if (!cornerPlanned() || p.pattern.equals("Skillet")) return false;
        try (java.io.InputStream is = in.getAssets().open("patterns/" + p.pattern + ".ahk")) { return true; } catch (Exception e) { return false; }
    }
    /** The hedges keep us in: corner mode, or Skillet at a corner spot (it walks into them on purpose). No edge steering then. */
    private boolean wallBound() { return corner != null || (!natroPattern && cornerPlanned()); }

    // ---- drift by sight: loop closure against the view where farming started (VisualAnchor)
    private VisualAnchor anchorRef; private int anchorMiss;
    /** The view now as an anchor (median of 3 frames when steady, else one fresh frame), or null. */
    private VisualAnchor view(boolean steady) {
        Remap.Out ro = Monitor.remap;
        int[] px;
        if (steady) px = frames3();
        else { Vision.Result f = Monitor.fresh(SystemClock.uptimeMillis(), 900); ro = Monitor.remap; px = f == null || ro == null ? null : ro.px.clone(); }
        if (px == null || ro == null || px.length != ro.w * ro.h) return null;
        return new VisualAnchor(px, ro.w, ro.h, p.playerX, p.playerY);
    }
    private void setAnchor() {
        anchorRef = null; anchorMiss = 0;
        if (!p.visualAnchor) return;
        VisualAnchor a = view(true);
        if (a == null || !a.usable()) { Log.add("anchor: view too plain to remember" + (a == null ? " (no frames)" : String.format(Locale.US, " (texture %.1f)", a.texture))); return; }
        anchorRef = a;
        Log.add(String.format(Locale.US, "anchor: remembered the view where farming starts (texture %.1f)", a.texture));
    }
    /** After a loop: how far has the scene slid from the remembered view? Walk back until it lines up again, re-measuring
     *  after every short press; stop if it gets worse twice (never trust a wrong direction) or the match is lost.
     *  Returns false when the view no longer matches (the caller falls back to the sprinkler check). */
    private int anchorSignX = 1, anchorSignY = 1; private boolean anchorSignKnown;
    /** A slide reading worth acting on: a clear match, clearly better than "no slide", and not at the edge of the
     *  search (live under the trees: false locks sat exactly at the edge, 44%, and walked the character out). */
    private boolean trustSlide(double[] s, int h, double maxFrac) {
        double edge = 0.9 * maxFrac * h;
        return s[2] >= 0.6 && s[2] - s[3] >= 0.08 && Math.abs(s[0]) < edge && Math.abs(s[1]) < edge;
    }
    private boolean anchorCorrect() {
        VisualAnchor now = view(true);
        if (now == null) return false;
        final double max = 0.25;
        double[] s = anchorRef.shiftTo(now, max);
        int h = now.h; double tol = 0.035;
        double off0 = Math.hypot(s[0], s[1]) / h;
        if (off0 <= tol && s[2] >= 0.6) { anchorMiss = 0; prevResult = String.format(Locale.US, "on the start spot (view %.1f%% off, match %.2f)", off0 * 100, s[2]); return true; }
        if (!trustSlide(s, h, max)) {
            if (++anchorMiss >= 3) { Log.add(String.format(Locale.US, "anchor: no trustworthy match for 3 loops (%.2f vs %.2f still), remembering this spot instead", s[2], s[3])); setAnchor(); }
            else Log.add(String.format(Locale.US, "anchor: no trustworthy match this loop (%.2f, still %.2f, slide %+.0f/%+.0f)", s[2], s[3], s[0], s[1]));
            return false;
        }
        anchorMiss = 0;
        double off = off0;
        String was = phase; phase = "Correcting drift";
        decide("Correcting drift", "The view has slid since the start of farming: walking back to where the pattern started.",
                String.format(Locale.US, "scene moved %+.0f / %+.0f px (match %.2f)", s[0], s[1], s[2]), "resume the pattern", s[2]);
        double first = off, last = off; long walked = 0; boolean flipped = false; String end = "";
        while (walked < 2500) {
            checkpoint();
            EnumSet<Moves.Key> ks = EnumSet.noneOf(Moves.Key.class);
            // the scene slid right = we went left: walk right; slid down = we went forward: walk back
            // (sign learned live: if the first press makes it worse, the camera sees it the other way round)
            double sx = s[0] * anchorSignX, sy = s[1] * anchorSignY;
            if (Math.abs(sx) > 0.4 * Math.abs(sy)) ks.add(sx > 0 ? Moves.Key.D : Moves.Key.A);
            if (Math.abs(sy) > 0.4 * Math.abs(sx)) ks.add(sy > 0 ? Moves.Key.S : Moves.Key.W);
            long press = off > 0.12 ? 200 : 130;
            if (!in.hold(ks, press)) break;
            walked += press; sleep(90);
            VisualAnchor v = view(false);
            if (v == null) { end = " (no frame)"; break; }
            double[] n = anchorRef.shiftTo(v, max);
            if (!trustSlide(n, h, max) && Math.hypot(n[0], n[1]) / h > tol) { end = String.format(Locale.US, " (match lost %.2f)", n[2]); break; }
            double o = Math.hypot(n[0], n[1]) / h;
            if (o > last + 0.004) {   // worse
                if (!anchorSignKnown && !flipped) {
                    flipped = true; anchorSignX = -anchorSignX; anchorSignY = -anchorSignY;
                    Log.add("anchor: the first press went the wrong way, flipping direction");
                } else { end = " (getting further away, stopped)"; s = n; off = o; break; }
            } else if (o < last - 0.004) anchorSignKnown = true;   // it worked: this direction is right from now on
            s = n; off = o; last = o;
            if (off <= tol * 0.6) break;
        }
        stats.corrections++;
        phase = was; Monitor.fieldTrack.clear();
        prevResult = String.format(Locale.US, "drift by sight: %.1f%% → %.1f%% of the screen in %d ms%s", first * 100, off * 100, walked, end);
        Log.add("drift: " + prevResult);
        return true;
    }


    // ---- corner mode: a Natro pattern lined up in the sprinkler's corner every loop (like Skillet's wall alignment)
    /** {towards-corner key left/right, towards-corner key forward/back, tiles out from the side wall, tiles out from the end wall} */
    private Object[] corner;
    private boolean cornerPlanned() {
        String l = p.sprinklerLoc;
        return p.cornerAnchor && p.keyboard() && (l.contains("Upper") || l.contains("Lower")) && (l.contains("Left") || l.contains("Right"));
    }
    /** The corner in the pattern's view (after the field camera turns) and how far out the pattern has to start to fit. */
    private Object[] cornerPlan(List<Moves.Step> sk) {
        if (Math.floorMod(p.fieldRotate, 2) != 0) { Log.add("corner: field camera turned by 45°, corner mode off"); return null; }
        double[] e = Patterns.extent(sk);
        if (e == null) { Log.add("corner: pattern turns by 45°, corner mode off"); return null; }
        int cx = p.sprinklerLoc.contains("Left") ? -1 : 1, cy = p.sprinklerLoc.contains("Upper") ? 1 : -1;
        for (int q = Math.floorMod(p.fieldRotate / 2, 4); q > 0; q--) { int t = cx; cx = -cy; cy = t; }   // camera turned right: the field's forward is now on the screen's left
        // start far enough from each wall that the pattern's furthest reach towards it stays 1 tile short of it
        double outX = 1 + (cx < 0 ? -e[0] : e[1]), outY = 1 + (cy > 0 ? e[3] : -e[2]);
        Log.add(String.format(Locale.US, "corner: %s in view %s%s, pattern reach %.1f×%.1f tiles, starting %.1f / %.1f tiles out",
                p.sprinklerLoc, cy > 0 ? "W" : "S", cx < 0 ? "A" : "D", e[1] - e[0], e[3] - e[2], outX, outY));
        return new Object[]{cx < 0 ? Moves.Key.A : Moves.Key.D, cy > 0 ? Moves.Key.W : Moves.Key.S, outX, outY};
    }
    /** Walk into both walls of the corner (until the world stops moving), then step out to the pattern's start. */
    private void cornerAlign(boolean first) {
        Moves.Key kx = (Moves.Key) corner[0], ky = (Moves.Key) corner[1];
        String was = phase; phase = "Lining up in the corner";
        long a = wallWalk(ky), b = wallWalk(kx);
        if (first || a < 0 || b < 0) Log.add("corner: walls " + (a < 0 ? "not reached" : "at " + a + " ms") + " / " + (b < 0 ? "not reached" : "at " + b + " ms"));
        walkHold(new Moves.Step('H', new Moves.Key[]{Moves.flip(ky)}, 1.0, (int) Moves.tileMs((double) corner[3], p.moveSpeed), null));
        walkHold(new Moves.Step('H', new Moves.Key[]{Moves.flip(kx)}, 1.0, (int) Moves.tileMs((double) corner[2], p.moveSpeed), null));
        checkpoint();
        phase = was; Monitor.fieldTrack.clear();
        if (first) { placeSprinkler(false, new Moves.Key[]{Moves.flip(ky), Moves.flip(kx)}); Monitor.fieldTrack.clear(); }   // the sprinkler sits where the pattern starts
    }
    /** Hold one key until the character stands still against a wall (ms walked), or -1 if no wall within ~30 tiles.
     *  Never jumps: a jump at the hedge is what climbed it. */
    private long wallWalk(Moves.Key k) {
        long t0 = SystemClock.uptimeMillis(); final long[] still = {0};
        boolean hit = in.holdUntil(MacroService.set(new Moves.Key[]{k}), Moves.tileMs(30, p.moveSpeed), () -> {
            long now = SystemClock.uptimeMillis();
            if (now - t0 < 700 || now - Monitor.motionTime > 1500) return false;
            if (Monitor.motion >= STILL) { still[0] = 0; return false; }
            if (still[0] == 0) still[0] = now;
            return now - still[0] > 450;
        });
        checkpoint();
        return hit ? SystemClock.uptimeMillis() - t0 : -1;
    }
    private void farmLoop(List<Moves.Step> sk) {
        Vision.Result r0 = waitFor(this::bagKnown, 4000);
        if (r0 == null && !noHudNow) { pause("Backpack unreadable on arrival"); checkpoint(); throw new Recover("unreadable"); }
        // Make sure we landed in PINE TREE (blue/white flowers), not a neighbouring field, before farming.
        int[] seen = Routes.blueField(p.field) ? fieldFrames() : new int[]{6, 0};   // other fields: no colour check (the 60 s no-pollen reset still applies)
        // at the field's edge a hedge can block the view: look for the flowers before giving up
        if (p.navGuard && p.keyboard() && seen[0] < 3 && seen[1] <= seen[0] && seekPine(8000, "field check")) seen = fieldFrames();
        int pine = seen[0], other = seen[1];
        Log.add("field check: pine=" + pine + " other=" + other + " of 6 frames");
        // Under Pine Tree's leaves no flowers show at all (pine=0 other=0). The field was confirmed on arrival and Bee
        // Swarm accepted the sprinkler (it refuses outside a field), so that is trusted; another field's flowers are not.
        long nowMs = SystemClock.uptimeMillis();
        if (p.field.equals("pinetree") && Monitor.msgOtherSince > 0 && nowMs - Monitor.msgOther < 3000)
            throw new Recover("the game's messages name another field, not Pine Tree Forest");
        boolean hidden = pine < 3 && other == 0 && arrivalOk && sprinklerPlaced;
        if (hidden) Log.add("field check: flowers hidden here (under the leaves); trusting the arrival check and the placed sprinkler");
        else if (pine < 3 || other > pine) throw new Recover(other > pine ? "landed in another field (red flowers), not Pine Tree" : "not standing in Pine Tree Forest");
        nav = Routes.name(p.field) + (Routes.blueField(p.field) ? " (flowers confirmed " + pine + "/6)" : "");
        double startPct = r0 == null ? 0 : r0.pollen;
        long t0 = SystemClock.uptimeMillis();
        if (Monitor.honey > 0) stats.honey(Monitor.honey);
        lastRise = t0; outsideSince = 0; farmStart = t0; lastPct = -1; patYaw = 0; haveLastIn = false; Monitor.fieldTrack.clear(); wrongSince = 0; wrongSeen = 0;
        for (int loop = 1; ; loop++) {
            if (corner != null) cornerAlign(loop == 1);
            // Drift by sight only where the pattern really closes: Skillet and corner mode line up on the hedges every
            // loop, so they end where the walls put them, not where they began (live: the anchor fought Skillet, 30%→44%)
            if (loop == 1 && corner == null) { if (!sprinklerPlaced) Log.add("farm: no sprinkler placed this trip"); if (!wallBound()) setAnchor(); else Log.add("anchor: off here - the hedges line this pattern up every loop"); }
            for (int i = 0; i < sk.size(); i++) {
                checkpoint();
                Moves.Step s = sk.get(i);
                step = patName() + " loop " + loop + " · " + (i + 1) + "/" + sk.size() + ": " + s;
                decide("Farming", patName() + " step " + (i + 1) + "/" + sk.size(), 1);
                if (stepBlocked(s)) { fieldControl(); continue; }
                exec(s);
                timedReport();
                Vision.Result r = watchScreen();
                guardBag(r);
                rate(r);
                checkLost();
                if (s.type == 'W' && !(r != null && bagKnown(r) && r.pollen >= p.backpackThreshold)) maybeCollect();
                boolean stall = stalledFull();
                if (stall || (r != null && bagKnown(r) && r.pollen >= p.backpackThreshold)) {
                    double pct = r != null && bagKnown(r) && r.pollen >= p.backpackThreshold ? r.pollen : lastPct;
                    if (stall) Log.add(String.format(Locale.US, "farm: no pollen for 30 s at %.0f%%: the bag is full", lastPct));
                    in.emergencyRelease(); in.arm();
                    stats.fill(pct);
                    Log.add(String.format(Locale.US, "farm: backpack %.0f%% ≥ %d%% after %s", pct, p.backpackThreshold, Stats.fmt(SystemClock.uptimeMillis() - t0)));
                    hook("field", "🎒 Backpack full", String.format(Locale.US, "%.0f%% after %s in %s", pct, Stats.fmt(SystemClock.uptimeMillis() - t0), Routes.name(p.field)), Webhook.GOLD, true);
                    if (i < sk.size() - 1) unwindCamera(sk, i);
                    return;
                }
                if (s.type == 'W') fieldControl();
            }
            stats.loops++;
            if (Monitor.honey > 0) stats.honey(Monitor.honey);
            if (stats.loops % 5 == 0) Stats.saveCurrent(in, stats);
            // corner mode re-lines up on the walls every loop instead; otherwise loop closure by sight, then the sprinkler
            if (corner == null && !(anchorRef != null && anchorCorrect())) correctDrift();
            if (loop == 2) {   // 1 bar pixel ≈ 0.24%; a big bag can need ~2 loops to show any rise
                Vision.Result r = waitFor(this::bagKnown, 3000);
                if (p.keyboard()) { /* the bag % can fall when capacity grows: the field guard watches this instead */ }
                else if (r == null || r.pollen - startPct < 0.2) throw new Recover("pollen did not rise during two pattern loops — not in a field");
                if (r != null) nav = String.format(Locale.US, "Pine Tree Forest (pollen %.1f%% → %.1f%%)", startPct, r.pollen);
            }
            if (SystemClock.uptimeMillis() - t0 > p.fieldMinutes * 60_000L) { Log.add("farm: field time limit " + p.fieldMinutes + " min"); return; }
            if (mondoDue()) { Log.add("farm: Mondo Chick time, heading home early"); return; }
        }
    }

    // ------------------------------------------------------------------ AI decisions (visible in the panel)
    volatile String decision = "—", why = "", evidence = "", nextAction = "", prevDecision = "—", prevResult = "";
    volatile double decisionConf;
    /** What the AI is doing, a short reason, the visual evidence it used, what comes next. (No hidden reasoning.) */
    private void decide(String what, String because, String ev, String next, double conf) {
        if (!what.equals(decision)) prevDecision = decision;
        decision = what; why = because; evidence = ev; nextAction = next; decisionConf = conf;
    }
    private void decide(String what, String because, double conf) { decide(what, because, "", "", conf); }

    /** Pollen gained per minute, split by the buffs active at the time (compares boost states), and buff reactions. */
    private double lastPct = -1; private long lastPctT; private String lastBuffKey = "";
    private void rate(Vision.Result r) {
        StringBuilder k = new StringBuilder();
        for (Buffs.Effect b : Monitor.activeBuffs()) k.append(k.length() == 0 ? "" : "+").append(b.name);
        String key = k.length() == 0 ? "no buffs" : k.toString();
        if (!key.equals(lastBuffKey)) {
            boolean haste = key.contains("Haste"), hadHaste = lastBuffKey.contains("Haste");
            if (haste != hadHaste) decide("Farming", haste ? "Detected Haste. Player movement is faster than baseline. " + (hasteFix ? "Pattern walks are covered by distance at the live speed." : "Haste correction is off: pattern timing unchanged.")
                    : "Haste ended. Back to base movement timing.", "buff row: " + key, "continue " + patName(), 1);
            else if (key.contains("Bear Morph") && !lastBuffKey.contains("Bear Morph")) decide("Farming", "Bear Morph detected. Added it to the active boost tracker.", "buff row: " + key, "continue " + patName(), 1);
            lastBuffKey = key;
        }
        if (r == null || !bagKnown(r)) return;
        long now = SystemClock.uptimeMillis();
        if (lastPct >= 0 && r.pollen >= lastPct && now - lastPctT < 10_000) stats.rate(key, r.pollen - lastPct, now - lastPctT);
        if (r.pollen >= p.backpackThreshold - 5 && lastPct < p.backpackThreshold - 5)
            decide("Farming", "Backpack approaching threshold. Preparing return-to-hive sequence.", String.format(Locale.US, "bag %.0f%% (threshold %d%%)", r.pollen, p.backpackThreshold), "return to hive at threshold", r.pollenConf);
        lastPct = r.pollen; lastPctT = now;
    }

    /** Where the character stands on screen (shift lock keeps it there). */
    private int[] anchor() { Vision.Result r = Monitor.last; int w = r == null ? 2340 : r.w, h = r == null ? 1080 : r.h; return new int[]{Math.round(p.playerX * w), Math.round(p.playerY * h), h}; }

    static int prioOf(Profile p, Detector.Cat c) {
        switch (c) { case BOOST: return p.prioBoost; case TOKEN: return p.prioToken; case LOOT: return p.prioLoot; case MARK: return p.prioMark; default: return 0; }
    }

    /** Best worthwhile token: CONFIRMED name (≥3 of 4 frames), not renamed, confident, visible now, in range;
     *  priority first, then distance. Returns {track, number of candidates}. */
    private Object[] target() {
        int[] a = anchor(); long now = SystemClock.uptimeMillis();
        Detector.Tracker.Track best = null; double bs = -1; int n = 0;
        for (Detector.Tracker.Track t : Monitor.tracker.snapshot()) {
            Detector.Det d = t.d; int pr = prioOf(p, d.cat);
            if (d.buff || pr <= 0 || t.confirmed() == null || t.renamed || now - t.last > 300 || d.conf < p.boxMinConf) continue;
            double dist = Math.hypot(d.cx() - a[0], d.cy() - a[1]);
            if (dist > p.collectRange * a[2]) continue;
            n++;
            double s = pr * 10_000 - dist;
            if (s > bs) { bs = s; best = t; }
        }
        return new Object[]{best, n};
    }

    /** Collect one token if worth it: steer toward it in short D-pad presses (re-aimed every frame), then walk the
     *  same presses back reversed so Skillet resumes where it left off. Outcome: collected (vanished at the player
     *  after we reached it), still visible, disappeared (away from the player), unverified. Only "collected" counts. */
    private void maybeCollect() {
        if (!p.collectTokens) return;
        Object[] tg = target();
        Detector.Tracker.Track t = (Detector.Tracker.Track) tg[0];
        if (t == null) return;
        String name = t.confirmed(); int id = t.id; double conf = t.d.conf;
        int[] a = anchor();
        int dist = (int) Math.hypot(t.d.cx() - a[0], t.d.cy() - a[1]);
        decide("Collecting token", ((int) tg[1] > 1 ? tg[1] + " nearby tokens detected. Prioritizing the reachable high-value token." : "Reachable token in range."),
                String.format(Locale.US, "%s %.2f, track #%d, %d px away, priority %d", name, conf, id, dist, prioOf(p, t.d.cat)), "walk at most " + p.maxCollectMs + " ms, then return to route", conf);
        phase = "Collecting " + name;
        long t0 = SystemClock.uptimeMillis(), walked = 0;
        java.util.List<Object[]> path = new java.util.ArrayList<>();
        boolean reached = false, gone = false;
        while (walked < p.maxCollectMs) {
            checkpoint();
            Detector.Tracker.Track cur = null;
            for (Detector.Tracker.Track x : Monitor.tracker.snapshot()) if (x.id == id) cur = x;
            if (cur == null) { gone = true; break; }
            double dx = cur.d.cx() - a[0], dy = cur.d.cy() - a[1];
            if (Math.hypot(dx, dy) < 0.06 * a[2]) { reached = true; sleep(250); continue; }
            EnumSet<Moves.Key> ks = EnumSet.noneOf(Moves.Key.class);
            if (Math.abs(dx) > 0.4 * Math.abs(dy)) ks.add(dx > 0 ? Moves.Key.D : Moves.Key.A);
            if (Math.abs(dy) > 0.4 * Math.abs(dx)) ks.add(dy > 0 ? Moves.Key.S : Moves.Key.W);
            if (!in.hold(ks, 150)) break;
            path.add(new Object[]{ks, 150L}); walked += 150;
            sleep(60);
        }
        Detector.Tracker.Ended end = null;
        synchronized (Monitor.tracker) { for (Detector.Tracker.Ended e : Monitor.tracker.history) if (e.id == id) { end = e; break; } }
        String outcome;
        if (!gone) outcome = "still visible";
        else if (end == null) outcome = "unverified";
        else if (end.how.equals("vanished at the player")) outcome = reached ? "collected" : "unverified (vanished near the player before we reached it)";
        else outcome = "disappeared (away from the player: despawned or unknown)";
        if (end != null && end.renamed) outcome += ", recognition changed during tracking";
        decide("Returning to route", "Token " + outcome + ". Returning to farming route.", "walked " + walked + " ms in " + path.size() + " presses", "reverse " + path.size() + " presses, resume " + patName(), 1);
        for (int i = path.size() - 1; i >= 0; i--) {
            checkpoint();
            @SuppressWarnings("unchecked") EnumSet<Moves.Key> ks = (EnumSet<Moves.Key>) path.get(i)[0];
            EnumSet<Moves.Key> back = EnumSet.noneOf(Moves.Key.class);
            for (Moves.Key k : ks) back.add(Moves.flip(k));
            in.hold(back, (Long) path.get(i)[1]);
        }
        long ms = SystemClock.uptimeMillis() - t0;
        stats.token(name, outcome.equals("collected"), ms);
        prevResult = name + ": " + outcome + " (" + ms + " ms)";
        Log.add("token: " + prevResult + String.format(Locale.US, " conf %.2f", conf));
        phase = "Farming";
    }

    /** End of a Skillet loop (camera back to its start angle): if your labelled landmark (sprinkler, placed at the
     *  pattern's start) is off-centre by more than the tolerance, walk toward it, re-checking every frame. No landmark
     *  visible = position unknown, so nothing is moved (logged). */
    private void correctDrift() {
        if (sprinkler != null) { sprinklerDrift(); return; }
        if (!p.driftCorrect) return;
        int[] a = anchor();
        Detector.Tracker.Track lm = null;
        for (int tries = 0; tries < 4 && lm == null; tries++) {
            for (Detector.Tracker.Track t : Monitor.tracker.snapshot())
                if (t.d.cat == Detector.Cat.LANDMARK && !t.d.buff && t.confirmed() != null && t.d.conf >= p.boxMinConf && SystemClock.uptimeMillis() - t.last < 400) lm = t;
            if (lm == null) sleep(150);
        }
        if (lm == null) { decide("Farming", "Position unknown, so no correction (safer than guessing).", "no confirmed landmark visible", "continue " + patName(), 0); Log.add("drift: landmark not visible, no correction"); return; }
        double off = Math.hypot(lm.d.cx() - a[0], lm.d.cy() - a[1]) / a[2];
        if (off <= p.driftTolerance) { prevResult = String.format(Locale.US, "on route (landmark %.0f%% off)", off * 100); return; }
        decide("Correcting drift", "Movement deviation detected. Pausing pattern for position correction.", String.format(Locale.US, "%s %.0f%% of screen off-centre", lm.d.label, off * 100), "walk toward it, then resume " + patName(), lm.d.conf);
        phase = "Correcting drift";
        long walked = 0; int id = lm.id;
        while (walked < p.maxCorrectMs) {
            checkpoint();
            Detector.Tracker.Track cur = null;
            for (Detector.Tracker.Track x : Monitor.tracker.snapshot()) if (x.id == id) cur = x;
            if (cur == null) break;                                     // lost it: stop, don't guess
            double dx = cur.d.cx() - a[0], dy = cur.d.cy() - a[1];
            if (Math.hypot(dx, dy) / a[2] <= p.driftTolerance * 0.5) break;
            EnumSet<Moves.Key> ks = EnumSet.noneOf(Moves.Key.class);
            if (Math.abs(dx) > 0.4 * Math.abs(dy)) ks.add(dx > 0 ? Moves.Key.D : Moves.Key.A);
            if (Math.abs(dy) > 0.4 * Math.abs(dx)) ks.add(dy > 0 ? Moves.Key.S : Moves.Key.W);
            if (!in.hold(ks, 150)) break;
            walked += 150; sleep(60);
        }
        stats.corrections++;
        prevResult = "drift correction: walked " + walked + " ms";
        Log.add("drift: " + String.format(Locale.US, "%.0f%%", off * 100) + " off → " + prevResult);
        phase = "Farming";
    }

    /** The sprinkler over 3 fresh frames (bees fly over it): the median spot when seen in at least 2, else null. */
    private double[] sprinklerSteady() {
        List<double[]> seen = new java.util.ArrayList<>();
        long since = SystemClock.uptimeMillis();
        for (int i = 0; i < 3; i++) {
            if (Monitor.fresh(since, 800) == null) break;
            since = Monitor.lastTime;
            double[] s = sprinklerNow(); if (s != null) seen.add(s);
        }
        if (seen.size() < 2) return null;
        seen.sort((x, y) -> Double.compare(x[0], y[0])); double mx = seen.get(seen.size() / 2)[0];
        seen.sort((x, y) -> Double.compare(x[1], y[1])); double my = seen.get(seen.size() / 2)[1];
        return new double[]{mx, my};
    }

    /** Natro nm_fieldDriftCompensation with the learned sprinkler: if it is off-centre, walk toward it until it is
     *  back under the character (re-found every step; out of sight = stop, don't guess). Up to 5 s. */
    private void sprinklerDrift() {
        int[] a = anchor();
        double[] s = sprinklerSteady();
        if (s == null) { prevResult = "sprinkler not in view (likely under the character)"; return; }
        double off = Math.hypot(s[0] - a[0], s[1] - a[1]) / a[2];
        if (off <= p.driftTolerance) { prevResult = String.format(Locale.US, "near the sprinkler (%.0f%% off)", off * 100); return; }
        phase = "Correcting drift";
        long walked = 0;
        while (walked < 5000 && s != null) {
            checkpoint();
            double dx = s[0] - a[0], dy = s[1] - a[1];
            if (Math.hypot(dx, dy) / a[2] <= p.driftTolerance * 0.5) break;
            EnumSet<Moves.Key> ks = EnumSet.noneOf(Moves.Key.class);
            if (Math.abs(dx) > 0.4 * Math.abs(dy)) ks.add(dx > 0 ? Moves.Key.D : Moves.Key.A);
            if (Math.abs(dy) > 0.4 * Math.abs(dx)) ks.add(dy > 0 ? Moves.Key.S : Moves.Key.W);
            if (!in.hold(ks, 180)) break;
            walked += 180; sleep(80);
            s = sprinklerNow();
        }
        stats.corrections++;
        prevResult = String.format(Locale.US, "sprinkler drift: %.0f%% off, walked %d ms back", off * 100, walked);
        Log.add("drift: " + prevResult);
        phase = "Farming";
    }

    /** The pattern's camera moves net to zero over a full loop; if we leave mid-loop, undo the partial rotation. */
    private void unwindCamera(List<Moves.Step> sk, int last) {
        int yaw = 0, pitch = 0;
        for (int i = 0; i <= last; i++) { if (sk.get(i).type == 'R') yaw += sk.get(i).count; if (sk.get(i).type == 'T') pitch += sk.get(i).count; }
        if (yaw != 0) in.rotate(-yaw);
        if (pitch != 0) in.tilt(-pitch);
    }

    String detail() {
        return "Preset: " + Profile.PRESET + "\nNav: " + nav + "\nStep: " + step + (reason.isEmpty() ? "" : "\nNote: " + reason)
                + String.format(Locale.US, "\nAI THINKING / DECISIONS\n  Action: %s (%.2f)\n  Why: %s%s%s\n  Previous: %s%s", decision, decisionConf, why,
                        evidence.isEmpty() ? "" : "\n  Evidence: " + evidence, nextAction.isEmpty() ? "" : "\n  Next: " + nextAction, prevDecision, prevResult.isEmpty() ? "" : " · " + prevResult)
                + "\n" + stats.summary();
    }
}
