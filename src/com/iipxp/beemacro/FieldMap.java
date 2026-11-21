package com.iipxp.beemacro;

/**
 * A coarse map of what surrounds the character, rebuilt from the screen. Roblox gives no coordinates to an outside
 * app, so "position" here means: how much field lies in each direction before its boundary (hedge, wall or bare
 * ground), in grid cells, relative to the camera. That works for any field shape, size or place.
 * FIELD means Pine Tree: blue flowers on grass, never red ones, so a neighbouring field counts as boundary.
 * Each cell is FIELD, EDGE or UNKNOWN; anything that hides the ground (bees, balloons, bubbles, the HUD) stays UNKNOWN
 * and is looked through, so it can't be mistaken for a boundary. Pure Java; runs on the Remap (S23-layout) frame.
 */
public final class FieldMap {
    public static final int CW = 40, CH = 24, NO_EDGE = 99;
    public static final byte UNKNOWN = 0, FIELD = 1, EDGE = 2, HUD = 3;
    private static final byte OTHER = 4;
    public enum State { UNKNOWN, INSIDE, OUTSIDE }

    public static final class Read {
        public final byte[] cells = new byte[CW * CH];
        public int left = NO_EDGE, right = NO_EDGE, up = NO_EDGE, down = NO_EDGE;   // cells to the boundary
        public double ring;          // share of FIELD among the readable cells around the character
        public int known;            // how many cells around the character were readable
        public double cx, cy;        // middle of the visible field relative to the character, in cells (+x right, +y up)
        public int fieldCells, redPatches;
        public boolean wrongField;   // red flowers all around: standing in another field
        public State state = State.UNKNOWN;
        public int nearest() { return Math.min(left, Math.min(right, Math.min(up, down))); }
        @Override public String toString() {
            return String.format(java.util.Locale.US, "%s L%s R%s F%s B%s ring %.2f%s", state, m(left), m(right), m(up), m(down), ring, wrongField ? " WRONG FIELD" : "");
        }
        private static String m(int v) { return v == NO_EDGE ? "-" : "" + v; }
    }

    /** Screen areas covered by the game's interface, as fractions of the world view. */
    static boolean hud(double x, double y) {
        return (y < .085 && x > .0 && x < .74) || (x < .29 && y < .20) || (x > .29 && x < .82 && y > .815)
                || (x > .68 && y > .84) || (x > .815 && y > .185 && y < .375) || (x > .93 && y < .17);
    }

    static final int PCT = 90;   // 90th percentile: dark hedge in view doesn't skew it, night does
    /** Night darkens the world (not the HUD): scale colours so the bright end of the view is like daytime. */
    public static double light(int[] px, int w, int h, double wx0, double wx1) {
        int[] hist = new int[256]; int n = 0;
        for (int y = (int) (.2 * h); y < (int) (.8 * h); y += 12) for (int x = (int) (wx0 + .05 * (wx1 - wx0)); x < (int) (wx0 + .8 * (wx1 - wx0)); x += 12) {
            int p = px[y * w + x]; hist[Math.max((p >> 16) & 255, Math.max((p >> 8) & 255, p & 255))]++; n++;
        }
        int acc = 0, ref = 0; for (int v = 0; v < 256; v++) { acc += hist[v]; if (acc >= n * PCT / 100) { ref = v; break; } }
        return Math.max(1, Math.min(3, 225.0 / Math.max(1, ref)));
    }

