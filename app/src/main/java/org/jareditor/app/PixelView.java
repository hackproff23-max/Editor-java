package org.jareditor.app;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.ArrayList;

public class PixelView extends View {
    public interface Info { void on(int x, int y, int c); }

    public Bitmap bmp;
    public int tool = 0; // 0 lapis, 1 borracha, 2 gota, 3 balde, 4 mover
    public int color = 0xFFFF0000, brush = 1;
    public boolean grid = true;
    public Runnable onPick;
    public Info info;
    float scale = 1, tx, ty, mx, my, pmx, pmy;
    boolean fitted, stroke, moved, multi;
    int lx, ly;
    final Paint p = new Paint(), gp = new Paint(), cp = new Paint(), op = new Paint();
    final ArrayList<Bitmap> undo = new ArrayList<>();
    final ScaleGestureDetector sg;

    // ---- PNG importada (camada flutuante, encaixada na grade de pixels) ----
    public boolean ovActive;
    public Runnable onOverlay;
    Bitmap ovSrc, ov;
    int ovX, ovY, gx, gy;

    public PixelView(Context c, Bitmap b) {
        super(c);
        bmp = b;
        gp.setColor(0x66000000); p.setFilterBitmap(false); p.setAntiAlias(false); p.setDither(false);
        gp.setStrokeWidth(0);
        op.setStyle(Paint.Style.STROKE);
        op.setColor(0xFFFFFF00);
        op.setStrokeWidth(3);
        Bitmap ck = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        Canvas cc = new Canvas(ck);
        cc.drawColor(0xFFBBBBBB);
        Paint q = new Paint();
        q.setColor(0xFF999999);
        cc.drawRect(0, 0, 8, 8, q);
        cc.drawRect(8, 8, 16, 16, q);
        cp.setShader(new BitmapShader(ck, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        sg = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                zoomAt(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
    }

    void refit() {
        int w = getWidth(), h = getHeight();
        if (w <= 0) { fitted = false; return; }
        float r = Math.min(w / (float) bmp.getWidth(), h / (float) bmp.getHeight());
        scale = r >= 1 ? (float) Math.floor(r) : r;
        tx = (w - bmp.getWidth() * scale) / 2;
        ty = (h - bmp.getHeight() * scale) / 2;
        fitted = true;
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (!fitted) refit();
    }

    @Override protected void onDraw(Canvas c) {
        int bw = bmp.getWidth(), bh = bmp.getHeight();
        c.drawColor(0xFF303030);
        c.drawRect(tx, ty, tx + bw * scale, ty + bh * scale, cp);
        c.save();
        c.translate(tx, ty);
        c.scale(scale, scale);
        c.drawBitmap(bmp, 0, 0, p);
        c.restore();
        if (ovActive && ov != null) {
            c.save();
            c.translate(tx, ty);
            c.scale(scale, scale);
            c.drawBitmap(ov, ovX, ovY, p);
            c.restore();
            c.drawRect(tx + ovX * scale, ty + ovY * scale,
                tx + (ovX + ov.getWidth()) * scale, ty + (ovY + ov.getHeight()) * scale, op);
        }
        if (grid && scale >= 6) {
            int x0 = Math.max(0, (int) Math.floor(-tx / scale));
            int x1 = Math.min(bw, (int) Math.ceil((getWidth() - tx) / scale));
            int y0 = Math.max(0, (int) Math.floor(-ty / scale));
            int y1 = Math.min(bh, (int) Math.ceil((getHeight() - ty) / scale));
            float top = Math.max(0, ty), bot = Math.min(getHeight(), ty + bh * scale);
            float left = Math.max(0, tx), right = Math.min(getWidth(), tx + bw * scale);
            for (int x = x0; x <= x1; x++) c.drawLine(tx + x * scale, top, tx + x * scale, bot, gp);
            for (int y = y0; y <= y1; y++) c.drawLine(left, ty + y * scale, right, ty + y * scale, gp);
        }
    }

    public void zoom(float f) { zoomAt(f, getWidth() / 2f, getHeight() / 2f); }

    void zoomAt(float f, float fx, float fy) {
        float s2 = Math.max(0.25f, Math.min(96f, scale * f));
        f = s2 / scale;
        tx = fx - (fx - tx) * f;
        ty = fy - (fy - ty) * f;
        scale = s2;
        invalidate();
    }

    void pushUndo() {
        undo.add(bmp.copy(Bitmap.Config.ARGB_8888, true));
        if (undo.size() > 10) undo.remove(0);
    }

    public void undo() {
        if (ovActive) { endOverlay(); return; }
        if (undo.isEmpty()) return;
        Bitmap b = undo.remove(undo.size() - 1);
        boolean diff = b.getWidth() != bmp.getWidth() || b.getHeight() != bmp.getHeight();
        bmp = b;
        if (diff) refit();
        invalidate();
    }

    void setBmp(Bitmap b) {
        bmp = (b.isMutable() && b.getConfig() == Bitmap.Config.ARGB_8888) ? b : b.copy(Bitmap.Config.ARGB_8888, true);
        refit();
        invalidate();
    }

    // ---------- importar PNG: camada flutuante presa a grade de pixels ----------
    public void beginOverlay(Bitmap src) {
        ovSrc = src;
        int bw = bmp.getWidth(), bh = bmp.getHeight();
        int w = src.getWidth(), h = src.getHeight();
        if (w > bw || h > bh) {
            float r = Math.min(bw / (float) w, bh / (float) h);
            w = Math.max(1, Math.round(w * r));
            h = Math.max(1, Math.round(h * r));
        }
        makeOv(w, h);
        ovX = (bw - ov.getWidth()) / 2;
        ovY = (bh - ov.getHeight()) / 2;
        ovActive = true;
        if (onOverlay != null) onOverlay.run();
        invalidate();
    }

    void makeOv(int w, int h) {
        if (w == ovSrc.getWidth() && h == ovSrc.getHeight()) ov = ovSrc;
        else ov = Bitmap.createScaledBitmap(ovSrc, w, h, false); // sem suavizar: pixels nitidos
    }

    public void overlaySize(float f) {
        if (!ovActive || ov == null) return;
        int w = ov.getWidth(), h = ov.getHeight();
        int nw = Math.round(w * f);
        if (nw == w) nw = w + (f > 1 ? 1 : -1);
        nw = Math.max(1, nw);
        int nh = Math.max(1, Math.round(nw * (float) ovSrc.getHeight() / ovSrc.getWidth()));
        if (nw > 4096 || nh > 4096) return;
        ovX += (w - nw) / 2;
        ovY += (h - nh) / 2;
        makeOv(nw, nh);
        invalidate();
    }

    public void commitOverlay() {
        if (!ovActive || ov == null) return;
        pushUndo();
        new Canvas(bmp).drawBitmap(ov, ovX, ovY, p);
        endOverlay();
    }

    public void cancelOverlay() { endOverlay(); }

    void endOverlay() {
        ovActive = false;
        ov = null;
        ovSrc = null;
        if (onOverlay != null) onOverlay.run();
        invalidate();
    }

    static int cl(float v) { return v < 0 ? 0 : v > 255 ? 255 : (int) (v + 0.5f); }

    // mode 0: brilho(a)/contraste(b); 1: saturacao(a); 2: cinza; 3: inverter; 4: posterizar(a niveis)
    public void adjust(int mode, int a, int b) {
        pushUndo();
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        float cf = (100 + b) / 100f, sat = 1 + a / 100f;
        int lv = Math.max(2, a);
        float st = 255f / (lv - 1);
        for (int i = 0; i < px.length; i++) {
            int c = px[i], al = c >>> 24;
            float r = (c >> 16) & 255, g = (c >> 8) & 255, bl = c & 255;
            float gr = 0.299f * r + 0.587f * g + 0.114f * bl;
            switch (mode) {
                case 0:
                    r = (r - 128) * cf + 128 + a; g = (g - 128) * cf + 128 + a; bl = (bl - 128) * cf + 128 + a;
                    break;
                case 1:
                    r = gr + (r - gr) * sat; g = gr + (g - gr) * sat; bl = gr + (bl - gr) * sat;
                    break;
                case 2:
                    r = gr; g = gr; bl = gr;
                    break;
                case 3:
                    r = 255 - r; g = 255 - g; bl = 255 - bl;
                    break;
                case 4:
                    r = Math.round(r / st) * st; g = Math.round(g / st) * st; bl = Math.round(bl / st) * st;
                    break;
            }
            px[i] = (al << 24) | (cl(r) << 16) | (cl(g) << 8) | cl(bl);
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h);
        invalidate();
    }

    public void rotate(boolean right) {
        pushUndo();
        Matrix m = new Matrix();
        m.postRotate(right ? 90 : -90);
        setBmp(Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, false));
    }

    public void flip(boolean horizontal) {
        pushUndo();
        Matrix m = new Matrix();
        m.setScale(horizontal ? -1 : 1, horizontal ? 1 : -1);
        setBmp(Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, false));
    }

    public boolean resize(int nw, int nh, boolean smooth) {
        if (nw < 1 || nh < 1 || nw > 4096 || nh > 4096 || (long) nw * nh > 8000000L) return false;
        pushUndo();
        setBmp(Bitmap.createScaledBitmap(bmp, nw, nh, smooth));
        return true;
    }

    boolean in(int x, int y) { return x >= 0 && y >= 0 && x < bmp.getWidth() && y < bmp.getHeight(); }

    void apply(int x, int y) {
        if (tool == 0 || tool == 1) {
            int o = (brush - 1) / 2;
            for (int j = 0; j < brush; j++)
                for (int i = 0; i < brush; i++) {
                    int xx = x - o + i, yy = y - o + j;
                    if (in(xx, yy)) bmp.setPixel(xx, yy, tool == 0 ? color : 0);
                }
            return;
        }
        if (!in(x, y)) return;
        if (tool == 2) { color = bmp.getPixel(x, y); if (onPick != null) onPick.run(); }
        else if (tool == 3) fill(x, y);
    }

    void line(int x0, int y0, int x1, int y1) {
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, err = dx + dy;
        while (true) {
            apply(x0, y0);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    void fill(int sx, int sy) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] a = new int[w * h];
        bmp.getPixels(a, 0, w, 0, 0, w, h);
        int target = a[sy * w + sx];
        if (target == color) return;
        int[] st = new int[w * h];
        int sp = 0;
        a[sy * w + sx] = color;
        st[sp++] = sy * w + sx;
        while (sp > 0) {
            int q = st[--sp], x = q % w, y = q / w;
            if (x > 0 && a[q - 1] == target) { a[q - 1] = color; st[sp++] = q - 1; }
            if (x < w - 1 && a[q + 1] == target) { a[q + 1] = color; st[sp++] = q + 1; }
            if (y > 0 && a[q - w] == target) { a[q - w] = color; st[sp++] = q - w; }
            if (y < h - 1 && a[q + w] == target) { a[q + w] = color; st[sp++] = q + w; }
        }
        bmp.setPixels(a, 0, w, 0, 0, w, h);
    }

    float avgX(MotionEvent e, int skip) {
        float s = 0; int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) if (i != skip) { s += e.getX(i); n++; }
        return n == 0 ? 0 : s / n;
    }

