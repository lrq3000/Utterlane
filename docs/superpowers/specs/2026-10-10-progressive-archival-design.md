# Progressive audio archival and managed transcript folders

## Approval and baseline

The user approved implementation after iterative design on 2026-10-10 and chose
the isolated worktree `.worktrees/progressive-archival`. The branch starts at
`f6975ce`, including the newer dialog-exit/recovery preservation behavior. Existing
untracked audio fixtures in the main checkout belong to the user.

## Product contract

### Imported audio

- Home, Share, Open With, and monitored-file entry points share one import policy.
- Imported compressed audio is always copied byte-for-byte, even with compression
  enabled; never re-encode it. Inspect media metadata, not just a suffix.
- The originating application's file/URI is never owned, modified, or deleted.
- With automatic audio history enabled, accepted imports become ordinary unpinned
  history entries. Viewing, navigation, replacement, and dismissal do not erase a
  retained entry. Existing retention and explicit deletion still apply.
- With history disabled or retention declined, retain only temporary processing/
  recovery ownership. Successful completion and owner release clean it up; actual
  interruption preserves useful recovery under existing privacy/discard rules.
- A known duration over 600000 ms prompts for retaining audio. Otherwise an
  unknown/short duration with size over 52428800 bytes (50 MiB) prompts for size.
  At the exact thresholds no warning applies. History off never prompts.
- Each warning category independently supports “Remember my choice and do not
  ask again”, persisting KEEP or DISCARD. Settings can reset those decisions.
- Ask at most once per import. Do metadata work off Main and before a large copy
  when possible; unknown values are not fabricated. Preserve the current result
  until an import really owns nonempty input.

### Optional saved-audio compression

- “Compress saved audio” defaults off. Microphone capture/archive defaults to
  PCM16 WAV at 16000 Hz mono, the existing recognition-native format.
- Native description: “Native recognition format — 16 kHz mono PCM. Microphone
  samples can feed transcription and WAV storage directly, without resampling,
  channel conversion, or audio encoding. Consumes more storage if audio history
  is enabled.”
- The quality section starts collapsed. Compression on reveals format, sample
  rate, bitrate, and channels. WAV represents uncompressed operation.
- M4A/AAC defaults to 16000 Hz mono at 96000 bps (AAC-LC cannot actually deliver
  192000 bps at this native mono sample rate). Choices: 8000, 16000, 22050, 32000,
  44100, 48000 Hz; 48000, 96000, 128000, 192000, 256000, 288000 bps; mono/stereo.
  Validate combinations against format and the selected device encoder. The
  Dimowner-style 3GP/AMR mode is mono at 8000/16000 Hz with codec-defined bitrate.
- Explain that compression consumes processing time, may contend with ASR/
  diarization, and 192 kbps is nominally only 25% smaller than native PCM16. Append
  exactly: “May worsen re-transcriptions accuracy.”
- Snapshot settings per operation. Distinguish requested from supported/actual
  format. Non-native quality must be genuine capture where requested and supported,
  not pretend stereo or improved fidelity obtained by upsampling old mono PCM.

### Progressive capture and recovery

- Keep inference fed by original uncompressed PCM, with unchanged native default
  sample values/order, ownership windows, overlap and EOF/final speaker draining.
- Encoding has independent lifetime, failure handling, cursor, and priority.
  Source PCM is not first compressed/decompressed for the active recognition.
- Use a rolling disk recovery/backlog spool with bounded memory. Reclaim a portion
  only after required consumers advance AND its retained replacement is durable/
  recoverable. Usually only compressed output remains at successful completion.
- A slower/unavailable recognizer can require a large disk PCM backlog; no false
  constant-disk guarantee or silent sample dropping. Encoding failure/force stop
  falls back to retained PCM while healthy capture/transcription continue.
- In-progress M4A requires recoverable encoded checkpoints/indexing: ordinary
  MediaMuxer finalization alone is not sufficient for process-death recovery.
- Recognition completion must not await archival finalization. Subsequent capture,
  transcription and navigation remain available while archival drains.

### Background archival ownership

- Parent owns durable jobs, settings snapshots, exact source/destination identities,
  publication/discard markers and leases; an isolated `:archive` worker does codec
  and provider IO. Do not initialize app-wide model/history stores in that worker.