    public static Read analyze(int[] px, int w, int h, double wx0, double wx1) {
        Read r = new Read();
        double lk = light(px, w, h, wx0, wx1);
        boolean[] redCell = new boolean[CW * CH];
        double cw = (wx1 - wx0) / CW, ch = (double) h / CH;
        int step = Math.max(4, (int) (cw / 7));
        for (int j = 0; j < CH; j++) for (int i = 0; i < CW; i++) {
            if (hud((i + .5) / CW, (j + .5) / CH)) { r.cells[j * CW + i] = HUD; continue; }
            int x0 = (int) (wx0 + i * cw), x1 = (int) (wx0 + (i + 1) * cw), y0 = (int) (j * ch), y1 = Math.min(h, (int) ((j + 1) * ch));
            int n = 0, blue = 0, white = 0, red = 0, grass = 0, hedge = 0, wall = 0;
            for (int y = y0; y < y1; y += step) for (int x = x0; x < x1; x += step) {
                int p = px[y * w + x], R = Math.min(255, (int) (((p >> 16) & 255) * lk)), G = Math.min(255, (int) (((p >> 8) & 255) * lk)), B = Math.min(255, (int) ((p & 255) * lk));
                int mx = Math.max(R, Math.max(G, B)), mn = Math.min(R, Math.min(G, B)); n++;
                if (R >= 95 && R >= 2 * G && R >= 2 * B && Math.abs(G - B) < 18) red++;   // red petals, bright or already harvested
                else if (B > 150 && R < 110 && G < 150) blue++;
                else if (mn > 190 && mx - mn < 30) white++;
                else if (G > R + 10 && G > B + 5 && mx < 95) hedge++;
                else if (G > R + 25 && G > B + 25) grass++;
                else if ((mx - mn < 28 && mx >= 60 && mx <= 190) || (R > G && G > B && R - B > 25 && R < 190)) wall++;
            }
            // solid blue with no grass is a balloon, not flowers; honey tiles follow the player anywhere, so they prove nothing
            double N = Math.max(1, n), flowers = (blue + Math.min(white, blue * 2)) / N;
            boolean balloon = blue / N > .6 && grass / N < .08;
            redCell[j * CW + i] = red / N >= .05;
            r.cells[j * CW + i] = red / N >= .05 ? EDGE : balloon ? UNKNOWN : flowers >= .10 ? FIELD : hedge / N >= .45 || wall / N >= .55 || (grass + hedge) / N >= .80 ? EDGE : UNKNOWN;
        }
        // a patch of red flowers (not one red token or the backpack) marks everything around it as another field
        for (int j = 2; j < CH - 2; j++) for (int i = 2; i < CW - 2; i++) {
            int n = 0;
            for (int y = j - 2; y <= j + 2; y++) for (int x = i - 2; x <= i + 2; x++) if (redCell[y * CW + x]) n++;
            if (n < 5) continue;
            for (int y = j - 2; y <= j + 2; y++) for (int x = i - 2; x <= i + 2; x++) if (r.cells[y * CW + x] == FIELD) r.cells[y * CW + x] = OTHER;
        }
        for (int k = 0; k < r.cells.length; k++) if (r.cells[k] == OTHER) r.cells[k] = EDGE;
        // red flowers on at least three sides of the character: we are standing in another field, nothing here is Pine Tree
        // (flowers are many small separate patches; a red tool or bee part is one or two solid shapes)
        int patches = redPatches(redCell), sides = 0; int[] side = new int[4];
        for (int j = 0; j < CH; j++) for (int i = 0; i < CW; i++) {
            int dx = i - CW / 2, dy = j - CH / 2;
            if (redCell[j * CW + i] && Math.abs(dx) <= 14 && Math.abs(dy) <= 10) side[(dx < 0 ? 0 : 1) + (dy < 0 ? 0 : 2)]++;
        }
        for (int c : side) if (c >= 2) sides++;
        r.redPatches = patches;
        r.wrongField = sides >= 3 && patches >= WRONG_FIELD_PATCHES;
        if (r.wrongField) for (int k = 0; k < r.cells.length; k++) if (r.cells[k] == FIELD) r.cells[k] = EDGE;
        finish(r);
        return r;
    }

