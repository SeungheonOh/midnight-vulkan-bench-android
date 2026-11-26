# Midnight Vulkan Bench (Android)

A standalone, distributable Android APK for benchmarking the `midnight-vulkan` GPU prover backend against the multi-core CPU prover across a wide range of Android devices (Qualcomm Adreno, ARM Mali/Immortalis, Samsung Xclipse, Imagination PowerVR, etc.).

## Features

- **Zero external corpus download (~15 MB APK)**: deterministically generates the KZG SRS (`ChaCha20Rng`), proving keys, and witnesses on-device so every phone runs the exact same workload while reusing the cached SRS across circuits of the same `k`.
- **Three distinct circuit families across multiple sizes**:
  - **Squaring Chain (`chain`)**: dense 4-advice-column rc1 benchmark (`2^k - 16` rows, MSM-heavy) — `k = 14..20`
  - **Poseidon Chain (`poseidon-chain`)**: Midnight `ZkStdLib` chained Poseidon hashes filling `2^k` rows (heavy MSM + quotient + iFFT) — `k = 14..18`
  - **Poseidon Preimage (`poseidon-preimage`)**: single Midnight `ZkStdLib` Poseidon hash in `2^k` rows (sparse witness, quotient-heavy) — `k = 14..17`
- **Built-in presets + custom matrix**:
  - **Quick** (`~1–2 min`): `chain` `k=15..17`, `poseidon-chain-k15`, `poseidon-preimage-k15`
  - **Standard** (`~3–5 min`): `chain` `k=14..18`, `poseidon-chain` `k=15..16`, `poseidon-preimage` `k=15..16`
  - **Heavy** (`~8–15 min`): adds `chain` `k=19..20` and `poseidon-chain` `k=17..18`
  - **Custom**: pick any combination of circuits, sizes, backend (`CPU vs Vulkan`, `Vulkan Only`, `CPU Only`), repeats (`1–3×`), and inter-circuit thermal cooldown (`0s / 5s / 15s`).
- **Cross-device diagnostics & safety**:
  - Full Android hardware + SoC + CPU cluster clock + Vulkan GPU capability probe (`vendor_id`, driver string, API version, `shaderInt64`, `shaderInt16`, subgroup sizes, shared memory, unified memory, thresholds).
  - One-tap **GPU Kernel Smoke Test** (`buffer_fill`, `fft_k15_vs_cpu`, `msm_k15_vs_cpu`) verified bit-exact against CPU.
  - Persistent `VkPipelineCache` in app cache (`MIDNIGHT_VULKAN_PIPELINE_CACHE`) and runtime **Force 32-bit** arithmetic toggle (`MIDNIGHT_VULKAN_ARITH=32`) for testing drivers with 64-bit/16-bit quirks.
  - Automatic RAM safety guardrail (auto-skips `k >= 18/19/20` on low-RAM devices), battery temperature & Android thermal status telemetry before/after every circuit, foreground service + WakeLock + screen-on lock.
  - Every proof is cryptographically verified (`Ver: OK`) and GPU scratch buffers are verified zeroed (`Erase: OK`).
- **One-tap export**:
  - **Share JSON**: full structured JSON report via Android Share Sheet.
  - **Copy Markdown**: GitHub-ready Markdown summary table copied to clipboard.

## Building the Distributable APK

### Prerequisites (macOS / Linux)

- Rust `1.90.0` with `aarch64-linux-android` target (`rustup target add aarch64-linux-android --toolchain 1.90.0`)
- `glslangValidator` + `spirv-opt` (`brew install glslang spirv-tools` or `apt install glslang-tools spirv-tools`)
- Android SDK (API 35) + Android NDK (API 29+ clang) + JDK 17 or 21

### One-command build

```bash
./scripts/build-apk.sh
```

Output APK and SHA-256 checksum are written to:

```text
dist/midnight-vulkan-bench-v0.1.0-arm64-v8a.apk
dist/midnight-vulkan-bench-v0.1.0-arm64-v8a.apk.sha256
```

To sign with your own release keystore, set `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` before running `./scripts/build-apk.sh`. Otherwise a local self-signed keystore is generated in `signing/` (gitignored) so the APK can be sideloaded directly on any Android 10+ (`arm64-v8a`) phone.

## Sideloading on Any Android Phone

- **Direct install**: transfer `dist/midnight-vulkan-bench-v0.1.0-arm64-v8a.apk` to the phone and tap to install (enable *Install unknown apps* if prompted).
- **Via ADB**:
  ```bash
  adb install -r dist/midnight-vulkan-bench-v0.1.0-arm64-v8a.apk
  ```

## Prover Source

Pinned in `core/Cargo.toml` to `https://github.com/SeungheonOh/midnight-zk.git` (`rev = d5ff7ea6ba5f6a6c16c3a5396d5103c1eb8e888f`, PR #2 `vulkan-prover`).

### Local Android Vulkan compatibility build

Use a Cargo patch config that points the Midnight crates at a single checkout
with Android Vulkan 1.1 support, such as the `android-vulkan-compat` branch.
Patch both the pinned Git source and its crates.io replacements so the prover
and the backend use the same curve types. With `COMPAT_CARGO_CONFIG` set to the
absolute path to that config and `COMPAT_PROVER_REV` set to its checkout's commit,
build and install a separate debug package:

```sh
MIDNIGHT_BENCH_PROVER_REV="$COMPAT_PROVER_REV" \
  ./scripts/build-core.sh --config "$COMPAT_CARGO_CONFIG"
./gradlew -PbenchCompatInstall :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open **Midnight Bench (Compat)**. Check the Vulkan device, then run a benchmark
with the GPU smoke check enabled. Confirm proof verification and a nonzero GPU
call count in the report.

### Optional app-local Mesa Turnip driver

On Android 11 or newer, an Adreno test build can load Turnip for this app.
Build the same Rust core as above. Check out the libadrenotools revision in
`scripts/build-turnip-loader.sh` with its submodule, and extract the pinned
`DD-Turnip-Emu-v1.0.0.zip` release. Stage the libraries and package the separate
**Midnight Bench (Turnip)** debug app with `ANDROID_NDK_HOME` set:

```sh
# Use absolute paths. TURNIP_DRIVER points to the extracted libvulkan_freedreno.so.
./scripts/build-turnip-loader.sh "$ADRENOTOOLS_CHECKOUT" "$TURNIP_DRIVER" "$TURNIP_STAGE"
./gradlew -PbenchTurnipInstall="$TURNIP_STAGE" :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

In the Device tab, confirm that the driver name contains `Mesa` before comparing
results against the system driver. The benchmark JSON records the active driver.
