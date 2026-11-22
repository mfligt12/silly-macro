package com.iipxp.beemacro;

/**
 * Pixel-rule recognizer for Bee Swarm Simulator's mobile HUD. Pure Java on ARGB ints so it is tested on desktop
 * against real screenshots (test/fixtures). Geometry was measured on a 2340x1080 S23 and stored as fractions.
 * Every result carries a confidence; callers must not act on results below Profile.minConfidence.
 */
public final class Vision {
    /** TAP_BLUE = "Make Honey" (or another blue action), TAP_CLAIM = "Claim Hive", TAP_CANNON = "Fire the Red Cannon",
     *  TAP_RED = "Stop Making Honey", BANNER_BLUE = "To Make Honey, Collect Pollen…" (own hive, empty bag). */
    public enum Prompt { NONE, TAP_BLUE, TAP_CLAIM, TAP_CANNON, TAP_RED, BANNER_BLUE }
    public enum Screen { GAME, MENU, RESPAWN_DIALOG, DARK, UNKNOWN }

    public static final class Result {
        public Screen screen = Screen.UNKNOWN; public double screenConf;
        public double pollen = -1, pollenConf;             // 0..100 backpack %
        public Prompt prompt = Prompt.NONE; public double promptConf;
        public double meanLum;
        public int shiftLock = -1;                        // 1 on, 0 off, -1 unknown
        public boolean menuPeople;                        // menu open on the People tab (Respawn button where we tap)
        public Field field = Field.UNKNOWN;               // flowers under the player
        public boolean haste;                             // Haste buff active (walk speed above base)
        public int hasteStacks;                           // 0 none, 1..10 stacks read from the icon, -1 Haste on but count unreadable
        public int hasteSlot = -1; public double hasteConf;
        public boolean coconutHaste;                      // a 2nd Haste icon on the row = Coconut Haste (same icon, Natro counts them) // buff-row slot of the Haste icon; share of its shape mask that matched
        public int w, h;
        public String summary() {
            return String.format(java.util.Locale.US, "%s(%.0f%%) pollen=%s(%.0f%%) prompt=%s(%.0f%%)",
                    screen, screenConf * 100, pollen < 0 ? "?" : String.format(java.util.Locale.US, "%.1f%%", pollen),
                    pollenConf * 100, prompt, promptConf * 100);
        }
    }

    // Measured geometry (fractions of a 2340x1080 landscape frame)
    static final double BAR_X0 = 1280 / 2340.0, BAR_X1 = 1694 / 2340.0, BAR_Y_LOW = 119 / 1080.0, BAR_Y_HIGH = 76 / 1080.0;
    static final double PILL_R_X = 1708 / 2340.0, PILL_L_X = 615 / 2340.0;
    static final double TAP_X0 = 848 / 2340.0, TAP_X1 = 955 / 2340.0, TAP_Y0 = 192 / 1080.0, TAP_Y1 = 290 / 1080.0;
    static final double BOX_X0 = 985 / 2340.0, BOX_X1 = 1490 / 2340.0, BOX_Y0 = 191 / 1080.0, BOX_Y1 = 288 / 1080.0;
    static final double BANNER_X0 = 850 / 2340.0, BANNER_X1 = 960 / 2340.0;
    // Left end of the menu's blue "Resume" button only: the floating panel docks right of x≈1650 and must not hide it.
    static final double RESUME_X0 = 1500 / 2340.0, RESUME_X1 = 1575 / 2340.0, RESUME_Y0 = 405 / 1080.0, RESUME_Y1 = 495 / 1080.0;
    static final double CONFIRM_X0 = 560 / 2340.0, CONFIRM_X1 = 1120 / 2340.0, CONFIRM_Y0 = 705 / 1080.0, CONFIRM_Y1 = 820 / 1080.0;