    static final int WRONG_FIELD_PATCHES = 9;   // measured: other fields 9-16, Pine Tree 0-8
    private static int redPatches(boolean[] red) {
        boolean[] seen = new boolean[red.length]; int[] stack = new int[red.length]; int count = 0;
        for (int k = 0; k < red.length; k++) {
            if (!red[k] || seen[k]) continue;
            count++; int top = 0; stack[top++] = k; seen[k] = true;
            while (top > 0) {
                int c = stack[--top], i = c % CW, j = c / CW;
                for (int d = 0; d < 4; d++) {
                    int x = i + (d == 0 ? 1 : d == 1 ? -1 : 0), y = j + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    if (x < 0 || y < 0 || x >= CW || y >= CH) continue;
                    int n = y * CW + x;
                    if (red[n] && !seen[n]) { seen[n] = true; stack[top++] = n; }
                }
            }
        }
        return count;
    }

    /** Distances, field middle and inside/outside from the classified cells. */
    static void finish(Read r) {
        int pi = CW / 2, pj = CH / 2;
        r.left = ray(r, pi, pj, -1, 0); r.right = ray(r, pi, pj, 1, 0); r.up = ray(r, pi, pj, 0, -1); r.down = ray(r, pi, pj, 0, 1);
        double sx = 0, sy = 0; int f = 0, ringF = 0, ringE = 0;
        for (int j = 0; j < CH; j++) for (int i = 0; i < CW; i++) {
            byte c = r.cells[j * CW + i];
            int dx = i - pi, dy = pj - j, ch = Math.max(Math.abs(dx), Math.abs(dy));
            if (c == FIELD) { sx += dx; sy += dy; f++; }
            if (ch >= 3 && ch <= 6) { if (c == FIELD) ringF++; else if (c == EDGE) ringE++; }
        }
        r.fieldCells = f; r.cx = f == 0 ? 0 : sx / f; r.cy = f == 0 ? 0 : sy / f;
        r.known = ringF + ringE; r.ring = r.known == 0 ? 0 : ringF / (double) r.known;
        r.state = r.known < 16 ? State.UNKNOWN : r.ring < .25 ? State.OUTSIDE : State.INSIDE;
    }

    /** Walk outward in a 5-cell-wide band; the boundary is the first of 3 steps in a row that are mostly EDGE. */
    private static int ray(Read r, int pi, int pj, int dx, int dy) {
        int run = 0;
        for (int d = 2; d < Math.max(CW, CH); d++) {
            int f = 0, e = 0, x = pi + dx * d, y = pj + dy * d;
            if (x < 0 || y < 0 || x >= CW || y >= CH) break;
            for (int k = -2; k <= 2; k++) {
                int i = x + (dy != 0 ? k : 0), j = y + (dx != 0 ? k : 0);
                if (i < 0 || j < 0 || i >= CW || j >= CH) continue;
                byte c = r.cells[j * CW + i];
                if (c == FIELD) f++; else if (c == EDGE) e++;
            }
            if (e >= 3 && e > f) { if (++run == 3) return d - 2; } else if (f > 0) run = 0;
        }
        return NO_EDGE;
    }

    // ------------------------------------------------------------------ steadying over several frames
    /** Keeps the last few readings; answers with the median so one bad frame can't cause a correction. */
    public static final class Tracker {
        private final Read[] last = new Read[5]; private int n, at; private long lastT;
        public synchronized void clear() { n = 0; at = 0; }
        public synchronized void add(Read r, long now) {
            if (now - lastT > 1500) { n = 0; at = 0; }   // a gap (lag, menu): old readings no longer apply
            lastT = now; last[at] = r; at = (at + 1) % last.length; n = Math.min(n + 1, last.length);
        }
        /** Median reading, or null until enough recent frames agree it is worth using. */
        public synchronized Read steady(long now) {
            if (n < 3 || now - lastT > 1000) return null;
            Read out = new Read(), newest = last[(at + last.length - 1) % last.length];
            System.arraycopy(newest.cells, 0, out.cells, 0, out.cells.length);
            int[] a = new int[n]; double[] d = new double[n];
            out.left = med(a, x -> x.left); out.right = med(a, x -> x.right); out.up = med(a, x -> x.up); out.down = med(a, x -> x.down);
            out.known = med(a, x -> x.known); out.fieldCells = med(a, x -> x.fieldCells);
            out.ring = medD(d, x -> x.ring); out.cx = medD(d, x -> x.cx); out.cy = medD(d, x -> x.cy);
            out.state = out.known < 16 ? State.UNKNOWN : out.ring < .25 ? State.OUTSIDE : State.INSIDE;
            return out;
        }
        private int med(int[] a, java.util.function.ToIntFunction<Read> f) { for (int i = 0; i < n; i++) a[i] = f.applyAsInt(last[i]); java.util.Arrays.sort(a, 0, n); return a[n / 2]; }
        private double medD(double[] a, java.util.function.ToDoubleFunction<Read> f) { for (int i = 0; i < n; i++) a[i] = f.applyAsDouble(last[i]); java.util.Arrays.sort(a, 0, n); return a[n / 2]; }
    }

