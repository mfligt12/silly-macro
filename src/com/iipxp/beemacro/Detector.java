package com.iipxp.beemacro;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * World objects (tokens, loot, your sprinkler…), in stages:
 *  1 Detection: compact blobs that are not Pine Tree ground (grass / blue / white flowers), HUD zones skipped.
 *  2 Classification: each blob's square crop is matched (masked NCC, scale + spin-squash search) against the
 *    catalog's wiki token/loot images AND your verified in-game crops ({@link Library}).
 *  3 Verification: the match must clear the threshold, beat the runner-up name by a margin, and pass the colour /
 *    contrast checks in Catalog.ncc. Your "Ignore" examples remove the object. Overlapping duplicates are merged.
 *  4 Temporal: {@link Tracker} keeps IDs across frames and only calls a name "confirmed" when ≥3 of the last 4
 *    frames agree. 5 Final: the overlay shows confirmed names; everything else reads "Unknown".
 */
public final class Detector {
    public enum Cat { MARK, TOKEN, LOOT, BOOST, LANDMARK, UNKNOWN, IGNORE }
    /** Box colours per category (reference style: blue marks, cyan tokens, green loot, purple boosts). */
    public static final int[] COLOR = {0xFF2F5BFF, 0xFF22D3EE, 0xFF22C55E, 0xFFB15CFF, 0xFFFACC15, 0xFFB0B0B0, 0};

    public static final class Det {
        public String label = "Unknown"; public Cat cat = Cat.UNKNOWN; public double conf;
        public String guess; public double guessScore;   // best catalog candidate even when not accepted (review queue)
        public int x0, y0, x1, y1;            // full-res screen px
        public boolean buff;                  // from the buff row (HUD), not the world
        public int[] tpl;                     // N×N crop (what gets saved when you confirm it)
        public int cx() { return (x0 + x1) / 2; } public int cy() { return (y0 + y1) / 2; }
        public String text() { return String.format(Locale.US, "%s %.2f", label, conf); }
    }

    public static Cat cat(String s) { try { return Cat.valueOf(s); } catch (Exception e) { return Cat.TOKEN; } }

