package com.iipxp.beemacro;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

/**
 * The learning part of the AI (Settings → AI: "Learn the best pattern"). Before each trip it picks the farming
 * pattern for the field from your shortlist; after the trip it scores that pattern by how fast the bag filled
 * (bag % per minute) and remembers it across runs, so overnight it settles on what works best for YOUR hive.
 * One learner per field. Pure Java: desktop-tested in test/AiSelfTest.
 */
public final class AiBrain {
    private static AiBrain inst;
    private final File file;
    private final Map<String, AiBandit> perField = new LinkedHashMap<>();
    private final Random random;
    /** Last thing it decided and why (shown in the panel's AI section). */
    public volatile String lastChoice = "", lastWhy = "";

    AiBrain(File file, Random random) { this.file = file; this.random = random; load(); }

    static synchronized AiBrain get(File dir) {
        if (inst == null) inst = new AiBrain(new File(dir, "ai_brain.properties"), new Random());
        return inst;
    }

    synchronized AiBandit bandit(String field) {
        AiBandit b = perField.get(field);
        if (b == null) { b = new AiBandit(0.3f, random); perField.put(field, b); }
        return b;
    }

    /** Shortlist "Snake, e_lol ,Squares" -> the names that exist (available = what assets/patterns holds). */
    public static List<String> shortlist(String csv, List<String> available) {
        List<String> o = new ArrayList<>();
        if (csv == null) return o;
        for (String s : csv.split(",")) {
            String n = s.trim();
            if (!n.isEmpty() && !o.contains(n) && (available == null || available.contains(n))) o.add(n);
        }
        return o;
    }

    /** The pattern to farm this trip, or null (shortlist empty: keep the pattern set in the app). */
    public synchronized String pickPattern(String field, List<String> candidates, float epsilon) {
        if (candidates.isEmpty()) return null;
        AiBandit b = bandit(field);
        String pick = b.choose(candidates, epsilon);
        Float est = b.estimate(pick);
        lastChoice = pick;
        lastWhy = b.pulls(pick) == 0 ? "not tried on this field yet"
                : String.format(Locale.US, "best so far: %.2f%% bag/min over %d trips", est, b.pulls(pick));
        return pick;
    }

    /** Scores the trip. Returns the reward (bag % per minute) or NaN when the trip can't be judged fairly. */
    public synchronized float reportTrip(String field, String pattern, double startPct, double endPct, long ms) {
        if (pattern == null || ms < 60_000 || startPct < 0 || endPct < startPct) return Float.NaN;
        float reward = (float) ((endPct - startPct) / (ms / 60_000.0));
        bandit(field).update(pattern, reward);
        save();
        return reward;
    }

    public synchronized String summary(String field, List<String> candidates) {
        AiBandit b = bandit(field);
        StringBuilder s = new StringBuilder();
        for (String c : candidates) {
            Float v = b.estimate(c);
            s.append(String.format(Locale.US, "\n  %s: %s (%d trips)", c, v == null ? "untried" : String.format(Locale.US, "%.2f%%/min", v), b.pulls(c)));
        }
        return s.toString();
    }

    private void load() {
        if (file == null || !file.exists()) return;
        Properties pr = new Properties();
        try (FileInputStream in = new FileInputStream(file)) { pr.load(in); } catch (Exception e) { return; }
        for (String k : pr.stringPropertyNames()) if (k.startsWith("field.")) bandit(k.substring(6)).load(pr.getProperty(k));
    }

    synchronized void save() {
        if (file == null) return;
        Properties pr = new Properties();
        for (Map.Entry<String, AiBandit> e : perField.entrySet()) pr.setProperty("field." + e.getKey(), e.getValue().save());
        try (FileOutputStream out = new FileOutputStream(file)) { pr.store(out, "Silly Macro AI: learned pattern scores"); } catch (Exception ignored) { }
    }

    synchronized void forget() { perField.clear(); save(); }
}
