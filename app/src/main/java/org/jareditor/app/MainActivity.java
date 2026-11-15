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
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.*;

public class MainActivity extends Activity {
    static final int REQ_OPEN = 1, REQ_SAVE = 2, REQ_REPLACE = 3, REQ_IMPORT = 4;
    static final int[] PAL = {
        0xFF000000, 0xFFFFFFFF, 0xFF7F7F7F, 0xFFBFBFBF, 0xFFFF0000, 0xFF7F0000, 0xFFFF7F00, 0xFFFFFF00,
        0xFF00FF00, 0xFF007F00, 0xFF00FFFF, 0xFF0000FF, 0xFF00007F, 0xFF7F00FF, 0xFFFF00FF, 0xFF7F3F00 };
    static final String TEXT_EXT = ".txt .jad .mf .properties .xml .json .ini .csv .lng .cfg .html .htm";
    static final int[] BPP = { 4, 4, 2, 1, 3 };
    static final String[] RAW_FMT = { "RGBA 8888", "ARGB 8888", "RGB 565", "Cinza 8 bits", "RGB 888" };

    interface SliderCb { void ok(int[] v); }

    static class Src { int kind, off, len, w, h, fmt; } // kind 0 arquivo, 1 PNG embutido, 2 RAW

    // Resultado do teste de abrir o arquivo como imagem.
    // type != null  -> o arquivo inteiro abriu como imagem (png, jpg, gif, bmp, webp...)
    // embedded > 0  -> não abriu inteiro, mas tem PNG(s) dentro (comum em pacotes de texturas)
    // nenhum dos dois -> não é imagem
    static class Scan { String type; int w, h, embedded; boolean isImage() { return type != null || embedded > 0; } }

