package com.iipxp.beemacro;


import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Natro Macro walking routes (NatroTeam/NatroMacro paths/gtf-*.ahk = hive → field, wf-*.ahk = field → hive,
 * GPL-3.0), run from their original files in assets/paths. Always the WALK branch: MoveMethod = "Walk" and
 * HiveBees = 0 (the cannon glide fails with Shift Lock on the tablet). Reads the subset those files use:
 * nm_gotoramp(), nm_Walk(tiles, key[, key]), send "{" Key " down}" / "{space}" / "{e down}" / "{" RotRight " 2}",
 * Walk(tiles) with keys held, (Hyper)Sleep, if/else, switch HiveSlot { case 3: … default: … }, loop n { }.
 * Pure Java, so the desktop self-test runs it ("X" steps carry an Android key code: 33 = E).
 */
public final class Routes {
    /** Field key (file name part) and display name, Natro's 17 gathering fields. */
    public static final String[][] FIELDS = {
            {"pinetree", "Pine Tree"}, {"blueflower", "Blue Flower"}, {"bamboo", "Bamboo"}, {"sunflower", "Sunflower"},
            {"dandelion", "Dandelion"}, {"mushroom", "Mushroom"}, {"clover", "Clover"}, {"strawberry", "Strawberry"},
            {"spider", "Spider"}, {"pineapple", "Pineapple"}, {"stump", "Stump"}, {"cactus", "Cactus"},
            {"pumpkin", "Pumpkin"}, {"rose", "Rose"}, {"mountaintop", "Mountain Top"}, {"pepper", "Pepper"}, {"coconut", "Coconut"}};
    /** Natro FieldDefault: pattern, size, width for each field (same order as FIELDS). */
    public static final String[][] DEFAULTS = {
            {"CornerXSnake", "M", "3"}, {"CornerXSnake", "L", "1"}, {"Snake", "M", "1"}, {"Snake", "L", "1"},
            {"CornerXSnake", "M", "1"}, {"Snake", "M", "1"}, {"Lines", "M", "1"}, {"Snake", "M", "1"},
            {"Snake", "M", "1"}, {"Squares", "M", "1"}, {"Snake", "M", "1"}, {"Squares", "M", "1"},
            {"Snake", "XS", "1"}, {"CornerXSnake", "M", "1"}, {"Snake", "M", "1"}, {"Snake", "M", "1"}, {"Snake", "M", "1"}};

    /** Natro nm_setSprinkler field sizes {length, width} (ms at Natro's reference speed, for distance 10). */
    static final int[][] SPRINKLER = {
            {2500, 1750}, {2750, 750}, {3000, 1250}, {1250, 2000}, {2500, 1000}, {1250, 1750}, {2000, 1500}, {1500, 2000},
            {2000, 2000}, {1750, 3000}, {1500, 1500}, {1500, 2500}, {1500, 2500}, {2500, 1500}, {2250, 1500}, {1500, 2250}, {1500, 2250}};
    /** Tiles to walk {forward/back, left/right} from the field entry to the sprinkler spot at this distance (1-10).
     *  Natro: ms = size * dist/10 * 18/speed, i.e. size * dist/10 * 18/4000 tiles at any speed. */
    public static double[] sprinklerTiles(String key, int dist) {
        int[] s = SPRINKLER[index(key)];
        return new double[]{s[0] * dist / 10.0 * 18 / 4000, s[1] * dist / 10.0 * 18 / 4000};
    }

    public static String name(String key) { for (String[] f : FIELDS) if (f[0].equals(key)) return f[1]; return key; }
    public static int index(String key) { for (int i = 0; i < FIELDS.length; i++) if (FIELDS[i][0].equals(key)) return i; return 0; }
    /** Fields whose flowers are blue/white like Pine Tree: the colour-based field guard understands these. */
    public static boolean blueField(String key) { return key.equals("pinetree") || key.equals("blueflower") || key.equals("bamboo"); }

    public static List<Moves.Step> steps(String source, int hiveSlot, double moveSpeed) {
        List<String> ls = new ArrayList<>();
        for (String l : source.replace("﻿", "").split("\r?\n")) {
            int c = l.indexOf(';');
            if (c >= 0) l = l.substring(0, c);
            l = l.trim();
            if (!l.isEmpty()) ls.add(l);
        }
        List<Moves.Step> out = new ArrayList<>();
        new Run(hiveSlot, moveSpeed, out).block(ls, 0, ls.size());
        return out;
    }

