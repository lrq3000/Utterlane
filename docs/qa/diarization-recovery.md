# Streaming diarization and recovery verification

Worktree: `.worktrees/diarization-recovery`; branch: `fix/diarization-recovery`.
Base: `1b1c12e`. Work was implemented as focused conventional commits, preserving
the origin/main source and the maintainer's original untracked test material.

## What changed

1. A timestamp-free scorer separates word edits, full-reference coverage,
   recording-wide speaker mapping, unknown fragments, and turn errors.
2. Validated `RuntimeOptions` centralizes **35** operational settings; grouped
   atomic edits prevent unrelated preferences being overwritten. Advanced starts
   collapsed and supports drafts, contextual help, reset and effective values.
3. The worker uses awake computational-progress budgets instead of a fixed
   90-second whole-request cutoff: connection 30 s, preparation/inference stall
   300 s, optional absolute cap disabled. Zero disables each limit. Genuine
   completed native work renews a stall budget; heartbeat/start/duplicate packets
   do not. Opaque operations retain an explicit configurable fallback.
4. ASR word ends and generic timing metadata survive JNI. Each ordinary ASR window
   is recognized once with unchanged ownership/PCM; speaker estimates annotate
   words without cutting custom audio into separate recognition calls.
5. A bounded probability timeline handles interval evidence, confirmation,
   timestamp tolerance, punctuation, brief same-voice gaps, and speech onsets
   after retained, inspected silence. Identity persists across windows and pauses.
   Fixed-one mode bypasses native diarization entirely.
6. Explicit speaker EOF finalization handles zero right context and pending labels
   without changing Off/On ASR cuts or repeating recognition. Cleanup respects
   cancellation, completed transcript ownership and immediate idle unloading.
7. A source-pinned native adaptation exposes completed-work callbacks and bounded
   cache/FIFO/update settings. Native catch-up is explicit; the measured default
   is **8**. The default same-speaker bridge is **1000 ms**. Neither grows the ASR
   window or waits for a complete recording. Failed native drains are distinct
   from successful buffering and terminate the affected stream safely.
8. Capture buffers, queue limits, wake recovery and download timeouts use operation
   snapshots. Cancelling a download interrupts blocking I/O even with timeout 0.
9. Stage, awake elapsed, progress-age and backlog UI accompany optional local
   content-free diagnostics. Logging consent is captured at operation entry,
   including time spent waiting for the inference mutex. Queue/disk/export bounds
   are tested; no automatic upload is implemented.

## Input evidence

The operator supplied two private 44.1 kHz stereo AAC files and three text files
per recording under `test_material/streaming_diarization_accuracy/`. Original
assets remain local and unchanged; test code/scorers, not private recordings,
are committed.

| Recording | Length | Supplied current output | Expected |
| --- | --- | --- | --- |
| One speaker, French/English switching | 25.449 s | 9 blocks, 5 unknown blocks, only 45/70 words; 25 missing tail words | One identity throughout, full text |
| Two speakers, three turns each | 46.835 s | 25 blocks, 13 unknown blocks; 128/128 words | Two persistent identities, six turns |

The provided plain transcripts match their word references. Plain text does not
provide reference timestamps: the scorer does **not** claim time-based DER.

## Actual acoustic runs

Device: dedicated LDPlayer **UtterlaneCrispQA**, instance 1, API 28, four virtual
CPUs / 8 GB RAM, ARM64 translation. ADB endpoint `127.0.0.1:5557`, server `5038`.
QA app ID: `io.github.lrq3000.utterlane.diarrecovery`.
Speech model: catalog Moondream Ultra Q8_0; speaker model: pinned official
Nemotron-3-Diarization Q8_0. CPU threads: four for each model.

### Attribution and wording

- Live single-speaker batch-8 replay: **70/70 words, one turn, zero unknowns,
  100% matched-word speaker accuracy**.
- Live two-speaker batch-8 / 1000 ms bridge replay: **six turns, zero unknowns,
  100% matched-word speaker accuracy**. All 128 output words are present.
  This engine/environment recognizes one word differently from the operator's
  reference (WER **0.78%**); the same substitution occurs in its plain Off run.
  Paired On/Off wording is identical. It is not hidden by changing the gold text.
- Ninety-three-second continuous replay (two copies of the two-speaker audio):
  **1,498,710 samples fed once**, 30 native forwards, complete output. Original
  recorded evidence exposed one missed onset after a long silence. The final
  bounded speech-island fix was validated by replaying those exact recorded ASR
  timings and posteriors with their original per-call availability: **12 turns,
  zero unknowns, 100% matched-word speaker accuracy**.
- Evidence replay is postprocessing validation using actual model outputs,
  **not another neural inference benchmark**. Recorded ASR differences remain
  visible (the repeated run has WER 3.12% against repeated reference wording).
- Some repeated native ASR runs produced slightly different tokens on identical
  PCM SHA-256 inputs (e.g. an extra short phrase). This is reported as native ASR
  variation, not repaired by editing recognized words or attributed to the label
  formatter. Label scoring and same-build wording parity are separate metrics.

### Speed measurements

The initial strict run was before explicit test warm-up; later runs use the
application's one-second silent ASR warm-up. These are individual emulator
observations, not controlled phone benchmarks.

| 25.45 s fixture | Strict batch 1 | Batch 8 |
| --- | ---: | ---: |
| Native speaker forwards | 53 | 8 |
| Speaker inference total | 199.134 s | 37.251 s |
| ASR inference total | 56.644 s | 55.528 s |
| Processing total, excluding model loading | 256.830 s | 93.539 s |

