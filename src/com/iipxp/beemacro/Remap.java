package com.iipxp.beemacro;

/**
 * Makes a frame from another screen shape look like the S23 frame (2340×1080) every reader was measured and tested
 * on. Each HUD piece is copied from where Roblox draws it on that device to where it sits on the S23, at the measured
 * scale; the 3D world is scaled evenly (aspect kept) and centred, side bars filled with grass so they read as ground.
 * Measured on the Galaxy Tab S9 FE (2304×1440, touch layout) on 2026-11-17 from live screenshots
 * (test/fixtures/tab_*.png). Pure Java, so the tests run on desktop.
 */
public final class Remap {
    public static final int W = 2340, H = 1080;

    /** A device whose layout differs from the S23: 16:10-ish landscape (the S23 is 2.17:1). */
    /** The world window's width / height: the Tab S9 FE's screen (2304 x 1440), where every world reader was measured. */
    public static final double WORLD_ASPECT = 1.6;

    public static boolean tablet(int w, int h) { return w > h && w / (double) h < 1.9; }

    // ---- other screen sizes. Every tablet position below was measured on the Tab S9 FE (2304x1440). Roblox keeps
    // each HUD piece at a fixed spot from its anchor (left edge, screen centre, top, or middle for dialogs), scaled by
    // the device's UI scale u (1.0 on the S9 FE). u is measured live from the honey counter (calibrate) or set by hand.
    /** Measured or chosen UI scale; 0 = not known yet (screen height / 1440 assumed). */
    public static volatile double uiCal = 0;
    public static double u(int h) { return uiCal > 0 ? uiCal : h / 1440.0; }
    /** Centre-anchored x, middle-anchored y (S9 FE coordinates to this screen). */
    static double cx(double x, int w, double u) { return w / 2.0 + (x - 1152) * u; }
    static double my(double y, int h, double u) { return h / 2.0 + (y - 720) * u; }
    static int at(int[] px, int w, int h, double x, double y) {
        int xi = Math.max(0, Math.min(w - 1, (int) x)), yi = Math.max(0, Math.min(h - 1, (int) y));
        return px[yi * w + xi];
    }

    /** UI scale from the honey counter in a full game frame, or 0 when it is not readable: find a scale at which the
     *  counter reads, then take the exact scale from the text's left edge and require the same number there. Pure Java.
     *  Large scales first: a too-small window starts mid-number and its tail ("513,459,658") also reads as a number
     *  (live failure 2026-11-21: 415,513,459,658 measured the S9 FE as 91.6% and blinded the macro). */
    public static double calibrate(int[] px, int w, int h) {
        for (double u = 2.2; u >= 0.45; u -= 0.03) {
            long v = HoneyOcr.read(px, w, h, u);
            if (v <= 0) continue;
            int[] first = HoneyOcr.boxes(px, w, h, u).get(0);
            // the text's left edge sits a fixed distance left of centre: 1 px there is 0.25% of scale (digit height: 5%)
            double uc = (w / 2.0 - first[0]) / (1152 - HoneyOcr.REF_LEFT);
            double byHeight = HoneyOcr.digitHeight(px, w, h, u) / HoneyOcr.REF_DIGIT_H;
            if (Math.abs(uc - byHeight) > 0.10 * uc || HoneyOcr.read(px, w, h, uc) != v) continue;
            // nothing yellow just left of the first digit (else this is the tail of a longer number)
            boolean more = false;
            for (int x = Math.max(0, first[0] - (int) (20 * uc)); x < first[0] - 1 && !more; x++)
                for (int y = first[2]; y <= first[3]; y++) if (HoneyOcr.fill(px[y * w + x])) { more = true; break; }
            if (!more) return uc;
        }
        // ponytail: digits too small to read (8-inch tablets at UI 0.56, 16:9 phones at 0.5) leave the screen-height
        // guess in place; the pills' span (1,040 px on the S9 FE) was tried and read 4-18% off when scenery touches them
        return 0;
    }

