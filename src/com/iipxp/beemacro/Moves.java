package com.iipxp.beemacro;

import java.util.ArrayList;
import java.util.List;

/**
 * Movement programs as plain data. Pure Java (no Android) so it is unit-tested on desktop.
 * Skillet ported 1:1 from Natro Macro v1.1.2 patterns/Skillet.ahk (Dully176): nm_Walk(tiles, k1, k2) = hold keys for
 * tiles*4000/moveSpeed ms. Travel uses the routes the user recorded on this phone (RecordedRoutes), replayed exactly.
 */
public final class Moves {
    public enum Key { W, A, S, D, SPACE, SHIFT }

    public static final class Step {
        /** 'W' walk tiles, 'Y' replay a recorded route (label = name), 'U' walk until a prompt shows
         *  (count = max ms, label = what), 'R' yaw rotate (+right), 'T' tilt (+up), 'P' pause ms. */
        public final char type;
        public final Key[] keys;
        public final double tiles;
        public final int count;
        public final String label;
        public Step(char type, Key[] keys, double tiles, int count, String label) {
            this.type = type; this.keys = keys; this.tiles = tiles; this.count = count; this.label = label;
        }
        public long ms(double moveSpeed) {
            if (type == 'W') return tileMs(tiles, moveSpeed);
            if (type == 'P') return count;
            if (type == 'Y') { int e = 0; for (int[] s : recorded(label)) e = Math.max(e, s[0] + s[s.length - 1]); return e; }
            if (type == 'H' || type == 'K') return count;
            return 0;
        }
        static String k(Key[] ks) { StringBuilder b = new StringBuilder(); for (Key x : ks) b.append(x).append('+'); return b.length() == 0 ? "-" : b.substring(0, b.length() - 1); }
        @Override public String toString() {
            switch (type) {
                case 'W': return k(keys) + " " + tiles + "t";
                case 'H': return "hold " + k(keys) + " " + count + "ms" + (tiles != 1.0 ? " (recorded at x" + tiles + " speed)" : "");
                case 'K': return "tap " + label;
                case 'X': return "press " + label;
                case 'Q': return "keyboard route " + label + " (" + count / 1000.0 + " s)";
                case 'Y': return "replay " + label + " (" + recorded(label).length + " touches, " + ms(0) / 1000.0 + " s)";
                case 'U': return k(keys) + " until " + label + " (max " + count + "ms)";
                case 'R': return "Rot" + (count > 0 ? "Right " : "Left ") + Math.abs(count);
                case 'T': return "Rot" + (count > 0 ? "Up " : "Down ") + Math.abs(count);
                default: return "Sleep " + count;
            }
        }
    }

    public static int[][] recorded(String name) {
        switch (name) {
            case "TO_FIELD": return RecordedRoutes.TO_FIELD;
            case "TO_HIVE": return RecordedRoutes.TO_HIVE;
            default: throw new IllegalArgumentException(name);
        }
    }

    /** Position of a recorded stroke {startMs, x,y,t, ...} at t ms after its start, linearly interpolated, scaled. */
    public static float[] at(int[] s, int t, float sx, float sy) {
        float x = s[1], y = s[2];
        for (int k = 4; k + 2 < s.length; k += 3) {
            if (s[k + 2] <= t) { x = s[k]; y = s[k + 1]; continue; }
            float f = (t - s[k - 1]) / (float) Math.max(1, s[k + 2] - s[k - 1]);
            x = s[k - 3] + (s[k] - s[k - 3]) * f; y = s[k - 2] + (s[k + 1] - s[k - 2]) * f;
            break;
        }
        return new float[]{x * sx, y * sy};
    }

    /** Opposite direction (used to step back after overrunning a prompt). */
    static Key flip(Key k) { switch (k) { case W: return Key.S; case S: return Key.W; case A: return Key.D; case D: return Key.A; default: return k; } }
    static Key[] flip(Key[] ks) { if (ks == null) return null; Key[] o = new Key[ks.length]; for (int i = 0; i < ks.length; i++) o[i] = flip(ks[i]); return o; }

