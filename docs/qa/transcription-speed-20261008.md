# Transcription first-text and catch-up study — 2026-10-08

## Decision

**No tested change established a reliable improvement in both first-text delay
and post-Stop catch-up while preserving word accuracy.** Keep the current
user-facing defaults. In particular, faster preview from a smaller ASR window is
not, by itself, a successful throughput optimization.

The study completed **29 real-time-paced recordings**, each **25.449125 seconds**,
plus a cheap 16-profile segmentation screen. No recording exceeded 30 seconds.
All captured PCM was saved and processed. Models remained Ultra Q8 for ASR and,
where enabled, Nemotron Q8 with Low latency / batch 16 / four threads /
192-cache / 192-FIFO / 160-update.

See [all numeric observations](transcription-speed-20261008.csv). Raw transcripts,
word/PCM evidence, native logs and backlog traces remain local in
`.worktrees/transcription-speed-study/qa-artifacts/transcription-speed/<tag>/`.
Production defaults and implementation were not changed; additions are QA tooling.

## What the measurements mean

`RealtimeFixtureCapture` pre-decodes the public French/English single-speaker
fixture, rejects inputs over 30 seconds, and delivers 50 ms PCM blocks at absolute
monotonic-clock deadlines. It runs through the production `RecordingPipeline`:
capture, durable writing and inference have independent execution, and recognition
reads the actual disk-backed backlog. It never waits for ASR before advancing the
capture clock. A slow consumer therefore cannot make the test falsely look real-time.

- **First text:** time from actual capture start until the first nonblank
  `TranscriptionSession` publication.
- **Backlog at Stop (audio duration):** captured samples minus completed ASR
  ownership-window samples, sampled when the producer stops. This is a
  conservative *completed-window* metric. It includes an in-flight window even
  when some of its computation is already done, and is not an estimate of CPU
  seconds remaining or a guarantee that every speaker label in that window was
  already published.
- **Post-Stop catch-up:** actual elapsed time from producer stop until the
  pipeline finishes recognition, final text/speaker draining and recording
  finalization. This is the primary catch-up objective.
- **Backlog trace:** captured and completed audio positions approximately once
  per second, including the final Stop snapshot. Maximum backlog is sampled at
  each capture block.
- **Capture lateness:** actual delivery lateness relative to each ideal block
  deadline. Capture lasted **25.449–25.450 s** in every observation; the largest
  single-block lateness was **25.353 ms**, with most runs below 4 ms.

The harness checks captured, stored and processed sample accounting. It checks
the wall-clock capture duration after completion as well as bounding input before
capture; the duration assertion is not an active watchdog. Recognition is allowed
to continue past 30 seconds to measure the requested catch-up time.

This is a **warm-model, paced pipeline test**, not a live microphone/UI/worker-IPC
test: ASR is preloaded and warmed with one second of silence, and the speaker
model is loaded before capture. Cold loading, UI rendering, insertion into other
apps and Binder overhead are not included in the main timing figures.
`first_text_with_setup_ms` additionally records the harness's *serial* setup
duration; it must not be presented as real cold-start latency because production
capture/model preparation overlap. No frozen ASR evidence was used in these runs.

### Target and scope

- Dedicated LDPlayer **UtterlaneCrispQA**, index 1, serial `emulator-5556`.
- Android API 28, four virtual CPUs, approximately 8 GB RAM; ARM64 translation
  on an x86-64 host. The regular user LDPlayer instance stayed running.
- QA package `io.github.lrq3000.utterlane.asrspeed`, debug 2.1.0 / 210.
- Application base `54a3dca`; harness commits `3e6b730`, `cf3705c`, `4bb5c2d`,
  `6f09710`. Instrumentation evolved as diagnostics were added; the application
  and native libraries stayed fixed. The balanced window comparison below used
  the same instrumentation version throughout.
