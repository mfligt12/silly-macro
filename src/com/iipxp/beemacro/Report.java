package com.iipxp.beemacro;

/**
 * "Send report to the developer": only the debug log and one screenshot of the Honey/Pollen counter band, nothing
 * else of the screen (no world, chat or names). Plain Java so the self-test checks the crop and the limits; the
 * JPEG and the upload are in MainActivity, the server-side limits in firestore.rules (keep them in step).
 */
public final class Report {
    public static final int MAX_LOG_CHARS = 200_000, MAX_IMAGE_BYTES = 150_000, LOG_LINES = 300;

    /** {x0, y0, x1, y1} of the counter band on a raw landscape frame, or null (portrait: not the game). Uses the
     *  measured HUD position when there is one; otherwise the top-middle strip where every device draws it. */
    public static int[] hudBand(int w, int h) {
        if (w <= h) return null;
        double x0, x1, y0, y1;
        Remap.Phone ph = Remap.phoneCal;
        if (Remap.tablet(w, h) && Remap.uiCal > 0) {
            double u = Remap.uiCal;
            x0 = Remap.cx(540, w, u); x1 = Remap.cx(1740, w, u); y0 = 0; y1 = 124 * u;
        } else if (!Remap.tablet(w, h) && ph != null) {
            x0 = ph.cxX(Remap.S23_CX - 600); x1 = ph.cxX(Remap.S23_CX + 600); y0 = ph.topY(Remap.S23_TOP - 20); y1 = ph.topY(Remap.S23_TOP + 74);
        } else { x0 = 0.15 * w; x1 = 0.85 * w; y0 = 0; y1 = 0.14 * h; }
        int a = clamp(x0, w), b = clamp(y0, h), c = clamp(x1, w), d = clamp(y1, h);
        return c - a < 50 || d - b < 20 ? null : new int[]{a, b, c, d};
    }
    static int clamp(double v, int max) { return (int) Math.max(0, Math.min(max, Math.round(v))); }

    /** The band's pixels (row-major, width = band[2] - band[0]). */
    public static int[] crop(int[] px, int w, int[] band) {
        int cw = band[2] - band[0], ch = band[3] - band[1]; int[] o = new int[cw * ch];
        for (int y = 0; y < ch; y++) System.arraycopy(px, (band[1] + y) * w + band[0], o, y * cw, cw);
        return o;
    }

    /** Emails out (same rule as Share log), then the newest part only if it's too long for one document. */
    public static String log(String text) {
        String t = text.replaceAll("[\\w.+-]+@[\\w-]+\\.[\\w.]+", "[email hidden]");
        return t.length() <= MAX_LOG_CHARS ? t : "…(older lines cut)\n" + t.substring(t.length() - MAX_LOG_CHARS + 20);
    }
    public static int lines(String t) { int n = t.isEmpty() ? 0 : 1; for (int i = 0; i < t.length(); i++) if (t.charAt(i) == '\n') n++; return n; }

    /** Firestore's limit is 1 MiB per document; the image travels as base64 (4/3). */
    public static int docBytes(String log, int imageBytes) { return log.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + (imageBytes + 2) / 3 * 4 + 2000; }
}
