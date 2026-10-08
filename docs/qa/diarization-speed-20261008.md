# Diarization throughput investigation — 2026-10-08

## Outcome

**Adoption follow-up:** After reviewing these results, the maintainer requested
the **192 / 192 / 160** profile as the default. It is now used for unset/reset
context settings; explicitly saved profiles are preserved. The measurements
below remain the original study, whose baseline was **264 / 264 / 222**.

Keep **Low latency, batch 16, four diarization threads** as the baseline.
The most promising additional settings on this LDPlayer target are:

| Setting | Baseline at study time | Measured profile (now default) |
| --- | ---: | ---: |
| Speaker cache frames | 264 | **192** |
| FIFO frames | 264 | **192** |
| Update frames | 222 | **160** |

In the closest baseline/candidate/baseline comparison, native speaker processing
took **146.951 / 119.029 / 148.960 seconds** for the same 93.669-second recording.
The candidate used approximately **20% less diarization time**. It preserved all
12 expected speaker turns, with zero unknown words and 100% speaker accuracy on
matched words. This is a measured **experimental profile**, not an established
safe default for eight-speaker meetings or speakers returning after long absences.
Production defaults were not changed during the measurement phase; the adoption
follow-up above is a separate maintainer-approved change.

The full numeric evidence is in [the generated CSV](diarization-speed-20261008.csv).
Raw local evidence is under
`.worktrees/diarization-speed-study/qa-artifacts/diarization-speed/<tag>/`.
It contains private speech-derived data and is not committed.

## Setup and method

- Application source: `b5e3074`; QA instrumentation additions: `97e631b` and
  `120ee3f`. Native sources: CrispASR `966561aa596cfc653aa0e9885d44117fad9cca35`
  with the existing checked Utterlane adaptation.
- Target: dedicated **UtterlaneCrispQA**, LDPlayer instance 1, Android API 28,
  four virtual CPUs, approximately 8 GB RAM, ARM64 code translated on x86-64.
  Resolved serial: `emulator-5556`; QA package:
  `io.github.lrq3000.utterlane.diarspeed`, version 2.1.0 / 210, debug build.
- Models: Ultra Q8_0 ASR and Nemotron-3-Diarization Q8_0. All relevant ggml CPU
  compile entries passed `check_native_optimization.py`; this was not an
  unoptimized `-O0` kernel comparison.
- Workload: the existing 46.835-second two-speaker fixture concatenated twice
  within one session, totaling **93.669375 seconds** and twelve reference turns.
  A separate 25.449125-second single-speaker French/English fixture checked
  wording, false fragmentation, and the final transcript tail.
- Settings were explicit and isolated in operation snapshots. ASR windows,
  confirmation, bridging, lookahead, thresholds and speaker mode stayed fixed.
  Automatic speaker detection was used even for the single-speaker fixture;
  fixed-one mode would bypass native diarization.
- Configurations ran serially, each in a new instrumentation process. The regular
  LDPlayer instance remained running. Host load and ARM translation effects were
  not experimentally isolated, so close results are not reliable rankings.

### Fresh ASR versus fixed-ASR experiments

First, the normal fixture runner performed both neural ASR and neural diarization.
Subsequent `native*` runs re-decoded the original audio, required the SHA-256 and
length of every ASR PCM window to match that first run, and reused its real ASR
words/timings. **The speaker model still ran from scratch on every run.** This
removes expensive repeated ASR and its wording variation from the settings sweep;
it is not posterior relabeling and is not an end-to-end speed measurement.

Each run checks accepted/processed/speaker-fed sample equality. Fixed-ASR runs
also require complete evidence consumption. An intentional change to two-second
ASR windows failed with `ASR evidence PCM differs from this window`, confirming
that mismatched cuts are rejected rather than silently compared.

`native_stage_ms` records intervals between completed-work callbacks. Features
include mel extraction and embeddings; transformer includes graph preparation,
allocation, input transfers and compute; cache includes the cache update. These
are wall-clock stage timings, **not sampled CPU or per-kernel profiles**. Native
forward times and per-window timings are retained separately.

Warm comparisons use only whole chunks starting at or after **60 seconds of
audio**, independently of the context settings. There are just five such chunks
per long run. p95 is nearest-rank over fourteen total chunks, not a statistically
precise device-wide latency estimate. EOF draining is included in speaker totals
and, when performed inside the final audio window (as in these runs), in that
chunk's duration and warm RTF. A separate zero-input finish call outside a window
would not enter the chunk RTF; this study does not isolate drain-only cost.

## Measured results

All rows below use Low latency. Times are **native speaker-stage seconds**, not
ASR+speaker completion times. Each letter is an independent run, in CSV order.

