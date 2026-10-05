#!/usr/bin/env bash
set -euo pipefail
NDK=${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/27.0.12077973}
ABI=${1:-arm64-v8a}
cmake -S app/src/main/cpp -B "build-android-$ABI" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-30 -DCMAKE_BUILD_TYPE=Release
cmake --build "build-android-$ABI" --target ancdu_scan
file "build-android-$ABI/libancdu_scan.so"
