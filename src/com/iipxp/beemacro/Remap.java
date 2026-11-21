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
        return 0;
    }

    public static final class Out {
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
        if (!tablet(w, h)) { o.px = px; o.worldScale = 1; o.worldX0 = Math.max(0, (w - WORLD_ASPECT * h) / 2); return o; }
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
        if (o == null || !o.tablet) return r;
        if (buffRow) {
            double k = 66.5 * o.ui / 60, y0 = 101.5 * o.ui;
            return new int[]{(int) ((r[0] - 100) * k), (int) (y0 + (r[1] - 178) * k), (int) ((r[2] - 100) * k), (int) (y0 + (r[3] - 178) * k)};
        }
        return new int[]{(int) ((r[0] - o.worldX0) / o.worldScale), (int) (r[1] / o.worldScale), (int) ((r[2] - o.worldX0) / o.worldScale), (int) (r[3] / o.worldScale)};
    }
    /** Screen rect (our own drawn boxes/panel) into remapped coordinates, for the detector's ignore list. */
    public static int[] fromScreen(Out o, int[] r) {
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
        } else {
            t.menuX = Vision.MENU_X; t.menuY = Vision.MENU_Y; t.respawnX = Vision.RESPAWN_X; t.respawnY = Vision.RESPAWN_Y;
            t.peopleX = Vision.PEOPLE_X; t.peopleY = Vision.PEOPLE_Y; t.confirmX = Vision.CONFIRM_X; t.confirmY = Vision.CONFIRM_Y;
            t.promptX = Vision.PROMPT_X; t.promptY = Vision.PROMPT_Y;
        }
        return t;
    }
}
