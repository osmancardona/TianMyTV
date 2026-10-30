package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.View;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wallpaper: the one in use, the ones that come with the app, the ones you add (from your phone: the TV shows a code, the
 * phone sends the pictures over the Wi-Fi). OK picks one, hold OK removes one you added.
 */
public class WallpaperActivity extends Activity {
    private static final String[] BUILT_IN = {"Alastor", "Crimson", "Midnight", "Ember"};

    private WallView view;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private UploadPage page;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        overridePendingTransition(0, 0);
        view = new WallView(this);
        setContentView(view);
    }

    @Override protected void onStop() {
        stopPage();
        super.onStop();
        if (!isFinishing()) finish();
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (view.onKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    private void stopPage() {
        if (page != null) {
            page.close();
            page = null;
        }
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "wallpapers");
        d.mkdirs();
        return d;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("tian", Context.MODE_PRIVATE);
    }

    /** The picture behind a wallpaper: one of the built-in ones (made here) or a file you added. */
    private static Bitmap load(Context c, String id, int w, int h) {
        try {
            if (id.startsWith("builtin:")) {
                String name = id.substring(8);
                if (name.equals("Alastor")) {
                    try (InputStream in = c.getAssets().open("bg-alastor.jpg")) {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inSampleSize = Math.max(1, 1920 / Math.max(1, w));
                        return BitmapFactory.decodeStream(in, null, o);
                    }
                }
                int[] colors = name.equals("Crimson") ? new int[] {0xFF5A0610, 0xFF12020A}
                        : name.equals("Midnight") ? new int[] {0xFF1B2250, 0xFF05060F} : new int[] {0xFFB5320A, 0xFF1C0504};
                Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Paint p = new Paint();
                p.setShader(new LinearGradient(0, 0, w * 0.6f, h, colors[0], colors[1], Shader.TileMode.CLAMP));
                new Canvas(b).drawRect(0, 0, w, h, p);
                return b;
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

    /** A soft blur: three passes of a box (radius r) in each direction. */
    private static Bitmap blurred(Bitmap src, int r) {
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

    /**
     * Draws wallpaper `id` (w-....jpg) for the screen from its original (o-w-....jpg), 2112 by 1188. mode 0 fills the
     * screen with the middle of the picture: `offset` (0 to 1) says how far down, for a tall picture; mode 1 keeps it whole
     * in the middle, its sides melting into a soft copy of itself.
     */
    static boolean render(Context c, String id, int mode, float offset) {
        Bitmap b = BitmapFactory.decodeFile(new File(dir(c), "o-" + id).getPath());
        if (b == null) return false;
        int tw = Photos.W, th = Photos.H;
        Bitmap screen = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        Canvas sc = new Canvas(screen);
        Paint sp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        RectF dst = new RectF(0, 0, tw, th);
        if (mode == 0) {
            float scale = Math.max(tw / (float) b.getWidth(), th / (float) b.getHeight());
            int sw = Math.min(b.getWidth(), Math.round(tw / scale)), sh = Math.min(b.getHeight(), Math.round(th / scale));
            int sx = (b.getWidth() - sw) / 2, sy = Math.round((b.getHeight() - sh) * Math.max(0, Math.min(1, offset)));
            Bitmap part = Bitmap.createBitmap(b, sx, sy, sw, sh);
            sc.drawBitmap(scaleTo(part, tw, th), null, dst, sp);
        } else {
            float ratio = b.getWidth() / (float) b.getHeight();
            // the soft copy: the picture cut down to a few dozen pixels, its middle in 16:9, then doubled up smoothly
            Bitmap tiny = Bitmap.createScaledBitmap(b, 48, Math.max(1, Math.round(48 / ratio)), true);
            int pw = Math.min(tiny.getWidth(), Math.max(1, Math.round(tiny.getHeight() * 16f / 9)));
            int ph = Math.min(tiny.getHeight(), Math.max(1, Math.round(pw * 9f / 16)));
            Bitmap middle = Bitmap.createBitmap(tiny, (tiny.getWidth() - pw) / 2, (tiny.getHeight() - ph) / 2, pw, ph);
            Bitmap haze = blurred(scaleTo(middle, 528, 297), 14);
            sc.drawBitmap(Bitmap.createScaledBitmap(haze, tw, th, true), null, dst, sp);
            sp.setColor(0x55000000);
            sc.drawRect(dst, sp);
            sp.setColor(0xFFFFFFFF);
            float fit = Math.min(tw / (float) b.getWidth(), th / (float) b.getHeight());
            int fw = Math.round(b.getWidth() * fit), fh = Math.round(b.getHeight() * fit);
            Bitmap whole = scaleTo(b, fw, fh);
            float fx = (tw - fw) / 2f, fy = (th - fh) / 2f, soft = Math.max(1, fw * 0.12f);
            int layer = sc.saveLayer(fx, fy, fx + fw, fy + fh, null);
            sc.drawBitmap(whole, null, new RectF(fx, fy, fx + fw, fy + fh), sp);
            Paint mask = new Paint();
            mask.setShader(new android.graphics.LinearGradient(fx, 0, fx + fw, 0, new int[] {0x00000000, 0xFF000000, 0xFF000000, 0x00000000},
                    new float[] {0f, soft / fw, 1f - soft / fw, 1f}, Shader.TileMode.CLAMP));
            mask.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
            sc.drawRect(fx, fy, fx + fw, fy + fh, mask);
            sc.restoreToCount(layer);
        }
        try (FileOutputStream f = new FileOutputStream(new File(dir(c), id))) {
            screen.compress(Bitmap.CompressFormat.JPEG, 97, f);
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    /** Puts it behind the home screen: the home's own photo, one only. */
    private void apply(String id) {
        final Context app = getApplicationContext();
        worker.execute(() -> {
            Bitmap b = load(app, id, 2112, 1188);
            if (b == null) return;
            Photos.removeAll(app);
            File in = Photos.privateInbox(app);
            in.mkdirs();
            File out = new File(in, "wallpaper.jpg");
            try (FileOutputStream f = new FileOutputStream(out)) {
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
                view.invalidate();
            });
            handler.post(() -> view.setCurrent(id));
        });
    }

    // MARK: - The page the phone opens

    private static final class UploadPage {
        interface Listener { void added(String name); void closed(); }

        final String url;
        private final ServerSocket server;
        private final String path;
        private final Context app;
        private final Listener listener;
        private volatile boolean closed;
        private int uploads;

        UploadPage(Context c, InetAddress lan, Listener listener) throws IOException {
            app = c.getApplicationContext();
            this.listener = listener;
            byte[] random = new byte[12];
            new SecureRandom().nextBytes(random);
            path = "/" + Base64.encodeToString(random, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            server = new ServerSocket(0, 4, lan);
            url = "http://" + lan.getHostAddress() + ":" + server.getLocalPort() + path;
            Thread t = new Thread(this::serve, "WallpaperPage");
            t.setDaemon(true);
            t.start();
        }

        void close() {
            closed = true;
            try {
                server.close();
            } catch (IOException ignored) {
            }
        }

        private void serve() {
            long until = System.nanoTime() + 10 * 60_000_000_000L;
            try {
                while (!closed && System.nanoTime() < until) {
                    server.setSoTimeout(5000);
                    Socket s;
                    try {
                        s = server.accept();
                    } catch (SocketTimeoutException e) {
                        continue;
                    }
                    Thread t = new Thread(() -> answer(s), "WallpaperPage answer");
                    t.setDaemon(true);
                    t.start();
                }
            } catch (IOException e) {
                // closed
            } finally {
                close();
                listener.closed();
            }
        }

        private void answer(Socket socket) {
            try (Socket s = socket) {
                s.setSoTimeout(15_000);
                handle(s.getInputStream(), s.getOutputStream());
            } catch (IOException | RuntimeException e) {
                // a phone that went away
            }
        }

        private void handle(InputStream in, OutputStream out) throws IOException {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int state = 0;
            while (state < 4 && head.size() < 8192) {
                int b = in.read();
                if (b < 0) return;
                head.write(b);
                state = (b == '\r' && (state == 0 || state == 2)) ? state + 1 : (b == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            }
            if (state < 4) return;
            String h = head.toString("ISO-8859-1");
            String[] line = h.substring(0, Math.max(0, h.indexOf("\r\n"))).split(" ");
            if (line.length < 2) return;
            String method = line[0], target = line[1];
            String query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                query = target.substring(q + 1);
                target = target.substring(0, q);
            }
            if (method.equals("GET") && target.equals(path)) {
                respond(out, "200 OK", "text/html; charset=utf-8", PAGE.replace("@UP@", path + "/up").getBytes(StandardCharsets.UTF_8));
            } else if (method.equals("POST") && target.equals(path + "/up")) {
                long length = -1;
                for (String l : h.split("\r\n")) {
                    if (l.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) length = Long.parseLong(l.substring(15).trim());
                }
                if (length <= 0 || length > 30_000_000L || uploads >= 40) {
                    respond(out, "413 Payload Too Large", "text/plain", new byte[0]);
                    return;
                }
                File tmp = new File(app.getCacheDir(), "upload.tmp");
                try (FileOutputStream f = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    long left = length;
                    while (left > 0) {
                        int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                        if (n < 0) throw new IOException("cut short");
                        f.write(buf, 0, n);
                        left -= n;
                    }
                }
                String name = save(tmp);
                tmp.delete();
                if (name == null) {
                    respond(out, "415 Unsupported Media Type", "text/plain", "No es una imagen".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                uploads++;
                respond(out, "200 OK", "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
                listener.added(name);
            } else {
                respond(out, "404 Not Found", "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            }
        }

        /**
         * The picture as the TV shows it: turned the way it was taken (the phone's own turn is read from the file),
         * kept at full quality and sized to the screen (2112 by 1188), whole: a picture that is not 16:9 sits in the middle
         * over a soft dark copy of itself. Saved as JPEG; its file name, or null when it isn't a picture.
         */
        private String save(File tmp) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(tmp.getPath(), bounds);
            if (bounds.outWidth < 400 || bounds.outHeight < 300) return null;
            // decoded no smaller than the screen needs: a phone's 12 megapixels are cut down once, well, not twice
            BitmapFactory.Options o = new BitmapFactory.Options();
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= Photos.W && bounds.outHeight / (sample * 2) >= Photos.H) sample *= 2;
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeFile(tmp.getPath(), o);
            if (b == null) return null;
            b = turned(tmp, b);
            String name = "w-" + System.currentTimeMillis() + ".jpg";
            try (FileOutputStream f = new FileOutputStream(new File(dir(app), "o-" + name))) {
                b.compress(Bitmap.CompressFormat.JPEG, 95, f);   // the original, turned right: the editor draws from it
            } catch (IOException ex) {
                return null;
            }
            float ratio = b.getWidth() / (float) b.getHeight();
            if (!render(app, name, 0, ratio < 1f ? 0.3f : 0.5f)) return null;
            return name;
        }

        /** Cut down in halves while it stays at least the target, then to it: sharper than one big step. */
        private static Bitmap shrunk(Bitmap src, int tw, int th) {
            Bitmap cur = src;
            while (cur.getWidth() / 2 >= tw && cur.getHeight() / 2 >= th) {
                cur = Bitmap.createScaledBitmap(cur, cur.getWidth() / 2, cur.getHeight() / 2, true);
            }
            if (cur.getWidth() != tw || cur.getHeight() != th) cur = Bitmap.createScaledBitmap(cur, tw, th, true);
            return cur;
        }

        /** The turn a phone wrote in the file (its camera held upright, sideways, or the front camera's mirror). */
        private static Bitmap turned(File f, Bitmap b) {
            try {
                int o = new android.media.ExifInterface(f.getPath()).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
                android.graphics.Matrix m = new android.graphics.Matrix();
                switch (o) {
                    case 2: m.setScale(-1, 1); break;
                    case 3: m.setRotate(180); break;
                    case 4: m.setScale(1, -1); break;
                    case 5: m.setRotate(90); m.postScale(-1, 1); break;
                    case 6: m.setRotate(90); break;
                    case 7: m.setRotate(-90); m.postScale(-1, 1); break;
                    case 8: m.setRotate(-90); break;
                    default: return b;
                }
                return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            } catch (IOException | RuntimeException e) {
                return b;
            }
        }

        private static void respond(OutputStream out, String status, String type, byte[] body) throws IOException {
            String head = "HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                    + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
        }

        private static final String PAGE = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>Tian Demon TV</title><style>body{font-family:sans-serif;background:#12040a;color:#fff;text-align:center;padding:40px 20px}"
                + "h1{font-size:26px}label{display:inline-block;background:#e3122b;color:#fff;padding:18px 34px;border-radius:40px;font-size:20px;margin:24px 0}"
                + "input{display:none}p{opacity:.8}#log p{margin:6px 0}</style>"
                + "<h1>Fondos para tu TV</h1><p>Elige una o varias fotos de tu celular.</p>"
                + "<label for=f>Elegir fotos</label><input id=f type=file accept='image/*' multiple><div id=log></div>"
                + "<script>var f=document.getElementById('f'),log=document.getElementById('log');"
                + "f.onchange=async function(){for(var i=0;i<f.files.length;i++){var x=f.files[i],p=document.createElement('p');"
                + "p.textContent='Enviando '+x.name+'...';log.appendChild(p);try{var r=await fetch('@UP@',{method:'POST',body:x});"
                + "p.textContent=(r.ok?'Listo: ':'No se pudo: ')+x.name}catch(e){p.textContent='Error: '+x.name}}f.value=''}</script>";
    }

    // MARK: - The screen

    private final class WallView extends View {
        private final float k;
        private final List<String> ids = new ArrayList<>();   // "+" is index 0
        private final List<Bitmap> thumbs = new ArrayList<>();
        private int focus = 1, scroll;
        private String current;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint head = Ui.text(Ui.displaySemibold, 1, 0xFFFFFFFF), sub = Ui.text(Ui.medium, 1, 0xB3FFFFFF),
                label = Ui.text(Ui.semibold, 1, 0xFFFFFFFF);
        private final RectF r = new RectF();
        private final Matrix matrix = new Matrix();
        private Bitmap qr;
        private final List<String> fresh = new ArrayList<>();   // pictures sent while the code was open
        private final java.util.Map<String, Bitmap> previews = new java.util.HashMap<>();
        private int freshIndex, freshBtn = 3, freshRow;
        private final java.util.Map<String, float[]> adj = new java.util.HashMap<>();   // per picture: mode, offset
        private long renderUntil;
        private String qrUrl, hint = "";
        private long holdAt;
        private Bitmap backdrop;
        private static final int COLS = 4, ROWS = 2;

        WallView(Context c) {
            super(c);
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            k = dm.widthPixels / 1920f;
            setFocusable(true);
            head.setTextSize(58 * k);
            sub.setTextSize(28 * k);
            label.setTextSize(26 * k);
            current = prefs(c).getString("wallpaper", "builtin:Alastor");
            reload();
        }

        void setCurrent(String id) {
            current = id;
            hint = "Fondo cambiado";
            loadBackdrop();
            invalidate();
        }

        private void reload() {
            ids.clear();
            thumbs.clear();
            ids.add("+");
            thumbs.add(null);
            for (String n : BUILT_IN) {
                ids.add("builtin:" + n);
                thumbs.add(null);
            }
            File[] mine = dir(getContext()).listFiles();
            if (mine != null) {
                Arrays.sort(mine);
                List<File> list = new ArrayList<>(Arrays.asList(mine));
                Collections.reverse(list);   // the newest first
                for (File f : list) {
                    if (f.getName().startsWith("w-") && f.getName().endsWith(".jpg")) {
                        ids.add(f.getName());
                        thumbs.add(null);
                    }
                }
            }
            if (focus >= ids.size()) focus = ids.size() - 1;
            loadBackdrop();
            for (int i = 1; i < ids.size(); i++) {
                final int index = i;
                final String id = ids.get(i);
                worker.execute(() -> {
                    Bitmap t = load(getContext(), id, 480, 270);
                    handler.post(() -> {
                        if (index < thumbs.size() && ids.get(index).equals(id)) {
                            thumbs.set(index, t);
                            invalidate();
                        }
                    });
                });
            }
            invalidate();
        }

        private void loadBackdrop() {
            final String id = current;
            worker.execute(() -> {
                Bitmap b = load(getContext(), id, 240, 135);
                handler.post(() -> {
                    backdrop = b;
                    invalidate();
                });
            });
        }

        boolean onKey(KeyEvent e) {
            int code = e.getKeyCode();
            boolean dpad = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP
                    || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
            if (page != null && code == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) closeCode();
                return true;
            }
            if (!dpad) return false;
            if (page != null) {   // the code, or the pictures that came
                if (e.getAction() != KeyEvent.ACTION_DOWN || fresh.isEmpty()) return true;
                boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
                if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    freshRow = 0;
                } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    freshRow = 1;
                } else if (freshRow == 0 && code == KeyEvent.KEYCODE_DPAD_LEFT && fresh.size() > 1) {
                    freshIndex = (freshIndex - 1 + fresh.size()) % fresh.size();
                    apply(fresh.get(freshIndex));
                } else if (freshRow == 0 && code == KeyEvent.KEYCODE_DPAD_RIGHT && fresh.size() > 1) {
                    freshIndex = (freshIndex + 1) % fresh.size();
                    apply(fresh.get(freshIndex));
                } else if (freshRow == 1 && code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    freshBtn = Math.max(0, freshBtn - 1);
                } else if (freshRow == 1 && code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    freshBtn = Math.min(3, freshBtn + 1);
                } else if (ok && freshRow == 1 && freshBtn < 3) {
                    float[] a2 = adj.get(fresh.get(freshIndex));
                    if (a2 != null) {
                        if (freshBtn == 0) a2[0] = a2[0] == 0 ? 1 : 0;
                        else if (a2[0] == 0) a2[1] = Math.max(0, Math.min(1, a2[1] + (freshBtn == 1 ? -0.12f : 0.12f)));
                        redraw(fresh.get(freshIndex), a2);
                    }
                } else if (ok && e.getRepeatCount() == 0) {
                    String id = fresh.get(freshIndex);
                    closeCode();
                    apply(id);
                    return true;
                }
                invalidate();
                return true;
            }
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                if (down && e.getRepeatCount() == 0) holdAt = System.currentTimeMillis();
                if (down && e.getRepeatCount() > 0 && e.isLongPress() && holdAt != 0) {
                    holdAt = 0;
                    remove();
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
            if (next >= 0 && next < ids.size()) {
                focus = next;
                int row = focus / COLS;
                if (row < scroll) scroll = row;
                if (row >= scroll + ROWS) scroll = row - ROWS + 1;
                hint = "";
            }
            invalidate();
            return true;
        }

        private void choose() {
            String id = ids.get(focus);
            if (id.equals("+")) {
                startPage();
                return;
            }
            hint = "Aplicando...";
            invalidate();
            apply(id);
        }

        private void remove() {
            String id = ids.get(focus);
            if (id.equals("+") || id.startsWith("builtin:")) {
                hint = "Ese fondo viene con la app y no se borra";
                invalidate();
                return;
            }
            new File(dir(getContext()), id).delete();
            new File(dir(getContext()), "o-" + id).delete();
            hint = "Fondo borrado";
            reload();
        }

        /** Draws the picture again with the new choice (whole or filling, higher or lower) and shows it. */
        private void redraw(String id, float[] a2) {
            renderUntil = android.os.SystemClock.uptimeMillis() + 500;
            final Context app = getContext().getApplicationContext();
            final int mode = Math.round(a2[0]);
            final float offset = a2[1];
            worker.execute(() -> {
                render(app, id, mode, offset);
                Bitmap p = load(app, id, 1000, 563);
                handler.post(() -> {
                    previews.put(id, p);
                    invalidate();
                });
            });
        }

        /** Closes the window of the code (and of the pictures that came); the list of wallpapers stays. */
        private void closeCode() {
            stopPage();
            fresh.clear();
            previews.clear();
            reload();
        }

        private void startPage() {
            InetAddress lan = KeyPage.lanAddress(getContext());
            if (lan == null) {
                hint = "No hay Wi-Fi para conectar el celular";
                invalidate();
                return;
            }
            try {
                page = new UploadPage(getContext(), lan, new UploadPage.Listener() {
                    @Override public void added(String name) {
                        handler.post(() -> {
                            fresh.add(name);
                            freshIndex = fresh.size() - 1;
                            freshBtn = 3;
                            freshRow = 0;
                            BitmapFactory.Options bo = new BitmapFactory.Options();
                            bo.inJustDecodeBounds = true;
                            BitmapFactory.decodeFile(new File(dir(getContext()), "o-" + name).getPath(), bo);
                            adj.put(name, new float[] {0, bo.outWidth < bo.outHeight ? 0.3f : 0.5f});
                            renderUntil = android.os.SystemClock.uptimeMillis() + 700;
                            apply(name);
                            hint = "Foto agregada";
                            reload();
                            worker.execute(() -> {
                                Bitmap p = load(getContext(), name, 1000, 563);
                                handler.post(() -> {
                                    previews.put(name, p);
                                    invalidate();
                                });
                            });
                        });
                    }

                    @Override public void closed() {
                        handler.post(() -> {
                            page = null;
                            invalidate();
                        });
                    }
                });
                qrUrl = page.url;
                qr = Qrs.bitmap(Qr.encode(page.url));
            } catch (IOException | RuntimeException e) {
                hint = "No se pudo abrir: " + e.getMessage();
                page = null;
            }
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (backdrop != null) {
                r.set(0, 0, w, h);
                c.drawBitmap(backdrop, null, r, fill);
            } else {
                c.drawColor(0xFF12040A);
            }
            fill.setColor(0xCC08020A);
            c.drawRect(0, 0, w, h, fill);
            c.drawText("Fondo de pantalla", 110 * k, 130 * k, head);
            sub.setTextAlign(Paint.Align.LEFT);
            c.drawText(hint.isEmpty() ? "OK elige el fondo. Mantén OK para borrar uno tuyo." : hint, 110 * k, 180 * k, sub);

            float cw = 380 * k, ch = 214 * k, gap = 36 * k, left = 110 * k, top = 250 * k;
            for (int row = 0; row < ROWS; row++) {
                for (int col = 0; col < COLS; col++) {
                    int i = (scroll + row) * COLS + col;
                    if (i >= ids.size()) continue;
                    float x = left + col * (cw + gap), y = top + row * (ch + gap + 36 * k);
                    r.set(x, y, x + cw, y + ch);
                    boolean on = i == focus;
                    String id = ids.get(i);
                    if (id.equals("+")) {
                        fill.setColor(0x33FFFFFF);
                        c.drawRoundRect(r, 24 * k, 24 * k, fill);
                        fill.setStyle(Paint.Style.STROKE);
                        fill.setStrokeWidth(2.5f * k);
                        fill.setColor(0x88FFFFFF);
                        c.drawRoundRect(r, 24 * k, 24 * k, fill);
                        fill.setColor(0xFFFFFFFF);
                        fill.setStrokeWidth(6 * k);
                        fill.setStrokeCap(Paint.Cap.ROUND);
                        c.drawLine(r.centerX() - 24 * k, r.centerY() - 10 * k, r.centerX() + 24 * k, r.centerY() - 10 * k, fill);
                        c.drawLine(r.centerX(), r.centerY() - 34 * k, r.centerX(), r.centerY() + 14 * k, fill);
                        fill.setStyle(Paint.Style.FILL);
                        label.setTextAlign(Paint.Align.CENTER);
                        c.drawText("Agregar desde el celular", r.centerX(), r.centerY() + 56 * k, label);
                        label.setTextAlign(Paint.Align.LEFT);
                    } else {
                        Bitmap t = thumbs.get(i);
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
                        c.drawRoundRect(r, 24 * k, 24 * k, fill);
                        fill.setShader(null);
                        if (id.equals(current)) {
                            fill.setColor(0xFF1DB954);
                            c.drawRoundRect(x + cw - 120 * k, y + 14 * k, x + cw - 14 * k, y + 54 * k, 20 * k, 20 * k, fill);
                            label.setTextAlign(Paint.Align.CENTER);
                            c.drawText("En uso", x + cw - 67 * k, y + 42 * k, label);
                            label.setTextAlign(Paint.Align.LEFT);
                        }
                        String name = id.startsWith("builtin:") ? id.substring(8) : "Mi foto";
                        c.drawText(name, x + 6 * k, y + ch + 34 * k, label);
                    }
                    if (on) {
                        fill.setStyle(Paint.Style.STROKE);
                        fill.setStrokeWidth(6 * k);
                        fill.setColor(0xFFFFFFFF);
                        c.drawRoundRect(r.left - 6 * k, r.top - 6 * k, r.right + 6 * k, r.bottom + 6 * k, 28 * k, 28 * k, fill);
                        fill.setStyle(Paint.Style.FILL);
                    }
                }
            }
            if (page != null) drawCode(c, w, h);
        }

        private void drawFresh(Canvas c, int w, int h) {
            fill.setColor(0xF2000000);
            c.drawRect(0, 0, w, h, fill);
            head.setTextAlign(Paint.Align.CENTER);
            sub.setTextAlign(Paint.Align.CENTER);
            head.setTextSize(50 * k);
            c.drawText("Ya es tu fondo", w / 2f, 100 * k, head);
            head.setTextSize(58 * k);
            float iw = 1000 * k, ih = iw * 9 / 16, x = (w - iw) / 2f, y = 140 * k;
            r.set(x, y, x + iw, y + ih);
            fill.setColor(0xFF1A0A10);
            c.drawRoundRect(r, 28 * k, 28 * k, fill);
            String id = fresh.get(freshIndex);
            Bitmap p = previews.get(id);
            long left = renderUntil - android.os.SystemClock.uptimeMillis();
            if (p != null && left <= 0) {
                BitmapShader sh = new BitmapShader(p, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                matrix.setScale(iw / p.getWidth(), ih / p.getHeight());
                matrix.postTranslate(x, y);
                sh.setLocalMatrix(matrix);
                fill.setShader(sh);
                c.drawRoundRect(r, 28 * k, 28 * k, fill);
                fill.setShader(null);
            } else {
                c.drawText("Renderizando fondo…", w / 2f, y + ih / 2 - 10 * k, sub);
                float f = Math.max(0, Math.min(1, 1 - left / 700f));
                fill.setColor(0x33FFFFFF);
                r.set(x + 200 * k, y + ih / 2 + 20 * k, x + iw - 200 * k, y + ih / 2 + 32 * k);
                c.drawRoundRect(r, 6 * k, 6 * k, fill);
                fill.setColor(0xFFFFFFFF);
                r.right = r.left + (iw - 400 * k) * f;
                c.drawRoundRect(r, 6 * k, 6 * k, fill);
                postInvalidateDelayed(40);
            }
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(4 * k);
            fill.setColor(0x88FFFFFF);
            r.set(x, y, x + iw, y + ih);
            c.drawRoundRect(r, 28 * k, 28 * k, fill);
            fill.setStyle(Paint.Style.FILL);
            // the arrows, when more than one came
            if (fresh.size() > 1) {
                fill.setColor(0xFFFFFFFF);
                float ay = y + ih / 2;
                android.graphics.Path a = new android.graphics.Path();
                a.moveTo(x - 30 * k, ay - 34 * k);
                a.lineTo(x - 80 * k, ay);
                a.lineTo(x - 30 * k, ay + 34 * k);
                a.close();
                c.drawPath(a, fill);
                a.reset();
                a.moveTo(x + iw + 30 * k, ay - 34 * k);
                a.lineTo(x + iw + 80 * k, ay);
                a.lineTo(x + iw + 30 * k, ay + 34 * k);
                a.close();
                c.drawPath(a, fill);
                c.drawText((freshIndex + 1) + " de " + fresh.size(), w / 2f, y + ih + 50 * k, sub);
            }
            // the buttons: whole or filling, higher, lower, accept
            float[] a2 = adj.get(id);
            boolean filling = a2 == null || a2[0] == 0;
            String[] labels = {filling ? "Ver entera" : "Llenar pantalla", "\u25B2 Subir", "\u25BC Bajar", "Aceptar"};
            label.setTextAlign(Paint.Align.CENTER);
            label.setTextSize(28 * k);
            float bw = 320 * k, gap = 20 * k, bx = (w - (4 * bw + 3 * gap)) / 2f, by = y + ih + 90 * k;
            for (int i = 0; i < 4; i++) {
                float bl = bx + i * (bw + gap);
                r.set(bl, by, bl + bw, by + 72 * k);
                boolean on = freshRow == 1 && freshBtn == i, off = (i == 1 || i == 2) && !filling;
                fill.setColor(i == 3 ? (on ? 0xFFFF2A3A : 0xFFC4101F) : (on ? 0x66FFFFFF : 0x28FFFFFF));
                c.drawRoundRect(r, 36 * k, 36 * k, fill);
                if (on) {
                    fill.setStyle(Paint.Style.STROKE);
                    fill.setStrokeWidth(4 * k);
                    fill.setColor(0xFFFFFFFF);
                    c.drawRoundRect(r, 36 * k, 36 * k, fill);
                    fill.setStyle(Paint.Style.FILL);
                }
                label.setAlpha(off ? 90 : 255);
                c.drawText(labels[i], bl + bw / 2, by + 48 * k, label);
                label.setAlpha(255);
            }
            label.setTextSize(26 * k);
            label.setTextAlign(Paint.Align.LEFT);
            head.setTextAlign(Paint.Align.LEFT);
            sub.setTextAlign(Paint.Align.LEFT);
        }

        private void drawCode(Canvas c, int w, int h) {
            if (!fresh.isEmpty()) {
                drawFresh(c, w, h);
                return;
            }
            fill.setColor(0xE8000000);
            c.drawRect(0, 0, w, h, fill);
            float s = 460 * k, x = w / 2f - s - 40 * k, y = h / 2f - s / 2;
            fill.setColor(0xFFFFFFFF);
            r.set(x - 24 * k, y - 24 * k, x + s + 24 * k, y + s + 24 * k);
            c.drawRoundRect(r, 28 * k, 28 * k, fill);
            if (qr != null) {
                r.set(x, y, x + s, y + s);
                fill.setFilterBitmap(false);
                c.drawBitmap(qr, null, r, fill);
                fill.setFilterBitmap(true);
            }
            float tx = w / 2f + 40 * k;
            head.setTextSize(50 * k);
            c.drawText("Agrega tus fondos", tx, y + 70 * k, head);
            head.setTextSize(58 * k);
            sub.setTextSize(30 * k);
            c.drawText("1. Apunta la cámara del celular al código.", tx, y + 150 * k, sub);
            c.drawText("2. Toca el enlace y elige tus fotos.", tx, y + 200 * k, sub);
            c.drawText("3. Aparecen aquí solas.", tx, y + 250 * k, sub);
            sub.setTextSize(24 * k);
            c.drawText("El celular debe estar en el mismo Wi-Fi.", tx, y + 320 * k, sub);
            c.drawText("Atrás para cerrar.", tx, y + 360 * k, sub);
            sub.setTextSize(28 * k);
        }
    }

    /** A QR code's modules as a small bitmap with a quiet margin. */
    private static final class Qrs {
        static Bitmap bitmap(boolean[][] m) {
            int n = m.length, q = 2;
            int[] px = new int[(n + 2 * q) * (n + 2 * q)];
            Arrays.fill(px, 0xFFFFFFFF);
            for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) if (m[y][x]) px[(y + q) * (n + 2 * q) + x + q] = 0xFF000000;
            return Bitmap.createBitmap(px, n + 2 * q, n + 2 * q, Bitmap.Config.ARGB_8888);
        }
    }
}