    // Tap targets (fractions) measured live
    public static final float MENU_X = 213 / 2340f, MENU_Y = 100 / 1080f;
    public static final float RESPAWN_X = 1168 / 2340f, RESPAWN_Y = 450 / 1080f;
    public static final float PEOPLE_X = 427 / 2340f, PEOPLE_Y = 262 / 1080f;   // menu "People" tab
    public static final float CONFIRM_X = 840 / 2340f, CONFIRM_Y = 762 / 1080f;
    public static final float PROMPT_X = 1160 / 2340f, PROMPT_Y = 240 / 1080f;

    static int r(int p) { return (p >> 16) & 255; }
    static int g(int p) { return (p >> 8) & 255; }
    static int b(int p) { return p & 255; }
    static int lum(int p) { return r(p) + g(p) + b(p); }
    static int sat(int p) { int mx = Math.max(r(p), Math.max(g(p), b(p))), mn = Math.min(r(p), Math.min(g(p), b(p))); return mx - mn; }

    /** Frame from any supported device, already brought to the S23 layout by Remap. */
    public static Result analyze(Remap.Out o) {
        Result r = analyze(o.px, o.w, o.h);
        if (o.tablet) {
            // the 4/5/7 templates are reliable when they match; anything else goes to the general digit reader
            if (r.haste && r.hasteStacks <= 1) {
                double x0 = (100 + 60 * r.hasteSlot) * o.w / 2340.0, y0 = 179 * o.h / 1080.0, sz = 58 * o.w / 2340.0;
                String t = StackOcr.read(o.px, o.w, o.h, x0, y0, sz);   // strict read first: it gets "x4" when the runner art is clear of it
                int n = t != null && t.matches("x[0-9]+") ? Integer.parseInt(t.substring(1)) : 0;
                r.hasteStacks = n >= 1 && n <= 10 ? n : StackOcr.hasteCount(o.px, o.w, o.h, x0, y0, sz);
            }
            if (o.respawnDialog) { r.screen = Screen.RESPAWN_DIALOG; r.screenConf = 0.95; r.menuPeople = false; }
            else if (o.menuOpen) { r.screen = Screen.MENU; r.screenConf = 0.9; r.menuPeople = true; }   // dropdown always lists Respawn
        }
        return r;
    }

    public static Result analyze(int[] px, int w, int h) {
        Result res = new Result(); res.w = w; res.h = h;
        if (w < h) { res.screen = Screen.UNKNOWN; res.screenConf = 0.9; return res; } // portrait: not Roblox gameplay
        long sum = 0; int n = 0;
        for (int y = 0; y < h; y += 16) for (int x = 0; x < w; x += 16) { sum += lum(px[y * w + x]); n++; }
        res.meanLum = sum / (3.0 * n);

        double hud = hudConfidence(px, w, h);
        double menu = frac(px, w, h, RESUME_X0, RESUME_X1, RESUME_Y0, RESUME_Y1, Vision::isMenuBlue);
        double confirm = frac(px, w, h, CONFIRM_X0, CONFIRM_X1, CONFIRM_Y0, CONFIRM_Y1, Vision::isMenuBlue);
        // Menu on ANY tab: the dark panel band across y≈360 (menus 0.93-1.0, gameplay ≤0.13 measured) with the HUD
        // and no blue Disconnected background (that screen is dark too). menuPeople = Resume visible.
        double band = 0; int bn = 0;
        for (int x = (int) (600 / 2340.0 * w); x <= (int) (1500 / 2340.0 * w); x += 4) {
            int p = px[(int) (360 / 1080.0 * h) * w + x]; bn++;
            if (Math.max(r(p), Math.max(g(p), b(p))) < 75 && sat(p) < 28) band++;
        }
        band /= bn;
        res.menuPeople = menu > 0.6 && hud < 0.3;   // menus dim the HUD; a navy game scene can match the Resume colour
        if (confirm > 0.6 && hud < 0.3) { res.screen = Screen.RESPAWN_DIALOG; res.screenConf = confirm; }
        else if (res.menuPeople) { res.screen = Screen.MENU; res.screenConf = menu; }
        else if (res.meanLum < 18) { res.screen = Screen.DARK; res.screenConf = 1 - res.meanLum / 18; }
        // + the HUD must be dimmed (menus measured 0.00, gameplay ~1.0): a dark game scene is not a menu (live false alarm)
        else if (band > 0.85 && hud < 0.3 && !isDisconnectBlue(at(px, w, h, 100 / 2340.0, 500 / 1080.0, 0, 0))) { res.screen = Screen.MENU; res.screenConf = band; }
        else if (hud >= 0.75) { res.screen = Screen.GAME; res.screenConf = hud; }
        else { res.screen = Screen.UNKNOWN; res.screenConf = 1 - hud; }

        if (res.screen == Screen.GAME) { pollen(px, w, h, res); prompt(px, w, h, res); res.shiftLock = shiftLock(px, w, h); res.field = field(px, w, h); double[] hm = hasteMatch(px, w, h); int hs = (int) hm[0]; res.hasteSlot = hs; res.hasteConf = hm[1]; res.haste = hs >= 0; res.coconutHaste = hm[2] >= 2; res.hasteStacks = hs < 0 ? 0 : hasteStacks(px, w, h, hs); }
        return res;
    }

