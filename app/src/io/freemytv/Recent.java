package io.freemytv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * "Seguir viendo": what you were playing in your other apps (video, live TV, not music), kept as you watch it. The media
 * sessions tell the title, the app and the minute; the row on the home opens the app again. Lives in the home's process.
 */
final class Recent {
    private Recent() {}

    private static final int MAX = 4;
    private static final long TICK_MS = 20_000;
    private static final String[] SKIP = {"spotify", "music", "podcast", "radio", "soundcloud", "deezer", "audio"};
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final List<MediaController> WATCHED = new ArrayList<>();
    private static Context app;
    private static boolean on;

    private static final MediaController.Callback CALLBACK = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { sweep(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { sweep(); }
    };

    private static final MediaSessionManager.OnActiveSessionsChangedListener LISTENER = sessions -> {
        attach(sessions);
        sweep();
    };

    private static final Runnable TICK = new Runnable() {
        @Override public void run() {
            sweep();
            H.postDelayed(this, TICK_MS);
        }
    };

    /** Starts once and stays: the listener does not depend on the home being in front. */
    static synchronized void start(Context c) {
        if (on) return;
        app = c.getApplicationContext();
        try {
            MediaSessionManager m = app.getSystemService(MediaSessionManager.class);
            m.addOnActiveSessionsChangedListener(LISTENER, NowPlaying.component(app), H);
            attach(m.getActiveSessions(NowPlaying.component(app)));
            on = true;
            H.postDelayed(TICK, TICK_MS);
        } catch (RuntimeException e) {
            AppLog.w("recent: " + e.getMessage());
        }
    }

    private static void attach(List<MediaController> sessions) {
        for (MediaController mc : WATCHED) mc.unregisterCallback(CALLBACK);
        WATCHED.clear();
        if (sessions == null) return;
        for (MediaController mc : sessions) {
            mc.registerCallback(CALLBACK, H);
            WATCHED.add(mc);
        }
    }

    private static void sweep() {
        for (MediaController mc : WATCHED) {
            try {
                record(mc);
            } catch (RuntimeException e) {
                AppLog.w("recent: " + e.getMessage());
            }
        }
    }

    private static void record(MediaController mc) {
        String pkg = mc.getPackageName();
        if (pkg == null || pkg.equals(app.getPackageName())) return;
        String low = pkg.toLowerCase();
        for (String s : SKIP) if (low.contains(s)) return;
        PlaybackState s = mc.getPlaybackState();
        MediaMetadata m = mc.getMetadata();
        if (s == null || m == null) return;
        int st = s.getState();
        boolean playing = st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING;
        if (!playing && st != PlaybackState.STATE_PAUSED) return;
        String title = first(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        if (TextUtils.isEmpty(title)) return;
        String sub = first(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        long dur = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        long pos = Math.max(0, s.getPosition());
        if (playing && s.getLastPositionUpdateTime() > 0) {
            pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        if (dur > 0 && pos > dur) pos = dur;
        final MediaMetadata md = m;
        boolean done = dur > 0 && dur - pos < 15_000;   // finished: it leaves the row
        synchronized (Recent.class) {
            JSONArray old = read(app);
            JSONArray out = new JSONArray();
            try {
                if (!done) {
                    JSONObject o = new JSONObject();
                    o.put("pkg", pkg).put("title", title).put("sub", sub == null ? "" : sub);
                    o.put("pos", pos).put("dur", dur).put("t", System.currentTimeMillis());
                    out.put(o);
                }
                for (int i = 0; i < old.length() && out.length() < MAX; i++) {
                    JSONObject x = old.getJSONObject(i);
                    if (pkg.equals(x.optString("pkg")) && title.equals(x.optString("title"))) continue;
                    out.put(x);
                }
            } catch (org.json.JSONException e) {
                return;
            }
            write(out);
        }
        saveArt(pkg, title, md);
    }

    private static String artName(String pkg, String title) {
        return "recent-art/" + Integer.toHexString((pkg + "|" + title).hashCode()) + ".jpg";
    }

    /** The card's picture, kept once per title (in the background: it may need a download). */
    private static void saveArt(String pkg, String title, MediaMetadata m) {
        File f = new File(app.getFilesDir(), artName(pkg, title));
        if (f.exists()) return;
        android.graphics.Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        final android.graphics.Bitmap bmp = b;
        final String uri = first(m, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
        if (bmp == null && uri == null) return;
        ART.execute(() -> {
            android.graphics.Bitmap x = bmp != null ? bmp : NowPlaying.fetch(app, uri);
            if (x == null) return;
            f.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(f)) {
                x.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out);
            } catch (java.io.IOException e) {
                f.delete();
            }
        });
    }

    private static final java.util.concurrent.ExecutorService ART = java.util.concurrent.Executors.newSingleThreadExecutor();

    private static String first(MediaMetadata m, String... keys) {
        for (String k : keys) {
            String v = m.getString(k);
            if (!TextUtils.isEmpty(v)) return v.trim();
        }
        return null;
    }

    /** The row's cards, most recent first; apps that were uninstalled are left out. */
    static synchronized List<WatchNext.Item> load(Context c) {
        List<WatchNext.Item> list = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        JSONArray a = read(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String pkg = o.optString("pkg");
            String label;
            try {
                label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            File art = new File(c.getFilesDir(), artName(pkg, o.optString("title")));
            WatchNext.Item it = new WatchNext.Item(pkg, art.exists() ? android.net.Uri.fromFile(art).toString() : null, null);
            it.title = o.optString("title");
            it.episode = o.optString("sub").isEmpty() ? null : o.optString("sub");
            it.app = label;
            it.position = o.optLong("pos");
            it.duration = o.optLong("dur");
            it.engaged = o.optLong("t");
            list.add(it);
        }
        return list;
    }

    private static JSONArray read(Context c) {
        File f = new File(c.getFilesDir(), "recent.json");
        if (!f.exists()) return new JSONArray();
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            return new JSONArray(new String(b, 0, n, "UTF-8"));
        } catch (java.io.IOException | org.json.JSONException e) {
            return new JSONArray();
        }
    }

    private static void write(JSONArray a) {
        File f = new File(app.getFilesDir(), "recent.json"), tmp = new File(app.getFilesDir(), "recent.json.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(a.toString().getBytes("UTF-8"));
        } catch (java.io.IOException e) {
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }
}
