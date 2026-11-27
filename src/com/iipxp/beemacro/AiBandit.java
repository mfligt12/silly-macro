package com.iipxp.beemacro;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Learns which option (pattern, field, ...) pays best, from the reward it measured each time (bag % per minute).
 * Exponential moving average, so it follows the hive getting stronger over a night; epsilon-greedy with an
 * optimistic bonus for rarely tried options. Pure Java (desktop-tested in test/AiSelfTest).
 */
public final class AiBandit {
    final float alpha;
    final Random random;
    final Map<String, Float> value = new LinkedHashMap<>();
    final Map<String, Integer> pulls = new LinkedHashMap<>();
    int total;

    public AiBandit(float alpha, Random random) { this.alpha = alpha; this.random = random; }
    public AiBandit() { this(0.3f, new Random()); }

    public synchronized void update(String arm, float reward) {
        Float v = value.get(arm);
        value.put(arm, v == null ? reward : v + alpha * (reward - v));
        pulls.put(arm, pulls(arm) + 1);
        total++;
    }

    public synchronized Float estimate(String arm) { return value.get(arm); }
    public synchronized int pulls(String arm) { Integer n = pulls.get(arm); return n == null ? 0 : n; }

    /** 0..1 against the best known arm, plus an exploration bonus (0.5 for an untried arm). */
    public synchronized float score(String arm, List<String> arms) {
        float best = Float.NEGATIVE_INFINITY;
        for (String a : arms) { Float v = value.get(a); if (v != null && v > best) best = v; }
        Float v = value.get(arm);
        int n = pulls(arm);
        float bonus = n == 0 ? 0.5f : (float) Math.min(0.5, 0.25 * Math.sqrt(Math.log(total + 1.0) / n));
        float norm = v == null || best <= 0 ? 0.5f : Math.max(0f, Math.min(1f, v / best));
        return Math.min(1.5f, norm + bonus);
    }

    public synchronized String choose(List<String> arms, float epsilon) {
        if (arms.isEmpty()) throw new IllegalArgumentException("no arms");
        if (random.nextFloat() < epsilon) return arms.get(random.nextInt(arms.size()));
        String best = arms.get(0); float bs = -1;
        for (String a : arms) { float s = score(a, arms); if (s > bs) { bs = s; best = a; } }
        return best;
    }

    /** "arm=value:pulls;..." for saving between runs. */
    public synchronized String save() {
        StringBuilder s = new StringBuilder();
        for (Map.Entry<String, Float> e : value.entrySet())
            s.append(s.length() == 0 ? "" : ";").append(e.getKey()).append('=').append(e.getValue()).append(':').append(pulls(e.getKey()));
        return s.toString();
    }

    public synchronized void load(String s) {
        value.clear(); pulls.clear(); total = 0;
        if (s == null || s.isEmpty()) return;
        for (String part : s.split(";")) {
            int eq = part.lastIndexOf('='), co = part.lastIndexOf(':');
            if (eq <= 0 || co < eq) continue;
            try {
                float v = Float.parseFloat(part.substring(eq + 1, co)); int n = Integer.parseInt(part.substring(co + 1));
                value.put(part.substring(0, eq), v); pulls.put(part.substring(0, eq), n); total += n;
            } catch (NumberFormatException ignored) { }
        }
    }
}
