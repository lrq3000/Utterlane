# Progressive compression / rolling capture — 2026-10-10

Status: **implementation integrated; delegated API 28 codec suite passed with one
unavailable-C2 skip; broader parent acceptance pending**.
The [review-fix/API handoff](progressive-compression-review-2026-10.md) records the
subsequent representation, original-source, AAC calibration, bundled-AMR, quality
and EOF fallback fixes. The [native runtime report](progressive-compression-native-2026-10-11.md)
records the subsequent AAC EOF fix, nine native passes/one skip and a remaining
Windows JVM filesystem-verification failure (647/648 passed). Those reports
supersede the earlier verification below.

Workspace: `C:\git\TranSlander\.worktrees\progressive-archival`, branch
`feat/progressive-archival`. One implementer; no commits, pushes, new worktrees,
blanket cleans or native-source preparation. Later runtime testing used only the
explicitly delegated parent emulator, handed back running. Existing reviewed
toolchain/import/archival changes were retained. `.native-cache` was not edited.

## Implemented paths

- Off-by-default compression and a collapsed quality section. M4A/AAC-LC defaults
  to 16 kHz mono / 96 kbps. Tuple validation includes the AAC-LC `6 * rate * channels`
  ceiling, platform encoder capabilities, mono 8/16-kHz AMR with codec-defined
  rates, and uncompressed WAV. The requested native/compression explanatory strings
  are in `res/values/compression_strings.xml`.
- One AudioRecord provides actual requested capture samples. Unsupported capture
  initialization falls back before starting the source clock. Non-native capture
  is independently normalized to 16-kHz mono for ASR; native-default samples bypass
  conversion. Input blocks and queue budgets use the actual rate/channel tuple.
- `ENCODE_AUDIO` is a durable archival operation using the existing job ID,
  generation, cancellation epoch, owner tombstone, lease, worker and foreground
  control infrastructure. Private encoding is queued without an external folder.
- The low-priority `:archive` worker reads the growing disk spool and continuously
  drains MediaCodec before capture closes. ASR and model availability do not gate
  encoding. Encoder/finalization completion is not awaited by MicrophoneSession.
- Capture uses 30-second PCM parts when encoding, with separate normalized ASR
  parts only for non-native input. Disabled compression keeps the original hourly
  WAV path. Explicit non-native WAV uses hourly actual-format PCM parts.
- A retained uncompressed import is independently decoded/encoded in the worker.
  Its derivative PCM producer throttles around a two-part disk backlog. Original
  ASR continues from its leased original input. Metadata-classified compressed or
  unknown imports never enter the encoder; the worker rechecks `audio/raw` too.
  Explicit Keep of an existing uncompressed microphone recording can also enqueue
  conversion. Original imported files outside the app are never owned/deleted.
- Force stop, timeout and codec failure preserve capture and switch the active
  spool to PCM preservation. A prior encoded journal is never truncated/re-encoded
  on retry. Ordinary retries report preserved mixed input rather than restarting
  a cancelled/partially completed codec stream.
- A failed encoder keeps durable `PENDING_FALLBACK` work until capture closure and
  required COPY scheduling commit. IO failure stays visible to ordinary Retry;
  retry executes parent-side recovery, never the stopped codec. Explicit Force Stop
  invalidates this work through the original epoch barrier too.
- A successful retained output is one ordinary M4A/3GP file. The existing managed
  folder pipeline can copy it, publish its exact URI, then release private staging.
  New output MIME/rate/channels/duration/source-frame metadata is explicit; a
  microphone recording does not become an “import” merely because it is encoded.
- Playback, seeking, export/share and re-transcription handle a compressed prefix
  plus PCM tail after interruption. Prefix repair is a local, codec-free remux on
  first access. Source clocks partition the recording without overlapping parts.
- Successful encoder completion does **not** settle an ASR processing obligation.
  Failed/interrupted original ASR retains unprocessed PCM for recovery; a leased
  retry prefers that tail. Once original processing succeeds, bounded background
  cleanup removes redundant PCM/journal data. Non-native normalized ASR copies are
  separately removable, including in uncompressed WAV mode.

