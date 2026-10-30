package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Looks for a newer Tian Demon TV in this project's GitHub releases and, when there is one, opens UpdateActivity: the
 * version, what is new and an Update button. The APK may only come from this repository's releases.
 */
final class TianUpdate {
    private TianUpdate() {}

    private static final String LATEST = "https://api.github.com/repos/osmancardona/TianMyTV/releases/latest";
    static final String DOWNLOADS = "https://github.com/osmancardona/TianMyTV/releases/download/";
    private static final String ASSET = "TianMyTV.apk";
    private static final long EVERY = 6 * 3600_000L;
    static final long MAX_SIZE = 30L << 20;

    static final class Info {
        String version = "", notes = "", url = "";
        long size;
    }

    private static boolean checking;

    /** The latest release when it is newer than this app, else null. Blocks: never on the main thread. */
    static Info check(Context c) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(LATEST).openConnection();
        h.setConnectTimeout(8000);
        h.setReadTimeout(10_000);
        h.setRequestProperty("Accept", "application/vnd.github+json");
        if (h.getResponseCode() != 200) throw new IOException("GitHub: " + h.getResponseCode());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = h.getInputStream()) {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0 && out.size() < 1_000_000; ) out.write(buf, 0, n);
        }
        try {
            JSONObject o = new JSONObject(out.toString("UTF-8"));
            Info info = new Info();
            info.version = o.optString("tag_name", "").replaceFirst("^[vV]", "");
            info.notes = o.optString("body", "").trim();
            JSONArray assets = o.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (ASSET.equals(a.optString("name"))) {
                    info.url = a.optString("browser_download_url");
                    info.size = a.optLong("size");
                }
            }
            if (info.version.isEmpty() || !info.url.startsWith(DOWNLOADS) || info.size <= 0 || info.size > MAX_SIZE) return null;
            return Updates.compare(info.version, Updates.installedVersion(c)) > 0 ? info : null;
        } catch (org.json.JSONException e) {
            throw new IOException("GitHub: " + e.getMessage());
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("tian", Context.MODE_PRIVATE);
    }

    static void later(Context c, String version) {
        prefs(c).edit().putString("updateSkipped", version).apply();
    }

    static Intent screen(Context c, Info info) {
        return new Intent(c, UpdateActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("version", info.version).putExtra("notes", info.notes).putExtra("url", info.url).putExtra("size", info.size);
    }

    /** From the home screen: checks at most every 6 hours, and speaks up once per version (Later keeps it quiet). */
    static void maybeShow(Context context) {
        final Context c = context.getApplicationContext();
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        synchronized (TianUpdate.class) {
            if (checking || now - p.getLong("updateChecked", 0) < EVERY) return;
            checking = true;
        }
        new Thread(() -> {
            try {
                Info info = check(c);
                p.edit().putLong("updateChecked", System.currentTimeMillis()).apply();
                if (info != null && !info.version.equals(p.getString("updateSkipped", ""))) {
                    new Handler(Looper.getMainLooper()).post(() -> c.startActivity(screen(c, info)));
                }
            } catch (IOException | RuntimeException e) {
                AppLog.w("update: " + e.getMessage());
            } finally {
                synchronized (TianUpdate.class) {
                    checking = false;
                }
            }
        }, "TianUpdate").start();
    }

    static byte[] unused() {
        return "".getBytes(StandardCharsets.UTF_8);
    }
}
