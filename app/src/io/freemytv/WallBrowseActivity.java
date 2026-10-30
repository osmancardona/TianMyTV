package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The online catalog: wallpapers from wallhaven.cc's public search (safe pictures only, 16:9, at least 1920 by 1080), by
 * category or by what you type. OK on one downloads it, makes it the TV's size and puts it behind the home screen. What you
 * choose stays in the list of wallpapers.
 */
public class WallBrowseActivity extends Activity {
    private static final String API = "https://wallhaven.cc/api/v1/search";
    private static final String FULL = "https://w.wallhaven.cc/full/";
    private static final String[][] CATEGORIES = {   // label, search, sorting
        {"Populares", "", "toplist"}, {"Dios", "god", "relevance"}, {"Virgen", "virgin mary guadalupe", "relevance"}, {"Demonios", "demon", "toplist"}, {"Terror", "horror", "toplist"}, {"Gótico", "gothic dark", "toplist"},
        {"Anime", "anime", "toplist"}, {"Juegos", "video game", "toplist"}, {"Autos", "car", "toplist"}, {"Espacio", "space", "toplist"},
        {"Oscuro", "dark", "toplist"}, {"Naturaleza", "nature", "toplist"}, {"Ciudad", "city night", "toplist"}, {"Abstracto", "abstract", "toplist"},
        {"Buscar…", null, null},
    };

    private static final class Item {
        String id, thumb, full, res;
        boolean commons;
        int width, height;
        long size;
        Bitmap bmp;
        boolean loading;
    }