## Durability, source clocks and bounded work

### Private layout within an audio owner's directory

| File | Owner / contract |
|---|---|
| `rolling.properties` | Immutable actual format, part-frame interval and optional original import/legacy-PCM descriptor |
| `capture-N.wav` | Original-format PCM; absolute start is `N * partFrames` |
| `audio-N.wav` | Normalized 16-kHz mono ASR staging for non-native live capture only |
| `capture.properties` | Capture-written frame watermark and closed flag; data is synced before periodic publication |
| `audio.frames` | One continuous codec stream, length/PTS/frame-count/CRC framed; each packet is at most 64 KiB |
| `codec.properties` | Actual codec configuration and priming offset, durable before reclaimable packet checkpoints |
| `encoded.properties` | Worker-written durable journal-byte limit and conservative source-frame watermark |
| `preservation-stop.properties` | Immutable parent-written stop fence and warning |
| `reclamation.properties` | Single-reclaimer source boundary: parent for live capture, import worker for derivative PCM |
| `preservation.properties` | Read-only legacy stop/cursor state; never rewritten by current parent/worker paths |
| `recovered-prefix.m4a` / `.3gp` | Atomically installed remux of exactly the reclaimed source interval; raw tail remains separate |
| `pcm-retired.properties` | Publication/consumer-completion fence before bounded final PCM cleanup; new readers cannot select partly cleaned original files |
| `normalized-cleaned.properties` | Completion of derivative ASR-spool cleanup |

The worker syncs journal bytes before publishing `encoded.properties`. The parent
requires a nontruncated journal, completed source parts, committed ASR progress
minus the complete configured recognition/context window, and durable encoded
coverage before reclaiming. It commits the prefix boundary before unlinking each
part. Reclamation is limited to 32 parts per pass. A missing/stalled recognizer
keeps the original disk backlog; there is no fixed-disk-size promise.

`AudioMetadata` syncs metadata file contents before atomic replacement and syncs
the directory entry on Android before reclamation. It also protects initial rolling
ownership and final PCM-retirement markers. The ordinary legacy `HistoryMetadata`
atomic rename was not sufficient for that boundary. Windows JVM tests exercise the
atomic-file logic; production Android uses `Os.fsync` on the directory descriptor.

Capture finalization uses its writer-owned frame/reclamation snapshot, not optional
codec metadata reads. A damaged codec checkpoint cannot strand the capture's active
owner or block its ordinary finalization/deletion. Recovery reconstructs absolute
source time rather than summing only surviving WAV sizes. A crash at an exact part
boundary can leave its preceding WAV header unclosed; part preparation repairs that
header outside history locks before playback, decoding or copying.
An ended capture whose EOF metadata write failed keeps its finalized writer-owned
source count. Fallback commits that exact capture closure before queueing copies,
publishing their URIs or unlinking private PCM; a stale periodic watermark cannot
shorten the retained layout. Failed repair blocks publication/unlink and preserves
private input. Restart can also repair interrupted capture; the coordinator stops
an executing/queued live encoder from waiting on missing EOF indefinitely. Restart
after encoded publication but before primary ASR finalization keeps the full
absolute sample count, including reclaimed PCM.

`DiskIndexedAudioMp4` avoids MediaMuxer's growing in-memory sample index. It uses
bounded journal passes, one contiguous `mdat` chunk, one `co64` offset, a streamed
`stsz` table, run-length `stts` durations and a version-one edit list. Physical
frame durations retain padding; the edit list identifies the source interval so
Android can derive priming and padding correctly. Payload
and the final sample table necessarily grow on disk; their index is not retained
as a heap list. There is an explicit sample-table size guard at extreme durations;
the codec stops before publishing a checkpoint beyond that bound, retaining a
remuxable journal prefix and PCM tail instead of truncating audio. Automatic splitting
at that extreme MP4 table limit is not implemented.

