#!/usr/bin/env bash
# Полный прогон: обычная сборка, ASan+UBSan, TSan, сверка с du.
set -euo pipefail
cd "$(dirname "$0")"
run() {
  local dir=$1 san=$2
  cmake -S . -B "$dir" -G Ninja -DCMAKE_BUILD_TYPE=RelWithDebInfo \
        -DCMAKE_C_COMPILER=clang -DANCDU_SANITIZE="$san" >/dev/null
  cmake --build "$dir"
  ctest --test-dir "$dir" --output-on-failure
}
run build ""
ASAN_OPTIONS=detect_leaks=1 run build-asan "address,undefined"
TSAN_OPTIONS=halt_on_error=1 run build-tsan "thread"
./du_compare.sh build/core/libancdu_scan.so
ASAN_OPTIONS=detect_leaks=1 ./du_compare.sh build-asan/core/libancdu_scan.so
echo "ALL GREEN"
