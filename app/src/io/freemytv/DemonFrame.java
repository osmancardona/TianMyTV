package io.freemytv;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * The shelf's frame: intense red and black, with a thick ornate border, big spikes and horns, black chains and an
 * eye sigil at each end. Drawn with paths (nothing copied), so it fits any width.
 */
final class DemonFrame {
    private DemonFrame() {}

    private static final int RED = 0xFFF0121F, HOT = 0xFFFF3B3B, DEEP = 0xFF8A0612, DARK = 0xFF2A0308, BRIGHT = 0xFFFFA3A3;
    private static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path PATH = new Path();
    private static final RectF TMP = new RectF(), TMP2 = new RectF();

    private static void fill(Canvas c, int color) {
        P.setShader(null);
        P.setStyle(Paint.Style.FILL);
        P.setColor(color);
        c.drawPath(PATH, P);
    }

    private static void stroke(Canvas c, int color, float w) {
        P.setShader(null);
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeJoin(Paint.Join.ROUND);
        P.setStrokeCap(Paint.Cap.ROUND);
        P.setColor(color);
        P.setStrokeWidth(w);
        c.drawPath(PATH, P);
    }

    /** A thorn from (x0, y0), `width` wide at its root, curving to its tip (tx, ty); lit edge and glow around it. */
    private static void thorn(Canvas c, float x0, float y0, float tx, float ty, float width, float bend, int color, float k) {
        float dx = tx - x0, dy = ty - y0, len = (float) Math.hypot(dx, dy);
        float nx = -dy / len, ny = dx / len;
        float mx = (x0 + tx) / 2, my = (y0 + ty) / 2;
        PATH.reset();
        PATH.moveTo(x0 + nx * width / 2, y0 + ny * width / 2);
        PATH.quadTo(mx + nx * (width * 0.2f + bend), my + ny * (width * 0.2f + bend), tx, ty);
        PATH.quadTo(mx - nx * (width * 0.2f - bend), my - ny * (width * 0.2f - bend), x0 - nx * width / 2, y0 - ny * width / 2);
        PATH.close();
        stroke(c, 0x44FF1A2A, 7 * k);
        fill(c, color);
        stroke(c, BRIGHT, 1.4f * k);
    }

    /** A four-pointed star. */
    private static void star(Canvas c, float cx, float cy, float rx, float ry, int color, float k) {
        float in = 0.14f;
        PATH.reset();
        PATH.moveTo(cx, cy - ry);
        PATH.quadTo(cx + rx * in, cy - ry * in, cx + rx, cy);
        PATH.quadTo(cx + rx * in, cy + ry * in, cx, cy + ry);
        PATH.quadTo(cx - rx * in, cy + ry * in, cx - rx, cy);
        PATH.quadTo(cx - rx * in, cy - ry * in, cx, cy - ry);
        PATH.close();
        stroke(c, 0x55FF1A2A, 8 * k);
        fill(c, color);
        stroke(c, BRIGHT, 1.2f * k);
    }

    /** The glowing eye sigil: thick ring, spikes, an almond and a slit pupil. */
    private static void eye(Canvas c, float cx, float cy, float r, float k) {
        PATH.reset();
        PATH.addCircle(cx, cy, r * 1.08f, Path.Direction.CW);
        stroke(c, 0x33FF1A2A, 18 * k);
        stroke(c, 0x55FF1A2A, 10 * k);
        for (int i = 0; i < 12; i++) {
            double a = Math.PI / 6 * i + Math.PI / 12;
            float big = i % 2 == 0 ? 1.55f : 1.28f;
            thorn(c, cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r,
                    cx + (float) Math.cos(a) * r * big, cy + (float) Math.sin(a) * r * big, 9 * k, 0, RED, k);
        }
        PATH.reset();
        PATH.addCircle(cx, cy, r, Path.Direction.CW);
        fill(c, 0xFF0C0204);
        stroke(c, RED, 6 * k);
        PATH.reset();
        PATH.addCircle(cx, cy, r * 0.82f, Path.Direction.CW);
        stroke(c, DEEP, 3 * k);
        stroke(c, HOT, 1.2f * k);
        PATH.reset();
        PATH.moveTo(cx - r * 0.78f, cy);
        PATH.quadTo(cx, cy - r * 0.72f, cx + r * 0.78f, cy);
        PATH.quadTo(cx, cy + r * 0.72f, cx - r * 0.78f, cy);
        PATH.close();
        fill(c, 0xFFD3081A);
        stroke(c, BRIGHT, 2 * k);
        PATH.reset();
        PATH.addCircle(cx, cy, r * 0.34f, Path.Direction.CW);
        fill(c, 0xFFFF6A4A);
        PATH.reset();
        PATH.moveTo(cx, cy - r * 0.5f);
        PATH.quadTo(cx + r * 0.13f, cy, cx, cy + r * 0.5f);
        PATH.quadTo(cx - r * 0.13f, cy, cx, cy - r * 0.5f);
        PATH.close();
        fill(c, 0xFF050001);
    }