    /** Natro legacy walk: HyperSleep(4000/MoveSpeedNum*tiles). 4 studs per tile. */
    /** Walk speed as a multiple of your movespeed stat, Natro DetectMovespeed (lib/Walk.ahk, GPL-3.0):
     *  (base + 10 Coconut Haste + 4 Bear Morph) × Hasty Guard 1.1 × Gifted Hasty 1.15 × hasteMul (1 + 0.1/stack)
     *  × Haste+ 2 × Oil 1.2 × Super Smoothie 1.25. moveSpeed already includes Hasty Guard / Gifted Hasty, so the flat
     *  bonuses are compared with the base before those. */
    public static double speedFactor(double moveSpeed, boolean hastyGuard, boolean giftedHasty, double hasteMul,
                                     boolean coconut, boolean bear, boolean hastePlus, boolean oil, boolean smoothie) {
        double base = moveSpeed / (hastyGuard ? 1.1 : 1) / (giftedHasty ? 1.15 : 1);
        return (base + (coconut ? 10 : 0) + (bear ? 4 : 0)) / base * hasteMul * (hastePlus ? 2 : 1) * (oil ? 1.2 : 1) * (smoothie ? 1.25 : 1);
    }
    public static long tileMs(double tiles, double moveSpeed) { return Math.round(4000.0 / moveSpeed * tiles); }

    private final List<Step> out = new ArrayList<>();
    private Moves walk(double t, Key... k) { out.add(new Step('W', k, t, 0, null)); return this; }
    /** dy_Walk = nm_Walk then Sleep(30). */
    private Moves dy(double t, Key... k) { walk(t, k); return sleep(30); }
    private Moves rot(int n) { out.add(new Step('R', null, 0, n, null)); return this; }
    private Moves tilt(int n) { out.add(new Step('T', null, 0, n, null)); return this; }
    private Moves sleep(int ms) { out.add(new Step('P', null, 0, ms, null)); return this; }
    private Moves until(String what, int maxMs, Key... k) { out.add(new Step('U', k, 0, maxMs, what)); return this; }
    private Moves replay(String name) { out.add(new Step('Y', null, 0, 0, name)); return this; }

    /** Hive (right after a respawn, shift lock on) -> Pine Tree Forest ON FOOT, as the user walked it on the D-pad.
     *  (Cannon routes were tried first: the walk was repeatable but the glide landed in different fields.) */
    // ---- Natro Macro paths (NatroTeam/NatroMacro, GPL-3.0), keyboard version. 'H' steps are walked by DISTANCE at
    // the live Haste speed (like Natro's Walk(n)): count = base-speed ms for the tiles at moveSpeed.
    private Moves wk(double tiles, double moveSpeed, Key... k) { out.add(new Step('H', k, 1.0, (int) tileMs(tiles, moveSpeed), null)); return this; }
    private Moves script(String name) { out.add(new Step('Q', null, 0, RecordedKeys.durationMs(RecordedKeys.get(name)), name)); return this; }

    /** nm_gotoRamp: from the hive pad (camera facing the hive, Shift Lock on) forward 5, then right along the hives. */
    public static List<Step> natroToRamp(int hiveSlot, double ms) { return new Moves().wk(5, ms, W).wk(9.2 * hiveSlot - 4, ms, D).out; }

    /** Natro slotMove: from the central spawn (camera facing the hives) to the pad of hive slot 1-6. */
    public static List<Step> natroSpawnToHive(int slot, double ms) {
        Moves m = new Moves();
        switch (slot) {
            case 1: m.wk(4, ms, D).wk(20, ms, D, W); break;
            case 2: m.wk(13, ms, W, D).wk(6, ms, W); break;
            case 4: m.wk(13, ms, A, W).wk(6, ms, W); break;
            case 5: m.wk(4, ms, A).wk(20, ms, A, W); break;
            case 6: m.wk(12, ms, A, W).wk(13, ms, A).wk(10, ms, A, W); break;
            default: m.wk(20, ms, W).wk(4, ms, S);
        }
        return m.out;
    }

    /** paths/gtf-pinetree.ahk, walk branch: from the ramp (after natroToRamp) on foot to Pine Tree Forest. The camera
     *  ends turned 180° from the hive, the same pose the cannon branch ends in. */
    /** Natro gtp-mountaintop walk (after natroToRamp), then nm_Mondo's 14 tiles Right to stand off the field. */
    public static List<Step> natroToMondo(double ms) {
        return new Moves().wk(67.5, ms, S, A).rot(4).wk(31, ms, W).wk(7.8, ms, A).wk(10, ms, S).wk(5, ms, D).wk(1.5, ms, W)
                .wk(60, ms, A).wk(3.75, ms, D).wk(85, ms, W).wk(45, ms, D).wk(50, ms, S).wk(60, ms, D)
                .wk(5, ms, A).wk(7, ms, W).wk(9, ms, W, A).wk(16.5, ms, W).rot(4).wk(14, ms, D).out;
    }

