#!/usr/bin/env bash
# Cross-compiles midnight-bench-core for aarch64-linux-android (API 29+)
# with embedded Vulkan SPIR-V shaders and stages libmidnight_bench_core.so
# into app/src/main/jniLibs/arm64-v8a/.
set -euo pipefail

export PATH="/opt/homebrew/opt/rustup/bin:/opt/homebrew/bin:$HOME/.cargo/bin:$PATH"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="aarch64-linux-android"
API="${ANDROID_API:-29}"
TOOLCHAIN_VER="$(sed -n 's/^channel = "\(.*\)"/\1/p' "$ROOT/core/rust-toolchain.toml")"

find_ndk() {
  for candidate in \
    "${ANDROID_NDK_HOME:-}" \
    "${ANDROID_NDK_ROOT:-}" \
    /opt/homebrew/share/android-ndk \
    /usr/local/share/android-ndk; do
    if [[ -n "$candidate" && -d "$candidate/toolchains/llvm/prebuilt" ]]; then
      echo "$candidate"
      return 0
    fi
  done
  if [[ -d "$HOME/Library/Android/sdk/ndk" ]]; then
    local latest
    latest="$(ls -1 "$HOME/Library/Android/sdk/ndk" | sort -V | tail -n 1)"
    if [[ -n "$latest" && -d "$HOME/Library/Android/sdk/ndk/$latest/toolchains/llvm/prebuilt" ]]; then
      echo "$HOME/Library/Android/sdk/ndk/$latest"
      return 0
    fi
  fi
  return 1
}

NDK="$(find_ndk || true)"
if [[ -z "$NDK" ]]; then
  echo "error: Android NDK not found. Set ANDROID_NDK_HOME or install android-ndk." >&2
  exit 1
fi

PREBUILT_DIR="$(ls -d "$NDK/toolchains/llvm/prebuilt/"* 2>/dev/null | head -n 1)"
CC="$PREBUILT_DIR/bin/${TARGET}${API}-clang"
CXX="$PREBUILT_DIR/bin/${TARGET}${API}-clang++"
AR="$PREBUILT_DIR/bin/llvm-ar"
RANLIB="$PREBUILT_DIR/bin/llvm-ranlib"

if [[ ! -x "$CC" ]]; then
  echo "error: NDK clang not found at $CC" >&2
  exit 1
fi

if ! rustup target list --toolchain "$TOOLCHAIN_VER" --installed | grep -qx "$TARGET"; then
  echo "error: Rust target $TARGET is not installed for toolchain $TOOLCHAIN_VER." >&2
  echo "Run: rustup target add $TARGET --toolchain $TOOLCHAIN_VER" >&2
  exit 1
fi

CARGO_BIN="$(rustup which cargo --toolchain "$TOOLCHAIN_VER")"
export PATH="$(dirname "$CARGO_BIN"):$PATH"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC"
export CC_aarch64_linux_android="$CC"
export CXX_aarch64_linux_android="$CXX"
export AR_aarch64_linux_android="$AR"
export RANLIB_aarch64_linux_android="$RANLIB"
export MIDNIGHT_VULKAN_REQUIRE_SPIRV=1

echo "Building midnight-bench-core for $TARGET (API $API) using NDK: $NDK"
cd "$ROOT/core"
cargo build --release --target "$TARGET" --lib "$@"

OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$OUT"
cp "target/$TARGET/release/libmidnight_bench_core.so" "$OUT/libmidnight_bench_core.so"
echo "Staged: $OUT/libmidnight_bench_core.so ($(du -h "$OUT/libmidnight_bench_core.so" | cut -f1))"
