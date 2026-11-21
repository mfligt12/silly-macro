package com.iipxp.beemacro;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole top buff/debuff row, every frame: each slot (60 px grid from x=100 on 2340×1080, as measured) is
 * matched against the catalog's BUFF/DEBUFF icons plus your verified in-game crops; Haste comes from Vision's
 * tested shape matcher. Effects are tracked BY NAME, so an icon sliding left when another expires is not a
 * disappearance. A slot only counts once its pixels held still for 2 frames: the game world behind an empty slot
 * changes as you move, icons don't (this is what stops sky/honeycomb from matching smooth icons).
 * Timers: BSS does not print a countdown on buff icons, so remaining time is never reported; "on for" is measured.
 */
public final class Buffs {
    public static final class Effect {
        public String name, stack, kind, pending; public boolean debuff, active; public double conf; public int slot = -1;
        public long since, lastSeen; public int x0, y0, x1, y1; public int[] tpl;
    }

    final Catalog cat;
    public double minConf = 0.70, margin = 0.04;
    /** Unseen this long = expired. Measured live: 1.5 s made effects blink off/on while the row shifted. */
    public static long EXPIRE_MS = 3000;
    public final Map<String, Effect> effects = new LinkedHashMap<>();
    public final ArrayDeque<String> timeline = new ArrayDeque<>();   // "HH:mm:ss text" newest first (caller formats time)
    private int[][] prev = new int[30][];
    private final int[] stable = new int[30];
    private final String[] lastName = new String[30];
    private final boolean[] evaluated = new boolean[30];
    private final double[] lastConf = new double[30];
    private final String[] lastStack = new String[30];

    public Buffs(Catalog c) { cat = c; }

    static double diff(int[] a, int[] b) {
        if (a == null || b == null) return 999;
        long s = 0; int n = 0, cut = Catalog.N * Catalog.N * 62 / 100;
        for (int i = 0; i < cut; i++) { int p = a[i], q = b[i]; for (int sh = 0; sh <= 16; sh += 8) s += Math.abs(((p >> sh) & 255) - ((q >> sh) & 255)); n += 3; }
        return s / (double) n;
    }

    /** One frame. `verified` = your confirmed in-game buff crops (compete with the wiki icons). Returns this frame's
     *  recognised slots (for boxes); `effects`/`timeline` hold the tracked state. */
    public synchronized List<Effect> update(int[] px, int w, int h, Vision.Result vr, List<Catalog.Entry> verified, long now, java.util.function.Consumer<String> log) {
        double sx = w / 2340.0, sy = h / 1080.0;
        List<Effect> seen = new ArrayList<>();
        for (int k = 0; k < 30; k++) {
            double x0 = (100 + 60 * k) * sx, y0 = 179 * sy, sz = 58 * sx;
            if (x0 + sz > 0.75 * w) break;                                       // the row never reaches the help button
            // the hive prompt / banner ("Tap Make Honey", "To Make Honey, Collect Pollen…") covers slots at x≈848-1500
            if (vr != null && vr.prompt != Vision.Prompt.NONE && x0 + sz > 840 * sx && x0 < 1500 * sx) { lastName[k] = null; evaluated[k] = false; continue; }
            int[] s = Catalog.sample(px, w, h, x0, y0, sz, sz);
            double d = diff(s, prev[k]);
            stable[k] = d < 14 ? stable[k] + 1 : 0;
            boolean same = d < 5 && evaluated[k];                                // unchanged slot: reuse last answer (named or not)
            prev[k] = s;
            if (d >= 5) evaluated[k] = false;
            String name = null; double conf = 0; String stack = null; Catalog.Entry ent = null;
            if (vr != null && vr.hasteSlot == k) {
                name = "Haste"; conf = vr.hasteConf;
                stack = vr.hasteStacks > 1 ? "x" + vr.hasteStacks : vr.hasteStacks < 0 ? StackOcr.read(px, w, h, x0, y0, sz) : null;   // Haste digit set is 4/5/7 only
            } else if (stable[k] >= 1) {
                if (same) { name = lastName[k]; conf = lastConf[k]; stack = lastStack[k]; }
                else {
                    evaluated[k] = true;
                    Catalog.Match m = cat.match(px, w, h, x0, y0, sz, sz, true, e -> e.buffRow(), verified);
                    if (m.best != null && m.score >= minConf && m.score - m.second >= margin && !m.best.kind.equals("IGNORE")) {
                        name = m.best.name; conf = m.score; ent = m.best;
                        stack = StackOcr.read(px, w, h, x0, y0, sz);
                    }
                }
            }
            lastName[k] = name; lastConf[k] = conf; lastStack[k] = stack;
            if (name == null) continue;
            Effect e = effects.get(name);
            if (e == null) { e = new Effect(); e.name = name; effects.put(name, e); }
            if (ent != null) { e.kind = ent.kind; e.debuff = ent.kind.equals("DEBUFF"); }
            else if (e.kind == null) e.kind = "BUFF";
            if (!e.active) { e.active = true; e.since = now; event(log, (e.debuff ? "debuff " : "buff ") + name + " appeared" + String.format(java.util.Locale.US, " (%.2f)", conf)); }
            else if (stack != null && e.stack != null && !stack.equals(e.stack) && stack.equals(e.pending)) event(log, name + " " + e.stack + " → " + stack + (num(stack) > num(e.stack) ? " (stack gained/refreshed)" : " (stack lost)"));
            if (e.slot >= 0 && e.slot != k && e.lastSeen > now - 1500) { /* moved along the row: same effect */ }
            e.slot = k; e.conf = conf;
            // a stack value must read the same on 2 frames before it replaces the shown one (OCR noise)
            if (stack != null) { if (e.stack == null || stack.equals(e.pending)) e.stack = stack; e.pending = stack; } e.lastSeen = now; e.tpl = s;
            e.x0 = (int) x0; e.y0 = (int) y0; e.x1 = (int) (x0 + sz); e.y1 = (int) (y0 + sz);
            seen.add(e);
        }
        for (Effect e : effects.values())
            if (e.active && now - e.lastSeen > EXPIRE_MS) { e.active = false; e.stack = null; event(log, e.name + " expired after " + (e.lastSeen - e.since) / 1000 + " s"); }
        return seen;
    }
    private void event(java.util.function.Consumer<String> log, String s) { timeline.addFirst(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + "  " + s); while (timeline.size() > 100) timeline.removeLast(); if (log != null) log.accept(s); }
    static double num(String s) { try { return Double.parseDouble(s.replaceAll("[^0-9.]", "")); } catch (Exception e) { return 0; } }

    public synchronized List<Effect> active() { List<Effect> o = new ArrayList<>(); for (Effect e : effects.values()) if (e.active) o.add(e); return o; }
}