    // ---- phones other than the S23. Roblox keeps each HUD piece at a fixed place from its anchor, scaled by the UI
    // scale, but phones differ in resolution, UI scale and the camera-cutout inset, so S23 positions miss on them
    // (user reports: "Bee Swarm HUD not recognized"). The Honey and Pollen pills - two white boxes meeting at the
    // screen centre, 8.6x as wide as tall - and Roblox's menu button give the layout on any phone.
    /** A phone's HUD relative to the S23: scale k, centre line cx, pill top, left inset (S23: 1, 1164, 66, 0). */
    public static final class Phone {
        public final double k, cx, top, inset;
        public Phone(double k, double cx, double top, double inset) { this.k = k; this.cx = cx; this.top = top; this.inset = inset; }
        /** Same layout as the S23 frame the readers were measured on (then the frame is used as it is). */
        public boolean s23(int w, int h) { return w == W && h == H && Math.abs(k - 1) < 0.02 && Math.abs(cx - S23_CX) < 6 && Math.abs(top - S23_TOP) < 5 && Math.abs(inset) < 8; }
        double cxX(double x) { return cx + (x - S23_CX) * k; }        // centre/top anchored (pills, bar, prompt)
        double topY(double y) { return top + (y - S23_TOP) * k; }
        double leftX(double x) { return inset + x * k; }              // left anchored (menu button, buff row, shift lock)
        @Override public String toString() { return String.format(java.util.Locale.US, "scale %.0f%%, centre %.0f, top %.0f, inset %.0f", k * 100, cx, top, inset); }
    }
    static final double S23_SPAN = 1112, S23_CX = 1164, S23_TOP = 66, S23_PILL_W = 550, S23_MENU_X0 = 182, S23_MENU_CY = 102, S23_MENU_W = 62;
    /** This phone's measured layout (null until measured; see Monitor). */
    public static volatile Phone phoneCal;

    /** Bee Swarm's counters are on screen somewhere (any layout): the pills, or on tablets a readable honey counter. */
    public static boolean hudVisible(int[] px, int w, int h) { return locatePhone(px, w, h) != null || (tablet(w, h) && calibrate(px, w, h) > 0); }

