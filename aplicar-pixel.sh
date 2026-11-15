#!/data/data/com.termux/files/usr/bin/bash
# Liga as miniaturas e a substituicao sem borrar no Editor-java. Rode na raiz do repo.
D=app/src/main/java/org/jareditor/app
M=$D/MainActivity.java
P=$D/PixelView.java
[ -f "$M" ] && [ -f "$P" ] && [ -f "$D/Thumbs.java" ] || { echo "ERRO: rode na raiz do Editor-java (com o zip ja extraido)"; exit 1; }
ok=1
if grep -q 'Thumbs.apply' "$M"; then echo "MainActivity: miniaturas ja ligadas"; else
  sed -i 's#\(t\.setText(tag + n + "  (" + data\.length + " B)");\)#\1 Thumbs.apply(MainActivity.this, t, n, data, sc);#' "$M"
  grep -q 'Thumbs.apply' "$M" && echo "MainActivity: miniaturas ligadas" || { echo "ERRO: linha das miniaturas nao encontrada"; ok=0; }
fi
if grep -q 'Thumbs.replace' "$M"; then echo "MainActivity: substituir ja ligado"; else
  sed -i 's#put(replaceTarget, nb);#Thumbs.replace(this, replaceTarget, files.get(replaceTarget), nb, r -> { put(replaceTarget, r); refresh(); });#' "$M"
  grep -q 'Thumbs.replace' "$M" && echo "MainActivity: substituir ligado" || { echo "ERRO: linha de substituir nao encontrada"; ok=0; }
fi
if grep -q 'p.setFilterBitmap(false)' "$P"; then echo "PixelView: ja sem filtro"; else
  sed -i 's#\(gp\.setColor(0x66000000);\)#\1 p.setFilterBitmap(false); p.setAntiAlias(false); p.setDither(false);#' "$P"
  grep -q 'p.setFilterBitmap(false)' "$P" && echo "PixelView: filtro desligado" || { echo "ERRO: linha do PixelView nao encontrada"; ok=0; }
fi
[ "$ok" = 1 ] || exit 1