    public static List<Step> natroToPine(double ms) {
        return new Moves().wk(67.5, ms, S, A).rot(4).wk(31, ms, W).wk(7.8, ms, A).wk(10, ms, S).wk(5, ms, D).wk(1.5, ms, W)
                .wk(60, ms, A).wk(3.75, ms, D).wk(38, ms, W).wk(33, ms, A, W).out;
    }

    /** paths/wf-pinetree.ahk, walk branch: Pine Tree Forest (after Skillet, camera back to its arrival angle) to
     *  the hive pads; slot 3 ends centred on its pad, other slots walk on to the ramp side and centre (Natro). */
    public static List<Step> natroPineHome(int hiveSlot, double ms) {
        Moves m = new Moves();
        m.wk(31, ms, W).wk(75, ms, D).rot(-4).sleep(50)
         .wk(20, ms, W).wk(3, ms, W, A).wk(18, ms, W).wk(6, ms, W, D).wk(10, ms, D).wk(2, ms, A)
         .wk(6, ms, W).script("JUMP_W").wk(108 - 200 * ms / 4000.0, ms, W);   // the 200 ms jump already covered ~1.7 tiles
        if (hiveSlot == 3) m.wk(2.7, ms, S);
        else m.wk(1.5, ms, S).wk(35, ms, D).wk(2.7, ms, S);
        return m.out;
    }

    /** Natro nm_setSprinkler (Supreme/Basic sprinkler: one placement): field length/width in ms at movespeed 18,
     *  scaled by distance/10 and 18/movespeed; Pine Tree is 2500 × 1750. Returns {forward ms (+) / back (−), right
     *  (+) / left (−)}. */
    public static int[] natroSprinklerMove(String loc, int dist, double moveSpeed) {
        double f = Math.round(18 / moveSpeed * 100) / 100.0, len = 2500 * dist / 10.0 * f, wid = 1750 * dist / 10.0 * f;
        String l = loc.toLowerCase(java.util.Locale.US);
        int fb = l.contains("upper") ? (int) len : l.contains("lower") ? -(int) len : 0;
        int lr = l.contains("left") ? -(int) wid : l.contains("right") ? (int) wid : 0;
        return new int[]{fb, lr};
    }

    /** Keyboard route (tablet): the user's recorded key presses, replayed exactly. */
    public static List<Step> toPineTreeKb() { Moves m = new Moves(); m.out.add(new Step('Q', null, 0, RecordedKeys.durationMs(RecordedKeys.TO_FIELD_KB), "TO_FIELD_KB")); return m.sleep(1000).out; }

    public static List<Step> toPineTree() { Moves m = new Moves(); m.out.addAll(keyHolds("TO_FIELD")); return m.sleep(1000).out; }

    /** Pine Tree -> hive on foot as the user walked it; then, like Natro's nm_findHiveSlot, walk along the hives
     *  until OUR prompt shows (ends at once if the replay already stopped on it). */
    public static List<Step> toHive() { Moves m = new Moves(); m.out.addAll(keyHolds("TO_HIVE")); return m.sleep(500).until("own hive", 10000, Key.A).out; }

    // D-pad geometry on the S23 (2340x1080): centre of the pad and one cell.
    static final int PAD_X = 417, PAD_Y = 680, PAD_CELL = 188;

    /** Walk speed (multiple of base 33.35) the user had while recording, by time into the route — read with
     *  Vision.hasteStacks from the clock-synced screenshots: TO_HIVE none until ~3.8 s, x4 until ~8.1 s, x5 until
     *  ~14.0 s, then x7 to the end; TO_FIELD none. BSS Haste = +10% per stack (x10 ≈ 66.7 = 2x base). */
    static double recordedSpeed(String route, int ms) {
        if (route.equals("TO_HIVE")) return ms < 3800 ? 1.0 : ms < 8100 ? 1.4 : ms < 14000 ? 1.5 : 1.7;
        return 1.0;
    }

    /** Average recorded speed over [start, start+dur) — stacks changed in the middle of some presses. */
    public static double recordedSpeed(String route, int start, int dur) {
        if (dur <= 0) return recordedSpeed(route, start);
        double sum = 0; int n = 0;
        for (int t = start; t < start + dur; t += 50) { sum += recordedSpeed(route, t); n++; }
        return Math.round(sum / n * 1000) / 1000.0;
    }