    /** Find the Honey/Pollen pills (and the menu button) in a game frame. null when they aren't both there. */
    public static Phone locatePhone(int[] px, int w, int h) {
        int s = Math.max(2, h / 540), gw = w / s, gh = (int) (h * 0.3) / s;   // coarse grid (never finer than 2 px: white bridges glue the pills to bright scenery) finds the pieces; edges refined below
        boolean[] m = new boolean[gw * gh];
        for (int y = 0; y < gh; y++) for (int x = 0; x < gw; x++) {
            int p = px[(y * s) * w + x * s], r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            int mn = Math.min(r, Math.min(g, b)), mx = Math.max(r, Math.max(g, b));
            m[y * gw + x] = mn > 185 && mx - mn < 45;
        }
        java.util.List<int[]> comps = new java.util.ArrayList<>();   // {x0, x1, y0, y1, cells} in screen px
        int[] q = new int[gw * gh]; boolean[] seen = new boolean[gw * gh]; int[] rowN = new int[gh];
        for (int i = 0; i < m.length; i++) {
            if (!m[i] || seen[i]) continue;
            int qa = 0, qb = 0, x0 = gw, x1 = 0, y0 = gh, y1 = 0, n = 0;
            q[qb++] = i; seen[i] = true;
            while (qa < qb) {
                int c = q[qa++], cx = c % gw, cy = c / gw; n++; rowN[cy]++;
                x0 = Math.min(x0, cx); x1 = Math.max(x1, cx); y0 = Math.min(y0, cy); y1 = Math.max(y1, cy);
                if (cx > 0 && m[c - 1] && !seen[c - 1]) { seen[c - 1] = true; q[qb++] = c - 1; }
                if (cx < gw - 1 && m[c + 1] && !seen[c + 1]) { seen[c + 1] = true; q[qb++] = c + 1; }
                if (cy > 0 && m[c - gw] && !seen[c - gw]) { seen[c - gw] = true; q[qb++] = c - gw; }
                if (cy < gh - 1 && m[c + gw] && !seen[c + gw]) { seen[c + gw] = true; q[qb++] = c + gw; }
            }
            // height from the rows that are mostly filled: white scenery touching a pill's edge made it look too tall
            // (tablets, Pine Tree: 5.2:1 instead of 8.6:1), while its few cells don't fill those rows
            int top = -1, bot = -1, nFull = 0; boolean[] full = new boolean[y1 - y0 + 1];
            for (int y = y0; y <= y1; y++) { if (rowN[y] >= 0.6 * (x1 - x0 + 1)) { if (top < 0) top = y; bot = y; full[y - y0] = true; nFull++; } rowN[y] = 0; }
            if (n < 20) continue;
            if (top < 0) { comps.add(new int[]{x0 * s, (x1 + 1) * s, y0 * s, (y1 + 1) * s, n}); continue; }
            // a pill is white in most of its mostly-white rows (its margins and label; the number box and text are dark);
            // scenery glued to its sides isn't: keep runs of columns white in at least half of those rows
            int[] colN = new int[x1 - x0 + 1], colAll = new int[x1 - x0 + 1];
            for (int k = 0; k < qb; k++) {
                int c = q[k], cy = c / gw; if (cy < top || cy > bot) continue;
                colAll[c % gw - x0]++; if (full[cy - y0]) colN[c % gw - x0]++;
            }
            int rows = bot - top + 1, needN = Math.max(1, (nFull + 1) / 2);
            for (int x = 0; x <= x1 - x0; ) {
                if (colN[x] < needN) { x++; continue; }
                int e = x; while (e + 1 <= x1 - x0 && colN[e + 1] >= needN) e++;
                // the rounded ends reach past the margins by at most half the pill's height; anything further is scenery
                int r = (rows + 1) / 2, xs = x, ee = e;
                while (xs - 1 >= 0 && x - (xs - 1) <= r && colAll[xs - 1] > 0) xs--;
                while (e + 1 <= x1 - x0 && e + 1 - ee <= r && colAll[e + 1] > 0) e++;
                int xe0 = x; x = xs;
                int[] cmp = {(x0 + x) * s, (x0 + e + 1) * s, top * s, (bot + 1) * s, (e - x + 1) * (bot - top + 1)};
                double asp = (cmp[1] - cmp[0]) / (double) (cmp[3] - cmp[2]);
                if (asp > 13 && asp < 24) {   // both pills as one run (they touch on foldables at a 3 px grid): split where they meet
                    int best = -1; double bv = 1e9;
                    for (int xx = x; xx <= e; xx++) { double v = colN[xx] + Math.abs((x0 + xx) * s - (cmp[0] + cmp[1]) / 2.0) / (8.0 * s); if (v < bv) { bv = v; best = xx; } }
                    int c2 = (x0 + best) * s;
                    comps.add(new int[]{cmp[0], c2, cmp[2], cmp[3], cmp[4] / 2}); comps.add(new int[]{c2, cmp[1], cmp[2], cmp[3], cmp[4] / 2});
                } else if (cmp[4] >= 20) comps.add(cmp);
                x = Math.max(xe0, e) + 1;
            }
        }
        // a bright effect crossing a pill (a light beam, live on the S23 2026-11-22) cuts its outline in two: join pieces
        // on the same rows whose union has a pill's shape again
        for (boolean joined = true; joined; ) {
            joined = false;
            for (int i = 0; i < comps.size() && !joined; i++) for (int j = 0; j < comps.size() && !joined; j++) {
                int[] a = comps.get(i), b = comps.get(j);
                if (i == j || a[0] >= b[0] || b[0] - a[1] > 0.06 * w || b[0] - a[1] < 0 || a[2] > 0.15 * h) continue;
                if (Math.abs(a[2] - b[2]) > s + 1 || Math.abs(a[3] - b[3]) > s + 1) continue;
                double uw = b[1] - a[0], uh = Math.max(a[3], b[3]) - Math.min(a[2], b[2]);
                if (uw / uh < 5.5 || uw / uh > 11 || (a[1] - a[0]) / (double) (a[3] - a[2]) >= 5.5 && (b[1] - b[0]) / (double) (b[3] - b[2]) >= 5.5) continue;   // not two whole pills
                comps.set(i, new int[]{a[0], b[1], Math.min(a[2], b[2]), Math.max(a[3], b[3]), a[4] + b[4]}); comps.remove(j); joined = true;
            }
        }
        int[] bl = null, br = null; double best = 0;
        for (int[] a : comps) for (int[] b : comps) {
            if (a == b || a[0] >= b[0]) continue;
            double wa = a[1] - a[0], wb = b[1] - b[0], ha = a[3] - a[2], hb = b[3] - b[2];
            if (wa < 0.08 * w || Math.abs(wa - wb) > 0.12 * Math.max(wa, wb)) continue;            // two equal pills
            if (ha < 0.04 * h) continue;                                                             // 6% of the height on the S23; UI scale never goes near 0.65
            if (wa / ha < 5.5 || wa / ha > 11 || wb / hb < 5.5 || wb / hb > 11) continue;           // 8.6:1 measured
            if (Math.abs(a[2] - b[2]) > 0.03 * h || b[0] - a[1] > 0.03 * w || b[0] - a[1] < -0.01 * w) continue;   // side by side, meeting
            double mid = (a[1] + b[0]) / 2.0;
            if (Math.abs(mid - w / 2.0) > 0.2 * w || a[2] > 0.2 * h) continue;                       // at the top, near the centre
            double score = wa + wb;
            if (score > best) { best = score; bl = a; br = b; }
        }
        if (bl == null) return null;
        // exact edges at full resolution (a 2 px grid left the buff row 2-3 px off: Haste in slot 24 was missed)
        int ry0 = bl[2] + (bl[3] - bl[2]) / 4, ry1 = bl[3] - (bl[3] - bl[2]) / 4;
        double lx0 = edge(px, w, h, bl[0], ry0, ry1, +1, s), lx1 = edge(px, w, h, bl[1] - 1, ry0, ry1, -1, s);
        double rx0 = edge(px, w, h, br[0], ry0, ry1, +1, s), rx1 = edge(px, w, h, br[1] - 1, ry0, ry1, -1, s);
        // scale from the two pills' outer span (1,112 px on the S23): a few px of edge error is ~0.3%, not the 1-2% of
        // one pill's width - which mattered: 1% moves buff slot 24 (Haste) by 15 px
        double k = (rx1 - lx0) / S23_SPAN, cx = (lx1 + rx0) / 2.0, top = topEdge(px, w, h, (int) ((lx0 + lx1) / 2), bl[2], s);
        // bright scenery touching one pill makes it look wider (never narrower): if the two disagree, trust the narrower
        double kl = (lx1 - lx0) / S23_PILL_W, kr = (rx1 - rx0) / S23_PILL_W;
        if (Math.abs(kl - kr) > 0.02 * Math.min(kl, kr)) k = Math.min(kl, kr);
        // Left inset (camera cutout) from Roblox's top-left buttons: the white menu "≡" (62 px on the S23, x 182) and the
        // next white icon 300 px to its right. The pair pins it; a single lookalike blob doesn't (live test: 30-80 px off).
        java.util.List<int[]> btn = new java.util.ArrayList<>();
        for (int[] c : comps) {
            double cw = c[1] - c[0], ch = c[3] - c[2], cy = (c[2] + c[3]) / 2.0;
            if (Math.abs(cy - (top + (S23_MENU_CY - S23_TOP) * k)) < 22 * k && c[1] < cx - 400 * k && cw > 20 * k && cw < 95 * k && ch > 20 * k && ch < 95 * k) btn.add(c);
        }
        double inset = Double.NaN;
        for (int[] a : btn) for (int[] b : btn) {
            if (a == b || Math.abs((b[0] - a[0]) - 300 * k) > 0.06 * 300 * k) continue;
            if (Math.abs((a[1] - a[0]) - S23_MENU_W * k) > 0.3 * S23_MENU_W * k) continue;   // the left one is the menu button
            double in = edge(px, w, h, a[0], a[2], a[3], +1, s) - S23_MENU_X0 * k;
            if (Double.isNaN(inset) || Math.abs(in) < Math.abs(inset)) inset = in;
        }
        if (Double.isNaN(inset))   // no pair: the menu-sized button nearest the S23 place
            for (int[] c : btn) {
                if (Math.abs((c[1] - c[0]) - S23_MENU_W * k) > 0.3 * S23_MENU_W * k) continue;
                double in = edge(px, w, h, c[0], c[2], c[3], +1, s) - S23_MENU_X0 * k;
                if (Double.isNaN(inset) || Math.abs(in) < Math.abs(inset)) inset = in;
            }
        return new Phone(k, cx, top, Double.isNaN(inset) ? 0 : inset);
    }

