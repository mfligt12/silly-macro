package com.iipxp.beemacro;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen, touch-transparent layer that draws the tracked detections over the game: box, name + real score,
 * category colour, track id. Never takes touches (FLAG_NOT_TOUCHABLE), so Roblox controls work underneath.
 * MediaProjection captures this layer too, so every rect it draws is handed to the detector as "ignore" for the
 * next frame — our own boxes never become detections.
 */
final class BoxOverlay extends View {
    private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG), bg = new Paint(), txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] origin = new int[2];
    volatile Rect panel;   // the floating panel, also ignored by the detector

    BoxOverlay(Context c) {
        super(c);
        box.setStyle(Paint.Style.STROKE);
        txt.setColor(0xFFFFFFFF); txt.setFakeBoldText(true);
    }

    private final Paint fieldPaint = new Paint();
    /** Debug view: outlines the cells read as the field's boundary, and marks the character's cell. */
    private void drawFieldMap(Canvas cv) {
        FieldMap.Read r = Monitor.fieldRead; Remap.Out o = Monitor.remap;
        if (r == null || o == null || !o.tablet) return;
        fieldPaint.setStyle(Paint.Style.STROKE); fieldPaint.setStrokeWidth(2);
        double cw = (Remap.W - 2 * o.worldX0) / FieldMap.CW, ch = (double) Remap.H / FieldMap.CH;
        for (int j = 0; j < FieldMap.CH; j++) for (int i = 0; i < FieldMap.CW; i++) {
            boolean me = i == FieldMap.CW / 2 && j == FieldMap.CH / 2;
            if (r.cells[j * FieldMap.CW + i] != FieldMap.EDGE && !me) continue;
            int[] c = Remap.toScreen(o, new int[]{(int) (o.worldX0 + i * cw), (int) (j * ch), (int) (o.worldX0 + (i + 1) * cw), (int) ((j + 1) * ch)}, false);
            fieldPaint.setColor(me ? 0xFF40FF80 : 0x90FF4040);
            cv.drawRect(c[0] - origin[0], c[1] - origin[1], c[2] - origin[0], c[3] - origin[1], fieldPaint);
        }
    }

    @Override protected void onDraw(Canvas cv) {
        Profile p = App.profile(getContext());
        List<int[]> ign = new ArrayList<>();
        if (panel != null) ign.add(new int[]{panel.left, panel.top, panel.right, panel.bottom});
        if (!p.showBoxes || CaptureService.inst == null) { Monitor.ignore = ign; return; }
        getLocationOnScreen(origin);   // capture coords are screen coords; this view may be offset (cutout/bars)
        float dens = getResources().getDisplayMetrics().scaledDensity;
        txt.setTextSize(p.boxLabelSp * dens);
        box.setStrokeWidth(p.boxThickness);
        setAlpha(p.boxAlpha);
        Paint.FontMetrics fm = txt.getFontMetrics();
        float lh = fm.descent - fm.ascent + 4;
        List<RectF> placed = new ArrayList<>();
        long now = SystemClock.uptimeMillis();
        if (p.showFieldMap) drawFieldMap(cv);
        int pad = p.boxThickness + 6;   // detector grid step is ~4 px: mask a little wider than the stroke
        List<Object[]> items = new ArrayList<>();   // {Det, label text, colour, faded}
        for (Detector.Tracker.Track t : Monitor.tracker.snapshot()) {
            Detector.Det d = t.d;
            if (t.hits < 2) continue;                                  // one-frame blips flicker; wait for a 2nd sighting
            String name = t.confirmed();                               // stage 4: only names agreed over frames are shown
            Detector.Cat c = name == null ? Detector.Cat.UNKNOWN : d.cat;
            if (c == Detector.Cat.UNKNOWN && !p.showUnknown) continue;
            if (p.hiddenCats.contains(c.name())) continue;
            String s = name == null ? "Unknown #" + t.id : String.format(java.util.Locale.US, "%s %.2f #%d", name, d.conf, t.id);
            items.add(new Object[]{d, s, Detector.COLOR[c.ordinal()], now - t.last > 150});
        }
        if (p.showBuffBoxes) for (Buffs.Effect e : Monitor.buffBoxes) {
            Detector.Det d = new Detector.Det(); d.buff = true; d.x0 = e.x0; d.y0 = e.y0; d.x1 = e.x1; d.y1 = e.y1;
            items.add(new Object[]{d, String.format(java.util.Locale.US, "%s%s %.2f", e.name, e.stack == null ? "" : " " + e.stack, e.conf), e.debuff ? 0xFFEF4444 : Detector.COLOR[Detector.Cat.BOOST.ordinal()], false});
        }
        for (Object[] it : items) {
            Detector.Det d = (Detector.Det) it[0]; String s = (String) it[1]; int col = (Integer) it[2];
            if ((Boolean) it[3]) col = (col & 0x00FFFFFF) | 0x88000000;   // in grace period: fade
            int[] sr = Remap.toScreen(Monitor.remap, new int[]{d.x0, d.y0, d.x1, d.y1}, d.buff);
            float x0 = sr[0] - origin[0], y0 = sr[1] - origin[1], x1 = sr[2] - origin[0], y1 = sr[3] - origin[1];
            // buff icons: 1 px line just outside the 58 px icon (slots are 60 px apart), so the line never lands in
            // the pixels the buff reader samples (this slot or the next)
            if (d.buff) { x0 -= 1; y0 -= 2; x1 += 1; y1 += 2; }
            box.setStrokeWidth(d.buff ? 1 : p.boxThickness);
            box.setColor(col);
            cv.drawRect(x0, y0, x1, y1, box);
            float tw = txt.measureText(s) + 8;
            RectF lr = new RectF(x0, y0 - lh, x0 + tw, y0);
            if (d.buff) lr.offsetTo(x0, y1 + 2 + (placedBuffRow(placed, lr) ? lh : 0));   // buff row: below, alternating rows
            for (int k = 0; k < 6 && hits(lr, placed); k++) {
                if (k == 0 && !d.buff) lr.offsetTo(x0, y1);
                else lr.offset(0, lh);
            }
            // never draw a world label into the HUD strip (prompt box edge rows are read at y≈191 and 288 of 1080)
            float hudBottom = 0.27f * getHeight();
            if (!d.buff && lr.top < hudBottom) lr.offsetTo(lr.left, Math.max(y1, hudBottom));
            placed.add(lr);
            bg.setColor(col);
            cv.drawRect(lr, bg);
            cv.drawText(s, lr.left + 4, lr.bottom - fm.descent - 2, txt);
            int ox = origin[0], oy = origin[1];
            if (!d.buff) {   // world boxes: their outline bands and label go to the detector ignore list
                ign.add(new int[]{(int) x0 + ox - pad, (int) y0 + oy - pad, (int) x1 + ox + pad, (int) y0 + oy + pad});
                ign.add(new int[]{(int) x0 + ox - pad, (int) y1 + oy - pad, (int) x1 + ox + pad, (int) y1 + oy + pad});
                ign.add(new int[]{(int) x0 + ox - pad, (int) y0 + oy, (int) x0 + ox + pad, (int) y1 + oy});
                ign.add(new int[]{(int) x1 + ox - pad, (int) y0 + oy, (int) x1 + ox + pad, (int) y1 + oy});
            }
            ign.add(new int[]{(int) lr.left + ox - pad, (int) lr.top + oy - pad, (int) lr.right + ox + pad, (int) lr.bottom + oy + pad});
        }
        Monitor.ignore = ign;
    }

    private static boolean placedBuffRow(List<RectF> placed, RectF r) { for (RectF q : placed) if (q.top >= r.top - 2 && q.right > r.left - 4 && q.left < r.right) return true; return false; }
    private static boolean hits(RectF r, List<RectF> placed) { for (RectF q : placed) if (RectF.intersects(r, q)) return true; return false; }
}