    final ConcurrentHashMap<String, Scan> scan = new ConcurrentHashMap<>();
    volatile boolean cancelScan;
    final LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
    final ArrayList<String> shown = new ArrayList<>();
    ArrayAdapter<String> adapter;
    EditText filter;
    CheckBox onlyImg;
    TextView info;
    String jarName = "jogo.jar", replaceTarget;
    PixelView importPv;

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
        Button scanBtn = new Button(this); scanBtn.setText("Escanear");
        row.addView(scanBtn, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(row);
        scanBtn.setOnClickListener(v -> scanAll());
        Button valBtn = new Button(this); valBtn.setText("Valores (moedas, diamantes...)");
        root.addView(valBtn);
        valBtn.setOnClickListener(v -> valuesDialog());
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
                Scan sc = scan.get(n);
                String tag = "";
                int color = Color.BLACK;
                if (sc != null && sc.type != null) {
                    tag = "[IMG " + sc.type.toUpperCase() + " " + sc.w + "x" + sc.h + "] ";
                    color = Color.rgb(0, 120, 0);
                } else if (sc != null && sc.embedded > 0) {
                    tag = "[PNG embutido x" + sc.embedded + "] ";
                    color = Color.rgb(0, 80, 170);
                }
                t.setTextColor(color);
                t.setText(tag + n + "  (" + data.length + " B)"); Thumbs.apply(MainActivity.this, t, n, data, sc);
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

    // Detecção rápida pelos primeiros bytes (usada também pelo PesActivity)
    static String imgType(byte[] d) {
        if (d == null || d.length < 12) return null;
        if ((d[0] & 255) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return "png";
        if ((d[0] & 255) == 0xFF && (d[1] & 255) == 0xD8 && (d[2] & 255) == 0xFF) return "jpg";
        if (d[0] == 'G' && d[1] == 'I' && d[2] == 'F' && d[3] == '8') return "gif";
        if (d.length > 30 && d[0] == 'B' && d[1] == 'M' && d[6] == 0 && d[7] == 0 && d[8] == 0 && d[9] == 0) return "bmp";
        if (d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F' && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') return "webp";
        return null;
    }

    // ---------- scanner: tenta ABRIR cada arquivo como imagem ----------
    static String extFromMime(String mime) {
        if (mime == null) return "img";
        String m = mime.toLowerCase();
        if (m.contains("png")) return "png";
        if (m.contains("jpeg") || m.contains("jpg")) return "jpg";
        if (m.contains("gif")) return "gif";
        if (m.contains("bmp")) return "bmp";
        if (m.contains("webp")) return "webp";
        if (m.contains("heif") || m.contains("heic")) return "heif";
        int i = m.indexOf('/');
        return i >= 0 && i + 1 < m.length() ? m.substring(i + 1) : "img";
    }

    // Testa um arquivo: 1) o arquivo inteiro abre como imagem? 2) tem PNG embutido?
    static Scan probe(byte[] d) {
        Scan s = new Scan();
        if (d == null || d.length < 8) return s;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(d, 0, d.length, o);
            if (o.outWidth > 0 && o.outHeight > 0) {
                boolean ok = true;
                // confirma de verdade: decodifica os pixels (o cabeçalho sozinho pode enganar)
                if ((long) o.outWidth * o.outHeight <= 4000000L) {
                    Bitmap b = BitmapFactory.decodeByteArray(d, 0, d.length);
                    ok = b != null;
                    if (b != null) b.recycle();
                }
                if (ok) {
                    s.type = extFromMime(o.outMimeType);
                    s.w = o.outWidth;
                    s.h = o.outHeight;
                    return s;
                }
            }
        } catch (Throwable t) { /* sem memória ou dados inválidos: segue para o teste de PNG embutido */ }
        s.embedded = findPng(d, 1000).size();
        return s;
    }

    // Procura PNGs dentro de um arquivo qualquer. Retorna {offset, tamanho}.
    static ArrayList<int[]> findPng(byte[] data, int max) {
        ArrayList<int[]> found = new ArrayList<>();
        byte[] sig = { (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10 };
        for (int i = 0; i + 8 <= data.length && found.size() < max; i++) {
            if (data[i] != sig[0]) continue;
            boolean m = true;
            for (int k = 1; k < 8; k++) if (data[i + k] != sig[k]) { m = false; break; }
            if (!m) continue;
            int end = -1;
            for (int j = i + 8; j + 8 <= data.length; j++) {
                if (data[j] == 'I' && data[j + 1] == 'E' && data[j + 2] == 'N' && data[j + 3] == 'D') { end = j + 8; break; }
            }
            if (end > 0) { found.add(new int[] { i, end - i }); i = end - 1; }
        }
        return found;
    }

    // Guarda o arquivo e refaz o teste só dele
    void put(String name, byte[] data) {
        files.put(name, data);
        scan.put(name, probe(data));
    }

    // Escaneia todos os arquivos do jar em segundo plano, com progresso e botão cancelar
    void scanAll() {
        if (files.isEmpty()) { toast("Abra um .jar primeiro"); return; }
        final ArrayList<String> names = new ArrayList<>(files.keySet());
        final int total = names.size();
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(12), dp(16), 0);
        final TextView tv = new TextView(this);
        tv.setText("0/" + total);
        final ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(total);
        l.addView(tv);
        l.addView(pb);
        cancelScan = false;
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Testando cada arquivo como imagem").setView(l)
            .setCancelable(false).setNegativeButton("Cancelar", (d, w) -> cancelScan = true).create();
        dlg.show();
        new Thread(() -> {
            final int[] cnt = { 0, 0, 0 }; // imagens inteiras, com PNG embutido, não-imagens
            int done = 0;
            for (final String n : names) {
                if (cancelScan) break;
                Scan s = probe(files.get(n));
                scan.put(n, s);
                if (s.type != null) cnt[0]++; else if (s.embedded > 0) cnt[1]++; else cnt[2]++;
                done++;
                if (done % 4 == 0 || done == total) {
                    final int dn = done;
                    runOnUiThread(() -> { pb.setProgress(dn); tv.setText(dn + "/" + total + "  " + n); });
                }
            }
            final boolean cancelled = cancelScan;
            runOnUiThread(() -> {
                try { dlg.dismiss(); } catch (Exception e) {}
                refresh();
                toast((cancelled ? "Cancelado. " : "") + cnt[0] + " imagens, " + cnt[1] + " com PNG embutido, " + cnt[2] + " não são imagem");
            });
        }).start();
    }

    void refresh() {
        String f = filter.getText().toString().toLowerCase();
        shown.clear();
        int img = 0, emb = 0;
        for (Map.Entry<String, byte[]> en : files.entrySet()) {
            String n = en.getKey();
            Scan s = scan.get(n);
            if (s != null && s.type != null) img++; else if (s != null && s.embedded > 0) emb++;
            if (!f.isEmpty() && !n.toLowerCase().contains(f)) continue;
            if (onlyImg.isChecked() && (s == null || !s.isImage())) continue;
            shown.add(n);
        }
        adapter.notifyDataSetChanged();
        info.setText(jarName + ": " + shown.size() + " de " + files.size() + " | " + img + " imagens, " + emb
            + " com PNG embutido (toque = editar, segurar = opções)");
    }

    // ---------- editor de valores (moedas, diamantes, rubis, cédulas...): decimal -> hex ----------
    static final String[] VAL_MODES = { "Automático (texto + 16 + 32 bits)", "8 bits", "16 bits", "32 bits", "Só texto decimal" };
    static final int MAXH = 600;

    static class Hit { String name; int off, size; boolean le, text; long val; }

    static Hit hit(String n, int off, int size, boolean le, boolean text, long v) {
        Hit h = new Hit();
        h.name = n; h.off = off; h.size = size; h.le = le; h.text = text; h.val = v;
        return h;
    }

    static byte[] toBytes(long v, int w, boolean le) {
        byte[] b = new byte[w];
        for (int k = 0; k < w; k++) b[k] = (byte) (v >> (le ? 8 * k : 8 * (w - 1 - k)));
        return b;
    }

    static boolean isDig(byte b) { return b >= '0' && b <= '9'; }

    static String hx(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < b.length; i++) s.append(String.format("%02X", b[i] & 255)).append(i + 1 < b.length ? " " : "");
        return s.toString();
    }

