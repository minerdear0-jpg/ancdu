#!/usr/bin/env bash
# Полный прогон: обычная сборка, ASan+UBSan, TSan, сверка с du.
set -euo pipefail
cd "$(dirname "$0")"
# Тесты не зависят от cwd: все фикстуры (и всё, что удаляется) — только внутри mk_tmp().
# Сверх того каждый прогон — в свежей песочнице: TMPDIR (база mk_tmp) и cwd каждого теста —
# пустые каталоги внутри одного mkdtemp, удаляемого в конце.
SANDBOX=$(mktemp -d "${TMPDIR:-/tmp}/ancdu-run-XXXXXX")
case $SANDBOX in /*) ;; *) echo "sandbox path not absolute: $SANDBOX" >&2; exit 2 ;; esac
trap 'chmod -R u+rwx "$SANDBOX" 2>/dev/null; rm -rf -- "$SANDBOX"' EXIT
mkdir "$SANDBOX/tmp" "$SANDBOX/cwd"
export TMPDIR=$SANDBOX/tmp
run() {
  local dir=$1 san=$2
  cmake -S . -B "$dir" -G Ninja -DCMAKE_BUILD_TYPE=RelWithDebInfo \
        -DCMAKE_C_COMPILER=clang -DANCDU_SANITIZE="$san" -DANCDU_TEST_CWD="$SANDBOX/cwd" >/dev/null
  cmake --build "$dir"
  ctest --test-dir "$dir" --output-on-failure
}
run build ""
ASAN_OPTIONS=detect_leaks=1 run build-asan "address,undefined"
TSAN_OPTIONS=halt_on_error=1 run build-tsan "thread"
./du_compare.sh build/core/libancdu_scan.so
ASAN_OPTIONS=detect_leaks=1 ./du_compare.sh build-asan/core/libancdu_scan.so
echo "ALL GREEN"
