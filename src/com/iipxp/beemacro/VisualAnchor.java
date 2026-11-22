package com.iipxp.beemacro;

/**
 * Visual loop closure for field drift. A pattern loop should end where it started, with the camera turned the same
 * way. So a picture of the world taken at the start (ground, flowers, hedges, tree trunks, the sprinkler — anything)
 * is matched against the view at the end of every loop: how far the scene has slid on screen is how far the character
 * drifted, and the macro walks back until the two line up again. Unlike Natro's drift check it needs no sprinkler in
 * view (under the Pine Tree's leaves it often isn't).
 *
 * What is ignored, because it doesn't move with the world: the HUD (top bar, buff row, hotbar, message log, our own
 * panel) and the character with its bees (shift lock keeps them on the same spot). Moving bees and particles are
 * thinned out by taking the median of three frames. Brightness changes (day/night, boosts) are absorbed by zero-mean
 * normalised cross-correlation.
 */
public final class VisualAnchor {
    static final int FINE = 8, COARSE = 16;           // downsampling steps (pixels of the screen per cell)
    public final int w, h;                                  // full frame size
    final float[] fine, coarse; final boolean[] okFine, okCoarse;
    final int fw, fh, cw, ch;
    public final double texture;                            // spread of brightness in the usable area: too flat = no anchor

    /** px: the frame (any size), playerX/Y: where the character stands (fractions of the frame). */
    public VisualAnchor(int[] px, int w, int h, double playerX, double playerY) {
        this.w = w; this.h = h;
        fw = w / FINE; fh = h / FINE; cw = w / COARSE; ch = h / COARSE;
        fine = gray(px, w, h, FINE, fw, fh); coarse = gray(px, w, h, COARSE, cw, ch);
        okFine = mask(fw, fh, FINE, w, h, playerX, playerY); okCoarse = mask(cw, ch, COARSE, w, h, playerX, playerY);
        double s = 0, s2 = 0; int n = 0;
        for (int i = 0; i < fine.length; i++) if (okFine[i]) { s += fine[i]; s2 += fine[i] * fine[i]; n++; }
        texture = n == 0 ? 0 : Math.sqrt(Math.max(0, s2 / n - (s / n) * (s / n)));
    }

    /** Usable for anchoring: enough texture to lock on to (a uniform sky or a black screen isn't). */
    public boolean usable() { return texture >= 8; }

    private static float[] gray(int[] px, int w, int h, int k, int gw, int gh) {
        float[] g = new float[gw * gh];
        for (int y = 0; y < gh; y++) for (int x = 0; x < gw; x++) {
            int s = 0, n = 0;
            for (int yy = y * k; yy < y * k + k; yy += 2) for (int xx = x * k; xx < x * k + k; xx += 2) {
                int p = px[yy * w + xx]; s += ((p >> 16) & 255) * 3 + ((p >> 8) & 255) * 6 + (p & 255); n++;   // ≈ luma ×10
            }
            g[y * gw + x] = s / (10f * n);
        }
        return g;
    }

    /** World area only: inside the frame margins, below the top HUD and buff row, above the hotbar, left of the side
     *  HUD and our panel, and not the character with the bees around it. */
    static boolean[] mask(int gw, int gh, int k, int w, int h, double plx, double ply) {
        boolean[] m = new boolean[gw * gh];
        for (int y = 0; y < gh; y++) for (int x = 0; x < gw; x++) {
            double fx = (x * k + k / 2.0) / w, fy = (y * k + k / 2.0) / h;
            boolean world = fx > 0.07 && fx < 0.80 && fy > 0.20 && fy < 0.80;
            boolean player = Math.abs(fx - plx) < 0.085 && fy > ply - 0.26 && fy < ply + 0.07;
            m[y * gw + x] = world && !player;
        }
        return m;
    }

    /** How far the scene in `now` has slid compared with this anchor: {dx, dy in screen pixels (+ = moved right /
     *  down), match score −1..1, score with no slide}. Searches up to `maxFrac` of the frame height. */
    public double[] shiftTo(VisualAnchor now, double maxFrac) {
        int r = Math.max(2, (int) Math.round(maxFrac * h / COARSE));
        double best = -2; int bx = 0, by = 0;
        for (int dy = -r; dy <= r; dy++) for (int dx = -r; dx <= r; dx++) {
            double c = ncc(coarse, okCoarse, now.coarse, now.okCoarse, cw, ch, dx, dy);
            if (c > best) { best = c; bx = dx; by = dy; }
        }
        // refine on the finer grid around the coarse answer
        int fx0 = bx * 2, fy0 = by * 2; double fb = -2; int rx = fx0, ry = fy0;
        for (int dy = fy0 - 2; dy <= fy0 + 2; dy++) for (int dx = fx0 - 2; dx <= fx0 + 2; dx++) {
            double c = ncc(fine, okFine, now.fine, now.okFine, fw, fh, dx, dy);
            if (c > fb) { fb = c; rx = dx; ry = dy; }
        }
        double zero = ncc(fine, okFine, now.fine, now.okFine, fw, fh, 0, 0);
        return new double[]{rx * FINE, ry * FINE, fb, zero};
    }

    /** Zero-mean normalised cross-correlation of a (at x,y) with b (at x+dx, y+dy) over cells usable in both. */
    static double ncc(float[] a, boolean[] ma, float[] b, boolean[] mb, int gw, int gh, int dx, int dy) {
        double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0; int n = 0;
        for (int y = Math.max(0, -dy); y < Math.min(gh, gh - dy); y++) {
            int ra = y * gw, rb = (y + dy) * gw;
            for (int x = Math.max(0, -dx); x < Math.min(gw, gw - dx); x++) {
                int i = ra + x, j = rb + x + dx;
                if (!ma[i] || !mb[j]) continue;
                double u = a[i], v = b[j];
                sa += u; sb += v; saa += u * u; sbb += v * v; sab += u * v; n++;
            }
        }
        if (n < 40) return -1;
        double cov = sab - sa * sb / n, va = saa - sa * sa / n, vb = sbb - sb * sb / n;
        return va <= 1e-6 || vb <= 1e-6 ? -1 : cov / Math.sqrt(va * vb);
    }
}
