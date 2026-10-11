# AAC EOF fix — delegated LDPlayer runtime evidence

Status: **AAC regression fixed; final native suite: 9 passed, 0 failed, 1 skipped.**
App code is frozen for parent QA. Both APK targets build and the latest APKs are
installed. Full JVM verification is **not all green**: latest standard run has
647 passed / 648 total, one intermittent deletion assertion, zero skipped.

## Target and handoff

- Worktree: `C:\git\TranSlander\.worktrees\progressive-archival`.
- Explicitly delegated serial: `emulator-5554`, Android API 28.
- LDPlayer: `C:\LDPlayer\LDPlayer9`, index 0,
  `agent-progressive-archival-a418f9`.
- Parent owner: `progressive-archival-20261010-a418f9`; ownership generation
  `progressive-archival-20261010-a418f9-1791683540023582900`.
- Only `io.github.lrq3000.utterlane` and its `.test` package were installed/tested.
- The other agent's debug-provider process fix, `:archive_test_provider`, is retained.
- Parent accepted immediate handoff back. The instance remains running, with the
  latest APKs installed and no instrumentation left running. No emulator/server
  stop, restart, removal or other-target operation was performed; no commits.

## Reproduction and root cause

The named OMX AAC case reproduced the parent's failure before changes. Bounded
scalar diagnostics showed:

```text
encoder=OMX.google.aac.encoder decoder=OMX.google.aac.decoder
rate=16000 channels=1 input=16384 packets=17 decoded=17408
delay=2048 correlation=0.9946536098233204
delayKey=null paddingKey=null pts=0..1024000
encoderEosBytes=768 decoderEosBytes=2048
```

All 17 access units decoded to 1024 frames each. Both nonempty EOS outputs were
consumed. However, empty-input EOS produced only one extra AAC access unit while
the actual round-trip priming measured **two** units (2048 frames). Thus only
`17408 - 2048 = 15360` of the 16384 source frames were covered: the final **1024**
frames were missing. A delay key or first PTS of zero could not establish this.

The defect was insufficient codec-input tail padding, not a reason to lower the
tail assertion or assume zero delay. The original interior probe windows could
establish alignment but did not independently prove source-edge signal survival.

## Production changes

- `AacPrimingCalibration` adds 8192 bounded synthetic zero frames after the
  16384-frame reference probe, covering the estimator's existing maximum-delay
  search range. Packet/byte/decoded-frame caps and the deadline remain enforced.
- `PcmPrimingEstimator` retains its unique interior alignment checks and now also
  requires full source coverage and correlation >= 0.65 in both the first and last
  1024 source frames. Extending decoded output with zeros cannot satisfy this.
- `AacEndPadding.frames(sourceFrames, measuredDelay)` computes codec-only padding
  from the measured delay and rounds the combined input to an AAC-LC frame boundary.
  It is bounded by `measuredDelay + 1023` frames, independent of recording length.
- `ProgressiveAudioEncoder` feeds those zeros only after the real source is closed
  and drained, then queues EOS and consumes all output. `paddingInputFrames` has a
  separate counter and codec timestamp; it never advances capture, ASR, source
  duration or reclaim authority. Checkpoints remain capped by real source frames.
- Exact final source-coverage assertions remain. Existing container edit-list and
  owned-decoder trimming remove priming and surplus output. Neither source counts
  nor decoder output are fabricated to make the checks pass.
- Content-free `ProgressiveEncoding` diagnostics record codec names, tuple, packet
  and frame counts, measured delay/correlation, optional keys and EOS payload sizes.
  `codec.properties` also records `paddingInputFrames` after successful draining.

Observed after-fix calibration on the same target:

| Tuple | Probe source / codec padding | Packets / decoded frames | Measured delay | Correlation |
|---|---|---|---:|---:|
| 16 kHz mono, 96 kbps AAC-LC | 16384 / 8192 | 25 / 25600 | 2048 | 0.9946536098233204 |
| 44.1 kHz stereo, 128 kbps AAC-LC | 16384 / 8192 | 25 / 25600 | 2048 | 0.98104865226192 |

Both used `OMX.google.aac.encoder` / `OMX.google.aac.decoder`; delay and padding keys
were absent. Successful real streams included:

| Real source frames | Codec-only input padding | Encoder output frames | Verified result |
|---:|---:|---:|---|
| 64317 | 2243 | 67584 | Exact decoded count and final 317-frame distinct tone |
| 16384 | 2048 | 19456 | Exact count and first/last source-edge correlation |
| 317 | 2755 | 4096 | Exact short-source count and both edges |
| 88277 (44.1 kHz stereo) | 2859 | 92160 | Correct track tuple and normalized source count |
| 32000 | 2816 | 35840 | Interrupted conversion plan completes in real worker |
| 96000 | 2304 | 99328 | Independent import conversion; original bytes unchanged |

## Native results on installed latest APKs

