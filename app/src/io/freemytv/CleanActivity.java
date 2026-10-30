package io.freemytv;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.View;

import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cleaning: looks at the memory, the free space, the cache the apps piled up and the apps that haven't been opened for a
 * long time. "Limpiar ahora" empties the caches (never an app's data) and closes what runs in the background; the apps nobody
 * opens are only listed: OK on one asks Android to remove it, and Android asks you first. Runs through the TV's own ADB.
 */
public class CleanActivity extends Activity {
    private CleanView view;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int state;                 // 0 looking, 1 ready, 2 cleaning, 3 done, 4 no ADB
    private long ramFree, ramTotal, spaceFree, spaceTotal, cacheBytes;
    private long ramFreeAfter, spaceFreeAfter;
    private String step = "";
    private final List<String[]> unused = new ArrayList<>();   // {package, name, days}

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        overridePendingTransition(0, 0);
        view = new CleanView(this);
        setContentView(view);
        analyze();
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (view.onKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    private void measure(boolean after) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        getSystemService(ActivityManager.class).getMemoryInfo(mi);
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        if (after) {
            ramFreeAfter = mi.availMem;
            spaceFreeAfter = fs.getAvailableBytes();
        } else {
            ramFree = mi.availMem;
            ramTotal = mi.totalMem;
            spaceFree = fs.getAvailableBytes();
            spaceTotal = fs.getTotalBytes();
        }
    }

    private void analyze() {
        state = 0;
        final Context app = getApplicationContext();
        measure(false);
        new Thread(() -> {
            long cache = 0;
            List<String[]> old = new ArrayList<>();
            boolean adbOk = true;
            try (Adb adb = Adb.connect(app, 6000, null)) {
                Matcher m = Pattern.compile("App Cache Size: (\\d+)").matcher(adb.shell("dumpsys diskstats"));
                if (m.find()) cache = Long.parseLong(m.group(1));
                // the apps that came from the store, and when each was last used
                List<String> packages = new ArrayList<>();
                for (String line : adb.shell("pm list packages -3").split("\n")) {
                    line = line.trim();
                    if (line.startsWith("package:")) packages.add(line.substring(8));
                }
                String usage = adb.shell("dumpsys usagestats");
                int from = usage.indexOf("In-memory yearly stats");
                Map<String, Long> last = new HashMap<>();
                if (from >= 0) {
                    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                    Pattern pk = Pattern.compile("package=(\\S+) ");
                    Pattern dt = Pattern.compile("lastTime\\w*=\"([^\"]+)\"");
                    for (String line : usage.substring(from).split("\n")) {
                        Matcher pm = pk.matcher(line);
                        if (!pm.find() || !line.contains("lastTime")) continue;
                        long best = last.containsKey(pm.group(1)) ? last.get(pm.group(1)) : 0;
                        Matcher dm = dt.matcher(line);
                        while (dm.find()) {
                            try {
                                long t = f.parse(dm.group(1)).getTime();
                                if (t > 946684800000L && t > best) best = t;   // 1969 means never
                            } catch (java.text.ParseException ignored) {
                            }
                        }
                        last.put(pm.group(1), best);
                    }
                }
                PackageManager pmgr = getPackageManager();
                long now = System.currentTimeMillis();
                for (String pkg : packages) {
                    if (pkg.equals(getPackageName())) continue;
                    Long t = last.get(pkg);
                    if (t == null || t == 0) continue;   // no record of it: not said to be unused
                    long days = (now - t) / 86_400_000L;
                    if (days < 14) continue;
                    String name = pkg;
                    try {
                        name = pmgr.getApplicationLabel(pmgr.getApplicationInfo(pkg, 0)).toString();
                    } catch (PackageManager.NameNotFoundException ignored) {
                    }
                    old.add(new String[] {pkg, name, String.valueOf(days)});
                }
                Collections.sort(old, (a, b) -> Integer.parseInt(b[2]) - Integer.parseInt(a[2]));
            } catch (IOException | RuntimeException e) {
                adbOk = false;
                AppLog.w("clean: " + e.getMessage());
            }
            final long cacheFound = cache;
            final boolean ok = adbOk;
            final List<String[]> list = old.size() > 3 ? new ArrayList<>(old.subList(0, 3)) : old;
            handler.post(() -> {
                cacheBytes = cacheFound;
                unused.clear();
                unused.addAll(list);
                state = ok ? 1 : 4;
                view.focus = 0;
                view.invalidate();
            });
        }, "CleanAnalyze").start();
    }

    private void clean() {
        state = 2;
        step = "Borrando la caché de las apps…";
        view.invalidate();
        final Context app = getApplicationContext();
        new Thread(() -> {
            try (Adb adb = Adb.connect(app, 6000, null)) {
                adb.shell("pm trim-caches 999999999999");
                handler.post(() -> {
                    step = "Cerrando lo que sobra en segundo plano…";
                    view.invalidate();
                });
                adb.shell("am kill-all");
                android.os.SystemClock.sleep(1500);
            } catch (IOException | RuntimeException e) {
                AppLog.w("clean: " + e.getMessage());
            }
            handler.post(() -> {
                measure(true);
                state = 3;
                view.focus = 0;
                view.invalidate();
            });
        }, "CleanRun").start();
    }

    private static String size(long bytes) {
        double mb = bytes / 1048576.0;
        return mb >= 1024 ? String.format(Locale.US, "%.1f GB", mb / 1024) : String.format(Locale.US, "%d MB", Math.round(mb));
    }

    private final class CleanView extends View {
        private final float k;
        int focus;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint title = Ui.text(Ui.displaySemibold, 1, 0xFFFFFFFF), caps = Ui.text(Ui.semibold, 1, 0xFFFF6A72),
                body = Ui.text(Ui.medium, 1, 0xCCFFFFFF), strong = Ui.text(Ui.semibold, 1, 0xFFFFFFFF), button = Ui.text(Ui.semibold, 1, 0xFFFFFFFF);
        private final RectF card = new RectF(), r = new RectF();
        private Bitmap logo;

        CleanView(Context c) {
            super(c);
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            k = dm.widthPixels / 1920f;
            setFocusable(true);
            title.setTextSize(44 * k);
            caps.setTextSize(24 * k);
            caps.setLetterSpacing(0.2f);
            body.setTextSize(26 * k);
            strong.setTextSize(28 * k);
            button.setTextSize(30 * k);
            for (Paint p : new Paint[] {title, caps, button}) p.setTextAlign(Paint.Align.CENTER);
            try (InputStream in = c.getAssets().open("logo.png")) {
                logo = BitmapFactory.decodeStream(in);
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                logo = null;
            }
            card.set(480 * k, 50 * k, 1440 * k, 1030 * k);
        }

        private int rows() {
            return state == 1 ? 2 + unused.size() : state == 3 ? 1 + unused.size() : state == 4 ? 1 : 0;
        }

        boolean onKey(KeyEvent e) {
            int code = e.getKeyCode();
            boolean ours = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP
                    || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
            if (code == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP && state != 2) {
                finish();
                return true;
            }
            if (!ours || e.getAction() != KeyEvent.ACTION_DOWN) return ours;
            if (state == 2 || state == 0) return true;
            if (code == KeyEvent.KEYCODE_DPAD_UP) focus = Math.max(0, focus - 1);
            else if (code == KeyEvent.KEYCODE_DPAD_DOWN) focus = Math.min(rows() - 1, focus + 1);
            else if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                if (e.getRepeatCount() > 0) return true;
                if (state == 1 && focus == 0) clean();
                else if ((state == 1 && focus == 1) || (state == 3 && focus == 0) || state == 4) finish();
                else {
                    int i = focus - (state == 1 ? 2 : 1);
                    if (i >= 0 && i < unused.size()) {
                        startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + unused.get(i)[0])).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    }
                }
            }
            invalidate();
            return true;
        }

        private void bar(Canvas c, float x, float y, float w, float f, int color) {
            fill.setColor(0x33FFFFFF);
            r.set(x, y, x + w, y + 12 * k);
            c.drawRoundRect(r, 6 * k, 6 * k, fill);
            fill.setColor(color);
            r.right = x + w * Math.max(0.02f, Math.min(1, f));
            c.drawRoundRect(r, 6 * k, 6 * k, fill);
        }

        private void pill(Canvas c, String text, float top, boolean primary, boolean on) {
            r.set(card.left + 140 * k, top, card.right - 140 * k, top + 74 * k);
            fill.setColor(primary ? (on ? 0xFFFF2A3A : 0xFFC4101F) : (on ? 0x55FFFFFF : 0x22FFFFFF));
            c.drawRoundRect(r, 37 * k, 37 * k, fill);
            if (on) {
                fill.setStyle(Paint.Style.STROKE);
                fill.setStrokeWidth(4 * k);
                fill.setColor(0xFFFFFFFF);
                c.drawRoundRect(r, 37 * k, 37 * k, fill);
                fill.setStyle(Paint.Style.FILL);
            }
            c.drawText(text, card.centerX(), top + 49 * k, button);
        }

        @Override protected void onDraw(Canvas c) {
            fill.setColor(0xDD000000);
            c.drawRect(0, 0, getWidth(), getHeight(), fill);
            fill.setStyle(Paint.Style.STROKE);
            for (int i = 4; i >= 1; i--) {
                fill.setColor(((0x10 + (4 - i) * 0x0A) << 24) | 0xFF1020);
                fill.setStrokeWidth((4 + i * 6) * k);
                c.drawRoundRect(card, 52 * k, 52 * k, fill);
            }
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0xFF000000);
            fill.setShader(new LinearGradient(0, card.top, 0, card.bottom, 0xFF3A0C16, 0xFF0A0306, Shader.TileMode.CLAMP));
            c.drawRoundRect(card, 52 * k, 52 * k, fill);
            fill.setShader(null);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(3.5f * k);
            fill.setColor(0xFFF0121F);
            c.drawRoundRect(card, 52 * k, 52 * k, fill);
            fill.setStyle(Paint.Style.FILL);

            float cx = card.centerX(), x = card.left + 80 * k, w = card.width() - 160 * k, y = card.top + 40 * k;
            if (logo != null) {
                r.set(cx - 60 * k, y, cx + 60 * k, y + 120 * k);
                c.drawBitmap(logo, null, r, fill);
            }
            c.drawText("Limpieza del TV", cx, y + 180 * k, title);
            c.drawText(state == 3 ? "LISTO" : state == 2 ? "TRABAJANDO" : "OPTIMIZAR TU TV", cx, y + 222 * k, caps);
            if (state == 0) {
                c.drawText("Revisando tu TV…", cx, y + 400 * k, strong.getTextAlign() == Paint.Align.CENTER ? strong : strong);
                return;
            }
            if (state == 4) {
                c.drawText("No pude revisar el TV: la Depuración USB está apagada.", x, y + 330 * k, body);
                pill(c, "Cerrar", card.bottom - 140 * k, false, true);
                return;
            }
            if (state == 2) {
                strong.setTextAlign(Paint.Align.CENTER);
                c.drawText(step, cx, y + 400 * k, strong);
                strong.setTextAlign(Paint.Align.LEFT);
                return;
            }
            float row = y + 290 * k;
            long freeRam = state == 3 ? ramFreeAfter : ramFree, freeSpace = state == 3 ? spaceFreeAfter : spaceFree;
            c.drawText("Memoria libre", x, row, strong);
            body.setTextAlign(Paint.Align.RIGHT);
            c.drawText(size(freeRam) + " de " + size(ramTotal) + (state == 3 && ramFreeAfter > ramFree ? "   (+" + size(ramFreeAfter - ramFree) + ")" : ""), x + w, row, body);
            body.setTextAlign(Paint.Align.LEFT);
            bar(c, x, row + 18 * k, w, freeRam / (float) ramTotal, 0xFFFF9F0A);
            row += 96 * k;
            c.drawText("Espacio libre", x, row, strong);
            body.setTextAlign(Paint.Align.RIGHT);
            c.drawText(size(freeSpace) + " de " + size(spaceTotal) + (state == 3 && spaceFreeAfter > spaceFree ? "   (+" + size(spaceFreeAfter - spaceFree) + ")" : ""), x + w, row, body);
            body.setTextAlign(Paint.Align.LEFT);
            bar(c, x, row + 18 * k, w, freeSpace / (float) spaceTotal, 0xFF1E7BFF);
            row += 80 * k;
            if (state == 1) {
                c.drawText("Caché que se puede borrar: " + size(cacheBytes), x, row, body);
                row += 30 * k;
            } else {
                c.drawText("Listo: caché borrada y apps cerradas en segundo plano.", x, row, body);
                row += 30 * k;
            }
            // the buttons
            float by = row + 20 * k;
            if (state == 1) {
                pill(c, "Limpiar ahora", by, true, focus == 0);
                pill(c, "Más tarde", by + 90 * k, false, focus == 1);
                by += 200 * k;
            } else {
                pill(c, "Cerrar", by, true, focus == 0);
                by += 110 * k;
            }
            // the apps nobody opens
            if (!unused.isEmpty()) {
                caps.setTextAlign(Paint.Align.LEFT);
                c.drawText("APPS QUE CASI NO USAS", x, by + 20 * k, caps);
                caps.setTextAlign(Paint.Align.CENTER);
                int first = state == 1 ? 2 : 1;
                for (int i = 0; i < unused.size(); i++) {
                    float top = by + 40 * k + i * 62 * k;
                    boolean on = focus == first + i;
                    r.set(x, top, x + w, top + 54 * k);
                    fill.setColor(on ? 0x55FFFFFF : 0x18FFFFFF);
                    c.drawRoundRect(r, 27 * k, 27 * k, fill);
                    String[] u = unused.get(i);
                    String name = u[1].length() > 28 ? u[1].substring(0, 27) + "…" : u[1];
                    c.drawText(name, x + 24 * k, top + 37 * k, strong);
                    body.setTextAlign(Paint.Align.RIGHT);
                    int d = Integer.parseInt(u[2]);
                    c.drawText(d >= 900 ? "nunca abierta" : "hace " + d + " días" + (on ? "  ·  OK: quitar" : ""), x + w - 24 * k, top + 36 * k, body);
                    body.setTextAlign(Paint.Align.LEFT);
                }
            }
        }
    }
}
