package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.View;

import java.io.IOException;
import java.io.InputStream;

/**
 * The editor: a small copy of the home screen where the clock, the music and the row of apps can be moved and made bigger or
 * smaller. Pick one at the bottom, OK to edit it: the arrows move it, OK switches between moving and size (up and down),
 * Back goes back to the list. What you do is saved at once and the home screen shows it as soon as you leave.
 */
public class EditorActivity extends Activity {
    private EditView view;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        overridePendingTransition(0, 0);
        view = new EditView(this);
        setContentView(view);
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (view.onKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    private static final String[] NAMES = {"Reloj y clima", "Música", "Apps", "Botón Tian", "Botón engranaje", "Botón música"};
    private static final String[] KEYS = {"clock", "music", "shelf", "b0", "b1", "b2"};

    private final class EditView extends View {
        private final float k;
        private int chip;          // 0 to 5 the pieces, 6 reset, 7 done
        private boolean editing;
        private int mode;          // 0 move, 1 size, 2 show or hide
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint title = Ui.text(Ui.displaySemibold, 1, 0xFFFFFFFF), body = Ui.text(Ui.medium, 1, 0xCCFFFFFF),
                big = Ui.numbers(Ui.light, 1, 0xFFFFFFFF), small = Ui.text(Ui.semibold, 1, 0xFFFFFFFF);
        private final RectF r = new RectF(), preview = new RectF();
        private Bitmap wallpaper;
        private String hint = "";

        EditView(Context c) {
            super(c);
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            k = dm.widthPixels / 1920f;
            setFocusable(true);
            title.setTextSize(54 * k);
            body.setTextSize(28 * k);
            small.setTextSize(26 * k);
            try (InputStream in = c.getAssets().open("bg-alastor.jpg")) {
                wallpaper = BitmapFactory.decodeStream(in);
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                wallpaper = null;
            }
        }

        private float get(int i, String axis, float def) {
            return Prefs.lay(KEYS[i] + axis, def);
        }

        private void change(int i, String axis, float delta, float min, float max, float def) {
            float v = Math.max(min, Math.min(max, get(i, axis, def) + delta));
            Prefs.setLay(KEYS[i] + axis, v);
        }

        boolean onKey(KeyEvent e) {
            int code = e.getKeyCode();
            boolean ours = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP
                    || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_BACK;
            if (!ours) return false;
            if (e.getAction() != KeyEvent.ACTION_DOWN) return code != KeyEvent.KEYCODE_BACK || editing;
            if (code == KeyEvent.KEYCODE_BACK) {
                if (editing) {
                    editing = false;
                    mode = 0;
                    hint = "";
                    invalidate();
                    return true;
                }
                return false;   // leaves
            }
            if (editing) {
                float step = e.getRepeatCount() > 6 ? 40 : 10;
                boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
                if (ok) {
                    if (e.getRepeatCount() == 0) mode = (mode + 1) % (chip == 2 ? 2 : 3);
                } else if (mode == 2) {
                    Prefs.setLay(KEYS[chip] + "H", Prefs.lay(KEYS[chip] + "H", 0) > 0 ? 0 : 1);
                } else if (mode == 1) {
                    float ds = (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_RIGHT) ? 0.02f : -0.02f;
                    change(chip, "S", ds, 0.5f, 1.8f, 1f);
                } else {
                    float lo = chip == 2 ? -700 : -80, hiX = chip == 2 ? 700 : 1500, loY = chip == 2 ? -600 : -150, hiY = chip == 2 ? 190 : 900;
                    if (chip >= 3) { lo = -1700; hiX = 150; loY = -100; hiY = 900; }
                    if (code == KeyEvent.KEYCODE_DPAD_LEFT) change(chip, "X", -step, lo, hiX, 0);
                    else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) change(chip, "X", step, lo, hiX, 0);
                    else if (code == KeyEvent.KEYCODE_DPAD_UP) change(chip, "Y", -step, loY, hiY, 0);
                    else change(chip, "Y", step, loY, hiY, 0);
                }
                invalidate();
                return true;
            }
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT: chip = Math.max(0, chip - 1); break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: chip = Math.min(7, chip + 1); break;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    break;
                default:
                    if (e.getRepeatCount() > 0) return true;
                    if (chip < 6) {
                        editing = true;
                        mode = 0;
                    } else if (chip == 6) {
                        for (String key : KEYS) {
                            Prefs.setLay(key + "X", 0);
                            Prefs.setLay(key + "Y", 0);
                            Prefs.setLay(key + "S", 1);
                            Prefs.setLay(key + "H", 0);
                        }
                        hint = "Todo volvió a su lugar";
                    } else {
                        finish();
                    }
                    break;
            }
            invalidate();
            return true;
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            c.drawColor(0xFF0B0206);
            c.drawText("Editor", 80 * k, 110 * k, title);
            // the small screen
            float pw = 1180 * k, ph = pw * 9 / 16, px = 80 * k, py = 160 * k;
            preview.set(px, py, px + pw, py + ph);
            float f = pw / 1920f;
            c.save();
            c.clipRect(preview);
            if (wallpaper != null) {
                c.drawBitmap(wallpaper, null, preview, fill);
            } else {
                fill.setColor(0xFF2A0A12);
                c.drawRect(preview, fill);
            }
            fill.setColor(0x99000000);
            c.drawRect(preview, fill);
            drawPieces(c, px, py, f);
            c.restore();
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(3 * k);
            fill.setColor(0x88FFFFFF);
            c.drawRoundRect(preview, 10 * k, 10 * k, fill);
            fill.setStyle(Paint.Style.FILL);

            // the pieces to pick, below
            float cy = py + ph + 60 * k, x = px;
            String[] chips = {"Reloj y clima", "Música", "Apps", "Logo", "Engranaje", "Botón música", "Restablecer", "Listo"};
            for (int i = 0; i < chips.length; i++) {
                float cw = small.measureText(chips[i]) + 60 * k;
                r.set(x, cy, x + cw, cy + 70 * k);
                boolean on = i == chip;
                fill.setColor(on ? (editing ? 0xFFE3122B : 0xFFFFFFFF) : 0x33FFFFFF);
                c.drawRoundRect(r, 35 * k, 35 * k, fill);
                small.setColor(on ? (editing ? 0xFFFFFFFF : 0xFF1A0408) : 0xFFFFFFFF);
                c.drawText(chips[i], x + 30 * k, cy + 46 * k, small);
                x += cw + 18 * k;
            }
            small.setColor(0xFFFFFFFF);
            // the help, at the right
            float hx = px + pw + 60 * k;
            body.setColor(0xCCFFFFFF);
            String[] help;
            if (editing) {
                boolean hidden = Prefs.lay(KEYS[chip] + "H", 0) > 0;
                help = mode == 2 ? new String[] {NAMES[chip], "", hidden ? "Está OCULTO" : "Se ve", "Cualquier flecha lo muestra", "u oculta.", "", "OK: volver a mover", "Atrás: elegir otra pieza"}
                        : mode == 1 ? new String[] {NAMES[chip], "", "Tamaño:", "Arriba o derecha: más grande", "Abajo o izquierda: más chico", "", "OK: siguiente (mostrar u ocultar)", "Atrás: elegir otra pieza"}
                        : new String[] {NAMES[chip], "", "Moviendo:", "Usa las flechas", "(mantén para ir rápido)", "", "OK: cambiar el tamaño", "Atrás: elegir otra pieza"};
            } else {
                help = new String[] {"Elige una pieza", "y pulsa OK.", "", "Mover, cambiar el tamaño", "u ocultar lo que no quieras.", "", hint.isEmpty() ? "Se guarda solo." : hint};
            }
            for (int i = 0; i < help.length; i++) {
                if (i == 0) {
                    small.setTextSize(34 * k);
                    c.drawText(help[i], hx, py + 50 * k, small);
                    small.setTextSize(26 * k);
                } else {
                    c.drawText(help[i], hx, py + (50 + i * 44) * k, body);
                }
            }
        }

