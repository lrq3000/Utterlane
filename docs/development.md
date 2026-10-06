# Utterlane developer guide

[Back to README](../README.md) · [User guide](user-guide.md) ·
[Contributing](../CONTRIBUTING.md)

## Build from source

### Requirements

- OpenJDK 21, Android SDK platforms 35 and 36, build tools `35.0.0`, Android NDK
  `28.2.13676358`, CMake `3.22.1` (including Ninja).
- Python 3.11.8+ and Git for pinned native-source preparation.
- AGP `8.10.1`, Kotlin `2.0.21`, and Gradle `8.12.1` via the wrapper.

Set `JAVA_HOME` and `ANDROID_HOME` to **absolute paths** for your installation.
The first build needs network access to retrieve dependencies and source code.
Run the following commands from the repository root:

```bash
# Build the sherpa-onnx Kotlin/JNI AAR from pinned source.
# ONNX Runtime is a SHA-256-verified official Maven Central dependency.
python tools/build_sherpa.py

# Prepare pinned CrispASR and transcribe.cpp sources (each with its own ggml).
python tools/prepare_native.py
./gradlew assembleDebug

# Optional: install on your selected Android target.
adb -s YOUR_DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows, use `gradlew.bat`; the same Python native builder works on Windows
and Linux. Build release artifacts with `./gradlew assembleRelease bundleRelease`.
They are unsigned unless the four documented signing environment variables are
provided. See the [signing guide](distribution/README.md#signing-and-github-releases).

## Tests

```bash
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest

# After installing the app and instrumentation APK, run a focused device test.
adb -s YOUR_DEVICE_SERIAL shell am instrument -w \
  -e class io.github.lrq3000.utterlane.CapturePanelAndroidTest \
  io.github.lrq3000.utterlane.test/androidx.test.runner.AndroidJUnitRunner
```

Integration tests involving recognition require the appropriate local models
and speech fixtures; see the [QA notes](qa/README.md). Use normal incremental
builds rather than clearing build caches for every test run.

## Architecture

```text
Microphone / shared file / watched folder
                  ↓
        PCM capture or audio decoding
                  ↓
    Bounded transcription windows → private recognition worker
                  ↓                    (sherpa-onnx / CrispASR / transcribe.cpp)
        Custom word corrections
                  ↓
   Keyboard / focused field / transcript preview and export
