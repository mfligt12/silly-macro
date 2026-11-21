package com.iipxp.beemacro;

/**
 * What the screen says about where we are, independent of the route being walked. Pure Java, runs on the S23-layout
 * frame from {@link Remap}. Three readings:
 *  - motion: does the world behind the HUD change? (median of coarse cells, so bees flying past don't count)
 *  - view: how much Pine Tree flower (blue/white, no red) lies left, right and ahead of the character
 *  - steer: stay, step back toward the flowers, or "lost" when none are in sight
 * Directions are camera-relative, like W/A/S/D, so they stay right whatever the camera angle is.
 */
public final class NavGuard {
    public enum State { IN_FIELD, NEAR_EDGE, OFF_FIELD, LOST }
    public enum Dir { NONE, LEFT, RIGHT, AHEAD }

    public static final class View {
        public double left, right, ahead, red;
        public State state = State.LOST; public Dir toward = Dir.NONE;
        public double best() { return Math.max(left, Math.max(right, ahead)); }
        @Override public String toString() { return String.format(java.util.Locale.US, "%s→%s L%.2f R%.2f A%.2f red%.2f", state, toward, left, right, ahead, red); }
    }

    // Shares of flower pixels per sector, calibrated on tablet and S23 screenshots (see SelfTest).
    public static double IN = 0.08, SEE = 0.04, EMPTY = 0.015, RED = 0.04;

    private static double K = 1;   // night brightening for the current frame (see FieldMap.light)
    private static double[] sector(int[] px, int w, int h, double x0, double x1, double y0, double y1) {
        int blue = 0, white = 0, red = 0, grass = 0, n = 0, step = Math.max(3, w / 400);
        for (int y = (int) y0; y < (int) y1; y += step) for (int x = (int) x0; x < (int) x1; x += step) {
            int p = px[y * w + x], r = Math.min(255, (int) (((p >> 16) & 255) * K)), g = Math.min(255, (int) (((p >> 8) & 255) * K)), b = Math.min(255, (int) ((p & 255) * K)); n++;
            if (r > 170 && g < 90 && b < 90) red++;
            else if (b > 150 && r < 110 && g < 150) blue++;
            else if (Math.min(r, Math.min(g, b)) > 190 && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 30) white++;
            else if (g > r + 12 && g > b + 12) grass++;
        }
        // a flower field = blue petals on green grass; white counts only alongside blue (the hive floor is white too)
        double N = Math.max(1, n), flowers = grass / N < 0.08 ? 0 : (blue + Math.min(white, blue)) / N;
        return new double[]{flowers, red / N};
    }

    /** worldX0/worldX1: the part of the frame that shows the game world (tablet frames have grass side bars). */
    public static View view(int[] px, int w, int h, double worldX0, double worldX1) {
        double ww = worldX1 - worldX0;
        K = FieldMap.light(px, w, h, worldX0, worldX1);
        double[] l = sector(px, w, h, worldX0 + .03 * ww, worldX0 + .33 * ww, .42 * h, .76 * h);
        double[] r = sector(px, w, h, worldX0 + .62 * ww, worldX0 + .82 * ww, .42 * h, .76 * h);
        double[] a = sector(px, w, h, worldX0 + .38 * ww, worldX0 + .62 * ww, .30 * h, .44 * h);
        View v = new View();
        v.red = Math.max(l[1], Math.max(r[1], a[1]));
        v.left = l[1] >= RED ? 0 : l[0]; v.right = r[1] >= RED ? 0 : r[0]; v.ahead = a[1] >= RED ? 0 : a[0];
        return classify(v);
    }

    static View classify(View v) {
        double best = v.best();
        v.toward = best == v.left ? Dir.LEFT : best == v.right ? Dir.RIGHT : Dir.AHEAD;
        if (best < SEE) { v.state = State.LOST; v.toward = Dir.NONE; }
        else if (v.left >= IN && v.right >= IN) { v.state = State.IN_FIELD; v.toward = Dir.NONE; }
        else if (best < IN) v.state = State.OFF_FIELD;
        // flowers on one side and none at all on the other: the empty side is the field edge
        else if (Math.min(v.left, v.right) < EMPTY) v.state = State.NEAR_EDGE;
        else { v.state = State.IN_FIELD; v.toward = Dir.NONE; }
        return v;
    }