- Actual neural trials varied ASR threads **2/3/4/8**, maximum windows
  **4/6/7/8/9/10 s**, left context **0.5/1 s**, right context **0.5/1/1.5 s**,
  and silence duration **200/300/600 ms**. Minimum window stayed 3 s and amplitude
  threshold stayed 200. The cheap structural screen additionally inspected
  100 ms silence and amplitude thresholds 400/800/1600, without neural inference.
- Only the existing 70-word single-speaker bilingual fixture was used. This
  avoids exceeding the recording limit, but cannot establish general accuracy,
  physical-phone speed, or sustained behavior beyond 30 seconds.

## Most informative repeated comparison

An A–B–B–A comparison retained four ASR threads and one second of context on each
side. Only the maximum window changed:

| Order / tag | Window | First text | Catch-up after Stop | WER |
| --- | ---: | ---: | ---: | ---: |
| `live_t4_w10_c1_c` | 10 s | 37.884 s | 48.575 s | 2.86% |
| `live_t4_w8_c1_b` | 8 s | 26.790 s | 46.041 s | 7.14% |
| `live_t4_w8_c1_c` | 8 s | 27.412 s | 44.034 s | 7.14% |
| `live_t4_w10_c1_d` | 10 s | 32.485 s | 41.448 s | 0% |
| **10 s mean** | | **35.185 s** | **45.012 s** | |
| **8 s mean** | | **27.101 s** | **45.038 s** | |

The eight-second window delivered first text about **23% earlier**, but catch-up
did not improve. All four runs still had 25.449 s of audio outside completed
windows at Stop. **This fails the requested joint objective**, and its word
accuracy also regressed.

The work counters explain the cancellation:

- The ten-second profile first permits inference after approximately **11 s**
  of audio and processes **29.449 s** of ASR input, including overlap.
- The eight-second profile first permits inference after approximately **9 s**,
  but processes **31.449 s** across four calls rather than three.
- Mean ASR computation in the balanced comparison increased from **59.376 s**
  to **61.429 s**, consuming approximately the two seconds saved in input waiting.

Backlog samples alone would be misleading. In an earlier eight-second run, one
window finished just before Stop and backlog was 17.449 s rather than 25.449 s.
That does not establish a proportional reduction in remaining wall-clock work.
Always inspect actual post-Stop catch-up alongside window-completion progress.

## Other user-facing setting screens

These are individual screening observations unless noted. Host/emulator drift
was substantial: ordinary four-thread baseline A–D catch-up ranged from 37.303 to
51.715 seconds. Do not turn small cross-run differences into speedup claims.

| Configuration | First text | Catch-up | Word result / interpretation |
| --- | ---: | ---: | --- |
| 2 threads, ordinary windows | 48.098 s | 83.405 s | Exact reference, much slower |
| 3 threads, ordinary windows | 36.324 s | 53.473 s | Exact reference; no established gain |
| 8 threads, ordinary windows | 47.008 s | 91.121 s | Exact reference, much slower; warm-up also expensive |
| 6 s window, 1/1 s context | 20.281 s | 44.501 s | Earlier text; 1 deletion + 3 insertions |
| 4 s window, 0.5/0.5 s context | 13.564 s | 42.662 s | Fast preview; 11.43% WER |
| 10 s window, 0.5/0.5 s context | 29.283 s | 33.661 s | Timing-only candidate, but **5 deleted reference words** |
| 6 s window, 0.5/1.5 s context | 20.921 s | 44.010 s | **7 deleted reference words** |
| 200 ms silence, ordinary context | 17.695 s | 47.995 s | Earlier text, no catch-up win; 8.57% WER |
| 200 ms silence, right context 0.5 s | 15.065 s | 36.149 s | Timing-only candidate, but **14.29% WER** |
| 300 ms silence, ordinary context | 30.142 s | 36.553 s | Small single-run timing difference, 1 substitution |

Shorter silence cuts can be useful, but the 300 ms screen did not release the
first window earlier: the actual segmentation plan still starts inference at
11 seconds. Its subsecond first-text difference is not evidence of reduced
first-window buffering. The 200 ms setting did release the first window at
5.658 seconds, while increasing overlap and ASR call count.

