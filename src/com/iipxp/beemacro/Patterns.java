package com.iipxp.beemacro;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Natro Macro gather patterns (NatroTeam/NatroMacro, patterns/*.ahk, GPL-3.0), run from their original files in
 * assets/patterns. They use a tiny subset of AutoHotkey, read here: variables (`x := expr`), `loop reps {`,
 * `if(facingcorner) {`, `send "{" TCFBKey " down}{" TCLRKey " up}"`, `Walk(tiles)`, `Sleep n` / `HyperSleep(n)`.
 * Each Walk becomes one walk step holding whatever keys are down at that moment, in Natro's tile units (the same
 * 4000 / moveSpeed ms per tile as Moves). Natro's key roles: TC = toward the field centre, AFC = away from it;
 * FB = forward/back, LR = left/right; with no inversion TCFB = W, AFCFB = S, TCLR = A, AFCLR = D. Pure Java.
 */
public final class Patterns {
    public static final String[] NAMES = {"CornerXSnake", "Snake", "Lines", "Squares", "Diamonds", "e_lol", "Auryn",
            "Fork", "SuperCat", "XSnake", "Slimline", "Stationary"};
    public static final String[] SIZES = {"XS", "S", "M", "L", "XL"};

    /** Natro nm_gather: XS 0.25, S 0.5, M 1, L 1.5, XL 2. */
    public static double size(String s) {
        switch (s) { case "XS": return 0.25; case "S": return 0.5; case "L": return 1.5; case "XL": return 2; default: return 1; }
    }

    /** Steps for one run of the pattern (the farm loop repeats it). Throws IllegalArgumentException on text it can't read. */
    public static List<Moves.Step> steps(String source, double size, int reps, boolean facingCorner, boolean invertFB, boolean invertLR, double moveSpeed) {
        vMoveSpeed = moveSpeed;
        Map<String, Double> vars = new HashMap<>();
        vars.put("size", size); vars.put("reps", (double) reps); vars.put("facingcorner", facingCorner ? 1.0 : 0.0);
        Map<String, Moves.Key> keys = new HashMap<>();
        keys.put("TCFBKey", invertFB ? Moves.Key.S : Moves.Key.W); keys.put("AFCFBKey", invertFB ? Moves.Key.W : Moves.Key.S);
        keys.put("TCLRKey", invertLR ? Moves.Key.D : Moves.Key.A); keys.put("AFCLRKey", invertLR ? Moves.Key.A : Moves.Key.D);
        keys.put("FwdKey", Moves.Key.W); keys.put("BackKey", Moves.Key.S); keys.put("LeftKey", Moves.Key.A); keys.put("RightKey", Moves.Key.D);
        List<String> lines = new ArrayList<>();
        for (String l : source.replace("﻿", "").split("\r?\n")) {
            int c = l.indexOf(';');                    // comments (the files have no ';' inside strings)
            if (c >= 0) l = l.substring(0, c);
            l = l.trim();
            if (!l.isEmpty()) lines.add(l);
        }
        List<Moves.Step> out = new ArrayList<>();
        run(lines, 0, lines.size(), vars, keys, EnumSet.noneOf(Moves.Key.class), out);
        return out;
    }

    /** How far one run of the pattern reaches from where it starts, in tiles: {left (≤0), right, back (≤0), forward},
     *  following camera turns in the pattern. null if it turns by 45° (the axes stop lining up with the field).
     *  Diagonal steps move 1/√2 of their length along each axis (Roblox keeps walk speed the same diagonally). */
    public static double[] extent(List<Moves.Step> steps) {
        double x = 0, y = 0, minX = 0, maxX = 0, minY = 0, maxY = 0; int yaw = 0;
        for (Moves.Step s : steps) {
            if (s.type == 'R') { yaw += s.count; continue; }
            if (s.type != 'W' || s.keys == null) continue;
            if (Math.floorMod(yaw, 2) != 0) return null;
            double dx = 0, dy = 0;
            for (Moves.Key k : s.keys) { if (k == Moves.Key.W) dy++; if (k == Moves.Key.S) dy--; if (k == Moves.Key.A) dx--; if (k == Moves.Key.D) dx++; }
            double n = Math.hypot(dx, dy); if (n == 0) continue;
            dx = s.tiles * dx / n; dy = s.tiles * dy / n;
            for (int q = Math.floorMod(yaw / 2, 4); q > 0; q--) { double t = dx; dx = dy; dy = -t; }   // camera turned right: the screen's forward is the field's right
            x += dx; y += dy;
            minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        }
        return new double[]{minX, maxX, minY, maxY};
    }

    private static double vMoveSpeed = 33.35;   // ponytail: static, steps() is only called from one thread at a time
    private static final Pattern SEND = Pattern.compile("(\\w+)\\s*\"\\s*(up|down)");
    private static final Pattern ASSIGN = Pattern.compile("(\\w+)\\s*:=\\s*(.+)");
    private static final Pattern CALL = Pattern.compile("(?i)(Walk|HyperSleep)\\((.*)\\)");
    private static final Pattern BLOCK = Pattern.compile("(?i)(loop|if)\\s*\\(?(.*?)\\)?\\s*\\{");

    private static void run(List<String> ls, int from, int to, Map<String, Double> vars, Map<String, Moves.Key> keys,
                            EnumSet<Moves.Key> held, List<Moves.Step> out) {
        for (int i = from; i < to; i++) {
            String l = ls.get(i);
            Matcher m;
            if ((m = BLOCK.matcher(l)).matches()) {
                int end = close(ls, i, to);
                if (m.group(1).equalsIgnoreCase("if")) { if (eval(m.group(2), vars) != 0) run(ls, i + 1, end, vars, keys, held, out); }
                else {
                    int n = (int) Math.round(eval(m.group(2), vars));
                    Double outer = vars.get("A_Index");
                    for (int k = 1; k <= n; k++) { vars.put("A_Index", (double) k); run(ls, i + 1, end, vars, keys, held, out); }
                    if (outer != null) vars.put("A_Index", outer); else vars.remove("A_Index");
                }
                i = end;
            } else if (l.toLowerCase().startsWith("send")) {
                m = SEND.matcher(l);
                boolean any = false;
                while (m.find()) {
                    Moves.Key k = keys.get(m.group(1));
                    if (k == null) throw new IllegalArgumentException("unknown key " + m.group(1) + " in: " + l);
                    if (m.group(2).equals("down")) held.add(k); else held.remove(k);
                    any = true;
                }
                if (!any) throw new IllegalArgumentException("can't read: " + l);
            } else if ((m = CALL.matcher(l)).matches()) {
                String arg = m.group(2);
                int comma = topComma(arg);
                double v = eval(comma < 0 ? arg : arg.substring(0, comma), vars);   // Walk's 2nd argument is ignored, as in Natro's pattern runner
                if (m.group(1).equalsIgnoreCase("Walk")) out.add(held.isEmpty() ? new Moves.Step('P', null, 0, (int) Moves.tileMs(v, vMoveSpeed), null)   // Natro: Walk with no key down = wait that long
                        : new Moves.Step('W', held.toArray(new Moves.Key[0]), v, 0, null));
                else out.add(new Moves.Step('P', null, 0, (int) Math.round(v), null));
            } else if (l.toLowerCase().startsWith("sleep")) {
                out.add(new Moves.Step('P', null, 0, (int) Math.round(eval(l.substring(5).trim(), vars)), null));
            } else if ((m = ASSIGN.matcher(l)).matches()) {
                vars.put(m.group(1), eval(m.group(2), vars));
            } else throw new IllegalArgumentException("can't read: " + l);
        }
        if (from == 0 && !held.isEmpty()) held.clear();   // Natro releases everything when the pattern ends
    }

    /** Index of the line holding the '}' that closes the block opened on line `open`. */
    private static int close(List<String> ls, int open, int to) {
        int depth = 0;
        for (int i = open; i < to; i++) {
            String l = ls.get(i).replaceAll("\"[^\"]*\"", "");   // braces inside send strings don't count
            for (char c : l.toCharArray()) { if (c == '{') depth++; else if (c == '}' && --depth == 0) return i; }
        }
        throw new IllegalArgumentException("block not closed: " + ls.get(open));
    }
    private static int topComma(String s) { int d = 0; for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); if (c == '(') d++; else if (c == ')') d--; else if (c == ',' && d == 0) return i; } return -1; }

    // ---- arithmetic: + - * / ** ( ), numbers, variables, sqrt()
    static double eval(String s, Map<String, Double> vars) { int[] p = {0}; double v = sum(s, p, vars); skip(s, p); if (p[0] != s.length()) throw new IllegalArgumentException("can't read: " + s); return v; }
    private static void skip(String s, int[] p) { while (p[0] < s.length() && Character.isWhitespace(s.charAt(p[0]))) p[0]++; }
    private static double sum(String s, int[] p, Map<String, Double> v) {
        double a = prod(s, p, v);
        while (true) { skip(s, p); if (p[0] >= s.length()) return a; char c = s.charAt(p[0]); if (c == '+') { p[0]++; a += prod(s, p, v); } else if (c == '-') { p[0]++; a -= prod(s, p, v); } else return a; }
    }
    private static double prod(String s, int[] p, Map<String, Double> v) {
        double a = pow(s, p, v);
        while (true) {
            skip(s, p); if (p[0] >= s.length()) return a; char c = s.charAt(p[0]);
            if (c == '*' && !s.startsWith("**", p[0])) { p[0]++; a *= pow(s, p, v); } else if (c == '/') { p[0]++; a /= pow(s, p, v); } else return a;
        }
    }
    private static double pow(String s, int[] p, Map<String, Double> v) {
        double a = unary(s, p, v); skip(s, p);
        if (s.startsWith("**", p[0])) { p[0] += 2; return Math.pow(a, pow(s, p, v)); }
        return a;
    }
    private static double unary(String s, int[] p, Map<String, Double> v) {
        skip(s, p);
        if (s.charAt(p[0]) == '-') { p[0]++; return -unary(s, p, v); }
        if (s.charAt(p[0]) == '(') { p[0]++; double a = sum(s, p, v); skip(s, p); p[0]++; return a; }
        int st = p[0];
        if (Character.isDigit(s.charAt(st)) || s.charAt(st) == '.') { while (p[0] < s.length() && (Character.isDigit(s.charAt(p[0])) || s.charAt(p[0]) == '.')) p[0]++; return Double.parseDouble(s.substring(st, p[0])); }
        while (p[0] < s.length() && (Character.isLetterOrDigit(s.charAt(p[0])) || s.charAt(p[0]) == '_')) p[0]++;
        String id = s.substring(st, p[0]);
        skip(s, p);
        if (id.equalsIgnoreCase("sqrt") && p[0] < s.length() && s.charAt(p[0]) == '(') { p[0]++; double a = sum(s, p, v); skip(s, p); p[0]++; return Math.sqrt(a); }
        Double x = v.get(id);
        if (x == null) throw new IllegalArgumentException("unknown value " + id);
        return x;
    }
}
