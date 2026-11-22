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
import android.media.projection.MediaProjectionManager;
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
    static final int BG = Ui.BG0, CARD = Ui.GLASS, PURPLE = Ui.PURPLE, BLUE = Ui.CYAN, TXT = Ui.TXT, DIM = Ui.DIM;
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
            {"hive", "🍯", "Hive", "Hive, conversion & balloon"}, {"stats", "📊", "Statistics", "Honey & sessions"},
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
    String deviceSummary() {
        android.graphics.Rect b = getWindowManager().getMaximumWindowMetrics().getBounds();
        int w = Math.max(b.width(), b.height()), h = Math.min(b.width(), b.height());
        boolean tab = Remap.tablet(w, h);
        String os = "Android " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")";
        return (tab ? "Tablet" : "Phone") + " · " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + "\n" + os
                + (android.os.Build.VERSION.SDK_INT < 33 ? "  ⚠ Silly Macro needs Android 13 or newer" : "")
                + "\nScreen " + w + " × " + h + " (" + String.format(java.util.Locale.US, "%.2f", w / (double) h) + ":1, "
                + getResources().getDisplayMetrics().densityDpi + " dpi)";
    }
    TextView heroSub, heroState, tPhase, tTimer, tBag, tHive, tLoops, tRate, tField, tPattern, aiText, startLabel;
    Ui.Bar bagBar; Ui.Pulse livePulse, railPulse; LinearLayout buffChips; View startGlow;
    // ---- statistics + performance
    Ui.Chart honeyChart, rateChart, fpsChart, tempChart; TextView sTotal, sTime, sBest, sCount, pFps, pFrame, pTemp, pBatt, pRam;
    final double[] fpsHist = new double[60], tempHist = new double[60]; int perfN;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = App.profile(this);
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        getWindow().setStatusBarColor(Ui.BG1); getWindow().setNavigationBarColor(BG);
        wide = getResources().getConfiguration().screenWidthDp >= 640;
        if (Account.configured(this) && !Account.signedIn(this)) { buildLogin(); return; }
        if (Account.configured(this)) new Thread(() -> {   // re-check on every open: a disabled account goes back to the login page
            String why = Account.check(this, true);
            if (why != null && !Account.signedIn(this)) runOnUiThread(this::recreate);
        }, "account").start();

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
        buildHive(); buildStats(); buildPerf(); buildSettings();
        for (View pg : pages.values()) pg.setVisibility(View.GONE);
        show("home", false);

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
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0x99090614); r.setBackground(bg);
        r.setPadding(0, dp(10), 0, dp(10));
        TextView menu = railButton("☰"); menu.setOnClickListener(v -> drawer(true)); r.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        View sep = new View(this); sep.setBackgroundColor(0x22FFFFFF); LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(28), dp(1)); sl.setMargins(0, dp(6), 0, dp(6)); r.addView(sep, sl);
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
        TextView t = new TextView(this); t.setText(glyph); t.setTextSize(20); t.setGravity(Gravity.CENTER); t.setTextColor(TXT);
        return t;
    }

    View buildDrawer() {
        drawer = new LinearLayout(this); drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setPadding(dp(16), dp(20), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xF2160C2E, 0xF208060F});
        bg.setStroke(dp(1), Ui.STROKE); drawer.setBackground(bg);
        ImageView logo = new ImageView(this); logo.setImageResource(R.drawable.ic_launcher);
        drawer.addView(logo, new LinearLayout.LayoutParams(dp(72), dp(72)));
        TextView name = tv("Silly Macro", 22, TXT); name.setTypeface(Typeface.DEFAULT_BOLD); name.setPadding(0, dp(8), 0, 0); drawer.addView(name);
        drawer.addView(tv("Bee Swarm farming assistant", 12, DIM));
        View sep = new View(this); sep.setBackgroundColor(0x22FFFFFF); LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, dp(1)); sl.setMargins(0, dp(14), 0, dp(8)); drawer.addView(sep, sl);
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
            e.getValue().setBackground(on ? Ui.pill(this, Ui.alpha(PURPLE, 0.55f), Ui.alpha(Ui.CYAN, 0.35f), 14) : null);
            TextView d = drawerItems.get(e.getKey());
            d.setBackground(on ? Ui.pill(this, Ui.alpha(PURPLE, 0.45f), Ui.alpha(Ui.CYAN, 0.15f), 12) : null);
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
        refresh();
    }

    /** A scrolling page with its title; card() adds to it until the next page starts. */
    LinearLayout page(String key) {
        ScrollView sv = new ScrollView(this); sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(14), dp(14), dp(40));
        sv.addView(col);
        String[] t = null; for (String[] x : TABS) if (x[0].equals(key)) t = x;
        TextView title = tv(t[2], 24, TXT); title.setTypeface(Typeface.DEFAULT_BOLD); col.addView(title);
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
        heroSub = tv("", 13, 0xFFC9B8FF); ht.addView(heroSub);
        hero.addView(ht, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout st = row(); livePulse = new Ui.Pulse(this); st.addView(livePulse, new LinearLayout.LayoutParams(dp(18), dp(18)));
        heroState = tv("Idle", 12, TXT); heroState.setPadding(dp(4), 0, 0, 0); st.addView(heroState);
        hero.addView(st);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2); hl.topMargin = dp(12); pg.addView(hero, hl);

        // start + controls
        LinearLayout ctl = card("Control");
        FrameLayout sf = new FrameLayout(this);
        startGlow = new View(this); startGlow.setBackground(Ui.pill(this, Ui.alpha(PURPLE, 0.55f), Ui.alpha(Ui.CYAN, 0.55f), 30));
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(-1, dp(64)); gl.setMargins(dp(6), dp(6), dp(6), dp(6)); sf.addView(startGlow, gl);
        startLabel = tv("▶  Start", 20, Color.WHITE); startLabel.setTypeface(Typeface.DEFAULT_BOLD); startLabel.setGravity(Gravity.CENTER);
        startLabel.setBackground(Ui.pill(this, PURPLE, 0xFF5B8CFF, 28));
        startLabel.setOnClickListener(v -> { press(v); startMacro(); });
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-1, dp(64)); sl.setMargins(dp(6), dp(6), dp(6), dp(6)); sf.addView(startLabel, sl);
        ctl.addView(sf, new LinearLayout.LayoutParams(-1, dp(76)));
        if (Ui.motion()) {   // the glow behind Start breathes; GPU-friendly (opacity + scale only)
            ValueAnimator g = ValueAnimator.ofFloat(0, 1); g.setDuration(2200); g.setRepeatCount(ValueAnimator.INFINITE); g.setRepeatMode(ValueAnimator.REVERSE);
            g.setInterpolator(Ui.EASE_IN_OUT);
            g.addUpdateListener(a -> { float t = (float) a.getAnimatedValue(); startGlow.setAlpha(0.25f + 0.45f * t); startGlow.setScaleX(1 + 0.02f * t); startGlow.setScaleY(1 + 0.12f * t); });
            g.start();
        } else startGlow.setAlpha(0.35f);
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
        c.addView(button("3 · Allow screen capture (pick \"Entire screen\")", PURPLE, v -> {
            if (CaptureService.inst == null) {
                MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
                // Android 14+ offers "A single app" first: the macro then sees a black screen ("dark", HUD not recognized). Ask for the whole screen.
                startActivityForResult(android.os.Build.VERSION.SDK_INT >= 34
                        ? mpm.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
                        : mpm.createScreenCaptureIntent(), 7);
            }
            else toast("Screen capture already running");
        }));
        c.addView(button("4 · Pick your hive slot (Hive tab)", PURPLE, v -> show("hive", true)));
        c.addView(button("5 · Open Bee Swarm (your private server)", BLUE, v -> {
            showPanel();   // the panel's ✕ hid it for good before (Discord: "panel isn't popping up")
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(p.serverLink)).setPackage("com.roblox.client")); }
            catch (Exception e) { toast("Roblox not installed"); }
        }));
        c.addView(tv("6 · In the game, hold your phone sideways and press ▶ Start on the floating panel. The app reads your screen by itself.", 12, DIM));
    }
    GradientDrawable heroBg() {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xCC2A1660, 0xB3121C3F});
        d.setCornerRadius(dp(20)); d.setStroke(dp(1), 0x668B5CF6); return d;
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
        Intent li = getPackageManager().getLaunchIntentForPackage("com.roblox.client");
        if (li == null) { toast("Roblox not installed"); return; }
        startActivity(li);
        toast("Starting in Roblox…");
        new Thread(() -> { SystemClock.sleep(3500); MacroService s = MacroService.inst; String why = s == null ? "input service stopped" : Engine.start(s); if (why != null) { MacroService.startWhy = why; MacroService.startWhyAt = SystemClock.uptimeMillis(); runOnUiThread(() -> toast(why)); } }, "start").start();
    }

    // ------------------------------------------------------------------ Tokens
    void buildTokens() {
        page("tokens");
        LinearLayout ct = card("Token collection");
        ct.addView(toggle("Collect labelled tokens while farming", p.collectTokens, on -> p.collectTokens = on));
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
                            p.moveSpeed = Math.round(ms * 100) / 100.0; App.save(this); refresh(); toast("Move speed set to " + p.moveSpeed);
                        } catch (NumberFormatException e) { toast("Type a number like 29.9"); }
                    }).setNegativeButton("Cancel", null).show();
        }));
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
    }

    // ------------------------------------------------------------------ Settings
    void buildSettings() {
        page("settings");
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
        LinearLayout dv = card("Your device");
        dv.addView(tv(deviceSummary(), 13, TXT));
        dv.addView(tv("Silly Macro reads Bee Swarm's HUD the way this screen draws it: it finds the Honey and Pollen "
                + "counters at the top and measures the scale, centre and camera-cutout inset by itself the first time you're in game.", 12, DIM));
        hudState = tv("", 13, Ui.CYAN); dv.addView(hudState);
        dv.addView(button("Re-measure HUD (after changing Roblox's UI or screen settings)", 0, v -> { Monitor.recalibrate(); toast("Open Bee Swarm in landscape for a few seconds"); }));
        if (Account.configured(this)) {
            LinearLayout ac = card("Account");
            ac.addView(tv("Signed in as " + Account.email(this), 13, TXT));
            ac.addView(button("Sign out", 0, v -> {
                Engine e = Engine.inst; if (e != null && !e.finished()) { toast("Stop the macro first"); return; }
                Account.signOut(this); recreate();
            }));
        }
        LinearLayout ps = card("Private server");
        ps.addView(tv("Used by \"Open Bee Swarm\" and by the overnight rejoin. Paste your server's share link from Roblox "
                + "(Server → Private Servers → … → Copy Link), e.g. https://www.roblox.com/share?code=…&type=Server", 12, DIM));
        android.widget.EditText link = new android.widget.EditText(this);
        link.setHint("https://www.roblox.com/share?code=…"); link.setTextColor(TXT); link.setHintTextColor(Ui.FAINT); link.setSingleLine(true);
        link.setText(p.serverLink);
        ps.addView(link);
        TextView psState = tv(KeyServer.linkOk(p.serverLink) ? "Server link saved ✔" : "No valid server link", 12, KeyServer.linkOk(p.serverLink) ? Ui.GREEN : Ui.GOLD);
        LinearLayout pr = row();
        pr.addView(button("Save link", PURPLE, v -> {
            String u = link.getText().toString().trim().replaceFirst("^http://", "https://").replaceFirst("^https://roblox\\.com/", "https://www.roblox.com/");
            if (!KeyServer.linkOk(u)) { toast("That isn't a Roblox server link (https://www.roblox.com/… or roblox://…)"); return; }
            p.serverLink = u; App.save(this); link.setText(u);
            psState.setText("Server link saved ✔"); psState.setTextColor(Ui.GREEN);
            Log.add("settings: private server link changed");
        }), new LinearLayout.LayoutParams(0, dp(48), 1));
        pr.addView(button("Open it", BLUE, v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(p.serverLink)).setPackage("com.roblox.client")); }
            catch (Exception e) { toast("Roblox not installed"); }
        }), new LinearLayout.LayoutParams(0, dp(48), 1));
        ps.addView(pr);
        ps.addView(psState);
        LinearLayout dc = card("Discord webhook");
        dc.addView(tv("Reports with screenshots to your Discord channel, like Natro: started, backpack full, converting, honey made, balloon, "
                + "Mondo, resets / rejoins, a timed report (every 10 min to 2 h) and the session summary. In Discord: channel → Edit → Integrations → Webhooks → New → Copy URL. "
                + "Screenshots go only to that webhook.", 12, DIM));
        android.widget.EditText url = new android.widget.EditText(this);
        url.setHint("https://discord.com/api/webhooks/…"); url.setTextColor(TXT); url.setHintTextColor(Ui.FAINT); url.setSingleLine(true);
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);   // it is a secret: hidden like a password
        url.setText(p.webhookUrl);
        dc.addView(url);
        TextView whState = tv(Webhook.valid(p.webhookUrl) ? "Webhook saved ✔" : "No webhook yet", 12, Webhook.valid(p.webhookUrl) ? Ui.GREEN : DIM);
        LinearLayout ub = row();
        ub.addView(button("Save", PURPLE, v -> {
            String u = url.getText().toString().trim();
            if (!u.isEmpty() && !Webhook.valid(u)) { toast("That isn't a Discord webhook URL"); return; }
            p.webhookUrl = u; App.save(this);
            whState.setText(u.isEmpty() ? "Webhook removed" : "Webhook saved ✔"); whState.setTextColor(u.isEmpty() ? DIM : Ui.GREEN);
        }), new LinearLayout.LayoutParams(0, dp(48), 1));
        ub.addView(button("Send test", BLUE, v -> {
            if (!Webhook.valid(p.webhookUrl)) { toast("Save a webhook URL first"); return; }
            Webhook.send(this, "test", "👋 Silly Macro test", "Webhook works. " + Routes.name(p.field) + " · " + p.pattern
                    + (Monitor.honey > 0 ? "\nHoney: " + Stats.big(Monitor.honey) : ""), Webhook.PURPLE, true);
            toast("Sending… (screenshot included if screen capture is on)");
        }), new LinearLayout.LayoutParams(0, dp(48), 1));
        dc.addView(ub);
        dc.addView(whState);
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
        LinearLayout k = card("Key sender (keyboard + mouse)");
        if (!shizukuInstalled()) k.addView(button("Install Shizuku (for the key sender without a PC)", 0, v -> getShizuku()));
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
        dbg.addView(tv(Profile.NAME + " · " + Profile.PRESET, 11, Ui.FAINT));
    }

    final Runnable tick = new Runnable() { @Override public void run() { refresh(); h.postDelayed(this, 1000); } };

    @Override protected void onDestroy() { h.removeCallbacks(tick); super.onDestroy(); }
    @Override protected void onResume() { super.onResume(); if (content == null) return; drawReview(); drawCatalog(""); }

    @Override protected void onActivityResult(int req, int code, Intent data) {
        if (req == 7 && code == RESULT_OK && data != null)
            startForegroundService(new Intent(this, CaptureService.class).putExtra("code", code).putExtra("data", data));
        else if (req == 7) toast("Screen capture was not allowed");
    }

    private long emuAsked;
    void refresh() {
        boolean input = MacroService.inst != null, cap = CaptureService.inst != null;
        Vision.Result v = Monitor.last;
        StringBuilder s = new StringBuilder();
        if (p.keyboard()) {
            String emu = Keys.emulator;
            line(s, emu != null && !emu.startsWith("none") && !emu.equals("starting"), "Input: keyboard + mouse emulator — " + (emu == null ? "key sender not reached yet" : emu.equals("starting") ? "starting…" : emu.startsWith("none") ? "NOT available (" + emu + ")" : "ready (" + emu + ")"));
            if (SystemClock.uptimeMillis() - emuAsked > 5000) { emuAsked = SystemClock.uptimeMillis(); new Thread(() -> { if (Keys.connect(this)) Keys.emulatorState(); }, "emu-state").start(); }
        }
        line(s, input, "Accessibility service (floating panel and menu taps)");
        line(s, cap, "Screen capture (MediaProjection)");
        line(s, p.hiveConfirmed, "Hive slot " + p.hiveSlot + (p.hiveConfirmed ? " confirmed" : " — confirm it once (Hive tab)"));
        line(s, input && MacroService.inst.overlayShown(), "Floating overlay with emergency stop");
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
            LinearLayout rw = row();
            ImageView th = new ImageView(this);
            th.setImageBitmap(Bitmap.createScaledBitmap(Bitmap.createBitmap(d.tpl, Catalog.N, Catalog.N, Bitmap.Config.ARGB_8888), dp(44), dp(44), false));
            rw.addView(th, new LinearLayout.LayoutParams(dp(44), dp(44)));
            rw.addView(tv(String.format(Locale.US, " guess: %s %.2f", d.guess == null ? "—" : d.guess, d.guessScore), 12, TXT), new LinearLayout.LayoutParams(0, -2, 1));
            if (d.guess != null) { Button ok = button("✓", 0xFF2E7D32, v -> confirm(d, d.guess)); rw.addView(ok, new LinearLayout.LayoutParams(dp(44), dp(44))); }
            Button nm = button("Name…", 0, v -> { nameDialog(d); synchronized (Monitor.review) { Monitor.review.remove(d); } }); nm.setTextSize(11);
            rw.addView(nm, new LinearLayout.LayoutParams(dp(72), dp(44)));
            Button no = button("✗", 0xFFB3261E, v -> { save(d, "Ignore", Detector.Cat.IGNORE); synchronized (Monitor.review) { Monitor.review.remove(d); } drawReview(); });
            rw.addView(no, new LinearLayout.LayoutParams(dp(44), dp(44)));
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

    // ------------------------------------------------------------------ view helpers (same API as before, new look)
    interface IntOp { void step(int d); }
    interface Str { String get(); }
    interface On { void set(boolean on); }

    View stepper(String label, Str val, IntOp op) {
        LinearLayout r = row(); r.setPadding(0, dp(4), 0, dp(4));
        TextView l = tv(label, 13, TXT); r.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        TextView v = tv(val.get(), 13, 0xFF8FE3FF); v.setGravity(Gravity.CENTER); v.setTypeface(Typeface.DEFAULT_BOLD); r.addView(v, new LinearLayout.LayoutParams(dp(84), -2));
        r.addView(button("−", 0, x -> { op.step(-1); App.save(this); v.setText(val.get()); }), new LinearLayout.LayoutParams(dp(44), dp(40)));
        r.addView(button("+", 0, x -> { op.step(1); App.save(this); v.setText(val.get()); }), new LinearLayout.LayoutParams(dp(44), dp(40)));
        return r;
    }
    View toggle(String label, boolean on, On cb) {
        Switch s = new Switch(this); s.setText(label); s.setTextColor(TXT); s.setTextSize(13); s.setChecked(on);
        s.setPadding(0, dp(8), 0, dp(8));
        int[][] st = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new android.content.res.ColorStateList(st, new int[]{Ui.CYAN, 0xFFB0ACC8}));
        s.setTrackTintList(new android.content.res.ColorStateList(st, new int[]{0x998B5CF6, 0x553A3555}));
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

    LinearLayout card(String title) {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackground(Ui.glass(this, 18, false));
        LinearLayout tr = row(); tr.setPadding(0, 0, 0, dp(8));
        View accent = new View(this); accent.setBackground(Ui.pill(this, PURPLE, Ui.CYAN, 2));
        tr.addView(accent, new LinearLayout.LayoutParams(dp(3), dp(16)));
        TextView t = tv(title, 15, TXT); t.setTypeface(Typeface.DEFAULT_BOLD); t.setPadding(dp(8), 0, 0, 0); tr.addView(t);
        c.addView(tr);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(12);
        root.addView(c, lp);
        return c;
    }
    LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    TextView tv(String s, int sp, int col) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(col); return t; }
    /** col PURPLE = primary (purple gradient), BLUE = accent (purple → cyan), 0 = glass, anything else = that colour. */
    Button button(String s, int col, View.OnClickListener l) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(14);
        b.setBackground(col == PURPLE ? Ui.pill(this, 0xFF7C3AED, PURPLE, 14) : col == BLUE ? Ui.pill(this, PURPLE, 0xFF0EA5E9, 14)
                : col == 0 ? Ui.glass(this, 14, true) : solid(col));
        b.setStateListAnimator(null);
        b.setMinHeight(dp(46)); b.setMinimumHeight(dp(46)); b.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(dp(3), dp(4), dp(3), dp(4)); b.setLayoutParams(lp);
        b.setOnClickListener(l == null ? null : v -> { press(v); l.onClick(v); });
        return b;
    }
    GradientDrawable solid(int col) { GradientDrawable d = new GradientDrawable(); d.setColor(col); d.setCornerRadius(dp(14)); return d; }
    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    static final String SHIZUKU = "moe.shizuku.privileged.api";
    boolean shizukuInstalled() { try { getPackageManager().getPackageInfo(SHIZUKU, 0); return true; } catch (Exception e) { return false; } }
    /** Install Shizuku here (download + signature check, Android confirms) or from the Play Store. */
    void getShizuku() {
        new android.app.AlertDialog.Builder(this).setTitle("Get Shizuku")
                .setMessage("Install here: Silly Macro downloads the latest Shizuku from its official GitHub, checks it is really Shizuku "
                        + "(signed by its developer), then Android asks you to confirm the install.\n\nOr get it from the Play Store.")
                .setPositiveButton("Install here", (d, w) -> ShizukuInstall.start(this, m -> Toast.makeText(this, m, Toast.LENGTH_LONG).show()))
                .setNeutralButton("Play Store", (d, w) -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + SHIZUKU))); }
                    catch (Exception e) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases/latest"))); }
                })
                .setNegativeButton("Cancel", null).show();
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
