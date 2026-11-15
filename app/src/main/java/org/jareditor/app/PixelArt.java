package org.jareditor.app;

import android.graphics.Bitmap;

/**
 * Converte uma imagem HD em pixel art alinhada as "casas" (pixels) do desenho.
 * Cada casa recebe a MEDIA da area da imagem HD que cai dentro dela (nao um unico pixel),
 * entao o resultado fica fiel e sem serrilhado. A imagem HD original nunca e alterada.
 */
final class PixelArt {
    private PixelArt() {
    }

    /** Resultado: bitmap com as casas cobertas e a posicao (x0, y0) delas no desenho. */
    static final class Cells {
        Bitmap bmp;
        int x0, y0;
    }

    /**
     * A imagem {@code src} cobre, em coordenadas do desenho (1 unidade = 1 casa), o retangulo
     * [rx, rx + largura*sx] x [ry, ry + altura*sy]. Para cada casa de [0,bw) x [0,bh) que a imagem
     * cobre em pelo menos metade, a cor e a media (ponderada pela area e pela opacidade) do pedaco da
     * imagem que cai ali. A transparencia vira tudo-ou-nada, como nas imagens de jogos J2ME.
     * Devolve null se a imagem nao toca o desenho.
     */
    static Cells convert(Bitmap src, float rx, float ry, float sx, float sy, int bw, int bh) {
        int sw = src.getWidth(), sh = src.getHeight();
        float rr = rx + sw * sx, rb = ry + sh * sy;
        int cx0 = Math.max(0, (int) Math.floor(rx));
        int cy0 = Math.max(0, (int) Math.floor(ry));
        int cx1 = Math.min(bw, (int) Math.ceil(rr));
        int cy1 = Math.min(bh, (int) Math.ceil(rb));
        if (cx1 <= cx0 || cy1 <= cy0) {
            return null;
        }
        int cw = cx1 - cx0, ch = cy1 - cy0;
        int[] out = new int[cw * ch];
        int[] buf = new int[0];

        for (int cy = cy0; cy < cy1; cy++) {
            float y0c = Math.max(cy, ry), y1c = Math.min(cy + 1, rb);
            float covH = y1c - y0c;
            if (covH <= 0f) {
                continue;
            }
            float s0 = Math.max(0f, (y0c - ry) / sy), s1 = Math.min(sh, (y1c - ry) / sy);
            int ra = (int) Math.floor(s0), rz = Math.min(sh, (int) Math.ceil(s1));
            int rows = rz - ra;
            if (rows <= 0) {
                continue;
            }
            if (buf.length < sw * rows) {
                buf = new int[sw * rows];
            }
            src.getPixels(buf, 0, sw, 0, ra, sw, rows);

            for (int cx = cx0; cx < cx1; cx++) {
                float x0c = Math.max(cx, rx), x1c = Math.min(cx + 1, rr);
                float covW = x1c - x0c;
                if (covW <= 0f || covW * covH < 0.5f) {
                    continue; // a imagem cobre menos da metade desta casa: deixa vazia
                }
                float t0 = Math.max(0f, (x0c - rx) / sx), t1 = Math.min(sw, (x1c - rx) / sx);
                int ca = (int) Math.floor(t0), cz = Math.min(sw, (int) Math.ceil(t1));

                double wsum = 0, asum = 0, rs = 0, gs = 0, bs = 0;
                for (int yy = ra; yy < rz; yy++) {
                    float wy = Math.min(yy + 1, s1) - Math.max(yy, s0);
                    if (wy <= 0f) {
                        continue;
                    }
                    int base = (yy - ra) * sw;
                    for (int xx = ca; xx < cz; xx++) {
                        float wx = Math.min(xx + 1, t1) - Math.max(xx, t0);
                        if (wx <= 0f) {
                            continue;
                        }
                        double w = (double) wx * wy;
                        int c = buf[base + xx];
                        double wa = w * (c >>> 24);
                        wsum += w;
                        asum += wa;
                        rs += wa * ((c >> 16) & 255);
                        gs += wa * ((c >> 8) & 255);
                        bs += wa * (c & 255);
                    }
                }
                if (wsum <= 0 || asum <= 0) {
                    continue;
                }
                if (asum / wsum < 128) {
                    continue; // casa transparente
                }
                int r = (int) Math.round(rs / asum), g = (int) Math.round(gs / asum), b = (int) Math.round(bs / asum);
                out[(cy - cy0) * cw + (cx - cx0)] = 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
            }
        }
        Cells k = new Cells();
        k.bmp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        k.bmp.setPixels(out, 0, cw, 0, 0, cw, ch);
        k.x0 = cx0;
        k.y0 = cy0;
        return k;
    }

    /** Ajusta a imagem inteira para w x h em pixel art (media de area), usado ao substituir arquivos. */
    static Bitmap fit(Bitmap src, int w, int h) {
        Cells k = convert(src, 0f, 0f, w / (float) src.getWidth(), h / (float) src.getHeight(), w, h);
        if (k != null && k.bmp.getWidth() == w && k.bmp.getHeight() == h) {
            return k.bmp;
        }
        return Bitmap.createScaledBitmap(src, w, h, false);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : v > 255 ? 255 : v;
    }
}
