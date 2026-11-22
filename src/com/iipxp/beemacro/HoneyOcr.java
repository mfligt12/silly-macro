package com.iipxp.beemacro;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the Honey counter at the top of the screen (yellow digits with a dark outline) on the tablet's full frame.
 * Digit templates were averaged from labelled screenshots (test/SelfTest checks them). Returns -1 when unsure:
 * every digit must match well and the comma groups must have three digits, so a bad read is dropped, not guessed.
 */
public final class HoneyOcr {
    static final int GW = 10, GH = 14;
    /** Tab S9 FE (UI scale 1), measured on 21 test frames: digits 20-21 px tall; the text always starts at x 741-742
     *  (11 and 12 digits alike), i.e. 410.5 px left of the screen centre. */
    static final double REF_DIGIT_H = 20.7, REF_LEFT = 741.5;
    /** Templates for 0-9, GW x GH, '#' = digit fill. Filled from labelled frames. */
    static final String[] DIGITS = {
            "....##.....########..########..###...##..###...###.##....######....######....###.##....###.###...###.###..###..########..########.....##....",   // 0 (15 samples)
            "....###....######....######.......###.......###.......###.......###.......###.......###.......###.......###...##############################",   // 1 (14 samples)
            "....##.....########.#########..#....###.......###.......###.......###......###......###......###.....####......#############################",   // 2 (16 samples)
            "...###.....########..########.......###.......###....######....#####.....######.......###.......####.#....###.#########.#########....###....",   // 3 (19 samples)
            ".....####.....#####.....#####....##.###...###.###...##..###..###..###..###..###.##############################......###.......###.......###.",   // 4 (6 samples)
            ".########..########..########..###.......#####.....########..########...#...###.......####......####.#....###.#########..########....###....",   // 5 (17 samples)
            "....###.....#######..########.###.......###.......##...##...#########.############.....######....#######..####.########...#######.....###...",   // 6 (6 samples)
            "#############################......###.......###.......###......###.......###......###.......###.......###......####......###.......###.....",   // 7 (22 samples)
            "...###.....#######...########.###....##.###....##..###..##....######....######..##....###.##.....######...#############.#########....###....",   // 8 (14 samples)
            "....##.....########..########.###...###.###....######....###.#########.#########...##..###.......##..##...###..########..#######.....###...."   // 9 (12 samples)
    };

    static boolean fill(int p) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        return r > 190 && g > 150 && r > b + 55 && g > b + 25;
    }

    /** Glyph masks left to right (commas come back as null), from the counter area of a tablet-sized frame. */
    static List<boolean[]> glyphs(int[] px, int w, int h, double u) {
        List<boolean[]> out = new ArrayList<>();
        for (int[] b : boxes(px, w, h, u)) {
            int bh = b[3] - b[2] + 1, bw = b[1] - b[0] + 1;
            if (b[4] == 0) { if (bw >= 2) out.add(null); continue; }   // comma (or a speck)
            boolean[] m = new boolean[GW * GH];
            for (int j = 0; j < GH; j++) for (int i = 0; i < GW; i++) {
                int x = b[0] + (int) ((i + .5) * bw / GW), y = b[2] + (int) ((j + .5) * bh / GH);
                m[j * GW + i] = fill(px[y * w + x]);
            }
            out.add(m);
        }
        return out;
    }

    /** Height in pixels of the tallest digit (the UI scale shows in it), 0 if none. */
    static int digitHeight(int[] px, int w, int h, double u) { int m = 0; for (int[] b : boxes(px, w, h, u)) m = Math.max(m, b[3] - b[2] + 1); return m; }

    /** Text boxes {x0, x1, top, bottom, isDigit} of the counter. The counter sits left of the screen centre at the
     *  top; positions are the Tab S9 FE's (2304×1440) at UI scale u (see Remap). */
    static List<int[]> boxes(int[] px, int w, int h, double u) {
        int x0 = Math.max(0, (int) Remap.cx(735, w, u)), x1 = Math.min(w - 1, (int) Remap.cx(1115, w, u)), y0 = (int) (50 * u), y1 = Math.min(h, (int) (95 * u));
        double sx = u;
        List<int[]> runs = new ArrayList<>();
        int start = -1;
        for (int x = x0; x <= x1; x++) {
            boolean any = false;
            if (x < x1) for (int y = y0; y < y1; y++) if (fill(px[y * w + x])) { any = true; break; }
            if (any && start < 0) start = x;
            if (!any && start >= 0) { runs.add(new int[]{start, x - 1}); start = -1; }
        }
        // the "(+N/sec)" rate follows after a wide gap: keep only the first block of text
        for (int k = 1; k < runs.size(); k++) if (runs.get(k)[0] - runs.get(k - 1)[1] > 16 * sx) { runs = new ArrayList<>(runs.subList(0, k)); break; }
        int maxH = 0, minTop = y1; List<int[]> boxes = new ArrayList<>();
        for (int[] r : runs) {
            int top = y1, bot = y0;
            for (int y = y0; y < y1; y++) for (int x = r[0]; x <= r[1]; x++) if (fill(px[y * w + x])) { top = Math.min(top, y); bot = Math.max(bot, y); }
            boxes.add(new int[]{r[0], r[1], top, bot, 1}); maxH = Math.max(maxH, bot - top + 1); minTop = Math.min(minTop, top);
        }
        // a comma starts low (at u 1.22 it is 15/25 = 60% of a digit's height, so height alone can't tell)
        for (int[] b : boxes) if (b[3] - b[2] + 1 < 0.6 * maxH || b[2] - minTop > 0.4 * maxH) b[4] = 0;
        return boxes;
    }

    /** The honey amount, or -1 if it can't be read with confidence. */
    public static long read(int[] px, int w, int h) { return read(px, w, h, Remap.u(h)); }
    public static long read(int[] px, int w, int h, double u) {
        if (DIGITS.length != 10) return -1;
        List<boolean[]> gs = glyphs(px, w, h, u);
        StringBuilder s = new StringBuilder();
        int group = 0; boolean first = true;
        for (boolean[] g : gs) {
            if (g == null) { if (first ? group < 1 || group > 3 : group != 3) return -1; first = false; group = 0; continue; }
            int best = -1; double bs = 0, second = 0;
            for (int d = 0; d < 10; d++) {
                int same = 0; for (int i = 0; i < GW * GH; i++) if (g[i] == (DIGITS[d].charAt(i) == '#')) same++;
                double sc = same / (double) (GW * GH);
                if (sc > bs) { second = bs; bs = sc; best = d; } else if (sc > second) second = sc;
            }
            if (bs < 0.80 || bs - second < 0.04) return -1;   // 0.80: big foldables (UI 1.58) score a "1" at 0.81
            s.append(best); group++;
        }
        if (s.length() == 0 || (first ? group > 3 : group != 3) || s.length() > 18) return -1;
        return Long.parseLong(s.toString());
    }
}
