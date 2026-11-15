package org.jareditor.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.*;
import java.io.*;
import java.util.*;

// Editor de save: o usuário escolhe a pasta dos saves, o app lê todos os arquivos dela,
// procura o valor atual (moedas, diamantes...) e grava o novo valor (decimal -> hex) direto na pasta.
public class SaveActivity extends Activity {
    static final int REQ_TREE = 11;

    final LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
    final HashMap<String, Uri> uris = new HashMap<>();
    final HashMap<String, Uri> parents = new HashMap<>();
    final HashMap<String, byte[]> origs = new HashMap<>();
    final HashSet<String> backedUp = new HashSet<>();
    ArrayList<MainActivity.Hit> last = new ArrayList<>();
    Uri tree;
    volatile boolean loading;
    TextView info;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        TextView title = new TextView(this);
        title.setText("Editor de save");
        title.setTextSize(22);
        root.addView(title);
        TextView help = new TextView(this);
        help.setText("1) Escolha a pasta dos saves\n2) Procure seu valor atual (ex: 1500 moedas)\n"
            + "3) Edite o valor: digite em decimal e veja o hex\n"
            + "Cada arquivo alterado ganha uma cópia .bak na mesma pasta.\n"
            + "Dica: mude o valor no jogo, salve e use Refinar para achar o endereço certo.");
        help.setPadding(0, dp(8), 0, dp(8));
        root.addView(help);
        Button pick = new Button(this); pick.setText("Escolher pasta dos saves");
        Button reload = new Button(this); reload.setText("Reler pasta");
        Button search = new Button(this); search.setText("Procurar meu valor");
        Button refine = new Button(this); refine.setText("Refinar (valor novo)");
        root.addView(pick);
        root.addView(reload);
        root.addView(search);
        root.addView(refine);
        info = new TextView(this);
        info.setText("Nenhuma pasta escolhida");
        info.setPadding(0, dp(12), 0, 0);
        root.addView(info);
        setContentView(root);

        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_TREE);
        });
        reload.setOnClickListener(v -> load(null));
        search.setOnClickListener(v -> searchDialog());
        refine.setOnClickListener(v -> refineDialog());
    }

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    EditText num(String hint, String val) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(val);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req != REQ_TREE || res != RESULT_OK || data == null || data.getData() == null) return;
        tree = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) {}
        origs.clear();
        backedUp.clear();
        last = new ArrayList<>();
        load(null);
    }

    // ---------- leitura da pasta ----------
    byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        in.close();
        return o.toByteArray();
    }

    void walk(Uri treeUri, String docId, String path, int depth) {
        if (depth > 8 || files.size() >= 3000) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
        Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
        String[] cols = { DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE };
        ArrayList<String[]> dirs = new ArrayList<>();
        try (Cursor c = getContentResolver().query(children, cols, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                long size = c.isNull(3) ? 0 : c.getLong(3);
                String p = path.isEmpty() ? name : path + "/" + name;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    dirs.add(new String[] { id, p });
                } else if (size <= 8000000L && !name.contains(".bak")) {
                    try {
                        Uri u = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                        byte[] d = readAll(getContentResolver().openInputStream(u));
                        files.put(p, d);
                        uris.put(p, u);
                        parents.put(p, parentUri);
                    } catch (Exception e) { /* arquivo ilegível: ignora */ }
                }
            }
        } catch (Exception e) { /* pasta bloqueada pelo Android */ }
        for (String[] dd : dirs) walk(treeUri, dd[0], dd[1], depth + 1);
    }

    void load(final Runnable done) {
        if (tree == null) { toast("Escolha a pasta dos saves primeiro"); return; }
        if (loading) { toast("Ainda lendo a pasta..."); return; }
        loading = true;
        info.setText("Lendo pasta...");
        new Thread(() -> {
            files.clear();
            uris.clear();
            parents.clear();
            try { walk(tree, DocumentsContract.getTreeDocumentId(tree), "", 0); } catch (Exception e) {}
            loading = false;
            runOnUiThread(() -> {
                long total = 0;
                for (byte[] d : files.values()) total += d.length;
                info.setText("Pasta: " + tree.getLastPathSegment() + "\n" + files.size() + " arquivo(s), " + (total / 1024) + " KB lidos");
                if (files.isEmpty()) toast("Nenhum arquivo lido. Pastas em Android/data podem ser bloqueadas pelo Android.");
                if (done != null) done.run();
            });
        }).start();
    }

    // ---------- busca ----------
    void searchDialog() {
        if (loading) { toast("Ainda lendo a pasta..."); return; }
        if (files.isEmpty()) { toast("Escolha uma pasta com arquivos primeiro"); return; }
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(8), dp(16), 0);
        final EditText ev = num("Valor atual (decimal), ex: 1500", "");
        final Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, MainActivity.VAL_MODES));
        l.addView(ev);
        l.addView(sp);
        new AlertDialog.Builder(this).setTitle("Procurar valor nos saves").setView(l)
            .setPositiveButton("Procurar", (d, w) -> {
                long v;
                try { v = Long.parseLong(ev.getText().toString().trim()); }
                catch (Exception ex) { toast("Digite um número"); return; }
                if (v <= 0) { toast("Use um valor maior que 0"); return; }
                last = MainActivity.findValue(files, v, sp.getSelectedItemPosition());
                showHits(v, last);
            }).setNegativeButton("Cancelar", null).show();
    }

    static boolean regionEq(byte[] d, int off, byte[] p) {
        if (off < 0 || off + p.length > d.length) return false;
        for (int i = 0; i < p.length; i++) if (d[off + i] != p[i]) return false;
        return true;
    }

    // Depois de mudar o valor no jogo e salvar: relê a pasta e mantém só os endereços que agora têm o valor novo
    void refineDialog() {
        if (last.isEmpty()) { toast("Faça uma busca primeiro"); return; }
        final EditText ev = num("Valor NOVO que está no jogo agora", "");
        new AlertDialog.Builder(this).setTitle("Refinar busca").setView(ev)
            .setPositiveButton("Refinar", (d, w) -> {
                final long nv;
                try { nv = Long.parseLong(ev.getText().toString().trim()); }
                catch (Exception ex) { toast("Digite um número"); return; }
                if (nv <= 0) { toast("Use um valor maior que 0"); return; }
                load(() -> {
                    ArrayList<MainActivity.Hit> keep = new ArrayList<>();
                    byte[] t = Long.toString(nv).getBytes();
                    for (MainActivity.Hit h : last) {
                        byte[] f = files.get(h.name);
                        if (f == null) continue;
                        if (h.text) {
                            if (regionEq(f, h.off, t)) keep.add(MainActivity.hit(h.name, h.off, t.length, false, true, nv));
                        } else if (MainActivity.fits(h, nv) && regionEq(f, h.off, MainActivity.toBytes(nv, h.size, h.le))) {
                            keep.add(MainActivity.hit(h.name, h.off, h.size, h.le, false, nv));
                        }
                    }
                    last = keep;
                    showHits(nv, keep);
                });
            }).setNegativeButton("Cancelar", null).show();
    }

    void showHits(long v, final ArrayList<MainActivity.Hit> hs) {
        if (hs.isEmpty()) { toast("Nenhum endereço com o valor " + v); return; }
        String[] it = new String[hs.size()];
        for (int i = 0; i < it.length; i++) {
            MainActivity.Hit h = hs.get(i);
            String t = h.text ? "texto" : (h.size * 8) + "b" + (h.size == 1 ? "" : h.le ? " LE" : " BE");
            String hv = h.text ? "" : "  0x" + String.format("%0" + (h.size * 2) + "X", v);
            it[i] = "[" + t + "] " + h.name + " @" + h.off + hv;
        }
        new AlertDialog.Builder(this).setTitle(hs.size() + (hs.size() >= MainActivity.MAXH ? "+" : "") + " resultado(s) para " + v)
            .setItems(it, (d, w) -> editHits(hs.subList(w, w + 1), "Editar " + hs.get(w).name))
            .setNeutralButton("Editar todos", (d, w) -> editHits(hs, "Editar todos (" + hs.size() + ")"))
            .setNegativeButton("Fechar", null).show();
    }

    // ---------- edição: decimal -> hex, grava na pasta ----------
    void editHits(final List<MainActivity.Hit> hs, String title) {
        final MainActivity.Hit first = hs.get(0);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(8), dp(16), 0);
        final EditText ev = num("Novo valor (decimal)", "" + first.val);
        final TextView tv = new TextView(this);
        tv.setText(MainActivity.hexInfo(first, "" + first.val));
        ev.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable e) { tv.setText(MainActivity.hexInfo(first, e.toString())); }
        });
        l.addView(ev);
        l.addView(tv);
        new AlertDialog.Builder(this).setTitle(title).setView(l)
            .setPositiveButton("Gravar no save", (d, w) -> {
                long nv;
                try { nv = Long.parseLong(ev.getText().toString().trim()); }
                catch (Exception ex) { toast("Valor inválido"); return; }
                apply(hs, nv);
            }).setNegativeButton("Cancelar", null).show();
    }

    void apply(List<MainActivity.Hit> hs, long nv) {
        ArrayList<MainActivity.Hit> sorted = new ArrayList<>(hs);
        // do fim para o começo, para não deslocar os offsets quando o texto muda de tamanho
        Collections.sort(sorted, (a, b) -> a.name.equals(b.name) ? b.off - a.off : a.name.compareTo(b.name));
        LinkedHashSet<String> touched = new LinkedHashSet<>();
        int skip = 0;
        for (MainActivity.Hit h : sorted) {
            if (!MainActivity.fits(h, nv)) { skip++; continue; }
            if (!origs.containsKey(h.name)) origs.put(h.name, files.get(h.name));
            files.put(h.name, MainActivity.patch(files.get(h.name), h, nv));
            touched.add(h.name);
        }
        int saved = 0, fail = 0;
        for (String n : touched) { if (writeFile(n)) saved++; else fail++; }
        last = new ArrayList<>(); // offsets podem ter mudado: busque de novo
        toast(saved + " arquivo(s) gravado(s)" + (fail > 0 ? ", " + fail + " com erro" : "")
            + (skip > 0 ? ", " + skip + " fora do limite" : "") + ". Backup: .bak");
    }

    boolean writeFile(String n) {
        try {
            if (!backedUp.contains(n)) {
                backedUp.add(n);
                try {
                    String base = n.substring(n.lastIndexOf('/') + 1);
                    Uri bk = DocumentsContract.createDocument(getContentResolver(), parents.get(n), "application/octet-stream", base + ".bak");
                    if (bk != null) {
                        OutputStream bo = getContentResolver().openOutputStream(bk);
                        bo.write(origs.get(n));
                        bo.close();
                    }
                } catch (Exception e) { /* sem backup, segue */ }
            }
            OutputStream o = getContentResolver().openOutputStream(uris.get(n), "wt");
            o.write(files.get(n));
            o.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