    private static final Pattern IF = Pattern.compile("(?i)if\\s*\\((.*)\\)\\s*\\{?");
    private static final Pattern ELSE = Pattern.compile("(?i)\\}?\\s*else\\s*\\{?");
    private static final Pattern CALL = Pattern.compile("(?i)(nm_walk|walk|hypersleep|sleep|nm_gotoramp|send|sendinput)\\s*\\((.*)\\)");
    private static final Pattern CMD = Pattern.compile("(?i)(send|sendinput|sleep)\\s+(.+)");
    private static final Pattern BRACE = Pattern.compile("\\{\\s*(\\w+)(?:\\s+(\\w+))?\\s*\\}");

    private static final class Run {
        final int slot; final double ms; final List<Moves.Step> out;
        final EnumSet<Moves.Key> held = EnumSet.noneOf(Moves.Key.class);
        Run(int slot, double ms, List<Moves.Step> out) { this.slot = slot; this.ms = ms; this.out = out; }

        void block(List<String> ls, int from, int to) {
            for (int i = from; i < to; i++) {
                String l = ls.get(i);
                Matcher m;
                if ((m = IF.matcher(l)).matches()) {
                    int open = l.endsWith("{") ? i : i + 1, end = close(ls, open);
                    boolean yes = cond(m.group(1));
                    int elseOpen = -1;
                    if (ELSE.matcher(ls.get(end)).matches() && ls.get(end).contains("else")) elseOpen = end;
                    else if (end + 1 < to && ELSE.matcher(ls.get(end + 1)).matches()) elseOpen = ls.get(end + 1).endsWith("{") ? end + 1 : end + 2;
                    int elseEnd = elseOpen < 0 ? end : close(ls, elseOpen);
                    if (yes) block(ls, open + 1, end); else if (elseOpen >= 0) block(ls, elseOpen + 1, elseEnd);
                    i = elseEnd;
                } else if (l.toLowerCase(Locale.US).startsWith("switch")) {
                    if (!l.toLowerCase(Locale.US).matches("switch\\s+hiveslot\\s*\\{?")) throw new IllegalArgumentException("can't read: " + l);
                    int open = l.endsWith("{") ? i : i + 1, end = close(ls, open);
                    int chosen = -1, dflt = -1;
                    for (int k = open + 1; k < end; k++) {
                        String c = ls.get(k).toLowerCase(Locale.US);
                        if (c.matches("case\\s+[\\d, ]+:") && chosen < 0) for (String n : c.replaceAll("[^\\d,]", "").split(",")) if (!n.isEmpty() && Integer.parseInt(n) == slot) chosen = k;
                        if (c.equals("default:")) dflt = k;
                    }
                    int start = chosen >= 0 ? chosen : dflt;
                    if (start >= 0) {
                        int stop = start + 1;
                        while (stop < end && !ls.get(stop).toLowerCase(Locale.US).matches("(case\\s+[\\d, ]+|default):")) stop++;
                        block(ls, start + 1, stop);
                    }
                    i = end;
                } else if (l.toLowerCase(Locale.US).matches("loop\\s+\\d+\\s*\\{?")) {
                    int open = l.endsWith("{") ? i : i + 1, end = close(ls, open);
                    int n = Integer.parseInt(l.replaceAll("[^\\d]", ""));
                    for (int k = 0; k < n; k++) block(ls, open + 1, end);
                    i = end;
                } else if (l.toLowerCase(Locale.US).startsWith("setkeydelay")) {
                    // timing between AutoHotkey key sends: not needed here
                } else if ((m = CALL.matcher(l)).matches()) call(m.group(1).toLowerCase(Locale.US), m.group(2), l);
                else if ((m = CMD.matcher(l)).matches()) call(m.group(1).toLowerCase(Locale.US), m.group(2), l);
                else throw new IllegalArgumentException("can't read: " + l);
            }
        }

        void call(String fn, String arg, String line) {
            switch (fn) {
                case "nm_gotoramp": out.addAll(Moves.natroToRamp(slot, ms)); return;
                case "nm_walk": {
                    String[] a = args(arg);
                    Moves.Key[] k = new Moves.Key[a.length - 1];
                    for (int i = 1; i < a.length; i++) k[i - 1] = dir(a[i].trim(), line);
                    out.add(new Moves.Step('H', k, 1.0, (int) Moves.tileMs(num(a[0]), ms), null));
                    return;
                }
                case "walk": hold((int) Moves.tileMs(num(args(arg)[0]), ms)); return;
                case "hypersleep": case "sleep": hold((int) Math.round(num(args(arg)[0]))); return;
                default: send(arg, line);
            }
        }

        /** Time passing: with keys down the character keeps walking (and jumping), else it waits. */
        void hold(int ms) {
            if (held.isEmpty()) out.add(new Moves.Step('P', null, 0, ms, null));
            else out.add(new Moves.Step('H', held.toArray(new Moves.Key[0]), 1.0, ms, null));
        }

