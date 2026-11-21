package com.iipxp.beemacro;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Discord webhook reports, like Natro Macro's: an embed with the event (started, backpack full, converting, honey
 * made, balloon, Mondo, rejoin / problems, hourly report, stopped) and, if you want, a screenshot of the game.
 * Only to the webhook URL you paste in Settings (checked to be a Discord webhook); the URL is never logged.
 * Sends one message at a time in the background, so the macro never waits on the network.
 */
final class Webhook {
    static final int PURPLE = 0x8B5CF6, CYAN = 0x22D3EE, GOLD = 0xFFC233, GREEN = 0x34D399, RED = 0xF43F5E, GREY = 0x6B6690;
    private static final ExecutorService one = Executors.newSingleThreadExecutor();
    static volatile String lastStatus = "nothing sent yet";

    /** A real Discord webhook address (https, discord.com / discordapp.com, /api/webhooks/<id>/<token>). */
    static boolean valid(String url) {
        return url != null && url.matches("https://(?:(?:ptb|canary)\\.)?discord(?:app)?\\.com/api/webhooks/\\d+/[A-Za-z0-9_\\-]+");
    }

    /** Queue a report. kind: "convert", "field", "problem", "hourly", "session" (each can be switched off). */
    static void send(Context c, String kind, String title, String text, int color, boolean screenshot) {
        Profile p = App.profile(c);
        if (!valid(p.webhookUrl) || !wanted(p, kind)) return;
        boolean shot = screenshot && p.whScreens && CaptureService.inst != null;
        boolean ping = kind.equals("problem") && p.discordPingId.matches("\\d{5,25}");
        String url = p.webhookUrl;
        one.execute(() -> {
            byte[] jpg = shot ? grab() : null;
            post(url, title, text, color, jpg, ping ? p.discordPingId : null);
        });
    }
    static boolean wanted(Profile p, String kind) {
        switch (kind) {
            case "convert": return p.whConvert;
            case "field": return p.whField;
            case "problem": return p.whProblems;
            case "hourly": return p.whHourly;
            default: return true;   // test, session start / end
        }
    }

    /** The next raw frame from the capture, shrunk to at most 1280 px wide, as JPEG (Monitor copies one on request). */
    private static byte[] grab() {
        long since = Monitor.shotTime;
        Monitor.shotWanted = true;
        long end = SystemClock.uptimeMillis() + 2000;
        while (Monitor.shotTime == since && SystemClock.uptimeMillis() < end) SystemClock.sleep(50);
        int[] px = Monitor.shotPx; int w = Monitor.shotW, h = Monitor.shotH;
        Monitor.shotPx = null;
        if (px == null || Monitor.shotTime == since) return null;
        Bitmap full = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        float k = Math.min(1f, 1280f / Math.max(w, h));
        Bitmap bm = k < 1 ? Bitmap.createScaledBitmap(full, Math.round(w * k), Math.round(h * k), true) : full;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.JPEG, 72, o);
        if (bm != full) bm.recycle();
        full.recycle();
        return o.toByteArray();
    }

    private static void post(String url, String title, String text, int color, byte[] jpg, String pingId) {
        String json = "{\"username\":\"Silly Macro\"" + (pingId != null ? ",\"content\":\"<@" + pingId + ">\",\"allowed_mentions\":{\"users\":[\"" + pingId + "\"]}" : "")
                + ",\"embeds\":[{\"title\":" + q(title) + ",\"description\":" + q(text) + ",\"color\":" + color
                + ",\"timestamp\":" + q(new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(new java.util.Date()))
                + (jpg != null ? ",\"image\":{\"url\":\"attachment://screen.jpg\"}" : "")
                + ",\"footer\":{\"text\":\"Silly Macro · " + q(Profile.PRESET).replace("\"", "") + "\"}}]}";
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String b = "----sillymacro" + Long.toHexString(System.nanoTime());
                HttpURLConnection c = (HttpURLConnection) new URL(url + "?wait=false").openConnection();
                c.setConnectTimeout(15000); c.setReadTimeout(20000); c.setDoOutput(true); c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + b);
                c.setRequestProperty("User-Agent", "SillyMacro (Android)");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n").getBytes("UTF-8"));
                    out.write(json.getBytes("UTF-8"));
                    if (jpg != null) {
                        out.write(("\r\n--" + b + "\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"screen.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n").getBytes("UTF-8"));
                        out.write(jpg);
                    }
                    out.write(("\r\n--" + b + "--\r\n").getBytes("UTF-8"));
                }
                int code = c.getResponseCode();
                if (code == 429 && attempt == 0) {   // Discord rate limit: wait what it says once, then retry
                    String body = read(c.getErrorStream());
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"retry_after\"\\s*:\\s*([0-9.]+)").matcher(body);
                    SystemClock.sleep(m.find() ? (long) Math.min(10_000, Double.parseDouble(m.group(1)) * 1000) : 2000);
                    continue;
                }
                lastStatus = code / 100 == 2 ? "sent: " + title : "Discord said " + code;
                if (code / 100 != 2) Log.add("webhook: Discord answered " + code + " for \"" + title + "\"");
                return;
            } catch (Exception e) { lastStatus = "not sent: " + e.getClass().getSimpleName(); Log.add("webhook: not sent (" + e.getClass().getSimpleName() + ")"); return; }
        }
    }

    private static String read(InputStream in) { try (InputStream i = in) { return i == null ? "" : new String(i.readAllBytes(), "UTF-8"); } catch (Exception e) { return ""; } }
    /** JSON string literal. */
    static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) { case '"': b.append("\\\""); break; case '\\': b.append("\\\\"); break; case '\n': b.append("\\n"); break; case '\r': break; case '\t': b.append("\\t"); break;
                default: if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch)); else b.append(ch); }
        }
        return b.append('"').toString();
    }
}
