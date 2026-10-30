package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.net.Uri;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What any app is playing (title, artist or channel, the app), from the media session each app publishes for the remote's
 * play/pause and the Assistant: one way for music, videos, radio and live streams alike. Reading the sessions needs this
 * notification listener allowed (HomeMode does it while Home Settings' Now Playing widget is on); it never reads a
 * notification and asks the system to unbind it at once, so no process stays up for it.
 */
public class NowPlaying extends NotificationListenerService {

    @Override public void onListenerConnected() {
        requestUnbind();
    }

    private static final float[] STILL = {0.55f, 1, 0.75f}, PERIOD_MS = {170, 230, 200};

    /** The bars' width for text of this cap height (drawBars). */
    static float barsWidth(float cap) {
        return cap * 1.12f;
    }

    /** Three level bars before the title, standing on its baseline, `cap` tall: still, or bouncing like a level meter. */
    static void drawBars(Canvas c, float x, float base, float cap, Paint p, boolean moving, RectF r) {
        long t = SystemClock.uptimeMillis();
        for (int i = 0; i < 3; i++) {
            float level = moving ? 0.3f + 0.7f * Math.abs((float) Math.sin(t / PERIOD_MS[i] + i * 1.3f)) : STILL[i];
            float bx = x + i * cap * 0.42f;
            r.set(bx, base - cap * level, bx + cap * 0.28f, base);
            c.drawRoundRect(r, cap * 0.14f, cap * 0.14f, p);
        }
    }

    static ComponentName component(Context c) {
        return new ComponentName(c, NowPlaying.class);
    }

    /** Whether Android lets us read the sessions: our listener is allowed. */
    static boolean allowed(Context c) {
        try {
            c.getSystemService(MediaSessionManager.class).getActiveSessions(component(c));
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    private static boolean asked;
    private static final ExecutorService ART = Executors.newSingleThreadExecutor();

    /** A cover from an address (https, or a content address of the player), cut down to what the card shows. */
    static Bitmap fetch(Context c, String address) {
        try {
            if (address.startsWith("spotify:image:")) address = "https://i.scdn.co/image/" + address.substring(14);
            Uri u = Uri.parse(address);
            for (int pass = 1; pass < 2; pass++) {
                InputStream in = null;
                try {
                    if ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) {
                        HttpURLConnection h = (HttpURLConnection) new URL(address).openConnection();
                        h.setConnectTimeout(6000);
                        h.setReadTimeout(8000);
                        in = h.getInputStream();
                    } else if (ContentResolver.SCHEME_CONTENT.equals(u.getScheme()) || ContentResolver.SCHEME_FILE.equals(u.getScheme())) {
                        in = c.getContentResolver().openInputStream(u);
                    } else {
                        return null;
                    }
                    if (in == null) return null;
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    if (pass == 0) {
                        o.inJustDecodeBounds = true;
                        BitmapFactory.decodeStream(in, null, o);
                        continue;
                    }
                    return BitmapFactory.decodeStream(in, null, o);
                } finally {
                    if (in != null) try { in.close(); } catch (java.io.IOException ignored) { }
                }
            }
        } catch (Exception | OutOfMemoryError e) {
            AppLog.w("Cover not loaded: " + e);
        }
        return null;
    }

    /** A screen's watch: start() when it shows, stop() when it's hidden. text is null while nothing plays. */
    static final class Watch implements MediaSessionManager.OnActiveSessionsChangedListener {
        private final Context context;
        private final Runnable changed;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final List<MediaController> controllers = new ArrayList<>();
        private final Map<String, String> labels = new HashMap<>();   // some players report every second
        private final MediaController.Callback callback = new MediaController.Callback() {
            @Override public void onPlaybackStateChanged(PlaybackState state) { update(); }
            @Override public void onMetadataChanged(MediaMetadata metadata) { update(); }
            @Override public void onSessionDestroyed() { update(); }
        };
        private boolean on;
        // The music card: title, artist, cover and the session that plays it (also kept while it is paused).
        volatile MediaController card;
        volatile String cardTitle, cardArtist;
        volatile Bitmap cardArt;
        volatile boolean cardPlaying;
        private String cardSig = "", artKey = "";
        volatile String text;
        /** The title alone (the app's package when it gives none): the line's other parts can change every second. */
        volatile String title;

        Watch(Context c, Runnable changed) {
            context = c;
            this.changed = changed;
        }

        void start() {
            if (on || !Prefs.nowPlaying()) return;   // off in Home Settings: nothing watched at all
            MediaSessionManager m = context.getSystemService(MediaSessionManager.class);
            try {
                m.addOnActiveSessionsChangedListener(this, component(context), handler);
                on = true;
                onActiveSessionsChanged(m.getActiveSessions(component(context)));
            } catch (SecurityException e) {
                // On, but not allowed (it was on when Original or the watchdog took it back): asked once, as Tian Demon TV
                // mode's switch itself never allows it. A TV that refused it shows the row unavailable instead.
                if (!asked && Capabilities.adb(context) && !Capabilities.refused(context, Capabilities.LISTENER)) {
                    asked = true;
                    HomeMode.nowPlayingAccess(context);
                }
            }
        }

        void stop() {
            if (!on) return;
            on = false;
            context.getSystemService(MediaSessionManager.class).removeOnActiveSessionsChangedListener(this);
            onActiveSessionsChanged(null);
        }

        @Override public void onActiveSessionsChanged(List<MediaController> sessions) {
            for (MediaController mc : controllers) mc.unregisterCallback(callback);
            controllers.clear();
            if (sessions != null && on) {
                for (MediaController mc : sessions) {
                    mc.registerCallback(callback, handler);
                    controllers.add(mc);
                }
            }
            update();
        }

        private void update() {
            String now = null, nowTitle = null;
            for (MediaController mc : controllers) {   // most recent first
                PlaybackState s = mc.getPlaybackState();
                int state = s == null ? PlaybackState.STATE_NONE : s.getState();
                // A stream that buffers is still playing: the line doesn't blink back to the prices.
                if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
                        || state == PlaybackState.STATE_CONNECTING) {
                    now = describe(mc);
                    MediaMetadata m = mc.getMetadata();
                    CharSequence t = m == null ? null : m.getDescription().getTitle();
                    nowTitle = now == null ? null : TextUtils.isEmpty(t) ? mc.getPackageName() : t.toString().trim();
                    break;
                }
            }
            updateCard();
            if (Objects.equals(now, text) && !cardChanged) return;
            cardChanged = false;
            title = nowTitle;
            text = now;
            changed.run();
        }

        private boolean cardChanged;

        private void updateCard() {
            MediaController pick = null;
            boolean playingNow = false;
            for (MediaController mc : controllers) {
                PlaybackState s = mc.getPlaybackState();
                int state = s == null ? PlaybackState.STATE_NONE : s.getState();
                if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
                        || state == PlaybackState.STATE_CONNECTING) {
                    pick = mc;
                    playingNow = true;
                    break;
                }
            }
            if (pick == null && !controllers.isEmpty()) {   // the latest one, paused: the card stays to resume it
                MediaController first = controllers.get(0);
                PlaybackState s = first.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PAUSED && first.getMetadata() != null) pick = first;
            }
            String t = null, a = null, uri = null;
            Bitmap bmp = null;
            if (pick != null) {
                MediaMetadata m = pick.getMetadata();
                if (m != null) {
                    t = first(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
                    a = first(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
                    bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                    if (bmp == null) bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
                    if (bmp == null) bmp = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                    if (bmp == null) {
                        MediaDescription d = m.getDescription();
                        bmp = d == null ? null : d.getIconBitmap();
                    }
                    if (bmp == null) {
                        uri = first(m, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI,
                                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
                        if (uri == null) {
                            MediaDescription d = m.getDescription();
                            Uri u = d == null ? null : d.getIconUri();
                            if (u != null) uri = u.toString();
                        }
                    }
                }
                if (TextUtils.isEmpty(t)) pick = null;
            }
            String key = bmp != null ? "b" + System.identityHashCode(bmp) : uri == null ? "" : uri;
            String sig = pick == null ? "" : t + "|" + a + "|" + playingNow + "|" + pick.getPackageName() + "|" + key;
            if (sig.equals(cardSig)) return;
            boolean sameArt = key.equals(artKey) && cardArt != null;
            cardSig = sig;
            artKey = key;
            card = pick;
            cardTitle = pick == null ? null : t;
            cardArtist = pick == null ? null : a;
            cardPlaying = playingNow;
            if (bmp != null) cardArt = bmp;
            else if (!sameArt) cardArt = null;
            cardChanged = true;
            if (pick != null && bmp == null && uri != null && !sameArt) loadArt(uri);
        }

        private String first(MediaMetadata m, String... keys) {
            for (String k : keys) {
                String v = m.getString(k);
                if (!TextUtils.isEmpty(v)) return v.trim();
            }
            return null;
        }

        private void loadArt(final String uri) {
            ART.execute(() -> {
                Bitmap b = fetch(context, uri);
                if (b == null) return;
                handler.post(() -> {
                    if (!on || !uri.equals(artKey)) return;
                    cardArt = b;
                    changed.run();
                });
            });
        }

        /** The card's buttons: 0 previous, 1 play or pause, 2 next. */
        void control(int which) {
            MediaController mc = card;
            if (mc == null) return;
            MediaController.TransportControls tc = mc.getTransportControls();
            if (which == 0) tc.skipToPrevious();
            else if (which == 2) tc.skipToNext();
            else if (cardPlaying) tc.pause();
            else tc.play();
        }

        /** "Title · Artist · App": the parts the app gives, then its name; null when there's none of them. */
        private String describe(MediaController mc) {
            List<String> parts = new ArrayList<>();
            MediaMetadata m = mc.getMetadata();
            MediaDescription d = m == null ? null : m.getDescription();
            if (d != null && !TextUtils.isEmpty(d.getTitle())) parts.add(d.getTitle().toString().trim());
            if (d != null && !TextUtils.isEmpty(d.getSubtitle())) parts.add(d.getSubtitle().toString().trim());
            String app = labels.get(mc.getPackageName());
            if (app == null) {
                try {
                    PackageManager pm = context.getPackageManager();
                    app = pm.getApplicationLabel(pm.getApplicationInfo(mc.getPackageName(), 0)).toString();
                } catch (PackageManager.NameNotFoundException e) {
                    app = "";   // an app without a launcher icon is hidden from us (Android 11+): no name to show
                }
                labels.put(mc.getPackageName(), app);
            }
            if (!app.isEmpty() && !parts.contains(app)) parts.add(app);
            return parts.isEmpty() ? null : TextUtils.join(" · ", parts);
        }
    }
}
