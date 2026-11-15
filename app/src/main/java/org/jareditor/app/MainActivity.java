package org.jareditor.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;

public class MainActivity extends Activity {
    static final int REQ_OPEN = 1, REQ_SAVE = 2, REQ_REPLACE = 3;
    static final int[] PAL = {
        0xFF000000, 0xFFFFFFFF, 0xFF7F7F7F, 0xFFBFBFBF, 0xFFFF0000, 0xFF7F0000, 0xFFFF7F00, 0xFFFFFF00,
        0xFF00FF00, 0xFF007F00, 0xFF00FFFF, 0xFF0000FF, 0xFF00007F, 0xFF7F00FF, 0xFFFF00FF, 0xFF7F3F00 };
    static final String TEXT_EXT = ".txt .jad .mf .properties .xml .json .ini .csv .lng .cfg .html .htm";
    static final int[] BPP = { 4, 4, 2, 1, 3 };
    static final String[] RAW_FMT = { "RGBA 8888", "ARGB 8888", "RGB 565", "Cinza 8 bits", "RGB 888" };

    interface SliderCb { void ok(int[] v); }

    static class Src { int kind, off, len, w, h, fmt; } // kind 0 arquivo, 1 PNG embutido, 2 RAW

    final LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
    final ArrayList<String> shown = new ArrayList<>();
    ArrayAdapter<String> adapter;
    EditText filter;
    CheckBox onlyImg;
    TextView info;
    String jarName = "jogo.jar", replaceTarget;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout row = new LinearLayout(this);
        Button open = new Button(this); open.setText("Abrir .jar");
        Button save = new Button(this); save.setText("Salvar .jar");
        row.addView(open, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(save, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(row);
        filter = new EditText(this);
        filter.setHint("Filtrar (ex: png, menu, .txt)");
        filter.setSingleLine(true);
        root.addView(filter);
        onlyImg = new CheckBox(this);
        onlyImg.setText("Só imagens/texturas detectadas");
        root.addView(onlyImg);
        info = new TextView(this);
        info.setText("Abra um .jar para começar");
        root.addView(info);
        ListView lv = new ListView(this);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, shown) {
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, cv, parent);
                String n = shown.get(pos);
                byte[] data = files.get(n);
                String ty = imgType(data);
                t.setText((ty != null ? "[IMG " + ty.toUpperCase() + "] " : "") + n + "  (" + data.length + " B)");
                return t;
            }
        };
        lv.setAdapter(adapter);
        root.addView(lv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        open.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_OPEN);
        });
        save.setOnClickListener(v -> {
            if (files.isEmpty()) { toast("Nada para salvar"); return; }
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/java-archive");
            i.putExtra(Intent.EXTRA_TITLE, "editado_" + jarName);
            startActivityForResult(i, REQ_SAVE);
        });
        filter.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable e) { refresh(); }
        });
        onlyImg.setOnCheckedChangeListener((bt, on) -> refresh());
        lv.setOnItemClickListener((p, v, pos, id) -> edit(shown.get(pos)));
        lv.setOnItemLongClickListener((p, v, pos, id) -> { options(shown.get(pos)); return true; });
    }

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ---------- detecção de formato pelo conteúdo ----------
    static String imgType(byte[] d) {
        if (d == null || d.length < 12) return null;
        if ((d[0] & 255) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return "png";
        if ((d[0] & 255) == 0xFF && (d[1] & 255) == 0xD8 && (d[2] & 255) == 0xFF) return "jpg";
        if (d[0] == 'G' && d[1] == 'I' && d[2] == 'F' && d[3] == '8') return "gif";
        if (d.length > 30 && d[0] == 'B' && d[1] == 'M' && d[6] == 0 && d[7] == 0 && d[8] == 0 && d[9] == 0) return "bmp";
        if (d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F' && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') return "webp";
        return null;
    }

    void refresh() {
        String f = filter.getText().toString().toLowerCase();
        shown.clear();
        for (Map.Entry<String, byte[]> en : files.entrySet()) {
            String n = en.getKey();
            if (!f.isEmpty() && !n.toLowerCase().contains(f)) continue;
            if (onlyImg.isChecked() && imgType(en.getValue()) == null) continue;
            shown.add(n);
        }
        adapter.notifyDataSetChanged();
        info.setText(jarName + ": " + shown.size() + " de " + files.size() + " (toque = editar, segurar = opções)");
    }

    byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        in.close();
        return o.toByteArray();
    }

    String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception e) {}
        return "jogo.jar";
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        try {
            if (req == REQ_OPEN) {
                jarName = displayName(u);
                files.clear();
                ZipInputStream z = new ZipInputStream(getContentResolver().openInputStream(u));
                ZipEntry e;
                while ((e = z.getNextEntry()) != null) {
                    if (!e.isDirectory()) files.put(e.getName(), readAll(z));
                }
                z.close();
                filter.setText("");
                refresh();
            } else if (req == REQ_SAVE) {
                ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(getContentResolver().openOutputStream(u)));
                for (Map.Entry<String, byte[]> en : files.entrySet()) {
                    z.putNextEntry(new ZipEntry(en.getKey()));
                    z.write(en.getValue());
                    z.closeEntry();
                }
                z.close();
                toast("Salvo!");
            } else if (req == REQ_REPLACE && replaceTarget != null) {
                files.put(replaceTarget, readAll(getContentResolver().openInputStream(u)));
                refresh();
                toast("Substituído");
            }
        } catch (Exception ex) {
            toast("Erro: " + ex.getMessage());
        }
    }

    // ---------- abrir arquivos ----------
    void edit(String name) {
        byte[] data = files.get(name);
        if (imgType(data) != null) { openImage(name, new Src()); return; }
        String l = name.toLowerCase();
        for (String ext : TEXT_EXT.split(" ")) if (l.endsWith(ext)) { editText(name); return; }
        options(name);
    }

    void options(final String name) {
        String[] it = { "Editar como imagem (detectar)", "Procurar PNG embutido no arquivo",
            "Abrir como imagem RAW (bruta)...", "Editar como texto", "Substituir por arquivo...", "Excluir" };
        new AlertDialog.Builder(this).setTitle(name).setItems(it, (d, w) -> {
            if (w == 0) openImage(name, new Src());
            else if (w == 1) scanEmbedded(name);
            else if (w == 2) rawDialog(name);
            else if (w == 3) editText(name);
            else if (w == 4) {
                replaceTarget = name;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, REQ_REPLACE);
            } else {
                new AlertDialog.Builder(this).setMessage("Excluir " + name + "?")
                    .setPositiveButton("Excluir", (d2, w2) -> { files.remove(name); refresh(); })
                    .setNegativeButton("Cancelar", null).show();
            }
        }).show();
    }

    void editText(final String name) {
        byte[] data = files.get(name);
        String cs = "UTF-8", s;
        try {
            s = Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data)).toString();
        } catch (Exception e) {
            cs = "ISO-8859-1";
            s = new String(data, Charset.forName(cs));
        }
        final String charset = cs;
        final EditText et = new EditText(this);
        et.setText(s);
        et.setGravity(Gravity.TOP);
        et.setTextSize(13);
        new AlertDialog.Builder(this).setTitle(name + " [" + cs + "]").setView(et)
            .setPositiveButton("Aplicar", (dd, w) -> {
                try { files.put(name, et.getText().toString().getBytes(charset)); }
                catch (Exception ex) { toast("Erro ao codificar"); }
            }).setNegativeButton("Cancelar", null).show();
    }

    // ---------- imagens: arquivo, PNG embutido, RAW ----------
    void scanEmbedded(final String name) {
        final byte[] data = files.get(name);
        final ArrayList<int[]> found = new ArrayList<>();
        byte[] sig = { (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10 };
        for (int i = 0; i + 8 <= data.length && found.size() < 100; i++) {
            boolean m = true;
            for (int k = 0; k < 8; k++) if (data[i + k] != sig[k]) { m = false; break; }
            if (!m) continue;
            int end = -1;
            for (int j = i + 8; j + 8 <= data.length; j++) {
                if (data[j] == 'I' && data[j + 1] == 'E' && data[j + 2] == 'N' && data[j + 3] == 'D') { end = j + 8; break; }
            }
            if (end > 0) { found.add(new int[] { i, end - i }); i = end - 1; }
        }
        if (found.isEmpty()) { toast("Nenhum PNG embutido encontrado"); return; }
        String[] items = new String[found.size()];
        for (int i = 0; i < items.length; i++) items[i] = "PNG #" + (i + 1) + "  @" + found.get(i)[0] + "  (" + found.get(i)[1] + " B)";
        new AlertDialog.Builder(this).setTitle(found.size() + " imagem(ns) em " + name).setItems(items, (d, w) -> {
            Src s = new Src();
            s.kind = 1; s.off = found.get(w)[0]; s.len = found.get(w)[1];
            openImage(name, s);
        }).show();
    }

    EditText num(String hint, String val) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(val);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    void rawDialog(final String name) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(8), dp(16), 0);
        final EditText eo = num("Offset (bytes de cabeçalho)", "0");
        final EditText ew = num("Largura", "64");
        final EditText eh = num("Altura", "64");
        final Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, RAW_FMT));
        l.addView(eo); l.addView(ew); l.addView(eh); l.addView(sp);
        new AlertDialog.Builder(this).setTitle("Imagem RAW: " + name).setView(l)
            .setPositiveButton("Abrir", (d, w) -> {
                try {
                    Src s = new Src();
                    s.kind = 2;
                    s.off = Integer.parseInt(eo.getText().toString().trim());
                    s.w = Integer.parseInt(ew.getText().toString().trim());
                    s.h = Integer.parseInt(eh.getText().toString().trim());
                    s.fmt = sp.getSelectedItemPosition();
                    openImage(name, s);
                } catch (Exception ex) { toast("Valores inválidos"); }
            }).setNegativeButton("Cancelar", null).show();
    }

    static Bitmap decodeRaw(byte[] d, int off, int w, int h, int fmt) {
        if (w <= 0 || h <= 0 || off < 0 || (long) w * h > 4000000L) return null;
        int bpp = BPP[fmt];
        int[] px = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            int p = off + i * bpp, r = 0, g = 0, b = 0, a = 255;
            if (p + bpp <= d.length) {
                switch (fmt) {
                    case 0: r = d[p] & 255; g = d[p + 1] & 255; b = d[p + 2] & 255; a = d[p + 3] & 255; break;
                    case 1: a = d[p] & 255; r = d[p + 1] & 255; g = d[p + 2] & 255; b = d[p + 3] & 255; break;
                    case 2: {
                        int v = (d[p] & 255) | ((d[p + 1] & 255) << 8);
                        r = ((v >> 11) & 31) * 255 / 31; g = ((v >> 5) & 63) * 255 / 63; b = (v & 31) * 255 / 31;
                        break;
                    }
                    case 3: r = d[p] & 255; g = r; b = r; break;
                    case 4: r = d[p] & 255; g = d[p + 1] & 255; b = d[p + 2] & 255; break;
                }
            }
            px[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bm.setPixels(px, 0, w, 0, 0, w, h);
        return bm;
    }

    static byte[] encodeRaw(Bitmap bm, byte[] d, int off, int fmt) {
        int w = bm.getWidth(), h = bm.getHeight(), bpp = BPP[fmt];
        byte[] o = Arrays.copyOf(d, Math.max(d.length, off + w * h * bpp));
        int[] px = new int[w * h];
        bm.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i++) {
            int c = px[i], a = c >>> 24, r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255, p = off + i * bpp;
            switch (fmt) {
                case 0: o[p] = (byte) r; o[p + 1] = (byte) g; o[p + 2] = (byte) b; o[p + 3] = (byte) a; break;
                case 1: o[p] = (byte) a; o[p + 1] = (byte) r; o[p + 2] = (byte) g; o[p + 3] = (byte) b; break;
                case 2: {
                    int v = ((r * 31 / 255) << 11) | ((g * 63 / 255) << 5) | (b * 31 / 255);
                    o[p] = (byte) v; o[p + 1] = (byte) (v >> 8);
                    break;
                }
                case 3: o[p] = (byte) ((r * 299 + g * 587 + b * 114) / 1000); break;
                case 4: o[p] = (byte) r; o[p + 1] = (byte) g; o[p + 2] = (byte) b; break;
            }
        }
        return o;
    }

    void openImage(String name, Src s) {
        byte[] data = files.get(name);
        Bitmap bm = null;
        try {
            if (s.kind == 0) bm = BitmapFactory.decodeByteArray(data, 0, data.length);
            else if (s.kind == 1) bm = BitmapFactory.decodeByteArray(data, s.off, s.len);
            else bm = decodeRaw(data, s.off, s.w, s.h, s.fmt);
        } catch (Exception e) {}
        if (bm == null) { toast("Não reconheci como imagem. Tente PNG embutido ou RAW (segure o arquivo)."); return; }
        editImage(name, s, bm, s.kind == 0 ? imgType(data) : "png");
    }

    byte[] encImg(Bitmap b, String type) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        if ("jpg".equals(type)) b.compress(Bitmap.CompressFormat.JPEG, 100, o);
        else b.compress(Bitmap.CompressFormat.PNG, 100, o);
        return o.toByteArray();
    }

    Button mk(String t) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextSize(11);
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    void editImage(final String name, final Src s, Bitmap bm, final String type) {
        final PixelView pv = new PixelView(this, bm.copy(Bitmap.Config.ARGB_8888, true));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final TextView st = new TextView(this);
        st.setTextSize(11);
        st.setText("Toque pinta · 2 dedos: zoom e mover · " + bm.getWidth() + "x" + bm.getHeight());
        box.addView(st);
        pv.info = (x, y, c) -> st.setText(x + "," + y + "  #" + String.format("%08X", c) + "  " + pv.bmp.getWidth() + "x" + pv.bmp.getHeight());

        LinearLayout tools = new LinearLayout(this);
        String[] tn = { "Lápis", "Borr.", "Gota", "Balde", "Mover" };
        final Button[] tb = new Button[tn.length];
        for (int i = 0; i < tn.length; i++) {
            final int t = i;
            tb[i] = mk(tn[i]);
            tb[i].setAlpha(i == 0 ? 1f : 0.55f);
            tb[i].setOnClickListener(v -> {
                pv.tool = t;
                for (int k = 0; k < tb.length; k++) tb[k].setAlpha(k == t ? 1f : 0.55f);
            });
            tools.addView(tb[i], new LinearLayout.LayoutParams(0, -2, 1));
        }
        box.addView(tools);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        LinearLayout pal = new LinearLayout(this);
        final View cur = new View(this);
        cur.setBackgroundColor(pv.color);
        pal.addView(cur, new LinearLayout.LayoutParams(dp(48), dp(40)));
        for (final int col : PAL) {
            View sw = new View(this);
            sw.setBackgroundColor(col);
            sw.setOnClickListener(v -> { pv.color = col; cur.setBackgroundColor(col); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
            lp.setMargins(dp(2), 0, dp(2), 0);
            pal.addView(sw, lp);
        }
        Button hex = mk("Hex");
        hex.setOnClickListener(v -> {
            final EditText h = new EditText(this);
            h.setHint("RRGGBB ou AARRGGBB");
            new AlertDialog.Builder(this).setView(h).setPositiveButton("OK", (dd, w) -> {
                try {
                    pv.color = Color.parseColor("#" + h.getText().toString().trim());
                    cur.setBackgroundColor(pv.color);
                } catch (Exception ex) { toast("Cor inválida"); }
            }).setNegativeButton("Cancelar", null).show();
        });
        pal.addView(hex);
        hs.addView(pal);
        box.addView(hs);
        pv.onPick = () -> cur.setBackgroundColor(pv.color);

        LinearLayout r3 = new LinearLayout(this);
        final Button bg = mk("Grade ✓"), br = mk("Pincel 1"), bf = mk("Foto"), bu = mk("Desfazer"), bz1 = mk("−"), bz2 = mk("+");
        bg.setOnClickListener(v -> { pv.grid = !pv.grid; bg.setText(pv.grid ? "Grade ✓" : "Grade"); pv.invalidate(); });
        br.setOnClickListener(v -> { pv.brush = pv.brush % 4 + 1; br.setText("Pincel " + pv.brush); });
        bf.setOnClickListener(v -> photoMenu(pv));
        bu.setOnClickListener(v -> pv.undo());
        bz1.setOnClickListener(v -> pv.zoom(0.5f));
        bz2.setOnClickListener(v -> pv.zoom(2f));
        Button[] r3b = { bg, br, bf, bu, bz1, bz2 };
        for (Button x : r3b) r3.addView(x, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(r3);
        box.addView(pv, new LinearLayout.LayoutParams(-1, 0, 1));

        String tag = s.kind == 1 ? " (PNG embutido)" : s.kind == 2 ? " (RAW)" : "";
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle(name + tag).setView(box)
            .setPositiveButton("Salvar", (dd, w) -> {
                try {
                    byte[] out;
                    if (s.kind == 2) out = encodeRaw(pv.bmp, files.get(name), s.off, s.fmt);
                    else {
                        byte[] img = encImg(pv.bmp, type);
                        if (s.kind == 1) {
                            byte[] old = files.get(name);
                            out = new byte[old.length - s.len + img.length];
                            System.arraycopy(old, 0, out, 0, s.off);
                            System.arraycopy(img, 0, out, s.off, img.length);
                            System.arraycopy(old, s.off + s.len, out, s.off + img.length, old.length - s.off - s.len);
                        } else out = img;
                    }
                    files.put(name, out);
                    refresh();
                    toast("Imagem atualizada");
                } catch (Exception ex) { toast("Erro ao salvar: " + ex.getMessage()); }
            }).setNegativeButton("Cancelar", null).create();
        dlg.setCanceledOnTouchOutside(false);
        dlg.show();
        dlg.getWindow().setLayout(-1, -1);
    }

    // ---------- modo foto ----------
    void photoMenu(final PixelView pv) {
        String[] it = { "Brilho / contraste", "Saturação", "Tons de cinza", "Inverter cores", "Posterizar (menos cores)",
            "Girar 90° →", "Girar 90° ←", "Espelhar horizontal", "Espelhar vertical", "Redimensionar..." };
        new AlertDialog.Builder(this).setTitle("Editor de foto").setItems(it, (d, w) -> {
            switch (w) {
                case 0: sliders("Brilho / contraste", new String[] { "Brilho", "Contraste" },
                    new int[] { -255, -100 }, new int[] { 255, 100 }, new int[] { 0, 0 }, v -> pv.adjust(0, v[0], v[1])); break;
                case 1: sliders("Saturação", new String[] { "Saturação" },
                    new int[] { -100 }, new int[] { 100 }, new int[] { 0 }, v -> pv.adjust(1, v[0], 0)); break;
                case 2: pv.adjust(2, 0, 0); break;
                case 3: pv.adjust(3, 0, 0); break;
                case 4: sliders("Posterizar", new String[] { "Níveis por canal" },
                    new int[] { 2 }, new int[] { 16 }, new int[] { 4 }, v -> pv.adjust(4, v[0], 0)); break;
                case 5: pv.rotate(true); break;
                case 6: pv.rotate(false); break;
                case 7: pv.flip(true); break;
                case 8: pv.flip(false); break;
                default: resizeDialog(pv);
            }
        }).show();
    }

    void sliders(String title, String[] names, int[] min, int[] max, int[] init, final SliderCb cb) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(8), dp(16), 0);
        final int[] vals = init.clone();
        for (int i = 0; i < names.length; i++) {
            final int idx = i, mn = min[i];
            final String nm = names[i];
            final TextView tv = new TextView(this);
            tv.setText(nm + ": " + init[i]);
            SeekBar sb = new SeekBar(this);
            sb.setMax(max[i] - min[i]);
            sb.setProgress(init[i] - min[i]);
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar bar, int p, boolean user) { vals[idx] = p + mn; tv.setText(nm + ": " + vals[idx]); }
                public void onStartTrackingTouch(SeekBar bar) {}
                public void onStopTrackingTouch(SeekBar bar) {}
            });
            l.addView(tv);
            l.addView(sb);
        }
        new AlertDialog.Builder(this).setTitle(title).setView(l)
            .setPositiveButton("Aplicar", (d, w) -> cb.ok(vals)).setNegativeButton("Cancelar", null).show();
    }

    void resizeDialog(final PixelView pv) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(8), dp(16), 0);
        final EditText ew = num("Largura", "" + pv.bmp.getWidth());
        final EditText eh = num("Altura", "" + pv.bmp.getHeight());
        final CheckBox sm = new CheckBox(this);
        sm.setText("Suavizar (desmarcado mantém pixels nítidos)");
        l.addView(ew); l.addView(eh); l.addView(sm);
        new AlertDialog.Builder(this).setTitle("Redimensionar").setView(l)
            .setPositiveButton("Aplicar", (d, w) -> {
                try {
                    int nw = Integer.parseInt(ew.getText().toString().trim());
                    int nh = Integer.parseInt(eh.getText().toString().trim());
                    if (!pv.resize(nw, nh, sm.isChecked())) toast("Tamanho fora do limite");
                } catch (Exception ex) { toast("Valor inválido"); }
            }).setNegativeButton("Cancelar", null).show();
    }
}