    float avgY(MotionEvent e, int skip) {
        float s = 0; int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) if (i != skip) { s += e.getY(i); n++; }
        return n == 0 ? 0 : s / n;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        sg.onTouchEvent(e);
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_POINTER_DOWN) {
            if (stroke && !moved) undo();
            stroke = false;
            multi = true;
            pmx = avgX(e, -1); pmy = avgY(e, -1);
            return true;
        }
        if (multi) {
            if (a == MotionEvent.ACTION_MOVE && e.getPointerCount() > 1) {
                float cx = avgX(e, -1), cy = avgY(e, -1);
                tx += cx - pmx; ty += cy - pmy;
                pmx = cx; pmy = cy;
                invalidate();
            } else if (a == MotionEvent.ACTION_POINTER_UP) {
                pmx = avgX(e, e.getActionIndex()); pmy = avgY(e, e.getActionIndex());
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                multi = false;
            }
            return true;
        }
        float x = e.getX(), y = e.getY();
        if (ovActive) {
            int qx = (int) Math.floor((x - tx) / scale), qy = (int) Math.floor((y - ty) / scale);
            if (a == MotionEvent.ACTION_DOWN) { gx = qx - ovX; gy = qy - ovY; }
            else if (a == MotionEvent.ACTION_MOVE) { ovX = qx - gx; ovY = qy - gy; invalidate(); }
            return true;
        }
        if (tool == 4) {
            if (a == MotionEvent.ACTION_DOWN) { mx = x; my = y; }
            else if (a == MotionEvent.ACTION_MOVE) { tx += x - mx; ty += y - my; mx = x; my = y; invalidate(); }
            return true;
        }
        int px = (int) Math.floor((x - tx) / scale), py = (int) Math.floor((y - ty) / scale);
        if (a == MotionEvent.ACTION_DOWN) {
            if (tool == 0 || tool == 1 || tool == 3) pushUndo();
            stroke = true; moved = false;
            lx = px; ly = py;
            apply(px, py);
        } else if (a == MotionEvent.ACTION_MOVE && (tool == 0 || tool == 1)) {
            moved = true;
            line(lx, ly, px, py);
            lx = px; ly = py;
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            stroke = false;
        }
        if (info != null && in(px, py)) info.on(px, py, bmp.getPixel(px, py));
        invalidate();
        return true;
    }
}
