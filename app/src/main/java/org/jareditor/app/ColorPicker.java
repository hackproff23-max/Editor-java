package org.jareditor.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * Paleta de cores: quadrado de saturacao/brilho + barra de matiz, hex com opacidade,
 * paleta pronta e favoritos (guardados no aparelho).
 */
final class ColorPicker {
    private ColorPicker() {
    }

    interface Result {
        void ok(int argb);
    }

    static final int ACCENT = 0xFF8AB4F8, TXT = 0xFFFFFFFF, LINE = 0xFF5A5A5A;
    static final int THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert;

    /** Abre a paleta com a cor inicial (ARGB); OK devolve a cor escolhida em ARGB. */
    static void show(final Activity a, int initial, final Result cb) {
        final Picker p = new Picker(a, initial);
        ScrollView sv = new ScrollView(a);
        sv.addView(p);
        new AlertDialog.Builder(a, THEME)
            .setView(sv)
            .setPositiveButton("OK", (d, w) -> cb.ok(p.argb()))
            .setNegativeButton("Cancelar", null)
            .show();
    }

    // ------------------------------------------------------------------ painel

    static final class Picker extends LinearLayout {
        final Activity act;
        final float[] hsv = new float[3];
        int alpha = 255;
        final Sv sv;
        final Hue hue;
        final TextView hex;
        final SeekBar seek;
        final TextView tabColor, tabPal, tabFav;
        final LinearLayout paneColor, panePal, paneFav;

        Picker(Activity a, int argb) {
            super(a);
            act = a;
            setOrientation(VERTICAL);
            setPadding(dp(14), dp(8), dp(14), dp(4));

            // ---- abas
            LinearLayout tabs = new LinearLayout(a);
            tabs.setGravity(Gravity.CENTER_VERTICAL);
            tabColor = tab("Cor");
            tabPal = tab("▦");
            tabFav = tab("Favoritos");
            tabColor.setBackground(null);
            tabs.addView(tabColor, new LayoutParams(0, dp(46), 1f));
            LayoutParams lp = new LayoutParams(dp(70), dp(46));
            lp.leftMargin = dp(8);
            tabs.addView(tabPal, lp);
            lp = new LayoutParams(dp(110), dp(46));
            lp.leftMargin = dp(8);
            tabs.addView(tabFav, lp);
            addView(tabs);
            View line = new View(a);
            line.setBackgroundColor(ACCENT);
            LayoutParams ll = new LayoutParams(-1, dp(1));
            ll.topMargin = dp(8);
            ll.bottomMargin = dp(10);
            addView(line, ll);

            // ---- aba Cor
            paneColor = new LinearLayout(a);
            paneColor.setOrientation(VERTICAL);
            LinearLayout sq = new LinearLayout(a);
            sv = new Sv(a);
            hue = new Hue(a);
            sq.addView(sv, new LayoutParams(0, dp(280), 1f));
            lp = new LayoutParams(dp(30), dp(280));
            lp.leftMargin = dp(14);
            sq.addView(hue, lp);
            paneColor.addView(sq);

            LinearLayout r2 = new LinearLayout(a);
            r2.setGravity(Gravity.CENTER_VERTICAL);
            hex = new TextView(a);
            hex.setGravity(Gravity.CENTER);
            hex.setTextSize(17);
            hex.setOnClickListener(v -> editHex());
            r2.addView(hex, new LayoutParams(0, dp(48), 1f));
            TextView star = tab("★");
            star.setTextSize(22);
            star.setOnClickListener(v -> addFav());
            lp = new LayoutParams(dp(52), dp(48));
            lp.leftMargin = dp(8);
            r2.addView(star, lp);
            TextView plus = tab("+");
            plus.setTextSize(26);
            plus.setOnClickListener(v -> editHex());
            lp = new LayoutParams(dp(52), dp(48));
            lp.leftMargin = dp(8);
            r2.addView(plus, lp);
            LayoutParams l2 = new LayoutParams(-1, -2);
            l2.topMargin = dp(12);
            paneColor.addView(r2, l2);

            LinearLayout r3 = new LinearLayout(a);
            r3.setGravity(Gravity.CENTER_VERTICAL);
            TextView op = tab("Opacidade");
            op.setTextSize(16);
            op.setPadding(dp(14), 0, dp(14), 0);
            r3.addView(op, new LayoutParams(-2, dp(46)));
            seek = new SeekBar(a);
            seek.setMax(255);
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar b, int p, boolean user) {
                    if (user) {
                        alpha = p;
                        refresh();
                    }
                }