    // ------------------------------------------------------------------ your verified examples
    /** Verified in-game crops you confirmed, as catalog entries (status "verified"). Backed up before every write. */
    public static final class Library {
        public final List<Catalog.Entry> ex = new ArrayList<>();
        public synchronized void add(String label, Cat cat, boolean buff, int[] tpl) {
            Catalog.Entry e = new Catalog.Entry(); e.name = label.trim(); e.cat = cat.name();
            e.kind = cat == Cat.IGNORE ? "IGNORE" : buff ? "BUFF" : cat == Cat.LOOT ? "LOOT" : cat == Cat.LANDMARK ? "LANDMARK" : "TOKEN";
            e.status = "verified"; e.file = "in-game crop"; e.url = ""; e.page = "your screenshots"; e.tpl = tpl.clone();
            ex.add(e);
        }
        public synchronized List<Catalog.Entry> world() { List<Catalog.Entry> o = new ArrayList<>(); for (Catalog.Entry e : ex) if (!e.kind.equals("BUFF")) o.add(e); return o; }
        public synchronized List<Catalog.Entry> buffs() { List<Catalog.Entry> o = new ArrayList<>(); for (Catalog.Entry e : ex) if (e.kind.equals("BUFF") || e.kind.equals("IGNORE")) o.add(e); return o; }
        public synchronized List<String> labels() { List<String> o = new ArrayList<>(); for (Catalog.Entry e : ex) if (!o.contains(e.name)) o.add(e.name); return o; }
        public synchronized int count(String label) { int n = 0; for (Catalog.Entry e : ex) if (e.name.equals(label)) n++; return n; }
        public synchronized void remove(String label) { ex.removeIf(e -> e.name.equals(label)); }
        public synchronized boolean removeLast(String label) { for (int i = ex.size() - 1; i >= 0; i--) if (ex.get(i).name.equals(label)) { ex.remove(i); return true; } return false; }
        public synchronized void save(File f) throws Exception {
            if (f.exists()) java.nio.file.Files.copy(f.toPath(), new File(f.getPath() + ".bak").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            File tmp = new File(f.getPath() + ".tmp");
            try (FileWriter w = new FileWriter(tmp)) {
                for (Catalog.Entry e : ex) {
                    StringBuilder b = new StringBuilder(e.name.replace('|', '/')).append('|').append(e.cat).append('|').append(e.kind).append('|');
                    for (int i = 0; i < e.tpl.length; i++) b.append(i == 0 ? "" : ",").append(Integer.toHexString(e.tpl[i]));
                    w.write(b.append('\n').toString());
                }
            }
            if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw new Exception("rename failed"); }
        }
        public static Library load(File f) {
            Library l = new Library();
            if (!f.exists()) return l;
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                for (String s; (s = r.readLine()) != null; ) {
                    String[] p = s.split("\\|"); if (p.length != 4) continue;              // older formats are skipped
                    String[] v = p[3].split(","); if (v.length != Catalog.N * Catalog.N) continue;
                    int[] t = new int[v.length]; for (int i = 0; i < v.length; i++) t[i] = (int) Long.parseLong(v[i], 16);
                    l.add(p[0], cat(p[1]), p[2].equals("BUFF"), t);
                    l.ex.get(l.ex.size() - 1).kind = p[2];
                }
            } catch (Exception ignored) { }
            return l;
        }
    }

    // ------------------------------------------------------------------ ground model (Pine Tree Forest)
    static boolean ground(int p) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        if (g >= r + 12 && g >= b - 10 && mx - mn > 25) return true;                 // grass (incl. shade)
        if (b >= r + 35 && b >= g + 15) return true;                                  // blue flower petals
        if (mn > 175 && mx - mn < 40) return true;                                    // white petals
        return false;
    }

    // HUD zones that never hold world objects (fractions of a 2340x1080 landscape frame)
    static final double[][] HUD = {
            {0, 0, 1, 0.27},                 // top bar: menu, Honey/Pollen, buff row, prompt
            {0, 0.27, 0.31, 1},              // left: menu icons, shift lock, D-pad
            {0.29, 0.76, 0.72, 1},           // ability row + hotbar
            {0.80, 0.70, 1, 1},              // jump button
            {0.88, 0.17, 1, 0.27},           // help "?"
            {0.84, 0.27, 1, 0.40},           // field "Bloom" counter
            {0.66, 0.74, 1, 1},              // notification feed ("+1 Blueberry…") above the jump button
    };

    public static double minMargin = 0.05, wikiMinConf = 0.92;

    /** Stage 1-3 for one frame. `ignore` = screen rects of our own panel/boxes/labels (captured too). */
    public static List<Det> detect(int[] px, int w, int h, Catalog catalog, Library lib, double minConf, List<int[]> ignore) { return detect(px, w, h, catalog, lib, minConf, ignore, true); }
    /** classify=false: stage 1 only (cheap); the caller classifies per tracked object (see Monitor). */
    public static List<Det> detect(int[] px, int w, int h, Catalog catalog, Library lib, double minConf, List<int[]> ignore, boolean classify) {
        List<Det> out = new ArrayList<>();
        if (w < h) return out;
        int S = Math.max(2, w / 585), gw = w / S, gh = h / S;
        boolean[] fg = new boolean[gw * gh];
        for (int gy = 0; gy < gh; gy++) for (int gx = 0; gx < gw; gx++) {
            int x = gx * S, y = gy * S;
            if (inHud(x, y, w, h) || inAny(ignore, x, y)) continue;
            fg[gy * gw + gx] = !ground(px[y * w + x]);
        }
        int[] lab = new int[gw * gh], stack = new int[gw * gh];
        int next = 0;
        List<Catalog.Entry> mine = lib == null ? null : lib.world();
        for (int i = 0; i < fg.length; i++) {
            if (!fg[i] || lab[i] != 0) continue;
            next++; int sp = 0; stack[sp++] = i; lab[i] = next;
            int n = 0, x0 = gw, y0 = gh, x1 = -1, y1 = -1;
            while (sp > 0) {
                int c = stack[--sp], cx = c % gw, cy = c / gw; n++;
                if (cx < x0) x0 = cx; if (cx > x1) x1 = cx; if (cy < y0) y0 = cy; if (cy > y1) y1 = cy;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int nx = cx + dx, ny = cy + dy;
                    if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue;
                    int j = ny * gw + nx;
                    if (fg[j] && lab[j] == 0) { lab[j] = next; stack[sp++] = j; }
                }
            }
            int bw = x1 - x0 + 1, bh = y1 - y0 + 1;
            double fill = n / (double) (bw * bh);
            double lo = 24.0 * h / 1080 / S, hi = 170.0 * h / 1080 / S;      // small distant tokens down to ~24 px
            if (bw < lo * 0.4 || bh < lo || bw > hi || bh > hi || fill < 0.35 || bw > bh * 2.2) continue;
            Det d = new Det();
            d.x0 = x0 * S; d.y0 = y0 * S; d.x1 = (x1 + 1) * S; d.y1 = (y1 + 1) * S;
            if (classify) classify(px, w, h, d, catalog, mine, minConf);
            if (d.cat != Cat.IGNORE) out.add(d);
        }
        return suppress(out);
    }

    /** Stage 2+3 for one box: square crop around its centre (a token is a disc; a spinning one looks narrower). */
    public static void classify(int[] px, int w, int h, Det d, Catalog catalog, List<Catalog.Entry> mine, double minConf) {
        double side = Math.max(d.x1 - d.x0, d.y1 - d.y0) * 1.1, cx = (d.x0 + d.x1) / 2.0, cy = (d.y0 + d.y1) / 2.0;
        d.tpl = Catalog.sample(px, w, h, cx - side / 2, cy - side / 2, side, side);
        if (catalog == null) return;
        Catalog.Match m = catalog.match(px, w, h, cx - side / 2, cy - side / 2, side, side, false,
                e -> e.kind.equals("TOKEN") || e.kind.equals("LOOT") || e.kind.equals("LANDMARK") || e.kind.equals("IGNORE"), mine);
        if (m.best == null) return;
        d.guess = m.best.name; d.guessScore = m.score;
        // Wiki images alone were measured to produce false names on flower centres/bubbles up to 0.91, so a wiki-only
        // match needs ≥ wikiMinConf; your verified in-game crops use the normal threshold.
        double need = "verified".equals(m.best.status) ? minConf : Math.max(minConf, wikiMinConf);
        if (m.score >= need && m.score - m.second >= minMargin) {
            if (m.best.kind.equals("IGNORE")) { d.cat = Cat.IGNORE; return; }
            d.label = m.best.name; d.cat = cat(m.best.cat); d.conf = m.score;
        }
    }

    /** Duplicate suppression: overlapping boxes (IoU > 0.3 or one inside the other) keep the better-scored one. */
    public static List<Det> suppress(List<Det> ds) {
        ds.sort((a, b) -> Double.compare(Math.max(b.conf, b.guessScore), Math.max(a.conf, a.guessScore)));
        List<Det> keep = new ArrayList<>();
        outer:
        for (Det d : ds) {
            for (Det k : keep) {
                int ix = Math.max(0, Math.min(d.x1, k.x1) - Math.max(d.x0, k.x0)), iy = Math.max(0, Math.min(d.y1, k.y1) - Math.max(d.y0, k.y0));
                double inter = ix * (double) iy, a = (d.x1 - d.x0) * (double) (d.y1 - d.y0), b = (k.x1 - k.x0) * (double) (k.y1 - k.y0);
                if (inter / (a + b - inter) > 0.3 || inter > 0.8 * Math.min(a, b)) continue outer;
            }
            keep.add(d);
        }
        return keep;
    }

    static boolean inHud(int x, int y, int w, int h) {
        double fx = x / (double) w, fy = y / (double) h;
        for (double[] z : HUD) if (fx >= z[0] && fx < z[2] && fy >= z[1] && fy < z[3]) return true;
        return false;
    }
    static boolean inAny(List<int[]> rs, int x, int y) {
        if (rs != null) for (int[] r : rs) if (x >= r[0] && x < r[2] && y >= r[1] && y < r[3]) return true;
        return false;
    }

    // ------------------------------------------------------------------ tracking (stage 4)
    public static final class Tracker {
        public static final class Track {
            public int id; public Det d; public long first, last; public int hits; public double vx, vy;   // px/s
            public double maxConf; public boolean renamed; public long classifiedAt;
            final java.util.ArrayDeque<String> recent = new java.util.ArrayDeque<>();
            final java.util.Map<String, Double> votes = new java.util.HashMap<>();
            /** Name agreed by ≥3 of the last 4 frames, else null (shown as Unknown, never acted on). */
            public String confirmed() {
                java.util.Map<String, Integer> c = new java.util.HashMap<>();
                for (String s : recent) c.merge(s, 1, Integer::sum);
                for (java.util.Map.Entry<String, Integer> e : c.entrySet()) if (e.getValue() >= 3 && !e.getKey().equals("Unknown")) return e.getKey();
                return null;
            }
            public String label() { String b = "Unknown"; double s = 0; for (java.util.Map.Entry<String, Double> e : votes.entrySet()) if (e.getValue() > s) { s = e.getValue(); b = e.getKey(); } return b; }
        }
        public static final class Ended { public int id; public String label; public double conf; public long first, last; public int x, y; public String how; public boolean renamed; }

        public final List<Track> tracks = new ArrayList<>();
        public final java.util.ArrayDeque<Ended> history = new java.util.ArrayDeque<>();
        public long graceMs = 600;
        private int nextId = 1;

        /** Per-object classification hook: (track or null for a new object, its new det) -> true if it classified the det
         *  now, false if it copied the previous answer (which then does not count as a new vote). */
        public interface Classifier { boolean apply(Track t, Det d); }
        public synchronized void update(List<Det> dets, long now, java.util.function.BiPredicate<Integer, Integer> near) { update(dets, now, near, null); }
        public synchronized void update(List<Det> dets, long now, java.util.function.BiPredicate<Integer, Integer> near, Classifier cls) {
            boolean[] used = new boolean[dets.size()];
            for (Track t : tracks) {
                int bi = -1; double bd = Double.MAX_VALUE;
                double dt = Math.max(0.001, (now - t.last) / 1000.0);
                double px = t.d.cx() + t.vx * dt, py = t.d.cy() + t.vy * dt;
                double gate = Math.max(60, 1.2 * Math.max(t.d.x1 - t.d.x0, t.d.y1 - t.d.y0));
                for (int i = 0; i < dets.size(); i++) {
                    Det d = dets.get(i);
                    if (used[i] || d.buff != t.d.buff) continue;
                    double dist = Math.hypot(d.cx() - px, d.cy() - py);
                    if (dist < gate && dist < bd) { bd = dist; bi = i; }
                }
                if (bi < 0) continue;
                used[bi] = true;
                Det d = dets.get(bi);
                double nvx = (d.cx() - t.d.cx()) / dt, nvy = (d.cy() - t.d.cy()) / dt;
                t.vx = 0.5 * t.vx + 0.5 * nvx; t.vy = 0.5 * t.vy + 0.5 * nvy;
                if (bd < 40) { d.x0 = (d.x0 + t.d.x0) / 2; d.y0 = (d.y0 + t.d.y0) / 2; d.x1 = (d.x1 + t.d.x1) / 2; d.y1 = (d.y1 + t.d.y1) / 2; }
                String before = t.confirmed();
                boolean fresh = cls == null || cls.apply(t, d);
                if (fresh && cls != null) t.classifiedAt = now;
                t.d = d; t.last = now; t.hits++; if (fresh) vote(t, d);
                String after = t.confirmed();
                if (before != null && after != null && !before.equals(after)) t.renamed = true;   // recognition changed: flagged
            }
            for (int i = 0; i < dets.size(); i++) {
                if (used[i]) continue;
                Track t = new Track(); t.id = nextId++; t.d = dets.get(i); t.first = t.last = now; t.hits = 1;
                if (cls == null || cls.apply(null, t.d)) { vote(t, t.d); if (cls != null) t.classifiedAt = now; }
                tracks.add(t);
            }
            for (java.util.Iterator<Track> it = tracks.iterator(); it.hasNext(); ) {
                Track t = it.next();
                if (now - t.last <= graceMs) continue;
                it.remove();
                if (t.hits < 2) continue;
                Ended e = new Ended(); e.id = t.id; e.label = t.confirmed() != null ? t.confirmed() : t.label(); e.conf = t.maxConf; e.first = t.first; e.last = t.last;
                e.x = t.d.cx(); e.y = t.d.cy(); e.renamed = t.renamed;
                e.how = near != null && near.test(e.x, e.y) ? "vanished at the player" : "disappeared";
                history.addFirst(e); while (history.size() > 200) history.removeLast();
            }
        }
        private static void vote(Track t, Det d) {
            t.recent.addLast(d.label); while (t.recent.size() > 4) t.recent.removeFirst();
            t.votes.merge(d.label, d.conf, Double::sum);
            if (d.conf > t.maxConf && !d.label.equals("Unknown")) t.maxConf = d.conf;
        }
        public synchronized List<Track> snapshot() { return new ArrayList<>(tracks); }
    }
}
