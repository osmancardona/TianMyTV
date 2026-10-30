package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.View;

import java.util.Locale;

/**
 * Now Playing, full screen: the cover big on the left, the title, artist and album, the buttons previous, play or pause
 * and next, and the progress bar, over the cover blurred. Works with the d-pad: left and right choose a button, up goes
 * to the bar (left and right then seek 10 seconds), OK presses.
 */
public class NowPlayingActivity extends Activity {
    private PlayView view;
    private NowPlaying.Watch watch;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            view.invalidate();
            handler.postDelayed(this, 120);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        overridePendingTransition(0, 0);
        view = new PlayView(this);
        setContentView(view);
        watch = new NowPlaying.Watch(this, () -> view.invalidate());
    }

    @Override protected void onStart() {
        super.onStart();
        watch.start();
        handler.post(tick);
    }

    @Override protected void onStop() {
        handler.removeCallbacks(tick);
        watch.stop();
        super.onStop();
        if (!isFinishing()) finish();
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (view.onKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    private static String fit(String s, Paint p, float w) {
        if (p.measureText(s) <= w) return s;
        int n = p.breakText(s, true, Math.max(0, w - p.measureText("…")), null);
        return s.substring(0, Math.max(0, n)) + "…";
    }

    private final class PlayView extends View {
        private final float k;
        private int focus = 1;   // 0 previous, 1 play or pause, 2 next, 3 the bar
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint kicker = Ui.text(Ui.semibold, 1, 0xB3FFFFFF), title = Ui.text(Ui.displaySemibold, 1, 0xFFFFFFFF),
                artist = Ui.text(Ui.semibold, 1, 0xF2FFFFFF), album = Ui.text(Ui.medium, 1, 0x99FFFFFF),
                time = Ui.numbers(Ui.medium, 1, 0xCCFFFFFF);
        private final RectF rect = new RectF(), tmp = new RectF();
        private final Matrix matrix = new Matrix();
        private final Path path = new Path();
        private Bitmap blurFor, blur;

        PlayView(Context c) {
            super(c);
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            k = dm.widthPixels / 1920f;
            setFocusable(true);
            kicker.setTextSize(30 * k);
            kicker.setLetterSpacing(0.14f);
            title.setTextSize(84 * k);
            artist.setTextSize(52 * k);
            album.setTextSize(36 * k);
            time.setTextSize(28 * k);
        }

        boolean onKey(KeyEvent e) {
            int code = e.getKeyCode();
            boolean ours = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                    || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                    || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
            if (!ours || e.getAction() != KeyEvent.ACTION_DOWN) return ours;
            MediaController mc = watch.card;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (focus == 3) seek(mc, e.getRepeatCount() > 3 ? -30_000 : -10_000);
                    else if (focus == 4) volume(-1);
                    else focus = Math.max(0, focus - 1);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (focus == 3) seek(mc, e.getRepeatCount() > 3 ? 30_000 : 10_000);
                    else if (focus == 4) volume(1);
                    else focus = Math.min(2, focus + 1);
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    focus = focus == 4 ? 1 : 3;
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    focus = focus == 3 ? 1 : 4;
                    break;
                default:
                    if (focus < 3) watch.control(focus);
                    break;
            }
            invalidate();
            return true;
        }

        private void volume(int dir) {
            AudioManager am = getSystemService(AudioManager.class);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), step = Math.max(1, Math.round(max / 15f));
            int now = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(0, Math.min(max, now + dir * step)), 0);
        }

        private void seek(MediaController mc, long delta) {
            if (mc == null) return;
            long to = Math.max(0, position(mc) + delta);
            long dur = duration(mc);
            if (dur > 0) to = Math.min(to, dur);
            mc.getTransportControls().seekTo(to);
        }

        private long position(MediaController mc) {
            PlaybackState s = mc.getPlaybackState();
            if (s == null) return 0;
            long p = s.getPosition();
            if (s.getState() == PlaybackState.STATE_PLAYING && s.getPlaybackSpeed() > 0) {
                p += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
            }
            return Math.max(0, p);
        }

        private long duration(MediaController mc) {
            MediaMetadata m = mc.getMetadata();
            return m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        }

        private String clock(long ms) {
            long s = Math.max(0, ms / 1000);
            return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                    : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
        }

        private void background(Canvas c, Bitmap art) {
            int w = getWidth(), h = getHeight();
            if (art != null && !art.isRecycled()) {
                if (blurFor != art) {
                    blurFor = art;
                    Bitmap a = Bitmap.createScaledBitmap(art, 16, 16, true);
                    for (int size = 32; size <= 256; size *= 2) a = Bitmap.createScaledBitmap(a, size, size, true);
                    blur = a;
                }
                rect.set(0, 0, w, h);
                c.drawBitmap(blur, null, rect, fill);
            } else {
                fill.setShader(new LinearGradient(0, 0, w, h, 0xFF3A0A12, 0xFF0A0306, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, fill);
                fill.setShader(null);
            }
            fill.setShader(new LinearGradient(0, 0, w, 0, 0x55000000, 0xB0000000, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);
        }

        @Override protected void onDraw(Canvas c) {
            Bitmap art = watch.cardArt;
            MediaController mc = watch.card;
            background(c, art);
            String t = watch.cardTitle;
            if (mc == null || t == null) {
                c.drawText("NOTHING PLAYING", 120 * k, 300 * k, kicker);
                title.setTextSize(64 * k);
                c.drawText("Start a song or a video", 120 * k, 400 * k, title);
                title.setTextSize(84 * k);
                return;
            }
            // the cover
            float s = 780 * k, ax = 110 * k, ay = 110 * k;
            tmp.set(ax, ay, ax + s, ay + s);
            fill.setColor(0x66000000);
            rect.set(tmp);
            rect.offset(0, 18 * k);
            rect.inset(-8 * k, -8 * k);
            c.drawRoundRect(rect, 44 * k, 44 * k, fill);
            if (art != null && !art.isRecycled()) {
                BitmapShader sh = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                float sc = Math.max(s / art.getWidth(), s / art.getHeight());
                matrix.setScale(sc, sc);
                matrix.postTranslate(ax + (s - art.getWidth() * sc) / 2, ay + (s - art.getHeight() * sc) / 2);
                sh.setLocalMatrix(matrix);
                fill.setShader(sh);
            } else {
                fill.setColor(0xFF3A0A12);
            }
            c.drawRoundRect(tmp, 40 * k, 40 * k, fill);
            fill.setShader(null);

            // the texts
            float x = 1000 * k, wmax = 1800 * k - x;
            String app = appName(mc.getPackageName());
            String head = ("NOW PLAYING" + (app.isEmpty() ? "" : " · " + app)).toUpperCase(Locale.ROOT);
            c.drawText(head, x, 270 * k, kicker);
            long now = SystemClock.uptimeMillis();
            fill.setColor(0xFFFFFFFF);
            float wx = x + kicker.measureText(head) + 30 * k;
            for (int i = 0; i < 12; i++) {
                float lv = watch.cardPlaying ? 0.25f + 0.75f * Math.abs((float) Math.sin(now / 200.0 + i * 1.7) * (float) Math.cos(now / 340.0 + i * 0.6)) : 0.2f;
                float h = (6 + 30 * lv) * k;
                tmp.set(wx + i * 9 * k, 262 * k - h / 2 - 8 * k, wx + i * 9 * k + 4.5f * k, 262 * k + h / 2 - 8 * k);
                c.drawRoundRect(tmp, 2 * k, 2 * k, fill);
            }
            c.drawText(fit(t, title, wmax), x, 390 * k, title);
            if (watch.cardArtist != null) {
                c.drawText(fit(watch.cardArtist, artist, wmax), x, 470 * k, artist);
            }
            MediaMetadata m = mc.getMetadata();
            String alb = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_ALBUM);
            if (!TextUtils.isEmpty(alb)) {
                c.drawText(fit(alb, album, wmax), x, 528 * k, album);
            }

            // the buttons
            float by = 690 * k, r = 44 * k;
            for (int i = 0; i < 3; i++) {
                float bx = x + r + i * 130 * k;
                boolean on = focus == i;
                float rr = on ? r * 1.1f : r;
                fill.setColor(on ? 0xFFFFFFFF : 0x40FFFFFF);
                c.drawCircle(bx, by, rr, fill);
                fill.setColor(on ? 0xFF1A0408 : 0xFFFFFFFF);
                float u = 17 * k;
                path.reset();
                if (i == 1 && !watch.cardPlaying) {
                    path.moveTo(bx - u * 0.7f, by - u * 1.1f);
                    path.lineTo(bx + u * 1.1f, by);
                    path.lineTo(bx - u * 0.7f, by + u * 1.1f);
                    path.close();
                } else if (i == 1) {
                    path.addRect(bx - u * 0.9f, by - u, bx - u * 0.25f, by + u, Path.Direction.CW);
                    path.addRect(bx + u * 0.25f, by - u, bx + u * 0.9f, by + u, Path.Direction.CW);
                } else {
                    float d = i == 0 ? -1 : 1;
                    path.addRect(bx + d * u * 0.95f - u * 0.17f, by - u, bx + d * u * 0.95f + u * 0.17f, by + u, Path.Direction.CW);
                    path.moveTo(bx - d * u * 0.9f, by - u);
                    path.lineTo(bx + d * u * 0.7f, by);
                    path.lineTo(bx - d * u * 0.9f, by + u);
                    path.close();
                }
                c.drawPath(path, fill);
            }

            // the volume: a speaker, a bar and the level
            AudioManager am = getSystemService(AudioManager.class);
            int vmax = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), vnow = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            boolean von = focus == 4;
            float vy = 840 * k, vl = x + 70 * k, vr = vl + 560 * k, vf = vnow / (float) vmax;
            Symbols.draw(getContext(), c, "speaker", x + 24 * k, vy, 40 * k, von ? 0xFFFFFFFF : 0xB3FFFFFF);
            fill.setColor(0x44FFFFFF);
            tmp.set(vl, vy - (von ? 8 : 5) * k, vr, vy + (von ? 8 : 5) * k);
            c.drawRoundRect(tmp, 8 * k, 8 * k, fill);
            fill.setColor(0xFFFFFFFF);
            tmp.right = vl + (vr - vl) * vf;
            c.drawRoundRect(tmp, 8 * k, 8 * k, fill);
            c.drawCircle(vl + (vr - vl) * vf, vy, (von ? 15 : 10) * k, fill);
            String vt = Math.round(vf * 100) + "%";
            c.drawText(vt, vr + 30 * k, vy + 10 * k, time);

            // the progress bar
            long pos = position(mc), dur = duration(mc);
            float bl = 110 * k, br = 1810 * k, bar = 990 * k;
            float frac = dur > 0 ? Math.min(1, pos / (float) dur) : 0;
            boolean on = focus == 3;
            fill.setColor(0x44FFFFFF);
            tmp.set(bl, bar - (on ? 4 : 1.5f) * k, br, bar + (on ? 4 : 1.5f) * k);
            c.drawRoundRect(tmp, 4 * k, 4 * k, fill);
            fill.setColor(0xFFFFFFFF);
            tmp.right = bl + (br - bl) * frac;
            c.drawRoundRect(tmp, 4 * k, 4 * k, fill);
            c.drawCircle(bl + (br - bl) * frac, bar, (on ? 12 : 6) * k, fill);
            if (on) {
                time.setTextAlign(Paint.Align.CENTER);
                c.drawText("◀ 10s     10s ▶", (bl + br) / 2, bar + 52 * k, time);
                time.setTextAlign(Paint.Align.LEFT);
            }
            c.drawText(clock(pos), bl, bar - 28 * k, time);
            if (dur > 0) {
                String d = clock(dur);
                c.drawText(d, br - time.measureText(d), bar - 28 * k, time);
            }
        }

        private String appName(String pkg) {
            try {
                PackageManager pm = getPackageManager();
                return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (PackageManager.NameNotFoundException e) {
                return "";
            }
        }
    }
}
