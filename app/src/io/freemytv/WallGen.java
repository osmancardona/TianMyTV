package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.BitmapFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Random;

/**
 * The catalog's wallpapers that are drawn, not stored: cloud-like smoke (noise layered at several scales and bent on itself),
 * coloured by a palette, with a vignette, embers, a blood moon or the TIAN emblem on top. Made at any size, always the
 * same picture; the screen's size (2112 by 1188) is made once and kept.
 */
final class WallGen {
    private WallGen() {}

    static final String[] NAMES = {"Emblema TIAN", "Luna de Sangre", "Humo Carmesi", "Brasas", "Medianoche"};

    static Bitmap make(Context c, String name, int w, int h) {
        switch (name) {
            case "Humo Carmesi": return smoke(w, h, 11, RED, 0.9f, 0f);
            case "Medianoche": return smoke(w, h, 29, BLUE, 0.9f, 0f);
            case "Brasas": return embers(w, h);
            case "Luna de Sangre": return moon(w, h);
            case "Emblema TIAN": return emblem(c, w, h);
            default: return smoke(w, h, 5, RED, 0.9f, 0f);
        }
    }

    // MARK: - Noise

    private static float hash(int x, int y, int seed) {
        int n = x * 374761393 + y * 668265263 + seed * 1442695041;
        n = (n ^ (n >>> 13)) * 1274126177;
        n ^= n >>> 16;
        return (n & 0xFFFFFF) / (float) 0x1000000;
    }

