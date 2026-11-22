package com.iipxp.beemacro;

/**
 * Which field the game says you are gathering in, read from its own message log (bottom right): "+12,664 Honey (from
 * Pine Tree Forest)" or "+23,421 Honey (from Spider Field)". Only the last word of the newest message counts: Pine Tree
 * Forest is the only field whose name ends in "Forest)"; the others end in "Field)" (or "Patch)", not read yet).
 * Messages about other things ("Token Link)", "Balloon)", "Cub Buddy)", "Diamond Drain)") match neither.
 * Templates are 28×12 masks of the white letters cut from full-size tablet frames (2304×1440); live matches score
 * 0.99–1.00, every other word ≤ 0.45 (checked on 50 frames). Full-size frames only: video-compressed ones are too soft.
 */
public final class FieldMessages {
    public enum Said { NONE, PINE_TREE, OTHER_FIELD }
    static final int GW = 28, GH = 12;
    static final String[] FOREST = {
            "####......................#.", "####...................#....", "#......................#...#", "#.........###..#....#..##..#",
            "#.....###.###.###..###.##..#", "###..#..#.#...#..#.#...#...#", "#....#..#.#...####.##..#...#", "#....#..#.#...#......#.#...#",
            "#....#..#.#...#......#.#...#", "#.....###.#...###..###..#..#", "#.....##..#....#....#...#...", "..........................#."};
    static final String[] FIELD = {
            "#####..#..................#.", "#####..#........#......#..#.", "#...............#......#..##", "#......#........#....#.#...#",
            "#......#..####..#...####...#", "###....#..#..#..#..##..#...#", "#......#..####..#..#...#...#", "#......#..#.....#..#...#...#",
            "#......#..#.....#..#...#...#", "#......#..####..#..#####..##", "#......#...#....#....#....#.", "..........................#."};
    static final double FOREST_ASPECT = 3.5, FIELD_ASPECT = 2.77;

    static boolean banner(int p) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        return Math.abs(r - 34) < 14 && Math.abs(g - 87) < 16 && Math.abs(b - 168) < 18;   // the newest message's solid blue
    }
    static boolean white(int p) { return ((p >> 16) & 255) > 200 && ((p >> 8) & 255) > 200 && (p & 255) > 200; }
    static boolean ink(int p) { return ((p >> 16) & 255) > 225 && ((p >> 8) & 255) > 225 && (p & 255) > 225; }

    /** What the newest solid message says about the field, from a full-size frame. */
    public static Said read(int[] px, int w, int h) {
        int x0 = (int) (w * 0.6), y0 = (int) (h * 0.7), minRow = (int) (0.10 * w), minBand = (int) (0.018 * h), gapMax = (int) (0.004 * w);
        int bandA = -1, bandB = -1, curA = -1, curB = -1;
        for (int y = y0; y <= h; y++) {
            int n = 0;
            if (y < h) for (int x = x0; x < w; x += 2) if (banner(px[y * w + x])) n += 2;
            if (y < h && n >= minRow) { if (curA < 0) curA = y; curB = y; continue; }
            if (curA >= 0 && curB - curA >= minBand) { bandA = curA; bandB = curB; }   // keep the lowest = newest band
            curA = -1;
        }
        if (bandA < 0) return Said.NONE;
        int mid = bandA + 2, bx0 = -1, bx1 = -1;
        for (int x = x0; x < w; x++) if (banner(px[mid * w + x])) { if (bx0 < 0) bx0 = x; bx1 = x; }
        if (bx0 < 0) return Said.NONE;
        // last word: the last run of ink columns (gaps up to ~9 px belong to the same word)
        int s0 = -1, s1 = -1, ls0 = -1, ls1 = -1;
        for (int x = bx0; x <= bx1; x++) {
            boolean any = false;
            for (int y = bandA; y <= bandB && !any; y++) any = ink(px[y * w + x]);
            if (!any) continue;
            if (s0 < 0) { s0 = s1 = x; }
            else if (x - s1 > gapMax) { ls0 = s0; ls1 = s1; s0 = s1 = x; }
            else s1 = x;
        }
        if (s0 >= 0) { ls0 = s0; ls1 = s1; }
        if (ls0 < 0) return Said.NONE;
        int Y0 = -1, Y1 = -1;
        for (int y = bandA; y <= bandB; y++) for (int x = ls0; x <= ls1; x++) if (ink(px[y * w + x])) { if (Y0 < 0) Y0 = y; Y1 = y; break; }
        if (Y0 < 0 || Y1 - Y0 < 6) return Said.NONE;
        boolean[] g = new boolean[GW * GH];
        for (int j = 0; j < GH; j++) for (int i = 0; i < GW; i++) {
            int x = ls0 + (int) ((i + .5) * (ls1 - ls0 + 1) / GW), y = Y0 + (int) ((j + .5) * (Y1 - Y0 + 1) / GH);
            g[j * GW + i] = white(px[y * w + x]);
        }
        double aspect = (ls1 - ls0 + 1) / (double) (Y1 - Y0 + 1);
        double forest = iou(g, FOREST), field = iou(g, FIELD);
        if (forest >= 0.75 && Math.abs(aspect - FOREST_ASPECT) < 0.45) return Said.PINE_TREE;
        if (field >= 0.75 && Math.abs(aspect - FIELD_ASPECT) < 0.45) return Said.OTHER_FIELD;
        return Said.NONE;
    }

    static double iou(boolean[] g, String[] t) {
        int inter = 0, uni = 0;
        for (int j = 0; j < GH; j++) for (int i = 0; i < GW; i++) {
            boolean a = g[j * GW + i], b = t[j].charAt(i) == '#';
            if (a && b) inter++; if (a || b) uni++;
        }
        return uni == 0 ? 0 : inter / (double) uni;
    }
}
