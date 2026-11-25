package com.iipxp.beemacro;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/** Silly Macro's look: restrained and dark by default (near-black, soft white type, hairline cards, pill buttons, one
 *  quiet accent), with themes switched live (MainActivity.recreate, no app restart): Refined (default), Dark (true
 *  black), Light, Red Hive and a custom accent. Motion is transform/opacity or a canvas redraw with a strong ease-out;
 *  Settings -> Animations: Full, Reduced (no looping/ambient motion) or Off; Android's own animation switch wins. */
final class Ui {
    // Palette: set by apply(). PURPLE/CYAN kept as names for "accent" / "accent, lighter" so older code reads the theme.
    static int BG0, BG1, GLASS, GLASS_HI, STROKE, PURPLE, CYAN, PINK = 0xFFE879F9, GOLD, GREEN, RED, TXT, DIM, FAINT, CHIP,
            PRIMARY, ON_PRIMARY, RAIL;
    static boolean light, particles;
    static String theme = "default";
    /** 0 off, 1 reduced (transitions only), 2 full. */
    static int motionLevel = 2;
    static final String[] THEMES = {"default", "dark", "light", "red", "custom"};
    static final String[] THEME_NAMES = {"Refined", "Dark", "Light", "Red Hive", "Custom accent"};
    /** Accent choices for the custom theme (Settings shows them as swatches). */
    static final int[] ACCENTS = {0xFF6E8BFF, 0xFF4FD1C5, 0xFFA78BFA, 0xFFF59E0B, 0xFFF472B6, 0xFF34D399, 0xFFE5484D, 0xFFE6E6E6};
    static { apply("default", 0, 2); }