    // Procura o valor em TODOS os arquivos (inclusive .class): como texto e como número binário (LE e BE)
    static ArrayList<Hit> findValue(Map<String, byte[]> files, long v, int mode) {
        ArrayList<Hit> out = new ArrayList<>();
        byte[] txt = Long.toString(v).getBytes();
        for (Map.Entry<String, byte[]> en : files.entrySet()) {
            if (out.size() >= MAXH) break;
            String n = en.getKey();
            byte[] d = en.getValue();
            if (mode == 0 || mode == 4) {
                for (int i = 0; i + txt.length <= d.length && out.size() < MAXH; i++) {
                    if (d[i] != txt[0]) continue;
                    boolean m = true;
                    for (int k = 1; k < txt.length; k++) if (d[i + k] != txt[k]) { m = false; break; }
                    if (!m) continue;
                    if (i > 0 && isDig(d[i - 1])) continue;
                    if (i + txt.length < d.length && isDig(d[i + txt.length])) continue;
                    out.add(hit(n, i, txt.length, false, true, v));
                }
            }
            if (mode == 4) continue;
            for (int w = 1; w <= 4; w *= 2) {
                if (mode == 0 && w == 1) continue;
                if (mode == 1 && w != 1) continue;
                if (mode == 2 && w != 2) continue;
                if (mode == 3 && w != 4) continue;
                if (v >= (1L << (8 * w))) continue;
                for (int e = 0; e < (w == 1 ? 1 : 2); e++) {
                    boolean le = e == 0;
                    byte[] pat = toBytes(v, w, le);
                    for (int i = 0; i + w <= d.length && out.size() < MAXH; i++) {
                        if (d[i] != pat[0]) continue;
                        boolean m = true;
                        for (int k = 1; k < w; k++) if (d[i + k] != pat[k]) { m = false; break; }
                        if (m) out.add(hit(n, i, w, le, false, v));
                    }
                }
            }
        }
        return out;
    }