    /** HUD present = the light ends of the "Honey" and "Pollen" pills (no text there) at their measured spots. */
    static double hudConfidence(int[] px, int w, int h) {
        int ok = 0, n = 0;
        for (double fx : new double[]{PILL_R_X, PILL_L_X}) for (int y = 80; y <= 120; y += 4) {
            for (int dx = -3; dx <= 3; dx += 3) {
                int p = at(px, w, h, fx, y / 1080.0, dx, 0);
                // translucent white pill: ~(218,216,206) over dark scenes, up to ~(246,239,213) over the bright hive wall
                n++; if (Math.min(r(p), Math.min(g(p), b(p))) > 185 && sat(p) < 45) ok++;
            }
        }
        return ok / (double) n;
    }

    /** Pollen bar: saturated fill (green -> red as it fills) from the left, translucent gray when empty. */
    static void pollen(int[] px, int w, int h, Result res) {
        int x0 = (int) (BAR_X0 * w), x1 = (int) (BAR_X1 * w);
        int[] rows = {(int) (BAR_Y_LOW * h), (int) (BAR_Y_HIGH * h)};
        int lastFill = x0 - 1, fillCols = 0, emptyCols = 0, conflicts = 0, cols = 0;
        boolean seenEmpty = false;
        for (int x = x0; x <= x1; x += 2) {
            int state = 0; // 1 fill, -1 empty, 0 unknown (text)
            for (int y : rows) {
                int p = px[y * w + x];
                if (sat(p) > 60 && lum(p) < 650) { state = 1; break; }
                if (sat(p) < 30 && lum(p) < 450) state = -1;
            }
            cols++;
            if (state == 1) { fillCols++; if (seenEmpty && x - lastFill > 8) conflicts++; lastFill = x; }
            else if (state == -1) { emptyCols++; if (x > lastFill + 8) seenEmpty = true; }
        }
        double known = (fillCols + emptyCols) / (double) cols;
        res.pollen = Math.max(0, Math.min(100, 100.0 * (lastFill - x0 + 1) / (x1 - x0)));
        res.pollenConf = Math.max(0, known - conflicts * 0.1);
    }

