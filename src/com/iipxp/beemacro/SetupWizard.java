package com.iipxp.beemacro;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * First-run setup, shown after sign-in until it's finished: one page per thing the macro needs, in order, with a live
 * check where the app can see it (key sender, Accessibility, screen capture, hive slot, move speed, Smallest width).
 * Next on an unfinished page asks "continue anyway?". The page is saved, so leaving and coming back resumes there;
 * Settings → Run setup again starts over. Players who already confirmed a hive slot can skip it on the first page.
 */
final class SetupWizard {
    static final String[] TITLES = {"Welcome", "Keyboard + mouse (Shizuku)", "Turn on Silly Macro", "Screen capture", "Your hive",
            "Move speed", "Sprinkler", "Roblox settings", "Discord & private server (optional)", "All set"};
    private final MainActivity a; private final Profile p;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status; private int page;
    private volatile String problem;   // what isn't done on this page (null = done), from the live check
    private java.util.function.Supplier<String> check;   // runs off the UI thread (the key sender check uses a socket)

    SetupWizard(MainActivity a) { this.a = a; this.p = App.profile(a); }

    void show() { open(Math.max(0, Math.min(p.setupStep, TITLES.length - 1))); }

    private void open(int i) {
        page = i; p.setupStep = i; App.save(a);
        check = null; problem = null;
        FrameLayout frame = new FrameLayout(a);
        frame.setBackground(new Ui.HoneyBackground(a)); frame.setFitsSystemWindows(true);
        ScrollView sv = new ScrollView(a); sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(a); col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(a.dp(20), a.dp(28), a.dp(20), a.dp(28));
        sv.addView(col); frame.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        a.setContentView(frame);
        a.root = col;   // the app's card() adds to this

        col.addView(a.tv("Set up Silly Macro · step " + (i + 1) + " of " + TITLES.length, 13, a.DIM));
        LinearLayout bar = new LinearLayout(a);   // progress
        View done = new View(a); done.setBackground(Ui.pill(a, a.PURPLE, Ui.CYAN, 2));
        View left = new View(a); left.setBackgroundColor(Ui.STROKE);
        bar.addView(done, new LinearLayout.LayoutParams(0, a.dp(4), i + 1)); bar.addView(left, new LinearLayout.LayoutParams(0, a.dp(4), TITLES.length - i - 1));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, a.dp(4)); blp.topMargin = a.dp(6); col.addView(bar, blp);
        TextView title = a.tv(TITLES[i], 24, a.TXT); title.setTypeface(Typeface.DEFAULT_BOLD); title.setPadding(0, a.dp(14), 0, a.dp(4));
        col.addView(title);

        body(i, col);