    static boolean fits(Hit h, long nv) { return nv >= 0 && (h.text || nv < (1L << (8 * h.size))); }

    static byte[] patch(byte[] d, Hit h, long nv) {
        if (h.text) {
            byte[] t = Long.toString(nv).getBytes();
            byte[] o = new byte[d.length - h.size + t.length];
            System.arraycopy(d, 0, o, 0, h.off);
            System.arraycopy(t, 0, o, h.off, t.length);
            System.arraycopy(d, h.off + h.size, o, h.off + t.length, d.length - h.off - h.size);
            return o;
        }
        byte[] o = d.clone();
        System.arraycopy(toBytes(nv, h.size, h.le), 0, o, h.off, h.size);
        return o;
    }

    // Mostra o decimal digitado convertido para hex e os bytes que serão gravados
    static String hexInfo(Hit h, String s) {
        try {
            long nv = Long.parseLong(s.trim());
            if (!fits(h, nv)) return "Valor fora do limite (máx " + (h.text ? "-" : "" + ((1L << (8 * h.size)) - 1)) + ")";
            byte[] b = h.text ? Long.toString(nv).getBytes() : toBytes(nv, h.size, h.le);
            return "Hex: 0x" + Long.toHexString(nv).toUpperCase() + "\nBytes gravados: " + hx(b);
        } catch (Exception e) { return "Digite um número decimal"; }
    }