    static boolean white(int p) { int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255, mn = Math.min(r, Math.min(g, b)); return mn > 185 && Math.max(r, Math.max(g, b)) - mn < 45; }
    /** The exact x of a white edge near x (within one coarse cell): dir +1 = left edge (first white column going right),
     *  -1 = right edge. A column counts when a quarter of rows y0..y1 are white there. */
    static double edge(int[] px, int w, int h, int x, int y0, int y1, int dir, int s) {
        int need = 1;   // outermost white column: the pills' rounded ends have few white rows (a quarter-rows rule measured 4% short)
        for (int i = -s - 1; i <= s + 1; i++) {
            int xx = x + dir * i; if (xx < 0 || xx >= w) continue;
            int n = 0; for (int y = Math.max(0, y0); y <= Math.min(h - 1, y1); y++) if (white(px[y * w + xx])) n++;
            if (n >= need) return dir > 0 ? xx : xx + 1;
        }
        return dir > 0 ? x : x + 1;
    }
    /** The exact top of the pill at column x (first white row near the coarse top). */
    static double topEdge(int[] px, int w, int h, int x, int y, int s) {
        for (int yy = Math.max(0, y - s - 1); yy <= y + s + 1 && yy < h; yy++) if (white(px[yy * w + x])) return yy;
        return y;
    }

