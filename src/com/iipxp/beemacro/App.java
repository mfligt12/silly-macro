package com.iipxp.beemacro;

import android.content.Context;

/** Process-wide access to the single loaded PINE_TREE_MASTER_PROFILE. */
final class App {
    private static Profile profile;
    static synchronized Profile profile(Context c) {
        if (profile == null) {
            profile = Profile.load(c.getFilesDir()); Log.add("profile: loaded " + Profile.NAME);
            if (profile.device.isEmpty()) {
                android.util.DisplayMetrics m = c.getResources().getDisplayMetrics();
                boolean tab = Remap.tablet(Math.max(m.widthPixels, m.heightPixels), Math.min(m.widthPixels, m.heightPixels));
                profile.device = tab ? "tablet" : "s23";
                if (tab) profile.applyTabletDefaults();
                profile.save(c.getFilesDir());
                Log.add("profile: first start on " + profile.device + " layout");
            }
        }
        if (!profile.pineSkilletV1) {   // user's Natro field config for Pine Tree + Skillet, applied once
            profile.pineSkilletV1 = true;
            profile.sprinklerLoc = "Upper Left"; profile.sprinklerDist = 7; profile.fieldRotate = 4;   // Rotate Right ×4
            profile.fieldMinutes = 10; profile.backpackThreshold = 95;                                  // until 10 min / 95 %
            profile.save(c.getFilesDir());
            Log.add("profile: Pine Tree Skillet config applied (Upper Left 7, Right ×4, 10 min, 95%)");
        }
        return profile;
    }
    static void save(Context c) { if (!profile(c).save(c.getFilesDir())) Log.add("profile: SAVE FAILED"); }

    /** Your labelled examples (recognition database), kept on the phone only. */
    private static Detector.Library library;
    static synchronized Detector.Library library(Context c) {
        if (library == null) { library = Detector.Library.load(labelsFile(c)); Log.add("labels: " + library.ex.size() + " examples loaded"); }
        return library;
    }
    /** Reference catalog bundled in the APK (assets/catalog.tsv + catalog.bin), null if missing. */
    private static Catalog catalog;
    static synchronized Catalog catalog(Context c) {
        if (catalog == null) {
            try { catalog = Catalog.load(c.getAssets().open("catalog.tsv"), c.getAssets().open("catalog.bin")); Log.add("catalog: " + catalog.entries.size() + " reference entries"); }
            catch (Exception e) { Log.add("catalog: LOAD FAILED " + e); }
        }
        return catalog;
    }
    static java.io.File labelsFile(Context c) { return new java.io.File(c.getFilesDir(), "labels.txt"); }
    static void saveLibrary(Context c) { try { library(c).save(labelsFile(c)); } catch (Exception e) { Log.add("labels: SAVE FAILED " + e); } }
}

/** In-memory log ring (shown in the app) mirrored to logcat tag BeeMacro. */
final class Log {
    static final java.util.ArrayDeque<String> lines = new java.util.ArrayDeque<>();
    static synchronized void add(String s) {
        String l = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + " " + s;
        lines.addFirst(l); while (lines.size() > 300) lines.removeLast();
        android.util.Log.i("BeeMacro", s);
    }
    static synchronized String dump(int n) { StringBuilder b = new StringBuilder(); int i = 0; for (String l : lines) { if (i++ >= n) break; b.append(l).append('\n'); } return b.toString(); }
}
