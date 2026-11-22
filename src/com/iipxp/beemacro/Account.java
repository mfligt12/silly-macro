package com.iipxp.beemacro;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONObject;

/**
 * Silly Macro accounts (Firebase). Signing in is required when assets/firebase.properties exists (your builds);
 * a build without it (e.g. from the public source) has no login. The account is checked before every start and
 * every few minutes during a run: a user disabled by the admin (Silly Admin app, or the Firebase console) is
 * stopped and signed out. Stored on the phone: uid, email and the refresh token, never the password.
 * Sent to Firebase: email, last seen time, device model and app version.
 */
final class Account {
    static final long RECHECK_MS = 5 * 60_000L, OFFLINE_GRACE_MS = 24 * 3_600_000L;
    private static Fb fb; private static boolean loaded;
    static volatile String disabledReason = "";
    private static long lastCheck;

    static synchronized Fb fb(Context c) {
        if (!loaded) {
            loaded = true;
            try (java.io.InputStream in = c.getAssets().open("firebase.properties")) {
                java.util.Properties p = new java.util.Properties(); p.load(in);
                String k = p.getProperty("apiKey", "").trim(), pr = p.getProperty("projectId", "").trim();
                if (!k.isEmpty() && !pr.isEmpty()) fb = new Fb(k, pr);
            } catch (Exception ignored) { }   // no config: accounts off
        }
        return fb;
    }
    static boolean configured(Context c) { return fb(c) != null; }
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("account", Context.MODE_PRIVATE); }
    static boolean signedIn(Context c) { return !prefs(c).getString("refresh", "").isEmpty(); }
    static String email(Context c) { return prefs(c).getString("email", ""); }
    static boolean disabled(Context c) { return prefs(c).getBoolean("disabled", false); }

    /** Sign in or create an account, then run the first check. Returns null when OK, else a message. Not on the UI thread. */
    static String signIn(Context c, String email, String pw, boolean create) {
        try {
            Fb.User u = create ? fb(c).signUp(email, pw) : fb(c).signIn(email, pw);
            prefs(c).edit().putString("uid", u.uid).putString("email", u.email).putString("refresh", u.refresh).putBoolean("disabled", false).apply();
            Log.add("account: signed in");
            String why = check(c, true);
            return why;
        } catch (Fb.Error e) { return Fb.explain(e.code); }
    }
    static void signOut(Context c) { prefs(c).edit().clear().apply(); disabledReason = ""; lastCheck = 0; Log.add("account: signed out"); }

    /** Checks the account with the server (at most every 5 min unless forced). Null = may run; else why not. Not on the UI thread. */
    static synchronized String check(Context c, boolean force) {
        if (!configured(c)) return null;
        if (!signedIn(c)) { disabledReason = savedReason(c); return disabled(c) ? "Your Silly Macro account has been disabled" + reasonText() : "Sign in to Silly Macro first."; }
        if (!force && SystemClock.elapsedRealtime() - lastCheck < RECHECK_MS && lastCheck > 0) return null;
        SharedPreferences sp = prefs(c);
        try {
            Fb.User u = fb(c).refresh(sp.getString("refresh", ""));
            String path = "users/" + u.uid;
            JSONObject d = fb(c).get(u.idToken, path);
            if (d != null && Fb.bool(d, "disabled")) return block(c, Fb.str(d, "reason"));
            JSONObject f = new JSONObject();
            f.put("email", Fb.s(sp.getString("email", ""))); f.put("lastSeen", Fb.now());
            f.put("device", Fb.s(Build.MANUFACTURER + " " + Build.MODEL)); f.put("version", Fb.s(version(c)));
            if (d == null) f.put("disabled", Fb.b(false));   // a new user: rules only allow creating it as not disabled
            fb(c).patch(u.idToken, path, f);
            sp.edit().putString("refresh", u.refresh).putLong("okAt", System.currentTimeMillis()).apply();
            lastCheck = SystemClock.elapsedRealtime();
            return null;
        } catch (Fb.Error e) {
            if (e.code.equals("USER_DISABLED")) return block(c, "");
            if (e.code.equals("TOKEN_EXPIRED") || e.code.equals("USER_NOT_FOUND") || e.code.equals("INVALID_REFRESH_TOKEN")) { signOut(c); return "Signed out: sign in again."; }
            // offline / server trouble: allow runs for a day after the last good check
            long ok = sp.getLong("okAt", 0);
            Log.add("account: check failed (" + e.code + ")");
            return System.currentTimeMillis() - ok < OFFLINE_GRACE_MS ? null : "Can't reach the account server (" + Fb.explain(e.code) + ")";
        } catch (org.json.JSONException e) { return null; }
    }
    private static String block(Context c, String reason) {
        disabledReason = reason == null ? "" : reason;
        prefs(c).edit().remove("refresh").putBoolean("disabled", true).putString("reason", disabledReason).apply();
        Log.add("account: DISABLED by admin");
        return "Your Silly Macro account has been disabled" + reasonText();
    }
    static String reasonText() { return disabledReason.isEmpty() ? "." : ": " + disabledReason; }
    static String savedReason(Context c) { return prefs(c).getString("reason", ""); }
    static String version(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName + ""; } catch (Exception e) { return "?"; }
    }
}
