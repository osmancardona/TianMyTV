package io.freemytv;

import android.app.Activity;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.StatFs;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The control center: a glass panel over the home screen. The clock and date, the music with its own buttons and
 * progress, tiles for Wi-Fi, Bluetooth, Audio and Wallpaper, the volume, round shortcuts (search, accessibility,
 * display, Tian Demon TV's settings, Android's), and the memory and storage as bars. Each opens the TV's own screen.
 */
public class ControlCenterActivity extends Activity {
    private CenterView view;
    private NowPlaying.Watch watch;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile String wifiName;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            view.invalidate();
            handler.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        overridePendingTransition(0, 0);
        view = new CenterView(this);
        setContentView(view);
        view.setAlpha(0);
        view.setTranslationX(70 * view.k);
        view.animate().alpha(1).translationX(0).setDuration(260).start();
        watch = new NowPlaying.Watch(this, () -> view.invalidate());
        new Thread(() -> {
            try (Adb adb = Adb.connect(getApplicationContext(), 4000, null)) {
                String out = adb.shell("cmd wifi status");
                int a = out.indexOf("connected to \""), b = a < 0 ? -1 : out.indexOf('"', a + 14);
                if (b > a) wifiName = out.substring(a + 14, b);
            } catch (Exception | Error e) {
                AppLog.w("control center: no Wi-Fi name: " + e.getMessage());
            }
            handler.post(() -> view.invalidate());
        }).start();
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
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) Click.playBack(this);
        if (view.onKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    private void open(Intent... intents) {
        for (Intent intent : intents) {
            try {
                startActivity(new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                finish();
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                AppLog.w("control center: couldn't open " + intent + ": " + e.getMessage());
            }
        }
    }

    private static final String TV = "com.android.tv.settings";

    private void activate(String id) {
        switch (id) {
            case "cover":
                open(new Intent(this, NowPlayingActivity.class));
                break;
            case "wifi":
                open(new Intent(Settings.ACTION_WIFI_SETTINGS));
                break;
            case "bluetooth":
                open(new Intent("com.android.tv.settings.DEVICES").setPackage(TV), new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                break;
            case "audio":
                open(new Intent("com.android.tv.action.VIEW_SOUND_SETTINGS").setPackage(TV), new Intent(Settings.ACTION_SOUND_SETTINGS));
                break;
            case "wallpaper":
                open(new Intent(this, WallpaperActivity.class));
                break;
            case "search":
                open(new Intent(Intent.ACTION_ASSIST), new Intent("android.search.action.GLOBAL_SEARCH"));
                break;
            case "access":
                open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                break;
            case "display":
                open(new Intent(Settings.ACTION_DISPLAY_SETTINGS).setPackage(TV), new Intent(Settings.ACTION_DISPLAY_SETTINGS));
                break;
            case "tian":
                open(new Intent(this, TianSettingsActivity.class));
                break;
            case "android":
                open(new Intent(Settings.ACTION_SETTINGS));
                break;
            default:
                break;
        }
    }

    private String audioName() {
        AudioManager am = getSystemService(AudioManager.class);
        boolean bt = false, hdmi = false, usb = false;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLE_HEADSET) bt = true;
            else if (t == AudioDeviceInfo.TYPE_HDMI || t == AudioDeviceInfo.TYPE_HDMI_ARC || t == AudioDeviceInfo.TYPE_HDMI_EARC) hdmi = true;
            else if (t == AudioDeviceInfo.TYPE_USB_DEVICE || t == AudioDeviceInfo.TYPE_USB_HEADSET) usb = true;
        }
        return bt ? "Bluetooth" : usb ? "USB" : hdmi ? "HDMI" : "Bocinas del TV";
    }

    private String wifiSub() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (nc == null) return "Sin conexión";
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
        String n = wifiName;
        return TextUtils.isEmpty(n) ? "Conectado" : n;
    }

    private String bluetoothSub() {
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null) return "No disponible";
            return a.isEnabled() ? "Activado" : "Apagado";
        } catch (RuntimeException e) {
            return "Ajustes";
        }
    }

    private static final class It {
        final String id, title, symbol;
        final RectF r = new RectF();
        final int kind;   // 0 tile, 1 round button, 2 music button, 3 cover, 4 volume
        int accent;

        It(String id, String title, String symbol, int kind) {
            this.id = id;
            this.title = title;
            this.symbol = symbol;
            this.kind = kind;
        }
    }

    private static String fit(String s, Paint p, float w) {
        if (p.measureText(s) <= w) return s;
        int n = p.breakText(s, true, Math.max(0, w - p.measureText("…")), null);
        return s.substring(0, Math.max(0, n)) + "…";
    }

    private final class CenterView extends View {
        final float k;
        private final List<It> items = new ArrayList<>();
        private int focus;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint head, sub, big, small, caps;
        private final RectF panel = new RectF(), tmp = new RectF(), music = new RectF(), header = new RectF(), footer = new RectF();
        private final Matrix matrix = new Matrix();
        private final Path path = new Path();
        private int mutedFrom = -1;

        CenterView(Context c) {
            super(c);
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            k = dm.widthPixels / 1920f;
            setFocusable(true);
            head = Ui.text(Ui.semibold, 28 * k, 0xFFFFFFFF);
            sub = Ui.text(Ui.medium, 22 * k, 0xB3FFFFFF);
            big = Ui.numbers(Ui.light, 78 * k, 0xFFFFFFFF);
            small = Ui.text(Ui.semibold, 22 * k, 0xCCFFFFFF);
            caps = Ui.text(Ui.semibold, 21 * k, 0x99FFFFFF);
            caps.setLetterSpacing(0.16f);
            float left = 1120 * k, top = 56 * k, pad = 30 * k, gap = 14 * k, pw = 740 * k, inner = pw - 2 * pad;
            panel.set(left, top, left + pw, top + 940 * k);
            float x = left + pad, y = top + pad;
            header.set(x, y, x + inner, y + 110 * k);
            y += 110 * k + 10 * k;
            music.set(x, y, x + inner, y + 214 * k);
            It cover = new It("cover", "", "speaker", 3);
            cover.r.set(x + 18 * k, y + 18 * k, x + 18 * k + 140 * k, y + 18 * k + 140 * k);
            items.add(cover);
            for (int i = 0; i < 3; i++) {
                It b = new It("m" + i, "", "", 2);
                float d = 56 * k, bx = x + 18 * k + 140 * k + 30 * k + i * 72 * k;
                b.r.set(bx, y + 168 * k - 56 * k, bx + d, y + 168 * k);
                items.add(b);
            }
            y += 214 * k + gap;
            float tw = (inner - gap) / 2, th = 92 * k;
            String[][] tiles = {{"wifi", "Wi-Fi", "wifi"}, {"bluetooth", "Bluetooth", "bluetooth"}, {"audio", "Audio", "speaker"}, {"wallpaper", "Fondo", "sunrise"}};
            int[] accents = {0xFF1E7BFF, 0xFF3F7CFF, 0xFFFF375F, 0xFFFF9F0A};
            for (int i = 0; i < 4; i++) {
                It t = new It(tiles[i][0], tiles[i][1], tiles[i][2], 0);
                t.accent = accents[i];
                float tx = x + (i % 2) * (tw + gap), ty = y + (i / 2) * (th + gap);
                t.r.set(tx, ty, tx + tw, ty + th);
                items.add(t);
            }
            y += 2 * (th + gap);
            It vol = new It("volume", "Volumen", "speaker", 4);
            vol.r.set(x, y, x + inner, y + 78 * k);
            items.add(vol);
            y += 78 * k + 18 * k;
            String[][] round = {{"search", "Buscar", "search"}, {"access", "Accesibilidad", "accessibility"}, {"display", "Pantalla", "tv"},
                    {"tian", "Tian", "sparkles"}, {"android", "Android", "settings"}};
            float d = 92 * k, space = (inner - 5 * d) / 4;
            for (int i = 0; i < 5; i++) {
                It b = new It(round[i][0], round[i][1], round[i][2], 1);
                float bx = x + i * (d + space);
                b.r.set(bx, y, bx + d, y + d);
                items.add(b);
            }
            y += d + 44 * k + 26 * k;
            footer.set(x, y, x + inner, y + 70 * k);
            panel.bottom = footer.bottom + pad;
            focus = 2;
        }

        boolean onKey(KeyEvent e) {
            int code = e.getKeyCode();
            boolean ours = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP
                    || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
            if (!ours || e.getAction() != KeyEvent.ACTION_DOWN) return ours;
            It cur = items.get(focus);
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (cur.kind == 4) volume(-1); else move(-1, 0);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (cur.kind == 4) volume(1); else move(1, 0);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP: move(0, -1); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: move(0, 1); return true;
                default:
                    if (e.getRepeatCount() > 0) return true;
                    if (cur.kind == 2) watch.control(Integer.parseInt(cur.id.substring(1)));
                    else if (cur.kind == 4) mute();
                    else {
                        Click.playOpen(ControlCenterActivity.this);
                        activate(cur.id);
                    }
                    invalidate();
                    return true;
            }
        }

        private void volume(int dir) {
            AudioManager am = getSystemService(AudioManager.class);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), step = Math.max(1, Math.round(max / 15f));
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(0, Math.min(max, am.getStreamVolume(AudioManager.STREAM_MUSIC) + dir * step)), 0);
            mutedFrom = -1;
            invalidate();
        }

        private void mute() {
            AudioManager am = getSystemService(AudioManager.class);
            int now = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (now > 0) {
                mutedFrom = now;
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            } else if (mutedFrom > 0) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, mutedFrom, 0);
                mutedFrom = -1;
            }
        }

        private void move(int dx, int dy) {
            It cur = items.get(focus);
            float cx = cur.r.centerX(), cy = cur.r.centerY();
            int best = -1;
            float bestScore = Float.MAX_VALUE;
            for (int i = 0; i < items.size(); i++) {
                if (i == focus) continue;
                It it = items.get(i);
                float ddx = it.r.centerX() - cx, ddy = it.r.centerY() - cy;
                float main = dx != 0 ? ddx * dx : ddy * dy, side = dx != 0 ? Math.abs(ddy) : Math.abs(ddx);
                if (main <= 1) continue;
                float score = main + 2.5f * side;
                if (score < bestScore) {
                    bestScore = score;
                    best = i;
                }
            }
            if (best >= 0) {
                focus = best;
                invalidate();
            }
        }

        private void glass(Canvas c, RectF r, float rad, int a1, int a2) {
            fill.setStyle(Paint.Style.FILL);
            fill.setShader(new LinearGradient(0, r.top, 0, r.bottom, a1, a2, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, rad, rad, fill);
            fill.setShader(null);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(1.6f * k);
            fill.setColor(0x44FF4A55);
            c.drawRoundRect(r, rad, rad, fill);
            fill.setStyle(Paint.Style.FILL);
        }

        private void ring(Canvas c, RectF r, float rad) {
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(10 * k);
            fill.setColor(0x44FF1A2A);
            c.drawRoundRect(r, rad, rad, fill);
            fill.setStrokeWidth(4 * k);
            fill.setColor(0xFFFFFFFF);
            c.drawRoundRect(r, rad, rad, fill);
            fill.setStyle(Paint.Style.FILL);
        }

        @Override protected void onDraw(Canvas c) {
            fill.setColor(0x99000000);
            c.drawRect(0, 0, getWidth(), getHeight(), fill);
            // the panel
            fill.setStyle(Paint.Style.STROKE);
            for (int i = 4; i >= 1; i--) {
                fill.setColor(((0x10 + (4 - i) * 0x0A) << 24) | 0xFF1020);
                fill.setStrokeWidth((4 + i * 6) * k);
                c.drawRoundRect(panel, 52 * k, 52 * k, fill);
            }
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0xFF12040A);
            c.drawRoundRect(panel, 52 * k, 52 * k, fill);
            glass(c, panel, 52 * k, 0xFC3A0C16, 0xFC0A0306);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(3.5f * k);
            fill.setColor(0xFFF0121F);
            c.drawRoundRect(panel, 52 * k, 52 * k, fill);
            fill.setStyle(Paint.Style.FILL);

            drawHeader(c);
            drawMusicCard(c);
            for (It it : items) {
                boolean on = items.get(focus) == it;
                switch (it.kind) {
                    case 0: drawTile(c, it, on); break;
                    case 1: drawRound(c, it, on); break;
                    case 4: drawVolume(c, it, on); break;
                    default: break;
                }
            }
            drawFooter(c);
            It cur = items.get(focus);
            if (cur.kind == 3) ring(c, cur.r, 22 * k);
        }

        private void drawHeader(Canvas c) {
            long now = System.currentTimeMillis();
            String time = new SimpleDateFormat("h:mm", Locale.US).format(new Date(now));
            String ampm = new SimpleDateFormat("a", Locale.getDefault()).format(new Date(now)).toUpperCase(Locale.getDefault());
            String day = new SimpleDateFormat("EEEE, d 'de' MMMM", Locale.getDefault()).format(new Date(now)).toUpperCase(Locale.getDefault());
            c.drawText(time, header.left - 2 * k, header.top + 76 * k, big);
            small.setLetterSpacing(0.08f);
            c.drawText(ampm, header.left + big.measureText(time) + 14 * k, header.top + 76 * k, small);
            small.setLetterSpacing(0);
            caps.setTextAlign(Paint.Align.RIGHT);
            c.drawText(day, header.right, header.top + 44 * k, caps);
            sub.setTextAlign(Paint.Align.RIGHT);
            c.drawText(fit(wifiSub(), sub, 300 * k), header.right, header.top + 82 * k, sub);
            sub.setTextAlign(Paint.Align.LEFT);
            caps.setTextAlign(Paint.Align.LEFT);
        }

        private void drawMusicCard(Canvas c) {
            glass(c, music, 34 * k, 0xB0301018, 0xB0140408);
            It cover = items.get(0);
            Bitmap art = watch.cardArt;
            if (art != null && !art.isRecycled()) {
                BitmapShader sh = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                float s = cover.r.width(), sc = Math.max(s / art.getWidth(), s / art.getHeight());
                matrix.setScale(sc, sc);
                matrix.postTranslate(cover.r.left + (s - art.getWidth() * sc) / 2, cover.r.top + (s - art.getHeight() * sc) / 2);
                sh.setLocalMatrix(matrix);
                fill.setShader(sh);
            } else {
                fill.setColor(0xFF3A0A12);
            }
            c.drawRoundRect(cover.r, 22 * k, 22 * k, fill);
            fill.setShader(null);
            String t = watch.cardTitle, a = watch.cardArtist;
            float tx = cover.r.right + 26 * k, wmax = music.right - tx - 22 * k;
            if (t == null) {
                c.drawText("Nada sonando", tx, music.top + 70 * k, head);
                return;
            }
            c.drawText(fit(t, head, wmax), tx, music.top + 56 * k, head);
            if (a != null) c.drawText(fit(a, sub, wmax), tx, music.top + 92 * k, sub);
            for (int i = 1; i <= 3; i++) {
                It b = items.get(i);
                boolean on = items.get(focus) == b;
                float cx = b.r.centerX(), cy = b.r.centerY(), r = b.r.width() / 2 * (on ? 1.1f : 1f);
                fill.setColor(on ? 0xFFFFFFFF : 0x40FFFFFF);
                c.drawCircle(cx, cy, r, fill);
                fill.setColor(on ? 0xFF1A0408 : 0xFFFFFFFF);
                float u = 13 * k;
                path.reset();
                if (i == 2 && !watch.cardPlaying) {
                    path.moveTo(cx - u * 0.7f, cy - u * 1.1f);
                    path.lineTo(cx + u * 1.1f, cy);
                    path.lineTo(cx - u * 0.7f, cy + u * 1.1f);
                    path.close();
                } else if (i == 2) {
                    path.addRect(cx - u * 0.9f, cy - u, cx - u * 0.25f, cy + u, Path.Direction.CW);
                    path.addRect(cx + u * 0.25f, cy - u, cx + u * 0.9f, cy + u, Path.Direction.CW);
                } else {
                    float d = i == 1 ? -1 : 1;
                    path.addRect(cx + d * u * 0.95f - u * 0.17f, cy - u, cx + d * u * 0.95f + u * 0.17f, cy + u, Path.Direction.CW);
                    path.moveTo(cx - d * u * 0.9f, cy - u);
                    path.lineTo(cx + d * u * 0.7f, cy);
                    path.lineTo(cx - d * u * 0.9f, cy + u);
                    path.close();
                }
                c.drawPath(path, fill);
            }
            MediaController mc = watch.card;
            if (mc != null) {
                PlaybackState s = mc.getPlaybackState();
                MediaMetadata m = mc.getMetadata();
                long dur = m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
                if (s != null && dur > 0) {
                    long pos = s.getPosition();
                    if (s.getState() == PlaybackState.STATE_PLAYING) {
                        pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
                    }
                    float f = Math.max(0, Math.min(1, pos / (float) dur));
                    float bl = cover.r.left, br = music.right - 18 * k, by = music.bottom - 22 * k;
                    fill.setColor(0x44FFFFFF);
                    tmp.set(bl, by - 2 * k, br, by + 2 * k);
                    c.drawRoundRect(tmp, 2 * k, 2 * k, fill);
                    fill.setColor(0xFFFFFFFF);
                    tmp.right = bl + (br - bl) * f;
                    c.drawRoundRect(tmp, 2 * k, 2 * k, fill);
                    c.drawCircle(tmp.right, by, 5 * k, fill);
                }
            }
        }

        private void drawTile(Canvas c, It it, boolean on) {
            glass(c, it.r, 30 * k, on ? 0xF0481220 : 0xB0301018, on ? 0xF0200508 : 0xB0140408);
            float cy = it.r.centerY(), r = 28 * k, cx = it.r.left + 22 * k + r;
            fill.setColor(it.accent);
            c.drawCircle(cx, cy, r, fill);
            Symbols.draw(getContext(), c, it.symbol, cx, cy, 30 * k, 0xFFFFFFFF);
            String s = it.id.equals("wifi") ? wifiSub() : it.id.equals("bluetooth") ? bluetoothSub() : it.id.equals("audio") ? audioName() : currentWallpaper();
            float tx = cx + r + 16 * k, wmax = it.r.right - tx - 14 * k;
            c.drawText(fit(it.title, head, wmax), tx, cy - 4 * k, head);
            c.drawText(fit(s, sub, wmax), tx, cy + 28 * k, sub);
            if (on) ring(c, it.r, 30 * k);
        }

        private String currentWallpaper() {
            String id = getSharedPreferences("tian", MODE_PRIVATE).getString("wallpaper", "builtin:Alastor");
            return id.startsWith("builtin:") ? id.substring(8) : "Mi foto";
        }

        private void drawRound(Canvas c, It it, boolean on) {
            glass(c, it.r, it.r.width() / 2, on ? 0xFF4A1220 : 0xB0301018, on ? 0xFF200508 : 0xB0140408);
            float cx = it.r.centerX(), cy = it.r.centerY();
            if (it.symbol.equals("search")) {
                fill.setStyle(Paint.Style.STROKE);
                fill.setStrokeWidth(5 * k);
                fill.setStrokeCap(Paint.Cap.ROUND);
                fill.setColor(0xFFFFFFFF);
                c.drawCircle(cx - 4 * k, cy - 4 * k, 15 * k, fill);
                c.drawLine(cx + 7 * k, cy + 7 * k, cx + 20 * k, cy + 20 * k, fill);
                fill.setStyle(Paint.Style.FILL);
            } else if (it.id.equals("tian")) {
                drawRune(c, cx, cy);
            } else {
                Symbols.draw(getContext(), c, it.symbol, cx, cy, 38 * k, 0xFFFFFFFF);
            }
            caps.setTextAlign(Paint.Align.CENTER);
            caps.setLetterSpacing(0.04f);
            caps.setColor(on ? 0xFFFFFFFF : 0x99FFFFFF);
            c.drawText(it.title, cx, it.r.bottom + 34 * k, caps);
            caps.setLetterSpacing(0.16f);
            caps.setColor(0x99FFFFFF);
            caps.setTextAlign(Paint.Align.LEFT);
            if (on) ring(c, it.r, it.r.width() / 2);
        }

        /** A demon rune: a ring with four spikes, a diamond and a slit eye in the middle. */
        private void drawRune(Canvas c, float cx, float cy) {
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeCap(Paint.Cap.ROUND);
            fill.setStrokeJoin(Paint.Join.ROUND);
            fill.setColor(0x55FF1A2A);
            fill.setStrokeWidth(9 * k);
            c.drawCircle(cx, cy, 24 * k, fill);
            fill.setColor(0xFFFFFFFF);
            fill.setStrokeWidth(3 * k);
            c.drawCircle(cx, cy, 24 * k, fill);
            // the four spikes
            for (int i = 0; i < 4; i++) {
                double a = Math.PI / 2 * i;
                float ux = (float) Math.cos(a), uy = (float) Math.sin(a);
                path.reset();
                path.moveTo(cx + ux * 24 * k - uy * 5 * k, cy + uy * 24 * k + ux * 5 * k);
                path.lineTo(cx + ux * 37 * k, cy + uy * 37 * k);
                path.lineTo(cx + ux * 24 * k + uy * 5 * k, cy + uy * 24 * k - ux * 5 * k);
                fill.setStrokeWidth(2.6f * k);
                c.drawPath(path, fill);
            }
            // the diamond
            path.reset();
            path.moveTo(cx, cy - 16 * k);
            path.lineTo(cx + 11 * k, cy);
            path.lineTo(cx, cy + 16 * k);
            path.lineTo(cx - 11 * k, cy);
            path.close();
            fill.setStrokeWidth(2.6f * k);
            c.drawPath(path, fill);
            // the slit eye
            fill.setStyle(Paint.Style.FILL);
            path.reset();
            path.moveTo(cx, cy - 10 * k);
            path.quadTo(cx + 4.5f * k, cy, cx, cy + 10 * k);
            path.quadTo(cx - 4.5f * k, cy, cx, cy - 10 * k);
            path.close();
            c.drawPath(path, fill);
            // little dots at the diagonals
            for (int i = 0; i < 4; i++) {
                double a = Math.PI / 4 + Math.PI / 2 * i;
                c.drawCircle(cx + (float) Math.cos(a) * 31 * k, cy + (float) Math.sin(a) * 31 * k, 2 * k, fill);
            }
        }

        private void drawVolume(Canvas c, It it, boolean on) {
            AudioManager am = getSystemService(AudioManager.class);
            int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), now = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            glass(c, it.r, 30 * k, on ? 0xF0481220 : 0xB0301018, on ? 0xF0200508 : 0xB0140408);
            float f = now / (float) max, cy = it.r.centerY();
            Symbols.draw(getContext(), c, "speaker", it.r.left + 46 * k, cy, 36 * k, now == 0 ? 0x88FFFFFF : 0xFFFFFFFF);
            float bl = it.r.left + 90 * k, br = it.r.right - 110 * k;
            fill.setColor(0x44FFFFFF);
            tmp.set(bl, cy - 5 * k, br, cy + 5 * k);
            c.drawRoundRect(tmp, 5 * k, 5 * k, fill);
            fill.setColor(0xFFFFFFFF);
            tmp.right = bl + (br - bl) * f;
            c.drawRoundRect(tmp, 5 * k, 5 * k, fill);
            c.drawCircle(tmp.right, cy, (on ? 13 : 9) * k, fill);
            sub.setTextAlign(Paint.Align.RIGHT);
            c.drawText(Math.round(f * 100) + "%", it.r.right - 28 * k, cy + 8 * k, sub);
            sub.setTextAlign(Paint.Align.LEFT);
            if (on) ring(c, it.r, 30 * k);
        }

        private void drawFooter(Canvas c) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            getSystemService(ActivityManager.class).getMemoryInfo(mi);
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            double gb = 1024.0 * 1024 * 1024;
            double ramTotal = mi.totalMem / gb, ramUsed = ramTotal - mi.availMem / gb;
            double stTotal = fs.getTotalBytes() / gb, stUsed = stTotal - fs.getAvailableBytes() / gb;
            float half = (footer.width() - 30 * k) / 2;
            drawMeter(c, footer.left, footer.top, half, "RAM", String.format(Locale.US, "%.1f de %.1f GB", ramUsed, ramTotal), (float) (ramUsed / ramTotal), 0xFFFF9F0A);
            drawMeter(c, footer.left + half + 30 * k, footer.top, half, "ESPACIO", String.format(Locale.US, "%.1f de %.1f GB", stUsed, stTotal), (float) (stUsed / stTotal), 0xFF1E7BFF);
        }

        private void drawMeter(Canvas c, float x, float y, float w, String name, String value, float f, int color) {
            c.drawText(name, x, y + 20 * k, caps);
            sub.setTextAlign(Paint.Align.RIGHT);
            c.drawText(value, x + w, y + 20 * k, sub);
            sub.setTextAlign(Paint.Align.LEFT);
            fill.setColor(0x33FFFFFF);
            tmp.set(x, y + 36 * k, x + w, y + 46 * k);
            c.drawRoundRect(tmp, 5 * k, 5 * k, fill);
            fill.setColor(color);
            tmp.right = x + w * Math.max(0.02f, Math.min(1, f));
            c.drawRoundRect(tmp, 5 * k, 5 * k, fill);
        }
    }
}
