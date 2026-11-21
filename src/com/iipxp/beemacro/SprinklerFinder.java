package com.iipxp.beemacro;

import java.util.ArrayDeque;

/**
 * Finds YOUR sprinkler on screen, whatever type it is. It is learned each trip: a picture of the spot just ahead
 * before placing and one after (the character steps back 3 tiles both times) - the difference is the sprinkler.
 * Later it is found again by colour template matching (normalised cross-correlation on a shrunken frame, at three
 * sizes for perspective). Works on the remapped 2340x1080 frame. Pure Java.
 */
public final class SprinklerFinder {
    final int k;                 // shrink factor the template was taken at
    final float[] tpl;           // template, RGB of shrunken pixels, zero-mean over the sprinkler's own pixels
    final int tw, th;            // template size in shrunken pixels
    final int[] cells;           // shrunken pixels that belong to the sprinkler (field behind it doesn't count)
    final double norm;
    double tmean;                // the template's mean brightness (to compare real colours, not just the shape)
    static final double CELL_CLOSE = 45, MIN_CLOSE_SHARE = 0.7;   // a cell is "close" within 45 per channel on average; 70 % must be
    public final int learnedX, learnedY, learnedW, learnedH;   // where it was found when learned (full-res box)

    private SprinklerFinder(int k, float[] tpl, int tw, int th, int[] cells, int x, int y, int w, int h) {
        this.k = k; this.tpl = tpl; this.tw = tw; this.th = th; this.cells = cells; learnedX = x; learnedY = y; learnedW = w; learnedH = h;
        double s = 0; for (int c : cells) for (int ch = 0; ch < 3; ch++) s += tpl[c * 3 + ch] * tpl[c * 3 + ch]; norm = Math.sqrt(s);
    }