    public static final class Out {
        public boolean phone; public double placeX0;   // phone remap: where the world was placed in the S23 frame
        public int[] px; public boolean tablet; public double worldScale, worldX0, ui = 1;
        public boolean menuOpen, respawnDialog;   // tablet-only screens (the S23 menu check doesn't apply)
        public int w, h;                           // size of px
    }

    /** Linear map of a tablet rectangle (a) onto an S23 rectangle (b): b = b0 + (a - a0) * k, per axis. */
    static void blit(int[] src, int sw, int sh, double ax0, double ay0, double ax1, double ay1,
                     int[] dst, double bx0, double by0, double bx1, double by1) {
        double kx = (ax1 - ax0) / (bx1 - bx0), ky = (ay1 - ay0) / (by1 - by0);
        for (int y = (int) Math.ceil(by0); y < by1 && y < H; y++) for (int x = (int) Math.ceil(bx0); x < bx1 && x < W; x++) {
            if (x < 0 || y < 0) continue;
            int sx = (int) (ax0 + (x - bx0) * kx), sy = (int) (ay0 + (y - by0) * ky);
            if (sx < 0 || sy < 0 || sx >= sw || sy >= sh) continue;
            dst[y * W + x] = src[sy * sw + sx];
        }
    }

    public static Out apply(int[] px, int w, int h) {
        Out o = new Out();
        o.w = w; o.h = h;
        // Phone-shaped frames keep their pixels, but the world readers look through the same window the tablet has:
        // Roblox keeps the vertical field of view, so a 1.6 x height window centred on the screen shows the same angles
        // on any shape (the S23's extra width is extra scenery at the sides, where the readers would look wrongly).
        if (!tablet(w, h)) {
            Phone ph = phoneCal;
            if (ph == null || ph.s23(w, h)) { o.px = px; o.worldScale = 1; o.worldX0 = Math.max(0, (w - WORLD_ASPECT * h) / 2); return o; }
            return phone(px, w, h, ph);
        }
        o.w = W; o.h = H;
        o.tablet = true;
        int[] d = new int[W * H];
        // 1. world: even scale to the S23 height, centred; sides = grass (ground model treats it as background)
        double s = H / (double) h, x0 = (W - w * s) / 2;
        o.worldScale = s; o.worldX0 = x0;
        java.util.Arrays.fill(d, 0xFF3CA03C);
        blit(px, w, h, 0, 0, w, h, d, x0, 0, x0 + w * s, H);
        double u = u(h); o.ui = u;
        // 2. buff row, left/top (before the prompt: the prompt is drawn on top of it on both devices): 66.5 px tiles from x=0, y 101.5..168 -> 60 px tiles from x=100, y 178..238
        for (int i = 0; i < 30; i++) {
            double ax = 66.5 * i * u, bx = 100 + 60 * i;
            if (ax + 66.5 * u > w || bx + 60 > W) break;
            blit(px, w, h, ax, 101.5 * u, ax + 66.5 * u, 168 * u, d, bx, 178, bx + 60, 238);
        }
        // 3. Honey/Pollen bar, centre/top: pollen bar x 1255..1640 rows 47/89 (tablet) -> 1280..1694 rows 76/119 (S23)
        double kx = (1694 - 1280) / (1640.0 - 1255), ky = (119 - 76) / (89.0 - 47);
        blit(px, w, h, cx(560, w, u), 20 * u, cx(1720, w, u), 116 * u, d, 1280 + (560 - 1255) * kx, 76 + (20 - 47) * ky, 1280 + (1720 - 1255) * kx, 76 + (116 - 47) * ky);
        // 4. hive prompt, centre/top: Tap box left 846 / blue box right 1458, y 112..210 -> 848 / 1490, 191..290
        double px0 = 846, px1 = 1458, qx0 = 848, qx1 = 1490, k = (qx1 - qx0) / (px1 - px0), kp = (290 - 191) / (210.0 - 112);
        blit(px, w, h, cx(780, w, u), 96 * u, cx(1520, w, u), 226 * u, d, qx0 + (780 - px0) * k, 191 + (96 - 112) * kp, qx0 + (1520 - px0) * k, 191 + (226 - 112) * kp);
        // 5. shift-lock button, left/middle (ponytail: anchor guessed from one device; check it on a second one): ring centre (52,888), scaled 0.85 around (147,700)
        blit(px, w, h, (52 - 50) * u, my(888 - 50, h, u), (52 + 50) * u, my(888 + 50, h, u), d, 147 - 42.5, 700 - 42.5, 147 + 42.5, 700 + 42.5);
        // tablet-only screens
        o.menuOpen = menuOpen(px, w, h);
        o.respawnDialog = respawnDialog(px, w, h);
        o.px = d;
        return o;
    }