    /** A black chain hanging from (x, y): links with a red edge, ending in a spike. */
    private static void chain(Canvas c, float x, float y, int links, float scale, float k) {
        float h = 26 * k * scale, w = 9 * k * scale;
        for (int i = 0; i < links; i++) {
            float cy = y + i * h * 0.78f + h / 2;
            float half = (i % 2 == 0 ? w : w * 0.45f);
            TMP2.set(x - half, cy - h / 2, x + half, cy + h / 2);
            PATH.reset();
            PATH.addRoundRect(TMP2, half, half, Path.Direction.CW);
            stroke(c, 0xFF050001, 6.5f * k * scale);
            stroke(c, i % 2 == 0 ? RED : DEEP, 2f * k * scale);
        }
        float ey = y + links * h * 0.78f + h * 0.3f;
        star(c, x, ey + 22 * k * scale, 10 * k * scale, 30 * k * scale, RED, k);
    }

    static void draw(Canvas c, RectF r, float radius, float k) {
        float cx = r.centerX();
        // soft red glow, then a light tint over the glass the caller drew
        P.setStyle(Paint.Style.STROKE);
        for (int i = 5; i >= 1; i--) {
            P.setColor(((0x0C + (5 - i) * 0x07) << 24) | 0xFF1020);
            P.setStrokeWidth((6 + i * 5) * k);
            c.drawRoundRect(r, radius, radius, P);
        }
        P.setStyle(Paint.Style.FILL);
        P.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0x14FF3A4A, 0x06200509, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, radius, radius, P);
        P.setShader(null);
        // the borders: thin red lines
        P.setStyle(Paint.Style.STROKE);
        P.setColor(RED);
        P.setStrokeWidth(3.2f * k);
        c.drawRoundRect(r, radius, radius, P);
        P.setColor(0x99FFC0C0);
        P.setStrokeWidth(1.1f * k);
        c.drawRoundRect(r, radius, radius, P);
        TMP.set(r.left + 9 * k, r.top + 9 * k, r.right - 9 * k, r.bottom - 9 * k);
        P.setColor(DEEP);
        P.setStrokeWidth(2f * k);
        c.drawRoundRect(TMP, radius - 8 * k, radius - 8 * k, P);
        // a small crest at the middle of the top and bottom, and curved blades at the corners
        for (int sy = -1; sy <= 1; sy += 2) {
            float y = sy < 0 ? r.top : r.bottom;
            for (int sx = -1; sx <= 1; sx += 2) {
                thorn(c, cx + sx * 22 * k, y, cx + sx * 150 * k, y + sy * 26 * k, 20 * k, 6 * k, RED, k);
                thorn(c, cx + sx * 14 * k, y, cx + sx * 64 * k, y + sy * 14 * k, 12 * k, 2 * k, HOT, k);
            }
            star(c, cx, y + sy * 6 * k, 14 * k, 40 * k, RED, k);
            star(c, cx, y + sy * 6 * k, 4.5f * k, 20 * k, BRIGHT, k);
        }
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                float x = sx < 0 ? r.left + 26 * k : r.right - 26 * k, y = sy < 0 ? r.top : r.bottom;
                thorn(c, x, y, x + sx * 52 * k, y + sy * 44 * k, 22 * k, 6 * k, RED, k);
                thorn(c, x, y, x - sx * 70 * k, y + sy * 22 * k, 18 * k, 4 * k, DEEP, k);
            }
        }
        // studs along the borders
        for (float x = r.left + radius + 20 * k; x < r.right - radius - 10 * k; x += 70 * k) {
            if (Math.abs(x - cx) < 190 * k) continue;
            star(c, x, r.top, 4 * k, 6 * k, BRIGHT, k * 0.5f);
            star(c, x, r.bottom, 4 * k, 6 * k, BRIGHT, k * 0.5f);
        }
        // the chains hanging under the bottom border
        float[] at = {0.10f, 0.22f, 0.36f, 0.5f, 0.64f, 0.78f, 0.90f};
        for (int i = 0; i < at.length; i++) {
            float x = r.left + r.width() * at[i];
            chain(c, x, r.bottom - 3 * k, i % 2 == 0 ? 4 : 3, 0.8f, k);
        }
    }
}
