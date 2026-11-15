package org.jareditor.app;

import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Imagens "PNG empacotado" da engine CWA (ex: data/img/img_*.mid do Pet Kingdom 6).
 * E um PNG sem assinatura, sem CRC e sem nomes de chunk:
 *   largura(4) altura(4) bitdepth(1) colortype(1) compressao(1) filtro(1)
 *   colortype 3: tamanho da paleta(2) + paleta RGB + [ "tRNS" + n(1) + indice(1) ] + tamanho do IDAT(4) + IDAT
 *   colortype 6: tamanho do IDAT(2) + IDAT
 * Aqui converte para PNG de verdade (para ver/editar) e de volta para esse formato (para salvar no jar).
 */
final class PackedImg {
    private PackedImg() {
    }

    private static int u16(byte[] d, int p) {
        return ((d[p] & 255) << 8) | (d[p + 1] & 255);
    }

    private static long u32(byte[] d, int p) {
        return ((long) (d[p] & 255) << 24) | ((d[p + 1] & 255) << 16) | ((d[p + 2] & 255) << 8) | (d[p + 3] & 255);
    }

    /** {w, h, colortype, palOff, palLen, trN, trIdx (-1 = sem), idatOff, idatLen} ou null se nao e esse formato. */
    private static int[] parse(byte[] d) {
        if (d == null || d.length < 20) {
            return null;
        }
        long w = u32(d, 0), h = u32(d, 4);
        if (w < 1 || h < 1 || w > 4096 || h > 4096) {
            return null;
        }
        int bd = d[8] & 255, ct = d[9] & 255;
        if (bd != 8 || (ct != 3 && ct != 6) || d[10] != 0 || d[11] != 0) {
            return null;
        }
        int p = 12, palOff = 0, palLen = 0, trN = 0, trIdx = -1;
        long len;
        if (ct == 3) {
            palLen = u16(d, p);
            p += 2;
            if (palLen < 3 || palLen % 3 != 0 || palLen > 768 || p + palLen + 4 > d.length) {
                return null;
            }
            palOff = p;
            p += palLen;
            if (p + 6 <= d.length && d[p] == 't' && d[p + 1] == 'R' && d[p + 2] == 'N' && d[p + 3] == 'S') {
                trN = d[p + 4] & 255;
                trIdx = d[p + 5] & 255;
                p += 6;
            }
            if (p + 4 > d.length) {
                return null;
            }
            len = u32(d, p);
            p += 4;
        } else {
            len = u16(d, p);
            p += 2;
        }
        if (len < 8 || p + len != d.length) {
            return null;
        }
        int cmf = d[p] & 255, flg = d[p + 1] & 255;
        if ((cmf & 15) != 8 || ((cmf << 8) | flg) % 31 != 0) {
            return null;
        }
        return new int[] { (int) w, (int) h, ct, palOff, palLen, trN, trIdx, p, (int) len };
    }

    static boolean isPacked(byte[] d) {
        return parse(d) != null;
    }

    /** {largura, altura} ou null. */
    static int[] size(byte[] d) {
        int[] f = parse(d);
        return f == null ? null : new int[] { f[0], f[1] };
    }

    // ---------------- empacotado -> PNG ----------------