    /** Per-pixel median of three frames (bees and pollen sparkles move, the sprinkler doesn't). */
    public static int[] median(int[] a, int[] b, int[] c) {
        int[] o = new int[a.length];
        for (int i = 0; i < a.length; i++) {
            int r = med((a[i] >> 16) & 255, (b[i] >> 16) & 255, (c[i] >> 16) & 255), g = med((a[i] >> 8) & 255, (b[i] >> 8) & 255, (c[i] >> 8) & 255), bl = med(a[i] & 255, b[i] & 255, c[i] & 255);
            o[i] = 0xFF000000 | (r << 16) | (g << 8) | bl;
        }
        return o;
    }
    private static int med(int a, int b, int c) { return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c)); }

    /** Learn from `before` (no sprinkler) and `after` (sprinkler placed), looking inside box x0..x1, y0..y1.
     *  Returns null when there is no single clear new object there (then nothing is anchored, nothing guessed). */
    public static SprinklerFinder learn(int[] before, int[] after, int w, int h, int x0, int y0, int x1, int y1) {
        int bw = x1 - x0, bh = y1 - y0;
        boolean[] m = new boolean[bw * bh];
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
            int p = before[y * w + x], q = after[y * w + x];
            int d = Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)) + Math.abs(((p >> 8) & 255) - ((q >> 8) & 255)) + Math.abs((p & 255) - (q & 255));
            m[(y - y0) * bw + (x - x0)] = d > 90;
        }
        // biggest connected changed area (8-neighbour), its bounding box
        int[] lab = new int[bw * bh]; int best = 0, bestN = 0, bx0 = 0, by0 = 0, bx1 = 0, by1 = 0, id = 0;
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int s = 0; s < m.length; s++) {
            if (!m[s] || lab[s] != 0) continue;
            id++; int n = 0, ax0 = bw, ay0 = bh, ax1 = 0, ay1 = 0;
            q.add(s); lab[s] = id;
            while (!q.isEmpty()) {
                int c = q.poll(), cx = c % bw, cy = c / bw; n++;
                ax0 = Math.min(ax0, cx); ay0 = Math.min(ay0, cy); ax1 = Math.max(ax1, cx); ay1 = Math.max(ay1, cy);
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int nx = cx + dx, ny = cy + dy;
                    if (nx < 0 || ny < 0 || nx >= bw || ny >= bh) continue;
                    int t = ny * bw + nx; if (m[t] && lab[t] == 0) { lab[t] = id; q.add(t); }
                }
            }
            if (n > bestN) { bestN = n; best = id; bx0 = ax0; by0 = ay0; bx1 = ax1; by1 = ay1; }
        }
        int ow = bx1 - bx0 + 1, oh = by1 - by0 + 1;
        if (best == 0 || bestN < 150 || ow < 12 || oh < 12 || ow > 0.8 * bw || oh > 0.8 * bh || bestN < 0.15 * ow * oh) return null;
        int pad = 4, fx = x0 + bx0 - pad, fy = y0 + by0 - pad, fw = ow + 2 * pad, fh = oh + 2 * pad;
        int k = Math.max(2, Math.min(8, Math.min(fw, fh) / 10));
        int tw = fw / k, th = fh / k;
        // cells where at least half the pixels changed = the sprinkler itself
        java.util.List<Integer> cl = new java.util.ArrayList<>();
        for (int j = 0; j < th; j++) for (int i = 0; i < tw; i++) {
            int n = 0, on = 0;
            for (int y = fy + j * k; y < fy + (j + 1) * k; y++) for (int x = fx + i * k; x < fx + (i + 1) * k; x++) {
                int mx = x - x0, my = y - y0; n++;
                if (mx >= 0 && my >= 0 && mx < bw && my < bh && lab[my * bw + mx] == best) on++;
            }
            if (on * 2 >= n) cl.add(j * tw + i);
        }
        if (cl.size() < 6) return null;
        int[] cells = new int[cl.size()]; for (int i = 0; i < cells.length; i++) cells[i] = cl.get(i);
        float[] t = shrink(after, w, fx, fy, tw, th, k);
        double mean = 0; for (int c : cells) mean += t[c * 3] + t[c * 3 + 1] + t[c * 3 + 2];
        mean /= cells.length * 3.0;
        for (int i = 0; i < t.length; i++) t[i] -= mean;   // zero-mean over the sprinkler's cells
        SprinklerFinder f = new SprinklerFinder(k, t, tw, th, cells, fx, fy, fw, fh);
        f.tmean = mean;
        return f;
    }

    /** RGB of the k x k averaged pixels of a tw x th (shrunken) box at full-res (fx, fy). */
    private static float[] shrink(int[] px, int w, int fx, int fy, int tw, int th, int k) {
        float[] o = new float[tw * th * 3];
        int h = px.length / w;
        for (int j = 0; j < th; j++) for (int i = 0; i < tw; i++) {
            int r = 0, g = 0, b = 0, n = 0;
            for (int y = fy + j * k; y < fy + (j + 1) * k; y++) for (int x = fx + i * k; x < fx + (i + 1) * k; x++) {
                if (x < 0 || y < 0 || x >= w || y >= h) continue;
                int p = px[y * w + x]; r += (p >> 16) & 255; g += (p >> 8) & 255; b += p & 255; n++;
            }
            n = Math.max(1, n);
            int o3 = (j * tw + i) * 3; o[o3] = r / (float) n; o[o3 + 1] = g / (float) n; o[o3 + 2] = b / (float) n;
        }
        return o;   // raw colours: learn() makes them zero-mean over the sprinkler's own cells
    }

    /** Best match in the frame between rows y0..y1: {centreX, centreY, score 0..1} in full-res pixels, or null below
     *  minScore. Tries 85 %, 100 % and 120 % size (the sprinkler looks smaller farther away). */
    public double[] locate(int[] px, int w, int h, int y0, int y1, double minScore) {
        double[] best = null;
        for (double sc : new double[]{0.85, 1.0, 1.2}) {
            int k2 = Math.max(1, (int) Math.round(k * sc));
            int sw = w / k2, sh = h / k2;
            float[] img = new float[sw * sh * 3];
            for (int j = 0; j < sh; j++) for (int i = 0; i < sw; i++) {
                int r = 0, g = 0, b = 0;
                for (int y = j * k2; y < (j + 1) * k2; y++) for (int x = i * k2; x < (i + 1) * k2; x++) { int p = px[y * w + x]; r += (p >> 16) & 255; g += (p >> 8) & 255; b += p & 255; }
                int n = k2 * k2, o3 = (j * sw + i) * 3; img[o3] = r / (float) n; img[o3 + 1] = g / (float) n; img[o3 + 2] = b / (float) n;
            }
            int ja = Math.max(0, y0 / k2), jb = Math.min(sh - th, y1 / k2 - th);
            for (int j = ja; j <= jb; j++) for (int i = 0; i + tw <= sw; i++) {
                double sum = 0, sum2 = 0, dot = 0;
                for (int c : cells) {
                    int at = ((j + c / tw) * sw + i + c % tw) * 3, tc = c * 3;
                    for (int ch = 0; ch < 3; ch++) { float v = img[at + ch]; sum += v; sum2 += v * v; dot += v * tpl[tc + ch]; }
                }
                int n = cells.length * 3;
                double var = sum2 - sum * sum / n;   // dot with a zero-mean template ignores the window's mean
                if (var <= 1e-6) continue;
                double score = dot / (Math.sqrt(var) * norm);
                if (score < minScore || (best != null && score <= best[2])) continue;
                int close = 0;   // same shape is not enough: the colours must match too (a field patch can share the shape)
                for (int c : cells) {   // per cell, so edge cells blended with the field don't spoil it
                    int at = ((j + c / tw) * sw + i + c % tw) * 3, tc = c * 3; double d = 0;
                    for (int ch = 0; ch < 3; ch++) d += Math.abs(img[at + ch] - (tpl[tc + ch] + tmean));
                    if (d / 3 <= CELL_CLOSE) close++;
                }
                if (close >= MIN_CLOSE_SHARE * cells.length) best = new double[]{(i + tw / 2.0) * k2, (j + th / 2.0) * k2, score};
            }
        }
        return best;
    }
}
