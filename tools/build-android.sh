#!/usr/bin/env bash
# Сборка нативных целей под ABI; с OUT — копирует lib*.so в OUT/<abi>/.
set -euo pipefail
NDK=${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/27.0.12077973}
ABI=${1:-arm64-v8a}
OUT=${2:-}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
B="$ROOT/build-android-$ABI"
cmake -S "$ROOT/app/src/main/cpp" -B "$B" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-30 -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build "$B"
if [[ -n "$OUT" ]]; then
  mkdir -p "$OUT/$ABI"
  find "$B" -maxdepth 1 -name 'lib*.so' -exec cp -f {} "$OUT/$ABI/" \;
  ls "$OUT/$ABI"
else
  file "$B/libancdu_scan.so"
fi