        void send(String arg, String line) {
            StringBuilder s = new StringBuilder();   // AutoHotkey string concatenation: "{" RotRight " 2}" -> {RotRight 2}
            Matcher q = Pattern.compile("\"([^\"]*)\"|(\\w+)").matcher(arg);
            while (q.find()) s.append(q.group(1) != null ? q.group(1) : q.group(2));
            Matcher b = BRACE.matcher(s);
            boolean any = false;
            while (b.find()) {
                any = true;
                String k = b.group(1).toLowerCase(Locale.US), what = b.group(2) == null ? "" : b.group(2).toLowerCase(Locale.US);
                if (k.equals("rotright") || k.equals("rotleft")) { int n = what.isEmpty() ? 1 : Integer.parseInt(what); out.add(new Moves.Step('R', null, 0, k.equals("rotright") ? n : -n, null)); }
                else if (k.equals("space") || k.equals("sc_space")) {
                    if (what.equals("down")) held.add(Moves.Key.SPACE); else if (what.equals("up")) held.remove(Moves.Key.SPACE);
                    else for (int n = what.isEmpty() ? 1 : Integer.parseInt(what); n > 0; n--) { EnumSet<Moves.Key> j = EnumSet.copyOf(held.isEmpty() ? EnumSet.of(Moves.Key.SPACE) : held); j.add(Moves.Key.SPACE); out.add(new Moves.Step('H', j.toArray(new Moves.Key[0]), 1.0, 100, null)); }
                } else if (k.equals("e") || k.equals("sc_e")) { if (!what.equals("up")) out.add(new Moves.Step('X', null, 0, 33, "E")); }
                else {
                    Moves.Key d = dir(b.group(1), line);
                    if (what.equals("down")) held.add(d); else if (what.equals("up")) held.remove(d); else throw new IllegalArgumentException("can't read: " + line);
                }
            }
            if (!any) throw new IllegalArgumentException("can't read: " + line);
        }

        Moves.Key dir(String k, String line) {
            switch (k.toLowerCase(Locale.US)) {
                case "fwdkey": return Moves.Key.W; case "backkey": return Moves.Key.S;
                case "leftkey": return Moves.Key.A; case "rightkey": return Moves.Key.D;
                default: throw new IllegalArgumentException("unknown key " + k + " in: " + line);
            }
        }

        /** Walk branch always: MoveMethod = "Walk", HiveBees = 0. Handles ||, &&, =, <, > on those. */
        boolean cond(String c) {
            for (String or : c.split("\\|\\|")) {
                boolean all = true;
                for (String and : or.split("&&")) {
                    String t = and.replaceAll("[()]", "").trim();
                    Matcher m = Pattern.compile("(\\w+)\\s*(<=|>=|=|<|>)\\s*\"?([\\w.]+)\"?").matcher(t);
                    if (!m.matches()) throw new IllegalArgumentException("can't read condition: " + c);
                    String var = m.group(1).toLowerCase(Locale.US), op = m.group(2), val = m.group(3);
                    boolean r;
                    if (var.equals("movemethod")) r = op.equals("=") && val.equalsIgnoreCase("walk");
                    else if (var.equals("hivebees") || var.equals("hiveslot")) {
                        double x = var.equals("hivebees") ? 0 : slot, y = Double.parseDouble(val);
                        r = op.equals("=") ? x == y : op.equals("<") ? x < y : op.equals(">") ? x > y : op.equals("<=") ? x <= y : x >= y;
                    } else throw new IllegalArgumentException("unknown value " + m.group(1) + " in: " + c);
                    all &= r;
                }
                if (all) return true;
            }
            return false;
        }

        static String[] args(String a) {
            List<String> o = new ArrayList<>(); int d = 0, st = 0;
            for (int i = 0; i < a.length(); i++) { char ch = a.charAt(i); if (ch == '(') d++; else if (ch == ')') d--; else if (ch == ',' && d == 0) { o.add(a.substring(st, i)); st = i + 1; } }
            o.add(a.substring(st));
            return o.toArray(new String[0]);
        }
        static double num(String s) { return Patterns.eval(s.trim(), new java.util.HashMap<>()); }

        /** Line holding the '}' that closes the block whose '{' ends line `open` (braces in strings don't count). */
        static int close(List<String> ls, int open) {
            int depth = 0;
            for (int i = open; i < ls.size(); i++) {
                String l = ls.get(i).replaceAll("\"[^\"]*\"", "");
                for (int k = 0; k < l.length(); k++) {
                    char ch = l.charAt(k);
                    if (i == open && depth == 0 && ch != '{') continue;   // the opening line: count from its '{'
                    if (ch == '{') depth++; else if (ch == '}' && --depth == 0) return i;
                }
            }
            throw new IllegalArgumentException("block not closed: " + ls.get(open));
        }
    }
}
