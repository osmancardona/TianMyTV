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
            sweep(true);
            ART.execute(Recent::sweepScreen);
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
        sweep(false);
    }

    private static void sweep(boolean onlyPlaying) {
        for (MediaController mc : WATCHED) {
            try {
                PlaybackState ps = mc.getPlaybackState();
                if (onlyPlaying && (ps == null || ps.getState() != PlaybackState.STATE_PLAYING)) continue;
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
        store(pkg, title, sub, pos, dur, done);
        saveArt(pkg, title, md);
    }

    private static void store(String pkg, String title, String sub, long pos, long dur, boolean done) {
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
    }

    // Apps that play without a media session (Xuper): the title is read from its own screen, with the TV's own debug
    // connection (as Clean does), only while it is in front and something is playing.
    // The home page of the same apps (Xuper, Magis): the pictures on its screen, cut out, for the row under the apps.
    private static final java.util.regex.Pattern NODE = java.util.regex.Pattern.compile("<node [^>]*>");

    private static void discover(Adb adb, String top) throws java.io.IOException {
        java.util.regex.Matcher mp = java.util.regex.Pattern.compile("u0 ([A-Za-z0-9_.]+)/").matcher(top);
        if (!mp.find()) return;
        String pkg = mp.group(1);
        String xml = adb.shell("uiautomator dump /sdcard/tian-u.xml >/dev/null 2>&1; cat /sdcard/tian-u.xml; rm -f /sdcard/tian-u.xml");
        AppLog.w("discover xml " + (xml == null ? -1 : xml.length()));
        if (xml == null) return;
        List<int[]> boxes = new ArrayList<>();
        int side = 0;
        java.util.regex.Matcher mn = NODE.matcher(xml);
        while (mn.find()) {
            String n = mn.group();
            boolean tab = n.contains(":id/mGridTab\"");
            if (!tab && !n.contains(":id/mImgPoster\"")) continue;
            java.util.regex.Matcher mb = java.util.regex.Pattern.compile("bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"").matcher(n);
            if (!mb.find()) continue;
            int l = Integer.parseInt(mb.group(1)), t = Integer.parseInt(mb.group(2)), r = Integer.parseInt(mb.group(3)), b = Integer.parseInt(mb.group(4));
            if (tab) {
                side = r;   // the side menu may be open: it covers the first pictures
                continue;
            }
            if (t < 140 || b > 1080 || r - l < 300 || b - t < 150) continue;   // whole pictures only
            boxes.add(new int[] {l, t, r, b});
        }
        final int menu = side > 140 ? side : 0;
        boxes.removeIf(bx -> bx[0] < menu);
        AppLog.w("discover boxes " + boxes.size() + " menu " + menu);
        if (boxes.size() < 3) return;
        java.util.Collections.sort(boxes, (x, y) -> x[1] != y[1] ? x[1] - y[1] : x[0] - y[0]);
        String b64 = adb.shell("screencap -p | toybox base64 -w 0");
        byte[] png = b64 == null ? null : android.util.Base64.decode(b64.trim(), android.util.Base64.DEFAULT);
        android.graphics.Bitmap full = png == null ? null : android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length);
        String again = adb.shell("dumpsys activity activities | grep -m1 topResumedActivity");
        if (full == null || again == null || !again.contains(pkg + "/com.main.ui.activity.HomeActivity")) return;   // left meanwhile
        float sx = full.getWidth() / 1920f, sy = full.getHeight() / 1080f;
        File dir = new File(app.getFilesDir(), "disc2");
        dir.mkdirs();
        JSONArray names = new JSONArray();
        int n = 0;
        for (int[] bx : boxes) {
            if (n >= 8) break;
            int l = Math.round((bx[0] + 4) * sx), t = Math.round((bx[1] + 4) * sy), r = Math.round((bx[2] - 4) * sx), b = Math.round((bx[3] - 4) * sy);
            if (r - l < 40 || b - t < 40) continue;
            android.graphics.Bitmap crop = android.graphics.Bitmap.createBitmap(full, l, t, r - l, b - t);
            int w = Math.min(520, crop.getWidth()), h = Math.round(crop.getHeight() * (w / (float) crop.getWidth()));
            android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(crop, w, h, true);
            File f = new File(dir, "d" + n + ".jpg");
            try (FileOutputStream out = new FileOutputStream(f)) {
                small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out);
            }
            names.put(f.getName());
            n++;
        }
        AppLog.w("discover saved " + n);
        if (n < 3) return;
        try {
            File meta = new File(dir, "discover.json");
            try (FileOutputStream out = new FileOutputStream(meta)) {
                out.write(new JSONObject().put("pkg", pkg).put("files", names).toString().getBytes("UTF-8"));
            }
        } catch (org.json.JSONException e) {
            AppLog.w("recent discover: " + e.getMessage());
        }
    }

    /** What was last cut from the apps' home page: the app and the pictures (decoded here, in the background). */
    static final class Discover {
        String pkg;
        final List<android.graphics.Bitmap> pictures = new ArrayList<>();
    }

    static long discoverStamp(Context c) {
        return new File(new File(c.getFilesDir(), "disc2"), "discover.json").lastModified();
    }

    static Discover discoverNow(Context c) {
        File dir = new File(c.getFilesDir(), "disc2");
        File meta = new File(dir, "discover.json");
        if (!meta.exists()) return null;
        try (FileInputStream in = new FileInputStream(meta)) {
            byte[] b = new byte[(int) meta.length()];
            int got = 0;
            while (got < b.length) {
                int r = in.read(b, got, b.length - got);
                if (r < 0) break;
                got += r;
            }
            JSONObject o = new JSONObject(new String(b, 0, got, "UTF-8"));
            Discover d = new Discover();
            d.pkg = o.optString("pkg");
            JSONArray files = o.optJSONArray("files");
            if (files == null) return null;
            for (int i = 0; i < files.length(); i++) {
                android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(new File(dir, files.getString(i)).getPath());
                if (bm != null) d.pictures.add(bm);
            }
            return d.pictures.size() >= 3 ? d : null;
        } catch (java.io.IOException | org.json.JSONException | RuntimeException e) {
            return null;
        }
    }

    /** A frame the TV could not capture (video layers can come out black): not worth keeping as a picture. */
    private static boolean dark(android.graphics.Bitmap b) {
        android.graphics.Bitmap t = android.graphics.Bitmap.createScaledBitmap(b, 16, 9, true);
        int lit = 0;
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 16; x++) {
                int c = t.getPixel(x, y);
                if (((c >> 16) & 255) + ((c >> 8) & 255) + (c & 255) > 150) lit++;   // subtitles and logos alone stay under a third
            }
        }
        return lit < 48;
    }

    private static boolean playingSession() {
        for (MediaController mc : WATCHED) {
            PlaybackState s = mc.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) return true;
        }
        return false;
    }

    private static void sweepScreen() {
        try {
            android.media.AudioManager am = app.getSystemService(android.media.AudioManager.class);
            try (Adb adb = Adb.connect(app, 3000, null)) {
                String top = adb.shell("dumpsys activity activities | grep -m1 topResumedActivity");
                if (top == null || top.contains(app.getPackageName())) return;
                if (top.contains("/com.main.ui.activity.HomeActivity")) {
                    discover(adb, top);
                    return;
                }
                if (am == null || !am.isMusicActive()) return;
                if (!top.contains("/com.vod.ui.activity.VodDetailsActivity")) return;
                String xml = adb.shell("uiautomator dump /sdcard/tian-u.xml >/dev/null 2>&1; cat /sdcard/tian-u.xml; rm -f /sdcard/tian-u.xml");
                java.util.regex.Matcher mt = java.util.regex.Pattern
                        .compile("<node [^>]*text=\"([^\"]*)\"[^>]*resource-id=\"[^\"]*:id/mTextTitle\"").matcher(xml == null ? "" : xml);
                if (!mt.find()) return;
                String title = mt.group(1).replace("&amp;", "&").replace("&quot;", "\"").replace("&apos;", "'").trim();
                if (title.isEmpty()) return;
                java.util.regex.Matcher mp = java.util.regex.Pattern.compile("u0 ([A-Za-z0-9_.]+)/").matcher(top);
                if (!mp.find()) return;
                String pkg = mp.group(1);
                store(pkg, title, "", 0, 0, false);
                File f = new File(app.getFilesDir(), artName(pkg, title));
                if (!f.exists()) {
                    String b64 = adb.shell("screencap -p | toybox base64 -w 0");
                    byte[] png = b64 == null ? null : android.util.Base64.decode(b64.trim(), android.util.Base64.DEFAULT);
                    android.graphics.Bitmap full = png == null ? null : android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length);
                    if (full != null && !dark(full)) {
                        android.graphics.Bitmap x = android.graphics.Bitmap.createScaledBitmap(full, 640, 360, true);
                        f.getParentFile().mkdirs();
                        try (FileOutputStream out = new FileOutputStream(f)) {
                            x.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out);
                        }
                    }
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            AppLog.w("recent screen: " + e.getMessage());
        }
    }

    private static String artName(String pkg, String title) {
        return "recent-art/v3-" + Integer.toHexString((pkg + "|" + title).hashCode()) + ".jpg";
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
