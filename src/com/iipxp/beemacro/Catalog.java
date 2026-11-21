package com.iipxp.beemacro;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Reference-image catalog: every token, buff, debuff and loot icon we have a picture of, as an N×N template
 * (ARGB, alpha = which pixels count), plus its source and verification status. Templates come from the BSS wiki
 * ("wiki-reference") or from your own confirmed in-game crops ("verified"). Pure Java: same code on phone and desktop.
 *
 * Matching = normalised cross-correlation (RGB, masked) over a small search of shift / scale / horizontal squash
 * (a spinning token is a squashed disc). Score is the NCC (−1..1); a name is given only if the score clears the
 * threshold AND beats every other name by a margin, otherwise the caller shows Unknown.
 */
public final class Catalog {
    public static final int N = 32;
    public static final class Entry {
        public String name, kind, cat, file, url, status, page;   // kind: BUFF, DEBUFF, TOKEN, LOOT
        public int[] tpl;                                         // N*N ARGB
        public boolean buffRow() { return kind.equals("BUFF") || kind.equals("DEBUFF"); }
    }
    public static final class Match { public Entry best; public double score, second; public String secondName; }

    public final List<Entry> entries = new ArrayList<>();

    // ------------------------------------------------------------------ load / save
    public static Catalog load(InputStream tsv, InputStream bin) throws Exception {
        Catalog c = new Catalog();
        BufferedReader r = new BufferedReader(new InputStreamReader(tsv, "UTF-8"));
        for (String s; (s = r.readLine()) != null; ) {
            if (s.startsWith("#") || s.trim().isEmpty()) continue;
            String[] f = s.split("\t", -1);
            Entry e = new Entry(); e.name = f[0]; e.kind = f[1]; e.cat = f[2]; e.file = f[3]; e.page = f[5]; e.url = f[6]; e.status = f[7];
            c.entries.add(e);
        }
        DataInputStream d = new DataInputStream(bin);
        int n = d.readInt(), nn = d.readInt();
        if (n != c.entries.size() || nn != N) throw new Exception("catalog templates out of date: " + n + " vs " + c.entries.size());
        for (Entry e : c.entries) { e.tpl = new int[N * N]; for (int i = 0; i < N * N; i++) e.tpl[i] = d.readInt(); }
        return c;
    }
    public void saveTemplates(OutputStream out) throws Exception {
        DataOutputStream d = new DataOutputStream(out);
        d.writeInt(entries.size()); d.writeInt(N);
        for (Entry e : entries) for (int v : e.tpl) d.writeInt(v);
        d.flush();
    }

    // ------------------------------------------------------------------ sampling
    /** Bilinear N×N sample of the rectangle (x0,y0,cw,ch) of an ARGB frame. Out-of-frame pixels get alpha 0. */
    public static int[] sample(int[] px, int w, int h, double x0, double y0, double cw, double ch) {
        int[] o = new int[N * N];
        for (int j = 0; j < N; j++) for (int i = 0; i < N; i++) {
            double fx = x0 + (i + 0.5) * cw / N - 0.5, fy = y0 + (j + 0.5) * ch / N - 0.5;
            int ix = (int) Math.floor(fx), iy = (int) Math.floor(fy);
            if (ix < 0 || iy < 0 || ix + 1 >= w || iy + 1 >= h) { o[j * N + i] = 0; continue; }
            double ax = fx - ix, ay = fy - iy;
            int p00 = px[iy * w + ix], p10 = px[iy * w + ix + 1], p01 = px[(iy + 1) * w + ix], p11 = px[(iy + 1) * w + ix + 1];
            int argb = 0;
            for (int sh = 0; sh <= 24; sh += 8) {
                double v = (1 - ax) * (1 - ay) * ((p00 >>> sh) & 255) + ax * (1 - ay) * ((p10 >>> sh) & 255)
                        + (1 - ax) * ay * ((p01 >>> sh) & 255) + ax * ay * ((p11 >>> sh) & 255);
                argb |= ((int) Math.round(v) & 255) << sh;
            }
            o[j * N + i] = argb;
        }
        return o;
    }

