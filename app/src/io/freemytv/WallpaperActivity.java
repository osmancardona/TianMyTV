package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wallpaper: the two that come with the app (Alastor and the TIAN emblem), "search online", and the folders with what you
 * downloaded (100 to a folder; the next one opens by itself). OK puts one behind the home screen at once; holding OK on a
 * downloaded one brings up a card from the bottom edge to delete it, and the picture flies into the trash.
 */
public class WallpaperActivity extends Activity {
    private static final String[] BUILT_IN = {"Alastor", "Emblema TIAN"};
    static final int PER_FOLDER = 100;

    private WallView view;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbWorker = Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        overridePendingTransition(0, 0);
        view = new WallView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        if (view != null) view.reload();   // coming back from the catalog: what was downloaded is there
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        thumbWorker.shutdownNow();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) Click.playBack(this);
        if (view.onKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "wallpapers");
        d.mkdirs();
        return d;
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("tian", Context.MODE_PRIVATE);
    }

    /** The downloaded wallpapers, oldest first: folder k holds the ones from 100 k to 100 k + 99. */
    static List<String> downloaded(Context c) {
        File[] all = dir(c).listFiles();
        List<File> list = new ArrayList<>();
        if (all != null) for (File f : all) if (f.getName().startsWith("w-") && f.getName().endsWith(".jpg")) list.add(f);
        Collections.sort(list, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        List<String> ids = new ArrayList<>();
        for (File f : list) ids.add(f.getName());
        return ids;
    }

    /** The picture behind a wallpaper: one of the two that come with the app, or a downloaded file. */
    private static Bitmap load(Context c, String id, int w, int h) {
        try {
            if (id.startsWith("builtin:")) {
                String name = id.substring(8);
                if (name.equals("Alastor")) {
                    try (InputStream in = c.getAssets().open("bg-alastor.jpg")) {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inSampleSize = Math.max(1, 2000 / Math.max(1, w));
                        return BitmapFactory.decodeStream(in, null, o);
                    }
                }
                if (w >= 1500) {   // the screen's size: made once, kept
                    File kept = new File(c.getCacheDir(), "wall-" + name.replace(' ', '-') + ".jpg");
                    if (kept.isFile()) {
                        Bitmap k = BitmapFactory.decodeFile(kept.getPath());
                        if (k != null) return k;
                    }
                    Bitmap made = WallGen.make(c, name, w, h);
                    try (FileOutputStream out = new FileOutputStream(kept)) {
                        made.compress(Bitmap.CompressFormat.JPEG, 96, out);
                    } catch (IOException ignored) {
                    }
                    return made;
                }
                return WallGen.make(c, name, w, h);
            }
            File f = new File(dir(c), id);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = Math.max(1, Math.min(bounds.outWidth / Math.max(1, w), bounds.outHeight / Math.max(1, h)));
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            AppLog.w("wallpaper: " + id + ": " + e.getMessage());
            return null;
        }
    }

    // MARK: - Making the picture the TV's size

    /** A soft blur: three passes of a box (radius r) in each direction. */
    static Bitmap blurred(Bitmap src, int r) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            box(px, tmp, w, h, r, true);
            box(tmp, px, w, h, r, false);
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h, lines = horizontal ? h : w, step = horizontal ? 1 : w, lineStep = horizontal ? w : 1;
        for (int l = 0; l < lines; l++) {
            int base = l * lineStep;
            long sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[base + Math.max(0, Math.min(n - 1, i)) * step];
                sr += (p >> 16) & 255;
                sg += (p >> 8) & 255;
                sb += p & 255;
            }
            int count = 2 * r + 1;
            for (int i = 0; i < n; i++) {
                out[base + i * step] = 0xFF000000 | ((int) (sr / count) << 16) | ((int) (sg / count) << 8) | (int) (sb / count);
                int add = in[base + Math.min(n - 1, i + r + 1) * step], sub = in[base + Math.max(0, i - r) * step];
                sr += ((add >> 16) & 255) - ((sub >> 16) & 255);
                sg += ((add >> 8) & 255) - ((sub >> 8) & 255);
                sb += (add & 255) - (sub & 255);
            }
        }
    }

    /** Doubles a small picture step by step (smoother than one big jump), or shrinks a big one in halves, to w by h. */
    private static Bitmap scaleTo(Bitmap src, int w, int h) {
        Bitmap cur = src;
        while (cur.getWidth() * 2 <= w && cur.getHeight() * 2 <= h) {
            cur = Bitmap.createScaledBitmap(cur, cur.getWidth() * 2, cur.getHeight() * 2, true);
        }
        while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h) {
            cur = Bitmap.createScaledBitmap(cur, cur.getWidth() / 2, cur.getHeight() / 2, true);
        }
        if (cur.getWidth() != w || cur.getHeight() != h) cur = Bitmap.createScaledBitmap(cur, w, h, true);
        return cur;
    }

    /** Where the picture is worth looking at: the edges, the colour and the skin tones, found automatically (0 to 1). */
    static float smartOffset(Bitmap b) {
        float ratio = b.getWidth() / (float) b.getHeight();
        boolean vertical = ratio < 16f / 9;
        int sw = 160, sh = Math.max(12, Math.round(160 / ratio));
        Bitmap small = Bitmap.createScaledBitmap(b, sw, sh, true);
        int[] px = new int[sw * sh];
        small.getPixels(px, 0, sw, 0, 0, sw, sh);
        int n = vertical ? sh : sw;
        float[] energy = new float[n];
        for (int y = 1; y < sh - 1; y++) {
            for (int x = 1; x < sw - 1; x++) {
                int p = px[y * sw + x];
                int r = (p >> 16) & 255, g = (p >> 8) & 255, bl = p & 255;
                float gx = lum(px[y * sw + x + 1]) - lum(px[y * sw + x - 1]), gy = lum(px[(y + 1) * sw + x]) - lum(px[(y - 1) * sw + x]);
                int max = Math.max(r, Math.max(g, bl)), min = Math.min(r, Math.min(g, bl));
                boolean skin = r > 95 && g > 40 && bl > 20 && r > g && r > bl && Math.abs(r - g) > 15 && max - min > 15;
                energy[vertical ? y : x] += (Math.abs(gx) + Math.abs(gy)) * (skin ? 2.5f : 1f) + 0.3f * (max - min);
            }
        }
        int win = vertical ? Math.round(sw * 9f / 16) : Math.round(sh * 16f / 9);
        win = Math.max(1, Math.min(win, n));
        float best = -1;
        int bestStart = 0;
        for (int start = 0; start + win <= n; start++) {
            float sum = 0;
            for (int i = start; i < start + win; i++) sum += energy[i];
            float centre = (start + win / 2f) / n;
            sum *= 1 - 0.15f * Math.abs(centre - 0.42f);
            if (sum > best) {
                best = sum;
                bestStart = start;
            }
        }
        return n - win <= 0 ? 0.5f : bestStart / (float) (n - win);
    }

    private static float lum(int p) {
        return 0.299f * ((p >> 16) & 255) + 0.587f * ((p >> 8) & 255) + 0.114f * (p & 255);
    }

    /** Sharpens by adding back some of what a soft copy loses (a picture made bigger comes out soft). */
    private static Bitmap sharpened(Bitmap src, float amount) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h], soft = new int[w * h], tmp = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        box(px, tmp, w, h, 2, true);
        box(tmp, soft, w, h, 2, false);
        for (int i = 0; i < px.length; i++) {
            int o = px[i], s2 = soft[i];
            int r = clamp(((o >> 16) & 255) + Math.round(amount * (((o >> 16) & 255) - ((s2 >> 16) & 255))));
            int g = clamp(((o >> 8) & 255) + Math.round(amount * (((o >> 8) & 255) - ((s2 >> 8) & 255))));
            int bl = clamp((o & 255) + Math.round(amount * ((o & 255) - (s2 & 255))));
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | bl;
        }
        src.setPixels(px, 0, w, 0, 0, w, h);
        return src;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    /**
     * Draws wallpaper `id` (w-....jpg) for the screen from its original (o-w-....jpg), 2112 by 1188. mode 0 fills the
     * screen with the best part of the picture (`offset` says where, found by smartOffset); mode 1 shows it whole in the
     * middle, over a soft blurred copy of itself that fills the rest.
     */
    static boolean render(Context c, String id, int mode, float offset) {
        Bitmap b = BitmapFactory.decodeFile(new File(dir(c), "o-" + id).getPath());
        if (b == null) return false;
        int tw = Photos.W, th = Photos.H;
        Bitmap screen = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        Canvas sc = new Canvas(screen);
        Paint sp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        RectF dst = new RectF(0, 0, tw, th);
        float off = Math.max(0, Math.min(1, offset));
        if (mode == 0) {
            float scale = Math.max(tw / (float) b.getWidth(), th / (float) b.getHeight());
            int sw = Math.min(b.getWidth(), Math.round(tw / scale)), sh = Math.min(b.getHeight(), Math.round(th / scale));
            int sx = Math.round((b.getWidth() - sw) * (sw < b.getWidth() ? off : 0.5f));
            int sy = Math.round((b.getHeight() - sh) * (sh < b.getHeight() ? off : 0.5f));
            Bitmap part = Bitmap.createBitmap(b, sx, sy, sw, sh);
            sc.drawBitmap(scaleTo(part, tw, th), null, dst, sp);
            if (scale > 1.3f) sharpened(screen, Math.min(0.9f, 0.35f + 0.15f * scale));
        } else {
            float cover = Math.max(tw / (float) b.getWidth(), th / (float) b.getHeight());
            int cw = Math.min(b.getWidth(), Math.round(tw / cover)), ch = Math.min(b.getHeight(), Math.round(th / cover));
            Bitmap mid = Bitmap.createBitmap(b, (b.getWidth() - cw) / 2, (b.getHeight() - ch) / 2, cw, ch);
            Bitmap haze = blurred(scaleTo(mid, 528, 297), 18);
            sc.drawBitmap(Bitmap.createScaledBitmap(haze, tw, th, true), null, dst, sp);
            sp.setColor(0x40000000);
            sc.drawRect(dst, sp);
            sp.setColor(0xFFFFFFFF);
            float fit = Math.min(tw / (float) b.getWidth(), th / (float) b.getHeight());
            int fw = Math.max(1, Math.round(b.getWidth() * fit)), fh = Math.max(1, Math.round(b.getHeight() * fit));
            Bitmap whole = scaleTo(b, fw, fh);
            float fx = (tw - fw) / 2f, fy = (th - fh) / 2f;
            boolean sides = fw < tw - 2;
            float len = sides ? fw : fh, soft = Math.max(1, len * 0.14f);
            int layer = sc.saveLayer(fx, fy, fx + fw, fy + fh, null);
            sc.drawBitmap(whole, fx, fy, sp);
            Paint mask = new Paint();
            int[] ramp = {0x00000000, 0xFF000000, 0xFF000000, 0x00000000};
            float[] pos = {0f, soft / len, 1f - soft / len, 1f};
            mask.setShader(sides ? new android.graphics.LinearGradient(fx, 0, fx + fw, 0, ramp, pos, Shader.TileMode.CLAMP)
                    : new android.graphics.LinearGradient(0, fy, 0, fy + fh, ramp, pos, Shader.TileMode.CLAMP));
            mask.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
            if (fw < tw - 2 || fh < th - 2) sc.drawRect(fx, fy, fx + fw, fy + fh, mask);
            sc.restoreToCount(layer);
        }
        try (FileOutputStream f = new FileOutputStream(new File(dir(c), id))) {
            screen.compress(Bitmap.CompressFormat.JPEG, 97, f);
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    /** Puts wallpaper `id` behind the home screen (off the main thread): the home's own photo, one only. */
    static void applyFile(Context app, String id) {
        Bitmap b = load(app, id, 2112, 1188);
        if (b == null) return;
        Photos.removeAll(app);
        File in = Photos.privateInbox(app);
        in.mkdirs();
        try (FileOutputStream f = new FileOutputStream(new File(in, "wallpaper.jpg"))) {
            b.compress(Bitmap.CompressFormat.JPEG, 97, f);
        } catch (IOException e) {
            AppLog.w("wallpaper: " + e.getMessage());
            return;
        }
        prefs(app).edit().putString("wallpaper", id).apply();
        Prefs.setPhotos(true);
        Photos.importInbox(app, () -> {
            Prefs.setPhotos(true);
            Runnable hook = Photos.photosHook;
            if (hook != null) hook.run();   // the home screen takes it at once
        });
    }

    // MARK: - The screen

    private final class WallView extends View {
        private static final int COLS = 4, ROWS = 3;
        private final float k;
        private final List<String> items = new ArrayList<>();   // what the screen shows now: "search", "builtin:..", "folder:n", or a file
        private List<String> files = new ArrayList<>();         // all downloaded ones
        private int level = -1;                                  // -1 the start, n a folder
        private int focus = 1, scrollRow;
        private String current = "builtin:Alastor", hint = "";
        private final Map<String, Bitmap> thumbs = new HashMap<>();
        private final Set<String> loading = new HashSet<>();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint head = Ui.text(Ui.displaySemibold, 1, 0xFFFFFFFF), sub = Ui.text(Ui.medium, 1, 0xB3FFFFFF),
                label = Ui.text(Ui.semibold, 1, 0xFFFFFFFF), btn = Ui.text(Ui.semibold, 1, 0xFFFFFFFF);
        private final RectF r = new RectF();
        private final Matrix matrix = new Matrix();
        private Bitmap backdrop;
        private long holdAt;
        // the card from the bottom edge and the picture flying into the trash
        private String sheetFor;
        private long sheetAt, deleteAt, doneAt;
        private int sheetBtn;
        private boolean closing;
        private final RectF flyFrom = new RectF();

        WallView(Context c) {
            super(c);
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            k = dm.widthPixels / 1920f;
            setFocusable(true);
            head.setTextSize(58 * k);
            sub.setTextSize(28 * k);
            label.setTextSize(26 * k);
            btn.setTextSize(30 * k);
            btn.setTextAlign(Paint.Align.CENTER);
            current = prefs(c).getString("wallpaper", "builtin:Alastor");
            reload();
        }

        void reload() {
            files = downloaded(getContext());
            items.clear();
            if (level < 0) {
                items.add("search");
                for (String n : BUILT_IN) items.add("builtin:" + n);
                for (int f = 0; f * PER_FOLDER < files.size(); f++) items.add("folder:" + f);
            } else {
                int from = level * PER_FOLDER, to = Math.min(files.size(), from + PER_FOLDER);
                if (from >= files.size()) {
                    level = -1;
                    reload();
                    return;
                }
                for (int i = from; i < to; i++) items.add(files.get(i));
            }
            if (focus >= items.size()) focus = Math.max(0, items.size() - 1);
            loadBackdrop();
            invalidate();
        }

        private void loadBackdrop() {
            final String id = current;
            thumbWorker.execute(() -> {
                Bitmap b = load(getContext(), id, 240, 135);
                handler.post(() -> {
                    backdrop = b;
                    invalidate();
                });
            });
        }

        private String folderName(int f) {
            return files.size() <= PER_FOLDER ? "Descargados" : "Descargados " + (f + 1);
        }

        /** The small picture of a wallpaper (or of the first ones of a folder), loaded when it comes into view. */
        private Bitmap thumb(String id) {
            Bitmap t = thumbs.get(id);
            if (t != null || loading.contains(id)) return t;
            if (id.equals("search") || id.startsWith("folder:")) return null;
            loading.add(id);
            thumbWorker.execute(() -> {
                Bitmap b = load(getContext(), id, 480, 270);
                handler.post(() -> {
                    if (thumbs.size() > 60) thumbs.clear();
                    if (b != null) thumbs.put(id, b);
                    loading.remove(id);
                    invalidate();
                });
            });
            return null;
        }

        void setCurrent(String id) {
            current = id;
            loadBackdrop();
            invalidate();
        }

        boolean onKey(KeyEvent e) {
            int code = e.getKeyCode();
            boolean dpad = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP
                    || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            if (deleteAt != 0) return true;   // the picture is flying: nothing else to do
            if (code == KeyEvent.KEYCODE_BACK) {
                if (!down) {
                    if (sheetFor != null) {
                        closeSheet();
                    } else if (level >= 0) {
                        level = -1;
                        focus = 1;
                        scrollRow = 0;
                        reload();
                    } else {
                        finish();
                    }
                }
                return true;
            }
            if (!dpad) return false;
            if (sheetFor != null) {
                if (down && code == KeyEvent.KEYCODE_DPAD_LEFT) sheetBtn = 0;
                else if (down && code == KeyEvent.KEYCODE_DPAD_RIGHT) sheetBtn = 1;
                else if (down && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && e.getRepeatCount() == 0) {
                    if (sheetBtn == 0) startDelete(); else closeSheet();
                }
                invalidate();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                if (down && e.getRepeatCount() == 0) holdAt = System.currentTimeMillis();
                if (down && e.getRepeatCount() > 0 && e.isLongPress() && holdAt != 0) {
                    holdAt = 0;
                    holdOn();
                } else if (!down && holdAt != 0) {
                    holdAt = 0;
                    choose();
                }
                return true;
            }
            if (!down) return true;
            int next = focus;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) next = focus - 1;
            else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) next = focus + 1;
            else if (code == KeyEvent.KEYCODE_DPAD_UP) next = focus - COLS;
            else next = focus + COLS;
            if (next >= 0 && next < items.size()) {
                focus = next;
                int row = focus / COLS;
                if (row < scrollRow) scrollRow = row;
                if (row >= scrollRow + ROWS) scrollRow = row - ROWS + 1;
                hint = "";
            }
            invalidate();
            return true;
        }

        private void choose() {
            String id = items.get(focus);
            Click.playOpen(getContext());
            if (id.equals("search")) {
                startActivity(new Intent(getContext(), WallBrowseActivity.class));
                return;
            }
            if (id.startsWith("folder:")) {
                level = Integer.parseInt(id.substring(7));
                focus = 0;
                scrollRow = 0;
                reload();
                return;
            }
            hint = "Aplicando...";
            invalidate();
            final Context app = getContext().getApplicationContext();
            setCurrent(id);
            worker.execute(() -> {
                applyFile(app, id);
                handler.post(() -> {
                    hint = "Fondo cambiado";
                    invalidate();
                });
            });
        }

        /** Holding OK on a downloaded wallpaper brings up the card from the bottom edge. */
        private void holdOn() {
            String id = items.get(focus);
            if (id.equals("search") || id.startsWith("builtin:") || id.startsWith("folder:")) {
                if (id.startsWith("builtin:")) hint = "Ese fondo viene con la app y no se borra";
                invalidate();
                return;
            }
            sheetFor = id;
            sheetBtn = 1;
            closing = false;
            sheetAt = SystemClock.uptimeMillis();
            invalidate();
        }

        private void closeSheet() {
            closing = true;
            sheetAt = SystemClock.uptimeMillis();
            invalidate();
        }

        private void startDelete() {
            int row = focus / COLS - scrollRow, col = focus % COLS;
            float cw = 380 * k, ch = 214 * k, gap = 36 * k;
            float x = 110 * k + col * (cw + gap), y = 250 * k + row * (ch + gap + 36 * k);
            flyFrom.set(x, y, x + cw, y + ch);
            deleteAt = SystemClock.uptimeMillis();
            invalidate();
        }

        private float sheetProgress(long now) {
            float t = Math.min(1, (now - sheetAt) / 280f);
            float e = 1 - (1 - t) * (1 - t) * (1 - t);
            return closing ? 1 - e : e;
        }

        private void finishDelete() {
            String id = sheetFor;
            new File(dir(getContext()), id).delete();
            new File(dir(getContext()), "o-" + id).delete();
            thumbs.remove(id);
            final Context app = getContext().getApplicationContext();
            if (id.equals(current)) {
                setCurrent("builtin:Alastor");
                worker.execute(() -> applyFile(app, "builtin:Alastor"));
            }
            hint = "Fondo eliminado";
            reload();
        }

        @Override protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            int w = getWidth(), h = getHeight();
            if (backdrop != null) {
                r.set(0, 0, w, h);
                c.drawBitmap(backdrop, null, r, fill);
            } else {
                c.drawColor(0xFF12040A);
            }
            fill.setColor(0xCC08020A);
            c.drawRect(0, 0, w, h, fill);
            c.drawText(level < 0 ? "Fondo de pantalla" : folderName(level), 110 * k, 130 * k, head);
            sub.setTextAlign(Paint.Align.LEFT);
            c.drawText(hint.isEmpty() ? (level < 0 ? "Elige un fondo y pulsa OK: se pone al instante." : "OK lo pone. Mantén OK para eliminarlo. Atrás vuelve.") : hint, 110 * k, 180 * k, sub);

            float cw = 380 * k, ch = 214 * k, gap = 36 * k, left = 110 * k, top = 250 * k;
            boolean flying = deleteAt != 0;
            float flyT = flying ? Math.min(1, (now - deleteAt) / 1100f) : 0;
            for (int row = 0; row < ROWS; row++) {
                for (int col = 0; col < COLS; col++) {
                    int i = (scrollRow + row) * COLS + col;
                    if (i >= items.size()) continue;
                    String id = items.get(i);
                    if (flying && id.equals(sheetFor)) continue;   // it is in the air
                    drawTile(c, id, left + col * (cw + gap), top + row * (ch + gap + 36 * k), cw, ch, i == focus && sheetFor == null);
                }
            }
            if (sheetFor != null) drawSheet(c, w, h, now, flyT);
            if (flying && flyT >= 1 && doneAt == 0) doneAt = now;
            if (doneAt != 0 && now - doneAt > 700) {
                finishDelete();
                sheetFor = null;
                deleteAt = 0;
                doneAt = 0;
                closing = false;
            }
            if (sheetFor != null && closing && now - sheetAt > 300) sheetFor = null;
            if (sheetFor != null || deleteAt != 0) postInvalidateOnAnimation();
        }

        private void drawTile(Canvas c, String id, float x, float y, float cw, float ch, boolean on) {
            r.set(x, y, x + cw, y + ch);
            String name;
            if (id.equals("search")) {
                fill.setColor(0x33FFFFFF);
                c.drawRoundRect(r, 24 * k, 24 * k, fill);
                fill.setStyle(Paint.Style.STROKE);
                fill.setStrokeWidth(2.5f * k);
                fill.setColor(0x88FFFFFF);
                c.drawRoundRect(r, 24 * k, 24 * k, fill);
                fill.setColor(0xFFFFFFFF);
                fill.setStrokeWidth(6 * k);
                fill.setStrokeCap(Paint.Cap.ROUND);
                c.drawCircle(r.centerX() - 6 * k, r.centerY() - 18 * k, 22 * k, fill);
                c.drawLine(r.centerX() + 10 * k, r.centerY() - 2 * k, r.centerX() + 28 * k, r.centerY() + 16 * k, fill);
                fill.setStyle(Paint.Style.FILL);
                label.setTextAlign(Paint.Align.CENTER);
                c.drawText("Buscar fondos en línea", r.centerX(), r.centerY() + 56 * k, label);
                label.setTextAlign(Paint.Align.LEFT);
                name = null;
            } else if (id.startsWith("folder:")) {
                int f = Integer.parseInt(id.substring(7));
                fill.setColor(0xFF2A0A12);
                c.drawRoundRect(r, 24 * k, 24 * k, fill);
                // a folder: up to four of its pictures side by side
                int from = f * PER_FOLDER, n = Math.min(4, files.size() - from);
                float pw = (cw - 36 * k) / 2f, ph = (ch - 36 * k) / 2f;
                for (int q = 0; q < n; q++) {
                    float px = x + 12 * k + (q % 2) * (pw + 12 * k), py = y + 12 * k + (q / 2) * (ph + 12 * k);
                    drawPicture(c, thumb(files.get(from + q)), px, py, pw, ph, 12 * k);
                }
                name = folderName(f) + " · " + Math.min(PER_FOLDER, files.size() - from);
            } else {
                drawPicture(c, thumb(id), x, y, cw, ch, 24 * k);
                if (id.equals(current)) {
                    fill.setColor(0xFF1DB954);
                    c.drawRoundRect(x + cw - 120 * k, y + 14 * k, x + cw - 14 * k, y + 54 * k, 20 * k, 20 * k, fill);
                    label.setTextAlign(Paint.Align.CENTER);
                    c.drawText("En uso", x + cw - 67 * k, y + 42 * k, label);
                    label.setTextAlign(Paint.Align.LEFT);
                }
                name = id.startsWith("builtin:") ? id.substring(8) : "Descargado";
            }
            if (name != null) c.drawText(name, x + 6 * k, y + ch + 34 * k, label);
            if (on) {
                fill.setStyle(Paint.Style.STROKE);
                fill.setStrokeWidth(6 * k);
                fill.setColor(0xFFFFFFFF);
                c.drawRoundRect(x - 6 * k, y - 6 * k, x + cw + 6 * k, y + ch + 6 * k, 28 * k, 28 * k, fill);
                fill.setStyle(Paint.Style.FILL);
            }
        }

        private void drawPicture(Canvas c, Bitmap t, float x, float y, float cw, float ch, float rad) {
            r.set(x, y, x + cw, y + ch);
            if (t != null) {
                BitmapShader sh = new BitmapShader(t, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                float sc = Math.max(cw / t.getWidth(), ch / t.getHeight());
                matrix.setScale(sc, sc);
                matrix.postTranslate(x + (cw - t.getWidth() * sc) / 2, y + (ch - t.getHeight() * sc) / 2);
                sh.setLocalMatrix(matrix);
                fill.setShader(sh);
            } else {
                fill.setColor(0xFF2A0A12);
            }
            c.drawRoundRect(r, rad, rad, fill);
            fill.setShader(null);
        }

        /** The card that rises from the bottom edge, the trash on it, and the picture that flies into it and fills it. */
        private void drawSheet(Canvas c, int w, int h, long now, float flyT) {
            float p = sheetProgress(now);
            float sh = 250 * k, top = h - sh * p;
            fill.setColor(0x99000000);
            c.drawRect(0, 0, w, top, fill);
            fill.setColor(0xFF000000);
            fill.setShader(new android.graphics.LinearGradient(0, top, 0, h, 0xFF3A0C16, 0xFF0A0306, Shader.TileMode.CLAMP));
            RectF card = new RectF(w / 2f - 560 * k, top, w / 2f + 560 * k, h + 60 * k);
            c.drawRoundRect(card, 56 * k, 56 * k, fill);
            fill.setShader(null);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(3.5f * k);
            fill.setColor(0xFFF0121F);
            c.drawRoundRect(card, 56 * k, 56 * k, fill);
            fill.setStyle(Paint.Style.FILL);
            // the trash
            float tx = card.left + 130 * k, ty = top + 125 * k;
            float filled = deleteAt == 0 ? 0 : Math.max(0, Math.min(1, (flyT - 0.55f) / 0.45f));
            drawTrash(c, tx, ty, filled, deleteAt != 0 && flyT < 0.6f);
            if (deleteAt != 0) {
                // the picture flies from its place into the trash, getting smaller
                float e = flyT * flyT * (3 - 2 * flyT);
                float cx = flyFrom.centerX() + (tx - flyFrom.centerX()) * e, cy = flyFrom.centerY() + (ty - 20 * k - flyFrom.centerY()) * e;
                float s = 1 - 0.86f * e;
                float pw = flyFrom.width() * s, ph = flyFrom.height() * s;
                if (flyT < 0.97f) drawPicture(c, thumbs.get(sheetFor), cx - pw / 2, cy - ph / 2, pw, ph, 18 * k * s);
            }
            // the words and the buttons
            label.setTextSize(34 * k);
            if (deleteAt == 0) {
                c.drawText("Eliminar este fondo", card.left + 240 * k, top + 98 * k, label);
                label.setTextSize(26 * k);
                c.drawText("Se borra de tu TV.", card.left + 240 * k, top + 138 * k, sub);
                for (int i = 0; i < 2; i++) {
                    float bx = card.left + 240 * k + i * 380 * k;
                    r.set(bx, top + 160 * k, bx + 350 * k, top + 230 * k);
                    boolean on = sheetBtn == i;
                    fill.setColor(i == 0 ? (on ? 0xFFFF2A3A : 0xFFC4101F) : (on ? 0x66FFFFFF : 0x28FFFFFF));
                    c.drawRoundRect(r, 35 * k, 35 * k, fill);
                    if (on) {
                        fill.setStyle(Paint.Style.STROKE);
                        fill.setStrokeWidth(4 * k);
                        fill.setColor(0xFFFFFFFF);
                        c.drawRoundRect(r, 35 * k, 35 * k, fill);
                        fill.setStyle(Paint.Style.FILL);
                    }
                    c.drawText(i == 0 ? "Eliminar" : "Cancelar", r.centerX(), r.centerY() + 11 * k, btn);
                }
            } else {
                c.drawText(flyT >= 1 ? "Eliminado" : "Eliminando…", card.left + 240 * k, top + 150 * k, label);
            }
            label.setTextSize(26 * k);
        }

        /** A trash can: the body, what is in it rising as it fills, and the lid (open while the picture goes in). */
        private void drawTrash(Canvas c, float x, float y, float filled, boolean open) {
            Path body = new Path();
            body.moveTo(x - 46 * k, y - 22 * k);
            body.lineTo(x + 46 * k, y - 22 * k);
            body.lineTo(x + 38 * k, y + 56 * k);
            body.lineTo(x - 38 * k, y + 56 * k);
            body.close();
            fill.setColor(0x33FFFFFF);
            c.drawPath(body, fill);
            if (filled > 0) {
                c.save();
                c.clipPath(body);
                fill.setColor(0xFFE3122B);
                c.drawRect(x - 50 * k, y + 56 * k - 78 * k * filled, x + 50 * k, y + 60 * k, fill);
                c.restore();
            }
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(5 * k);
            fill.setStrokeJoin(Paint.Join.ROUND);
            fill.setColor(0xFFFFFFFF);
            c.drawPath(body, fill);
            fill.setStyle(Paint.Style.FILL);
            c.save();
            if (open) c.rotate(-24, x - 52 * k, y - 28 * k);
            fill.setColor(0xFFFFFFFF);
            r.set(x - 56 * k, y - 36 * k, x + 56 * k, y - 26 * k);
            c.drawRoundRect(r, 5 * k, 5 * k, fill);
            r.set(x - 14 * k, y - 48 * k, x + 14 * k, y - 36 * k);
            c.drawRoundRect(r, 5 * k, 5 * k, fill);
            c.restore();
        }
    }
}