Encoding retains one codec, one packet buffer, one input block and small metadata.
No chunk descriptors or PCM arrays travel over Binder. Each execution carries one
input/config PFD and a small validated private-path descriptor; the worker opens
one source part at a time. Existing durable scheduling remains a 32-entry LRU.
Imported conversion adds one decoder coroutine in the same worker process. Decoder
and encoder native allocations remain platform-owned and require device profiling.

Private targets reuse journal publication/cleanup with exact `file:` identities;
only encoding descriptors may use a private destination. Lost private receipts
resolve only the recorded filename, and an absent private target can retire after
worker quiescence. The provider-specific delayed-creation ambiguity is retained
for external document providers. The encoded journal itself is never registered
as a disposable partial output.

## APIs and integration points

Paths below are relative to `app/src/main/java/io/github/lrq3000/utterlane/`.

- `archive/AudioQuality`, `PcmFormat`, `AudioEncoderCapabilities`: immutable request,
  native-format identity, codec/tuple selection and actual-format metadata.
- `archive/RollingPcmJournal`: `append`, `closeCapture`, `commitAsr`,
  `checkpointEncoded`, `reclaim`, `preservePcm`, `recoverCapture`, `captureLayout`,
  `commitFinalizedCapture`. The last method repairs failed EOF metadata from the
  finalized writer's exact source-frame count after writers are quiescent.
  `checkpointEncoded` is called only after `EncodedJournal.Writer.checkpoint()`.
- `archive/EncodedJournal`: bounded continuous packet writer/reader with an explicit
  committed byte limit; refuses to overwrite a nonempty encoded prefix.
- `archive/ProgressiveAudioEncoder` / `ImportedAudioEncoder`: worker-only codecs.
  AAC priming is now measured by `AacPrimingCalibration`, not inferred from a key.
- `archive/DiskIndexedAudioMp4` / `RollingRecovery`: codec-free finalization/repair.
- `ArchivalCoordinator.Producer.encode(id)`: queue live retained encoding using
  the producer epoch. `Producer.audio(id)` also handles eligible completed PCM
  conversions and snapshots the existing external destination plan.
- `RecordingHistory.begin(..., quality)`: actual capture snapshot. Active readers
  use original PCM and normalized offsets, not a physical remaining-file-size sum.
- `RecordingHistory.processingAudio(entry)`: leased retry projection preferring
  original unprocessed PCM. `preparePart(entry, index)` performs bounded header
  repair / necessary prefix preparation outside repository locks.
- `RecordingHistory.cleanupPublishedEncoding(budget = 32)`: bounded private cleanup
  on the coordinator's IO drain, outside history locks and ASR completion. It waits
  for original consumers/processing obligations and installs the retirement fence
  before unlinking. Public retained rolling audio uses the part-format decoder;
  the legacy `Reader` is explicitly limited to original active rolling input.
- `AudioPart`: actual MIME, rate/channels, normalized count and source-frame interval.
  Unknown legacy/import rate/channel fields are `0`, not invented native metadata.
- `AudioDecoder.decodePart`: source-count limiting and single application of owned
  edit-list priming. `AudioTimeline` supports a variable-length prefix and uniform
  tail parts using scalar metadata.

Foreground ownership is still acquired at the user/visible start boundary. Encoding
adds `mediaProcessing` on API 35+, justified `specialUse` on API 34, and the older
local-file-processing `dataSync` type below that. Transfers retain `dataSync`; timeout
and stop-all still stop only the verified archive process. No background-start
exemption is inferred from a later ASR completion callback.

## Verification

### JVM / build

Meaningful RED phases observed included absent rolling reclamation, missing private
encoding jobs, missing import conversion, per-channel conversion, missing/truncated
encoded checkpoint reclamation, exact-boundary WAV repair, deletion of unprocessed
recovery PCM, redundant normalized WAV retention, private lost-receipt reconciliation,
and optional codec metadata breaking capture finalization. They were followed by
focused GREEN checks. The existing native-default recording pipeline tests remain
part of the full verification.

