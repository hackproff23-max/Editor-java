package org.jareditor.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.zip.*;

/**
 * Editor de nomes (jogadores/times) para PES Java ME.
 * Procura tabelas de nomes dentro do .jar por padrao estrutural (sem depender de um formato fixo):
 *  0 = campos fixos (nome + zeros, passo constante)   -> edita ate o limite do campo
 *  1 = strings UTF com tamanho de 2 bytes (readUTF)   -> tamanho livre
 *  2 = strings terminadas em zero, em sequencia        -> tamanho livre
 *  3 = arquivo de texto, uma linha por nome            -> tamanho livre
 * Nas tabelas de tamanho livre, o nome maior usa primeiro o espaco vazio (zeros) depois da tabela,
 * mantendo o resto do arquivo no mesmo lugar. So desloca o arquivo se voce confirmar.
 */
public class PesActivity extends Activity {
    static final int REQ_OPEN = 11, REQ_SAVE = 12;
    static final String[] KIND = { "campos fixos", "UTF (tam. 2 bytes)", "texto terminado em zero", "linhas de texto" };

    static class Ent { int start, cap = -1; byte[] raw; }
    static class Table {
        String file; int kind, begin, end, stride;
        boolean crlf, endsNl;
        ArrayList<Ent> ents = new ArrayList<>();
    }

    final LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
    final ArrayList<Table> tables = new ArrayList<>();
    final HashMap<String, String> labels = new HashMap<>();
    final ArrayList<String> tRows = new ArrayList<>(), eRows = new ArrayList<>();
    final ArrayList<Integer> view = new ArrayList<>();
    String jarName = "pes.jar";
    Charset cs = Charset.forName("ISO-8859-1");
    LinearLayout listRoot, entRoot;
    TextView info, entInfo;
    ArrayAdapter<String> tAdapter, eAdapter;
    EditText search;
    Table cur;
    boolean sorted, inEntries;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = dp(12);

        listRoot = new LinearLayout(this);
        listRoot.setOrientation(LinearLayout.VERTICAL);
        listRoot.setPadding(pad, pad, pad, pad);
        LinearLayout row = new LinearLayout(this);
        Button open = new Button(this); open.setText("Abrir .jar do PES");
        Button save = new Button(this); save.setText("Salvar .jar");
        row.addView(open, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(save, new LinearLayout.LayoutParams(0, -2, 1));
        listRoot.addView(row);
        info = new TextView(this);
        info.setText("Abra o .jar do PES. O app procura as tabelas de nomes sozinho.");
        listRoot.addView(info);
        ListView tl = new ListView(this);
        tAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, tRows);
        tl.setAdapter(tAdapter);
        listRoot.addView(tl, new LinearLayout.LayoutParams(-1, 0, 1));

