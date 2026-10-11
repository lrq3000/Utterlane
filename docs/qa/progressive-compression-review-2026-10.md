# Progressive compression review fixes / API handoff

Workspace: `.worktrees/progressive-archival`, branch `feat/progressive-archival`.
Status: **AAC EOF regression fixed and app code frozen for parent QA**. The
[delegated API 28 runtime report](progressive-compression-native-2026-10-11.md)
records 9 native passes / 1 unavailable-C2 skip and the remaining Windows JVM
filesystem-verification failure. No commits or native-cache changes were made.
The delegated emulator remains running for the parent's accepted handoff.

## Verification

- Latest AAC verification: **647/648 JVM passed, one intermittent deletion assertion,
  zero skipped**, 52.895s. Both APK targets completed; the full command returned failure
  due to that assertion. See the runtime report for the exact failure and host IO evidence.
- Last all-green pre-AAC run: **644 JVM tests passed, zero failures/skips**, 31.485s;
  both APK builds passed in 1m 2s, 101 tasks, 15 executed / 86 up-to-date.
- Latest native suite on freshly installed APKs: **9 passed, 0 failed, 1 skipped**
  (C2 unavailable), 6.802s. This includes the original nine cases and a new EOF regression.
- `git diff --check` passed.
- The existing generated `$stable` mismatch recurred in eight RecordingPipeline
  tests. XML and `javap` confirmed it before the authorized targeted
  `:app:compileDebugKotlin -Pkotlin.incremental=false` rebuild. The normal full
  command then passed. No blanket clean or native-source preparation was used.
- During re-review, two existing immediate-directory-deletion assertions failed
  in one full run; one recurred after the compiler rebuild. Both passed unchanged
  in a focused run, and the final full run passed. Their cause was not established.
  `ProcessingObligationTest` now reports remaining files/lease state on failure;
  its assertion was not relaxed and app deletion behavior was not changed.
- This quality pass also encountered temporary `kotlin-backups...` failures;
  the same commands succeeded when rerun with `--stacktrace`, without cache cleanup.
  One existing Android export test was migrated to the selection-based API. The
  final full run passed; no device/emulator was operated on in this pass.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*RepresentationReplacementTest" --tests "*OriginalMultipartProcessingTest" --tests "*AmrFramesTest" --tests "*PcmPrimingEstimatorTest" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests "*ConversionPlanningRecoveryTest" --tests "*RepresentationReplacementTest" --tests "*OriginalMultipartProcessingTest" --tests "*archive*Test" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests "*AudioExportRepresentationTest" --tests "*PcmPreservationTest" --tests "*EncodingFailureRecoveryTest" --tests "*CaptureFormatBudgetTest" --tests "*ManagedHistoryTest" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests "*EncodingFailureRecoveryTest" --tests "*RollingRecoveryTest" --tests "*RollingHistoryTest" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests "*EncodingFailureRecoveryTest" --tests "*ArchivalJobStoreTest" --tests "*ArchivalCoordinatorLifecycleTest" --tests "*ManagedHistoryTest" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests "*archive*Test" --tests "*AudioQualityPreferencesTest" --tests "*CaptureFormatBudgetTest" :app:assembleDebugAndroidTest :app:assembleDebug "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:assembleDebug "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