    private View view;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newFixedThreadPool(3);
    private final List<Item> items = new ArrayList<>();
    private int page, lastPage = 1;
    private boolean fetching, failed, busy;
    private String query = "", sorting = "toplist", status = "";
    private float progress;
    private int chip, focus, scrollRow;
    private boolean onChips = true, keyboard;
    private final StringBuilder typed = new StringBuilder();
    private int key;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        overridePendingTransition(0, 0);
        view = new BrowseView(this);
        setContentView(view);
        pick(0);
    }

    @Override protected void onDestroy() {
        net.shutdownNow();
        super.onDestroy();
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (((BrowseView) view).onKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    private void pick(int i) {
        chip = i;
        query = CATEGORIES[i][1] == null ? query : CATEGORIES[i][1];
        sorting = CATEGORIES[i][2] == null ? sorting : CATEGORIES[i][2];
        reset();
    }

    private void reset() {
        items.clear();
        page = 0;
        lastPage = 1;
        focus = 0;
        scrollRow = 0;
        failed = false;
        view.invalidate();
        more();
    }

    /** Spanish words people type, in the English the catalogs understand. */
    static String english(String q) {
        String t = java.text.Normalizer.normalize(q.toLowerCase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").trim();
        String[][] phrases = {{"virgen de guadalupe", "our lady of guadalupe"}, {"santa muerte", "santa muerte"}, {"virgen maria", "virgin mary"}, {"sagrado corazon", "sacred heart"}};
        for (String[] p : phrases) if (t.contains(p[0])) return t.replace(p[0], p[1]);
        String[][] words = {{"dios", "god"}, {"virgen", "virgin mary"}, {"jesus", "jesus christ"}, {"cristo", "christ"}, {"angel", "angel"}, {"angeles", "angels"},
            {"cruz", "cross"}, {"iglesia", "church"}, {"catedral", "cathedral"}, {"santo", "saint"}, {"santa", "saint"}, {"maria", "mary"}, {"calavera", "skull"},
            {"muerte", "death"}, {"demonio", "demon"}, {"demonios", "demon"}, {"diablo", "devil"}, {"infierno", "hell"}, {"cielo", "sky"}, {"dragon", "dragon"},
            {"lobo", "wolf"}, {"leon", "lion"}, {"tigre", "tiger"}, {"aguila", "eagle"}, {"mar", "sea"}, {"playa", "beach"}, {"montana", "mountain"},
            {"montanas", "mountains"}, {"bosque", "forest"}, {"ciudad", "city"}, {"noche", "night"}, {"espacio", "space"}, {"luna", "moon"}, {"sol", "sun"},
            {"fuego", "fire"}, {"agua", "water"}, {"flores", "flowers"}, {"flor", "flower"}, {"carro", "car"}, {"coche", "car"}, {"auto", "car"}, {"autos", "car"},
            {"moto", "motorcycle"}, {"gato", "cat"}, {"perro", "dog"}, {"caballo", "horse"}, {"guerrero", "warrior"}, {"oscuro", "dark"}, {"terror", "horror"},
            {"gotico", "gothic"}, {"juegos", "video game"}, {"videojuego", "video game"}, {"naturaleza", "nature"}, {"abstracto", "abstract"}, {"rosa", "rose"},
            {"rosas", "roses"}, {"mujer", "woman"}, {"guadalupe", "guadalupe"}};
        StringBuilder out = new StringBuilder();
        for (String w : t.split("\\s+")) {
            String r = w;
            for (String[] p : words) if (p[0].equals(w)) { r = p[1]; break; }
            out.append(out.length() == 0 ? "" : " ").append(r);
        }
        return out.toString();
    }

    private static final String UA = "TianDemonTV/0.18 (https://github.com/osmancardona/TianMyTV)";

    /** The next page of the search: wallhaven.cc and Wikimedia Commons, one picture of each in turn. */
    private void more() {
        if (fetching || page >= lastPage) return;
        fetching = true;
        final int want = page + 1;
        final String q = query, s = sorting;
        net.execute(() -> {
            List<Item> wh = new ArrayList<>(), cm = new ArrayList<>();
            int whLast = 1;
            boolean cmMore = false;
            String en = english(q);
            try {
                String url = API + "?q=" + URLEncoder.encode(en, "UTF-8") + "&categories=110&purity=100&atleast=1920x1080&ratios=16x9&sorting="
                        + s + (s.equals("toplist") ? "&topRange=1y" : "") + "&page=" + want;
                JSONObject o = new JSONObject(text(open(url)));
                JSONArray data = o.getJSONArray("data");
                for (int i = 0; i < data.length(); i++) {
                    JSONObject d = data.getJSONObject(i);
                    Item it = new Item();
                    it.id = d.getString("id");
                    it.full = d.getString("path");
                    it.res = d.optString("resolution");
                    it.size = d.optLong("file_size");
                    it.thumb = d.getJSONObject("thumbs").optString("large", d.getJSONObject("thumbs").optString("small"));
                    if (it.size > 18_000_000L) continue;   // a picture that heavy is slow to bring, and no sharper on this TV
                    if (it.full.startsWith(FULL) && it.thumb.startsWith("https://th.wallhaven.cc/")) wh.add(it);
                }
                whLast = Math.max(1, o.getJSONObject("meta").optInt("last_page", want));
            } catch (Exception e) {
                AppLog.w("catalog: wallhaven: " + e.getMessage());
            }
            if (!en.isEmpty()) {
                try {
                    String url = "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=24&gsroffset="
                            + (want - 1) * 24 + "&gsrsearch=" + URLEncoder.encode(en + " filetype:bitmap", "UTF-8")
                            + "&prop=imageinfo&iiprop=url%7Csize%7Cmime&iiurlwidth=1920";
                    JSONObject o = new JSONObject(text(open(url)));
                    JSONObject pages = o.optJSONObject("query") == null ? null : o.getJSONObject("query").optJSONObject("pages");
                    if (pages != null) {
                        List<JSONObject> list = new ArrayList<>();
                        java.util.Iterator<String> keys = pages.keys();
                        while (keys.hasNext()) list.add(pages.getJSONObject(keys.next()));
                        java.util.Collections.sort(list, (x, y) -> Integer.compare(x.optInt("index"), y.optInt("index")));
                        cmMore = list.size() >= 20;
                        for (JSONObject pg : list) {
                            JSONArray info = pg.optJSONArray("imageinfo");
                            if (info == null || info.length() == 0) continue;
                            JSONObject ii = info.getJSONObject(0);
                            String mime = ii.optString("mime");
                            int width = ii.optInt("width"), height = ii.optInt("height");
                            if (!(mime.equals("image/jpeg") || mime.equals("image/png")) || width < 1400 || height < 700 || ii.optLong("size") > 40_000_000L) continue;
                            String thumbUrl = ii.optString("thumburl"), original = ii.optString("url");
                            Item it = new Item();
                            it.commons = true;
                            it.id = "cm" + pg.optInt("pageid");
                            it.full = width > 1920 && thumbUrl.startsWith("https://upload.wikimedia.org/") ? thumbUrl : original;
                            it.thumb = thumbUrl.contains("/1920px-") ? thumbUrl.replace("/1920px-", "/500px-") : it.full;
                            it.width = width;
                            it.height = height;
                            it.res = width + "x" + height;
                            it.size = ii.optLong("size");
                            if (it.full.startsWith("https://upload.wikimedia.org/")) cm.add(it);
                        }
                    }
                } catch (Exception e) {
                    AppLog.w("catalog: commons: " + e.getMessage());
                }
            }
            final List<Item> got = new ArrayList<>();
            for (int i = 0; i < Math.max(wh.size(), cm.size()); i++) {
                if (i < wh.size()) got.add(wh.get(i));
                if (i < cm.size()) got.add(cm.get(i));
            }
            final int last = Math.max(whLast, cmMore ? want + 1 : 1);
            final boolean any = !got.isEmpty();
            ui.post(() -> {
                if (!q.equals(query) || !s.equals(sorting)) {
                    fetching = false;
                    return;
                }
                items.addAll(got);
                page = want;
                lastPage = Math.max(1, last);
                if (!any && want > 1) lastPage = want;
                fetching = false;
                if (!any && items.isEmpty()) failed = false;
                view.invalidate();
                for (Item it : got) thumb(it);
            });
        });
    }

    private void thumb(Item it) {
        if (it.bmp != null || it.loading) return;
        it.loading = true;
        net.execute(() -> {
            try (InputStream in = open(it.thumb)) {
                Bitmap b = BitmapFactory.decodeStream(in);
                ui.post(() -> {
                    it.bmp = b;
                    it.loading = false;
                    view.invalidate();
                });
            } catch (Exception e) {
                ui.post(() -> it.loading = false);
            }
        });
    }

    private static InputStream open(String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(8000);
        h.setReadTimeout(15_000);
        h.setRequestProperty("User-Agent", UA);
        if (h.getResponseCode() != 200) throw new IOException("HTTP " + h.getResponseCode());
        return h.getInputStream();
    }

    private static String text(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = i.read(buf)) > 0 && out.size() < 2_000_000; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    /** Downloads the picture, makes it the TV's size and puts it behind the home screen. */
    private void choose(Item it) {
        if (busy) return;
        busy = true;
        progress = 0;
        status = "Descargando…";
        view.invalidate();
        final Context app = getApplicationContext();
        net.execute(() -> {
            try {
                File tmp = new File(app.getCacheDir(), "wh.tmp");
                if (!it.full.startsWith(FULL) && !it.full.startsWith("https://upload.wikimedia.org/")) throw new IOException("host");
                HttpURLConnection h = (HttpURLConnection) new URL(it.full).openConnection();
                h.setConnectTimeout(8000);
                h.setReadTimeout(20_000);
                h.setRequestProperty("User-Agent", UA);
                if (h.getResponseCode() != 200) throw new IOException("HTTP " + h.getResponseCode());
                long total = h.getContentLengthLong();
                if (total > 20_000_000L) throw new IOException("too big");
                try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[32768];
                    long got = 0;
                    for (int n; (n = in.read(buf)) > 0; ) {
                        got += n;
                        if (got > 20_000_000L) throw new IOException("too big");
                        out.write(buf, 0, n);
                        if (total > 0) {
                            final float f = got / (float) total;
                            ui.post(() -> {
                                progress = f;
                                view.invalidate();
                            });
                        }
                    }
                }
                ui.post(() -> {
                    status = "Preparando para tu TV…";
                    view.invalidate();
                });
                BitmapFactory.Options bo = new BitmapFactory.Options();
                bo.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(tmp.getPath(), bo);
                int sample = 1;
                while (bo.outWidth / (sample * 2) >= 1900 && bo.outHeight / (sample * 2) >= 1068) sample *= 2;
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                Bitmap b = BitmapFactory.decodeFile(tmp.getPath(), o);
                tmp.delete();
                if (b == null) throw new IOException("unreadable");
                String id = (it.commons ? "w-cm-" : "w-wh-") + it.id + ".jpg";
                try (OutputStream out = new FileOutputStream(new File(WallpaperActivity.dir(app), "o-" + id))) {
                    b.compress(Bitmap.CompressFormat.JPEG, 95, out);
                }
                float ratio = b.getWidth() / (float) b.getHeight();   // wide ones fill the screen; tall ones stay whole over a soft copy
                if (!WallpaperActivity.render(app, id, ratio >= 1.45f ? 0 : 1, ratio >= 1.45f ? WallpaperActivity.smartOffset(b) : 0.5f)) throw new IOException("render");
                WallpaperActivity.applyFile(app, id);
                ui.post(() -> {
                    busy = false;
                    status = "Listo: ya es tu fondo";
                    view.invalidate();
                    ui.postDelayed(() -> {
                        status = "";
                        view.invalidate();
                    }, 2200);
                });
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("catalog: " + e.getMessage());
                ui.post(() -> {
                    busy = false;
                    status = "No se pudo descargar. Revisa el Wi-Fi.";
                    view.invalidate();
                });
            }
        });
    }

    // MARK: - The screen

    private static final String[] KEYS = {"a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z",
        "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", " ", "<", "ok"};

    private final class BrowseView extends View {
        private static final int COLS = 4, ROWS = 3;
        private final float k;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint title = Ui.text(Ui.displaySemibold, 1, 0xFFFFFFFF), chipP = Ui.text(Ui.semibold, 1, 0xFFFFFFFF),
                small = Ui.text(Ui.medium, 1, 0xB3FFFFFF), big = Ui.text(Ui.semibold, 1, 0xFFFFFFFF);
        private final RectF r = new RectF();
        private final Matrix matrix = new Matrix();

        BrowseView(Context c) {
            super(c);
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            k = dm.widthPixels / 1920f;
            setFocusable(true);
            title.setTextSize(54 * k);
            chipP.setTextSize(26 * k);
            small.setTextSize(24 * k);
            big.setTextSize(34 * k);
        }

        boolean onKey(KeyEvent e) {
            int code = e.getKeyCode();
            boolean dpad = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP
                    || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
            if (code == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) {
                    if (keyboard) {
                        keyboard = false;
                        invalidate();
                    } else if (!busy) {
                        finish();
                    }
                }
                return true;
            }
            if (!dpad || e.getAction() != KeyEvent.ACTION_DOWN) return dpad;
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
            if (busy) return true;
            if (keyboard) {
                keys(code, ok, e.getRepeatCount());
            } else if (onChips) {
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) chip = Math.max(0, chip - 1);
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) chip = Math.min(CATEGORIES.length - 1, chip + 1);
                else if (code == KeyEvent.KEYCODE_DPAD_DOWN && !items.isEmpty()) onChips = false;
                else if (ok && e.getRepeatCount() == 0) {
                    if (CATEGORIES[chip][1] == null) {
                        keyboard = true;
                        key = 0;
                    } else {
                        pick(chip);
                    }
                }
            } else {
                int row = focus / COLS, col = focus % COLS;
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && col > 0) focus--;
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT && col < COLS - 1 && focus + 1 < items.size()) focus++;
                else if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    if (row == 0) onChips = true; else focus -= COLS;
                } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (focus + COLS < items.size()) focus += COLS;
                    else if (focus + COLS >= items.size() && page < lastPage) more();
                } else if (ok && e.getRepeatCount() == 0 && focus < items.size()) {
                    choose(items.get(focus));
                }
                int fr = focus / COLS;
                if (fr < scrollRow) scrollRow = fr;
                if (fr >= scrollRow + ROWS) scrollRow = fr - ROWS + 1;
                if (items.size() - focus <= COLS * 2 && page < lastPage) more();
            }
            invalidate();
            return true;
        }

        private void keys(int code, boolean ok, int repeat) {
            int cols = 10;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) key = Math.max(0, key - 1);
            else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) key = Math.min(KEYS.length - 1, key + 1);
            else if (code == KeyEvent.KEYCODE_DPAD_UP) key = Math.max(0, key - cols);
            else if (code == KeyEvent.KEYCODE_DPAD_DOWN) key = Math.min(KEYS.length - 1, key + cols);
            else if (ok && repeat == 0) {
                String t = KEYS[key];
                if (t.equals("<")) {
                    if (typed.length() > 0) typed.setLength(typed.length() - 1);
                } else if (t.equals("ok")) {
                    if (typed.toString().trim().length() > 0) {
                        keyboard = false;
                        query = typed.toString().trim();
                        sorting = "relevance";
                        onChips = false;
                        reset();
                    }
                } else if (typed.length() < 30) {
                    typed.append(t);
                }
            }
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            fill.setColor(0xFF000000);
            fill.setShader(new LinearGradient(0, 0, 0, h, 0xFF14050A, 0xFF050102, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);
            c.drawText("Catálogo de fondos", 98 * k, 100 * k, title);
            small.setTextAlign(Paint.Align.RIGHT);
            c.drawText("Fondos de wallhaven.cc y Wikimedia Commons", w - 98 * k, 96 * k, small);
            small.setTextAlign(Paint.Align.LEFT);
            // the categories, scrolled so the picked one shows
            float x = 98 * k, cy = 150 * k;
            int first = Math.max(0, Math.min(chip - 3, CATEGORIES.length - 8));
            for (int i = first; i < CATEGORIES.length; i++) {
                String label = (i == CATEGORIES.length - 1 && typed.length() > 0 && sorting.equals("relevance")) ? "“" + typed + "”" : CATEGORIES[i][0];
                float cw = chipP.measureText(label) + 56 * k;
                if (x + cw > w - 60 * k) break;
                r.set(x, cy, x + cw, cy + 62 * k);
                boolean sel = i == chip, on = sel && onChips && !keyboard;
                fill.setColor(on ? 0xFFFFFFFF : sel ? 0xFFC4101F : 0x33FFFFFF);
                c.drawRoundRect(r, 31 * k, 31 * k, fill);
                chipP.setColor(on ? 0xFF1A0408 : 0xFFFFFFFF);
                c.drawText(label, x + 28 * k, cy + 42 * k, chipP);
                x += cw + 14 * k;
            }
            chipP.setColor(0xFFFFFFFF);
            // the pictures
            float tw = 410 * k, th = tw * 9 / 16, gap = 28 * k, left = (w - (COLS * tw + (COLS - 1) * gap)) / 2f, top = 250 * k;
            for (int row = 0; row < ROWS; row++) {
                for (int col = 0; col < COLS; col++) {
                    int i = (scrollRow + row) * COLS + col;
                    if (i >= items.size()) continue;
                    Item it = items.get(i);
                    float tx = left + col * (tw + gap), ty = top + row * (th + gap);
                    r.set(tx, ty, tx + tw, ty + th);
                    if (it.bmp != null) {
                        BitmapShader sh = new BitmapShader(it.bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                        float sc = Math.max(tw / it.bmp.getWidth(), th / it.bmp.getHeight());
                        matrix.setScale(sc, sc);
                        matrix.postTranslate(tx + (tw - it.bmp.getWidth() * sc) / 2, ty + (th - it.bmp.getHeight() * sc) / 2);
                        sh.setLocalMatrix(matrix);
                        fill.setShader(sh);
                    } else {
                        fill.setColor(0xFF200A10);
                    }
                    c.drawRoundRect(r, 20 * k, 20 * k, fill);
                    fill.setShader(null);
                    if (!onChips && i == focus && !keyboard) {
                        fill.setStyle(Paint.Style.STROKE);
                        fill.setStrokeWidth(6 * k);
                        fill.setColor(0xFFFFFFFF);
                        c.drawRoundRect(tx - 5 * k, ty - 5 * k, tx + tw + 5 * k, ty + th + 5 * k, 24 * k, 24 * k, fill);
                        fill.setStyle(Paint.Style.FILL);
                        fill.setColor(0xB0000000);
                        r.set(tx + 12 * k, ty + th - 46 * k, tx + 12 * k + small.measureText(it.res) + 28 * k, ty + th - 12 * k);
                        c.drawRoundRect(r, 17 * k, 17 * k, fill);
                        c.drawText(it.res, tx + 26 * k, ty + th - 21 * k, small);
                    }
                }
            }
            if (items.isEmpty()) {
                big.setTextAlign(Paint.Align.CENTER);
                c.drawText(failed ? "No hay conexión con el catálogo. Revisa el Wi-Fi." : fetching ? "Buscando fondos…" : "No encontré fondos con eso.", w / 2f, 520 * k, big);
                big.setTextAlign(Paint.Align.LEFT);
            }
            small.setTextAlign(Paint.Align.CENTER);
            c.drawText(onChips ? "Izquierda y derecha cambian de categoría. Abajo ves los fondos. «Buscar…» te deja escribir (en inglés)."
                    : "OK descarga el fondo y lo pone en tu TV al instante.", w / 2f, h - 40 * k, small);
            small.setTextAlign(Paint.Align.LEFT);
            if (keyboard) drawKeyboard(c, w, h);
            if (!status.isEmpty()) drawStatus(c, w, h);
        }

        private void drawStatus(Canvas c, int w, int h) {
            fill.setColor(0xE6000000);
            c.drawRect(0, 0, w, h, fill);
            big.setTextAlign(Paint.Align.CENTER);
            c.drawText(status, w / 2f, h / 2f - 10 * k, big);
            big.setTextAlign(Paint.Align.LEFT);
            if (status.startsWith("Descargando")) {
                fill.setColor(0x33FFFFFF);
                r.set(w / 2f - 300 * k, h / 2f + 24 * k, w / 2f + 300 * k, h / 2f + 38 * k);
                c.drawRoundRect(r, 7 * k, 7 * k, fill);
                fill.setColor(0xFFFFFFFF);
                r.right = r.left + 600 * k * progress;
                c.drawRoundRect(r, 7 * k, 7 * k, fill);
            }
        }

        private void drawKeyboard(Canvas c, int w, int h) {
            fill.setColor(0xF0000000);
            c.drawRect(0, 0, w, h, fill);
            big.setTextAlign(Paint.Align.CENTER);
            c.drawText("¿Qué fondo buscas?", w / 2f, 150 * k, big);
            r.set(w / 2f - 500 * k, 190 * k, w / 2f + 500 * k, 270 * k);
            fill.setColor(0x33FFFFFF);
            c.drawRoundRect(r, 24 * k, 24 * k, fill);
            title.setTextSize(44 * k);
            c.drawText(typed.length() == 0 ? "escribe en inglés: dragon, skull, city…" : typed.toString(), w / 2f, 247 * k, typed.length() == 0 ? small : title);
            title.setTextSize(54 * k);
            float kw = 100 * k, kh = 84 * k, g = 12 * k, x0 = (w - (10 * kw + 9 * g)) / 2f, y0 = 330 * k;
            chipP.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < KEYS.length; i++) {
                float kx = x0 + (i % 10) * (kw + g), ky = y0 + (i / 10) * (kh + g);
                r.set(kx, ky, kx + kw, ky + kh);
                boolean on = i == key;
                String t = KEYS[i];
                fill.setColor(on ? 0xFFFFFFFF : t.equals("ok") ? 0xFFC4101F : 0x33FFFFFF);
                c.drawRoundRect(r, 20 * k, 20 * k, fill);
                chipP.setColor(on ? 0xFF1A0408 : 0xFFFFFFFF);
                c.drawText(t.equals(" ") ? "␣" : t.equals("<") ? "⌫" : t.equals("ok") ? "Buscar" : t.toUpperCase(), kx + kw / 2, ky + 54 * k, chipP);
            }
            chipP.setColor(0xFFFFFFFF);
            chipP.setTextAlign(Paint.Align.LEFT);
            big.setTextAlign(Paint.Align.LEFT);
        }
    }
}
