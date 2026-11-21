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

/** Silly Macro's look: midnight-purple glass, purple + cyan accents, a faint honeycomb, and a few small animated views.
 *  Motion is transform/opacity or a canvas redraw, strong ease-out, and off when Android's animations are off. */
final class Ui {
    static final int BG0 = 0xFF07060D, BG1 = 0xFF140B2A, GLASS = 0xB3171230, GLASS_HI = 0xCC211845, STROKE = 0x557C5CFF,
            PURPLE = 0xFF8B5CF6, CYAN = 0xFF22D3EE, PINK = 0xFFE879F9, GOLD = 0xFFFFC233, GREEN = 0xFF34D399, RED = 0xFFF43F5E,
            TXT = 0xFFEDEBFA, DIM = 0xFF9C97B8, FAINT = 0xFF6B6690, CHIP = 0x33211845;
    static final Interpolator EASE_OUT = new PathInterpolator(0.23f, 1f, 0.32f, 1f);
    static final Interpolator EASE_IN_OUT = new PathInterpolator(0.77f, 0f, 0.175f, 1f);
    static boolean motion() { return ValueAnimator.areAnimatorsEnabled(); }

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
    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    /** Page background: vertical midnight gradient with a very faint honeycomb. */
    static final class HoneyBackground extends Drawable {
        private final Paint hex = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint();
        private final float r;
        HoneyBackground(Context c) { r = dp(c, 26); hex.setStyle(Paint.Style.STROKE); hex.setStrokeWidth(Math.max(1, dp(c, 1))); hex.setColor(0x0F8B5CF6); }
        private android.graphics.Bitmap cache;
        @Override public void draw(Canvas out) {   // drawn once per size into a bitmap: scrolling just blits it
            int w = getBounds().width(), h = getBounds().height();
            if (w <= 0 || h <= 0) return;
            if (cache == null || cache.getWidth() != w || cache.getHeight() != h) {
                if (cache != null) cache.recycle();
                cache = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.RGB_565);
                render(new Canvas(cache), w, h);
            }
            out.drawBitmap(cache, getBounds().left, getBounds().top, null);
        }
        private void render(Canvas cv, int w, int h) {
            fill.setShader(new LinearGradient(0, 0, 0, h, BG1, BG0, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, fill);
            float dx = (float) (Math.sqrt(3) * r), dy = 1.5f * r;
            Path p = new Path();
            for (int row = 0; row * dy < h + r; row++)
                for (int col = 0; col * dx < w + dx; col++) {
                    float cx = col * dx + (row % 2 == 0 ? 0 : dx / 2), cy = row * dy;
                    p.reset();
                    for (int i = 0; i < 6; i++) {
                        double a = Math.toRadians(60 * i - 30);
                        float x = cx + (float) (r * Math.cos(a)), y = cy + (float) (r * Math.sin(a));
                        if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
                    }
                    p.close(); cv.drawPath(p, hex);
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
        Bar(Context c) { super(c); track.setColor(0x33FFFFFF); }
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
            if (live && a == null && motion()) {
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
            grid.setColor(0x22FFFFFF); stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeWidth(2.5f * d); stroke.setStrokeCap(Paint.Cap.ROUND);
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