    /** Sets the palette. accent is used by "custom" (0 = the default blue). */
    static void apply(String t, int accent, int motion) {
        theme = t == null ? "default" : t; motionLevel = motion;
        light = theme.equals("light"); particles = theme.equals("default") || theme.equals("red") || theme.equals("custom");
        if (light) {
            BG0 = 0xFFF4F4F6; BG1 = 0xFFFAFAFB; GLASS = 0xFFFFFFFF; GLASS_HI = 0xFFF0F0F3; STROKE = 0x16000000; RAIL = 0xFFFFFFFF;
            TXT = 0xFF111114; DIM = 0xFF5F6068; FAINT = 0xFF9A9BA3; CHIP = 0x0F000000;
            GOLD = 0xFFB45309; GREEN = 0xFF15803D; RED = 0xFFDC2626; PURPLE = 0xFF3B5BDB; CYAN = 0xFF5C7CFA;
            PRIMARY = 0xFF111114; ON_PRIMARY = 0xFFFFFFFF;
        } else {
            boolean black = theme.equals("dark");
            BG0 = black ? 0xFF000000 : 0xFF0A0A0C; BG1 = black ? 0xFF000000 : 0xFF101115;
            GLASS = black ? 0xFF0D0D0F : 0xF2141519; GLASS_HI = black ? 0xFF161618 : 0xFF1C1D22; STROKE = 0x17FFFFFF; RAIL = black ? 0xFF000000 : 0xFF0C0C0F;
            TXT = 0xFFF2F2F3; DIM = 0xFF9A9BA3; FAINT = 0xFF5E5F66; CHIP = 0x14FFFFFF;
            GOLD = 0xFFF5C451; GREEN = 0xFF4ADE80; RED = 0xFFF87171;
            int a = theme.equals("red") ? 0xFFE5484D : theme.equals("custom") && accent != 0 ? accent : black ? 0xFFC9CCD3 : 0xFF6E8BFF;
            PURPLE = a; CYAN = mix(a, 0xFFFFFFFF, 0.35f);
            boolean accentButton = theme.equals("red") || theme.equals("custom");
            PRIMARY = accentButton ? a : 0xFFF2F2F3; ON_PRIMARY = accentButton && lum(a) < 0.6 ? 0xFFFFFFFF : 0xFF0A0A0C;
        }
    }
    static void apply(Profile p) { apply(p.theme, p.accentColor, p.motionLevel); }
    static int mix(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t), Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t), Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }
    static double lum(int c) { return (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255; }

    static final Interpolator EASE_OUT = new PathInterpolator(0.23f, 1f, 0.32f, 1f);
    static final Interpolator EASE_IN_OUT = new PathInterpolator(0.77f, 0f, 0.175f, 1f);
    static boolean motion() { return motionLevel > 0 && ValueAnimator.areAnimatorsEnabled(); }
    /** Looping / ambient motion (pulsing dot): Full only. */
    static boolean ambient() { return motionLevel >= 2 && motion(); }

    static GradientDrawable glass(Context c, float radiusDp, boolean hi) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(hi ? GLASS_HI : GLASS); d.setCornerRadius(dp(c, radiusDp)); d.setStroke(Math.max(1, dp(c, 1)), STROKE);
        return d;
    }
    static GradientDrawable pill(Context c, int from, int to, float radiusDp) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{from, to});
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }
    static GradientDrawable solid(Context c, int col, float radiusDp) { GradientDrawable d = new GradientDrawable(); d.setColor(col); d.setCornerRadius(dp(c, radiusDp)); return d; }
    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    /** Page background: the theme's near-flat base, one very soft accent light in the top corner and a sparse field
     *  of faint particles (reference: dark cards with subtle blue particles). Rendered once per size into a bitmap. */
    static final class HoneyBackground extends Drawable {
        private final Paint fill = new Paint(), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float d;
        HoneyBackground(Context c) { d = c.getResources().getDisplayMetrics().density; }
        private android.graphics.Bitmap cache;
        @Override public void draw(Canvas out) {
            int w = getBounds().width(), h = getBounds().height();
            if (w <= 0 || h <= 0) return;
            if (cache == null || cache.getWidth() != w || cache.getHeight() != h) {
                if (cache != null) cache.recycle();
                cache = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
                render(new Canvas(cache), w, h);
            }
            out.drawBitmap(cache, getBounds().left, getBounds().top, null);
        }
        private void render(Canvas cv, int w, int h) {
            fill.setShader(new LinearGradient(0, 0, 0, h, BG1, BG0, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, fill);
            float r = Math.max(w, h) * 0.55f;
            fill.setShader(new android.graphics.RadialGradient(w * 0.9f, -h * 0.05f, r, alpha(PURPLE, light ? 0.06f : 0.09f), 0, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, fill);
            if (!particles) return;
            java.util.Random rnd = new java.util.Random(7);            // same pattern every time: no flicker between rebuilds
            int n = (int) (w * h / (d * d) / 2600);
            for (int i = 0; i < n; i++) {
                float x = rnd.nextFloat() * w, y = rnd.nextFloat() * h * 0.7f, s = (0.6f + rnd.nextFloat() * 1.1f) * d;
                dot.setColor(alpha(rnd.nextInt(4) == 0 ? 0xFFFFFFFF : CYAN, (0.05f + 0.16f * rnd.nextFloat()) * (1 - y / (h * 0.7f))));
                cv.drawCircle(x, y, s, dot);
            }
        }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    /** Thin gradient progress bar that eases to its new value. */
    static final class Bar extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float shown, target; private ValueAnimator anim;
        Bar(Context c) { super(c); track.setColor(light ? 0x14000000 : 0x1FFFFFFF); }
        void set(float frac) {
            frac = Math.max(0, Math.min(1, frac));
            if (Math.abs(frac - target) < 0.002f) return;
            target = frac;
            if (anim != null) anim.cancel();
            if (!motion()) { shown = frac; invalidate(); return; }
            anim = ValueAnimator.ofFloat(shown, frac); anim.setDuration(450); anim.setInterpolator(EASE_OUT);
            anim.addUpdateListener(a -> { shown = (float) a.getAnimatedValue(); invalidate(); }); anim.start();
        }
        @Override protected void onDraw(Canvas c) {
            float h = getHeight(), w = getWidth(), r = h / 2;
            c.drawRoundRect(new RectF(0, 0, w, h), r, r, track);
            fill.setShader(new LinearGradient(0, 0, w, 0, PURPLE, CYAN, Shader.TileMode.CLAMP));
            if (shown > 0) c.drawRoundRect(new RectF(0, 0, Math.max(h, w * shown), h), r, r, fill);
        }
    }

    /** Small dot that pulses while "live" (status / AI scanning). */
    static final class Pulse extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); private float t; private ValueAnimator a; private int color = GREEN;
        Pulse(Context c) { super(c); }
        void set(boolean live, int col) {
            color = col; invalidate();
            if (live && a == null && ambient()) {
                a = ValueAnimator.ofFloat(0, 1); a.setDuration(1400); a.setRepeatCount(ValueAnimator.INFINITE);
                a.addUpdateListener(v -> { t = (float) v.getAnimatedValue(); invalidate(); }); a.start();
            } else if (!live && a != null) { a.cancel(); a = null; t = 0; invalidate(); }
        }
        @Override protected void onDetachedFromWindow() { if (a != null) a.cancel(); a = null; super.onDetachedFromWindow(); }
        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) * 0.45f;
            if (a != null) { p.setColor(color); p.setAlpha((int) (110 * (1 - t))); c.drawCircle(cx, cy, r + (Math.min(cx, cy) - r) * t, p); }
            p.setColor(color); p.setAlpha(255); c.drawCircle(cx, cy, r, p);
        }
    }

    /** Bar or line chart of a short series; grows in once when the values arrive. */
    static final class Chart extends View {
        private double[] v = new double[0]; private String[] labels = new String[0]; private boolean line; private float grow = 1; private ValueAnimator a;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint(), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float d;
        Chart(Context c, boolean line) {
            super(c); this.line = line; d = c.getResources().getDisplayMetrics().density;
            text.setColor(DIM); text.setTextSize(10 * d); text.setTextAlign(Paint.Align.CENTER);
            grid.setColor(light ? 0x14000000 : 0x1AFFFFFF); stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeWidth(2.5f * d); stroke.setStrokeCap(Paint.Cap.ROUND);
        }
        void set(double[] values, String[] lbl) {
            boolean changed = !java.util.Arrays.equals(values, v);
            v = values; labels = lbl;
            if (changed && motion() && getWidth() > 0) {
                if (a != null) a.cancel();
                a = ValueAnimator.ofFloat(0, 1); a.setDuration(600); a.setInterpolator(EASE_OUT);
                a.addUpdateListener(x -> { grow = (float) x.getAnimatedValue(); invalidate(); }); a.start();
            } else invalidate();
        }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), bottom = h - 16 * d, top = 8 * d;
            for (int i = 0; i <= 3; i++) { float y = top + (bottom - top) * i / 3f; c.drawLine(0, y, w, y, grid); }
            if (v.length == 0) { text.setTextAlign(Paint.Align.CENTER); c.drawText("No data yet", w / 2, h / 2, text); return; }
            double max = 0; for (double x : v) max = Math.max(max, x);
            if (max <= 0) { c.drawText("No data yet", w / 2, h / 2, text); return; }   // e.g. sessions too short to measure honey
            float step = w / v.length;
            fill.setShader(new LinearGradient(0, bottom, 0, top, PURPLE, CYAN, Shader.TileMode.CLAMP));
            stroke.setShader(new LinearGradient(0, 0, w, 0, PURPLE, CYAN, Shader.TileMode.CLAMP));
            Path p = new Path();
            for (int i = 0; i < v.length; i++) {
                float x = step * i + step / 2, y = bottom - (float) (v[i] / max) * (bottom - top) * grow;
                if (line) { if (i == 0) p.moveTo(x, y); else p.lineTo(x, y); }
                else c.drawRoundRect(new RectF(x - step * 0.3f, y, x + step * 0.3f, bottom), 4 * d, 4 * d, fill);
                if (i < labels.length && (v.length <= 8 || i % 2 == 0)) c.drawText(labels[i], x, h - 3 * d, text);
            }
            if (line) c.drawPath(p, stroke);
        }
    }

    static int alpha(int col, float a) { return Color.argb(Math.round(255 * a), Color.red(col), Color.green(col), Color.blue(col)); }
}
