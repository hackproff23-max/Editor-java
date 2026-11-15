#!/data/data/com.termux/files/usr/bin/bash
# Liga a paleta de cores no editor de textura. Rode na raiz do repo.
D=app/src/main/java/org/jareditor/app
M=$D/MainActivity.java
[ -f "$M" ] && [ -f "$D/ColorPicker.java" ] || { echo "ERRO: rode na raiz do Editor-java (com o zip ja extraido)"; exit 1; }
if grep -q 'ColorPicker.show' "$M"; then echo "Paleta ja ligada"; exit 0; fi
sed -i 's#pal\.addView(hex);#pal.addView(hex); View.OnClickListener pkl = v -> ColorPicker.show(this, pv.color, c -> { pv.color = c; cur.setBackgroundColor(c); }); Button pk = mk("Paleta"); pk.setOnClickListener(pkl); cur.setOnClickListener(pkl); pal.addView(pk, 1);#' "$M"
grep -q 'ColorPicker.show' "$M" && echo "Paleta ligada" || { echo "ERRO: linha da paleta de cores nao encontrada"; exit 1; }