        status = a.tv("", 14, a.DIM); status.setPadding(0, a.dp(14), 0, a.dp(6));
        col.addView(status);
        LinearLayout nav = a.row(); nav.setPadding(0, a.dp(8), 0, 0);
        if (i > 0) nav.addView(a.button("Back", 0, v -> open(page - 1)), new LinearLayout.LayoutParams(0, a.dp(52), 1));
        boolean last = i == TITLES.length - 1;
        nav.addView(a.button(last ? "Finish" : i == 8 ? "Next (or skip)" : "Next", a.PURPLE, v -> next()), new LinearLayout.LayoutParams(0, a.dp(52), 1));
        col.addView(nav);
        live();
    }

    /** Live check every second while this page is open. */
    private void live() {
        int mine = page;
        Runnable tick = new Runnable() {
            @Override public void run() {
                if (page != mine || a.isFinishing()) return;
                java.util.function.Supplier<String> c = check;
                if (c == null) { status.setText(""); return; }
                new Thread(() -> {
                    String why = c.get(); problem = why;
                    ui.post(() -> { if (page != mine) return; status.setText(why == null ? "✓ Done" : "⚠ " + why); status.setTextColor(why == null ? Ui.GREEN : Ui.GOLD); });
                }, "setup-check").start();
                ui.postDelayed(this, 1000);
            }
        };
        ui.post(tick);
    }

    private void next() {
        if (page == TITLES.length - 1) { finish(); return; }
        String why = check == null ? null : problem;
        if (why == null) { open(page + 1); return; }
        if (page == 7 && ScreenWidth.needed(a)) {   // required: the HUD can't be read at another width
            new android.app.AlertDialog.Builder(a).setTitle("Required").setMessage(why + "\n\nThe macro can't read the game at any other width.").setPositiveButton("OK", null).show();
            return;
        }
        Log.add("setup: continued past \"" + TITLES[page] + "\" without: " + why);
        new android.app.AlertDialog.Builder(a).setTitle("Not done yet").setMessage(why + "\n\nThe macro may not work until this is done. Continue anyway?")
                .setPositiveButton("Continue anyway", (d, w) -> open(page + 1)).setNegativeButton("Stay", null).show();
    }

    private void finish() {
        p.setupDone = true; p.setupStep = 0; App.save(a);
        Log.add("setup: finished");
        a.recreate();
    }

    private void text(LinearLayout col, String s) { TextView t = a.tv(s, 15, a.TXT); t.setPadding(0, a.dp(6), 0, a.dp(6)); t.setLineSpacing(0, 1.15f); col.addView(t); }
    private Button btn(LinearLayout col, String s, int c, View.OnClickListener l) { Button b = a.button(s, c, l); col.addView(b, new LinearLayout.LayoutParams(-1, a.dp(52))); return b; }

    private void body(int i, LinearLayout col) {
        switch (i) {
            case 0:
                text(col, "A few minutes, once. You need:\n• Android 13 or newer\n• Shizuku (free, Play Store): it runs the keyboard + mouse the macro uses\n• Bee Swarm Simulator\n\nEach step checks itself where it can. You can come back to any step later: Settings → Run setup again.");
                if (p.hiveConfirmed) btn(col, "Skip setup (I've set Silly Macro up before)", 0, v -> { Log.add("setup: skipped by an existing player"); finish(); });
                break;
            case 1:
                text(col, "1. Install Shizuku.\n2. Settings → About phone → Software information → tap Build number 7 times (Developer options).\n3. Developer options → Wireless debugging → On (Wi-Fi needed).\n4. Shizuku → Pairing, then Wireless debugging → Pair device with pairing code, type the code into Shizuku's notification.\n5. Shizuku → Start.\n6. Then press the button below and allow Silly Macro in Shizuku's popup (Allow all the time).\n\nAfter every phone restart: Shizuku → Start, then Home step 2.");
                if (!a.shizukuInstalled()) btn(col, "Get Shizuku", 0, v -> a.getShizuku());
                btn(col, "Start the keyboard + mouse", a.PURPLE, v -> {
                    a.toast("Starting the keyboard + mouse…");
                    new Thread(() -> { String m = ShizukuKeys.start(a); Log.add("shizuku: " + m); a.runOnUiThread(() -> a.toast(m)); }).start();
                });
                check = () -> !a.shizukuInstalled() ? "Shizuku isn't installed" : Keys.connect(a) && Keys.ping() ? null : "The keyboard + mouse isn't running yet: start Shizuku, then press the button";
                break;
            case 2:
                text(col, "Silly Macro uses an Accessibility service for its floating panel (Start / Stop / emergency stop). Open Accessibility, tap Silly Macro, switch it on and tap Allow.\n\nSwitch greyed out (\"Controlled by Restricted setting\")? Tap App info below → ⋮ (top right) → Allow restricted settings, then try again.");
                btn(col, "Open Accessibility", a.PURPLE, v -> a.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
                btn(col, "App info (Allow restricted settings)", 0, v -> a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.getPackageName()))));
                check = () -> MacroService.inst != null ? null : "Silly Macro isn't switched on in Accessibility yet";
                break;
            case 3:
                text(col, "The macro reads Bee Swarm from the screen. Press the button and pick \"Entire screen\" (not \"A single app\": that gives a black screen).\n\nAfter every update of Silly Macro, allow it again (Home step 3).");
                btn(col, "Allow screen capture", a.PURPLE, v -> a.requestCapture());
                check = () -> CaptureService.inst != null ? null : "Screen capture isn't on yet";
                break;
            case 4:
                text(col, "Which hive slot do you use? Slot 3 (straight ahead of the spawn) is the most tested. Pick it with − / +, then Confirm.");
                TextView hs = a.tv("", 18, a.TXT); hs.setTypeface(Typeface.DEFAULT_BOLD); hs.setGravity(Gravity.CENTER); col.addView(hs);
                Runnable show = () -> hs.setText("Slot " + p.hiveSlot + (p.hiveConfirmed ? "  ✔ confirmed" : ""));
                show.run();
                LinearLayout r = a.row();
                r.addView(a.button("−", 0, v -> { p.hiveSlot = Math.max(1, p.hiveSlot - 1); p.hiveConfirmed = false; App.save(a); show.run(); }), new LinearLayout.LayoutParams(0, a.dp(52), 1));
                r.addView(a.button("+", 0, v -> { p.hiveSlot = Math.min(6, p.hiveSlot + 1); p.hiveConfirmed = false; App.save(a); show.run(); }), new LinearLayout.LayoutParams(0, a.dp(52), 1));
                r.addView(a.button("Confirm", a.PURPLE, v -> { p.hiveConfirmed = true; App.save(a); Log.add("hive: slot " + p.hiveSlot + " confirmed in setup"); show.run(); }), new LinearLayout.LayoutParams(0, a.dp(52), 2));
                col.addView(r);
                col.addView(a.toggle("If this hive is taken, use the nearest free one (3 first)", p.autoHiveSlot, on -> p.autoHiveSlot = on));
                check = () -> p.hiveConfirmed ? null : "Confirm your hive slot";
                break;
            case 5:
                text(col, "Type your movement speed from Bee Swarm: the stats menu (your bee icon → Stats) shows \"Movement Speed\". Use the number without Haste. The macro walks by distance, so a wrong number ends short or long (wrong field).");
                android.widget.EditText ms = new android.widget.EditText(a);
                ms.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
                ms.setTextColor(a.TXT); ms.setTextSize(20); ms.setHint("e.g. 29.9");
                if (p.moveSpeedTyped) ms.setText(String.format(java.util.Locale.US, "%.2f", p.moveSpeed));
                ms.addTextChangedListener(new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence c, int x, int y, int z) { }
                    public void onTextChanged(CharSequence c, int x, int y, int z) { }
                    public void afterTextChanged(android.text.Editable e) {
                        try { double v = Double.parseDouble(e.toString().trim().replace(',', '.')); if (v >= 10 && v <= 100) { p.moveSpeed = Math.round(v * 100) / 100.0; p.moveSpeedTyped = true; App.save(a); } } catch (NumberFormatException ignored) { }
                    }
                });
                col.addView(ms);
                check = () -> p.moveSpeedTyped ? null : "Type your movement speed (a number between 10 and 100)";
                break;
            case 6:
                text(col, "Which sprinkler do you have, and in which hotbar slot (the number key) is it? \"None\" if you don't have one.");
                Button st = a.button("Sprinkler: " + p.sprinklerType, 0, null);
                st.setOnClickListener(v -> { int k = java.util.Arrays.asList(Profile.SPRINKLERS).indexOf(p.sprinklerType); p.sprinklerType = Profile.SPRINKLERS[(k + 1) % Profile.SPRINKLERS.length]; App.save(a); st.setText("Sprinkler: " + p.sprinklerType); });
                col.addView(st, new LinearLayout.LayoutParams(-1, a.dp(52)));
                col.addView(a.stepper("Sprinkler hotbar slot (key)", () -> "" + p.sprinklerSlot, d -> p.sprinklerSlot = MainActivity.clamp(p.sprinklerSlot + d, 1, 7)));
                break;
            case 7: {
                int sw = ScreenWidth.current(a);
                boolean tab = ScreenWidth.tablet(a);
                text(col, "In Roblox (in the game: menu → Settings):\n• Camera Mode → Classic\n• Shift Lock Switch → On\n\n"
                        + (tab ? "Recommended on tablets: Smallest width " + ScreenWidth.target(a) + " dp, the Galaxy Tab S9 FE's (2304 × 1440 at 280 dpi), so Roblox draws its HUD like on the tablet the macro was measured on. Yours is now " + sw + " dp"
                                 + (sw == ScreenWidth.target(a) ? " ✓" : ". Tap Set it for me (uses the key sender from step 1), then close and reopen Roblox.")
                               : "REQUIRED on phones: Smallest width 360 (Developer options; Samsung: Minimum width). Yours is now " + sw + " dp"
                                 + (sw == ScreenWidth.TARGET ? " ✓" : ". Tap Set it for me (uses the key sender from step 1), then close and reopen Roblox.")));
                if (sw != ScreenWidth.target(a)) {
                    int tw = ScreenWidth.target(a);
                    btn(col, "Set it for me (" + tw + ")", 0, v -> new Thread(() -> {
                        String why = ScreenWidth.apply(a);
                        a.runOnUiThread(() -> { if (why != null) a.toast(why); else { a.toast("Smallest width set to " + tw + ". Close and reopen Roblox."); open(page); } });
                    }).start());
                    btn(col, "Open Developer options", 0, v -> {
                        try { a.startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); } catch (Exception e) { a.toast("Turn on Developer options first (Build number ×7)"); }
                    });
                }
                check = () -> ScreenWidth.needed(a) ? "Smallest width is " + ScreenWidth.current(a) + " dp: it must be 360 (tap Set it for me)"
                        : ScreenWidth.off(a) ? "Smallest width is " + ScreenWidth.current(a) + " dp: " + ScreenWidth.target(a) + " (Tab S9 FE) is recommended (tap Set it for me)" : null;
                break;
            }
            case 8: {
                text(col, "Both are optional: Next skips them. You can add them later in Settings.");
                LinearLayout w = a.card("Discord webhook"); a.webhookEditor(w);
                LinearLayout s = a.card("Private server link"); a.serverLinkEditor(s);
                break;
            }
            default:
                text(col, "That's it. Open Bee Swarm sideways, wait a few seconds, and press ▶ Start on the floating panel.\n\nStuck later? Settings → Debug → Send report to the developer, or the popular fixes on Discord.");
        }
    }
}