**Pre-review verification: 605 JVM tests passed, zero failures and zero skipped**
(576 supplied baseline + 29 new tests). Debug and Android-test APK builds passed.
The final normal full command completed **BUILD SUCCESSFUL in 2m 22s**, 101 tasks
(13 executed, 88 up-to-date), after the diagnosed targeted compiler rebuild.
The test report records 44.912 seconds of JVM test time. `git diff --check` passed.
Commands use this worktree and the 600000-ms tool timeout:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*archive*Test" --tests "*AudioQualityPreferencesTest" --tests "*RecordingReaderTest" --tests "*AudioTimelineTest" --tests "*StreamingResamplerTest" :app:assembleDebugAndroidTest "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests "*archive*Test" --tests "*AudioQualityPreferencesTest" --tests "*CaptureFormatBudgetTest" --tests "*RecordingPipelineTest" --tests "*RecordingReaderTest" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:assembleDebug "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
```

The known Compose-generated ABI mismatch recurred: the XML failure named missing
`RecordingHistory$Recording.$stable`, and `javap` confirmed the absent generated
field. Only after diagnosis, the authorized targeted workaround was used:

```powershell
& "C:\Program Files\Java\jdk-21\bin\javap.exe" -p -classpath "app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes" 'io.github.lrq3000.utterlane.history.RecordingHistory$Recording'
.\gradlew.bat :app:compileDebugKotlin "-Pkotlin.incremental=false" "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
```

The normal full command was then rerun. No native-source/cache cleaning was used.
Gradle's ordinary native configure/build graph ran; merge/copy/strip native outputs
remained up-to-date. Existing compiler/deprecation warnings remain.

New JVM coverage:

| Suite | Tests |
|---|---:|
| AudioQualityTest | 3 |
| RollingPcmJournalTest | 5 |
| EncodedJournalTest | 3 |
| RollingHistoryTest | 4 |
| EncodingOwnershipTest | 4 |
| ArchivePcmConverterTest | 2 |
| ImportedEncodingTest | 2 |
| RollingRecoveryTest | 4 |
| AudioQualityPreferencesTest | 1 |
| CaptureFormatBudgetTest | 1 |

Artifacts:
- `app/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

### Host container evidence

A generated 16-kHz mono AAC fixture with **64,317 source samples** was packetized
into the actual production `EncodedJournal` and remuxed by the production writer
from a temporary Java harness. FFprobe reports AAC-LC, 16000 Hz, one channel,
`time_base=1/16000`, `duration_ts=64317`, `duration=4.019813`, and 64 encoded packets.
FFmpeg decodes it without container errors. This verifies container structure and
duration, not Android codec behavior or decoded sample trimming on every player.

The initial fragmented-container prototype reported a padded duration in that same
check and was removed in favor of the streamed conventional sample table. The
temporary fixture/harness are under `C:\Users\33632\AppData\Local\Temp\opencode`
(`ProgressiveContainerCheck.java`, `progressive-indexed.m4a`), outside this worktree.

## Parent native QA and acceptance blockers

