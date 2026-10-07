# Recording-first Backpressure Implementation Plan

> **For agentic workers:** Use executing-plans inline. The user approved implementation; no delegation or publication was requested.

**Goal:** Preserve live input while models load or fall behind, honor enabled speaker labeling, and reduce redundant presentation and file IO.

**Architecture:** Private PCM storage is the producer/consumer boundary. Model preparation and ordered ASR/diarization run alongside capture. Recovery flags outlive processing failure; bounded UI snapshots are independent of input preservation.

**Tech Stack:** Kotlin, coroutines, Android AudioRecord, app-private WAV files, DataStore, Compose/native views, JUnit and Android instrumentation.

**Execution status:** Implemented and verified on 2026-10-07. The checklist below
is the original execution recipe; final results, adjustments, commands and limits
are recorded in [the QA report](../../qa/recording-first-backpressure.md).

## File ownership

- `asr/MicrophoneSession.kt`: Android lifecycle and callbacks.
- `asr/RecordingPipeline.kt`: bounded handoff, persistence publication, preparation/processing isolation.
- `history/RecordingHistory.kt`, `history/WavFile.kt`: recovery lifecycle and sequential reader.
- `asr/CaptureMetrics.kt`, `asr/WaveformHistory.kt`: exact counters and incremental presentation.
- `settings/SettingsRepository.kt`, `settings/VisualRefreshSetting.kt`: refresh preference and editor.
- `ui/RecordingPanel.kt`, `transcribe/TranscribeActivity.kt`: presentation and recovery integration.
- Existing history settings/recovery surfaces and privacy/user-guide docs describe temporary audio retention.

All Kotlin paths above are beneath `app/src/main/java/io/github/lrq3000/utterlane/`.
Tests live under the matching `app/src/test/java/` packages, with Android integration
under `app/src/androidTest/java/io/github/lrq3000/utterlane/`.

## Task 1: Reproduction and storage foundation

- [ ] Run focused baseline: `./gradlew.bat :app:testDebugUnitTest --tests '*Capture*Test' --tests '*HistoryTest' -Pkotlin.compiler.execution.strategy=in-process --console=plain -q`.
- [ ] Add failing recovery tests: begin a temporary recording with history off,
  append `[1,2,3]`, finish with failure, prune with `NONE`, reopen the history and
  assert the same PCM is available. Successful temporary completion must remove it.
- [ ] Persist recovery state at recording creation, exempt unresolved recordings
  from automatic pruning, and clear it only on successful transcription. Keep the
  existing `begin(retention)` entry point as the single spool/history creation
  path; `NONE` now means delete after successful processing, as approved.
- [ ] Add sequential-reader tests for following newly appended audio, part rollover,
  lease-protected deletion, EOF, and use-after-close. Implement one handle per part
  and reusable bounded byte storage.
- [ ] Run the focused history tests and review the diff.

## Task 2: Recording-first lifecycle

- [ ] Add blocked-preparation and slow-consumer tests. Capture must complete and
  persist input while preparation is blocked; subsequent drain must equal the
  original PCM exactly. Inject preparation/inference exceptions and ensure capture
  and persistence continue until Stop. Exercise cancellation and writer failure.
- [ ] Encapsulate capture, writer and processor as independently failing stages.
  Keep the processor's error as session outcome; do not propagate it into healthy
  capture. Reader waits on a conflated saved-length notification, not queued PCM.
- [ ] Integrate into `MicrophoneSession`, retaining cleanup, ownership, diagnostics,
  text append/delivery and wake behavior. Stop stops capture only; cancellation
  finalizes the spool as unresolved. Preserve the triggering overflow block in
  bounded handoff storage while draining, rather than silently rejecting it.
- [ ] Show loading/failed recognition separately from capturing. Ensure model
  recovery has a Settings/model-selection action and saved-audio recovery action.
- [ ] Run pipeline/history tests plus existing session and speaker-finalization tests.

## Task 3: Presentation and IO optimization

- [ ] Add refresh tests proving repeated PCM calls before a refresh deadline do not
  allocate/publish waveform snapshots; terminal transitions flush exact counts.
- [ ] Use fixed sample-count energy buckets and a bounded ring, with visual
  publication governed by a monotonic deadline. Preserve immediate signal changes.
- [ ] Add persistent validated 1/2/5/10/20 Hz Appearance setting, default 10 Hz.
  Keep it independent of inference options and diagnostics consent.
- [ ] Replace per-update UI coroutine creation with latest-value state consumed by
  one owner. Preserve final publication and cancellation lifetime.
- [ ] Reuse sequential WAV readers in live backlog and history retranscription.
- [ ] Run metrics, setting persistence and lifecycle regression tests.

## Task 4: Recovery UX and verification

- [ ] Show unresolved recordings in the history/recovery UI even when retention is
  disabled. Keep retry/export/delete available and refresh listings after a retry.
- [ ] Finish recovered recordings only after all transcript and speaker tails have
  been stored; use current retention for successful completion.
- [ ] Update English resources and user/privacy documentation together. Follow the
  repository workflow deferring other locales until release stabilization.
- [ ] Run `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest -Pkotlin.compiler.execution.strategy=in-process --console=plain -q` once incremental focused tests pass.
- [ ] Run scoped lifecycle/UI instrumentation on a dedicated QA identity/target;
  report any environment limits and quantitative presentation-work reduction.
- [ ] Review `git diff --check`, the complete diff and status. Leave intended source,
  tests and docs together for review. Commit/push only if explicitly requested.
