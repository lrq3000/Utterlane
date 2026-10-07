# Parallel Capture Implementation Plan

> Execute inline in the existing isolated worktree, task by task, using the
> executing-plans workflow. No subagents are required.

**Goal:** Record while the model loads, continue recording after recognition
failure, and recover the original audio after Stop.

**Architecture:** Separate capture/writing from the recognition consumer in a
shared coroutine coordinator. Reuse RecordingHistory's bounded split-WAV IO for
retained and temporary recordings; transfer failed recordings to an app-owned
recovery registry consumed by the existing transcription screen.

**Tech stack:** Kotlin, coroutines, Android AudioRecord, existing native worker,
JUnit and Android instrumentation, native recording panel and Compose settings.

## 1. Reproduction and build baseline

- [x] Add `app/src/androidTest/java/io/github/lrq3000/utterlane/ParallelCaptureAndroidTest.kt`
  using a deterministic AudioCapture and an unavailable selected model. Assert
  `started.await(3, TimeUnit.SECONDS)` before Stop; the old lifecycle must fail.
- [x] Prepare this worktree's pinned native sources and source-built sherpa AAR.
- [x] Build with `gradlew.bat --console=plain testDebugUnitTest assembleDebug assembleDebugAndroidTest`
  in normal incremental mode and run the reproduction on the available adb target.

## 2. Independent lifecycle

- [x] Add `asr/MicrophonePipeline.kt` with `Audio` (append/read/sample count),
  `Consumer` (accept/finish/close), and `Failure` (PREPARATION/INFERENCE/AUDIO/STORAGE)
  boundaries. Catch recognition failures inside the consumer coroutine, not around
  the whole capture scope. All readers consume published successful writes.
- [x] Add JVM `asr/MicrophonePipelineTest.kt` covering gated preparation, failed
  preparation/inference, byte-for-byte input preservation, Stop, cancellation, and
  storage failure. Use latches/deferred results rather than native models.
- [x] Delegate MicrophoneSession's capture/writer/consumer coordination to this
  class while preserving active-session arbitration, diagnostics, power ownership,
  and caller cleanup callbacks.

## 3. Recording and recovery ownership

- [x] Add `history/MicrophoneRecordings.kt` wrapping RecordingHistory for retained
  and separate temporary recordings. Capture owns a lease until completion or
  transfer to a recovery entry; temporary disposal deletes the entry.
- [x] Test temporary recordings are absent from retained history, reads are exact,
  cleanup respects an active reader, and recovery survives the settings handoff.
- [x] Initialize the repository in UtterlaneApp; add optional recovery identity to
  SessionFailure. The shared factory presents recovery after completion.

## 4. State and UI

- [x] Add independent preparation/failure status to CaptureSnapshot and update
  RecordingPanel so a live microphone remains stoppable during loading/failure.
- [x] Extend TranscribeActivity with app-owned recovery identity, an initial error
  screen, retry, model-selection navigation, and explicit recovery disposal.
- [x] Add a Settings entry for pending recordings and a model-selection intent
  handled on creation and subsequent intents. Preserve the recording while away.
- [x] Test loading/listening and error/retry states, including returning from
  Settings, with Android instrumentation.

## 5. Documentation and final verification

- [x] Update privacy policy, user/developer guides and default strings for temporary
  buffering, loading independence, and retry behavior. Defer locale batches per
  AGENTS.md.
- [x] Run focused new/related tests, debug build and instrumentation APK build.
  Inspect failures before broadening checks; avoid clean/forced rebuilds.
- [x] Review the diff against every approved design requirement and record exact
  verification results and any device limitations. Publication is a separate
  user-directed action.

Results and reproducible commands: [QA report](../../qa/parallel-capture.md).