These errors are model/segmentation output errors, **not dropped captured audio**.
All samples were retained and processed. No run lost the final reference tail.
Baseline ASR itself sometimes inserted two extra words on the same fixture; the
report keeps that variation visible rather than changing the reference.

## Confirmation with speaker labeling

Speaker labeling was Auto, not fixed-one (which would bypass the native model).
The current Low-latency / batch-16 / 192/192/160 configuration was retained.

| Profile | First text | Catch-up after Stop | Native speaker forwards |
| --- | ---: | ---: | ---: |
| Default 10 s, baseline A | 36.344 s | 69.109 s | 4 |
| 8 s, full context | 29.105 s | 70.816 s | 4 |
| 6 s, full context | 25.717 s | 82.788 s | 6 |
| Default 10 s, baseline B | 33.971 s | 61.348 s | 4 |

The shorter windows again improved first output but **did not improve catch-up**.
Six-second windows added speaker forwards as well as ASR overlap. All four runs
kept one speaker identity, no unknown labels and 100% speaker accuracy on matched
words; ASR wording errors remained separate and visible in the CSV.

## Native bottleneck: encoder computation

To distinguish real compute from scheduling overhead, QA captured upstream
`CRISPASR_PARAKEET_BENCH`, `CRISPASR_PARAKEET_ENC_PROBE` and
`CRISPASR_PARAKEET_DECODE_TIMING` output. The capture is scoped to the isolated
test process, and restores stderr and the probe environment afterward.

For `live_probe_default`, excluding the initial silent warm-up:

| Component | Measured duration |
| --- | ---: |
| Total timed ASR | 51.014 s |
| **Encoder graph execution** | **49.721 s (97.465%)** |
| Encoder graph build + allocation | **0.0078 s** |
| Mel preprocessing | 0.091 s |
| Decoder stage | 1.162 s |

These are native wall-clock timers, not sampled CPU instructions. Execution time
includes emulator/host scheduling. They nevertheless identify encoder execution
as the dominant stage for this configuration. The ggml CPU hot paths passed the
optimization checker; `parakeet.cpp` and `parakeet_orchestrate.cpp` also compile
with `-O3`. This is not explained by accidentally unoptimized model code.

### Backend-only experiments (not existing UI settings)

- **ggml decoder:** no demonstrated improvement in both objectives.
- **ggml decoder plus backend encoder projection:** native logs confirm the
  alternate path actually ran on the CPU. First text was **30.379 s**, catch-up
  **36.932 s**, versus **30.209 / 36.646 s** for the nearby probed default. Both
  had zero WER. Decoder-stage time increased from **1.162 to 2.557 s**; modest
  encoder timing variation masked part of that increase in the total.
- **FFN weight repacking:** the selected override was recorded, but the native
  log did not emit the loader's expected repacked-partition confirmation. The
  inspected loader falls back when no eligible optimized buffer/kernel exists.
  Treat this as **no demonstrated activation or gain** on this build/target,
  not a benchmark of successful repacking. First text/catch-up were
  **30.063 / 37.131 s**.
- **Encoder graph cache:** deliberately not enabled. The pinned native source
  explicitly documents stale-buffer corruption on repeated same-shape inference
  and leaves it opt-in for diagnostics. Even a correct replacement would target
  only milliseconds of graph setup here, not the measured 49.7 s of execution.

