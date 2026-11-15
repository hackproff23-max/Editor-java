package org.jareditor.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
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
 * Editor de nomes (jogadores/times/selecoes...) para PES Java ME.
 * Procura tabelas de nomes dentro do .jar por padrao estrutural (sem depender de um formato fixo):
 *  0 = campos fixos (nome + zeros, passo constante)   -> edita ate o limite do campo
 *  1 = strings UTF com tamanho de 2 bytes (readUTF)   -> tamanho livre
 *  2 = strings terminadas em zero, em sequencia        -> tamanho livre
 *  3 = arquivo de texto, uma linha por nome            -> tamanho livre
 *  4 = [tam. 1 byte][nome UTF-8][N bytes de dados], em sequência (PES: jogadores e times)
 *      -> tamanho livre; nome menor é completado com espaço para não deslocar o arquivo
 * Cada tabela e classificada sozinha (PesClassifier) em Jogadores, Times, Selecoes, Estadios,
 * Campeonatos, Idiomas, Textos do jogo, Outros nomes ou Ocultos (lixo binario). A marca manual
 * (segurar na tabela) vale mais e fica guardada para esse .jar.
 * Nas tabelas de tamanho livre, o nome maior usa primeiro o espaco vazio (zeros) depois da tabela,
 * mantendo o resto do arquivo no mesmo lugar. So desloca o arquivo se voce confirmar.
 */
public class PesActivity extends Activity {
    static final int REQ_OPEN = 11, REQ_SAVE = 12;
    static final String[] KIND = { "campos fixos", "UTF (tam. 2 bytes)", "texto terminado em zero", "linhas de texto", "nome (tam. 1 byte) + dados" };
    static final String PREFS = "pes_marcas";

    static class Ent { int start, cap = -1; byte[] raw, tail; }
    static class Table {
        String file; int kind, begin, end, stride, trail;
        boolean crlf, endsNl;
        String auto = PesClassifier.OUT;
        ArrayList<Ent> ents = new ArrayList<>();
    }

    final LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
    final ArrayList<Table> tables = new ArrayList<>();
    final ArrayList<Table> shown = new ArrayList<>();
    final HashMap<String, String> labels = new HashMap<>();
    final ArrayList<String> tRows = new ArrayList<>(), eRows = new ArrayList<>();
    final ArrayList<Integer> view = new ArrayList<>();
    String jarName = "pes.jar";
    Charset cs = Charset.forName("UTF-8");
    LinearLayout listRoot, entRoot, chipRow;
    TextView info, entInfo;
    ArrayAdapter<String> tAdapter, eAdapter;
    EditText search;
    Table cur;
    boolean sorted, inEntries, pickDefault;
    String filterCat = null; // null = todas as categorias

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
        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipScroll.addView(chipRow);
        listRoot.addView(chipScroll);
        info = new TextView(this);
        info.setText("Abra o .jar do PES. O app procura as tabelas de nomes e separa em Jogadores, Times, Seleções e outras sozinho.");
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
        final Button enc = new Button(this); enc.setText("UTF-8");
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
        tl.setOnItemClickListener((p, v, pos, id) -> openTable(shown.get(pos)));
        tl.setOnItemLongClickListener((p, v, pos, id) -> { labelMenu(shown.get(pos)); return true; });
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
                loadLabels();
                analyze();
                pickDefault = true;
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

    // ---------- marcas manuais (guardadas por .jar) ----------
    void loadLabels() {
        labels.clear();
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String pre = jarName + "|";
        for (Map.Entry<String, ?> en : sp.getAll().entrySet()) {
            if (en.getKey().startsWith(pre) && en.getValue() instanceof String) {
                labels.put(en.getKey().substring(pre.length()), (String) en.getValue());
            }
        }
    }

    void saveLabel(String key, String cat) {
        SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        if (cat == null) ed.remove(jarName + "|" + key); else ed.putString(jarName + "|" + key, cat);
        ed.apply();
    }

    // ---------- análise ----------
    static boolean isP(int b) { return (b >= 0x20 && b < 0x7F) || b >= 0xA0; }

    /** Texto imprimível; aceita bytes 0x80+ (UTF-8: Á = C3 81). */
    static boolean printable(byte[] d, int off, int len) {
        for (int i = off; i < off + len; i++) {
            int c = d[i] & 255;
            if (c < 0x20 || c == 0x7F) return false;
        }
        return true;
    }

