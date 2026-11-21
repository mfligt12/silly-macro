package com.iipxp.beemacro;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * Start-up animation, shown on every launch (≈1.75 s, tap to skip). Its own honey/honeycomb design, built on the
 * motion rules of the reference loop: anticipation before the drop, gravity with motion trails, squash and stretch
 * on impact with a springy recovery, a roll inside the "bowl", then a burst into a honeycomb of dots that resolves
 * into the title. Transform/alpha style drawing only; with Android's animations off it is a short title fade.
 */
final class SplashView extends View {
    private static final int TOTAL = 1750, FADE_AT = 1550;
    private static final Interpolator EO = new PathInterpolator(0.23f, 1f, 0.32f, 1f);        // ease-out
    private static final Interpolator EIO = new PathInterpolator(0.77f, 0f, 0.175f, 1f);      // ease-in-out
    private static final int BG = 0xFF0F0F14, HONEY = 0xFFFFC233;
    private static final int[] RING = {0xFFFFC233, 0xFFFF8A3D, 0xFFFF4FA0, 0xFF7C5CFF, 0xFF4FA8FF, 0xFFFFC233};

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), ball = new Paint(Paint.ANTI_ALIAS_FLAG),
            dot = new Paint(Paint.ANTI_ALIAS_FLAG), title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path hex = new Path(), part = new Path();
    private PathMeasure pm;
    private final float dp;
    private final boolean motion = ValueAnimator.areAnimatorsEnabled();
    private float t;                     // ms into the animation
    private ValueAnimator anim;
    private final Runnable done;

    SplashView(Context c, Runnable done) {
        super(c);
        this.done = done;
        dp = c.getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE); ring.setStrokeCap(Paint.Cap.ROUND); ring.setStrokeJoin(Paint.Join.ROUND); ring.setStrokeWidth(5 * dp);
        ball.setColor(Color.WHITE);
        title.setColor(Color.WHITE); title.setTextAlign(Paint.Align.CENTER); title.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD)); title.setTextSize(30 * dp);
        setClickable(true);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        int len = motion ? TOTAL : 900;   // animations off: the finished frame (title + honeycomb), then a gentle fade
        anim = ValueAnimator.ofFloat(0, len);
        anim.setDuration(len);
        anim.setInterpolator(null);   // linear clock; each phase applies its own curve
        anim.addUpdateListener(a -> { float v = (float) a.getAnimatedValue(); t = motion ? v : 1500 + v * (TOTAL - 1500) / 900f; invalidate(); });
        anim.addListener(new android.animation.AnimatorListenerAdapter() { @Override public void onAnimationEnd(android.animation.Animator a) { finish(); } });
        anim.start();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {   // tap to skip: jump to the fade-out
        if (e.getAction() == MotionEvent.ACTION_UP && anim != null && motion && t < FADE_AT) anim.setCurrentPlayTime(FADE_AT);
        return true;
    }

    private void finish() {
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
        if (done != null) done.run();
    }

    private static float seg(float t, float a, float b) { return Math.max(0, Math.min(1, (t - a) / (b - a))); }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), cx = w / 2, cy = h * 0.42f, R = Math.min(w, h) * 0.16f;
        float fade = 1 - EO.getInterpolation(seg(t, FADE_AT, TOTAL));
        c.drawColor((Math.round(255 * fade) << 24) | (BG & 0xFFFFFF));
        if (fade <= 0) return;
        if (pm == null) {   // pointy-top hexagon, path starting at the top vertex so the gap opens there
            hex.reset();
            for (int i = 0; i <= 6; i++) { double a = Math.toRadians(-90 + 60 * i); float x = (float) (R * Math.cos(a)), y = (float) (R * Math.sin(a)); if (i == 0) hex.moveTo(x, y); else hex.lineTo(x, y); }
            pm = new PathMeasure(hex, false);
            ring.setShader(new SweepGradient(0, 0, RING, null));
        }
        float L = pm.getLength();

        // ---- ring: draws itself (0-380), opens a gap at the top (380-560), squashes into a bowl on impact (620-900), bursts (1100+)
        float draw = EO.getInterpolation(seg(t, 0, 380));
        float gap = EO.getInterpolation(seg(t, 380, 560)) * (1 - EO.getInterpolation(seg(t, 900, 1080))) * 0.14f * L;
        float impact = seg(t, 620, 900);
        float squash = (float) (Math.exp(-5 * impact) * Math.cos(impact * Math.PI * 3)) * (impact > 0 ? 1 : 0);   // damped spring
        float burst = EO.getInterpolation(seg(t, 1100, 1350));
        if (burst < 1) {
            c.save();
            c.translate(cx, cy);
            c.scale(1 + 0.08f * squash, 1 - 0.18f * squash, 0, R);   // squash from the bottom, where the drop lands
            c.rotate(-30 * (1 - draw));
            ring.setAlpha(Math.round(255 * fade * (1 - burst)));
            part.reset();
            pm.getSegment(gap / 2, Math.max(gap / 2, draw * (L - gap / 2)), part, true);
            c.drawPath(part, ring);
            c.restore();
        }

        // ---- the honey drop: anticipation (0-200), fall (380-620), impact squash, roll (760-1100), becomes the centre dot
        float r = 9 * dp, bx = cx, by;
        float sx = 1, sy = 1;
        float top = cy - R * 2.1f, floor = cy + R * 0.62f;
        if (t < 380) by = top - 6 * dp * (float) Math.sin(Math.PI * seg(t, 0, 380));   // a little lift before the drop
        else if (t < 620) { float f = seg(t, 380, 620); by = top + (floor - top) * f * f; sx = 1 - 0.12f * f; sy = 1 + 0.22f * f; }   // gravity, stretching as it speeds up
        else if (t < 760) { by = floor; float k = (float) (Math.exp(-6 * seg(t, 620, 760)) * Math.cos(seg(t, 620, 760) * Math.PI * 2.5)); sx = 1 + 0.35f * k; sy = 1 - 0.3f * k; }
        else if (t < 1100) { float f = EIO.getInterpolation(seg(t, 760, 1100)); bx = cx + (float) Math.sin(f * Math.PI * 2) * R * 0.45f; by = floor - (float) (1 - Math.cos(f * Math.PI * 2)) * R * 0.08f; }
        else { float f = EO.getInterpolation(seg(t, 1100, 1350)); by = floor + (cy - floor) * f; }
        // motion trails: where the drop was 1-3 frames ago, fading
        if (t > 380 && t < 1100 && motion) {
            for (int k = 3; k >= 1; k--) {
                float tt = t - 22 * k, py, px = cx;
                if (tt < 380) continue;
                if (tt < 620) { float f = seg(tt, 380, 620); py = top + (floor - top) * f * f; }
                else if (tt < 760) py = floor;
                else { float f = EIO.getInterpolation(seg(tt, 760, 1100)); px = cx + (float) Math.sin(f * Math.PI * 2) * R * 0.45f; py = floor - (float) (1 - Math.cos(f * Math.PI * 2)) * R * 0.08f; }
                ball.setColor(Color.WHITE); ball.setAlpha(Math.round(fade * 60 / k));
                c.drawCircle(px, py, r * (1 - 0.12f * k), ball);
            }
        }
        // colour: white drop warms to honey as it lands
        float warm = seg(t, 620, 900);
        ball.setColor(blend(Color.WHITE, HONEY, warm)); ball.setAlpha(Math.round(255 * fade));
        c.save(); c.scale(sx, sy, bx, by + r); c.drawCircle(bx, by, r, ball); c.restore();

        // ---- burst: honeycomb of dots flying out from the hexagon's corners (30 ms apart), coloured from the ring
        if (t > 1100) {
            for (int i = 0; i < 6; i++) {
                float f = EO.getInterpolation(seg(t, 1100 + 30 * i, 1380 + 30 * i));
                double a = Math.toRadians(-90 + 60 * i);
                float dist = R * (1 + 0.35f * f) * (1 - 0.45f * seg(t, 1300, 1550));
                float dx = cx + (float) (dist * Math.cos(a)), dy = cy + (float) (dist * Math.sin(a));
                dot.setColor(RING[i % (RING.length - 1)]); dot.setAlpha(Math.round(255 * fade * Math.min(1, f * 2)));
                c.drawCircle(dx, dy, (3 + 4 * f) * dp, dot);
            }
        }

        // ---- title resolves in (ease-out, rising 10 dp)
        float ti = EO.getInterpolation(seg(t, 1250, 1500));
        if (ti > 0) {
            title.setAlpha(Math.round(255 * fade * ti));
            c.drawText("Silly Macro", cx, cy + R * 1.9f + 10 * dp * (1 - ti), title);
        }
    }

    private static int blend(int a, int b, float f) {
        int r = Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * f), g = Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * f), bl = Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f);
        return Color.rgb(r, g, bl);
    }
}
