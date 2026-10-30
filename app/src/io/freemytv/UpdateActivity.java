package io.freemytv;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * "Tian Demon TV, new update, version 0.2" with what is new, and the Update button below: it downloads the APK (only from
 * this project's GitHub releases), checks it is this app's, and hands it to Android's installer, which asks once.
 */
public class UpdateActivity extends Activity {
    private static final String RESULT = "io.freemytv.TIAN_UPDATE";
    private UpdateView view;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String version, notes, url;
    private long size;
    private int state;        // 0 ready, 1 downloading, 2 installing, 3 failed
    private float progress;
    private String message = "";

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    state = 2;
                    message = "Confirma la instalación en la pantalla";
                    startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                state = 3;
                message = "No se pudo instalar. Prueba otra vez.";
            }
            view.invalidate();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        overridePendingTransition(0, 0);
        Intent i = getIntent();
        version = i.getStringExtra("version");
        notes = i.getStringExtra("notes");
        url = i.getStringExtra("url");
        size = i.getLongExtra("size", 0);
        if (version == null || url == null || !url.startsWith(TianUpdate.DOWNLOADS)) {
            finish();
            return;
        }
        view = new UpdateView(this);
        setContentView(view);
        IntentFilter filter = new IntentFilter(RESULT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
    }

    @Override protected void onDestroy() {
        try {
            unregisterReceiver(receiver);
        } catch (RuntimeException ignored) {
        }
        super.onDestroy();
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (view != null && view.onKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    private void start() {
        if (state == 1) return;
        state = 1;
        progress = 0;
        message = "";
        final Context app = getApplicationContext();
        new Thread(() -> {
            try {
                File dir = new File(app.getNoBackupFilesDir(), "update");
                dir.mkdirs();
                File apk = new File(dir, "TianMyTV.apk");
                download(apk);
                PackageInfo pi = getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
                if (pi == null || !getPackageName().equals(pi.packageName) || Updates.compare(pi.versionName, Updates.installedVersion(app)) <= 0) {
                    throw new IOException("not an update");
                }
                install(apk);
            } catch (IOException | RuntimeException e) {
                AppLog.w("update: " + e.getMessage());
                handler.post(() -> {
                    state = 3;
                    message = "No se pudo descargar. Revisa el Wi-Fi.";
                    view.invalidate();
                });
            }
        }, "UpdateDownload").start();
    }

    private void download(File to) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();   // github.com sends it on to its own download host
        h.setConnectTimeout(8000);
        h.setReadTimeout(15_000);
        long total = h.getContentLengthLong() > 0 ? h.getContentLengthLong() : size;
        if (total <= 0 || total > TianUpdate.MAX_SIZE) throw new IOException("bad size");
        try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[32768];
            long got = 0;
            for (int n; (n = in.read(buf)) > 0; ) {
                got += n;
                if (got > TianUpdate.MAX_SIZE) throw new IOException("too big");
                out.write(buf, 0, n);
                final float f = got / (float) total;
                handler.post(() -> {
                    progress = f;
                    view.invalidate();
                });
            }
            if (got != total) throw new IOException("cut short");
        }
    }

    private void install(File apk) throws IOException {
        PackageInstaller installer = getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(getPackageName());
        int id = installer.createSession(params);
        try (PackageInstaller.Session s = installer.openSession(id)) {
            try (OutputStream out = s.openWrite("TianMyTV", 0, apk.length()); InputStream in = new java.io.FileInputStream(apk)) {
                byte[] buf = new byte[32768];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent result = new Intent(RESULT).setPackage(getPackageName());
            PendingIntent pending = PendingIntent.getBroadcast(this, id, result, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            s.commit(pending.getIntentSender());
        }
        handler.post(() -> {
            state = 2;
            message = "Instalando…";
            view.invalidate();
        });
    }

    private final class UpdateView extends View {
        private final float k;
        private int focus;   // 0 update, 1 later
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint title = Ui.text(Ui.displaySemibold, 1, 0xFFFFFFFF), caps = Ui.text(Ui.semibold, 1, 0xFFFF6A72),
                big = Ui.text(Ui.displaySemibold, 1, 0xFFFFFFFF), body = Ui.text(Ui.medium, 1, 0xCCFFFFFF), button = Ui.text(Ui.semibold, 1, 0xFFFFFFFF);
        private final RectF card = new RectF(), r = new RectF();
        private Bitmap logo;

        UpdateView(Context c) {
            super(c);
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            k = dm.widthPixels / 1920f;
            setFocusable(true);
            title.setTextSize(40 * k);
            caps.setTextSize(24 * k);
            caps.setLetterSpacing(0.2f);
            big.setTextSize(88 * k);
            body.setTextSize(28 * k);
            button.setTextSize(32 * k);
            for (Paint p : new Paint[] {title, caps, big, body, button}) p.setTextAlign(Paint.Align.CENTER);
            try (InputStream in = c.getAssets().open("logo.png")) {
                logo = BitmapFactory.decodeStream(in);
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                logo = null;
            }
            card.set(560 * k, 90 * k, 1360 * k, 990 * k);
        }

        boolean onKey(KeyEvent e) {
            int code = e.getKeyCode();
            boolean ours = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP
                    || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
            if (code == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP && state != 1) {
                TianUpdate.later(UpdateActivity.this, version);
                finish();
                return true;
            }
            if (!ours || e.getAction() != KeyEvent.ACTION_DOWN) return ours;
            if (state == 1) return true;
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_LEFT) focus = 0;
            else if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_RIGHT) focus = 1;
            else if (e.getRepeatCount() == 0) {
                if (focus == 0) {
                    start();
                } else {
                    TianUpdate.later(UpdateActivity.this, version);
                    finish();
                }
            }
            invalidate();
            return true;
        }

        @Override protected void onDraw(Canvas c) {
            fill.setColor(0xCC000000);
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

            float cx = card.centerX(), y = card.top + 40 * k;
            if (logo != null) {
                r.set(cx - 90 * k, y, cx + 90 * k, y + 180 * k);
                c.drawBitmap(logo, null, r, fill);
            }
            c.drawText("Tian Demon TV", cx, y + 240 * k, title);
            c.drawText("NUEVA ACTUALIZACIÓN", cx, y + 290 * k, caps);
            c.drawText("Versión " + version, cx, y + 380 * k, big);
            // what is new, a few lines
            if (notes != null && !notes.isEmpty() && state == 0) {
                String[] lines = notes.split("\\r?\\n");
                int shown = 0;
                for (String line : lines) {
                    line = line.replaceFirst("^[#*\\-\\s]+", "").trim();
                    if (line.isEmpty()) continue;
                    if (body.measureText(line) > card.width() - 90 * k) {
                        int n = body.breakText(line, true, card.width() - 90 * k - body.measureText("…"), null);
                        line = line.substring(0, n) + "…";
                    }
                    c.drawText(line, cx, y + 440 * k + shown * 40 * k, body);
                    if (++shown == 4) break;
                }
            }
            float by = card.bottom - 290 * k;
            if (state == 1) {
                body.setColor(0xFFFFFFFF);
                c.drawText("Descargando… " + Math.round(progress * 100) + "%", cx, by + 20 * k, body);
                body.setColor(0xCCFFFFFF);
                fill.setColor(0x33FFFFFF);
                r.set(card.left + 90 * k, by + 50 * k, card.right - 90 * k, by + 62 * k);
                c.drawRoundRect(r, 6 * k, 6 * k, fill);
                fill.setColor(0xFFFFFFFF);
                r.right = r.left + (card.width() - 180 * k) * progress;
                c.drawRoundRect(r, 6 * k, 6 * k, fill);
                return;
            }
            if (state == 2 || state == 3) {
                body.setColor(state == 3 ? 0xFFFF8A90 : 0xFFFFFFFF);
                c.drawText(message, cx, by + 40 * k, body);
                body.setColor(0xCCFFFFFF);
                if (state == 2) return;
            }
            // the buttons
            String[] labels = {state == 3 ? "Reintentar" : "Actualizar", "Más tarde"};
            for (int i = 0; i < 2; i++) {
                float top = by + 70 * k + i * 100 * k;
                r.set(card.left + 150 * k, top, card.right - 150 * k, top + 80 * k);
                boolean on = focus == i;
                fill.setColor(i == 0 ? (on ? 0xFFFF2A3A : 0xFFC4101F) : (on ? 0x55FFFFFF : 0x22FFFFFF));
                c.drawRoundRect(r, 40 * k, 40 * k, fill);
                if (on) {
                    fill.setStyle(Paint.Style.STROKE);
                    fill.setStrokeWidth(4 * k);
                    fill.setColor(0xFFFFFFFF);
                    c.drawRoundRect(r, 40 * k, 40 * k, fill);
                    fill.setStyle(Paint.Style.FILL);
                }
                c.drawText(labels[i], cx, top + 52 * k, button);
            }
        }
    }
}
