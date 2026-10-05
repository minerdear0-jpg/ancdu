#!/usr/bin/env bash
# Тёплый кэш: лучшее из 3 прогонов du -sx и ancdu (по числу потоков).
set -euo pipefail
BIN=${1:?bin}; DIR=${2:?dir}
best() { local b=999999; for _ in 1 2 3; do
  local s=$(date +%s%N); "$@" >/dev/null 2>&1 || true; local e=$(date +%s%N)
  local ms=$(( (e - s) / 1000000 )); (( ms < b )) && b=$ms; done; echo $b; }
du -sx "$DIR" >/dev/null 2>&1 || true
echo "du -sx      $(best du -sx "$DIR") ms"
for th in 1 2 4 8 16; do
  echo "ancdu t=$th $(best "$BIN" --summary --root "$DIR" --threads $th) ms"
done
