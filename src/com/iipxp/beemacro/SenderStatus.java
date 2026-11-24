package com.iipxp.beemacro;

/** The key sender's "st" reply, parsed (pure Java, tested on desktop). */
public final class SenderStatus {
    /** The key sender keeps running across app updates (a separate process started through Shizuku), so a player who
     *  updated without pressing Home Ã¢â€ â€™ step 2 still had a sender without "hk": the sprinkler key then went out as an
     *  injected event, which Roblox ignores on a phone (sprinkler never placed). Raise this whenever the sender changes;
     *  the app restarts any older sender by itself before a run. 1 = "+hk", 2 = this version check. */
    public static final int PROTO = 4;
    /** "ok <emulator> (...) +hk +p2" -> the emulator text, and proto[0] = 2 (1 for a bare "+hk", 0 for neither).
     *  Null text when the reply isn't "ok ...". */
    public static String parse(String r, int[] proto) {
        proto[0] = 0;
        if (r == null || !r.startsWith("ok ")) return null;
        String s = r.substring(3).trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\s\\+p(\\d+)$").matcher(s);
        if (m.find()) { proto[0] = Integer.parseInt(m.group(1)); s = s.substring(0, m.start()).trim(); }
        if (s.endsWith(" +hk")) { if (proto[0] == 0) proto[0] = 1; s = s.substring(0, s.length() - 4).trim(); }
        return s;
    }
}