        entRoot = new LinearLayout(this);
        entRoot.setOrientation(LinearLayout.VERTICAL);
        entRoot.setPadding(pad, pad, pad, pad);
        LinearLayout r2 = new LinearLayout(this);
        Button back = new Button(this); back.setText("Voltar");
        final Button srt = new Button(this); srt.setText("Ordem A–Z");
        final Button enc = new Button(this); enc.setText("ISO-8859-1");
        r2.addView(back, new LinearLayout.LayoutParams(0, -2, 1));
        r2.addView(srt, new LinearLayout.LayoutParams(0, -2, 1));
        r2.addView(enc, new LinearLayout.LayoutParams(0, -2, 1));
        entRoot.addView(r2);
        entInfo = new TextView(this);
        entInfo.setTextSize(12);
        entRoot.addView(entInfo);
        search = new EditText(this);
        search.setHint("Buscar nome");
        search.setSingleLine(true);
        entRoot.addView(search);
        ListView el = new ListView(this);
        eAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, eRows);
        el.setAdapter(eAdapter);
        entRoot.addView(el, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(listRoot);

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
        tl.setOnItemClickListener((p, v, pos, id) -> openTable(tables.get(pos)));
        tl.setOnItemLongClickListener((p, v, pos, id) -> { labelMenu(tables.get(pos)); return true; });
        back.setOnClickListener(v -> showList());
        srt.setOnClickListener(v -> {
            sorted = !sorted;
            srt.setText(sorted ? "Ordem original" : "Ordem A–Z");
            rebuildView();
        });
        enc.setOnClickListener(v -> {
            boolean iso = cs.name().equalsIgnoreCase("ISO-8859-1");
            cs = Charset.forName(iso ? "UTF-8" : "ISO-8859-1");
            enc.setText(iso ? "UTF-8" : "ISO-8859-1");
            rebuildView();
            refreshTables();
        });
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            public void afterTextChanged(Editable e) { rebuildView(); }
        });
        el.setOnItemClickListener((p, v, pos, id) -> editEntry(view.get(pos)));
    }

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override public void onBackPressed() {
        if (inEntries) showList(); else super.onBackPressed();
    }

    void showList() { inEntries = false; setContentView(listRoot); }

    // ---------- abrir / salvar ----------
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
        return "pes.jar";
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
                while ((e = z.getNextEntry()) != null) if (!e.isDirectory()) files.put(e.getName(), readAll(z));
                z.close();
                analyze();
                refreshTables();
            } else if (req == REQ_SAVE) {
                ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(getContentResolver().openOutputStream(u)));
                for (Map.Entry<String, byte[]> en : files.entrySet()) {
                    z.putNextEntry(new ZipEntry(en.getKey()));
                    z.write(en.getValue());
                    z.closeEntry();
                }
                z.close();
                toast("Salvo!");
            }
        } catch (Exception ex) {
            toast("Erro: " + ex.getMessage());
        }
    }

    // ---------- análise ----------
    static boolean isP(int b) { return (b >= 0x20 && b < 0x7F) || b >= 0xA0; }

    static boolean printable(byte[] d, int off, int len) {
        for (int i = off; i < off + len; i++) if (!isP(d[i] & 255)) return false;
        return true;
    }

    static boolean isText(byte[] d) {
        if (d.length < 50) return false;
        int good = 0;
        for (byte x : d) {
            int c = x & 255;
            if (isP(c) || c == '\n' || c == '\r' || c == '\t') good++;
        }
        return good >= d.length * 0.93;
    }

    void analyze() {
        tables.clear();
        for (Map.Entry<String, byte[]> en : files.entrySet()) {
            String n = en.getKey(), l = n.toLowerCase();
            byte[] d = en.getValue();
            if (l.endsWith(".class") || l.endsWith(".mf") || l.endsWith(".mid") || l.endsWith(".mmf")
                || l.endsWith(".amr") || l.endsWith(".wav") || MainActivity.imgType(d) != null) continue;
            if (d.length < 64 || d.length > 3000000) continue;
            scanFile(n, d);
        }
        Collections.sort(tables, (a, b) -> b.ents.size() - a.ents.size());
    }

    Table linesTable(String name, byte[] d) {
        ArrayList<Ent> es = new ArrayList<>();
        int s = 0;
        boolean crlf = false;
        for (int i = 0; i <= d.length; i++) {
            if (i == d.length || d[i] == '\n') {
                if (i == d.length && s == d.length) break;
                int e = i;
                if (e > s && d[e - 1] == '\r') { e--; crlf = true; }
                Ent en = new Ent();
                en.start = s;
                en.raw = Arrays.copyOfRange(d, s, e);
                es.add(en);
                s = i + 1;
            }
        }
        if (es.size() < 8) return null;
        Table t = new Table();
        t.file = name; t.kind = 3; t.begin = 0; t.end = d.length; t.ents = es;
        t.crlf = crlf;
        t.endsNl = d[d.length - 1] == '\n';
        return t;
    }

    void scanFile(String name, byte[] d) {
        if (isText(d)) {
            Table t = linesTable(name, d);
            if (t != null) { tables.add(t); return; }
        }
        boolean[] used = new boolean[d.length];

        // 1) strings UTF: [tam 2 bytes][bytes] em sequência
        for (int i = 0; i + 3 < d.length; ) {
            int p = i;
            ArrayList<Ent> es = new ArrayList<>();
            while (p + 2 <= d.length) {
                int len = ((d[p] & 255) << 8) | (d[p + 1] & 255);
                if (len < 2 || len > 40 || p + 2 + len > d.length || used[p] || !printable(d, p + 2, len)) break;
                Ent e = new Ent();
                e.start = p + 2;
                e.raw = Arrays.copyOfRange(d, p + 2, p + 2 + len);
                es.add(e);
                p += 2 + len;
            }
            if (es.size() >= 8) {
                Table t = new Table();
                t.file = name; t.kind = 1; t.begin = i; t.end = p; t.ents = es;
                tables.add(t);
                for (int k = i; k < p; k++) used[k] = true;
                i = p;
            } else i++;
        }

        // 2) strings terminadas em zero, coladas uma na outra
        for (int i = 0; i < d.length; ) {
            if (used[i] || !(i == 0 || d[i - 1] == 0)) { i++; continue; }
            int p = i;
            ArrayList<Ent> es = new ArrayList<>();
            while (p < d.length && !used[p]) {
                int q = p;
                while (q < d.length && q - p <= 40 && isP(d[q] & 255)) q++;
                int len = q - p;
                if (len < 2 || len > 40 || q >= d.length || d[q] != 0) break;
                Ent e = new Ent();
                e.start = p;
                e.raw = Arrays.copyOfRange(d, p, q);
                es.add(e);
                p = q + 1;
            }
            if (es.size() >= 8) {
                Table t = new Table();
                t.file = name; t.kind = 2; t.begin = i; t.end = p; t.ents = es;
                tables.add(t);
                for (int k = i; k < p; k++) used[k] = true;
                i = p;
            } else i++;
        }

        // 3) campos fixos: nome + zeros, repetindo com passo constante
        ArrayList<int[]> runs = new ArrayList<>(); // inicio, tamanho, espaço até o próximo byte != 0
        for (int i = 0; i < d.length; ) {
            if (used[i] || !isP(d[i] & 255) || !(i == 0 || !isP(d[i - 1] & 255))) { i++; continue; }
            int q = i;
            while (q < d.length && q - i <= 40 && isP(d[q] & 255)) q++;
            int len = q - i;
            if (len >= 3 && len <= 40 && q < d.length && d[q] == 0) {
                int r = q;
                while (r < d.length && d[r] == 0) r++;
                runs.add(new int[] { i, len, r - i });
                i = q;
            } else i = Math.max(q, i + 1);
        }
        int m = runs.size();
        HashMap<Integer, Integer> idx = new HashMap<>();
        for (int k = 0; k < m; k++) idx.put(runs.get(k)[0], k);
        boolean[] taken = new boolean[m];
        for (int k = 0; k < m; k++) {
            if (taken[k]) continue;
            for (int j = k + 1; j < m && j < k + 4; j++) {
                int stride = runs.get(j)[0] - runs.get(k)[0];
                if (stride < 4 || stride > 512) continue;
                ArrayList<Integer> ks = new ArrayList<>();
                ks.add(k);
                int pos = runs.get(k)[0];
                while (true) {
                    Integer nk = idx.get(pos + stride);
                    if (nk == null || taken[nk]) break;
                    ks.add(nk);
                    pos += stride;
                }
                if (ks.size() >= 8) {
                    Table t = new Table();
                    t.file = name; t.kind = 0; t.stride = stride;
                    t.begin = runs.get(k)[0];
                    t.end = Math.min(d.length, pos + stride);
                    for (int kk : ks) {
                        int[] r = runs.get(kk);
                        Ent e = new Ent();
                        e.start = r[0];
                        e.raw = Arrays.copyOfRange(d, r[0], r[0] + r[1]);
                        e.cap = Math.min(r[2], stride) - 1;
                        t.ents.add(e);
                        taken[kk] = true;
                    }
                    tables.add(t);
                    break;
                }
            }
        }
    }

    // ---------- listas ----------
    String key(Table t) { return t.file + "@" + t.begin; }

    String dec(Ent e) { return new String(e.raw, cs); }

    void refreshTables() {
        tRows.clear();
        for (Table t : tables) {
            String lb = labels.get(key(t));
            StringBuilder sm = new StringBuilder();
            for (int i = 0; i < Math.min(3, t.ents.size()); i++) sm.append(i > 0 ? ", " : "").append(dec(t.ents.get(i)));
            tRows.add((lb != null ? "[" + lb + "] " : "") + t.file + " @" + t.begin + "\n"
                + KIND[t.kind] + " · " + t.ents.size() + " nomes" + (t.kind == 0 ? " · passo " + t.stride : "") + "\n" + sm);
        }
        tAdapter.notifyDataSetChanged();
        info.setText(jarName + ": " + tables.size() + " tabelas de nomes (maiores primeiro). Toque = abrir, segurar = marcar como Jogadores/Times.");
    }

    void labelMenu(final Table t) {
        final String[] it = { "Jogadores", "Times", "Outro", "Remover marca" };
        new AlertDialog.Builder(this).setTitle(t.file).setItems(it, (d, w) -> {
            if (w == 3) labels.remove(key(t)); else labels.put(key(t), it[w]);
            refreshTables();
        }).show();
    }

    int slackOf(Table t) {
        if (t.kind == 3) return 0;
        byte[] f = files.get(t.file);
        int p = t.end;
        while (p < f.length && f[p] == 0) p++;
        return p - t.end;
    }

    void openTable(Table t) {
        cur = t;
        inEntries = true;
        sorted = false;
        search.setText("");
        setContentView(entRoot);
        rebuildView();
    }

    void rebuildView() {
        if (cur == null) return;
        String q = search.getText().toString().toLowerCase();
        view.clear();
        for (int i = 0; i < cur.ents.size(); i++) {
            if (q.isEmpty() || dec(cur.ents.get(i)).toLowerCase().contains(q)) view.add(i);
        }
        if (sorted) {
            Collections.sort(view, (a, b) -> dec(cur.ents.get(a)).compareToIgnoreCase(dec(cur.ents.get(b))));
        }
        eRows.clear();
        for (int i : view) {
            Ent e = cur.ents.get(i);
            eRows.add((i + 1) + ".  " + dec(e) + (e.cap >= 0 ? "   [" + e.raw.length + "/" + e.cap + " bytes]" : ""));
        }
        eAdapter.notifyDataSetChanged();
        String lb = labels.get(key(cur));
        entInfo.setText((lb != null ? "[" + lb + "] " : "") + cur.file + " @" + cur.begin + " · " + KIND[cur.kind]
            + (cur.kind == 0 ? " · limite fixo por nome (bytes)" : cur.kind == 3 ? " · tamanho livre"
            : " · tamanho livre; espaço vazio após a tabela: " + slackOf(cur) + " bytes")
            + "\nA ordem A–Z é só de visualização: a posição de cada nome no jogo não muda.");
    }

    // ---------- edição ----------
    void editEntry(final int ei) {
        final Ent e = cur.ents.get(ei);
        final EditText et = new EditText(this);
        et.setText(dec(e));
        et.setSingleLine(true);
        et.setSelection(et.getText().length());
        new AlertDialog.Builder(this)
            .setTitle(cur.kind == 0 ? "Máximo " + e.cap + " bytes" : "Editar nome")
            .setView(et)
            .setPositiveButton("Aplicar", (dd, w) -> {
                String txt = et.getText().toString().replace("\n", " ").replace("\r", " ");
                byte[] nb = txt.getBytes(cs);
                if (nb.length < 1) { toast("O nome não pode ficar vazio"); return; }
                if (cur.kind == 0) {
                    if (nb.length > e.cap) { toast("Máximo " + e.cap + " bytes neste campo"); return; }
                    byte[] f = files.get(cur.file);
                    Arrays.fill(f, e.start, e.start + e.cap + 1, (byte) 0);
                    System.arraycopy(nb, 0, f, e.start, nb.length);
                    e.raw = nb;
                    rebuildView();
                    refreshTables();
                } else {
                    if (nb.length > 255) { toast("Nome grande demais"); return; }
                    commitVar(ei, nb);
                }
            }).setNegativeButton("Cancelar", null).show();
    }

    byte[] serialize(Table t, ArrayList<byte[]> raws) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] nl = t.crlf ? new byte[] { 13, 10 } : new byte[] { 10 };
        for (int i = 0; i < raws.size(); i++) {
            byte[] r = raws.get(i);
            if (t.kind == 1) { o.write(r.length >> 8); o.write(r.length & 255); o.write(r, 0, r.length); }
            else if (t.kind == 2) { o.write(r, 0, r.length); o.write(0); }
            else { o.write(r, 0, r.length); if (i < raws.size() - 1 || t.endsNl) o.write(nl, 0, nl.length); }
        }
        return o.toByteArray();
    }

    static byte[] splice(byte[] f, int b, int e, byte[] seg) {
        byte[] o = new byte[f.length - (e - b) + seg.length];
        System.arraycopy(f, 0, o, 0, b);
        System.arraycopy(seg, 0, o, b, seg.length);
        System.arraycopy(f, e, o, b + seg.length, f.length - e);
        return o;
    }

    void commitVar(int ei, byte[] nb) {
        ArrayList<byte[]> raws = new ArrayList<>();
        for (Ent x : cur.ents) raws.add(x.raw);
        raws.set(ei, nb);
        final byte[] seg = serialize(cur, raws);
        final byte[] f = files.get(cur.file);
        final int begin = cur.begin, end = cur.end;
        int oldLen = end - begin;
        if (cur.kind == 3) {
            afterWrite(splice(f, begin, end, seg));
        } else if (seg.length - oldLen <= slackOf(cur)) {
            // cabe no espaço da tabela + zeros livres depois dela: o resto do arquivo não se mexe
            byte[] out = f.clone();
            Arrays.fill(out, begin, begin + Math.max(oldLen, seg.length), (byte) 0);
            System.arraycopy(seg, 0, out, begin, seg.length);
            afterWrite(out);
        } else {
            new AlertDialog.Builder(this)
                .setMessage("Esse nome passa do espaço livre da tabela. Para caber, o resto do arquivo precisa ser deslocado, e isso pode quebrar o jogo se outros pontos do arquivo ou do código apontam para posições depois da tabela. Continuar?")
                .setPositiveButton("Continuar", (dd, w) -> afterWrite(splice(f, begin, end, seg)))
                .setNegativeButton("Cancelar", null).show();
        }
    }

    void afterWrite(byte[] out) {
        String file = cur.file;
        int begin = cur.begin;
        files.put(file, out);
        analyze();
        refreshTables();
        for (Table t : tables) {
            if (t.file.equals(file) && t.begin == begin) { openTable(t); return; }
        }
        toast("Tabela salva; reanalisada");
        showList();
    }
}
