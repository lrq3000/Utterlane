# Progressive archival: integrated recording QA — 2026-10-11

**Recording/archival isolation and affected native storage tests passed. Follow-up
disposition: the exact-word failure used an overstrong determinism assumption.**
One archival-free, same-worker control reproduced BOTH original token variants
from identical PCM. The replacement gate preserves exact input/count/context and
tail checks, all 70 reference words in order, and a maximum of two insertions—the
observed native-control quality envelope. The original failures/results remain
below; the precise native-state/scheduling cause is still unknown. See the
[follow-up disposition](#follow-up-disposition-one-archival-free-control).
Parent release checks remain outstanding.

## Scope and handoff

- Source/build/artifact root: `C:\git\TranSlander`, branch
  `feat/progressive-archival-project`, HEAD
  `72dc059cc9684e64b5f38f10d02daa54c8158086`, with the parent's uncommitted archival
  and new microphone/HFP integration. Read `AGENTS.md` and `CORE_VALUES.md` fully.
- No other checkout/worktree was inspected or used. Native investigation read
  only the root-owned prepared `.native-cache/crispasr` sources. No production
  source, native dependency, build configuration or assertion was weakened.
- Sole device: `emulator-5554`, API 28, LDPlayer 9 at
  `C:\LDPlayer\LDPlayer9`, index 0, `agent-progressive-archival-a418f9`.
  Verified running name/index, ADB state and boot completion. All ADB commands
  carried this explicit serial. No other device, restart, emulator shutdown,
  root toggle, global setting, ADB-server reset, commit or push was used.
- Accepted immediate handoff remains to owner
  `progressive-archival-20261010-a418f9`, generation
  `progressive-archival-20261010-a418f9-1791683540023582900`. The emulator is still
  running; its existing ownership record retains `keep_running=true`.
- The standard app contains the verified ASR model. **`.dhome` remains model-free**
  and retains its real Downloads SAF grant. Its compression toggle was restored
  to OFF after the UI smoke. Final screenshot shows zero queued/running/paused/
  pending-cleanup archival jobs and no active recording.
- One explicitly created `.dhome` UI smoke recording remains for inspection:
  `100edddf-e900-464f-81e6-b7f0fc928718`. Its raw PCM and completed M4A are retained
  on the emulator: **967648 frames / 60.478 s**, `failureKind=MODEL`, `recovery=true`,
  `encodedComplete=true`. The first two 30-second PCM parts and exact tail remain
  alongside the M4A. Metadata also preserves the integrated HFP preset's
  `VOICE_RECOGNITION`, `AGC_ONLY` and `AUTO_LEVEL` choices. See
  `recording-dhome-smoke-{history,metadata}.txt`. Ambient microphone audio was not
  copied to the host or uploaded.

## Source changes made in this QA pass

1. New `app/src/androidTest/java/io/github/lrq3000/utterlane/RecordingArchivalIntegrationAndroidTest.kt`:
   four focused cases covering actual microphone capture, blocked publication,
   slow processing and an opt-in real-model fixture pair. Tests use production
   `MicrophoneSession`, `RecordingPipeline`, histories, coordinator and isolated
   codec/recognition workers. They restore touched preferences and remove their
   generated history entries. The native worker observer records real input
   hashes/tokens; it does not substitute ASR results.
2. `RecordingFirstAndroidTest.kt`: its real-activity case now uses the existing
   `launchNativeActivity` helper, waiting for the actual RESUMED callback rather
   than API-28 `startActivitySync` idle completion.
3. This report. Root-local ignored `qa-artifacts` also contains transport and
   evidence-summary scripts. `ui_step.py` gained upward UI-tree seeking.

All other staged/unstaged feature changes belong to the parent integration.

## Initial native results on the combined tree (before follow-up disposition)

| Coverage | Result | Evidence under `qa-artifacts/` |
|---|---|---|
| New model-free recording integration | **3 passed**, 20.406 s including initial opt-out of the unstaged model case | `recording-integration-first.txt`, `recording-device/*.json` |
| Original real-model OFF/ON equality gate | **1 distinct failing case**, reproduced in four diagnostic invocations; subsequently dispositioned below | `recording-pair-{first,fresh,reversed,boundary}.{txt,json}` |
| Progressive compression codecs/import recovery | **9 passed, 1 skipped**, 7.066 s | `recording-codecs-current-root.txt` |
| Managed archival, real SAF, import retention and export snapshot | **8 passed**, 17.412 s, `.dhome` current-root build | `recording-ui-saf-current-root.txt` |
| Recording-first affected cases | **4 passed**, 3.556 s, `.dhome` current-root build | `recording-first-current-root.txt` |

The initial result was **24 distinct passing native cases, one platform skip and
one failing exact-equality case**. The first integration invocation skipped the optional speech case
before its model/fixture were staged; it was subsequently executed, not counted as
a final skip. The codec skip is specifically absent `c2.android.aac.encoder` on
API 28. OMX AAC, AAC stereo, short/aligned EOS, AMR-NB/WB and mixed recovery ran.
The older `.recordingfirst`-identity-only case was not run under another identity;
the new missing-model integration covers its capture-survival boundary.

### Actual microphone and archival isolation

These are real Android `AudioRecord` sessions using the integrated microphone
configuration, including its default `VOICE_RECOGNITION` source. Missing ASR was
created by selecting the absent catalog ONNX model, not by mocking recognition.
The default GGUF model was installed only after the initial model-free run.

| Session | Actual PCM | Durable encoded frames while capture open | Final source frames | Saved normalized samples |
|---|---|---:|---:|---:|
| First missing-model capture | 16000 Hz, mono | 10240 | 48224 | 48224 |
| Next capture during prior blocked publication | 16000 Hz, mono | 0 for this queued job | 65472 | 65472 |
| Non-native-format capture | **44100 Hz, stereo** | 38912 | 180304 | 65417 |

- Capture remained active after actual model preparation failed; completion waited
  for explicit capture Stop. Before Stop, processing was zero, reclaimed PCM was
  zero, and the raw part remained present.
- The first capture's real compression worker advanced before Stop. Its eventual
  SAF write was intentionally blocked in the debug DocumentsProvider. After the
  microphone session closed, a second actual microphone session acquired admission
  and delivered more than three seconds of input while that worker remained blocked.
- Calling the production `ArchivalCoordinator.forceStopAll()` terminated the archive
  worker, durably disabled encoding, and left the parent and capture alive. Both
  force-stop sessions delivered another full second of normalized input afterward.
- Complete retained audio decoded to the exact expected normalized count. Stereo
  was actually accepted, so this was **not** a fallback/skip. `ceil(frames*16000/44100)`
  equals 65417. Final capture metrics also reported 65417.
- Periodic `capture.properties` frames observed before Stop are a durable lower
  watermark, published approximately once per source second, not an exact snapshot
  of the current in-process capture count. Final source counts above are exact.

### Delayed/slow consumer and original PCM

The production pipeline and real encoder received a deterministic PCM capture
source while the preparation callback was held. With **1,075,200** source frames
already durable, the encoder had committed **26,624**, ASR had accepted zero,
neither of the first two raw parts had been reclaimed, and capture was still open.
After releasing preparation, a deliberately slower consumer observed a backlog of
**1,086,400 samples** while capture continued. On Stop it drained all
**1,120,000 source samples**, checking **every original sample** for equality.
This functional gate is not used for inference timing.

## Real-model speed and accuracy sanity

### Provenance and method

- Local source: `test_material/streaming_diarization_accuracy/test-1-speaker-french.m4a`.
  SHA-256 `da70c308f4a2e3df83482733dbae8bda55b151b94f3d8cbc1706bbd17cbfb399`.
  Reference: `test-1-speaker-french_transcript_true-diarization.txt`.
- `ModelCatalog.DEFAULT`: **Parakeet Ultra Q8_0**, CrispASR through the actual
  Android recognition worker, **674342400 bytes**. Public catalog download:
  `https://huggingface.co/cstr/parakeet-ultra-GGUF/resolve/main/parakeet-ultra-q8_0.gguf`.
  Content pin: SHA-256
  `ebf1186c3dc7e77f71877a5380a73e39d5c0aaf5cb55e65e56b077b1b2aacef1`.
- No cached weights were found in inspected root `assets`, `app`, `test_material`,
  `qa-artifacts` or the fixture's expected device locations. The public binary was
  downloaded into `qa-artifacts/parakeet-ultra-q8_0.gguf` (105.77 s), verified
  against catalog size/hash, staged only for the default app, and verified again
  by **`ModelManager.ensureVerified()`**. Svipall's HEAD reached HTTP 200; its
  document extractor does not transport a GGUF binary, so the binary transfer used
  curl. Provenance is in `recording-model-provenance.json`.
- Decode the local M4A once to a temporary PCM WAV before the pair. Replay that same
  disk source in 1600-sample packets with 20-ms pacing through the production
  `RecordingPipeline`; do not use ambient host speech, frozen results or mock ASR.
  This accelerated fixture replay tests the pipeline, **not acoustic AudioRecord
  accuracy or microphone software gain**. Actual AudioRecord/HFP-source integration
  is covered separately above. No speech was sent to a network service.
- Both arms used default `RuntimeOptions`: four ASR threads, 10-s maximum / 3-s
  minimum windows, 1-s left/right context, 600-ms silence threshold. Diarization
  and dictionary corrections were disabled. Compression ON was AAC-LC/M4A,
  16000 Hz mono, 96000 bps; OFF retained native WAV.
- Total source/accepted/processed counts were **407186** in both arms,
  **25.449125 seconds**. Full accepted PCM SHA-256 was identical:
  `dd4c1b5b7d0024e8879fe0436cf5d7e507c64ac166bd59616d3d085e905ec1d9`.
- The first pair shared one loaded worker. To remove unequal prior-speech history,
  a diagnostic pair used a fresh worker and its normal silence warmup for **each**
  arm, excluded from measured intervals. Reversed order and actual worker-boundary
  observation were subsequently used to investigate the word mismatch. No option
  sweep or new general performance framework was added.

### Within-build comparison (fresh-worker pair)

This is **compression OFF versus ON within the same integrated build**, not a
pre-change baseline. `recording-pair-fresh.json` is the ordinary-manager pair:

| Measurement | OFF | ON |
|---|---:|---:|
| Model load + native warmup, excluded | 3.725 s | 4.607 s |
| Sum of measured ASR-window wall times | **50.715 s** | **50.458 s** |
| Capture start to transcript result | 52.998 s | 52.718 s |
| Capture Stop to transcript result | 47.755 s | 47.523 s |
| Encoded source frames before capture Stop | 0 | 370688 |
| Source / accepted / processed samples | 407186 / 407186 / 407186 | 407186 / 407186 / 407186 |
| Recognized normalized words | 72 | 70 |
| Reference word edits | 2 insertions | 0 |

ON minus OFF ASR wall time is **-0.257 s (-0.51%)** in this pair. Across the initial
and three diagnostic pairs the difference ranged **-0.51% to +1.07%**; the final
boundary-observed pair was +0.343 s (+0.69%). These small, single-pair emulator
differences do not establish a speed improvement or a robust overhead bound.
ASR-window timings include worker/IPC and result-commit overhead; they are not
sampled CPU time. LDPlayer reports x86/x86_64 plus ARM ABIs and `libnb.so` native
translation, while the APK packages arm64 native inference. Absolute timings are
not handset throughput claims. Capture was deliberately faster than real time, so
the large Stop-to-result tail is expected backlog, not evidence of lost frames.

### Initial exact-word discrepancy

Every pair produced the same difference:

- OFF: `… en fait juste pour voir en fait quel effet ça fait. …`
- ON: `… en fait juste pour voir quel effet ça fait. …`

ON exactly matches the supplied reference after removing the speaker prefix. OFF
has two extra words. Unicode lowercase word-token Levenshtein comparison, ignoring
punctuation, gives **0/70 (0%)** versus **2/70 (2.86%)** WER. Both retain the same
last 12 words: **“au moins comme ça je saurai si ça capture bien ou pas”**.

The discrepancy survived fresh-worker initialization and reversed run order.
The final observer wraps the real `WorkerRecognitionBackend` inside the production
manager and records the actual samples passed to its native-worker call plus the
returned tokens. All three window input sizes/hashes match OFF versus ON:

| Owned end | Actual input samples | SHA-256 |
|---:|---:|---|
| 160000 | 176000 | `21bd064cea556bb5814b90bc6b4a533f4a8301c35752746074d38a6c5dd18b4b` |
| 277246 | 149246 | `2ea95a7fef8807a2b305d3d65d3fb642ee589f6c4aff129d2a1fa2103c71c57b` |
| 407186 | 145940 | `812f0964e744cf19c3b75796a8adda6be98b0ff75fd557b8a1cc4fb1197812cd` |

Returned native tokens differ only in window 1; windows 2 and 3 match. This places
the unexplained difference downstream of the identical worker-client PCM boundary.
It is **not evidence that compression dropped or changed ASR PCM**, and it is not
an established root cause in the native runtime, IPC or emulator. Initially the
strict transcript-equality assertion was retained and no production fix was
guessed. The authorized follow-up below reproduces the variation without archival
and replaces that assumption with reference-quality gates. Exact-word release
parity is not claimed. `recording-pair-boundary.json` retains the original
token/timestamp evidence.

## SAF, UI and platform limits

The eight current-tree tests include full Markdown contents, pin/delete/expiry,
provider failure/retry, stopping a blocked worker, remembered import decisions and
immutable picker/export metadata. Real folder:

`content://com.android.providers.downloads.documents/tree/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2FUtterlaneQA-a418f9-ui`

Final `recording-final-grants.txt` confirms persisted read/write (`0x3`) ownership
by `.dhome`. No default-app access to that grant was assumed.

Current-root screenshots under `qa-artifacts/`:

- `recording-quality-on.png` / `.xml`: UI compression enabled.
- `recording-live-model-unavailable.png`: real Home capture, recording timer and
  one running archival job while the speech model is absent.
- `recording-after-stop.png` / `.xml`: capture stopped, New recording available.
- `recording-dhome-model-free.png`: model not installed.
- `recording-handoff-compression-off.png` / `.xml`: restored OFF, archive queue idle.

The live waveform prevented UIAutomator's idle-based XML dump; the failed
`recording-live-model-unavailable.xml` is not a valid tree and is not claimed as
evidence. The screenshot succeeded. Stop used the previously tree-derived recording
toggle coordinates, and the post-Stop tree verified completion. This does not
establish real Bluetooth HFP/SCO routing, Codec2 behavior, API-34+ foreground-service
rules, interruption across reboot, or physical-device thermal/sustained performance.

## Initial build verification and artifact provenance

Initial handoff's required root command, saved in `qa-artifacts/recording-final-build.txt`:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
```

**BUILD SUCCESSFUL**, 3 s, 104 tasks / 18 executed / 86 up-to-date. JVM tests were
**UP-TO-DATE**, not freshly re-executed: unchanged production/JVM sources retain
the integrated report's **1090 tests, zero failures/errors/skips**, checked from
the current XML results. Only Android tests and this report were added/changed.
No clean/cache deletion, native rebuild exclusion or incremental workaround was used.
`git diff --check` and `git diff --cached --check` passed.

The `.dhome` APK pair was built with the CLI-only `-PqaApplicationIdSuffix=.dhome`
and installed for its tests. That command restored **standard identities**.
The app APK below remains unchanged; the updated follow-up test APK is listed
in the final follow-up subsection:

| Root output | Identity | SHA-256 |
|---|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | `io.github.lrq3000.utterlane`, 2.1.0 / 210 | `414425c84a714d9ca2f7be9dbe1b3b5c41baac7cf2703f973cf6a33641efcc9f` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | `io.github.lrq3000.utterlane.test` | `fc96ea2ef5618bf766cab45f8ef3c6ae8e1033d2850170a39e200f46357cb42e` |

The standard app was installed from this root before native tests; its unchanged
production APK remains installed. Its updated test APK is also installed. `.dhome`
and `.dhome.test` remain separately installed from the current-root suffix build.
At that initial handoff the last source adjustment only reordered independent parity assertions so counts
and the final tail are checked before full-text equality; it compiled in the final
APKs. The four diagnostic logs precede that assertion-order-only edit and all show
the same unresolved equality failure.

The current paired real-model case (now enforcing input/reference quality rather
than deterministic text) uses the staged default-app fixture and reference:

```powershell
& "C:\LDPlayer\LDPlayer9\adb.exe" -s emulator-5554 shell am instrument -w -r -e recordingFixture true -e class io.github.lrq3000.utterlane.RecordingArchivalIntegrationAndroidTest#pairedRealModelFixturePreservesPcmAndReferenceQuality io.github.lrq3000.utterlane.test/androidx.test.runner.AndroidJUnitRunner
```

Machine-readable calculations, JVM counts, fixture/APK hashes and word comparisons:
`qa-artifacts/recording-summary.json`; reproducer:
`python qa-artifacts/summarize_recording_qa.py`. Model and speech fixture are staged
in the standard app's `getExternalFilesDir(null)/recording-qa`; its verified model
is in `files/models/parakeet-ultra-q8_0/model.gguf`. `.dhome` has neither `files/models`
nor `files/parakeet-v3`. Parent release/signing/distribution checks remain outstanding.

## Follow-up disposition: one archival-free control

### Question and outcome

The parent authorized a narrow follow-up: audit inputs beyond their hashes, allow
one direct replay control, and distinguish an application regression from an
unjustified deterministic-output assertion. **The same disputed output variation
occurs without archival**, using the original production recognition backend.
This establishes native-worker/runtime baseline variation for this fixture; the
initial difference is not evidence of archival input corruption or an observed
accuracy degradation. It does **not** prove that every archival workload is
non-regressing or identify the native numerical/state/scheduling cause.

No production, model-math, runtime-option, resampler, gain, or native-dependency
code was changed. No further timing pair, option sweep or performance measurement
was run. The original ON reference quality remains 0/70 errors, versus OFF's two
insertions; it is inappropriate to convert those different but non-degraded
outputs into an archival correctness failure solely because their bytes differ.

### Configuration and context audit

- Both original arms used the same checksum-verified catalog model and all 35
  serialized `RuntimeOptions` fields. The control parses the saved map and asserts
  exact map round-trip equality, then configures `WorkerRecognitionBackend` with
  that snapshot. Explicit ASR threads remain four.
- `RecognizerManager.createSession` uses the provided immutable snapshot;
  `RecognitionSessionPreparation` does not reread it after waiting for its mutex.
  Plain ASR passes the full window for this catalog model. No custom-model
  disjoint-input branch, dictionary, diarization or speaker finalization is used.
- M4A decoding/normalization happened once outside each original pair; both arms
  read the same native 16-kHz mono PCM WAV. There is no further resampling or
  `TranscriptionGain` on this fixture pipeline. Actual microphone HFP/auto-gain
  behavior is separate from this controlled fixture case, as stated above.
- Full control metadata, reconstructed by the same `AudioSegmenter`, is:

  | Window | Input start | Owned start | Owned end | Input samples | Final |
  |---|---:|---:|---:|---:|---|
  | 0 | 0 | 0 | 160000 | 176000 | false |
  | 1 | 144000 | 160000 | 277246 | 149246 | false |
  | 2 | 261246 | 277246 | 407186 | 145940 | true |

  All earlier per-window and whole-source hashes match. Previous evidence stored
  hashes rather than PCM bytes; the control decoded the same fixture, verified
  those hashes, then saved the exact windows. It did not substitute newly changed
  audio and assume equivalence. New pair evidence now explicitly includes owned
  start and final flags as well as input start/end/count/hash.
- Replaying the saved native tokens and timestamps through **production
  `WindowText.select`** reconstructs each earlier complete transcript exactly.
  The extra words occur approximately 3.5–3.6 s into the first window, before its
  10-s ownership boundary. They are present in raw returned tokens; callback
  ordering, EOF drain and overlap cropping did not manufacture the discrepancy.
- Fresh-pair logs show separate worker PIDs **15071** and **15896**, each completing
  the same normal one-second silence warmup. Worker LOAD chooses the same
  `CrispParakeetBackend`; JNI sets four CPU threads and `use_gpu=false`.
- Source audit: `parakeet_context` defaults to `decode_temperature=0`, beam size 1
  and no hotwords. Utterlane does not call the sampling setters. The decoder creates
  an RNG but uses it only when temperature is positive. The active mel path does
  not add random dither. Native graph reuse is opt-in via environment; default
  encoder graphs rebuild per call, and predictor state is initialized per decode.
  This does not establish numerical determinism of all native kernels/emulation.
- During the control the actual worker's `/proc/<pid>/environ` was readable and
  contained **no `CRISPASR_`, `GGML_`, `OMP_` or `OPENBLAS_` overrides**. Those values
  were not captured in the older pair, but no app source changes such variables.
  App CMake explicitly disables GPU backends, GGML OpenMP and CrispASR mel BLAS;
  CPU kernels and portable mel threads still run under LDPlayer native translation.

### The single direct control

`RecordingNativeReplayControlAndroidTest.replayExistingWindowsWithoutArchival`
uses the actual `WorkerRecognitionBackend` and its unchanged native model path.
It does not use `RecordingPipeline`, a recording producer, codec execution,
transcript correction or frozen ASR output for the replay.

One worker, **PID 24159**, remained constant through request order
**0 → 1 → 2 → 0 → 0**, following the normal silence warmup. Before and after every
request, the test asserted no archive worker process and zero queued/running/
producer work. One old cancelled-job cleanup descriptor existed; it was durably
fenced by the production `forceStopAll()` and did not execute. Thus “archival
absent” means no active archival execution, not deletion of recovery metadata.

| Request | Window | Result |
|---|---:|---|
| 1 | 0 | Extra `en fait`; token array matches original OFF exactly |
| 2 | 1 | Tokens and timestamps match both original arms |
| 3 | 2 | Tokens and timestamps match both arms; final words intact |
| 4 | 0 | No extra phrase; token array matches original ON exactly |
| 5 | 0 | No extra phrase; token array matches original ON exactly |

Window-0 timestamps also varied and do not match either old arm bit-for-bit.
Those raw values are retained. The control therefore proves variation without
archival, but does not distinguish numerical scheduling effects from residual
native state or emulation behavior. No attempt was made to alter model math.

Evidence in `qa-artifacts/`:

- `recording-native-replay-control.json`: full metadata/options and five actual
  native replies, including token starts/ends and archival absence checks.
- `recording-native-control-summary.json`: exact comparisons with both original
  token arrays; `first_window_varies_without_archival=true`.
- `recording-control-window-{0,1,2}.pcm`: raw little-endian PCM16 windows matching
  the hashes already recorded in the initial report.
- `recording-native-replay-control-fenced.txt`: **one executed replay control passed**.
- `recording-native-control-logcat.txt`: native-worker logs.
- `summarize_recording_control.py`: offline comparison reproducer.

Two setup failures occurred before any replay inference: the initial Kotlin test
inferred a non-void return and needed explicit `Unit`; the next precondition
incorrectly treated the inactive cleanup descriptor as active archival. Both were
corrected before the single successful native control. Their logs are retained
as `recording-native-replay-control.txt` and `recording-native-replay-control-run.txt`.

### Revised acceptance and verification

The paired method is now named
`pairedRealModelFixturePreservesPcmAndReferenceQuality`. Its hard assertions require:

1. Exactly **407186** source, accepted and processed samples per arm, the pinned
   full PCM hash, identical full window metadata, and matching per-window input
   hashes/sizes at the real worker boundary.
2. **Every one of the 70 supplied reference words in order**: no missing,
   substituted or reordered reference content.
3. **At most two inserted words per arm**, the archival-free control's observed
   ceiling; this is a fixed, fixture/model-specific ceiling, not an adaptive rule
   that blesses whatever the current run produces.
4. **Exact final 12 reference words** in both arms, plus the existing live codec
   progress and successful pipeline checks.

The raw OFF/ON transcripts and tokens remain saved. `exact_transcripts_equal` is
now diagnostic, with `native_control_insertion_ceiling=2`. An ON/OFF difference
within that empirically observed native envelope is not itself a regression;
source changes, reference-word loss or quality worse than the control still fail.
This is stronger than simply allowing arbitrary word differences or checking only
the last few words. It is not a claim that insertion errors are desirable.

`savedPairSatisfiesNativeControlQualityEnvelope` exercises the same new assertion
function against the already saved **real inference** pair. It also requires the
control to reproduce BOTH original token variants and verifies that three negative
examples are rejected: lost final words, changed PCM hash and three added words.
Final run: **1 passed** (`recording-followup-final-quality-gate.txt`). This is an
offline evidence-validation test, not a new inference or timing run. The full
paired capture was deliberately not remeasured after changing only its assertions.

The authorized follow-up therefore adds one passing native replay control and one
passing saved-evidence/negative-control test. It supersedes the old deterministic
gate's failure classification; it does not rewrite the four original failed logs.

### Follow-up final build and target state

The required root JVM/debug/test command succeeded again in **3 s**,
104 tasks / 9 executed / 95 up-to-date. Log:
`qa-artifacts/recording-followup-final-build.txt`. Production and JVM sources did
not change, so JVM tests were **UP-TO-DATE**, retaining the verified **1090 passed,
zero failures/errors/skips** report. No additional production JVM test was needed.

Final standard-identity artifacts:

- `app/build/outputs/apk/debug/app-debug.apk`: unchanged SHA-256
  `414425c84a714d9ca2f7be9dbe1b3b5c41baac7cf2703f973cf6a33641efcc9f`.
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`: SHA-256
  `2aa1d95889b4792cbc822073762d0ee2e086715304cec086fe532b734c1610da`.
  This final test APK is installed under `io.github.lrq3000.utterlane.test`.

The reference text is now staged alongside the fixture in the default app's
`recording-qa` directory, as required by the revised pair. All follow-up source,
builds and host artifacts stayed under the current root. `.dhome` was not modified
during this follow-up. The same emulator remains running for the accepted parent
handoff; no commits were made. Parent release checks follow this QA code freeze.