    /** A phone with another layout, rebuilt into the S23 frame: the world scaled evenly to its height and centred,
     *  then each HUD piece copied from where this phone draws it (anchor + scale) to where the S23 has it. */
    public static Out phone(int[] px, int w, int h, Phone ph) {
        Out o = new Out(); o.w = W; o.h = H; o.phone = true;
        int[] d = new int[W * H];
        double s = H / (double) h, x0 = (W - w * s) / 2;
        o.worldScale = s; o.placeX0 = x0; o.worldX0 = Math.max(0, (W - WORLD_ASPECT * H) / 2);
        java.util.Arrays.fill(d, 0xFF3CA03C);
        blit(px, w, h, 0, 0, w, h, d, x0, 0, x0 + w * s, H);
        // buff row (left/top) first: the hive prompt is drawn over it
        blit(px, w, h, ph.leftX(0), ph.topY(160), ph.leftX(1900), ph.topY(250), d, 0, 160, 1900, 250);
        // Roblox's top-left buttons (menu)
        blit(px, w, h, ph.leftX(0), ph.topY(0), ph.leftX(560), ph.topY(160), d, 0, 0, 560, 160);
        // Honey/Pollen pills and bar (centre/top)
        blit(px, w, h, ph.cxX(440), ph.topY(0), ph.cxX(1900), ph.topY(160), d, 440, 0, 1900, 160);
        // hive prompt (centre/top): it sits over the middle of the buff row, so it is copied only when one is showing -
        // otherwise those buff icons (left-anchored, e.g. Haste in slot 24) would be overwritten at the wrong scale
        int[] withPrompt = d.clone();
        blit(px, w, h, ph.cxX(760), ph.topY(160), ph.cxX(1560), ph.topY(300), withPrompt, 760, 160, 1560, 300);
        Vision.Result pr = new Vision.Result();
        Vision.prompt(withPrompt, W, H, pr);
        if (pr.prompt != Vision.Prompt.NONE) d = withPrompt;
        // shift-lock button (left, middle of the screen)
        blit(px, w, h, ph.leftX(40), h / 2.0 + (590 - 540) * ph.k, ph.leftX(260), h / 2.0 + (810 - 540) * ph.k, d, 40, 590, 260, 810);
        o.px = d;
        return o;
    }

