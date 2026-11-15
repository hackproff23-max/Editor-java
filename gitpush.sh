#!/data/data/com.termux/files/usr/bin/bash
# Uso (1a vez): bash gitpush.sh https://github.com/USUARIO/REPO.git
# Depois:       bash gitpush.sh
# Opcoes: MSG="texto do commit"  FORCE=1 (sobrescreve o remoto)
set -e
cd "$(dirname "$0")"
command -v git >/dev/null || pkg install -y git
[ -d .git ] || git init -b main
if [ -n "$1" ]; then
  git remote remove origin 2>/dev/null || true
  git remote add origin "$1"
fi
git add -A
git commit -m "${MSG:-atualizacao}" || true
if [ "$FORCE" = "1" ]; then
  git push -u origin main --force
else
  git push -u origin main
fi
echo "Enviado! Acompanhe o build na aba Actions do GitHub."