    void valuesDialog() {
        if (files.isEmpty()) { toast("Abra um .jar primeiro"); return; }
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(8), dp(16), 0);
        final EditText ev = num("Valor atual (decimal), ex: 1500", "");
        final Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, VAL_MODES));
        l.addView(ev);
        l.addView(sp);
        new AlertDialog.Builder(this).setTitle("Procurar valor em todos os arquivos").setView(l)
            .setPositiveButton("Procurar", (d, w) -> {
                long v;
                try { v = Long.parseLong(ev.getText().toString().trim()); }
                catch (Exception ex) { toast("Digite um número"); return; }
                if (v <= 0) { toast("Use um valor maior que 0"); return; }
                showHits(v, findValue(files, v, sp.getSelectedItemPosition()));
            }).setNegativeButton("Cancelar", null).show();
    }

    void showHits(long v, final ArrayList<Hit> hs) {
        if (hs.isEmpty()) { toast("Valor " + v + " não encontrado"); return; }
        String[] it = new String[hs.size()];
        for (int i = 0; i < it.length; i++) {
            Hit h = hs.get(i);
            String t = h.text ? "texto" : (h.size * 8) + "b" + (h.size == 1 ? "" : h.le ? " LE" : " BE");
            String hv = h.text ? "" : "  0x" + String.format("%0" + (h.size * 2) + "X", v);
            it[i] = "[" + t + "] " + h.name + " @" + h.off + hv;
        }
        new AlertDialog.Builder(this).setTitle(hs.size() + (hs.size() >= MAXH ? "+" : "") + " resultado(s) para " + v)
            .setItems(it, (d, w) -> editHits(hs.subList(w, w + 1), "Editar " + hs.get(w).name))
            .setNeutralButton("Editar todos", (d, w) -> editHits(hs, "Editar todos (" + hs.size() + ")"))
            .setNegativeButton("Fechar", null).show();
    }

    void editHits(final List<Hit> hs, String title) {
        final Hit first = hs.get(0);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(8), dp(16), 0);
        final EditText ev = num("Novo valor (decimal)", "" + first.val);
        final TextView tv = new TextView(this);
        tv.setText(hexInfo(first, "" + first.val));
        ev.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable e) { tv.setText(hexInfo(first, e.toString())); }
        });
        l.addView(ev);
        l.addView(tv);
        new AlertDialog.Builder(this).setTitle(title).setView(l)
            .setPositiveButton("Aplicar", (d, w) -> {
                long nv;
                try { nv = Long.parseLong(ev.getText().toString().trim()); }
                catch (Exception ex) { toast("Valor inválido"); return; }
                ArrayList<Hit> sorted = new ArrayList<>(hs);
                Collections.sort(sorted, (a, b) -> a.name.equals(b.name) ? b.off - a.off : a.name.compareTo(b.name));
                HashSet<String> touched = new HashSet<>();
                int ok = 0, skip = 0;
                for (Hit h : sorted) {
                    if (!fits(h, nv)) { skip++; continue; }
                    files.put(h.name, patch(files.get(h.name), h, nv));
                    touched.add(h.name);
                    ok++;
                }
                for (String n : touched) scan.put(n, probe(files.get(n)));
                refresh();
                toast(ok + " alterado(s)" + (skip > 0 ? ", " + skip + " fora do limite" : "") + ". Use Salvar .jar");
            }).setNegativeButton("Cancelar", null).show();
    }

    byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n); // não fecha: quem chamou fecha (o ZipInputStream precisa continuar aberto)
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
                scan.clear();
                ZipInputStream z = new ZipInputStream(getContentResolver().openInputStream(u));
                ZipEntry e;
                while ((e = z.getNextEntry()) != null) {
                    if (!e.isDirectory()) files.put(e.getName(), readAll(z));
                }
                z.close();
                filter.setText("");
                refresh();
                scanAll(); // testa cada arquivo como imagem assim que o jar abre
            } else if (req == REQ_SAVE) {
                ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(getContentResolver().openOutputStream(u)));
                for (Map.Entry<String, byte[]> en : files.entrySet()) {
                    z.putNextEntry(new ZipEntry(en.getKey()));
                    z.write(en.getValue());
                    z.closeEntry();
                }
                z.close();
                toast("Salvo!");
            } else if (req == REQ_IMPORT && importPv != null) {
                // resolucao original (ate 4096 px no lado maior; reduz so se faltar memoria)
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inScaled = false;
                o.inJustDecodeBounds = true;
                InputStream i1 = getContentResolver().openInputStream(u);
                try { BitmapFactory.decodeStream(i1, null, o); } finally { i1.close(); }
                int ss = 1;
                while (Math.max(o.outWidth, o.outHeight) / ss > 4096) ss *= 2;
                Bitmap ib = null;
                while (ib == null && ss <= 64) {
                    o.inJustDecodeBounds = false;
                    o.inSampleSize = ss;
                    InputStream i2 = getContentResolver().openInputStream(u);
                    try { ib = BitmapFactory.decodeStream(i2, null, o); }
                    catch (OutOfMemoryError oom) { ib = null; ss *= 2; }
                    finally { i2.close(); }
                    if (ib == null && o.outWidth <= 0) break;
                }
                if (ib == null) toast("Não consegui abrir essa imagem");
                else { importPv.setReference(ib); toast("Referência carregada. Desenhe por cima; toque em Mover para reposicionar a imagem"); }
            } else if (req == REQ_REPLACE && replaceTarget != null) {
                InputStream is = getContentResolver().openInputStream(u);
                byte[] nb;
                try { nb = readAll(is); } finally { is.close(); }
                Thumbs.replace(this, replaceTarget, files.get(replaceTarget), nb, r -> { put(replaceTarget, r); refresh(); });
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
        Scan s = scan.get(name);
        if (s == null) { s = probe(data); scan.put(name, s); }
        if (s.type != null) { openImage(name, new Src()); return; }
        String l = name.toLowerCase();
        for (String ext : TEXT_EXT.split(" ")) if (l.endsWith(ext)) { editText(name); return; }
        if (s.embedded > 0) { scanEmbedded(name); return; }
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
                try { put(name, et.getText().toString().getBytes(charset)); }
                catch (Exception ex) { toast("Erro ao codificar"); }
            }).setNegativeButton("Cancelar", null).show();
    }

    // ---------- imagens: arquivo, PNG embutido, RAW ----------
    void scanEmbedded(final String name) {
        final byte[] data = files.get(name);
        final ArrayList<int[]> found = findPng(data, 300);
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
        Scan sc = scan.get(name);
        editImage(name, s, bm, s.kind == 0 && sc != null && sc.type != null ? sc.type : "png");
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
        Button bpng = mk("PNG");
        bpng.setOnClickListener(v -> {
            importPv = pv;
            Intent ii = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            ii.addCategory(Intent.CATEGORY_OPENABLE);
            ii.setType("image/*");
            startActivityForResult(ii, REQ_IMPORT);
        });
        tools.addView(bpng, new LinearLayout.LayoutParams(0, -2, 1));
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
        pal.addView(hex); View.OnClickListener pkl = v -> ColorPicker.show(this, pv.color, c -> { pv.color = c; cur.setBackgroundColor(c); }); Button pk = mk("Paleta"); pk.setOnClickListener(pkl); cur.setOnClickListener(pkl); pal.addView(pk, 1);
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
        // barra da PNG de referencia (so aparece enquanto ha uma referencia)
        final LinearLayout refBar = new LinearLayout(this);
        refBar.setOrientation(LinearLayout.VERTICAL);
        LinearLayout rb1 = new LinearLayout(this);
        final Button rMove = mk("Mover ✓"), rMenos = mk("Tam −"), rMais = mk("Tam +"), rAbove = mk("Abaixo"),
            rPaste = mk("Colar"), rDel = mk("Tirar");
        rMove.setOnClickListener(v -> pv.setRefMove(!pv.refMove));
        rMenos.setOnClickListener(v -> pv.refSize(0.9f));
        rMais.setOnClickListener(v -> pv.refSize(1.1f));
        rAbove.setOnClickListener(v -> pv.setRefAbove(!pv.refAbove));
        rPaste.setOnClickListener(v -> toast(pv.pasteRef() ? "Colada no desenho (pode desfazer)" : "Tamanho fora do limite"));
        rDel.setOnClickListener(v -> pv.removeRef());
        Button[] rbs = { rMove, rMenos, rMais, rAbove, rPaste, rDel };
        for (Button x : rbs) rb1.addView(x, new LinearLayout.LayoutParams(0, -2, 1));
        refBar.addView(rb1);
        LinearLayout rb2 = new LinearLayout(this);
        rb2.setGravity(Gravity.CENTER_VERTICAL);
        final TextView rTv = new TextView(this);
        rTv.setTextSize(11);
        rTv.setText("Opacidade 50%");
        SeekBar rSb = new SeekBar(this);
        rSb.setMax(100);
        rSb.setProgress(50);
        rSb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int p, boolean user) { pv.setRefAlpha(p); rTv.setText("Opacidade " + p + "%"); }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        rb2.addView(rTv, new LinearLayout.LayoutParams(dp(100), -2));
        rb2.addView(rSb, new LinearLayout.LayoutParams(0, -2, 1));
        refBar.addView(rb2);
        refBar.setVisibility(View.GONE);
        pv.onRef = () -> {
            refBar.setVisibility(pv.ref != null ? View.VISIBLE : View.GONE);
            rMove.setText(pv.refMove ? "Mover ✓" : "Mover");
            rAbove.setText(pv.refAbove ? "Acima" : "Abaixo");
        };
        pv.setRefAlpha(50);
        box.addView(refBar);
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
                    put(name, out);
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
