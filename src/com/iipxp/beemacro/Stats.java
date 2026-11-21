package com.iipxp.beemacro;

import android.content.Context;
import android.os.SystemClock;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Session statistics. Everything here is MEASURED from the screen or clock; honey is not OCR'd, so it is not reported. */
final class Stats {
    final long startWall = System.currentTimeMillis(), start = SystemClock.uptimeMillis();
    long farmMs, navMs, convertMs, end;
    int conversions, interruptions, navFailures, patternRestarts, errors, loops;
    double backpackPctCollected;                 // sum of measured backpack % at each conversion start
    final List<String> fills = new ArrayList<>(); // "mm:ss 91%"
    String reason = "";
    private String bucket = ""; private long bucketStart = start;

    synchronized void enter(String b) {
        long now = SystemClock.uptimeMillis(), d = now - bucketStart;
        if (bucket.equals("farm")) farmMs += d; else if (bucket.equals("nav")) navMs += d; else if (bucket.equals("convert")) convertMs += d;
        bucket = b; bucketStart = now;
    }
    long elapsed() { return (end > 0 ? end : SystemClock.uptimeMillis()) - start; }
    void fill(double pct) { fills.add(fmt(elapsed()) + " " + Math.round(pct) + "%"); }
    synchronized void finish(String why) { enter(""); end = SystemClock.uptimeMillis(); reason = why; }

    static String fmt(long ms) { long s = ms / 1000; return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format(Locale.US, "%d:%02d", s / 60, s % 60); }

    int corrections, tokensGot, tokensMissed; long collectMs;
    final java.util.Map<String, Integer> tokens = new java.util.TreeMap<>();
    final java.util.Map<String, double[]> rates = new java.util.TreeMap<>();   // boost state -> {bag % gained, ms}
    synchronized void token(String name, boolean got, long ms) { collectMs += ms; if (got) { tokensGot++; tokens.merge(name, 1, Integer::sum); } else tokensMissed++; }
    synchronized void rate(String key, double pct, long ms) { double[] r = rates.computeIfAbsent(key, k -> new double[2]); r[0] += pct; r[1] += ms; }

    // honey (read from the counter) and overnight events
    long honeyStart = -1, honeyNow = -1, honeyStartT, honeyNowT; int rejoins, hiveClaims, fieldExits, balloons;
    final List<String> trips = new ArrayList<>();
    synchronized void honey(long v) { if (v <= 0) return; long t = SystemClock.uptimeMillis(); if (honeyStart < 0) { honeyStart = v; honeyStartT = t; } honeyNow = v; honeyNowT = t; }
    synchronized void trip(String line) { trips.add(fmt(elapsed()) + "  " + line); }
    static String big(long v) { return v < 0 ? "?" : v >= 1_000_000_000_000L ? String.format(Locale.US, "%.2fT", v / 1e12) : v >= 1_000_000_000L ? String.format(Locale.US, "%.2fB", v / 1e9) : v >= 1_000_000 ? String.format(Locale.US, "%.1fM", v / 1e6) : String.valueOf(v); }

    /** Honey per hour so far, for the panel; empty until two readings are 3+ minutes apart. */
    synchronized String honeyRate() {
        if (honeyStart <= 0 || honeyNow <= 0 || honeyNowT - honeyStartT < 180_000) return "";
        return "🍯 +" + big((long) ((honeyNow - honeyStart) / ((honeyNowT - honeyStartT) / 3_600_000.0))) + "/h";
    }

    synchronized String summary() {
        StringBuilder s = new StringBuilder(core());
        if (honeyStart > 0 && honeyNow > 0) {
            double h = Math.max(1, honeyNowT - honeyStartT) / 3_600_000.0;
            s.append(String.format(Locale.US, "%nHoney: %s → %s · made %s (%s per hour)", big(honeyStart), big(honeyNow), big(honeyNow - honeyStart), h < 0.05 ? "—" : big((long) ((honeyNow - honeyStart) / h))));
        } else s.append("\nHoney: counter not read yet");
        s.append(String.format(Locale.US, "%nRejoins %d · hive claims %d · left the field %d× · balloons %d", rejoins, hiveClaims, fieldExits, balloons));
        if (!trips.isEmpty()) { s.append("\nTrips:"); for (String t : trips.subList(Math.max(0, trips.size() - 12), trips.size())) s.append("\n  ").append(t); }
        return s.toString();
    }

    /** Overwritten during the session so a crash or a dead battery still leaves a report. */
    static void saveCurrent(Context c, Stats s) {
        String when = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new java.util.Date(s.startWall));
        String text = "Running since " + when + "\n" + s.summary() + "\n";
        for (File dir : new File[]{c.getFilesDir(), c.getExternalFilesDir(null)}) {   // the second copy can be opened from a file manager or the PC
            if (dir == null) continue;
            try (FileWriter w = new FileWriter(new File(dir, "report_current.txt"))) { w.write(text); }
            catch (Exception e) { Log.add("report: save failed " + e); }
        }
    }

    private String core() {
        StringBuilder rs = new StringBuilder();
        for (java.util.Map.Entry<String, double[]> e : rates.entrySet()) if (e.getValue()[1] > 30_000)   // ≥30 s of data only
            rs.append(String.format(Locale.US, "%n  %s: %.2f%%/min bag fill", e.getKey(), e.getValue()[0] / (e.getValue()[1] / 60_000.0)));
        return String.format(Locale.US, "Total %s · Farm %s · Nav %s · Convert %s%nConversions %d · Backpack filled %.0f%% total (measured)%nLoops %d · Interruptions %d · Nav failures %d · Pattern restarts %d · Errors %d"
                        + "%nTokens collected %d %s · missed %d · collecting time %s · drift corrections %d%nFill rate by boost state:%s%nHoney: read from the counter (no OCR)",
                fmt(elapsed()), fmt(farmMs), fmt(navMs), fmt(convertMs), conversions, backpackPctCollected, loops, interruptions, navFailures, patternRestarts, errors,
                tokensGot, tokens.isEmpty() ? "" : tokens.toString(), tokensMissed, fmt(collectMs), corrections, rs.length() == 0 ? " not enough data yet" : rs);
    }

    static File file(Context c) { return new File(c.getFilesDir(), "session_history.txt"); }

    static void save(Context c, Stats s) {
        if (!App.profile(c).stats) return;
        String when = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new java.util.Date(s.startWall));
        try (FileWriter w = new FileWriter(file(c), true)) {
            w.write(when + " — " + s.reason + "\n" + s.summary() + (s.fills.isEmpty() ? "" : "\nBackpack fills: " + String.join(", ", s.fills)) + "\n\u0000\n");
        } catch (Exception e) { Log.add("stats: save failed " + e); }
    }

    static List<String> history(Context c) {
        List<String> out = new ArrayList<>();
        try { for (String e : new String(Files.readAllBytes(file(c).toPath())).split("\n\u0000\n")) if (!e.trim().isEmpty()) out.add(e.trim()); } catch (Exception ignored) { }
        Collections.reverse(out);
        return out;
    }
}