Focused default AAC, named OMX, stereo and new EOF-edge case: **4 passed**, 2.217s.
Final complete suite (original nine cases plus one new regression): **9 passed,
0 failed, 1 skipped**, 6.802s. Raw instrumentation reports nine status-code `0`
results and one `-4` assumption skip; its summary is `OK (10 tests)`.

| Case | Final result |
|---|---|
| `interruptedConversionDescriptorReplansToImportAndCompletesInWorker` | PASS |
| `uncompressedImportIsIndependentlyTranscodedAndOriginalSurvives` | PASS |
| `amrNbBundledBuffersDeclareActualTrackFormatAndTail` | PASS — `OMX.google.amrnb.encoder`, 8 kHz mono |
| `codecProducesDurablePacketsBeforeStopAndPreservesTail` | PASS — real packet checkpoint and PCM reclamation before Stop; final tone |
| `amrWbBundledBuffersDeclareActualTrackFormatAndTail` | PASS — `OMX.google.amrwb.encoder`, 16 kHz mono |
| `aacAlignedAndShortSourcesPreserveBothEdgesAfterEos` | PASS — new 16384/317-frame regression |
| `aacStereoDeclaresActualTrackFormat` | PASS — 44.1 kHz stereo |
| `c2SoftwareAacWorksWithoutAnEncoderDelayKey` | SKIP — `c2.android.aac.encoder` is not installed on API 28 target |
| `omxSoftwareAacUsesMeasuredPrimingAndPreservesTail` | PASS |
| `isolatedWorkerEncodesBeforeCloseAndStopDoesNotStopPcmWriter` | PASS — real worker stop, 96002 mixed decoded frames, prefix-end signal correlation and exact post-stop PCM tail |

The worker-stop case now uses a nonperiodic reference and additionally verifies the
reclaimed prefix's last 200 samples by correlation and the exact final `123, -321`
PCM samples written after worker termination. Existing count/reclamation assertions
remain. Tests delete only their own fixture directories.

Final native command, after explicitly reinstalling both worktree APKs:

```powershell
adb -s emulator-5554 shell am instrument -w -r -e class io.github.lrq3000.utterlane.ProgressiveCompressionAndroidTest io.github.lrq3000.utterlane.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 logcat -d -v threadtime -s ProgressiveEncoding:I AndroidRuntime:E '*:S'
```

No global log clearing was used. The final scoped log showed successful codec
diagnostics and no AndroidRuntime crash; final targeted `get-state` returned `device`.

## JVM/build evidence and remaining host verification issue

Four new JVM cases cover missing final-AU coverage despite correlated interior,
fabricated silence at either source edge, bounded measured-delay padding across
short/long/partial sources, and invalid delay rejection. The two missing-edge
regressions failed before the estimator change. All nine estimator/padding tests
passed in the focused run and in the final full run.

The full JVM suite has 648 cases. The final ordinary-temp run (using `--continue`
so both APK targets could complete) had **647 passed, 1 failed, 0 skipped**, 52.895s
test time; the combined Gradle command returned failure in 58s. Both APK targets
completed up-to-date and were then explicitly reinstalled for the final native run.

Remaining failure: `RollingHistoryTest.badOptionalCodecMetadataCannotStrandTheCaptureOwnerAtFinish`,
line 19, immediate `directory.exists()` after deletion. The earlier metadata and
source-count assertions passed. This test does not invoke AAC encoding/calibration.

Earlier full/focused runs failed at different existing filesystem boundaries:
`ArchivalWorkingSetTest` reported Windows **file used by another process** while
opening a `.target.tmp`; history/import/pin tests intermittently failed immediate
deletion assertions. A controlled external init-script run changing only test
`java.io.tmpdir` to `app/build/qa-aac-test-tmp` did **not** resolve this (nine failures,
including `AccessDeniedException` on metadata replacement). The final run restored
the normal temporary directory. The responsible process and individual deletion
failures' causes are not established; this is not an all-green JVM claim.

`ImportedAudioCopyTest` gained only a diagnostic assertion message reporting choice,
remaining files and lease state. Assertions were not weakened. No production deletion
behavior, Gradle config, native cache or system scanner settings were changed.
The experimental init script is outside the repo at
`C:\Users\33632\AppData\Local\Temp\opencode\aac-qa-test-tmp.init.gradle`.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:assembleDebug "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain --continue
```

## Remaining parent cases

- Resolve the Windows JVM filesystem-verification instability before all-green JVM acceptance.
- C2/native codecs on a target where installed; this API 28 run does not establish them.
- Actual microphone + ASR contention, unavailable/slow model, route loss and long recordings.
- API 34/35+ foreground types, denial/timeout and background ownership.
- Real document-picker rotation/process death/slow provider export and pending-fallback Retry/Force Stop UI flows.
- Other players' gapless behavior and broader device/format compatibility.

The parent can immediately resume use of the running delegated target.