Thus the observed speaker stage was approximately **5.35× faster**, while ASR
cost stayed similar. Batch 4 and 16 were also exercised; batch 16 did not improve
the observed end-to-end times over the best batch-8 runs, so 8 remains the default.
All factors remain configurable; accuracy and thermal/CPU behavior can differ.

Additional short-fixture screening used the final attribution controls and ASR
warm-up. Times exclude model loading/warm-up; these remain individual runs:

| Configuration | Native forwards | Processing time | Speaker result |
| --- | ---: | ---: | --- |
| Batch 2, four speaker threads, very-low latency | 28 | 176.827 s | One turn, no unknowns, 70/70 words |
| Batch 8, two speaker threads, very-low latency | 8 | 128.919 s | One turn, no unknowns, 70/70 words |
| Batch 8, four speaker threads, low latency | 7 | 92.177 s | One turn, no unknowns; recorded raw ASR phrase variation |
| Batch 8, four speaker threads, ultra-low latency | 15 | 134.434 s | One turn, no unknowns; recorded raw ASR phrase variation |

The screening covers all planned batch factors 1/2/4/8, an additional factor 16,
thread-count comparison, and every streaming preset. It is not a Cartesian search
of every configuration, and close short-run timings are not treated as proof of
a better universal default. The selected profile is also exercised beyond cache
warm-up, separately from these short screening runs.

The 93.67 s run completed in **509.420 s** on this translating emulator. Beyond
the conservative 60 s warm-up classification, five application chunks had speaker
durations 21.358, 39.877, 21.421, 11.434 and 24.540 seconds, processing different
amounts of new audio; final flush also has overhead. Warm speaker RTF was **3.783**,
and end-to-end RTF **6.314**. This is **not real-time on this emulator**. Native
history, input, and batching remain bounded; wall-clock chunk time is not promised
to be identical. No real-phone throughput guarantee is inferred from these runs.

A supplementary **fresh native low-latency** replay of the same 93.67 s repeated
audio completed in 497.03 s including setup. Unlike posterior relabeling, this
run exercised the final implementation end to end: **12 turns, zero unknowns,
100% matched-word speaker accuracy**, with the same documented 3.12% reference
word error from raw ASR. Warm speaker RTF was 3.736 versus 3.783 for very-low
latency; warm overall RTF was 6.452 versus 6.314. These close/noisy results do not
establish a superior preset, so the existing very-low-latency preset is retained.
Both remain user-selectable; batching provides the much larger observed gain.

### Watchdog, lifecycle, and UI

- Actual worker run with **45 s stall budget and no absolute cap** completed a
  request lasting **133,586 ms of awake time**, with genuine native progress and
  the final transcript tail intact. This directly covers the former 90 s cutoff.
- Focused final device batch: **14 tests passed in 32.57 s**, including all 12
  idle-lifecycle tests (new explicit speaker-finisher lease included), generic
  Whisper timing/JNI smoke coverage and native boundary finalization.
- Recorded-evidence replay plus scheduling/options/JSON checks: **6 Android tests
  passed** without another expensive inference run.
- UI-tree-based interactions verified Advanced expansion, recovery field editing
  from 300 to 600 s, saved values, group reset, and diagnostic chooser launch.
  The chooser was dismissed without selecting a receiving app. Screenshots:
  `qa-artifacts/advanced-expanded.png`, `recovery-options.png`,
  `recovery-saved.png`, `diagnostics-share.png`.

## Automated verification and review

- Full JVM suite: **237 tests, zero failures** at the final integration checkpoint.
- Python scorer/adaptation/trace tests: **50 passed**.
- ARM64 app/test APK builds passed. A transient Windows Kotlin-daemon backup
  cleanup error fell back successfully; subsequent commands use
  `-Pkotlin.compiler.execution.strategy=in-process` without forced rebuilds.
- Independent spec/quality reviews addressed fixed-count slot confirmation,
  punctuation, word ownership, cancellation publication, atomic settings edits,
  blocking network cancellation, per-operation diagnostic consent, native failure
  signaling and EOF finalization. Added regression tests remain committed.

## Reproduction

```text
python tools/prepare_native.py
gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest "-PqaApplicationIdSuffix=.diarrecovery" "-Pkotlin.compiler.execution.strategy=in-process" --console=plain -q
python -m unittest tools.qa.test_diarization_regression tools.qa.test_native_stream_options tools.qa.test_speaker_frames
```

Install the matching app/test APKs on the dedicated endpoint. The fixture runner
expects the two M4As under `/sdcard/Download/diarization-qa/`, Ultra Q8 under
`/sdcard/Download/parakeet-qa/`, and the official speaker GGUF under Downloads.
Supply storage permission on API 28. Run one scoped test at a time:

```text
adb -P 5038 -s 127.0.0.1:5557 shell am instrument -w -e class io.github.lrq3000.utterlane.DiarizationFixtureAndroidTest#replay -e fixture test-2-speakers-french-3-turns -e tag candidate io.github.lrq3000.utterlane.diarrecovery.test/androidx.test.runner.AndroidJUnitRunner
```

Arguments include `diarization=false`, `speakers=1`, `repeats=2`, and validated
`option_<runtime_key>` overrides. Outputs reside in the QA application's external
private `diarization-runs/<tag>/` directory. The scorer supports
`--plain-baseline`, `--repeat-reference`, and performance JSONL; see
[scoring documentation](diarization-scoring.md). Strict gold checks intentionally
still fail on pre-existing native ASR substitutions even when speaker labels and
paired wording are correct.

Final docs include changed local-diagnostic privacy/retention behavior. No remote
CI, F-Droid release build, model-wide quality certification, or physical-phone
performance test is claimed by this local verification.
