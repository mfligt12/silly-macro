package com.iipxp.beemacro;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Installs Shizuku from inside the app: downloads the latest release APK from Shizuku's official GitHub
 * (RikkaApps/Shizuku), checks it IS Shizuku (package moe.shizuku.privileged.api, signed with Rikka's certificate)
 * and hands it to Android's own installer, where you confirm. Nothing is sent anywhere; only GitHub is contacted.
 */
final class ShizukuInstall {
    static final String PKG = "moe.shizuku.privileged.api";
    /** SHA-256 of Rikka's signing certificate (CN=Rikka), checked on release 13.6.0 on 2026-11-21. */
    static final String RIKKA_CERT = "268b5590e868fb08bae7e0ac413564cd1ff88f5ccff74af9dbd0dc918e30db30";
    private static final String ACTION = "com.iipxp.beemacro.SHIZUKU_INSTALL";

    interface Status { void show(String s); }

    /** Run from the UI thread; work happens on a background thread, status messages come back on the UI thread. */
    static void start(Activity a, Status st) {
        PackageManager pm = a.getPackageManager();
        if (!pm.canRequestPackageInstalls()) {   // Android's own switch: "Install unknown apps" for Silly Macro
            st.show("Allow Silly Macro to install apps (the switch that just opened), then tap Install Shizuku again.");
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            return;
        }
        st.show("Downloading Shizuku from its GitHub…");
        new Thread(() -> {
            try {
                String[] rel = latest();
                File apk = new File(a.getCacheDir(), "shizuku.apk");
                download(rel[1], apk, Long.parseLong(rel[2]));
                String why = verify(pm, apk);
                if (why != null) { apk.delete(); post(a, st, "Not installed: " + why); return; }
                post(a, st, "Shizuku " + rel[0] + " checked (signed by Rikka). Confirm the install in Android's prompt.");
                install(a, apk);
                Log.add("shizuku: " + rel[0] + " downloaded, verified, handed to the installer");
            } catch (Exception e) { post(a, st, "Shizuku download failed: " + e.getMessage()); Log.add("shizuku install: " + e); }
        }, "shizuku-install").start();
    }

    private static void post(Activity a, Status st, String s) { a.runOnUiThread(() -> st.show(s)); }

    /** {tag, apk url, size} of the latest release. */
    private static String[] latest() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/RikkaApps/Shizuku/releases/latest").openConnection();
        c.setRequestProperty("Accept", "application/vnd.github+json"); c.setConnectTimeout(15000); c.setReadTimeout(15000);
        String json;
        try (InputStream in = c.getInputStream()) { json = new String(readAll(in), "UTF-8"); }
        org.json.JSONObject o = new org.json.JSONObject(json);
        org.json.JSONArray as = o.getJSONArray("assets");
        for (int i = 0; i < as.length(); i++) {
            org.json.JSONObject x = as.getJSONObject(i);
            String url = x.getString("browser_download_url");
            if (x.getString("name").endsWith(".apk") && url.startsWith("https://github.com/RikkaApps/Shizuku/releases/download/"))
                return new String[]{o.getString("tag_name"), url, String.valueOf(x.getLong("size"))};
        }
        throw new Exception("no APK in the latest release");
    }

    private static void download(String url, File to, long size) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();   // GitHub redirects https → https: followed
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        long n = 0;
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
            byte[] b = new byte[16384];
            for (int r; (r = in.read(b)) > 0; ) { out.write(b, 0, r); n += r; if (n > 50_000_000) throw new Exception("too large"); }
        }
        if (n != size) throw new Exception("size " + n + " ≠ " + size);
    }

    /** null when the file is Shizuku signed by Rikka, else why not. */
    static String verify(PackageManager pm, File apk) throws Exception {
        PackageInfo pi = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (pi == null) return "the file isn't an app";
        if (!PKG.equals(pi.packageName)) return "wrong app (" + pi.packageName + ")";
        if (pi.signingInfo == null || pi.signingInfo.hasMultipleSigners()) return "unexpected signature";
        for (Signature s : pi.signingInfo.getApkContentsSigners()) if (RIKKA_CERT.equals(sha256(s.toByteArray()))) return null;
        return "not signed by Shizuku's developer";
    }

    private static void install(Activity a, File apk) throws Exception {
        PackageInstaller pi = a.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(PKG);
        int id = pi.createSession(sp);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("shizuku.apk", 0, apk.length())) {
                byte[] b = new byte[16384]; for (int r; (r = in.read(b)) > 0; ) out.write(b, 0, r);
                s.fsync(out);
            }
            Context app = a.getApplicationContext();
            app.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {   // Android asks you to confirm
                        Intent confirm = (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
                        if (confirm != null) { confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); c.startActivity(confirm); }
                        return;
                    }
                    Log.add("shizuku install: " + (status == PackageInstaller.STATUS_SUCCESS ? "installed" : "not installed (" + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) + ")"));
                    try { c.unregisterReceiver(this); } catch (Exception ignored) { }
                    apk.delete();
                }
            }, new IntentFilter(ACTION), Context.RECEIVER_NOT_EXPORTED);
            PendingIntent piIntent = PendingIntent.getBroadcast(app, id, new Intent(ACTION).setPackage(app.getPackageName()), PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            s.commit(piIntent.getIntentSender());
        }
    }

    private static String sha256(byte[] b) throws Exception {
        StringBuilder s = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) s.append(String.format("%02x", x));
        return s.toString();
    }
    private static byte[] readAll(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); byte[] b = new byte[8192];
        for (int n; (n = is.read(b)) > 0; ) o.write(b, 0, n);
        return o.toByteArray();
    }
}