| Configuration | Runs (seconds) | Native forwards | Interpretation |
| --- | --- | ---: | --- |
| Batch 16, 4 threads, 264/264/222 | 218.655, 164.350, 146.951, 148.960 | 15 | Baseline; substantial early drift |
| Batch 8, 4 threads, 264/264/222 | 260.673, 218.929 | 23 | More repeated forwards; supports retaining 16 |
| Batch 16, 2 threads, 264/264/222 | 317.622 | 15 | Slower screening result; no reason to reduce threads here |
| Batch 16, manual attention, 264/264/222 | 215.469 | 15 | No convincing improvement over default CPU flash attention |
| Cache reduction only: 192/264/222 | 181.413 | 15 | Screening observation, confounded by early drift |
| FIFO/update reduction: 264/192/160 | 189.642 | 15 | Screening observation, confounded by early drift |
| Combined reduction: 192/192/160 | 136.344, 128.506, 119.029 | 15 | Repeated promising result; final bracket supports about 20% |

Every long run produced the same twelve correctly mapped speaker turns, no
unknown words, and 100% speaker accuracy on the **251 matched reference words**.
This does not mean perfect transcription: the frozen ASR had 257 candidate words
against 256 reference words, with three substitutions, two deletions and three
insertions (WER 3.125%). No trailing reference words were deleted. Reference
word coverage is 98.05%; the gold text was not modified to hide ASR errors.

### Why the earliest comparison is not the final speed claim

The unchanged native baseline fell from 218.655 to roughly 148 seconds over the
session. Reporting the earliest baseline against the fastest candidate would
overstate the gain. The final bracket was:

| Run | Speaker time | Chunk p50 | Chunk p95 | Warm chunk RTF |
| --- | ---: | ---: | ---: | ---: |
| `native16_t4_c` (baseline) | 146.951 s | 10.762 s | 20.857 s | 2.121 |
| `native16_t4_context192_c` | **119.029 s** | **8.686 s** | **15.127 s** | **1.660** |
| `native16_t4_d` (baseline) | 148.960 s | 10.843 s | 20.353 s | 2.138 |

That supports a local candidate effect beyond the approximately 1.4% difference
between the two bracketing controls. It does not establish confidence intervals,
phone performance, or a universal optimum. Even the candidate is **not real-time
on this emulator** once the context is warm.

### Fresh-ASR confirmation

The selected profile was then run with neural ASR enabled again:

- **93.669-second two-speaker replay:** 347.173 s total processing, including
  225.367 s ASR and 119.783 s speaker processing. Fifteen forwards, twelve turns,
  no unknowns, the same WER and 100% matched-word speaker accuracy.
- **25.449-second single-speaker replay:** 78.829 s total, including 54.477 s ASR
  and 23.735 s speaker processing. Four forwards, **70/70 words**, one speaker
  turn, no unknowns, no tail loss, zero WER.

These totals exclude model loading/warm-up. They exercise the shared segmenter,
ASR backend, native speaker stream, attribution, and transcript formatter inside
the fixture runner. They do **not** measure microphone pacing, production worker
IPC, UI responsiveness, or actual post-Stop delay. Do not claim an end-to-end
percentage improvement from the earlier 550.510-second baseline: its ASR time
alone was 334.974 seconds despite unchanged ASR settings, demonstrating drift.

## Implementation findings and next optimizations

1. **Keep amortizing historical transformer work.** Batch 16 reduced forwards
   from 23 to 15 on this workload. Native catch-up consumes only complete,
   already-buffered steps; it does not wait for a full batch. Under the default
   ten-second ASR windows, approximately fourteen low-latency base steps fit in
   a push. Raising the cap beyond sixteen would not normally provide more work
   to merge without changing window scheduling.
2. **Smaller retained context is the strongest measured additional lever.**
   `SpeakerCache.get_embeds()` concatenates representative speaker history and
   recent FIFO embeddings; `n3d_stream_step()` appends the new chunk and runs the
   transformer over the combined sequence. Reducing context cuts transformer
   work, not merely storage allocation. Cache 192 also lowers representative
   capacity per speaker; FIFO 192 shortens recent context. Validate four-to-eight
   speakers, overlap, short interjections and long return gaps before changing
   general defaults. The paired FIFO/update reduction preserves validation
   constraints; neither individual cache lever has a confirmed isolated effect
   size in this study.
3. **Do not optimize the cache-maintenance routine first.** In the initial full
   run, transformer-stage time was 211.192 s out of 212.330 s of diarization
   (99.46%), features 1.061 s, cache update 0.062 s. Eliminating cache-update code
   cost would barely affect total time; reducing its retained transformer input
   has a very different, much larger effect.
