package com.iipxp.sillyadmin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.iipxp.beemacro.Fb;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Silly Admin: sign in with the admin account, see every Silly Macro user, disable / enable them with a reason.
 * Only the admin UID in firestore.rules may list users or change "disabled"; anyone else gets PERMISSION_DENIED.
 */
public class AdminActivity extends Activity {
    static final int BG = 0xFF0B0816, CARD = 0xFF17122B, TXT = 0xFFF1EEFF, DIM = 0xFF9C95C0, PURPLE = 0xFF8B5CF6, RED = 0xFFF43F5E, GREEN = 0xFF34D399, GOLD = 0xFFFFC233;
    Fb fb; SharedPreferences sp;
    LinearLayout list; TextView status; EditText search;
    final List<JSONObject> users = new ArrayList<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        sp = getSharedPreferences("admin", MODE_PRIVATE);
        try (java.io.InputStream in = getAssets().open("firebase.properties")) {
            java.util.Properties p = new java.util.Properties(); p.load(in);
            fb = new Fb(p.getProperty("apiKey", "").trim(), p.getProperty("projectId", "").trim());
        } catch (Exception e) { setContentView(tv("Missing firebase.properties in this build.", 16, RED)); return; }
        if (sp.getString("refresh", "").isEmpty()) showLogin(); else showUsers();
    }

    LinearLayout screen(String title) {
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(BG); sv.setFitsSystemWindows(true);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(16), dp(20), dp(16), dp(40));
        sv.addView(col); setContentView(sv);
        TextView t = tv(title, 24, TXT); t.setTypeface(Typeface.DEFAULT_BOLD); col.addView(t);
        return col;
    }

    void showLogin() {
        LinearLayout col = screen("Silly Admin");
        col.addView(tv("Sign in with the admin account (the one whose UID is in firestore.rules).", 13, DIM));
        EditText email = edit("Admin email", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS); col.addView(email);
        EditText pw = edit("Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); col.addView(pw);
        TextView msg = tv("", 13, GOLD); col.addView(msg);
        col.addView(button("Sign in", PURPLE, v -> {
            msg.setText("Signing in…");
            String e = email.getText().toString().trim(), w = pw.getText().toString();
            new Thread(() -> {
                try { Fb.User u = fb.signIn(e, w); sp.edit().putString("refresh", u.refresh).putString("uid", u.uid).putString("email", u.email).apply(); runOnUiThread(this::showUsers); }
                catch (Fb.Error x) { runOnUiThread(() -> msg.setText(Fb.explain(x.code))); }
            }).start();
        }));
    }

    void showUsers() {
        LinearLayout col = screen("Users");
        col.addView(tv("Signed in as " + sp.getString("email", "") + "\nYour UID: " + sp.getString("uid", ""), 12, DIM));
        LinearLayout r = new LinearLayout(this);
        r.addView(button("Refresh", PURPLE, v -> load()), new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(button("Reports", GOLD, v -> showReports()), new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(button("Sign out", CARD, v -> { sp.edit().clear().apply(); showLogin(); }), new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(r);
        search = edit("Search email or device", InputType.TYPE_CLASS_TEXT);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { draw(); }
        });
        col.addView(search);
        status = tv("", 13, DIM); col.addView(status);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); col.addView(list);
        load();
    }

    /** A fresh ID token from the stored refresh token (they last an hour). */
    String token() throws Fb.Error {
        Fb.User u = fb.refresh(sp.getString("refresh", ""));
        sp.edit().putString("refresh", u.refresh).apply();
        return u.idToken;
    }

    void load() {
        status.setText("Loading…");
        new Thread(() -> {
            try {
                List<JSONObject> got = fb.list(token(), "users");
                got.sort((a, c) -> Fb.str(c, "lastSeen").compareTo(Fb.str(a, "lastSeen")));
                runOnUiThread(() -> { users.clear(); users.addAll(got); draw(); });
            } catch (Fb.Error x) {
                runOnUiThread(() -> status.setText(x.code.equals("PERMISSION_DENIED")
                        ? "Not allowed: this account isn't the admin. Put this UID in firestore.rules and publish them:\n" + sp.getString("uid", "")
                        : Fb.explain(x.code)));
            }
        }).start();
    }

    void draw() {
        list.removeAllViews();
        String q = search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
        int off = 0, shown = 0;
        for (JSONObject u : users) {
            if (Fb.bool(u, "disabled")) off++;
            String email = Fb.str(u, "email"), dev = Fb.str(u, "device");
            if (!q.isEmpty() && !(email + " " + dev).toLowerCase(java.util.Locale.ROOT).contains(q)) continue;
            shown++;
            list.addView(row(u));
        }
        status.setText(users.size() + " users · " + off + " disabled" + (q.isEmpty() ? "" : " · " + shown + " match"));
    }

    View row(JSONObject u) {
        String name = u.optString("name"), uid = name.substring(name.lastIndexOf('/') + 1);
        boolean off = Fb.bool(u, "disabled");
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(CARD); bg.setCornerRadius(dp(14)); bg.setStroke(dp(1), off ? RED : 0x22FFFFFF); c.setBackground(bg);
        TextView e = tv(Fb.str(u, "email"), 15, TXT); e.setTypeface(Typeface.DEFAULT_BOLD); c.addView(e);
        c.addView(tv(Fb.str(u, "device") + " · v" + Fb.str(u, "version") + "\nLast seen " + Fb.str(u, "lastSeen").replace('T', ' ').replace("Z", " UTC") + "\nUID " + uid, 12, DIM));
        if (off) c.addView(tv("DISABLED" + (Fb.str(u, "reason").isEmpty() ? "" : ": " + Fb.str(u, "reason")), 13, RED));
        c.addView(off ? button("Enable", GREEN, v -> set(uid, false, ""))
                      : button("Disable", RED, v -> askReason(uid, Fb.str(u, "email"))));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(10); c.setLayoutParams(lp);
        return c;
    }

    void askReason(String uid, String email) {
        EditText reason = edit("Reason (shown to the user)", InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this).setTitle("Disable " + email + "?")
                .setMessage("Their macro stops within a few minutes and they're signed out.").setView(reason)
                .setPositiveButton("Disable", (d, w) -> set(uid, true, reason.getText().toString().trim()))
                .setNegativeButton("Cancel", null).show();
    }

    void set(String uid, boolean disabled, String reason) {
        status.setText(disabled ? "Disabling…" : "Enabling…");
        new Thread(() -> {
            try {
                JSONObject f = new JSONObject();
                f.put("disabled", Fb.b(disabled)); f.put("reason", Fb.s(reason)); f.put("changedAt", Fb.now()); f.put("changedBy", Fb.s(sp.getString("email", "")));
                fb.patch(token(), "users/" + uid, f);
                runOnUiThread(this::load);
            } catch (Fb.Error x) { runOnUiThread(() -> status.setText(Fb.explain(x.code))); }
            catch (org.json.JSONException x) { runOnUiThread(() -> status.setText("error")); }
        }).start();
    }

    // ---- reports ("Send report to the developer" in Silly Macro: debug log + one Honey/Pollen counter screenshot)
    void showReports() {
        LinearLayout col = screen("Reports");
        LinearLayout r = new LinearLayout(this);
        r.addView(button("Refresh", PURPLE, v -> showReports()), new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(button("Users", CARD, v -> showUsers()), new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(r);
        TextView st = tv("Loading…", 13, DIM); col.addView(st);
        LinearLayout rl = new LinearLayout(this); rl.setOrientation(LinearLayout.VERTICAL); col.addView(rl);
        new Thread(() -> {
            try {
                String t = token();
                List<JSONObject> got = fb.list(t, "reports", "uid", "createdAt", "version", "device", "android", "logLines");
                got.sort((a, c) -> Fb.str(c, "createdAt").compareTo(Fb.str(a, "createdAt")));
                java.util.Map<String, String> emails = new java.util.HashMap<>();
                for (JSONObject u : users) { String n = u.optString("name"); emails.put(n.substring(n.lastIndexOf('/') + 1), Fb.str(u, "email")); }
                runOnUiThread(() -> {
                    st.setText(got.size() + " reports, newest first");
                    for (JSONObject d : got) {
                        String uid = Fb.str(d, "uid"), who = emails.containsKey(uid) ? emails.get(uid) : "UID " + uid;
                        LinearLayout c = card();
                        TextView e = tv(who, 15, TXT); e.setTypeface(Typeface.DEFAULT_BOLD); c.addView(e);
                        c.addView(tv(Fb.str(d, "device") + " · Android " + Fb.str(d, "android") + " · v" + Fb.str(d, "version") + "\n"
                                + Fb.str(d, "createdAt").replace('T', ' ').replace("Z", " UTC") + " · " + Fb.num(d, "logLines") + " log lines", 12, DIM));
                        c.setOnClickListener(v -> openReport(d.optString("name"), who));
                        rl.addView(c);
                    }
                });
            } catch (Fb.Error x) { runOnUiThread(() -> st.setText(Fb.explain(x.code))); }
        }).start();
    }

    void openReport(String name, String who) {
        LinearLayout col = screen("Report");
        col.addView(tv(who, 14, TXT));
        TextView st = tv("Loading…", 13, DIM); col.addView(st);
        new Thread(() -> {
            try {
                JSONObject d = fb.get(token(), name.substring(name.indexOf("/documents/") + 11));
                runOnUiThread(() -> {
                    if (d == null) { st.setText("Already deleted."); return; }
                    st.setText(Fb.str(d, "device") + " · Android " + Fb.str(d, "android") + " · v" + Fb.str(d, "version") + "\n" + Fb.str(d, "createdAt").replace('T', ' ').replace("Z", " UTC"));
                    byte[] img = Fb.bytesOf(d, "image");
                    android.graphics.Bitmap bm = img == null ? null : android.graphics.BitmapFactory.decodeByteArray(img, 0, img.length);
                    if (bm != null) { android.widget.ImageView iv = new android.widget.ImageView(this); iv.setImageBitmap(bm); iv.setAdjustViewBounds(true); col.addView(iv, new LinearLayout.LayoutParams(-1, -2)); }
                    else col.addView(tv("(no screenshot)", 12, DIM));
                    String log = Fb.str(d, "log");
                    LinearLayout r = new LinearLayout(this);
                    r.addView(button("Copy log", PURPLE, v -> {
                        ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("Silly Macro log", log));
                        android.widget.Toast.makeText(this, "Log copied", android.widget.Toast.LENGTH_SHORT).show();
                    }), new LinearLayout.LayoutParams(0, -2, 1));
                    r.addView(button("Delete", RED, v -> new AlertDialog.Builder(this).setTitle("Delete this report?")
                            .setPositiveButton("Delete", (dd, w) -> new Thread(() -> {
                                try { fb.delete(token(), name); runOnUiThread(this::showReports); }
                                catch (Fb.Error x) { runOnUiThread(() -> st.setText(Fb.explain(x.code))); }
                            }).start()).setNegativeButton("Cancel", null).show()), new LinearLayout.LayoutParams(0, -2, 1));
                    r.addView(button("Back", CARD, v -> showReports()), new LinearLayout.LayoutParams(0, -2, 1));
                    col.addView(r);
                    TextView lt = tv(log, 11, TXT); lt.setTypeface(Typeface.MONOSPACE); lt.setTextIsSelectable(true); col.addView(lt);
                });
            } catch (Fb.Error x) { runOnUiThread(() -> st.setText(Fb.explain(x.code))); }
        }).start();
    }
    LinearLayout card() {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(CARD); bg.setCornerRadius(dp(14)); bg.setStroke(dp(1), 0x22FFFFFF); c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(10); c.setLayoutParams(lp);
        return c;
    }

    // ---- views
    TextView tv(String s, int sp, int col) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(col); t.setPadding(0, dp(4), 0, dp(4)); return t; }
    EditText edit(String hint, int type) { EditText e = new EditText(this); e.setHint(hint); e.setInputType(type); e.setSingleLine(true); e.setTextColor(TXT); e.setHintTextColor(DIM); return e; }
    Button button(String s, int col, View.OnClickListener l) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextColor(Color.WHITE);
        GradientDrawable d = new GradientDrawable(); d.setColor(col); d.setCornerRadius(dp(12)); b.setBackground(d);
        b.setOnClickListener(l); b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(dp(3), dp(6), dp(3), dp(4)); b.setLayoutParams(lp);
        return b;
    }
    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
