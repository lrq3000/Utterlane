# Compact Redux native-ternary integration

Base: `4947b71`. Worktree `.worktrees/redux-native-ternary`, branch
`feat/redux-native-ternary`. Package namespace is the current Utterlane identity.

## Runtime and artifact

- transcribe.cpp: `ba949120d60f29daaaa13eec65b9c28c2c2112a6` (vendored patched ggml).
- Build: Parakeet-only, Android ARM64/API 26, NDK 28.2.13676358, CPU, no system
  BLAS/OpenMP/GPU. Native compute targets use `-O3` in debug as well as release.
- Model: `Nairod785/parakeet-redux-gguf` at
  `87cbc354ce32bc9fe144b5b7bcdd9c68538907a9`,
  `parakeet-redux-0.6b-TQ1_Q8_0.gguf`, **159,121,504 bytes**.
- SHA-256: `74f43ba852479e86e29df92cdbc89aa8215c7e8070f711be424ff466415b6184`.
- Native-only ternary policy is set before load. A pinned-layout invariant checks
  the actual loaded encoder, not just the environment variable. Initialization
  fails if the 264 ternary matrices have been expanded into another type.
- `transcribe-native` has a separate CMake scope so its patched ggml never replaces
  CrispASR's version. Its JNI shared library hides all static-archive symbols.
- The existing worker warm-up, bounded input, force-unload, history, UI and
  device-sleep protection are reused. Existing five catalog entries and the
  first-launch Ultra Q8 default are preserved.

## Observed results

LDPlayer 9 / Android 9, ARM64 translated on an x86 host:

- Model SHA-256 and size match the pinned catalog artifact.
- Six focused catalog JVM tests passed.
- Two direct Android instrumentation tests passed in **63.434 seconds**:
  1. Native ternary layout retained through two short spoken-file runs and a
     complete 12-second repeated-speech window; timestamp arrays, text, and
     SentencePiece handling checked.
  2. Actual inference and reset while switching between CrispASR Ultra Q8,
     compact transcribe.cpp Redux, and sherpa-onnx Parakeet v3. Existing model
     downloads survive unloading.
- Recorded layout: **264 TQ1_G128 tensors**, **132,120,576 ternary bytes**, and
  **158,894,768 total model-tensor bytes**.
- Direct-backend run: load **480 ms**, native allocations after load
  **258,389,768 bytes**, after inference **282,079,864 bytes**; process PSS
  **472,582 KiB** after inference. The three audio calls plus load took **26,638 ms**.
  These are snapshots, not a measured peak or physical-phone performance claims.
  Dense host mirrors, graph scratch and emulator/process overhead explain why
  total RAM is larger than the compact encoder/file.
- After moving fixtures into app-specific storage, both Android tests passed
  again in **50.080 seconds** without requesting storage permission or UiAutomation.
  Layout remained identical; the final run recorded 420 ms load, 258,392,008
  native allocated bytes after load, 282,062,904 after inference, and 472,940 KiB
  process PSS. No claim about the physical phone's speed is inferred from this.
- `llvm-nm -D --defined-only` reports exactly the four bridge JNI exports.
  `llvm-readelf -d` lists only Android system libraries (log, m, dl, c) as
  dependencies: no ggml/CrispASR shared dependency or exported ggml symbols.
- Initial full JVM run: 41 passed and one existing history temporary-file deletion
  assertion failed on Windows. A narrow history rerun failed a different deletion
  assertion while the first passed. Neither history code nor those tests was
  changed here; this intermittent filesystem cleanup issue is not counted as a
  successful full-suite run. Catalog tests remained green.

## Parallel-emulator isolation

The initially installed emulator app was a separate versionCode 11 development
build; the feature branch is based on main's versionCode 10. A backup APK was
saved locally before a data-preserving test downgrade. Another instrumentation
run then explicitly force-stopped this test mid-inference; Android's activity
log confirmed `start instr`, not a native crash. The short speech passes had
already succeeded and the native layout assertion was satisfied.

Subsequent validation uses **`io.github.lrq3000.utterlane.ternaryqa`**, independent
of the canonical app and other QA packages. `qaApplicationIdSuffix` applies only
to debug builds; production releases always retain the canonical identity.
The tests use app-specific external fixtures and never attach UiAutomation,
whose device-wide singleton would interfere with another UI test runner.

The original versionCode 11 emulator application was restored after the isolated
QA runs; its data was not cleared. The final deliverable uses the canonical
`io.github.lrq3000.utterlane` identity and main's versionCode 10 / versionName
1.2.4. APK SHA-256:
`71f0835cb655ce41e9cd022f452a32ee87989ca621ade98954f026897e03f0d4`.

## Reproduction

Prepare native source once:

```powershell
python tools/prepare_native.py
.\gradlew.bat :app:testDebugUnitTest --tests '*ModelCatalogTest' :app:assembleDebug :app:assembleDebugAndroidTest "-PqaApplicationIdSuffix=.ternaryqa" --console=plain --quiet
```

Install the two APKs once. Put test fixtures in
`/sdcard/Android/data/io.github.lrq3000.utterlane.ternaryqa/files/ternary-qa/`:

- `parakeet-redux-0.6b-TQ1_Q8_0.gguf`
- `speech-source.wav` (official sherpa-onnx Parakeet v3 English example)
- For the switching test on a fresh QA install: `parakeet-ultra-q8_0.gguf`,
  `encoder.onnx`, `decoder.onnx`, `joiner.onnx`, and `tokens.txt`.

Then rerun tests directly, without Gradle or model downloads:

```text
adb -s emulator-5554 shell am instrument -w -e class io.github.lrq3000.utterlane.NativeTernaryAndroidTest io.github.lrq3000.utterlane.ternaryqa.test/androidx.test.runner.AndroidJUnitRunner
```

Detailed metrics are written by the test to private `files/ternary-qa.txt`.
The vendored runtime sources are unmodified. The app-owned JNI bridge assertion
reads the pinned internal model layout for verification.
Future upstream revision changes must revalidate that structure and tensor count.