    // ------------------------------------------------------------------ facing the hive
    private static double gold(int[] px, int w, double x0, double x1, double y0, double y1, java.util.List<int[]> skip) {
        int hit = 0, n = 0;
        for (int y = (int) y0; y < (int) y1; y += 4) for (int x = (int) x0; x < (int) x1; x += 4) {
            if (skip != null && hidden(skip, x, y)) continue;
            int p = px[y * w + x], r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255; n++;
            if (r >= 120 && g >= 85 && r > g && g > b && r - b >= 60) hit++;
        }
        return n < 40 ? 0 : hit / (double) n;   // a side mostly hidden tells nothing
    }
    private static boolean hidden(java.util.List<int[]> skip, int x, int y) { for (int[] r : skip) if (x >= r[0] && x < r[2] && y >= r[1] && y < r[3]) return true; return false; }

    /** Looking straight at the hives, gold walls fill both sides of the view; turned away, at most one side has them. */
    public static double hiveFacing(int[] px, int w, int h, double worldX0, double worldX1) { return hiveFacing(px, w, h, worldX0, worldX1, null); }
    /** Hive gold in BOTH halves of the upper view (the smaller one): which way the hive row is, at any zoom (phone camera reset). */
    public static double goldRow(int[] px, int w, int h, double worldX0, double worldX1, java.util.List<int[]> skip) {
        double ww = worldX1 - worldX0;   // both halves: the row centred ahead, not one hive seen side-on up close
        return Math.min(gold(px, w, worldX0 + .03 * ww, worldX0 + .50 * ww, .12 * h, .50 * h, skip),
                        gold(px, w, worldX0 + .50 * ww, worldX0 + .97 * ww, .12 * h, .50 * h, skip));
    }
    /** skip: screen rects to leave out (the macro's own panel; on a phone it covers a big part of one side). */
    public static double hiveFacing(int[] px, int w, int h, double worldX0, double worldX1, java.util.List<int[]> skip) {
        double ww = worldX1 - worldX0;
        return Math.min(gold(px, w, worldX0 + .03 * ww, worldX0 + .25 * ww, .20 * h, .44 * h, skip),
                        gold(px, w, worldX0 + .62 * ww, worldX0 + .81 * ww, .20 * h, .44 * h, skip));
    }

    // ------------------------------------------------------------------ motion
    public static final int GW = 32, GH = 12;

    /** Coarse brightness grid of the world around the character. */
    public static int[] grid(int[] px, int w, int h, double worldX0, double worldX1) {
        double ww = worldX1 - worldX0, y0 = .30 * h, y1 = .76 * h, xa = worldX0 + .03 * ww, xb = worldX0 + .82 * ww;
        int[] g = new int[GW * GH];
        for (int j = 0; j < GH; j++) for (int i = 0; i < GW; i++) {
            long s = 0; int n = 0;
            int ax = (int) (xa + (xb - xa) * i / GW), bx = (int) (xa + (xb - xa) * (i + 1) / GW), ay = (int) (y0 + (y1 - y0) * j / GH), by = (int) (y0 + (y1 - y0) * (j + 1) / GH);
            for (int y = ay; y < by; y += 4) for (int x = ax; x < bx; x += 4) { int p = px[y * w + x]; s += ((p >> 16) & 255) + ((p >> 8) & 255) + (p & 255); n++; }
            g[j * GW + i] = (int) (s / Math.max(1, 3 * n));
        }
        return g;
    }

    /** Median change per cell between two grids: 0 = same picture. Moving things that cover few cells don't count. */
    public static double motion(int[] a, int[] b) {
        int[] d = new int[a.length];
        for (int i = 0; i < a.length; i++) d[i] = Math.abs(a[i] - b[i]);
        java.util.Arrays.sort(d);
        return d[d.length / 2];
    }

    /** Baseline for "is pollen still rising": it follows the reading both ways, because a buff that raises the bag's
     *  capacity lowers the percentage without losing pollen. A rise is any reading above the returned-from baseline. */
    public static double bagBase(double base, double now) { return Math.abs(now - base) > 0.2 ? now : base; }
    public static boolean bagRose(double base, double now) { return now > base + 0.2; }

    /** Pollen only empties at the hive: an empty bag anywhere else means the character died or was reset. */
    public static boolean bagEmptied(double before, double now) { return before >= 3 && now < 1; }   // the % also falls when a buff raises capacity
}