The native model reported a 24-layer, 1024-dimensional encoder, CPU backend,
four threads and encoder BLAS off. Source:
[`parakeet.cpp`](https://github.com/CrispStrobe/CrispASR/blob/966561aa596cfc653aa0e9885d44117fad9cca35/src/parakeet.cpp)
and `core/gguf_loader.cpp` at the same pinned revision.

## Recommendations and remaining work

1. **Retain four ASR threads on this four-vCPU target.** Neither underusing it
   with two threads nor oversubscribing it with eight helped.
2. **Keep the existing window/context defaults for now.** The measured earlier
   preview is not a joint win when post-Stop time is unchanged/worse or words are
   lost. Timeout and queue-budget changes do not accelerate the encoder, and
   stopping capture early would violate the input-preservation objective.
3. **Target encoder execution for a material improvement in both timings.**
   Runtime/kernel acceleration or a separately evaluated model/quantization
   variant is more promising than decoder or graph-allocation tuning. This study
   fixes Ultra Q8 and does not establish which alternative would be better.
4. **A separately bounded first-window budget is a plausible future scheduling
   experiment.** It could start inference earlier while retaining larger later
   windows, rather than paying short-window overlap throughout the recording.
   It is not an existing setting, was not implemented or benchmarked here, and
   must still pass both timing and accuracy checks.

No tested profile achieved zero catch-up. The result is specific to the supplied
short bilingual fixture and this translating emulator; it is not a declaration
that real-time Ultra Q8 is impossible on a physical phone.

## Reproduction and verification

Build and install matching QA APKs in an exclusively assigned emulator. Existing
model and fixture paths are those of `DiarizationFixtureAndroidTest`. Grant the
QA app `READ_EXTERNAL_STORAGE` on API 28. Use unique tags; the host tool refuses
to overwrite a completed/failed observation. Every real-time input is bounded
to 30 seconds and repeat-concatenation/evidence-only recognition are rejected.

```text
gradlew.bat assembleDebug assembleDebugAndroidTest -PqaApplicationIdSuffix=.asrspeed --console=plain --quiet
python tools/qa/diarization_speed.py --serial emulator-5556 --package io.github.lrq3000.utterlane.asrspeed --tag baseline --fixture test-1-speaker-french --realtime --diarization off --option asr_threads=4 --option asr_window_seconds=10 --option asr_min_seconds=3 --option asr_left_context_seconds=1 --option asr_right_context_seconds=1 --option silence_duration_ms=600 --option silence_amplitude=200 --output qa-artifacts/transcription-speed --compact
python tools/qa/diarization_speed.py --output qa-artifacts/transcription-speed --report docs/qa/transcription-speed-20261008.csv
```

Use `--diarization on` for the combined path, and override one setting at a time
or document a paired profile. `--native-probe` captures upstream native counters;
`--asr-decoder ggml --asr-projection backend` and `--asr-ffn repack` are QA-only
native experiments. Run `DiarizationFixtureAndroidTest#segmentationPlans` separately
for the cheap pause-boundary screen. Do not run the entire instrumentation class
when only a bounded timing trial is intended.

Validation completed:

- Debug app/test APKs built and installed. The initial combined terminal command
  timed out after producing APKs; the subsequent ordinary incremental completion
  check passed. No forced clean/rebuild loop was used.
- **29 successful paced acoustic recordings** plus the 16-profile segmentation
  screen (one successful instrumentation test, 0.79 s).
- **22 focused JVM tests**: 8 pipeline, 8 runtime-options and 6 segmenter-options.
- **50 Python tests**: scorer, native adaptation and speaker-frame evidence.
- Optimized CPU-kernel compilation check passed; focused independent review
  found no blocking measurement/concurrency issues.
- Numeric CSV export contains all 29 successful observations. Full per-run
  commands/options, word evidence and one-second backlog traces remain local.
- The QA instance was stopped and its ownership released after evidence export;
  its existing models/data and the user's regular LDPlayer instance were retained.

### SHA-256 provenance

| Input/build | SHA-256 |
| --- | --- |
| Ultra Q8 model | `ebf1186c3dc7e77f71877a5380a73e39d5c0aaf5cb55e65e56b077b1b2aacef1` |
| Source M4A | `da70c308f4a2e3df83482733dbae8bda55b151b94f3d8cbc1706bbd17cbfb399` |
| Decoded PCM (407,186 samples) | `dd4c1b5b7d0024e8879fe0436cf5d7e507c64ac166bd59616d3d085e905ec1d9` |
| QA app APK | `613fb8300e580a26728eb749cd1bee20036c43d1e3cb65fca4874ae5924c5fd2` |
| Final instrumentation APK | `fa3a61ee63f550566eb0fad5561776223b460a66bb0a8736b5b05f78c17bc8ab` |