    /** Masked RGB NCC between a sample and a template. Buff-row mode also ignores the bottom 38% (stack text). */
    static double ncc(int[] s, int[] t, boolean buff) {
        double ms = 0, mt = 0; int n = 0;
        int cut = buff ? (int) (N * 0.62) : N;
        for (int j = 0; j < cut; j++) for (int i = 0; i < N; i++) {
            int k = j * N + i, a = t[k], b = s[k];
            if ((a >>> 24) < 128 || (b >>> 24) < 128) continue;
            for (int sh = 0; sh <= 16; sh += 8) { mt += (a >> sh) & 255; ms += (b >> sh) & 255; }
            n++;
        }
        if (n < N * N / 8) return -1;
        ms /= 3.0 * n; mt /= 3.0 * n;
        // NCC ignores brightness and contrast, so plain sky/grass/honeycomb correlates with smooth icons.
        // Verification: per-channel means must agree, and contrast must be comparable.
        double[] ca = new double[3], cb = new double[3];
        for (int j = 0; j < cut; j++) for (int i = 0; i < N; i++) {
            int k = j * N + i, a = t[k], b = s[k];
            if ((a >>> 24) < 128 || (b >>> 24) < 128) continue;
            for (int c = 0; c < 3; c++) { ca[c] += (a >> (16 - 8 * c)) & 255; cb[c] += (b >> (16 - 8 * c)) & 255; }
        }
        double cd = 0; for (int c = 0; c < 3; c++) cd = Math.max(cd, Math.abs(ca[c] - cb[c]) / n);
        double st = 0, ss = 0, x = 0;
        for (int j = 0; j < cut; j++) for (int i = 0; i < N; i++) {
            int k = j * N + i, a = t[k], b = s[k];
            if ((a >>> 24) < 128 || (b >>> 24) < 128) continue;
            for (int sh = 0; sh <= 16; sh += 8) { double p = ((a >> sh) & 255) - mt, q = ((b >> sh) & 255) - ms; x += p * q; st += p * p; ss += q * q; }
        }
        double r = x / Math.sqrt(st * ss + 1e-9), contrast = Math.sqrt(ss / Math.max(1, st));
        if (cd > COLOR_TOL || contrast < 0.45 || contrast > 2.2) r -= 0.5;   // fails verification: pushed below any threshold
        return r;
    }
    public static double COLOR_TOL = 55;
    public static int FINE = 6;   // templates that get the full geometry search after the coarse pass

    /** Best entry for a screen rectangle among entries accepted by `want`, searching small geometry variations.
     *  buffRow: shifts ±1 px and scale ±5% (UI icons are flat). World: scale ±15% and horizontal squash to 50%
     *  (spinning disc), centred on the rectangle. Extra templates (your verified crops) compete on equal terms. */
    public Match match(int[] px, int w, int h, double x0, double y0, double cw, double ch, boolean buffRow, java.util.function.Predicate<Entry> want, List<Entry> extra) {
        List<int[]> vs = new ArrayList<>();
        double cx = x0 + cw / 2, cy = y0 + ch / 2;
        if (buffRow) {
            double u = cw / N;
            for (double s : new double[]{0.95, 1, 1.05}) for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++)
                vs.add(sample(px, w, h, cx - cw * s / 2 + dx * u, cy - ch * s / 2 + dy * u, cw * s, ch * s));
        } else {
            for (double s : new double[]{0.85, 1, 1.15}) for (double q : new double[]{1, 0.75, 0.5})
                vs.add(sample(px, w, h, cx - cw * s * q / 2, cy - ch * s / 2, cw * s * q, ch * s));
        }
        Match m = new Match(); m.score = -1; m.second = -1;
        List<Entry> all = new ArrayList<>(entries); if (extra != null) all.addAll(extra);
        // Coarse pass: every template at the centre variant only; full search on the best few (speed: ~5x fewer NCCs)
        int centre = buffRow ? 13 : 0;
        List<Entry> cand = new ArrayList<>(); List<Double> coarse = new ArrayList<>();
        for (Entry e : all) { if (!want.test(e)) continue; cand.add(e); coarse.add(ncc(vs.get(centre), e.tpl, buffRow)); }
        Integer[] order = new Integer[cand.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (p, q) -> Double.compare(coarse.get(q), coarse.get(p)));
        for (int oi = 0; oi < Math.min(FINE, order.length); oi++) {
            Entry e = cand.get(order[oi]);
            double best = -1;
            for (int[] v : vs) best = Math.max(best, ncc(v, e.tpl, buffRow));
            if (best > m.score) {
                if (m.best != null && !m.best.name.equals(e.name)) { m.second = m.score; m.secondName = m.best.name; }
                m.score = best; m.best = e;
            } else if (best > m.second && (m.best == null || !m.best.name.equals(e.name))) { m.second = best; m.secondName = e.name; }
        }
        return m;
    }

    /** Template from a full-size reference image: crop (fractions), trim transparent border, sample to N×N. */
    public static int[] template(int[] px, int w, int h, double[] crop) {
        int x0 = (int) (crop[0] * w), y0 = (int) (crop[1] * h), x1 = (int) (crop[2] * w), y1 = (int) (crop[3] * h);
        int tx0 = x1, ty0 = y1, tx1 = x0, ty1 = y0;
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) if ((px[y * w + x] >>> 24) > 40) {
            tx0 = Math.min(tx0, x); ty0 = Math.min(ty0, y); tx1 = Math.max(tx1, x + 1); ty1 = Math.max(ty1, y + 1);
        }
        if (tx1 <= tx0) { tx0 = x0; ty0 = y0; tx1 = x1; ty1 = y1; }
        return sample(px, w, h, tx0, ty0, tx1 - tx0, ty1 - ty0);
    }
}