    static byte[] toPng(byte[] d) {
        int[] f = parse(d);
        if (f == null) {
            return null;
        }
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] sig = { (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10 };
            o.write(sig, 0, sig.length);
            byte[] ih = new byte[13];
            System.arraycopy(d, 0, ih, 0, 12);
            chunk(o, "IHDR", ih, 0, 13);
            if (f[2] == 3) {
                chunk(o, "PLTE", d, f[3], f[4]);
                if (f[6] >= 0) {
                    int n = Math.max(f[5], f[6] + 1);
                    byte[] a = new byte[n];
                    Arrays.fill(a, (byte) 255);
                    a[f[6]] = 0;
                    chunk(o, "tRNS", a, 0, n);
                }
            }
            chunk(o, "IDAT", d, f[7], f[8]);
            chunk(o, "IEND", new byte[0], 0, 0);
            return o.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void wr32(ByteArrayOutputStream o, long v) {
        o.write((int) (v >> 24));
        o.write((int) (v >> 16));
        o.write((int) (v >> 8));
        o.write((int) v);
    }

    private static void chunk(ByteArrayOutputStream o, String type, byte[] d, int off, int len) {
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        wr32(o, len);
        o.write(t, 0, t.length);
        o.write(d, off, len);
        CRC32 c = new CRC32();
        c.update(t, 0, t.length);
        c.update(d, off, len);
        wr32(o, c.getValue());
    }

    // ---------------- Bitmap -> empacotado ----------------

    /**
     * Gera o formato empacotado a partir da imagem editada. Mantem o tipo do original
     * (RGBA ou paleta); se nao couber (RGBA grande demais) usa paleta.
     * Em paleta: ate 255 cores + 1 transparente; se passar disso, reduz as cores.
     */
    static byte[] fromBitmap(Bitmap bm, byte[] orig) {
        try {
            int[] f = parse(orig);
            int w = bm.getWidth(), h = bm.getHeight();
            if (w < 1 || h < 1 || w > 4096 || h > 4096) {
                return null;
            }
            int[] px = new int[w * h];
            bm.getPixels(px, 0, w, 0, 0, w, h);
            byte[] r = null;
            if (f != null && f[2] == 6) {
                r = build6(px, w, h);
            }
            if (r == null) {
                r = build3(px, w, h);
            }
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void head(ByteArrayOutputStream o, int w, int h, int ct) {
        wr32(o, w);
        wr32(o, h);
        o.write(8);
        o.write(ct);
        o.write(0);
        o.write(0);
    }

    private static byte[] deflate(byte[] raw) {
        Deflater df = new Deflater(9);
        df.setInput(raw);
        df.finish();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!df.finished()) {
            int n = df.deflate(buf);
            o.write(buf, 0, n);
        }
        df.end();
        return o.toByteArray();
    }

    private static byte[] build6(int[] px, int w, int h) {
        byte[] raw = new byte[h * (1 + 4 * w)];
        int q = 0;
        for (int y = 0; y < h; y++) {
            raw[q++] = 0;
            for (int x = 0; x < w; x++) {
                int c = px[y * w + x];
                raw[q++] = (byte) (c >> 16);
                raw[q++] = (byte) (c >> 8);
                raw[q++] = (byte) c;
                raw[q++] = (byte) (c >>> 24);
            }
        }
        byte[] z = deflate(raw);
        if (z.length > 65535) {
            return null;
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        head(o, w, h, 6);
        o.write(z.length >> 8);
        o.write(z.length);
        o.write(z, 0, z.length);
        return o.toByteArray();
    }

    private static int quant(int c, int s) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        if (s > 0) {
            int hf = 1 << (s - 1);
            r = Math.min(255, ((r >> s) << s) + hf);
            g = Math.min(255, ((g >> s) << s) + hf);
            b = Math.min(255, ((b >> s) << s) + hf);
        }
        return (r << 16) | (g << 8) | b;
    }

    private static byte[] build3(int[] px, int w, int h) {
        boolean trans = false;
        for (int c : px) {
            if ((c >>> 24) < 128) {
                trans = true;
                break;
            }
        }
        int max = trans ? 254 : 256;
        int shift = 0;
        HashMap<Integer, Integer> map = new HashMap<>();
        while (true) {
            map.clear();
            for (int c : px) {
                if ((c >>> 24) < 128) {
                    continue;
                }
                int key = quant(c, shift);
                if (!map.containsKey(key)) {
                    map.put(key, map.size());
                    if (map.size() > max) {
                        break;
                    }
                }
            }
            if (map.size() <= max || shift >= 7) {
                break;
            }
            shift++;
        }
        int k = map.size();
        int count = k + (trans ? 1 : 0);
        if (count < 1 || count > 256) {
            return null;
        }
        byte[] pal = new byte[count * 3];
        for (Map.Entry<Integer, Integer> e : map.entrySet()) {
            int c = e.getKey();
            int i = e.getValue() * 3;
            pal[i] = (byte) (c >> 16);
            pal[i + 1] = (byte) (c >> 8);
            pal[i + 2] = (byte) c;
        }
        if (trans) {
            pal[k * 3] = (byte) 255;
            pal[k * 3 + 1] = (byte) 255;
            pal[k * 3 + 2] = (byte) 255;
        }
        byte[] raw = new byte[h * (1 + w)];
        int q = 0;
        for (int y = 0; y < h; y++) {
            raw[q++] = 0;
            for (int x = 0; x < w; x++) {
                int c = px[y * w + x];
                int idx = (c >>> 24) < 128 ? k : map.get(quant(c, shift));
                raw[q++] = (byte) idx;
            }
        }
        byte[] z = deflate(raw);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        head(o, w, h, 3);
        o.write((pal.length >> 8) & 255);
        o.write(pal.length & 255);
        o.write(pal, 0, pal.length);
        if (trans) {
            o.write('t');
            o.write('R');
            o.write('N');
            o.write('S');
            o.write(count);
            o.write(k);
        }
        wr32(o, z.length);
        o.write(z, 0, z.length);
        return o.toByteArray();
    }
}