    /** Tablet Roblox menu = dark dropdown panel x 40..360, y ~120..890 (measured ≈ (19..31,18..29,20..22)). */
    static boolean menuOpen(int[] px, int w, int h) { return keyboardMenu(px, w, h) || touchMenu(px, w, h); }

    /** With a keyboard, Esc opens the large centered menu instead: its blue "Resume" button is at x 1400..1845, y 1240..1312. */
    static boolean keyboardMenu(int[] px, int w, int h) {
        int ok = 0, n = 0;
        for (int y = 1240; y <= 1312; y += 6) for (int x = 1400; x <= 1845; x += 10) {
            n++;
            if (Vision.isMenuBlue(at(px, w, h, cx(x, w, u(h)), my(y, h, u(h))))) ok++;   // centred dialog
        }
        return ok > 0.6 * n;
    }

    static boolean touchMenu(int[] px, int w, int h) {
        int ok = 0, n = 0;
        for (int y : new int[]{200, 300, 500, 700, 800}) for (int x = 60; x <= 340; x += 20) {
            int p = at(px, w, h, x * u(h), y * u(h)); n++;   // dropdown hangs from the top-left corner
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            if (Math.max(r, Math.max(g, b)) < 60 && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 18) ok++;
        }
        if (ok <= 0.7 * n) return false;   // menu text/icons are white, so not every sample is dark
        // and the menu button shows ✕, not ☰: the ☰ lines are white at (165,48) and (165,72), the ✕ is dark there
        // (a dark game scene also passes the panel test above; measured live)
        for (int y : new int[]{48, 72}) { int p = at(px, w, h, 165 * u(h), y * u(h)); if (((p >> 16) & 255) > 150 && ((p >> 8) & 255) > 150 && (p & 255) > 150) return false; }
        return true;
    }

    /** Tablet respawn confirmation: navy "Respawn" button x 800..1120, y 615..685 (measured (24..36,41..54,102..123)). */
    static boolean respawnDialog(int[] px, int w, int h) {
        int ok = 0, n = 0;
        for (int y = 618; y <= 684; y += 6) for (int x = 800; x <= 1120; x += 10) {
            int p = at(px, w, h, cx(x, w, u(h)), my(y, h, u(h))); n++;   // centred dialog
            if (Vision.isMenuBlue(p)) ok++;
        }
        return ok > 0.6 * n;
    }

