package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Choreographer;
import android.view.View;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The waiting screen: your wallpaper, slowly zooming, under a dark veil with red embers rising; the big clock, the date
 * and the weather, the music playing, and the TIAN emblem breathing in a corner. Everything drifts a little all the time, so
 * no pixel stays lit (the TV's screen). Any key exits (Screensaver).
 */
final class TianDreamView extends View implements Choreographer.FrameCallback {
    private static final int EMBERS = 44;
    private final float k;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint big, small, caps, ampm, music, artist;
    private final RectF r = new RectF();
    private final float[] ex = new float[EMBERS], ey = new float[EMBERS], es = new float[EMBERS], ev = new float[EMBERS], ep = new float[EMBERS];
    private final NowPlaying.Watch watch;
    private final HomeData data;
    private Bitmap photo, logo;
    private Shader vignette;
    private int vignetteW;
    private boolean running;
    private long started, lastDraw;
    private String clockText = "", ampmText = "", dateText = "";
    private long clockMinute = -1;

    TianDreamView(Context c) {
        super(c);
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        k = dm.widthPixels / 1920f;
        big = Ui.numbers(Ui.light, 230 * k, 0xFFFFFFFF);
        small = Ui.text(Ui.medium, 30 * k, 0xCCFFFFFF);
        caps = Ui.text(Ui.semibold, 28 * k, 0xAAFFFFFF);
        caps.setLetterSpacing(0.22f);
        ampm = Ui.text(Ui.semibold, 38 * k, 0xFFFFFFFF);
        ampm.setLetterSpacing(0.08f);
        music = Ui.text(Ui.semibold, 30 * k, 0xFFFFFFFF);
        artist = Ui.text(Ui.regular, 24 * k, 0xB3FFFFFF);
        data = HomeData.get(c);
        watch = new NowPlaying.Watch(c, this::postInvalidateOnAnimation);
        for (int i = 0; i < EMBERS; i++) {
            ex[i] = (float) Math.random();
            ey[i] = (float) Math.random();
            es[i] = 2 + (float) Math.random() * 4;
            ev[i] = 0.01f + (float) Math.random() * 0.03f;
            ep[i] = (float) Math.random() * 6.28f;
        }
        try {
            List<File> own = Photos.files(c);
            if (!own.isEmpty()) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                photo = BitmapFactory.decodeFile(own.get(0).getPath(), o);
            }
            if (photo == null) {
                try (InputStream in = c.getAssets().open("bg-alastor.jpg")) {
                    photo = BitmapFactory.decodeStream(in);
                }
            }
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            photo = null;
        }
        try (InputStream in = c.getAssets().open("logo.png")) {
            logo = BitmapFactory.decodeStream(in);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            logo = null;
        }
    }

    void start() {
        if (running) return;
        running = true;
        started = SystemClock.uptimeMillis();
        watch.start();
        Choreographer.getInstance().postFrameCallback(this);
    }

    void stop() {
        running = false;
        watch.stop();
        Choreographer.getInstance().removeFrameCallback(this);
    }

    /** OK: nothing to open here (the other screensaver opens titles). */
    void watch() {
    }

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastDraw >= 33) {   // about 30 frames a second: enough, and kind to a small TV box
            lastDraw = now;
            invalidate();
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void updateClock() {
        long now = System.currentTimeMillis(), minute = now / 60_000;
        if (minute == clockMinute) return;
        clockMinute = minute;
        Date d = new Date(now);
        boolean h24 = Ui.h24();
        clockText = new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", Locale.US).format(d);
        ampmText = h24 ? "" : new SimpleDateFormat("a", Locale.getDefault()).format(d).toUpperCase(Locale.getDefault());
        dateText = new SimpleDateFormat("EEEE, d 'de' MMMM", Locale.getDefault()).format(d).toUpperCase(Locale.getDefault());
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float t = (SystemClock.uptimeMillis() - started) / 1000f;
        updateClock();
        c.drawColor(0xFF000000);
        // the wallpaper, a slow breath of zoom and a drift
        if (photo != null) {
            float zoom = 1.06f + 0.05f * (float) Math.sin(t / 45.0);
            float pw = w * zoom, ph = h * zoom;
            float ox = (float) Math.sin(t / 60.0) * w * 0.02f, oy = (float) Math.cos(t / 75.0) * h * 0.02f;
            r.set((w - pw) / 2 + ox, (h - ph) / 2 + oy, (w + pw) / 2 + ox, (h + ph) / 2 + oy);
            c.drawBitmap(photo, null, r, fill);
        }
        fill.setColor(0xB0050106);
        c.drawRect(0, 0, w, h, fill);
        // the vignette: darker at the edges
        if (vignette == null || vignetteW != w) {
            vignette = new RadialGradient(w / 2f, h / 2f, w * 0.75f, 0x00000000, 0xDD000000, Shader.TileMode.CLAMP);
            vignetteW = w;
        }
        fill.setShader(vignette);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);
        // the embers
        for (int i = 0; i < EMBERS; i++) {
            float y = (ey[i] - t * ev[i]) % 1f;
            if (y < 0) y += 1f;
            float x = ex[i] + (float) Math.sin(t * 0.4f + ep[i]) * 0.02f;
            float flick = 0.45f + 0.55f * (float) Math.abs(Math.sin(t * 1.3f + ep[i] * 3));
            int a = Math.round(200 * flick * (0.4f + 0.6f * (1 - y)));
            fill.setColor((a << 24) | (i % 3 == 0 ? 0xFF8A2A : 0xFF1F2F));
            c.drawCircle(x * w, y * h, es[i] * k, fill);
        }
        // everything else drifts slowly (the TV's screen)
        c.save();
        c.translate((float) Math.sin(t / 55.0) * 30 * k, (float) Math.cos(t / 70.0) * 22 * k);
        float x = 120 * k, base = 560 * k;
        c.drawText("TIAN DEMON TV", x, base - 250 * k, caps);
        c.drawText(clockText, x - 6 * k, base, big);
        float cx = x + big.measureText(clockText) + 22 * k;
        if (!ampmText.isEmpty()) {
            c.drawText(ampmText, cx, base, ampm);
            cx += ampm.measureText(ampmText) + 34 * k;
        } else {
            cx += 12 * k;
        }
        float cap = Ui.cap(big);
        fill.setColor(0x88FFFFFF);
        c.drawRect(cx, base - cap * 0.95f, cx + 3 * k, base, fill);
        c.drawText(dateText, cx + 34 * k, base - cap * 0.4f, caps);
        HomeData.Weather wx = data.weather;
        if (!Double.isNaN(wx.temp)) {
            String symbol = Symbols.weather(wx.code, wx.isDay);
            Symbols.draw(getContext(), c, symbol, x + 24 * k, base + 74 * k, 44 * k, 0xDDFFFFFF);
            c.drawText(HomeData.degrees(wx.temp) + "   " + HomeData.condition(wx.code, wx.isDay), x + 64 * k, base + 86 * k, small);
        }
        // the music
        String title = watch.cardTitle;
        if (title != null) {
            float my = 880 * k, art = 96 * k;
            Bitmap cover = watch.cardArt;
            r.set(x, my, x + art, my + art);
            fill.setColor(0xFF3A0A12);
            c.drawRoundRect(r, 20 * k, 20 * k, fill);
            if (cover != null && !cover.isRecycled()) c.drawBitmap(cover, null, r, fill);
            c.drawText(fit(title, music, 760 * k), x + art + 26 * k, my + 42 * k, music);
            if (watch.cardArtist != null) c.drawText(fit(watch.cardArtist, artist, 760 * k), x + art + 26 * k, my + 80 * k, artist);
            for (int i = 0; i < 12; i++) {
                float lv = watch.cardPlaying ? 0.25f + 0.75f * Math.abs((float) Math.sin(t * 5 + i * 1.7) * (float) Math.cos(t * 3 + i * 0.6)) : 0.2f;
                float hh = (8 + 44 * lv) * k;
                float bx = x + art + 26 * k + 560 * k + i * 11 * k, mid = my + art / 2;
                fill.setColor(0xFFFFFFFF);
                r.set(bx, mid - hh / 2, bx + 5 * k, mid + hh / 2);
                c.drawRoundRect(r, 2.5f * k, 2.5f * k, fill);
            }
        }
        c.restore();
        // the emblem, breathing, at the lower right
        if (logo != null) {
            float s = (210 + 14 * (float) Math.sin(t * 0.9)) * k;
            int a = Math.round(255 * (0.35f + 0.18f * (float) Math.sin(t * 0.9)));
            fill.setAlpha(a);
            r.set(w - 140 * k - s, h - 80 * k - s, w - 140 * k, h - 80 * k);
            r.offset((float) Math.sin(t / 65.0) * 20 * k, (float) Math.cos(t / 80.0) * 16 * k);
            c.drawBitmap(logo, null, r, fill);
            fill.setAlpha(255);
        }
    }

    private static String fit(String s, Paint p, float w) {
        if (p.measureText(s) <= w) return s;
        int n = p.breakText(s, true, Math.max(0, w - p.measureText("…")), null);
        return s.substring(0, Math.max(0, n)) + "…";
    }
}