    static void prompt(int[] px, int w, int h, Result res) {
        double tap = frac(px, w, h, TAP_X0, TAP_X1, TAP_Y0, TAP_Y1, p -> r(p) > 220 && g(p) > 220 && b(p) > 220 && sat(p) < 15);
        double blue = edgeFrac(px, w, h, BOX_X0, BOX_X1, Vision::isPromptBlue);
        double red = edgeFrac(px, w, h, BOX_X0, BOX_X1, Vision::isPromptRed);
        double bannerLeft = edgeFrac(px, w, h, BANNER_X0, BANNER_X1, Vision::isPromptBlue);
        if (tap > 0.45 && red > 0.6) { res.prompt = Prompt.TAP_RED; res.promptConf = Math.min(1, (tap + red) / 1.6); }
        else if (tap > 0.45 && blue > 0.6) {
            // Same blue box for "Claim Hive" (someone else's/empty hive) and "Make Honey" (yours). The font is fixed,
            // so the text's left edge separates them: Claim starts at x≈1026, Make Honey at x≈999 (of 2340).
            int x0 = textLeft(px, w, h);
            // text width separates the rest (measured: Make Honey 369 cols, Fire the Red Cannon 329, Claim Hive 297)
            int cols = textCols(px, w, h);
            res.prompt = x0 >= 0 && x0 >= (int) (1013 / 2340.0 * w) ? Prompt.TAP_CLAIM
                    : cols > 0 && cols < (int) (349 / 2340.0 * w) ? Prompt.TAP_CANNON : Prompt.TAP_BLUE;
            res.promptConf = Math.min(1, (tap + blue) / 1.6) * (x0 < 0 ? 0.5 : 1);
        }
        else if (tap < 0.1 && blue > 0.6 && bannerLeft > 0.6) { res.prompt = Prompt.BANNER_BLUE; res.promptConf = Math.min(1, (blue + bannerLeft) / 1.6); }
        else { res.prompt = Prompt.NONE; res.promptConf = 1 - Math.max(tap, Math.max(blue, red)); }
    }

    public enum Field { UNKNOWN, PINE_TREE, OTHER_FIELD }

    /** Which flowers are under the player, from the ground band beside the character. Measured live: Pine Tree
     *  Forest = blue (0.17-0.23) + white, red 0.000-0.002; the neighbouring red/blue field = red ~0.09-0.11;
     *  hive area = no flowers. */
    public static Field field(int[] px, int w, int h) {
        int red = 0, blue = 0, n = 0, step = Math.max(4, w / 390);
        for (int y = (int) (480 / 1080.0 * h); y < (int) (860 / 1080.0 * h); y += step)
            for (int x = 0; x < (int) (1600 / 2340.0 * w); x += step) {
                if (x > 850 / 2340.0 * w && x < 1450 / 2340.0 * w) continue;      // skip the character
                int p = px[y * w + x]; n++;
                if (r(p) > 170 && g(p) < 90 && b(p) < 90) red++;
                else if (b(p) > 150 && r(p) < 110 && g(p) < 150) blue++;
            }
        double fr = red / (double) n, fb = blue / (double) n;
        if (fr >= 0.04) return Field.OTHER_FIELD;
        // blue ≥ 0.07: the tablet route zooms the camera out (O O), measured 0.095-0.13 there vs 0.17-0.23 on the S23
        if (fb >= 0.07 && fr < 0.02) return Field.PINE_TREE;
        return Field.UNKNOWN;
    }

    /** Haste buff: a gray running figure on a light square in the buff row (icons sit on a 60 px grid from x=100,
     *  y 178-238 on 2340x1080). Matched by shape (mask taken from a real Haste icon on this phone) so plain gray
     *  scenery or other icons' rings don't count. Returns the slot index, or -1 if Haste isn't active. */
    /** Stack-count text ("x4") under the Haste icon, as its black outline (max channel < 70): cols x0+18..x0+55,
     *  rows y 204..236 step 2 on 2340x1080. Taken from real frames (test/fixtures/haste_x*.png). One stack shows no text. */
    static final int[] HASTE_COUNTS = {4, 5, 7};
    static final String[][] HASTE_DIGITS = {
        {   // x4
        "......................................",
        "......................................",
        "......................................",
        "..................................####",
        "......................................",
        "...............................#......",
        "................#.#..........#........",
        "............#....#....#.....#....##...",
        ".............#.......#....#.......#...",
        "..............#.....#....#............",
        "............##.......#................",
        "...........#....##....#...........#...",
        "..........#....#..................#...",
        "..........#####.....####..........####",
        "......................................",
        "......................................",
        "......................................",
        },
        {   // x5
        "......................................",
        "......................................",
        ".........................############.",
        "........................#...........#.",
        "........................#...#########.",
        "................#.......#...#####.....",
        "..........#....#....#...#.............",
        "...........#.......#....#.....##......",
        "............#.....#..............#....",
        "...........#.......#.................#",
        ".........#....##....#......####.....#.",
        "........#....##.#.....................",
        "........#####.....####....######......",
        "......................................",
        "......................................",
        "......................................",
        "......................................",
        },
        {   // x7
        "......................................",
        "......................................",
        "........................##############",
        ".....................................#",
        "........................#########....#",
        "................#..............#....#.",
        "..........#....#....#.........#....#..",
        "...........#.......#..............#...",
        "............#.....#..........#...#....",
        "...........#.......#........#.........",
        ".........#....##....#.......#...#.....",
        "........#....##.#...............#.....",
        "........#####.....####......####......",
        "......................................",
        "......................................",
        "......................................",
        "......................................",
        },
    };