    // ------------------------------------------------------------------ steering
    /** Way back toward the middle as {x, y} in -1/0/1 (+x right, +y forward). Uses the room on each side; when no edge
     *  is in view on an axis it stays neutral unless the visible field clearly lies that way. */
    public static int[] steer(Read r, int margin) {
        return new int[]{axis(r.left, r.right, r.cx, margin), axis(r.down, r.up, r.cy, margin)};
    }
    private static int axis(int minus, int plus, double centre, int margin) {
        boolean lowMinus = minus < margin, lowPlus = plus < margin;
        if (lowMinus && !lowPlus) return 1;
        if (lowPlus && !lowMinus) return -1;
        if (lowMinus) return Integer.compare(plus, minus);   // squeezed on both sides: toward the roomier one
        return 0;
    }
    /** For recovery: straight toward the field that is in view. */
    public static int[] toField(Read r) { return new int[]{Math.abs(r.cx) < 2 ? 0 : r.cx > 0 ? 1 : -1, Math.abs(r.cy) < 2 ? 0 : r.cy > 0 ? 1 : -1}; }

    /** Decides corrections. Starts when the nearest edge is inside the margin and keeps going until there is clear room
     *  (so it doesn't flicker on and off at the line), won't reverse an axis it just corrected, and leaves a gap
     *  between pulses so each one is judged on fresh frames. Pulses are short and scale with how close the edge is. */
    public static final class Controller {
        public static final class Move { public int x, y; public long ms; }
        public int margin = 5, corrections; public boolean correcting;
        private int lastX, lastY; private long lastXAt = -100_000, lastYAt = -100_000, nextAt;
        public Move update(Read r, long now, double speed) {
            if (r == null || r.state != State.INSIDE) return null;
            int near = r.nearest();
            if (!correcting && near < margin) correcting = true; else if (correcting && near >= margin + 2) correcting = false;
            if (!correcting || now < nextAt) return null;
            int[] s = steer(r, margin + 2);
            int x = s[0], y = s[1];
            if (x != 0 && x == -lastX && now - lastXAt < 2500) x = 0;
            if (y != 0 && y == -lastY && now - lastYAt < 2500) y = 0;
            if (x == 0 && y == 0) return null;
            Move m = new Move(); m.x = x; m.y = y;
            m.ms = Math.round(Math.max(180, Math.min(520, 180 + 70 * (margin + 2 - near))) / Math.max(1, speed));
            if (x != 0) { lastX = x; lastXAt = now; }
            if (y != 0) { lastY = y; lastYAt = now; }
            nextAt = now + m.ms + 500; corrections++;
            return m;
        }
    }

    /** How close an edge may be before a pattern step of this length is skipped. */
    public static int stepLimit(double tiles) { return Math.max(2, Math.min(8, (int) Math.ceil(tiles) + 1)); }

    /** Is walking (x, y) heading into a boundary closer than limit? */
    public static boolean blocked(Read r, int x, int y, int limit) {
        return (x < 0 && r.left < limit) || (x > 0 && r.right < limit) || (y > 0 && r.up < limit) || (y < 0 && r.down < limit);
    }
}