    // ---------- tabela [tam. 1 byte][nome][N bytes de dados] (PES: jogadores e times) ----------
    static boolean nameAt(byte[] d, int p, int minLen) {
        if (p < 0 || p >= d.length) return false;
        int len = d[p] & 255;
        if (len < minLen || len > 40 || p + 1 + len > d.length) return false;
        int letters = 0;
        for (int i = p + 1; i <= p + len; i++) {
            int c = d[i] & 255;
            if (c < 0x20 || c == 0x7F) return false;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c >= 0xC3) letters++;
        }
        return letters >= 1;
    }

    /** O registro em q é válido e depois dele ainda vem outro com dados de tamanho parecido (T ± 4). */
    static boolean nextOk(byte[] d, int q, int T) {
        int e = q + 1 + (d[q] & 255);
        if (e + T >= d.length) return true;
        for (int t = Math.max(0, T - 4); t <= T + 4; t++) if (nameAt(d, e + t, 1)) return true;
        return false;
    }

    /** Segue a cadeia a partir de i. Cada item de out = {posição do byte de tamanho, bytes de dados}. Devolve o fim. */
    static int chainU8(byte[] d, boolean[] used, int i, int T, boolean resync, ArrayList<int[]> out) {
        int n = d.length, p = i;
        int[] dls = { -1, 1, -2, 2, -3, 3, -4, 4 };
        while (p < n && !used[p] && nameAt(d, p, 1)) {
            int e = p + 1 + (d[p] & 255), nxt = e + T;
            if (nxt >= n) { out.add(new int[] { p, n - e }); return n; }
            if (nameAt(d, nxt, 1) && !used[nxt]) { out.add(new int[] { p, T }); p = nxt; continue; }
            int fnd = -1, fdl = 0;
            if (resync) {
                for (int dl : dls) {
                    int q = nxt + dl;
                    if (T + dl < 0 || q < e || q >= n || used[q] || !nameAt(d, q, 1)) continue;
                    if (nextOk(d, q, T)) { fnd = q; fdl = dl; break; }
                }
            }
            if (fnd >= 0) { out.add(new int[] { p, T + fdl }); p = fnd; continue; }
            out.add(new int[] { p, T });
            return nxt;
        }
        return p;
    }

    /** Aceita a cadeia se quase todos os dados têm o mesmo tamanho e a maioria dos nomes parece texto. */
    static boolean goodU8(byte[] d, ArrayList<int[]> es, int T) {
        int var = 0, ok = 0;
        for (int[] r : es) {
            if (r[1] != T) var++;
            int l = d[r[0]] & 255, lt = 0;
            for (int i = r[0] + 1; i <= r[0] + l; i++) {
                int c = d[i] & 255;
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c >= 0xC3 || c == 32) lt++;
            }
            if (lt * 2 >= l) ok++;
        }
        return var * 20 <= es.size() && ok * 4 >= es.size() * 3;
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

        // 2b) [tam. 1 byte][nome][N bytes de dados]: jogadores e times do PES
        for (int i = 0; i < d.length; ) {
            int first = d[i] & 255;
            if (used[i] || first > 30 || !nameAt(d, i, 3)) { i++; continue; }
            ArrayList<int[]> best = null;
            int bestT = 0, bestEnd = 0;
            for (int T = 2; T < 256; T++) {
                for (int rs = 1; rs >= 0; rs--) {
                    ArrayList<int[]> es = new ArrayList<>();
                    int end = chainU8(d, used, i, T, rs == 1, es);
                    if (es.size() >= 8 && goodU8(d, es, T)) {
                        if (best == null || es.size() > best.size()) { best = es; bestT = T; bestEnd = end; }
                        break;
                    }
                }
            }
            if (best == null) { i++; continue; }
            Table t = new Table();
            t.file = name; t.kind = 4; t.begin = i; t.end = bestEnd; t.trail = bestT;
            for (int[] r : best) {
                int len = d[r[0]] & 255, e0 = r[0] + 1 + len;
                Ent en = new Ent();
                en.start = r[0] + 1;
                en.raw = Arrays.copyOfRange(d, r[0] + 1, e0);
                en.tail = Arrays.copyOfRange(d, e0, e0 + r[1]);
                t.ents.add(en);
            }
            tables.add(t);
            for (int k = i; k < bestEnd; k++) used[k] = true;
            i = bestEnd;
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

    /** Categoria valida da tabela: marca manual (se houver) ou a automatica. */
    String cat(Table t) {
        String lb = labels.get(key(t));
        return lb != null ? lb : t.auto;
    }

    void classifyAll() {
        for (Table t : tables) {
            ArrayList<String> names = new ArrayList<>();
            for (Ent e : t.ents) names.add(dec(e));
            t.auto = PesClassifier.classify(names, t.kind == 4 ? t.trail : 0);
        }
    }

    void refreshTables() {
        classifyAll();
        // contagem por categoria
        LinkedHashMap<String, int[]> cnt = new LinkedHashMap<>(); // {tabelas, nomes}
        for (String c : PesClassifier.ALL) cnt.put(c, new int[2]);
        for (Table t : tables) {
            int[] a = cnt.get(cat(t));
            if (a == null) { a = new int[2]; cnt.put(cat(t), a); }
            a[0]++;
            a[1] += t.ents.size();
        }
        if (pickDefault) { // ao abrir um .jar: ja mostra Jogadores (ou Times, ou tudo)
            pickDefault = false;
            filterCat = null;
            for (String c : new String[] { PesClassifier.JOG, PesClassifier.TIM, PesClassifier.SEL }) {
                if (cnt.get(c)[0] > 0) { filterCat = c; break; }
            }
        }
        if (filterCat != null && (cnt.get(filterCat) == null || cnt.get(filterCat)[0] == 0)) filterCat = null;

        // botoes de categoria
        chipRow.removeAllViews();
        addChip("Tudo " + tables.size(), null);
        for (Map.Entry<String, int[]> en : cnt.entrySet()) {
            if (en.getValue()[0] > 0) addChip(en.getKey() + " " + en.getValue()[0], en.getKey());
        }

        // tabelas da categoria escolhida (maiores primeiro; em "Tudo", agrupadas por categoria)
        shown.clear();
        for (Table t : tables) if (filterCat == null || filterCat.equals(cat(t))) shown.add(t);
        Collections.sort(shown, (a, b) -> {
            int ca = PesClassifier.order(cat(a)), cb = PesClassifier.order(cat(b));
            return ca != cb ? ca - cb : b.ents.size() - a.ents.size();
        });

        tRows.clear();
        for (Table t : shown) {
            boolean manual = labels.containsKey(key(t));
            StringBuilder sm = new StringBuilder();
            for (int i = 0; i < Math.min(3, t.ents.size()); i++) sm.append(i > 0 ? ", " : "").append(dec(t.ents.get(i)));
            tRows.add("[" + cat(t) + (manual ? " ✎" : "") + "] " + t.file + " @" + t.begin + "\n"
                + KIND[t.kind] + " · " + t.ents.size() + " nomes" + (t.kind == 0 ? " · passo " + t.stride : t.kind == 4 ? " · " + t.trail + " bytes de dados por nome" : "") + "\n" + sm);
        }
        tAdapter.notifyDataSetChanged();
        int[] sel = filterCat == null ? null : cnt.get(filterCat);
        info.setText(jarName + ": " + tables.size() + " tabelas · "
            + (sel == null ? "todas as categorias" : filterCat + ": " + sel[0] + " tabela(s), " + sel[1] + " nomes")
            + ".\nToque = abrir. Segurar = corrigir a categoria (✎ = marcada por você).");
    }

    void addChip(String text, final String cat) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12);
        boolean on = (cat == null && filterCat == null) || (cat != null && cat.equals(filterCat));
        b.setAlpha(on ? 1f : 0.55f);
        b.setOnClickListener(v -> { filterCat = cat; refreshTables(); });
        chipRow.addView(b, new LinearLayout.LayoutParams(-2, dp(42)));
    }

    void labelMenu(final Table t) {
        final String[] cats = PesClassifier.ALL;
        String[] it = new String[cats.length + 1];
        System.arraycopy(cats, 0, it, 0, cats.length);
        it[cats.length] = "Automático (remover marca)";
        new AlertDialog.Builder(this).setTitle(t.file + " @" + t.begin + "\nAgora: " + cat(t)).setItems(it, (d, w) -> {
            if (w == cats.length) { labels.remove(key(t)); saveLabel(key(t), null); }
            else { labels.put(key(t), cats[w]); saveLabel(key(t), cats[w]); }
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
        entInfo.setText("[" + cat(cur) + "] " + cur.file + " @" + cur.begin + " · " + KIND[cur.kind]
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
                    if (cur.kind == 4) {
                        if (nb.length > 40) { toast("Máximo 40 bytes"); return; }
                        if (nb.length < e.raw.length) { // mantém o tamanho: completa com espaço
                            byte[] pd = new byte[e.raw.length];
                            Arrays.fill(pd, (byte) 32);
                            System.arraycopy(nb, 0, pd, 0, nb.length);
                            nb = pd;
                        }
                    }
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
            else if (t.kind == 4) { byte[] tl = t.ents.get(i).tail; o.write(r.length); o.write(r, 0, r.length); o.write(tl, 0, tl.length); }
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