- Bound work/concurrency and give capture, UI, transcription and diarization
  priority over optional archival. Defer/throttle optional work before data loss.
- Maintain an ongoing foreground-service notification while executing/draining
  archival work. Include “Force stop all archival jobs”; remove notification after
  completion or cancellation. Android notification visibility rules still apply.
- Stop-all persists cancellation of current running/queued jobs, rejects stale
  callbacks, requests shutdown and can terminate the worker process if hung. It
  does not kill the app/recognizer or stop microphone capture. Cancelled jobs do
  not auto-resurrect; new user operations can enqueue new jobs.
- Preserve sole authoritative encoded checkpoints/PCM on cancellation. Delete only
  disposable partial derivatives. Handle service timeouts without losing sources.
- Publication is staged, verified and committed before reclaiming the source.
  External-provider IO never happens while holding repository publication locks.

### Audio destination and Markdown

- Use anggrayudi SimpleStorage for separate audio and transcript folder selectors,
  persisted grants and document access. Default audio destination stays private.
- Private recovery staging remains local. Successful publication to the chosen
  audio destination becomes the retained audio (not a permanent second copy).
- Store URIs, not guessed physical paths. Playback, re-transcription, sharing,
  retention and deletion support existing private and new external assets.
- Folder changes apply to new jobs; existing entries keep their exact locations.
  Missing/revoked/removable storage preserves local source and shows truthful state.
- Optional automatic Markdown saving and a manual save action write the COMPLETE
  disk-backed transcript, using bounded streaming, independently of ASR/UI.
- Filename uses recording/import datetime and model actually used, e.g.
  `2026-10-10_09-30-15_Parakeet-v3.md`; sanitize names, avoid overwrite and deduplicate
  retried jobs. Body preserves text, paragraphs and speaker labels with readable
  date/model metadata. Failed/incomplete work must not masquerade as complete.
- Markdown files are managed transcript-history artifacts, linked by stable ID.
  Existing transcript Delete/prune deletes linked files; pin/Forever protects them.
  Audio-only deletion preserves them. No extra deletion menu/confirmation is added.
- Register exact owned URIs durably, retaining links after folder/setting changes.
  Persist deletion intent before cancelling publication; never resurrect a deleted
  transcript from late work. Keep pending cleanup durable when permissions prevent
  physical deletion, retry idempotently, never delete unrelated files by name.

## Toolchain

Validate Kotlin/Compose compiler 2.4.21, AGP 9.2.1, Gradle 9.4.1, compileSdk 37,
SimpleStorage 3.1.0; retain targetSdk 36, minSdk 26, JDK 21 and NDK 28.2.13676358.
Migrate AGP built-in Kotlin/DSL/source sets in the app and sherpa helper; update CI
and distribution prerequisites. Actual dependency resolution/build evidence decides
compatibility, not publication version numbers alone.

## Verification

- Baseline JVM command: `./gradlew.bat :app:testDebugUnitTest
  "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=2 --console=plain
  --offline` succeeded on the unchanged worktree before implementation.
- Focused meaningful JVM regressions for policies, lifetime/leases, safe publication,
  deletion races, rolling cursors, restart/cancellation, settings and filenames.
- Android codec/SAF/foreground/process tests plus normal `assembleDebug`; release
  shrinking/JNI packaging checks for the toolchain migration and distribution
  verification required by repository instructions, reporting environment blockers.
- Reuse accuracy/window/final-tail fixtures; preserve source PCM entering ASR.
- No elaborate performance suite. If implementation can affect speed materially,
  use one matched existing workload before/after with the same target/model/options.
  Investigate further only if it reveals a meaningful regression.
- Incremental builds; no clean/forced reruns. Defer translation batch until stable.

## Reference evidence

- Kotlin: https://kotlinlang.org/docs/gradle-configure-project.html
- AGP: https://developer.android.google.cn/build/releases/agp-9-2-0-release-notes
- SimpleStorage: https://github.com/anggrayudi/SimpleStorage
- Dimowner encoder/recovery reference:
  https://github.com/Dimowner/AudioRecorder/tree/master/app/src/main/java/com/dimowner/audiorecorder/v2/audio