    /** Box from remapped (S23) coordinates back to the real screen, for drawing. World boxes use the even world
     *  scale; the buff row uses its tile mapping. */
    public static int[] toScreen(Out o, int[] r, boolean buffRow) {
        if (o != null && o.phone) {
            Phone ph = phoneCal; if (ph == null) return r;
            if (buffRow) return new int[]{(int) ph.leftX(r[0]), (int) ph.topY(r[1]), (int) ph.leftX(r[2]), (int) ph.topY(r[3])};
            return new int[]{(int) ((r[0] - o.placeX0) / o.worldScale), (int) (r[1] / o.worldScale), (int) ((r[2] - o.placeX0) / o.worldScale), (int) (r[3] / o.worldScale)};
        }
        if (o == null || !o.tablet) return r;
        if (buffRow) {
            double k = 66.5 * o.ui / 60, y0 = 101.5 * o.ui;
            return new int[]{(int) ((r[0] - 100) * k), (int) (y0 + (r[1] - 178) * k), (int) ((r[2] - 100) * k), (int) (y0 + (r[3] - 178) * k)};
        }
        return new int[]{(int) ((r[0] - o.worldX0) / o.worldScale), (int) (r[1] / o.worldScale), (int) ((r[2] - o.worldX0) / o.worldScale), (int) (r[3] / o.worldScale)};
    }
    /** Screen rect (our own drawn boxes/panel) into remapped coordinates, for the detector's ignore list. */
    public static int[] fromScreen(Out o, int[] r) {
        if (o != null && o.phone) return new int[]{(int) (r[0] * o.worldScale + o.placeX0), (int) (r[1] * o.worldScale), (int) (r[2] * o.worldScale + o.placeX0), (int) (r[3] * o.worldScale)};
        if (o == null || !o.tablet) return r;
        return new int[]{(int) (r[0] * o.worldScale + o.worldX0), (int) (r[1] * o.worldScale), (int) (r[2] * o.worldScale + o.worldX0), (int) (r[3] * o.worldScale)};
    }

    /** Where to tap (fractions of the real screen) on each layout. */
    public static final class Taps { public float menuX, menuY, respawnX, respawnY, peopleX, peopleY, confirmX, confirmY, promptX, promptY; }
    public static Taps taps(int w, int h) {
        Taps t = new Taps();
        if (tablet(w, h)) {   // measured 2304x1440, placed by anchor and UI scale on other screens
            double u = u(h);
            t.menuX = (float) (165 * u / w); t.menuY = (float) (60 * u / h);
            t.respawnX = (float) (190 * u / w); t.respawnY = (float) (848 * u / h);        // "Respawn" in the dropdown (no People tab)
            t.peopleX = -1; t.peopleY = -1;
            t.confirmX = (float) (cx(960, w, u) / w); t.confirmY = (float) (my(650, h, u) / h);
            t.promptX = (float) (cx(1150, w, u) / w); t.promptY = (float) (160 * u / h);
        } else if (phoneCal != null && !phoneCal.s23(w, h)) {   // another phone: the S23 spots moved by its own layout
            Phone ph = phoneCal;
            t.menuX = (float) (ph.leftX(213) / w); t.menuY = (float) (ph.topY(100) / h);
            t.promptX = (float) (ph.cxX(1160) / w); t.promptY = (float) (ph.topY(240) / h);
            // menu dialog buttons: centred on the screen
            t.respawnX = (float) ((w / 2.0 + (1168 - 1170) * ph.k) / w); t.respawnY = (float) ((h / 2.0 + (450 - 540) * ph.k) / h);
            t.peopleX = (float) ((w / 2.0 + (427 - 1170) * ph.k) / w); t.peopleY = (float) ((h / 2.0 + (262 - 540) * ph.k) / h);
            t.confirmX = (float) ((w / 2.0 + (840 - 1170) * ph.k) / w); t.confirmY = (float) ((h / 2.0 + (762 - 540) * ph.k) / h);
        } else {
            t.menuX = Vision.MENU_X; t.menuY = Vision.MENU_Y; t.respawnX = Vision.RESPAWN_X; t.respawnY = Vision.RESPAWN_Y;
            t.peopleX = Vision.PEOPLE_X; t.peopleY = Vision.PEOPLE_Y; t.confirmX = Vision.CONFIRM_X; t.confirmY = Vision.CONFIRM_Y;
            t.promptX = Vision.PROMPT_X; t.promptY = Vision.PROMPT_Y;
        }
        return t;
    }
}
