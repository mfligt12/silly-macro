package com.iipxp.beemacro;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Silly Macro's app: an expandable sidebar of tabs (Home dashboard, Tokens, Buffs, Sequences, Navigation,
 *  Hive, Statistics, Performance, Settings). Every setting is the same one as before, just grouped by tab. All views
 *  are built in code; the macro itself runs in its services, so switching tabs never touches it. */
public class MainActivity extends Activity {
    /** The theme's palette when this screen was built (a theme switch rebuilds the screen). PURPLE = primary button, BLUE = secondary. */
    final int BG = Ui.BG0, CARD = Ui.GLASS, PURPLE = Ui.PURPLE, BLUE = Ui.CYAN, TXT = Ui.TXT, DIM = Ui.DIM;
    final Handler h = new Handler(Looper.getMainLooper());
    Profile p;
    TextView checks, hiveText, history, log, labelsText, buffText, catText;
    LinearLayout reviewRows, catRows;
    Switch overlaySwitch;
    LinearLayout root;   // the page being built: card() adds to it

    // ---- shell: rail + drawer + pages
    static final String[][] TABS = {
            {"home", "🏠", "Home", "Dashboard"},
            {"tokens", "🪙", "Tokens", "Token recognition & collection"}, {"buffs", "✨", "Buffs", "Buff tracker"},
            {"seq", "🧩", "Sequences", "Field, pattern & sprinkler"}, {"nav", "🧭", "Navigation", "Guard, edges, drift & camera"},
            {"hive", "🍯", "Hive", "Hive, conversion & balloon"}, {"redhive", "🔴", "Red Hive", "Red hive tokens, priorities & recognition"},
            {"music", "🎵", "Music", "Background music & playlists"}, {"stats", "📊", "Statistics", "Honey & sessions"},
            {"perf", "⚡", "Performance", "Vision speed, heat & battery"}, {"settings", "⚙", "Settings", "Overlay, overnight & key sender"}};
    /** Simple by default: the tabs most players never need stay hidden until Settings → Show advanced tabs. */
    static final java.util.Set<String> ADVANCED = new java.util.HashSet<>(java.util.Arrays.asList("tokens", "buffs", "seq", "nav", "perf"));
    boolean hidden(String key) { return !p.advanced && ADVANCED.contains(key); }
    final Map<String, View> pages = new LinkedHashMap<>();
    final Map<String, TextView> railItems = new LinkedHashMap<>(), drawerItems = new LinkedHashMap<>();
    FrameLayout content; View scrim; LinearLayout drawer; View current; String currentKey = "home";
    boolean wide;

