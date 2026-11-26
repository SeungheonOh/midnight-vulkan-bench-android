#!/usr/bin/env bash
# Builds the optional Android Turnip loader into <output>/arm64-v8a.
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "Usage: ANDROID_NDK_HOME=<ndk> $0 <libadrenotools-checkout> <libvulkan_freedreno.so> <output>" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADRENOTOOLS="$1"
DRIVER="$2"
DRIVER_DIR="$(dirname "$DRIVER")"
OUTPUT="$3"
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to an installed Android NDK}"
CMAKE="${CMAKE:-cmake}"

# https://github.com/bylaws/libadrenotools and its pinned linkernsbypass submodule.
ADRENOTOOLS_REV=8fae8ce254dfc1344527e05301e43f37dea2df80
LINKERNSBYPASS_REV=aa3975893d83ef1bc84c321ec60c65fbf1287887
if [[ "$(git -C "$ADRENOTOOLS" rev-parse HEAD)" != "$ADRENOTOOLS_REV" ||
      "$(git -C "$ADRENOTOOLS/lib/linkernsbypass" rev-parse HEAD)" != "$LINKERNSBYPASS_REV" ]] ||
   ! git -C "$ADRENOTOOLS" diff --quiet HEAD -- ||
   ! git -C "$ADRENOTOOLS/lib/linkernsbypass" diff --quiet HEAD --; then
  echo "Expected libadrenotools $ADRENOTOOLS_REV with its unmodified linkernsbypass submodule." >&2
  exit 1
fi

# DD-Turnip-Emu v1.0.0 from https://github.com/Droid-Deck/Drivers/releases.
# The driver is a Mesa edce88a39e build for Android API 30+.
DRIVER_SHA256=14fbbab228d0183c95ddd906c3bd46cea5a327f86e441ef788acf4f24ec9cfb7
checksum="$(shasum -a 256 "$DRIVER")"
if [[ "${checksum%% *}" != "$DRIVER_SHA256" ]]; then
  echo "The Turnip driver does not match the pinned DroidDeck v1.0.0 build." >&2
  exit 1
fi
if [[ ! -f "$DRIVER_DIR/NOTICE" || ! -d "$DRIVER_DIR/LICENSES" || ! -f "$DRIVER_DIR/meta.json" ]]; then
  echo "Keep NOTICE, LICENSES and meta.json beside the extracted DroidDeck driver." >&2
  exit 1
fi

CXX=""
for candidate in "$NDK"/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android29-clang++; do
  if [[ -x "$candidate" ]]; then
    CXX="$candidate"
    break
  fi
done
if [[ -z "$CXX" ]]; then
  echo "Android arm64 clang++ was not found under $NDK." >&2
  exit 1
fi

BUILD="$(mktemp -d "${TMPDIR:-/tmp}/midnight-turnip-loader.XXXXXX")"
trap 'rm -rf -- "$BUILD"' EXIT
"$CMAKE" -S "$ADRENOTOOLS" -B "$BUILD" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_static \
  -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DCMAKE_CXX_STANDARD=20 -DCMAKE_BUILD_TYPE=Release
"$CMAKE" --build "$BUILD" --parallel "${TURNIP_BUILD_JOBS:-4}"

"$CXX" -std=c++20 -O2 -Wall -Wextra -Werror -fPIC -fvisibility=hidden -shared \
  -static-libstdc++ -Wl,--exclude-libs,ALL -Wl,--no-undefined \
  -I "$ADRENOTOOLS/include" "$ROOT/app/src/main/cpp/turnip_loader.cpp" \
  "$BUILD/libadrenotools.a" "$BUILD/lib/linkernsbypass/liblinkernsbypass.a" \
  -landroid -ldl -llog -o "$BUILD/libmidnight_turnip.so"

LIB_DIR="$OUTPUT/arm64-v8a"
mkdir -p "$LIB_DIR"
install -m 0644 "$BUILD/libmidnight_turnip.so" "$BUILD"/src/hook/*.so "$LIB_DIR/"
install -m 0644 "$DRIVER" "$LIB_DIR/libvulkan_freedreno.so"

NOTICE_DIR="$OUTPUT/notices/turnip"
mkdir -p "$NOTICE_DIR/DroidDeck-LICENSES"
install -m 0644 "$DRIVER_DIR/NOTICE" "$NOTICE_DIR/DroidDeck-NOTICE"
install -m 0644 "$DRIVER_DIR/meta.json" "$NOTICE_DIR/DroidDeck-meta.json"
cp -R "$DRIVER_DIR/LICENSES/." "$NOTICE_DIR/DroidDeck-LICENSES/"
install -m 0644 "$ADRENOTOOLS/LICENSE" "$NOTICE_DIR/libadrenotools-LICENSE"
install -m 0644 "$ADRENOTOOLS/lib/linkernsbypass/LICENSE" "$NOTICE_DIR/liblinkernsbypass-LICENSE"
printf 'Staged verified Turnip and loader libraries in %s\n' "$LIB_DIR"
printf 'Staged third-party notices in %s\n' "$NOTICE_DIR"
