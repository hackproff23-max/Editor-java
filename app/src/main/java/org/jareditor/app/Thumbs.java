package org.jareditor.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.LruCache;
import android.view.Gravity;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;

/**
 * Miniaturas das texturas ao lado do nome na lista de arquivos e substituicao de imagem sem borrar.
 * Tudo aqui usa vizinho mais proximo (sem filtro), entao pixel art continua nitido.
 */
final class Thumbs {
    private Thumbs() {
    }

    interface Done {
        void ok(byte[] result);
    }

    private static final Bitmap NONE = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(300);

    // ---------------- miniaturas na lista ----------------

    /** Poe a miniatura (se o arquivo for imagem) a esquerda do nome. Nao-imagens ficam com o mesmo espaco vazio. */
    static void apply(Activity a, TextView t, String name, byte[] data, MainActivity.Scan sc) {
        float d = a.getResources().getDisplayMetrics().density;
        int size = Math.round(56 * d);
        Bitmap bm = null;
        if (sc != null && sc.isImage() && data != null) {
            bm = thumb(name, data, sc);
        }
        t.setCompoundDrawables(new PixelDrawable(bm, size), null, null, null);
        t.setCompoundDrawablePadding(Math.round(10 * d));
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinHeight(size + Math.round(8 * d));
    }

    private static Bitmap thumb(String name, byte[] data, MainActivity.Scan sc) {
        // a chave muda quando o arquivo e trocado/editado (novo array de bytes)
        String key = name + "#" + System.identityHashCode(data) + "#" + data.length;
        Bitmap b = CACHE.get(key);
        if (b == null) {
            b = decode(data, sc);
            CACHE.put(key, b == null ? NONE : b);
        }
        return b == NONE ? null : b;
    }

    private static Bitmap decode(byte[] d, MainActivity.Scan sc) {
        try {
            if (sc.packed) { // PNG empacotado (.mid da engine CWA): vira PNG de verdade antes de decodificar
                byte[] p = PackedImg.toPng(d);
                if (p == null) {
                    return null;
                }
                d = p;
            }
            int off = 0, len = d.length;
            if (sc.type == null) { // pacote com PNG embutido: mostra o primeiro
                ArrayList<int[]> f = MainActivity.findPng(d, 1);
                if (f.isEmpty()) {
                    return null;
                }
                off = f.get(0)[0];
                len = f.get(0)[1];
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(d, off, len, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) {
                return null;
            }
            int s = 1;
            while (o.outWidth / (s * 2) >= 128 || o.outHeight / (s * 2) >= 128) {
                s *= 2;
            }
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            o2.inScaled = false;
            return BitmapFactory.decodeByteArray(d, off, len, o2);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Desenha a imagem encaixada no quadrado, em escala inteira quando possivel, sem suavizar. */
    private static final class PixelDrawable extends Drawable {
        private final Bitmap bm;
        private final int size;
        private final Paint img = new Paint();
        private final Paint bg = new Paint();

        PixelDrawable(Bitmap bm, int size) {
            this.bm = bm;
            this.size = size;
            img.setFilterBitmap(false);
            img.setAntiAlias(false);
            img.setDither(false);
            setBounds(0, 0, size, size);
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }

        @Override
        public void draw(Canvas c) {
            if (bm == null || bm.isRecycled()) {
                return;
            }
            Rect r = getBounds();
            float s = Math.min(r.width() / (float) bm.getWidth(), r.height() / (float) bm.getHeight());
            if (s >= 1) {
                s = (float) Math.floor(s); // cada pixel da textura vira um bloco inteiro de pixels da tela
            }
            int w = Math.max(1, Math.round(bm.getWidth() * s));
            int h = Math.max(1, Math.round(bm.getHeight() * s));
            int l = r.left + (r.width() - w) / 2;
            int t = r.top + (r.height() - h) / 2;
            Rect dst = new Rect(l, t, l + w, t + h);
            // xadrez para a transparencia aparecer
            c.save();
            c.clipRect(dst);
            int cell = Math.max(4, size / 8);
            for (int y = 0; y * cell < h; y++) {
                for (int x = 0; x * cell < w; x++) {
                    bg.setColor(((x + y) & 1) == 0 ? 0xFFBBBBBB : 0xFF999999);
                    c.drawRect(l + x * cell, t + y * cell, l + (x + 1) * cell, t + (y + 1) * cell, bg);
                }
            }
            c.restore();
            c.drawBitmap(bm, null, dst, img);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // ---------------- substituir imagem ----------------

    /** {largura, altura} se os bytes abrem como imagem; senao null. */
    private static int[] size(byte[] d) {
        if (d == null || d.length < 8) {
            return null;
        }
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(d, 0, d.length, o);
            return o.outWidth > 0 && o.outHeight > 0 ? new int[] { o.outWidth, o.outHeight } : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Substituicao de arquivo. Os bytes novos entram exatamente como estao (sem recodificar).
     * Se os dois sao imagens de tamanhos diferentes, pergunta se quer ajustar ao tamanho original
     * com vizinho mais proximo (pixel nitido, sem suavizar).
     */
    static void replace(final Activity a, final String name, final byte[] old, final byte[] nb, final Done done) {
        final int[] o = size(old);
        final int[] n = size(nb);
        if (o == null || n == null || (o[0] == n[0] && o[1] == n[1])) {
            done.ok(nb);
            return;
        }
        new AlertDialog.Builder(a).setTitle("Tamanho diferente")
            .setMessage(name + "\nOriginal: " + o[0] + "x" + o[1] + "\nNova: " + n[0] + "x" + n[1]
                + "\n\nAjustar a nova imagem ao tamanho original? Usa pixel nítido (sem suavizar).")
            .setPositiveButton("Ajustar ao original", (d, w) -> {
                byte[] r = fit(nb, o[0], o[1], MainActivity.imgType(old));
                done.ok(r != null ? r : nb);
            })
            .setNegativeButton("Manter tamanho novo", (d, w) -> done.ok(nb))
            .setNeutralButton("Cancelar", null)
            .show();
    }

    /** Redimensiona com vizinho mais proximo (filter = false) e grava em PNG (ou JPG se o original era JPG). */
    private static byte[] fit(byte[] nb, int w, int h, String type) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            Bitmap src = BitmapFactory.decodeByteArray(nb, 0, nb.length, o);
            if (src == null) {
                return null;
            }
            Bitmap dst = Bitmap.createScaledBitmap(src, w, h, false);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if ("jpg".equals(type)) {
                dst.compress(Bitmap.CompressFormat.JPEG, 100, out);
            } else {
                dst.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }
}
