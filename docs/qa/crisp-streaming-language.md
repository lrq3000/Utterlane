# Custom models, streaming diarization, and app language QA

Date: 2026-10-04. Worktree: `.worktrees/crisp-streaming-language`, branch
`feat/crisp-streaming-language`, base `cad85a1`.

Subsequently synchronized with main `b293212`: see the
[main-first hunk checklist and combined regression results](crisp-streaming-main-first-integration.md).

## Implementation

- Generic CrispASR C session/JNI loading for custom GGUF and Whisper GGML speech
  models, private atomic multi-file imports with SHA-256 receipts, persisted
  selection/deletion, and explicit audio-tokenizer/codec assignment where needed.
- Separate Nemotron-3-Diarization streaming session per recording. Off by default;
  Auto/eight tracks or a 1–8 arrival-order track constraint. Both microphone and
  file inputs use the same session pipeline. Anonymous labels reach the append-only
  transcript before delivery; dictionary replacements never rewrite labels.
- Original ONNX Parakeet remains usable without migration. Timestamped backends
  retain overlap ownership; custom models use disjoint spans without fabricated
  word times, and speaker-turn slices when diarization is enabled.
- System/English/all 23 translated UI languages. Android 13+ uses LocaleManager;
  older versions persist and wrap activity/application/service contexts.
- Pinned native-source preparation adapts embedded CMake root paths and disables
  implicit native Android downloads. Upstream third-party notices ship in assets.

## Verification evidence

- `testDebugUnitTest --console=plain -q`: **55 JVM tests, no failures**, covering
  existing audio/history/sleep behavior plus import receipts/companion roles,
  bounded speaker timelines, overlap deduplication, counts, dictionary separation,
  pending-boundary EOF, and packaged locale consistency.
- `assembleDebug` and `assembleDebugAndroidTest`: successful ARM64 builds.
- Final regular `assembleDebug` succeeded; APK metadata confirms
  `io.github.lrq3000.utterlane`. Installed and launched it on the dedicated
  instance. Native optimization check passed for all three inspected ggml hot
  paths; `git diff --check` passed and the root main worktree remained clean.
- Six distinct methods in `CrispStreamingAndroidTest` passed on Android API 28:
  1. Actual FileProvider import, persisted custom selection, native generic
     Parakeet warm-up, and real speech transcription with labels Off/Auto.
     Output with Auto: `Speaker 1: Ask not what your country can do for you, ask
     what you can do for your country.`
  2. Original ONNX Parakeet with a specified count of two emits speaker-labeled
     text **before** `finish()`, and completes successfully (60.409 s test run).
  3. Twenty seconds of NVIDIA's official multi-voice demo yields native tracks
     `0, 1, 2, 3`, with 1,999 returned frames and a correct final flush.
  4. Generic legacy Whisper `ggml-tiny.en-q5_1.bin` transcribes the speech fixture
     and includes the expected word `country` (9.579 s initial test run).
  5. French → English → System resource override/restoration.
  6. Actual Nemotron streaming flush at exactly ten seconds, without future audio;
     ASR is stubbed in this test to isolate the speaker boundary regression.
- Latest focused device batch after review fixes: EOF regression, custom import/
  Off/Auto, and generic Whisper — **3 passed in 71.205 s**.
- UI-tree-derived interactions and screenshots verify the System/language picker,
  switching a French system UI to English, English persisting after force-stop,
  and the full Auto/1–8 count picker.
- Source preparation ran twice successfully to verify known adaptations are
  accepted on subsequent runs. Read-only review confirmed both Important findings
  were fixed; no remaining Critical or Important findings in that follow-up.

## Bugs caught during verification

1. Native centered FFT finalization can omit up to 256 trailing samples (16 ms).
   Reproduced with 61,522 input versus 61,280 labeled samples. Extend only that
   bounded final tail using the last decision; reject larger coverage failures.
2. A pending ASR boundary at EOF lacked streaming right context. The diarized
   path now coalesces the remaining bounded audio before native flush; Off keeps
   the existing segmentation. JVM regression failed before the fix and passed
   afterward; the actual native ten-second boundary test also passed.
3. Preserving companion filenames alone was insufficient for MiMo-ASR's explicit
   tokenizer setter. A persisted codec role now reaches JNI before warm-up, with
   failure cleanup. Full real MiMo inference is not part of the tested matrix.

## Dedicated emulator and reproduction

The shared instance was receiving APK replacements and ADB/UI-automation activity
from other work. With operator approval, created **UtterlaneCrispQA**, LDPlayer
index **1**, with four CPUs and 8 GB RAM. Its ADB endpoint is
`127.0.0.1:5557`; these checks used a separate ADB server on port `5038`.
The original LDPlayer instance is separate.

Build the separate QA application identity:

```text
gradlew.bat assembleDebug assembleDebugAndroidTest "-PqaApplicationIdSuffix=.crispqa" --console=plain -q
adb -P 5038 connect 127.0.0.1:5557
adb -P 5038 -s 127.0.0.1:5557 install -r app/build/outputs/apk/debug/app-debug.apk
adb -P 5038 -s 127.0.0.1:5557 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -P 5038 -s 127.0.0.1:5557 shell pm grant io.github.lrq3000.utterlane.crispqa android.permission.READ_EXTERNAL_STORAGE
adb -P 5038 -s 127.0.0.1:5557 shell am instrument -w -e class io.github.lrq3000.utterlane.CrispStreamingAndroidTest io.github.lrq3000.utterlane.crispqa.test/androidx.test.runner.AndroidJUnitRunner
```

Fixtures under `/sdcard/Download/`: the existing `speech-source.wav`, catalog
ONNX files and Ultra Q8 GGUF in `parakeet-qa/`, plus:

- `Nemotron-3-Diarization.q8_0.gguf` and `nemotron3_tts_8_open_voices.mp4` from
  `https://huggingface.co/nvidia/Nemotron-3-Diarization/tree/main`.
- `ggml-tiny.en-q5_1.bin` from
  `https://huggingface.co/ggerganov/whisper.cpp/tree/main`.

Normal `assembleDebug` (without the property) produces the normal application ID.
Screenshots/XML: `qa-artifacts/app-language-picker.*`, `app-language-english.*`,
`english-after-restart.*`, `diarization-toggle.*`, `speaker-count-picker.*`.
Final regular-app smoke evidence: `qa-artifacts/final-regular-apk.*`.
Early screenshots predate the clarified auxiliary-model-ready text.

## Limits

- This is functional evidence on an ARM64-translating emulator, not a real-phone
  real-time-performance guarantee. The multi-voice path was slower than audio
  duration here. Labels follow the existing bounded segment cadence, not each
  word immediately; ordinary overload/backlog behavior still applies.
- Native ASR inference was exercised with Parakeet and Whisper, not every upstream
  model/quantization. The explicit MiMo codec route is wired and manifest-tested,
  but was not exercised with real MiMo weights. Model-specific files, memory,
  native support, and processing deadlines still constrain compatibility.
- Android 13+ LocaleManager/system-settings synchronization is implemented and
  compiled, but runtime locale tests used API 28. New strings retain English
  fallback pending the project's translation batch.
- AudioRecord hardware acquisition was not revalidated for diarization; the tested
  incremental PCM session is shared by microphone and imported-file flows.
- No F-Droid build or remote CI run was performed; this checkout has no fdroiddata.