4. **Separate graph preparation from compute before a native refactor.**
   `run_chunk()` currently constructs a ggml graph and resets/allocates its
   scheduler on each forward. Bounded reuse of graphs/buffers for recurrent
   shapes is a plausible exact-result optimization, but these callbacks do not
   isolate graph cost from neural compute. Upstream has optional internal
   build/compute counters, whose report is exposed by the whole-recording API,
   not our incremental stream. Exposing those counters to a QA-only path is the
   next narrow measurement before implementing graph reuse. Avoid unbounded
   shape caches or padding that creates more attention work than it saves.
5. **An autoregressive-style KV cache is not a drop-in fix.** This forward uses
   full attention over the selected history and current chunk, and restarts
   positional indices each time. Historical hidden states can change with the
   new context; blindly reusing deeper-layer K/V would change model semantics.
6. **Retain optimized kernels and four threads pending stronger evidence.**
   CPU flash attention is already enabled by the native default and ggml kernels
   are compiled optimized. The one manual-attention screen did not establish a
   gain. Physical ARM profiling is needed before attributing LDPlayer timings
   to a particular kernel or selecting a phone-wide thread policy.

Source: pinned
[`nemotron3_diar.cpp`](https://github.com/CrispStrobe/CrispASR/blob/966561aa596cfc653aa0e9885d44117fad9cca35/src/nemotron3_diar.cpp),
particularly `SpeakerCache`, `n3d_stream_step`, `n3d_stream_drain`, and `run_chunk`.

## Reproduction

Use a dedicated, explicitly assigned emulator. Build matching APKs with the
normal source/native prerequisites, install them, and grant
`android.permission.READ_EXTERNAL_STORAGE` on API 28. Fixtures and models must
be at the existing paths in `DiarizationFixtureAndroidTest`. A clean checkout
includes only the public single-speaker fixture; the two-speaker fixture remains
maintainer-local. `--references` selects the local reference directory without
copying private inputs into the repository.

```text
gradlew.bat assembleDebug assembleDebugAndroidTest -PqaApplicationIdSuffix=.diarspeed --console=plain --quiet
python tools/qa/diarization_speed.py --serial emulator-5556 --tag baseline --repeats 2 --option diarization_mode=low_latency --option diarization_batch=16 --option diarization_threads=4 --option native_cache_frames=264 --option native_fifo_frames=264 --option native_update_frames=222
python tools/qa/diarization_speed.py --serial emulator-5556 --tag candidate --repeats 2 --asr-source-tag baseline --option native_cache_frames=192 --option native_fifo_frames=192 --option native_update_frames=160
python tools/qa/diarization_speed.py --report docs/qa/diarization-speed-20261008.csv
```

Tags must be unique; the host runner refuses to overwrite observations. Use
`--attention manual` for the upstream attention comparison. Each completed run
retains its exact command and source HEAD, instrumentation result, source options,
ASR evidence, probabilities, per-stage performance, transcript and quality report.
`--report` exports only numeric/context metadata to CSV, never transcript text.
Failed/rejected runs have no `analysis.json` and are excluded from that CSV.

Checks performed:

- Debug app and instrumentation APK builds passed after disk-space recovery.
- Sixteen successful acoustic instrumentation runs; one intentional PCM-mismatch
  rejection verified the frozen-ASR guard.
- `python tools/qa/check_native_optimization.py app/.cxx/Debug/6e372dt4/arm64-v8a/compile_commands.json`
  passed for all three relevant CPU kernel translation units.
- `python -m unittest tools.qa.test_diarization_regression tools.qa.test_native_stream_options tools.qa.test_speaker_frames`:
  **50 tests passed**.
- Content-free CSV export produced sixteen completed observations.

The first fresh C++ build exhausted host disk space. With explicit permission,
generated `.cxx` intermediates from two earlier completed worktrees were removed;
the current QA app was then **successfully built from source**, not substituted
with an old APK. At completion, instance 1 was stopped and ownership released;
its existing models/data and the regular running LDPlayer instance were retained.

### Input/build fingerprints (SHA-256)

| Input | SHA-256 |
| --- | --- |
| Nemotron Q8 model | `08456d9e22cd9a323c0364d98375f3746d6e68507ebb705cd46438c534c7a3a1` |
| Ultra Q8 model | `ebf1186c3dc7e77f71877a5380a73e39d5c0aaf5cb55e65e56b077b1b2aacef1` |
| Single-speaker M4A | `da70c308f4a2e3df83482733dbae8bda55b151b94f3d8cbc1706bbd17cbfb399` |
| Two-speaker M4A | `911ca22034366d842165cc75e5b4fb2da94ecd33d01a3ea3c2d95080d75140c0` |
| QA app APK | `f999bd979a90382326a05134d5932b320c082c693ab7b8cbf47dae3da7263a8e` |
| Final QA instrumentation APK | `5d9b0c647f23de1cc91ff4e7066afaa4514dfd4b02bb40d366f202ac09629158` |