```

RED runs reproduced both external-WAV replacement failures, loss of the original
multipart source, all four bundled-AMR parser cases, and the priming estimator's
missing delay/rejection behavior. A container regression also exposed that shortened
`mdhd`/`stts` durations hid actual encoder padding from Android's gapless metadata.

## P1 — representation replacement

An audio **owner ID** remains the recording/deletion/pinning identity. An audio
**representation ID** identifies one byte/format/part layout owned by that record.

- `AudioRepresentation.ORIGINAL` is the legacy/default representation.
- `HistoryEntry.representation` qualifies **all** of that immutable entry's
  `externalParts` bindings. Never combine another representation's bindings with
  the current MIME, part count or source clock.
- `ArchivalRequest.representation` is the output representation. For COPY_AUDIO,
  it is also `sourceRepresentation`. For ENCODE_AUDIO, `sourceRepresentation` is
  the layout being replaced; `representation` is a new UUID persisted in
  `rolling.properties` before enqueue.
- `PublishedArtifact.representation` qualifies each published URI. Deduplication
  slots include representation ID. The original representation retains the old
  slot-key encoding for compatibility.
- `ArchiveCatalog` persists `owners/<owner-key>/active-representation.properties`.
  Encoding publication commits its URI before switching that pointer. Restart
  repairs an interrupted commit from the durable execution record. Pre-schema
  encoded publications receive stable `encoded-<job-id>` identities; old WAV
  publications remain `original`.
- History projects only the active representation's copy bindings. Encoding
  publication switches layout/ID and clears the old binding projection in the
  same history transaction. Stale requests/callbacks cannot publish into the new
  representation. Old PUBLISHED slots cannot acknowledge a new-format copy.

Old external files are **not** deleted merely because the active layout changed.
`retireSupersededRepresentations(owner, keep)` is requested only after all replacement
parts reach the selected destination, original processing obligations settle, and
`pcm-retired.properties` prevents new readers selecting originals being cleaned.
Existing leases protect already-selected readers. Private-only replacement needs
no external copy.

Retirement uses bounded journal maintenance (`replacements/*.replace`). Each old
publication receives an exact-URI cleanup intent before its retired marker. It
stays on disk until physical cleanup is acknowledged. Restart preserves pending
retirement; an older maintenance cursor cannot remove a newer replacement intent.
Replacement never tombstones the owner or changes its pin, chronology, transcript
links or ordinary retention.

`RepresentationReplacementTest` covers single/multipart externally published WAV,
Keep/compression, no old-URI/M4A alias, distinct deduplication, stale callbacks,
leases, restart before copying and during retirement, exact old-URI cleanup, new
private-output cleanup, final restart and pin preservation.

## P2 — leased original source descriptors

`OriginalAudioSource` is independent of `RollingPcmJournal`: a conversion's PCM
spool is **derivative**, not the authoritative original ASR input.

- `original-source.properties` stores representation, original source/native-PCM
  identity, MIME, sample count, part count, part interval and duration.
- `original-source/<part>.properties` holds the exact managed URI when applicable.
  Otherwise parts resolve to private original paths. Bindings arriving after
  planning are reconciled before private unlink. No file-long Binder array is used.
- Planning takes an audio lease and serializes same-owner plans. It commits the
  original descriptor before the encoder job/configuration becomes actionable.
- `AudioLease.originalSource` resolves the descriptor for that lease, including a
  descriptor created while model preparation was waiting; it does not freeze an
  early null before conversion planning.
- `AudioLease.originalPcmReader(offset)` takes a derived lease and streams exact
  native PCM across original part boundaries. Closing it releases that lease.
  `OriginalAudioSource.asset(part)` supports independent decoding of original
  imported/non-native containers, including URI-backed ones.
- `processingAudio(entry)` offers a format-correct original projection. The actual
  transcription consumer prefers the leased original descriptor before retained
  or mixed converted audio. `HistoryEntry.sourceOverride` is that read-view only;
  publication always uses the repository's current representation, never this view.
- The worker independently reads that same descriptor. It can therefore convert
  externally archived WAV even after private `audio-N.wav` has been reclaimed.
- Older conversions recover descriptors from persisted `importSource` /
  `importPcmParts` while original ownership remains. A live rolling capture with
  reclaimed PCM is never mistaken for a complete original.
- The retirement fence prevents original selection during/after cleanup. Failed
  or outstanding original processing keeps its originals available.

`OriginalMultipartProcessingTest` reads exact signed samples across a legacy WAV
part boundary after lossy conversion, using a lease acquired before conversion.
It covers failed ASR, restart/upgrading a pre-descriptor conversion, and original
cleanup only after the processing attempt completes.

### Re-review — descriptor publication race and interrupted configuration

Both gaps were reproduced before their fixes: the latch-controlled publication
race failed with `FileNotFoundException`, and restart at the old first config
replacement selected the live-worker branch instead of the import producer.

**Publication ordering:** `RecordingHistory` retains the planning lease while
the original descriptor is installed outside its publication lock. It then takes
that lock, rechecks the current owner/source representation, and reconciles the
descriptor with the **current** same-representation URI bindings. COPY_AUDIO
publication uses the same lock. Private unlink repeats reconciliation under that
lock, covering an installer failure after the manifest committed but before the
planner's explicit transaction. Only private metadata is touched under the lock;
the regression's provider adapter asserts that payload reads occur outside it.
Late URI binding also syncs the containing directory before private PCM can unlink.

`RecordingHistory(..., installOriginal = ...)` is the metadata-installer injection
boundary used by the latch/fault tests; normal callers use `OriginalAudioSource.capture`.
Clock remains the final constructor parameter for existing trailing-lambda callers.

**Atomic conversion planning:** `RollingPcmJournal.create(..., original = source)`
publishes quality, part interval, import source, source representation, native part
count where applicable, and output representation in its **first atomic write**.
`EncodingInput.prepareConversion(original, requested)` also repairs pre-fix bare
configs from the durable original descriptor before enqueue. It keeps existing
quality/representation snapshots, PCM/journal bytes and cancellation state; it does
not restart a stopped encoder or replace an authoritative prefix.

`EncodingInput.dispatch` is the actual worker's shared routing boundary. It
validates conversion fields against the original descriptor. A descriptor claiming
an original/conversion but missing its import fields fails explicitly instead of
falling through to the indefinitely open live spool. A genuine live recording keeps
the existing live path. No provider IO is performed while selecting the route.

`ConversionPlanningRecoveryTest` contains five JVM cases:
1. COPY publication between snapshot and descriptor install; all planning leases
   release, private WAV is removed, and the original reader returns the exact PCM
   through the published URI **without restarting**.
2. The same interleaving with failure immediately after descriptor installation;
   the unlink boundary still repairs the binding before removing private staging.
3. Restart at the exact old bare-config write boundary, replan, import dispatch,
   real original-PCM reading and finite producer EOF (the live branch is forbidden).
4. An unrepaired conversion is rejected before either encoder callback is invoked.
5. A fresh config is complete, and repair preserves prior quality, output identity,
   cancellation and existing PCM/journal bytes.

App code is frozen after these fixes and the passing full build. The additional
native regression below is compiled for the parent's actual worker/codec check.

## P1 — AAC priming without a key or negative PTS

The old gate is removed: it incorrectly rejected standard C2 software AAC.
`AacPrimingCalibration.measure(encoderName, quality, checkActive)` measures the exact
selected encoder/configuration with a bounded real encode/decode round trip:

1. Generate 16,384 frames of nonperiodic, band-limited synthetic PCM, followed by
   8192 codec-only zero frames so the full measured-delay range can drain before EOS.
2. Encode/drain it, capped at 64 packets and 512 KiB compressed payload.
3. Decode raw AAC with delay/padding explicitly zeroed, capped at 32,768 decoded
   frames. Validate rate/channels and one AAC-LC access unit per packet.
4. `PcmPrimingEstimator` finds a unique offset in `0..8192`, correlation at least
   0.65, and agreement between two separated windows. Silence, unrelated output,
   ambiguous timing or drift fail closed. Zero needs signal evidence too. Full
   source coverage and first/last 1024-frame signal correlation are also required.
5. Start a fresh actual encoder with matching CSD. Synthetic samples never enter
   the recording or its journal.

There is a 20-second cooperative deadline; stuck native calls remain subject to
the isolated-worker hard stop. Preparation runs at archival priority independently
of capture/model loading/ASR. No brand whitelist or assumed fixed delay decides
support. `ProgressiveAudioEncoder.encode(..., encoderName = ...)` allows native QA
of another installed compatible codec; the normal app uses capability selection.

AU order plus measured delay defines source time. Provider PTS is diagnostic only.
`codec.properties` records `delayMethod=codec-round-trip`, `delay`,
`calibrationCorrelation`, `encoder`, `decoder`, and `platformFirstPtsUs`. Logcat tag
**ProgressiveEncoding** reports the content-free calibration result.
The AAC EOF fix additionally records codec-only `paddingInputFrames`; source
duration and checkpoint authority exclude this padding. See the runtime report.

The container keeps physical frame durations in `mdhd`/`stts` and source interval
in `elst`, allowing Android to derive actual `encoder-padding`. Owned decoding
disables framework skip-cut, applies edit-list delay exactly once, then clamps to
the retained source count. It does not rely on negative decoder PTS. Converted
imports keep authoritative duration instead of a padded player estimate. FFprobe
still reports the host fixture's exact **64,317** source samples (4.019813 seconds).

Sources inspected:
- [C2SoftAacEnc.cpp](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/codec2/components/aac/C2SoftAacEnc.cpp): encoder interface and first-input-based output timestamps.
- [C2SoftAacDec.cpp](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/codec2/components/aac/C2SoftAacDec.cpp): decoder delay compensation and EOS draining.
- [MPEG4Extractor.cpp](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/module/extractors/mp4/MPEG4Extractor.cpp): edit-list delay/padding calculation and composition timestamps.

## P1 — bundled AMR frames

`AmrFrames.split` validates the entire octet-aligned buffer and emits individual
NB/WB access units from their TOCs/frame lengths. Each owns 20 ms: 160 frames at
8 kHz or 320 at 16 kHz. The journal/sample table receive one entry per access unit,
including SID/NO_DATA and WB SPEECH_LOST. Reserved/truncated frames fail before any
callback advances coverage. A native buffer is not assumed to be one frame.

`AmrFramesTest` independently covers a 20-frame/400-ms NB buffer, a 10-frame/200-ms
WB buffer, variable frame types, source offsets and invalid/truncated data.

## Parent native QA

### Quality review — writer ownership, exports, failure fallback and buffers

**Cross-process preservation:** `PcmPreservation` replaces the combined mutable
record with two authorities. Only the parent installs the immutable
`preservation-stop.properties` fence. Only the operation's reclaimer writes
`reclamation.properties`: the parent maintenance loop for live microphone capture,
or the import decoder coroutine in `:archive` for derivative PCM. Neither writer
read/modifies/writes the other record, and their atomic-write temporary paths are
distinct. A process-local monitor is not used as cross-process synchronization.
The old `preservation.properties` is read-only compatibility state: its stopped bit
still fences encoding, and its cursor remains a monotonic lower bound. Each reclaim
pass rechecks cancellation; an already committed in-flight checkpoint is not rolled
back by cancellation and cannot erase the stop fence.

`PcmPreservationTest` uses independent writers without a shared JVM monitor and
latches at the actual file-write boundary. Both stale-write orderings failed first
(lost stop, regressed cursor), then passed with split authority. A third case checks
legacy state, repeated stop permanence and rejection of backwards cursors.

**Export/share identity:** `RecordingHistory.selectForExport(id)` atomically obtains
one immutable `HistoryEntry` representation and its audio lease. `AudioExportSelection`
supplies name, MIME, part count and payload streams from that same entry. It never
rereads the current history row to resolve bytes. `AudioExportSession` holds the
pending picker selection in the retained ViewModel and transfers ownership to the
export task on result. Cancel, explicit owner release and model clearing release
pending selections. Activity recreation retains the handle; process recreation
does not invent a replacement lease. A returned URI without the original selection
reports an error before writing any bytes. The saved picker-pending UI marker also
prevents starting another picker before an old restored result is consumed.

`AudioExportContract` receives MIME/name in its invocation input rather than using
the recomposed current audio MIME. `DialogAudioActions.export` accepts the selected
handle, not an audio ID. `HistoryExports.createSnapshot` returns MIME and
representation together with the immutable copied files; Share uses those returned
values. The legacy file-list-only helper delegates to it for compatibility.
Transfers run in application-owned tasks with a separate `audioExporting` status;
they do not hold `saving`/Home busy throughout provider transfer. Selected-source
leases close in non-cancellable cleanup, independently of dialog navigation.

`AudioExportRepresentationTest` covers WAV selection followed by M4A publication
before picker result, metadata/payload agreement in share snapshots, cancellation,
retained-session recreation and a fresh process with no selection. The first two
failed against current-row byte resolution. The compiled
`AudioExportSnapshotAndroidTest` additionally checks real Intent MIME/title and
ContentResolver output after representation publication.

**Ordinary codec failure:** `EncodingFailureRecovery.afterWorkerStopped` is called
only after the coordinator has quiesced the worker. It checks generation, source
representation, owner and cancellation epoch; fences the stopped codec; cancels
that invalid encoding attempt only after its fallback is scheduled; and asks history
to schedule retained audio using the existing destination plan. Until then, the
job remains retryable exclusively in its durable `PENDING_FALLBACK` phase. A finished
live capture keeps its finalized writer-owned PCM/mixed layout, commits its capture
closure, and queues copying. A still-active capture continues preserving PCM and
its normal finish queues the plan. Imports
copy their authoritative original, never the derivative decoding spool. Explicit
stop-all epochs and deleted/superseded owners cannot acquire fallback jobs from a
late codec error. Already committed/ready output is not demoted into fallback.

`EncodingFailureRecoveryTest` reproduces EOS/container failure after capture close,
including a reclaimed encoded prefix plus raw tail, verifies folder publication
without restarting, verifies that Retry cannot resurrect the stopped encoder,
and checks that explicit stop-all produces no fallback jobs.

**Recorder preparation:** `configureFormat(requested, runtimeOptions)` receives the
same immutable options already captured by MicrophoneSession. `CaptureConfiguration`
uses `CaptureBufferPolicy` and the Android minimum to construct the prepared recorder;
start and route/wake reopen reuse that exact configuration. Two new factory-based
`CaptureFormatBudgetTest` cases verify the initial/reopened byte capacities, snapshot
stability and a device minimum larger than the requested time budget. Both caught
the previous hardcoded one-second allocation.

The pending-fallback fixes passed the 644-test/APK build. The subsequent AAC freeze
and current verification status are recorded at the top of this handoff.
For parent export QA, run:

```powershell
adb -s <QA_SERIAL> shell am instrument -w -e class io.github.lrq3000.utterlane.AudioExportSnapshotAndroidTest io.github.lrq3000.utterlane.test/androidx.test.runner.AndroidJUnitRunner
```

Also rotate/background the real document picker while conversion finishes, cancel
it, and test a process-death result. Verify no new-format bytes are written under
the old picker metadata, and that new recording/navigation stays usable during a
slow external transfer. These Android runtime checks remain parent-owned.

### EOF re-review — preserve the finalized source count through fallback

The combined regression reproduced a 19,200-frame recording being reduced to its
16,000-frame periodic watermark when fallback reopened `capture.properties` after
EOF publication failed. A short 3,200-frame recording could similarly become zero.
`RecordingHistory.scheduleEncodingFallback` now keeps the `rollingAudio` layout
saved from the finalized capture writer instead of replacing it with a reopened
periodic-watermark layout.

`RollingPcmJournal.commitFinalizedCapture(finalizedFrames)` repairs closure from
that authoritative writer-owned source-frame count after capture and codec writers
are quiescent. It rejects a checkpoint or reclaimed prefix beyond that count. When
repair is needed and raw tail remains, it verifies the last PCM part contains the
required frames, repairs its WAV header, and durably publishes the exact count and
closed flag. It never derives the final duration from encoded coverage or rewrites
codec bytes, codec checkpoints, reclamation state or the retained encoded prefix.

`RecordingHistory.commitFallbackClosure` guards fallback scheduling, archive queueing,
COPY publication and private-part unlink for externally bound fallback audio. If
closure cannot commit, publication/unlink stay blocked and private PCM remains.
Already durable closure at the exact final count is idempotent, including after
private staging has been removed.

Four additions to `EncodingFailureRecoveryTest` cover:

- `failedEofWatermarkKeepsAll19200FramesThroughFallbackPublicationAndRestart`.
- `failedFirstEofWatermarkKeepsShortCaptureThroughFallbackPublicationAndRestart`.
- `failedEofRepairPreservesReclaimedEncodedPrefixAndEveryRawTailFrame` — 480,000
  reclaimed prefix frames plus 19,200 raw tail frames; journal bytes stay identical.
- `unrepairedClosureBlocksFallbackPublicationAndPrivateUnlink` — a persistent
  metadata-write obstruction also blocks an already queued COPY publication until
  closure repair succeeds.

The first three cases append in 3,200-frame blocks and force EOF write failure with
a directory at `capture.properties.tmp`. They exercise fallback, URI-backed folder
publication, lease release/private WAV removal and repository restart, then check
full source counts and exact raw tail samples. All three failed before the fix;
all four pass in the final suite. The mixed fixture verifies journal preservation
and raw PCM fidelity, not native AAC decoding. Parent Android runtime acceptance
remains pending; LDPlayer was reported starting during the subsequent lifecycle fix.

### Pending-fallback re-review — ordinary Retry after a persistent IO failure

The previous EOF gate protected source bytes but cancelled ENCODE before attempting
repair. If repair failed, no COPY existed and ordinary Retry had no paused work to
resume. The first four lifecycle regressions reproduced this loss of retryable work
and a second gap where `queueArchive` swallowed a COPY-scheduling error.

`ArchivalJob.phase` now persists `PRIMARY` or `PENDING_FALLBACK` in the existing
execution descriptor; older descriptors default to `PRIMARY`. This is separate
from the existing QUEUED/RUNNING/PAUSED scheduling state and uses the same bounded
32-entry cache, disk indexes, generation and original cancellation epoch.

- `ArchivalJobStore.pendingFallback(id, generation, message)` durably changes the
  stopped encoder job to PAUSED/PENDING_FALLBACK **before** fallible PCM fencing,
  closure repair or COPY scheduling. Repeated IO failure leaves it visible to Retry.
- Ordinary coordinator Retry still calls `retryPaused()` and `beginCleanupPass()`.
  The claimed descriptor retains `PENDING_FALLBACK`. The drain routes it directly
  to `EncodingFailureRecovery.afterWorkerStopped` in the parent before any codec
  preparation, dispatch or media-processing setup. No new worker target is created;
  old-generation cleanup intents remain independent. The worker client also rejects
  non-PRIMARY descriptors, and fallback jobs cannot become encoded READY output.
- `scheduleEncodingFallback` preserves the finalized writer count and propagates
  COPY-scheduling failures to this retry boundary. Successfully queued parts deduplicate
  on another attempt. Only after closure and all required COPY scheduling succeed
  does `completeFallback(id, generation)` make the old encoding attempt terminal.
- If capture is still active, fallback stays pending while PCM continues. Normal
  capture finish acknowledges the pending job only after closure and COPY scheduling;
  it resolves the existing encoding dedup slot rather than scanning all owner jobs.
- The observer that detects capture ending without EOF also persists pending fallback
  before stopping the worker, replacing its earlier premature cancellation.
- Force Stop keeps its existing epoch barrier. A pending, queued or already-claimed
  old fallback cannot enqueue work under the new epoch. Owner deletion and source
  representation changes also invalidate pending work; no retry restarts the codec.

Six additions to `EncodingFailureRecoveryTest` (13 tests total in that suite):

1. `normalRetryRepairsPendingFallbackWithoutRestartOrManualCopy` — blocked EOF,
   repeated blocked Retry, remove the obstruction, ordinary Retry primitives plus
   the same production recovery helper, then COPY publication/private removal with
   all 19,200 source frames. No Keep, restart, replacement plan or manual COPY enqueue.
2. `pendingFallbackSurvivesLedgerRestartAndNormalRetry` — same path with a reopened
   ledger and rebuilt history; the phase survives and is claimed before recovery.
3. `forceStopCancelsPendingFallbackAndRejectsClaimedLateRetry`.
4. `forceStopCancelsQueuedFallbackBeforeRetryCanRun`.
5. `copyQueueWriteFailureKeepsFallbackRetryableUntilAllPartsAreScheduled` — fail the
   queue index after a COPY descriptor reaches disk; Retry completes both parts with
   no duplicate publication and clears pending fallback only after scheduling succeeds.
6. `activeCaptureFailureStaysPendingUntilNormalFinishCommitsItsCopies` — encoding
   fails during capture, PCM continues, and normal Stop schedules/acknowledges fallback.

The earlier manual-COPY test remains a separate guard for a transfer queued by an
older build; it is not the Retry-liveness evidence. These are JVM production-helper
and filesystem fault-injection checks, not executed Android UI/codec tests. Parent
native QA should additionally exercise the actual Retry action after restoring IO
and Force Stop before/while fallback retry is pending.

### Codec and recovery cases

The latest APKs are in the usual worktree output paths and installed on the delegated
API 28 target. `ProgressiveCompressionAndroidTest` now has ten independent cases;
[actual results](progressive-compression-native-2026-10-11.md) are nine passes and
one unavailable-C2 skip:

- `codecProducesDurablePacketsBeforeStopAndPreservesTail` — mandatory default codec,
  before-Stop checkpoint/reclamation and final-tone correlation.
- `c2SoftwareAacWorksWithoutAnEncoderDelayKey`.
- `omxSoftwareAacUsesMeasuredPrimingAndPreservesTail`.
- `aacStereoDeclaresActualTrackFormat`.
- `aacAlignedAndShortSourcesPreserveBothEdgesAfterEos` — new aligned/short-input EOS regression.
- `amrNbBundledBuffersDeclareActualTrackFormatAndTail`.
- `amrWbBundledBuffersDeclareActualTrackFormatAndTail`.
- `isolatedWorkerEncodesBeforeCloseAndStopDoesNotStopPcmWriter`.
- `uncompressedImportIsIndependentlyTranscodedAndOriginalSurvives`.
- `interruptedConversionDescriptorReplansToImportAndCompletesInWorker` — recreates
  repositories at the bare-config boundary, replans, executes the real isolated
  worker under a deadline, publishes the result and verifies the decoded sample count.

Named-codec cases assume only when the codec is not installed; optional tuples
use Settings' capability filter. The default AAC case never skips for calibration
failure. Run the worker-stop case only on the designated isolated installation.

```powershell
adb -s <QA_SERIAL> shell am instrument -w -e class io.github.lrq3000.utterlane.ProgressiveCompressionAndroidTest io.github.lrq3000.utterlane.test/androidx.test.runner.AndroidJUnitRunner
adb -s <QA_SERIAL> shell am instrument -w -e class 'io.github.lrq3000.utterlane.ProgressiveCompressionAndroidTest#c2SoftwareAacWorksWithoutAnEncoderDelayKey' io.github.lrq3000.utterlane.test/androidx.test.runner.AndroidJUnitRunner
adb -s <QA_SERIAL> shell am instrument -w -e class 'io.github.lrq3000.utterlane.ProgressiveCompressionAndroidTest#amrNbBundledBuffersDeclareActualTrackFormatAndTail' io.github.lrq3000.utterlane.test/androidx.test.runner.AndroidJUnitRunner
adb -s <QA_SERIAL> shell am instrument -w -e class 'io.github.lrq3000.utterlane.ProgressiveCompressionAndroidTest#amrWbBundledBuffersDeclareActualTrackFormatAndTail' io.github.lrq3000.utterlane.test/androidx.test.runner.AndroidJUnitRunner
adb -s <QA_SERIAL> shell am instrument -w -e class 'io.github.lrq3000.utterlane.ProgressiveCompressionAndroidTest#interruptedConversionDescriptorReplansToImportAndCompletesInWorker' io.github.lrq3000.utterlane.test/androidx.test.runner.AndroidJUnitRunner
```

For AAC failures, retain the nested exception, codec/tuple, calibration log and
`codec.properties` from a production history run if written (test fixtures are
cleaned after each case). Distinguish calibration, real-stream drain, container
metadata, and final-tone failures. For replacement QA, inspect owner ID, active
representation, publication representation/URI and original descriptors before Keep,
after private encoding, after folder copy, and after cleanup/restart. Old URI
retirement must neither remove the new URI nor acknowledge the old copy as new.