                public void onStartTrackingTouch(SeekBar b) {
                }

                public void onStopTrackingTouch(SeekBar b) {
                }
            });
            lp = new LayoutParams(0, -2, 1f);
            lp.leftMargin = dp(10);
            r3.addView(seek, lp);
            LayoutParams l3 = new LayoutParams(-1, -2);
            l3.topMargin = dp(12);
            paneColor.addView(r3, l3);

            // ---- abas Paleta e Favoritos
            panePal = new LinearLayout(a);
            panePal.setOrientation(VERTICAL);
            panePal.addView(grid(presets(), false));
            paneFav = new LinearLayout(a);
            paneFav.setOrientation(VERTICAL);

            FrameLayout area = new FrameLayout(a);
            area.addView(paneColor);
            area.addView(panePal);
            area.addView(paneFav);
            addView(area);

            sv.l = (s, v) -> {
                hsv[1] = s;
                hsv[2] = v;
                refresh();
            };
            hue.l = h -> {
                hsv[0] = h;
                refresh();
            };
            tabColor.setOnClickListener(v -> select(0));
            tabPal.setOnClickListener(v -> select(1));
            tabFav.setOnClickListener(v -> select(2));

            setArgb(argb);
            select(0);
        }

        int dp(float v) {
            return Math.round(v * getResources().getDisplayMetrics().density);
        }

        GradientDrawable box(int fill, int stroke) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(fill);
            g.setStroke(dp(1), stroke);
            g.setCornerRadius(dp(6));
            return g;
        }

        TextView tab(String t) {
            TextView v = new TextView(act);
            v.setText(t);
            v.setTextColor(TXT);
            v.setTextSize(16);
            v.setGravity(Gravity.CENTER);
            v.setBackground(box(0xFF2B2B2B, LINE));
            return v;
        }

        void select(int i) {
            paneColor.setVisibility(i == 0 ? VISIBLE : GONE);
            panePal.setVisibility(i == 1 ? VISIBLE : GONE);
            paneFav.setVisibility(i == 2 ? VISIBLE : GONE);
            tabColor.setTextColor(i == 0 ? ACCENT : TXT);
            tabPal.setTextColor(i == 1 ? ACCENT : TXT);
            tabFav.setTextColor(i == 2 ? ACCENT : TXT);
            if (i == 2) {
                buildFavs();
            }
        }

        int rgb() {
            return Color.HSVToColor(hsv);
        }

        int argb() {
            return Color.HSVToColor(alpha, hsv);
        }

        void setArgb(int c) {
            alpha = Color.alpha(c);
            Color.colorToHSV(c, hsv);
            refresh();
        }

        void refresh() {
            sv.set(hsv[0], hsv[1], hsv[2]);
            hue.set(hsv[0]);
            int c = rgb();
            hex.setText(String.format("#%06X - %d", c & 0xFFFFFF, alpha));
            int lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
            hex.setTextColor(lum > 150 ? 0xFF000000 : 0xFFFFFFFF);
            hex.setBackground(box(c, 0xFF888888));
            if (seek.getProgress() != alpha) {
                seek.setProgress(alpha);
            }
        }

        /** Escolhe uma cor das listas; mantem a opacidade atual quando a cor e opaca (paleta). */
        void choose(int c, boolean keepAlpha) {
            setArgb(keepAlpha ? (alpha << 24) | (c & 0xFFFFFF) : c);
            select(0);
        }

        void editHex() {
            final EditText e = new EditText(act);
            e.setSingleLine(true);
            e.setHint("RRGGBB ou AARRGGBB");
            e.setText(String.format("%06X", rgb() & 0xFFFFFF));
            e.selectAll();
            new AlertDialog.Builder(act, THEME).setTitle("Cor em hex").setView(e)
                .setPositiveButton("OK", (d, w) -> {
                    try {
                        String s = e.getText().toString().trim().replace("#", "");
                        if (s.length() != 6 && s.length() != 8) {
                            throw new IllegalArgumentException();
                        }
                        int c = Color.parseColor("#" + s);
                        if (s.length() == 6) {
                            c = (alpha << 24) | (c & 0xFFFFFF);
                        }
                        setArgb(c);
                    } catch (Exception ex) {
                        Toast.makeText(act, "Cor inválida", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancelar", null).show();
        }

        // ---- paleta pronta: as 16 cores do editor + variações
        int[] presets() {
            ArrayList<Integer> l = new ArrayList<>();
            for (int c : MainActivity.PAL) {
                l.add(c);
            }
            float[] hues = { 0, 30, 60, 120, 180, 210, 270, 320 };
            float[][] sv2 = { { 1f, 1f }, { 0.6f, 1f }, { 1f, 0.65f }, { 0.35f, 1f }, { 0.8f, 0.35f } };
            for (float[] k : sv2) {
                for (float h : hues) {
                    l.add(Color.HSVToColor(new float[] { h, k[0], k[1] }));
                }
            }
            int[] out = new int[l.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = l.get(i);
            }
            return out;
        }

        /** Grade de amostras, 8 por linha. */
        LinearLayout grid(int[] colors, final boolean removable) {
            LinearLayout g = new LinearLayout(act);
            g.setOrientation(VERTICAL);
            for (int i = 0; i < colors.length; i += 8) {
                LinearLayout row = new LinearLayout(act);
                for (int k = 0; k < 8; k++) {
                    LayoutParams lp = new LayoutParams(0, -2, 1f);
                    lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                    if (i + k < colors.length) {
                        final int c = colors[i + k];
                        Sw s = new Sw(act, c);
                        if (removable) {
                            s.setOnClickListener(v -> choose(c, false));
                            s.setOnLongClickListener(v -> {
                                removeFav(c);
                                return true;
                            });
                        } else {
                            s.setOnClickListener(v -> choose(c, true));
                        }
                        row.addView(s, lp);
                    } else {
                        row.addView(new View(act), lp);
                    }
                }
                g.addView(row);
            }
            return g;
        }

        // ---- favoritos (guardados no aparelho)
        SharedPreferences prefs() {
            return act.getSharedPreferences("cores", Context.MODE_PRIVATE);
        }

        ArrayList<Integer> favs() {
            ArrayList<Integer> l = new ArrayList<>();
            String s = prefs().getString("fav", "");
            for (String p : s.split(",")) {
                try {
                    if (!p.isEmpty()) {
                        l.add(Integer.parseInt(p));
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            return l;
        }

        void saveFavs(ArrayList<Integer> l) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < l.size(); i++) {
                sb.append(i > 0 ? "," : "").append(l.get(i));
            }
            prefs().edit().putString("fav", sb.toString()).apply();
        }

        void addFav() {
            ArrayList<Integer> l = favs();
            Integer c = argb();
            l.remove(c);
            l.add(0, c);
            while (l.size() > 48) {
                l.remove(l.size() - 1);
            }
            saveFavs(l);
            Toast.makeText(act, "Cor salva nos favoritos", Toast.LENGTH_SHORT).show();
        }

        void removeFav(int c) {
            ArrayList<Integer> l = favs();
            l.remove(Integer.valueOf(c));
            saveFavs(l);
            buildFavs();
            Toast.makeText(act, "Favorito removido", Toast.LENGTH_SHORT).show();
        }

        void buildFavs() {
            paneFav.removeAllViews();
            ArrayList<Integer> l = favs();
            if (l.isEmpty()) {
                TextView t = new TextView(act);
                t.setText("Nenhum favorito ainda.\nToque na ★ para salvar a cor atual.");
                t.setTextColor(TXT);
                t.setPadding(0, dp(20), 0, dp(20));
                paneFav.addView(t);
                return;
            }
            int[] a = new int[l.size()];
            for (int i = 0; i < a.length; i++) {
                a[i] = l.get(i);
            }
            paneFav.addView(grid(a, true));
            TextView h = new TextView(act);
            h.setText("Segure uma cor para remover");
            h.setTextColor(0xFF9A9A9A);
            h.setTextSize(12);
            h.setPadding(0, dp(8), 0, 0);
            paneFav.addView(h);
        }
    }

    // ------------------------------------------------------------------ quadrado saturacao x brilho

    static final class Sv extends View {
        interface L {
            void on(float s, float v);
        }

        L l;
        float hue, s = 1, v = 1;
        final Paint fill = new Paint();
        final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        Shader sh;
        float shHue = -1;
        int shW, shH;

        Sv(Context c) {
            super(c);
            // ComposeShader de dois degradês só funciona acelerado a partir do Android 9
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            ring.setStyle(Paint.Style.STROKE);
        }

        void set(float h, float s, float v) {
            hue = h;
            this.s = s;
            this.v = v;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            if (sh == null || shHue != hue || shW != w || shH != h) {
                Shader val = new LinearGradient(0, 0, 0, h, 0xFFFFFFFF, 0xFF000000, Shader.TileMode.CLAMP);
                Shader sat = new LinearGradient(0, 0, w, 0, 0xFFFFFFFF,
                    Color.HSVToColor(new float[] { hue, 1, 1 }), Shader.TileMode.CLAMP);
                sh = new ComposeShader(val, sat, PorterDuff.Mode.MULTIPLY);
                shHue = hue;
                shW = w;
                shH = h;
            }
            fill.setShader(sh);
            c.drawRect(0, 0, w, h, fill);
            float d = getResources().getDisplayMetrics().density;
            float x = s * w, y = (1 - v) * h;
            ring.setStrokeWidth(3.5f * d);
            ring.setColor(0xFF000000);
            c.drawCircle(x, y, 10 * d, ring);
            ring.setStrokeWidth(2 * d);
            ring.setColor(0xFFFFFFFF);
            c.drawCircle(x, y, 10 * d, ring);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                s = Math.max(0f, Math.min(1f, e.getX() / getWidth()));
                v = 1f - Math.max(0f, Math.min(1f, e.getY() / getHeight()));
                invalidate();
                if (l != null) {
                    l.on(s, v);
                }
            }
            return true;
        }
    }

    // ------------------------------------------------------------------ barra de matiz

    static final class Hue extends View {
        interface L {
            void on(float hue);
        }

        L l;
        float hue;
        final Paint fill = new Paint();
        final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
        Shader sh;
        int shH;

        Hue(Context c) {
            super(c);
        }

        void set(float h) {
            hue = h;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            if (sh == null || shH != h) {
                sh = new LinearGradient(0, 0, 0, h, new int[] { 0xFFFF0000, 0xFFFF00FF, 0xFF0000FF, 0xFF00FFFF,
                    0xFF00FF00, 0xFFFFFF00, 0xFFFF0000 }, null, Shader.TileMode.CLAMP);
                shH = h;
            }
            fill.setShader(sh);
            c.drawRect(0, 0, w, h, fill);
            float d = getResources().getDisplayMetrics().density;
            float y = (1f - hue / 360f) * h;
            float top = Math.max(1.5f * d, Math.min(h - 15.5f * d, y - 7 * d));
            RectF r = new RectF(1.5f * d, top, w - 1.5f * d, top + 14 * d);
            mark.setStyle(Paint.Style.FILL);
            mark.setColor(Color.HSVToColor(new float[] { hue, 1, 1 }));
            c.drawRect(r, mark);
            mark.setStyle(Paint.Style.STROKE);
            mark.setStrokeWidth(2.5f * d);
            mark.setColor(0xFF000000);
            c.drawRect(r, mark);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                float t = Math.max(0f, Math.min(1f, e.getY() / getHeight()));
                hue = Math.min(359.99f, 360f * (1f - t));
                invalidate();
                if (l != null) {
                    l.on(hue);
                }
            }
            return true;
        }
    }

    // ------------------------------------------------------------------ amostra de cor quadrada

    static final class Sw extends View {
        final int color;
        final Paint p = new Paint();

        Sw(Context c, int color) {
            super(c);
            this.color = color;
        }

        @Override
        protected void onMeasure(int wm, int hm) {
            int w = View.getDefaultSize(0, wm);
            setMeasuredDimension(w, w);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float d = getResources().getDisplayMetrics().density;
            float cell = 6 * d;
            for (int y = 0; y * cell < h; y++) {
                for (int x = 0; x * cell < w; x++) {
                    p.setColor(((x + y) & 1) == 0 ? 0xFFBBBBBB : 0xFF888888);
                    c.drawRect(x * cell, y * cell, Math.min(w, (x + 1) * cell), Math.min(h, (y + 1) * cell), p);
                }
            }
            p.setColor(color);
            c.drawRect(0, 0, w, h, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(d);
            p.setColor(0xFF666666);
            c.drawRect(0, 0, w, h, p);
            p.setStyle(Paint.Style.FILL);
        }
    }
}