    /** Haste stacks shown on the icon in buff slot k: 1 when there is no count text, else the best-matching
     *  template (outline overlap both ways, ±2 px shift), or -1 when nothing matches well (unknown digit / x10). */
    public static int hasteStacks(int[] px, int w, int h, int k) {
        double sx = w / 2340.0, sy = h / 1080.0;
        int R = 14, C = 38, x0 = 100 + 60 * k + 18, ink = 0;   // rows below 14 fall under the icon (dark world)
        boolean[][] m = new boolean[R][C];
        for (int r = 0; r < R; r++) for (int c = 0; c < C; c++) {
            int x = (int) ((x0 + c) * sx), y = (int) ((204 + 2 * r) * sy);
            if (x >= w || y >= h) continue;
            int p = px[y * w + x];
            if (m[r][c] = Math.max(r(p), Math.max(g(p), b(p))) < 70) if (c >= 6) ink++;
        }
        if (ink < 6) return 1;
        double best = 0; int bestN = -1;
        for (int t = 0; t < HASTE_DIGITS.length; t++) {
            String[] T = HASTE_DIGITS[t];
            for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
                int tn = 0, th = 0, mn = 0, mh = 0;
                for (int r = 0; r < R; r++) for (int c = 6; c < C; c++) {
                    int rr = r + dy, cc = c + dx;
                    boolean tOn = T[r].charAt(c) == '#';
                    if (tOn) { tn++; if (near(m, rr, cc)) th++; }
                    if (rr >= 0 && rr < R && cc >= 6 && cc < C && m[rr][cc]) { mn++; if (nearT(T, r, c)) mh++; }
                }
                double sc = Math.min(th / (double) Math.max(1, tn), mh / (double) Math.max(1, mn));
                if (sc > best) { best = sc; bestN = HASTE_COUNTS[t]; }
            }
        }
        return best >= 0.6 ? bestN : -1;
    }
    private static boolean near(boolean[][] m, int r, int c) {
        for (int d = -1; d <= 1; d++) { int cc = c + d; if (r >= 0 && r < m.length && cc >= 0 && cc < m[0].length && m[r][cc]) return true; }
        return false;
    }
    private static boolean nearT(String[] T, int r, int c) {
        for (int d = -1; d <= 1; d++) { int cc = c + d; if (cc >= 0 && cc < T[r].length() && T[r].charAt(cc) == '#') return true; }
        return false;
    }

    static final String[] HASTE_MASK = {
        "...........#..######.......",
        ".............########......",
        ".............########......",
        ".............#######.......",
        ".............#######.......",
        ".............#######.......",
        ".......#############.......",
        "......##############.....##",
        "......###...##############.",
        "..##..##....#######..####..",
        "............#######....#...",
        "...........########........",
        "...........########........",
        "...........########........",
        "..........########.........",
        ".........#####.##..........",
        "..###....####...#.#........",
        ".###########....###........",
        "..#########.....####.......",
        "......####.....#####.......",
        "..............#####........",
        ".........#...#####.........",
        ".............###...........",
        "..............#............",
        "...........................",
        "...........................",
        "...........................",
    };
    /** Rows of a solid red message banner in the message log (bottom right), e.g. "You must be standing in a field to
     *  build a Sprinkler". Long unbroken red runs only (white text inside keeps a run going), so red flowers don't count. */
    public static int redBannerRows(int[] px, int w, int h) {
        // the log sits low on the right; a banner row is a long red run with white letters inside it (a red platform
        // rim or roof has no text)
        int rows = 0, minRun = (int) (0.09 * w), minText = Math.max(6, w / 230);
        for (int y = (int) (h * 0.78); y < (int) (h * 0.995); y += 2) {
            int red = 0, white = 0, gap = 0, start = -1;
            for (int x = (int) (w * 0.55); x <= w; x++) {
                int r = 0, g = 0, b = 0;
                if (x < w) { int p = px[y * w + x]; r = (p >> 16) & 255; g = (p >> 8) & 255; b = p & 255; }
                boolean isRed = x < w && r > 170 && g < 115 && b < 115 && r - g > 80, isWhite = x < w && r > 200 && g > 200 && b > 200;
                if (isRed || (start >= 0 && isWhite)) { if (start < 0) start = x; if (isRed) red++; else white++; gap = 0; continue; }
                if (start >= 0 && ++gap <= 6) continue;   // soft letter edges inside the banner
                if (start >= 0) {
                    int span = x - gap - start;
                    if (span >= minRun && white >= minText && red >= 0.55 * span) { rows++; break; }
                }
                red = 0; white = 0; gap = 0; start = -1;
            }
        }
        return rows;
    }

    public static int hasteSlot(int[] px, int w, int h) { return (int) hasteMatch(px, w, h)[0]; }
    /** {first slot or -1, its share of the running-figure mask that matched (the detection score), number of Haste icons}. */
    public static double[] hasteMatch(int[] px, int w, int h) {
        double sx = w / 2340.0, sy = h / 1080.0;
        double[] first = null; int n = 0;
        for (int k = 0; 100 + 60 * k + 58 < 2340; k++) {
            int x0 = 100 + 60 * k, inMask = 0, inHit = 0, out = 0, outHit = 0;
            for (int r = 0; r < HASTE_MASK.length; r++) for (int c = 0; c < HASTE_MASK[r].length(); c++) {
                int x = (int) ((x0 + 3 + 2 * c) * sx), y = (int) ((182 + 2 * r) * sy);
                if (x >= w || y >= h) continue;
                int p = px[y * w + x], mx = Math.max(r(p), Math.max(g(p), b(p))), mn = Math.min(r(p), Math.min(g(p), b(p)));
                boolean gray = mx - mn < 14 && mx >= 95 && mx <= 170;
                if (HASTE_MASK[r].charAt(c) == '#') { inMask++; if (gray) inHit++; } else { out++; if (gray) outHit++; }
            }
            if (inMask > 0 && inHit >= (k < 12 ? 0.4 : 0.6) * inMask && outHit <= 0.25 * out)   // tablet icons are rescaled: true slot 0.44-0.51, others ≤ 0.02; far slots (scenery behind) need more
            { n++; if (first == null) first = new double[]{k, inHit / (double) inMask}; }
        }
        return first == null ? new double[]{-1, 0, 0} : new double[]{first[0], first[1], n};
    }

    /** Roblox mobile shift-lock button ring (centre ≈ (147,700) on 2340x1080): blue when on, light gray when off.
     *  Returns 1 on, 0 off, -1 unknown. */
    public static int shiftLock(int[] px, int w, int h) {
        int on = 0, off = 0, n = 0;
        for (int dx : new int[]{-22, 23}) for (int dy = -3; dy <= 3; dy++) for (int ddx = -1; ddx <= 1; ddx++) {
            int p = at(px, w, h, 147 / 2340.0, 700 / 1080.0, (int) Math.round((dx + ddx) * w / 2340.0), (int) Math.round(dy * h / 1080.0));
            n++;
            if (b(p) > 170 && r(p) < 90 && g(p) > 110) on++;
            else if (r(p) > 185 && g(p) > 185 && b(p) > 185 && sat(p) < 20) off++;
            else {
                // The off ring is translucent white: over the yellow hive floor it's yellowish (measured live), so
                // compare with the background just outside the ring (10 px further out): clearly lighter = off ring.
                int q = at(px, w, h, 147 / 2340.0, 700 / 1080.0, (int) Math.round((dx + (dx < 0 ? -10 : 10) + ddx) * w / 2340.0), (int) Math.round(dy * h / 1080.0));
                if (lum(p) >= lum(q) + 60 && !(b(p) > r(p) + 60)) off++;   // total brightness: bright bg channels are already at 255
            }
        }
        return on * 3 > n ? 1 : off * 3 > n ? 0 : -1;
    }

    /** Number of prompt-box columns holding ≥3 white text pixels (text width). */
    static int textCols(int[] px, int w, int h) {
        int y0 = (int) (200 / 1080.0 * h), y1 = (int) (285 / 1080.0 * h), cols = 0;
        for (int x = (int) (975 / 2340.0 * w); x <= (int) (1495 / 2340.0 * w); x++) {
            int n = 0;
            for (int y = y0; y <= y1 && n < 3; y++) { int p = px[y * w + x]; if (r(p) > 230 && g(p) > 230 && b(p) > 230) n++; }
            if (n >= 3) cols++;
        }
        return cols;
    }

    /** First column (from the box's left) holding ≥3 white text pixels, or -1. */
    static int textLeft(int[] px, int w, int h) {
        int y0 = (int) (200 / 1080.0 * h), y1 = (int) (285 / 1080.0 * h);
        for (int x = (int) (975 / 2340.0 * w); x <= (int) (1495 / 2340.0 * w); x++) {
            int n = 0;
            for (int y = y0; y <= y1; y++) { int p = px[y * w + x]; if (r(p) > 230 && g(p) > 230 && b(p) > 230 && ++n >= 3) return x; }
        }
        return -1;
    }

    /** Roblox "Disconnected" screen background (33,86,169): dark-panel tests must not mistake it for the menu. */
    static boolean isDisconnectBlue(int p) { return b(p) > 140 && b(p) > r(p) + 90; }

    static boolean isPromptBlue(int p) { return b(p) > r(p) + 70 && b(p) > g(p) + 25 && b(p) > 150; }
    static boolean isPromptRed(int p) { return r(p) > b(p) + 60 && r(p) > g(p) + 60 && r(p) > 130; }
    static boolean isMenuBlue(int p) { return b(p) > 85 && b(p) < 175 && r(p) < 60 && g(p) < 90 && b(p) > g(p) + 40; }

    interface Pred { boolean ok(int p); }

    static int at(int[] px, int w, int h, double fx, double fy, int dx, int dy) {
        int x = Math.max(0, Math.min(w - 1, (int) (fx * w) + dx)), y = Math.max(0, Math.min(h - 1, (int) (fy * h) + dy));
        return px[y * w + x];
    }

    static double frac(int[] px, int w, int h, double fx0, double fx1, double fy0, double fy1, Pred pr) {
        int ok = 0, n = 0, step = Math.max(2, w / 600);
        for (int y = (int) (fy0 * h); y <= (int) (fy1 * h); y += step)
            for (int x = (int) (fx0 * w); x <= (int) (fx1 * w); x += step) { n++; if (pr.ok(px[y * w + x])) ok++; }
        return n == 0 ? 0 : ok / (double) n;
    }

    /** Prompt boxes carry white text in the middle, so judge their colour from the top and bottom edge rows. */
    static double edgeFrac(int[] px, int w, int h, double fx0, double fx1, Pred pr) {
        int ok = 0, n = 0;
        for (double fy : new double[]{BOX_Y0, BOX_Y1})
            for (int x = (int) (fx0 * w); x <= (int) (fx1 * w); x += 3) { n++; if (pr.ok(px[(int) (fy * h) * w + x])) ok++; }
        return ok / (double) n;
    }
}
