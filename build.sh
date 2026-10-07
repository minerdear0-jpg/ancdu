#!/usr/bin/env bash
# Сборка APK ancdu: проверяет окружение, при нужде ставит NDK/платформу через sdkmanager,
# собирает через Gradle wrapper и кладёт результат в dist/ вместе с SHA-256.
#
#   ./build.sh                 release APK (подписан release-ключом, если он настроен)
#   ./build.sh --debug         debug APK
#   ./build.sh --test          ещё и JVM-тесты перед сборкой
#   ./build.sh --install [-s SERIAL]   поставить собранный APK через adb
#   ./build.sh --clean         чистая сборка
#
# Окружение: JDK 17+, Android SDK (ANDROID_HOME / ANDROID_SDK_ROOT / ~/Android/Sdk),
# cmake ≥ 3.22 и ninja в PATH. Подпись release — см. app/build.gradle.kts (ANCDU_SIGNING
# или ANCDU_KEYSTORE…); без ключа release подписывается debug-ключом.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")" && pwd)
NDK_VERSION=27.0.12077973
PLATFORM=android-34
BUILD_TOOLS=34.0.0

variant=release; run_tests=0; install=0; clean=0; serial=
while (($#)); do
  case $1 in
    --debug) variant=debug ;;
    --release) variant=release ;;
    --test) run_tests=1 ;;
    --install) install=1 ;;
    -s) serial=${2:?-s требует серийный номер}; shift ;;
    --clean) clean=1 ;;
    -h|--help) sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "неизвестный параметр: $1 (см. --help)" >&2; exit 2 ;;
  esac
  shift
done

say() { printf '\033[1;33m▸\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m✗\033[0m %s\n' "$*" >&2; exit 1; }

# --- JDK ---------------------------------------------------------------------
command -v java >/dev/null || die "нет java: поставьте JDK 17+ (например, openjdk-17 / temurin-17)"
jv=$(java -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -1)
[[ ${jv:-0} -ge 17 ]] || die "нужен JDK 17+, найден ${jv:-?}"

# --- Android SDK -------------------------------------------------------------
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z $SDK && -f $ROOT/local.properties ]]; then
  SDK=$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties")
fi
SDK=${SDK:-$HOME/Android/Sdk}
[[ -d $SDK ]] || die "Android SDK не найден: задайте ANDROID_HOME (сейчас ищу в $SDK)"
export ANDROID_HOME=$SDK

sdkmanager=
for c in "$SDK/cmdline-tools/latest/bin/sdkmanager" "$SDK"/cmdline-tools/*/bin/sdkmanager; do
  [[ -x $c ]] && { sdkmanager=$c; break; }
done
need=()
[[ -d $SDK/ndk/$NDK_VERSION ]] || need+=("ndk;$NDK_VERSION")
[[ -d $SDK/platforms/$PLATFORM ]] || need+=("platforms;$PLATFORM")
[[ -d $SDK/build-tools/$BUILD_TOOLS ]] || need+=("build-tools;$BUILD_TOOLS")
if ((${#need[@]})); then
  [[ -n $sdkmanager ]] || die "не хватает ${need[*]}, а sdkmanager (cmdline-tools) не найден в $SDK"
  say "ставлю ${need[*]}"
  yes | "$sdkmanager" --licenses >/dev/null || true
  "$sdkmanager" --install "${need[@]}"
fi
export ANDROID_NDK_HOME=$SDK/ndk/$NDK_VERSION

# local.properties нужен Gradle, если ANDROID_HOME не видно демону
[[ -f $ROOT/local.properties ]] || echo "sdk.dir=$SDK" > "$ROOT/local.properties"

# --- нативный тулчейн (tools/build-android.sh) --------------------------------
command -v cmake >/dev/null || die "нет cmake (≥ 3.22): поставьте пакет cmake"
command -v ninja >/dev/null || die "нет ninja: поставьте пакет ninja / ninja-build"

# --- сборка ------------------------------------------------------------------
cd "$ROOT"
cap=${variant^}
tasks=()
((clean)) && tasks+=(clean)
((run_tests)) && tasks+=(testDebugUnitTest)
tasks+=("assemble$cap")
if [[ $variant == release && -z ${ANCDU_KEYSTORE:-} && ! -f ${ANCDU_SIGNING:-$HOME/.android/ancdu-release.properties} ]]; then
  say "release-ключ не настроен — APK будет подписан debug-ключом (только для своей проверки)"
fi
say "gradle ${tasks[*]}"
./gradlew --console=plain "${tasks[@]}"

name=$(sed -nE 's/.*versionName = "([^"]+)".*/\1/p' app/build.gradle.kts)
src=app/build/outputs/apk/$variant/app-$variant.apk
[[ -f $src ]] || die "APK не найден: $src"
mkdir -p dist
suffix=; [[ $variant == debug ]] && suffix=-debug
out=dist/ancdu-$name$suffix.apk
cp -f "$src" "$out"
(cd dist && sha256sum "$(basename "$out")" > "$(basename "$out").sha256")
say "готово: $out ($(stat -c %s "$out") Б)"
cat "$out.sha256"

if ((install)); then
  command -v adb >/dev/null || PATH=$SDK/platform-tools:$PATH
  say "adb install $out"
  adb ${serial:+-s "$serial"} install -r "$out"
fi