The original `ProgressiveCompressionAndroidTest` had four compiled runtime tests;
the [review handoff](progressive-compression-review-2026-10.md#parent-native-qa)
lists the current ten independent cases and links their API 28 runtime results:

1. Durable real MediaCodec output while the source remains open, reclamation before
   Stop, real track MIME/rate/channels, exact source count and a distinct 317-frame
   final-tone correlation check after priming/padding trimming.
2. Real isolated-worker IPC, durable output before source close, verified worker
   stop, continued PCM writes and decoding all mixed recovery parts without missing
   or duplicated source samples.
3. Independent uncompressed-WAV conversion, unchanged original bytes and decoded
   output count.
4. Supported 44.1-kHz stereo AAC and 8/16-kHz AMR tuples, actual container track
   metadata and final-tail counts. Unsupported optional tuples use the same capability
   filter as Settings.

Use the parent's designated **isolated QA installation with no unrelated archival
queue**; the worker-stop test intentionally terminates that installation's archive
worker. With the default application ID:

```powershell
adb -s <QA_SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <QA_SERIAL> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <QA_SERIAL> shell am instrument -w -e class io.github.lrq3000.utterlane.ProgressiveCompressionAndroidTest io.github.lrq3000.utterlane.test/androidx.test.runner.AndroidJUnitRunner
```

Then exercise a real 90-second microphone run with a working model and compression
on. Before Stop, inspect `audio.frames`, the encoded checkpoint and removal of
older `capture-N.wav` parts. Repeat with model unavailable/slow: encoding must
advance but unprocessed PCM must stay. Force-stop archival mid-capture, keep talking,
then replay/re-transcribe/export across the prefix/tail boundary. Start another
microphone operation while the previous archive remux/copy is draining.

The API 28 software-codec suite now has actual evidence in the native report.
Broader acceptance still needs:
- AAC calibration, edit-list handling and final-tone survival. A failed measured
  calibration preserves PCM; a missing delay key or nonnegative encoder PTS alone
  no longer causes fallback. The capability query alone cannot establish timing.
- AMR output framing and 3GP playback/seek on the designated Android versions.
- Real non-native AudioRecord input/fallback, waveform timing, route loss, and settings
  snapshots while a capture is active.
- API 34/35+ foreground types, denial/timeout, provider failure, and process death at
  checkpoint/publication/cleanup boundaries.
- Sustained capture/ASR resource contention and the platform decoder's own native
  allocations on long recordings. No device performance claim is made here.

English resources await the project's normal translation batch. The extreme MP4
sample-table guard preserves recovery but does not split into multiple final encoded
containers. Mixed recovery may require a linear journal scan to remux its prefix
on first use. These are explicit limits, not claims of instant repair or unlimited
physical storage.

## Files changed in this milestone

New production files under `archive/`: `AudioMetadata.kt`, `AudioQuality.kt`,
`AudioEncoderCapabilities.kt`, `AudioQualitySection.kt`, `ArchivePcmConverter.kt`,
`RollingPcmJournal.kt`, `RollingAudio.kt`, `RollingRecovery.kt`, `EncodedJournal.kt`,
`DiskIndexedAudioMp4.kt`, `ProgressiveAudioEncoder.kt`, `ImportedAudioEncoder.kt`.

Integration changes: `archive/ArchivalSettings.kt`, `ArchiveRecords.kt`,
`ArchivalJobStore.kt`, `ArchivalCoordinator.kt`, `ArchiveWire.kt`,
`ArchiveWorkerService.kt`, `ArchiveSupervisorService.kt`, `SafArtifacts.kt`,
`ManagedMarkdown.kt`; `asr/AudioCapture.kt`, `AudioRecorder.kt`,
`CaptureReadLoop.kt`, `CaptureBufferPolicy.kt`, `BoundedAudioQueue.kt`,
`RecordingPipeline.kt`, `MicrophoneSession.kt`, `TranscriptStore.kt`;
`history/AudioAsset.kt`, `RecordingHistory.kt`, `WavFile.kt`;
`home/HomeCaptureSpool.kt`; `settings/SettingsRepository.kt`, `SettingsActivity.kt`;
`transcribe/AudioDecoder.kt`, `AudioTimeline.kt`, `AudioPlaybackController.kt`,
`DialogAudioActions.kt`, `TranscriptionDialogModel.kt`; and the main manifest.

New resources: `res/values/compression_strings.xml`. New tests:
`archive/{AudioQuality,RollingPcmJournal,EncodedJournal,RollingHistory,EncodingOwnership,ArchivePcmConverter,ImportedEncoding,RollingRecovery}Test.kt`,
`settings/AudioQualityPreferencesTest.kt`, `asr/CaptureFormatBudgetTest.kt`, and
Android `ProgressiveCompressionAndroidTest.kt`. User-facing documentation changes:
`docs/user-guide.md`, `PRIVACY_POLICY.md`, and this report.