    /** The recording as discrete D-pad key holds ('H': keys, count = ms, tiles = recorded speed multiple), jump taps
     *  on the pad centre, other taps (hotbar) as 'K' (label "x,y" in 2340x1080 px), and the pauses in between. */
    public static List<Step> keyHolds(String route) {
        int[][] st = recorded(route).clone();
        java.util.Arrays.sort(st, (p, q) -> Integer.compare(p[0], q[0]));
        Moves m = new Moves();
        int t = 0;
        for (int[] s : st) {
            int start = s[0], dur = s[s.length - 1];
            if (start > t) m.sleep(start - t);
            int x = s[1], y = s[2];
            boolean onPad = Math.abs(x - PAD_X) < PAD_CELL * 1.6 && Math.abs(y - PAD_Y) < PAD_CELL * 1.6;
            int dx = onPad ? (int) Math.max(-1, Math.min(1, Math.round((x - PAD_X) / (double) PAD_CELL))) : 0;
            int dy = onPad ? (int) Math.max(-1, Math.min(1, Math.round((y - PAD_Y) / (double) PAD_CELL))) : 0;
            if (onPad && (dx != 0 || dy != 0)) {
                List<Key> ks = new ArrayList<>();
                if (dy < 0) ks.add(Key.W); if (dy > 0) ks.add(Key.S); if (dx < 0) ks.add(Key.A); if (dx > 0) ks.add(Key.D);
                m.out.add(new Step('H', ks.toArray(new Key[0]), recordedSpeed(route, start, dur), Math.max(1, dur), null));
            } else {
                m.out.add(new Step('K', null, 0, Math.max(60, dur), onPad ? PAD_X + "," + PAD_Y : x + "," + y));   // jump / hotbar tap
            }
            t = Math.max(t, start + dur);
        }
        return m.out;
    }

    static final Key W = Key.W, A = Key.A, S = Key.S, D = Key.D;

    /** Hotbar slot (1-7) of a tap recorded on the S23 hotbar (slot 1 at x≈760, 135 px apart). */
    public static int hotbarSlot(int x) { return Math.max(1, Math.min(7, 1 + (int) Math.round((x - 760) / 135.0))); }

    /** Skillet by Dully176 — order, timings and key combos preserved exactly. */
    public static List<Step> skillet(double dwFDC, double hwFDC, double crFDC, double dwAlign, double hwAlign) {
        Moves m = new Moves();
        m.tilt(4).sleep(50);                                   // Send("{RotUp 4}"), Sleep(50)

        m.dy(3 + hwFDC, D).dy(1.5, W).dy(6, A).dy(1.5, W).dy(6 + hwFDC + hwAlign, D);
        m.rot(-1).sleep(50);
        m.dy(6 + dwFDC, S, A).dy(1.5, W, A).dy(6, W, D).dy(1.5, W, A).dy(3 + dwFDC, S, A);
        m.rot(1).sleep(50);
        m.dy(3 + dwFDC, S).dy(1.5, A).dy(6, W).dy(1.5, A).dy(6 + dwFDC, S);
        if (dwAlign > 0) m.dy(dwAlign + 2, S).dy(dwAlign, W);
        m.rot(-2).sleep(50);
        m.dy(6 + hwFDC, S).dy(1.5, D).dy(6, W).dy(1.5, D).dy(3 + hwFDC, S);
        m.rot(2).sleep(100);
        m.dy(3, W, D).dy(1.5, W, A).dy(6, S, A).dy(1.5, W, A).dy(6, W, D);
        m.rot(-1).sleep(50);
        m.dy(6 + crFDC, S).dy(1.5, A).dy(6, W).dy(1.5, A).dy(3 + crFDC, S);
        m.rot(1).sleep(50);
        m.dy(3 + crFDC, S, D).dy(1.5, S, A).dy(6, W, A).dy(1.5, S, A).dy(6 + crFDC, S, D);
        m.rot(-2).sleep(50);
        m.dy(6, S, D).dy(1.5, W, D).dy(6, W, A).dy(1.5, W, D).dy(3, S, D);
        m.rot(2).sleep(50);
        m.tilt(-4).sleep(50);                                  // Send("{RotDown 4}"), Sleep(50)
        return m.out;
    }

    /** From spawn (no hive owned) onto pad 3 with the D-pad; measured live: 2.6 s forward runs into the hive wall,
     *  then 0.45 s back lands on the pad ("Make Honey" / "Claim Hive" shows). */
    public static List<Step> spawnToHive3(double moveSpeed) {
        Moves m = new Moves();
        m.walk(2600 * moveSpeed / 4000.0, W).sleep(300).walk(450 * moveSpeed / 4000.0, S);
        return m.out;
    }

    public static long totalMs(List<Step> steps, double moveSpeed, long rotMs) {
        long t = 0;
        for (Step s : steps) t += s.type == 'R' || s.type == 'T' ? rotMs : s.ms(moveSpeed);
        return t;
    }
}
