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

### Onboarding module

`onboarding/` contains the native first-launch guide. `OnboardingContent` owns
ordered, stable page descriptors; `OnboardingFlow` owns conditional navigation.
The ViewModel combines a separate onboarding DataStore with `OnboardingServices`.
Only `AppOnboardingServices` depends on the app's existing model managers,
preferences, capture factory and optional services. Android activity-result
handoffs live in `OnboardingActivity`; pages consume snapshots and typed actions.

Add/reorder informational pages in the registry and provide the matching renderer;
keep model identities and capability integration in the adapter. The four named
model cards use stable catalog IDs, so changing the app's default cannot silently
relabel a different engine as Parakeet Ultra. Current custom models remain usable
when replaying the guide.

Local vector resources and `assets/onboarding/` are independent of the website.
`python tools/fetch_onboarding_sample.py --check` verifies the bundled audio
offline. Regeneration additionally uses FFmpeg and downloads the hash-pinned
Commons source; neither tool is a runtime or Android-build dependency. See
[onboarding verification](qa/onboarding.md) for test and emulator details.

### Streaming and long sessions

Utterlane processes microphone audio while capture continues and delivers
completed text segments. The Parakeet v3 backend uses **simulated streaming over
an offline model**, with context around bounded windows of at most 12 seconds per
inference call. This is segment-by-segment output, not a promise of instantaneous
word-by-word results. Latency depends on pauses, model, and device speed;
recognition near segment boundaries can differ from a single whole-recording pass.

`RecordingPipeline` starts capture alongside model preparation and persists PCM
through a bounded writer queue, independent of inference speed. Its disk-backed
backlog is also used with history disabled. The processor follows a conflated
saved-sample watermark using a lease-protected sequential WAV reader. Inference
failure is an outcome, not cancellation of capture/writing. Stop drains ordered
recognition and all enabled speaker-label work. Cancellation drains accepted writer
blocks; explicit user discard then deletes temporary work, while unexpected owner
interruption preserves it. A single emergency block slot retains the
block that detects writer-queue overflow, then capture stops and the writer drains.

Audio is approximately **115 MB per hour**, split into hourly PCM16 WAV parts.
Recovery metadata is written before the first sample and survives process death.
Live history-off successes are deleted. Temporary dialogs retain their sources
through retries until explicit dismissal; saved recovery audio expires normally.
Encoded shared inputs are copied once without owning/deleting the sender's original.
Persisted discard markers prevent resurrection after a crash during deletion.

`RecordingHistory` and `TranscriptHistory` have independent data and policies.
`RetentionIndex` orders only eligible unpinned metadata; candidate checks are O(1)
after index updates and do not scan file contents. Manual saves pin stable item/
attempt IDs. Unpin starts a new reference time, and Immediate unpins carry a
persisted launch-token hold. `HistoryCleanupCoordinator` releases those holds only
at user entry, coalesces IO requests and never simulates app close. `JobScheduler`
uses separate audio/text jobs for finite durations, preserving unchanged schedules.
Temporary and pinned items are absent from the expiration index. Audio reader
leases can derive a reader after expiry becomes due without authorizing new readers.

`HistoryActivity` hosts the full-screen Audio history and Transcript history
destinations using the same B-style rows and branded header. `HistoryIndex` keeps
visible metadata in creation-time/ID order, with bidirectional keyset pages in
O(log n + page size); pins do not change the creation key, and leased deletions leave
the visible index immediately. `HistoryViewModel` uses AndroidX Paging 3.3.6 with
30-row pages, six-row prefetch and a 90-row cache target. Source invalidation
refreshes around the visible anchor, while activity recreation retains the same
cached window. Only visible date labels use the current UI locale; transcript
previews are limited to 160 characters and read on IO under a lease. Internal
history/detail navigation preserves the cleanup launch token. Legacy Settings
recovery intents route once to the new audio destination.

`TranscriptionDialogModel` retains source ownership and per-attempt results across
activity recreation. Transcript-origin viewers never own their linked audio.
Completed attempts can be copied into independent no-backup text history; cached
working text and granted export snapshots keep their separate cleanup lifetimes.
`DialogAudioActions` owns private imports and bounded URI/file exports. The shared
`AudioPlaybackController` uses Main-thread owner tokens, read leases, audio focus,
coalesced native seeks and O(1) hourly-part timeline addressing. Playback updates
only its own UI state and stops polling while paused.

`WaveformHistory` calculates each nonempty microphone callback's RMS once and
appends its normalized level to a 64-point ring, matching the original waveform.
The ring advances on input even between visual publications: the refresh cap must
not impose an audio-time aggregation bucket or stretch the waveform's time scale.
Memory is bounded; processing is O(samples), insertion O(1), and immutable 64-point
snapshots are rebuilt only at publication after new PCM. Unchanged snapshots are
reused. `CaptureMetrics` publishes exact cumulative counters at the
configured visual cadence (1/2/5/10/20/30/60/90/200 Hz; default 60), with immediate
control/error states. Hz-scaled monotonic deadlines avoid fractional-period drift
and skip obsolete slots rather than queuing catch-up work. `VisualRefreshRate`
centralizes defaults, validation and timer intervals across presentation surfaces.
File percentage and preview updates use latest-value state and one presentation
owner, rather than allocating a UI coroutine for every decoder callback.

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
| Moondream Parakeet Ultra — Q8_0 is the catalog default; onboarding recommends by RAM | CrispASR / GGUF Q8_0 or Q4_K | 674 MB or 402 MB |
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