    // ---- dashboard views
    TextView whStatus, hudState;
    /** Phone/tablet, model, Android version and the screen in landscape: what the HUD reader adapts to. */
    String deviceSummary() { return deviceSummary(this); }
    static String deviceSummary(android.content.Context c) {
        android.view.WindowManager wmgr = c.getSystemService(android.view.WindowManager.class);
        // getMaximumWindowMetrics is Android 11+: on Android 9/10 (Huawei EMUI) it crashed every open after sign-in (Discord)
        android.util.DisplayMetrics m = new android.util.DisplayMetrics();
        if (android.os.Build.VERSION.SDK_INT >= 30) { android.graphics.Rect r = wmgr.getMaximumWindowMetrics().getBounds(); m.widthPixels = r.width(); m.heightPixels = r.height(); }
        else wmgr.getDefaultDisplay().getRealMetrics(m);
        int w = Math.max(m.widthPixels, m.heightPixels), h = Math.min(m.widthPixels, m.heightPixels);
        boolean tab = Remap.tablet(w, h);
        String os = "Android " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")";
        return (tab ? "Tablet" : "Phone") + " · " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + "\n" + os
                + (android.os.Build.VERSION.SDK_INT < 33 ? "  ⚠ Silly Macro needs Android 13 or newer" : "")
                + "\nScreen " + w + " × " + h + " (" + String.format(java.util.Locale.US, "%.2f", w / (double) h) + ":1, "
                + c.getResources().getDisplayMetrics().densityDpi + " dpi)";
    }
    /** The report's log: the same text as Share log (emails hidden), capped for one document. */
    static String reportLog(android.content.Context c) {
        return Report.log("Silly Macro " + Account.version(c) + "\n" + deviceSummary(c) + "\nHUD: " + Monitor.calState + "\n\n" + Log.dump(Report.LOG_LINES));
    }
    TextView heroSub, heroState, tPhase, tTimer, tBag, tHive, tLoops, tRate, tField, tPattern, aiText, startLabel;
    Ui.Bar bagBar; Ui.Pulse livePulse, railPulse; LinearLayout buffChips;
    // ---- statistics + performance
    Ui.Chart honeyChart, rateChart, fpsChart, tempChart; TextView sTotal, sTime, sBest, sCount, pFps, pFrame, pTemp, pBatt, pRam;
    final double[] fpsHist = new double[60], tempHist = new double[60]; int perfN;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        // a crash on an untested phone (Huawei: "grey screen then it kicks me off") leaves crash.txt; the next open shows it
        // with a Share button instead of crashing in the same spot again, then the open after that tries normally
        java.io.File crash = new java.io.File(getFilesDir(), "crash.txt");
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try (java.io.FileWriter fw = new java.io.FileWriter(crash)) { fw.write(versionName() + " · " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " · Android " + android.os.Build.VERSION.RELEASE + "\n" + android.util.Log.getStackTraceString(e)); } catch (Exception ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
        if (crash.exists()) {
            String text = "Silly Macro crashed\n";
            try { text += new String(java.nio.file.Files.readAllBytes(crash.toPath()), "UTF-8"); } catch (Exception ignored) { }
            crash.delete();
            final String report = text.replaceAll("[\\w.+-]+@[\\w-]+\\.[\\w.]+", "[email hidden]");
            TextView tv = new TextView(this); tv.setText(report); tv.setTextSize(11); tv.setTextIsSelectable(true); tv.setPadding(40, 40, 40, 40);
            ScrollView sv = new ScrollView(this); sv.addView(tv); setContentView(sv);
            new android.app.AlertDialog.Builder(this).setTitle("Silly Macro crashed last time")
                    .setMessage("Please share this in #bug-reports so it can be fixed. Open the app again to retry.")
                    .setPositiveButton("Share", (d, w) -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), "Share crash report")))
                    .setNegativeButton("Close", (d, w) -> finish()).show();
            return;
        }
        p = App.profile(this);
        // once only: asked on every open, the prompt landed on top of the game while Start was checking the screen and both Starts were refused ("Roblox menu is open" / "Turn the device sideways"; S23 live 2026-11-24)
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0 && !p.notifAsked) { p.notifAsked = true; App.save(this); requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1); }
        getWindow().setStatusBarColor(Ui.BG1); getWindow().setNavigationBarColor(BG);
        if (Ui.light) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        wide = getResources().getConfiguration().screenWidthDp >= 640;
        if (Account.configured(this) && !Account.signedIn(this)) { buildLogin(); return; }
        if (Account.configured(this)) new Thread(() -> {   // re-check on every open: a disabled account goes back to the login page
            String why = Account.check(this, true);
            if (why != null && !Account.signedIn(this)) runOnUiThread(this::recreate);
        }, "account").start();

        // first-run setup until it's finished (players asked for every setting in one place before the app)
        if (!p.setupDone) { new SetupWizard(this).show(); return; }

        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(new Ui.HoneyBackground(this));
        frame.setFitsSystemWindows(true);
        LinearLayout main = new LinearLayout(this); main.setOrientation(LinearLayout.HORIZONTAL);
        main.addView(buildRail(), new LinearLayout.LayoutParams(dp(64), -1));
        content = new FrameLayout(this);
        main.addView(content, new LinearLayout.LayoutParams(0, -1, 1));
        frame.addView(main, new FrameLayout.LayoutParams(-1, -1));
        scrim = new View(this); scrim.setBackgroundColor(0xFF000000); scrim.setAlpha(0); scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(v -> drawer(false));
        frame.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        frame.addView(buildDrawer(), new FrameLayout.LayoutParams(dp(250), -1));
        setContentView(frame);

        buildHome(); buildTokens(); buildBuffs(); buildSequences(); buildNavigation();
        buildHive(); buildRedHive(); buildMusic(); buildStats(); buildPerf(); buildSettings();
        for (View pg : pages.values()) pg.setVisibility(View.GONE);
        frame.addView(buildMiniPlayer(), miniLp());
        String back = b == null ? "home" : b.getString("tab", "home");
        show(pages.containsKey(back) ? back : "home", false);

        if (b == null) addContentView(new SplashView(this, null), new ViewGroup.LayoutParams(-1, -1));   // every launch (not on rotation)
        if (!p.shizukuOffered && !shizukuInstalled()) {
            p.shizukuOffered = true; App.save(this);
            new android.app.AlertDialog.Builder(this).setTitle("Set up the key sender?")
                    .setMessage("Silly Macro's keyboard and mouse need a small helper started as the shell user. Shizuku (free, open source) "
                            + (android.os.Build.VERSION.SDK_INT >= 30
                               ? "lets this device start it by itself, without a PC. After installing: open Shizuku, start it with Wireless debugging, "
                               : "keeps it ready. Android 10 and older have no Wireless debugging, so after every restart start Shizuku once from a PC "
                                 + "(USB debugging, the command is in Shizuku's guide) or with root, ")
                            + "then press 'Start key sender on this device' (Settings tab) and allow Silly Macro.")
                    .setPositiveButton("Get Shizuku", (dl, w) -> getShizuku())
                    .setNegativeButton("Not now", null).show();
        }
        h.post(tick);
    }

    // ------------------------------------------------------------------ shell
    View buildRail() {
        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.VERTICAL); r.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Ui.RAIL); r.setBackground(bg);
        r.setPadding(0, dp(10), 0, dp(10));
        TextView menu = railButton("☰"); menu.setOnClickListener(v -> drawer(true)); r.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        View sep = new View(this); sep.setBackgroundColor(Ui.STROKE); LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(28), dp(1)); sl.setMargins(0, dp(6), 0, dp(6)); r.addView(sep, sl);
        ScrollView sv = new ScrollView(this); sv.setVerticalScrollBarEnabled(false);
        LinearLayout items = new LinearLayout(this); items.setOrientation(LinearLayout.VERTICAL); items.setGravity(Gravity.CENTER_HORIZONTAL);
        for (String[] t : TABS) {
            if (hidden(t[0])) continue;
            TextView it = railButton(t[1]); it.setContentDescription(t[2]);
            it.setOnClickListener(v -> show(t[0], true));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48)); lp.setMargins(0, dp(3), 0, dp(3));
            items.addView(it, lp); railItems.put(t[0], it);
        }
        sv.addView(items);
        r.addView(sv, new LinearLayout.LayoutParams(-2, 0, 1));
        railPulse = new Ui.Pulse(this); r.addView(railPulse, new LinearLayout.LayoutParams(dp(24), dp(24)));
        return r;
    }
    TextView railButton(String glyph) {
        TextView t = new TextView(this); t.setText(glyph); t.setTextSize(19); t.setGravity(Gravity.CENTER); t.setTextColor(TXT);
        return t;
    }

    View buildDrawer() {
        drawer = new LinearLayout(this); drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setPadding(dp(16), dp(20), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Ui.BG1);
        bg.setStroke(dp(1), Ui.STROKE); drawer.setBackground(bg);
        ImageView logo = new ImageView(this); logo.setImageResource(R.drawable.ic_launcher);
        drawer.addView(logo, new LinearLayout.LayoutParams(dp(72), dp(72)));
        TextView name = tv("Silly Macro", 22, TXT); name.setTypeface(Typeface.DEFAULT_BOLD); name.setPadding(0, dp(8), 0, 0); drawer.addView(name);
        drawer.addView(tv("Bee Swarm farming assistant", 12, DIM));
        View sep = new View(this); sep.setBackgroundColor(Ui.STROKE); LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, dp(1)); sl.setMargins(0, dp(14), 0, dp(8)); drawer.addView(sep, sl);
        ScrollView sv = new ScrollView(this);
        LinearLayout items = new LinearLayout(this); items.setOrientation(LinearLayout.VERTICAL);
        for (String[] t : TABS) {
            if (hidden(t[0])) continue;
            TextView it = tv(t[1] + "    " + t[2], 15, TXT); it.setGravity(Gravity.CENTER_VERTICAL); it.setPadding(dp(14), 0, dp(10), 0);
            it.setOnClickListener(v -> { show(t[0], true); drawer(false); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(46)); lp.setMargins(0, dp(2), 0, dp(2));
            items.addView(it, lp); drawerItems.put(t[0], it);
        }
        sv.addView(items);
        drawer.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        drawer.addView(tv("v2 · " + Profile.NAME, 11, Ui.FAINT));
        drawer.setTranslationX(-dp(260)); drawer.setVisibility(View.INVISIBLE);
        return drawer;
    }

    static final PathInterpolator DRAWER = new PathInterpolator(0.32f, 0.72f, 0f, 1f);
    boolean drawerOpen;
    void drawer(boolean open) {
        if (open == drawerOpen) return;
        drawerOpen = open;
        int w = dp(260);
        if (open) { drawer.setVisibility(View.VISIBLE); scrim.setVisibility(View.VISIBLE); }
        if (!Ui.motion()) { drawer.setTranslationX(open ? 0 : -w); scrim.setAlpha(open ? 0.55f : 0); if (!open) { drawer.setVisibility(View.INVISIBLE); scrim.setVisibility(View.GONE); } return; }
        drawer.animate().translationX(open ? 0 : -w).setDuration(open ? 260 : 180).setInterpolator(open ? DRAWER : Ui.EASE_OUT)
                .withEndAction(() -> { if (!drawerOpen) drawer.setVisibility(View.INVISIBLE); }).start();
        scrim.animate().alpha(open ? 0.55f : 0).setDuration(open ? 220 : 160).setInterpolator(Ui.EASE_OUT)
                .withEndAction(() -> { if (!drawerOpen) scrim.setVisibility(View.GONE); }).start();
    }
    @Override public void onBackPressed() { if (drawerOpen) drawer(false); else if (!currentKey.equals("home")) show("home", true); else super.onBackPressed(); }

    /** Switch tab: the old page fades out, the new one fades in rising 10 dp (220 ms ease-out). */
    void show(String key, boolean animate) {
        View next = pages.get(key); if (next == null) return;
        for (Map.Entry<String, TextView> e : railItems.entrySet()) {
            boolean on = e.getKey().equals(key);
            e.getValue().setBackground(on ? Ui.solid(this, Ui.alpha(PURPLE, Ui.light ? 0.14f : 0.20f), 16) : null);
            TextView d = drawerItems.get(e.getKey());
            d.setBackground(on ? Ui.solid(this, Ui.alpha(PURPLE, Ui.light ? 0.12f : 0.16f), 14) : null);
            d.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        if (next == current) return;
        View prev = current; current = next; currentKey = key;
        next.setVisibility(View.VISIBLE);
        if (animate && Ui.motion()) {
            next.setAlpha(0); next.setTranslationY(dp(10));
            next.animate().alpha(1).translationY(0).setDuration(220).setInterpolator(Ui.EASE_OUT).start();
            if (prev != null) prev.animate().alpha(0).setDuration(120).setInterpolator(Ui.EASE_OUT).withEndAction(() -> { if (prev != current) prev.setVisibility(View.GONE); prev.setAlpha(1); }).start();
        } else { next.setAlpha(1); next.setTranslationY(0); if (prev != null) prev.setVisibility(View.GONE); }
        if (key.equals("tokens")) { drawReview(); }
        if (key.equals("music")) drawMusic();
        refresh();
    }

    /** A scrolling page with its title; card() adds to it until the next page starts. */
    LinearLayout page(String key) {
        ScrollView sv = new ScrollView(this); sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(14), dp(14), dp(96));
        sv.addView(col);
        String[] t = null; for (String[] x : TABS) if (x[0].equals(key)) t = x;
        TextView title = tv(t[2], 28, TXT); title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); title.setLetterSpacing(-0.02f); col.addView(title);
        col.addView(tv(t[3], 13, DIM));
        content.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        pages.put(key, sv);
        root = col;
        return col;
    }

    // ------------------------------------------------------------------ Home dashboard
    void buildHome() {
        LinearLayout pg = page("home");
        // hero: logo, profile, live state
        LinearLayout hero = new LinearLayout(this); hero.setOrientation(LinearLayout.HORIZONTAL); hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(14), dp(14), dp(14), dp(14)); hero.setBackground(heroBg());
        ImageView logo = new ImageView(this); logo.setImageResource(R.drawable.ic_launcher); hero.addView(logo, new LinearLayout.LayoutParams(dp(58), dp(58)));
        LinearLayout ht = new LinearLayout(this); ht.setOrientation(LinearLayout.VERTICAL); ht.setPadding(dp(12), 0, 0, 0);
        TextView hn = tv("Silly Macro", 20, TXT); hn.setTypeface(Typeface.DEFAULT_BOLD); ht.addView(hn);
        heroSub = tv("", 13, DIM); ht.addView(heroSub);
        hero.addView(ht, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout st = row(); livePulse = new Ui.Pulse(this); st.addView(livePulse, new LinearLayout.LayoutParams(dp(18), dp(18)));
        heroState = tv("Idle", 12, TXT); heroState.setPadding(dp(4), 0, 0, 0); st.addView(heroState);
        hero.addView(st);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2); hl.topMargin = dp(12); pg.addView(hero, hl);

        // start + controls
        LinearLayout ctl = card("Control");
        FrameLayout sf = new FrameLayout(this);
        startLabel = tv("▶  Start", 19, Ui.ON_PRIMARY); startLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); startLabel.setGravity(Gravity.CENTER);
        startLabel.setBackground(Ui.solid(this, Ui.PRIMARY, 32));
        startLabel.setOnClickListener(v -> { press(v); startMacro(); });
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-1, dp(64)); sl.setMargins(dp(6), dp(6), dp(6), dp(6)); sf.addView(startLabel, sl);
        ctl.addView(sf, new LinearLayout.LayoutParams(-1, dp(76)));
        LinearLayout cr = row();
        cr.addView(button("Pause", 0, v -> { Engine e = Engine.inst; if (e != null && !e.finished() && !e.paused) e.pause("paused by you"); }), new LinearLayout.LayoutParams(0, dp(48), 1));
        cr.addView(button("Resume", 0, v -> { Engine e = Engine.inst; if (e != null && e.paused) e.resume(); }), new LinearLayout.LayoutParams(0, dp(48), 1));
        cr.addView(button("Stop", 0, v -> { Engine e = Engine.inst; if (e != null) e.stop("stopped by you"); if (MacroService.inst != null) MacroService.inst.emergencyRelease(); }), new LinearLayout.LayoutParams(0, dp(48), 1));
        for (int i = 0; i < cr.getChildCount(); i++) ((Button) cr.getChildAt(i)).setMaxLines(1);   // a third of a phone width: one line
        ctl.addView(cr);
        ctl.addView(tv("Start opens Roblox and begins after a few seconds. The floating panel in the game has the same controls and the emergency stop.", 11, DIM));

        // farming status + buffs (side by side on wide screens)
        LinearLayout status = card("Farming status");
        tField = kv(status, "📍 Field"); tPattern = kv(status, "🧩 Pattern"); tPhase = kv(status, "🐝 Doing");
        tBag = kv(status, "🎒 Backpack"); bagBar = new Ui.Bar(this);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(8)); bl.setMargins(0, dp(2), 0, dp(8)); status.addView(bagBar, bl);
        tHive = kv(status, "🍯 Hive"); tLoops = kv(status, "🔁 Loops"); tRate = kv(status, "📈 Honey rate");
        tTimer = tv("0:00", 34, TXT); tTimer.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL)); tTimer.setGravity(Gravity.CENTER);
        status.addView(tTimer); TextView tl = tv("session time", 11, DIM); tl.setGravity(Gravity.CENTER); status.addView(tl);
        LinearLayout buffs = card("Active buffs");
        HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false);
        buffChips = row(); hs.addView(buffChips); buffs.addView(hs);
        buffs.addView(tv("Read from the buff row while screen capture runs.", 11, DIM));
        pair(status, buffs);

        LinearLayout dec = card("AI decision");
        aiText = tv("", 13, TXT); aiText.setLineSpacing(0, 1.2f); dec.addView(aiText);

        // setup / ready check (unchanged steps)
        LinearLayout c = card("Setup & ready check");
        checks = tv("", 13, TXT); checks.setLineSpacing(0, 1.25f); c.addView(checks);
        // one numbered path for new players: each step is a button, the ✅/⚠️ list above shows what's done
        c.addView(button("1 · Turn on Silly Macro (Accessibility → Silly Macro → On)", PURPLE, v -> {
            if (MacroService.inst != null) { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return; }
            // apps installed from a file: Android greys the switch out ("Controlled by Restricted setting") until allowed
            new android.app.AlertDialog.Builder(this).setTitle("Turn on Silly Macro")
                    .setMessage("In the list, tap Silly Macro, switch it on and tap Allow.\n\n"
                            + "Switch greyed out (\"Controlled by Restricted setting\")? Tap App info below, then the ⋮ menu at the top right → "
                            + "Allow restricted settings, and come back here.")
                    .setPositiveButton("Open Accessibility", (d, w) -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                    .setNeutralButton("App info", (d, w) -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))))
                    .show();
        }));
        c.addView(button(shizukuInstalled() ? "2 · Start the keyboard + mouse (Shizuku must be running)" : "2 · Get Shizuku (it runs the keyboard + mouse)", PURPLE, v -> {
            if (!shizukuInstalled()) { getShizuku(); return; }
            Toast.makeText(this, "Starting the keyboard + mouse…", Toast.LENGTH_SHORT).show();
            new Thread(() -> { String m = ShizukuKeys.start(this); Log.add("shizuku: " + m); runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show()); }).start();
        }));
        c.addView(button("3 · Allow screen capture (pick \"Entire screen\")", PURPLE, v -> requestCapture()));
        c.addView(button("4 · Pick your hive slot (Hive tab)", PURPLE, v -> show("hive", true)));
        c.addView(button("5 · Open Bee Swarm (your private server)", BLUE, v -> {
            showPanel();   // the panel's ✕ hid it for good before (Discord: "panel isn't popping up")
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(p.joinLink())).setPackage("com.roblox.client")); }
            catch (Exception e) { toast("Roblox not installed"); }
        }));
        c.addView(tv("6 · In the game, hold your phone sideways and press ▶ Start on the floating panel. The app reads your screen by itself.", 12, DIM));
    }
    GradientDrawable heroBg() {
        return Ui.glass(this, 24, true);
    }
    TextView kv(LinearLayout parent, String key) {
        LinearLayout r = row(); r.setPadding(0, dp(3), 0, dp(3));
        r.addView(tv(key, 13, DIM), new LinearLayout.LayoutParams(0, -2, 1));
        TextView v = tv("—", 13, TXT); v.setTypeface(Typeface.DEFAULT_BOLD); v.setGravity(Gravity.END); r.addView(v, new LinearLayout.LayoutParams(0, -2, 1.4f));
        parent.addView(r); return v;
    }
    /** Two cards side by side on wide screens; stacked (as added) on phones. */
    void pair(View a, View b) {
        if (!wide) return;
        LinearLayout parent = (LinearLayout) a.getParent(); int at = parent.indexOfChild(a);
        parent.removeView(a); parent.removeView(b);
        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(0, -1, 1); la.setMargins(0, 0, dp(6), 0);
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(0, -1, 1); lb.setMargins(dp(6), 0, 0, 0);
        a.setLayoutParams(la); b.setLayoutParams(lb); r.addView(a); r.addView(b);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); rl.topMargin = dp(12);
        parent.addView(r, at, rl);
    }
    void press(View v) {   // button press feedback: quick scale dip (transform only)
        if (!Ui.motion()) return;
        v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).setInterpolator(Ui.EASE_OUT).withEndAction(() -> v.animate().scaleX(1).scaleY(1).setDuration(160).setInterpolator(Ui.EASE_OUT).start()).start();
    }

    /** Start from the app: bring Roblox to the front (its own launcher entry, which keeps the game running), then run
     *  the same preflight + engine the floating panel's Start uses. */
    String versionName() { try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; } }
    /** Bring the floating panel back (its ✕ turns it off; new players never found Settings → Show floating overlay). */
    void showPanel() {
        if (p.overlayVisible && MacroService.inst != null && MacroService.inst.overlayShown()) return;
        p.overlayVisible = true; App.save(this);
        if (overlaySwitch != null) overlaySwitch.setChecked(true);
        if (MacroService.inst != null) MacroService.inst.showOverlay(true);
    }
    void startMacro() {
        showPanel();
        if (MacroService.inst == null) { toast("Enable input first (Setup on Home)"); return; }
        Engine e = Engine.inst;
        if (e != null && !e.finished()) { toast(e.paused ? "Paused: press Resume" : "Already running"); return; }
        // Never send Roblox its launch intent while a game runs: when the game was joined from a link (Home step 5, a
        // share link), Roblox takes it as "go to the home page" and leaves the game, and the check 3.5 s later saw the
        // phone still upright ("Turn the device sideways"). S23 live 2026-11-23; Silly Admin report (S25+): 35 min of
        // "not in Bee Swarm gameplay" after pressing Start. Game seen lately: step aside so Android shows it again.
        // Roblox itself drops the game after about a minute behind another app (S23 live 2026-11-24: 15 s away kept it,
        // 90 s away came back on Roblox's home page with nothing sent to it), so "seen lately" means the last 45 s.
        boolean inGame = Monitor.gameSeen > 0 && SystemClock.uptimeMillis() - Monitor.gameSeen < 45_000L;
        if (inGame) moveTaskToBack(true);
        else if (!joinGame()) return;
        startWhenReady(inGame);
    }
    /** Opens the server link (private server, or a public Bee Swarm server) in Roblox. False if Roblox isn't installed. */
    boolean joinGame() {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(p.joinLink())).setPackage("com.roblox.client")); return true; }
        catch (Exception x) { toast("Roblox not installed"); return false; }
    }
    /** Waits for the game, then runs the same preflight + engine as the panel's Start. Ready = the Honey/Pollen counters
     *  read for 2 s in a row (a bright loading/join screen looked "playable" and Start found the Roblox menu still up,
     *  live); screens whose counters never read (no-HUD) start when the wait runs out, if the frame looks like the game. */
    void startWhenReady(boolean inGame) {
        toast(inGame ? "Back to Bee Swarm: starting in a few seconds" : "Joining Bee Swarm: Start begins once the game has loaded");
        new Thread(() -> {
            long until = SystemClock.uptimeMillis() + (inGame ? 10_000 : 120_000), hudSince = 0; boolean ready = false, joined = !inGame;
            while (!ready && SystemClock.uptimeMillis() < until) {
                SystemClock.sleep(400);
                Vision.Result r = Monitor.last; long now = SystemClock.uptimeMillis();
                boolean fresh = r != null && now - Monitor.lastTime < 1500;
                if (fresh && r.screen == Vision.Screen.GAME) { if (hudSince == 0) hudSince = now; ready = now - hudSince >= 2000; } else hudSince = 0;
                // stepped aside but Roblox shows its home page (portrait) after 4 s: the game was dropped, join it again
                if (!joined && now > until - 6_000 && fresh && r.w < r.h) { joined = true; Log.add("start: Roblox had dropped the game, joining the server again"); runOnUiThread(this::joinGame); until = now + 120_000; }
            }
            if (!ready) { Vision.Result r = Monitor.last; ready = r != null && SystemClock.uptimeMillis() - Monitor.lastTime < 1500 && Vision.playable(r, true); }
            MacroService s = MacroService.inst;
            String why = s == null ? "input service stopped" : !ready ? "Open Bee Swarm (sideways) and press ▶ Start on the floating panel." : Engine.start(s);
            if (why != null) { MacroService.startWhy = why; MacroService.startWhyAt = SystemClock.uptimeMillis(); runOnUiThread(() -> toast(why)); }
        }, "start").start();
    }


    // ------------------------------------------------------------------ Tokens
    void buildTokens() {
        page("tokens");
        LinearLayout ct = card("Token collection");
        if (Profile.AI_GATHERING) ct.addView(toggle("Collect labelled tokens while farming", p.collectTokens, on -> p.collectTokens = on));
        else ct.addView(tv("Token collection is switched off in this version (AI token gathering comes in a later update). Recognition and review still work.", 12, DIM));
        ct.addView(stepper("Priority: boost tokens", () -> "" + p.prioBoost, d -> p.prioBoost = clamp(p.prioBoost + d, 0, 5)));
        ct.addView(stepper("Priority: tokens", () -> "" + p.prioToken, d -> p.prioToken = clamp(p.prioToken + d, 0, 5)));
        ct.addView(stepper("Priority: loot", () -> "" + p.prioLoot, d -> p.prioLoot = clamp(p.prioLoot + d, 0, 5)));
        ct.addView(stepper("Priority: marks", () -> "" + p.prioMark, d -> p.prioMark = clamp(p.prioMark + d, 0, 5)));
        ct.addView(stepper("Max token distance", () -> Math.round(p.collectRange * 100) + "% of screen", d -> p.collectRange = Math.max(0.1f, Math.min(0.6f, Math.round((p.collectRange + d * 0.05f) * 100) / 100f))));
        ct.addView(stepper("Max walk per token", () -> p.maxCollectMs + " ms", d -> p.maxCollectMs = clamp(p.maxCollectMs + d * 250, 250, 4000)));
        LinearLayout rv = card("Review uncertain detections");
        rv.addView(tv("Objects the AI saw for ≥3 frames but couldn't name. Confirm the guess, give the right name, or mark a false detection. "
                + "Only what you confirm is ever added (labels.txt is backed up to labels.txt.bak before each change).", 12, DIM));
        reviewRows = new LinearLayout(this); reviewRows.setOrientation(LinearLayout.VERTICAL); rv.addView(reviewRows);
        rv.addView(button("Refresh review list", 0, v -> drawReview()));
        LinearLayout lb = card("Recognition database");
        labelsText = tv("", 12, TXT); lb.addView(labelsText);
        lb.addView(button("Delete a label…", 0, v -> {
            List<String> ls = App.library(this).labels();
            if (ls.isEmpty()) { toast("No labels yet"); return; }
            new android.app.AlertDialog.Builder(this).setTitle("Delete all examples of").setItems(ls.toArray(new String[0]), (dl, i) -> {
                App.library(this).remove(ls.get(i)); App.saveLibrary(this); Log.add("labels: deleted " + ls.get(i)); refresh();
            }).show();
        }));
        LinearLayout cb = card("Token & buff catalog");
        catText = tv("", 12, DIM); cb.addView(catText);
        android.widget.EditText q = new android.widget.EditText(this); q.setHint("Search (e.g. boost, haste, loot)"); q.setTextColor(TXT); q.setHintTextColor(DIM);
        cb.addView(q);
        catRows = new LinearLayout(this); catRows.setOrientation(LinearLayout.VERTICAL); cb.addView(catRows);
        q.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            public void onTextChanged(CharSequence c, int a, int b, int d) { drawCatalog(c.toString()); }
            public void afterTextChanged(android.text.Editable e) { }
        });
    }

    // ------------------------------------------------------------------ Buffs
    void buildBuffs() {
        page("buffs");
        LinearLayout bf = card("Active buffs & debuffs (live)");
        buffText = tv("", 12, TXT); bf.addView(buffText);
        LinearLayout bs = card("Buff recognition");
        bs.addView(toggle("Show buff-row boxes (Haste, Bear Morph…)", p.showBuffBoxes, on -> p.showBuffBoxes = on));
        bs.addView(stepper("Buff-row threshold", () -> String.format(Locale.US, "%.2f", p.buffMinConf), d -> p.buffMinConf = Math.max(0.5, Math.min(0.99, Math.round((p.buffMinConf + d * 0.05) * 100) / 100.0))));
        bs.addView(toggle("Haste correction for patterns (keeps their size)", p.hastePatterns, on -> p.hastePatterns = on));
        bs.addView(toggle("Haste correction for Skillet too", p.hasteAdjustSkillet, on -> p.hasteAdjustSkillet = on));
        bs.addView(tv("Also counted: Coconut Haste (+10), Bear Morph (+4), Oil ×1.2, Super Smoothie ×1.25. Tell it which permanent boosts you have:", 12, DIM));
        bs.addView(toggle("I wear the Hasty Guard", p.hastyGuard, on -> p.hastyGuard = on));
        bs.addView(toggle("I have a Gifted Hasty Bee", p.giftedHasty, on -> p.giftedHasty = on));
    }

    // ------------------------------------------------------------------ Sequences: field, pattern, sprinkler
    void buildSequences() {
        page("seq");
        LinearLayout fc = card("Field");
        Button fb = button("Field: " + Routes.name(p.field), PURPLE, null);
        fb.setOnClickListener(v -> { int i = Routes.index(p.field); p.field = Routes.FIELDS[(i + 1) % Routes.FIELDS.length][0]; App.save(this); fb.setText("Field: " + Routes.name(p.field)); });
        fc.addView(fb);
        fc.addView(tv("Walking routes are Natro Macro's (walk branch). Pine Tree uses the live-tuned route.", 12, DIM));
        LinearLayout pc = card("Farming pattern");
        String[] pats = new String[Patterns.NAMES.length + 1]; pats[0] = "Skillet"; System.arraycopy(Patterns.NAMES, 0, pats, 1, Patterns.NAMES.length);
        Button pb = button("Pattern: " + p.pattern, PURPLE, null);
        List<View> natroOnly = new ArrayList<>();   // size / width / invert: Natro patterns only (Skillet has fixed measurements)
        Runnable natroRows = () -> { for (View nv : natroOnly) nv.setVisibility(p.pattern.equals("Skillet") ? View.GONE : View.VISIBLE); };
        pb.setOnClickListener(v -> { int i = java.util.Arrays.asList(pats).indexOf(p.pattern); p.pattern = pats[(i + 1) % pats.length]; App.save(this); pb.setText("Pattern: " + p.pattern); natroRows.run(); });
        pc.addView(pb);
        Button zb = button("Pattern size: " + p.patternSize, 0, null);
        zb.setOnClickListener(v -> { int i = java.util.Arrays.asList(Patterns.SIZES).indexOf(p.patternSize); p.patternSize = Patterns.SIZES[(i + 1) % Patterns.SIZES.length]; App.save(this); zb.setText("Pattern size: " + p.patternSize); });
        natroOnly.add(zb);
        natroOnly.add(stepper("Pattern width", () -> "" + p.patternWidth, d -> p.patternWidth = clamp(p.patternWidth + d, 1, 9)));
        natroOnly.add(toggle("Invert pattern forward/back", p.patternInvertFB, on -> p.patternInvertFB = on));
        natroOnly.add(toggle("Invert pattern left/right", p.patternInvertLR, on -> p.patternInvertLR = on));
        for (View nv : natroOnly) pc.addView(nv);
        natroRows.run();
        pc.addView(stepper("Field camera turns (+right / −left)", () -> (p.fieldRotate > 0 ? "Right " : p.fieldRotate < 0 ? "Left " : "") + Math.abs(p.fieldRotate), d -> p.fieldRotate = clamp(p.fieldRotate + d, -4, 4)));
        pc.addView(toggle("Auto-click the tool while gathering", p.toolHold, on -> p.toolHold = on));
        pc.addView(stepper("Backpack threshold", () -> p.backpackThreshold + "%", d -> p.backpackThreshold = clamp(p.backpackThreshold + d * 5, 50, 100)));
        pc.addView(stepper("Max time in field", () -> p.fieldMinutes + " min", d -> p.fieldMinutes = clamp(p.fieldMinutes + d, 1, 60)));
        LinearLayout sc = card("Sprinkler");
        sc.addView(toggle("Learn my sprinkler on screen and stay near it (also used to find the field again)", p.sprinklerAnchor, on -> p.sprinklerAnchor = on));
        sc.addView(stepper("Sprinkler hotbar slot (key)", () -> "" + p.sprinklerSlot, d -> p.sprinklerSlot = clamp(p.sprinklerSlot + d, 1, 7)));
        sc.addView(stepper("Sprinkler distance", () -> "" + p.sprinklerDist, d -> p.sprinklerDist = clamp(p.sprinklerDist + d, 1, 10)));
        String[] locs = {"Center", "Upper Left", "Upper", "Upper Right", "Right", "Lower Right", "Lower", "Lower Left", "Left"};
        Button sl = button("Sprinkler spot: " + p.sprinklerLoc, 0, null);
        sl.setOnClickListener(v -> { int i = java.util.Arrays.asList(locs).indexOf(p.sprinklerLoc); p.sprinklerLoc = locs[(i + 1) % locs.length]; App.save(this); sl.setText("Sprinkler spot: " + p.sprinklerLoc); });
        sc.addView(sl);
        sc.addView(toggle("Corner spots: patterns line up in that corner every loop, like Skillet (Pine Tree: under the tree)", p.cornerAnchor, on -> p.cornerAnchor = on));
        sc.addView(toggle("Drift fix by sight: remember the view where farming starts, walk back to it after every loop", p.visualAnchor, on -> p.visualAnchor = on));
    }

    // ------------------------------------------------------------------ Navigation
    void buildNavigation() {
        page("nav");
        LinearLayout g = card("Screen guard & field edges");
        g.addView(toggle("Screen guard: find the way back, get unstuck, reset if lost", p.navGuard, on -> p.navGuard = on));
        g.addView(toggle("Keep inside the field (edge detection and recovery)", p.edgeCorrect, on -> p.edgeCorrect = on));
        g.addView(stepper("Vision min confidence", () -> Math.round(p.minConfidence * 100) + "%", d -> p.minConfidence = Math.max(0.5, Math.min(0.99, Math.round((p.minConfidence + d * 0.05) * 100) / 100.0))));
        g.addView(stepper("Player position on screen (y)", () -> Math.round(p.playerY * 100) + "%", d -> p.playerY = Math.max(0.3f, Math.min(0.8f, Math.round((p.playerY + d * 0.02f) * 100) / 100f))));
        g.addView(stepper("Field edge safety margin", () -> p.fieldMargin + " cells", d -> p.fieldMargin = Math.max(3, Math.min(10, p.fieldMargin + d))));
        g.addView(toggle("Show detected field edges on screen", p.showFieldMap, on -> p.showFieldMap = on));
        g.addView(toggle("Automatic recovery (respawn & retry)", p.recovery, on -> p.recovery = on));
        LinearLayout d = card("Drift correction");
        d.addView(toggle("Drift correction on your labelled landmark", p.driftCorrect, on -> p.driftCorrect = on));
        d.addView(stepper("Drift tolerance (sensitivity)", () -> Math.round(p.driftTolerance * 100) + "% of screen", x -> p.driftTolerance = Math.max(0.04f, Math.min(0.3f, Math.round((p.driftTolerance + x * 0.02f) * 100) / 100f))));
        d.addView(stepper("Max correction walk", () -> p.maxCorrectMs + " ms", x -> p.maxCorrectMs = clamp(p.maxCorrectMs + x * 200, 200, 3000)));
        LinearLayout m = card("Movement & camera");
        m.addView(tv("Input: Keyboard + mouse emulator (the key sender, started with Shizuku in the Settings tab)", 13, TXT));
        m.addView(stepper("Move speed (Natro MoveSpeedNum)", () -> String.format(Locale.US, "%.2f", p.moveSpeed), x -> p.moveSpeed = Math.round((p.moveSpeed + x * 0.05) * 100) / 100.0));
        m.addView(stepper("Movement timing scale", () -> String.format(Locale.US, "×%.2f", p.moveScale), x -> p.moveScale = Math.round((p.moveScale + x * 0.02) * 100) / 100.0));
        m.addView(stepper("Camera: swipe px per 45°", () -> p.pxPer45 + " px", x -> p.pxPer45 = clamp(p.pxPer45 + x * 5, 100, 900)));
        m.addView(stepper("Camera: tilt px per step", () -> p.tiltPx + " px", x -> p.tiltPx = clamp(p.tiltPx + x * 5, 10, 300)));
        m.addView(toggle("Camera reset by eye on this device (set automatically when needed)", p.camGuided, on -> p.camGuided = on));
        m.addView(tv("Roblox Settings → Camera Mode must be Classic and Shift Lock Switch On.", 12, DIM));
    }

    // ------------------------------------------------------------------ Hive
    void buildHive() {
        page("hive");
        LinearLayout hv = card("Your hive");
        hiveText = tv("", 15, TXT); hiveText.setTypeface(Typeface.DEFAULT_BOLD); hv.addView(hiveText);
        hv.addView(tv("Respawning always returns you to the hive you own, and the app checks the hive prompt each time. "
                + "The slot number only sets the walk distance to the ramp. Slot 3 is the hive straight ahead of spawn.", 12, DIM));
        LinearLayout hr = row();
        hr.addView(button("−", 0, v -> { p.hiveSlot = Math.max(1, p.hiveSlot - 1); p.hiveConfirmed = false; App.save(this); refresh(); }), new LinearLayout.LayoutParams(0, dp(48), 1));
        hr.addView(button("+", 0, v -> { p.hiveSlot = Math.min(6, p.hiveSlot + 1); p.hiveConfirmed = false; App.save(this); refresh(); }), new LinearLayout.LayoutParams(0, dp(48), 1));
        hr.addView(button("Confirm slot", PURPLE, v -> { p.hiveConfirmed = true; App.save(this); Log.add("hive: slot " + p.hiveSlot + " confirmed by user"); refresh(); }), new LinearLayout.LayoutParams(0, dp(48), 2));
        hv.addView(hr);
        hv.addView(toggle("If this hive is taken, use the nearest free one (3 first)", p.autoHiveSlot, on -> p.autoHiveSlot = on));
        // no-HUD mode (counters not read on this screen, or switched on in Settings): these two timers replace the bag reading
        hv.addView(stepper("Time in the field before converting", () -> p.fieldMinutes + " min", d -> p.fieldMinutes = clamp(p.fieldMinutes + d, 1, 60)));
        hv.addView(stepper("No-HUD mode: converting time at the hive", () -> p.noHudConvertSec + " s", d -> p.noHudConvertSec = clamp(p.noHudConvertSec + d * 15, 30, 900)));
        hv.addView(stepper("Your move speed (Bee Swarm stats)", () -> String.format(Locale.US, "%.2f", p.moveSpeed), x -> p.moveSpeed = Math.round((p.moveSpeed + x * 0.05) * 100) / 100.0));
        hv.addView(button("Type your move speed", 0, v -> {   // e.g. 29.9: the − / + steps can't land on every value
            android.widget.EditText et = new android.widget.EditText(this);
            et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            et.setText(String.format(Locale.US, "%.2f", p.moveSpeed)); et.setSelectAllOnFocus(true);
            new android.app.AlertDialog.Builder(this).setTitle("Move speed").setMessage("From your stats in Bee Swarm (e.g. 29.9)").setView(et)
                    .setPositiveButton("Save", (d, w) -> {
                        try {
                            double ms = Double.parseDouble(et.getText().toString().trim().replace(',', '.'));
                            if (ms < 10 || ms > 80) { toast("Move speed must be between 10 and 80"); return; }
                            p.moveSpeed = Math.round(ms * 100) / 100.0; p.moveSpeedTyped = true; App.save(this); refresh(); toast("Move speed set to " + p.moveSpeed);
                        } catch (NumberFormatException e) { toast("Type a number like 29.9"); }
                    }).setNegativeButton("Cancel", null).show();
        }));
        // here, not only on the advanced Sequences tab (Discord: "there was no sprinkler option in the app")
        LinearLayout sp = card("Sprinkler");
        Button st = button("Sprinkler: " + p.sprinklerType, 0, null);
        st.setOnClickListener(v -> { int i = java.util.Arrays.asList(Profile.SPRINKLERS).indexOf(p.sprinklerType); p.sprinklerType = Profile.SPRINKLERS[(i + 1) % Profile.SPRINKLERS.length]; App.save(this); st.setText("Sprinkler: " + p.sprinklerType); });
        sp.addView(st);
        sp.addView(stepper("Sprinkler hotbar slot (key)", () -> "" + p.sprinklerSlot, d -> p.sprinklerSlot = clamp(p.sprinklerSlot + d, 1, 7)));
        LinearLayout cv = card("Conversion");
        cv.addView(toggle("Automatic conversion", p.autoConvert, on -> p.autoConvert = on));
        cv.addView(toggle("Automatic return to field", p.autoReturn, on -> p.autoReturn = on));
        cv.addView(toggle("Return to hive by cannon + glider (off = respawn)", p.returnByCannon, on -> p.returnByCannon = on));
        String[] bm = {"Never", "Always", "Every"};
        Button bb = button("Convert hive balloon: " + p.balloonMode, 0, null);
        bb.setOnClickListener(v -> { int i = java.util.Arrays.asList(bm).indexOf(p.balloonMode); p.balloonMode = bm[(i + 1) % bm.length]; App.save(this); bb.setText("Convert hive balloon: " + p.balloonMode); });
        cv.addView(bb);
        cv.addView(stepper("Balloon: every", () -> p.balloonMins + " min", d -> p.balloonMins = clamp(p.balloonMins + d * 5, 5, 120)));
        LinearLayout mo = card("Mondo Chick");
        mo.addView(toggle("Mondo buff: stand by Mountain Top in minutes 0-13 of each hour", p.mondoBuff, on -> p.mondoBuff = on));
        mo.addView(stepper("Mondo buff wait", () -> p.mondoSecs + " s", d -> p.mondoSecs = clamp(p.mondoSecs + d * 10, 10, 300)));
    }

    // ------------------------------------------------------------------ Statistics
    void buildStats() {
        page("stats");
        LinearLayout tiles = card("Overall");
        LinearLayout r1 = row(), r2 = row();
        sTotal = tile(r1, "Honey made"); sBest = tile(r1, "Best honey / h");
        sTime = tile(r2, "Time macroing"); sCount = tile(r2, "Sessions");
        tiles.addView(r1); tiles.addView(r2);
        LinearLayout hc = card("Honey made per session");
        honeyChart = new Ui.Chart(this, false); hc.addView(honeyChart, new LinearLayout.LayoutParams(-1, dp(170)));
        LinearLayout rc = card("Honey per hour");
        rateChart = new Ui.Chart(this, true); rc.addView(rateChart, new LinearLayout.LayoutParams(-1, dp(150)));
        pair(hc, rc);
        LinearLayout hs = card("Session history");
        history = tv("", 12, TXT); hs.addView(history);
        hs.addView(toggle("Save session statistics", p.stats, on -> p.stats = on));
    }
    TextView tile(LinearLayout row, String label) {
        LinearLayout t = new LinearLayout(this); t.setOrientation(LinearLayout.VERTICAL); t.setPadding(dp(12), dp(10), dp(12), dp(10));
        t.setBackground(Ui.glass(this, 14, true));
        TextView v = tv("—", 20, TXT); v.setTypeface(Typeface.DEFAULT_BOLD); t.addView(v); t.addView(tv(label, 11, DIM));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        row.addView(t, lp); return v;
    }

    // ------------------------------------------------------------------ Performance
    void buildPerf() {
        page("perf");
        LinearLayout tl = card("Right now");
        LinearLayout r1 = row(), r2 = row();
        pFps = tile(r1, "Vision fps"); pFrame = tile(r1, "ms per frame"); pTemp = tile(r2, "Device temperature"); pBatt = tile(r2, "Battery");
        LinearLayout r3 = row(); pRam = tile(r3, "App memory"); tile(r3, "Screenshots stay on device").setText("🔒");
        tl.addView(r1); tl.addView(r2); tl.addView(r3);
        LinearLayout fc = card("Vision fps (last minute)"); fpsChart = new Ui.Chart(this, true); fc.addView(fpsChart, new LinearLayout.LayoutParams(-1, dp(120)));
        LinearLayout tc = card("Temperature °C (last minute)"); tempChart = new Ui.Chart(this, true); tc.addView(tempChart, new LinearLayout.LayoutParams(-1, dp(120)));
        pair(fc, tc);
        LinearLayout s = card("Speed vs battery");
        s.addView(stepper("Detection rate (battery!)", () -> p.detectFps + " fps", d -> p.detectFps = clamp(p.detectFps + d, 1, 15)));
        s.addView(toggle("Battery saver (1 fps vision while idle)", p.batterySaver, on -> p.batterySaver = on));
        s.addView(toggle("Keep Samsung's game touch lock away (harmless key when idle 45 s)", p.keepAlive, on -> p.keepAlive = on));
        s.addView(toggle("Show temperature (°C / °F) and battery under the overlay", p.showTemp, on -> p.showTemp = on));
        LinearLayout ai = card("AI");
        ai.addView(toggle("Field test tour on next Start: every field with Natro's pattern, " + p.tourMinutes + " min each, then your field comes back", p.fieldTour, on -> p.fieldTour = on));
        ai.addView(toggle("Never pause by itself (retries, resets or rejoins instead; your Pause button still works)", p.noAutoPause, on -> p.noAutoPause = on));
        ai.addView(toggle("Learn the best pattern (tries " + p.aiPatterns.replace(",", ", ") + "; keeps the fastest bag fill)", p.aiPatternLearn, on -> p.aiPatternLearn = on));
    }

    // ------------------------------------------------------------------ Settings

    /** Private server link field + Save / Open it + state (Settings and the setup wizard). */
    void serverLinkEditor(LinearLayout into) {
        final TextView psState = tv("", 12, DIM);
        android.widget.EditText link = new android.widget.EditText(this);
        link.setHint("https://www.roblox.com/share?code=…"); link.setTextColor(TXT); link.setHintTextColor(Ui.FAINT); link.setSingleLine(true);
        link.setText(p.serverLinkDraft.isEmpty() ? p.serverLink : p.serverLinkDraft);
        into.addView(link);
        psState.setText(KeyServer.linkOk(p.serverLink) ? "Server link saved ✔" : "No private server link: Open Bee Swarm uses a public server"); psState.setTextColor(KeyServer.linkOk(p.serverLink) ? Ui.GREEN : Ui.GOLD);
        // saved as you type (Discord: the link was lost after switching to Roblox to copy it, unless Save was pressed):
        // a valid link at once, anything else as a draft that comes back in this field
        link.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            public void onTextChanged(CharSequence c, int a, int b, int d) { }
            public void afterTextChanged(android.text.Editable e) {
                String raw = e.toString().trim(), n = Profile.normLink(raw);
                p.serverLinkDraft = raw;
                if (raw.isEmpty()) { p.serverLink = ""; psState.setText("No private server link: Open Bee Swarm uses a public server"); psState.setTextColor(Ui.GOLD); }
                else if (KeyServer.linkOk(n)) { p.serverLink = n; p.serverLinkDraft = ""; psState.setText("Server link saved ✔"); psState.setTextColor(Ui.GREEN); }
                else { psState.setText("Not a Roblox server link yet (kept as you typed it)"); psState.setTextColor(Ui.GOLD); }
                App.save(MainActivity.this);
            }
        });
        LinearLayout pr = row();
        pr.addView(button("Save link", PURPLE, v -> {
            String u = Profile.normLink(link.getText().toString().trim());
            if (!KeyServer.linkOk(u)) { toast("That isn't a Roblox server link (https://www.roblox.com/… or roblox://…)"); return; }
            p.serverLink = u; p.serverLinkDraft = ""; App.save(this); link.setText(u);
            psState.setText("Server link saved ✔"); psState.setTextColor(Ui.GREEN);
            Log.add("settings: private server link changed");
        }), new LinearLayout.LayoutParams(0, dp(48), 1));
        pr.addView(button("Open it", BLUE, v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(p.joinLink())).setPackage("com.roblox.client")); }
            catch (Exception e) { toast("Roblox not installed"); }
        }), new LinearLayout.LayoutParams(0, dp(48), 1));
        into.addView(pr);
        into.addView(psState);
    }
    /** Discord webhook field + Paste / Save / Send test + state (Settings and the setup wizard). */
    void webhookEditor(LinearLayout into) {
        final TextView whState = tv("", 12, DIM);
        android.widget.EditText url = new android.widget.EditText(this);
        url.setHint("https://discord.com/api/webhooks/…"); url.setTextColor(TXT); url.setHintTextColor(Ui.FAINT); url.setSingleLine(true);
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);   // it is a secret: hidden like a password
        url.setText(p.webhookDraft.isEmpty() ? p.webhookUrl : p.webhookDraft);
        into.addView(url);
        whState.setText(Webhook.valid(p.webhookUrl) ? "Webhook saved ✔" : "No webhook yet"); whState.setTextColor(Webhook.valid(p.webhookUrl) ? Ui.GREEN : DIM);
        url.addTextChangedListener(new android.text.TextWatcher() {   // saved as you type, like the server link
            public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            public void onTextChanged(CharSequence c, int a, int b, int d) { }
            public void afterTextChanged(android.text.Editable e) {
                String u = e.toString().trim();
                p.webhookDraft = u;
                if (u.isEmpty()) { p.webhookUrl = ""; whState.setText("No webhook yet"); whState.setTextColor(DIM); }
                else if (Webhook.valid(u)) { p.webhookUrl = u; p.webhookDraft = ""; whState.setText("Webhook saved ✔"); whState.setTextColor(Ui.GREEN); }
                else { whState.setText("Not a Discord webhook URL yet (kept as you typed it)"); whState.setTextColor(Ui.GOLD); }
                App.save(MainActivity.this);
            }
        });
        LinearLayout ub = row();
        // a password field opens Samsung's secure keyboard, which can't paste (Discord): read the clipboard ourselves
        ub.addView(button("Paste", 0, v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            android.content.ClipData cd = cm == null ? null : cm.getPrimaryClip();
            CharSequence t = cd == null || cd.getItemCount() == 0 ? null : cd.getItemAt(0).coerceToText(this);
            if (t == null || t.toString().trim().isEmpty()) { toast("Copy the webhook URL in Discord first"); return; }
            url.setText(t.toString().trim()); toast(Webhook.valid(t.toString().trim()) ? "Pasted and saved" : "Pasted, but that isn't a Discord webhook URL");
        }), new LinearLayout.LayoutParams(0, dp(48), 1));
        ub.addView(button("Save", PURPLE, v -> {
            String u = url.getText().toString().trim();
            if (!u.isEmpty() && !Webhook.valid(u)) { toast("That isn't a Discord webhook URL"); return; }
            p.webhookUrl = u; p.webhookDraft = ""; App.save(this);
            whState.setText(u.isEmpty() ? "Webhook removed" : "Webhook saved ✔"); whState.setTextColor(u.isEmpty() ? DIM : Ui.GREEN);
        }), new LinearLayout.LayoutParams(0, dp(48), 1));
        ub.addView(button("Send test", BLUE, v -> {
            if (!Webhook.valid(p.webhookUrl)) { toast("Save a webhook URL first"); return; }
            Webhook.send(this, "test", "👋 Silly Macro test", "Webhook works. " + Routes.name(p.field) + " · " + p.pattern
                    + (Monitor.honey > 0 ? "\nHoney: " + Stats.big(Monitor.honey) : ""), Webhook.PURPLE, true);
            toast("Sending… (screenshot included if screen capture is on)");
        }), new LinearLayout.LayoutParams(0, dp(48), 1));
        into.addView(ub);
        into.addView(whState);
    }

    /** Screen reading (Home step 3 and the setup wizard). No MediaProjection consent: frames come from the key sender
     *  (FrameServer, shell user via Shizuku), which this starts if it isn't running. */
    void requestCapture() {
            if (CaptureService.inst == null) {
                startForegroundService(new Intent(this, CaptureService.class));
                new Thread(() -> { if (!Keys.connect(this, false) && ShizukuKeys.ready()) Log.add("capture: starting the key sender for screen frames: " + ShizukuKeys.start(this)); }, "frames-start").start();
                toast("Screen reading on (through the key sender, nothing is recorded)");
            }
            else toast("Screen reading already running");
            }

    void buildSettings() {
        page("settings");
        buildAppearance();
        LinearLayout o = card("Floating overlay");
        overlaySwitch = (Switch) toggle("Show floating overlay", p.overlayVisible, on -> { p.overlayVisible = on; if (MacroService.inst != null) MacroService.inst.showOverlay(on); });
        o.addView(overlaySwitch);
        o.addView(toggle("Show advanced tabs (tokens, buffs, patterns, navigation, performance)", p.advanced, on -> { p.advanced = on; App.save(this); recreate(); }));
        o.addView(stepper("Overlay width", () -> p.overlayWidthDp + " dp", d -> p.overlayWidthDp = clamp(p.overlayWidthDp + d * 10, 170, 230)));
        o.addView(tv("Overlay transparency", 13, DIM));
        SeekBar sb = new SeekBar(this); sb.setMax(70); sb.setProgress(Math.round(p.overlayAlpha * 100) - 30);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean u) { p.overlayAlpha = (v + 30) / 100f; if (MacroService.inst != null) MacroService.inst.setOverlayAlpha(p.overlayAlpha); }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { App.save(MainActivity.this); }
        });
        o.addView(sb);
        boolean tabW = ScreenWidth.tablet(this); int tgt = ScreenWidth.target(this);
        LinearLayout swc = card(tabW ? "Smallest width (tablets: recommended)" : "Smallest width (required)");
        TextView swt = tv("", 12, DIM); swc.addView(swt);
        Runnable swShow = () -> { int now = ScreenWidth.current(this); boolean ok = now == tgt;
            swt.setText((tabW ? "Tablets read best at the Galaxy Tab S9 FE's Smallest width, " + tgt + " dp (2304 × 1440 at 280 dpi), the tablet the macro was measured on."
                              : "Phones must use Android's Smallest width 360 dp (Developer options; Samsung: Minimum width), or Roblox draws a HUD the macro can't read.")
                    + " Now: " + now + " dp" + (ok ? " ✓" : (tabW ? " ⚠ differs" : "") + ". Set it here (uses the key sender), then close and reopen Roblox." + (tabW ? "" : " Start also sets it for you.")));
            swt.setTextColor(ok ? Ui.GREEN : tabW ? Ui.GOLD : DIM); };
        swShow.run();
        swc.addView(button("Set to " + tgt, PURPLE, v ->
            new Thread(() -> { String why = ScreenWidth.apply(this); runOnUiThread(() -> { toast(why != null ? why : "Set to " + tgt + ". Close and reopen Roblox."); swShow.run(); }); }).start()));
        swc.addView(button("Test my resolution", 0, v -> {
            String r = ScreenWidth.report(this);
            new android.app.AlertDialog.Builder(this).setTitle("Your screen").setMessage(r + "\n\nOpen Bee Swarm sideways for a few seconds and test again to see the HUD measured.")
                    .setPositiveButton("OK", null).show();
        }));
        swc.addView(button("Open Developer options", 0, v -> {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); } catch (Exception e) { toast("Turn on Developer options first (Build number ×7)"); }
        }));
        swc.addView(toggle(ScreenWidth.reference(this) + " — the screen the macro was built on", p.s23Res, on -> {
            boolean was = p.s23Res; p.s23Res = on; App.save(this);   // before: the size change recreates this screen
            new Thread(() -> {
                String why = ScreenWidth.s23(this, on);
                if (why != null) { p.s23Res = was; App.save(this); }
                runOnUiThread(() -> toast(why != null ? why : on ? (tabW ? "S9 FE" : "S23") + " resolution on. Close and reopen Roblox." : "Back to your own resolution (Smallest width " + tgt + "). Close and reopen Roblox."));
            }).start();
        }));
        swc.addView(tv("Changes the whole " + (tabW ? "tablet" : "phone") + "'s resolution until you switch it off (the screen may look a little sharper or softer). Turn it off before uninstalling Silly Macro, or reset it in Developer options.", 11, DIM));
        LinearLayout su = card("Setup");
        su.addView(tv("Walks you through Shizuku, Accessibility, screen capture, hive slot, move speed, sprinkler, Roblox settings, webhook and private server again.", 12, DIM));
        su.addView(button("Run setup again", PURPLE, v -> {
            Engine e = Engine.inst; if (e != null && !e.finished()) { toast("Stop the macro first"); return; }
            p.setupDone = false; p.setupStep = 0; App.save(this); recreate();
        }));
        LinearLayout dv = card("Your device");
        dv.addView(tv(deviceSummary(), 13, TXT));
        dv.addView(tv("Silly Macro reads Bee Swarm's HUD the way this screen draws it: it finds the Honey and Pollen "
                + "counters at the top and measures the scale, centre and camera-cutout inset by itself the first time you're in game.", 12, DIM));
        hudState = tv("", 13, Ui.CYAN); dv.addView(hudState);
        dv.addView(toggle("No-HUD mode (convert on a timer)", p.noHudForced, on -> p.noHudForced = on));
        dv.addView(tv("Off: used by itself only when the Honey/Pollen counters can't be read. On: always farms for the field time (Hive tab), then converts for the set time, without reading the bag.", 12, DIM));
        dv.addView(button("Re-measure HUD (after changing Roblox's UI or screen settings)", 0, v -> { Monitor.recalibrate(); toast("Open Bee Swarm in landscape for a few seconds"); }));
        String tips = brandTips(android.os.Build.MANUFACTURER);
        if (tips != null) {   // Redmi Note 13, Honor 90 in #whats-your-android: these systems stop background apps and keys
            LinearLayout bt = card("Your " + android.os.Build.MANUFACTURER + " phone needs these switches");
            bt.addView(tv(tips, 13, TXT));
            bt.addView(button("Open Silly Macro's app settings", 0, v -> startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())))));
        }
        if (Account.configured(this)) {
            LinearLayout ac = card("Account");
            ac.addView(tv("Signed in as " + Account.email(this), 13, TXT));
            ac.addView(button("Sign out", 0, v -> {
                Engine e = Engine.inst; if (e != null && !e.finished()) { toast("Stop the macro first"); return; }
                Account.signOut(this); recreate();
            }));
        }
        LinearLayout ps = card("Private server");
        ps.addView(tv("Optional. Without a link, \"Open Bee Swarm\" opens the normal game (a public server works too). With one, it and the overnight rejoin go to your private server. Paste its share link from Roblox "
                + "(Server → Private Servers → … → Copy Link), e.g. https://www.roblox.com/share?code=…&type=Server", 12, DIM));
        serverLinkEditor(ps);
        LinearLayout dc = card("Discord webhook");
        dc.addView(tv("Reports with screenshots to your Discord channel, like Natro: started, backpack full, converting, honey made, balloon, "
                + "Mondo, resets / rejoins, a timed report (every 10 min to 2 h) and the session summary. In Discord: channel → Edit → Integrations → Webhooks → New → Copy URL. "
                + "Screenshots go only to that webhook.", 12, DIM));
        webhookEditor(dc);
        whStatus = tv("", 11, DIM); dc.addView(whStatus);
        dc.addView(toggle("Include screenshots", p.whScreens, on -> p.whScreens = on));
        dc.addView(toggle("Converting & honey made", p.whConvert, on -> p.whConvert = on));
        dc.addView(toggle("Field: backpack full, Mondo", p.whField, on -> p.whField = on));
        dc.addView(toggle("Problems: pauses, resets, rejoins", p.whProblems, on -> p.whProblems = on));
        dc.addView(toggle("Timed report (honey made, rate, screenshot)", p.whHourly, on -> p.whHourly = on));
        dc.addView(stepper("Send it every", () -> p.whEveryMins + " min", x -> {
            int[] ch = {10, 20, 30, 60, 120}; int i = 0;
            while (i < ch.length - 1 && ch[i] < p.whEveryMins) i++;
            p.whEveryMins = ch[Math.max(0, Math.min(ch.length - 1, i + x))];
        }));
        android.widget.EditText ping = new android.widget.EditText(this);
        ping.setHint("Your Discord user ID to ping on problems (optional)"); ping.setTextColor(TXT); ping.setHintTextColor(Ui.FAINT); ping.setSingleLine(true);
        ping.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); ping.setText(p.discordPingId);
        ping.setOnFocusChangeListener((v, has) -> { if (!has) { p.discordPingId = ping.getText().toString().trim(); App.save(this); } });
        dc.addView(ping);
        LinearLayout n = card("Overnight");
        n.addView(toggle("Overnight mode: auto rejoin, auto hive claim, never pause", p.overnight, on -> p.overnight = on));
        n.addView(tv("For long runs, let Silly Macro run without battery limits (Samsung: Battery → Unrestricted), so Android doesn't slow it down in the background.", 12, DIM));
        n.addView(button("Battery settings", 0, v -> {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
            catch (Exception e) { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
        }));
        LinearLayout k = card("Key sender (keyboard + mouse)");
        if (!shizukuInstalled()) k.addView(button("Get Shizuku (for the key sender without a PC)", 0, v -> getShizuku()));
        k.addView(button("Start key sender on this device (Shizuku, no PC)", PURPLE, v -> {
            Toast.makeText(this, "Starting key sender…", Toast.LENGTH_SHORT).show();
            new Thread(() -> { String m = ShizukuKeys.start(this); Log.add("shizuku: " + m); runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show()); }).start();
        }));
        k.addView(button("Stop screen capture", 0, v -> {
            if (CaptureService.inst == null) { toast("Screen capture is not running"); return; }
            CaptureService.stopCapture(this); toast("Screen capture stopped");
        }));
        LinearLayout dbg = card("Debug");
        dbg.addView(toggle("Debug mode (show the log below)", p.debug, on -> { p.debug = on; log.setVisibility(on ? View.VISIBLE : View.GONE); }));
        log = tv("", 11, DIM); log.setTypeface(Typeface.MONOSPACE); log.setVisibility(p.debug ? View.VISIBLE : View.GONE);
        dbg.addView(log);
        dbg.addView(button("Share log (paste it in #bug-reports)", 0, v -> {   // remote problems can't be fixed without it
            String text = "Silly Macro " + versionName() + "\n" + deviceSummary() + "\nHUD: " + Monitor.calState + "\n\n" + Log.dump(300);
            text = text.replaceAll("[\\w.+-]+@[\\w-]+\\.[\\w.]+", "[email hidden]");   // never share the account email
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share Silly Macro log"));
        }));
        if (Account.configured(this)) dbg.addView(button("Send report to the developer (log + counter screenshot)", PURPLE, v -> sendReport()));
        dbg.addView(tv(Profile.NAME + " · " + Profile.PRESET, 11, Ui.FAINT));
    }

    /** Shows exactly what goes out (the Honey/Pollen counter strip + the log) and sends it only on "Send". */
    void sendReport() {
        if (!Account.signedIn(this)) { toast("Sign in to Silly Macro first"); return; }
        String log = reportLog(this);
        int[] shot = Monitor.hudShot; int sw = Monitor.hudShotW, sh = Monitor.hudShotH;
        android.graphics.Bitmap bm = shot == null ? null : android.graphics.Bitmap.createBitmap(shot, sw, sh, android.graphics.Bitmap.Config.ARGB_8888);
        byte[] jpeg = bm == null ? null : jpeg(bm);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(tv("Only these two things are sent, with your account id, app version and phone model:", 13, TXT));
        if (bm != null && jpeg != null) {
            android.widget.ImageView iv = new android.widget.ImageView(this); iv.setImageBitmap(bm); iv.setAdjustViewBounds(true);
            box.addView(iv, new LinearLayout.LayoutParams(-1, -2));
            long age = (android.os.SystemClock.uptimeMillis() - Monitor.hudShotTime) / 1000;
            box.addView(tv("1. This screenshot of the Honey/Pollen counters (" + jpeg.length / 1024 + " KB, from " + (age < 60 ? age + " s" : age / 60 + " min") + " ago)"
                    + (Monitor.hudShotGame ? "" : "\n⚠ Taken before the app recognised Bee Swarm: it may not show the counters. For a better one, use Send report on the floating panel while in the game."), 12, Monitor.hudShotGame ? DIM : Ui.GOLD));
        } else box.addView(tv("1. No counter screenshot (screen capture hasn't seen the game yet: allow it in Home step 3, open Bee Swarm sideways for a few seconds, then come back)", 12, Ui.GOLD));
        box.addView(tv("2. Your debug log, " + Report.lines(log) + " lines (emails hidden)", 12, DIM));
        final byte[] img = jpeg;
        new android.app.AlertDialog.Builder(this).setTitle("Send report to the developer?").setView(box)
                .setPositiveButton("Send", (d, w) -> {
                    toast("Sending…");
                    new Thread(() -> { String err = Account.sendReport(this, log, img); runOnUiThread(() -> toast(err == null ? "Report sent. Thanks!" : "Not sent: " + err)); }).start();
                })
                .setNegativeButton("Cancel", null).show();
    }
    /** JPEG at most Report.MAX_IMAGE_BYTES: lower quality first, then smaller. */
    static byte[] jpeg(android.graphics.Bitmap bm) {
        android.graphics.Bitmap b = bm;
        for (int i = 0; i < 8; i++) {
            for (int q : new int[]{80, 60, 40}) {
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                b.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, o);
                if (o.size() <= Report.MAX_IMAGE_BYTES) return o.toByteArray();
            }
            b = android.graphics.Bitmap.createScaledBitmap(b, Math.max(1, b.getWidth() * 7 / 10), Math.max(1, b.getHeight() * 7 / 10), true);
        }
        return null;
    }

    /** What each phone maker's system blocks, in its own menu names; null = nothing extra (Samsung, Pixel, Motorola). */
    static String brandTips(String maker) {
        String m = maker == null ? "" : maker.toLowerCase(java.util.Locale.US);
        String both = "Do this for Silly Macro AND Shizuku:\n";
        if (m.contains("xiaomi") || m.contains("redmi") || m.contains("poco"))
            return both + "• Autostart: ON\n• Battery saver: No restrictions\n• Other permissions → Display pop-up windows while running in the background: ON (the floating panel)\n"
                    + "• Developer options → USB debugging (Security settings): ON. Without it HyperOS/MIUI blocks the keys. It asks for a Mi account and a SIM card once";
        if (m.contains("honor") || m.contains("huawei"))
            return both + "• Battery → App launch: turn off \"Manage automatically\", then turn ON Auto-launch, Secondary launch and Run in background\n"
                    + "• Battery: turn off Power saving mode while macroing";
        if (m.contains("oppo") || m.contains("realme") || m.contains("oneplus"))
            return both + "• Battery → Allow background activity: ON (Battery optimisation: Don't optimise)\n• Auto launch / Allow auto startup: ON";
        if (m.contains("vivo") || m.contains("iqoo"))
            return both + "• Battery → Background power consumption: Allow\n• Autostart: ON";
        return null;
    }

    final Runnable tick = new Runnable() { @Override public void run() { refresh(); h.postDelayed(this, 1000); } };

    // Music only plays outside the app during a run (if allowed): leaving the app pauses it otherwise
    @Override protected void onStart() { super.onStart(); Music.appVisible = true; }
    @Override protected void onStop() { super.onStop(); if (!isChangingConfigurations()) Music.appHidden(this); }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putString("tab", currentKey); }
    @Override protected void onDestroy() { h.removeCallbacks(tick); if (musicListener != null) Music.get(this).unlisten(musicListener); super.onDestroy(); }
    @Override protected void onResume() { super.onResume(); if (content == null) return; drawReview(); drawCatalog(""); }

    @Override protected void onActivityResult(int req, int code, Intent data) {
        if (req == REQ_AUDIO) { if (code == RESULT_OK && data != null) importAudio(data); return; }
    }

    private long emuAsked;
    void refresh() {
        if (mTitle != null && currentKey.equals("music")) drawMusicState(); else updateMini();
        boolean input = MacroService.inst != null, cap = CaptureService.inst != null;
        Vision.Result v = Monitor.last;
        StringBuilder s = new StringBuilder();
        if (p.keyboard()) {
            String emu = Keys.emulator;
            line(s, emu != null && !emu.startsWith("none") && !emu.equals("starting"), "Input: keyboard + mouse emulator — " + (emu == null ? "key sender not reached yet" : emu.equals("starting") ? "starting…" : emu.startsWith("none") ? "NOT available (" + emu + ")" : "ready (" + emu + ")"));
            if (SystemClock.uptimeMillis() - emuAsked > 5000) { emuAsked = SystemClock.uptimeMillis(); new Thread(() -> { if (Keys.connect(this)) Keys.emulatorState(); }, "emu-state").start(); }
        }
        line(s, input, "Accessibility service (floating panel and menu taps)");
        line(s, cap, "Screen reading (key sender frames, no recording — " + ShellFrames.status + ")");
        line(s, p.hiveConfirmed, "Hive slot " + p.hiveSlot + (p.hiveConfirmed ? " confirmed" : " — confirm it once (Hive tab)"));
        boolean panel = input && MacroService.inst.overlayShown();
        line(s, panel, panel ? "Floating panel on screen (Start, Stop, emergency stop)"
                : "Floating panel hidden: tap step 5 below, or Settings → Show floating overlay");
        // Required: Developer options → "Smallest width" 360 on phones (Roblox draws a HUD the macro can't read otherwise)
        if (!ScreenWidth.tablet(this)) { int sw = ScreenWidth.current(this);
            line(s, sw == ScreenWidth.TARGET, "Smallest width " + sw + " dp" + (sw == ScreenWidth.TARGET ? " (required 360)" : ": must be 360. Settings → Smallest width → Set to 360, or Start sets it for you")); }
        else { int sw = ScreenWidth.current(this), tw = ScreenWidth.target(this);   // tablets: the Tab S9 FE's width, recommended
            line(s, sw == tw, "Smallest width " + sw + " dp" + (sw == tw ? " (Tab S9 FE " + tw + ")" : ": recommended " + tw + " (Tab S9 FE). Settings → Smallest width → Set to " + tw)); }
        boolean game = v != null && v.screen == Vision.Screen.GAME && v.screenConf >= p.minConfidence;
        line(s, game, "Vision: " + (v == null ? "no frames" : game ? "Bee Swarm HUD recognized" : "waiting for Bee Swarm (now: " + v.screen + ")"));
        checks.setText(s.toString().trim());
        if (overlaySwitch.isChecked() != p.overlayVisible) overlaySwitch.setChecked(p.overlayVisible);  // hidden from the panel's ✕
        hiveText.setText("Slot " + p.hiveSlot + (p.hiveConfirmed ? "  ✔ confirmed" : "  — tap Confirm once"));
        refreshHome(v);
        if (currentKey.equals("stats")) refreshStats();
        refreshPerf();
        if (currentKey.equals("settings") && p.debug) log.setText(Log.dump(60));
        if (currentKey.equals("settings")) whStatus.setText("Last: " + Webhook.lastStatus);
        if (currentKey.equals("settings") && hudState != null) hudState.setText("HUD layout: " + Monitor.calState);
        if (currentKey.equals("tokens")) {
            Detector.Library lib = App.library(this);
            StringBuilder L = new StringBuilder();
            for (String l : lib.labels()) L.append(l).append(" — ").append(lib.count(l)).append(" example(s)\n");
            labelsText.setText(L.length() == 0 ? "No labels yet: every detection shows as Unknown." : L.toString().trim());
        }
        if (currentKey.equals("buffs")) {
            StringBuilder B = new StringBuilder();
            for (Buffs.Effect e : Monitor.activeBuffs())
                B.append(String.format(Locale.US, "%s %s — active%s — on %s — %.2f\n", e.debuff ? "🔴" : "🟣", e.name, e.stack == null ? "" : " — " + e.stack,
                        Stats.fmt(SystemClock.uptimeMillis() - e.since), e.conf));
            B.append(B.length() == 0 ? "None recognised right now.\n" : "").append("Remaining time: not shown by the game on buff icons.\n\nTimeline (newest first):\n");
            Buffs bb = Monitor.buffs;
            if (bb != null) synchronized (bb) { int i = 0; for (String t : bb.timeline) { if (i++ >= 20) break; B.append(t).append('\n'); } }
            buffText.setText(B.toString().trim());
        }
    }

    void refreshHome(Vision.Result v) {
        Engine e = Engine.inst;
        boolean running = e != null && !e.finished();
        heroSub.setText(Routes.name(p.field) + "  ·  " + p.pattern + (p.pattern.equals("Skillet") ? "" : " " + p.patternSize + " ×" + p.patternWidth));
        heroState.setText(running ? (e.paused ? "Paused" : "Farming") : CaptureService.inst != null ? "Ready" : "Idle");
        int col = running ? (e.paused ? Ui.GOLD : Ui.GREEN) : CaptureService.inst != null ? Ui.CYAN : Ui.FAINT;
        livePulse.set(running && !e.paused, col); railPulse.set(running && !e.paused, col);
        startLabel.setText(running ? (e.paused ? "⏸  Paused" : "●  Running") : "▶  Start");
        tField.setText(Routes.name(p.field)); tPattern.setText(p.pattern);
        tPhase.setText(running ? e.phase : "—");
        boolean bagOk = v != null && v.pollen >= 0 && v.pollenConf >= p.minConfidence;
        tBag.setText(bagOk ? String.format(Locale.US, "%.0f%%", v.pollen) : "?");
        bagBar.set(bagOk ? (float) (v.pollen / 100.0) : 0);
        tHive.setText("Slot " + p.hiveSlot + (p.hiveConfirmed ? " ✔" : ""));
        tLoops.setText(running ? String.valueOf(e.stats.loops) : "—");
        String hr = running ? e.stats.honeyRate() : "";
        tRate.setText(hr.isEmpty() ? (Monitor.honey > 0 ? "honey " + Stats.big(Monitor.honey) : "—") : hr);
        tTimer.setText(running ? Stats.fmt(e.stats.elapsed()) : "0:00");
        // buffs as chips
        buffChips.removeAllViews();
        List<Buffs.Effect> act = Monitor.activeBuffs();
        if (act.isEmpty()) buffChips.addView(tv("None right now", 12, DIM));
        for (Buffs.Effect b : act) {
            TextView c = tv((b.debuff ? "🔴 " : "✨ ") + b.name + (b.stack == null ? "" : " " + b.stack) + "\n" + Stats.fmt(SystemClock.uptimeMillis() - b.since), 12, TXT);
            c.setGravity(Gravity.CENTER); c.setPadding(dp(12), dp(8), dp(12), dp(8)); c.setBackground(Ui.glass(this, 14, true));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMargins(0, dp(4), dp(8), dp(4)); buffChips.addView(c, lp);
        }
        aiText.setText(running ? "▸ " + e.decision + (e.why.isEmpty() ? "" : "\n" + e.why) + (e.nextAction.isEmpty() ? "" : "\nNext: " + e.nextAction)
                : "Waiting to start. The AI explains every step here while it farms.");
    }

    // ---- statistics: parsed from the saved session summaries
    static final Pattern MADE = Pattern.compile("made ([0-9.]+)([KMBT]?)"), RATE = Pattern.compile("\\(([0-9.]+)([KMBT]?) per hour\\)"), TOTAL = Pattern.compile("Total (\\d+(?::\\d+)+)");
    static double big(Matcher m) {
        double v = Double.parseDouble(m.group(1));
        switch (m.group(2)) { case "K": return v * 1e3; case "M": return v * 1e6; case "B": return v * 1e9; case "T": return v * 1e12; default: return v; }
    }
    static long secs(String hms) { long s = 0; for (String x : hms.split(":")) s = s * 60 + Long.parseLong(x); return s; }
    void refreshStats() {
        List<String> hist = Stats.history(this);
        Engine run = Engine.inst;
        String now = run != null && !run.finished() ? "RUNNING NOW\n" + run.stats.summary() + "\n\n" : "";
        history.setText(now + (hist.isEmpty() ? "No finished sessions yet." : String.join("\n\n", hist.subList(0, Math.min(10, hist.size())))));
        double total = 0, best = 0; long time = 0; List<double[]> per = new ArrayList<>(); List<String> lbl = new ArrayList<>();
        for (String s : hist) {
            Matcher m = MADE.matcher(s), r = RATE.matcher(s), t = TOTAL.matcher(s);
            double made = m.find() ? big(m) : 0, rate = r.find() ? big(r) : 0;
            if (t.find()) time += secs(t.group(1));
            total += made; best = Math.max(best, rate);
            per.add(new double[]{made, rate}); lbl.add(s.length() >= 16 ? s.substring(5, 10) : "");
        }
        int n = Math.min(12, per.size());
        double[] made = new double[n], rate = new double[n]; String[] lb = new String[n];
        for (int i = 0; i < n; i++) { int k = n - 1 - i; made[i] = per.get(k)[0]; rate[i] = per.get(k)[1]; lb[i] = lbl.get(k); }   // oldest → newest
        honeyChart.set(made, lb); rateChart.set(rate, lb);
        sTotal.setText(Stats.big((long) total)); sBest.setText(best > 0 ? Stats.big((long) best) : "—");
        sTime.setText(Stats.fmt(time * 1000)); sCount.setText(String.valueOf(hist.size()));
    }

    long perfAt;
    void refreshPerf() {
        if (SystemClock.uptimeMillis() - perfAt < 1000) return;
        perfAt = SystemClock.uptimeMillis();
        Intent b = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        double temp = b == null ? 0 : b.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0;
        int lvl = b == null ? -1 : b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) * 100 / Math.max(1, b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100));
        boolean chg = b != null && b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0;
        System.arraycopy(fpsHist, 1, fpsHist, 0, fpsHist.length - 1); fpsHist[fpsHist.length - 1] = Monitor.fps;
        System.arraycopy(tempHist, 1, tempHist, 0, tempHist.length - 1); tempHist[tempHist.length - 1] = temp;
        perfN = Math.min(fpsHist.length, perfN + 1);
        if (!currentKey.equals("perf")) return;
        Runtime rt = Runtime.getRuntime();
        pFps.setText(String.format(Locale.US, "%.1f", Monitor.fps)); pFrame.setText(Monitor.lastFrameMs + "");
        pTemp.setText(String.format(Locale.US, "%.0f°C · %.0f°F", temp, temp * 9 / 5 + 32)); pBatt.setText(lvl + "%" + (chg ? " ⚡" : ""));
        pRam.setText((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024) + " MB");
        double[] f = java.util.Arrays.copyOfRange(fpsHist, fpsHist.length - perfN, fpsHist.length), t = java.util.Arrays.copyOfRange(tempHist, tempHist.length - perfN, tempHist.length);
        fpsChart.set(f, new String[0]); tempChart.set(t, new String[0]);
    }

    /** Review queue: thumbnails of uncertain objects with Confirm / Name… / False detection. */
    void drawReview() {
        reviewRows.removeAllViews();
        List<Detector.Det> items;
        synchronized (Monitor.review) { items = new java.util.ArrayList<>(Monitor.review); }
        if (items.isEmpty()) { reviewRows.addView(tv("Nothing to review yet.", 12, DIM)); return; }
        for (Detector.Det d : items) {
            // two lines (a 360 dp phone has no room for picture + text + 3 buttons in one): picture + guess, then buttons
            LinearLayout top = row(); top.setPadding(0, dp(8), 0, dp(2));
            ImageView th = new ImageView(this);
            th.setImageBitmap(Bitmap.createScaledBitmap(Bitmap.createBitmap(d.tpl, Catalog.N, Catalog.N, Bitmap.Config.ARGB_8888), dp(56), dp(56), false));
            top.addView(th, new LinearLayout.LayoutParams(dp(56), dp(56)));
            TextView g = tv(d.guess == null ? "No guess" : String.format(Locale.US, "Looks most like %s (%.2f)", d.guess, d.guessScore), 13, TXT); g.setPadding(dp(12), 0, 0, 0);
            top.addView(g, new LinearLayout.LayoutParams(0, -2, 1));
            reviewRows.addView(top);
            LinearLayout rw = row();
            if (d.guess != null) { Button ok = button("✓ It is", 0xFF2E7D32, v -> confirm(d, d.guess)); ok.setTextSize(12); rw.addView(ok, new LinearLayout.LayoutParams(0, dp(44), 1)); }
            Button nm = button("Name…", 0, v -> { nameDialog(d); synchronized (Monitor.review) { Monitor.review.remove(d); } }); nm.setTextSize(12);
            rw.addView(nm, new LinearLayout.LayoutParams(0, dp(44), 1));
            Button no = button("✗ Not a token", 0xFFB3261E, v -> { save(d, "Ignore", Detector.Cat.IGNORE); synchronized (Monitor.review) { Monitor.review.remove(d); } drawReview(); }); no.setTextSize(12);
            rw.addView(no, new LinearLayout.LayoutParams(0, dp(44), 1.3f));
            reviewRows.addView(rw);
        }
    }
    void confirm(Detector.Det d, String name) {
        Catalog c = App.catalog(this); Detector.Cat cat = Detector.Cat.TOKEN;
        if (c != null) for (Catalog.Entry e : c.entries) if (e.name.equals(name)) cat = Detector.cat(e.cat);
        save(d, name, cat);
        synchronized (Monitor.review) { Monitor.review.remove(d); }
        drawReview();
    }

    /** Searchable catalog: wiki reference image, name, kind, category, status (+ your verified count), source. */
    void drawCatalog(String query) {
        catRows.removeAllViews();
        Catalog c = App.catalog(this);
        if (c == null) { catText.setText("Catalog failed to load (see log)."); return; }
        Detector.Library lib = App.library(this);
        int k = 0, shown = 0; String ql = query.toLowerCase(Locale.US).trim();
        for (Catalog.Entry e : c.entries) {
            int idx = k++;
            if (!ql.isEmpty() && !(e.name + " " + e.kind + " " + e.cat).toLowerCase(Locale.US).contains(ql)) continue;
            if (shown++ >= 60) { catRows.addView(tv("… refine the search to see more", 12, DIM)); break; }
            LinearLayout rw = row();
            ImageView th = new ImageView(this);
            try { th.setImageBitmap(android.graphics.BitmapFactory.decodeStream(getAssets().open("thumbs/" + idx + ".png"))); } catch (Exception ignored) { }
            rw.addView(th, new LinearLayout.LayoutParams(dp(36), dp(36)));
            int mine = lib.count(e.name);
            rw.addView(tv(" " + e.name + "\n " + e.kind.toLowerCase(Locale.US) + " · " + e.cat.toLowerCase(Locale.US) + " · "
                    + (mine > 0 ? "verified in-game (" + mine + ")" : "wiki reference only, unverified") + "\n " + e.page, 11, mine > 0 ? TXT : DIM), new LinearLayout.LayoutParams(0, -2, 1));
            rw.setOnClickListener(v -> toast(e.url.isEmpty() ? e.file : e.url));
            catRows.addView(rw);
        }
        java.util.Map<String, Integer> kinds = new java.util.TreeMap<>();
        for (Catalog.Entry e : c.entries) kinds.merge(e.kind, 1, Integer::sum);
        catText.setText(c.entries.size() + " entries " + kinds + " · " + lib.ex.size() + " verified in-game examples. "
                + "Images © Onett / Bee Swarm Simulator via the BSS Fandom wiki, kept on this device as recognition references. Tap a row for its source.");
    }

    /** Name dialog for one detection: existing names offered, then the category. Adds the example and saves. */
    void nameDialog(Detector.Det d) {
        android.widget.AutoCompleteTextView et = new android.widget.AutoCompleteTextView(this);
        List<String> ls = App.library(this).labels(); if (!ls.contains("Ignore")) ls.add("Ignore");
        et.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, ls));
        et.setThreshold(0); et.setHint(d.buff ? "e.g. Bear Morph" : "e.g. Honey Token, Sprinkler, Ignore");
        et.setOnFocusChangeListener((v, has) -> { if (has) et.showDropDown(); });
        new android.app.AlertDialog.Builder(this).setTitle(d.buff ? "Name this buff icon" : "Name this object").setView(et)
                .setPositiveButton("Next", (dl, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty() || name.equalsIgnoreCase("unknown")) { toast("Give it a name"); return; }
                    if (name.equalsIgnoreCase("ignore")) { save(d, "Ignore", Detector.Cat.IGNORE); return; }
                    Detector.Cat[] cats = {Detector.Cat.MARK, Detector.Cat.TOKEN, Detector.Cat.LOOT, Detector.Cat.BOOST, Detector.Cat.LANDMARK};
                    String[] names = {"Mark (blue)", "Token: honey/pollen (cyan)", "Loot / collectible (green)", "Boost (purple)", "Landmark: sprinkler, hive… (yellow)"};
                    new android.app.AlertDialog.Builder(this).setTitle("Category of " + name).setItems(names, (d2, i) -> save(d, name, cats[i])).show();
                }).setNegativeButton("Cancel", null).show();
    }
    void save(Detector.Det d, String name, Detector.Cat cat) {
        App.library(this).add(name, cat, d.buff, d.tpl); App.saveLibrary(this);
        Log.add("labels: +" + name + " (" + cat + (d.buff ? ", buff" : "") + ")");
        toast("Saved: " + name); refresh();
    }

    void line(StringBuilder s, boolean ok, String t) { s.append(ok ? "✅ " : "⚠️ ").append(t).append('\n'); }

    // ------------------------------------------------------------------ Appearance (Settings)
    void buildAppearance() {
        LinearLayout a = card("Appearance");
        a.addView(tv("Theme", 12, DIM));
        LinearLayout th = row();
        for (int i = 0; i < Ui.THEMES.length; i++) { String t = Ui.THEMES[i]; th.addView(chip(Ui.THEME_NAMES[i], p.theme.equals(t), v -> setTheme(t, p.accentColor, p.motionLevel))); }
        HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false); hs.addView(th); a.addView(hs);
        a.addView(tv("Accent colour (picks the Custom accent theme)", 12, DIM));
        LinearLayout sw = row(); sw.setPadding(0, dp(6), 0, dp(6));
        for (int c : Ui.ACCENTS) {
            View dot = new View(this);
            GradientDrawable d = new GradientDrawable(); d.setShape(GradientDrawable.OVAL); d.setColor(c);
            if (p.theme.equals("custom") && p.accentColor == c) d.setStroke(dp(3), TXT); else d.setStroke(dp(1), Ui.STROKE);
            dot.setBackground(d); dot.setContentDescription("Accent colour");
            dot.setOnClickListener(v -> { press(v); setTheme("custom", c, p.motionLevel); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30)); lp.setMargins(0, 0, dp(10), 0); sw.addView(dot, lp);
        }
        HorizontalScrollView hs2 = new HorizontalScrollView(this); hs2.setHorizontalScrollBarEnabled(false); hs2.addView(sw); a.addView(hs2);
        a.addView(tv("Animations", 12, DIM));
        LinearLayout an = row(); String[] mn = {"Off", "Reduced", "Full"};
        for (int i = 2; i >= 0; i--) { int lvl = i; an.addView(chip(mn[i], p.motionLevel == i, v -> setTheme(p.theme, p.accentColor, lvl))); }
        a.addView(an);
        a.addView(tv("Reduced keeps page changes and button feedback but drops looping motion; Off is instant. Android's own "
                + "\"Remove animations\" switch turns them off too. The floating panel over the game stays dark in every theme "
                + "(readable over Bee Swarm) and takes your accent.", 11, DIM));
    }
    /** Pill chip: filled when selected, outlined otherwise. */
    TextView chip(String s, boolean on, View.OnClickListener l) {
        TextView t = tv(s, 13, on ? Ui.ON_PRIMARY : TXT); t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable out = new GradientDrawable(); out.setCornerRadius(dp(18)); out.setStroke(dp(1), Ui.alpha(TXT, 0.22f));
        t.setBackground(on ? Ui.solid(this, Ui.PRIMARY, 18) : out);
        t.setOnClickListener(v -> { press(v); l.onClick(v); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMargins(0, dp(4), dp(8), dp(8)); t.setLayoutParams(lp);
        return t;
    }
    /** New theme / accent / animation level, live: the palette changes, the floating panel restyles, this screen is
     *  rebuilt on the same tab (a quick fade; no app restart, the macro and music keep running). */
    void setTheme(String t, int accent, int motion) {
        if (t.equals("custom") && accent == 0) accent = Ui.ACCENTS[0];
        p.theme = t; p.accentColor = accent; p.motionLevel = motion; App.save(this); Ui.apply(p);
        Log.add("theme: " + t + (t.equals("custom") ? String.format(" #%06X", accent & 0xFFFFFF) : "") + ", animations " + motion);
        if (MacroService.inst != null) MacroService.inst.restyle();
        if (Ui.motion() && content != null) content.animate().alpha(0).setDuration(110).setInterpolator(Ui.EASE_OUT).withEndAction(this::recreate).start();
        else recreate();
    }

    // ------------------------------------------------------------------ Red Hive
    static final String[] RED_FIELDS = {"rose", "strawberry", "pepper", "mushroom"};
    void buildRedHive() {
        page("redhive");
        LinearLayout h = card("Hive type");
        h.addView(tv(p.redHive()
                ? "Red hive is on: Silly Macro farms the red field below with Natro's pattern for it."
                : "Blue hive (Pine Tree, Skillet) is on. Switch to Red hive to farm a red field (Rose, Strawberry, Pepper, Mushroom). Your blue settings stay saved.", 12, DIM));
        LinearLayout hr = row();
        hr.addView(chip("Blue hive", !p.redHive(), v -> { if (!p.redHive()) return; p.applyBlueHive(); App.save(this); Log.add("hive type: blue"); recreate(); }));
        hr.addView(chip("Red hive", p.redHive(), v -> { if (p.redHive()) return; p.applyRedHive(); App.save(this); Log.add("hive type: red, field " + p.field + ", " + p.pattern); recreate(); }));
        h.addView(hr);
        LinearLayout m = card("Field");
        m.addView(stepper("Field", () -> Routes.name(p.field) + (Routes.blueField(p.field) ? " (blue)" : ""), d -> {
            int i = 0; for (int k = 0; k < RED_FIELDS.length; k++) if (RED_FIELDS[k].equals(p.field)) i = k;
            p.field = RED_FIELDS[(i + d + RED_FIELDS.length) % RED_FIELDS.length]; p.fieldDefaults(); if (MacroService.inst != null) MacroService.inst.restyle();
        }));
        m.addView(tv("Changing the field also sets Natro's pattern for it (Sequences tab to change it).", 11, DIM));
        if (!Profile.AI_GATHERING) {   // AI token gathering is held back for now (Profile.AI_GATHERING)
            LinearLayout ai = card("AI token gathering");
            ai.addView(tv("Coming in a later update. Token recognition isn't reliable enough on real red fields yet, so the macro farms "
                    + "with your field's pattern and doesn't chase tokens.", 12, DIM));
            return;
        }
        LinearLayout mv = card("Token walking");
        mv.addView(toggle("Farm with the AI Tokens pattern (walks all over the field, grabs tokens as they pop up)", p.pattern.equals(Patterns.AI),
                on -> { if (on) p.pattern = Patterns.AI; else p.fieldDefaults(); App.save(this); if (MacroService.inst != null) MacroService.inst.restyle(); }));   // panel shows the new pattern
        mv.addView(stepper("Max token distance", () -> Math.round(p.collectRange * 100) + "% of screen", d -> p.collectRange = Math.max(0.1f, Math.min(0.6f, Math.round((p.collectRange + d * 0.05f) * 100) / 100f))));
        mv.addView(stepper("Max walk per token", () -> p.maxCollectMs + " ms", d -> p.maxCollectMs = clamp(p.maxCollectMs + d * 250, 250, 4000)));
        mv.addView(tv("Movement is the same key sender as every other step (Shizuku's virtual keyboard: no physical keyboard needed): short W/A/S/D "
                + "presses re-aimed at the token on every frame, then the same presses reversed so the pattern carries on where it left off.", 11, DIM));
        LinearLayout t = card("Ability tokens to go for");
        t.addView(toggle("Collect tokens while farming", p.collectTokens, on -> p.collectTokens = on));
        t.addView(stepper("🔥 Flame", () -> "" + p.prioFlame, d -> p.prioFlame = clamp(p.prioFlame + d, 0, 5)));
        t.addView(tv("Inferno, Flame Fuel", 11, DIM));
        t.addView(stepper("🎯 Marks", () -> "" + p.prioMarks, d -> p.prioMarks = clamp(p.prioMarks + d, 0, 5)));
        t.addView(tv("Pollen, Honey and Precise marks, Mark Surge, Target Practice", 11, DIM));
        t.addView(stepper("🔴 Red boosts", () -> "" + p.prioRedBoost, d -> p.prioRedBoost = clamp(p.prioRedBoost + d, 0, 5)));
        t.addView(tv("Red Boost, Rage, Red Bomb Sync, Red Pulse", 11, DIM));
        t.addView(stepper("Other tokens", () -> "" + p.prioToken, d -> p.prioToken = clamp(p.prioToken + d, 0, 5)));
        t.addView(stepper("Loot", () -> "" + p.prioLoot, d -> p.prioLoot = clamp(p.prioLoot + d, 0, 5)));
        t.addView(tv("0 = never. 5 = always first. Lower numbers are weighed against distance. Blue-only tokens are skipped with a red hive.", 11, DIM));
        LinearLayout r = card("Recognition & performance");
        r.addView(button("Review detected tokens (teach it what they are)", PURPLE, v -> show("tokens", true)));   // works with advanced tabs hidden
        r.addView(toggle("Learn from results (adaptive priority)", p.adaptiveTokens, on -> p.adaptiveTokens = on));
        r.addView(stepper("Name confidence needed", () -> String.format(java.util.Locale.US, "%.2f", p.boxMinConf), d -> p.boxMinConf = Math.max(0.6, Math.min(0.95, Math.round((p.boxMinConf + d * 0.05) * 100) / 100.0))));
        r.addView(stepper("Detection rate (battery!)", () -> p.detectFps + " fps", d -> p.detectFps = clamp(p.detectFps + d, 1, 15)));
        r.addView(toggle("Draw detection boxes over the game", p.showBoxes, on -> p.showBoxes = on));
        r.addView(tv("What the AI really does, on the phone, no internet and no big model:\n"
                + "• Detects: anything round and token-sized that isn't flower or grass (red fields: red petals and their yellow centres count as ground).\n"
                + "• Recognises: compares each one with the token pictures (wiki) and the examples you confirmed (Tokens tab → Review). A name is only given when it clearly beats every other name.\n"
                + "• Tracks: the same token across frames; it's only acted on once 3 of the last 4 frames agree.\n"
                + "• Chooses: priority 5 goes first; below that a near token can beat a far one, and tokens in a group count for more. Target Practice counts extra while your Precision buff is off.\n"
                + "• Adapts: counts which tokens it really picked up this run; tokens it keeps missing drop down the list.\n"
                + "Red tokens on red petals are the hardest case: confirming a few examples in Review makes them reliable.", 11, DIM));
    }

    // ------------------------------------------------------------------ Music
    static final int REQ_AUDIO = 41;   // 7 is screen capture (requestCapture): must stay different
    TextView mTitle, mSub, mTime, mPlay, mRepeat, mShuffle, mMute; Ui.Bar mBar; LinearLayout mLists, mTracks; Runnable musicListener;
    View mini, miniFull, miniChip; TextView miniTitle, miniPlay;
    void buildMusic() {
        page("music");
        Music m = Music.get(this);
        LinearLayout np = card("Now playing");
        np.addView(toggle("Music on", p.musicOn, on -> m.setOn(on)));
        mTitle = tv("", 20, TXT); mTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); mTitle.setPadding(0, dp(6), 0, 0); np.addView(mTitle);
        mSub = tv("", 12, DIM); np.addView(mSub);
        mBar = new Ui.Bar(this); LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(5)); bl.setMargins(0, dp(10), 0, dp(4)); np.addView(mBar, bl);
        mTime = tv("", 11, DIM); np.addView(mTime);
        LinearLayout ctl = row(); ctl.setGravity(Gravity.CENTER);
        ctl.addView(roundBtn("⏮︎", false, v -> m.prev()));
        mPlay = roundBtn("▶︎", true, v -> m.toggle()); ctl.addView(mPlay);
        ctl.addView(roundBtn("⏭︎", false, v -> m.next(true)));
        np.addView(ctl);
        LinearLayout vr = row();
        mMute = roundBtn("🔊", false, v -> m.setMute(!p.musicMute)); vr.addView(mMute);
        SeekBar vol = new SeekBar(this); vol.setMax(100); vol.setProgress(p.musicVolume);
        vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean u) { if (u) m.setVolume(v); }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { App.save(MainActivity.this); }
        });
        vr.addView(vol, new LinearLayout.LayoutParams(0, -2, 1));
        np.addView(vr);
        LinearLayout mo = row();
        mRepeat = chip("", false, v -> m.setRepeat(p.musicRepeat.equals("off") ? "all" : p.musicRepeat.equals("all") ? "one" : "off")); mo.addView(mRepeat);
        mShuffle = chip("", false, v -> { p.musicShuffle = !p.musicShuffle; App.save(this); drawMusicState(); }); mo.addView(mShuffle);
        np.addView(mo);
        np.addView(toggle("Keep playing during macro runs", p.musicDuringRun, on -> p.musicDuringRun = on));
        np.addView(tv("Off: music pauses when a run starts. On: it keeps playing over Bee Swarm. Calls and other apps' short sounds pause it either way.", 11, DIM));

        LinearLayout pl = card("Playlists");
        mLists = new LinearLayout(this); mLists.setOrientation(LinearLayout.VERTICAL); pl.addView(mLists);
        pl.addView(button("New playlist", BLUE, v -> ask("New playlist", "", name -> { m.lists.create(name); m.saveLists(); drawMusic(); })));
        LinearLayout tr = card("Tracks");
        mTracks = new LinearLayout(this); mTracks.setOrientation(LinearLayout.VERTICAL); tr.addView(mTracks);
        tr.addView(button("Import audio files…", PURPLE, v -> {
            try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQ_AUDIO); }
            catch (Exception e) { toast("No file picker on this device"); }
        }));
        tr.addView(button("Add a built-in track…", 0, v -> new android.app.AlertDialog.Builder(this).setTitle("Built-in tracks").setItems(Playlists.BUILTIN, (dl, i) -> {
            m.list().tracks.add(Playlists.builtin(i)); m.saveLists(); drawMusic(); }).show()));
        tr.addView(tv("Built-in tracks are made by Silly Macro itself from simple maths (no recordings), so they're free to use. "
                + "Imported files stay where they are on your phone; the playlist only remembers them. ★ = the track it starts with.", 11, DIM));
        musicListener = () -> { drawMusicState(); if (currentKey.equals("music")) drawMusic(); };
        m.listen(musicListener);
    }
    TextView roundBtn(String s, boolean primary, View.OnClickListener l) {
        TextView t = tv(s, primary ? 20 : 16, primary ? Ui.ON_PRIMARY : TXT); t.setGravity(Gravity.CENTER);
        t.setBackground(primary ? Ui.solid(this, Ui.PRIMARY, 28) : Ui.glass(this, 24, true));
        t.setOnClickListener(v -> { press(v); l.onClick(v); });
        int sz = dp(primary ? 56 : 46);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz); lp.setMargins(dp(8), dp(8), dp(8), dp(8)); t.setLayoutParams(lp);
        return t;
    }
    interface Got { void got(String s); }
    void ask(String title, String start, Got g) {
        android.widget.EditText e = new android.widget.EditText(this); e.setText(start); e.setSingleLine(true); e.setSelectAllOnFocus(true);
        new android.app.AlertDialog.Builder(this).setTitle(title).setView(e).setPositiveButton("OK", (d, w) -> { String s = e.getText().toString().trim(); if (!s.isEmpty()) g.got(s); }).setNegativeButton("Cancel", null).show();
    }
    void drawMusicState() {
        if (mTitle == null) return;
        Music m = Music.get(this);
        mTitle.setText(m.title());
        mSub.setText(m.error.isEmpty() ? m.list().name + (p.musicOn ? m.playing() ? " · playing" : " · paused" : " · music off") : m.error);
        mSub.setTextColor(m.error.isEmpty() ? DIM : Ui.GOLD);
        int pos = m.position(), dur = m.duration();
        mBar.set(dur > 0 ? pos / (float) dur : 0);
        mTime.setText(dur > 0 ? Stats.fmt(pos) + " / " + Stats.fmt(dur) : "");
        mPlay.setText(m.playing() ? "❚❚" : "▶︎");
        mMute.setText(p.musicMute ? "🔇" : "🔊");
        mRepeat.setText("Repeat: " + (p.musicRepeat.equals("one") ? "one" : p.musicRepeat.equals("all") ? "all" : "off"));
        mShuffle.setText(p.musicShuffle ? "Shuffle: on" : "Shuffle: off");
        updateMini();
    }
    void drawMusic() {
        if (mLists == null) return;
        Music m = Music.get(this);
        mLists.removeAllViews();
        for (int i = 0; i < m.lists.lists.size(); i++) {
            int li = i; Playlists.Playlist l = m.lists.lists.get(i);
            LinearLayout r = row(); r.setPadding(0, dp(2), 0, dp(2));
            TextView n = tv((i == p.musicPlaylist ? "● " : "") + l.name + "  (" + l.tracks.size() + ")", 14, i == p.musicPlaylist ? TXT : DIM);
            n.setOnClickListener(v -> { p.musicPlaylist = li; p.musicIndex = 0; App.save(this); drawMusic(); drawMusicState(); });
            r.addView(n, new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(mini("▶", v -> { if (!l.tracks.isEmpty()) m.playAt(li, 0); }));
            r.addView(mini("✎", v -> ask("Rename playlist", l.name, s -> { m.lists.rename(li, s); m.saveLists(); })));
            r.addView(mini("↑", v -> { if (Playlists.move(m.lists.lists, li, li - 1)) { if (p.musicPlaylist == li) p.musicPlaylist--; else if (p.musicPlaylist == li - 1) p.musicPlaylist++; m.saveLists(); } }));
            r.addView(mini("↓", v -> { if (Playlists.move(m.lists.lists, li, li + 1)) { if (p.musicPlaylist == li) p.musicPlaylist++; else if (p.musicPlaylist == li + 1) p.musicPlaylist--; m.saveLists(); } }));
            r.addView(mini("✕", v -> new android.app.AlertDialog.Builder(this).setTitle("Remove \"" + l.name + "\"?").setMessage("The playlist goes; your audio files stay on the phone.")
                    .setPositiveButton("Remove", (d, w) -> { if (!m.lists.remove(li)) { toast("Keep at least one playlist"); return; } if (p.musicPlaylist >= li && p.musicPlaylist > 0) p.musicPlaylist--; App.save(this); m.saveLists(); })
                    .setNegativeButton("Cancel", null).show()));
            mLists.addView(r);
        }
        mTracks.removeAllViews();
        Playlists.Playlist l = m.list();
        if (l.tracks.isEmpty()) mTracks.addView(tv("No tracks in \"" + l.name + "\" yet.", 12, DIM));
        for (int i = 0; i < l.tracks.size(); i++) {
            int ti = i; Playlists.Track t = l.tracks.get(i);
            boolean cur = i == p.musicIndex;
            LinearLayout r = row(); r.setPadding(0, dp(2), 0, dp(2));
            TextView n = tv((t.uri.equals(p.musicDefault) ? "★ " : "") + t.title, 14, cur ? Ui.CYAN : TXT); n.setSingleLine(true); n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            n.setOnClickListener(v -> m.playAt(p.musicPlaylist, ti));
            r.addView(n, new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(mini("★", v -> { p.musicDefault = t.uri; App.save(this); drawMusic(); toast("\"" + t.title + "\" plays first from now on"); }));
            r.addView(mini("↑", v -> { if (Playlists.move(l.tracks, ti, ti - 1)) { if (p.musicIndex == ti) p.musicIndex--; else if (p.musicIndex == ti - 1) p.musicIndex++; m.saveLists(); } }));
            r.addView(mini("↓", v -> { if (Playlists.move(l.tracks, ti, ti + 1)) { if (p.musicIndex == ti) p.musicIndex++; else if (p.musicIndex == ti + 1) p.musicIndex--; m.saveLists(); } }));
            r.addView(mini("✕", v -> { l.tracks.remove(ti); if (p.musicIndex > ti) p.musicIndex--; App.save(this); m.saveLists(); }));
            mTracks.addView(r);
        }
    }
    TextView mini(String s, View.OnClickListener l) {
        TextView t = tv(s, 14, DIM); t.setGravity(Gravity.CENTER); t.setOnClickListener(v -> { press(v); l.onClick(v); });
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(40))); return t;
    }
    void importAudio(Intent data) {
        java.util.List<Uri> us = new java.util.ArrayList<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) us.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) us.add(data.getData());
        int n = 0;
        for (Uri u : us) {
            try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception e) { Log.add("music: no lasting access to " + u); }
            String title = "Imported track";
            try (android.database.Cursor c = getContentResolver().query(u, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) title = c.getString(0).replaceFirst("\\.[A-Za-z0-9]{2,4}$", "");
            } catch (Exception ignored) { }
            Music.get(this).add(u.toString(), title); n++;
        }
        Log.add("music: imported " + n + " file(s)");
        toast(n == 1 ? "Added 1 track" : "Added " + n + " tracks");
        drawMusic();
    }

    /** Compact player over every page: title + ⏮ ⏯ ⏭, folds to a small ♪ chip. Shown while music is on. */
    View buildMiniPlayer() {
        FrameLayout f = new FrameLayout(this);
        LinearLayout full = row(); full.setPadding(dp(14), dp(4), dp(6), dp(4)); full.setBackground(Ui.glass(this, 26, true)); full.setElevation(dp(6));
        miniTitle = tv("", 13, TXT); miniTitle.setSingleLine(true); miniTitle.setEllipsize(android.text.TextUtils.TruncateAt.END); miniTitle.setMaxWidth(dp(150));
        miniTitle.setOnClickListener(v -> show("music", true));
        full.addView(miniTitle);
        full.addView(mini("⏮︎", v -> Music.get(this).prev()));
        miniPlay = mini("▶︎", v -> Music.get(this).toggle()); miniPlay.setTextColor(TXT); miniPlay.setTextSize(16); full.addView(miniPlay);
        full.addView(mini("⏭︎", v -> Music.get(this).next(true)));
        full.addView(mini("–", v -> { p.musicMini = false; App.save(this); updateMini(); }));
        TextView chip = tv("♪", 18, Ui.ON_PRIMARY); chip.setGravity(Gravity.CENTER); chip.setBackground(Ui.solid(this, Ui.PRIMARY, 24)); chip.setElevation(dp(6));
        chip.setOnClickListener(v -> { p.musicMini = true; App.save(this); updateMini(); });
        f.addView(full, new FrameLayout.LayoutParams(-2, dp(48), Gravity.END | Gravity.BOTTOM));
        f.addView(chip, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END | Gravity.BOTTOM));
        mini = f; miniFull = full; miniChip = chip;
        updateMini();
        return f;
    }
    FrameLayout.LayoutParams miniLp() { FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.BOTTOM); lp.setMargins(0, 0, dp(14), dp(14)); return lp; }
    void updateMini() {
        if (mini == null) return;
        Music m = Music.get(this);
        boolean show = p.musicOn;
        if ((mini.getVisibility() == View.VISIBLE) != show) {
            mini.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show && Ui.motion()) { mini.setAlpha(0); mini.setTranslationY(dp(12)); mini.animate().alpha(1).translationY(0).setDuration(200).setInterpolator(Ui.EASE_OUT).start(); }
        }
        boolean full = p.musicMini;
        if ((miniFull.getVisibility() == View.VISIBLE) != full) {
            View in = full ? miniFull : miniChip, out = full ? miniChip : miniFull;
            out.setVisibility(View.GONE); in.setVisibility(View.VISIBLE);
            if (Ui.motion()) { in.setAlpha(0); in.setScaleX(0.92f); in.setScaleY(0.92f); in.animate().alpha(1).scaleX(1).scaleY(1).setDuration(180).setInterpolator(Ui.EASE_OUT).start(); }
        } else miniChip.setVisibility(full ? View.GONE : View.VISIBLE);
        miniTitle.setText(m.title());
        miniPlay.setText(m.playing() ? "❚❚" : "▶︎");
    }

    // ------------------------------------------------------------------ view helpers (same API as before, new look)
    interface IntOp { void step(int d); }
    interface Str { String get(); }
    interface On { void set(boolean on); }

    View stepper(String label, Str val, IntOp op) {
        LinearLayout r = row(); r.setPadding(0, dp(4), 0, dp(4));
        TextView l = tv(label, 13, TXT); r.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        TextView v = tv(val.get(), 13, Ui.CYAN); v.setGravity(Gravity.CENTER); v.setTypeface(Typeface.DEFAULT_BOLD); r.addView(v, new LinearLayout.LayoutParams(dp(64), -2));
        r.addView(button("−", 0, x -> { op.step(-1); App.save(this); v.setText(val.get()); }), new LinearLayout.LayoutParams(dp(44), dp(40)));
        r.addView(button("+", 0, x -> { op.step(1); App.save(this); v.setText(val.get()); }), new LinearLayout.LayoutParams(dp(44), dp(40)));
        return r;
    }
    View toggle(String label, boolean on, On cb) {
        Switch s = new Switch(this); s.setText(label); s.setTextColor(TXT); s.setTextSize(13); s.setChecked(on);
        s.setPadding(0, dp(8), 0, dp(8));
        int[][] st = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new android.content.res.ColorStateList(st, new int[]{Ui.light ? PURPLE : Ui.CYAN, Ui.light ? 0xFFFFFFFF : 0xFFB8B9C0}));
        s.setTrackTintList(new android.content.res.ColorStateList(st, new int[]{Ui.alpha(PURPLE, 0.55f), Ui.alpha(TXT, 0.18f)}));
        s.setOnCheckedChangeListener((CompoundButton b, boolean v) -> { cb.set(v); App.save(this); });
        return s;
    }
    // ------------------------------------------------------------------ login (Account)
    void buildLogin() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(new Ui.HoneyBackground(this)); frame.setFitsSystemWindows(true);
        ScrollView sv = new ScrollView(this); sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(20), dp(40), dp(20), dp(40));
        sv.addView(col);
        frame.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        setContentView(frame);
        root = col;
        ImageView logo = new ImageView(this); logo.setImageResource(R.drawable.ic_launcher);
        col.addView(logo, new LinearLayout.LayoutParams(dp(84), dp(84)));
        TextView title = tv("Silly Macro", 26, TXT); title.setTypeface(Typeface.DEFAULT_BOLD); title.setGravity(Gravity.CENTER); col.addView(title);
        LinearLayout c = card(Account.disabled(this) ? "Account disabled" : "Sign in");
        c.setLayoutParams(new LinearLayout.LayoutParams(wide ? dp(440) : -1, -2) {{ topMargin = dp(16); }});
        if (Account.disabled(this)) {
            String r = Account.savedReason(this);
            c.addView(tv("This account was disabled by the Silly Macro admin" + (r.isEmpty() ? "." : ":\n" + r)
                    + "\n\nIf you think this is a mistake, contact the admin.", 13, Ui.GOLD));
        }
        c.addView(tv("An account is needed to use Silly Macro. It stores your email, when you last used the app, your device model and the app version. "
                + "Your password is only sent to the account server (Google Firebase), never stored on this device.", 12, DIM));
        android.widget.EditText email = new android.widget.EditText(this);
        email.setHint("Email"); email.setTextColor(TXT); email.setHintTextColor(Ui.FAINT); email.setSingleLine(true);
        email.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        email.setText(Account.email(this));
        email.setAutofillHints(View.AUTOFILL_HINT_EMAIL_ADDRESS);
        c.addView(email);
        android.widget.EditText pw = new android.widget.EditText(this);
        pw.setHint("Password (6+ characters)"); pw.setTextColor(TXT); pw.setHintTextColor(Ui.FAINT); pw.setSingleLine(true);
        pw.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pw.setAutofillHints(View.AUTOFILL_HINT_PASSWORD);
        c.addView(pw);
        TextView msg = tv("", 13, Ui.GOLD); c.addView(msg);
        java.util.function.Consumer<Boolean> go = create -> {
            String e = email.getText().toString().trim(), w = pw.getText().toString();
            if (e.isEmpty() || w.isEmpty()) { msg.setText("Enter your email and password."); return; }
            msg.setTextColor(DIM); msg.setText(create ? "Creating account…" : "Signing in…");
            new Thread(() -> {
                String why = Account.signIn(this, e, w, create);
                runOnUiThread(() -> { if (why == null) recreate(); else { msg.setTextColor(Ui.GOLD); msg.setText(why); } });
            }, "login").start();
        };
        c.addView(button("Sign in", PURPLE, v -> go.accept(false)));
        c.addView(button("Create account", BLUE, v -> go.accept(true)));
        c.addView(button("Forgot password", 0, v -> {
            String e = email.getText().toString().trim();
            if (e.isEmpty()) { msg.setText("Enter your email first."); return; }
            new Thread(() -> {
                String r; try { Account.fb(this).sendReset(e); r = "If that email has an account, a reset link is on its way."; } catch (Fb.Error x) { r = Fb.explain(x.code); }
                String rr = r; runOnUiThread(() -> { msg.setTextColor(DIM); msg.setText(rr); });
            }, "reset").start();
        }));
    }

    /** A card: hairline-bordered surface; tap its title to fold it away (the fold animates with the layout, and
     *  is instant with animations off). */
    LinearLayout card(String title) {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(16), dp(18), dp(16));
        c.setBackground(Ui.glass(this, 22, false));
        LinearLayout tr = row(); tr.setPadding(0, 0, 0, dp(10));
        TextView t = tv(title, 15, TXT); t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); tr.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        TextView chev = tv("⌄", 16, DIM); tr.addView(chev);
        c.addView(tr);
        if (Ui.motion()) { android.animation.LayoutTransition lt = new android.animation.LayoutTransition(); lt.setDuration(Ui.motionLevel >= 2 ? 200 : 120); c.setLayoutTransition(lt); }
        tr.setOnClickListener(v -> {
            boolean fold = c.getChildCount() > 1 && c.getChildAt(1).getVisibility() == View.VISIBLE;
            for (int i = 1; i < c.getChildCount(); i++) c.getChildAt(i).setVisibility(fold ? View.GONE : View.VISIBLE);
            if (Ui.motion()) chev.animate().rotation(fold ? -90 : 0).setDuration(180).setInterpolator(Ui.EASE_OUT).start(); else chev.setRotation(fold ? -90 : 0);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(12);
        root.addView(c, lp);
        return c;
    }
    LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    TextView tv(String s, int sp, int col) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(col); return t; }
    /** col PURPLE = primary (solid pill in the theme's button colour), BLUE = secondary (outlined pill), 0 = quiet
     *  surface, anything else = that colour (status colours: ✓ / ✗). */
    Button button(String s, int col, View.OnClickListener l) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextSize(14);
        b.setTextColor(col == PURPLE ? Ui.ON_PRIMARY : col == BLUE || col == 0 ? TXT : Color.WHITE);
        GradientDrawable out = new GradientDrawable(); out.setCornerRadius(dp(24)); out.setStroke(dp(1), Ui.alpha(TXT, 0.28f)); out.setColor(0);
        b.setBackground(col == PURPLE ? Ui.solid(this, Ui.PRIMARY, 24) : col == BLUE ? out : col == 0 ? Ui.glass(this, 24, true) : Ui.solid(this, col, 24));
        b.setStateListAnimator(null);
        b.setMinHeight(dp(46)); b.setMinimumHeight(dp(46)); b.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(dp(3), dp(4), dp(3), dp(4)); b.setLayoutParams(lp);
        b.setOnClickListener(l == null ? null : v -> { press(v); l.onClick(v); });
        return b;
    }
        int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    static final String SHIZUKU = "moe.shizuku.privileged.api";
    boolean shizukuInstalled() { try { getPackageManager().getPackageInfo(SHIZUKU, 0); return true; } catch (Exception e) { return false; } }
    /** Shizuku from the Play Store, or its official GitHub page in the browser. The in-app installer is gone: the
     *  "install other apps" permission it needed, next to Accessibility and screen capture, is what Google Play
     *  Protect blocks as "App blocked to protect your device" (Discord #help, 2026-11-23). */
    void getShizuku() {
        new android.app.AlertDialog.Builder(this).setTitle("Get Shizuku")
                .setMessage("Shizuku is free and open source. Get it from the Play Store, or from its official GitHub page (download the .apk under Assets).")
                .setPositiveButton("Play Store", (d, w) -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + SHIZUKU))); }
                    catch (Exception e) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + SHIZUKU))); }
                })
                .setNeutralButton("GitHub", (d, w) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases/latest"))))
                .setNegativeButton("Cancel", null).show();
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
