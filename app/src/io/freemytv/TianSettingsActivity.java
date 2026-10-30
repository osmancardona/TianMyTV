package io.freemytv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.View;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Tian Demon TV's own settings, short and plain: the wallpaper, the theme, the clock, the weather and the city, and the
 * way to the longer list (shelf, buttons, screensaver) and to Android's settings. Left and right change a value, OK opens.
 */
public class TianSettingsActivity extends Activity {
    private SettingsView view;

    private static final String[][] CITIES = {
        {"Bogotá", "4.7110", "-74.0721"}, {"Medellín", "6.2442", "-75.5812"}, {"La Ceja", "6.0305", "-75.4300"}, {"Rionegro", "6.1550", "-75.3740"}, {"Envigado", "6.1759", "-75.5917"}, {"Itagüí", "6.1846", "-75.5991"}, {"Cali", "3.4516", "-76.5320"},
        {"Barranquilla", "10.9685", "-74.7813"}, {"Cartagena", "10.3910", "-75.4794"}, {"Bucaramanga", "7.1193", "-73.1227"},
        {"Cúcuta", "7.8939", "-72.5078"}, {"Pereira", "4.8133", "-75.6961"}, {"Santa Marta", "11.2408", "-74.1990"},
        {"Manizales", "5.0703", "-75.5138"}, {"Ibagué", "4.4389", "-75.2322"}, {"Villavicencio", "4.1420", "-73.6266"},
        {"Pasto", "1.2136", "-77.2811"}, {"Montería", "8.7480", "-75.8814"}, {"Neiva", "2.9273", "-75.2819"},
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        overridePendingTransition(0, 0);
        view = new SettingsView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        if (view != null) view.invalidate();
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (view.onKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    private void open(Intent... intents) {
        for (Intent intent : intents) {
            try {
                startActivity(new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                AppLog.w("settings: couldn't open " + intent + ": " + e.getMessage());
            }
        }
    }

    private static String fit(String s, Paint p, float w) {
        if (p.measureText(s) <= w) return s;
        int n = p.breakText(s, true, Math.max(0, w - p.measureText("…")), null);
        return s.substring(0, Math.max(0, n)) + "…";
    }

    private final class SettingsView extends View {
        private final float k;
        private final String[] labels = {"Fondo de pantalla", "Tema", "Reloj", "Clima", "Ciudad", "Apagar el TV en", "Apagar cada noche a las", "Estante, botones y más", "Ajustes de Android", "Editor de pantalla", "Buscar actualización", "Limpiar y optimizar el TV"};
        private int focus;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint title = Ui.text(Ui.displaySemibold, 1, 0xFFFFFFFF), label = Ui.text(Ui.semibold, 1, 0xFFFFFFFF),
                value = Ui.text(Ui.medium, 1, 0xB3FFFFFF), small = Ui.text(Ui.medium, 1, 0x99FFFFFF);
        private final RectF r = new RectF();
        private Bitmap wallpaper, logo;

        SettingsView(Context c) {
            super(c);
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            k = dm.widthPixels / 1920f;
            setFocusable(true);
            title.setTextSize(54 * k);
            label.setTextSize(30 * k);
            value.setTextSize(32 * k);
            small.setTextSize(26 * k);
            title.setTextAlign(Paint.Align.CENTER);
            small.setTextAlign(Paint.Align.CENTER);
            try (InputStream in = c.getAssets().open("bg-alastor.jpg")) {
                wallpaper = BitmapFactory.decodeStream(in);
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                wallpaper = null;
            }
            try (InputStream in = c.getAssets().open("logo.png")) {
                logo = BitmapFactory.decodeStream(in);
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                logo = null;
            }
        }

        private String updateText = "Versión " + Updates.installedVersion(getContext());
        private boolean looking;

        private void lookForUpdate() {
            if (looking) return;
            looking = true;
            updateText = "Buscando…";
            invalidate();
            final Context c = getContext().getApplicationContext();
            new Thread(() -> {
                String text;
                TianUpdate.Info info = null;
                try {
                    info = TianUpdate.check(c);
                    text = info == null ? "Ya tienes la última" : "Hay una nueva: " + info.version;
                } catch (IOException | RuntimeException e) {
                    text = "Sin conexión";
                }
                final String shown = text;
                final TianUpdate.Info found = info;
                post(() -> {
                    looking = false;
                    updateText = shown;
                    invalidate();
                    if (found != null) startActivity(TianUpdate.screen(c, found));
                });
            }, "TianUpdateCheck").start();
        }

        private int daily() {
            return SleepTimer.daily(getContext());
        }

        private int cityIndex() {
            String city = Prefs.city();
            for (int i = 0; i < CITIES.length; i++) if (CITIES[i][0].equalsIgnoreCase(city)) return i;
            return -1;
        }

        private String valueOf(int i) {
            switch (i) {
                case 0: return getSharedPreferences("tian", MODE_PRIVATE).getString("wallpaper", "builtin:Alastor").startsWith("builtin:")
                        ? getSharedPreferences("tian", MODE_PRIVATE).getString("wallpaper", "builtin:Alastor").substring(8).replace("builtin:", "") : "Mi foto";
                case 1: return Prefs.theme().equals("dark") ? "Oscuro" : Prefs.theme().equals("light") ? "Claro" : "Automático";
                case 2: return Prefs.clockFormat().equals("24") ? "24 horas" : "12 horas";
                case 3: return Prefs.weather() ? "Con clima" : "Sin clima";
                case 10: return updateText;
                case 4: return Prefs.city().isEmpty() ? "Sin elegir" : Prefs.city();
                case 5: {
                    int left = SleepTimer.minutesLeft(getContext());
                    return left == 0 ? "Apagado" : SleepTimer.duration(left);
                }
                case 6: return daily() < 0 ? "Apagado" : new java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                        .format(new java.util.Date(0, 0, 1, daily() / 60, daily() % 60));
                default: return "";
            }
        }

        private void change(int dir) {
            switch (focus) {
                case 1: {
                    String[] all = {"auto", "dark", "light"};
                    int at = Math.max(0, java.util.Arrays.asList(all).indexOf(Prefs.theme()));
                    Prefs.setTheme(all[(at + dir + all.length) % all.length]);
                    break;
                }
                case 2:
                    Prefs.setClockFormat(Prefs.clockFormat().equals("24") ? "12" : "24");
                    break;
                case 3:
                    Prefs.setWeather(!Prefs.weather());
                    break;
                case 4: {
                    int at = cityIndex();
                    String[] c = CITIES[((at < 0 ? (dir > 0 ? -1 : 0) : at) + dir + CITIES.length) % CITIES.length];
                    Prefs.setCity(c[0], c[1], c[2]);
                    HomeData.get(getContext()).settingsChanged();
                    break;
                }
                case 5: {
                    int[] list = {0, 5, 10, 30, 60, 150, 180, 270, 300};
                    int left = SleepTimer.minutesLeft(getContext()), next = 0;
                    if (dir > 0) {
                        next = list[list.length - 1];
                        for (int m : list) if (m > left) { next = m; break; }
                        if (left >= list[list.length - 1]) next = 0;
                    } else {
                        for (int m : list) if (m < left) next = m;
                    }
                    SleepTimer.setDaily(getContext(), -1);
                    SleepTimer.set(getContext(), next);
                    break;
                }
                case 6: {
                    int at = daily(), idx = 0;
                    if (at >= 0) idx = 1 + (((at - 1200) % 1440 + 1440) % 1440) / 15;
                    idx = (idx + dir + 34) % 34;   // 0 off, 1 to 33: 8:00 PM to 4:00 AM, every 15 minutes
                    SleepTimer.setDaily(getContext(), idx == 0 ? -1 : (1200 + 15 * (idx - 1)) % 1440);
                    break;
                }
                default:
                    return;
            }
            invalidate();
        }

        boolean onKey(KeyEvent e) {
            int code = e.getKeyCode();
            boolean ours = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP
                    || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
            if (!ours || e.getAction() != KeyEvent.ACTION_DOWN) return ours;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    focus = Math.max(0, focus - 1);
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    focus = Math.min(labels.length - 1, focus + 1);
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    change(-1);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    change(1);
                    break;
                default:
                    if (e.getRepeatCount() > 0) return true;
                    Click.playOpen(getContext());
                    if (focus == 0) startActivity(new Intent(getContext(), WallpaperActivity.class));
                    else if (focus == 7) startActivity(new Intent(getContext(), SettingsActivity.class));
                    else if (focus == 8) open(new Intent(Settings.ACTION_SETTINGS));
                    else if (focus == 9) startActivity(new Intent(getContext(), EditorActivity.class));
                    else if (focus == 10) lookForUpdate();
                    else if (focus == 11) startActivity(new Intent(getContext(), CleanActivity.class));
                    else change(1);
                    break;
            }
            invalidate();
            return true;
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (wallpaper != null) {
                r.set(0, 0, w, h);
                c.drawBitmap(wallpaper, null, r, fill);
            } else {
                c.drawColor(0xFF12040A);
            }
            fill.setColor(0xD0080208);
            c.drawRect(0, 0, w, h, fill);
            // on the left: the emblem and the name
            float cx = 520 * k;
            if (logo != null) {
                r.set(cx - 190 * k, 250 * k, cx + 190 * k, 630 * k);
                c.drawBitmap(logo, null, r, fill);
            }
            c.drawText("Tian Demon TV", cx, 720 * k, title);
            c.drawText("Versión " + Updates.installedVersion(getContext()), cx, 770 * k, small);
            // on the right: the rows
            float left = 1000 * k, right = 1800 * k, top = 44 * k, rh = 70 * k, gap = 6 * k;
            for (int i = 0; i < labels.length; i++) {
                float y = top + i * (rh + gap);
                r.set(left, y, right, y + rh);
                boolean on = i == focus;
                fill.setColor(on ? 0xFFFFFFFF : 0x2EFFFFFF);
                c.drawRoundRect(r, 28 * k, 28 * k, fill);
                if (!on) {
                    fill.setStyle(Paint.Style.STROKE);
                    fill.setStrokeWidth(1.6f * k);
                    fill.setColor(0x55FF4A55);
                    c.drawRoundRect(r, 28 * k, 28 * k, fill);
                    fill.setStyle(Paint.Style.FILL);
                }
                label.setColor(on ? 0xFF1A0408 : 0xFFFFFFFF);
                value.setColor(on ? 0xFF5A4A4E : 0xB3FFFFFF);
                c.drawText(labels[i], left + 34 * k, y + rh / 2 + 12 * k, label);
                String v = valueOf(i);
                if (!v.isEmpty()) {
                    value.setTextAlign(Paint.Align.RIGHT);
                    c.drawText(fit(v, value, 320 * k), right - 34 * k, y + rh / 2 + 11 * k, value);
                    value.setTextAlign(Paint.Align.LEFT);
                } else {
                    value.setTextAlign(Paint.Align.RIGHT);
                    c.drawText("›", right - 34 * k, y + rh / 2 + 11 * k, value);
                    value.setTextAlign(Paint.Align.LEFT);
                }
            }
            small.setTextAlign(Paint.Align.LEFT);
            c.drawText("Izquierda y derecha cambian el valor. OK abre.", left, top + labels.length * (rh + gap) + 34 * k, small);
            small.setTextAlign(Paint.Align.CENTER);
        }
    }
}