        private final Paint pBig = Ui.numbers(Ui.light, 140, 0xFFFFFFFF), pSmall = Ui.text(Ui.semibold, 25, 0xAAFFFFFF),
                pName = Ui.text(Ui.semibold, 28, 0xFFFFFFFF), pTitle = Ui.text(Ui.semibold, 28, 0xFFFFFFFF),
                pArtist = Ui.text(Ui.regular, 22, 0xB3FFFFFF);

        /** The three pieces in their real places on the 1920 by 1080 screen, then moved and scaled by what is saved. */
        private void drawPieces(Canvas c, float px, float py, float f) {
            c.save();
            c.translate(px, py);
            c.scale(f, f);
            // the apps: bottom anchored at the middle
            float sx = get(2, "X", 0), sy = get(2, "Y", 0), ss = get(2, "S", 1);
            c.save();
            c.translate(sx, sy);
            c.scale(ss, ss, 960, 880);
            fadeStart(c, 2);
            for (int i = 0; i < 6; i++) {
                fill.setColor(new int[] {0xFF1B4DB3, 0xFFFFE600, 0xFFFF6A5A, 0xFF1A1A1C, 0xFF1DB954, 0xFF0A0A0A}[i]);
                r.set(270 + i * 238, 690, 270 + i * 238 + 190, 880);
                c.drawRoundRect(r, 42, 42, fill);
            }
            outline(c, 2, 250, 670, 1670, 900);
            fadeEnd(c);
            c.restore();
            // the music pill
            c.save();
            c.translate(get(1, "X", 0), get(1, "Y", 0));
            float ms = get(1, "S", 1);
            c.scale(ms, ms, 80, 44);
            fadeStart(c, 1);
            fill.setColor(0xFF3A0A12);
            r.set(80, 44, 156, 120);
            c.drawRoundRect(r, 16, 16, fill);
            c.drawText("Canción", 174, 76, pTitle);
            c.drawText("Artista", 174, 106, pArtist);
            for (int i = 0; i < 12; i++) {
                fill.setColor(0xFFFFFFFF);
                float hh = 10 + (i * 7) % 30;
                r.set(430 + i * 8, 82 - hh / 2, 434 + i * 8, 82 + hh / 2);
                c.drawRoundRect(r, 2, 2, fill);
            }
            outline(c, 1, 60, 30, 560, 136);
            fadeEnd(c);
            c.restore();
            // the clock and the weather
            c.save();
            c.translate(get(0, "X", 0), get(0, "Y", 0));
            float cs = get(0, "S", 1);
            c.scale(cs, cs, 80, 176);
            fadeStart(c, 0);
            c.drawText("BOO", 80, 206, pSmall);
            c.drawText("2:34", 76, 340, pBig);
            c.drawText("P. M.", 390, 340, pName);
            c.drawText("Soleado", 80, 410, pSmall);
            outline(c, 0, 60, 176, 760, 430);
            fadeEnd(c);
            c.restore();
            // the three round buttons
            String[] syms = {"sparkles", "settings", "speaker"};
            for (int i = 0; i < 3; i++) {
                float cx = 1630 + i * 88, cy = 103;
                c.save();
                c.translate(get(3 + i, "X", 0), get(3 + i, "Y", 0));
                float bs = get(3 + i, "S", 1);
                c.scale(bs, bs, cx, cy);
                fadeStart(c, 3 + i);
                fill.setColor(0xF0160508);
                c.drawCircle(cx, cy, 34, fill);
                fill.setStyle(Paint.Style.STROKE);
                fill.setStrokeWidth(3);
                fill.setColor(0xCCE3122B);
                c.drawCircle(cx, cy, 34, fill);
                fill.setStyle(Paint.Style.FILL);
                Symbols.draw(getContext(), c, syms[i], cx, cy, 30, 0xFFFFFFFF);
                outline(c, 3 + i, cx - 44, cy - 44, cx + 44, cy + 44);
                fadeEnd(c);
                c.restore();
            }
            c.restore();
        }

        private boolean faded;

        /** A hidden piece is drawn faint, so it can be found and shown again. */
        private void fadeStart(Canvas c, int i) {
            faded = Prefs.lay(KEYS[i] + "H", 0) > 0;
            if (faded) c.saveLayerAlpha(-2000, -2000, 4000, 4000, 70);
        }

        private void fadeEnd(Canvas c) {
            if (faded) c.restore();
            faded = false;
        }

        /** The box around a piece: white while picked, red while editing it, a thin line otherwise. */
        private void outline(Canvas c, int i, float l, float t, float rr, float b) {
            boolean on = i == chip && chip < 6;
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(on ? 7 : 3);
            fill.setColor(on ? (editing ? 0xFFE3122B : 0xFFFFFFFF) : 0x44FFFFFF);
            r.set(l, t, rr, b);
            c.drawRoundRect(r, 24, 24, fill);
            fill.setStyle(Paint.Style.FILL);
        }
    }
}