```

First-party code lives in `app/src/main/java/io/github/lrq3000/utterlane/`:
`asr/` handles models and recognition, `service/` and `ime/` provide text-input
surfaces, `transcribe/` handles files, `history/` manages optional recordings,
and `settings/` and `ui/` provide the interface. Native glue is in
`app/src/main/cpp/`; [native dependency notices](../app/src/main/assets/native-licenses.txt)
are also included in the APK.

### Streaming and long sessions

Utterlane processes microphone audio while capture continues and delivers
completed text segments. The Parakeet v3 backend uses **simulated streaming over
an offline model**, with context around bounded windows of at most 12 seconds per
inference call. This is segment-by-segment output, not a promise of instantaneous
word-by-word results. Latency depends on pauses, model, and device speed;
recognition near segment boundaries can differ from a single whole-recording pass.

With history disabled, microphone audio stays in bounded memory queues. If the
device cannot keep up, recording stops visibly and accepted audio finishes
processing. With history enabled, recordings also provide a disk-backed backlog.
Optional audio history is approximately **115 MB per hour**, split into hourly
PCM16 WAV parts. Retention ranges from one hour to forever; Android can delay
background cleanup while asleep or force-stopped.

Long-session handling uses bounded audio queues, chunked decoding, cancellation,
and recoverable completed transcripts. Temporary text files support long
transcripts and recovery. See the [privacy policy](../PRIVACY_POLICY.md) for data
retention details.

### Model lifecycle

The idle countdown starts after the last transcription finishes or is cancelled,
or after loading a model without transcribing. Active recording and processing
keep the model loaded. Changing the timeout applies to time already spent idle.

Unloading keeps downloaded model files and microphone services available; the
next transcription reloads the model automatically. **Immediate** also skips
startup auto-loading. **Never** disables automatic unloading, although Android
can still reclaim the app process. Sleep counts toward inactivity; expired
deadlines are rechecked when the device wakes without waking it solely to unload
the model. See [user-facing memory settings](user-guide.md#model-memory).

## Recognition backends and models

| Model | Runtime | Approximate model download |
| --- | --- | --- |
| NVIDIA Parakeet TDT v3 | sherpa-onnx / ONNX INT8 | 670 MB |
| Moondream Parakeet Ultra — Q8_0 is the first-launch default | CrispASR / GGUF Q8_0 or Q4_K | 674 MB or 402 MB |
| Moondream Parakeet Redux | CrispASR / GGUF Q8_0 or Q4_K | 674 MB or 402 MB |
| Moondream Parakeet Redux — compact native ternary | transcribe.cpp / TQ1_Q8_0 | **159.1 MB** |

The CrispASR Redux GGUF conversions are not the original 178 MB Photon packing,
and published Photon benchmarks do not establish their performance on your phone.
The Q8 alternatives have on-emulator inference coverage; Q4 variants share the
backend but have not received a separate on-device inference run.

### Native ternary Redux

The **Redux TQ1_Q8_0** option retains its ternary encoder in RAM using
transcribe.cpp's native ternary kernels. Q8_0 applies only to the remaining dense
parameters. This prioritizes compact memory over the engine's faster expanded
CPU layout. Its download is pinned and verified; it has separate storage and
does not replace the existing Redux options. Total process RAM is greater than
the download size because decoding, activations and application state also use
memory. See [native ternary QA](qa/redux-native-ternary.md) for measurements.

### Custom models

GGUF and legacy Whisper GGML models use CrispASR's generic session dispatcher.
Models requiring an explicit audio tokenizer/codec (such as MiMo-ASR) need the
companion assigned during import; other models use automatic sibling discovery.
Original filenames are preserved in private storage; **Load** checks native
compatibility and runs a warm-up.

A model supported upstream still needs the correct converted weights, companions,
and enough device memory. Missing companions are not downloaded implicitly.
TTS/music models are not speech-input models. Custom models currently use disjoint
audio chunks so models without word timestamps cannot duplicate overlapping text.
See the [custom-model import steps](user-guide.md#custom-models).

### Speaker diarization

Speaker labels use the separate NVIDIA Nemotron-3-Diarization model (107 MB).
Diarization works alongside the original ONNX Parakeet v3; no migration or
replacement download of that speech model is needed.

A specified speaker count constrains persistent output identities using all
native-channel evidence; it does not force nonexistent speakers or perform an
offline global re-clustering pass. Fixed-one mode bypasses native diarization.
Speaker IDs belong to one recording, and uncertain speech can be labeled
**Unknown speaker**. Settings changes take effect on the next recording.

Streaming labels are emitted at the existing audio segment boundaries, with
lookahead and additional inference work. Device throughput determines whether
processing keeps up with recording. Custom models without exposed word timings
retain their ordinary disjoint ASR windows when diarization is enabled. The
generic JNI bridge preserves available word timings; models without usable
timings receive explicitly coarse/unknown attribution rather than another ASR
pass on tiny speaker slices.
See [speaker-label settings](user-guide.md#speaker-labels).

Runtime tuning is centralized in `settings/RuntimeOptions.kt`, validated on
persistence and IPC boundaries, and snapshotted per operation. Native diarization
uses a bounded persistent stream; catch-up merges already-buffered steps. The
checked source adaptation in `tools/native_stream_options.py` adds context
controls, genuine completed-work callbacks, and an explicit failure status.
Opaque native operations retain configurable fallback budgets. See
[recovery QA](qa/diarization-recovery.md) for acoustic evidence and limitations.

## Distribution and project identity

Utterlane uses the application ID `io.github.lrq3000.utterlane`. It installs
separately from its predecessor: settings, model files, recording history, and
permissions do not migrate automatically.

Store publishing under this new identity is separate from upstream distribution;
the presence of Fastlane metadata does not mean a Google Play or F-Droid listing
is live. Follow the [release and store submission guide](distribution/README.md).

## Development roadmap

- Quick Settings tile, home-screen widget, and word-correction import/export.
- Hotword boosting when the selected recognition backend/model supports it.
- Broader physical-device performance measurements and Q4 inference coverage.

For current user-facing limitations, see [compatibility](user-guide.md#compatibility).
Original brand artwork, generated exports, and regeneration instructions are in
the [design document](design/utterlane-branding.md).

## Dependency acknowledgments

Speech recognition builds on [NVIDIA Parakeet](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3),
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx),
[CrispASR](https://github.com/CrispStrobe/CrispASR), transcribe.cpp, and ggml.
Models and third-party libraries retain their own licenses. The NVIDIA Parakeet
v3 model is CC-BY-4.0; the ONNX conversion is provided by
[csukuangfj](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8).
See the [bundled native notices](../app/src/main/assets/native-licenses.txt).

Utterlane is licensed under [Apache-2.0](../LICENSE). Copyright notices for
upstream contributors are retained; Utterlane contributions are copyright 2026
**Stephen Karl Larroque &lt;LRQ3000@GMAIL.COM&gt; and Utterlane contributors**.
Historical release links and original copyright notices remain intact to
preserve the project's [lineage](../README.md#lineage-and-maintenance).