    private static float noise(float x, float y, int seed) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        float fx = x - ix, fy = y - iy;
        float ux = fx * fx * (3 - 2 * fx), uy = fy * fy * (3 - 2 * fy);
        float a = hash(ix, iy, seed), b = hash(ix + 1, iy, seed), cc = hash(ix, iy + 1, seed), d = hash(ix + 1, iy + 1, seed);
        return a + (b - a) * ux + (cc - a) * uy + (a - b - cc + d) * ux * uy;
    }

    private static float fbm(float x, float y, int seed) {
        float sum = 0, amp = 0.5f;
        for (int i = 0; i < 6; i++) {
            sum += amp * noise(x, y, seed + i * 17);
            x = x * 2.03f + 11.7f;
            y = y * 2.03f + 5.3f;
            amp *= 0.5f;
        }
        return sum;
    }

    /** The smoke: a field of 0 to 1, made small (a third of a screen) and read back smoothly. */
    private static float[] field(int fw, int fh, int seed, float scale) {
        float[] f = new float[fw * fh];
        float lo = 9, hi = -9;
        for (int j = 0; j < fh; j++) {
            for (int i = 0; i < fw; i++) {
                float x = i / (float) fh * scale, y = j / (float) fh * scale;
                float qx = fbm(x, y, seed), qy = fbm(x + 5.2f, y + 1.3f, seed);
                float rx = fbm(x + 4 * qx + 1.7f, y + 4 * qy + 9.2f, seed + 3), ry = fbm(x + 4 * qx + 8.3f, y + 4 * qy + 2.8f, seed + 3);
                float v = fbm(x + 4 * rx, y + 4 * ry, seed + 7);
                f[j * fw + i] = v;
                if (v < lo) lo = v;
                if (v > hi) hi = v;
            }
        }
        float span = Math.max(1e-4f, hi - lo);
        for (int i = 0; i < f.length; i++) f[i] = (f[i] - lo) / span;
        return f;
    }

    private static final int[][] RED = {{0, 0, 0}, {26, 2, 6}, {92, 6, 14}, {190, 22, 30}, {255, 96, 70}};
    private static final int[][] BLUE = {{0, 0, 4}, {4, 6, 30}, {14, 22, 96}, {50, 76, 200}, {170, 190, 255}};

    private static int[] ramp(int[][] stops, float t) {
        t = Math.max(0, Math.min(0.9999f, t)) * (stops.length - 1);
        int i = (int) t;
        float f = t - i;
        int[] a = stops[i], b = stops[i + 1];
        return new int[] {Math.round(a[0] + (b[0] - a[0]) * f), Math.round(a[1] + (b[1] - a[1]) * f), Math.round(a[2] + (b[2] - a[2]) * f)};
    }

    /** The smoke coloured and darkened at the edges. `dim` darkens it overall (the moon and embers use a dim one). */
    private static Bitmap smoke(int w, int h, int seed, int[][] palette, float gain, float dim) {
        int fw = Math.max(48, w / 3), fh = Math.max(27, h / 3);
        float[] f = field(fw, fh, seed, 3.2f);
        int[] px = new int[w * h];
        float cx = (w - 1) / 2f, cy = (h - 1) / 2f;
        for (int y = 0; y < h; y++) {
            float gy = y / (float) (h - 1) * (fh - 1);
            int y0 = (int) gy, y1 = Math.min(fh - 1, y0 + 1);
            float ty = gy - y0, dy = (y - cy) / cy;
            for (int x = 0; x < w; x++) {
                float gx = x / (float) (w - 1) * (fw - 1);
                int x0 = (int) gx, x1 = Math.min(fw - 1, x0 + 1);
                float tx = gx - x0;
                float v = (f[y0 * fw + x0] * (1 - tx) + f[y0 * fw + x1] * tx) * (1 - ty) + (f[y1 * fw + x0] * (1 - tx) + f[y1 * fw + x1] * tx) * ty;
                v = (float) Math.pow(v, 1.7) * gain;
                float dx = (x - cx) / cx;
                float vig = 1f - 0.62f * (float) Math.pow(Math.min(1.2f, dx * dx * 0.7f + dy * dy * 0.9f), 1.15);
                v *= Math.max(0.12f, vig) * (1 - dim);
                int[] c = ramp(palette, v);
                px[y * w + x] = 0xFF000000 | (c[0] << 16) | (c[1] << 8) | c[2];
            }
        }
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.setPixels(px, 0, w, 0, 0, w, h);
        return b;
    }

    // MARK: - The pictures

    private static Bitmap embers(int w, int h) {
        Bitmap b = smoke(w, h, 41, RED, 0.7f, 0.35f);
        Canvas c = new Canvas(b);
        float k = w / 2112f;
        Random r = new Random(7);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        int n = Math.max(40, Math.round(300 * k * k + 60));
        for (int i = 0; i < n; i++) {
            float x = r.nextFloat() * w, y = (float) Math.pow(r.nextFloat(), 0.7) * h;
            float size = (1.2f + (float) Math.pow(r.nextFloat(), 3) * 6f) * Math.max(0.35f, k);
            float heat = r.nextFloat();
            int core = heat > 0.55f ? 0xFFFFB04A : 0xFFFF4A2A;
            p.setShader(new RadialGradient(x, y, size * 5, new int[] {core, (core & 0x00FFFFFF) | 0x55000000, 0x00000000}, new float[] {0f, 0.25f, 1f}, Shader.TileMode.CLAMP));
            p.setAlpha(Math.round(120 + 135 * r.nextFloat()));
            c.drawCircle(x, y, size * 5, p);
        }
        return b;
    }

    private static Bitmap moon(int w, int h) {
        Bitmap b = smoke(w, h, 23, RED, 0.8f, 0.25f);
        Canvas c = new Canvas(b);
        float k = w / 2112f;
        float mx = 1480 * k, my = 400 * k, mr = 200 * k;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        p.setShader(new RadialGradient(mx, my, mr * 4.2f, new int[] {0x99E02A22, 0x40801010, 0x00000000}, new float[] {0.25f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(mx, my, mr * 4.2f, p);
        p.setXfermode(null);
        p.setShader(new RadialGradient(mx - mr * 0.3f, my - mr * 0.3f, mr * 1.4f, new int[] {0xFFFF7A5C, 0xFFD6322A, 0xFF8E1218}, new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(mx, my, mr, p);
        // craters and seas
        Random r = new Random(5);
        p.setShader(null);
        for (int i = 0; i < 46; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = Math.sqrt(r.nextDouble()) * mr * 0.9;
            float cx = mx + (float) (Math.cos(a) * d), cy = my + (float) (Math.sin(a) * d), cr = (4 + r.nextFloat() * 26) * k + 2;
            p.setColor(((int) (18 + r.nextFloat() * 40) << 24) | 0x3A0508);
            c.drawCircle(cx, cy, cr, p);
        }
        // the dark ridge of trees along the bottom, and a low fog
        Path ridge = new Path();
        ridge.moveTo(0, h);
        float base = h * 0.84f;
        for (int x = 0; x <= w; x += Math.max(2, Math.round(6 * k))) {
            float ys = base - fbm(x / (float) h * 3.1f, 4.2f, 99) * h * 0.1f - (r.nextFloat() < 0.18f ? r.nextFloat() * h * 0.05f : 0);
            ridge.lineTo(x, ys);
        }
        ridge.lineTo(w, h);
        ridge.close();
        p.setColor(0xFF020001);
        c.drawPath(ridge, p);
        p.setShader(new LinearGradient(0, h * 0.7f, 0, h, new int[] {0x00000000, 0x66200608, 0xFF000000}, new float[] {0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, h * 0.7f, w, h, p);
        return b;
    }

    private static Bitmap emblem(Context c0, int w, int h) {
        Bitmap b = smoke(w, h, 11, RED, 0.8f, 0.2f);
        Canvas c = new Canvas(b);
        float k = w / 2112f;
        Bitmap logo = null;
        try (InputStream in = c0.getAssets().open("logo-big.png")) {
            logo = BitmapFactory.decodeStream(in);
        } catch (IOException | RuntimeException e) {
            logo = null;
        }
        if (logo == null) return b;
        float size = 1010 * k, x = 1070 * k, y = (h - size) / 2f;
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setMaskFilter(new BlurMaskFilter(Math.max(2, 46 * k), BlurMaskFilter.Blur.NORMAL));
        int[] off = new int[2];
        Bitmap alpha = logo.extractAlpha(glow, off);
        Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tint.setColor(0xFFE0201C);
        tint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        float s = size / logo.getWidth();
        c.save();
        c.translate(x, y);
        c.scale(s, s);
        c.drawBitmap(alpha, off[0], off[1], tint);
        c.restore();
        Paint pic = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        c.drawBitmap(logo, null, new android.graphics.RectF(x, y, x + size, y + size), pic);
        return b;
    }
}
