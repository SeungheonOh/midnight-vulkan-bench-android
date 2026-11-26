#!/usr/bin/env bash
# Builds a signed distributable arm64-v8a release APK for Midnight Vulkan Bench.
# Stages libmidnight_bench_core.so (unless SKIP_CORE=1), ensures a signing
# keystore exists, runs Gradle assembleRelease, and writes the APK + SHA-256
# checksum into dist/.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# Locate JDK 21/17
if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in \
    /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
    /opt/homebrew/opt/openjdk@21 \
    /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
    /opt/homebrew/opt/openjdk@17; do
    if [[ -d "$candidate" && -x "$candidate/bin/java" ]]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi

# Locate Android SDK
if [[ -z "${ANDROID_HOME:-}" ]]; then
  for candidate in \
    /opt/homebrew/share/android-commandlinetools \
    "$HOME/Library/Android/sdk" \
    /usr/local/share/android-commandlinetools; do
    if [[ -d "$candidate/platforms" ]]; then
      export ANDROID_HOME="$candidate"
      break
    fi
  done
fi
if [[ -n "${ANDROID_HOME:-}" && ! -f "$ROOT/local.properties" ]]; then
  echo "sdk.dir=$ANDROID_HOME" > "$ROOT/local.properties"
fi

if [[ "${SKIP_CORE:-0}" != "1" ]]; then
  "$ROOT/scripts/build-core.sh" "$@"
fi

# Create a local self-signed release keystore if none is provided so the
# distributable APK always has a valid v2/v3 signature for sideloading.
if [[ -z "${KEYSTORE_PATH:-}" && ! -f "$ROOT/signing/release-bench.keystore" ]]; then
  mkdir -p "$ROOT/signing"
  keytool -genkeypair -v \
    -keystore "$ROOT/signing/release-bench.keystore" \
    -storepass midnight-bench \
    -keypass midnight-bench \
    -alias midnight-bench \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Midnight Vulkan Bench, OU=Benchmarks, O=Midnight, L=Unknown, S=Unknown, C=US" >/dev/null 2>&1
  echo "Generated local distribution signing keystore: signing/release-bench.keystore"
fi

./gradlew :app:assembleRelease --no-daemon

VERSION="$(sed -n 's/.*versionName = "\(.*\)"/\1/p' "$ROOT/app/build.gradle.kts")"
SRC_APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
DIST_DIR="$ROOT/dist"
OUT_APK="$DIST_DIR/midnight-vulkan-bench-v${VERSION}-arm64-v8a.apk"

mkdir -p "$DIST_DIR"
cp "$SRC_APK" "$OUT_APK"
(cd "$DIST_DIR" && shasum -a 256 "$(basename "$OUT_APK")" > "$(basename "$OUT_APK").sha256")

echo ""
echo "Built distributable APK:"
ls -lh "$OUT_APK"
cat "${OUT_APK}.sha256"
